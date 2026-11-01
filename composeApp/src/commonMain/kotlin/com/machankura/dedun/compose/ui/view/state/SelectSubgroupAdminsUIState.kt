package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.subgroup.isDecided
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant

sealed interface SelectSubgroupAdminsUIState {
    data class Loaded(
        val parentRoom: LocalChatRoom,
        /**
         * Who can be picked: the parent's own members, admins and non-admins
         * alike.
         *
         * Not filtered to the parent's admins on purpose. The point of a subgroup
         * is that it can be run by people the parent does not let run the parent,
         * so a picker that only offered existing admins would offer the one thing
         * subgroups are not for.
         */
        val candidates: List<LocalParticipant> = emptyList(),
        /**
         * Members with no unconsumed key package, who therefore cannot be
         * welcomed into the room at step 4.
         *
         * Checked here rather than at creation because creation is three
         * ceremonies later: a ChillDKG, a parent quorum and a child quorum all
         * have to complete before `MarmotGroupCreation` would discover it, and
         * each of those needed every selected admin to show up and approve.
         */
        val withoutKeyPackage: Set<HexKey> = emptySet(),
        /** Null while the lookup is still out; a set once it has settled. */
        val isResolvingKeyPackages: Boolean = true,
        /**
         * What the parent's own certificate says of its wallet, or null for a
         * parent with none. A parent decided *guarded* can only make guarded
         * subgroups -- `docs/subgroup-wallet-policy.md`, D4.
         */
        val parentPolicy: WalletPolicyReading? = null,
        /**
         * The guardian keys the parent's own certificate grants it, or null when
         * they do not read -- in which case it cannot grant a child any, and a
         * guarded subgroup is refused here rather than after a ceremony.
         */
        val parentGuardians: List<GuardianDerivation.Guardian>? = emptyList(),
    ): SelectSubgroupAdminsUIState

    data class Error(val message: String): SelectSubgroupAdminsUIState

    data object Loading: SelectSubgroupAdminsUIState
}

/** Whether this parent may only make guarded subgroups. */
val SelectSubgroupAdminsUIState.Loaded.onlyGuarded: Boolean
    get() = parentPolicy.isDecided(SubgroupWalletPolicy.GUARDED)

/** Whether this parent can grant a child guardian keys at all. */
val SelectSubgroupAdminsUIState.Loaded.canGuard: Boolean
    get() = parentGuardians != null
