package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.nostr.SignInSync

class UnqueuedProfileSynchronizationViewModel(
    val unsignedNostrEventId: Long,
    val profilePublicKey: String,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
): ViewModel() {
    companion object {
        private const val TAG = "UnqueuedProfileSynchronizationViewModel"

        fun factory(
            unsignedNostrEventId: Long,
            profilePublicKey: String,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UnqueuedProfileSynchronizationViewModel(
                    unsignedNostrEventId = unsignedNostrEventId,
                    profilePublicKey = profilePublicKey,
                    nostrRepository = nostrRepository
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Asks the bootstrap relays -- the indexers, and ours -- for everything this pubkey
     * has published about itself. The relay list that comes back is followed one hop by
     * the sync pump; see [SignInSync].
     */
    fun queueSynchronization() {
        logger.d { "queueSynchronization" }
        viewModelScope.launch(Dispatchers.IO) {

            delay(2_000)

            nostrRepository.queueSynchronizeNostrEvent(
                SignInSync.requests(
                    unsignedNostrEventId = unsignedNostrEventId,
                    publicKey = profilePublicKey,
                    relays = SignInSync.bootstrapRelays,
                    level = 0,
                )
            )
        }

    }
}