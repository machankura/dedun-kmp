package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.repository.MarmotRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.CreateProfileUIState
import com.machankura.dedun.compose.ui.view.state.form.CreateProfileFormState
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.identity.NewProfileWriter

class CreateProfileViewModel(
    val initialCreateProfileUIState: CreateProfileUIState,
    val createProfileFormState: CreateProfileFormState = CreateProfileFormState(),
    val nostrRepository: NostrRepository,
    val marmotRepository: MarmotRepository
): ViewModel() {
    companion object {
        private const val TAG = "CreateAccountViewModel"

        fun factory(
            initialCreateProfileUIState: CreateProfileUIState,
            nostrRepository: NostrRepository,
            marmotRepository: MarmotRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreateProfileViewModel(
                    initialCreateProfileUIState,
                    nostrRepository = nostrRepository,
                    marmotRepository = marmotRepository
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    var createProfileUIState: MutableState<CreateProfileUIState> = mutableStateOf(
        initialCreateProfileUIState
    )

    fun validateInput(): Boolean {
        if (createProfileFormState.nameField.textFieldState.text.isBlank()) {
            createProfileFormState.nameField.errorMessage.value = "Please input a display name"
            return false
        } else {
            createProfileFormState.nameField.errorMessage.value = null
        }

        if (createProfileFormState.biographyField.textFieldState.text.isBlank()) {
            createProfileFormState.biographyField.errorMessage.value = "Please input a biography"
            return false
        } else {
            createProfileFormState.biographyField.errorMessage.value = null
        }

        return true
    }


    private fun failProfileCreation(e: Throwable) {
        logger.e("error when creating new profile: ", e)
        createProfileUIState.value = CreateProfileUIState.Error
        isActionPending.value = false
    }

    /**
     * Makes the profile: the secret through [newProfile], then the six bootstrap events
     * for the key it derives, then [onProfileCreated] with the id, on the main thread.
     *
     * The secret is written first, so that the events are only ever queued for a key the
     * device holds and the notary can sign; and the events are in the database before the
     * caller's tail activates the identity, so that the navigation machine finds the
     * account the moment it looks -- an ordering the sign-in screen documents as
     * load-bearing and the create flow used to get away with by luck. Which secret is made
     * -- a seed, or a bare key -- is the writer's business; see [NewProfileWriter].
     */
    fun createAccount(newProfile: NewProfileWriter, onProfileCreated: (WalletId) -> Unit) {
        logger.d { "createAccount" }
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            failProfileCreation(e)
        }) {
            val written = newProfile.write().getOrElse { e ->
                failProfileCreation(e)
                return@launch
            }
            logger.d("Pubkey: ${written.nostrPublicKey}")

            nostrRepository.createNewProfile(
                written.nostrPublicKey,
                name = createProfileFormState.nameField.textFieldState.text.toString(),
                biography = createProfileFormState.biographyField.textFieldState.text.toString(),
                onCompletion = {
                    logger.d("Created: ${written.nostrPublicKey}")
                }
            )
            withContext(Dispatchers.Main) { onProfileCreated(written.id) }
        }
    }
}