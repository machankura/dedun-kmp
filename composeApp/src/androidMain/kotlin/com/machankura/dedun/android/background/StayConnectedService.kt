package com.machankura.dedun.android.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.android.DedunApplication
import com.machankura.dedun.android.MainActivity
import com.machankura.dedun.android.R
import com.machankura.dedun.compose.AppLifecycle
import com.machankura.dedun.compose.identity.DefaultIdentity
import com.machankura.dedun.compose.identity.Identity
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.stay_connected_channel
import dedun.composeapp.generated.resources.stay_connected_notification
import org.jetbrains.compose.resources.getString

/**
 * Stay connected: the live subscriptions, held open while the app is closed, for as long as the
 * user leaves this on. docs/notifications-and-background-sync.md, D10 and Phase 10.
 *
 * It starts nothing of its own. It keeps `AppLifecycle.holdConnections` true, and if no screen has
 * set an identity it activates the default profile in the runtime, as the background pass does --
 * after which the app's own engines are what run.
 */
class StayConnectedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var activatedHere: Identity? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        AppLifecycle.keepConnected(AppLifecycle.KEEP_STAY_CONNECTED)

        val runtime = (application as DedunApplication).runtime
        scope.launch {
            runtime.synchronization
            runtime.notary
            runtime.notifications
            if (runtime.activeIdentity.value == null) {
                DefaultIdentity.load(runtime.phoenixGlobal)?.let { identity ->
                    activatedHere = identity
                    runtime.activeIdentity.compareAndSet(null, identity)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLifecycle.release(AppLifecycle.KEEP_STAY_CONNECTED)
        val runtime = (application as DedunApplication).runtime
        activatedHere?.let { identity ->
            if (!AppLifecycle.isVisible.value) runtime.activeIdentity.compareAndSet(identity, null)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        // Read once, here, before startForeground's deadline; a resource read is not slow.
        val (channelName, text) = runBlocking {
            getString(Res.string.stay_connected_channel) to getString(Res.string.stay_connected_notification)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 2
        private const val PREFERENCES = "giza_background"
        private const val KEY_STAY_CONNECTED = "stay_connected"

        /** Whether the user turned it on. A device's choice, not a profile's. */
        fun isOn(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_STAY_CONNECTED, false)

        fun set(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_STAY_CONNECTED, on).apply()
            if (on) start(context) else stop(context)
        }

        /** From the foreground only: Android refuses a foreground service started from the background. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StayConnectedService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StayConnectedService::class.java))
        }
    }
}
