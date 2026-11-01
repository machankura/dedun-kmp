package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Spike B of `docs/group-payout-destination.md`, against the electrs `testing/regtest/regtest.sh up`
 * starts: what `startScriptHashSubscription` answers for an address nobody has paid, one paid in the
 * mempool, one paid and confirmed, and when no server answers at all. D7 rests on the four answers
 * being null, a status, a status and a timeout -- unlike `getScriptHashHistory`, whose failure reads
 * as no history.
 *
 * Skipped unless `DEDUN_REGTEST=1`.
 */
class PayoutAddressRegtestJvmTest {
    private val enabled = System.getenv("DEDUN_REGTEST") == "1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loggerFactory = LoggerFactory(StaticConfig(Severity.Warn, listOf(platformLogWriter())))
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun close() = scope.cancel()

    private fun btc(method: String, vararg params: Any, wallet: String? = null): JsonElement {
        val args = params.joinToString(",") { if (it is String) "\"$it\"" else it.toString() }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:28443/" + (wallet?.let { "wallet/$it" } ?: "")))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("foo:bar".toByteArray()))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"1.0","id":1,"method":"$method","params":[$args]}"""))
            .build()
        val response = json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        check(response["error"].toString() == "null") { "bitcoind $method: ${response["error"]}" }
        return response["result"]!!
    }

    private fun newAddress(): String = btc("getnewaddress", wallet = "miner").jsonPrimitive.content

    private fun scriptHashOf(address: String): ByteVector32 {
        val script = Bitcoin.addressToPublicKeyScript(Chain.Regtest.chainHash, address).right!!
        return Crypto.sha256(Script.write(script)).byteVector32().reversed()
    }

    private suspend fun connected(): ElectrumClient {
        val electrum = ElectrumClient(scope, loggerFactory)
        check(electrum.connect(ServerAddress("127.0.0.1", 28001, TcpSocket.TLS.DISABLED), TcpSocket.Builder())) {
            "electrs is not reachable: testing/regtest/regtest.sh up"
        }
        return electrum
    }

    /** What electrs says about [address], polled until it has seen [expectSeen] or 30 seconds pass. */
    private suspend fun statusOf(electrum: ElectrumClient, address: String, expectSeen: Boolean): String? =
        withTimeout(30.seconds) {
            while (true) {
                val status = electrum.startScriptHashSubscription(scriptHashOf(address)).status
                if ((status != null) == expectSeen) return@withTimeout status
                delay(1.seconds)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    @Test
    fun anUnusedAddressHasNoStatusAndAPaidOneHasOne() = runBlocking {
        assumeTrue("set DEDUN_REGTEST=1 with testing/regtest/regtest.sh up", enabled)
        val electrum = connected()

        val fresh = newAddress()
        assertNull(electrum.startScriptHashSubscription(scriptHashOf(fresh)).status, "a fresh address")

        val mempool = newAddress()
        btc("sendtoaddress", mempool, 0.0001, wallet = "miner")
        assertNotNull(statusOf(electrum, mempool, expectSeen = true), "an address paid in the mempool")

        val confirmed = newAddress()
        btc("sendtoaddress", confirmed, 0.0001, wallet = "miner")
        btc("generatetoaddress", 1, newAddress(), wallet = "miner")
        assertNotNull(statusOf(electrum, confirmed, expectSeen = true), "an address paid and confirmed")

        electrum.disconnect()
    }

    @Test
    fun noServerIsATimeoutNotAnAnswer() = runBlocking {
        assumeTrue("set DEDUN_REGTEST=1 with testing/regtest/regtest.sh up", enabled)
        // A client that was never connected stands for a server that is down: the call waits for one,
        // so only the caller's timeout ends it, and the caller reads that as "couldn't ask".
        val electrum = ElectrumClient(scope, loggerFactory)
        val answer = withTimeoutOrNull(3.seconds) { electrum.startScriptHashSubscription(scriptHashOf(newAddress())) }
        assertNull(answer, "no server, no answer")
    }
}
