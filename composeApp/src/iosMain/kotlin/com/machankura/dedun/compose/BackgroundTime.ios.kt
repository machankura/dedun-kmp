package com.machankura.dedun.compose

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/** Under the thirty seconds iOS grants, so the task is ended by us and not expired by it. */
actual val platformBackgroundGrace: Duration = 25.seconds

/**
 * `beginBackgroundTask` around [block], ended exactly once: when [block] finishes, or when iOS
 * takes the time back, which cancels it. Not compiled on the linux host this was written on.
 */
actual suspend fun <T> inPlatformBackgroundTask(name: String, block: suspend () -> T): T? = coroutineScope {
    val work = async { block() }
    val application = UIApplication.sharedApplication
    var ended = false
    var taskId = UIBackgroundTaskInvalid
    fun end() {
        if (ended || taskId == UIBackgroundTaskInvalid) return
        ended = true
        application.endBackgroundTask(taskId)
    }
    taskId = application.beginBackgroundTaskWithName(name) {
        work.cancel()
        end()
    }
    try {
        work.await()
    } catch (expired: CancellationException) {
        null
    } finally {
        end()
    }
}
