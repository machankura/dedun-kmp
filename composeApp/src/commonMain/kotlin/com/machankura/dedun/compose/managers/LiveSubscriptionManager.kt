package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.network.relays.LiveSubscriptionTransport
import com.machankura.dedun.compose.network.relays.isRelayBackPressure
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.network.sockets.endsLiveSubscription
import com.machankura.dedun.compose.nostr.MemberProfileSync
import com.machankura.dedun.compose.nostr.Nip17Filters
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the chat subscriptions open for as long as it runs, so relays push new events at us
 * instead of us asking for them once per screen.
 *
 * See `docs/long-running-sync.md`. The short version: every chat sync in this app used to be
 * a pull — a screen queues a request row, a pump drains it, the relay answers, the
 * subscription is closed — which is structurally incapable of delivering a message that
 * arrives one second after EOSE.
 *
 * Two subscriptions, on every DM relay:
 *  - [GIFT_WRAP_SUB_ID], kind 1059 p-tagged to us: direct messages, and the Marmot Welcome
 *    events that make us a member of a group.
 *  - [GROUP_SUB_ID_PREFIX]`<n>`, kind 445 h-tagged with the groups we belong to, in chunks.
 *
 * This deliberately does NOT share `SynchronizationViewModel`'s subscription semaphore. That
 * budget is four permits across all relays, sized for requests that finish; a subscription
 * that never finishes would hold one forever and permanently halve the backfill throughput.
 * The two budgets have to be read together against what a relay will actually tolerate
 * (commonly ~20 per connection), which they comfortably are.
 */
class LiveSubscriptionManager(
    private val relaysSocketManager: LiveSubscriptionTransport,
    private val nostrRepository: NostrRepository,
    private val chatRepository: ChatRepository,
    private val isForeground: StateFlow<Boolean>,
) {
    private val logger = Logger.withTag(TAG)

    companion object {
        private const val TAG = "LiveSubscriptionManager"

        /**
         * Stable, and the same on every relay. A subscription id is scoped to its socket, and
         * a relay answers a repeated REQ on an existing id by replacing that subscription's
         * filter — which is what lets a filter be widened later without a close-and-reopen.
         */
        const val GIFT_WRAP_SUB_ID = "live-giftwrap"

        const val GROUP_SUB_ID_PREFIX = "live-groups-"

        /**
         * Group ids per `#h` filter. One subscription per group would be simpler, but every
         * Marmot group here lives on the same DM relay set, so carrying many ids in one
         * filter costs a fraction of the subscriptions. Conservative rather than maximal:
         * relays cap the size of a filter's tag arrays, and a chunk that is refused is a
         * chunk of conversations that go quiet.
         */
        private const val MAX_GROUPS_PER_SUBSCRIPTION = 100

        /**
         * How much history to ask for when a subscription is opened.
         *
         * NIP-01 scopes `limit` to the initial query — the stored events a relay sends before
         * EOSE — and explicitly not to the stream that follows. So this bounds what a
         * reconnect costs without touching the live tail at all. Filling in the rest of the
         * history is negentropy's job, not this subscription's.
         */
        private const val INITIAL_HISTORY_LIMIT = 100

        /**
         * A group chunk covers up to [MAX_GROUPS_PER_SUBSCRIPTION] conversations at once, so
         * the same initial window has to stretch much further than the gift wrap one.
         */
        private const val INITIAL_GROUP_HISTORY_LIMIT = 500

        /**
         * Joining a group writes the room, its participants and placeholder profiles in quick
         * succession, each of which re-emits the room list. Without this the group filter
         * would be re-sent several times per join.
         */
        private val GROUP_CHANGE_DEBOUNCE = 500.milliseconds

        private val INITIAL_REOPEN_DELAY = 5.seconds

        private val MAX_REOPEN_DELAY = 5.minutes

/**
         * Every gift wrap addressed to us: direct messages, and the Marmot Welcome events
         * that make us a member of a group.
         *
         * Deferred to [Nip17Filters] rather than written here, because this shape has been
         * got wrong in several call sites before and the reasons it is what it is belong in
         * one place — chiefly the absence of a `since`, which looks like an oversight and is
         * the difference between receiving mail and silently not.
         */
        internal fun giftWrapFilter(publicKey: HexKey) =
            Nip17Filters.liveInbox(publicKey, limit = INITIAL_HISTORY_LIMIT)

        /**
         * Marmot group messages for a chunk of the groups we belong to.
         *
         * Unlike gift wraps these carry honest timestamps (`MarmotOutboundDao` stamps them
         * with `TimeUtils.now()`), so a `since` watermark would be safe here. It is still not
         * used: `limit` already bounds the initial burst, and a watermark would have to be
         * recomputed every time the chunk's membership changed.
         */
        internal fun groupFilter(groupIds: List<HexKey>) = Filter(
            kinds = listOf(GroupEvent.KIND),
            tags = mapOf("h" to groupIds),
            limit = INITIAL_GROUP_HISTORY_LIMIT,
        )

        /**
         * The groups a live subscription should be watching.
         *
         * An MLS group is a room with group state; a NIP-17 room has none and is served by
         * the gift wrap subscription instead. A room we have left or deleted keeps its
         * history locally but must stop pulling new messages.
         *
         * Sorted so the same membership in a different row order is the same value, and a
         * re-emit that changes nothing costs nothing downstream.
         */
        internal fun groupIdsFrom(rooms: List<LocalChatRoom>): List<HexKey> =
            rooms
                .filter { it.chatRoom.mlsGroupState != null }
                .filter { it.chatRoom.leftGroupAt == null && it.chatRoom.deletedAt == null }
                // The addresses, not the ids: a relay indexes the `h` tag, and on the
                // current profile that is a routing id apart from the room's own. Every
                // address a room has had, so a message published to a prior one while
                // it is still inside the retention window is not missed.
                .flatMap { it.routingIds }
                .distinct()
                .sorted()

        internal fun groupChunks(groupIds: List<HexKey>): List<List<HexKey>> =
            groupIds.chunked(MAX_GROUPS_PER_SUBSCRIPTION)

        internal fun groupSubId(index: Int) = "$GROUP_SUB_ID_PREFIX$index"

        internal fun subscriptionKey(relayUrl: String, subId: String) = "$relayUrl|$subId"

        /**
         * The chunk index a [subscriptionKey] refers to, or [Int.MAX_VALUE] for anything that
         * does not parse — which reads as "past the end", so an unrecognised key is closed
         * rather than kept open forever.
         */
        internal fun subscriptionIndex(key: String) =
            key.substringAfterLast(GROUP_SUB_ID_PREFIX).toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * The group ids each `live-groups-<n>` subscription is currently responsible for.
     *
     * Read by the subscription coroutines when they (re-)open, written by the single coroutine
     * collecting the room list. Replaced wholesale rather than mutated, so a reader always
     * sees a coherent chunking.
     */
    @Volatile
    private var groupIdChunks: List<List<HexKey>> = emptyList()

    /**
     * Runs until cancelled. Cancellation is the only exit: the caller scopes this to the
     * active wallet, so a wallet switch tears every subscription down and the new wallet's
     * call builds its own.
     */
    suspend fun observe(keyPair: KeyPair) {
        val publicKey = keyPair.pubKey.toHexKey()

        // collectLatest is the whole lifecycle mechanism: going to the background cancels the
        // block below, and every subscription's `finally` sends its CLOSE on the way out.
        isForeground.collectLatest { foreground ->
            if (!foreground) {
                logger.i("Backgrounded; live subscriptions closed")
                return@collectLatest
            }

            runWhileForeground(publicKey = publicKey, keyPair = keyPair)
        }
    }

    /**
     * Everything below inherits the caller's dispatcher rather than pinning Dispatchers.IO.
     * `SynchronizationViewModel` already launches [observe] on IO, so nothing has moved — but
     * a coroutine that picks its own dispatcher cannot be driven by a test scheduler.
     */
    private suspend fun runWhileForeground(publicKey: HexKey, keyPair: KeyPair): Unit = coroutineScope {
        logger.i("Foregrounded; opening live subscriptions for $publicKey")

        // Before anything is asked for. A socket that was open when the OS suspended us
        // reports itself connected on the way back while being functionally dead, and the
        // relay dropped our subscriptions long ago.
        runCatching { relaysSocketManager.reconnectAll() }
            .onFailure { logger.w(throwable = it) { "Reconnect on foreground failed" } }

        // Live subscriptions cover the window we are online for; this covers the gap we were
        // not. Neither subsumes the other.
        launch { queueCatchUpSynchronization(publicKey) }

        Relays.DefaultDMRelayList.forEach { relay ->
            launch {
                runLiveSubscription(
                    subId = GIFT_WRAP_SUB_ID,
                    relayUrl = relay.url,
                    filters = { listOf(giftWrapFilter(publicKey)) },
                    keyPair = keyPair,
                )
            }
        }

        launch { followGroupMembership(publicKey = publicKey, keyPair = keyPair) }
    }

    /**
     * Reconciles what we hold against what the relays hold, for the time we were away.
     *
     * A live subscription answers "what is new since I connected"; negentropy answers "what do
     * you have that I don't". Coming back from the background is exactly the question only the
     * second one can answer, and `limit` on the re-opened subscriptions is a window, not a
     * guarantee.
     */
    private suspend fun queueCatchUpSynchronization(publicKey: HexKey) {
        val rooms = chatRepository.getChatRoomListByPublicKey(publicKey)
        val groupIds = groupIdsFrom(rooms)

        val giftWrapFilter = Nip17Filters.inbox(publicKey)

        val requests = mutableListOf<NegentropySynchronizeRequest>()

        Relays.DefaultDMRelayList.forEach { relay ->
            requests += NegentropySynchronizeRequest(
                id = NegentropySynchronizeRequest.computeId(
                    relayURL = relay.url,
                    synchronizationFilter = giftWrapFilter,
                ),
                purpose = "chat",
                synchronizationFilter = giftWrapFilter,
                relayURL = relay.url,
                level = 0,
            )

            if (groupIds.isEmpty()) return@forEach

            val groupSynchronizationFilter = SynchronizationFilter(
                kinds = arrayOf(GroupEvent.KIND),
                tags = mapOf("h" to groupIds),
            )
            requests += NegentropySynchronizeRequest(
                id = NegentropySynchronizeRequest.computeId(
                    relayURL = relay.url,
                    synchronizationFilter = groupSynchronizationFilter,
                ),
                purpose = "mlsMessages",
                synchronizationFilter = groupSynchronizationFilter,
                relayURL = relay.url,
                level = 0,
            )
        }

        // The members none of our rooms can name yet. Asked for here, where every room
        // this account has is already in hand, because the ask made where a placeholder
        // is first minted happens once and a Marmot group gives it no second chance --
        // see [MemberProfileSync].
        val unnamedMembers = MemberProfileSync.unresolvedMemberPublicKeys(rooms, publicKey)
        requests += MemberProfileSync.requests(unnamedMembers)

        logger.i(
            "Queueing ${requests.size} catch-up reconciliation(s) over ${groupIds.size} group(s)" +
                " and ${unnamedMembers.size} unnamed member(s)"
        )
        nostrRepository.queueNegentropySynchronizeRequest(requests)
    }

    /**
     * Keeps the group subscriptions matching the groups we are actually in.
     *
     * Derived from the room list rather than wired into the places a group is joined, because
     * a group id can appear four ways and only one of them is somewhere anyone would think to
     * call a subscribe function: we create a group, we are added to one (a Welcome processed
     * deep inside `NostrDao.storeNostrEvent`), membership shifts under us via a commit, or we
     * leave. Observing the table catches all four.
     *
     * It also closes the loop: a Welcome arrives on the gift wrap subscription above, a
     * ChatRoom row is written, this flow re-emits, and the group filter widens — so the first
     * message in a group we were just added to arrives without anyone opening a chat.
     */
    @OptIn(FlowPreview::class)
    private suspend fun followGroupMembership(publicKey: HexKey, keyPair: KeyPair): Unit = coroutineScope {
        val subscriptions = mutableMapOf<String, Job>()

        chatRepository.observeChatRoomListByPublicKey(publicKey)
            .map { rooms -> groupIdsFrom(rooms) }
            .distinctUntilChanged()
            .debounce(GROUP_CHANGE_DEBOUNCE)
            .collect { groupIds ->
                reconcileGroupSubscriptions(
                    groupIds = groupIds,
                    subscriptions = subscriptions,
                    keyPair = keyPair,
                    scope = this,
                )
            }
    }

    /**
     * Brings the open group subscriptions in line with [groupIds].
     *
     * A chunk that already has a subscription is updated in place — the relay replaces that
     * subscription's filter and the collector never notices — so a new group does not
     * interrupt delivery on the groups that were already being watched. Chunks that no longer
     * exist have their coroutine cancelled, which sends the CLOSE from its `finally`.
     */
    private suspend fun reconcileGroupSubscriptions(
        groupIds: List<HexKey>,
        subscriptions: MutableMap<String, Job>,
        keyPair: KeyPair,
        scope: CoroutineScope,
    ) {
        val chunks = groupChunks(groupIds)
        groupIdChunks = chunks

        logger.i("Live group membership: ${groupIds.size} group(s) over ${chunks.size} subscription(s)")

        Relays.DefaultDMRelayList.forEach { relay ->
            val relayUrl = relay.url

            chunks.forEachIndexed { index, chunk ->
                val subId = groupSubId(index)
                val key = subscriptionKey(relayUrl = relayUrl, subId = subId)

                if (subscriptions[key]?.isActive == true) {
                    relaysSocketManager.updateLiveSubscription(
                        reqCommand = ReqCmd(subId = subId, filters = listOf(groupFilter(chunk))),
                        relayUrl = relayUrl,
                    )
                } else {
                    subscriptions[key] = scope.launch {
                        runLiveSubscription(
                            subId = subId,
                            relayUrl = relayUrl,
                            // Read at every open rather than captured, so a subscription that
                            // has to be re-opened after a CLOSED comes back with the current
                            // membership rather than the membership it was created with.
                            filters = {
                                groupIdChunks.getOrNull(index)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { listOf(groupFilter(it)) }
                                    .orEmpty()
                            },
                            keyPair = keyPair,
                        )
                    }
                }
            }

            // Membership shrank far enough to need fewer subscriptions.
            subscriptions.keys
                .filter { it.startsWith("$relayUrl|$GROUP_SUB_ID_PREFIX") }
                .filter { subscriptionIndex(it) >= chunks.size }
                .forEach { key ->
                    logger.i("Closing $key: no groups left in that chunk")
                    // Joined, not just cancelled. The CLOSE is sent from that coroutine's
                    // finally, so returning before it lands would let a later reconcile open a
                    // subscription on the same id that the old one then closes out from under.
                    subscriptions.remove(key)?.cancelAndJoin()
                }
        }
    }

    /**
     * Keeps one subscription open on one relay, re-opening it if the relay ends it.
     *
     * A socket that merely drops needs nothing from here: the pool replays the REQ on
     * reconnect and the collector below, which is attached to the socket client rather than
     * to a session, simply starts receiving again. This loop is for the other case — a relay
     * that answered with CLOSED, which is terminal for that subscription and needs a new one.
     */
    private suspend fun runLiveSubscription(
        subId: String,
        relayUrl: String,
        filters: () -> List<Filter>,
        keyPair: KeyPair,
    ) {
        var reopenDelay = INITIAL_REOPEN_DELAY

        try {
            while (currentCoroutineContext().isActive) {
                val currentFilters = filters()
                if (currentFilters.isEmpty()) {
                    // Nothing to ask for right now — a group chunk emptied out and the
                    // reconcile that will cancel this coroutine has not run yet.
                    delay(INITIAL_REOPEN_DELAY)
                    continue
                }

                val end = try {
                    collectUntilClosed(
                        subId = subId,
                        relayUrl = relayUrl,
                        filters = currentFilters,
                        keyPair = keyPair,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.e("Live subscription $subId on $relayUrl failed", error)
                    SubscriptionEnd(reason = error.message, reachedEose = false)
                }

                // A subscription that got as far as EOSE was working. Whatever ended it is a
                // new problem, not a continuing one, so it does not inherit the old backoff.
                if (end.reachedEose) reopenDelay = INITIAL_REOPEN_DELAY

                reopenDelay = if (isRelayBackPressure(end.reason)) {
                    // Answering "you have too many subscriptions" by opening another one
                    // promptly is how one refusal becomes a flood of them.
                    logger.w("$relayUrl is rate limiting $subId; backing off to the maximum")
                    MAX_REOPEN_DELAY
                } else {
                    minOf(reopenDelay * 2, MAX_REOPEN_DELAY)
                }

                logger.w("Re-opening $subId on $relayUrl in $reopenDelay (ended: ${end.reason})")
                delay(reopenDelay)
            }
        } finally {
            // NonCancellable so a cancelled manager still releases the retained REQ, which is
            // what tells the socket it no longer has a reason to reconnect.
            withContext(NonCancellable) {
                runCatching { relaysSocketManager.closeLiveSubscription(subId, relayUrl) }
                    .onFailure { logger.w(throwable = it) { "Failed to close $subId on $relayUrl" } }
            }
        }
    }

    private data class SubscriptionEnd(
        /** The relay's CLOSED reason, or the failure that ended the collection. */
        val reason: String?,
        /** Whether the relay got as far as sending EOSE before it ended. */
        val reachedEose: Boolean,
    )

    private suspend fun collectUntilClosed(
        subId: String,
        relayUrl: String,
        filters: List<Filter>,
        keyPair: KeyPair,
    ): SubscriptionEnd {
        var reason: String? = null
        var reachedEose = false

        relaysSocketManager.openLiveSubscription(
            reqCommand = ReqCmd(subId = subId, filters = filters),
            relayUrl = relayUrl,
        ).transformWhile { message ->
            emit(message)
            !message.endsLiveSubscription()
        }.collect { message ->
            when (message) {
                is NostrIncomingMessage.EventMessage -> {
                    message.nostrEvent?.let { save(it, relayUrl, keyPair) }
                }

                is NostrIncomingMessage.EventsMessage -> {
                    message.nostrEvents.forEach { save(it, relayUrl, keyPair) }
                }

                is NostrIncomingMessage.EoseMessage -> {
                    reachedEose = true
                    logger.i("$subId on $relayUrl has caught up; now live")
                }

                is NostrIncomingMessage.ClosedMessage -> {
                    reason = message.message
                    logger.w("Relay closed $subId ($relayUrl): ${message.message}")
                }

                is NostrIncomingMessage.NoticeMessage -> {
                    // Advisory only. A NOTICE carries no subscription id, so it is delivered
                    // to every collector on the socket — acting on one here would let an
                    // unrelated relay complaint tear this subscription down.
                    logger.d("Notice while running $subId ($relayUrl): ${message.message}")
                }

                else -> logger.d("Unhandled message on $subId ($relayUrl): $message")
            }
        }

        return SubscriptionEnd(reason = reason, reachedEose = reachedEose)
    }

    /**
     * Saved inline rather than in a launched coroutine, on purpose. Sequential writes keep
     * the events in the order the relay sent them and let the socket's buffer do the
     * back-pressure, instead of fanning a burst out into a coroutine per event.
     */
    private suspend fun save(nostrEvent: NostrEvent, relayUrl: String, keyPair: KeyPair) {
        nostrRepository.saveNostrEvent(
            nostrEvent = nostrEvent,
            relayURL = relayUrl,
            synchronizationRelayURLs = listOf(relayUrl),
            level = 0,
            activeKeyPair = keyPair,
        )
    }
}
