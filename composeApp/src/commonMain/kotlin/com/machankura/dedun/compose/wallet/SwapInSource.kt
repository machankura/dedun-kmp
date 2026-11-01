package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.SwapInManager
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.PaymentEvents
import fr.acinq.lightning.SwapInEvents
import fr.acinq.lightning.db.OnChainIncomingPayment
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.sum
import fr.acinq.phoenix.data.Notification
import fr.acinq.phoenix.managers.NotificationsManager
import fr.acinq.phoenix.managers.PeerManager
import fr.acinq.phoenix.utils.extensions.isTerminated
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan

/**
 * Everything the swap-in screen reads off the node, at one moment.
 *
 * [tip] is null until the node has heard of a block: a coin's confirmations cannot be counted before.
 */
data class SwapInSnapshot(
    val state: WalletState,
    val tip: Int?,
    /** The coins an unconfirmed funding transaction spends; see [SwapInWalletView.coins]. */
    val reserved: Set<OutPoint>,
    val params: SwapInParams,
    val legacyAddress: String,
    /** Whether the node has a channel that is not closed: a deposit then splices in, rather than opening one. */
    val hasChannel: Boolean = false,
    /** What opening a first channel with a deposit costs at today's fees; see [SwapInWalletView.openingFee]. */
    val openingFee: Satoshi? = null,
    /** The node's liquidity policy, which every swap-in's fee is checked against. */
    val policy: LiquidityPolicy? = null,
    /** Whether the node is connected to the LSP, without which no coin moves. */
    val connected: Boolean = true,
    /** Why the node last refused to move the ready coins, or null since it last asked to move them. */
    val refusal: SwapInRefusal? = null,
)

/** Something the node just did with the deposits, worth a snackbar (docs/personal-swap-in-wallet.md, Phase 4). */
sealed interface SwapInNotice {
    /** The node asked the LSP to move [amount] into Lightning. */
    data class Moving(val amount: Satoshi) : SwapInNotice

    /** The splice or channel confirmed, and [amount] is now Lightning balance. */
    data class Received(val amount: Satoshi) : SwapInNotice
}

/** Where the swap-in screen's snapshots come from: the personal node, or a test's fake. */
fun interface SwapInSource {
    fun snapshots(): Flow<SwapInSnapshot>

    fun notices(): Flow<SwapInNotice> = emptyFlow()
}

/**
 * The personal node's swap-in wallet, through the [PeerManager] of the profile's `PhoenixBusiness`.
 *
 * It takes a Phoenix [PeerManager] and nothing else on purpose: the group's node is a bare `Peer` behind
 * `GroupLightningNode.PeerHandle`, and its swap-in wallet pays a 2-of-2 with a key nobody serves. It must
 * never be listed (docs/personal-swap-in-wallet.md, D1), and this type cannot be built from it.
 */
class PeerSwapInSource(
    private val peerManager: PeerManager,
    /** Where `PeerManager` files each refusal: the one that came before the screen opened is read back from it. */
    private val notificationsManager: NotificationsManager,
) : SwapInSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun snapshots(): Flow<SwapInSnapshot> = peerManager.peerState.filterNotNull().flatMapLatest { peer ->
        val wallet = peer.swapInWallet ?: return@flatMapLatest emptyFlow()
        // The live channels are empty until the LSP has answered; until then the ones read from the
        // database say which coins a funding attempt holds, as PeerManager's own channels flow does.
        val channels = combine(peer.bootChannelsFlow, peer.channelsFlow) { boot, live -> live.ifEmpty { boot.orEmpty() } }
        // Every swap-in address has the same shape, so any one of them prices the channel a deposit opens.
        val swapInScript = peer.nodeParams.keyManager.swapInOnChainWallet.getSwapInProtocol(0).serializedPubkeyScript
        val pricing = combine(peer.remoteFundingRates, peer.peerFeeratesFlow, peer.nodeParams.liquidityPolicy) { rates, feerates, policy ->
            val target = (policy as? LiquidityPolicy.Auto)?.inboundLiquidityTarget ?: 1.sat
            val fee = if (rates != null && feerates != null) SwapInWalletView.openingFee(swapInScript, rates, feerates.fundingFeerate, target) else null
            fee to policy
        }
        // The node's events are not replayed, so the refusal starts from the last one filed, if any; each
        // new refusal replaces it, and a request to move the coins clears it.
        val refusal = peer.nodeParams.nodeEvents
            .mapNotNull { event ->
                when (event) {
                    is LiquidityEvents.Rejected -> event.takeIf { it.source == LiquidityEvents.Source.OnChainWallet }?.let { listOf(SwapInRefusal.of(it.reason, it.amount, it.fee)) }
                    is SwapInEvents.Requested -> listOf(null)
                    else -> null
                }
            }
            .scan(lastFiledRefusal()) { _, update -> update.single() }
        val link = combine(peer.connectionState, refusal) { connection, last -> (connection is Connection.ESTABLISHED) to last }
        combine(wallet.wallet.walletStateFlow, peer.currentTipFlow, channels, pricing, link) { state, tip, current, (openingFee, policy), (connected, last) ->
            SwapInSnapshot(
                state = state,
                tip = tip,
                reserved = SwapInManager.reservedWalletInputs(current.values.filterIsInstance<PersistedChannelState>()),
                params = peer.walletParams.swapInParams,
                legacyAddress = wallet.legacySwapInAddress,
                hasChannel = current.values.any { !it.isTerminated() },
                openingFee = openingFee,
                policy = policy,
                connected = connected,
                refusal = last,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun notices(): Flow<SwapInNotice> = peerManager.peerState.filterNotNull().flatMapLatest { peer ->
        peer.nodeParams.nodeEvents.mapNotNull { event ->
            when (event) {
                is SwapInEvents.Requested -> SwapInNotice.Moving(event.walletInputs.map { it.amount }.sum())
                is PaymentEvents.PaymentReceived -> (event.payment as? OnChainIncomingPayment)?.let { SwapInNotice.Received(it.amountReceived.truncateToSatoshi()) }
                else -> null
            }
        }
    }

    /** The newest refusal of a deposit `PeerManager` has filed and nobody has read, if any. */
    private fun lastFiledRefusal(): SwapInRefusal? = notificationsManager.notifications.value
        .map { it.second }
        .filterIsInstance<Notification.PaymentRejected>()
        .filter { it.source == LiquidityEvents.Source.OnChainWallet }
        .maxByOrNull { it.createdAt }
        ?.let(SwapInRefusal::of)
}
