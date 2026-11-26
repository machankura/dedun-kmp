package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

/**
 * When the group's inbox key has to change because its admins have (docs/group-inbox.md, D14).
 *
 * The rule is read off what every admin's device already holds, rather than off the change that
 * caused it: the key was made by a ceremony over some set of admins, and the room's admin policy
 * now names another. That covers a removal, a demotion, a step-down and a promotion the same way,
 * and a change that landed while the app was closed.
 *
 * One admin starts it, so the devices do not race: the one whose key sorts first among the
 * admins now. Nobody is asked -- the group's signature on the admin change was the decision --
 * and if that admin's device is away, any admin can rotate by hand from the inbox's settings.
 * It waits for whatever share rotation the same change owes ([InboxKeyRitualManager.refusal]
 * reads the same hold), so the two run in turn.
 */
object InboxRotation {
    private val logger = Logger.withTag("InboxRotation")

    /**
     * Whether [localChatRoom]'s inbox key was made over admins the room no longer has, as
     * [userPublicKey]'s device sees it. False where there is no key, or the admins cannot be read.
     */
    suspend fun isOwed(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey): Boolean {
        val roomId = localChatRoom.chatRoom.id
        val keys = database.inboxKeyDao().getKeys(roomId, userPublicKey).filter { it.state != InboxKeyState.WIPED }
        if (keys.isEmpty()) return false
        val newest = database.inboxKeyDao().getCompletedCeremonies(roomId, userPublicKey).firstOrNull() ?: return false
        val admins = RoomAdmins.of(database, roomId)?.admins ?: return false
        return admins != newest.participants().toSet()
    }

    /** Whether [userPublicKey] is the admin whose device starts an owed rotation in [chatRoomId]. */
    suspend fun startsIt(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): Boolean =
        RoomAdmins.of(database, chatRoomId)?.admins?.minOrNull() == userPublicKey

    /**
     * Starts every rotation [userPublicKey]'s device owes. Run by the governance follow-through and
     * the sweep; repeats nothing, since a running ceremony is not started twice. Returns how many it
     * started.
     */
    suspend fun pass(database: DedunDatabase, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Int {
        if (!ExperimentalFeatures.groupInbox) return 0
        var started = 0
        database.inboxKeyDao().getHeldKeys(userPublicKey).map { it.chatRoomId }.distinct().forEach { roomId ->
            val room = database.chatRoomDao().findChatRoomById(roomId) ?: return@forEach
            if (!isOwed(database, room, userPublicKey) || !startsIt(database, roomId, userPublicKey)) return@forEach
            if (database.inboxKeyDao().getRunningCeremoniesIn(roomId).any { it.userPublicKey == userPublicKey }) return@forEach
            if (InboxKeyRitualManager.refusal(database, room, userPublicKey) != null) return@forEach
            try {
                InboxKeyRitualManager.propose(database, room, userPublicKey, nostrPrivateKey)
                started += 1
                logger.i("The admins of ${roomId.take(8)} changed; rotating its inbox key")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.w("Could not rotate the inbox key of ${roomId.take(8)}: ${e.message}")
            }
        }
        return started
    }
}
