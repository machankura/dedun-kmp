package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class WriteNewNoteViewModel(
    val activeUserPublicKey: HexKey,
    val replyToNostrEventId: HexKey?,
    val quotedNostrEventId: HexKey?,

    initialWriteNewNoteUIState: com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState,
    val writeNewNoteFormState: com.machankura.dedun.compose.ui.view.state.form.WriteNewNoteFormState = com.machankura.dedun.compose.ui.view.state.form.WriteNewNoteFormState(),
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
): ViewModel() {
    companion object {
        private const val TAG = "WriteNewNoteViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            replyToNostrEventId: HexKey?,
            quotedNostrEventId: HexKey?,
            initialWriteNewNoteUIState: com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WriteNewNoteViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    replyToNostrEventId = replyToNostrEventId,
                    quotedNostrEventId = quotedNostrEventId,
                    initialWriteNewNoteUIState = initialWriteNewNoteUIState,
                    nostrRepository = nostrRepository
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    var writeNewNoteUIState: com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState by mutableStateOf(initialWriteNewNoteUIState)
        private set

    var mentionableProfiles = mutableStateListOf<com.machankura.dedun.compose.database.model.Profile>()
        private set

    var mentionedProfiles = mutableStateListOf<com.machankura.dedun.compose.database.model.Profile>()
        private set

    var atSymbolRange = mutableStateOf<TextRange?>(null)

    fun initiate() {
        observeActiveUserProfileWithFollowing()
        loadSearchableProfiles()
    }

    fun loadSearchableProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            mentionableProfiles.addAll(
                nostrRepository.searchableProfiles()
            )
        }
    }

    fun addProfileToMentionedProfiles(profile: com.machankura.dedun.compose.database.model.Profile) {
        atSymbolRange.value?.let { range ->
            mentionedProfiles.add(profile)
            writeNewNoteFormState.textFieldForm.textFieldState.edit {
                val mentionProfile = "nostr:${
                    NProfile.create(
                        profile.publicKey,
                        null // TODO: Get the relay this profile was seen from...
                    )
                }"

                replace(
                    range.start,
                    range.end,
                    mentionProfile
                )
            }
        }
    }

    fun observeActiveUserProfileWithFollowing() {
        logger.d("observeActiveUserProfileWithFollowing")
        viewModelScope.launch(Dispatchers.IO) {
            val nostrEventBeingRepliedTo = replyToNostrEventId?.let {
                nostrRepository.observeLocalNostrEventById(
                    it
                ).firstOrNull()
            }

            val nostrEventBeingQuoted = quotedNostrEventId?.let {
                nostrRepository.observeLocalNostrEventById(
                    it
                ).firstOrNull()
            }

            val activePublicKey = activeUserPublicKey

            // TODO: Get note being replied too...
            // TODO: Get users being mentioned...
            nostrRepository.observeProfileWithFollowing(
                activePublicKey
            ).collect { profileWithFollowing ->
                writeNewNoteUIState = if (profileWithFollowing != null) {
                    com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.InputPrompt(
                        localProfile = profileWithFollowing,
                        inReplyToNostrEvent = nostrEventBeingRepliedTo,
                        quotedNostrEvent = nostrEventBeingQuoted
                    )
                } else {
                    com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.Error
                }
            }
        }
    }

    fun confirmNote(
        localProfile: com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing,
        inReplyToNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent?,
        quotedNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent?
    ) {
        validateInput().let { isInputValid ->
            if (isInputValid) {
                writeNewNoteUIState =
                    com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.ConfirmNote(
                        localProfile = localProfile,
                        inReplyToNostrEvent = inReplyToNostrEvent,
                        quotedNostrEvent = quotedNostrEvent,
                        text = writeNewNoteFormState.textFieldForm.textFieldState.text.toString(),
                    )
            }
        }

    }

    fun validateInput(): Boolean {
        if (writeNewNoteFormState.textFieldForm.textFieldState.text.isBlank()) {
            writeNewNoteFormState.textFieldForm.errorMessage.value = "Please input a note"
            return false
        } else {
            writeNewNoteFormState.textFieldForm.errorMessage.value = null
        }
        return true
    }

    fun createNewNote(
        activeUserPublicKey: HexKey,
        onNostrEventPublished: () -> Unit,
        inReplyToNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent? = null,
        quotedNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent? = null,
    ) {
        logger.d { "createAccount" }
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val mentionedPublicKeys = com.machankura.dedun.compose.ui.composable.widgets.content.ContentParser.parse(
                writeNewNoteFormState.textFieldForm.textFieldState.text.toString(),
            ).filterIsInstance<com.machankura.dedun.compose.ui.composable.widgets.content.ContentSegment.NostrProfileSegment>().map { it.pubkey }

            nostrRepository.createNewTextNote(
                publicKey = activeUserPublicKey,
                mentionedPublicKeys = mentionedPublicKeys,
                textInput = writeNewNoteFormState.textFieldForm.textFieldState.text.toString(),
                inReplyToNostrEvent = inReplyToNostrEvent,
                quotedNostrEvent = quotedNostrEvent,
                onError = {
                    writeNewNoteUIState = com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.Error
                },
                onSuccess = onNostrEventPublished
            )
        }
    }
}