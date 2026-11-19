package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.GroupEventResult
import com.vitorpamplona.marmot.MarmotInboundProcessor
import com.vitorpamplona.marmot.MarmotOutboundProcessor
import com.vitorpamplona.marmot.groups.MarmotGroupPolicy
import com.vitorpamplona.marmot.groups.MlsGroupManager
import com.vitorpamplona.marmot.mip00KeyPackages.KeyPackageRotationManager
import com.vitorpamplona.marmot.protocolCore.MarmotPublishGate
import com.vitorpamplona.marmot.protocolCore.PublishOutcome
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.group.MlsGroupState
import com.vitorpamplona.mls.group.OwnSenderRatchet
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
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
 * One gap the app's own pipeline had is not closed by moving to this one, and should
 * not be read as closed: a late application message decrypted under a retained prior
 * epoch is decrypted without the sender's leaf signature being verified -- the
 * library's `tryDecryptWithRetainedEpoch` has the same gap the app's had.
 *
 * The other one is closed. The secret tree's skipped keys reach the state the
 * manager would save -- mls-kmp carries them, which quartz's engine did not -- and
 * [processGroupEvent] writes that state after an application message, so they reach
 * the row as well (docs/mls-skipped-keys.md). Read inbound events through that
 * rather than through [inbound] directly, or the write is lost.
 *
 * Which state is written where is now split by what moved. An epoch change is a full
 * save, by the manager, as it applies. An application message received is a full
 * save, here. An application message *sent* moves only this leaf's place in the
 * secret tree, and that is a `MarmotSenderRatchet` row rather than the group --
 * which is why [groupFor] has to put the record back before the manager adopts a
 * group, and says there what goes wrong if it does not.
 */
class MarmotEngine(
    private val database: DedunDatabase,
    /** Lives as long as the database, which the app never closes; a test that does stops this first. */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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

    /**
     * The next step of a two-commit change, by the group whose commit it waits on:
     * removing an admin is a demotion and then the removal, and leaving as an admin
     * is a step-down and then the leave. Run when that group's commit is confirmed,
     * dropped when every relay refuses it.
     *
     * Keyed by group because the gate allows one commit in flight per group, so the
     * next confirmation for it is the one this waits on. In memory on purpose: a
     * restart between the two leaves a demoted member who has not been removed, or
     * an ex-admin who has not left, and either can be finished with one tap.
     * docs/member-admin-promotion.md, Phases 6 and 10.
     */
    private val followUps = mutableMapOf<HexKey, suspend () -> Unit>()
    private val followUpsMutex = Mutex()

    /**
     * Runs [step] once [routingId]'s next commit is confirmed. Registered *before*
     * that commit is published, so a relay's OK cannot arrive ahead of it; a caller
     * whose publish then fails takes it back with [cancelFollowUp].
     */
    suspend fun afterConfirmed(routingId: HexKey, step: suspend () -> Unit) {
        followUpsMutex.withLock { followUps[routingId] = step }
    }

    /** Drops the step [afterConfirmed] registered for [routingId], if any. */
    suspend fun cancelFollowUp(routingId: HexKey) {
        takeFollowUp(routingId)
    }

    private suspend fun takeFollowUp(routingId: HexKey): (suspend () -> Unit)? =
        followUpsMutex.withLock { followUps.remove(routingId) }
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
        val group = MlsGroup.restore(MlsGroupState.decodeTls(bytes), MarmotGroupPolicy)
        applySenderRatchetRecord(routingId, group)
        groupManager.adoptGroup(routingId, group)
        inbound.trackGroup(routingId)
        return group
    }

    /**
     * Move [group] up to the sender ratchet position its own row records, as
     * `MlsGroupManager.restoreAll` does for the groups it loads.
     *
     * **Before `adoptGroup`, not after.** Adopting persists, and persisting rewrites
     * the ratchet row from the group it was handed -- so a group adopted at the
     * restored state's older position overwrites the record with that position, and
     * the generations between the two are published a second time under keys and
     * nonces already used. This is the half of the split the library cannot do for
     * the app: `restoreAll` is its own path and applies the record itself, while this
     * one is the app's, for a room first touched after that restore.
     *
     * The library decides whether the record applies at all: it is accepted only for
     * the epoch, the leaf and the exact secret tree it was taken from, and then only
     * where it moves a chain forward. So a record that does not belong to this state
     * is simply not applied.
     */
    private suspend fun applySenderRatchetRecord(routingId: HexKey, group: MlsGroup) {
        val bytes = store.loadSenderRatchet(routingId) ?: return
        val record = OwnSenderRatchet.decodeTlsOrNull(bytes) ?: return
        if (group.restoreOwnSenderRatchet(record)) {
            logger.d("${routingId.take(8)}… resumes at application generation ${record.applicationGeneration}")
        }
    }

    /**
     * Read one kind:445 against this database's groups, and write what the library
     * leaves in memory.
     *
     * The processor's own persistence is the manager's: a commit that advanced the
     * epoch is saved as it is applied, a staged proposal is saved as it is staged.
     * An application message is the one result that moves state and writes nothing,
     * because the manager has no small record for the receive side the way it has for
     * the send side -- decrypting generation N+1 before N derives N's key and keeps it
     * (RFC 9420 §9.1), and those keys are `(leafIndex, generation)` pairs for other
     * members' ratchets, which live in the group state and nowhere else.
     *
     * Not writing them does not lose the late message, and it is worth being exact
     * about that, because the opposite is what the original bug was.
     * `MlsGroupState` carries the skipped keys beside the ratchet positions they
     * belong to, so a state saved without a receive is an older *coherent* state, and
     * a position that has not passed the late generation derives it forward. quartz's
     * engine saved the positions and dropped the keys, which is the pair that lost
     * messages. What an unwritten row costs is the deriving -- every generation since
     * the last full save, re-derived on each restart, against `SecretTree`'s
     * thousand-step forward cap.
     *
     * The reason the write is here is the send side. Until the sender ratchet became
     * its own row, every sent message wrote the whole state, so the row could not
     * fall far behind inside an epoch; now nothing else would update it between epoch
     * changes, and a long-lived room would drift. It is a full save, which is the cost
     * the legacy pipeline has always paid -- `MlsGroupCache` saves the whole state
     * after every inbound event -- and it is paid on the receive path, where the
     * device is already doing an AEAD open and a database write for the event itself.
     *
     * The snapshot is taken through `readGroup`, under the lock the manager applies
     * commits under, so it cannot catch a commit half applied.
     *
     * See docs/mls-skipped-keys.md and docs/marmot-kmp.md, Phase 8.
     */
    suspend fun processGroupEvent(groupEvent: GroupEvent): GroupEventResult {
        ensureRestored()
        val result = inbound.processGroupEvent(groupEvent)
        if (result is GroupEventResult.ApplicationMessage) {
            val encoded = groupManager.readGroup(result.groupId) { it.saveState().encodeTls() }
            if (encoded != null) store.save(result.groupId, encoded)
        }
        return result
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
        MarmotInboundManager.processGroupMembershipChanges(
            database, localChatRoom, group, announceAdditions = false,
            priorAdmins = MarmotGroupPolicy.adminIdentitiesIn(obligation.priorState.groupContext.extensions),
        )
        database.marmotOutboundDao().continueDisband(localChatRoom)
        takeFollowUp(obligation.groupId)?.let { step ->
            runCatching { step() }.onFailure { logger.w("The step after ${obligation.groupId.take(8)}…'s commit failed: ${it.message}") }
        }
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
        takeFollowUp(obligation.groupId)
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
