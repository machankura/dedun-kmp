package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupKeyState
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
}
