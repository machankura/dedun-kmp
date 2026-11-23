package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import com.machankura.dedun.compose.managers.MarmotGroupName

/**
 * v52, data only: every Marmot room already on the device is called by the current rule.
 *
 * A room made before docs/group-name-prefix.md is `#name` in its MLS state, and that is what
 * its Welcome wrote to `ChatRoom.subject`. Renaming it for everyone would be a commit, which
 * in a governed room is a proposal its quorum has to sign, for a change of punctuation. So
 * each device renames its own copy instead: the group's state keeps `#name`, and `subject`,
 * the only thing the app ever shows, becomes `&name`. A room joined after this has the rule
 * applied as its Welcome is processed, so nothing has to run twice.
 *
 * The rule runs here in Kotlin, through [MarmotGroupName.of], rather than as an `ltrim` in
 * SQL, so this is the same function the rest of the app names rooms with and not a copy of
 * it that can drift. A room with a bare name, from a client that marks nothing, gets the
 * mark here for the same reason a Welcome gives it one.
 *
 * Marmot rooms only -- `mlsGroupState IS NOT NULL`, which is `ChatRoomDao`'s own test -- and
 * including rooms the user has left or deleted, which are still listed by name somewhere. A
 * NIP-17 room is a conversation whose subject is whatever its people called it, `#` or not,
 * and is not touched.
 */
class MarmotGroupNameMigrationSpec : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        val renamed = connection.prepare(
            "SELECT `id`, `subject` FROM `ChatRoom` WHERE `mlsGroupState` IS NOT NULL AND `subject` IS NOT NULL"
        ).use { statement ->
            buildList {
                while (statement.step()) {
                    val subject = statement.getText(1)
                    val named = MarmotGroupName.of(subject)
                    if (named != subject) add(statement.getText(0) to named)
                }
            }
        }

        connection.prepare("UPDATE `ChatRoom` SET `subject` = ? WHERE `id` = ?").use { statement ->
            renamed.forEach { (id, subject) ->
                statement.bindText(1, subject)
                statement.bindText(2, id)
                statement.step()
                statement.reset()
            }
        }
    }
}
