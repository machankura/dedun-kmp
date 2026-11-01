package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `getResolvedMarmotGroupEventIds` is the query a reindex sweep subtracts from the room's
 * stored group events to decide what to replay, so its answer decides what work the sweep does.
 * Both ways of being wrong are quiet. Report an event as resolved when it is not and the replay
 * skips the one event that needed it -- the message stays missing from the feed with nothing
 * left to trigger another attempt. Report it as unresolved when it is resolved and every sweep
 * re-decrypts it forever.
 *
 * The distinction rests entirely on `messageType NOT IN (:unresolvedTypes)`, where the
 * unresolved types are the two placeholder lines that stand in for a message still to come
 * rather than reporting one.
 */
class ChatMessageDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = "a".repeat(64)
    private val other = "b".repeat(64)
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

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
        listOf(roomOne, roomTwo).forEach { id ->
            db.chatRoomDao().upsert(
                ChatRoom(
                    id = id,
                    userPublicKey = user,
                    subject = null,
                    description = null,
                    mlsGroupState = null,
                )
            )
        }
    }

    /** A stored kind:445 plus its indexed row, which a chat line's foreign key hangs off. */
    private suspend fun seedGroupEvent(id: String, chatRoomId: String = roomOne): String {
        val eventId = id.padEnd(64, '0')
        db.nostrEventDao().upsert(
            NostrEvent(
                id = eventId,
                pubKey = user,
                kind = MarmotGroupEvent.KIND,
                tags = arrayOf(arrayOf("h", chatRoomId)),
                content = "ciphertext",
                sig = "0".repeat(128),
            )
        )
        db.marmotGroupEventDao().upsert(
            MarmotGroupEvent(
                id = eventId,
                userPublicKey = user,
                publicKey = user,
                chatRoomId = chatRoomId,
                signature = "0".repeat(128),
                encryptedContent = "ciphertext",
                expiresAt = null,
            )
        )
        return eventId
    }

    private suspend fun line(
        groupEventId: String?,
        messageType: String = ChatMessage.TYPE_DIRECT_MESSAGE,
        chatRoomId: String = roomOne,
        content: String = "a line",
        sender: String = user,
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
    ): Long = db.chatMessageDao().upsert(
        ChatMessage(
            content = content,
            chatRoomId = chatRoomId,
            senderPublicKey = sender,
            isUserMessage = sender == user,
            giftWrapPayloadId = null,
            marmotGroupEventId = groupEventId,
            marmotInnerEventId = null,
            messageType = messageType,
            createdAt = createdAt,
        )
    )

    private suspend fun resolved(chatRoomId: String = roomOne) =
        db.chatMessageDao().getResolvedMarmotGroupEventIds(
            chatRoomId = chatRoomId,
            unresolvedTypes = ChatMessage.UNRESOLVED_MARMOT_TYPES,
        )

    @Test
    fun `an event with a real line is resolved`() = runBlocking {
        seedRooms()
        val eventId = seedGroupEvent("1")
        line(eventId)

        assertEquals(listOf(eventId), resolved())
    }

    /**
     * The two placeholder types. An undecryptable outer layer stands in for a message that
     * could not be opened yet, and a pending commit for one whose commit has not arrived --
     * both are exactly what a replay exists to retry, so neither may count as resolved.
     */
    @Test
    fun `a placeholder line leaves its event unresolved`() = runBlocking {
        seedRooms()
        val undecryptable = seedGroupEvent("1")
        val pendingCommit = seedGroupEvent("2")
        line(undecryptable, messageType = ChatMessage.TYPE_UNDECRYPTABLE_OUTER_LAYER)
        line(pendingCommit, messageType = ChatMessage.TYPE_PENDING_COMMIT)

        val found = resolved()

        assertTrue(undecryptable !in found, "an undecryptable placeholder is not a resolution")
        assertTrue(pendingCommit !in found, "a pending commit is not a resolution")
        assertTrue(found.isEmpty())
    }

    /** The sweep's subtraction: only the placeholder-backed event is left to replay. */
    @Test
    fun `a replay is left with exactly the events that still have nothing to show`() = runBlocking {
        seedRooms()
        val settled = seedGroupEvent("1")
        val stillWaiting = seedGroupEvent("2")
        val neverSeen = seedGroupEvent("3")
        line(settled)
        line(stillWaiting, messageType = ChatMessage.TYPE_PENDING_COMMIT)

        val unresolved = listOf(settled, stillWaiting, neverSeen) - resolved().toSet()

        assertEquals(listOf(stillWaiting, neverSeen), unresolved)
    }

    /**
     * Lines that are not about a group event -- a NIP-17 direct message, a locally written
     * line -- carry a null marmotGroupEventId, and `IS NOT NULL` keeps them out. Without it
     * the result would carry nulls into a set the sweep subtracts with.
     */
    @Test
    fun `lines with no group event are not reported as resolutions`() = runBlocking {
        seedRooms()
        line(groupEventId = null)

        assertTrue(resolved().isEmpty())
    }

    /** A sweep runs per room, so another room's resolved lines must not shorten its work. */
    @Test
    fun `resolutions are scoped to their own room`() = runBlocking {
        seedRooms()
        val mine = seedGroupEvent("1", chatRoomId = roomOne)
        val theirs = seedGroupEvent("2", chatRoomId = roomTwo)
        line(mine, chatRoomId = roomOne)
        line(theirs, chatRoomId = roomTwo)

        assertEquals(listOf(mine), resolved(roomOne))
        assertEquals(listOf(theirs), resolved(roomTwo))
    }

    /**
     * A placeholder replaced by a real line resolves the event. This is the transition the
     * sweep is trying to cause, so it has to be visible through this query -- the row is
     * upserted in place, keeping its id.
     */
    @Test
    fun `a placeholder that becomes a real line resolves its event`() = runBlocking {
        seedRooms()
        val eventId = seedGroupEvent("1")
        val lineId = line(eventId, messageType = ChatMessage.TYPE_PENDING_COMMIT)
        assertTrue(resolved().isEmpty(), "precondition: the placeholder is unresolved")

        val placeholder = assertNotNull(db.chatMessageDao().getChatMessagesByMarmotGroupEventId(eventId))
        db.chatMessageDao().upsert(
            placeholder.copy(id = lineId, messageType = ChatMessage.TYPE_DIRECT_MESSAGE, content = "decrypted")
        )

        assertEquals(listOf(eventId), resolved())
    }

    /**
     * The single-row lookups order newest first, which is what makes them a sensible "the line
     * for this event" rather than whichever row sqlite reached first.
     */
    @Test
    fun `the newest line wins for a group event`() = runBlocking {
        seedRooms()
        val eventId = seedGroupEvent("1")
        line(eventId, content = "older", createdAt = Instant.fromEpochSeconds(1_000))
        line(eventId, content = "newer", createdAt = Instant.fromEpochSeconds(2_000))

        assertEquals("newer", db.chatMessageDao().getChatMessagesByMarmotGroupEventId(eventId)?.content)
    }

    /**
     * Timestamps are stored to the second, so a burst written in one second ties -- a ceremony
     * puts a dozen lines into a room faster than that, and a request with the answer it triggers
     * routinely lands inside one. With nothing to break the tie the transcript came back in
     * whatever order SQLite's sorter produced, which under a descending query drew a same-second
     * burst backwards and was free to change between two reads of the same room.
     *
     * `id DESC` is the key `ChatRoomDao` already breaks its last-message tie on, so the chat
     * list's preview and the bottom of the room it opens name the same line.
     */
    @Test
    fun `lines written in the same second come back in write order`() = runBlocking {
        seedRooms()
        val burst = Instant.fromEpochSeconds(1_000)
        line(groupEventId = null, content = "first", createdAt = burst)
        line(groupEventId = null, content = "second", createdAt = burst)
        line(groupEventId = null, content = "third", createdAt = burst)

        // Newest first, which is the order `reverseLayout` draws from the bottom up.
        assertEquals(
            listOf("third", "second", "first"),
            db.chatMessageDao().getChatMessagesByChatRoomId(roomOne).map { it.chatMessage.content }
        )

        // The flow is what the open room actually reads, and it must not order differently
        // from the snapshot the same screen loads with.
        assertEquals(
            listOf("third", "second", "first"),
            db.chatMessageDao().observeChatMessagesByChatRoomId(roomOne).first()
                .map { it.chatMessage.content }
        )
    }

    @Test
    fun `a senders lines are counted per room`() = runBlocking {
        seedRooms()
        line(groupEventId = null, sender = user, chatRoomId = roomOne)
        line(groupEventId = null, sender = user, chatRoomId = roomOne)
        line(groupEventId = null, sender = other, chatRoomId = roomOne)
        line(groupEventId = null, sender = user, chatRoomId = roomTwo)

        assertEquals(2, db.chatMessageDao().countChatMessagesBySenderPublicKey(roomOne, user))
        assertEquals(1, db.chatMessageDao().countChatMessagesBySenderPublicKey(roomOne, other))
        assertEquals(0, db.chatMessageDao().countChatMessagesBySenderPublicKey(roomTwo, other))
    }
}
