package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.ChatMessage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow

/**
 * The room's transcript, newest first -- the order the feed's `reverseLayout` draws from the
 * bottom up, so the first row is the line sitting above the composer.
 *
 * `id DESC` is not decoration. [com.machankura.dedun.compose.database.converters.DedunConverters]
 * stores an `Instant` as epoch seconds, so lines written inside one second tie: a ceremony
 * puts a dozen into a room faster than that, and a request with the answer it triggers
 * routinely lands inside one. `createdAt DESC` alone left those to whatever order the sorter
 * produced -- the rows as scanned, which under a descending query drew a same-second burst
 * backwards, and is free to change under a new index or a different plan, swapping two lines
 * in a transcript the user has already read.
 *
 * The key [ChatRoomDao] already breaks its last-message tie on, which is what makes the chat
 * list's preview and the bottom of the room it opens name the same line.
 *
 * One constant for both reads because they are the same list: the snapshot a screen loads
 * with and the flow it then follows must not order it differently.
 */
private const val TRANSCRIPT =
    "SELECT * FROM ChatMessage WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC, id DESC"

@Dao
interface ChatMessageDao {
    @Transaction
    @Query(TRANSCRIPT)
    fun observeChatMessagesByChatRoomId(chatRoomId: String): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage>>

    /** A room's lines of [types], newest first. */
    @Query("SELECT * FROM ChatMessage WHERE chatRoomId = :chatRoomId AND messageType IN (:types) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun linesOfTypes(chatRoomId: String, types: List<String>, limit: Int): List<ChatMessage>

    @Transaction
    @Query(TRANSCRIPT)
    suspend fun getChatMessagesByChatRoomId(chatRoomId: String): List<com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage>

    @Query("SELECT * FROM ChatMessage WHERE giftWrapPayloadId = :giftWrapPayloadId ORDER BY createdAt DESC")
    suspend fun getChatMessagesByGiftWrapPayloadId(giftWrapPayloadId: HexKey): ChatMessage?

    @Query("SELECT * FROM ChatMessage WHERE marmotInnerEventId = :marmotInnerEventId ORDER BY createdAt DESC")
    suspend fun getChatMessagesByMarmotInnerEventId(marmotInnerEventId: HexKey): ChatMessage?

    /** The room's line of [messageType] about one Lightning wallet fact, if it has one. */
    @Query("SELECT * FROM ChatMessage WHERE chatRoomId = :chatRoomId AND messageType = :messageType AND lightningFactKey = :lightningFactKey LIMIT 1")
    suspend fun getLightningFactLine(chatRoomId: String, messageType: String, lightningFactKey: String): ChatMessage?

    @Query("SELECT * FROM ChatMessage WHERE marmotGroupEventId = :marmotGroupEventId ORDER BY createdAt DESC")
    suspend fun getChatMessagesByMarmotGroupEventId(marmotGroupEventId: HexKey): ChatMessage?

    @Query("SELECT COUNT(*) FROM ChatMessage WHERE chatRoomId = :chatRoomId AND senderPublicKey = :senderPublicKey")
    suspend fun countChatMessagesBySenderPublicKey(chatRoomId: HexKey, senderPublicKey: HexKey): Int

    /**
     * The room's group events that already have something to show for them.
     *
     * One query so a replay can skip the events it has no business touching without
     * a lookup per event -- `marmotGroupEventId` carries no index, so each of those
     * is a scan of the whole table.
     *
     * [unresolvedTypes] is [com.machankura.dedun.compose.database.model.ChatMessage.UNRESOLVED_MARMOT_TYPES]:
     * lines that stand in for a message still to come rather than reporting one.
     */
    @Query(
        "SELECT marmotGroupEventId FROM ChatMessage " +
            "WHERE chatRoomId = :chatRoomId AND marmotGroupEventId IS NOT NULL " +
            "AND messageType NOT IN (:unresolvedTypes)"
    )
    suspend fun getResolvedMarmotGroupEventIds(
        chatRoomId: String,
        unresolvedTypes: Collection<String>
    ): List<HexKey>

    @Upsert
    suspend fun upsert(chatMessage: com.machankura.dedun.compose.database.model.ChatMessage): Long
}