package com.machankura.dedun.compose.network.sockets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReconnectBackoffTest {

    private val initial = 1.seconds
    private val max = 60.seconds

    private fun delay(attempt: Int, jitter: Double = 0.0) =
        reconnectDelay(
            attempt = attempt,
            initialDelay = initial,
            maxDelay = max,
            jitterFraction = 0.25,
            jitter = jitter,
        )

    @Test
    fun `doubles once per previous failure`() {
        assertEquals(1.seconds, delay(attempt = 1))
        assertEquals(2.seconds, delay(attempt = 2))
        assertEquals(4.seconds, delay(attempt = 3))
        assertEquals(8.seconds, delay(attempt = 4))
    }

    @Test
    fun `stops growing at the cap`() {
        assertEquals(max, delay(attempt = 7))
        assertEquals(max, delay(attempt = 50))
    }

    /**
     * The reason [MAX_RECONNECT_EXPONENT] exists. `2.0.pow(4000)` is `Infinity`, and
     * `Duration * Double` throws on it — so without the clamp a socket that had been failing
     * for long enough turned its own reconnect loop into a crash loop, at roughly the moment
     * the network was least likely to recover unaided.
     */
    @Test
    fun `survives an attempt count large enough to overflow the doubling`() {
        assertEquals(max, delay(attempt = 4_000))
        assertEquals(max, delay(attempt = Int.MAX_VALUE))
    }

    /** Defensive: a caller that starts counting at zero should still get a usable delay. */
    @Test
    fun `treats a non-positive attempt as the first one`() {
        assertEquals(initial, delay(attempt = 0))
        assertEquals(initial, delay(attempt = -3))
    }

    @Test
    fun `adds at most the jitter fraction on top never subtracts`() {
        assertEquals(4.seconds, delay(attempt = 3, jitter = 0.0))
        assertEquals(5.seconds, delay(attempt = 3, jitter = 1.0))

        val midway = delay(attempt = 3, jitter = 0.5)
        assertTrue(midway > 4.seconds && midway < 5.seconds, "expected 4s..5s, got $midway")
    }

    /**
     * Jitter is applied after the cap, so a capped delay still spreads: relays all drop at the
     * same moment when the network does, and identical waits would bring them back in lockstep
     * for as long as the outage lasted.
     */
    @Test
    fun `jitter still spreads a capped delay`() {
        assertTrue(delay(attempt = 50, jitter = 1.0) > max)
    }
}
