package com.machankura.dedun.compose.notifications

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom

/** An identity's switches. D7, D9; stored on `NotificationLedger`. */
data class NotificationSettings(
    val enabled: Boolean = true,
    val messages: Boolean = true,
    val needsYou: Boolean = true,
    val groupActivity: Boolean = false,
    val showMessageText: Boolean = true,
) {
    fun allows(category: NotificationCategory): Boolean = enabled && when (category) {
        NotificationCategory.Messages -> messages
        NotificationCategory.NeedsYou -> needsYou
        NotificationCategory.GroupActivity -> groupActivity
    }
}

/** What the policy decided about one line. */
sealed interface NotificationDecision {
    data class Post(val category: NotificationCategory) : NotificationDecision

    /** Not posted, and why: what a log and a test assert on. */
    data class Drop(val reason: Reason) : NotificationDecision

    enum class Reason {
        /** A line the classifier places nowhere. */
        NotNotifiable,
        NotificationsOff,
        CategoryOff,
        /** The room is gone, left or deleted. */
        RoomGone,
        Muted,
        /** It is the room on screen, and the app is in front. */
        OnScreen,
        /** At or below the room's read mark. */
        AlreadyRead,
        /** Written long ago, however recently it was filed: backfill, not news. */
        TooOld,
    }
}

/**
 * Whether one line becomes a notification. Pure: everything it reads is a parameter.
 * docs/notifications-and-background-sync.md, D2 and D7.
 *
 * Whether the identity is armed (D3) is not asked here: an unarmed identity has no cursor, so the
 * dispatcher never reads its lines at all.
 */
object NotificationPolicy {

    /**
     * How old a line may be and still be news. A line filed now but written before this is history
     * arriving -- "load older messages", a chronicle, a device that was away for a week -- and the
     * unread count is the place for it, not a notification.
     */
    val HORIZON: Duration = 72.hours

    fun decide(
        line: ChatMessage,
        room: ChatRoom?,
        settings: NotificationSettings,
        visibleChatRoomId: String?,
        appVisible: Boolean,
        now: Instant,
    ): NotificationDecision {
        val category = NotificationClassifier.categoryOf(line)
            ?: return drop(NotificationDecision.Reason.NotNotifiable)

        return when {
            !settings.enabled -> drop(NotificationDecision.Reason.NotificationsOff)
            !settings.allows(category) -> drop(NotificationDecision.Reason.CategoryOff)
            room == null || room.deletedAt != null || room.leftGroupAt != null ->
                drop(NotificationDecision.Reason.RoomGone)
            room.notifications == ChatRoom.NOTIFICATIONS_MUTED -> drop(NotificationDecision.Reason.Muted)
            appVisible && visibleChatRoomId == room.id -> drop(NotificationDecision.Reason.OnScreen)
            // An approval waiting on you is not answered by having scrolled past it.
            category != NotificationCategory.NeedsYou && room.lastReadMessageId != null &&
                line.id <= room.lastReadMessageId -> drop(NotificationDecision.Reason.AlreadyRead)
            line.createdAt < now - HORIZON -> drop(NotificationDecision.Reason.TooOld)
            else -> NotificationDecision.Post(category)
        }
    }

    private fun drop(reason: NotificationDecision.Reason) = NotificationDecision.Drop(reason)
}
