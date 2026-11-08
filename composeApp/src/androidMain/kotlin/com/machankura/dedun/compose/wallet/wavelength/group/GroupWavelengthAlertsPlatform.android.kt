package com.machankura.dedun.compose.wallet.wavelength.group

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.machankura.dedun.android.R
import com.machankura.dedun.compose.PlatformContext

/**
 * One notification channel for the two things a group's Wavelength wallet interrupts a member about.
 *
 * `IMPORTANCE_HIGH`, where `GroupWavelengthService`'s is `IMPORTANCE_LOW`: that one is an ongoing *this
 * device is running the wallet*, and this one is *the group needs to do something*. A member who turns
 * the first off, as a persistent notification invites them to, must not thereby turn the second off.
 *
 * The id is derived from the fact's own key, so a second alert about the same deadline **replaces** the
 * first rather than stacking under it -- and the caller only posts where the fact was new anyway, so
 * the two mechanisms agree.
 */
private class AndroidGroupWavelengthAlerts(private val context: PlatformContext) : GroupWavelengthAlerts {
    private val logger = Logger.withTag("GroupWavelengthAlerts")

    override fun post(key: String, title: String, text: String) {
        val app = context.applicationContext
        // Android 13 and up: without the runtime permission a post is silently dropped, and asking for
        // it is the app's business and not this file's. Checked so the log says which it was.
        if (ContextCompat.checkSelfPermission(app, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            logger.i { "No permission to say that the group's wallet needs something" }
            return
        }
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.group_wavelength_alert_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            // The text is two sentences, and a collapsed notification shows one line of it.
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(key.hashCode(), notification) }
            .onFailure { logger.w(it) { "Couldn't say that the group's wallet needs something" } }
    }

    private companion object {
        const val CHANNEL = "group_wavelength_alerts"
    }
}

actual fun groupWavelengthAlerts(context: PlatformContext): GroupWavelengthAlerts =
    AndroidGroupWavelengthAlerts(context)
