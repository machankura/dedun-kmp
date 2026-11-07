package com.machankura.dedun.compose.nostr.wavelength

import kotlinx.serialization.Serializable

/**
 * What the operator of a group's Wavelength wallet says it holds, kind 30378 (`docs/group-wavelength-ui.md`, Phase 5). All of it is a
 * claim: a member compares it with what its own device has signed, and shows both. [wallet] is the wallet's key.
 */
@Serializable
data class GroupWavelengthSnapshot(
    val wallet: String,
    val term: Long,
    val sentAt: Long,
    val tip: Long,
    val confirmedSat: Long,
    val pendingInSat: Long = 0,
    val pendingOutSat: Long = 0,
    /** Held on the swap server's ledger, which no threshold guards. Never part of a balance (D10). */
    val creditSat: Long = 0,
    val coins: List<Coin> = emptyList(),
    val activity: List<Line> = emptyList(),
) {
    @Serializable
    data class Coin(val outpoint: String, val amountSat: Long, val status: String = "", val batchExpiry: Long = 0)

    /** One thing the wallet did, in the daemon's own words for its [kind] and [status]. */
    @Serializable
    data class Line(val id: String, val kind: String, val status: String, val amountSat: Long, val feeSat: Long = 0, val at: Long = 0, val failure: String = "", val paymentHash: String = "")

    /** The same claim, whenever it was made. */
    fun sameAs(other: GroupWavelengthSnapshot): Boolean = copy(sentAt = 0, tip = 0) == other.copy(sentAt = 0, tip = 0)

    companion object {
        /** More than a wallet under the cap can hold, and a bound on what any member has to read. */
        const val MAX_COINS = 256
        const val MAX_ACTIVITY = 50
    }
}

/** An operator stopping, kind 30382: what the next one can't rebuild from the chain (D14). */
@Serializable
data class GroupWavelengthStopped(val wallet: String, val term: Long, val exitsUnderWay: Int = 0, val paymentsInFlight: Int = 0)

/** A member asking for an address to pay the wallet at, kind 30380. Who's asking is who sent it. */
@Serializable
data class GroupWavelengthDepositRequest(val requestId: String, val wallet: String)

/** The operator's answer, kind 30381, which every device checks is an address of the group's before believing it's the requester's. */
@Serializable
data class GroupWavelengthDepositAnswer(val requestId: String, val wallet: String, val term: Long, val address: String)
