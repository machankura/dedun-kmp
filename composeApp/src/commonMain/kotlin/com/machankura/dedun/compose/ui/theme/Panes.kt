package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide a list pane should be at [breakpoint], or `null` where M3 asks for one pane.
 *
 * **`null` below expanded is the spec, not caution.** The breakpoints page says not to put
 * two dense panes in a medium window, and `calculatePaneScaffoldDirective` in
 * `material3-adaptive` says the same thing in code: `maxHorizontalPartitions = 1` for both
 * compact and medium. A chat transcript is exactly the dense content that rule is about.
 *
 * The two widths are that same function's, so a hand-built pair of panes measures the same
 * as a `ListDetailPaneScaffold` would: `DefaultPreferredWidth` at expanded, and
 * `DefaultPreferredWidthXL` from large upward, where the directive also allows a third
 * partition this app has no content for.
 *
 * **Why not the scaffold itself.** `ListDetailPaneScaffold` earns its API surface by
 * owning the single-pane case too -- showing the detail *instead of* the list on a phone,
 * and animating between them. This app cannot hand it that: the chat room is a navigation
 * destination reached from eleven places, so on a compact window the detail has to stay a
 * pushed route. A scaffold permanently in its two-pane state would be a `Row` with more
 * words, and a navigator whose history nothing reads.
 */
fun listPaneWidthFor(breakpoint: Breakpoint): Dp? = when (breakpoint) {
    Breakpoint.Compact, Breakpoint.Medium -> null
    Breakpoint.Expanded -> 360.dp
    Breakpoint.Large, Breakpoint.ExtraLarge -> 412.dp
}
