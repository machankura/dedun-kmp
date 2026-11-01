package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * M3's spacing scale, and the semantic names layered over it.
 *
 * `MaterialTheme` has no spacing slot, so this rides a composition local beside it, the
 * same way [ExtendedColors] does. [DedunTheme] provides it.
 *
 * **The scale** is M3's own, an 8dp system where `space100 = 8dp`, including the sub-8
 * nested units Material defines because its components need them. Numbers are from
 * m3.material.io/m3/pages/spacing/tokens, read September 2026; see
 * docs/material-design-conformance.md for the full table.
 *
 * **Why a `data class` and not constants.** So that a different instance can be provided
 * without touching a call site, which is what [spacingFor] now does per [Breakpoint] and
 * what M3's density setting for data-heavy views would do next. A file of top-level
 * `val`s would read the same at the call site and adapt to nothing.
 */
@Immutable
data class Spacing(
    val space0: Dp = 0.dp,
    val space25: Dp = 2.dp,
    val space50: Dp = 4.dp,
    val space75: Dp = 6.dp,
    val space100: Dp = 8.dp,
    val space125: Dp = 10.dp,
    val space150: Dp = 12.dp,
    val space175: Dp = 14.dp,
    val space200: Dp = 16.dp,
    val space250: Dp = 20.dp,
    val space300: Dp = 24.dp,
    val space400: Dp = 32.dp,
    val space450: Dp = 36.dp,
    val space500: Dp = 40.dp,
    val space600: Dp = 48.dp,
    val space700: Dp = 56.dp,
    val space800: Dp = 64.dp,
    val space900: Dp = 72.dp,

    // -----------------------------------------------------------------------
    // Semantic names
    // -----------------------------------------------------------------------
    //
    // `space125` at a call site is no more readable than `10.dp` -- it says the size and
    // not the job. These say the job, and they are what call sites should reach for; the
    // raw scale is for the cases none of them fits.
    //
    // The distinction M3 draws, and the reason the names are split this way:
    //
    //   padding  space inside an element, between its edge and its content
    //   gap      space between elements in a row, column or grid
    //   margin   space outside an element, between it and its parent or the screen
    //
    // The spec is explicit that margins are a last resort -- "define padding and gaps on
    // the parent container", "avoid defining margins on child elements as they usually
    // aren't uniform, and require more tokens" -- so there is exactly one margin here,
    // for the screen edge, and everything else is padding or a gap.

    // They are constructor parameters rather than `get()`s over the scale so that a
    // breakpoint can reassign one without moving the stop underneath it. That direction
    // matters: M3's spacing tokens are absolute values that do not change with window
    // width -- `space200` is 16dp on a phone and 16dp on a desktop -- and what adapts is
    // which token a given job reaches for. A wider window takes a wider screen margin, it
    // does not take a wider 16.
    //
    // Kotlin resolves a default expression against the parameters before it, so each of
    // these still reads its stop by name and follows it when the scale itself is
    // overridden. `Spacing(space200 = 24.dp)` still moves `screenMargin`.

    /** Screen edge to content. The one margin; everything inside a screen is padding or a gap. */
    val screenMargin: Dp = space200,

    /** Inside a card, dialog, sheet or list row: container edge to its content. */
    val containerPadding: Dp = space200,

    /** Inside a compact container -- a chip, a badge, a dense row. */
    val compactPadding: Dp = space100,

    /** Between two elements that belong to the same thought: a label and its value. */
    val relatedGap: Dp = space50,

    /** The default gap between items in a list or column. */
    val itemGap: Dp = space100,

    /** Between one group of content and the next within a screen. */
    val sectionGap: Dp = space300,

    /** Around a lone element that needs to stand apart -- an empty state, a hero action. */
    val emphasisGap: Dp = space500,

    /** Between adjacent touch targets, which M3 asks to be at least 8dp apart. */
    val targetGap: Dp = space100,

    /**
     * Between two panes. M3's own `PaneScaffoldDirective` uses 24dp at every breakpoint
     * that has a second pane, which is why this does not vary with the window either.
     */
    val paneGap: Dp = space300,
)

/**
 * The spacing a window at [breakpoint] should use.
 *
 * Exactly one value moves, and that is not an oversight. M3 publishes a margin per
 * breakpoint -- 16dp compact, 24dp everywhere wider -- and publishes nothing else that
 * varies with window width: padding inside a card and the gap between two list rows are
 * component decisions, and a card does not become a different component because the
 * window grew. Widening them all would be the "everything breathes on a big screen"
 * instinct, which reads as a zoomed phone rather than as a layout.
 *
 * What actually fills a wide window is a second pane and a bounded measure, not fatter
 * gaps. Those are layout, and they live in `AdaptiveContent` rather than here.
 */
fun spacingFor(breakpoint: Breakpoint): Spacing =
    if (breakpoint == Breakpoint.Compact) CompactSpacing else MediumAndWiderSpacing

// Held as singletons rather than built per call. `LocalSpacing` is a
// `staticCompositionLocalOf`, so a provider that hands it a fresh but equal instance on
// every recomposition would restart every composition reading it; `Spacing` is a data
// class, but static locals compare by identity when deciding whether to invalidate.
private val CompactSpacing = Spacing()
private val MediumAndWiderSpacing = Spacing(screenMargin = 24.dp)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/**
 * `MaterialTheme.spacing.containerPadding`, to match `MaterialTheme.colorScheme.primary`.
 *
 * The alternative is `LocalSpacing.current`, which has to be read into a local before it
 * can be used and so cannot appear inline in a `Modifier` chain. That difference decides
 * whether a call site reads
 *
 *     Modifier.padding(MaterialTheme.spacing.containerPadding)
 *
 * or acquires a `val spacing = LocalSpacing.current` several lines above, in whichever
 * composable happens to enclose it. Over 500 call sites the second shape is what stops
 * people using the scale.
 */
val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
