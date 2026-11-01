package com.machankura.dedun.compose.nostr.subgroup.tags

import com.machankura.dedun.compose.managers.GuardianDerivation

/**
 * The delay a coordinator chose for the parent's own leaf in a guarded
 * subgroup's wallet, on the subgroup's ceremony proposal:
 *
 * ```
 * ["guardian_delay", "<blocks>"]
 * ```
 *
 * Shown to every admin at the join gate, and what the certificate is later
 * proposed under. Beside `["wallet", "guarded"]` and nowhere else. See
 * `docs/guardian-delay.md`.
 *
 * Shape and range only. Whether it is the delay the certificate carries is the
 * child signers' question, asked of the certificate.
 */
object GuardianDelayTag {
    const val TAG_NAME = "guardian_delay"

    fun assemble(delay: Long): Array<String> = arrayOf(TAG_NAME, delay.toString())

    /**
     * The delay [tags] carry: [Result.Absent] for none, the delay for exactly one
     * that reads and is in `GuardianDerivation.DELAY_RANGE`, and
     * [Result.Unreadable] otherwise -- a delay present and unusable is never
     * mistaken for one absent.
     */
    fun parseOne(tags: Array<Array<String>>): Result {
        val named = tags.filter { it.isNotEmpty() && it[0] == TAG_NAME }
        if (named.isEmpty()) return Result.Absent
        if (named.size > 1) return Result.Unreadable
        val delay = named.single().getOrNull(1)?.toLongOrNull()?.takeIf { it in GuardianDerivation.DELAY_RANGE }
        return delay?.let { Result.Present(it) } ?: Result.Unreadable
    }

    sealed interface Result {
        data object Absent : Result
        data object Unreadable : Result
        data class Present(val delay: Long) : Result
    }
}
