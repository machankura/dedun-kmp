package com.machankura.dedun.compose.managers

import kotlin.time.Instant

/**
 * What this device's group Lightning node is doing, as the operator's wallet screen shows it
 * (`docs/lsp-connection-status.md`). Written by [GroupLightningNode] where the facts are -- its start and
 * its reconnect loop -- rather than read back out of its log.
 */
sealed interface GroupLightningNodeStatus {
    /** This device is the operator, by key, and runs no node for the wallet: another device of the same identity may (D5). */
    data object NotOnThisDevice : GroupLightningNodeStatus

    /** The node is not running: refused at its last start for [reason], or stopped, when null. */
    data class NotRunning(val reason: Refusal? = null) : GroupLightningNodeStatus

    /** Elected and allowed to run, waiting for the group's round one before it dials the LSP. */
    data object Starting : GroupLightningNodeStatus

    data class Running(val term: Long, val link: LspLink) : GroupLightningNodeStatus

    /** Why a node did not start. The UI says each in its own words, from the catalogue (D3). */
    enum class Refusal { NOT_OPERATOR, ROOM_NOT_SYNCED, NO_SEED, POOL_SHORT, NO_ONCHAIN_WALLET }
}

/** The running node's connection to the LSP. */
sealed interface LspLink {
    /** Dialling, for the [attempt]th time in a row. */
    data class Connecting(val attempt: Int) : LspLink

    /** The LSP's `init` arrived on this connection at [since] (D2). */
    data class Connected(val since: Instant) : LspLink

    /**
     * The last attempt did not hold, for [drop]; the next starts at [retryAt]. [failures] counts the
     * attempts in a row that did not hold, as the backoff does; [lastConnected] is when the LSP last sent its
     * `init`, or null when it has not on this run.
     */
    data class Unreachable(val drop: LspDrop, val failures: Int, val retryAt: Instant, val lastConnected: Instant?) : LspLink
}

/** Why a connection to the LSP ended: a [cause] the screen can say, and the [detail] that was logged (D4). */
data class LspDrop(val cause: Cause, val detail: String) {
    enum class Cause {
        /** One side wants a feature the other does not offer, and the LSP hung up on it. */
        FEATURE_MISMATCH,
        /** No socket: refused, timed out or not resolved. */
        UNREACHABLE,
        /** A socket, but no handshake: the node gave up on it. */
        HANDSHAKE,
        /** The LSP ended a connection that had been up. */
        CLOSED_BY_LSP,
        /** Tor is on and the LSP has no onion address on this network, so the node will not dial it. */
        TOR_WITHOUT_ONION,
        OTHER,
    }
}
