package com.machankura.dedun.compose

/**
 * Android reads these from `BuildConfig`, which the android plugin generates. There is no
 * equivalent for the jvm target, so this reads the jar manifest that `compose.desktop`
 * writes when it packages a distribution, and falls back when running from a class
 * directory -- from gradle, or a test -- where no manifest exists.
 */
actual object AppVersion {
    actual val versionName: String
        get() = AppVersion::class.java.`package`?.implementationVersion ?: "0.0.0-dev"

    /** No monotonic build number exists here the way `versionCode` does on android. */
    actual val versionCode: String
        get() = "0"
}
