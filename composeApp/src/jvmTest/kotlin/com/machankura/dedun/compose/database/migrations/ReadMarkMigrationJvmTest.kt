package com.machankura.dedun.compose.database.migrations

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom

/**
 * 49 -> 50 (Giza's 46 -> 47): the read marks start where the history ends, so an upgrade does not make every line the
 * user has ever had unread. docs/notifications-and-background-sync.md, Phase 3.
 *
 * Built from the exported v46 schema, as [ShippedProfileUpgradeJvmTest] builds its v36, so the
 * fixture's tables and identity hash are a v46 install's.
 */
class ReadMarkMigrationJvmTest {

    private val directory: File = Files.createTempDirectory("dedun-read-mark").toFile()
    private val file: File = File(directory, "aux.db")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val schemas: File =
        (File("schemas").takeIf { it.isDirectory } ?: File("composeApp/schemas"))
            .resolve("com.machankura.dedun.compose.database.DedunDatabase")

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(schemas.resolve("$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entities(version: Int) = schema(version)["entities"]!!.jsonArray.map { it.jsonObject }

    private val user = "f".repeat(64)
    private val talkedIn = "a".repeat(64)
    private val alsoTalkedIn = "b".repeat(64)
    private val quiet = "c".repeat(64)

    private fun writeV46() {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            entities(46).forEach { entity ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema(46)["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 46")

            insert(connection, "NostrEvent", mapOf("id" to "e".repeat(64), "pubKey" to user))
            insert(connection, "Profile", mapOf("publicKey" to user, "nostrEventId" to "e".repeat(64)))
            listOf(talkedIn, alsoTalkedIn, quiet).forEach {
                insert(connection, "ChatRoom", mapOf("id" to it, "userPublicKey" to user, "mlsGroupState" to null))
            }
            listOf(talkedIn, alsoTalkedIn, talkedIn, alsoTalkedIn, talkedIn).forEachIndexed { index, room ->
                insert(
                    connection,
                    "ChatMessage",
                    mapOf(
                        "id" to (index + 1).toLong(),
                        "chatRoomId" to room,
                        "giftWrapPayloadId" to null,
                        "marmotGroupEventId" to null,
                        "marmotInnerEventId" to null,
                    ),
                )
            }
        }
    }

    /** A row with [values], every other NOT NULL column without a default given a placeholder. */
    private fun insert(connection: SQLiteConnection, table: String, values: Map<String, Any?>) {
        val entity = entities(46).single { it["tableName"]!!.jsonPrimitive.content == table }
        val filled = LinkedHashMap<String, Any?>()
        entity["fields"]!!.jsonArray.map { it.jsonObject }.forEach { field ->
            val column = field["columnName"]!!.jsonPrimitive.content
            val notNull = field["notNull"]?.jsonPrimitive?.booleanOrNull == true
            when {
                column in values -> filled[column] = values[column]
                notNull && field["defaultValue"] == null -> filled[column] = when (field["affinity"]!!.jsonPrimitive.content) {
                    "TEXT" -> "x"
                    "BLOB" -> ByteArray(0)
                    else -> 1L
                }
            }
        }
        val sql = "INSERT INTO `$table` (${filled.keys.joinToString(", ") { "`$it`" }}) " +
            "VALUES (${filled.keys.joinToString(", ") { "?" }})"
        connection.prepare(sql).use { statement ->
            filled.values.forEachIndexed { position, value ->
                val index = position + 1
                when (value) {
                    null -> statement.bindNull(index)
                    is String -> statement.bindText(index, value)
                    is ByteArray -> statement.bindBlob(index, value)
                    is Long -> statement.bindLong(index, value)
                    else -> error("no binding for a ${value::class.simpleName}")
                }
            }
            statement.step()
        }
    }

    @Test
    fun `every room an upgraded install has is read up to its newest line`(): Unit = runBlocking {
        writeV46()

        val database = getRoomDatabase(Room.databaseBuilder<DedunDatabase>(name = file.absolutePath))
        try {
            suspend fun room(id: String) = assertNotNull(database.chatRoomDao().findChatRoomById(id)).chatRoom

            assertEquals(5L, room(talkedIn).lastReadMessageId)
            assertEquals(4L, room(alsoTalkedIn).lastReadMessageId)
            assertNull(room(quiet).lastReadMessageId, "a room with no lines was given a mark")
            assertEquals(ChatRoom.NOTIFICATIONS_ALL, room(quiet).notifications)
            assertEquals(0, database.notificationDao().unreadCount(talkedIn))
            assertNull(database.notificationDao().getLedger(user), "an upgrade armed notifications by itself")
        } finally {
            database.close()
        }
    }
}
