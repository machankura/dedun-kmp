package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedTextField
import com.machankura.dedun.compose.extensions.bech32ToHexOrThrow
import dedun.composeapp.generated.resources.asked_the_group_to_sign_this_message
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.could_not_ask_the_group_to_send_this
import dedun.composeapp.generated.resources.new_message
import dedun.composeapp.generated.resources.nothing_signed_by_this_person_is_on_this_device_yet
import dedun.composeapp.generated.resources.preparing_the_admins_are_working_out_how_to_write_to_them
import dedun.composeapp.generated.resources.send
import dedun.composeapp.generated.resources.sending_again
import dedun.composeapp.generated.resources.that_is_not_a_nostr_key
import dedun.composeapp.generated.resources.the_groups_quorum_has_to_sign_before_anything_is_sent
import dedun.composeapp.generated.resources.their_npub
import dedun.composeapp.generated.resources.write_a_message
import dedun.composeapp.generated.resources.write_to_them
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupDmRepository
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_message_this_screen_cant_show_yet
import dedun.composeapp.generated.resources.being_signed
import dedun.composeapp.generated.resources.declined_by_the_group
import dedun.composeapp.generated.resources.group_messages
import dedun.composeapp.generated.resources.n_messages_being_opened
import dedun.composeapp.generated.resources.n_more_are_queued
import dedun.composeapp.generated.resources.n_unread
import dedun.composeapp.generated.resources.nobody_has_written_to_the_group_yet
import dedun.composeapp.generated.resources.not_yet_answered_by
import dedun.composeapp.generated.resources.one_message_being_opened
import dedun.composeapp.generated.resources.only_current_admins_see_the_groups_messages
import dedun.composeapp.generated.resources.send_again
import dedun.composeapp.generated.resources.message_sent
import dedun.composeapp.generated.resources.signed_but_not_sent
import dedun.composeapp.generated.resources.signed_not_yet_sent
import dedun.composeapp.generated.resources.the_group
import dedun.composeapp.generated.resources.waiting_for_n_more_admins_to_open_this
import dedun.composeapp.generated.resources.waiting_for_one_more_admin_to_open_this
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupDmConversationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupDmConversationViewModel
import com.machankura.dedun.compose.ui.view.model.GroupDmListViewModel
import com.machankura.dedun.compose.ui.view.state.GroupDmConversationUIState
import com.machankura.dedun.compose.ui.view.state.GroupDmLine
import com.machankura.dedun.compose.ui.view.state.GroupDmListUIState
import com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState
import com.machankura.dedun.compose.ui.view.state.GroupDmPending
import org.jetbrains.compose.resources.stringResource

/**
 * A group's conversations (docs/group-threshold-ecdh.md, Phase 8): who has written, the last
 * line, what is unread, and what is still being opened, naming the admins it waits on rather
 * than a count. Nothing here says how the opening works; a person reads "waiting for 1 more
 * admin", not a protocol.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDmListScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chatRepository: ChatRepository,
    groupDmRepository: GroupDmRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupDmListViewModel = viewModel(
        factory = GroupDmListViewModel.factory(chatRoomId, activeUserPublicKey, chatRepository, groupDmRepository)
    )
    var writing by remember { mutableStateOf(false) }
    val canWrite = (viewModel.uiState as? GroupDmListUIState.Loaded)?.isAdmin == true && LocalCanSign.current
    if (writing) {
        NewMessageDialog(
            onDismiss = { writing = false },
            onRecipient = { recipient ->
                writing = false
                onNavigateToRoute(GroupDmConversationRoute(activeUserPublicKey, chatRoomId, conversationId = null, recipients = listOf(recipient)))
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_messages), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
        floatingActionButton = {
            if (canWrite) {
                ExtendedFab(
                    label = stringResource(Res.string.new_message),
                    icon = { Icon(Icons.Default.Edit, contentDescription = Decorative) },
                    onClick = { writing = true },
                )
            }
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupDmListUIState.Loading -> LoadingDataIndicator(modifier = Modifier.padding(padding))
                // The room came from a navigation argument; reading it again fails the same way.
                is GroupDmListUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)
                is GroupDmListUIState.Loaded -> LazyColumn(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    if (!state.isAdmin) {
                        item { EmptyState(message = stringResource(Res.string.only_current_admins_see_the_groups_messages), icon = Icons.Default.Mail) }
                        return@LazyColumn
                    }
                    if (state.pending.waiting > 0 || state.pending.queued > 0) {
                        item { PendingCard(state.pending) }
                    }
                    if (state.conversations.isEmpty()) {
                        item { EmptyState(message = stringResource(Res.string.nobody_has_written_to_the_group_yet), icon = Icons.Default.Mail) }
                    }
                    items(items = state.conversations, key = { it.conversationId }) { conversation ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onNavigateToRoute(GroupDmConversationRoute(activeUserPublicKey, chatRoomId, conversation.conversationId))
                            },
                        ) {
                            ListItem(
                                leadingContent = { Icon(Icons.Default.Mail, contentDescription = Decorative) },
                                trailingContent = {
                                    if (conversation.unread > 0) {
                                        Badge { Text(stringResource(Res.string.n_unread, conversation.unread)) }
                                    } else {
                                        Icon(Icons.Default.ChevronRight, contentDescription = Decorative)
                                    }
                                },
                                headlineContent = { Text(conversation.names.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(
                                        conversation.lastLine ?: stringResource(Res.string.a_message_this_screen_cant_show_yet),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What is still being opened, and on whom it waits. */
@Composable
internal fun PendingCard(pending: GroupDmPending) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(Icons.Default.HourglassTop, contentDescription = Decorative) },
            headlineContent = {
                Text(
                    if (pending.waiting == 1) stringResource(Res.string.one_message_being_opened)
                    else stringResource(Res.string.n_messages_being_opened, pending.waiting)
                )
            },
            supportingContent = {
                Column {
                    when {
                        pending.moreAdminsNeeded == 1 -> Text(stringResource(Res.string.waiting_for_one_more_admin_to_open_this))
                        pending.moreAdminsNeeded > 1 -> Text(stringResource(Res.string.waiting_for_n_more_admins_to_open_this, pending.moreAdminsNeeded))
                    }
                    if (pending.notYetAnsweredBy.isNotEmpty()) {
                        Text(stringResource(Res.string.not_yet_answered_by, pending.notYetAnsweredBy.joinToString(", ")))
                    }
                    if (pending.queued > 0) Text(stringResource(Res.string.n_more_are_queued, pending.queued))
                }
            },
        )
    }
}

/**
 * One of a group's conversations, in and out (Phase 8). A message the group sent shows where it
 * has got to, and one signed but not on a relay offers Send again (Phase 9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDmConversationScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    conversationId: HexKey?,
    recipients: List<HexKey>,
    chatRepository: ChatRepository,
    groupDmRepository: GroupDmRepository,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit = {},
) {
    val notify = rememberNotifier(rememberCoroutineScope())
    val proposed = stringResource(Res.string.asked_the_group_to_sign_this_message)
    val noEvidence = stringResource(Res.string.nothing_signed_by_this_person_is_on_this_device_yet)
    val couldNotSend = stringResource(Res.string.could_not_ask_the_group_to_send_this)
    val sendingAgain = stringResource(Res.string.sending_again)
    val canSign = LocalCanSign.current
    val viewModel: GroupDmConversationViewModel = viewModel(
        key = "$chatRoomId/$conversationId/${recipients.joinToString(",")}",
        factory = GroupDmConversationViewModel.factory(chatRoomId, activeUserPublicKey, conversationId, recipients, chatRepository, groupDmRepository)
    )

    ScreenStateTransition(viewModel.uiState) { uiState ->
        when (val state = uiState) {
            GroupDmConversationUIState.Loading -> LoadingDataIndicator()
            is GroupDmConversationUIState.Error -> ErrorState(message = state.message, onRetry = null)
            is GroupDmConversationUIState.Loaded -> Scaffold(
                snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                topBar = {
                    TopAppBar(
                        title = { Text(state.names.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { NavigateBackButton(onNavigateBack) },
                    )
                },
                bottomBar = {
                    if (state.isAdmin && canSign) {
                        Composer(
                            composing = viewModel.composing,
                            onSend = { text, clear ->
                                viewModel.send(
                                    text = text,
                                    replyTo = state.lines.lastOrNull { !it.outgoing }?.id,
                                    onProposed = { sessionId ->
                                        clear()
                                        notify(proposed)
                                        onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                    },
                                    onNoEvidence = { notify(noEvidence) },
                                    onFailure = { notify(couldNotSend) },
                                )
                            },
                        )
                    }
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                ) {
                    if (!state.isAdmin) {
                        item { EmptyState(message = stringResource(Res.string.only_current_admins_see_the_groups_messages), icon = Icons.Default.Mail) }
                        return@LazyColumn
                    }
                    items(items = state.lines, key = { it.id }) { line ->
                        GroupDmBubble(line, onSendAgain = { sessionId -> viewModel.sendAgain(sessionId) { notify(sendingAgain) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDmBubble(line: GroupDmLine, onSendAgain: ((String) -> Unit)?) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (line.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            colors = if (line.outgoing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors(),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.compactPadding),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
            ) {
                Text(
                    text = if (line.outgoing) stringResource(Res.string.the_group) else line.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = line.text ?: stringResource(Res.string.a_message_this_screen_cant_show_yet), style = MaterialTheme.typography.bodyLarge)
                line.outgoingState?.let { state ->
                    Text(
                        text = stringResource(
                            when (state) {
                                GroupDmOutgoingState.BEING_SIGNED -> Res.string.being_signed
                                GroupDmOutgoingState.SIGNED -> Res.string.signed_not_yet_sent
                                GroupDmOutgoingState.SENT -> Res.string.message_sent
                                GroupDmOutgoingState.NOT_SENT -> Res.string.signed_but_not_sent
                                GroupDmOutgoingState.DECLINED -> Res.string.declined_by_the_group
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val sessionId = line.signingSessionId
                    val owes = state == GroupDmOutgoingState.NOT_SENT || state == GroupDmOutgoingState.SIGNED
                    if (owes && sessionId != null && onSendAgain != null) {
                        TextButton(onClick = { onSendAgain(sessionId) }) { Text(stringResource(Res.string.send_again)) }
                    }
                }
            }
        }
    }
}

/**
 * What an admin writes as the group, with what sending means said above the button: nothing
 * goes until the group's quorum has signed (D10).
 */
@Composable
private fun Composer(composing: GroupDmConversationViewModel.Composing, onSend: (String, clear: () -> Unit) -> Unit) {
    val field = rememberTextFieldState()
    Column(
        modifier = Modifier.fillMaxWidth().imePadding().padding(MaterialTheme.spacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
    ) {
        Text(
            text = when (composing) {
                GroupDmConversationViewModel.Composing.Preparing -> stringResource(Res.string.preparing_the_admins_are_working_out_how_to_write_to_them)
                else -> stringResource(Res.string.the_groups_quorum_has_to_sign_before_anything_is_sent)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            OutlinedTextField(
                state = field,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(Res.string.write_a_message)) },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            )
            FilledIconButton(
                enabled = composing == GroupDmConversationViewModel.Composing.Idle && field.text.isNotBlank(),
                onClick = { onSend(field.text.toString()) { field.clearText() } },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.send))
            }
        }
    }
}

/** Starts a conversation: an npub, pasted. */
@Composable
internal fun NewMessageDialog(onDismiss: () -> Unit, onRecipient: (HexKey) -> Unit) {
    val field = rememberTextFieldState()
    val key = recipientKeyOf(field.text.toString())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_message)) },
        text = {
            OutlinedTextField(
                state = field,
                label = { Text(stringResource(Res.string.their_npub)) },
                isError = field.text.isNotBlank() && key == null,
                supportingText = if (field.text.isNotBlank() && key == null) {
                    { Text(stringResource(Res.string.that_is_not_a_nostr_key)) }
                } else null,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
        confirmButton = {
            TextButton(enabled = key != null, onClick = { key?.let(onRecipient) }) { Text(stringResource(Res.string.write_to_them)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** An npub or 64 hex characters, as a key; null for anything else. */
internal fun recipientKeyOf(text: String): HexKey? {
    val trimmed = text.trim().removePrefix("nostr:")
    val hex = when {
        trimmed.startsWith("npub1") -> runCatching { trimmed.bech32ToHexOrThrow() }.getOrNull()
        else -> trimmed.lowercase()
    } ?: return null
    return hex.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } && com.machankura.dedun.compose.cryptography.threshold.ThresholdEcdh.pointOf(it) != null }
}
