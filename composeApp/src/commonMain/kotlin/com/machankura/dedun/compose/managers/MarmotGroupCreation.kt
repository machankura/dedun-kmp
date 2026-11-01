package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlinx.coroutines.withTimeoutOrNull
import com.machankura.dedun.compose.database.DedunDatabase
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
     * caller. Every Marmot room on a device is a `#name`, and this is the funnel
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

        val notAdded = runCatching {
            chatRepository.addMembers(localChatRoom = localChatRoom, peers = addable)
        }.onFailure {
            logger.e("Failed to add members to $groupId", it)
        }.getOrElse { addable.map { (publicKey, _) -> publicKey } }

        if (notAdded.isNotEmpty()) {
            logger.w("$groupId created without ${notAdded.size} member(s): $notAdded")
        }

        stampAdmins(database, groupId, adminPublicKeys)

        return Outcome.Created(
            room = chatRepository.getChatRoomByIdentifier(groupId) ?: localChatRoom,
            notAdded = notAdded
        )
    }

    /**
     * Writes `adminAt` on the rows of the members this room was created with as
     * admins.
     *
     * The creating device never learns this any other way, which is a bug older
     * than subgroups and easy to miss. `Participant.adminAt` is written in exactly
     * one place -- `MarmotInboundManager.processGroupMembershipChanges`, on an
     * arriving commit -- while the rows a creator makes come from
     * `getOrCreateChatRoom` and `addMembers`, neither of which sets it. So on the
     * device that made the room, every participant reads as a non-admin until some
     * *other* member sends a commit it processes: a room whose epoch-0 context
     * names three admins had none of them flagged, on the one device certain to be
     * one.
     *
     * This is the same list already baked into `MarmotGroupData` a few lines
     * above, so nothing new is being asserted -- the row is being caught up with
     * the group context it was created from. `processGroupMembershipChanges`
     * reconciles it against the MLS tree from then on and will agree.
     *
     * Only flags are written. That function also *removes* participants missing
     * from the tree, and running the whole reconciliation here would soft-delete
     * the rows of anyone `addMembers` could not reach -- turning a partial invite
     * into a partial membership. Widening a flag is safe; narrowing membership on
     * a best-effort step is not.
     *
     * Swallowed on failure, like `adopt` above it: the room is made and usable,
     * and what a missing flag costs is a button not being offered rather than
     * anything being wrong.
     */
    private suspend fun stampAdmins(
        database: DedunDatabase,
        groupId: String,
        adminPublicKeys: Set<HexKey>
    ) {
        runCatching {
            val room = database.chatRoomDao().findChatRoomById(groupId) ?: return

            val stamped = room.localParticipants
                .map { it.participant }
                .filter { it.participantPublicKey in adminPublicKeys && it.adminAt == null }
                .map { it.copy(adminAt = Clock.System.now()) }

            if (stamped.isNotEmpty()) database.participantDao().upsert(stamped)
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
