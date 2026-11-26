package com.machankura.dedun.compose.notifications

import co.touchlab.kermit.Logger
import kotlin.time.Duration.Companion.seconds
import com.machankura.dedun.compose.DedunRuntime

/**
 * What a notification's buttons do, the same on every platform that has them.
 * docs/notifications-and-background-sync.md, Phase 13.
 *
 * Not Approve. Approving a signing session from a notification shade, without seeing what is being
 * signed, is exactly what the approval screens exist to prevent.
 */
object NotificationActions {
    private val logger = Logger.withTag("NotificationActions")

    const val ACTION_MARK_READ = "mobi.dedun.notification.markRead"
    const val ACTION_REPLY = "mobi.dedun.notification.reply"
    const val EXTRA_KEY = "mobi.dedun.notification.key"
    const val REPLY_INPUT = "mobi.dedun.notification.replyText"

    suspend fun markRead(runtime: DedunRuntime, chatRoomId: String, key: String) {
        runtime.chatRepository.markChatRoomRead(chatRoomId)
        runtime.notifier.cancel(key)
    }

    /**
     * Sends [text] into [chatRoomId] as [userPublicKey], waits for it to leave, and takes the
     * notification away. False, and the notification left where it was, when it could not be sent.
     */
    suspend fun reply(runtime: DedunRuntime, userPublicKey: String, chatRoomId: String, key: String, text: String): Boolean {
        if (text.isBlank()) return false
        val sent = runtime.backgroundSync.withProfile(userPublicKey, deadline = 25.seconds) {
            val room = runtime.chatRepository.getChatRoomByIdentifier(chatRoomId) ?: return@withProfile false
            runtime.chatRepository.sendChatMessage(text = text, localChatRoom = room)
            runtime.chatRepository.markChatRoomRead(chatRoomId)
            true
        }
        if (sent) runtime.notifier.cancel(key) else logger.w { "A reply into $chatRoomId could not be sent" }
        return sent
    }
}
