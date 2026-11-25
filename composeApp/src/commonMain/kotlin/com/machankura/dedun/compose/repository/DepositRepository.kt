package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.nostr.wallet.PayoutDestination
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.managers.Contacts
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.wallet.GroupOnchainWallets

/**
 * Who deposits with a group, for the screens: whether the active profile can be handed an
 * address or an invoice yet, and opening a member's deposits. `docs/member-deposits.md`.
 */
interface DepositRepository {

    /** Where the active profile stands in [chatRoomId] on [chain], as the receive card says it. */
    sealed interface Standing {
        /** A profile that can't sign: it sees tallies, and is handed nothing. */
        data object CannotSign : Standing

        /** No payout destination yet, so nothing can be attributed to them (D1). */
        data object NeedsDestination : Standing

        /** A destination, and the group has not opened their deposits. [canPropose] when this device holds a share. */
        data class CanOpen(val canPropose: Boolean) : Standing

        /** A depositor: their branch is theirs. */
        data class Open(val depositor: DepositorStatementEvent.Depositor) : Standing
    }

    suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): Standing

    /** Every member the group has opened deposits for, by member. */
    suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor>

    /** Whether [member] has a destination and no depositor statement yet, so a signer could open them. */
    suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean

    /** Proposes opening [member]'s deposits, as the active profile, which has to hold a share. */
    suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession

    /**
     * Proposes opening the active profile's own deposits in [chatRoomId] when it can now: it has a
     * destination, the group has not opened its deposits, and this device holds a share to propose
     * with. What saving a first destination leads to. Null when there is nothing to propose.
     */
    suspend fun openOwnIfReady(chatRoomId: String, chain: Chain): FrostSigningSession?

    /** Files the outputs that paid the room's receive and deposit branches (Phase 8). */
    suspend fun recordOnchain(chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>)

    /** Every member's tally, the unattributed and the total (Phase 8). */
    suspend fun tallies(chatRoomId: String, chain: Chain): DepositLedger.Tallies

    /** The people outside [chatRoomId] the group has agreed to count, by id: `docs/non-member-deposits.md`. */
    suspend fun contacts(chatRoomId: String): Map<HexKey, Contacts.Contact>

    /** Every deposit on record, newest first, with the group's signed attributions applied. */
    suspend fun deposits(chatRoomId: String, chain: Chain): List<DepositRow>

    /** Proposes that the group sign whose each deposit in [assignments] is -- a member, or null for the group's own (Phase 9). */
    suspend fun attribute(localChatRoom: LocalChatRoom, chain: Chain, assignments: Map<String, Party?>): FrostSigningSession

    /** Every deposit the group counts to [contactId], as it was given: before any claim resolves it (`docs/non-member-deposits.md`, Phase 4). */
    suspend fun depositsOf(chatRoomId: String, chain: Chain, contactId: HexKey): List<DepositRow> = emptyList()

    /** Where the group pays [contactId] on [chain], or null while it has said nowhere or the contact is claimed (D9). */
    suspend fun contactDestination(chatRoomId: String, chain: Chain, contactId: HexKey): PayoutDestination? = null

    /** The current members whose devices haven't said they read contacts: who adding one waits for (`docs/non-member-deposits.md`, D5). */
    suspend fun contactsWaitingFor(chatRoomId: String): List<HexKey> = emptyList()

    /**
     * Proposes the contact [identifier] called [name] -- straight to the group from a device that holds
     * a share, or as a request for one that does (T10). Null when nothing could be sent.
     */
    suspend fun addContact(localChatRoom: LocalChatRoom, identifier: ContactIdentifier, name: String): ContactOutcome? = null

    /** Proposes, or asks for, a contact's amendment. */
    suspend fun amendContact(localChatRoom: LocalChatRoom, contactId: HexKey, change: ContactAmendmentEvent.Change): ContactOutcome? = null

    /** Proposes, or asks for, the group saying [contactId] was [member] -- or nobody in the group, when null (D8). */
    suspend fun claimContact(localChatRoom: LocalChatRoom, contactId: HexKey, member: HexKey?): ContactOutcome? = null

    /**
     * Proposes, or asks for, where the group pays [contactId] on [chain] -- or, with neither address,
     * that it pays them nowhere there (D9). The addresses are checked by the caller first.
     */
    suspend fun setContactDestination(localChatRoom: LocalChatRoom, chain: Chain, contactId: HexKey, lightning: LightningAddress?, onchain: String?): ContactOutcome? = null

    /** Asks a share-holder to give [depositKey] to [party], from a member who holds no share. */
    suspend fun askToAttribute(chatRoomId: String, chain: Chain, depositKey: String, party: Party?): Boolean = false

    /** The members' requests nobody has put to the group yet. */
    suspend fun pendingRequests(chatRoomId: String): List<Contacts.PendingRequest> = emptyList()

    /** Puts [request] to the group, from a device that holds a share. */
    suspend fun putToGroup(localChatRoom: LocalChatRoom, request: Contacts.PendingRequest): FrostSigningSession? = null

    /** What a contact proposal came to. */
    sealed interface ContactOutcome {
        data class Proposed(val session: FrostSigningSession) : ContactOutcome
        data object Asked : ContactOutcome
    }

    companion object {
        val NO_OP = object : DepositRepository {
            override suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): Standing = Standing.CannotSign
            override suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor> = emptyMap()
            override suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean = false
            override suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
            override suspend fun openOwnIfReady(chatRoomId: String, chain: Chain): FrostSigningSession? = null
            override suspend fun recordOnchain(chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>) = Unit
            override suspend fun tallies(chatRoomId: String, chain: Chain) = DepositLedger.Tallies(emptyMap(), 0, 0)
            override suspend fun contacts(chatRoomId: String): Map<HexKey, Contacts.Contact> = emptyMap()
            override suspend fun deposits(chatRoomId: String, chain: Chain): List<DepositRow> = emptyList()
            override suspend fun attribute(localChatRoom: LocalChatRoom, chain: Chain, assignments: Map<String, Party?>): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
        }
    }
}
