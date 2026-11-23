package com.machankura.dedun.compose.database.model

import com.machankura.dedun.compose.managers.MarmotGroupName

/**
 * The room's name as it is drawn: a Marmot room's [ChatRoom.subject] in braces, a NIP-17
 * room's as it is, and null when it has none.
 *
 * Every screen that shows a room's name reads it here rather than off `subject`, which
 * holds the bare name (docs/group-name-braces.md, D3). The braces are a mark of what kind
 * of room this is; they are drawn, never stored.
 */
val ChatRoom.title: String?
    get() {
        val subject = subject ?: return null
        return if (mlsGroupState != null) MarmotGroupName.display(subject) else subject
    }
