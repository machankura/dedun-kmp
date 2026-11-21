package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.database.model.GroupInboxWrap
import kotlin.time.Instant

/** What a group's inbox has received, filed and owes relays (docs/group-inbox.md, Phase 3). */
@Dao
interface GroupInboxDao {
    /** The ledger is keyed by the wrap's id: a second copy of one changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWrap(wrap: GroupInboxWrap): Long

    @Query("SELECT * FROM GroupInboxWrap WHERE id = :id")
    suspend fun getWrap(id: HexKey): GroupInboxWrap?

    @Query("SELECT * FROM GroupInboxWrap WHERE chatRoomId = :chatRoomId AND openedGeneration IS NULL ORDER BY receivedAt")
    suspend fun getUnopenedWraps(chatRoomId: String): List<GroupInboxWrap>

    @Query("SELECT COUNT(*) FROM GroupInboxWrap WHERE chatRoomId = :chatRoomId AND openedGeneration IS NULL")
    fun observeUnopenedCount(chatRoomId: String): Flow<Int>

    /** The newest wrap's own time in a room: where a catch-up starts, less NIP-59's two days. */
    @Query("SELECT MAX(wrapCreatedAt) FROM GroupInboxWrap WHERE chatRoomId = :chatRoomId")
    suspend fun newestWrapCreatedAt(chatRoomId: String): Long?

    @Query("UPDATE GroupInboxWrap SET openedGeneration = :generation WHERE id = :id")
    suspend fun markOpened(id: HexKey, generation: Int)

    /** Keyed by room and rumor: two wraps of one rumor are one message, and the first is kept. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: GroupInboxMessage): Long

    @Query("SELECT * FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId AND rumorId = :rumorId")
    suspend fun getMessage(chatRoomId: String, rumorId: HexKey): GroupInboxMessage?

    @Query("SELECT * FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    fun observeMessages(chatRoomId: String): Flow<List<GroupInboxMessage>>

    @Query("SELECT * FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    suspend fun getMessages(chatRoomId: String): List<GroupInboxMessage>

    @Query("SELECT * FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId AND conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeConversation(chatRoomId: String, conversationId: String): Flow<List<GroupInboxMessage>>

    @Query("SELECT * FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId AND conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getConversation(chatRoomId: String, conversationId: String): List<GroupInboxMessage>

    @Query("SELECT COUNT(*) FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId AND outgoing = 0 AND readAt IS NULL")
    fun observeUnreadCount(chatRoomId: String): Flow<Int>

    @Query("UPDATE GroupInboxMessage SET readAt = :at WHERE chatRoomId = :chatRoomId AND conversationId = :conversationId AND readAt IS NULL")
    suspend fun markConversationRead(chatRoomId: String, conversationId: String, at: Instant)

    @Query("DELETE FROM GroupInboxMessage WHERE chatRoomId = :chatRoomId")
    suspend fun deleteMessages(chatRoomId: String)

    @Query("DELETE FROM GroupInboxWrap WHERE chatRoomId = :chatRoomId")
    suspend fun deleteWraps(chatRoomId: String)

    @Upsert
    suspend fun upsertPublication(publication: GroupInboxPublication)

    @Query("SELECT * FROM GroupInboxPublication WHERE eventId = :eventId")
    suspend fun getPublication(eventId: HexKey): GroupInboxPublication?

    /** What is still owed and due by [now], oldest first. */
    @Query("SELECT * FROM GroupInboxPublication WHERE publishedAt IS NULL AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) ORDER BY createdAt")
    suspend fun getDuePublications(now: Instant): List<GroupInboxPublication>

    @Query("SELECT * FROM GroupInboxPublication WHERE signingSessionId = :signingSessionId")
    suspend fun getPublicationsFor(signingSessionId: String): List<GroupInboxPublication>

    @Query("SELECT * FROM GroupInboxPublication WHERE signingSessionId = :signingSessionId")
    fun observePublicationsFor(signingSessionId: String): Flow<List<GroupInboxPublication>>
}
