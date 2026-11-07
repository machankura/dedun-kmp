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
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.ui.view.state.IcebergCeremonyUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches a group's wallet key ceremony, opens one, joins it, and abandons it. [purpose] says which wallet's, when the
 * caller is asking for one by what it's for rather than by which it is.
 *
 * Nothing here drives the protocol: [com.machankura.dedun.compose.managers.IcebergRitualManager]
 * advances as messages arrive, and this only reads the session and asks its owner whether to join.
 */
class IcebergCeremonyViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    /** The ceremony to show, when the caller knew which; null shows the room's newest. */
    val sessionId: String?,
    val chain: Chain,
    initialUIState: IcebergCeremonyUIState,
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
    val purpose: String = IcebergKeySession.PURPOSE_LIGHTNING,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: IcebergCeremonyUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** The threshold a new ceremony would be opened at; starts at the smallest the group allows. */
    val threshold: MutableState<Int?> = mutableStateOf(null)

    private var watching: Job? = null

    /** The ceremonies this screen has nudged once, so a re-read does not nudge them again. */
    private val resumed = mutableSetOf<String>()

    fun initiate() {
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                uiState = IcebergCeremonyUIState.Error("Couldn't find the group")
                return@launch
            }
            val thresholds = lightningRepository.ceremonyThresholds(chatRoomId)
            if (threshold.value == null) threshold.value = thresholds.firstOrNull()
            val hasOnchainWallet = lightningRepository.hasOnchainWallet(chatRoomId, chain)
            val holderCount = lightningRepository.keyHolderCount(chatRoomId)

            val sessions = if (sessionId != null) {
                lightningRepository.observeCeremony(sessionId)
            } else {
                lightningRepository.observeCeremonies(chatRoomId).map { sessions -> sessions.firstOrNull { it.purpose == purpose } }
            }
            sessions.collect { session ->
                if (session != null) resume(localChatRoom, session)
                val messages = session?.let { lightningRepository.ceremonyMessages(it.id) }.orEmpty()
                uiState = IcebergCeremonyUIState.Loaded(
                    localChatRoom = localChatRoom,
                    session = session,
                    progress = session?.participants().orEmpty().associateWith { p -> messages.count { it.participantPublicKey == p } },
                    pending = session?.let { lightningRepository.pendingApproval(it) },
                    thresholds = thresholds,
                    holderCount = holderCount,
                    hasOnchainWallet = hasOnchainWallet,
                )
            }
        }
    }

    /**
     * A joined ceremony this device has not moved since an earlier build stopped it at a gate that no
     * longer exists: the others are waiting on it, so nothing will arrive to move it. Once per ceremony.
     */
    private suspend fun resume(localChatRoom: LocalChatRoom, session: IcebergKeySession) {
        val running = session.stage != IcebergRitualStage.FAILED && session.stage != IcebergRitualStage.COMPLETE
        if (!running || session.joinApprovedAt == null || !resumed.add(session.id)) return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return
        runCatching { lightningRepository.resumeCeremony(localChatRoom, session.id, privateKey) }
            .onFailure { logger.e("Could not resume ${session.id}", it) }
    }

    /** Whether this device can open a ceremony: none running, a deployable size, an onchain wallet, a key to sign with. */
    fun canPropose(): Boolean {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return false
        val running = loaded.session?.stage?.let { it != IcebergRitualStage.FAILED && it != IcebergRitualStage.COMPLETE } == true
        return !running && loaded.thresholds.isNotEmpty() && loaded.hasOnchainWallet && allowed() &&
            activeIdentityStateFlow.value?.nostrPrivateKey != null
    }

    private fun allowed(): Boolean =
        if (purpose == IcebergKeySession.PURPOSE_WAVELENGTH) GroupWavelengthRollout.allows(chain) else GroupLightningRollout.allows(chain)

    fun propose(onFailure: () -> Unit) {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return
        val t = threshold.value ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val proposed = runCatching { lightningRepository.proposeCeremony(loaded.localChatRoom, activeUserPublicKey, privateKey, t, chain, purpose) }
                .onFailure { logger.e("Could not open a $purpose wallet key ceremony in $chatRoomId", it) }
                .getOrNull()
            isActionPending.value = false
            if (proposed == null) withContext(Dispatchers.Main) { onFailure() }
        }
    }

    fun approve(onFailure: () -> Unit) {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return
        val session = loaded.session ?: return
        val step = loaded.pending ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val done = runCatching { lightningRepository.approveCeremony(loaded.localChatRoom, session.id, step, privateKey) }
                .onFailure { logger.e("Could not approve $step of ${session.id}", it) }
                .isSuccess
            isActionPending.value = false
            if (!done) withContext(Dispatchers.Main) { onFailure() }
            // The ceremony may have moved without a new row being written; read it again.
            initiate()
        }
    }

    /** Whether the shown ceremony is still running, which is when a participant may end it. */
    fun canAbandon(): Boolean {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return false
        return loaded.session?.stage?.let { it != IcebergRitualStage.FAILED && it != IcebergRitualStage.COMPLETE } == true
    }

    fun abandon(onFailure: () -> Unit) {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return
        val session = loaded.session ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val abandoned = runCatching { lightningRepository.abandonCeremony(loaded.localChatRoom, session.id) }
                .onFailure { logger.e("Could not abandon ${session.id}", it) }
                .getOrDefault(false)
            isActionPending.value = false
            if (!abandoned) withContext(Dispatchers.Main) { onFailure() }
        }
    }

    companion object {
        private const val TAG = "IcebergCeremonyViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            sessionId: String?,
            chain: Chain,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
            purpose: String = IcebergKeySession.PURPOSE_LIGHTNING,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                IcebergCeremonyViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    sessionId = sessionId,
                    chain = chain,
                    initialUIState = IcebergCeremonyUIState.Loading,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    chatRepository = chatRepository,
                    lightningRepository = lightningRepository,
                    purpose = purpose,
                )
            }
        }
    }
}
