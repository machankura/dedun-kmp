package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules for `t` and `n` every group is made on. docs/remove-convenient-groups.md. */
class QuorumTest {

    @Test
    fun `a group starts on a simple majority`() {
        assertEquals(
            listOf(2, 2, 3, 3, 4, 4, 5, 5),
            (2..9).map(Quorum::default),
        )
    }

    @Test
    fun `the default is never below the minimum`() {
        (0..9).forEach { size ->
            assertTrue(Quorum.default(size) >= Quorum.MINIMUM, "default for $size")
        }
    }

    @Test
    fun `a quorum runs from two to everybody`() {
        assertEquals(2..3, Quorum.range(3))
        assertEquals(2..7, Quorum.range(7))
    }

    @Test
    fun `the default is always one the range offers`() {
        (Quorum.MINIMUM_GROUP_SIZE..9).forEach { size ->
            assertTrue(Quorum.default(size) in Quorum.range(size), "default for $size")
        }
    }

    @Test
    fun `a group needs three people, its creator included`() {
        assertFalse(Quorum.isPossible(1))
        assertFalse(Quorum.isPossible(2))
        assertTrue(Quorum.isPossible(3))
    }
}
