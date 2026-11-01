package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Moves a signing session's per-event columns onto `FrostSigningItem`, so a
 * session can sign more than one event.
 *
 * Not an `AutoMigration`. Room can create the table and it can drop the columns,
 * but it cannot copy between them, and this migration is entirely about the copy.
 *
 * ### Why the backfill has to be exact
 *
 * A session in flight at upgrade time holds two things that cannot be
 * regenerated: `nonceRandom`, the seed its secret nonce is derived from, and
 * `aggregatedNonce`, the aggregate its partial signature is made against. Lose
 * the seed and the next pass derives a *different* nonce for the same message,
 * then publishes a second partial signature over it -- two partial signatures,
 * one message, two nonces, which is precisely how a secret share is extracted.
 * Lose the aggregate and the session republishes against a new one, with the
 * same result.
 *
 * So this copies them verbatim into item 0, and an in-flight session resumes as
 * though nothing happened. Dropping the columns and letting sessions start over
 * would have been the one dangerous way to write this migration.
 *
 * `signerIds` stays on the session: it is shared by every item of a batch, and
 * an upgraded session keeps whichever set it had chosen.
 *
 * ### Why `DROP COLUMN` rather than a table rebuild
 *
 * The usual way to remove columns in SQLite -- create a new table, copy, drop
 * the old one, rename -- is unsafe here and quietly so. `FrostSignerMessage` and
 * the new `FrostSigningItem` both reference `FrostSigningSession(id)`
 * `ON DELETE CASCADE`, and `DROP TABLE` fires cascades: with foreign keys
 * enforced it would delete every signer message and every item this migration
 * had just written. It would depend entirely on Room having turned foreign keys
 * off around the migration, which is not a thing worth depending on when there
 * is an alternative that cannot go wrong.
 *
 * `ALTER TABLE ... DROP COLUMN` is that alternative. It needs SQLite 3.35, and
 * the columns must be free of indices and constraints -- these five are, and
 * `getRoomDatabase` pins `BundledSQLiteDriver` on every platform, so the version
 * is ours rather than the host's. The table, its foreign key and both its
 * indices are left alone.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `FrostSigningItem` (" +
                "`sessionId` TEXT NOT NULL, " +
                "`itemIndex` INTEGER NOT NULL, " +
                "`unsignedEventJson` TEXT NOT NULL, " +
                "`eventId` TEXT NOT NULL, " +
                "`nonceRandom` TEXT NOT NULL, " +
                "`aggregatedNonce` TEXT, " +
                "`signature` TEXT, " +
                "PRIMARY KEY(`sessionId`, `itemIndex`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `FrostSigningSession`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_FrostSigningItem_sessionId` " +
                "ON `FrostSigningItem` (`sessionId`)"
        )

        // Every existing session signed exactly one event, so every one of them
        // becomes a batch of one at index 0.
        connection.execSQL(
            "INSERT INTO `FrostSigningItem` " +
                "(`sessionId`, `itemIndex`, `unsignedEventJson`, `eventId`, " +
                "`nonceRandom`, `aggregatedNonce`, `signature`) " +
                "SELECT `id`, 0, `unsignedEventJson`, `eventId`, " +
                "`nonceRandom`, `aggregatedNonce`, `signature` " +
                "FROM `FrostSigningSession`"
        )

        listOf(
            "unsignedEventJson",
            "eventId",
            "nonceRandom",
            "aggregatedNonce",
            "signature",
        ).forEach { column ->
            connection.execSQL("ALTER TABLE `FrostSigningSession` DROP COLUMN `$column`")
        }
    }
}
