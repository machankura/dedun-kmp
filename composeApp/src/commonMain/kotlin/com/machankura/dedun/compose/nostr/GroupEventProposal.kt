package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.managers.GroupStatementRefusal
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * An unsigned event a member pasted for the group to sign, read and checked
 * before a quorum is asked for it.
 *
 * The typed editors -- the profile form and the post composer -- each build one
 * kind of event and know what it needs. This is the untyped way in: the event as
 * JSON, the way another tool prints it, pasted whole. What comes out is the same
 * thing the editors produce, a kind, tags and content for
 * `FrostSigningRepository.proposeSigning`, and it is filed the same way when the
 * quorum signs: `FrostSigningManager.complete` records it as a `GroupSignedEvent`
 * and the group's screen reads its profile and posts straight off those by kind.
 * docs/group-nostr-profile-and-posts.md, D9.
 *
 * ### What is ignored
 *
 * `id`, `pubkey`, `sig` and `created_at`. The group is the author -- the
 * session resolves the room's key and hashes the id over it -- and the time is
 * when the session opens, which is what every typed editor does too. A pasted
 * `created_at` in particular has to go: the kinds here are replaceable or
 * addressable, so a stale one would make the new event *older* than the last and
 * the proposal would look as though it did nothing. The screen says so.
 *
 * ### What is refused, and why before rather than after
 *
 * Only kinds the group's screen has a place for, and only events that place
 * would accept. A signature is the most expensive thing this app does, and the
 * readers refuse rather than repair: a kind 0 whose content is not a profile or
 * a blank post would be signed, filed and shown nowhere, with nothing to tell
 * the member their quorum was spent on nothing. So every check a reader makes is
 * made here first, on the paste -- with the predicates every member's device
 * declines on (`GroupStatementRefusal`), so the proposer and the signer draw the
 * same line.
 */
object GroupEventProposal {
    /**
     * The kinds a pasted event may be: exactly the ones the group's screen reads.
     *
     * Not the kinds `ChatMessage.applyInnerEvent` has an arm for. A subgroup
     * certificate or a key state event has invariants a paste cannot be trusted
     * to meet and a place in the room's life that a form is not. The line is "has
     * a section on the group's screen", because that is where a member goes to see
     * it landed -- Curare's line, less the relay lists Dedun did not bring back
     * (D1) and Curare's schemas.
     */
    val ACCEPTED_KINDS: Set<Kind> = setOf(MetadataEvent.KIND, TextNoteEvent.KIND)

    sealed interface Reading

    /** An event the group could sign and the screen could show. */
    class Ready(
        val kind: Kind,
        val tags: Array<Array<String>>,
        val content: String,
    ) : Reading {
        /**
         * The event as the signing screen will describe it, for a preview before
         * the button is pressed. The id, author and signature are placeholders:
         * `ProposedEvent.summarize` reads none of them.
         */
        fun preview(): Event = Event(
            id = "",
            pubKey = "",
            createdAt = 0,
            kind = kind,
            tags = tags,
            content = content,
            sig = "",
        )
    }

    /** Why the paste cannot be proposed as it is. */
    sealed interface Refused : Reading {
        data object NotJson : Refused

        /** JSON, but an array, a string, a number -- anything but an object. */
        data object NotAnObject : Refused

        data object NoKind : Refused

        data class KindNotAccepted(val kind: Kind) : Refused

        /** `tags` present but not a list of lists of strings. */
        data object TagsMalformed : Refused

        /** `content` present but not a string. */
        data object ContentNotText : Refused

        /** A kind 0 whose content will not parse as a profile. */
        data object ProfileUnreadable : Refused

        /** A kind 1 with nothing to say, which the posts reader would drop. */
        data object PostBlank : Refused
    }

    private const val KEY_KIND = "kind"
    private const val KEY_TAGS = "tags"
    private const val KEY_CONTENT = "content"

    /** The paste as something the group could sign, or the reason it is not. */
    fun read(json: String): Reading {
        val element = runCatching { CommonJson.parseToJsonElement(json) }.getOrNull()
            ?: return Refused.NotJson

        val obj = element as? JsonObject ?: return Refused.NotAnObject

        // A number or a numeric string: relays write the former and a hand
        // types either, and there is nothing to be gained by refusing "1".
        val kind = (obj[KEY_KIND] as? JsonPrimitive)?.intOrNull ?: return Refused.NoKind
        if (kind !in ACCEPTED_KINDS) return Refused.KindNotAccepted(kind)

        val tags = when (val raw = obj[KEY_TAGS]) {
            null -> emptyArray()
            else -> readTags(raw) ?: return Refused.TagsMalformed
        }

        val content = when (val raw = obj[KEY_CONTENT]) {
            null -> ""
            else -> (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Refused.ContentNotText
        }

        return when (kind) {
            // An empty kind 0 is a profile the group has wiped, which it is
            // entitled to do; content that is not JSON is not a profile at all.
            // The same line `GroupNostrProfile.of` draws.
            MetadataEvent.KIND -> if (GroupStatementRefusal.isUnreadableProfile(tags, content)) {
                Refused.ProfileUnreadable
            } else {
                Ready(kind, tags, content)
            }

            // The line `AddGroupPostViewModel.proposePost` draws, for the reason
            // it gives: a blank post cannot be told from a broken one and, kind:1
            // not being replaceable, cannot be un-said.
            TextNoteEvent.KIND -> if (GroupStatementRefusal.isBlankPost(content)) {
                Refused.PostBlank
            } else {
                Ready(kind, tags, content)
            }

            else -> Refused.KindNotAccepted(kind)
        }
    }

    private fun readTags(raw: JsonElement): Array<Array<String>>? {
        val outer = raw as? JsonArray ?: return null

        return outer.map { tag ->
            val inner = tag as? JsonArray ?: return null
            inner.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            }.toTypedArray()
        }.toTypedArray()
    }
}
