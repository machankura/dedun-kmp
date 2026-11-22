package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

/**
 * What a group's message is, for the admins who sign it (D12 of docs/group-threshold-ecdh.md).
 *
 * A signing proposal reaches every member of the room, and its events are seals, which are
 * ciphertext. A signer also needs to know which seal is for whom and why its device may
 * multiply by each recipient's key. That is this manifest, sealed to the current admins'
 * host keys and carried as proposal tags:
 *
 * ```
 * ["dm_manifest", <content>]      ["dm_slot", <host key>, <sealed content key>]   one per admin
 *
 * {"v":1,"seals":[{"recipient":"<hex>","evidence":<event signed by the recipient>}, …],"replying_to":<id or null>}
 * ```
 *
 * The seals are in the same order as the batch's events. The decoder accepts only its own
 * encoding.
 */
class GroupDmManifest(
    val seals: List<Seal>,
    val replyingTo: HexKey?,
) {
    class Seal(val recipient: HexKey, val evidence: Event)

    val recipients: List<HexKey> get() = seals.map { it.recipient }

    fun encode(): String = Json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("v", VERSION)
        put("seals", buildJsonArray {
            seals.forEach { seal ->
                add(buildJsonObject {
                    put("recipient", seal.recipient)
                    put("evidence", Json.parseToJsonElement(seal.evidence.toJson()))
                })
            }
        })
        put("replying_to", replyingTo?.let(::JsonPrimitive) ?: JsonNull)
    })

    /** As proposal tags, sealed to [adminHostKeys] from the proposer's host key. */
    fun toTags(sealed: AdminSeal.Sealed): Array<Array<String>> =
        (listOf(arrayOf(MANIFEST_TAG, sealed.content)) + AdminSeal.slotTags(sealed.slots, SLOT_TAG)).toTypedArray()

    companion object {
        const val VERSION = 1
        const val MANIFEST_TAG = "dm_manifest"
        const val SLOT_TAG = "dm_slot"

        private fun isHex64(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }

        /** Whether [tags] carry a manifest at all: what marks a batch of seals as the group's message. */
        fun isCarriedBy(tags: Array<Array<String>>): Boolean = tags.any { it.size >= 2 && it[0] == MANIFEST_TAG }

        /** The sealed manifest in [tags], or null if there is none. */
        fun sealedIn(tags: Array<Array<String>>): AdminSeal.Sealed? {
            val content = tags.firstOrNull { it.size >= 2 && it[0] == MANIFEST_TAG }?.get(1) ?: return null
            return AdminSeal.Sealed(content, AdminSeal.parseSlots(tags, SLOT_TAG))
        }

        fun decode(text: String): GroupDmManifest? = runCatching {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            if ((root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.int != VERSION) return null
            val seals = (root["seals"] as? JsonArray)?.map { element ->
                val o = element as? JsonObject ?: return null
                val recipient = (o["recipient"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isHex64) ?: return null
                val evidence = o["evidence"]?.let { Event.fromJson(Json.encodeToString(JsonElement.serializer(), it)) } ?: return null
                Seal(recipient, evidence)
            } ?: return null
            if (seals.isEmpty()) return null
            val replyingTo = when (val r = root["replying_to"]) {
                null, JsonNull -> null
                is JsonPrimitive -> r.takeIf { it.isString }?.content?.takeIf(::isHex64) ?: return null
                else -> return null
            }
            GroupDmManifest(seals, replyingTo).takeIf { it.encode() == text }
        }.getOrNull()
    }
}
