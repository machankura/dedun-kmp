package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The kinds threshold ECDH travels on (docs/group-threshold-ecdh.md, D4, D11 and Phase 0's
 * check 5). Marmot inner events in the group's own room, like the signing and share refresh
 * families.
 *
 * 30400 and 30401 were free in the NIP README (30402 and 30403 are NIP-99's), quartz, Dedun,
 * and `RetiredKinds` when they were chosen.
 */
object GroupEcdhEvents {
    /** An admin's partials for one or more rounds: [GroupEcdhPayload], sealed to the admins by [AdminSeal]. */
    val PARTIALS: Kind = 30400

    /** A round whose partial from [BLAME_TAG] did not prove. Content is the reason, in words. */
    val FAILURE: Kind = 30401

    val ALL: Set<Kind> = setOf(PARTIALS, FAILURE)

    const val ROUND_TAG = "ecdh_round"
    const val BLAME_TAG = "ecdh_blame"

    /** A partials message's tags: the slots, and nothing else a member who is not an admin can read. */
    fun partialsTags(sealed: AdminSeal.Sealed): Array<Array<String>> = AdminSeal.slotTags(sealed.slots).toTypedArray()

    fun failureTags(roundId: HexKey, participantId: Int): Array<Array<String>> =
        arrayOf(arrayOf(ROUND_TAG, roundId), arrayOf(BLAME_TAG, participantId.toString()))

    fun parseFailure(tags: Array<Array<String>>): Pair<HexKey, Int>? {
        val round = tags.firstOrNull { it.size >= 2 && it[0] == ROUND_TAG }?.get(1)?.takeIf { it.length == 64 } ?: return null
        val blamed = tags.firstOrNull { it.size >= 2 && it[0] == BLAME_TAG }?.get(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return round to blamed
    }
}
