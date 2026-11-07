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
fun entryStatus(entry: GroupWavelengthEntry): String = when (entry.kind) {
    GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN, GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING -> when (entry.status) {
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.wavelength_entry_waiting_for_payment)
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_coin_settled)
        GroupWavelengthEntry.Status.FAILED -> stringResource(Res.string.wavelength_entry_failed)
        else -> stringResource(Res.string.group_wavelength_coin_pending)
    }

    GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_payout_paid)
        GroupWavelengthEntry.Status.FAILED -> stringResource(
            Res.string.group_wavelength_payout_failed_s,
            (entry.detail as? GroupWavelengthEntry.Detail.LightningPayout)?.line?.failure.orEmpty(),
        )
        GroupWavelengthEntry.Status.IN_FLIGHT -> stringResource(Res.string.group_wavelength_payout_in_flight)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_onchain_payout_expired)
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.group_wavelength_payout_approved)
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
