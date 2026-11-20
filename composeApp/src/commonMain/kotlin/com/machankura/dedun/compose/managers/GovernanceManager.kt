package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.marmot.groups.MlsGroupManager
import com.vitorpamplona.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.group.MlsGroupState
import com.vitorpamplona.mls.messages.CommitResult
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GovernanceProposal
import com.machankura.dedun.compose.database.model.GovernanceProposalStage
import com.machankura.dedun.compose.database.model.MarmotCommitResult
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.exceptions.MarmotCommitPendingException
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.frost.GovernanceCarrierEvent
import com.machankura.dedun.compose.nostr.frost.GovernanceChange
import com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent
import com.machankura.dedun.compose.nostr.frost.OwnCommitEvent
import com.vitorpamplona.marmot.appComponents.GroupLifecycleV1
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.mls.components.ComponentsList

/**
 * How a change to a governed room becomes a commit: built, signed by the group, then
 * published inside that signature. docs/member-admin-promotion.md, R2 and Phase 5.
 *
 * 1. [propose]: the commit is built on a copy of the group -- the copy's policy
 *    records the proposals, so the statement is read off the very commit -- and the
 *    kind:445 that carries it is built and *not* published. The group is asked to
 *    sign a statement whose content is that kind:445, and the staged state is kept
 *    in a [GovernanceProposal], because this device cannot process its own commit.
 * 2. The quorum signs, through the room's ordinary signing sessions. A member's
 *    device declines a statement whose commit does not say what its tags say
 *    ([refusalOf]), before its owner is asked.
 * 3. [onSigned]: the proposer's device sends the signed statement in a carrier and
 *    raises the publish gate with the kept state. A relay's OK on the carrier
 *    installs it, as any commit's would.
 */
object GovernanceManager {
    private val logger = Logger.withTag("GovernanceManager")

    /** A commit built on a copy, with what it does. */
    class Prepared(
        val staged: MlsGroupManager.StagedCommit,
        val change: GovernanceChange,
        val commitEvent: GroupEvent,
        val epoch: Long,
    )

    /**
     * Builds the commit [propose] describes on a copy of [live], and the kind:445 that
     * would carry it to [routingId]. [propose] calls the copy's `propose…` functions;
     * a member's leave already pooled rides along (D6). The copy's policy is Marmot's,
     * so a commit every peer would refuse is refused here.
     */
    suspend fun prepare(live: MlsGroup, routingId: HexKey, propose: (MlsGroup) -> Unit): Prepared {
        val prior = live.saveState()
        val recorder = GovernanceGate.RecordingPolicy()
        val copy = MlsGroup.restore(prior, recorder)
        propose(copy)
        val result = copy.commit()
        val (_, proposals, _) = checkNotNull(recorder.seen) { "The commit was built without being checked" }
        // Who a Remove names is read off the group as it stood before the commit:
        // the copy's own view can already have the removed leaf blank.
        val change = checkNotNull(GovernanceChange.of(live.view(), proposals)) {
            "This change cannot be described, so the group could not approve it: " +
                proposals.joinToString { it.proposal::class.simpleName.orEmpty() }
        }
        val commitEvent: GroupEvent = NostrSignerInternal(KeyPair()).sign(
            GroupEvent.build(
                encryptedContentBase64 = GroupEventEncryption.encrypt(result.framedCommitBytes, result.preCommitExporterSecret),
                nostrGroupId = routingId,
            )
        )
        return Prepared(MlsGroupManager.StagedCommit(result, prior, copy.saveState()), change, commitEvent, live.epoch)
    }

    /**
     * Proposes [propose]'s change to a governed room, as [userPublicKey]: builds it,
     * asks the group to sign it, and keeps what is needed to publish it. Returns the
     * signing session. Throws, in words a person can act on, when it cannot.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        peerKeyPackageEventId: HexKey? = null,
        propose: (MlsGroup) -> Unit,
    ): String {
        val engine = database.marmotEngine
        val routingId = localChatRoom.routingId
        val roomId = localChatRoom.chatRoom.id
        engine.awaitSettled(routingId)
        val live = engine.groupFor(routingId) ?: throw MarmotMissingChatGroupException("No live group for $roomId")
        check(GovernanceGate.isGoverned(live)) { "This group's changes do not need its signature" }
        if (!engine.gate.canPrepareLocalCommit(routingId)) {
            throw MarmotCommitPendingException("An earlier change to this room is still waiting for a relay to accept it; try again in a moment")
        }

        val prepared = prepare(live, routingId, propose)
        // A disband ends the group, so no floor applies to what it leaves behind.
        val disbands = GroupLifecycleV1.COMPONENT_ID in prepared.change.components
        GovernanceGate.minimumAdmins(live)?.takeUnless { disbands }?.let { floor ->
            val admins = prepared.change.admins ?: live.currentAdminIdentitiesSet()
            val remaining = admins - prepared.change.removes
            check(remaining.size >= floor) { "A group with a shared key needs at least $floor admins. Make someone else an admin first" }
        }

        val session = FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = GovernanceStatementEvent.KIND,
            tags = GovernanceStatementEvent.assembleTags(roomId, prepared.epoch, prepared.change, userPublicKey),
            content = prepared.commitEvent.toJson(),
        )
        val statementId = database.frostSigningSessionDao().getItems(session.id).single().eventId
        record(database, localChatRoom, userPublicKey, prepared, statementId, session.id, peerKeyPackageEventId)
        return session.id
    }

    /**
     * Keeps what publishing [prepared] will need once the group has signed the
     * statement [statementId]: the commit's parts, under the id of the event that
     * carries it -- which is also how the inbound path knows a carried commit is this
     * device's own -- and the states either side of it.
     */
    internal suspend fun record(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        prepared: Prepared,
        statementId: HexKey,
        sessionId: String,
        peerKeyPackageEventId: HexKey? = null,
    ): GovernanceProposal {
        val roomId = localChatRoom.chatRoom.id
        database.marmotCommitResultDao().upsert(
            MarmotCommitResult.from(
                commitEventId = prepared.commitEvent.id,
                commitResult = prepared.staged.result,
                chatRoomId = roomId,
                userPublicKey = userPublicKey,
                peerKeyPackageEventId = peerKeyPackageEventId ?: MarmotCommitResult.NO_INVITEE,
                isOneMemberInitialGroupCreation = false,
                createdAt = Instant.fromEpochSeconds(prepared.commitEvent.createdAt),
            )
        )
        val proposal = GovernanceProposal(
            id = statementId,
            chatRoomId = roomId,
            routingId = localChatRoom.routingId,
            epoch = prepared.epoch,
            frostSigningSessionId = sessionId,
            commitEventId = prepared.commitEvent.id,
            commitEventJson = prepared.commitEvent.toJson(),
            peerKeyPackageEventId = peerKeyPackageEventId,
            priorStateHex = prepared.staged.priorState.encodeTls().toHex(),
            pendingStateHex = prepared.staged.pendingState.encodeTls().toHex(),
        )
        database.governanceProposalDao().upsert(proposal)
        return proposal
    }

    /**
     * Why this device will not put its share to [statement], or null when it may.
     * Run on a statement still being asked for -- unsigned -- by every member's device
     * before its owner is asked: the same check the door will make, but for the
     * signature, so nobody signs a commit that does not say what it does. The
     * proposer's own is not checked here: it built it, and cannot process its own
     * commit to look.
     */
    suspend fun refusalOf(database: DedunDatabase, localChatRoom: LocalChatRoom, statement: Event): String? {
        if (database.governanceProposalDao().getById(statement.id) != null) return null
        val group = database.marmotEngine.groupFor(localChatRoom.routingId) ?: return "this device holds no state for the group"
        return GovernanceGate.check(group, localChatRoom.chatRoom.id, statement, requireSignature = false).exceptionOrNull()?.message
    }

    /**
     * The group signed [signedEvents]: for each that is a statement this device
     * proposed, publish it. Called on every device that completes a signing session;
     * only the proposer's holds a [GovernanceProposal] for it.
     */
    suspend fun onSigned(database: DedunDatabase, signedEvents: List<Event>) {
        signedEvents.filter { it.kind == GovernanceStatementEvent.KIND }.forEach { signed ->
            val proposal = database.governanceProposalDao().getById(signed.id) ?: return@forEach
            if (proposal.stage != GovernanceProposalStage.SIGNING) return@forEach
            runCatching { publish(database, proposal, signed) }
                .onFailure { logger.e("Signed ${signed.id.take(8)}… but could not publish it", it) }
        }
    }

    /**
     * Sends [signed] in a carrier and raises the publish gate with the state
     * [proposal] kept; a relay's OK on the carrier installs it. A proposal the group
     * has moved past is marked stale and said so: its commit can no longer land.
     */
    suspend fun publish(
        database: DedunDatabase,
        proposal: GovernanceProposal,
        signed: Event,
        relays: List<String> = Relays.DefaultDMRelayList.map { it.url },
    ) {
        val engine = database.marmotEngine
        val routingId = proposal.routingId
        val room = database.chatRoomDao().findChatRoomById(proposal.chatRoomId) ?: return
        val me = room.chatRoom.userPublicKey
        engine.awaitSettled(routingId)
        val live = engine.groupFor(routingId) ?: return
        if (live.epoch != proposal.epoch || !engine.gate.canPrepareLocalCommit(routingId)) {
            database.governanceProposalDao().upsert(proposal.copy(stage = GovernanceProposalStage.STALE, updatedAt = Clock.System.now()))
            announce(database, proposal.chatRoomId, me, "A change the group signed was overtaken by another before it could be sent. Propose it again")
            return
        }

        val commitRow = checkNotNull(database.marmotCommitResultDao().getMarmotCommitRequestById(proposal.commitEventId)) {
            "The proposed commit's parts are missing"
        }
        val result = CommitResult(
            commitBytes = commitRow.commitBytes,
            welcomeBytes = commitRow.welcomeBytes,
            groupInfoBytes = commitRow.groupInfoBytes,
            framedCommitBytes = commitRow.framedCommitBytes,
            preCommitExporterSecret = commitRow.preCommitExporterSecret,
        )
        val staged = MlsGroupManager.StagedCommit(
            result,
            MlsGroupState.decodeTls(proposal.priorStateHex.hexToByteArray()),
            MlsGroupState.decodeTls(proposal.pendingStateHex.hexToByteArray()),
        )

        val commitEvent = Event.fromJson(proposal.commitEventJson).let {
            GroupEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
        }
        sendEnvelope(
            database = database,
            chatRoomId = proposal.chatRoomId,
            routingId = routingId,
            me = me,
            staged = staged,
            commitEvent = commitEvent,
            commitRow = commitRow,
            carrierKind = GovernanceCarrierEvent.KIND,
            carrierContent = signed.toJson(),
            carrierTags = GovernanceCarrierEvent.assembleTags(proposal.chatRoomId),
            relays = relays,
        )
        database.governanceProposalDao().upsert(proposal.copy(stage = GovernanceProposalStage.PUBLISHED, updatedAt = Clock.System.now()))
    }

    /**
     * Commits whatever members' own leaves are waiting in a governed room's pool, as
     * an admin's device does on receipt -- inside this account's own envelope (D6),
     * not bare. Nothing when nothing is waiting.
     */
    suspend fun publishOwnCommit(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        relays: List<String> = Relays.DefaultDMRelayList.map { it.url },
    ) {
        val engine = database.marmotEngine
        val routingId = localChatRoom.routingId
        val me = localChatRoom.chatRoom.userPublicKey
        if (!engine.groupManager.hasPendingProposals(routingId) || !engine.gate.canPrepareLocalCommit(routingId)) return
        engine.awaitSettled(routingId)
        val live = engine.groupFor(routingId) ?: return
        val prepared = prepare(live, routingId) { }
        // Only members' own leaves are ever pooled (MIP-03), and they need nobody's
        // signature; anything else would not be this envelope's to carry.
        check(prepared.change == GovernanceChange()) { "Only a member's own leave can be committed without the group's signature" }
        val commitRow = MarmotCommitResult.from(
            commitEventId = prepared.commitEvent.id,
            commitResult = prepared.staged.result,
            chatRoomId = localChatRoom.chatRoom.id,
            userPublicKey = me,
            peerKeyPackageEventId = MarmotCommitResult.NO_INVITEE,
            isOneMemberInitialGroupCreation = false,
            createdAt = Instant.fromEpochSeconds(prepared.commitEvent.createdAt),
        )
        database.marmotCommitResultDao().upsert(commitRow)
        sendEnvelope(
            database = database,
            chatRoomId = localChatRoom.chatRoom.id,
            routingId = routingId,
            me = me,
            staged = prepared.staged,
            commitEvent = prepared.commitEvent,
            commitRow = commitRow,
            carrierKind = OwnCommitEvent.KIND,
            carrierContent = prepared.commitEvent.toJson(),
            carrierTags = OwnCommitEvent.assembleTags(localChatRoom.chatRoom.id, prepared.epoch),
            relays = relays,
        )
    }

    /**
     * Puts a commit on the wire inside an envelope: the envelope as an application
     * message at the commit's epoch, then the publish gate raised with the staged
     * state, the commit filed as the event it is -- so the envelope coming back is
     * known for this device's own -- and the relay's OK on the envelope made the
     * commit's confirmation, as `publishCommit` does for a bare one.
     */
    private suspend fun sendEnvelope(
        database: DedunDatabase,
        chatRoomId: String,
        routingId: HexKey,
        me: HexKey,
        staged: MlsGroupManager.StagedCommit,
        commitEvent: GroupEvent,
        commitRow: MarmotCommitResult,
        carrierKind: Int,
        carrierContent: String,
        carrierTags: Array<Array<String>>,
        relays: List<String>,
    ) {
        val engine = database.marmotEngine
        val payload = MarmotAppEvent.build(
            pubKey = me,
            kind = carrierKind,
            content = carrierContent,
            createdAt = Clock.System.now().epochSeconds,
            tags = carrierTags,
        ).encodeToPayload()
        val carrier = engine.outbound.buildGroupEventFromBytes(routingId, payload).signedEvent
        val obligation = engine.gate.prepare(
            groupId = routingId,
            staged = staged,
            outboundBytes = staged.result.framedCommitBytes,
            recipientScope = relays,
        )

        listOf(commitEvent, carrier).forEach { event ->
            database.nostrEventDao().upsert(
                NostrEvent(
                    id = event.id, createdAt = Instant.fromEpochSeconds(event.createdAt), content = event.content,
                    sig = event.sig, tags = event.tags, kind = event.kind, pubKey = event.pubKey,
                )
            )
            database.marmotGroupEventDao().upsert(
                MarmotGroupEvent(
                    id = event.id, createdAt = Instant.fromEpochSeconds(event.createdAt), encryptedContent = event.encryptedContent(),
                    publicKey = event.pubKey, userPublicKey = me, expiresAt = null, chatRoomId = chatRoomId, signature = event.sig,
                )
            )
        }
        database.marmotCommitResultDao().upsert(
            commitRow.copy(id = carrier.id, obligationId = obligation.obligationId, createdAt = Instant.fromEpochSeconds(carrier.createdAt))
        )
        database.broadcastNostrEventRequestDao().insert(relays.map { BroadcastNostrEventRequest(nostrEventId = carrier.id, relayURL = it) })
    }

    // --- The changes, as the proposals a copy of the group is given ----------------

    /** Removing the member at [leafIndex], and their admin entry in the same commit when [admins] names [member]. */
    fun removal(leafIndex: Int, member: HexKey, admins: Set<HexKey>): (MlsGroup) -> Unit = { copy ->
        copy.proposeRemove(leafIndex)
        if (member in admins) {
            copy.proposeAppDataUpdate(AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(admins - member).encode())
        }
    }

    /** Adding the holder of [keyPackageBytes]. */
    fun addition(keyPackageBytes: ByteArray): (MlsGroup) -> Unit = { copy -> copy.proposeAdd(keyPackageBytes) }

    /** Ending the group, as the library's own disband does: everyone else removed, this device its last admin. */
    val disbanding: (MlsGroup) -> Unit = { copy ->
        copy.proposeAppDataUpdate(GroupLifecycleV1.COMPONENT_ID, GroupLifecycleV1.DISBANDED.encode())
        copy.proposeAppDataUpdate(AdminPolicyV1.COMPONENT_ID, AdminPolicyV1(listOf(checkNotNull(copy.memberIdentity(copy.leafIndex)))).encode())
        for ((leafIndex, _) in copy.members()) {
            if (leafIndex != copy.leafIndex) copy.proposeRemove(leafIndex)
        }
    }

    /** Agreeing the group can be disbanded, as the library's enablement does: the lifecycle component required, and active. */
    val enablingDisbanding: (MlsGroup) -> Unit = { copy ->
        val dictionary = copy.appDataDictionary()
        val required = ComponentsList.supportedOrRequired(dictionary).toMutableSet()
        required.add(GroupLifecycleV1.COMPONENT_ID)
        copy.proposeAppDataUpdate(ComponentsList.APP_COMPONENTS_ID, ComponentsList.encode(required))
        if (dictionary[GroupLifecycleV1.COMPONENT_ID] == null) {
            copy.proposeAppDataUpdate(GroupLifecycleV1.COMPONENT_ID, GroupLifecycleV1.ACTIVE.encode())
        }
    }

    private suspend fun announce(database: DedunDatabase, chatRoomId: String, me: HexKey, content: String) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content,
                messageType = ChatMessage.TYPE_CHANGE_NOT_APPLIED,
                chatRoomId = chatRoomId,
                senderPublicKey = me,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
    }

    private fun MlsGroup.currentAdminIdentitiesSet(): Set<HexKey> =
        com.vitorpamplona.marmot.groups.MarmotGroupPolicy.adminIdentitiesIn(extensions)
}
