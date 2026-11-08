package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The wallet's five obligations, each green, amber or a problem.
 *
 * The guide's security checklist "abstracts away detailed settings and gives simplified feedback", and
 * B7 of `docs/group-wavelength-redesign.md` says what the items are here: they are not settings but the
 * wallet's real obligations, every one of which the group can fail at without being told. A member who
 * wants to know whether this wallet is all right has, before this, had to read a balance card, a
 * deadline line, a devices card, an operator card and the advanced screen, and know what each meant.
 *
 * **Derived entirely from [GroupWavelengthWalletUIState.Opened].** Nothing here reads the database, asks
 * the operator or starts a client: every figure is one the device already holds, which is what makes it
 * a pure function with a fixture-driven test rather than a screen somebody has to look at.
 */
object GroupWavelengthHealth {

    /** Green, amber, or a thing to fix. In this order, so `maxOf` is "the worst of them". */
    enum class Standing { FINE, ATTENTION, PROBLEM }

    /** The five obligations, in the order the screen lists them: soonest consequence first. */
    enum class Kind { COINS, DEVICES, WHO_RUNS_IT, FEES, DEPARTED }

    /** Where the thing that fixes it is, or [NOTHING] where only the group can fix it. */
    enum class Fix { NOTHING, ADVANCED, CEREMONY, THIS_DEVICE_CAN_RUN_IT }

    data class Item(val kind: Kind, val standing: Standing, val fix: Fix = Fix.NOTHING)

    /**
     * Below this, a group with coins needs its devices online soon rather than eventually.
     *
     * A fortnight of blocks. B7's second notification trigger is the same threshold, which is why it is
     * here rather than in the screen: too few devices answering matters very differently at three
     * weeks and at three days.
     */
    const val SOON_BLOCKS: Long = 2016

    /** Over this much of the term's fee budget, somebody should look before the next round spends more. */
    private const val BUDGET_ATTENTION = 0.8

    fun of(state: GroupWavelengthWalletUIState.Opened, now: Instant = Clock.System.now()): List<Item> =
        listOfNotNull(coins(state), devices(state, now), whoRunsIt(state, now), fees(state), departed(state))

    /** The one the screen's row shows, or null where there is nothing to say. The worst, and the first of equals. */
    fun worst(items: List<Item>): Item? = items.maxByOrNull { it.standing.ordinal }

    /**
     * A wallet holding nothing has no deadline, so it gets **no item** rather than a green one.
     *
     * A green tick against a deadline that does not exist is the kind of reassurance a checklist should
     * never give: it reads as "checked and fine" where the truth is "nothing to check".
     */
    private fun coins(state: GroupWavelengthWalletUIState.Opened): Item? = when (state.expiry) {
        WalletExpiry.Nothing -> null
        is WalletExpiry.Fine -> Item(Kind.COINS, Standing.FINE)
        // The wallet renews it by itself, but only while somebody's app is open.
        is WalletExpiry.Renewing -> Item(Kind.COINS, Standing.ATTENTION)
        is WalletExpiry.Urgent, WalletExpiry.Expired -> Item(Kind.COINS, Standing.PROBLEM, Fix.ADVANCED)
    }

    /** A round needs `2t-1` devices to have answered lately, and the coins decide how soon that matters. */
    private fun devices(state: GroupWavelengthWalletUIState.Opened, now: Instant): Item {
        if (state.seenRecently(now) >= 2 * state.wallet.statement.threshold - 1) return Item(Kind.DEVICES, Standing.FINE)
        return Item(Kind.DEVICES, if (soon(state.expiry)) Standing.PROBLEM else Standing.ATTENTION)
    }

    /**
     * Somebody has to run it, and their app has to be answering.
     *
     * Two different failures with two different next steps: nobody elected, which only the group can
     * fix, and somebody elected whose device has gone quiet, which this device can take over from.
     */
    private fun whoRunsIt(state: GroupWavelengthWalletUIState.Opened, now: Instant): Item {
        val operator = state.operator?.publicKey
            ?: return Item(Kind.WHO_RUNS_IT, Standing.PROBLEM, if (state.canRunHere && state.isParticipant) Fix.THIS_DEVICE_CAN_RUN_IT else Fix.NOTHING)
        // On the device that runs it, its own process says more than "seen lately" ever could.
        if (state.isOperator) {
            val stopped = state.daemon is GroupWavelengthDaemonStatus.Refused || state.daemon == GroupWavelengthDaemonStatus.Failed
            return Item(Kind.WHO_RUNS_IT, if (stopped) Standing.PROBLEM else Standing.FINE, if (stopped) Fix.CEREMONY else Fix.NOTHING)
        }
        val heard = state.seen[operator]?.let { now - it <= GroupWavelengthWalletUIState.Opened.SEEN_WINDOW } == true
        if (heard) return Item(Kind.WHO_RUNS_IT, Standing.FINE)
        val standing = if (soon(state.expiry)) Standing.PROBLEM else Standing.ATTENTION
        return Item(Kind.WHO_RUNS_IT, standing, if (state.canRunHere && state.isParticipant) Fix.THIS_DEVICE_CAN_RUN_IT else Fix.NOTHING)
    }

    /** What the group has agreed to pay this term, against what it said it would. */
    private fun fees(state: GroupWavelengthWalletUIState.Opened): Item {
        val budget = state.wallet.statement.policy.maxMonthlyFeeSat
        if (budget <= 0) return Item(Kind.FEES, Standing.FINE)
        val spent = state.feesSat.toDouble() / budget
        return Item(
            Kind.FEES,
            when {
                spent >= 1.0 -> Standing.PROBLEM
                spent >= BUDGET_ATTENTION -> Standing.ATTENTION
                else -> Standing.FINE
            },
            if (spent >= BUDGET_ATTENTION) Fix.ADVANCED else Fix.NOTHING,
        )
    }

    /**
     * Somebody who left the group still holds a share of this key, and Iceberg cannot reshare.
     *
     * Never amber. Either nobody who left holds a share, or people who can no longer be asked to leave
     * the group can still help sign for its money, and the only fix is a new key and a new wallet.
     */
    private fun departed(state: GroupWavelengthWalletUIState.Opened): Item =
        if (state.departed.isEmpty()) Item(Kind.DEPARTED, Standing.FINE)
        else Item(Kind.DEPARTED, Standing.PROBLEM, Fix.CEREMONY)

    /** Whether the coins are close enough that the group's other failings start to cost money. */
    private fun soon(expiry: WalletExpiry): Boolean = when (expiry) {
        WalletExpiry.Nothing -> false
        is WalletExpiry.Fine -> expiry.blocksLeft <= SOON_BLOCKS
        is WalletExpiry.Renewing -> expiry.blocksLeft <= SOON_BLOCKS
        is WalletExpiry.Urgent, WalletExpiry.Expired -> true
    }
}
