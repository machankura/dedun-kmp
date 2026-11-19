package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.machankura.dedun.compose.database.model.types.ShareStanding

/**
 * The kinds and tags of a share refresh (docs/frost-share-refresh.md, Phase 7).
 *
 * They travel as Marmot inner events in the room whose id the key derives, like
 * the signing family beside them. The dealt values inside are encrypted to each
 * recipient's ChillDKG host key by the protocol itself, so a message every member
 * of the room can read discloses nothing -- which is not true of a repair, whose
 * messages are raw scalars and go pairwise (Phase 10).
 *
 * 30338-30349 were unused when these were chosen, between the retired wallet
 * kinds and the retired group Lightning wallet's. Dedun has not used them.
 */
object ShareRefreshEvents {
    /** Proposer (coordinator): "refresh this key, from this generation, with these recipients". */
    val PROPOSAL: Kind = 30340

    /** Recipient: round 1, the commitments and encrypted dealt values. */
    val ROUND_1: Kind = 30341

    /** Coordinator: the sum of every recipient's round 1 (cmsg1). */
    val COORDINATOR_ROUND_1: Kind = 30342

    /** Recipient: the confirmation that it has stored the new generation. */
    val ROUND_2: Kind = 30343

    /** Coordinator: the certificate, every recipient's confirmation (cmsg2). */
    val CERTIFICATE: Kind = 30344

    /** Any recipient: abandon. Content is the reason. */
    val FAILURE: Kind = 30345

    val ALL: Set<Kind> = setOf(PROPOSAL, ROUND_1, COORDINATOR_ROUND_1, ROUND_2, CERTIFICATE, FAILURE)

    fun isShareRefreshKind(kind: Kind): Boolean = kind in ALL

    /**
     * Whether [event] is a share refresh's message -- not only its kind, but its session tag too.
     *
     * Until Dedun's own kinds have moved out of Giza's way for good (docs/giza-into-dedun.md, Phase K,
     * K3), 30340-30343 are also the legacy numbers of Dedun's deposit attribution, coin assignment and
     * transfer kinds, and a device on an older build still sends a transfer request's carrier as a 30342.
     * Every refresh message carries [SESSION_TAG], and none of Dedun's does, so the tag says which.
     */
    fun isShareRefresh(event: Event): Boolean =
        event.kind in ALL && parseSessionId(event.tags) != null

    private const val SESSION_TAG = "refresh_session"
    private const val RECIPIENTS_TAG = "refresh_recipients"
    private const val WITHHELD_TAG = "refresh_withheld"
    private const val KEY_TAG = "frost_key"
    private const val GENERATION_TAG = "frost_generation"

    /**
     * Tags for a refresh message. The session is on every one; what the refresh
     * is -- the key, the base generation, the recipients and the withheld -- only
     * on the proposal, so that no later message has a say in it.
     */
    fun assembleTags(
        sessionId: String,
        dkgSessionId: String? = null,
        baseGeneration: Int? = null,
        recipientIds: List<Int>? = null,
        withheld: Map<Int, ShareStanding>? = null,
    ): Array<Array<String>> = buildList {
        add(arrayOf(SESSION_TAG, sessionId))
        dkgSessionId?.let { add(arrayOf(KEY_TAG, it)) }
        baseGeneration?.let { add(arrayOf(GENERATION_TAG, it.toString())) }
        recipientIds?.let { add(arrayOf(RECIPIENTS_TAG, it.sorted().joinToString(","))) }
        withheld?.takeIf { it.isNotEmpty() }?.let { w ->
            add(arrayOf(WITHHELD_TAG, w.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.name.lowercase()}" }))
        }
    }.toTypedArray()

    private fun value(tags: Array<Array<String>>, name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun parseSessionId(tags: Array<Array<String>>): String? = value(tags, SESSION_TAG)?.takeIf { it.length == 64 }

    fun parseKey(tags: Array<Array<String>>): String? = value(tags, KEY_TAG)

    fun parseBaseGeneration(tags: Array<Array<String>>): Int? = value(tags, GENERATION_TAG)?.toIntOrNull()?.takeIf { it >= 0 }

    /** The recipients, or null when the tag is absent or any entry is unreadable: a set read in part is a different set. */
    fun parseRecipients(tags: Array<Array<String>>): List<Int>? {
        val raw = value(tags, RECIPIENTS_TAG) ?: return null
        val ids = raw.split(",").map { it.trim().toIntOrNull() ?: return null }
        return ids.takeIf { it.isNotEmpty() && it == it.sorted() && it.toSet().size == it.size && it.all { id -> id >= 0 } }
    }

    /** The withheld and their standings: empty when the tag is absent, null when it is unreadable. */
    fun parseWithheld(tags: Array<Array<String>>): Map<Int, ShareStanding>? {
        val raw = value(tags, WITHHELD_TAG) ?: return emptyMap()
        return raw.split(",").associate { entry ->
            val parts = entry.split(":").takeIf { it.size == 2 } ?: return null
            val id = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val standing = when (parts[1].trim()) {
                "stale" -> ShareStanding.STALE
                "retired" -> ShareStanding.RETIRED
                else -> return null
            }
            id to standing
        }
    }
}
