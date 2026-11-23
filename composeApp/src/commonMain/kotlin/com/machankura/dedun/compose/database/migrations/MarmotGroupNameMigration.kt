package com.machankura.dedun.compose.database.migrations

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import com.machankura.dedun.compose.managers.MarmotGroupName

/**
 * v56, data only: every Marmot room already on the device stores its bare name.
 *
 * Dedun's rooms were stored `#name`; `#Ekklesia` becomes `Ekklesia`, and the screens draw
 * `{Ekklesia}` (docs/group-name-braces.md; docs/giza-second-batch.md, Phase 8). Giza needed two
 * migrations, `#` to `&` and `&` to bare, because it shipped `&` in between. Dedun never stored
 * `&`, so it takes Giza's second migration's body once, and [MarmotGroupName.bare] strips
 * either mark anyway. See [renameMarmotRooms].
 */
class MarmotGroupBareNameMigrationSpec : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) = renameMarmotRooms(connection)
}

/**
 * Stores every Marmot room's subject under [MarmotGroupName.bare].
 *
 * A room made under an older rule carries its mark -- `#name`, then `&name` -- in its MLS
 * state, and that is what its Welcome wrote to `ChatRoom.subject`. Renaming it for everyone
 * would be a commit, which in a governed room is a proposal its quorum has to sign, for a
 * change of punctuation. So each device renames its own copy instead: the group's state
 * keeps what its creator wrote, and `subject` becomes the bare name the app draws in
 * braces. A room joined after this has the rule applied as its Welcome is processed.
 *
 * The rule runs here in Kotlin, through [MarmotGroupName.bare], rather than as an `ltrim`
 * in SQL, so this is the same function the rest of the app names rooms with and not a copy
 * of it that can drift. It is idempotent, so a device that runs it twice is unchanged.
 *
 * Marmot rooms only -- `mlsGroupState IS NOT NULL`, which is `ChatRoomDao`'s own test -- and
 * including rooms the user has left or deleted, which are still listed by name somewhere. A
 * NIP-17 room is a conversation whose subject is whatever its people called it, mark or
 * not, and is not touched. Nor is a confederation, which has no room and whose signed name
 * is compared byte for byte (docs/group-name-braces.md, D10).
 */
internal suspend fun renameMarmotRooms(connection: SQLiteConnection) {
    val renamed = connection.prepare(
        "SELECT `id`, `subject` FROM `ChatRoom` WHERE `mlsGroupState` IS NOT NULL AND `subject` IS NOT NULL"
    ).use { statement ->
        buildList {
            while (statement.step()) {
                val subject = statement.getText(1)
                val named = MarmotGroupName.bare(subject)
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
