package com.machankura.dedun.compose.ui.view.model

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
import com.machankura.dedun.compose.nostr.GroupEventProposal
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.view.state.ProposeGroupEventUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Asks the group to sign an event a member pasted.
 *
 * Nothing is built here; the paste is the event. What this decides is whether
 * the group should be asked at all -- `GroupEventProposal.read` refuses a paste
 * the group's screen could never show, so a quorum is not spent on an event that
 * would be signed, filed and seen by nobody -- and it hands the kind, tags and
 * content to the same `proposeSigning` every typed editor uses. The author and
 * the time are the session's to fill in, which is how a pasted `pubkey` or
 * `created_at` comes to be ignored: they are never passed along.
 *
 * Where the event lands is decided by nothing here. `FrostSigningManager.complete`
 * files every signed event as a `GroupSignedEvent`, and the group's screen reads
 * its profile and posts off those by kind. A pasted kind 0
 * becomes the profile the same way a kind 0 from the profile form does, because
 * by the time either is signed there is no telling them apart.
 */
class ProposeGroupEventViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialProposeGroupEventUIState: ProposeGroupEventUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var proposeGroupEventUIState: ProposeGroupEventUIState by mutableStateOf(
        initialProposeGroupEventUIState
    )
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateProposeGroupEvent() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            proposeGroupEventUIState = if (localChatRoom == null) {
                ProposeGroupEventUIState.Error("Couldn't find the chat room")
            } else {
                ProposeGroupEventUIState.Loaded(
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
     * Opens a session over the pasted event, or says why not.
     *
     * [onRefused] carries the reading's own reason, which the screen has been
     * showing under the paste all along; it is repeated here so pressing the
     * button on a refused paste is answered rather than ignored. Both refusals
     * and a failed proposal stay on the paste: the JSON is the thing worth
     * keeping.
     */
    fun proposeEvent(
        localChatRoom: LocalChatRoom,
        json: String,
        onSuccess: (sessionId: String) -> Unit,
        onRefused: (GroupEventProposal.Refused) -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits. A pasted kind:1 accumulates like any
        // other post, so a second session over the same paste is a second post
        // on the record forever.
        if (isActionPending.value) return

        val ready = when (val reading = GroupEventProposal.read(json)) {
            is GroupEventProposal.Ready -> reading
            is GroupEventProposal.Refused -> {
                onRefused.invoke(reading)
                return
            }
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = ready.kind,
                    tags = ready.tags,
                    content = ready.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a pasted kind ${ready.kind} event for $chatRoomId", error)
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
        private const val TAG = "ProposeGroupEventViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialProposeGroupEventUIState: ProposeGroupEventUIState =
                ProposeGroupEventUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProposeGroupEventViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialProposeGroupEventUIState = initialProposeGroupEventUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
