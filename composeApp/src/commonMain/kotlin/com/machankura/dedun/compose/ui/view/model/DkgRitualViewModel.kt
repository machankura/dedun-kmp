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
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.ChillDkgRitualManager
import com.machankura.dedun.compose.managers.MarmotGroupCreation
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers.Main
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.view.state.DkgRitualUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Watches one ChillDKG ritual, and the steps that follow it.
 *
 * Nothing here drives the protocol — the ritual advances in
 * [ChillDkgRitualManager] as messages arrive on the inbound path, so this only
 * has to read the session and show it moving.
 *
 * It never opens one. That is `CreateSharedKeyViewModel`'s job for a group's own
 * key, and the subgroup picker's for a subgroup's: a screen that showed a
 * ceremony and offered to start one from the same button is how a member
 * approving a subgroup's ceremony opened a second -- see
 * docs/ceremony-creation.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DkgRitualViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    /** The ceremony to show -- see [DkgRitualRoute.dkgSessionId]. */
    val dkgSessionId: String,
    initialDkgRitualUIState: DkgRitualUIState,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val chatRepository: ChatRepository,
    val dkgRepository: DkgRepository,
): ViewModel() {

    var dkgRitualUIState: DkgRitualUIState by mutableStateOf(initialDkgRitualUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Whether [initiate] has run. The view model's to decide, not the screen's. */
    private var initiated = false

    /**
     * Members the admin room is waiting on for a key package. The one part of the
     * state an action writes rather than a flow, so it is a flow too, and goes
     * through the same single write as everything else.
     */
    private val adminGroupBlockedOn = MutableStateFlow<List<String>>(emptyList())

    fun initiate() {
        // The screen asks every time it comes back into view -- returning from an
        // approval screen, say -- and starting again used to reset the state to
        // "no ceremony" and stack a second set of watchers on the first. The
        // first run's watchers are still running, so there is nothing to start.
        if (initiated) return
        initiated = true

        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                dkgRitualUIState = DkgRitualUIState.Error("Couldn't load this chat")
                return@launch
            }

            // One writer. Every part of the screen used to be its own coroutine
            // reading the state, copying one field and writing it back, and one
            // that read before another wrote put the other's field back the way it
            // was -- which could leave the screen with no ceremony on it, offering
            // to start one. Now each part is a flow and the state is built from all
            // of them at once.
            ceremonyState(localChatRoom).collect { state ->
                // An action that failed has said so; a later emission must not
                // paper over it.
                if (dkgRitualUIState is DkgRitualUIState.Error) return@collect

                dkgRitualUIState = state
            }
        }
    }

    /**
     * The whole screen as one flow, so no part of it can overwrite another.
     *
     * The purpose -- a group's own key or a subgroup's -- is read off the
     * session rather than handed in, because only the member who opened a
     * subgroup arrives knowing it is one; everybody else comes from a transcript
     * line that names the ceremony and nothing more.
     */
    private fun ceremonyState(localChatRoom: LocalChatRoom): Flow<DkgRitualUIState> {
        val sessions = dkgRepository.observeSessionById(dkgSessionId)

        // The session in which the group agrees what its admin room will sign
        // with. Matched on the ceremony the state names, not on being the newest
        // key state in the room: a subgroup's ceremony runs in its parent's room,
        // and so does every other subgroup's, so the newest one there is as
        // likely a sibling's as this one's.
        val keyStateSession = dkgRepository.observeSigningSessions(chatRoomId)
            .map { sessions ->
                sessions.firstCarrying(GroupKeyStateEvent.KIND) {
                    GroupKeyStateEvent.parseDkgSessionId(it.tags) == dkgSessionId
                }
            }

        // Everything about one ceremony, re-pointed when the ceremony, its
        // purpose or its key changes -- which for a member arriving cold is at
        // most twice, the key arriving as the ceremony finishes -- and not on
        // every change to the session row.
        val parts = sessions
            .map { Triple(it?.id, it?.parentChatRoomId, it?.thresholdPublicKey) }
            .distinctUntilChanged()
            .flatMapLatest { (sessionId, parent, key) -> ceremonyParts(sessionId, parent, key) }

        return combine(
            sessions,
            parts,
            keyStateSession,
            adminGroupBlockedOn,
        ) { session, ceremony, keyState, blockedOn ->
            Snapshot(session, ceremony, keyState, blockedOn)
        }
            // The parts lag the session by one emission when the ceremony changes;
            // a state that drew one ceremony's counts under another's name is the
            // thing this is here to stop.
            .filter { it.ceremony.sessionId == it.session?.id }
            .mapLatest { snapshot ->
                // A ceremony this device has no row for. Every way here names one
                // the device recorded -- a transcript line it wrote, or one it has
                // just opened -- so this is a bug to say out loud rather than an
                // empty screen to fill with an offer.
                val session = snapshot.session
                    ?: return@mapLatest DkgRitualUIState.Error("Couldn't find this key ceremony")
                val ceremony = snapshot.ceremony

                DkgRitualUIState.Loaded(
                    localChatRoom = localChatRoom,
                    session = session,
                    hostKeyParticipants = ceremony.hostKeyParticipants,
                    round1Participants = ceremony.round1Participants,
                    round2Participants = ceremony.round2Participants,
                    // Computed here, before the one write, rather than read and
                    // written around a suspension: whether round 1 can be asked
                    // for depends on how many host keys have arrived, which only
                    // the messages flow sees.
                    pendingApproval = dkgRepository.pendingApproval(session),
                    adminGroupBlockedOn = snapshot.adminGroupBlockedOn,
                    keyStateSession = snapshot.keyStateSession,
                    isKeyStateSigned = ceremony.isKeyStateSigned,
                    parentChatRoomId = session.parentChatRoomId,
                    isCertified = ceremony.isCertified,
                    certificateSession = ceremony.certificateSession,
                )
            }
    }

    /**
     * One ceremony's messages, whether its key state has been signed, and -- for
     * a subgroup -- whether its parent has certified it.
     *
     * The certificate is read off the *parent's* signed events and sessions
     * rather than this room's: it is made where the parent's key can sign it,
     * which is never the ceremony's room. An ordinary ceremony passes no parent
     * and gets nothing watched there, which is exactly right.
     *
     * The parent's room holds a certificate session for every subgroup it has
     * been asked about, so the one shown is the one naming [thresholdPublicKey].
     * Taking the newest regardless is how a parent's second subgroup opened on
     * its first one's finished certificate, with the button to ask for its own
     * already disabled. Before the ceremony has a key there is nothing to have
     * certified, and no session is this one's.
     */
    private fun ceremonyParts(
        sessionId: String?,
        parentChatRoomId: HexKey?,
        thresholdPublicKey: HexKey?,
    ): Flow<CeremonyParts> {
        if (sessionId == null) return flowOf(CeremonyParts(sessionId = null))

        val certified = parentChatRoomId
            ?.let { parent -> dkgRepository.observeBirthCertificate(sessionId, parent).map { it != null } }
            ?: flowOf(false)

        val certificateSession = parentChatRoomId
            ?.takeIf { thresholdPublicKey != null }
            ?.let { parent ->
                dkgRepository.observeSigningSessions(parent).map { sessions ->
                    sessions.firstCarrying(SubgroupBirthCertificateEvent.KIND) {
                        SubgroupBirthCertificateEvent.parseThresholdPublicKey(it.tags)
                            .equals(thresholdPublicKey, ignoreCase = true)
                    }
                }
            }
            ?: flowOf(null)

        return combine(
            dkgRepository.observeMessages(sessionId),
            // Asked by ceremony rather than by room: the room holds however many
            // ceremonies it holds, and the answer is about this one's key.
            dkgRepository.observeSignedGroupKeyState(sessionId).map { it != null },
            certified,
            certificateSession,
        ) { messages, isKeyStateSigned, isCertified, certificate ->
            fun senders(kind: Kind) = messages
                .filter { it.kind == kind }
                .map { it.participantPublicKey }
                .toSet()

            CeremonyParts(
                sessionId = sessionId,
                hostKeyParticipants = senders(DkgRitualEvents.HOST_KEY),
                round1Participants = senders(DkgRitualEvents.ROUND_1),
                round2Participants = senders(DkgRitualEvents.ROUND_2),
                isKeyStateSigned = isKeyStateSigned,
                isCertified = isCertified,
                certificateSession = certificate,
            )
        }
    }

    /**
     * The newest signing session carrying an event of [kind] that is [about]
     * this ceremony, if there is one.
     */
    private fun List<LocalFrostSigningSession>.firstCarrying(
        kind: Kind,
        about: (Event) -> Boolean,
    ): FrostSigningSession? =
        firstOrNull { local ->
            local.items.any { item ->
                runCatching { Event.fromJson(item.unsignedEventJson) }
                    .getOrNull()
                    ?.let { it.kind == kind && about(it) } == true
            }
        }?.session

    /** What [ceremonyParts] knows about the ceremony named by [sessionId]. */
    private data class CeremonyParts(
        val sessionId: String?,
        val hostKeyParticipants: Set<HexKey> = emptySet(),
        val round1Participants: Set<HexKey> = emptySet(),
        val round2Participants: Set<HexKey> = emptySet(),
        val isKeyStateSigned: Boolean = false,
        val isCertified: Boolean = false,
        val certificateSession: FrostSigningSession? = null,
    )

    private data class Snapshot(
        val session: DkgSession?,
        val ceremony: CeremonyParts,
        val keyStateSession: FrostSigningSession?,
        val adminGroupBlockedOn: List<String>,
    )

    /**
     * Asks the group to sign what its admin room will sign with.
     *
     * The step before the room exists, and the reason it can be created knowing
     * its own key state rather than being told afterwards. Offered to the
     * ceremony's coordinator, who is also the member who will create the room.
     *
     * Nothing here waits for the answer: the signing runs on arriving messages
     * like everything else, and the screen follows it through [ceremonyState].
     */
    fun proposeKeyState() {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        val session = loaded.session
        if (session.thresholdPublicKey == null) return

        // Nothing to ask for, and nobody to ask twice. A failed session is the
        // one case a second proposal is right, and it has to be a new session
        // rather than that one resumed -- see `FrostSigningManager`, which is
        // why `propose` mints a fresh id every time.
        if (loaded.isKeyStateSigned) return
        loaded.keyStateSession?.let { if (it.stage != FrostSigningStage.FAILED) return }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // A subgroup's state carries the parent's certificate, and refuses
            // to be proposed without one -- every device that receives it runs
            // the same check and drops it, so proposing one this device would not
            // believe spends a quorum's attention on nothing.
            val subgroupParent = loaded.parentChatRoomId

            val signing = if (subgroupParent != null) {
                dkgRepository.proposeSubgroupKeyState(
                    ceremonyRoom = loaded.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    session = session,
                    parentChatRoomId = subgroupParent
                )
            } else {
                dkgRepository.proposeGroupKeyState(
                    localChatRoom = loaded.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    session = session
                )
            }

            isActionPending.value = false

            if (signing == null) {
                logger.e("Failed to propose the key state for $chatRoomId")
                dkgRitualUIState = DkgRitualUIState.Error(
                    "Couldn't ask the group to agree its signing key. Please try again."
                )
            }
            // On success the session flow above delivers the new row; no need to
            // set it here and risk racing the observer.
        }
    }

    /**
     * Asks the parent's quorum to certify the subgroup this ceremony produced.
     *
     * Step 2 of four, and the only one that runs somewhere else: the certificate
     * is signed in the *parent's* room, because it is the parent's signature.
     * Offered to whoever holds a share of the parent's key, which the coordinator
     * does by construction -- they are a parent admin, that is how they got here.
     *
     * Nothing waits for the answer. The session runs on arriving messages like
     * every other, and [ceremonyState] follows it.
     */
    fun proposeBirthCertificate() {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        // The state's, not the route's. Every step after the ceremony is open to
        // any of the subgroup's admins -- see `SubgroupManager` -- and they reach
        // this screen from the room, with no route to have told them anything.
        val parent = loaded.parentChatRoomId ?: return
        val session = loaded.session
        if (session.thresholdPublicKey == null) return

        // Nothing to ask for, and nobody to ask twice. A failed session is the
        // one case a second proposal is right, and it has to be a new session
        // rather than that one resumed.
        if (loaded.isCertified) return
        loaded.certificateSession?.let { if (it.stage != FrostSigningStage.FAILED) return }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val signing = dkgRepository.proposeBirthCertificate(
                parentChatRoomId = parent,
                userPublicKey = activeUserPublicKey,
                session = session,
                // The ceremony's participants, not the room's members. The
                // ceremony runs in the parent's room, so the room's roster is
                // everybody the subgroup is *not* -- putting it here would ask the
                // parent to certify itself as its own child.
                adminPublicKeys = ChillDkgRitualManager
                    .participantsOf(session, loaded.localChatRoom)
                    .toList(),
                // The name the coordinator typed, carried to every other admin on
                // the proposal -- so this is the same name they have all been
                // looking at, and the one the parent's quorum is about to sign.
                name = session.subject ?: "Subgroup"
            )

            isActionPending.value = false

            if (signing == null) {
                logger.e("Failed to propose a birth certificate in $parent")
                dkgRitualUIState = DkgRitualUIState.Error(
                    "Couldn't ask the group to certify this subgroup. Please try again."
                )
            }
        }
    }

    /**
     * Ends this ceremony for everyone in it. The screen asks the member to confirm
     * first; the session flow delivers the failed row, so nothing is set here.
     */
    fun abandon() {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        if (!loaded.canAbandon) return

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            dkgRepository.abandon(loaded.localChatRoom, loaded.session.id)
            isActionPending.value = false
        }
    }

    /**
     * Agrees to whatever the ritual is currently waiting on from this device.
     *
     * Takes the step rather than reading it back from the state so a screen opened
     * for one step cannot approve a different one it has since moved on to — the
     * manager checks it against the ritual again and ignores a mismatch.
     *
     * [onApproved] runs once the approval has been sent, not when the button is
     * pressed. The approval screens close on it, and closing one clears this view
     * model and cancels its scope -- so closing first could cut the publish off,
     * and on the last approval of a round nothing arrives afterwards to resume it.
     */
    fun approve(step: DkgApprovalStep, onApproved: () -> Unit = {}) {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        val session = loaded.session

        val nostrPrivateKey = activeIdentityStateFlow.value?.nostrPrivateKey
        if (nostrPrivateKey == null) {
            dkgRitualUIState = DkgRitualUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            dkgRepository.approve(
                localChatRoom = loaded.localChatRoom,
                sessionId = session.id,
                step = step,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray()
            )

            isActionPending.value = false
            // The session flow delivers the updated row and with it the next pending
            // step, so nothing is set here.

            withContext(Main) { onApproved() }
        }
    }

    /**
     * Opens the group's admin room: a Marmot/MLS chat whose every member is an
     * admin, keyed on an id derived from the shared key.
     *
     * The room the ceremony ran in is somebody else's -- a NIP-17 room where
     * nobody administers anything, or the parent whose subgroup this is. This
     * gives the people who hold shares a room where every one of them can act,
     * which is the shape a group that has just made a t-of-n key is asking for.
     *
     * Derived rather than random, unlike every other Marmot room. Every member's
     * device can compute the id from the ceremony they all took part in, so the
     * room is addressable without being announced, and two members racing to create
     * it produce the same id instead of two rival rooms -- which is why this returns
     * early to the existing room rather than minting a second one.
     *
     * Only once the group has signed what the room signs with. That statement is
     * agreed in the room the ceremony ran in, before there is a room of its own
     * to hold it -- see `GroupKeyStateManager.propose` -- so by here it is a fact
     * the room is created *knowing*, rather than the first thing it has to go and
     * ask about.
     */
    fun createAdminGroup(onNavigateToRoute: (Route) -> Unit) {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        val session = loaded.session
        val thresholdPublicKey = session.thresholdPublicKey ?: return

        // The screen offers nothing until the group has signed, so this is a
        // guard rather than a path anybody takes.
        if (!loaded.isKeyStateSigned) return

        val nostrPrivateKey = activeIdentityStateFlow.value?.nostrPrivateKey
        if (nostrPrivateKey == null) {
            dkgRitualUIState = DkgRitualUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        val keyPair = KeyPair(privKey = nostrPrivateKey.value.toByteArray())
        val groupId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

        // Everyone the ceremony was run with, this device included. These are the
        // admins: the whole point of the room is that the members who hold shares of
        // the key can all act in it.
        //
        // Off the ceremony rather than off the room, because for a subgroup the
        // room is the parent's and its roster is the wrong set entirely.
        val members = ChillDkgRitualManager.participantsOf(session, loaded.localChatRoom)

        // A subgroup is called what the coordinator called it; an admin room is
        // called after the group it administers, because it has no name of its
        // own to be given. Either way the bare name goes down and
        // `MarmotGroupCreation` puts it under `MarmotGroupName`, so a NIP-17 room
        // called `Ekklesia` stands up a Marmot room called `#Ekklesia`.
        val subgroupParent = loaded.parentChatRoomId

        val name = session.subject
            ?: if (subgroupParent != null) "Subgroup" else "Group"

        isActionPending.value = true
        adminGroupBlockedOn.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            val outcome = chatRepository.createMarmotGroup(
                groupId = groupId,
                name = name,
                purpose = if (subgroupParent != null) {
                    "A subgroup of the group that certified it."
                } else {
                    "Admins of ${session.subject ?: "the group"}."
                },
                adminPublicKeys = members,
                userPublicKey = activeUserPublicKey,
                keyPair = keyPair,
                // Verified by the time it is written: the key state this room was
                // gated on carries the certificate, and `stateFrom` refused it
                // otherwise.
                parentChatRoomId = subgroupParent
            )

            isActionPending.value = false

            when (outcome) {
                is MarmotGroupCreation.Outcome.BlockedOn -> {
                    // Named rather than counted. A member cannot publish a key
                    // package on somebody else's behalf, so the only useful thing
                    // to say is whose door to knock on.
                    adminGroupBlockedOn.value = outcome.missing.map { publicKey ->
                        loaded.ritualMembers
                            .firstOrNull { it.participant.participantPublicKey == publicKey }
                            ?.profile
                            ?.humanReadableNameOrPubkey()
                            ?: publicKey.take(12)
                    }
                }

                is MarmotGroupCreation.Outcome.Failed -> {
                    dkgRitualUIState = DkgRitualUIState.Error(outcome.reason)
                }

                is MarmotGroupCreation.Outcome.Created -> withContext(Main) {
                    onNavigateToRoute(
                        ChatRoomMessagingRoute(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = outcome.room.chatRoom.id,
                            relayHint = null
                        )
                    )
                }

                is MarmotGroupCreation.Outcome.Existing -> withContext(Main) {
                    onNavigateToRoute(
                        ChatRoomMessagingRoute(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = outcome.room.chatRoom.id,
                            relayHint = null
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "DkgRitualViewModel"

        /** Matches SelectChatRoomTypeViewModel: relays are not always prompt. */
        private const val KEY_PACKAGE_LOOKUP_TIMEOUT = 10_000L

        fun factory(
            chatRoomId: String,
            activeUserPublicKey: HexKey,
            dkgSessionId: String,
            initialDkgRitualUIState: DkgRitualUIState = DkgRitualUIState.Loading,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            dkgRepository: DkgRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DkgRitualViewModel(
                    chatRoomId = chatRoomId,
                    activeUserPublicKey = activeUserPublicKey,
                    dkgSessionId = dkgSessionId,
                    initialDkgRitualUIState = initialDkgRitualUIState,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository
                )
            }
        }
    }
}
