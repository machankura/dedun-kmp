package com.machankura.dedun.compose.notifications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room3.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository

/**
 * A room on screen stays read; one that is not counts what arrives in it.
 * docs/notifications-and-background-sync.md, Phase 4.
 */
class ReadStateJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val chatRepository = DatabaseChatRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = "a".repeat(64)
    private val peer = "b".repeat(64)
    private val open = "11".repeat(32)
    private val elsewhere = "22".repeat(32)

    private fun seed() = runBlocking {
        listOf(user, peer).forEach { publicKey ->
            val eventId = publicKey.take(32).padEnd(64, 'c')
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(4), nostrEventId = eventId))
        }
        listOf(open, elsewhere).forEach { id ->
            db.chatRoomDao().upsert(ChatRoom(id = id, userPublicKey = user, subject = null, description = null, mlsGroupState = null))
        }
    }

    private fun line(room: String, mine: Boolean = false) = runBlocking {
        db.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = if (mine) user else peer,
                isUserMessage = mine,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = room,
                content = "hello",
                createdAt = Instant.fromEpochSeconds(1_800_000_000),
            )
        )
    }

    private fun unread(room: String) = runBlocking { db.notificationDao().unreadCount(room) }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the room on screen is read on entry and as lines arrive, and the one off screen counts`() {
        seed()
        line(open)
        line(open)

        runDesktopComposeUiTest {
            setContent { MarkReadWhileVisible(activeUserPublicKey = user, chatRoomId = open, chatRepository = chatRepository) }

            waitUntil(timeoutMillis = 5_000) { unread(open) == 0 }
            assertEquals(open, VisibleConversation.chatRoomId.value)

            line(open)
            line(elsewhere)
            waitUntil(timeoutMillis = 5_000) { unread(open) == 0 }
            assertEquals(1, unread(elsewhere))
        }

        // Out of the composition, it is no longer the room on screen.
        assertNull(VisibleConversation.chatRoomId.value)
    }

    @Test
    fun `a room left is cleared only if it is still the one recorded`() {
        VisibleConversation.entered(open)
        VisibleConversation.entered(elsewhere)
        VisibleConversation.left(open)
        assertEquals(elsewhere, VisibleConversation.chatRoomId.value, "leaving the old room cleared the new one")
        VisibleConversation.left(elsewhere)
        assertNull(VisibleConversation.chatRoomId.value)
    }

    @Test
    fun `a badge says the count, and stops at 999`() {
        assertEquals("7", badgeText(7))
        assertEquals("999", badgeText(999))
        assertEquals("999+", badgeText(1_000))
    }
}
