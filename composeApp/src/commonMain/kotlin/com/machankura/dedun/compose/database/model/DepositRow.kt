package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One deposit into a group, as this device has learned it: `docs/member-deposits.md`, Phase 8.
 *
 * Kept, not derived: the wallet holds what is unspent now, and a deposit the group has spent since
 * would vanish from a tally read off it. A cache of what the chain, the snapshots and the group's
 * signed events say, so a restored device rebuilds it.
 */
@Entity(primaryKeys = ["chatRoomId", "depositKey"], indices = [Index(value = ["chatRoomId", "chain"])])
data class DepositRow(
    val chatRoomId: String,
    /** The network, as `GroupWalletStatementEvent.chainName` spells it. */
    val chain: String,
    /** `<txid>:<vout>`, `ln:<payment hash>` or `swapin:<txid>:<vout>`: `DepositAttributionEvent.DepositKey.text`. */
    val depositKey: String,
    /** [ONCHAIN] or [LIGHTNING]. */
    val source: String,
    /** What came in, in msat for both, so a tally adds one unit: a sat is 1,000. Net of the LSP's fee over Lightning. */
    val amountMsat: Long,
    /** Whose deposit it is, or null for nobody's yet. */
    val member: HexKey? = null,
    /** How [member] is known: [BY_BRANCH], [BY_INVOICE], [BY_OPERATOR], [BY_SWAP_IN] or [BY_GROUP]; null while unattributed. */
    val attributedBy: String? = null,
    val seenAt: Instant = Clock.System.now(),
    val confirmedAt: Instant? = null,
) {
    companion object {
        const val ONCHAIN = "onchain"
        const val LIGHTNING = "lightning"

        /** Paid to an address on the member's own deposit branch. */
        const val BY_BRANCH = "branch"
        /** Paid an invoice issued against the member's statement, as this device holds its request and reply. */
        const val BY_INVOICE = "invoice"
        /** Paid an invoice the operator's snapshot said was the member's: its word, for a device that missed the pair. */
        const val BY_OPERATOR = "operator"
        /**
         * A swap-in coin the operator's snapshot named, on an address this device derived as that member's:
         * the address is checked, the outpoint and amount are the operator's word (`docs/group-lightning-swap-in.md`, Phase 5).
         */
        const val BY_SWAP_IN = "swap-in"
        /** The group's signed attribution (30340) says whose it is. */
        const val BY_GROUP = "signed"

        /** How sure each source is, so a weaker one never overwrites a stronger. */
        fun strength(attributedBy: String?): Int = when (attributedBy) {
            BY_GROUP -> 4
            BY_BRANCH, BY_INVOICE -> 3
            BY_OPERATOR, BY_SWAP_IN -> 2
            else -> 0
        }
    }
}
