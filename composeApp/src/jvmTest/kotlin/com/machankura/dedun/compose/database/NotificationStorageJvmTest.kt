package com.machankura.dedun.compose.database

import androidx.room3.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile

/**
 * Read marks, the notification cursor and the ledger, over the app's own schema.
 * docs/notifications-and-background-sync.md, Phase 3.
 */
class NotificationStorageJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val dao = db.notificationDao()

    @AfterTest
    fun closeDb() = db.close()

    private val user = "a".repeat(64)
    private val peer = "b".repeat(64)
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

    private suspend fun seedRooms() {
        listOf(user, peer).forEach { publicKey ->
            val eventId = publicKey.take(32).padEnd(64, 'c')
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(4), nostrEventId = eventId))
        }
        listOf(roomOne, roomTwo).forEach { id ->
            db.chatRoomDao().upsert(ChatRoom(id = id, userPublicKey = user, subject = null, description = null, mlsGroupState = null))
        }
    }

    private suspend fun line(
        room: String = roomOne,
        mine: Boolean = false,
        type: String = ChatMessage.TYPE_MESSAGE,
    ): Long = db.chatMessageDao().upsert(
        ChatMessage(
            senderPublicKey = if (mine) user else peer,
            isUserMessage = mine,
            giftWrapPayloadId = null,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            chatRoomId = room,
            content = "hello",
            messageType = type,
            createdAt = Instant.fromEpochSeconds(1_800_000_000),
        )
    )

    private suspend fun readMark(room: String) = assertNotNull(db.chatRoomDao().findChatRoomById(room)).chatRoom.lastReadMessageId

    @Test
    fun `a new room has nothing read and notifies`() = runBlocking {
        seedRooms()
        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomOne)).chatRoom
        assertNull(room.lastReadMessageId)
        assertEquals(ChatRoom.NOTIFICATIONS_ALL, room.notifications)
    }

    @Test
    fun `unread counts the conversation said by others, above the mark`() = runBlocking {
        seedRooms()
        line()
        line(mine = true)
        line(type = ChatMessage.TYPE_FROST_STARTED)
        val directMessage = line(type = ChatMessage.TYPE_DIRECT_MESSAGE)

        assertEquals(2, dao.unreadCount(roomOne))

        dao.markRead(roomOne, directMessage)
        assertEquals(0, dao.unreadCount(roomOne))

        line()
        assertEquals(1, dao.unreadCount(roomOne))
        assertEquals(listOf(com.machankura.dedun.compose.database.dao.RoomUnread(roomOne, 1, ChatRoom.NOTIFICATIONS_ALL)), dao.observeUnreadCounts(user).first())
    }

    @Test
    fun `a read mark never moves back`() = runBlocking {
        seedRooms()
        val first = line()
        val second = line()

        dao.markRead(roomOne, second)
        assertEquals(0, dao.markRead(roomOne, first), "an older mark was written over a newer one")
        assertEquals(second, readMark(roomOne))
    }

    @Test
    fun `marking a room all read takes it to its newest line, and is a no-op when it already is`() = runBlocking {
        seedRooms()
        line()
        val newest = line()

        assertEquals(1, dao.markAllRead(roomOne))
        assertEquals(newest, readMark(roomOne))
        assertEquals(0, dao.markAllRead(roomOne), "a room already read was written again")
    }

    @Test
    fun `nothing is new for an identity that is not armed`() = runBlocking {
        seedRooms()
        line()
        assertNull(dao.getLedger(user))
    }

    /** D3: arming starts from what the device already holds, and reads it all. */
    @Test
    fun `arming puts the cursor at the newest line and reads every room`() = runBlocking {
        seedRooms()
        line(roomOne)
        val newest = line(roomTwo)

        assertTrue(dao.arm(user, Instant.fromEpochSeconds(1_800_000_100)))
        val ledger = assertNotNull(dao.getLedger(user))
        assertEquals(newest, ledger.cursorMessageId)
        assertEquals(0, dao.unreadCount(roomOne))
        assertEquals(0, dao.unreadCount(roomTwo))
        assertTrue(dao.getAfter(user, ledger.cursorMessageId).isEmpty())

        val arrived = line(roomOne)
        assertEquals(listOf(arrived), dao.getAfter(user, ledger.cursorMessageId).map { it.id })

        assertFalse(dao.arm(user, Instant.fromEpochSeconds(1_800_000_200)), "armed twice")
        assertEquals(newest, assertNotNull(dao.getLedger(user)).cursorMessageId, "re-arming moved the cursor")
    }

    @Test
    fun `the cursor never moves back`() = runBlocking {
        seedRooms()
        dao.arm(user, Instant.fromEpochSeconds(1_800_000_000))
        dao.advanceCursor(user, 10)
        assertEquals(0, dao.advanceCursor(user, 5))
        assertEquals(10, assertNotNull(dao.getLedger(user)).cursorMessageId)
    }

    @Test
    fun `a background sync is recorded on the ledger`() = runBlocking {
        seedRooms()
        dao.arm(user, Instant.fromEpochSeconds(1_800_000_000))
        dao.recordBackgroundSync(user, Instant.fromEpochSeconds(1_800_000_500), "completed", "full")
        val ledger = assertNotNull(dao.getLedger(user))
        assertEquals(Instant.fromEpochSeconds(1_800_000_500), ledger.lastBackgroundSyncAt)
        assertEquals("completed", ledger.lastBackgroundSyncOutcome)
        assertEquals("full", ledger.lastBackgroundSyncKind)
    }

    @Test
    fun `muting a room is stored on it`() = runBlocking {
        seedRooms()
        dao.setNotifications(roomOne, ChatRoom.NOTIFICATIONS_MUTED)
        assertEquals(ChatRoom.NOTIFICATIONS_MUTED, assertNotNull(db.chatRoomDao().findChatRoomById(roomOne)).chatRoom.notifications)
    }
}
