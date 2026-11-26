package com.machankura.dedun.compose.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.machankura.dedun.android.DedunApplication

/**
 * A notification's Mark as read and Reply, run without opening the app. Phase 13.
 *
 * `goAsync` gives a receiver about ten seconds before Android may stop it; a reply's send is given
 * longer than that by `NotificationActions`, and is cut short if the process is. What was queued
 * stays queued, and goes out the next time anything runs the notary.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val userPublicKey = intent.getStringExtra(NotificationTaps.EXTRA_USER_PUBLIC_KEY) ?: return
        val chatRoomId = intent.getStringExtra(NotificationTaps.EXTRA_CHAT_ROOM_ID) ?: return
        val key = intent.getStringExtra(NotificationActions.EXTRA_KEY) ?: return
        val runtime = (context.applicationContext as DedunApplication).runtime

        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    NotificationActions.ACTION_MARK_READ -> NotificationActions.markRead(runtime, chatRoomId, key)
                    NotificationActions.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationActions.REPLY_INPUT)
                            ?.toString()
                            .orEmpty()
                        NotificationActions.reply(runtime, userPublicKey, chatRoomId, key, text)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
