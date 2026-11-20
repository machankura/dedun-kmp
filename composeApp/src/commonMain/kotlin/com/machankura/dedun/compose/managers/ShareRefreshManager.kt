package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.FrostRefresh
import fr.acinq.bitcoin.crypto.frost.FrostRefreshFault
import fr.acinq.bitcoin.crypto.frost.FrostRefreshParams
import fr.acinq.secp256k1.ChilldkgFault
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.ShareRefreshConfirmation
import com.machankura.dedun.compose.database.model.ShareRefreshMessage
import com.machankura.dedun.compose.database.model.ShareRefreshSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.GovernanceProposalStage
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.ShareRepairStage
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep
import com.machankura.dedun.compose.database.model.types.ShareRefreshStage
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.frost.ShareRefreshEvents

/**
 * Rotates a group's shares: every recipient gets a new share of the same key, so
 * the shares from before stop combining with the shares after
 * (docs/frost-share-refresh.md, Phase 7).
 *
 * Built in the image of [ChillDkgRitualManager]. Every inbound message is stored
 * and then the refresh is asked whether it can move; every step is a pure
 * function of stored inputs, so an app killed halfway resumes on the next
 * message. It runs in the Marmot room whose id the key derives (D7) and travels
 * as that room's inner events: the dealt values are encrypted to host keys by the
 * protocol, so a message the whole room can read discloses nothing.
 *
 * Three rules hold the group together, and each is checked on proposal and on
 * arrival:
 * - **Recipients are at least t, and more than half the ids not retired** (D3b),
 *   so any two refreshes from one generation share a recipient.
 * - **A device confirms at most one refresh per base generation** (D3a), so that
 *   shared recipient leaves at most one with a certificate.
 *   [ShareRefreshConfirmation] is the write-once claim.
 * - **Every recipient takes part** (D2). A certificate needs them all; anyone
 *   who cannot is withheld, and repaired later.
 *
 * Nothing of this device's own goes out until its owner agrees to take part,
 * with the withheld list the proposal names ([ShareRefreshApprovalStep.DEAL]).
 * That is the only gate, and [pendingApproval] mirrors it. Everything after it
 * -- dealing, checking the combined result, storing the new share, confirming --
 * the device does itself: the check is the device's, and a person tapping
 * "store" added nothing to it (docs/rotate-shares-single-approval.md).
 */
object ShareRefreshManager {
    private val logger = Logger.withTag("ShareRefreshManager")

    // ---- refusals ----------------------------------------------------------------------------------------------

    /**
     * Why a refresh of [key] from [shares] with these recipients and withheld ids
     * must not run, or null when it can.
     */
    fun refusal(key: DkgSession, shares: ShareGenerations.Shares, recipients: List<Int>, withheld: Map<Int, ShareStanding>): String? {
        val roster = shares.roster
        if (roster.isEmpty() || shares.publicShares == null) return "this key has no public shares to refresh"
        val notRetired = roster.filter { it.standing != ShareStanding.RETIRED }.map { it.participantId }.toSet()
        val live = roster.filter { it.standing == ShareStanding.LIVE }.map { it.participantId }.toSet()
        if (recipients.isEmpty() || recipients != recipients.sorted() || recipients.toSet().size != recipients.size) {
            return "the recipients are not a set"
        }
        if (!live.containsAll(recipients)) return "a recipient holds no share of generation ${shares.generation}"
        if (withheld.keys.any { it in recipients }) return "a participant is both a recipient and withheld"
        if ((recipients.toSet() + withheld.keys) != notRetired) {
            return "every participant who is not retired has to be a recipient or withheld"
        }
        if (withheld.values.any { it == ShareStanding.LIVE }) return "a withheld participant cannot stay live"
        if (recipients.size < key.threshold) {
            return "it would leave ${recipients.size} members holding shares, fewer than the ${key.threshold} it takes to sign; " +
                "the group could not sign at all, and only a new ceremony goes on from there"
        }
        if (recipients.size * 2 <= notRetired.size) {
            return "${recipients.size} of ${notRetired.size} is not a majority, so two such refreshes could both complete"
        }
        return null
    }

    /** Whether [localChatRoom] is the room [key] signs as: its key state names the key, or its id derives from it. */
    private suspend fun isKeysRoom(database: DedunDatabase, localChatRoom: LocalChatRoom, key: DkgSession): Boolean {
        if (localChatRoom.chatRoom.mlsGroupState == null) return false
        val roomId = localChatRoom.chatRoom.id
        if (GroupKeyStateManager.keyStateFor(database, roomId)?.dkgSessionId == key.id) return true
        val thresholdPublicKey = key.thresholdPublicKey ?: return false
        return SharedKeyDerivation.marmotGroupId(thresholdPublicKey) == roomId
    }

    // ---- proposing ------------------------------------------------------------------------------------------------

    /**
     * Who a refresh of [key] from [shares] would deal to and who it would leave
     * out, with [withheld] asked for: every live participant not in it is a
     * recipient, and every stale participant is withheld again, as what they
     * were -- a refresh does not bring anybody back, a repair does. [refusal] is
     * why it cannot run, or null. One function for [proposeRefresh] and
     * [preview], so the dialog shows exactly what the proposal will say.
     */
    private fun plan(key: DkgSession, shares: ShareGenerations.Shares, userPublicKey: HexKey, withheld: Map<Int, ShareStanding>): Plan {
        val recipients = shares.roster
            .filter { it.standing == ShareStanding.LIVE && it.participantId !in withheld }
            .map { it.participantId }
        val allWithheld = withheld + shares.roster
            .filter { it.standing == ShareStanding.STALE && it.participantId !in withheld }
            .associate { it.participantId to ShareStanding.STALE }
        val myId = shares.signerIds[userPublicKey]
        val refusal = when {
            shares.secretShare == null -> "this device holds no share of generation ${shares.generation}"
            myId == null -> "this device is not a signer of generation ${shares.generation}"
            else -> refusal(key, shares, recipients, allWithheld)
                ?: "the proposer has to be a recipient".takeIf { myId !in recipients }
        }
        return Plan(recipients, allWithheld, refusal)
    }

    private data class Plan(val recipients: List<Int>, val withheld: Map<Int, ShareStanding>, val refusal: String?)

    /**
     * Why the rotation a change would owe could not run, or null when it could
     * (docs/admin-change-rotation.md, A12): every live holder of [shares] in
     * [retire] withheld for good, every stale one withheld again, and the rest
     * dealt to -- checked by the same [refusal] the rotation itself will meet. The
     * admin floor counts admins; this counts recipients against every id not yet
     * retired, so a share left live by an abandoned rotation makes the two differ.
     */
    fun owedRotationRefusal(key: DkgSession, shares: ShareGenerations.Shares, retire: Set<HexKey>): String? {
        val withheld = shares.roster
            .filter { it.standing == ShareStanding.LIVE && it.memberPublicKey in retire }
            .associate { it.participantId to ShareStanding.RETIRED }
        val plan = plan(key, shares, userPublicKey = "", withheld)
        return refusal(key, shares, plan.recipients, plan.withheld)
    }

    /** A refresh as it would be proposed, by member rather than by id. */
    data class Preview(
        val takingPart: List<HexKey>,
        val withheld: Map<HexKey, ShareStanding>,
        /** Why it cannot be proposed, or null. */
        val refusal: String?,
    )

    /**
     * What [proposeRefresh] would open from this device, without opening it: who
     * takes part and who is left out, stale members included, and whether it
     * would be refused. The proposer's dialog shows this, and pressing it is the
     * proposer's agreement to take part (docs/rotate-shares-single-approval.md,
     * Phase 4). [without] is a member to withhold for good, as after a removal.
     */
    suspend fun preview(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, without: HexKey? = null): Preview? {
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id) ?: return null
        if (!isKeysRoom(database, localChatRoom, key)) return null
        val shares = ShareGenerations.active(database, key)
        val withheld = without?.let { member ->
            val id = shares.signerIds[member] ?: return Preview(emptyList(), emptyMap(), "they hold no share of the group's key")
            mapOf(id to ShareStanding.RETIRED)
        } ?: emptyMap()
        val plan = plan(key, shares, userPublicKey, withheld)
        val memberOf = shares.roster.associate { it.participantId to it.memberPublicKey }
        return Preview(
            takingPart = plan.recipients.mapNotNull { memberOf[it] },
            withheld = plan.withheld.mapNotNull { (id, standing) -> memberOf[id]?.let { it to standing } }.toMap(),
            refusal = plan.refusal ?: rotationHold(database, localChatRoom, key)?.reason,
        )
    }

    /**
     * Opens a refresh of [key], making this device the coordinator. Every live
     * participant not in [withheld] is a recipient, and every stale one is
     * withheld again ([plan]).
     *
     * **Proposing is the proposer's agreement to take part**: the proposer's
     * device deals at once, and its owner is not asked again. That is safe only
     * because the proposal is made from a dialog that named everyone it leaves
     * out ([preview]) -- proposing is agreeing to withhold whoever the form
     * showed, never whoever it defaulted to unseen. Every other recipient is
     * asked the one thing, to take part (docs/rotate-shares-single-approval.md,
     * which also made storing the new share automatic for everyone, and
     * docs/rotation-waits-for-key-state.md, Phase 4).
     *
     * Refused while [rotationHold] holds: a rotation stacked on a generation the
     * group has not yet signed is what D6 exists to prevent.
     */
    suspend fun proposeRefresh(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        key: DkgSession,
        withheld: Map<Int, ShareStanding> = emptyMap(),
    ): ShareRefreshSession {
        check(ExperimentalFeatures.shareRefresh) { "This build does not rotate shares" }
        require(key.stage == DkgRitualStage.COMPLETE) { "Ceremony ${key.id} has not produced a key" }
        require(isKeysRoom(database, localChatRoom, key)) { "${localChatRoom.chatRoom.id} is not the room key ${key.id} signs as" }

        running(database, key)?.let { return it }
        rotationHold(database, localChatRoom, key)?.let { throw IllegalStateException("Cannot rotate yet: ${it.reason}") }

        val shares = ShareGenerations.active(database, key)
        val (recipients, allWithheld, refused) = plan(key, shares, userPublicKey, withheld)
        refused?.let { throw IllegalStateException("Cannot refresh: $it") }

        val session = ShareRefreshSession(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = localChatRoom.chatRoom.id,
            dkgSessionId = key.id,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            baseGeneration = shares.generation,
            recipientIds = ShareRefreshSession.formatIds(recipients),
            withheld = ShareRefreshSession.formatWithheld(allWithheld),
            round1Random = RandomInstance.bytes(32).toHex(),
            auxRandom = RandomInstance.bytes(32).toHex(),
            dealApprovedAt = Clock.System.now(),
        )
        database.shareRefreshDao().upsert(session)
        announce(database, session, ChatMessage.TYPE_SHARE_REFRESH_STARTED, startedLine(session, shares), userPublicKey)
        logger.i("Proposing refresh ${session.id} of ${key.id} from generation ${shares.generation}: $session")

        broadcast(
            database, localChatRoom, session, ShareRefreshEvents.PROPOSAL, "",
            ShareRefreshEvents.assembleTags(session.id, key.id, shares.generation, recipients, allWithheld)
        )
        advance(database, localChatRoom, session.id, nostrPrivateKey)
        return session
    }

    /**
     * The participant id [memberPublicKey] holds a live share of [localChatRoom]'s
     * key under, or null when they hold none -- the question a removal asks
     * before offering to rotate without them (docs/frost-share-refresh.md, Phase 9).
     */
    suspend fun liveIdOf(database: DedunDatabase, localChatRoom: LocalChatRoom, memberPublicKey: HexKey): Int? {
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id) ?: return null
        if (!isKeysRoom(database, localChatRoom, key)) return null
        return ShareGenerations.active(database, key).signerIds[memberPublicKey]
    }

    /**
     * Opens a refresh that withholds [memberPublicKey] for good, after they were
     * removed from the room (docs/frost-share-refresh.md, Phase 9). The removal is
     * one admin's commit; this needs every remaining holder, so it is a second
     * act, never folded into the first.
     *
     * Refused, with a reason, when it would leave fewer than t live ids -- the
     * group could not sign at all, and only a new ceremony goes on from there --
     * or when the remaining recipients are not a majority (remove fewer at a time).
     */
    suspend fun proposeWithout(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        memberPublicKey: HexKey,
    ): ShareRefreshSession {
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id)
            ?: throw IllegalStateException("This group has no shared key this device can rotate")
        val id = ShareGenerations.active(database, key).signerIds[memberPublicKey]
            ?: throw IllegalStateException("They hold no share of the group's key")
        return proposeRefresh(database, localChatRoom, userPublicKey, nostrPrivateKey, key, mapOf(id to ShareStanding.RETIRED))
    }

    /**
     * Why this device must not propose a rotation of [key] now, or null when it may
     * (docs/rotation-waits-for-key-state.md). The Shares section and [proposeRefresh]
     * both read this, so the button and the protocol cannot disagree.
     *
     * A rotation is not over when its refresh is: it is over when the group has
     * signed the key state at the new generation, the first signature the new
     * shares make and what every device waits on before deleting the old ones
     * (D6). Rotating again before then stacks a second unproven generation on the
     * first, and leaves the first one's key-state proposal to fail under its
     * signers.
     *
     * Checked on the proposer's device only. A recipient a few seconds behind on
     * the signature would refuse out loud and end a rotation that was fine.
     */
    suspend fun rotationHold(database: DedunDatabase, localChatRoom: LocalChatRoom, key: DkgSession): RotationHold? =
        rotationPending(database, localChatRoom, key)
            ?: adminChangeInFlight(database, localChatRoom, key)
            ?: enrolling(database, localChatRoom, key)

    /**
     * Whether a rotation of [key] is still under way: running, or done but with its
     * new generation's key state not yet signed -- the moment the old shares are
     * wiped -- or this device behind the group. The part of [rotationHold] that
     * admin changes wait on too (docs/admin-change-rotation.md, A5): neither
     * direction may start while the other has not finished.
     */
    suspend fun rotationPending(database: DedunDatabase, localChatRoom: LocalChatRoom, key: DkgSession): RotationHold? {
        running(database, key)?.let { return RotationHold.Running(it.id) }
        FrostSigningManager.openWalletSession(database, key.id)?.let { return RotationHold.SpendOpen(it.id) }
        FrostSigningManager.openMoneyStatementSession(database, key.id)?.let { return RotationHold.StatementOpen(it.id) }

        val active = ShareGenerations.active(database, key).generation
        val signed = GroupKeyStateManager.keyStateFor(database, localChatRoom.chatRoom.id)
            ?.takeIf { it.dkgSessionId == key.id }
            ?.generation ?: 0
        if (signed > active) return RotationHold.Behind(groupGeneration = signed, localGeneration = active)

        val open = openKeyStateSession(database, key)
        if (signed < active || open != null) return RotationHold.AwaitingKeyState(active, open?.id)
        return null
    }

    /**
     * A signed change to the room that moves who holds a share, not yet landed
     * (docs/admin-change-rotation.md, Phase 2): a governance statement of
     * [key] still being signed that changes the admins or removes a holder --
     * every signer holds that session -- or one of this device's own that is
     * signed and on its way to a relay, which nobody else holds a session for
     * any more. One that has landed and owes a rotation is not counted: that
     * rotation is the change's next step, and must not wait on the change.
     */
    private suspend fun adminChangeInFlight(database: DedunDatabase, localChatRoom: LocalChatRoom, key: DkgSession): RotationHold? {
        val holders = ShareGenerations.active(database, key).roster
            .filter { it.standing == ShareStanding.LIVE }
            .map { it.memberPublicKey }
            .toSet()
        val dao = database.frostSigningSessionDao()
        suspend fun movesShares(sessionId: String): Boolean = dao.getItems(sessionId).any { item ->
            val statement = Event.fromJsonOrNull(item.unsignedEventJson)?.let(GovernanceStatementEvent::parse) ?: return@any false
            statement.change.admins != null || statement.change.removes.any { it in holders }
        }

        dao.getOpenSessionsForKey(key.id)
            .filter { it.chatRoomId == localChatRoom.chatRoom.id && it.signDeclinedAt == null }
            .firstOrNull { movesShares(it.id) }
            ?.let { return RotationHold.AdminChangeInFlight(it.id) }

        database.governanceProposalDao().getAll()
            .filter { it.chatRoomId == localChatRoom.chatRoom.id }
            .filter { it.stage == GovernanceProposalStage.SIGNING || it.stage == GovernanceProposalStage.PUBLISHED }
            .filter { proposal ->
                // Landed while this device was not watching: the group is past its epoch.
                val epoch = runCatching { database.marmotEngine.groupFor(proposal.routingId) }.getOrNull()?.epoch
                epoch == null || epoch <= proposal.epoch
            }
            .firstOrNull { movesShares(it.frostSigningSessionId) }
            ?.let { return RotationHold.AdminChangeInFlight(it.frostSigningSessionId) }
        return null
    }

    /**
     * A new admin whose id the group's key state binds at the active generation
     * and who holds no share of it yet (docs/admin-change-rotation.md, A8). Not
     * once their enrollment has failed -- a repair brings that id back at any
     * generation -- and not for someone the live group does not name an admin,
     * whose promotion never landed: nothing will enroll them, and they must not
     * hold every rotation for good.
     */
    private suspend fun enrolling(database: DedunDatabase, localChatRoom: LocalChatRoom, key: DkgSession): RotationHold? {
        val active = ShareGenerations.active(database, key)
        val state = GroupKeyStateManager.keyStateFor(database, localChatRoom.chatRoom.id)
            ?.takeIf { it.dkgSessionId == key.id && it.generation == active.generation }
            ?: return null
        val bindings = GroupKeyStateEvent.MemberBinding.decodeAll(state.memberBindings)
        if (bindings.isEmpty()) return null
        val admins = RoomAdmins.of(database, localChatRoom.chatRoom.id)?.admins
        val repairs = database.shareRepairDao().getSessionsForKey(key.id)
        return bindings.firstOrNull { binding ->
            val entry = active.roster.firstOrNull { it.participantId == binding.participantId } ?: return@firstOrNull false
            entry.standing == ShareStanding.STALE &&
                (admins == null || binding.memberPublicKey in admins) &&
                repairs.none {
                    it.targetId == binding.participantId && it.generation == active.generation && it.stage == ShareRepairStage.FAILED
                }
        }?.let { RotationHold.Enrolling(it.memberPublicKey) }
    }

    /**
     * Asks the group to sign the key state it owes for the generation the last
     * rotation produced, when nobody here is asking -- the coordinator's proposal
     * failed, or its device is gone. Anyone may (docs/frost-share-refresh.md,
     * Phase 8); without it a lost coordinator holds every later rotation for good
     * (docs/rotation-waits-for-key-state.md, R4).
     */
    suspend fun proposeOwedKeyState(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, key: DkgSession): FrostSigningSession {
        val hold = rotationHold(database, localChatRoom, key)
        check(hold is RotationHold.AwaitingKeyState && hold.signingSessionId == null) {
            "The group is not waiting on anyone to ask for its key state"
        }
        // The refresh that produced the generation, named on the state as the
        // coordinator would have named it.
        val refreshId = database.shareRefreshDao().getSessionsForKey(key.id)
            .firstOrNull { it.stage == ShareRefreshStage.COMPLETE && it.baseGeneration + 1 == hold.generation }
            ?.id
        return GroupKeyStateManager.proposeGeneration(database, localChatRoom, userPublicKey, key, refreshId)
    }

    /**
     * A key-state signing session of [key] still open on this device -- a
     * rotation's, a rebind's, or the ceremony's first -- unless this device has
     * declined it. A session fails only once enough members decline, so one this
     * device has refused can sit open for as long as the others stay quiet; it
     * does not hold this device's hand for that long.
     */
    private suspend fun openKeyStateSession(database: DedunDatabase, key: DkgSession): FrostSigningSession? {
        val dao = database.frostSigningSessionDao()
        return dao.getOpenSessionsForKey(key.id)
            .filter { it.signDeclinedAt == null }
            .firstOrNull { session ->
                dao.getItems(session.id).singleOrNull()
                    ?.let { Event.fromJsonOrNull(it.unsignedEventJson) }
                    ?.kind == GroupKeyStateEvent.KIND
            }
    }

    /** A refresh of [key] still running on this device, if there is one. One at a time per key. */
    private suspend fun running(database: DedunDatabase, key: DkgSession): ShareRefreshSession? =
        database.shareRefreshDao().getSessionsForKey(key.id).firstOrNull {
            it.stage != ShareRefreshStage.COMPLETE && it.stage != ShareRefreshStage.FAILED
        }

    // ---- resuming ------------------------------------------------------------------------------------------------

    /**
     * Moves every running refresh of this user's as far as its stored messages
     * allow. What a tap on "Store your new share" used to do, for refreshes that
     * already have everything they need and will receive nothing more -- one an
     * older build left waiting on that tap, or one the app died in between
     * storing a message and acting on it (docs/rotate-shares-single-approval.md,
     * Phase 3).
     *
     * Not only the approved ones: a coordinator waiting on everyone's round 1 and
     * a withheld device waiting on the certificate may also hold what they need,
     * and [advance] stops at the one gate for anyone who has not taken part.
     * Safe to run as often as the caller likes: [advance] repeats no work.
     */
    suspend fun resumeRunning(database: DedunDatabase, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Int {
        if (!ExperimentalFeatures.shareRefresh) return 0
        val running = database.shareRefreshDao().getRunningSessionsFor(userPublicKey)
        running.forEach { session ->
            val localChatRoom = database.chatRoomDao().findChatRoomById(session.chatRoomId)
            if (localChatRoom == null) {
                logger.w("Refresh ${session.id} is in ${session.chatRoomId}, which is gone; not resuming it")
                return@forEach
            }
            advance(database, localChatRoom, session.id, nostrPrivateKey)
        }
        if (running.isNotEmpty()) logger.i("Resumed ${running.size} running refresh(es)")
        return running.size
    }

    // ---- inbound ---------------------------------------------------------------------------------------------------

    /**
     * Feeds one inbound refresh message in and advances as far as it will go.
     * Safe to call twice with the same message.
     */
    suspend fun processRefreshPayload(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
    ) {
        val sessionId = ShareRefreshEvents.parseSessionId(innerEvent.tags) ?: run {
            logger.w("Refresh payload ${innerEvent.id} has no session tag; dropping")
            return
        }

        val session = if (innerEvent.kind == ShareRefreshEvents.PROPOSAL) {
            acceptProposal(database, localChatRoom, innerEvent, sessionId, userPublicKey)
        } else {
            database.shareRefreshDao().getSessionById(sessionId)
        } ?: return

        if (session.stage == ShareRefreshStage.FAILED) return
        if (!record(database, session, innerEvent)) return

        advance(database, localChatRoom, session.id, nostrPrivateKey)
    }

    private suspend fun acceptProposal(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey,
    ): ShareRefreshSession? {
        database.shareRefreshDao().getSessionById(sessionId)?.let { return it }

        val key = ShareRefreshEvents.parseKey(innerEvent.tags)
            ?.let { database.dkgSessionDao().getSessionById(it) }
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
            ?: run {
                logger.w("Refresh $sessionId names a key this device does not hold; dropping")
                return null
            }
        val base = ShareRefreshEvents.parseBaseGeneration(innerEvent.tags)
        val recipients = ShareRefreshEvents.parseRecipients(innerEvent.tags)
        val withheld = ShareRefreshEvents.parseWithheld(innerEvent.tags)
        if (base == null || recipients == null || withheld == null) {
            logger.w("Refresh $sessionId has an unreadable generation, recipient or withheld tag; dropping")
            return null
        }
        if (!isKeysRoom(database, localChatRoom, key)) {
            logger.w("Refresh $sessionId of ${key.id} arrived in ${localChatRoom.chatRoom.id}, which is not its room; dropping")
            return null
        }

        // A member of the room with no id in the key has nothing to do with it.
        val active = ShareGenerations.active(database, key)
        if (userPublicKey !in active.roster.map { it.memberPublicKey }) return null

        val proposer = active.roster.firstOrNull { it.memberPublicKey == innerEvent.pubKey }?.participantId

        val session = ShareRefreshSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            dkgSessionId = key.id,
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            baseGeneration = base,
            recipientIds = ShareRefreshSession.formatIds(recipients),
            withheld = ShareRefreshSession.formatWithheld(withheld),
            round1Random = RandomInstance.bytes(32).toHex(),
            auxRandom = RandomInstance.bytes(32).toHex(),
        )

        val reason = when {
            // Built but not shipped (D4): a release build declines, out loud, rather
            // than leaving a refresh that needs it waiting forever.
            !ExperimentalFeatures.shareRefresh -> "this build cannot rotate shares"
            base != active.generation -> "it refreshes generation $base, and this device is at generation ${active.generation}"
            proposer == null || proposer !in recipients -> "its proposer is not one of its recipients"
            running(database, key) != null -> "another refresh of this key is still running here"
            else -> refusal(key, active, recipients, withheld)
        }

        database.shareRefreshDao().upsert(session)
        announce(database, session, ChatMessage.TYPE_SHARE_REFRESH_STARTED, startedLine(session, active), innerEvent.pubKey)

        if (reason != null) {
            logger.w("Refusing refresh $sessionId: $reason")
            // A recipient that cannot take part ends the refresh for everyone,
            // since a certificate needs it; anyone else just stays out.
            val myId = active.participantIds[userPublicKey]
            fail(database, session, "Refused: $reason")
            if (myId != null && myId in recipients) {
                broadcast(database, localChatRoom, session, ShareRefreshEvents.FAILURE, "Refused: $reason", ShareRefreshEvents.assembleTags(sessionId))
            }
            return null
        }

        replayStoredMessages(database, localChatRoom, session)
        return database.shareRefreshDao().getSessionById(sessionId)
    }

    /** Messages that arrived before the proposal, read back out of the room's inner events. */
    private suspend fun replayStoredMessages(database: DedunDatabase, localChatRoom: LocalChatRoom, session: ShareRefreshSession) {
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = localChatRoom.chatRoom.id, kinds = (ShareRefreshEvents.ALL - ShareRefreshEvents.PROPOSAL).toList())
            .filter { ShareRefreshEvents.parseSessionId(it.tags) == session.id }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))
            .forEach { stored ->
                val event = Event(stored.id, stored.publicKey, stored.createdAt.epochSeconds, stored.kind, stored.tags, stored.content, "")
                if (!record(database, current(database, session), event)) return
            }
    }

    /** Files one message. False when it ends the refresh. */
    private suspend fun record(database: DedunDatabase, session: ShareRefreshSession, innerEvent: Event): Boolean {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return false
        val base = ShareGenerations.at(database, key, session.baseGeneration) ?: return true
        val senderId = base.roster.firstOrNull { it.memberPublicKey == innerEvent.pubKey }?.participantId
        val fromRecipient = senderId != null && senderId in session.recipientIdList()

        when (innerEvent.kind) {
            ShareRefreshEvents.ROUND_1, ShareRefreshEvents.ROUND_2 -> {
                if (!fromRecipient) {
                    logger.w("Refresh ${session.id}: kind ${innerEvent.kind} from ${innerEvent.pubKey.take(8)}, who is not a recipient; ignoring")
                    return true
                }
                // Stored once, on first arrival: its time is when the rotation last
                // moved, and a relay repeating itself is not progress ([progress]).
                val known = database.shareRefreshDao().getMessage(session.id, innerEvent.kind, innerEvent.pubKey) != null
                if (!known) {
                    database.shareRefreshDao().upsert(ShareRefreshMessage(session.id, innerEvent.pubKey, innerEvent.kind, innerEvent.content))
                    if (innerEvent.pubKey != session.userPublicKey) {
                        announce(database, session, stepType(innerEvent.kind), stepLine(innerEvent.kind), innerEvent.pubKey)
                    }
                }
            }

            ShareRefreshEvents.COORDINATOR_ROUND_1 -> {
                if (innerEvent.pubKey != session.coordinatorPublicKey) return true
                update(database, session) { if (it.coordinatorRound1 == null) it.copy(coordinatorRound1 = innerEvent.content) else it }
            }

            ShareRefreshEvents.CERTIFICATE -> {
                if (innerEvent.pubKey != session.coordinatorPublicKey) return true
                update(database, session) { if (it.certificate == null) it.copy(certificate = innerEvent.content) else it }
            }

            ShareRefreshEvents.FAILURE -> {
                if (!fromRecipient) return true
                fail(database, session, "Abandoned by ${innerEvent.pubKey.take(8)}: ${innerEvent.content}")
                return false
            }
        }
        return true
    }

    // ---- advancing ------------------------------------------------------------------------------------------------

    /**
     * Takes every step the stored messages allow, stopping at the first that
     * waits on somebody. Each step asks whether its output exists rather than
     * what the stage says, so a crash or a redelivery repeats no work and skips
     * none.
     */
    private suspend fun advance(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) {
        var session = database.shareRefreshDao().getSessionById(sessionId) ?: return
        if (session.stage == ShareRefreshStage.COMPLETE || session.stage == ShareRefreshStage.FAILED) return

        try {
            val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return
            val base = ShareGenerations.at(database, key, session.baseGeneration) ?: return
            val myId = base.roster.firstOrNull { it.memberPublicKey == session.userPublicKey }?.participantId ?: return
            val recipients = session.recipientIdList()
            val isRecipient = myId in recipients
            val params = paramsFor(key, base, session)
            val hostSecretKey = ChillDkgRitualManager.deriveHostSecretKey(nostrPrivateKey)

            if (isRecipient) {
                // The one gate: taking part, with these members withheld. Every
                // step after it follows from that decision and asks nothing more.
                if (session.dealApprovedAt == null) {
                    announceApprovalNeeded(database, session, ShareRefreshApprovalStep.DEAL)
                    return
                }
                if (ownMessage(database, session, ShareRefreshEvents.ROUND_1) == null) {
                    val pmsg1 = FrostRefresh.participantStep1(hostSecretKey, params, myId.toUInt(), ByteVector32(session.round1Random)).right
                        ?: throw IllegalStateException("round 1 refused this device's inputs")
                    publishOwn(database, localChatRoom, session, ShareRefreshEvents.ROUND_1, pmsg1.toHex())
                }
            }

            if (session.isCoordinator() && session.coordinatorRound1 == null) {
                val round1 = byRecipient(database, session, base, ShareRefreshEvents.ROUND_1) ?: return
                val result = FrostRefresh.coordinatorStep1(round1.mapValues { ByteVector(it.value.hexToByteArray()) }, params)
                result.fault.raiseIfFaulty("summing round 1", base)
                val cmsg1 = result.message!!.toHex()
                session = update(database, session) { it.copy(coordinatorRound1 = cmsg1) }
                broadcast(database, localChatRoom, session, ShareRefreshEvents.COORDINATOR_ROUND_1, cmsg1, ShareRefreshEvents.assembleTags(session.id))
            }

            val cmsg1 = session.coordinatorRound1 ?: return
            session = moveTo(database, session, ShareRefreshStage.COLLECTING_ROUND_2)

            if (isRecipient && ownMessage(database, session, ShareRefreshEvents.ROUND_2) == null) {
                // No gate here. participantStep2 is the check that stops a
                // coordinator substituting a table nobody dealt, and it runs
                // whoever is looking; confirmAndStore is the one-confirmation
                // rule, and it holds whoever is looking.
                val secretShare = base.secretShare ?: throw IllegalStateException("this device holds no share of generation ${base.generation}")
                val step2 = FrostRefresh.participantStep2(hostSecretKey, secretShare, params, myId.toUInt(), ByteVector(cmsg1.hexToByteArray()), ByteVector32(session.auxRandom))
                step2.fault.raiseIfFaulty("round 2", base)

                // Stored before it is confirmed, and claimed as this device's one
                // confirmation from this generation in the same transaction.
                val holder = database.shareRefreshDao().confirmAndStore(
                    ShareRefreshConfirmation(key.id, session.baseGeneration, session.id),
                    KeyShareGeneration(
                        dkgSessionId = key.id,
                        generation = session.baseGeneration + 1,
                        secretShare = step2.newSecretShare!!.value.toHex(),
                        publicShares = step2.newPublicShares!!.joinToString(",") { it.value.toHex() },
                        state = ShareGenerationState.PENDING,
                        refreshSessionId = session.id,
                    ),
                    newRoster(key, base, session),
                )
                if (holder != session.id) {
                    throw IllegalStateException("this device has already confirmed refresh ${holder.take(8)} from generation ${session.baseGeneration}")
                }
                publishOwn(database, localChatRoom, session, ShareRefreshEvents.ROUND_2, step2.certEqSignature!!.toHex())
            }

            if (session.isCoordinator() && session.certificate == null) {
                val round2 = byRecipient(database, session, base, ShareRefreshEvents.ROUND_2) ?: return
                val result = FrostRefresh.coordinatorFinalize(params, ByteVector(cmsg1.hexToByteArray()), round2.mapValues { ByteVector64(it.value) })
                result.fault.raiseIfFaulty("certifying", base)
                val cmsg2 = result.output!!.toHex()
                session = update(database, session) { it.copy(certificate = cmsg2) }
                broadcast(database, localChatRoom, session, ShareRefreshEvents.CERTIFICATE, cmsg2, ShareRefreshEvents.assembleTags(session.id))
            }

            val certificate = session.certificate ?: return
            val finalized = FrostRefresh.participantFinalize(params, ByteVector(cmsg1.hexToByteArray()), ByteVector(certificate.hexToByteArray()))
            finalized.fault.raiseIfFaulty("verifying the certificate", base)
            activate(database, key, base, session, finalized.output!!, isRecipient)

            update(database, session) { it.copy(stage = ShareRefreshStage.COMPLETE) }
            announce(
                database, session, ChatMessage.TYPE_SHARE_REFRESH_COMPLETE,
                "The group's shares were rotated. Shares held before stop working once everyone's device has deleted them.",
                session.coordinatorPublicKey
            )
            logger.i("Refresh ${session.id} complete: ${key.id} is at generation ${session.baseGeneration + 1}")

            // The coordinator asks the group to sign the new generation's key state:
            // its first signature with the new shares, and what every device waits on
            // before deleting the old ones (D6). Anyone can propose it again if this
            // device is gone.
            if (session.isCoordinator() && isRecipient) {
                // Its own failure is not the refresh's, which is complete: logged,
                // and anyone can propose the key state again.
                runCatching { GroupKeyStateManager.proposeGeneration(database, localChatRoom, session.userPublicKey, key, session.id) }
                    .onFailure { if (it is CancellationException) throw it; logger.e("Could not propose generation ${session.baseGeneration + 1}'s key state", it) }
            }
            // A signing session at the new generation that arrived before the
            // certificate has been waiting on the share it now has.
            if (isRecipient) FrostSigningManager.resumeAt(database, localChatRoom, key.id, session.baseGeneration + 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Refresh $sessionId failed", e)
            fail(database, session, e.message ?: e::class.simpleName ?: "Unknown error")
            broadcast(database, localChatRoom, session, ShareRefreshEvents.FAILURE, e.message ?: "Refresh failed", ShareRefreshEvents.assembleTags(session.id))
        }
    }

    private fun FrostRefreshFault.raiseIfFaulty(step: String, base: ShareGenerations.Shares) {
        if (isOk) return
        val who = participantId?.let { id -> base.roster.firstOrNull { it.participantId == id.toInt() }?.memberPublicKey?.take(8) ?: "participant $id" }
        val what = when (code) {
            ChilldkgFault.FAULTY_COORDINATOR -> "the coordinator is faulty"
            ChilldkgFault.FAULTY_PARTICIPANT -> "a recipient is faulty ($who)"
            ChilldkgFault.FAULTY_PARTICIPANT_OR_COORDINATOR -> "a recipient or the coordinator is faulty ($who)"
            ChilldkgFault.UNKNOWN_FAULTY_PARTICIPANT_OR_COORDINATOR -> "what was dealt does not match the commitments"
            else -> "the inputs were refused"
        }
        throw IllegalStateException("Share refresh, $step, failed: $what")
    }

    /** The parameters every party derives from what it already holds. */
    private fun paramsFor(key: DkgSession, base: ShareGenerations.Shares, session: ShareRefreshSession): FrostRefreshParams =
        FrostRefresh.params(
            thresholdPublicKey = PublicKey(ByteVector(requireNotNull(key.thresholdPublicKey).hexToByteArray())),
            hostPublicKeys = base.roster.map { PublicKey(ByteVector(it.hostPublicKey.hexToByteArray())) },
            publicShares = requireNotNull(base.publicShares),
            threshold = key.threshold,
            generation = session.baseGeneration.toUInt(),
            recipientIds = session.recipientIdList().map { it.toUInt() },
            refreshId = ByteVector32(session.id),
        ).let { result -> result.right ?: throw IllegalStateException("invalid refresh parameters: ${result.left?.message}") }

    /** Every recipient's message of [kind], keyed by participant id, or null while any is missing. */
    private suspend fun byRecipient(database: DedunDatabase, session: ShareRefreshSession, base: ShareGenerations.Shares, kind: Kind): Map<UInt, String>? {
        val idByMember = base.roster.associate { it.memberPublicKey to it.participantId }
        val messages = database.shareRefreshDao().getMessagesByKind(session.id, kind)
            .mapNotNull { message -> idByMember[message.participantPublicKey]?.let { it to message.payload } }
            .toMap()
        val recipients = session.recipientIdList()
        if (!messages.keys.containsAll(recipients)) return null
        return recipients.associate { it.toUInt() to messages.getValue(it) }
    }

    /** The roster of the new generation: recipients live, the withheld as the proposal said, the retired retired. */
    private fun newRoster(key: DkgSession, base: ShareGenerations.Shares, session: ShareRefreshSession): List<KeyShareMember> {
        val recipients = session.recipientIdList().toSet()
        val withheld = session.withheldStandings()
        return base.roster.map { entry ->
            KeyShareMember(
                dkgSessionId = key.id,
                generation = session.baseGeneration + 1,
                participantId = entry.participantId,
                memberPublicKey = entry.memberPublicKey,
                hostPublicKey = entry.hostPublicKey,
                standing = when {
                    entry.participantId in recipients -> ShareStanding.LIVE
                    else -> withheld[entry.participantId] ?: ShareStanding.RETIRED
                },
            )
        }
    }

    /**
     * The certificate verified: the new generation is the one the group signs with
     * here. A recipient's pending row becomes active; a withheld device learns the
     * new table from the recovery data and files it with no share. The generation
     * before becomes retiring -- its share is deleted in Phase 8, once the group has
     * signed with the new one.
     */
    private suspend fun activate(database: DedunDatabase, key: DkgSession, base: ShareGenerations.Shares, session: ShareRefreshSession, recovery: ByteVector, isRecipient: Boolean) {
        val now = Clock.System.now()
        val next = session.baseGeneration + 1
        val dao = database.keyShareDao()

        if (isRecipient) {
            val pending = dao.getGeneration(key.id, next) ?: throw IllegalStateException("generation $next was confirmed but is not stored")
            dao.upsert(pending.copy(state = ShareGenerationState.ACTIVE, recoveryData = recovery.toHex(), activatedAt = now, updatedAt = now))
        } else {
            val public = FrostRefresh.publicRecover(recovery)
            if (!public.fault.isOk) throw IllegalStateException("the certified recovery data does not recover")
            dao.upsert(
                KeyShareGeneration(
                    dkgSessionId = key.id,
                    generation = next,
                    secretShare = null,
                    publicShares = public.newPublicShares!!.joinToString(",") { it.value.toHex() },
                    state = ShareGenerationState.ACTIVE,
                    refreshSessionId = session.id,
                    recoveryData = recovery.toHex(),
                    activatedAt = now,
                )
            )
            dao.upsert(newRoster(key, base, session))
        }

        if (session.baseGeneration >= 1) {
            dao.getGeneration(key.id, session.baseGeneration)?.let { previous ->
                dao.upsert(previous.copy(state = ShareGenerationState.RETIRING, updatedAt = now))
            }
        }
    }

    // ---- approvals ------------------------------------------------------------------------------------------------

    /**
     * What this device's owner is being asked for, or null. **Must mirror the
     * gate in [advance] exactly**: drift makes the screen offer an approval that
     * does nothing, or none while the refresh sits still.
     *
     * Only ever [ShareRefreshApprovalStep.DEAL]. [ShareRefreshApprovalStep.CONFIRM]
     * was a second gate until docs/rotate-shares-single-approval.md.
     */
    suspend fun pendingApproval(database: DedunDatabase, session: ShareRefreshSession): ShareRefreshApprovalStep? {
        if (session.stage == ShareRefreshStage.COMPLETE || session.stage == ShareRefreshStage.FAILED) return null
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return null
        val base = ShareGenerations.at(database, key, session.baseGeneration) ?: return null
        val myId = base.roster.firstOrNull { it.memberPublicKey == session.userPublicKey }?.participantId ?: return null
        if (myId !in session.recipientIdList()) return null
        return if (session.dealApprovedAt == null) ShareRefreshApprovalStep.DEAL else null
    }

    /**
     * Where a running refresh stands, as the Shares section shows it once its one
     * approval is given: which step, who it waits on, since when, and whether
     * this device may abandon it (docs/rotate-shares-single-approval.md, Phase 6).
     * Null once it has finished.
     *
     * With nothing left to approve, this is the only place a member who took part
     * can see a rotation stall, and the only way they can end it.
     */
    suspend fun progress(database: DedunDatabase, session: ShareRefreshSession): Progress? {
        if (session.stage == ShareRefreshStage.COMPLETE || session.stage == ShareRefreshStage.FAILED) return null
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return null
        val base = ShareGenerations.at(database, key, session.baseGeneration) ?: return null
        val memberOf = base.roster.associate { it.participantId to it.memberPublicKey }
        val recipients = session.recipientIdList()
        val messages = database.shareRefreshDao().getMessagesForSession(session.id)
        fun missing(kind: Kind) = recipients
            .filter { id -> messages.none { it.kind == kind && it.participantPublicKey == memberOf[id] } }
            .mapNotNull { memberOf[it] }
        val (step, waitingOn) = when {
            session.coordinatorRound1 == null -> Progress.Step.DEALING to missing(ShareRefreshEvents.ROUND_1).ifEmpty { listOf(session.coordinatorPublicKey) }
            else -> Progress.Step.STORING to missing(ShareRefreshEvents.ROUND_2).ifEmpty { listOf(session.coordinatorPublicKey) }
        }
        val myId = memberOf.entries.firstOrNull { it.value == session.userPublicKey }?.key
        return Progress(
            step = step,
            waitingOn = waitingOn,
            since = messages.maxOfOrNull { it.createdAt } ?: session.createdAt,
            canAbandon = myId != null && myId in recipients,
        )
    }

    /** See [progress]. */
    data class Progress(val step: Step, val waitingOn: List<HexKey>, val since: Instant, val canAbandon: Boolean) {
        enum class Step { DEALING, STORING }
    }

    /** Approves the step this device's owner is being asked for, and moves on. */
    suspend fun approve(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) {
        val session = database.shareRefreshDao().getSessionById(sessionId) ?: return
        val now = Clock.System.now()
        when (pendingApproval(database, session)) {
            ShareRefreshApprovalStep.DEAL -> update(database, session) { it.copy(dealApprovedAt = now) }
            // Never pending any more; kept so the `when` stays over the whole enum.
            ShareRefreshApprovalStep.CONFIRM, null -> return
        }
        advance(database, localChatRoom, sessionId, nostrPrivateKey)
    }

    /** Ends a refresh for everyone. Only a recipient can; a refresh has no "no" short of this. */
    suspend fun abandon(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String) {
        val session = database.shareRefreshDao().getSessionById(sessionId) ?: return
        if (session.stage == ShareRefreshStage.COMPLETE || session.stage == ShareRefreshStage.FAILED) return
        fail(database, session, ABANDONED)
        broadcast(database, localChatRoom, session, ShareRefreshEvents.FAILURE, ABANDONED, ShareRefreshEvents.assembleTags(session.id))
    }

    private const val ABANDONED = "Abandoned by a recipient"

    // ---- persistence and transport --------------------------------------------------------------------------------

    private suspend fun current(database: DedunDatabase, session: ShareRefreshSession): ShareRefreshSession =
        database.shareRefreshDao().getSessionById(session.id) ?: session

    private suspend fun update(database: DedunDatabase, session: ShareRefreshSession, change: (ShareRefreshSession) -> ShareRefreshSession): ShareRefreshSession {
        val now = Clock.System.now()
        val updated = change(current(database, session)).copy(updatedAt = now, savedAt = now)
        database.shareRefreshDao().upsert(updated)
        return updated
    }

    private suspend fun moveTo(database: DedunDatabase, session: ShareRefreshSession, stage: ShareRefreshStage): ShareRefreshSession =
        update(database, session) { if (it.stage < stage) it.copy(stage = stage) else it }

    private suspend fun fail(database: DedunDatabase, session: ShareRefreshSession, reason: String) {
        val now = current(database, session)
        if (now.stage == ShareRefreshStage.FAILED || now.stage == ShareRefreshStage.COMPLETE) return
        update(database, session) { it.copy(stage = ShareRefreshStage.FAILED, failureReason = reason) }
        announce(database, session, ChatMessage.TYPE_SHARE_REFRESH_FAILED, "The share rotation ended: $reason", session.coordinatorPublicKey)
    }

    private suspend fun ownMessage(database: DedunDatabase, session: ShareRefreshSession, kind: Kind): ShareRefreshMessage? =
        database.shareRefreshDao().getMessage(session.id, kind, session.userPublicKey)

    /** Queues this device's own message and records it, so the coordinator's own is in its sums. */
    private suspend fun publishOwn(database: DedunDatabase, localChatRoom: LocalChatRoom, session: ShareRefreshSession, kind: Kind, content: String) {
        broadcast(database, localChatRoom, session, kind, content, ShareRefreshEvents.assembleTags(session.id))
        if (ownMessage(database, session, kind) != null) return
        database.shareRefreshDao().upsert(ShareRefreshMessage(session.id, session.userPublicKey, kind, content))
        announce(database, session, stepType(kind), stepLine(kind), session.userPublicKey)
    }

    /** One of this device's messages, as an inner event of the key's Marmot room. */
    private suspend fun broadcast(database: DedunDatabase, localChatRoom: LocalChatRoom, session: ShareRefreshSession, kind: Kind, content: String, tags: Array<Array<String>>) {
        var createdAt = Clock.System.now().epochSeconds
        var id = EventHasher.hashId(pubKey = session.userPublicKey, createdAt = createdAt, kind = kind, tags = tags, content = content)
        // Step past an id already queued: a repeat inside the same second hashes the same.
        var attempts = 0
        while (attempts < 16 && database.marmotInnerEventDao().getById(id) != null) {
            createdAt += 1
            id = EventHasher.hashId(pubKey = session.userPublicKey, createdAt = createdAt, kind = kind, tags = tags, content = content)
            attempts += 1
        }
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id,
                publicKey = session.userPublicKey,
                kind = kind,
                createdAt = Instant.fromEpochSeconds(createdAt),
                tags = tags,
                content = content,
                chatRoomId = localChatRoom.chatRoom.id,
            )
        )
    }

    // ---- transcript -----------------------------------------------------------------------------------------------

    private fun startedLine(session: ShareRefreshSession, shares: ShareGenerations.Shares): String {
        val withheld = session.withheldStandings()
        if (withheld.isEmpty()) return "started rotating the group's shares, with every member taking part."
        val names = withheld.entries.joinToString(", ") { (id, standing) ->
            val who = shares.roster.firstOrNull { it.participantId == id }?.memberPublicKey?.take(8) ?: "participant $id"
            if (standing == ShareStanding.RETIRED) "$who for good" else "$who until they are brought back"
        }
        return "started rotating the group's shares without $names."
    }

    private fun stepType(kind: Kind): String = when (kind) {
        ShareRefreshEvents.ROUND_1 -> ChatMessage.TYPE_SHARE_REFRESH_DEALT
        else -> ChatMessage.TYPE_SHARE_REFRESH_CONFIRMED
    }

    private fun stepLine(kind: Kind): String = when (kind) {
        ShareRefreshEvents.ROUND_1 -> "sent their part of the new shares."
        else -> "stored their new share."
    }

    private suspend fun announceApprovalNeeded(database: DedunDatabase, session: ShareRefreshSession, step: ShareRefreshApprovalStep) {
        val now = current(database, session)
        val through = now.approvalRequestedThrough
        if (through != null && through >= step) return
        update(database, session) { it.copy(approvalRequestedThrough = step) }
        val line = when (step) {
            ShareRefreshApprovalStep.DEAL -> "Your approval is needed to take part in rotating the group's shares."
            // Unreachable: CONFIRM is no longer asked. Kept because the enum keeps the value.
            ShareRefreshApprovalStep.CONFIRM -> "Your approval is needed to store your new share."
        }
        announce(database, session, ChatMessage.TYPE_SHARE_REFRESH_APPROVAL_NEEDED, line, session.userPublicKey)
    }

    private suspend fun announce(database: DedunDatabase, session: ShareRefreshSession, messageType: String, content: String, actor: HexKey) {
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = actor,
                isUserMessage = actor == session.userPublicKey,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = session.chatRoomId,
                content = content,
                messageType = messageType,
            )
        )
    }
}
