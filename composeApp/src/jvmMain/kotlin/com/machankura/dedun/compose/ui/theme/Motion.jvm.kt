package com.machankura.dedun.compose.ui.theme

import androidx.compose.runtime.Composable

/**
 * Always `false`, and this is the honest answer rather than a stub.
 *
 * The three desktop platforms each have a reduced-motion setting -- Windows' "Show
 * animations in Windows", macos' "Reduce motion", and the freedesktop
 * `gtk-enable-animations` / `org.gnome.desktop.interface enable-animations` -- and **none
 * of them reaches AWT or the jvm at all**. Reading any one means a native call per
 * platform, which is the same wall `platformThemeContrast` hits on linux and macos.
 *
 * Recorded here rather than papered over, because the alternative shape -- guessing, or
 * quietly disabling motion on desktop -- would be worse than a documented gap. When the
 * app grows a settings screen, this becomes a preference with the platform as its default,
 * which is where a desktop app should have ended up regardless: it cannot always see what
 * the desktop was told.
 */
@Composable
actual fun platformReducedMotion(): Boolean = false
