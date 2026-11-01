package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import com.machankura.dedun.compose.exceptions.MarmotUnsupportedWireFormatException
import com.machankura.dedun.compose.extensions.exporterSecret
import com.machankura.dedun.compose.extensions.toHex
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.quartz.marmot.mls.codec.TlsReader
import com.vitorpamplona.quartz.marmot.mls.codec.TlsWriter
import com.vitorpamplona.quartz.marmot.mls.crypto.MlsCryptoProvider
import com.vitorpamplona.quartz.marmot.mls.framing.ContentType
import com.vitorpamplona.quartz.marmot.mls.framing.MlsMessage
import com.vitorpamplona.quartz.marmot.mls.framing.PrivateMessage
import com.vitorpamplona.quartz.marmot.mls.framing.PublicMessage
import com.vitorpamplona.quartz.marmot.mls.framing.WireFormat
import com.vitorpamplona.quartz.marmot.mls.group.DecryptedMessage
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.group.RetainedEpochSecrets
import com.vitorpamplona.quartz.marmot.mls.schedule.KeySchedule
import com.vitorpamplona.quartz.marmot.mls.schedule.SecretTree
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.MarmotDirectMessage
import com.machankura.dedun.compose.nostr.Relays
import kotlin.time.Clock

object MarmotInboundManager {
    private const val TAG = "MarmotInboundManager"

    private val logger = Logger.withTag(TAG)


    /**
     * Number of past epochs to retain for late-arriving message decryption.
     * Matches MDK's `DEFAULT_EPOCH_LOOKBACK` so a message encrypted under
     * the prior N epochs' exporter secrets can still be decrypted after a
     * Commit advances the group. Capped for forward-secrecy reasons.
     */
    const val EPOCH_RETENTION_WINDOW = 5


    /** Size of reuse_guard in PrivateMessage (RFC 9420 §6.3.1) */
    private const val REUSE_GUARD_LENGTH = 4

    private val retainedEpochs = mutableMapOf<HexKey, MutableList<RetainedEpochSecrets>>()

    private val commitTracker = CommitOrdering.EpochCommitTracker()

    /**
     * Why an inner application event may not be accepted from this sender, or null.
     *
     * An inner event's `pubkey` MUST equal the MLS sender's credential identity, so a
     * member cannot mint events claiming a different author. MIP-03 said so and
     * `foundation/application-messages.md` says the same.
     *
     * In a legacy room a gift wrap is exempt. Its pubkey is a throwaway key by
     * construction -- see [MarmotDirectMessage] -- so there is no author field to
     * compare and the check cannot be applied as written. The authorship claim moves
     * inside, to the signed kind:13 seal, which [MarmotDirectMessage.classify] binds to
     * this same identity before a word is rendered: a verified signature bound to an
     * MLS leaf, rather than a plaintext field compared to one.
     *
     * The exemption is on kind:1059 alone, and on legacy rooms alone. Widening it to any
     * other kind, or dropping the kind guard, hands every member the ability to publish
     * events as anybody. A current-profile room never carries a bare wrap -- a direct
     * message there travels inside an envelope authored by the sender, which passes the
     * check like any other payload -- so a bare wrap arriving in one is what every other
     * conformant client says it is: an author that is not the sender, refused. Other
     * Marmot clients have no carve-out at all and drop legacy wraps as impersonation.
     * See docs/marmot-direct-messages.md and docs/marmot-v2.md, Phase 2.
     *
     * [senderIdentity] is required rather than merely compared: every sender-derived
     * field downstream reads from it -- who a message is from, and whether it is ours --
     * because a gift wrap payload carries no author of its own.
     */
    fun mip03Rejection(
        innerEventKind: Int,
        innerEventPubKey: HexKey,
        senderIdentity: HexKey?,
        legacyRoom: Boolean,
    ): String? {
        if (senderIdentity == null) {
            return "No MLS credential identity for the sender leaf"
        }

        val bareWrapInLegacyRoom = legacyRoom && innerEventKind == GiftWrapEvent.KIND
        if (!bareWrapInLegacyRoom && innerEventPubKey != senderIdentity) {
            return "MIP-03: inner event pubkey ($innerEventPubKey) does not match MLS sender identity ($senderIdentity)"
        }

        return null
    }

    /**
     * Brings the room's members into line with the group's tree after a commit, and
     * says what changed.
     *
     * The lines are derived from the difference, never received -- see
     * [ChatMessage.TYPE_MEMBER_ADDED]. [announceAdditions] is false for a commit this
     * device made itself, whose invite lines were written when the invites were
     * made. Departures are always captioned, on every device including the one that
     * committed them: the caption is the state's, not the actor's.
     *
     * Two changes are about this device rather than the room. A commit that removed
     * our own leaf, or that disbanded the group, ends the room for us: the line says
     * so and `leftGroupAt` is set, which is what stops the subscriptions and greys
     * the room out. Both are a terminal state on the other side too -- a removed
     * member cannot decrypt the next epoch, and a disbanded group refuses every
     * later event -- so nothing is lost by closing here.
     */
    suspend fun processGroupMembershipChanges(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        mlsGroup: MlsGroup,
        announceAdditions: Boolean = true,
    ) {
        logger.d("processGroupMembershipChanges")
        val adminPublicKeys = mlsGroup.currentAdminIdentities()

        val commitedGroupMembers = Participant.fromMlsGroup(
            mlsGroup = mlsGroup,
            nostrGroupId = localChatRoom.chatRoom.id,
            relayHint = mlsGroup.roomRelays().firstOrNull()
        )
        val committedGroupMembersPublicKeys = commitedGroupMembers.map { it.participantPublicKey }
        val excludedParticipants = localChatRoom.localParticipants.filter { localParticipant ->
            committedGroupMembersPublicKeys.contains(localParticipant.participant.participantPublicKey).not()
        }.map { localParticipant ->
            localParticipant.participant.copy(
                deletedAt = Clock.System.now()
            )
        }
        logger.d("excludedParticipants: $excludedParticipants")
        val existingParticipantPublicKeys = localChatRoom.localParticipants.map { it.participant.participantPublicKey }
        val newParticipants = commitedGroupMembers.filter { committedGroupMember ->
            existingParticipantPublicKeys.contains(committedGroupMember.participantPublicKey).not()
        }.map { newParticipant ->
            newParticipant.copy(
                adminAt = if (adminPublicKeys.contains(newParticipant.participantPublicKey)) {
                    Clock.System.now()
                } else {
                    null
                }
            )
        }
        logger.d("newParticipants: $newParticipants")
        val removedAdmins = localChatRoom.localParticipants.filter { localParticipant ->
            localParticipant.participant.adminAt != null && !adminPublicKeys.contains(localParticipant.participant.participantPublicKey)
        }.map { localParticipant ->
            localParticipant.participant.copy(
                adminAt = null
            )
        }
        logger.d("removedAdmins: $removedAdmins")

        val graduatedAdmins = localChatRoom.localParticipants.filter { localParticipant ->
            localParticipant.participant.adminAt == null && adminPublicKeys.contains(localParticipant.participant.participantPublicKey)
        }.map { localParticipant ->
            localParticipant.participant.copy(
                adminAt = Clock.System.now()
            )
        }
        logger.d("graduatedAdmins: $graduatedAdmins")

        // Placeholder profiles for new participants...
        newParticipants.forEach { newParticipant ->
            logger.d("New Participant: ${newParticipant.participantPublicKey}")
            val profile = database.profileDao().getProfileByPublicKey(newParticipant.participantPublicKey)

            // A placeholder row is not a profile: it is the "LOADING..." name and a
            // GENESIS_AT stamp, standing in until a kind:0 arrives. So a member we
            // already hold one for still needs asking about -- one removed and added
            // again, or one whose last ask came back empty, would otherwise keep the
            // placeholder for the life of the room. Only a resolved profile is done.
            if (profile != null && profile.createdAt > GENESIS_AT) return@forEach

            if (profile == null) {
                // Create placeholder profile...
                val nostrEvent = database.nostrEventDao().getNostrEvents(
                    kinds = arrayOf(GroupEvent.KIND),
                    since = GENESIS_AT,
                    limit = 1
                )

                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = newParticipant.participantPublicKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.first().id,  // Will hopefully get overwriting by sync,
                    )
                )
            }

            // Schedule sync for profile...
            val synchronizationFilter = SynchronizationFilter(
                authors = arrayOf(
                    newParticipant.participantPublicKey
                ),
                kinds = arrayOf(
                    MetadataEvent.KIND,
                    ChatMessageRelayListEvent.KIND,
                    KeyPackageEvent.KIND,
                    // Where a current-profile peer publishes their KeyPackages: the
                    // NIP-65 write set, per transports/nostr.md. Kind 10051 stays as
                    // the legacy hint it has become.
                    AdvertisedRelayListEvent.KIND,
                    KeyPackageRelayListEvent.KIND,
                )
            )

            database.negentropySynchronizeRequestDao().insert(
                Relays.DefaultDMRelayList.map { relayUrl ->
                    NegentropySynchronizeRequest(
                        id = NegentropySynchronizeRequest.computeId(
                            relayURL = relayUrl.url,
                            synchronizationFilter
                        ),
                        purpose = "synchronization",
                        synchronizationFilter = synchronizationFilter,
                        relayURL = relayUrl.url,
                        level = 0
                    )
                }
            )
        }
        // Handle membership changes...
        database.participantDao().upsert(removedAdmins)
        database.participantDao().delete(excludedParticipants)
        database.participantDao().upsert(newParticipants)
        database.participantDao().upsert(graduatedAdmins)

        val userPublicKey = localChatRoom.chatRoom.userPublicKey
        val disbanded = mlsGroup.currentGroupState().isDisbanded
        val removedUs = !mlsGroup.isLocalMember()

        if (announceAdditions) {
            newParticipants.forEach { participant ->
                announceLine(database, localChatRoom, ChatMessage.TYPE_MEMBER_ADDED,
                    "${memberName(database, participant.participantPublicKey)} joined the group")
            }
        }
        excludedParticipants
            .filterNot { it.participantPublicKey == userPublicKey }
            // A disband removes everyone; one line for it below, not one per member.
            .takeUnless { disbanded }
            ?.forEach { participant ->
                announceLine(database, localChatRoom, ChatMessage.TYPE_MEMBER_LEFT,
                    "${memberName(database, participant.participantPublicKey)} left the group")
            }

        when {
            disbanded -> announceLine(database, localChatRoom, ChatMessage.TYPE_GROUP_DISBANDED, "The group was disbanded")
            removedUs -> announceLine(database, localChatRoom, ChatMessage.TYPE_MEMBER_LEFT, "You were removed from the group")
        }
        if ((disbanded || removedUs) && localChatRoom.chatRoom.leftGroupAt == null) {
            database.chatRoomDao().upsert(localChatRoom.chatRoom.copy(leftGroupAt = Clock.System.now()))
        }
    }

    private suspend fun memberName(database: DedunDatabase, publicKey: HexKey): String =
        database.profileDao().getProfileByPublicKey(publicKey)?.humanReadableNameOrPubkey()
            ?: publicKey.take(8)

    /** A membership caption, this device's own account of what the group's state said. */
    private suspend fun announceLine(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        messageType: String,
        content: String,
    ) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content,
                messageType = messageType,
                chatRoomId = localChatRoom.chatRoom.id,
                senderPublicKey = localChatRoom.chatRoom.userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
    }

    suspend fun processGroupEvent(
        database: DedunDatabase,
        activeKeyPair: KeyPair,
        localChatRoom: LocalChatRoom,
        mlsGroup: MlsGroup,
        groupEvent: GroupEvent
    ): GroupEventResult? {
        groupEvent.groupId()?.let { groupId ->
            return try {
                val mlsBytes = tryDecryptOuterLayer(
                    mlsGroup,
                    groupEvent.encryptedContent()
                )

                if (mlsBytes == null) {
                    // Expected when the sender's epoch has drifted, or when a
                    // key that predates our join is what this was encrypted
                    // with (classical MLS forward secrecy). Not an error —
                    // callers should log at DEBUG.
                    //
                    // The second of those is now rare rather than routine:
                    // `NostrDao.indexMarmotGroupEvent` holds back anything the
                    // group published before this device joined, so what
                    // reaches here from before our epoch is only what sits on
                    // the boundary — see ChatRoom.predatesMembership. A room
                    // full of these is a sign that gate is not being applied.
                    GroupEventResult.UndecryptableOuterLayer(
                        localChatRoom.chatRoom.id,
                        retainedEpochCount = retainedExporterSecrets(localChatRoom.chatRoom.id).size,
                    )
                } else {
                    val mlsMessage = MlsMessage.decodeTls(TlsReader(mlsBytes))

                    when (mlsMessage.wireFormat) {
                        WireFormat.PRIVATE_MESSAGE -> {
                            processPrivateMessage(
                                database = database,
                                mlsGroup = mlsGroup,
                                mlsMessage = mlsMessage,
                                groupEvent = groupEvent
                            )
                        }
                        WireFormat.PUBLIC_MESSAGE -> {
                            processPublicMessage(
                                database = database,
                                mlsGroup = mlsGroup,
                                mlsMessage = mlsMessage,
                                groupEvent = groupEvent
                            )
                        }
                        else -> {
                            throw MarmotUnsupportedWireFormatException(
                                "Unexpected wire format: ${mlsMessage.wireFormat}"
                            )
                        }
                    }
                }

            } catch (e: Throwable) {
                GroupEventResult.Error(groupId, "Failed to process GroupEvent: ${e.message}")
            }
        }

        return null
    }


    suspend fun processPrivateMessage(
        database: DedunDatabase,
        mlsGroup: MlsGroup,
        mlsMessage: MlsMessage,
        groupEvent: GroupEvent
    ): GroupEventResult  {
        val groupId = mlsGroup.roomId()

        // Peek at content type from the PrivateMessage header
        val privMsg = PrivateMessage.decodeTls(TlsReader(mlsMessage.payload))

        return when (privMsg.contentType) {
            ContentType.APPLICATION -> {
                // MLS decrypt to get the inner plaintext
                val decrypted = decryptMessageBytes(
                    database,
                    mlsGroup,
                    mlsMessage.toTlsBytes()
                )
                logger.d("decrypted: $decrypted")
                val innerJson = decrypted.content.decodeToString()

                // If the inner application payload is a Nostr event, its
                // `pubkey` field MUST equal the MLS sender's credential
                // identity. Reject any mismatch -- otherwise a group member
                // could mint events claiming a different author. Non-event
                // payloads (raw bytes via buildGroupEventFromBytes) bypass
                // this check since there is no author field to verify.
                //
                // The canonical shape is decoded first, strictly: six members,
                // no `sig`, an id that rehashes, no duplicate keys. The legacy
                // shape -- a rumor serialised with `"sig":""`, which is what
                // every build before docs/marmot-v2.md Phase 2 sent -- is the
                // fallback, and only that: Event.fromJsonOrNull would accept
                // the canonical shape too, but by defaulting the signature
                // rather than by checking anything, and a payload that is
                // neither still has no author and still fails the comparison.
                val innerAuthor: Pair<Int, HexKey>? = MarmotAppEvent.decodeOrNull(innerJson)
                    ?.let { it.kind to it.pubKey }
                    ?: Event.fromJsonOrNull(innerJson)?.let { it.kind to it.pubKey }
                logger.d("innerAuthor: $innerAuthor")

                if (innerAuthor != null) {
                    val senderIdentity = mlsGroup.memberIdentityHex(decrypted.senderLeafIndex)

                    mip03Rejection(
                        innerEventKind = innerAuthor.first,
                        innerEventPubKey = innerAuthor.second,
                        senderIdentity = senderIdentity,
                        legacyRoom = !mlsGroup.currentGroupState().isCurrentProfile,
                    )?.let { reason ->
                        return GroupEventResult.Error(groupId, reason)
                    }
                }

                GroupEventResult.ApplicationMessage(
                    groupId = groupId,
                    innerEventJson = innerJson,
                    senderLeafIndex = decrypted.senderLeafIndex,
                    epoch = decrypted.epoch,
                )
            }

            ContentType.COMMIT -> {
                handleCommitEvent(
                    database,
                    mlsGroup,
                    groupEvent
                )
            }

            ContentType.PROPOSAL -> {
                GroupEventResult.Error(groupId, "Standalone proposals not yet supported")
            }
        }
    }

    private suspend fun decryptMessageBytes(
        database: DedunDatabase,
        mlsGroup: MlsGroup,
        messageBytes: ByteArray,
    ): DecryptedMessage {
        val nostrGroupId = mlsGroup.roomId()

        // Try current epoch. If we hit an exception here we MUST surface
        // it — commits that throw mid-processCommit leave the in-memory
        // group half-mutated, and a retry via `group.decrypt(...)` will
        // just report a stale "epoch mismatch" from the partial advance,
        // hiding the real bug. Capture the original throwable, try
        // retained epochs as a fallback, and re-raise the captured one
        // if nothing decrypts.
        val retainedBefore = mlsGroup.retainedSecrets()
        val preEpoch = mlsGroup.epoch
        val currentFailure: Throwable? =
            try {
                val result = mlsGroup.decrypt(messageBytes)
                // PrivateMessage commits apply inline through
                // `MlsGroup.decrypt` → `processCommit`; the epoch advances
                // in memory but the CLI reopens a fresh Context on every
                // command, so we MUST persist here or reloaded state
                // silently reverts to the pre-commit extensions (including
                // admin list).
                if (result.contentType == ContentType.COMMIT && mlsGroup.epoch != preEpoch) {
                    pushRetainedEpoch(nostrGroupId, retainedBefore)
                    persistGroup(
                        database,
                        mlsGroup
                    )
                }
                return result
            } catch (t: Throwable) {
                t
            }

        val retained = retainedEpochs[nostrGroupId] ?: emptyList()
        for (epochSecrets in retained) {
            val result = tryDecryptWithRetainedEpoch(messageBytes, epochSecrets)
            if (result != null) return result
        }

        throw currentFailure ?: IllegalStateException("Decrypt failed without captured cause")
    }

    public suspend fun processPublicMessage(
        database: DedunDatabase,
        mlsGroup: MlsGroup,
        mlsMessage: MlsMessage,
        groupEvent: GroupEvent
    ): GroupEventResult {
        val groupId = mlsGroup.roomId()

        val pubMsg = PublicMessage.decodeTls(TlsReader(mlsMessage.payload))

        return when (pubMsg.contentType) {
            ContentType.COMMIT -> {
                handleCommitEvent(
                    database,
                    mlsGroup,
                    groupEvent
                )
            }

            ContentType.PROPOSAL -> {
                // wn/openmls publishes SelfRemove as a standalone PublicMessage
                // proposal — admins fold it into their next commit. Stage it
                // locally so a subsequent commit's `ProposalRef` can resolve;
                // without this every other member silently dropped the
                // proposal and the admin's commit then failed with "Commit
                // references unknown proposal" (marmot-interop test 15).
                try {
                    mlsGroup.receivePublicMessageProposal(pubMsg)
                    GroupEventResult.ProposalStaged(groupId, pubMsg.sender.leafIndex)
                } catch (e: Exception) {
                    GroupEventResult.Error(
                        groupId,
                        "Failed to stage standalone proposal: ${e.message}",
                        e,
                    )
                }
            }

            ContentType.APPLICATION -> {
                GroupEventResult.Error(groupId, "Application messages should use PrivateMessage")
            }
        }
    }

    private suspend fun handleCommitEvent(
        database: DedunDatabase,
        mlsGroup: MlsGroup,
        groupEvent: GroupEvent
    ): GroupEventResult {
        logger.d("handleCommitEvent: ${groupEvent.id}")
        val nostrGroupId = mlsGroup.roomId()

        val currentEpoch = mlsGroup.epoch
        commitTracker.addCommit(nostrGroupId, currentEpoch, groupEvent)

        // If this is the only commit for this epoch, apply immediately
        val pending = commitTracker.pendingForEpoch(nostrGroupId, currentEpoch)
        return if (pending.size == 1) {
            val result = applyCommit(
                database,
                mlsGroup,
                groupEvent
            )
            commitTracker.clearEpoch(nostrGroupId, currentEpoch)
            result
        } else {
            GroupEventResult.CommitPending(nostrGroupId, currentEpoch)
        }
    }

    /**
     * Forget the commits being held for [nostrGroupId], so a replay decides its
     * epochs again from the events it is about to feed back in.
     *
     * [handleCommitEvent] holds a commit whenever a second one turns up for an
     * epoch, and only [CommitOrdering.EpochCommitTracker.clearEpoch] on a
     * successful apply ever empties that. Nothing applies while more than one is
     * held, so the set is a dead end: replaying those commits without clearing
     * first just adds them again and leaves every one of them pending, and the
     * list grows with each attempt.
     *
     * Safe to drop because the tracker is a scratch pad, not a record -- the
     * commits themselves are on disk, and a replay hands them back oldest first,
     * which is the order [CommitOrdering.comparator] picks a winner in.
     */
    suspend fun forgetPendingCommits(nostrGroupId: HexKey) {
        commitTracker
            .pendingGroupEpochs()
            .filter { it.groupId == nostrGroupId }
            .forEach { pending ->
                logger.d("Forgetting pending commits for $nostrGroupId epoch ${pending.epoch}")
                commitTracker.clearEpoch(pending.groupId, pending.epoch)
            }
    }

    private suspend fun applyCommit(
        database: DedunDatabase,
        mlsGroup: MlsGroup,
        commitEvent: GroupEvent,
    ): GroupEventResult =
        try {
            val groupId = mlsGroup.roomId()

            val mlsBytes =
                tryDecryptOuterLayer(mlsGroup, commitEvent.encryptedContent())
                    ?: return GroupEventResult.UndecryptableOuterLayer(
                        groupId,
                        retainedEpochCount = retainedExporterSecrets(groupId).size,
                    )
            val mlsMessage = MlsMessage.decodeTls(TlsReader(mlsBytes))

            when (mlsMessage.wireFormat) {
                WireFormat.PRIVATE_MESSAGE -> {
                    // Sniff the PrivateMessage epoch without consuming any
                    // ratchet state. Past-epoch echoes and future-epoch
                    // arrivals must not advance the secret tree — otherwise
                    // the real handshake / application message gets rejected
                    // when it finally arrives.
                    val privPeek = PrivateMessage.decodeTls(TlsReader(mlsMessage.payload))
                    val currentEpoch = mlsGroup.epoch
                    when {
                        privPeek.epoch < currentEpoch -> {
                            GroupEventResult.Duplicate(groupId)
                        }

                        privPeek.epoch > currentEpoch -> {
                            GroupEventResult.Error(
                                groupId,
                                "PrivateMessage epoch ${privPeek.epoch} is ahead of local epoch $currentEpoch; ignoring",
                            )
                        }

                        else -> {
                            // `decrypt` applies the commit inline, advancing the
                            // epoch in memory. Capture the outgoing epoch's
                            // secrets first, then retain + persist on a
                            // successful advance — mirroring decryptMessageBytes
                            // and processCommit. Without this the advance is lost
                            // on reload and prior-epoch messages can no longer be
                            // decrypted.
                            val retainedBefore = mlsGroup.retainedSecrets()
                            val preEpoch = mlsGroup.epoch
                            val decrypted = mlsGroup.decrypt(mlsMessage.toTlsBytes())
                            if (decrypted.contentType == ContentType.COMMIT) {
                                if (mlsGroup.epoch != preEpoch) {
                                    pushRetainedEpoch(groupId, retainedBefore)
                                    persistGroup(database, mlsGroup)
                                }
                                GroupEventResult.CommitProcessed(groupId, mlsGroup.epoch)
                            } else {
                                GroupEventResult.Error(
                                    groupId,
                                    "Expected COMMIT but got ${decrypted.contentType}",
                                )
                            }
                        }
                    }
                }

                WireFormat.PUBLIC_MESSAGE -> {
                    val pubMsg = PublicMessage.decodeTls(TlsReader(mlsMessage.payload))
                    val tag = pubMsg.confirmationTag
                    val currentEpoch = mlsGroup.epoch
                    when {
                        tag == null -> {
                            GroupEventResult.Error(groupId, "PublicMessage commit missing confirmation_tag")
                        }

                        // Reject commits that are not for our current epoch.
                        // Happens most commonly when our own already-applied
                        // commit is echoed back from the relay after an app
                        // restart (the in-memory dedup set is cleared), and
                        // the outer layer decrypts via a retained epoch key.
                        // Calling `processCommit` on a past-epoch commit
                        // partially mutates tree / groupContext / epochSecrets
                        // before throwing on the confirmation-tag check,
                        // leaving the local state diverged from every other
                        // member's — they then can't decrypt anything we
                        // send next.
                        pubMsg.epoch < currentEpoch -> {
                            GroupEventResult.Duplicate(groupId)
                        }
                        pubMsg.epoch > currentEpoch -> {
                            GroupEventResult.Error(
                                groupId,
                                "Commit epoch ${pubMsg.epoch} is ahead of local epoch $currentEpoch; ignoring",
                            )
                        }

                        else -> {
                            // RFC 9420 §6.2 — reject PublicMessage commits
                            // whose membership_tag doesn't match what the
                            // current epoch's membership_key would produce.
                            // Without this an outsider with the outer
                            // exporter secret could inject arbitrary commit
                            // bytes and advance the group past them.
                            if (!mlsGroup.verifyPublicMessageCommitMembershipTag(pubMsg)) {
                                GroupEventResult.Error(
                                    groupId,
                                    "Invalid membership_tag on PublicMessage commit",
                                )
                            } else {
                                processCommit(
                                    database,
                                    mlsGroup,
                                    commitBytes = pubMsg.content,
                                    senderLeafIndex = pubMsg.sender.leafIndex,
                                    confirmationTag = tag,
                                    signature = pubMsg.signature,
                                )
                                GroupEventResult.CommitProcessed(groupId, mlsGroup.epoch)
                            }
                        }
                    }
                }

                else -> {
                    GroupEventResult.Error(groupId, "Unexpected wire format for commit")
                }
            }
        } catch (e: Exception) {
            GroupEventResult.Error(
                groupId = mlsGroup.roomId(),
                "Failed to apply commit: ${e.message}",
                e
            )
        }

    /**
     * Process a received Commit, advancing the epoch.
     *
     * @param mlsGroup MLS Group
     * @param commitBytes TLS-serialized Commit
     * @param senderLeafIndex sender's leaf index
     * @param confirmationTag optional confirmation tag for verification
     */
    suspend fun processCommit(
        database: DedunDatabase,
        mlsGroup: MlsGroup,
        commitBytes: ByteArray,
        senderLeafIndex: Int,
        confirmationTag: ByteArray,
        signature: ByteArray = ByteArray(0),
        wireFormat: WireFormat = WireFormat.PUBLIC_MESSAGE,
    ) {

        // Capture the outgoing epoch's secrets BEFORE advancing, but only
        // commit them to the retention window once processCommit succeeds —
        // otherwise a failed commit (e.g. "Duplicate encryption key" on an
        // add-me relay echo) would pollute the window with a duplicate of
        // the current epoch key, wasting the finite retention slots.
        val retainedBefore = mlsGroup.retainedSecrets()

        mlsGroup.processCommit(commitBytes, senderLeafIndex, confirmationTag, signature, wireFormat)

        pushRetainedEpoch(
            nostrGroupId = mlsGroup.roomId(),
            retainedBefore
        )
        persistGroup(
            database,
            mlsGroup
        )
    }

    /**
     * Decrypt the outer ChaCha20-Poly1305 layer, trying the current epoch's
     * exporter key first and falling back to retained epoch exporter keys.
     *
     * After a commit advances the epoch, late-arriving messages encrypted
     * with the previous epoch's exporter key would fail without this fallback.
     *
     * Returns null when neither the current epoch key nor any retained key
     * decrypts. This happens normally for commits/application messages from
     * epochs we never held the keys for, so callers should treat null as an
     * expected "nothing to do here" outcome and log at DEBUG, not as an error.
     * Most of that class of event no longer gets this far: what predates this
     * device's join is held back before any of it is attempted -- see
     * `ChatRoom.predatesMembership`.
     */
    private fun tryDecryptOuterLayer(
        mlsGroup: MlsGroup,
        encryptedContent: String,
    ): ByteArray? {
        // Try current epoch key first
        try {
            val exporterKey = mlsGroup.exporterSecret()
            logger.d("exporterKey: ${exporterKey.toHex()}")
            return GroupEventEncryption.decrypt(encryptedContent, exporterKey)
        } catch (_: Exception) {
            // Current epoch key failed — try retained epoch keys
        }

        // Try retained epoch exporter keys (most recent first)
        logger.d("Try retained")
        val retainedKeys = retainedExporterSecrets(
            nostrGroupId = mlsGroup.roomId()
        )
        for (retainedKey in retainedKeys) {
            try {
                return GroupEventEncryption.decrypt(encryptedContent, retainedKey)
            } catch (_: Exception) {
                // This retained key didn't work — try the next one
            }
        }

        return null
    }

    private suspend fun persistGroup(
        database: DedunDatabase,
        mlsGroup: MlsGroup
    ) {
        val nostrGroupId = mlsGroup.roomId()

        try {
            val state = mlsGroup.saveState()
            val encoded = state.encodeTls()
            logger.d { "persistGroup($nostrGroupId): serialized ${encoded.size} bytes, calling store.save" }

            val localChatRoom = database.chatRoomDao().findChatRoomById(nostrGroupId)

            if (localChatRoom == null) {
                throw MarmotMissingChatGroupException("chatRoom not found in database, skipping")
            } else {
                // ⚠️ SECURITY / KNOWN VULNERABILITY (H1: MLS group state stored
                //  in plaintext at rest). `MlsGroupState` contains secret key
                //  material (signing key, encryption key, epoch secrets) and its
                //  own KDoc states it "MUST be stored in encrypted local
                //  storage", yet it is written here as plain hex. See the matching
                //  warning on the key-package bundle in DatabaseMarmotRepository.
                //  TODO(security): Encrypt `mlsGroupState` at rest with a
                //   platform-backed key-encryption-key before this ships.
                database.chatRoomDao().upsert(
                    localChatRoom.chatRoom.copy(
                        mlsGroupState = encoded.toHex()
                    )
                )
            }

            // Also persist retained epochs
            val retained = retainedEpochs[nostrGroupId]
            if (retained != null) {
                val retainedBytes =
                    retained.map { epoch ->
                        val writer = TlsWriter()
                        epoch.encodeTls(writer)
                        writer.toByteArray()
                    }
//              TODO:  store.saveRetainedEpochs(nostrGroupId, retainedBytes)
                logger.d { "persistGroup($nostrGroupId): persisted ${retainedBytes.size} retained epochs" }
            }
        } catch (e: Exception) {
            logger.e("persistGroup($nostrGroupId) FAILED: ${e.message}", e)
            throw e
        }
    }

    /**
     * Push a previously-captured [RetainedEpochSecrets] into the bounded
     * retention window. Call after the epoch advance has been applied
     * successfully so that failed commits don't pollute the window with
     * duplicate current-epoch keys.
     */
    private fun pushRetainedEpoch(
        nostrGroupId: HexKey,
        retainedBefore: RetainedEpochSecrets,
    ) {
        val retained = retainedEpochs.getOrPut(nostrGroupId) { mutableListOf() }
        retained.add(retainedBefore)

        // Trim to retention window (keep only the most recent N-1 epochs)
        while (retained.size > EPOCH_RETENTION_WINDOW) {
            retained.removeAt(0)
        }
    }

    /**
     * ⚠️ SECURITY / KNOWN VULNERABILITY (retained-epoch sender impersonation).
     *
     * This hand-rolled fallback decrypts a late APPLICATION message under a
     * previous epoch's secrets but does NOT verify the sender's per-message
     * `FramedContentTBS` signature — unlike the library's primary
     * [MlsGroup.decrypt], which verifies it against the sender leaf's signature
     * key (quartz MlsGroup.kt: `verifyWithLabel(senderLeaf.signatureKey,
     * "FramedContentTBS", …)`).
     *
     * The application key/nonce here are derived from the epoch's *shared*
     * `encryptionSecret` (via [SecretTree]), which every group member — and any
     * removed member who kept a prior [RetainedEpochSecrets] — possesses. The
     * only thing that normally binds a message to a specific member is that
     * signature. Because it is skipped, a holder of a retained epoch secret can
     * forge an APPLICATION message with an arbitrary `senderLeafIndex`, and the
     * downstream MIP-03 check in [processPrivateMessage] passes (the attacker
     * simply sets the inner event's pubkey to match that leaf's identity).
     *
     * This CANNOT be fixed here: [RetainedEpochSecrets] carries only symmetric
     * material (epoch, senderDataSecret, encryptionSecret, leafCount,
     * exporterSecret) — no ratchet tree, no leaf signature keys, and no
     * past-epoch groupContext — so the `FramedContentTBS` for a past epoch
     * cannot be reconstructed or verified in the app.
     *
     * TODO(security): Fix in the quartz library — snapshot the epoch's ratchet
     *  tree (or at least each leaf's signature key) and groupContext into
     *  [RetainedEpochSecrets], and expose a verified `decryptWithRetainedSecrets`
     *  that reuses the library's `buildApplicationFramedContentTbs` +
     *  `verifyWithLabel` path. Until then this path trusts sender identity for
     *  late prior-epoch messages.
     */
    private fun tryDecryptWithRetainedEpoch(
        messageBytes: ByteArray,
        retained: RetainedEpochSecrets,
    ): DecryptedMessage? =
        try {
            val secretTree = SecretTree(retained.encryptionSecret, retained.leafCount)
            val mlsMsg =
                MlsMessage
                    .decodeTls(TlsReader(messageBytes))

            if (mlsMsg.wireFormat != WireFormat.PRIVATE_MESSAGE) {
                return null
            }

            val privMsg = PrivateMessage.decodeTls(
                TlsReader(
                    mlsMsg.payload
                )
            )
            if (privMsg.epoch != retained.epoch) return null

            // Derive sender data key/nonce using ciphertext sample (RFC 9420 §6.3.1)
            // RFC 9420 §6.3.2: ciphertext_sample is the first KDF.Nh bytes
            // (32 for HKDF-SHA256), not AEAD.Nk (16). Using AEAD.Nk here made
            // sender-data decryption silently fail for every retained-epoch
            // message and turned the fallback path into a no-op — the
            // symptom was interop Test 12 (offline catch-up): kind:9
            // messages encrypted under epoch N arriving after a 1→N+1
            // commit got rejected with "Message epoch X doesn't match
            // current epoch Y" instead of being pulled through this path.
            // Same fix already applied to MlsGroup.decrypt (line ~878);
            // this branch was missed when that one was patched.
            val ciphertextSample =
                privMsg.ciphertext.copyOfRange(0, minOf(privMsg.ciphertext.size, MlsCryptoProvider.HASH_OUTPUT_LENGTH))
            val senderDataKey =
                MlsCryptoProvider.expandWithLabel(
                    retained.senderDataSecret,
                    "key",
                    ciphertextSample,
                    MlsCryptoProvider.AEAD_KEY_LENGTH,
                )
            val senderDataNonce =
                MlsCryptoProvider.expandWithLabel(
                    retained.senderDataSecret,
                    "nonce",
                    ciphertextSample,
                    MlsCryptoProvider.AEAD_NONCE_LENGTH,
                )

            // Build SenderDataAAD
            val senderDataAad = TlsWriter()
            senderDataAad.putOpaqueVarInt(privMsg.groupId)
            senderDataAad.putUint64(privMsg.epoch)
            senderDataAad.putUint8(privMsg.contentType.value)

            val senderDataPlain =
                MlsCryptoProvider.aeadDecrypt(
                    senderDataKey,
                    senderDataNonce,
                    senderDataAad.toByteArray(),
                    privMsg.encryptedSenderData,
                )
            val senderReader = TlsReader(senderDataPlain)
            val senderLeafIndex = senderReader.readUint32().toInt()
            val generation = senderReader.readUint32().toInt()
            val reuseGuard = senderReader.readBytes(REUSE_GUARD_LENGTH)

            val kng = secretTree.applicationKeyNonceForGeneration(senderLeafIndex, generation)

            // Apply reuse_guard XOR to nonce
            val guardedNonce = kng.nonce.copyOf()
            for (i in 0 until REUSE_GUARD_LENGTH) {
                guardedNonce[i] = (guardedNonce[i].toInt() xor reuseGuard[i].toInt()).toByte()
            }

            // Build PrivateContentAAD
            val contentAad = TlsWriter()
            contentAad.putOpaqueVarInt(privMsg.groupId)
            contentAad.putUint64(privMsg.epoch)
            contentAad.putUint8(privMsg.contentType.value)
            contentAad.putOpaqueVarInt(privMsg.authenticatedData)

            val pmcBytes =
                MlsCryptoProvider.aeadDecrypt(kng.key, guardedNonce, contentAad.toByteArray(), privMsg.ciphertext)

            // AEAD plaintext is a PrivateMessageContent struct (RFC 9420
            // §6.3.1): `applicationData<V> || signature<V> || padding`. The
            // main decrypt path parses this and returns the inner
            // applicationData; the retained-epoch branch was returning the
            // raw struct, which made callers see a length-prefixed blob
            // with signature + zero-padding glued onto the end. Extract the
            // applicationData the same way.
            val pmcReader = TlsReader(pmcBytes)
            val applicationData = pmcReader.readOpaqueVarInt()

            DecryptedMessage(
                senderLeafIndex = senderLeafIndex,
                contentType = privMsg.contentType,
                content = applicationData,
                epoch = privMsg.epoch,
            )
        } catch (_: Exception) {
            null
        }


    /**
     * Return exporter secrets from retained epochs for a group.
     *
     * Used by the inbound processor to attempt outer decryption with
     * previous epoch keys when the current epoch's key fails (e.g.,
     * after a commit has advanced the epoch but late-arriving messages
     * still use the old exporter key).
     *
     * @param nostrGroupId hex-encoded Nostr group ID
     * @return list of retained exporter secrets (most recent first), each
     *         derived via MLS-Exporter("marmot", "group-event", 32)
     */
    fun retainedExporterSecrets(nostrGroupId: HexKey): List<ByteArray> {
        val retained = retainedEpochs[nostrGroupId] ?: return emptyList()
        return retained
            .filter { it.exporterSecret.isNotEmpty() }
            .sortedByDescending { it.epoch }
            .map { epochSecrets ->
                KeySchedule.mlsExporter(
                    epochSecrets.exporterSecret,
                    "marmot",
                    "group-event".encodeToByteArray(),
                    32,
                )
            }
    }

}