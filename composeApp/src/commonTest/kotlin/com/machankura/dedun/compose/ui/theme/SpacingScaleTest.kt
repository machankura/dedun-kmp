package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spacing scale against M3's published token values.
 *
 * A scale is only worth having if it is the same scale everyone else is using. These are
 * transcribed from m3.material.io/m3/pages/spacing/tokens, and the point of asserting them
 * is that a transcription error is invisible: `space175 = 15.dp` would look entirely
 * plausible in the source, would compile, and would put every call site that reached for
 * it one unit off the grid.
 */
class SpacingScaleTest {

    private val spacing = Spacing()

    /** M3's system spacing tokens, token name to value. */
    private val published: List<Pair<String, Dp>> = listOf(
        "space0" to 0.dp,
        "space25" to 2.dp,
        "space50" to 4.dp,
        "space75" to 6.dp,
        "space100" to 8.dp,
        "space125" to 10.dp,
        "space150" to 12.dp,
        "space175" to 14.dp,
        "space200" to 16.dp,
        "space250" to 20.dp,
        "space300" to 24.dp,
        "space400" to 32.dp,
        "space450" to 36.dp,
        "space500" to 40.dp,
        "space600" to 48.dp,
        "space700" to 56.dp,
        "space800" to 64.dp,
        "space900" to 72.dp,
    )

    private val declared: List<Pair<String, Dp>> = listOf(
        "space0" to spacing.space0,
        "space25" to spacing.space25,
        "space50" to spacing.space50,
        "space75" to spacing.space75,
        "space100" to spacing.space100,
        "space125" to spacing.space125,
        "space150" to spacing.space150,
        "space175" to spacing.space175,
        "space200" to spacing.space200,
        "space250" to spacing.space250,
        "space300" to spacing.space300,
        "space400" to spacing.space400,
        "space450" to spacing.space450,
        "space500" to spacing.space500,
        "space600" to spacing.space600,
        "space700" to spacing.space700,
        "space800" to spacing.space800,
        "space900" to spacing.space900,
    )

    @Test
    fun `every stop matches its published value`() {
        declared.zip(published).forEach { (mine, theirs) ->
            assertEquals(theirs.second, mine.second, "${mine.first} is ${mine.second}, M3 says ${theirs.second}")
        }
    }

    @Test
    fun `the token name is the value's relation to space100`() {
        // The naming rule, which is what makes the scale readable: the number after
        // "space" is the value as a percentage of the 8dp base. space250 is 20dp because
        // 20 is 250% of 8. A stop that does not obey it is a stop nobody can predict.
        val base = 8.0
        declared.forEach { (name, value) ->
            val percent = name.removePrefix("space").toInt()
            assertEquals(
                base * percent / 100.0,
                value.value.toDouble(),
                absoluteTolerance = 0.001,
                message = "$name should be ${base * percent / 100.0}dp to match its name, is $value",
            )
        }
    }

    @Test
    fun `the scale rises`() {
        declared.zipWithNext().forEach { (a, b) ->
            assertTrue(b.second > a.second, "${b.first} (${b.second}) is not greater than ${a.first} (${a.second})")
        }
    }

    @Test
    fun `every semantic name resolves to a stop on the scale`() {
        // The semantic layer exists so call sites say the job rather than the size. It
        // stops being a scale the moment one of them is given a literal instead, which is
        // an easy thing to do and an invisible thing to review.
        val stops = declared.map { it.second }.toSet()
        val semantic = listOf(
            "screenMargin" to spacing.screenMargin,
            "containerPadding" to spacing.containerPadding,
            "compactPadding" to spacing.compactPadding,
            "relatedGap" to spacing.relatedGap,
            "itemGap" to spacing.itemGap,
            "sectionGap" to spacing.sectionGap,
            "emphasisGap" to spacing.emphasisGap,
            "targetGap" to spacing.targetGap,
        )

        semantic.forEach { (name, value) ->
            assertTrue(value in stops, "$name is $value, which is not on the scale")
        }
    }

    @Test
    fun `adjacent touch targets are held at least 8dp apart`() {
        // M3: "targets separated by 8dp of space or more promote balanced information
        // density and usability." targetGap is what the phase 3 sweep applies between
        // icon buttons, so it is the one semantic name with an external floor.
        assertTrue(
            spacing.targetGap >= 8.dp,
            "targetGap is ${spacing.targetGap}, below M3's 8dp minimum separation",
        )
    }

    @Test
    fun `a scaled instance moves every stop and every semantic name with it`() {
        // What the data class is for. The breakpoint phase provides a wider Spacing at
        // larger windows; if a semantic name were a hardcoded Dp rather than a reference
        // to a stop, it would stay behind and the layout would half-adapt.
        val wide = Spacing(space200 = 24.dp, space300 = 32.dp)

        assertEquals(24.dp, wide.screenMargin, "screenMargin did not follow space200")
        assertEquals(24.dp, wide.containerPadding, "containerPadding did not follow space200")
        assertEquals(32.dp, wide.sectionGap, "sectionGap did not follow space300")
        assertEquals(spacing.itemGap, wide.itemGap, "itemGap moved without space100 moving")
    }
}
