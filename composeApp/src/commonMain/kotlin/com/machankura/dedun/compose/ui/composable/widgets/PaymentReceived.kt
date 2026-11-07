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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.theme.reducedMotion
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.payment_received
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.the_operator_says_a_payment_arrived
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * The fourth state a screen has, and the one that had no home.
 *
 * [ErrorState], [EmptyState] and [LoadingDataIndicator] are shared because sixteen
 * hand-rolled copies of one of them was how every failure in this app became a dead end.
 * *This worked* is the state those three never covered, and seven screens in this tree
 * hand out a payment request without it: three keep showing a paid invoice or address
 * until the screen is left, and four swap the address for a fresh one and say nothing.
 *
 * It is a panel inside a column rather than a screen's whole body, which is why it sits
 * here and not in `ScreenState.kt` beside the other three.
 *
 * **It takes the place of the QR code, and the request's text stays below it.** A paid
 * request is still worth reading -- a member checking what they handed out against what a
 * payer says they paid -- and is never worth scanning again. That is the rule
 * `docs/group-wallet-addresses.md` already states for a used address, spread to the other
 * six surfaces by `docs/paid-requests.md`.
 *
 * @param amount what arrived, or null where the screen cannot attribute a figure: an
 *   address paid and spent before it opened has a zero balance, and *Payment received*
 *   with no number is honest where a zero would not be.
 * @param note one line on what this means for the request that was here -- that a paid
 *   invoice must not be paid again, that the address below is fresh.
 * @param onTheWordOf the operator's name, where only the operator says the payment
 *   happened and this device has no record of its own (`docs/paid-requests.md`, D4).
 *   Null where the device knows it itself.
 * @param action the way to ask for another request, where the screen has one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaymentReceived(
    amount: Satoshi?,
    modifier: Modifier = Modifier,
    note: String? = null,
    onTheWordOf: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    // The panel is composed in place of a QR code that was already there, so there is no
    // state change for `AnimatedContent` to key on: it animates itself in on arrival.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }

    val reduced = MaterialTheme.reducedMotion
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    AnimatedVisibility(
        visible = arrived,
        modifier = modifier,
        // Reduced motion drops the rise, not the transition: money arriving is worth
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
            // A pairing the theme has measured in all six schemes. A success green written
            // here would belong to none of them and be wrong in five.
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                // Decorative: the line under it says a payment was received, and naming the
                // tick would have a screen reader announce it twice.
                Icon(Icons.Default.CheckCircle, contentDescription = Decorative)
                Text(
                    text = stringResource(Res.string.payment_received),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                amount?.let {
                    Text(
                        text = stringResource(Res.string.s_sats, it.grouped()),
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                note?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                onTheWordOf?.let { operator ->
                    Text(
                        text = stringResource(Res.string.the_operator_says_a_payment_arrived, operator),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                action?.invoke()
            }
        }
    }
}

/**
 * How far the panel rises as it fades in, as a fraction of its own height.
 *
 * A quarter: enough to read as arriving from below, short of the travel M3 gives content
 * that is actually changing place.
 */
private const val RiseDivisor = 4
