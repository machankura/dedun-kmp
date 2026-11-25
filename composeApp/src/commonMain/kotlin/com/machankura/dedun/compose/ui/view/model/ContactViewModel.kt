package com.machankura.dedun.compose.ui.view.model

import kotlinx.coroutines.Job
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import fr.acinq.bitcoin.utils.Either
import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.ui.view.state.ContactUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One person outside the group that it has agreed to count: who they are, what they have put in,
 * and what any member can ask the group to change. `docs/non-member-deposits.md`, Phase 4.
 */
class ContactViewModel(
    val chatRoomId: String,
    val contactId: HexKey,
    val chain: Chain,
    initialUIState: ContactUIState,
    private val chatRepository: ChatRepository,
    private val depositRepository: DepositRepository,
    private val payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
    /** The group's onchain wallets, for the address to hand the contact; null where there are none (D10). */
    private val wallets: GroupOnchainWallets? = null,
) : ViewModel() {
    var uiState: ContactUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** The lowest unused address on the contact's branch, once Electrum has answered: what a payer is handed (D10). */
    var depositAddress: String? by mutableStateOf(null)
        private set

    private var following: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = ContactUIState.Error("Couldn't find the group") }
            val contact = depositRepository.contacts(chatRoomId)[contactId.lowercase()]
                ?: return@launch run { uiState = ContactUIState.Error("The group has no record of this contact") }
            val tallies = depositRepository.tallies(chatRoomId, chain)
            // Claimed, their tally is their member's; unclaimed, it is theirs.
            val tally = contact.claimedBy?.let { tallies.members[it] } ?: tallies.contacts[contact.id]
            uiState = ContactUIState.Loaded(
                localChatRoom = localChatRoom,
                contact = contact,
                tally = tally,
                deposits = depositRepository.depositsOf(chatRoomId, chain, contact.id),
                destination = depositRepository.contactDestination(chatRoomId, chain, contact.id),
                names = localChatRoom.localParticipants.associate { participant ->
                    participant.participant.participantPublicKey.lowercase() to
                        (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
                },
                members = localChatRoom.localParticipants.map { it.participant.participantPublicKey.lowercase() },
                depositsOpen = depositRepository.contactDepositsOpen(chatRoomId, chain, contact.id),
            )
            // Only an open, unclaimed contact is handed an address: once claimed, the member uses their own.
            if (contact.isOpen) followAddress()
        }
    }

    private fun followAddress() {
        following?.cancel()
        following = viewModelScope.launch(Dispatchers.IO) {
            val wallet = chatRepository.groupWallet(chatRoomId, chain) ?: return@launch
            val states = wallets?.walletFor(wallet.account, chatRoomId)?.state ?: return@launch
            states.collect { state -> depositAddress = state.depositAddressOf(contactId) }
        }
    }

    /** Proposes, or asks for, the group opening deposits for this contact on this network (D10). */
    fun openDeposits(onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        act({ depositRepository.openContactDeposits(it, chain, contactId) }, onProposed, onAsked, onFailure)

    /** Proposes, or asks a share-holder to propose, [change] to this contact. */
    fun amend(change: ContactAmendmentEvent.Change, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        act({ depositRepository.amendContact(it, contactId, change) }, onProposed, onAsked, onFailure)

    /** Proposes, or asks for, the group saying this contact was [member], or nobody in the group (D8). */
    fun claim(member: HexKey?, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        act({ depositRepository.claimContact(it, contactId, member) }, onProposed, onAsked, onFailure)

    /** Why a destination can't be proposed as typed: `docs/non-member-deposits.md`, D9. */
    enum class DestinationProblem { NEEDS_ONE, LIGHTNING_NOT_AN_ADDRESS, LIGHTNING_NOT_FOUND, ONCHAIN_NOT_AN_ADDRESS, ONCHAIN_OTHER_NETWORK, ONCHAIN_USED, ONCHAIN_UNKNOWN, ONCHAIN_THE_GROUPS }

    var destinationProblem: DestinationProblem? by mutableStateOf(null)
        private set

    /**
     * Proposes where the group pays this contact, checked first as a member's own device checks its
     * own: the Lightning address resolves (unless Tor is on), and the onchain address is on this network,
     * unused by Electrum, and not the group's own (group-payout-destination.md, D5-D7).
     */
    fun setDestination(lightningText: String, onchainText: String, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) {
        if (isActionPending.value) return
        destinationProblem = null
        val lightning = when (val parsed = LightningAddress.parse(lightningText)) {
            is LightningAddress.Parsed.Address -> parsed.address
            LightningAddress.Parsed.Empty -> null
            else -> return run { destinationProblem = DestinationProblem.LIGHTNING_NOT_AN_ADDRESS }
        }
        val onchain = when (val parsed = PayoutAddress.parse(chain, onchainText)) {
            is Either.Right -> parsed.value
            is Either.Left -> when (parsed.value) {
                PayoutAddress.Problem.Empty -> null
                PayoutAddress.Problem.NotAnAddress -> return run { destinationProblem = DestinationProblem.ONCHAIN_NOT_AN_ADDRESS }
                PayoutAddress.Problem.OtherNetwork -> return run { destinationProblem = DestinationProblem.ONCHAIN_OTHER_NETWORK }
            }
        }
        if (lightning == null && onchain == null) return run { destinationProblem = DestinationProblem.NEEDS_ONE }
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val problem = lightning?.let { address ->
                when (payoutDestinationRepository.resolve(address)) {
                    is LightningAddressResolver.Resolution.Resolved, LightningAddressResolver.Resolution.NotCheckedTor -> null
                    else -> DestinationProblem.LIGHTNING_NOT_FOUND
                }
            } ?: onchain?.let { parsed ->
                when (payoutDestinationRepository.usageOf(chatRoomId, chain, parsed.script)) {
                    UnusedAddressCheck.Usage.Unused -> null
                    UnusedAddressCheck.Usage.Used -> DestinationProblem.ONCHAIN_USED
                    UnusedAddressCheck.Usage.Unknown -> DestinationProblem.ONCHAIN_UNKNOWN
                    UnusedAddressCheck.Usage.TheGroups -> DestinationProblem.ONCHAIN_THE_GROUPS
                }
            }
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                destinationProblem = problem
            }
            if (problem == null) {
                withContext(Dispatchers.Main) {
                    act({ depositRepository.setContactDestination(it, chain, contactId, lightning, onchain?.address) }, onProposed, onAsked, onFailure)
                }
            }
        }
    }

    /** Proposes that the group pays this contact nowhere on this network. */
    fun removeDestination(onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        act({ depositRepository.setContactDestination(it, chain, contactId, null, null) }, onProposed, onAsked, onFailure)

    internal fun act(
        action: suspend (LocalChatRoom) -> DepositRepository.ContactOutcome?,
        onProposed: (sessionId: String) -> Unit,
        onAsked: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val loaded = uiState as? ContactUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = action(loaded.localChatRoom)
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                when (outcome) {
                    is DepositRepository.ContactOutcome.Proposed -> onProposed(outcome.session.id)
                    DepositRepository.ContactOutcome.Asked -> onAsked()
                    null -> onFailure()
                }
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            contactId: HexKey,
            chain: Chain,
            initialUIState: ContactUIState = ContactUIState.Loading,
            chatRepository: ChatRepository,
            depositRepository: DepositRepository,
            payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
            wallets: GroupOnchainWallets? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContactViewModel(chatRoomId, contactId, chain, initialUIState, chatRepository, depositRepository, payoutDestinationRepository, wallets) }
        }
    }
}
