package com.machankura.dedun.compose.database.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Which room a stored kind:445 belongs to.
 *
 * `NostrEventDao.getMarmotGroupNostrEventsByChatRoomId` finds a room's group
 * events with `tags LIKE '%' || :chatRoomId || '%'`, because the tags are one
 * serialised column and there is nothing better to match on. That is a prefilter
 * and not a test: a group id can appear in a tag that is not the `h` tag, and an
 * event matched that way belongs to a different room entirely. Feeding one of
 * those to the replay would read another group's event against this group's MLS
 * state.
 *
 * So the reindex confirms `groupId()` in Kotlin after the query. These pin the
 * cases that confirmation is there for.
 */
class MarmotGroupEventRoomFilterTest {

    private val room = "a".repeat(64)
    private val otherRoom = "d".repeat(64)
    private val ephemeralSender = "e".repeat(64)

    private fun storedGroupEvent(
        tags: Array<Array<String>>,
        kind: Int = 445,
    ) = NostrEvent(
        id = "1".repeat(64),
        pubKey = ephemeralSender,
        kind = kind,
        tags = tags,
        content = "bm9uY2UrY2lwaGVydGV4dA==",
        sig = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    @Test
    fun `the h tag is what names the room`() {
        val stored = storedGroupEvent(arrayOf(arrayOf("h", room)))

        assertEquals(room, stored.toGroupEvent(userPublicKey = ephemeralSender)?.groupId())
    }

    /**
     * The case the `LIKE` cannot tell apart: this event is another room's, and
     * only mentions ours in a tag that says nothing about routing.
     */
    @Test
    fun `an event mentioning the room outside its h tag belongs to the other room`() {
        val stored = storedGroupEvent(
            arrayOf(
                arrayOf("h", otherRoom),
                arrayOf("q", room),
            )
        )

        val groupId = stored.toGroupEvent(userPublicKey = ephemeralSender)?.groupId()

        assertEquals(otherRoom, groupId)
        assertNotEquals(room, groupId, "the LIKE would match this event; groupId() is what rejects it")
    }

    @Test
    fun `an event with no h tag names no room`() {
        val stored = storedGroupEvent(arrayOf(arrayOf("q", room)))

        assertNull(stored.toGroupEvent(userPublicKey = ephemeralSender)?.groupId())
    }

    /**
     * Only kind:445 is a group event. The query pins the kind in SQL, and this is
     * the other half of that: nothing else can be read as one by mistake.
     */
    @Test
    fun `an event of another kind is not a group event`() {
        val stored = storedGroupEvent(tags = arrayOf(arrayOf("h", room)), kind = 9)

        assertNull(stored.toGroupEvent(userPublicKey = ephemeralSender))
    }
}
