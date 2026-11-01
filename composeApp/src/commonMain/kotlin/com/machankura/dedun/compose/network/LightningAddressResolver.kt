package com.machankura.dedun.compose.network

import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.text.LightningAddress
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

    companion object {
        const val TIMEOUT_MILLIS: Long = 10_000
        const val MAX_REDIRECTS = 3
        const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_REASON_LENGTH = 90
    }
}
