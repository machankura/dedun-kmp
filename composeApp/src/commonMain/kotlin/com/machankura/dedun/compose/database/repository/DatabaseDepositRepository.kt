package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.nostr.wallet.PayoutDestination
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.managers.Contacts
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.repository.DepositRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DatabaseDepositRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
) : DepositRepository {
    private val logger = Logger.withTag(TAG)

    override suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): DepositRepository.Standing = try {
        val identity = activeIdentity.value
        val me = identity?.nostrPublicKey
        when {
            identity == null || me == null || !identity.canSign -> DepositRepository.Standing.CannotSign
            else -> Depositors.statementOf(database, chatRoomId, chain, me)?.let { DepositRepository.Standing.Open(it) }
                ?: when (Depositors.refusal(database, chatRoomId, chain, me)) {
                    Depositors.Refusal.NO_DESTINATION, Depositors.Refusal.NOT_A_MEMBER -> DepositRepository.Standing.NeedsDestination
                    else -> DepositRepository.Standing.CanOpen(canPropose)
                }
        }
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId takes this profile's deposits", e)
        DepositRepository.Standing.CannotSign
    }

    override suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor> = try {
        Depositors.statementsIn(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's depositors", e)
        emptyMap()
    }

    override suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean =
        runCatching { Depositors.refusal(database, chatRoomId, chain, member) == null }.getOrDefault(false)

    override suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        return Depositors.propose(database, localChatRoom, me, member, chain)
    }

    override suspend fun openOwnIfReady(chatRoomId: String, chain: Chain): FrostSigningSession? {
        val identity = activeIdentity.value?.takeIf { it.canSign } ?: return null
        val room = database.chatRoomDao().findChatRoomById(chatRoomId)?.takeIf { it.chatRoom.mlsGroupState != null } ?: return null
        if (Depositors.refusal(database, chatRoomId, chain, identity.nostrPublicKey) != null) return null
        if (!FrostSigningManager.canSign(database, chatRoomId)) return null
        return Depositors.propose(database, room, identity.nostrPublicKey, identity.nostrPublicKey, chain)
    }

    override suspend fun recordOnchain(chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>) {
        runCatching { DepositLedger.recordOnchain(database, chatRoomId, chain, deposits) }
            .onFailure { logger.e("Error recording $chatRoomId's deposits", it) }
    }

    override suspend fun tallies(chatRoomId: String, chain: Chain): DepositLedger.Tallies = try {
        DepositLedger.tallies(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's tallies", e)
        DepositLedger.Tallies(emptyMap(), 0, 0)
    }

    override suspend fun contacts(chatRoomId: String): Map<HexKey, Contacts.Contact> = try {
        Contacts.contactsIn(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's contacts", e)
        emptyMap()
    }

    override suspend fun deposits(chatRoomId: String, chain: Chain): List<DepositRow> = try {
        DepositLedger.depositsIn(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's deposits", e)
        emptyList()
    }

    override suspend fun attribute(localChatRoom: LocalChatRoom, chain: Chain, assignments: Map<String, Party?>): FrostSigningSession {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        return DepositLedger.proposeAttribution(database, localChatRoom, me, chain, assignments)
    }

    override suspend fun depositsOf(chatRoomId: String, chain: Chain, contactId: HexKey): List<DepositRow> = try {
        DepositLedger.depositsIn(database, chatRoomId, chain, resolveClaims = false).filter { it.member.equals(contactId, ignoreCase = true) }
    } catch (e: Throwable) {
        logger.e("Error reading a contact's deposits in $chatRoomId", e)
        emptyList()
    }

    override suspend fun contactDestination(chatRoomId: String, chain: Chain, contactId: HexKey): PayoutDestination? =
        runCatching { Contacts.destinationsIn(database, chatRoomId, chain)[contactId.lowercase()] }.getOrNull()

    override suspend fun contactsWaitingFor(chatRoomId: String): List<HexKey> = try {
        activeIdentity.value?.nostrPublicKey?.let { Contacts.announceIfNeeded(database, chatRoomId, it) }
        Contacts.waitingFor(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading who $chatRoomId waits for", e)
        emptyList()
    }

    /** [template] to the group from this device if it holds a share, or as a request for one that does. */
    private suspend fun proposeOrAsk(localChatRoom: LocalChatRoom, template: EventTemplate<*>): DepositRepository.ContactOutcome? = try {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        val chatRoomId = localChatRoom.chatRoom.id
        if (FrostSigningManager.canSign(database, chatRoomId)) {
            DepositRepository.ContactOutcome.Proposed(Contacts.propose(database, localChatRoom, me, template))
        } else {
            Contacts.ask(database, chatRoomId, me, template)
            DepositRepository.ContactOutcome.Asked
        }
    } catch (e: Throwable) {
        logger.e("Couldn't propose a contact in ${localChatRoom.chatRoom.id}", e)
        null
    }

    override suspend fun addContact(localChatRoom: LocalChatRoom, identifier: ContactIdentifier, name: String): DepositRepository.ContactOutcome? {
        val me = activeIdentity.value?.nostrPublicKey ?: return null
        val chatRoomId = localChatRoom.chatRoom.id
        if (!Contacts.everyoneReads(database, chatRoomId)) return null
        return proposeOrAsk(localChatRoom, ContactRecordEvent.template(chatRoomId, me, name, identifier))
    }

    override suspend fun amendContact(localChatRoom: LocalChatRoom, contactId: HexKey, change: ContactAmendmentEvent.Change): DepositRepository.ContactOutcome? =
        proposeOrAsk(localChatRoom, ContactAmendmentEvent.template(contactId, change))

    override suspend fun askToAttribute(chatRoomId: String, chain: Chain, depositKey: String, party: Party?): Boolean = try {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        Contacts.ask(database, chatRoomId, me, DepositLedger.attributionTemplate(database, chatRoomId, chain, mapOf(depositKey to party)))
        true
    } catch (e: Throwable) {
        logger.e("Couldn't ask for an attribution in $chatRoomId", e)
        false
    }

    override suspend fun pendingRequests(chatRoomId: String): List<Contacts.PendingRequest> = try {
        Contacts.pendingRequestsIn(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's contact requests", e)
        emptyList()
    }

    override suspend fun putToGroup(localChatRoom: LocalChatRoom, request: Contacts.PendingRequest): FrostSigningSession? = try {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        Contacts.propose(database, localChatRoom, me, request.template)
    } catch (e: Throwable) {
        logger.e("Couldn't put a request to ${localChatRoom.chatRoom.id}", e)
        null
    }

    companion object {
        private const val TAG = "DatabaseDepositRepository"
    }
}
