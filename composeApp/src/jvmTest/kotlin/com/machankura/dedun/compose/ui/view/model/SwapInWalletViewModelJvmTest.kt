package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.managers.GroupLightningNode
import com.machankura.dedun.compose.wallet.lightning.GroupLightningNodes
import com.machankura.dedun.compose.ui.view.state.SwapInWalletUIState
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.SwapInFeeLimit
import com.machankura.dedun.compose.wallet.SwapInNotice
import com.machankura.dedun.compose.wallet.SwapInRefusal
import com.machankura.dedun.compose.wallet.SwapInSnapshot
import com.machankura.dedun.compose.wallet.SwapInWaiting
import com.machankura.dedun.compose.wallet.SwapInSource
import com.machankura.dedun.compose.wallet.SwapInRefunder
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.crypto.SwapInOnChainKeys
import fr.acinq.phoenixd.conf.LSP
import fr.acinq.secp256k1.Hex
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.SwapInWallet
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.electrum.WalletState.AddressMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    @Test
    fun `a ready coin the node refused to move says why`() {
        val mined = utxo(50_000, 0, blockHeight = 100)
        val refusal = SwapInRefusal.OverLimit(Satoshi(6_200), Satoshi(5_000), Satoshi(7_000))
        val node = MutableStateFlow(snapshot(used(0, mined), unused(1), tip = 110).copy(refusal = refusal))
        val viewModel = SwapInWalletViewModel(SwapInSource { node }).also { it.initiate() }
        assertEquals(SwapInWaiting.Refused(refusal), assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState).waiting)

        node.value = node.value.copy(connected = false)
        assertEquals(SwapInWaiting.NotConnected, assertIs<SwapInWalletUIState.Loaded>(viewModel.uiState).waiting)
    }

    @Test
    fun `what the node does with the deposits reaches the screen once each`() = runBlocking {
        val events = MutableSharedFlow<SwapInNotice>(extraBufferCapacity = 4)
        val source = object : SwapInSource {
            override fun snapshots() = MutableStateFlow(snapshot(unused(0), unused(1)))
            override fun notices() = events
        }
        val viewModel = SwapInWalletViewModel(source).also { it.initiate() }
        events.emit(SwapInNotice.Moving(Satoshi(50_000)))
        events.emit(SwapInNotice.Received(Satoshi(48_713)))
        assertEquals(listOf(SwapInNotice.Moving(Satoshi(50_000)), SwapInNotice.Received(Satoshi(48_713))), withTimeout(5_000) { viewModel.notices.take(2).toList() })
    }

    private class FakeFeeLimit(var stored: LiquidityPolicy) : SwapInFeeLimit {
        val allowed = mutableListOf<LiquidityPolicy.Auto>()
        override suspend fun saved() = stored
        override suspend fun allowOnce(policy: LiquidityPolicy.Auto) { allowed += policy }
        override suspend fun save(policy: LiquidityPolicy.Auto) { stored = policy }
    }

    private val savedPolicy = LiquidityPolicy.Auto(null, Satoshi(5_000), 50_00, skipAbsoluteFeeCheck = false, maxAllowedFeeCredit = MilliSatoshi(0))
    private val overLimit = SwapInRefusal.OverLimit(Satoshi(6_200), Satoshi(5_000), Satoshi(50_000))

    @Test
    fun `reviewing a refused fee offers the fee plus the margin as the new limit`() {
        val viewModel = SwapInWalletViewModel(source = null, feeLimit = FakeFeeLimit(savedPolicy))
        viewModel.review(overLimit)
        assertEquals(overLimit, viewModel.reviewing)
        assertEquals("6820", viewModel.limitField.text.toString())
        assertNull(viewModel.limitProblem())
    }

    @Test
    fun `a limit of nothing, or of more than the coins, is not saved`() {
        val fees = FakeFeeLimit(savedPolicy)
        val viewModel = SwapInWalletViewModel(source = null, feeLimit = fees)
        viewModel.review(overLimit)

        viewModel.limitField.setTextAndPlaceCursorAtEnd("0")
        assertEquals(SwapInWalletViewModel.LimitProblem.Zero, viewModel.limitProblem())
        viewModel.limitField.setTextAndPlaceCursorAtEnd("60,000")
        assertEquals(SwapInWalletViewModel.LimitProblem.OverAmount(Satoshi(50_000)), viewModel.limitProblem())
        viewModel.saveLimit {}
        assertEquals(savedPolicy, fees.stored)
    }

    @Test
    fun `allowing a fee once raises the running limit only, and closes the sheet`() {
        val fees = FakeFeeLimit(savedPolicy)
        val viewModel = SwapInWalletViewModel(source = null, feeLimit = fees)
        var done = 0
        viewModel.review(overLimit)
        viewModel.allowOnce { done++ }
        assertEquals(listOf(savedPolicy.copy(maxAbsoluteFee = Satoshi(6_820))), fees.allowed)
        assertEquals(savedPolicy, fees.stored)
        assertNull(viewModel.reviewing)
        assertEquals(1, done)
    }

    @Test
    fun `saving a limit stores it`() {
        val fees = FakeFeeLimit(savedPolicy)
        val viewModel = SwapInWalletViewModel(source = null, feeLimit = fees)
        viewModel.review(overLimit)
        viewModel.limitField.setTextAndPlaceCursorAtEnd("8,000")
        viewModel.saveLimit {}
        assertEquals(savedPolicy.copy(maxAbsoluteFee = Satoshi(8_000)), fees.stored)
        assertNull(viewModel.reviewing)
    }

    @Test
    fun `a policy that is off has no fee to allow`() {
        val fees = FakeFeeLimit(LiquidityPolicy.Disable)
        val viewModel = SwapInWalletViewModel(source = null, feeLimit = fees)
        viewModel.review(overLimit)
        viewModel.allowOnce {}
        assertTrue(fees.allowed.isEmpty())
        assertEquals(overLimit, viewModel.reviewing)
    }

    // ---- the refund ----

    private val refundChain = Chain.Testnet3
    private val refundKeys = LocalKeyManager(randomBytes32(), refundChain, LSP.from(refundChain).swapInXpub).swapInOnChainWallet
    private val elsewhere = Bitcoin.computeBIP86Address(PrivateKey(ByteVector32(Hex.decode("f1".padStart(64, '0')))).publicKey(), refundChain.chainHash)

    private class FakeRefunder(override val chain: Chain, val swapInKeys: SwapInOnChainKeys, var usage: UnusedAddressCheck.Usage = UnusedAddressCheck.Usage.Unused) : SwapInRefunder {
        val broadcast = mutableListOf<Transaction>()
        var fails = false
        override suspend fun keys() = swapInKeys
        override suspend fun feerate() = FeeratePerKw(Satoshi(2_500))
        override suspend fun usage(script: ByteVector) = usage
        override suspend fun broadcast(tx: Transaction): TxId {
            if (fails) error("no server")
            broadcast += tx
            return tx.txid
        }
    }

    /** One coin at index 0 of [refundKeys]'s wallet, [confirmations] deep at tip 3,000,000. */
    private fun refundSnapshot(confirmations: Int): SwapInSnapshot {
        val protocol = refundKeys.getSwapInProtocol(0)
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(Satoshi(50_000), protocol.pubkeyScript)), 0)
        val tip = 3_000_000
        val utxo = WalletState.Utxo(tx.txid, 0, (tip - confirmations + 1).toLong(), tx, AddressMeta.Derived(0))
        return SwapInSnapshot(
            WalletState(mapOf(protocol.address(refundChain) to WalletState.AddressState(AddressMeta.Derived(0), true, listOf(utxo)), unused(1))),
            tip, emptySet(), params, legacyAddress = "legacy",
        )
    }

    private fun refunding(snapshot: SwapInSnapshot, refunder: FakeRefunder) =
        SwapInWalletViewModel(SwapInSource { MutableStateFlow(snapshot) }, refunder = refunder).also { it.initiate() }

    @Test
    fun `a refund is checked, shown with its fee, and sent only on confirmation`() {
        val refunder = FakeRefunder(refundChain, refundKeys)
        val viewModel = refunding(refundSnapshot(params.refundDelay), refunder)
        viewModel.startRefund()
        viewModel.refundAddressField.setTextAndPlaceCursorAtEnd(elsewhere)
        viewModel.reviewRefund()

        val review = assertIs<SwapInWalletViewModel.RefundStep.Review>(viewModel.refund)
        assertEquals(elsewhere, review.address)
        assertEquals(Satoshi(50_000), review.preview.refunded)
        assertTrue(refunder.broadcast.isEmpty())

        var sent: TxId? = null
        viewModel.sendRefund { sent = it }
        assertEquals(listOf(review.preview.tx), refunder.broadcast)
        assertEquals(review.preview.tx.txid, sent)
        assertNull(viewModel.refund)
    }

    @Test
    fun `a refund is refused to an address that is not one, is the wallet's own, or has been paid`() {
        val refunder = FakeRefunder(refundChain, refundKeys)
        val snapshot = refundSnapshot(params.refundDelay)
        val viewModel = refunding(snapshot, refunder)
        viewModel.startRefund()

        viewModel.refundAddressField.setTextAndPlaceCursorAtEnd("nope")
        viewModel.reviewRefund()
        assertEquals(SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.UNREADABLE), viewModel.refund)

        viewModel.refundAddressField.setTextAndPlaceCursorAtEnd(snapshot.state.addresses.keys.first())
        viewModel.reviewRefund()
        assertEquals(SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.OWN_DEPOSIT), viewModel.refund)

        refunder.usage = UnusedAddressCheck.Usage.Used
        viewModel.refundAddressField.setTextAndPlaceCursorAtEnd(elsewhere)
        viewModel.reviewRefund()
        assertEquals(SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.USED), viewModel.refund)

        refunder.usage = UnusedAddressCheck.Usage.Unknown
        viewModel.reviewRefund()
        assertEquals(SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.UNCHECKED), viewModel.refund)
        assertTrue(refunder.broadcast.isEmpty())
    }

    @Test
    fun `before the refund delay there is nothing to refund`() {
        val viewModel = refunding(refundSnapshot(params.refundDelay - 1), FakeRefunder(refundChain, refundKeys))
        viewModel.startRefund()
        viewModel.refundAddressField.setTextAndPlaceCursorAtEnd(elsewhere)
        viewModel.reviewRefund()
        assertEquals(SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.NOTHING_TO_REFUND), viewModel.refund)
    }

    @Test
    fun `a broadcast that fails says so, and can be tried again`() {
        val refunder = FakeRefunder(refundChain, refundKeys).apply { fails = true }
        val viewModel = refunding(refundSnapshot(params.refundDelay), refunder)
        viewModel.startRefund()
        viewModel.refundAddressField.setTextAndPlaceCursorAtEnd(elsewhere)
        viewModel.reviewRefund()
        viewModel.sendRefund {}
        assertEquals(SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.NOT_SENT), viewModel.refund)
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
