package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every colour pair the six declared schemes promise, measured.
 *
 * M3's accessibility foundation gives two thresholds: small text needs 4.5:1 against
 * its background, and large text or a meaningful non-text boundary needs 3:1. Disabled
 * states are exempt. See docs/material-design-conformance.md, "The numbers".
 *
 * This passes as written -- the generated palette is sound, and the tightest pair in
 * the tree is `onPrimaryContainer` on `primaryContainer` at 4.61:1 light. It exists so
 * that a later edit to Color.kt or to the wiring in Theme.kt cannot quietly break one,
 * because a broken pair is invisible in review: the two hex values look unrelated, and
 * the failure only appears on a device, in one theme, to someone who cannot read it.
 *
 * It walks the real `ColorScheme` objects rather than rebuilding them from Color.kt, so
 * it also covers the wiring. A role pointed at its neighbour's value -- `surfaceContainerHigh
 * = surfaceContainerHighestLight` -- is a plausible slip that reads fine in isolation.
 *
 * What is deliberately *not* asserted here:
 *
 *  - **Monotonicity across the contrast ladder.** The obvious invariant, that
 *    high-contrast beats medium beats default for every pair, is false and correctly
 *    so. In the light high-contrast scheme `surfaceContainerHighest` goes darker to
 *    separate it from `surface`, which lowers its ratio against `onSurface` (13.30 ->
 *    12.29) while raising the one that matters. Ten pairs move that way. The floor is
 *    the invariant; the ladder is not.
 *  - **`outlineVariant`.** It reads 1.61:1 against surface, which looks alarming and is
 *    not a defect: M3's own baseline sits in the same range, and outlineVariant is a
 *    decorative divider. `outline`, the meaningful-boundary role, is asserted at 3:1.
 *  - **Call sites that pair two roles the scheme already covers.** Once
 *    `ProposalListScreen` derives its `ListItem` colours from its `Card`, the pairing it
 *    produces is `onPrimaryContainer` on `primaryContainer`, which the first assertion
 *    already walks. Restating it here would double the maintenance and catch nothing.
 *    What *is* asserted per call site is the composited case, below, because a
 *    translucent container has no ratio until it is put over something.
 */
class ColorSchemeContrastTest {

    private val schemes: List<Pair<String, ColorScheme>> = listOf(
        "light" to lightScheme,
        "dark" to darkScheme,
        "light medium-contrast" to mediumContrastLightColorScheme,
        "dark medium-contrast" to mediumContrastDarkColorScheme,
        "light high-contrast" to highContrastLightColorScheme,
        "dark high-contrast" to highContrastDarkColorScheme,
    )

    /**
     * Container role paired with the content role M3 assigns to it. Text drawn on the
     * first is drawn in the second, so each of these is a small-text pairing.
     */
    private val textPairs: List<Triple<String, (ColorScheme) -> Color, (ColorScheme) -> Color>> =
        listOf(
            Triple("onPrimary on primary", { s: ColorScheme -> s.primary }, { s: ColorScheme -> s.onPrimary }),
            Triple("onPrimaryContainer on primaryContainer", { s: ColorScheme -> s.primaryContainer }, { s: ColorScheme -> s.onPrimaryContainer }),
            Triple("onSecondary on secondary", { s: ColorScheme -> s.secondary }, { s: ColorScheme -> s.onSecondary }),
            Triple("onSecondaryContainer on secondaryContainer", { s: ColorScheme -> s.secondaryContainer }, { s: ColorScheme -> s.onSecondaryContainer }),
            Triple("onTertiary on tertiary", { s: ColorScheme -> s.tertiary }, { s: ColorScheme -> s.onTertiary }),
            Triple("onTertiaryContainer on tertiaryContainer", { s: ColorScheme -> s.tertiaryContainer }, { s: ColorScheme -> s.onTertiaryContainer }),
            Triple("onError on error", { s: ColorScheme -> s.error }, { s: ColorScheme -> s.onError }),
            Triple("onErrorContainer on errorContainer", { s: ColorScheme -> s.errorContainer }, { s: ColorScheme -> s.onErrorContainer }),
            Triple("onBackground on background", { s: ColorScheme -> s.background }, { s: ColorScheme -> s.onBackground }),
            Triple("onSurface on surface", { s: ColorScheme -> s.surface }, { s: ColorScheme -> s.onSurface }),
            Triple("onSurfaceVariant on surfaceVariant", { s: ColorScheme -> s.surfaceVariant }, { s: ColorScheme -> s.onSurfaceVariant }),
            Triple("inverseOnSurface on inverseSurface", { s: ColorScheme -> s.inverseSurface }, { s: ColorScheme -> s.inverseOnSurface }),
        )

    /**
     * The tonal surfaces. All eight carry `onSurface` content -- there is no
     * `onSurfaceContainer` role -- so every one of them is a text background, and a
     * scheme that darkens one of them without checking is how this breaks.
     */
    private val tonalSurfaces: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "surfaceDim" to { s: ColorScheme -> s.surfaceDim },
        "surfaceBright" to { s: ColorScheme -> s.surfaceBright },
        "surfaceContainerLowest" to { s: ColorScheme -> s.surfaceContainerLowest },
        "surfaceContainerLow" to { s: ColorScheme -> s.surfaceContainerLow },
        "surfaceContainer" to { s: ColorScheme -> s.surfaceContainer },
        "surfaceContainerHigh" to { s: ColorScheme -> s.surfaceContainerHigh },
        "surfaceContainerHighest" to { s: ColorScheme -> s.surfaceContainerHighest },
    )

    @Test
    fun `every content role reads at 4_5 to 1 on its container`() {
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            textPairs.forEach { (pairName, container, content) ->
                val ratio = contrastRatio(container(scheme), content(scheme))
                if (ratio < SMALL_TEXT_MINIMUM) {
                    failures += "$schemeName: $pairName is ${ratio.format()}:1"
                }
            }
        }

        assertTrue(failures.isEmpty(), "below 4.5:1 —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `onSurface reads at 4_5 to 1 on every tonal surface`() {
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            tonalSurfaces.forEach { (surfaceName, surface) ->
                val ratio = contrastRatio(surface(scheme), scheme.onSurface)
                if (ratio < SMALL_TEXT_MINIMUM) {
                    failures += "$schemeName: onSurface on $surfaceName is ${ratio.format()}:1"
                }
            }
        }

        assertTrue(failures.isEmpty(), "below 4.5:1 —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `both content roles read on both fixed containers at 4_5 to 1`() {
        // `onXFixedVariant` is the lower-emphasis of the two and `xFixedDim` the darker
        // container, so onVariant-on-Dim is the corner that decides the family. It is
        // 5.44:1 in the default schemes -- real headroom, but not much, and a tone
        // chosen by eye rather than computed would land under it.
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            listOf(
                "primary" to Quad(scheme.primaryFixed, scheme.primaryFixedDim, scheme.onPrimaryFixed, scheme.onPrimaryFixedVariant),
                "secondary" to Quad(scheme.secondaryFixed, scheme.secondaryFixedDim, scheme.onSecondaryFixed, scheme.onSecondaryFixedVariant),
                "tertiary" to Quad(scheme.tertiaryFixed, scheme.tertiaryFixedDim, scheme.onTertiaryFixed, scheme.onTertiaryFixedVariant),
            ).forEach { (family, roles) ->
                listOf(
                    "on${family}Fixed on ${family}Fixed" to contrastRatio(roles.fixed, roles.on),
                    "on${family}FixedVariant on ${family}Fixed" to contrastRatio(roles.fixed, roles.onVariant),
                    "on${family}Fixed on ${family}FixedDim" to contrastRatio(roles.dim, roles.on),
                    "on${family}FixedVariant on ${family}FixedDim" to contrastRatio(roles.dim, roles.onVariant),
                ).forEach { (pairName, ratio) ->
                    if (ratio < SMALL_TEXT_MINIMUM) {
                        failures += "$schemeName: $pairName is ${ratio.format()}:1"
                    }
                }
            }
        }

        assertTrue(failures.isEmpty(), "below 4.5:1 —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the fixed roles are the same colour in light and dark`() {
        // What "fixed" means. A fixed container that shifted with the theme would be an
        // ordinary container with a misleading name, and the bug would only show on a
        // screen that puts one beside a themed surface.
        val failures = mutableListOf<String>()

        listOf(
            "default" to (lightScheme to darkScheme),
            "medium-contrast" to (mediumContrastLightColorScheme to mediumContrastDarkColorScheme),
            "high-contrast" to (highContrastLightColorScheme to highContrastDarkColorScheme),
        ).forEach { (level, pair) ->
            val (light, dark) = pair
            fixedRoles.forEach { (roleName, role) ->
                if (role(light) != role(dark)) {
                    failures += "$level: $roleName is ${role(light)} light, ${role(dark)} dark"
                }
            }
        }

        assertTrue(failures.isEmpty(), "fixed roles differ across themes —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `no role is left at the Material baseline palette`() {
        // A role omitted from lightColorScheme() takes its baseline default, which for
        // the twelve *Fixed* roles is PaletteTokens.Primary90 and friends -- lavender,
        // in an app whose primary is pure black. Nothing in the tree reads a fixed role
        // yet, so this cannot be caught by looking at the app; it has to be asserted.
        val baselineFixed = setOf(
            Color(0xFFEADDFF), Color(0xFFD0BCFF), Color(0xFF21005D), Color(0xFF4F378B), // primary
            Color(0xFFE8DEF8), Color(0xFFCCC2DC), Color(0xFF1D192B), Color(0xFF4A4458), // secondary
            Color(0xFFFFD8E4), Color(0xFFEFB8C8), Color(0xFF31111D), Color(0xFF633B48), // tertiary
        )
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            fixedRoles.forEach { (roleName, role) ->
                if (role(scheme) in baselineFixed) {
                    failures += "$schemeName: $roleName is still the baseline ${role(scheme)}"
                }
            }
        }

        assertTrue(failures.isEmpty(), "unassigned roles —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the extended brand families read at 4_5 to 1`() {
        // The two pills on CreateProfileScreen. They are not part of any ColorScheme, so
        // nothing else in this file reaches them, and their previous incarnation is
        // exactly why they need asserting: `BluePill` paired with `Color.DarkGray` by eye
        // was 2.90:1, and `RedPill` carried alpha 0.749 so its white label was 3.50:1 once
        // composited. Both looked fine to whoever wrote them.
        val failures = mutableListOf<String>()

        listOf(
            "bluePill light" to bluePillLight,
            "bluePill dark" to bluePillDark,
            "redPill light" to redPillLight,
            "redPill dark" to redPillDark,
        ).forEach { (name, family) ->
            listOf(
                "onColor on color" to contrastRatio(family.color, family.onColor),
                "onColorContainer on colorContainer" to
                    contrastRatio(family.colorContainer, family.onColorContainer),
            ).forEach { (pairName, ratio) ->
                if (ratio < SMALL_TEXT_MINIMUM) {
                    failures += "$name: $pairName is ${ratio.format()}:1"
                }
            }
        }

        assertTrue(failures.isEmpty(), "below 4.5:1 —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the extended brand families are opaque`() {
        // `RedPill` was `Color(230, 32, 32, 191)` -- the four-Int constructor, whose last
        // argument is alpha. A translucent container has no contrast ratio of its own; it
        // has one only once composited, and the test above would have measured a colour
        // the user never sees. Roles are opaque.
        val translucent = listOf(
            "bluePillLight" to bluePillLight,
            "bluePillDark" to bluePillDark,
            "redPillLight" to redPillLight,
            "redPillDark" to redPillDark,
        ).flatMap { (name, family) ->
            listOf(
                "$name.color" to family.color,
                "$name.onColor" to family.onColor,
                "$name.colorContainer" to family.colorContainer,
                "$name.onColorContainer" to family.onColorContainer,
            )
        }.filter { (_, color) -> color.alpha != 1f }

        assertTrue(
            translucent.isEmpty(),
            "translucent roles —\n" + translucent.joinToString("\n") { "${it.first} alpha ${it.second.alpha}" },
        )
    }

    @Test
    fun `translucent containers still carry their content at 4_5 to 1 once composited`() {
        // Call sites that tint a container with `.copy(alpha = …)`. A translucent colour
        // has no ratio of its own, so the only way to check one is to composite it over
        // what is actually behind it and measure that -- which is why these could not be
        // caught by the role-pair assertions above, and why one of them was missed for
        // as long as it was.
        //
        // Each row names the call site so a failure says where to go. `surface` is the
        // backdrop in every case; a screen that puts one of these on a tonal surface
        // instead would need its own row.
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            listOf(
                Triple(
                    "ChatRoomMessagingScreen: private-message field",
                    scheme.primaryContainer.copy(alpha = 0.3f),
                    scheme.onSurface,
                ),
                Triple(
                    "UnsupportedKindBadge / LinkPreview: tinted badge",
                    scheme.surfaceVariant.copy(alpha = 0.3f),
                    scheme.onSurfaceVariant,
                ),
            ).forEach { (site, container, content) ->
                val ratio = contrastRatio(container.compositeOver(scheme.surface), content)
                if (ratio < SMALL_TEXT_MINIMUM) {
                    failures += "$schemeName: $site is ${ratio.format()}:1"
                }
            }
        }

        assertTrue(failures.isEmpty(), "below 4.5:1 once composited —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `outline separates from every surface it is drawn on at 3 to 1`() {
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            (listOf("surface" to { s: ColorScheme -> s.surface }) + tonalSurfaces)
                .forEach { (surfaceName, surface) ->
                    val ratio = contrastRatio(surface(scheme), scheme.outline)
                    if (ratio < NON_TEXT_MINIMUM) {
                        failures += "$schemeName: outline on $surfaceName is ${ratio.format()}:1"
                    }
                }
        }

        assertTrue(failures.isEmpty(), "below 3:1 —\n" + failures.joinToString("\n"))
    }

    @Test
    fun `a filled container stands off the surface behind it at 3 to 1`() {
        // M3 asks for 3:1 between a clustered interactive container and its background.
        // A standalone element such as a FAB is exempt by prominence, but `primary` and
        // `error` are both used for buttons that sit beside other buttons.
        val failures = mutableListOf<String>()

        schemes.forEach { (schemeName, scheme) ->
            listOf(
                "primary" to scheme.primary,
                "error" to scheme.error,
            ).forEach { (roleName, role) ->
                val ratio = contrastRatio(scheme.surface, role)
                if (ratio < NON_TEXT_MINIMUM) {
                    failures += "$schemeName: $roleName on surface is ${ratio.format()}:1"
                }
            }
        }

        assertTrue(failures.isEmpty(), "below 3:1 —\n" + failures.joinToString("\n"))
    }

    /** The twelve theme-independent roles, by name, for the two tests that walk them all. */
    private val fixedRoles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "primaryFixed" to { s: ColorScheme -> s.primaryFixed },
        "primaryFixedDim" to { s: ColorScheme -> s.primaryFixedDim },
        "onPrimaryFixed" to { s: ColorScheme -> s.onPrimaryFixed },
        "onPrimaryFixedVariant" to { s: ColorScheme -> s.onPrimaryFixedVariant },
        "secondaryFixed" to { s: ColorScheme -> s.secondaryFixed },
        "secondaryFixedDim" to { s: ColorScheme -> s.secondaryFixedDim },
        "onSecondaryFixed" to { s: ColorScheme -> s.onSecondaryFixed },
        "onSecondaryFixedVariant" to { s: ColorScheme -> s.onSecondaryFixedVariant },
        "tertiaryFixed" to { s: ColorScheme -> s.tertiaryFixed },
        "tertiaryFixedDim" to { s: ColorScheme -> s.tertiaryFixedDim },
        "onTertiaryFixed" to { s: ColorScheme -> s.onTertiaryFixed },
        "onTertiaryFixedVariant" to { s: ColorScheme -> s.onTertiaryFixedVariant },
    )

    /** One fixed family: the two containers and the two content roles that sit on them. */
    private data class Quad(val fixed: Color, val dim: Color, val on: Color, val onVariant: Color)

    private companion object {
        /** WCAG 2.x, small text. */
        const val SMALL_TEXT_MINIMUM = 4.5

        /** WCAG 2.x, large text and meaningful non-text elements. */
        const val NON_TEXT_MINIMUM = 3.0
    }
}

/**
 * WCAG relative luminance of one sRGB channel.
 *
 * The 0.03928 knee and the 2.4 exponent are the specification's, not an approximation
 * of gamma 2.2 -- swapping in the simpler curve moves borderline pairs by enough to
 * change a verdict, which is the whole point of this file.
 */
private fun channelLuminance(component: Float): Double {
    val c = component.toDouble()
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun Color.relativeLuminance(): Double =
    0.2126 * channelLuminance(red) +
        0.7152 * channelLuminance(green) +
        0.0722 * channelLuminance(blue)

/**
 * Contrast ratio between two opaque colours, 1.0 to 21.0.
 *
 * Both arguments must be opaque. A translucent colour has no ratio of its own -- it has
 * one only once composited over something -- so composite it first and pass the result.
 * Phase 3 needs that for the `.copy(alpha = 0.5f)` call sites; the schemes here are all
 * fully opaque.
 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Two decimal places, without pulling in a platform formatter. */
private fun Double.format(): String {
    val scaled = (this * 100).toInt()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
