package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One transaction a member signed alone from their guarded wallet.
 *
 * The subset of [GroupTransaction] a spend with no signing session needs.
 * `GroupTransaction` cannot hold it: its foreign keys are to a session and a
 * room, and a member's own spend has neither. Written whole when it is signed,
 * then filled in as the chain answers.
 *
 * No foreign key to [MemberWallet] either. A spend is a fact about the chain,
 * and a row that cascaded away with its wallet would take the record of where
 * the coins went with it.
 */
@Entity(
    indices = [
        Index("walletId"),
    ],
)
data class MemberWalletSpend(
    /** The transaction's id, which a segwit spend fixes before it is signed. */
    @PrimaryKey
    val txid: String,

    /** `W`, the wallet the inputs are under. */
    val walletId: String,

    /** The PSBT as built, base64: what every input's sighash was computed from. */
    val psbt: String,

    /** The finished transaction, hex, once it has passed `correctlySpends`. */
    val signedTransaction: String,

    /** When this device last handed it to Electrum -- which reports success whatever the server said. */
    val broadcastAt: Instant? = null,

    /** When this device's wallet first saw the transaction, in the mempool or in a block. */
    val seenAt: Instant? = null,

    /** When its first confirmation was seen. */
    val confirmedAt: Instant? = null,

    /** The txid of a later spend of the same coins that confirmed instead, if one did. */
    val replacedByTxid: String? = null,

    val createdAt: Instant = Clock.System.now(),
)
