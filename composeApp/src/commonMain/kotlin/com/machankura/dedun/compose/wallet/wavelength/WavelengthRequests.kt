package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryPhase
import engineering.lightning.wavelength.model.EntryStatus
import fr.acinq.bitcoin.Satoshi

/**
 * What the wallet's own activity says has arrived against a request it issued --
 * `docs/paid-requests.md`, Phase 2.
 *
 * **Matched by the activity entry's id, not by the invoice or the address.** `Receive` and
 * `Deposit` each hand back the request *and* the entry it created, so the id is known exactly.
 * The alternative was `Entry.request`, which echoes the invoice or address back -- but the SDK
 * documents that field as "nil when the backing subsystem did not persist one", and the SDK's
 * own fake daemon never sets it at all. A screen that takes a QR code down on a field allowed
 * to be absent would take it down never, or at the daemon's discretion.
 *
 * **Stated as what has been seen, never as the absence of waiting.** The first draft negated
 * `WavelengthWalletScreen`'s `isUnpaidRequest`, which reads *waiting* off a list of phases.
 * Negating a list like that claims a payment for everything not on it: a failed receive, a
 * refund, and -- since `EntryStatus` and `EntryPhase` both carry an `UNKNOWN` for values a
 * newer daemon sends -- anything this SDK version has never heard of. Taking a QR code down is
 * a decision about money, so it is made from a phase that says a payment happened and nothing
 * else.
 */
internal object WavelengthRequests {

    /**
     * The phases that mean money has been seen.
     *
     * [EntryPhase.PAYMENT_DETECTED] is the earliest and is the one D1 turns on: the payer has
     * paid, the daemon has noticed, nothing is settled yet.
     * [EntryPhase.WAITING_FOR_CONFIRMATION] is its onchain twin, and settling and confirmed
     * come after. Refunding and refunded are deliberately absent -- money that came and went
     * is not a payment to announce.
     */
    private val SEEN = setOf(
        EntryPhase.PAYMENT_DETECTED,
        EntryPhase.WAITING_FOR_CONFIRMATION,
        EntryPhase.SETTLING,
        EntryPhase.CONFIRMED,
    )

    /**
     * What has arrived against the request whose activity entry is [entryId], or null while
     * nothing has. Null for an unknown id, so a request made before a restart -- whose entry
     * this screen never saw -- is never claimed as paid.
     */
    fun paidOn(activity: List<Entry>, entryId: String): Satoshi? {
        val entry = activity.firstOrNull { it.id == entryId } ?: return null
        if (entry.kind != EntryKind.RECEIVE && entry.kind != EntryKind.DEPOSIT) return null
        val seen = when (entry.status) {
            EntryStatus.COMPLETE -> true
            EntryStatus.PENDING -> entry.progress?.phase in SEEN
            else -> false
        }
        return if (seen) Satoshi(entry.amountSat) else null
    }
}
