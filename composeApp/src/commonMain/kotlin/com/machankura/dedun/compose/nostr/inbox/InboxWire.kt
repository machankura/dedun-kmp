package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.bitcoin.PrivateKey
import com.machankura.dedun.compose.extensions.toHex

/**
 * The arithmetic every piece of the group's inbox shares (docs/group-inbox.md).
 *
 * NIP-4E's keys are x-only, as every nostr key is. The inbox key's secret `e` comes out of a
 * ChillDKG ceremony whose public key may have either parity; NIP-44 uses only the x of the
 * shared point, which is the same for `e` and `-e`, so `e` is used as it is and nobody negates
 * anything. `InboxKeyPremiseTest` checks that from both sides.
 */
object InboxWire {
    /** One instance: [Nip44v2.computeConversationKey] is pure, and nothing here uses its cache. */
    val nip44 = Nip44v2()

    fun isHex32(hex: String?): Boolean =
        hex != null && hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }

    /** `E`, x-only, lowercase hex, as it goes in a 10044's `n` and a seal's `n`. */
    fun publicKeyOf(secret: ByteArray): HexKey = PrivateKey(secret).publicKey().xOnly().value.toHex()

    /**
     * The NIP-44 conversation key between [secret] and [publicKey], uncached: an inbox key is
     * held by several admins and retired on rotation, and a process-wide cache of conversation
     * keys would keep a retired one alive in memory for no gain.
     */
    fun conversationKey(secret: ByteArray, publicKey: HexKey): ByteArray =
        nip44.computeConversationKey(secret, publicKey.hexToByteArray())

    fun encrypt(plaintext: String, conversationKey: ByteArray): String =
        nip44.encrypt(plaintext, conversationKey).encodePayload()

    /** [payload] opened under [conversationKey], or null for any way that can fail. */
    fun decrypt(payload: String, conversationKey: ByteArray): String? =
        runCatching { nip44.decrypt(payload, conversationKey) }.getOrNull()
}
