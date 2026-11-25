package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.managers.MemberWalletManager.asEvent
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.nostr.wallet.ContactClaimEvent
import com.machankura.dedun.compose.nostr.wallet.ContactDepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.ContactPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The people outside a group that the group has agreed to count: `docs/non-member-deposits.md`.
 *
 * Read from the group's signed events, so a device that joined late has them from the chronicle,
 * and nothing about a contact is believed without a verified record (D1): an amendment, a claim,
 * a branch or a destination for an id with no record is ignored.
 */
object Contacts {

    /** Every kind the group signs about contacts. */
    val SIGNED_KINDS: Set<Kind> = setOf(
        ContactRecordEvent.KIND,
        ContactAmendmentEvent.KIND,
        ContactDepositorStatementEvent.KIND,
        ContactClaimEvent.KIND,
        ContactPayoutDestinationEvent.KIND,
    )

    /** Every contact kind, the member's request included: none is ever a chat bubble (D5). */
    val ALL_KINDS: Set<Kind> = SIGNED_KINDS + ContactRequestEvent.KIND

    /** A contact as the group's records leave them: the record, the name amendments give, and whether closed or claimed. */
    data class Contact(
        val record: ContactRecordEvent.Record,
        val name: String,
        val closed: Boolean,
        /** The member the group's newest claim says the contact was, or null. */
        val claimedBy: HexKey?,
    ) {
        val id: HexKey get() = record.contactId
        val identifier: ContactIdentifier get() = record.identifier
        val proposer: HexKey get() = record.proposer

        /** Whether new deposits may be attributed to, or opened for, the contact: neither closed nor claimed. */
        val isOpen: Boolean get() = !closed && claimedBy == null

        /** Whom the contact's deposits and coins belong to now. */
        val party: Party get() = claimedBy?.let { Party.Member(it) } ?: Party.Contact(id)
    }

    private suspend fun signed(database: DedunDatabase, chatRoomId: String, kind: Kind): List<Event> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, kind).map { it.toEvent() }

    /** Every contact the group has a record of in [chatRoomId], by id. */
    suspend fun contactsIn(database: DedunDatabase, chatRoomId: String): Map<HexKey, Contact> {
        val records = ContactRecordEvent.firstPerContact(signed(database, chatRoomId, ContactRecordEvent.KIND), chatRoomId)
        if (records.isEmpty()) return emptyMap()
        val states = ContactAmendmentEvent.stateOf(signed(database, chatRoomId, ContactAmendmentEvent.KIND), chatRoomId)
        val claims = ContactClaimEvent.claimsIn(signed(database, chatRoomId, ContactClaimEvent.KIND), chatRoomId)
        return records.mapValues { (id, record) ->
            val state = states[id] ?: ContactAmendmentEvent.State.NONE
            Contact(record, state.name ?: record.name, state.closed, claims[id])
        }
    }

    suspend fun contactOf(database: DedunDatabase, chatRoomId: String, contactId: HexKey): Contact? =
        contactsIn(database, chatRoomId)[contactId.lowercase()]

    /** What the group calls [contactId] now, or null when it has no record of them. */
    suspend fun nameOf(database: DedunDatabase, chatRoomId: String, contactId: HexKey): String? =
        contactOf(database, chatRoomId, contactId)?.name

    /** Contact id → member key, for every recorded contact the group has said a member was: what [Party.resolve] takes. */
    suspend fun claimsIn(database: DedunDatabase, chatRoomId: String): Map<HexKey, HexKey> =
        contactsIn(database, chatRoomId).mapNotNull { (id, contact) -> contact.claimedBy?.let { id to it } }.toMap()

    /** Each recorded contact's deposit branch in [chatRoomId] on [chain]. */
    suspend fun depositorsIn(database: DedunDatabase, chatRoomId: String, chain: Chain): Map<HexKey, ContactDepositorStatementEvent.Depositor> {
        val recorded = contactsIn(database, chatRoomId).keys
        if (recorded.isEmpty()) return emptyMap()
        return ContactDepositorStatementEvent.firstPerContact(signed(database, chatRoomId, ContactDepositorStatementEvent.KIND), chatRoomId, chain)
            .filterKeys { it in recorded }
    }

    /**
     * Where the group pays each recorded, unclaimed contact on [chain]: their newest 30399, unless it
     * says removed (D9). A claimed contact is paid as the member, at the member's own destination.
     */
    suspend fun destinationsIn(database: DedunDatabase, chatRoomId: String, chain: Chain): Map<HexKey, ContactPayoutDestinationEvent.Destination> {
        val unclaimed = contactsIn(database, chatRoomId).filterValues { it.claimedBy == null }.keys
        if (unclaimed.isEmpty()) return emptyMap()
        return ContactPayoutDestinationEvent.newestPerContact(signed(database, chatRoomId, ContactPayoutDestinationEvent.KIND), chatRoomId, chain)
            .filter { (id, destination) -> id in unclaimed && !destination.removed }
    }

    /** Every destination the group ever signed for a recorded contact on [chain], not only the newest: what an output it paid could have paid. */
    suspend fun everSignedIn(database: DedunDatabase, chatRoomId: String, chain: Chain): List<ContactPayoutDestinationEvent.Destination> {
        val recorded = contactsIn(database, chatRoomId).keys
        if (recorded.isEmpty()) return emptyList()
        return signed(database, chatRoomId, ContactPayoutDestinationEvent.KIND)
            .mapNotNull { ContactPayoutDestinationEvent.verify(it, chatRoomId) }
            .filter { it.chain == chain && !it.removed && it.contactId in recorded }
    }

    // ---- What a signer checks (D7) ----

    /**
     * Why a signer won't sign [proposed], one of the five group-signed contact kinds for [chatRoomId],
     * or null when it will. Readers ship a release ahead of proposers (D11), so this build reads every
     * contact kind and signs none.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event): String? =
        if (proposed.kind in SIGNED_KINDS) NOT_YET else null

    /** What a build that reads contacts but doesn't yet sign for them says (D11). */
    const val NOT_YET: String = "is about a contact, which this build doesn't sign for yet"

    // ---- The announcement (D5) ----

    /** The members of [chatRoomId] whose devices have said they read contacts. */
    suspend fun announcedIn(database: DedunDatabase, chatRoomId: String): Set<HexKey> =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(ContactRequestEvent.KIND))
            .mapNotNull { row ->
                val request = ContactRequestEvent.read(row.asEvent()) as? ContactRequestEvent.Request.Reads ?: return@mapNotNull null
                row.publicKey.lowercase().takeIf {
                    request.chatRoomId.equals(chatRoomId, ignoreCase = true) && ContactRequestEvent.CONTACTS_V1 in request.features
                }
            }
            .toSet()

    /** The current members of [chatRoomId] who haven't said they read contacts: who a contact proposal waits for. */
    suspend fun waitingFor(database: DedunDatabase, chatRoomId: String): List<HexKey> {
        val announced = announcedIn(database, chatRoomId)
        return database.participantDao().findParticipantsByChatRoomId(chatRoomId)
            .map { it.participantPublicKey.lowercase() }
            .filter { it !in announced }
    }

    /** Whether every current member of [chatRoomId] has said they read contacts (D5). */
    suspend fun everyoneReads(database: DedunDatabase, chatRoomId: String): Boolean = waitingFor(database, chatRoomId).isEmpty()

    /** Says, in [chatRoomId], that [userPublicKey]'s device reads contacts. Returns the inner event's id. */
    suspend fun announce(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): HexKey {
        val template = ContactRequestEvent.reads(chatRoomId)
        return MemberWalletManager.queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            tags = template.tags,
            content = template.content,
            kind = ContactRequestEvent.KIND,
            createdAt = Clock.System.now(),
        )
    }

    /**
     * Announces for [userPublicKey] in [chatRoomId] unless this device already has: the first time
     * it sees the room. Returns whether it sent one.
     */
    suspend fun announceIfNeeded(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): Boolean {
        if (userPublicKey.lowercase() in announcedIn(database, chatRoomId)) return false
        announce(database, chatRoomId, userPublicKey)
        return true
    }
}
