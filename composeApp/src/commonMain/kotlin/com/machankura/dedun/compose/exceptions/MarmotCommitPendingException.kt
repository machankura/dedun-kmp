package com.machankura.dedun.compose.exceptions

/**
 * The room has a commit of its own still waiting for a relay to accept it, so no
 * new one may be prepared: a second commit at the same epoch would be a fork this
 * device caused itself. The state it describes -- an invite, say -- is not lost;
 * the earlier one lands, and this is asked for again. See docs/marmot-v2.md,
 * Phase 6.
 */
class MarmotCommitPendingException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
