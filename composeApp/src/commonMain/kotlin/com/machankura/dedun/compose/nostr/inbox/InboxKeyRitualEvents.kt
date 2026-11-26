package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The group's inbox key ceremony on the wire (docs/group-inbox.md, D2 and D3): a ChillDKG
 * ceremony at threshold 1 over the room's admins, carried as MLS inner events in the group's
 * own Marmot room.
 *
 * The same seven steps as the group's signing ceremony ([com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents]),
 * on kinds of their own. A `t = 1` key must never be filed where a signing key could be found,
 * and a device that knows only the signing ceremony's kinds -- an older build, or a Dedun
 * admin's -- would take a 30310 over a subset of the room for a subgroup's ceremony. On kinds it
 * does not know it takes it for nothing.
 *
 * Rumor kinds: they only ever travel inside the group's encryption, so the replaceable range
 * means nothing for them. 30410-30416 are in no NIP, quartz, Curare or Dedun (Phase 0).
 *
 * ```
 *  coordinator --[ 30410 proposal      ]-> the room   "let's make the inbox key", p-tagging the admins
 *  admin       --[ 30411 host key      ]-> the room   host pubkey
 *  admin       --[ 30412 round 1       ]-> the room   pmsg1: a contribution, encrypted to the admins' host keys
 *  coordinator --[ 30413 coord round 1 ]-> the room   cmsg1
 *  admin       --[ 30414 round 2       ]-> the room   CertEq signature
 *  coordinator --[ 30415 certificate   ]-> the room   cmsg2
 *  anyone      --[ 30416 failure       ]-> the room   abort, with a reason
 * ```
 *
 * Every member of the room receives all of it. ChillDKG encrypts each contribution to its
 * recipients' host keys, so a member who is not an admin learns the inbox's public key and
 * nothing else.
 */
object InboxKeyRitualEvents {
    const val PROPOSAL: Kind = 30410
    const val HOST_KEY: Kind = 30411
    const val ROUND_1: Kind = 30412
    const val COORDINATOR_ROUND_1: Kind = 30413
    const val ROUND_2: Kind = 30414
    const val CERTIFICATE: Kind = 30415
    const val FAILURE: Kind = 30416

    val ALL: Set<Kind> = setOf(PROPOSAL, HOST_KEY, ROUND_1, COORDINATOR_ROUND_1, ROUND_2, CERTIFICATE, FAILURE)

    fun isInboxKeyKind(kind: Kind): Boolean = kind in ALL

    /** Binds a message to one ceremony, so a late one from an abandoned attempt is dropped. */
    const val SESSION_TAG = "inbox_key_session"

    /** Which generation of the room's inbox key the ceremony makes. On the proposal only. */
    const val GENERATION_TAG = "inbox_key_generation"

    /** The ceremony's threshold. Always 1; carried so a device can refuse anything else. */
    const val THRESHOLD_TAG = "inbox_key_threshold"

    const val THRESHOLD = 1

    fun assembleTags(
        sessionId: String,
        generation: Int? = null,
        participants: List<HexKey> = emptyList(),
    ): Array<Array<String>> = buildList {
        add(arrayOf(SESSION_TAG, sessionId))
        generation?.let {
            add(arrayOf(GENERATION_TAG, it.toString()))
            add(arrayOf(THRESHOLD_TAG, THRESHOLD.toString()))
        }
        participants.forEach { add(arrayOf("p", it)) }
    }.toTypedArray()

    fun parseSessionId(tags: Array<Array<String>>): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == SESSION_TAG }?.get(1)?.takeIf(InboxWire::isHex32)

    fun parseGeneration(tags: Array<Array<String>>): Int? =
        tags.firstOrNull { it.size >= 2 && it[0] == GENERATION_TAG }?.get(1)?.toIntOrNull()?.takeIf { it >= 0 }

    fun parseThreshold(tags: Array<Array<String>>): Int? =
        tags.firstOrNull { it.size >= 2 && it[0] == THRESHOLD_TAG }?.get(1)?.toIntOrNull()

    /** The admins a proposal names, sorted and without repeats; null if any is malformed. */
    fun parseParticipants(tags: Array<Array<String>>): List<HexKey>? {
        val named = tags.filter { it.isNotEmpty() && it[0] == "p" }.map { it.getOrNull(1) }
        if (named.any { !InboxWire.isHex32(it) }) return null
        return named.filterNotNull().distinct().sorted()
    }
}
