package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.AddGroupPostViewModel
import com.machankura.dedun.compose.ui.view.state.AddGroupPostUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_post_needs_something_to_say
import dedun.composeapp.generated.resources.add_post
import dedun.composeapp.generated.resources.could_not_ask_the_group_to_sign_this_post
import dedun.composeapp.generated.resources.propose_post
import dedun.composeapp.generated.resources.the_group_signs_a_post_so_it_takes_a_quorum
import dedun.composeapp.generated.resources.this_group_has_no_shared_key_to_sign_a_post
import dedun.composeapp.generated.resources.what_should_the_group_say
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.title

/**
 * The draft of something the group would say, in its own name.
 *
 * Not a message in the room: this is signed by the group's key and read by anybody,
 * which is why the byline over the draft is the group's rather than the writer's.
 * Whoever types it is proposing words for the group to put its signature to, and
 * the words become a post -- attributed to the group and to nobody in particular --
 * only once a quorum has signed.
 *
 * The byline is the whole of the design here. Every other composer in this app puts
 * the author's name over the draft because the author is the person typing; getting
 * that wrong on this screen would have a member writing what looks like their own
 * note and finding the group had said it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddGroupPostScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialAddGroupPostUIState: AddGroupPostUIState = AddGroupPostUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val addGroupPostViewModel: AddGroupPostViewModel = viewModel(
        factory = AddGroupPostViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialAddGroupPostUIState = initialAddGroupPostUIState,
            activeUserPublicKey = activeUserPublicKey,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Read out here rather than in the click handler: both are composable and an
    // onClick lambda is not. The scope is the caller's so a message survives this
    // screen being replaced by the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_this_post)
    val nothingToSay = stringResource(Res.string.a_post_needs_something_to_say)

    ScreenStateTransition(addGroupPostViewModel.addGroupPostUIState) { uiState ->
        when (val addGroupPostUIState = uiState) {
            is AddGroupPostUIState.Error -> {
                // Nothing to retry: the room came from a navigation argument, and
                // reading it again with the same one fails the same way.
                ErrorState(message = addGroupPostUIState.message, onRetry = null)
            }

            is AddGroupPostUIState.Loaded -> {
                val noteFieldState = rememberTextFieldState()
                // The room and this device's share, and the identity: an npub-only
                // profile cannot send the proposal at all. docs/npub-sign-in.md.
                val canSign = addGroupPostUIState.canSign && LocalCanSign.current

                // imePadding: this screen is a text field the size of the screen,
                // and without it the software keyboard covers what is being typed.
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = { Text(text = stringResource(Res.string.add_post)) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_post),
                                    icon = { Icon(Icons.Default.Draw, contentDescription = "Propose post") },
                                    enabled = canSign,
                                    onClick = {
                                        addGroupPostViewModel.proposePost(
                                            localChatRoom = addGroupPostUIState.localChatRoom,
                                            noteField = noteFieldState,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back
                                                // to the group. Nothing has been
                                                // posted yet -- the post appears
                                                // when a quorum signs -- so landing
                                                // on the list it is not in would
                                                // read as a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            // Both stay on the draft. One is an
                                            // empty note and one is a failure to
                                            // propose, and neither is a reason to
                                            // throw away what was written.
                                            onEmpty = { notify(nothingToSay) },
                                            onFailure = { notify(couldNotPropose) }
                                        )
                                    }
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Whose name this goes out in, said before a word is typed.
                        // It is the group's, and it is the one thing about this
                        // screen a member could reasonably get wrong.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileAvatar(
                                size = MaterialTheme.spacing.space500,
                                publicKey = chatRoomId,
                                picture = addGroupPostUIState.groupNostrProfile?.picture(),
                                name = addGroupPostUIState.groupNostrProfile?.name()
                            )

                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

                            Text(
                                text = addGroupPostUIState.groupNostrProfile?.name()
                                    ?: addGroupPostUIState.localChatRoom.chatRoom.title
                                    ?: chatRoomId.take(16),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = stringResource(
                                Res.string.the_group_signs_a_post_so_it_takes_a_quorum
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (!addGroupPostUIState.canSign) {
                            Text(
                                text = stringResource(
                                    Res.string.this_group_has_no_shared_key_to_sign_a_post
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .background(BottomAppBarDefaults.containerColor),
                            state = noteFieldState,
                            // The draft grows into the screen rather than scrolling
                            // inside three lines: a post is the one thing here
                            // somebody writes at length, and a composer that hides
                            // what was written two sentences ago is a composer
                            // nobody proofreads in.
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent
                            ),
                            placeholder = {
                                Text(stringResource(Res.string.what_should_the_group_say))
                            }
                        )
                    }
                }
            }

            AddGroupPostUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                    Text(
                        text = stringResource(Res.string.add_post),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(fillScreen = false)
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialAddGroupPostUIState == AddGroupPostUIState.Loading) {
            addGroupPostViewModel.initiateAddGroupPost()
        }
    }
}

@ConformancePreviews
@Composable
private fun AddGroupPostScreenPreview() {
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AddGroupPostScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialAddGroupPostUIState = AddGroupPostUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "publicKey",
                            userPublicKey = "",
                            subject = "Translation room",
                            description = "A group translating hard books.",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        ),
                    ),
                    // A group that can sign, so the composer renders in the state a
                    // member actually meets it in rather than greyed out.
                    canSign = true
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {},
                onNavigateBack = {},
            )
        }
    }
}
