package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reading measure against M3's 40–60 characters per line.
 *
 * The whole point of deriving the cap rather than writing `480.dp` is that it follows the
 * reader's text size, and that is the property no screenshot catches: a hardcoded column
 * still looks correct at 200% text scale, it just holds thirty characters instead of
 * sixty.
 */
class ReadableMeasureTest {

    @Test
    fun `sixty characters of the default body size is 480dp`() {
        // bodyLarge is 16sp in the baseline scale, and 1sp is 1dp at the default text
        // size. Half an em per character times sixty.
        assertEquals(480.dp, readableWidthFor(16.dp))
    }

    @Test
    fun `the measure follows the text size rather than the window`() {
        // A reader at 200% text size gets a column twice as wide, and still sixty
        // characters. This is the assertion a constant cannot pass, and the reason the
        // function takes a font size at all.
        assertEquals(
            readableWidthFor(16.dp) * 2f,
            readableWidthFor(32.dp),
            "doubling the text size did not double the measure",
        )
    }

    @Test
    fun `the cap is the top of M3's range and not the bottom`() {
        // 40 is the floor and 60 the ceiling; a max-width enforces the ceiling. Capping
        // at 40 would be the same mistake in the other direction -- a column too narrow
        // to read comfortably on any window wide enough to matter.
        assertEquals(MaxCharactersPerLine, 60)
        assertTrue(readableWidthFor(16.dp, 40) < readableWidthFor(16.dp, 60))
    }

    @Test
    fun `a phone window holds a line inside the range`() {
        // The check that the floor needs no enforcement. A 400dp compact window less its
        // two 16dp margins is 368dp, which at 8dp a character is 46 -- inside 40 to 60,
        // so the cap is a no-op there and nothing has to widen anything.
        val compactContent = 400.dp - spacingFor(Breakpoint.Compact).screenMargin * 2f
        val charactersPerLine = compactContent / (readableWidthFor(16.dp) / 60f)

        assertTrue(
            charactersPerLine in 40f..60f,
            "a 400dp window fits $charactersPerLine characters, outside M3's 40-60",
        )
        assertTrue(
            compactContent < readableWidthFor(16.dp),
            "the measure is narrower than a phone, so it would crop rather than cap",
        )
    }

    @Test
    fun `a desktop window is held to the measure rather than filled`() {
        // The case this exists for. 1800dp of window, 480dp of text, and the remaining
        // 1320dp becomes margin instead of a line nobody can track back to its start.
        assertTrue(readableWidthFor(16.dp) < 1800.dp)
    }
}
