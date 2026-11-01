package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.view.model.ShareProfileViewModel
import com.machankura.dedun.compose.ui.view.state.ShareProfileUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.profile
import dedun.composeapp.generated.resources.re_broadcast
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.we_couldn_t_find_the_local_profile_please
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShareProfileScreen(
    initialShareProfileUIState: ShareProfileUIState = ShareProfileUIState.Loading,
    activeUserPublicKey: HexKey,
    nostrEventId: HexKey,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    nostrRepository: NostrRepository,
) {

    val shareProfileViewModel: ShareProfileViewModel = viewModel(
        factory = ShareProfileViewModel.factory(
            nostrEventId = nostrEventId,
            initialActiveProfileUIState = initialShareProfileUIState,
            nostrRepository = nostrRepository,
            activeUserPublicKey = activeUserPublicKey
        ),
    )

    ScreenStateTransition(shareProfileViewModel.shareProfileUIState) { uiState ->
    when (val shareProfileUIState = uiState) {
            ShareProfileUIState.Error -> {
                ErrorState()
            }
            is ShareProfileUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(Res.string.profile)
                                )
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding)
                        .readableContent()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                                ) {
                                    ProfileAvatar(
                                        profile = shareProfileUIState.localNostrEvent.profile,
                                        publicKey = activeUserPublicKey
                                    )

                                    Column {
                                        shareProfileUIState.localNostrEvent.profile?.humanReadableNameOrPubkey()?.let { humanReadableNameOrPubkey ->
                                            Text(
                                                text = humanReadableNameOrPubkey,
                                                style = MaterialTheme.typography.titleLarge
                                            )
                                        }
                                        shareProfileUIState.localNostrEvent.profile?.about?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier.padding(MaterialTheme.spacing.space500)
                                ) {
                                    QRCodeView(
                                        activeUserPublicKey.hexToNpubHrp()
                                    )
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250)
                                ) {
                                    val clipboardManager = LocalClipboardManager.current

                                    TextButton(
                                        onClick = {

                                            clipboardManager.setText(
                                                buildAnnotatedString {
                                                    append(activeUserPublicKey.hexToNpubHrp())
                                                }
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy npub"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )
                                        Text(
                                            text = activeUserPublicKey.hexToNpubHrp(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }

                            }
                        }

                        // Nothing is signed by a re-broadcast, but a copy of the kind 0 is
                        // queued for the finder relays, and a read-only identity puts nothing
                        // on a relay -- not even a copy. The broadcast pump is not running for
                        // it to carry the request anyway.
                        if (LocalCanSign.current) Row(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = {
                                    shareProfileViewModel.rebroadcastProfile(
                                        localNostrEvent = shareProfileUIState.localNostrEvent
                                    )
                                }
                            ) {
                                Text(
                                    stringResource(Res.string.re_broadcast)
                                )
                            }
                        }
                    }
                }
            }
            ShareProfileUIState.Loading -> {
                LoadingDataIndicator()

                LaunchedEffect(true) {
                    shareProfileViewModel.loadNostrFeed()
                }
            }
            ShareProfileUIState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(Res.string.we_couldn_t_find_the_local_profile_please))
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun UnannouncedProfileScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            ShareProfileScreen(
                initialShareProfileUIState = ShareProfileUIState.Loaded(
                    localNostrEvent = LocalNostrEvent(
                        nostrEvent = NostrEvent(
                            id = "eventId",
                            pubKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                            content = """Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
                                
Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. 
                                
It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.""".trimIndent(),
                            tags = emptyArray(),
                            sig = "",
                            kind = MetadataEvent.KIND
                        ),
                        profile = com.machankura.dedun.compose.database.model.Profile(
                            publicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                            displayName = "John Doe",
                            nostrEventId = "nostrEventId",
                            about = "I been here, there and everywhere."
                        )

                    )
                ),
                activeUserPublicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                nostrEventId = "",
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {}
            )
        }
    }
}