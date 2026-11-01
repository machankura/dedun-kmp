package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Drops the nip30303 document tables, and the chat lines nothing can show any more.
 *
 * The artifact, chapter, dialect and translation screens were unreachable, and
 * their kinds shared numbers with the key ceremony's: a subgroup's round 1 (30312)
 * was read as a document submission and landed in the parent's chat as raw hex.
 * See docs/ceremony-creation.md, Phases 7 and 8.
 *
 * Manual rather than an `AutoMigration`, because it deletes chat rows as well as
 * changing shape:
 *
 *  - the lines the removed document arms wrote (`artifact`, `chapter`, `dialect`,
 *    `translationArtifactVersion`, `translation`), which nothing renders now;
 *  - the `unsupported` lines written for a ceremony's own messages. They are the
 *    raw-hex bubbles above, found by the kind of the inner event behind them, and
 *    only those: an `unsupported` line over any other kind is still the only trace
 *    an unrecognised payload leaves, and stays.
 *
 * Then the twelve tables, children before parents. No kept table has a foreign key
 * into any of them, so nothing outside them cascades; the order is so the drops
 * hold if foreign keys are ever enforced during a migration. And
 * `MarmotInnerEvent.payloadEventId`, which only a submission ever set.
 *
 * `DROP COLUMN` is safe for the reason `ChronicleRenameMigration` gives for
 * `RENAME COLUMN`: `getRoomDatabase` pins `BundledSQLiteDriver` on every platform,
 * so the SQLite underneath is one this app chose. The column has no index and is
 * in no foreign key, either of which SQLite would refuse to drop it from.
 *
 * `GroupSignedEvent` keeps any rows of the document kinds. The table is generic
 * and keyed by kind, nothing reads those kinds any more, and deleting what a group
 * signed is not this migration's call. The chronicle's column and its lines stay
 * too: the chronicle is kept, carrying nothing.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "DELETE FROM `ChatMessage` WHERE `messageType` IN " +
                "('artifact', 'chapter', 'dialect', 'translationArtifactVersion', 'translation')"
        )
        connection.execSQL(
            "DELETE FROM `ChatMessage` WHERE `messageType` = 'unsupported' " +
                "AND `marmotInnerEventId` IN (" +
                "SELECT `id` FROM `MarmotInnerEvent` WHERE `kind` BETWEEN $FIRST_CEREMONY_KIND AND $LAST_CEREMONY_KIND)"
        )

        DOCUMENT_TABLES.forEach { table -> connection.execSQL("DROP TABLE IF EXISTS `$table`") }

        connection.execSQL("ALTER TABLE `MarmotInnerEvent` DROP COLUMN `payloadEventId`")
    }
}

/**
 * The ceremony's kinds, written out rather than read from `DkgRitualEvents`: a
 * migration has to keep meaning what it meant when it shipped, whatever the
 * constants later say.
 */
private const val FIRST_CEREMONY_KIND = 30310
private const val LAST_CEREMONY_KIND = 30316

/** Children before parents. */
internal val DOCUMENT_TABLES = listOf(
    "MantraTranslationContributor",
    "MantraTranslation",
    "MantraTranslationArtifactVersionContributor",
    "MantraTranslationChapterContributor",
    "MantraTranslationChunk",
    "MantraTranslationChapter",
    "MantraTranslationArtifactVersion",
    "MantraChunk",
    "MantraChapter",
    "MantraArtifactVersion",
    "MantraArtifact",
    "MantraDialect",
)
