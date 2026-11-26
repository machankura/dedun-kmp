package com.machankura.dedun.compose.notifications

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.machankura.dedun.compose.PlatformContext

/** The names a platform shows for each category, where it shows them (Android's channels). */
data class NotificationChannelNames(
    val messages: String,
    val messagesDescription: String,
    val needsYou: String,
    val needsYouDescription: String,
    val groupActivity: String,
    val groupActivityDescription: String,
    /** How the user is named in a conversation's own lines. */
    val you: String,
    /** What a locked screen shows in place of a message. */
    val newMessage: String,
    /** The actions on a message notification (Phase 13). */
    val markRead: String = "",
    val reply: String = "",
    val replyHint: String = "",
)

/**
 * Where a [RoomNotification] goes to be shown. One per platform; the dispatcher decides what to
 * post and this only knows how. docs/notifications-and-background-sync.md, Phase 6.
 */
interface PlatformNotifier {
    /** Creates whatever the platform needs before a first post -- Android's channels. Idempotent. */
    suspend fun prepare(names: NotificationChannelNames) {}

    /** Shows [notification], replacing any already showing under the same key. */
    fun post(notification: RoomNotification)

    /** Takes away the notification under [key], if one is showing. */
    fun cancel(key: String)

    /** Whether the platform will show what is posted at all. */
    fun canPost(): Boolean
}

/** The notifier for this platform. */
expect fun platformNotifier(platformContext: PlatformContext): PlatformNotifier

/** Asking the platform for permission to notify, from a screen. */
interface NotificationPermission {
    val granted: Boolean

    /** Asks, where the platform asks; does nothing where it does not. */
    fun request()
}

@Composable
expect fun rememberNotificationPermission(): NotificationPermission

/** A notification somebody tapped: the room to open, for the identity it was posted for. */
data class NotificationTap(val userPublicKey: String, val chatRoomId: String)

/**
 * Taps waiting to be turned into navigation. A platform puts one here -- from an intent, from a
 * notification centre delegate -- and the navigation host takes it once the identity it is for is
 * the one running. A singleton, because a tap can arrive before there is a host to hand it to.
 */
object NotificationTaps {
    private val _pending = MutableStateFlow<NotificationTap?>(null)

    val pending: StateFlow<NotificationTap?> = _pending.asStateFlow()

    fun opened(userPublicKey: String, chatRoomId: String) {
        _pending.value = NotificationTap(userPublicKey, chatRoomId)
    }

    fun consumed(tap: NotificationTap) {
        _pending.update { if (it == tap) null else it }
    }

    /** The extras a platform carries a tap in. */
    const val EXTRA_USER_PUBLIC_KEY = "mobi.dedun.notification.userPublicKey"
    const val EXTRA_CHAT_ROOM_ID = "mobi.dedun.notification.chatRoomId"
}
