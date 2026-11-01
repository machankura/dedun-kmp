package com.machankura.dedun.compose.text

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.text.BlockDuration.Approximate
import com.machankura.dedun.compose.text.BlockDuration.Unit.DAYS
import com.machankura.dedun.compose.text.BlockDuration.Unit.HOURS
import com.machankura.dedun.compose.text.BlockDuration.Unit.MINUTES
import com.machankura.dedun.compose.text.BlockDuration.Unit.MONTHS
import com.machankura.dedun.compose.text.BlockDuration.Unit.YEARS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a guardian's delay is said in time -- `docs/guardian-delay.md`, D5. The
 * table is the plan's, row for row, so the words a member reads are the words
 * the plan argued for.
 */
class BlockDurationTest {
    @Test
    fun `every row of the plan's table`() {
        val table = mapOf(
            6L to Approximate(1, HOURS),
            12L to Approximate(2, HOURS),
            100L to Approximate(17, HOURS),
            144L to Approximate(1, DAYS),
            216L to Approximate(36, HOURS),
            1_008L to Approximate(7, DAYS),
            4_320L to Approximate(1, MONTHS),
            13_140L to Approximate(3, MONTHS),
            26_280L to Approximate(6, MONTHS),
            52_560L to Approximate(1, YEARS),
            65_535L to Approximate(15, MONTHS),
        )
        table.forEach { (blocks, expected) -> assertEquals(expected, BlockDuration.approximate(blocks), "$blocks blocks") }
    }

    @Test
    fun `exactly one of a unit is a five percent window either side`() {
        // A day is 144 blocks: 137 is 95.1% of it and 151 is 104.9%.
        assertEquals(Approximate(1, DAYS), BlockDuration.approximate(137))
        assertEquals(Approximate(1, DAYS), BlockDuration.approximate(151))
        assertEquals(Approximate(23, HOURS), BlockDuration.approximate(136))
        assertEquals(Approximate(25, HOURS), BlockDuration.approximate(152))
    }

    @Test
    fun `under an hour is minutes`() {
        assertEquals(Approximate(10, MINUTES), BlockDuration.approximate(1))
        assertEquals(Approximate(50, MINUTES), BlockDuration.approximate(5))
    }

    @Test
    fun `nothing a leaf can carry comes out as zero or past a year`() {
        GuardianDerivation.DELAY_RANGE.forEach { blocks ->
            val approximate = BlockDuration.approximate(blocks)
            assertTrue(approximate.count >= 1, "$blocks blocks came out as $approximate")
            assertTrue(approximate.unit != YEARS || approximate.count == 1, "$blocks blocks came out as $approximate")
        }
    }

    @Test
    fun `a block count is grouped in thousands`() {
        assertEquals("6", 6L.groupedBlocks())
        assertEquals("1,008", 1_008L.groupedBlocks())
        assertEquals("65,535", 65_535L.groupedBlocks())
    }
}
