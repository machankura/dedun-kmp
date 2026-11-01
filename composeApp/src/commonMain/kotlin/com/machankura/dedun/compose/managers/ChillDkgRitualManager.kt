package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianDelayTag
import com.machankura.dedun.compose.database.model.subgroupWalletPolicy
import com.machankura.dedun.compose.nostr.subgroup.tags.WalletPolicyTag
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import fr.acinq.secp256k1.ChilldkgFault
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Runs a ChillDKG ritual in a group's room, on whichever transport that room has.
 *
 * The participant set is who the proposal p-tags, the member who opens the ritual
 * is the coordinator, and every protocol message travels on the kinds in
 * [DkgRitualEvents] — as a gift-wrapped rumor in a NIP-17 room and as an MLS
 * application message in a Marmot one, which are the two pipelines chat messages
 * already use, so there is no second transport to operate.
 *
 * ### Two transports, because a subgroup's ceremony is held by its parent
 *
 * A group's own ceremony runs in the NIP-17 room its members share: there is no
 * MLS tree yet, and cannot be, since the room the key will make is derived from
 * the key the ceremony is about to produce.
 *
 * A *subgroup's* ceremony runs in the **parent's** Marmot room. The parent
 * already exists, already holds exactly the people a subgroup can be drawn from,
 * and already signs the child's birth certificate there — so the alternative was
 * a sibling NIP-17 room whose id was an aggregate of the child's admins, which
 * meant one admin set could hold one subgroup ever. [broadcast] is the only place
 * that knows the difference; everything above it is the same protocol either way.
 *
 * The p-tags stay on **both**, unlike `FrostSigningManager`'s. There they are an
 * address list and a Marmot message needs none; here the p-tag set *is* the
 * participant set every device derives `n` from, and in the parent's room that is
 * a subset of who can read the message. Inside MLS encryption, naming them leaks
 * nothing.
 *
 * The coordinator is a participant too. ChillDKG treats the coordinator as
 * untrusted (it relays but cannot learn secrets or bias the key), so opening
 * the ritual buys it no authority here — only the job of aggregating. That is
 * why any member may open one: there is nothing to gain by being the one who
 * does.
 *
 * ### Why this is driven by arriving messages
 *
 * Every step is a pure function of inputs this device has already stored, so
 * there is no long-lived in-memory session to lose. Each inbound message is
 * persisted and then the ritual is asked whether it can move; if the app dies
 * mid-round it picks up exactly where it left off on the next message.
 *
 * [advance] therefore decides what to do from what is *stored* — "is my round-1
 * message out yet?" — and never from how far [DkgSession.stage] says the ritual
 * got. The stage is a label for the UI. Gating the work on it would mean a
 * device killed between "record the stage" and "publish the message" never
 * publishes at all, and a DKG stalls for everyone when one member stalls.
 */
object ChillDkgRitualManager {
    private const val TAG = "ChillDkgRitualManager"

    private val logger = Logger.withTag(TAG)

    /**
     * Domain separator for deriving the ChillDKG host key. The host key must NOT
     * be the nostr identity key: reusing one secret across two protocols means a
     * flaw in either can reach the other. Deriving it from the same seed keeps it
     * recoverable from the wallet backup without being the same key.
     *
     * The `mantra/` prefix has now survived four rebrands on purpose -- Mantra to
     * Curated, Curated to Curare, Curare to Bennu, Bennu to Dedun. It is an input to the hash, so renaming it derives
     * a different host key from the same seed --
     * which loses the outputs of every DKG session already run, since ChillDKG needs
     * the host secret key to recover them. Renaming it is a protocol fork.
     */
    private const val HOST_KEY_DERIVATION_TAG = "mantra/chilldkg/host-key/v1"

    /**
     * Smallest group a ritual can run in. A shared key held by one person is
     * just a key, and [ChatRoomType.quorumRange] has nothing to offer below two.
     */
    const val MINIMUM_PARTICIPANTS = 2

    /** Why a ceremony a participant walked away from ended, as every device records it. */
    private const val ABANDONED = "Abandoned by a participant"

    /**
     * This device's long-term ChillDKG host secret key.
     *
     * Deterministic in the nostr secret key, so it survives a reinstall from the
     * wallet seed alone — ChillDKG requires the host secret key to recover a
     * session's outputs, and asking a chat user to back up a second secret is a
     * good way to lose keys.
     */
    fun deriveHostSecretKey(nostrPrivateKey: ByteArray): PrivateKey =
        PrivateKey(Crypto.sha256(HOST_KEY_DERIVATION_TAG.encodeToByteArray() + nostrPrivateKey))

    fun deriveHostPublicKey(nostrPrivateKey: ByteArray): PublicKey =
        ChillDKG.hostPublicKey(deriveHostSecretKey(nostrPrivateKey))

    /**
     * The room's members, one entry per person.
     *
     * [LocalChatRoom.localParticipants] is a row list, not a set — `Participant`
     * is keyed on an autogenerated id, so a room can carry the same member
     * twice. The participant count is `n`, which every device has to agree on
     * or ChillDKG rejects the session, so it must be counted over people.
     */
    fun memberPublicKeys(localChatRoom: LocalChatRoom): Set<HexKey> =
        localChatRoom.localParticipants.map { it.participant.participantPublicKey }.toSet()

    /**
     * Whether a group of this size can hold a shared key at all. Read by the UI
     * so a ritual that could only fail is never offered.
     */
    fun canRunRitual(localChatRoom: LocalChatRoom): Boolean =
        memberPublicKeys(localChatRoom).size >= MINIMUM_PARTICIPANTS

    /**
     * Whether [localChatRoom] is a Marmot room, and so can host a subgroup's
     * ceremony but never one of its own. The same reading [broadcast] makes to
     * pick a transport.
     */
    fun isMarmotRoom(localChatRoom: LocalChatRoom): Boolean =
        localChatRoom.chatRoom.mlsGroupState != null

    /**
     * Everyone a ceremony is being run with — which is not the same question as
     * who is in the room it runs in.
     *
     * A subgroup's ceremony is held in the parent's room over a subset of the
     * parent's members, so the room's roster names people the ceremony has
     * nothing to do with. [DkgSession.participantPublicKeys] is the set the
     * ceremony was opened on, taken from the proposal's p-tags on every device
     * that did not open it.
     *
     * The room is the fallback, and only for rows written before that column
     * existed: every ceremony then ran in a room whose members were exactly its
     * participants, so reading the room gave the same answer.
     */
    fun participantsOf(session: DkgSession, localChatRoom: LocalChatRoom): Set<HexKey> =
        session.participantPublicKeySet() ?: memberPublicKeys(localChatRoom)

    /**
     * Opens a ritual, making this device the coordinator. A room already running
     * one for the same purpose gets that one back rather than a second.
     *
     * [participantPublicKeys] is who the ceremony is with, and defaults to the
     * room's members -- which is the whole story for a group's own ceremony,
     * where the room *is* the participant set. A subgroup's ceremony runs in the
     * parent's room over a subset of it, so that caller names the set, and this
     * device is added to it either way: opening a ceremony is being in it.
     *
     * [subject] is what the room this ceremony's key derives will be called. A
     * group's own ceremony takes the name of the room it runs in, since the room
     * it makes administers that group; a subgroup's is named by the coordinator,
     * and is carried to the other admins on the proposal because there is no
     * longer a room of its own for them to read it off.
     *
     * [walletPolicy] is what the subgroup's wallet is to be, as the coordinator
     * chose it on the picker -- carried on the proposal so every admin is told
     * before joining, and on the session so whoever proposes the birth
     * certificate proposes it under the same one. Only a subgroup's ceremony has
     * one. See `docs/subgroup-wallet-policy.md`.
     *
     * [guardianDelay] is the delay, in blocks, the coordinator chose for the
     * parent's own leaf in a guarded subgroup's wallet, carried the same way and
     * for the same reasons. Only a guarded ceremony has one, and it is in
     * `GuardianDerivation.DELAY_RANGE`. See `docs/guardian-delay.md`.
     *
     * Throws when the group or the threshold cannot support one; the UI checks
     * both before offering the button, so reaching either is a bug rather than a
     * user error.
     */
    suspend fun proposeRitual(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        threshold: Int,
        parentChatRoomId: HexKey? = null,
        participantPublicKeys: Set<HexKey>? = null,
        subject: String? = null,
        walletPolicy: SubgroupWalletPolicy? = null,
        guardianDelay: Long? = null
    ): DkgSession {
        // A policy is for a subgroup's wallet, and a ceremony with no parent makes
        // no subgroup. `acceptProposal` drops the same thing on the way in.
        // Before the running check, which would otherwise hand back a ceremony
        // for a question this one should never have asked.
        require(walletPolicy == null || parentChatRoomId != null) {
            "Only a subgroup's ceremony carries a wallet policy"
        }
        // A delay is a guarded wallet's, and within what a leaf can carry.
        require(guardianDelay == null || walletPolicy == SubgroupWalletPolicy.GUARDED) {
            "Only a guarded subgroup's ceremony carries a guardian delay"
        }
        require(guardianDelay == null || guardianDelay in GuardianDerivation.DELAY_RANGE) {
            "A guardian's delay is ${GuardianDerivation.MIN_DELAY} to ${GuardianDerivation.MAX_DELAY} blocks, not $guardianDelay"
        }

        // Built the way a receiver rebuilds it from the proposal — the p-tags
        // `broadcast` writes, plus this device — so both sides count the same `n`
        // even if the room's own rows have drifted.
        val members = (participantPublicKeys ?: memberPublicKeys(localChatRoom)) + userPublicKey

        // A room runs one ceremony at a time *for one purpose*, and this is
        // reachable twice now that a robust group opens one as it is created: the
        // room id is derived from its members, so making the same group again
        // lands back in the same room and asks again. A second proposal for the
        // same purpose is a second participant set for every member to reconcile,
        // and the key the first one produced would be left with nothing pointing
        // at it. A failed ritual is not running, and is there to be replaced.
        //
        // What "the same purpose" means differs by which kind of ceremony this
        // is, because a parent's room hosts every subgroup ceremony the group
        // ever runs. Scoping a subgroup's on the room would refuse the parent's
        // *second* subgroup on the strength of its first.
        //
        // Checked before the threshold is, because a running ritual makes the
        // requested threshold moot -- it settled that question when it opened.
        val running = if (parentChatRoomId == null) {
            database.dkgSessionDao()
                .getLatestSessionFor(localChatRoom.chatRoom.id, null)
                ?.takeIf { it.stage != DkgRitualStage.FAILED }
        } else {
            // Same parent, same admins -- and still running. A *completed* one is
            // deliberately not a reason to refuse: it means that subgroup was
            // made, and a group is entitled to a second one run by the same
            // people. That was impossible while the ceremony room was derived
            // from the admins, since asking again handed back the first
            // ceremony's key and the "new" subgroup was the old one renamed.
            database.dkgSessionDao().getLatestSubgroupSessionFor(
                chatRoomId = localChatRoom.chatRoom.id,
                parentChatRoomId = parentChatRoomId,
                participantPublicKeys = DkgSession.formatParticipants(members).orEmpty()
            )?.takeIf {
                it.stage != DkgRitualStage.FAILED && it.stage != DkgRitualStage.COMPLETE
            }
        }

        if (running != null) {
            logger.i(
                "Room ${localChatRoom.chatRoom.id} is already running ritual " +
                    "${running.id}; not opening another"
            )
            return running
        }

        // Everyone in a ceremony has to be able to hear it. In a NIP-17 room a
        // gift wrap is sealed per p-tag so this was true by construction; in a
        // Marmot room a message reaches the MLS tree and nobody else, so a
        // participant outside it is an `n` that can never be met. Checked here
        // because `SubgroupManager.refuseSubgroup` checking it is a screen not
        // drawing something, which is not a guard.
        val room = memberPublicKeys(localChatRoom) + localChatRoom.chatRoom.userPublicKey
        require(room.containsAll(members)) {
            "A ceremony in ${localChatRoom.chatRoom.id} cannot include " +
                "${(members - room).joinToString { it.take(8) }}, who are not in it"
        }

        // A Marmot room's id is derived from the key it already has, so a ceremony
        // of its own would make a key the room could never answer to. It hosts its
        // subgroups' ceremonies and nothing else. The screens stopped offering one
        // here, and this is what makes that a rule rather than a screen not drawing
        // a button -- see docs/ceremony-creation.md.
        require(parentChatRoomId != null || !isMarmotRoom(localChatRoom)) {
            "${localChatRoom.chatRoom.id} is a Marmot room; it can only host a subgroup's ceremony"
        }

        require(members.size >= MINIMUM_PARTICIPANTS) {
            "A shared key needs at least $MINIMUM_PARTICIPANTS members, this ceremony has ${members.size}"
        }
        require(threshold in ChatRoomType.quorumRange(members.size)) {
            "Quorum $threshold is outside ${ChatRoomType.quorumRange(members.size)} for ${members.size} members"
        }

        // A subgroup is named by whoever is making it. Falling back to the room
        // there would name a child after its parent, which is the one name it
        // must not have -- the certificate the parent signs is over this string.
        val ceremonySubject = subject?.takeIf { it.isNotBlank() }
            ?: localChatRoom.chatRoom.subject?.takeIf { parentChatRoomId == null }

        val sessionId = RandomInstance.bytes(32).toHex()

        val session = DkgSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            threshold = threshold,
            // The `n` every other device will derive from this proposal's p-tags,
            // which `broadcast` assembles from exactly this set.
            participantCount = members.size,
            // And the set itself, because the room can no longer be asked: a
            // subgroup's ceremony runs in the parent's room over a subset of it.
            participantPublicKeys = DkgSession.formatParticipants(members),
            subject = ceremonySubject,
            walletPolicy = walletPolicy?.tagValue,
            guardianDelay = guardianDelay,
            hostPublicKey = deriveHostPublicKey(nostrPrivateKey).value.toHex(),
            // Fresh per session. Persisted because participantStep1/2 are pure in
            // it, which is what makes the whole ritual restart-safe.
            round1Random = RandomInstance.bytes(32).toHex(),
            round2AuxRandom = RandomInstance.bytes(32).toHex(),
            // Opening a ceremony is already the act of agreeing to be in it, so this
            // member is not asked again to publish the host key they just committed
            // the group to. Rounds 1 and 2 are still asked for: those publish key
            // material, and by then the ceremony has other people in it.
            hostKeyApprovedAt = Clock.System.now(),
            // What this ceremony is for, if it is for anything in particular. The
            // coordinator's own copy of the claim they are about to put on the
            // proposal -- unverified there and unverified here, and read for
            // nothing but a screen's title and a way back into an unfinished
            // subgroup flow. See `DkgSession.parentChatRoomId`.
            parentChatRoomId = parentChatRoomId
        )
        database.dkgSessionDao().upsert(session)
        announceStarted(database, session)

        logger.i("Proposing DKG ritual $sessionId: $threshold-of-${members.size}")

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = DkgRitualEvents.PROPOSAL,
            content = "",
            includeThreshold = true,
            // Only on the proposal. Every later message belongs to a session the
            // receiver already has both of these on, so repeating them would be
            // bytes per round to say something already settled -- and would give a
            // later message a say in what an opened ceremony is for.
            includeParent = true,
            // What the room this key derives is to be called. For a group's own
            // ceremony that is the NIP-17 room's name, which until this tag
            // existed reached nobody but its creator --
            // `NostrDao.getOrCreateNip17ChatRoom` has always read a subject off
            // the payload that first tells it about a room, and a ritual proposal
            // is routinely that payload. For a subgroup it is the only way the
            // name travels at all, since the child has no room yet and the room
            // this runs in is the parent's.
            subject = ceremonySubject
        )

        publishHostKey(database, localChatRoom, session)

        return session
    }

    /**
     * Feeds one inbound ritual message in and advances the ritual as far as it
     * will go. Safe to call twice with the same message: every write is keyed
     * and every step recomputed from stored inputs.
     *
     * [innerEvent] is the rumor as its sender wrote it, whichever transport
     * carried it -- the decrypted gift wrap in a NIP-17 room, the inner event of
     * a kind:445 in a Marmot one. `sig` is empty on both and nothing here reads
     * it: what vouches for the author is the seal or the MLS frame, not a
     * signature on the payload.
     */
    suspend fun processRitualPayload(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray
    ) {
        val sessionId = DkgRitualEvents.parseSessionId(innerEvent.tags)
        if (sessionId == null) {
            logger.w("DKG payload ${innerEvent.id} has no session tag; dropping")
            return
        }

        val session = if (innerEvent.kind == DkgRitualEvents.PROPOSAL) {
            acceptProposal(
                database = database,
                localChatRoom = localChatRoom,
                innerEvent = innerEvent,
                sessionId = sessionId,
                userPublicKey = userPublicKey,
                nostrPrivateKey = nostrPrivateKey
            )
        } else {
            // Not knowing the session is normal: relays hand events back in no
            // particular order -- and gift wraps carry a randomised created_at on
            // top of that -- so a round-1 message routinely lands before the
            // proposal that opens the ritual. The payload is already stored, and
            // [acceptProposal] replays it once the proposal turns up.
            database.dkgSessionDao().getSessionById(sessionId)
        }

        if (session == null) {
            logger.i("No DKG session $sessionId for kind ${innerEvent.kind}; leaving it stored")
            return
        }

        if (session.stage == DkgRitualStage.FAILED) {
            logger.i("Ritual $sessionId already failed; ignoring kind ${innerEvent.kind}")
            return
        }

        if (!record(database, localChatRoom, session, innerEvent)) return

        advance(
            database = database,
            localChatRoom = localChatRoom,
            sessionId = session.id,
            nostrPrivateKey = nostrPrivateKey
        )
    }

    /**
     * Records a proposal and answers it with this device's host key. Returns the
     * session, whether it was just created or already known.
     */
    private suspend fun acceptProposal(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray
    ): DkgSession? {
        database.dkgSessionDao().getSessionById(sessionId)?.let { return it }

        // The receiving half of `proposeRitual`'s refusal. A build from before it
        // can still put a group's own ceremony on the wire in a Marmot room, and
        // recording it would show every member a ceremony whose key the room can
        // never use -- which is how a second ceremony ended up beside a subgroup's.
        if (isMarmotRoom(localChatRoom) &&
            DkgRitualEvents.parseParentChatRoomId(innerEvent.tags) == null
        ) {
            logger.w(
                "DKG proposal $sessionId names no parent in Marmot room " +
                    "${localChatRoom.chatRoom.id}; dropping"
            )
            return null
        }

        // The participant set is the proposal's own p-tags plus its sender, NOT
        // this device's view of the room. `n` is hashed into the session identity,
        // so two devices that disagree on it cannot complete a ritual together —
        // and local membership is exactly the thing that drifts between devices.
        //
        // In the parent's room it is not even meant to match: a subgroup's
        // ceremony is run by some of the room, and every member outside the set
        // drops the proposal below rather than joining a ceremony they were not
        // picked for.
        val members = participantsFrom(innerEvent)

        if (userPublicKey !in members) {
            logger.i("DKG proposal $sessionId does not include this device; dropping")
            return null
        }

        if (members.size < MINIMUM_PARTICIPANTS) {
            logger.w("DKG proposal $sessionId has only ${members.size} participant(s); dropping")
            return null
        }

        val threshold = DkgRitualEvents.parseThreshold(innerEvent.tags)
        if (threshold == null) {
            logger.w("DKG proposal $sessionId carries no threshold; dropping")
            return null
        }

        // A ritual is worth joining only at a quorum the group could have agreed
        // to. `t = 1` is a threshold key any single member can sign with, and
        // ChillDKG will happily generate one — the check has to be here.
        if (threshold !in ChatRoomType.quorumRange(members.size)) {
            logger.w(
                "DKG proposal $sessionId asks for a $threshold-of-${members.size} key, " +
                    "outside ${ChatRoomType.quorumRange(members.size)}; dropping"
            )
            return null
        }

        // A policy is only for a subgroup's wallet, and one that does not read is
        // not one to join on the assumption it says nothing: an admin would be
        // shown no policy and then asked to sign a key state under one.
        val walletPolicy = when (val tag = DkgRitualEvents.parseWalletPolicy(innerEvent.tags)) {
            WalletPolicyTag.Result.Absent -> null
            WalletPolicyTag.Result.Unreadable -> {
                logger.w("DKG proposal $sessionId carries a wallet policy that does not read; dropping")
                return null
            }
            is WalletPolicyTag.Result.Present -> tag.policy
        }
        if (walletPolicy != null && DkgRitualEvents.parseParentChatRoomId(innerEvent.tags) == null) {
            logger.w("DKG proposal $sessionId carries a wallet policy and names no parent; dropping")
            return null
        }

        // The same for the delay: a guarded subgroup's only, and in range. An
        // admin shown no delay must not be asked to sign a certificate under one.
        val guardianDelay = when (val tag = DkgRitualEvents.parseGuardianDelay(innerEvent.tags)) {
            GuardianDelayTag.Result.Absent -> null
            GuardianDelayTag.Result.Unreadable -> {
                logger.w("DKG proposal $sessionId carries a guardian delay that does not read; dropping")
                return null
            }
            is GuardianDelayTag.Result.Present -> tag.delay
        }
        if (guardianDelay != null && walletPolicy != SubgroupWalletPolicy.GUARDED) {
            logger.w("DKG proposal $sessionId carries a guardian delay for a subgroup that is not guarded; dropping")
            return null
        }

        val session = DkgSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            // Whoever opens the ritual coordinates it. ChillDKG gives the
            // coordinator no power over the outcome, so there is nothing here to
            // reserve for a room's creator or its admins — and in the parent's
            // room, where a member who is neither can put a proposal on the wire,
            // that is what keeps this from needing a permission check. The worst
            // such a proposal can do is ask: the birth certificate two steps later
            // needs the parent's own quorum, which is the check that was always
            // going to be the check.
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            threshold = threshold,
            participantCount = members.size,
            // The set the count is of, kept so the room never has to be asked.
            // In the parent's room asking it would answer with the parent's
            // membership, which is every person this ceremony is not with.
            participantPublicKeys = DkgSession.formatParticipants(members),
            // What the room this key derives is to be called, as claimed. Taken
            // as said for the same reason the parent below is: nothing is granted
            // on it, and the certificate the parent's quorum signs is over this
            // name, so an admin who dislikes it declines that.
            subject = DkgRitualEvents.parseSubject(innerEvent.tags),
            // What the subgroup's wallet is to be, as claimed -- shown at the join
            // gate, so joining is agreeing to it. See the checks above.
            walletPolicy = walletPolicy?.tagValue,
            guardianDelay = guardianDelay,
            hostPublicKey = deriveHostPublicKey(nostrPrivateKey).value.toHex(),
            round1Random = RandomInstance.bytes(32).toHex(),
            round2AuxRandom = RandomInstance.bytes(32).toHex(),
            // Taken as said, because there is nothing here to check it against and
            // nothing is granted on it. A proposal claiming a parent this member has
            // never heard of, or one they are not in, still opens exactly the
            // ceremony it would have opened without the tag -- the participant set
            // is the p-tags and the threshold is the threshold. What it changes is
            // that the screen can say what the ceremony is for. The parent's own
            // signature turns up two steps later, on the birth certificate.
            parentChatRoomId = DkgRitualEvents.parseParentChatRoomId(innerEvent.tags)
        )
        database.dkgSessionDao().upsert(session)
        announceStarted(database, session)

        logger.i("Recorded DKG ritual $sessionId ($threshold-of-${members.size}); awaiting approval")

        // Deliberately publishes nothing. This used to put the device's host key on
        // the wire, which meant a relay delivering an event was enough to enrol its
        // owner in the group's permanent signing quorum. The session is recorded so
        // the ritual can be shown and stored messages replayed; [advance] sends
        // nothing until [approve] has been called.
        announceApprovalNeeded(database, session, DkgApprovalStep.HOST_KEY)
        replayStoredMessages(database, localChatRoom, session)

        return session
    }

    /**
     * Feeds in every message of this ritual that arrived before the proposal did.
     *
     * They were dropped at the time for want of a session to file them under, but
     * the inbound path stores every payload it decrypts before dispatching on
     * kind, so nothing was actually lost — this reads them back out. Without it a
     * ritual whose proposal loses the race to its own round-1 messages waits
     * forever on messages that already arrived.
     *
     * Out of whichever store the room's transport writes to, which has to be the
     * same reading [broadcast] makes: a ceremony in a NIP-17 room has its backlog
     * in the gift-wrap payloads and none at all in the inner events, and looking
     * in the wrong one is a stall with nothing to blame it on.
     */
    private suspend fun replayStoredMessages(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: DkgSession
    ) {
        val kinds = DkgRitualEvents.ALL.toList()

        val received = if (localChatRoom.chatRoom.mlsGroupState != null) {
            database.marmotInnerEventDao()
                .getByChatRoomAndKinds(chatRoomId = session.chatRoomId, kinds = kinds)
                .map { stored ->
                    Event(
                        id = stored.id,
                        pubKey = stored.publicKey,
                        createdAt = stored.createdAt.epochSeconds,
                        kind = stored.kind,
                        tags = stored.tags,
                        content = stored.content,
                        sig = ""
                    )
                }
        } else {
            database.giftWrapPayloadDao()
                .getByChatRoomAndKinds(chatRoomId = session.chatRoomId, kinds = kinds)
                .map { stored ->
                    Event(
                        id = stored.id,
                        pubKey = stored.publicKey,
                        createdAt = stored.createdAt.epochSeconds,
                        kind = stored.kind,
                        tags = stored.tags,
                        content = stored.content,
                        sig = ""
                    )
                }
        }

        val stored = received.filter { payload ->
            payload.kind != DkgRitualEvents.PROPOSAL &&
                DkgRitualEvents.parseSessionId(payload.tags) == session.id
        }

        if (stored.isEmpty()) return

        logger.i("Replaying ${stored.size} stored message(s) for ritual ${session.id}")

        stored.forEach { payload ->
            if (!record(database, localChatRoom, session, payload)) return
        }
    }

    /**
     * Files one ritual message. Returns false when the message ends the ritual,
     * so the caller stops rather than trying to advance a dead session.
     *
     * The session row is re-read for every write: this is called in a loop during
     * a replay, and each caller holding its own stale copy would have them
     * clobber each other's columns.
     */
    private suspend fun record(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: DkgSession,
        innerEvent: Event
    ): Boolean {
        when (innerEvent.kind) {
            DkgRitualEvents.PROPOSAL -> Unit // handled by acceptProposal; host key already published

            DkgRitualEvents.HOST_KEY,
            DkgRitualEvents.ROUND_1,
            DkgRitualEvents.ROUND_2 -> {
                // Read before writing so the line goes out once. Relays redeliver,
                // and a replay feeds the whole backlog through here again; the
                // upsert below absorbs that, but a chat row has no key to absorb it
                // with.
                val known = database.dkgSessionDao()
                    .getMessage(session.id, innerEvent.kind, innerEvent.pubKey) != null

                database.dkgSessionDao().upsert(
                    DkgParticipantMessage(
                        sessionId = session.id,
                        participantPublicKey = innerEvent.pubKey,
                        kind = innerEvent.kind,
                        payload = innerEvent.content
                    )
                )

                if (!known) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            // Both aggregates come from the coordinator and only ever once. Taking
            // them from anyone else lets any member stall the ritual by getting a
            // bogus one in first, since the real one would then be ignored.
            DkgRitualEvents.COORDINATOR_ROUND_1 -> {
                if (!isFromCoordinator(session, innerEvent)) return true

                val known = current(database, session).coordinatorRound1 != null

                update(database, session) { current ->
                    if (current.coordinatorRound1 == null) {
                        current.copy(coordinatorRound1 = innerEvent.content)
                    } else {
                        current
                    }
                }

                if (!known) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            DkgRitualEvents.CERTIFICATE -> {
                if (!isFromCoordinator(session, innerEvent)) return true

                val known = current(database, session).certificate != null

                update(database, session) { current ->
                    if (current.certificate == null) {
                        current.copy(certificate = innerEvent.content)
                    } else {
                        current
                    }
                }

                if (!known) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            DkgRitualEvents.FAILURE -> {
                // Only somebody in the ceremony can end it. In a parent's room every
                // member can read a subgroup's proposal, and so could name its
                // session -- but a member who was never in it walking away is not
                // the ceremony failing.
                if (innerEvent.pubKey !in participantsOf(session, localChatRoom)) {
                    logger.w(
                        "Ritual ${session.id}: FAILURE from ${innerEvent.pubKey.take(8)}, " +
                            "who is not in it; ignoring"
                    )
                    return true
                }

                fail(
                    database = database,
                    session = session,
                    reason = "Abandoned by ${innerEvent.pubKey.take(8)}: ${innerEvent.content}",
                    culprit = innerEvent.pubKey
                )
                return false
            }
        }

        return true
    }

    private fun isFromCoordinator(session: DkgSession, innerEvent: Event): Boolean {
        if (innerEvent.pubKey == session.coordinatorPublicKey) return true

        logger.w(
            "Ritual ${session.id}: kind ${innerEvent.kind} from " +
                "${innerEvent.pubKey.take(8)}, who is not the coordinator; ignoring"
        )
        return false
    }

    /**
     * Takes every step the stored messages now allow, in order, stopping at the
     * first one still waiting on somebody.
     *
     * Each step asks whether its output already exists rather than whether the
     * stage says it has run, so re-entering after a crash — or after the same
     * message is delivered twice — repeats no work and skips none.
     */
    private suspend fun advance(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String,
        nostrPrivateKey: ByteArray
    ) {
        var session = database.dkgSessionDao().getSessionById(sessionId) ?: return
        if (session.stage == DkgRitualStage.COMPLETE || session.stage == DkgRitualStage.FAILED) return

        val hostSeckey = deriveHostSecretKey(nostrPrivateKey)

        try {
            // Nothing of this device's own goes out before its owner has said so.
            // Each gate returns rather than throwing: the ritual is not failing, it
            // is waiting on a person, and everything already received stays stored
            // so it resumes the moment they approve.
            if (session.hostKeyApprovedAt == null) return

            if (ownMessage(database, session, DkgRitualEvents.HOST_KEY) == null) {
                publishHostKey(database, localChatRoom, session)
            }

            // Waiting on host keys: the participant set isn't settled, so there is
            // nothing this device can compute yet.
            val hostPublicKeys = hostPublicKeys(database, session) ?: return

            // A second decision, and a different one: the host key only joined the
            // ceremony, this contributes to the key itself.
            if (session.round1ApprovedAt == null) {
                announceApprovalNeeded(database, session, DkgApprovalStep.ROUND_1)
                return
            }

            val step1 = ChillDKG.participantStep1(
                hostSecretKey = hostSeckey,
                hostPublicKeys = hostPublicKeys,
                threshold = session.threshold,
                random = ByteVector32(session.round1Random)
            )

            if (ownMessage(database, session, DkgRitualEvents.ROUND_1) == null) {
                publishOwn(database, localChatRoom, session, DkgRitualEvents.ROUND_1, step1.message.toHex())
            }
            session = moveTo(database, session, DkgRitualStage.COLLECTING_ROUND_1)

            if (session.isCoordinator() && session.coordinatorRound1 == null) {
                val pmsgs1 = orderedPayloads(database, session, DkgRitualEvents.ROUND_1) ?: return
                val cmsg1 = coordinatorStep1(pmsgs1, hostPublicKeys, session).message.toHex()

                session = update(database, session) { it.copy(coordinatorRound1 = cmsg1) }
                broadcast(database, localChatRoom, session, DkgRitualEvents.COORDINATOR_ROUND_1, cmsg1)
                announceStep(
                    database,
                    session,
                    DkgRitualEvents.COORDINATOR_ROUND_1,
                    session.userPublicKey
                )
            }

            val coordinatorRound1 = session.coordinatorRound1 ?: return

            // The last decision, and a real check rather than a formality: signing
            // here asserts the combined result matches what this device sent, which
            // is what stops a coordinator swapping in a key the members never
            // contributed to.
            if (session.round2ApprovedAt == null) {
                announceApprovalNeeded(database, session, DkgApprovalStep.ROUND_2)
                return
            }

            val step2 = ChillDKG.participantStep2(
                hostSecretKey = hostSeckey,
                state = step1.state,
                coordinatorMessage = ByteVector(coordinatorRound1.hexToByteArray()),
                auxRand = ByteVector32(session.round2AuxRandom)
            )
            step2.fault.raiseIfFaulty("round 2")

            if (ownMessage(database, session, DkgRitualEvents.ROUND_2) == null) {
                publishOwn(
                    database,
                    localChatRoom,
                    session,
                    DkgRitualEvents.ROUND_2,
                    step2.certEqSignature.toHex()
                )
            }
            session = moveTo(database, session, DkgRitualStage.COLLECTING_ROUND_2)

            if (session.isCoordinator() && session.certificate == null) {
                val pmsgs2 = orderedPayloads(database, session, DkgRitualEvents.ROUND_2) ?: return

                // Rebuild the coordinator state from the round-1 messages rather
                // than holding it across rounds — same inputs, same state, and it
                // survives a restart between the two aggregations.
                val pmsgs1 = orderedPayloads(database, session, DkgRitualEvents.ROUND_1) ?: return
                val certified = ChillDKG.coordinatorFinalize(
                    state = coordinatorStep1(pmsgs1, hostPublicKeys, session).state,
                    certEqSignatures = pmsgs2.map { ByteVector64(it) },
                    threshold = session.threshold
                )
                certified.fault.raiseIfFaulty("certificate")
                val cmsg2 = certified.certificate.toHex()

                session = update(database, session) { it.copy(certificate = cmsg2) }
                broadcast(database, localChatRoom, session, DkgRitualEvents.CERTIFICATE, cmsg2)
                announceStep(database, session, DkgRitualEvents.CERTIFICATE, session.userPublicKey)
            }

            val certificate = session.certificate ?: return

            // The payoff: verify the certificate and keep the share.
            val output = ChillDKG.participantFinalize(
                state = step2.state,
                certificate = ByteVector(certificate.hexToByteArray()),
                nParticipants = hostPublicKeys.size,
                threshold = session.threshold
            )
            output.fault.raiseIfFaulty("finalize")

            update(database, session) {
                it.copy(
                    stage = DkgRitualStage.COMPLETE,
                    thresholdPublicKey = output.thresholdPublicKey?.value?.toHex(),
                    secretShare = output.secretShare?.value?.toHex(),
                    // Kept for signing, which needs every participant's public
                    // share to place and check the signers. In participant order,
                    // the same order the ids are derived from.
                    publicShares = output.publicShares
                        .joinToString(",") { share -> share.value.toHex() }
                        .takeIf { output.publicShares.isNotEmpty() },
                    recoveryData = output.recovery?.toHex()
                )
            }
            announce(
                database = database,
                session = session,
                messageType = ChatMessage.TYPE_DKG_COMPLETE,
                content = "The group has a shared key. It takes ${session.threshold} of " +
                    "${session.participantCount} members to sign with it.",
                actor = session.coordinatorPublicKey
            )

            logger.i("DKG ritual $sessionId complete")
        } catch (e: CancellationException) {
            // The sync was torn down mid-step, which says nothing about the ritual.
            // Killing the session here would abandon it — and tell the whole group
            // to abandon it — because this device closed a coroutine scope.
            throw e
        } catch (e: Throwable) {
            // The session is over for this device. Either a protocol fault came
            // back from a ChillDKG step -- [raiseIfFaulty] turns those into
            // exceptions naming the culprit -- or a step rejected a local input.
            // Either way the key is not usable, so say so rather than leaving a
            // spinner running forever.
            logger.e("DKG ritual $sessionId failed", e)
            fail(database, session, e.message ?: e::class.simpleName ?: "Unknown error")
            broadcast(
                database = database,
                localChatRoom = localChatRoom,
                session = session,
                kind = DkgRitualEvents.FAILURE,
                content = e.message ?: "Ritual failed"
            )
        }
    }

    /**
     * The agreed participant set, or null while host keys are still outstanding.
     * Ordering is a bytewise sort of the host public keys: ChillDKG fails outright
     * if the participants disagree on the order, and sorting is the only ordering
     * every device can derive independently.
     *
     * ChillDKG has no session-params object: every step takes this list and the
     * threshold, and hashes them into the session identity itself.
     */
    private suspend fun hostPublicKeys(
        database: DedunDatabase,
        session: DkgSession
    ): List<PublicKey>? {
        val hostKeys = database.dkgSessionDao().getMessagesByKind(session.id, DkgRitualEvents.HOST_KEY)

        if (hostKeys.size < session.participantCount) {
            logger.d("Ritual ${session.id}: ${hostKeys.size}/${session.participantCount} host keys")
            return null
        }

        // More host keys than the ritual was opened for means someone is running a
        // different participant set than the proposal named. Every device would
        // derive a different session identity from here on, so there is no key to
        // be had — better to say so than to grind to a halt inside ChillDKG.
        check(hostKeys.size == session.participantCount) {
            "${hostKeys.size} participants joined a ritual opened for ${session.participantCount}"
        }

        // Case-folded before sorting: hex from another client could arrive upper
        // case, and it is the *order* that has to match on every device, not the
        // bytes — a stray capital would silently reorder the participant set.
        return hostKeys.map { it.payload.lowercase() }
            .sorted()
            .map { PublicKey(ByteVector(it.hexToByteArray())) }
    }

    /**
     * The coordinator's round-1 aggregation. Called twice — once to publish
     * `cmsg1`, once to rebuild the state the finalization needs — so the fault
     * check lives here rather than at both call sites.
     */
    private fun coordinatorStep1(
        pmsgs1: List<ByteArray>,
        hostPublicKeys: List<PublicKey>,
        session: DkgSession
    ) = ChillDKG.coordinatorStep1(
        participantMessages = pmsgs1.map { ByteVector(it) },
        hostPublicKeys = hostPublicKeys,
        threshold = session.threshold
    ).also { it.fault.raiseIfFaulty("coordinator round 1") }

    /**
     * Turns a protocol fault into an exception so it joins [advance]'s single
     * failure path.
     *
     * ChillDKG reports faults as values, not exceptions, because a faulty
     * participant is a normal outcome of a DKG rather than a bug. This ritual has
     * exactly one response to every one of them — the key is unusable, so the
     * session dies and the group is told — so there is nothing to gain from
     * handling them individually. What is worth keeping is the culprit: on a
     * failed DKG, which participant to blame is the only actionable thing there
     * is, and it goes into the failure reason shown to the user.
     */
    private fun ChilldkgFault.raiseIfFaulty(step: String) {
        if (isOk) return

        val culprit = participantIndex?.let { " (participant $it)" } ?: ""
        val reason = when (code) {
            ChilldkgFault.FAULTY_COORDINATOR -> "the coordinator is faulty"
            ChilldkgFault.FAULTY_PARTICIPANT -> "a participant is faulty"
            ChilldkgFault.FAULTY_PARTICIPANT_OR_COORDINATOR -> "a participant or the coordinator is faulty"
            ChilldkgFault.UNKNOWN_FAULTY_PARTICIPANT_OR_COORDINATOR ->
                "an unidentified participant or the coordinator is faulty"
            ChilldkgFault.INVALID_INPUT -> "invalid input"
            else -> "unrecognised fault code $code"
        }
        throw IllegalStateException("ChillDKG $step failed: $reason$culprit")
    }

    /**
     * Round messages in participant order — i.e. ordered by the sender's host key,
     * matching the [hostPublicKeys] ordering. Null while any are missing: both
     * coordinator steps require exactly `n` entries.
     */
    private suspend fun orderedPayloads(
        database: DedunDatabase,
        session: DkgSession,
        kind: Kind
    ): List<ByteArray>? {
        val hostKeyByParticipant = database.dkgSessionDao()
            .getMessagesByKind(session.id, DkgRitualEvents.HOST_KEY)
            .associate { it.participantPublicKey to it.payload }

        val messages = database.dkgSessionDao().getMessagesByKind(session.id, kind)
        if (messages.size < session.participantCount) {
            logger.d("Ritual ${session.id}: ${messages.size}/${session.participantCount} of kind $kind")
            return null
        }

        // Pair each message with its sender's host key so it can be sorted into
        // participant order. A message from someone who never published a host key
        // cannot be placed, and a short list would be rejected by ChillDKG anyway.
        val orderable = messages.mapNotNull { message ->
            hostKeyByParticipant[message.participantPublicKey]?.let { hostKey ->
                hostKey.lowercase() to message.payload
            }
        }
        if (orderable.size < session.participantCount) {
            logger.d("Ritual ${session.id}: ${orderable.size}/${session.participantCount} of kind $kind are placeable")
            return null
        }

        return orderable
            .sortedBy { (hostKey, _) -> hostKey }
            .map { (_, payload) -> payload.hexToByteArray() }
    }

    /** This device's own message of [kind], if it has published one. */
    private suspend fun ownMessage(
        database: DedunDatabase,
        session: DkgSession,
        kind: Kind
    ): DkgParticipantMessage? = database.dkgSessionDao().getMessage(
        sessionId = session.id,
        kind = kind,
        participantPublicKey = session.userPublicKey
    )

    /**
     * Applies [transform] to the *stored* session and returns what was written.
     *
     * Callers hold a session across several steps, each of which may write; going
     * back to the row means a later write cannot silently undo an earlier one by
     * copying from a stale snapshot.
     */
    private suspend fun update(
        database: DedunDatabase,
        session: DkgSession,
        transform: (DkgSession) -> DkgSession
    ): DkgSession {
        val current = database.dkgSessionDao().getSessionById(session.id) ?: session
        val updated = transform(current)

        if (updated == current) return current

        val stamped = updated.copy(updatedAt = Clock.System.now())
        database.dkgSessionDao().upsert(stamped)

        return stamped
    }

    /**
     * Moves the progress label forward, never back. Stages are declared in the
     * order the ritual runs them (see [DkgRitualStage]), so a message arriving
     * out of order cannot walk the UI back down the ladder.
     */
    private suspend fun moveTo(
        database: DedunDatabase,
        session: DkgSession,
        stage: DkgRitualStage
    ): DkgSession = update(database, session) { current ->
        val settled = current.stage == DkgRitualStage.COMPLETE || current.stage == DkgRitualStage.FAILED

        if (settled || current.stage.ordinal >= stage.ordinal) current else current.copy(stage = stage)
    }

    private suspend fun fail(
        database: DedunDatabase,
        session: DkgSession,
        reason: String,
        // Whoever ended it. A member who sent FAILURE, or this device when it hit a
        // fault of its own -- which amounts to the same thing from the group's side,
        // since hitting one makes this device broadcast FAILURE in turn.
        culprit: HexKey = session.userPublicKey
    ) {
        // Read before writing so the notice goes out once. A ritual can be failed
        // from two directions -- a FAILURE message from a member, and a fault raised
        // locally -- and the group does not need to be told twice.
        val current = database.dkgSessionDao().getSessionById(session.id) ?: session
        if (current.stage == DkgRitualStage.FAILED) return

        update(database, current) {
            it.copy(stage = DkgRitualStage.FAILED, failureReason = reason)
        }
        announce(
            database = database,
            session = current,
            messageType = ChatMessage.TYPE_DKG_FAILED,
            // The fault itself is deliberately left out and kept on the ritual
            // screen this line taps through to. "a participant is faulty
            // (participant 2)" is worth having, but not in the middle of a
            // sentence about who walked away.
            content = "abandoned the shared key ceremony. No key was created, " +
                "and it is safe to run it again.",
            actor = culprit
        )
    }

    private suspend fun announceStarted(database: DedunDatabase, session: DkgSession) = announce(
        database = database,
        session = session,
        messageType = ChatMessage.TYPE_DKG_STARTED,
        // Reads after the actor's name -- see ChatMessage.DKG_AUTHORED_TYPES. Who
        // opened a ceremony matters: any member can, and it settles a group's
        // signing quorum for good.
        //
        // A subgroup's ceremony says so, because it lands in the *parent's* room
        // and the unqualified sentence reads there as though this group were
        // getting a new key. It is not: the quorum quoted is the child's, over
        // the child's admins, and the parent's own key is untouched. Getting that
        // wrong is how a member approves something they did not follow.
        content = if (session.parentChatRoomId != null) {
            "started a key ceremony for ${session.subject ?: "a subgroup"}, a subgroup of " +
                "this group. It will take ${session.threshold} of " +
                "${session.participantCount} of its admins to sign with the subgroup's key, " +
                "and it finishes once all of them have taken part."
        } else {
            "started a shared key ceremony. It will take ${session.threshold} of " +
                "${session.participantCount} members to sign with the key, and it finishes " +
                "once everyone has taken part."
        },
        actor = session.coordinatorPublicKey
    )

    /**
     * What this ritual is waiting on its owner for, or null if it is waiting on
     * somebody else — or on nothing, being finished.
     *
     * Mirrors the gates in [advance] rather than duplicating their reasoning: a step
     * is pending exactly when [advance] would stop at it. The two must agree, or the
     * screen offers an approval that does nothing, or none while the ritual sits
     * still.
     */
    suspend fun pendingApproval(database: DedunDatabase, session: DkgSession): DkgApprovalStep? {
        if (session.stage == DkgRitualStage.COMPLETE || session.stage == DkgRitualStage.FAILED) return null

        if (session.hostKeyApprovedAt == null) return DkgApprovalStep.HOST_KEY

        if (session.round1ApprovedAt == null) {
            // Nothing to contribute to until the participant set is settled: round 1
            // is computed over every member's host key, so asking earlier would be
            // asking about a ceremony whose shape is not yet known.
            val hostKeys = database.dkgSessionDao()
                .countMessagesByKind(session.id, DkgRitualEvents.HOST_KEY)

            return if (hostKeys >= session.participantCount) DkgApprovalStep.ROUND_1 else null
        }

        if (session.round2ApprovedAt == null) {
            // Nothing to check until the coordinator has combined the contributions.
            return if (session.coordinatorRound1 != null) DkgApprovalStep.ROUND_2 else null
        }

        return null
    }

    /**
     * Ends a ceremony this device is in, for everyone in it.
     *
     * ChillDKG has no "no" short of this: a participant who never approves stalls
     * the ceremony for good, and the others are left waiting on a key that will
     * not come. Saying so lets them stop. It is the same FAILURE message a fault
     * sends, and every device files it the same way -- no key is created, and the
     * group can start again.
     *
     * Returns whether it was ended. Refuses, rather than throws, a ceremony this
     * device is not in or one that is already over: a stale screen may ask.
     */
    suspend fun abandon(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String,
    ): Boolean {
        val session = database.dkgSessionDao().getSessionById(sessionId) ?: return false

        if (session.stage == DkgRitualStage.COMPLETE || session.stage == DkgRitualStage.FAILED) {
            logger.i("Ritual $sessionId is already over; nothing to abandon")
            return false
        }

        if (session.userPublicKey !in participantsOf(session, localChatRoom)) {
            logger.w("Ritual $sessionId does not include this device; not abandoning it")
            return false
        }

        logger.i("Abandoning ritual $sessionId")

        fail(database, session, ABANDONED)
        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = DkgRitualEvents.FAILURE,
            content = ABANDONED
        )

        return true
    }

    /**
     * Records that this device's owner agreed to [step], then lets the ritual run as
     * far as the next thing it is waiting on.
     *
     * Approving a step it is not actually waiting on is a no-op rather than an error:
     * a stale screen left open across a redelivery should not publish anything, and
     * the caller has no way to know the ritual has moved on since it was drawn.
     */
    suspend fun approve(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String,
        step: DkgApprovalStep,
        nostrPrivateKey: ByteArray
    ) {
        val session = database.dkgSessionDao().getSessionById(sessionId) ?: return

        if (pendingApproval(database, session) != step) {
            logger.i("Ritual $sessionId is not waiting on $step; ignoring the approval")
            return
        }

        val now = Clock.System.now()
        update(database, session) {
            when (step) {
                DkgApprovalStep.HOST_KEY -> it.copy(hostKeyApprovedAt = now)
                DkgApprovalStep.ROUND_1 -> it.copy(round1ApprovedAt = now)
                DkgApprovalStep.ROUND_2 -> it.copy(round2ApprovedAt = now)
            }
        }

        logger.i("Ritual $sessionId: $step approved")

        advance(
            database = database,
            localChatRoom = localChatRoom,
            sessionId = sessionId,
            nostrPrivateKey = nostrPrivateKey
        )
    }

    /**
     * Tells the group's chat that this ritual is waiting on the reader.
     *
     * The ritual is otherwise silent while it waits, and waiting is its normal
     * state: it cannot finish until every member has taken part, so a member who
     * never notices stalls it for everybody. The line is not addressed to the group
     * — every device writes its own, and each only ever writes its own owner's.
     *
     * Written once per step by [DkgSession.approvalRequestedThrough], because
     * [advance] runs on every arriving message and would otherwise ask again on each
     * one.
     */
    private suspend fun announceApprovalNeeded(
        database: DedunDatabase,
        session: DkgSession,
        step: DkgApprovalStep
    ) {
        val asked = current(database, session).approvalRequestedThrough
        if (asked != null && asked.ordinal >= step.ordinal) return

        update(database, session) { it.copy(approvalRequestedThrough = step) }

        val (messageType, content) = when (step) {
            DkgApprovalStep.HOST_KEY -> ChatMessage.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY to
                "Your approval is needed to join the shared key ceremony. Nothing has been " +
                    "published from this device yet."

            DkgApprovalStep.ROUND_1 -> ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_1 to
                "Everyone has joined the shared key ceremony. Your approval is needed to " +
                    "contribute to the key."

            DkgApprovalStep.ROUND_2 -> ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_2 to
                "The contributions have been combined. Your approval is needed to check the " +
                    "result and confirm it."
        }

        announce(
            database = database,
            session = session,
            messageType = messageType,
            content = content,
            actor = session.userPublicKey
        )
    }

    /**
     * Puts one protocol message in the group's chat, as a line naming who sent it.
     *
     * The ritual is otherwise a black box. Its kinds are gift-wrapped payloads that
     * never become chat messages, so a ceremony that fixes the group's signing
     * quorum for good ran with nothing to watch, and a stalled one gave no way to
     * see which member it was waiting on. Naming the sender is the point: every one
     * of these is a thing a particular member's device did.
     *
     * The wording describes what the step accomplishes rather than what it is
     * called. "sent their contribution to the key" is a true and useful thing to
     * read in a group chat; "sent pmsg1" is not, and the protocol names are on the
     * shared-key screen for anyone who wants them.
     *
     * Each is written once per device, guarded at the point of first arrival --
     * relays redeliver and [replayStoredMessages] feeds the whole backlog through
     * again, both of which the keyed [DkgParticipantMessage] absorbs and a chat row
     * cannot.
     */
    private suspend fun announceStep(
        database: DedunDatabase,
        session: DkgSession,
        kind: Kind,
        actor: HexKey
    ) {
        val (messageType, content) = when (kind) {
            DkgRitualEvents.HOST_KEY -> ChatMessage.TYPE_DKG_HOST_KEY to
                "joined the shared key ceremony, publishing the key their device is " +
                    "identified by for the rest of it."

            DkgRitualEvents.ROUND_1 -> ChatMessage.TYPE_DKG_ROUND_1 to
                "sent their contribution to the key. Every member's is mixed in, so no " +
                    "single device ever holds the whole thing."

            DkgRitualEvents.COORDINATOR_ROUND_1 -> ChatMessage.TYPE_DKG_COORDINATOR_ROUND_1 to
                "combined everyone's contributions and sent the result back to be checked."

            DkgRitualEvents.ROUND_2 -> ChatMessage.TYPE_DKG_ROUND_2 to
                "checked the combined result and signed to confirm it matches what they sent."

            DkgRitualEvents.CERTIFICATE -> ChatMessage.TYPE_DKG_CERTIFICATE to
                "gathered everyone's confirmations into a certificate. A device that has " +
                    "it can finish and keep its share of the key."

            // PROPOSAL and FAILURE are announced by the code that acts on them --
            // both say more than the message itself carries, the quorum in one case
            // and who walked away in the other.
            else -> return
        }

        announce(
            database = database,
            session = session,
            messageType = messageType,
            content = content,
            actor = actor
        )
    }

    /** The stored session, for reading a column back before overwriting it. */
    private suspend fun current(database: DedunDatabase, session: DkgSession): DkgSession =
        database.dkgSessionDao().getSessionById(session.id) ?: session

    /**
     * Puts a ritual milestone in the group's chat.
     *
     * The ritual is otherwise invisible: its kinds never become chat messages, so a
     * member's device would join a ceremony that fixes the group's signing quorum
     * for good without anything appearing where they would look. Each device writes
     * its own row from the ritual messages it has already received, so this costs no
     * traffic and cannot disagree with the ritual it describes.
     *
     * Written once by construction, not by de-duplication. There is no key on
     * ChatMessage to make a second insert idempotent -- its id is autogenerated --
     * so every caller checks before it writes: a session is created once (its caller
     * returns early if the row exists), [advance] leaves a completed ritual alone,
     * [fail] checks the stage, and [announceStep] reads the row it is about to
     * write over. Those guards are what keep the transcript honest.
     */
    private suspend fun announce(
        database: DedunDatabase,
        session: DkgSession,
        messageType: String,
        content: String,
        actor: HexKey
    ) {
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
                // Which ceremony this line is about. A room can hold more than
                // one -- see `ChatMessage.dkgSessionId` -- and the transcript is
                // what a member taps to reach the one that wants them.
                dkgSessionId = session.id
            )
        )
    }

    private suspend fun publishHostKey(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: DkgSession
    ) = publishOwn(database, localChatRoom, session, DkgRitualEvents.HOST_KEY, session.hostPublicKey)

    /**
     * Broadcasts one of this device's own protocol messages AND records it
     * locally. The local copy matters: the coordinator is a participant too, and
     * its own message has to be in the aggregation alongside everyone else's.
     *
     * Queued before it is recorded, because [advance] republishes any message it
     * has no local copy of. A crash between the two therefore costs a duplicate
     * broadcast — which every receiver folds away — rather than a message the
     * group waits on forever.
     */
    private suspend fun publishOwn(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: DkgSession,
        kind: Kind,
        content: String
    ) {
        broadcast(database, localChatRoom, session, kind, content)

        // Guarded here rather than trusting the callers: every one of them already
        // checks before publishing, but the chat row is the thing with no key to
        // fall back on, so the check that matters lives next to the write.
        val known = ownMessage(database, session, kind) != null

        database.dkgSessionDao().upsert(
            DkgParticipantMessage(
                sessionId = session.id,
                participantPublicKey = session.userPublicKey,
                kind = kind,
                payload = content
            )
        )

        if (!known) announceStep(database, session, kind, session.userPublicKey)
    }

    /**
     * Queues one of this device's ritual messages for the outbound pipeline, on
     * whichever transport the room it is in has.
     *
     * In a NIP-17 room it is a gift-wrap payload: `NotaryViewModel` picks it up,
     * seals a copy per participant and broadcasts. In a Marmot room it is an
     * unprocessed inner event, MLS-encrypted and broadcast as a kind:445 for the
     * room. Both are the paths chat messages already take, which is why this
     * needs no transport of its own.
     *
     * **The p-tags go on both**, which is where this parts company with
     * `FrostSigningManager.broadcast`. There a p-tag is an address, and a group
     * event needs none because it is encrypted to the whole tree. Here the p-tag
     * set *is* the participant set: `acceptProposal` builds `n` out of it on
     * every device, and `n` is hashed into the session identity, so dropping the
     * tags in a Marmot room would leave every receiver unable to say what
     * ceremony they had been invited to. In the parent's room they are also the
     * only thing separating the subgroup's admins from everybody else who can
     * read the message, and naming them inside MLS encryption leaks nothing.
     *
     * Off the session rather than off the room, for that same reason. A
     * subgroup's ceremony runs in the parent's room, so the room's roster would
     * invite the whole parent into the child's key.
     */
    private suspend fun broadcast(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: DkgSession,
        kind: Kind,
        content: String,
        includeThreshold: Boolean = false,
        includeParent: Boolean = false,
        subject: String? = null
    ) {
        // Sorted so a receiver comparing two devices' proposals is comparing the
        // same bytes, and the sender is implied rather than tagged -- together
        // they are the set every receiver derives `n` from.
        val relayHints = localChatRoom.localParticipants
            .associate { it.participant.participantPublicKey to it.participant.relayHint }

        val receiverTags = participantsOf(session, localChatRoom)
            .filterNot { it == session.userPublicKey }
            .sorted()
            .map { participant ->
                PTag.assemble(
                    participant,
                    relayHints[participant]?.let { NormalizedRelayUrl(it) }
                )
            }

        val tags = receiverTags.toTypedArray() + DkgRitualEvents.assembleTags(
            sessionId = session.id,
            threshold = if (includeThreshold) session.threshold else null,
            parentChatRoomId = if (includeParent) session.parentChatRoomId else null,
            subject = subject,
            // With the parent, and for the same reason: only on the proposal.
            walletPolicy = if (includeParent) session.subgroupWalletPolicy else null,
            guardianDelay = if (includeParent) session.guardianDelay else null
        )

        val createdAt = Clock.System.now().epochSeconds

        // The rumor id the outbound pipeline will recompute from these same
        // fields when it assembles the event to encrypt, on either transport.
        val id = EventHasher.hashId(
            pubKey = session.userPublicKey,
            createdAt = createdAt,
            tags = tags,
            content = content,
            kind = kind
        )

        // No MLS state is what marks a room NIP-17 -- the same reading
        // `NostrNip17Dao.getOrCreateChatRoom` writes, `sendChatMessage` makes when
        // it chooses between a group event and gift wraps, and
        // [replayStoredMessages] has to make again to find this message later.
        if (localChatRoom.chatRoom.mlsGroupState != null) {
            database.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = id,
                    publicKey = session.userPublicKey,
                    kind = kind,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = tags,
                    content = content,
                    chatRoomId = localChatRoom.chatRoom.id
                )
            )
        } else {
            database.giftWrapPayloadDao().upsert(
                GiftWrapPayload(
                    id = id,
                    kind = kind,
                    tags = tags,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    content = content,
                    chatRoomId = localChatRoom.chatRoom.id,
                    publicKey = session.userPublicKey
                )
            )
        }
    }

    /**
     * The participant set a proposal names: its p-tags plus its sender.
     *
     * The sender is in the set without tagging themselves -- they are running the
     * ceremony, and a device does not need to be told to send itself a message.
     * `GiftWrapPayload.participantPTags` reads exactly this off the other
     * transport, and the two have to agree or a ceremony cannot span them.
     */
    private fun participantsFrom(innerEvent: Event): Set<HexKey> =
        innerEvent.tags.taggedUsers().map { it.pubKey }.toSet() + innerEvent.pubKey
}
