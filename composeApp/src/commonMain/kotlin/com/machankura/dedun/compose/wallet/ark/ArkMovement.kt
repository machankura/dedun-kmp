package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.absoluteValue
import kotlin.time.Instant

/** Which way money went, as the wallet's owner sees it. */
enum class ArkDirection { Incoming, Outgoing, Internal }

/** How it moved. */
enum class ArkRail { Ark, Lightning, Onchain, Board, Offboard, Renewal, Exit, Other }

enum class ArkMovementStatus { Pending, Succeeded, Failed, Canceled }

/** One row of the wallet's history. */
data class ArkMovement(
    val id: Long,
    val status: ArkMovementStatus,
    val direction: ArkDirection,
    val rail: ArkRail,
    /** What the wallet's balance changed by, without sign: see [direction]. */
    val amount: Satoshi,
    val fee: Satoshi,
    /** The address, invoice or Lightning address the money went to or came in on, if any. */
    val counterparty: String?,
    val paymentHash: String?,
    val createdAt: Instant?,
    val completedAt: Instant?,
)

/**
 * A movement as the FFI gives it, field for field, so the mapping can be tested
 * without the library -- `bark-ffi`'s `types::Movement`.
 */
data class RawArkMovement(
    val id: Long,
    val status: String,
    val subsystemName: String,
    val subsystemKind: String,
    val effectiveBalanceSats: Long,
    val offchainFeeSats: Long,
    val sentTo: List<String>,
    val receivedOn: List<String>,
    val paymentHash: String?,
    val lightningInvoice: String?,
    val createdAt: String,
    val completedAt: String?,
)

/**
 * Bark's subsystem names and kinds, and its status strings, turned into what a
 * history row says. The names are `bark/src/subsystem/mod.rs` at `bark-0.8.0`:
 * `bark.arkoor` (send, receive), `bark.board` (board), `bark.offboard` (offboard,
 * send_onchain), `bark.exit` (start), `bark.lightning_receive` (receive),
 * `bark.lightning_send` (send) and `bark.round` (refresh).
 */
object ArkMovementMapping {
    fun map(raw: RawArkMovement): ArkMovement {
        val rail = railOf(raw.subsystemName, raw.subsystemKind)
        val direction = when {
            rail == ArkRail.Renewal || rail == ArkRail.Board || rail == ArkRail.Exit -> ArkDirection.Internal
            raw.effectiveBalanceSats > 0 -> ArkDirection.Incoming
            raw.effectiveBalanceSats < 0 -> ArkDirection.Outgoing
            else -> ArkDirection.Internal
        }
        val counterparty = when (direction) {
            ArkDirection.Outgoing -> raw.sentTo.firstNotNullOfOrNull(::destinationOf)
            ArkDirection.Incoming -> raw.lightningInvoice ?: raw.receivedOn.firstNotNullOfOrNull(::destinationOf)
            ArkDirection.Internal -> null
        }
        return ArkMovement(
            id = raw.id,
            status = statusOf(raw.status),
            direction = direction,
            rail = rail,
            amount = Satoshi(raw.effectiveBalanceSats.absoluteValue),
            fee = Satoshi(raw.offchainFeeSats),
            counterparty = counterparty,
            paymentHash = raw.paymentHash,
            createdAt = instantOf(raw.createdAt),
            completedAt = raw.completedAt?.let(::instantOf),
        )
    }

    fun statusOf(status: String): ArkMovementStatus = when (status.lowercase()) {
        "pending" -> ArkMovementStatus.Pending
        "successful", "succeeded", "success" -> ArkMovementStatus.Succeeded
        "failed" -> ArkMovementStatus.Failed
        "canceled", "cancelled" -> ArkMovementStatus.Canceled
        else -> ArkMovementStatus.Pending
    }

    fun railOf(name: String, kind: String): ArkRail = when (name) {
        "bark.arkoor" -> ArkRail.Ark
        "bark.lightning_send", "bark.lightning_receive" -> ArkRail.Lightning
        "bark.board" -> ArkRail.Board
        "bark.offboard" -> if (kind == "send_onchain") ArkRail.Onchain else ArkRail.Offboard
        "bark.round" -> ArkRail.Renewal
        "bark.exit" -> ArkRail.Exit
        else -> ArkRail.Other
    }

    /**
     * A destination the FFI serialised as JSON -- a `PaymentMethod`, whose exact
     * shape is serde's -- read as the first string inside it, or the text itself
     * if it is not JSON.
     */
    fun destinationOf(serialised: String): String? {
        // The parser takes an unquoted word as a literal, so a bare address is not JSON here.
        val element = runCatching { Json.parseToJsonElement(serialised) }.getOrNull()
            ?.takeUnless { it is JsonPrimitive && !it.isString }
            ?: return serialised.takeIf { it.isNotBlank() }
        return firstString(element)
    }

    private fun firstString(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.content.takeIf { element.isString && it.isNotBlank() }
        is JsonObject -> (element["value"]?.let(::firstString)) ?: element.values.firstNotNullOfOrNull(::firstString)
        is JsonArray -> element.firstNotNullOfOrNull(::firstString)
    }

    private fun instantOf(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
