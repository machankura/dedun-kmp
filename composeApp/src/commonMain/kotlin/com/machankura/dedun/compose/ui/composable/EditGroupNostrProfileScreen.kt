package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
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
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.EditGroupNostrProfileViewModel
import com.machankura.dedun.compose.ui.view.state.EditGroupNostrProfileUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.about
import dedun.composeapp.generated.resources.could_not_ask_the_group_to_sign_this_profile
import dedun.composeapp.generated.resources.edit_nostr_profile
import dedun.composeapp.generated.resources.eg_a_group_translating_hard_books
import dedun.composeapp.generated.resources.eg_group_example_com
import dedun.composeapp.generated.resources.eg_https_example_com
import dedun.composeapp.generated.resources.eg_https_example_com_group_png
import dedun.composeapp.generated.resources.eg_translation_collective
import dedun.composeapp.generated.resources.name
import dedun.composeapp.generated.resources.nostr_address
import dedun.composeapp.generated.resources.picture_url
import dedun.composeapp.generated.resources.propose_nostr_profile
import dedun.composeapp.generated.resources.the_group_signs_its_profile_so_it_takes_a_quorum
import dedun.composeapp.generated.resources.this_group_has_no_shared_key_to_sign_a_profile
import dedun.composeapp.generated.resources.website
import dedun.composeapp.generated.resources.what_the_group_says_about_itself_on_nostr
import org.jetbrains.compose.resources.stringResource

/**
 * The form for what a group says about itself on nostr.
 *
 * A group's kind:0 is signed by the group, so pressing the button proposes rather
 * than saves: the form goes out as one event for the room's quorum to put its key
 * to, and the screen hands over to the signing session it opened. Nothing about the
 * profile has changed by the time this screen closes.
 *
 * The fields start at what the group already says, so an edit is an edit. And they
 * are sent as they stand -- **an emptied field clears what the group had said**,
 * which is the only way to unsay something a group has signed. Fields this form
 * does not offer are carried across untouched; see `GroupNostrProfile.template`.
 *
 * There is no lightning address on it. Dedun keeps no wallet, and offering a payment
 * address on the group's behalf would invite payments it cannot route or see; one
 * already on the group's profile is kept through an edit. D6 of
 * docs/group-nostr-profile-and-posts.md.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditGroupNostrProfileScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialEditGroupNostrProfileUIState: EditGroupNostrProfileUIState =
        EditGroupNostrProfileUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val editGroupNostrProfileViewModel: EditGroupNostrProfileViewModel = viewModel(
        factory = EditGroupNostrProfileViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialEditGroupNostrProfileUIState = initialEditGroupNostrProfileUIState,
            activeUserPublicKey = activeUserPublicKey,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Read out here rather than in the click handler: both are composable and an
    // onClick lambda is not. The scope is the caller's so the message survives this
    // screen being replaced by the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_this_profile)

    ScreenStateTransition(editGroupNostrProfileViewModel.editGroupNostrProfileUIState) { uiState ->
        when (val editGroupNostrProfileUIState = uiState) {
            is EditGroupNostrProfileUIState.Error -> {
                // Nothing to retry: the room came from a navigation argument, and
                // reading it again with the same one fails the same way.
                ErrorState(
                    message = editGroupNostrProfileUIState.message,
                    onRetry = null
                )
            }

            is EditGroupNostrProfileUIState.Loaded -> {
                val profile = editGroupNostrProfileUIState.groupNostrProfile

                // Seeded from the profile the group already signed, so the form is
                // an edit of it. `rememberTextFieldState` saves what is typed, which
                // is what survives a rotation with the edits intact.
                val nameFieldState = rememberTextFieldState(profile?.name() ?: "")
                val aboutFieldState = rememberTextFieldState(profile?.about() ?: "")
                val pictureFieldState = rememberTextFieldState(profile?.picture() ?: "")
                val websiteFieldState = rememberTextFieldState(profile?.website() ?: "")
                val nip05FieldState = rememberTextFieldState(profile?.nip05() ?: "")

                // The room and this device's share, and the identity: an npub-only
                // profile cannot send the proposal at all. docs/npub-sign-in.md.
                val canSign = editGroupNostrProfileUIState.canSign && LocalCanSign.current

                // imePadding: this screen is nothing but text fields, and without it
                // the software keyboard covers whichever one is being typed into.
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                editGroupNostrProfileUIState.localChatRoom
                                    .RenderChatRoomTitleText()
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_nostr_profile),
                                    icon = { Icon(Icons.Default.Draw, contentDescription = "Propose nostr profile") },
                                    enabled = canSign,
                                    onClick = {
                                        editGroupNostrProfileViewModel.proposeNostrProfile(
                                            localChatRoom =
                                                editGroupNostrProfileUIState.localChatRoom,
                                            groupNostrProfile = profile,
                                            nameField = nameFieldState,
                                            aboutField = aboutFieldState,
                                            pictureField = pictureFieldState,
                                            websiteField = websiteFieldState,
                                            nip05Field = nip05FieldState,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back
                                                // to the group. The profile has not
                                                // changed yet -- it changes when a
                                                // quorum signs -- so landing on a
                                                // group still showing the old one
                                                // would read as a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            // Stay on the form with what was typed
                                            // still in it. The proposal is what
                                            // failed, and every word of it is worth
                                            // keeping for the retry.
                                            onFailure = { notify(couldNotPropose) }
                                        )
                                    }
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(Res.string.what_the_group_says_about_itself_on_nostr))

                        Text(
                            text = stringResource(
                                Res.string.the_group_signs_its_profile_so_it_takes_a_quorum
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (!editGroupNostrProfileUIState.canSign) {
                            Text(
                                text = stringResource(
                                    Res.string.this_group_has_no_shared_key_to_sign_a_profile
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        NostrProfileField(
                            state = nameFieldState,
                            icon = Icons.Default.Title,
                            iconDescription = "Name of the group",
                            label = stringResource(Res.string.name),
                            placeholder = stringResource(Res.string.eg_translation_collective)
                        )

                        NostrProfileField(
                            state = aboutFieldState,
                            icon = Icons.AutoMirrored.Filled.Notes,
                            iconDescription = "What the group is for",
                            label = stringResource(Res.string.about),
                            placeholder = stringResource(
                                Res.string.eg_a_group_translating_hard_books
                            ),
                            // The one field with prose in it, and the only one a
                            // single line would truncate while it was being typed.
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3)
                        )

                        NostrProfileField(
                            state = pictureFieldState,
                            icon = Icons.Default.Image,
                            iconDescription = "Picture of the group",
                            label = stringResource(Res.string.picture_url),
                            placeholder = stringResource(Res.string.eg_https_example_com_group_png)
                        )

                        NostrProfileField(
                            state = websiteFieldState,
                            icon = Icons.Default.Link,
                            iconDescription = "Website of the group",
                            label = stringResource(Res.string.website),
                            placeholder = stringResource(Res.string.eg_https_example_com)
                        )

                        NostrProfileField(
                            state = nip05FieldState,
                            icon = Icons.Default.AlternateEmail,
                            iconDescription = "Nostr address of the group",
                            label = stringResource(Res.string.nostr_address),
                            placeholder = stringResource(Res.string.eg_group_example_com)
                        )
                    }
                }
            }

            EditGroupNostrProfileUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                    Text(
                        text = stringResource(Res.string.edit_nostr_profile),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(fillScreen = false)
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialEditGroupNostrProfileUIState == EditGroupNostrProfileUIState.Loading) {
            editGroupNostrProfileViewModel.initiateEditGroupNostrProfile()
        }
    }
}

/**
 * One field of the profile, in the shape the app's other forms use.
 *
 * Five of these on one screen is what makes it worth a function: the borderless
 * outlined field over the bottom bar's tint is four lines of colours apiece, and
 * five copies of that is where a divergence hides.
 */
@Composable
private fun NostrProfileField(
    state: TextFieldState,
    icon: ImageVector,
    iconDescription: String,
    label: String,
    placeholder: String,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().background(BottomAppBarDefaults.containerColor),
        state = state,
        lineLimits = lineLimits,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent
        ),
        leadingIcon = {
            Icon(icon, contentDescription = iconDescription)
        },
        label = {
            Text(text = label)
        },
        placeholder = {
            Text(text = placeholder)
        }
    )
}

@ConformancePreviews
@Composable
private fun EditGroupNostrProfileScreenPreview() {
    DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            EditGroupNostrProfileScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialEditGroupNostrProfileUIState = EditGroupNostrProfileUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "publicKey",
                            userPublicKey = "",
                            subject = "Translation collective",
                            description = "A group translating hard books.",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        ),
                    ),
                    // A group that can sign, so the form renders in the state a
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
