package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
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
     * or null when it will (D7). The quorum is supreme, so who proposed it is never asked: only that
     * it is well formed, consistent with the records this device holds, and proposed in a room where
     * every device reads contacts (D5).
     */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event): String? {
        if (proposed.kind !in SIGNED_KINDS) return null
        if (!everyoneReads(database, chatRoomId)) return WAITING
        val contacts = contactsIn(database, chatRoomId)
        return when (proposed.kind) {
            ContactRecordEvent.KIND -> {
                val record = ContactRecordEvent.read(proposed, chatRoomId) ?: return "does not read as a contact for this group"
                if (record.contactId in contacts) "adds a contact the group already counts, as ${contacts.getValue(record.contactId).name}" else null
            }
            ContactAmendmentEvent.KIND -> {
                val amendment = ContactAmendmentEvent.read(proposed) ?: return "does not read as an amendment"
                val contact = contacts[amendment.contactId] ?: return NO_RECORD
                when (amendment.change) {
                    is ContactAmendmentEvent.Change.Rename -> null
                    ContactAmendmentEvent.Change.Close -> if (contact.closed) "closes a contact the group already closed" else null
                    ContactAmendmentEvent.Change.Reopen -> if (!contact.closed) "reopens a contact the group never closed" else null
                }
            }
            ContactClaimEvent.KIND -> {
                val claim = ContactClaimEvent.read(proposed) ?: return "does not read as a claim"
                val contact = contacts[claim.contactId] ?: return NO_RECORD
                when {
                    claim.member == contact.claimedBy -> "says what the group already says"
                    claim.member != null && claim.member !in currentMembers(database, chatRoomId) -> "says a contact is somebody who is not in the group"
                    else -> null
                }
            }
            ContactPayoutDestinationEvent.KIND -> {
                val destination = ContactPayoutDestinationEvent.read(proposed) ?: return "does not read as a destination"
                val contact = contacts[destination.contactId] ?: return NO_RECORD
                when {
                    contact.claimedBy != null -> "gives a destination to a contact the group said is a member, who is paid at their own"
                    // The check a Wavelength payout already makes between members: one address, one payee (D9).
                    destination.onchainAddress != null && GroupPayoutDestinations.payoutDestinationsIn(database, chatRoomId, destination.chain)
                        .any { (payee, other) -> payee != destination.contactId && other.onchainAddress == destination.onchainAddress } ->
                        "pays a contact at an address somebody else is paid at"
                    else -> null
                }
            }
            ContactDepositorStatementEvent.KIND -> {
                val depositor = ContactDepositorStatementEvent.read(proposed, chatRoomId) ?: return "does not read as a depositor statement for this group"
                val contact = contacts[depositor.contactId] ?: return NO_RECORD
                when {
                    !contact.isOpen -> "opens deposits for a contact the group closed or said is a member"
                    depositor.contactId in depositorsIn(database, chatRoomId, depositor.chain) -> "opens deposits the group has already opened"
                    depositor.branch in Depositors.branchesIn(database, chatRoomId, depositor.chain).values -> "gives the contact a branch somebody already has"
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Why a signer won't give a deposit to [contactId] in [chatRoomId], or null when it will: D7's row for an attribution. */
    suspend fun refuseAsPayee(database: DedunDatabase, chatRoomId: String, contactId: HexKey): String? {
        if (!everyoneReads(database, chatRoomId)) return WAITING
        val contact = contactOf(database, chatRoomId, contactId) ?: return NO_RECORD
        return when {
            contact.claimedBy != null -> "gives a deposit to a contact the group said is a member: it is theirs"
            contact.closed -> "gives a deposit to a contact the group closed"
            else -> null
        }
    }

    private suspend fun currentMembers(database: DedunDatabase, chatRoomId: String): Set<HexKey> =
        database.participantDao().findParticipantsByChatRoomId(chatRoomId).map { it.participantPublicKey.lowercase() }.toSet()

    const val NO_RECORD: String = "names a contact the group has no record of"
    const val WAITING: String = "is about a contact, in a room where not every device reads contacts yet"

    // ---- Proposing, and asking (T10) ----

    /** Asks the group to sign [template], one of the contact kinds or an attribution, from a device that holds a share. */
    suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, proposer: HexKey, template: EventTemplate<*>): FrostSigningSession =
        FrostSigningManager.proposeSigning(database, localChatRoom, proposer, template.kind, template.tags, template.content)

    /** Asks a device that holds a share to put [template] to the group, for [member] who holds none: a 30398 into the room. */
    suspend fun ask(database: DedunDatabase, chatRoomId: String, member: HexKey, template: EventTemplate<*>): HexKey {
        val request = ContactRequestEvent.propose(chatRoomId, template)
        return MemberWalletManager.queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = member,
            tags = request.tags,
            content = request.content,
            kind = ContactRequestEvent.KIND,
            createdAt = Clock.System.now(),
        )
    }

    /** A member's request a share-holder can put to the group: who asked, when, and what. */
    data class PendingRequest(val id: HexKey, val member: HexKey, val askedAt: Long, val request: ContactRequestEvent.Request.Propose) {
        val template: EventTemplate<*> get() = EventTemplate<Nothing>(Clock.System.now().epochSeconds, request.kind, request.tags, request.content)
    }

    /** How long a request waits to be put to the group before it is let go. */
    const val REQUEST_LASTS_SECONDS: Long = 7L * 24 * 60 * 60

    /**
     * The requests in [chatRoomId] nobody has put to the group yet: asked in the last week, and not
     * yet signed -- the group has no event of that kind with the same tags.
     */
    suspend fun pendingRequestsIn(database: DedunDatabase, chatRoomId: String, nowSeconds: Long = Clock.System.now().epochSeconds): List<PendingRequest> {
        val requests = database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(ContactRequestEvent.KIND))
            .filter { nowSeconds - it.createdAt.epochSeconds <= REQUEST_LASTS_SECONDS }
            .mapNotNull { row ->
                val propose = ContactRequestEvent.read(row.asEvent()) as? ContactRequestEvent.Request.Propose ?: return@mapNotNull null
                if (!propose.chatRoomId.equals(chatRoomId, ignoreCase = true)) return@mapNotNull null
                PendingRequest(row.id, row.publicKey.lowercase(), row.createdAt.epochSeconds, propose)
            }
        if (requests.isEmpty()) return emptyList()
        val signed = requests.map { it.request.kind }.distinct().associateWith { kind ->
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, kind).map { it.toEvent().tags.map(Array<String>::toList) }.toSet()
        }
        return requests
            .filter { pending -> pending.request.tags.map(Array<String>::toList) !in signed.getValue(pending.request.kind) }
            .distinctBy { it.request }
            .sortedByDescending { it.askedAt }
    }

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
