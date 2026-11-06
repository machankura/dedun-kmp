package com.machankura.dedun.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Cancels this scope and waits for what was running in it to stop: what a tearDown does before
 * it closes a database.
 *
 * `cancel()` only asks. A coroutine answers at its next suspension point, and a Room query has
 * none between its statements: `NavigationViewModel`'s observer has been seen running the rest
 * of an `observeProfile` transaction after `scope.cancel()` had returned and `db.close()` had
 * been called. `close()` does not wait for it either -- `ConnectionPoolImpl` closes connections
 * still in use, its own TODO says as much (b/319657104) -- and the bundled SQLite is built with
 * `THREADSAFE=2`, so no mutex stands between a `prepare` and the `close` freeing its connection.
 * What that costs is a SIGSEGV in the test JVM, which takes the whole run with it and names
 * whichever test happened to be next.
 *
 * The timeout makes a coroutine that will not stop a failed test instead of a hung run.
 */
fun CoroutineScope.cancelAndWait() {
    val job = coroutineContext.job
    runBlocking { withTimeout(30.seconds) { job.cancelAndJoin() } }
}
