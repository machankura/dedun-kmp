package com.machankura.dedun.compose.ui.view.state

/**
 * What a group's screen says about its direct messages (docs/group-threshold-ecdh.md,
 * Phases 6 and 8). Null when there is nothing to say: the feature off, or no key.
 */
sealed interface GroupDmRow {
    /** A current admin, and the group has not signed where it receives messages. */
    data object OpenMessages : GroupDmRow

    /** A current admin of a group that receives messages: the way in, with what is unread. */
    data class Messages(val unread: Int) : GroupDmRow

    /** Anyone else, where the group's 10050 is signed: it is public, and nothing more is. */
    data object ReceivesMessages : GroupDmRow
}
