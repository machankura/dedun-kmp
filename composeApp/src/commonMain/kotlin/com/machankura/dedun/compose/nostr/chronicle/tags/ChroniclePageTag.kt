package com.machankura.dedun.compose.nostr.chronicle.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Where this page sits in its chronicle, and how many there are.
 *
 * The count is what lets a receiver say whether it holds a whole chronicle, which
 * is the only question it can answer about completeness on its own. It cannot
 * tell whether the *sender* left anything out -- see the note on omission in
 * docs/member-chronicle.md -- so this is a claim about the transfer, not about the
 * group's record.
 *
 * The index is not an ordering instruction. Pages apply in whatever order they
 * arrive and the sweep resolves what could not be applied yet, because relays
 * give no ordering guarantee and a design that needed one would be wrong on the
 * wire rather than merely slow.
 */
class ChroniclePageTag(
    val index: Int,
    val count: Int,
) {
    /** Whether this page claims to be the whole chronicle on its own. */
    fun isOnlyPage(): Boolean = count == 1

    fun toTagArray() = assemble(index = index, count = count)

    companion object {
        const val TAG_NAME = "chroniclePage"

        fun parse(tag: Array<String>): ChroniclePageTag? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val index = tag[1].toIntOrNull() ?: return null
            val count = tag[2].toIntOrNull() ?: return null

            // A page outside its own chronicle is not a page. Refused rather than
            // clamped: the pair is how a receiver decides it has everything, and
            // a repaired one would let a truncated chronicle read as complete.
            ensure(count >= 1) { return null }
            ensure(index in 0 until count) { return null }

            return ChroniclePageTag(index = index, count = count)
        }

        fun assemble(index: Int, count: Int): Array<String> =
            arrayOf(TAG_NAME, index.toString(), count.toString())

        fun assemble(chroniclePageTag: ChroniclePageTag) =
            assemble(index = chroniclePageTag.index, count = chroniclePageTag.count)
    }
}
