package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.Lightning.randomBytes32
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Taking a swap-in coin back after its refund delay, by the group's FROST key alone, into its depositor's own
 * deposit branch (docs/group-lightning-swap-in.md, D9 and Phase 7). The LSP takes no part.
 */
class GroupSwapInRefundJvmTest {
    private val fixture = GroupLightningFixture(chain = Chain.Mainnet)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()

    @AfterTest
    fun close() = fixture.close()

    private class Opened(val all: List<GroupLightningFixture.Device>, val wallet: GroupLightningWalletManager.Wallet, val keys: GroupSwapInDerivation.Keys) {
        val depositor get() = all[1]
    }

    private suspend fun opened(): Opened {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        fixture.openDeposits(all, all[1])
        fixture.openDeposits(all, all[2])
        val wallet = GroupLightningWalletManager.walletFor(all[0].db, fixture.roomId, fixture.chain)!!
        return Opened(all, wallet, GroupSwapInDerivation.keys(fixture.thresholdPublicKey, fixture.chain, 0, wallet.statement.icebergKey, wallet.statement.nodeId))
    }

    private fun branchOf(device: GroupLightningFixture.Device) = DepositBranch.of(fixture.roomId, device.publicKey)

    /** A coin of [amount] on [owner]'s swap-in address [index]. */
    private fun coin(o: Opened, owner: GroupLightningFixture.Device, index: Long, amount: Long = 60_000): Pair<OutPoint, TxOut> =
        OutPoint(TxId(randomBytes32()), 0) to TxOut(Satoshi(amount), o.keys.address(branchOf(owner), index).protocol.pubkeyScript)

    private fun refunds(o: Opened) = GroupSweep.SwapInRefunds(o.keys, o.all.drop(1).take(2).associate { it.publicKey.lowercase() to branchOf(it) })

    @Test
    fun `the group refunds a depositor's coins into their own branch, and the transaction is valid`() = runBlocking<Unit> {
        val o = opened()
        val coins = listOf(coin(o, o.depositor, 2), coin(o, o.depositor, 5, 40_000))
        val session = GroupLightningSweeps.proposeSwapInRefund(
            o.depositor.db, o.depositor.room, o.depositor.publicKey, o.wallet, o.depositor.publicKey,
            listOf(GroupLightningSweeps.RefundableCoin(coins[0].first, coins[0].second.amount, 2), GroupLightningSweeps.RefundableCoin(coins[1].first, coins[1].second.amount, 5)),
            toIndex = 0,
        )
        fixture.signSession(o.all, o.depositor, session.id)

        o.all.forEach { d -> assertEquals(FrostSigningStage.COMPLETE, d.db.frostSigningSessionDao().getSessionById(session.id)?.stage, "device ${d.index}") }
        val signed = Transaction.read(assertNotNull(o.all[0].db.groupTransactionDao().getBySessionId(session.id)?.signedTransaction))
        Transaction.correctlySpends(signed, coins.toMap(), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(GroupWalletDerivation.derive(fixture.account, branchOf(o.depositor), 0).pubkeyScript, signed.txOut.single().publicKeyScript, "the depositor's own branch")
        assertEquals(listOf(DefaultSwapInParams.RefundDelay.toLong(), DefaultSwapInParams.RefundDelay.toLong()), signed.txIn.map { it.sequence }, "each coin waits out the refund delay")

        // The refund is the room's own transaction, and still the depositor's deposit: it was never a Lightning one.
        val refunded = OutPoint(signed, 0)
        DepositLedger.recordOnchain(o.all[0].db, fixture.roomId, fixture.chain, listOf(GroupOnchainWallets.OnchainDeposit(refunded, signed.txOut[0].amount, branchOf(o.depositor), confirmed = true)))
        val row = assertNotNull(o.all[0].db.depositDao().get(fixture.roomId, DepositAttributionEvent.DepositKey.Onchain(refunded.txid.toString(), 0).text))
        assertEquals(o.depositor.publicKey.lowercase(), row.member)
        assertEquals(DepositRow.BY_BRANCH, row.attributedBy)
    }

    @Test
    fun `a refund anywhere but the depositor's own branch, or of more than one depositor, is refused`() = runBlocking<Unit> {
        val o = opened()
        val (outPoint, txOut) = coin(o, o.depositor, 2)
        val input = GroupSweep.refundInputOf(outPoint, txOut.amount, o.keys.address(branchOf(o.depositor), 2), o.depositor.publicKey)
        val good = assertNotNull(GroupSweep.build(0, listOf(input), fixture.account, 0, 2, branch = branchOf(o.depositor)))
        assertIs<GroupSweep.Result.Ok>(GroupSweep.check(good, o.wallet.basepoints, fixture.account, refunds(o)))

        fun refused(sweep: GroupSweep.Sweep?, why: String) = assertIs<GroupSweep.Result.Refused>(GroupSweep.check(sweep!!, o.wallet.basepoints, fixture.account, refunds(o)), why)
        refused(GroupSweep.build(0, listOf(input), fixture.account, 0, 2), "to the receive branch")
        refused(GroupSweep.build(0, listOf(input), fixture.account, 0, 2, branch = branchOf(o.all[2])), "to another depositor's branch")
        refused(good.copy(branch = branchOf(o.all[2])), "a branch the output is not on")
        val bobs = coin(o, o.all[2], 0)
        val bobsInput = GroupSweep.refundInputOf(bobs.first, bobs.second.amount, o.keys.address(branchOf(o.all[2]), 0), o.all[2].publicKey)
        refused(GroupSweep.build(0, listOf(input, bobsInput), fixture.account, 0, 2, branch = branchOf(o.depositor)), "two depositors' coins")
        assertIs<GroupSweep.Result.Refused>(GroupSweep.check(good, o.wallet.basepoints, fixture.account, swapIns = null), "with no way to derive the refund keys")
    }

    @Test
    fun `a refund input whose leaf, delay or address is not the depositor's is refused`() = runBlocking<Unit> {
        val o = opened()
        val (outPoint, txOut) = coin(o, o.depositor, 2)
        val input = GroupSweep.refundInputOf(outPoint, txOut.amount, o.keys.address(branchOf(o.depositor), 2), o.depositor.publicKey)
        fun refused(changed: GroupSweep.Input, why: String) =
            assertIs<GroupSweep.Result.Refused>(GroupSweep.check(GroupSweep.build(0, listOf(changed), fixture.account, 0, 2, branch = branchOf(o.depositor))!!, o.wallet.basepoints, fixture.account, refunds(o)), why)
        refused(input.copy(csvDelay = 144), "a shorter delay than the swap-in's")
        refused(input.copy(swapInIndex = 3), "an address index the coin is not on")
        refused(input.copy(leafScript = GroupSweep.leafScript(o.keys.refundKey(branchOf(o.all[2]), 2).publicKey.xOnly(), DefaultSwapInParams.RefundDelay.toLong()).toHex()), "another depositor's refund key")
    }

    @Test
    fun `a member who is no depositor has nothing to refund`() = runBlocking<Unit> {
        val o = opened()
        val (outPoint, txOut) = coin(o, o.all[3], 0)
        assertFailsWith<IllegalArgumentException> {
            GroupLightningSweeps.proposeSwapInRefund(o.all[3].db, o.all[3].room, o.all[3].publicKey, o.wallet, o.all[3].publicKey, listOf(GroupLightningSweeps.RefundableCoin(outPoint, txOut.amount, 0)), toIndex = 0)
        }
    }
}
