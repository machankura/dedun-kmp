package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bark on a platform with no Kotlin binding -- `docs/bark-wallet.md`, D4 and Phase 8.
 *
 * iOS reaches Bark through its Swift package, so the iOS app implements this in Swift and
 * registers it with [ArkEngineRegistry] before the first screen. Four methods, JSON across
 * the boundary: Swift cannot implement a Kotlin `suspend` function, and one dispatch method
 * keeps the Swift side a `switch` over method names rather than a protocol that has to
 * change with every call the port gains. The method names and JSON shapes are
 * [EngineArkWallet]'s, and `iosApp/iosApp/BarkEngine.swift` is the other half.
 */
interface ArkEngine {
    /** Opens, or on the first call creates, the wallet. Replies `{}`. */
    fun open(configJson: String, words: String, datadir: String, reply: ArkEngineReply)

    /** One port call by name, its arguments as a JSON object. Replies with its result as JSON. */
    fun call(method: String, argumentsJson: String, reply: ArkEngineReply)

    /** The next wallet notification as JSON, or `null` when the stream has ended. */
    fun nextEvent(reply: ArkEngineReply)

    /** Stops Bark's daemon, waits for it, and releases the datadir. Replies `{}`. */
    fun close(reply: ArkEngineReply)
}

/** One answer to one [ArkEngine] call: exactly one of the two, exactly once. */
interface ArkEngineReply {
    fun ok(json: String)

    fun error(message: String)
}

/** Where the iOS app puts its [ArkEngine]. Nothing registered means no Ark wallet on this device. */
object ArkEngineRegistry {
    var engine: ArkEngine? = null
        private set

    fun register(engine: ArkEngine) {
        this.engine = engine
    }
}

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

private suspend fun ArkEngine.ask(method: String, arguments: JsonObject = JsonObject(emptyMap())): JsonElement =
    suspendCancellableCoroutine { continuation ->
        call(method, arguments.toString(), continuation.reply())
    }

private fun kotlinx.coroutines.CancellableContinuation<JsonElement>.reply() = object : ArkEngineReply {
    override fun ok(json: String) = resume(com.machankura.dedun.compose.wallet.ark.json.parseToJsonElement(json))
    override fun error(message: String) = resumeWithException(ArkError.classify(message))
}

/** Opens the wallet through [engine], then reads how the first open's scan went. */
suspend fun openEngineWallet(engine: ArkEngine, config: ArkConfig, words: String, datadir: String): ArkWallet {
    val configJson = json.encodeToString(EngineConfig(config.network.name.lowercase(), config.endpoints.server, config.endpoints.esplora, config.userAgent))
    suspendCancellableCoroutine { continuation -> engine.open(configJson, words, datadir, continuation.reply()) }
    val recovery = json.decodeFromJsonElement<EngineRecovery>(engine.ask("recovery")).toArk()
    return EngineArkWallet(engine, config.network, recovery)
}

@Serializable
internal data class EngineConfig(val network: String, val server: String, val esplora: String, val userAgent: String)

@Serializable
internal data class EngineRecovery(
    val kind: String,
    val message: String? = null,
    val complete: Boolean = true,
    val recoveredSats: Long = 0,
    val failedIds: List<String> = emptyList(),
) {
    fun toArk(): ArkRecovery = when (kind) {
        "failed" -> ArkRecovery.Failed(message.orEmpty())
        "completed" -> ArkRecovery.Completed(complete, Satoshi(recoveredSats), failedIds)
        else -> ArkRecovery.NotRun
    }
}

@Serializable
internal data class EngineBalance(
    val spendable: Long,
    val pendingLightningSend: Long = 0,
    val claimableLightningReceive: Long = 0,
    val pendingInRound: Long = 0,
    val pendingBoard: Long = 0,
    val pendingExit: Long = 0,
    val pendingOffboard: Long = 0,
    val pendingArkSend: Long = 0,
    val onchain: Long = 0,
) {
    fun toArk() = ArkBalance(
        Satoshi(spendable), Satoshi(pendingLightningSend), Satoshi(claimableLightningReceive), Satoshi(pendingInRound),
        Satoshi(pendingBoard), Satoshi(pendingExit), Satoshi(pendingOffboard), Satoshi(pendingArkSend), Satoshi(onchain),
    )
}

@Serializable
internal data class EngineMovement(
    val id: Long,
    val status: String,
    val subsystemName: String,
    val subsystemKind: String,
    val effectiveBalanceSats: Long,
    val offchainFeeSats: Long = 0,
    val sentTo: List<String> = emptyList(),
    val receivedOn: List<String> = emptyList(),
    val paymentHash: String? = null,
    val lightningInvoice: String? = null,
    val createdAt: String = "",
    val completedAt: String? = null,
) {
    fun toArk() = ArkMovementMapping.map(
        RawArkMovement(id, status, subsystemName, subsystemKind, effectiveBalanceSats, offchainFeeSats, sentTo, receivedOn, paymentHash, lightningInvoice, createdAt, completedAt),
    )
}

@Serializable
internal data class EngineFee(val gross: Long, val fee: Long, val net: Long) {
    fun toArk() = ArkFee(Satoshi(gross), Satoshi(fee), Satoshi(net))
}

/** A payment method by its Bark name: `ark`, `bitcoin`, `invoice`, `offer`, `lightningAddress`, `lnurl`. */
@Serializable
internal data class EngineMethod(val type: String, val value: String) {
    fun toArk(): ArkPaymentMethod? = when (type) {
        "ark" -> ArkPaymentMethod.ArkAddress(value)
        "bitcoin" -> ArkPaymentMethod.Onchain(value)
        "invoice" -> ArkPaymentMethod.Bolt11(value)
        "offer" -> ArkPaymentMethod.Bolt12Offer(value)
        "lightningAddress" -> ArkPaymentMethod.LightningAddress(value)
        "lnurl" -> ArkPaymentMethod.Lnurl(value)
        else -> null
    }

    companion object {
        fun of(method: ArkPaymentMethod) = EngineMethod(
            when (method) {
                is ArkPaymentMethod.ArkAddress -> "ark"
                is ArkPaymentMethod.Onchain -> "bitcoin"
                is ArkPaymentMethod.Bolt11 -> "invoice"
                is ArkPaymentMethod.Bolt12Offer -> "offer"
                is ArkPaymentMethod.LightningAddress -> "lightningAddress"
                is ArkPaymentMethod.Lnurl -> "lnurl"
            },
            method.value,
        )
    }
}

@Serializable
internal data class EngineOption(val method: EngineMethod, val requiresAmount: Boolean, val problems: List<String> = emptyList()) {
    fun toArk() = method.toArk()?.let { ArkPaymentOption(it, requiresAmount, problems) }
}

@Serializable
internal data class EngineRequest(val amount: Long? = null, val description: String? = null, val options: List<EngineOption>, val preferred: EngineOption? = null) {
    fun toArk() = ArkPaymentRequest(amount?.let(::Satoshi), description, options.mapNotNull { it.toArk() }, preferred?.toArk())
}

@Serializable
internal data class EngineReceive(
    val uri: String,
    val arkAddress: String? = null,
    val bolt11: String? = null,
    val paymentHash: String? = null,
    val onchainAddress: String? = null,
)

@Serializable
internal data class EngineSendResult(val type: String, val value: String)

@Serializable
internal data class EngineEvent(val type: String, val movement: EngineMovement? = null)

@Serializable
internal data class EngineVtxo(val id: String, val amount: Long, val expiryHeight: Long, val state: String) {
    fun toArk() = ArkVtxo(
        id, Satoshi(amount), expiryHeight,
        when (state) {
            "locked" -> ArkVtxo.State.Locked
            "spent" -> ArkVtxo.State.Spent
            "exited" -> ArkVtxo.State.Exited
            else -> ArkVtxo.State.Spendable
        },
    )
}

@Serializable
internal data class EngineExit(val vtxoId: String, val state: String, val error: String? = null) {
    fun toArk() = ArkExitStatus(
        vtxoId,
        when (state) {
            "processing" -> ArkExitStatus.State.Broadcasting
            "awaitingDelta" -> ArkExitStatus.State.AwaitingDelay
            "claimable" -> ArkExitStatus.State.Claimable
            "claimInProgress" -> ArkExitStatus.State.Claiming
            "claimed" -> ArkExitStatus.State.Claimed
            "canceled" -> ArkExitStatus.State.Canceled
            "vtxoAlreadySpent" -> ArkExitStatus.State.AlreadySpent
            "vtxoSwept" -> ArkExitStatus.State.Swept
            else -> ArkExitStatus.State.Starting
        },
        error,
    )
}

@Serializable
internal data class EngineExitFee(val total: Long, val fundable: Boolean)

/** A single value: an address, a height, a txid. `{"value": …}`, the value null where there is none. */
private fun JsonElement.value(): JsonElement = (this as? JsonObject)?.get("value") ?: JsonNull

private fun JsonElement.string(): String = value().jsonPrimitive.content

private fun JsonElement.longOrNull(): Long? = (value() as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

/** The port over an [ArkEngine]. Every name and shape here is one the Swift side answers. */
internal class EngineArkWallet(
    private val engine: ArkEngine,
    override val network: ArkNetwork,
    private val recovery: ArkRecovery,
) : ArkWallet {
    override val events: Flow<ArkEvent> = flow {
        while (true) {
            val reply = suspendCancellableCoroutine<JsonElement> { continuation -> engine.nextEvent(continuation.reply()) }
            if (reply is JsonNull) break
            val event = json.decodeFromJsonElement<EngineEvent>(reply)
            when (event.type) {
                "lagging" -> emit(ArkEvent.Lagging)
                else -> event.movement?.let { emit(ArkEvent.MovementChanged(it.toArk())) }
            }
        }
    }

    override fun recovery(): ArkRecovery = recovery

    override suspend fun balance(): ArkBalance = json.decodeFromJsonElement<EngineBalance>(engine.ask("balance")).toArk()

    override suspend fun history(): List<ArkMovement> =
        json.decodeFromJsonElement<List<EngineMovement>>(engine.ask("history")).map { it.toArk() }.sortedByDescending { it.id }

    override suspend fun sync() { engine.ask("sync") }

    override suspend fun newArkAddress(): String = engine.ask("newArkAddress").string()

    override suspend fun receive(amount: Satoshi?, description: String?): ArkReceiveRequest {
        val reply = json.decodeFromJsonElement<EngineReceive>(engine.ask("receive", buildJsonObject {
            amount?.let { put("amount", it.sat) }
            description?.let { put("description", it) }
        }))
        return ArkReceiveRequest(reply.uri, reply.arkAddress, reply.bolt11, reply.paymentHash, reply.onchainAddress, amount)
    }

    override suspend fun lightningReceiveFee(amount: Satoshi): ArkFee =
        json.decodeFromJsonElement<EngineFee>(engine.ask("lightningReceiveFee", buildJsonObject { put("amount", amount.sat) })).toArk()

    override suspend fun claimLightningReceives() { engine.ask("claimLightningReceives") }

    override suspend fun boardAll() { engine.ask("boardAll") }

    override suspend fun parse(text: String): ArkPaymentRequest =
        json.decodeFromJsonElement<EngineRequest>(engine.ask("parse", buildJsonObject { put("text", text.trim()) })).toArk()

    override suspend fun estimateFee(method: ArkPaymentMethod, amount: Satoshi): ArkFee =
        json.decodeFromJsonElement<EngineFee>(engine.ask("estimateFee", buildJsonObject {
            put("method", json.encodeToJsonElement(EngineMethod.serializer(), EngineMethod.of(method)))
            put("amount", amount.sat)
        })).toArk()

    override suspend fun send(method: ArkPaymentMethod, amount: Satoshi?, comment: String?): ArkSendResult {
        val reply = json.decodeFromJsonElement<EngineSendResult>(engine.ask("send", buildJsonObject {
            put("method", json.encodeToJsonElement(EngineMethod.serializer(), EngineMethod.of(method)))
            amount?.let { put("amount", it.sat) }
            comment?.let { put("comment", it) }
        }))
        return when (reply.type) {
            "lightning" -> ArkSendResult.Lightning(reply.value)
            "onchain" -> ArkSendResult.Onchain(reply.value)
            else -> ArkSendResult.Ark(reply.value.toLong())
        }
    }

    override suspend fun maintenance() { engine.ask("maintenance") }

    override suspend fun renewalFee(): ArkFee? {
        val reply = engine.ask("renewalFee")
        return if (reply is JsonNull || reply.value() is JsonNull) null else json.decodeFromJsonElement<EngineFee>(reply.jsonObject.getValue("value")).toArk()
    }

    override suspend fun renew() { engine.ask("renew") }

    override suspend fun firstExpiryHeight(): Long? = engine.ask("firstExpiryHeight").longOrNull()

    override suspend fun tipHeight(): Long = engine.ask("tipHeight").longOrNull() ?: 0

    override suspend fun vtxos(): List<ArkVtxo> = json.decodeFromJsonElement<List<EngineVtxo>>(engine.ask("vtxos")).map { it.toArk() }

    override suspend fun onchainAddress(): String = engine.ask("onchainAddress").string()

    override suspend fun offboardAllFee(address: String): ArkFee =
        json.decodeFromJsonElement<EngineFee>(engine.ask("offboardAllFee", buildJsonObject { put("address", address) })).toArk()

    override suspend fun offboardAll(address: String): String = engine.ask("offboardAll", buildJsonObject { put("address", address) }).string()

    override suspend fun emergencyExitFee(): ArkExitFee =
        json.decodeFromJsonElement<EngineExitFee>(engine.ask("emergencyExitFee")).let { ArkExitFee(Satoshi(it.total), it.fundable) }

    override suspend fun startEmergencyExit() { engine.ask("startEmergencyExit") }

    override suspend fun progressExits(): List<ArkExitStatus> =
        json.decodeFromJsonElement<List<EngineExit>>(engine.ask("progressExits")).map { it.toArk() }

    override suspend fun exitsClaimableAt(): Long? = engine.ask("exitsClaimableAt").longOrNull()

    override suspend fun claimExits(address: String): String = engine.ask("claimExits", buildJsonObject { put("address", address) }).string()

    override suspend fun close() {
        suspendCancellableCoroutine<JsonElement> { continuation -> engine.close(continuation.reply()) }
    }
}
