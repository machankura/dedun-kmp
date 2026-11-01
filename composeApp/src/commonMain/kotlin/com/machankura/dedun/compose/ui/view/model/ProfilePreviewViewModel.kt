package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.nostr.MarmotProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.nostr.DirectMessagePeerSync
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState.Readiness
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Finds the person behind a pasted npub, and says whether a chat with them can start.
 *
 * Cached first: the observed rows are whatever the device already holds, so a person
 * already known is on screen before any relay answers, and the relays refresh them
 * behind it. The relays are asked twice over -- the kind 0 of the indexers, which hold
 * everyone's, and the three kinds a chat needs of the DM relays -- through
 * [DirectMessagePeerSync], so that pressing the button on the screen after this one
 * finds the key package already here.
 *
 * Both halves are observed together, as `ChatRoomMessagingViewModel` does, because a
 * key package arriving after the profile touches a different table and a collect on
 * the profile alone would never see it. A room that already exists is read once, up
 * front: a key package is meant to be used once, and the relays need not hold a fresh
 * one for a person you already have a room with.
 *
 * One timeout, the one both DM view models use, and it only changes what is shown:
 * nothing found becomes not-found, and a person found without a key package becomes
 * not-yet-on-Bennu. The collect stays live past it, so whatever arrives on the
 * twenty-first second still lands. See docs/npub-profile-preview.md.
 */
class ProfilePreviewViewModel(
    val activeUserPublicKey: HexKey,
    val profilePublicKey: HexKey,
    initialProfilePreviewUIState: ProfilePreviewUIState,
    private val nostrRepository: NostrRepository,
    private val chatRepository: ChatRepository,
    /** How long the relays get before the screen says it came up empty. A parameter so a test need not wait it out. */
    private val relayLookupTimeout: Duration = RELAY_LOOKUP_TIMEOUT,
) : ViewModel() {

    var profilePreviewUIState: ProfilePreviewUIState by mutableStateOf(initialProfilePreviewUIState)
        private set

    private val logger = Logger.withTag(TAG)

    private var started = false
    private var lookupTimeout: Job? = null

    /** Set when the timeout fires; cleared by a retry. Written and read on different coroutines. */
    @Volatile
    private var relaysHadTheirTime = false

    /** Once. A recomposition that calls this again must not open a second collect. */
    fun initiate() {
        if (started) return
        started = true
        observe()
        askTheRelays()
    }

    /**
     * Asks again. The observation is still live, so the answer arrives through the same
     * collect. With a person on screen it is the key package being asked for, and the
     * DM relays alone are asked; without one, everything is.
     */
    fun retry() {
        relaysHadTheirTime = false
        when (val state = profilePreviewUIState) {
            is ProfilePreviewUIState.Loaded -> {
                if (state.readiness == Readiness.NotYetOnDedun) {
                    profilePreviewUIState = state.copy(readiness = Readiness.Checking)
                }
                askTheRelays(profileToo = false)
            }
            else -> {
                profilePreviewUIState = ProfilePreviewUIState.Loading
                askTheRelays(profileToo = true)
            }
        }
    }

    private fun observe() {
        viewModelScope.launch(Dispatchers.IO) {
            val existingRoom = chatRepository.findDirectMessageRoom(
                userPublicKey = activeUserPublicKey,
                peerPublicKey = profilePublicKey,
            )?.let { localChatRoom ->
                Readiness.ExistingChat(
                    chatRoomId = localChatRoom.chatRoom.id,
                    relayHint = localChatRoom.localParticipants
                        .firstOrNull { it.participant.participantPublicKey == profilePublicKey }
                        ?.participant?.relayHint,
                )
            }

            combine(
                nostrRepository.observeProfileWithPublicKey(profilePublicKey),
                chatRepository.observeMarmotKeyPackageForPublicKey(profilePublicKey, MarmotProfile.DEFAULT_FOR_NEW_ROOMS),
            ) { profile, keyPackage ->
                // A placeholder is not a person. Mapping it to null here is what keeps
                // "LOADING..." off a screen whose one job is to say who this is.
                profile?.takeIf { it.isResolved() } to keyPackage
            }
                .distinctUntilChanged()
                .collect { (profile, keyPackage) ->
                    if (profile == null) return@collect
                    show(profile, keyPackage, existingRoom)
                }
        }
    }

    private fun show(profile: Profile, keyPackage: MarmotKeyPackage?, existingRoom: Readiness.ExistingChat?) {
        val readiness = when {
            existingRoom != null -> existingRoom
            keyPackage != null -> Readiness.CanStartChat
            relaysHadTheirTime -> Readiness.NotYetOnDedun
            else -> Readiness.Checking
        }
        // The clock stops only when there is nothing left to wait for. A profile on its
        // own cancels nothing: the key package is still owed.
        if (readiness != Readiness.Checking) lookupTimeout?.cancel()
        profilePreviewUIState = ProfilePreviewUIState.Loaded(profile = profile, readiness = readiness)
    }

    private fun askTheRelays(profileToo: Boolean = true) {
        lookupTimeout?.cancel()
        lookupTimeout = viewModelScope.launch(Dispatchers.IO) {
            delay(relayLookupTimeout)
            relaysHadTheirTime = true
            when (val state = profilePreviewUIState) {
                ProfilePreviewUIState.Loading -> profilePreviewUIState = ProfilePreviewUIState.NotFound
                is ProfilePreviewUIState.Loaded ->
                    if (state.readiness == Readiness.Checking) {
                        profilePreviewUIState = state.copy(readiness = Readiness.NotYetOnDedun)
                    }
                else -> Unit
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (profileToo) {
                    nostrRepository.queueSynchronizeNostrEvent(
                        DirectMessagePeerSync.profileRequests(profilePublicKey)
                    )
                }
                nostrRepository.queueNegentropySynchronizeRequest(
                    DirectMessagePeerSync.requests(profilePublicKey)
                )
            } catch (e: Exception) {
                logger.e("could not ask the relays about $profilePublicKey", e)
                lookupTimeout?.cancel()
                profilePreviewUIState = ProfilePreviewUIState.Error
            }
        }
    }

    companion object {
        private const val TAG = "ProfilePreviewViewModel"

        /** How long to wait on the relays before telling the user we came up empty. */
        val RELAY_LOOKUP_TIMEOUT = 20.seconds

        fun factory(
            activeUserPublicKey: HexKey,
            profilePublicKey: HexKey,
            initialProfilePreviewUIState: ProfilePreviewUIState = ProfilePreviewUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProfilePreviewViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    profilePublicKey = profilePublicKey,
                    initialProfilePreviewUIState = initialProfilePreviewUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
