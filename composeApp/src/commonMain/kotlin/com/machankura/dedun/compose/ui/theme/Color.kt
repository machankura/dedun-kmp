package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Extended colour roles: the two pills
// ---------------------------------------------------------------------------
//
// The choice on CreateProfileScreen -- commit to the account, or wipe and leave -- is
// the Matrix pill choice, and the two colours carry that meaning rather than a place in
// the scheme. M3 calls this an extended colour: a brand colour promoted to a full role
// family (`color` / `onColor` / `colorContainer` / `onColorContainer`) so that contrast
// is a property of the family rather than something chosen per call site.
//
// They were `val BluePill = Color(0xFF5D8DD6)` and `val RedPill = Color(230, 32, 32, 191)`,
// paired at the call site with `Color.DarkGray` and `Color.White`. Both pairings were
// below the 4.5:1 floor -- 2.90:1 and, because RedPill carried alpha 0.749 and composited
// over the surface, 3.50:1 -- so this step could not leave the pixels alone. Any correct
// version of these two buttons is a visible change.
//
// **Derivation**, the same rule as the gold palette in the fixed-roles block: maximum
// in-gamut chroma at the source colour's Lab hue, sampled at M3's role tones. BluePill's
// hue is 277.0 and RedPill's is 36.3.
//
//     role              light          dark
//     color             tone 40        tone 80
//     onColor           tone 100       tone 20
//     colorContainer    tone 90        tone 30
//     onColorContainer  tone 10        tone 90
//
// A side effect worth having: at tone 40 the two pills are the same lightness, so they
// read as a matched pair. Before, a saturated red sat beside a soft periwinkle and the
// blue looked like the lesser option -- which is not what the screen is asking.
//
// No medium- or high-contrast variants. The whole surface is two buttons on one screen,
// and the light family's weakest pair is 6.44:1 -- clear of the 4.5:1 floor by more than
// the contrast schemes would need to add. Generating 32 more values for that would be
// out of proportion to what they cover.

val bluePillLight = ColorFamily(
    color = Color(0xFF0060AB),           // tone 40
    onColor = Color(0xFFFFFFFF),         // tone 100
    colorContainer = Color(0xFFD7E2FF),  // tone 90
    onColorContainer = Color(0xFF001C39) // tone 10
)

val bluePillDark = ColorFamily(
    color = Color(0xFFACC7FF),           // tone 80
    onColor = Color(0xFF00315C),         // tone 20
    colorContainer = Color(0xFF004882),  // tone 30
    onColorContainer = Color(0xFFD7E2FF) // tone 90
)

val redPillLight = ColorFamily(
    color = Color(0xFFC00012),
    onColor = Color(0xFFFFFFFF),
    colorContainer = Color(0xFFFFDAD3),
    onColorContainer = Color(0xFF390C00)
)

val redPillDark = ColorFamily(
    color = Color(0xFFFFB4A5),
    onColor = Color(0xFF690000),
    colorContainer = Color(0xFF93000B),
    onColorContainer = Color(0xFFFFDAD3)
)

val primaryLight = Color(0xFF000000)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFF1B1B1B)
val onPrimaryContainerLight = Color(0xFF848484)
val secondaryLight = Color(0xFF745B00)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFEFBF04)
val onSecondaryContainerLight = Color(0xFF644F00)
val tertiaryLight = Color(0xFF000000)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFF1B1B1B)
val onTertiaryContainerLight = Color(0xFF848484)
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF93000A)
val backgroundLight = Color(0xFFF9F9F9)
val onBackgroundLight = Color(0xFF1B1B1B)
val surfaceLight = Color(0xFFF9F9F9)
val onSurfaceLight = Color(0xFF1B1B1B)
val surfaceVariantLight = Color(0xFFEBE0E1)
val onSurfaceVariantLight = Color(0xFF4C4546)
val outlineLight = Color(0xFF7E7576)
val outlineVariantLight = Color(0xFFCFC4C5)
val scrimLight = Color(0xFF000000)
val inverseSurfaceLight = Color(0xFF303030)
val inverseOnSurfaceLight = Color(0xFFF1F1F1)
val inversePrimaryLight = Color(0xFFC6C6C6)
val surfaceDimLight = Color(0xFFDADADA)
val surfaceBrightLight = Color(0xFFF9F9F9)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFF3F3F3)
val surfaceContainerLight = Color(0xFFEEEEEE)
val surfaceContainerHighLight = Color(0xFFE8E8E8)
val surfaceContainerHighestLight = Color(0xFFE2E2E2)

val primaryLightMediumContrast = Color(0xFF000000)
val onPrimaryLightMediumContrast = Color(0xFFFFFFFF)
val primaryContainerLightMediumContrast = Color(0xFF1B1B1B)
val onPrimaryContainerLightMediumContrast = Color(0xFFA7A7A7)
val secondaryLightMediumContrast = Color(0xFF443400)
val onSecondaryLightMediumContrast = Color(0xFFFFFFFF)
val secondaryContainerLightMediumContrast = Color(0xFF866A00)
val onSecondaryContainerLightMediumContrast = Color(0xFFFFFFFF)
val tertiaryLightMediumContrast = Color(0xFF000000)
val onTertiaryLightMediumContrast = Color(0xFFFFFFFF)
val tertiaryContainerLightMediumContrast = Color(0xFF1B1B1B)
val onTertiaryContainerLightMediumContrast = Color(0xFFA7A7A7)
val errorLightMediumContrast = Color(0xFF740006)
val onErrorLightMediumContrast = Color(0xFFFFFFFF)
val errorContainerLightMediumContrast = Color(0xFFCF2C27)
val onErrorContainerLightMediumContrast = Color(0xFFFFFFFF)
val backgroundLightMediumContrast = Color(0xFFF9F9F9)
val onBackgroundLightMediumContrast = Color(0xFF1B1B1B)
val surfaceLightMediumContrast = Color(0xFFF9F9F9)
val onSurfaceLightMediumContrast = Color(0xFF111111)
val surfaceVariantLightMediumContrast = Color(0xFFEBE0E1)
val onSurfaceVariantLightMediumContrast = Color(0xFF3B3436)
val outlineLightMediumContrast = Color(0xFF585152)
val outlineVariantLightMediumContrast = Color(0xFF736B6C)
val scrimLightMediumContrast = Color(0xFF000000)
val inverseSurfaceLightMediumContrast = Color(0xFF303030)
val inverseOnSurfaceLightMediumContrast = Color(0xFFF1F1F1)
val inversePrimaryLightMediumContrast = Color(0xFFC6C6C6)
val surfaceDimLightMediumContrast = Color(0xFFC6C6C6)
val surfaceBrightLightMediumContrast = Color(0xFFF9F9F9)
val surfaceContainerLowestLightMediumContrast = Color(0xFFFFFFFF)
val surfaceContainerLowLightMediumContrast = Color(0xFFF3F3F3)
val surfaceContainerLightMediumContrast = Color(0xFFE8E8E8)
val surfaceContainerHighLightMediumContrast = Color(0xFFDDDDDD)
val surfaceContainerHighestLightMediumContrast = Color(0xFFD1D1D1)

val primaryLightHighContrast = Color(0xFF000000)
val onPrimaryLightHighContrast = Color(0xFFFFFFFF)
val primaryContainerLightHighContrast = Color(0xFF1B1B1B)
val onPrimaryContainerLightHighContrast = Color(0xFFD0D0D0)
val secondaryLightHighContrast = Color(0xFF382A00)
val onSecondaryLightHighContrast = Color(0xFFFFFFFF)
val secondaryContainerLightHighContrast = Color(0xFF5A4700)
val onSecondaryContainerLightHighContrast = Color(0xFFFFFFFF)
val tertiaryLightHighContrast = Color(0xFF000000)
val onTertiaryLightHighContrast = Color(0xFFFFFFFF)
val tertiaryContainerLightHighContrast = Color(0xFF1B1B1B)
val onTertiaryContainerLightHighContrast = Color(0xFFD0D0D0)
val errorLightHighContrast = Color(0xFF600004)
val onErrorLightHighContrast = Color(0xFFFFFFFF)
val errorContainerLightHighContrast = Color(0xFF98000A)
val onErrorContainerLightHighContrast = Color(0xFFFFFFFF)
val backgroundLightHighContrast = Color(0xFFF9F9F9)
val onBackgroundLightHighContrast = Color(0xFF1B1B1B)
val surfaceLightHighContrast = Color(0xFFF9F9F9)
val onSurfaceLightHighContrast = Color(0xFF000000)
val surfaceVariantLightHighContrast = Color(0xFFEBE0E1)
val onSurfaceVariantLightHighContrast = Color(0xFF000000)
val outlineLightHighContrast = Color(0xFF312B2C)
val outlineVariantLightHighContrast = Color(0xFF4F4749)
val scrimLightHighContrast = Color(0xFF000000)
val inverseSurfaceLightHighContrast = Color(0xFF303030)
val inverseOnSurfaceLightHighContrast = Color(0xFFFFFFFF)
val inversePrimaryLightHighContrast = Color(0xFFC6C6C6)
val surfaceDimLightHighContrast = Color(0xFFB9B9B9)
val surfaceBrightLightHighContrast = Color(0xFFF9F9F9)
val surfaceContainerLowestLightHighContrast = Color(0xFFFFFFFF)
val surfaceContainerLowLightHighContrast = Color(0xFFF1F1F1)
val surfaceContainerLightHighContrast = Color(0xFFE2E2E2)
val surfaceContainerHighLightHighContrast = Color(0xFFD4D4D4)
val surfaceContainerHighestLightHighContrast = Color(0xFFC6C6C6)

val primaryDark = Color(0xFFC6C6C6)
val onPrimaryDark = Color(0xFF303030)
val primaryContainerDark = Color(0xFF000000)
val onPrimaryContainerDark = Color(0xFF757575)
val secondaryDark = Color(0xFFFFDE82)
val onSecondaryDark = Color(0xFF3D2F00)
val secondaryContainerDark = Color(0xFFEFBF04)
val onSecondaryContainerDark = Color(0xFF644F00)
val tertiaryDark = Color(0xFFC6C6C6)
val onTertiaryDark = Color(0xFF303030)
val tertiaryContainerDark = Color(0xFF000000)
val onTertiaryContainerDark = Color(0xFF757575)
val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)
val backgroundDark = Color(0xFF131313)
val onBackgroundDark = Color(0xFFE2E2E2)
val surfaceDark = Color(0xFF131313)
val onSurfaceDark = Color(0xFFE2E2E2)
val surfaceVariantDark = Color(0xFF4C4546)
val onSurfaceVariantDark = Color(0xFFCFC4C5)
val outlineDark = Color(0xFF988E90)
val outlineVariantDark = Color(0xFF4C4546)
val scrimDark = Color(0xFF000000)
val inverseSurfaceDark = Color(0xFFE2E2E2)
val inverseOnSurfaceDark = Color(0xFF303030)
val inversePrimaryDark = Color(0xFF5E5E5E)
val surfaceDimDark = Color(0xFF131313)
val surfaceBrightDark = Color(0xFF393939)
val surfaceContainerLowestDark = Color(0xFF0E0E0E)
val surfaceContainerLowDark = Color(0xFF1B1B1B)
val surfaceContainerDark = Color(0xFF1F1F1F)
val surfaceContainerHighDark = Color(0xFF2A2A2A)
val surfaceContainerHighestDark = Color(0xFF353535)

val primaryDarkMediumContrast = Color(0xFFDCDCDC)
val onPrimaryDarkMediumContrast = Color(0xFF262626)
val primaryContainerDarkMediumContrast = Color(0xFF919191)
val onPrimaryContainerDarkMediumContrast = Color(0xFF000000)
val secondaryDarkMediumContrast = Color(0xFFFFDE82)
val onSecondaryDarkMediumContrast = Color(0xFF342700)
val secondaryContainerDarkMediumContrast = Color(0xFFEFBF04)
val onSecondaryContainerDarkMediumContrast = Color(0xFF423300)
val tertiaryDarkMediumContrast = Color(0xFFDCDCDC)
val onTertiaryDarkMediumContrast = Color(0xFF262626)
val tertiaryContainerDarkMediumContrast = Color(0xFF919191)
val onTertiaryContainerDarkMediumContrast = Color(0xFF000000)
val errorDarkMediumContrast = Color(0xFFFFD2CC)
val onErrorDarkMediumContrast = Color(0xFF540003)
val errorContainerDarkMediumContrast = Color(0xFFFF5449)
val onErrorContainerDarkMediumContrast = Color(0xFF000000)
val backgroundDarkMediumContrast = Color(0xFF131313)
val onBackgroundDarkMediumContrast = Color(0xFFE2E2E2)
val surfaceDarkMediumContrast = Color(0xFF131313)
val onSurfaceDarkMediumContrast = Color(0xFFFFFFFF)
val surfaceVariantDarkMediumContrast = Color(0xFF4C4546)
val onSurfaceVariantDarkMediumContrast = Color(0xFFE5DADB)
val outlineDarkMediumContrast = Color(0xFFBAAFB1)
val outlineVariantDarkMediumContrast = Color(0xFF988E8F)
val scrimDarkMediumContrast = Color(0xFF000000)
val inverseSurfaceDarkMediumContrast = Color(0xFFE2E2E2)
val inverseOnSurfaceDarkMediumContrast = Color(0xFF2A2A2A)
val inversePrimaryDarkMediumContrast = Color(0xFF484848)
val surfaceDimDarkMediumContrast = Color(0xFF131313)
val surfaceBrightDarkMediumContrast = Color(0xFF444444)
val surfaceContainerLowestDarkMediumContrast = Color(0xFF070707)
val surfaceContainerLowDarkMediumContrast = Color(0xFF1D1D1D)
val surfaceContainerDarkMediumContrast = Color(0xFF282828)
val surfaceContainerHighDarkMediumContrast = Color(0xFF323232)
val surfaceContainerHighestDarkMediumContrast = Color(0xFF3E3E3E)

val primaryDarkHighContrast = Color(0xFFF0F0F0)
val onPrimaryDarkHighContrast = Color(0xFF000000)
val primaryContainerDarkHighContrast = Color(0xFFC2C2C2)
val onPrimaryContainerDarkHighContrast = Color(0xFF0B0B0B)
val secondaryDarkHighContrast = Color(0xFFFFEFCA)
val onSecondaryDarkHighContrast = Color(0xFF000000)
val secondaryContainerDarkHighContrast = Color(0xFFEFBF04)
val onSecondaryContainerDarkHighContrast = Color(0xFF140E00)
val tertiaryDarkHighContrast = Color(0xFFF0F0F0)
val onTertiaryDarkHighContrast = Color(0xFF000000)
val tertiaryContainerDarkHighContrast = Color(0xFFC2C2C2)
val onTertiaryContainerDarkHighContrast = Color(0xFF0B0B0B)
val errorDarkHighContrast = Color(0xFFFFECE9)
val onErrorDarkHighContrast = Color(0xFF000000)
val errorContainerDarkHighContrast = Color(0xFFFFAEA4)
val onErrorContainerDarkHighContrast = Color(0xFF220001)
val backgroundDarkHighContrast = Color(0xFF131313)
val onBackgroundDarkHighContrast = Color(0xFFE2E2E2)
val surfaceDarkHighContrast = Color(0xFF131313)
val onSurfaceDarkHighContrast = Color(0xFFFFFFFF)
val surfaceVariantDarkHighContrast = Color(0xFF4C4546)
val onSurfaceVariantDarkHighContrast = Color(0xFFFFFFFF)
val outlineDarkHighContrast = Color(0xFFF9EDEF)
val outlineVariantDarkHighContrast = Color(0xFFCBC0C1)
val scrimDarkHighContrast = Color(0xFF000000)
val inverseSurfaceDarkHighContrast = Color(0xFFE2E2E2)
val inverseOnSurfaceDarkHighContrast = Color(0xFF000000)
val inversePrimaryDarkHighContrast = Color(0xFF484848)
val surfaceDimDarkHighContrast = Color(0xFF131313)
val surfaceBrightDarkHighContrast = Color(0xFF505050)
val surfaceContainerLowestDarkHighContrast = Color(0xFF000000)
val surfaceContainerLowDarkHighContrast = Color(0xFF1F1F1F)
val surfaceContainerDarkHighContrast = Color(0xFF303030)
val surfaceContainerHighDarkHighContrast = Color(0xFF3B3B3B)
val surfaceContainerHighestDarkHighContrast = Color(0xFF474747)

// ---------------------------------------------------------------------------
// Fixed colour roles
// ---------------------------------------------------------------------------
//
// The twelve *Fixed* roles are theme-independent by definition: a container that
// stays light in both light and dark, with content that stays dark on it. That is
// why they carry no Light/Dark suffix here. M3 defines them by tone --
// `xFixed` = tone 90, `xFixedDim` = tone 80, `onXFixed` = tone 10,
// `onXFixedVariant` = tone 30 (ColorLightTokens.kt, material3 1.10.0-alpha05, whose
// light and dark token files carry identical values for all twelve).
//
// Before this block they were simply absent, so `lightColorScheme()` defaulted them
// to `PaletteTokens.Primary90` and friends -- #EADDFF, Material baseline lavender --
// in a monochrome app, in both themes.
//
// **How these were derived.** Tone is CIE L*, so a tone of a chroma-0 palette is
// exactly the sRGB grey at that L*. Inverting L* -> Y -> sRGB reproduces this
// palette's existing greys to the byte: tone 0 = #000000 (primaryLight), tone 10 =
// #1B1B1B (primaryContainerLight, onSurfaceLight), tone 20 = #303030 (onPrimaryDark),
// tone 40 = #5E5E5E (inversePrimaryDark), tone 80 = #C6C6C6 (primaryDark), tone 90 =
// #E2E2E2 (onSurfaceDark), tone 95 = #F1F1F1 (inverseOnSurfaceLight), tone 100 =
// #FFFFFF. So the neutral family below is computed, not chosen.
//
// The secondary palette is gold at Lab hue 87.5 degrees, and its dark half is
// maximum in-gamut chroma at that hue -- which regenerates onSecondaryDark (#3D2F00,
// tone 20) and secondaryLight (#745B00, tone 40) byte for byte. Tones 10 and 30 below
// come off the same ramp. Its tones 90 and 80 are not regenerated but reused: the
// palette already ships #FFDE82 at tone 90 (secondaryDark) and the brand gold #EFBF04
// at tone 80 (secondaryContainer, in both themes). Generating them instead would have
// produced #FFDF99 and #F1C100 -- a second, almost identical gold two units away from
// the one already on screen, which is worse than no gold at all.
//
// Sanity check on the derivation: the four ratios these families produce land within
// 0.1 of M3's own baseline fixed family (13.30/7.17/10.08/5.44 here against
// 13.32/7.23/10.08/5.47 for source #6750A4). Tone, not hue, sets the ratio.

val primaryFixed = Color(0xFFE2E2E2)              // neutral tone 90
val primaryFixedDim = Color(0xFFC6C6C6)           // neutral tone 80
val secondaryFixed = Color(0xFFFFDE82)            // gold tone 90, = secondaryDark
val secondaryFixedDim = Color(0xFFEFBF04)         // gold tone 80, = secondaryContainer
// The tertiary family is a copy of primary throughout this palette -- compare
// tertiaryLight to primaryLight. Regenerate these two with primary's, or give
// tertiary its own hue and regenerate all six.
val tertiaryFixed = Color(0xFFE2E2E2)
val tertiaryFixedDim = Color(0xFFC6C6C6)

// Content on the fixed containers. The containers hold across the contrast setting --
// they are the brand-visible half -- and the content darkens, which is the same move
// Color.kt already makes for onSurface (#1B1B1B -> #111111 -> #000000) and
// onSurfaceVariant. Tones 10/30, then 5/20, then 0/10.
val onPrimaryFixed = Color(0xFF1B1B1B)            // neutral tone 10
val onPrimaryFixedVariant = Color(0xFF474747)     // neutral tone 30
val onSecondaryFixed = Color(0xFF241A00)          // gold tone 10
val onSecondaryFixedVariant = Color(0xFF584400)   // gold tone 30
val onTertiaryFixed = Color(0xFF1B1B1B)
val onTertiaryFixedVariant = Color(0xFF474747)

val onPrimaryFixedMediumContrast = Color(0xFF111111)            // neutral tone 5
val onPrimaryFixedVariantMediumContrast = Color(0xFF303030)     // neutral tone 20
val onSecondaryFixedMediumContrast = Color(0xFF171000)          // gold tone 5
val onSecondaryFixedVariantMediumContrast = Color(0xFF3D2F00)   // gold tone 20
val onTertiaryFixedMediumContrast = Color(0xFF111111)
val onTertiaryFixedVariantMediumContrast = Color(0xFF303030)

val onPrimaryFixedHighContrast = Color(0xFF000000)              // neutral tone 0
val onPrimaryFixedVariantHighContrast = Color(0xFF1B1B1B)       // neutral tone 10
val onSecondaryFixedHighContrast = Color(0xFF000000)            // gold tone 0 -- no
val onSecondaryFixedVariantHighContrast = Color(0xFF241A00)     // chroma survives L*=0
val onTertiaryFixedHighContrast = Color(0xFF000000)
val onTertiaryFixedVariantHighContrast = Color(0xFF1B1B1B)

