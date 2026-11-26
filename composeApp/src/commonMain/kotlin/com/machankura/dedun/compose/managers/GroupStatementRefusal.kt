package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent

/**
 * A profile or a post the group is asked to sign that nothing would show once it had
 * (docs/group-nostr-profile-and-posts.md, D5).
 *
 * The group's profile is read through `GroupNostrProfile.of` and its posts through
 * `GroupPost.newestFirstAmong`, and both refuse rather than repair: a kind:0 whose
 * content is not a profile, and a kind:1 with nothing in it, would each be signed,
 * filed and shown nowhere, with nothing to tell the group its quorum was spent on
 * nothing. Curare's editors never produce one -- they check before they propose -- but
 * a proposal can come from any build, and Dedun signs only what it can check. So it
 * declines these the way it declines a wallet's: out loud, so the group counts it.
 *
 * The predicates are the ones the paste screen refuses on too
 * (`GroupEventProposal.read`), so the member proposing and the member signing draw the
 * same line.
 */
object GroupStatementRefusal {
    /** What a decline says, on the wire and in this device's transcript. */
    const val REASON = "Dedun doesn't sign a profile or a post that nothing could show"

    /**
     * Whether [content] under [tags] is not a profile at all.
     *
     * An empty kind:0 is a profile -- the group wiping its own, which it is entitled to
     * do -- and quartz reads it as the blank one it is. Only content that will not
     * parse is refused, which is the same line `GroupNostrProfile.of` draws.
     */
    fun isUnreadableProfile(tags: Array<Array<String>>, content: String): Boolean = MetadataEvent(
        id = "",
        pubKey = "",
        createdAt = 0,
        tags = tags,
        content = content,
        sig = "",
    ).contactMetaData() == null

    /**
     * Whether a kind:1 has nothing to say. A blank post cannot be told from a broken
     * one, and a kind:1 is not replaceable, so it could not be taken back either.
     */
    fun isBlankPost(content: String): Boolean = content.isBlank()

    /** Why [event] would be shown nowhere once signed, or null when it would be. */
    fun of(event: Event): String? = when (event.kind) {
        MetadataEvent.KIND -> "is a profile no reader could parse".takeIf {
            isUnreadableProfile(event.tags, event.content)
        }
        TextNoteEvent.KIND -> "is a post with nothing in it".takeIf { isBlankPost(event.content) }
        else -> null
    }

    fun of(events: List<Event>): String? = events.firstNotNullOfOrNull(::of)
}
