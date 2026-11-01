package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.address_copied
import bennu.composeapp.generated.resources.address_s
import bennu.composeapp.generated.resources.address_s_was_just_paid
import bennu.composeapp.generated.resources.addresses
import bennu.composeapp.generated.resources.addresses_explainer
import bennu.composeapp.generated.resources.being_handed_out_now
import bennu.composeapp.generated.resources.change
import bennu.composeapp.generated.resources.copy_address
import bennu.composeapp.generated.resources.copy_address_s
import bennu.composeapp.generated.resources.finding_the_group_s_coins
import bennu.composeapp.generated.resources.not_used_yet
import bennu.composeapp.generated.resources.paid_s_sats
import bennu.composeapp.generated.resources.paid_spent_since
import bennu.composeapp.generated.resources.show_as_a_qr_code
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWalletAddressesViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWalletAddressesUIState
import com.machankura.dedun.compose.wallet.GroupAddress
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.bip21Uri
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * The group wallet's receive addresses, newest first: every one paid, the one
 * the wallet screen is handing out, and the few after it.
 *
 * Modelled on Phoenix's swap-in address list, with the look-ahead cut to what is
 * worth handing out. The addresses after the current one are there so that two
 * members sharing the wallet at once can each give a payer a different one;
 * the sentence above the list says so, or nobody would use anything but the top.
 * Tapping an unused one opens it as a QR code, in a sheet that closes itself if
 * the address is paid while it is open.
 *
 * No block explorer: a tap that sends every address of the group's wallet to a
 * third party's server is the leak the watch-only export warns about, made
 * without asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWalletAddressesScreen(
    chatRoomId: String,
    chain: Chain,
    initialUIState: GroupWalletAddressesUIState = GroupWalletAddressesUIState.Loading,
    chatRepository: ChatRepository,
    wallets: GroupOnchainWallets,
    onNavigateBack: () -> Unit,
) {
    val addressesViewModel: GroupWalletAddressesViewModel = viewModel(
        factory = GroupWalletAddressesViewModel.factory(
            chatRoomId = chatRoomId,
            chain = chain,
            initialUIState = initialUIState,
            chatRepository = chatRepository,
            wallets = wallets,
        )
    )

    LaunchedEffect(true) {
        if (initialUIState == GroupWalletAddressesUIState.Loading) {
            addressesViewModel.initiate()
        }
    }

    val notify = rememberNotifier(rememberCoroutineScope())

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.addresses),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(addressesViewModel.uiState) { uiState ->
            when (val state = uiState) {
                is GroupWalletAddressesUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupWalletAddressesUIState.Loading -> {
                    LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                }

                is GroupWalletAddressesUIState.Loaded -> {
                    val receive = state.receive
                    if (receive == null) {
                        LoadingDataIndicator(
                            modifier = Modifier.fillMaxWidth().padding(padding),
                            text = stringResource(Res.string.finding_the_group_s_coins)
                        )
                    } else {
                        AddressList(
                            modifier = Modifier.padding(padding),
                            receive = receive,
                            change = state.change,
                            notify = notify,
                            onShow = { index, paid -> addressesViewModel.show(index) { notify(paid) } },
                        )

                        val shown = addressesViewModel.shown?.let { index -> receive.firstOrNull { it.derivation.index == index } }
                        if (shown != null) {
                            AddressSheet(
                                address = shown,
                                notify = notify,
                                onDismiss = addressesViewModel::dismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressList(
    modifier: Modifier,
    receive: List<GroupAddress>,
    change: List<GroupAddress>,
    notify: (String) -> Unit,
    onShow: (index: Long, paidMessage: String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)
    val copy = { address: GroupAddress ->
        clipboardManager.setText(buildAnnotatedString { append(address.address) })
        notify(addressCopied)
    }
    // Both branches start at index 0, so the index alone is not a row's identity.
    val key = { address: GroupAddress -> "${address.derivation.change}/${address.derivation.index}" }

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        item {
            Text(
                text = stringResource(Res.string.addresses_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(receive, key = key) { address ->
            // Read here rather than in the click: the index is the row's, and a
            // click handler is not composable.
            val paid = stringResource(Res.string.address_s_was_just_paid, address.derivation.index.toString())
            AddressRow(
                address = address,
                onCopy = { copy(address) },
                // A used address is never offered for scanning: copying it is for
                // checking, a QR code is for being paid.
                onShow = if (address.status != GroupAddress.Status.USED) {
                    { onShow(address.derivation.index, paid) }
                } else null,
            )
        }

        // The group's money too, and what a watch-only import will show beside
        // the receive addresses -- but chosen by a spend, so never offered here.
        if (change.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.change),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sectionGap),
                )
            }
            items(change, key = key) { address ->
                AddressRow(address = address, onCopy = { copy(address) }, onShow = null)
            }
        }
    }
}

/**
 * One unused address, ready to be paid: what the wallet screen's receive card
 * shows for the current address, for whichever one this member is handing out.
 *
 * The title carries the index so a member reading it to a payer can say which
 * one they gave.
 */
@Composable
private fun AddressSheet(
    address: GroupAddress,
    notify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)

    ModalBottomSheet(
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Text(
                text = stringResource(Res.string.address_s, address.derivation.index.toString()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = address.status.label(address.balance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // BIP21, so a scanning wallet knows what the text is.
            QRCodeView(bip21Uri(address.address))
            SelectionContainer {
                Text(text = address.address, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = {
                    clipboardManager.setText(buildAnnotatedString { append(address.address) })
                    notify(addressCopied)
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.copy_address))
            }
        }
    }
}

/**
 * One address: its index, the address itself, and what it has been used for.
 *
 * The current address takes the secondary container rather than Phoenix's
 * leading bar, so the emphasis is a colour pairing the theme has already
 * measured in all six schemes.
 */
@Composable
private fun AddressRow(address: GroupAddress, onCopy: () -> Unit, onShow: (() -> Unit)?) {
    val showLabel = stringResource(Res.string.show_as_a_qr_code)
    val colors = if (address.status == GroupAddress.Status.CURRENT) {
        val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            headlineColor = onContainer,
            supportingColor = onContainer,
            leadingIconColor = onContainer,
            trailingIconColor = onContainer,
        )
    } else {
        ListItemDefaults.colors()
    }

    ListItem(
        modifier = if (onShow != null) {
            Modifier.clickable(onClickLabel = showLabel, onClick = onShow).minimumInteractiveComponentSize()
        } else Modifier,
        colors = colors,
        leadingContent = {
            Text(
                text = address.derivation.index.toString(),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        headlineContent = {
            Text(
                text = address.address,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        supportingContent = { Text(address.status.label(address.balance)) },
        trailingContent = {
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(Res.string.copy_address_s, address.derivation.index.toString())
                )
            }
        },
    )
}

@Composable
private fun GroupAddress.Status.label(balance: Satoshi): String = when (this) {
    GroupAddress.Status.CURRENT -> stringResource(Res.string.being_handed_out_now)
    GroupAddress.Status.NEXT -> stringResource(Res.string.not_used_yet)
    GroupAddress.Status.USED -> if (balance > Satoshi(0)) {
        stringResource(Res.string.paid_s_sats, balance.grouped())
    } else {
        stringResource(Res.string.paid_spent_since)
    }
}
