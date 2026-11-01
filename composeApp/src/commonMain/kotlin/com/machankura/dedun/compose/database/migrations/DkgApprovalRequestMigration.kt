package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.machankura.dedun.compose.database.model.ChatMessage

/**
 * Splits the single `dkgApprovalNeeded` chat line into one type per ritual step.
 *
 * The rows carried no step, so every request rendered with the same icon and a
 * reader could not tell "join the ceremony" from "confirm the key". The type is
 * the only thing the transcript keeps — a line drawn days later has no session to
 * ask — so the step had to move into it.
 *
 * Rewriting the existing rows rather than leaving them to the renderer's fallback,
 * because they cannot be regenerated: a request is announced once, guarded by
 * `DkgSession.approvalRequestedThrough`, so a ritual already in flight will never
 * write its request lines again. Without this, any ceremony started before the
 * split keeps its undifferentiated icons forever.
 *
 * Matched on content because that is the only thing distinguishing them, and the
 * three strings were written by this app one version ago. A row that matches none
 * of them keeps the old type, which the renderer still recognises — see
 * [ChatMessage.TYPE_DKG_APPROVAL_NEEDED_LEGACY]. This changes no schema; the
 * version bump exists only to give the data rewrite somewhere to run.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        suspend fun rewrite(newType: String, contentPrefix: String) {
            connection.execSQL(
                "UPDATE ChatMessage SET messageType = '$newType' " +
                    "WHERE messageType = '${ChatMessage.TYPE_DKG_APPROVAL_NEEDED_LEGACY}' " +
                    "AND content LIKE '$contentPrefix%'"
            )
        }

        rewrite(
            ChatMessage.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY,
            "Your approval is needed to join the shared key ceremony."
        )
        rewrite(
            ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_1,
            "Everyone has joined the shared key ceremony."
        )
        rewrite(
            ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_2,
            "The contributions have been combined."
        )
    }
}
