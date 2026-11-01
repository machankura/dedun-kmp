package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The one payment the group's Lightning wallet authorises: a member's own balance, to their own
 * address. What a signer refuses -- one mutation per check. `docs/payouts-only.md`, Phase 6.
 */
class GroupLightningPayoutsJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val aliceKey = key("a11ce")
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val bob = key("b0b").xOnlyPublicKey().value.toHex()
    private val payee = PrivateKey(randomBytes32())
    private val address = LightningAddress("alice", "example.com")
    private val metadata = """[["text/plain","Pay alice"],["text/identifier","alice@example.com"]]"""

    private fun file(statement: Event) = runBlocking {
        val tags = arrayOf(arrayOf("h", room))
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = EventHasher.hashId(statement.pubKey, statement.createdAt, GroupPayoutDestinationEvent.KIND, tags, statement.toJson()),
                publicKey = statement.pubKey, kind = GroupPayoutDestinationEvent.KIND, createdAt = Instant.fromEpochSeconds(statement.createdAt),
                tags = tags, content = statement.toJson(), chatRoomId = room,
            )
        )
    }

    /** Alice, with a destination and her deposits opened, and [depositedMsat] in over Lightning. The destination's id. */
    private suspend fun alice(depositedMsat: Long = 50_000_000): String {
        signingRoom(db, group, members = listOf(alice, bob), holdsShare = false)
        val destination = GroupPayoutDestinationEvent.build(aliceKey, room, chain, address, createdAt = 1_700_000_000)
        file(destination)
        val template = DepositorStatementEvent.template(room, GroupPayoutDestinationEvent.verified(destination) as GroupPayoutDestination, 0, 1_700_000_100)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signedAsRoom(group, template.kind, template.tags, "", 1_700_000_100), chatRoomId = room))
        db.depositDao().upsert(
            DepositRow(chatRoomId = room, chain = "regtest", depositKey = "ln:" + "ab".repeat(32), source = DepositRow.LIGHTNING, amountMsat = depositedMsat, member = alice, attributedBy = DepositRow.BY_INVOICE)
        )
        return destination.id
    }

    private fun pay(amountMsat: Long, maxFeeMsat: Long, payout: GroupLightningAuthorisationEvent.Payout?, lnurl: String? = null, describing: String? = lnurl): GroupLightningAuthorisationEvent.Pay {
        val description: Either<String, ByteVector32> = describing?.let { Either.Right(Crypto.sha256(it.encodeToByteArray()).byteVector32()) } ?: Either.Left("payout")
        val invoice = Bolt11Invoice.create(chain, amountMsat.msat, randomBytes32(), payee, description, CltvExpiryDelta(144), INVOICE_FEATURES)
        return GroupLightningAuthorisationEvent.Pay(chain, 0, invoice.write(), amountMsat, maxFeeMsat, invoice.paymentHash.toHex(), Clock.System.now().epochSeconds + 3_600, payout, lnurl)
    }

    private fun proposed(pay: GroupLightningAuthorisationEvent.Pay) =
        Event("", room, 1_700_000_500, GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(pay).tags, "", "")

    private suspend fun refused(pay: GroupLightningAuthorisationEvent.Pay) = GroupLightningPayouts.refuse(db, room, proposed(pay))

    @Test
    fun `a payout of a member's balance to their own destination is signed`(): Unit = runBlocking {
        val destination = alice()
        assertNull(refused(pay(40_000_000, 400_000, GroupLightningAuthorisationEvent.Payout(alice, destination), metadata)))
        // Without the metadata too: older proposers sent none.
        assertNull(refused(pay(40_000_000, 400_000, GroupLightningAuthorisationEvent.Payout(alice, destination))))
    }

    @Test
    fun `a payment naming no member is refused`(): Unit = runBlocking {
        alice()
        assertTrue(refused(pay(10_000_000, 100_000, null))!!.contains("does not name"))
    }

    @Test
    fun `a payout past the member's balance, with its fee limit, is refused`(): Unit = runBlocking {
        val destination = alice(depositedMsat = 10_000_000)
        assertTrue(refused(pay(10_000_000, 1_000, GroupLightningAuthorisationEvent.Payout(alice, destination)))!!.contains("more than"))
        assertNull(refused(pay(9_000_000, 1_000_000, GroupLightningAuthorisationEvent.Payout(alice, destination))))
    }

    @Test
    fun `a payout to a destination the member has replaced or withdrawn is refused`(): Unit = runBlocking {
        val first = alice()
        file(GroupPayoutDestinationEvent.build(aliceKey, room, chain, LightningAddress("alice", "new.example"), createdAt = 1_700_000_200))
        assertTrue(refused(pay(1_000_000, 10_000, GroupLightningAuthorisationEvent.Payout(alice, first)))!!.contains("since replaced"))
        file(GroupPayoutDestinationEvent.buildRemoved(aliceKey, room, chain, createdAt = 1_700_000_300))
        assertTrue(refused(pay(1_000_000, 10_000, GroupLightningAuthorisationEvent.Payout(alice, first)))!!.contains("nowhere"))
    }

    @Test
    fun `metadata the invoice does not commit to, or that names another address, is refused`(): Unit = runBlocking {
        val destination = alice()
        val payout = GroupLightningAuthorisationEvent.Payout(alice, destination)
        assertTrue(refused(pay(1_000_000, 10_000, payout, metadata, describing = "[]"))!!.contains("does not commit"))
        val elsewhere = """[["text/identifier","mallory@example.com"]]"""
        assertTrue(refused(pay(1_000_000, 10_000, payout, elsewhere))!!.contains("does not name"))
    }

    @Test
    fun `a liquidity purchase is refused, and a close is not`(): Unit = runBlocking {
        alice()
        val expires = Clock.System.now().epochSeconds + 3_600
        val buy = GroupLightningAuthorisationEvent.BuyLiquidity(chain, 0, "cd".repeat(32), 200_000, 4_000, expires, "01".repeat(8))
        assertTrue(GroupLightningPayouts.refuse(db, room, Event("", room, 0, GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(buy).tags, "", ""))!!.contains("liquidity"))
        val close = GroupLightningAuthorisationEvent.ChannelAction(GroupLightningAuthorisationEvent.CLOSE, chain, 0, "cd".repeat(32), expires)
        assertNull(GroupLightningPayouts.refuse(db, room, Event("", room, 0, GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(close).tags, "", "")))
    }
}
