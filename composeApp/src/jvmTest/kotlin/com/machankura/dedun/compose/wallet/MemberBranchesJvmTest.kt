package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The group's wallet watching each depositor's branch beside receive and change --
 * `docs/member-deposits.md`, Phase 4.
 */
class MemberBranchesJvmTest {
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
    )
    private val room = "9a17".repeat(16)
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val alicesBranch = DepositBranch.of(room, alice)
    private val bobsBranch = DepositBranch.of(room, bob)
    private fun alices(index: Long) = GroupWalletDerivation.derive(account, alicesBranch, index)
    private fun bobs(index: Long) = GroupWalletDerivation.derive(account, bobsBranch, index)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `each member is handed an address of their own, and it moves on when paid`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)
        wallet.watchMembers(mapOf(alice to alicesBranch, bob to bobsBranch))

        eventually { wallet.state.first { it.depositAddressOf(alice) == alices(0).address(Chain.Regtest) && it.depositAddressOf(bob) != null } }
        eventually { electrum.pay(alices(0).pubkeyScript, Satoshi(30_000)) }

        val state = eventually { wallet.state.first { it.depositAddressOf(alice) == alices(1).address(Chain.Regtest) } }
        assertEquals(bobs(0).address(Chain.Regtest), state.depositAddressOf(bob), "Bob's address does not move for Alice's deposit")
        assertEquals(Satoshi(30_000), state.confirmedBalance)
        assertEquals(alicesBranch, state.utxos.single().derivation.change)
        assertEquals(alice, state.memberOfBranch(alicesBranch))
        assertEquals(alices(0), state.derivationOf(alices(0).pubkeyScript))
        assertEquals(GroupAddress.Status.USED, state.depositAddressesOf(alice)!!.last().status)
    }

    @Test
    fun `a member opened after the wallet started is watched from then on`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)
        val before = eventually { wallet.state.first { it.receiveAddress != null } }
        assertNull(before.depositAddressOf(alice))

        wallet.watchMembers(mapOf(alice to alicesBranch))
        eventually { electrum.pay(alices(0).pubkeyScript, Satoshi(12_000)) }
        val after = eventually { wallet.state.first { it.confirmedBalance == Satoshi(12_000) } }
        assertEquals(alices(1).address(Chain.Regtest), after.depositAddressOf(alice))
    }

    @Test
    fun `watching a member twice keeps one watcher, and the balance sums every branch`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)
        wallet.watchMembers(mapOf(alice to alicesBranch))
        wallet.watchMembers(mapOf(alice to alicesBranch, bob to bobsBranch))
        eventually { wallet.state.first { it.receiveAddress != null && it.depositAddressOf(bob) != null } }

        eventually {
            electrum.pay(alices(0).pubkeyScript, Satoshi(1_000))
            electrum.pay(bobs(0).pubkeyScript, Satoshi(2_000))
            electrum.pay(GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript, Satoshi(4_000))
        }
        val state = eventually { wallet.state.first { it.confirmedBalance == Satoshi(7_000) } }
        assertEquals(3, state.utxos.size)
        assertEquals(2, state.members.size)
    }
}
