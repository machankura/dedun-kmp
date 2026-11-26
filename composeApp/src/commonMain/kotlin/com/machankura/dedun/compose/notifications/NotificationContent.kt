package com.machankura.dedun.compose.notifications

import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

/**
 * The words a notification is built from that are not anybody's message: read from the string
 * catalogue by the caller, since `getString` is suspending and this is not, and handed in so that
 * this file stays pure and testable.
 */
data class NotificationStrings(
    /** What a message says when its text is hidden (D9). */
    val newMessage: String,
    /** A room with no subject and nobody else in it. */
    val noteToSelf: String,
    /** A sender this device has no name for. */
    val unknownSender: String,
    /** "%1$s and %2$d more": a summary line when a room has more than [RoomNotification.MAX_LINES]. */
    val moreMessages: (count: Int) -> String,
)

/** One line inside a notification. */
data class NotificationLine(
    val messageId: Long,
    val senderPublicKey: String,
    val senderName: String?,
    val text: String,
    val at: Instant,
)

/**
 * What a platform posts for one room and one category: Android's `MessagingStyle` and iOS's
 * thread both show the newest few lines under the room's name, and are replaced, never stacked,
 * when more arrive. D7.
 */
data class RoomNotification(
    val userPublicKey: String,
    val chatRoomId: String,
    val category: NotificationCategory,
    val title: String,
    /** The newest [MAX_LINES] lines, oldest first. */
    val lines: List<NotificationLine>,
    /** How many lines this notification stands for in all, which may be more than [lines]. */
    val total: Int,
    /** Which profile it is for, when the device holds more than one (D6). */
    val profileName: String?,
) {
    /** Stable per room and category, so a newer post replaces the older one. */
    val key: String get() = "$chatRoomId|${category.name}"

    companion object {
        const val MAX_LINES = 5
    }
}

object NotificationContent {

    /**
     * The room's name as the chat list shows it: its subject, or the other members' names, or the
     * one member's own as a note to self.
     */
    fun roomTitle(room: LocalChatRoom, strings: NotificationStrings): String {
        room.chatRoom.subject?.takeIf { it.isNotBlank() }?.let { return it }
        val others = room.localParticipants.filter { it.participant.participantPublicKey != room.chatRoom.userPublicKey }
        if (others.isEmpty()) return strings.noteToSelf
        return others.joinToString(", ") { participant ->
            participant.profile?.humanReadableNameOrPubkey() ?: shortKey(participant.participant.participantPublicKey)
        }
    }

    fun senderName(room: LocalChatRoom, senderPublicKey: String): String? =
        room.localParticipants
            .firstOrNull { it.participant.participantPublicKey == senderPublicKey }
            ?.profile?.humanReadableNameOrPubkey()

    /**
     * One notification for [lines] -- all in [room], all of [category] -- newest last. A message's
     * text is replaced by [NotificationStrings.newMessage] when [showMessageText] is off; a Needs you
     * or Group activity line is the app's own sentence, never what anybody wrote, and is always shown.
     */
    fun build(
        room: LocalChatRoom,
        category: NotificationCategory,
        lines: List<ChatMessage>,
        showMessageText: Boolean,
        profileName: String?,
        strings: NotificationStrings,
        /** Names for senders the room's participant rows do not carry -- a NIP-17 peer's, say. */
        knownNames: Map<String, String> = emptyMap(),
    ): RoomNotification {
        val ordered = lines.sortedBy { it.id }
        return RoomNotification(
            userPublicKey = room.chatRoom.userPublicKey,
            chatRoomId = room.chatRoom.id,
            category = category,
            title = roomTitle(room, strings),
            lines = ordered.takeLast(RoomNotification.MAX_LINES).map { line ->
                NotificationLine(
                    messageId = line.id,
                    senderPublicKey = line.senderPublicKey,
                    senderName = senderName(room, line.senderPublicKey) ?: knownNames[line.senderPublicKey] ?: strings.unknownSender.takeIf { category == NotificationCategory.Messages },
                    text = if (category == NotificationCategory.Messages && !showMessageText) strings.newMessage else line.content,
                    at = line.createdAt,
                )
            },
            total = ordered.size,
            profileName = profileName,
        )
    }

    /** Merges what arrived into what is already showing for the same key, keeping the newest. */
    fun merge(showing: RoomNotification?, arrived: RoomNotification): RoomNotification {
        if (showing == null || showing.key != arrived.key) return arrived
        val lines = (showing.lines + arrived.lines).distinctBy { it.messageId }.sortedBy { it.messageId }
        return arrived.copy(
            lines = lines.takeLast(RoomNotification.MAX_LINES),
            total = showing.total + arrived.total,
        )
    }

    private fun shortKey(publicKey: String) = publicKey.take(8)
}
