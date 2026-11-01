package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.ui.view.state.GroupPayoutsUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.PayoutBuilder
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every member's destination and balance, and paying the ones chosen everything they own:
 * `docs/payouts-only.md`, Phase 4.
 */
class GroupPayoutsViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupPayoutsUIState,
    private val chatRepository: ChatRepository,
    private val frostSigningRepository: FrostSigningRepository,
    private val payoutRepository: PayoutRepository,
    private val payoutDestinationRepository: PayoutDestinationRepository,
    private val wallets: GroupOnchainWallets?,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupPayoutsUIState by mutableStateOf(initialUIState)
        private set

    /** The members chosen to be paid out onchain. */
    var selected: Set<HexKey> by mutableStateOf(emptySet())
        private set

    val feerateField = TextFieldState()

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Whether each payee's address has been paid before, as the proposer checks it (D7): absent while checking. */
    val usage = mutableStateMapOf<HexKey, UnusedAddressCheck.Usage>()

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupPayoutsUIState.Error("Couldn't find the group") }
            val wallet = chatRepository.groupWallet(chatRoomId, chain)
            val names = localChatRoom.localParticipants.associate { participant ->
                participant.participant.participantPublicKey.lowercase() to
                    (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
            }
            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            val loaded = GroupPayoutsUIState.Loaded(
                localChatRoom = localChatRoom,
                balances = payoutRepository.balances(chatRoomId, chain, null),
                walletState = null,
                reserved = frostSigningRepository.reservedOutPoints(chatRoomId),
                context = payoutRepository.payoutContext(chatRoomId, chain),
                names = names,
                canSign = canSign && wallet != null,
                self = activeUserPublicKey.lowercase(),
            )
            uiState = loaded

            wallets?.suggestedFeeratePerVbyte(chain)?.let { suggested ->
                withContext(Dispatchers.Main) {
                    if (feerateField.text.isEmpty()) feerateField.setTextAndPlaceCursorAtEnd(suggested.toString())
                }
            }
            val states = wallet?.let { wallets?.walletFor(it.account, chatRoomId)?.state } ?: return@launch
            follow(loaded, states)
        }
    }

    /**
     * Keeps [loaded] current: the coins from [chainState], whose they are, and which of them the
     * room's open spends hold, re-read every time a spend is proposed, fails or settles.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun follow(loaded: GroupPayoutsUIState.Loaded, chainState: Flow<GroupWalletState?>) {
        val reservations = frostSigningRepository.observeGroupTransactions(chatRoomId).mapLatest { frostSigningRepository.reservedOutPoints(chatRoomId) }
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            combine(chainState, reservations) { state, reserved ->
                loaded.copy(
                    balances = payoutRepository.balances(chatRoomId, chain, state),
                    walletState = state,
                    reserved = reserved,
                    context = payoutRepository.payoutContext(chatRoomId, chain),
                )
            }.collect { uiState = it }
        }
    }

    fun toggle(member: HexKey) {
        selected = if (member in selected) selected - member else selected + member
    }

    /** The payout the screen describes now, or null while nobody is chosen, the coins are not known, or there is no fee rate. */
    fun preview(): PayoutBuilder.Result? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        val walletState = state.walletState ?: return null
        if (selected.isEmpty()) return null
        val feerate = feerateField.text.toString().trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        return PayoutBuilder.build(walletState, selected, state.context, feerate, state.reserved)
    }

    /** Checks the payout address of each of [members] that has not been checked yet (D7). */
    fun checkUsage(members: Collection<HexKey>) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        members.filter { it !in usage }.forEach { member ->
            val script = state.context.scripts[member] ?: return@forEach
            viewModelScope.launch(Dispatchers.IO) {
                val answer = payoutDestinationRepository.usageOf(chatRoomId, chain, script)
                withContext(Dispatchers.Main) { usage[member] = answer }
            }
        }
    }

    /** Why the payout built can't be proposed yet, by the usage of its payees' addresses, or null when it can (D7). */
    fun usageProblem(built: PayoutBuilder.Result.Built): UsageProblem? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        built.payout.members.forEach { payee ->
            val former = state.balances.members[payee.member]?.isFormerMember == true
            when (usage[payee.member]) {
                null -> return UsageProblem.Checking(payee.member)
                UnusedAddressCheck.Usage.Unknown -> return UsageProblem.CouldNotCheck(payee.member)
                UnusedAddressCheck.Usage.Used -> if (!former) return UsageProblem.PaidBefore(payee.member)
                else -> Unit
            }
        }
        return null
    }

    /** A former member whose address has been paid before: paid anyway, after a warning, since they can't give another. */
    fun reusedByFormer(built: PayoutBuilder.Result.Built): List<HexKey> {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return emptyList()
        return built.payout.members.map { it.member }.filter { usage[it] == UnusedAddressCheck.Usage.Used && state.balances.members[it]?.isFormerMember == true }
    }

    sealed interface UsageProblem {
        data class Checking(val member: HexKey) : UsageProblem
        data class CouldNotCheck(val member: HexKey) : UsageProblem
        data class PaidBefore(val member: HexKey) : UsageProblem
    }

    fun propose(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        val built = preview() as? PayoutBuilder.Result.Built ?: return
        if (usageProblem(built) != null || isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSpend(localChatRoom = state.localChatRoom, userPublicKey = activeUserPublicKey, psbt = built.psbt, chain = chain)
            }.onFailure { logger.e("Failed to propose a payout from $chatRoomId", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) {
                    selected = emptySet()
                    onSuccess(session.id)
                } else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "GroupPayoutsViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            initialUIState: GroupPayoutsUIState = GroupPayoutsUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            payoutRepository: PayoutRepository,
            payoutDestinationRepository: PayoutDestinationRepository,
            wallets: GroupOnchainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupPayoutsViewModel(
                    activeUserPublicKey, chatRoomId, chain, initialUIState,
                    chatRepository, frostSigningRepository, payoutRepository, payoutDestinationRepository, wallets,
                )
            }
        }
    }
}
