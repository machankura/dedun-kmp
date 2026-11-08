package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.theme.reducedMotion
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.wallet.InvoiceExpiry
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.payment_received
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.the_operator_says_a_payment_arrived
import dedun.composeapp.generated.resources.this_invoice_expired
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The two states a payment request reaches when it stops being scannable, and the panel they
 * share -- `docs/paid-requests.md`.
 *
 * [ErrorState], [EmptyState] and [LoadingDataIndicator] cover the states a *screen* has. These
 * two belong to the request on it: the money arrived, or the window closed. Both take the QR
 * code's place rather than hiding it, because a gap where a code was reads as a bug, and both
 * leave the request's text below them, since a paid or lapsed invoice is still worth reading
 * against what a payer says they paid and is never worth scanning again.
 *
 * They are panels inside a column rather than a screen's whole body, which is why they sit here
 * and not in `ScreenState.kt` beside the other three.
 */

/**
 * Money has arrived against the request this panel replaces.
 *
 * @param amount what arrived, or null where the screen cannot attribute a figure: an address
 *   paid and spent before it opened has a zero balance, and *Payment received* with no number is
 *   honest where a zero would not be.
 * @param note one line on what this means for the request that was here -- that a paid invoice
 *   must not be paid again, that the address below is fresh.
 * @param onTheWordOf the operator's name, where only the operator says the payment happened and
 *   this device has no record of its own (`docs/paid-requests.md`, D4). Null where the device
 *   knows it itself.
 * @param action the way to ask for another request, where the screen has one.
 */
@Composable
fun PaymentReceived(
    amount: Satoshi?,
    modifier: Modifier = Modifier,
    note: String? = null,
    onTheWordOf: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    RequestPanel(
        modifier = modifier,
        icon = Icons.Default.CheckCircle,
        // A pairing the theme has measured in all six schemes. A success green written here
        // would belong to none of them and be wrong in five.
        container = MaterialTheme.colorScheme.tertiaryContainer,
        onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
        title = stringResource(Res.string.payment_received),
        amount = amount,
        note = note,
        footnote = onTheWordOf?.let { stringResource(Res.string.the_operator_says_a_payment_arrived, it) },
        action = action,
    )
}

/**
 * The invoice this panel replaces has expired, and nobody can pay it any more.
 *
 * Neutral rather than the error pair: an invoice lapsing is what invoices do, the member has
 * lost nothing, and the only news is that this one needs replacing. The alarm the error colour
 * carries belongs to a payment that failed.
 *
 * @param note one line on what to do about it.
 * @param action the way to ask for another invoice, where the screen has one.
 */
@Composable
fun InvoiceExpired(
    modifier: Modifier = Modifier,
    note: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    RequestPanel(
        modifier = modifier,
        icon = Icons.Default.HourglassEmpty,
        container = MaterialTheme.colorScheme.surfaceVariant,
        onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
        title = stringResource(Res.string.this_invoice_expired),
        amount = null,
        note = note,
        footnote = null,
        action = action,
    )
}

/**
 * Whether [bolt11] has expired, recomposing once when it does.
 *
 * One timer per invoice, not a poll: [InvoiceExpiry] gives the moment, and this waits for it.
 * An invoice already expired when the screen opens reads as expired on the first frame, and one
 * whose expiry this app cannot work out never does -- so a QR code goes only when something is
 * known about it. The screen's own state decides what wins: a payment that landed in the last
 * second is still a payment, so every caller checks paid before expired.
 */
@Composable
fun rememberInvoiceExpired(bolt11: String?): Boolean {
    val expiresAt = remember(bolt11) { bolt11?.let(InvoiceExpiry::expiresAt) }
    var expired by remember(bolt11) { mutableStateOf(false) }
    LaunchedEffect(bolt11, expiresAt) {
        if (expiresAt == null) return@LaunchedEffect
        val left = expiresAt - Clock.System.now()
        if (left.isPositive()) delay(left)
        expired = true
    }
    return expired
}

/**
 * What both states draw: an icon, a title, optionally an amount, a line, a footnote, and the way
 * on. It animates itself in because it is composed in place of a QR code that was already there,
 * so there is no state change for an `AnimatedContent` to key on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RequestPanel(
    modifier: Modifier,
    icon: ImageVector,
    container: Color,
    onContainer: Color,
    title: String,
    amount: Satoshi?,
    note: String?,
    footnote: String?,
    action: (@Composable () -> Unit)?,
) {
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }

    val reduced = MaterialTheme.reducedMotion
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    AnimatedVisibility(
        visible = arrived,
        modifier = modifier,
        // Reduced motion drops the rise, not the transition: a request changing state is worth
        // announcing, and a panel that simply appears is the announcement cut short.
        enter = if (reduced) {
            fadeIn(effects)
        } else {
            fadeIn(effects) + slideInVertically(spatial) { height -> height / RiseDivisor }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = onContainer,
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                // Decorative: the title under it says what happened, and naming the icon would
                // have a screen reader announce it twice.
                Icon(icon, contentDescription = Decorative)
                Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                // A zero is as uninformative as no figure at all, and a deposit's amount is
                // zero until the chain says otherwise -- the same rule the activity list's
                // `signedAmount` already follows.
                amount?.takeIf { it.sat > 0 }?.let {
                    Text(
                        text = stringResource(Res.string.s_sats, it.grouped()),
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                note?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                footnote?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                action?.invoke()
            }
        }
    }
}

/**
 * How far the panel rises as it fades in, as a fraction of its own height.
 *
 * A quarter: enough to read as arriving from below, short of the travel M3 gives content that is
 * actually changing place.
 */
private const val RiseDivisor = 4
