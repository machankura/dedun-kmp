package com.machankura.dedun.compose.network

import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.text.LightningAddress
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.phoenix.data.lnurl.Lnurl
import fr.acinq.phoenix.data.lnurl.LnurlPay
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.http.URLBuilder
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Asks a Lightning address's server what it accepts: `docs/group-payout-destination.md`, D5 and D6.
 *
 * The app's own, on the app's own engine, rather than the fork's `LnurlManager`, which needs a
 * `PhoenixBusiness` that a profile without a node does not have. The fork's pure parser reads the
 * answer (Spike A); this class only fetches it, and keeps the fetch narrow:
 *
 * - **Tor on, no request.** The app has no Tor transport for HTTP, and a clearnet GET tells the
 *   domain's owner who is about to be paid at that name.
 * - **https only, and no other host.** A redirect is followed to the same host only, at most
 *   [MAX_REDIRECTS] times.
 * - **A bounded answer.** [MAX_BODY_BYTES] is far more than any pay request needs.
 */
class LightningAddressResolver(base: HttpClient = HttpClient(platformHttpClientEngineFactory())) {

    sealed interface Resolution {
        data class Resolved(val intent: LnurlPay.Intent) : Resolution {
            val sendable: Sendable get() = Sendable(intent.minSendable, intent.maxSendable)
        }
        /** Tor is on, and this device cannot reach the server through it: nothing was asked. */
        data object NotCheckedTor : Resolution
        /** No answer: no such domain, no connection, or too slow. */
        data object Unreachable : Resolution
        /** The server answered, but has no such name. */
        data object NotFound : Resolution
        /** The server's own `{"status": "ERROR"}`, with its reason when it gave one. */
        data class Refused(val reason: String?) : Resolution
        /** An LNURL, but not a pay request. */
        data object NotAPayRequest : Resolution
        /** An answer that is not a pay request anybody could pay: bad JSON, an http callback, a range that is not one, a redirect elsewhere, too long. */
        data class Malformed(val reason: String) : Resolution
    }

    private val client = base.config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MILLIS
            connectTimeoutMillis = TIMEOUT_MILLIS
            socketTimeoutMillis = TIMEOUT_MILLIS
        }
    }

    suspend fun resolve(address: LightningAddress, torEnabled: Boolean): Resolution {
        if (torEnabled) return Resolution.NotCheckedTor
        val origin = Url(address.wellKnownUrl)

        var url = origin
        var response: HttpResponse
        var redirects = 0
        while (true) {
            response = try {
                client.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return Resolution.Unreachable
            }
            if (response.status.value !in 300..399) break
            val location = response.headers[HttpHeaders.Location] ?: return Resolution.Malformed("a redirect to nowhere")
            val next = runCatching { URLBuilder(url).takeFrom(location).build() }.getOrNull() ?: return Resolution.Malformed("a redirect that is not a URL")
            if (next.protocol != URLProtocol.HTTPS || next.host != origin.host) return Resolution.Malformed("a redirect to ${next.host}")
            if (++redirects > MAX_REDIRECTS) return Resolution.Malformed("too many redirects")
            url = next
        }

        val bytes = try {
            response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return Resolution.Unreachable
        }
        if (bytes.size > MAX_BODY_BYTES) return Resolution.Malformed("an answer longer than ${MAX_BODY_BYTES / 1024} KiB")

        val json: JsonObject? = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
        // LUD-06's error shape first: a server that says why it will not answer is not malformed.
        val status = json?.get("status")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (status.equals("ERROR", ignoreCase = true)) {
            return Resolution.Refused(json?.get("reason")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.take(MAX_REASON_LENGTH))
        }
        if (response.status == HttpStatusCode.NotFound) return Resolution.NotFound
        if (json == null) return Resolution.Malformed("not JSON")
        val tag = json["tag"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (tag != Lnurl.Tag.Pay.label) return Resolution.NotAPayRequest

        return when (val lnurl = runCatching { Lnurl.parseLnurlJson(origin, json) }.getOrElse { return Resolution.Malformed(it::class.simpleName ?: "unreadable") }) {
            is LnurlPay.Intent -> {
                if (lnurl.callback.protocol != URLProtocol.HTTPS) return Resolution.Malformed("an http callback")
                if (lnurl.minSendable.msat <= 0 || lnurl.minSendable > lnurl.maxSendable) return Resolution.Malformed("a range that is not one")
                Resolution.Resolved(lnurl)
            }
            else -> Resolution.NotAPayRequest
        }
    }

    /** What a pay request's callback answered for one amount: `docs/group-payout-destination.md`, Phase 7. */
    sealed interface InvoiceAnswer {
        data class Invoice(val invoice: Bolt11Invoice, val bolt11: String) : InvoiceAnswer
        /** Tor is on: nothing was asked. */
        data object NotCheckedTor : InvoiceAnswer
        data object Unreachable : InvoiceAnswer
        data class Refused(val reason: String?) : InvoiceAnswer
        /** An invoice for an amount other than the one asked for (LUD-06). */
        data object WrongAmount : InvoiceAnswer
        /** An invoice whose description hash is not the hash of the address's metadata (LUD-06). */
        data object WrongDescription : InvoiceAnswer
        data class Malformed(val reason: String) : InvoiceAnswer
    }

    /**
     * Asks [intent]'s callback for an invoice of [amount], under the same rules as [resolve]: no
     * request at all while Tor is on, https only, and a bounded answer. The invoice must be for
     * exactly [amount] and commit to the metadata the intent showed -- LUD-06's two checks. That it
     * pays the person behind the address is not something anybody can check: the server signs nothing.
     */
    suspend fun requestInvoice(intent: LnurlPay.Intent, amount: MilliSatoshi, torEnabled: Boolean): InvoiceAnswer {
        if (torEnabled) return InvoiceAnswer.NotCheckedTor
        if (intent.callback.protocol != URLProtocol.HTTPS) return InvoiceAnswer.Malformed("an http callback")
        val url = URLBuilder(intent.callback).apply { parameters.append("amount", amount.msat.toString()) }.build()
        val response = try {
            client.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return InvoiceAnswer.Unreachable
        }
        if (response.status.value in 300..399) return InvoiceAnswer.Malformed("a redirect")
        val bytes = try {
            response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return InvoiceAnswer.Unreachable
        }
        if (bytes.size > MAX_BODY_BYTES) return InvoiceAnswer.Malformed("an answer longer than ${MAX_BODY_BYTES / 1024} KiB")
        val json = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
            ?: return InvoiceAnswer.Malformed("not JSON")
        val status = json["status"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (status.equals("ERROR", ignoreCase = true)) {
            return InvoiceAnswer.Refused(json["reason"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.take(MAX_REASON_LENGTH))
        }
        val bolt11 = json["pr"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return InvoiceAnswer.Malformed("no invoice")
        val invoice = runCatching { Bolt11Invoice.read(bolt11).get() }.getOrNull() ?: return InvoiceAnswer.Malformed("an invoice that does not read")
        if (invoice.amount != amount) return InvoiceAnswer.WrongAmount
        if (invoice.descriptionHash != Crypto.sha256(intent.metadata.raw.encodeToByteArray()).byteVector32()) return InvoiceAnswer.WrongDescription
        return InvoiceAnswer.Invoice(invoice, bolt11)
    }

    /** An invoice for paying a member's Lightning address, or why there is none: Phase 7. */
    sealed interface PayoutInvoice {
        /**
         * [bolt11], and the metadata [bolt11]'s description hash commits to, which a payout carries so
         * every signer can see it names the member's address (`docs/payouts-only.md`, D8).
         */
        data class Ready(val bolt11: String, val metadata: String? = null) : PayoutInvoice
        data object NotCheckedTor : PayoutInvoice
        /** The amount is outside what the address said it takes, just now. */
        data class OutOfRange(val sendable: Sendable) : PayoutInvoice
        /** An invoice on another network than the group's Lightning wallet. */
        data object OtherNetwork : PayoutInvoice
        data object Unreachable : PayoutInvoice
        data class Refused(val reason: String?) : PayoutInvoice
        /** An invoice for another amount, or for metadata other than the address's. */
        data object Mismatched : PayoutInvoice
        data object Malformed : PayoutInvoice
    }

    /**
     * An invoice of [amount] from [address], for a group Lightning wallet on [chain]: the address
     * resolved again -- what it takes may have changed since the member set it -- the amount held to
     * that range, the callback asked, and the answer held to LUD-06 and to [chain].
     */
    suspend fun invoiceFor(address: LightningAddress, amount: MilliSatoshi, chain: Chain, torEnabled: Boolean): PayoutInvoice {
        val intent = when (val resolution = resolve(address, torEnabled)) {
            is Resolution.Resolved -> resolution.intent
            Resolution.NotCheckedTor -> return PayoutInvoice.NotCheckedTor
            Resolution.Unreachable -> return PayoutInvoice.Unreachable
            is Resolution.Refused -> return PayoutInvoice.Refused(resolution.reason)
            Resolution.NotFound, Resolution.NotAPayRequest, is Resolution.Malformed -> return PayoutInvoice.Malformed
        }
        if (amount < intent.minSendable || amount > intent.maxSendable) return PayoutInvoice.OutOfRange(Sendable(intent.minSendable, intent.maxSendable))
        return when (val answer = requestInvoice(intent, amount, torEnabled)) {
            is InvoiceAnswer.Invoice -> if (answer.invoice.chain != chain) PayoutInvoice.OtherNetwork else PayoutInvoice.Ready(answer.bolt11, intent.metadata.raw)
            InvoiceAnswer.NotCheckedTor -> PayoutInvoice.NotCheckedTor
            InvoiceAnswer.Unreachable -> PayoutInvoice.Unreachable
            is InvoiceAnswer.Refused -> PayoutInvoice.Refused(answer.reason)
            InvoiceAnswer.WrongAmount, InvoiceAnswer.WrongDescription -> PayoutInvoice.Mismatched
            is InvoiceAnswer.Malformed -> PayoutInvoice.Malformed
        }
    }

    companion object {
        const val TIMEOUT_MILLIS: Long = 10_000
        const val MAX_REDIRECTS = 3
        const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_REASON_LENGTH = 90
    }
}
