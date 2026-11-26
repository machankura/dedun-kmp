package com.machankura.dedun.compose

import kotlin.time.Duration

actual val platformBackgroundGrace: Duration = Duration.ZERO

/** Android's background work is WorkManager's (Phase 10); there is no task to wrap. */
actual suspend fun <T> inPlatformBackgroundTask(name: String, block: suspend () -> T): T? = block()
