package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupWavelengthSeed
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkRequest
import com.machankura.dedun.compose.wallet.wavelength.group.ArkResponse
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkManifest
import com.sun.net.httpserver.HttpServer
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.utils.Either
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * A harness for Phase 8 of `docs/group-wavelength-wallet.md`, not a test that runs by itself: four devices co-signing, for a real
 * daemon and a real operator on signet. The daemon is Go and its bindings are Android's, so it runs in `TestHostedBridge`, in another
 * process, and each request it makes comes here over HTTP to the same [GroupArkTransport] an elected phone would use.
 *
 * Whoever drives it starts that test on the same directory, reads what this writes, sends coins by hand, and writes a txid to
 * `settle.txt` once they have seen it confirm, which is what Phase 8's watcher will do. It runs until `stop` exists. Skipped unless
 * `DEDUN_SIGNET_DIR` names a directory. **The directory ends up holding the group's shares, so it is only for signet.**
 */
class GroupWavelengthSignetJvmTest {
    private companion object {
        /** For whatever the last second of a stopped run used and didn't get written down. */
        const val LABEL_MARGIN = 16L
    }

    @Test
    fun `four devices co-sign for a real daemon on signet`() = runBlocking<Unit> {
        val path = System.getenv("DEDUN_SIGNET_DIR")
        Assume.assumeTrue("set DEDUN_SIGNET_DIR to run against signet", !path.isNullOrEmpty())
        val dir = File(path!!).also { it.mkdirs() }
        val timeline = File(dir, "timeline.log")
        fun say(line: String) = timeline.appendText("${Clock.System.now()} $line\n")

        val terms = Json.parseToJsonElement(
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("https://signet.wavelength-rest.lightning.finance/v1/ark/get-info"))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        ).jsonObject
        val operatorKey = PublicKey(fr.acinq.bitcoin.ByteVector(Base64.getDecoder().decode(terms.getValue("pubkey").jsonPrimitive.content))).toString()
        val policy = GroupWavelengthWalletStatementEvent.Policy.DEFAULT.copy(
            exitDelay = terms.getValue("vtxo_exit_delay").jsonPrimitive.int.toLong(),
            boardingDelay = terms.getValue("boarding_exit_delay").jsonPrimitive.int.toLong(),
        )
        say("operator $operatorKey policy $policy")

        val fixture = GroupLightningFixture(chain = Chain.Signet).also { f ->
            val inherited = f.dispatch
            f.dispatch = { to, event ->
                when (event.kind) {
                    in GroupWavelengthEvents.CO_SIGNING -> GroupArkCoSigner.process(to.db, to.room, event, to.publicKey)
                    else -> inherited(to, event)
                }
            }
        }
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val recovery = File(dir, "recovery.json")
        val wallet = if (recovery.exists()) {
            // The same group as a run that stopped: its coins are bound to these shares. The room and its members are new, and neither is in the key.
            val lines = recovery.readLines()
            val icebergKey = lines[0].substringAfter(" ")
            val seedHex = lines[1].substringAfter(" ")
            val shares = lines.drop(2).map { Share(it.substringAfterLast(" ").hexToByteArray()) }
            fun permutations(items: List<Share>): List<List<Share>> =
                if (items.size <= 1) listOf(items) else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
            val ordered = permutations(shares).first { order ->
                (Iceberg.groupPublicKey(order.map { Iceberg.publicShare(it, null) }, 4, 2) as? Either.Right)?.value?.toString() == icebergKey
            }
            val publicShares = ordered.joinToString(",") { Hex.encode(Iceberg.publicShare(it, null).toByteArray()) }
            val ceremony = assertNotNull(GroupWavelengthWalletManager.unusedKey(all[0].db, fixture.roomId))
            all.forEach { d ->
                val row = assertNotNull(d.ceremony(ceremony.id))
                val position = row.participants().indexOf(d.publicKey.lowercase())
                d.db.icebergKeySessionDao().upsert(row.copy(share = Hex.encode(ordered[position].toByteArray()), publicShares = publicShares, groupPublicKey = icebergKey))
            }
            val (identity, manifestHash) = GroupWavelengthWalletManager.bindingOf(PublicKey.fromHex(icebergKey), ByteVector32(seedHex), Chain.Signet, 0)
            val chainName = GroupWalletStatementEvent.chainName(Chain.Signet)
            all.forEach { it.db.groupWavelengthSeedDao().insertIfAbsent(GroupWavelengthSeed(fixture.roomId, identity, chainName, 0, seedHex, all[0].publicKey)) }
            val template = GroupWavelengthWalletStatementEvent.template(
                GroupWavelengthWalletStatementEvent.Statement(
                    chain = Chain.Signet, wallet = 0, onchainAccount = 0, icebergKey = icebergKey, ceremonyId = ceremony.id, threshold = ceremony.threshold,
                    participants = ceremony.participants(), identity = identity, manifestHash = manifestHash, arkOperatorKey = operatorKey, policy = policy,
                ),
            )
            fixture.fileSignedAsRoom(all, template.kind, template.tags, template.content, createdAt = Clock.System.now().epochSeconds)
            say("restored group $icebergKey from its shares")
            assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, Chain.Signet))
        } else {
            val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, Chain.Signet, operatorKey, policy = policy)
            fixture.signSession(all, all[0], session.id)
            assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, Chain.Signet))
        }
        val nominated = GroupWavelengthOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, Chain.Signet)
        fixture.signSession(all, all[0], nominated.id)
        val term = assertNotNull(GroupWavelengthOperatorManager.current(all[0].db, fixture.roomId, Chain.Signet)).term
        val statement = wallet.statement
        val seed = assertNotNull(all[0].db.groupWavelengthSeedDao().get(fixture.roomId, statement.identity))
        val auth = GroupArkAuth.of(ByteVector32(seed.seed), Chain.Signet)
        val key = GroupArkCoSigner.walletKey(fixture.roomId, Chain.Signet, statement.wallet)
        val transport = GroupArkTransport(all[0].db, all[0].room, all[0].publicKey, wallet, term, auth, timeout = GroupWavelengthDaemon.TRANSPORT_TIMEOUT)
        val manifest = GroupArkManifest.of(PublicKey.fromHex(statement.icebergKey), auth, Chain.Signet, statement.wallet)

        // What it would take to reach the coins if this process dies: nothing here is worth more than signet's are.
        if (!recovery.exists()) {
            recovery.createNewFile()
            recovery.setReadable(false, false)
            recovery.setWritable(false, false)
            recovery.setReadable(true, true)
            recovery.setWritable(true, true)
            recovery.writeText(
                all.joinToString("\n", prefix = "icebergKey ${statement.icebergKey}\nauthSeed ${seed.seed}\n") { d ->
                    "device ${d.index} ${d.publicKey} share ${runBlocking { d.ceremony(statement.ceremonyId)?.share }}"
                },
            )
        }
        say("group ${statement.icebergKey} identity ${statement.identity} manifest ${statement.manifestHash} onchain ${fixture.account}")

        // A restored device doesn't know which labels it answered, and a label answered twice is how a share leaks. Real devices ask
        // each other: here every device is restored at once, so nobody remembers, and this file does instead.
        val labels = File(dir, "plain-floor.txt")
        val floor = labels.takeIf { it.exists() }?.readText()?.trim()?.toLong()?.plus(LABEL_MARGIN) ?: 0L
        all.forEach { it.db.groupArkDao().upsertFloor(GroupArkFloor(key, floor, 0)) }
        say("plain labels from $floor")

        val pump = launch(Dispatchers.Default) {
            while (isActive) {
                fixture.pump(all, GroupWavelengthEvents.CO_SIGNING)
                delay(20)
            }
        }
        val began = System.nanoTime()
        var plainTo = floor + 127L
        transport.provision(plainTo, 7)
        say("round one provisioned in ${(System.nanoTime() - began) / 1_000_000} ms, as far as the operator's device")

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/sign") { exchange ->
            val started = System.nanoTime()
            val raw = exchange.requestBody.readBytes()
            val request = runCatching { CommonJson.decodeFromString(ArkRequest.serializer(), raw.decodeToString()) }
            val what = request.getOrNull()?.let { "${it.kind} family=${it.family} ${it.branch}/${it.index} tag=${it.tag} note=${it.note?.kind}" } ?: "unreadable"
            val answer = runCatching { runBlocking { transport.answer(request.getOrThrow()) } }
            val millis = (System.nanoTime() - started) / 1_000_000
            answer.onSuccess { say("ok $what ${millis}ms") }.onFailure { say("FAILED $what ${millis}ms: ${it.message}") }
            if (answer.isFailure) File(dir, "refused-${System.currentTimeMillis()}.json").writeBytes(raw)
            val body = answer.map { CommonJson.encodeToString(ArkResponse.serializer(), it).encodeToByteArray() }.getOrElse { (it.message ?: "refused").encodeToByteArray() }
            exchange.sendResponseHeaders(if (answer.isSuccess) 200 else 500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val open = mapOf(
            "signer_url" to "http://127.0.0.1:${server.address.port}/sign",
            "manifest" to manifest.canonical,
            "auth_keys" to auth.authKeysJson(),
            "password" to Hex.encode(GroupWavelengthDaemon.passwordOf(seed.seed)),
            "identity" to statement.identity,
        )
        File(dir, "open.json.tmp").writeText(CommonJson.encodeToString(open))
        File(dir, "open.json.tmp").renameTo(File(dir, "open.json"))
        say("asked the daemon to open")

        // Until told to stop: keeps round one topped up, files what has been seen to settle, and says what the devices refuse or know.
        val settled = HashSet<String>()
        val refused = HashSet<String>()
        var ledger = ""
        while (!File(dir, "stop").exists()) {
            val used = all[0].db.icebergCoSigningDao().highestAssignment(key) ?: (floor - 1)
            labels.writeText("${used + 1}")
            if (used + 64 > plainTo) {
                plainTo += 128
                transport.provision(plainTo, 7)
                say("provisioned to $plainTo")
            }
            File(dir, "settle.txt").takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() && settled.add(it) }?.forEach { txid ->
                all.forEach { GroupArkCoSigner.settled(it.db, key, txid) }
                say("settled $txid")
            }
            all.forEach { d ->
                fixture.lines(d, ChatMessage.TYPE_LIGHTNING_REFUSED).forEach { if (refused.add("${d.index}${it.content}")) say("device ${d.index}: ${it.content}") }
                fixture.lines(d, ChatMessage.TYPE_LIGHTNING_EQUIVOCATION).forEach { if (refused.add("${d.index}${it.content}")) say("device ${d.index}: ${it.content}") }
            }
            val now = all.joinToString(" | ") { d -> runBlocking { d.db.groupArkDao().vtxos(key) }.joinToString { "${it.outpoint.take(12)} ${it.amountSat} settled=${it.settled} spent=${it.spent} by=${it.reservedBy?.take(12)}" } }
            if (now != ledger) {
                ledger = now
                say("ledger $now")
            }
            delay(1_000)
        }
        server.stop(0)
        pump.cancelAndJoin()
        fixture.close()
        assertEquals(false, statement.chain.isMainnet())
    }
}
