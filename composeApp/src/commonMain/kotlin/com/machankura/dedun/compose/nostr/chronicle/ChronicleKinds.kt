package com.machankura.dedun.compose.nostr.chronicle

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The kinds a chronicle may carry, in the order they have to be applied.
 *
 * One list doing both jobs, because a separate allowlist is one more thing that
 * can disagree with the order it is applied in. The order is the database's, not
 * nostr's: where one kind's rows hang off another's, the one they hang off comes
 * first, so a page applies front to back.
 *
 * **It is an allowlist first.** Verification admits an event to the apply path on
 * the strength of the group's signature, which makes every kind the group has
 * ever signed replayable by any member at any time. A `GroupKeyStateEvent` is
 * group-signed and passes verification perfectly, so a chronicle carrying an old
 * one is a validly signed statement about what the room signs with, replayed by
 * whoever kept a copy. Nothing but this list stops it.
 *
 * A value rather than a constant so the machinery can be exercised without
 * making anything chroniclable in the app: the app's own list is
 * [ChronicleEvent.KINDS], and the tests hand in theirs.
 */
class ChronicleKinds(private val applyOrder: List<Kind>) {

    /** Every kind a chronicle may carry. */
    val all: Set<Kind> = applyOrder.toSet()

    /** A chronicle that may carry nothing asks for nothing and sends nothing. */
    fun isEmpty(): Boolean = applyOrder.isEmpty()

    /** Where [kind] sits in apply order, or null if a chronicle may not carry it at all. */
    fun applyRank(kind: Kind): Int? = applyOrder.indexOf(kind).takeIf { it >= 0 }

    fun isChroniclable(kind: Kind): Boolean = kind in all

    /**
     * [payloads] in apply order, so a page applies front to back.
     *
     * Stable within a rank: two events of one kind have no order between them,
     * and keeping the caller's is one less thing that varies between two members
     * assembling the same chronicle.
     */
    fun inApplyOrder(payloads: List<Event>): List<Event> =
        payloads.sortedBy { applyRank(it.kind) ?: Int.MAX_VALUE }

    companion object {
        val NONE = ChronicleKinds(emptyList())
    }
}
