package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** When an invoice's window closes, read off the invoice itself. */
class InvoiceExpiryTest {

    private fun bolt11(expirySeconds: Long?, timestampSeconds: Long): String = Bolt11Invoice.create(
        chain = Chain.Regtest,
        amount = 1_250_000.msat,
        paymentHash = ByteVector32(Hex.decode("07".repeat(32))),
        privateKey = PrivateKey(ByteVector32(Hex.decode("05".repeat(32)))),
        description = Either.Left("tea"),
        minFinalCltvExpiryDelta = CltvExpiryDelta(144),
        features = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory),
        expirySeconds = expirySeconds,
        timestampSeconds = timestampSeconds,
    ).write()

    @Test
    fun `the expiry tag decides when the window closes`() {
        val made = 1_700_000_000L
        assertEquals(Instant.fromEpochSeconds(made + 60), InvoiceExpiry.expiresAt(bolt11(60, made)))
    }

    @Test
    fun `with no expiry tag bolt 11's own hour applies`() {
        val made = 1_700_000_000L
        assertEquals(Instant.fromEpochSeconds(made + 3_600), InvoiceExpiry.expiresAt(bolt11(null, made)))
    }

    @Test
    fun `a lightning uri is read as well as a bare invoice`() {
        val made = 1_700_000_000L
        val invoice = bolt11(60, made)
        assertEquals(Instant.fromEpochSeconds(made + 60), InvoiceExpiry.expiresAt("lightning:$invoice"))
        assertEquals(Instant.fromEpochSeconds(made + 60), InvoiceExpiry.expiresAt("LIGHTNING:$invoice"))
    }

    /**
     * Unknown rather than expired, so a screen that cannot read an invoice keeps showing it: the
     * cost of being wrong the other way is hiding a QR code somebody was about to pay.
     */
    @Test
    fun `anything that will not read has no expiry`() {
        assertNull(InvoiceExpiry.expiresAt(""))
        assertNull(InvoiceExpiry.expiresAt("not an invoice"))
        assertNull(InvoiceExpiry.expiresAt("lnbcrt1250n1fakeinvoice"))
        // Signet: lightning-kmp has no prefix for it, so `lntbs` matches testnet's `lntb` and
        // then fails on the amount. The wallets this is wired into are on mainnet by decision.
        assertNull(InvoiceExpiry.expiresAt("lntbs12500n1fake"))
    }
}
