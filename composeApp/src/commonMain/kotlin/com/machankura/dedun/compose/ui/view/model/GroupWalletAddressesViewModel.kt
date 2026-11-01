package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.view.state.GroupWalletAddressesUIState
import com.machankura.dedun.compose.wallet.GroupAddress
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The group wallet's addresses, watched for as long as the screen is open.
 *
 * [wallets] hands back the wallet the wallet screen is already watching, so the
 * list costs no subscriptions of its own and agrees with that screen about which
 * address is current.
 */
class GroupWalletAddressesViewModel(
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupWalletAddressesUIState,
    val chatRepository: ChatRepository,
    private val wallets: GroupOnchainWallets,
): ViewModel() {
    var uiState: GroupWalletAddressesUIState by mutableStateOf(initialUIState)
        private set

    /** The index of the address the sheet is showing as a QR code, or null when it is closed. */
    var shown: Long? by mutableStateOf(null)
        private set

    private var onShownPaid: (() -> Unit)? = null

    /**
     * Opens the sheet on the receive address at [index]. [onPaid] runs once if
     * that address is paid while the sheet is open, as the sheet closes: whoever
     * is scanning it then has already paid, and nobody else should.
     */
    fun show(index: Long, onPaid: () -> Unit) {
        shown = index
        onShownPaid = onPaid
    }

    fun dismiss() {
        shown = null
        onShownPaid = null
    }

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = chatRepository.groupWallet(chatRoomId, chain)
            if (wallet == null) {
                uiState = GroupWalletAddressesUIState.Error("This group has not opened a wallet")
                return@launch
            }

            uiState = GroupWalletAddressesUIState.Loaded(receive = null)
            wallets.walletFor(wallet.account).state.collect { state ->
                val receive = state.receiveAddresses
                uiState = GroupWalletAddressesUIState.Loaded(receive = receive, change = state.changeAddresses.orEmpty())

                val index = shown ?: return@collect
                val status = receive?.firstOrNull { it.derivation.index == index }?.status ?: return@collect
                if (status == GroupAddress.Status.USED) {
                    val paid = onShownPaid
                    withContext(Dispatchers.Main) {
                        dismiss()
                        paid?.invoke()
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            chain: Chain,
            initialUIState: GroupWalletAddressesUIState = GroupWalletAddressesUIState.Loading,
            chatRepository: ChatRepository,
            wallets: GroupOnchainWallets,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupWalletAddressesViewModel(
                    chatRoomId = chatRoomId,
                    chain = chain,
                    initialUIState = initialUIState,
                    chatRepository = chatRepository,
                    wallets = wallets,
                )
            }
        }
    }
}
