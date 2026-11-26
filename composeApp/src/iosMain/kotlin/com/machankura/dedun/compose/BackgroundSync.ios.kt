package com.machankura.dedun.compose

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.managers.BackgroundSyncPass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState

/** What Swift holds while a pass runs, to cancel it when iOS takes the time back. */
class BackgroundSyncHandle internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/** Whether to ask iOS for background time at all. docs/notifications-and-background-sync.md, D12. */
fun backgroundSyncEnabled(): Boolean = ExperimentalFeatures.backgroundSync

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Runs the pass for a BGTaskScheduler task and reports once, through [onDone], whether it got to the
 * end: a refresh ([quick]) with 25 seconds, under the thirty iOS grants; a processing task with
 * eight minutes. Called from `iOSApp.swift`. Not compiled on the linux host this was written on.
 */
@OptIn(ExperimentalAtomicApi::class)
fun startBackgroundSync(quick: Boolean, onDone: (Boolean) -> Unit): BackgroundSyncHandle {
    val reported = AtomicInt(0)
    fun report(success: Boolean) {
        if (reported.compareAndSet(0, 1)) onDone(success)
    }

    if (!ExperimentalFeatures.backgroundSync) {
        report(true)
        return BackgroundSyncHandle(Job().apply { complete() })
    }

    // A task launched cold has no screen, and AppLifecycle defaults to visible -- which the pass
    // reads as "the foreground already covers it".
    if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) {
        AppLifecycle.enteredBackground()
    }

    val job = scope.launch {
        try {
            val outcome = gizaRuntime().backgroundSync.run(
                kind = if (quick) BackgroundSyncPass.Kind.Quick else BackgroundSyncPass.Kind.Full,
                deadline = if (quick) 25.seconds else 8.minutes,
            )
            report(outcome == BackgroundSyncPass.Outcome.Completed || outcome == BackgroundSyncPass.Outcome.Visible)
        } finally {
            report(false)
        }
    }
    return BackgroundSyncHandle(job)
}
