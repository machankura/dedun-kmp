package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.BroadcastStatus
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.network.dto.toRelayDTO
import com.machankura.dedun.compose.AppLifecycle
import com.machankura.dedun.compose.managers.LiveSubscriptionManager
import com.machankura.dedun.compose.network.relays.RelayPool
import com.machankura.dedun.compose.network.relays.RelaysSocketManager
import com.machankura.dedun.compose.network.relays.isRelayBackPressure
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.network.sockets.DefaultNostrSocketClientFactory
import com.machankura.dedun.compose.repository.CachingImportRepository
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.repository.RelayRepository
import co.touchlab.kermit.Logger
import com.vitorpamplona.negentropy.Negentropy
import com.vitorpamplona.negentropy.storage.StorageVector
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CloseCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip77Negentropy.NegCloseCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegMsgCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegOpenCmd
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.toKeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.nostr.SignInSync
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SynchronizationViewModel(
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    val relayRepository: RelayRepository,
    val scope: CoroutineScope,
): ViewModel() {

    val relaysSocketManager = RelaysSocketManager(
        activeIdentityStateFlow = activeIdentityStateFlow,
        nostrSocketClientFactory = DefaultNostrSocketClientFactory,
        cachingImportRepository = CachingImportRepository.NO_OP_CACHING_IMPORT_REPOSITORY,
        relayRepository = relayRepository,
    )

    /**
     * The chat subscriptions that stay open while a wallet is active. Built here because it
     * has to share this class's RelaysSocketManager — a second one would mean a second
     * RelayPool and a duplicate socket per relay.
     */
    private val liveSubscriptionManager = LiveSubscriptionManager(
        relaysSocketManager = relaysSocketManager,
        nostrRepository = nostrRepository,
        chatRepository = chatRepository,
        isForeground = AppLifecycle.isForeground,
    )

    companion object {
        private const val TAG = "SynchronizationViewModel"

        private const val MAX_CONCURRENT_PUBLISHES = 8

        /**
         * Well under the ~20-per-connection cap relays commonly enforce, and applied
         * across all relays rather than per relay, which keeps the emulator's socket
         * count sane too.
         */
        private const val MAX_CONCURRENT_SUBSCRIPTIONS = 4

        /**
         * Hard ceiling on a single publish attempt, covering the suspend calls that open the
         * socket as well as the response flow, so a request can never sit in "processing".
         */
        private val PUBLISH_ATTEMPT_TIMEOUT = (RelayPool.PUBLISH_TIMEOUT * 2).milliseconds

        /**
         * Hard ceiling on one REQ or negentropy exchange. Both hold a subscription slot for
         * their whole life, and a relay that opens a subscription and then goes quiet owes us
         * no EOSE — without a bound, four such relays park every slot and the queue behind
         * them never drains. Generous rather than tight: the cost of cutting a slow but live
         * download short is re-fetching it next pass, and a REQ can carry MAX_IDS_PER_REQ ids.
         */
        private val SUBSCRIPTION_TIMEOUT = 120.seconds

        /**
         * Backstop for a peer whose ranges never converge. Each round splits the disagreeing
         * ranges 16 ways, so a well-behaved exchange over even a very large set settles in a
         * handful; this only bounds the pathological case.
         */
        private const val MAX_NEGENTROPY_ROUNDS = 32

        /**
         * Ids per fallback REQ. Relays cap the size of a filter's `ids` array (1000 is the
         * common limit), and a first sync can reconcile far more than that.
         */
        private const val MAX_IDS_PER_REQ = 500

        private const val NOSTR_EVENT_ID_HEX_LENGTH = 64

        private val mutex = Mutex()

        fun factory(
            activeIdentityStateFlow: StateFlow<Identity?>,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            relayRepository: RelayRepository,
            scope: CoroutineScope,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SynchronizationViewModel(
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    relayRepository = relayRepository,
                    scope = scope
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    /** Guards the one-shot startup requeue against a second active-wallet emission. */
    private var hasRequeuedStaleBroadcasts = false

    /** Caps how many publishes may be in flight while the queue drains a backlog. */
    private val publishSlots = Semaphore(MAX_CONCURRENT_PUBLISHES)

    /**
     * Caps how many REQ/NEG-OPEN subscriptions may be open at once.
     *
     * The queue advances the moment a request is marked "sent", so without this the
     * pump opened a subscription per pending row with nothing awaiting the previous
     * one — relays answered with "too many concurrent REQs" and, because a NOTICE is
     * delivered to every collector on the socket, logged it once per open collector.
     * Requests queue on this semaphore instead, so the backlog still drains, just not
     * all at once.
     *
     * Live subscriptions are deliberately outside this budget (see LiveSubscriptionManager):
     * a subscription that never finishes would hold a permit forever. The two have to be
     * read together against what a relay tolerates per connection, not separately.
     */
    private val subscriptionSlots = Semaphore(MAX_CONCURRENT_SUBSCRIPTIONS)

    /**
     * Keeps one bad request from killing the pump that is draining the queue. Mirrors
     * `NotaryViewModel.guardNotarization`.
     */
    private inline fun guardPump(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Synchronization step failed: $what", e)
        }
    }

    init {
        scope.launch {
            activeIdentityStateFlow.collectLatest { identity ->
                logger.d("Synchronization identity: $identity")
                identity?.let {
                    // Through the helper, never by hand: a read-only identity has no private
                    // key, and quartz's KeyPair given neither key generates a fresh one.
                    val keyPair = identity.toKeyPair()

                    // Anything a previous run left mid-flight is ours to retry: it was flipped to
                    // "processing" before the publish and nothing observes that status, so
                    // without this it would sit in the table forever. Once per process only —
                    // collectLatest can fire again on a wallet switch, and re-running it then
                    // would resurrect rows whose publish is still in flight.
                    if (!hasRequeuedStaleBroadcasts) {
                        hasRequeuedStaleBroadcasts = true
                        runCatching { nostrRepository.requeueStaleBroadcastNostrEventRequests() }
                            .onFailure { logger.e("Failed to requeue stale broadcasts", it) }
                    }

                    // Run the three pumps as children of collectLatest so a wallet switch
                    // cancels them. Launching on `scope` (as this used to) escaped that
                    // cancellation and accumulated a duplicate set of collectors — each
                    // racing the same single-row queues with a stale key pair.
                    // supervisorScope, not coroutineScope: the three pumps are independent, and a
                    // throw in one must not take the other two down with it for the rest of the
                    // session. Each pump also guards its own per-request body (see guardPump).
                    supervisorScope {
                        launch(Dispatchers.IO) { observePendingSyncNostrEventRequests(keyPair) }
                        launch(Dispatchers.IO) { observePendingNegentropySynchronizeRequests(keyPair) }
                        // A read-only identity runs the two pumps that read and neither of
                        // the two that write. The rule Phase 5 of docs/npub-sign-in.md
                        // leans on: such an identity never sends anything to a relay, not a
                        // signature and not a copy. The broadcast pump would find nothing --
                        // nothing is ever signed -- and the live subscriptions are the
                        // gift-wrap inbox and the group-membership follow, both fetching
                        // things this identity cannot open. Not asking is not an
                        // optimisation; it is the identity not asking for what it cannot use.
                        if (identity.canSign) {
                            // The wraps that arrived while another profile was open, or
                            // while this one held no key: opened now, before the live inbox
                            // starts, so the two are not opening the same wrap at once. Both
                            // are idempotent by event id; the order is about not doing the
                            // work twice. See docs/multiple-profiles.md, Phase 6.
                            runCatching { nostrRepository.reopenInbox(keyPair) }
                                .onSuccess { if (it > 0) logger.i { "Reopened $it gift wrap(s) that arrived while this profile was not open" } }
                                .onFailure { logger.e("Could not reopen the inbox", it) }
                            launch(Dispatchers.IO) { observePendingBroadcastNostrEventRequests(keyPair) }
                            // Runs until cancelled rather than draining a queue, but it
                            // belongs here for the same reason the pumps do: it needs the
                            // active wallet's key pair, and a wallet switch must tear it down.
                            launch(Dispatchers.IO) { liveSubscriptionManager.observe(keyPair) }
                        }
                    }
                }
            }
        }

    }

    private suspend fun observePendingSyncNostrEventRequests(
        keyPair: KeyPair
    ): Unit = coroutineScope {
        logger.i { "observePendingSyncNostrEventRequests" }

        // This identity's requests and the ones nobody owns; another identity's wait for it.
        nostrRepository.observePendingSynchronizeNostrEventRequests(keyPair.pubKey.toHexKey()).distinctUntilChanged().collect { synchronizeNostrEventRequestOrNull ->
            synchronizeNostrEventRequestOrNull?.let { synchronizeNostrEventRequest ->
                guardPump("sync request ${synchronizeNostrEventRequest.id}") {
                    logger.i("synchronizeNostrEventRequest: $synchronizeNostrEventRequest")
                    val reqCommand = ReqCmd(
                        subId = synchronizeNostrEventRequest.id,
                        filters = synchronizeNostrEventRequest.synchronizationFilters.map { synchronizationFilter ->
                            Filter(
                                ids = synchronizationFilter.ids?.toList(),
                                authors = synchronizationFilter.authors?.toList(),
                                kinds = synchronizationFilter.kinds?.toList(),
                                tags = synchronizationFilter.tags,
                                tagsAll = synchronizationFilter.tagsAll,
                                since = synchronizationFilter.since?.epochSeconds,
                                until = synchronizationFilter.until?.epochSeconds,
                                limit = synchronizationFilter.limit,
                                search = synchronizationFilter.search
                            )
                        }
                    )

                    nostrRepository.synchronizeNostrEventRequestProcessed(synchronizeNostrEventRequest)

                    launch(Dispatchers.IO) {
                      subscriptionSlots.withPermit {
                        try {
                            // Bounded for the same reason the negentropy exchange is: a relay
                            // that answers a REQ with neither EOSE nor CLOSED would otherwise
                            // hold a subscription slot for the life of the app.
                            withTimeout(SUBSCRIPTION_TIMEOUT) {
                            relaysSocketManager.query(
                                reqCommand,
                                synchronizeNostrEventRequest.relayURL
                            ).collect { nostrIncomingMessage ->
                                when (nostrIncomingMessage) {
                                    is NostrIncomingMessage.EventMessage -> {
                                        launch(Dispatchers.IO) {
                                            logger.d("Import message: $nostrIncomingMessage")
                                            nostrIncomingMessage.nostrEvent?.let {
                                                nostrRepository.saveNostrEvent(
                                                    nostrEvent = it,
                                                    synchronizeNostrEventRequest,
                                                    synchronizationRelayURLs = listOf(synchronizeNostrEventRequest.relayURL),
                                                    activeKeyPair = keyPair
                                                    // TODO: + Relays.eventPublishRelaySet.map { normalizedRelayUrl -> normalizedRelayUrl.url }
                                                )
                                                followSignInHop(it, synchronizeNostrEventRequest)
                                            }
                                        }
                                    }
                                    is NostrIncomingMessage.EventsMessage -> {
                                        logger.d("Import messages: $nostrIncomingMessage")

                                        nostrIncomingMessage.nostrEvents.forEach { nostrEvent ->
                                            nostrRepository.saveNostrEvent(
                                                nostrEvent = nostrEvent,
                                                synchronizeNostrEventRequest,
                                                synchronizationRelayURLs = listOf(synchronizeNostrEventRequest.relayURL),
                                                activeKeyPair = keyPair
                                                // TODO: + Relays.eventPublishRelaySet.map { normalizedRelayUrl -> normalizedRelayUrl.url }
                                            )
                                            followSignInHop(nostrEvent, synchronizeNostrEventRequest)
                                        }
                                    }
                                    is NostrIncomingMessage.EoseMessage -> {
                                        logger.d("Sync request has been successfully processed (${synchronizeNostrEventRequest.relayURL}): $nostrIncomingMessage")
                                        // EOSE ends the flow (completeOnSubscriptionEnd);
                                        // the finally below sends the CLOSE.
                                    }
                                    is NostrIncomingMessage.ClosedMessage -> {
                                        // The relay ended the subscription on its side (auth
                                        // required, rate limit, unsupported filter): no EOSE
                                        // will follow. Previously CLOSED was not even parsed, so
                                        // the reason was invisible. (`return@collect` only ends
                                        // handling of this message — same as the EOSE branch.)
                                        logger.w("Relay closed sync subscription (${synchronizeNostrEventRequest.relayURL}): ${nostrIncomingMessage.message}")
                                        return@collect
                                    }
                                    else -> {
                                        logger.d("Unhandled message ${synchronizeNostrEventRequest.relayURL}: $nostrIncomingMessage")
                                    }
                                }
                            }
                            }

                        } catch (e: Throwable) {
                            logger.e("Failed to sync", e)
                        } finally {
                            // Close on every exit, not just the EOSE branch. A
                            // subscription the relay never terminates would otherwise
                            // hold a slot on both sides for the life of the app.
                            // NonCancellable so a cancelled pump still says goodbye.
                            withContext(NonCancellable) {
                                runCatching {
                                    relaysSocketManager.closeQuery(
                                        CloseCmd(subId = synchronizeNostrEventRequest.id),
                                        synchronizeNostrEventRequest.relayURL
                                    )
                                }
                                // And write down that the relay has finished, on every exit
                                // too. Before this a request that got EOSE with nothing stayed
                                // at `sent` for good, and "still searching" and "searched,
                                // found nothing" were the same row -- which is why the screen
                                // waiting on a sign-in sync could never say the second thing.
                                // Conditional on `sent` in the DAO: an event handler that has
                                // already moved the row to `processed` wins.
                                runCatching {
                                    nostrRepository.synchronizeNostrEventRequestCompleted(synchronizeNostrEventRequest)
                                }.onFailure { logger.e("Failed to record sync completion", it) }
                            }
                        }
                      }
                    }
                }
            }
        }
    }

    /**
     * Sign-in syncs this session has already followed a hop for, by account. Every
     * bootstrap relay that holds the user's kind 10002 answers with it, and one hop per
     * account is enough.
     */
    private val signInHopsFollowed = mutableSetOf<Long>()
    private val signInHopsMutex = Mutex()

    /**
     * The one hop the sign-in sync follows. A level-0 request that brings back the user's
     * own kind 10002 is asked again, at level 1, at the relays that list says they write
     * to, less the bootstrap set already asked. That is the outbox model doing what it is
     * for: the indexers know *where* the user publishes, and the user's own relays are
     * where the rest of their lists are authoritative. One hop only -- a level-1 request
     * never re-enters here -- because past the user's own relays lies the feed, not the
     * profile.
     */
    private suspend fun followSignInHop(
        nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,
        request: com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest,
    ) {
        if (request.purpose != SignInSync.PURPOSE || request.level != 0) return
        if (nostrEvent.kind != AdvertisedRelayListEvent.KIND) return
        val unsignedNostrEventId = request.unsignedNostrEventId ?: return
        val author = request.synchronizationFilters.firstOrNull()?.authors?.firstOrNull() ?: return
        if (!nostrEvent.pubKey.equals(author, ignoreCase = true)) return

        val relays = SignInSync.writeRelaysOf(nostrEvent.tags, alreadyAsked = SignInSync.bootstrapRelays)
        if (relays.isEmpty()) return
        val isFirst = signInHopsMutex.withLock { signInHopsFollowed.add(unsignedNostrEventId) }
        if (!isFirst) return

        logger.i { "Following the sign-in relay list of $author to ${relays.size} relay(s)" }
        nostrRepository.queueSynchronizeNostrEvent(
            SignInSync.requests(unsignedNostrEventId, author, relays, level = 1)
        )
    }

    private suspend fun observePendingNegentropySynchronizeRequests(
        keyPair: KeyPair
    ): Unit = coroutineScope {
        logger.i { "observePendingNegentropySynchronizeRequests" }

        nostrRepository.observePendingNegentropySynchronizeRequests(keyPair.pubKey.toHexKey()).distinctUntilChanged().collect { negentropySynchronizeRequestOrNull ->
                negentropySynchronizeRequestOrNull?.let { negentropySynchronizeRequest ->
                  guardPump("negentropy request ${negentropySynchronizeRequest.id}") {
                    mutex.withLock {
                        logger.i("negentropySynchronizeRequest: $negentropySynchronizeRequest")

                        val events = nostrRepository.getNegentropicNostrFeedIds(
                            arrayOf(
                                negentropySynchronizeRequest.synchronizationFilter
                            ),
                            applyLimits = false
                        )
                        logger.d("Events: ${events.size}")

                        // The vector is built before the request leaves "pending", and building it
                        // can throw (a malformed id, a duplicate). The pending queue is a single
                        // oldest-row-first observation, so a row that throws here and never
                        // changes status is never retried AND blocks every negentropy request
                        // behind it for the life of the process. Claim the row either way.
                        val negentropy = runCatching { events.toNegentropy() }
                            .onFailure { error ->
                                logger.e("Failed to build the negentropy vector for ${negentropySynchronizeRequest.id}", error)
                                nostrRepository.negentropySynchronizeRequestProcessed(negentropySynchronizeRequest)
                            }
                            .getOrNull() ?: return@withLock

                        val negOpenCmd = NegOpenCmd(
                            subId = negentropySynchronizeRequest.uuid,
                            filter = Filter(
                                ids = negentropySynchronizeRequest.synchronizationFilter.ids?.toList(),
                                authors = negentropySynchronizeRequest.synchronizationFilter.authors?.toList(),
                                kinds = negentropySynchronizeRequest.synchronizationFilter.kinds?.toList(),
                                tags = negentropySynchronizeRequest.synchronizationFilter.tags,
                                tagsAll = negentropySynchronizeRequest.synchronizationFilter.tagsAll,
                                since = negentropySynchronizeRequest.synchronizationFilter.since?.epochSeconds,
                                until = negentropySynchronizeRequest.synchronizationFilter.until?.epochSeconds,
                                limit = null, // We don't do limits when performing negentropy...
                                search = negentropySynchronizeRequest.synchronizationFilter.search
                            ),
                            initialMessage = negentropy.initiate().toHexString()
                        )
                        logger.d("negOpenCmd: ${negOpenCmd.initialMessage}")

                        nostrRepository.negentropySynchronizeRequestProcessed(negentropySynchronizeRequest)

                        // The ids we hold, for deciding which of the relay's "you have what I
                        // don't" answers we can actually broadcast. A set, because reconcile() can
                        // report thousands of them and this used to be a list scan per id — and
                        // taken here rather than inside the coroutine below so that a long
                        // exchange does not keep every loaded event (content and all) alive for
                        // its whole life.
                        val localEventIds = events.mapTo(HashSet(events.size)) { it.id }

                        launch(Dispatchers.IO) {
                          subscriptionSlots.withPermit {
                            val negCloseCmd = NegCloseCmd(
                                subId = negentropySynchronizeRequest.uuid,
                            )
                            // Reconciliation is incremental: each round resolves some ranges into
                            // ids and splits the rest. Accumulate across rounds and act once, at
                            // the end — acting per round would queue a REQ per round for ids that
                            // later rounds are still discovering.
                            val needIds = LinkedHashSet<String>()
                            val sendIds = LinkedHashSet<String>()
                            var rounds = 0
                            var reconciliationOver = false

                            try {
                                withTimeout(SUBSCRIPTION_TIMEOUT) {
                                relaysSocketManager.negentropySync(
                                    negOpenCmd,
                                    negentropySynchronizeRequest.relayURL
                                ).transformWhile { message ->
                                    // The collector below runs inside this emit, so by the time
                                    // the predicate is read it has already recorded whether the
                                    // exchange finished. Without this the flow would only end on
                                    // EOSE/CLOSED/NEG-ERR, and a relay owes us none of those once
                                    // reconciliation completes.
                                    emit(message)
                                    !reconciliationOver
                                }.collect { nostrIncomingMessage ->
                                    when (nostrIncomingMessage) {
                                        is NostrIncomingMessage.EventMessage -> {
                                            launch(Dispatchers.IO) {
                                                logger.d("Import message: $nostrIncomingMessage")
                                                nostrIncomingMessage.nostrEvent?.let {
                                                    nostrRepository.saveNostrEvent(
                                                        nostrEvent = it,
                                                        negentropySynchronizeRequest,
                                                        synchronizationRelayURLs = listOf(negentropySynchronizeRequest.relayURL),
                                                        activeKeyPair = keyPair
                                                        // TODO: + Relays.eventPublishRelaySet.map { normalizedRelayUrl -> normalizedRelayUrl.url }
                                                    )
                                                }
                                            }
                                        }
                                        is NostrIncomingMessage.EventsMessage -> {
                                            logger.d("Import messages: $nostrIncomingMessage")

                                            nostrIncomingMessage.nostrEvents.forEach { nostrEvent ->
                                                nostrRepository.saveNostrEvent(
                                                    nostrEvent = nostrEvent,
                                                    negentropySynchronizeRequest,
                                                    synchronizationRelayURLs = listOf(negentropySynchronizeRequest.relayURL),
                                                    activeKeyPair = keyPair
                                                    // TODO: + Relays.eventPublishRelaySet.map { normalizedRelayUrl -> normalizedRelayUrl.url }
                                                )
                                            }
                                        }
                                        is NostrIncomingMessage.EoseMessage -> {
                                            logger.d("Sync request has been successfully processed (${negentropySynchronizeRequest.relayURL}): $nostrIncomingMessage")

                                            return@collect
                                        }
                                        is NostrIncomingMessage.NegentropyError -> {
                                            logger.d("Negentropy Error (need to synchronize like normal): ${nostrIncomingMessage.negentropyReason}")

                                            // TODO: Don't schedule a sync when on mobile internet.
                                            if (negentropySynchronizeRequest.purpose != "mlsMessages") {
                                                // Require negentropy for syncRequest...
                                                nostrRepository.queueSynchronizeNostrEvent(
                                                    listOf(
                                                        negentropySynchronizeRequest.toSynchronizeNostrEventRequest()
                                                    )
                                                )
                                            }
                                            return@collect
                                        }
                                        is NostrIncomingMessage.NegentropyMessage -> {
                                            rounds++
                                            logger.d("NegentropyMessage (round $rounds): ${nostrIncomingMessage.negentropyMessage}")

                                            val result = negentropy.reconcile(
                                                nostrIncomingMessage.negentropyMessage.hexToByteArray()
                                            )
                                            result.needIds.mapTo(needIds) { it.toHexString() }
                                            result.sendIds.mapTo(sendIds) { it.toHexString() }
                                            logger.d("Round $rounds: +${result.needIds.size} need, +${result.sendIds.size} send")

                                            val nextMessage = result.msg
                                            // A null message is the library saying "nothing left
                                            // to ask about" — that, not the arrival of the first
                                            // NEG-MSG, is where an exchange is over. The round cap
                                            // is a backstop against a peer that keeps splitting
                                            // ranges forever; reconciliation halves the search
                                            // space each round, so a healthy one is far shorter.
                                            if (nextMessage == null || rounds >= MAX_NEGENTROPY_ROUNDS) {
                                                if (nextMessage != null) {
                                                    logger.w("Negentropy with ${negentropySynchronizeRequest.relayURL} did not settle in $MAX_NEGENTROPY_ROUNDS rounds; using what reconciled so far")
                                                }
                                                reconciliationOver = true
                                                return@collect
                                            }

                                            relaysSocketManager.sendNegentropyMessage(
                                                NegMsgCmd(
                                                    subId = negentropySynchronizeRequest.uuid,
                                                    message = nextMessage.toHexString(),
                                                ),
                                                negentropySynchronizeRequest.relayURL
                                            )
                                            return@collect
                                        }
                                        is NostrIncomingMessage.ClosedMessage -> {
                                            // Relay refused or ended the NEG subscription (no
                                            // NIP-77 support, auth required, rate limit). No
                                            // NEG-MSG will follow, so fall back to a plain REQ
                                            // rather than leaving the request silently unserved.
                                            // (`return@collect` ends handling of this message,
                                            // matching the EOSE and NEG-ERR branches.)
                                            logger.w("Relay closed negentropy subscription (${negentropySynchronizeRequest.relayURL}): ${nostrIncomingMessage.message}")
                                            // Falling back to a plain REQ is right when the
                                            // relay cannot serve negentropy, and wrong when it
                                            // is telling us to ease off: answering back-pressure
                                            // by opening another subscription is what turned one
                                            // refusal into a flood of them.
                                            if (isRelayBackPressure(nostrIncomingMessage.message)) {
                                                logger.w("Relay ${negentropySynchronizeRequest.relayURL} is rate limiting; not retrying this request")
                                            } else if (negentropySynchronizeRequest.purpose != "mlsMessages") {
                                                nostrRepository.queueSynchronizeNostrEvent(
                                                    listOf(
                                                        negentropySynchronizeRequest.toSynchronizeNostrEventRequest()
                                                    )
                                                )
                                            }
                                            return@collect
                                        }
                                        is NostrIncomingMessage.NoticeMessage -> {
                                            logger.d("Notice message ${negentropySynchronizeRequest.relayURL}: $nostrIncomingMessage")
                                            // A NOTICE carries its text in `message`; it used to
                                            // be read out of `subscriptionId`, which NIP-01
                                            // NOTICEs do not have, so this fallback never fired.
                                            // Kept narrow on purpose: a NOTICE has no
                                            // subscription id, so it is delivered to EVERY live
                                            // collector on this socket — a loose match would fan
                                            // one notice out into a REQ fallback per collector.
                                            if (nostrIncomingMessage.message?.contains("negentropy disabled", ignoreCase = true) == true) {
                                                if (negentropySynchronizeRequest.purpose != "mlsMessages") {
                                                    nostrRepository.queueSynchronizeNostrEvent(
                                                        listOf(
                                                            negentropySynchronizeRequest.toSynchronizeNostrEventRequest()
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        else -> {
                                            logger.d("Unhandled message ${negentropySynchronizeRequest.relayURL}: $nostrIncomingMessage")
                                        }
                                    }
                                }
                                }
                                logger.d("Queried Sync")
                            } catch (e: Throwable) {
                                logger.e("Failed to sync", e)
                            } finally {
                                // Close on every exit. The EOSE and NEG-MSG branches used
                                // to do this by hand while CLOSED and NEG-ERR did not, so a
                                // subscription leaked every time a relay refused one —
                                // exactly the case that needs the slot back most.
                                withContext(NonCancellable) {
                                    runCatching {
                                        relaysSocketManager.closeNegentropySync(
                                            negCloseCmd,
                                            negentropySynchronizeRequest.relayURL
                                        )
                                    }
                                }
                            }

                            // Outside the try, and NonCancellable, so a session that timed out or
                            // was cancelled mid-exchange still acts on what it did reconcile
                            // instead of throwing the rounds it paid for away.
                            withContext(NonCancellable) {
                                runCatching {
                                    applyReconciliation(
                                        negentropySynchronizeRequest = negentropySynchronizeRequest,
                                        needIds = needIds,
                                        sendIds = sendIds,
                                        localEventIds = localEventIds,
                                    )
                                }.onFailure { logger.e("Failed to schedule reconciliation follow-ups", it) }
                            }
                          }
                        }

                        logger.i("After launch")
                    }
                    logger.i("Lock released")
                }
            }
        }
    }

    /**
     * Loads the local set into a sealed negentropy vector.
     *
     * `StorageVector` rejects an id that is not 32 bytes and, on seal, a duplicate item — either
     * throws out of the whole request — so the set is filtered and de-duplicated on the way in.
     * A row the vector cannot hold is a row this device cannot reconcile; dropping it costs one
     * event's worth of extra transfer, where letting it through costs the entire sync.
     */
    private fun List<com.machankura.dedun.compose.database.model.NostrEvent>.toNegentropy(): Negentropy {
        val storage = StorageVector()
        val seen = HashSet<String>(size)

        forEach { event ->
            if (event.id.length != NOSTR_EVENT_ID_HEX_LENGTH || !event.id.all { it.isHex() }) {
                logger.w("Skipping event with an id negentropy cannot index: ${event.id}")
                return@forEach
            }
            if (!seen.add(event.id)) return@forEach

            // Nostr timestamps — and therefore every timestamp a relay puts in its own
            // negentropy vector — are in SECONDS. Feeding milliseconds here sorted every local
            // item ~1000x past every remote one, so the fingerprint bounds could never match and
            // reconciliation degenerated into a full ID transfer.
            storage.insert(
                timestamp = event.createdAt.epochSeconds,
                idHex = event.id
            )
        }
        storage.seal()

        return Negentropy(storage)
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Turns a finished reconciliation into work: fetch what only the relay has, offer what only
     * we have.
     */
    private suspend fun applyReconciliation(
        negentropySynchronizeRequest: com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest,
        needIds: Set<String>,
        sendIds: Set<String>,
        localEventIds: Set<String>,
    ) {
        logger.d("Reconciled with ${negentropySynchronizeRequest.relayURL}: ${needIds.size} to fetch, ${sendIds.size} to offer")

        if (needIds.isNotEmpty()) {
            // One REQ per chunk. A first sync can need thousands of ids, and relays cap the
            // length of a filter's `ids` array — a single oversized REQ is answered with a
            // CLOSED (or silently truncated), which loses every id past the cap.
            nostrRepository.queueSynchronizeNostrEvent(
                needIds.chunked(MAX_IDS_PER_REQ).map { chunk ->
                    SynchronizeNostrEventRequest(
                        purpose = negentropySynchronizeRequest.purpose,
                        synchronizationFilters = arrayOf(
                            SynchronizationFilter(
                                ids = chunk.toTypedArray()
                            )
                        ),
                        relayURL = negentropySynchronizeRequest.relayURL,
                        level = negentropySynchronizeRequest.level,
                    )
                }
            )
        }

        // Only offer events we actually hold. `sendIds` is what the relay is missing relative to
        // our vector, so everything in it should be local — the guard is against a peer that
        // echoes ids we never sent.
        val broadcastNostrEventRequests = sendIds.filter { it in localEventIds }.map { sendId ->
            BroadcastNostrEventRequest(
                nostrEventId = sendId,
                relayURL = negentropySynchronizeRequest.relayURL
            )
        }
        logger.d("broadcastNostrEventRequests: ${broadcastNostrEventRequests.size}")

        if (broadcastNostrEventRequests.isNotEmpty()) {
            nostrRepository.rescheduleBroadcastNostrEventRequests(
                broadcastNostrEventRequests
            )
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun observePendingBroadcastNostrEventRequests(
        keyPair: KeyPair
    ): Unit = coroutineScope {
        logger.i { "observePendingBroadcastNostrEventRequests" }

        nostrRepository.observePendingBroadcastNostrEventRequests().distinctUntilChanged().collect { localBroadcastNostrEventRequest ->
                logger.d("localBroadcastNostrEventRequest: $localBroadcastNostrEventRequest")

                if (localBroadcastNostrEventRequest != null) {
                  guardPump("broadcast request ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.id}") {
                    nostrRepository.broadcastProcessed(
                        localBroadcastNostrEventRequest.broadcastNostrEventRequest
                    )

//                if (localBroadcastNostrEventRequest.nostrEvent.broadcastedAt == null) {
//                    scope.launch(Dispatchers.IO) {
//                        relaysSocketManager.publishEvent(
//                            localBroadcastNostrEventRequest.nostrEvent
//                        ).timeout(RelayPool.PUBLISH_TIMEOUT.milliseconds).catch {
//                            // Timeout...
//                        }.collect { nostrPublishResult ->
//                            if (nostrPublishResult.error != null) {
//                                logger.e("Error publishing note: $nostrPublishResult")
//                                nostrRepository.broadcastProcessed(
//                                    localBroadcastNostrEventRequest.broadcastNostrEventRequest,
//                                    "failed"
//                                )
//                            } else {
//                                logger.d("Nostr Publish Result: $nostrPublishResult")
//                                nostrRepository.broadcastProcessed(
//                                    localBroadcastNostrEventRequest.broadcastNostrEventRequest,
//                                    "published" // TODO: Might want to pass the nostrPublishResult.result
//                                )
//                            }
//                        }
//                    }
//                }

                    // The queue advances as soon as the row leaves "pending", so a backlog would
                    // otherwise fan out one publish coroutine (and one 30s timer) per row as fast
                    // as SQLite can commit. Bound it.
                    launch(Dispatchers.IO) {
                      publishSlots.withPermit {
                      // Every publish MUST reach a terminal status. The row was flipped to
                      // "processing" before we got here, and the navigation gate parks the user
                      // on "announcing your profile" while a broadcast is pending/processing —
                      // so a publish that neither completes nor throws would strand them there.
                      // `.catch` only sees errors from the flow, not from the suspend call that
                      // builds it, hence the outer try/catch and the outer timeout.
                      try {
                        withTimeout(PUBLISH_ATTEMPT_TIMEOUT) {
                        // Broadcast to the intended relay...
                        relaysSocketManager.publishEvent(
                            localBroadcastNostrEventRequest.nostrEvent,
                            setOf(
                                localBroadcastNostrEventRequest.broadcastNostrEventRequest.relayURL.toRelayDTO()
                            )
                        ).timeout(RelayPool.PUBLISH_TIMEOUT.milliseconds)
                            // We publish to exactly ONE relay per request, so exactly one result
                            // is expected. `take(1)` completes the collection on that result —
                            // without it the underlying MutableSharedFlow never completes, the
                            // timeout keeps running, and 30s after a SUCCESSFUL publish the catch
                            // below would overwrite "published" with "failed".
                            .take(1)
                            .catch { e ->
                                // The request was flipped to "processing" before we got here, so
                                // an empty catch left it stranded in that status with nothing to
                                // observe it. Record the failure; the next launch requeues it.
                                logger.e("Publish timed out/failed for ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.nostrEventId} @ ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.relayURL}", e)
                                nostrRepository.broadcastProcessed(
                                    localBroadcastNostrEventRequest.broadcastNostrEventRequest,
                                    "failed"
                                )
                            }.collect { nostrPublishResult ->
                            // A relay's answer decides the status, not merely whether
                            // there was an error: a refusal it will keep giving is
                            // "rejected" and is not retried, a `duplicate:` is the
                            // event being there already. See BroadcastStatus.
                            val status = BroadcastStatus.of(nostrPublishResult.error)
                            if (nostrPublishResult.error != null) {
                                logger.e("Error publishing note ($status): $nostrPublishResult")
                            } else {
                                logger.d("Nostr Publish Result: $nostrPublishResult")
                            }
                            nostrRepository.broadcastProcessed(
                                localBroadcastNostrEventRequest.broadcastNostrEventRequest,
                                status,
                                reason = nostrPublishResult.error?.message,
                            )
                        }
                        }
                      } catch (e: CancellationException) {
                        // A withTimeout expiry is a CancellationException too, but only the
                        // *outer* coroutine being cancelled should propagate.
                        if (currentCoroutineContext().isActive) {
                            logger.e("Publish did not complete for ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.nostrEventId} @ ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.relayURL}", e)
                            nostrRepository.broadcastProcessed(
                                localBroadcastNostrEventRequest.broadcastNostrEventRequest,
                                "failed"
                            )
                        } else {
                            throw e
                        }
                      } catch (e: Throwable) {
                        logger.e("Publish failed for ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.nostrEventId} @ ${localBroadcastNostrEventRequest.broadcastNostrEventRequest.relayURL}", e)
                        nostrRepository.broadcastProcessed(
                            localBroadcastNostrEventRequest.broadcastNostrEventRequest,
                            "failed"
                        )
                      }
                      }
                    }
                  }
                }
            }
    }
}