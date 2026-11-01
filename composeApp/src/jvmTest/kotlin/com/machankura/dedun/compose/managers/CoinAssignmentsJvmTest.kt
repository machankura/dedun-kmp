package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent.Coin
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.ui.view.model.GroupAssignCoinsViewModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The group saying whose a coin nobody owns is: what a signer refuses, and the shares the screen
 * suggests and checks before anybody is asked. `docs/payouts-only.md`, Phase 3.
 */
class CoinAssignmentsJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val aliceKey = key("a11ce")
    private val bobKey = key("b0b")
    private val carolKey = key("ca201")
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val bob = bobKey.xOnlyPublicKey().value.toHex()
    private val carol = carolKey.xOnlyPublicKey().value.toHex()

    private val legacy = DepositKey.Onchain("31".repeat(32), 0)
    private val onBranch = DepositKey.Onchain("11".repeat(32), 0)

    private suspend fun aGroup() {
        signingRoom(db, group, members = listOf(alice, bob, carol), holdsShare = false)
        listOf(aliceKey, bobKey).forEach { member ->
            val destination = GroupPayoutDestinationEvent.verified(
                GroupPayoutDestinationEvent.build(member, room, chain, LightningAddress("me", "example.com"), createdAt = 1_700_000_000)
            ) as GroupPayoutDestination
            val template = DepositorStatementEvent.template(room, destination, 0, 1_700_000_100)
            db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signedAsRoom(group, template.kind, template.tags, "", 1_700_000_100), chatRoomId = room))
        }
        db.depositDao().upsert(DepositRow(chatRoomId = room, chain = "regtest", depositKey = legacy.text, source = DepositRow.ONCHAIN, amountMsat = 100_000_000))
        db.depositDao().upsert(
            DepositRow(chatRoomId = room, chain = "regtest", depositKey = onBranch.text, source = DepositRow.ONCHAIN, amountMsat = 70_000_000, member = alice, attributedBy = DepositRow.BY_BRANCH)
        )
    }

    private fun proposal(vararg coins: Coin): Event = signedAsRoom(group, CoinAssignmentEvent.KIND, CoinAssignmentEvent.template(chain, coins.toList()).tags, "")

    private val split = Coin(legacy, 100_000, mapOf(alice to 60_000, bob to 40_000))

    @Test
    fun `a legacy deposit shared between depositors is signed`(): Unit = runBlocking {
        aGroup()
        assertNull(CoinAssignments.refuse(db, room, proposal(split)))
    }

    @Test
    fun `a coin for another amount, a coin nobody has seen, or one on a member's branch is refused`(): Unit = runBlocking {
        aGroup()
        assertTrue(CoinAssignments.refuse(db, room, proposal(Coin(legacy, 99_000, mapOf(alice to 99_000))))!!.contains("another amount"))
        assertTrue(CoinAssignments.refuse(db, room, proposal(Coin(DepositKey.Onchain("99".repeat(32), 3), 5_000, mapOf(alice to 5_000))))!!.contains("not seen"))
        assertTrue(CoinAssignments.refuse(db, room, proposal(Coin(onBranch, 70_000, mapOf(bob to 70_000))))!!.contains("own branch"))
    }

    @Test
    fun `a share for somebody the group has not opened deposits for is refused`(): Unit = runBlocking {
        aGroup()
        assertTrue(CoinAssignments.refuse(db, room, proposal(Coin(legacy, 100_000, mapOf(alice to 60_000, carol to 40_000))))!!.contains("not opened deposits"))
    }

    @Test
    fun `change from one of the room's own spends can be assigned, for its amount`(): Unit = runBlocking {
        aGroup()
        val spend = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 5 })), 0), ByteVector.empty, 0xfffffffdL)),
            listOf(TxOut(Satoshi(30_000), ByteVector("0014" + "aa".repeat(20))), TxOut(Satoshi(19_000), ByteVector("5120" + "bb".repeat(32)))), 0,
        )
        db.frostSigningSessionDao().upsert(
            FrostSigningSession(id = "spend", chatRoomId = room, coordinatorPublicKey = alice, userPublicKey = alice, dkgSessionId = "dkg", threshold = 2, participantCount = 3, signerId = 1, stage = FrostSigningStage.COMPLETE)
        )
        db.groupTransactionDao().upsert(
            GroupTransaction(frostSigningSessionId = "spend", chatRoomId = room, chain = "regtest", account = 0, psbt = "", txid = spend.txid.toString(), signedTransaction = Hex.encode(Transaction.write(spend)), confirmedAt = Clock.System.now())
        )
        val change = DepositKey.Onchain(spend.txid.toString(), 1)
        assertNull(CoinAssignments.refuse(db, room, proposal(Coin(change, 19_000, mapOf(alice to 10_000, bob to 9_000)))))
        assertTrue(CoinAssignments.refuse(db, room, proposal(Coin(change, 20_000, mapOf(alice to 20_000))))!!.contains("another amount"))
    }

    @Test
    fun `shares are suggested pro rata to what each has in, small ones folded into the largest, and none for nobody`() {
        assertEquals(mapOf(alice to 75_000L, bob to 25_000L), CoinAssignments.suggest(100_000, mapOf(alice to 300_000_000, bob to 100_000_000)))
        // 299:99.4:1.6 of 100,000 is 74,750, 24,850 and 400. Carol's 400 sats are too few to be paid
        // alone, so they go to the largest share.
        assertEquals(mapOf(alice to 75_150L, bob to 24_850L), CoinAssignments.suggest(100_000, mapOf(alice to 299_000_000, bob to 99_400_000, carol to 1_600_000)))
        assertEquals(emptyMap(), CoinAssignments.suggest(100_000, mapOf(alice to 0, bob to -5)))
        assertEquals(emptyMap(), CoinAssignments.suggest(500, mapOf(alice to 1_000)))
    }

    @Test
    fun `the form proposes nothing until every sat is assigned, none twice, and no share is dust`() {
        assertEquals(mapOf(alice to 60_000L, bob to 40_000L), GroupAssignCoinsViewModel.sharesFrom(mapOf(alice to "60,000", bob to "40000", carol to "")))
        assertNull(GroupAssignCoinsViewModel.sharesFrom(mapOf(alice to "60000.5")))
        assertIs<GroupAssignCoinsViewModel.Problem.LeftToAssign>(GroupAssignCoinsViewModel.problemWith(100_000, mapOf(alice to 60_000)))
        assertIs<GroupAssignCoinsViewModel.Problem.TooMany>(GroupAssignCoinsViewModel.problemWith(100_000, mapOf(alice to 60_000, bob to 50_000)))
        assertIs<GroupAssignCoinsViewModel.Problem.BelowDust>(GroupAssignCoinsViewModel.problemWith(100_000, mapOf(alice to 99_600, bob to 400)))
        assertNull(GroupAssignCoinsViewModel.problemWith(100_000, mapOf(alice to 60_000, bob to 40_000)))
    }
}
