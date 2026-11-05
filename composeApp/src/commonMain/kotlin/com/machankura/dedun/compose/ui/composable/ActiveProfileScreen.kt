package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.ui.view.model.MemberWalletsViewModel
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.NewMemberWalletRoute
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bento
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.KeyPackageManagementRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.KeyRecoveryRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.ShareProfileRoute
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.view.model.ActiveProfileViewModel
import com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.edit_profile
import dedun.composeapp.generated.resources.key_package_management
import dedun.composeapp.generated.resources.network_relays
import dedun.composeapp.generated.resources.profile
import dedun.composeapp.generated.resources.key_recovery
import dedun.composeapp.generated.resources.share_profile
import dedun.composeapp.generated.resources.sign_out
import dedun.composeapp.generated.resources.switch_profile
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilesRoute
import dedun.composeapp.generated.resources.sign_out_of_this_profile_question
import dedun.composeapp.generated.resources.could_not_sign_out_please_try_again
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.SignOutOfReadOnlyDialog
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.view.model.SignOutViewModel
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.we_couldn_t_find_the_local_profile_please
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.composable.navigation.routes.WavelengthWalletRoute
import com.machankura.dedun.compose.ui.view.model.WavelengthWalletViewModel
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActiveProfileScreen(
    initialActiveProfileUIState: ActiveProfileUIState = ActiveProfileUIState.Loading,
    activeUserPublicKey: HexKey,
    nostrEventId: HexKey,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    nostrRepository: NostrRepository,
    // Sign out is real for a read-only identity only -- there is nothing on the device
    // to lose -- and these are what it takes. Null where the screen is composed without
    // an identity to sign out of (previews, tests), in which case the button keeps its
    // pending route for every kind.
    signOut: SignOutDependencies? = null,
    // The member's guarded wallets (docs/member-guarded-wallets.md). The no-op
    // repository lists none, and null wallets watch nothing: both for previews
    // and tests, which compose the screen without an identity.
    memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
    wallets: GroupOnchainWallets? = null,
    // The profile's own wallet (docs/wavelength-wallet.md). Null where the screen is composed
    // without an identity: previews and tests.
    identityWavelength: IdentityWavelength? = null,
    // An nsec profile's: its wallet has words of its own. Null for everyone else.
    onWalletRecoveryPhrase: (() -> Unit)? = null,
) {

    // Whether this identity can sign, which decides which rows below exist.
    val canSign = LocalCanSign.current

    val wavelengthWalletViewModel: WavelengthWalletViewModel? = identityWavelength?.let { viewModel(factory = WavelengthWalletViewModel.factory(it)) }
    var consentingToWallet by remember { mutableStateOf(false) }
    if (consentingToWallet && wavelengthWalletViewModel != null) {
        WavelengthConsentSheet(
            onTurnOn = { consentingToWallet = false; wavelengthWalletViewModel.turnOn() },
            onDismiss = { consentingToWallet = false },
        )
    }

    val signOutViewModel: SignOutViewModel? = signOut?.let {
        viewModel(
            factory = SignOutViewModel.factory(
                activeIdentityStateFlow = it.activeIdentityStateFlow,
                nostrRepository = nostrRepository,
                forgetNostrCredential = it.forgetNostrCredential,
                hideIdentityMetadata = it.hideIdentityMetadata,
            ),
        )
    }
    val signOutState = signOutViewModel?.state?.collectAsState()?.value
    if (signOutViewModel != null && signOut != null && signOutState is SignOutViewModel.State.Confirming) {
        SignOutOfReadOnlyDialog(
            title = Res.string.sign_out_of_this_profile_question,
            nostrPublicKey = signOutViewModel.nostrPublicKey,
            onConfirm = { signOutViewModel.signOut(onSignedOut = signOut.onSignedOut) },
            onDismiss = { signOutViewModel.cancel() },
        )
    }
    // The failure is short and needs no action: the button is still there to try again.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotSignOut = stringResource(Res.string.could_not_sign_out_please_try_again)
    LaunchedEffect(signOutState) {
        if (signOutState is SignOutViewModel.State.Failed) notify(couldNotSignOut)
    }

    val memberWalletsViewModel: MemberWalletsViewModel = viewModel(
        factory = MemberWalletsViewModel.factory(
            ownerPublicKey = activeUserPublicKey,
            memberWalletRepository = memberWalletRepository,
            wallets = wallets,
        ),
    )
    // Each time the profile is shown: a certificate can arrive while it is not.
    LaunchedEffect(true) { memberWalletsViewModel.refresh() }

    val activeProfileViewModel: ActiveProfileViewModel = viewModel(
        factory = ActiveProfileViewModel.factory(
            nostrEventId = nostrEventId,
            initialActiveProfileUIState = initialActiveProfileUIState,
            nostrRepository = nostrRepository,
            activeUserPublicKey = activeUserPublicKey
        ),
    )

    ScreenStateTransition(activeProfileViewModel.activeProfileUIState) { uiState ->
    when (val activeProfileUIState = uiState) {
            ActiveProfileUIState.Error -> {
                ErrorState()
            }
            is ActiveProfileUIState.Loaded -> {
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
                            modifier = Modifier.fillMaxWidth(),
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
                                        profile = activeProfileUIState.localNostrEvent.profile,
                                        publicKey = activeUserPublicKey
                                    )

                                    Column {
                                        activeProfileUIState.localNostrEvent.profile?.humanReadableNameOrPubkey()?.let { humanReadableNameOrPubkey ->
                                            Text(
                                                text = humanReadableNameOrPubkey,
                                                style = MaterialTheme.typography.titleLarge
                                            )
                                        }
                                        activeProfileUIState.localNostrEvent.profile?.about?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }

                                        Spacer(
                                            modifier = Modifier.height(MaterialTheme.spacing.space125)
                                        )

                                        Text(
                                            text = activeUserPublicKey.hexToNpubHrp(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125)
                                ) {
                                    Spacer(
                                        modifier = Modifier.weight(1f)
                                    )

                                    TextButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ImplementationPendingRoute("Edit profile")
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit profile"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(
                                            stringResource(Res.string.edit_profile)
                                        )
                                    }

                                    Spacer(
                                        modifier = Modifier.weight(1f)
                                    )

                                    // The list this sits in is otherwise TextButtons --
                                    // edit profile, key packages, change account, profile
                                    // keys, network relays. Two of the seven were filled
                                    // Buttons, which is M3's highest emphasis and is meant
                                    // for one action per screen. Sharing is the useful one,
                                    // so it keeps medium emphasis rather than maximum.
                                    FilledTonalButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ShareProfileRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    nostrEventId = nostrEventId
                                                )
                                            )
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.QrCode,
                                            contentDescription = "QR code"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )
                                        Text(
                                            stringResource(Res.string.share_profile)
                                        )
                                    }

                                    Spacer(
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            wavelengthWalletViewModel?.let { wallet ->
                                wavelengthWalletItems(
                                    state = wallet.uiState,
                                    onOpen = { onNavigateToRoute.invoke(WavelengthWalletRoute(activeUserPublicKey = activeUserPublicKey)) },
                                    onTurnOn = { consentingToWallet = true },
                                    onRecoveryPhrase = onWalletRecoveryPhrase,
                                )
                            }

                            memberWalletItems(
                                rows = memberWalletsViewModel.rows,
                                onOpen = { walletId ->
                                    onNavigateToRoute.invoke(MemberWalletRoute(activeUserPublicKey = activeUserPublicKey, walletId = walletId))
                                },
                                // A read-only identity cannot sign a request, so it is not offered one.
                                onNew = if (canSign) {
                                    { onNavigateToRoute.invoke(NewMemberWalletRoute(activeUserPublicKey = activeUserPublicKey)) }
                                } else null,
                            )

                            // Key packages are signed, and there is nothing to recover for
                            // an identity the device holds no secret for: neither row for a
                            // read-only identity.
                            if (canSign) item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            KeyPackageManagementRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                nostrEventId = nostrEventId
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.Bento,
                                        contentDescription = "Key packages"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.key_package_management)
                                    )
                                }
                            }


                            // For every kind: a read-only identity can leave for another
                            // profile the same way a signing one can.
                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(ProfilesRoute)
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.ImportExport,
                                        contentDescription = Decorative
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.switch_profile)
                                    )
                                }
                            }


                            if (canSign) item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            KeyRecoveryRoute(
                                                activeUserPublicKey = activeUserPublicKey
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = Decorative
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.key_recovery))
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ImplementationPendingRoute("Network relays")
                                        )

                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Hub,
                                        contentDescription = "Network relays"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.network_relays)
                                    )
                                }
                            }

                            item {
                                // Signing out was the *other* filled Button -- the most
                                // prominent control on the screen given to its most
                                // destructive action. It now matches how leaving and
                                // deleting a group are already treated in
                                // ChatRoomDetailScreen: a TextButton in the error colour.
                                //
                                // For a read-only identity it does what its colour has been
                                // promising: a confirmation naming the npub, then the
                                // identity leaves the device. For the other two kinds it
                                // keeps routing to the pending screen -- removing a seed is
                                // a wallet question, and an nsec has its own forget under
                                // key recovery.
                                TextButton(
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    enabled = signOutState !is SignOutViewModel.State.InProgress,
                                    onClick = {
                                        if (!canSign && signOutViewModel != null) {
                                            signOutViewModel.askToSignOut()
                                        } else {
                                            onNavigateToRoute.invoke(
                                                ImplementationPendingRoute("Sign out")
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Logout,
                                        contentDescription = "Logout"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.sign_out)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ActiveProfileUIState.Loading -> {
                LoadingDataIndicator()

                LaunchedEffect(true) {
                    activeProfileViewModel.loadNostrFeed()
                }
            }
            ActiveProfileUIState.NotFound -> {
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

/**
 * What signing out of a read-only identity needs, bundled so that the screen's parameter
 * list gains one line rather than four, and so a caller that has none of them passes
 * nothing rather than three lambdas that throw.
 */
class SignOutDependencies(
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
    val hideIdentityMetadata: suspend (Identity) -> Unit,
    /** The nav host's tail: re-list, and clear the active identity so startup shows the selector or Landing. */
    val onSignedOut: () -> Unit,
)

@ConformancePreviews
@Composable
private fun UnannouncedProfileScreenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            ActiveProfileScreen(
                initialActiveProfileUIState = ActiveProfileUIState.Loaded(
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