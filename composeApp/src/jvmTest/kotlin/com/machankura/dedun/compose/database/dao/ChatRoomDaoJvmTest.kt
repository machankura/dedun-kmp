package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The chat list's order and its previews both come out of one query, so a single wrong
 * join reads as two unrelated bugs: rooms sorted by nothing anybody can see, and a row
 * quoting a message that is not the newest one in it.
 *
 * The parts that can only be checked against a real SQLite are here -- which row the
 * correlated subquery picks, and how the ordering treats a room nothing has been said
 * in. What the picked row is then rendered as is [com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom]'s
 * own business and is tested against it directly.
 */
class ChatRoomDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = "a".repeat(64)
    private val quiet = "11".repeat(32)
    private val busy = "22".repeat(32)
    private val stale = "33".repeat(32)

    /** Rooms created oldest-first, so creation order and activity order disagree. */
    private suspend fun seedRooms() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = user,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId))

        listOf(stale to 100L, busy to 200L, quiet to 300L).forEach { (id, createdAt) ->
            db.chatRoomDao().upsert(
                ChatRoom(
                    id = id,
                    userPublicKey = user,
                    subject = null,
                    description = null,
                    mlsGroupState = null,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                )
            )
        }
    }

    private suspend fun line(
        chatRoomId: String,
        content: String = "a line",
        createdAt: Long,
        deletedAt: Long? = null,
    ): Long = db.chatMessageDao().upsert(
        ChatMessage(
            content = content,
            chatRoomId = chatRoomId,
            senderPublicKey = user,
            isUserMessage = true,
            giftWrapPayloadId = null,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            createdAt = Instant.fromEpochSeconds(createdAt),
            deletedAt = deletedAt?.let { Instant.fromEpochSeconds(it) },
        )
    )

    private suspend fun rooms() = db.chatRoomDao().getChatRoomListByUserPublicKey(user)

    @Test
    fun `rooms are ordered by their newest message`() = runBlocking {
        seedRooms()
        line(stale, createdAt = 1_000)
        line(busy, createdAt = 9_000)
        line(quiet, createdAt = 5_000)

        assertEquals(listOf(busy, quiet, stale), rooms().map { it.chatRoom.id })
    }

    /**
     * A room nothing has been said in still has to land somewhere, and the only time it
     * has is its own. Sorting it last regardless would bury a room the user just made
     * under every conversation they have ever had.
     */
    @Test
    fun `a room with no messages sorts on when it was created`() = runBlocking {
        seedRooms()
        line(stale, createdAt = 50)
        line(busy, createdAt = 250)

        // quiet was created at 300, so it outranks both.
        assertEquals(listOf(quiet, busy, stale), rooms().map { it.chatRoom.id })
        assertNull(rooms().first().lastChatMessage)
    }

    @Test
    fun `the newest line in a room is the one carried`() = runBlocking {
        seedRooms()
        line(busy, content = "older", createdAt = 1_000)
        line(busy, content = "newest", createdAt = 2_000)
        line(quiet, content = "someone else's room", createdAt = 3_000)

        val lastChatMessage = rooms().first { it.chatRoom.id == busy }.lastChatMessage

        assertNotNull(lastChatMessage)
        assertEquals("newest", lastChatMessage.content)
        assertEquals(Instant.fromEpochSeconds(2_000), lastChatMessage.createdAt)
    }

    /**
     * Timestamps are stored to the second, so a burst written in one second ties. The
     * transcript orders those on their row ids, and the preview has to agree with it --
     * a room whose last two lines arrived together must not quote whichever one SQLite
     * happened to reach first.
     */
    @Test
    fun `lines written in the same second break the tie on write order`() = runBlocking {
        seedRooms()
        line(busy, content = "first", createdAt = 1_000)
        line(busy, content = "second", createdAt = 1_000)
        line(busy, content = "third", createdAt = 1_000)

        assertEquals("third", rooms().first { it.chatRoom.id == busy }.lastChatMessage?.content)
    }

    @Test
    fun `a deleted line is not the room's last message`() = runBlocking {
        seedRooms()
        line(busy, content = "kept", createdAt = 1_000)
        line(busy, content = "deleted", createdAt = 2_000, deletedAt = 2_500)

        assertEquals("kept", rooms().first { it.chatRoom.id == busy }.lastChatMessage?.content)
    }

    /** One room's traffic must not become another room's preview. */
    @Test
    fun `the last message is scoped to its own room`() = runBlocking {
        seedRooms()
        line(busy, content = "in the busy room", createdAt = 9_000)

        assertEquals("in the busy room", rooms().first { it.chatRoom.id == busy }.lastChatMessage?.content)
        assertNull(rooms().first { it.chatRoom.id == quiet }.lastChatMessage)
        assertNull(rooms().first { it.chatRoom.id == stale }.lastChatMessage)
    }

    /** The single-room lookup reads the same shape, so a detail screen sees what the list did. */
    @Test
    fun `finding one room carries its last message too`() = runBlocking {
        seedRooms()
        line(busy, content = "newest", createdAt = 2_000)

        assertEquals("newest", db.chatRoomDao().findChatRoomById(busy)?.lastChatMessage?.content)
        assertNull(db.chatRoomDao().findChatRoomById(quiet)?.lastChatMessage)
    }
}
