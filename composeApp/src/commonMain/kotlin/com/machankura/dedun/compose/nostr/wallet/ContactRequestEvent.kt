package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.MovedKinds
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * A member's message about contacts, as an inner event of the room: `docs/non-member-deposits.md`,
 * D4, D5 and Spike S2. Two forms:
 *
 * ```
 * kind: 30428, pubkey: <member>, content: ""
 *   ["h",       <room id>]
 *   ["request", "reads"]
 *   ["reads",   "contacts/1"]                       this device reads contacts (D5)
 *
 * kind: 30428, pubkey: <member>, content: ""
 *   ["h",        <room id>]
 *   ["request",  "propose"]
 *   ["template", "{\"kind\":30424,\"tags\":[…],\"content\":\"\"}"]   put this to the group (T10)
 * ```
 *
 * ### It carries no authority
 *
 * The quorum decides everything about a contact (the owner's decision 3), so whoever asks decides
 * nothing: signers check the proposal, never the request. That is why it is neither signed nor
 * embedded in what the group signs. The MLS frame says who sent it, which is all a screen needs
 * to say *Put @alice's request to the group*.
 *
 * ### Why `reads` rides here
 *
 * Spike S2 found nothing every build already sends and files silently. An older build shows this
 * as one raw bubble, with a feature name and nothing private in it -- which is the point: the
 * announcement is what keeps a number from reaching a build that would show it raw.
 */
object ContactRequestEvent {
    val KIND: Kind = 30428

    private const val ROOM_TAG = "h"
    private const val REQUEST_TAG = "request"
    private const val READS = "reads"
    private const val PROPOSE = "propose"
    private const val TEMPLATE_TAG = "template"

    /** What this build announces it reads. */
    const val CONTACTS_V1: String = "contacts/1"

    /** The kinds a `propose` request may ask the group to sign: the five group-signed contact kinds, and an attribution, under either of its numbers. */
    val PROPOSABLE: Set<Kind> = setOf(
        ContactRecordEvent.KIND,
        ContactAmendmentEvent.KIND,
        ContactDepositorStatementEvent.KIND,
        ContactClaimEvent.KIND,
        ContactPayoutDestinationEvent.KIND,
        DepositAttributionEvent.KIND,
    )

    sealed interface Request {
        val chatRoomId: String

        data class Reads(override val chatRoomId: String, val features: Set<String>) : Request

        /** An unsigned event for a share-holder to propose: what the requester would have proposed themselves. */
        data class Propose(override val chatRoomId: String, val kind: Kind, val tags: Array<Array<String>>, val content: String) : Request {
            override fun equals(other: Any?): Boolean =
                other is Propose && other.chatRoomId == chatRoomId && other.kind == kind && other.content == content &&
                    other.tags.size == tags.size && other.tags.indices.all { other.tags[it].contentEquals(tags[it]) }

            override fun hashCode(): Int = (chatRoomId.hashCode() * 31 + kind) * 31 + tags.sumOf { it.contentHashCode() }
        }
    }

    fun reads(chatRoomId: String, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(arrayOf(ROOM_TAG, chatRoomId.lowercase()), arrayOf(REQUEST_TAG, READS), arrayOf(READS, CONTACTS_V1)),
            content = "",
        )

    fun propose(chatRoomId: String, proposed: EventTemplate<*>, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        require(MovedKinds.current(proposed.kind) in PROPOSABLE) { "not a kind a contact request may ask for" }
        val json = JsonObject(
            mapOf(
                "kind" to JsonPrimitive(proposed.kind),
                "tags" to JsonArray(proposed.tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }),
                "content" to JsonPrimitive(proposed.content),
            )
        )
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(arrayOf(ROOM_TAG, chatRoomId.lowercase()), arrayOf(REQUEST_TAG, PROPOSE), arrayOf(TEMPLATE_TAG, json.toString())),
            content = "",
        )
    }

    /** What an inner event of this kind asks, or null when it is no request this build reads. */
    fun read(inner: Event): Request? = runCatching {
        if (inner.kind != KIND) return null
        fun values(name: String) = inner.tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }
        val room = values(ROOM_TAG).singleOrNull()?.lowercase() ?: return null
        when (values(REQUEST_TAG).singleOrNull()) {
            READS -> Request.Reads(room, values(READS).toSet()).takeIf { it.features.isNotEmpty() }
            PROPOSE -> {
                val json = Json.parseToJsonElement(values(TEMPLATE_TAG).singleOrNull() ?: return null).jsonObject
                val kind = json["kind"]?.jsonPrimitive?.int?.takeIf { MovedKinds.current(it) in PROPOSABLE } ?: return null
                val tags = json["tags"]?.jsonArray?.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content }.toTypedArray() }?.toTypedArray() ?: return null
                val content = json["content"]?.jsonPrimitive?.content ?: ""
                Request.Propose(room, kind, tags, content)
            }
            else -> null
        }
    }.getOrNull()
}
