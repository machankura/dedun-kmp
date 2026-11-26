package com.machankura.dedun.compose

/**
 * Features built but not shipped: on in a debug or development build, off in a
 * release one, and overridable in tests.
 *
 * Share rotation (docs/frost-share-refresh.md, D4) stays here until upstream
 * drops "experimental" from the secp256k1-zkp enrollment module and the
 * frost_refresh module has had independent review. Off, the app offers no
 * rotation or repair, and declines a rotation it is asked to take part in,
 * out loud.
 */
object ExperimentalFeatures {
    /** Set by a test that has to see the build a release would be. */
    var shareRefreshOverride: Boolean? = null

    val shareRefresh: Boolean
        get() = shareRefreshOverride ?: isDevelopmentBuild()

    /** Set by a test that has to see the build a release would be. */
    var backgroundSyncOverride: Boolean? = null

    /**
     * Syncing while the app is off screen (docs/notifications-and-background-sync.md, D12): the
     * WorkManager worker, the iOS background tasks, Android's Stay connected and the desktop tray.
     * Off in a release until a device run says the pass behaves. Notifications while the app runs
     * are not behind it: they carry no protocol risk.
     */
    val backgroundSync: Boolean
        get() = backgroundSyncOverride ?: isDevelopmentBuild()
}

/** Whether this is a debug or development build of the app, rather than a release. */
expect fun isDevelopmentBuild(): Boolean
