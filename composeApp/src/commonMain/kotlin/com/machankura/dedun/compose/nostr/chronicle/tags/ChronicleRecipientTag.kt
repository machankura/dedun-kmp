package com.machankura.dedun.compose.nostr.chronicle.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Who a chronicle is for.
 *
 * **A hint, not access control.** The page is an ordinary group message and
 * every member can read it -- which is right, because it is their own history
 * going back to them. What this decides is who *acts*: a device that is not
 * named stores the page and applies nothing, since it already holds the work and
 * re-applying would rewrite every one of its rows to point at a chronicle page
 * rather than at the event that actually introduced it.
 *
 * So this is not a secret being kept from the group, and nothing downstream may
 * treat it as one. Encrypting a chronicle to one member was considered and
 * rejected in docs/member-chronicle.md: it protects nothing and costs a "sent a
 * private message" line per page in everyone's transcript.
 */
class ChronicleRecipientTag(
    val pubKey: HexKey,
) {
    fun toTagArray() = assemble(pubKey = pubKey)

    companion object {
        const val TAG_NAME = "p"

        fun parse(tag: Array<String>): ChronicleRecipientTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }

            return ChronicleRecipientTag(pubKey = tag[1])
        }

        fun assemble(pubKey: HexKey): Array<String> = arrayOf(TAG_NAME, pubKey)

        fun assemble(chronicleRecipientTag: ChronicleRecipientTag) =
            assemble(pubKey = chronicleRecipientTag.pubKey)
    }
}
