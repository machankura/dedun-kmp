package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Gets a member an address the group's key makes. Nothing here believes the operator: the repository only hands back what checked out. */
class GroupWavelengthReceiveViewModel(
    private val activeUserPublicKey: HexKey,
    private val chatRoomId: String,
    private val chain: Chain,
    private val chatRepository: ChatRepository,
    private val repository: GroupWavelengthRepository,
) : ViewModel() {
    private val logger = Logger.withTag("GroupWavelengthReceiveViewModel")

    var uiState: GroupWavelengthReceiveUIState by mutableStateOf(GroupWavelengthReceiveUIState.Loading)
        private set

    private var room: LocalChatRoom? = null
    private var wallet: GroupWavelengthWalletManager.Wallet? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            val found = repository.wallets(chatRoomId, chain).lastOrNull()
            if (localChatRoom == null || found == null) {
                uiState = GroupWavelengthReceiveUIState.Error("Couldn't find the wallet")
                return@launch
            }
            room = localChatRoom
            wallet = found
            val number = found.statement.wallet
            if (!repository.isDepositor(chatRoomId, chain, activeUserPublicKey)) {
                uiState = GroupWavelengthReceiveUIState.NotADepositor
                return@launch
            }
            if (repository.operator(chatRoomId, chain, number)?.publicKey == null) {
                uiState = GroupWavelengthReceiveUIState.NobodyRunsIt
                return@launch
            }
            val ledger = GroupWavelengthLedger.of(repository.observeCoins(chatRoomId, chain, number).first())
            val cap = GroupWavelengthRollout.capFor(chain)?.sat
            val roomLeft = cap?.let { (it - ledger.settledSat - ledger.pendingSat).coerceAtLeast(0) }
            if (roomLeft == 0L) {
                uiState = GroupWavelengthReceiveUIState.AtTheCap
                return@launch
            }
            repository.observeDepositAddresses(chatRoomId, chain, number, activeUserPublicKey).collect { rows ->
                uiState = when {
                    rows.isEmpty() -> GroupWavelengthReceiveUIState.CanAsk
                    else -> rows.lastOrNull { it.address != null }?.let { GroupWavelengthReceiveUIState.Ready(it.address!!, repository.terms(chain), found.statement.policy.maxRoundFeeSat, roomLeft) }
                        ?: GroupWavelengthReceiveUIState.Waiting
                }
            }
        }
    }

    fun ask(onFailure: () -> Unit) {
        val localChatRoom = room ?: return
        val found = wallet ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.requestDepositAddress(localChatRoom, activeUserPublicKey, found) }
                .onFailure {
                    logger.e("Couldn't ask for a deposit address in $chatRoomId", it)
                    onFailure()
                }
        }
    }

    companion object {
        fun factory(activeUserPublicKey: HexKey, chatRoomId: String, chain: Chain, chatRepository: ChatRepository, repository: GroupWavelengthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupWavelengthReceiveViewModel(activeUserPublicKey, chatRoomId, chain, chatRepository, repository) }
        }
    }
}
