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
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRequests
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
            val operator = repository.operator(chatRoomId, chain, number)?.publicKey
            if (operator == null) {
                uiState = GroupWavelengthReceiveUIState.NobodyRunsIt
                return@launch
            }
            val operatorName = localChatRoom.localParticipants
                .firstOrNull { it.participant.participantPublicKey.equals(operator, ignoreCase = true) }
                ?.profile?.humanReadableNameOrPubkey() ?: operator.take(8)
            val ledger = GroupWavelengthLedger.of(repository.observeCoins(chatRoomId, chain, number).first())
            val cap = GroupWavelengthRollout.capFor(chain)?.sat
            val roomLeft = cap?.let { (it - ledger.settledSat - ledger.pendingSat).coerceAtLeast(0) }
            if (roomLeft == 0L) {
                uiState = GroupWavelengthReceiveUIState.AtTheCap
                return@launch
            }
            val needed = 2 * found.statement.threshold - 1
            val since = Clock.System.now() - GroupWavelengthWalletUIState.Opened.SEEN_WINDOW
            val seen = repository.seen(chatRoomId)
            val enoughSeen = found.statement.participants.count { p -> seen[p]?.let { it >= since } == true } >= needed
            val terms = repository.terms(chain)
            // The coins are followed rather than read once: a deposit that lands while the screen
            // is open takes room under the cap, and "ask for another" must not offer an amount
            // that no longer fits.
            combine(
                repository.observeDepositAddresses(chatRoomId, chain, number, activeUserPublicKey),
                repository.observeCoins(chatRoomId, chain, number),
                repository.observeBoarded(chatRoomId, chain, number),
                repository.observeClaims(chatRoomId, chain, number),
                repository.observeSnapshot(chatRoomId, chain, number),
            ) { rows, coins, boarded, claims, snapshot ->
                val addresses = rows.filter { it.invoiceAmountSat == null }
                val invoices = rows.filter { it.invoiceAmountSat != null }.sortedBy { it.requestedAt }
                val newest = invoices.lastOrNull()
                val shownAddress = addresses.lastOrNull { it.address != null }
                val live = GroupWavelengthLedger.of(coins)
                GroupWavelengthReceiveUIState.Loaded(
                    address = shownAddress?.address,
                    addressAsked = addresses.isNotEmpty(),
                    invoice = newest?.invoice,
                    invoiceAmountSat = newest?.invoiceAmountSat,
                    invoiceAsked = newest != null && newest.invoice == null,
                    terms = terms,
                    maxRoundFeeSat = found.statement.policy.maxRoundFeeSat,
                    roomLeftSat = cap?.let { (it - live.settledSat - live.pendingSat).coerceAtLeast(0) },
                    enoughSeen = enoughSeen,
                    addressPaid = shownAddress?.let { GroupWavelengthRequests.paidOnAddress(it, boarded) },
                    invoicePaid = newest?.let { GroupWavelengthRequests.paidOnInvoice(it, claims, snapshot) },
                    operatorName = operatorName,
                )
            }.collect { uiState = it }
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

    /** Asks for an invoice for [amountSat], or says no if it's more or less than a coin here can hold. */
    fun askInvoice(amountSat: Long, onFailure: () -> Unit) {
        val state = uiState as? GroupWavelengthReceiveUIState.Loaded ?: return
        val localChatRoom = room ?: return
        val found = wallet ?: return
        if (amountSat < (state.terms?.minVtxoSat ?: 1) || amountSat > (state.maxInvoiceSat ?: Long.MAX_VALUE)) return onFailure()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.requestDepositInvoice(localChatRoom, activeUserPublicKey, found, amountSat) }
                .onFailure {
                    logger.e("Couldn't ask for an invoice in $chatRoomId", it)
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
