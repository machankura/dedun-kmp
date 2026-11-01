package com.machankura.dedun.compose.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * M3's upper bound on line length: *"across all breakpoints, adjust margins and type
 * styles to keep text between 40–60 characters per line."*
 *
 * The lower bound is not enforced here, and does not need to be. A window narrower than
 * this holds fewer characters by arithmetic, and no cap can add any -- 40 is a floor on
 * the type scale and the margin, which the compact margin of 16dp already respects: a
 * 400dp phone at the default text size holds about 46 characters of `bodyLarge`.
 */
const val MaxCharactersPerLine = 60

/**
 * The width of one character of English prose, as a fraction of the font size.
 *
 * There is no exact answer -- a proportional face has no character width -- and this is
 * the standard estimate for mixed-case Latin text with its spaces: half an em. It is the
 * number to change if the app ever takes a font whose average advance is visibly wider or
 * narrower than the system stack's, and changing it moves the measure on every screen at
 * once, which is the reason it is a named constant rather than a `480.dp` somewhere.
 */
private const val AverageCharacterAdvance = 0.5f

/**
 * The widest a column of [charactersPerLine] characters should be.
 *
 * Derived from the type scale rather than fixed, and that is the point: `sp` carries the
 * reader's font scale, so at 200% text size the column doubles and still holds sixty
 * characters. A hardcoded `480.dp` would hold thirty, which is the failure the 40–60 rule
 * is about -- and it would fail silently, because the text still fits.
 */
@Composable
fun readableContentWidth(charactersPerLine: Int = MaxCharactersPerLine): Dp {
    val bodySize = MaterialTheme.typography.bodyLarge.fontSize
    // `bodyLarge` is declared in sp and this is the only role prose is set in, but a type
    // scale is a value somebody can edit, and an em-relative or unspecified size here
    // would convert to nonsense rather than fail. 16dp is what the baseline scale gives.
    val bodySizeDp = if (bodySize.isSp) with(LocalDensity.current) { bodySize.toDp() } else 16.dp
    return readableWidthFor(bodySizeDp, charactersPerLine)
}

/**
 * [readableContentWidth] without the composition, so the arithmetic can be asserted.
 */
fun readableWidthFor(fontSize: Dp, charactersPerLine: Int = MaxCharactersPerLine): Dp =
    fontSize * AverageCharacterAdvance * charactersPerLine

/**
 * Holds this element to a readable measure and centres it in whatever space it is given.
 *
 * The one change that decides whether a screen written for a phone survives a 1800dp
 * window. Without it a paragraph runs the full width and becomes unreadable -- the eye
 * loses the start of the next line -- and a form's fields stretch to 1700dp for a
 * six-character input. With it the content keeps the proportions it was designed at and
 * the window's extra width becomes margin, which is what M3's single-pane canonical
 * layout does.
 *
 * The centring is of the *column*, not of the text inside it. Those are opposite things:
 * a centred column still has a straight leading edge for every row, avatar and icon to
 * align to, which is what the grids-and-spacing page asks for. Centred text has none.
 *
 * Applied at the top of a `Scaffold`'s content, so a screen gets it once:
 *
 *     Scaffold(...) { innerPadding ->
 *         Column(modifier = Modifier.padding(innerPadding).readableContent()) { … }
 *     }
 *
 * A no-op on any window narrower than the measure, which is every phone.
 */
@Composable
fun Modifier.readableContent(charactersPerLine: Int = MaxCharactersPerLine): Modifier =
    this
        // fillMaxWidth first so that the centring has the whole window to centre within;
        // wrapContentWidth then relaxes the minimum so the child may be narrower, and
        // widthIn caps it. Reordering these silently loses either the cap or the centring.
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = readableContentWidth(charactersPerLine))
