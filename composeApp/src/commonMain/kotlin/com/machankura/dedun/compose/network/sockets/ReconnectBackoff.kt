package com.machankura.dedun.compose.network.sockets

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Bounds the exponent so a socket that has been failing for hours cannot overflow the doubling
 * into `Infinity`, which `Duration * Double` rejects outright — turning a reconnect loop into a
 * crash loop at roughly the point the network is least likely to come back on its own.
 */
internal const val MAX_RECONNECT_EXPONENT = 16

/**
 * How long to wait before reconnect [attempt]: [initialDelay] doubled once per previous
 * failure, capped at [maxDelay], plus up to [jitterFraction] of that again at random.
 *
 * The jitter is not decoration. Every relay in the pool drops at the same moment when the
 * network does, so without it they all come back in lockstep for as long as the outage lasts,
 * and each round of that is a simultaneous burst of connection attempts.
 *
 * [jitter] is a parameter rather than an inline `Random.nextDouble()` so the arithmetic can be
 * pinned in a test; callers leave it defaulted.
 */
internal fun reconnectDelay(
    attempt: Int,
    initialDelay: Duration,
    maxDelay: Duration,
    jitterFraction: Double,
    jitter: Double = Random.nextDouble(),
): Duration {
    val doublings = 2.0.pow(min(attempt - 1, MAX_RECONNECT_EXPONENT).coerceAtLeast(0))
    val backoff = minOf(initialDelay * doublings, maxDelay)

    return backoff + backoff * jitterFraction * jitter
}
