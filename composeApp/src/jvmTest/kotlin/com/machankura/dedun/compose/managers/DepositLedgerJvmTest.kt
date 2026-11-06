package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.FakeElectrum
import com.machankura.dedun.compose.wallet.GroupOnchainWallet
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The deposits ledger and the tallies -- `docs/member-deposits.md`, Phase 8: whose each deposit is,
 * kept after the group spends it, and every member's tally adding up with the unattributed to the
 * total.
 */
class DepositLedgerJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancelAndWait()
        db.close()
    }

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val aliceKey = key("a11ce")
    private val bobKey = key("b0b")
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val bob = bobKey.xOnlyPublicKey().value.toHex()

    /** The group opens [member]'s deposits: a statement signed by the room, on file. */
    private suspend fun opened(member: PrivateKey): DepositorStatementEvent.Depositor {
        GroupFixtures.seedRoom(db, room)
        val destination = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(member, room, chain, LightningAddress("me", "example.com"), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination
        val template = DepositorStatementEvent.template(room, destination, 0, 1_700_000_100)
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, 1_700_000_100)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = room, derivationPath = null, frostSigningSessionId = null))
        return DepositorStatementEvent.verify(signed, room)!!
    }

    private fun deposit(seed: Byte, sats: Long, branch: Long, confirmed: Boolean = true) =
        GroupOnchainWallets.OnchainDeposit(OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0), Satoshi(sats), branch, confirmed)

    @Test
    fun `deposits on two members' branches and the receive branch are counted, and add up`(): Unit = runBlocking {
        val a = opened(aliceKey)
        val b = opened(bobKey)
        DepositLedger.recordOnchain(db, room, chain, listOf(deposit(1, 30_000, a.branch), deposit(2, 20_000, b.branch), deposit(3, 5_000, GroupWalletDerivation.RECEIVE)))

        val tallies = DepositLedger.tallies(db, room, chain)
        assertEquals(30_000_000, tallies.members.getValue(alice).onchainMsat)
        assertEquals(20_000_000, tallies.members.getValue(bob).onchainMsat)
        assertEquals(5_000_000, tallies.unattributedMsat)
        assertEquals(55_000_000, tallies.totalMsat)
        assertEquals(tallies.totalMsat, tallies.members.values.sumOf { it.depositedMsat } + tallies.unattributedMsat)
    }

    @Test
    fun `a deposit the group has spent since still counts`(): Unit = runBlocking {
        val a = opened(aliceKey)
        DepositLedger.recordOnchain(db, room, chain, listOf(deposit(1, 30_000, a.branch, confirmed = false)))
        // Spent: the next look finds nothing on the branch.
        DepositLedger.recordOnchain(db, room, chain, emptyList())
        assertEquals(30_000_000, DepositLedger.tallies(db, room, chain).members.getValue(alice).onchainMsat)
        // Seen again, confirmed: one deposit, now confirmed.
        DepositLedger.recordOnchain(db, room, chain, listOf(deposit(1, 30_000, a.branch, confirmed = true)))
        val rows = db.depositDao().inRoom(room, "regtest")
        assertEquals(1, rows.size)
        assertEquals(DepositRow.BY_BRANCH, rows.single().attributedBy)
    }

    @Test
    fun `a Lightning deposit is the requester's, by the invoice held or on the operator's word, and liquidity is not a deposit`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, room)
        val wallet = "w"
        val held = "aa".repeat(32)
        val reported = "bb".repeat(32)
        val nobodys = "cc".repeat(32)
        db.groupLightningRecordsDao().upsertInvoice(GroupLightningInvoiceRow(wallet, "r1", "lnbcrt1", requester = alice, paymentHash = held))
        DepositLedger.recordLightning(db, room, chain, wallet, listOf(
            WalletWire.PaymentView("p1", incoming = true, amountMsat = 10_000_000, feesMsat = 0, paymentHash = held, succeeded = true),
            WalletWire.PaymentView("p2", incoming = true, amountMsat = 7_000_000, feesMsat = 0, paymentHash = reported, succeeded = true, requestId = "r2", requester = bob),
            WalletWire.PaymentView("p3", incoming = true, amountMsat = 1_000_000, feesMsat = 0, paymentHash = nobodys, succeeded = true),
            WalletWire.PaymentView("p4", incoming = true, amountMsat = 500_000, feesMsat = 0, paymentHash = "dd".repeat(32), succeeded = true, kind = WalletWire.LIQUIDITY),
            WalletWire.PaymentView("p5", incoming = true, amountMsat = 9_000_000, feesMsat = 0, paymentHash = "ee".repeat(32), succeeded = false),
        ))

        val tallies = DepositLedger.tallies(db, room, chain)
        assertEquals(10_000_000, tallies.members.getValue(alice).lightningMsat)
        assertEquals(7_000_000, tallies.members.getValue(bob).lightningMsat)
        assertEquals(1_000_000, tallies.unattributedMsat)
        assertEquals(18_000_000, tallies.totalMsat)
        assertEquals(DepositRow.BY_OPERATOR, db.depositDao().get(room, "ln:$reported")?.attributedBy)
    }

    @Test
    fun `the operator's word never overwrites what the device holds, and what it holds overwrites the operator's`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, room)
        val hash = "aa".repeat(32)
        fun row(member: String, by: String) = DepositRow(room, "regtest", "ln:$hash", DepositRow.LIGHTNING, 1_000, member, by)
        DepositLedger.merge(db, row(bob, DepositRow.BY_OPERATOR))
        DepositLedger.merge(db, row(alice, DepositRow.BY_INVOICE))
        assertEquals(alice, db.depositDao().get(room, "ln:$hash")?.member)
        DepositLedger.merge(db, row(bob, DepositRow.BY_OPERATOR))
        assertEquals(alice, db.depositDao().get(room, "ln:$hash")?.member)
    }

    @Test
    fun `the wallet's history finds a deposit spent before anybody looked`(): Unit = runBlocking {
        val electrum = FakeElectrum()
        val account = GroupWalletDerivation.account(GroupFixtures.thresholdKeyOf(group), chain)
        val branch = DepositBranch.of(room, alice)
        val wallets = GroupOnchainWallets(electrum, scope)
        val wallet = wallets.walletFor(account).also { it.watchMembers(mapOf(alice to branch)) }
        val script = GroupWalletDerivation.derive(account, branch, 0).pubkeyScript
        withTimeout(10_000) { wallet.state.first { it.depositAddressOf(alice) != null } }
        electrum.pay(script, Satoshi(42_000))
        electrum.spend(script)

        val state = withTimeout(10_000) { wallet.state.first { s -> s.members[alice]?.state?.addresses?.values?.any { it.alreadyUsed } == true } }
        val found = wallets.depositsIn(state)
        assertEquals(listOf(Satoshi(42_000)), found.map { it.amount })
        assertEquals(branch, found.single().branch)
        assertNull(state.utxos.firstOrNull(), "nothing is unspent")
    }
}
