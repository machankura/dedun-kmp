package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.ui.view.model.PersonGuardedWalletsViewModel
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupSendRoute
import dedun.composeapp.generated.resources.wallets_your_groups_guard
import dedun.composeapp.generated.resources.guarded_by_s
import dedun.composeapp.generated.resources.a_group
import dedun.composeapp.generated.resources.a_group_below_s
import dedun.composeapp.generated.resources.another_group
import dedun.composeapp.generated.resources.guardian_after
import dedun.composeapp.generated.resources.spend_from_s_as_s
import dedun.composeapp.generated.resources.the_wallet_guarded_by_s
import com.machankura.dedun.compose.ui.composable.widgets.delayText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dedun.composeapp.generated.resources.spend
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.ListItem
import androidx.compose.material3.Icon
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.checking_whether_s_can_receive_messages_here
import dedun.composeapp.generated.resources.looking_for_this_profile_on_the_relays
import dedun.composeapp.generated.resources.open_chat
import dedun.composeapp.generated.resources.profile
import dedun.composeapp.generated.resources.s_hasn_t_set_up_messaging_on_dedun_yet
import dedun.composeapp.generated.resources.start_new_chat
import dedun.composeapp.generated.resources.try_again
import dedun.composeapp.generated.resources.we_could_not_find_a_profile_for_this_key
import dedun.composeapp.generated.resources.you_already_have_a_chat_with_s
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.ProfilePreviewViewModel
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState.Readiness

/**
 * The person behind a pasted npub, and the one thing to do about them.
 *
 * Reached from the new-chat dialog by public key, before anything is created: the
 * profile block is the confirmation the flow never had, and "Start new chat" hands
 * over to `ChatRoomMessagingRoute` exactly as the dialog used to -- the room is made
 * where it has always been made, and this screen is popped under it, as the profile
 * detail is under a chat started from it.
 *
 * The action is the app's extended FAB, in the bottom bar where every screen's
 * primary action goes, and it reads the state's readiness: a chat that exists opens,
 * one that can be started starts, and one that cannot yet is disabled with the
 * reason in the body above it rather than twenty seconds into the next screen. Hidden
 * altogether, bar and all, for a read-only identity: a message is a gift wrap sealed
 * with the sender's key, and there is nothing such an identity can do here but look.
 * See docs/npub-profile-preview.md.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfilePreviewScreen(
    activeUserPublicKey: HexKey,
    profilePublicKey: HexKey,
    initialProfilePreviewUIState: ProfilePreviewUIState = ProfilePreviewUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    onStartChat: (Route) -> Unit,
    onNavigateBack: () -> Unit,
    // The person's guarded wallets that this profile's groups guard
    // (docs/member-guarded-wallets.md, Phase 11), and where a spend from one goes.
    memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
    onNavigateToRoute: (Route) -> Unit = {},
) {
    val personWalletsViewModel: PersonGuardedWalletsViewModel = viewModel(
        factory = PersonGuardedWalletsViewModel.factory(ownerPublicKey = profilePublicKey, memberWalletRepository = memberWalletRepository),
    )
    LaunchedEffect(true) { personWalletsViewModel.initiate() }

    val profilePreviewViewModel: ProfilePreviewViewModel = viewModel(
        factory = ProfilePreviewViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            profilePublicKey = profilePublicKey,
            initialProfilePreviewUIState = initialProfilePreviewUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
        ),
    )

    // Sixty-three characters of bech32, remembered once: they are what the user
    // pasted, and the block is not a confirmation without them.
    val npub = remember(profilePublicKey) { profilePublicKey.hexToNpubHrp() }

    val canSign = LocalCanSign.current

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.profile)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
        bottomBar = {
            // Read straight off the view model rather than inside the transition below:
            // the bar is a slot of the scaffold, not of the content, and it exists only
            // once there is a person to act on.
            val state = profilePreviewViewModel.profilePreviewUIState
            if (canSign && state is ProfilePreviewUIState.Loaded) {
                BottomAppBar(
                    floatingActionButton = {
                        ChatAction(
                            readiness = state.readiness,
                            onOpenChat = { chatRoomId, relayHint ->
                                onStartChat(
                                    ChatRoomMessagingRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = chatRoomId,
                                        relayHint = relayHint,
                                    )
                                )
                            },
                            onStartChat = {
                                onStartChat(
                                    ChatRoomMessagingRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = profilePublicKey,
                                        relayHint = null,
                                    )
                                )
                            },
                        )
                    },
                    actions = {},
                )
            }
        },
    ) { innerPadding ->
        // The bars are constant, so the transition wraps the content alone. None of the
        // branches needs `ColumnScope`, which is the one thing that costs.
        ScreenStateTransition(profilePreviewViewModel.profilePreviewUIState) { uiState ->
            when (val state = uiState) {
                ProfilePreviewUIState.Loading -> {
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                    ) {
                        Npub(npub)
                        LoadingDataIndicator(
                            fillScreen = false,
                            text = stringResource(Res.string.looking_for_this_profile_on_the_relays),
                        )
                    }
                }

                is ProfilePreviewUIState.Loaded -> {
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                    ) {
                        ProfileBlock(profile = state.profile, publicKey = profilePublicKey, npub = npub)

                        // Why the button in the bar below is or is not pressable. Nothing
                        // for a read-only identity: there is no button to explain.
                        if (canSign) {
                            ReadinessLine(
                                name = state.profile.humanReadableNameOrPubkey(),
                                readiness = state.readiness,
                                onRetry = { profilePreviewViewModel.retry() },
                            )
                        }

                        // One's own wallets are listed on one's own profile, with their
                        // keys; this is the guardians' view of somebody else's.
                        if (profilePublicKey != activeUserPublicKey && personWalletsViewModel.rows.isNotEmpty()) {
                            GuardedByYourGroups(
                                wallets = personWalletsViewModel.rows,
                                onSpend = { holder ->
                                    onNavigateToRoute(
                                        GroupSendRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            chatRoomId = holder.wallet.ancestorChatRoomId,
                                            relayHint = null,
                                            guardedChatRoomId = holder.wallet.descendantChatRoomId,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }

                ProfilePreviewUIState.NotFound -> {
                    // Nothing went wrong: the search came back empty, and that is the one
                    // absence this screen has to name. Asking again is the action.
                    EmptyState(
                        modifier = Modifier.padding(innerPadding).readableContent(),
                        message = stringResource(Res.string.we_could_not_find_a_profile_for_this_key),
                        icon = Icons.Default.PersonSearch,
                        action = {
                            TextButton(onClick = { profilePreviewViewModel.retry() }) {
                                Text(stringResource(Res.string.try_again))
                            }
                        },
                    )
                }

                ProfilePreviewUIState.Error -> {
                    // The queue refused, so nothing was asked; asking again is the retry.
                    ErrorState(
                        modifier = Modifier.padding(innerPadding).readableContent(),
                        onRetry = { profilePreviewViewModel.retry() },
                    )
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialProfilePreviewUIState == ProfilePreviewUIState.Loading) {
            profilePreviewViewModel.initiate()
        }
    }
}

/**
 * The person's guarded wallets as this profile's groups hold them: one entry per
 * wallet, saying which group directly guards it, and under it each group here
 * holding a leaf in it, with that leaf's delay and -- where this device holds a
 * share of that group's key -- the way to spend through it, which is the
 * guardian spend the group's wallet screen already offers.
 * `docs/member-wallet-guarding-group.md`, D7.
 */
@Composable
private fun GuardedByYourGroups(
    wallets: List<MemberWalletManager.GuardedHereWallet>,
    onSpend: (MemberWalletManager.GuardedHere) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        Text(stringResource(Res.string.wallets_your_groups_guard), style = MaterialTheme.typography.titleMedium)
        wallets.forEach { wallet ->
            val guardian = wallet.guardianName
                ?: wallet.below?.let { stringResource(Res.string.a_group_below_s, it) }
                ?: stringResource(Res.string.another_group)
            val guardedBy = stringResource(Res.string.guarded_by_s, guardian)
            ListItem(
                leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
                // Named by its label, or with none by the group that guards it.
                headlineContent = { Text(wallet.label ?: guardedBy) },
                supportingContent = wallet.label?.let { { Text(guardedBy) } },
            )
            wallet.holders.forEach { holder ->
                val holderName = holder.holderName ?: stringResource(Res.string.a_group)
                val spendLabel = stringResource(
                    Res.string.spend_from_s_as_s,
                    wallet.label ?: stringResource(Res.string.the_wallet_guarded_by_s, guardian),
                    holderName,
                )
                ListItem(
                    // Under its wallet, so the holders read as the wallet's.
                    modifier = Modifier.padding(start = MaterialTheme.spacing.containerPadding),
                    headlineContent = {
                        Text(stringResource(Res.string.guardian_after, holderName, delayText(holder.wallet.delay, holder.wallet.chain)))
                    },
                    trailingContent = if (holder.canSpend) {
                        {
                            TextButton(onClick = { onSpend(holder) }, modifier = Modifier.semantics { contentDescription = spendLabel }) {
                                Text(stringResource(Res.string.spend))
                            }
                        }
                    } else null,
                )
            }
        }
    }
}

/**
 * Who this is: leading-aligned, because rows with an avatar align to a leading edge
 * and centring is for a block that is the only thing on the screen, which this is
 * not once the button is under it.
 */
@Composable
private fun ProfileBlock(
    profile: Profile,
    publicKey: HexKey,
    npub: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        // 75dp is the avatar's size, a dimension and not a spacing, and is what the
        // profile detail draws.
        ProfileAvatar(
            size = 75.dp,
            publicKey = publicKey,
            profile = profile,
        )

        Text(
            text = profile.humanReadableNameOrPubkey(),
            style = MaterialTheme.typography.titleLarge,
        )

        // The nip05 or the lightning address: the thing a user compares against what
        // they were told.
        profile.staticIdentifier()?.let { staticIdentifier ->
            Text(
                text = staticIdentifier,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The whole of it. This is the one screen whose job is to let the user read it.
        profile.about?.takeIf { it.isNotBlank() }?.let { about ->
            Text(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Npub(npub)
    }
}

@Composable
private fun Npub(npub: String) {
    Text(
        text = npub,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one thing to do about the person, and whether it can be done yet.
 *
 * The app's extended FAB, so that this screen's primary action is shaped where every
 * other screen's is. Its `enabled` is what the readiness decides, and while the key
 * package is still owed the icon's slot holds the indicator, as the two screens with an
 * action out on the relays do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatAction(
    readiness: Readiness,
    onOpenChat: (chatRoomId: String, relayHint: String?) -> Unit,
    onStartChat: () -> Unit,
) {
    ExtendedFab(
        label = stringResource(
            if (readiness is Readiness.ExistingChat) Res.string.open_chat else Res.string.start_new_chat
        ),
        icon = {
            if (readiness == Readiness.Checking) {
                LoadingIndicator()
            } else {
                Icon(
                    Icons.Default.Mail,
                    // The label is beside it.
                    contentDescription = Decorative,
                )
            }
        },
        enabled = readiness is Readiness.ExistingChat || readiness == Readiness.CanStartChat,
        onClick = {
            when (readiness) {
                is Readiness.ExistingChat -> onOpenChat(readiness.chatRoomId, readiness.relayHint)
                else -> onStartChat()
            }
        },
    )
}

/**
 * The reason the button below is or is not pressable: a supporting line, not a second
 * message, and nothing at all when the answer is simply yes.
 */
@Composable
private fun ReadinessLine(
    name: String,
    readiness: Readiness,
    onRetry: () -> Unit,
) {
    when (readiness) {
        is Readiness.ExistingChat -> SupportingLine(stringResource(Res.string.you_already_have_a_chat_with_s, name))
        Readiness.CanStartChat -> Unit
        Readiness.Checking -> SupportingLine(stringResource(Res.string.checking_whether_s_can_receive_messages_here, name))
        Readiness.NotYetOnDedun -> {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                SupportingLine(stringResource(Res.string.s_hasn_t_set_up_messaging_on_dedun_yet, name))
                TextButton(onClick = onRetry) {
                    Text(stringResource(Res.string.try_again))
                }
            }
        }
    }
}

@Composable
private fun SupportingLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@ConformancePreviews
@Composable
private fun ProfilePreviewScreenPreview() {
    DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProfilePreviewScreen(
                activeUserPublicKey = "a".repeat(64),
                profilePublicKey = "b".repeat(64),
                initialProfilePreviewUIState = ProfilePreviewUIState.Loaded(
                    profile = Profile(
                        publicKey = "b".repeat(64),
                        displayName = "John Doe",
                        nip05 = "john@doe.com",
                        about = "I am the stone that the builder refused.",
                        nostrEventId = "nostrEventId",
                    ),
                    readiness = Readiness.CanStartChat,
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onStartChat = {},
                onNavigateBack = {},
            )
        }
    }
}
