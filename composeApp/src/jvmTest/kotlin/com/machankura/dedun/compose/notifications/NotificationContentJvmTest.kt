package com.machankura.dedun.compose.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant

/** Grouping and wording. Phase 5. */
class NotificationContentJvmTest {

    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val strings = NotificationStrings(
        newMessage = "New message",
        noteToSelf = "Note to self",
        unknownSender = "Someone",
        moreMessages = { "$it more" },
    )

    private fun participant(publicKey: String, name: String?) = LocalParticipant(
        participant = Participant(chatRoomId = "room", participantPublicKey = publicKey, relayHint = null),
        profile = name?.let { Profile(publicKey = publicKey, userName = it, nostrEventId = "e".repeat(64)) },
    )

    private fun room(subject: String? = null) = LocalChatRoom(
        chatRoom = ChatRoom(id = "room", userPublicKey = me, subject = subject, description = null, mlsGroupState = null),
        localParticipants = listOf(participant(me, "me"), participant(alice, "alice")),
    )

    private fun line(id: Long, text: String = "line $id") = ChatMessage(
        id = id,
        senderPublicKey = alice,
        isUserMessage = false,
        giftWrapPayloadId = null,
        marmotGroupEventId = null,
        marmotInnerEventId = null,
        chatRoomId = "room",
        content = text,
        createdAt = Instant.fromEpochSeconds(1_800_000_000 + id),
    )

    @Test
    fun `a room is titled by its subject, or by the others in it`() {
        assertEquals("Book club", NotificationContent.roomTitle(room(subject = "Book club"), strings))
        assertEquals("alice", NotificationContent.roomTitle(room(), strings))
    }

    @Test
    fun `forty lines in one room are one notification of the newest five`() {
        val notification = NotificationContent.build(
            room = room(),
            category = NotificationCategory.Messages,
            lines = (1L..40L).shuffled().map { line(it) },
            showMessageText = true,
            profileName = null,
            strings = strings,
        )
        assertEquals(40, notification.total)
        assertEquals((36L..40L).toList(), notification.lines.map { it.messageId })
        assertEquals("alice", notification.lines.first().senderName)
        assertEquals("room|Messages", notification.key)
    }

    @Test
    fun `with message text off, a message says only that it arrived`() {
        val notification = NotificationContent.build(room(), NotificationCategory.Messages, listOf(line(1, "a secret")), false, null, strings)
        assertEquals("New message", notification.lines.single().text)

        val approval = NotificationContent.build(room(), NotificationCategory.NeedsYou, listOf(line(2, "Your approval is needed")), false, null, strings)
        assertEquals("Your approval is needed", approval.lines.single().text, "the app's own sentence was hidden")
    }

    @Test
    fun `what arrives is merged into what is showing`() {
        val showing = NotificationContent.build(room(), NotificationCategory.Messages, (1L..4L).map { line(it) }, true, null, strings)
        val arrived = NotificationContent.build(room(), NotificationCategory.Messages, (5L..7L).map { line(it) }, true, null, strings)
        val merged = NotificationContent.merge(showing, arrived)
        assertEquals(7, merged.total)
        assertEquals((3L..7L).toList(), merged.lines.map { it.messageId })
    }
}
