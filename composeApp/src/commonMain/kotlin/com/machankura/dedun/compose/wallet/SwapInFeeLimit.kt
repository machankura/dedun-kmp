package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.SwapInEvents
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.phoenix.managers.PeerManager
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The fee a swap-in may cost, as the user changes it from the swap-in screen
 * (docs/personal-swap-in-wallet.md, D6 and Phase 5).
 */
object SwapInFeeLimits {
    /** What a one-off allowance adds to the refused fee: the next try is at the next block's feerate. */
    const val MARGIN_PERCENT: Long = 10

    fun withMargin(fee: Satoshi): Satoshi = Satoshi(fee.sat + (fee.sat * MARGIN_PERCENT + 99) / 100)

    /**
     * [saved] raised just far enough for [refusal]'s fee, with the margin: the absolute cap for a fee over
     * it, and for a fee over the share, the share -- and the cap with it, so the raised share is not
     * then refused on the other test. Null for a refusal no fee would get through.
     */
    fun allowingOnce(saved: LiquidityPolicy.Auto, refusal: SwapInRefusal): LiquidityPolicy.Auto? = when (refusal) {
        is SwapInRefusal.OverLimit -> saved.copy(maxAbsoluteFee = maxOf(saved.maxAbsoluteFee, withMargin(refusal.fee)))
        is SwapInRefusal.OverShare -> {
            val fee = withMargin(refusal.fee)
            val share = ((fee.sat * 10_000 + refusal.amount.sat - 1) / refusal.amount.sat).toInt()
            saved.copy(
                maxAbsoluteFee = maxOf(saved.maxAbsoluteFee, fee),
                maxRelativeFeeBasisPoints = maxOf(saved.maxRelativeFeeBasisPoints, share),
            )
        }
        SwapInRefusal.Disabled, SwapInRefusal.Other -> null
    }

    /** [saved] with its absolute cap set to [limit], and nothing else changed. */
    fun withLimit(saved: LiquidityPolicy.Auto, limit: Satoshi): LiquidityPolicy.Auto = saved.copy(maxAbsoluteFee = limit)
}

/** Where the swap-in screen reads and changes the node's fee limit: the personal node, or a test's fake. */
interface SwapInFeeLimit {
    /** The policy the user saved, which a restart starts the node with. */
    suspend fun saved(): LiquidityPolicy

    /** Runs the node with [policy] until the next swap-in is accepted, without saving it. */
    suspend fun allowOnce(policy: LiquidityPolicy.Auto)

    /** Saves [policy] and runs the node with it from now on. */
    suspend fun save(policy: LiquidityPolicy.Auto)
}

/**
 * A fee limit over a running node: [apply] sets the policy it runs with, [store] and [stored] the
 * user's saved one, and [accepted] fires each time the node's LSP takes a swap-in.
 *
 * A one-off allowance is put back to the saved policy after the next acceptance, in [scope] -- the
 * node's, so that leaving the screen does not leave the raise in place -- and by a restart, which
 * reads only what was stored. A later allowance or save replaces a pending put-back.
 */
class NodeFeeLimit(
    private val apply: suspend (LiquidityPolicy) -> Unit,
    private val stored: Flow<LiquidityPolicy>,
    private val store: suspend (LiquidityPolicy) -> Unit,
    private val accepted: Flow<Unit>,
    private val scope: CoroutineScope,
) : SwapInFeeLimit {
    private var restoring: Job? = null

    override suspend fun saved(): LiquidityPolicy = stored.first()

    override suspend fun allowOnce(policy: LiquidityPolicy.Auto) {
        restoring?.cancel()
        apply(policy)
        restoring = scope.launch {
            accepted.first()
            apply(stored.first())
        }
    }

    override suspend fun save(policy: LiquidityPolicy.Auto) {
        restoring?.cancel()
        store(policy)
        apply(policy)
    }

    companion object {
        /** Over the profile's node and its preferences, with the put-back in the node's own scope. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun of(peerManager: PeerManager, userPrefs: UserPrefs): NodeFeeLimit = NodeFeeLimit(
            apply = peerManager::updatePeerLiquidityPolicy,
            stored = userPrefs.getLiquidityPolicy,
            store = userPrefs::saveLiquidityPolicy,
            accepted = peerManager.peerState.filterNotNull()
                .flatMapLatest { it.nodeParams.nodeEvents }
                .filterIsInstance<SwapInEvents.Accepted>()
                .map { },
            scope = peerManager,
        )
    }
}
