package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.view.state.EditGroupNostrProfileUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Asks the group to sign what it says about itself on nostr.
 *
 * The room's id is the pubkey the group signs as, so the kind:0 this proposes is
 * the profile the rest of nostr would read for that key -- authored by the group
 * rather than by whoever filled the form in. Nothing is saved when the button is
 * pressed: what goes out is a proposal, and the profile changes on every member's
 * device at once when a quorum has signed it.
 */
class EditGroupNostrProfileViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialEditGroupNostrProfileUIState: EditGroupNostrProfileUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var editGroupNostrProfileUIState: EditGroupNostrProfileUIState by mutableStateOf(
        initialEditGroupNostrProfileUIState
    )
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateEditGroupNostrProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            editGroupNostrProfileUIState = if (localChatRoom == null) {
                EditGroupNostrProfileUIState.Error("Couldn't find the chat room")
            } else {
                EditGroupNostrProfileUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupNostrProfile = chatRepository.groupNostrProfile(chatRoomId),
                    // The same condition the group detail screen gates the entry
                    // point on.
                    canSign = GroupStatementCapability.roomIsOpen(localChatRoom) &&
                        frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Opens a session over one kind:0 for the group's own key.
     *
     * A blank field is not a field left alone: it clears what the group had said,
     * because that is the only way to unsay it. [GroupNostrProfile.template] hands
     * every field to `MetadataEvent`, whose rule for these arguments is that empty
     * deletes the key -- and it builds on the group's newest profile, so a field
     * this form does not offer is carried across rather than wiped.
     *
     * The fields are not cleared on success, unlike the add forms': a profile is
     * standing state rather than a thing being added, and this screen is replaced
     * by the signing session anyway.
     */
    fun proposeNostrProfile(
        localChatRoom: LocalChatRoom,
        groupNostrProfile: GroupNostrProfile?,
        nameField: TextFieldState,
        aboutField: TextFieldState,
        pictureField: TextFieldState,
        websiteField: TextFieldState,
        nip05Field: TextFieldState,
        onSuccess: (sessionId: String) -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits from repeated taps. A second session over a
        // second kind:0 would leave the group's profile decided by whichever
        // quorum finished last.
        if (isActionPending.value) return
        isActionPending.value = true

        val template = GroupNostrProfile.template(
            latest = groupNostrProfile,
            name = nameField.text.toString().trim(),
            about = aboutField.text.toString().trim(),
            picture = pictureField.text.toString().trim(),
            website = websiteField.text.toString().trim(),
            nip05 = nip05Field.text.toString().trim(),
        )

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a nostr profile for $chatRoomId", error)
            }.getOrNull()

            viewModelScope.launch(Dispatchers.Main) {
                if (session != null) {
                    onSuccess.invoke(session.id)
                } else {
                    onFailure.invoke()
                }
            }

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "EditGroupNostrProfileViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialEditGroupNostrProfileUIState: EditGroupNostrProfileUIState =
                EditGroupNostrProfileUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditGroupNostrProfileViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialEditGroupNostrProfileUIState = initialEditGroupNostrProfileUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
