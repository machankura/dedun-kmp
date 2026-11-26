package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository

/**
 * What the app does today that notifications change, pinned so each change is made on purpose.
 * docs/notifications-and-background-sync.md, Phase 1.
 *
 * The third pin of that phase -- that going to the background closes every live subscription --
 * is `LiveSubscriptionReconcileTest`'s `backgrounding closes the subscriptions and foregrounding rebuilds them`, and Phase 8 moves it.
 */
class NotificationTodayJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val peer = MarmotClientStandIn()

    /**
     * The premise of the background pass (Phase 0, check 5): a Welcome and the first message after
     * it are filed by `storeNostrEvent` alone, with no view model running. Phase 4 flipped the second
     * half: the room now knows the message has not been read.
     */
    @Test
    fun `a group message is filed with no view model, and counts as unread`() = runBlocking {
        MarmotStandIn.seedProfile(db, userHex, "us")
        MarmotStandIn.seedProfile(db, peer.hex, "peer")
        val roomId = peer.inviteApp(db, marmotRepository, user, peer.createGroup("notify"))

        val bundle = peer.client.buildTextMessage(roomId, "are you there", persistOwn = false)
        MarmotStandIn.storeInApp(db, user, bundle.outbound.signedEvent)

        val line = assertNotNull(
            db.chatMessageDao().getChatMessagesByChatRoomId(roomId)
                .map { it.chatMessage }
                .firstOrNull { it.content == "are you there" },
            "the message was not filed without a view model",
        )
        assertFalse(line.isUserMessage)

        assertEquals(1, db.notificationDao().unreadCount(roomId))
    }

    private val source: File =
        File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")

    /**
     * The object graph used to be built inside a composable, so nothing could run without a UI.
     * Phase 2 moved it to `DedunRuntime`, one per process, and this names that file instead. A
     * second construction site is a second database handle and a second relay pool.
     */
    @Test
    fun `the repositories are constructed in DedunRuntime and nowhere else`() {
        val builders = source.resolve("commonMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "DatabaseNostrRepository(" in it.readText().replace("class DatabaseNostrRepository(", "") }
            .map { it.nameWithoutExtension }
            .toSet()

        assertEquals(setOf("DedunRuntime"), builders)
    }
}
