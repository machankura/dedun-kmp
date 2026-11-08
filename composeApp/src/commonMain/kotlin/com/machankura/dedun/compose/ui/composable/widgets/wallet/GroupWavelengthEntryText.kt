package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.group_wavelength_activity_line_s_s
import dedun.composeapp.generated.resources.group_wavelength_arriving_for_a_round
import dedun.composeapp.generated.resources.group_wavelength_arriving_mempool
import dedun.composeapp.generated.resources.group_wavelength_arriving_s_of_s
import dedun.composeapp.generated.resources.group_wavelength_arriving_taken_unseen
import dedun.composeapp.generated.resources.group_wavelength_coin_pending
import dedun.composeapp.generated.resources.group_wavelength_coin_settled
import dedun.composeapp.generated.resources.group_wavelength_history_exit_s
import dedun.composeapp.generated.resources.group_wavelength_history_in_s
import dedun.composeapp.generated.resources.group_wavelength_history_in_s_s
import dedun.composeapp.generated.resources.group_wavelength_history_ran_out
import dedun.composeapp.generated.resources.group_wavelength_history_round
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_expired
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_in_a_round
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_paid_s
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_s_s
import dedun.composeapp.generated.resources.group_wavelength_payout_approved
import dedun.composeapp.generated.resources.group_wavelength_payout_failed_s
import dedun.composeapp.generated.resources.group_wavelength_payout_in_flight
import dedun.composeapp.generated.resources.group_wavelength_payout_paid
import dedun.composeapp.generated.resources.group_wavelength_payout_s_s
import dedun.composeapp.generated.resources.group_wavelength_payout_went_nowhere_s_s
import dedun.composeapp.generated.resources.wavelength_entry_failed
import dedun.composeapp.generated.resources.wavelength_entry_succeeded
import dedun.composeapp.generated.resources.wavelength_entry_waiting_for_payment
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * What one thing a group's Wavelength wallet did is called, and where it has got to:
 * `docs/group-wavelength-transaction-details.md`. Shared, so the row in the history and the screen about
 * one entry never say it differently -- which they would, written twice.
 */

/** The direction, since the headline says it in words too: a row carries no state the text doesn't. */
fun entryIcon(direction: GroupWavelengthEntry.Direction): ImageVector = when (direction) {
    GroupWavelengthEntry.Direction.IN -> Icons.AutoMirrored.Filled.CallReceived
    GroupWavelengthEntry.Direction.OUT -> Icons.AutoMirrored.Filled.CallMade
    GroupWavelengthEntry.Direction.NONE -> Icons.Default.Autorenew
    GroupWavelengthEntry.Direction.UNKNOWN -> Icons.AutoMirrored.Filled.ReceiptLong
}

@Composable
fun entryHeadline(entry: GroupWavelengthEntry, localChatRoom: LocalChatRoom, reader: HexKey): String {
    val amount = Satoshi(entry.amountSat).grouped()
    val who = memberName(localChatRoom, entry.member, reader)
    return when (entry.kind) {
        GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN, GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING ->
            if (entry.member == null) stringResource(Res.string.group_wavelength_history_in_s, amount)
            else stringResource(Res.string.group_wavelength_history_in_s_s, amount, who)
        GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> stringResource(Res.string.group_wavelength_payout_s_s, amount, who)
        GroupWavelengthEntry.Kind.PAYOUT_ONCHAIN -> stringResource(Res.string.group_wavelength_onchain_payout_s_s, amount, who)
        GroupWavelengthEntry.Kind.ROUND -> stringResource(Res.string.group_wavelength_history_round)
        GroupWavelengthEntry.Kind.EXIT -> stringResource(Res.string.group_wavelength_history_exit_s, amount)
        // The daemon's own word for what it was, which this device has no better name for.
        GroupWavelengthEntry.Kind.OPERATOR -> {
            val line = (entry.detail as? GroupWavelengthEntry.Detail.OperatorLine)?.line
            stringResource(Res.string.group_wavelength_activity_line_s_s, line?.kind?.replaceFirstChar { it.uppercase() }.orEmpty(), amount)
        }
    }
}

/** Where it has got to, in the words each kind already used. */
@Composable
fun entryStatus(entry: GroupWavelengthEntry, localChatRoom: LocalChatRoom, reader: HexKey): String = when (entry.kind) {
    GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN, GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING -> when (entry.status) {
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.wavelength_entry_waiting_for_payment)
        GroupWavelengthEntry.Status.ARRIVING -> arrivingStatus(entry)
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_coin_settled)
        GroupWavelengthEntry.Status.FAILED -> stringResource(Res.string.wavelength_entry_failed)
        else -> stringResource(Res.string.group_wavelength_coin_pending)
    }

    GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_payout_paid)
        // A payout that failed before anything was signed tied nothing up, so it says whose balance is
        // free again: the one of the two outcomes a member can act on.
        GroupWavelengthEntry.Status.FAILED -> {
            val payout = entry.detail as? GroupWavelengthEntry.Detail.LightningPayout
            if (payout?.wentNowhere == true) {
                stringResource(
                    Res.string.group_wavelength_payout_went_nowhere_s_s,
                    payout.line?.failure.orEmpty(),
                    memberName(localChatRoom, entry.member, reader),
                )
            } else {
                stringResource(Res.string.group_wavelength_payout_failed_s, payout?.line?.failure.orEmpty())
            }
        }
        GroupWavelengthEntry.Status.IN_FLIGHT -> stringResource(Res.string.group_wavelength_payout_in_flight)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_onchain_payout_expired)
        // ARRIVING is money coming in, which a payout never is. Named rather than left to an `else`, so that
        // adding another status is a compiler error here rather than a wrong word on a screen.
        GroupWavelengthEntry.Status.WAITING, GroupWavelengthEntry.Status.ARRIVING ->
            stringResource(Res.string.group_wavelength_payout_approved)
    }

    GroupWavelengthEntry.Kind.PAYOUT_ONCHAIN -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_onchain_payout_paid_s, Satoshi(entry.feeSat).grouped())
        GroupWavelengthEntry.Status.IN_FLIGHT -> stringResource(Res.string.group_wavelength_onchain_payout_in_a_round)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_onchain_payout_expired)
        else -> stringResource(Res.string.group_wavelength_payout_approved)
    }

    GroupWavelengthEntry.Kind.ROUND, GroupWavelengthEntry.Kind.EXIT -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.wavelength_entry_succeeded)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_history_ran_out)
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.group_wavelength_payout_approved)
        else -> stringResource(Res.string.group_wavelength_coin_pending)
    }

    GroupWavelengthEntry.Kind.OPERATOR -> {
        val line = (entry.detail as? GroupWavelengthEntry.Detail.OperatorLine)?.line
        buildString {
            append(line?.status?.replaceFirstChar { it.uppercase() }.orEmpty())
            if (line?.failure?.isNotBlank() == true) append(": ").append(line.failure)
        }
    }
}

/**
 * Where a boarding payment on the chain has got to, which is the one thing a member can act on about it:
 * `docs/group-wavelength-unconfirmed-deposits.md`, A7 and A12.
 *
 * Three states in one status, because the answer to all three is *wait* and only the wait differs: a
 * transaction with no block yet, one confirming towards the depth the operator asks for, and one deep enough
 * and waiting for a round -- which is also what says the group should nominate somebody if nobody is running
 * the wallet. The fourth is A12's: a coin a round this device never signed has taken in.
 */
@Composable
private fun arrivingStatus(entry: GroupWavelengthEntry): String {
    val arriving = (entry.detail as? GroupWavelengthEntry.Detail.OnchainDeposit)?.arriving
        ?: return stringResource(Res.string.group_wavelength_coin_pending)
    return when {
        arriving.row.spentUnseenAtEpochSeconds != null -> stringResource(Res.string.group_wavelength_arriving_taken_unseen)
        arriving.depth <= 0 -> stringResource(Res.string.group_wavelength_arriving_mempool)
        arriving.depth < arriving.needed -> stringResource(Res.string.group_wavelength_arriving_s_of_s, arriving.depth, arriving.needed)
        else -> stringResource(Res.string.group_wavelength_arriving_for_a_round)
    }
}

/**
 * Whether the fee and the time on this entry are the operator's word rather than this device's.
 *
 * A Lightning payout is the one kind where the two are mixed: the group signed the approval and this
 * device signed the payment, so its amount is its own, but what it cost to route and when it arrived are
 * only ever reported (`docs/group-wavelength-transaction-details.md`, Phase 6). Said on the detail, where
 * the provenance belongs, rather than in the row, which is a summary.
 */
fun entryProgressIsTheirs(entry: GroupWavelengthEntry): Boolean = when (val detail = entry.detail) {
    is GroupWavelengthEntry.Detail.LightningPayout -> detail.line != null
    is GroupWavelengthEntry.Detail.OperatorLine -> true
    else -> false
}
