package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.new_group_with_these_members
import dedun.composeapp.generated.resources.an_unnamed_group
import dedun.composeapp.generated.resources.you_already_have_a_group_with_these_people
import dedun.composeapp.generated.resources.you_already_have_groups_with_these_people
import dedun.composeapp.generated.resources.name
import dedun.composeapp.generated.resources.the_new_group_and_its_room_are_called_this
import dedun.composeapp.generated.resources.a_shared_key_needs_at_least_members
import dedun.composeapp.generated.resources.couldn_t_load_this_group
import dedun.composeapp.generated.resources.couldn_t_read_your_keys
import dedun.composeapp.generated.resources.couldn_t_start_the_key_ceremony
import dedun.composeapp.generated.resources.everyone_has_to_be_online_at_the_same_time
import dedun.composeapp.generated.resources.how_many_members_will_it_take_to_sign
import dedun.composeapp.generated.resources.of
import dedun.composeapp.generated.resources.shared_key
import dedun.composeapp.generated.resources.shared_key_for
import dedun.composeapp.generated.resources.start_key_ceremony
import dedun.composeapp.generated.resources.the_group_can_hold_one_key_together_split_so
import dedun.composeapp.generated.resources.this_is_fixed_once_the_ceremony_runs
import dedun.composeapp.generated.resources.this_room_was_made_from_a_key_the_group_already_holds
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.ChillDkgRitualManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.CreateSharedKeyViewModel
import com.machankura.dedun.compose.ui.view.state.CreateSharedKeyUIState

/**
 * Starts a group's key ceremony in a NIP-17 room: its name, the quorum, and one
 * button. The room's first group, or another made by the same people beside the
 * ones they have (docs/nip17-multiple-ceremonies.md).
 *
 * The only screen that does. The ceremony screen it hands over to shows a
 * ceremony and never offers to start one, because doing both from one button
 * slot is how a member approving a subgroup's ceremony opened a second, whole-room
 * one in the parent's room. See docs/ceremony-creation.md.
 *
 * [onCeremonyOpened] is called with the ceremony to show, whether this screen
 * opened it or a repeated ask was folded into one already running under the same
 * name. The caller replaces this screen with it, so back from the ceremony goes
 * wherever the member came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSharedKeyScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    initialUIState: CreateSharedKeyUIState = CreateSharedKeyUIState.Loading,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onCeremonyOpened: (dkgSessionId: String) -> Unit,
    onNavigateBack: () -> Unit,
    retrying: String? = null,
) {
    val viewModel: CreateSharedKeyViewModel = viewModel(
        factory = CreateSharedKeyViewModel.factory(
            chatRoomId = chatRoomId,
            activeUserPublicKey = activeUserPublicKey,
            initialUIState = initialUIState,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            dkgRepository = dkgRepository,
            retrying = retrying,
        )
    )

    LaunchedEffect(true) {
        if (initialUIState == CreateSharedKeyUIState.Loading) {
            viewModel.initiate()
        }
    }

    val state = viewModel.uiState

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    // The room's name only once it is loaded and only for its first
                    // group, which it was named for; before that, and on a refusal,
                    // the plain title rather than "Shared key for " and nothing. A
                    // room that already has a group is making another, and its name
                    // is the first group's, not this one's.
                    val loaded = state as? CreateSharedKeyUIState.Loaded
                    val subject = loaded?.takeIf { !it.needsName }?.localChatRoom?.chatRoom?.subject
                    Text(
                        text = when {
                            loaded?.needsName == true -> stringResource(Res.string.new_group_with_these_members)
                            subject != null -> stringResource(Res.string.shared_key_for, subject)
                            else -> stringResource(Res.string.shared_key)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { innerPadding ->
        ScreenStateTransition(state) { uiState ->
            when (uiState) {
                CreateSharedKeyUIState.Loading -> LoadingDataIndicator(
                    modifier = Modifier.padding(innerPadding).fillMaxSize()
                )

                is CreateSharedKeyUIState.Error -> ErrorState(
                    modifier = Modifier.padding(innerPadding).readableContent(),
                    message = when (uiState.reason) {
                        CreateSharedKeyUIState.Error.Reason.ROOM_NOT_FOUND ->
                            stringResource(Res.string.couldn_t_load_this_group)
                        CreateSharedKeyUIState.Error.Reason.NO_KEYS ->
                            stringResource(Res.string.couldn_t_read_your_keys)
                        CreateSharedKeyUIState.Error.Reason.NOT_STARTED ->
                            stringResource(Res.string.couldn_t_start_the_key_ceremony)
                    },
                    onRetry = viewModel::initiate,
                )

                is CreateSharedKeyUIState.Refused -> EmptyState(
                    modifier = Modifier.padding(innerPadding).readableContent(),
                    icon = Icons.Default.Key,
                    message = when (uiState.reason) {
                        CreateSharedKeyUIState.Refused.Reason.MARMOT_ROOM ->
                            stringResource(Res.string.this_room_was_made_from_a_key_the_group_already_holds)
                        CreateSharedKeyUIState.Refused.Reason.TOO_FEW_MEMBERS ->
                            stringResource(
                                Res.string.a_shared_key_needs_at_least_members,
                                ChillDkgRitualManager.MINIMUM_PARTICIPANTS
                            )
                    },
                )

                is CreateSharedKeyUIState.Loaded -> {
                    val isActionPending = viewModel.isActionPending.value

                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .readableContent()
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacing.containerPadding),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                    ) {
                        if (uiState.groups.isNotEmpty()) {
                            val unnamed = stringResource(Res.string.an_unnamed_group)
                            val roomSubject = uiState.localChatRoom.chatRoom.subject
                            val names = uiState.groups.joinToString(", ") {
                                it.subject ?: roomSubject ?: unnamed
                            }
                            Text(
                                text = if (uiState.groups.size == 1) {
                                    stringResource(Res.string.you_already_have_a_group_with_these_people, names)
                                } else {
                                    stringResource(Res.string.you_already_have_groups_with_these_people, names)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Text(
                            text = stringResource(Res.string.the_group_can_hold_one_key_together_split_so),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // The name the group's room will carry. A room that already
                        // has a group has to name this one: the room keeps its first
                        // group's name, and falling back to it would make that
                        // group's namesake. docs/nip17-multiple-ceremonies.md, D2.
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = viewModel.name.value,
                            onValueChange = viewModel::setName,
                            enabled = !isActionPending,
                            singleLine = true,
                            label = { Text(stringResource(Res.string.name)) },
                            supportingText = if (uiState.needsName) {
                                { Text(stringResource(Res.string.the_new_group_and_its_room_are_called_this)) }
                            } else {
                                null
                            },
                        )

                        Text(
                            text = stringResource(
                                Res.string.everyone_has_to_be_online_at_the_same_time,
                                uiState.participantCount
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        SharedKeyQuorumStepper(
                            threshold = viewModel.threshold.value,
                            participantCount = uiState.participantCount,
                            quorumRange = viewModel.quorumRange(),
                            onThresholdChange = viewModel::setThreshold
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isActionPending && viewModel.canCreate(),
                            onClick = { viewModel.create(onCreated = onCeremonyOpened) }
                        ) {
                            if (isActionPending) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Key, contentDescription = Decorative)
                                Text(
                                    modifier = Modifier.padding(start = MaterialTheme.spacing.relatedGap),
                                    text = stringResource(Res.string.start_key_ceremony)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Same shape as the group-creation quorum picker, so the choice reads the same. */
@Composable
private fun SharedKeyQuorumStepper(
    threshold: Int,
    participantCount: Int,
    quorumRange: IntRange,
    onThresholdChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.space50),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        Text(
            text = stringResource(Res.string.how_many_members_will_it_take_to_sign),
            style = MaterialTheme.typography.titleSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { onThresholdChange(threshold - 1) },
                enabled = threshold > quorumRange.first
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer signers")
            }

            Text(
                text = stringResource(Res.string.of, threshold, participantCount),
                style = MaterialTheme.typography.titleMedium
            )

            FilledIconButton(
                onClick = { onThresholdChange(threshold + 1) },
                enabled = threshold < quorumRange.last
            ) {
                Icon(Icons.Default.Add, contentDescription = "More signers")
            }
        }

        Text(
            text = stringResource(Res.string.this_is_fixed_once_the_ceremony_runs),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@ConformancePreviews
@Composable
private fun CreateSharedKeyScreenPreview() {
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CreateSharedKeyScreen(
                activeUserPublicKey = "",
                chatRoomId = "chatRoomId",
                initialUIState = CreateSharedKeyUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "chatRoomId",
                            userPublicKey = "",
                            subject = "Group discussions",
                            description = null,
                            mlsGroupState = null,
                        )
                    ),
                    participantCount = 3,
                ),
                activeIdentityStateFlow = MutableStateFlow(null),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                onCeremonyOpened = {},
                onNavigateBack = {},
            )
        }
    }
}
