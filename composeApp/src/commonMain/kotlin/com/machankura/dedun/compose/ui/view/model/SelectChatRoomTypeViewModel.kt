package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.nostr.MarmotProfile
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.MarmotGroupFactory
import com.machankura.dedun.compose.managers.MarmotGroupName
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.view.state.SelectChatRoomTypeUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.utils.RandomInstance
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Last step of group creation: how the group should be run, and then actually
 * building it.
 *
 * The choice is not cosmetic — it decides who lands in the epoch-0
 * `admin_pubkeys` list, so the group can only be minted once it has been made.
 */
class SelectChatRoomTypeViewModel(
    val activeUserPublicKey: HexKey,
    val name: String,
    val description: String?,
    val memberPublicKeys: List<HexKey>,
    initialSelectChatRoomTypeUIState: SelectChatRoomTypeUIState,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    val dkgRepository: DkgRepository,
): ViewModel() {

    var selectChatRoomTypeUIState: SelectChatRoomTypeUIState by mutableStateOf(initialSelectChatRoomTypeUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Convenient until the user says otherwise: it is the choice that always works. */
    val selectedChatRoomType: MutableState<ChatRoomType> = mutableStateOf(ChatRoomType.CONVENIENT)

    /**
     * Set as soon as the room exists. Creating a group is not idempotent, so a second
     * tap after a partially failed invite round has to reuse this room rather than
     * mint another one.
     */
    val createdChatRoomId: MutableState<String?> = mutableStateOf(null)

    /** Members the group was created without, because no key package ever showed up. */
    val membersNotAdded = mutableStateListOf<HexKey>()

    /**
     * Set when a [ChatRoomType.ROBUST] room was made but its key ceremony was not.
     *
     * The room is real and usable either way -- only the shared key is missing, and
     * it can be started again from the group's details. Worth saying rather than
     * navigating on, because the key is the whole of what robust means.
     */
    val keyCeremonyNotStarted: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Everyone who administers the group under [ChatRoomType.ROBUST]: the picked
     * members plus the creator.
     */
    val adminCount: Int = (memberPublicKeys + activeUserPublicKey).distinct().size

    /** Quorums this group can be run on, under [ChatRoomType.ROBUST]. */
    val quorumRange: IntRange = ChatRoomType.quorumRange(adminCount)

    /**
     * How many of [adminCount] admins have to approve a change, under
     * [ChatRoomType.ROBUST]. Starts on a simple majority and is the user's to move.
     */
    val quorum: MutableState<Int> = mutableStateOf(ChatRoomType.defaultQuorum(adminCount))

    /** Whether this group is big enough to be run as [ChatRoomType.ROBUST]. */
    val isRobustAvailable: Boolean = ChatRoomType.isRobustAvailable(adminCount)

    /** Why robust is off the table, or null when it is available. */
    val robustUnavailableReason: String? = if (isRobustAvailable) {
        null
    } else {
        val shortfall = ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE - adminCount
        val missing = if (shortfall == 1) "1 more person" else "$shortfall more people"

        "Needs at least ${ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE} people in the group. Go back and add $missing."
    }

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val members = memberPublicKeys.mapNotNull { publicKey ->
                nostrRepository.getProfileWithPublicKey(publicKey)
            }

            selectChatRoomTypeUIState = SelectChatRoomTypeUIState.Loaded(
                members = members
            )
        }
    }

    fun selectChatRoomType(chatRoomType: ChatRoomType) {
        // Once the room exists the choice is baked into its epoch-0 group context and
        // re-picking here would change nothing.
        if (isActionPending.value || createdChatRoomId.value != null) return

        // Belt and braces alongside the disabled card: a group this small cannot carry
        // a meaningful threshold, so it never becomes robust.
        if (chatRoomType == ChatRoomType.ROBUST && !isRobustAvailable) return

        selectedChatRoomType.value = chatRoomType
    }

    fun setQuorum(value: Int) {
        // Same freeze as the type itself: past creation the group's governance is
        // already stamped into its epoch-0 context.
        if (isActionPending.value || createdChatRoomId.value != null) return

        quorum.value = value.coerceIn(quorumRange)
    }

    /** Spells out what the chosen quorum costs the group day to day. */
    fun quorumExplanation(): String {
        val chosenQuorum = quorum.value

        return if (chosenQuorum == adminCount) {
            "Every admin has to agree. If one of you goes quiet, nothing about the group can change."
        } else {
            "Any $chosenQuorum of you can approve a change — the other ${adminCount - chosenQuorum} don't have to be around."
        }
    }

    /** Names the member behind [publicKey], falling back to the key itself. */
    fun displayNameFor(publicKey: HexKey): String {
        val members = (selectChatRoomTypeUIState as? SelectChatRoomTypeUIState.Loaded)?.members

        return members?.firstOrNull { it.publicKey == publicKey }?.humanReadableNameOrPubkey()
            ?: publicKey.take(SHORTENED_PUBLIC_KEY_LENGTH)
    }

    fun createChatRoom(
        onNavigateToRoute: (Route) -> Unit
    ) {
        if (isActionPending.value) return

        // The room survived a partial invite round — the user has read who was left out
        // and just wants to get on with the chat.
        createdChatRoomId.value?.let { chatRoomId ->
            onNavigateToRoute.invoke(
                ChatRoomMessagingRoute(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = null
                )
            )
            return
        }

        val nostrPrivateKey = activeIdentityStateFlow.value?.nostrPrivateKey
        if (nostrPrivateKey == null) {
            selectChatRoomTypeUIState = SelectChatRoomTypeUIState.Error(
                "Couldn't read your keys. Please try again."
            )
            return
        }

        isActionPending.value = true

        val nostrPrivateKeyBytes = nostrPrivateKey.value.toByteArray()
        val keyPair = KeyPair(
            privKey = nostrPrivateKeyBytes
        )

        when (selectedChatRoomType.value) {
            ChatRoomType.CONVENIENT -> createMarmotChatRoom(keyPair, onNavigateToRoute)
            // The ceremony needs the secret itself, not the pair: the ChillDKG host
            // key is derived from it rather than being it.
            ChatRoomType.ROBUST -> createNip17ChatRoom(keyPair, nostrPrivateKeyBytes, onNavigateToRoute)
        }
    }

    /**
     * Convenient rooms are Marmot/MLS: one admin, forward secrecy, and members added
     * by commit — which is why every invite needs the peer's key package first.
     */
    private fun createMarmotChatRoom(
        keyPair: KeyPair,
        onNavigateToRoute: (Route) -> Unit
    ) {
        val gid = RandomInstance.bytes(32).toHexKey() // TODO: Generate GID through frost...

        // Every Marmot room is a `#name`. This one does not go through
        // `MarmotGroupCreation` -- its id is random rather than derived, so it has
        // no key state to adopt and no admin set to bake in -- which is why the
        // convention is applied here as well as there.
        val groupName = MarmotGroupName.of(name)

        viewModelScope.launch(Dispatchers.IO) {
            // The group's initial state is baked into the epoch-0 GroupContext so later
            // invitees receive a pre-populated group from the Welcome and never have to
            // chase an undecryptable bootstrap commit that predates their membership.
            // Which profile that state is on is the factory's decision, not this
            // screen's -- see MarmotGroupFactory.
            val created = MarmotGroupFactory.create(
                creator = keyPair,
                roomId = gid,
                name = groupName,
                description = description,
                adminPublicKeys = listOf(keyPair.pubKey.toHexKey()),
                relays = Relays.DefaultDMRelayList.map { it.url },
            )
            val group = created.group

            val localChatRoom = chatRepository.getOrCreateChatRoom(
                // Key the room on its Marmot id, NOT on `MlsGroup`'s own randomly
                // generated groupId — they are unrelated 32-byte values. Every inbound
                // path (and the invitee's own join) resolves rooms to this id, through
                // the routing address recorded below.
                chatRoomId = gid,
                activeUserPublicKey = keyPair.pubKey.toHexKey(),
                relayHint = null,
                defaultSubject = groupName,
                description = description,
                mlsGroupState = group.saveState().encodeTls().toHex()
            )
            if (localChatRoom != null) {
                chatRepository.recordMarmotRouting(chatRoomId = gid, routingId = created.routingId)
            }

            if (localChatRoom == null) {
                withContext(Dispatchers.Main) {
                    isActionPending.value = false
                    onNavigateToRoute.invoke(
                        ImplementationPendingRoute("Something went wrong")
                    )
                }
                return@launch
            }

            createdChatRoomId.value = localChatRoom.chatRoom.id

            val notAdded = inviteMembers(chatRoomId = localChatRoom.chatRoom.id)

            withContext(Dispatchers.Main) {
                isActionPending.value = false

                if (notAdded.isEmpty()) {
                    onNavigateToRoute.invoke(
                        ChatRoomMessagingRoute(
                            activeUserPublicKey = keyPair.pubKey.toHexKey(),
                            chatRoomId = localChatRoom.chatRoom.id,
                            relayHint = null
                        )
                    )
                } else {
                    // The group is real and usable without them; say who is missing instead
                    // of dropping the user into a chat that is quietly short a few people.
                    membersNotAdded.clear()
                    membersNotAdded.addAll(notAdded)
                }
            }
        }
    }

    /**
     * Robust rooms are NIP-17: no MLS group, no admin, no privileged member. Membership
     * is simply the p-tag set every message carries, which is why there is nothing to
     * invite anyone to and no key package to wait on — everybody picked is in the room
     * the moment it exists, and learns about it from the first message.
     *
     * That first message is the key ceremony, opened here rather than left for somebody
     * to find a button for.
     *
     * NIP-17 itself has nothing for the quorum to govern — no group state to change, and
     * a different member set is simply a different room — so the only thing that can
     * carry it is a key the group generates together and can only sign with t of n of
     * them present. Everything that ceremony needs is settled by the time the room
     * exists: who is in it, and how many of them have to agree. Waiting would mean
     * handing the user the room they asked for minus the thing that makes it robust.
     *
     * Opening it is also how the rest of the group hears of the room at all. Standing up
     * a NIP-17 room sends nothing to anybody; the proposal is the first event out, and
     * the inbound path builds the same room on the other side from its p-tags.
     */
    private fun createNip17ChatRoom(
        keyPair: KeyPair,
        nostrPrivateKey: ByteArray,
        onNavigateToRoute: (Route) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.createNip17ChatRoom(
                userPublicKey = keyPair.pubKey.toHexKey(),
                participantPublicKeys = memberPublicKeys,
                subject = name,
                description = description
            )

            if (localChatRoom == null) {
                withContext(Dispatchers.Main) {
                    isActionPending.value = false
                    onNavigateToRoute.invoke(
                        ImplementationPendingRoute("Something went wrong")
                    )
                }
                return@launch
            }

            createdChatRoomId.value = localChatRoom.chatRoom.id

            // The room's membership is the set this screen was opened for, so the
            // quorum picked against [adminCount] is the same t-of-n the ceremony is
            // asked for. A room that already has a ceremony -- the id is derived from
            // its members, so making the same group twice returns the same room --
            // hands that one back instead of opening a second.
            val session = dkgRepository.proposeRitual(
                localChatRoom = localChatRoom,
                userPublicKey = keyPair.pubKey.toHexKey(),
                nostrPrivateKey = nostrPrivateKey,
                threshold = quorum.value
            )

            withContext(Dispatchers.Main) {
                isActionPending.value = false

                if (session == null) {
                    // Nothing is rolled back: the room works, the group can talk in it,
                    // and the ceremony can be opened again from the group's details.
                    // Said here rather than navigated past, because a robust group
                    // without a shared key is not what the user asked for.
                    logger.e("Created ${localChatRoom.chatRoom.id} without a key ceremony")
                    keyCeremonyNotStarted.value = true
                    return@withContext
                }

                onNavigateToRoute.invoke(
                    ChatRoomMessagingRoute(
                        activeUserPublicKey = keyPair.pubKey.toHexKey(),
                        chatRoomId = localChatRoom.chatRoom.id,
                        relayHint = null
                    )
                )
            }
        }
    }

    /**
     * Adds every picked member to the freshly created group and returns the ones that
     * could not be added.
     */
    private suspend fun inviteMembers(chatRoomId: String): List<HexKey> {
        if (memberPublicKeys.isEmpty()) return emptyList()

        // Resolve the key packages concurrently so the whole batch costs one relay round
        // trip rather than one per member.
        val keyPackages = coroutineScope {
            memberPublicKeys.map { publicKey ->
                async {
                    publicKey to withTimeoutOrNull(KEY_PACKAGE_LOOKUP_TIMEOUT) {
                        chatRepository.observeMarmotKeyPackageForPublicKey(publicKey, MarmotProfile.DEFAULT_FOR_NEW_ROOMS)
                            .filterNotNull()
                            .first()
                    }
                }
            }.awaitAll()
        }

        val withoutKeyPackage = keyPackages.filter { it.second == null }.map { it.first }
        withoutKeyPackage.forEach { logger.w("No key package for $it; leaving them out of $chatRoomId") }

        val addable = keyPackages.mapNotNull { (publicKey, keyPackage) ->
            keyPackage?.let { publicKey to it }
        }
        if (addable.isEmpty()) return withoutKeyPackage

        // One commit for the whole membership rather than one per member. Inviting
        // sequentially creates an epoch each, and each commit races the previous
        // member's Welcome -- a race MarmotInboundManager resolves by dropping the
        // commit outright, leaving that member an epoch behind while this reports
        // success. See docs/marmot-membership.md.
        val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
        if (localChatRoom == null) {
            logger.e("Chat room $chatRoomId disappeared before its members were added")
            return memberPublicKeys
        }

        val notAdded = runCatching {
            chatRepository.addMembers(localChatRoom = localChatRoom, peers = addable)
        }.onFailure {
            logger.e("Failed to add members to $chatRoomId", it)
        }.getOrElse { addable.map { (publicKey, _) -> publicKey } }

        return withoutKeyPackage + notAdded
    }

    companion object {
        private const val TAG = "SelectChatRoomTypeViewModel"

        /** Shared budget for pulling every picked member's key package off the relays. */
        private val KEY_PACKAGE_LOOKUP_TIMEOUT = 20.seconds

        /** Enough of a pubkey to tell two members apart when no profile is stored. */
        private const val SHORTENED_PUBLIC_KEY_LENGTH = 12

        fun factory(
            activeUserPublicKey: HexKey,
            name: String,
            description: String?,
            memberPublicKeys: List<HexKey>,
            initialSelectChatRoomTypeUIState: SelectChatRoomTypeUIState = SelectChatRoomTypeUIState.Loading,
            activeIdentityStateFlow: StateFlow<Identity?>,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            dkgRepository: DkgRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SelectChatRoomTypeViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    name = name,
                    description = description,
                    memberPublicKeys = memberPublicKeys,
                    initialSelectChatRoomTypeUIState = initialSelectChatRoomTypeUIState,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository
                )
            }
        }
    }
}
