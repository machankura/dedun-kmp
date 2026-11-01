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
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.exceptions.MarmotCommitPendingException
import com.machankura.dedun.compose.exceptions.MarmotConvergencePendingException
import com.machankura.dedun.compose.exceptions.MarmotKeyPackageProfileException
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.AddMemberToChatRoomConfirmationUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class AddMemberToChatRoomConfirmationViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val profilePublicKey: HexKey,
    val relayHint: String?,
    initialAddMemberToChatRoomConfirmationUIState: AddMemberToChatRoomConfirmationUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository
): ViewModel() {

    var addMemberToChatRoomConfirmationUIState: AddMemberToChatRoomConfirmationUIState by mutableStateOf(initialAddMemberToChatRoomConfirmationUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        logger.d("compressed (most likely chat room): $chatRoomId")
        // Get ChatRoomById
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            if (localChatRoom == null) {
                addMemberToChatRoomConfirmationUIState =
                    AddMemberToChatRoomConfirmationUIState.Error("Couldn't load chat")
                return@launch
            }

            val profile = nostrRepository.getProfileWithPublicKey(profilePublicKey)
            if (profile == null) {
                addMemberToChatRoomConfirmationUIState =
                    AddMemberToChatRoomConfirmationUIState.Error("Failed to find profile...")
                return@launch
            }

            // Don't depend on the bulk sync the previous screen fired asynchronously — the
            // contact list is tappable long before that relay round-trip can finish. Ask for
            // this one peer's key package ourselves.
            scheduleKeyPackageSync()

            // Wait for the relays, but don't spin forever with no explanation. Cancelling this
            // job (rather than testing a shared flag) is what keeps the timeout from racing the
            // arrival and clobbering a Loaded state.
            val lookupTimeout = launch {
                delay(RELAY_LOOKUP_TIMEOUT)
                addMemberToChatRoomConfirmationUIState =
                    AddMemberToChatRoomConfirmationUIState.Error("Profile missing key package event. Try again later or tell ${profile.humanReadableNameOrPubkey()} to use Bennu.")
            }

            // Observe rather than read once: the key package usually lands a moment after
            // this screen opens, and the old one-shot read latched a terminal error that no
            // amount of waiting could clear.
            // The package has to fit the room: a current-profile room can add only a
            // current-profile leaf, and the newest package of any profile is, while a
            // peer publishes both, deliberately the legacy one.
            val roomProfile = localChatRoom.chatRoom.toMlsGroup()
                ?.let { if (it.currentGroupState().isCurrentProfile) MarmotProfile.CURRENT else MarmotProfile.LEGACY }
                ?: MarmotProfile.LEGACY
            chatRepository.observeMarmotKeyPackageForPublicKey(profilePublicKey, roomProfile)
                .distinctUntilChanged()
                .collect { marmotKeyPackage ->
                    if (marmotKeyPackage != null) {
                        lookupTimeout.cancel()
                        addMemberToChatRoomConfirmationUIState =
                            AddMemberToChatRoomConfirmationUIState.Loaded(
                                localChatRoom = localChatRoom,
                                profile = profile,
                                marmotKeyPackage = marmotKeyPackage
                            )
                    }
                }
        }
    }

    private suspend fun scheduleKeyPackageSync() {
        val synchronizationFilter = SynchronizationFilter(
            authors = arrayOf(profilePublicKey),
            kinds = arrayOf(KeyPackageEvent.KIND)
        )
        nostrRepository.queueNegentropySynchronizeRequest(
            Relays.DefaultDMRelayList.map { relay ->
                NegentropySynchronizeRequest(
                    id = NegentropySynchronizeRequest.computeId(
                        relayURL = relay.url,
                        synchronizationFilter = synchronizationFilter
                    ),
                    purpose = "key-packages",
                    synchronizationFilter = synchronizationFilter,
                    relayURL = relay.url,
                    level = 0
                )
            }
        )
    }

    fun inviteToChatRoom(
        localChatRoom: LocalChatRoom,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage,
        onInviteSent: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Invite member. `inviteMember` can throw (no MLS state for the room, credential
            // identity mismatch); reporting success regardless popped the user back to the chat
            // as though an invite had been sent when nothing was ever queued.
            val outcome = runCatching {
                chatRepository.inviteMember(
                    localChatRoom = localChatRoom,
                    peerPublicKey = peerPublicKey,
                    peerKeyPackage = peerKeyPackage
                )
            }

            outcome.exceptionOrNull()?.let { e ->
                logger.e("Failed to invite $peerPublicKey to ${localChatRoom.chatRoom.id}", e)
                isActionPending.value = false
                // A key package on the wrong profile is the one failure the member can
                // fix, and a commit still waiting for a relay is the one that fixes
                // itself, so both are shown as themselves rather than as "try again".
                addMemberToChatRoomConfirmationUIState = AddMemberToChatRoomConfirmationUIState.Error(
                    when (e) {
                        is MarmotKeyPackageProfileException, is MarmotCommitPendingException, is MarmotConvergencePendingException -> e.message
                        else -> null
                    } ?: "Couldn't send the invite. Please try again."
                )
                return@launch
            }

            viewModelScope.launch(Dispatchers.Main) {
                onInviteSent.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "AddMemberToChatRoomConfirmationViewModel"

        /** How long to wait on the relays before telling the user we came up empty. */
        private val RELAY_LOOKUP_TIMEOUT = 20.seconds

        fun factory(
            activeUserPublicKey: HexKey,
            profilePublicKey: HexKey,
            chatRoomId: HexKey,
            relayHint: String?,
            initialAddMemberToChatRoomConfirmationUIState: AddMemberToChatRoomConfirmationUIState = AddMemberToChatRoomConfirmationUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddMemberToChatRoomConfirmationViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    profilePublicKey = profilePublicKey,
                    relayHint = relayHint,
                    initialAddMemberToChatRoomConfirmationUIState = initialAddMemberToChatRoomConfirmationUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}