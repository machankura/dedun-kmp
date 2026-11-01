package com.machankura.dedun.compose.network.relays

/**
 * Whether a relay's CLOSED/NOTICE reason means "you are asking for too much" rather than
 * "I cannot serve this".
 *
 * The difference decides what to do next, and getting it wrong is expensive in both
 * directions: answering back-pressure by immediately opening another subscription is what
 * turns one refusal into a flood of them, while treating an unsupported filter as
 * back-pressure leaves a request unserved for minutes.
 *
 * NIP-01 gives `rate-limited:` as the machine-readable prefix; the free-text forms are what
 * relays actually send.
 */
fun isRelayBackPressure(reason: String?): Boolean {
    val text = reason?.lowercase() ?: return false

    return text.startsWith("rate-limited") ||
        text.contains("rate limit") ||
        text.contains("too many") ||
        text.contains("concurrent") ||
        text.contains("slow down")
}
