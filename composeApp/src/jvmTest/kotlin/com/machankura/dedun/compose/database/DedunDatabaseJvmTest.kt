package com.machankura.dedun.compose.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.PlatformContext
import com.machankura.dedun.compose.database.builder.PlatformDatabaseBuilder
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first thing to actually open this database anywhere but android.
 *
 * Everything else about the jvm target is checked by the compiler, and the compiler cannot
 * see any of what this covers: that Room's KSP output for the jvm target is usable, that the
 * bundled SQLite native for the *host* loads (the android artifact ships only android-ABI
 * `.so` files and would fail here), that the schema is created, and that the DAO methods
 * turned `suspend` for this target still read back what they wrote.
 *
 * Room's own guidance recommends exactly this shape -- an in-memory database on the host
 * rather than an instrumented test on a device -- and the no-`Context` builder overload it
 * uses is available here because `DedunDatabaseConstructor` gives Room a way to instantiate
 * without reflection.
 */
class DedunDatabaseJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    /**
     * `Profile.nostrEventId` is a foreign key onto `NostrEvent`, so the parent row has to
     * exist first. The first version of this test skipped that and every write failed with
     * SQLite error 787 -- which is worth noting rather than tidying away, because it is
     * evidence that foreign keys really are enforced on this platform. A schema whose
     * constraints were quietly off would have let all of it pass.
     */
    private suspend fun seedProfile(publicKey: String, userName: String = "tester"): Profile {
        val nostrEventId = "1$publicKey".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = publicKey,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        return Profile(publicKey = publicKey, userName = userName, nostrEventId = nostrEventId)
    }

    @Test
    fun `the database opens and the schema exists`() = runBlocking {
        // getAllProfiles against an empty database exercises the generated query path
        // without depending on anything having been written first.
        assertTrue(db.profileDao().getAllProfiles().isEmpty())
    }

    @Test
    fun `a profile survives a write and a read`() = runBlocking {
        val publicKey = "a".repeat(64)
        db.profileDao().upsert(seedProfile(publicKey))

        val found = db.profileDao().getProfileByPublicKey(publicKey)
        assertNotNull(found, "the profile just written could not be read back")
        assertEquals("tester", found.userName)
    }

    @Test
    fun `upsert replaces rather than duplicating`() = runBlocking {
        val publicKey = "b".repeat(64)
        db.profileDao().upsert(seedProfile(publicKey))
        db.profileDao().upsert(seedProfile(publicKey, userName = "renamed"))

        assertEquals("renamed", db.profileDao().getProfileByPublicKey(publicKey)?.userName)
        assertEquals(1, db.profileDao().getProfileByPublicKeys(listOf(publicKey)).size)
    }

    /**
     * `findChatRoomById` is `@Transaction`-annotated and returns a relation object, so it
     * exercises rather more of the generated code than a flat entity query does -- and it is
     * one of the methods this target forced from blocking to `suspend`.
     */
    @Test
    fun `a chat room reads back through its relation query`() = runBlocking {
        val userPublicKey = "c".repeat(64)
        val chatRoomId = "d".repeat(64)
        db.profileDao().upsert(seedProfile(userPublicKey))
        db.chatRoomDao().upsert(
            ChatRoom(
                id = chatRoomId,
                userPublicKey = userPublicKey,
                subject = "a subject",
                description = null,
                mlsGroupState = null,
            )
        )

        val found = db.chatRoomDao().findChatRoomById(chatRoomId)
        assertNotNull(found, "the chat room just written could not be read back")
        assertEquals(1, db.chatRoomDao().getChatRoomListByUserPublicKey(userPublicKey).size)
    }

    /** Every read query in this schema filters on `deletedAt IS NULL`. */
    @Test
    fun `a soft deleted chat room stops being found`() = runBlocking {
        val userPublicKey = "e".repeat(64)
        val chatRoomId = "f".repeat(64)
        db.profileDao().upsert(seedProfile(userPublicKey))
        val room = ChatRoom(
            id = chatRoomId,
            userPublicKey = userPublicKey,
            subject = null,
            description = null,
            mlsGroupState = null,
        )
        db.chatRoomDao().upsert(room)
        assertNotNull(db.chatRoomDao().findChatRoomById(chatRoomId))

        db.chatRoomDao().upsert(room.copy(deletedAt = kotlin.time.Clock.System.now()))
        assertNull(db.chatRoomDao().findChatRoomById(chatRoomId))
    }

    /**
     * The on-disk builder, rather than the in-memory one, so the platform actual written for
     * this target is covered too -- including that it puts the file under the context's
     * directory instead of the system temporary directory.
     */
    @Test
    fun `the on-disk builder writes where the context says`() = runBlocking {
        val dir = Files.createTempDirectory("dedun-db-test").toFile()
        try {
            val onDisk = getRoomDatabase(
                PlatformDatabaseBuilder.getDatabaseBuilder(PlatformContext(dir))
            )
            try {
                val nostrEventId = "9".repeat(64)
                onDisk.nostrEventDao().upsert(
                    NostrEvent(
                        id = nostrEventId,
                        pubKey = "1".repeat(64),
                        kind = 0,
                        tags = emptyArray(),
                        content = "{}",
                        sig = "0".repeat(128),
                    )
                )
                onDisk.profileDao().upsert(
                    Profile(publicKey = "1".repeat(64), nostrEventId = nostrEventId)
                )
                assertNotNull(onDisk.profileDao().getProfileByPublicKey("1".repeat(64)))
            } finally {
                onDisk.close()
            }
            assertTrue(
                java.io.File(dir, "aux.db").exists(),
                "expected the database under the context directory, not java.io.tmpdir",
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
