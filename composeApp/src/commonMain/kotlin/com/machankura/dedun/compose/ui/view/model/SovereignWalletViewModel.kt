package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.lightning.logging.error
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.PhoenixGlobal
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.DecryptNostrCredentialsResult
import fr.acinq.phoenix.data.DecryptNostrKeysResult
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.data.ListWalletState
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.SeedCredentials
import com.machankura.dedun.compose.identity.StoredIdentity

sealed class WritingSeedState {
    data object Init : WritingSeedState()
    data class Writing(val mnemonics: List<String>) : WritingSeedState()
    data class WrittenToDisk(val walletId: WalletId) : WritingSeedState()
    sealed class Error : WritingSeedState() {
        data class Generic(val cause: Throwable) : Error()
        data object CannotLoadSeedMap: Error()
        /** [id] is the wallet's, so that a refusal from inside the app can offer to switch to it. */
        data class SeedAlreadyExists(val id: WalletId): Error()
    }
}

expect fun updateBusinessActiveInUI(walletId: WalletId)

/**
 * Stops the node running for [walletId], if one is. The inverse of what the startup
 * screen's mnemonic branch starts; an `expect` for the reason [updateBusinessActiveInUI]
 * is, which is that `BusinessManager` is a per-platform object.
 */
expect fun stopPlatformBusiness(walletId: WalletId)
expect fun loadAndDecryptSeed(phoenixGlobal: PhoenixGlobal): DecryptSeedResult

expect fun getAvailableWalletsMeta(phoenixGlobal: PhoenixGlobal): Flow<Map<WalletId, UserWalletMetadata>>

expect suspend fun saveAvailableWalletMeta(phoenixGlobal: PhoenixGlobal, metadata: UserWalletMetadata)

expect suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    walletId: WalletId,
    name: String?,
    avatar: String,
    isHidden: Boolean
)

class SovereignWalletViewModel(
    val phoenixGlobal: PhoenixGlobal,
    // We might end up only using the machankuraWalletRepository in the future where we send the walletId in each request.
    /** Injected so that a switch can be pinned in a test without a node to stop. */
    private val stopBusiness: (WalletId) -> Unit = ::stopPlatformBusiness,
): ViewModel() {
    private val log = Logger.withTag("SovereignWalletViewModel")

    // A StateFlow, not a `mutableStateOf`, and it has to stay one. This view model is built by
    // `viewModel()` during composition, so a Compose state created in its constructor is created
    // inside that composition's snapshot -- and a write from another thread that lands before the
    // composition is applied is discarded. `listIdentities` runs from `init` and, on a device
    // with no seed file, answers in about four milliseconds, which is squarely inside that window:
    // the write to Success was lost and the app sat on "Decrypting..." for ever. A StateFlow has no
    // snapshot to belong to, so the same write from the same thread at the same moment is seen.
    private val _listWalletState = MutableStateFlow<ListWalletState>(ListWalletState.Init)
    val listWalletState = _listWalletState.asStateFlow()

    /**
     * Every profile this device holds something for -- the credentials file, with each
     * seed attached to the profile its key derives -- keyed by the id the preferences
     * and metadata use.
     */
    private val _availableIdentities = MutableStateFlow<Map<WalletId, StoredIdentity>>(emptyMap())
    val availableIdentities = _availableIdentities.asStateFlow()

    private val _desiredWalletId = MutableStateFlow<WalletId?>(null)
    val desiredWalletId = _desiredWalletId.asStateFlow()
    val startWalletImmediately = MutableStateFlow(true)

    /**
     * The identity the app is running as, or null while nothing has been started.
     *
     * This replaces what used to be a flow of the active *wallet*: every reader of it
     * only ever wanted the nostr key, and reached it through the node --
     * `business.walletManager.keyManager.value.nostrPrivateKey()`. An [Identity]
     * carries the key directly, so a reader no longer needs a node to exist, and an
     * identity that never has one (a bare nsec) reads the same as one that does.
     */
    private val _activeIdentity = MutableStateFlow<Identity?>(null)
    val activeIdentity = _activeIdentity.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val exchangeRates = phoenixGlobal.currencyManager.ratesFlow
        .stateIn(viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList())

    var walletUIState: com.machankura.dedun.compose.ui.view.state.SovereignWalletUIState by mutableStateOf(
        com.machankura.dedun.compose.ui.view.state.SovereignWalletUIState.Loading)
        private set

    fun retryLoad() {
        walletUIState = com.machankura.dedun.compose.ui.view.state.SovereignWalletUIState.Loading
    }


    fun loadSovereignData(walletId: WalletId) {
        walletUIState = com.machankura.dedun.compose.ui.view.state.SovereignWalletUIState.Success(
            publicKey = walletId.nodeIdHash,
            balanceInSatoshis = 0 // TODO: Get balance...
        )

        viewModelScope.launch {

        }
    }


    init {
        listIdentities(onDone = {})
    }

    /**
     * Makes [identity] the one the app runs as. Everything reading [activeIdentity]
     * follows, and the id is saved as the default: every activation goes through here
     * -- startup for all three kinds, the sign-in and create tails through startup -- so
     * the default is always the profile most recently open, and a cold boot with several
     * opens that one. It was read by startup and written by nothing, which made every
     * cold boot with two profiles the selector.
     */
    fun setActiveIdentity(identity: Identity) {
        _activeIdentity.value = identity
        rememberDefault { saveDefaultWallet(identity.id) }
//        scheduleAutoLock()
    }

    /**
     * Forgets which profile opens on launch. For the forget tails: the default is the
     * one memory of an identity that would otherwise outlive it, and a null read is
     * better than a miss that happens to be handled.
     */
    fun forgetDefaultIdentity() {
        rememberDefault { clearDefaultWallet() }
    }

    /**
     * One reader, so the writes land in the order they were asked for. A coroutine each had
     * no order at all: the default could end on the profile opened first, or a forgotten
     * profile could be written back after the forget.
     */
    private val defaultWrites = Channel<suspend GlobalPrefs.() -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (write in defaultWrites) {
                runCatching { getGlobalPrefs().write() }
                    .onFailure { log.e("could not record which profile is open", it) }
            }
        }
    }

    /** A default that could not be written is a selector on the next boot, not a crash now. */
    private fun rememberDefault(write: suspend GlobalPrefs.() -> Unit) {
        defaultWrites.trySend(write)
    }

    /**
     * Activates a profile with a wallet attached, once its node has started.
     *
     * The key is the credential's, not the node's. The node still derives it -- and is
     * asked to, as a cross-check -- but the credentials file is what the app signs with
     * now, and a node that disagrees with it is the one corruption worth refusing to run
     * under: the throw lands in the startup view model's handler and shows as a startup
     * error. It cannot happen for a file the repair wrote, since that derived the key
     * from the same seed.
     */
    fun setActiveWallet(stored: StoredIdentity.Mnemonic, business: PhoenixBusiness) {
        val walletId = stored.id
        val keyManager = business.walletManager.keyManager.value
        if (keyManager == null) {
            // `startNewBusiness` loads the wallet before it reports success, so a business
            // handed here without a key manager is a programming error rather than a state
            // to route around. Leaving the identity unset sends the UI back to startup.
            log.e { "business for wallet=$walletId started without a key manager" }
            return
        }
        check(keyManager.nostrPrivateKey() == stored.privateKey) {
            "the node for wallet=$walletId derives a different nostr key than its credential holds"
        }
        val dataStoreManager = DataStoreManager(business)

        setActiveIdentity(
            Identity.signing(
                id = walletId,
                kind = IdentityKind.Mnemonic,
                nostrPrivateKey = stored.privateKey,
                userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId = walletId),
                internalPrefs = dataStoreManager.loadInternalPrefsForWallet(walletId = walletId),
                business = business,
            )
        )
        updateBusinessActiveInUI(walletId)
    }

    /**
     * Reads both stores, repairs the credentials file so that every seed's key is in it,
     * and publishes the merged list, registering metadata for any id seen for the first
     * time.
     *
     * A failure in either file is surfaced, not skipped: a corrupt `nostr-keys.dat` would
     * otherwise drop every imported identity from the list without a word, and the seed
     * file has always been handled this way. A failure of the *repair* is the one thing
     * logged and not surfaced -- the listing derives around it, see [SeedCredentials].
     */
    fun listIdentities(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
//            log.error("error when initialising startup-view: ", e)
            _listWalletState.value = ListWalletState.Error.Generic(e)
        }) {

            val wallets: Map<WalletId, UserWallet> = when (val result = loadAndDecryptSeed(phoenixGlobal)) {
                is DecryptSeedResult.Failure.SerializationError -> {
                    log.error {"cannot deserialize seed file: "}
                    _listWalletState.value = ListWalletState.Error.Serialization
                    return@launch
                }
                is DecryptSeedResult.Failure.DecryptionError -> {
                    log.e("cannot decrypt seed file: ", throwable = result.cause)
                    _listWalletState.value = ListWalletState.Error.DecryptionError.GeneralException(result.cause)
                    return@launch
                }
                is DecryptSeedResult.Failure.KeyStoreFailure -> {
                    log.e("key store failure: ", throwable = result.cause)
                    _listWalletState.value = ListWalletState.Error.DecryptionError.KeystoreFailure(result.cause)
                    return@launch
                }
                is DecryptSeedResult.Failure.SeedFileUnreadable -> {
                    log.e("aborting, unreadable seed file")
                    _listWalletState.value = ListWalletState.Error.Generic(null)
                    return@launch
                }
                is DecryptSeedResult.Failure.SeedInvalid -> {
                    log.e("aborting, seed is invalid")
                    _listWalletState.value = ListWalletState.Error.Generic(null)
                    return@launch
                }
                is DecryptSeedResult.Failure.SeedFileNotFound -> emptyMap()
                is DecryptSeedResult.Success -> result.userWalletsMap
            }

            // Before the read, and here rather than in the reader: a listing that writes
            // would be a surprise, and this runs from init, before anything else touches
            // either file. An old file that cannot be read is left alone and reported the
            // way an unreadable credentials file is, so the user sees the error rather
            // than silently losing every imported identity.
            when (val migration = NostrCredentialManager.migrateFromNostrKeys(phoenixGlobal)) {
                is NostrCredentialManager.MigrationResult.Migrated -> log.i { "migrated ${migration.count} nostr key(s) into the credentials file" }
                is NostrCredentialManager.MigrationResult.NotNeeded -> Unit
                is NostrCredentialManager.MigrationResult.Failed -> {
                    log.e { "nostr-keys.dat could not be migrated: ${migration.failure}" }
                    _listWalletState.value = when (val failure = migration.failure) {
                        is DecryptNostrKeysResult.Failure.SerializationError -> ListWalletState.Error.Serialization
                        is DecryptNostrKeysResult.Failure.DecryptionError -> ListWalletState.Error.DecryptionError.GeneralException(failure.cause)
                        is DecryptNostrKeysResult.Failure.KeyStoreFailure -> ListWalletState.Error.DecryptionError.KeystoreFailure(failure.cause)
                        is DecryptNostrKeysResult.Failure.FileUnreadable,
                        is DecryptNostrKeysResult.Failure.FileNotFound -> ListWalletState.Error.Generic(null)
                    }
                    return@launch
                }
            }

            val readCredentials: Map<HexKey, NostrCredential> = when (val result = NostrCredentialManager.loadAndDecrypt(phoenixGlobal)) {
                is DecryptNostrCredentialsResult.Failure.SerializationError -> {
                    log.e { "cannot deserialize nostr credentials file" }
                    _listWalletState.value = ListWalletState.Error.Serialization
                    return@launch
                }
                is DecryptNostrCredentialsResult.Failure.DecryptionError -> {
                    log.e("cannot decrypt nostr credentials file: ", throwable = result.cause)
                    _listWalletState.value = ListWalletState.Error.DecryptionError.GeneralException(result.cause)
                    return@launch
                }
                is DecryptNostrCredentialsResult.Failure.KeyStoreFailure -> {
                    log.e("key store failure: ", throwable = result.cause)
                    _listWalletState.value = ListWalletState.Error.DecryptionError.KeystoreFailure(result.cause)
                    return@launch
                }
                is DecryptNostrCredentialsResult.Failure.FileUnreadable -> {
                    log.e("aborting, unreadable nostr credentials file")
                    _listWalletState.value = ListWalletState.Error.Generic(null)
                    return@launch
                }
                is DecryptNostrCredentialsResult.Failure.FileNotFound -> emptyMap()
                is DecryptNostrCredentialsResult.Success -> result.credentials
            }

            // The second write a listing makes, and for the same reason as the first: a
            // seed's key belongs in the credentials file, and every seed from before that
            // was so gets its entry here, once. The listing uses what the repair returns --
            // the repaired map, or the one read if the write failed -- so a seed is listed
            // either way.
            val credentials = when (val repair = SeedCredentials.reconcile(log, phoenixGlobal, wallets, readCredentials)) {
                is SeedCredentials.Result.Written -> {
                    log.i { "wrote the credential of ${repair.count} seed(s) into the credentials file" }
                    repair.credentials
                }
                is SeedCredentials.Result.NotNeeded -> repair.credentials
                is SeedCredentials.Result.Failed -> {
                    log.e("the credentials of ${wallets.size} seed(s) could not be repaired; listing by derivation", repair.cause)
                    repair.credentials
                }
            }

            val identities = StoredIdentity.merge(wallets, credentials)

            val metadataMap = getAvailableWalletsMeta(phoenixGlobal).first()
            identities.keys.forEach { walletId ->
                if (metadataMap[walletId] == null) {
                    saveAvailableWalletMeta(
                        phoenixGlobal = phoenixGlobal,
                        walletId = walletId,
                        name = null,
                        avatar = com.machankura.dedun.compose.ui.composable.widgets.wallet.WalletAvatars.list.random(),
                        isHidden = false
                    )
                }
            }
            _availableIdentities.value = identities
            _listWalletState.value = ListWalletState.Success
            viewModelScope.launch(Dispatchers.Main) {
                onDone()
            }
        }
    }

//    fun scheduleAutoLock() {
//        // TODO: Run this through an expect...
//        viewModelScope.launch {
//            autoLockHandler.removeCallbacksAndMessages(null)
//            val activeUserPrefs = activeIdentity.first()?.userPrefs ?: return@launch
//
//            val biometricLockEnabled = activeUserPrefs.getLockBiometricsEnabled.first()
//            val customPinLockEnabled = activeUserPrefs.getLockPinEnabled.first()
//            val autoLockDelay = activeUserPrefs.getAutoLockDelay.first()
//
//            if ((biometricLockEnabled || customPinLockEnabled) && autoLockDelay != Duration.INFINITE) {
//                autoLockHandler.postDelayed(autoLockRunnable, autoLockDelay.inWholeMilliseconds)
//            }
//        }
//    }

    /**
     * Makes [id] the identity to open next and clears the active one, which is the whole
     * of a switch: the navigation observer sends a null identity to startup, startup
     * opens [id] through the lock gate, and the machine routes it from its account.
     * Everything reading [activeIdentity] is cancelled by the null and rebuilt by the
     * activation -- the notary, the pumps, the live subscriptions, the relay observer.
     * See docs/multiple-profiles.md, Phase 2.
     *
     * [startWalletImmediately] goes back to true: a switch is the user saying which one,
     * and the flag was only ever the user saying *show me the list*. Nothing set it back
     * before because nothing could switch.
     *
     * The identity is cleared **before** the node is stopped: clearing cancels every
     * collector that could reach the business, so the stop finds nothing reading it.
     * The test is `business != null`, not the kind -- whether the profile being left has
     * a node behind it is the fact, and the kind is how it currently comes to be true.
     * `previous.id` is the wallet's id when a wallet is attached, which is why the
     * manager can take it.
     */
    fun switchToIdentity(id: WalletId) {
        val previous = _activeIdentity.value
        _desiredWalletId.value = id
        startWalletImmediately.value = true
        _activeIdentity.value = null
        if (previous?.business != null) {
            viewModelScope.launch(Dispatchers.IO) { stopBusiness(previous.id) }
        }
    }

    /** Clears the active identity. It does not affect [desiredWalletId]. The UI may still auto-open a specific wallet, if [desiredWalletId] is not null. */
    fun clearActiveWallet() {
        _activeIdentity.value = null
    }

    /** Resets the active identity and [desiredWalletId]. The UI will redirect to the startup screen with the wallets selector prompt. */
    fun resetToSelector() {
        _desiredWalletId.value = null
        _activeIdentity.value = null
        startWalletImmediately.value = false
    }

    override fun onCleared() {
        super.onCleared()
        log.i("AppViewModel cleared")
    }

    fun getGlobalPrefs(): GlobalPrefs {
        return com.machankura.dedun.compose.extensions.getGlobalPrefs(
            phoenixGlobal
        )
    }

    // wallet initialisation options -- to be saved to user prefs once the wallet has been created and we know its walletId
    var isTorEnabled = mutableStateOf(false)
    var customElectrumServer = mutableStateOf<ElectrumConfig.Custom?>(null)

    /** Monitors the writing of a seed on disk ; used by the restore view and the create view thru [writeSeed]. */
    var writingState by mutableStateOf<WritingSeedState>(WritingSeedState.Init)
        private set


    /**
     * Attempts to write a seed on disk and updates the view model state. If a seed already
     * exists on disk, this method will put the [writingState] in error.
     */
    fun writeSeed(
        mnemonics: List<String>,
        isRestoringWallet: Boolean,
        onSeedWritten: (WalletId) -> Unit,
        onSeedWriteError: (WritingSeedState.Error) -> Unit = {}
    ) {
        if (writingState is WritingSeedState.Error || writingState is WritingSeedState.WrittenToDisk) {
            // A previous attempt finished; reset so writeMnemonic does not silently skip this one.
            writingState = WritingSeedState.Init
        }
        IdentityWriter.writeMnemonic(
            log = log,
            phoenixGlobal = phoenixGlobal,
            globalPrefs = getGlobalPrefs(),
            writingState = writingState,
            viewModelScope = viewModelScope,
            mnemonics = mnemonics,
            onWritingSeedError = { writingSeedStateError ->
                writingState = writingSeedStateError
                onSeedWriteError(writingSeedStateError)
            },
            onWritingSeedStateWriting = { writing ->
                writingState = writing
            },
            isRestoringWallet = isRestoringWallet,
            isTorEnabled = isTorEnabled.value,
            customElectrumServer = customElectrumServer.value,
            onSeedWritten = { walletId ->
                writingState = WritingSeedState.WrittenToDisk(walletId)
                onSeedWritten(walletId)
            }
        )
    }

    /**
     * Writes a bare nostr key as a new identity, with the same initialisation options
     * [writeSeed] applies. Suspends; the caller is the sign-in view model's coroutine.
     */
    suspend fun writeNostrKey(privateKey: PrivateKey): IdentityWriter.WriteNostrCredentialResult =
        IdentityWriter.writeNostrKey(
            log = log,
            phoenixGlobal = phoenixGlobal,
            globalPrefs = getGlobalPrefs(),
            privateKey = privateKey,
            isTorEnabled = isTorEnabled.value,
            customElectrumServer = customElectrumServer.value,
        )

    /** Writes a public key as a read-only identity, with the same options. */
    suspend fun writeNostrPublicKey(nostrPublicKey: HexKey): IdentityWriter.WriteNostrCredentialResult =
        IdentityWriter.writeNostrPublicKey(
            log = log,
            phoenixGlobal = phoenixGlobal,
            globalPrefs = getGlobalPrefs(),
            nostrPublicKey = nostrPublicKey,
            isTorEnabled = isTorEnabled.value,
            customElectrumServer = customElectrumServer.value,
        )

    /** The inverse of both writers above. Only for a credential; see [IdentityWriter.forgetNostrCredential]. */
    suspend fun forgetNostrCredential(identity: Identity): IdentityWriter.ForgetNostrCredentialResult =
        IdentityWriter.forgetNostrCredential(
            log = log,
            phoenixGlobal = phoenixGlobal,
            id = identity.id,
            nostrPublicKey = identity.nostrPublicKey,
        )

    /**
     * Hides a forgotten identity's entry in the wallet metadata rather than deleting it:
     * the metadata store has no delete, and `isHidden` is what the selector filters on.
     */
    suspend fun hideIdentityMetadata(identity: Identity) {
        val existing = getAvailableWalletsMeta(phoenixGlobal).first()[identity.id] ?: return
        saveAvailableWalletMeta(
            phoenixGlobal = phoenixGlobal,
            walletId = identity.id,
            name = existing.name,
            avatar = existing.avatar,
            isHidden = true,
        )
    }

    companion object {
        private const val TAG = "SovereignWalletViewModel"
        fun factory(
            phoenixGlobal: PhoenixGlobal,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SovereignWalletViewModel(
                    phoenixGlobal = phoenixGlobal,
                )
            }
        }
    }
}
