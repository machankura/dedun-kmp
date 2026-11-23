package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import dedun.composeapp.generated.resources.earlier_mail_is_not_on_this_device
import dedun.composeapp.generated.resources.inbox
import dedun.composeapp.generated.resources.inbox_settings
import dedun.composeapp.generated.resources.n_messages_this_device_can_t_open
import dedun.composeapp.generated.resources.nobody_has_written_to_the_group_yet
import dedun.composeapp.generated.resources.one_message_this_device_can_t_open
import dedun.composeapp.generated.resources.the_group_wrote
import dedun.composeapp.generated.resources.the_group_hasn_t_announced_its_inbox
import dedun.composeapp.generated.resources.why_a_message_can_t_be_opened
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupInboxConversationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupInboxSettingsRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
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
import com.machankura.dedun.compose.ui.view.model.GroupInboxViewModel
import com.machankura.dedun.compose.ui.view.state.GroupInboxUIState
import org.jetbrains.compose.resources.stringResource

/**
 * The group's inbox: the conversations people have started with the group by writing to its
 * npub, newest first (docs/group-inbox.md, D8 and D9).
 *
 * Reached from the group's screen, and only on a device that holds the inbox key. Says what it
 * cannot do as plainly as what it can: mail no key here opens is counted with why, and an inbox
 * the group has made but not yet announced says nobody can write to it yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInboxScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialUIState: GroupInboxUIState = GroupInboxUIState.Loading,
    chatRepository: ChatRepository,
    inboxRepository: GroupInboxRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupInboxViewModel = viewModel(
        factory = GroupInboxViewModel.factory(chatRoomId, initialUIState, chatRepository, inboxRepository)
    )
    LaunchedEffect(true) {
        if (initialUIState == GroupInboxUIState.Loading) viewModel.initiate()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.inbox), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
                actions = {
                    IconButton(onClick = { onNavigateToRoute(GroupInboxSettingsRoute(activeUserPublicKey, chatRoomId, relayHint)) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.inbox_settings))
                    }
                },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                // Nothing to retry: the room came from a navigation argument.
                is GroupInboxUIState.Error -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = stringResource(Res.string.couldn_t_find_the_group),
                    onRetry = null,
                )

                GroupInboxUIState.Loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(padding).padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))
                    LoadingDataIndicator(fillScreen = false)
                }

                is GroupInboxUIState.Loaded -> LazyColumn(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    val status = state.status
                    if (status.announcedKey == null) {
                        item { NoticeCard(Icons.Default.Lock, stringResource(Res.string.the_group_hasn_t_announced_its_inbox)) }
                    }
                    if (state.unopenable > 0) {
                        item {
                            NoticeCard(
                                Icons.Default.Lock,
                                if (state.unopenable == 1) stringResource(Res.string.one_message_this_device_can_t_open)
                                else stringResource(Res.string.n_messages_this_device_can_t_open, state.unopenable),
                                stringResource(Res.string.why_a_message_can_t_be_opened),
                            )
                        }
                    }
                    if (state.conversations.isEmpty()) {
                        item {
                            // D15: a promoted admin's first key opens nothing sent before it.
                            val joinedLate = status.keys.isNotEmpty() && status.keys.minOf { it.generation } > 0
                            EmptyState(
                                message = stringResource(
                                    if (joinedLate) Res.string.earlier_mail_is_not_on_this_device
                                    else Res.string.nobody_has_written_to_the_group_yet
                                )
                            )
                        }
                    } else {
                        items(state.conversations, key = { it.conversationId }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onClick = {
                                    onNavigateToRoute(
                                        GroupInboxConversationRoute(activeUserPublicKey, chatRoomId, relayHint, conversation.conversationId)
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

@Composable
private fun NoticeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, headline: String, supporting: String? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = Decorative) },
            headlineContent = { Text(headline) },
            supportingContent = supporting?.let { { Text(it) } },
        )
    }
}

/** One conversation: who it is with, the last line and when, and how much is unread. */
@Composable
private fun ConversationRow(conversation: GroupInboxRepository.ConversationSummary, onClick: () -> Unit) {
    val lastLine = if (conversation.lastOutgoing) {
        stringResource(Res.string.the_group_wrote, conversation.lastText)
    } else {
        conversation.lastText
    }
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Mail, contentDescription = Decorative) },
            headlineContent = {
                Text(conversation.names.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                    Text(lastLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        Instant.fromEpochSeconds(conversation.lastAt).toFormattedTimeAndDateString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                if (conversation.unread > 0) {
                    Badge { Text(conversation.unread.toString()) }
                } else {
                    Icon(Icons.Default.ChevronRight, contentDescription = Decorative)
                }
            },
        )
    }
}

@ConformancePreviews
@Composable
private fun GroupInboxScreenPreview() {
    val chatRoomId = "d".repeat(64)
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupInboxScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                initialUIState = GroupInboxUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "Ekklesia", description = null, mlsGroupState = "state")
                    ),
                    conversations = listOf(
                        GroupInboxRepository.ConversationSummary(
                            conversationId = "c1", participants = listOf("b".repeat(64)), names = listOf("Zola"),
                            lastText = "Can I join the reading group?", lastAt = 1_700_000_000, lastOutgoing = false, unread = 1,
                        )
                    ),
                    unopenable = 2,
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
