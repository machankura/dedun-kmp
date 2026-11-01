package com.machankura.dedun.compose

import com.machankura.dedun.android.BuildConfig

actual object AppVersion {
    actual val versionName: String
        get() = BuildConfig.VERSION_NAME
    actual val versionCode: String
        get() = BuildConfig.VERSION_CODE.toString()
}