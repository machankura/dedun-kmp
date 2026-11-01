package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
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
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whose a coin is, and the one spend the group's wallet signs -- one mutation per check.
 * `docs/payouts-only.md`, Phase 1.
 */
class PayoutRuleTest {
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("6a0b".padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
    )
    private val room = "9a17".repeat(16)
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val carol = "c0".repeat(32)
    private val alicesBranch = DepositBranch.of(room, alice)
    private val bobsBranch = DepositBranch.of(room, bob)

    private fun addressOf(seed: Byte) = ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { seed })).publicKey())))
    private val alicesAddress = addressOf(1)
    private val bobsAddress = addressOf(2)
    private val carolsAddress = addressOf(3)
    private val stranger = addressOf(9)

    private val records = CoinOwnership.Records(mapOf(alicesBranch to alice, bobsBranch to bob))
    private val scripts = mapOf(alice to alicesAddress, bob to bobsAddress, carol to carolsAddress)
    private val context = PayoutRule.Context(records, scripts)

    private data class Coin(val tx: Transaction, val derived: GroupWalletDerivation.Derived) {
        val outPoint get() = OutPoint(tx, 0)
        val txOut get() = tx.txOut[0]
    }

    private fun coin(branch: Long, index: Long, amount: Long, seed: Byte): Coin {
        val derived = GroupWalletDerivation.derive(account, branch, index)
        val tx = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(amount), derived.pubkeyScript)), 0,
        )
        return Coin(tx, derived)
    }

    private fun spend(coins: List<Coin>, outputs: List<Pair<ByteVector, Long>>, change: Map<Int, GroupWalletDerivation.Derived> = emptyMap()): GroupSpend.Checked {
        val tx = Transaction(
            2,
            coins.map { TxIn(it.outPoint, ByteVector.empty, GroupSpend.MAX_SEQUENCE) },
            outputs.map { (script, amount) -> TxOut(Satoshi(amount), script) }, 0,
        )
        val psbt = GroupSpend.psbtOf(tx, coins.map { it.txOut to it.derived }, change, account)
        return assertIs<GroupSpend.Result.Ok>(GroupSpend.check(GroupSpend.decode(GroupSpend.encode(psbt))!!, account)).checked
    }

    private fun refused(checked: GroupSpend.Checked, context: PayoutRule.Context = this.context): String =
        assertIs<PayoutRule.Result.Refused>(PayoutRule.check(checked, context)).reason

    private val a1 = coin(alicesBranch, 0, 70_000, 0x11)
    private val a2 = coin(alicesBranch, 1, 50_000, 0x12)
    private val b1 = coin(bobsBranch, 0, 30_000, 0x21)
    private val legacy = coin(GroupWalletDerivation.RECEIVE, 0, 100_000, 0x31)
    private val oldChange = coin(GroupWalletDerivation.CHANGE, 0, 20_000, 0x41)

    private fun key(coin: Coin) = DepositKey.Onchain(coin.outPoint.txid.toString(), coin.outPoint.index)

    // --- whose a coin is ---

    @Test
    fun `a coin on a member's branch is theirs, and one on receive or change is nobody's`() {
        assertEquals(CoinOwnership.Owner.Member(alice), CoinOwnership.ownerOf(a1.outPoint, alicesBranch, 70_000, records))
        assertEquals(CoinOwnership.Owner.Nobody(CoinOwnership.Why.RECEIVE), CoinOwnership.ownerOf(legacy.outPoint, 0, 100_000, records))
        assertEquals(CoinOwnership.Owner.Nobody(CoinOwnership.Why.CHANGE), CoinOwnership.ownerOf(oldChange.outPoint, 1, 20_000, records))
        assertEquals(CoinOwnership.Owner.Nobody(CoinOwnership.Why.UNKNOWN_BRANCH), CoinOwnership.ownerOf(a1.outPoint, DepositBranch.of(room, carol), 70_000, records))
    }

    @Test
    fun `an assignment outranks an attribution, which outranks a branch`() {
        val attributed = records.copy(attributions = mapOf(key(legacy) to DepositAttributionEvent.Attribution(key(legacy), bob, 100_000)))
        assertEquals(CoinOwnership.Owner.Member(bob), CoinOwnership.ownerOf(legacy.outPoint, 0, 100_000, attributed))

        val assigned = attributed.copy(assignments = mapOf(key(legacy) to CoinAssignmentEvent.Coin(key(legacy), 100_000, mapOf(alice to 60_000, bob to 40_000))))
        assertEquals(CoinOwnership.Owner.Shares(mapOf(alice to 60_000L, bob to 40_000L)), CoinOwnership.ownerOf(legacy.outPoint, 0, 100_000, assigned))

        // A correction moves a coin off the branch's member.
        val corrected = records.copy(attributions = mapOf(key(a1) to DepositAttributionEvent.Attribution(key(a1), bob, 70_000)))
        assertEquals(CoinOwnership.Owner.Member(bob), CoinOwnership.ownerOf(a1.outPoint, alicesBranch, 70_000, corrected))
    }

    @Test
    fun `an assignment of a member-branch coin, or one for another amount, is ignored, and a group attribution is nobody's`() {
        val onBranch = records.copy(assignments = mapOf(key(a1) to CoinAssignmentEvent.Coin(key(a1), 70_000, mapOf(bob to 70_000))))
        assertEquals(CoinOwnership.Owner.Member(alice), CoinOwnership.ownerOf(a1.outPoint, alicesBranch, 70_000, onBranch))

        val wrongAmount = records.copy(assignments = mapOf(key(legacy) to CoinAssignmentEvent.Coin(key(legacy), 99_000, mapOf(bob to 99_000))))
        assertIs<CoinOwnership.Owner.Nobody>(CoinOwnership.ownerOf(legacy.outPoint, 0, 100_000, wrongAmount))

        val theGroups = records.copy(attributions = mapOf(key(a1) to DepositAttributionEvent.Attribution(key(a1), null, 70_000)))
        assertEquals(CoinOwnership.Owner.Nobody(CoinOwnership.Why.THE_GROUPS), CoinOwnership.ownerOf(a1.outPoint, alicesBranch, 70_000, theGroups))
    }

    // --- the payout ---

    @Test
    fun `all of one member's coins to their address, less the fee, is a payout`() {
        val payout = assertIs<PayoutRule.Result.Payout>(PayoutRule.check(spend(listOf(a1, a2), listOf(alicesAddress to 119_400)), context))
        val alices = payout.members.single()
        assertEquals(alice, alices.member)
        assertEquals(120_000, alices.owned)
        assertEquals(600, alices.feeShare)
        assertEquals(119_400, alices.paid)
    }

    @Test
    fun `two members in one payout share the fee pro rata`() {
        // 120,000 and 30,000 sats share a 1,000 sat fee as 800 and 200.
        val checked = spend(listOf(a1, b1, a2), listOf(bobsAddress to 29_800, alicesAddress to 119_200))
        val payout = assertIs<PayoutRule.Result.Payout>(PayoutRule.check(checked, context))
        assertEquals(mapOf(alice to 800L, bob to 200L), payout.members.associate { it.member to it.feeShare })
    }

    @Test
    fun `a shared coin pays every owner their share`() {
        val shared = context.copy(records = records.copy(assignments = mapOf(key(legacy) to CoinAssignmentEvent.Coin(key(legacy), 100_000, mapOf(alice to 60_000, bob to 40_000)))))
        // Alice owns 70,000 + 60,000 and Bob 40,000: 1,700 sats of fee is 1,300 and 400.
        val checked = spend(listOf(a1, legacy), listOf(alicesAddress to 128_700, bobsAddress to 39_600))
        assertIs<PayoutRule.Result.Payout>(PayoutRule.check(checked, shared))

        val bobLeftOut = spend(listOf(a1, legacy), listOf(alicesAddress to 168_300))
        assertTrue(refused(bobLeftOut, shared).contains("without paying them"))
    }

    @Test
    fun `a coin nobody owns can't be paid out`() {
        assertTrue(refused(spend(listOf(a1, legacy), listOf(alicesAddress to 169_000))).contains("nobody owns"))
    }

    @Test
    fun `another member's coin, with them unpaid, is refused`() {
        assertTrue(refused(spend(listOf(a1, b1), listOf(alicesAddress to 99_000))).contains("without paying them"))
    }

    @Test
    fun `an output to an address that is no member's is refused`() {
        assertTrue(refused(spend(listOf(a1), listOf(stranger to 69_500))).contains("no member's payout address"))
    }

    @Test
    fun `paying a member none of whose coins it spends is refused`() {
        assertTrue(refused(spend(listOf(a1), listOf(alicesAddress to 60_000, carolsAddress to 9_500))).contains("none of whose coins"))
    }

    @Test
    fun `a sat moved from one member to another is refused`() {
        // 70,000 and 30,000 sats with a 1,000 sat fee are 69,300 and 29,700. With one member the
        // fee is whatever their output leaves, which check 6 bounds; with two, a sat moved shows.
        assertTrue(refused(spend(listOf(a1, b1), listOf(alicesAddress to 69_301, bobsAddress to 29_699))).contains("less a"))
        assertTrue(refused(spend(listOf(a1, b1), listOf(alicesAddress to 69_299, bobsAddress to 29_701))).contains("less a"))
    }

    @Test
    fun `change, or a member paid twice, is refused`() {
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        assertTrue(refused(spend(listOf(a1), listOf(alicesAddress to 40_000, change.pubkeyScript to 29_500), mapOf(1 to change))).contains("change"))
        assertTrue(refused(spend(listOf(a1), listOf(alicesAddress to 40_000, alicesAddress to 29_500))).contains("twice"))
    }

    @Test
    fun `an address two members gave is refused`() {
        val shared = context.copy(scripts = scripts + (bob to alicesAddress))
        assertTrue(refused(spend(listOf(a1), listOf(alicesAddress to 69_500)), shared).contains("more than one member"))
    }

    @Test
    fun `a member with no address can't be paid`() {
        val noAddress = context.copy(scripts = scripts - alice)
        assertIs<PayoutRule.Result.Refused>(PayoutRule.check(spend(listOf(a1), listOf(alicesAddress to 69_500)), noAddress))
    }

    @Test
    fun `a bump that re-splits a larger fee is still a payout`() {
        val first = spend(listOf(a1, b1), listOf(alicesAddress to 69_300, bobsAddress to 29_700))
        assertIs<PayoutRule.Result.Payout>(PayoutRule.check(first, context))
        // 2,000 sats over 70,000 and 30,000 is 1,400 and 600.
        val bumped = spend(listOf(a1, b1), listOf(alicesAddress to 68_600, bobsAddress to 29_400))
        assertIs<PayoutRule.Result.Payout>(PayoutRule.check(bumped, context))
    }
}
