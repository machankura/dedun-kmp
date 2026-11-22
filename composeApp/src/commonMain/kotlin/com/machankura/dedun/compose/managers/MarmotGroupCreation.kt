package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.mls.group.MlsGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository

/**
 * Creating a Marmot room at an id the group's key derives, once, for every flow
 * that needs one.
 *
 * Two do: the admin room a group opens after its own ceremony, and a subgroup,
 * which is the same act with four different values. This was 120 lines inside
 * `DkgRitualViewModel` and is here unchanged in behaviour, because the rules it
 * already gets right are not rules worth deriving twice.
 *
 * ### The three that are not obvious
 *
 * **Every key package before anything exists.** The id is derived, so there is
 * exactly one room per key at a path; a half-created one occupies that address
 * permanently and there is no second id to retry with. Better to create nothing
 * and name who is missing -- and better still to have checked at the picker, so
 * that a subgroup does not discover it after three ceremonies. See
 * `docs/subgroups.md`.
 *
 * **`adminPubkeys` baked into the epoch-0 group context**, rather than added by a
 * later commit, so a member welcomed afterwards gets a populated group instead of
 * chasing a bootstrap commit that predates their membership. That is why
 * `MarmotGroupData` is built here rather than through `MarmotGroupData.bootstrap`,
 * which hardcodes a single admin.
 *
 * **[GroupKeyStateManager.adopt] before the members are added.** Filing the key
 * state is local and certain; adding members is a relay round trip that can
 * partly fail. Doing it first means the room comes into existence already knowing
 * what it signs with, whatever happens next.
 *
 * ### Derived ids make every step reachable twice
 *
 * A second tap, or another member having got there first, lands on the same id.
 * Joining what exists beats minting a rival group on one address, so [Existing]
 * is a success rather than a refusal.
 */
object MarmotGroupCreation {
    private const val TAG = "MarmotGroupCreation"

    private val logger = Logger.withTag(TAG)

    /** Matches `SelectChatRoomTypeViewModel`: relays are not always prompt. */
    const val KEY_PACKAGE_LOOKUP_TIMEOUT: Long = 10_000L

    sealed interface Outcome {
        /**
         * The room was created. [notAdded] are the members whose invite failed
         * after it existed -- a room with most of the group in it is more useful
         * than no room, and the rest can be invited again.
         */
        data class Created(val room: LocalChatRoom, val notAdded: List<HexKey>) : Outcome

        /** The room was already here. Every path into one ends the same way. */
        data class Existing(val room: LocalChatRoom) : Outcome

        /**
         * Nothing was created, because [missing] have no published key package.
         *
         * The one refusal that is not a failure: creating the room anyway would
         * list an admin who is not in the MLS tree, in a group that disagrees with
         * itself from its first epoch, at an address nothing can replace.
         */
        data class BlockedOn(val missing: List<HexKey>) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * Creates the room [groupId] with [adminPublicKeys] as its admins.
     *
     * [purpose] is the human half of the description; the derivation path is
     * appended to it, because MIP-01 has no field for one and the path is what
     * rebuilds the `TweakCache` a signing session needs.
     *
     * [parentChatRoomId] is written onto the room when this is a subgroup. It is
     * the verified value off the child's own key state -- never a claim off a
     * proposal.
     *
     * [name] is put under [MarmotGroupName] here rather than trusted from the
     * caller. Every Marmot room on a device is an `&name`, and this is the funnel
     * both flows that make one already go through, so it is the one place the
     * convention cannot be forgotten.
     */
    suspend fun create(
        database: DedunDatabase,
        chatRepository: ChatRepository,
        groupId: String,
        name: String,
        purpose: String,
        adminPublicKeys: Set<HexKey>,
        userPublicKey: HexKey,
        keyPair: KeyPair,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
        parentChatRoomId: HexKey? = null,
        keyPackageLookupTimeout: Long = KEY_PACKAGE_LOOKUP_TIMEOUT,
        profile: MarmotProfile = MarmotProfile.DEFAULT_FOR_NEW_ROOMS,
    ): Outcome {
        chatRepository.getChatRoomByIdentifier(groupId)?.let { return Outcome.Existing(it) }

        val groupName = MarmotGroupName.of(name)

        val peers = adminPublicKeys.filterNot { it == userPublicKey }
        val keyPackages = keyPackagesFor(chatRepository, peers, keyPackageLookupTimeout, profile)

        val missing = keyPackages.filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            logger.w("Not creating $groupId: no key package for $missing")
            return Outcome.BlockedOn(missing)
        }

        val addable = keyPackages.mapNotNull { (publicKey, keyPackage) ->
            keyPackage?.let { publicKey to it }
        }

        val description = SharedKeyDerivation.describe(purpose = purpose, path = path)

        // On whichever profile new rooms are on. The room id is the derived key
        // either way; on the current profile the group's routing address is minted
        // apart from it and the id rides in the description, so a joiner keys the
        // room as this device does. See MarmotGroupFactory and MarmotRoomIdentity.
        val created = MarmotGroupFactory.create(
            creator = keyPair,
            roomId = groupId,
            name = groupName,
            description = description,
            adminPublicKeys = adminPublicKeys,
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = profile,
        )
        val group = created.group

        // Keyed on the room id, not MlsGroup's own groupId and not the routing
        // address: they are unrelated 32-byte values, and every inbound path resolves
        // rooms to this one.
        val localChatRoom = chatRepository.getOrCreateChatRoom(
            chatRoomId = groupId,
            activeUserPublicKey = userPublicKey,
            relayHint = null,
            defaultSubject = groupName,
            description = description,
            mlsGroupState = group.saveState().encodeTls().toHex()
        ) ?: return Outcome.Failed("Couldn't create the group. Please try again.")

        chatRepository.recordMarmotRouting(chatRoomId = groupId, routingId = created.routingId)

        if (parentChatRoomId != null) {
            // Written after the room exists rather than passed into its creation,
            // because `getOrCreateChatRoom` is shared with every other way a room
            // appears and none of them has a parent to hand it.
            database.chatRoomDao().upsert(
                localChatRoom.chatRoom.copy(parentChatRoomId = parentChatRoomId)
            )
        }

        // The room comes into existence already knowing what it signs with. The
        // group agreed that before any of this ran and this device has been
        // holding the signed statement since; this is simply the first moment
        // there is a row to file it against.
        GroupKeyStateManager.adopt(database, groupId)

        // Read again now the routing is on file. The row above predates it, and a
        // room with no routing row is addressed by its own id -- so on the current
        // profile the invite went to a second copy of the group the engine adopted
        // under the room id, and the copy at the real address still held only its
        // creator. Every later invite then took the founding path: no commit, and
        // nobody already in the room told. docs/robust-group-admins.md, Phase 4.
        val routedRoom = chatRepository.getChatRoomByIdentifier(groupId) ?: localChatRoom

        val notAdded = runCatching {
            chatRepository.addMembers(localChatRoom = routedRoom, peers = addable)
        }.onFailure {
            logger.e("Failed to add members to $groupId", it)
        }.getOrElse { addable.map { (publicKey, _) -> publicKey } }

        if (notAdded.isNotEmpty()) {
            logger.w("$groupId created without ${notAdded.size} member(s): $notAdded")
        }

        flagAdmins(database, groupId)

        return Outcome.Created(
            room = chatRepository.getChatRoomByIdentifier(groupId) ?: localChatRoom,
            notAdded = notAdded
        )
    }

    /**
     * Creates the room [ceremony]'s key derives, with the ceremony's participants
     * as its admins.
     *
     * The only way the app makes such a room, and the reason [create] takes a list
     * of admins nobody outside this file supplies. The founding members of a
     * robust group are the participants of the ceremony that made its key --
     * every one of their devices built that set from the proposal it agreed to --
     * and a room's epoch-0 admin list is fixed for good at an address derived from
     * the key. Two callers computing it was two chances to compute it differently.
     * See docs/robust-group-admins.md, D1 and Phase 1.
     *
     * The room's id, name and description come off the ceremony too, so the
     * coordinator's device creating it on its own and a member pressing the
     * button make the same room.
     *
     * [ceremonyRoom] is the room the ceremony ran in: the participant set falls
     * back to its members for a ceremony that predates its own participant list,
     * as [ChillDkgRitualManager.participantsOf] does everywhere.
     *
     * A device that was not in the ceremony is refused. It holds no key state for
     * the room and no share of the key, and listing itself as the room's
     * creator would put an admin into the room the group never chose.
     */
    suspend fun createFor(
        database: DedunDatabase,
        chatRepository: ChatRepository,
        ceremony: DkgSession,
        ceremonyRoom: LocalChatRoom,
        keyPair: KeyPair,
        parentChatRoomId: HexKey? = null,
        keyPackageLookupTimeout: Long = KEY_PACKAGE_LOOKUP_TIMEOUT,
        profile: MarmotProfile = MarmotProfile.DEFAULT_FOR_NEW_ROOMS,
    ): Outcome {
        val thresholdPublicKey = ceremony.thresholdPublicKey
            ?: return Outcome.Failed("This ceremony has no key yet.")

        val founders = ChillDkgRitualManager.participantsOf(ceremony, ceremonyRoom)
        if (ceremony.participantPublicKeySet() == null) {
            logger.w("Ceremony ${ceremony.id} predates its participant list; its room's members are its founders")
        }

        if (ceremony.userPublicKey !in founders) {
            logger.e("Not creating the room for ${ceremony.id}: ${ceremony.userPublicKey} was not in it")
            return Outcome.Failed("Only someone who took part in the key ceremony can create its room.")
        }

        return create(
            database = database,
            chatRepository = chatRepository,
            groupId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey),
            name = CeremonyFollowThrough.roomName(ceremony),
            purpose = CeremonyFollowThrough.roomPurpose(ceremony),
            adminPublicKeys = founders,
            userPublicKey = ceremony.userPublicKey,
            keyPair = keyPair,
            parentChatRoomId = parentChatRoomId,
            keyPackageLookupTimeout = keyPackageLookupTimeout,
            profile = profile,
        )
    }

    /** What [finishFoundingInvite] did. */
    sealed interface FoundingInvite {
        /** Every founding member holds a leaf, or the room is past its founding. */
        data object NothingToDo : FoundingInvite

        /** The founders who were missing were invited; [notAdded] could not be. */
        data class Sent(val chatRoomId: String, val invited: List<HexKey>, val notAdded: List<HexKey>) : FoundingInvite

        /** Some founders have no published key package, so nobody was invited. */
        data class BlockedOn(val missing: List<HexKey>) : FoundingInvite

        data class Failed(val reason: String) : FoundingInvite
    }

    /**
     * The founding members of [room] its founding invite never reached: listed as
     * admins, holding no leaf, in a room nothing has been committed to since it was
     * made.
     *
     * Only at epoch 0. A room is created at epoch 0 and its founding invite is the
     * commit that leaves it -- so a room still there is one whose invite failed
     * outright, and nobody in it has left or been removed, because either would
     * have been a commit. Past epoch 0 a founder without a leaf might be one who
     * left, and inviting them back is not this function's to decide
     * (docs/robust-group-admins.md, Out of scope).
     *
     * A device holds a room at epoch 0 only if it made it: everybody else's copy
     * comes from a Welcome, which is already past the founding commit.
     */
    suspend fun uninvitedFounders(
        database: DedunDatabase,
        room: LocalChatRoom,
        ceremony: DkgSession,
        ceremonyRoom: LocalChatRoom,
    ): Set<HexKey> {
        val group = liveGroupOf(database, room) ?: return emptySet()
        if (group.epoch != 0L) return emptySet()

        val holdingLeaves = Participant.fromMlsGroup(group, room.chatRoom.id, null).map { it.participantPublicKey }.toSet()
        return ChillDkgRitualManager.participantsOf(ceremony, ceremonyRoom) - holdingLeaves
    }

    /**
     * The ceremony that made [chatRoomId]'s key, as this device holds it, and the
     * room it ran in -- or null where this device holds no key state for the room,
     * no record of that ceremony, or no longer the room it ran in.
     */
    suspend fun ceremonyOf(database: DedunDatabase, chatRoomId: String): Pair<DkgSession, LocalChatRoom>? {
        val state = database.groupKeyStateDao().getByChatRoomId(chatRoomId) ?: return null
        val ceremony = database.dkgSessionDao().getSessionById(state.dkgSessionId) ?: return null
        val ceremonyRoom = database.chatRoomDao().findChatRoomById(ceremony.chatRoomId) ?: return null
        return ceremony to ceremonyRoom
    }

    /**
     * Invites the founding members [room]'s founding invite never reached -- see
     * [uninvitedFounders] -- in one commit, as [create] would have.
     *
     * Every key package first, as at creation: a founder who cannot be invited yet
     * holds the rest back rather than leaving the room to a second, partial
     * founding. docs/robust-group-admins.md, Phase 4.
     */
    suspend fun finishFoundingInvite(
        database: DedunDatabase,
        chatRepository: ChatRepository,
        ceremony: DkgSession,
        ceremonyRoom: LocalChatRoom,
        keyPackageLookupTimeout: Long = KEY_PACKAGE_LOOKUP_TIMEOUT,
    ): FoundingInvite {
        val thresholdPublicKey = ceremony.thresholdPublicKey ?: return FoundingInvite.NothingToDo
        val groupId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
        val room = chatRepository.getChatRoomByIdentifier(groupId) ?: return FoundingInvite.NothingToDo

        val uninvited = uninvitedFounders(database, room, ceremony, ceremonyRoom)
        if (uninvited.isEmpty()) return FoundingInvite.NothingToDo

        val keyPackages = keyPackagesFor(chatRepository, uninvited.toList(), keyPackageLookupTimeout, room.chatRoom.marmotProfile())
        val missing = keyPackages.filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            logger.w("Not finishing $groupId's founding invite: no key package for $missing")
            return FoundingInvite.BlockedOn(missing)
        }
        val addable = keyPackages.mapNotNull { (publicKey, keyPackage) -> keyPackage?.let { publicKey to it } }

        val notAdded = runCatching {
            chatRepository.addMembers(localChatRoom = room, peers = addable)
        }.onFailure {
            logger.e("Failed to finish $groupId's founding invite", it)
        }.getOrElse { return FoundingInvite.Failed(it.message ?: "Couldn't invite them. Please try again.") }

        flagAdmins(database, groupId)
        logger.i("Finished $groupId's founding invite: ${addable.size - notAdded.size} invited, ${notAdded.size} not")
        return FoundingInvite.Sent(groupId, addable.map { it.first }, notAdded)
    }

    /** The room's live group: the row's on the legacy profile, the engine's on the current one. */
    private suspend fun liveGroupOf(database: DedunDatabase, room: LocalChatRoom): MlsGroup? =
        when (room.chatRoom.marmotProfile()) {
            MarmotProfile.LEGACY -> room.chatRoom.toMlsGroup()
            MarmotProfile.CURRENT -> database.marmotEngine.groupFor(room.routingId)
        }

    /**
     * Writes `adminAt` on the rows of the members this room was created with as
     * admins.
     *
     * The creating device never learns this any other way: its rows come from
     * `getOrCreateChatRoom` and `addMembers`, neither of which sets the flag, and
     * no commit arrives to reconcile them -- a room whose epoch-0 context named
     * three admins had none of them flagged on the one device certain to be one.
     *
     * Through [AdminFlags.reconcile], off the group this device just made, rather
     * than off [create]'s own list: the policy is what every other device reads,
     * and one writer is how the two cannot disagree (docs/robust-group-admins.md,
     * D2). Only flags are written. A membership reconciliation here would remove
     * the rows of anyone `addMembers` could not reach -- turning a partial invite
     * into a partial membership.
     *
     * Swallowed on failure, like `adopt` above it: the room is made and usable,
     * and what a missing flag costs is a label rather than anything being wrong.
     */
    private suspend fun flagAdmins(database: DedunDatabase, groupId: String) {
        runCatching {
            val group = database.chatRoomDao().findChatRoomById(groupId)?.chatRoom?.toMlsGroup() ?: return
            AdminFlags.reconcile(database, groupId, group)
        }.onFailure {
            logger.e("Created $groupId but could not flag its admins", it)
        }
    }

    /**
     * Every peer's published key package, or null where there is none.
     *
     * Concurrently, so the whole set costs one relay round trip rather than one
     * each, and with a timeout because a member who has never published one would
     * otherwise hold the flow open forever.
     */
    suspend fun keyPackagesFor(
        chatRepository: ChatRepository,
        publicKeys: List<HexKey>,
        timeout: Long = KEY_PACKAGE_LOOKUP_TIMEOUT,
        profile: MarmotProfile = MarmotProfile.DEFAULT_FOR_NEW_ROOMS,
    ): List<Pair<HexKey, MarmotKeyPackage?>> = coroutineScope {
        publicKeys.map { publicKey ->
            async {
                publicKey to withTimeoutOrNull(timeout) {
                    chatRepository.observeMarmotKeyPackageForPublicKey(publicKey, profile)
                        .filterNotNull()
                        .first()
                }
            }
        }.awaitAll()
    }
}
