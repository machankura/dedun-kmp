package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.SwapInWallet
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.crypto.SwapInOnChainKeys
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.sat
import fr.acinq.phoenixd.conf.LSP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 7 of `docs/personal-swap-in-wallet.md`, cut down to the refund: a swap-in coin on the regtest
 * network `testing/regtest/regtest.sh up` builds is followed through every status the screen shows,
 * from the mempool to refundable, and then refunded with the user's key alone.
 *
 * No LSP takes part. The regtest LSP is open-source eclair, which has no swap-in code, so the swap
 * itself cannot run here (Phase 0, spike 3); the refund is the one path that needs nobody else. The
 * wallet is a bare [SwapInWallet] over electrs rather than a `PhoenixBusiness`, whose chain is fixed
 * to mainnet.
 *
 * It mines 144 blocks, a short refund delay (see [params]). Skipped unless `DEDUN_REGTEST=1`.
 */
class SwapInRefundRegtestJvmTest {
    private val enabled = System.getenv("DEDUN_REGTEST") == "1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loggerFactory = LoggerFactory(StaticConfig(Severity.Warn, listOf(platformLogWriter())))
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    /**
     * The production windows are 17,280 and 25,920 blocks, and this machine's bitcoind mines about eight
     * a second: the refund delay would be fifty minutes. The keys take their delay as a parameter and the
     * refund script is the same at any delay, so the run uses a day's worth.
     */
    private val params = SwapInParams(DefaultSwapInParams.MinConfirmations, maxConfirmations = 72, refundDelay = 144)

    @AfterTest
    fun close() = scope.cancel()

    /** A bitcoind call; each of [params] is already JSON. */
    private fun btc(method: String, vararg params: String, wallet: String? = null): JsonElement {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:28443/" + (wallet?.let { "wallet/$it" } ?: "")))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("foo:bar".toByteArray()))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"1.0","id":1,"method":"$method","params":[${params.joinToString(",")}]}"""))
            .build()
        val response = json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        check(response["error"].toString() == "null") { "bitcoind $method: ${response["error"]}" }
        return response["result"]!!
    }

    private fun String.quoted() = "\"$this\""
    private val miner by lazy { btc("getnewaddress", wallet = "miner").jsonPrimitive.content }
    private fun height(): Int = btc("getblockcount").jsonPrimitive.int

    /** Mines until the chain is [target] high, in chunks bitcoind answers quickly. */
    private fun mineTo(target: Int) {
        while (height() < target) btc("generatetoaddress", minOf(1_000, target - height()).toString(), miner.quoted())
    }

    /** Polls [block] until it answers, for at most [timeout]. */
    private suspend fun <T : Any> until(what: String, timeout: Duration = 2.minutes, block: suspend () -> T?): T =
        withTimeout(timeout) {
            while (true) {
                block()?.let { return@withTimeout it }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") error(what)
        }

    private fun accepted(tx: Transaction): Boolean =
        btc("testmempoolaccept", "[${Transaction.write(tx).byteVector().toHex().quoted()}]").jsonArray.single().jsonObject["allowed"]!!.jsonPrimitive.boolean

    @Test
    fun aCoinIsFollowedToRefundableAndRefunded() = runBlocking {
        assumeTrue("set DEDUN_REGTEST=1 with testing/regtest/regtest.sh up", enabled)
        val electrum = ElectrumClient(scope, loggerFactory)
        check(electrum.connect(ServerAddress("127.0.0.1", 28001, TcpSocket.TLS.DISABLED), TcpSocket.Builder())) {
            "electrs is not reachable: testing/regtest/regtest.sh up"
        }
        // The LSP's key is never asked for: nothing here swaps in. Any tpub will do for the address.
        val keys: SwapInOnChainKeys = LocalKeyManager(randomBytes32(), Chain.Regtest, LSP.from(Chain.Testnet3).swapInXpub).swapInOnChainWallet
            .copy(refundDelay = params.refundDelay)
        val wallet = SwapInWallet(Chain.Regtest, keys, electrum, scope, loggerFactory)

        val (address, index) = withTimeout(1.minutes) { wallet.swapInAddressFlow.filterNotNull().first() }
        println("REGTEST swap-in address $index $address")
        val listed = SwapInWalletView.addresses(wallet.wallet.walletStateFlow.value, wallet.legacySwapInAddress)!!
        assertEquals(address, listed.single { it.status == SwapInAddress.Status.CURRENT }.address)

        btc("sendtoaddress", address.quoted(), "0.0005", wallet = "miner")
        suspend fun coin(): SwapInCoin {
            val tip = until("electrs to reach ${height()}") { electrum.startHeaderSubscription().blockHeight.takeIf { it == height() } }
            val state = until("the wallet to see the coin") { wallet.wallet.walletStateFlow.value.takeIf { it.utxos.isNotEmpty() } }
            return SwapInWalletView.coins(state, tip, params, emptySet()).single()
        }

        assertEquals(SwapInCoin.Status.Unconfirmed, coin().status)
        assertEquals(50_000.sat, coin().amount)
        // The address has been paid, so the wallet hands out the next one.
        until("the address to rotate") { wallet.swapInAddressFlow.value?.takeIf { it.first != address } }

        btc("generatetoaddress", "1", miner.quoted())
        val mined = height()
        until("the coin to confirm") { coin().takeIf { it.confirmations == 1 } }
        assertEquals(SwapInCoin.Status.Confirming(1, 3), coin().status)

        mineTo(mined + params.minConfirmations - 1)
        // With a 72-block window every ready coin is within two weeks of its end.
        assertEquals(SwapInCoin.Status.ExpiringSoon(params.maxConfirmations - 3), until("three confirmations") { coin().takeIf { it.confirmations == 3 } }.status)

        mineTo(mined + params.maxConfirmations - 1)
        val locked = until("the swap window to close") { coin().takeIf { it.confirmations == params.maxConfirmations } }
        assertEquals(SwapInCoin.Status.Locked(params.refundDelay - params.maxConfirmations), locked.status)

        // One block short of the refund delay: built anyway, bitcoind refuses it.
        val destination = btc("getnewaddress", wallet = "miner").jsonPrimitive.content
        val script = Script.write(Bitcoin.addressToPublicKeyScript(Chain.Regtest.chainHash, destination).right!!).byteVector()
        mineTo(mined + params.refundDelay - 2)
        until("the last block before the refund") { coin().takeIf { it.confirmations == params.refundDelay - 1 } }
        val early = keys.createRecoveryTransaction(
            wallet.wallet.walletStateFlow.value.utxos.map { SwapInOnChainKeys.SwapInUtxo(it.txOut, it.outPoint, it.addressMeta.indexOrNull) },
            script,
            FeeratePerKw(FeeratePerByte(2.sat)),
        )!!
        assertFalse(accepted(early), "a refund one block early is not final")
        // Sent anyway, the server refuses it, and the check after the broadcast says so.
        assertTrue(runCatching { NodeRefunder.broadcastAndCheck(electrum, early) }.isFailure, "a refused refund does not read as sent")

        btc("generatetoaddress", "1", miner.quoted())
        assertEquals(SwapInCoin.Status.Refundable, until("the refund delay") { coin().takeIf { it.confirmations == params.refundDelay } }.status)

        assertEquals(UnusedAddressCheck.Usage.Unused, UnusedAddressCheck.check(electrum, script))
        val tip = electrum.startHeaderSubscription().blockHeight
        val preview = SwapInRefund.preview(wallet.wallet.walletStateFlow.value, tip, params, keys, script, FeeratePerKw(FeeratePerByte(2.sat)))!!
        println("REGTEST refund ${preview.tx.txid}: ${preview.refunded} less ${preview.fee}")
        assertTrue(accepted(preview.tx), "a refund at the refund delay is final")
        assertEquals(preview.tx.txid, NodeRefunder.broadcastAndCheck(electrum, preview.tx))
        btc("generatetoaddress", "1", miner.quoted())

        val received = until("the refund to confirm") {
            btc("getreceivedbyaddress", destination.quoted(), "1", wallet = "miner").jsonPrimitive.double.takeIf { it > 0 }
        }
        assertEquals(preview.received.sat, Math.round(received * 100_000_000))
        until("the wallet to see the coin spent") { wallet.wallet.walletStateFlow.value.takeIf { it.utxos.isEmpty() } }
        electrum.disconnect()
    }
}
