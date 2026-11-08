package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.runtime.Composable

/** Desktop has no per-window brightness, and turning a monitor up is not an app's business. */
@Composable
actual fun RaiseBrightnessWhileVisible() = Unit
