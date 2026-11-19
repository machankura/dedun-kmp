package com.machankura.dedun.compose.database.builder

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL

/**
 * Opens every connection with `PRAGMA secure_delete = ON`, so content SQLite frees -- a row
 * deleted, a column overwritten with something shorter -- is zeroed in the page rather than left
 * there until the space happens to be reused.
 *
 * The secret this is for is a FROST share. `DkgSession.secretShare` sits in `aux.db` as plaintext
 * hex, and rotating a group's shares (docs/frost-share-refresh.md) ends with this device deleting
 * the old one. Without this, that delete is a promise the file does not keep: the old share stays
 * in a free block of the page, readable by anything that gets the file.
 *
 * The pragma is per connection, and Room keeps a pool of them, which is why it is set here, where
 * every connection is born, rather than once in a `RoomDatabase.Callback`: a callback sees one
 * connection, and a delete run on another would not be secure.
 *
 * It is not encryption at rest, which the database still lacks. It only makes sure that what this
 * app deletes is gone from the database file; it says nothing about what was copied out of it
 * before. See [com.machankura.dedun.compose.database.DedunDatabase.scrubFreedPages] for the write-ahead log,
 * which keeps old page images of its own.
 */
class SecureDeleteDriver(private val delegate: SQLiteDriver) : SQLiteDriver {
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        try {
            connection.execSQL("PRAGMA secure_delete = ON")
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
        return connection
    }
}
