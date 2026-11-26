package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.mls.group.MlsGroup
import kotlin.time.Clock
import com.machankura.dedun.compose.database.DedunDatabase

/**
 * `Participant.adminAt`, kept in line with the group's own admin policy.
 *
 * The policy is the truth -- `AdminPolicyV1` on the current profile,
 * `MarmotGroupData.admin_pubkeys` on the legacy one -- and it is what the library
 * enforces and what `isLocalAdmin()` reads. The column is this device's cache of
 * it, for the screens that label members. A cache with three writers drifted:
 * the creator caught its rows up by hand, a commit reconciled them, and the
 * Welcome wrote none at all, so on every device but the creator's a robust
 * group's admin room showed no admins until some later commit happened to
 * arrive. docs/robust-group-admins.md, G2 and D2.
 *
 * So there is one writer, and every path that changes what the policy says or
 * which rows exist calls it: the room's creation, the Welcome, and every commit.
 *
 * Flags only, never membership. Removing the rows of members the tree no longer
 * holds is `MarmotInboundManager.processGroupMembershipChanges`'s job, on a
 * commit; doing it here would turn a best-effort step -- a partly failed invite
 * at creation, say -- into a partial membership.
 */
object AdminFlags {
    private val logger = Logger.withTag("AdminFlags")

    /**
     * Flags the rows of [chatRoomId] that [mlsGroup]'s admin policy names and
     * clears the rest. Reads the rows fresh, so it can run straight after an
     * upsert that added some.
     *
     * @return how many rows changed.
     */
    suspend fun reconcile(database: DedunDatabase, chatRoomId: String, mlsGroup: MlsGroup): Int {
        val admins = mlsGroup.currentAdminIdentities()
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return 0
        val now = Clock.System.now()

        val changed = room.localParticipants.map { it.participant }.mapNotNull { participant ->
            val named = participant.participantPublicKey in admins
            when {
                named && participant.adminAt == null -> participant.copy(adminAt = now)
                !named && participant.adminAt != null -> participant.copy(adminAt = null)
                else -> null
            }
        }

        if (changed.isNotEmpty()) {
            logger.d("$chatRoomId: ${changed.size} admin flag(s) brought in line with the group")
            database.participantDao().upsert(changed)
        }
        return changed.size
    }

    /**
     * [reconcile] for every room this device holds MLS state for and is still in.
     *
     * Run once per start, for the rooms made before the Welcome wrote any flags:
     * a joiner's rows there flag nobody until some commit arrives, and in a
     * robust group's admin room one may never arrive. No stored "done" flag -- a
     * room whose rows already agree is a read and no write, so a second run costs
     * one decode per room and a preference would only be one more thing to keep
     * right. docs/robust-group-admins.md, Phase 6.
     *
     * One room that will not decode is logged and passed over, not fatal to the
     * rest.
     *
     * @return how many rows changed, across every room.
     */
    suspend fun reconcileAll(database: DedunDatabase): Int =
        database.chatRoomDao().getChatRoomIdsWithMlsState().sumOf { chatRoomId ->
            runCatching {
                val group = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.toMlsGroup()
                    ?: return@runCatching 0
                reconcile(database, chatRoomId, group)
            }.getOrElse {
                logger.e("Could not bring $chatRoomId's admin flags in line", it)
                0
            }
        }
}
