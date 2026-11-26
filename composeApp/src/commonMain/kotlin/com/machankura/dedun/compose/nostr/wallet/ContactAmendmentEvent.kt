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
 * The group changing what it calls a contact, or whether it still counts new deposits for them:
 * `docs/non-member-deposits.md`, D4.
 *
 * ```
 * kind:    30425
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",       <contact id>]
 *   ["name",    "Gogo"]          or   ["closed"]   or   ["open"]      exactly one
 * ```
 *
 * It never carries an identifier. The group agreed to that number; another number is another
 * contact, or a contact could be pointed at somebody else after the fact (D2).
 *
 * Applied in the group's order, each field by the newest that sets it: a rename after a close
 * leaves the contact closed. [stateOf] is the fold.
 */
object ContactAmendmentEvent {
    val KIND: Kind = 30425

    private const val D_TAG = "d"
    private const val NAME_TAG = "name"
    private const val CLOSED_TAG = "closed"
    private const val OPEN_TAG = "open"
    private const val IDENTIFIER_TAG = "identifier"

    sealed interface Change {
        data class Rename(val name: String) : Change
        data object Close : Change
        data object Reopen : Change
    }

    data class Amendment(val contactId: HexKey, val change: Change, val event: Event) {
        val createdAt: Long get() = event.createdAt
    }

    /** What a contact's amendments come to: the name they give, if any, and whether the contact is closed. */
    data class State(val name: String?, val closed: Boolean) {
        companion object {
            val NONE = State(null, false)
        }
    }

    fun template(contactId: HexKey, change: Change, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        val tag = when (change) {
            is Change.Rename -> {
                require(ContactRecordEvent.isValidName(change.name)) { "not a name a contact may have" }
                arrayOf(NAME_TAG, change.name)
            }
            Change.Close -> arrayOf(CLOSED_TAG)
            Change.Reopen -> arrayOf(OPEN_TAG)
        }
        return EventTemplate<Nothing>(createdAt = createdAt, kind = KIND, tags = arrayOf(arrayOf(D_TAG, contactId.lowercase()), tag), content = "")
    }

    fun read(event: Event): Amendment? = runCatching {
        if (event.kind != KIND) return null
        val contactId = single(event, D_TAG)?.get(1)?.lowercase()?.takeIf(::isHex64) ?: return null
        // Never who the contact is: another number is another contact (D2).
        if (event.tags.any { it.isNotEmpty() && it[0] == IDENTIFIER_TAG }) return null
        val changes = event.tags.filter { it.isNotEmpty() && it[0] in setOf(NAME_TAG, CLOSED_TAG, OPEN_TAG) }
        if (changes.size != 1) return null
        val change = when (changes[0][0]) {
            NAME_TAG -> changes[0].getOrNull(1)?.takeIf(ContactRecordEvent::isValidName)?.let(Change::Rename) ?: return null
            CLOSED_TAG -> Change.Close
            else -> Change.Reopen
        }
        Amendment(contactId, change, event)
    }.getOrNull()

    fun verify(event: Event, chatRoomId: String): Amendment? =
        read(event)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /** Every contact's [State] among [events], each amendment applied in the group's order. */
    fun stateOf(events: List<Event>, chatRoomId: String): Map<HexKey, State> =
        events.mapNotNull { verify(it, chatRoomId) }
            .sortedWith(compareBy<Amendment>({ it.createdAt }, { it.event.id }))
            .fold(mutableMapOf<HexKey, State>()) { states, amendment ->
                val state = states[amendment.contactId] ?: State.NONE
                states[amendment.contactId] = when (val change = amendment.change) {
                    is Change.Rename -> state.copy(name = change.name)
                    Change.Close -> state.copy(closed = true)
                    Change.Reopen -> state.copy(closed = false)
                }
                states
            }
}
