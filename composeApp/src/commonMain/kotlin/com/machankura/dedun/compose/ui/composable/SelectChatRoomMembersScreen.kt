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
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.model.SelectChatRoomMembersViewModel
import com.machankura.dedun.compose.ui.view.state.SelectChatRoomMembersUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.next
import dedun.composeapp.generated.resources.no_one_selected_yet
import dedun.composeapp.generated.resources.no_one_to_add_yet
import dedun.composeapp.generated.resources.search_for_people_and_chat_with_them_first
import dedun.composeapp.generated.resources.you_can_still_carry_on_and_invite_people
import dedun.composeapp.generated.resources.add_people_to
import dedun.composeapp.generated.resources.next_with
import dedun.composeapp.generated.resources.selected
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

/**
 * Second step of group creation: pick who is in the group.
 *
 * The list is whatever profiles are already in the local store — no directory
 * lookup happens here. Creating the group waits for the next step, where the
 * user says how it should be run.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SelectChatRoomMembersScreen(
    activeUserPublicKey: HexKey,
    name: String,
    description: String?,
    initialSelectChatRoomMembersUIState: SelectChatRoomMembersUIState = SelectChatRoomMembersUIState.Loading,
    nostrRepository: NostrRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val selectChatRoomMembersViewModel: SelectChatRoomMembersViewModel = viewModel(
        factory = SelectChatRoomMembersViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            name = name,
            description = description,
            initialSelectChatRoomMembersUIState = initialSelectChatRoomMembersUIState,
            nostrRepository = nostrRepository
        )
    )

    ScreenStateTransition(selectChatRoomMembersViewModel.selectChatRoomMembersUIState) { uiState ->
    when (val selectChatRoomMembersUIState = uiState) {
            is SelectChatRoomMembersUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = selectChatRoomMembersUIState.message,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is SelectChatRoomMembersUIState.Loaded -> {
                val selectedCount = selectChatRoomMembersViewModel.selectedPublicKeys.size

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(Res.string.add_people_to, name),
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
                                    label = if (selectedCount > 0) {
                                        stringResource(Res.string.next_with, selectedCount)
                                    } else {
                                        stringResource(Res.string.next)
                                    },
                                    icon = {
                                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next")
                                    },
                                    onClick = {
                                        selectChatRoomMembersViewModel.selectChatRoomType(
                                            onNavigateToRoute = onNavigateToRoute
                                        )
                                    }
                                )
                            },
                            actions = {
                                Text(
                                    modifier = Modifier.padding(start = MaterialTheme.spacing.space200),
                                    text = if (selectedCount > 0) {
                                        stringResource(Res.string.selected, selectedCount)
                                    } else {
                                        stringResource(Res.string.no_one_selected_yet)
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        if (selectChatRoomMembersUIState.profiles.isEmpty()) {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(MaterialTheme.spacing.space250),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                            ) {
                                Spacer(
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = stringResource(Res.string.no_one_to_add_yet),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = stringResource(Res.string.search_for_people_and_chat_with_them_first),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = stringResource(Res.string.you_can_still_carry_on_and_invite_people),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(
                                    modifier = Modifier.weight(2f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(MaterialTheme.spacing.space50),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                            ) {
                                items(
                                    items = selectChatRoomMembersUIState.profiles,
                                    key = { it.publicKey }
                                ) { profile ->
                                    val isSelected = selectChatRoomMembersViewModel.isSelected(profile.publicKey)

                                    Card(
                                        onClick = {
                                            selectChatRoomMembersViewModel.toggleMember(profile.publicKey)
                                        }
                                    ) {
                                        ListItem(
                                            leadingContent = {
                                                ProfileAvatar(
                                                    profile = profile
                                                )
                                            },
                                            headlineContent = {
                                                Text(
                                                    profile.humanReadableNameOrPubkey()
                                                )
                                            },
                                            supportingContent = {
                                                profile.about?.let {
                                                    Text(
                                                        text = it,
                                                        overflow = TextOverflow.Ellipsis,
                                                        maxLines = 1
                                                    )
                                                }
                                            },
                                            trailingContent = {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = {
                                                        selectChatRoomMembersViewModel.toggleMember(profile.publicKey)
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SelectChatRoomMembersUIState.Loading -> {
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
                        text = stringResource(Res.string.add_people_to, name),
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
        if (initialSelectChatRoomMembersUIState == SelectChatRoomMembersUIState.Loading) {
            selectChatRoomMembersViewModel.initiate()
        }
    }
}

@ConformancePreviews
@Composable
private fun SelectChatRoomMembersScreenPreview() {
    BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            SelectChatRoomMembersScreen(
                activeUserPublicKey = "",
                name = "Group discussions",
                description = "See something... say something.",
                initialSelectChatRoomMembersUIState = SelectChatRoomMembersUIState.Loaded(
                    profiles = listOf(
                        Profile(
                            publicKey = "hex",
                            displayName = "Man With A Plan",
                            about = "Making plans and getting things done...",
                            nostrEventId = "nostrEventId"
                        )
                    )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
