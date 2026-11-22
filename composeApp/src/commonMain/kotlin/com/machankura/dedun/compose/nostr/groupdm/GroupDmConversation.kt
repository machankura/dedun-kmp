package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Crypto
import com.machankura.dedun.compose.extensions.toHex

/**
 * Which of the group's conversations a message belongs to. NIP-17 defines a chat as the set of
 * its author and its `p` tags; the group is in every one of its own, so it is left out, and
 * what remains is the group's correspondents.
 */
object GroupDmConversation {
    fun participantsOf(rumor: Event, group: HexKey): List<HexKey> =
        (listOf(rumor.pubKey) + rumor.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] })
            .map { it.lowercase() }
            .filter { it != group.lowercase() && it.length == 64 }
            .distinct()
            .sorted()

    /** A hash of the sorted participants: the same conversation on every admin's device. */
    fun idOf(rumor: Event, group: HexKey): HexKey = idOf(participantsOf(rumor, group))

    fun idOf(participants: Collection<HexKey>): HexKey =
        Crypto.sha256(participants.map { it.lowercase() }.distinct().sorted().joinToString(",").encodeToByteArray()).toHex()
}
