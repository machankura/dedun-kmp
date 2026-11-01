package com.machankura.dedun.compose.network

import com.machankura.dedun.compose.managers.INVOICE_FEATURES
import com.machankura.dedun.compose.network.LightningAddressResolver.PayoutInvoice
import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.text.LightningAddress
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Paying a member's Lightning address -- `docs/group-payout-destination.md`, Phase 7: the address
 * resolved again, the amount held to what it takes, and the invoice held to LUD-06 and to the group's
 * Lightning network. On a mock engine; the invoices are real.
 */
class PayoutInvoiceJvmTest {
    private val alice = LightningAddress("alice", "example.com")
    private val metadata = """[["text/plain","Pay alice"],["text/identifier","alice@example.com"]]"""
    private val node = PrivateKey(ByteVector32(Hex.decode("70de".padStart(64, '0'))))

    private fun invoice(amount: MilliSatoshi, chain: Chain = Chain.Regtest, describing: String = metadata): String =
        Bolt11Invoice.create(
            chain, amount, randomBytes32(), node,
            Either.Right(Crypto.sha256(describing.encodeToByteArray()).byteVector32()),
            CltvExpiryDelta(144), INVOICE_FEATURES,
        ).write()

    /** A server whose pay request takes 1,000 to 1,000,000 msat and answers its callback with [pr]. */
    private fun resolver(pr: (MilliSatoshi) -> String) = LightningAddressResolver(HttpClient(MockEngine { request ->
        val body = if (request.url.encodedPath.startsWith("/.well-known/")) {
            """{"tag":"payRequest","callback":"https://example.com/callback","minSendable":1000,"maxSendable":1000000,"metadata":${JsonPrimitive(metadata)}}"""
        } else {
            val amount = MilliSatoshi(request.url.parameters["amount"]!!.toLong())
            """{"pr":"${pr(amount)}","routes":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test
    fun `an invoice for the amount, describing the address's metadata, is ready`() = runBlocking {
        val answer = resolver { invoice(it) }.invoiceFor(alice, MilliSatoshi(21_000), Chain.Regtest, torEnabled = false)
        val ready = assertIs<PayoutInvoice.Ready>(answer)
        assertEquals(MilliSatoshi(21_000), Bolt11Invoice.read(ready.bolt11).get().amount)
        // The metadata the invoice commits to comes back with it, for the payout to carry.
        assertEquals(metadata, ready.metadata)
    }

    @Test
    fun `an invoice for another amount is refused`() = runBlocking {
        assertEquals(PayoutInvoice.Mismatched, resolver { invoice(MilliSatoshi(it.msat + 1_000)) }.invoiceFor(alice, MilliSatoshi(21_000), Chain.Regtest, false))
    }

    @Test
    fun `an invoice describing something else is refused`() = runBlocking {
        assertEquals(PayoutInvoice.Mismatched, resolver { invoice(it, describing = "[]") }.invoiceFor(alice, MilliSatoshi(21_000), Chain.Regtest, false))
    }

    @Test
    fun `an invoice on another network is refused`() = runBlocking {
        assertEquals(PayoutInvoice.OtherNetwork, resolver { invoice(it, chain = Chain.Testnet3) }.invoiceFor(alice, MilliSatoshi(21_000), Chain.Regtest, false))
    }

    @Test
    fun `an amount outside what the address takes is not asked for`() = runBlocking {
        var asked = false
        val answer = resolver { asked = true; invoice(it) }.invoiceFor(alice, MilliSatoshi(2_000_000), Chain.Regtest, false)
        assertEquals(PayoutInvoice.OutOfRange(Sendable(MilliSatoshi(1_000), MilliSatoshi(1_000_000))), answer)
        assertEquals(false, asked)
    }

    @Test
    fun `Tor on asks nothing`() = runBlocking {
        assertEquals(PayoutInvoice.NotCheckedTor, resolver { invoice(it) }.invoiceFor(alice, MilliSatoshi(21_000), Chain.Regtest, torEnabled = true))
    }
}
