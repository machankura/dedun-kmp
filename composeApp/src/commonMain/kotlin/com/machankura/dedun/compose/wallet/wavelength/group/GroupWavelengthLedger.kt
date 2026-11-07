package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.GroupArkVtxo

/**
 * What this device's own record says the group's wallet holds, for a screen: `docs/group-wavelength-ui.md`, U2. It only knows what
 * this device has itself signed, so a restored device says nothing rather than zero, and coins sent by anyone else aren't in it.
 */
data class GroupWavelengthLedger(
    /** Coins this device has seen settle and not seen spent, including those in [heldSat]. */
    val settledSat: Long,
    /** The part of [settledSat] this device has signed towards something it hasn't seen take effect. */
    val heldSat: Long,
    /** Coins this device signed for and hasn't seen settle. */
    val pendingSat: Long,
    val coins: Int,
    /** The soonest height any settled coin can be swept from, or null when this device has worked out none. */
    val earliestExpiryHeight: Long?,
) {
    /** Whether this device has ever signed for a coin of this wallet: if not, it can't say what the wallet holds. */
    val isEmpty: Boolean get() = coins == 0

    companion object {
        fun of(rows: List<GroupArkVtxo>): GroupWavelengthLedger {
            val live = rows.filter { !it.spent }
            val settled = live.filter { it.settled }
            return GroupWavelengthLedger(
                settledSat = settled.sumOf { it.amountSat },
                heldSat = settled.filter { it.reservedBy != null }.sumOf { it.amountSat },
                pendingSat = live.filter { !it.settled }.sumOf { it.amountSat },
                coins = rows.size,
                earliestExpiryHeight = settled.mapNotNull { it.expiresAtHeight }.minOrNull(),
            )
        }
    }
}
