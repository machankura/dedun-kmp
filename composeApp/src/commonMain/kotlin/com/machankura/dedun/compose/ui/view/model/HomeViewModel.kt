package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.ui.view.state.HomeScreenUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

enum class HomeScreenType {
    Messages
}

class HomeViewModel(
    val activeUserPublicKey: HexKey,
    val initialHomeScreenUIState: HomeScreenUIState,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
    val pagerState: PagerState
): ViewModel() {

    val logger = Logger.withTag(TAG)


    var homeScreenUIState: HomeScreenUIState by mutableStateOf(initialHomeScreenUIState)
        private set

    fun initiate() {
        observeActiveUserProfileWithFollowing()
    }

    fun observeActiveUserProfileWithFollowing() {
        logger.d("observeActiveUserProfileWithFollowing")
        viewModelScope.launch(Dispatchers.IO) {
            nostrRepository.observeProfileWithFollowing(
                activeUserPublicKey
            ).collect { profileWithFollowing ->
                homeScreenUIState = if (profileWithFollowing != null) {
                    HomeScreenUIState.Loaded(
                        profileWithFollowing = profileWithFollowing
                    )
                } else {
                    HomeScreenUIState.Error
                }
            }
        }
    }

    companion object {
        const val TAG = "HomeViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            initialHomeScreenUIState: HomeScreenUIState,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
            pagerState: PagerState
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    initialHomeScreenUIState = initialHomeScreenUIState,
                    nostrRepository = nostrRepository,
                    pagerState = pagerState,
                )
            }
        }
    }
}