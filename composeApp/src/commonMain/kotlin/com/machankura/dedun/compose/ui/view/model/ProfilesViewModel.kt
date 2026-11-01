package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.acinq.phoenix.data.ListWalletState
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.StoredIdentity
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.repository.observeProfilesOf
import com.machankura.dedun.compose.ui.view.state.ProfilesUIState

/**
 * What the switcher shows: the device's profiles, which one is open, and each one's
 * nostr profile where the device has it.
 *
 * Everything comes from flows the app already holds -- the listing, the active
 * identity, the wallet metadata -- joined to the profile rows for the listed keys. The
 * three states follow `ListWalletState`: `Init` is loading, `Error` is an unreadable
 * key file, and `Success` is the list. Nothing here writes; the switch itself is the
 * screen's callback into `SovereignWalletViewModel.switchToIdentity`.
 */
class ProfilesViewModel(
    listWalletState: StateFlow<ListWalletState>,
    availableIdentities: StateFlow<Map<WalletId, StoredIdentity>>,
    activeIdentity: StateFlow<Identity?>,
    walletsMetadata: Flow<Map<WalletId, UserWalletMetadata>>,
    nostrRepository: NostrRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ProfilesUIState> = combine(
        listWalletState,
        availableIdentities,
        activeIdentity,
        walletsMetadata,
    ) { listState, identities, active, metadata ->
        Inputs(listState, identities, active?.id, metadata)
    }.flatMapLatest { inputs ->
        when (inputs.listState) {
            is ListWalletState.Init -> flowOf(ProfilesUIState.Loading)
            is ListWalletState.Error -> flowOf(ProfilesUIState.Error)
            is ListWalletState.Success -> nostrRepository
                .observeProfilesOf(inputs.identities.values.map { it.nostrPublicKey })
                .map { profiles ->
                    ProfilesUIState.Loaded(
                        identities = inputs.identities,
                        activeId = inputs.activeId,
                        walletsMetadata = inputs.metadata,
                        profiles = profiles,
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUIState.Loading)

    private data class Inputs(
        val listState: ListWalletState,
        val identities: Map<WalletId, StoredIdentity>,
        val activeId: WalletId?,
        val metadata: Map<WalletId, UserWalletMetadata>,
    )

    companion object {
        fun factory(
            listWalletState: StateFlow<ListWalletState>,
            availableIdentities: StateFlow<Map<WalletId, StoredIdentity>>,
            activeIdentity: StateFlow<Identity?>,
            walletsMetadata: Flow<Map<WalletId, UserWalletMetadata>>,
            nostrRepository: NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProfilesViewModel(
                    listWalletState = listWalletState,
                    availableIdentities = availableIdentities,
                    activeIdentity = activeIdentity,
                    walletsMetadata = walletsMetadata,
                    nostrRepository = nostrRepository,
                )
            }
        }
    }
}
