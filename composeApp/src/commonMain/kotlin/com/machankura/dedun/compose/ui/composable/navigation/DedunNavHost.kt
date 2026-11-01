package com.machankura.dedun.compose.ui.composable.navigation

import com.machankura.dedun.compose.ui.composable.GroupPayoutsScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutsRoute
import com.machankura.dedun.compose.ui.composable.DepositsScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.DepositsRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupAssignCoinsRoute
import com.machankura.dedun.compose.ui.composable.GroupAssignCoinsScreen
import com.machankura.dedun.compose.database.repository.DatabasePayoutRepository
import com.machankura.dedun.compose.database.repository.DatabaseDepositRepository
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import fr.acinq.phoenixd.conf.LSP
import fr.acinq.phoenix.db.createPaymentsDbDriver
import fr.acinq.phoenix.db.createSqlitePaymentsDb
import fr.acinq.phoenix.db.createChannelsDbDriver
import fr.acinq.phoenix.db.createSqliteChannelsDb
import fr.acinq.phoenix.managers.PhoenixDatabases
import fr.acinq.phoenix.managers.DatabaseManager
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.managers.GroupLightningNode
import com.machankura.dedun.compose.wallet.lightning.GroupLightningNodes
import com.machankura.dedun.compose.wallet.lightning.GroupLightningNodesStatusSource
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningWalletRoute
import com.machankura.dedun.compose.ui.composable.GroupLightningWalletScreen
import com.machankura.dedun.compose.ui.composable.GroupLightningReceiveScreen
import com.machankura.dedun.compose.ui.composable.GroupLightningSendScreen
import com.machankura.dedun.compose.ui.composable.GroupLightningLiquidityScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningLiquidityRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningSendRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningReceiveRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.IcebergCeremonyRoute
import com.machankura.dedun.compose.ui.composable.IcebergCeremonyScreen
import com.machankura.dedun.compose.database.repository.DatabaseGroupLightningRepository
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupChainClients
import com.machankura.dedun.compose.ui.composable.MemberWalletPolicyScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberWalletPolicyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutDestinationRoute
import com.machankura.dedun.compose.ui.composable.GroupPayoutDestinationScreen
import com.machankura.dedun.compose.database.repository.DatabasePayoutDestinationRepository
import com.machankura.dedun.compose.ui.composable.GroupSendScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupSendRoute
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.machankura.dedun.compose.AppLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.machankura.dedun.compose.DedunGlobal
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseDkgRepository
import com.machankura.dedun.compose.database.repository.DatabaseFrostSigningRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.database.repository.DatabaseSearchRepository
import com.machankura.dedun.compose.managers.AuxDatabaseManager
import com.machankura.dedun.compose.ui.composable.ActiveProfileScreen
import com.machankura.dedun.compose.ui.composable.AddMemberToChatRoomConfirmationScreen
import com.machankura.dedun.compose.ui.composable.ChatRoomCreationScreen
import com.machankura.dedun.compose.ui.composable.ChatRoomDetailScreen
import com.machankura.dedun.compose.ui.composable.ChatRoomMessagingScreen
import com.machankura.dedun.compose.ui.composable.CreateProfileScreen
import com.machankura.dedun.compose.ui.composable.DkgJoinApprovalScreen
import com.machankura.dedun.compose.ui.composable.CreateSharedKeyScreen
import com.machankura.dedun.compose.ui.composable.DkgRitualScreen
import com.machankura.dedun.compose.ui.composable.DkgRound1ApprovalScreen
import com.machankura.dedun.compose.ui.composable.DkgRound2ApprovalScreen
import com.machankura.dedun.compose.ui.composable.GroupWalletScreen
import com.machankura.dedun.compose.ui.composable.GroupWalletAddressesScreen
import com.machankura.dedun.compose.ui.composable.HomeScreen
import com.machankura.dedun.compose.ui.composable.ImplementationPendingScreen
import com.machankura.dedun.compose.ui.composable.KeyPackageManagementScreen
import com.machankura.dedun.compose.ui.composable.KeyRecoveryScreen
import com.machankura.dedun.compose.ui.composable.LandingScreen
import com.machankura.dedun.compose.ui.composable.LoadingScreen
import com.machankura.dedun.compose.ui.composable.NostrEventDetailScreen
import com.machankura.dedun.compose.ui.composable.NostrSecretScreen
import com.machankura.dedun.compose.ui.composable.RecoveryPhraseScreen
import com.machankura.dedun.compose.ui.composable.SearchMemberToAddToChatRoomScreen
import com.machankura.dedun.compose.ui.composable.SearchResultScreen
import com.machankura.dedun.compose.ui.composable.SearchScreen
import com.machankura.dedun.compose.ui.composable.SelectChatRoomMembersScreen
import com.machankura.dedun.compose.ui.composable.SelectChatRoomTypeScreen
import com.machankura.dedun.compose.ui.composable.ShareProfileScreen
import com.machankura.dedun.compose.ui.composable.SignInToProfileScreen
import com.machankura.dedun.compose.ui.composable.SocialPreconditionScreen
import com.machankura.dedun.compose.ui.composable.SovereignWalletStartupScreen
import com.machankura.dedun.compose.ui.composable.UnannouncedProfileScreen
import com.machankura.dedun.compose.ui.composable.UnindexedProfileScreen
import com.machankura.dedun.compose.ui.composable.UnqueuedProfileScreen
import com.machankura.dedun.compose.ui.composable.UnqueuedProfileSynchronizationScreen
import com.machankura.dedun.compose.ui.composable.UnsignedProfileScreen
import com.machankura.dedun.compose.ui.composable.UnsyncedProfileScreen
import com.machankura.dedun.compose.ui.composable.WriteNewNoteScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.ActiveProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.AddMemberToChatRoomConfirmationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.BlankRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomCreationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.CreateProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.DkgJoinApprovalRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.CreateSharedKeyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.DkgRitualRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.DkgRound1ApprovalRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.DkgRound2ApprovalRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.KeyPackageManagementRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.KeyRecoveryRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.LandingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.LoadingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.NostrSecretRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilesRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.RecoveryPhraseRoute
import com.machankura.dedun.compose.ui.composable.ProfilePreviewScreen
import com.machankura.dedun.compose.ui.composable.ProfilesScreen
import com.machankura.dedun.compose.ui.view.model.ProfilesViewModel
import com.machankura.dedun.compose.ui.view.model.getAvailableWalletsMeta
import com.machankura.dedun.compose.ui.view.model.bareKeyProfileWriter
import com.machankura.dedun.compose.ui.view.model.seedProfileWriter
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchMemberToAddToChatRoomRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchResultRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectChatRoomMembersRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import com.machankura.dedun.compose.ui.composable.SelectSubgroupAdminsScreen
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectChatRoomTypeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ShareProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SignInRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SocialPreconditionRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SovereignWalletStartupRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.UnannouncedProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.UnindexedProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.UnqueuedProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.UnqueuedProfileSynchronizationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.UnsignedProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.UnsyncedProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.WriteNewNoteRoute
import com.machankura.dedun.compose.ui.view.model.NavigationViewModel
import com.machankura.dedun.compose.ui.view.model.NotaryViewModel
import com.machankura.dedun.compose.ui.view.model.SovereignWalletViewModel
import com.machankura.dedun.compose.ui.view.model.SynchronizationViewModel
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import com.machankura.dedun.compose.ui.view.state.NostrEventDetailUIState
import com.machankura.dedun.compose.ui.view.state.SearchUIState
import com.machankura.dedun.compose.ui.theme.NavigationMotion
import com.machankura.dedun.compose.ui.composable.SignOutDependencies
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSigningCapability
import com.machankura.dedun.compose.ui.theme.breakpoint
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletAddressesRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.NewMemberWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberSendRoute
import com.machankura.dedun.compose.ui.composable.MemberSendScreen
import com.machankura.dedun.compose.ui.composable.MemberWalletScreen
import com.machankura.dedun.compose.ui.composable.NewMemberWalletScreen
import com.machankura.dedun.compose.database.repository.DatabaseMemberWalletRepository
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.lightning.GroupSwapInWallets
import com.machankura.dedun.compose.wallet.IdentityElectrum
import com.machankura.dedun.compose.wallet.GroupTransactionWatcher
import fr.acinq.phoenix.managers.NodeParamsManager
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProposalListRoute
import com.machankura.dedun.compose.ui.composable.FrostSigningScreen
import com.machankura.dedun.compose.ui.composable.ProposalListScreen

@Composable
fun DedunNavHost(
    dedunGlobal: DedunGlobal,
    phoenixGlobal: PhoenixGlobal,
    navController: NavHostController
) {
    val logger = Logger.withTag("DedunNavHost")

    val exceptionHandler = remember {
        CoroutineExceptionHandler { _, throwable ->
            logger.e("Caught exception: ${throwable.message}", throwable)
        }
    }

    val applicationIOScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler) }
    val applicationMainScope = remember { CoroutineScope(Dispatchers.Main) }

    val lifecycleOwner = LocalLifecycleOwner.current

    val auxDatabaseManager = remember {
        AuxDatabaseManager(dedunGlobal)
    }

    // Before the repositories: the nostr repository stamps every fetch request it queues
    // with the active identity, and takes the flow at construction.
    val sovereignWalletViewModel: SovereignWalletViewModel = viewModel(
        factory = SovereignWalletViewModel.factory(
            phoenixGlobal = phoenixGlobal
        )
    )

    val databaseNostrRepository = remember {
        DatabaseNostrRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
        )
    }

    val databaseChatRepository = remember {
        DatabaseChatRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
        )
    }

    val databaseDkgRepository = remember {
        DatabaseDkgRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val databaseGroupLightningRepository = remember {
        DatabaseGroupLightningRepository(database = auxDatabaseManager.auxDatabase)
    }

    val databaseFrostSigningRepository = remember {
        DatabaseFrostSigningRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val databaseMemberWalletRepository = remember {
        DatabaseMemberWalletRepository(
            database = auxDatabaseManager.auxDatabase,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
        )
    }

    // Who deposits with each group: docs/member-deposits.md.
    val databaseDepositRepository = remember {
        DatabaseDepositRepository(
            database = auxDatabaseManager.auxDatabase,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
        )
    }

    // Whose each coin is, and paying members out: docs/payouts-only.md.
    val databasePayoutRepository = remember {
        DatabasePayoutRepository(
            database = auxDatabaseManager.auxDatabase,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
        )
    }

    val databaseMarmotRepository = remember {
        DatabaseMarmotRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val searchRepository = remember {
        DatabaseSearchRepository(
            database = auxDatabaseManager.auxDatabase
        )
    }

    // The Electrum client this identity watches the chain on: its node's, or --
    // for an nsec or npub profile, which has no node -- one of its own. Remade
    // with the identity, the last one stopped first, because a switch of profile
    // is a restart and a wallet left subscribing on the last one's connection is
    // a wallet nobody can see. See docs/standalone-electrum.md.
    val activeIdentityForWallets by sovereignWalletViewModel.activeIdentity.collectAsState()
    val identityElectrum = remember {
        IdentityElectrum(IdentityElectrum.standalone(phoenixGlobal.networkMonitor, phoenixGlobal.loggerFactory))
    }
    DisposableEffect(identityElectrum) {
        onDispose { identityElectrum.stop() }
    }
    val electrum = remember(activeIdentityForWallets?.id, activeIdentityForWallets?.business) {
        identityElectrum.clientFor(activeIdentityForWallets)
    }

    // One Electrum connection per network: the identity's own -- its node's, or the one a
    // profile with no node is given -- for the node's network, and one of their own for any
    // other a group keeps a wallet on (testnet3, while a Lightning wallet is tried there).
    // Shared by the wallets and the watcher below, and remade with the identity's client.
    val groupChainClients = remember(electrum) {
        val identity = activeIdentityForWallets
        if (electrum == null || identity == null) null else GroupChainClients.forNode(NodeParamsManager.chain, electrum) { chain ->
            IdentityElectrum.standaloneOn(chain, identity, phoenixGlobal.networkMonitor, phoenixGlobal.loggerFactory)
        }
    }
    DisposableEffect(groupChainClients) {
        onDispose { groupChainClients?.let { clients -> applicationIOScope.launch { clients.stop() } } }
    }

    // The group wallets this identity is watching. Remade with the client.
    val groupOnchainWallets = remember(groupChainClients) {
        groupChainClients?.let {
            // Each depositor's branch, from the group's signed statements: docs/member-deposits.md.
            GroupOnchainWallets(it, applicationIOScope) { chatRoomId, chain ->
                Depositors.statementsIn(auxDatabaseManager.auxDatabase, chatRoomId, chain).mapValues { (_, depositor) -> depositor.branch }
            }
        }
    }
    DisposableEffect(groupOnchainWallets) {
        onDispose { groupOnchainWallets?.let { wallets -> applicationIOScope.launch { wallets.stop() } } }
    }

    // Each group Lightning wallet's swap-in addresses, every depositor's: docs/group-lightning-swap-in.md.
    // Every device watches them, so a member sees their own deposit arrive; the operator's node splices them in.
    val groupSwapInWallets = remember(groupChainClients) {
        groupChainClients?.let { GroupSwapInWallets(it, applicationIOScope, auxDatabaseManager.auxDatabase) }
    }
    DisposableEffect(groupSwapInWallets) {
        onDispose { groupSwapInWallets?.let { wallets -> applicationIOScope.launch { wallets.stop() } } }
    }

    // The group Lightning nodes this identity is the elected operator of. Behind the
    // rollout gate: nothing is built while it is off. Same lifetime as the wallets.
    // A group's node is not the identity's own, and needs only an Electrum client: so a
    // profile with no node -- an nsec or an npub, which has one now -- can run it too.
    val groupLightningNodes = remember(groupChainClients) {
        val clients = groupChainClients
        val identity = activeIdentityForWallets
        val user = identity?.nostrPublicKey
        if (!GroupLightningRollout.ENABLED || clients == null || identity == null || user == null) null else {
            val ctx = phoenixGlobal.ctx
            GroupLightningNodes(
                database = auxDatabaseManager.auxDatabase,
                userPublicKey = user,
                scope = applicationIOScope,
                peerFactory = GroupLightningNode.lightningKmpPeers(
                    clients = clients,
                    databasesFor = { nodeId, chain ->
                        PhoenixDatabases(
                            channels = createSqliteChannelsDb(createChannelsDbDriver(ctx, "group-" + DatabaseManager.channelsDbName(chain, nodeId))),
                            payments = createSqlitePaymentsDb(createPaymentsDbDriver(ctx, "group-" + DatabaseManager.paymentsDbName(chain, nodeId)) {}, null, phoenixGlobal.loggerFactory),
                            cloudKit = null,
                        )
                    },
                    // Phoenix's own choice of address: the LSP's onion while Tor is on, on the one network it has one.
                    walletParamsFor = { chain, tor ->
                        LSP.from(chain).walletParams.let { params ->
                            if (tor && chain == NodeParamsManager.chain) params.copy(trampolineNode = NodeParamsManager.trampolineNodeOnionUri) else params
                        }
                    },
                    loggerFactory = phoenixGlobal.loggerFactory,
                    scope = applicationIOScope,
                    torIsEnabled = { identity.userPrefs.getIsTorEnabled.first() },
                ),
                swapIns = groupSwapInWallets?.let { wallets -> { chatRoomId, wallet -> wallets.walletFor(chatRoomId, wallet) } },
            ).also { it.start() }
        }
    }
    // The screens read the nodes through this, since the nodes are built again as the chain clients change.
    val currentGroupLightningNodes = remember { MutableStateFlow<GroupLightningNodes?>(null) }
    val groupNodeStatusSource = remember { GroupLightningNodesStatusSource(currentGroupLightningNodes) }
    DisposableEffect(groupLightningNodes) {
        currentGroupLightningNodes.value = groupLightningNodes
        onDispose {
            currentGroupLightningNodes.value = null
            groupLightningNodes?.let { nodes -> applicationIOScope.launch { nodes.stop() } }
        }
    }

    // Where each group pays its members. After the wallets: the onchain check asks the
    // identity's current wallets, which come and go with its Electrum client.
    // docs/group-payout-destination.md.
    val currentGroupOnchainWallets by rememberUpdatedState(groupOnchainWallets)
    val databasePayoutDestinationRepository = remember {
        DatabasePayoutDestinationRepository(
            database = auxDatabaseManager.auxDatabase,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
            wallets = { currentGroupOnchainWallets },
        )
    }

    // Sends the group's signed spends to the network from this device too, and
    // follows them to a confirmation -- whether or not anybody opens the wallet.
    // Same lifetime as the wallets: the identity's client, and nothing longer.
    val groupTransactionWatcher = remember(groupChainClients) {
        groupChainClients?.let {
            GroupTransactionWatcher(auxDatabaseManager.auxDatabase, it, applicationIOScope).also { watcher -> watcher.start() }
        }
    }
    DisposableEffect(groupTransactionWatcher) {
        onDispose { groupTransactionWatcher?.stop() }
    }

    val navigationViewModel: NavigationViewModel =  viewModel (
        factory = NavigationViewModel.factory(
            activeIdentity = sovereignWalletViewModel.activeIdentity,
            initialNavigationUIState = NavigationUIState.Loading(),
            nostrRepository = databaseNostrRepository,
            scope = applicationIOScope
        )
    )

    val notaryViewModel: NotaryViewModel = viewModel(
        factory = NotaryViewModel.factory(
            activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
            nostrRepository = databaseNostrRepository,
            chatRepository = databaseChatRepository,
            marmotRepository = databaseMarmotRepository,
            scope = applicationIOScope
        )
    )

    // TODO: Produce a notary UI Element...
    val synchronizationViewModel: SynchronizationViewModel = viewModel(
        factory = SynchronizationViewModel.factory(
            activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
            nostrRepository = databaseNostrRepository,
            chatRepository = databaseChatRepository,
            relayRepository = databaseNostrRepository,
            scope = applicationIOScope
        )
    )

    // TODO: Produce a synchronization UI element...

    // The one place in the app that knows whether it is on screen. Everything that holds a
    // relay connection open reads AppLifecycle rather than a lifecycle owner, because those
    // consumers are application-scoped coroutines that outlive any composition.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> AppLifecycle.enteredForeground()
                Lifecycle.Event.ON_STOP -> AppLifecycle.enteredBackground()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lifecycleOwner) {
        navigationViewModel.navigationUIState.collect { state ->
            when (state) {
                is NavigationUIState.Error -> {
                    navController.navigate(
                        route = ImplementationPendingRoute(
                            "Error screen"
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.StartupPhoenix -> {
                    navController.navigate(
                        route = SovereignWalletStartupRoute
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.Loading -> {
                    navController.navigate(
                        route = LoadingRoute(
                            text = state.text
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.Landing -> {
                    navController.navigate(
                        route = LandingRoute
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnsignedProfile -> {
                    navController.navigate(
                        route = UnsignedProfileRoute(
                            state.unsignedNostrEvent.id
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnsyncedProfile -> {
                    navController.navigate(
                        route = UnsyncedProfileRoute(
                            state.unsignedNostrEvent.pubKey
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnqueuedProfileSynchronization -> {
                    navController.navigate(
                        route = UnqueuedProfileSynchronizationRoute(
                            unsignedNostrEventId = state.unsignedNostrEvent.id,
                            publicKey = state.unsignedNostrEvent.pubKey
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnindexedProfile -> {
                    navController.navigate(
                        route = UnindexedProfileRoute(
                            state.nostrEvent.id
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnqueuedProfile -> {
                    navController.navigate(
                        route = UnqueuedProfileRoute(
                            state.profile.nostrEventId
                        )
                    )
                }
                is NavigationUIState.UnannouncedProfile -> {
                    navController.navigate(
                        route = UnannouncedProfileRoute(
                            state.broadcastNostrEventRequest.nostrEventId
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.ProfileLoaded -> {
                    navController.navigate(
                        route = SocialPreconditionRoute(
                            activeUserPubkey = state.publicKey
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                else -> {
                    navController.navigate(
                        route = ImplementationPendingRoute(
                            "Unsupported $state"
                        )
                    ) {
                        popUpTo(0)
                    }
                }
            }
        }
    }

    // The navigation component's three items, and the two facts it needs to build their
    // routes.
    //
    // `activeUserPublicKey` is read off whichever top-level route is current -- all three
    // carry it -- rather than held here, so it cannot drift from the screen underneath.
    // The profile's metadata event id has no such source: `ActiveProfileRoute` is addressed
    // by event id and only the home screen ever knew one, so it is observed here for as
    // long as a key is signed in. One flow, cancelled and restarted when the key changes.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val activeUserPublicKey = currentBackStackEntry?.destination?.let { destination ->
        when (TopLevelDestination.of(destination)) {
            TopLevelDestination.Messages -> currentBackStackEntry?.toRoute<HomeRoute>()?.activeUserPublicKey
            TopLevelDestination.Search -> currentBackStackEntry?.toRoute<SearchRoute>()?.activeUserPublicKey
            TopLevelDestination.Profile -> currentBackStackEntry?.toRoute<ActiveProfileRoute>()?.activeUserPublicKey
            null -> null
        }
    }

    var activeProfileNostrEventId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeUserPublicKey) {
        val publicKey = activeUserPublicKey ?: return@LaunchedEffect
        databaseNostrRepository.observeProfileWithFollowing(publicKey).collect { profile ->
            activeProfileNostrEventId = profile?.nostrEvent?.id
        }
    }

    // What signing out of a read-only identity takes, for the two screens that offer it.
    // The tail is the nsec forget's: the device no longer holds anything for the
    // identity, so re-list -- the selector drops it -- then clear the active identity,
    // and the navigation observer sends a null identity to startup, which shows the
    // selector or, if this was the last one, Landing.
    val signOutOfReadOnlyIdentity = remember(sovereignWalletViewModel) {
        SignOutDependencies(
            activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
            forgetNostrCredential = { identity -> sovereignWalletViewModel.forgetNostrCredential(identity) },
            hideIdentityMetadata = { identity -> sovereignWalletViewModel.hideIdentityMetadata(identity) },
            onSignedOut = {
                sovereignWalletViewModel.forgetDefaultIdentity()
                sovereignWalletViewModel.listIdentities {
                    sovereignWalletViewModel.resetToSelector()
                }
            },
        )
    }

    // Once, above every screen: what a read-only identity may not be offered is decided
    // where the control is drawn, and this is how the control finds out.
    ProvideSigningCapability(sovereignWalletViewModel.activeIdentity) {
    DedunNavigationSuite(
        navController = navController,
        breakpoint = MaterialTheme.breakpoint,
        activeUserPublicKey = activeUserPublicKey,
        activeProfileNostrEventId = activeProfileNostrEventId,
    ) {
        NavHost(
            navController = navController,
            startDestination = LoadingRoute(),
            // All 43 routes at once, which is the point: navigation-compose's default is
            // `fadeIn(tween(700))` in its own internals, so before this the app's
            // transitions were a library's literal rather than a decision, and roughly
            // three times M3's duration for a full-screen change. These come from the
            // theme's MotionScheme and collapse to a fade when the platform asks for
            // reduced motion.
            enterTransition = NavigationMotion.enter(),
            exitTransition = NavigationMotion.exit(),
            popEnterTransition = NavigationMotion.popEnter(),
            popExitTransition = NavigationMotion.popExit(),
        ) {
            composable<SovereignWalletStartupRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SovereignWalletStartupRoute>()

                SovereignWalletStartupScreen(
                    sovereignWalletViewModel = sovereignWalletViewModel,
                    nostrRepository = databaseNostrRepository,
                    // No seed on this device, so there is no wallet to start: the user has to make or
                    // restore one, and that lives behind the landing screen. This used to park them on
                    // a loading screen with nothing left to load.
                    onNavigateToWalletLandingPage = {
                        navController.navigate(
                            route = LandingRoute
                        ) {
                            popUpTo(0)
                        }
                    },
                    onSuccessfulStartup = {
                        applicationIOScope.launch {
                            navigationViewModel.loadNostrProfile(route)
                        }
                    },
                    onNavigateToSignIn = {
                        navController.navigate(route = SignInRoute)
                    },
                    onNavigateToCreateProfile = {
                        navController.navigate(route = CreateProfileRoute(withWallet = false))
                    },
                    forceWalletId = null,
                )
            }
            composable<LoadingRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<LoadingRoute>()

                LoadingScreen(
                    text = route.text
                )
            }
            composable<LandingRoute> {
                LandingScreen(
                    onNavigateToLearnMore = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "Learn more"
                            )
                        )
                    },
                    onNavigateToSignIn = {
                        navController.navigate(
                            route = SignInRoute
                        )
                    },
                    // The first profile on a device is the one the wallet will belong to.
                    onNavigateToCreateProfile = {
                        navController.navigate(
                            route = CreateProfileRoute(withWallet = true)
                        )
                    }
                )
            }
            composable<CreateProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<CreateProfileRoute>()
                CreateProfileScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    nostrRepository = databaseNostrRepository,
                    marmotRepository = databaseMarmotRepository,
                    // A seed from Landing, a bare key from inside: the screen does not know
                    // which. See docs/multiple-profiles.md, Phase 5.
                    newProfile = if (route.withWallet) sovereignWalletViewModel.seedProfileWriter() else sovereignWalletViewModel.bareKeyProfileWriter(),
                    // The sign-in tail, with the popUpTo(0) it always had there: back from the
                    // new profile's startup must not return to a form whose secret is already
                    // on disk. The account rows are in the database before this runs, so the
                    // machine finds them the moment the identity is activated.
                    onProfileCreated = { walletId ->
                        sovereignWalletViewModel.loadSovereignData(walletId)
                        sovereignWalletViewModel.listIdentities {
                            sovereignWalletViewModel.switchToIdentity(walletId)
                            navController.navigate(
                                route = SovereignWalletStartupRoute
                            ) {
                                popUpTo(0)
                            }
                        }
                    },
                )
            }
            composable<ChatRoomCreationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoomCreationRoute>()

                ChatRoomCreationScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    onNavigateToRoute = { selectMembersRoute ->
                        navController.navigate(
                            selectMembersRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<SelectChatRoomMembersRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectChatRoomMembersRoute>()

                SelectChatRoomMembersScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    name = route.name,
                    description = route.description,
                    nostrRepository = databaseNostrRepository,
                    onNavigateToRoute = { selectChatRoomTypeRoute ->
                        navController.navigate(
                            selectChatRoomTypeRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<SelectSubgroupAdminsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectSubgroupAdminsRoute>()

                SelectSubgroupAdminsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    parentChatRoomId = route.parentChatRoomId,
                    retrying = route.retrying,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { nextRoute ->
                        navController.navigate(route = nextRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupLightningWalletRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupLightningWalletRoute>()

                GroupLightningWalletScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    lightningRepository = databaseGroupLightningRepository,
                    nodeStatusSource = groupNodeStatusSource,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupLightningReceiveRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupLightningReceiveRoute>()

                GroupLightningReceiveScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chatRepository = databaseChatRepository,
                    lightningRepository = databaseGroupLightningRepository,
                    depositRepository = databaseDepositRepository,
                    onNavigateToRoute = { actionRoute -> navController.navigate(route = actionRoute) },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupLightningLiquidityRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupLightningLiquidityRoute>()

                GroupLightningLiquidityScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chatRepository = databaseChatRepository,
                    lightningRepository = databaseGroupLightningRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupLightningSendRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupLightningSendRoute>()

                GroupLightningSendScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chatRepository = databaseChatRepository,
                    lightningRepository = databaseGroupLightningRepository,
                    initialInvoice = route.invoice,
                    payout = if (route.payoutMember != null && route.payoutDestination != null) {
                        GroupLightningAuthorisationEvent.Payout(route.payoutMember, route.payoutDestination)
                    } else null,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<IcebergCeremonyRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<IcebergCeremonyRoute>()

                IcebergCeremonyScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    sessionId = route.sessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    lightningRepository = databaseGroupLightningRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<CreateSharedKeyRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<CreateSharedKeyRoute>()

                CreateSharedKeyScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    // Replaces this screen with the ceremony, whether it was just
                    // opened or was already there, so back from the ceremony goes
                    // wherever the member came from rather than to a second offer.
                    onCeremonyOpened = { dkgSessionId ->
                        navController.navigate(
                            DkgRitualRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                chatRoomId = route.chatRoomId,
                                dkgSessionId = dkgSessionId
                            )
                        ) {
                            popUpTo<CreateSharedKeyRoute> { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable<DkgRitualRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRitualRoute>()

                DkgRitualScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { approvalRoute ->
                        navController.navigate(route = approvalRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<DkgJoinApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgJoinApprovalRoute>()

                DkgJoinApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable<DkgRound1ApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRound1ApprovalRoute>()

                DkgRound1ApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable<DkgRound2ApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRound2ApprovalRoute>()

                DkgRound2ApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable<SelectChatRoomTypeRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectChatRoomTypeRoute>()

                SelectChatRoomTypeScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    name = route.name,
                    description = route.description,
                    memberPublicKeys = route.memberPublicKeys,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { chatRoomResultRoute ->
                        navController.navigate(
                            chatRoomResultRoute
                        ) {
                            // Drop every creation step: backing out of the new chat should land
                            // on whatever the user was doing before, not back in the form.
                            popUpTo(
                                ChatRoomCreationRoute(
                                    activeUserPublicKey = route.activeUserPublicKey
                                )
                            ) {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<WriteNewNoteRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<WriteNewNoteRoute>()
                WriteNewNoteScreen(
                    replyToNostrEventId = route.inReplyToEventId,
                    activeUserPublicKey = route.activeUserPublicKey,
                    quotedNostrEventId = route.quotedEventId,
                    onNostrEventPublished = {
                        applicationMainScope.launch {
                            navController.popBackStack()
                        }
                    },
                    onNavigateToNostrEvent = { hexKey ->
                        navController.navigate(
                            route = NostrEventDetailRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                nostrEventId = hexKey
                            )
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    nostrRepository = databaseNostrRepository
                )
            }
            composable<SignInRoute> {
                SignInToProfileScreen(
                    nostrRepository = databaseNostrRepository,
                    writeNostrKey = { privateKey -> sovereignWalletViewModel.writeNostrKey(privateKey) },
                    writeNostrPublicKey = { publicKey -> sovereignWalletViewModel.writeNostrPublicKey(publicKey) },
                    writeRecoveryPhrase = { words, onWritten, onError ->
                        sovereignWalletViewModel.writeSeed(
                            words,
                            isRestoringWallet = true,
                            onSeedWritten = onWritten,
                            onSeedWriteError = onError,
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    // The tail the create flow uses after writeSeed: re-list, select, go to
                    // startup. Startup finds the new identity, activates it, and the navigation
                    // machine takes it from the placeholder account the sign-in planted. The
                    // form is popped so that back does not return to a field holding a secret.
                    //
                    // The navigate here looks redundant with the observer's -- switching clears
                    // the identity, and a null identity is StartupPhoenix, which the observer
                    // above navigates to with the same popUpTo(0). It is not: the navigation
                    // state is a StateFlow, and an update to a state equal to the current one
                    // emits nothing. This is what guarantees the stack moves even on the day
                    // the state does not. Leave both.
                    onSignedIn = { walletId ->
                        sovereignWalletViewModel.loadSovereignData(walletId)
                        sovereignWalletViewModel.listIdentities {
                            sovereignWalletViewModel.switchToIdentity(walletId)
                            navController.navigate(
                                route = SovereignWalletStartupRoute
                            ) {
                                popUpTo(0)
                            }
                        }
                    },
                )
            }
            composable<UnsignedProfileRoute> { backStackEntry ->
                backStackEntry.toRoute<UnsignedProfileRoute>()

                UnsignedProfileScreen()
            }
            composable<UnsyncedProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<UnsyncedProfileRoute>()
                val identities by sovereignWalletViewModel.availableIdentities.collectAsState()
                UnsyncedProfileScreen(
                    unsyncedProfilePublicKey = route.publicKey,
                    nostrRepository = databaseNostrRepository,
                    signOut = signOutOfReadOnlyIdentity,
                    // Another profile to go to, when there is one; the switcher has a back
                    // button, so this screen is still there if the user changes their mind.
                    onSwitchProfile = if (identities.size > 1) {
                        { navController.navigate(route = ProfilesRoute) }
                    } else null,
                )
            }
            composable<UnqueuedProfileSynchronizationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<UnqueuedProfileSynchronizationRoute>()
                UnqueuedProfileSynchronizationScreen(
                    unsignedNostrEventId = route.unsignedNostrEventId,
                    profilePublicKey = route.publicKey,
                    nostrRepository = databaseNostrRepository
                )
            }
            composable<UnindexedProfileRoute> {
                UnindexedProfileScreen()
            }
            composable<UnqueuedProfileRoute> {
                UnqueuedProfileScreen()
            }
            composable<UnannouncedProfileRoute> { backStackEntry ->
                backStackEntry.toRoute<UnannouncedProfileRoute>()

                UnannouncedProfileScreen()
            }
            composable<SocialPreconditionRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SocialPreconditionRoute>()
                SocialPreconditionScreen(
                    onNavigateToSkipForNow = {
                        navController.navigate(
                            route = HomeRoute(
                                activeUserPublicKey = route.activeUserPubkey
                            )
                        )
                    },
                    onNavigateToInviteFriend = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "Invite friend"
                            )
                        )
                    },
                    onNavigateToViewInvites = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "View invites"
                            )
                        )
                    }
                )
            }
            composable<HomeRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<HomeRoute>()
                HomeScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    onNavigateToRoute = { eventRoute ->
                        navController.navigate(
                            route = eventRoute
                        )
                    },
                    onNavigateToChatRoomCreation = {
                        navController.navigate(
                            route = ChatRoomCreationRoute(
                                activeUserPublicKey = route.activeUserPublicKey
                            )
                        )
                    },
                    onNavigateToDirectMessageDetail = { chatRoomDetailRoute ->
                        navController.navigate(
                            route = chatRoomDetailRoute
                        )
                    },
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    dkgRepository = databaseDkgRepository,
                    lightningRepository = databaseGroupLightningRepository,
                )
            }
            composable<BlankRoute> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // Nothing is drawn on it, so this is a backdrop rather than a surface
                    // carrying content. `scrim` is the role for that, and is #000000 in every
                    // one of this app's schemes -- so the pixels are unchanged and the value
                    // now moves with the theme instead of standing outside it.
                    color = MaterialTheme.colorScheme.scrim
                ) {

                }
            }
            composable<ActiveProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ActiveProfileRoute>()

                ActiveProfileScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    nostrEventId = route.nostrEventId,
                    nostrRepository = databaseNostrRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    },
                    signOut = signOutOfReadOnlyIdentity,
                    memberWalletRepository = databaseMemberWalletRepository,
                    wallets = groupOnchainWallets,
                )
            }
            composable<ShareProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ShareProfileRoute>()

                ShareProfileScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    nostrEventId = route.nostrEventId,
                    nostrRepository = databaseNostrRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<KeyPackageManagementRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<KeyPackageManagementRoute>()

                KeyPackageManagementScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    nostrEventId = route.nostrEventId,
                    marmotRepository = databaseMarmotRepository,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<ProfilesRoute> {
                val profilesViewModel: ProfilesViewModel = viewModel(
                    factory = ProfilesViewModel.factory(
                        listWalletState = sovereignWalletViewModel.listWalletState,
                        availableIdentities = sovereignWalletViewModel.availableIdentities,
                        activeIdentity = sovereignWalletViewModel.activeIdentity,
                        walletsMetadata = getAvailableWalletsMeta(phoenixGlobal),
                        nostrRepository = databaseNostrRepository,
                    ),
                )
                ProfilesScreen(
                    viewModel = profilesViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    // The switch is the whole of it: the observer above sends the null
                    // identity to startup, and startup opens the one named.
                    onSwitch = { walletId ->
                        sovereignWalletViewModel.switchToIdentity(walletId)
                    },
                    onRetry = {
                        sovereignWalletViewModel.listIdentities {}
                    },
                    onSignIn = {
                        navController.navigate(route = SignInRoute)
                    },
                    onCreateProfile = {
                        navController.navigate(route = CreateProfileRoute(withWallet = false))
                    },
                )
            }
            composable<KeyRecoveryRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<KeyRecoveryRoute>()

                KeyRecoveryScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<RecoveryPhraseRoute> {
                RecoveryPhraseScreen(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<NostrSecretRoute> {
                NostrSecretScreen(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    nostrRepository = databaseNostrRepository,
                    forgetNostrCredential = { identity -> sovereignWalletViewModel.forgetNostrCredential(identity) },
                    hideIdentityMetadata = { identity -> sovereignWalletViewModel.hideIdentityMetadata(identity) },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    // The device no longer holds the key. Re-list so the selector drops it,
                    // then clear the active identity: the navigation observer sends a null
                    // identity to startup, which shows the selector -- or Landing, if this
                    // was the last one.
                    onForgotten = {
                        sovereignWalletViewModel.forgetDefaultIdentity()
                        sovereignWalletViewModel.listIdentities {
                            sovereignWalletViewModel.resetToSelector()
                        }
                    },
                )
            }
            composable<ChatRoomMessagingRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoomMessagingRoute>()

                ChatRoomMessagingScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    dkgRepository = databaseDkgRepository,
                    lightningRepository = databaseGroupLightningRepository,
                    onNavigateToRouteAndPopUpInclusive = { chatRoomDetailRoute ->
                        navController.navigate(
                            route = chatRoomDetailRoute
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    // A pushed route, unlike the same screen inside the home screen's
                    // detail pane, so there is something to pop.
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<ChatRoomDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoomDetailRoute>()

                ChatRoomDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    memberWalletRepository = databaseMemberWalletRepository,
                    payoutDestinationRepository = databasePayoutDestinationRepository,
                    depositRepository = databaseDepositRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onPopBackToRoute = { popRoute ->
                        navController.popBackStack(
                            route = popRoute,
                            inclusive = false
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<SearchMemberToAddToChatRoomRoute> {  backStackEntry ->
                val route = backStackEntry.toRoute<SearchMemberToAddToChatRoomRoute>()

                SearchMemberToAddToChatRoomScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    onNavigateToRoute = { chatRoomDetailRoute ->
                        navController.navigate(
                            route = chatRoomDetailRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                )
            }
            composable<AddMemberToChatRoomConfirmationRoute> {  backStackEntry ->
                val route = backStackEntry.toRoute<AddMemberToChatRoomConfirmationRoute>()

                AddMemberToChatRoomConfirmationScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    profilePublicKey = route.profilePublicKey,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    onInviteSent = {
                        navController.popBackStack(
                            route = SearchMemberToAddToChatRoomRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                chatRoomId = route.chatRoomId,
                                relayHint = route.relayHint
                            ),
                            inclusive = true
                        )
                    },
                    // Back is to the search, not past it: a member who picked the wrong
                    // person wants the list again, not the room.
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                )
            }
            composable<GroupWalletRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupWalletRoute>()
                // Null only with no identity, for the frame between a profile
                // switch and the startup screen: there is no chain to show yet.
                val wallets = groupOnchainWallets ?: return@composable

                GroupWalletScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    wallets = wallets,
                    watcher = groupTransactionWatcher,
                    depositRepository = databaseDepositRepository,
                    payoutRepository = databasePayoutRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<MemberWalletRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<MemberWalletRoute>()
                val wallets = groupOnchainWallets ?: return@composable

                MemberWalletScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    walletId = route.walletId,
                    memberWalletRepository = databaseMemberWalletRepository,
                    wallets = wallets,
                    onSend = { walletId ->
                        navController.navigate(route = MemberSendRoute(activeUserPublicKey = route.activeUserPublicKey, walletId = walletId))
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<MemberSendRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<MemberSendRoute>()
                val wallets = groupOnchainWallets ?: return@composable

                MemberSendScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    walletId = route.walletId,
                    chain = NodeParamsManager.chain,
                    memberWalletRepository = databaseMemberWalletRepository,
                    wallets = wallets,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupPayoutsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupPayoutsRoute>()

                GroupPayoutsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    payoutRepository = databasePayoutRepository,
                    payoutDestinationRepository = databasePayoutDestinationRepository,
                    wallets = groupOnchainWallets,
                    lightningRepository = databaseGroupLightningRepository,
                    onNavigateToRoute = { actionRoute -> navController.navigate(route = actionRoute) },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupAssignCoinsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupAssignCoinsRoute>()

                GroupAssignCoinsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    payoutRepository = databasePayoutRepository,
                    wallets = groupOnchainWallets,
                    onNavigateToRoute = { actionRoute -> navController.navigate(route = actionRoute) },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<DepositsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DepositsRoute>()

                DepositsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    depositRepository = databaseDepositRepository,
                    onNavigateToRoute = { actionRoute -> navController.navigate(route = actionRoute) },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupPayoutDestinationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupPayoutDestinationRoute>()

                GroupPayoutDestinationScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    repository = databasePayoutDestinationRepository,
                    depositRepository = databaseDepositRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the form, so back returns to where the member came from rather
                        // than to a destination already saved.
                        navController.navigate(route = signingRoute) {
                            popUpTo<GroupPayoutDestinationRoute> { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<MemberWalletPolicyRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<MemberWalletPolicyRoute>()

                MemberWalletPolicyScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chatRepository = databaseChatRepository,
                    memberWalletRepository = databaseMemberWalletRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<NewMemberWalletRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<NewMemberWalletRoute>()

                NewMemberWalletScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = NodeParamsManager.chain,
                    memberWalletRepository = databaseMemberWalletRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupWalletAddressesRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupWalletAddressesRoute>()
                val wallets = groupOnchainWallets ?: return@composable

                GroupWalletAddressesScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    wallets = wallets,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupSendRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupSendRoute>()
                val wallets = groupOnchainWallets ?: return@composable

                GroupSendScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = route.chain?.let(GroupWalletStatementEvent::chainNamed) ?: NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    wallets = wallets,
                    guardedChatRoomId = route.guardedChatRoomId,
                    fundChatRoomId = route.fundChatRoomId,
                    initialAddress = route.address,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the form so back returns to the wallet rather
                        // than to a spend that has already been proposed.
                        navController.navigate(route = signingRoute) {
                            popUpTo<GroupSendRoute> { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<FrostSigningRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<FrostSigningRoute>()

                FrostSigningScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    sessionId = route.sessionId,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    payoutDestinationRepository = databasePayoutDestinationRepository,
                    payoutRepository = databasePayoutRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<ProposalListRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ProposalListRoute>()

                ProposalListScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { signingRoute ->
                        navController.navigate(route = signingRoute)
                    },
                    initialFilter = route.filter
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SearchRoute>()

                SearchScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    initialSearchUIState = SearchUIState.Prompt,
                    nostrRepository = databaseNostrRepository,
                    searchRepository = searchRepository,
                    onNavigateToProfile = { route ->
                        navController.navigate(
                            route = route
                        )
                    },
                    onNavigateToSearchResult = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<SearchResultRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SearchResultRoute>()

                SearchResultScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    searchQuery = route.query,
                    nostrRepository = databaseNostrRepository,
                    searchRepository = searchRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToEvent = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<NostrEventDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<NostrEventDetailRoute>()

                NostrEventDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    initialNostrEventDetailUIState = NostrEventDetailUIState.Loading,
                    nostrEventId = route.nostrEventId,
                    nostrRepository = databaseNostrRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToEvent = { route ->
                        navController.navigate(
                            route = route
                        )
                    },
                    onNavigateToWriteAReply = { nostrEventId ->
                        navController.navigate(
                            route = WriteNewNoteRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                inReplyToEventId = nostrEventId
                            )
                        )
                    },
                    onNavigateToEditProfile = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "Edit profile"
                            )
                        )
                    },
                    onNavigateToQuoteNostrEvent = { nostrEventId ->
                        navController.navigate(
                            route = WriteNewNoteRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                quotedEventId = nostrEventId
                            )
                        )
                    },
                    onNavigateToDirectMessage = { chatRoom ->
                        navController.navigate(
                            route = chatRoom
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                )
            }
            composable<ProfilePreviewRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ProfilePreviewRoute>()

                ProfilePreviewScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    profilePublicKey = route.profilePublicKey,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    memberWalletRepository = databaseMemberWalletRepository,
                    onNavigateToRoute = { actionRoute -> navController.navigate(route = actionRoute) },
                    // The room replaces the preview, as it replaces the profile detail
                    // above: back from a chat is the list, not the person it was started
                    // from.
                    onStartChat = { chatRoom ->
                        navController.navigate(
                            route = chatRoom
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                )
            }
            composable<ImplementationPendingRoute> { backStackEntry ->
                val route: ImplementationPendingRoute = backStackEntry.toRoute()

                // Pushed from a "learn more" or an "edit profile", and also where the
                // navigation observer above lands with `popUpTo(0)` on an error. Whether
                // there is anything under this entry is what decides the back button, and
                // it is remembered because the answer is read again while the screen is
                // leaving, when the stack has already moved on.
                val hasSomethingToReturnTo = remember {
                    navController.previousBackStackEntry != null
                }

                ImplementationPendingScreen(
                    text = route.name,
                    onNavigateBack = if (hasSomethingToReturnTo) {
                        { navController.popBackStack() }
                    } else {
                        null
                    }
                )
            }
        }
    }
    }

}