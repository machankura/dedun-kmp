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
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupEventProposal
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.ProposedEvent
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
import com.machankura.dedun.compose.ui.view.model.ProposeGroupEventViewModel
import com.machankura.dedun.compose.ui.view.state.ProposeGroupEventUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_post_needs_something_to_say
import dedun.composeapp.generated.resources.an_event_is_a_json_object
import dedun.composeapp.generated.resources.an_event_needs_a_kind
import dedun.composeapp.generated.resources.content_must_be_text
import dedun.composeapp.generated.resources.could_not_ask_the_group_to_sign_this_event
import dedun.composeapp.generated.resources.eg_kind_1_tags_content
import dedun.composeapp.generated.resources.kind_n
import dedun.composeapp.generated.resources.kind_n_has_nowhere_to_go_on_this_screen
import dedun.composeapp.generated.resources.paste_an_unsigned_event_for_the_group_to_sign
import dedun.composeapp.generated.resources.propose_event
import dedun.composeapp.generated.resources.tags_must_be_a_list_of_lists_of_strings
import dedun.composeapp.generated.resources.that_is_not_json
import dedun.composeapp.generated.resources.the_content_is_not_a_profile
import dedun.composeapp.generated.resources.the_group_signs_an_event_so_it_takes_a_quorum
import dedun.composeapp.generated.resources.this_group_has_no_shared_key_to_sign_an_event
import dedun.composeapp.generated.resources.unsigned_event
import dedun.composeapp.generated.resources.what_the_group_would_sign
import org.jetbrains.compose.resources.stringResource

/**
 * An unsigned event, pasted, for the group to sign.
 *
 * The typed editors each build one kind of statement; this takes any of the
 * kinds the group's screen can show, as JSON, the way another tool prints one.
 * Above the paste is what the group would be signing, read back the way the
 * signing screen will describe it, so a member sees "Nostr profile · Translation
 * collective" rather than a wall of brackets before deciding -- and sees why the group will
 * not be asked when the paste is something it could never show.
 *
 * The byline is the group's, as on the post composer and for the same reason:
 * whatever is pasted goes out in the group's name, and a paste that names some
 * other `pubkey` goes out in the group's name anyway. The explanatory line says
 * so, because a paste with a `pubkey` in it is the one case where a member could
 * reasonably expect otherwise.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProposeGroupEventScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialProposeGroupEventUIState: ProposeGroupEventUIState = ProposeGroupEventUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val proposeGroupEventViewModel: ProposeGroupEventViewModel = viewModel(
        factory = ProposeGroupEventViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialProposeGroupEventUIState = initialProposeGroupEventUIState,
            activeUserPublicKey = activeUserPublicKey,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Read out here rather than in the click handler: both are composable and an
    // onClick lambda is not. The scope is the caller's so a message survives this
    // screen being replaced by the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_this_event)

    ScreenStateTransition(proposeGroupEventViewModel.proposeGroupEventUIState) { uiState ->
        when (val proposeGroupEventUIState = uiState) {
            is ProposeGroupEventUIState.Error -> {
                // Nothing to retry: the room came from a navigation argument, and
                // reading it again with the same one fails the same way.
                ErrorState(message = proposeGroupEventUIState.message, onRetry = null)
            }

            is ProposeGroupEventUIState.Loaded -> {
                val eventFieldState = rememberTextFieldState()
                // The room and this device's share, and the identity: an npub-only
                // profile cannot send the proposal at all. docs/npub-sign-in.md.
                val canSign = proposeGroupEventUIState.canSign && LocalCanSign.current

                // Read on every change. A paste is a few kilobytes at most and the
                // reading is one JSON parse, so this is cheaper than the debounce
                // that would hide the answer for a beat after the paste.
                val json = eventFieldState.text.toString()
                val reading = remember(json) {
                    json.takeIf { it.isNotBlank() }?.let(GroupEventProposal::read)
                }

                // imePadding: this screen is a text field the size of the screen,
                // and without it the software keyboard covers what is being pasted.
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = { Text(text = stringResource(Res.string.propose_event)) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_event),
                                    icon = { Icon(Icons.Default.Draw, contentDescription = "Propose event") },
                                    enabled = canSign,
                                    onClick = {
                                        proposeGroupEventViewModel.proposeEvent(
                                            localChatRoom = proposeGroupEventUIState.localChatRoom,
                                            json = eventFieldState.text.toString(),
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back
                                                // to the group. Nothing has been
                                                // signed yet -- the event lands when
                                                // a quorum signs -- so landing on a
                                                // screen that does not show it would
                                                // read as a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            // The reason is already under the paste;
                                            // pressing the button anyway is answered
                                            // rather than ignored, and the paste
                                            // stays. Same on a failure to propose.
                                            onRefused = {},
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
                        // Whose name this goes out in, said before anything is
                        // pasted. It is the group's, whatever the paste says.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileAvatar(
                                size = MaterialTheme.spacing.space500,
                                publicKey = chatRoomId,
                                picture = proposeGroupEventUIState.groupNostrProfile?.picture(),
                                name = proposeGroupEventUIState.groupNostrProfile?.name()
                            )

                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

                            Text(
                                text = proposeGroupEventUIState.groupNostrProfile?.name()
                                    ?: proposeGroupEventUIState.localChatRoom.chatRoom.subject
                                    ?: chatRoomId.take(16),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = stringResource(
                                Res.string.paste_an_unsigned_event_for_the_group_to_sign
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(
                                Res.string.the_group_signs_an_event_so_it_takes_a_quorum
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (!proposeGroupEventUIState.canSign) {
                            Text(
                                text = stringResource(
                                    Res.string.this_group_has_no_shared_key_to_sign_an_event
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        when (reading) {
                            null -> Unit
                            is GroupEventProposal.Ready -> ProposalPreview(reading)
                            is GroupEventProposal.Refused -> RefusalNotice(reading)
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .background(BottomAppBarDefaults.containerColor),
                            state = eventFieldState,
                            // The paste grows into the screen rather than scrolling
                            // inside three lines, and is set in a monospace face:
                            // it is JSON, and a bracket that does not line up is
                            // the kind of thing a member is looking for in it.
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent
                            ),
                            label = { Text(stringResource(Res.string.unsigned_event)) },
                            placeholder = {
                                Text(
                                    text = stringResource(Res.string.eg_kind_1_tags_content),
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            isError = reading is GroupEventProposal.Refused
                        )
                    }
                }
            }

            ProposeGroupEventUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                    Text(
                        text = stringResource(Res.string.propose_event),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(fillScreen = false)
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialProposeGroupEventUIState == ProposeGroupEventUIState.Loading) {
            proposeGroupEventViewModel.initiateProposeGroupEvent()
        }
    }
}

/**
 * What the group would sign, said the way the signing screen will say it.
 *
 * `ProposedEvent.summarize` rather than a description of this screen's own, so
 * that what a member reads here before proposing is word for word what every
 * other member reads before signing. Two descriptions of one event would drift.
 */
@Composable
private fun ProposalPreview(ready: GroupEventProposal.Ready) {
    val summary = ProposedEvent.summarize(ready.preview())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            Text(
                text = stringResource(Res.string.what_the_group_would_sign),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(Res.string.kind_n, ready.kind.toString()) + " · " + summary.label,
                style = MaterialTheme.typography.titleSmall
            )

            // Capped, because a kind:1's detail is the whole post and the paste
            // under this card is the thing the screen is for.
            Text(
                text = summary.detail,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Why the group will not be asked, under the paste it is about.
 *
 * Named rather than silent: a button that does nothing for a paste it refuses is
 * indistinguishable from a missed tap.
 */
@Composable
private fun RefusalNotice(refused: GroupEventProposal.Refused) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        Text(
            text = when (refused) {
                GroupEventProposal.Refused.NotJson -> stringResource(Res.string.that_is_not_json)
                GroupEventProposal.Refused.NotAnObject -> stringResource(Res.string.an_event_is_a_json_object)
                GroupEventProposal.Refused.NoKind -> stringResource(Res.string.an_event_needs_a_kind)
                is GroupEventProposal.Refused.KindNotAccepted -> stringResource(
                    Res.string.kind_n_has_nowhere_to_go_on_this_screen,
                    refused.kind.toString(),
                    GroupEventProposal.ACCEPTED_KINDS.sorted().joinToString()
                )
                GroupEventProposal.Refused.TagsMalformed ->
                    stringResource(Res.string.tags_must_be_a_list_of_lists_of_strings)
                GroupEventProposal.Refused.ContentNotText -> stringResource(Res.string.content_must_be_text)
                GroupEventProposal.Refused.ProfileUnreadable ->
                    stringResource(Res.string.the_content_is_not_a_profile)
                GroupEventProposal.Refused.PostBlank -> stringResource(Res.string.a_post_needs_something_to_say)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@ConformancePreviews
@Composable
private fun ProposeGroupEventScreenPreview() {
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProposeGroupEventScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialProposeGroupEventUIState = ProposeGroupEventUIState.Loaded(
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
                    // A group that can sign, so the screen renders in the state a
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
