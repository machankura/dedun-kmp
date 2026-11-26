package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.NotificationLedger

/**
 * Read marks, the notification cursor and the background sync's record.
 * docs/notifications-and-background-sync.md, Phase 3.
 *
 * Every mark here only moves forward. A read mark moved back would raise an unread count the
 * user already cleared; a cursor moved back would post again what was already posted.
 */
@Dao
abstract class NotificationDao {

    // -- read marks (D8) ---------------------------------------------------------------------

    /** Marks [chatRoomId] read up to [upTo], unless it is already read further. */
    @Query(
        "UPDATE ChatRoom SET lastReadMessageId = :upTo " +
            "WHERE id = :chatRoomId AND (lastReadMessageId IS NULL OR lastReadMessageId < :upTo)"
    )
    abstract suspend fun markRead(chatRoomId: String, upTo: Long): Int

    /** Marks [chatRoomId] read up to its newest line. */
    @Query(
        "UPDATE ChatRoom SET lastReadMessageId = (SELECT MAX(id) FROM ChatMessage WHERE chatRoomId = ChatRoom.id) " +
            "WHERE id = :chatRoomId AND EXISTS (SELECT 1 FROM ChatMessage WHERE chatRoomId = ChatRoom.id " +
            "AND (ChatRoom.lastReadMessageId IS NULL OR id > ChatRoom.lastReadMessageId))"
    )
    abstract suspend fun markAllRead(chatRoomId: String): Int

    /** The newest line in [chatRoomId], read or not. */
    @Query("SELECT MAX(id) FROM ChatMessage WHERE chatRoomId = :chatRoomId")
    abstract suspend fun newestMessageId(chatRoomId: String): Long?

    /**
     * Lines in [chatRoomId] the user has not been shown: said by somebody else, above the read
     * mark, and in [kinds] -- the conversation, not the transcript of ceremonies around it.
     */
    @Query(
        "SELECT COUNT(*) FROM ChatMessage JOIN ChatRoom ON ChatRoom.id = ChatMessage.chatRoomId " +
            "WHERE ChatMessage.chatRoomId = :chatRoomId AND ChatMessage.isUserMessage = 0 " +
            "AND ChatMessage.deletedAt IS NULL AND ChatMessage.messageType IN (:kinds) " +
            "AND ChatMessage.id > COALESCE(ChatRoom.lastReadMessageId, 0)"
    )
    abstract suspend fun unreadCount(chatRoomId: String, kinds: List<String> = UNREAD_KINDS): Int

    /** Unread lines per room, for every room [userPublicKey] is in and has not left or deleted. */
    @Query(
        "SELECT ChatMessage.chatRoomId AS chatRoomId, COUNT(*) AS unread, " +
            "ChatRoom.notifications AS notifications FROM ChatMessage " +
            "JOIN ChatRoom ON ChatRoom.id = ChatMessage.chatRoomId " +
            "WHERE ChatRoom.userPublicKey = :userPublicKey AND ChatRoom.deletedAt IS NULL " +
            "AND ChatRoom.leftGroupAt IS NULL AND ChatMessage.isUserMessage = 0 " +
            "AND ChatMessage.deletedAt IS NULL AND ChatMessage.messageType IN (:kinds) " +
            "AND ChatMessage.id > COALESCE(ChatRoom.lastReadMessageId, 0) " +
            "GROUP BY ChatMessage.chatRoomId"
    )
    abstract fun observeUnreadCounts(userPublicKey: HexKey, kinds: List<String> = UNREAD_KINDS): Flow<List<RoomUnread>>

    @Query("UPDATE ChatRoom SET notifications = :notifications WHERE id = :chatRoomId")
    abstract suspend fun setNotifications(chatRoomId: String, notifications: String)

    // -- the cursor (D2, D3) -----------------------------------------------------------------

    @Query("SELECT * FROM NotificationLedger WHERE userPublicKey = :userPublicKey")
    abstract suspend fun getLedger(userPublicKey: HexKey): NotificationLedger?

    @Query("SELECT * FROM NotificationLedger WHERE userPublicKey = :userPublicKey")
    abstract fun observeLedger(userPublicKey: HexKey): Flow<NotificationLedger?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLedger(ledger: NotificationLedger): Long

    @Query(
        "SELECT MAX(ChatMessage.id) FROM ChatMessage JOIN ChatRoom ON ChatRoom.id = ChatMessage.chatRoomId " +
            "WHERE ChatRoom.userPublicKey = :userPublicKey"
    )
    protected abstract suspend fun newestMessageIdFor(userPublicKey: HexKey): Long?

    @Query(
        "UPDATE ChatRoom SET lastReadMessageId = (SELECT MAX(id) FROM ChatMessage WHERE chatRoomId = ChatRoom.id) " +
            "WHERE userPublicKey = :userPublicKey"
    )
    protected abstract suspend fun markEverythingReadFor(userPublicKey: HexKey)

    /**
     * Starts notifying for [userPublicKey], once: the cursor at the newest line it holds, and every
     * room read up to its newest line -- what a device had when it started counting is not news.
     * Returns false, and changes nothing, when it was already armed.
     */
    @Transaction
    open suspend fun arm(userPublicKey: HexKey, at: Instant): Boolean {
        if (getLedger(userPublicKey) != null) return false
        markEverythingReadFor(userPublicKey)
        insertLedger(
            NotificationLedger(
                userPublicKey = userPublicKey,
                armedAt = at,
                cursorMessageId = newestMessageIdFor(userPublicKey) ?: 0,
            )
        )
        return true
    }

    /** Moves the cursor to [cursorMessageId], unless it is already past it. */
    @Query(
        "UPDATE NotificationLedger SET cursorMessageId = :cursorMessageId " +
            "WHERE userPublicKey = :userPublicKey AND cursorMessageId < :cursorMessageId"
    )
    abstract suspend fun advanceCursor(userPublicKey: HexKey, cursorMessageId: Long): Int

    /**
     * Lines above [cursor] in [userPublicKey]'s rooms that somebody else wrote, oldest first, at most
     * [limit] of them. The dispatcher decides about these and moves the cursor past them.
     */
    @Query(
        "SELECT ChatMessage.* FROM ChatMessage JOIN ChatRoom ON ChatRoom.id = ChatMessage.chatRoomId " +
            "WHERE ChatRoom.userPublicKey = :userPublicKey AND ChatMessage.id > :cursor " +
            "ORDER BY ChatMessage.id ASC LIMIT :limit"
    )
    abstract fun observeAfter(userPublicKey: HexKey, cursor: Long, limit: Int = 200): Flow<List<ChatMessage>>

    @Query(
        "SELECT ChatMessage.* FROM ChatMessage JOIN ChatRoom ON ChatRoom.id = ChatMessage.chatRoomId " +
            "WHERE ChatRoom.userPublicKey = :userPublicKey AND ChatMessage.id > :cursor " +
            "ORDER BY ChatMessage.id ASC LIMIT :limit"
    )
    abstract suspend fun getAfter(userPublicKey: HexKey, cursor: Long, limit: Int = 200): List<ChatMessage>

    /** Writes the switches, leaving the cursor and the sync record alone. */
    @Query(
        "UPDATE NotificationLedger SET enabled = :enabled, messages = :messages, needsYou = :needsYou, " +
            "groupActivity = :groupActivity, showMessageText = :showMessageText WHERE userPublicKey = :userPublicKey"
    )
    abstract suspend fun setSettings(
        userPublicKey: HexKey,
        enabled: Boolean,
        messages: Boolean,
        needsYou: Boolean,
        groupActivity: Boolean,
        showMessageText: Boolean,
    ): Int

    // -- the background sync's record (D5) ---------------------------------------------------

    @Query(
        "UPDATE NotificationLedger SET lastBackgroundSyncAt = :at, lastBackgroundSyncOutcome = :outcome, " +
            "lastBackgroundSyncKind = :kind WHERE userPublicKey = :userPublicKey"
    )
    abstract suspend fun recordBackgroundSync(userPublicKey: HexKey, at: Instant, outcome: String, kind: String): Int

    companion object {
        /** What an unread count counts: the conversation. */
        val UNREAD_KINDS = listOf(ChatMessage.TYPE_MESSAGE, ChatMessage.TYPE_DIRECT_MESSAGE)
    }
}

/** One room's unread count, and whether the room is muted -- a muted room counts, quietly. */
data class RoomUnread(
    val chatRoomId: String,
    val unread: Int,
    val notifications: String,
) {
    val isMuted: Boolean get() = notifications == com.machankura.dedun.compose.database.model.ChatRoom.NOTIFICATIONS_MUTED
}
