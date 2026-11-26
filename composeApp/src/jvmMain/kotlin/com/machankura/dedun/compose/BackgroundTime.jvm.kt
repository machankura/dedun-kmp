package com.machankura.dedun.compose

import kotlin.time.Duration

/** A desktop process is not suspended; it holds its connections for as long as it runs instead. */
actual val platformBackgroundGrace: Duration = Duration.ZERO

actual suspend fun <T> inPlatformBackgroundTask(name: String, block: suspend () -> T): T? = block()
