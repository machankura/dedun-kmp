package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.managers.GroupLightningNode
import com.machankura.dedun.compose.wallet.lightning.GroupLightningNodes
import com.machankura.dedun.compose.ui.view.state.SwapInWalletUIState
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.SwapInSnapshot
import com.machankura.dedun.compose.wallet.SwapInSource
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.SwapInWallet
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.electrum.WalletState.AddressMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The swap-in screen's state over a fake node (docs/personal-swap-in-wallet.md, Phase 2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapInWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val params = SwapInParams(DefaultSwapInParams.MinConfirmations, DefaultSwapInParams.MaxConfirmations, DefaultSwapInParams.RefundDelay)

    private fun utxo(amount: Long, index: Int, blockHeight: Long): WalletState.Utxo {
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(Satoshi(amount), ByteVector.empty)), 0)
        return WalletState.Utxo(tx.txid, 0, blockHeight, tx, AddressMeta.Derived(index))
    }

    private fun unused(index: Int) = "a$index" to WalletState.AddressState(AddressMeta.Derived(index), alreadyUsed = false, utxos = emptyList())
    private fun used(index: Int, vararg utxos: WalletState.Utxo) = "a$index" to WalletState.AddressState(AddressMeta.Derived(index), alreadyUsed = true, utxos = utxos.toList())

    private fun snapshot(vararg entries: Pair<String, WalletState.AddressState>, tip: Int? = 100, reserved: Set<OutPoint> = emptySet()) =
        SwapInSnapshot(WalletState(entries.toMap()), tip, reserved, params, legacyAddress = "legacy")

    @Test
    fun `a profile with no node has no swap-in wallet, and reading it does nothing`() {
        val viewModel = SwapInWalletViewModel(source = null)
        viewModel.initiate()
        assertEquals(SwapInWalletUIState.NoNode, viewModel.uiState)
    }

    @Test
    fun `nothing is shown before Electrum has answered`() {
        val node = MutableStateFlow(SwapInSnapshot(WalletState.empty, tip = 100, reserved = emptySet(), params = params, legacyAddress = "legacy"))
        val viewModel = SwapInWalletViewModel(SwapInSource { node }).also { it.initiate() }
        assertEquals(SwapInWalletUIState.Loading, viewModel.uiState)
    }

    @Test
    fun `nothing is shown before the node has heard of a block`() {
        val node = MutableStateFlow(snapshot(unused(0), unused(1), tip = null))
        val viewModel = SwapInWalletViewModel(SwapInSource { node }).also { it.initiate() }
        assertEquals(SwapInWalletUIState.Loading, viewModel.uiState)
    }

    @Test
    fun `a fresh wallet shows its address and no coins`() {
        val node = MutableStateFlow(snapshot(unused(0), unused(1), unused(2)))
        val viewModel = SwapInWalletViewModel(SwapInSource { node }).also { it.initiate() }
        val loaded = assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState)
        assertEquals("a0", loaded.current.address)
        assertTrue(loaded.used.isEmpty())
        assertTrue(loaded.coins.isEmpty())
    }

    @Test
    fun `a payment moves the address on and its coin confirms with the chain`() {
        val node = MutableStateFlow(snapshot(unused(0), unused(1), unused(2)))
        val viewModel = SwapInWalletViewModel(SwapInSource { node }).also { it.initiate() }

        val coin = utxo(50_000, 0, blockHeight = 0)
        node.value = snapshot(used(0, coin), unused(1), unused(2), unused(3))
        var loaded = assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState)
        assertEquals("a1", loaded.current.address)
        assertEquals(listOf(SwapInAddress("a0", 0, SwapInAddress.Status.USED, Satoshi(50_000))), loaded.used)
        assertEquals(SwapInCoin.Status.Unconfirmed, loaded.coins.single().status)

        val mined = utxo(50_000, 0, blockHeight = 100)
        node.value = snapshot(used(0, mined), unused(1), unused(2), unused(3), tip = 102)
        loaded = assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState)
        assertEquals(SwapInCoin.Status.Ready, loaded.coins.single().status)

        node.value = snapshot(used(0, mined), unused(1), unused(2), unused(3), tip = 102, reserved = setOf(mined.outPoint))
        loaded = assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState)
        assertEquals(SwapInCoin.Status.Swapping, loaded.coins.single().status)
    }

    @Test
    fun `a node that fails to answer is an error, and a retry reads it again`() {
        var fail = true
        val viewModel = SwapInWalletViewModel(SwapInSource {
            flow {
                if (fail) error("the node is gone")
                emit(snapshot(unused(0), unused(1)))
            }
        })
        viewModel.initiate()
        assertEquals(SwapInWalletUIState.Error, viewModel.uiState)

        fail = false
        viewModel.initiate()
        assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState)
    }

    /**
     * D1: the group's node pays a 2-of-2 with a swap-in key nobody serves, so nothing may ever list its
     * addresses. Nothing it exposes hands out a swap-in wallet; one added later fails here, not in review.
     */
    @Test
    fun `the group's node exposes no swap-in wallet`() {
        val types = listOf(GroupLightningNode::class.java, GroupLightningNode.PeerHandle::class.java, GroupLightningNodes::class.java)
        val exposed = types.flatMap { type ->
            type.methods.filter { method ->
                method.name.contains("swapIn", ignoreCase = true) || SwapInWallet::class.java.isAssignableFrom(method.returnType)
            }.map { "${type.simpleName}.${it.name}" }
        }
        assertEquals(emptyList<String>(), exposed)
    }
}
