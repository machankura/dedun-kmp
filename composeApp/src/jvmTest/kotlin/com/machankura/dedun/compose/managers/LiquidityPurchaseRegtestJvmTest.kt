package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.NodeUri
import fr.acinq.lightning.blockchain.Feerates
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.ChannelFundingResponse
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.FundingSigner
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.phoenix.db.createChannelsDbDriver
import fr.acinq.phoenix.db.createPaymentsDbDriver
import fr.acinq.phoenix.db.createSqliteChannelsDb
import fr.acinq.phoenix.db.createSqlitePaymentsDb
import fr.acinq.phoenix.managers.PhoenixDatabases
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenixd.conf.LSP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Phase 0 of `docs/group-lightning-liquidity.md`, against the eclair LSP `testing/regtest/regtest.sh up`
 * builds: a stock lightning-kmp node, not a group, whose channel keys serve the funding key of index 0 at
 * every index -- design B, as a group's node will -- buys inbound liquidity by splicing, and the run
 * records three things:
 *
 * 1. whether eclair accepts a splice in which our funding key does not change (step 1);
 * 2. how long eclair waits for our signatures inside a splice, by delaying each one (step 3);
 * 3. how long two commitments stay live, from the purchase's answer to the splice locking (step 4).
 *
 * Skipped unless `BENNU_REGTEST=1`. `BENNU_SPLICE_DELAYS` takes the delays to try, in seconds,
 * comma-separated; by default 0, 10 and 25. A purchase needs two signatures, the first commitment on the
 * new output and the shared input, and eclair allows a splice `channel.quiescence-timeout`, one minute by
 * default, from quiescence: at 30 seconds a signature it disconnects as the second arrives (the run of
 * 2026-11-01, recorded in the plan's Phase 0).
 */
class LiquidityPurchaseRegtestJvmTest {
    private val enabled = System.getenv("BENNU_REGTEST") == "1"
    private val delays = (System.getenv("BENNU_SPLICE_DELAYS") ?: "0,10,25").split(",").map { it.trim().toInt().seconds }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loggerFactory = LoggerFactory(StaticConfig(Severity.Warn, listOf(platformLogWriter())))
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun close() = scope.cancel()

    private fun post(url: String, auth: String, form: Map<String, String> = emptyMap(), body: String? = null, contentType: String = "application/x-www-form-urlencoded"): JsonElement {
        val payload = body ?: form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI(url))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray()))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "$url ${form.keys}: ${response.statusCode()} ${response.body()}" }
        return json.parseToJsonElement(response.body())
    }

    private fun lsp(method: String, form: Map<String, String> = emptyMap()) = post("http://127.0.0.1:28080/$method", ":lsp", form)
    private fun payer(method: String, form: Map<String, String> = emptyMap()) = post("http://127.0.0.1:28081/$method", ":payer", form)

    private fun btc(method: String, vararg params: Any, wallet: String? = null): JsonElement {
        val args = params.joinToString(",") { if (it is String && !it.startsWith("[")) "\"$it\"" else it.toString() }
        val response = post("http://127.0.0.1:28443/" + (wallet?.let { "wallet/$it" } ?: ""), "foo:bar", body = """{"jsonrpc":"1.0","id":1,"method":"$method","params":[$args]}""", contentType = "text/plain").jsonObject
        check(response["error"].toString() == "null") { "bitcoind $method: ${response["error"]}" }
        return response["result"]!!
    }

    private fun mine(blocks: Int = 1) {
        btc("generatetoaddress", blocks, btc("getnewaddress", wallet = "miner").jsonPrimitive.content, wallet = "miner")
    }

    /** Waits for [condition], mining a block whenever the mempool holds something, or every second when [confirming]. */
    private suspend fun <T> mineUntil(what: String, timeout: Duration = 3.minutes, confirming: Boolean = false, condition: suspend () -> T?): T = try {
        withTimeout(timeout) {
            var result = condition()
            while (result == null) {
                if (confirming || btc("getmempoolinfo").jsonObject["size"]!!.jsonPrimitive.long > 0) mine(1)
                delay(1.seconds)
                result = condition()
            }
            result
        }
    } catch (e: Throwable) {
        throw AssertionError("timed out waiting for $what", e)
    }

    /**
     * Waits for eclair to see the chain's tip. Mining a block a second outruns it, and it refuses an
     * interactive transaction whose lock time, our node's height, is past its own.
     */
    private suspend fun lspCaughtUp() = mineUntil("eclair to see the chain's tip", 1.minutes) {
        val tip = btc("getblockcount").jsonPrimitive.long
        lsp("getinfo").jsonObject["blockHeight"]!!.jsonPrimitive.long.takeIf { it >= tip }
    }

    /**
     * The funding signer a stock channel would have at index 0, served at every index, and made to wait
     * [delay] before each signature while [delay] is set: a group's node signs the same way, slower.
     */
    private class OneKeySlowly(private val underlying: FundingSigner, private val delay: () -> Duration, private val splices: MutableList<FundingSigner.SigningContext.Splice>) : FundingSigner by underlying {
        override suspend fun signWithFreshNonce(tx: Transactions.ChannelSpendTransaction, remoteFundingPubKey: PublicKey, extraUtxos: Map<OutPoint, TxOut>, fundingTxId: fr.acinq.bitcoin.TxId, remoteNonce: IndividualNonce, context: FundingSigner.SigningContext) =
            delay(delay()).let { underlying.signWithFreshNonce(tx, remoteFundingPubKey, extraUtxos, fundingTxId, remoteNonce, context) }
        override suspend fun signWithPublishedNonce(tx: Transactions.ChannelSpendTransaction, remoteFundingPubKey: PublicKey, extraUtxos: Map<OutPoint, TxOut>, session: FundingSigner.PublishedNonceSession, remoteNonce: IndividualNonce, context: FundingSigner.SigningContext) =
            delay(delay()).let {
                if (context is FundingSigner.SigningContext.Splice) splices += context
                underlying.signWithPublishedNonce(tx, remoteFundingPubKey, extraUtxos, session, remoteNonce, context)
            }
        override suspend fun signWithVerificationNonce(tx: Transactions.ChannelSpendTransaction, remoteFundingPubKey: PublicKey, extraUtxos: Map<OutPoint, TxOut>, id: FundingSigner.VerificationNonceId, remoteNonce: IndividualNonce, context: FundingSigner.SigningContext) =
            delay(delay()).let { underlying.signWithVerificationNonce(tx, remoteFundingPubKey, extraUtxos, id, remoteNonce, context) }
    }

    private class OneKeyManager(private val delegate: KeyManager, private val delay: () -> Duration) : KeyManager by delegate {
        /** What each splice's shared input was said to be: Phase 2's context, as a real purchase fills it. */
        val splices = mutableListOf<FundingSigner.SigningContext.Splice>()
        override fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys {
            val keys = delegate.channelKeys(fundingKeyPath)
            return keys.withFundingSigner(OneKeySlowly(FundingSigner.PrivateKeyFundingSigner(keys.fundingKey(0)), delay, splices))
        }
    }

    private fun Peer.normal(): Normal? = channelsFlow.value.values.singleOrNull() as? Normal

    @Test
    fun `a node that keeps its funding key buys liquidity from eclair, and how long eclair waits`() = runBlocking<Unit> {
        assumeTrue("set BENNU_REGTEST=1 with testing/regtest up", enabled)
        val lspId = PublicKey.fromHex(lsp("getinfo").jsonObject["nodeId"]!!.jsonPrimitive.content)

        var signingDelay = Duration.ZERO
        val keyManager = OneKeyManager(GroupLightningCoSigner.keyManagerOf(ByteVector(randomBytes32().toByteArray()), Chain.Regtest)) { signingDelay }
        val nodeParams = NodeParams(Chain.Regtest, loggerFactory, keyManager).copy(
            zeroConfPeers = setOf(lspId),
            liquidityPolicy = MutableStateFlow(LiquidityPolicy.Auto(inboundLiquidityTarget = null, maxAbsoluteFee = Satoshi(20_000), maxRelativeFeeBasisPoints = 50_00, skipAbsoluteFeeCheck = false, maxAllowedFeeCredit = 0.msat)),
        )
        val electrum = ElectrumClient(scope, loggerFactory)
        check(electrum.connect(ServerAddress("127.0.0.1", 28001, TcpSocket.TLS.DISABLED), TcpSocket.Builder())) { "electrs is not reachable: testing/regtest/regtest.sh up" }
        val ctx = PlatformContext(Files.createTempDirectory("bennu-liquidity").toFile())
        val databases = PhoenixDatabases(
            channels = createSqliteChannelsDb(createChannelsDbDriver(ctx, "channels.sqlite")),
            payments = createSqlitePaymentsDb(createPaymentsDbDriver(ctx, "payments.sqlite") {}, null, loggerFactory),
            cloudKit = null,
        )
        val walletParams = LSP.from(Chain.Testnet3).walletParams.copy(trampolineNode = NodeUri(lspId, "127.0.0.1", 29735))
        val peer = Peer(nodeParams, walletParams, electrum, ElectrumWatcher(electrum, scope, loggerFactory), databases, TcpSocket.Builder(), scope)
        peer.currentTipFlow.value = electrum.startHeaderSubscription().blockHeight
        val feerate = FeeratePerByte(Satoshi(2))
        peer.onChainFeeratesFlow.value = OnChainFeerates(Feerates(feerate, feerate, feerate, feerate, feerate))
        check(peer.connect(connectTimeout = 5.seconds, handshakeTimeout = 10.seconds)) { "could not connect to the LSP" }

        // A channel, by on-the-fly funding of a payment to the node's offer; confirmed, since eclair splices
        // only a channel whose funding transaction has locked.
        val offer = nodeParams.defaultOffer(lspId).offer.encode()
        payer("payoffer", mapOf("offer" to offer, "amountMsat" to "100000000"))
        mineUntil("the on-the-fly channel") { peer.normal() }
        mineUntil("the on-the-fly channel to confirm", confirming = true) { peer.normal()?.takeIf { it.commitments.latest.localFundingStatus is fr.acinq.lightning.channel.LocalFundingStatus.ConfirmedFundingTx } }
        val inboundAtOpen = peer.normal()!!.commitments.latest.localCommit.spec.toRemote
        val rates = mineUntil("the LSP's funding rates") { peer.remoteFundingRates.value }
        println("PHASE0 the LSP sells: ${rates.fundingRates.joinToString { "${it.minAmount}..${it.maxAmount} base ${it.feeBase} ${it.feeProportional}bp weight ${it.fundingWeight} creation ${it.channelCreationFee}" }}, paid by ${rates.paymentTypes}")

        val results = mutableListOf<String>()
        for (wait in delays) {
            lspCaughtUp()
            val before = peer.normal()!!.commitments.latest
            val amount = Satoshi(200_000)
            val rate = assertNotNull(rates.findRate(amount))
            val (actualFeerate, fees) = assertNotNull(peer.estimateFeeForInboundLiquidity(amount, FeeratePerKw(feerate), rate))
            signingDelay = wait
            val started = TimeSource.Monotonic.markNow()
            val response = withTimeout(10.minutes) { peer.requestInboundLiquidity(amount, actualFeerate, rate) }
            val answeredAfter = started.elapsedNow()
            signingDelay = Duration.ZERO
            if (response !is ChannelFundingResponse.Success) {
                results += "delay $wait: refused after $answeredAfter, $response"
                println("PHASE0 ${results.last()}")
                // Whatever eclair did, get the channel back to Normal and quiescent-free before the next try.
                mineUntil("the channel to settle after the refused splice", 2.minutes) { peer.normal()?.takeIf { it.spliceStatus is fr.acinq.lightning.channel.SpliceStatus.None } }
                continue
            }
            // The answer comes as the splice is signed; the channel's state shows it once both sides sent tx_signatures.
            val after = mineUntil("the splice in the channel's state", 1.minutes) { peer.normal()?.commitments?.latest?.takeIf { it.fundingTxIndex == response.fundingTxIndex } }
            // Step 1: our half of the new funding output is the key of index 0 again, and eclair's half changed.
            val ourKey = keyManager.channelKeys(peer.normal()!!.commitments.channelParams.localParams.fundingKeyPath).fundingPublicKey(0)
            assertEquals(before.fundingTxIndex + 1, after.fundingTxIndex)
            assertNotEquals(before.remoteFundingPubkey, after.remoteFundingPubkey, "eclair did not rotate its key")
            assertEquals(Transactions.makeFundingScript(ourKey, after.remoteFundingPubkey, Transactions.CommitmentFormat.SimpleTaprootChannels).pubkeyScript, after.localFundingStatus.txOut.publicKeyScript)
            // Phase 2: the shared input was told it was this purchase.
            val told = keyManager.splices.last()
            assertEquals(after.fundingTxIndex, told.fundingTxIndex)
            assertEquals(amount, told.purchase?.amount)
            assertEquals(amount, told.remoteContribution)
            // What the group pays comes out two ways. Our share of the splice's mining fee leaves the funding
            // output (a negative contribution); the purchase's fees -- service, and the mining fee for the
            // LSP's inputs -- move from our balance to the LSP's in the first commitment on the new output.
            // Together they are the estimate.
            val purchase = assertNotNull(told.purchase)
            assertEquals(before.fundingAmount + amount + told.localContribution, response.capacity, "the capacity grew by what was bought, less our own share of the mining fee")
            assertEquals(fees.total, purchase.fees.total - told.localContribution, "the estimate is our mining share plus the purchase's fees")
            assertEquals(fees.serviceFee, purchase.fees.serviceFee)
            // Step 4: how long two commitments stay live.
            val live = TimeSource.Monotonic.markNow()
            mineUntil("the splice to lock", confirming = true) { peer.normal()?.takeIf { it.commitments.active.size == 1 && it.commitments.latest.fundingTxIndex == after.fundingTxIndex } }
            results += "delay $wait: bought ${response.liquidityPurchase?.amount} for ${fees.total} (mining ${fees.miningFee}, service ${fees.serviceFee}), answered after $answeredAfter, two commitments live for ${live.elapsedNow()}"
            println("PHASE0 ${results.last()}")
        }
        println("PHASE0 summary:\n" + results.joinToString("\n"))
        // The one claim the rest of the plan rests on: with no delay, eclair takes the splice.
        assertIs<String>(results.first { it.startsWith("delay 0s") }.takeIf { it.contains("bought") }, "eclair refused a splice with an unchanged funding key: ${results.first()}")

        // And the spliced channel carries a payment it had no room for before the purchases: the on-the-fly
        // channel left a few thousand sats of inbound. Not more than 100,000: the channel's in-flight maximum
        // was fixed at its opening, at the 100,000 sats it then held, and a splice does not raise it -- eclair
        // refuses a bigger HTLC with "in-flight htlcs hold too much value".
        val received = peer.normal()!!.commitments.latest.localCommit.spec.toLocal
        payer("payoffer", mapOf("offer" to offer, "amountMsat" to "90000000"))
        mineUntil("a 90,000-sat payment over the spliced channel", 2.minutes) { peer.normal()?.takeIf { it.commitments.latest.localCommit.spec.toLocal > received + 85_000_000.msat } }
        println("PHASE0 a 90,000-sat payment arrived; before the purchases the channel could receive ${inboundAtOpen.truncateToSatoshi()}")
        assertTrue(inboundAtOpen < 90_000_000.msat, "the payment needed the liquidity bought")
        peer.disconnect()
    }
}
