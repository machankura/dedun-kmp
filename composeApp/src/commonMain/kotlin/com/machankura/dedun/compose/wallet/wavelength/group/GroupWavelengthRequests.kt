package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import fr.acinq.bitcoin.Satoshi

/**
 * A request that has been paid: how much, if that is knowable, and on whose word.
 *
 * Public because a UI state carries it; [GroupWavelengthRequests], which decides it, is not.
 */
data class PaidRequest(val amount: Satoshi?, val onTheOperatorsWord: Boolean)

/**
 * Whether a deposit request of the group's Wavelength wallet has been paid --
 * `docs/paid-requests.md`, Phase 3.
 *
 * **This device's own records first, the operator's word second**, which is this wallet's
 * standing rule: a member takes the operator's word for a Lightning payment because nothing
 * can prove where an invoice pays, and for nothing else. Where this device co-signed the thing
 * that took the money in, it says so flatly; where only the operator's snapshot says a payment
 * happened, the screen names the operator.
 */
internal object GroupWavelengthRequests {

    /** The one status word of the operator's snapshot that means a payment finished. */
    private const val COMPLETE = "complete"

    /**
     * What arrived against the boarding address in [row], by this device's own record of the
     * round that took it in.
     *
     * A [GroupArkBoarded] row is written when this device co-signs a boarding coin into a round
     * and deleted when the round confirms, so it is the window between the two. There is no
     * operator fallback: the snapshot's activity lines carry a payment hash and nothing that
     * names an address, so a boarding payment the operator alone has seen cannot be told from
     * any other member's. The panel therefore appears when the group signs for the coin rather
     * than at first sight of it in the mempool.
     */
    fun paidOnAddress(row: GroupArkDepositAddress, boarded: List<GroupArkBoarded>): PaidRequest? {
        val script = row.script ?: return null
        val coins = boarded.filter { it.script.equals(script, ignoreCase = true) }
        if (coins.isEmpty()) return null
        return PaidRequest(Satoshi(coins.sumOf { it.amountSat }), onTheOperatorsWord = false)
    }

    /**
     * What arrived against the invoice in [row]: a claim this device signed, a payment it filed,
     * or failing both the operator's own account of it.
     *
     * [GroupArkClaim] carries the amount it claimed; [GroupArkPayment] records only that a
     * payment hash reached a transaction, so a request known that way is paid with no figure --
     * which is what the panel's nullable amount is for.
     */
    fun paidOnInvoice(
        row: GroupArkDepositAddress,
        claims: List<GroupArkClaim>,
        payments: List<GroupArkPayment>,
        snapshot: GroupWavelengthSnapshot?,
    ): PaidRequest? {
        val hash = row.paymentHash ?: return null
        claims.firstOrNull { it.paymentHash.equals(hash, ignoreCase = true) }
            ?.let { return PaidRequest(Satoshi(it.amountSat), onTheOperatorsWord = false) }
        if (payments.any { it.paymentHash.equals(hash, ignoreCase = true) }) {
            return PaidRequest(amount = null, onTheOperatorsWord = false)
        }
        // "pending" is no help here: a line exists from the moment the invoice is made, so only
        // the operator saying it finished is the operator saying it was paid.
        return snapshot?.activity
            ?.firstOrNull { it.paymentHash.equals(hash, ignoreCase = true) && it.status == COMPLETE }
            ?.let { PaidRequest(Satoshi(it.amountSat), onTheOperatorsWord = true) }
    }
}
