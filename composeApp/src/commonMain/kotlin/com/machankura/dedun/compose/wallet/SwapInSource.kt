package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.OutPoint
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.SwapInManager
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.phoenix.managers.PeerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest

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
)

/** Where the swap-in screen's snapshots come from: the personal node, or a test's fake. */
fun interface SwapInSource {
    fun snapshots(): Flow<SwapInSnapshot>
}

/**
 * The personal node's swap-in wallet, through the [PeerManager] of the profile's `PhoenixBusiness`.
 *
 * It takes a Phoenix [PeerManager] and nothing else on purpose: the group's node is a bare `Peer` behind
 * `GroupLightningNode.PeerHandle`, and its swap-in wallet pays a 2-of-2 with a key nobody serves. It must
 * never be listed (docs/personal-swap-in-wallet.md, D1), and this type cannot be built from it.
 */
class PeerSwapInSource(private val peerManager: PeerManager) : SwapInSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun snapshots(): Flow<SwapInSnapshot> = peerManager.peerState.filterNotNull().flatMapLatest { peer ->
        val wallet = peer.swapInWallet ?: return@flatMapLatest emptyFlow()
        // The live channels are empty until the LSP has answered; until then the ones read from the
        // database say which coins a funding attempt holds, as PeerManager's own channels flow does.
        val channels = combine(peer.bootChannelsFlow, peer.channelsFlow) { boot, live -> live.ifEmpty { boot.orEmpty() } }
        combine(wallet.wallet.walletStateFlow, peer.currentTipFlow, channels) { state, tip, current ->
            SwapInSnapshot(
                state = state,
                tip = tip,
                reserved = SwapInManager.reservedWalletInputs(current.values.filterIsInstance<PersistedChannelState>()),
                params = peer.walletParams.swapInParams,
                legacyAddress = wallet.legacySwapInAddress,
            )
        }
    }
}
