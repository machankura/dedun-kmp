package com.machankura.dedun.compose.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.machankura.dedun.android.MainActivity
import com.machankura.dedun.android.R
import com.machankura.dedun.compose.PlatformContext

actual fun platformNotifier(platformContext: PlatformContext): PlatformNotifier =
    AndroidNotifier(platformContext.applicationContext)

/**
 * Android's half of Phase 6: a channel per category, a `MessagingStyle` per room and category,
 * tagged with the notification's key so a newer post replaces the older one, and a lock-screen
 * version that names neither the room nor what was said (D9).
 */
class AndroidNotifier(private val context: Context) : PlatformNotifier {

    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var names: NotificationChannelNames? = null

    override suspend fun prepare(names: NotificationChannelNames) {
        this.names = names
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, names.messages, NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = names.messagesDescription },
                NotificationChannel(CHANNEL_NEEDS_YOU, names.needsYou, NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = names.needsYouDescription },
                NotificationChannel(CHANNEL_GROUP_ACTIVITY, names.groupActivity, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = names.groupActivityDescription },
            )
        )
    }

    override fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    override fun post(notification: RoomNotification) {
        if (!canPost()) return
        val names = names ?: return

        val me = Person.Builder().setName(names.you).setKey(notification.userPublicKey).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(notification.title)
            .setGroupConversation(true)
        notification.lines.forEach { line ->
            val sender = line.senderName?.let { Person.Builder().setName(it).setKey(line.senderPublicKey).build() }
            style.addMessage(line.text, line.at.toEpochMilliseconds(), sender)
        }

        val locked = NotificationCompat.Builder(context, channelOf(notification.category))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(names.newMessage)
            .build()

        val built = NotificationCompat.Builder(context, channelOf(notification.category))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setStyle(style)
            .setContentTitle(notification.title)
            .setContentText(notification.lines.lastOrNull()?.text)
            .setNumber(notification.total)
            .setWhen(notification.lines.lastOrNull()?.at?.toEpochMilliseconds() ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(
                if (notification.category == NotificationCategory.Messages) NotificationCompat.CATEGORY_MESSAGE
                else NotificationCompat.CATEGORY_REMINDER
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(openRoom(notification))
            .build()

        try {
            manager.notify(notification.key, NOTIFICATION_ID, built)
        } catch (denied: SecurityException) {
            // The permission was taken away between canPost and here. Nothing to do but not crash.
        }
    }

    override fun cancel(key: String) {
        manager.cancel(key, NOTIFICATION_ID)
    }

    private fun openRoom(notification: RoomNotification): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NotificationTaps.EXTRA_USER_PUBLIC_KEY, notification.userPublicKey)
            .putExtra(NotificationTaps.EXTRA_CHAT_ROOM_ID, notification.chatRoomId)
        return PendingIntent.getActivity(
            context,
            notification.key.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun channelOf(category: NotificationCategory) = when (category) {
        NotificationCategory.Messages -> CHANNEL_MESSAGES
        NotificationCategory.NeedsYou -> CHANNEL_NEEDS_YOU
        NotificationCategory.GroupActivity -> CHANNEL_GROUP_ACTIVITY
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_NEEDS_YOU = "needs_you"
        const val CHANNEL_GROUP_ACTIVITY = "group_activity"

        /** One id for all: the key is the tag, and a tag and id together name a notification. */
        private const val NOTIFICATION_ID = 1
    }
}

@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    fun granted(): Boolean = AndroidNotifier(context.applicationContext).canPost()

    var isGranted by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        isGranted = result && granted()
    }

    return remember(isGranted, launcher) {
        object : NotificationPermission {
            override val granted: Boolean = isGranted

            override fun request() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    isGranted = granted()
                }
            }
        }
    }
}
