package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.GroupLightningRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.SignInRoute
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.NewChatBottomSheetDialog
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.StartDirectMessageToNpubOrNip05Dialog
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.model.ChatRoomListViewModel
import com.machankura.dedun.compose.ui.view.model.HomeScreenType
import com.machankura.dedun.compose.ui.view.model.HomeViewModel
import com.machankura.dedun.compose.ui.view.state.HomeScreenUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.bennu
import bennu.composeapp.generated.resources.new_chat
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import androidx.compose.ui.Alignment
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.theme.breakpoint
import com.machankura.dedun.compose.ui.theme.listPaneWidthFor
import com.machankura.dedun.compose.ui.theme.readableContent
import bennu.composeapp.generated.resources.pick_a_conversation_to_read_it_here
import bennu.composeapp.generated.resources.messages_need_the_secret_key_this_profile
import bennu.composeapp.generated.resources.sign_in_with_the_nsec
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    activeUserPublicKey: HexKey,
    initialHomeScreenUIState: HomeScreenUIState = HomeScreenUIState.Loading,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateToDirectMessageDetail: (Route) -> Unit,
    onNavigateToChatRoomCreation: () -> Unit,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    // Only the detail pane uses this, and only from the expanded breakpoint up. It is a
    // required parameter rather than a nullable one because a home screen that silently
    // loses its detail pane on a desktop is a worse failure than a compile error.
    frostSigningRepository: FrostSigningRepository,
    dkgRepository: DkgRepository,
    lightningRepository: GroupLightningRepository,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    val openNpubDialog = remember { mutableStateOf(false) }

    // Null below the expanded breakpoint, which is where the room list is the whole screen
    // and tapping a room navigates, exactly as it did before panes existed.
    val listPaneWidth = listPaneWidthFor(MaterialTheme.breakpoint)

    // The room the detail pane is showing. Two saveable strings rather than the route
    // object, because that is all the route carries and both survive a process death that
    // a `@Serializable` route would need a Saver to survive.
    var selectedChatRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRelayHint by rememberSaveable { mutableStateOf<String?>(null) }

    // Tapping a room means two different things at two widths, and the list does not need
    // to know which: it hands over a `ChatRoomMessagingRoute` either way, and this decides
    // whether that is a destination to push or a selection to make.
    val onOpenChatRoom: (Route) -> Unit = { route ->
        if (listPaneWidth != null && route is ChatRoomMessagingRoute) {
            selectedChatRoomId = route.chatRoomId
            selectedRelayHint = route.relayHint
        } else {
            onNavigateToDirectMessageDetail(route)
        }
    }

    // Whether this identity can sign: the one thing the read-only branch below turns on.
    val canSign = LocalCanSign.current

    val homeScreenViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            initialHomeScreenUIState = initialHomeScreenUIState,
            nostrRepository = nostrRepository,
            pagerState = rememberPagerState { HomeScreenType.entries.size }
        ),
    )

    ScreenStateTransition(homeScreenViewModel.homeScreenUIState) { uiState ->
    when (val homeScreenUIState = uiState) {
            HomeScreenUIState.Error -> {
                Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ErrorState()
                    }
                }

            }
            is HomeScreenUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier,
                    topBar = {
                        // No colour override. It was `containerColor = primaryContainer` with
                        // `titleContentColor = primary`, which in the light scheme is #000000
                        // on #1B1B1B -- 1.22:1, a black title on a near-black bar. The
                        // default is `surface`/`onSurface` and needs no help.
                        TopAppBar(
                            title = {
                                // "Bennu". The launcher label, the desktop window title, the
                                // landing screen and the package name all say so, and since the
                                // rebrand so does `UserAgent.CLIENT_NAME` -- the one that goes on
                                // the wire, as the `client` tag on a published relay list. The three
                                // brands before it are gone from live code too -- what was
                                // `TorchTheme`, then `CuratedTheme`, then `CurareTheme`, is
                                // `BennuTheme`, and no error string names any of them.
                                Text(stringResource(Res.string.bennu))
                            },
                            // No navigation icon and no actions. The avatar in the leading
                            // slot and the search icon in the trailing one were the app's only
                            // two peer surfaces, and they are now items in the navigation
                            // component -- a bar on a phone, a rail on a wide window. Leaving
                            // them here as well would be two routes to one destination, which
                            // is the thing M3's "swap only functionally equivalent components"
                            // caution is about.
                        )

                    },
                    floatingActionButton = {
                        // Only while there is one pane. With two, the scaffold's FAB slot is
                        // the bottom-right of the *window*, which is on top of the
                        // transcript's send button; M3 puts a list-detail layout's primary
                        // action in the list pane, and so does the branch below.
                        // And only for an identity that can sign: a new chat is a key
                        // package, a welcome and a gift wrap, none of which a read-only
                        // identity can produce. Hidden, not disabled -- the empty state
                        // beside it says why.
                        if (listPaneWidth == null && canSign) NewChatButton { showBottomSheet = true }
                    }
                ) { innerPadding ->
                    @Composable
                    fun ChatRoomListPane(modifier: Modifier) = Column(
                        modifier = modifier
                    ) {
                        PrimaryScrollableTabRow(
                            modifier = Modifier.padding(MaterialTheme.spacing.space125),
                            edgePadding = 10.dp,
                            selectedTabIndex = homeScreenViewModel.pagerState.currentPage,
                        ) {
                            HomeScreenType.entries.forEachIndexed { index, destination ->
                                Tab(
                                    selected = homeScreenViewModel.pagerState.currentPage == index,
                                    onClick = {
                                        scope.launch {
                                            homeScreenViewModel.pagerState.animateScrollToPage(destination.ordinal)
                                        }
                                    },
                                    text = {
                                        Text(
                                            text = destination.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            HorizontalPager(
                                state = homeScreenViewModel.pagerState,
                            ) { pageIndex ->
                                when (val homeScreenTypeType = HomeScreenType.entries[pageIndex]) {
                                    HomeScreenType.Messages -> {
                                        val chatRoomListViewModel: ChatRoomListViewModel = viewModel(
                                            key = homeScreenTypeType.name,
                                            factory = ChatRoomListViewModel.factory(
                                                initialChatRoomListUIState = com.machankura.dedun.compose.ui.view.state.ChatRoomListUIState.Loading,
                                                publicKey = homeScreenUIState.profileWithFollowing.profile.publicKey,
                                                nostrRepository = nostrRepository,
                                                chatRepository = chatRepository
                                            ),
                                        )

                                        key(true) {
                                            chatRoomListViewModel.RenderFeed(
                                                onNavigateToDirectMessageDetail = onOpenChatRoom
                                            )
                                        }

                                        key(true) {
                                            chatRoomListViewModel.initiate()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!canSign) {
                        // One column at every width. The two-pane layout is a list beside
                        // a detail, and there is no list: every room is MLS or a gift wrap,
                        // encrypted to the key this identity does not hold. The empty state
                        // is the only thing on the screen, which is the one case
                        // readableContent()'s centring is for -- and its action is where
                        // the upgrade lives. ChatRoomListViewModel is not composed, so the
                        // inbox sync and the MLS negentropy it would queue are not queued.
                        Column(
                            modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ReadOnlyInbox(onSignInWithTheNsec = { onNavigateToRoute(SignInRoute) })
                        }
                    } else if (listPaneWidth == null) {
                        // Compact and medium: the list is the screen, and a room is a route.
                        // Byte for byte the layout this screen has always had.
                        ChatRoomListPane(
                            Modifier.padding(innerPadding).readableContent().fillMaxSize()
                        )
                    } else {
                        // Expanded and up: the list keeps a fixed width and the room fills the
                        // rest. No `readableContent()` on the pair -- capping the two panes
                        // together to one column's measure is the opposite of what a second
                        // pane is for. Each pane holds its own content to a measure instead,
                        // and `ChatRoomMessagingScreen` already does.
                        Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(listPaneWidth)
                                    .fillMaxHeight()
                                    // The pane's width is the one thing about this layout that
                                    // is a number rather than a rule, and the only way to check
                                    // a number is to measure it in a composition.
                                    .testTag(ChatListPaneTag)
                            ) {
                                ChatRoomListPane(Modifier.fillMaxSize())

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(MaterialTheme.spacing.containerPadding)
                                ) {
                                    NewChatButton { showBottomSheet = true }
                                }
                            }

                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.paneGap))

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                val chatRoomId = selectedChatRoomId
                                if (chatRoomId == null) {
                                    EmptyState(
                                        message = stringResource(Res.string.pick_a_conversation_to_read_it_here)
                                    )
                                } else {
                                    // Keyed on the room, and given a view model store of its
                                    // own under that key. `viewModel()` scopes to the nearest
                                    // owner, which on a phone is the room's own back stack
                                    // entry: every push gets a fresh store, and the screen
                                    // never has to think about it. The pane is not pushed.
                                    // Every room it shows shares the home destination's
                                    // entry, where the first room's view models sat under
                                    // the unkeyed slot and each later `viewModel(factory =
                                    // …)` got them back with its factory ignored -- the
                                    // transcript stayed on the first room opened, whatever
                                    // the list said. `key` alone rebuilds the composition,
                                    // not the store. Keying the `viewModel()` calls on the
                                    // room would unstick it at the price of keeping every
                                    // room ever opened, DB observers and all, in the home
                                    // entry's store for as long as the home screen lives.
                                    // This is what a back stack entry gives a phone, given
                                    // to the pane: a store cleared when the room changes,
                                    // and parked in the home entry's so that it survives a
                                    // rotation. ChatPaneSelectionJvmTest holds it to both.
                                    key(chatRoomId) {
                                        CompositionLocalProvider(
                                            LocalViewModelStoreOwner provides rememberViewModelStoreOwner()
                                        ) {
                                            ChatRoomMessagingScreen(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                                relayHint = selectedRelayHint,
                                                nostrRepository = nostrRepository,
                                                chatRepository = chatRepository,
                                                frostSigningRepository = frostSigningRepository,
                                                dkgRepository = dkgRepository,
                                                lightningRepository = lightningRepository,
                                                // "Replace what is on screen" is a route push
                                                // on a phone and a change of selection here.
                                                // It fires when a conversation that did not
                                                // exist yet has just been created and has an
                                                // id at last.
                                                onNavigateToRouteAndPopUpInclusive = onOpenChatRoom,
                                                onNavigateToRoute = onNavigateToRoute,
                                                // The list is beside it; there is nothing to
                                                // pop.
                                                onNavigateBack = null,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Neither can open without the button above, and the button is hidden for
                    // an identity that cannot sign; the guard here is so that the two cannot
                    // drift apart.
                    if (showBottomSheet && canSign) {
                        NewChatBottomSheetDialog(
                            scope = scope,
                            sheetState = sheetState,
                            onSetShowBottomSheetUpdate = { toggle ->
                                showBottomSheet = toggle
                            },
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateToChatRoomCreation = onNavigateToChatRoomCreation,
                            onStartDirectMessageChatWithNpub = {
                                openNpubDialog.value = true
                            }
                        )
                    }

                    when {
                        openNpubDialog.value && canSign -> {
                            StartDirectMessageToNpubOrNip05Dialog(
                                activeUserPublicKey = activeUserPublicKey,
                                scope = scope,
                                toggleOpenDialogSetting = {
                                    openNpubDialog.value = false
                                },
                                onNavigateToRoute = onNavigateToRoute
                            )
                        }
                    }
                }
            }
            HomeScreenUIState.Loading -> {
                LoadingDataIndicator()
            }
        }
    }

    key(true) {
        homeScreenViewModel.initiate()
    }
}

@ConformancePreviews
@Composable
private fun HomeScreenPreview() {
    BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            HomeScreen(
                activeUserPublicKey = "",
                initialHomeScreenUIState = HomeScreenUIState.Loaded(
                    profileWithFollowing = LocalProfileWithFollowing(
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
                        ),
                        following = emptyList()
                    )
                ),
                onNavigateToRoute = {},
                onNavigateToChatRoomCreation = {},
                onNavigateToDirectMessageDetail = {},
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                lightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
            )
        }
    }
}

/**
 * What a read-only identity sees where its rooms would be, and the one thing that fills
 * it.
 *
 * `EmptyState`'s message is required for exactly this reason -- an absence has to say
 * which absence it is -- and its action slot is where the upgrade lives: the nsec pasted
 * on the sign-in screen hits the credentials file's upgrade rule, and startup takes the
 * user back here with rooms in it.
 */
@Composable
private fun ReadOnlyInbox(onSignInWithTheNsec: () -> Unit) {
    EmptyState(
        message = stringResource(Res.string.messages_need_the_secret_key_this_profile),
        icon = Icons.Default.Lock,
        action = {
            TextButton(onClick = onSignInWithTheNsec) {
                Text(stringResource(Res.string.sign_in_with_the_nsec))
            }
        },
    )
}

/**
 * The one action the chat list offers, in whichever slot the layout has for it.
 *
 * Extracted only so that the two branches above cannot drift: on one pane it is the
 * `Scaffold`'s floating action button, on two it sits in the bottom corner of the list
 * pane, and a "new chat" that reads differently depending on window width would be a
 * strange thing to discover.
 */
@Composable
private fun NewChatButton(onClick: () -> Unit) {
    ExtendedFab(
        label = stringResource(Res.string.new_chat),
        icon = {
            Icon(
                Icons.Default.Add,
                // The button's own text says "New chat" immediately beside it.
                contentDescription = Decorative,
            )
        },
        onClick = onClick,
    )
}

/** Addresses the chat list pane from a layout test. See `ChatPaneLayoutJvmTest`. */
const val ChatListPaneTag = "chat-list-pane"
