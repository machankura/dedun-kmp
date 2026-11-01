package com.machankura.dedun.compose.nostr.subgroup

/**
 * What a parent decided, at a subgroup's birth, about the subgroup's wallet --
 * `docs/subgroup-wallet-policy.md`.
 *
 * Chosen by the coordinator on the subgroup picker, carried on the ceremony
 * proposal so every admin sees it before joining, and signed by the parent's
 * quorum into the birth certificate. Every honest device then offers, signs,
 * derives and watches only the wallet it allows.
 *
 * Not a rule the chain enforces: the child's key is the child's, and a quorum of
 * modified clients can sign whatever it likes with it. It is the parent's signed
 * decision, which every client running this app keeps.
 */
enum class SubgroupWalletPolicy(val tagValue: String) {
    /**
     * The child can only open a guarded wallet, and its certificate carries the
     * guardian keys that wallet's leaves are built from. A guarded group's own
     * subgroups are guarded too.
     */
    GUARDED("guarded"),

    /**
     * The child can only open a plain wallet. Its certificate carries no guardian
     * keys, so nothing above it can reach its wallet or anything beneath it.
     */
    UNGUARDED("unguarded");

    companion object {
        fun fromTagValue(value: String): SubgroupWalletPolicy? = entries.firstOrNull { it.tagValue == value }
    }
}

/** What a birth certificate says about the child's wallet. */
sealed interface WalletPolicyReading {
    /** The parent decided, and signed it. */
    data class Decided(val policy: SubgroupWalletPolicy) : WalletPolicyReading

    /**
     * No policy tag: a certificate from before policies, which leaves the choice
     * to the child -- a guarded wallet if its certificate carries keys, a plain
     * one beside it either way.
     */
    data object Undecided : WalletPolicyReading
}

/** Whether this reading is the parent having decided [policy]. */
fun WalletPolicyReading?.isDecided(policy: SubgroupWalletPolicy): Boolean =
    (this as? WalletPolicyReading.Decided)?.policy == policy

/**
 * Whether a wallet that is [guarded] -- or plain, when false -- is one this
 * reading allows: a decided policy allows its own kind only, and an undecided
 * or absent one allows either, as before policies.
 */
fun WalletPolicyReading?.allows(guarded: Boolean): Boolean = when (this) {
    is WalletPolicyReading.Decided -> guarded == (policy == SubgroupWalletPolicy.GUARDED)
    WalletPolicyReading.Undecided, null -> true
}
