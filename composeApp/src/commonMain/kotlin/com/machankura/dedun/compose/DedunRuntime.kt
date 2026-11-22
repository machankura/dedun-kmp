package com.machankura.dedun.compose

import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import com.machankura.dedun.compose.identity.DefaultIdentity
import com.machankura.dedun.compose.managers.BackgroundSyncPass
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.notifications.NotificationDispatcher
import com.machankura.dedun.compose.notifications.NotificationResources
import com.machankura.dedun.compose.notifications.PlatformNotifier
import com.machankura.dedun.compose.notifications.VisibleConversation
import com.machankura.dedun.compose.notifications.platformNotifier
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseDkgRepository
import com.machankura.dedun.compose.database.repository.DatabaseFrostSigningRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseGroupInboxRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.database.repository.DatabaseSearchRepository
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.AuxDatabaseManager
import com.machankura.dedun.compose.ui.view.model.NotaryViewModel
import com.machankura.dedun.compose.ui.view.model.SynchronizationViewModel

/**
 * Everything the app runs on that is not a screen: the database, the repositories over it, the
 * relay pool and the engines that sync and send. One per process.
 *
 * It used to be built in `remember { }` blocks inside `DedunNavHost`, which meant nothing could run
 * without a UI -- a background job had no database or relay pool to use unless it built a second
 * one, and a second one is two Room instances on `aux.db` that cannot see each other's writes, two
 * sockets per relay, and two writers ratcheting one room's MLS state. It also meant an Android
 * activity recreated over a living process built fresh repositories beside the view models that
 * held on to the old ones. See docs/notifications-and-background-sync.md, D4.
 *
 * Created by whatever owns the process: `DedunApplication` on Android, `MainViewController` on iOS,
 * `main()` on desktop once the passphrase is in. Everything else asks [current] or [getOrCreate].
 *
 * In Dedun the wallets' own objects -- the Electrum clients, the group Lightning nodes, the
 * chain watchers and the Wavelength processes, and the repositories only they and the wallet
 * screens read -- are still built in `DedunNavHost`: they follow the running node, and nothing
 * that runs without a screen needs them yet. A background pass files a signing proposal through
 * `storeNostrEvent` like any other event. docs/giza-second-batch.md, Phase 3.
 *
 * The two engines are still `ViewModel`s by name. Neither ever used `viewModelScope` -- both run on
 * the [ioScope] they are given -- so constructing them here rather than through a `ViewModelStore`
 * changes nothing about how they run, only that there is one of each.
 */
class DedunRuntime(
    val dedunGlobal: DedunGlobal,
    val phoenixGlobal: PhoenixGlobal,
) {
    private val logger = Logger.withTag(TAG)

    val ioScope: CoroutineScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            logger.e("Caught exception: ${throwable.message}", throwable)
        }
    )

    /**
     * The identity the app is running as. Written by `SovereignWalletViewModel`, read by every
     * repository and engine below. Owned here rather than by that view model so that the engines,
     * which live as long as the process, are not left holding a flow an old activity owned.
     */
    val activeIdentity: MutableStateFlow<Identity?> = MutableStateFlow(null)

    val auxDatabaseManager: AuxDatabaseManager = AuxDatabaseManager(dedunGlobal)

    val nostrRepository: DatabaseNostrRepository by lazy {
        DatabaseNostrRepository(database = auxDatabaseManager.auxDatabase, ioScope, activeIdentity = activeIdentity)
    }

    val chatRepository: DatabaseChatRepository by lazy {
        DatabaseChatRepository(database = auxDatabaseManager.auxDatabase, ioScope, activeIdentity = activeIdentity)
    }

    val dkgRepository: DatabaseDkgRepository by lazy {
        DatabaseDkgRepository(database = auxDatabaseManager.auxDatabase, ioScope)
    }

    val frostSigningRepository: DatabaseFrostSigningRepository by lazy {
        DatabaseFrostSigningRepository(database = auxDatabaseManager.auxDatabase, ioScope)
    }

    val groupDmRepository: com.machankura.dedun.compose.database.repository.DatabaseGroupDmRepository by lazy {
        com.machankura.dedun.compose.database.repository.DatabaseGroupDmRepository(database = auxDatabaseManager.auxDatabase, activeIdentity = activeIdentity)
    }

    val groupInboxRepository: DatabaseGroupInboxRepository by lazy {
        DatabaseGroupInboxRepository(database = auxDatabaseManager.auxDatabase, ioScope, activeIdentity = activeIdentity)
    }

    val marmotRepository: DatabaseMarmotRepository by lazy {
        DatabaseMarmotRepository(database = auxDatabaseManager.auxDatabase, ioScope)
    }

    val searchRepository: DatabaseSearchRepository by lazy {
        DatabaseSearchRepository(database = auxDatabaseManager.auxDatabase)
    }

    /** Sends what this device has queued: seals gift wraps, encrypts Marmot events, signs. */
    val notary: NotaryViewModel by lazy {
        NotaryViewModel(
            activeIdentityStateFlow = activeIdentity,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            marmotRepository = marmotRepository,
            scope = ioScope,
        )
    }

    /** The relay pool, the request pumps and the live subscriptions. */
    val synchronization: SynchronizationViewModel by lazy {
        SynchronizationViewModel(
            activeIdentityStateFlow = activeIdentity,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            relayRepository = nostrRepository,
            scope = ioScope,
            groupInboxRepository = groupInboxRepository,
            groupDmRepository = groupDmRepository,
        )
    }

    /** Where notifications are shown, on this platform. */
    val notifier: PlatformNotifier by lazy { platformNotifier(dedunGlobal.platformContext) }

    /** New lines into notifications, for the identity running. docs/notifications-and-background-sync.md. */
    val notificationDispatcher: NotificationDispatcher by lazy {
        NotificationDispatcher(
            database = auxDatabaseManager.auxDatabase,
            notifier = notifier,
            strings = { NotificationResources.strings() },
            appVisible = AppLifecycle.isVisible,
            visibleChatRoomId = VisibleConversation.chatRoomId,
            syncPending = { publicKey ->
                nostrRepository.observePendingNegentropySynchronizeRequests(publicKey).map { it != null }
            },
        )
    }

    /**
     * The dispatcher, running for whichever identity is active and holds a key -- a public-key
     * identity can open nothing, so has nothing to be told about (D6). Started on first read.
     */
    val notifications: Job by lazy {
        ioScope.launch {
            runCatching { notifier.prepare(NotificationResources.channelNames()) }
                .onFailure { logger.e("Could not prepare notification channels", it) }
            activeIdentity.collectLatest { identity ->
                if (identity?.nostrPrivateKey == null) return@collectLatest
                notificationDispatcher.run(identity.nostrPublicKey)
            }
        }
    }

    /**
     * One bounded sync while off screen, run by the platform's scheduler: WorkManager, a
     * BGTaskScheduler task. docs/notifications-and-background-sync.md, Phase 9.
     */
    val backgroundSync: BackgroundSyncPass by lazy {
        BackgroundSyncPass(
            activeIdentity = activeIdentity,
            loadDefaultIdentity = { DefaultIdentity.load(phoenixGlobal) },
            startEngines = {
                synchronization
                notary
                notifications
            },
            syncPending = { publicKey ->
                nostrRepository.observePendingNegentropySynchronizeRequests(publicKey).map { it != null }
            },
            outboundQueued = { publicKey ->
                combine(
                    chatRepository.observeUnsealedGiftWrapPayloads(publicKey).map { it.size },
                    marmotRepository.observeUnprocessedMarmotInnerEvents(publicKey).map { it.size },
                    nostrRepository.observeUnsignedNostrEvents(publicKey).map { it.size },
                    nostrRepository.observePendingBroadcastNostrEventRequests().map { if (it != null) 1 else 0 },
                ) { seals, inner, unsigned, broadcast -> seals + inner + unsigned + broadcast }
            },
            dispatcher = notificationDispatcher,
            ledger = auxDatabaseManager.auxDatabase.notificationDao(),
        )
    }

    private var graceJob: Job? = null

    /**
     * The app left the screen. Where the platform grants a moment (iOS), the connections are held
     * for it, inside a background task, so what was queued can still be sent. Phase 8.
     */
    fun enteredBackground() {
        AppLifecycle.enteredBackground()
        if (platformBackgroundGrace <= Duration.ZERO) return
        graceJob?.cancel()
        graceJob = ioScope.launch {
            inPlatformBackgroundTask("dedun-grace") {
                AppLifecycle.keepConnected(AppLifecycle.KEEP_GRACE)
                try {
                    delay(platformBackgroundGrace)
                } finally {
                    AppLifecycle.release(AppLifecycle.KEEP_GRACE)
                }
            }
        }
    }

    fun enteredForeground() {
        graceJob?.cancel()
        graceJob = null
        AppLifecycle.release(AppLifecycle.KEEP_GRACE)
        AppLifecycle.enteredForeground()
    }

    companion object {
        private const val TAG = "DedunRuntime"

        @OptIn(ExperimentalAtomicApi::class)
        private val instance = ProcessSingleton<DedunRuntime>()

        /** The runtime, if whatever owns the process has made it. */
        fun current(): DedunRuntime? = instance.current()

        /** The runtime, made by [create] if nothing has made it yet. [create] runs at most once. */
        fun getOrCreate(create: () -> DedunRuntime): DedunRuntime = instance.getOrCreate(create)
    }
}

/**
 * One value per process, made on first ask. A race between two first asks builds two and keeps
 * whichever was published first; the loser is dropped before anything has been started on it, which
 * is only true because [DedunRuntime] starts nothing in its constructor -- every engine is lazy.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ProcessSingleton<T : Any> {
    private val reference = AtomicReference<T?>(null)

    fun current(): T? = reference.load()

    fun getOrCreate(create: () -> T): T {
        reference.load()?.let { return it }
        val made = create()
        return if (reference.compareAndSet(null, made)) made else reference.load()!!
    }
}
