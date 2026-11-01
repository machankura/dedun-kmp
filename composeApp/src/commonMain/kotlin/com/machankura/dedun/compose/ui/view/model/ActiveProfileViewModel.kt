package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class ActiveProfileViewModel(
    val activeUserPublicKey: HexKey,
    val nostrEventId: HexKey,
    initialActiveProfileUIState: com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
): ViewModel() {
    var activeProfileUIState: com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState by mutableStateOf(initialActiveProfileUIState)
        private set

    fun retryLoad() {
        activeProfileUIState = com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState.Loading
        loadNostrFeed()
    }

    fun loadNostrFeed() {
        viewModelScope.launch(Dispatchers.IO) {

            // Get contact list and sync feed...
            nostrRepository.observeLocalNostrEventById(
                nostrEventId
            ).firstOrNull().let { localNostrEvent ->
                activeProfileUIState = if (localNostrEvent != null && localNostrEvent.nostrEvent.kind == MetadataEvent.KIND) {
                    com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState.Loaded(
                        localNostrEvent = localNostrEvent
                    )
                } else {
                    com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState.Error
                }
            }
        }
    }

    companion object {
        const val TAG = "ActiveProfileViewModel"

        fun factory(
            nostrEventId: HexKey,
            activeUserPublicKey: HexKey,
            initialActiveProfileUIState: com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState = com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState.Loading,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ActiveProfileViewModel(
                    nostrEventId = nostrEventId,
                    initialActiveProfileUIState = initialActiveProfileUIState,
                    nostrRepository = nostrRepository,
                    activeUserPublicKey = activeUserPublicKey
                )
            }
        }
    }
}