package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.CoinOwnership
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.MemberBranch
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What each member may be paid out, and where to, and the ledger's paid-out column made fit to
 * compute it from -- `docs/payouts-only.md`, Phase 2.
 */
class MemberBalancesJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val aliceKey = key("a11ce")
    private val bobKey = key("b0b")
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val bob = bobKey.xOnlyPublicKey().value.toHex()
    private val account = GroupWalletDerivation.account(key("6a0b").publicKey().toString(), chain)

    private fun addressOf(seed: Byte): String {
        val script = Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { seed })).publicKey())
        return Bitcoin.addressFromPublicKeyScript(chain.chainHash, script).right!!
    }

    private suspend fun aRoomWithAliceAndBob() {
        signingRoom(db, group, members = listOf(alice, bob), holdsShare = false)
    }

    private fun destination(member: PrivateKey, onchain: String?, createdAt: Long): Event =
        GroupPayoutDestinationEvent.build(member, room, chain, LightningAddress("me", "example.com"), onchainAddress = onchain, createdAt = createdAt)

    /** [statement] put in the room by its author, as an inner event carrying it whole. */
    private suspend fun file(statement: Event) {
        val tags = arrayOf(arrayOf("h", room))
        val id = EventHasher.hashId(statement.pubKey, statement.createdAt, GroupPayoutDestinationEvent.KIND, tags, statement.toJson())
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id, publicKey = statement.pubKey, kind = GroupPayoutDestinationEvent.KIND,
                createdAt = Instant.fromEpochSeconds(statement.createdAt), tags = tags, content = statement.toJson(), chatRoomId = room,
            )
        )
    }

    private suspend fun signed(kind: Int, tags: Array<Array<String>>, createdAt: Long = 1_700_000_100) {
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signedAsRoom(group, kind, tags, "", createdAt), chatRoomId = room))
    }

    /** The group opens [member]'s deposits, carrying [destination]. */
    private suspend fun opened(member: PrivateKey, destination: Event): DepositorStatementEvent.Depositor {
        val template = DepositorStatementEvent.template(room, GroupPayoutDestinationEvent.verified(destination) as GroupPayoutDestination, 0, 1_700_000_100)
        signed(template.kind, template.tags)
        return Depositors.statementOf(db, room, chain, member.xOnlyPublicKey().value.toHex())!!
    }

    private fun coinOn(branch: Long, index: Int, sats: Long, seed: Byte): Pair<String, WalletState.AddressState> {
        val derived = GroupWalletDerivation.derive(account, branch, index.toLong())
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(sats), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(index)
        return derived.address(chain) to WalletState.AddressState(meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(tx.txid, 0, 100, tx, meta)))
    }

    private fun state(receive: List<Pair<String, WalletState.AddressState>>, members: Map<String, Pair<Long, List<Pair<String, WalletState.AddressState>>>>) =
        GroupWalletState(
            account, WalletState(receive.toMap()), WalletState.empty, tipHeight = 110,
            members = members.mapValues { (_, branch) -> MemberBranch(branch.first, WalletState(branch.second.toMap())) },
        )

    @Test
    fun `a member's branch coins are theirs, an assigned coin is shared, and an unassigned one is nobody's`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        val alices = opened(aliceKey, destination(aliceKey, addressOf(1), 1_700_000_000))
        opened(bobKey, destination(bobKey, null, 1_700_000_000))
        val legacy = coinOn(GroupWalletDerivation.RECEIVE, 0, 100_000, 0x31)
        val stray = coinOn(GroupWalletDerivation.RECEIVE, 1, 5_000, 0x32)
        val wallet = state(listOf(legacy, stray), mapOf(alice to (alices.branch to listOf(coinOn(alices.branch, 0, 70_000, 0x11)))))

        val before = MemberBalances.balancesIn(db, room, chain, wallet)
        assertEquals(70_000, before.members[alice]?.onchainConfirmedSat)
        assertEquals(105_000, before.unownedSat)
        assertTrue(before.unowned.all { it.why == CoinOwnership.Why.RECEIVE })

        val legacyKey = DepositKey.Onchain(legacy.second.utxos.single().outPoint.txid.toString(), 0)
        signed(CoinAssignmentEvent.KIND, CoinAssignmentEvent.template(chain, listOf(CoinAssignmentEvent.Coin(legacyKey, 100_000, mapOf(alice to 60_000, bob to 40_000)))).tags)

        val after = MemberBalances.balancesIn(db, room, chain, wallet)
        assertEquals(130_000, after.members[alice]?.onchainConfirmedSat)
        assertEquals(40_000, after.members[bob]?.onchainConfirmedSat)
        assertTrue(after.members[bob]!!.coins.single().shared)
        assertEquals(5_000, after.unownedSat)
        assertTrue(after.members[alice]!!.hasOnchainAddress)
        assertTrue(!after.members[bob]!!.hasOnchainAddress)
    }

    @Test
    fun `a former member keeps their balance and their destination, a removal is honoured, and the statement's is the fallback`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        val bobsFirst = destination(bobKey, addressOf(2), 1_700_000_000)
        opened(bobKey, bobsFirst)

        // A device that holds none of Bob's own statements still has the one the group signed.
        assertEquals(addressOf(2), GroupPayoutDestinations.payoutDestinationOf(db, room, chain, bob)?.onchainAddress)

        file(destination(bobKey, addressOf(3), 1_700_000_200))
        db.participantDao().delete(db.participantDao().findParticipantsByChatRoomId(room).filter { it.participantPublicKey == bob })
        val balances = MemberBalances.balancesIn(db, room, chain, null)
        assertTrue(balances.members[bob]!!.isFormerMember)
        assertEquals(addressOf(3), balances.members[bob]?.destination?.onchainAddress)
        assertNull(GroupPayoutDestinations.destinationOf(db, room, chain, bob))

        file(GroupPayoutDestinationEvent.buildRemoved(bobKey, room, chain, createdAt = 1_700_000_300))
        assertNull(GroupPayoutDestinations.payoutDestinationOf(db, room, chain, bob))
    }

    private val payee = PrivateKey(randomBytes32())

    private fun authorisation(member: String?, amountMsat: Long, maxFeeMsat: Long, wallet: Long = 0): GroupLightningAuthorisationEvent.Pay {
        val invoice = Bolt11Invoice.create(chain, amountMsat.msat, randomBytes32(), payee, Either.Left("payout"), CltvExpiryDelta(144), INVOICE_FEATURES)
        return GroupLightningAuthorisationEvent.Pay(
            chain, wallet, invoice.write(), amountMsat, maxFeeMsat, invoice.paymentHash.toHex(), Clock.System.now().epochSeconds + 3_600,
            member?.let { GroupLightningAuthorisationEvent.Payout(it, "d".repeat(64)) },
        )
    }

    private suspend fun sent(pay: GroupLightningAuthorisationEvent.Pay, feesMsat: Long, wallet: Long = pay.wallet) {
        db.groupLightningRecordsDao().upsertPayment(
            GroupLightningPaymentRow(
                wallet = GroupLightningCoSigner.walletKey(room, chain, wallet), id = pay.paymentHash, incoming = false,
                amountMsat = pay.amountMsat, feesMsat = feesMsat, paymentHash = pay.paymentHash, description = null,
                completedAt = Clock.System.now(), succeeded = true,
            )
        )
    }

    @Test
    fun `a Lightning balance is deposits less what was sent and what is open, from signed authorisations on this room's wallet only`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        opened(aliceKey, destination(aliceKey, null, 1_700_000_000))
        db.depositDao().upsert(
            DepositRow(chatRoomId = room, chain = "regtest", depositKey = "ln:" + "ab".repeat(32), source = DepositRow.LIGHTNING, amountMsat = 100_000_000, member = alice, attributedBy = DepositRow.BY_INVOICE)
        )
        val paid = authorisation(alice, 20_000_000, 300_000)
        val open = authorisation(alice, 10_000_000, 200_000)
        listOf(paid, open).forEachIndexed { i, pay -> signed(GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(pay).tags, 1_700_000_200L + i) }
        sent(paid, 12_000)

        // Signed by nobody: a row a buggy or hostile peer filed, which no balance reads.
        val forged = authorisation(alice, 30_000_000, 0)
        val forgedEvent = signedAsRoom(keyMaterial("9a9e"), GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(forged).tags, "", 1_700_000_300)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(forgedEvent, chatRoomId = room))
        sent(forged, 0)

        // Paid from another wallet of this room by the same hash: not what the authorisation named.
        val elsewhere = authorisation(alice, 5_000_000, 0, wallet = 0)
        signed(GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(elsewhere).tags, 1_700_000_400)
        sent(elsewhere, 0, wallet = 1)

        val balance = assertNotNull(MemberBalances.balancesIn(db, room, chain, null).members[alice]).lightning
        assertEquals(20_012_000, balance.sentMsat)
        assertEquals(10_200_000 + 5_000_000, balance.openMsat)
        assertEquals(100_000_000 - 20_012_000 - 15_200_000, balance.availableMsat)
        assertEquals(20_000_000, DepositLedger.tallies(db, room, chain).members[alice]?.paidOutMsat)
    }

    @Test
    fun `a payout to an address the member has since replaced still counts as paid to them`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        val first = addressOf(1)
        file(destination(aliceKey, first, 1_700_000_000))
        val script = Bitcoin.addressToPublicKeyScript(chain.chainHash, first).right!!
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 7 })), 0), ByteVector.empty, 0xfffffffdL)), listOf(TxOut(Satoshi(50_000), Script.write(script))), 0)
        db.frostSigningSessionDao().upsert(
            FrostSigningSession(
                id = "payout", chatRoomId = room, coordinatorPublicKey = alice, userPublicKey = alice, dkgSessionId = "dkg",
                threshold = 2, participantCount = 3, signerId = 1, stage = FrostSigningStage.COMPLETE,
            )
        )
        db.groupTransactionDao().upsert(
            GroupTransaction(
                frostSigningSessionId = "payout", chatRoomId = room, chain = "regtest", account = 0, psbt = "", txid = tx.txid.toString(),
                signedTransaction = Hex.encode(Transaction.write(tx)), confirmedAt = Clock.System.now(),
            )
        )
        assertEquals(50_000_000, DepositLedger.tallies(db, room, chain).members[alice]?.paidOutMsat)

        file(destination(aliceKey, addressOf(4), 1_700_000_500))
        assertEquals(50_000_000, DepositLedger.tallies(db, room, chain).members[alice]?.paidOutMsat)
    }
}
