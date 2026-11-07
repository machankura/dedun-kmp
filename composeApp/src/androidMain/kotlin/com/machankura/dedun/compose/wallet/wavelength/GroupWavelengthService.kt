package com.machankura.dedun.compose.wallet.wavelength

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import co.touchlab.kermit.Logger
import com.machankura.dedun.android.R

/**
 * Keeps the process alive while this device runs a group's Wavelength wallet, since a daemon stopped in the background can't answer a
 * round (`docs/group-wavelength-ui.md`, Phase 3). It does nothing else: the daemon and the runner are the process's, and this only says
 * so. Android 15 limits `dataSync` to six hours in twenty-four, which a wallet that has to answer at any hour can't live with, so
 * this is `specialUse`, whose reason is in the manifest and goes to review on Play.
 */
class GroupWavelengthService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val group = intent?.getStringExtra(EXTRA_GROUP).orEmpty()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.group_wavelength_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.group_wavelength_notification_title, group))
            .setContentText(getString(R.string.group_wavelength_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "group_wavelength_wallet"
        private const val NOTIFICATION = 7_370
        private const val EXTRA_GROUP = "group"
        private val logger = Logger.withTag("GroupWavelengthService")

        /** Starting one from the background can be refused: the daemon then runs for as long as the app happens to, which is said on the screen. */
        fun hold(context: Context, group: String?) {
            val intent = Intent(context, GroupWavelengthService::class.java)
            if (group == null) {
                context.stopService(intent)
                return
            }
            runCatching { context.startForegroundService(intent.putExtra(EXTRA_GROUP, group)) }
                .onFailure { logger.w(it) { "Couldn't keep the process alive for the group's wallet" } }
        }
    }
}
