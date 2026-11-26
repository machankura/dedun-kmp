package com.machankura.dedun.compose.database.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dedun's v55 -> v56 rename, against rows a device stored under the old marks.
 *
 * Dedun's rooms are `#name` (docs/giza-second-batch.md, Phase 8). The `&` subjects are Giza's
 * and Bennu's intermediate mark, which a room joined from one of their Welcomes may carry. This
 * migration stores both bare, and the app draws the braces (docs/group-name-braces.md, Phase 3).
 *
 * Run against the spec on a bare connection: the schema does not change, so Room's half of the
 * migration is empty. The `ChatRoom` table is cut down to the columns the spec reads.
 */
class MarmotGroupBareNameMigrationJvmTest {

    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    private fun createV54() = connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `ChatRoom` (`id` TEXT NOT NULL, `userPublicKey` TEXT NOT NULL, " +
            "`subject` TEXT, `description` TEXT, `mlsGroupState` TEXT, `initialGiftWrapPayloadId` TEXT, " +
            "`leftGroupAt` INTEGER, `joinedGroupAt` INTEGER, `chronicleRequestedAt` INTEGER, " +
            "`parentChatRoomId` TEXT, `lastReadMessageId` INTEGER, " +
            "`notifications` TEXT NOT NULL DEFAULT 'all', `disappearingMessageSecs` INTEGER, " +
            "`disappearingNoticeShownAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, `viewedAt` INTEGER, " +
            "`deletedAt` INTEGER, PRIMARY KEY(`id`))"
    )

    /** A room called [subject]: a Marmot room when it has an MLS state, a NIP-17 room when not. */
    private fun insertRoom(id: String, subject: String?, marmot: Boolean, leftGroupAt: Long? = null) =
        connection.prepare(
            "INSERT INTO `ChatRoom` (`id`, `userPublicKey`, `subject`, `mlsGroupState`, `leftGroupAt`, " +
                "`createdAt`, `updatedAt`, `savedAt`) VALUES (?, 'user', ?, ?, ?, 1000, 1000, 1000)"
        ).use { statement ->
            statement.bindText(1, id)
            if (subject == null) statement.bindNull(2) else statement.bindText(2, subject)
            if (marmot) statement.bindText(3, "00") else statement.bindNull(3)
            if (leftGroupAt == null) statement.bindNull(4) else statement.bindLong(4, leftGroupAt)
            statement.step()
        }

    private fun subjectOf(id: String): String? =
        connection.prepare("SELECT `subject` FROM `ChatRoom` WHERE `id` = ?").use { statement ->
            statement.bindText(1, id)
            if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
        }

    private fun updatedAtOf(id: String): Long =
        connection.prepare("SELECT `updatedAt` FROM `ChatRoom` WHERE `id` = ?").use { statement ->
            statement.bindText(1, id)
            statement.step()
            statement.getLong(0)
        }

    private fun migrate() = runBlocking { MarmotGroupBareNameMigrationSpec().onPostMigrate(connection) }

    /** Phase 3's table, row by row. */
    @Test
    fun `every Marmot room is stored bare, and nothing else changes`() {
        createV54()
        insertRoom("ekklesia", "&Ekklesia", marmot = true)
        insertRoom("atlas", "#Atlas", marmot = true)
        insertRoom("agora", "Agora", marmot = true)
        insertRoom("delos", "&Delos", marmot = true, leftGroupAt = 2000)
        insertRoom("crafts", "&Arts & crafts", marmot = true)
        insertRoom("topic", "&topic", marmot = false)
        insertRoom("unnamed", null, marmot = true)
        insertRoom("stokvel", "#Stokvel", marmot = true)
        insertRoom("chat", "#weekend", marmot = false)

        migrate()

        assertEquals("Stokvel", subjectOf("stokvel"), "a Dedun group goes straight from #name to bare")
        assertEquals("#weekend", subjectOf("chat"), "a NIP-17 conversation named with a # keeps it")

        assertEquals("Ekklesia", subjectOf("ekklesia"))
        assertEquals("Atlas", subjectOf("atlas"))
        assertEquals("Agora", subjectOf("agora"))
        assertEquals("Delos", subjectOf("delos"), "a group the user left is still listed by name")
        assertEquals("Arts & crafts", subjectOf("crafts"), "only a leading mark is a mark")
        assertEquals("&topic", subjectOf("topic"), "a conversation keeps whatever its people called it")
        assertEquals(null, subjectOf("unnamed"), "a group with no name is not given one")
    }

    @Test
    fun `a room already bare is not rewritten`() {
        createV54()
        insertRoom("agora", "Agora", marmot = true)

        migrate()

        assertEquals("Agora", subjectOf("agora"))
        assertEquals(1000, updatedAtOf("agora"))
    }

    @Test
    fun `running it twice changes nothing the first run did not`() {
        createV54()
        insertRoom("ekklesia", "&Ekklesia", marmot = true)
        insertRoom("topic", "&topic", marmot = false)

        migrate()
        migrate()

        assertEquals("Ekklesia", subjectOf("ekklesia"))
        assertEquals("&topic", subjectOf("topic"))
    }
}
