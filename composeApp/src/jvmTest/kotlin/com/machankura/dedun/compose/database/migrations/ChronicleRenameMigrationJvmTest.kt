package com.machankura.dedun.compose.database.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The v13 -> v14 rename, against a real database holding rows written under the
 * old word.
 *
 * A rename looks like the safest migration there is, which is why it is worth
 * asserting. Both halves of it carry something that cannot be recovered if it is
 * dropped instead of carried across:
 *
 *  - `chronicleRequestedAt` is the only record that a device with an empty room
 *    has already asked the group for its history. Lose it and the device asks
 *    again on its next launch, and on the one after that.
 *  - The three chat lines are written once each -- when a chronicle is requested,
 *    sent and applied -- and nothing regenerates them. A type the transcript does
 *    not recognise is not skipped; it renders as an ordinary chat bubble, so
 *    "Caught up on 12 items" would come back attributed to a member as something
 *    they said.
 *
 * Run against the migration's own SQL on a bare connection rather than through
 * Room, the way `FrostSigningItemMigrationJvmTest` is: it covers the rename and
 * the rewrite, not Room's version wiring, which belongs to
 * `PlatformDatabaseBuilder` and is the same for every migration in that list.
 */
class ChronicleRenameMigrationJvmTest {

    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    /** v13's `ChatRoom` and `ChatMessage`, verbatim from `schemas/13.json`. */
    private fun createV13() {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `ChatRoom` (`id` TEXT NOT NULL, " +
                "`userPublicKey` TEXT NOT NULL, `subject` TEXT, `description` TEXT, " +
                "`mlsGroupState` TEXT, `initialGiftWrapPayloadId` TEXT, `leftGroupAt` INTEGER, " +
                "`archiveRequestedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, `viewedAt` INTEGER, " +
                "`deletedAt` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`userPublicKey`) REFERENCES `Profile`(`publicKey`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`initialGiftWrapPayloadId`) REFERENCES `GiftWrapPayload`(`id`) " +
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

    /** A room that asked for the group's history at [requestedAt] and is still waiting. */
    private fun insertV13Room(id: String = "room", requestedAt: Long?) = connection.execSQL(
        "INSERT INTO `ChatRoom` VALUES ('$id', 'user', NULL, NULL, NULL, NULL, NULL, " +
            "${requestedAt ?: "NULL"}, 1000, 1000, 1000, NULL, NULL)"
    )

    private fun insertV13Message(messageType: String, content: String = "line") =
        connection.execSQL(
            "INSERT INTO `ChatMessage` (`senderPublicKey`, `isUserMessage`, `chatRoomId`, " +
                "`content`, `messageType`, `createdAt`, `updatedAt`, `savedAt`) " +
                "VALUES ('user', 0, 'room', '$content', '$messageType', 1000, 1000, 1000)"
        )

    /** Column names in declaration order, which is what Room checks the table against. */
    private fun columns(table: String): List<String> =
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildList { while (statement.step()) add(statement.getText(1)) }
        }

    /** One column of the one row, as text, or null when the column is null. */
    private fun read(table: String, column: String): String? =
        connection.prepare("SELECT `$column` FROM `$table`").use { statement ->
            if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
        }

    private fun messageTypes(): List<String> =
        connection.prepare("SELECT `messageType` FROM `ChatMessage` ORDER BY `id`").use { s ->
            buildList { while (s.step()) add(s.getText(0)) }
        }

    @Test
    fun `a room still waiting on an answer keeps the moment it asked`() = runBlocking {
        createV13()
        insertV13Room(requestedAt = 1_700_000_000)

        MIGRATION_13_14.migrate(connection)

        // Renamed, not dropped and re-added: a null here is a device that asks
        // the group for its history again on every launch.
        assertEquals("1700000000", read("ChatRoom", "chronicleRequestedAt"))
    }

    @Test
    fun `the column keeps its place, so Room reads the table it expects`() = runBlocking {
        createV13()

        MIGRATION_13_14.migrate(connection)

        assertEquals(
            listOf(
                "id",
                "userPublicKey",
                "subject",
                "description",
                "mlsGroupState",
                "initialGiftWrapPayloadId",
                "leftGroupAt",
                "chronicleRequestedAt",
                "createdAt",
                "updatedAt",
                "savedAt",
                "viewedAt",
                "deletedAt",
            ),
            columns("ChatRoom"),
            "v14's ChatRoom columns, in the order schemas/14.json declares them"
        )
    }

    @Test
    fun `a room that never asked still reads as never having asked`() = runBlocking {
        createV13()
        insertV13Room(requestedAt = null)

        MIGRATION_13_14.migrate(connection)

        assertEquals(null, read("ChatRoom", "chronicleRequestedAt"))
    }

    @Test
    fun `the three lines about the group's record are rewritten`() = runBlocking {
        createV13()
        insertV13Message("archiveRequested")
        insertV13Message("archiveSent")
        insertV13Message("archiveReceived")

        MIGRATION_13_14.migrate(connection)

        assertEquals(
            listOf(
                ChatMessage.TYPE_CHRONICLE_REQUESTED,
                ChatMessage.TYPE_CHRONICLE_SENT,
                ChatMessage.TYPE_CHRONICLE_RECEIVED,
            ),
            messageTypes()
        )
    }

    @Test
    fun `every other line is left alone`() = runBlocking {
        createV13()
        insertV13Message("message")
        insertV13Message(ChatMessage.TYPE_FROST_COMPLETE)
        insertV13Message(ChatMessage.TYPE_DKG_APPROVAL_NEEDED_LEGACY)

        MIGRATION_13_14.migrate(connection)

        assertEquals(
            listOf(
                "message",
                ChatMessage.TYPE_FROST_COMPLETE,
                ChatMessage.TYPE_DKG_APPROVAL_NEEDED_LEGACY,
            ),
            messageTypes()
        )
    }
}
