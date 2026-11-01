package com.machankura.dedun.compose.database.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The v9 -> v10 backfill, against a real database holding a real half-finished
 * session.
 *
 * The stake is higher than a migration test usually carries. A session in flight
 * at upgrade time holds `nonceRandom`, the seed its secret nonce is regenerated
 * from, and `aggregatedNonce`, the aggregate its partial signature is made
 * against. If either fails to arrive at item 0, the session's next pass derives a
 * *different* nonce for the same message and publishes a second partial signature
 * over it -- two partial signatures, one message, two nonces, which is how a
 * secret share is extracted. So this asserts the values, not merely that a row
 * appeared.
 *
 * Run against the migration's own SQL on a bare connection rather than through
 * Room. That covers the copy and the shape it leaves behind, which is what this
 * migration is; it does not cover Room's version wiring, which belongs to
 * `PlatformDatabaseBuilder` and is the same for every migration in that list.
 */
class FrostSigningItemMigrationJvmTest {

    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    /** v9's `FrostSigningSession`, verbatim from `schemas/9.json`. */
    private fun createV9() {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `FrostSigningSession` (`id` TEXT NOT NULL, " +
                "`chatRoomId` TEXT NOT NULL, `coordinatorPublicKey` TEXT NOT NULL, " +
                "`userPublicKey` TEXT NOT NULL, `dkgSessionId` TEXT NOT NULL, " +
                "`threshold` INTEGER NOT NULL, `participantCount` INTEGER NOT NULL, " +
                "`signerId` INTEGER NOT NULL, `derivationPath` TEXT, `stage` TEXT NOT NULL, " +
                "`unsignedEventJson` TEXT NOT NULL, `eventId` TEXT NOT NULL, " +
                "`nonceRandom` TEXT NOT NULL, `aggregatedNonce` TEXT, `signerIds` TEXT, " +
                "`signature` TEXT, `failureReason` TEXT, `signApprovedAt` INTEGER, " +
                "`approvalRequestedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`chatRoomId`) REFERENCES `ChatRoom`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_FrostSigningSession_chatRoomId` " +
                "ON `FrostSigningSession` (`chatRoomId`)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_FrostSigningSession_dkgSessionId` " +
                "ON `FrostSigningSession` (`dkgSessionId`)"
        )
    }

    /** A session mid-flight: it has its seed, and the aggregate it is signing against. */
    private fun insertV9Session(
        id: String = "s1",
        aggregatedNonce: String? = null,
        signature: String? = null,
    ) = connection.execSQL(
        "INSERT INTO `FrostSigningSession` VALUES (" +
            "'$id', 'room', 'coord', 'user', 'dkg-1', 2, 3, 1, 'm/9420/0/0', 'COLLECTING_NONCES', " +
            "'$unsignedEventJson', '$eventId', '$nonceRandom', " +
            "${aggregatedNonce.orNull()}, '0,1', ${signature.orNull()}, " +
            "NULL, 1, 1, 1000, 1000, 1000)"
    )

    private fun String?.orNull(): String = this?.let { "'$it'" } ?: "NULL"

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

    private val unsignedEventJson = """{"kind":1}"""
    private val eventId = "ff".repeat(32)
    private val nonceRandom = "aa".repeat(32)
    private val aggregatedNonce = "bb".repeat(66)
    private val signature = "cc".repeat(32)

    @Test
    fun `an in-flight session keeps the seed and the aggregate it is already signing against`() =
        runBlocking {
            createV9()
            insertV9Session(aggregatedNonce = aggregatedNonce)

            MIGRATION_9_10.migrate(connection)

            // The two that cannot be regenerated. Losing either turns this
            // session's next pass into a second partial signature over one message.
            assertEquals(nonceRandom, read("FrostSigningItem", "nonceRandom"))
            assertEquals(aggregatedNonce, read("FrostSigningItem", "aggregatedNonce"))

            assertEquals("0", read("FrostSigningItem", "itemIndex"))
            assertEquals(eventId, read("FrostSigningItem", "eventId"))
            assertEquals(unsignedEventJson, read("FrostSigningItem", "unsignedEventJson"))
        }

    @Test
    fun `a finished session keeps its signature`() = runBlocking {
        createV9()
        insertV9Session(aggregatedNonce = aggregatedNonce, signature = signature)

        MIGRATION_9_10.migrate(connection)

        assertEquals(signature, read("FrostSigningItem", "signature"))
    }

    /** A session that had not been aggregated yet arrives with nothing invented for it. */
    @Test
    fun `a session still collecting nonces gets an item with no aggregate`() = runBlocking {
        createV9()
        insertV9Session()

        MIGRATION_9_10.migrate(connection)

        assertEquals(null, read("FrostSigningItem", "aggregatedNonce"))
        assertEquals(null, read("FrostSigningItem", "signature"))
        assertEquals(nonceRandom, read("FrostSigningItem", "nonceRandom"))
    }

    /**
     * The shape Room checks on the next open. A column left behind, or one
     * missing, is refused at open time -- a crash on launch rather than a bug
     * anybody gets to debug.
     */
    @Test
    fun `the tables are left with exactly the columns v10 declares`() = runBlocking {
        createV9()
        insertV9Session()

        MIGRATION_9_10.migrate(connection)

        assertEquals(
            listOf(
                "id", "chatRoomId", "coordinatorPublicKey", "userPublicKey", "dkgSessionId",
                "threshold", "participantCount", "signerId", "derivationPath", "stage",
                "signerIds", "failureReason", "signApprovedAt", "approvalRequestedAt",
                "createdAt", "updatedAt", "savedAt",
            ),
            columns("FrostSigningSession")
        )
        assertEquals(
            listOf(
                "sessionId", "itemIndex", "unsignedEventJson", "eventId",
                "nonceRandom", "aggregatedNonce", "signature",
            ),
            columns("FrostSigningItem")
        )
    }

    /** `signerIds` is shared by every item of a batch, so it stays where it was. */
    @Test
    fun `the signer set stays on the session`() = runBlocking {
        createV9()
        insertV9Session()

        MIGRATION_9_10.migrate(connection)

        assertEquals("0,1", read("FrostSigningSession", "signerIds"))
    }

    /** An empty table migrates to an empty table rather than to one bad row. */
    @Test
    fun `a database with no sessions gets no items`() = runBlocking {
        createV9()

        MIGRATION_9_10.migrate(connection)

        assertEquals(null, read("FrostSigningItem", "sessionId"))
    }
}
