package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.edit_nostr_profile
import dedun.composeapp.generated.resources.no_name_on_this_profile
import dedun.composeapp.generated.resources.nostr_profile
import dedun.composeapp.generated.resources.signed_by_the_group_on
import dedun.composeapp.generated.resources.this_device_holds_no_profile_for_this_group
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.EditGroupNostrProfileRoute
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
import com.machankura.dedun.compose.ui.view.model.GroupNostrProfileViewModel
import com.machankura.dedun.compose.ui.view.state.GroupNostrProfileUIState
import kotlin.time.Instant

/**
 * What the group says about itself on nostr, as the group signed it.
 *
 * Pushed from its row on the group's screen, which names the profile and
 * nothing more. This is where it is read: the card and -- for a member holding
 * a share of the key -- the way into the editor. A device that holds no profile
 * for the group says so, and offers the editor from the same place.
 *
 * "Holds no profile", not "has none": the group's signed work is not handed to
 * a member who joined after it was signed (docs/group-nostr-profile-and-posts.md,
 * D8), so a late member's screen cannot tell a group that never described itself
 * from one that did before they came.
 *
 * The profile is worth reading either way; proposing one is a signature by the
 * group, and `proposeSigningBatch` throws for a device holding no share of the
 * key. The editor is hidden rather than disabled, the way every gated way in
 * on the group's screen is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupNostrProfileScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialGroupNostrProfileUIState: GroupNostrProfileUIState = GroupNostrProfileUIState.Loading,
    chatRepository: ChatRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupNostrProfileViewModel: GroupNostrProfileViewModel = viewModel(
        factory = GroupNostrProfileViewModel.factory(
            chatRoomId = chatRoomId,
            initialGroupNostrProfileUIState = initialGroupNostrProfileUIState,
            chatRepository = chatRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupNostrProfileUIState == GroupNostrProfileUIState.Loading) {
            groupNostrProfileViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.nostr_profile),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupNostrProfileViewModel.groupNostrProfileUIState) { uiState ->
            when (val state = uiState) {
                is GroupNostrProfileUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument,
                    // and reading it again with the same one fails the same way.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupNostrProfileUIState.Loading -> {
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

                is GroupNostrProfileUIState.Loaded -> {
                    // The room and this device's share, and the identity: an
                    // npub-only profile cannot send a proposal at all.
                    val canPropose = state.canSign && LocalCanSign.current
                    val edit: () -> Unit = {
                        onNavigateToRoute.invoke(
                            EditGroupNostrProfileRoute(
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
                        item {
                            state.groupNostrProfile?.let { groupNostrProfile ->
                                GroupNostrProfileCard(
                                    groupNostrProfile = groupNostrProfile,
                                    publicKey = chatRoomId
                                )
                            } ?: EmptyState(
                                message = stringResource(
                                    Res.string.this_device_holds_no_profile_for_this_group
                                ),
                                // The way to fill the absence, where this device
                                // could: describing the group is a signature by it.
                                action = if (canPropose) {
                                    { EditNostrProfileButton(onClick = edit) }
                                } else {
                                    null
                                }
                            )
                        }

                        if (canPropose && state.groupNostrProfile != null) {
                            item {
                                EditNostrProfileButton(onClick = edit)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the group says about itself on nostr, as the group signed it.
 *
 * A preview rather than a profile screen: the name, the picture, the nostr address
 * and the first lines of the group's own account of itself, which is what a member
 * opening the group's details came here to check. The whole thing is one signed
 * event and the proposal list is where it can be read out in full.
 *
 * **The picture and the name come from the metadata; the tint comes from the key.**
 * [publicKey] is the room's id, which is the pubkey the group signs as, so a group
 * with no picture yet still gets the colour it has everywhere else in the app.
 *
 * The signing date is here because a group's profile is standing state with no
 * other clue to its age -- and because the alternative reading, that this arrived
 * from a relay a moment ago, is exactly what has not happened. Nothing on this card
 * was published by being signed -- see `GroupNostrProfile`.
 */
@Composable
private fun GroupNostrProfileCard(
    groupNostrProfile: GroupNostrProfile,
    publicKey: HexKey
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                ProfileAvatar(
                    publicKey = publicKey,
                    picture = groupNostrProfile.picture(),
                    name = groupNostrProfile.name()
                )
            },
            headlineContent = {
                Text(
                    // Named rather than falling back to the pubkey: the signing key
                    // row on the group's screen already shows that, and showing it
                    // twice would say the group has a name when it has not.
                    text = groupNostrProfile.name()
                        ?: stringResource(Res.string.no_name_on_this_profile),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    groupNostrProfile.nip05()?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    groupNostrProfile.about()?.let {
                        Text(
                            text = it,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = stringResource(
                            Res.string.signed_by_the_group_on,
                            groupNostrProfile.signedAt.toFormattedTimeAndDateString()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/** The way into `EditGroupNostrProfileScreen`. The icon is decorative because the label is beside it. */
@Composable
private fun EditNostrProfileButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Badge, contentDescription = Decorative)

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

        Text(stringResource(Res.string.edit_nostr_profile))
    }
}

@ConformancePreviews
@Composable
private fun GroupNostrProfileScreenPreview() {
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupNostrProfileScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = "d".repeat(64),
                relayHint = null,
                initialGroupNostrProfileUIState = GroupNostrProfileUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "d".repeat(64),
                            userPublicKey = "a".repeat(64),
                            subject = "Translation room",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        )
                    ),
                    // Built directly rather than read out of a signed event: the
                    // reading checks a signature, and there is no real one here.
                    groupNostrProfile = GroupNostrProfile(
                        signedEvent = GroupSignedEvent(
                            id = "e".repeat(64),
                            chatRoomId = "d".repeat(64),
                            publicKey = "d".repeat(64),
                            kind = MetadataEvent.KIND,
                            tags = emptyArray(),
                            content = "",
                            signature = "f".repeat(128),
                            createdAt = Instant.fromEpochSeconds(1_700_000_000)
                        ),
                        metadata = UserMetadata().apply {
                            displayName = "Translation collective"
                            about = "A group translating hard books into Sesotho."
                            nip05 = "group@example.com"
                        }
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
