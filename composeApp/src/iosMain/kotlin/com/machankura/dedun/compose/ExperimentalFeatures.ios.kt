package com.machankura.dedun.compose

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual fun isDevelopmentBuild(): Boolean = Platform.isDebugBinary
