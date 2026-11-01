package com.machankura.dedun.compose.network

import com.machankura.dedun.compose.network.LightningAddressResolver.Resolution
import com.machankura.dedun.compose.text.LightningAddress
import fr.acinq.lightning.MilliSatoshi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Each answer a Lightning address's server can give, and what the resolver makes of it, on a
 * mock engine. `docs/group-payout-destination.md`, Phase 3.
 */
class LightningAddressResolverTest {
    private val alice = LightningAddress("alice", "example.com")

    private val metadata = """[[\"text/plain\",\"Pay alice\"],[\"text/identifier\",\"alice@example.com\"]]"""

    private fun payRequest(
        callback: String = "https://example.com/lnurlp/alice/callback",
        min: Long = 1_000,
        max: Long = 100_000_000,
        tag: String = "payRequest",
    ) = """{"tag":"$tag","callback":"$callback","minSendable":$min,"maxSendable":$max,"metadata":"$metadata"}"""

    private val requests = mutableListOf<HttpRequestData>()

    private fun resolver(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        LightningAddressResolver(HttpClient(MockEngine { request -> requests += request; handler(request) }))

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun `a pay request resolves, from the well-known URL`() = runTest {
        val resolution = resolver(json(payRequest())).resolve(alice, torEnabled = false)
        val resolved = assertIs<Resolution.Resolved>(resolution)
        assertEquals(MilliSatoshi(1_000), resolved.sendable.min)
        assertEquals(MilliSatoshi(100_000_000), resolved.sendable.max)
        assertEquals("alice@example.com", resolved.intent.metadata.lnid)
        assertEquals("https://example.com/.well-known/lnurlp/alice", requests.single().url.toString())
    }

    @Test
    fun `Tor on asks nothing`() = runTest {
        assertEquals(Resolution.NotCheckedTor, resolver(json(payRequest())).resolve(alice, torEnabled = true))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `no connection is unreachable`() = runTest {
        assertEquals(Resolution.Unreachable, resolver { throw IOException("no route to host") }.resolve(alice, torEnabled = false))
    }

    @Test
    fun `a name the server does not have is not found`() = runTest {
        assertEquals(Resolution.NotFound, resolver(json("<html>not here</html>", HttpStatusCode.NotFound)).resolve(alice, torEnabled = false))
    }

    @Test
    fun `the server's own error is a refusal with its reason`() = runTest {
        val resolution = resolver(json("""{"status":"ERROR","reason":"no such user"}""", HttpStatusCode.NotFound)).resolve(alice, torEnabled = false)
        assertEquals(Resolution.Refused("no such user"), resolution)
    }

    @Test
    fun `another LNURL is not a pay request`() = runTest {
        assertEquals(Resolution.NotAPayRequest, resolver(json(payRequest(tag = "withdrawRequest"))).resolve(alice, torEnabled = false))
        assertEquals(Resolution.NotAPayRequest, resolver(json("""{"callback":"https://example.com"}""")).resolve(alice, torEnabled = false))
    }

    @Test
    fun `an http callback is malformed`() = runTest {
        assertIs<Resolution.Malformed>(resolver(json(payRequest(callback = "http://example.com/callback"))).resolve(alice, torEnabled = false))
    }

    @Test
    fun `a range that is not one is malformed`() = runTest {
        assertIs<Resolution.Malformed>(resolver(json(payRequest(min = 0))).resolve(alice, torEnabled = false))
    }

    @Test
    fun `an answer that is not JSON is malformed`() = runTest {
        assertIs<Resolution.Malformed>(resolver(json("not json")).resolve(alice, torEnabled = false))
    }

    @Test
    fun `an answer longer than the cap is malformed`() = runTest {
        val long = payRequest().dropLast(1) + ""","padding":"${"x".repeat(LightningAddressResolver.MAX_BODY_BYTES)}"}"""
        assertIs<Resolution.Malformed>(resolver(json(long)).resolve(alice, torEnabled = false))
    }

    @Test
    fun `a redirect on the same host is followed`() = runTest {
        val resolution = resolver { request ->
            if (request.url.encodedPath.startsWith("/.well-known/")) {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/lnurlp/alice"))
            } else {
                respond(payRequest(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }.resolve(alice, torEnabled = false)
        assertIs<Resolution.Resolved>(resolution)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a redirect to another host is not followed`() = runTest {
        val resolution = resolver {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://elsewhere.example/lnurlp/alice"))
        }.resolve(alice, torEnabled = false)
        assertEquals(Resolution.Malformed("a redirect to elsewhere.example"), resolution)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a redirect loop ends`() = runTest {
        val resolution = resolver {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/.well-known/lnurlp/alice"))
        }.resolve(alice, torEnabled = false)
        assertEquals(Resolution.Malformed("too many redirects"), resolution)
    }
}
