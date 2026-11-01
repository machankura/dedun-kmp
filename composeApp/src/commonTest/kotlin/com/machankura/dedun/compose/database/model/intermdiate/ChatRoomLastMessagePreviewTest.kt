package com.machankura.dedun.compose.database.model.intermdiate

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The one line under a room's name in the chat list.
 *
 * It is a summary of somebody's message shown next to their name, which is the shape
 * every way of getting this wrong takes: attributing a ceremony milestone to the member
 * who happened to trigger it, showing an empty row where a private message this device
 * cannot open should say so, or putting a name in front of a line in a two-person room
 * where the name is already the title.
 */
class ChatRoomLastMessagePreviewTest {

    private val user = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)

    private fun room(
        participants: List<String>,
        lastChatMessage: ChatRoomLastMessage?,
    ) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = "11".repeat(32),
            userPublicKey = user,
            subject = null,
            description = null,
            mlsGroupState = null,
        ),
        localParticipants = (participants + user).map { publicKey ->
            LocalParticipant(
                participant = Participant(
                    participantPublicKey = publicKey,
                    chatRoomId = "11".repeat(32),
                    relayHint = null,
                ),
                profile = Profile(
                    publicKey = publicKey,
                    userName = when (publicKey) {
                        alice -> "Alice"
                        bob -> "Bob"
                        else -> "Me"
                    },
                    nostrEventId = "d".repeat(64),
                ),
            )
        },
        lastChatMessage = lastChatMessage,
    )

    private fun message(
        content: String = "hello",
        sender: String = alice,
        messageType: String = "message",
        directMessageRecipientPublicKey: String? = null,
    ) = ChatRoomLastMessage(
        senderPublicKey = sender,
        isUserMessage = sender == user,
        content = content,
        messageType = messageType,
        directMessageRecipientPublicKey = directMessageRecipientPublicKey,
        createdAt = Instant.fromEpochSeconds(1_000),
    )

    @Test
    fun `a room with nothing said in it has no preview`() {
        assertNull(room(listOf(alice), lastChatMessage = null).lastChatMessagePreviewText())
    }

    /** The other party's name is the row's title already; repeating it says nothing. */
    @Test
    fun `a two-person room shows the words alone`() {
        assertEquals(
            "hello",
            room(listOf(alice), message(content = "hello")).lastChatMessagePreviewText()
        )
    }

    @Test
    fun `a group names who spoke`() {
        assertEquals(
            "Alice: hello",
            room(listOf(alice, bob), message(content = "hello")).lastChatMessagePreviewText()
        )
    }

    /** True in both room shapes: the title never names the reader. */
    @Test
    fun `the reader's own message is prefixed either way`() {
        assertEquals(
            "You: hello",
            room(listOf(alice), message(content = "hello", sender = user)).lastChatMessagePreviewText()
        )
        assertEquals(
            "You: hello",
            room(listOf(alice, bob), message(content = "hello", sender = user)).lastChatMessagePreviewText()
        )
    }

    /**
     * Ritual content is written as a predicate for the actor's name, so an authored line
     * needs it and reads as nonsense without it.
     */
    @Test
    fun `an authored ritual line reads as its actor doing it`() {
        assertEquals(
            "Alice published their share",
            room(
                listOf(alice, bob),
                message(content = "published their share", messageType = ChatMessage.TYPE_DKG_ROUND_1)
            ).lastChatMessagePreviewText()
        )
    }

    /**
     * The group ends up with a key; nobody hands it to them. A name in front of this
     * would read as the member having announced it.
     */
    @Test
    fun `a ritual line nobody authored stands alone`() {
        assertEquals(
            "The group now has a shared key",
            room(
                listOf(alice, bob),
                message(
                    content = "The group now has a shared key",
                    messageType = ChatMessage.TYPE_DKG_COMPLETE
                )
            ).lastChatMessagePreviewText()
        )
    }

    /** A chronicle is not anybody's words either, whichever end of it this device was on. */
    @Test
    fun `a chronicle line stands alone`() {
        assertEquals(
            "Caught up on 12 items",
            room(
                listOf(alice, bob),
                message(
                    content = "Caught up on 12 items",
                    messageType = ChatMessage.TYPE_CHRONICLE_RECEIVED
                )
            ).lastChatMessagePreviewText()
        )
    }

    /**
     * An invite is nobody's words either, and its content is a whole sentence with the
     * invitee's name already in it. A prefix here reads as the inviter having said
     * "Invited Bob to the group" out loud.
     */
    @Test
    fun `a membership line stands alone`() {
        ChatMessage.MEMBERSHIP_TYPES.forEach { messageType ->
            assertEquals(
                "Invited Bob to the group",
                room(
                    listOf(alice, bob),
                    message(content = "Invited Bob to the group", messageType = messageType)
                ).lastChatMessagePreviewText(),
                "$messageType should not be attributed to its sender"
            )
        }
    }

    /**
     * The bystanders' copy of a private message has no content. An empty preview would
     * read as the sender having said nothing, so the line says what the group can in
     * fact see: that a private message was sent, and to whom.
     */
    @Test
    fun `a private message this device cannot open says so`() {
        assertEquals(
            "Alice sent a private message to Bob",
            room(
                listOf(alice, bob),
                message(
                    content = "",
                    messageType = ChatMessage.TYPE_DIRECT_MESSAGE,
                    directMessageRecipientPublicKey = bob,
                )
            ).lastChatMessagePreviewText()
        )
    }

    /** A private message this device can read is a message like any other. */
    @Test
    fun `a readable private message shows its words`() {
        assertEquals(
            "Alice: just between us",
            room(
                listOf(alice, bob),
                message(
                    content = "just between us",
                    messageType = ChatMessage.TYPE_DIRECT_MESSAGE,
                    directMessageRecipientPublicKey = user,
                )
            ).lastChatMessagePreviewText()
        )
    }

    /**
     * A sender the room has no participant row for -- a member who has since left, or one
     * whose profile has not arrived. Falls back to a shortened key rather than dropping
     * the attribution, which would silently reassign the words to nobody.
     */
    @Test
    fun `an unknown sender is named by their key`() {
        val stranger = "e".repeat(64)

        assertEquals(
            "${stranger.take(8)}: hello",
            room(listOf(alice, bob), message(sender = stranger)).lastChatMessagePreviewText()
        )
    }
}
