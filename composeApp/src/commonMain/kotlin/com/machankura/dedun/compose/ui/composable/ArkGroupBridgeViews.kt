package com.machankura.dedun.compose.ui.composable

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkGroupBridge
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.ark_pay_from_my_wallet
import dedun.composeapp.generated.resources.ark_pay_s_sats_from_your_wallet_question
import dedun.composeapp.generated.resources.ark_pay_with_a_fee_of_s_sats
import dedun.composeapp.generated.resources.ark_send_could_not_send
import dedun.composeapp.generated.resources.ark_send_not_enough
import dedun.composeapp.generated.resources.ark_send_payment_sent
import dedun.composeapp.generated.resources.ark_use_my_wallet_s_onchain_address
import dedun.composeapp.generated.resources.cancel
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * *Pay from my wallet* under a group's deposit invoice -- `docs/bark-wallet.md`, Phase 10.
 * Drawn only when the profile's Ark wallet is open on the invoice's network; priced, then
 * confirmed, then paid.
 */
@Composable
fun ArkPayFromWalletButton(identityArk: IdentityArk?, bolt11: String, chain: Chain) {
    identityArk ?: return
    val walletState by identityArk.state.collectAsState()
    val bridge = remember(identityArk) { ArkGroupBridge(identityArk.state) }
    if (!ArkGroupBridge.offered(walletState, chain)) return

    val scope = rememberCoroutineScope()
    val notify = rememberNotifier(scope)
    val sent = stringResource(Res.string.ark_send_payment_sent)
    val couldNotSend = stringResource(Res.string.ark_send_could_not_send)
    val notEnough = stringResource(Res.string.ark_send_not_enough)
    var quote by remember(bolt11) { mutableStateOf<ArkGroupBridge.Quote?>(null) }

    quote?.let { priced ->
        AlertDialog(
            onDismissRequest = { quote = null },
            title = { Text(stringResource(Res.string.ark_pay_s_sats_from_your_wallet_question, priced.amount.grouped())) },
            text = { Text(stringResource(Res.string.ark_pay_with_a_fee_of_s_sats, priced.fee.grouped())) },
            confirmButton = {
                TextButton(onClick = {
                    quote = null
                    scope.launch {
                        try {
                            bridge.pay(priced, chain)
                            notify(sent)
                        } catch (e: ArkError) {
                            notify(couldNotSend)
                        }
                    }
                }) { Text(stringResource(Res.string.ark_pay_from_my_wallet)) }
            },
            dismissButton = { TextButton(onClick = { quote = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    FilledTonalButton(onClick = {
        scope.launch {
            val priced = bridge.quote(bolt11, chain)
            when {
                priced == null -> notify(couldNotSend)
                !priced.covered -> notify(notEnough)
                else -> quote = priced
            }
        }
    }) { Text(stringResource(Res.string.ark_pay_from_my_wallet)) }
}

/**
 * *Use my wallet's onchain address* beside a payout destination's onchain field -- Phase 10.
 * The address is new, so unused by construction, which is what the form checks.
 */
@Composable
fun ArkUseWalletAddressButton(identityArk: IdentityArk?, chain: Chain, onAddress: (String) -> Unit) {
    identityArk ?: return
    val walletState by identityArk.state.collectAsState()
    val bridge = remember(identityArk) { ArkGroupBridge(identityArk.state) }
    if (!ArkGroupBridge.offered(walletState, chain)) return
    val scope = rememberCoroutineScope()
    TextButton(onClick = { scope.launch { bridge.onchainAddress(chain)?.let(onAddress) } }) {
        Text(stringResource(Res.string.ark_use_my_wallet_s_onchain_address))
    }
}
