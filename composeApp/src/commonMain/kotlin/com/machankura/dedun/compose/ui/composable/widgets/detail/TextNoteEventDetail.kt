package com.machankura.dedun.compose.ui.composable.widgets.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.be_the_first_to_comment
import dedun.composeapp.generated.resources.post
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.reply_to
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextNoteEventDetail(
    localNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent,
    onNavigateBack: () -> Unit,
    nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
    onNavigateToWriteAReply: (String) -> Unit,
    onNavigateToNostrEvent: (HexKey) -> Unit,
) {
    val scrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()

    val inReplyToNostrEventFeedListViewModel: com.machankura.dedun.compose.ui.view.model.InReplyToViewModel = viewModel(
        key = "replies-${localNostrEvent.nostrEvent.id}",
        factory = com.machankura.dedun.compose.ui.view.model.InReplyToViewModel.factory(
            nostrEvent = localNostrEvent.nostrEvent,
            profile = localNostrEvent.profile,
            initialFeedListUIState = com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loading,
            nostrRepository = nostrRepository,
            createdAt = localNostrEvent.nostrEvent.createdAt
        ),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = { NavigateBackButton(onNavigateBack) },
                title = {
                    Text(stringResource(Res.string.post))
                },
            )
        },
        bottomBar = {
            FlexibleBottomAppBar(
                containerColor = Color.Transparent,
                contentPadding = PaddingValues(
                    start = MaterialTheme.spacing.space125,
                    end = MaterialTheme.spacing.space125
                ),
                scrollBehavior = scrollBehavior
            ) {

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable {
                            onNavigateToWriteAReply.invoke(
                                localNostrEvent.nostrEvent.id
                            )
                        },
                    // The one hand-written corner that was off the shape scale, at 30dp
                    // against extraLarge's 28. Two units, invisible on its own and exactly
                    // the drift a scale exists to stop.
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(MaterialTheme.spacing.space125).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                    ) {
                        // TODO: Load user profile...
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "User profile",
                            tint = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromPublicKey("profile")
                        )
//                                    ProfileAvatar(
//                                        profile = localProfile.profile,
//                                        size = 35.dp
//                                    )

                        Text(
                            modifier = Modifier.weight(1f),
                            text = stringResource(Res.string.reply_to, localNostrEvent.profile?.humanReadableNameOrPubkey() ?: "the above"),
                            maxLines = 1,
                        )

                        Icon(
                            Icons.Default.ChatBubble,
                            contentDescription = "User profile",
                        )
                    }

                }
            }
//            HorizontalFloatingToolbar(
//                expanded = true,
//
//            ) {
//
//                Row(
//                    modifier = Modifier
//                        .padding(10.dp)
//                        .fillMaxWidth().clickable {
//                            onNavigateToWriteAReply.invoke(
//                                localNostrEvent.nostrEvent.id
//                            )
//                        },
//                    horizontalArrangement = Arrangement.spacedBy(10.dp),
//                ) {
//                    // TODO: Load user profile...
//                    Icon(
//                        Icons.Default.Person,
//                        contentDescription = "User profile",
//                        tint = ProfileColor.fromPublicKey("profile")
//                    )
////                                    ProfileAvatar(
////                                        profile = localProfile.profile,
////                                        size = 35.dp
////                                    )
//
//                    Text(
//                        modifier = Modifier.weight(1f),
//                        text = "Reply to ${localNostrEvent.profile?.humanReadableNameOrPubkey() ?: "the above"}",
//                        maxLines = 1,
//                    )
//
//                    Icon(
//                        Icons.Default.ChatBubble,
//                        contentDescription = "User profile",
//                    )
//                }
//            }

        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
        ) {

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // TODO: get all the notes inReplyToRoot before this note
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space150),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val profile = localNostrEvent.profile

                            com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar(
                                profile = localNostrEvent.profile,
                                publicKey = localNostrEvent.nostrEvent.pubKey
                            )

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = profile?.displayName ?: profile?.userName ?: localNostrEvent.nostrEvent.pubKey,
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                profile?.staticIdentifier()?.let { staticIdentifier ->
                                    Text(
                                        text = staticIdentifier,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromProfile(
                                            profile
                                        )
                                    )
                                }
                            }


//                            Button(
//                                onClick = {
//
//                                }
//                            ) {
//                                Text(
//                                    "Follow"
//                                )
//                            }

                            IconButton(
                                onClick = {
                                    // TODO: Show dropdown of more actions...
                                }
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                        }

                        com.machankura.dedun.compose.ui.composable.widgets.content.RichContent(
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = MaterialTheme.spacing.space250
                            ),
                            nostrEvent = localNostrEvent.nostrEvent,
                            localQuotedNostrEvent = localNostrEvent.localQuotedNostrEvent,
                            mentionedProfiles = localNostrEvent.mentionedProfiles,
                            onNavigateToEvent = onNavigateToNostrEvent
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Text(
                                text = localNostrEvent.nostrEvent.createdAt.toFormattedTimeAndDateString()
                            )
                        }

                        HorizontalDivider()

                        BottomAppBar(
                            actions = {


                                IconButton(
                                    onClick = {

                                    }
                                ) {
                                    Icon(
                                        Icons.Default.AddReaction,
                                        contentDescription = "Add reaction"
                                    )
                                }

                                IconButton(
                                    onClick = {

                                    }
                                ) {
                                    Icon(
                                        Icons.Default.CurrencyBitcoin,
                                        contentDescription = "Tip"
                                    )
                                }


                                IconButton(
                                    onClick = {
                                        onNavigateToNostrEvent.invoke(
                                            localNostrEvent.nostrEvent.id
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Repeat,
                                        contentDescription = "Repost"
                                    )
                                }
                            },
//                            floatingActionButton = {
//                                ExtendedFloatingActionButton(
//                                    onClick = {
//                                        onNavigateToWriteAReply.invoke(
//                                            localNostrEvent.nostrEvent.id
//                                        )
//                                    },
//                                    icon = {
//                                        Icon(
//                                            Icons.Default.ChatBubble,
//                                            contentDescription = "Reply"
//                                        )
//                                    },
//                                    text = {
//                                        Text("Reply")
//                                    }
//                                )
//                            },
                            windowInsets = WindowInsets.navigationBars
                        )


                    }
                }

                when (val inReplyToFeedListUIState = inReplyToNostrEventFeedListViewModel.inReplyToFeedListUIState) {
                    is com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loaded -> {
                        if (inReplyToFeedListUIState.localNostrEvents.isEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.be_the_first_to_comment)
                                )
                            }
                        } else {
                            items(
                                items = inReplyToFeedListUIState.localNostrEvents,
                                key = { e -> "reply-${e.nostrEvent.id}" }
                            ) { localNostrEvent ->
                                localNostrEvent.RenderNotePreview(
                                    onNavigateToEvent = onNavigateToNostrEvent
                                )
                            }
                        }
                    }
                    com.machankura.dedun.compose.ui.view.state.FeedListUIState.Error -> {
                        item { ErrorState() }
                    }
                    com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loading -> {
                        item {
                            LoadingIndicator()
                        }
                    }
                }
            }

            LaunchedEffect(true) {
                inReplyToNostrEventFeedListViewModel.initiate()
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun TextNoteEventDetailPreview() {

    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            TextNoteEventDetail(
                localNostrEvent = com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent(
                    nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                        id = "eventId",
                        pubKey = "publicKey",
                        content = """Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
                                
Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. 
                                
It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.""".trimIndent(),
                        tags = emptyArray(),
                        sig = "",
                        kind = TextNoteEvent.KIND
                    ),
                    profile = com.machankura.dedun.compose.database.model.Profile(
                        publicKey = "pubKey",
                        displayName = "John Doe",
                        nip05 = "john@doe.me",
                        nostrEventId = "nostrEventId"
                    )
                ),
                onNavigateBack = {},
                onNavigateToWriteAReply = {},
                onNavigateToNostrEvent = {},
                nostrRepository = com.machankura.dedun.compose.repository.NostrRepository.NO_OP_NOSTR_REPOSITORY
            )
        }
    }
}