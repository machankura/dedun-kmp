package com.machankura.dedun.compose.wallet.lightning

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupLightningCoSigner
import com.machankura.dedun.compose.managers.GroupSwapInDerivation
import com.machankura.dedun.compose.wallet.FakeElectrum
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.crypto.GroupBasepoints
import fr.acinq.lightning.crypto.IcebergSigner
import fr.acinq.lightning.crypto.IcebergSwapInSigner
import fr.acinq.lightning.crypto.SwapInOnChainKeys
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every depositor's swap-in addresses, watched over a fake chain (docs/group-lightning-swap-in.md, Phase 3).
 */
class GroupSwapInWalletJvmTest {
    private fun key(hex: String) = PrivateKey(ByteVector32(Hex.decode(hex.padStart(64, '0'))))

    private val chatRoomId = "9a17".repeat(16)
    private val alice = key("a11ce").publicKey().xOnly().value.toHex()
    private val bob = key("b0b").publicKey().xOnly().value.toHex()
    private val carol = key("ca201").publicKey().xOnly().value.toHex()
    private val nodeSeed = ByteVector(Hex.decode("5eed".padStart(64, '0')))
    private val nodeId = GroupLightningCoSigner.keyManagerOf(nodeSeed, Chain.Mainnet).nodeKeys.nodeKey.publicKey.toHex()
    private val keys = GroupSwapInDerivation.keys(key("f0057").publicKey().toHex(), Chain.Mainnet, 0, key("1ceb3").publicKey().toHex(), nodeId)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun script(member: String, index: Long) = keys.address(DepositBranch.of(chatRoomId, member), index).protocol.serializedPubkeyScript

    @Test
    fun `a depositor's coin is seen, and their current address moves on`() {
        val electrum = FakeElectrum()
        val wallet = GroupSwapInWallet(keys, electrum, scope)
        wallet.watchDepositors(mapOf(alice to DepositBranch.of(chatRoomId, alice)))

        val first = eventually { wallet.state.first { it.currentAddressOf(alice) != null } }.currentAddressOf(alice)!!
        assertEquals(0L, first.index)
        eventually { electrum.pay(script(alice, 0), Satoshi(50_000)) }

        val paid = eventually { wallet.state.first { it.currentAddressOf(alice)?.index == 1L } }
        assertEquals(Satoshi(50_000), paid.depositors.getValue(alice).state.utxos.single().amount)
        assertEquals(alice, paid.depositorOf(paid.depositors.getValue(alice).state.utxos.single().outPoint))
    }

    @Test
    fun `the node numbers each depositor's addresses apart, and reads the number back to the same address`() {
        val electrum = FakeElectrum()
        val wallet = GroupSwapInWallet(keys, electrum, scope)
        wallet.watchDepositors(mapOf(alice to DepositBranch.of(chatRoomId, alice), bob to DepositBranch.of(chatRoomId, bob)))
        eventually { wallet.state.first { it.currentAddressOf(alice) != null && it.currentAddressOf(bob) != null } }
        eventually { electrum.pay(script(bob, 1), Satoshi(70_000)) }

        val state = eventually { wallet.state.first { s -> s.forNode.utxos.isNotEmpty() } }
        val coin = state.forNode.utxos.single()
        val number = assertIs<WalletState.AddressMeta.Derived>(coin.addressMeta).index
        assertEquals(state.depositors.getValue(bob.lowercase()).ordinal * GroupSwapInWallet.STRIDE + 1, number)
        val protocol = state.protocolAt(number)!!
        assertEquals(keys.address(DepositBranch.of(chatRoomId, bob), 1).protocol, protocol)
        assertEquals(bob to 1L, state.ownerOf(protocol))
        assertNull(state.protocolAt(GroupSwapInWallet.STRIDE * 40))
    }

    @Test
    fun `a coin on an address that is no depositor's never reaches the node`() {
        val electrum = FakeElectrum()
        val wallet = GroupSwapInWallet(keys, electrum, scope)
        wallet.watchDepositors(mapOf(alice to DepositBranch.of(chatRoomId, alice)))
        eventually { wallet.state.first { it.currentAddressOf(alice) != null } }
        // Carol has no depositor statement: her branch's address is the group's in form, and nobody's to count.
        eventually { electrum.pay(script(carol, 0), Satoshi(10_000)) }
        eventually { electrum.pay(script(alice, 0), Satoshi(20_000)) }

        val state = eventually { wallet.state.first { it.forNode.utxos.isNotEmpty() } }
        assertEquals(listOf(Satoshi(20_000)), state.forNode.utxos.map { it.amount })
        assertNull(state.ownerOf(keys.address(DepositBranch.of(chatRoomId, carol), 0).protocol))
    }

    @Test
    fun `the group's swap-in keys look an address up by the node's number`() {
        val electrum = FakeElectrum()
        val wallet = GroupSwapInWallet(keys, electrum, scope)
        wallet.watchDepositors(mapOf(alice to DepositBranch.of(chatRoomId, alice)))
        eventually { wallet.latest.first { it?.currentAddressOf(alice) != null } }
        val group = IcebergSigner.keygen(4, 2, ByteVector32(Hex.decode("5a".repeat(32))))
        val swapInKeys = GroupSwapInKeys(wallet, IcebergSwapInSigner(group))
        assertEquals(keys.address(DepositBranch.of(chatRoomId, alice), 2).protocol, swapInKeys.getSwapInProtocol(GroupSwapInState.nodeIndex(0, 2)))
        assertFailsWith<IllegalStateException> { swapInKeys.getSwapInProtocol(GroupSwapInWallet.STRIDE * 7) }
        assertFailsWith<UnsupportedOperationException> { swapInKeys.localServerPrivateKey(key("1").publicKey()) }
    }

    /** D10: whatever else it has, a group's node never runs on the node seed's own swap-in keys. */
    @Test
    fun `a group node's key manager never hands out the node seed's swap-in keys`() {
        val seedKeys = GroupLightningCoSigner.keyManagerOf(nodeSeed, Chain.Mainnet)
        assertIs<SwapInOnChainKeys>(seedKeys.swapInOnChainWallet)
        val noBasepoints = GroupBasepoints(key("1").publicKey(), key("2").publicKey())
        val withoutSwapIn = GroupNodeKeyManager(seedKeys, IcebergSwapInTestFundingSigner, noBasepoints, ByteVector.empty)
        assertFalse(withoutSwapIn.swapInOnChainWallet is SwapInOnChainKeys, "with no swap-in wallet, nothing the seed derives")
        assertFailsWith<UnsupportedOperationException> { withoutSwapIn.swapInOnChainWallet.getSwapInProtocol(0) }

        val wallet = GroupSwapInWallet(keys, FakeElectrum(), scope)
        val group = IcebergSigner.keygen(4, 2, ByteVector32(Hex.decode("5a".repeat(32))))
        val withSwapIn = GroupNodeKeyManager(seedKeys, IcebergSwapInTestFundingSigner, noBasepoints, ByteVector.empty, GroupSwapInKeys(wallet, IcebergSwapInSigner(group)))
        assertTrue(withSwapIn.swapInOnChainWallet is GroupSwapInKeys)
    }

    /** A funding signer for a key manager that never signs anything in these tests. */
    private object IcebergSwapInTestFundingSigner : fr.acinq.lightning.crypto.FundingSigner by fr.acinq.lightning.crypto.IcebergFundingSigner(
        IcebergSigner.keygen(4, 2, ByteVector32(Hex.decode("1f".repeat(32))))
    )
}
