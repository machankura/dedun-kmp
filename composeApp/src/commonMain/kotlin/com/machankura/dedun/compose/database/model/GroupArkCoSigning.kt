package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * A participant's public share of one tree key (`docs/group-wavelength-wallet.md`, D5), from which everybody finds the key: a
 * tree key is hashed from each member's seeds, so nobody knows it until `2t-1` of these are in.
 */
@Entity(primaryKeys = ["wallet", "child", "participant"])
data class GroupArkTreeShare(val wallet: String, val child: Long, val participant: HexKey, val share: HexKey)

/**
 * What this device knows the group's wallet holds, from what it has itself signed: never the operator's word. It is what lets a
 * co-signer refuse to forfeit a VTXO twice, or for the wrong amount, and how a member checks a snapshot.
 */
@Entity(primaryKeys = ["wallet", "outpoint"])
data class GroupArkVtxo(
    val wallet: String,
    /** `txid:vout`. */
    val outpoint: String,
    val amountSat: Long,
    /** The round this VTXO was forfeited into, once this device has signed that. */
    val forfeitedIn: String? = null,
    /** Whether this device signed it away in a transfer. */
    val spent: Boolean = false,
)

/**
 * The lowest label counter a device may answer, which is what stops a restored phone answering a label twice with no error. Null
 * until `t` others have said how far they have answered, and until then this device signs nothing.
 */
@Entity(primaryKeys = ["wallet"])
data class GroupArkFloor(val wallet: String, val floor: Long? = null)

/** How far one participant says it has answered. */
@Entity(primaryKeys = ["wallet", "participant"])
data class GroupArkMark(val wallet: String, val participant: HexKey, val highest: Long)
