package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarmotRoomIdentityTest {
    private val roomId = "a1".repeat(32)

    @Test
    fun `the room id rides at the end of the description and reads back`() {
        val described = MarmotRoomIdentity.describe("Admins of X.\n\nShared key path: m/9420/0/0", roomId)

        assertEquals("Admins of X.\n\nShared key path: m/9420/0/0\n\nRoom id: $roomId", described)
        assertEquals(roomId, MarmotRoomIdentity.parse(described))
        // The path line beside it is untouched.
        assertEquals(listOf(9420L, 0L, 0L), SharedKeyDerivation.parsePath(described))
    }

    @Test
    fun `an empty description is just the line`() {
        assertEquals("Room id: $roomId", MarmotRoomIdentity.describe(null, roomId))
        assertEquals("Room id: $roomId", MarmotRoomIdentity.describe("  ", roomId))
    }

    @Test
    fun `describing twice does not repeat the line`() {
        val once = MarmotRoomIdentity.describe("d", roomId)
        assertEquals(once, MarmotRoomIdentity.describe(once, roomId))
    }

    @Test
    fun `a description that names no room or names it badly yields none`() {
        assertNull(MarmotRoomIdentity.parse(null))
        assertNull(MarmotRoomIdentity.parse("Admins of X."))
        assertNull(MarmotRoomIdentity.parse("Room id: not-hex"))
        assertNull(MarmotRoomIdentity.parse("Room id: " + "a".repeat(63)))
        assertEquals(roomId, MarmotRoomIdentity.parse("  Room id: ${roomId.uppercase()}  "))
    }
}
