package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.GuardianManager
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.utils.sum
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.repository.DepositRepository

sealed interface GroupWalletUIState {
    /**
     * A room whose group has not signed a wallet statement.
     *
     * [account] is what a statement would carry, or null for a room with no key
     * at all -- there is nothing to open then. [canSign] gates the way in, for
     * the reason every proposing screen gives: `proposeSigning` throws for a
     * device holding no share.
     *
     * [guarded] is the guarded account a subgroup could open instead, when its
     * birth certificate grants it guardian keys and guarded wallets are offered
     * on this network -- see `docs/group-wallet-guardians.md`.
     *
     * [policy] is what the room's own certificate decided about its wallet, or
     * null for a room with none -- `docs/subgroup-wallet-policy.md`. A decided
     * policy leaves one way in: [guarded] alone for *guarded*, the plain
     * [account] alone for *unguarded*, which [offersPlain] and [guarded] say.
     */
    data class NotOpened(
        val localChatRoom: LocalChatRoom,
        val account: GroupWalletDerivation.Account?,
        val canSign: Boolean,
        val guarded: GroupWalletManager.GuardedOpening? = null,
        val policy: WalletPolicyReading? = null,
        /** What this device calls each guardian's room, by room id, for the ones it holds. */
        val guardianNames: Map<String, String> = emptyMap(),
        /** Whether a plain wallet may be opened: false only when the parent decided this one is guarded. */
        val offersPlain: Boolean = true,
        /** Guarded wallets of groups below this one. A group with no wallet of its own can still guard others'. */
        val guardedWallets: List<GuardedWalletRow> = emptyList(),
        /** Guarded statements this device can carry to the groups above. */
        val shareable: List<GuardianManager.Shareable> = emptyList(),
    ): GroupWalletUIState

    /**
     * An open wallet.
     *
     * [walletState] is null before Electrum's first answer.
     */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupWallet,
        val walletState: GroupWalletState?,
        val canSign: Boolean,
        /** The room's spends, newest first. */
        val spends: List<SpendRow> = emptyList(),
        /** Guarded wallets of groups below this one. */
        val guardedWallets: List<GuardedWalletRow> = emptyList(),
        /** Guarded statements this device can carry to the groups above. */
        val shareable: List<GuardianManager.Shareable> = emptyList(),
        /** What this device calls each guardian's room, by room id, for the ones it holds. */
        val guardianNames: Map<String, String> = emptyMap(),
        /** Whether the active profile is handed a deposit address, and why not: `docs/member-deposits.md`. */
        val deposits: DepositRepository.Standing = DepositRepository.Standing.CannotSign,
        /** Each member's name, for saying whose a coin is. */
        val memberNames: Map<HexKey, String> = emptyMap(),
        /** What the wallet holds that nobody owns yet, and so nobody can be paid: `docs/payouts-only.md`. */
        val unownedSat: Long = 0,
    ): GroupWalletUIState

    data class Error(
        val message: String
    ): GroupWalletUIState

    data object Loading: GroupWalletUIState
}

/** A descendant's guarded wallet, as an ancestor's wallet screen lists it. */
data class GuardedWalletRow(
    val wallet: GuardianManager.GuardedWallet,
    /** Null before Electrum's first answer, and for a profile that cannot watch the chain. */
    val walletState: GroupWalletState?,
) {
    /**
     * What this group could spend now: coins confirmed at least as deep as its
     * own leaf's lock -- not the default's, since the group that granted the
     * leaf chose it. Null while the chain is not known.
     */
    val withinReach: Satoshi?
        get() = walletState?.let { state ->
            state.utxos.filter { state.confirmations(it) >= wallet.delay }.map { it.amount }.sum()
        }

    /**
     * Coins in the wallet not yet within reach: in the mempool, or confirmed
     * less deeply than this group's own leaf's lock, the same one [withinReach]
     * counts past. What a funding looks like for the two
     * hours after it lands -- without it, [withinReach] would make the money
     * look gone. Every such coin, whoever paid it, which is all this group can
     * know. Null while the chain is not known.
     */
    val arriving: Satoshi?
        get() = walletState?.let { state ->
            state.utxos.filter { state.confirmations(it) < wallet.delay }.map { it.amount }.sum()
        }
}

/**
 * One member's wallets as an ancestor's list gathers them, so a group can see
 * at a glance that one person has several, guarded by different groups below
 * it. `docs/member-wallet-guarding-group.md`, D6.
 */
data class PersonGuardedWallets(
    val ownerPublicKey: HexKey,
    /** The member's profile name on this device, or null. */
    val ownerName: String?,
    /** Nearest first, then by the guarding group's name: wallets from one branch sit together. */
    val rows: List<GuardedWalletRow>,
) {
    /** How many groups below this one directly guard these wallets. */
    val guardianCount: Int get() = rows.map { it.wallet.directGuardianChatRoomId.lowercase() }.distinct().size
}

/**
 * The member wallets among these rows, by person, people by name and then key.
 * Subgroups' wallets are left out: a row there is a room, not a person.
 */
fun List<GuardedWalletRow>.byPerson(): List<PersonGuardedWallets> =
    mapNotNull { row -> (row.wallet.subject as? GuardianManager.GuardedSubject.Member)?.let { it to row } }
        .groupBy { (member, _) -> member.ownerPublicKey.lowercase() }
        .map { (_, owned) ->
            val member = owned.first().first
            PersonGuardedWallets(
                ownerPublicKey = member.ownerPublicKey,
                ownerName = owned.firstNotNullOfOrNull { (it, _) -> it.ownerName },
                rows = owned.map { it.second }.sortedWith(
                    compareBy<GuardedWalletRow>(
                        { it.wallet.position },
                        { (it.wallet.subject as GuardianManager.GuardedSubject.Member).guardianName?.lowercase() ?: it.wallet.directGuardianChatRoomId },
                        { (it.wallet.subject as GuardianManager.GuardedSubject.Member).label?.lowercase() },
                    )
                ),
            )
        }
        .sortedWith(compareBy({ it.ownerName?.lowercase() ?: it.ownerPublicKey }, { it.ownerPublicKey }))

/** Where one of the room's spends has got to, as one fact a member reads. */
enum class SpendStatus {
    WAITING_FOR_SIGNATURES,
    ABANDONED,
    SIGNED,
    SENT,
    CONFIRMED,
    REPLACED;

    companion object {
        /**
         * The session decides until the group has signed; the chain decides after.
         * Sent means Electrum has reported it back -- a broadcast reports success
         * whatever the server said, so sending is not evidence of having been sent.
         */
        fun of(stage: FrostSigningStage?, transaction: GroupTransaction): SpendStatus = when {
            transaction.replacedByTxid != null -> REPLACED
            transaction.confirmedAt != null -> CONFIRMED
            transaction.seenAt != null -> SENT
            transaction.signedTransaction != null -> SIGNED
            stage == FrostSigningStage.FAILED -> ABANDONED
            else -> WAITING_FOR_SIGNATURES
        }
    }
}

/** One of the room's spends, as the wallet screen lists it. */
data class SpendRow(
    val transaction: GroupTransaction,
    val status: SpendStatus,
    /** The spend, checked again; null where this device cannot check it, or for a sweep. */
    val spend: GroupSpend.Checked?,
    /** A sweep from the Lightning wallet into this one, checked again; null for a spend. */
    val sweep: GroupSweep.Checked? = null,
    /** The spend as a payout, as this device reads it, or null when it is not one: `docs/payouts-only.md`. */
    val payout: PayoutRule.Result.Payout? = null,
) {
    /** Signed, never reported back: the one moment sending it again is worth anything. */
    val canSendAgain: Boolean get() = status == SpendStatus.SIGNED

    /** Signed and not yet decided by a block: RBF is still possible. */
    val canBump: Boolean get() = (status == SpendStatus.SIGNED || status == SpendStatus.SENT) && spend != null
}
