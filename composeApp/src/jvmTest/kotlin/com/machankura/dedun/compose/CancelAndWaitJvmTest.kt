package com.machankura.dedun.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class CancelAndWaitJvmTest {

    /** The sleep stands in for a statement in native code: work that cannot notice a cancellation. */
    @Test
    fun `a coroutine between suspension points has finished by the time it returns`() {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        scope.launch {
            started.countDown()
            Thread.sleep(200)
            finished.set(true)
        }
        started.await()

        scope.cancelAndWait()

        assertTrue(finished.get(), "a tearDown would go on to close the database under this coroutine")
    }
}
