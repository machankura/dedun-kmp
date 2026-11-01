package com.machankura.dedun.compose

import platform.Foundation.NSBundle

actual object AppVersion {
    val serviceName: String = "Machankura"
    val accessGroup: String? = null

    actual val versionName: String
        get() = (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as String?) ?: "Unknown"
    actual val versionCode: String
        get() = (NSBundle.mainBundle.infoDictionary?.get("CFBundleVersion") as String?) ?: "0"
}