package com.machankura.dedun.compose.database.migrations

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The v26 -> v27 migration, against a real database holding what it removes and
 * what it must leave alone.
 *
 * Two deletes and a drop. The drop is the easy half: twelve tables nothing reads
 * and a column only a submission ever set. The deletes are the half worth
 * asserting, because a transcript has no undo -- one is aimed at the lines the
 * removed document arms wrote, and the other at the raw-hex bubbles a
 * subgroup's ceremony left in its parent's chat, found by the kind of the inner
 * event behind them. An `unsupported` line over any other kind is still the only
 * trace an unrecognised payload leaves, and has to survive.
 *
 * Built from the exported schemas rather than copied SQL, so the tables are
 * exactly the ones a v26 install has, and the result is compared with what Room
 * expects of v27. Run on a bare connection, the way the other migration tests
 * are: Room's version wiring belongs to `PlatformDatabaseBuilder`.
 */
class RemoveDocumentsMigrationJvmTest {

    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    private val schemas: File =
        (File("schemas").takeIf { it.isDirectory } ?: File("composeApp/schemas"))
            .resolve("com.machankura.dedun.compose.database.DedunDatabase")

    private fun entities(version: Int): List<JsonObject> =
        Json.parseToJsonElement(schemas.resolve("$version.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }

    private fun JsonObject.tableName() = this["tableName"]!!.jsonPrimitive.content

    private fun JsonObject.columns() =
        this["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()

    /** Every v26 table, as Room created it. */
    private fun createV26() {
        entities(26).forEach { entity ->
            val sql = entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", entity.tableName())
            connection.execSQL(sql)
        }
    }

    private fun tables(): Set<String> = connection.prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table'"
    ).use { statement ->
        buildSet { while (statement.step()) add(statement.getText(0)) }
    }

    private fun columnsOf(table: String): Set<String> = connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
        buildSet { while (statement.step()) add(statement.getText(1)) }
    }

    private fun lines(): List<Pair<Long, String>> = connection.prepare(
        "SELECT id, messageType FROM ChatMessage ORDER BY id"
    ).use { statement ->
        buildList { while (statement.step()) add(statement.getLong(0) to statement.getText(1)) }
    }

    private fun innerEvent(id: String, kind: Int) = connection.execSQL(
        "INSERT INTO MarmotInnerEvent (id, publicKey, chatRoomId, kind, tags, content, " +
            "payloadEventId, createdAt, updatedAt, savedAt) VALUES " +
            "('$id', 'author', 'room', $kind, '[]', 'content', NULL, 1, 1, 1)"
    )

    private fun line(id: Long, type: String, innerEventId: String? = null) = connection.execSQL(
        "INSERT INTO ChatMessage (id, senderPublicKey, isUserMessage, marmotInnerEventId, chatRoomId, " +
            "content, messageType, createdAt, updatedAt, savedAt) VALUES " +
            "($id, 'author', 0, ${innerEventId?.let { "'$it'" } ?: "NULL"}, 'room', 'x', '$type', 1, 1, 1)"
    )

    @Test
    fun `the document tables and the submission column are gone, as Room expects of v27`(): Unit = runBlocking {
        createV26()
        connection.execSQL(
            "INSERT INTO MantraDialect (id, publicKey, name, country, language, signature, chatRoomId, " +
                "createdAt, updatedAt) VALUES ('d', 'p', 'isiZulu', 'ZA', 'zu', 's', 'room', 1, 1)"
        )

        MIGRATION_26_27.migrate(connection)

        val expected = entities(27).associate { it.tableName() to it.columns() }

        assertTrue(tables().none { it.startsWith("Mantra") }, "every document table is dropped")
        assertEquals(
            expected.keys,
            tables().filterNot { it.startsWith("sqlite_") || it == "room_master_table" }.toSet(),
            "and every other table is still there",
        )
        assertFalse("payloadEventId" in columnsOf("MarmotInnerEvent"))
        assertEquals(expected["MarmotInnerEvent"], columnsOf("MarmotInnerEvent"))
        assertTrue(
            "chronicleRequestedAt" in columnsOf("ChatRoom"),
            "the chronicle is kept, and so is its column",
        )
    }

    @Test
    fun `only the lines nothing can show any more are deleted`(): Unit = runBlocking {
        createV26()

        innerEvent("round1", kind = 30312)  // a subgroup's round 1, read as a submission
        innerEvent("proposal", kind = 30310)
        innerEvent("mystery", kind = 4242)  // a payload this app does not know

        line(1, "translation")
        line(2, "dialect")
        line(3, "unsupported", innerEventId = "round1")
        line(4, "unsupported", innerEventId = "proposal")
        line(5, "unsupported", innerEventId = "mystery")
        line(6, "unsupported")
        line(7, "message")
        line(8, "chronicleReceived")

        MIGRATION_26_27.migrate(connection)

        assertEquals(
            listOf(5L to "unsupported", 6L to "unsupported", 7L to "message", 8L to "chronicleReceived"),
            lines(),
            "a ceremony's raw-hex bubbles and the document lines go; an unknown payload's " +
                "bubble, a message and the chronicle's line stay",
        )
    }
}
