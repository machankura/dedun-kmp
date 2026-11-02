package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Lightning
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The profile's Ark wallet end to end against a real Ark server on regtest --
 * `docs/bark-wallet.md`, Phase 12, with `testing/regtest/ark/ark.sh up` running.
 *
 * Coins onto Ark, an Ark payment between two profiles, a renewal, everything back onchain,
 * and a wallet restored from its words alone. The emergency exit is run by hand, with
 * `ark.sh stop-server`, because it takes a server that has gone away and a day of blocks.
 *
 * Skipped unless `DEDUN_ARK_REGTEST=1`, on Linux x86-64.
 */
class ArkRegtestJvmTest {
    private val enabled = System.getenv("DEDUN_ARK_REGTEST") == "1" && ArkPlatform.supported
    private val root = Files.createTempDirectory("ark-regtest").toFile()
    private val config = ArkConfig(ArkNetwork.Regtest, ArkWalletRollout.endpointsFor(ArkNetwork.Regtest), ArkSetup.userAgent("regtest"))
    private val http = HttpClient.newHttpClient()

    private fun words(): String = MnemonicCode.toMnemonics(Lightning.randomBytes(16)).joinToString(" ")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun btc(method: String, vararg params: Any, wallet: String? = "miner"): JsonElement {
        val args = params.joinToString(",") { if (it is String) "\"$it\"" else it.toString() }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:38443/" + (wallet?.let { "wallet/$it" } ?: "")))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("second:ark".toByteArray()))
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"1.0","id":1,"method":"$method","params":[$args]}"""))
            .build()
        val body = Json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        return body.getValue("result")
    }

    private fun mine(blocks: Int = 1) {
        btc("generatetoaddress", blocks, btc("getnewaddress").jsonPrimitive.content)
    }

    /** Polls [condition], mining a block between tries, as a round or a board needs blocks to progress. */
    private suspend fun eventually(what: String, blocksPerTry: Int = 1, condition: suspend () -> Boolean) {
        repeat(60) {
            if (condition()) return
            mine(blocksPerTry)
            delay(2_000)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun boardPayRenewOffboardAndRestore() = runBlocking {
        assumeTrue("set DEDUN_ARK_REGTEST=1 with testing/regtest/ark/ark.sh up", enabled)
        val alicePhrase = words()
        val alice = ArkPlatform.open(config, alicePhrase, root.resolve("alice").path)
        val bob = ArkPlatform.open(config, words(), root.resolve("bob").path)
        try {
            // Onchain into Alice's wallet, then onto Ark.
            btc("sendtoaddress", alice.onchainAddress(), 0.001)
            mine()
            eventually("Alice's onchain coins") { alice.sync(); alice.balance().onchain.sat > 0 }
            alice.boardAll()
            eventually("the board") { alice.sync(); alice.balance().spendable.sat > 0 }
            val boarded = alice.balance().spendable

            // An Ark payment, which needs no round.
            val bobAddress = bob.newArkAddress()
            val method = alice.parse(bobAddress).chosen!!.method
            alice.send(method, Satoshi(20_000), null)
            eventually("Bob's payment") { bob.sync(); bob.balance().spendable == Satoshi(20_000) }
            assertTrue(alice.history().any { it.direction == ArkDirection.Outgoing && it.rail == ArkRail.Ark })

            // A delegated renewal of whatever is due, then everything back onchain.
            alice.maintenance()
            val destination = btc("getnewaddress").jsonPrimitive.content
            alice.offboardAll(destination)
            eventually("the offboard") { alice.sync(); alice.balance().spendable.sat == 0L }
            assertTrue(boarded.sat > 20_000)
        } finally {
            alice.close()
            bob.close()
        }

        // Alice's words alone, in a fresh datadir: the server's mailbox gives the balance back.
        val restored = ArkPlatform.open(config, alicePhrase, root.resolve("alice-restored").path)
        try {
            assertTrue(restored.recovery() is ArkRecovery.Completed)
            restored.sync()
            assertEquals(Satoshi(0), restored.balance().spendable)
        } finally {
            restored.close()
        }
    }

    /** `ark.sh stop-server` and `start-server`: the exit is for a server that has gone. */
    private fun server(action: String) {
        val script = java.io.File("../testing/regtest/ark/ark.sh").absoluteFile
        val process = ProcessBuilder(script.path, action).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { "ark.sh $action: " + process.inputStream.bufferedReader().readText() }
    }

    @Test
    fun anEmergencyExitWithTheServerGoneIsClaimedOnchain() = runBlocking {
        assumeTrue("set DEDUN_ARK_REGTEST=1 with testing/regtest/ark/ark.sh up", enabled)
        val wallet = ArkPlatform.open(config, words(), root.resolve("exit").path)
        try {
            btc("sendtoaddress", wallet.onchainAddress(), 0.0005)
            mine()
            eventually("onchain coins") { wallet.sync(); wallet.balance().onchain.sat > 0 }
            wallet.boardAll()
            eventually("the board") { wallet.sync(); wallet.balance().spendable.sat > 0 }
            // The exit is fee-bumped from the onchain wallet (D8), so it needs coins there again.
            btc("sendtoaddress", wallet.onchainAddress(), 0.001)
            mine()
            eventually("fee money onchain") { wallet.sync(); wallet.balance().onchain.sat > 0 }
            assertTrue(wallet.emergencyExitFee().fundable)

            server("stop-server")
            wallet.startEmergencyExit()
            // Each exit transaction confirms in turn, then the coin waits vtxo_exit_delta (144 blocks).
            var last = emptyList<ArkExitStatus>()
            try {
                eventually("every exit to be claimable", blocksPerTry = 10) {
                    last = wallet.progressExits()
                    last.isNotEmpty() && last.all { it.state == ArkExitStatus.State.Claimable }
                }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}; exits: $last")
            }
            wallet.exitsClaimableAt()?.let { height ->
                val tip = btc("getblockcount", wallet = null).jsonPrimitive.content.toLong()
                if (tip < height) mine((height - tip).toInt())
            }
            val txid = wallet.claimExits(btc("getnewaddress").jsonPrimitive.content)
            assertEquals(64, txid.length)
        } finally {
            server("start-server")
            wallet.close()
        }
    }
}
