package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
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
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Paying members everything they own: what goes in, what each gets, and every refusal before
 * anybody is asked to sign. `docs/payouts-only.md`, Phase 4.
 */
class PayoutBuilderTest {
    private val chain = Chain.Regtest
    private val account = GroupWalletDerivation.account(PrivateKey(ByteVector32(Hex.decode("6a0b".padStart(64, '0')))).publicKey().toString(), chain)
    private val room = "9a17".repeat(16)
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val alicesBranch = DepositBranch.of(room, alice)
    private val bobsBranch = DepositBranch.of(room, bob)

    private fun addressOf(seed: Byte) = ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { seed })).publicKey())))
    private val scripts = mapOf(alice to addressOf(1), bob to addressOf(2))
    private val records = CoinOwnership.Records(mapOf(alicesBranch to alice, bobsBranch to bob))
    private val context = PayoutRule.Context(records, scripts)

    private data class Coin(val branch: Long, val index: Int, val sats: Long, val seed: Byte, val confirmed: Boolean = true) {
        fun tx(derived: GroupWalletDerivation.Derived) = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(sats), derived.pubkeyScript)), 0,
        )
    }

    private fun stateOf(vararg coins: Coin): GroupWalletState {
        fun branchState(branch: Long) = WalletState(
            coins.filter { it.branch == branch }.associate { coin ->
                val derived = GroupWalletDerivation.derive(account, branch, coin.index.toLong())
                val meta = WalletState.AddressMeta.Derived(coin.index)
                val tx = coin.tx(derived)
                derived.address(chain) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(tx.txid, 0, if (coin.confirmed) 100 else 0, tx, meta)))
            }
        )
        return GroupWalletState(
            account, branchState(GroupWalletDerivation.RECEIVE), branchState(GroupWalletDerivation.CHANGE), tipHeight = 110,
            members = mapOf(alice to MemberBranch(alicesBranch, branchState(alicesBranch)), bob to MemberBranch(bobsBranch, branchState(bobsBranch))),
        )
    }

    private val a1 = Coin(alicesBranch, 0, 70_000, 0x11)
    private val a2 = Coin(alicesBranch, 1, 50_000, 0x12)
    private val b1 = Coin(bobsBranch, 0, 30_000, 0x21)
    private val legacy = Coin(GroupWalletDerivation.RECEIVE, 0, 100_000, 0x31)

    private fun built(result: PayoutBuilder.Result) = assertIs<PayoutBuilder.Result.Built>(result)

    @Test
    fun `one member is paid every confirmed coin they own, less the whole fee, and nobody else's`() {
        val state = stateOf(a1, a2, b1, Coin(alicesBranch, 2, 9_000, 0x13, confirmed = false))
        val payout = built(PayoutBuilder.build(state, setOf(alice), context, 2, emptySet()))
        val only = payout.payout.members.single()
        assertEquals(alice, only.member)
        assertEquals(120_000, only.owned)
        assertEquals(payout.fee.sat, only.feeShare)
        assertEquals(2, payout.psbt.global.tx.txIn.size)
        assertEquals(scripts[alice], payout.psbt.global.tx.txOut.single().publicKeyScript)
        // What the builder made, a signer accepts.
        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(GroupSpend.decode(GroupSpend.encode(payout.psbt))!!, account)).checked
        assertIs<PayoutRule.Result.Payout>(PayoutRule.check(checked, context))
    }

    @Test
    fun `two members in one payout share the fee pro rata`() {
        val payout = built(PayoutBuilder.build(stateOf(a1, b1), setOf(alice, bob), context, 10, emptySet()))
        val byMember = payout.payout.members.associateBy { it.member }
        assertEquals(payout.fee.sat, byMember.values.sumOf { it.feeShare })
        assertTrue(byMember.getValue(alice).feeShare > byMember.getValue(bob).feeShare)
    }

    @Test
    fun `a coin a chosen member shares brings its other owner in, for their share alone`() {
        val key = DepositKey.Onchain(legacy.tx(GroupWalletDerivation.derive(account, 0, 0)).txid.toString(), 0)
        val shared = context.copy(records = records.copy(assignments = mapOf(key to CoinAssignmentEvent.Coin(key, 100_000, mapOf(alice to 60_000, bob to 40_000)))))
        val payout = built(PayoutBuilder.build(stateOf(a1, b1, legacy), setOf(alice), shared, 2, emptySet()))
        val byMember = payout.payout.members.associateBy { it.member }
        assertEquals(130_000, byMember.getValue(alice).owned)
        // Bob's share of the shared coin, not his own coin b1.
        assertEquals(40_000, byMember.getValue(bob).owned)
        assertEquals(2, payout.psbt.global.tx.txIn.size)
    }

    @Test
    fun `a member whose coins an open spend holds already has a payout waiting`() {
        val state = stateOf(a1, b1)
        val held = state.utxos.filter { it.derivation.change == alicesBranch }.map { it.utxo.outPoint }.toSet()
        assertEquals(PayoutBuilder.Result.AlreadyWaiting(alice), PayoutBuilder.build(state, setOf(alice), context, 2, held))
    }

    @Test
    fun `a member with no confirmed coins, or no onchain address, can't be paid out`() {
        assertEquals(PayoutBuilder.Result.NothingToPay(alice), PayoutBuilder.build(stateOf(b1, Coin(alicesBranch, 0, 9_000, 0x13, confirmed = false)), setOf(alice), context, 2, emptySet()))
        assertEquals(PayoutBuilder.Result.NoOnchainAddress(alice), PayoutBuilder.build(stateOf(a1), setOf(alice), context.copy(scripts = scripts - alice), 2, emptySet()))
    }

    @Test
    fun `a member whose coins don't cover their share of the fee is too small to pay`() {
        assertEquals(PayoutBuilder.Result.BelowDust(alice), PayoutBuilder.build(stateOf(Coin(alicesBranch, 0, 1_000, 0x14)), setOf(alice), context, 10, emptySet()))
    }

    @Test
    fun `a bump re-splits a larger fee over the same coins and members, and a lower one is refused`() {
        val first = built(PayoutBuilder.build(stateOf(a1, b1), setOf(alice, bob), context, 2, emptySet()))
        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(first.psbt, account)).checked
        val bumped = built(PayoutBuilder.bump(checked, context, checked.feeratePerVbyte * 2))
        assertTrue(bumped.fee > first.fee)
        assertEquals(first.psbt.global.tx.txIn.map { it.outPoint }, bumped.psbt.global.tx.txIn.map { it.outPoint })
        assertEquals(PayoutBuilder.Result.NotHigher, PayoutBuilder.bump(checked, context, 1))
    }
}
