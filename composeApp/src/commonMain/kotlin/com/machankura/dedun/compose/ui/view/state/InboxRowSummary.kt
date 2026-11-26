package com.machankura.dedun.compose.ui.view.state

/**
 * The group's inbox as its row on the group's screen shows it (docs/group-inbox.md, Phase 7).
 * Only on a device that holds the inbox key, has a ceremony running, or could start one: a
 * member who is not an admin sees nothing of the inbox (D9).
 */
data class InboxRowSummary(
    val holdsKey: Boolean,
    val unread: Int,
    val ceremonyRunning: Boolean,
    val canSetUp: Boolean,
)
