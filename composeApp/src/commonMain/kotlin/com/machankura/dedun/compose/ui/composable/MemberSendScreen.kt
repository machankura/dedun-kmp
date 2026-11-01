package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.couldn_t_send
import bennu.composeapp.generated.resources.send_from_your_guarded_wallet
import bennu.composeapp.generated.resources.sending_from_this_wallet_needs_its_key_and_a_node
import bennu.composeapp.generated.resources.sent
import bennu.composeapp.generated.resources.sign_and_send
import bennu.composeapp.generated.resources.signed_by_you_alone_and_sent_at_once
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.MemberSendViewModel
import com.machankura.dedun.compose.ui.view.state.MemberSendUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource

/**
 * A spend from the member's own guarded wallet: the group's send form, and a
 * button that signs with the member's key alone and sends. There is no quorum
 * to stop a mistake after this, which the line above the button says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberSendScreen(
    activeUserPublicKey: HexKey,
    walletId: String,
    chain: Chain,
    initialUIState: MemberSendUIState = MemberSendUIState.Loading,
    memberWalletRepository: MemberWalletRepository,
    wallets: GroupOnchainWallets,
    onNavigateBack: () -> Unit,
) {
    val viewModel: MemberSendViewModel = viewModel(
        factory = MemberSendViewModel.factory(
            ownerPublicKey = activeUserPublicKey,
            walletId = walletId,
            chain = chain,
            initialUIState = initialUIState,
            memberWalletRepository = memberWalletRepository,
            wallets = wallets,
        )
    )

    LaunchedEffect(true) {
        if (initialUIState == MemberSendUIState.Loading) viewModel.initiate()
    }

    // The caller's scope, so "sent" outlives the screen going back.
    val notify = rememberNotifier(rememberCoroutineScope())
    val sent = stringResource(Res.string.sent)
    val couldNotSend = stringResource(Res.string.couldn_t_send)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.send_from_your_guarded_wallet), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                // Nothing to retry: the wallet came from a navigation argument.
                is MemberSendUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                MemberSendUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                MemberSendUIState.Unavailable -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.sending_from_this_wallet_needs_its_key_and_a_node),
                    icon = Icons.Default.AccountBalanceWallet,
                )

                is MemberSendUIState.Ready -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    val preview = viewModel.preview()

                    SpendForm(
                        addressField = viewModel.addressField,
                        amountField = viewModel.amountField,
                        feerateField = viewModel.feerateField,
                        sendEverything = viewModel.sendEverything,
                        spendable = viewModel.spendable(),
                        cap = GroupWalletRollout.memberCapFor(chain),
                        preview = preview,
                        amountProblem = preview?.takeIf { viewModel.isAboutTheAmount(it) },
                        coinsKnown = state.walletState != null,
                        chain = chain,
                        ownWallet = true,
                    )

                    Text(
                        text = stringResource(Res.string.signed_by_you_alone_and_sent_at_once),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        enabled = preview is GroupSpendBuilder.Result.Built && !viewModel.isActionPending.value,
                        onClick = { viewModel.send(onSent = { notify(sent); onNavigateBack() }, onFailure = { notify(couldNotSend) }) },
                    ) {
                        Icon(Icons.Default.Send, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.sign_and_send))
                    }
                }
            }
        }
    }
}
