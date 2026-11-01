package com.machankura.dedun.compose.network.relays

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which CLOSED reasons mean "ease off".
 *
 * The classification decides what happens next and is expensive to get wrong in both
 * directions: a refusal read as a transient failure is answered by opening another
 * subscription, which is how one "too many concurrent REQs" becomes a flood of them, while an
 * unsupported filter read as back-pressure leaves a subscription shut for five minutes over a
 * problem no amount of waiting fixes.
 */
class RelayBackPressureTest {

    @Test
    fun `recognises the NIP-01 machine-readable prefix`() {
        assertTrue(isRelayBackPressure("rate-limited: slow down there chief"))
    }

    @Test
    fun `recognises the free-text forms relays actually send`() {
        listOf(
            "ERROR: too many concurrent REQs",
            "rate limit exceeded",
            "Please slow down",
            "TOO MANY SUBSCRIPTIONS",
            "maximum concurrent subscriptions reached",
        ).forEach { reason ->
            assertTrue(isRelayBackPressure(reason), "expected back-pressure: $reason")
        }
    }

    @Test
    fun `does not read a refusal we cannot wait out as back-pressure`() {
        listOf(
            "auth-required: we can't serve DMs to unauthenticated users",
            "unsupported: filter contains unknown tag",
            "invalid: filter is empty",
            "error: negentropy disabled",
            "blocked: you are not allowed to write here",
        ).forEach { reason ->
            assertFalse(isRelayBackPressure(reason), "did not expect back-pressure: $reason")
        }
    }

    /**
     * A CLOSED with no reason at all is common. Treating it as back-pressure would mean the
     * least informative refusal produced the longest possible outage.
     */
    @Test
    fun `a missing or empty reason is not back-pressure`() {
        assertFalse(isRelayBackPressure(null))
        assertFalse(isRelayBackPressure(""))
    }
}
