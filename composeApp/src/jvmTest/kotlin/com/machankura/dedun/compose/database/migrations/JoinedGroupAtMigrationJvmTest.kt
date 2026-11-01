package com.machankura.dedun.compose.database.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v14 -> v15 migration, against a real database holding the lines it exists
 * to clear out.
 *
 * Fixing the write path only stops the next one. Nothing rewrites a chat line
 * that is already in the transcript, so a member who joined a busy room a week
 * ago would go on opening it to the same run of "Undecryptable Message" -- one
 * per message the group sent before they arrived -- however many versions later.
 * That is the half of this migration worth asserting.
 *
 * What it must not do matters just as much. The delete is aimed at rows that say
 * nothing by design, and everything else in the transcript is the final word on
 * its group event: a message that was read, a commit that was applied, a line
 * this device wrote for something it sent. A rule with a join and a comparison in
 * it can take too much, and there is no undo.
 *
 * Run against the migration's own SQL on a bare connection rather than through
 * Room, the way `ChronicleRenameMigrationJvmTest` is: Room's version wiring
 * belongs to `PlatformDatabaseBuilder` and is the same for every migration in
 * that list.
 */
class JoinedGroupAtMigrationJvmTest {

    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    /** v14's three tables, verbatim from `schemas/14.json`. */
    private fun createV14() {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `ChatRoom` (`id` TEXT NOT NULL, " +
                "`userPublicKey` TEXT NOT NULL, `subject` TEXT, `description` TEXT, " +
                "`mlsGroupState` TEXT, `initialGiftWrapPayloadId` TEXT, `leftGroupAt` INTEGER, " +
                "`chronicleRequestedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, `viewedAt` INTEGER, " +
                "`deletedAt` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`userPublicKey`) REFERENCES `Profile`(`publicKey`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`initialGiftWrapPayloadId`) REFERENCES `GiftWrapPayload`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `MarmotGroupEvent` (`id` TEXT NOT NULL, " +
                "`userPublicKey` TEXT NOT NULL, `publicKey` TEXT NOT NULL, " +
                "`chatRoomId` TEXT NOT NULL, `signature` TEXT NOT NULL, " +
                "`encryptedContent` TEXT NOT NULL, `expiresAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`savedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `broadcastedAt` INTEGER, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `NostrEvent`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `ChatMessage` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`senderPublicKey` TEXT NOT NULL, `isUserMessage` INTEGER NOT NULL, " +
                "`giftWrapPayloadId` TEXT, `marmotGroupEventId` TEXT, " +
                "`marmotInnerEventId` TEXT, `chatRoomId` TEXT NOT NULL, " +
                "`replyToMessageId` INTEGER, `quotedMessageId` INTEGER, " +
                "`content` TEXT NOT NULL, `messageType` TEXT NOT NULL, " +
                "`directMessageRecipientPublicKey` TEXT, `frostSigningSessionId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`savedAt` INTEGER NOT NULL, `viewedAt` INTEGER, `deletedAt` INTEGER, " +
                "FOREIGN KEY(`chatRoomId`) REFERENCES `ChatRoom`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`giftWrapPayloadId`) REFERENCES `GiftWrapPayload`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`marmotGroupEventId`) REFERENCES `MarmotGroupEvent`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`marmotInnerEventId`) REFERENCES `MarmotInnerEvent`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
    }

    /** A room this device wrote down at [createdAt], which is when it joined. */
    private fun insertRoom(id: String = "room", createdAt: Long) = connection.execSQL(
        "INSERT INTO `ChatRoom` VALUES ('$id', 'user', NULL, NULL, NULL, NULL, NULL, NULL, " +
            "$createdAt, $createdAt, $createdAt, NULL, NULL)"
    )

    private fun insertGroupEvent(id: String, createdAt: Long, room: String = "room") =
        connection.execSQL(
            "INSERT INTO `MarmotGroupEvent` (`id`, `userPublicKey`, `publicKey`, `chatRoomId`, " +
                "`signature`, `encryptedContent`, `createdAt`, `updatedAt`, `savedAt`) " +
                "VALUES ('$id', 'user', 'sender', '$room', 'sig', 'ciphertext', " +
                "$createdAt, $createdAt, $createdAt)"
        )

    private fun insertLine(
        groupEventId: String?,
        messageType: String,
        room: String = "room",
        content: String = "line",
    ) = connection.execSQL(
        "INSERT INTO `ChatMessage` (`senderPublicKey`, `isUserMessage`, `marmotGroupEventId`, " +
            "`chatRoomId`, `content`, `messageType`, `createdAt`, `updatedAt`, `savedAt`) " +
            "VALUES ('user', 0, ${groupEventId?.let { "'$it'" } ?: "NULL"}, '$room', " +
            "'$content', '$messageType', 1000, 1000, 1000)"
    )

    private fun lines(): List<String> =
        connection.prepare("SELECT `content` FROM `ChatMessage` ORDER BY `id`").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }

    private fun columns(table: String): List<String> =
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildList { while (statement.step()) add(statement.getText(1)) }
        }

    @Test
    fun `the column arrives, and every room reads as not having recorded a join`() = runBlocking {
        createV14()
        insertRoom(createdAt = 5_000)

        MIGRATION_14_15.migrate(connection)

        assertTrue("joinedGroupAt" in columns("ChatRoom"))
        // Null on purpose. It already means "ask createdAt" -- see
        // ChatRoom.memberSince -- and backfilling it would turn a fallback into
        // a claim this migration is in no position to make.
        connection.prepare("SELECT `joinedGroupAt` FROM `ChatRoom`").use { statement ->
            assertTrue(statement.step() && statement.isNull(0))
        }
    }

    /**
     * `ALTER TABLE ... ADD COLUMN` appends, so the column lands last rather than
     * where v15 declares it. Pinned because it looks like a mismatch and is not:
     * Room compares a table's columns by name, and its own generated migration
     * for a nullable addition appends in exactly this way.
     */
    @Test
    fun `the column is appended, which is where Room's own migrations put one`() = runBlocking {
        createV14()

        MIGRATION_14_15.migrate(connection)

        assertEquals("joinedGroupAt", columns("ChatRoom").last())
    }

    @Test
    fun `the placeholder lines for messages sent before this device joined are cleared`() =
        runBlocking {
            createV14()
            insertRoom(createdAt = 5_000)
            insertGroupEvent("before", createdAt = 4_000)
            insertLine("before", ChatMessage.TYPE_UNDECRYPTABLE_OUTER_LAYER, content = "gone")

            MIGRATION_14_15.migrate(connection)

            assertEquals(emptyList(), lines())
        }

    /**
     * A commit held back because two competed for the same epoch reads the same
     * way to the transcript, so it goes by the same rule. Both types are
     * [ChatMessage.UNRESOLVED_MARMOT_TYPES] -- lines that stand in for an event
     * that was never read -- and removing one loses nothing.
     */
    @Test
    fun `a pending commit line from before the join goes too`() = runBlocking {
        createV14()
        insertRoom(createdAt = 5_000)
        insertGroupEvent("before", createdAt = 4_000)
        insertLine("before", ChatMessage.TYPE_PENDING_COMMIT, content = "gone")

        MIGRATION_14_15.migrate(connection)

        assertEquals(emptyList(), lines())
    }

    /**
     * The one this rule could get wrong. A placeholder for an event from after
     * the join is a message still waiting on a commit that has not landed, and a
     * replay is expected to recover it -- see `NostrDao.reindexMarmotGroupEvents`.
     */
    @Test
    fun `a placeholder for an event from after the join is left to be recovered`() = runBlocking {
        createV14()
        insertRoom(createdAt = 5_000)
        insertGroupEvent("after", createdAt = 6_000)
        insertLine("after", ChatMessage.TYPE_UNDECRYPTABLE_OUTER_LAYER, content = "still waiting")

        MIGRATION_14_15.migrate(connection)

        assertEquals(listOf("still waiting"), lines())
    }

    @Test
    fun `a message that was read is left alone however old it is`() = runBlocking {
        createV14()
        insertRoom(createdAt = 5_000)
        insertGroupEvent("read", createdAt = 4_000)
        insertLine("read", "message", content = "hello")

        MIGRATION_14_15.migrate(connection)

        assertEquals(listOf("hello"), lines())
    }

    /**
     * Lines with no group event behind them: NIP-17 messages, and everything a
     * session writes about itself. The delete reaches them through
     * `marmotGroupEventId`, and a null one joins to nothing, but SQL nulls are
     * quiet enough about it to be worth an assertion.
     */
    @Test
    fun `a line with no group event behind it is out of reach of the rule`() = runBlocking {
        createV14()
        insertRoom(createdAt = 5_000)
        insertLine(null, ChatMessage.TYPE_UNDECRYPTABLE_OUTER_LAYER, content = "not marmot")

        MIGRATION_14_15.migrate(connection)

        assertEquals(listOf("not marmot"), lines())
    }

    /**
     * The comparison is per room. Two rooms joined at different times share one
     * transcript table, and a run of placeholders in the older room says nothing
     * about the newer one's.
     */
    @Test
    fun `each room is measured against its own join`() = runBlocking {
        createV14()
        insertRoom(id = "old", createdAt = 1_000)
        insertRoom(id = "new", createdAt = 9_000)
        insertGroupEvent("inOld", createdAt = 4_000, room = "old")
        insertGroupEvent("inNew", createdAt = 4_000, room = "new")
        insertLine("inOld", ChatMessage.TYPE_UNDECRYPTABLE_OUTER_LAYER, room = "old", content = "kept")
        insertLine("inNew", ChatMessage.TYPE_UNDECRYPTABLE_OUTER_LAYER, room = "new", content = "gone")

        MIGRATION_14_15.migrate(connection)

        assertEquals(listOf("kept"), lines())
    }
}
