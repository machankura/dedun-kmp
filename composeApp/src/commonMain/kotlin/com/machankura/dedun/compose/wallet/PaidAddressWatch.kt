package com.machankura.dedun.compose.wallet

/**
 * The address a screen was handing out, once somebody has paid it --
 * `docs/paid-requests.md`, Phase 4.
 *
 * Three screens hand out the lowest unused address of an HD branch. They are already right
 * about *which* address to show: a payment makes the branch move on, and the card redraws with
 * a fresh address. What they never did was say so, and an address changing under the reader
 * with nothing said is the one case in this plan where the money arriving is the only news --
 * the request itself needs no hiding, because the wallet has already replaced it (D7).
 *
 * Deliberately not a flow or a state: it is one field of memory beside a collector, and the
 * rule it holds is the one sentence above. [paid] is sticky until a later address is paid, so a
 * reader who looks away does not lose the announcement to the next block.
 *
 * @param addressOf reads the address out of whatever row the caller's wallet uses.
 */
class PaidAddressWatch<T>(private val addressOf: (T) -> String) {

    /** The address being handed out as of the last call, or null before the first. */
    private var handing: String? = null

    var paid: T? = null
        private set

    /**
     * Call on every emission with the address being handed out now and the ones that have been
     * paid. Returns [paid].
     *
     * Nothing is claimed on the first call: an address already paid when the screen opened is
     * old news rather than an event, and saying "payment received" for it would be a lie about
     * when (D8).
     */
    fun seen(current: String?, used: List<T>): T? {
        handing?.let { was -> used.firstOrNull { addressOf(it) == was } }?.let { paid = it }
        if (current != null) handing = current
        return paid
    }
}
