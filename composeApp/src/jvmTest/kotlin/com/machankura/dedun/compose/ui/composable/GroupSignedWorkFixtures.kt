package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.nostr.GroupPost
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import kotlin.time.Instant

/**
 * One group, for the screens that show it.
 *
 * Everything here is built rather than read out of a signed event, and these
 * tests are about the screens rather than about signatures -- so nothing here
 * verifies, and none of the keys derive.
 */
internal object GroupSignedWorkFixtures {

    val chatRoomId = "d4c3b2a1".repeat(8)

    val activeUserPublicKey = "a".repeat(64)

    /** The room, as this device names it. */
    fun localChatRoom(mlsGroupState: String? = "state") = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = activeUserPublicKey,
            subject = "Translation room",
            description = "A group translating hard books.",
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = mlsGroupState
        )
    )

    /**
     * A key state, so the signing key row is on the group's screen. Not a real
     * key: nothing here derives.
     */
    val groupKeyState = GroupKeyState(
        chatRoomId = chatRoomId,
        dkgSessionId = "c".repeat(64),
        thresholdPublicKey = "02".padEnd(66, 'a'),
        derivationPath = "m/9420/0/0",
        announcedBy = chatRoomId,
        announcedAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    /** The group's nostr profile. docs/group-nostr-profile-and-posts.md. */
    val profile = GroupNostrProfile(
        signedEvent = GroupSignedEvent(
            id = "e".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = MetadataEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
        metadata = UserMetadata().apply {
            displayName = "Translation collective"
            about = "A group translating hard books into Sesotho."
            nip05 = "group@example.com"
        }
    )

    /** One of the group's posts, at [createdAt]. */
    fun post(content: String, createdAt: Long) = GroupPost(
        signedEvent = GroupSignedEvent(
            id = createdAt.toString().padStart(64, 'b'),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = TextNoteEvent.KIND,
            tags = emptyArray(),
            content = content,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt)
        )
    )
}
