package com.machankura.dedun.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.Profile

/**
 * One signing session with everything a list of them has to show.
 *
 * A proposal is not readable from its session row alone: the row says how the
 * signing is going, and the items say what is being signed. A screen listing a
 * room's proposals needs both of every one of them, and asking per session would
 * be one query per row.
 *
 * The proposer is joined for the same reason the transcript joins a sender --
 * so a rename follows, and so a member who has only ever been seen as a pubkey
 * is not stuck on the placeholder a profile is created with.
 */
data class LocalFrostSigningSession(
    @Embedded val session: FrostSigningSession,

    /**
     * The events the session signs.
     *
     * Room does not order a relation, so this comes back in whatever order the
     * query returns. Sorting is [items]'s job: item order is protocol, not
     * presentation -- see [FrostSigningItem.itemIndex].
     */
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["sessionId"]
    )
    val unorderedItems: List<FrostSigningItem> = emptyList(),

    @Relation(
        parentColumns = ["coordinatorPublicKey"],
        entityColumns = ["publicKey"]
    )
    val proposer: Profile? = null,
) {
    /** The batch in the order its proposal fixed. */
    val items: List<FrostSigningItem> get() = unorderedItems.sortedBy { it.itemIndex }
}
