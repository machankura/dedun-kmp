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
 * co-signer refuse to spend a VTXO twice, or for the wrong amount, and how a member checks a snapshot.
 *
 * Signing a thing doesn't make it so, since the operator can ask and never submit. So a VTXO is usable only once [settled], when
 * this device has seen [origin] take effect, and until then what it replaces is only reserved. A reservation, and a VTXO still pending,
 * last as long as the [term] they were made in: a hostile operator can freeze coins that way, and an election is the way out.
 */
@Entity(primaryKeys = ["wallet", "outpoint"])
data class GroupArkVtxo(
    val wallet: String,
    /** `txid:vout`. */
    val outpoint: String,
    val amountSat: Long,
    /** The commitment or Ark transaction this VTXO comes from, or null for one that needs no such proof. */
    val origin: String? = null,
    val settled: Boolean = false,
    /** The commitment or Ark transaction this device last signed this VTXO into. */
    val reservedBy: String? = null,
    /** The term it was last reserved or made in. */
    val term: Long? = null,
    /** Whether what it was reserved for has taken effect. */
    val spent: Boolean = false,
)

/** The one account of a round this device has signed anything under: a second is a mistake or an attack. */
@Entity(primaryKeys = ["wallet", "commitment"])
data class GroupArkRound(val wallet: String, val commitment: String, val noteHash: String)

/** The one commitment a tree key has signed for, ever. */
@Entity(primaryKeys = ["wallet", "treeKey"])
data class GroupArkTreeBinding(val wallet: String, val treeKey: HexKey, val commitment: String)

/**
 * A signature this device has checked: the request exactly as [com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy] saw it,
 * the digest it computed, and the label's counter. Round two signs from this and nothing newer, since two answers under a label leak
 * a share however they differ, in the message or in anything else that goes into the challenge.
 */
@Entity(primaryKeys = ["wallet", "label"])
data class GroupArkChecked(val wallet: String, val label: HexKey, val child: Long?, val counter: Long, val item: String, val message: HexKey)

/**
 * The lowest label counter a device may answer, which is what stops a restored phone answering a label twice with no error. Null
 * until `t` others have said how far they have answered, and until then this device signs nothing.
 */
@Entity(primaryKeys = ["wallet"])
data class GroupArkFloor(val wallet: String, val floor: Long? = null, val treeFloor: Long? = null)

/** How far one participant says it has answered. */
@Entity(primaryKeys = ["wallet", "participant"])
data class GroupArkMark(val wallet: String, val participant: HexKey, val highest: Long, val highestChild: Long)
