package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupDmRepository
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.anyone_on_nostr_will_be_able_to_write_to_the_group
import dedun.composeapp.generated.resources.ask_the_group_to_sign
import dedun.composeapp.generated.resources.could_not_ask_the_group_to_sign_its_message_relays
import dedun.composeapp.generated.resources.every_relay_has_to_be_a_public_wss_address
import dedun.composeapp.generated.resources.message_relays
import dedun.composeapp.generated.resources.one_wss_address_per_line
import dedun.composeapp.generated.resources.only_a_current_admin_can_open_the_groups_messages
import dedun.composeapp.generated.resources.open_the_groups_messages
import dedun.composeapp.generated.resources.senders_apps_deliver_to_these_relays
import dedun.composeapp.generated.resources.the_group_signs_where_it_receives_messages
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupDmSetupViewModel
import com.machankura.dedun.compose.ui.view.state.GroupDmSetupUIState
import org.jetbrains.compose.resources.stringResource

/**
 * Where a group decides to receive direct messages (D8 of docs/group-threshold-ecdh.md): the
 * relays senders deliver to, and what signing them means, said before the button. The button
 * proposes; the group's messages open when a quorum has signed, so the screen hands over to
 * the signing session rather than going back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDmSetupScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chatRepository: ChatRepository,
    groupDmRepository: GroupDmRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupDmSetupViewModel = viewModel(
        factory = GroupDmSetupViewModel.factory(chatRoomId, activeUserPublicKey, chatRepository, groupDmRepository)
    )
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_its_message_relays)
    val canSign = LocalCanSign.current

    ScreenStateTransition(viewModel.uiState) { uiState ->
        when (val state = uiState) {
            is GroupDmSetupUIState.Loading -> LoadingDataIndicator()
            // The room came from a navigation argument; reading it again fails the same way.
            is GroupDmSetupUIState.Error -> ErrorState(message = state.message, onRetry = null)
            is GroupDmSetupUIState.Loaded -> {
                val relaysField = rememberTextFieldState(state.relays.joinToString("\n"))
                val relays = viewModel.relaysOf(relaysField.text.toString())
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(Res.string.open_the_groups_messages)) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.ask_the_group_to_sign),
                                    icon = { Icon(Icons.Default.Draw, contentDescription = Decorative) },
                                    enabled = state.canPropose && canSign && relays != null && !viewModel.isActionPending,
                                    onClick = {
                                        viewModel.propose(
                                            text = relaysField.text.toString(),
                                            onSuccess = { sessionId ->
                                                onNavigateToRouteAndPopUpInclusive(
                                                    FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)
                                                )
                                            },
                                            onFailure = { notify(couldNotPropose) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    ) {
                        Text(
                            text = stringResource(Res.string.anyone_on_nostr_will_be_able_to_write_to_the_group),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.the_group_signs_where_it_receives_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!state.canPropose) {
                            Text(
                                text = stringResource(Res.string.only_a_current_admin_can_open_the_groups_messages),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        OutlinedTextField(
                            state = relaysField,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.message_relays)) },
                            placeholder = { Text(stringResource(Res.string.one_wss_address_per_line)) },
                            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
                            isError = relays == null,
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (relays == null) Res.string.every_relay_has_to_be_a_public_wss_address
                                        else Res.string.senders_apps_deliver_to_these_relays
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
