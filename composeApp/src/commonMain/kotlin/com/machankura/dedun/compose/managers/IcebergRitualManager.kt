package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.IcebergParticipantMessage
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.PublicShare
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.utils.Either
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Runs an Iceberg key ceremony ([IcebergDkg]) in a group's Marmot room: the key a group Lightning
 * wallet signs its channels with (`docs/group-lightning-wallet.md`, Phase 5).
 *
 * Built like [ChillDkgRitualManager], and following its rules: every step is a pure function of what
 * this device has stored, so a ceremony survives the app being killed; faults are values with one
 * failure path; and each device writes the ceremony's transcript lines from what it has received.
 *
 * ### One approval, then the device
 *
 * Nothing of this device's own is published until its owner agrees to join. After that the device takes
 * the other two rounds by itself, because every check in them is one the code makes and the person
 * could not: whether a contribution matches its commitment, whether everyone saw the same commitments,
 * whether the public shares make one key. Asking a person to tap through them added nothing but the
 * chance that a member who joined would not come back, holding the ceremony open for everybody.
 *
 * A ceremony ends without anyone deciding to end it in two ways. A fault any device detects stops it on
 * that device, and the failure it broadcasts stops it on every other. A ceremony that has not finished
 * [DEADLINE] after its proposal is abandoned by the device of whichever participant notices first
 * ([abandonOverdue]), naming who had not taken their part. Any participant can also [abandon] it.
 *
 * ### Two things it does not need, that ChillDKG does
 *
 * **No host keys.** ChillDKG's first round exists to fix the participant set and its order. Here the
 * set is fixed before anyone speaks -- it is the people who hold the room's onchain wallet key
 * (decision D1), which the proposal names and every device checks against its own record of that
 * ceremony -- and the order is the bytewise order of their nostr keys.
 *
 * **No coordinator aggregation.** Every round is broadcast and every device assembles its own share, so
 * the member who opens the ceremony does nothing the others do not.
 *
 * ### What travels in the clear, inside MLS
 *
 * Commitments, public shares and transcript hashes are public by construction. Contributions are not:
 * each one goes only to the other holders of its seed, NIP-44 encrypted from the sender's nostr key to
 * each recipient's, inside the MLS message the whole room can read. A member who is in the room but
 * outside a seed's holders sees ciphertext.
 *
 * The room must be a Marmot room: that is where the onchain wallet's statement and every signing
 * session already are.
 */
object IcebergRitualManager {
    private val logger = Logger.withTag("IcebergRitualManager")

    private const val SESSION_TAG = "iceberg_session"
    private const val THRESHOLD_TAG = "threshold"
    private const val ONCHAIN_TAG = "onchain_dkg"

    /**
     * How long a ceremony has, from its proposal, before any participant's device abandons it. Long
     * enough for a member to see the request and open the app once; short enough that a member who
     * never does stops blocking the room, which can only run one ceremony at a time.
     */
    val DEADLINE: Duration = 24.hours

    /** When [session] is abandoned if it has not finished: [DEADLINE] after it was proposed. */
    fun deadlineOf(session: IcebergKeySession): Instant = session.createdAt + DEADLINE

    @Serializable
    private data class Round1(val commitments: Map<String, String>)

    @Serializable
    private data class Round2(val to: Map<String, String>)

    @Serializable
    private data class Round2Plain(val contributions: Map<String, String>)

    @Serializable
    private data class Round3(val publicShare: String, val transcript: String)

    /** The thresholds a group whose onchain key is [onchainKey] could choose, smallest first; empty if none. */
    fun thresholdsFor(onchainKey: DkgSession): List<Int> =
        onchainKey.participantPublicKeySet()?.let { IcebergDkg.deployableThresholds(it.size) }.orEmpty()

    /** The thresholds a ceremony in [chatRoomId] could use now, over [holdersInRoom]; empty if none. */
    suspend fun thresholds(database: DedunDatabase, chatRoomId: String): List<Int> {
        val onchainKey = FrostSigningManager.completedKey(database, chatRoomId) ?: return emptyList()
        return IcebergDkg.deployableThresholds(holdersInRoom(database, chatRoomId, onchainKey).size)
    }

    /**
     * Who a ceremony in [chatRoomId] is over (decision D1, as Phase 14 narrows it): the holders of the
     * onchain key [onchainKey] who are still in the room, as this device's MLS state has it. A member
     * who was removed keeps their onchain share, but a new Lightning wallet is made without them.
     *
     * The removal commit is an earlier epoch than any proposal sent after it, so a device reading a
     * proposal has already applied it, and every device computes the same set.
     */
    suspend fun holdersInRoom(database: DedunDatabase, chatRoomId: String, onchainKey: DkgSession): Set<HexKey> {
        val holders = onchainKey.participantPublicKeySet()?.map { it.lowercase() }?.toSet().orEmpty()
        val inRoom = database.participantDao().findParticipantsByChatRoomId(chatRoomId).filter { it.deletedAt == null }.map { it.participantPublicKey.lowercase() }.toSet()
        return holders intersect inRoom
    }

    /** A participant's nostr key as the compressed point [IcebergDkg.Params] orders and binds. */
    private fun asPoint(nostrKey: HexKey): PublicKey = PublicKey(ByteVector("02$nostrKey"))

    fun paramsOf(session: IcebergKeySession): IcebergDkg.Params =
        IcebergDkg.Params.of(session.threshold, session.participants().map(::asPoint), ByteVector32(session.id))

    private fun indexOf(session: IcebergKeySession, nostrKey: HexKey): Int? =
        session.participants().indexOf(nostrKey.lowercase()).takeIf { it >= 0 }?.plus(1)

    /**
     * Opens a ceremony over the people who hold [localChatRoom]'s onchain wallet key, at [threshold].
     * A ceremony already running in the room is returned instead of a second.
     *
     * @param chain the network the Lightning wallet is for. The room must have opened an onchain
     *   wallet there first (decision D2), because that wallet is where the Lightning wallet closes to.
     */
    suspend fun proposeCeremony(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        threshold: Int,
        chain: Chain,
    ): IcebergKeySession {
        val roomId = localChatRoom.chatRoom.id
        require(localChatRoom.chatRoom.mlsGroupState != null) { "An Iceberg ceremony runs in a Marmot room" }

        database.icebergKeySessionDao().getSessionsForChatRoom(roomId)
            .firstOrNull { it.stage != IcebergRitualStage.FAILED && it.stage != IcebergRitualStage.COMPLETE }
            ?.let { return it }

        val onchainKey = FrostSigningManager.completedKey(database, roomId)
            ?: throw IllegalStateException("This group has no onchain key whose holders could sign a Lightning wallet")
        requireNotNull(GroupWalletManager.walletFor(database, roomId, chain)) {
            "Open a group onchain wallet on ${chain.name} before a Lightning wallet: it is where the Lightning wallet closes to"
        }
        val members = holdersInRoom(database, roomId, onchainKey)
        require(userPublicKey.lowercase() in members) { "This device does not hold the onchain key" }
        require(IcebergDkg.isDeployable(members.size, threshold)) { "$threshold-of-${members.size} is not a deployable Iceberg group" }

        val session = IcebergKeySession(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = roomId,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            threshold = threshold,
            participantCount = members.size,
            participantPublicKeys = IcebergKeySession.formatParticipants(members),
            onchainDkgSessionId = onchainKey.id,
            root = RandomInstance.bytes(32).toHex(),
            // Opening a ceremony is agreeing to be in it, as with ChillDKG's host key.
            joinApprovedAt = Clock.System.now(),
        )
        database.icebergKeySessionDao().upsert(session)
        announce(
            database, session, ChatMessage.TYPE_ICEBERG_STARTED,
            "started a key ceremony for a Lightning wallet. It will take ${session.threshold} of ${session.participantCount} " +
                "of the people who hold the onchain wallet's key to sign for it, and ${2 * session.threshold - 1} of their devices " +
                "to be online for every payment.",
            session.coordinatorPublicKey,
        )
        broadcast(database, localChatRoom, session, GroupLightningEvents.CEREMONY_PROPOSAL, "", includeProposalTags = true)
        advance(database, localChatRoom, session.id, nostrPrivateKey)
        return database.icebergKeySessionDao().getSessionById(session.id) ?: session
    }

    /** Feeds one inbound ceremony message in and advances as far as it will go. Safe to call twice. */
    suspend fun processCeremonyPayload(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
    ) {
        val sessionId = innerEvent.tags.firstOrNull { it.size >= 2 && it[0] == SESSION_TAG }?.get(1)
        if (sessionId == null) {
            logger.w("Iceberg payload ${innerEvent.id} has no session tag; dropping")
            return
        }
        val session = if (innerEvent.kind == GroupLightningEvents.CEREMONY_PROPOSAL) {
            acceptProposal(database, localChatRoom, innerEvent, sessionId, userPublicKey)
        } else {
            database.icebergKeySessionDao().getSessionById(sessionId)
        } ?: return
        if (session.stage == IcebergRitualStage.FAILED || session.stage == IcebergRitualStage.COMPLETE) return
        if (!record(database, session, innerEvent)) return
        advance(database, localChatRoom, session.id, nostrPrivateKey)
    }

    private suspend fun acceptProposal(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey,
    ): IcebergKeySession? {
        database.icebergKeySessionDao().getSessionById(sessionId)?.let { return it }

        val members = innerEvent.tags.taggedUsers().map { it.pubKey.lowercase() }.toSet() + innerEvent.pubKey.lowercase()
        val threshold = innerEvent.tags.firstOrNull { it.size >= 2 && it[0] == THRESHOLD_TAG }?.get(1)?.toIntOrNull()
        val onchainId = innerEvent.tags.firstOrNull { it.size >= 2 && it[0] == ONCHAIN_TAG }?.get(1)
        if (userPublicKey.lowercase() !in members) return null
        // A proposal read after its deadline is over on every device that took part in it, finished or
        // not: one that could still be running would need this device, which has not joined. Filing it
        // would ask its owner to join a ceremony that is about to be abandoned -- or, on a device
        // reading the room again after a restore, abandon one the group finished long ago.
        val proposedAt = Instant.fromEpochSeconds(innerEvent.createdAt)
        if (Clock.System.now() >= proposedAt + DEADLINE) {
            logger.i("Iceberg proposal $sessionId is past its deadline; not filing it")
            return null
        }
        if (threshold == null || !IcebergDkg.isDeployable(members.size, threshold)) {
            logger.w("Iceberg proposal $sessionId asks for $threshold-of-${members.size}, which is not deployable; dropping")
            return null
        }
        // D1, checked rather than trusted: the people the proposal names must be exactly the people
        // who hold the onchain key it names and are still in the room, as this device's own records say.
        val onchainKey = onchainId?.let { database.dkgSessionDao().getSessionById(it) }
        if (onchainKey == null || holdersInRoom(database, localChatRoom.chatRoom.id, onchainKey) != members) {
            logger.w("Iceberg proposal $sessionId names people who are not the holders of onchain key $onchainId; dropping")
            return null
        }
        // The id is the ceremony id the key is bound to (IcebergDkg.Params), so it has to be 32 bytes.
        if (runCatching { ByteVector32(sessionId) }.isFailure) {
            logger.w("Iceberg proposal $sessionId has an id that is not 32 bytes; dropping")
            return null
        }

        val session = IcebergKeySession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            threshold = threshold,
            participantCount = members.size,
            participantPublicKeys = IcebergKeySession.formatParticipants(members),
            onchainDkgSessionId = onchainKey.id,
            root = RandomInstance.bytes(32).toHex(),
            // When it was proposed, not when this device heard of it: the deadline runs from here,
            // and every device has to agree on it.
            createdAt = proposedAt,
        )
        database.icebergKeySessionDao().upsert(session)
        announce(
            database, session, ChatMessage.TYPE_ICEBERG_STARTED,
            "started a key ceremony for a Lightning wallet. It will take ${session.threshold} of ${session.participantCount} " +
                "of the people who hold the onchain wallet's key to sign for it, and ${2 * session.threshold - 1} of their devices " +
                "to be online for every payment.",
            session.coordinatorPublicKey,
        )
        announceApprovalNeeded(database, session, IcebergApprovalStep.JOIN)
        replayStoredMessages(database, session)
        return session
    }

    private suspend fun replayStoredMessages(database: DedunDatabase, session: IcebergKeySession) {
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = session.chatRoomId, kinds = GroupLightningEvents.CEREMONY.toList())
            .filter { it.kind != GroupLightningEvents.CEREMONY_PROPOSAL && it.tags.any { t -> t.size >= 2 && t[0] == SESSION_TAG && t[1] == session.id } }
            .forEach { stored ->
                val event = Event(stored.id, stored.publicKey, stored.createdAt.epochSeconds, stored.kind, stored.tags, stored.content, "")
                if (!record(database, session, event)) return
            }
    }

    /** Files one message, first copy wins. False when the message ends the ceremony. */
    private suspend fun record(database: DedunDatabase, session: IcebergKeySession, innerEvent: Event): Boolean {
        val sender = innerEvent.pubKey.lowercase()
        when (innerEvent.kind) {
            GroupLightningEvents.CEREMONY_PROPOSAL -> Unit
            GroupLightningEvents.DKG_ROUND_1, GroupLightningEvents.DKG_ROUND_2, GroupLightningEvents.DKG_ROUND_3 -> {
                if (indexOf(session, sender) == null) {
                    logger.w("Ceremony ${session.id}: kind ${innerEvent.kind} from ${sender.take(8)}, who is not in it; ignoring")
                    return true
                }
                val dao = database.icebergKeySessionDao()
                if (dao.getMessage(session.id, innerEvent.kind, sender) == null) {
                    dao.upsert(IcebergParticipantMessage(session.id, sender, innerEvent.kind, innerEvent.content))
                    announceStep(database, session, innerEvent.kind, sender)
                }
            }
            GroupLightningEvents.CEREMONY_FAILURE -> {
                // The sender's own account of why, which is a sentence from any build
                // with [describe]. The line names the sender; the reason is not the
                // place for a key prefix.
                fail(database, session, innerEvent.content.ifBlank { "Something went wrong on their device." }, sender)
                return false
            }
        }
        return true
    }

    /**
     * Takes every step the stored messages allow, stopping at the first that waits on somebody. Each step
     * asks whether its output exists rather than what the stage says, as [ChillDkgRitualManager.advance] does.
     */
    private suspend fun advance(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) {
        var session = database.icebergKeySessionDao().getSessionById(sessionId) ?: return
        if (session.stage == IcebergRitualStage.COMPLETE || session.stage == IcebergRitualStage.FAILED) return
        val dao = database.icebergKeySessionDao()
        try {
            val params = paramsOf(session)
            val me = indexOf(session, session.userPublicKey) ?: error("this device is not in ceremony ${session.id}")
            val root = ByteVector32(session.root ?: error("ceremony ${session.id} has lost its random root"))

            if (session.joinApprovedAt == null) return
            if (own(database, session, GroupLightningEvents.DKG_ROUND_1) == null) {
                val commitments = IcebergDkg.round1(params, me, root)
                publishOwn(database, localChatRoom, session, GroupLightningEvents.DKG_ROUND_1, CommonJson.encodeToString(Round1.serializer(), Round1(commitments.byRank.entries.associate { (rank, c) -> rank.toString() to c.toHex() })))
            }
            val commitments = commitmentsOf(database, session, params) ?: return
            session = moveTo(database, session, IcebergRitualStage.COLLECTING_REVEALS)

            if (own(database, session, GroupLightningEvents.DKG_ROUND_2) == null) {
                val to = (1..params.n).filter { it != me }.associate { k ->
                    val recipient = session.participants()[k - 1]
                    val plain = Round2Plain(IcebergDkg.round2(params, me, root, k).entries.associate { (rank, c) -> rank.toString() to c.toHex() })
                    recipient to Nip44.encrypt(CommonJson.encodeToString(Round2Plain.serializer(), plain), nostrPrivateKey, recipient.hexToByteArray()).encodePayload()
                }
                publishOwn(database, localChatRoom, session, GroupLightningEvents.DKG_ROUND_2, CommonJson.encodeToString(Round2.serializer(), Round2(to)))
            }
            val reveals = revealsTo(database, session, me, nostrPrivateKey) ?: return
            val share = when (val assembled = IcebergDkg.assemble(params, me, root, commitments, reveals)) {
                is Either.Left -> throw assembled.value
                is Either.Right -> assembled.value
            }
            session = moveTo(database, session, IcebergRitualStage.COLLECTING_CONFIRMATIONS)

            val transcript = IcebergDkg.transcriptHash(params, commitments)
            val myPublicShare = Iceberg.publicShare(share, null)
            if (own(database, session, GroupLightningEvents.DKG_ROUND_3) == null) {
                publishOwn(database, localChatRoom, session, GroupLightningEvents.DKG_ROUND_3, CommonJson.encodeToString(Round3.serializer(), Round3(myPublicShare.toByteArray().toHex(), transcript.toHex())))
            }
            val confirmations = dao.getMessagesByKind(session.id, GroupLightningEvents.DKG_ROUND_3)
            if (confirmations.size < params.n) return
            val byIndex = confirmations.associate { m -> indexOf(session, m.participantPublicKey)!! to CommonJson.decodeFromString(Round3.serializer(), m.payload) }
            val disagreeing = byIndex.filterValues { it.transcript != transcript.toHex() }.keys
            if (disagreeing.isNotEmpty()) throw Disagreement(disagreeing.sorted())
            val publicShares = (1..params.n).map { PublicShare(byIndex.getValue(it).publicShare) }
            if (publicShares[me - 1] != myPublicShare) throw WrongPublicShare()
            val groupKey = when (val key = IcebergDkg.groupPublicKey(params, publicShares)) {
                is Either.Left -> throw key.value
                is Either.Right -> key.value
            }
            update(database, session) {
                it.copy(
                    stage = IcebergRitualStage.COMPLETE,
                    share = share.toByteArray().toHex(),
                    root = null,
                    groupPublicKey = groupKey.value.toHex(),
                    publicShares = publicShares.joinToString(",") { p -> p.toByteArray().toHex() },
                )
            }
            announce(
                database, session, ChatMessage.TYPE_ICEBERG_COMPLETE,
                "The group has a key for a Lightning wallet. It takes ${session.threshold} of ${session.participantCount} to sign for it.",
                session.coordinatorPublicKey,
            )
            logger.i("Iceberg ceremony ${session.id} complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Iceberg ceremony ${session.id} failed", e)
            // Said, not thrown: the exception is in the log, and what the group is told
            // -- here, and on every other device through the failure message -- is a
            // sentence naming the member at fault where the fault names one.
            val reason = describe(database, session, e)
            fail(database, session, reason)
            broadcast(database, localChatRoom, session, GroupLightningEvents.CEREMONY_FAILURE, reason)
        }
    }

    /** Some participants' transcript hashes are not this device's: they saw different commitments. */
    private class Disagreement(val participants: List<Int>) : Exception("participants $participants saw a different set of commitments")

    private class WrongPublicShare : Exception("the public share published for this device is not the one its share gives")

    /**
     * Why a ceremony stopped on this device, as a sentence for the group: in its failure line's
     * reason, on its screen, and in the failure message the other devices read. Shaped like
     * ChillDKG's blame -- the member at fault is the actionable part -- but with their name rather
     * than their index, since the index is an order of keys nobody sees.
     */
    internal suspend fun describe(database: DedunDatabase, session: IcebergKeySession, failure: Throwable): String {
        suspend fun named(index: Int?): String = index
            ?.let { session.participants().getOrNull(it - 1) }
            ?.let { "@" + ChatMessage.memberName(database, it) }
            ?: "A member"

        return when (failure) {
            is IcebergDkg.Fault.MissingCommitment -> "${named(failure.participant)} sent no commitment to their contributions."
            is IcebergDkg.Fault.MissingReveal -> "${named(failure.participant)} sent this device nothing in the second round."
            is IcebergDkg.Fault.BadReveal -> "${named(failure.participant)} sent a contribution that does not match what they committed to."
            is IcebergDkg.Fault.MalformedMessage -> "${named(failure.participant)} sent a message this device could not read."
            is IcebergDkg.Fault.InconsistentShares -> "The members' public shares do not make one key."
            is IcebergDkg.Fault.RepairMismatch -> "A repaired share does not match the public share published for it."
            is Disagreement -> failure.participants.map { named(it) }.joinToString(", ") +
                " saw a different set of commitments from this device."
            is WrongPublicShare -> "The public share published for this device is not the one its share gives."
            else -> "Something went wrong on this device."
        }
    }

    /** Every participant's commitments, or null while any are missing. */
    private suspend fun commitmentsOf(database: DedunDatabase, session: IcebergKeySession, params: IcebergDkg.Params): Map<Int, IcebergDkg.Commitments>? {
        val messages = database.icebergKeySessionDao().getMessagesByKind(session.id, GroupLightningEvents.DKG_ROUND_1)
        if (messages.size < params.n) return null
        return messages.associate { m ->
            val j = indexOf(session, m.participantPublicKey)!!
            val body = CommonJson.decodeFromString(Round1.serializer(), m.payload)
            j to IcebergDkg.Commitments(j, body.commitments.entries.associate { (rank, c) -> rank.toInt() to ByteVector32(c) })
        }
    }

    /** What every other participant revealed to this device, decrypted, or null while any are missing. */
    private suspend fun revealsTo(database: DedunDatabase, session: IcebergKeySession, me: Int, nostrPrivateKey: ByteArray): Map<Int, Map<Int, ByteVector32>>? {
        val messages = database.icebergKeySessionDao().getMessagesByKind(session.id, GroupLightningEvents.DKG_ROUND_2)
            .filter { it.participantPublicKey != session.userPublicKey.lowercase() }
        if (messages.size < session.participantCount - 1) return null
        val myKey = session.userPublicKey.lowercase()
        return messages.associate { m ->
            val j = indexOf(session, m.participantPublicKey)!!
            val ciphertext = CommonJson.decodeFromString(Round2.serializer(), m.payload).to[myKey]
                ?: throw IcebergDkg.Fault.MissingReveal(j)
            val plain = try {
                Nip44.decrypt(ciphertext, nostrPrivateKey, m.participantPublicKey.hexToByteArray())
            } catch (e: Throwable) {
                throw IcebergDkg.Fault.MalformedMessage(j, "its contributions could not be decrypted")
            }
            j to CommonJson.decodeFromString(Round2Plain.serializer(), plain).contributions.entries.associate { (rank, c) -> rank.toInt() to ByteVector32(c) }
        }
    }

    /**
     * What this ceremony is waiting on its owner for, or null. Only ever [IcebergApprovalStep.JOIN]: it
     * is the one gate in [advance].
     */
    suspend fun pendingApproval(database: DedunDatabase, session: IcebergKeySession): IcebergApprovalStep? {
        if (session.stage == IcebergRitualStage.COMPLETE || session.stage == IcebergRitualStage.FAILED) return null
        return if (session.joinApprovedAt == null) IcebergApprovalStep.JOIN else null
    }

    /** Records that this device's owner agreed to [step] and runs on. A step it is not waiting on is a no-op. */
    suspend fun approve(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray) {
        val session = database.icebergKeySessionDao().getSessionById(sessionId) ?: return
        if (pendingApproval(database, session) != step) {
            logger.i("Ceremony $sessionId is not waiting on $step; ignoring the approval")
            return
        }
        val now = Clock.System.now()
        update(database, session) {
            when (step) {
                IcebergApprovalStep.JOIN -> it.copy(joinApprovedAt = now)
                IcebergApprovalStep.REVEAL -> it.copy(revealApprovedAt = now)
                IcebergApprovalStep.CONFIRM -> it.copy(confirmApprovedAt = now)
            }
        }
        advance(database, localChatRoom, sessionId, nostrPrivateKey)
    }

    /**
     * Takes whatever steps [sessionId] can take now. Messages arriving and approvals already do this;
     * this is for a ceremony that stopped at a gate a build before this one had, and that nothing will
     * arrive to move, since the others are waiting on this device.
     */
    suspend fun resume(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) =
        advance(database, localChatRoom, sessionId, nostrPrivateKey)

    /**
     * Ends [sessionId] for everyone in it, at its owner's word. Open to any participant, as the shared
     * key's is (`docs/ceremony-creation.md`, decision 3): any of them could already end it by going
     * quiet, and saying so lets the others stop waiting. False when it is already over.
     */
    suspend fun abandon(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String): Boolean {
        val session = database.icebergKeySessionDao().getSessionById(sessionId) ?: return false
        if (session.stage == IcebergRitualStage.COMPLETE || session.stage == IcebergRitualStage.FAILED) return false
        val reason = "@${ChatMessage.memberName(database, session.userPublicKey)} abandoned it."
        fail(database, session, reason)
        broadcast(database, localChatRoom, session, GroupLightningEvents.CEREMONY_FAILURE, reason)
        return true
    }

    /**
     * Abandons every ceremony [userPublicKey] is in that has passed its [deadlineOf] without finishing,
     * telling the group who had not taken their part. Every participant's device does this, so several
     * may say it; the first failure each device files is the one it keeps.
     */
    suspend fun abandonOverdue(database: DedunDatabase, userPublicKey: HexKey, now: Instant = Clock.System.now()) {
        for (session in database.icebergKeySessionDao().getRunningSessions()) {
            if (session.userPublicKey != userPublicKey || now < deadlineOf(session)) continue
            val localChatRoom = database.chatRoomDao().findChatRoomById(session.chatRoomId) ?: continue
            val reason = overdueReason(database, session)
            logger.i("Iceberg ceremony ${session.id} is past its deadline; abandoning it")
            fail(database, session, reason)
            broadcast(database, localChatRoom, session, GroupLightningEvents.CEREMONY_FAILURE, reason)
        }
    }

    /**
     * Why an overdue ceremony stopped: the members missing from the first round this device does not
     * have from everyone, which is who the ceremony was waiting on.
     */
    internal suspend fun overdueReason(database: DedunDatabase, session: IcebergKeySession): String {
        val dao = database.icebergKeySessionDao()
        val missing = listOf(GroupLightningEvents.DKG_ROUND_1, GroupLightningEvents.DKG_ROUND_2, GroupLightningEvents.DKG_ROUND_3)
            .firstNotNullOfOrNull { kind ->
                val sent = dao.getMessagesByKind(session.id, kind).map { it.participantPublicKey.lowercase() }.toSet()
                session.participants().filter { it !in sent }.takeIf { it.isNotEmpty() }
            }
            .orEmpty()
        val hours = DEADLINE.inWholeHours
        if (missing.isEmpty()) return "It didn't finish within $hours hours of being proposed."
        val names = missing.map { "@" + ChatMessage.memberName(database, it) }
        return "${names.joinToString(", ")} didn't take their part within $hours hours of it being proposed."
    }

    /** The share this device holds for the room's newest completed ceremony, with what it is a share of. */
    suspend fun completedKey(database: DedunDatabase, chatRoomId: String): IcebergKeySession? =
        database.icebergKeySessionDao().getSessionsForChatRoom(chatRoomId).firstOrNull { it.stage == IcebergRitualStage.COMPLETE && it.share != null }

    fun shareOf(session: IcebergKeySession): Share? = session.share?.let { Share(it.hexToByteArray()) }

    private suspend fun own(database: DedunDatabase, session: IcebergKeySession, kind: Kind): IcebergParticipantMessage? =
        database.icebergKeySessionDao().getMessage(session.id, kind, session.userPublicKey.lowercase())

    private suspend fun update(database: DedunDatabase, session: IcebergKeySession, transform: (IcebergKeySession) -> IcebergKeySession): IcebergKeySession {
        val current = database.icebergKeySessionDao().getSessionById(session.id) ?: session
        val updated = transform(current)
        if (updated == current) return current
        val stamped = updated.copy(updatedAt = Clock.System.now())
        database.icebergKeySessionDao().upsert(stamped)
        return stamped
    }

    private suspend fun moveTo(database: DedunDatabase, session: IcebergKeySession, stage: IcebergRitualStage): IcebergKeySession = update(database, session) { current ->
        val settled = current.stage == IcebergRitualStage.COMPLETE || current.stage == IcebergRitualStage.FAILED
        if (settled || current.stage.ordinal >= stage.ordinal) current else current.copy(stage = stage)
    }

    private suspend fun fail(database: DedunDatabase, session: IcebergKeySession, reason: String, culprit: HexKey = session.userPublicKey) {
        val current = database.icebergKeySessionDao().getSessionById(session.id) ?: session
        if (current.stage == IcebergRitualStage.FAILED) return
        update(database, current) { it.copy(stage = IcebergRitualStage.FAILED, failureReason = reason, root = null) }
        announce(database, current, ChatMessage.TYPE_ICEBERG_FAILED, "abandoned the Lightning wallet's key ceremony. No key was created, and it is safe to run it again.", culprit)
    }

    private suspend fun announceApprovalNeeded(database: DedunDatabase, session: IcebergKeySession, step: IcebergApprovalStep) {
        val asked = (database.icebergKeySessionDao().getSessionById(session.id) ?: session).approvalRequestedThrough
        if (asked != null && asked.ordinal >= step.ordinal) return
        update(database, session) { it.copy(approvalRequestedThrough = step) }
        val (type, content) = when (step) {
            IcebergApprovalStep.JOIN -> ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_JOIN to
                "Your approval is needed to join the Lightning wallet's key ceremony. Nothing has been published from this device yet."
            IcebergApprovalStep.REVEAL -> ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_REVEAL to
                "Everyone has joined the Lightning wallet's key ceremony. Your approval is needed to send your contributions to the key."
            IcebergApprovalStep.CONFIRM -> ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_CONFIRM to
                "Your share of the Lightning wallet's key is assembled. Your approval is needed to publish what you saw, so the group can check it agrees."
        }
        announce(database, session, type, content, session.userPublicKey)
    }

    private suspend fun announceStep(database: DedunDatabase, session: IcebergKeySession, kind: Kind, actor: HexKey) {
        val (type, content) = when (kind) {
            GroupLightningEvents.DKG_ROUND_1 -> ChatMessage.TYPE_ICEBERG_COMMITTED to
                "joined the Lightning wallet's key ceremony, committing to their contributions before anyone reveals one."
            GroupLightningEvents.DKG_ROUND_2 -> ChatMessage.TYPE_ICEBERG_REVEALED to
                "sent their contributions to the key, each one only to the members who will hold it."
            GroupLightningEvents.DKG_ROUND_3 -> ChatMessage.TYPE_ICEBERG_CONFIRMED to
                "assembled their share and published what they saw, for the group to check it agrees."
            else -> return
        }
        announce(database, session, type, content, actor)
    }

    private suspend fun announce(database: DedunDatabase, session: IcebergKeySession, messageType: String, content: String, actor: HexKey) {
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = actor,
                isUserMessage = actor.lowercase() == session.userPublicKey.lowercase(),
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = session.chatRoomId,
                content = content,
                messageType = messageType,
                dkgSessionId = session.id,
            )
        )
    }

    private suspend fun publishOwn(database: DedunDatabase, localChatRoom: LocalChatRoom, session: IcebergKeySession, kind: Kind, content: String) {
        broadcast(database, localChatRoom, session, kind, content)
        val dao = database.icebergKeySessionDao()
        val me = session.userPublicKey.lowercase()
        val known = dao.getMessage(session.id, kind, me) != null
        dao.upsert(IcebergParticipantMessage(session.id, me, kind, content))
        if (!known) announceStep(database, session, kind, me)
    }

    /**
     * Queues one of this device's messages as a Marmot inner event for the room. The p-tags name the
     * participants on every message, as the ChillDKG ritual's do: they are the set the proposal is
     * checked against, and inside MLS naming them leaks nothing.
     */
    private suspend fun broadcast(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: IcebergKeySession,
        kind: Kind,
        content: String,
        includeProposalTags: Boolean = false,
    ) {
        val pTags = session.participants().filterNot { it == session.userPublicKey.lowercase() }.map { PTag.assemble(it, null) }
        val ownTags = buildList {
            add(arrayOf(SESSION_TAG, session.id))
            if (includeProposalTags) {
                add(arrayOf(THRESHOLD_TAG, session.threshold.toString()))
                add(arrayOf(ONCHAIN_TAG, session.onchainDkgSessionId))
            }
        }
        val tags = pTags.toTypedArray() + ownTags.toTypedArray()
        val createdAt = Clock.System.now().epochSeconds
        val id = EventHasher.hashId(pubKey = session.userPublicKey, createdAt = createdAt, tags = tags, content = content, kind = kind)
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
}
