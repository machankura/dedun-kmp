package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Deletes the raw-JSON bubbles the group Lightning wallet's own messages left in the chat.
 *
 * Every kind the wallet's devices send each other -- the Iceberg ceremony, the co-signing,
 * the operator's snapshots and invoices -- reached `ChatMessage.applyInnerEvent`'s last arm
 * and was written as an `unsupported` line holding the event's JSON, attributed to whoever
 * sent it. They are found the way `MIGRATION_26_27` found the ceremony's raw-hex ones: by
 * the kind of the inner event behind them. See docs/group-lightning-transcript.md, Phase 1.
 *
 * Only those. The three kinds the group signs had lines of their own and never left a
 * bubble, and an `unsupported` line over any other kind is still the only trace an
 * unrecognised payload leaves.
 *
 * No shape changes, so Room's schema for v33 is v32's.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "DELETE FROM `ChatMessage` WHERE `messageType` = 'unsupported' " +
                "AND `marmotInnerEventId` IN (" +
                "SELECT `id` FROM `MarmotInnerEvent` WHERE `kind` IN (${LIGHTNING_PROTOCOL_KINDS.joinToString()}))"
        )
    }
}

/**
 * The wallet's protocol kinds, written out rather than read from `GroupLightningEvents`: a
 * migration has to keep meaning what it meant when it shipped, whatever the constants later
 * say. 30350-30366 without 30355, 30356 and 30363, the three the group signs.
 */
private val LIGHTNING_PROTOCOL_KINDS = (30350..30366) - setOf(30355, 30356, 30363)
