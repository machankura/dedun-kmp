package com.machankura.dedun.compose.wallet

import fr.acinq.lightning.payment.Bolt11Invoice
import kotlin.time.Instant

/**
 * When a Lightning invoice stops being payable -- `docs/paid-requests.md`, Phase 7.
 *
 * Read from the invoice itself rather than asked of a wallet, because the answer is in the
 * bolt11 and is the same on all three screens that hand one out. Nothing stores it and nothing
 * polls for it: a screen works out the moment once and waits for it.
 */
object InvoiceExpiry {

    /**
     * The moment [bolt11] expires, or null when this app cannot tell.
     *
     * Null for anything that will not read, which is the safe answer: an invoice whose expiry is
     * unknown keeps its QR code, exactly as before this existed. That covers a malformed string
     * and also a network lightning-kmp has no prefix for -- signet among them, whose `lntbs`
     * reads as testnet's `lntb` and then fails on the amount. The wallets this is wired into run
     * on mainnet by decision, so in practice the answer is known.
     */
    fun expiresAt(bolt11: String): Instant? {
        val text = bolt11.removePrefix("lightning:").removePrefix("LIGHTNING:")
        val invoice = runCatching { Bolt11Invoice.read(text).get() }.getOrNull() ?: return null
        val expiry = invoice.expirySeconds ?: Bolt11Invoice.DEFAULT_EXPIRY_SECONDS.toLong()
        return Instant.fromEpochSeconds(invoice.timestampSeconds + expiry)
    }
}
