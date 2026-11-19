package com.machankura.dedun.compose

import com.machankura.dedun.android.BuildConfig

actual fun isDevelopmentBuild(): Boolean = BuildConfig.DEBUG
