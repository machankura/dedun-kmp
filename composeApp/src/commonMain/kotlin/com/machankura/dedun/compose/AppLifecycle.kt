package com.machankura.dedun.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is in the foreground, for the parts of the app that hold network
 * connections open.
 *
 * A singleton rather than something threaded through the composition, because the consumers
 * are not composables: they are long-lived coroutines at application scope, started before
 * any screen exists and outliving all of them.
 *
 * Defaults to foreground. Nothing here can observe a platform that has not wired
 * [enteredForeground]/[enteredBackground] up, and on such a platform "always on" is the
 * behaviour that predates this file — a subscription that never opens is a far worse failure
 * than one that stays open too long.
 */
object AppLifecycle {
    private val _isForeground = MutableStateFlow(true)

    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun enteredForeground() {
        _isForeground.value = true
    }

    fun enteredBackground() {
        _isForeground.value = false
    }
}
