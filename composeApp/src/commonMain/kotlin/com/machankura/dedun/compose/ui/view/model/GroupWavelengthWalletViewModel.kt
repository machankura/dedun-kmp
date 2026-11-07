package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.GroupWavelengthReports
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The group's Wavelength wallet, read for what the room has agreed and what this device has itself signed. Every action is a
 * proposal the group signs: nothing here moves money.
 */
class GroupWavelengthWalletViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupWavelengthWalletUIState,
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val chatRepository: ChatRepository,
    private val repository: GroupWavelengthRepository,
    private val canRunHere: Boolean,
    private val daemonSource: GroupWavelengthDaemonSource = GroupWavelengthDaemonSource.None,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupWavelengthWalletUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                uiState = GroupWavelengthWalletUIState.Error("Couldn't find the group")
                return@launch
            }
            uiState = read(localChatRoom)
            (uiState as? GroupWavelengthWalletUIState.Opened)?.let { watch(it) }
        }
    }

    private suspend fun read(localChatRoom: LocalChatRoom): GroupWavelengthWalletUIState {
        if (!repository.hasOnchainWallet(chatRoomId, chain)) return GroupWavelengthWalletUIState.NeedsOnchainWallet(localChatRoom)
        val wallets = repository.wallets(chatRoomId, chain)
        wallets.lastOrNull()?.let { wallet ->
            val operator = repository.operator(chatRoomId, chain, wallet.statement.wallet)
            return GroupWavelengthWalletUIState.Opened(
                localChatRoom = localChatRoom,
                wallet = wallet,
                operator = operator,
                isParticipant = activeUserPublicKey.lowercase() in wallet.statement.participants,
                isOperator = operator?.publicKey == activeUserPublicKey.lowercase(),
                canRunHere = canRunHere,
                userPublicKey = activeUserPublicKey,
                departed = repository.departed(wallet),
                canOpenNew = repository.unusedKey(chatRoomId) != null && blocked() == null,
                earlier = wallets.dropLast(1).map { earlier ->
                    val number = earlier.statement.wallet
                    GroupWavelengthWalletUIState.Earlier(
                        wallet = earlier,
                        operator = repository.operator(chatRoomId, chain, number),
                        settledSat = GroupWavelengthLedger.of(repository.observeCoins(chatRoomId, chain, number).first()).settledSat,
                        leaveAsked = repository.leaveAsked(chatRoomId, chain, number),
                    )
                },
            )
        }
        when (val why = repository.refusal(chatRoomId, chain)) {
            null -> Unit
            GroupWavelengthWalletManager.Refusal.WAITING_FOR_SEED -> return GroupWavelengthWalletUIState.WaitingForSeed(localChatRoom)
            else -> return GroupWavelengthWalletUIState.Refused(localChatRoom, why)
        }
        val key = repository.unusedKey(chatRoomId)
        if (key != null) {
            return GroupWavelengthWalletUIState.NotOpened(
                localChatRoom = localChatRoom,
                key = key,
                openingSession = repository.openingSession(chatRoomId, key.id),
                blocked = blocked(),
            )
        }
        val running = repository.observeCeremonies(chatRoomId).first().firstOrNull { it.stage != IcebergRitualStage.FAILED }
        return GroupWavelengthWalletUIState.NeedsKey(localChatRoom, running, canMakeKey = repository.ceremonyThresholds(chatRoomId).isNotEmpty())
    }

    /** What stops this device proposing: a statement under terms the operator has left would be refused by every co-signer, every round. */
    private suspend fun blocked(): GroupWavelengthWalletUIState.Blocked? {
        if (activeIdentityStateFlow.value?.nostrPrivateKey == null) return GroupWavelengthWalletUIState.Blocked.NO_KEY
        val terms = repository.terms(chain) ?: return GroupWavelengthWalletUIState.Blocked.NO_OPERATOR_KNOWN
        val live = repository.liveTerms(chain)
        return GroupWavelengthWalletUIState.Blocked.TERMS_CHANGED.takeIf { live != null && !terms.bindsTheSameAs(live) }
    }

    private fun watch(opened: GroupWavelengthWalletUIState.Opened) {
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            val number = opened.wallet.statement.wallet
            launch {
                combine(repository.observeCoins(chatRoomId, chain, number), repository.observeSnapshot(chatRoomId, chain, number)) { coins, snapshot -> coins to snapshot }
                    .collect { (coins, snapshot) ->
                        (uiState as? GroupWavelengthWalletUIState.Opened)?.let {
                            uiState = it.copy(
                                coins = coins,
                                ledger = GroupWavelengthLedger.of(coins),
                                snapshot = snapshot,
                                comparison = snapshot?.let { said -> GroupWavelengthReports.compare(said, coins) },
                            )
                            refreshFacts()
                        }
                    }
            }
            // A block's about ten minutes, and who has answered changes as they do. Nothing here asks the daemon or the group for anything.
            launch {
                while (isActive) {
                    refreshFacts()
                    delay(FACTS_EVERY)
                }
            }
            if (opened.isOperator) launch {
                daemonSource.observe(chatRoomId, chain, opened.wallet.statement.wallet).collect { status ->
                    (uiState as? GroupWavelengthWalletUIState.Opened)?.let { uiState = it.copy(daemon = status) }
                }
            }
        }
    }

    private suspend fun refreshFacts() {
        val state = uiState as? GroupWavelengthWalletUIState.Opened ?: return
        val number = state.wallet.statement.wallet
        val tip = repository.tipHeight(chain)
        val free = repository.terms(chain)?.freeRefreshWindowBlocks ?: 0
        val term = state.operator?.term ?: 0
        val monthAgo = Clock.System.now().epochSeconds - FEE_WINDOW_SECONDS
        val seen = repository.seen(chatRoomId)
        val alarms = repository.alarms(chatRoomId)
        val fees = repository.feesSince(chatRoomId, chain, number, term, monthAgo)
        val labels = repository.labelsLeft(chatRoomId, chain, number, activeUserPublicKey)
        val payouts = repository.payouts(chatRoomId, chain, number)
        val leaveAsked = repository.leaveAsked(chatRoomId, chain, number)
        (uiState as? GroupWavelengthWalletUIState.Opened)?.let {
            uiState = it.copy(
                expiry = if (tip != null) WalletExpiry.ofHeight(it.ledger.earliestExpiryHeight, tip, free) else it.expiry,
                seen = seen,
                alarms = alarms,
                feesSat = fees,
                labelsLeft = labels,
                payouts = payouts,
                leaveAsked = leaveAsked,
            )
        }
    }

    /** Proposes the wallet's statement; the wallet exists once a quorum signs, on the session it returns. */
    fun openWallet(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        // The first wallet, or the one that leaves a departed member out, beside the current one under a new key.
        val localChatRoom = (uiState as? GroupWavelengthWalletUIState.NotOpened)?.takeIf { it.blocked == null }?.localChatRoom
            ?: (uiState as? GroupWavelengthWalletUIState.Opened)?.takeIf { it.canOpenNew }?.localChatRoom
            ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        act(onSession, onFailure) { repository.proposeWallet(localChatRoom, activeUserPublicKey, privateKey, chain) }
    }

    /** Nominates this device to run [wallet]: the current wallet's when null. */
    fun nominateSelf(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit, wallet: Long? = null) {
        val state = uiState as? GroupWavelengthWalletUIState.Opened ?: return
        act(onSession, onFailure) { repository.nominateSelf(state.localChatRoom, activeUserPublicKey, chain, wallet ?: state.wallet.statement.wallet) }
    }

    /** Asks the group to leave for its onchain wallet: [earlier], or the current wallet when null. */
    fun proposeLeave(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit, earlier: GroupWavelengthWalletManager.Wallet? = null) {
        val state = uiState as? GroupWavelengthWalletUIState.Opened ?: return
        act(onSession, onFailure) { repository.proposeExit(state.localChatRoom, activeUserPublicKey, earlier ?: state.wallet) }
    }

    fun stepDown(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupWavelengthWalletUIState.Opened ?: return
        act(onSession, onFailure) { repository.stepDown(state.localChatRoom, activeUserPublicKey, chain, state.wallet.statement.wallet) }
    }

    private fun act(onSession: (String) -> Unit, onFailure: () -> Unit, propose: suspend () -> FrostSigningSession) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { propose() }.onFailure { logger.e("Wavelength wallet proposal in $chatRoomId failed", it) }.getOrNull()
            isActionPending.value = false
            withContext(Dispatchers.Main) { if (session != null) onSession(session.id) else onFailure() }
        }
    }

    companion object {
        private const val TAG = "GroupWavelengthWalletViewModel"
        private val FACTS_EVERY = 1.minutes

        /** The window the statement's fee budget runs over, in [com.machankura.dedun.compose.managers.GroupArkCoSigner]. */
        private const val FEE_WINDOW_SECONDS = 30L * 24 * 60 * 60

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            repository: GroupWavelengthRepository,
            canRunHere: Boolean,
            daemonSource: GroupWavelengthDaemonSource,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupWavelengthWalletViewModel(activeUserPublicKey, chatRoomId, chain, GroupWavelengthWalletUIState.Loading, activeIdentityStateFlow, chatRepository, repository, canRunHere, daemonSource)
            }
        }
    }
}
