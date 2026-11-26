package com.machankura.dedun.compose

import kotlin.time.Duration

/**
 * How long the platform lets a backgrounded app keep working before it is suspended, as far as
 * this app means to use it: iOS grants a background task about thirty seconds, which is what it
 * takes to finish sending what was queued (D11). Zero elsewhere -- Android's cached-app freezer
 * stops a backgrounded process within seconds, and a desktop is not suspended at all.
 * docs/notifications-and-background-sync.md, Phase 8.
 */
expect val platformBackgroundGrace: Duration

/**
 * Runs [block] as a platform background task, so the platform does not suspend the process under
 * it; null when the platform takes the time back first. On a platform with no such thing, runs
 * [block] as it is.
 */
expect suspend fun <T> inPlatformBackgroundTask(name: String, block: suspend () -> T): T?
