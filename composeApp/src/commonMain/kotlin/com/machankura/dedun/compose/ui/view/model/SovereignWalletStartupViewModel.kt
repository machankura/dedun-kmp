package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.StartBusinessResult
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch


sealed class StartupViewState {
    data object Init : StartupViewState()
    data class StartingBusiness(val walletId: WalletId) : StartupViewState()
    data class BusinessActive(val walletId: WalletId): StartupViewState()

    sealed class Error: StartupViewState() {
        abstract val walletId: WalletId
        data class Generic(override val walletId: WalletId, val cause: Throwable?): Error()
    }
}

class SovereignWalletStartupViewModel(
    val phoenixGlobal: PhoenixGlobal
): ViewModel() {
    private val log = Logger.withTag("SovereignWalletStartupViewModel")

    val state = mutableStateOf<StartupViewState>(StartupViewState.Init)

    fun startupNode(walletId: WalletId, words: List<String>, onStartupSuccess: (PhoenixBusiness) -> Unit) {
        if (state.value !is StartupViewState.Init) {
            return
        }

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            log.e("error when initialising startup-view: ", throwable = e)
            state.value = StartupViewState.Error.Generic(walletId = walletId, cause = e)
        }) {
            state.value = StartupViewState.StartingBusiness(walletId)

            val startResult: StartBusinessResult =
                com.machankura.dedun.compose.extensions.platformStartupLogic(
                    words
                )

            com.machankura.dedun.compose.extensions.schedulePlatformLogic(
                phoenixGlobal = phoenixGlobal,
            )
            when (startResult) {
                is StartBusinessResult.Success -> {
                    state.value = StartupViewState.BusinessActive(walletId)
                    launch(Dispatchers.Main) {
                        onStartupSuccess(startResult.business)
                    }
                }
                is StartBusinessResult.Failure.Generic -> state.value = StartupViewState.Error.Generic(walletId = walletId, cause = startResult.cause)
                is StartBusinessResult.Failure.LoadWalletError -> state.value = StartupViewState.Error.Generic(walletId = walletId, cause = null)
            }
        }
    }

    fun getShowIntroFlow(): Flow<Boolean> {
        return com.machankura.dedun.compose.extensions.getShowIntroFlow(phoenixGlobal)
    }

    companion object {
        fun factory(
            phoenixGlobal: PhoenixGlobal,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SovereignWalletStartupViewModel(
                    phoenixGlobal = phoenixGlobal,
                )
            }
        }
    }


}