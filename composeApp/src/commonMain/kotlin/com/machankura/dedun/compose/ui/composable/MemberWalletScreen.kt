package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.managers.MemberWalletManager
import bennu.composeapp.generated.resources.s_has_stopped_guarding_members_wallets_discard
import bennu.composeapp.generated.resources.s_has_changed_its_delay_since_you_asked
import bennu.composeapp.generated.resources.s_would_refuse_this_request_asked_before
import bennu.composeapp.generated.resources.s_would_refuse_this_request
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.a_group
import bennu.composeapp.generated.resources.address_copied
import bennu.composeapp.generated.resources.ask_again
import bennu.composeapp.generated.resources.cancel
import bennu.composeapp.generated.resources.change_descriptor
import bennu.composeapp.generated.resources.close
import bennu.composeapp.generated.resources.coins
import bennu.composeapp.generated.resources.copied
import bennu.composeapp.generated.resources.copy_address
import bennu.composeapp.generated.resources.couldn_t_ask_again
import bennu.composeapp.generated.resources.couldn_t_discard_the_wallet
import bennu.composeapp.generated.resources.discard
import bennu.composeapp.generated.resources.discard_this_wallet_question
import bennu.composeapp.generated.resources.discarding_forgets_its_key
import bennu.composeapp.generated.resources.export_key
import bennu.composeapp.generated.resources.export_this_wallet_s_key_question
import bennu.composeapp.generated.resources.finding_your_coins
import bennu.composeapp.generated.resources.copy
import bennu.composeapp.generated.resources.send
import bennu.composeapp.generated.resources.guarded_by_s
import bennu.composeapp.generated.resources.guarded_wallet
import bennu.composeapp.generated.resources.if_you_lose_this_device_those_groups_can_still_move_your_coins
import bennu.composeapp.generated.resources.nothing_has_been_sent_to_this_wallet_yet
import bennu.composeapp.generated.resources.receive_address
import bennu.composeapp.generated.resources.receive_descriptor
import bennu.composeapp.generated.resources.s_did_not_sign
import bennu.composeapp.generated.resources.s_is_signing
import bennu.composeapp.generated.resources.s_took_s_sats_in_s
import bennu.composeapp.generated.resources.show_key
import bennu.composeapp.generated.resources.taken_by_guardians
import bennu.composeapp.generated.resources.the_key_of_this_wallet_is_on_another_device
import bennu.composeapp.generated.resources.waiting_for_someone_in_s_to_put_it_to_the_group
import bennu.composeapp.generated.resources.whoever_holds_these_descriptors_holds_the_coins
import bennu.composeapp.generated.resources.you_re_no_longer_in_s
import bennu.composeapp.generated.resources.your_spends
import bennu.composeapp.generated.resources.confirmed
import bennu.composeapp.generated.resources.in_the_mempool
import bennu.composeapp.generated.resources.sent_waiting_for_the_network
import com.machankura.dedun.compose.managers.MemberWalletManager.WalletState
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.MemberWalletViewModel
import com.machankura.dedun.compose.ui.view.state.MemberWalletUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.bip21Uri
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import org.jetbrains.compose.resources.stringResource

/**
 * One of the member's guarded wallets.
 *
 * Before a group has certified it, where the asking has got to and the two
 * things the member can do about it: ask again, or discard the key -- which is
 * safe exactly then, because no address was ever offered. Once certified, the
 * wallet itself: balance, the address to be paid at, the coins, and every coin
 * a guardian took, read off the chain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberWalletScreen(
    activeUserPublicKey: HexKey,
    walletId: String,
    initialUIState: MemberWalletUIState = MemberWalletUIState.Loading,
    memberWalletRepository: MemberWalletRepository,
    wallets: GroupOnchainWallets,
    /** The send form for this wallet, or null where there is none to offer. */
    onSend: ((walletId: String) -> Unit)? = null,
    onNavigateBack: () -> Unit,
) {
    val viewModel: MemberWalletViewModel = viewModel(
        factory = MemberWalletViewModel.factory(
            ownerPublicKey = activeUserPublicKey,
            walletId = walletId,
            initialUIState = initialUIState,
            memberWalletRepository = memberWalletRepository,
            wallets = wallets,
        )
    )

    LaunchedEffect(true) {
        if (initialUIState == MemberWalletUIState.Loading) viewModel.initiate()
    }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAskAgain = stringResource(Res.string.couldn_t_ask_again)
    val couldNotDiscard = stringResource(Res.string.couldn_t_discard_the_wallet)
    val addressCopied = stringResource(Res.string.address_copied)
    val copied = stringResource(Res.string.copied)
    val clipboardManager = LocalClipboardManager.current

    var confirmingDiscard by remember { mutableStateOf(false) }
    var confirmingExport by remember { mutableStateOf(false) }

    val title = (viewModel.uiState as? MemberWalletUIState.Loaded)?.wallet?.label ?: stringResource(Res.string.guarded_wallet)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                MemberWalletUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                // Nothing to retry: the wallet came from a navigation argument.
                is MemberWalletUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                is MemberWalletUIState.Loaded -> {
                    val wallet = state.wallet
                    val group = wallet.groupRoom?.chatRoom?.subject?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.a_group)
                    val walletState = state.walletState

                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    ) {
                        item { Text(stringResource(Res.string.guarded_by_s, group), style = MaterialTheme.typography.titleMedium) }
                        item {
                            Text(
                                reachText(group, state.delays, wallet.account.chain),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!wallet.isMember) item { Text(stringResource(Res.string.you_re_no_longer_in_s, group)) }

                        when (wallet.state) {
                            WalletState.ASKED, WalletState.SIGNING, WalletState.NOT_SIGNED -> {
                                item {
                                    Text(
                                        stringResource(
                                            when (wallet.state) {
                                                WalletState.SIGNING -> Res.string.s_is_signing
                                                WalletState.NOT_SIGNED -> Res.string.s_did_not_sign
                                                else -> Res.string.waiting_for_someone_in_s_to_put_it_to_the_group
                                            },
                                            group,
                                        )
                                    )
                                }
                                // Why the group would refuse the request as it stands, said
                                // before the button that fixes it. docs/member-wallet-delay.md.
                                wallet.refusedBecause?.let { why ->
                                    item {
                                        Text(
                                            stringResource(
                                                when (why) {
                                                    MemberWalletManager.Why.NO_POLICY -> Res.string.s_would_refuse_this_request_asked_before
                                                    MemberWalletManager.Why.DELAY_CHANGED -> Res.string.s_has_changed_its_delay_since_you_asked
                                                    MemberWalletManager.Why.CLOSED -> Res.string.s_has_stopped_guarding_members_wallets_discard
                                                    MemberWalletManager.Why.NOT_THE_GROUPS -> Res.string.s_would_refuse_this_request
                                                },
                                                group,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // A closed group takes no new request, so asking again
                                // could only fail; discarding is what is left.
                                if (wallet.state != WalletState.SIGNING && wallet.refusedBecause != MemberWalletManager.Why.CLOSED) {
                                    item {
                                        OutlinedButton(
                                            onClick = { viewModel.askAgain(onAsked = {}, onFailure = { notify(couldNotAskAgain) }) },
                                            enabled = !viewModel.isActionPending.value,
                                        ) { Text(stringResource(Res.string.ask_again)) }
                                    }
                                }
                                if (wallet.state != WalletState.SIGNING) {
                                    item {
                                        TextButton(onClick = { confirmingDiscard = true }, enabled = !viewModel.isActionPending.value) {
                                            Text(stringResource(Res.string.discard))
                                        }
                                    }
                                }
                            }

                            WalletState.OPEN, WalletState.WATCH_ONLY -> {
                                item { BalanceCard(walletState) }

                                if (wallet.state == WalletState.WATCH_ONLY) {
                                    item {
                                        Text(
                                            stringResource(Res.string.the_key_of_this_wallet_is_on_another_device),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                onSend?.takeIf { wallet.state == WalletState.OPEN }?.let { send ->
                                    item { SendButton(onClick = { send(wallet.walletId) }) }
                                }

                                when {
                                    walletState?.receiveAddress == null -> item {
                                        LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.finding_your_coins))
                                    }

                                    else -> {
                                        val address = walletState.receiveAddress!!
                                        item {
                                            Card(modifier = Modifier.fillMaxWidth()) {
                                                Column(
                                                    modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                                                ) {
                                                    Text(stringResource(Res.string.receive_address), style = MaterialTheme.typography.titleMedium)
                                                    QRCodeView(bip21Uri(address))
                                                    SelectionContainer { Text(text = address, style = MaterialTheme.typography.bodyMedium) }
                                                    TextButton(onClick = {
                                                        clipboardManager.setText(buildAnnotatedString { append(address) })
                                                        notify(addressCopied)
                                                    }) {
                                                        Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                                                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                                                        Text(stringResource(Res.string.copy_address))
                                                    }
                                                }
                                            }
                                        }

                                        item { Text(stringResource(Res.string.coins), style = MaterialTheme.typography.titleMedium) }
                                        if (walletState.utxos.isEmpty()) {
                                            item { EmptyState(message = stringResource(Res.string.nothing_has_been_sent_to_this_wallet_yet)) }
                                        } else {
                                            items(walletState.utxos, key = { it.utxo.outPoint.toString() }) { utxo ->
                                                CoinRow(utxo = utxo, confirmations = walletState.confirmations(utxo))
                                            }
                                        }
                                    }
                                }

                                if (state.spends.isNotEmpty()) {
                                    item { Text(stringResource(Res.string.your_spends), style = MaterialTheme.typography.titleMedium) }
                                    items(state.spends, key = { "spend-" + it.txid }) { spend ->
                                        ListItem(
                                            headlineContent = { Text(spend.txid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            supportingContent = {
                                                Text(
                                                    stringResource(
                                                        when {
                                                            spend.confirmedAt != null -> Res.string.confirmed
                                                            spend.seenAt != null -> Res.string.in_the_mempool
                                                            else -> Res.string.sent_waiting_for_the_network
                                                        }
                                                    )
                                                )
                                            },
                                        )
                                    }
                                }

                                if (state.taken.isNotEmpty()) {
                                    item { Text(stringResource(Res.string.taken_by_guardians), style = MaterialTheme.typography.titleMedium) }
                                    items(state.taken, key = { "taken-" + it.txid + it.guardianName }) { line ->
                                        ListItem(
                                            headlineContent = {
                                                Text(stringResource(Res.string.s_took_s_sats_in_s, line.guardianName ?: stringResource(Res.string.a_group), line.amount.grouped(), line.txid))
                                            }
                                        )
                                    }
                                }

                                if (wallet.state == WalletState.OPEN) {
                                    item {
                                        TextButton(onClick = { confirmingExport = true }) {
                                            Icon(Icons.Default.Key, contentDescription = Decorative)
                                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                                            Text(stringResource(Res.string.export_key))
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                stringResource(Res.string.if_you_lose_this_device_those_groups_can_still_move_your_coins),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(Res.string.discard_this_wallet_question)) },
            text = { Text(stringResource(Res.string.discarding_forgets_its_key)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    viewModel.discard(onDiscarded = onNavigateBack, onFailure = { notify(couldNotDiscard) })
                }) { Text(stringResource(Res.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    if (confirmingExport) {
        AlertDialog(
            onDismissRequest = { confirmingExport = false },
            title = { Text(stringResource(Res.string.export_this_wallet_s_key_question)) },
            text = { Text(stringResource(Res.string.whoever_holds_these_descriptors_holds_the_coins)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingExport = false
                    viewModel.exportKey()
                }) { Text(stringResource(Res.string.show_key)) }
            },
            dismissButton = { TextButton(onClick = { confirmingExport = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    viewModel.exported?.let { (receive, change) ->
        AlertDialog(
            onDismissRequest = viewModel::hideKey,
            title = { Text(stringResource(Res.string.export_key)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    listOf(Res.string.receive_descriptor to receive, Res.string.change_descriptor to change).forEach { (name, descriptor) ->
                        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(descriptor, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = {
                            clipboardManager.setText(buildAnnotatedString { append(descriptor) })
                            notify(copied)
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                            Text(stringResource(Res.string.copy))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::hideKey) { Text(stringResource(Res.string.close)) } },
        )
    }
}

@Composable
private fun SendButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Icon(Icons.Default.Send, contentDescription = Decorative)
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
        Text(stringResource(Res.string.send))
    }
}
