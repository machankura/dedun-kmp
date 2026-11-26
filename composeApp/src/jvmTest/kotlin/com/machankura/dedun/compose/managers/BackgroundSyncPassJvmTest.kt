package com.machankura.dedun.compose.managers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.notifications.NotificationDispatcher
import com.machankura.dedun.compose.notifications.NotificationStrings
import com.machankura.dedun.compose.notifications.PlatformNotifier
import com.machankura.dedun.compose.notifications.RoomNotification
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The bounds around the app's own engines: when a pass runs at all, what it activates and puts
 * back, when it calls the network quiet, and what it records. docs/notifications-and-background-sync.md, Phase 9.
 *
 * The engines themselves -- subscriptions, reconciliation, ingest -- are the ones the foreground
 * runs and are tested where they live. Here "the relay delivered a message" is a line written while
 * the pass is waiting, which is what a delivery is by the time anything here can see it.
 */
class BackgroundSyncPassJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val privateKey = PrivateKey(ByteVector32("02".repeat(32)))

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private val identity: Identity by lazy {
        Identity.signing(
            id = privateKey.publicKey().xOnly().toWalletId(),
            kind = IdentityKind.NostrSecret,
            nostrPrivateKey = privateKey,
            userPrefs = UserPrefs(prefsStore("user")),
            internalPrefs = InternalPrefs(prefsStore("internal")),
            business = null,
        )
    }
    private val user get() = identity.nostrPublicKey
    private val peer = "b".repeat(64)
    private val room = "11".repeat(32)

    private class FakeNotifier : PlatformNotifier {
        val posted: MutableList<RoomNotification> = Collections.synchronizedList(mutableListOf())
        override fun post(notification: RoomNotification) { posted += notification }
        override fun cancel(key: String) = Unit
        override fun canPost() = true
    }

    private val notifier = FakeNotifier()
    private val active = MutableStateFlow<Identity?>(null)
    private val visible = MutableStateFlow(false)
    private val syncPending = MutableStateFlow(false)
    private val outbound = MutableStateFlow(0)
    private val kept = Collections.synchronizedSet(mutableSetOf<String>())
    private var enginesStarted = 0

    private val dispatcher = NotificationDispatcher(
        database = db,
        notifier = notifier,
        strings = { NotificationStrings("New message", "Note to self", "Someone", { "$it more" }) },
        appVisible = visible,
        visibleChatRoomId = MutableStateFlow(null),
        syncPending = { syncPending },
        settleFor = 10.milliseconds,
    )

    private fun pass(defaultIdentity: Identity? = identity) = BackgroundSyncPass(
        activeIdentity = active,
        loadDefaultIdentity = { defaultIdentity },
        startEngines = { enginesStarted++ },
        syncPending = { syncPending },
        outboundQueued = { outbound },
        dispatcher = dispatcher,
        ledger = db.notificationDao(),
        isVisible = visible,
        keepConnected = { kept += it },
        release = { kept -= it },
        quietFor = 200.milliseconds,
    )

    private suspend fun seed() {
        listOf(user to "me", peer to "alice").forEach { (publicKey, name) ->
            val eventId = publicKey.take(32).padEnd(64, 'c')
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = eventId))
        }
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = user, subject = "Hikers", description = null, mlsGroupState = null))
        db.notificationDao().arm(user, Clock.System.now())
    }

    private suspend fun delivered(text: String) = db.chatMessageDao().upsert(
        ChatMessage(
            senderPublicKey = peer,
            isUserMessage = false,
            giftWrapPayloadId = null,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            chatRoomId = room,
            content = text,
            createdAt = Clock.System.now(),
        )
    )

    @Test
    fun `nothing runs while the app is on screen`() = runBlocking {
        visible.value = true
        assertEquals(BackgroundSyncPass.Outcome.Visible, pass().run(BackgroundSyncPass.Kind.Quick, 5.seconds))
        assertEquals(0, enginesStarted)
    }

    @Test
    fun `nothing runs without a profile that can sign`() = runBlocking {
        assertEquals(BackgroundSyncPass.Outcome.NoIdentity, pass(defaultIdentity = null).run(BackgroundSyncPass.Kind.Quick, 5.seconds))
        assertEquals(0, enginesStarted)
    }

    @Test
    fun `nothing runs for a profile whose notifications are off`() = runBlocking {
        seed()
        db.notificationDao().setSettings(user, enabled = false, messages = true, needsYou = true, groupActivity = false, showMessageText = true)
        assertEquals(BackgroundSyncPass.Outcome.Disabled, pass().run(BackgroundSyncPass.Kind.Full, 5.seconds))
        assertEquals(0, enginesStarted)
    }

    /** The pass's whole job, in one: activate, hold, notice the delivery, post it, let go, record. */
    @Test
    fun `a message delivered during the pass is posted, and the pass puts everything back`() = runBlocking {
        seed()
        val running = async(Dispatchers.Default) { pass().run(BackgroundSyncPass.Kind.Full, 60.seconds) }

        // The engines see the identity and the connections held, as a launch would give them.
        while (active.value == null) delay(10)
        assertSame(identity, active.value)
        assertTrue(com.machankura.dedun.compose.AppLifecycle.KEEP_BACKGROUND_PASS in kept)

        // The reconciliation is queued and the relay answers.
        syncPending.value = true
        delivered("summit at noon")
        syncPending.value = false

        assertEquals(BackgroundSyncPass.Outcome.Completed, running.await())
        assertEquals("summit at noon", notifier.posted.single().lines.single().text)

        assertNull(active.value, "the identity the pass activated was left running")
        assertTrue(kept.isEmpty(), "the connections were left held")
        val ledger = assertNotNull(db.notificationDao().getLedger(user))
        assertEquals("completed", ledger.lastBackgroundSyncOutcome)
        assertEquals("full", ledger.lastBackgroundSyncKind)
        assertEquals(1, enginesStarted)
    }

    @Test
    fun `a pass cut off by its deadline still posts what it filed, and says so`() = runBlocking {
        seed()
        syncPending.value = true
        val running = async(Dispatchers.Default) { pass().run(BackgroundSyncPass.Kind.Quick, 9.seconds) }
        delay(200)
        delivered("before the cut")

        assertEquals(BackgroundSyncPass.Outcome.Deadline, running.await())
        assertEquals("before the cut", notifier.posted.single().lines.single().text)
        assertEquals("deadline", db.notificationDao().getLedger(user)!!.lastBackgroundSyncOutcome)
    }

    /** A row that can never be sent does not move, and is no reason to stay up until the deadline. */
    @Test
    fun `a stuck outbound row does not hold the pass to its deadline`() = runBlocking {
        seed()
        outbound.value = 3
        assertEquals(BackgroundSyncPass.Outcome.Completed, pass().run(BackgroundSyncPass.Kind.Full, 60.seconds))
    }

    /** A process whose screen set the identity before the pass began keeps it afterwards. */
    @Test
    fun `an identity a screen set is left as it was`() = runBlocking {
        seed()
        active.value = identity
        assertEquals(BackgroundSyncPass.Outcome.Completed, pass(defaultIdentity = null).run(BackgroundSyncPass.Kind.Full, 60.seconds))
        assertSame(identity, active.value)
    }

    /** WorkManager and a push, say, asking at once: the second finds the first and leaves. */
    @Test
    fun `two passes at once are one`(): Unit = runBlocking {
        seed()
        syncPending.value = true
        val shared = pass()
        val first = async(Dispatchers.Default) { shared.run(BackgroundSyncPass.Kind.Quick, 30.seconds) }
        while (active.value == null) delay(10)

        assertEquals(BackgroundSyncPass.Outcome.AlreadyRunning, shared.run(BackgroundSyncPass.Kind.Quick, 9.seconds))

        syncPending.value = false
        assertEquals(BackgroundSyncPass.Outcome.Completed, first.await())
    }
}
