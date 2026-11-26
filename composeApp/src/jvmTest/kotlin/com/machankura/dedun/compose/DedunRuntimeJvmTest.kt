package com.machankura.dedun.compose

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * One runtime per process: whatever asks second gets what was made first.
 * docs/notifications-and-background-sync.md, Phase 2.
 */
class GizaRuntimeJvmTest {

    @Test
    fun `the first value made is the one every later ask gets`() {
        val singleton = ProcessSingleton<Any>()
        assertNull(singleton.current())

        val made = AtomicInteger()
        val first = singleton.getOrCreate { made.incrementAndGet(); Any() }
        val second = singleton.getOrCreate { made.incrementAndGet(); Any() }

        assertSame(first, second)
        assertSame(first, singleton.current())
        assertEquals(1, made.get())
    }

    /**
     * A worker's thread and the main thread can both ask first. Both may build, but every caller
     * gets the same one -- which is only safe because nothing is started by building.
     */
    @Test
    fun `racing first asks all get the same value`() {
        val singleton = ProcessSingleton<Any>()
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Any>())

        val threads = (1..8).map {
            thread {
                start.await()
                results += singleton.getOrCreate { Any() }
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, results.toSet().size)
        assertSame(results.first(), singleton.current())
    }
}
