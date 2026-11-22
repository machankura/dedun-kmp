package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import com.machankura.dedun.compose.cryptography.threshold.Dleq
import com.machankura.dedun.compose.extensions.toHex

/**
 * What a partials message carries, once its [AdminSeal] is opened (D11 of
 * docs/group-threshold-ecdh.md):
 *
 * ```
 * {"v":1,"generation":g,"from":id,"items":[{"point","purpose","ref"?,"evidence"?,"partial","proof"}, …]}
 * ```
 *
 * [Item.point] is the x-only key multiplied, lifted to even y by everyone; [Item.partial] is
 * `sᵢ·P`, compressed. A wrap or a seal is named by the wrap's id in [Item.ref], since every
 * admin's device holds the wrap and opens the same seal from it (Phase 0, check 6). Only a
 * recipient's evidence travels whole, in [Item.evidence].
 *
 * The decoder accepts only its own encoding, so there is one spelling of a payload.
 */
class GroupEcdhPayload(
    val generation: Int,
    val from: Int,
    val items: List<Item>,
) {
    class Item(
        val point: HexKey,
        val purpose: EcdhPurpose,
        val ref: HexKey? = null,
        val evidence: Event? = null,
        val partial: PublicKey,
        val proof: Dleq.Proof,
    )

    fun encode(): String = json.encodeToString(JsonElement.serializer(), toJson())

    private fun toJson(): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("generation", generation)
        put("from", from)
        put("items", buildJsonArray {
            items.forEach { item ->
                add(buildJsonObject {
                    put("point", item.point)
                    put("purpose", item.purpose.wire)
                    item.ref?.let { put("ref", it) }
                    item.evidence?.let { put("evidence", json.parseToJsonElement(it.toJson())) }
                    put("partial", item.partial.value.toHex())
                    put("proof", item.proof.toHex())
                })
            }
        })
    }

    companion object {
        const val VERSION = 1

        private val json = Json

        /** A round: its id commits to the room, the generation and the point (D11). */
        fun roundId(room: HexKey, generation: Int, point: HexKey): HexKey =
            Crypto.sha256(room.hexToByteArray() + "$generation".encodeToByteArray() + point.hexToByteArray()).toHex()

        private fun isHex(value: String, length: Int) = value.length == length && value.all { it in "0123456789abcdef" }

        fun decode(text: String): GroupEcdhPayload? = runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            if ((root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.int != VERSION) return null
            val generation = (root["generation"] as? JsonPrimitive)?.takeIf { !it.isString }?.int?.takeIf { it >= 0 } ?: return null
            val from = (root["from"] as? JsonPrimitive)?.takeIf { !it.isString }?.int?.takeIf { it >= 0 } ?: return null
            val items = (root["items"] as? JsonArray)?.map { element ->
                val o = element as? JsonObject ?: return null
                fun string(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val point = string("point")?.takeIf { isHex(it, 64) } ?: return null
                val purpose = string("purpose")?.let(EcdhPurpose::fromWire) ?: return null
                val ref = string("ref")?.also { if (!isHex(it, 64)) return null }
                val evidence = o["evidence"]?.let { Event.fromJson(json.encodeToString(JsonElement.serializer(), it)) }
                val partial = string("partial")?.takeIf { isHex(it, 66) }?.let { PublicKey(it.hexToByteArray()) }
                    ?.takeIf { it.isValid() } ?: return null
                val proof = string("proof")?.let(Dleq.Proof::fromHex) ?: return null
                // What each purpose names its evidence by, and nothing else.
                when (purpose) {
                    EcdhPurpose.WRAP, EcdhPurpose.SEAL -> if (ref == null || evidence != null) return null
                    EcdhPurpose.RECIPIENT -> if (ref != null || evidence == null) return null
                }
                Item(point, purpose, ref, evidence, partial, proof)
            } ?: return null
            if (items.isEmpty()) return null
            GroupEcdhPayload(generation, from, items).takeIf { it.encode() == text }
        }.getOrNull()
    }
}
