package com.machankura.dedun.compose.exceptions

/**
 * The room is mid-way through settling a fork -- two members committed against
 * the same epoch, and the convergence pass that picks between them has not
 * reached its cutoff -- so nothing may be encrypted or committed against a state
 * that may lose. The pass settles within a quiet second or two; what was asked
 * for stays queued or is asked for again. See docs/marmot-v2.md, Phase 5.
 */
class MarmotConvergencePendingException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
