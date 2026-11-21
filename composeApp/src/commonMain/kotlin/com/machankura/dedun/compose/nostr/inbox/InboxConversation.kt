package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Crypto
import com.machankura.dedun.compose.extensions.toHex

/**
 * Which conversation a message in a group's inbox belongs to (docs/group-inbox.md, D8).
 *
 * NIP-17 defines a chat as the set of its author and its `p` tags. The group is in every one of
 * its own, so it is left out: a message from Alice to the group and the group's reply to Alice
 * are both the conversation `{Alice}`, whichever way round they were written.
 */
object InboxConversation {
    /** The people in [rumor]'s conversation other than [group], sorted and without repeats. */
    fun participantsOf(rumor: Event, group: HexKey): List<HexKey> =
        (listOf(rumor.pubKey) + rumor.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] })
            .map { it.lowercase() }
            .filter { it != group.lowercase() && InboxWire.isHex32(it) }
            .distinct()
            .sorted()

    /** A stable id for a set of [participants]: the same set gives the same id on every device. */
    fun idOf(participants: List<HexKey>): String =
        Crypto.sha256(participants.map { it.lowercase() }.distinct().sorted().joinToString(",").encodeToByteArray()).toHex()

    fun idOf(rumor: Event, group: HexKey): String = idOf(participantsOf(rumor, group))
}
