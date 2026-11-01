package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import kotlinx.coroutines.flow.Flow

/**
 * The chat-list queries carry the room's newest line with them.
 *
 * A correlated subquery rather than a `GROUP BY chatRoomId` with `MAX(createdAt)`: an
 * [com.machankura.dedun.compose.database.model.ChatMessage] timestamp is stored to the second,
 * so a burst of ritual lines written in the same second ties, and the aggregate would
 * pick one of them arbitrarily. Falling back to `id DESC` breaks the tie on the order
 * the rows were actually written, which is the order the transcript shows them in.
 *
 * A room with nothing said in it sorts on when it was created, so a freshly made room
 * appears at the top where the user left it rather than at the bottom under everything.
 */
private const val CHAT_ROOM_WITH_LAST_MESSAGE =
    "SELECT ChatRoom.*, " +
        "lastMessage.senderPublicKey AS lastMessage_senderPublicKey, " +
        "lastMessage.isUserMessage AS lastMessage_isUserMessage, " +
        "lastMessage.content AS lastMessage_content, " +
        "lastMessage.messageType AS lastMessage_messageType, " +
        "lastMessage.directMessageRecipientPublicKey AS lastMessage_directMessageRecipientPublicKey, " +
        "lastMessage.createdAt AS lastMessage_createdAt " +
        "FROM ChatRoom " +
        "LEFT JOIN ChatMessage AS lastMessage ON lastMessage.id = (" +
        "SELECT id FROM ChatMessage " +
        "WHERE chatRoomId = ChatRoom.id AND deletedAt IS NULL " +
        "ORDER BY createdAt DESC, id DESC LIMIT 1" +
        ") "

private const val ORDER_BY_LAST_ACTIVITY =
    "ORDER BY COALESCE(lastMessage.createdAt, ChatRoom.createdAt) DESC, ChatRoom.createdAt DESC"

@Dao
interface ChatRoomDao {
    @Transaction
    @Query(CHAT_ROOM_WITH_LAST_MESSAGE + "WHERE ChatRoom.id = :id AND ChatRoom.deletedAt IS NULL")
    suspend fun findChatRoomById(id: String): LocalChatRoom?

    @Transaction
    @Query(
        CHAT_ROOM_WITH_LAST_MESSAGE +
            "WHERE ChatRoom.userPublicKey = :userPublicKey AND ChatRoom.deletedAt IS NULL " +
            ORDER_BY_LAST_ACTIVITY
    )
    suspend fun getChatRoomListByUserPublicKey(userPublicKey: String): List<LocalChatRoom>

    /**
     * Re-emits when a message lands as well as when a room changes -- the query reads
     * ChatMessage, so Room invalidates it on both, which is what keeps a row's preview
     * and its place in the order current without the list asking.
     */
    @Transaction
    @Query(
        CHAT_ROOM_WITH_LAST_MESSAGE +
            "WHERE ChatRoom.userPublicKey = :userPublicKey AND ChatRoom.deletedAt IS NULL " +
            ORDER_BY_LAST_ACTIVITY
    )
    fun observeChatRoomListByUserPublicKey(userPublicKey: String): Flow<List<LocalChatRoom>>

    /**
     * The subgroups of [parentChatRoomId] this device actually holds rooms for.
     *
     * Deliberately not the answer to "what subgroups does this group have" --
     * that is `SubgroupManager.subgroupsOf`, which reads the parent's signed
     * certificates and so can name a child whose room has not been created, or
     * was created for somebody else. This names the ones there is something to
     * open.
     *
     * Soft-deleted rooms are excluded like everywhere else: a room the user has
     * cleared away should not reappear because its parent lists it.
     */
    @Transaction
    @Query(
        CHAT_ROOM_WITH_LAST_MESSAGE +
            "WHERE ChatRoom.parentChatRoomId = :parentChatRoomId AND ChatRoom.deletedAt IS NULL " +
            ORDER_BY_LAST_ACTIVITY
    )
    fun observeByParentChatRoomId(parentChatRoomId: String): Flow<List<LocalChatRoom>>

    /**
     * The ids of every room holding MLS state that this device is still in. What the
     * Marmot engine restores at startup -- ids only, because it loads each state on
     * its own and the relations the room queries above carry would be wasted here.
     */
    @Query("SELECT id FROM ChatRoom WHERE mlsGroupState IS NOT NULL AND deletedAt IS NULL AND leftGroupAt IS NULL")
    suspend fun getChatRoomIdsWithMlsState(): List<String>

    @Query("SELECT mlsGroupState FROM ChatRoom WHERE id = :id")
    suspend fun getMlsGroupState(id: String): String?

    @Query("SELECT COUNT(*) > 0 FROM ChatRoom WHERE id = :id")
    suspend fun exists(id: String): Boolean

    @Query("UPDATE ChatRoom SET mlsGroupState = :mlsGroupState WHERE id = :id")
    suspend fun updateMlsGroupState(id: String, mlsGroupState: String?)

    @Upsert
    suspend fun upsert(chatRoom: ChatRoom)

    @Delete
    suspend fun delete(chatRoom: ChatRoom)

}
