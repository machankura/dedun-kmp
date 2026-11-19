package com.machankura.dedun.compose.database.migrations

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.vitorpamplona.marmot.groups.currentMarmotData
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotSenderRatchet
import com.machankura.dedun.compose.extensions.exporterSecret
import com.machankura.dedun.compose.managers.OldEngineBlob

/**
 * A profile written by the app as it ships, opened by the app as this branch builds it.
 *
 * `MlsGroupStateCompatibilityTest` is the blob on its own: bytes quartz 1.15.2 wrote,
 * handed straight to mls-kmp. This is the same bytes where a user's are -- in a
 * database at schema 45, which is what every install of Dedun before the Marmot move is at -- and
 * the thing being opened is the app's own `DedunDatabase`, through the builder the app
 * uses, rather than a decoder in a test. Phase 8 added a table, so this branch is the
 * first in a while whose upgrade can fail for a reason that has nothing to do with MLS.
 *
 * It is named for what it opens rather than for a migration, because 45 -> 46 is an
 * `AutoMigration` and there is no `MIGRATION_45_46` to call: Room generates it, and
 * the only way to run the generated one is to let Room decide it is needed. That is
 * also the failure this catches. An entity added to the `@Database` list without an
 * `AutoMigration` beside it builds, passes every test over an in-memory database, and
 * installs perfectly on a phone that has never had the app -- and throws
 * `IllegalStateException: A migration from 45 to 46 was required but not found` on
 * every phone that has.
 *
 * The v45 database is built from the exported schema rather than from copied SQL, so
 * its tables, indices and identity hash are exactly a v45 install's, which is the same
 * argument the other migration tests make. (Giza's 37 and Bennu's 36 are this 46: docs/giza-into-dedun.md, "The schema numbers".)
 *
 * What it does not cover is a real profile's accumulated rows, and where the file sits:
 * `PlatformDatabaseBuilder` picks the path per target and this picks a temporary one.
 * The engine under it is the same everywhere -- `getRoomDatabase` sets
 * `BundledSQLiteDriver()` in common code, so Android, iOS and desktop all migrate
 * through the SQLite this test migrates through. docs/marmot-kmp.md, Phase 9.
 */
class ShippedProfileUpgradeJvmTest {

    /** The schema version on `dedun`, which is the one every existing install is at. */
    private val shipped = 45

    /** The schema version this branch builds. */
    private val current = 46

    private val directory: File = Files.createTempDirectory("dedun-shipped-profile").toFile()
    private val file: File = File(directory, "aux.db")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val schemas: File =
        (File("schemas").takeIf { it.isDirectory } ?: File("composeApp/schemas"))
            .resolve("com.machankura.dedun.compose.database.DedunDatabase")

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(schemas.resolve("$version.json").readText())
            .jsonObject["database"]!!.jsonObject

    private fun entities(version: Int): List<JsonObject> =
        schema(version)["entities"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.tableName() = this["tableName"]!!.jsonPrimitive.content

    private fun JsonObject.columns() =
        this["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()

    private fun JsonObject.indexNames() =
        this["indices"]?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    // -- the v45 profile -------------------------------------------------------------

    private val userPublicKey = "f".repeat(64)
    private val profileEventId = "e".repeat(64)

    /** A room with no MLS state of its own, so the engine has to pick between two. */
    private val plainRoomId = "b".repeat(64)

    /**
     * The database a v45 install has: its own schema, its own `room_master_table`
     * identity hash and its own `user_version`, which together are what make Room
     * treat the file as a v45 database rather than a corrupt one.
     */
    private fun writeShippedProfile() {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            entities(shipped).forEach { entity ->
                val table = entity.tableName()
                connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema(shipped)["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = $shipped")

            insert(connection, "NostrEvent", mapOf("id" to profileEventId, "pubKey" to userPublicKey))
            insert(connection, "Profile", mapOf("publicKey" to userPublicKey, "nostrEventId" to profileEventId))
            insert(
                connection,
                "ChatRoom",
                mapOf(
                    "id" to OldEngineBlob.ROOM_ID,
                    "userPublicKey" to userPublicKey,
                    "subject" to "Fixture",
                    "mlsGroupState" to OldEngineBlob.HEX,
                ),
            )
            insert(
                connection,
                "ChatRoom",
                mapOf("id" to plainRoomId, "userPublicKey" to userPublicKey, "mlsGroupState" to null),
            )
        }
    }

    /**
     * A row in [table] carrying [values], every other NOT NULL column without a default
     * given a placeholder of its affinity. Bound rather than interpolated, so a BLOB
     * column gets bytes instead of the text of a number.
     */
    private fun insert(connection: SQLiteConnection, table: String, values: Map<String, Any?>) {
        val entity = entities(shipped).single { it.tableName() == table }
        val filled = LinkedHashMap<String, Any?>()
        entity["fields"]!!.jsonArray.map { it.jsonObject }.forEach { field ->
            val column = field["columnName"]!!.jsonPrimitive.content
            val notNull = field["notNull"]?.jsonPrimitive?.booleanOrNull == true
            val hasDefault = field["defaultValue"] != null
            when {
                column in values -> filled[column] = values[column]
                notNull && !hasDefault -> filled[column] = when (field["affinity"]!!.jsonPrimitive.content) {
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
                    is Int -> statement.bindLong(index, value.toLong())
                    else -> error("no binding for a ${value::class.simpleName}")
                }
            }
            statement.step()
        }
    }

    // -- opening it ------------------------------------------------------------------

    /**
     * The app's own builder over that file -- the migrations the app ships, and no
     * others -- opened, used and closed.
     *
     * Room opens lazily: `build()` returns a database that has not touched the file,
     * and the migration runs on the first query. So [use] has to ask it something, and
     * a test that only built and closed one would pass against a file nothing had
     * migrated.
     */
    private suspend fun <T> opened(use: suspend (DedunDatabase) -> T): T {
        val database = getRoomDatabase(Room.databaseBuilder<DedunDatabase>(name = file.absolutePath))
        return try {
            use(database)
        } finally {
            database.close()
        }
    }

    private fun <T> onDisk(read: (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(file.absolutePath).use(read)

    private fun SQLiteConnection.tables(): Set<String> =
        prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(0)) }
        }.filterNot { it.startsWith("sqlite_") || it == "room_master_table" }.toSet()

    private fun SQLiteConnection.columnsOf(table: String): Set<String> =
        prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(1)) }
        }

    private fun SQLiteConnection.indexNamesOf(table: String): Set<String> =
        prepare("PRAGMA index_list(`$table`)").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(1)) }
        }.filter { it.startsWith("index_") }.toSet()

    private fun SQLiteConnection.userVersion(): Long =
        prepare("PRAGMA user_version").use { statement -> statement.step(); statement.getLong(0) }

    private fun SQLiteConnection.identityHash(): String =
        prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
            statement.step()
            statement.getText(0)
        }

    // -- what it has to prove --------------------------------------------------------

    /**
     * Room finds the way from 45 to 46, runs it, and stamps the file with the hash the
     * generated code expects. The hash is the assertion that matters: Room writes it
     * only after `validateMigration` has compared every table it was handed against the
     * schema the entities declare.
     */
    @Test
    fun `a v45 profile opens at v46, with the table this branch adds`(): Unit = runBlocking {
        writeShippedProfile()
        onDisk { connection ->
            assertEquals(shipped.toLong(), connection.userVersion(), "the fixture is not at the shipped version")
            assertTrue("MarmotSenderRatchet" !in connection.tables(), "the fixture already has the new table")
        }

        opened { database ->
            assertTrue(database.chatRoomDao().exists(OldEngineBlob.ROOM_ID), "the room is gone after the upgrade")
        }

        onDisk { connection ->
            assertEquals(current.toLong(), connection.userVersion(), "the file was not moved to the new version")
            assertEquals(
                schema(current)["identityHash"]!!.jsonPrimitive.content,
                connection.identityHash(),
                "Room did not stamp the v37 identity hash, so it did not validate the migrated schema",
            )
            val expected = entities(current).associateBy { it.tableName() }
            assertEquals(expected.keys, connection.tables(), "the migrated file has a different set of tables")
            expected.forEach { (table, entity) ->
                assertEquals(entity.columns(), connection.columnsOf(table), "the columns of $table")
                assertEquals(entity.indexNames(), connection.indexNamesOf(table), "the indices of $table")
            }
        }
    }

    /**
     * And the room in it is still a room. The blob is read back out of the migrated
     * file, restored by the engine that replaced the one that wrote it, and asked for
     * the two things the app needs from a group before it can show a message: the
     * MIP-01 metadata, and the exporter secret the kind:445 wrapper is sealed with.
     */
    @Test
    fun `the legacy room survives the upgrade and the engine opens it`(): Unit = runBlocking {
        writeShippedProfile()

        opened { database ->
            assertEquals(
                OldEngineBlob.HEX,
                database.chatRoomDao().getMlsGroupState(OldEngineBlob.ROOM_ID),
                "the room's state is not the bytes that were in the file",
            )

            val group = assertNotNull(
                database.marmotEngine.groupFor(OldEngineBlob.ROOM_ID),
                "the engine holds no group for a room that has state on disk",
            )
            val data = assertNotNull(group.currentMarmotData(), "the 0xF2EE extension did not survive the upgrade")
            assertEquals(OldEngineBlob.ROOM_ID, data.nostrGroupId, "the room's address changed")
            assertEquals(32, group.exporterSecret().size, "the group-event exporter secret is the wrong length")

            assertNull(
                database.marmotEngine.groupFor(plainRoomId),
                "a room with no state came back with a group",
            )
        }
    }

    /**
     * An upgrade does not rewrite the rooms it finds.
     *
     * Restoring a group re-derives its key schedule and could just as well save the
     * result at the current state version, and the first thing that persists each room
     * will do exactly that (`MlsGroupStateCompatibilityTest` is that re-save on its
     * own). Doing it to every room at once, on the first launch after an upgrade, is a
     * different thing: it is the one moment when every room's only copy is being
     * rewritten, and a crash in the middle of it has no floor under it. So the claim
     * here is the absence of a write.
     */
    @Test
    fun `opening the profile leaves the rooms it restored as they were`(): Unit = runBlocking {
        writeShippedProfile()

        opened { database ->
            database.marmotEngine.ensureRestored()
            // Without this the test asserts the absence of a write by an engine that
            // may be holding nothing: `groupFor` would load the room from its row on
            // demand, so only the restore itself proves the room was picked up.
            assertNotNull(
                database.marmotEngine.groupManager.snapshot(OldEngineBlob.ROOM_ID),
                "the restore did not pick the room up, so there is no write for this test to be about",
            )
        }

        onDisk { connection ->
            val stored = connection.prepare("SELECT mlsGroupState FROM ChatRoom WHERE id = ?").use { statement ->
                statement.bindText(1, OldEngineBlob.ROOM_ID)
                statement.step()
                statement.getText(0)
            }
            assertEquals(
                OldEngineBlob.HEX,
                stored,
                "a restore rewrote the room's state; the bytes on disk are no longer the ones quartz 1.15.2 wrote",
            )
        }
    }

    /**
     * The new table's foreign key points at `ChatRoom`, which is a table that was
     * already there with rows in it -- the one direction an added table can be wrong
     * in. A row keyed to a room that predates the table has to be insertable, and
     * readable back.
     */
    @Test
    fun `the added table takes a row keyed to a room that predates it`(): Unit = runBlocking {
        writeShippedProfile()

        opened { database ->
            val dao = database.marmotSenderRatchetDao()
            assertNull(dao.forChatRoomId(OldEngineBlob.ROOM_ID), "the new table arrived with a row in it")

            dao.upsert(
                MarmotSenderRatchet(
                    chatRoomId = OldEngineBlob.ROOM_ID,
                    epoch = 0L,
                    leafIndex = 0,
                    applicationGeneration = 0,
                    record = ByteArray(8) { it.toByte() },
                )
            )

            val row = assertNotNull(dao.forChatRoomId(OldEngineBlob.ROOM_ID), "the row did not come back")
            assertContentEquals(ByteArray(8) { it.toByte() }, row.record, "the record came back as different bytes")
        }
    }
}
