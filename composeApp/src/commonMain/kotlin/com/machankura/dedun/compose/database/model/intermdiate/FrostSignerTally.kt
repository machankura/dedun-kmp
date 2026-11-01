package com.machankura.dedun.compose.database.model.intermdiate

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * How many members have sent one kind of message to one signing session.
 *
 * A projection rather than an entity: there is no table of these, only a count
 * over `FrostSignerMessage` grouped by session and kind. It exists so a list of
 * a room's proposals can say how far each has got without one query per row --
 * the same reason `LocalFrostSigningSession` joins the items.
 *
 * [members] counts members and not rows, and counts only the ones who hold a
 * share of the key being signed with. Both matter: `FrostSigningManager` records
 * a nonce or a partial signature without checking who sent it -- unlike a
 * refusal, which it filters -- so the table may hold messages from members who
 * are not in the ceremony at all, and `orderedNonces` drops exactly those when
 * it aggregates. A count that included them would read ahead of the session it
 * describes, which is the one way a progress figure must never be wrong: it
 * would say the group is ready while the coordinator is still waiting.
 */
data class FrostSignerTally(
    val sessionId: String,
    val kind: Kind,
    val members: Int,
)
