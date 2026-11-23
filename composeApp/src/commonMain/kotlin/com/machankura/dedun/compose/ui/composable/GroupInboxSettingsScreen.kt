package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyStage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.RelayUrlInput
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupInboxRepository
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.abandon
import dedun.composeapp.generated.resources.announced_anyone_can_write_to_it
import dedun.composeapp.generated.resources.couldn_t_find_the_group
import dedun.composeapp.generated.resources.couldn_t_start_the_ceremony
import dedun.composeapp.generated.resources.inbox_key_generation
import dedun.composeapp.generated.resources.inbox_settings
import dedun.composeapp.generated.resources.inbox_setup_explainer
import dedun.composeapp.generated.resources.mail_arrives_on
import dedun.composeapp.generated.resources.no_inbox_relays_were_valid
import dedun.composeapp.generated.resources.one_relay_per_line
import dedun.composeapp.generated.resources.retired_kept_to_open_older_mail
import dedun.composeapp.generated.resources.rotate_explainer
import dedun.composeapp.generated.resources.rotate_the_inbox_key
import dedun.composeapp.generated.resources.set_up_an_inbox
import dedun.composeapp.generated.resources.the_admins_are_making_the_inbox_key
import dedun.composeapp.generated.resources.the_ceremony_ended
import dedun.composeapp.generated.resources.the_admins_have_changed_since_this_key_was_made
import dedun.composeapp.generated.resources.the_inbox_key_ceremony_started
import dedun.composeapp.generated.resources.try_again
import dedun.composeapp.generated.resources.waiting_for_the_group_to_sign_its_announcement
import dedun.composeapp.generated.resources.waiting_on
import dedun.composeapp.generated.resources.where_mail_should_arrive
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupInboxSettingsViewModel
import com.machankura.dedun.compose.ui.view.state.GroupInboxSettingsUIState
import org.jetbrains.compose.resources.stringResource

/**
 * The group's inbox key (docs/group-inbox.md, D2 to D7 and D14): setting one up, which is an
 * admin starting the ceremony that makes it; where it stands, generation by generation; the
 * ceremony while it runs, with who it waits on; and rotating it.
 *
 * Nothing here is the decision to have an inbox. That is the group signing its announcement,
 * which the ceremony's coordinator asks for when the key is made, and which every member who
 * holds a share is asked about on the signing screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInboxSettingsScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialUIState: GroupInboxSettingsUIState = GroupInboxSettingsUIState.Loading,
    chatRepository: ChatRepository,
    inboxRepository: GroupInboxRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupInboxSettingsViewModel = viewModel(
        factory = GroupInboxSettingsViewModel.factory(chatRoomId, initialUIState, chatRepository, inboxRepository)
    )
    LaunchedEffect(true) {
        if (initialUIState == GroupInboxSettingsUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val started = stringResource(Res.string.the_inbox_key_ceremony_started)
    val noRelays = stringResource(Res.string.no_inbox_relays_were_valid)
    val couldNotStart = stringResource(Res.string.couldn_t_start_the_ceremony)
    val report: (String?) -> Unit = { failure -> notify(if (failure == null) started else couldNotStart) }
    val canSign = LocalCanSign.current

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.inbox_settings), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is GroupInboxSettingsUIState.Error -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = stringResource(Res.string.couldn_t_find_the_group),
                    onRetry = null,
                )

                GroupInboxSettingsUIState.Loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(padding).padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))
                    LoadingDataIndicator(fillScreen = false)
                }

                is GroupInboxSettingsUIState.Loaded -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                ) {
                    val status = state.status
                    val ceremony = status.ceremony
                    val running = ceremony?.isRunning == true
                    val visibleKeys = status.keys.filter { it.state != InboxKeyState.WIPED }

                    if (visibleKeys.isEmpty() && !running) {
                        SetUp(
                            busy = state.busy,
                            refusal = status.setUpRefusal,
                            canSign = canSign,
                            onSetUp = { text ->
                                val relays = text.lines().mapNotNull { RelayUrlInput.relayUrlOrNull(it) }
                                if (relays.isEmpty()) notify(noRelays) else viewModel.setUp(relays, report)
                            },
                        )
                    }

                    visibleKeys.forEach { key ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                leadingContent = { Icon(Icons.Default.Key, contentDescription = Decorative) },
                                headlineContent = {
                                    Text(stringResource(Res.string.inbox_key_generation, key.generation, fingerprint(key.publicKey)))
                                },
                                supportingContent = {
                                    Text(
                                        when (key.state) {
                                            InboxKeyState.ANNOUNCED -> stringResource(Res.string.announced_anyone_can_write_to_it)
                                            InboxKeyState.RETIRED -> stringResource(Res.string.retired_kept_to_open_older_mail)
                                            else -> stringResource(Res.string.waiting_for_the_group_to_sign_its_announcement)
                                        }
                                    )
                                },
                            )
                        }
                    }

                    if (status.relays.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.mail_arrives_on, status.relays.joinToString(", ") { it.url.removePrefix("wss://").trimEnd('/') }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (ceremony != null && running) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                leadingContent = { Icon(Icons.Default.HourglassTop, contentDescription = Decorative) },
                                headlineContent = { Text(stringResource(Res.string.the_admins_are_making_the_inbox_key)) },
                                supportingContent = status.waitingOn.takeIf { it.isNotEmpty() }?.let { names ->
                                    { Text(stringResource(Res.string.waiting_on, names.joinToString(", "))) }
                                },
                                trailingContent = if (ceremony.isParticipant() && canSign) {
                                    { TextButton(onClick = { viewModel.abandon(ceremony.id) {} }, enabled = !state.busy) { Text(stringResource(Res.string.abandon)) } }
                                } else {
                                    null
                                },
                            )
                        }
                    } else if (ceremony?.stage == InboxKeyCeremonyStage.FAILED && visibleKeys.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.the_ceremony_ended, ceremony.failureReason.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (visibleKeys.isNotEmpty() && !running) {
                        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                            if (status.rotationOwed) {
                                Text(
                                    stringResource(Res.string.the_admins_have_changed_since_this_key_was_made),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(stringResource(Res.string.rotate_explainer), style = MaterialTheme.typography.bodyMedium)
                            status.setUpRefusal?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { viewModel.rotate(report) },
                                enabled = status.setUpRefusal == null && !state.busy && canSign,
                            ) {
                                Icon(Icons.Default.Autorenew, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(
                                    stringResource(
                                        if (ceremony?.stage == InboxKeyCeremonyStage.FAILED) Res.string.try_again else Res.string.rotate_the_inbox_key
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A key as a person can compare it across devices: its first and last eight characters. */
private fun fingerprint(key: HexKey): String = key.take(8) + "\u2026" + key.takeLast(8)

/** Starting the first ceremony: what it does, where mail should arrive, and the button. */
@Composable
private fun SetUp(busy: Boolean, refusal: String?, canSign: Boolean, onSetUp: (String) -> Unit) {
    var relays by remember { mutableStateOf(Relays.DefaultDMRelayList.joinToString("\n") { it.url }) }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
        Text(stringResource(Res.string.inbox_setup_explainer), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = relays,
            onValueChange = { relays = it },
            label = { Text(stringResource(Res.string.where_mail_should_arrive)) },
            supportingText = { Text(stringResource(Res.string.one_relay_per_line)) },
            minLines = 2,
        )
        refusal?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { onSetUp(relays) }, enabled = refusal == null && !busy && canSign) {
            Text(stringResource(Res.string.set_up_an_inbox))
        }
    }
}

@ConformancePreviews
@Composable
private fun GroupInboxSettingsScreenPreview() {
    val chatRoomId = "d".repeat(64)
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupInboxSettingsScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                initialUIState = GroupInboxSettingsUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "&Ekklesia", description = null, mlsGroupState = "state")
                    ),
                    status = GroupInboxRepository.Status(
                        keys = emptyList(), ceremony = null, announcedKey = null, relays = emptyList(), setUpRefusal = null, waitingOn = emptyList(),
                    ),
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                inboxRepository = GroupInboxRepository.NO_OP,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
