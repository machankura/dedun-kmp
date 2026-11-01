package com.machankura.dedun.compose.nostr.subgroup.tags

import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy

/**
 * A subgroup's wallet policy, as its parent decided it:
 *
 * ```
 * ["wallet", "guarded" | "unguarded"]
 * ```
 *
 * On a key ceremony's proposal it is what the coordinator chose, shown to every
 * admin before they join; on a birth certificate it is the parent's quorum
 * signing that choice. See `SubgroupWalletPolicy` and
 * `docs/subgroup-wallet-policy.md`.
 *
 * Shape only, as `GuardianTag` is. Whether a policy agrees with the guardian
 * keys beside it is the certificate's question, not the tag's.
 */
object WalletPolicyTag {
    const val TAG_NAME = "wallet"

    fun assemble(policy: SubgroupWalletPolicy): Array<String> = arrayOf(TAG_NAME, policy.tagValue)

    /** The policy [tag] names, or null when it is not a wallet tag or names no policy this build knows. */
    fun parse(tag: Array<String>): SubgroupWalletPolicy? {
        if (tag.size < 2 || tag[0] != TAG_NAME) return null
        return SubgroupWalletPolicy.fromTagValue(tag[1])
    }

    /**
     * The policy [tags] carry: null for none, the policy for exactly one readable
     * tag -- and [Unreadable] when a tag *named* `wallet` fails to read or there
     * are two, so that a policy present and unreadable is never mistaken for one
     * absent.
     */
    fun parseOne(tags: Array<Array<String>>): Result {
        val named = tags.filter { it.isNotEmpty() && it[0] == TAG_NAME }
        if (named.isEmpty()) return Result.Absent
        if (named.size > 1) return Result.Unreadable
        return parse(named.single())?.let { Result.Present(it) } ?: Result.Unreadable
    }

    sealed interface Result {
        data object Absent : Result
        data object Unreadable : Result
        data class Present(val policy: SubgroupWalletPolicy) : Result
    }
}
