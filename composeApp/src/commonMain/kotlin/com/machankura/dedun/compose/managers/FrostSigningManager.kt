package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.subgroup.allows
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import fr.acinq.bitcoin.OutPoint
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.wallet.GroupSpend
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.secp256k1.Hex
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.AggregatedNonce
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.utils.Either
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent

/**
 * Signs a nostr event with a group's FROST threshold key, in one of its rooms.
 *
 * The shape is [ChillDkgRitualManager]'s, deliberately: the member who proposes
 * a signature coordinates it, protocol messages travel on the kinds in
 * [FrostSigningEvents], each inbound message is persisted and then the session
 * is asked whether it can move, and every step is recomputed from stored inputs
 * so a device killed mid-round resumes on the next message. What that manager's
 * own notes say about being message-driven applies here unchanged.
 *
 * ### Two transports, because a group signs before it has a room
 *
 * Almost every signature is made in the group's admin room, and there a
 * signing message is an ordinary Marmot inner event needing no addressing of its
 * own: the room already exists, its membership is exactly the share holders, and
 * its id is derived from the key. One encrypted group event reaches everyone.
 *
 * The exception is the group's *first* signature. A room's `GroupKeyState` is
 * now signed before the room it is about is created -- see
 * `GroupKeyStateManager.propose` -- so that session runs wherever the ceremony
 * ran: the members' NIP-17 room for a group's own key, where there is no MLS
 * tree and a message goes out as one sealed gift wrap per member, and the
 * parent's Marmot room for a subgroup's, where it is an ordinary group event.
 * [broadcast] is the only place that knows the difference; everything above it
 * is the same protocol either way.
 *
 * ### What it signs as
 *
 * The room's own key, not the group's root threshold key. A signing session is
 * created with the [SharedKeyDerivation] cache for the path the room was derived
 * at, so the `pubkey` on every event a room signs is that room's id -- see
 * [SharedKeyDerivation.marmotGroupId], where those are one value. A reader
 * holding a signed dialect therefore needs no lookup to check it: the author
 * they expect is the id of the room they found it in.
 *
 * A session in a ceremony's own room signs as the room that ceremony's key
 * derives -- the admin room that does not exist yet -- which is the same rule
 * read forwards: what a group signs as is the room the signature belongs to.
 *
 * The path comes from the room, never from a proposal -- [signingPath] -- because
 * it decides which key the group signs as.
 *
 * ### It signs a batch
 *
 * A session carries one or more events as [FrostSigningItem] rows, and runs one
 * FROST instance per event in lockstep: one signer set, one aggregate per item,
 * one partial signature per item per signer, one approval. Four group events
 * whatever the size, instead of four per event.
 *
 * That is all the batching there is, and all there can be. A Schnorr partial
 * signature is `s = k + e·x` with `e = H(R‖P‖m)`, so two messages under one
 * nonce give two equations in one unknown and the secret share falls out; a
 * batch shares only what is outside that equation. Every item has its own seed,
 * its own aggregate and its own `Session`, and [joinPayload] is the only place
 * they travel together.
 *
 * Three things are genuinely different from a ceremony, and each of them is why
 * this is a separate manager rather than another branch of that one.
 *
 * ### It does not need everybody
 *
 * A DKG cannot finish until every member takes part; that is what makes the key.
 * Signing with a t-of-n key needs t, and waiting for n would throw away the
 * property the group ran a ceremony to get. So the coordinator waits for the
 * threshold to be reachable, picks a signer set, and says who is in it. Members
 * outside the set do nothing and are not stalling anything.
 *
 * The same arithmetic runs the other way. A member who refuses takes one of
 * the n away rather than ending the session, and it is abandoned only once
 * fewer than t are left who could still sign -- see [recordDecline]. In a
 * 2-of-3 group one refusal is nothing the other two cannot sign around; the
 * second is the one that ends it.
 *
 * ### Restart-safety is forced, not chosen
 *
 * `SecretNonce` cannot be serialised and refuses to be used twice. Storing the
 * randomness it is derived from and regenerating on demand is the only way a
 * signing session can survive the app closing -- and it is safe only because an
 * item signs one message and cannot be made to sign another. See
 * [FrostSigningSession] for the two rules that hold that in place; both are
 * enforced here, in [acceptProposal] and in [record].
 *
 * ### One approval, not three
 *
 * A DKG asks three times because each step publishes something different and
 * commits the member to something different. Here every step serves one
 * decision -- sign these events or do not -- and they are fixed before the
 * member is asked. A second prompt would be the same question again. That holds
 * for a batch only as long as the member can see every event in it before
 * answering, which is the screen's side of the bargain.
 */
object FrostSigningManager {
    private const val TAG = "FrostSigningManager"

    private val logger = Logger.withTag(TAG)

    /**
     * The most events one session will sign.
     *
     * Enforced when proposing, and again -- independently -- when a proposal
     * arrives. The second check is the one that matters. A proposal is the only
     * place in this protocol where a remote party decides how much work everybody
     * else does: k native key generations, k signatures, and a group event
     * carrying k payloads, all from a single message. Until batching that was
     * bounded by never being more than one.
     *
     * Sized against what a group event will carry rather than picked round. A
     * signer's nonce message is k * 133 bytes of hex and commas and their partial
     * message k * 65, neither of which binds; the proposal carries k whole
     * events, which does.
     */
    const val MAX_BATCH_SIZE: Int = 64

    /**
     * How many seconds [broadcast] will step forward looking for an unused
     * outbound row id before giving up and publishing nothing.
     *
     * Only a repeat can collide at all -- a first publish differs from every
     * stored row in its content or its kind -- so this is the number of times
     * the same message can be re-published within one second. Four is past
     * anything a person pressing a button can do, and the cooldown on
     * `FrostSigningSession.resentAt` keeps it far from the limit.
     */
    private const val MAX_ID_SEARCH: Int = 4

    /**
     * How long after a resend the coordinator may send another.
     *
     * A resend is a publish. Without a floor under it a control that repeats a
     * 64-event proposal becomes a control that fills a room with them, and two
     * taps inside one second collide on the outbound row id -- see [broadcast].
     * Long enough that a second knock means a person decided to knock again,
     * short enough to be useful while somebody is watching the screen.
     */
    val RESEND_COOLDOWN: Duration = 5.minutes

    /**
     * How long [resendAll] waits between one session and the next.
     *
     * A resend publishes two messages, which is a burst the room is known to
     * survive -- `MlsGroupCache` covers a burst decrypted in one sync, and
     * [resend] keeps to two deliberately for that reason. Asking again about
     * three proposals at once is six, outside the budget somebody set with a bug
     * attached to it, so the sessions are spaced instead of the budget being
     * quietly raised.
     *
     * One second because that is the resolution the protocol works in.
     * `created_at` is stored to the second, relays offer no ordering, and "two
     * events published in the same second race, and exactly one survives" is how
     * a proposal was lost once already -- see `MlsGroupCache`. A second between
     * sessions puts each one's pair in a second of its own.
     */
    val RESEND_SPACING: Duration = 1.seconds

    /**
     * The kinds a member authors for a session, and so the ones [republishOwn]
     * may repeat. Everything else on the session is the coordinator's.
     */
    private val OWN_KINDS: List<Kind> = listOf(
        FrostSigningEvents.NONCE,
        FrostSigningEvents.PARTIAL_SIGNATURE,
        FrostSigningEvents.DECLINE
    )

    /**
     * Opens a signing session, making this device the coordinator.
     *
     * The event is signed as it stands apart from its `pubkey`, which is
     * replaced with the group's key: a signature over an event claiming
     * somebody else's author would verify against nothing.
     *
     * [key] names the ceremony to sign under, for the one caller that knows it
     * before the room does: `GroupKeyStateManager.propose`, whose whole job is to
     * establish the answer [completedKey] would otherwise look up -- a room
     * proposing its own key state cannot be asked which key it signs with.
     * Everybody else leaves it null and is resolved from the room, which is the
     * only form that cannot name a ceremony the room has nothing to do with.
     *
     * Even then it is taken only if this device actually holds a share of it, so
     * naming one cannot talk the session into signing with material it has not
     * got; anything else falls back as though none was given.
     */
    suspend fun proposeSigning(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String,
        key: DkgSession? = null,
        createdAt: Long = Clock.System.now().epochSeconds
    ): FrostSigningSession = proposeSigningBatch(
        database = database,
        localChatRoom = localChatRoom,
        userPublicKey = userPublicKey,
        events = listOf(EventTemplate<Event>(createdAt, kind, tags, content)),
        key = key
    )

    /**
     * Opens a session over several events at once, making this device the
     * coordinator.
     *
     * One ceremony, one signer set, one approval and four group events, whatever
     * the size -- but k independent FROST instances underneath, because that is
     * the only thing a batch can be. See [FrostSigningItem] for why.
     *
     * A batch is all-or-nothing: if any item cannot be aggregated the session
     * fails and none of its events are applied. That makes a batch **only as
     * available as its worst item**, so events that do not belong together
     * should not be proposed together.
     *
     * A failed batch is retried by proposing a *new* one, never by re-proposing
     * this session. Its items' nonce seeds have already been published against an
     * aggregate; reusing any of them for a second attempt would produce two
     * partial signatures over one secret nonce, which is how a share is
     * extracted. [itemsOver] mints fresh seeds precisely so that a retry is a new
     * session by construction.
     */
    suspend fun proposeSigningBatch(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<EventTemplate<*>>,
        key: DkgSession? = null
    ): FrostSigningSession = proposeSigningBatch(
        database = database,
        localChatRoom = localChatRoom,
        userPublicKey = userPublicKey,
        lead = events.firstOrNull()
            ?: throw IllegalArgumentException("A signing session must be given something to sign"),
        dependents = { events.drop(1) },
        key = key
    )

    /**
     * Opens a session over an event and the events that name it.
     *
     * The flat form above cannot express this. A chunk carries the id of the
     * chapter it belongs to, and that id is not knowable to a caller: it is a
     * hash over the *group's* key at the *room's* derivation path, and neither
     * is resolved until this function runs. A caller that computed it anyway
     * would be recomputing [signingPath], which is the one input in this
     * protocol that must never come from a proposer -- the path decides which
     * key the group signs as.
     *
     * So [lead] is built here and handed to [dependents], which returns the
     * events that reference it. Every id in the batch then comes from one place,
     * and an item naming a chapter nobody signed is not a mistake that can be
     * made rather than one that has to be tested for.
     *
     * The lead is item 0, so it is applied before anything that names it -- a
     * chunk row whose chapter does not exist yet is a foreign key violation.
     */
    suspend fun proposeSigningBatch(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        lead: EventTemplate<*>,
        dependents: (lead: Event) -> List<EventTemplate<*>>,
        key: DkgSession? = null
    ): FrostSigningSession {
        val ceremony = key?.takeIf { it.stage == DkgRitualStage.COMPLETE && it.secretShare != null }
            ?: completedKey(database, localChatRoom.chatRoom.id)
            ?: throw IllegalStateException("This group has no shared key to sign with")

        val signerId = signerIdOf(database, ceremony, userPublicKey)
            ?: throw IllegalStateException("This device is not a participant in ceremony ${ceremony.id}")

        val path = signingPath(database, localChatRoom, ceremony)
        val unsignedEventOf = { template: EventTemplate<*> ->
            unsignedEventOf(
                key = ceremony,
                path = path,
                kind = template.kind,
                tags = template.tags,
                content = template.content,
                createdAt = template.createdAt
            )
        }

        val leadEvent = unsignedEventOf(lead)
        val unsignedEvents = listOf(leadEvent) + dependents(leadEvent).map(unsignedEventOf)

        require(unsignedEvents.size <= MAX_BATCH_SIZE) {
            "A signing session will sign at most $MAX_BATCH_SIZE events, not ${unsignedEvents.size}"
        }

        val sessionId = RandomInstance.bytes(32).toHex()

        val session = FrostSigningSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            dkgSessionId = ceremony.id,
            threshold = ceremony.threshold,
            participantCount = ceremony.participantCount,
            signerId = signerId,
            derivationPath = path?.let(SharedKeyDerivation::formatPath),
            // Proposing a signature is already the act of agreeing to it.
            signApprovedAt = Clock.System.now()
        )
        database.frostSigningSessionDao().upsert(session)
        database.frostSigningSessionDao().upsertItems(itemsOver(sessionId, unsignedEvents))
        announceStarted(database, session)

        logger.i(
            "Proposing signature $sessionId over ${describe(unsignedEvents.size)} " +
                "(${unsignedEvents.joinToString { it.id.take(8) }}) with key ${ceremony.id}"
        )

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.PROPOSAL,
            content = FrostSigningEvents.encodeProposal(unsignedEvents),
            includeKey = true
        )

        advance(database, localChatRoom, sessionId)

        return session
    }

    /**
     * Feeds one inbound signing message in and advances the session as far as it
     * will go. Safe to call twice with the same message: every write is keyed
     * and every step recomputed from stored inputs.
     */
    suspend fun processSigningPayload(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey
    ) {
        val sessionId = FrostSigningEvents.parseSessionId(innerEvent.tags)
        if (sessionId == null) {
            logger.w("FROST payload ${innerEvent.id} has no session tag; dropping")
            return
        }

        val session = if (innerEvent.kind == FrostSigningEvents.PROPOSAL) {
            acceptProposal(
                database = database,
                localChatRoom = localChatRoom,
                innerEvent = innerEvent,
                sessionId = sessionId,
                userPublicKey = userPublicKey
            )
        } else if (innerEvent.kind == FrostSigningEvents.TRANSACTION_PROPOSAL && FrostSigningEvents.parseSweep(innerEvent.tags) != null) {
            acceptSweepProposal(
                database = database,
                localChatRoom = localChatRoom,
                innerEvent = innerEvent,
                sessionId = sessionId,
                userPublicKey = userPublicKey
            )
        } else if (innerEvent.kind == FrostSigningEvents.TRANSACTION_PROPOSAL) {
            acceptSpendProposal(
                database = database,
                localChatRoom = localChatRoom,
                innerEvent = innerEvent,
                sessionId = sessionId,
                userPublicKey = userPublicKey
            )
        } else {
            // Not knowing the session is normal: a member catching up applies a
            // group's backlog in whatever order the epochs decrypt, so a nonce can
            // land before the proposal that asks for it. The inner event is already
            // stored, and [acceptProposal] replays it once the proposal turns up.
            database.frostSigningSessionDao().getSessionById(sessionId)
        }

        if (session == null) {
            logger.i("No signing session $sessionId for kind ${innerEvent.kind}; leaving it stored")
            return
        }

        if (session.stage == FrostSigningStage.FAILED) {
            logger.i("Session $sessionId already failed; ignoring kind ${innerEvent.kind}")
            return
        }

        // A resent proposal can carry the coordinator saying it has heard nothing
        // from this device. If it has, this device says it again -- the same
        // bytes, under its own key, because nobody else can send them for it.
        //
        // Ahead of [record] and [advance] so that a nonce lost on its way to the
        // coordinator goes out on the same pass that learns it was lost. Only
        // from the coordinator, and only messages already on file: see
        // [republishOwn].
        if (FrostSigningEvents.isProposal(innerEvent.kind) &&
            innerEvent.pubKey == session.coordinatorPublicKey &&
            FrostSigningEvents.parseSilentMembers(innerEvent.tags)
                ?.contains(session.userPublicKey) == true
        ) {
            republishOwn(database, localChatRoom, session)
        }

        if (!record(database, session, innerEvent)) return

        advance(database, localChatRoom, session.id)
    }

    /**
     * Records a proposal. Returns the session, whether it was just created or
     * already known.
     *
     * Publishes nothing: a signature is the one thing a group's key exists to
     * produce, so a relay delivering an event to a phone in a pocket must not be
     * enough to make it happen.
     */
    private suspend fun acceptProposal(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey
    ): FrostSigningSession? {
        database.frostSigningSessionDao().getSessionById(sessionId)?.let { existing ->
            // The events a session signs are fixed at creation. A second proposal
            // under the same id carrying different ones is either a mistake or an
            // attempt to get two signatures out of one secret nonce, which is how
            // a share is extracted -- so it is refused, not applied.
            val proposed = FrostSigningEvents.decodeProposal(innerEvent.content)
            val signing = database.frostSigningSessionDao().getItems(sessionId).map { it.eventId }
            if (proposed != null && signing != proposed.map { it.id }) {
                logger.w(
                    "Session $sessionId re-proposed with ${describe(proposed.size)} " +
                        "(${proposed.joinToString { it.id.take(8) }}), but it is already signing " +
                        "${signing.joinToString { it.take(8) }}; ignoring"
                )
            }
            return existing
        }

        val dkgSessionId = FrostSigningEvents.parseKey(innerEvent.tags)
        if (dkgSessionId == null) {
            logger.w("Signing proposal $sessionId names no key; dropping")
            return null
        }

        val key = database.dkgSessionDao().getSessionById(dkgSessionId)
        if (key == null || key.stage != DkgRitualStage.COMPLETE) {
            logger.w("Signing proposal $sessionId names key $dkgSessionId, which this device does not hold; dropping")
            return null
        }

        val signerId = signerIdOf(database, key, userPublicKey)
        if (signerId == null) {
            logger.w("Signing proposal $sessionId is for a ceremony this device did not take part in; dropping")
            return null
        }

        val proposed = FrostSigningEvents.decodeProposal(innerEvent.content)
        if (proposed == null) {
            logger.w("Signing proposal $sessionId does not carry any events; dropping")
            return null
        }

        // Checked here as well as when proposing, because this is where a remote
        // party gets to decide how much work this device does. See [MAX_BATCH_SIZE].
        if (proposed.size > MAX_BATCH_SIZE) {
            logger.w(
                "Signing proposal $sessionId asks for ${proposed.size} events, " +
                    "more than the $MAX_BATCH_SIZE this device will sign at once; dropping"
            )
            return null
        }

        // Rebuilt from the event's own fields rather than trusted. The id is the
        // 32 bytes every signer puts their share behind, so taking the proposer's
        // word for it would let them have the group sign one thing while being
        // shown another.
        //
        // The author it is rebuilt under comes from this device's own reading of
        // the room, for the same reason. A proposer who could choose the path
        // could choose the key the group signs as, and every signer would put
        // their share behind an author none of them checked.
        val path = signingPath(database, localChatRoom, key)
        val unsignedEvents = proposed.map { event ->
            unsignedEventOf(
                key = key,
                path = path,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                createdAt = event.createdAt
            )
        }

        // All of them or none. A batch's length is what every later payload is
        // checked against, so quietly dropping one bad event would leave a session
        // that rejects every signer's contribution for being the wrong size --
        // a stall with nothing to blame it on.
        unsignedEvents.forEachIndexed { index, rebuilt ->
            if (rebuilt.id != proposed[index].id) {
                logger.w(
                    "Signing proposal $sessionId carries id ${proposed[index].id} at $index " +
                        "but its fields hash to ${rebuilt.id}; dropping"
                )
                return null
            }
        }

        // A birth certificate's guardian keys are a promise by the group signing
        // it, so they have to be the keys it grants: recomputed here from the key
        // this session signs with and the group's own certificate, never taken
        // from the proposer. Its wallet policy has to be one the group may make:
        // a guarded group only makes guarded subgroups. Refused before anything is shown, for the reason a
        // mismatched id is.
        val thresholdPublicKey = key.thresholdPublicKey
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != SubgroupBirthCertificateEvent.KIND || thresholdPublicKey == null) return@forEach
            SubgroupManager.refuseCertificate(database, rebuilt.pubKey, thresholdPublicKey, rebuilt)?.let { reason ->
                logger.w("Signing proposal $sessionId carries a birth certificate that $reason; dropping")
                return null
            }
        }

        // A member wallet's certificate is this group agreeing to guard one
        // member's key, so it has to be exactly what that member asked for, asked
        // by a member, with exactly the guardian keys this group grants -- the
        // list a guarded subgroup of it would get. Recomputed from this device's
        // own key state, never taken from the sponsor who proposed it.
        // docs/member-guarded-wallets.md, Phase 7.
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != MemberWalletCertificateEvent.KIND) return@forEach
            val reason = thresholdPublicKey
                ?.let { MemberWalletManager.refuseCertificate(database, rebuilt.pubKey, it, rebuilt) }
                ?: if (thresholdPublicKey == null) "cannot be checked without the group's key" else null
            reason?.let {
                logger.w("Signing proposal $sessionId carries a member wallet certificate that $it; dropping")
                return null
            }
        }

        // A depositor statement gives one member a branch of the group's wallet, for good:
        // it has to be a member, a branch nobody else has, and the member's own current
        // destination. docs/member-deposits.md, Phase 3.
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != DepositorStatementEvent.KIND) return@forEach
            Depositors.refuse(database, rebuilt.pubKey, rebuilt)?.let { reason ->
                logger.w("Signing proposal $sessionId carries a depositor statement that $reason; dropping")
                return null
            }
        }

        // An attribution says whose some deposits are, which every member's tally then
        // shows: each deposit has to be one this device has on record, for the amount
        // it has, given to somebody the group opened deposits for. docs/member-deposits.md.
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != DepositAttributionEvent.KIND) return@forEach
            DepositLedger.refuseAttribution(database, rebuilt.pubKey, rebuilt)?.let { reason ->
                logger.w("Signing proposal $sessionId carries an attribution that $reason; dropping")
                return null
            }
        }

        // The group's Lightning wallet authorises only a payout of a member's own balance to their
        // own address, and buys no liquidity while nobody's balance is charged with it.
        // docs/payouts-only.md, Phase 6.
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != GroupLightningAuthorisationEvent.KIND) return@forEach
            GroupLightningPayouts.refuse(database, rebuilt.pubKey, rebuilt)?.let { reason ->
                logger.w("Signing proposal $sessionId carries a Lightning authorisation that $reason; dropping")
                return null
            }
        }

        // An assignment says whose some of the group's coins are, which is whom a payout of
        // them pays: each coin has to be one this device knows, for the amount it has, shared
        // among members the group opened deposits for. docs/payouts-only.md, Phase 3.
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != CoinAssignmentEvent.KIND) return@forEach
            CoinAssignments.refuse(database, rebuilt.pubKey, rebuilt)?.let { reason ->
                logger.w("Signing proposal $sessionId carries a coin assignment that $reason; dropping")
                return null
            }
        }

        // A subgroup's key state carries its birth certificate, and the
        // certificate's wallet policy has to be the one this group was made
        // under: every admin was shown it at the join gate, so joining was
        // agreeing to it. A certificate proposed later, by a parent admin
        // resuming the flow, under another one would be the child signing a
        // decision none of its members agreed to. [key] is the child's own
        // ceremony here, which is where the policy was filed.
        // `docs/subgroup-wallet-policy.md`, Phase 5.
        // A wallet statement has to be one the room's parent allows: a subgroup
        // decided guarded opens a guarded wallet and nothing else, and one decided
        // unguarded a plain one. Read off this device's own certificate for the
        // room, never the proposal's, and refused before it is shown -- a member
        // should not be asked to approve what their device already knows the
        // room has ruled out. docs/subgroup-wallet-policy.md, Phase 7.
        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != GroupWalletStatementEvent.KIND) return@forEach
            val guarded = GroupWalletStatementEvent.read(rebuilt.tags, rebuilt.content)?.guardians?.isNotEmpty() ?: return@forEach
            val policy = SubgroupManager.walletPolicyOf(database, rebuilt.pubKey)
            if (!policy.allows(guarded)) {
                logger.w(
                    "Signing proposal $sessionId opens ${if (guarded) "a guarded" else "an unguarded"} wallet, " +
                        "and this group's parent decided otherwise; dropping"
                )
                return null
            }
        }

        unsignedEvents.forEach { rebuilt ->
            if (rebuilt.kind != GroupKeyStateEvent.KIND) return@forEach
            SubgroupManager.refuseKeyState(key, rebuilt)?.let { reason ->
                logger.w("Signing proposal $sessionId carries a key state that $reason; dropping")
                return null
            }
        }

        val session = FrostSigningSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            // Whoever proposes coordinates. Aggregating nonces and partial
            // signatures gives no power over the outcome -- a wrong aggregate
            // produces a signature that does not verify, not a forged one.
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            dkgSessionId = key.id,
            threshold = key.threshold,
            participantCount = key.participantCount,
            signerId = signerId,
            derivationPath = path?.let(SharedKeyDerivation::formatPath)
        )
        database.frostSigningSessionDao().upsert(session)
        database.frostSigningSessionDao().upsertItems(itemsOver(sessionId, unsignedEvents))
        announceStarted(database, session)

        logger.i(
            "Recorded signing session $sessionId over ${describe(unsignedEvents.size)} " +
                "(${unsignedEvents.joinToString { it.id.take(8) }}); awaiting approval"
        )

        announceApprovalNeeded(database, session)
        replayStoredMessages(database, localChatRoom, session)

        return session
    }

    /**
     * Opens a session over one transaction from the group's wallet, making this
     * device the coordinator.
     *
     * A transaction is a batch: one item per input, each its own FROST instance
     * over that input's BIP341 sighash, with its own nonce seed and its own
     * cache -- the coin it spends is at its own address. Everything the batch
     * rules say holds here too, and two of them with more force. A retry is a new
     * session with new seeds, never this one again, and a fee bump is a retry.
     * And the batch is all or nothing, which for a transaction is not a policy:
     * a transaction with one unsigned input is not a transaction.
     *
     * [psbt] is checked here exactly as every other member will check it, so a
     * proposal that would be refused everywhere is refused before it is sent.
     *
     * @throws IllegalArgumentException when the spend fails a check, with the reason
     * @throws IllegalStateException when the room has no key, this device holds no
     * share of it, or the group has not opened a wallet on [chain] at [account]
     */
    suspend fun proposeSpend(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        psbt: Psbt,
        chain: Chain,
        account: Long = 0L,
    ): FrostSigningSession {
        val chatRoomId = localChatRoom.chatRoom.id
        val ceremony = completedKey(database, chatRoomId)
            ?: throw IllegalStateException("This group has no shared key to sign with")
        val thresholdPublicKey = ceremony.thresholdPublicKey
            ?: throw IllegalStateException("Ceremony ${ceremony.id} holds no threshold key")
        val signerId = signerIdOf(database, ceremony, userPublicKey)
            ?: throw IllegalStateException("This device is not a participant in ceremony ${ceremony.id}")
        val wallet = GroupWalletManager.walletFor(database, chatRoomId, thresholdPublicKey, chain, account)
            ?: throw IllegalStateException("This group has not opened a wallet on ${GroupWalletStatementEvent.chainName(chain)}")

        val checked = when (val result = GroupSpend.check(psbt, wallet.account).withFundings(database, chatRoomId)) {
            is GroupSpend.Result.Ok -> result.checked
            is GroupSpend.Result.Refused -> throw IllegalArgumentException("This spend ${result.reason}")
        }
        refusePayout(database, chatRoomId, checked)?.let { throw IllegalArgumentException("This spend $it") }

        val session = FrostSigningSession(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = chatRoomId,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            dkgSessionId = ceremony.id,
            threshold = ceremony.threshold,
            participantCount = ceremony.participantCount,
            signerId = signerId,
            // A transaction does not sign as the room: each input has its own
            // derivation, and those are on the items.
            derivationPath = null,
            subject = FrostSigningSubject.TRANSACTION,
            // Proposing a spend is already the act of agreeing to it.
            signApprovedAt = Clock.System.now()
        )
        writeSpend(database, session, checked, chain, account)
        announceStarted(database, session)

        logger.i(
            "Proposing spend ${session.id}: ${checked.inputs.size} input(s), " +
                "${checked.amountSent.sat} sats out, ${checked.fee.sat} sats fee, txid ${checked.tx.txid}"
        )

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.TRANSACTION_PROPOSAL,
            content = GroupSpend.encode(psbt),
            includeKey = true,
            extraTags = FrostSigningEvents.walletTags(GroupWalletStatementEvent.chainName(chain), account)
        )

        advance(database, localChatRoom, session.id)

        return session
    }

    /**
     * Opens a session over one transaction from a *descendant's* guarded wallet,
     * signed through this room's guardian leaf -- `docs/group-wallet-guardians.md`.
     *
     * The same session as [proposeSpend] in every way but three: the wallet is
     * the one this room was told about rather than its own, every input is
     * signed over BIP342's script-path sighash as the key this group's walk
     * reaches, and the proposal names the descendant so every signer reads the
     * same wallet from its own copy of the statement.
     *
     * @throws IllegalArgumentException when the spend fails a check, with the reason
     * @throws IllegalStateException when the room has no key, this device holds no
     * share of it, or the room holds no guarded wallet of [descendantChatRoomId] on
     * [chain] at [account]
     */
    suspend fun proposeGuardianSpend(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        psbt: Psbt,
        descendantChatRoomId: String,
        chain: Chain,
        account: Long = 0L,
    ): FrostSigningSession {
        val chatRoomId = localChatRoom.chatRoom.id
        val ceremony = completedKey(database, chatRoomId)
            ?: throw IllegalStateException("This group has no shared key to sign with")
        val thresholdPublicKey = ceremony.thresholdPublicKey
            ?: throw IllegalStateException("Ceremony ${ceremony.id} holds no threshold key")
        val signerId = signerIdOf(database, ceremony, userPublicKey)
            ?: throw IllegalStateException("This device is not a participant in ceremony ${ceremony.id}")
        val guarded = GuardianManager.guardedWalletFor(database, chatRoomId, descendantChatRoomId, chain, account)
            ?: throw IllegalStateException("This group guards no wallet of $descendantChatRoomId on ${GroupWalletStatementEvent.chainName(chain)}")

        val checked = when (val result = GroupSpend.check(psbt, guarded.account, guarded.spender(thresholdPublicKey)).withFundings(database, chatRoomId)) {
            is GroupSpend.Result.Ok -> result.checked
            is GroupSpend.Result.Refused -> throw IllegalArgumentException("This spend ${result.reason}")
        }

        val session = FrostSigningSession(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = chatRoomId,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            dkgSessionId = ceremony.id,
            threshold = ceremony.threshold,
            participantCount = ceremony.participantCount,
            signerId = signerId,
            derivationPath = null,
            subject = FrostSigningSubject.TRANSACTION,
            signApprovedAt = Clock.System.now()
        )
        writeSpend(database, session, checked, chain, account, guardedChatRoomId = descendantChatRoomId)
        announceStarted(database, session)

        logger.i(
            "Proposing guardian spend ${session.id} from $descendantChatRoomId: ${checked.inputs.size} input(s), " +
                "${checked.amountSent.sat} sats out, ${checked.fee.sat} sats fee, txid ${checked.tx.txid}"
        )

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.TRANSACTION_PROPOSAL,
            content = GroupSpend.encode(psbt),
            includeKey = true,
            extraTags = FrostSigningEvents.walletTags(GroupWalletStatementEvent.chainName(chain), account) +
                FrostSigningEvents.guardedTags(descendantChatRoomId)
        )

        advance(database, localChatRoom, session.id)

        return session
    }

    /**
     * Records a proposed spend, after every check [GroupSpend.check] makes.
     * Returns the session, whether it was just created or already known.
     *
     * Publishes nothing, for the reason [acceptProposal] gives -- and trusts
     * nothing the proposer computed. The account comes from the room's own
     * verified statement, under *this ceremony's* key; the proposal's tags only
     * say which statement. A ceremony that is not the room's -- a subgroup's,
     * held in its parent's room -- cannot reach a statement the room signed, so
     * a spend opened against one is refused here rather than signed for a
     * wallet nobody opened.
     */
    private suspend fun acceptSpendProposal(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey
    ): FrostSigningSession? {
        val psbt = GroupSpend.decode(innerEvent.content)

        database.frostSigningSessionDao().getSessionById(sessionId)?.let { existing ->
            // 7. Write-once. Every item's seed is already committed to its
            // sighash; a different transaction under the same id would have those
            // seeds produce a second partial signature over a second message.
            val stored = database.groupTransactionDao().getBySessionId(sessionId)
                ?.let { GroupSpend.decode(it.psbt)?.global?.tx }
            if (existing.subject != FrostSigningSubject.TRANSACTION || stored == null || stored != psbt?.global?.tx) {
                logger.w("Session $sessionId re-proposed with a different transaction; ignoring")
            }
            return existing
        }

        if (psbt == null) {
            logger.w("Spend proposal $sessionId does not carry a PSBT; dropping")
            return null
        }

        val key = FrostSigningEvents.parseKey(innerEvent.tags)
            ?.let { database.dkgSessionDao().getSessionById(it) }
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
        val thresholdPublicKey = key?.thresholdPublicKey
        if (key == null || thresholdPublicKey == null) {
            logger.w("Spend proposal $sessionId names a key this device does not hold; dropping")
            return null
        }

        val signerId = signerIdOf(database, key, userPublicKey)
        if (signerId == null) {
            logger.w("Spend proposal $sessionId is for a ceremony this device did not take part in; dropping")
            return null
        }

        val (chainName, account) = FrostSigningEvents.parseWallet(innerEvent.tags) ?: run {
            logger.w("Spend proposal $sessionId names no wallet; dropping")
            return null
        }
        val chain = GroupWalletStatementEvent.chainNamed(chainName) ?: run {
            logger.w("Spend proposal $sessionId names a network this build does not know, $chainName; dropping")
            return null
        }

        // 1. There is a wallet, and it is the room's -- or, for a guardian's
        // spend, a descendant's this room was told about and holds a leaf in.
        // Either way read from this device's own copy of the statement.
        val guardedChatRoomId = FrostSigningEvents.parseGuarded(innerEvent.tags)
        val (walletAccount, spender) = if (guardedChatRoomId == null) {
            val wallet = GroupWalletManager.walletFor(database, localChatRoom.chatRoom.id, thresholdPublicKey, chain, account)
            if (wallet == null) {
                logger.w("Spend proposal $sessionId spends from a wallet the group has not opened on $chainName; dropping")
                return null
            }
            wallet.account to GroupSpend.Spender.Owner
        } else {
            // The leaf is the room's, so the key signing it has to be the room's
            // own -- not a subgroup ceremony held in this room.
            if (GroupWalletManager.thresholdKeyFor(database, localChatRoom.chatRoom.id) != thresholdPublicKey) {
                logger.w("Guardian spend proposal $sessionId names a key that is not this room's; dropping")
                return null
            }
            val guarded = GuardianManager.guardedWalletFor(database, localChatRoom.chatRoom.id, guardedChatRoomId, chain, account)
            if (guarded == null) {
                logger.w("Guardian spend proposal $sessionId spends from a wallet this room guards none of; dropping")
                return null
            }
            guarded.account to guarded.spender(thresholdPublicKey)
        }

        val checked = when (val result = GroupSpend.check(psbt, walletAccount, spender).withFundings(database, localChatRoom.chatRoom.id)) {
            is GroupSpend.Result.Ok -> result.checked
            is GroupSpend.Result.Refused -> {
                logger.w("Spend proposal $sessionId ${result.reason}; dropping")
                return null
            }
        }

        // 8. The group's own wallet signs only payouts. docs/payouts-only.md, Phase 6.
        refusePayout(database, localChatRoom.chatRoom.id, checked)?.let { reason ->
            logger.w("Spend proposal $sessionId $reason; dropping")
            return null
        }

        val session = FrostSigningSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            dkgSessionId = key.id,
            threshold = key.threshold,
            participantCount = key.participantCount,
            signerId = signerId,
            derivationPath = null,
            subject = FrostSigningSubject.TRANSACTION,
        )
        writeSpend(database, session, checked, chain, account, guardedChatRoomId)
        announceStarted(database, session)

        logger.i(
            "Recorded spend $sessionId: ${checked.inputs.size} input(s), ${checked.amountSent.sat} sats out, " +
                "${checked.fee.sat} sats fee; awaiting approval"
        )

        announceApprovalNeeded(database, session)
        replayStoredMessages(database, localChatRoom, session)

        return session
    }

    /**
     * The session, an item per input carrying that input's sighash and place
     * under the account, and the transaction row -- the three things a spend is.
     */
    private suspend fun writeSpend(
        database: DedunDatabase,
        session: FrostSigningSession,
        checked: GroupSpend.Checked,
        chain: Chain,
        account: Long,
        guardedChatRoomId: String? = null,
    ) {
        database.frostSigningSessionDao().upsert(session)
        database.frostSigningSessionDao().upsertItems(
            checked.inputs.map { input ->
                FrostSigningItem(
                    sessionId = session.id,
                    itemIndex = input.index,
                    unsignedEventJson = derivationPayload(input.derivation),
                    eventId = input.sighash.toHex(),
                    // Independent per input, for the reason [itemsOver] gives.
                    nonceRandom = RandomInstance.bytes(32).toHex(),
                    inputIndex = input.index,
                )
            }
        )
        database.groupTransactionDao().insertIfAbsent(
            GroupTransaction(
                frostSigningSessionId = session.id,
                chatRoomId = session.chatRoomId,
                chain = GroupWalletStatementEvent.chainName(chain),
                account = account,
                guardedChatRoomId = guardedChatRoomId,
                psbt = GroupSpend.encode(checked.psbt),
                txid = checked.tx.txid.toString(),
            )
        )
    }

    /** An input's place under the account, as the item's payload carries it. */
    private fun derivationPayload(derived: GroupWalletDerivation.Derived): String =
        """{"change":${derived.change},"index":${derived.index}}"""

    private val DERIVATION_PAYLOAD = Regex("""^\{"change":(\d+),"index":(\d+)\}$""")

    /**
     * The account a spend session signs under and where each of its inputs is,
     * rebuilt on every pass from the ceremony's key and the transaction row --
     * the same rule as a room's path: stored inputs, rederived, never cached.
     */
    /**
     * One cache per input: the address's key path for the room's own spend, read
     * off the items as always; the guardian's leaf for a spend through it, read
     * off the spend checked again -- the leaf, the walk and the sighash are one
     * computation, and a cache taken apart from it could sign for another leaf.
     */
    private suspend fun spendCaches(
        database: DedunDatabase,
        session: FrostSigningSession,
        thresholdPublicKey: HexKey,
        items: List<FrostSigningItem>,
    ): List<TweakCache>? {
        val transaction = database.groupTransactionDao().getBySessionId(session.id) ?: return null
        if (transaction.guardedChatRoomId == null) {
            return spendDerivations(database, session, thresholdPublicKey, items)?.map { it.cache }
        }
        val checked = checkedSpend(database, session.id) ?: return null
        return checked.inputs.map { it.cache }.takeIf { it.size == items.size }
    }

    private suspend fun spendDerivations(
        database: DedunDatabase,
        session: FrostSigningSession,
        thresholdPublicKey: HexKey,
        items: List<FrostSigningItem>,
    ): List<GroupWalletDerivation.Derived>? {
        val account = spendAccount(database, session, thresholdPublicKey)?.second ?: return null

        return items.map { item ->
            val (change, index) = DERIVATION_PAYLOAD.matchEntire(item.payload)?.destructured ?: return null
            GroupWalletDerivation.derive(account, change.toLong(), index.toLong())
        }
    }

    /** What a spend session signs under: its transaction row, the account its coins are in, and who signs them. */
    private data class SpendContext(
        val transaction: GroupTransaction,
        val account: GroupWalletDerivation.Account,
        val spender: GroupSpend.Spender,
    )

    /**
     * [spendAccount], and for a guardian's spend the descendant's guarded wallet
     * as this room holds it, with this group as the spender.
     */
    private suspend fun spendContext(
        database: DedunDatabase,
        session: FrostSigningSession,
        thresholdPublicKey: HexKey,
    ): SpendContext? {
        val transaction = database.groupTransactionDao().getBySessionId(session.id) ?: return null
        val guardedChatRoomId = transaction.guardedChatRoomId
            ?: return spendAccount(database, session, thresholdPublicKey)?.let { (row, account) -> SpendContext(row, account, GroupSpend.Spender.Owner) }
        val chain = GroupWalletStatementEvent.chainNamed(transaction.chain) ?: return null
        val guarded = GuardianManager.guardedWalletFor(database, transaction.chatRoomId, guardedChatRoomId, chain, transaction.account) ?: return null
        return SpendContext(transaction, guarded.account, guarded.spender(thresholdPublicKey))
    }

    private suspend fun spendAccount(
        database: DedunDatabase,
        session: FrostSigningSession,
        thresholdPublicKey: HexKey,
    ): Pair<GroupTransaction, GroupWalletDerivation.Account>? {
        val transaction = database.groupTransactionDao().getBySessionId(session.id) ?: return null
        val chain = GroupWalletStatementEvent.chainNamed(transaction.chain) ?: return null
        // The account as the room's statement opened it: a guarded one's
        // addresses carry its guardians' leaves, and the bare derivation would be
        // a plain account at the same key, whose addresses none of these coins are at.
        val opened = GroupWalletManager.walletFor(database, transaction.chatRoomId, thresholdPublicKey, chain, transaction.account)
        return transaction to (opened?.account ?: GroupWalletDerivation.account(thresholdPublicKey, chain, transaction.account))
    }

    /**
     * Opens a sweep of what a closed Lightning channel left the group into the
     * onchain wallet at [account] on [chain], after every check [GroupSweep.check]
     * makes. Like a spend, proposing it is agreeing to it.
     */
    suspend fun proposeSweep(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        sweep: GroupSweep.Sweep,
        chain: Chain,
        account: Long = 0L,
    ): FrostSigningSession {
        val chatRoomId = localChatRoom.chatRoom.id
        val ceremony = completedKey(database, chatRoomId)
            ?: throw IllegalStateException("This group has no shared key to sign with")
        val thresholdPublicKey = ceremony.thresholdPublicKey
            ?: throw IllegalStateException("Ceremony ${ceremony.id} holds no threshold key")
        val signerId = signerIdOf(database, ceremony, userPublicKey)
            ?: throw IllegalStateException("This device is not a participant in ceremony ${ceremony.id}")
        val checked = when (val result = checkSweep(database, chatRoomId, thresholdPublicKey, chain, account, sweep)) {
            is GroupSweep.Result.Ok -> result.checked
            is GroupSweep.Result.Refused -> throw IllegalArgumentException("This sweep ${result.reason}")
        }

        val session = FrostSigningSession(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = chatRoomId,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            dkgSessionId = ceremony.id,
            threshold = ceremony.threshold,
            participantCount = ceremony.participantCount,
            signerId = signerId,
            derivationPath = null,
            subject = FrostSigningSubject.SWEEP,
            signApprovedAt = Clock.System.now()
        )
        writeSweep(database, session, checked, chain, account)
        announceStarted(database, session)
        logger.i("Proposing sweep ${session.id}: ${checked.inputs.size} output(s), ${checked.amount.sat} sats to the wallet, ${checked.fee.sat} sats fee")

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.TRANSACTION_PROPOSAL,
            content = CommonJson.encodeToString(GroupSweep.Sweep.serializer(), sweep),
            includeKey = true,
            extraTags = FrostSigningEvents.sweepTags(GroupWalletStatementEvent.chainName(chain), account, sweep.wallet)
        )
        advance(database, localChatRoom, session.id)
        return session
    }

    /**
     * Records a proposed sweep, after every check [GroupSweep.check] makes, against
     * the group's own basepoints and the onchain wallet the room opened. Write-once,
     * as a spend is.
     */
    private suspend fun acceptSweepProposal(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey
    ): FrostSigningSession? {
        val sweep = decodeSweep(innerEvent.content)
        database.frostSigningSessionDao().getSessionById(sessionId)?.let { existing ->
            val stored = database.groupTransactionDao().getBySessionId(sessionId)?.let { decodeSweep(it.psbt) }
            if (existing.subject != FrostSigningSubject.SWEEP || stored == null || stored != sweep) {
                logger.w("Session $sessionId re-proposed with a different sweep; ignoring")
            }
            return existing
        }
        if (sweep == null) {
            logger.w("Sweep proposal $sessionId does not carry a sweep; dropping")
            return null
        }
        val key = FrostSigningEvents.parseKey(innerEvent.tags)
            ?.let { database.dkgSessionDao().getSessionById(it) }
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
        val thresholdPublicKey = key?.thresholdPublicKey
        if (key == null || thresholdPublicKey == null) {
            logger.w("Sweep proposal $sessionId names a key this device does not hold; dropping")
            return null
        }
        val signerId = signerIdOf(database, key, userPublicKey) ?: run {
            logger.w("Sweep proposal $sessionId is for a ceremony this device did not take part in; dropping")
            return null
        }
        val (chainName, account) = FrostSigningEvents.parseWallet(innerEvent.tags) ?: run {
            logger.w("Sweep proposal $sessionId names no wallet; dropping")
            return null
        }
        val chain = GroupWalletStatementEvent.chainNamed(chainName) ?: return null
        if (FrostSigningEvents.parseSweep(innerEvent.tags) != sweep.wallet) {
            logger.w("Sweep proposal $sessionId names one Lightning wallet in its tags and another in its content; dropping")
            return null
        }
        val checked = when (val result = checkSweep(database, localChatRoom.chatRoom.id, thresholdPublicKey, chain, account, sweep)) {
            is GroupSweep.Result.Ok -> result.checked
            is GroupSweep.Result.Refused -> {
                logger.w("Sweep proposal $sessionId ${result.reason}; dropping")
                return null
            }
        }
        val session = FrostSigningSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            dkgSessionId = key.id,
            threshold = key.threshold,
            participantCount = key.participantCount,
            signerId = signerId,
            derivationPath = null,
            subject = FrostSigningSubject.SWEEP,
        )
        writeSweep(database, session, checked, chain, account)
        announceStarted(database, session)
        logger.i("Recorded sweep $sessionId: ${checked.amount.sat} sats to the wallet, ${checked.fee.sat} sats fee; awaiting approval")
        announceApprovalNeeded(database, session)
        replayStoredMessages(database, localChatRoom, session)
        return session
    }

    /**
     * [sweep] checked against the Lightning wallet the room agreed for its
     * network and number, whose basepoints derive from [thresholdPublicKey], and
     * the onchain wallet at [account] it must pay.
     */
    private suspend fun checkSweep(database: DedunDatabase, chatRoomId: String, thresholdPublicKey: HexKey, chain: Chain, account: Long, sweep: GroupSweep.Sweep): GroupSweep.Result {
        GroupLightningWalletManager.walletFor(database, chatRoomId, chain, sweep.wallet)
            ?: return GroupSweep.Result.Refused("sweeps a Lightning wallet the group has not opened")
        val onchain = GroupWalletManager.walletFor(database, chatRoomId, thresholdPublicKey, chain, account)
            ?: return GroupSweep.Result.Refused("pays an onchain wallet the group has not opened")
        // A swap-in refund's keys: every depositor's, from the room's own statements (docs/group-lightning-swap-in.md, Phase 7).
        val swapIns = if (!sweep.isSwapInRefund) null else {
            val lightning = GroupLightningWalletManager.walletFor(database, chatRoomId, chain, sweep.wallet)!!.statement
            GroupSwapInDerivation.lspSwapInXpub(chain)?.let { xpub ->
                GroupSweep.SwapInRefunds(
                    GroupSwapInDerivation.keys(thresholdPublicKey, chain, sweep.wallet, lightning.icebergKey, lightning.nodeId, xpub),
                    Depositors.statementsIn(database, chatRoomId, chain).mapValues { (_, d) -> d.branch },
                )
            }
        }
        return GroupSweep.check(sweep, GroupLightningDerivation.basepoints(thresholdPublicKey, chain, sweep.wallet), onchain.account, swapIns)
    }

    private suspend fun writeSweep(database: DedunDatabase, session: FrostSigningSession, checked: GroupSweep.Checked, chain: Chain, account: Long) {
        database.frostSigningSessionDao().upsert(session)
        database.frostSigningSessionDao().upsertItems(
            checked.inputs.map { input ->
                FrostSigningItem(
                    sessionId = session.id,
                    itemIndex = input.index,
                    unsignedEventJson = """{"input":${input.index}}""",
                    eventId = input.sighash.toHex(),
                    nonceRandom = RandomInstance.bytes(32).toHex(),
                    inputIndex = input.index,
                )
            }
        )
        // The row a spend keeps, so the watcher broadcasts the sweep like any of the group's
        // transactions; its PSBT column holds the sweep as proposed instead.
        database.groupTransactionDao().insertIfAbsent(
            GroupTransaction(
                frostSigningSessionId = session.id,
                chatRoomId = session.chatRoomId,
                chain = GroupWalletStatementEvent.chainName(chain),
                account = account,
                psbt = CommonJson.encodeToString(GroupSweep.Sweep.serializer(), checked.sweep),
                txid = checked.tx.txid.toString(),
            )
        )
    }

    private fun decodeSweep(content: String): GroupSweep.Sweep? =
        runCatching { CommonJson.decodeFromString(GroupSweep.Sweep.serializer(), content) }.getOrNull()

    /** The sweep a sweep session signs, checked again from its row, or null. */
    private suspend fun sweepOf(database: DedunDatabase, session: FrostSigningSession, thresholdPublicKey: HexKey): GroupSweep.Checked? {
        val transaction = database.groupTransactionDao().getBySessionId(session.id) ?: return null
        val sweep = decodeSweep(transaction.psbt) ?: return null
        val chain = GroupWalletStatementEvent.chainNamed(transaction.chain) ?: return null
        return (checkSweep(database, session.chatRoomId, thresholdPublicKey, chain, transaction.account, sweep) as? GroupSweep.Result.Ok)?.checked
    }

    /** The sweep [sessionId] signs, as the approval screen shows it, or null for any other session. */
    suspend fun checkedSweep(database: DedunDatabase, sessionId: String): GroupSweep.Checked? {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return null
        if (session.subject != FrostSigningSubject.SWEEP) return null
        val thresholdPublicKey = database.dkgSessionDao().getSessionById(session.dkgSessionId)?.thresholdPublicKey ?: return null
        return sweepOf(database, session, thresholdPublicKey)
    }

    /** A sweep's signatures, each checked under its leaf key, witnessed, and run through the interpreter. */
    private suspend fun completeSweep(database: DedunDatabase, session: FrostSigningSession, items: List<FrostSigningItem>) {
        val thresholdPublicKey = database.dkgSessionDao().getSessionById(session.dkgSessionId)?.thresholdPublicKey
            ?: throw IllegalStateException("The ceremony this sweep signs under is gone")
        val checked = sweepOf(database, session, thresholdPublicKey) ?: throw IllegalStateException("This sweep no longer checks out")
        val signatures = items.mapIndexed { index, item ->
            val input = checked.inputs[index]
            val signature = ByteVector64(item.signature!!)
            if (!Crypto.verifySignatureSchnorr(input.sighash, signature, input.leafKey)) {
                throw IllegalStateException("The aggregated signature for input $index does not verify under its leaf key")
            }
            signature
        }
        val signed = GroupSweep.witnessed(checked.tx, checked.inputs, signatures)
        signed.correctlySpends(checked.inputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        update(database, session) { it.copy(stage = FrostSigningStage.COMPLETE) }
        try {
            val transaction = database.groupTransactionDao().getBySessionId(session.id) ?: return
            database.groupTransactionDao().upsert(transaction.copy(signedTransaction = Hex.encode(Transaction.write(signed))))
        } catch (e: Throwable) {
            logger.e("Signed sweep ${session.id} but could not record it", e)
        }
        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_COMPLETE,
            content = "The group signed a sweep of ${checked.amount.sat} sats from its Lightning wallet into its onchain wallet, " +
                "with ${checked.fee.sat} sats in fees. It is sent once its delay is over.",
            actor = session.coordinatorPublicKey
        )
        logger.i("Sweep ${session.id} complete: txid ${signed.txid}")
    }

    /**
     * The spend a transaction session signs, checked again from its stored PSBT,
     * or null for an event session or one whose spend no longer checks out.
     *
     * What the approval screen and the proposal list show. Checked rather than
     * decoded, so that what a member reads is exactly what [GroupSpend.check]
     * vouched for: every input's place under the account, which outputs are
     * change, and a fee computed from amounts the sighashes commit to.
     */
    suspend fun checkedSpend(database: DedunDatabase, sessionId: String): GroupSpend.Checked? {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return null
        if (session.subject != FrostSigningSubject.TRANSACTION) return null
        val thresholdPublicKey = database.dkgSessionDao().getSessionById(session.dkgSessionId)?.thresholdPublicKey
            ?: return null
        val context = spendContext(database, session, thresholdPublicKey) ?: return null
        val psbt = GroupSpend.decode(context.transaction.psbt) ?: return null
        return (GroupSpend.check(psbt, context.account, context.spender).withFundings(database, context.transaction.chatRoomId) as? GroupSpend.Result.Ok)?.checked
    }

    /**
     * A checked spend with its payments into descendants' guarded wallets read
     * against the ones [chatRoomId] holds -- `GroupSpend.fundingsOf` -- or
     * refused for a claim that does not derive. Every place a spend is checked
     * for a member to read or sign reads its fundings here, so the label a
     * signer sees is the one every other signer's device derived too.
     *
     * A spend none of whose payments claims anything -- every spend to a typed
     * address -- reads no guarded wallets at all.
     */
    private suspend fun GroupSpend.Result.withFundings(database: DedunDatabase, chatRoomId: String): GroupSpend.Result {
        val checked = (this as? GroupSpend.Result.Ok)?.checked ?: return this
        if (checked.payments.all { checked.psbt.outputs[it.index].taprootDerivationPaths.isEmpty() }) return this
        return when (val reading = GroupSpend.fundingsOf(checked, GuardianManager.guardedWalletsOf(database, chatRoomId))) {
            is GroupSpend.FundingReading.Ok -> GroupSpend.Result.Ok(checked.copy(fundings = reading.fundings))
            is GroupSpend.FundingReading.Refused -> GroupSpend.Result.Refused(reading.reason)
        }
    }

    /**
     * The coins a room's open spends are already spending, which a new spend
     * should leave alone. A failed session releases its coins; every other one
     * holds them, a finished one included -- until its transaction is on chain,
     * the coins are still unspent as far as Electrum can say.
     */
    suspend fun reservedOutPoints(database: DedunDatabase, chatRoomId: String): Set<OutPoint> =
        openSpends(database, chatRoomId)
            .flatMap { psbt -> psbt.global.tx.txIn.map { it.outPoint } }
            .toSet()

    /**
     * The change addresses a room's open spends pay back to, under [account],
     * which a new spend should not pay to as well. Held by the same sessions as
     * [reservedOutPoints], for the same reason: until a spend is on chain, its
     * change address reads as unused, and the next spend would pick it again.
     */
    suspend fun reservedChangeIndices(
        database: DedunDatabase,
        chatRoomId: String,
        account: GroupWalletDerivation.Account,
    ): Set<Long> =
        openSpends(database, chatRoomId)
            .flatMap { GroupSpend.claimedChangeIndices(it, account) }
            .toSet()

    /**
     * The receive addresses of [account] -- a descendant's guarded wallet -- that
     * a room's open spends fund, which a new funding should not pay as well.
     * Held by the same sessions as [reservedOutPoints], for the reason
     * [reservedChangeIndices] gives.
     */
    suspend fun reservedFundingIndices(
        database: DedunDatabase,
        chatRoomId: String,
        account: GroupWalletDerivation.Account,
    ): Set<Long> =
        openSpends(database, chatRoomId)
            .flatMap { GroupSpend.claimedFundingIndices(it, account) }
            .toSet()

    /** The PSBT of every spend in the room whose session has not failed. */
    private suspend fun openSpends(database: DedunDatabase, chatRoomId: String): List<Psbt> =
        database.groupTransactionDao().getByChatRoomId(chatRoomId)
            .filter { database.frostSigningSessionDao().getSessionById(it.frostSigningSessionId)?.stage != FrostSigningStage.FAILED }
            .mapNotNull { GroupSpend.decode(it.psbt) }

    /**
     * A spend seen confirmed: recorded, and every other spend of the same coins
     * settled by it.
     *
     * Only one spend of a coin can ever confirm, so the rest are over whatever
     * stage they are in. One still collecting is abandoned -- its signatures, if
     * it ever got them, would be over a transaction nobody can mine. One already
     * signed is a transaction this one replaced, a fee bump or a race, and is
     * marked so rather than failed: it was signed, and saying otherwise would
     * misreport what the group did.
     *
     * Once per transaction, by the read of `confirmedAt` before the write; the
     * transcript line rides on the same guard.
     */
    /**
     * Why [checked], a spend of [chatRoomId]'s own wallet, is not a payout -- the one spend that
     * wallet signs -- or null when it is: `docs/payouts-only.md`, D1. A guardian's spend passes: it is
     * the ancestor's to sign, under the ancestor's rules, and the leaf it signs through is the
     * descendant's way back when the descendant can't sign at all.
     */
    private suspend fun refusePayout(database: DedunDatabase, chatRoomId: String, checked: GroupSpend.Checked): String? {
        if (checked.spender != GroupSpend.Spender.Owner) return null
        val rule = PayoutRule.check(checked, MemberBalances.payoutContextOf(database, chatRoomId, checked.account.chain))
        return (rule as? PayoutRule.Result.Refused)?.reason
    }

    /**
     * The members [checked] pays out, named for a transcript line -- *@alice*, *@alice and @bob* --
     * or null when it is not a payout. Read on the device writing the line, from its own signed
     * history, as a signer reads it. `docs/payouts-only.md`, Phase 4.
     */
    private suspend fun payeesOf(database: DedunDatabase, chatRoomId: String, checked: GroupSpend.Checked): String? {
        val payout = PayoutRule.check(checked, MemberBalances.payoutContextOf(database, chatRoomId, checked.account.chain)) as? PayoutRule.Result.Payout
            ?: return null
        val names = payout.members.map { "@" + ChatMessage.memberName(database, it.member) }
        return if (names.size == 1) names.single() else names.dropLast(1).joinToString() + " and " + names.last()
    }

    suspend fun recordConfirmed(database: DedunDatabase, sessionId: String, at: Instant = Clock.System.now()) {
        val confirmed = database.groupTransactionDao().getBySessionId(sessionId) ?: return
        if (confirmed.confirmedAt != null) return
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return

        database.groupTransactionDao().upsert(confirmed.copy(confirmedAt = at, seenAt = confirmed.seenAt ?: at))

        settleSpendsOf(database, confirmed.chatRoomId, coinsOf(confirmed), byTxid = confirmed.txid, except = sessionId)

        val checked = checkedSpend(database, sessionId)
        val sent = checked?.amountSent
        val payees = checked?.takeIf { confirmed.guardedChatRoomId == null }?.let { payeesOf(database, confirmed.chatRoomId, it) }
        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_GROUP_TRANSACTION_CONFIRMED,
            content = when {
                payees != null -> "The group's payout confirmed: ${sent!!.sat} sats paid to $payees."
                sent != null -> "The group's transaction confirmed: ${sent.sat} sats sent."
                else -> "The group's transaction confirmed."
            },
            actor = session.coordinatorPublicKey
        )
    }

    /**
     * A spend whose coins a transaction the room never proposed has taken, as
     * [conflictTxid] confirmed: marked replaced, and every other spend of the same
     * coins settled with it.
     *
     * A guarded wallet's coins have two kinds of spender. The child's own spend
     * can lose a coin to a guardian's, and a guardian's to the child's, and the
     * winner is never a row in the loser's room -- so [recordConfirmed] cannot be
     * what settles the loser. The watcher is: it asks the chain whether a signed
     * spend's coins are still there, and calls this when one was taken by a
     * confirmed transaction. Once per spend, by the read of `replacedByTxid`.
     */
    suspend fun recordReplaced(database: DedunDatabase, sessionId: String, conflictTxid: String) {
        val lost = database.groupTransactionDao().getBySessionId(sessionId) ?: return
        if (lost.confirmedAt != null || lost.replacedByTxid != null) return

        database.groupTransactionDao().upsert(lost.copy(replacedByTxid = conflictTxid))
        settleSpendsOf(database, lost.chatRoomId, coinsOf(lost), byTxid = conflictTxid, except = sessionId)
        logger.i("Spend $sessionId lost its coins to $conflictTxid")
    }

    /**
     * Every spend in [chatRoomId] other than [except] that wants any of [coins],
     * settled by [byTxid] having taken them: only one spend of a coin can ever
     * confirm, so the rest are over whatever stage they are in. One still
     * collecting is abandoned -- its signatures, if it ever got them, would be
     * over a transaction nobody can mine. One already signed is a transaction
     * [byTxid] replaced, a fee bump or a race, and is marked so rather than
     * failed: it was signed, and saying otherwise would misreport what the group
     * did.
     */
    private suspend fun settleSpendsOf(
        database: DedunDatabase,
        chatRoomId: String,
        coins: Set<OutPoint>,
        byTxid: String,
        except: String,
    ) {
        database.groupTransactionDao().getByChatRoomId(chatRoomId)
            .filter { it.frostSigningSessionId != except && (coinsOf(it) intersect coins).isNotEmpty() }
            .forEach { other ->
                val otherSession = database.frostSigningSessionDao().getSessionById(other.frostSigningSessionId)
                    ?: return@forEach
                when (otherSession.stage) {
                    FrostSigningStage.COMPLETE -> if (other.confirmedAt == null && other.replacedByTxid == null) {
                        database.groupTransactionDao().upsert(other.copy(replacedByTxid = byTxid))
                    }

                    FrostSigningStage.FAILED -> Unit

                    else -> fail(
                        database = database,
                        session = otherSession,
                        reason = "Spent by another transaction",
                        content = "abandoned a spend: another transaction spent its coins first. " +
                            "Nothing was signed."
                    )
                }
            }
    }

    private fun coinsOf(transaction: GroupTransaction): Set<OutPoint> =
        GroupSpend.decode(transaction.psbt)?.global?.tx?.txIn?.map { it.outPoint }?.toSet().orEmpty()

    /**
     * Verifies the group's signatures, puts them on the transaction, and closes
     * the session -- for a spend, the counterpart of [complete].
     *
     * Each signature is checked against the output key of the coin it spends,
     * and then the whole transaction against the script interpreter: a signature
     * that verifies over the wrong sighash is a perfectly good signature and a
     * transaction nobody can mine, and `correctlySpends` is the only check that
     * asks the question the network will. Nothing is applied and nothing is
     * filed as a `GroupSignedEvent` -- this is not an event, and the chronicle
     * reads that table.
     */
    private suspend fun completeSpend(
        database: DedunDatabase,
        session: FrostSigningSession,
        items: List<FrostSigningItem>,
    ) {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId)
        val thresholdPublicKey = key?.thresholdPublicKey
            ?: throw IllegalStateException("The ceremony this spend signs under is gone")
        val (transaction, account, spender) = spendContext(database, session, thresholdPublicKey)
            ?: throw IllegalStateException("This spend has no transaction on file")
        val psbt = GroupSpend.decode(transaction.psbt)
            ?: throw IllegalStateException("This spend's PSBT can no longer be read")
        val checked = when (val result = GroupSpend.check(psbt, account, spender)) {
            is GroupSpend.Result.Ok -> result.checked
            is GroupSpend.Result.Refused -> throw IllegalStateException("This spend ${result.reason}")
        }
        // Again here: a spend accepted by a build before the rule, or before this device learnt
        // whose its coins are, is not signed now if it is not a payout.
        refusePayout(database, session.chatRoomId, checked)?.let { throw IllegalStateException("This spend $it") }

        val signatures = items.mapIndexed { index, item ->
            val input = checked.inputs[index]
            val signature = ByteVector64(item.signature!!)
            if (!Crypto.verifySignatureSchnorr(input.sighash, signature, input.signingKey)) {
                throw IllegalStateException("The aggregated signature for input $index does not verify against its coin")
            }
            signature
        }

        val signed = checked.witnessed(signatures)
        signed.correctlySpends(
            checked.inputs.associate { it.outPoint to it.txOut },
            ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
        )

        update(database, session) { it.copy(stage = FrostSigningStage.COMPLETE) }

        // The signature is made and valid either way; a row this device could not
        // write is logged, not a failed ceremony -- the same rule as
        // [recordSignedEvents].
        try {
            database.groupTransactionDao().upsert(
                transaction.copy(signedTransaction = Hex.encode(Transaction.write(signed)))
            )
        } catch (e: Throwable) {
            logger.e("Signed spend ${session.id} but could not record it", e)
        }

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_COMPLETE,
            content = when (val guardian = checked.spender as? GroupSpend.Spender.Guardian) {
                null -> payeesOf(database, session.chatRoomId, checked)?.let { "The group signed a payout of ${checked.amountSent.sat} sats to $it, " }
                    ?: "The group signed a transaction sending ${checked.amountSent.sat} sats, "
                else -> "The group signed a transaction sending ${checked.amountSent.sat} sats from " +
                    "${com.machankura.dedun.compose.text.ProposedSpend.guardedWallet(guardian.name, guardian.label)}, "
            } + "with ${checked.fee.sat} sats in fees. It took ${session.threshold} of " +
                "${session.participantCount} members.",
            actor = session.coordinatorPublicKey
        )

        logger.i("Spend ${session.id} complete: txid ${signed.txid}")
    }

    /**
     * Feeds in every message of this session that arrived before the proposal did.
     *
     * They were dropped at the time for want of a session to file them under, but
     * the inbound path stores every payload it decrypts before dispatching on
     * kind, so nothing was actually lost — this reads them back out.
     *
     * Out of whichever store the room's transport writes to, which has to be the
     * same reading [broadcast] makes: a session in a NIP-17 room has its backlog
     * in the gift-wrap payloads and none at all in the inner events, and looking
     * in the wrong one is a stall with nothing to blame it on.
     */
    private suspend fun replayStoredMessages(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession
    ) {
        val kinds = FrostSigningEvents.ALL.toList()

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

        val stored = received.filter { stored ->
            !FrostSigningEvents.isProposal(stored.kind) &&
                FrostSigningEvents.parseSessionId(stored.tags) == session.id
        }

        if (stored.isEmpty()) return

        logger.i("Replaying ${stored.size} stored message(s) for signing session ${session.id}")

        stored.forEach { innerEvent ->
            if (!record(database, session, innerEvent)) return
        }
    }

    /**
     * Files one signing message. Returns false when the message ends the session,
     * so the caller stops rather than trying to advance a dead one.
     */
    private suspend fun record(
        database: DedunDatabase,
        session: FrostSigningSession,
        innerEvent: Event
    ): Boolean {
        when (innerEvent.kind) {
            FrostSigningEvents.PROPOSAL,
            FrostSigningEvents.TRANSACTION_PROPOSAL -> Unit // handled by acceptProposal and acceptSpendProposal

            FrostSigningEvents.NONCE,
            FrostSigningEvents.PARTIAL_SIGNATURE -> {
                val known = database.frostSigningSessionDao()
                    .getMessage(session.id, innerEvent.kind, innerEvent.pubKey) != null

                database.frostSigningSessionDao().upsert(
                    FrostSignerMessage(
                        sessionId = session.id,
                        signerPublicKey = innerEvent.pubKey,
                        kind = innerEvent.kind,
                        payload = innerEvent.content
                    )
                )

                if (!known) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            FrostSigningEvents.SIGNER_SET -> {
                if (!isFromCoordinator(session, innerEvent)) return true

                val signerIds = FrostSigningEvents.parseSignerIds(innerEvent.tags)
                if (signerIds == null) {
                    logger.w("Session ${session.id}: signer set carries no ids; ignoring")
                    return true
                }

                // Write-once, and this is the load-bearing one. Signing the same
                // message twice under one secret nonce against two different
                // aggregated nonces is exactly how a secret share is extracted, so
                // a coordinator that sends a second, different signer set is
                // ignored rather than obeyed. The session stalls; the share does
                // not leak.
                //
                // `signerIds` is the gate for the whole batch: it and every item's
                // aggregated nonce are written together, so a session holding one
                // holds all of them.
                if (current(database, session).signerIds != null) return true

                val aggregated = splitForSession(database, session, innerEvent.content)
                    ?: return true

                if (applyAggregate(database, session, signerIds, aggregated)) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            FrostSigningEvents.SIGNATURE -> {
                if (!isFromCoordinator(session, innerEvent)) return true

                // Write-once as a unit, for the same reason the aggregate is: a
                // batch that had some of its signatures would be one the group
                // could neither finish nor safely retry.
                if (isSigned(database, session)) return true

                val signatures = splitForSession(database, session, innerEvent.content)
                    ?: return true

                if (applySignatures(database, session, signatures)) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            FrostSigningEvents.FAILURE -> {
                fail(
                    database = database,
                    session = session,
                    reason = "Abandoned by ${innerEvent.pubKey.take(8)}: ${innerEvent.content}",
                    culprit = innerEvent.pubKey
                )
                return false
            }

            FrostSigningEvents.DECLINE -> {
                return recordDecline(
                    database = database,
                    session = session,
                    member = innerEvent.pubKey,
                    reason = innerEvent.content
                )
            }
        }

        return true
    }

    /**
     * Files one member's refusal, and ends the session if it is the refusal
     * that leaves too few members to sign. Returns false when it is, so the
     * caller stops rather than trying to advance a dead one.
     *
     * The count is this device's own, made from the refusals it has received,
     * and every device makes the same one: the threshold and the participant
     * count came off the ceremony, and a refusal is a message like any other.
     * Nobody announces the abandonment, because nobody has to -- each device
     * reaches it on its own the way each writes its own transcript, and a
     * FAILURE sent to say so would name one member as the culprit for what was
     * the group's arithmetic.
     *
     * Only a ceremony participant's refusal counts, and only theirs is kept. A
     * subgroup's ceremony runs in the parent's room over a subset of it, so a
     * message here can come from a member who holds no share, and a refusal to
     * sign from somebody who could not have signed takes nobody out of the n.
     * A member who has already offered a nonce cannot refuse through the app --
     * the screen offers one answer or the other -- so a refusal is never
     * counted against a signer.
     *
     * The transcript line is written once, on the first delivery; the count is
     * recomputed on every one, which costs nothing and is idempotent by
     * [fail]'s own guard.
     */
    private suspend fun recordDecline(
        database: DedunDatabase,
        session: FrostSigningSession,
        member: HexKey,
        reason: String
    ): Boolean {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return true
        val participants = signerIds(database, key).keys

        if (member !in participants) {
            logger.w(
                "Session ${session.id}: refusal from ${member.take(8)}, who holds no share of " +
                    "key ${key.id}; ignoring"
            )
            return true
        }

        val known = database.frostSigningSessionDao()
            .getMessage(session.id, FrostSigningEvents.DECLINE, member) != null

        database.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = session.id,
                signerPublicKey = member,
                kind = FrostSigningEvents.DECLINE,
                payload = reason
            )
        )

        val declined = database.frostSigningSessionDao()
            .getMessagesByKind(session.id, FrostSigningEvents.DECLINE)
            .map { it.signerPublicKey }
            .filter { it in participants }
            .toSet()
            .size
        val willing = session.participantCount - declined

        if (declined >= session.declinesThatAbandon()) {
            fail(
                database = database,
                session = session,
                reason = "Abandoned: $declined of ${session.participantCount} members declined " +
                    "to sign, leaving fewer than the ${session.threshold} it takes.",
                culprit = member,
                content = "declined to sign. That leaves fewer than the ${session.threshold} of " +
                    "${session.participantCount} members it takes, so the signature is " +
                    "abandoned. Nothing was signed, and it is safe to ask again."
            )
            return false
        }

        if (!known) {
            announce(
                database = database,
                session = session,
                messageType = ChatMessage.TYPE_FROST_DECLINED,
                content = "declined to sign. It takes ${session.threshold} of " +
                    "${session.participantCount} members, and $willing could still sign.",
                actor = member
            )
        }

        return true
    }

    private fun isFromCoordinator(
        session: FrostSigningSession,
        innerEvent: Event
    ): Boolean {
        if (innerEvent.pubKey == session.coordinatorPublicKey) return true

        logger.w(
            "Session ${session.id}: kind ${innerEvent.kind} from " +
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
        sessionId: String
    ) {
        var session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return
        if (session.stage == FrostSigningStage.COMPLETE || session.stage == FrostSigningStage.FAILED) return

        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return
        val secretShare = key.secretShare?.let { PrivateKey(ByteVector32(it)) } ?: return
        val thresholdPublicKey = key.thresholdPublicKey ?: return

        try {
            // A signature the group has already made settles this session whether
            // or not its owner ever answered: a t-of-n key does not need everybody,
            // so a quorum can finish while one member's phone is still in a pocket.
            // Ahead of the gate below because that gate is about keeping this
            // device's own material off the wire, and finishing puts none of it
            // there -- while going on to ask would be asking for a decision that
            // can no longer change anything, and would let a late "don't sign"
            // abandon a signature that exists.
            if (isSigned(database, session)) {
                complete(database, session)
                return
            }

            // Nothing of this device's own goes out before its owner has said so.
            // Returns rather than throws: the session is not failing, it is waiting
            // on a person, and everything received stays stored so it resumes the
            // moment they approve.
            //
            // A refusal is the other way of having said so, and it returns from
            // the same place: there is nothing more of this device's to publish,
            // and the session goes on around it -- the signature arriving is what
            // closes it, at the top of this pass. What a refusal does publish is
            // itself, on the same terms as a nonce below: whenever the row says
            // the owner refused and no copy of the message is on file, so that a
            // device killed between deciding and sending says it on the next
            // message rather than leaving the group to wait on a member who has
            // already answered.
            if (session.signApprovedAt == null) {
                if (session.signDeclinedAt == null) {
                    announceApprovalNeeded(database, session)
                } else if (ownMessage(database, session, FrostSigningEvents.DECLINE) == null) {
                    publishDecline(database, localChatRoom, session)
                }
                return
            }

            // The room's key rather than the group's root key. A signature
            // aggregates against whatever the cache carries, so the cache and the
            // author on the event have to be the same derivation -- and the
            // session stores the path exactly so that every pass rebuilds the
            // same one.
            val publicShares = key.publicShareList()

            // The events this session signs, in the order the proposal fixed. Every
            // join below is positional against it.
            val items = database.frostSigningSessionDao().getItems(sessionId)
            if (items.isEmpty()) return

            // One cache per item. Every item of an event session signs as the
            // room, so they share one; every input of a transaction is a coin at
            // its own address, so each has its own -- and signing an input with
            // its neighbour's cache aggregates to a perfectly good signature for a
            // key that coin is not at.
            val tweakCaches: List<TweakCache> = when (session.subject) {
                FrostSigningSubject.EVENTS -> SharedKeyDerivation
                    .derive(thresholdPublicKey, session.pathIndices())
                    .cache
                    .let { cache -> List(items.size) { cache } }

                FrostSigningSubject.TRANSACTION -> spendCaches(database, session, thresholdPublicKey, items)
                    ?: throw IllegalStateException("The coins this transaction spends no longer derive under the group's wallet")

                FrostSigningSubject.SWEEP -> sweepOf(database, session, thresholdPublicKey)
                    ?.inputs?.map { it.cache }
                    ?: throw IllegalStateException("The outputs this sweep spends no longer derive under the group's basepoints")
            }

            val messages = items.map { ByteVector(it.eventId.hexToByteArray()) }

            val ownNonces = ownMessage(database, session, FrostSigningEvents.NONCE)
            val ownPartials = ownMessage(database, session, FrostSigningEvents.PARTIAL_SIGNATURE)

            // One nonce per item, regenerated rather than stored -- SecretNonce
            // refuses both. Safe because an item's message can never change; see
            // the notes on FrostSigningSession.
            //
            // On demand rather than up front, and that is the point of the `lazy`:
            // `advance` runs on every arriving message, so at a batch of k this is
            // k native key generations each time, usually to find there is nothing
            // left to publish. A guard computed here instead would have to predict
            // whether this device turns out to be a signer, which it cannot -- the
            // coordinator settles the signer set further down this same pass.
            val nonces by lazy {
                items.mapIndexed { index, item ->
                    SecretNonce.generate(
                        sessionRandom = ByteVector32(item.nonceRandom),
                        secretShare = secretShare,
                        publicShare = publicShares?.getOrNull(session.signerId),
                        tweakedThresholdPublicKey = tweakCaches[index].tweakedPublicKey,
                        message = messages[index],
                        extraInput = null
                    )
                }
            }

            if (ownNonces == null) {
                publishOwn(
                    database,
                    localChatRoom,
                    session,
                    FrostSigningEvents.NONCE,
                    joinPayload(nonces.map { (_, publicNonce) -> publicNonce.data.toHex() })
                )
            }

            if (session.isCoordinator() && session.signerIds == null) {
                val offered = orderedNonces(database, session, items.size) ?: return
                val chosen = offered.take(session.threshold)
                val chosenIds = chosen.map { (id, _) -> id }

                // One aggregate per item, each built from that item's nonce from
                // each chosen signer. Reusing one across two items would be reusing
                // R across two messages, which is the whole thing this design is
                // arranged to make impossible.
                val aggregated = items.indices.map { index ->
                    IndividualNonce.aggregate(chosen.map { (_, offeredNonces) -> offeredNonces[index] })
                        .orThrow("aggregating nonces")
                        .toByteArray()
                        .toHex()
                }

                if (!applyAggregate(database, session, chosenIds, aggregated)) return
                session = current(database, session)

                broadcast(
                    database = database,
                    localChatRoom = localChatRoom,
                    session = session,
                    kind = FrostSigningEvents.SIGNER_SET,
                    content = joinPayload(aggregated),
                    signerIds = chosenIds
                )
                announceStep(
                    database,
                    session,
                    FrostSigningEvents.SIGNER_SET,
                    session.userPublicKey
                )
            }

            val signerIds = session.signerIdList() ?: return
            // Re-read, because the aggregate above is written to the item rows.
            val aggregated = database.frostSigningSessionDao().getItems(sessionId)
            session = moveTo(database, session, FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)

            // A member outside the chosen set has nothing to contribute and is not
            // holding anybody up. They stay in the session to receive the finished
            // signatures like everybody else -- and leaving here rather than
            // building k FROST sessions to do nothing with is the same saving the
            // nonce short-circuit above makes.
            val signing = session.isSigner() && ownPartials == null
            val aggregating = session.isCoordinator() && !isSigned(database, session)
            if (!signing && !aggregating) {
                complete(database, session)
                return
            }

            // One FROST session per item. `Session.create` binds the message and
            // the aggregate together, so only the signer set, the shares and the
            // tweak cache are shared across a batch.
            val signingSessions = aggregated.mapIndexed { index, item ->
                Session.create(
                    aggregatedNonce = AggregatedNonce(
                        (item.aggregatedNonce ?: return).hexToByteArray()
                    ),
                    signerIds = signerIds.map { it.toUInt() },
                    signerPublicShares = publicShares?.let { shares ->
                        signerIds.mapNotNull { shares.getOrNull(it) }.takeIf { it.size == signerIds.size }
                    },
                    nParticipants = session.participantCount,
                    threshold = session.threshold,
                    tweakCache = tweakCaches[index],
                    message = messages[index]
                )
            }

            if (signing) {
                val partialSignatures = signingSessions.mapIndexed { index, signingSession ->
                    signingSession
                        .sign(nonces[index].first, secretShare, session.signerId.toUInt())
                        .orThrow("signing")
                        .toHex()
                }

                publishOwn(
                    database,
                    localChatRoom,
                    session,
                    FrostSigningEvents.PARTIAL_SIGNATURE,
                    joinPayload(partialSignatures)
                )
            }

            if (aggregating) {
                val partials = orderedPartialSignatures(database, session, signerIds, aggregated.size)
                    ?: return

                val signatures = signingSessions.mapIndexed { index, signingSession ->
                    signingSession
                        .aggregateSigs(partials.map { ByteVector32(it[index]) })
                        .orThrow("aggregating partial signatures")
                        .toHex()
                }

                if (!applySignatures(database, session, signatures)) return
                session = current(database, session)

                broadcast(
                    database = database,
                    localChatRoom = localChatRoom,
                    session = session,
                    kind = FrostSigningEvents.SIGNATURE,
                    content = joinPayload(signatures)
                )
                announceStep(database, session, FrostSigningEvents.SIGNATURE, session.userPublicKey)
            }

            complete(database, session)
        } catch (e: CancellationException) {
            // The sync was torn down mid-step, which says nothing about the session.
            throw e
        } catch (e: Throwable) {
            logger.e("Signing session $sessionId failed", e)
            fail(database, session, e.message ?: e::class.simpleName ?: "Unknown error")
            broadcast(
                database = database,
                localChatRoom = localChatRoom,
                session = session,
                kind = FrostSigningEvents.FAILURE,
                content = e.message ?: "Signing failed"
            )
        }
    }

    /**
     * Verifies the group's signature, uses the event, and closes the session.
     *
     * The payoff: a signature that verifies is one the group made, whoever
     * relayed it. Checking rather than trusting is what keeps a faulty or
     * dishonest coordinator from passing off something that will be rejected by
     * every relay it reaches.
     *
     * Reached only with the session still open -- [advance] returns above this
     * on a settled one -- so the milestone is written once by construction,
     * like every other line in the transcript.
     */
    private suspend fun complete(database: DedunDatabase, session: FrostSigningSession) {
        val items = database.frostSigningSessionDao().getItems(session.id)
        if (items.isEmpty() || items.any { !it.isSigned() }) return

        if (session.subject == FrostSigningSubject.TRANSACTION) {
            completeSpend(database, session, items)
            return
        }
        if (session.subject == FrostSigningSubject.SWEEP) {
            completeSweep(database, session, items)
            return
        }

        // Every signature is checked before any event is applied. A batch is
        // all-or-nothing, so a bad one anywhere has to fail the session rather
        // than leave some of its events already filed.
        val signedEvents = items.map { item ->
            val signedEvent = signedEvent(item, item.signature!!)
            val verified = Nip01Crypto.verify(
                signature = item.signature.hexToByteArray(),
                hash = item.eventId.hexToByteArray(),
                pubKey = signedEvent.pubKey.hexToByteArray()
            )
            if (!verified) {
                throw IllegalStateException(
                    "The aggregated signature does not verify against ${item.eventId}"
                )
            }
            signedEvent
        }

        update(database, session) { it.copy(stage = FrostSigningStage.COMPLETE) }

        // The group's own statement, filed before anything is derived from it.
        // What the batch is applied *into* is a reading of these events -- an
        // artifact keeps some of its fields, a contributor list keeps none --
        // and the reading is the part that can be wrong or, for a kind this
        // build has no arm for, missing entirely. So the events are kept as
        // signed, and the rows made from them point back at these.
        val recorded = recordSignedEvents(database, session, signedEvents)

        // A signature exists to be used. Every device has the event and the
        // signature by now, so each applies the result itself rather than
        // waiting to be sent something it can already build -- the same
        // reasoning the transcript lines are written on. Nothing goes on the
        // wire from here: a signed event authored by the threshold key cannot
        // travel as an inner event anyway, because the outbound pipeline
        // re-authors rumors as their sender and would strip the group's
        // signature off.
        signedEvents.forEach { applySignedEvent(database, session, it, recorded) }

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_COMPLETE,
            content = "The group signed ${describe(signedEvents.size)}. It took " +
                "${session.threshold} of ${session.participantCount} members.",
            actor = session.coordinatorPublicKey
        )

        logger.i("Signing session ${session.id} complete")
    }

    /**
     * Files the batch as [GroupSignedEvent] rows, and says whether it landed.
     *
     * One write for the batch, because that is how it was signed: a session's
     * events are one decision by one quorum, and half of them on file is a state
     * no reader should have to reason about. The path comes from the session
     * rather than from the events, since it is the session that resolved it from
     * the room -- see [signingPath], and note that a null there means the
     * untweaked threshold key rather than an unknown path.
     *
     * A failure is logged and swallowed, like [applySignedEvent]'s: the
     * signature is made and valid either way, and a ceremony that succeeded must
     * not be reported as failed because this device could not write it down.
     * What the caller loses is the id to point the derived rows at, which is why
     * this returns a boolean rather than nothing.
     */
    private suspend fun recordSignedEvents(
        database: DedunDatabase,
        session: FrostSigningSession,
        signedEvents: List<Event>
    ): Boolean = try {
        database.groupSignedEventDao().recordAll(
            signedEvents.map { signedEvent ->
                GroupSignedEvent.fromEvent(
                    event = signedEvent,
                    chatRoomId = session.chatRoomId,
                    derivationPath = session.derivationPath,
                    frostSigningSessionId = session.id,
                )
            }
        )
        true
    } catch (e: Throwable) {
        logger.e("Signed session ${session.id} but could not record its events", e)
        false
    }

    /**
     * Turns the signed event into whatever it is: a dialect, an artifact, a
     * chapter.
     *
     * Reuses the inbound path's dispatch rather than repeating it, with no group
     * event and no inner event behind the row -- there is neither, and both
     * columns are nullable for exactly this kind of locally-derived record. What
     * the row does get is the [GroupSignedEvent] it was made from, when
     * [recorded] says one is on file; a row pointing at an event that is not
     * there would be worse than one pointing at nothing.
     *
     * A failure here is not the session's: the signature is made and valid, and
     * saying otherwise would tell the group to abandon a ceremony that
     * succeeded. It is logged and the session still completes.
     */
    private suspend fun applySignedEvent(
        database: DedunDatabase,
        session: FrostSigningSession,
        signedEvent: Event,
        recorded: Boolean
    ) {
        try {
            ChatMessage.applyInnerEvent(
                database = database,
                groupId = session.chatRoomId,
                event = signedEvent,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                senderPublicKey = session.coordinatorPublicKey,
                isUserMessage = session.isCoordinator(),
                createdAt = Clock.System.now(),
                groupSignedEventId = signedEvent.id.takeIf { recorded }
            )?.let { database.chatMessageDao().upsert(it) }
        } catch (e: Throwable) {
            logger.e("Signed ${signedEvent.id} but could not apply it locally", e)
        }
    }

    /**
     * The event this session produces, with the signature on it.
     *
     * Public so a caller can take the finished event and do whatever it was
     * signing it for — the session's job ends at a valid signature.
     */
    fun signedEvent(item: FrostSigningItem, signature: HexKey): Event {
        val unsigned = Event.fromJson(item.unsignedEventJson)

        return Event(
            id = unsigned.id,
            pubKey = unsigned.pubKey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = signature
        )
    }

    /** The finished event, or null while this item is still running. */
    fun signedEvent(item: FrostSigningItem): Event? =
        item.signature?.let { signedEvent(item, it) }

    /** Every finished event of a batch, empty while any of them is still running. */
    fun signedEvents(items: List<FrostSigningItem>): List<Event> =
        // A transaction's items are inputs, and their signatures are witnesses,
        // not events -- see [completeSpend].
        if (items.any { it.inputIndex != null }) emptyList() else items.map { it.signature ?: return emptyList() }
            .mapIndexed { index, signature -> signedEvent(items[index], signature) }

    /**
     * The items a session signs: one per event, each with its own nonce seed.
     *
     * Independent seeds rather than one derived per index, which would work and
     * save nothing worth having. Independence means an off-by-one anywhere in
     * the index handling produces a session that fails to aggregate, instead of
     * one that signs two messages under a single nonce.
     */
    private fun itemsOver(sessionId: String, events: List<Event>): List<FrostSigningItem> =
        events.mapIndexed { index, event ->
            FrostSigningItem(
                sessionId = sessionId,
                itemIndex = index,
                unsignedEventJson = event.toJson(),
                eventId = event.id,
                nonceRandom = RandomInstance.bytes(32).toHex()
            )
        }

    /**
     * Records the coordinator's signer set and the aggregated nonce each item is
     * signed against, as one write. Returns false when the message does not fit
     * the batch, so the caller neither announces nor acts on it.
     *
     * The two belong together. [FrostSigningSession.signerIds] is what the rest
     * of the session gates on, so a session holding it while an item still had no
     * aggregate would stall on a message that has already been delivered — and
     * the write that cleared the stall would be a second aggregate for an item
     * that had one, which is the case the write-once rule exists to prevent.
     * Items first, in one transaction, then the session: the gate is only ever
     * set on a batch that is entirely ready.
     */
    private suspend fun applyAggregate(
        database: DedunDatabase,
        session: FrostSigningSession,
        signerIds: List<Int>,
        aggregatedNonces: List<HexKey>
    ): Boolean {
        val items = database.frostSigningSessionDao().getItems(session.id)
        if (items.isEmpty() || items.size != aggregatedNonces.size) {
            logger.w(
                "Session ${session.id}: signer set carries ${aggregatedNonces.size} " +
                    "aggregated nonce(s) for ${items.size} item(s); ignoring"
            )
            return false
        }

        database.frostSigningSessionDao().upsertItems(
            items.mapIndexed { index, item -> item.copy(aggregatedNonce = aggregatedNonces[index]) }
        )
        update(database, session) { it.copy(signerIds = signerIds.joinToString(",")) }

        return true
    }

    /**
     * Records the group's finished signatures, all of them or none. Returns false
     * when the message does not fit the batch.
     *
     * Settled as a unit for the same reason the aggregate is: a batch holding
     * some of its signatures is one the group can neither finish nor safely
     * retry, since a retry means fresh nonces for events that already have a
     * signature against the old ones.
     */
    private suspend fun applySignatures(
        database: DedunDatabase,
        session: FrostSigningSession,
        signatures: List<HexKey>
    ): Boolean {
        val items = database.frostSigningSessionDao().getItems(session.id)
        if (items.isEmpty() || items.size != signatures.size) {
            logger.w(
                "Session ${session.id}: ${signatures.size} signature(s) for " +
                    "${items.size} item(s); ignoring"
            )
            return false
        }

        database.frostSigningSessionDao().upsertItems(
            items.mapIndexed { index, item -> item.copy(signature = signatures[index]) }
        )

        return true
    }

    /**
     * Whether the group has produced every signature this session was opened for.
     *
     * Counted rather than flagged, so it cannot disagree with the rows it
     * describes. A session with no items is not signed — it is one whose proposal
     * has not landed yet.
     */
    private suspend fun isSigned(database: DedunDatabase, session: FrostSigningSession): Boolean {
        val total = database.frostSigningSessionDao().countItems(session.id)

        return total > 0 && database.frostSigningSessionDao().countSignedItems(session.id) == total
    }

    /** "the event" or "3 events", for a transcript line that reads the same at either size. */
    private fun describe(count: Int): String = if (count == 1) "the event" else "$count events"

    /**
     * The nonces on offer, as (signer id, nonce), ordered by signer id — or null
     * while fewer than the threshold have arrived.
     *
     * Ordered so that every device that recomputes the aggregate from the same
     * set arrives at the same bytes. FROST binds the signer set into the
     * challenge, so an order nobody else derives is a signature nobody can
     * aggregate.
     */
    private suspend fun orderedNonces(
        database: DedunDatabase,
        session: FrostSigningSession,
        items: Int
    ): List<Pair<Int, List<IndividualNonce>>>? {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return null
        val idByMember = signerIds(database, key)

        val offered = database.frostSigningSessionDao()
            .getMessagesByKind(session.id, FrostSigningEvents.NONCE)
            .mapNotNull { message ->
                val id = idByMember[message.signerPublicKey] ?: return@mapNotNull null
                val values = splitPayload(message.payload, items) ?: run {
                    logger.w(
                        "Session ${session.id}: ${message.signerPublicKey.take(8)} offered a " +
                            "nonce payload that is not $items value(s); leaving them out"
                    )
                    return@mapNotNull null
                }

                id to values.map { IndividualNonce(it.hexToByteArray()) }
            }
            .sortedBy { (id, _) -> id }

        if (offered.size < session.threshold) {
            logger.d("Session ${session.id}: ${offered.size}/${session.threshold} nonces")
            return null
        }

        return offered
    }

    /**
     * The chosen signers' partial signatures in signer-set order, or null while
     * any are missing. Aggregation needs exactly one per signer, positionally
     * matched to the set the session was created with.
     */
    private suspend fun orderedPartialSignatures(
        database: DedunDatabase,
        session: FrostSigningSession,
        signerIds: List<Int>,
        items: Int
    ): List<List<ByteArray>>? {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return null
        val memberById = signerIds(database, key).entries.associate { (member, id) -> id to member }

        val payloadByMember = database.frostSigningSessionDao()
            .getMessagesByKind(session.id, FrostSigningEvents.PARTIAL_SIGNATURE)
            .associate { it.signerPublicKey to it.payload }

        val partials = signerIds.mapNotNull { id ->
            val payload = memberById[id]?.let { payloadByMember[it] } ?: return@mapNotNull null

            splitPayload(payload, items)?.map { it.hexToByteArray() }
        }

        if (partials.size < signerIds.size) {
            logger.d("Session ${session.id}: ${partials.size}/${signerIds.size} partial signatures")
            return null
        }

        return partials
    }

    /**
     * A signer's whole contribution to a batch, as one payload.
     *
     * Comma separated, the encoding [FrostSigningSession.signerIds] already uses,
     * and at a batch of one it is the bare value — which is what keeps a
     * single-event session on exactly the wire it has always been on.
     *
     * One row per signer per kind rather than one per item, deliberately.
     * [FrostSignerMessage]'s key is what makes a redelivered message replace its
     * predecessor instead of adding a row, and splitting by item would multiply
     * the ways a partial delivery can look like a complete one.
     */
    private fun joinPayload(values: List<HexKey>): String = values.joinToString(",")

    /**
     * The other half, and strict: a payload not carrying exactly [expected]
     * values is refused rather than truncated or padded.
     *
     * Checked here rather than in [record] on purpose. Payloads are stored
     * without being parsed, which is what lets a nonce arrive before the proposal
     * that would give it a length to be checked against. This runs where the
     * session — and so the length — is known.
     */
    private fun splitPayload(payload: String, expected: Int): List<HexKey>? =
        payload.split(",").map { it.trim() }.takeIf { it.size == expected }

    /** [splitPayload] against the number of events this session signs. */
    private suspend fun splitForSession(
        database: DedunDatabase,
        session: FrostSigningSession,
        payload: String
    ): List<HexKey>? {
        val expected = database.frostSigningSessionDao().countItems(session.id)

        return splitPayload(payload, expected) ?: run {
            logger.w(
                "Session ${session.id}: payload carries ${payload.split(",").size} value(s) " +
                    "for $expected item(s); ignoring"
            )
            null
        }
    }

    /**
     * Every ceremony participant's FROST id, keyed by their nostr public key.
     *
     * The id is the member's index in the ceremony's participant order, which is
     * the bytewise sort of the host public keys — the same ordering ChillDKG
     * hashed into the session identity, and so the same one the public shares
     * are in. Deriving it rather than storing it means signing cannot disagree
     * with the ceremony that made the key.
     */
    private suspend fun signerIds(
        database: DedunDatabase,
        key: DkgSession
    ): Map<HexKey, Int> {
        val hostKeys = database.dkgSessionDao()
            .getMessagesByKind(key.id, DkgRitualEvents.HOST_KEY)
            .associate { it.participantPublicKey to it.payload.lowercase() }

        val order = hostKeys.values.sorted()

        return hostKeys.mapNotNull { (member, hostKey) ->
            order.indexOf(hostKey).takeIf { it >= 0 }?.let { member to it }
        }.toMap()
    }

    private suspend fun signerIdOf(
        database: DedunDatabase,
        key: DkgSession,
        member: HexKey
    ): Int? = signerIds(database, key)[member]

    /**
     * The key a room signs with, or null when it has none.
     *
     * Signing usually runs in the admin room, which is never where the ceremony
     * ran: the admin room's id is derived from the key the ceremony produces, so
     * it cannot exist until afterwards. A group's own ceremony runs in the NIP-17
     * room its members share; a subgroup's runs in its parent's Marmot room.
     *
     * They are bound together by the room's [GroupKeyState]: the statement the
     * room opened with and the group signed, naming the ceremony behind it. That
     * is a lookup rather than a search, and it carries the derivation path, so a
     * room derived anywhere other than [SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH]
     * is findable at all -- which the rederivation below cannot manage, since it
     * can only rederive at the one path the constant names.
     *
     * The state buys none of its authority from being written down. It is only
     * ever stored having rederived the room it describes, so what actually binds
     * a room to a key is still that the room's id *is* the key, and a room still
     * cannot be pointed at a key it was not derived from.
     *
     * Two fallbacks behind it. The first is the original scan over every
     * ceremony this device holds a share for, rederiving each one's room id
     * until one matches; the second is a ceremony held in this very room, which
     * is not how the app wires things today but costs one lookup to keep honest.
     *
     * That second one asks for the room's *own* ceremony, and the distinction is
     * load-bearing now rather than pedantic. A parent's Marmot room hosts the
     * ChillDKG of every subgroup it makes, so it holds completed ceremonies whose
     * key belongs to a child; handing one back here would have the parent sign as
     * its own subgroup, silently, for every member who holds no key-state row --
     * which is every member welcomed after the parent's own ceremony. A ceremony
     * naming a parent is never the room's own key, and that is all it takes to
     * tell them apart.
     *
     * They are not only for rooms that predate the table. A room's key state is
     * signed before the room exists and filed as the room is created, so a
     * device that missed that session -- a member invited later, a reinstall --
     * has a room and no state, and the scan is what keeps it usable.
     * `GroupKeyStateManager.propose` is the one caller that does not come
     * through here at all, because it names the ceremony outright: the room it
     * runs in is the NIP-17 room the ceremony was held in, which signs nothing
     * else and has no key state of its own.
     */
    suspend fun completedKey(database: DedunDatabase, chatRoomId: String): DkgSession? {
        GroupKeyStateManager.keyStateFor(database, chatRoomId)?.let { state ->
            database.dkgSessionDao().getSessionById(state.dkgSessionId)?.takeIf { key ->
                key.stage == DkgRitualStage.COMPLETE &&
                    key.secretShare != null &&
                    key.thresholdPublicKey == state.thresholdPublicKey
            }?.let { return it }
        }

        database.dkgSessionDao().getKeyHoldingSessions().firstOrNull { session ->
            session.stage == DkgRitualStage.COMPLETE &&
                session.thresholdPublicKey?.let {
                    SharedKeyDerivation.marmotGroupId(it) == chatRoomId
                } == true
        }?.let { return it }

        return database.dkgSessionDao()
            .getLatestOwnSessionForChatRoom(chatRoomId)
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE && it.secretShare != null }
    }

    /** Whether this group can sign at all, read by the UI so it offers nothing that would fail. */
    suspend fun canSign(database: DedunDatabase, chatRoomId: String): Boolean =
        completedKey(database, chatRoomId) != null

    /**
     * The path off [key] that a signature made in this room is derived at, or
     * null when the room is not derived from [key] at all.
     *
     * A room's id *is* its key, so a candidate path is right exactly when
     * walking it reaches the room -- which makes this self-checking rather than
     * trusting. Three candidates, in descending order of how much they know:
     * the room's [GroupKeyState] if the group has signed one, the path recorded
     * in the room's own MIP-01 description, and the default the app derives at.
     * Whichever answers, it answers the same thing: two paths reaching one key
     * is not a thing that happens.
     *
     * Nothing here comes off a proposal. The path decides what key the group
     * signs as, so a proposer able to choose it could have every signer put
     * their share behind an author of the proposer's choosing.
     *
     * ### The room a group signs from before it has one
     *
     * One case cannot be self-checked, because there is nothing yet to check
     * against: the room a ceremony ran in, signing the very statement that lets
     * the room the ceremony's key derives be created -- see
     * `GroupKeyStateManager.propose`. What the group signs as there is the room
     * it is about to make: the ceremony's key at the app's admin path.
     *
     * Two rooms are that room. A group's own ceremony runs in the NIP-17 room its
     * members share, whose id is an aggregation of their keys, so it is derived
     * from nothing and no path reaches it. A *subgroup's* ceremony runs in the
     * parent's Marmot room, which is derived from the parent's key and so reaches
     * itself at some path -- just never from the child's, which is the key being
     * signed under here.
     *
     * Each is admitted on the narrowest thing that identifies it, and the path is
     * the constant rather than anything off the wire. Every input is read from
     * this device's own database, so a proposer still chooses nothing: naming
     * some other ceremony this device holds a share for gets no path at all, and
     * a session with no path signs as the bare threshold key, which is not an
     * identity any room answers to.
     *
     * `key.parentChatRoomId` is the unverified claim off a proposal, and admitting
     * it here grants nothing. The signature it enables is by the child's key over
     * the child's own id -- both derived from a ceremony every signer contributed
     * to -- so a member who put a false parent on a proposal gets a key state for
     * a room made from a key they helped make, which is what they would have got
     * by telling the truth.
     *
     * Null is not a failure. `completedKey` will find a key for a Marmot room
     * that was never derived from it -- the fallback kept for rooms the app no
     * longer makes -- and such a room has no key of its own to sign as, so it
     * signs as the group's threshold key, which is what it always did.
     */
    private suspend fun signingPath(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        key: DkgSession
    ): List<Long>? {
        val thresholdPublicKey = key.thresholdPublicKey ?: return null
        val chatRoomId = localChatRoom.chatRoom.id

        val candidates = listOfNotNull(
            GroupKeyStateManager.keyStateFor(database, chatRoomId)?.pathIndices(),
            SharedKeyDerivation.parsePath(localChatRoom.chatRoom.description),
            SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        )

        candidates.firstOrNull { path ->
            runCatching {
                SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path) == chatRoomId
            }.getOrDefault(false)
        }?.let { return it }

        val isCeremonyRoom = key.chatRoomId == chatRoomId
        val isSubgroupCeremony = key.parentChatRoomId == chatRoomId

        if (isCeremonyRoom && (localChatRoom.chatRoom.mlsGroupState == null || isSubgroupCeremony)) {
            return SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        }

        return null
    }

    /**
     * The unsigned event a session signs: the caller's fields under the room's
     * own key, with the id computed from them.
     *
     * [path] is the room's, so the author is the room's id -- see
     * [SharedKeyDerivation.marmotGroupId], where those are one value. A null
     * path means no derivation at all, which is the group's threshold key
     * itself; that is what a room not derived from the key signs as, because
     * there is no room key for it to be.
     */
    private fun unsignedEventOf(
        key: DkgSession,
        path: List<Long>?,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String,
        createdAt: Long
    ): Event {
        // Nostr identifies an author by the x-only key a BIP-340 signature
        // verifies against, which for a FROST key is the tweaked key the signing
        // cache carries -- not the 33-byte value the ceremony reports.
        val groupPubKey = SharedKeyDerivation
            .derive(key.thresholdPublicKey!!, path ?: emptyList())
            .hex

        return Event(
            id = EventHasher.hashId(
                pubKey = groupPubKey,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content
            ),
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = ""
        )
    }

    /**
     * Turns a failed FROST step into an exception so it joins [advance]'s single
     * failure path.
     *
     * The library reports these as `Either`, because a partial signature that
     * will not aggregate is a normal outcome of collecting them from other
     * people rather than a bug. This session has one response to all of them —
     * there is no signature, so it dies and the group is told — and what is
     * worth keeping is which step failed.
     */
    private fun <T> Either<Throwable, T>.orThrow(step: String): T = when (this) {
        is Either.Right -> value
        is Either.Left -> throw IllegalStateException("FROST $step failed: ${value.message}", value)
    }

    /** This device's own message of [kind], if it has published one. */
    private suspend fun ownMessage(
        database: DedunDatabase,
        session: FrostSigningSession,
        kind: Kind
    ): FrostSignerMessage? = database.frostSigningSessionDao().getMessage(
        sessionId = session.id,
        kind = kind,
        signerPublicKey = session.userPublicKey
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
        session: FrostSigningSession,
        transform: (FrostSigningSession) -> FrostSigningSession
    ): FrostSigningSession {
        val current = database.frostSigningSessionDao().getSessionById(session.id) ?: session
        val updated = transform(current)

        if (updated == current) return current

        val stamped = updated.copy(updatedAt = Clock.System.now())
        database.frostSigningSessionDao().upsert(stamped)

        return stamped
    }

    /**
     * Moves the progress label forward, never back. Stages are declared in the
     * order the session runs them, so a message arriving out of order cannot
     * walk the UI back down the ladder.
     */
    private suspend fun moveTo(
        database: DedunDatabase,
        session: FrostSigningSession,
        stage: FrostSigningStage
    ): FrostSigningSession = update(database, session) { current ->
        val settled =
            current.stage == FrostSigningStage.COMPLETE || current.stage == FrostSigningStage.FAILED

        if (settled || current.stage.ordinal >= stage.ordinal) current else current.copy(stage = stage)
    }

    /**
     * Ends the session, and puts the line saying so in the group's chat with
     * [culprit]'s name in front of it.
     *
     * [content] is that line's predicate. The default is the one a fault gets;
     * a refusal that tips the count writes its own, because what happened there
     * is that a member declined, and the abandonment is the arithmetic of it.
     */
    private suspend fun fail(
        database: DedunDatabase,
        session: FrostSigningSession,
        reason: String,
        culprit: HexKey = session.userPublicKey,
        content: String = "abandoned the signature. Nothing was signed, and it is safe to ask again."
    ) {
        // Read before writing so the notice goes out once. A session can be failed
        // from two directions -- a FAILURE message from a member, and a fault
        // raised locally -- and the group does not need to be told twice.
        //
        // A signature the group has made is not abandoned by anything that
        // arrives afterwards. A member who refused before their device had seen
        // the signature has refused something that no longer needed them, and
        // marking a signed session failed on their say-so would be reporting
        // a signature that exists as one that does not.
        val current = database.frostSigningSessionDao().getSessionById(session.id) ?: session
        if (current.stage == FrostSigningStage.FAILED || current.stage == FrostSigningStage.COMPLETE) return

        update(database, current) {
            it.copy(stage = FrostSigningStage.FAILED, failureReason = reason)
        }
        announce(
            database = database,
            session = current,
            messageType = ChatMessage.TYPE_FROST_FAILED,
            content = content,
            actor = culprit
        )
    }

    /** The stored session, for reading a column back before overwriting it. */
    private suspend fun current(
        database: DedunDatabase,
        session: FrostSigningSession
    ): FrostSigningSession =
        database.frostSigningSessionDao().getSessionById(session.id) ?: session

    /**
     * Whether this session is waiting on its owner to agree to sign.
     *
     * Mirrors the gate in [advance]: pending exactly when [advance] would stop at
     * it. The two must agree, or the screen offers an approval that does nothing,
     * or none while the session sits still.
     */
    fun isAwaitingApproval(
        session: FrostSigningSession,
        items: List<FrostSigningItem>
    ): Boolean {
        if (session.stage == FrostSigningStage.COMPLETE || session.stage == FrostSigningStage.FAILED) {
            return false
        }

        // The group signed it without needing this member, and [advance] closes
        // the session on its next pass without asking them anything. Offering the
        // decision anyway would be offering two bad answers: a nonce nobody is
        // waiting for, or a refusal nobody is counting.
        if (items.isNotEmpty() && items.all { it.isSigned() }) return false

        // Either answer is an answer. A member who refused is not asked again,
        // even though the session is still open around them: the question was
        // theirs to answer once, and it has been.
        return session.signApprovedAt == null && session.signDeclinedAt == null
    }

    /**
     * Records that this device's owner agreed to sign, then lets the session run
     * as far as the next thing it is waiting on.
     *
     * Approving a session not waiting on it is a no-op rather than an error: a
     * stale screen left open across a redelivery should not publish anything.
     */
    suspend fun approve(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String
    ) {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return
        val items = database.frostSigningSessionDao().getItems(sessionId)

        if (!isAwaitingApproval(session, items)) {
            logger.i("Session $sessionId is not waiting on an approval; ignoring it")
            return
        }

        // A spend this device took in before it refused anything but a payout -- an older build,
        // or before it learnt whose the coins are -- is declined rather than signed.
        // docs/payouts-only.md, Phase 6.
        if (session.subject == FrostSigningSubject.TRANSACTION) {
            val refused = checkedSpend(database, sessionId)?.let { refusePayout(database, session.chatRoomId, it) }
            if (refused != null) {
                logger.w("Session $sessionId $refused; declining instead of signing")
                decline(database, localChatRoom, sessionId)
                return
            }
        }

        update(database, session) { it.copy(signApprovedAt = Clock.System.now()) }

        logger.i("Session $sessionId: signing approved")

        advance(database, localChatRoom, sessionId)
    }

    /**
     * Refuses to sign, and tells the group so the coordinator can pick somebody
     * else rather than wait.
     *
     * A t-of-n group can sign without this member, so declining is a normal
     * outcome and not a failure of the session — but only if it is said out loud.
     * Silence is indistinguishable from a phone in a pocket.
     *
     * It used to end the session on the spot, which made one member's no into
     * everybody's. Now it is one refusal among however many the key can do
     * without: [recordDecline] counts them, on this device as on every other,
     * and the session is abandoned only once fewer than the threshold could
     * still sign -- so in a 2-of-3 group the first refusal changes nothing the
     * other two can do, and the second ends it.
     *
     * The same shape as [approve]: the decision is written to the row, and
     * [advance] does the publishing, so that a device killed in between says
     * it on the next message rather than never.
     */
    suspend fun decline(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String
    ) {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return
        val items = database.frostSigningSessionDao().getItems(sessionId)

        if (!isAwaitingApproval(session, items)) {
            logger.i("Session $sessionId is not waiting on an approval; ignoring the refusal")
            return
        }

        update(database, session) { it.copy(signDeclinedAt = Clock.System.now()) }

        logger.i("Session $sessionId: signing declined")

        advance(database, localChatRoom, sessionId)
    }

    /**
     * Broadcasts this device's refusal AND files it, so it is counted here the
     * way it will be everywhere else.
     *
     * Not [publishOwn], although it is the same two steps in the same order,
     * because the local copy is not just a copy: filing it is what may end the
     * session, and [recordDecline] is the one place that decides.
     */
    /**
     * Re-publishes what this device wrote for a session, for the members it has
     * heard nothing from. Returns whether anything went out.
     *
     * A member can be left with nothing at all for a session they would have
     * agreed to. Several ways, and only some of them heal on their own: a group
     * event that decrypted to nothing because MLS had moved its ratchet past the
     * generation it arrived at, which is terminal at the receiver and invisible
     * to the sender (`docs/mls-skipped-keys.md`); a disappearing-message group
     * whose kind:445 expired off the relay before they synced; a member added to
     * the room after the proposal, holding a share from an earlier ceremony. In
     * each of them the session sits in [FrostSigningStage.COLLECTING_NONCES]
     * until somebody gives up, and the member it was waiting on never knew.
     *
     * A resend repairs all three because a resent message is a *new* message:
     * new `created_at`, new id, encrypted at a generation the receiver has not
     * passed, carrying a fresh expiry.
     *
     * ### Only this device's own messages, and only the coordinator's
     *
     * What goes out is the proposal, the signer set and the signature -- the
     * three the coordinator authors. A member's nonce, partial signature or
     * refusal is not resent and cannot be: [record] files a signer's message
     * under the inner event's own `pubKey`, so a relayed copy would be filed
     * under the relayer, and for a refusal that is a way to manufacture one.
     * Nor is there a need. A nonce and a partial signature are read by nobody
     * but the coordinator -- [orderedNonces] and [orderedPartialSignatures] are
     * both called under `isCoordinator` -- and the coordinator already holds
     * them, which is how it knows who is quiet. What the resend does for a
     * silent member instead is name them, in `FrostSilentMembersTag`, so their
     * own device republishes what it already said. See [republishOwn].
     *
     * Coordinator-only, and that is correctness rather than etiquette.
     * [acceptProposal] takes `coordinatorPublicKey` from the proposal's author,
     * so a non-coordinator resending it to a member with no session row would
     * have that member believe the resender coordinates -- and then drop the
     * real coordinator's signer set through [isFromCoordinator].
     *
     * ### Who it goes to
     *
     * [membersOwing], which asks what the stage makes sense of: who has said
     * nothing at all while nonces are being collected, and which of the chosen
     * signers has yet to sign once they have been chosen. The first question
     * asked at the second stage names only members who owe nothing, which is
     * how a session could sit in [FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES]
     * with a control that refused to knock.
     *
     * ### The minimum the stage requires
     *
     * | stage | what goes out |
     * |---|---|
     * | [FrostSigningStage.COLLECTING_NONCES] | the proposal |
     * | [FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES] | the proposal, then the signer set |
     * | [FrostSigningStage.COMPLETE] | the proposal, then the signature |
     * | [FrostSigningStage.FAILED] | nothing -- refused |
     *
     * The proposal goes in every case because a device with no session row can
     * apply nothing else: a signer set naming ids it has no items for, and a
     * signature over events it has never seen, are both dropped.
     * [FrostSigningStage.COMPLETE] is worth repeating rather than skipping --
     * a member who never received the signature never ran [complete], so the
     * signed events were never applied to their store.
     *
     * Two and not three, deliberately. Publishing in bursts is what the
     * skipped-keys bug feeds on, and a recovery for it that re-creates it is no
     * recovery. `MlsGroupCache` covers a burst decrypted in one sync, which is
     * what makes [proposeSigningBatch]'s own two-message burst work; this stays
     * inside the same budget.
     *
     * A failed session is refused rather than repaired. Its items' nonce seeds
     * have been published against an aggregate already, so the retry is a fresh
     * proposal and never this one again -- which is what [fail] tells the group.
     */
    /**
     * Asks again about several sessions, one after another, and says how many
     * actually published.
     *
     * One at a time and spaced, which is the whole reason this is here rather
     * than a loop at the call site: a caller that launched [resend] per session
     * would put every one of their two-message bursts into the same second, and
     * the room's tolerance for that is the thing `MlsGroupCache` documents the
     * limits of. See [RESEND_SPACING].
     *
     * A session that publishes nothing is stepped over rather than stopping the
     * run -- a cooldown that has not expired, or one that turns out to be
     * waiting on nobody, is a reason to skip it and not to abandon the others.
     * The count returned is of the ones that went out, so a caller can say "two
     * of three" rather than claiming all of them.
     */
    suspend fun resendAll(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionIds: List<String>
    ): Int {
        var sent = 0

        sessionIds.forEachIndexed { index, sessionId ->
            // Before, not after, so the last session is not followed by a pause
            // nobody is waiting through.
            if (index > 0) delay(RESEND_SPACING)

            if (resend(database, localChatRoom, sessionId)) sent++
        }

        logger.i("Asked again about $sent of ${sessionIds.size} session(s)")

        return sent
    }

    suspend fun resend(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String
    ): Boolean {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return false

        if (!session.isCoordinator()) {
            logger.i("Session $sessionId was not proposed by this device; it is not ours to resend")
            return false
        }

        if (session.stage == FrostSigningStage.FAILED) {
            logger.i("Session $sessionId was abandoned; nothing to resend. Propose afresh instead")
            return false
        }

        val items = database.frostSigningSessionDao().getItems(sessionId)
        if (items.isEmpty()) {
            logger.w("Session $sessionId holds no items; nothing to resend")
            return false
        }

        val now = Clock.System.now()
        session.resentAt?.let { last ->
            if (now - last < RESEND_COOLDOWN) {
                logger.i("Session $sessionId was resent less than $RESEND_COOLDOWN ago; not again yet")
                return false
            }
        }

        val owed = membersOwing(database, session)
        if (owed.isEmpty()) {
            logger.i("Session $sessionId is waiting on nobody; nothing to resend")
            return false
        }

        // The proposal as it was first sent: the events, or the transaction.
        val (proposalKind, proposalContent, proposalTags) = when (session.subject) {
            FrostSigningSubject.EVENTS -> {
                val unsigned = items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
                if (unsigned.size != items.size) {
                    // Refused rather than shortened. A proposal that quietly lost an
                    // event would have every signer's contribution rejected for being
                    // the wrong length, which is a stall with nothing to blame.
                    logger.w(
                        "Session $sessionId: only ${unsigned.size} of ${items.size} stored events could " +
                            "be read; not resending a proposal that is missing one"
                    )
                    return false
                }
                Triple(FrostSigningEvents.PROPOSAL, FrostSigningEvents.encodeProposal(unsigned), emptyArray<Array<String>>())
            }

            // Byte for byte the PSBT that was proposed -- it is what every item's
            // sighash was computed from, and a member who already holds the
            // session ignores any other.
            FrostSigningSubject.TRANSACTION -> {
                val transaction = database.groupTransactionDao().getBySessionId(sessionId)
                if (transaction == null) {
                    logger.w("Session $sessionId: no transaction on file; nothing to resend")
                    return false
                }
                Triple(
                    FrostSigningEvents.TRANSACTION_PROPOSAL,
                    transaction.psbt,
                    FrostSigningEvents.walletTags(transaction.chain, transaction.account)
                )
            }

            // The sweep as proposed, which the transaction row keeps where a spend keeps its PSBT.
            FrostSigningSubject.SWEEP -> {
                val transaction = database.groupTransactionDao().getBySessionId(sessionId)
                val sweep = transaction?.let { decodeSweep(it.psbt) }
                if (transaction == null || sweep == null) {
                    logger.w("Session $sessionId: no sweep on file; nothing to resend")
                    return false
                }
                Triple(
                    FrostSigningEvents.TRANSACTION_PROPOSAL,
                    transaction.psbt,
                    FrostSigningEvents.sweepTags(transaction.chain, transaction.account, sweep.wallet)
                )
            }
        }

        logger.i(
            "Resending session $sessionId at ${session.stage} to ${owed.size} member(s) " +
                "(${owed.joinToString { it.take(8) }})"
        )

        // The proposal first, so a device that has nothing has somewhere to file
        // what follows it.
        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = proposalKind,
            content = proposalContent,
            includeKey = true,
            silentMembers = owed.toList(),
            extraTags = proposalTags
        )

        when (session.stage) {
            FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> {
                val chosen = session.signerIdList()
                val aggregated = items.map { it.aggregatedNonce }
                if (chosen != null && aggregated.all { it != null }) {
                    broadcast(
                        database = database,
                        localChatRoom = localChatRoom,
                        session = session,
                        kind = FrostSigningEvents.SIGNER_SET,
                        content = joinPayload(aggregated.map { it!! }),
                        signerIds = chosen
                    )
                }
            }

            FrostSigningStage.COMPLETE -> {
                val signatures = items.map { it.signature }
                if (signatures.all { it != null }) {
                    broadcast(
                        database = database,
                        localChatRoom = localChatRoom,
                        session = session,
                        kind = FrostSigningEvents.SIGNATURE,
                        content = joinPayload(signatures.map { it!! })
                    )
                }
            }

            FrostSigningStage.COLLECTING_NONCES, FrostSigningStage.FAILED -> Unit
        }

        update(database, session) { it.copy(resentAt = now) }
        announceResent(database, session, owed.size)

        return true
    }

    /**
     * The share holders this session is still waiting on, which is a different
     * set of people at each stage.
     *
     * Off the ceremony rather than off the room, like [recordDecline]'s own
     * count and `FrostSigningUIState.Loaded.members`: a room can hold members
     * added after its key was made who hold no share, and a subgroup's ceremony
     * room is the parent, which holds everybody the subgroup is not. A door that
     * cannot answer is not one to knock on.
     *
     * ### The question changes when the stage does
     *
     * While nonces are being collected the question is who has said *nothing*,
     * and a member with any message on file has answered it.
     *
     * Past that point that reading is not merely imprecise, it is inverted. A
     * session reaches [FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES] only
     * because enough members offered a nonce, so everybody who could be holding
     * it up has been heard from -- and asking who is silent returns the members
     * who were never chosen and owe nothing, while the chosen signer whose
     * partial signature went missing is never named. That is a knock on the
     * wrong door; and once the unchosen have answered or refused it is no knock
     * at all, because the set is empty and [resend] gives up before it reaches
     * the stage it was written for.
     *
     * So the later stage asks its own question: of the signers the coordinator
     * chose, who has yet to send a partial signature. Naming them is the half of
     * a resend that only they can carry out -- a partial signature is read by
     * nobody but the coordinator, so one lost on the way here can be repeated by
     * no other device. See [republishOwn].
     *
     * [FrostSigningStage.COMPLETE] keeps the first reading, and correctly: the
     * signature is the coordinator's own and nobody answers it, so having heard
     * nothing at all is still exactly what it means to have missed the session.
     *
     * A member who declined is never here, at either stage. They answered, and
     * the session is counting their refusal; knocking again would be asking a
     * question they have already closed.
     */
    suspend fun membersOwing(
        database: DedunDatabase,
        session: FrostSigningSession
    ): Set<HexKey> {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return emptySet()
        val holders = signerIds(database, key)

        val collectingPartials = session.stage == FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES
        val chosen = session.signerIdList()?.toSet()

        // Whose answer the session is short of: the chosen signers once there
        // are any, every share holder before that. A stage that says signers
        // have been chosen and names none is nothing to knock about -- it cannot
        // happen, since [advance] writes the ids before it moves the stage, and
        // guessing at the set would be guessing at who is owed.
        val asked = when {
            !collectingPartials -> holders.keys
            chosen != null -> holders.filterValues { it in chosen }.keys
            else -> return emptySet()
        }

        val answered = database.frostSigningSessionDao()
            .getMessagesForSession(session.id)
            .filter { message ->
                // A nonce is the previous stage's answer. Counting it here is
                // what made every signer look like somebody who had replied.
                !collectingPartials ||
                    message.kind == FrostSigningEvents.PARTIAL_SIGNATURE ||
                    message.kind == FrostSigningEvents.DECLINE
            }
            .map { it.signerPublicKey }
            .toSet()

        return asked - answered - session.userPublicKey
    }

    /**
     * Takes every answered session in a room as far as its stored messages now
     * allow. The one thing here that no message drives.
     *
     * Being message-driven is this manager's whole shape, and it is not quite
     * enough on its own. A pass of [advance] is where a device publishes its
     * nonce and its partial signature, and a device killed part way through one
     * -- the coordinator's signer set recorded, the signature not yet made --
     * has nothing left to wake it. The message that would have is already filed,
     * so it is never processed again; and the coordinator's [resend] cannot
     * reach past that either, because what it asks a quiet member for is a
     * message this device believes it has already sent.
     *
     * **It publishes nothing new.** [advance]'s own gates decide what goes out,
     * and each asks whether a message exists rather than whether a stage says it
     * ran, so a session with nothing outstanding takes no steps at all. Only
     * sessions whose owner has answered are passed in, so this cannot put this
     * device's material behind events nobody agreed to: the approval gate is the
     * one thing a resume must not reach past, and this does not come near it.
     *
     * A refusal is an answer too, and resumes for the same reason: a device
     * killed between deciding and publishing says it on the next pass rather
     * than leaving the group waiting on a member who has already made up their
     * mind.
     */
    suspend fun resumeAnswered(database: DedunDatabase, localChatRoom: LocalChatRoom) {
        val answered = database.frostSigningSessionDao()
            .getSessionsForChatRoom(localChatRoom.chatRoom.id)
            .filter { session ->
                session.stage != FrostSigningStage.COMPLETE &&
                    session.stage != FrostSigningStage.FAILED &&
                    (session.signApprovedAt != null || session.signDeclinedAt != null)
            }

        if (answered.isEmpty()) return

        logger.i(
            "Resuming ${answered.size} answered signing session(s) in " +
                localChatRoom.chatRoom.id
        )

        // One at a time, and one failing does not stop the rest: [advance]
        // settles its own session's failure and returns.
        answered.forEach { session -> advance(database, localChatRoom, session.id) }
    }

    /**
     * Re-publishes the messages *this* device authored for a session, because
     * the coordinator says it never received them.
     *
     * The other half of a resend, and the only half that can repair a signal
     * lost on its way *to* the coordinator. [advance] republishes a message it
     * has no local copy of; a device that published its nonce and recorded it
     * has a copy, so it will never send that nonce again however long the
     * coordinator waits. This is how it learns to.
     *
     * The stored bytes, never regenerated. They are byte-identical either way --
     * `SecretNonce.generate` is deterministic in the seed and the message, and
     * an item's message cannot change -- so this cannot produce a second nonce
     * for one message, which is the thing the whole design is arranged to make
     * impossible. Reading the row just avoids k native key generations to arrive
     * at the same answer.
     *
     * **It publishes nothing new.** Only messages already on file go out, so a
     * device whose owner has not decided sends nothing and falls through to
     * [advance], which does what it always does: write the approval request and
     * wait on a person. A message from the coordinator must never be able to
     * talk a device into putting its share behind events its owner has not
     * agreed to -- that is [acceptProposal]'s "publishes nothing" rule, and the
     * gate enforcing it is untouched.
     *
     * Caller checks that the request came from the coordinator. A member who
     * could name another member as silent could make them publish on demand --
     * the same bytes they already sent, so nothing on the wire changes, but it
     * is not a trigger to leave lying around.
     */
    private suspend fun republishOwn(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession
    ) {
        val own = OWN_KINDS.mapNotNull { kind -> ownMessage(database, session, kind) }
        if (own.isEmpty()) return

        logger.i(
            "Session ${session.id}: the coordinator has not heard from this device; " +
                "republishing ${own.size} message(s) it already sent"
        )

        // To the room, not to the coordinator alone. Only the coordinator reads a
        // nonce, so addressing one would be the honest shape -- but on NIP-17 the
        // p-tag set is the room's identity rather than a recipient list, and
        // naming fewer members files the message under a different room. See
        // [recipientTags].
        own.forEach { message ->
            broadcast(
                database = database,
                localChatRoom = localChatRoom,
                session = session,
                kind = message.kind,
                content = message.payload
            )
        }
    }

    private suspend fun publishDecline(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession
    ) {
        val reason = "Declined to sign"

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.DECLINE,
            content = reason
        )

        recordDecline(
            database = database,
            session = session,
            member = session.userPublicKey,
            reason = reason
        )
    }

    /**
     * The line saying the coordinator knocked again.
     *
     * Its own type rather than another step: nothing moved, somebody asked. The
     * ladder should show that the knock happened so that a second one reads as a
     * second one -- which `FrostSigningSession.resentAt` makes true, since it is
     * what stops a tap from writing a line without publishing anything.
     *
     * Counted rather than named. Who is quiet is on the screen above this line,
     * member by member, and a transcript line listing four pubkeys is a line
     * nobody reads.
     */
    private suspend fun announceResent(
        database: DedunDatabase,
        session: FrostSigningSession,
        owed: Int
    ) {
        val one = owed == 1
        val who = if (one) "the member" else "the $owed members"

        // What they are short of, not just that they are quiet. Past the nonces
        // the members being asked have answered once already, and a line saying
        // they have not answered would be the screen contradicting itself.
        val what = when (session.stage) {
            FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES ->
                if (one) "who has not signed their part" else "who have not signed their part"

            else -> if (one) "who has not answered" else "who have not answered"
        }

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_RESENT,
            content = "asked $who $what to take another look.",
            actor = session.coordinatorPublicKey
        )
    }

    private suspend fun announceStarted(database: DedunDatabase, session: FrostSigningSession) {
        val count = database.frostSigningSessionDao().countItems(session.id)

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_STARTED,
            content = (
                if (session.subject == FrostSigningSubject.TRANSACTION) "asked the group to spend from its wallet"
                else "asked the group to sign " + (if (count > 1) "$count events" else "something") + " with its shared key"
            ) + ". It takes ${session.threshold} of ${session.participantCount} members to do it.",
            actor = session.coordinatorPublicKey
        )
    }

    /**
     * Tells the group's chat that this session is waiting on the reader.
     *
     * Written once, guarded by [FrostSigningSession.approvalRequestedAt], because
     * [advance] runs on every arriving message and would otherwise ask again on
     * each one.
     */
    private suspend fun announceApprovalNeeded(
        database: DedunDatabase,
        session: FrostSigningSession
    ) {
        if (current(database, session).approvalRequestedAt != null) return

        update(database, session) { it.copy(approvalRequestedAt = Clock.System.now()) }

        val count = database.frostSigningSessionDao().countItems(session.id)

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_APPROVAL_NEEDED,
            content = (
                if (session.subject == FrostSigningSubject.TRANSACTION) "Your approval is needed to spend from the group's wallet. "
                else "Your approval is needed to sign " + (if (count > 1) "$count events " else "") + "with the group's shared key. "
            ) + "Nothing has been published from this device yet.",
            actor = session.userPublicKey
        )
    }

    /**
     * Puts one protocol message in the group's chat, as a line naming who sent it.
     *
     * Same reasoning as the ceremony's transcript: these are things a particular
     * member's device did, and a signature made with the group's key is worth
     * being able to watch. The wording describes what a step accomplishes rather
     * than what it is called.
     */
    private suspend fun announceStep(
        database: DedunDatabase,
        session: FrostSigningSession,
        kind: Kind,
        actor: HexKey
    ) {
        // A batch is still one line per member per step -- what changes is the
        // number in it. Every one of these lines describes work that covered the
        // whole batch, so saying so is the difference between a member reading
        // "signed their part" and knowing what they signed.
        val count = database.frostSigningSessionDao().countItems(session.id)
        val batch = count > 1

        val (messageType, content) = when (kind) {
            FrostSigningEvents.NONCE -> ChatMessage.TYPE_FROST_NONCE to
                "offered to help sign, sending the one-time " +
                    (if (batch) "values their signatures need." else "value their signature needs.")

            FrostSigningEvents.SIGNER_SET -> ChatMessage.TYPE_FROST_SIGNER_SET to
                "chose who is signing and combined their one-time values."

            FrostSigningEvents.PARTIAL_SIGNATURE -> ChatMessage.TYPE_FROST_PARTIAL_SIGNATURE to
                "signed their part" + (
                    if (session.subject == FrostSigningSubject.TRANSACTION) " of the transaction"
                    else if (batch) " of all $count events" else ""
                ) +
                    ". On its own it proves nothing; combined with the others it is " +
                    "the group's " + (if (batch) "signatures." else "signature.")

            FrostSigningEvents.SIGNATURE -> ChatMessage.TYPE_FROST_SIGNATURE to
                "combined the parts into the group's " +
                    (if (batch) "$count signatures." else "signature.")

            // PROPOSAL, FAILURE and DECLINE are announced by the code that acts on
            // them -- each says more than the message itself carries.
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

    /**
     * Puts a signing milestone in the group's chat.
     *
     * Each device writes its own row from the messages it has already received, so
     * this costs no traffic and cannot disagree with the session it describes.
     * Written once by construction rather than de-duplication: every caller checks
     * before it writes, because [ChatMessage] has no key to make a second insert a
     * no-op.
     *
     * Every line names its session. A room signs repeatedly and can have two
     * sessions open at once, so which one a line is about is the difference
     * between a request that is still asking and one that has been answered --
     * see [ChatMessage.frostSigningSessionId].
     */
    private suspend fun announce(
        database: DedunDatabase,
        session: FrostSigningSession,
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
                frostSigningSessionId = session.id
            )
        )
    }

    /**
     * Broadcasts one of this device's own protocol messages AND records it
     * locally. The local copy matters: the coordinator signs too, and its own
     * message has to be in the aggregation alongside everyone else's.
     *
     * Queued before it is recorded, because [advance] republishes any message it
     * has no local copy of. A crash between the two therefore costs a duplicate
     * broadcast — which every receiver folds away — rather than a message the
     * group waits on forever.
     *
     * Which cuts both ways, so a [broadcast] that queued nothing records
     * nothing: the local copy is the thing that stops the next pass from trying
     * again.
     */
    private suspend fun publishOwn(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession,
        kind: Kind,
        content: String
    ) {
        // Nothing is recorded if nothing was queued. The local copy is what stops
        // [advance] from publishing this again, so writing one for a message that
        // did not go out would be a message the group waits on forever -- the
        // opposite of the trade this order is for.
        if (!broadcast(database, localChatRoom, session, kind, content)) return

        val known = ownMessage(database, session, kind) != null

        database.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = session.id,
                signerPublicKey = session.userPublicKey,
                kind = kind,
                payload = content
            )
        )

        if (!known) announceStep(database, session, kind, session.userPublicKey)
    }

    /**
     * Queues one of this device's signing messages for the outbound pipeline,
     * on whichever transport the room it is in has.
     *
     * In a Marmot room it is an unprocessed inner event: `NotaryViewModel` picks
     * it up, MLS-encrypts it and broadcasts it as a kind:445 for the room -- the
     * same path every other event in a Marmot group takes.
     *
     * In a NIP-17 room it is a gift-wrap payload, sealed once per recipient and
     * broadcast the way a ritual message is. That is not a second-class path; it
     * is the only one a group has before it owns a Marmot room, which is exactly
     * where the group's first signature is made -- see
     * `GroupKeyStateManager.propose`.
     *
     * The p-tags are the difference between the two, and they are not
     * cosmetic. A group event is encrypted to the group and who is in it is the
     * MLS tree's business, so a Marmot message names nobody; a gift wrap is
     * addressed and sealed per recipient, so a NIP-17 message has to name
     * everybody or the members it left out never see it. Neither shape lets a
     * recipient list decide anything: the signer set comes from the ceremony's
     * host keys either way, so tagging somebody does not put them in it, and
     * failing to tag somebody only stops them hearing.
     */
    private suspend fun broadcast(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession,
        kind: Kind,
        content: String,
        includeKey: Boolean = false,
        signerIds: List<Int>? = null,
        silentMembers: List<HexKey>? = null,
        extraTags: Array<Array<String>> = emptyArray()
    ): Boolean {
        val sessionTags = FrostSigningEvents.assembleTags(
            sessionId = session.id,
            dkgSessionId = if (includeKey) session.dkgSessionId else null,
            signerIds = signerIds,
            silentMembers = silentMembers
        ) + extraTags

        // No MLS state is what marks a room NIP-17 -- the same reading
        // `NostrNip17Dao.getOrCreateChatRoom` writes and `sendChatMessage` makes
        // when it chooses between a group event and gift wraps.
        val isMarmotRoom = localChatRoom.chatRoom.mlsGroupState != null

        val tags = if (isMarmotRoom) {
            sessionTags
        } else {
            recipientTags(localChatRoom, session.userPublicKey) + sessionTags
        }

        // The rumor id the outbound pipeline will recompute from these same
        // fields when it assembles the event to encrypt, on either transport --
        // and with it the second this message claims to have been written in,
        // because that second is one of the fields.
        //
        // Which is why this is a search and not an assignment. `created_at` is
        // stored to the second, so a message re-published inside the same second
        // as the copy it repeats hashes to the same id; upserting that would put
        // an already-sent row back on the outbound queue with its
        // `marmotGroupEventId` cleared -- sending the message twice and losing
        // the row's mapping to the group event it became. So an id already in
        // the store is stepped past rather than written over. One second of drift
        // per collision is nothing a protocol message reads, and no caller
        // publishes faster than [MAX_ID_SEARCH] identical messages a second.
        var createdAt = Clock.System.now().epochSeconds
        var id = EventHasher.hashId(
            pubKey = session.userPublicKey,
            createdAt = createdAt,
            tags = tags,
            content = content,
            kind = kind
        )
        var attempts = 0

        while (attempts < MAX_ID_SEARCH && isQueued(database, isMarmotRoom, id)) {
            createdAt += 1
            id = EventHasher.hashId(
                pubKey = session.userPublicKey,
                createdAt = createdAt,
                tags = tags,
                content = content,
                kind = kind
            )
            attempts += 1
        }

        // Refused rather than written over. The row this would replace is a
        // message this device has already queued or sent, and clearing its
        // mapping is strictly worse than not repeating it.
        if (attempts >= MAX_ID_SEARCH) {
            logger.w(
                "Session ${session.id}: kind $kind already queued at every second tried; " +
                    "not publishing it again"
            )
            return false
        }

        if (isMarmotRoom) {
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
                    publicKey = session.userPublicKey,
                    kind = kind,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = tags,
                    content = content,
                    chatRoomId = localChatRoom.chatRoom.id
                )
            )
        }

        return true
    }

    /** Whether this id is already an outbound row on the room's transport. */
    private suspend fun isQueued(
        database: DedunDatabase,
        isMarmotRoom: Boolean,
        id: HexKey
    ): Boolean = if (isMarmotRoom) {
        database.marmotInnerEventDao().getById(id) != null
    } else {
        database.giftWrapPayloadDao().getById(id) != null
    }

    /**
     * One p-tag per member of a NIP-17 room, the sender excepted.
     *
     * The sender is left out rather than tagged because a gift wrap is sealed
     * per recipient and this device already has its own copy -- `publishOwn`
     * records it locally. Deduplicated by member, since `localParticipants` is a
     * row list and a room can carry the same person twice.
     *
     * **Every member, always.** This list is not a recipient list that a caller
     * may narrow -- on this transport it is the room's *identity*. A receiver
     * computes the room a payload belongs to as
     * `ChatRoom.deriveChatRoomId({sender} + p-tags)`; see
     * `GiftWrapPayload.aggregatedParticipantsPublicKey`, which is what
     * `GiftWrapSeal.decryptGiftWrapPayload` files an inbound payload under. Leave
     * a member out and the id changes, so the message is filed under a room that
     * is not the one it was sent in -- and `getOrCreateChatRoom` will mint that
     * room rather than reject it, which turns a lost message into a phantom
     * conversation in the reader's list.
     *
     * A resend was briefly narrowed to the members it was for, on the reasoning
     * that a wrap is sealed per recipient and repeating one for the whole room
     * wastes wraps. Both halves of that were wrong: the saving does not exist
     * either, because `sealGiftWrapPayload` seals for every participant of the
     * room it looks up rather than for the p-tags it was handed.
     */
    private fun recipientTags(
        localChatRoom: LocalChatRoom,
        senderPublicKey: HexKey
    ): Array<Array<String>> = localChatRoom.localParticipants
        .distinctBy { it.participant.participantPublicKey }
        .filter { it.participant.participantPublicKey != senderPublicKey }
        .map { localParticipant ->
            PTag.assemble(
                localParticipant.participant.participantPublicKey,
                localParticipant.participant.relayHint?.let { NormalizedRelayUrl(it) }
            )
        }
        .toTypedArray()
}
