package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Kotlin half of the iOS bridge -- `docs/bark-wallet.md`, Phase 8. The fake engine
 * answers each method with the JSON `BarkEngine.swift` is written to produce, so these
 * pin the contract the two halves share.
 */
class EngineArkWalletJvmTest {
    private class FakeEngine(private val answers: Map<String, String>) : ArkEngine {
        val calls = mutableListOf<Pair<String, String>>()
        val events = ArrayDeque<String>()
        var opened: Triple<String, String, String>? = null
        var closed = false

        override fun open(configJson: String, words: String, datadir: String, reply: ArkEngineReply) {
            opened = Triple(configJson, words, datadir)
            reply.ok("{}")
        }

        override fun call(method: String, argumentsJson: String, reply: ArkEngineReply) {
            calls += method to argumentsJson
            val answer = answers[method]
            if (answer == null) reply.error("unknown method $method") else reply.ok(answer)
        }

        override fun nextEvent(reply: ArkEngineReply) = reply.ok(events.removeFirstOrNull() ?: "null")

        override fun close(reply: ArkEngineReply) {
            closed = true
            reply.ok("{}")
        }
    }

    private val movement = """{"id":3,"status":"successful","subsystemName":"bark.arkoor","subsystemKind":"receive","effectiveBalanceSats":2500,"offchainFeeSats":0,"sentTo":[],"receivedOn":[],"createdAt":"2026-11-02T08:00:00Z"}"""

    private val answers = mapOf(
        "recovery" to """{"kind":"completed","complete":false,"recoveredSats":1000,"failedIds":["x:0"]}""",
        "balance" to """{"spendable":21000,"pendingInRound":500,"onchain":7}""",
        "history" to "[$movement]",
        "newArkAddress" to """{"value":"tark1abc"}""",
        "receive" to """{"uri":"bitcoin:?ark=tark1abc","arkAddress":"tark1abc","bolt11":"lntbs1","paymentHash":"00"}""",
        "parse" to """{"amount":5000,"options":[{"method":{"type":"invoice","value":"lntbs1"},"requiresAmount":false}],"preferred":{"method":{"type":"invoice","value":"lntbs1"},"requiresAmount":false}}""",
        "estimateFee" to """{"gross":5010,"fee":10,"net":5000}""",
        "send" to """{"type":"lightning","value":"ab"}""",
        "renewalFee" to """{"value":null}""",
        "firstExpiryHeight" to """{"value":1144}""",
        "tipHeight" to """{"value":1000}""",
        "exitsClaimableAt" to """{"value":null}""",
        "progressExits" to """[{"vtxoId":"aa:0","state":"awaitingDelta"}]""",
        "vtxos" to """[{"id":"aa:0","amount":20000,"expiryHeight":1144,"state":"spendable"}]""",
        "emergencyExitFee" to """{"total":3000,"fundable":false}""",
    )

    private val config = ArkConfig(ArkNetwork.Signet, ArkWalletRollout.endpointsFor(ArkNetwork.Signet), "dedun/test")

    @Test
    fun `opening hands Swift the config, the words and the datadir, then reads the recovery`() = runTest {
        val engine = FakeEngine(answers)
        val wallet = openEngineWallet(engine, config, "abandon ability", "/ark/signet/x")
        val (configJson, words, datadir) = engine.opened!!
        val sent = Json.parseToJsonElement(configJson).jsonObject
        assertEquals("signet", sent.getValue("network").jsonPrimitive.content)
        assertEquals("dedun/test", sent.getValue("userAgent").jsonPrimitive.content)
        assertEquals("abandon ability", words)
        assertEquals("/ark/signet/x", datadir)
        assertEquals(ArkRecovery.Completed(complete = false, recovered = Satoshi(1000), failedIds = listOf("x:0")), wallet.recovery())
    }

    @Test
    fun `every port call reads what Swift answers`() = runTest {
        val engine = FakeEngine(answers)
        val wallet = openEngineWallet(engine, config, "w", "/d")
        assertEquals(Satoshi(21_000), wallet.balance().spendable)
        assertEquals(Satoshi(500), wallet.balance().pending)
        assertEquals(ArkRail.Ark, wallet.history().single().rail)
        assertEquals("tark1abc", wallet.newArkAddress())
        assertEquals("lntbs1", wallet.receive(Satoshi(5_000), "x").bolt11)
        val request = wallet.parse("lntbs1")
        assertEquals(ArkPaymentMethod.Bolt11("lntbs1"), request.chosen?.method)
        assertEquals(Satoshi(10), wallet.estimateFee(ArkPaymentMethod.Bolt11("lntbs1"), Satoshi(5_000)).fee)
        assertEquals(ArkSendResult.Lightning("ab"), wallet.send(ArkPaymentMethod.Bolt11("lntbs1"), null, null))
        assertNull(wallet.renewalFee())
        assertEquals(1_144L, wallet.firstExpiryHeight())
        assertEquals(1_000L, wallet.tipHeight())
        assertNull(wallet.exitsClaimableAt())
        assertEquals(ArkExitStatus.State.AwaitingDelay, wallet.progressExits().single().state)
        assertEquals(ArkVtxo.State.Spendable, wallet.vtxos().single().state)
        assertEquals(ArkExitFee(Satoshi(3_000), fundable = false), wallet.emergencyExitFee())
    }

    @Test
    fun `arguments go to Swift by name, and a method is named as Bark names it`() = runTest {
        val engine = FakeEngine(answers)
        val wallet = openEngineWallet(engine, config, "w", "/d")
        wallet.send(ArkPaymentMethod.LightningAddress("a@b.c"), Satoshi(1_000), "thanks")
        val arguments = Json.parseToJsonElement(engine.calls.last().second).jsonObject
        assertEquals("lightningAddress", arguments.getValue("method").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("1000", arguments.getValue("amount").jsonPrimitive.content)
        assertEquals("thanks", arguments.getValue("comment").jsonPrimitive.content)
    }

    @Test
    fun `an error from Swift is classified like one from the JVM binding`() = runTest {
        val wallet = openEngineWallet(FakeEngine(answers), config, "w", "/d")
        assertFailsWith<ArkError.Other> { wallet.boardAll() }
    }

    @Test
    fun `events stream until Swift says null, and close reaches Swift`() = runTest {
        val engine = FakeEngine(answers)
        engine.events += """{"type":"movement","movement":$movement}"""
        engine.events += """{"type":"lagging"}"""
        val wallet = openEngineWallet(engine, config, "w", "/d")
        val events = wallet.events.toList()
        assertIs<ArkEvent.MovementChanged>(events[0])
        assertEquals(ArkEvent.Lagging, events[1])
        wallet.close()
        assertEquals(true, engine.closed)
    }
}
