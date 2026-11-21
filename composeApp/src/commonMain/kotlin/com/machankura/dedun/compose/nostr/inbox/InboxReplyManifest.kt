package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a signer of a reply needs beside the seals themselves (docs/group-inbox.md, D11): which
 * seal is for whom, the key each was sealed to, and the evidence for that key.
 *
 * It rides the FROST proposal as an `inbox_manifest` tag, encrypted to the inbox key, so a
 * member of the room who is not an admin sees how many seals the group is signing and nothing
 * else: not the reply, and not who it goes to.
 *
 * The encoding is this app's own and is decoded strictly: a manifest with a field it does not
 * know, or one missing, is no manifest, and a reply carrying one is declined.
 */
data class InboxReplyManifest(
    /** The inbox key generation every seal is sealed from. */
    val generation: Int,
    val seals: List<Entry>,
    /** Which of [seals] is the group's own copy, sealed to the inbox key itself. */
    val groupCopy: Int,
) {
    data class Entry(
        val recipient: HexKey,
        /** The key the seal is encrypted to. */
        val key: HexKey,
        /** The recipient's signed 10044, as JSON, when [key] came from one; null when [key] is their identity. */
        val announcement: String?,
    )

    fun encode(): String = buildJsonObject {
        put("v", 1)
        put("generation", generation)
        put("seals", buildJsonArray {
            seals.forEach { entry ->
                add(buildJsonObject {
                    put("recipient", entry.recipient)
                    put("key", entry.key)
                    put("announcement", entry.announcement?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
        put("group_copy", groupCopy)
    }.toString()

    /** Encrypted under `conv(e, E)`: only a holder of this generation's key reads it. */
    fun seal(inboxSecret: ByteArray): String =
        InboxWire.encrypt(encode(), selfConversationKey(inboxSecret))

    companion object {
        const val TAG_NAME = "inbox_manifest"

        private val json = Json { ignoreUnknownKeys = false }
        private val TOP = setOf("v", "generation", "seals", "group_copy")
        private val ENTRY = setOf("recipient", "key", "announcement")

        fun tag(sealed: String): Array<String> = arrayOf(TAG_NAME, sealed)

        /** The sealed manifest among a proposal's tags, or null. More than one is none. */
        fun sealedIn(tags: Array<Array<String>>): String? =
            tags.filter { it.isNotEmpty() && it[0] == TAG_NAME }.singleOrNull()?.getOrNull(1)?.ifBlank { null }

        fun selfConversationKey(inboxSecret: ByteArray): ByteArray =
            InboxWire.conversationKey(inboxSecret, InboxWire.publicKeyOf(inboxSecret))

        /** [sealed] opened with [inboxSecret], or null if that key does not open it or it is not a manifest. */
        fun open(sealed: String, inboxSecret: ByteArray): InboxReplyManifest? =
            InboxWire.decrypt(sealed, selfConversationKey(inboxSecret))?.let(::decode)

        fun decode(text: String): InboxReplyManifest? = runCatching {
            val obj = json.parseToJsonElement(text) as JsonObject
            require(obj.keys == TOP)
            require((obj["v"] as JsonPrimitive).content == "1")
            val generation = (obj["generation"] as JsonPrimitive).content.toInt().also { require(it >= 0) }
            val seals = (obj["seals"] as JsonArray).map { element ->
                val entry = element as JsonObject
                require(entry.keys == ENTRY)
                val announcement = entry["announcement"]
                Entry(
                    recipient = (entry["recipient"] as JsonPrimitive).content.also { require(InboxWire.isHex32(it)) },
                    key = (entry["key"] as JsonPrimitive).content.also { require(InboxWire.isHex32(it)) },
                    announcement = if (announcement is JsonNull) null else (announcement as JsonPrimitive).also { require(it.isString) }.content,
                )
            }
            val groupCopy = (obj["group_copy"] as JsonPrimitive).content.toInt()
            require(groupCopy in seals.indices)
            InboxReplyManifest(generation, seals, groupCopy)
        }.getOrNull()
    }
}
