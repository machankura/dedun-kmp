package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent

/**
 * D5 of docs/group-threshold-ecdh.md, in one function, so the device answering a round and the
 * signer checking a message check the same thing: a device multiplies only by a key whose
 * owner has signed something it can check, of the right shape for the purpose.
 *
 * A signature by `P` proves its signer knows `log_G(P)`, so no point asked about can be
 * `x^k·G`, which is the query a static Diffie-Hellman oracle is worn down by. The group's own
 * npub is refused outright: `x·(x·G)` is exactly that query, and why the group never encrypts
 * to itself.
 */
object Evidence {
    enum class Refusal { WRONG_KIND, WRONG_KEY, NOT_TO_GROUP, BAD_SIGNATURE, THE_GROUP_ITSELF }

    /** Null when [event] lets a device multiply by [point] for [purpose]; otherwise why not. */
    fun check(purpose: EcdhPurpose, point: HexKey, event: Event, groupNpub: HexKey): Refusal? {
        val p = point.lowercase()
        if (p == groupNpub.lowercase()) return Refusal.THE_GROUP_ITSELF
        if (event.pubKey.lowercase() != p) return Refusal.WRONG_KEY
        when (purpose) {
            EcdhPurpose.WRAP -> {
                if (event.kind != GiftWrapEvent.KIND) return Refusal.WRONG_KIND
                val addressed = event.tags.any { it.size >= 2 && it[0] == "p" && it[1].lowercase() == groupNpub.lowercase() }
                if (!addressed) return Refusal.NOT_TO_GROUP
            }
            EcdhPurpose.SEAL -> if (event.kind != SealEvent.KIND) return Refusal.WRONG_KIND
            EcdhPurpose.RECIPIENT -> Unit
        }
        if (!event.verify()) return Refusal.BAD_SIGNATURE
        return null
    }

    fun allows(purpose: EcdhPurpose, point: HexKey, event: Event, groupNpub: HexKey): Boolean =
        check(purpose, point, event, groupNpub) == null
}
