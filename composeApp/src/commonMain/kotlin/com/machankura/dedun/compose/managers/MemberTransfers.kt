package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.wallet.MemberTransferRollout
import com.machankura.dedun.compose.wallet.TransferLedger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Members transferring part of their balance to each other: `docs/member-transfers.md`.
 *
 * What every device counts: the transfers the group has signed in a room, first per request, in the group's own order, and
 * what each moves on a rail. A transfer moves no money. Each rail's balance adds [netsIn] to what it already counts, and the
 * deposit ledger never sees one (T7).
 *
 * And how one comes about: the giver signs a request ([TransferRequestEvent]) and carries it into the room; a device that
 * holds a share puts it to the group as a [MemberTransferEvent] embedding it; every signer checks it against its own count
 * ([refuse]) before it is shown, and again before it signs.
 */
object MemberTransfers {
    private val logger = Logger.withTag("MemberTransfers")

    /** The longest a request may stand, as for a payout: a far-off one could still be acted on long after anyone remembers it (T9). */
    const val MAX_REQUEST_SECONDS: Long = 7L * 24 * 60 * 60

    /** How far ahead of this device's clock a request may be dated, as for a payout destination. */
    const val MAX_FUTURE_SECONDS: Long = 600

    /** How long a request stands unless the giver says otherwise. */
    const val DEFAULT_REQUEST_SECONDS: Long = 24L * 60 * 60

    // ---- What has been signed ---------------------------------------------

    /** Every transfer the group has signed in [chatRoomId] on [chain], first per request, in [MemberTransferEvent.ORDER]. */
    suspend fun signedIn(database: DedunDatabase, chatRoomId: String, chain: Chain): List<MemberTransferEvent.Transfer> =
        MemberTransferEvent.firstPerRequest(
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, MemberTransferEvent.KIND).map { it.toEvent() },
            chatRoomId,
            chain,
        )

    /**
     * What each member has received and given on [rail] in [chatRoomId] on [chain], by the transfers the group signed that
     * their givers could cover ([TransferLedger.netsOf]): a transfer that overdraws [capacityIn] counts on no device.
     */
    suspend fun netsIn(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail): Map<HexKey, TransferLedger.Net> =
        TransferLedger.netsOf(signedIn(database, chatRoomId, chain), chain, rail, capacityIn(database, chatRoomId, chain, rail))

    /**
     * What each member holds on [rail] before any transfer, for deciding which transfers count (`docs/member-transfers.md`,
     * T15): their deposits less **every payout the group signed for them, at its most** -- paid, open, expired, refunded or
     * gone nowhere alike. Only signed records go in, so devices that co-signed different rounds, or none, agree on the figure;
     * a device that never saw a payout's round can't take its expiry for "never paid". What it costs: a payout whose approval
     * ran out unpaid still holds its amount against its member's transfers, though not against their next payout.
     */
    suspend fun capacityIn(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail): Map<HexKey, Long> {
        val capacity = HashMap<HexKey, Long>()
        fun add(member: String, sat: Long) {
            capacity[member.lowercase()] = (capacity[member.lowercase()] ?: 0) + sat
        }
        when (rail) {
            Rail.WAVELENGTH -> {
                DepositLedger.depositsIn(database, chatRoomId, chain)
                    .filter { it.source == DepositRow.WAVELENGTH && it.member != null }
                    .forEach { add(it.member!!, it.amountMsat / 1000) }
                GroupWavelengthPayouts.signed(database, chatRoomId, chain).forEach { add(it.payout!!.member, -(it.maxSat ?: 0)) }
                GroupWavelengthPayouts.signedOnchain(database, chatRoomId, chain).forEach { add(it.member, -it.maxSat) }
            }
            Rail.LIGHTNING -> {
                DepositLedger.depositsIn(database, chatRoomId, chain)
                    .filter { it.source == DepositRow.LIGHTNING && it.member != null }
                    .forEach { add(it.member!!, it.amountMsat / 1000) }
                DepositLedger.signedPayouts(database, chatRoomId, chain).forEach { add(it.payout!!.member, -((it.budgetMsat + 999) / 1000)) }
            }
            Rail.ONCHAIN -> Unit
        }
        return capacity
    }

    /** Every event in a signing session in [chatRoomId] that hasn't completed or failed. */
    private suspend fun beingSignedEvents(database: DedunDatabase, chatRoomId: String): List<Event> =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId).first()
            .filter { it.session.stage != FrostSigningStage.FAILED && it.session.stage != FrostSigningStage.COMPLETE }
            .flatMap { it.items }
            .mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }

    /**
     * The transfers in a signing session in [chatRoomId] that hasn't completed or failed: proposed, not yet the group's. A
     * giver with one of these has an outflow open (T8), and neither a payout nor another transfer of theirs is signed on that
     * rail until it settles one way or the other.
     */
    suspend fun beingSigned(database: DedunDatabase, chatRoomId: String): List<MemberTransferEvent.Transfer> =
        beingSignedEvents(database, chatRoomId)
            .filter { it.kind == MemberTransferEvent.KIND }
            .mapNotNull { MemberTransferEvent.read(it) }

    /** Whether [member] is giving something on [rail] and [chain] that the group is still signing, other than [except]. */
    suspend fun isGiving(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail, member: HexKey, except: String? = null): Boolean =
        beingSigned(database, chatRoomId).any {
            it.chain == chain && it.rail == rail && it.from.equals(member, ignoreCase = true) && it.requestId != except
        }

    // ---- What the giver asked ---------------------------------------------

    /** Every request and withdrawal carried in [chatRoomId], whoever carried it, signed by its giver and about this room. */
    private suspend fun statementsIn(database: DedunDatabase, chatRoomId: String): List<TransferRequestEvent.Statement> =
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(TransferRequestEvent.KIND))
            .mapNotNull { row -> TransferRequestEvent.carried(Event(row.id, row.publicKey, row.createdAt.epochSeconds, row.kind, row.tags, row.content, "")) }
            .mapNotNull { TransferRequestEvent.verified(it) }
            .filter { it.chatRoomId.equals(chatRoomId, ignoreCase = true) }
            .distinctBy { it.event.id }

    /** Whether [request]'s giver has taken it back, by a withdrawal carried in [chatRoomId]. */
    suspend fun isWithdrawn(database: DedunDatabase, chatRoomId: String, request: TransferRequestEvent.Request): Boolean =
        statementsIn(database, chatRoomId).filterIsInstance<TransferRequestEvent.Withdrawal>()
            .any { it.requestId.equals(request.id, ignoreCase = true) && it.giver.equals(request.giver, ignoreCase = true) && it.chain == request.chain }

    enum class State { WAITING, SIGNING, SIGNED, WITHDRAWN, EXPIRED }

    data class Asked(val request: TransferRequestEvent.Request, val state: State)

    /** Every request carried in [chatRoomId], newest first, with where it has got to. */
    suspend fun requestsIn(database: DedunDatabase, chatRoomId: String, nowSeconds: Long = Clock.System.now().epochSeconds): List<Asked> {
        val statements = statementsIn(database, chatRoomId)
        val withdrawn = statements.filterIsInstance<TransferRequestEvent.Withdrawal>().map { it.giver.lowercase() to it.requestId.lowercase() }.toSet()
        val requests = statements.filterIsInstance<TransferRequestEvent.Request>()
        val signing = beingSigned(database, chatRoomId).map { it.requestId }.toSet()
        val signed = requests.map { it.chain }.distinct().flatMap { signedIn(database, chatRoomId, it) }.map { it.requestId }.toSet()
        return requests.sortedByDescending { it.createdAt }.map { request ->
            val state = when {
                request.id in signed -> State.SIGNED
                (request.giver.lowercase() to request.id.lowercase()) in withdrawn -> State.WITHDRAWN
                request.id in signing -> State.SIGNING
                request.expiresAt <= nowSeconds -> State.EXPIRED
                else -> State.WAITING
            }
            Asked(request, state)
        }
    }

    // ---- What a signer checks -----------------------------------------------

    /**
     * What [member] can give on [rail] in [chatRoomId] on [chain], by this device's own count, in sats: on the Wavelength rail
     * their ledger before the wallet's shortfall is shared out (T5), over Lightning what they could be paid out. Null on the
     * onchain rail, whose transfers name coins (T6).
     */
    suspend fun givableSat(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail, member: HexKey, nowSeconds: Long = Clock.System.now().epochSeconds): Long? {
        val balance = when (rail) {
            Rail.WAVELENGTH -> GroupWavelengthPayouts.balancesIn(database, chatRoomId, chain, nowSeconds)[member.lowercase()]?.netSat ?: 0
            Rail.LIGHTNING -> (MemberBalances.balancesIn(database, chatRoomId, chain, null, nowSeconds).members[member.lowercase()]?.lightning?.availableMsat ?: 0) / 1000
            Rail.ONCHAIN -> return null
        }
        // And no more than the signed records alone cover, or the transfer would count on no device (T15).
        val covered = TransferLedger.capacityAfter(signedIn(database, chatRoomId, chain), chain, rail, capacityIn(database, chatRoomId, chain, rail), member)
        return minOf(balance, covered).coerceAtLeast(0)
    }

    /** Whether [member] has a payout open on [rail], approved and unpaid or being signed: an outflow a transfer waits for (T8). */
    private suspend fun hasPayoutOpen(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail, member: HexKey, nowSeconds: Long): Boolean =
        when (rail) {
            Rail.WAVELENGTH -> GroupWavelengthPayouts.hasPayoutOpen(database, chatRoomId, chain, member, nowSeconds)
            Rail.LIGHTNING -> {
                val open = MemberBalances.balancesIn(database, chatRoomId, chain, null, nowSeconds).members[member.lowercase()]?.lightning?.openMsat ?: 0
                open > 0 || beingSignedEvents(database, chatRoomId)
                    .filter { it.kind == GroupLightningAuthorisationEvent.KIND }
                    .mapNotNull { GroupLightningAuthorisationEvent.read(it.tags) }
                    .any { it.chain == chain && it.payout?.member.equals(member, ignoreCase = true) }
            }
            Rail.ONCHAIN -> false
        }

    /**
     * Why a signer won't sign [proposed], a transfer for [chatRoomId], or null when it will: `docs/member-transfers.md`, T4,
     * against this device's own records. Run when a proposal arrives, so a refused one is never shown, and again at approval.
     */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event, nowSeconds: Long = Clock.System.now().epochSeconds): String? {
        // 1-2: the giver's own signature, embedded whole, and every tag beside it agreeing with it.
        val transfer = MemberTransferEvent.read(proposed) ?: return "isn't a transfer its giver signed"
        val request = transfer.request
        if (!request.chatRoomId.equals(chatRoomId, ignoreCase = true)) return "is a transfer asked for in another group"
        if (request.expiresAt <= nowSeconds) return "was asked for in a request that has expired"
        if (request.createdAt > nowSeconds + MAX_FUTURE_SECONDS) return "was asked for in a request dated in the future"
        // A transfer counts only if the group signs it inside its request's window (T15), so one dated outside it moves nothing.
        if (proposed.createdAt < request.createdAt || proposed.createdAt > request.expiresAt) return "is dated outside its request's window"
        if (request.expiresAt > request.createdAt + MAX_REQUEST_SECONDS) return "stands for longer than the group lets anything stand"
        if (isWithdrawn(database, chatRoomId, request)) return "was taken back by the member who asked for it"

        // 3: between two members of the room, the receiver one the group has opened deposits for.
        val members = database.participantDao().findParticipantsByChatRoomId(chatRoomId).map { it.participantPublicKey.lowercase() }.toSet()
        if (transfer.from.lowercase() !in members) return "is from somebody who isn't a member"
        if (transfer.to.lowercase() !in members) return "is to somebody who isn't a member"
        if (transfer.to.lowercase() !in Depositors.statementsIn(database, chatRoomId, transfer.chain).keys.map { it.lowercase() }) {
            return "is to a member the group hasn't opened deposits for"
        }

        // 4: once per request.
        if (signedIn(database, chatRoomId, transfer.chain).any { it.requestId == transfer.requestId }) return "is a transfer the group has already recorded"

        // 5: one outflow open at a time.
        if (hasPayoutOpen(database, chatRoomId, transfer.chain, transfer.rail, transfer.from, nowSeconds)) return "is from a member who has a payout waiting"
        if (isGiving(database, chatRoomId, transfer.chain, transfer.rail, transfer.from, except = transfer.requestId)) {
            return "is from a member who is already transferring part of their balance"
        }

        // 6: no more than the giver has.
        val givable = givableSat(database, chatRoomId, transfer.chain, transfer.rail, transfer.from, nowSeconds)
            ?: return "moves coins, which this device can't check yet"
        if (transfer.amountSat > givable) return "transfers ${transfer.amountSat} sats, more than the $givable sats the member has to give"
        return null
    }

    // ---- Asking -------------------------------------------------------------

    /**
     * [giverPrivateKey]'s member asking the group to record a transfer of [amountSat] on [rail] to [receiver]: the request is
     * signed and carried into the room, and where this device can sign, put to the group at once (T10). Refused first if this
     * device would refuse it, so a member is never asked to approve what their own device has ruled out. Returns the signed
     * request, and the session if one was opened.
     */
    suspend fun request(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        giverPrivateKey: PrivateKey,
        chain: Chain,
        rail: Rail,
        receiver: HexKey,
        amountSat: Long,
        note: String? = null,
        standsFor: Long = DEFAULT_REQUEST_SECONDS,
        nowSeconds: Long = Clock.System.now().epochSeconds,
    ): Pair<TransferRequestEvent.Request, FrostSigningSession?> {
        val chatRoomId = localChatRoom.chatRoom.id
        require(standsFor in 1..MAX_REQUEST_SECONDS) { "a request stands for at most seven days" }
        require(MemberTransferRollout.offers(chain, rail)) { "transfers aren't offered on this rail and network yet" }
        val signed = TransferRequestEvent.build(giverPrivateKey, chatRoomId, chain, rail, receiver, amountSat, nowSeconds + standsFor, note, nowSeconds)
        val request = TransferRequestEvent.verified(signed) as TransferRequestEvent.Request
        refuse(database, chatRoomId, unsigned(chatRoomId, request), nowSeconds)?.let { throw IllegalArgumentException("This transfer $it") }

        carry(database, chatRoomId, signed)
        val session = if (FrostSigningManager.canSign(database, chatRoomId)) putToGroup(database, localChatRoom, request.giver, request) else null
        return request to session
    }

    /**
     * The giver taking back [request], which the group hasn't recorded yet (T9): the withdrawal is carried, so every signer
     * refuses it from now on, at approval as well as on arrival, and a session over it that somebody else put to the group is
     * declined here, so the giver's share is out of its quorum too. A session the giver proposed already counts their approval,
     * and a session with enough approvals before the withdrawal reaches them can still complete: until it is recorded, a
     * transfer being signed may still be.
     */
    suspend fun withdraw(database: DedunDatabase, localChatRoom: LocalChatRoom, giverPrivateKey: PrivateKey, request: TransferRequestEvent.Request) {
        val chatRoomId = localChatRoom.chatRoom.id
        require(giverPrivateKey.xOnlyPublicKey().value.toHex().equals(request.giver, ignoreCase = true)) { "only its giver can take a request back" }
        check(signedIn(database, chatRoomId, request.chain).none { it.requestId == request.id }) { "The group has already recorded it" }
        carry(database, chatRoomId, TransferRequestEvent.buildWithdrawn(giverPrivateKey, chatRoomId, request.chain, request.id))
        openSessionFor(database, chatRoomId, request)?.let { session ->
            runCatching { FrostSigningManager.decline(database, localChatRoom, session.id) }
                .onFailure { logger.w("Withdrew ${request.id.take(8)} but could not decline session ${session.id.take(8)}: ${it.message}") }
        }
    }

    /**
     * A device that holds a share putting [request] to the group (T10): the transfer it embeds, checked as this device would
     * check it, and no second session while one is already open over it.
     */
    suspend fun putToGroup(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, request: TransferRequestEvent.Request): FrostSigningSession {
        val chatRoomId = localChatRoom.chatRoom.id
        openSessionFor(database, chatRoomId, request)?.let { return it }
        val template = MemberTransferEvent.template(request)
        refuse(database, chatRoomId, unsigned(chatRoomId, request))?.let { throw IllegalStateException("This group would refuse it: it $it") }
        logger.i("Putting ${request.giver.take(8)}'s transfer ${request.id.take(8)} to $chatRoomId")
        return FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            createdAt = template.createdAt,
        )
    }

    /** The session over [request] in [chatRoomId] that hasn't completed or failed, if there is one. */
    suspend fun openSessionFor(database: DedunDatabase, chatRoomId: String, request: TransferRequestEvent.Request): FrostSigningSession? =
        database.frostSigningSessionDao().getSessionsForChatRoom(chatRoomId)
            .filter { it.stage != FrostSigningStage.FAILED && it.stage != FrostSigningStage.COMPLETE }
            .firstOrNull { session ->
                database.frostSigningSessionDao().getItems(session.id)
                    .mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
                    .any { it.kind == MemberTransferEvent.KIND && MemberTransferEvent.read(it)?.requestId == request.id }
            }

    /** [request] as the group's transfer, unsigned: what [refuse] reads. */
    private fun unsigned(chatRoomId: String, request: TransferRequestEvent.Request): Event {
        val template = MemberTransferEvent.template(request)
        return Event("00".repeat(32), chatRoomId.lowercase(), template.createdAt, template.kind, template.tags, template.content, "")
    }

    /** [signed], a request or a withdrawal, into [chatRoomId] whole, as an inner event's content, with its line on this device. */
    private suspend fun carry(database: DedunDatabase, chatRoomId: String, signed: Event) {
        require(signed.kind == TransferRequestEvent.KIND) { "not a transfer request" }
        val createdAt = Clock.System.now()
        val tags = arrayOf(arrayOf("h", chatRoomId.lowercase()))
        val content = signed.toJson()
        val id = MemberWalletManager.queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = signed.pubKey,
            tags = tags,
            content = content,
            kind = TransferRequestEvent.KIND,
            createdAt = createdAt,
        )
        val inner = Event(id, signed.pubKey, createdAt.epochSeconds, TransferRequestEvent.KIND, tags, content, "")
        ChatMessage.applyInnerEvent(
            database = database,
            groupId = chatRoomId,
            event = inner,
            marmotGroupEventId = null,
            marmotInnerEventId = id,
            senderPublicKey = signed.pubKey,
            isUserMessage = true,
            createdAt = createdAt,
        )?.let { database.chatMessageDao().upsert(it) }
    }
}
