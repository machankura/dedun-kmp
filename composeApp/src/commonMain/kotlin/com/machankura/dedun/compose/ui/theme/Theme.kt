package com.machankura.dedun.compose.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The six schemes are `internal` rather than `private` so ColorSchemeContrastTest can
// walk the real objects. Testing a copy rebuilt in the test from Color.kt would assert
// the palette and miss the wiring, which is the half that has gone wrong before -- a
// role pointed at the neighbouring value reads fine in isolation.
internal val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
    // Explicit only so that no role is left to a default. This is the value
    // lightColorScheme()/darkColorScheme() would have supplied anyway, and it is
    // right: surfaceColorAtElevation composites surfaceTint over surface at 2-8%
    // alpha, so an elevated light surface darkens toward primary and an elevated
    // dark one lightens toward it, which is M3's own behaviour. Nothing reads it
    // today -- the app sets no elevations anywhere.
    surfaceTint = primaryLight,
    // Theme-independent by definition -- these carry no Light/Dark variant. See the
    // "Fixed colour roles" block in Color.kt for how the tones were derived.
    primaryFixed = primaryFixed,
    primaryFixedDim = primaryFixedDim,
    onPrimaryFixed = onPrimaryFixed,
    onPrimaryFixedVariant = onPrimaryFixedVariant,
    secondaryFixed = secondaryFixed,
    secondaryFixedDim = secondaryFixedDim,
    onSecondaryFixed = onSecondaryFixed,
    onSecondaryFixedVariant = onSecondaryFixedVariant,
    tertiaryFixed = tertiaryFixed,
    tertiaryFixedDim = tertiaryFixedDim,
    onTertiaryFixed = onTertiaryFixed,
    onTertiaryFixedVariant = onTertiaryFixedVariant,
)

internal val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
    // Explicit only so that no role is left to a default. This is the value
    // lightColorScheme()/darkColorScheme() would have supplied anyway, and it is
    // right: surfaceColorAtElevation composites surfaceTint over surface at 2-8%
    // alpha, so an elevated light surface darkens toward primary and an elevated
    // dark one lightens toward it, which is M3's own behaviour. Nothing reads it
    // today -- the app sets no elevations anywhere.
    surfaceTint = primaryDark,
    // Theme-independent by definition -- these carry no Light/Dark variant. See the
    // "Fixed colour roles" block in Color.kt for how the tones were derived.
    primaryFixed = primaryFixed,
    primaryFixedDim = primaryFixedDim,
    onPrimaryFixed = onPrimaryFixed,
    onPrimaryFixedVariant = onPrimaryFixedVariant,
    secondaryFixed = secondaryFixed,
    secondaryFixedDim = secondaryFixedDim,
    onSecondaryFixed = onSecondaryFixed,
    onSecondaryFixedVariant = onSecondaryFixedVariant,
    tertiaryFixed = tertiaryFixed,
    tertiaryFixedDim = tertiaryFixedDim,
    onTertiaryFixed = onTertiaryFixed,
    onTertiaryFixedVariant = onTertiaryFixedVariant,
)

internal val mediumContrastLightColorScheme = lightColorScheme(
    primary = primaryLightMediumContrast,
    onPrimary = onPrimaryLightMediumContrast,
    primaryContainer = primaryContainerLightMediumContrast,
    onPrimaryContainer = onPrimaryContainerLightMediumContrast,
    secondary = secondaryLightMediumContrast,
    onSecondary = onSecondaryLightMediumContrast,
    secondaryContainer = secondaryContainerLightMediumContrast,
    onSecondaryContainer = onSecondaryContainerLightMediumContrast,
    tertiary = tertiaryLightMediumContrast,
    onTertiary = onTertiaryLightMediumContrast,
    tertiaryContainer = tertiaryContainerLightMediumContrast,
    onTertiaryContainer = onTertiaryContainerLightMediumContrast,
    error = errorLightMediumContrast,
    onError = onErrorLightMediumContrast,
    errorContainer = errorContainerLightMediumContrast,
    onErrorContainer = onErrorContainerLightMediumContrast,
    background = backgroundLightMediumContrast,
    onBackground = onBackgroundLightMediumContrast,
    surface = surfaceLightMediumContrast,
    onSurface = onSurfaceLightMediumContrast,
    surfaceVariant = surfaceVariantLightMediumContrast,
    onSurfaceVariant = onSurfaceVariantLightMediumContrast,
    outline = outlineLightMediumContrast,
    outlineVariant = outlineVariantLightMediumContrast,
    scrim = scrimLightMediumContrast,
    inverseSurface = inverseSurfaceLightMediumContrast,
    inverseOnSurface = inverseOnSurfaceLightMediumContrast,
    inversePrimary = inversePrimaryLightMediumContrast,
    surfaceDim = surfaceDimLightMediumContrast,
    surfaceBright = surfaceBrightLightMediumContrast,
    surfaceContainerLowest = surfaceContainerLowestLightMediumContrast,
    surfaceContainerLow = surfaceContainerLowLightMediumContrast,
    surfaceContainer = surfaceContainerLightMediumContrast,
    surfaceContainerHigh = surfaceContainerHighLightMediumContrast,
    surfaceContainerHighest = surfaceContainerHighestLightMediumContrast,
    // Explicit only so that no role is left to a default. This is the value
    // lightColorScheme()/darkColorScheme() would have supplied anyway, and it is
    // right: surfaceColorAtElevation composites surfaceTint over surface at 2-8%
    // alpha, so an elevated light surface darkens toward primary and an elevated
    // dark one lightens toward it, which is M3's own behaviour. Nothing reads it
    // today -- the app sets no elevations anywhere.
    surfaceTint = primaryLightMediumContrast,
    // Theme-independent by definition -- these carry no Light/Dark variant. See the
    // "Fixed colour roles" block in Color.kt for how the tones were derived.
    // Content darkens with the contrast setting; the containers hold.
    primaryFixed = primaryFixed,
    primaryFixedDim = primaryFixedDim,
    onPrimaryFixed = onPrimaryFixedMediumContrast,
    onPrimaryFixedVariant = onPrimaryFixedVariantMediumContrast,
    secondaryFixed = secondaryFixed,
    secondaryFixedDim = secondaryFixedDim,
    onSecondaryFixed = onSecondaryFixedMediumContrast,
    onSecondaryFixedVariant = onSecondaryFixedVariantMediumContrast,
    tertiaryFixed = tertiaryFixed,
    tertiaryFixedDim = tertiaryFixedDim,
    onTertiaryFixed = onTertiaryFixedMediumContrast,
    onTertiaryFixedVariant = onTertiaryFixedVariantMediumContrast,
)

internal val highContrastLightColorScheme = lightColorScheme(
    primary = primaryLightHighContrast,
    onPrimary = onPrimaryLightHighContrast,
    primaryContainer = primaryContainerLightHighContrast,
    onPrimaryContainer = onPrimaryContainerLightHighContrast,
    secondary = secondaryLightHighContrast,
    onSecondary = onSecondaryLightHighContrast,
    secondaryContainer = secondaryContainerLightHighContrast,
    onSecondaryContainer = onSecondaryContainerLightHighContrast,
    tertiary = tertiaryLightHighContrast,
    onTertiary = onTertiaryLightHighContrast,
    tertiaryContainer = tertiaryContainerLightHighContrast,
    onTertiaryContainer = onTertiaryContainerLightHighContrast,
    error = errorLightHighContrast,
    onError = onErrorLightHighContrast,
    errorContainer = errorContainerLightHighContrast,
    onErrorContainer = onErrorContainerLightHighContrast,
    background = backgroundLightHighContrast,
    onBackground = onBackgroundLightHighContrast,
    surface = surfaceLightHighContrast,
    onSurface = onSurfaceLightHighContrast,
    surfaceVariant = surfaceVariantLightHighContrast,
    onSurfaceVariant = onSurfaceVariantLightHighContrast,
    outline = outlineLightHighContrast,
    outlineVariant = outlineVariantLightHighContrast,
    scrim = scrimLightHighContrast,
    inverseSurface = inverseSurfaceLightHighContrast,
    inverseOnSurface = inverseOnSurfaceLightHighContrast,
    inversePrimary = inversePrimaryLightHighContrast,
    surfaceDim = surfaceDimLightHighContrast,
    surfaceBright = surfaceBrightLightHighContrast,
    surfaceContainerLowest = surfaceContainerLowestLightHighContrast,
    surfaceContainerLow = surfaceContainerLowLightHighContrast,
    surfaceContainer = surfaceContainerLightHighContrast,
    surfaceContainerHigh = surfaceContainerHighLightHighContrast,
    surfaceContainerHighest = surfaceContainerHighestLightHighContrast,
    // Explicit only so that no role is left to a default. This is the value
    // lightColorScheme()/darkColorScheme() would have supplied anyway, and it is
    // right: surfaceColorAtElevation composites surfaceTint over surface at 2-8%
    // alpha, so an elevated light surface darkens toward primary and an elevated
    // dark one lightens toward it, which is M3's own behaviour. Nothing reads it
    // today -- the app sets no elevations anywhere.
    surfaceTint = primaryLightHighContrast,
    // Theme-independent by definition -- these carry no Light/Dark variant. See the
    // "Fixed colour roles" block in Color.kt for how the tones were derived.
    // Content darkens with the contrast setting; the containers hold.
    primaryFixed = primaryFixed,
    primaryFixedDim = primaryFixedDim,
    onPrimaryFixed = onPrimaryFixedHighContrast,
    onPrimaryFixedVariant = onPrimaryFixedVariantHighContrast,
    secondaryFixed = secondaryFixed,
    secondaryFixedDim = secondaryFixedDim,
    onSecondaryFixed = onSecondaryFixedHighContrast,
    onSecondaryFixedVariant = onSecondaryFixedVariantHighContrast,
    tertiaryFixed = tertiaryFixed,
    tertiaryFixedDim = tertiaryFixedDim,
    onTertiaryFixed = onTertiaryFixedHighContrast,
    onTertiaryFixedVariant = onTertiaryFixedVariantHighContrast,
)

internal val mediumContrastDarkColorScheme = darkColorScheme(
    primary = primaryDarkMediumContrast,
    onPrimary = onPrimaryDarkMediumContrast,
    primaryContainer = primaryContainerDarkMediumContrast,
    onPrimaryContainer = onPrimaryContainerDarkMediumContrast,
    secondary = secondaryDarkMediumContrast,
    onSecondary = onSecondaryDarkMediumContrast,
    secondaryContainer = secondaryContainerDarkMediumContrast,
    onSecondaryContainer = onSecondaryContainerDarkMediumContrast,
    tertiary = tertiaryDarkMediumContrast,
    onTertiary = onTertiaryDarkMediumContrast,
    tertiaryContainer = tertiaryContainerDarkMediumContrast,
    onTertiaryContainer = onTertiaryContainerDarkMediumContrast,
    error = errorDarkMediumContrast,
    onError = onErrorDarkMediumContrast,
    errorContainer = errorContainerDarkMediumContrast,
    onErrorContainer = onErrorContainerDarkMediumContrast,
    background = backgroundDarkMediumContrast,
    onBackground = onBackgroundDarkMediumContrast,
    surface = surfaceDarkMediumContrast,
    onSurface = onSurfaceDarkMediumContrast,
    surfaceVariant = surfaceVariantDarkMediumContrast,
    onSurfaceVariant = onSurfaceVariantDarkMediumContrast,
    outline = outlineDarkMediumContrast,
    outlineVariant = outlineVariantDarkMediumContrast,
    scrim = scrimDarkMediumContrast,
    inverseSurface = inverseSurfaceDarkMediumContrast,
    inverseOnSurface = inverseOnSurfaceDarkMediumContrast,
    inversePrimary = inversePrimaryDarkMediumContrast,
    surfaceDim = surfaceDimDarkMediumContrast,
    surfaceBright = surfaceBrightDarkMediumContrast,
    surfaceContainerLowest = surfaceContainerLowestDarkMediumContrast,
    surfaceContainerLow = surfaceContainerLowDarkMediumContrast,
    surfaceContainer = surfaceContainerDarkMediumContrast,
    surfaceContainerHigh = surfaceContainerHighDarkMediumContrast,
    surfaceContainerHighest = surfaceContainerHighestDarkMediumContrast,
    // Explicit only so that no role is left to a default. This is the value
    // lightColorScheme()/darkColorScheme() would have supplied anyway, and it is
    // right: surfaceColorAtElevation composites surfaceTint over surface at 2-8%
    // alpha, so an elevated light surface darkens toward primary and an elevated
    // dark one lightens toward it, which is M3's own behaviour. Nothing reads it
    // today -- the app sets no elevations anywhere.
    surfaceTint = primaryDarkMediumContrast,
    // Theme-independent by definition -- these carry no Light/Dark variant. See the
    // "Fixed colour roles" block in Color.kt for how the tones were derived.
    // Content darkens with the contrast setting; the containers hold.
    primaryFixed = primaryFixed,
    primaryFixedDim = primaryFixedDim,
    onPrimaryFixed = onPrimaryFixedMediumContrast,
    onPrimaryFixedVariant = onPrimaryFixedVariantMediumContrast,
    secondaryFixed = secondaryFixed,
    secondaryFixedDim = secondaryFixedDim,
    onSecondaryFixed = onSecondaryFixedMediumContrast,
    onSecondaryFixedVariant = onSecondaryFixedVariantMediumContrast,
    tertiaryFixed = tertiaryFixed,
    tertiaryFixedDim = tertiaryFixedDim,
    onTertiaryFixed = onTertiaryFixedMediumContrast,
    onTertiaryFixedVariant = onTertiaryFixedVariantMediumContrast,
)

internal val highContrastDarkColorScheme = darkColorScheme(
    primary = primaryDarkHighContrast,
    onPrimary = onPrimaryDarkHighContrast,
    primaryContainer = primaryContainerDarkHighContrast,
    onPrimaryContainer = onPrimaryContainerDarkHighContrast,
    secondary = secondaryDarkHighContrast,
    onSecondary = onSecondaryDarkHighContrast,
    secondaryContainer = secondaryContainerDarkHighContrast,
    onSecondaryContainer = onSecondaryContainerDarkHighContrast,
    tertiary = tertiaryDarkHighContrast,
    onTertiary = onTertiaryDarkHighContrast,
    tertiaryContainer = tertiaryContainerDarkHighContrast,
    onTertiaryContainer = onTertiaryContainerDarkHighContrast,
    error = errorDarkHighContrast,
    onError = onErrorDarkHighContrast,
    errorContainer = errorContainerDarkHighContrast,
    onErrorContainer = onErrorContainerDarkHighContrast,
    background = backgroundDarkHighContrast,
    onBackground = onBackgroundDarkHighContrast,
    surface = surfaceDarkHighContrast,
    onSurface = onSurfaceDarkHighContrast,
    surfaceVariant = surfaceVariantDarkHighContrast,
    onSurfaceVariant = onSurfaceVariantDarkHighContrast,
    outline = outlineDarkHighContrast,
    outlineVariant = outlineVariantDarkHighContrast,
    scrim = scrimDarkHighContrast,
    inverseSurface = inverseSurfaceDarkHighContrast,
    inverseOnSurface = inverseOnSurfaceDarkHighContrast,
    inversePrimary = inversePrimaryDarkHighContrast,
    surfaceDim = surfaceDimDarkHighContrast,
    surfaceBright = surfaceBrightDarkHighContrast,
    surfaceContainerLowest = surfaceContainerLowestDarkHighContrast,
    surfaceContainerLow = surfaceContainerLowDarkHighContrast,
    surfaceContainer = surfaceContainerDarkHighContrast,
    surfaceContainerHigh = surfaceContainerHighDarkHighContrast,
    surfaceContainerHighest = surfaceContainerHighestDarkHighContrast,
    // Explicit only so that no role is left to a default. This is the value
    // lightColorScheme()/darkColorScheme() would have supplied anyway, and it is
    // right: surfaceColorAtElevation composites surfaceTint over surface at 2-8%
    // alpha, so an elevated light surface darkens toward primary and an elevated
    // dark one lightens toward it, which is M3's own behaviour. Nothing reads it
    // today -- the app sets no elevations anywhere.
    surfaceTint = primaryDarkHighContrast,
    // Theme-independent by definition -- these carry no Light/Dark variant. See the
    // "Fixed colour roles" block in Color.kt for how the tones were derived.
    // Content darkens with the contrast setting; the containers hold.
    primaryFixed = primaryFixed,
    primaryFixedDim = primaryFixedDim,
    onPrimaryFixed = onPrimaryFixedHighContrast,
    onPrimaryFixedVariant = onPrimaryFixedVariantHighContrast,
    secondaryFixed = secondaryFixed,
    secondaryFixedDim = secondaryFixedDim,
    onSecondaryFixed = onSecondaryFixedHighContrast,
    onSecondaryFixedVariant = onSecondaryFixedVariantHighContrast,
    tertiaryFixed = tertiaryFixed,
    tertiaryFixedDim = tertiaryFixedDim,
    onTertiaryFixed = onTertiaryFixedHighContrast,
    onTertiaryFixedVariant = onTertiaryFixedVariantHighContrast,
)

@Immutable
data class ColorFamily(
    val color: Color,
    val onColor: Color,
    val colorContainer: Color,
    val onColorContainer: Color
)

val unspecified_scheme = ColorFamily(
    Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified
)

/**
 * Brand colours that are not part of the generated scheme.
 *
 * `ColorScheme` has no slot for them -- M3 calls them extended colours and expects them to
 * ride alongside -- so they travel on their own composition local, provided by [DedunTheme]
 * from the same `darkTheme` the scheme is chosen with. Reading `isSystemInDarkTheme()` at
 * the call site instead would be subtly wrong: it would ignore a caller who passed
 * `darkTheme` explicitly, and a preview forcing dark would show light pills.
 */
@Immutable
data class ExtendedColors(
    val bluePill: ColorFamily,
    val redPill: ColorFamily,
)

/**
 * The extended colours for the current theme.
 *
 * Defaults to the light families rather than to `unspecified_scheme` so that anything
 * composing outside [DedunTheme] renders a real colour instead of nothing. That should not
 * happen -- as of this commit nothing in the tree does -- but an invisible button is a worse
 * way to find out than a light-themed one.
 */
val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(bluePill = bluePillLight, redPill = redPillLight)
}

/** `MaterialTheme.extendedColors.redPill`, matching the [spacing] accessor. */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

internal fun extendedColorsFor(darkTheme: Boolean): ExtendedColors =
    if (darkTheme) {
        ExtendedColors(bluePill = bluePillDark, redPill = redPillDark)
    } else {
        ExtendedColors(bluePill = bluePillLight, redPill = redPillLight)
    }

/**
 * How much contrast the person using the device has asked for.
 *
 * Not a preference this app invents -- every platform that has the setting owns it, and
 * `platformThemeContrast` reports it. M3's accessibility foundation puts *honour
 * individuals* first: "supporting varying preferences and choices that allow individuals
 * to address how their changing conditions, individual knowledge, and varying needs are
 * met." The four schemes to answer with were already written out in Color.kt and were,
 * until this type existed, unreachable.
 */
enum class ThemeContrast {
    /** The app's default schemes. */
    Standard,

    /** Android's 0.5 contrast step. No iOS or desktop equivalent, so never reported there. */
    Medium,

    /** Android's 1.0 step; iOS "Increase Contrast"; Windows high contrast mode. */
    High,
}

/**
 * The scheme to use, before dynamic colour gets a say.
 *
 * Kept in common code rather than behind the platform boundary so that all six schemes
 * are selected from one table. The platform actuals answer two narrow questions instead
 * -- what contrast was asked for, and whether there is a wallpaper palette to prefer --
 * which is the part that genuinely differs.
 */
internal fun appColorScheme(darkTheme: Boolean, contrast: ThemeContrast): ColorScheme =
    when (contrast) {
        ThemeContrast.Standard -> if (darkTheme) darkScheme else lightScheme
        ThemeContrast.Medium ->
            if (darkTheme) mediumContrastDarkColorScheme else mediumContrastLightColorScheme
        ThemeContrast.High ->
            if (darkTheme) highContrastDarkColorScheme else highContrastLightColorScheme
    }

/**
 * The app's theme: colour, shape, type and motion, in one place.
 *
 * **Expressive, deliberately.** `MaterialExpressiveTheme` rather than `MaterialTheme` is a
 * product decision taken 2026-09-08. The pinned material3 1.10.0-alpha05 ships the whole
 * expressive set -- button groups, split buttons, floating toolbars, wide navigation rails,
 * loading indicators, the eight-step shape scale, thirty type roles -- and the tree already
 * opts into `ExperimentalMaterial3ExpressiveApi` in 66 places. The visible effect is that
 * components take their expressive defaults: rounder, larger, more motion. It is one
 * function name to change back.
 *
 * All four slots are passed explicitly. `MaterialExpressiveTheme` would otherwise supply
 * `expressiveLightColorScheme()` and friends, which are Material's palette rather than this
 * app's -- the same class of accident as the twelve unassigned fixed roles.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DedunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    contrast: ThemeContrast = platformThemeContrast(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // A parameter with a platform default, the same shape as `contrast` and for the same
    // reason: the platform's answer is the right starting point and the wrong final word.
    // A desktop cannot see what its desktop environment was told, and a settings screen
    // will eventually want to override this on every platform.
    reducedMotion: Boolean = platformReducedMotion(),
    content: @Composable () -> Unit
) {
    // Dynamic colour wins when the platform offers it, because it is the user's own
    // choice and already carries their contrast setting -- on Android 14+ the
    // `system_*` palette resources shift with it, so `dynamicLightColorScheme` needs
    // no help from `contrast`. Everywhere else the app's schemes answer, and that is
    // where `contrast` decides which of the six.
    //
    // Note what this means in practice: on Android 12+ the six schemes above are not
    // used at all, and the app takes the wallpaper palette. That is the intended
    // behaviour -- see "What this plan does not cover" in
    // docs/material-design-conformance.md -- but it is why a change to Color.kt shows
    // up on desktop and ios and not on a modern phone.
    val colorScheme = dynamicColorScheme(darkTheme, dynamicColor)
        ?: appColorScheme(darkTheme, contrast)

    // Classified once, here, so that every screen below reads the same answer. Doing it
    // per screen would let two of them disagree about the window they are both in, which
    // is the failure mode of `BoxWithConstraints`-per-screen adaptivity.
    val breakpoint = currentBreakpoint()

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColorsFor(darkTheme),
        LocalBreakpoint provides breakpoint,
        // Provided once here rather than read per animation, so a screen cannot animate
        // against a setting a neighbouring screen is honouring.
        LocalReducedMotion provides reducedMotion,
        // The payoff for tokenising spacing in phase 2: the screen margin widens from
        // 16dp to 24dp at medium and above without a single call site changing.
        LocalSpacing provides spacingFor(breakpoint),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            // Every animation in the app should come from here rather than from a literal
            // `tween`, so that the whole app's feel is one decision. Nothing reads it yet;
            // the motion phase is what puts it to work.
            motionScheme = MotionScheme.expressive(),
            shapes = DedunShapes,
            typography = AuxTypography,
            content = content
        )
    }
}

/**
 * The platform's contrast setting, recomposing when it changes.
 *
 * Reading it once at startup would be a worse version of honouring it: someone who turns
 * contrast up because they cannot read the screen in front of them should not have to
 * find out that the app needs restarting.
 */
@Composable
expect fun platformThemeContrast(): ThemeContrast

/**
 * A wallpaper-derived palette, or `null` where the platform has none.
 *
 * Returns `null` rather than falling back internally so that the choice of app scheme --
 * which now depends on contrast as well as darkness -- stays in one place.
 */
@Composable
expect fun dynamicColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme?
