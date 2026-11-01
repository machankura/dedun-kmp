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
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.repository.GroupNodeStatusSource
import com.machankura.dedun.compose.ui.view.state.GroupLightningWalletUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The group's Lightning wallet, read for what the room has agreed: whether it can have one yet, the
 * statement that opened it, and who runs its node. Every action is a proposal the group signs.
 */
class GroupLightningWalletViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupLightningWalletUIState,
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
    private val nodeStatusSource: GroupNodeStatusSource = GroupNodeStatusSource.None,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupLightningWalletUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                uiState = GroupLightningWalletUIState.Error("Couldn't find the group")
                return@launch
            }
            uiState = when {
                !lightningRepository.hasOnchainWallet(chatRoomId, chain) -> GroupLightningWalletUIState.NeedsOnchainWallet(localChatRoom)
                else -> {
                    val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
                    val key = lightningRepository.completedCeremony(chatRoomId)
                    when {
                        wallet != null -> {
                            val operator = lightningRepository.operator(chatRoomId, chain, wallet.statement.wallet)
                            GroupLightningWalletUIState.Opened(
                                localChatRoom = localChatRoom,
                                wallet = wallet,
                                operator = operator,
                                isParticipant = activeUserPublicKey.lowercase() in wallet.statement.participants && wallet.ceremony?.share != null,
                                isOperator = operator?.publicKey == activeUserPublicKey,
                                departed = lightningRepository.departed(wallet),
                                canOpenNew = lightningRepository.hasUnusedKey(chatRoomId, chain) && canPropose(),
                                earlier = lightningRepository.earlierWallets(chatRoomId, chain).map { earlier ->
                                    val report = lightningRepository.observeReport(chatRoomId, chain, earlier.statement.wallet).first()
                                    GroupLightningWalletUIState.Earlier(
                                        wallet = earlier,
                                        operator = lightningRepository.operator(chatRoomId, chain, earlier.statement.wallet),
                                        report = report,
                                        unswept = lightningRepository.unswept(chatRoomId, report),
                                    )
                                },
                            )
                        }
                        key != null -> GroupLightningWalletUIState.NotOpened(
                            localChatRoom = localChatRoom,
                            key = key,
                            canPropose = canPropose(),
                        )
                        else -> {
                            val running = lightningRepository.observeCeremonies(chatRoomId).first().firstOrNull { it.stage != IcebergRitualStage.FAILED }
                            GroupLightningWalletUIState.NeedsKey(localChatRoom, running, canMakeKey = lightningRepository.ceremonyThresholds(chatRoomId).isNotEmpty())
                        }
                    }
                }
            }
            if (uiState is GroupLightningWalletUIState.Opened) {
                watchReports()
                watchNodes()
            }
        }
    }

    /** Whether this device can propose a wallet statement: an LSP to name, and a key to send the seed with. */
    private fun canPropose(): Boolean = lightningRepository.lspNodeId(chain) != null && activeIdentityStateFlow.value?.nostrPrivateKey != null

    private var watching: Job? = null

    /** Keeps an opened wallet's snapshot and payments current as the operator's reports arrive. */
    private fun watchReports() {
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            val wallet = (uiState as? GroupLightningWalletUIState.Opened)?.wallet?.statement?.wallet ?: return@launch
            combine(lightningRepository.observeReport(chatRoomId, chain, wallet), lightningRepository.observePayments(chatRoomId, chain, wallet)) { report, payments -> report to payments }
                .collect { (report, payments) ->
                    val unswept = lightningRepository.unswept(chatRoomId, report)
                    (uiState as? GroupLightningWalletUIState.Opened)?.let { uiState = it.copy(report = report, payments = payments, unswept = unswept) }
                }
        }
    }

    private var watchingNodes: Job? = null

    /**
     * Keeps this device's node status current, for the wallets it is the operator of: the current one and
     * any earlier one it still runs (`docs/lsp-connection-status.md`, Phase 3). Never asked for a wallet
     * somebody else runs.
     */
    private fun watchNodes() {
        watchingNodes?.cancel()
        val opened = uiState as? GroupLightningWalletUIState.Opened ?: return
        watchingNodes = viewModelScope.launch(Dispatchers.IO) {
            if (opened.isOperator) launch {
                nodeStatusSource.observe(chatRoomId, opened.wallet.statement.wallet).collect { status ->
                    (uiState as? GroupLightningWalletUIState.Opened)?.let { uiState = it.copy(nodeStatus = status) }
                }
            }
            opened.earlier.filter { it.operator?.publicKey == activeUserPublicKey }.forEach { earlier ->
                val number = earlier.wallet.statement.wallet
                launch {
                    nodeStatusSource.observe(chatRoomId, number).collect { status ->
                        (uiState as? GroupLightningWalletUIState.Opened)?.let { state ->
                            uiState = state.copy(earlier = state.earlier.map { if (it.wallet.statement.wallet == number) it.copy(nodeStatus = status) else it })
                        }
                    }
                }
            }
        }
    }

    /** Dials the LSP now, from the operator's device, when its node's last attempt did not hold. */
    fun reconnectNow(wallet: Long? = null) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        nodeStatusSource.reconnectNow(chatRoomId, wallet ?: state.wallet.statement.wallet)
    }

    /** Proposes the wallet's statement; the wallet exists once a quorum signs, on the session it returns. */
    fun openWallet(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        // The first wallet, or the one a re-key opens beside the current one under a new key.
        val localChatRoom = (uiState as? GroupLightningWalletUIState.NotOpened)?.localChatRoom
            ?: (uiState as? GroupLightningWalletUIState.Opened)?.takeIf { it.canOpenNew }?.localChatRoom
            ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        act(onSession, onFailure) { lightningRepository.proposeWallet(localChatRoom, activeUserPublicKey, privateKey, chain) }
    }

    /** Nominates this device to run [wallet]'s node: the current wallet's when null. */
    fun nominateSelf(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit, wallet: Long? = null) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        act(onSession, onFailure) { lightningRepository.nominateSelf(state.localChatRoom, activeUserPublicKey, chain, wallet ?: state.wallet.statement.wallet) }
    }

    fun stepDown(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        act(onSession, onFailure) { lightningRepository.stepDown(state.localChatRoom, activeUserPublicKey, chain, state.wallet.statement.wallet) }
    }

    /** Proposes closing every channel an earlier wallet still has, so its money reaches the onchain wallet. */
    fun closeEarlier(earlier: GroupLightningWalletUIState.Earlier, onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        if (earlier.openChannels.isEmpty()) return
        act(onSession, onFailure) { lightningRepository.proposeCloseAll(state.localChatRoom, activeUserPublicKey, earlier.wallet, earlier.openChannels) }
    }

    /** Proposes sweeping what an earlier wallet's closed channels left the group. */
    fun sweepEarlier(earlier: GroupLightningWalletUIState.Earlier, onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        if (earlier.unswept.isEmpty()) return
        act(onSession, onFailure) { lightningRepository.proposeSweep(state.localChatRoom, activeUserPublicKey, earlier.wallet, earlier.unswept) }
    }

    /** Proposes closing [channelId] to the onchain wallet, or by force when the LSP is gone. */
    fun close(channelId: String, force: Boolean, onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        act(onSession, onFailure) { lightningRepository.proposeClose(state.localChatRoom, activeUserPublicKey, state.wallet, channelId, force) }
    }

    /** Proposes sweeping everything closed channels left the group into the onchain wallet. */
    fun sweep(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        if (state.unswept.isEmpty()) return
        act(onSession, onFailure) { lightningRepository.proposeSweep(state.localChatRoom, activeUserPublicKey, state.wallet, state.unswept) }
    }

    private fun act(onSession: (String) -> Unit, onFailure: () -> Unit, propose: suspend () -> FrostSigningSession) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { propose() }.onFailure { logger.e("Lightning wallet proposal in $chatRoomId failed", it) }.getOrNull()
            isActionPending.value = false
            withContext(Dispatchers.Main) { if (session != null) onSession(session.id) else onFailure() }
        }
    }

    companion object {
        private const val TAG = "GroupLightningWalletViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
            nodeStatusSource: GroupNodeStatusSource,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupLightningWalletViewModel(activeUserPublicKey, chatRoomId, chain, GroupLightningWalletUIState.Loading, activeIdentityStateFlow, chatRepository, lightningRepository, nodeStatusSource)
            }
        }
    }
}
