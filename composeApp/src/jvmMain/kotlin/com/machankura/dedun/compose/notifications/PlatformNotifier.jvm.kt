package com.machankura.dedun.compose.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.concurrent.Volatile
import com.machankura.dedun.compose.PlatformContext

actual fun platformNotifier(platformContext: PlatformContext): PlatformNotifier = DesktopNotifier

/**
 * The desktop's notifier. A desktop notification needs a tray icon to come from, and the tray is
 * built by `main()` (Phase 12), which hands this its [sink]. Until it does, nothing is shown.
 */
object DesktopNotifier : PlatformNotifier {
    /** Shows a title and a body. Set by the tray; null while there is none. */
    @Volatile
    var sink: ((title: String, body: String) -> Unit)? = null

    override fun canPost(): Boolean = sink != null

    override fun post(notification: RoomNotification) {
        val newest = notification.lines.lastOrNull() ?: return
        sink?.invoke(notification.title, listOfNotNull(newest.senderName, newest.text).joinToString(": "))
    }

    /** A tray notification cannot be taken back once shown. */
    override fun cancel(key: String) = Unit
}

@Composable
actual fun rememberNotificationPermission(): NotificationPermission = remember {
    object : NotificationPermission {
        override val granted: Boolean = true
        override fun request() = Unit
    }
}
