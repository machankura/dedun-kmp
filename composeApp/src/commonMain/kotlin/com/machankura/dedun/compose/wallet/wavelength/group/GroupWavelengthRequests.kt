package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import fr.acinq.bitcoin.Satoshi

/**
 * A request that has been paid: how much, if that is knowable, on whose word, and whether the money is
 * still on its way.
 *
 * Public because a UI state carries it; [GroupWavelengthRequests], which decides it, is not.
 */
data class PaidRequest(val amount: Satoshi?, val onTheOperatorsWord: Boolean, val onTheWay: OnTheWay? = null) {
    /**
     * Where a boarding payment this device has seen on the chain has got to, or null once a round has it.
     *
     * Deliberately coarse. The panel's job is to say that the money arrived and is still moving, not to be
     * a block explorer: how deep the coin is against the tip is on its row in the history, which holds a
     * tip read a minute ago rather than one read when this screen opened.
     */
    enum class OnTheWay { MEMPOOL, CONFIRMED }
}

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
     * What arrived against the boarding address in [row], by this device's own record of the chain and of
     * the round that took it in -- `docs/group-wavelength-unconfirmed-deposits.md`, Phase 5.
     *
     * Two records, in the order of their strength. A [GroupArkBoarded] row is written when this device
     * co-signs a boarding coin into a round and deleted when the round confirms, so it is the window
     * between the two. A [GroupArkArriving] row is earlier still: a payment this device's own Electrum
     * connection has seen at that address, from first sight in the mempool until a round takes it in.
     *
     * There is still no operator fallback, and there never can be: the snapshot's activity lines carry a
     * payment hash and nothing that names an address, so a boarding payment the operator alone has seen
     * cannot be told from any other member's. The reason this rail could not meet `paid-requests.md`'s D1
     * was that fallback's absence -- but a device does not need the operator here, because it checked the
     * address when it was answered and can watch it itself. So the panel appears **at first sight of the
     * money**, which is what D1 asked for.
     *
     * A coin a round this device never signed has taken in is not on its way and is not paid-and-arriving:
     * it has gone where this device cannot follow it, and the history row says so (A12).
     */
    fun paidOnAddress(
        row: GroupArkDepositAddress,
        boarded: List<GroupArkBoarded>,
        arriving: List<GroupArkArriving> = emptyList(),
    ): PaidRequest? {
        val script = row.script ?: return null
        val coins = boarded.filter { it.script.equals(script, ignoreCase = true) }
        if (coins.isNotEmpty()) return PaidRequest(Satoshi(coins.sumOf { it.amountSat }), onTheOperatorsWord = false)
        val onChain = arriving.filter { it.script.equals(script, ignoreCase = true) && it.spentUnseenAtEpochSeconds == null }
        if (onChain.isEmpty()) return null
        return PaidRequest(
            amount = Satoshi(onChain.sumOf { it.amountSat }),
            onTheOperatorsWord = false,
            // One of them still in the mempool is what the member is waiting on, so that is what is said.
            onTheWay = if (onChain.any { it.blockHeight == null }) PaidRequest.OnTheWay.MEMPOOL else PaidRequest.OnTheWay.CONFIRMED,
        )
    }

    /**
     * What arrived against the invoice in [row]: a claim this device signed, or failing that the
     * operator's own account of it.
     *
     * [GroupArkClaim] is the one device-local record that means money came *in* -- "a Lightning
     * payment this device has signed the claim of" -- and it carries the amount. `GroupArkPayment`
     * is not: it is "the transfer this device signed a Lightning payment into", a payout going
     * out, and reading it as a deposit arriving was this screen's mistake for one commit.
     */
    fun paidOnInvoice(
        row: GroupArkDepositAddress,
        claims: List<GroupArkClaim>,
        snapshot: GroupWavelengthSnapshot?,
    ): PaidRequest? {
        val hash = row.paymentHash ?: return null
        claims.firstOrNull { it.paymentHash.equals(hash, ignoreCase = true) }
            ?.let { return PaidRequest(Satoshi(it.amountSat), onTheOperatorsWord = false) }
        // "pending" is no help here: a line exists from the moment the invoice is made, so only
        // the operator saying it finished is the operator saying it was paid.
        return snapshot?.activity
            ?.firstOrNull { it.paymentHash.equals(hash, ignoreCase = true) && it.status == COMPLETE }
            ?.let { PaidRequest(Satoshi(it.amountSat), onTheOperatorsWord = true) }
    }
}
