package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.MarmotInboundProcessor
import com.vitorpamplona.quartz.marmot.MarmotOutboundProcessor
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupManager
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupState
import com.vitorpamplona.quartz.marmot.protocolCore.MarmotPublishGate
import com.vitorpamplona.quartz.marmot.protocolCore.PublishOutcome
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.exceptions.MarmotConvergencePendingException

/**
 * quartz's Marmot engine, over this database, for the rooms on the current profile.
 *
 * `MlsGroupManager` holds the live `MlsGroup` of every group and persists through
 * [RoomMlsGroupStateStore]; `MarmotInboundProcessor` reads kind:445s against them,
 * settling same-epoch commits through its convergence engine and recognising
 * redelivery by the Marmot message id; `MarmotOutboundProcessor` builds the kind:445s
 * this device sends. Everything in docs/marmot-v2.md's "v2 says" column that is not a
 * wire shape lives in these three, and they are built around the store rather than
 * the app's own copy of their logic.
 *
 * **A room's profile picks its pipeline.** Legacy rooms keep the app's own path --
 * `MarmotInboundManager` over `MlsGroupCache`, unchanged -- and retire with it;
 * current-profile rooms go through here. A room is on one profile for its whole
 * life, so the two never hold the same group, which is what keeps the manager's live
 * instance the single writer of a current-profile room's state. The library's
 * processor also has no carve-out for the bare gift wrap a legacy room carries a
 * direct message in, which is the other reason legacy rooms stay where they are.
 *
 * One engine per database, owned by it ([DedunDatabase.marmotEngine]): the manager
 * keys groups by routing address, which is unique across identities, and a room row
 * belongs to one identity anyway.
 *
 * The library's KeyPackage rotation manager is constructed but not fed: Welcomes are
 * matched to this device's bundles by the app's own table, which is profile-aware
 * and matched by reference since Phase 1, and the processor reads the rotation
 * manager only on its own Welcome path, which the app does not use.
 *
 * Two gaps the app's own pipeline had are not closed by moving to this one, and
 * should not be read as closed. A late application message decrypted under a
 * retained prior epoch is decrypted without the sender's leaf signature being
 * verified -- the library's `tryDecryptWithRetainedEpoch` has the same gap the
 * app's had. And the secret tree's skipped keys do not survive `saveState`
 * (docs/mls-skipped-keys.md); the manager holding the live group covers that
 * within a process, exactly as `MlsGroupCache` did, and no further.
 */
class MarmotEngine(
    private val database: DedunDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = Logger.withTag("MarmotEngine")

    val store = RoomMlsGroupStateStore(database)
    val groupManager = MlsGroupManager(store)
    val inbound = MarmotInboundProcessor(groupManager, KeyPackageRotationManager())
    val outbound = MarmotOutboundProcessor(groupManager)

    /**
     * Publish-before-apply for the commits this device makes: a commit is prepared
     * on a clone, recorded durably, broadcast, and installed only when a relay in its
     * scope has accepted it. See [RoomPublishObligationStore] and Phase 6.
     */
    val gate = MarmotPublishGate(groupManager, RoomPublishObligationStore(database))

    private val restoreMutex = Mutex()
    private var restored = false

    /**
     * Load every group from the store, once, and seed convergence with each.
     *
     * Convergence cannot see a fork in a group whose current state it never
     * retained, so a restored group is tracked before its first inbound commit.
     */
    suspend fun ensureRestored() {
        restoreMutex.withLock {
            if (restored) return
            groupManager.restoreAll()
            groupManager.activeGroupIds().forEach { inbound.trackGroup(it) }
            // After the groups: an obligation restored names states of a group the
            // manager has to hold already.
            gate.restore()
            restored = true
            logger.d("Restored ${groupManager.activeGroupIds().size} group(s)")
        }
    }

    /**
     * The live group addressed by [routingId], or null when this device holds no
     * state for it.
     *
     * A room made or joined after the restore is loaded from its row the first time
     * it is asked for and handed to the manager, which owns it from then on.
     */
    suspend fun groupFor(routingId: HexKey): MlsGroup? {
        ensureRestored()
        groupManager.getGroup(routingId)?.let { return it }
        val bytes = store.load(routingId) ?: return null
        val group = MlsGroup.restore(MlsGroupState.decodeTls(bytes))
        groupManager.adoptGroup(routingId, group)
        inbound.trackGroup(routingId)
        return group
    }

    /**
     * A relay accepted the commit behind [obligationId]: the pending state becomes
     * canonical, and convergence learns of the commit we authored -- without which a
     * peer's competing commit would look like an orphan rather than a fork we are
     * half of. Nothing to do for an obligation the gate no longer holds, which is a
     * second OK for the same event from another relay.
     */
    suspend fun confirmPublished(obligationId: HexKey) {
        ensureRestored()
        val obligation = gate.allPending().firstOrNull { it.obligationId == obligationId } ?: return
        gate.resolve(obligationId, PublishOutcome.CONFIRMED)
        inbound.recordLocalCommit(
            groupId = obligation.groupId,
            framedCommitBytes = obligation.outboundBytes,
            sourceEpoch = obligation.priorState.groupContext.epoch,
            preState = obligation.priorState,
        )
        logger.i("Commit ${obligationId.take(8)}… confirmed; ${obligation.groupId.take(8)}… is at epoch ${obligation.pendingState.groupContext.epoch}")

        // The room follows the state it just gained -- a member removed, a group
        // disbanded -- without a caption for what this device asked for itself,
        // which was written when it asked. A disband that has a step left takes it.
        val chatRoomId = database.marmotRoutingDao().chatRoomIdFor(obligation.groupId)
        val localChatRoom = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return
        val group = groupManager.getGroup(obligation.groupId) ?: return
        MarmotInboundManager.processGroupMembershipChanges(database, localChatRoom, group, announceAdditions = false)
        database.marmotOutboundDao().continueDisband(localChatRoom)
    }

    /**
     * Every relay in scope refused [obligationId]: discard its pending state.
     *
     * The live group never advanced, so there is nothing to undo; the gate drops
     * the clone, the lifecycle returns to `STABLE`, and the next commit may be
     * prepared. Nothing is told to convergence -- a commit no relay carries is
     * not a branch any member will ever see. Returns whether there was an
     * obligation to discard.
     */
    suspend fun discardRejected(obligationId: HexKey): Boolean {
        ensureRestored()
        val obligation = gate.allPending().firstOrNull { it.obligationId == obligationId } ?: return false
        gate.resolve(obligationId, PublishOutcome.FAILED)
        logger.w("Commit ${obligationId.take(8)}… discarded; ${obligation.groupId.take(8)}… stays at epoch ${obligation.priorState.groupContext.epoch}")
        return true
    }

    /**
     * Wait for [routingId]'s convergence pass, if one is open, to settle.
     *
     * Outbound work against a state that may lose is work no peer can read once
     * the other branch wins -- a message keyed under the losing epoch, or worse,
     * a commit that makes a three-way fork -- so a message or a commit waits for
     * the pass first: `convergenceStatus(...).allowsOutboundWork` is the
     * library's word for it. A pass settles at the quiescence window, so the wait
     * is a second or two, and it ticks the processor itself rather than relying
     * on [settleLater] having been armed. Past [MAX_SETTLE_WAIT_TICKS] it gives
     * up with [MarmotConvergencePendingException]; the caller's row stays queued
     * and is tried again. The bound is short because most callers hold a Room
     * transaction while they wait.
     */
    suspend fun awaitSettled(routingId: HexKey) {
        repeat(MAX_SETTLE_WAIT_TICKS) {
            if (inbound.convergenceStatus(routingId).allowsOutboundWork) return
            delay(SETTLE_TICK_MS)
            runCatching { inbound.settleDueConvergence() }
                .onFailure { logger.e("Convergence tick failed", it) }
        }
        if (!inbound.convergenceStatus(routingId).allowsOutboundWork) {
            throw MarmotConvergencePendingException(
                "The room is still settling two members' conflicting changes; try again in a moment"
            )
        }
    }

    /**
     * Tick convergence, and keep ticking while any pass is open.
     *
     * Inbound traffic is only an opportunistic tick: a group that goes quiet
     * mid-pass has nothing else to drive it to its cutoff, so this loop runs at the
     * quiescence window until the processor reports no open pass. A resolution that
     * rewound canonical state is logged and nothing more for now -- see
     * docs/marmot-v2.md, Phase 5, on why the transcript is not yet corrected.
     */
    fun settleLater(): Job = scope.launch {
        while (true) {
            delay(SETTLE_TICK_MS)
            val resolutions = runCatching { inbound.settleDueConvergence() }
                .onFailure { logger.e("Convergence tick failed", it) }
                .getOrDefault(emptyList())
            resolutions.forEach { resolution ->
                logger.i(
                    "Convergence for ${resolution.groupId.take(8)}…: ${resolution.status}, epoch ${resolution.canonicalEpoch}" +
                        if (resolution.rewound) " -- REWOUND, the state that was canonical lost" else ""
                )
            }
            if (inbound.openConvergencePasses().isEmpty()) return@launch
        }
    }

    companion object {
        /** The convergence policy's quiescence window: a pass may settle after one quiet second. */
        const val SETTLE_TICK_MS = 1_000L

        /** How many ticks [awaitSettled] gives a pass before handing the wait back to the queue. */
        const val MAX_SETTLE_WAIT_TICKS = 3

        /**
         * [result] as filed under [chatRoomId]. The processor names a group by its
         * routing address; every row the app writes is keyed by the room, so the
         * result is re-addressed once, here, rather than at each consumer.
         */
        fun GroupEventResult.inRoom(chatRoomId: HexKey): GroupEventResult = when (this) {
            is GroupEventResult.ApplicationMessage -> copy(groupId = chatRoomId)
            is GroupEventResult.CommitProcessed -> copy(groupId = chatRoomId)
            is GroupEventResult.CommitPending -> copy(groupId = chatRoomId)
            is GroupEventResult.Duplicate -> copy(groupId = chatRoomId)
            is GroupEventResult.UndecryptableOuterLayer -> copy(groupId = chatRoomId)
            is GroupEventResult.ProposalStaged -> copy(groupId = chatRoomId)
            is GroupEventResult.AppMessageOnCandidateBranch -> copy(groupId = chatRoomId)
            is GroupEventResult.RefusedByLifecycle -> copy(groupId = chatRoomId)
            is GroupEventResult.Error -> copy(groupId = chatRoomId)
        }
    }
}
