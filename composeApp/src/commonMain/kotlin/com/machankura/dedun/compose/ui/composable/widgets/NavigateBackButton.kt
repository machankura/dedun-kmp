package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.back
import org.jetbrains.compose.resources.stringResource

/**
 * The button in a pushed screen's leading slot, the one M3 calls the navigation icon.
 *
 * Before this file, 18 screens drew it and 22 did not, and the 18 spelled it four ways:
 * `ArrowBack`, the auto-mirrored `ArrowBack`, `ArrowBackIosNew`, and a `"Back"` literal
 * against a `stringResource`. A screen that is reached by pushing a route and has no way
 * to pop it is a dead end wherever the platform gives no back gesture -- which is the
 * desktop target, where a window has neither a back button nor an edge to swipe from.
 *
 * Two of the four spellings are decisions this component makes once:
 *
 *  - **The icon is the auto-mirrored one.** "Back" points at the leading edge, and the
 *    leading edge is on the right in an RTL locale. `Icons.Filled.ArrowBack` is
 *    deprecated for exactly this reason, and the navigation transitions already mirror
 *    through `slideIntoContainer`; an arrow that did not would point the wrong way at the
 *    screen that was sliding the right way.
 *  - **The description is the catalogue's.** "Back" is what a screen reader says, and it
 *    is text, so it lives in `strings.xml` like every other word the app says.
 *
 * `IconButton` reserves the 48dp target itself, so there is no modifier to remember.
 *
 * Put it in `TopAppBar(navigationIcon = …)`, and only on a screen the user pushed onto
 * the stack. A top-level destination has the navigation bar instead, and a screen that
 * replaced the whole stack -- onboarding, the loading gate -- has nothing under it to
 * return to; a back button there would pop to an empty host.
 *
 * @param onNavigateBack what popping means for the screen, which is nearly always the
 *   host's `popBackStack()`. Hoisted rather than read from a controller here so the
 *   screen stays previewable and testable without one.
 */
@Composable
fun NavigateBackButton(onNavigateBack: () -> Unit) {
    IconButton(onClick = onNavigateBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(Res.string.back),
        )
    }
}
