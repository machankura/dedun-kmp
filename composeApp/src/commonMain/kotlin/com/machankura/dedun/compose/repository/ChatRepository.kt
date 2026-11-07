package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.managers.MarmotGroupCreation
import com.machankura.dedun.compose.managers.SubgroupManager
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.MarmotReindexReport
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ChatRepository {
    suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>>

    suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom>

    suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom?

    /**
     * Ask the group for its signed history if this device holds none of it.
     *
     * True when a request went out. Safe to call on every open: it is a no-op
     * for a room that already holds work and for one still waiting on an answer.
     * See docs/member-chronicle.md for why the joiner asks rather than the inviter
     * pushing.
     */
    suspend fun requestGroupHistoryIfMissing(chatRoomId: String, userPublicKey: HexKey): Boolean

    /**
     * What the group has said this room signs with, or null while it has said
     * nothing.
     *
     * The row the app resolves a signing request against -- see
     * `FrostSigningManager.completedKey` -- and the thing the group detail screen
     * puts at the top, because which key a room signs as is the one fact about a
     * room that decides whether anything it signs will be believed.
     */
    suspend fun groupKeyState(chatRoomId: String): GroupKeyState?

    /**
     * The event the group signed to say it, as the group signed it.
     *
     * Kept apart from [groupKeyState] because they can be missing separately and
     * mean different things when they are. The state is this device's reading;
     * this is the statement, with the signature on it, for a member who wants to
     * see or check what the group actually put its key to.
     */
    suspend fun signedGroupKeyStateEvent(chatRoomId: String): GroupSignedEvent?

    /**
     * Every subgroup this room has certified, as far as this device knows.
     *
     * Read off the parent's own signed certificates rather than off a table, so
     * it includes children whose room this device does not have -- which is the
     * normal position of a member who is not in the subgroup. See
     * `SubgroupManager.subgroupsOf`.
     */
    suspend fun subgroupsOf(chatRoomId: String): List<SubgroupManager.Subgroup>

    /**
     * The group this room is a subgroup of, verified, or null if it is nobody's
     * child.
     *
     * Off the room's `GroupKeyState`, which only carries a parent whose
     * certificate was checked. A member welcomed in after the founding holds no
     * state and gets null here even when the room really is a subgroup.
     */
    suspend fun parentOf(chatRoomId: String): HexKey?

    /**
     * What this room's own birth certificate says of its wallet, or null for a
     * room with none. See `SubgroupManager.walletPolicyOf`.
     */
    suspend fun walletPolicyOf(chatRoomId: String): WalletPolicyReading?

    /**
     * The guardian keys this room's own certificate grants it, nearest first --
     * empty for a room with none, null when they do not read. See
     * `SubgroupManager.guardiansOf`.
     */
    suspend fun guardiansOf(chatRoomId: String): List<GuardianDerivation.Guardian>?

    /**
     * The wallet this group has opened on [chain], or null when it has not --
     * read off the group's signed statement and rederived from the room's key.
     */
    suspend fun groupWallet(chatRoomId: String, chain: Chain): GroupWallet?

    /**
     * Who holds a share of the key of the Lightning wallet this group uses on [chain], and its node's
     * seed: the people a removal leaves able to grief it until a new wallet replaces it (Phase 14).
     */
    suspend fun lightningParticipants(chatRoomId: String, chain: Chain): Set<HexKey> = emptySet()

    /** Who holds a share of any of a room's Wavelength wallets, on any network it's offered on. */
    suspend fun wavelengthParticipants(chatRoomId: String): Set<HexKey> = emptySet()

    /**
     * The account this group *would* open on [chain], whether or not it has, or
     * null for a room with no key: what a statement proposing the wallet carries.
     */
    suspend fun groupWalletAccount(chatRoomId: String, chain: Chain): GroupWalletDerivation.Account?

    /**
     * The guarded account this group could open on [chain] instead, and the
     * certificate granting its guardians -- or null for a group whose birth
     * certificate grants it none.
     */
    suspend fun groupWalletGuardedOpening(chatRoomId: String, chain: Chain): GroupWalletManager.GuardedOpening?

    /** The guarded wallets of groups below this one that it has been told about and can sign for. */
    suspend fun guardedWallets(chatRoomId: String): List<GuardianManager.GuardedWallet>

    /** The guarded statements this device can carry from this group to the groups above it, with where to. */
    suspend fun shareableGuardedWallets(chatRoomId: String, chain: Chain): List<GuardianManager.Shareable>

    /** Tells the groups in [shareable] about its statement, as [userPublicKey]. Returns how many were told. */
    suspend fun shareGuardedWallet(shareable: GuardianManager.Shareable, userPublicKey: HexKey): Int

    /**
     * Tells the groups above this one about every guarded wallet it holds that
     * they have not heard of -- the catch-up of `docs/guarded-wallet-forwarding.md`.
     * Returns how many groups were told. Nothing, by default.
     */
    suspend fun forwardGuardedWallets(chatRoomId: String): Int = 0

    /** Writes a transcript line for each guardian spend from this group's wallet it has none for yet. */
    suspend fun recordGuardianSpends(chatRoomId: String, taken: List<GuardianManager.Taken>): Int

    /**
     * Whether this device holds a share of the group's key, and so could take
     * part in signing for it.
     *
     * Read by the UI so nothing is offered that would throw:
     * `FrostSigningManager.proposeSigningBatch` refuses outright for a device
     * with no share, and a button that throws is not a button.
     */
    suspend fun canSign(chatRoomId: String): Boolean

    /**
     * Why these members cannot hold a subgroup ceremony together, or null when
     * they can.
     *
     * Read by the picker so a subgroup that could only fail is never offered, and
     * again on confirm, because a screen not drawing something is not a guard.
     * See `SubgroupManager.refuseSubgroup`.
     */
    suspend fun refuseSubgroup(
        parentChatRoomId: String,
        adminPublicKeys: Set<HexKey>,
        coordinatorPublicKey: HexKey,
    ): String?

    suspend fun getAllParticipantsWithPubKey(publicKey: HexKey): List<Participant>

    /**
     * The room that is a conversation between [userPublicKey] and [peerPublicKey]
     * alone, if there is one: its members are exactly the two of them, or the one of
     * them when the two keys are the same. A group of two is not told apart from a
     * direct message here -- the schema does not record which a room is -- so this is
     * the rule `ChatRoomMessagingViewModel.initiateNewChat` has applied since it was
     * written, in one place with its name on it. See docs/npub-profile-preview.md.
     */
    suspend fun findDirectMessageRoom(userPublicKey: HexKey, peerPublicKey: HexKey): LocalChatRoom?

    suspend fun getChatMessageListByChatRoomId(chatRoomId: String): List<LocalChatMessage>

    suspend fun observeChatMessageListByChatRoomId(chatRoomId: String): Flow<List<LocalChatMessage>>

    suspend fun getOrCreateChatRoom(
        chatRoomId: String,
        activeUserPublicKey: HexKey,
        relayHint: String?,
        defaultSubject: String?,
        description: String?,
        mlsGroupState: String?
    ): LocalChatRoom?

    suspend fun getChatMessageRelayForPublicKey(publicKey: HexKey): ChatMessageRelayListEvent?

    /**
     * A peer's usable KeyPackage for a room on [roomProfile].
     *
     * A legacy room takes the newest of any profile -- both shapes fit it. A
     * current-profile room takes the newest current-profile package or none: the
     * legacy one is deliberately the newest a peer publishes while they publish both,
     * and cannot join a current-profile room. See docs/marmot-v2.md, Phase 3.
     */
    suspend fun getMarmotKeyPackageForPublicKey(
        publicKey: HexKey,
        roomProfile: MarmotProfile = MarmotProfile.LEGACY,
    ): MarmotKeyPackage?

    suspend fun observeMarmotKeyPackageForPublicKey(
        publicKey: HexKey,
        roomProfile: MarmotProfile = MarmotProfile.LEGACY,
    ): Flow<MarmotKeyPackage?>

    /**
     * Creates a NIP-17 group: participants and a room, no MLS group. Membership is
     * the p-tag set carried by each message, so there is nothing to invite anyone to.
     */
    suspend fun createNip17ChatRoom(
        userPublicKey: HexKey,
        participantPublicKeys: List<HexKey>,
        subject: String? = null,
        description: String? = null
    ): LocalChatRoom?

    suspend fun createMlsDirectMessage(
        name: String? = null,
        description: String? = null,
        userPublicKey: HexKey,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage
    ): String

    /**
     * Record that [chatRoomId]'s kind:445 traffic travels under [routingId]. On the
     * legacy profile the two are one value; on the current profile the address is
     * minted at random and this is what ties it back. See `MarmotRouting`.
     */
    suspend fun recordMarmotRouting(chatRoomId: HexKey, routingId: HexKey)

    suspend fun inviteMember(
        localChatRoom: LocalChatRoom,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage,
    )

    /**
     * Adds every member in one MLS commit, returning those that could not be added.
     *
     * Prefer this to looping [inviteMember] whenever the membership is known up
     * front: one commit means no intermediate epoch for a member to miss, which is
     * a silent failure rather than a loud one. See docs/marmot-membership.md.
     */
    suspend fun addMembers(
        localChatRoom: LocalChatRoom,
        peers: List<Pair<HexKey, MarmotKeyPackage>>,
    ): List<HexKey>

    /**
     * Creates a Marmot room at an id the group's key derives, with
     * [adminPublicKeys] as its admins, and welcomes them into it.
     *
     * Two flows want exactly this: the admin room a group opens after its
     * ceremony, and a subgroup. See `MarmotGroupCreation`, which holds the rules
     * -- every key package before anything exists, the admins baked into epoch 0,
     * and the key state filed before the members are added.
     *
     * Refuses rather than half-creates: the id is derived, so a partly created
     * room occupies that address permanently and there is no second id to retry
     * with.
     */
    suspend fun createMarmotGroup(
        groupId: String,
        name: String,
        purpose: String,
        adminPublicKeys: Set<HexKey>,
        userPublicKey: HexKey,
        keyPair: KeyPair,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
        parentChatRoomId: HexKey? = null,
    ): MarmotGroupCreation.Outcome

    /**
     * Sends [text] to the room, or -- when [directMessageRecipientPublicKey] is given --
     * to that one member of it.
     *
     * A direct message still travels as one group event that every member receives and
     * can see the shape of; only its contents are private. See
     * docs/marmot-direct-messages.md. NIP-17 rooms do not support it: there is no group
     * for the rest of to see it, so a private message there is just a message.
     */
    suspend fun sendChatMessage(
        text: String,
        localChatRoom: LocalChatRoom,
        messageType: Kind = ChatMessageEvent.KIND,
        directMessageRecipientPublicKey: HexKey? = null
    )

    fun updateChatRoomSubject(publicKey: String, subject: String): ChatRoom?

    /**
     * The whole unsealed backlog for [publicKey], oldest first.
     *
     * See `GiftWrapPayloadDao.observeUnsealedGiftWrapPayloads`: a payload that cannot be
     * sealed used to be the entire queue. Callers work through it and a refusal costs
     * only the payload it refuses.
     */
    suspend fun observeUnsealedGiftWrapPayloads(publicKey: HexKey): Flow<List<GiftWrapPayload>>

    suspend fun sealGiftWrapPayload(giftWrapPayload: GiftWrapPayload, nostrSignerSync: NostrSignerSync)

    suspend fun leaveChatRoom(
        localChatRoom: LocalChatRoom,
        onCompletion: () -> Unit
    )

    /**
     * Removes [memberPublicKey] from a current-profile room this device administers:
     * a commit through the publish gate, applied when a relay accepts it. See
     * docs/marmot-v2.md, Phase 7.
     */
    suspend fun removeMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey)

    /**
     * Disbands a current-profile room this device administers. Absorbing: once the
     * disband commit is applied nobody rejoins, and a replacement conversation is a
     * new group.
     */
    suspend fun disbandChatRoom(localChatRoom: LocalChatRoom)

    suspend fun softDeleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    )

    suspend fun deleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    )

    /**
     * Reads the room's stored marmot group events again, so ones the group could
     * not make sense of when they arrived get another chance now that the events
     * they were waiting on have landed.
     *
     * Only events with nothing to show for them are replayed, so a room where
     * nothing went wrong is left exactly as it was.
     */
    suspend fun reindexMarmotGroupEvents(
        chatRoomId: String,
        userPublicKey: HexKey
    ): MarmotReindexReport

    companion object {
        val NO_OP_CHAT_REPOSITORY = object: ChatRepository {
            override suspend fun requestGroupHistoryIfMissing(
                chatRoomId: String,
                userPublicKey: HexKey
            ): Boolean = false

            override suspend fun groupKeyState(chatRoomId: String): GroupKeyState? = null

            override suspend fun signedGroupKeyStateEvent(
                chatRoomId: String
            ): GroupSignedEvent? = null

            override suspend fun subgroupsOf(chatRoomId: String): List<SubgroupManager.Subgroup> =
                emptyList()

            override suspend fun parentOf(chatRoomId: String): HexKey? = null

            override suspend fun walletPolicyOf(chatRoomId: String): WalletPolicyReading? = null

            override suspend fun guardiansOf(chatRoomId: String): List<GuardianDerivation.Guardian>? = emptyList()

            override suspend fun groupWallet(chatRoomId: String, chain: Chain): GroupWallet? = null

            override suspend fun groupWalletAccount(
                chatRoomId: String,
                chain: Chain
            ): GroupWalletDerivation.Account? = null

            override suspend fun groupWalletGuardedOpening(
                chatRoomId: String,
                chain: Chain
            ): GroupWalletManager.GuardedOpening? = null

            override suspend fun guardedWallets(chatRoomId: String): List<GuardianManager.GuardedWallet> = emptyList()

            override suspend fun shareableGuardedWallets(
                chatRoomId: String,
                chain: Chain
            ): List<GuardianManager.Shareable> = emptyList()

            override suspend fun shareGuardedWallet(
                shareable: GuardianManager.Shareable,
                userPublicKey: HexKey
            ): Int = 0

            override suspend fun recordGuardianSpends(
                chatRoomId: String,
                taken: List<GuardianManager.Taken>
            ): Int = 0

            override suspend fun canSign(chatRoomId: String): Boolean = false

            override suspend fun refuseSubgroup(
                parentChatRoomId: String,
                adminPublicKeys: Set<HexKey>,
                coordinatorPublicKey: HexKey,
            ): String? = "No database"

            override suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>> {
                return flow {  }
            }

            override suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom> {
                return emptyList()
            }

            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? {
                return null
            }

            override suspend fun getAllParticipantsWithPubKey(publicKey: HexKey): List<Participant> {
                TODO("Not yet implemented")
            }

            override suspend fun findDirectMessageRoom(userPublicKey: HexKey, peerPublicKey: HexKey): LocalChatRoom? {
                TODO("Not yet implemented")
            }

            override suspend fun getChatMessageListByChatRoomId(chatRoomId: String): List<LocalChatMessage> {
                return emptyList()
            }

            override suspend fun observeChatMessageListByChatRoomId(chatRoomId: String): Flow<List<LocalChatMessage>> {
                return flow {
                }
            }

            override suspend fun getOrCreateChatRoom(
                chatRoomId: String,
                activeUserPublicKey: HexKey,
                relayHint: String?,
                defaultSubject: String?,
                description: String?,
                mlsGroupState: String?
            ): LocalChatRoom? {
                return null
            }

            override suspend fun getChatMessageRelayForPublicKey(publicKey: HexKey): ChatMessageRelayListEvent? {
                return null
            }

            override suspend fun getMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): MarmotKeyPackage? {
                TODO("Not yet implemented")
            }

            override suspend fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): Flow<MarmotKeyPackage?> {
                TODO("Not yet implemented")
            }

            override suspend fun createNip17ChatRoom(
                userPublicKey: HexKey,
                participantPublicKeys: List<HexKey>,
                subject: String?,
                description: String?
            ): LocalChatRoom? {
                return null
            }

            override suspend fun createMlsDirectMessage(
                name: String?,
                description: String?,
                userPublicKey: HexKey,
                peerPublicKey: HexKey,
                peerKeyPackage: MarmotKeyPackage
            ): String {
                TODO("Not yet implemented")
            }

            override suspend fun recordMarmotRouting(chatRoomId: HexKey, routingId: HexKey) = Unit

            override suspend fun inviteMember(
                localChatRoom: LocalChatRoom,
                peerPublicKey: HexKey,
                peerKeyPackage: MarmotKeyPackage
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun addMembers(
                localChatRoom: LocalChatRoom,
                peers: List<Pair<HexKey, MarmotKeyPackage>>
            ): List<HexKey> = peers.map { it.first }

            override suspend fun createMarmotGroup(
                groupId: String,
                name: String,
                purpose: String,
                adminPublicKeys: Set<HexKey>,
                userPublicKey: HexKey,
                keyPair: KeyPair,
                path: List<Long>,
                parentChatRoomId: HexKey?,
            ): MarmotGroupCreation.Outcome =
                MarmotGroupCreation.Outcome.Failed("No database")


            override suspend fun sendChatMessage(
                text: String,
                localChatRoom: LocalChatRoom,
                messageType: Kind,
                directMessageRecipientPublicKey: HexKey?
            ) {
                // TODO("Not yet implemented")
            }

            override fun updateChatRoomSubject(publicKey: String, subject: String): ChatRoom? {
                return null
            }

            override suspend fun observeUnsealedGiftWrapPayloads(publicKey: HexKey): Flow<List<GiftWrapPayload>> {
                TODO("Not yet implemented")
            }

            override suspend fun sealGiftWrapPayload(
                giftWrapPayload: GiftWrapPayload,
                nostrSignerSync: NostrSignerSync
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun leaveChatRoom(
                localChatRoom: LocalChatRoom,
                onCompletion: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun removeMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey) = Unit

            override suspend fun disbandChatRoom(localChatRoom: LocalChatRoom) = Unit

            override suspend fun softDeleteChatRoom(
                chatRoom: ChatRoom,
                onCompletion: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun deleteChatRoom(
                chatRoom: ChatRoom,
                onCompletion: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun reindexMarmotGroupEvents(
                chatRoomId: String,
                userPublicKey: HexKey
            ): MarmotReindexReport = MarmotReindexReport()
        }
    }
}