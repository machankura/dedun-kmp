package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.acinq.lightning.payment.Bolt11Invoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.any_amount
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.expired
import dedun.composeapp.generated.resources.failed
import dedun.composeapp.generated.resources.lightning_invoice
import dedun.composeapp.generated.resources.paid
import dedun.composeapp.generated.resources.pay
import dedun.composeapp.generated.resources.pay_now

@Composable
internal fun LightningInvoiceCard(
    invoice: String,
    decoded: Bolt11Invoice,
    onPayInvoice: (suspend (String) -> Boolean)?
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val isExpired = decoded.isExpired()
    var showConfirm by remember { mutableStateOf(false) }
    var payState by remember { mutableStateOf(InvoicePayState.Idle) }
    val scope = rememberCoroutineScope()

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(Res.string.lightning_invoice)) },
            text = {
                Column {
                    Text(
                        text = "${decoded.amount?.truncateToSatoshi() ?: "Any amount"}"
                    )
                    if (!decoded.description.isNullOrBlank()) {
                        Spacer(Modifier.height(MaterialTheme.spacing.space50))
                        Text(
                            text = decoded.description!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        if (onPayInvoice != null) {
                            scope.launch {
                                payState = InvoicePayState.Paying
                                val success = onPayInvoice(invoice)
                                payState = if (success) InvoicePayState.Success else InvoicePayState.Failed
                                delay(3000)
                                payState = InvoicePayState.Idle
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary,
                        contentColor = onPrimary
                    )
                ) { Text(stringResource(Res.string.pay)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.space50),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, primary.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.space150, vertical = MaterialTheme.spacing.space125),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = Decorative,
                tint = primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(MaterialTheme.spacing.space125))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (decoded.amount != null)
                        "${decoded.amount!!.truncateToSatoshi()}"
                    else stringResource(Res.string.any_amount),
                    style = MaterialTheme.typography.titleSmall,
                    color = primary
                )
                if (!decoded.description.isNullOrBlank()) {
                    Text(
                        text = decoded.description!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                if (isExpired) {
                    Text(
                        text = stringResource(Res.string.expired),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (onPayInvoice != null) {
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
                when (payState) {
                    InvoicePayState.Paying -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = primary,
                        strokeWidth = 2.dp
                    )
                    InvoicePayState.Success -> Text(
                        text = stringResource(Res.string.paid),
                        style = MaterialTheme.typography.labelMedium,
                        color = primary
                    )
                    InvoicePayState.Failed -> Text(
                        text = stringResource(Res.string.failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    InvoicePayState.Idle -> Button(
                        onClick = { if (!isExpired) showConfirm = true },
                        enabled = !isExpired,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = onPrimary
                        )
                    ) {
                        Text(
                            if (isExpired) stringResource(Res.string.expired)
                            else stringResource(Res.string.pay_now)
                        )
                    }
                }
            }
        }
    }
}