package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_reply
import dedun.composeapp.generated.resources.add_post
import dedun.composeapp.generated.resources.posted_on
import dedun.composeapp.generated.resources.posts
import dedun.composeapp.generated.resources.this_device_holds_no_posts_for_this_group
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.inComparableGroups
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.nostr.GroupPost
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.AddGroupPostRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupPostsViewModel
import com.machankura.dedun.compose.ui.view.state.GroupPostsUIState
import kotlin.time.Instant

/**
 * What the group has said publicly, as itself, newest first.
 *
 * Pushed from its row on the group's screen, which counts the posts and nothing
 * more. This is where they are read: one card per post and -- for a member
 * holding a share of the key -- the way into the composer. A device holding no
 * posts says so, and offers the composer from the same place; a post signed
 * before this member joined is not passed on (docs/group-nostr-profile-and-posts.md,
 * D8), so "holds none" is all it can say.
 *
 * A post is the one thing the group signs that is meant to be *read* by people
 * who were never in the room, so a member without a share reads it like
 * anybody else and simply cannot propose the next one: proposing is a signature
 * by the group, and `proposeSigningBatch` throws for a device holding no share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPostsScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialGroupPostsUIState: GroupPostsUIState = GroupPostsUIState.Loading,
    chatRepository: ChatRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupPostsViewModel: GroupPostsViewModel = viewModel(
        factory = GroupPostsViewModel.factory(
            chatRoomId = chatRoomId,
            initialGroupPostsUIState = initialGroupPostsUIState,
            chatRepository = chatRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupPostsUIState == GroupPostsUIState.Loading) {
            groupPostsViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.posts),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupPostsViewModel.groupPostsUIState) { uiState ->
            when (val state = uiState) {
                is GroupPostsUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument,
                    // and reading it again with the same one fails the same way.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupPostsUIState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(padding)
                            .padding(MaterialTheme.spacing.screenMargin),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                    ) {
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                        LoadingDataIndicator(fillScreen = false)
                    }
                }

                is GroupPostsUIState.Loaded -> {
                    // The room and this device's share, and the identity: an
                    // npub-only profile cannot send a proposal at all.
                    val canPropose = state.canSign && LocalCanSign.current
                    val add: () -> Unit = {
                        onNavigateToRoute.invoke(
                            AddGroupPostRoute(
                                activeUserPublicKey = activeUserPublicKey,
                                chatRoomId = chatRoomId,
                                relayHint = relayHint
                            )
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.space250),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        if (state.groupPosts.isEmpty()) {
                            item {
                                EmptyState(
                                    message = stringResource(
                                        Res.string.this_device_holds_no_posts_for_this_group
                                    ),
                                    action = if (canPropose) {
                                        { AddPostButton(onClick = add) }
                                    } else {
                                        null
                                    }
                                )
                            }
                        } else {
                            items(
                                items = state.groupPosts,
                                key = { post -> post.signedEvent.id }
                            ) { post ->
                                GroupPostCard(
                                    post = post,
                                    publicKey = chatRoomId,
                                    groupNostrProfile = state.groupNostrProfile
                                )
                            }

                            if (canPropose) {
                                item {
                                    AddPostButton(onClick = add)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One thing the group said, as the group signed it.
 *
 * The whole note rather than a preview. A post is short by construction -- kind:1
 * with no long-form arm behind it -- and truncating the one kind of content on this
 * screen that exists to be *read* would mean opening something to see a paragraph.
 *
 * **The byline is the group's**, taken from its profile where it has one and from
 * the room's own id where it has not. It is worth spelling out because a post is
 * the one thing here a reader might otherwise attribute to whichever member
 * proposed it -- and the point of signing it as the group is that they cannot.
 *
 * A reply is marked as one. Nothing in this app writes replies, so the mark is for
 * an event that arrived some other way; drawing one as an announcement would put
 * half a conversation on a group's page with no sign that it was half.
 */
@Composable
private fun GroupPostCard(
    post: GroupPost,
    publicKey: HexKey,
    groupNostrProfile: GroupNostrProfile?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(
                    size = MaterialTheme.spacing.space400,
                    publicKey = publicKey,
                    picture = groupNostrProfile?.picture(),
                    name = groupNostrProfile?.name()
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupNostrProfile?.name() ?: publicKey.take(16).inComparableGroups(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = stringResource(
                            Res.string.posted_on,
                            post.postedAt.toFormattedTimeAndDateString()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!post.isNewThread()) {
                    Text(
                        text = stringResource(Res.string.a_reply),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = post.content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** The way into `AddGroupPostScreen`. The icon is decorative because the label is beside it. */
@Composable
private fun AddPostButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Campaign, contentDescription = Decorative)

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

        Text(stringResource(Res.string.add_post))
    }
}

@ConformancePreviews
@Composable
private fun GroupPostsScreenPreview() {
    val chatRoomId = "d".repeat(64)

    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupPostsScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                initialGroupPostsUIState = GroupPostsUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = chatRoomId,
                            userPublicKey = "a".repeat(64),
                            subject = "Translation room",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        )
                    ),
                    groupPosts = listOf(
                        GroupPost(
                            signedEvent = GroupSignedEvent(
                                id = "b".repeat(64),
                                chatRoomId = chatRoomId,
                                publicKey = chatRoomId,
                                kind = TextNoteEvent.KIND,
                                tags = emptyArray(),
                                content = "The first chapter is out. Read it, and tell us where it reads wrong.",
                                signature = "f".repeat(128),
                                createdAt = Instant.fromEpochSeconds(1_700_000_000)
                            )
                        )
                    ),
                    canSign = true
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
