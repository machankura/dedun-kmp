package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import fr.acinq.phoenix.data.ListWalletState
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.utils.preferences.UserPrefs
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import fr.acinq.phoenix.utils.preferences.getByWalletIdOrDefault
import kotlinx.coroutines.flow.first
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.StoredIdentity
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.wallet.AddProfileRows
import com.machankura.dedun.compose.ui.composable.widgets.wallet.rememberProfilesOf
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.choose_a_profile
import dedun.composeapp.generated.resources.could_not_load_the_profiles_on_this_device
import dedun.composeapp.generated.resources.decrypting
import dedun.composeapp.generated.resources.loading_preferences
import dedun.composeapp.generated.resources.lock_prompt_coming_soon
import dedun.composeapp.generated.resources.opening_profile
import dedun.composeapp.generated.resources.preparing_profiles
import dedun.composeapp.generated.resources.starting_wallet
import dedun.composeapp.generated.resources.startup_error
import dedun.composeapp.generated.resources.unlock_to_continue
import com.machankura.dedun.compose.ui.view.state.StartupChoice

@Composable
fun SovereignWalletStartupScreen(
    sovereignWalletViewModel: com.machankura.dedun.compose.ui.view.model.SovereignWalletViewModel,
    /** For the selector's rows: the nostr profile of each listed key, where the device has one. */
    nostrRepository: NostrRepository,
    onNavigateToWalletLandingPage: () -> Unit,
    onSuccessfulStartup: () -> Unit,
    /** The two ways to add a profile from the selector; a device with one profile can be given a second without opening the first. */
    onNavigateToSignIn: () -> Unit,
    onNavigateToCreateProfile: () -> Unit,
    forceWalletId: WalletId?,
) {
    val sovereignWalletStartupViewModel = viewModel<com.machankura.dedun.compose.ui.view.model.SovereignWalletStartupViewModel>(
        factory = com.machankura.dedun.compose.ui.view.model.SovereignWalletStartupViewModel.factory(
            phoenixGlobal = sovereignWalletViewModel.phoenixGlobal,
        )
    )

    // Deliberately no intro gate. `getShowIntro` defaults to true and nothing in this app ever
    // writes it back to false, so gating on it navigated away from this screen on every cold boot
    // — and a screen that has been left behind never gets to start the wallet. Startup has to run
    // to completion here: everything downstream reads the active identity, and nothing sets one
    // but this screen. An intro screen has to be a step inside this flow, not a detour around it.

    val listWalletState by sovereignWalletViewModel.listWalletState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        when (listWalletState) {
            is ListWalletState.Init -> {
                LoadingDataIndicator(
                    text = stringResource(Res.string.decrypting)
                )
            }
            is ListWalletState.Error -> {
                ImplementationPendingScreen(
                    text = stringResource(Res.string.could_not_load_the_profiles_on_this_device),
                    // Drawn inside the startup gate, which is the root of the stack.
                    onNavigateBack = null,
                )
            }
            is ListWalletState.Success -> {
                val globalPrefs = sovereignWalletViewModel.getGlobalPrefs()

                val availableIdentities by sovereignWalletViewModel.availableIdentities.collectAsState()
                val defaultWallet = globalPrefs.getDefaultWallet.collectAsState(null)
                val startWalletImmediately by sovereignWalletViewModel.startWalletImmediately.collectAsState()

                val availableWalletMetadataPrefs = globalPrefs.getAvailableWalletsMeta.collectAsState(null)
                val availableWalletMetadata = availableWalletMetadataPrefs.value

                val desiredWalletIdFlow = sovereignWalletViewModel.desiredWalletId.collectAsState()
                val desiredWalletId = desiredWalletIdFlow.value

                val activeIdentityFlow = sovereignWalletViewModel.activeIdentity.collectAsState()
                val activeIdentity = activeIdentityFlow.value

                when {
                    availableIdentities.isEmpty() -> {
                        LaunchedEffect(Unit) { onNavigateToWalletLandingPage.invoke() }
                        LoadingDataIndicator(
                            text = stringResource(Res.string.preparing_profiles)
                        )
                    }
                    availableWalletMetadata == null || defaultWallet.value == null -> {
                        LoadingDataIndicator(
                            text = stringResource(Res.string.preparing_profiles)
                        )
                    }
                    activeIdentity != null -> {
                        LoadingDataIndicator(
                            text = stringResource(Res.string.opening_profile)
                        )
                        LaunchedEffect(Unit) {
                            sovereignWalletViewModel.loadSovereignData(activeIdentity.id)
                            onSuccessfulStartup.invoke()
                        }
                    }
                    else -> {
                        when (val startupState = sovereignWalletStartupViewModel.state.value) {
                            is com.machankura.dedun.compose.ui.view.model.StartupViewState.Init -> {
                                // The table is StartupChoice's, where it can be read and
                                // tested; the one thing decided here is that the saved default
                                // is only a default when it names a wallet.
                                var loadingIdentity by remember {
                                    mutableStateOf(
                                        StartupChoice.resolve(
                                            force = forceWalletId,
                                            desired = desiredWalletId,
                                            startImmediately = startWalletImmediately,
                                            identities = availableIdentities,
                                            default = defaultWallet.value as? WalletId,
                                        )
                                    )
                                }
                                when (val stored = loadingIdentity) {
                                    null -> {
                                        val profiles by rememberProfilesOf(
                                            publicKeys = availableIdentities.values.map { it.nostrPublicKey },
                                            nostrRepository = nostrRepository,
                                        )
                                        com.machankura.dedun.compose.ui.composable.widgets.wallet.WalletsSelector(
                                            wallets = availableIdentities,
                                            walletsMetadata = availableWalletMetadata,
                                            activeWalletId = null,
                                            profiles = profiles,
                                            onWalletClick = {
                                                sovereignWalletViewModel.switchToIdentity(
                                                    it.id
                                                ); loadingIdentity = it
                                            },
                                            canEdit = false,
                                            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.space300),
                                            topContent = {
                                                Spacer(Modifier.height(MaterialTheme.spacing.space800))
                                                Text(
                                                    text = stringResource(Res.string.choose_a_profile),
                                                    style = MaterialTheme.typography.headlineSmall
                                                )
                                                Spacer(Modifier.height(MaterialTheme.spacing.space200))
                                            },
                                            bottomContent = {
                                                AddProfileRows(
                                                    onSignIn = onNavigateToSignIn,
                                                    onCreateProfile = onNavigateToCreateProfile,
                                                    caption = false,
                                                )
                                                // m3-spacing-exempt: room to scroll the
                                                // last row clear of the bottom of the
                                                // window, not a step in the spacing
                                                // rhythm. The scale tops out at 72dp and
                                                // rounding to it would put the last row
                                                // back under the edge.
                                                Spacer(Modifier.height(128.dp))
                                            }
                                        )
                                    }
                                    else -> {
                                        val metadata = remember { availableWalletMetadata.getByWalletIdOrDefault(stored.id) }
                                        val dataStoreManager = DataStoreManager(
                                            ctx = sovereignWalletViewModel.phoenixGlobal.ctx,
                                            chain = NodeParamsManager.chain
                                        )
                                        // The screen-lock gate wraps both kinds. It only ever needed the
                                        // id, to read the lock preferences, so it sits outside the branch
                                        // and a future lock is not something to remember to add twice.
                                        LoadWallet(
                                            identity = stored,
                                            metadata = metadata,
                                            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
                                            promptScreenLockImmediately = startWalletImmediately,
                                            doLoadWallet = { identity ->
                                                when (identity) {
                                                    is StoredIdentity.Mnemonic -> {
                                                        // The node starts for the wallet attached to this profile --
                                                        // its preferences, its metadata, the channel watcher -- and no
                                                        // longer for the key, which the identity reads from the
                                                        // credential the node is handed to cross-check.
                                                        sovereignWalletStartupViewModel.startupNode(walletId = identity.id, words = identity.userWallet.words, onStartupSuccess = {
                                                            sovereignWalletViewModel.setActiveWallet(stored = identity, business = it)
                                                            onSuccessfulStartup.invoke()
                                                        })
                                                    }
                                                    is StoredIdentity.NostrSecret -> {
                                                        // No node to start, so no platformStartupLogic, no
                                                        // schedulePlatformLogic and no StartupViewState: the
                                                        // identity is active the moment it is read. Setting it
                                                        // recomposes into the `activeIdentity != null` branch
                                                        // above, which is what reports the startup.
                                                        sovereignWalletViewModel.setActiveIdentity(
                                                            Identity.signing(
                                                                id = identity.id,
                                                                kind = IdentityKind.NostrSecret,
                                                                nostrPrivateKey = identity.privateKey,
                                                                userPrefs = dataStoreManager.loadUserPrefsForWallet(identity.id),
                                                                internalPrefs = dataStoreManager.loadInternalPrefsForWallet(identity.id),
                                                                business = null,
                                                            )
                                                        )
                                                    }
                                                    is StoredIdentity.NostrPublic -> {
                                                        // Nothing to start and nothing to decrypt: the
                                                        // public key is the whole of what the device
                                                        // holds, and the identity is active the moment
                                                        // it is read, as the nsec branch above is.
                                                        sovereignWalletViewModel.setActiveIdentity(
                                                            Identity.readOnly(
                                                                id = identity.id,
                                                                nostrPublicKey = identity.nostrPublicKey,
                                                                userPrefs = dataStoreManager.loadUserPrefsForWallet(identity.id),
                                                                internalPrefs = dataStoreManager.loadInternalPrefsForWallet(identity.id),
                                                            )
                                                        )
                                                    }
                                                }
                                                loadingIdentity = null
                                            },
                                            // only show back-to-selector button if there's more than one identity
                                            goToWalletSelector = availableIdentities.takeIf { it.size > 1 }?.let {
                                                { loadingIdentity = null; sovereignWalletViewModel.startWalletImmediately.value = false }
                                            }
                                        )
                                    }
                                }
                            }
                            is com.machankura.dedun.compose.ui.view.model.StartupViewState.StartingBusiness -> {
                                // The one place the word "wallet" stays: only the branch with
                                // a wallet attached reaches this state, and what is starting is
                                // a Lightning node. A bare-key profile never sees it.
                                LoadingDataIndicator(
                                    text = stringResource(Res.string.starting_wallet)
                                )
                            }
                            is com.machankura.dedun.compose.ui.view.model.StartupViewState.BusinessActive -> {
                                LoadingDataIndicator(
                                    text = stringResource(Res.string.opening_profile)
                                )
                            }
                            is com.machankura.dedun.compose.ui.view.model.StartupViewState.Error -> {

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = stringResource(Res.string.startup_error)
                                    )

                                    when (startupState) {
                                        is com.machankura.dedun.compose.ui.view.model.StartupViewState.Error.Generic -> {
                                            startupState.cause?.message?.let { message ->
                                                Text(
                                                    text = message,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(MaterialTheme.spacing.space200))
                                    HorizontalDivider(
                                        modifier = Modifier.width(50.dp)
                                    )
                                    Spacer(Modifier.height(MaterialTheme.spacing.space300))
//                                    BorderButton(
//                                        text = stringResource(R.string.startup_error_try_again),
//                                        icon = R.drawable.ic_reset,
//                                        onClick = onTryAgainClick
//                                    )
                                    Spacer(Modifier.height(MaterialTheme.spacing.space100))
//                                    StartErrorShareLogsButton()
                                }
                            }

                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.LoadWallet(
    identity: StoredIdentity,
    metadata: UserWalletMetadata,
    userPrefs: UserPrefs,
    promptScreenLockImmediately: Boolean,
    doLoadWallet: (StoredIdentity) -> Unit,
    goToWalletSelector: (() -> Unit)?
) {

    val isScreenLockRequired = produceState<Boolean?>(initialValue = null, key1 = identity) {
        val biometricLockEnabled = userPrefs.getLockBiometricsEnabled.first()
        val customPinLockEnabled = userPrefs.getLockPinEnabled.first()

        value = biometricLockEnabled || customPinLockEnabled
    }

    when (isScreenLockRequired.value) {
        null -> {
            LoadingDataIndicator(text = stringResource(Res.string.loading_preferences))
        }
        true -> {
            LoadingDataIndicator(text = stringResource(Res.string.unlock_to_continue))
            ScreenLockPrompt(
                walletId = identity.id,
                walletName = metadata.nameOrDefault(),
                promptScreenLockImmediately = promptScreenLockImmediately,
                onUnlock = { doLoadWallet(identity) },
                onLock = { },
                userPrefs = userPrefs,
                goToWalletSelector = goToWalletSelector,
            )
        }
        false -> {
            LoadingDataIndicator(text = stringResource(Res.string.opening_profile))
            LaunchedEffect(Unit) {
                doLoadWallet(identity)
            }
        }
    }
}


@Composable
private fun BoxScope.ScreenLockPrompt(
    walletId: WalletId,
    walletName: String,
    userPrefs: UserPrefs,
    promptScreenLockImmediately: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    goToWalletSelector: (() -> Unit)?,
) {
    Text(
        text = stringResource(Res.string.lock_prompt_coming_soon),
    )
//    val scope = rememberCoroutineScope()
//
//
//    val isBiometricLockEnabledState = userPrefs.getLockBiometricsEnabled.collectAsState(initial = null)
//    val isBiometricLockEnabled = isBiometricLockEnabledState.value
//    val isCustomPinLockEnabledState = userPrefs.getLockPinEnabled.collectAsState(initial = null)
//    val isCustomPinLockEnabled = isCustomPinLockEnabledState.value
//
//    val promptBiometricLock = {
//        val promptInfo = BiometricPrompt.PromptInfo.Builder().apply {
//            setTitle(context.getString(R.string.lockprompt_title))
//            setAllowedAuthenticators(BiometricsHelper.authCreds)
//        }.build()
//        BiometricsHelper.getPrompt(
//            activity = context.findActivity(),
//            onSuccess = {
//                scope.launch { userPrefs.saveLockPinCodeSuccess() }
//                onUnlock()
//            },
//            onFailure = { onLock() },
//            onCancel = { }
//        ).authenticate(promptInfo)
//    }
//
//    var showPinLockDialog by rememberSaveable { mutableStateOf(false) }
//    if (showPinLockDialog) {
//        CheckScreenLockPinFlow(
//            walletId = walletId,
//            onCancel = { showPinLockDialog = false },
//            onPinValid = { onUnlock() }
//        )
//    }
//
//    if (goToWalletSelector != null) {
////      TODO:  BackHandler(enabled = !showPinLockDialog) { goToWalletSelector() }
//        TransparentFilledButton(
////            icon = R.drawable.ic_arrow_back,
//            onClick = goToWalletSelector,
//            modifier = Modifier.align(Alignment.TopStart),
//            padding = PaddingValues(24.dp),
//        )
//    }
//
//    Column(
//        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.spacedBy(16.dp)
//    ) {
//        if (isBiometricLockEnabled == true) {
//            Button(
//                text = "System lock",
////                icon = R.drawable.ic_fingerprint,
//                onClick = promptBiometricLock,
//                modifier = Modifier.fillMaxWidth(),
//                backgroundColor = MaterialTheme.colors.surface,
//                shape = CircleShape,
//                padding = PaddingValues(16.dp),
//            )
//        }
//        if (isCustomPinLockEnabled == true) {
//            Button(
//                text = "PIN Code",
////                icon = R.drawable.ic_pin,
//                onClick = { showPinLockDialog = true },
//                modifier = Modifier.fillMaxWidth(),
//                backgroundColor = MaterialTheme.colors.surface,
//                shape = CircleShape,
//                padding = PaddingValues(16.dp),
//            )
//        }
//        Spacer(modifier = Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
//    }
//
//    when {
//        isBiometricLockEnabled == true && isCustomPinLockEnabled == false -> {
//            LaunchedEffect(Unit) {
//                promptBiometricLock()
//            }
//        }
//
//        isBiometricLockEnabled == false && isCustomPinLockEnabled == true -> {
//            LaunchedEffect(Unit) {
//                showPinLockDialog = true
//            }
//        }
//
//        promptScreenLockImmediately -> {
//            LaunchedEffect(key1 = isBiometricLockEnabled, key2 = isCustomPinLockEnabled, ) {
//                if (isBiometricLockEnabled == true) {
//                    promptBiometricLock()
//                } else if (isCustomPinLockEnabled == true) {
//                    showPinLockDialog = true
//                }
//            }
//        }
//        else -> {}
//    }
}