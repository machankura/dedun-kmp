package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.machankura.dedun.compose.database.model.ChatMessage

/**
 * Records when this device joined each room, and clears the lines it wrote for
 * messages it was never able to read.
 *
 * A member added to a group is given the key schedule from their own epoch
 * forward and nothing before it. Every kind:445 the group published earlier is
 * still on the relays, still syncs down, and is still unreadable -- so the room
 * they opened for the first time led with a run of "Undecryptable Message",
 * one per message sent before they arrived, above the conversation they were
 * actually invited to.
 *
 * Two changes, one shape and one data, which is why this is a manual migration
 * rather than an `AutoMigration` Room could generate:
 *
 *  - `ChatRoom.joinedGroupAt` says when this device became a member, which is
 *    what `ChatRoom.predatesMembership` reads to leave those events alone. It
 *    is left null here rather than backfilled from `createdAt`: null already
 *    means "ask `createdAt`" -- see `ChatRoom.memberSince` -- and copying the
 *    value would turn a fallback into a claim this migration is in no position
 *    to make.
 *  - The placeholder lines already written for those events are deleted. Fixing
 *    the write path only stops the next one; nothing rewrites a line that is
 *    already in the transcript, so a member who joined last week would go on
 *    seeing their run of them forever.
 *
 * Only the two types in [ChatMessage.UNRESOLVED_MARMOT_TYPES] are deleted, and
 * only where the group event behind them predates the room. Those lines say
 * nothing by design -- they stand in for an event that was never read -- so
 * removing one loses nothing, while every other line is the final word on its
 * group event and is left alone. The group events themselves stay: this is
 * about what the room shows, not about forgetting what arrived.
 *
 * `createdAt` is compared rather than `joinedGroupAt` because the column was
 * added in this same migration and is null for every row in the database being
 * migrated. It is the same comparison `memberSince` falls back to.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `ChatRoom` ADD COLUMN `joinedGroupAt` INTEGER")

        val placeholderTypes = ChatMessage.UNRESOLVED_MARMOT_TYPES.joinToString(", ") { "'$it'" }

        connection.execSQL(
            "DELETE FROM `ChatMessage` WHERE `messageType` IN ($placeholderTypes) " +
                "AND `marmotGroupEventId` IN (" +
                "SELECT `MarmotGroupEvent`.`id` FROM `MarmotGroupEvent` " +
                "JOIN `ChatRoom` ON `ChatRoom`.`id` = `MarmotGroupEvent`.`chatRoomId` " +
                "WHERE `MarmotGroupEvent`.`createdAt` < `ChatRoom`.`createdAt`)"
        )
    }
}
