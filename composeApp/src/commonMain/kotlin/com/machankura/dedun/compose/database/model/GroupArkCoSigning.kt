package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant

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
    /** How many blocks after its commitment confirms the batch it's in can be swept, from the round this device checked. */
    val sweepDelay: Long? = null,
    /**
     * The height the operator can sweep it from, by this device's own count and never the operator's: the commitment's height as this
     * device's Electrum says, plus [sweepDelay], or the soonest of what a transfer spent. Null where this device hasn't worked one out.
     */
    val expiresAtHeight: Long? = null,
)

/**
 * A round this device has signed something towards and has seen neither confirm nor die. [commitmentTx] is what the operator noted
 * and this device checked, kept so that a commitment no Electrum server has heard of can still be asked after by its inputs.
 */
@Entity(primaryKeys = ["wallet", "commitment"])
data class GroupArkPendingRound(val wallet: String, val commitment: String, val chatRoomId: String, val chain: String, val commitmentTx: HexKey)

/**
 * One checkpoint of a transfer this device has signed something towards. A transfer never reaches the chain, so what makes it so is
 * the group's signature on every checkpoint being complete. [label] is where this device signed this one, and is only ever set after
 * it verified that the operator had co-signed the Ark transaction.
 */
@Entity(primaryKeys = ["wallet", "ark", "checkpoint"])
data class GroupArkCheckpoint(val wallet: String, val ark: String, val checkpoint: String, val label: HexKey? = null)

/**
 * The transfer this device signed a Lightning payment into. A payment goes once, whatever the daemon asks. [amountSat] is what its vHTLC
 * held, which is what the member it paid is charged, and [vhtlcScript] how its refund is known; [refundedIn] is the transfer that brought
 * it home, which gives the member it back once it settles (`docs/group-wavelength-payouts.md`, Phase 5). The first two are null on a
 * row from before they were kept.
 */
@Entity(primaryKeys = ["wallet", "paymentHash"])
data class GroupArkPayment(
    val wallet: String,
    val paymentHash: HexKey,
    val tx: String,
    val amountSat: Long? = null,
    val vhtlcScript: HexKey? = null,
    val refundedIn: String? = null,
    /** When this device signed it, so a history can put it in order. Null on a row from before it was kept. */
    val atEpochSeconds: Long? = null,
)

/**
 * An approved onchain payout this device signed a round paying (`docs/group-wavelength-payouts.md`, W11). From then on it can't be
 * assumed not to have left, so the member is charged [amountSat] and the round's [feeSat]; it is [confirmed] once the round is, and
 * forgotten if the round is seen to die, which leaves the approval to be paid in another.
 */
@Entity(primaryKeys = ["wallet", "authorisation"])
data class GroupArkLeave(
    val wallet: String,
    val authorisation: String,
    val commitment: String,
    val script: HexKey,
    val amountSat: Long,
    val feeSat: Long,
    val confirmed: Boolean = false,
    /** When this device signed the round. Null on a row from before it was kept. */
    val atEpochSeconds: Long? = null,
    /** When it saw that round confirm, which is the one moment a payout finished that nobody has to be believed about. */
    val confirmedAtEpochSeconds: Long? = null,
)

/** A fee this device has agreed to, on what, when and in which term, against the group's budget for them. */
@Entity(primaryKeys = ["wallet", "tx"])
data class GroupArkFee(val wallet: String, val tx: String, val feeSat: Long, val term: Long, val atEpochSeconds: Long)

/**
 * The one account of a round this device has signed anything under: a second is a mistake or an attack.
 * [atEpochSeconds] is when it signed, kept so a round the free refresh window cost nothing still has a
 * place in the history; null on a row from before it was kept.
 */
@Entity(primaryKeys = ["wallet", "commitment"])
data class GroupArkRound(val wallet: String, val commitment: String, val noteHash: String, val atEpochSeconds: Long? = null)

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

/** The newest thing the wallet's operator said it holds: a claim, whose [content] is a `GroupWavelengthSnapshot`. */
@Entity(primaryKeys = ["wallet"])
data class GroupArkSnapshotRow(val wallet: String, val sentBy: HexKey, val term: Long, val content: String, val sentAt: Instant)

/**
 * A member's request for a boarding address and what the operator answered, on every device: an address is one member's for good, and
 * [script] is only ever one this device recomputed from the statement, so a deposit is attributed before it can be shown.
 */
@Entity(primaryKeys = ["wallet", "requestId"])
data class GroupArkDepositAddress(
    val wallet: String,
    val requestId: String,
    val requester: HexKey,
    val requestedAt: Instant,
    val address: String? = null,
    val script: HexKey? = null,
    /** What a Lightning request asks for, or null for a boarding address. */
    val invoiceAmountSat: Long? = null,
    /** The operator's answer to a Lightning request, whose amount this device has read. Nothing can prove where it pays: spike 6. */
    val invoice: String? = null,
    val paymentHash: HexKey? = null,
)

/**
 * A boarding coin this device signed into a round it hasn't seen happen: it becomes a deposit when the round does.
 * [atEpochSeconds] is when it signed; null on a row from before it was kept.
 */
@Entity(primaryKeys = ["wallet", "commitment", "outpoint"])
data class GroupArkBoarded(
    val wallet: String,
    val outpoint: String,
    val commitment: String,
    val amountSat: Long,
    val script: HexKey,
    val atEpochSeconds: Long? = null,
)

/**
 * A transaction paying one of this wallet's boarding addresses, as this device's own connection to the chain has seen it, before any
 * round has taken it in: `docs/group-wavelength-unconfirmed-deposits.md`.
 *
 * **Not a deposit** (A2). It becomes one when a round this device signed for happens, and until then it is money on the chain that the
 * wallet cannot spend: `GroupWavelengthPayouts.balancesIn` reads the deposit ledger with no filter on confirmation, so a row filed
 * there would pay a member for a transaction that can still be replaced -- and would cut every other member's share while it stood.
 *
 * A table rather than memory, because a history needs a time that survives a restart, and because the receive screen's QR has to stay
 * down after one or somebody pays the address twice (A3).
 */
@Entity(primaryKeys = ["wallet", "outpoint"])
data class GroupArkArriving(
    val wallet: String,
    /** `DepositKey.Onchain(txid, vout).text`, the same string a boarded row and a deposit row use, so one id spans all four states (A4). */
    val outpoint: String,
    val amountSat: Long,
    /** The boarding script it paid, which this device had already recomputed as the group's before the address was shown. */
    val script: HexKey,
    /** Whose address it paid, from the answered request; null for a script no request of this device's claims. */
    val member: HexKey? = null,
    /** When this device first saw it. The only time a transaction in the mempool has, and the reason this is a table (A3). */
    val firstSeenAtEpochSeconds: Long,
    /** The block it is in, or null while it is in the mempool. The depth is worked out against the tip, never stored (A6). */
    val blockHeight: Long? = null,
    /**
     * When this device saw the output spent with no round of its own behind it: the coin was boarded by a round this device didn't
     * sign, so it will never file a deposit for it (A12). Kept rather than dropped, because that is the one fact this wallet's
     * co-signing exists to make visible.
     */
    val spentUnseenAtEpochSeconds: Long? = null,
)

/**
 * A Lightning payment this device has signed the claim of and not seen settle. [amountSat] is what the claim itself keeps,
 * and [atEpochSeconds] when it signed; null on a row from before it was kept.
 */
@Entity(primaryKeys = ["wallet", "ark"])
data class GroupArkClaim(
    val wallet: String,
    val ark: String,
    val paymentHash: HexKey,
    val amountSat: Long,
    val atEpochSeconds: Long? = null,
)

/** How far one participant says it has answered. */
@Entity(primaryKeys = ["wallet", "participant"])
data class GroupArkMark(val wallet: String, val participant: HexKey, val highest: Long, val highestChild: Long)
