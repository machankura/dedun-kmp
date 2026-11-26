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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ExpiredGiftWrap
import com.machankura.dedun.compose.database.repository.DisappearingSettingsStore
import com.machankura.dedun.compose.nostr.DisappearingMessages

/**
 * 49 -> 50: disappearing messages' storage arrives empty, and an upgraded identity reads as the
 * one-day default without a data step. docs/disappearing-messages.md, Phase 2 and D9.
 *
 * Built from the exported v49 schema, as [ReadMarkMigrationJvmTest] builds its v47.
 */
class DisappearingMessagesMigrationJvmTest {

    private val directory: File = Files.createTempDirectory("dedun-disappearing").toFile()
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
    private val nip17Room = "a".repeat(64)
    private val payload = "b".repeat(64)

    private fun writeV49() {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            entities(49).forEach { entity ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema(49)["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 49")

            insert(connection, "NostrEvent", mapOf("id" to "e".repeat(64), "pubKey" to user))
            insert(connection, "Profile", mapOf("publicKey" to user, "nostrEventId" to "e".repeat(64)))
            insert(connection, "ChatRoom", mapOf("id" to nip17Room, "userPublicKey" to user, "mlsGroupState" to null, "initialGiftWrapPayloadId" to null))
            insert(
                connection,
                "GiftWrapPayload",
                mapOf("id" to payload, "publicKey" to user, "chatRoomId" to nip17Room, "kind" to 14L, "giftWrapSealId" to null),
            )
            insert(
                connection,
                "ChatMessage",
                mapOf(
                    "id" to 1L,
                    "chatRoomId" to nip17Room,
                    "giftWrapPayloadId" to payload,
                    "marmotGroupEventId" to null,
                    "marmotInnerEventId" to null,
                ),
            )
        }
    }

    /** A row with [values], every other NOT NULL column without a default given a placeholder. */
    private fun insert(connection: SQLiteConnection, table: String, values: Map<String, Any?>) {
        val entity = entities(49).single { it["tableName"]!!.jsonPrimitive.content == table }
        val filled = LinkedHashMap<String, Any?>()
        entity["fields"]!!.jsonArray.map { it.jsonObject }.forEach { field ->
            val column = field["columnName"]!!.jsonPrimitive.content
            val notNull = field["notNull"]?.jsonPrimitive?.booleanOrNull == true
            when {
                column in values -> filled[column] = values[column]
                notNull && field["defaultValue"] == null -> filled[column] = when (field["affinity"]!!.jsonPrimitive.content) {
                    "TEXT" -> if (column == "tags") "[]" else "x"
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
    fun `an upgraded install keeps its messages, has no expiries, and reads as Off`(): Unit = runBlocking {
        writeV49()

        val database = getRoomDatabase(Room.databaseBuilder<DedunDatabase>(name = file.absolutePath))
        try {
            val room = assertNotNull(database.chatRoomDao().findChatRoomById(nip17Room)).chatRoom
            assertNull(room.disappearingMessageSecs, "an upgraded room was given an override")
            assertNull(room.disappearingNoticeShownAt, "the one-time notice counts as shown")

            val line = assertNotNull(database.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload))
            assertNull(line.expiresAt, "a message sent before the upgrade was given an expiry")
            assertNull(assertNotNull(database.giftWrapPayloadDao().getById(payload)).expiresAt)

            val store = DisappearingSettingsStore(database)
            assertNull(database.disappearingDao().getDefault(user), "an upgrade wrote a setting")
            // Dedun's default is Off (docs/giza-second-batch.md, D11): nothing anyone writes starts
            // disappearing because they upgraded, in a new conversation or an existing one.
            assertEquals(DisappearingMessages.OFF, store.defaultFor(user))
            assertEquals(DisappearingMessages.OFF, store.policyFor(nip17Room, user))
            assertEquals(DisappearingMessages.OFF, store.defaultFor("9".repeat(64)), "a new identity")
        } finally {
            database.close()
        }
    }

    @Test
    fun `the override wins, Off is a value, and the default can change`(): Unit = runBlocking {
        writeV49()

        val database = getRoomDatabase(Room.databaseBuilder<DedunDatabase>(name = file.absolutePath))
        try {
            val store = DisappearingSettingsStore(database)
            store.saveDefault(user, DisappearingMessages.HOUR)
            assertEquals(DisappearingMessages.HOUR, store.policyFor(nip17Room, user))
            store.saveOverride(nip17Room, DisappearingMessages.OFF)
            assertEquals(DisappearingMessages.OFF, store.policyFor(nip17Room, user))
            store.saveOverride(nip17Room, null)
            assertEquals(DisappearingMessages.HOUR, store.policyFor(nip17Room, user))
        } finally {
            database.close()
        }
    }

    @Test
    fun `a tombstone lasts until its wrap would have`(): Unit = runBlocking {
        writeV49()

        val database = getRoomDatabase(Room.databaseBuilder<DedunDatabase>(name = file.absolutePath))
        try {
            val dao = database.disappearingDao()
            dao.tombstone(ExpiredGiftWrap("c".repeat(64), Instant.fromEpochSeconds(2_000)))
            assertTrue(dao.isTombstoned("c".repeat(64)))
            assertEquals(0, dao.pruneTombstones(Instant.fromEpochSeconds(1_999)))
            assertEquals(1, dao.pruneTombstones(Instant.fromEpochSeconds(2_000)))
            assertFalse(dao.isTombstoned("c".repeat(64)))
        } finally {
            database.close()
        }
    }
}
