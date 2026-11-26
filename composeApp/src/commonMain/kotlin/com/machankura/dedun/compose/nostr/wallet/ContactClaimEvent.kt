package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent.isHex64
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent.single
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Clock

/**
 * The group saying a contact was one of its members: `docs/non-member-deposits.md`, D8.
 *
 * ```
 * kind:    30427
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",       <contact id>]
 *   ["p",       <member>]        or   ["none"]: the contact is nobody's again
 * ```
 *
 * Nothing proves the member holds the contact's number: the group knows who joined, and the
 * quorum decides. Newest wins, so a wrong claim is corrected by signing again -- another member,
 * or `none`. From the newest claim on, every reader resolves the contact to the member.
 */
object ContactClaimEvent {
    val KIND: Kind = 30427

    private const val D_TAG = "d"
    private const val MEMBER_TAG = "p"
    private const val NONE_TAG = "none"

    /** [contactId] is [member]'s, or nobody's when [member] is null. */
    data class Claim(val contactId: HexKey, val member: HexKey?, val event: Event) {
        val createdAt: Long get() = event.createdAt
    }

    fun template(contactId: HexKey, member: HexKey?, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(arrayOf(D_TAG, contactId.lowercase()), member?.let { arrayOf(MEMBER_TAG, it.lowercase()) } ?: arrayOf(NONE_TAG)),
            content = "",
        )

    fun read(event: Event): Claim? = runCatching {
        if (event.kind != KIND) return null
        val contactId = single(event, D_TAG)?.get(1)?.lowercase()?.takeIf(::isHex64) ?: return null
        val members = event.tags.filter { it.isNotEmpty() && it[0] == MEMBER_TAG }
        val none = event.tags.any { it.isNotEmpty() && it[0] == NONE_TAG }
        val member = when {
            members.size == 1 && !none -> members[0].getOrNull(1)?.lowercase()?.takeIf(::isHex64) ?: return null
            members.isEmpty() && none -> null
            else -> return null
        }
        if (member == contactId) return null
        Claim(contactId, member, event)
    }.getOrNull()

    fun verify(event: Event, chatRoomId: String): Claim? =
        read(event)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /** Each contact's newest claim among [events], `none` included. */
    fun newestPerContact(events: List<Event>, chatRoomId: String): Map<HexKey, Claim> =
        events.asSequence()
            .mapNotNull { verify(it, chatRoomId) }
            .groupBy { it.contactId }
            .mapValues { (_, claims) -> claims.maxWith(compareBy<Claim>({ it.createdAt }, { it.event.id })) }

    /** Contact id → member key, for every contact whose newest claim names one: what [com.machankura.dedun.compose.contacts.Party.resolve] takes. */
    fun claimsIn(events: List<Event>, chatRoomId: String): Map<HexKey, HexKey> =
        newestPerContact(events, chatRoomId).mapNotNull { (id, claim) -> claim.member?.let { id to it } }.toMap()
}
