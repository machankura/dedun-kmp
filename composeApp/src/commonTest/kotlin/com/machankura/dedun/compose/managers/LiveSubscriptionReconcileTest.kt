package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.GIFT_WRAP_SUB_ID
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.groupSubId
import com.machankura.dedun.compose.network.relays.LiveSubscriptionTransport
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The requirement this whole change exists to meet: the group subscription has to follow group
 * membership, without anyone remembering to call a subscribe function when a group is joined.
 *
 * The failure this guards against is entirely silent. A group whose id never makes it into the
 * `#h` filter is not an error anywhere — it is a conversation that simply never delivers, on a
 * screen that looks exactly like an empty one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSubscriptionReconcileTest {

    private val keyPair = KeyPair()
    private val me = keyPair.pubKey.toHexString()

    @Test
    fun `opens a group subscription for the groups we are in`() = runTest {
        val transport = FakeTransport()
        val rooms = MutableStateFlow(listOf(room("group-a"), room("group-b")))
        val running = start(transport, rooms)

        settle()

        val opened = transport.opened.single { it.reqCommand.subId == groupSubId(0) }
        assertEquals(
            mapOf("h" to listOf("group-a", "group-b")),
            opened.reqCommand.filters.single().tags,
        )

        running.cancelAndJoin()
    }

    /**
     * The loop that closes: a Welcome arrives on the gift wrap subscription, a ChatRoom row is
     * written, the room list re-emits, and the filter widens — with no chat screen involved.
     *
     * It has to widen *in place*. Closing and re-opening would drop the live tail of every
     * group already in that chunk for as long as the round trip takes, so joining one group
     * would briefly stop delivery on all the others.
     */
    @Test
    fun `joining a group widens the filter without reopening the subscription`() = runTest {
        val transport = FakeTransport()
        val rooms = MutableStateFlow(listOf(room("group-a")))
        val running = start(transport, rooms)
        settle()

        val opensBefore = transport.opened.count { it.reqCommand.subId == groupSubId(0) }

        rooms.value = listOf(room("group-a"), room("group-new"))
        settle()

        val updated = transport.updated.last { it.reqCommand.subId == groupSubId(0) }
        assertEquals(
            mapOf("h" to listOf("group-a", "group-new")),
            updated.reqCommand.filters.single().tags,
        )
        assertEquals(
            opensBefore,
            transport.opened.count { it.reqCommand.subId == groupSubId(0) },
            "widening must not re-open the subscription",
        )

        running.cancelAndJoin()
    }

    @Test
    fun `leaving a group drops it from the filter`() = runTest {
        val transport = FakeTransport()
        val rooms = MutableStateFlow(listOf(room("group-a"), room("group-b")))
        val running = start(transport, rooms)
        settle()

        rooms.value = listOf(room("group-a"), room("group-b", leftGroupAt = Instant.fromEpochSeconds(1)))
        settle()

        val updated = transport.updated.last { it.reqCommand.subId == groupSubId(0) }
        assertEquals(mapOf("h" to listOf("group-a")), updated.reqCommand.filters.single().tags)

        running.cancelAndJoin()
    }

    @Test
    fun `leaving every group closes the subscription`() = runTest {
        val transport = FakeTransport()
        val rooms = MutableStateFlow(listOf(room("group-a")))
        val running = start(transport, rooms)
        settle()

        rooms.value = emptyList()
        settle()

        assertTrue(transport.closed.any { it.subId == groupSubId(0) })

        running.cancelAndJoin()
    }

    /**
     * Membership churn inside the debounce window must collapse. Joining a group writes the
     * room, its participants and placeholder profiles in quick succession, each of which
     * re-emits the list — so without this a single join re-sends the filter several times.
     */
    @Test
    fun `rapid membership changes collapse into one update`() = runTest {
        val transport = FakeTransport()
        val rooms = MutableStateFlow(listOf(room("group-a")))
        val running = start(transport, rooms)
        settle()

        val updatesBefore = transport.updated.size

        rooms.value = listOf(room("group-a"), room("group-b"))
        advanceTimeBy(100)
        rooms.value = listOf(room("group-a"), room("group-b"), room("group-c"))
        advanceTimeBy(100)
        rooms.value = listOf(room("group-a"), room("group-b"), room("group-c"), room("group-d"))
        settle()

        assertEquals(1, transport.updated.size - updatesBefore, "expected one update, not three")

        running.cancelAndJoin()
    }

    @Test
    fun `a NIP-17 room never becomes a group subscription`() = runTest {
        val transport = FakeTransport()
        val rooms = MutableStateFlow(listOf(room("dm-room", mlsGroupState = null)))
        val running = start(transport, rooms)
        settle()

        assertTrue(transport.opened.none { it.reqCommand.subId.startsWith("live-groups-") })

        running.cancelAndJoin()
    }

    // --- what arrives on a subscription -------------------------------------------------

    @Test
    fun `events are stored against the relay they arrived from`() = runTest {
        val transport = FakeTransport()
        val nostr = RecordingNostrRepository()
        val running = start(transport, MutableStateFlow(emptyList()), nostr)
        settle()

        val subscription = transport.opened.single { it.reqCommand.subId == GIFT_WRAP_SUB_ID }
        subscription.messages.emit(
            NostrIncomingMessage.EventMessage(subscriptionId = GIFT_WRAP_SUB_ID, nostrEvent = event("ev-1"))
        )
        settle()

        val saved = nostr.saved.single()
        assertEquals("ev-1", saved.first)
        assertEquals(subscription.relayUrl, saved.second)

        running.cancelAndJoin()
    }

    /** EOSE is a marker, not an end: what follows it is the whole point. */
    @Test
    fun `an event after EOSE is still stored`() = runTest {
        val transport = FakeTransport()
        val nostr = RecordingNostrRepository()
        val running = start(transport, MutableStateFlow(emptyList()), nostr)
        settle()

        val subscription = transport.opened.single { it.reqCommand.subId == GIFT_WRAP_SUB_ID }
        subscription.messages.emit(NostrIncomingMessage.EoseMessage(subscriptionId = GIFT_WRAP_SUB_ID))
        subscription.messages.emit(
            NostrIncomingMessage.EventMessage(subscriptionId = GIFT_WRAP_SUB_ID, nostrEvent = event("after-eose"))
        )
        settle()

        assertEquals(listOf("after-eose"), nostr.saved.map { it.first })

        running.cancelAndJoin()
    }

    @Test
    fun `a relay that closes the subscription gets it re-opened`() = runTest {
        val transport = FakeTransport()
        val running = start(transport, MutableStateFlow(emptyList()))
        settle()

        val first = transport.opened.single { it.reqCommand.subId == GIFT_WRAP_SUB_ID }
        first.messages.emit(
            NostrIncomingMessage.ClosedMessage(subscriptionId = GIFT_WRAP_SUB_ID, message = "shutting down")
        )

        settle(1.seconds)
        assertEquals(
            1,
            transport.opened.count { it.reqCommand.subId == GIFT_WRAP_SUB_ID },
            "should still be inside the back-off, not hammering the relay",
        )

        settle(30.seconds)
        assertEquals(
            2,
            transport.opened.count { it.reqCommand.subId == GIFT_WRAP_SUB_ID },
            "a CLOSED subscription should have been re-opened once the back-off elapsed",
        )

        running.cancelAndJoin()
    }

    /**
     * Back-pressure is the one refusal that must NOT be answered promptly — opening another
     * subscription is exactly what the relay just asked us to stop doing.
     */
    @Test
    fun `a rate-limited close waits far longer before re-opening`() = runTest {
        val transport = FakeTransport()
        val running = start(transport, MutableStateFlow(emptyList()))
        settle()

        transport.opened.single { it.reqCommand.subId == GIFT_WRAP_SUB_ID }.messages.emit(
            NostrIncomingMessage.ClosedMessage(
                subscriptionId = GIFT_WRAP_SUB_ID,
                message = "rate-limited: too many concurrent REQs",
            )
        )
        settle(30.seconds)
        assertEquals(
            1,
            transport.opened.count { it.reqCommand.subId == GIFT_WRAP_SUB_ID },
            "must not re-open within the window a plain failure would have",
        )

        // ...but it is a back-off, not a give-up.
        settle(5.minutes)
        assertEquals(
            2,
            transport.opened.count { it.reqCommand.subId == GIFT_WRAP_SUB_ID },
            "the subscription should come back once the relay has had its breathing room",
        )

        running.cancelAndJoin()
    }

    // --- lifecycle -----------------------------------------------------------------------

    @Test
    fun `backgrounding closes the subscriptions and foregrounding rebuilds them`() = runTest {
        val transport = FakeTransport()
        val foreground = MutableStateFlow(true)
        val running = start(transport, MutableStateFlow(emptyList()), isForeground = foreground)
        settle()

        val openedWhileForeground = transport.opened.size
        assertTrue(openedWhileForeground > 0)

        foreground.value = false
        settle()
        assertTrue(transport.closed.any { it.subId == GIFT_WRAP_SUB_ID }, "should have said goodbye")

        foreground.value = true
        settle()
        assertTrue(transport.opened.size > openedWhileForeground, "should have re-subscribed")

        running.cancelAndJoin()
    }

    /**
     * A live subscription covers the window we are connected for; it cannot answer for the gap
     * we were away. That is negentropy's job, and returning to the foreground is precisely
     * when it needs asking.
     */
    @Test
    fun `foregrounding reconnects and queues a catch-up reconciliation`() = runTest {
        val transport = FakeTransport()
        val nostr = RecordingNostrRepository()
        val running = start(transport, MutableStateFlow(listOf(room("group-a"))), nostr)
        settle()

        assertEquals(1, transport.reconnects, "the socket must not be trusted after a gap")

        val purposes = nostr.queued.map { it.purpose }.toSet()
        assertTrue("chat" in purposes, "gift wraps should be reconciled")
        assertTrue("mlsMessages" in purposes, "group events should be reconciled")

        running.cancelAndJoin()
    }

    // --- harness -------------------------------------------------------------------------

    /**
     * Advances virtual time by exactly [duration] and runs what that makes due.
     *
     * Deliberately not `advanceUntilIdle()`: that runs until nothing is scheduled at all,
     * which means it fast-forwards through *any* pending delay — including the five-minute
     * back-off this suite needs to assert has NOT elapsed. A test that cannot tell "waited"
     * from "did not wait" cannot test a back-off at all.
     */
    private fun kotlinx.coroutines.test.TestScope.settle(duration: Duration = 1.seconds) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun kotlinx.coroutines.test.TestScope.start(
        transport: FakeTransport,
        rooms: MutableStateFlow<List<LocalChatRoom>>,
        nostr: RecordingNostrRepository = RecordingNostrRepository(),
        isForeground: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = launch {
        LiveSubscriptionManager(
            relaysSocketManager = transport,
            nostrRepository = nostr,
            chatRepository = FakeChatRepository(rooms),
            isForeground = isForeground,
        ).observe(keyPair)
    }

    private fun room(
        id: String,
        mlsGroupState: String? = "state",
        leftGroupAt: Instant? = null,
    ) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = id,
            userPublicKey = me,
            subject = null,
            description = null,
            mlsGroupState = mlsGroupState,
            leftGroupAt = leftGroupAt,
        )
    )

    private fun event(id: String) = NostrEvent(
        id = id,
        pubKey = me,
        createdAt = Instant.fromEpochSeconds(1_000),
        kind = 1059,
        tags = emptyArray(),
        content = "",
        sig = "",
    )
}

private class OpenedSubscription(val reqCommand: ReqCmd, val relayUrl: String) {
    val messages = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 32)
}

private class FakeTransport : LiveSubscriptionTransport {
    val opened = mutableListOf<OpenedSubscription>()
    val updated = mutableListOf<OpenedSubscription>()
    val closed = mutableListOf<ClosedSubscription>()
    var reconnects = 0

    data class ClosedSubscription(val subId: String, val relayUrl: String)

    override suspend fun openLiveSubscription(reqCommand: ReqCmd, relayUrl: String): Flow<NostrIncomingMessage> =
        OpenedSubscription(reqCommand, relayUrl).also { opened += it }.messages.asSharedFlow()

    override suspend fun updateLiveSubscription(reqCommand: ReqCmd, relayUrl: String) {
        updated += OpenedSubscription(reqCommand, relayUrl)
    }

    override suspend fun closeLiveSubscription(subId: String, relayUrl: String) {
        closed += ClosedSubscription(subId, relayUrl)
    }

    override suspend fun reconnectAll() {
        reconnects++
    }
}

private class FakeChatRepository(
    private val rooms: StateFlow<List<LocalChatRoom>>,
) : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {

    override suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>> = rooms

    override suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom> = rooms.value
}

private class RecordingNostrRepository : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
    /** (event id, relay it arrived from) */
    val saved = mutableListOf<Pair<String, String>>()
    val queued = mutableListOf<NegentropySynchronizeRequest>()

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        relayURL: String,
        synchronizationRelayURLs: List<String>,
        level: Int,
        activeKeyPair: KeyPair,
    ) {
        saved += nostrEvent.id to relayURL
    }

    override suspend fun queueNegentropySynchronizeRequest(
        negentropySynchronizeRequests: List<NegentropySynchronizeRequest>,
    ) {
        queued += negentropySynchronizeRequests
    }
}
