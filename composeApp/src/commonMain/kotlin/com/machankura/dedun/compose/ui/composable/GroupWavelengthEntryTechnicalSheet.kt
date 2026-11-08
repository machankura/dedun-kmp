package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.text.groupedBlocks
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.machankura.dedun.compose.ui.composable.widgets.wallet.DetailLine
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.group_wavelength_advanced_untested_refund
import dedun.composeapp.generated.resources.group_wavelength_coin_expires_s
import dedun.composeapp.generated.resources.group_wavelength_coin_held
import dedun.composeapp.generated.resources.group_wavelength_coin_pending
import dedun.composeapp.generated.resources.group_wavelength_coin_settled
import dedun.composeapp.generated.resources.group_wavelength_coin_spent
import dedun.composeapp.generated.resources.group_wavelength_invoice_warning
import dedun.composeapp.generated.resources.group_wavelength_tech_approval
import dedun.composeapp.generated.resources.group_wavelength_tech_approval_id
import dedun.composeapp.generated.resources.group_wavelength_tech_ark
import dedun.composeapp.generated.resources.group_wavelength_tech_asked
import dedun.composeapp.generated.resources.group_wavelength_tech_asked_for_s
import dedun.composeapp.generated.resources.group_wavelength_tech_attributed
import dedun.composeapp.generated.resources.group_wavelength_tech_by_boarding
import dedun.composeapp.generated.resources.group_wavelength_tech_by_group
import dedun.composeapp.generated.resources.group_wavelength_tech_by_invoice
import dedun.composeapp.generated.resources.group_wavelength_tech_by_operator
import dedun.composeapp.generated.resources.group_wavelength_tech_coin
import dedun.composeapp.generated.resources.group_wavelength_tech_block
import dedun.composeapp.generated.resources.group_wavelength_tech_confirmations
import dedun.composeapp.generated.resources.group_wavelength_tech_block_mempool
import dedun.composeapp.generated.resources.group_wavelength_tech_depth_s_s
import dedun.composeapp.generated.resources.group_wavelength_tech_spent_unseen
import dedun.composeapp.generated.resources.group_wavelength_tech_coins_made_s
import dedun.composeapp.generated.resources.group_wavelength_tech_confirmed_at
import dedun.composeapp.generated.resources.group_wavelength_tech_deposit_key
import dedun.composeapp.generated.resources.group_wavelength_tech_description_hash
import dedun.composeapp.generated.resources.group_wavelength_tech_destination
import dedun.composeapp.generated.resources.group_wavelength_tech_device
import dedun.composeapp.generated.resources.group_wavelength_tech_expires
import dedun.composeapp.generated.resources.group_wavelength_tech_held
import dedun.composeapp.generated.resources.group_wavelength_tech_invoice
import dedun.composeapp.generated.resources.group_wavelength_tech_lnurl
import dedun.composeapp.generated.resources.group_wavelength_tech_max_fee
import dedun.composeapp.generated.resources.group_wavelength_tech_no_deadline
import dedun.composeapp.generated.resources.group_wavelength_tech_no_proof
import dedun.composeapp.generated.resources.group_wavelength_tech_no_record
import dedun.composeapp.generated.resources.group_wavelength_tech_nothing
import dedun.composeapp.generated.resources.group_wavelength_tech_open
import dedun.composeapp.generated.resources.group_wavelength_tech_operator
import dedun.composeapp.generated.resources.group_wavelength_tech_refund_height
import dedun.composeapp.generated.resources.group_wavelength_tech_refunded_in
import dedun.composeapp.generated.resources.group_wavelength_tech_request
import dedun.composeapp.generated.resources.group_wavelength_tech_round
import dedun.composeapp.generated.resources.group_wavelength_tech_said_at_s_s
import dedun.composeapp.generated.resources.group_wavelength_tech_script
import dedun.composeapp.generated.resources.group_wavelength_tech_seen
import dedun.composeapp.generated.resources.group_wavelength_tech_signed_at
import dedun.composeapp.generated.resources.group_wavelength_tech_term
import dedun.composeapp.generated.resources.group_wavelength_tech_their_fee
import dedun.composeapp.generated.resources.group_wavelength_tech_their_line
import dedun.composeapp.generated.resources.group_wavelength_tech_their_time
import dedun.composeapp.generated.resources.group_wavelength_tech_untested
import dedun.composeapp.generated.resources.group_wavelength_tech_vhtlc
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.wavelength_amount
import dedun.composeapp.generated.resources.wavelength_entry_failure
import dedun.composeapp.generated.resources.wavelength_entry_payment_hash
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import org.jetbrains.compose.resources.stringResource
import fr.acinq.bitcoin.Satoshi
import kotlin.time.Instant

/**
 * Every field this device holds about one transaction, and every field the operator claims, under headings
 * that say which is which (`docs/group-wavelength-transaction-details.md`, T5).
 *
 * Almost everything technical about this wallet is either a thing this device signed or a thing the
 * operator says, and a sheet can put those under two headings where a summary cannot. A field the device
 * doesn't hold is left out rather than shown empty: a blank beside a label reads as a value of nothing.
 */
@Composable
fun GroupWavelengthEntryTechnicalSheet(entry: GroupWavelengthEntry, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Text(
            stringResource(Res.string.group_wavelength_tech_open),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val detail = entry.detail) {
            is GroupWavelengthEntry.Detail.OnchainDeposit -> onchainDeposit(detail)
            is GroupWavelengthEntry.Detail.LightningDeposit -> lightningDeposit(detail)
            is GroupWavelengthEntry.Detail.LightningPayout -> lightningPayout(detail)
            is GroupWavelengthEntry.Detail.OnchainPayout -> onchainPayout(detail)
            is GroupWavelengthEntry.Detail.Round -> round(detail)
            is GroupWavelengthEntry.Detail.Exit -> exit(detail)
            is GroupWavelengthEntry.Detail.OperatorLine -> operatorLine(detail)
        }
        untested(entry)
    }
}

/** A heading and the fields under it, or nothing at all when this device holds none of them. */
@Composable
private fun ColumnScope.section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) { content() }
}

private fun seconds(at: Long?): Instant? = at?.let { Instant.fromEpochSeconds(it) }

@Composable
private fun ColumnScope.onchainDeposit(detail: GroupWavelengthEntry.Detail.OnchainDeposit) {
    section(stringResource(Res.string.group_wavelength_tech_device)) {
        detail.boarded?.let { boarded ->
            DetailLine(stringResource(Res.string.group_wavelength_tech_coin), boarded.outpoint, copyable = true)
            DetailLine(stringResource(Res.string.group_wavelength_tech_round), boarded.commitment, copyable = true)
            DetailLine(stringResource(Res.string.group_wavelength_tech_script), boarded.script, copyable = true)
            seconds(boarded.atEpochSeconds)?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_signed_at), it.toFormattedTimeAndDateString()) }
        }
        detail.deposit?.let { row ->
            DetailLine(stringResource(Res.string.group_wavelength_tech_deposit_key), row.depositKey, copyable = true)
            DetailLine(stringResource(Res.string.wavelength_amount), stringResource(Res.string.s_sats, Satoshi(row.amountMsat / 1000).grouped()))
            attribution(row)
            DetailLine(stringResource(Res.string.group_wavelength_tech_seen), row.seenAt.toFormattedTimeAndDateString())
            row.confirmedAt?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_confirmed_at), it.toFormattedTimeAndDateString()) }
        }
        detail.arriving?.let { arriving ->
            val row = arriving.row
            DetailLine(stringResource(Res.string.group_wavelength_tech_coin), row.outpoint, copyable = true)
            DetailLine(stringResource(Res.string.wavelength_amount), stringResource(Res.string.s_sats, Satoshi(row.amountSat).grouped()))
            DetailLine(stringResource(Res.string.group_wavelength_tech_script), row.script, copyable = true)
            DetailLine(
                stringResource(Res.string.group_wavelength_tech_block),
                row.blockHeight?.toString() ?: stringResource(Res.string.group_wavelength_tech_block_mempool),
            )
            DetailLine(
                stringResource(Res.string.group_wavelength_tech_confirmations),
                stringResource(Res.string.group_wavelength_tech_depth_s_s, arriving.depth, arriving.needed),
            )
            seconds(row.firstSeenAtEpochSeconds)?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_seen), it.toFormattedTimeAndDateString()) }
            seconds(row.spentUnseenAtEpochSeconds)?.let {
                DetailLine(stringResource(Res.string.group_wavelength_tech_spent_unseen), it.toFormattedTimeAndDateString())
            }
        }
        coin(detail.coin)
        if (detail.boarded == null && detail.deposit == null && detail.coin == null && detail.arriving == null) {
            Text(stringResource(Res.string.group_wavelength_tech_nothing), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColumnScope.lightningDeposit(detail: GroupWavelengthEntry.Detail.LightningDeposit) {
    section(stringResource(Res.string.group_wavelength_tech_device)) {
        detail.claim?.let { claim ->
            DetailLine(stringResource(Res.string.group_wavelength_tech_ark), claim.ark, copyable = true)
            DetailLine(stringResource(Res.string.wavelength_entry_payment_hash), claim.paymentHash, copyable = true)
            DetailLine(stringResource(Res.string.group_wavelength_tech_held), stringResource(Res.string.s_sats, Satoshi(claim.amountSat).grouped()))
            seconds(claim.atEpochSeconds)?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_signed_at), it.toFormattedTimeAndDateString()) }
        }
        detail.deposit?.let { row ->
            DetailLine(stringResource(Res.string.group_wavelength_tech_deposit_key), row.depositKey, copyable = true)
            attribution(row)
            row.confirmedAt?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_confirmed_at), it.toFormattedTimeAndDateString()) }
        }
        // A claimed coin's batch is one this device never saw confirm, so it can work out no deadline for
        // it: a known gap of the wallet note's, said to whoever opened the detail.
        Text(stringResource(Res.string.group_wavelength_tech_no_deadline), style = MaterialTheme.typography.bodyMedium)
    }
    detail.request?.let { request ->
        section(stringResource(Res.string.group_wavelength_tech_approval)) {
            DetailLine(stringResource(Res.string.group_wavelength_tech_request), request.requestId, copyable = true)
            request.invoiceAmountSat?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_asked), stringResource(Res.string.group_wavelength_tech_asked_for_s, Satoshi(it).grouped())) }
            request.invoice?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_invoice), it, copyable = true) }
            request.paymentHash?.let { DetailLine(stringResource(Res.string.wavelength_entry_payment_hash), it, copyable = true) }
        }
    }
}

@Composable
private fun ColumnScope.lightningPayout(detail: GroupWavelengthEntry.Detail.LightningPayout) {
    detail.signed?.let { signed ->
        section(stringResource(Res.string.group_wavelength_tech_device)) {
            DetailLine(stringResource(Res.string.group_wavelength_tech_ark), signed.tx, copyable = true)
            signed.amountSat?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_held), stringResource(Res.string.s_sats, Satoshi(it).grouped())) }
            signed.vhtlcScript?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_vhtlc), it, copyable = true) }
            signed.refundedIn?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_refunded_in), it, copyable = true) }
            seconds(signed.atEpochSeconds)?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_signed_at), it.toFormattedTimeAndDateString()) }
        }
    }
    section(stringResource(Res.string.group_wavelength_tech_approval)) {
        DetailLine(stringResource(Res.string.group_wavelength_tech_invoice), detail.approval.invoice, copyable = true)
        detail.approval.paymentHash?.let { DetailLine(stringResource(Res.string.wavelength_entry_payment_hash), it.toString(), copyable = true) }
        detail.approval.descriptionHash?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_description_hash), it.toString(), copyable = true) }
        detail.approval.lnurl?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_lnurl), it, copyable = true) }
        detail.approval.payout?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_destination), it.destinationEventId, copyable = true) }
        DetailLine(stringResource(Res.string.group_wavelength_tech_max_fee), stringResource(Res.string.s_sats, Satoshi(detail.approval.maxFeeSat).grouped()))
        DetailLine(stringResource(Res.string.group_wavelength_tech_refund_height), detail.approval.maxRefundHeight.groupedBlocks())
        DetailLine(stringResource(Res.string.group_wavelength_tech_expires), Instant.fromEpochSeconds(detail.approval.expiresAt).toFormattedTimeAndDateString())
    }
    detail.line?.let { line ->
        section(stringResource(Res.string.group_wavelength_tech_operator)) {
            DetailLine(stringResource(Res.string.group_wavelength_tech_their_line), line.status)
            DetailLine(stringResource(Res.string.group_wavelength_tech_their_fee), stringResource(Res.string.s_sats, Satoshi(line.feeSat).grouped()))
            seconds(line.at.takeIf { it > 0 })?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_their_time), it.toFormattedTimeAndDateString()) }
            line.failure.takeIf { it.isNotBlank() }?.let { DetailLine(stringResource(Res.string.wavelength_entry_failure), it) }
        }
    }
    // Answered here by default rather than by the owner: the group's wallet never sees a preimage, and a
    // row that said nothing would read as a wallet that lost one.
    Text(stringResource(Res.string.group_wavelength_tech_no_proof), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ColumnScope.onchainPayout(detail: GroupWavelengthEntry.Detail.OnchainPayout) {
    detail.leave?.let { leave ->
        section(stringResource(Res.string.group_wavelength_tech_device)) {
            DetailLine(stringResource(Res.string.group_wavelength_tech_round), leave.commitment, copyable = true)
            DetailLine(stringResource(Res.string.group_wavelength_tech_script), leave.script, copyable = true)
            DetailLine(stringResource(Res.string.wavelength_amount), stringResource(Res.string.s_sats, Satoshi(leave.amountSat).grouped()))
            seconds(leave.atEpochSeconds)?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_signed_at), it.toFormattedTimeAndDateString()) }
            seconds(leave.confirmedAtEpochSeconds)?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_confirmed_at), it.toFormattedTimeAndDateString()) }
        }
    }
    section(stringResource(Res.string.group_wavelength_tech_approval)) {
        DetailLine(stringResource(Res.string.group_wavelength_tech_approval_id), detail.approval.id, copyable = true)
        DetailLine(stringResource(Res.string.group_wavelength_tech_destination), detail.approval.destinationEventId, copyable = true)
        DetailLine(stringResource(Res.string.group_wavelength_tech_max_fee), stringResource(Res.string.s_sats, Satoshi(detail.approval.maxFeeSat).grouped()))
        DetailLine(stringResource(Res.string.group_wavelength_tech_asked), Instant.fromEpochSeconds(detail.approval.askedAt).toFormattedTimeAndDateString())
        DetailLine(stringResource(Res.string.group_wavelength_tech_expires), Instant.fromEpochSeconds(detail.approval.expiresAt).toFormattedTimeAndDateString())
    }
}

@Composable
private fun ColumnScope.round(detail: GroupWavelengthEntry.Detail.Round) {
    section(stringResource(Res.string.group_wavelength_tech_device)) {
        detail.round?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_round), it.commitment, copyable = true) }
        detail.fee?.let { fee ->
            if (detail.round == null) DetailLine(stringResource(Res.string.group_wavelength_tech_ark), fee.tx, copyable = true)
            DetailLine(stringResource(Res.string.group_wavelength_tech_term), fee.term.toString())
            DetailLine(stringResource(Res.string.group_wavelength_tech_signed_at), Instant.fromEpochSeconds(fee.atEpochSeconds).toFormattedTimeAndDateString())
        }
        seconds(detail.round?.atEpochSeconds)?.takeIf { detail.fee == null }?.let {
            DetailLine(stringResource(Res.string.group_wavelength_tech_signed_at), it.toFormattedTimeAndDateString())
        }
        DetailLine(stringResource(Res.string.group_wavelength_tech_coins_made_s, detail.made.size.toString()), detail.made.joinToString { it.outpoint })
    }
}

@Composable
private fun ColumnScope.exit(detail: GroupWavelengthEntry.Detail.Exit) {
    section(stringResource(Res.string.group_wavelength_tech_approval)) {
        DetailLine(stringResource(Res.string.group_wavelength_tech_asked), Instant.fromEpochSeconds(detail.approval.askedAt).toFormattedTimeAndDateString())
        DetailLine(stringResource(Res.string.group_wavelength_tech_expires), Instant.fromEpochSeconds(detail.approval.expiresAt).toFormattedTimeAndDateString())
    }
}

@Composable
private fun ColumnScope.operatorLine(detail: GroupWavelengthEntry.Detail.OperatorLine) {
    section(stringResource(Res.string.group_wavelength_tech_operator)) {
        DetailLine(stringResource(Res.string.group_wavelength_tech_their_line), detail.line.id, copyable = true)
        DetailLine(stringResource(Res.string.group_wavelength_tech_their_fee), stringResource(Res.string.s_sats, Satoshi(detail.line.feeSat).grouped()))
        detail.line.paymentHash.takeIf { it.isNotBlank() }?.let { DetailLine(stringResource(Res.string.wavelength_entry_payment_hash), it, copyable = true) }
        detail.line.failure.takeIf { it.isNotBlank() }?.let { DetailLine(stringResource(Res.string.wavelength_entry_failure), it) }
        DetailLine(stringResource(Res.string.group_wavelength_tech_term), detail.term.toString())
        DetailLine(
            label = stringResource(Res.string.group_wavelength_tech_their_time),
            value = stringResource(
                Res.string.group_wavelength_tech_said_at_s_s,
                Instant.fromEpochSeconds(detail.sentAt).toFormattedTimeAndDateString(),
                detail.tip.groupedBlocks(),
            ),
        )
    }
    Text(stringResource(Res.string.group_wavelength_tech_no_record), style = MaterialTheme.typography.bodyMedium)
}

/** What this device knows of the coin a deposit became, where it holds one. */
@Composable
private fun ColumnScope.coin(coin: com.machankura.dedun.compose.database.model.GroupArkVtxo?) {
    if (coin == null) return
    DetailLine(stringResource(Res.string.group_wavelength_tech_coin), coin.outpoint, copyable = true)
    DetailLine(
        label = stringResource(Res.string.group_wavelength_tech_round),
        value = coin.origin.orEmpty(),
        copyable = coin.origin != null,
    )
    DetailLine(
        label = stringResource(Res.string.group_wavelength_tech_their_line),
        value = stringResource(
            when {
                coin.spent -> Res.string.group_wavelength_coin_spent
                !coin.settled -> Res.string.group_wavelength_coin_pending
                coin.reservedBy != null -> Res.string.group_wavelength_coin_held
                else -> Res.string.group_wavelength_coin_settled
            }
        ),
    )
    coin.expiresAtHeight?.let { DetailLine(stringResource(Res.string.group_wavelength_tech_coin), stringResource(Res.string.group_wavelength_coin_expires_s, it.groupedBlocks())) }
}

/** Whose a deposit is, and how firmly: the weakest of these is the operator's bare word (D6). */
@Composable
private fun ColumnScope.attribution(row: DepositRow) {
    val how = when (row.attributedBy) {
        DepositRow.BY_BOARDING_ADDRESS -> Res.string.group_wavelength_tech_by_boarding
        DepositRow.BY_INVOICE -> Res.string.group_wavelength_tech_by_invoice
        DepositRow.BY_OPERATOR -> Res.string.group_wavelength_tech_by_operator
        DepositRow.BY_GROUP -> Res.string.group_wavelength_tech_by_group
        else -> return
    }
    DetailLine(stringResource(Res.string.group_wavelength_tech_attributed), stringResource(how))
}

/** The known gap this kind sits on, from the wallet note's own list of what the screens may not promise. */
@Composable
private fun ColumnScope.untested(entry: GroupWavelengthEntry) {
    val warning = when (entry.kind) {
        GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING -> Res.string.group_wavelength_invoice_warning
        GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> Res.string.group_wavelength_advanced_untested_refund
        else -> return
    }
    section(stringResource(Res.string.group_wavelength_tech_untested)) {
        Text(stringResource(warning), style = MaterialTheme.typography.bodyMedium)
    }
}
