package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.database.model.ChatMessage
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.GroupChainClients
import com.machankura.dedun.compose.wallet.GroupTransactionWatcher
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeUri
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.ServerAddress
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The group Lightning wallet end to end, on the regtest network `testing/regtest/regtest.sh up` builds:
 * four devices make the key and open the wallet, the operator's device runs a real lightning-kmp node
 * against an eclair LSP, and the group receives, pays, closes and sweeps
 * (`docs/group-lightning-wallet.md`, "The regtest run").
 *
 * Every message between the devices goes through the same managers the inbound path uses, pumped in the
 * background; the node's signatures are the group's, made by Iceberg co-signing in the room.
 *
 * Skipped unless `DEDUN_REGTEST=1`, because it needs the docker network.
 */
class GroupLightningRegtestJvmTest {
    private val enabled = System.getenv("DEDUN_REGTEST") == "1"
    private val fixture = GroupLightningFixture(chain = Chain.Regtest)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loggerFactory = LoggerFactory(StaticConfig(Severity.Info, listOf(platformLogWriter())))
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    // --- the network ------------------------------------------------------------------------------

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

    private fun mempoolSize(): Long = btc("getmempoolinfo").jsonObject["size"]!!.jsonPrimitive.long

    /** What the onchain wallet's first receive address holds, confirmed, in sats. */
    private fun receivedAtFirstAddress(): Long {
        val address = Bitcoin.addressFromPublicKeyScript(Chain.Regtest.chainHash, GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript.toByteArray()).right!!
        val result = btc("scantxoutset", "start", "[\"addr($address)\"]").jsonObject
        return (result["total_amount"]!!.jsonPrimitive.content.toBigDecimal() * 100_000_000.toBigDecimal()).toLong()
    }

    /**
     * Waits for [condition], mining a block whenever the mempool holds something, so funding and closing
     * confirm -- or every pass when [confirming], for what needs more than one confirmation, as a splice does.
     */
    private suspend fun <T> mineUntil(what: String, timeout: Duration = 3.minutes, confirming: Boolean = false, condition: suspend () -> T?): T = try {
        withTimeout(timeout) {
            var result = condition()
            while (result == null) {
                if (confirming || mempoolSize() > 0) mine(1)
                delay(1.seconds)
                result = condition()
            }
            result
        }
    } catch (e: Throwable) {
        diagnose()
        throw AssertionError("timed out waiting for $what", e)
    }

    /** What the run prints when it gets stuck: set once the group exists. */
    private var diagnose: suspend () -> Unit = {}

    // --- the group ----------------------------------------------------------------------------------

    private val pumping = Mutex()

    /** Delivers everything between the devices, as the inbound path would, until the test ends. */
    private fun pumpInBackground(all: List<GroupLightningFixture.Device>) {
        val base = fixture.dispatch
        fixture.dispatch = { to, event ->
            base(to, event)
            if (event.kind in GroupLightningEvents.CO_SIGNING) GroupLightningCoSigner.process(to.db, to.room, event, to.publicKey)
        }
        val kinds = FrostSigningEvents.ALL + GroupLightningEvents.ALL
        scope.launch {
            while (isActive) {
                runCatching { pumping.withLock { fixture.pump(all, kinds) } }.onFailure { println("pump: $it") }
                delay(100)
            }
        }
    }

    private suspend fun signed(all: List<GroupLightningFixture.Device>, proposer: GroupLightningFixture.Device, sessionId: String) =
        pumping.withLock { fixture.signSession(all, proposer, sessionId) }

    private suspend fun heardFromRoom(device: GroupLightningFixture.Device) {
        val id = randomBytes32().toHex()
        device.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = device.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        device.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = device.publicKey, publicKey = device.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
    }

    private val key = GroupLightningCoSigner.walletKey(fixture.roomId, Chain.Regtest, 0)
    private suspend fun report(device: GroupLightningFixture.Device) = GroupLightningReports.latest(device.db, key)

    /**
     * The payer pays the group [sats], and the channel the LSP opens on the fly for it confirms. It pays the
     * node's offer rather than a Bolt11 invoice: an invoice reaches the group's node through a route hint
     * naming it by a peer-id short channel id, which ACINQ's LSP resolves and public eclair does not, while
     * an offer's blinded path names the node as a wallet, which eclair funds. The app shows no offer
     * (docs/member-deposits.md, D7), so the offer is derived from the seed here since no snapshot carries
     * one: Spike B.
     */
    private suspend fun receive(all: List<GroupLightningFixture.Device>, sats: Long) {
        val offer = GroupLightningReports.offerOf(GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!)!!
        val paid = payer("payoffer", mapOf("offer" to offer, "amountMsat" to (sats * 1000).toString()))
        val paymentId = (paid as? JsonObject)?.get("paymentId")?.jsonPrimitive?.content ?: paid.jsonPrimitive.content
        mineUntil("the payer's payment of $sats sats") {
            val sent = payer("getsentinfo", mapOf("id" to paymentId)).jsonArray
            val status = sent.lastOrNull()?.jsonObject?.get("status")?.jsonObject?.get("type")?.jsonPrimitive?.content
            check(status != "failed") { "the payment failed: $sent" }
            status?.takeIf { it == "sent" }
        }
    }

    /**
     * Fails at once, naming what is missing, when the network `regtest.sh up` builds is not there: every
     * service listening, and the payer's channel to the LSP open. Without this a missing network shows up
     * as a connection error deep in the first call, or a three-minute timeout.
     */
    private fun requireNetwork() {
        val up = "Start it with testing/regtest/regtest.sh up; testing/regtest/README.md has the details."
        listOf("bitcoind" to 28443, "electrs" to 28001, "the LSP's API" to 28080, "the payer's API" to 28081).forEach { (what, port) ->
            val listening = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) } }.isSuccess
            if (!listening) throw AssertionError("The regtest network is not running: nothing answers for $what on 127.0.0.1:$port. $up")
        }
        val channels = runCatching { payer("channels").jsonArray }.getOrElse { throw AssertionError("The payer's API on 127.0.0.1:28081 does not answer (${it.message}). Restart the network with testing/regtest/regtest.sh down, then up.", it) }
        if (channels.none { it.jsonObject["state"]?.jsonPrimitive?.content == "NORMAL" }) {
            throw AssertionError("The regtest network is up but the payer has no open channel to the LSP, so `regtest.sh up` did not finish. Tear it down with testing/regtest/regtest.sh down, then run up again.")
        }
    }

    @Test
    fun `four devices open, receive, pay, close and sweep on regtest`() = runBlocking<Unit> {
        assumeTrue("set DEDUN_REGTEST=1 with testing/regtest up", enabled)
        requireNetwork()
        val lspId = lsp("getinfo").jsonObject["nodeId"]!!.jsonPrimitive.content

        // Open: the onchain wallet, the key, the Lightning wallet, and its operator.
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, Chain.Regtest, lspId).id)
        fixture.signSession(all, all[1], GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, Chain.Regtest).id)
        val operator = all[1]
        val wallet = GroupLightningWalletManager.walletFor(operator.db, fixture.roomId, Chain.Regtest)!!
        pumpInBackground(all)
        diagnose = {
            val dao = operator.db.icebergCoSigningDao()
            dao.assignments(key).forEach { println("REGTEST assigned ${it.counter} ${it.label.take(16)} ${it.purpose}") }
            for (c in 0L..6L) println("REGTEST pool $c: ${dao.noncesFor(key, c).size} rows, label ${GroupLightningCoSigner.poolLabel(wallet.statement.icebergKey, c).toHex().take(16)}")
        }

        val electrum = ElectrumClient(scope, loggerFactory)
        assertTrue(electrum.connect(ServerAddress("127.0.0.1", 28001, TcpSocket.TLS.DISABLED), TcpSocket.Builder()), "electrs is reachable")
        val clients = GroupChainClients.single(electrum)
        val ctx = PlatformContext(Files.createTempDirectory("dedun-regtest").toFile())
        val walletParams = LSP.from(Chain.Testnet3).walletParams.copy(trampolineNode = NodeUri(PublicKey.fromHex(lspId), "127.0.0.1", 29735))
        val handles = mutableListOf<GroupLightningNode.PeerHandle>()
        val real = GroupLightningNode.lightningKmpPeers(
            clients = clients,
            databasesFor = { nodeId, chain ->
                PhoenixDatabases(
                    channels = createSqliteChannelsDb(createChannelsDbDriver(ctx, "group-channels-$nodeId-${chain.name}.sqlite")),
                    payments = createSqlitePaymentsDb(createPaymentsDbDriver(ctx, "group-payments-$nodeId-${chain.name}.sqlite") {}, null, loggerFactory),
                    cloudKit = null,
                )
            },
            walletParamsFor = { _, _ -> walletParams },
            loggerFactory = loggerFactory,
            scope = scope,
        )
        heardFromRoom(operator)
        val node = GroupLightningNode(operator.db, operator.room, operator.publicKey, wallet, scope, { c -> real(c).also { handles += it } }, handoverCheck = 1.seconds, reportEvery = 5.seconds)
        assertIs<GroupLightningNode.State.Running>(node.start())
        mineUntil("the LSP to see the group's node", 1.minutes) {
            lsp("peers").jsonArray.firstOrNull { it.jsonObject["nodeId"]?.jsonPrimitive?.content == wallet.statement.nodeId && it.jsonObject["state"]?.jsonPrimitive?.content == "CONNECTED" }
        }

        // The operator sees the link as it is (docs/lsp-connection-status.md, Phase 6): connected once the LSP's
        // init is in, unreachable when the LSP hangs up, and back after the backoff -- or at once, on Try now.
        fun link() = (node.status.value as? GroupLightningNodeStatus.Running)?.link
        mineUntil("the operator's status to say connected", 1.minutes) { link() as? LspLink.Connected }
        lsp("disconnect", mapOf("nodeId" to wallet.statement.nodeId))
        val dropped = mineUntil("the operator's status to say unreachable", 1.minutes) { link() as? LspLink.Unreachable }
        println("REGTEST the LSP's disconnect read as ${dropped.drop}, failures ${dropped.failures}")
        assertEquals(1, dropped.failures)
        mineUntil("the node to reconnect after its backoff", 1.minutes) { link() as? LspLink.Connected }
        lsp("disconnect", mapOf("nodeId" to wallet.statement.nodeId))
        val again = mineUntil("the operator's status to say unreachable again", 1.minutes) { link() as? LspLink.Unreachable }
        node.reconnectNow()
        mineUntil("the node to reconnect on Try now", 1.minutes) { link() as? LspLink.Connected }
        assertTrue(Clock.System.now() < again.retryAt, "Try now dialled before the backoff would have")
        mineUntil("the LSP to see the group's node again", 1.minutes) {
            lsp("peers").jsonArray.firstOrNull { it.jsonObject["nodeId"]?.jsonPrimitive?.content == wallet.statement.nodeId && it.jsonObject["state"]?.jsonPrimitive?.content == "CONNECTED" }
        }

        // Receive: a depositor's Bolt11 request comes back as an invoice for the group's node...
        val destination = fixture.openDeposits(all, all[3])
        val requestId = GroupLightningReports.requestInvoice(all[3].db, all[3].room, all[3].publicKey, wallet, 100_000_000, "first")
        val invoice = Bolt11Invoice.read(mineUntil("the operator's invoice", 1.minutes) { all[3].db.groupLightningRecordsDao().invoice(key, requestId)?.bolt11 }).get()
        assertEquals(wallet.statement.nodeId, invoice.nodeId.toString())
        // ...and the first payment, to the group's offer, opens the channel on the fly.
        receive(all, 100_000)
        // The balance, not only the payment: lightning-kmp reports a payment received as it fulfils the HTLC,
        // before the commitment that moves the amount to our side is signed, and a snapshot in between shows
        // a Normal channel, a received payment and nothing of ours.
        val received = mineUntil("every member's snapshot of the channel") {
            all.map { report(it) }.takeIf { reports ->
                reports.all {
                    val channel = it?.snapshot?.channels?.singleOrNull()
                    channel?.state == "Normal" && channel.oursMsat > 0 && it.snapshot.payments.any { p -> p.incoming && p.succeeded }
                }
            }
        }
        val channel = received.first()!!.snapshot.channels.single()
        println("REGTEST received: channel ${channel.channelId.take(16)} capacity ${channel.capacitySat}, ours ${channel.oursMsat} msat")
        assertTrue(channel.oursMsat in 90_000_000..100_000_000, "the group holds the payment, less the LSP's fee: ${channel.oursMsat}")
        assertTrue(received.count { it!!.checks.single().agrees == true } >= 2, "the members who co-signed agree with the operator")

        // The offer's payment is nobody's deposit -- no invoice of a depositor's explains it -- until the
        // group signs whose it is (docs/member-deposits.md, Spike B and Phase 9).
        val offerPaid = mineUntil("the offer's payment on file as a deposit") {
            DepositLedger.depositsIn(all[0].db, fixture.roomId, fixture.chain).singleOrNull { it.source == com.machankura.dedun.compose.database.model.DepositRow.LIGHTNING }
        }
        assertEquals(null, offerPaid.member, "an offer's payment is nobody's")
        signed(all, all[0], DepositLedger.proposeAttribution(all[0].db, all[0].room, all[0].publicKey, fixture.chain, mapOf(offerPaid.depositKey to all[3].publicKey)).id)
        all.forEach { d ->
            assertEquals(offerPaid.amountMsat, DepositLedger.tallies(d.db, fixture.roomId, fixture.chain).members[all[3].publicKey]?.lightningMsat, "device ${d.index}'s tally")
        }

        // Buy liquidity (docs/group-lightning-liquidity.md, Phase 8): a member prices 200,000 sats of inbound
        // from the operator's snapshot, the group authorises it, the operator's node splices, and every
        // co-signer checks the first commitment on the new output and the shared input before the group signs.
        val priced = mineUntil("the LSP's prices in a member's snapshot", 1.minutes) { report(all[3])?.snapshot?.takeIf { it.fundingRates != null && it.feeratePerByte != null } }
        val price = com.machankura.dedun.compose.wallet.lightning.LiquidityPrice.price(priced.fundingRates!!, 200_000, priced.feeratePerByte!!)!!
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channel.channelId, channel.capacitySat, 200_000, com.machankura.dedun.compose.wallet.lightning.LiquidityPrice.defaultCap(price)).getOrThrow()
        // Every signer refuses a purchase now (docs/payouts-only.md, D9): one signed before the rule,
        // filed as signed, is what the operator's node still makes.
        GroupLightningAuthorisationEvent.template(buy).let { fixture.fileSignedAsRoom(all, it.kind, it.tags, it.content) }
        mineUntil("eclair to see the chain's tip", 1.minutes) { lsp("getinfo").jsonObject["blockHeight"]!!.jsonPrimitive.long.takeIf { it >= btc("getblockcount").jsonPrimitive.long } }
        node.buyAuthorised()
        val bought = mineUntil("every member to see the purchase locked", 4.minutes, confirming = true) {
            all.map { report(it) }.takeIf { reports -> reports.all { r -> r?.snapshot?.purchases?.singleOrNull()?.state == com.machankura.dedun.compose.wallet.lightning.WalletWire.LOCKED } }
        }
        val spliced = bought.first()!!.snapshot.channels.single()
        println("REGTEST bought: ${spliced.capacitySat} sats now, for ${bought.first()!!.snapshot.purchases.single().feeSat} sats; estimated ${price.totalSat}")
        assertEquals(channel.channelId, spliced.channelId, "the same channel, grown")
        assertTrue(spliced.capacitySat > channel.capacitySat + 190_000, "the channel grew by what was bought: ${spliced.capacitySat}")
        assertTrue(bought.first()!!.snapshot.purchases.single().feeSat!! <= buy.maxFeeSat)
        all.forEach { d -> assertEquals(0, fixture.lines(d, ChatMessage.TYPE_LIGHTNING_REFUSED).size, "device ${d.index} refused part of an honest purchase") }
        // And a payment the channel had no room for before arrives over it, with no on-the-fly splice.
        receive(all, 50_000)
        val after = mineUntil("the 50,000 sats in a member's snapshot") { report(all[0])?.snapshot?.channels?.singleOrNull()?.takeIf { it.oursMsat > spliced.oursMsat + 45_000_000 } }
        assertEquals(spliced.capacitySat, after.capacitySat, "no splice was needed to receive it")

        // Pay: a payout of the depositor's Lightning balance, which the group signs (docs/payouts-only.md,
        // Phase 6), and the operator's node pays. The payer stands in for the depositor's own wallet.
        val owed = payer("createinvoice", mapOf("description" to "back", "amountMsat" to "20000000")).jsonObject
        val pay = GroupLightningAuthorisations.prepare(wallet, owed["serialized"]!!.jsonPrimitive.content, null, 2_000_000).getOrThrow()
            .copy(payout = GroupLightningAuthorisationEvent.Payout(all[3].publicKey, destination))
        signed(all, all[2], GroupLightningAuthorisations.propose(all[2].db, all[2].room, all[2].publicKey, pay).id)
        mineUntil("the payer to receive 20,000 sats") {
            payer("getreceivedinfo", mapOf("paymentHash" to pay.paymentHash)).jsonObject["status"]?.jsonObject?.get("type")?.jsonPrimitive?.content?.takeIf { it == "received" }
        }
        val paid = mineUntil("the snapshot to show the payment") { report(all[0])?.takeIf { r -> r.snapshot.payments.any { !it.incoming && it.succeeded } } }
        println("REGTEST paid: ours now ${paid.snapshot.channels.single().oursMsat} msat")

        // Close: mutually, to the onchain wallet.
        val before = receivedAtFirstAddress()
        signed(all, all[0], GroupLightningAuthorisations.proposeChannelAction(all[0].db, all[0].room, all[0].publicKey, wallet, channel.channelId, force = false).id)
        val closedTo = mineUntil("the close to reach the onchain wallet") { receivedAtFirstAddress().takeIf { it > before } }
        println("REGTEST closed: ${closedTo - before} sats at the onchain wallet's first address")
        assertTrue(closedTo - before > 70_000, "the group's balance reached its onchain wallet")

        // Sweep: a second channel, force-closed, whose delayed output the group sweeps.
        receive(all, 100_000)
        val second = mineUntil("the second channel") {
            // With the payment on our side of it, or the force close would publish it still in flight.
            report(all[0])?.snapshot?.channels?.firstOrNull { it.channelId != channel.channelId && it.state == "Normal" && it.oursMsat > 0 }
        }
        signed(all, all[3], GroupLightningAuthorisations.proposeChannelAction(all[3].db, all[3].room, all[3].publicKey, wallet, second.channelId, force = true).id)
        val held = mineUntil("the force close's delayed output") { report(all[3])?.snapshot?.heldOutputs?.takeIf { it.isNotEmpty() } }
        println("REGTEST force-closed: ${held.size} output(s) to sweep, after ${held.map { it.csvDelay }} blocks")
        mine(held.maxOf { it.csvDelay }.toInt() + 1)
        val beforeSweep = receivedAtFirstAddress()
        val sweeper = all[3]
        signed(all, sweeper, GroupLightningSweeps.propose(sweeper.db, sweeper.room, sweeper.publicKey, wallet, GroupLightningSweeps.unswept(sweeper.db, fixture.roomId, report(sweeper))).id)
        val watcher = GroupTransactionWatcher(sweeper.db, clients, scope)
        val swept = mineUntil("the sweep to confirm") {
            watcher.pass()
            receivedAtFirstAddress().takeIf { it > beforeSweep }
        }
        println("REGTEST swept: ${swept - beforeSweep} sats into the onchain wallet")
        assertTrue(swept - beforeSweep > 70_000, "the sweep paid the onchain wallet")
        assertNotNull(sweeper.db.groupTransactionDao().getByChatRoomId(fixture.roomId).firstOrNull { it.confirmedAt != null || it.seenAt != null })
        assertEquals(1, held.size)

        // The room's account of all that, on every device (docs/group-lightning-transcript.md,
        // Phase 5): one line per fact however many snapshots repeated it, and no raw JSON. Waited
        // for, since the last snapshots are still reaching the members.
        val channels = setOf(channel.channelId, second.channelId)
        mineUntil("every member to be told both channels closed") {
            all.all { d -> fixture.lines(d, ChatMessage.TYPE_LIGHTNING_CHANNEL_CLOSED).mapNotNull { it.lightningFactKey }.toSet() == channels }.takeIf { it }
        }
        all.forEach { d ->
            suspend fun count(type: String) = fixture.lines(d, type).size
            d.db.chatMessageDao().getChatMessagesByChatRoomId(fixture.roomId).map { it.chatMessage }
                .filter { it.messageType in ChatMessage.LIGHTNING_TYPES || it.messageType in ChatMessage.GROUP_LIGHTNING_SIGNED_TYPES }
                .forEach { println("REGTEST line, device ${d.index}: ${it.messageType} ${it.content}") }
            assertEquals(2, count(ChatMessage.TYPE_LIGHTNING_CHANNEL_OPENED), "device ${d.index}: one opened line per channel")
            assertEquals(2, count(ChatMessage.TYPE_LIGHTNING_CHANNEL_CLOSED), "device ${d.index}: one closed line per channel")
            assertEquals(3, count(ChatMessage.TYPE_LIGHTNING_PAYMENT_RECEIVED), "device ${d.index}: the three payments in")
            assertEquals(1, count(ChatMessage.TYPE_LIGHTNING_LIQUIDITY_ADDED), "device ${d.index}: the liquidity bought")
            assertEquals(1, count(ChatMessage.TYPE_LIGHTNING_PAYMENT_SENT), "device ${d.index}: the payment out")
            assertEquals(1, count(ChatMessage.TYPE_LIGHTNING_SEED_SHARED), "device ${d.index}")
            assertEquals(1, count(ChatMessage.TYPE_LIGHTNING_INVOICE_REQUESTED), "device ${d.index}")
            assertEquals(1, count(ChatMessage.TYPE_LIGHTNING_INVOICE_ISSUED), "device ${d.index}")
            assertEquals(0, count(ChatMessage.TYPE_LIGHTNING_REFUSED), "device ${d.index}: an honest operator is never refused")
            assertEquals(0, count(ChatMessage.TYPE_LIGHTNING_EQUIVOCATION), "device ${d.index}")
            assertEquals(0, count("unsupported"), "device ${d.index}: no raw JSON in the room")
        }
        node.stop()
    }
}
