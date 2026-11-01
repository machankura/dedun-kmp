package com.machankura.dedun.compose.network.relays

import androidx.annotation.VisibleForTesting
import com.machankura.dedun.compose.network.dto.toRelayDTO
import com.machankura.dedun.compose.network.sockets.filterByEventId
import com.machankura.dedun.compose.network.sockets.filterBySubscriptionId
import com.machankura.dedun.compose.network.sockets.completeOnSubscriptionEnd
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CloseCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip77Negentropy.NegCloseCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegMsgCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegOpenCmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * As seen in Primal
 *
 * https://github.com/PrimalHQ/primal-android-app/blob/8a912053131764c39711cd0dc5012645498c9a4f/app/src/main/kotlin/net/primal/android/networking/relays/RelayPool.kt
 */
class RelayPool(
    private val nostrSocketClientFactory: com.machankura.dedun.compose.network.sockets.NostrSocketClientFactory,
    private val cachingImportRepository: com.machankura.dedun.compose.repository.CachingImportRepository,
    /**
     * Where this pool's own fire-and-forget work runs: status updates, the socket closes that
     * a relay-list change triggers, and the subscription replay a reconnect triggers.
     * Injectable so a test can drive that work deterministically rather than racing it.
     */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    val logger = Logger.withTag("RelayPool")

    companion object {
        const val PUBLISH_TIMEOUT = 30_000
    }

    val relays: MutableSet<com.machankura.dedun.compose.network.dto.RelayDTO> = mutableSetOf()

    private val relayMutex = Mutex()

    /**
     * The REQ text of every subscription still believed to be open, keyed by normalized
     * relay url and then by subscription id, so a socket that comes back can be handed
     * its subscriptions again.
     *
     * Only plain REQs live here. A negentropy exchange is stateful — NEG-OPEN carries a
     * fingerprint of the local set and each round depends on the last — so replaying one
     * mid-exchange would reconcile against a conversation the relay is no longer having.
     * An interrupted negentropy request is abandoned and re-queued instead.
     */
    private val retainedRequests = mutableMapOf<String, MutableMap<String, String>>()

    private val retainedRequestsMutex = Mutex()

    @VisibleForTesting
    var socketClients = setOf<com.machankura.dedun.compose.network.sockets.NostrSocketClient>()

    private val _relayPoolStatus = MutableStateFlow(mapOf<String, Boolean>())
    val relayPoolStatus = _relayPoolStatus.asStateFlow()
    private fun updateRelayStatus(url: String, connected: Boolean) =
        scope.launch {
            _relayPoolStatus.getAndUpdate {
                it.toMutableMap().apply { this[url] = connected }
            }
        }

    private val onSocketConnectionOpenedCallback: com.machankura.dedun.compose.network.sockets.SocketConnectionOpenedCallback = { url ->
        updateRelayStatus(url = url, connected = true)
    }

    private val onSocketConnectionClosedCallback: com.machankura.dedun.compose.network.sockets.SocketConnectionClosedCallback = { url, _ ->
        updateRelayStatus(url = url, connected = false)
    }

    private val onSocketConnectionReopenedCallback: com.machankura.dedun.compose.network.sockets.SocketConnectionReopenedCallback = { url ->
        scope.launch { replayRetainedRequests(url) }
    }

    fun changeRelays(relays: List<com.machankura.dedun.compose.network.dto.RelayDTO>) {
        val existingRelayUrls = socketClients.map { it.socketUrl }
        val newRelayUrls = relays.map { it.url }

        val toAddRelayUrls = newRelayUrls.filter { it !in existingRelayUrls }
        val toAddSocketClients = relays.filter { it.url in toAddRelayUrls }.mapAsNostrSocketClient()
        val toRemoveSocketClients = socketClients.filter { it.socketUrl !in newRelayUrls }

        val newSocketClients = socketClients.toMutableSet().apply {
            removeAll(toRemoveSocketClients)
            addAll(toAddSocketClients)
        }

        socketClients = newSocketClients
        toRemoveSocketClients.forEach { client ->
            updateRelayStatus(url = client.socketUrl, connected = false)
            scope.launch { client.close() }
        }
        forgetRetainedRequests(toRemoveSocketClients.map { it.socketUrl })
        this.relays.clear()
        this.relays.addAll(relays)
    }

    fun removeRelays(relays: Set<com.machankura.dedun.compose.network.dto.RelayDTO>) {
        val relayUrls = relays.map { it.url }

        val toRemoveSocketClients = socketClients.filter { it.socketUrl in relayUrls }

        val newSocketClients = socketClients.toMutableSet().apply {
            removeAll(toRemoveSocketClients)
        }

        socketClients = newSocketClients
        toRemoveSocketClients.forEach { client ->
            updateRelayStatus(url = client.socketUrl, connected = false)
            scope.launch { client.close() }
        }
        forgetRetainedRequests(toRemoveSocketClients.map { it.socketUrl })
        this.relays.removeAll(relays)
    }

    suspend fun addRelaysIfMissing(relays: Set<com.machankura.dedun.compose.network.dto.RelayDTO>) {
        relayMutex.withLock {
            val existingRelayUrls = socketClients.map { NormalizedRelayUrl(it.socketUrl).displayUrl() }
            val newRelayUrls = relays.map { it.url }

            val toAddRelayUrls = newRelayUrls.filter { NormalizedRelayUrl(it).displayUrl() !in existingRelayUrls }.toSet()
            val toAddSocketClients = relays.filter { it.url in toAddRelayUrls }.mapAsNostrSocketClient()
            if (toAddSocketClients.isNotEmpty()) {
                val newSocketClients = socketClients.toMutableSet().apply {
                    addAll(toAddSocketClients)
                }
                logger.d("newSocketClients: ${newSocketClients.map { it.socketUrl }}")

                socketClients = newSocketClients

                this.relays.addAll(relays)
            }
        }
    }

    fun closePool() {
        socketClients.forEach { client ->
            updateRelayStatus(url = client.socketUrl, connected = false)
            scope.launch { client.close() }
        }
        forgetRetainedRequests(socketClients.map { it.socketUrl })
        socketClients = emptySet()
        relays.clear()
    }

    fun hasRelays() = relays.isNotEmpty()

    /**
     * Tears every socket down and immediately builds it again.
     *
     * For coming back from the background, where trusting the connection is the mistake: a
     * socket that was open when the OS suspended the process reports itself connected on the
     * way back while being functionally dead, and the relay has long since dropped the
     * subscriptions it was carrying.
     *
     * Re-opening here rather than leaving it to the next send is deliberate — it is what makes
     * this a RE-connection, so the retained subscriptions are replayed and any collector still
     * attached from before the gap starts receiving again.
     */
    suspend fun reconnectAll() {
        socketClients.forEach { client ->
            updateRelayStatus(url = client.socketUrl, connected = false)
            runCatching { client.close() }
            runCatching { client.ensureSocketConnectionOrThrow() }
                .onFailure { logger.w(throwable = it) { "Could not re-open ${client.socketUrl}" } }
        }
    }

    suspend fun tryConnectingToRelay(url: String) {
        runCatching {
            socketClients.find { it.socketUrl == url }?.ensureSocketConnectionOrThrow()
        }
    }

    private fun socketClientFor(relayUrl: String): com.machankura.dedun.compose.network.sockets.NostrSocketClient? {
        val wanted = NormalizedRelayUrl(relayUrl).displayUrl()

        return socketClients.find { NormalizedRelayUrl(it.socketUrl).displayUrl() == wanted }
    }

    /**
     * Remembers a subscription so it can be re-sent if this relay's socket comes back,
     * and tells the socket it is now worth reconnecting.
     */
    private suspend fun retainRequest(relayUrl: String, subId: String, message: String) {
        val key = NormalizedRelayUrl(relayUrl).displayUrl()

        retainedRequestsMutex.withLock {
            retainedRequests.getOrPut(key) { mutableMapOf() }[subId] = message
        }
        socketClientFor(relayUrl)?.autoReconnect = true
    }

    /**
     * Forgets a subscription that has been closed. Once a relay is carrying none of ours,
     * its socket stops reconnecting on its own: keeping a socket alive for a relay we have
     * nothing open on is battery spent on nothing.
     */
    private suspend fun releaseRequest(relayUrl: String, subId: String) {
        val key = NormalizedRelayUrl(relayUrl).displayUrl()

        val stillWanted = retainedRequestsMutex.withLock {
            val forRelay = retainedRequests[key]
            forRelay?.remove(subId)
            if (forRelay.isNullOrEmpty()) {
                retainedRequests.remove(key)
                false
            } else {
                true
            }
        }

        if (!stillWanted) socketClientFor(relayUrl)?.autoReconnect = false
    }

    private fun forgetRetainedRequests(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return

        val keys = relayUrls.map { NormalizedRelayUrl(it).displayUrl() }
        scope.launch {
            retainedRequestsMutex.withLock {
                keys.forEach { retainedRequests.remove(it) }
            }
        }
    }

    /**
     * Hands a relay back the subscriptions it was serving before its socket dropped.
     *
     * Only ever called for a RE-connection. A relay answers a repeated REQ on the same
     * subscription id by replacing the filter, so this is a send rather than a
     * close-and-reopen, and the collector already attached to the socket's message flow
     * simply starts receiving again.
     */
    private suspend fun replayRetainedRequests(relayUrl: String) {
        val key = NormalizedRelayUrl(relayUrl).displayUrl()
        val messages = retainedRequestsMutex.withLock { retainedRequests[key]?.toMap() }

        if (messages.isNullOrEmpty()) return

        val nostrSocketClient = socketClientFor(relayUrl)
        if (nostrSocketClient == null) {
            logger.w("Cannot replay ${messages.size} subscription(s): $relayUrl has no socket")
            return
        }

        logger.i("Replaying ${messages.size} subscription(s) on $relayUrl: ${messages.keys}")
        messages.forEach { (subId, message) ->
            runCatching { nostrSocketClient.sendMESSAGE(message) }
                .onFailure { logger.w(throwable = it) { "Failed to replay $subId on $relayUrl" } }
        }
    }

    private fun List<com.machankura.dedun.compose.network.dto.RelayDTO>.mapAsNostrSocketClient() =
        this.map {
            nostrSocketClientFactory.create(
                wssUrl = it.url,
                onSocketConnectionOpened = onSocketConnectionOpenedCallback,
                onSocketConnectionClosed = onSocketConnectionClosedCallback,
                onSocketConnectionReopened = onSocketConnectionReopenedCallback,
            )
        }

    @Throws(com.machankura.dedun.compose.exceptions.NostrPublishException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun publishEvent(nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent, relays: Set<com.machankura.dedun.compose.network.dto.RelayDTO> = emptySet()): Flow<NostrPublishResult> {
        val relayUrls = relays.map { NormalizedRelayUrl(it.url).displayUrl() }

        return if (relayUrls.isEmpty()) {
            logger.w("Empty Relays")
            handlePublishEventToRelays(socketClients, nostrEvent)
        } else {
            logger.d("Add Relays: $relays")
            addRelaysIfMissing(relays)

            val filteredSocketClients = socketClients.filter { NormalizedRelayUrl(it.socketUrl).displayUrl() in relayUrls }
            logger.d("filteredSocketClients: ${filteredSocketClients.map { it.socketUrl }}")

            handlePublishEventToRelays(filteredSocketClients.toSet(), nostrEvent)
        }
    }

    suspend fun query(reqCommand: ReqCmd, relayUrl: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        addRelaysIfMissing(
            setOf(
                NormalizedRelayUrl(relayUrl).url.toRelayDTO()
            )
        )

        logger.d("socketClients: ${socketClients.map { it.socketUrl }}")
        val nostrSocketClient = socketClientFor(relayUrl)

        val filterRequest = OptimizedJsonMapper.toJson(reqCommand)

        if (nostrSocketClient == null) {
            throw com.machankura.dedun.compose.exceptions.NetworkException("$relayUrl is not connected")
        }

        // Retained BEFORE the send, so a socket that dies between here and the relay's
        // first answer still gets the REQ replayed when it comes back. Released by
        // closeQuery, which every pump calls from a NonCancellable finally.
        retainRequest(relayUrl = relayUrl, subId = reqCommand.subId, message = filterRequest)

        return coroutineScope {
            val eventFlow =  nostrSocketClient.queryAsFlow(reqCommand.subId)
            with(nostrSocketClient) {
                sendMESSAGE(filterRequest)
            }
            eventFlow
        }
    }

    /**
     * Opens a subscription that is meant to stay open, and returns a flow that never
     * completes on its own.
     *
     * The difference from [query] is the absence of `completeOnSubscriptionEnd`. For a
     * one-shot request EOSE is the end — it is what finishes the collector, frees the
     * subscription slot and triggers the CLOSE. For a live subscription EOSE is only the
     * boundary between the stored history and the live tail, and the relay owes us nothing
     * further to mark the end. The flow therefore ends when, and only when, the caller
     * stops collecting it.
     *
     * The returned flow is attached to the socket CLIENT's message flow, not to a session,
     * so it survives a drop: the socket reconnects, [replayRetainedRequests] re-sends this
     * REQ, and the same collector starts receiving again without anything being rebuilt.
     */
    suspend fun openLiveSubscription(reqCommand: ReqCmd, relayUrl: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        addRelaysIfMissing(
            setOf(
                NormalizedRelayUrl(relayUrl).url.toRelayDTO()
            )
        )

        val nostrSocketClient = socketClientFor(relayUrl)
            ?: throw com.machankura.dedun.compose.exceptions.NetworkException("$relayUrl is not connected")

        val filterRequest = OptimizedJsonMapper.toJson(reqCommand)

        // Attached before the send: the socket's message flow is buffered but not replayed,
        // so a collector subscribing after the first events arrived would miss them.
        val eventFlow = nostrSocketClient.incomingMessages.filterBySubscriptionId(id = reqCommand.subId)

        retainRequest(relayUrl = relayUrl, subId = reqCommand.subId, message = filterRequest)
        nostrSocketClient.sendMESSAGE(filterRequest)

        return eventFlow
    }

    /**
     * Narrows or widens a live subscription in place.
     *
     * A relay answers a repeated REQ on an existing subscription id by replacing that
     * subscription's filter, so this never closes anything: the collector opened by
     * [openLiveSubscription] keeps running and simply starts matching the new filter.
     * Re-retained too, so a reconnect replays the new filter rather than the old one.
     */
    suspend fun updateLiveSubscription(reqCommand: ReqCmd, relayUrl: String) {
        val filterRequest = OptimizedJsonMapper.toJson(reqCommand)

        // Retained first, and unconditionally. Recording the new filter is what matters: if
        // the send below cannot happen the reconnect replays this, whereas a throw here would
        // leave the OLD filter retained and a reconnect would restore a subscription the
        // caller has already moved on from.
        retainRequest(relayUrl = relayUrl, subId = reqCommand.subId, message = filterRequest)

        val nostrSocketClient = socketClientFor(relayUrl)
        if (nostrSocketClient == null) {
            logger.w("No socket for $relayUrl; ${reqCommand.subId} will be sent when one opens")
            return
        }

        runCatching { nostrSocketClient.sendMESSAGE(filterRequest) }
            .onFailure { logger.w(throwable = it) { "Failed to update ${reqCommand.subId} on $relayUrl; retained for reconnect" } }
    }

    /** Ends a live subscription: forgets the retained REQ and tells the relay to stop. */
    suspend fun closeLiveSubscription(subId: String, relayUrl: String) =
        closeQuery(CloseCmd(subId = subId), relayUrl)

    suspend fun negentropySync(negOpenCmd: NegOpenCmd, relayUrl: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        addRelaysIfMissing(
            setOf(
                NormalizedRelayUrl(relayUrl).url.toRelayDTO()
            )
        )

        logger.d("socketClients: ${socketClients.map { it.socketUrl }}")
        val nostrSocketClient = socketClientFor(relayUrl)

        val negentropySyncRequest = OptimizedJsonMapper.toJson(negOpenCmd)

        if (nostrSocketClient == null) {
            throw com.machankura.dedun.compose.exceptions.NetworkException("$relayUrl is not connected")
        }
        return coroutineScope {
            val eventFlow =  nostrSocketClient.queryAsFlow(negOpenCmd.subId)
            with(nostrSocketClient) {
                sendMESSAGE(negentropySyncRequest)
            }
            eventFlow
        }
    }

    /**
     * Sends the next round of a negentropy exchange. The relay answers on the same
     * subscription, so the caller keeps collecting the flow it already opened with
     * [negentropySync] rather than opening a new one.
     */
    suspend fun sendNegentropyMessage(negMsgCmd: NegMsgCmd, relayUrl: String) {
        val nostrSocketClient = socketClientFor(relayUrl)
            ?: throw com.machankura.dedun.compose.exceptions.NetworkException("$relayUrl is not connected")

        nostrSocketClient.sendMESSAGE(OptimizedJsonMapper.toJson(negMsgCmd))
    }

    suspend fun closeQuery(closeCmd: CloseCmd, relayUrl: String) {
        releaseRequest(relayUrl = relayUrl, subId = closeCmd.subId)

        addRelaysIfMissing(
            setOf(
                NormalizedRelayUrl(relayUrl).url.toRelayDTO()
            )
        )

        val nostrSocketClient = socketClientFor(relayUrl)

        val closeSubscription = OptimizedJsonMapper.toJson(closeCmd)

        if (nostrSocketClient == null) {
            throw com.machankura.dedun.compose.exceptions.NetworkException("$relayUrl is not connected")
        }
        coroutineScope {
            with(nostrSocketClient) {
                sendMESSAGE(closeSubscription)
            }
        }
    }

    suspend fun closeNegentropySync(negCloseCmd: NegCloseCmd, relayUrl: String) {
        addRelaysIfMissing(
            setOf(
                NormalizedRelayUrl(relayUrl).url.toRelayDTO()
            )
        )

        logger.d("socketClients: ${socketClients.map { it.socketUrl }}")
        val nostrSocketClient = socketClientFor(relayUrl)

        val closeNegentropySubscription = OptimizedJsonMapper.toJson(negCloseCmd)

        if (nostrSocketClient == null) {
            throw com.machankura.dedun.compose.exceptions.NetworkException("$relayUrl is not connected")
        }
        coroutineScope {
            with(nostrSocketClient) {
                sendMESSAGE(closeNegentropySubscription)
            }
        }
    }

    private fun Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage>.transformWhileEventsAreIncoming() =
        transformWhile {
            emit(it)
            it is com.machankura.dedun.compose.network.sockets.NostrIncomingMessage.EventMessage || it is com.machankura.dedun.compose.network.sockets.NostrIncomingMessage.EventsMessage
        }

    @OptIn(FlowPreview::class)
    private suspend fun com.machankura.dedun.compose.network.sockets.NostrSocketClient.queryAsFlow(subscriptionId: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        return this.incomingMessages
            .filterBySubscriptionId(id = subscriptionId)
            // The socket's incomingMessages is a hot flow, so a filtered view of it
            // never completes on its own. Ending it at EOSE/CLOSED/NEG-ERR is what
            // lets a caller's collector finish, its subscription slot be released,
            // and the relay-side subscription actually be closed.
            .completeOnSubscriptionEnd(id = subscriptionId)
    }

    @OptIn(FlowPreview::class)
    private suspend fun handlePublishEventToRelays(relayConnections: Set<com.machankura.dedun.compose.network.sockets.NostrSocketClient>, nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent): Flow<NostrPublishResult> {
        val responseFlow = MutableSharedFlow<NostrPublishResult>()
        relayConnections.forEach { nostrSocketClient ->
            scope.launch {
                with(nostrSocketClient) {
                    val sendEventResult = runCatching {
                        ensureSocketConnectionOrThrow()
                        sendEVENT(nostrEvent.toNostrJsonObject())
                        collectPublishResponse(eventId = nostrEvent.id)
                    }
                    sendEventResult.getOrNull()?.let {
                        // A relay that answers ["OK", id, false, reason] has REJECTED the event.
                        // Surfacing it as an error keeps the caller from recording a receipt for
                        // an event that was never stored, and preserves the relay's reason.
                        val rejection = if (!it.success) {
                            logger.w { "relay $socketUrl rejected ${nostrEvent.id}: ${it.message}" }
                            com.machankura.dedun.compose.exceptions.NostrPublishException(
                                message = it.message ?: "rejected by $socketUrl"
                            )
                        } else {
                            null
                        }
                        responseFlow.emit(
                            NostrPublishResult(
                                result = it,
                                error = rejection,
                                relayUrl = nostrSocketClient.socketUrl
                            )
                        )
                    }
                    sendEventResult.exceptionOrNull()?.let {
                        logger.w(throwable = it) { "sendEVENT failed to $socketUrl" }
                        responseFlow.emit(
                            NostrPublishResult(
                                error = it,
                                relayUrl = nostrSocketClient.socketUrl
                            )
                        )
                    }
                }
            }
        }

//        var responseCount = 0
//        responseFlow.timeout(PUBLISH_TIMEOUT.milliseconds)
//            .catch {
//                logger.e("Caught a nostr publish exception: $it")
//                throw NostrPublishException(cause = it)
//            }
//            .transform {
//                emit(it)
//                responseCount++
//                if (relayConnections.size == responseCount && !it.isSuccessful()) {
//                    throw NostrPublishException(cause = null)
//                }
//            }
//            .first { it.isSuccessful() }
        return responseFlow
    }

    @FlowPreview
    private suspend fun com.machankura.dedun.compose.network.sockets.NostrSocketClient.collectPublishResponse(eventId: String): com.machankura.dedun.compose.network.sockets.NostrIncomingMessage.OkMessage {
        return incomingMessages
            .filterByEventId(id = eventId)
            .filterIsInstance<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage.OkMessage>()
            .timeout(PUBLISH_TIMEOUT.milliseconds)
            .first()
    }

    private fun NostrPublishResult.isSuccessful(): Boolean {
        return result is com.machankura.dedun.compose.network.sockets.NostrIncomingMessage.OkMessage && result.success
    }
}
