package com.machankura.dedun.compose.database.model.intermdiate

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.room3.Embedded
import androidx.room3.Relation
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotRouting
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.extensions.memberName
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor
import com.vitorpamplona.quartz.nip01Core.core.HexKey

data class LocalChatRoom(
    @Embedded val chatRoom: ChatRoom,

    @Relation(
        entity = Participant::class,
        parentColumns = ["id"],
        entityColumns = ["chatRoomId"],
    )
    val localParticipants: List<LocalParticipant> = emptyList(),

    /**
     * The room's newest line, or null for a room nothing has been said in yet.
     *
     * Only the chat-list queries fill this in; every other way of getting a room
     * leaves it null rather than paying for a join no screen reads. So a null here
     * means "not asked for" as often as it means "nothing said", which is why
     * nothing hangs a decision on it beyond what to draw.
     */
    @Embedded(prefix = "lastMessage_")
    val lastChatMessage: ChatRoomLastMessage? = null,
    /**
     * The addresses this room's kind:445 traffic travels under. Empty for a room from
     * before the table, which is addressed by its own id -- see [routingId].
     */
    @Relation(
        entity = MarmotRouting::class,
        parentColumns = ["id"],
        entityColumns = ["chatRoomId"],
    )
    val routings: List<MarmotRouting> = emptyList(),
) {
    /**
     * The `h` tag to stamp on what this room sends now: its current routing id, or its
     * own id when it has none recorded. See [MarmotRouting].
     */
    val routingId: HexKey
        get() = routings.filter { it.isActive }.maxByOrNull { it.activeFromEpoch }?.routingId ?: chatRoom.id

    /**
     * Every address this room has ever been reachable at, current first, for the
     * filters that fetch its history. A room with no row is its own one address.
     */
    val routingIds: List<HexKey>
        get() = routings.sortedByDescending { it.activeFromEpoch }.map { it.routingId }.ifEmpty { listOf(chatRoom.id) }

    @Composable
    fun RenderChatRoomTitleText() {
        if (chatRoom.subject != null) {
            Text(
                text = chatRoom.subject,
                color = ProfileColor.fromPublicKey(chatRoom.id),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            if (localParticipants.size == 1) {
                Text(
                    text = "Note to Self (${localParticipants.first().profile?.humanReadableNameOrPubkey()})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // Remove the active user publicKey from participants...
                val participantNames = localParticipants.filter { it.participant.participantPublicKey != chatRoom.userPublicKey }.map { buildAnnotatedString {
                    withStyle(style = SpanStyle(color = ProfileColor.fromPublicKey(it.participant.participantPublicKey))) {
                        append(it.profile?.humanReadableNameOrPubkey() ?: "unknown participant")
                    }
                } }

                Text(
                    text = buildAnnotatedString {
                        participantNames.forEachIndexed { index, participantName ->
                            if (index != 0) {
                                append(", ")
                            }
                            append(participantName)
                        }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    /**
     * One line saying what was last said here, under the room's name in the chat list.
     *
     * A quieter echo of how the transcript renders that same line, so the two never
     * disagree about what a room's newest activity was.
     */
    @Composable
    fun RenderChatRoomLastMessageText() {
        Text(
            text = lastChatMessagePreviewText() ?: "No messages yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    /**
     * The room's newest line as a single line of text, or null if there is none.
     *
     * Follows the transcript's own dispatch, for the same reasons it does:
     *
     * - a ritual, chronicle or membership line is nobody's words. The authored ones
     *   have content written as a predicate for an actor's name, so they get that name
     *   in front; the rest are whole sentences already and stand alone. Prefixing "a
     *   shared key ceremony started" with a member would read as them having announced
     *   it, and prefixing "Invited Bob to the group" with one reads as them saying it.
     * - a private message this device cannot open has no words to show. Saying that a
     *   private message was sent, and to whom, is all the group can learn from the line
     *   and all the preview should claim.
     * - anything else is somebody's words, prefixed with who said them -- except in a
     *   two-person room, where the only other name is already the row's title.
     */
    fun lastChatMessagePreviewText(): String? {
        val lastChatMessage = lastChatMessage ?: return null

        if (lastChatMessage.messageType in ChatMessage.DKG_TYPES ||
            lastChatMessage.messageType in ChatMessage.ICEBERG_TYPES ||
            lastChatMessage.messageType in ChatMessage.FROST_TYPES ||
            lastChatMessage.messageType in ChatMessage.CHRONICLE_TYPES ||
            lastChatMessage.messageType in ChatMessage.MEMBERSHIP_TYPES ||
            lastChatMessage.messageType in ChatMessage.SUBGROUP_TYPES ||
            lastChatMessage.messageType in ChatMessage.GROUP_IDENTITY_TYPES
        ) {
            val isAuthored = lastChatMessage.messageType in ChatMessage.DKG_AUTHORED_TYPES ||
                lastChatMessage.messageType in ChatMessage.ICEBERG_AUTHORED_TYPES ||
                lastChatMessage.messageType in ChatMessage.FROST_AUTHORED_TYPES

            return if (isAuthored) {
                "${nameFor(lastChatMessage.senderPublicKey)} ${lastChatMessage.content}"
            } else {
                lastChatMessage.content
            }
        }

        if (lastChatMessage.messageType == ChatMessage.TYPE_DIRECT_MESSAGE &&
            lastChatMessage.content.isBlank()
        ) {
            return "${nameFor(lastChatMessage.senderPublicKey)} sent a private message to " +
                nameFor(lastChatMessage.directMessageRecipientPublicKey)
        }

        val isGroup = localParticipants.count {
            it.participant.participantPublicKey != chatRoom.userPublicKey
        } > 1

        return when {
            lastChatMessage.isUserMessage -> "You: ${lastChatMessage.content}"
            isGroup -> "${nameFor(lastChatMessage.senderPublicKey)}: ${lastChatMessage.content}"
            else -> lastChatMessage.content
        }
    }

    /** "someone" for a message with no recipient -- which no direct message has. */
    private fun nameFor(publicKey: HexKey?): String =
        publicKey?.memberName(localParticipants, chatRoom.userPublicKey) ?: "someone"
}
