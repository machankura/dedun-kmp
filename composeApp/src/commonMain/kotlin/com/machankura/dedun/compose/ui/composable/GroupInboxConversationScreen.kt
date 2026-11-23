package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dedun.composeapp.generated.resources.ask_the_group_to_sign_this_reply
import dedun.composeapp.generated.resources.could_not_ask_the_group_to_sign_this_reply
import dedun.composeapp.generated.resources.has_announced_no_inbox_key
import dedun.composeapp.generated.resources.not_yet_on_a_relay
import dedun.composeapp.generated.resources.reply_as_the_group
import dedun.composeapp.generated.resources.send_reply_again
import dedun.composeapp.generated.resources.sent_again
import dedun.composeapp.generated.resources.the_group_signs_a_reply
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupInboxRepository
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.couldn_t_find_the_group
import dedun.composeapp.generated.resources.nobody_has_written_to_the_group_yet
import dedun.composeapp.generated.resources.the_group
import dedun.composeapp.generated.resources.you_and_the_group
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupInboxConversationViewModel
import com.machankura.dedun.compose.ui.view.state.GroupInboxConversationUIState
import org.jetbrains.compose.resources.stringResource

/**
 * One conversation in the group's inbox (docs/group-inbox.md, D8 and D10): what the people in it
 * wrote, and what the group answered, in the order they were written. A reply is the group's,
 * not the admin's who drafted it, so it is marked as the group's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInboxConversationScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    conversationId: String,
    initialUIState: GroupInboxConversationUIState = GroupInboxConversationUIState.Loading,
    chatRepository: ChatRepository,
    inboxRepository: GroupInboxRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupInboxConversationViewModel = viewModel(
        factory = GroupInboxConversationViewModel.factory(chatRoomId, conversationId, initialUIState, chatRepository, inboxRepository)
    )
    LaunchedEffect(true) {
        if (initialUIState == GroupInboxConversationUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_this_reply)
    val sentAgain = stringResource(Res.string.sent_again)
    val canSign = LocalCanSign.current
    var draft by rememberSaveable { mutableStateOf("") }
    val title = (viewModel.uiState as? GroupInboxConversationUIState.Loaded)?.names?.joinToString(", ")
        ?.let { stringResource(Res.string.you_and_the_group, it) }
        ?: stringResource(Res.string.the_group)

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
                is GroupInboxConversationUIState.Error -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = stringResource(Res.string.couldn_t_find_the_group),
                    onRetry = null,
                )

                GroupInboxConversationUIState.Loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(padding).padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))
                    LoadingDataIndicator(fillScreen = false)
                }

                is GroupInboxConversationUIState.Loaded -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    if (state.lines.isEmpty()) {
                        EmptyState(
                            modifier = Modifier.weight(1f),
                            message = stringResource(Res.string.nobody_has_written_to_the_group_yet),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        ) {
                            items(state.lines, key = { it.rumorId }) { line ->
                                MessageCard(line, onSendAgain = { session -> viewModel.sendAgain(session) { if (it) notify(sentAgain) } })
                            }
                        }
                    }
                    if (canSign && state.lines.isNotEmpty()) {
                        Composer(
                            draft = draft,
                            onDraft = { draft = it },
                            withoutKey = state.withoutKey,
                            proposing = state.proposing,
                            onPropose = {
                                viewModel.propose(draft) { session ->
                                    if (session == null) {
                                        notify(couldNotPropose)
                                    } else {
                                        draft = ""
                                        onNavigateToRoute(FrostSigningRoute(activeUserPublicKey, chatRoomId, session))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The way to answer (D10, D13): the words, what signing it means, a warning for every recipient
 * whose app may not open a message from a group, and the button that asks the quorum.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    withoutKey: List<String>,
    proposing: Boolean,
    onPropose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
        withoutKey.forEach { name ->
            Text(
                stringResource(Res.string.has_announced_no_inbox_key, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = draft,
            onValueChange = onDraft,
            label = { Text(stringResource(Res.string.reply_as_the_group)) },
            minLines = 2,
        )
        Text(
            stringResource(Res.string.the_group_signs_a_reply),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPropose, enabled = draft.isNotBlank() && !proposing) {
            Text(stringResource(Res.string.ask_the_group_to_sign_this_reply))
        }
    }
}

/** One message: theirs on the leading edge, the group's on the trailing one, in its own colours. */
@Composable
private fun MessageCard(line: GroupInboxConversationUIState.Line, onSendAgain: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.outgoing) Alignment.End else Alignment.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = MaterialTheme.spacing.space400 * 8),
            colors = if (line.outgoing) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
            ) {
                Text(
                    text = if (line.outgoing) stringResource(Res.string.the_group) else line.senderName,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = line.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = Instant.fromEpochSeconds(line.createdAt).toFormattedTimeAndDateString(),
                    style = MaterialTheme.typography.labelSmall,
                )
                line.unsentSessionId?.let { session ->
                    Text(stringResource(Res.string.not_yet_on_a_relay), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onSendAgain(session) }) { Text(stringResource(Res.string.send_reply_again)) }
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun GroupInboxConversationScreenPreview() {
    val chatRoomId = "d".repeat(64)
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupInboxConversationScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                conversationId = "c1",
                initialUIState = GroupInboxConversationUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "&Ekklesia", description = null, mlsGroupState = "state")
                    ),
                    names = listOf("Zola"),
                    lines = listOf(
                        GroupInboxConversationUIState.Line("r1", "Can I join the reading group?", 1_700_000_000, outgoing = false, senderName = "Zola"),
                        GroupInboxConversationUIState.Line("r2", "Yes. We meet on Thursdays.", 1_700_003_600, outgoing = true, senderName = ""),
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
