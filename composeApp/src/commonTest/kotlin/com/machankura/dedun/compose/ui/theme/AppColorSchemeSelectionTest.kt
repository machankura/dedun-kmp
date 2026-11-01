package com.machankura.dedun.compose.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * The six-way table `appColorScheme` picks from.
 *
 * Worth a test because the failure mode is silent and specific: a scheme wired to the
 * wrong cell still renders a complete, plausible UI. Somebody who turns contrast up and
 * gets the medium scheme back has no way to tell it apart from a high-contrast scheme
 * that is simply not very high, and `Color.kt`'s six schemes are 468 near-identical
 * lines in which a transposed row reads as a typo you would have to be looking for.
 *
 * Until this table existed, four of the six schemes were unreachable -- declared in full
 * and never selected.
 */
class AppColorSchemeSelectionTest {

    @Test
    fun `each darkness and contrast pair selects its own scheme`() {
        assertSame(lightScheme, appColorScheme(darkTheme = false, contrast = ThemeContrast.Standard))
        assertSame(darkScheme, appColorScheme(darkTheme = true, contrast = ThemeContrast.Standard))

        assertSame(
            mediumContrastLightColorScheme,
            appColorScheme(darkTheme = false, contrast = ThemeContrast.Medium),
        )
        assertSame(
            mediumContrastDarkColorScheme,
            appColorScheme(darkTheme = true, contrast = ThemeContrast.Medium),
        )

        assertSame(
            highContrastLightColorScheme,
            appColorScheme(darkTheme = false, contrast = ThemeContrast.High),
        )
        assertSame(
            highContrastDarkColorScheme,
            appColorScheme(darkTheme = true, contrast = ThemeContrast.High),
        )
    }

    @Test
    fun `all six schemes are distinct`() {
        // A copy-paste that left two cells pointing at the same object would satisfy the
        // table test above only if it also mislabelled one, so this catches the other
        // half: six declarations that are not six schemes.
        val schemes = ThemeContrast.entries.flatMap { contrast ->
            listOf(false, true).map { dark -> "$contrast/${if (dark) "dark" else "light"}" to appColorScheme(dark, contrast) }
        }

        schemes.forEachIndexed { i, (nameA, a) ->
            schemes.drop(i + 1).forEach { (nameB, b) ->
                assertNotEquals(a, b, "$nameA and $nameB are the same scheme")
            }
        }
    }

    @Test
    fun `raising contrast never lowers the contrast of body text`() {
        // The one direction that must hold. Individual tonal surfaces legitimately move
        // the other way -- see ColorSchemeContrastTest for why a full monotonicity
        // assertion is wrong -- but onSurface against surface is the pair the setting
        // exists for, and it going backwards would be indefensible.
        listOf(false, true).forEach { dark ->
            val standard = appColorScheme(dark, ThemeContrast.Standard)
            val medium = appColorScheme(dark, ThemeContrast.Medium)
            val high = appColorScheme(dark, ThemeContrast.High)

            val ratios = listOf(standard, medium, high).map { contrastRatio(it.surface, it.onSurface) }
            val theme = if (dark) "dark" else "light"

            assertEquals(
                ratios.sorted(),
                ratios,
                "$theme: onSurface on surface does not rise with contrast — $ratios",
            )
        }
    }
}
