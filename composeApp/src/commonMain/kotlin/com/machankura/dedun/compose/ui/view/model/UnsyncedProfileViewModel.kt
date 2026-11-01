package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.nostr.SignInSync
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.UnsyncedProfileUIState
import com.machankura.dedun.compose.ui.view.state.form.CreateProfileFormState

/**
 * The screen that waits for a sign-in sync, and the one decision it makes: whether the
 * search is still running or has finished with nothing.
 *
 * `NavigationViewModel` keeps the route at `UnsyncedProfile` for as long as the account
 * has sync requests and no kind 0; it cannot tell the two apart, and until Phase 5 of
 * docs/nsec-sign-in.md nothing could. Now every request records when its relay has
 * finished answering, and [decide] reads that: no request still in flight, and still
 * no profile, is not-found. The two exits from not-found are to ask again and to set a
 * profile up for the key that is already here.
 */
class UnsyncedProfileViewModel(
    private val publicKey: HexKey,
    private val nostrRepository: NostrRepository,
    val formState: CreateProfileFormState = CreateProfileFormState(),
) : ViewModel() {

    companion object {
        private const val TAG = "UnsyncedProfileViewModel"

        fun factory(
            publicKey: HexKey,
            nostrRepository: NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UnsyncedProfileViewModel(
                    publicKey = publicKey,
                    nostrRepository = nostrRepository,
                )
            }
        }

        /**
         * What the account says. A kind 0 indexed or a Profile row means the navigation
         * machine is about to move on, so that is still "searching" from here; a request
         * still `pending` or `sent` is a relay that has not finished. Only when every
         * request has finished and nothing came is the answer not-found.
         */
        fun decide(account: LocalAccount?): UnsyncedProfileUIState {
            val unsignedNostrEvent = account?.unsignedNostrEvent ?: return UnsyncedProfileUIState.Searching
            if (account.profile != null || account.nostrEvent != null) return UnsyncedProfileUIState.Searching
            val requests = account.synchronizeNostrEventRequests
            if (requests.isEmpty()) return UnsyncedProfileUIState.Searching
            if (requests.any { it.status in SignInSync.IN_FLIGHT_STATUSES }) return UnsyncedProfileUIState.Searching
            return UnsyncedProfileUIState.NotFound(unsignedNostrEventId = unsignedNostrEvent.id)
        }
    }

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** True after a submit with no name; the field shows why. */
    val nameMissing: MutableState<Boolean> = mutableStateOf(false)

    val uiState: StateFlow<UnsyncedProfileUIState> = accountFlow()
        .map { decide(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnsyncedProfileUIState.Searching)

    private fun accountFlow(): Flow<LocalAccount?> = flow {
        nostrRepository.observeLocalAccount(publicKey).collect { emit(it) }
    }

    /** Asks the bootstrap relays again. The new requests are in flight, so the screen goes back to searching on its own. */
    fun tryAgain(unsignedNostrEventId: Long) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                nostrRepository.queueSynchronizeNostrEvent(
                    SignInSync.requests(
                        unsignedNostrEventId = unsignedNostrEventId,
                        publicKey = publicKey,
                        relays = SignInSync.bootstrapRelays,
                        level = 0,
                    )
                )
            } catch (e: Exception) {
                logger.e("could not queue the sign-in sync again", e)
            } finally {
                isActionPending.value = false
            }
        }
    }

    /**
     * Gives the key a profile. The rows change under `observeLocalAccount`, the
     * navigation machine sees an unsigned kind 0 and routes to the create flow's own
     * screens, and the notary -- which already holds this key -- signs it.
     */
    fun setUpProfile(unsignedNostrEventId: Long) {
        if (isActionPending.value) return
        val name = formState.nameField.textFieldState.text.toString().trim()
        if (name.isBlank()) {
            nameMissing.value = true
            return
        }
        nameMissing.value = false
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                nostrRepository.setUpProfileForExistingKey(
                    publicKey = publicKey,
                    placeholderUnsignedNostrEventId = unsignedNostrEventId,
                    name = name,
                    biography = formState.biographyField.textFieldState.text.toString().trim().ifBlank { null },
                )
            } catch (e: Exception) {
                logger.e("could not set up a profile for the key", e)
                isActionPending.value = false
            }
        }
    }
}
