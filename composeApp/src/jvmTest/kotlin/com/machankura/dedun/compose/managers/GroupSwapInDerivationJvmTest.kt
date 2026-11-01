package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenixd.conf.LSP
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The group's swap-in addresses, derived (docs/group-lightning-swap-in.md, Phase 1).
 */
class GroupSwapInDerivationJvmTest {
    private fun key(hex: String) = PrivateKey(ByteVector32(Hex.decode(hex.padStart(64, '0'))))

    private val roomKey = key("f0057").publicKey().toHex()
    private val icebergKey = key("1ceb3").publicKey().toHex()
    private val nodeSeed = ByteVector32(Hex.decode("5eed".padStart(64, '0')))
    private val nodeKeys = LocalKeyManager(nodeSeed, Chain.Mainnet, LSP.from(Chain.Mainnet).swapInXpub)
    private val nodeId = nodeKeys.nodeKeys.nodeKey.publicKey.toHex()
    private val chatRoomId = "9a17".repeat(16)
    private val alice = key("a11ce").publicKey().xOnly().value.toHex()
    private val bob = key("b0b").publicKey().xOnly().value.toHex()

    private fun keys() = GroupSwapInDerivation.keys(roomKey, Chain.Mainnet, wallet = 0, icebergKey = icebergKey, nodeId = nodeId)

    @Test
    fun `every device derives the same address from the statements alone`() {
        val branch = DepositBranch.of(chatRoomId, alice)
        assertEquals(keys().address(branch, 3).address, keys().address(branch, 3).address)
        // Pinned: a change to the tag, the step or the order of the keys moves every coin's address.
        assertEquals(PINNED_ADDRESS, keys().address(branch, 0).address)
    }

    @Test
    fun `two members never share an address, nor two indices of one member`() {
        val ours = keys()
        val a = ours.addresses(DepositBranch.of(chatRoomId, alice), upTo = 9).map { it.address }
        val b = ours.addresses(DepositBranch.of(chatRoomId, bob), upTo = 9).map { it.address }
        assertEquals(10, a.toSet().size)
        assertTrue(a.intersect(b.toSet()).isEmpty())
    }

    @Test
    fun `another wallet in the same room has other addresses`() {
        val branch = DepositBranch.of(chatRoomId, alice)
        val next = GroupSwapInDerivation.keys(roomKey, Chain.Mainnet, wallet = 1, icebergKey = icebergKey, nodeId = nodeId)
        assertNotEquals(keys().address(branch, 0).address, next.address(branch, 0).address)
    }

    @Test
    fun `the address is the group's key path and the sweep's own leaf`() {
        val address = keys().address(DepositBranch.of(chatRoomId, alice), 0)
        val leaf = ScriptTree.Leaf(Script.parse(GroupSweep.leafScript(address.refundKey.publicKey.xOnly(), DefaultSwapInParams.RefundDelay.toLong())))
        val outputKey = Musig2.aggregateKeys(listOf(keys().icebergKey, keys().serverKey)).outputKey(Crypto.TaprootTweak.ScriptPathTweak(leaf.hash())).first
        // A P2TR script is OP_1, a 32-byte push, and the output key.
        assertEquals(outputKey.value.toByteArray().toList(), address.protocol.serializedPubkeyScript.toByteArray().drop(2))
        assertEquals(keys().icebergKey, address.protocol.userPublicKey)
    }

    @Test
    fun `the refund key's cache reaches the refund key, so a FROST session can sign as it`() {
        val refund = keys().refundKey(DepositBranch.of(chatRoomId, bob), 7)
        assertEquals(refund.publicKey.xOnly(), refund.cache.tweakedPublicKey)
    }

    @Test
    fun `the LSP's key is the one lightning-kmp derives for the same node`() {
        assertEquals(nodeKeys.swapInOnChainWallet.remoteServerPublicKey, keys().serverKey)
    }

    /** D10: the node seed's own swap-in wallet pays a key every member holds, and must not be confused with these. */
    @Test
    fun `none of them is one of the node seed's own swap-in addresses`() {
        val seedAddresses = (0..9).map { nodeKeys.swapInOnChainWallet.getSwapInProtocol(it).address(Chain.Mainnet) }.toSet()
        val ours = keys().addresses(DepositBranch.of(chatRoomId, alice), upTo = 9).map { it.address }
        assertTrue(ours.none { it in seedAddresses })
    }

    companion object {
        const val PINNED_ADDRESS = "bc1pzxy7a0z7z30fhwwcmtdqjmr6fhwj9kexfvepdp0sccq7ul4q80jqu0rryd"
    }
}
