package com.machankura.dedun.compose.ui.theme

import androidx.compose.ui.tooling.preview.Preview

/**
 * The five conditions every screen has to survive, as one annotation.
 *
 * A screen renders under a light theme on a phone because that is the only way anybody
 * ever looks at it. The other four are where this app's defects have actually been: a
 * colour that only fails in dark, a fixed-height container that clips at 200% text, a
 * layout that stretches to 1000dp because nothing ever held it, a row that reflows badly
 * at 400dp. Each of those is invisible in a diff and obvious in a render.
 *
 * Replaces a bare `@Preview` at the 51 preview sites in the tree. `@Preview` is
 * `@Repeatable`, so this is five renders from one annotation rather than five annotations
 * copied onto every preview and drifting apart.
 *
 * **High contrast is deliberately not here**, and that is an argument rather than an
 * omission. Contrast is a property of the *scheme*, not of a screen: the app declares six
 * schemes and `ColorSchemeContrastTest` measures every pair in all six, so a screen that
 * is right in the default scheme is right in the high-contrast one by construction. A
 * per-screen high-contrast preview would be 51 more renders checking something already
 * proved, and there is no `@Preview` parameter for it anyway -- it needs
 * `BennuTheme(contrast = …)` in the body. [ThemeGallery] covers the schemes once, over
 * the components rather than over the screens.
 *
 * @see ThemeGallery
 */
@Preview(name = "Light", group = "conformance")
@Preview(name = "Dark", group = "conformance", uiMode = UiModeNightYes)
@Preview(name = "200% text", group = "conformance", fontScale = 2f)
@Preview(name = "Compact 400dp", group = "conformance", widthDp = 400, heightDp = 800)
@Preview(name = "Expanded 1000dp", group = "conformance", widthDp = 1000, heightDp = 800)
annotation class ConformancePreviews

/**
 * `Configuration.UI_MODE_NIGHT_YES or UI_MODE_TYPE_NORMAL`.
 *
 * Written as a literal because `android.content.res.Configuration` is not reachable from
 * common code, and as a named constant rather than an inline `0x21` because a bare hex
 * number in a preview annotation says nothing about which of the two flags it carries.
 */
private const val UiModeNightYes = 0x21
