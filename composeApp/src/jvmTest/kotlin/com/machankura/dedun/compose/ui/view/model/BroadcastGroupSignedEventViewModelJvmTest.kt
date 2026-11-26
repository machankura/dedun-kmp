package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.exceptions.NostrPublishException
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.view.model.BroadcastGroupSignedEventViewModel.RelayOutcome
import com.machankura.dedun.compose.ui.view.state.BroadcastGroupSignedEventUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The three decisions the broadcast screen makes that nothing else can check.
 *
 * **Where an event goes before anyone says otherwise.** The seeded list is what a
 * member who reads nothing and presses the button sends to: the app's publish set,
 * since Dedun did not bring back the group's relay lists
 * (docs/group-nostr-profile-and-posts.md, D1).
 *
 * **What each relay's answer becomes.** The pool answers once per relay in three
 * shapes -- an OK, a rejection wrapped in one exception type, and any other error
 * -- and the rows show them as three different things because a member can act
 * on "blocked: not on the allow list" and cannot act on a red icon.
 *
 * **What goes on the wire.** The seven fields the group hashed and nothing else,
 * so a relay checking the id and the signature finds the event the group signed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastGroupSignedEventViewModelJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")
    private val three = RelayUrlNormalizer.normalize("wss://relay.three.example")

    /** Answers each relay with whatever [answers] says, keyed by host, and remembers what it was handed. */
    private class FakeTransport(
        private val answers: Map<String, NostrPublishResult>
    ) : EventPublishTransport {
        var sent: NostrEvent? = null
        var askedRelays: Set<RelayDTO> = emptySet()

        override suspend fun publishEvent(
            nostrEvent: NostrEvent,
            relays: Set<RelayDTO>
        ): Flow<NostrPublishResult> {
            sent = nostrEvent
            askedRelays = relays
            return flowOf(*answers.values.toTypedArray())
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(transport: EventPublishTransport = FakeTransport(emptyMap())) =
        BroadcastGroupSignedEventViewModel(
            chatRoomId = chatRoomId,
            signedEventId = "b".repeat(64),
            activeUserPublicKey = "a".repeat(64),
            relayHint = null,
            initialBroadcastGroupSignedEventUIState = BroadcastGroupSignedEventUIState.Loading,
            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
            publishTransport = transport,
        )

    private fun signedEvent(kind: Int = TextNoteEvent.KIND, tags: Array<Array<String>> = emptyArray()) =
        GroupSignedEvent(
            id = "b".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = kind,
            tags = tags,
            content = "The first chapter is out.",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        )

    private fun ok(relay: NormalizedRelayUrl) = NostrPublishResult(
        result = NostrIncomingMessage.OkMessage(eventId = "b".repeat(64), success = true),
        relayUrl = relay.url,
    )

    private fun rejected(relay: NormalizedRelayUrl, reason: String) = NostrPublishResult(
        result = NostrIncomingMessage.OkMessage(eventId = "b".repeat(64), success = false, message = reason),
        error = NostrPublishException(message = reason),
        relayUrl = relay.url,
    )

    private fun failed(relay: NormalizedRelayUrl, reason: String) = NostrPublishResult(
        error = IllegalStateException(reason),
        relayUrl = relay.url,
    )

    // -- where an event goes before anyone says otherwise ------------------------

    /** docs/group-nostr-profile-and-posts.md, D1: no relay lists, so the app's own set. */
    @Test
    fun `an event is seeded to the app's publish set, whatever its kind`() {
        listOf(MetadataEvent.KIND, TextNoteEvent.KIND).forEach { kind ->
            assertEquals(
                Relays.eventPublishRelaySet.toList(),
                BroadcastGroupSignedEventViewModel.defaultRelaysFor(kind),
                "kind $kind",
            )
        }
    }

    @Test
    fun `seeding happens once, so an emptied list stays empty`() {
        val viewModel = viewModel()
        val event = signedEvent()

        viewModel.seedRelays(event.kind)
        viewModel.seedRelays(event.kind)
        assertEquals(Relays.eventPublishRelaySet.toList(), viewModel.relays.toList())

        viewModel.relays.toList().forEach(viewModel::removeRelay)
        viewModel.seedRelays(event.kind)
        assertEquals(emptyList(), viewModel.relays.toList())
    }

    // -- what may be added ----------------------------------------------------------

    @Test
    fun `an add is refused by name, and a good one clears the refusal`() {
        val viewModel = viewModel()

        assertFalse(viewModel.addRelay("https://not-a-relay.example"))
        assertEquals(BroadcastGroupSignedEventViewModel.AddFailure.NotARelay, viewModel.addFailure)

        assertTrue(viewModel.addRelay(one.url))
        assertNull(viewModel.addFailure)

        assertFalse(viewModel.addRelay(one.url))
        assertEquals(BroadcastGroupSignedEventViewModel.AddFailure.AlreadyListed, viewModel.addFailure)

        assertEquals(listOf(one), viewModel.relays.toList())
    }

    // -- what each relay's answer becomes ---------------------------------------------

    @Test
    fun `each relay's answer lands on its own row, and the sum counts only the OKs`() {
        val transport = FakeTransport(
            mapOf(
                "one" to ok(one),
                "two" to rejected(two, "blocked: not on the allow list"),
                "three" to failed(three, "Connection refused"),
            )
        )
        val viewModel = viewModel(transport)
        listOf(one, two, three).forEach { assertTrue(viewModel.addRelay(it.url)) }

        var toldNoRelays = false
        val job = viewModel.broadcast(signedEvent(), onNoRelays = { toldNoRelays = true })
        runBlocking { withTimeout(5_000) { job!!.join() } }

        assertFalse(toldNoRelays)
        assertEquals(RelayOutcome.Accepted, viewModel.outcomes[one])
        assertEquals(RelayOutcome.Rejected("blocked: not on the allow list"), viewModel.outcomes[two])
        assertEquals(RelayOutcome.Failed("Connection refused"), viewModel.outcomes[three])
        assertEquals(
            BroadcastGroupSignedEventViewModel.BroadcastSummary(accepted = 1, asked = 3),
            viewModel.lastBroadcast,
        )
        assertFalse(viewModel.isActionPending.value)

        // Asked as write relays: the pool would otherwise read from them too.
        assertEquals(
            setOf(one, two, three).map { RelayDTO(url = it.url, read = false, write = true) }.toSet(),
            transport.askedRelays,
        )
    }

    @Test
    fun `an answer is matched by host, since the pool may spell the relay with a trailing slash`() {
        val transport = FakeTransport(
            mapOf("one" to ok(one).copy(relayUrl = one.url.trimEnd('/') + "/"))
        )
        val viewModel = viewModel(transport)
        assertTrue(viewModel.addRelay(one.url))

        val job = viewModel.broadcast(signedEvent(), onNoRelays = {})
        runBlocking { withTimeout(5_000) { job!!.join() } }

        assertEquals(RelayOutcome.Accepted, viewModel.outcomes[one])
    }

    @Test
    fun `a relay that never answers is marked so, which is not the same as refused`() {
        // The pool answers for one relay and then the flow ends, which is what a
        // send that ran out of time looks like from here.
        val transport = FakeTransport(mapOf("one" to ok(one)))
        val viewModel = viewModel(transport)
        assertTrue(viewModel.addRelay(one.url))
        assertTrue(viewModel.addRelay(two.url))

        val job = viewModel.broadcast(signedEvent(), onNoRelays = {})
        runBlocking { withTimeout(5_000) { job!!.join() } }

        assertEquals(RelayOutcome.Accepted, viewModel.outcomes[one])
        assertEquals(RelayOutcome.NoAnswer, viewModel.outcomes[two])
        assertEquals(
            BroadcastGroupSignedEventViewModel.BroadcastSummary(accepted = 1, asked = 2),
            viewModel.lastBroadcast,
        )
    }

    @Test
    fun `an empty list asks nobody and says so`() {
        val transport = FakeTransport(emptyMap())
        val viewModel = viewModel(transport)

        var toldNoRelays = false
        val job = viewModel.broadcast(signedEvent(), onNoRelays = { toldNoRelays = true })

        assertNull(job)
        assertTrue(toldNoRelays)
        assertNull(transport.sent)
        assertFalse(viewModel.isActionPending.value)
    }

    // -- what goes on the wire ------------------------------------------------------------

    @Test
    fun `what goes on the wire is the event the group signed, field for field`() {
        val event = signedEvent(
            kind = MetadataEvent.KIND,
            tags = arrayOf(arrayOf("alt", "The group's profile")),
        )

        val wire = BroadcastGroupSignedEventViewModel.wireEventOf(event).toNostrJsonObject()

        assertEquals(event.id, wire["id"]!!.jsonPrimitive.content)
        assertEquals(event.publicKey, wire["pubkey"]!!.jsonPrimitive.content)
        assertEquals(event.createdAt.epochSeconds.toString(), wire["created_at"]!!.jsonPrimitive.content)
        assertEquals(event.kind.toString(), wire["kind"]!!.jsonPrimitive.content)
        assertEquals(event.content, wire["content"]!!.jsonPrimitive.content)
        assertEquals(event.signature, wire["sig"]!!.jsonPrimitive.content)
        assertEquals("""[["alt","The group's profile"]]""", wire["tags"].toString())
    }
}
