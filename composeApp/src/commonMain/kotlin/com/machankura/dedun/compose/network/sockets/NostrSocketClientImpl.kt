package com.machankura.dedun.compose.network.sockets

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.Buffer
import okio.GzipSink
import okio.Inflater
import okio.InflaterSource
import okio.buffer
import okio.use
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class NostrSocketClientImpl(
    wssUrl: String,
    private val httpClient: HttpClient,
    private val incomingCompressionEnabled: Boolean = false,
    private val onSocketConnectionOpened: SocketConnectionOpenedCallback? = null,
    private val onSocketConnectionClosed: SocketConnectionClosedCallback? = null,
    private val onSocketConnectionReopened: SocketConnectionReopenedCallback? = null,
) : NostrSocketClient {

    val logger = Logger.withTag("NostrSocketClientImpl")

    companion object {
        /** Wait before the first reconnect attempt; doubles per failure up to [MAX_RECONNECT_DELAY]. */
        private val INITIAL_RECONNECT_DELAY = 1.seconds

        private val MAX_RECONNECT_DELAY = 60.seconds

        /** Fraction of the delay added at random. See [reconnectDelay]. */
        private const val RECONNECT_JITTER = 0.25

        /**
         * How far a collector may lag before its socket's reader is made to wait for it.
         * Deep enough to absorb the burst a relay sends between a REQ and its EOSE while a
         * collector writes each event to SQLite; not so deep that a genuinely stuck
         * collector is invisible.
         */
        private const val INBOUND_BUFFER = 256
    }

    /** Outcome of a connect attempt, so the caller can tell a first connect from a repair. */
    private enum class ConnectOutcome { ALREADY_CONNECTED, OPENED, REOPENED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wsMutex = Mutex()
    private var wsSession: WebSocketSession? = null
    private var wsReceiverJob: Job? = null
    private var reconnectJob: Job? = null

    /** Consecutive failed reconnect attempts; reset the moment a session is established. */
    private var reconnectAttempts = 0

    /**
     * True once a session has been established, so the next one is a RE-connection and
     * the pool owes it a subscription replay.
     */
    private var hasConnected = false

    /**
     * Set by [close], cleared by the next caller-driven connect. A socket the client
     * deliberately closed must stay closed — otherwise `closePool` would be answered by
     * every socket in it politely reconnecting.
     */
    @Volatile
    private var closedByClient = false

    @Volatile
    override var autoReconnect: Boolean = false

    /**
     * Buffered on purpose. This used to be a rendezvous flow emitted into from a coroutine
     * launched per message, which had two consequences a short-lived request/response
     * collector never noticed and a permanent one would: messages reached collectors in
     * whatever order those coroutines were scheduled, and an emit with every collector busy
     * blocked on the slowest of them.
     *
     * With a buffer, a collector may fall [INBOUND_BUFFER] messages behind before it slows
     * the socket reader down, and the reader emits in wire order.
     */
    private val _incomingMessages = MutableSharedFlow<NostrIncomingMessage>(
        extraBufferCapacity = INBOUND_BUFFER,
    )
    override val incomingMessages = _incomingMessages.asSharedFlow()

    override val socketUrl = wssUrl.cleanWebSocketUrl()

    override suspend fun ensureSocketConnectionOrThrow() {
        if (isSessionActive()) return

        val outcome = wsMutex.withLock {
            // Asking for the socket is asking for it to stay up: clear the flag close()
            // set so a drop after this point reconnects again.
            closedByClient = false

            if (isSessionActive()) {
                ConnectOutcome.ALREADY_CONNECTED
            } else {
                wsSession = openSession()
                // Assigned BEFORE the post-connect handshake below, because sendMESSAGE
                // writes to `wsSession` rather than to the session it was handed — the
                // compression REQ used to go to the previous (usually null) session and
                // was silently dropped.
                if (incomingCompressionEnabled) {
                    val id = Uuid.generateV4().toHexDashString()
                    sendMESSAGE(
                        text = """["REQ","$id",{"cache":["set_primal_protocol",{"compression":"zlib"}]}]""",
                        ensureSessionBeforeSend = false,
                    )
                }

                val reopened = hasConnected
                hasConnected = true
                reconnectAttempts = 0
                if (reopened) ConnectOutcome.REOPENED else ConnectOutcome.OPENED
            }
        }

        if (outcome == ConnectOutcome.ALREADY_CONNECTED) return

        // Fired outside the lock. A callback that turns around and sends on this socket
        // would otherwise queue behind a mutex the caller still holds.
        onSocketConnectionOpened?.invoke(socketUrl)
        if (outcome == ConnectOutcome.REOPENED) onSocketConnectionReopened?.invoke(socketUrl)
    }

    private fun isSessionActive() = wsSession?.isActive == true

    /** Opens a session and starts its receiver. Call with [wsMutex] held. */
    private suspend fun openSession(): WebSocketSession {
        return try {
            httpClient.webSocketSession(urlString = socketUrl).apply { launchWebSocketReceiver() }
        } catch (error: Exception) {
            logger.w("NostrSocketClient::openSession($socketUrl) failed.", error)
            wsReceiverJob?.cancel()
            wsReceiverJob = null
            wsSession = null
            onSocketConnectionClosed?.invoke(socketUrl, error)
            scheduleReconnect()
            throw com.machankura.dedun.compose.exceptions.NetworkException(cause = error)
        }
    }

    private fun WebSocketSession.launchWebSocketReceiver() {
        wsReceiverJob?.cancel()
        wsReceiverJob = scope.launch {
            receiveSocketMessages()
        }
    }

    private suspend fun WebSocketSession.receiveSocketMessages() {
        var failure: Throwable? = null
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        logLargeText(text = text, url = socketUrl, incoming = true)
                        emitIncomingMessage(text = text)
                    }

                    is Frame.Binary -> {
                        val decompressedMessage = decompressMessage(frame.data)
                        logLargeText(text = decompressedMessage, url = socketUrl, incoming = true)
                        emitIncomingMessage(text = decompressedMessage)
                    }

                    is Frame.Close -> {
                        val closeReason = frame.readReason()
                        logger.w { "WS $socketUrl closed. [${closeReason?.code}, ${closeReason?.message}]" }
                        // Leave the teardown to onSessionLost below. The relay closing us
                        // is not the client closing us, so this still earns a reconnect.
                        break
                    }

                    else -> Unit
                }
            }
        } catch (error: CancellationException) {
            logger.w("NostrSocketClient::receiveSocketMessages() on $socketUrl cancelled.")
            throw error
        } catch (error: Exception) {
            logger.w("NostrSocketClient::receiveSocketMessages() on $socketUrl failed.", error)
            failure = error
        }

        onSessionLost(session = this, error = failure)
    }

    /**
     * The single exit point for a session that ended without the client asking. Clears the
     * session, tells the pool, and arms the reconnect.
     *
     * NonCancellable because the receiver job is cancelled as part of a replacement connect,
     * and a cancelled cleanup would leave `wsSession` pointing at a dead socket.
     */
    private suspend fun onSessionLost(session: WebSocketSession, error: Throwable?) =
        withContext(NonCancellable) {
            val wasCurrent = wsMutex.withLock {
                // Identity check, not a null check: a reconnect may already have installed a
                // newer session, and clearing that one would drop a healthy socket.
                if (wsSession !== session) {
                    false
                } else {
                    wsSession = null
                    true
                }
            }

            if (!wasCurrent) return@withContext

            runCatching {
                session.close(
                    reason = CloseReason(
                        code = CloseReason.Codes.NORMAL,
                        message = "Session ended.",
                    ),
                )
            }
            onSocketConnectionClosed?.invoke(socketUrl, error)
            scheduleReconnect()
        }

    /**
     * Re-opens a dropped socket with exponential backoff, for as long as the pool says it
     * still wants one. Nothing in this app used to reconnect at all: a socket that failed
     * stayed failed, and the only reason that was survivable is that every subscription was
     * short and the next queued request opened a fresh socket on its way out.
     */
    private fun scheduleReconnect() {
        if (!autoReconnect || closedByClient) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            while (isActive && autoReconnect && !closedByClient) {
                val attempt = ++reconnectAttempts
                val wait = reconnectDelay(
                    attempt = attempt,
                    initialDelay = INITIAL_RECONNECT_DELAY,
                    maxDelay = MAX_RECONNECT_DELAY,
                    jitterFraction = RECONNECT_JITTER,
                )
                logger.i { "Reconnecting to $socketUrl in $wait (attempt $attempt)" }
                delay(wait)

                if (!autoReconnect || closedByClient) return@launch

                val reconnected = runCatching { ensureSocketConnectionOrThrow() }
                if (reconnected.isSuccess) {
                    logger.i { "Reconnected to $socketUrl after $attempt attempt(s)" }
                    return@launch
                }
            }
        }
    }

    override suspend fun close() {
        val session = wsMutex.withLock {
            closedByClient = true
            val current = wsSession
            wsSession = null
            wsReceiverJob?.cancel()
            wsReceiverJob = null
            current
        }

        reconnectJob?.cancel()
        reconnectJob = null

        runCatching {
            session?.close(
                reason = CloseReason(
                    code = CloseReason.Codes.NORMAL,
                    message = "Closed by client.",
                ),
            )
        }
    }

    /**
     * Emits inline, on the reader, so messages reach collectors in the order the relay sent
     * them. That replaces a 75ms delay this used to sleep before every EOSE — a way of
     * hoping the events that preceded it had already been delivered by their own coroutines.
     * Ordering is now a property rather than a race that usually resolved in time.
     */
    private suspend fun emitIncomingMessage(text: String) {
        text.parseIncomingMessage()?.let { _incomingMessages.emit(value = it) }
    }

    override suspend fun sendMESSAGE(text: String, ensureSessionBeforeSend: Boolean) {
        if (ensureSessionBeforeSend) {
            ensureSocketConnectionOrThrow()
        }
        logLargeText(text = text, url = socketUrl, incoming = false)
        val session = wsSession
        if (session == null) {
            // Used to be a silent `?.` no-op, which is how a dropped socket could swallow
            // a send and leave the caller waiting on an answer to a message never sent.
            logger.w { "Dropping a send to $socketUrl: no session" }
            return
        }
        session.send(Frame.Text(text = text))
    }

    override suspend fun sendREQ(subscriptionId: String, data: JsonObject) {
        val reqMessage = data.buildNostrREQMessage(subscriptionId)
        return sendMESSAGE(text = reqMessage)
    }

    override suspend fun sendCOUNT(data: JsonObject): String {
        val subscriptionId: String = Uuid.generateV4().toHexDashString()
        val reqMessage = data.buildNostrCOUNTMessage(subscriptionId)
        sendMESSAGE(text = reqMessage)
        return subscriptionId
    }

    override suspend fun sendCLOSE(subscriptionId: String) = sendMESSAGE(text = subscriptionId.buildNostrCLOSEMessage())

    override suspend fun sendEVENT(signedEvent: JsonObject) = sendMESSAGE(text = signedEvent.buildNostrEVENTMessage())

    override suspend fun sendAUTH(signedEvent: JsonObject) = sendMESSAGE(text = signedEvent.buildNostrAUTHMessage())

    private fun logLargeText(
        text: String,
        url: String,
        incoming: Boolean,
    ) {
        val chunks = text.chunked(size = 3_500)
        val chunksCount = chunks.size
        chunks.forEachIndexed { index, chunk ->
            val prefix = if (incoming) "<--" else "-->"
            val suffix = if (index == chunksCount - 1) "[$url]" else ""
            logger.d(
                "$prefix $chunk $suffix",
            )
        }
    }

    @Suppress("unused")
    private fun compressMessage(message: String): ByteArray {
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { sink ->
            sink.writeUtf8(message)
            sink.flush() // Ensure all data is written
        }
        return buffer.readByteArray()
    }

    private fun decompressMessage(compressedMessage: ByteArray): String {
        val buffer = Buffer().write(compressedMessage)
        InflaterSource(buffer, Inflater(false)).buffer().use { source ->
            return source.readUtf8()
        }
    }

    private fun String.cleanWebSocketUrl(): String {
        return replace("https://", "wss://", ignoreCase = true)
            .replace("http://", "ws://", ignoreCase = true)
            .let { if (it.endsWith("/")) it.dropLast(1) else it }
    }
}
