package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import com.machankura.dedun.compose.ui.composable.navigation.routes.CreateSharedKeyRoute
import dedun.composeapp.generated.resources.start_the_key_ceremony
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.SelectChatRoomTypeViewModel
import com.machankura.dedun.compose.ui.view.state.SelectChatRoomTypeUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.create_chat
import dedun.composeapp.generated.resources.how_many_admins_have_to_approve_a_change
import dedun.composeapp.generated.resources.just_you_for_now
import dedun.composeapp.generated.resources.open_chat
import dedun.composeapp.generated.resources.this_decides_who_can_change_the_group_later
import dedun.composeapp.generated.resources.you_and_1_other
import dedun.composeapp.generated.resources.how_should_be_run
import dedun.composeapp.generated.resources.of
import dedun.composeapp.generated.resources.was_created_but_couldn_t_be_added_yet_invite
import dedun.composeapp.generated.resources.was_created_but_its_shared_key_ceremony
import dedun.composeapp.generated.resources.you_and_others
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

/**
 * Last step of group creation: convenient (one admin) or robust (everyone
 * admins, changes need a threshold to approve), and then build the group.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SelectChatRoomTypeScreen(
    activeUserPublicKey: HexKey,
    name: String,
    description: String?,
    memberPublicKeys: List<HexKey>,
    initialSelectChatRoomTypeUIState: SelectChatRoomTypeUIState = SelectChatRoomTypeUIState.Loading,
    activeIdentityStateFlow: StateFlow<Identity?>,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val selectChatRoomTypeViewModel: SelectChatRoomTypeViewModel = viewModel(
        factory = SelectChatRoomTypeViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            name = name,
            description = description,
            memberPublicKeys = memberPublicKeys,
            initialSelectChatRoomTypeUIState = initialSelectChatRoomTypeUIState,
            activeIdentityStateFlow = activeIdentityStateFlow,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            dkgRepository = dkgRepository
        )
    )

    ScreenStateTransition(selectChatRoomTypeViewModel.selectChatRoomTypeUIState) { uiState ->
    when (val selectChatRoomTypeUIState = uiState) {
            is SelectChatRoomTypeUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = selectChatRoomTypeUIState.message,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is SelectChatRoomTypeUIState.Loaded -> {
                val isActionPending = selectChatRoomTypeViewModel.isActionPending.value
                val selectedChatRoomType = selectChatRoomTypeViewModel.selectedChatRoomType.value
                val membersNotAdded = selectChatRoomTypeViewModel.membersNotAdded
                val keyCeremonyNotStarted = selectChatRoomTypeViewModel.keyCeremonyNotStarted.value
                val adminCount = selectChatRoomTypeViewModel.adminCount
                val isRobustAvailable = selectChatRoomTypeViewModel.isRobustAvailable
                val isRobustSelected = selectedChatRoomType == ChatRoomType.ROBUST

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(Res.string.how_should_be_run, name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            floatingActionButton = {
                                ExtendedFab(
                                    label = if (selectChatRoomTypeViewModel.createdChatRoomId.value != null) {
                                        stringResource(Res.string.open_chat)
                                    } else {
                                        stringResource(Res.string.create_chat)
                                    },
                                    icon = {
                                        if (isActionPending) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        } else {
                                            Icon(Icons.Default.Check, contentDescription = "Create chat")
                                        }
                                    },
                                    onClick = {
                                        selectChatRoomTypeViewModel.createChatRoom(
                                            onNavigateToRoute = onNavigateToRoute
                                        )
                                    }
                                )
                            },
                            actions = {
                                Text(
                                    modifier = Modifier.padding(start = MaterialTheme.spacing.space200),
                                    text = when (selectChatRoomTypeUIState.members.size) {
                                        0 -> stringResource(Res.string.just_you_for_now)
                                        1 -> stringResource(Res.string.you_and_1_other)
                                        else -> stringResource(Res.string.you_and_others, selectChatRoomTypeUIState.members.size)
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .readableContent()
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacing.space125),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        if (membersNotAdded.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Text(
                                    modifier = Modifier.padding(MaterialTheme.spacing.space200),
                                    text = stringResource(Res.string.was_created_but_couldn_t_be_added_yet_invite, name, membersNotAdded.joinToString { selectChatRoomTypeViewModel.displayNameFor(it) }),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        if (keyCeremonyNotStarted) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Text(
                                    modifier = Modifier.padding(MaterialTheme.spacing.space200),
                                    text = stringResource(Res.string.was_created_but_its_shared_key_ceremony, name),
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                // Straight to the one screen that starts a group's own
                                // ceremony, rather than leaving the member to find it
                                // through the group's details.
                                selectChatRoomTypeViewModel.createdChatRoomId.value?.let { createdChatRoomId ->
                                    TextButton(
                                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.space100),
                                        onClick = {
                                            onNavigateToRoute(
                                                CreateSharedKeyRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = createdChatRoomId,
                                                )
                                            )
                                        }
                                    ) {
                                        Text(stringResource(Res.string.start_the_key_ceremony))
                                    }
                                }
                            }
                        }

                        Text(
                            text = stringResource(Res.string.this_decides_who_can_change_the_group_later),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.space125)
                        )

                        ChatRoomTypeCard(
                            icon = Icons.Default.Bolt,
                            title = "Convenient",
                            summary = "You are the only admin.",
                            detail = "You can add or remove people and change the group's name or description on your own, without waiting for anybody.",
                            footnote = "Nothing about the group can change unless you do it — and nobody else can carry it on if you lose your keys.",
                            isSelected = selectedChatRoomType == ChatRoomType.CONVENIENT,
                            onClick = {
                                selectChatRoomTypeViewModel.selectChatRoomType(ChatRoomType.CONVENIENT)
                            }
                        )

                        ChatRoomTypeCard(
                            icon = Icons.Default.Groups,
                            title = "Robust",
                            summary = "Every member is an admin.",
                            detail = if (isRobustAvailable) {
                                "All $adminCount of you administer the group together. Any change — adding or removing someone, renaming the group — has to be approved by a quorum of you before it takes effect."
                            } else {
                                "Everyone administers the group together. Any change — adding or removing someone, renaming the group — has to be approved by a quorum of the admins before it takes effect."
                            },
                            // Says what tapping create actually does, because it is the one
                            // thing here that asks something of everybody else: the group's
                            // first act is a ceremony each member has to approve their way
                            // through before there is a key.
                            footnote = "No single admin can change the group alone, and the group outlives any one of you. Creating the group starts a key ceremony every member takes part in.",
                            isSelected = isRobustSelected,
                            isEnabled = isRobustAvailable,
                            disabledReason = selectChatRoomTypeViewModel.robustUnavailableReason,
                            onClick = {
                                selectChatRoomTypeViewModel.selectChatRoomType(ChatRoomType.ROBUST)
                            }
                        ) {
                            // Ask for the quorum only once robust is the choice — before that
                            // there is no decision to make.
                            if (isRobustSelected) {
                                QuorumPicker(
                                    quorum = selectChatRoomTypeViewModel.quorum.value,
                                    adminCount = adminCount,
                                    quorumRange = selectChatRoomTypeViewModel.quorumRange,
                                    explanation = selectChatRoomTypeViewModel.quorumExplanation(),
                                    onQuorumChange = selectChatRoomTypeViewModel::setQuorum
                                )
                            }
                        }
                    }
                }
            }
            SelectChatRoomTypeUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        MaterialTheme.spacing.space250
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {

                    Spacer(
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = stringResource(Res.string.how_should_be_run, name),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(
                        fillScreen = false
                    )

                    Spacer(
                        modifier = Modifier.weight(2f)
                    )
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialSelectChatRoomTypeUIState == SelectChatRoomTypeUIState.Loading) {
            selectChatRoomTypeViewModel.initiate()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRoomTypeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    detail: String,
    footnote: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
    disabledReason: String? = null,
    content: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = isEnabled,
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space200),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick,
                    enabled = isEnabled
                )

                Icon(
                    imageVector = icon,
                    contentDescription = Decorative
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall
            )

            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = footnote,
                style = MaterialTheme.typography.labelMedium
            )

            disabledReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            content()
        }
    }
}

/**
 * Asks how many admins have to sign off on a change. Stepped rather than typed:
 * the range is small, both ends are bounded, and the caption has to keep up.
 */
@Composable
private fun QuorumPicker(
    quorum: Int,
    adminCount: Int,
    quorumRange: IntRange,
    explanation: String,
    onQuorumChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.space50),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        Text(
            text = stringResource(Res.string.how_many_admins_have_to_approve_a_change),
            style = MaterialTheme.typography.titleSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { onQuorumChange(quorum - 1) },
                enabled = quorum > quorumRange.first
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Fewer approvals"
                )
            }

            Text(
                text = stringResource(Res.string.of, quorum, adminCount),
                style = MaterialTheme.typography.titleMedium
            )

            FilledIconButton(
                onClick = { onQuorumChange(quorum + 1) },
                enabled = quorum < quorumRange.last
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "More approvals"
                )
            }
        }

        Text(
            text = explanation,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@ConformancePreviews
@Composable
private fun SelectChatRoomTypeScreenPreview() {
    DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            SelectChatRoomTypeScreen(
                activeUserPublicKey = "",
                name = "Group discussions",
                description = "See something... say something.",
                memberPublicKeys = listOf("hex", "otherHex"),
                initialSelectChatRoomTypeUIState = SelectChatRoomTypeUIState.Loaded(
                    members = listOf(
                        Profile(
                            publicKey = "hex",
                            displayName = "Man With A Plan",
                            about = "Making plans and getting things done...",
                            nostrEventId = "nostrEventId"
                        ),
                        Profile(
                            publicKey = "otherHex",
                            displayName = "Woman Of Few Words",
                            about = "Says it once, says it well.",
                            nostrEventId = "otherNostrEventId"
                        )
                    )
                ),
                activeIdentityStateFlow = MutableStateFlow(null),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
