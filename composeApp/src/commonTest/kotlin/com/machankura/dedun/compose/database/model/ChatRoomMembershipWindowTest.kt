package com.machankura.dedun.compose.database.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Which of a group's kind:445 events are this device's to read at all.
 *
 * MLS hands a joiner the key schedule from their own epoch forward and nothing
 * before it, but the relay hands them the whole room. Everything the group
 * published earlier still syncs down and is still permanently unreadable, so a
 * member invited into a busy room opened it on a run of "Undecryptable Message"
 * above the conversation they were invited to.
 *
 * The epoch an event was encrypted under is inside the layer that will not
 * decrypt, so the only thing left to ask is when it was published. That makes
 * the boundary a judgement call rather than a fact, and the direction it errs in
 * is what these pin down: an unreadable event kept costs one refused decrypt, a
 * readable event discarded is a message the member never sees.
 */
class ChatRoomMembershipWindowTest {

    private val roomId = "a".repeat(64)
    private val user = "b".repeat(64)

    private fun room(
        joinedGroupAt: Instant?,
        createdAt: Instant = Instant.fromEpochSeconds(5_000),
    ) = ChatRoom(
        id = roomId,
        userPublicKey = user,
        subject = null,
        description = null,
        mlsGroupState = null,
        joinedGroupAt = joinedGroupAt,
        createdAt = createdAt,
    )

    @Test
    fun `a message from before the welcome is not this devices to read`() {
        val chatRoom = room(joinedGroupAt = Instant.fromEpochSeconds(2_000))

        assertTrue(chatRoom.predatesMembership(Instant.fromEpochSeconds(1_999)))
    }

    @Test
    fun `a message from after the welcome is`() {
        val chatRoom = room(joinedGroupAt = Instant.fromEpochSeconds(2_000))

        assertFalse(chatRoom.predatesMembership(Instant.fromEpochSeconds(2_001)))
    }

    /**
     * The boundary, and the reason it is drawn strictly.
     *
     * Nostr stamps `created_at` in whole seconds, so the second the Welcome was
     * minted holds both the commit that added us -- the last act of the epoch
     * before ours, unreadable by construction -- and any message another member
     * sent the instant they applied it. Only one of those two can be had, and a
     * message is worth more than a spared decrypt.
     */
    @Test
    fun `a message from the very second of the welcome is kept`() {
        val chatRoom = room(joinedGroupAt = Instant.fromEpochSeconds(2_000))

        assertFalse(chatRoom.predatesMembership(Instant.fromEpochSeconds(2_000)))
    }

    /**
     * A room joined before the column existed. `createdAt` is when this device
     * wrote the row down, which for a joiner is the Welcome it wrote it from --
     * the same answer every path that sets `joinedGroupAt` would have written.
     */
    @Test
    fun `a room with no recorded join falls back to when it was written down`() {
        val chatRoom = room(joinedGroupAt = null, createdAt = Instant.fromEpochSeconds(5_000))

        assertEquals(Instant.fromEpochSeconds(5_000), chatRoom.memberSince)
        assertTrue(chatRoom.predatesMembership(Instant.fromEpochSeconds(4_999)))
        assertFalse(chatRoom.predatesMembership(Instant.fromEpochSeconds(5_000)))
    }

    /**
     * A device that joined a room it had already heard of -- its own invite
     * gift wrap arrived out of order, say, and the row was written before the
     * Welcome was processed. The recorded join is the group's own account of
     * when our epoch began and beats this device's account of when it started
     * keeping notes.
     */
    @Test
    fun `a recorded join wins over when the row was written`() {
        val chatRoom = room(
            joinedGroupAt = Instant.fromEpochSeconds(9_000),
            createdAt = Instant.fromEpochSeconds(5_000),
        )

        assertEquals(Instant.fromEpochSeconds(9_000), chatRoom.memberSince)
        assertTrue(chatRoom.predatesMembership(Instant.fromEpochSeconds(6_000)))
    }
}
