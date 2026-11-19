package com.machankura.dedun.compose

/**
 * A packaged distribution carries a jar manifest with its version (see
 * [AppVersion]); running from class directories -- gradle, a test -- does not.
 * A packaged build can still opt in with `-Dbennu.experimental=true`.
 */
actual fun isDevelopmentBuild(): Boolean =
    AppVersion::class.java.`package`?.implementationVersion == null ||
        System.getProperty("dedun.experimental") == "true"
