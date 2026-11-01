package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.detail.MetadataEventDetail
import com.machankura.dedun.compose.ui.composable.widgets.detail.TextNoteEventDetail
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.NostrEventDetailViewModel
import com.machankura.dedun.compose.ui.view.state.NostrEventDetailUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.functionality_coming_soon
import dedun.composeapp.generated.resources.post
import dedun.composeapp.generated.resources.post_functionality_coming_soon
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.we_couldn_t_find_your_nostr_event_please_try
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrEventDetailScreen(
    initialNostrEventDetailUIState: NostrEventDetailUIState,
    activeUserPublicKey: HexKey,
    nostrEventId: HexKey,
    nostrRepository: NostrRepository,
    onNavigateBack: () -> Unit,
    onNavigateToEvent: (Route) -> Unit,
    onNavigateToDirectMessage: (Route) -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToWriteAReply: (String) -> Unit,
    onNavigateToQuoteNostrEvent: (String) -> Unit,
) {
    val nostrEventDetailViewModel: NostrEventDetailViewModel = viewModel(
        factory = NostrEventDetailViewModel.factory(
            nostrEventId = nostrEventId,
            initialNostrEventDetailUIState = initialNostrEventDetailUIState,
            nostrRepository = nostrRepository,
            activeUserPublicKey = activeUserPublicKey
        ),
    )

    ScreenStateTransition(nostrEventDetailViewModel.nostrEventDetailUIState) { uiState ->
    when (val feedListUIState = uiState) {
            NostrEventDetailUIState.Error -> {
                ErrorState()
            }
            is NostrEventDetailUIState.Loaded -> {

                when(feedListUIState.localNostrEvent.nostrEvent.kind) {
                    MetadataEvent.KIND -> {
                        MetadataEventDetail(
                            activeUserPublicKey = activeUserPublicKey,
                            localNostrEvent = feedListUIState.localNostrEvent,
                            onNavigateBack = onNavigateBack,
                            onNavigateToEvent = onNavigateToEvent,
                            onNavigateToChatRoom = onNavigateToDirectMessage,
                            onNavigateToEditProfile = onNavigateToEditProfile,
                            nostrRepository = nostrRepository
                        )
                    }
                    TextNoteEvent.KIND -> {
                        TextNoteEventDetail(
                            feedListUIState.localNostrEvent,
                            onNavigateBack = onNavigateBack,
                            onNavigateToWriteAReply = onNavigateToWriteAReply,
                            onNavigateToNostrEvent = onNavigateToQuoteNostrEvent,
                            nostrRepository = nostrRepository
                        )
                    }
                    // The two kinds with a detail of their own draw their bar inside it. The
                    // rest are a placeholder, and a placeholder pushed onto the stack still
                    // needs the way off it.
                    RepostEvent.KIND -> {
                        // TODO: RePost...
                        Scaffold(
                            snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                            topBar = {
                                TopAppBar(
                                    title = { Text(stringResource(Res.string.post)) },
                                    navigationIcon = { NavigateBackButton(onNavigateBack) },
                                )
                            },
                        ) { innerPadding ->
                            Column(
                                modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(feedListUIState.localNostrEvent.nostrEvent.content)

                                Text(stringResource(Res.string.post_functionality_coming_soon))
                            }
                        }
                    }
                    else -> {
                        Scaffold(
                            snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                            topBar = {
                                TopAppBar(
                                    title = { Text(stringResource(Res.string.post)) },
                                    navigationIcon = { NavigateBackButton(onNavigateBack) },
                                )
                            },
                        ) { innerPadding ->
                            Column(
                                modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(feedListUIState.localNostrEvent.nostrEvent.content)

                                    Text(stringResource(Res.string.functionality_coming_soon))
                                }
                            }
                        }
                    }
                }
            }
            NostrEventDetailUIState.Loading -> {
                LoadingDataIndicator()

                LaunchedEffect(true) {
                    nostrEventDetailViewModel.loadNostrFeed()
                }
            }
            NostrEventDetailUIState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(Res.string.we_couldn_t_find_your_nostr_event_please_try))
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun NostrEventDetailScreenPreview() {
    DedunTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            NostrEventDetailScreen(
                initialNostrEventDetailUIState = NostrEventDetailUIState.Loaded(
                    localNostrEvent = LocalNostrEvent(
                        nostrEvent = NostrEvent(
                            id = "eventId",
                            pubKey = "publicKey",
                            content = """Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
                                
Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. 
                                
It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.""".trimIndent(),
                            tags = emptyArray(),
                            sig = "",
                            kind = TextNoteEvent.KIND
                        ),
                        profile = Profile(
                            publicKey = "pubKey",
                            displayName = "John Doe",
                            nostrEventId = "nostrEventId"
                        )
                    )
                ),
                nostrEventId = "",
                activeUserPublicKey = "",
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                onNavigateBack = {},
                onNavigateToEvent = {},
                onNavigateToWriteAReply = {},
                onNavigateToEditProfile = {},
                onNavigateToQuoteNostrEvent = {},
                onNavigateToDirectMessage = {}
            )
        }
    }
}