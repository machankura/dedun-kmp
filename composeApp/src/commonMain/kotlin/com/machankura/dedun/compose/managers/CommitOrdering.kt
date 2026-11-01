package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic commit conflict resolution for MLS over Nostr (MIP-03), carried over
 * from quartz 1.14.0's `com.vitorpamplona.quartz.marmot.mip03GroupMessages.CommitOrdering`
 * (MIT, Copyright (c) 2025 Vitor Pamplona).
 *
 * quartz 1.15.2 removed it: its inbound processor resolves same-epoch commits through
 * `protocolCore.MarmotConvergenceEngine` instead, and its changelog calls this rule
 * superseded "and deliberately: both [created_at and event id] are transport metadata
 * the sender chooses and MLS does not authenticate, so a member could win every race by
 * backdating". The app's inbound pipeline does not go through that processor -- it
 * drives [com.vitorpamplona.quartz.marmot.mls.group.MlsGroup] itself -- so the rule is
 * kept here, unchanged, until the pipeline adopts the convergence engine.
 *
 * When multiple members submit Commits for the same epoch, exactly one must win:
 * 1. Lowest created_at timestamp wins
 * 2. If timestamps are equal, lexicographically smallest event id wins
 * 3. All other competing Commits for that epoch are discarded
 */
object CommitOrdering {
    /**
     * Comparator for ordering GroupEvents deterministically.
     * The "winner" (lowest value) should be applied; all others are discarded.
     */
    val comparator: Comparator<GroupEvent> =
        compareBy<GroupEvent> { it.createdAt }
            .thenBy { it.id }

    /**
     * Selects the winning Commit from a set of competing Commits for the same epoch.
     */
    fun selectWinner(commits: List<GroupEvent>): GroupEvent? {
        if (commits.isEmpty()) return null
        return commits.minWithOrNull(comparator)
    }

    /**
     * Key for tracking pending commits: must be unique per (group, epoch) pair
     * since epoch numbers are not globally unique across different groups.
     */
    data class GroupEpochKey(
        val groupId: String,
        val epoch: Long,
    )

    /**
     * Tracks pending commits per (group, epoch) and resolves conflicts.
     */
    class EpochCommitTracker {
        private val mutex = Mutex()
        private val pendingByGroupEpoch = mutableMapOf<GroupEpochKey, MutableList<GroupEvent>>()

        companion object {
            /** Maximum number of (group, epoch) entries to track before evicting oldest. */
            const val MAX_TRACKED_EPOCHS = 1000
        }

        suspend fun addCommit(
            groupId: String,
            epoch: Long,
            commit: GroupEvent,
        ) = mutex.withLock {
            val key = GroupEpochKey(groupId, epoch)
            pendingByGroupEpoch.getOrPut(key) { mutableListOf() }.add(commit)

            // Evict oldest entries if the tracker grows too large
            if (pendingByGroupEpoch.size > MAX_TRACKED_EPOCHS) {
                val oldestKeys =
                    pendingByGroupEpoch.keys
                        .sortedBy { it.epoch }
                        .take(pendingByGroupEpoch.size - MAX_TRACKED_EPOCHS)
                for (oldKey in oldestKeys) {
                    pendingByGroupEpoch.remove(oldKey)
                }
            }
        }

        suspend fun pendingForEpoch(
            groupId: String,
            epoch: Long,
        ): List<GroupEvent> =
            mutex.withLock {
                pendingByGroupEpoch[GroupEpochKey(groupId, epoch)]?.toList() ?: emptyList()
            }

        suspend fun resolve(
            groupId: String,
            epoch: Long,
        ): GroupEvent? =
            mutex.withLock {
                selectWinner(pendingByGroupEpoch[GroupEpochKey(groupId, epoch)] ?: emptyList())
            }

        suspend fun clearEpoch(
            groupId: String,
            epoch: Long,
        ) = mutex.withLock {
            pendingByGroupEpoch.remove(GroupEpochKey(groupId, epoch))
            Unit
        }

        suspend fun pendingGroupEpochs(): Set<GroupEpochKey> =
            mutex.withLock {
                pendingByGroupEpoch.keys.toSet()
            }

        suspend fun clear() =
            mutex.withLock {
                pendingByGroupEpoch.clear()
            }
    }
}
