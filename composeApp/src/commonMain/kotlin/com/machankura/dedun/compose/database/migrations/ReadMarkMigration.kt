package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v50's data half (Giza's v47): every room an upgraded install already has is read up to its newest line.
 *
 * Room generates the shape -- `ChatRoom.lastReadMessageId`, `ChatRoom.notifications` and the
 * `NotificationLedger` table -- and runs this after. Without it the column is null for every
 * existing room, null reads as "nothing read", and the first launch of this build would show the
 * user's entire history as unread. A user who had not read something before the upgrade has no
 * way to have told us so, and "everything you had is read" is the only answer that does not
 * invent one. docs/notifications-and-background-sync.md, Phase 3.
 */
class ReadMarkMigrationSpec : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(
            "UPDATE `ChatRoom` SET `lastReadMessageId` = " +
                "(SELECT MAX(`id`) FROM `ChatMessage` WHERE `ChatMessage`.`chatRoomId` = `ChatRoom`.`id`)"
        )
    }
}
