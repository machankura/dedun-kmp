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
import com.machankura.dedun.compose.managers.GroupStatementRefusal
import com.machankura.dedun.compose.nostr.GroupPost
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.view.state.AddGroupPostUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Asks the group to say something publicly, in its own name.
 *
 * The post is not written here and does not exist yet. What goes out is a proposal
 * to sign one, and the post appears -- on every member's device at once, authored by
 * the room's own key rather than by whoever typed it -- when enough members have
 * signed. That author is the room's id: signing runs at the path the room was
 * derived at, so a post says which group made it simply by being signed.
 *
 * That is the difference from a message in the room. A message is a member speaking
 * where the group can hear; this is the group speaking where anyone can, and it
 * takes a quorum to say.
 */
class AddGroupPostViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialAddGroupPostUIState: AddGroupPostUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var addGroupPostUIState: AddGroupPostUIState by mutableStateOf(initialAddGroupPostUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateAddGroupPost() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            addGroupPostUIState = if (localChatRoom == null) {
                AddGroupPostUIState.Error("Couldn't find the chat room")
            } else {
                AddGroupPostUIState.Loaded(
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
     * Opens a session over one kind:1 authored by the group.
     *
     * Refuses a blank draft rather than proposing it: a quorum signing an empty note
     * would put a post on the record that renders as nothing and cannot be told from
     * a broken one -- and kind:1 is not replaceable, so there is no un-saying it.
     * `GroupPost.newestFirstAmong` drops blanks on the way back out too, which is
     * the second line of the same defence.
     *
     * The draft is not cleared on success. This screen is replaced by the signing
     * session, and on the failure path the words are the thing worth keeping.
     */
    fun proposePost(
        localChatRoom: LocalChatRoom,
        noteField: TextFieldState,
        onSuccess: (sessionId: String) -> Unit,
        onEmpty: () -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits. Posts accumulate rather than replace, so a
        // second session over the same words is a second post on the record forever.
        if (isActionPending.value) return

        val note = noteField.text.toString().trim()
        // The line every member's device declines on too, so a proposal this
        // composer sends is never one the group refuses. GroupStatementRefusal.
        if (GroupStatementRefusal.isBlankPost(note)) {
            onEmpty.invoke()
            return
        }

        isActionPending.value = true

        val template = GroupPost.template(note)

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
                logger.e("Failed to propose a post for $chatRoomId", error)
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
        private const val TAG = "AddGroupPostViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialAddGroupPostUIState: AddGroupPostUIState = AddGroupPostUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddGroupPostViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialAddGroupPostUIState = initialAddGroupPostUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
