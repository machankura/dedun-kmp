package com.machankura.dedun.compose.network.relays

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CloseCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip77Negentropy.NegOpenCmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.network.sockets.NostrSocketClient
import com.machankura.dedun.compose.network.sockets.NostrSocketClientFactory
import com.machankura.dedun.compose.network.sockets.SocketConnectionClosedCallback
import com.machankura.dedun.compose.network.sockets.SocketConnectionOpenedCallback
import com.machankura.dedun.compose.network.sockets.SocketConnectionReopenedCallback
import com.machankura.dedun.compose.repository.CachingImportRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pool's half of surviving a dropped socket.
 *
 * A subscription that outlives its socket only works if the pool remembers what that socket was
 * carrying and hands it back on reconnect. None of that is observable from the outside — no
 * return value changes, nothing throws — so a regression here looks like "messages stopped
 * arriving after a tunnel", hours later, on someone else's phone.
 *
 * The other half is that a relay answers a repeated REQ on an existing subscription id by
 * replacing that subscription's filter, which is what makes replay a send rather than a
 * close-and-reopen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolSubscriptionTest {

    private val relayUrl = "wss://relay.example.com"
    private val otherRelayUrl = "wss://other.example.com"

    private fun req(subId: String, kind: Int = 1) =
        ReqCmd(subId = subId, filters = listOf(Filter(kinds = listOf(kind))))

    @Test
    fun `a query is retained and replayed when its socket comes back`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.query(req("sub-1"), relayUrl)

        val socket = factory.only()
        assertEquals(1, socket.sent.size, "the REQ should have gone out once")
        assertTrue(socket.autoReconnect, "a relay carrying a subscription is worth reconnecting")

        socket.sent.clear()
        factory.reopen(relayUrl)

        assertEquals(1, socket.sent.size, "the REQ should have been replayed")
        assertTrue(socket.sent.single().contains("sub-1"))
    }

    @Test
    fun `a closed query is forgotten and the socket stops reconnecting for it`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.query(req("sub-1"), relayUrl)
        pool.closeQuery(CloseCmd(subId = "sub-1"), relayUrl)

        val socket = factory.only()
        assertFalse(socket.autoReconnect, "nothing is open on this relay any more")

        socket.sent.clear()
        factory.reopen(relayUrl)

        assertTrue(socket.sent.isEmpty(), "a closed subscription must not come back on reconnect")
    }

    /**
     * Closing one of two must not drop the other — the bug this guards is a shared socket
     * quietly losing its remaining subscription because a sibling finished first.
     */
    @Test
    fun `closing one subscription leaves the rest of that relay's alone`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.query(req("sub-1"), relayUrl)
        pool.query(req("sub-2", kind = 7), relayUrl)
        pool.closeQuery(CloseCmd(subId = "sub-1"), relayUrl)

        val socket = factory.only()
        assertTrue(socket.autoReconnect, "sub-2 is still open")

        socket.sent.clear()
        factory.reopen(relayUrl)

        assertEquals(1, socket.sent.size)
        assertTrue(socket.sent.single().contains("sub-2"))
    }

    /**
     * Negentropy is stateful: NEG-OPEN carries a fingerprint of the local set and every round
     * depends on the last. Replaying one mid-exchange would reconcile against a conversation
     * the relay is no longer having, so an interrupted exchange is abandoned and re-queued
     * instead.
     */
    @Test
    fun `a negentropy exchange is never replayed`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.negentropySync(
            NegOpenCmd(subId = "neg-1", filter = Filter(kinds = listOf(1)), initialMessage = "6100"),
            relayUrl,
        )

        val socket = factory.only()
        assertEquals(1, socket.sent.size, "NEG-OPEN still goes out")

        socket.sent.clear()
        factory.reopen(relayUrl)

        assertTrue(socket.sent.isEmpty(), "a half-finished reconciliation must not be resumed")
    }

    @Test
    fun `a live subscription is retained like any other`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.openLiveSubscription(req("live-giftwrap"), relayUrl)

        val socket = factory.only()
        assertTrue(socket.autoReconnect)

        socket.sent.clear()
        factory.reopen(relayUrl)

        assertTrue(socket.sent.single().contains("live-giftwrap"))
    }

    /**
     * Widening a live subscription has to replace what gets replayed, not just what is on the
     * wire now. Retaining the old filter would mean a reconnect quietly restored a
     * subscription the caller had already moved on from — a group you just joined going
     * silent the first time you walked through a tunnel.
     */
    @Test
    fun `updating a live subscription replaces what a reconnect replays`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.openLiveSubscription(req("live-groups-0", kind = 445), relayUrl)
        pool.updateLiveSubscription(
            ReqCmd(
                subId = "live-groups-0",
                filters = listOf(Filter(kinds = listOf(445), tags = mapOf("h" to listOf("group-b")))),
            ),
            relayUrl,
        )

        val socket = factory.only()
        socket.sent.clear()
        factory.reopen(relayUrl)

        val replayed = socket.sent.single()
        assertTrue(replayed.contains("group-b"), "expected the current filter, got: $replayed")
    }

    /**
     * An update that cannot be sent must still be recorded. Throwing instead would leave the
     * previous filter retained, which is the one outcome worse than not sending at all.
     */
    @Test
    fun `an update whose send fails is still retained for the reconnect`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.openLiveSubscription(req("live-groups-0", kind = 445), relayUrl)

        val socket = factory.only()
        socket.failSends = true
        pool.updateLiveSubscription(
            ReqCmd(
                subId = "live-groups-0",
                filters = listOf(Filter(kinds = listOf(445), tags = mapOf("h" to listOf("group-b")))),
            ),
            relayUrl,
        )

        socket.failSends = false
        socket.sent.clear()
        factory.reopen(relayUrl)

        assertTrue(socket.sent.single().contains("group-b"))
    }

    @Test
    fun `dropping a relay forgets what it was carrying`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.query(req("sub-1"), relayUrl)
        pool.changeRelays(listOf(RelayDTO(url = otherRelayUrl, read = true, write = true)))

        val dropped = factory.forUrl(relayUrl)
        dropped.sent.clear()
        factory.reopen(relayUrl)

        assertTrue(dropped.sent.isEmpty(), "a relay we removed should not be re-subscribed")
    }

    @Test
    fun `closing the pool forgets everything`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.query(req("sub-1"), relayUrl)
        pool.closePool()

        val socket = factory.only()
        socket.sent.clear()
        factory.reopen(relayUrl)

        assertTrue(socket.sent.isEmpty())
    }

    /** Retention is per relay: one relay's reconnect must not re-send another's REQ. */
    @Test
    fun `replay is scoped to the relay that reconnected`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        pool.query(req("sub-here"), relayUrl)
        pool.query(req("sub-there"), otherRelayUrl)

        factory.forUrl(relayUrl).sent.clear()
        factory.forUrl(otherRelayUrl).sent.clear()

        factory.reopen(relayUrl)

        assertTrue(factory.forUrl(relayUrl).sent.single().contains("sub-here"))
        assertTrue(factory.forUrl(otherRelayUrl).sent.isEmpty())
    }

    /**
     * The semantic the whole change rests on. For a one-shot request EOSE is the end; for a
     * live one it is only the boundary between the history a relay had stored and the tail it
     * will now stream. Ending there is precisely what made every chat sync a poll.
     */
    @Test
    fun `a live subscription keeps delivering after EOSE`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        val received = mutableListOf<NostrIncomingMessage>()
        val flow = pool.openLiveSubscription(req("live-giftwrap"), relayUrl)
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received += it }
        }

        val socket = factory.only()
        socket.deliver(NostrIncomingMessage.EoseMessage(subscriptionId = "live-giftwrap"))
        socket.deliver(NostrIncomingMessage.EventMessage(subscriptionId = "live-giftwrap"))

        assertEquals(2, received.size, "the event after EOSE should still have arrived")
        assertTrue(collecting.isActive, "a live subscription ends when its collector stops, not at EOSE")

        collecting.cancel()
    }

    /** The contrast, so the two rules cannot silently converge. */
    @Test
    fun `a one-shot query still ends at EOSE`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        val received = mutableListOf<NostrIncomingMessage>()
        val flow = pool.query(req("sub-1"), relayUrl)
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received += it }
        }

        val socket = factory.only()
        socket.deliver(NostrIncomingMessage.EoseMessage(subscriptionId = "sub-1"))
        socket.deliver(NostrIncomingMessage.EventMessage(subscriptionId = "sub-1"))

        assertEquals(1, received.size, "nothing should arrive after the EOSE that ended it")
        assertFalse(collecting.isActive, "the collector should have completed")
    }

    /**
     * A NOTICE carries no subscription id, so the socket hands it to every collector. It is
     * admitted deliberately — it is the only signal some relays give for "negentropy disabled"
     * — but it must stay advisory, never terminal, or one relay's complaint would tear down
     * every unrelated subscription on that socket.
     */
    @Test
    fun `a NOTICE reaches a live subscription without ending it`() = runTest {
        val factory = FakeSocketClientFactory()
        val pool = pool(factory)

        val received = mutableListOf<NostrIncomingMessage>()
        val flow = pool.openLiveSubscription(req("live-giftwrap"), relayUrl)
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received += it }
        }

        factory.only().deliver(NostrIncomingMessage.NoticeMessage(message = "restricted: slow"))

        assertEquals(1, received.size)
        assertTrue(collecting.isActive)

        collecting.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.pool(factory: FakeSocketClientFactory) =
        RelayPool(
            nostrSocketClientFactory = factory,
            cachingImportRepository = CachingImportRepository.NO_OP_CACHING_IMPORT_REPOSITORY,
            // Unconfined so the pool's launched work — status updates, and the replay a
            // reconnect triggers — has run by the time the call that started it returns.
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
}

private class FakeSocketClientFactory : NostrSocketClientFactory {
    private val clients = mutableMapOf<String, FakeNostrSocketClient>()
    private val reopenCallbacks = mutableMapOf<String, SocketConnectionReopenedCallback?>()

    override fun create(
        wssUrl: String,
        incomingCompressionEnabled: Boolean,
        onSocketConnectionOpened: SocketConnectionOpenedCallback?,
        onSocketConnectionClosed: SocketConnectionClosedCallback?,
        onSocketConnectionReopened: SocketConnectionReopenedCallback?,
    ): NostrSocketClient {
        reopenCallbacks[wssUrl] = onSocketConnectionReopened

        return FakeNostrSocketClient(wssUrl).also { clients[wssUrl] = it }
    }

    fun only(): FakeNostrSocketClient = clients.values.single()

    fun forUrl(url: String): FakeNostrSocketClient = clients.getValue(url)

    /** Stands in for a socket that dropped and re-established its session. */
    fun reopen(url: String) {
        reopenCallbacks.getValue(url)?.invoke(url)
    }
}

private class FakeNostrSocketClient(override val socketUrl: String) : NostrSocketClient {
    val sent = mutableListOf<String>()
    var failSends = false

    private val _incomingMessages = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<NostrIncomingMessage> = _incomingMessages.asSharedFlow()

    override var autoReconnect: Boolean = false

    override suspend fun close() = Unit

    override suspend fun ensureSocketConnectionOrThrow() = Unit

    override suspend fun sendMESSAGE(text: String, ensureSessionBeforeSend: Boolean) {
        if (failSends) throw IllegalStateException("socket is down")
        sent += text
    }

    override suspend fun sendAUTH(signedEvent: JsonObject) = Unit

    override suspend fun sendCLOSE(subscriptionId: String) = Unit

    override suspend fun sendCOUNT(data: JsonObject): String = "unused"

    override suspend fun sendEVENT(signedEvent: JsonObject) = Unit

    override suspend fun sendREQ(subscriptionId: String, data: JsonObject) = Unit

    /** Stands in for a message arriving on the wire. */
    suspend fun deliver(message: NostrIncomingMessage) {
        _incomingMessages.emit(message)
    }
}
