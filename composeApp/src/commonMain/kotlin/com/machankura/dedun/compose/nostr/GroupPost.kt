package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.time.Instant

/**
 * A kind:1 the group signed: something it said publicly, as itself.
 *
 * The room's id is the pubkey it signs as, so a note authored by that key is the
 * *group* posting rather than a member posting in the group. Those are different
 * things and the room already has the second one -- the transcript. This is the
 * first: a statement the group put a quorum's signature to, which any reader can
 * check came from the group and not from whoever typed it.
 *
 * ### Why this is not a `Post` row
 *
 * `Post` hangs off both `NostrEvent` and `Profile` by foreign key, and a group has
 * neither: a group-signed event is not a `NostrEvent` -- see `GroupNostrProfile`,
 * which gives the reasoning at length -- and the group has no `Profile` row for the
 * same reason. So a group's posts live where the group's other signed statements
 * live, and this is the reading of them.
 *
 * ### Signing it does not publish it
 *
 * The same caveat `GroupNostrProfile` carries, and it bites hardest here: a post
 * is the one kind of statement whose entire purpose is to be read by people
 * outside the group. `FrostSigningManager.complete` applies signed events locally
 * and puts nothing on the wire, so a group's post is visible to its members alone
 * until one of them sends it to relays from `BroadcastGroupSignedEventScreen`.
 */
data class GroupPost(
    /** The group's statement, whole, with the signature that makes it one. */
    val signedEvent: GroupSignedEvent,
) {
    /** The group's nostr identity, which for a derived room is also its id. */
    val publicKey: HexKey get() = signedEvent.publicKey

    /** When the group signed it, which is the order posts are read in. */
    val postedAt: Instant get() = signedEvent.createdAt

    /** What the group said. */
    val content: String get() = signedEvent.content

    /**
     * Whether this is the group speaking rather than answering.
     *
     * A kind:1 with no `e` tag starts a thread; one with them is a reply. Nothing
     * here writes a reply yet -- the composer builds a bare note -- so this exists
     * to keep an imported or chronicled reply from being drawn as an announcement.
     */
    fun isNewThread(): Boolean = asTextNoteEvent().isNewThread()

    /** The event as the group signed it. */
    fun asTextNoteEvent(): TextNoteEvent = signedEvent.toEvent().let { event ->
        TextNoteEvent(
            id = event.id,
            pubKey = event.pubKey,
            createdAt = event.createdAt,
            tags = event.tags,
            content = event.content,
            sig = event.sig,
        )
    }

    companion object {
        /**
         * The reading of one signed event, or null when it is not one of these.
         *
         * The same gate the profile and the relay lists use: the wrong kind, or a
         * signature that does not check out as [chatRoomId]'s, and a caller gets
         * nothing. A kind:1 a member sent into the room is that member's note, not
         * the group's, and fails on the author.
         */
        fun of(signedEvent: GroupSignedEvent, chatRoomId: String): GroupPost? {
            if (signedEvent.kind != TextNoteEvent.KIND) return null
            if (!signedEvent.verifies()) return null

            return GroupPost(signedEvent)
        }

        /**
         * Every post [chatRoomId] signed among [signedEvents], newest first.
         *
         * Newest first because that is how a feed is read, and the id breaks a tie
         * so two devices holding the same posts show them in the same order. Unlike
         * a profile or a relay list these accumulate rather than replace: kind:1 is
         * not replaceable, so a second post stands beside the first.
         *
         * Blank posts are dropped. A signed note with nothing in it renders as an
         * empty card that cannot be told from a broken one, and the composer will
         * not produce one -- so anything that reaches here empty came from
         * somewhere this app does not control.
         */
        fun newestFirstAmong(
            signedEvents: List<GroupSignedEvent>,
            chatRoomId: String,
        ): List<GroupPost> =
            signedEvents
                .mapNotNull { of(it, chatRoomId) }
                .filter { it.content.isNotBlank() }
                .sortedWith(compareByDescending<GroupPost> { it.postedAt }.thenByDescending {
                    it.signedEvent.id
                })

        /**
         * The event to ask the group to sign for a post saying [note].
         *
         * A bare kind:1: no reply tags, no mentions, no subject. What the group is
         * doing here is speaking, and every tag `TextNoteEvent.build` can add
         * describes a relationship to something else -- a thread to reply into, a
         * pubkey to notify -- which a group's first way of posting does not have
         * and should not guess at.
         */
        fun template(note: String): EventTemplate<TextNoteEvent> =
            TextNoteEvent.build(note = note.trim())
    }
}
