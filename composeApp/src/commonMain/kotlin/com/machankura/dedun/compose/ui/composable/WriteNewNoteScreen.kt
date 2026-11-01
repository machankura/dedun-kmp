package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.time.Clock
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.new_note
import bennu.composeapp.generated.resources.next
import bennu.composeapp.generated.resources.something_went_wrong_and_we_were_unable_to_3
import bennu.composeapp.generated.resources.the_above_will_be_your_new_note
import bennu.composeapp.generated.resources.transmit_note
import bennu.composeapp.generated.resources.type_out_what_you_would_like_to_publish
import bennu.composeapp.generated.resources.what_s_your_comment_on_the_below
import bennu.composeapp.generated.resources.what_s_your_reply_to_the_above
import bennu.composeapp.generated.resources.what_vibrations_do_you_want_to_send_out
import bennu.composeapp.generated.resources.replying_to
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WriteNewNoteScreen(
    activeUserPublicKey: HexKey,
    initialWriteNewNoteUIState: com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState = com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.Loading,
    replyToNostrEventId: HexKey?,
    quotedNostrEventId: HexKey?,
    onNostrEventPublished: () -> Unit,
    onNavigateToNostrEvent: (HexKey) -> Unit,
    onNavigateBack: () -> Unit,
    nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
) {
    val writeNewNoteViewModel: com.machankura.dedun.compose.ui.view.model.WriteNewNoteViewModel =  viewModel (
        factory = com.machankura.dedun.compose.ui.view.model.WriteNewNoteViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            replyToNostrEventId = replyToNostrEventId,
            quotedNostrEventId = quotedNostrEventId,
            initialWriteNewNoteUIState,
            nostrRepository
        )
    )
    // imePadding: this screen has a text field, and without it the software
    // keyboard covers whatever is being typed into.
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        modifier = Modifier.imePadding(),
        topBar = {
            // "New note" whether it is a reply, a quote or neither: the body says which
            // above the field. Back pops the route and drops the draft, which is what
            // the platform's own back does on this screen already.
            TopAppBar(
                title = { Text(stringResource(Res.string.new_note)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
            .readableContent()
        ) {

            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    MaterialTheme.spacing.space125
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {

                when (val writeNewNoteUIState = writeNewNoteViewModel.writeNewNoteUIState) {
                    is com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.Loading -> {
                        com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator()
                    }
                    is com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.InputPrompt -> {

                        val listState = rememberLazyListState()

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f)
                        ) {
                            // TODO: Stick Header with post button...

                            item {
                                if (writeNewNoteUIState.inReplyToNostrEvent?.nostrEvent?.kind == TextNoteEvent.KIND) {
                                    writeNewNoteUIState.inReplyToNostrEvent.RenderNotePreview(
                                        onNavigateToEvent = onNavigateToNostrEvent
                                    )
                                } else {
                                    // Nothing to show, but the LazyColumn item has to
                                    // render something. 1dp was standing in for zero.
                                    Spacer(
                                        modifier = Modifier.height(MaterialTheme.spacing.space0)
                                    )
                                }
                            }

                            item {
                                Column(
                                    modifier = Modifier.fillParentMaxHeight()
                                ) {
                                    if (writeNewNoteUIState.inReplyToNostrEvent != null) {
                                        Text(
                                            modifier = Modifier.padding(
                                                start = MaterialTheme.spacing.space600,
                                                bottom = MaterialTheme.spacing.space125,

                                            ),
                                            text = stringResource(Res.string.replying_to, writeNewNoteUIState.inReplyToNostrEvent.profile?.humanReadableNameOrPubkey() ?: "note"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }



                                    TextField(
                                        modifier = Modifier.fillMaxWidth(),
                                        state = writeNewNoteViewModel.writeNewNoteFormState.textFieldForm.textFieldState,
                                        isError = writeNewNoteViewModel.writeNewNoteFormState.textFieldForm.errorMessage.value != null,
                                        supportingText = writeNewNoteViewModel.writeNewNoteFormState.textFieldForm.errorMessage.value?.let {
                                            {
                                                Text(
                                                    text = it,
                                                    modifier = Modifier.padding(
                                                        bottom = MaterialTheme.spacing.compactPadding
                                                    )
                                                )
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Text,
                                        ),
                                        label = {
                                            Text(
                                                text = if (writeNewNoteUIState.inReplyToNostrEvent != null) {
                                                    stringResource(Res.string.what_s_your_reply_to_the_above)
                                                } else if (writeNewNoteUIState.quotedNostrEvent != null) {
                                                    stringResource(Res.string.what_s_your_comment_on_the_below)
                                                } else {
                                                    stringResource(Res.string.what_vibrations_do_you_want_to_send_out)
                                                },
                                                maxLines = 1,
                                            )
                                        },
                                        placeholder = {
                                            Text(
                                                text = stringResource(Res.string.type_out_what_you_would_like_to_publish),
                                                maxLines = 1,
                                            )
                                        },
                                        leadingIcon = {
                                            com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar(
                                                profile = writeNewNoteUIState.localProfile.profile,
                                                size = 35.dp
                                            )
                                        },
                                        inputTransformation = InputTransformation {
                                            if (changes.changeCount > 0) {
                                                val range = changes.getRange(changes.changeCount-1)
                                                val newInput = asCharSequence().substring(range.start, range.end)

                                                if (newInput == "@") {
                                                    writeNewNoteViewModel.atSymbolRange.value = range
                                                }
                                            }
                                        },
                                        outputTransformation = OutputTransformation {
                                            val content = originalText.toString()
                                            val nostrProfiles = com.machankura.dedun.compose.ui.composable.widgets.content.ContentParser.parse(content.trimEnd('\n', '\r'), emptyMap(), emptyMap(), trimBlankLines = true)
                                                .filterIsInstance<com.machankura.dedun.compose.ui.composable.widgets.content.ContentSegment.NostrProfileSegment>()
                                                // TODO: We might want to order these profiles by the startIndex so that we can replace the better

                                            if (writeNewNoteViewModel.mentionedProfiles.isNotEmpty()) {
                                                for (nostrProfile in nostrProfiles) {
                                                    writeNewNoteViewModel.mentionedProfiles.find { mentionedProfile -> mentionedProfile.publicKey == nostrProfile.pubkey }?.let { mentionedProfile ->
                                                        val profileMention = "@${mentionedProfile.humanReadableNameOrPubkey()}"

                                                        replace(
                                                            start = nostrProfile.startIndex,
                                                            end = nostrProfile.endIndex + 1,
                                                            text = profileMention
                                                        )

                                                        addStyle(
                                                            SpanStyle(
                                                                color = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromPublicKey(mentionedProfile.publicKey)
                                                            ),
                                                            nostrProfile.startIndex,
                                                            nostrProfile.startIndex + profileMention.length
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )

                                    // TODO: Dropdown for the mentioned users

                                    DropdownMenu(
                                        expanded = writeNewNoteViewModel.atSymbolRange.value != null,
                                        onDismissRequest = {
                                            writeNewNoteViewModel.atSymbolRange.value = null
                                        }
                                    ) {
                                        writeNewNoteViewModel.mentionableProfiles.forEach { profile ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = profile.humanReadableNameOrPubkey()
                                                    )
                                                },
                                                leadingIcon = {
                                                    com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar(
                                                        profile = profile
                                                    )
                                                },
                                                onClick = {
                                                    writeNewNoteViewModel.addProfileToMentionedProfiles(profile)
                                                    writeNewNoteViewModel.atSymbolRange.value = null
                                                }
                                            )
                                        }
                                    }
                                    if (writeNewNoteUIState.quotedNostrEvent?.nostrEvent?.kind == TextNoteEvent.KIND) {
                                        Card(
                                            modifier = Modifier.padding(
                                                start = MaterialTheme.spacing.space600,
                                                top = MaterialTheme.spacing.space125
                                            )
                                        ) {
                                            writeNewNoteUIState.quotedNostrEvent.RenderNotePreview(
                                                onNavigateToEvent = onNavigateToNostrEvent
                                            )
                                        }
                                    }
                                }


                            }
                        }

                        if (writeNewNoteUIState.inReplyToNostrEvent != null) {
                            LaunchedEffect(writeNewNoteUIState) {
                                listState.scrollToItem(1)
                            }
                        }


                        Button(
                            onClick = {
                                writeNewNoteViewModel.confirmNote(
                                    localProfile = writeNewNoteUIState.localProfile,
                                    inReplyToNostrEvent = writeNewNoteUIState.inReplyToNostrEvent,
                                    quotedNostrEvent = writeNewNoteUIState.quotedNostrEvent
                                )
                            },
                        ) {
                            Text(
                                stringResource(Res.string.next)
                            )
                            Icon(
                                Icons.Default.NavigateNext,
                                contentDescription = "Next"
                            )
                        }

                    }
                    is com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.ConfirmNote-> {

                        val listState = rememberLazyListState()

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f)
                        ) {
                            item {
                                if (writeNewNoteUIState.inReplyToNostrEvent?.nostrEvent?.kind == TextNoteEvent.KIND) {

                                    Card {
                                        writeNewNoteUIState.inReplyToNostrEvent.RenderNotePreview(
                                            onNavigateToEvent = onNavigateToNostrEvent
                                        )
                                    }


                                    HorizontalDivider(
                                        modifier = Modifier.padding(MaterialTheme.spacing.space250)
                                    )
                                } else {
                                    // Nothing to show, but the LazyColumn item has to
                                    // render something. 1dp was standing in for zero.
                                    Spacer(
                                        modifier = Modifier.height(MaterialTheme.spacing.space0)
                                    )
                                }
                            }

                            item {
                                Column(
                                    modifier = Modifier.fillParentMaxHeight().fillMaxWidth().padding(
                                        MaterialTheme.spacing.space125
                                    ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                                ) {
                                    Text(
                                        text = writeNewNoteUIState.localProfile.profile.humanReadableNameOrPubkey(),
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    writeNewNoteUIState.localProfile.profile.staticIdentifier()?.let { staticIdentifier ->
                                        Text(
                                            text = staticIdentifier,
                                            style = MaterialTheme.typography.labelSmallEmphasized
                                        )
                                    }

                                    com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar(
                                        profile = writeNewNoteUIState.localProfile.profile
                                    )

                                    if (writeNewNoteUIState.inReplyToNostrEvent != null) {
                                        Text(
                                            text = stringResource(Res.string.replying_to, writeNewNoteUIState.inReplyToNostrEvent.profile?.humanReadableNameOrPubkey() ?: "note"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    com.machankura.dedun.compose.ui.composable.widgets.content.RichContent(
                                        // Hack to have the thing rendered for a preview...
                                        nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                                            id = "",
                                            content = writeNewNoteUIState.text,
                                            sig = "",
                                            tags = emptyArray(),
                                            kind = 1,
                                            createdAt = Clock.System.now(),
                                            pubKey = ""
                                        ),
                                        localQuotedNostrEvent = null,
                                        mentionedProfiles = writeNewNoteViewModel.mentionableProfiles.map {
                                            com.machankura.dedun.compose.database.model.intermdiate.LocalMention(
                                                mention = com.machankura.dedun.compose.database.model.Mention(
                                                    mentionedPublicKey = it.publicKey,
                                                    mentioningNostrEventId = ""
                                                ),
                                                profile = it
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        onNavigateToEvent = {}
                                        // TODO: Add TextAlign.Center
                                    )

//                                    Text(
//                                        text = writeNewNoteUIState.text,
//                                        style = MaterialTheme.typography.bodySmall,
//                                        textAlign = TextAlign.Center
//                                    )

                                    if (writeNewNoteUIState.quotedNostrEvent?.nostrEvent?.kind == TextNoteEvent.KIND) {
                                        Card {
                                            writeNewNoteUIState.quotedNostrEvent.RenderNotePreview(
                                                onNavigateToEvent = onNavigateToNostrEvent
                                            )
                                        }

                                    }

                                    Text(
                                        text = Clock.System.now().toFormattedTimeAndDateString(),
                                        style = MaterialTheme.typography.labelSmall

                                    )
                                }
                            }
                        }

                        if (writeNewNoteUIState.inReplyToNostrEvent != null) {
                            LaunchedEffect(writeNewNoteUIState) {
                                listState.scrollToItem(1)
                            }
                        }

                        Text(
                            text = stringResource(Res.string.the_above_will_be_your_new_note),
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (writeNewNoteViewModel.isActionPending.value) {
                            LoadingIndicator(
                                modifier = Modifier
                            )
                        } else {
                            Button(
                                onClick = {
                                    writeNewNoteViewModel.createNewNote(
                                        activeUserPublicKey = activeUserPublicKey,
                                        onNostrEventPublished = onNostrEventPublished,
                                        inReplyToNostrEvent = writeNewNoteUIState.inReplyToNostrEvent,
                                        quotedNostrEvent = writeNewNoteUIState.quotedNostrEvent
                                    )
                                }
                            ) {
                                Text(
                                    text = stringResource(Res.string.transmit_note)
                                )
                            }
                        }

                    }
                    is com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.Error -> {
                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(Res.string.something_went_wrong_and_we_were_unable_to_3),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    key(true) {
        writeNewNoteViewModel.initiate()
    }
}

@ConformancePreviews
@Composable
private fun WriteNewNoteScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            WriteNewNoteScreen(
                activeUserPublicKey = "",
                replyToNostrEventId = null,
                quotedNostrEventId = null,
                initialWriteNewNoteUIState =
                    com.machankura.dedun.compose.ui.view.state.WriteNewNoteUIState.InputPrompt(
//                        text = "Yes, I did it. So WHAT?!",
                        inReplyToNostrEvent = com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent(
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
                                nostrEventId = "nostrEventId"
                            ),
                        ),
                        localProfile = com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing(
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
                                nip05 = "john@doe.com",
                                nostrEventId = "nostrEventId"
                            ),
                            following = emptyList()
                        )
                    ),
                onNostrEventPublished = {},
                onNavigateToNostrEvent = {},
                onNavigateBack = {},
                nostrRepository = com.machankura.dedun.compose.repository.NostrRepository.NO_OP_NOSTR_REPOSITORY
            )
        }
    }
}