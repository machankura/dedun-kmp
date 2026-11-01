package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The breakpoint thresholds against M3's published values, and the spacing they select.
 *
 * The thresholds are the kind of number that is wrong silently: a layout that switches to
 * two panes at 640dp instead of 600dp still looks like a working layout on every device
 * anybody happens to test, and only misbehaves in the 40dp nobody opens. Asserting the
 * boundary from both sides is the only way that shows up.
 */
class BreakpointTest {

    /** m3.material.io/foundations/layout/applying-layout/window-size-classes, September 2026. */
    private val published = listOf(
        Breakpoint.Compact to 0,
        Breakpoint.Medium to 600,
        Breakpoint.Expanded to 840,
        Breakpoint.Large to 1200,
        Breakpoint.ExtraLarge to 1600,
    )

    @Test
    fun `each breakpoint starts at its published width`() {
        assertEquals(published.size, Breakpoint.entries.size, "a breakpoint was added or removed")
        published.forEach { (breakpoint, lowerBound) ->
            assertEquals(
                lowerBound.dp,
                breakpoint.minWidth,
                "$breakpoint starts at ${breakpoint.minWidth}, M3 says $lowerBound.dp",
            )
        }
    }

    @Test
    fun `the lower bound belongs to the breakpoint it opens`() {
        // Half-open on the upper bound: exactly 600dp is Medium, and 599dp is Compact.
        // Off by one here and a phone in landscape gets the tablet layout, or does not.
        published.forEach { (breakpoint, lowerBound) ->
            assertEquals(
                breakpoint,
                Breakpoint.ofWidth(lowerBound.dp),
                "${lowerBound}dp should be exactly at the bottom of $breakpoint",
            )
        }
    }

    @Test
    fun `one dp below a lower bound is the breakpoint beneath it`() {
        published.drop(1).forEachIndexed { indexBefore, (breakpoint, lowerBound) ->
            val beneath = published[indexBefore].first
            assertEquals(
                beneath,
                Breakpoint.ofWidth((lowerBound - 1).dp),
                "${lowerBound - 1}dp fell in $breakpoint rather than $beneath",
            )
        }
    }

    @Test
    fun `the widths the phase is meant to be checked at land where the plan says`() {
        // The five window widths phase 6's acceptance criterion names. If one of these
        // ever moves, the manual check and the code have stopped talking about the same
        // thing.
        assertEquals(Breakpoint.Compact, Breakpoint.ofWidth(400.dp))
        assertEquals(Breakpoint.Medium, Breakpoint.ofWidth(700.dp))
        assertEquals(Breakpoint.Expanded, Breakpoint.ofWidth(1000.dp))
        assertEquals(Breakpoint.Large, Breakpoint.ofWidth(1400.dp))
        assertEquals(Breakpoint.ExtraLarge, Breakpoint.ofWidth(1800.dp))
    }

    @Test
    fun `a zero or negative width is compact rather than an error`() {
        // A window has zero width for one frame on desktop, before the first layout pass,
        // and `ofWidth` is called from the theme every composition. Throwing there would
        // take the app down on a resize.
        assertEquals(Breakpoint.Compact, Breakpoint.ofWidth(0.dp))
        assertEquals(Breakpoint.Compact, Breakpoint.ofWidth((-1).dp))
    }

    @Test
    fun `isAtLeast reads up the scale and not down it`() {
        assertTrue(Breakpoint.Large.isAtLeast(Breakpoint.Expanded))
        assertTrue(Breakpoint.Expanded.isAtLeast(Breakpoint.Expanded))
        assertTrue(!Breakpoint.Medium.isAtLeast(Breakpoint.Expanded))
    }

    @Test
    fun `the screen margin widens at medium and holds there`() {
        // M3 publishes 16dp compact, 24dp for every wider breakpoint -- it does not keep
        // growing. A margin that scaled with the window would push a bounded column of
        // text further from the edge for no reason at 1800dp.
        assertEquals(16.dp, spacingFor(Breakpoint.Compact).screenMargin)
        listOf(Breakpoint.Medium, Breakpoint.Expanded, Breakpoint.Large, Breakpoint.ExtraLarge)
            .forEach { assertEquals(24.dp, spacingFor(it).screenMargin, "$it") }
    }

    @Test
    fun `nothing but the screen margin moves with the breakpoint`() {
        // The scale itself is absolute -- space200 is 16dp in every window -- and the
        // other semantic names are component decisions. This is the assertion that stops
        // a later "make it breathe on desktop" edit from quietly turning the whole scale
        // into a zoom factor.
        val compact = spacingFor(Breakpoint.Compact)
        val wide = spacingFor(Breakpoint.ExtraLarge)

        assertEquals(compact, wide.copy(screenMargin = compact.screenMargin))
    }

    @Test
    fun `every breakpoint gets one of two shared instances`() {
        // LocalSpacing is a static composition local: it invalidates on identity, not on
        // equality, so handing it a freshly built but equal Spacing every recomposition
        // would restart every composition that reads spacing.
        assertSame(spacingFor(Breakpoint.Medium), spacingFor(Breakpoint.ExtraLarge))
        assertSame(spacingFor(Breakpoint.Compact), spacingFor(Breakpoint.Compact))
    }
}
