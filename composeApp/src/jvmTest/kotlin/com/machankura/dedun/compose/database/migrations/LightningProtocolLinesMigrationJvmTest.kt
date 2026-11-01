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

/**
 * The v32 -> v33 migration, against a database holding the raw-JSON bubbles the Lightning
 * wallet's messages left and the lines that have to survive beside them. Built from the
 * exported v32 schema, the way [RemoveDocumentsMigrationJvmTest] builds v26.
 */
class LightningProtocolLinesMigrationJvmTest {

    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    private val schemas: File =
        (File("schemas").takeIf { it.isDirectory } ?: File("composeApp/schemas"))
            .resolve("com.machankura.dedun.compose.database.DedunDatabase")

    private fun createV32() {
        Json.parseToJsonElement(schemas.resolve("32.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .forEach { entity: JsonObject ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
    }

    private fun lines(): List<Pair<Long, String>> = connection.prepare(
        "SELECT id, messageType FROM ChatMessage ORDER BY id"
    ).use { statement ->
        buildList { while (statement.step()) add(statement.getLong(0) to statement.getText(1)) }
    }

    private fun innerEvent(id: String, kind: Int) = connection.execSQL(
        "INSERT INTO MarmotInnerEvent (id, publicKey, chatRoomId, kind, tags, content, createdAt, updatedAt, savedAt) " +
            "VALUES ('$id', 'author', 'room', $kind, '[]', '{}', 1, 1, 1)"
    )

    private fun line(id: Long, type: String, innerEventId: String? = null) = connection.execSQL(
        "INSERT INTO ChatMessage (id, senderPublicKey, isUserMessage, marmotInnerEventId, chatRoomId, " +
            "content, messageType, createdAt, updatedAt, savedAt) VALUES " +
            "($id, 'author', 0, ${innerEventId?.let { "'$it'" } ?: "NULL"}, 'room', 'x', '$type', 1, 1, 1)"
    )

    @Test
    fun `only the Lightning wallet's raw-JSON bubbles are deleted`(): Unit = runBlocking {
        createV32()

        innerEvent("proposal", kind = 30350)      // the Iceberg ceremony
        innerEvent("nonces", kind = 30360)        // co-signing
        innerEvent("snapshot", kind = 30362)      // the operator's traffic
        innerEvent("stopped", kind = 30366)
        innerEvent("statement", kind = 30355)     // signed by the group
        innerEvent("mystery", kind = 4242)        // a payload this app does not know

        line(1, "unsupported", innerEventId = "proposal")
        line(2, "unsupported", innerEventId = "nonces")
        line(3, "unsupported", innerEventId = "snapshot")
        line(4, "unsupported", innerEventId = "stopped")
        line(5, "groupLightningWalletOpened", innerEventId = "statement")
        line(6, "unsupported", innerEventId = "mystery")
        line(7, "icebergStarted")
        line(8, "message")

        MIGRATION_32_33.migrate(connection)

        assertEquals(
            listOf(5L to "groupLightningWalletOpened", 6L to "unsupported", 7L to "icebergStarted", 8L to "message"),
            lines(),
            "the protocol's bubbles go; the group's statement, an unknown payload's bubble, " +
                "the ceremony's own line and a message stay",
        )
    }
}
