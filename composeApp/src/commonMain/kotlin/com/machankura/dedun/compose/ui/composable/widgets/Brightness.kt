package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.runtime.Composable

/**
 * Turns the screen up while the composable calling it is on screen, and puts it back after.
 *
 * The guide asks for this where a code is held up to be scanned: a phone on automatic brightness in a
 * dim room renders a QR code a camera cannot resolve, and the member holding it out has no way to know
 * that is why nothing is happening. It is the one accessibility fix in this plan that a sighted member
 * with good eyes never notices.
 *
 * Called by the screens a member holds up, not by [QRCodeView] itself. Turning the screen up is a
 * visible side effect, and the profile share and the group's other wallets did not ask for it.
 *
 * Only Android does anything. The desktop target has no per-window brightness, and on iOS
 * `UIScreen.brightness` is a device-wide setting rather than a per-window override -- raising it there
 * would change what a member finds when they leave the app, which is worse than a dim QR code.
 */
@Composable
expect fun RaiseBrightnessWhileVisible()
