package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupLightningCoSigner
import com.machankura.dedun.compose.managers.GroupSwapInDerivation
import com.machankura.dedun.compose.ui.view.state.GroupSwapInUIState
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.lightning.GroupSwapInState
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
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.electrum.WalletState.AddressMeta
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What a member sees of the group's swap-in addresses (docs/group-lightning-swap-in.md, Phase 6).
 */
class GroupSwapInViewModelJvmTest {
    private fun key(hex: String) = PrivateKey(ByteVector32(Hex.decode(hex.padStart(64, '0'))))

    private val chatRoomId = "9a17".repeat(16)
    private val alice = key("a11ce").publicKey().xOnly().value.toHex()
    private val bob = key("b0b").publicKey().xOnly().value.toHex()
    private val nodeId = GroupLightningCoSigner.keyManagerOf(ByteVector(Hex.decode("5eed".padStart(64, '0'))), Chain.Mainnet).nodeKeys.nodeKey.publicKey.toHex()
    private val keys = GroupSwapInDerivation.keys(key("f0057").publicKey().toHex(), Chain.Mainnet, 0, key("1ceb3").publicKey().toHex(), nodeId)
    private val tip = 900_000

    private fun address(member: String, index: Int) = keys.address(DepositBranch.of(chatRoomId, member), index.toLong()).address

    private fun coin(index: Int, amount: Long, height: Long): WalletState.Utxo {
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(Satoshi(amount), ByteVector.empty)), 0)
        return WalletState.Utxo(tx.txid, 0, height, tx, AddressMeta.Derived(index))
    }

    private fun depositor(member: String, ordinal: Int, vararg addresses: Pair<Int, List<WalletState.Utxo>?>): GroupSwapInState.Depositor {
        val state = WalletState(addresses.associate { (index, utxos) ->
            address(member, index) to WalletState.AddressState(AddressMeta.Derived(index), alreadyUsed = utxos != null, utxos = utxos.orEmpty())
        })
        return GroupSwapInState.Depositor(member, DepositBranch.of(chatRoomId, member), ordinal, state)
    }

    @Test
    fun `a member with no depositor statement has no address`() {
        val state = GroupSwapInState(keys, tip, mapOf(bob to depositor(bob, 0, 0 to null)))
        assertEquals(GroupSwapInUIState.NotDepositor, GroupSwapInViewModel.stateOf(state, alice))
    }

    @Test
    fun `nothing is shown before Electrum has answered, or before a block is known`() {
        assertEquals(GroupSwapInUIState.Loading, GroupSwapInViewModel.stateOf(GroupSwapInState(keys, tip, mapOf(alice to depositor(alice, 0))), alice))
        assertEquals(GroupSwapInUIState.Loading, GroupSwapInViewModel.stateOf(GroupSwapInState(keys, null, mapOf(alice to depositor(alice, 0, 0 to null))), alice))
    }

    @Test
    fun `a member sees their current address, their coins, and everyone's used addresses`() {
        val mine = coin(0, 50_000, (tip - 1).toLong())
        val state = GroupSwapInState(
            keys, tip,
            mapOf(
                alice to depositor(alice, 0, 0 to listOf(mine), 1 to null, 2 to null),
                bob to depositor(bob, 1, 0 to emptyList(), 1 to null),
            ),
        )
        val loaded = assertIs<GroupSwapInUIState.Loaded>(GroupSwapInViewModel.stateOf(state, alice, mapOf(bob to "bob")))
        assertEquals(address(alice, 1), loaded.current.address)
        assertEquals(SwapInCoin.Status.Confirming(2, 3), loaded.coins.single().status)
        assertEquals(listOf(address(alice, 0)), loaded.used.map { it.address })
        assertEquals(listOf("bob" to listOf(address(bob, 0))), loaded.others.map { (name, used) -> name to used.map { it.address } })
    }
}
