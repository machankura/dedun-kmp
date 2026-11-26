package com.machankura.dedun.compose.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.machankura.dedun.compose.PlatformContext
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
            setSound(UNNotificationSound.defaultSound())
            setUserInfo(
                mapOf(
                    NotificationTaps.EXTRA_USER_PUBLIC_KEY to notification.userPublicKey,
                    NotificationTaps.EXTRA_CHAT_ROOM_ID to notification.chatRoomId,
                )
            )
        }
        val request = UNNotificationRequest.requestWithIdentifier(notification.key, content, null)
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    override fun cancel(key: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(key))
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
