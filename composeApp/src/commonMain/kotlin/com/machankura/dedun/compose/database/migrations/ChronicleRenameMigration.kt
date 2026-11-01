package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.machankura.dedun.compose.database.model.ChatMessage

/**
 * Renames the group's signed record from "archive" to "chronicle", everywhere the
 * old word had been written down.
 *
 * The word was needed back. Archiving a chat is an ordinary thing a user does to
 * a conversation, and the group's signed record is not that -- it is what a
 * member who joined after the work was done is handed so their room stops being
 * empty. Two unrelated meanings of one word in one app is a bug waiting to be
 * written, and `ChatRoom.archiveRequestedAt` is precisely where they would have
 * met: a column on the chat row, named for the thing that is not the chat.
 *
 * Two changes, one shape and one data, which is why this is a manual migration
 * rather than a `@RenameColumn` spec Room could generate:
 *
 *  - `ChatRoom.archiveRequestedAt` becomes `chronicleRequestedAt`. Renamed rather
 *    than dropped and re-added, because the value is load-bearing while it is
 *    set: it is the only thing stopping a device with an empty room asking the
 *    group for its history on every launch. A device that dropped it mid-flight
 *    would ask again on the next start, and again after that.
 *  - The three `ChatMessage.messageType` strings become their `chronicle*`
 *    spellings. Rewritten rather than left to a legacy constant, because these
 *    lines cannot be regenerated -- a chronicle is announced once, when it is
 *    requested, sent or applied -- and an unrecognised type does not render as
 *    nothing. It renders as a chat bubble, so "Caught up on 12 items" would come
 *    back attributed to a member as something they said.
 *
 * Matched on the exact old strings rather than a `LIKE`, because this app wrote
 * all three of them one version ago and knows what they were. Anything else in
 * the column is left alone.
 *
 * `ALTER TABLE ... RENAME COLUMN` needs SQLite 3.25, which `getRoomDatabase`
 * guarantees by pinning `BundledSQLiteDriver` on every platform. The column is in
 * no index and no foreign key -- see the v13 schema -- so nothing else in the
 * table has to move with it.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `ChatRoom` RENAME COLUMN `archiveRequestedAt` TO `chronicleRequestedAt`"
        )

        mapOf(
            "archiveRequested" to ChatMessage.TYPE_CHRONICLE_REQUESTED,
            "archiveSent" to ChatMessage.TYPE_CHRONICLE_SENT,
            "archiveReceived" to ChatMessage.TYPE_CHRONICLE_RECEIVED,
        ).forEach { (oldType, newType) ->
            connection.execSQL(
                "UPDATE `ChatMessage` SET `messageType` = '$newType' " +
                    "WHERE `messageType` = '$oldType'"
            )
        }
    }
}
