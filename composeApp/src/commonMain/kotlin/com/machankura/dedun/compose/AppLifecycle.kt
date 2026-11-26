package com.machankura.dedun.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the app is on screen, and whether it should hold its relay connections open -- two
 * questions that used to be one. docs/notifications-and-background-sync.md, Phase 8.
 *
 * A singleton rather than something threaded through the composition, because the consumers
 * are not composables: they are long-lived coroutines at application scope, started before
 * any screen exists and outliving all of them.
 *
 * They came apart because the answers differ by platform. A phone that is not on screen is about
 * to be suspended, so its subscriptions close; a desktop window that is minimised is still a
 * running process with a network, and Compose Desktop reports a minimised window as stopped, so
 * the single flag used to close a desktop's subscriptions every time it was minimised.
 *
 * Both default to true. Nothing here can observe a platform that has not wired
 * [enteredForeground]/[enteredBackground] up, and on such a platform "always on" is the
 * behaviour that predates this file -- a subscription that never opens is a far worse failure
 * than one that stays open too long.
 */
object AppLifecycle {
    private val _isVisible = MutableStateFlow(true)

    /** Somebody is looking: read by read marks and by the notification policy. */
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /** Reasons, other than being on screen, to keep the connections open. */
    private val keepers = MutableStateFlow<Set<String>>(emptySet())

    private val _holdConnections = MutableStateFlow(true)

    /**
     * Whether the live subscriptions should be open: on screen, or something has asked to keep
     * them -- the desktop for as long as it runs, the iOS grace after backgrounding, Android's
     * Stay connected service.
     */
    val holdConnections: StateFlow<Boolean> = _holdConnections.asStateFlow()

    fun enteredForeground() {
        _isVisible.value = true
        recompute()
    }

    fun enteredBackground() {
        _isVisible.value = false
        recompute()
    }

    /** Keeps the connections open for [reason] until [release] is called with it. */
    fun keepConnected(reason: String) {
        keepers.update { it + reason }
        recompute()
    }

    fun release(reason: String) {
        keepers.update { it - reason }
        recompute()
    }

    private fun recompute() {
        _holdConnections.value = _isVisible.value || keepers.value.isNotEmpty()
    }

    /** For a test, which has to start from a known state. */
    internal fun reset() {
        _isVisible.value = true
        keepers.value = emptySet()
        recompute()
    }

    const val KEEP_DESKTOP = "desktop"
    const val KEEP_GRACE = "grace"
    const val KEEP_STAY_CONNECTED = "stayConnected"
    const val KEEP_BACKGROUND_PASS = "backgroundPass"
}
