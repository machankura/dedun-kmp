package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

/**
 * What follows an inbox key ceremony on the device that coordinated it (docs/group-inbox.md,
 * D5 and D6): proposing the group's announcement of the key it made.
 */
object InboxFollowThrough {
    /** Called once, on every participant's device, when [ceremony] has made its key. */
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun onKeyMade(database: DedunDatabase, localChatRoom: LocalChatRoom, ceremony: InboxKeyCeremony) = Unit
}
