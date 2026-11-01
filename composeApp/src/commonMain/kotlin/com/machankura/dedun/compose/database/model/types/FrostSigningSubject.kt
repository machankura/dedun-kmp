package com.machankura.dedun.compose.database.model.types

/**
 * What a signing session's items are messages *of*.
 *
 * A column rather than an inference. A session could be recognised as a
 * transaction by the presence of its `GroupTransaction` row, and the code that
 * decides what a nonce is committed to should not be a join away from the answer.
 */
enum class FrostSigningSubject {
    /** Nostr events: each item's message is an event id, its payload the unsigned event. */
    EVENTS,

    /**
     * One bitcoin transaction from the group's wallet: each item is one input, its
     * message that input's BIP341 sighash, its payload where under the wallet's
     * account the coin it spends is.
     */
    TRANSACTION,

    /**
     * A sweep of what a closed Lightning channel left the group into its onchain
     * wallet: each item is one input, a taproot script-path spend under a key
     * derived from the group's basepoints, its message that input's sighash.
     * See `GroupSweep`.
     */
    SWEEP,
}
