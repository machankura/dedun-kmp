package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a second pane appears, and how wide the first one is.
 *
 * The numbers are `material3-adaptive`'s own `calculatePaneScaffoldDirective`, transcribed
 * so that a hand-built pair of panes measures the same as a `ListDetailPaneScaffold` would.
 * A transcription error here is the kind that looks fine on the one desktop anybody opens.
 */
class PanesTest {

    @Test
    fun `no second pane below expanded`() {
        // M3: "don't use two panes in medium layouts with high information density", and
        // `calculatePaneScaffoldDirective` agrees in code -- `maxHorizontalPartitions = 1`
        // for compact and medium alike. A chat transcript is that dense content.
        assertNull(listPaneWidthFor(Breakpoint.Compact))
        assertNull(listPaneWidthFor(Breakpoint.Medium))
    }

    @Test
    fun `the list pane takes the directive's two widths`() {
        assertEquals(360.dp, listPaneWidthFor(Breakpoint.Expanded))
        assertEquals(412.dp, listPaneWidthFor(Breakpoint.Large))
        assertEquals(412.dp, listPaneWidthFor(Breakpoint.ExtraLarge))
    }

    @Test
    fun `the list pane never widens past the window that first allowed it`() {
        // The failure a fixed width invites: a pane wider than the breakpoint that opens
        // it leaves the detail pane with nothing, or negative space. 360dp of list plus
        // 24dp of gap inside the 840dp window that first allows two panes leaves 456dp for
        // the transcript, which is above the 40-character floor.
        Breakpoint.entries.forEach { breakpoint ->
            val width = listPaneWidthFor(breakpoint) ?: return@forEach
            val gap = Spacing().paneGap
            val detail = breakpoint.minWidth - width - gap

            assertTrue(
                detail >= readableWidthFor(16.dp, charactersPerLine = 40),
                "at $breakpoint the detail pane starts at $detail, under a 40-character line",
            )
        }
    }

    @Test
    fun `the gap between panes is the directive's 24dp at every width that has one`() {
        // `PaneScaffoldDirective` uses 24dp for `horizontalPartitionSpacerSize` at every
        // breakpoint with a second pane, which is why `paneGap` does not vary either.
        assertEquals(24.dp, Spacing().paneGap)
        assertEquals(
            spacingFor(Breakpoint.Compact).paneGap,
            spacingFor(Breakpoint.ExtraLarge).paneGap,
            "the pane gap moved with the breakpoint, which the directive does not do",
        )
    }

    @Test
    fun `two panes begin exactly where the breakpoint says`() {
        // The pairing that has to hold: the first breakpoint with a list pane is the first
        // one M3 recommends two panes in. If a breakpoint were inserted, or `Expanded`
        // renumbered, this is what notices.
        assertNotNull(listPaneWidthFor(Breakpoint.Expanded))
        assertEquals(840.dp, Breakpoint.Expanded.minWidth)
        assertNull(listPaneWidthFor(Breakpoint.ofWidth(839.dp)))
        assertNotNull(listPaneWidthFor(Breakpoint.ofWidth(840.dp)))
    }
}
