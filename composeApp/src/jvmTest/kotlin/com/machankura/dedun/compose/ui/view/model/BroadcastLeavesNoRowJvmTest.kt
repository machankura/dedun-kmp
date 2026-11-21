package com.machankura.dedun.compose.ui.view.model

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.RetiredKinds
import com.machankura.dedun.compose.ui.view.state.BroadcastGroupSignedEventUIState

/**
 * A broadcast of the group's profile writes nothing to this device's tables
 * (docs/group-nostr-profile-and-posts.md, D7).
 *
 * The whole reason the screen sends through the pool rather than the
 * `BroadcastNostrEventRequest` queue: the queue hangs off a `NostrEvent` row, and a
 * row for the group's event would put its post in the home feed, offer it to every
 * relay the sync loop reconciles with, and -- for a kind:0 -- make a `Profile` row for
 * the group's key through `NostrDao.toProfile`. Checked against the real repository
 * and database, since what is being pinned is an absence there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastLeavesNoRowJvmTest {

    private val database: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val member = "a".repeat(64)

    private val profile = GroupSignedEvent(
        id = "e".repeat(64),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = MetadataEvent.KIND,
        tags = emptyArray(),
        content = """{"name":"Translation collective"}""",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    /** Answers every relay with an OK, and keeps what it was handed. */
    private class Transport : EventPublishTransport {
        var sent: NostrEvent? = null

        override suspend fun publishEvent(nostrEvent: NostrEvent, relays: Set<RelayDTO>): Flow<NostrPublishResult> {
            sent = nostrEvent
            return flowOf(
                *relays.map {
                    NostrPublishResult(
                        result = NostrIncomingMessage.OkMessage(eventId = nostrEvent.id, success = true),
                        relayUrl = it.url,
                    )
                }.toTypedArray()
            )
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `a broadcast profile reaches the relays and leaves no event, profile or queue row`(): Unit = runBlocking {
        // The room's owner, which a room row needs before it can be written.
        val ownerEventId = "0e".repeat(32)
        database.nostrEventDao().upsert(
            NostrEvent(id = ownerEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        database.profileDao().upsert(Profile(publicKey = member, nostrEventId = ownerEventId))
        database.chatRoomDao().upsert(
            ChatRoom(id = chatRoomId, userPublicKey = member, subject = "Translation room", description = null, mlsGroupState = "state")
        )
        database.groupSignedEventDao().record(profile)

        val transport = Transport()
        val viewModel = BroadcastGroupSignedEventViewModel(
            chatRoomId = chatRoomId,
            signedEventId = profile.id,
            activeUserPublicKey = member,
            relayHint = null,
            initialBroadcastGroupSignedEventUIState = BroadcastGroupSignedEventUIState.Loading,
            chatRepository = DatabaseChatRepository(database, CoroutineScope(Dispatchers.Default)),
            publishTransport = transport,
        )

        viewModel.initiateBroadcastGroupSignedEvent()
        val loaded = withTimeout(5_000) {
            var state = viewModel.broadcastGroupSignedEventUIState
            while (state !is BroadcastGroupSignedEventUIState.Loaded) {
                assertIs<BroadcastGroupSignedEventUIState.Loading>(state, "the event did not load")
                delay(10)
                state = viewModel.broadcastGroupSignedEventUIState
            }
            state
        }

        viewModel.seedRelays(loaded.signedEvent.kind)
        val send = assertNotNull(viewModel.broadcast(loaded.signedEvent, onNoRelays = {}))
        withTimeout(5_000) { send.join() }

        assertEquals(profile.id, transport.sent?.id, "the relays were handed the group's event")
        assertEquals(viewModel.relays.size, viewModel.lastBroadcast?.accepted)

        assertNull(database.nostrEventDao().getNostrEventById(profile.id), "a NostrEvent row for the group's event")
        assertNull(database.profileDao().getProfileByPublicKey(chatRoomId), "a Profile row for the group's key")
        assertTrue(
            database.broadcastNostrEventRequestDao().getBroadcastNostrEventRequestsByNostrEventId(profile.id).isEmpty(),
            "a queued broadcast",
        )
    }

    /**
     * A wallet's statement the group signed in its Dedun days is still on this device
     * (the wallet removal kept every `GroupSignedEvent`), and its xpub on a public
     * relay would be the group's whole history on chain. The screen names only the
     * profile and posts, but the id is a navigation argument.
     */
    @Test
    fun `a signed event of any other kind is not offered to the relays`(): Unit = runBlocking {
        val ownerEventId = "0e".repeat(32)
        database.nostrEventDao().upsert(
            NostrEvent(id = ownerEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        database.profileDao().upsert(Profile(publicKey = member, nostrEventId = ownerEventId))
        database.chatRoomDao().upsert(
            ChatRoom(id = chatRoomId, userPublicKey = member, subject = "Translation room", description = null, mlsGroupState = "state")
        )
        val statement = profile.copy(id = "5".repeat(64), kind = RetiredKinds.ANNOUNCEMENTS.first(), content = "{}")
        database.groupSignedEventDao().record(statement)

        val transport = Transport()
        val viewModel = BroadcastGroupSignedEventViewModel(
            chatRoomId = chatRoomId,
            signedEventId = statement.id,
            activeUserPublicKey = member,
            relayHint = null,
            initialBroadcastGroupSignedEventUIState = BroadcastGroupSignedEventUIState.Loading,
            chatRepository = DatabaseChatRepository(database, CoroutineScope(Dispatchers.Default)),
            publishTransport = transport,
        )

        viewModel.initiateBroadcastGroupSignedEvent()
        val state = withTimeout(5_000) {
            while (viewModel.broadcastGroupSignedEventUIState is BroadcastGroupSignedEventUIState.Loading) delay(10)
            viewModel.broadcastGroupSignedEventUIState
        }

        assertIs<BroadcastGroupSignedEventUIState.Error>(state)
        assertNull(transport.sent, "nothing was handed to the relays")
    }
}
