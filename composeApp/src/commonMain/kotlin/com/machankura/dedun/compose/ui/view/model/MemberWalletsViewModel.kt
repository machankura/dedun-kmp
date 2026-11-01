package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * A member's guarded wallets, as their profile lists them: each one's state,
 * and -- for the ones a group has certified -- its balance. [wallets] is null
 * only where the profile is composed without an identity: previews and tests.
 */
class MemberWalletsViewModel(
    private val ownerPublicKey: HexKey,
    private val memberWalletRepository: MemberWalletRepository,
    private val wallets: GroupOnchainWallets?,
) : ViewModel() {
    /** One wallet and, once the chain has answered, its coins. */
    data class Row(val wallet: MemberWalletManager.MemberWalletRow, val walletState: GroupWalletState?)

    /** Null until the first read. */
    var rows: List<Row>? by mutableStateOf(null)
        private set

    private var watching: Job? = null

    /** Reads the list again: called each time the profile is shown, since a certificate can arrive while it is not. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = memberWalletRepository.walletsOf(ownerPublicKey)
            rows = found.map { Row(it, walletState = null) }

            watching?.cancel()
            val watched = found.filter { it.isCertified }
            if (wallets == null || watched.isEmpty()) return@launch
            watching = viewModelScope.launch(Dispatchers.IO) {
                val states = found.map { wallet ->
                    if (wallet.isCertified) wallets.walletFor(wallet.account).state else flowOf(null)
                }
                combine(states) { it.toList() }.collect { current ->
                    rows = found.zip(current) { wallet, state -> Row(wallet, state) }
                }
            }
        }
    }

    companion object {
        fun factory(
            ownerPublicKey: HexKey,
            memberWalletRepository: MemberWalletRepository,
            wallets: GroupOnchainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemberWalletsViewModel(ownerPublicKey, memberWalletRepository, wallets) }
        }
    }
}
