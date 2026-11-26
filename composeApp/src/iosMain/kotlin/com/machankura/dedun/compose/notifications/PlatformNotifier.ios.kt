package com.machankura.dedun.compose.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.machankura.dedun.compose.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNTextInputNotificationAction
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.concurrent.Volatile

actual fun platformNotifier(platformContext: PlatformContext): PlatformNotifier = IosNotifier()

/**
 * iOS's half of Phase 6: one delivered notification per room and category, its identifier the key
 * so a newer one replaces it, threaded by room. Lock-screen previews are the system's own setting
 * and need nothing here (D9). Not compiled on the linux host this was written on; see the
 * document's record.
 */
class IosNotifier : PlatformNotifier {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    @Volatile
    private var authorised = false

    override suspend fun prepare(names: NotificationChannelNames) {
        // Mark as read and Reply on what somebody said; nothing on an approval, which is opened,
        // never decided from the lock screen. Phase 13.
        val markRead = UNNotificationAction.actionWithIdentifier(
            NotificationActions.ACTION_MARK_READ, names.markRead, UNNotificationActionOptionNone,
        )
        val reply = UNTextInputNotificationAction.actionWithIdentifier(
            NotificationActions.ACTION_REPLY, names.reply, UNNotificationActionOptionNone, names.reply, names.replyHint,
        )
        val messages = UNNotificationCategory.categoryWithIdentifier(
            CATEGORY_MESSAGES, listOf(markRead, reply), emptyList<String>(), UNNotificationCategoryOptionNone,
        )
        center.setNotificationCategories(setOf(messages))

        center.getNotificationSettingsWithCompletionHandler { settings ->
            authorised = settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
                settings?.authorizationStatus == UNAuthorizationStatusProvisional
        }
    }

    override fun canPost(): Boolean = authorised

    override fun post(notification: RoomNotification) {
        val newest = notification.lines.lastOrNull() ?: return
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(listOfNotNull(newest.senderName, newest.text).joinToString(": "))
            setThreadIdentifier(notification.chatRoomId)
            if (notification.category == NotificationCategory.Messages) setCategoryIdentifier(CATEGORY_MESSAGES)
            setSound(UNNotificationSound.defaultSound())
            setUserInfo(
                mapOf(
                    NotificationTaps.EXTRA_USER_PUBLIC_KEY to notification.userPublicKey,
                    NotificationTaps.EXTRA_CHAT_ROOM_ID to notification.chatRoomId,
                    NotificationActions.EXTRA_KEY to notification.key,
                )
            )
        }
        val request = UNNotificationRequest.requestWithIdentifier(notification.key, content, null)
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    override fun cancel(key: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(key))
    }

    companion object {
        const val CATEGORY_MESSAGES = "messages"
    }
}

private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Called by the app delegate for Mark as read and Reply, with the typed text for a reply.
 * [onDone] is called once, when the work is over, so the delegate can call its completion handler.
 */
fun notificationAction(actionIdentifier: String, userInfo: Map<Any?, *>, text: String?, onDone: () -> Unit) {
    val userPublicKey = userInfo[NotificationTaps.EXTRA_USER_PUBLIC_KEY] as? String
    val chatRoomId = userInfo[NotificationTaps.EXTRA_CHAT_ROOM_ID] as? String
    val key = userInfo[NotificationActions.EXTRA_KEY] as? String
    if (userPublicKey == null || chatRoomId == null || key == null) return onDone()
    actionScope.launch {
        try {
            val runtime = com.machankura.dedun.compose.gizaRuntime()
            when (actionIdentifier) {
                NotificationActions.ACTION_MARK_READ -> NotificationActions.markRead(runtime, chatRoomId, key)
                NotificationActions.ACTION_REPLY -> NotificationActions.reply(runtime, userPublicKey, chatRoomId, key, text.orEmpty())
            }
        } finally {
            onDone()
        }
    }
}

/**
 * Called by the app delegate's `userNotificationCenter(_:didReceive:)` with the tapped
 * notification's user info. docs/notifications-and-background-sync.md, Phase 11.
 */
fun notificationTapped(userInfo: Map<Any?, *>) {
    val userPublicKey = userInfo[NotificationTaps.EXTRA_USER_PUBLIC_KEY] as? String ?: return
    val chatRoomId = userInfo[NotificationTaps.EXTRA_CHAT_ROOM_ID] as? String ?: return
    NotificationTaps.opened(userPublicKey, chatRoomId)
}

@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    var isGranted by remember { mutableStateOf(false) }
    val center = remember { UNUserNotificationCenter.currentNotificationCenter() }

    remember {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            isGranted = settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
                settings?.authorizationStatus == UNAuthorizationStatusProvisional
        }
    }

    return remember(isGranted) {
        object : NotificationPermission {
            override val granted: Boolean = isGranted

            override fun request() {
                center.requestAuthorizationWithOptions(
                    UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
                ) { granted, _ -> isGranted = granted }
            }
        }
    }
}
