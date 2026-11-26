package com.machankura.dedun.compose.notifications

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.managers.MarmotClientStandIn
import com.machankura.dedun.compose.managers.MarmotStandIn

/** Rows in, notifications out. docs/notifications-and-background-sync.md, Phase 6. */
class NotificationDispatcherJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private class FakeNotifier : PlatformNotifier {
        val posted: MutableList<RoomNotification> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun post(notification: RoomNotification) { posted += notification }
        override fun cancel(key: String) { cancelled += key }
        override fun canPost() = true
    }

    private val notifier = FakeNotifier()
    private val pending = MutableStateFlow(false)
    private val appVisible = MutableStateFlow(false)
    private val visibleRoom = MutableStateFlow<String?>(null)

    private val strings = NotificationStrings("New message", "Note to self", "Someone", { "$it more" })

    private val dispatcher = NotificationDispatcher(
        database = db,
        notifier = notifier,
        strings = { strings },
        appVisible = appVisible,
        visibleChatRoomId = visibleRoom,
        syncPending = { pending },
        settleFor = 100.milliseconds,
    )

    private val user = "a".repeat(64)
    private val peer = "b".repeat(64)
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

    private suspend fun seed() {
        listOf(user to "me", peer to "alice").forEach { (publicKey, name) ->
            val eventId = publicKey.take(32).padEnd(64, 'c')
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = eventId))
        }
        db.chatRoomDao().upsert(ChatRoom(id = roomOne, userPublicKey = user, subject = "Book club", description = null, mlsGroupState = null))
        db.chatRoomDao().upsert(ChatRoom(id = roomTwo, userPublicKey = user, subject = "Garden", description = null, mlsGroupState = null))
    }

    private suspend fun line(
        room: String = roomOne,
        text: String = "hello",
        type: String = ChatMessage.TYPE_MESSAGE,
        mine: Boolean = false,
    ): Long = db.chatMessageDao().upsert(
        ChatMessage(
            senderPublicKey = if (mine) user else peer,
            isUserMessage = mine,
            giftWrapPayloadId = null,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            chatRoomId = room,
            content = text,
            messageType = type,
            createdAt = Clock.System.now(),
        )
    )

    private suspend fun armNow() = dispatcher.awaitArmed(user)

    @Test
    fun `nothing is posted for an identity that is not armed`() = runBlocking {
        seed()
        line()
        assertTrue(dispatcher.dispatchPending(user).isEmpty())
        assertTrue(notifier.posted.isEmpty())
    }

    /** D3: arming waits for the catch-up to drain and stay drained, and history is never news. */
    @Test
    fun `arming waits for the first sync to settle, and what it brought is not posted`() = runBlocking {
        seed()
        pending.value = true
        val arming = launch(Dispatchers.Default) { armNow() }

        line(text = "history")
        delay(300)
        assertNull(db.notificationDao().getLedger(user), "armed while the first sync was still running")

        pending.value = false
        withTimeout(5_000) { arming.join() }
        assertNotNull(db.notificationDao().getLedger(user))

        assertTrue(dispatcher.dispatchPending(user).isEmpty(), "the first sync's history was posted")
        assertEquals(0, db.notificationDao().unreadCount(roomOne), "the first sync's history was left unread")

        line(text = "news")
        val posted = dispatcher.dispatchPending(user).single()
        assertEquals("Book club", posted.title)
        assertEquals(listOf("news"), posted.lines.map { it.text })
        assertEquals("alice", posted.lines.single().senderName)
    }

    @Test
    fun `lines are grouped per room, merged into what is showing, and posted once`() = runBlocking {
        seed()
        armNow()
        line(roomOne, "one")
        line(roomOne, "two")
        line(roomTwo, "elsewhere")

        val posted = dispatcher.dispatchPending(user)
        assertEquals(setOf("Book club", "Garden"), posted.map { it.title }.toSet())
        assertEquals(2, posted.single { it.chatRoomId == roomOne }.total)

        assertTrue(dispatcher.dispatchPending(user).isEmpty(), "a line was posted twice")

        line(roomOne, "three")
        val merged = dispatcher.dispatchPending(user).single()
        assertEquals(3, merged.total)
        assertEquals(listOf("one", "two", "three"), merged.lines.map { it.text })
    }

    @Test
    fun `a dropped line still moves the cursor`() = runBlocking {
        seed()
        armNow()
        db.notificationDao().setNotifications(roomOne, ChatRoom.NOTIFICATIONS_MUTED)
        val muted = line(roomOne)

        assertTrue(dispatcher.dispatchPending(user).isEmpty())
        assertEquals(muted, db.notificationDao().getLedger(user)!!.cursorMessageId)
    }

    @Test
    fun `an approval waiting on this device is posted as needing you`() = runBlocking {
        seed()
        armNow()
        line(type = ChatMessage.TYPE_FROST_APPROVAL_NEEDED, mine = true, text = "Your approval is needed")
        val posted = dispatcher.dispatchPending(user).single()
        assertEquals(NotificationCategory.NeedsYou, posted.category)
    }

    @Test
    fun `the room on screen is not posted while the app is in front`() = runBlocking {
        seed()
        armNow()
        appVisible.value = true
        visibleRoom.value = roomOne
        line(roomOne)
        line(roomTwo)
        assertEquals(listOf(roomTwo), dispatcher.dispatchPending(user).map { it.chatRoomId })
    }

    /** D8: reading the room takes its notification away. */
    @Test
    fun `reading a room cancels its notification`() = runBlocking {
        seed()
        val running = launch(Dispatchers.Default) { dispatcher.run(user) }
        withTimeout(5_000) { while (db.notificationDao().getLedger(user) == null) delay(20) }

        line(roomOne)
        withTimeout(5_000) { while (notifier.posted.isEmpty()) delay(20) }
        val key = notifier.posted.single().key

        db.notificationDao().markAllRead(roomOne)
        withTimeout(5_000) { while (key !in notifier.cancelled) delay(20) }

        running.cancelAndJoin()
    }

    /** The whole of it, with a real client's message delivered through the app's ingest. */
    @Test
    fun `a message from a Marmot client becomes a notification titled by its group`() = runBlocking {
        val me = KeyPair()
        val meHex = me.pubKey.toHexKey()
        val client = MarmotClientStandIn()
        val marmot = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))
        MarmotStandIn.seedProfile(db, meHex, "us")
        MarmotStandIn.seedProfile(db, client.hex, "amethyst")
        val roomId = client.inviteApp(db, marmot, me, client.createGroup("Hikers"))

        dispatcher.awaitArmed(meHex)

        val bundle = client.client.buildTextMessage(roomId, "summit at noon", persistOwn = false)
        MarmotStandIn.storeInApp(db, me, bundle.outbound.signedEvent)

        val posted = dispatcher.dispatchPending(meHex).single()
        assertEquals(roomId, posted.chatRoomId)
        assertEquals("summit at noon", posted.lines.single().text)
        assertEquals(NotificationCategory.Messages, posted.category)
    }
}
