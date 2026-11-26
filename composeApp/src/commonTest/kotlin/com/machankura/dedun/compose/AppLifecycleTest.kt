package com.machankura.dedun.compose

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Visible and connected are two answers now. docs/notifications-and-background-sync.md, Phase 8. */
class AppLifecycleTest {

    @AfterTest
    fun reset() = AppLifecycle.reset()

    @Test
    fun `a phone off screen is neither visible nor connected`() {
        AppLifecycle.enteredBackground()
        assertFalse(AppLifecycle.isVisible.value)
        assertFalse(AppLifecycle.holdConnections.value)
        AppLifecycle.enteredForeground()
        assertTrue(AppLifecycle.holdConnections.value)
    }

    /** The bug Phase 0, check 1 found: minimising the desktop window closed its subscriptions. */
    @Test
    fun `a minimised desktop is not visible, and stays connected`() {
        AppLifecycle.keepConnected(AppLifecycle.KEEP_DESKTOP)
        AppLifecycle.enteredBackground()
        assertFalse(AppLifecycle.isVisible.value)
        assertTrue(AppLifecycle.holdConnections.value)
    }

    @Test
    fun `a grace holds the connections until it is released`() {
        AppLifecycle.enteredBackground()
        AppLifecycle.keepConnected(AppLifecycle.KEEP_GRACE)
        assertTrue(AppLifecycle.holdConnections.value)
        AppLifecycle.release(AppLifecycle.KEEP_GRACE)
        assertFalse(AppLifecycle.holdConnections.value)
    }
}
