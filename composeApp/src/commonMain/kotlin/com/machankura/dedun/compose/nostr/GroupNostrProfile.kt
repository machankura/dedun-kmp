package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Instant

/**
 * The kind:0 a group signed about itself, as the group signed it.
 *
 * A Marmot room's id *is* the nostr pubkey it signs as -- see
 * `docs/shared-key-derivation.md`, where those are one value -- so the group has
 * an identity on nostr whether or not it has ever said anything about it. This is
 * what it says: a name, a picture, a line about what the group is for, authored by
 * the group rather than by whichever member typed it.
 *
 * ### Why this reads off [GroupSignedEvent] and not off `Profile`
 *
 * A `Profile` row hangs off a `NostrEvent` by foreign key, and a group-signed
 * event is not a `NostrEvent`: no member sent it, it never travelled on the wire
 * as itself, and the outbound pipeline would re-author it as its sender and strip
 * the group's signature off. See `GroupSignedEvent`, which exists for exactly the
 * events in that position. So the group's profile lives where the group's other
 * signed statements live, and this is the reading of it.
 *
 * The consequence worth stating: **nothing here reaches a relay by being signed.**
 * A member's kind:0 is published and is how the rest of nostr learns their name; a
 * group's is signed and kept, and every device that followed the signing session
 * -- or was handed the event afterwards -- holds it. It reaches a relay when a
 * member sends it there from `BroadcastGroupSignedEventScreen`, which serialises
 * the signed row directly rather than making a `NostrEvent` row for an event no
 * member authored -- a decision this still does not make.
 *
 * ### What a reading has to earn
 *
 * [GroupSignedEvent.verifies] and nothing less: the author has to be the room, the
 * id has to be the hash of the fields beside it, and the signature has to verify.
 * That is what makes this the *group's* profile rather than a kind:0 that happened
 * to be filed against the room -- and it is checkable from the row alone, with no
 * ceremony or key state to consult first.
 *
 * A room never derived from its group's key signs as the bare threshold key rather
 * than as its own id, so it has no profile *for its own pubkey* and gets none
 * here. That is the derivation's limit rather than this reader's, and it is the
 * same limit `GroupSignedEvent.verifies` documents.
 */
data class GroupNostrProfile(
    /** The group's statement, whole, with the signature that makes it one. */
    val signedEvent: GroupSignedEvent,
    /** Its content parsed: the fields nostr clients read a profile out of. */
    val metadata: UserMetadata,
) {
    /** The group's nostr identity, which for a derived room is also its id. */
    val publicKey: HexKey get() = signedEvent.publicKey

    /** When the group signed it, which is what decides the newest of two. */
    val signedAt: Instant get() = signedEvent.createdAt

    /**
     * What to call the group, or null when it has published no name.
     *
     * `display_name` before `name`, which is what `UserMetadata.anyName` does and
     * what every other nostr client does. Null rather than the pubkey: a caller
     * showing this beside the pubkey would otherwise show it twice.
     */
    fun name(): String? = metadata.anyName()?.takeIf { it.isNotBlank() }

    fun about(): String? = metadata.about?.takeIf { it.isNotBlank() }

    fun picture(): String? = metadata.picture?.takeIf { it.isNotBlank() }

    fun banner(): String? = metadata.banner?.takeIf { it.isNotBlank() }

    fun website(): String? = metadata.website?.takeIf { it.isNotBlank() }

    fun nip05(): String? = metadata.nip05?.takeIf { it.isNotBlank() }

    fun lud16(): String? = metadata.lud16?.takeIf { it.isNotBlank() }

    /** The event as the group signed it, for [MetadataEvent.updateFromPast]. */
    fun asMetadataEvent(): MetadataEvent = signedEvent.toEvent().let { event ->
        MetadataEvent(
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
         * Three ways to be null and they are all the same refusal: the wrong kind,
         * a signature that does not check out as [chatRoomId]'s, or content that
         * will not parse as a profile. A caller gets a profile the group really
         * signed or gets nothing.
         */
        fun of(signedEvent: GroupSignedEvent, chatRoomId: String): GroupNostrProfile? {
            if (signedEvent.kind != MetadataEvent.KIND) return null
            if (!signedEvent.verifies()) return null

            // An empty kind:0 parses to an empty profile rather than to null --
            // wiping a profile is a thing groups are entitled to do, and quartz
            // reads it as the blank one it is. Null here is a parse failure.
            val metadata = MetadataEvent(
                id = signedEvent.id,
                pubKey = signedEvent.publicKey,
                createdAt = signedEvent.createdAt.epochSeconds,
                tags = signedEvent.tags,
                content = signedEvent.content,
                sig = signedEvent.signature,
            ).contactMetaData() ?: return null

            return GroupNostrProfile(signedEvent = signedEvent, metadata = metadata)
        }

        /**
         * The newest profile [chatRoomId] signed among [signedEvents], or null if
         * it signed none.
         *
         * Newest by the timestamp the group signed at, with the id breaking a tie,
         * because kind:0 is replaceable: a group that edits its profile signs a
         * second one and the second is the answer. Two devices reading the same
         * events in whatever order the queries hand them over have to agree on
         * which, and a tie on the second is not rare enough to leave to luck.
         *
         * Takes the events rather than fetching them so the same question can be
         * asked of a query's result or of a flow's emission.
         */
        fun newestAmong(
            signedEvents: List<GroupSignedEvent>,
            chatRoomId: String,
        ): GroupNostrProfile? =
            signedEvents
                .asSequence()
                .mapNotNull { of(it, chatRoomId) }
                .maxWithOrNull(
                    compareBy({ it.signedAt }, { it.signedEvent.id })
                )

        /**
         * The event to ask the group to sign for a profile with these fields.
         *
         * Built from [latest] where the group has one, so a field this app does not
         * offer -- a birthday, a CLINK offer, whatever a future NIP adds -- survives
         * an edit instead of being dropped by it. That is `updateFromPast`'s whole
         * job, and reaching for `createNew` on a group that already has a profile
         * would quietly wipe every such field.
         *
         * Blank means delete, which is `MetadataEvent`'s own rule for these
         * arguments: an empty string removes the key and null leaves it alone. The
         * screen sends what its fields hold, so clearing one clears it in the
         * profile -- which is the only way to *un*-say something a group has said.
         *
         * The lightning address is the one field the screen does not send, and it
         * goes in as null -- *leave it alone* -- rather than as an empty string. Dedun
         * keeps no wallet, so it neither offers a payment address on the group's
         * behalf nor shows one; but one that another tool or a Curare member put on
         * the group's profile is the group's to keep, and an edit made here must not
         * wipe it. docs/group-nostr-profile-and-posts.md, D6.
         *
         * [name] is written to both `name` and `display_name`, which are the two
         * fields readers pick a name out of and which disagree at their peril. A
         * group whose `display_name` was set by some other tool and whose `name` was
         * edited here would keep answering to the old one in every client that
         * prefers `display_name` -- an edit that visibly does not take. One field on
         * the form, both keys in the event.
         */
        fun template(
            latest: GroupNostrProfile?,
            name: String,
            about: String,
            picture: String,
            website: String,
            nip05: String,
        ): EventTemplate<MetadataEvent> = latest?.let {
            MetadataEvent.updateFromPast(
                latest = it.asMetadataEvent(),
                name = name,
                displayName = name,
                about = about,
                picture = picture,
                website = website,
                nip05 = nip05,
            )
        } ?: MetadataEvent.createNew(
            name = name,
            displayName = name,
            about = about,
            picture = picture,
            website = website,
            nip05 = nip05,
        )
    }
}
