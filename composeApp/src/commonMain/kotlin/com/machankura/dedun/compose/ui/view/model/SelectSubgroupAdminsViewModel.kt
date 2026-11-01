package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.ui.view.state.canGuard
import com.machankura.dedun.compose.ui.view.state.onlyGuarded
import com.machankura.dedun.compose.database.model.subgroupWalletPolicy
import com.machankura.dedun.compose.nostr.subgroup.isDecided
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.managers.MarmotGroupCreation
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.view.state.SelectSubgroupAdminsUIState

/**
 * Who runs a subgroup, what it is called, on what quorum, and whether its wallet
 * is guarded.
 *
 * All four are settled here and nowhere else. The wallet policy rides the
 * proposal like the threshold and is signed into the birth certificate by the
 * parent's quorum -- `docs/subgroup-wallet-policy.md`. The name and the admin set are
 * carried into the birth certificate, so they are what the parent's admins will
 * be asked to approve; the threshold cannot move afterwards at all, because
 * ChillDKG hashes it and the host keys into the session identity, so `t` is fixed
 * the moment the proposal goes out and a group that disagrees about it gets no
 * key rather than a weak one.
 *
 * It is also the one value picked *for* other people, and consent survives that:
 * `t` rides on the proposal, every invitee's `acceptProposal` re-checks it against
 * `quorumRange` and drops a proposal outside it, and the host-key approval gate is
 * where each of them agrees to the `t`-of-`n` they can now see.
 *
 * Confirming opens the ceremony -- a ritual proposal in the **parent's** room,
 * p-tagged to the picked admins -- and hands back to the parent's transcript,
 * where every admin answers the ceremony from the same place. See
 * `docs/subgroups.md`.
 */
class SelectSubgroupAdminsViewModel(
    val activeUserPublicKey: HexKey,
    val parentChatRoomId: String,
    /** A failed subgroup ceremony to start from; see `SelectSubgroupAdminsRoute.retrying`. */
    val retrying: String? = null,
    initialUIState: SelectSubgroupAdminsUIState,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val chatRepository: ChatRepository,
    val dkgRepository: DkgRepository,
): ViewModel() {

    var uiState: SelectSubgroupAdminsUIState by mutableStateOf(initialUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Members ticked, in the order they were ticked. The coordinator is never here. */
    val selectedPublicKeys = mutableStateListOf<HexKey>()

    /**
     * What the coordinator typed, bare.
     *
     * The `#` is drawn beside the field rather than pushed into this state:
     * normalising on every keystroke would move the caret out from under somebody
     * halfway through a word. It is added by `MarmotGroupName.of` where the
     * subgroup's name is actually used -- in the certificate the parent signs and
     * on the room itself -- which is idempotent, so the two agree.
     */
    val name: MutableState<String> = mutableStateOf("")

    val threshold: MutableState<Int> = mutableStateOf(ChatRoomType.MINIMUM_QUORUM)

    /**
     * What the subgroup's wallet is to be -- `docs/subgroup-wallet-policy.md`.
     * Guarded unless the coordinator says otherwise, and only ever guarded under a
     * parent that is guarded itself: [setWalletPolicy] will not move it, so the
     * state never holds a choice the screen shows as disabled.
     */
    val walletPolicy: MutableState<SubgroupWalletPolicy> = mutableStateOf(SubgroupWalletPolicy.GUARDED)

    /**
     * How long a coin in a guarded subgroup's wallet waits before this group can
     * take it -- `docs/guardian-delay.md`. The parent's own leaf only: each
     * ancestor above keeps the delay it chose.
     */
    private val delay = GuardianDelayInput()

    /** The delay as typed; see [GuardianDelayInput]. */
    val guardianDelayInput: MutableState<String> get() = delay.input

    /** Whether the delay is being typed rather than picked from a preset. */
    val isCustomDelay: MutableState<Boolean> get() = delay.isCustom

    /** Whether [retrying] has been read into the form, which happens once. */
    private var prefilled = false

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val parentRoom = chatRepository.getChatRoomByIdentifier(parentChatRoomId)
            if (parentRoom == null) {
                uiState = SelectSubgroupAdminsUIState.Error("Couldn't load this group")
                return@launch
            }

            // Everybody but this device, since the coordinator is a participant by
            // construction and is not theirs to tick or untick.
            val candidates = parentRoom.localParticipants
                .distinctBy { it.participant.participantPublicKey }
                .filterNot { it.participant.participantPublicKey == activeUserPublicKey }

            // The parent's own wallet policy and guardians, read once: whether it
            // may make an unguarded child at all, and whether it can grant a
            // guarded one anything.
            val parentPolicy = chatRepository.walletPolicyOf(parentChatRoomId)
            val parentGuardians = chatRepository.guardiansOf(parentChatRoomId)

            uiState = SelectSubgroupAdminsUIState.Loaded(
                parentRoom = parentRoom,
                candidates = candidates,
                parentPolicy = parentPolicy,
                parentGuardians = parentGuardians,
            )

            // A parent that cannot grant keys can only make an unguarded child,
            // and one that is guarded can only make a guarded one.
            if (parentGuardians == null) walletPolicy.value = SubgroupWalletPolicy.UNGUARDED
            if (parentPolicy.isDecided(SubgroupWalletPolicy.GUARDED)) walletPolicy.value = SubgroupWalletPolicy.GUARDED

            // Before the key-package lookup, so anybody who can no longer be
            // welcomed is unpicked by the same rule as a fresh pick would be.
            prefillFromRetry(candidates.map { it.participant.participantPublicKey }.toSet())

            resolveKeyPackages(candidates.map { it.participant.participantPublicKey })
        }
    }

    /**
     * Starts the form from the failed ceremony this is trying again.
     *
     * Only from one that was this parent's subgroup, and only admins who are
     * still in the parent: the ceremony recorded who it was with, and the
     * parent's membership may have moved since. Once, because the screen asks
     * to start again every time it comes back into view and a member's edits
     * must not be put back.
     */
    private suspend fun prefillFromRetry(candidates: Set<HexKey>) {
        val sessionId = retrying ?: return
        if (prefilled) return
        prefilled = true

        val failed = dkgRepository.observeSessionById(sessionId).first() ?: return
        if (failed.parentChatRoomId != parentChatRoomId) return

        name.value = failed.subject.orEmpty()

        selectedPublicKeys.clear()
        selectedPublicKeys.addAll(
            failed.participantPublicKeySet().orEmpty()
                .filter { it != activeUserPublicKey && it in candidates }
        )

        threshold.value = failed.threshold.coerceIn(quorumRange())

        // Through the setter, so a policy the parent no longer allows -- the
        // failed ceremony can predate the parent's own certificate -- is pulled
        // back the way the threshold is pulled into its range.
        failed.subgroupWalletPolicy?.let(::setWalletPolicy)
        failed.guardianDelay?.let(delay::reset)
    }

    /**
     * Asks every candidate's key package up front.
     *
     * The same move `SelectChatRoomMembersViewModel` makes for group creation, and
     * here it is doing more than saving a round trip. A key package is
     * one-time-use -- `NostrDao` marks the bundle consumed as the device processes
     * its own Welcome -- so every group a member joins burns one, and a member who
     * has not published since has none left. `MarmotGroupCreation` would refuse to
     * create the room for that, correctly, but only after a ChillDKG, a parent
     * quorum and a child quorum had all completed, each needing every selected
     * admin present. Finding out here costs nothing and finding out there costs
     * three ceremonies.
     */
    private suspend fun resolveKeyPackages(publicKeys: List<HexKey>) {
        val resolved = MarmotGroupCreation.keyPackagesFor(chatRepository, publicKeys)
        val missing = resolved.filter { it.second == null }.map { it.first }.toSet()

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return
        uiState = loaded.copy(withoutKeyPackage = missing, isResolvingKeyPackages = false)

        // A member who cannot be welcomed cannot be an admin of a room they are not
        // in, so unpick them rather than leaving a selection that confirm will
        // refuse.
        selectedPublicKeys.removeAll(missing)
    }

    fun toggleMember(publicKey: HexKey) {
        if (isActionPending.value) return

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return
        if (publicKey in loaded.withoutKeyPackage) return

        if (!selectedPublicKeys.remove(publicKey)) {
            selectedPublicKeys.add(publicKey)
        }

        // The range moves with the admin count, so a threshold picked at one size
        // has to be pulled back into the range at another.
        threshold.value = threshold.value.coerceIn(quorumRange())
    }

    fun isSelected(publicKey: HexKey): Boolean = selectedPublicKeys.contains(publicKey)

    fun setName(value: String) {
        if (isActionPending.value) return

        name.value = value
    }

    /**
     * Chooses the subgroup's wallet policy. *Unguarded* under a guarded parent is
     * ignored -- D4 -- and so is *guarded* under a parent that cannot grant keys.
     */
    fun setWalletPolicy(value: SubgroupWalletPolicy) {
        if (isActionPending.value) return

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return
        if (value == SubgroupWalletPolicy.UNGUARDED && loaded.onlyGuarded) return
        if (value == SubgroupWalletPolicy.GUARDED && !loaded.canGuard) return

        walletPolicy.value = value
    }

    /** Picks a preset delay. */
    fun setGuardianDelay(blocks: Long) {
        if (isActionPending.value) return
        delay.pick(blocks)
    }

    /** Switches to typing a delay, starting from the one already chosen. */
    fun useCustomDelay() {
        if (isActionPending.value) return
        delay.useCustom()
    }

    /** What was typed as the delay, kept as typed. */
    fun setGuardianDelayInput(value: String) {
        if (isActionPending.value) return
        delay.type(value)
    }

    /** The delay typed or picked, or null while the field holds no number. */
    fun guardianDelay(): Long? = delay.delay()

    /** Why the delay cannot be used, or null when it can. */
    fun guardianDelayProblem(): String? = delay.problem()

    /** The subgroup's admins: everyone ticked, plus the coordinator. */
    fun adminCount(): Int = selectedPublicKeys.size + 1

    fun quorumRange(): IntRange =
        ChatRoomType.quorumRange(maxOf(adminCount(), ChatRoomType.MINIMUM_QUORUM))

    fun setThreshold(value: Int) {
        if (isActionPending.value) return

        threshold.value = value.coerceIn(quorumRange())
    }

    /**
     * Why this cannot be confirmed yet, or null when it can.
     *
     * Every message names what to do rather than what is wrong, because each of
     * these is something the user can fix from where they are standing.
     */
    suspend fun refusal(): String? {
        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return "Still loading"

        if (name.value.isBlank()) return "Give the subgroup a name."

        // The policy before the admins and before the key-package lookup, which it
        // does not wait on: an answer here sends the coordinator back
        // to a choice, not to a person. Checked again on confirm, so a state that
        // got past the screen is stopped before a ceremony is proposed.
        if (walletPolicy.value == SubgroupWalletPolicy.UNGUARDED && loaded.onlyGuarded) {
            return "This group is guarded, so its subgroups are too."
        }
        if (walletPolicy.value == SubgroupWalletPolicy.GUARDED && !loaded.canGuard) {
            return "This group's own guardian keys can't be read, so it can't guard a subgroup."
        }
        if (walletPolicy.value == SubgroupWalletPolicy.GUARDED) guardianDelayProblem()?.let { return it }


        if (loaded.isResolvingKeyPackages) return "Checking who can be added…"

        val unreachable = selectedPublicKeys.filter { it in loaded.withoutKeyPackage }
        if (unreachable.isNotEmpty()) {
            return "Some of the people picked have no key package. Ask them to " +
                "publish one, then try again."
        }

        return chatRepository.refuseSubgroup(
            parentChatRoomId = parentChatRoomId,
            adminPublicKeys = selectedPublicKeys.toSet(),
            coordinatorPublicKey = activeUserPublicKey
        )
    }

    /**
     * Opens the ceremony in the parent's room and hands back to its transcript.
     *
     * No sibling room is made. The ceremony runs where the parent's members
     * already are: the proposal names the picked admins in its p-tags, which is
     * the participant set every device derives `n` from, and a parent member who
     * is not in it drops the proposal rather than joining. It carries the name
     * and the parent for the same reason it always did -- the child has no room
     * of its own to read either off until the ceremony has produced its key.
     */
    fun confirm(onNavigateToRoute: (Route) -> Unit) {
        if (isActionPending.value) return

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return

        val nostrPrivateKey = activeIdentityStateFlow.value?.nostrPrivateKey
        if (nostrPrivateKey == null) {
            uiState = SelectSubgroupAdminsUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // Checked again here rather than trusted from the button: a screen not
            // drawing something is not a guard, and the key-package answer can go
            // stale while the screen is open.
            val refusal = refusal()
            if (refusal != null) {
                isActionPending.value = false
                uiState = SelectSubgroupAdminsUIState.Error(refusal)
                return@launch
            }

            // The bare name goes on the ceremony, and the `#` is added where the
            // name is used: `proposeBirthCertificate` normalises the name the
            // parent signs and `MarmotGroupCreation` normalises the name the room
            // carries, both reading it back off this session.
            // `MarmotGroupName.of` is idempotent, so those are the same string --
            // which they have to be, or the room is not called what its parent
            // certified.
            //
            // Carried on the session rather than on a room, because there is no
            // longer a room to carry it: the ceremony is held in the parent's,
            // and the parent's name is the one name a child must not take.
            val session = dkgRepository.proposeRitual(
                localChatRoom = loaded.parentRoom,
                userPublicKey = activeUserPublicKey,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray(),
                threshold = threshold.value,
                parentChatRoomId = parentChatRoomId,
                // The admins, and only them. The room is the parent's, so the
                // ceremony has to say who it is with -- this is the set that
                // becomes the proposal's p-tags and every device's `n`. The
                // coordinator is added by `proposeRitual`: opening a ceremony is
                // being in it, and is not theirs to tick.
                participantPublicKeys = selectedPublicKeys.toSet(),
                subject = name.value.trim(),
                // Carried on the proposal so every admin is told at the join gate,
                // and on the session so whoever proposes the certificate proposes
                // it under the same one.
                walletPolicy = walletPolicy.value,
                // The parent's own leaf, and only for a guarded subgroup.
                // docs/guardian-delay.md.
                guardianDelay = guardianDelay().takeIf { walletPolicy.value == SubgroupWalletPolicy.GUARDED }
            )

            isActionPending.value = false

            if (session == null) {
                logger.e("Could not open a subgroup ceremony in $parentChatRoomId")
                uiState = SelectSubgroupAdminsUIState.Error(
                    "Couldn't start the subgroup's key ceremony. Please try again."
                )
                return@launch
            }

            withContext(Dispatchers.Main) {
                // The parent's transcript, not the shared-key screen. The ceremony
                // is already open and its first line is already there, so what the
                // coordinator needs is to watch it and answer the requests as they
                // arrive -- which is now literally the same place every other
                // admin answers from, through the ritual notices that name their
                // own ceremony.
                onNavigateToRoute(
                    ChatRoomMessagingRoute(
                        activeUserPublicKey = activeUserPublicKey,
                        chatRoomId = parentChatRoomId,
                        relayHint = null
                    )
                )
            }
        }
    }

    companion object {
        /** The delays the picker offers: [GuardianDelayInput.PRESETS]. */
        val DELAY_PRESETS: List<Long> get() = GuardianDelayInput.PRESETS

        private const val TAG = "SelectSubgroupAdminsViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            parentChatRoomId: String,
            retrying: String? = null,
            initialUIState: SelectSubgroupAdminsUIState = SelectSubgroupAdminsUIState.Loading,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            dkgRepository: DkgRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SelectSubgroupAdminsViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    parentChatRoomId = parentChatRoomId,
                    retrying = retrying,
                    initialUIState = initialUIState,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository
                )
            }
        }
    }
}
