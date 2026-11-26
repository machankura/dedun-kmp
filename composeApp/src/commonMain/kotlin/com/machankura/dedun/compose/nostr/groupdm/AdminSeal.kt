package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.bitcoin.PrivateKey
import com.machankura.dedun.compose.extensions.toHex

/**
 * Something only the group's current admins may read, inside a room every member receives
 * (D4 and D12 of docs/group-threshold-ecdh.md).
 *
 * The plaintext is NIP-44-encrypted once, under a fresh 32-byte key used as the conversation
 * key, and only that key is sealed to each admin's ChillDKG host key over the share-repair
 * channel. Phase 0's check 6 is why: sealing the whole plaintext per admin multiplied a batch
 * of partials by the number of admins, past a relay's event.
 *
 * A host key is the 33-byte compressed one the roster carries, as 66 hex; NIP-44's ECDH reads
 * only its x-coordinate.
 */
object AdminSeal {
    private val nip44 = Nip44v2()

    /** [content] for everyone; [slots] the content key, sealed per admin host key. */
    data class Sealed(val content: String, val slots: Map<HexKey, String>)

    fun seal(
        plaintext: String,
        senderHostSecret: PrivateKey,
        recipientHostKeys: Collection<HexKey>,
        contentKey: ByteArray = RandomInstance.bytes(32),
    ): Sealed {
        require(contentKey.size == 32) { "a content key is 32 bytes" }
        val content = nip44.encrypt(plaintext, contentKey).encodePayload()
        val slots = recipientHostKeys.distinct().associateWith { host ->
            nip44.encrypt(contentKey.toHex(), senderHostSecret.value.toByteArray(), xOnly(host)).encodePayload()
        }
        return Sealed(content, slots)
    }

    /**
     * The plaintext, or null when this device has no slot, its slot does not open under
     * [senderHostKey], or the content does not open under what the slot held.
     */
    fun open(sealed: Sealed, myHostSecret: PrivateKey, myHostKey: HexKey, senderHostKey: HexKey): String? = runCatching {
        val slot = sealed.slots[myHostKey.lowercase()] ?: return null
        val contentKey = nip44.decrypt(slot, myHostSecret.value.toByteArray(), xOnly(senderHostKey))
            .takeIf { it.length == 64 }?.hexToByteArray() ?: return null
        nip44.decrypt(sealed.content, contentKey)
    }.getOrNull()

    /** The x-coordinate of a compressed host key, which is all NIP-44's ECDH reads. */
    private fun xOnly(hostPublicKey: HexKey): ByteArray {
        require(hostPublicKey.length == 66) { "a host key is 33 bytes" }
        return hostPublicKey.hexToByteArray().copyOfRange(1, 33)
    }

    /** As tags: `["slot", <host key>, <sealed content key>]`, sorted so two devices agree byte for byte. */
    const val SLOT_TAG = "slot"

    fun slotTags(slots: Map<HexKey, String>, name: String = SLOT_TAG): List<Array<String>> =
        slots.entries.sortedBy { it.key }.map { (host, sealed) -> arrayOf(name, host, sealed) }

    fun parseSlots(tags: Array<Array<String>>, name: String = SLOT_TAG): Map<HexKey, String> =
        tags.filter { it.size >= 3 && it[0] == name && it[1].length == 66 }
            .associate { it[1].lowercase() to it[2] }
}
