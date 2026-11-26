package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.accepted_by_n_of_m_relays
import dedun.composeapp.generated.resources.add_a_relay_to_broadcast_to
import dedun.composeapp.generated.resources.add_relay
import dedun.composeapp.generated.resources.broadcast
import dedun.composeapp.generated.resources.copied_the_raw_json
import dedun.composeapp.generated.resources.copy_raw_json
import dedun.composeapp.generated.resources.eg_wss_relay_example_com
import dedun.composeapp.generated.resources.no_relays_to_broadcast_to
import dedun.composeapp.generated.resources.relay_url
import dedun.composeapp.generated.resources.relays
import dedun.composeapp.generated.resources.sends_the_event_exactly_as_the_group_signed_it
import dedun.composeapp.generated.resources.signed_by_the_group_on
import dedun.composeapp.generated.resources.that_is_not_a_relay_address
import dedun.composeapp.generated.resources.that_relay_is_already_in_this_list
import dedun.composeapp.generated.resources.the_signed_event
import dedun.composeapp.generated.resources.the_app_s_publishing_relays_are_filled_in
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.label
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.BroadcastGroupSignedEventViewModel
import com.machankura.dedun.compose.ui.view.model.BroadcastGroupSignedEventViewModel.RelayOutcome
import com.machankura.dedun.compose.ui.view.state.BroadcastGroupSignedEventUIState
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Instant

/**
 * One event the group signed, and the relays to send it to.
 *
 * The group's screen shows what the group has signed; this is where one of those
 * leaves the device. The list starts at where the group has said it lives and the
 * member can change it, the button sends to every relay in it at once, and each
 * row then says what its relay answered -- accepted, rejected in the relay's own
 * words, or nothing. The raw JSON is at the bottom for anyone who would rather
 * hand it to some other tool.
 *
 * **The button sends rather than proposes.** Every other screen off the identity
 * block opens a signing session, because what it makes is a statement by the
 * group. This one moves a statement the group has already made, which any member
 * holding it may do: the signature is the group's, and no share of the key is
 * needed to repeat it. See `BroadcastGroupSignedEventViewModel` for why the send
 * goes to the pool directly rather than through the app's broadcast queue.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BroadcastGroupSignedEventScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    signedEventId: HexKey,
    initialBroadcastGroupSignedEventUIState: BroadcastGroupSignedEventUIState =
        BroadcastGroupSignedEventUIState.Loading,
    chatRepository: ChatRepository,
    publishTransport: EventPublishTransport,
    onNavigateBack: () -> Unit,
) {
    val broadcastGroupSignedEventViewModel: BroadcastGroupSignedEventViewModel = viewModel(
        factory = BroadcastGroupSignedEventViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            signedEventId = signedEventId,
            initialBroadcastGroupSignedEventUIState = initialBroadcastGroupSignedEventUIState,
            chatRepository = chatRepository,
            publishTransport = publishTransport,
        )
    )

    val clipboardManager = LocalClipboardManager.current

    // Read out here rather than in a click handler: both are composable and an
    // onClick lambda is not.
    val notify = rememberNotifier(rememberCoroutineScope())
    val noRelays = stringResource(Res.string.add_a_relay_to_broadcast_to)
    val copied = stringResource(Res.string.copied_the_raw_json)

    ScreenStateTransition(broadcastGroupSignedEventViewModel.broadcastGroupSignedEventUIState) { uiState ->
        when (val broadcastGroupSignedEventUIState = uiState) {
            is BroadcastGroupSignedEventUIState.Error -> {
                // Nothing to retry: the room and the event came from navigation
                // arguments, and reading them again with the same ones fails the
                // same way.
                ErrorState(message = broadcastGroupSignedEventUIState.message, onRetry = null)
            }

            is BroadcastGroupSignedEventUIState.Loaded -> {
                val signedEvent = broadcastGroupSignedEventUIState.signedEvent
                val urlFieldState = rememberTextFieldState()
                val isPending = broadcastGroupSignedEventViewModel.isActionPending.value
                val relays = broadcastGroupSignedEventViewModel.relays

                // The list comes from the state rather than from whatever loaded
                // it, so a screen handed a loaded state -- a preview, a layout
                // test -- fills in the same as the app does. Once only, so a list
                // the member emptied stays empty.
                LaunchedEffect(broadcastGroupSignedEventUIState) {
                    broadcastGroupSignedEventViewModel.seedRelays(kind = signedEvent.kind)
                }

                // A refusal is about what is in the field, so it goes the moment
                // the field changes rather than sitting under a URL that has since
                // been corrected.
                LaunchedEffect(urlFieldState) {
                    snapshotFlow { urlFieldState.text.toString() }
                        .collect { broadcastGroupSignedEventViewModel.clearAddFailure() }
                }

                // As the group signed it, byte for byte: what is copied is exactly
                // what goes on the wire, because the point of copying it is to
                // hand somebody something they can verify.
                val json = signedEvent.toEvent().toJson()
                val summary = ProposedEvent.summarize(signedEvent.toEvent())

                val canSend = !isPending

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                broadcastGroupSignedEventUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) }
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.broadcast),
                                    icon = { Icon(Icons.Default.CellTower, contentDescription = "Broadcast to these relays") },
                                    enabled = canSend,
                                    onClick = {
                                        broadcastGroupSignedEventViewModel.broadcast(
                                            signedEvent = signedEvent,
                                            // Stays on the screen: an empty list is
                                            // a question rather than a mistake.
                                            onNoRelays = { notify(noRelays) }
                                        )
                                    }
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .padding(horizontal = MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                    ) {
                        // What is being sent, in the words the signing screen used
                        // for it: a member deciding where a post goes is deciding
                        // about a post, and "kind 1" answers a question nobody asked.
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = MaterialTheme.spacing.containerPadding),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                            ) {
                                Text(
                                    text = summary.label,
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Text(
                                    text = summary.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4
                                )

                                Text(
                                    text = stringResource(
                                        Res.string.signed_by_the_group_on,
                                        signedEvent.createdAt.toFormattedTimeAndDateString()
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item {
                            Text(
                                text = stringResource(
                                    Res.string.sends_the_event_exactly_as_the_group_signed_it
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))

                            Text(
                                text = stringResource(Res.string.relays),
                                style = MaterialTheme.typography.titleSmall
                            )

                            Text(
                                text = stringResource(
                                    Res.string.the_app_s_publishing_relays_are_filled_in
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.weight(1f)
                                        .background(BottomAppBarDefaults.containerColor),
                                    state = urlFieldState,
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent
                                    ),
                                    leadingIcon = {
                                        Icon(Icons.Default.Dns, contentDescription = Decorative)
                                    },
                                    label = { Text(stringResource(Res.string.relay_url)) },
                                    placeholder = {
                                        Text(stringResource(Res.string.eg_wss_relay_example_com))
                                    },
                                    isError = broadcastGroupSignedEventViewModel.addFailure != null
                                )

                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

                                IconButton(
                                    onClick = {
                                        if (
                                            broadcastGroupSignedEventViewModel.addRelay(
                                                urlFieldState.text.toString()
                                            )
                                        ) {
                                            urlFieldState.clearText()
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(Res.string.add_relay)
                                    )
                                }
                            }

                            // Named rather than silent. An add button that does
                            // nothing for a URL it refuses is indistinguishable from
                            // a missed tap.
                            broadcastGroupSignedEventViewModel.addFailure?.let { failure ->
                                Text(
                                    text = when (failure) {
                                        BroadcastGroupSignedEventViewModel.AddFailure.NotARelay ->
                                            stringResource(Res.string.that_is_not_a_relay_address)
                                        BroadcastGroupSignedEventViewModel.AddFailure.AlreadyListed ->
                                            stringResource(
                                                Res.string.that_relay_is_already_in_this_list
                                            )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // The last press, summed up, above the rows that break it
                        // down. Persistent rather than a snackbar, because it is
                        // the answer the member came for and a snackbar would be
                        // gone before the last relay had been read.
                        broadcastGroupSignedEventViewModel.lastBroadcast?.let { last ->
                            item {
                                Text(
                                    text = stringResource(
                                        Res.string.accepted_by_n_of_m_relays,
                                        last.accepted,
                                        last.asked
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (last.accepted == last.asked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }

                        if (relays.isEmpty()) {
                            item {
                                EmptyState(
                                    message = stringResource(Res.string.no_relays_to_broadcast_to),
                                    icon = Icons.Default.Dns
                                )
                            }
                        } else {
                            items(items = relays, key = { it.url }) { relay ->
                                BroadcastRelayRow(
                                    relay = relay,
                                    outcome = broadcastGroupSignedEventViewModel.outcomes[relay],
                                    // A row cannot leave while its relay is being
                                    // asked: the answer would arrive for a relay
                                    // that is no longer there to show it.
                                    canRemove = !isPending,
                                    onRemove = {
                                        broadcastGroupSignedEventViewModel.removeRelay(relay)
                                    }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

                            Text(
                                text = stringResource(Res.string.the_signed_event),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = json,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(MaterialTheme.spacing.containerPadding)
                                )
                            }

                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))

                            FilledTonalButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(json))
                                    notify(copied)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
                                Text(text = stringResource(Res.string.copy_raw_json))
                            }

                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))
                        }
                    }
                }
            }

            BroadcastGroupSignedEventUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                    Text(
                        text = stringResource(Res.string.broadcast),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(fillScreen = false)
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialBroadcastGroupSignedEventUIState == BroadcastGroupSignedEventUIState.Loading) {
            broadcastGroupSignedEventViewModel.initiateBroadcastGroupSignedEvent()
        }
    }
}

/**
 * One relay to send to, and what it said the last time it was asked.
 *
 * The answer is on a second line under the host rather than in an icon, because
 * the answers that matter are the relay's own words -- "blocked: not on the
 * allow list" is something a member can act on, and a red icon is not.
 */
@Composable
private fun BroadcastRelayRow(
    relay: NormalizedRelayUrl,
    outcome: RelayOutcome?,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.relatedGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            Text(
                // The host rather than the whole URL: every relay here starts
                // `wss://` -- nothing else is allowed in -- so the scheme is four
                // characters of nothing in front of the part that differs.
                text = relay.displayUrl(),
                style = MaterialTheme.typography.bodyMedium
            )

            outcome?.let {
                Text(
                    text = it.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (it) {
                        RelayOutcome.Accepted -> MaterialTheme.colorScheme.primary
                        is RelayOutcome.Rejected, is RelayOutcome.Failed ->
                            MaterialTheme.colorScheme.error
                        RelayOutcome.Sending, RelayOutcome.NoAnswer ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove relay",
                // The button's own disabled colour while a send is out, rather
                // than a red that promises a tap will do something.
                tint = if (canRemove) MaterialTheme.colorScheme.error else LocalContentColor.current
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun BroadcastGroupSignedEventScreenPreview() {
    val chatRoomId = "d4c3b2a1".repeat(8)

    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BroadcastGroupSignedEventScreen(
                activeUserPublicKey = "",
                chatRoomId = chatRoomId,
                relayHint = null,
                signedEventId = "b".repeat(64),
                initialBroadcastGroupSignedEventUIState = BroadcastGroupSignedEventUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = chatRoomId,
                            userPublicKey = "",
                            subject = "Translation room",
                            description = "A group translating hard books.",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        ),
                    ),
                    signedEvent = GroupSignedEvent(
                        id = "b".repeat(64),
                        chatRoomId = chatRoomId,
                        publicKey = chatRoomId,
                        kind = TextNoteEvent.KIND,
                        tags = emptyArray(),
                        content = "The first chapter is out.",
                        signature = "f".repeat(128),
                        createdAt = Instant.fromEpochSeconds(1_700_000_000)
                    ),
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                publishTransport = object : EventPublishTransport {
                    override suspend fun publishEvent(
                        nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,
                        relays: Set<com.machankura.dedun.compose.network.dto.RelayDTO>
                    ): Flow<com.machankura.dedun.compose.network.relays.NostrPublishResult> = emptyFlow()
                },
                onNavigateBack = {}
            )
        }
    }
}
