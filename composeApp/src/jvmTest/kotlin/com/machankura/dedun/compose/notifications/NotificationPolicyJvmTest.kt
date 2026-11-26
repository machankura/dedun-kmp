package com.machankura.dedun.compose.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.notifications.NotificationDecision.Drop
import com.machankura.dedun.compose.notifications.NotificationDecision.Post
import com.machankura.dedun.compose.notifications.NotificationDecision.Reason

/** One test per reason a line is not posted, and the line that is. Phase 5. */
class NotificationPolicyJvmTest {

    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val roomId = "room"

    private fun line(
        id: Long = 10,
        type: String = ChatMessage.TYPE_MESSAGE,
        mine: Boolean = false,
        at: Instant = now,
    ) = ChatMessage(
        id = id,
        senderPublicKey = "b".repeat(64),
        isUserMessage = mine,
        giftWrapPayloadId = null,
        marmotGroupEventId = null,
        marmotInnerEventId = null,
        chatRoomId = roomId,
        content = "hello",
        messageType = type,
        createdAt = at,
    )

    private fun room(
        lastRead: Long? = null,
        notifications: String = ChatRoom.NOTIFICATIONS_ALL,
        left: Instant? = null,
        deleted: Instant? = null,
    ) = ChatRoom(
        id = roomId,
        userPublicKey = "a".repeat(64),
        subject = null,
        description = null,
        mlsGroupState = null,
        lastReadMessageId = lastRead,
        notifications = notifications,
        leftGroupAt = left,
        deletedAt = deleted,
    )

    private fun decide(
        line: ChatMessage = line(),
        room: ChatRoom? = room(),
        settings: NotificationSettings = NotificationSettings(),
        visible: String? = null,
        appVisible: Boolean = false,
    ) = NotificationPolicy.decide(line, room, settings, visible, appVisible, now)

    @Test
    fun `a new message in a quiet room is posted`() =
        assertEquals(Post(NotificationCategory.Messages), decide())

    @Test
    fun `a line that never notifies`() =
        assertEquals(Drop(Reason.NotNotifiable), decide(line(type = ChatMessage.TYPE_FROST_NONCE)))

    @Test
    fun `notifications off`() =
        assertEquals(Drop(Reason.NotificationsOff), decide(settings = NotificationSettings(enabled = false)))

    @Test
    fun `the category off, and group activity is off unless asked for`() {
        assertEquals(Drop(Reason.CategoryOff), decide(settings = NotificationSettings(messages = false)))
        assertEquals(Drop(Reason.CategoryOff), decide(line(type = ChatMessage.TYPE_MEMBER_ADDED)))
        assertEquals(
            Post(NotificationCategory.GroupActivity),
            decide(line(type = ChatMessage.TYPE_MEMBER_ADDED), settings = NotificationSettings(groupActivity = true)),
        )
    }

    @Test
    fun `a room that is gone, left or deleted`() {
        assertEquals(Drop(Reason.RoomGone), decide(room = null))
        assertEquals(Drop(Reason.RoomGone), decide(room = room(left = now)))
        assertEquals(Drop(Reason.RoomGone), decide(room = room(deleted = now)))
    }

    @Test
    fun `a muted room`() =
        assertEquals(Drop(Reason.Muted), decide(room = room(notifications = ChatRoom.NOTIFICATIONS_MUTED)))

    @Test
    fun `the room on screen, only while the app is in front`() {
        assertEquals(Drop(Reason.OnScreen), decide(visible = roomId, appVisible = true))
        assertEquals(Post(NotificationCategory.Messages), decide(visible = roomId, appVisible = false))
        assertEquals(Post(NotificationCategory.Messages), decide(visible = "another room", appVisible = true))
    }

    @Test
    fun `a line already read, but never an approval`() {
        assertEquals(Drop(Reason.AlreadyRead), decide(line(id = 10), room = room(lastRead = 10)))
        assertEquals(Post(NotificationCategory.Messages), decide(line(id = 11), room = room(lastRead = 10)))
        assertEquals(
            Post(NotificationCategory.NeedsYou),
            decide(line(id = 10, type = ChatMessage.TYPE_FROST_APPROVAL_NEEDED, mine = true), room = room(lastRead = 10)),
        )
    }

    @Test
    fun `a line written before the horizon is history, not news`() {
        assertEquals(Drop(Reason.TooOld), decide(line(at = now - NotificationPolicy.HORIZON - 1.hours)))
        assertEquals(Post(NotificationCategory.Messages), decide(line(at = now - NotificationPolicy.HORIZON + 1.hours)))
    }
}
