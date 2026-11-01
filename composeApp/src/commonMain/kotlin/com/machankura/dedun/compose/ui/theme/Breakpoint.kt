package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * M3's five window width breakpoints.
 *
 * Renamed from "window size class" in the May 2026 revision, which also grew the set from
 * three to five: [Large] and [ExtraLarge] were split off the old expanded class because a
 * 1600dp window and an 850dp one want different numbers of panes. Values are from
 * m3.material.io/foundations/layout/applying-layout/window-size-classes, read September
 * 2026.
 *
 * | breakpoint   | width       | panes                  | navigation              |
 * |--------------|-------------|------------------------|-------------------------|
 * | [Compact]    | under 600dp | 1                      | navigation bar          |
 * | [Medium]     | 600–839dp   | 1 recommended          | collapsed rail          |
 * | [Expanded]   | 840–1199dp  | 2 recommended          | collapsed/expanded rail |
 * | [Large]      | 1200–1599dp | 2 recommended          | expanded rail           |
 * | [ExtraLarge] | 1600dp+     | up to 3                | expanded rail           |
 *
 * The classification is on the **window**, not on the composable being measured. That is
 * the distinction between this and `BoxWithConstraints`: a pane 300dp wide inside a
 * 1400dp window is still in a large layout, and should not start behaving like a phone.
 * Anything wanting the local constraints should still measure them.
 *
 * @property minWidth the narrowest window that falls in this breakpoint.
 */
enum class Breakpoint(val minWidth: Dp) {
    Compact(0.dp),
    Medium(600.dp),
    Expanded(840.dp),
    Large(1200.dp),
    ExtraLarge(1600.dp);

    /**
     * `true` when this breakpoint is [other] or anything wider.
     *
     * The comparison call sites want almost always. Enum ordering already answers it, but
     * `breakpoint >= Breakpoint.Expanded` reads as a size comparison on a width and is
     * one edit away from being wrong if a breakpoint is ever inserted; this says what it
     * means.
     */
    fun isAtLeast(other: Breakpoint): Boolean = ordinal >= other.ordinal

    companion object {
        /**
         * The breakpoint a window of [width] falls in.
         *
         * Ranges are half-open on the upper bound -- exactly 600dp is [Medium], not
         * [Compact] -- which is how M3 states them and how `WindowSizeClass` computes
         * them.
         *
         * Total, including for a width below [Compact.minWidth]. This is called from
         * [BennuTheme] on every composition, and a desktop window reports a zero size for
         * the frame before its first layout pass; throwing there would take the app down
         * on a resize rather than on anything a user did.
         */
        fun ofWidth(width: Dp): Breakpoint =
            entries.lastOrNull { width >= it.minWidth } ?: Compact
    }
}

/**
 * The breakpoint the current window is in.
 *
 * Provided by [BennuTheme] as [LocalBreakpoint], so screens read
 * `MaterialTheme.breakpoint` rather than calling this. It is public because the two
 * entry points that compose above the theme -- the desktop passphrase gate, and any
 * future splash -- have nowhere else to get it.
 *
 * **Why the window size and not `currentWindowAdaptiveInfo()`.** The latter also computes
 * a [androidx.compose.material3.adaptive.Posture] from the platform's fold state, which
 * on android means reaching for `WindowInfoTracker` and an `Activity`. This is called
 * from [BennuTheme], which wraps every `@Preview` in the tree, and a preview context is
 * not an activity. `currentWindowDpSize()` is `LocalWindowInfo` and `LocalDensity` and
 * nothing else, so it answers everywhere. The pane scaffolds ask for posture themselves,
 * where a fold genuinely changes the answer.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun currentBreakpoint(): Breakpoint = Breakpoint.ofWidth(currentWindowDpSize().width)

/**
 * Defaults to [Breakpoint.Compact] rather than throwing, because that is the layout every
 * screen in this app was written against: a composable that never reaches a [BennuTheme]
 * should render as it did before breakpoints existed, not fail.
 */
val LocalBreakpoint = staticCompositionLocalOf { Breakpoint.Compact }

/** `MaterialTheme.breakpoint`, to match `MaterialTheme.spacing` and `MaterialTheme.colorScheme`. */
val MaterialTheme.breakpoint: Breakpoint
    @Composable
    @ReadOnlyComposable
    get() = LocalBreakpoint.current
