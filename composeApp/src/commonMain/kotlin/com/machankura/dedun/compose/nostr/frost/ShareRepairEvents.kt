package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The kinds and tags of a share repair (docs/frost-share-refresh.md, Phase 10).
 *
 * Inner events of the key's Marmot room, like the refresh beside them. Unlike
 * the refresh's, two of these carry raw additive shares, which the room must
 * not read: each is encrypted with NIP-44 between the sender's and the
 * recipient's ChillDKG host keys, so only the two of them hold the conversation
 * key -- confidential, and authenticated by the same token. Never an MLS
 * application message in the clear.
 */
object ShareRepairEvents {
    /** Target: "bring participant k back at generation g, with these helpers, under this host key". */
    val REQUEST: Kind = 30346

    /** Helper to larger-id helper: a round-1.1 share, encrypted. */
    val SHARE: Kind = 30347

    /** Helper to target: the round-1.2 sum, encrypted. */
    val SIGMA: Kind = 30348

    /** Target: done, with a Schnorr signature by the repaired share over the session id. */
    val DONE: Kind = 30349

    val ALL: Set<Kind> = setOf(REQUEST, SHARE, SIGMA, DONE)

    fun isShareRepairKind(kind: Kind): Boolean = kind in ALL

    const val SESSION_TAG = "repair_session"
    const val TARGET_TAG = "repair_target"
    const val HELPERS_TAG = "repair_helpers"
    const val HOST_KEY_TAG = "repair_host_key"
    const val TO_TAG = "repair_to"
    const val KEY_TAG = "frost_key"
    const val GENERATION_TAG = "frost_generation"

    fun value(tags: Array<Array<String>>, name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun parseSessionId(tags: Array<Array<String>>): String? = value(tags, SESSION_TAG)?.takeIf { it.length == 64 }
}
