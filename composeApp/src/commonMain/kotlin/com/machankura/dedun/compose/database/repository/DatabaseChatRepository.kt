package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.DedunDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.database.model.MarmotRouting
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.toKeyPair
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.ChronicleManager
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.managers.GroupKeyStateManager
import com.machankura.dedun.compose.managers.MarmotGroupCreation
import com.machankura.dedun.compose.managers.SubgroupManager
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.MarmotReindexReport
import com.machankura.dedun.compose.nostr.GroupPost
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.machankura.dedun.compose.database.model.ShareRefreshSession
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.machankura.dedun.compose.managers.AdminGovernance
import com.machankura.dedun.compose.managers.GovernanceGate
import com.machankura.dedun.compose.managers.PromotionManager
import com.machankura.dedun.compose.managers.GovernanceFollowThrough
import com.machankura.dedun.compose.managers.GovernanceSupport
import com.machankura.dedun.compose.managers.MemberActionContext
import com.machankura.dedun.compose.managers.MemberActionPolicy
import com.machankura.dedun.compose.managers.MemberActionRoom
import com.machankura.dedun.compose.managers.MarmotInboundManager
import com.machankura.dedun.compose.managers.AdminFlags
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.managers.CeremonyFollowThrough
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.managers.ShareRefreshManager
import com.machankura.dedun.compose.managers.WalletKeysHeld
import com.machankura.dedun.compose.ui.view.state.GovernanceStatus
import com.machankura.dedun.compose.ui.view.state.MemberActionOption
import com.machankura.dedun.compose.managers.RotationHold
import com.machankura.dedun.compose.managers.RoomAdmins
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.SharesSummary
import com.machankura.dedun.compose.ui.view.state.RotationPreview
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.database.model.types.ShareRepairStage
import com.machankura.dedun.compose.database.model.types.ShareRefreshStage
import com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.managers.ShareGenerations
import com.machankura.dedun.compose.managers.ShareRepairManager
import com.machankura.dedun.compose.ExperimentalFeatures

/**
 * @param activeIdentity the identity the app runs as. Read by the one write here that
 *   has to sign as it -- creating a direct-message room on the current profile puts
 *   an account identity proof in the creator's leaf -- and by nothing else; a caller
 *   that has only the public key still gets a legacy room without it.
 */
class DatabaseChatRepository(
    private val database: DedunDatabase,
    private val scope: CoroutineScope,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
): com.machankura.dedun.compose.repository.ChatRepository {
    val logger = Logger.withTag(TAG)

    override suspend fun createMarmotGroup(
        ceremony: DkgSession,
        ceremonyRoom: LocalChatRoom,
        keyPair: KeyPair,
        parentChatRoomId: HexKey?,
    ): MarmotGroupCreation.Outcome = MarmotGroupCreation.createFor(
        database = database,
        chatRepository = this,
        ceremony = ceremony,
        ceremonyRoom = ceremonyRoom,
        keyPair = keyPair,
        parentChatRoomId = parentChatRoomId,
    )

    override suspend fun finishFoundingInvite(
        ceremony: DkgSession,
        ceremonyRoom: LocalChatRoom,
    ): MarmotGroupCreation.FoundingInvite = MarmotGroupCreation.finishFoundingInvite(
        database = database,
        chatRepository = this,
        ceremony = ceremony,
        ceremonyRoom = ceremonyRoom,
    )

    override suspend fun finishFoundingInvite(chatRoomId: String): MarmotGroupCreation.FoundingInvite {
        val (ceremony, ceremonyRoom) = MarmotGroupCreation.ceremonyOf(database, chatRoomId)
            ?: return MarmotGroupCreation.FoundingInvite.NothingToDo
        return finishFoundingInvite(ceremony, ceremonyRoom)
    }

    override suspend fun uninvitedFounders(localChatRoom: LocalChatRoom): Set<HexKey> = try {
        MarmotGroupCreation.ceremonyOf(database, localChatRoom.chatRoom.id)
            ?.let { (ceremony, ceremonyRoom) ->
                MarmotGroupCreation.uninvitedFounders(database, localChatRoom, ceremony, ceremonyRoom)
            }
            .orEmpty()
    } catch (e: Throwable) {
        logger.e("Error reading who ${localChatRoom.chatRoom.id}'s founding invite missed", e)
        emptySet()
    }

    override suspend fun adminRoomOf(chatRoomId: String): String? = try {
        // The room's own ceremony, not a subgroup's it hosted: a subgroup's key
        // makes the subgroup's room, which is not this group's admin room.
        database.dkgSessionDao().getLatestSessionFor(chatRoomId, parentChatRoomId = null)
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
            ?.thresholdPublicKey
            ?.let { SharedKeyDerivation.marmotGroupId(it) }
            ?.takeIf { it != chatRoomId && database.chatRoomDao().findChatRoomById(it) != null }
    } catch (e: Throwable) {
        logger.e("Error finding the admin room of $chatRoomId", e)
        null
    }

    override suspend fun reconcileAdminFlags(): Int = AdminFlags.reconcileAll(database)

    override suspend fun announceGovernanceSupport(): Int = GovernanceSupport.announceAll(database)

    override suspend fun followThroughCeremonies(keyPair: KeyPair) = kotlinx.coroutines.coroutineScope {
        launch { CeremonyFollowThrough.observe(database = database, chatRepository = this@DatabaseChatRepository, keyPair = keyPair) }
        // What a governed room's signed changes need after they land: a rotation
        // without a demoted member, a promotion's next steps.
        // docs/member-admin-promotion.md, Phases 8 and 9.
        launch { GovernanceFollowThrough.observe(database = database, keyPair = keyPair) }
        Unit
    }

    override suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>> {
        return database.chatRoomDao().observeChatRoomListByUserPublicKey(publicKey)
    }

    override suspend fun markChatRoomRead(chatRoomId: String) {
        database.notificationDao().markAllRead(chatRoomId)
    }

    override fun observeUnreadCounts(publicKey: String): Flow<Map<String, com.machankura.dedun.compose.database.dao.RoomUnread>> =
        database.notificationDao().observeUnreadCounts(publicKey)
            .map { counts -> counts.associateBy { it.chatRoomId } }

    override suspend fun setChatRoomNotifications(chatRoomId: String, notifications: String) {
        database.notificationDao().setNotifications(chatRoomId, notifications)
    }

    override suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom> {
        return database.chatRoomDao().getChatRoomListByUserPublicKey(publicKey)
    }

    override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? {
        return database.chatRoomDao().findChatRoomById(id)
    }

    override suspend fun requestGroupHistoryIfMissing(
        chatRoomId: String,
        userPublicKey: HexKey
    ): Boolean = ChronicleManager.requestIfEmpty(
        database = database,
        chatRoomId = chatRoomId,
        userPublicKey = userPublicKey,
    )

    override suspend fun groupKeyState(chatRoomId: String): GroupKeyState? =
        GroupKeyStateManager.keyStateFor(database, chatRoomId)

    override suspend fun signedGroupKeyStateEvent(chatRoomId: String): GroupSignedEvent? = try {
        GroupKeyStateManager.signedEventFor(database, chatRoomId)
    } catch (e: Throwable) {
        // Reading it walks every key state this device holds through the same
        // checks that let one be believed in the first place, and one that will
        // not derive throws rather than answering. The screen wants to draw
        // either way -- what it loses is the copyable event, not the room.
        logger.e("Error reading the signed key state event for $chatRoomId", e)
        null
    }

    override suspend fun groupNostrProfile(chatRoomId: String): GroupNostrProfile? = try {
        GroupNostrProfile.newestAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, MetadataEvent.KIND),
            chatRoomId = chatRoomId,
        )
    } catch (e: Throwable) {
        // Reading one parses content off the wire and checks a signature, and a
        // room that shows no profile is wrong and survivable where one that will
        // not open is neither -- the same trade the readings above make.
        logger.e("Error reading the nostr profile of $chatRoomId", e)
        null
    }

    override suspend fun groupPosts(chatRoomId: String): List<GroupPost> = try {
        GroupPost.newestFirstAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, TextNoteEvent.KIND),
            chatRoomId = chatRoomId,
        )
    } catch (e: Throwable) {
        logger.e("Error reading the posts of $chatRoomId", e)
        emptyList()
    }

    override suspend fun groupSignedEvent(chatRoomId: String, id: HexKey): GroupSignedEvent? = try {
        database.groupSignedEventDao().getById(id)?.takeIf { it.chatRoomId == chatRoomId }
    } catch (e: Throwable) {
        logger.e("Error reading the signed event $id of $chatRoomId", e)
        null
    }


    override suspend fun subgroupsOf(chatRoomId: String): List<SubgroupManager.Subgroup> = try {
        SubgroupManager.subgroupsOf(database, chatRoomId)
    } catch (e: Throwable) {
        // A room that shows no subgroups is wrong and survivable; one that will
        // not open because reading them threw is neither.
        logger.e("Error reading the subgroups of $chatRoomId", e)
        emptyList()
    }

    override suspend fun parentOf(chatRoomId: String): HexKey? = try {
        SubgroupManager.parentOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the parent of $chatRoomId", e)
        null
    }

    override suspend fun walletPolicyOf(chatRoomId: String): WalletPolicyReading? = try {
        SubgroupManager.walletPolicyOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the wallet policy of $chatRoomId", e)
        null
    }

    // Null on a failure, which reads as keys this build cannot read: the picker
    // then refuses a guarded subgroup rather than promising a grant it could not
    // check.
    override suspend fun guardiansOf(chatRoomId: String): List<GuardianDerivation.Guardian>? = try {
        SubgroupManager.guardiansOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the guardians of $chatRoomId", e)
        null
    }

    override suspend fun wavelengthParticipants(chatRoomId: String): Set<HexKey> = runCatching {
        GroupWavelengthRollout.NETWORKS.flatMap { chain -> GroupWavelengthWalletManager.current(database, chatRoomId, chain)?.statement?.participants.orEmpty() }.toSet()
    }.getOrDefault(emptySet())

    override suspend fun lightningParticipants(chatRoomId: String, chain: Chain): Set<HexKey> =
        runCatching { GroupLightningWalletManager.current(database, chatRoomId, chain)?.statement?.participants?.toSet() }.getOrNull().orEmpty()

    override suspend fun groupWallet(chatRoomId: String, chain: Chain): GroupWallet? = try {
        GroupWalletManager.walletFor(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading the wallet of $chatRoomId", e)
        null
    }

    override suspend fun groupWalletAccount(chatRoomId: String, chain: Chain): GroupWalletDerivation.Account? = try {
        GroupWalletManager.accountFor(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error deriving the wallet account of $chatRoomId", e)
        null
    }

    override suspend fun groupWalletGuardedOpening(chatRoomId: String, chain: Chain): GroupWalletManager.GuardedOpening? = try {
        GroupWalletManager.guardedAccountFor(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error deriving the guarded wallet account of $chatRoomId", e)
        null
    }

    override suspend fun guardedWallets(chatRoomId: String): List<GuardianManager.GuardedWallet> = try {
        GuardianManager.guardedWalletsOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the guarded wallets $chatRoomId was told about", e)
        emptyList()
    }

    override suspend fun shareableGuardedWallets(chatRoomId: String, chain: Chain): List<GuardianManager.Shareable> = try {
        GuardianManager.shareableFrom(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading what $chatRoomId can tell the groups above it", e)
        emptyList()
    }

    override suspend fun forwardGuardedWallets(chatRoomId: String): Int = try {
        GuardianManager.forwardAll(database, chatRoomId)
    } catch (e: Throwable) {
        // A room that opens without telling the groups above is wrong and
        // survivable, and the button is still there.
        logger.e("Error forwarding $chatRoomId's guarded wallets upward", e)
        0
    }

    override suspend fun shareGuardedWallet(shareable: GuardianManager.Shareable, userPublicKey: HexKey): Int = try {
        GuardianManager.share(database, shareable.statement, shareable.rooms, userPublicKey, certificate = shareable.certificate)
    } catch (e: Throwable) {
        logger.e("Error telling the groups above about ${shareable.statement.pubKey}'s guarded wallet", e)
        0
    }

    override suspend fun recordGuardianSpends(chatRoomId: String, taken: List<GuardianManager.Taken>): Int = try {
        GuardianManager.recordTaken(database, chatRoomId, taken)
    } catch (e: Throwable) {
        logger.e("Error recording guardian spends from $chatRoomId's wallet", e)
        0
    }

    override suspend fun canSign(chatRoomId: String): Boolean = try {
        FrostSigningManager.canSign(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId can sign", e)
        false
    }

    override suspend fun refuseSubgroup(
        parentChatRoomId: String,
        adminPublicKeys: Set<HexKey>,
        coordinatorPublicKey: HexKey,
    ): String? = try {
        val parentRoom = database.chatRoomDao().findChatRoomById(parentChatRoomId)
            ?: return "Couldn't load this group."

        SubgroupManager.refuseSubgroup(
            database = database,
            parentRoom = parentRoom,
            adminPublicKeys = adminPublicKeys,
            coordinatorPublicKey = coordinatorPublicKey,
        )
    } catch (e: Throwable) {
        // A refusal that cannot be computed has to refuse. The alternative is
        // opening a ceremony whose guards never ran.
        logger.e("Error checking whether $parentChatRoomId can take a subgroup", e)
        "Couldn't check this subgroup. Please try again."
    }
    override suspend fun getAllParticipantsWithPubKey(publicKey: HexKey): List<Participant> {
        return database.participantDao().findParticipantByPublicKey(publicKey)
    }

    override suspend fun findDirectMessageRoom(userPublicKey: HexKey, peerPublicKey: HexKey): LocalChatRoom? {
        val theTwoOfUs = setOf(userPublicKey, peerPublicKey)

        for (participant in database.participantDao().findParticipantByPublicKey(peerPublicKey)) {
            val localChatRoom = database.chatRoomDao().findChatRoomById(participant.chatRoomId) ?: continue
            val members = localChatRoom.localParticipants.map { it.participant.participantPublicKey }.toSet()
            if (members == theTwoOfUs) return localChatRoom
        }

        return null
    }
    override suspend fun getChatMessageListByChatRoomId(chatRoomId: String): List<LocalChatMessage> {
        return database.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId)
    }

    override suspend fun observeChatMessageListByChatRoomId(chatRoomId: String): Flow<List<LocalChatMessage>> {
        return database.chatMessageDao().observeChatMessagesByChatRoomId(chatRoomId)
    }


    override suspend fun getOrCreateChatRoom(
        chatRoomId: String,
        activeUserPublicKey: HexKey,
        relayHint: String?,
        defaultSubject: String?,
        description: String?,
        mlsGroupState: String?
    ): LocalChatRoom? = try {
        return database.nostrNip17Dao().getOrCreateChatRoom(
            chatRoomId = chatRoomId,
            activeUserPublicKey = activeUserPublicKey,
            relayHint = relayHint,
            defaultSubject = defaultSubject,
            description = description,
            mlsGroupState = mlsGroupState
        )
    } catch (e: Throwable) {
        logger.e("Error getting or creating chat", e)
        return null
    }

    override suspend fun getChatMessageRelayForPublicKey(publicKey: HexKey): DmRelayListEvent? {
        // TODO: Implement a UserChatMessageRelayList model
        return database.nostrEventDao().getAuthoredNostrEvents(
            kinds = arrayOf(
                DmRelayListEvent.KIND
            ),
            authors = arrayOf(
                publicKey
            ),
            since = GENESIS_AT,
            limit = 5
        ).firstOrNull()?.let { nostrEvent ->
            if (nostrEvent.kind != DmRelayListEvent.KIND) {
                logger.e("getAuthoredNostrEvents returned invalid DmRelayListEvent for ${publicKey}: $nostrEvent")
                null
            } else {
                DmRelayListEvent(
                    id = nostrEvent.id,
                    tags = nostrEvent.tags,
                    pubKey = nostrEvent.pubKey,
                    content = nostrEvent.content,
                    createdAt = nostrEvent.createdAt.epochSeconds,
                    sig = nostrEvent.sig
                )
            }
        }
    }

    override suspend fun getMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): MarmotKeyPackage? =
        when (roomProfile) {
            MarmotProfile.LEGACY -> database.marmotKeyPackageDao().getMarmotKeyPackageForPublicKey(publicKey)
            MarmotProfile.CURRENT -> database.marmotKeyPackageDao()
                .getMarmotKeyPackageForPublicKeyAndProfile(publicKey, MarmotProfile.CURRENT.wireName)
        }

    override suspend fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): Flow<MarmotKeyPackage?> =
        when (roomProfile) {
            MarmotProfile.LEGACY -> database.marmotKeyPackageDao().observeMarmotKeyPackageForPublicKey(publicKey)
            MarmotProfile.CURRENT -> database.marmotKeyPackageDao()
                .observeMarmotKeyPackageForPublicKeyAndProfile(publicKey, MarmotProfile.CURRENT.wireName)
        }

    override suspend fun createNip17ChatRoom(
        userPublicKey: HexKey,
        participantPublicKeys: List<HexKey>,
        subject: String?,
        description: String?
    ): LocalChatRoom? = try {
        database.nostrNip17Dao().createNip17ChatRoom(
            userPublicKey = userPublicKey,
            participantPublicKeys = participantPublicKeys,
            subject = subject,
            description = description
        )
    } catch (e: Throwable) {
        logger.e("Error creating nip-17 chat room", e)
        null
    }

    override suspend fun createMlsDirectMessage(
        name: String?,
        description: String?,
        userPublicKey: HexKey,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage
    ): String {
        // The active identity's key pair when it is the creator; otherwise a read-only
        // pair, which is enough for a legacy room and refused by the factory for a
        // current-profile one.
        val creator = activeIdentity.value
            ?.takeIf { it.nostrPublicKey.equals(userPublicKey, ignoreCase = true) }
            ?.toKeyPair()
            ?: KeyPair(privKey = null, pubKey = userPublicKey.hexToByteArray())

        return database.marmotOutboundDao().createMlsDirectMessageChatRoom(
            name = name,
            description = description,
            creator = creator,
            peerPublicKey = peerPublicKey,
            peerKeyPackage = peerKeyPackage
        )
    }

    override suspend fun recordMarmotRouting(chatRoomId: HexKey, routingId: HexKey) {
        database.marmotRoutingDao().upsert(MarmotRouting(routingId = routingId, chatRoomId = chatRoomId))
    }

    override suspend fun inviteMember(
        localChatRoom: LocalChatRoom,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage
    ) {
        try {
            database.marmotOutboundDao().inviteMemberToChatRoom(
                localChatRoom = localChatRoom,
                peerPublicKey = peerPublicKey,
                peerKeyPackage = peerKeyPackage
            )
        } catch (e: Throwable) {
            // Outside the transaction that just went down, which is the point: the
            // invite line `inviteMember` writes went with it, so an invite refused
            // for want of MLS state or over a mismatched credential left the room
            // with nothing at all to show. The caller still gets the throw and
            // still puts its own message on the invite screen -- this is the copy
            // that is still there tomorrow.
            logger.e("Failed to invite $peerPublicKey to ${localChatRoom.chatRoom.id}", e)
            runCatching {
                database.marmotOutboundDao().announceInviteFailed(
                    chatRoomId = localChatRoom.chatRoom.id,
                    userPublicKey = localChatRoom.chatRoom.userPublicKey,
                    peerPublicKey = peerPublicKey,
                    reason = e.message,
                )
            }.onFailure { logger.e("Failed to record the refused invite:", it) }
            throw e
        }
    }
    override suspend fun addMembers(
        localChatRoom: LocalChatRoom,
        peers: List<Pair<HexKey, MarmotKeyPackage>>
    ): List<HexKey> = database.marmotOutboundDao().addMembersToChatRoom(
        localChatRoom = localChatRoom,
        peers = peers
    )

    override suspend fun sendChatMessage(
        text: String,
        localChatRoom: LocalChatRoom,
        messageType: Kind,
        directMessageRecipientPublicKey: HexKey?
    ) {
        logger.i("sendChatMessage($text): $localChatRoom")
        val mlsGroup = localChatRoom.chatRoom.toMlsGroup()

        if (mlsGroup != null) {
            // Resolved before anything is written. A recipient outside the room cannot be
            // sent to -- the wrap would be undecryptable by every member including them,
            // while the group still saw that a private message had gone somewhere -- and
            // the room is also where the relay hint for the `p` tag comes from.
            val directMessageRecipient = directMessageRecipientPublicKey?.let { recipient ->
                require(recipient != localChatRoom.chatRoom.userPublicKey) {
                    "Cannot send a direct message to yourself: the wrap's throwaway key means it could never be read back"
                }

                localChatRoom.localParticipants
                    .map { it.participant }
                    .firstOrNull { it.participantPublicKey == recipient }
                    ?: throw IllegalArgumentException(
                        "Cannot send a direct message to $recipient: not a member of ${localChatRoom.chatRoom.id}"
                    )
            }

            // Create ChatRumor...
            val createdAt = Clock.System.now().epochSeconds
            val marmotInnerEventKind = when {
                // A direct message is a NIP-17 chat message that happens to travel inside
                // the group, so it keeps kind:14 instead of being mapped down to the
                // group's kind:9. That is also what tells the two apart on the way back in.
                directMessageRecipient != null -> ChatMessageEvent.KIND
                messageType == ChatMessageEvent.KIND -> ChatEvent.KIND // nip-17 chatMessageEvent.kind is in MLS chatMessage.kind
                else -> messageType
            }

            val marmotInnerEventTags = directMessageRecipient?.let { recipient ->
                arrayOf(
                    PTag.assemble(
                        recipient.participantPublicKey,
                        recipient.relayHint?.let { NormalizedRelayUrl(it) }
                    )
                )
            } ?: emptyArray()

            // For a direct message the queued row IS the rumor the wrap will carry, so
            // this id is the one the recipient arrives at after unwrapping -- and, on the
            // way out, the one the notary uses to find this chat message again. The wrap
            // built around it has its own id, which is why that lookup keys on the row.
            val marmotInnerEventId = EventHasher.hashId(
                pubKey = localChatRoom.chatRoom.userPublicKey,
                createdAt = createdAt,
                tags = marmotInnerEventTags,
                content = text,
                kind = marmotInnerEventKind
            )

            database.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = marmotInnerEventId,
                    publicKey = localChatRoom.chatRoom.userPublicKey,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = marmotInnerEventTags,
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    kind = marmotInnerEventKind,
                    directMessageRecipientPublicKey = directMessageRecipient?.participantPublicKey
                )
            )

            database.chatMessageDao().upsert(
                ChatMessage(
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    senderPublicKey = localChatRoom.chatRoom.userPublicKey,
                    isUserMessage = true,
                    giftWrapPayloadId = null,
                    marmotGroupEventId = null,
                    marmotInnerEventId = marmotInnerEventId,
                    // The sender's only copy: the wrap is sealed with a key that is
                    // discarded, so nothing can reconstruct these words from the
                    // transcript later, on this device or any other.
                    messageType = if (directMessageRecipient != null) {
                        ChatMessage.TYPE_DIRECT_MESSAGE
                    } else {
                        "message"
                    },
                    directMessageRecipientPublicKey = directMessageRecipient?.participantPublicKey
                )
            )
        } else {
            // Send GiftWrapMessage
            val receiverTags = localChatRoom.localParticipants.filter { participant ->
                participant.participant.participantPublicKey != localChatRoom.chatRoom.userPublicKey // TODO: Allow this where it's notes to self...
            }.map { localParticipant ->
                PTag.assemble(
                    localParticipant.participant.participantPublicKey,
                    localParticipant.participant.relayHint?.let { NormalizedRelayUrl(it) }
                )
            }

            val createdAt = Clock.System.now().epochSeconds
            val giftWrapPayloadId = EventHasher.hashId(
                pubKey = localChatRoom.chatRoom.userPublicKey,
                createdAt = createdAt,
                tags = receiverTags.toTypedArray(),
                content = text,
                kind = messageType
            )

            database.giftWrapPayloadDao().upsert(
                GiftWrapPayload(
                    id = giftWrapPayloadId,
                    kind = messageType,
                    tags = receiverTags.toTypedArray(),
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    publicKey = localChatRoom.chatRoom.userPublicKey
                )
            )

            database.chatMessageDao().upsert(
                ChatMessage(
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    senderPublicKey = localChatRoom.chatRoom.userPublicKey,
                    isUserMessage = true,
                    giftWrapPayloadId = giftWrapPayloadId,
                    marmotGroupEventId = null,
                    marmotInnerEventId = null
                )
            )
        }
    }

    override fun updateChatRoomSubject(
        publicKey: String,
        subject: String
    ): com.machankura.dedun.compose.database.model.ChatRoom? {
        // TODO: Update chatRoom with subject...
        return null
    }

    override suspend fun observeUnsealedGiftWrapPayloads(publicKey: HexKey): Flow<List<GiftWrapPayload>> {
        return database.giftWrapPayloadDao().observeUnsealedGiftWrapPayloads(publicKey)
    }

    override suspend fun sealGiftWrapPayload(
        giftWrapPayload: GiftWrapPayload,
        nostrSignerSync: NostrSignerSync
    ) {
        // An MLS room must never produce a NIP-17 gift wrap of its *contents*. Its messages
        // already travel inside kind:445, and its direct messages are real, correctly signed
        // NIP-59 wraps -- indistinguishable from something this path would be right to
        // publish. The only thing keeping one off a relay is that it never becomes a
        // GiftWrapPayload, so refuse here too rather than trusting every future caller to
        // know that. See docs/marmot-direct-messages.md.
        //
        // The Welcome is the one thing an MLS room is *supposed* to publish this way, and
        // the reason it reaches this path at all. MIP-02 addresses kind:444 to a joiner who
        // holds no group state yet: they cannot read a kind:445, so a relay-borne gift wrap
        // is the only way to reach them, and `MarmotOutboundDao.deliveryWelcome` queues one
        // here deliberately. Every Welcome carries its room's nostrGroupId, so a refusal
        // keyed on `mlsGroupState` alone catches all of them and no invite is ever
        // delivered. The refusal below no longer strands the rest of the queue behind it
        // -- the notary is handed the whole backlog and refuses one payload at a time --
        // but a refused Welcome is still an invite that never arrives, so the exception
        // for it stays.
        if (giftWrapPayload.kind != WelcomeEvent.KIND) {
            val chatRoom = database.chatRoomDao().findChatRoomById(giftWrapPayload.chatRoomId)
            if (chatRoom?.chatRoom?.mlsGroupState != null) {
                logger.e(
                    "Refusing to seal payload ${giftWrapPayload.id}: ${giftWrapPayload.chatRoomId} is an MLS room, " +
                        "and sealing would broadcast it to relays"
                )
                return
            }
        }

        database.participantDao().findParticipantsByChatRoomId(giftWrapPayload.chatRoomId).forEach { participant ->
            if (giftWrapPayload.kind == WelcomeEvent.KIND) {
                logger.i("Only giftWrap welcomeEvent payload (${giftWrapPayload.id}) to the participant who published the related keyPackage")
                val welcomeEvent = WelcomeEvent(
                    id = giftWrapPayload.id,
                    pubKey = nostrSignerSync.pubKey,
                    tags = giftWrapPayload.tags,
                    content = giftWrapPayload.content,
                    createdAt = giftWrapPayload.createdAt.epochSeconds,
                    sig = ""
                )

                val shouldBeGiftWrapped: Boolean = welcomeEvent.keyPackageEventId()?.let { keyPackageEventId ->
                    val marmotKeyPackage = database.marmotKeyPackageDao().getMarmotKeyPackageById(
                        keyPackageEventId
                    )

                    logger.i("MarmoteKeyPackage published by ${marmotKeyPackage?.publicKey}")

                    marmotKeyPackage?.publicKey == participant.participantPublicKey
                } ?: false

                if (!shouldBeGiftWrapped) {
                    logger.w("Participant ${participant.participantPublicKey} didn't publish key package for this welcome event")

                    return@forEach
                }
            }

            val wrapperKeyPair = KeyPair()
            val wrapperSigner = NostrSignerSync(
                keyPair = wrapperKeyPair
            )

            // TODO: Don't sign the payload... use event template instead...
            val giftWrapPayloadRumor = RumorAssembler.assembleRumor(
                pubKey = nostrSignerSync.pubKey,
                ev = EventTemplate(
                    createdAt = giftWrapPayload.createdAt.epochSeconds,
                    kind = giftWrapPayload.kind,
                    tags = giftWrapPayload.tags,
                    content = giftWrapPayload.content
                )
            )
            logger.d("GiftWrap Payload Rumor: ${giftWrapPayloadRumor.toJson()}")

            logger.d("Seal to $participant")
            val payload = giftWrapPayloadRumor.toJson()

            val sealedRumorEvent = nostrSignerSync.signNormal<SealEvent>(
                createdAt = TimeUtils.randomWithTwoDays(),
                kind = SealEvent.KIND,
                tags = emptyArray(),
                content = nostrSignerSync.nip44Encrypt(
                    plaintext = payload,
                    toPublicKey = participant.participantPublicKey
                )
            )
            logger.d("SealEvent: ${sealedRumorEvent.toJson()}")

            val giftWrapEvent = wrapperSigner.signNormal<GiftWrapEvent>(
                createdAt = TimeUtils.randomWithTwoDays(),
                kind = GiftWrapEvent.KIND,
                tags = arrayOf(
                    PTag.assemble(participant.participantPublicKey, participant.relayHint?.let { NormalizedRelayUrl(it) })
                ),
                content = wrapperSigner.nip44Encrypt(
                    plaintext = sealedRumorEvent.toJson(),
                    toPublicKey = participant.participantPublicKey
                )
            )
            logger.d("giftWrapEvent: ${giftWrapEvent.toJson()}")

            database.nostrNip17Dao().persistAndBroadcastGiftWrap(
                giftWrapPayload = giftWrapPayload,
                sealedRumorEvent = sealedRumorEvent,
                giftWrapEvent = giftWrapEvent,
                receiverPublicKey = PTag(
                    pubKey = participant.participantPublicKey,
                    relayHint = participant.relayHint?.let { NormalizedRelayUrl(it) }
                )
            )
        }
    }

    override suspend fun leaveChatRoom(
        localChatRoom: LocalChatRoom,
        onCompletion: () -> Unit
    ) {
        // A current-profile room is left by a SelfRemove proposal the admins commit,
        // the local state dropped and the room closed here -- see MarmotOutboundDao.leave.
        // A legacy room, or one this device holds no state for, is left the way it
        // always was: the flag, and a line.
        if (localChatRoom.chatRoom.marmotProfile() == MarmotProfile.CURRENT && localChatRoom.chatRoom.leftGroupAt == null) {
            database.marmotOutboundDao().leave(localChatRoom)
            onCompletion.invoke()
            return
        }

        database.chatRoomDao().upsert(
            chatRoom = localChatRoom.chatRoom.copy(
                leftGroupAt = Clock.System.now()
            )
        )

        // TODO: Process this in the notary...
        sendChatMessage(
            text = "Left group",
            localChatRoom = localChatRoom,
            // TODO: Add Information/LeaveChatGroup Message Type...
        )

        onCompletion.invoke()
    }

    override suspend fun reconcileAdmins(chatRoomId: String): Boolean {
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return false
        // A row whose state this build cannot read has no admin set to agree with.
        val group = runCatching { room.chatRoom.toMlsGroup() }.getOrNull() ?: return false
        return MarmotInboundManager.reconcileAdmins(database, chatRoomId, group)
    }

    override suspend fun adminFloor(chatRoomId: String): Int {
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return 1
        val group = runCatching { room.chatRoom.toMlsGroup() }.getOrNull() ?: return 1
        return AdminGovernance.adminFloor(database, chatRoomId, group)
    }

    override suspend fun governanceStatus(chatRoomId: String): GovernanceStatus? {
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return null
        if (room.chatRoom.marmotProfile() != MarmotProfile.CURRENT) return null
        val group = database.marmotEngine.groupFor(room.routingId) ?: return null
        if (!AdminGovernance.isKeyedRoom(group)) return null
        GovernanceGate.minimumAdmins(group)?.let { return GovernanceStatus.Governed(it) }
        if (room.chatRoom.leftGroupAt == null) GovernanceSupport.announce(database, chatRoomId, group, room.chatRoom.userPublicKey)
        val missing = GovernanceSupport.missing(database, chatRoomId, group)
        return if (missing.isEmpty()) GovernanceStatus.Ready(AdminGovernance.adminFloor(database, chatRoomId, group), GovernanceSupport.switcherOf(group))
        else GovernanceStatus.Waiting(missing)
    }

    override suspend fun memberActionOptions(localChatRoom: LocalChatRoom): Map<HexKey, List<MemberActionOption>> {
        val chatRoom = localChatRoom.chatRoom
        val me = chatRoom.userPublicKey
        val rows = localChatRoom.localParticipants.map { it.participant.participantPublicKey }
        val hasLeft = chatRoom.leftGroupAt != null
        // Off the current profile there is no admin policy to read: the room's kind
        // is the whole answer, and the participants' flags say which role to name.
        val flaggedAdmins = localChatRoom.localParticipants
            .filter { it.participant.adminAt != null }
            .map { it.participant.participantPublicKey }
            .toSet()
        val context = when {
            chatRoom.mlsGroupState == null ->
                MemberActionContext(MemberActionRoom.NIP17, me, admins = flaggedAdmins, members = emptySet(), hasLeft = hasLeft)
            chatRoom.marmotProfile() != MarmotProfile.CURRENT ->
                MemberActionContext(MemberActionRoom.LEGACY, me, admins = flaggedAdmins, members = emptySet(), hasLeft = hasLeft)
            else -> {
                // A row whose state this build cannot read has no rules to read either.
                val group = runCatching { chatRoom.toMlsGroup() }.getOrNull() ?: return emptyMap()
                val context = AdminGovernance.context(group, me, AdminGovernance.adminFloor(database, chatRoom.id, group))
                val engine = database.marmotEngine
                val heldWallet = if (context.isKeyed) WalletKeysHeld.of(database, chatRoom.id) else null
                context.copy(
                    hasLeft = hasLeft,
                    // Only asked when it is the answer: a keyed room not yet governed.
                    waitingOn = if (context.isKeyed && !context.governed) GovernanceSupport.missing(database, chatRoom.id, group) else emptySet(),
                    // Work in flight, which clears by itself: the gate holding an earlier
                    // commit until a relay accepts it, and offers nobody has answered.
                    // docs/member-row-actions.md, Phase 5.
                    changePending = engine.groupFor(localChatRoom.routingId) != null &&
                        !engine.gate.canPrepareLocalCommit(localChatRoom.routingId),
                    offeredAdmin = if (context.isKeyed) PromotionManager.unansweredOfferTargets(database, chatRoom.id) else emptySet(),
                    // Dedun's: no admin change in a room with a Lightning or Wavelength wallet (D4).
                    hasLightningWallet = context.isKeyed && heldWallet == WalletKeysHeld.Wallet.LIGHTNING,
                    hasWavelengthWallet = context.isKeyed && heldWallet == WalletKeysHeld.Wallet.WAVELENGTH,
                ).let { if (it.isKeyed) withShares(localChatRoom, it) else it }
            }
        }
        return MemberActionPolicy.options(context, rows)
    }

    override suspend fun requireSignatures(localChatRoom: LocalChatRoom) {
        database.marmotOutboundDao().requireSignatures(localChatRoom)
    }

    /**
     * [context] with what the room's shares say about its member rows: a rotation
     * still under way, and whom it waits on, and who holds a live share.
     * docs/admin-change-rotation.md, Phase 3.
     */
    private suspend fun withShares(localChatRoom: LocalChatRoom, context: MemberActionContext): MemberActionContext {
        val key = AdminGovernance.keyOf(database, localChatRoom.chatRoom.id) ?: return context
        val holders = ShareGenerations.active(database, key).roster
            .filter { it.standing == ShareStanding.LIVE }
            .map { it.memberPublicKey }
            .toSet()
        val pending = AdminGovernance.adminChangeHold(database, localChatRoom)?.let { hold ->
            val waitingOn = (hold as? RotationHold.Running)
                ?.let { database.shareRefreshDao().getSessionById(it.refreshId) }
                ?.let { ShareRefreshManager.progress(database, it)?.waitingOn }
                .orEmpty()
            MemberActionBlocker.RotationPending(waitingOn.toSet())
        }
        return context.copy(rotationPending = pending, shareHolders = holders)
    }

    override suspend fun promoteMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey) {
        refuseAdminChangeOverWallets(localChatRoom)
        // The key, for the rotation a first promotion starts (docs/admin-change-rotation.md, Phase 6).
        database.marmotOutboundDao().promote(localChatRoom, memberPublicKey, privateKeyOrNull()?.second)
    }

    override suspend fun demoteMember(localChatRoom: LocalChatRoom, adminPublicKey: HexKey) {
        refuseAdminChangeOverWallets(localChatRoom)
        database.marmotOutboundDao().demote(localChatRoom, adminPublicKey)
    }

    /**
     * Dedun's: a keyed room with a Lightning or Wavelength wallet changes no admins (D4 in
     * docs/giza-into-dedun.md, and [WalletKeysHeld]). The menu says so first
     * (`MemberActionBlocker.LightningWalletHeld`, `WavelengthWalletHeld`), and
     * `MarmotOutboundDao` holds to it where the change is made; this says it before
     * anything is sent.
     */
    private suspend fun refuseAdminChangeOverWallets(localChatRoom: LocalChatRoom) {
        if (AdminGovernance.keyOf(database, localChatRoom.chatRoom.id) == null) return
        WalletKeysHeld.refuseAdminChange(database, localChatRoom.chatRoom.id)
    }

    override suspend fun pendingPromotion(chatRoomId: String): PromotionManager.Offer? {
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return null
        return PromotionManager.pendingOffer(database, chatRoomId, room.chatRoom.userPublicKey)
    }

    override suspend fun acceptPromotion(localChatRoom: LocalChatRoom, offerId: HexKey): String? {
        val identity = activeIdentity.value ?: return "No identity is signed in"
        val nostrPrivateKey = identity.toKeyPair().privKey ?: return "This identity cannot sign"
        return runCatching {
            PromotionManager.accept(database, localChatRoom, identity.nostrPublicKey, nostrPrivateKey, offerId)
        }.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "Unknown error" }
    }

    override suspend fun stepDownAndLeave(localChatRoom: LocalChatRoom, heir: HexKey?) {
        database.marmotOutboundDao().stepDownAndLeave(localChatRoom, heir)
    }

    override suspend fun removeMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey) {
        database.marmotOutboundDao().removeMember(localChatRoom, memberPublicKey)
    }

    override suspend fun holdsShare(localChatRoom: LocalChatRoom, memberPublicKey: HexKey): Boolean =
        ExperimentalFeatures.shareRefresh &&
            runCatching { ShareRefreshManager.liveIdOf(database, localChatRoom, memberPublicKey) != null }.getOrDefault(false)

    /** The key the room's Shares section is about: the one its key state names, else the one this device signs it with. */
    private suspend fun shareKeyOf(localChatRoom: LocalChatRoom): DkgSession? {
        val roomId = localChatRoom.chatRoom.id
        return GroupKeyStateManager.keyStateFor(database, roomId)?.dkgSessionId?.let { database.dkgSessionDao().getSessionById(it) }
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
            ?: FrostSigningManager.completedKey(database, roomId)
    }

    override suspend fun sharesSummary(localChatRoom: LocalChatRoom): SharesSummary? = runCatching {
        if (localChatRoom.chatRoom.mlsGroupState == null) return null
        val key = shareKeyOf(localChatRoom) ?: return null
        val me = localChatRoom.chatRoom.userPublicKey
        val shares = ShareGenerations.active(database, key)
        if (shares.roster.isEmpty()) return null
        val names = localChatRoom.localParticipants.associate { it.participant.participantPublicKey to it.profile?.humanReadableNameOrPubkey() }
        fun nameOf(member: HexKey) = names[member] ?: member.take(8)
        val refreshes = database.shareRefreshDao().getSessionsForKey(key.id)
        val repairs = database.shareRepairDao().getSessionsForKey(key.id)
        val pending = refreshes.mapNotNull { session ->
            when (ShareRefreshManager.pendingApproval(database, session)) {
                ShareRefreshApprovalStep.DEAL -> {
                    val roster = ShareGenerations.at(database, key, session.baseGeneration)?.roster.orEmpty()
                        .associate { it.participantId to it.memberPublicKey }
                    SharesSummary.PendingApproval(
                        sessionId = session.id,
                        kind = SharesSummary.PendingApproval.Kind.REFRESH_DEAL,
                        withheld = session.withheldStandings().map { (id, standing) ->
                            SharesSummary.Withheld(roster[id]?.let(::nameOf) ?: id.toString(), standing)
                        },
                    )
                }
                // Never pending since docs/rotate-shares-single-approval.md.
                ShareRefreshApprovalStep.CONFIRM, null -> null
            }
        } + repairs.filter { ShareRepairManager.isAwaitingApproval(database, it) }
            .map { SharesSummary.PendingApproval(it.id, SharesSummary.PendingApproval.Kind.REPAIR_HELP) }
        val runningRefresh = refreshes.firstOrNull { it.stage != ShareRefreshStage.COMPLETE && it.stage != ShareRefreshStage.FAILED }
        val running = runningRefresh?.let { session ->
            // The approval row covers a member not yet asked; this is everything after it.
            if (ShareRefreshManager.pendingApproval(database, session) != null) return@let null
            ShareRefreshManager.progress(database, session)?.let { progress ->
                SharesSummary.Running(
                    sessionId = session.id,
                    step = when (progress.step) {
                        ShareRefreshManager.Progress.Step.DEALING -> SharesSummary.Running.Step.DEALING
                        ShareRefreshManager.Progress.Step.STORING -> SharesSummary.Running.Step.STORING
                    },
                    waitingOn = progress.waitingOn.map(::nameOf),
                    since = progress.since,
                    canAbandon = progress.canAbandon,
                    takingAway = takingAway(localChatRoom, key, session).associateWith(::nameOf),
                )
            }
        } ?: signingNewGeneration(localChatRoom, key, shares.generation, refreshes)
        // The roster has a row per participant id, and a reinstated admin has two.
        val members = SharesSummary.byMember(
            shares.roster.map { entry ->
                SharesSummary.Member(entry.memberPublicKey, nameOf(entry.memberPublicKey), entry.standing, entry.memberPublicKey == me)
            }
        )
        val mine = members.firstOrNull { it.isSelf }
        val repairing = repairs.any { it.targetPublicKey == me && it.stage != ShareRepairStage.FAILED && it.stage != ShareRepairStage.COMPLETE }
        SharesSummary(
            generation = shares.generation,
            members = members,
            pending = if (ExperimentalFeatures.shareRefresh) pending else emptyList(),
            rotation = if (!ExperimentalFeatures.shareRefresh || shares.secretShare == null) {
                SharesSummary.Rotation.Unavailable
            } else {
                when (val hold = ShareRefreshManager.rotationHold(database, localChatRoom, key)) {
                    null -> SharesSummary.Rotation.Available
                    is RotationHold.Running -> SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.ROTATION_RUNNING)
                    is RotationHold.AwaitingKeyState -> SharesSummary.Rotation.Held(
                        SharesSummary.Rotation.Reason.AWAITING_SIGNATURES,
                        canAskForSignature = hold.signingSessionId == null,
                    )
                    is RotationHold.Behind -> SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.BEHIND)
                    is RotationHold.SpendOpen -> SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.SPEND_OPEN)
                    is RotationHold.StatementOpen -> SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.STATEMENT_OPEN)
                    is RotationHold.AdminChangeInFlight -> SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.ADMIN_CHANGE)
                    is RotationHold.Enrolling -> SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.ENROLLING, who = nameOf(hold.memberPublicKey))
                }
            },
            canAskToBeRepaired = ExperimentalFeatures.shareRefresh && mine?.standing == ShareStanding.STALE && !repairing,
            running = running.takeIf { ExperimentalFeatures.shareRefresh },
            lastFailure = refreshes.firstOrNull()?.takeIf { it.stage == ShareRefreshStage.FAILED }?.failureReason
                ?.takeIf { ExperimentalFeatures.shareRefresh },
        )
    }.getOrNull()

    override fun observeSharesInputs(chatRoomId: String): Flow<Unit> = combine(
        database.groupKeyStateDao().observeByChatRoomId(chatRoomId),
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId),
        database.shareRefreshDao().observeSessionsForRoom(chatRoomId),
    ) { _, _, _ -> }

    /**
     * Whom [session] withholds for good because the group's signature took them out
     * of the admins: the cause the running card names (docs/admin-change-rotation.md,
     * Phase 7).
     */
    private suspend fun takingAway(localChatRoom: LocalChatRoom, key: DkgSession, session: ShareRefreshSession): List<HexKey> {
        val admins = RoomAdmins.of(database, localChatRoom.chatRoom.id) ?: return emptyList()
        val roster = ShareGenerations.at(database, key, session.baseGeneration)?.roster.orEmpty()
            .associate { it.participantId to it.memberPublicKey }
        return session.withheldStandings()
            .filterValues { it == ShareStanding.RETIRED }
            .keys.mapNotNull { roster[it] }
            .filter { admins.removedBySignature(it) }
    }

    /**
     * The last step of a rotation: complete, and the group signing the new
     * generation's key state, which the old shares wait on before they are
     * deleted. Shown only while that signature is actually open, so a key state
     * nobody is asking for does not read as one under way.
     */
    private suspend fun signingNewGeneration(
        localChatRoom: LocalChatRoom,
        key: DkgSession,
        generation: Int,
        refreshes: List<ShareRefreshSession>,
    ): SharesSummary.Running? {
        val refresh = refreshes.firstOrNull { it.stage == ShareRefreshStage.COMPLETE && it.baseGeneration + 1 == generation } ?: return null
        val signed = GroupKeyStateManager.signedStateFor(database, localChatRoom.chatRoom.id)
        if ((signed?.generation ?: 0) >= generation) return null
        val signing = database.frostSigningSessionDao()
        val open = signing.getOpenSessionsForKey(key.id).firstOrNull { session ->
            (session.generation ?: 0) == generation && signing.getItems(session.id).singleOrNull()
                ?.let { Event.fromJsonOrNull(it.unsignedEventJson)?.kind } == GroupKeyStateEvent.KIND
        } ?: return null
        return SharesSummary.Running(
            sessionId = refresh.id,
            step = SharesSummary.Running.Step.SIGNING,
            waitingOn = emptyList(),
            since = open.createdAt,
            canAbandon = false,
        )
    }

    private fun failureOf(t: Throwable?): String? = t?.let { it.message ?: it::class.simpleName ?: "Unknown error" }

    private fun privateKeyOrNull(): Pair<HexKey, ByteArray>? {
        val identity = activeIdentity.value ?: return null
        val privateKey = identity.toKeyPair().privKey ?: return null
        return identity.nostrPublicKey to privateKey
    }

    override suspend fun rotateShares(localChatRoom: LocalChatRoom): String? {
        val (me, privateKey) = privateKeyOrNull() ?: return "This identity cannot sign"
        val key = shareKeyOf(localChatRoom) ?: return "This group has no shared key"
        return failureOf(runCatching { ShareRefreshManager.proposeRefresh(database, localChatRoom, me, privateKey, key) }.exceptionOrNull())
    }

    override suspend fun rotationPreview(localChatRoom: LocalChatRoom, without: HexKey?): RotationPreview? = runCatching {
        if (!ExperimentalFeatures.shareRefresh) return null
        val me = localChatRoom.chatRoom.userPublicKey
        val preview = ShareRefreshManager.preview(database, localChatRoom, me, without) ?: return null
        val names = localChatRoom.localParticipants.associate { it.participant.participantPublicKey to it.profile?.humanReadableNameOrPubkey() }
        fun nameOf(member: HexKey) = names[member] ?: member.take(8)
        RotationPreview(
            takingPart = preview.takingPart.map(::nameOf),
            untilBroughtBack = preview.withheld.filterValues { it == ShareStanding.STALE }.keys.map(::nameOf),
            forGood = preview.withheld.filterValues { it == ShareStanding.RETIRED }.keys.map(::nameOf),
            refusal = preview.refusal,
        )
    }.getOrNull()

    override suspend fun approveShares(localChatRoom: LocalChatRoom, approval: SharesSummary.PendingApproval): String? {
        val (_, privateKey) = privateKeyOrNull() ?: return "This identity cannot sign"
        return failureOf(runCatching {
            when (approval.kind) {
                SharesSummary.PendingApproval.Kind.REFRESH_DEAL ->
                    ShareRefreshManager.approve(database, localChatRoom, approval.sessionId, privateKey)
                SharesSummary.PendingApproval.Kind.REPAIR_HELP ->
                    ShareRepairManager.approve(database, localChatRoom, approval.sessionId, privateKey)
            }
        }.exceptionOrNull())
    }

    override suspend fun proposeKeyState(localChatRoom: LocalChatRoom): String? {
        val (me, _) = privateKeyOrNull() ?: return "This identity cannot sign"
        val key = shareKeyOf(localChatRoom) ?: return "This group has no shared key"
        return failureOf(runCatching { ShareRefreshManager.proposeOwedKeyState(database, localChatRoom, me, key) }.exceptionOrNull())
    }

    override suspend fun abandonRotation(localChatRoom: LocalChatRoom, sessionId: String): String? =
        failureOf(runCatching { ShareRefreshManager.abandon(database, localChatRoom, sessionId) }.exceptionOrNull())

    override suspend fun askToBeRepaired(localChatRoom: LocalChatRoom): String? {
        val (me, privateKey) = privateKeyOrNull() ?: return "This identity cannot sign"
        val key = shareKeyOf(localChatRoom) ?: return "This group has no shared key"
        return failureOf(runCatching { ShareRepairManager.requestRepair(database, localChatRoom, me, privateKey, key) }.exceptionOrNull())
    }

    override suspend fun rotateSharesWithout(localChatRoom: LocalChatRoom, memberPublicKey: HexKey): String? {
        val identity = activeIdentity.value ?: return "No identity is signed in"
        val nostrPrivateKey = identity.toKeyPair().privKey ?: return "This identity cannot sign"
        return runCatching {
            ShareRefreshManager.proposeWithout(database, localChatRoom, identity.nostrPublicKey, nostrPrivateKey, memberPublicKey)
        }.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "Unknown error" }
    }

    override suspend fun disbandChatRoom(localChatRoom: LocalChatRoom) {
        database.marmotOutboundDao().disband(localChatRoom)
    }

    override suspend fun softDeleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    ) {
        database.chatRoomDao().upsert(
            chatRoom.copy(
                deletedAt = Clock.System.now()
            )
        )

        onCompletion.invoke()
    }

    override suspend fun deleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    ) {
        database.chatRoomDao().delete(chatRoom)

        onCompletion.invoke()
    }

    /**
     * Only the public key is needed: reading a group event is done with the MLS
     * group's own secrets, held in the room's stored state, and the identity is
     * used for nothing but deciding whether this device is a member and whether a
     * line is its own. So a read-only [KeyPair] is enough, and the nostr private
     * key stays where it is.
     */
    override suspend fun reindexMarmotGroupEvents(
        chatRoomId: String,
        userPublicKey: HexKey
    ): MarmotReindexReport =
        database.nostrDao().reindexMarmotGroupEvents(
            chatRoomId = chatRoomId,
            activeKeyPair = KeyPair(pubKey = userPublicKey.hexToByteArray()),
        )

    companion object {
        const val TAG = "DatabaseChatRepository"
    }
}
