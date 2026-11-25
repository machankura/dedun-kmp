package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Clock

/**
 * The group agreeing to count a person outside it: `docs/non-member-deposits.md`, D3.
 *
 * ```
 * kind:    30394
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",          <contact id>]
 *   ["p",          <proposer>]                       shown as "added by", decides nothing
 *   ["name",       "Mum"]
 *   ["identifier", "phone" | "email", "+27821234567"]
 * ```
 *
 * ### Signed whole, in the clear
 *
 * The quorum that signs a record is looking at the name and the number, and that is the decision:
 * this person may be counted. No device counts, attributes to or pays a contact without one. The
 * number is therefore in the group's permanent record and reaches every member added later,
 * which the owner decided (the plan's decision 1).
 *
 * ### The id is derived, and one per number
 *
 * `d` is [ContactIdentifier.contactId], which every reader recomputes. A proposer can't steer it
 * onto a member's key, and the same number in the same room is always the same id -- so the
 * record is write-once and a second for that number is a second, refused.
 *
 * ### Why 30394
 *
 * After the member transfer, 30393: the first of the six contact kinds, 30394-30399 (`docs/kinds.md`).
 */
object ContactRecordEvent {
    val KIND: Kind = 30394

    private const val D_TAG = "d"
    private const val PROPOSER_TAG = "p"
    private const val NAME_TAG = "name"
    private const val IDENTIFIER_TAG = "identifier"

    /** The most characters a contact's name may have. */
    const val MAX_NAME_CHARS: Int = 64

    /** What a contact record says. */
    data class Record(
        val contactId: HexKey,
        val proposer: HexKey,
        val name: String,
        val identifier: ContactIdentifier,
        val event: Event,
    ) {
        val createdAt: Long get() = event.createdAt
    }

    /** Whether [name] is one a contact may have: trimmed already, 1 to [MAX_NAME_CHARS] characters, no control characters. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name == name.trim() && name.length <= MAX_NAME_CHARS && name.none { it.isISOControl() }

    fun template(chatRoomId: String, proposer: HexKey, name: String, identifier: ContactIdentifier, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        require(isValidName(name)) { "not a name a contact may have" }
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier.contactId(chatRoomId)),
                arrayOf(PROPOSER_TAG, proposer.lowercase()),
                arrayOf(NAME_TAG, name),
                arrayOf(IDENTIFIER_TAG, identifier.type, identifier.text),
            ),
            content = "",
        )
    }

    /**
     * What [event] says about [chatRoomId], by its shape -- every check but the room's signature, for
     * a signer reading a proposal: one each of the four tags, a name a contact may have, an
     * identifier already in its canonical spelling, and a `d` that recomputes from it.
     */
    fun read(event: Event, chatRoomId: String): Record? = runCatching {
        if (event.kind != KIND) return null
        val proposer = single(event, PROPOSER_TAG)?.get(1)?.lowercase()?.takeIf(::isHex64) ?: return null
        val name = single(event, NAME_TAG)?.get(1)?.takeIf(::isValidName) ?: return null
        val identifierTag = single(event, IDENTIFIER_TAG)?.takeIf { it.size >= 3 } ?: return null
        val identifier = ContactIdentifier.parse(identifierTag[1], identifierTag[2]) ?: return null
        val contactId = identifier.contactId(chatRoomId)
        if (single(event, D_TAG)?.get(1) != contactId) return null
        Record(contactId, proposer, name, identifier, event)
    }.getOrNull()

    /** [event] as [chatRoomId]'s record: [read], and signed by the room itself. */
    fun verify(event: Event, chatRoomId: String): Record? =
        read(event, chatRoomId)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /** Each contact's record among [events]: the first the group signed, by its own timestamp with the id breaking a tie. */
    fun firstPerContact(events: List<Event>, chatRoomId: String): Map<HexKey, Record> =
        events.asSequence()
            .mapNotNull { verify(it, chatRoomId) }
            .groupBy { it.contactId }
            .mapValues { (_, records) -> records.minWith(ORDER) }

    val ORDER: Comparator<Record> = compareBy<Record>({ it.createdAt }, { it.event.id })

    internal fun single(event: Event, name: String): Array<String>? =
        event.tags.filter { it.isNotEmpty() && it[0] == name }.singleOrNull()?.takeIf { it.size >= 2 }

    internal fun isHex64(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
}
