package com.machankura.dedun.compose.identity

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.EncryptedNostrCredentials
import fr.acinq.phoenix.security.EncryptedSeed
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.AppVersion
import com.machankura.dedun.compose.ui.view.model.WritingSeedState

/**
 * Puts a new identity on disk, one function per kind, and prepares the per-identity
 * preferences every kind reads.
 *
 * [writeMnemonic] was an `expect` with three byte-identical actuals -- android, jvm, ios --
 * using nothing but commonMain: SeedManager, EncryptedSeed, LocalKeyManager,
 * DataStoreManager, AppVersion. It was an expect because something once needed to
 * differ and nothing does now, so it is one function here, and the second writer sits
 * next to it rather than being triplicated in turn.
 *
 * All three refuse a duplicate **by nostr public key**, not only by id. A wallet and an
 * imported key can be the same npub under different ids -- one is hash160 of the node
 * key, the other hash160 of the nostr key -- and the database is keyed by pubkey, so two
 * identities for one pubkey would share every row and disagree about which is active.
 *
 * With two exceptions, which are the point of the credentials file. A public key already
 * here read-only is not a duplicate of the secret that signs as it: the device does not
 * have that secret, and refusing it would be false. [writeNostrKey] replaces the entry
 * in one write. And a bare secret already here is not a duplicate of the recovery phrase
 * that derives it: the device does not have that wallet, and pasting the phrase is the
 * profile acquiring the wallet that derives it. [writeMnemonic] writes the credential
 * first and then the seed -- see docs/multiple-profiles.md, Phase 1 -- and every seed's
 * key is a `Secret` entry in the credentials file, so that the file is the list of
 * profiles and the seed is a wallet attached to one of them.
 */
object IdentityWriter {

    /** Each seed's nostr public key and the wallet id it is filed under, or null if the store could not be read. */
    private fun seedWalletIds(phoenixGlobal: PhoenixGlobal): Map<HexKey, WalletId>? =
        SeedManager.loadAndDecryptOrNull(phoenixGlobal)
            ?.values
            ?.associate { StoredIdentity.nostrPublicKeyOf(it.words) to it.walletId }

    /**
     * Creates the preference files a new id needs and records the choices made before
     * the id was known. Tor and a custom Electrum server mean nothing to an identity
     * without a wallet; they are saved anyway, because `loadUserPrefsForWallet` is what
     * creates the file and the recovery screens read it.
     */
    private suspend fun prepareIdentity(
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        id: WalletId,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ) {
        globalPrefs.saveLastUsedAppCode(AppVersion.versionCode)
        val dataStoreManager = DataStoreManager(
            phoenixGlobal.ctx,
            chain = NodeParamsManager.chain,
        )
        val userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId = id)
        userPrefs.saveIsTorEnabled(isTorEnabled)
        userPrefs.saveElectrumServer(customElectrumServer)
    }

    /**
     * Adds [mnemonics] to `seed.dat`. Callback-shaped, driving [WritingSeedState], because
     * that is what the create flow and its view model were built around.
     */
    fun writeMnemonic(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        writingState: WritingSeedState,
        viewModelScope: CoroutineScope,
        mnemonics: List<String>,
        onWritingSeedError: (WritingSeedState.Error) -> Unit,
        onWritingSeedStateWriting: (WritingSeedState.Writing) -> Unit,
        isRestoringWallet: Boolean,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
        onSeedWritten: (WalletId) -> Unit
    ) {
        if (writingState !is WritingSeedState.Init) return
        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            log.e("failed to write mnemonics to disk: ${e.message}")
            onWritingSeedError.invoke(
                WritingSeedState.Error.Generic(e)
            )
        }) {
            log.d("writing mnemonics to disk...")

            onWritingSeedStateWriting.invoke(
                WritingSeedState.Writing(mnemonics)
            )
            val existingSeeds = SeedManager.loadAndDecryptOrNull(phoenixGlobal)?.map {
                it.key to it.value.words
            }?.toMap()
            // Null when nostr-credentials.dat exists and cannot be read; an empty map when it is absent.
            val existingCredentials = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)

            val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
            val keyManager = LocalKeyManager(seed, NodeParamsManager.chain, NodeParamsManager.remoteSwapInXpub)
            val newWalletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)
            val newNostrPublicKey = keyManager.nostrPrivateKey().nostrPublicKeyHex()

            when {
                existingSeeds == null || existingCredentials == null -> {
                    log.e("could not load the existing seed map, aborting...")
                    onWritingSeedError.invoke(
                        WritingSeedState.Error.CannotLoadSeedMap
                    )
                    return@launch
                }
                existingSeeds.containsKey(newWalletId) -> {
                    // The same seed, and so the same nostr key with a wallet already attached.
                    // Two different seeds cannot derive one nostr key, so this is the only
                    // way a profile with a wallet attached is offered its phrase again. The
                    // id goes with the refusal: from inside the app, "already here" has an
                    // obvious next step, which is to switch to it.
                    log.i("attempting to import a seed that already exists, aborting...")
                    onWritingSeedError.invoke(
                        WritingSeedState.Error.SeedAlreadyExists(newWalletId)
                    )
                    return@launch
                }
                else -> {
                    // The credential first, then the seed. What the credentials file holds
                    // for this key beforehand decides what the write means, not whether it
                    // happens: nothing, and this is a new profile with a wallet; a public
                    // entry, and it is the read-only upgrade landing on a wallet; a secret,
                    // and it is a bare key acquiring the wallet that derives it, which used
                    // to be refused as a duplicate and is not one -- the device did not have
                    // the wallet. In every case the entry becomes the derived key.
                    //
                    // A crash between the two writes leaves a bare-key profile whose key
                    // the phrase derives: a valid thing to hold, and pasting the phrase
                    // again completes it. The reverse order would leave a seed with no
                    // credential, which the listing derives around and the repair fixes --
                    // also recoverable, but this order says the model out loud: the profile
                    // exists, and then a wallet is attached to it.
                    val existingEntry = existingCredentials[newNostrPublicKey]
                    val bareKeyId = if (existingEntry != null) StoredIdentity.nostrPublic(newNostrPublicKey).id else null
                    when (existingEntry) {
                        null -> Unit
                        is NostrCredential.Public -> log.i("the seed's nostr key is here read-only; it becomes a profile with a wallet")
                        is NostrCredential.Secret -> log.i("the seed's nostr key is here as a bare key; attaching the wallet")
                    }
                    NostrCredentialManager.writeToDisk(
                        phoenixGlobal,
                        EncryptedNostrCredentials.encrypt(
                            existingCredentials + (newNostrPublicKey to NostrCredential.Secret(keyManager.nostrPrivateKey()))
                        ),
                    )
                    val newSeedMap = existingSeeds + (newWalletId to mnemonics)
                    val encrypted = EncryptedSeed.V2.encrypt(newSeedMap)
                    SeedManager.writeSeedToDisk(phoenixGlobal, encrypted, overwrite = true)
                    if (isRestoringWallet) {
                        log.i("successfully restored wallet=$newWalletId")
                    } else {
                        log.i("successfully created wallet=$newWalletId")
                    }
                    if (bareKeyId != null) {
                        // The profile's id is its wallet's now, so its preferences are the
                        // wallet's -- fresh, as a new wallet's are, which is right: there is
                        // a new secret to back up. The files under the bare key's id would
                        // otherwise sit on the disk with nothing reading them.
                        DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain).deleteNodeUserPrefs(bareKeyId)
                    }
                }
            }

            prepareIdentity(phoenixGlobal, globalPrefs, newWalletId, isTorEnabled, customElectrumServer)

            viewModelScope.launch(Dispatchers.Main) {
                delay(1000)
                onSeedWritten(newWalletId)
            }
        }
    }

    sealed class WriteNostrCredentialResult {
        data class Written(val id: WalletId) : WriteNostrCredentialResult()

        /**
         * An identity with this nostr public key is already on the device and holds at least
         * what was pasted: a wallet or a secret, for either input; a public key, for an npub.
         * [id] is the one it is listed under -- the wallet's where a seed derives the key --
         * so that the refusal can offer to switch to it.
         */
        data class AlreadyExists(val id: WalletId) : WriteNostrCredentialResult()

        /** One of the two stores exists and could not be read; nothing was written. */
        data object CannotLoadKeys : WriteNostrCredentialResult()
    }

    /**
     * Adds [privateKey] to `nostr-credentials.dat` as a secret and prepares its
     * preferences. Suspending and result-shaped rather than callback-shaped: nothing was
     * built around a state machine for it, and the caller is a coroutine already. Throws
     * on an I/O or key store failure during the write, as [writeMnemonic]'s handler would
     * have caught.
     *
     * A public entry for the same key is the one thing that is not a duplicate: the entry
     * becomes a secret one, in a single write, under the id it already had.
     */
    suspend fun writeNostrKey(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        privateKey: PrivateKey,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ): WriteNostrCredentialResult {
        log.d("writing nostr key to disk...")
        val stored = StoredIdentity.nostrSecret(privateKey)

        val seeds = seedWalletIds(phoenixGlobal)
        val existing = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        if (seeds == null || existing == null) {
            log.e("could not load the existing keys, aborting...")
            return WriteNostrCredentialResult.CannotLoadKeys
        }
        seeds[stored.nostrPublicKey]?.let { walletId ->
            log.i("attempting to import the nostr key of a wallet that is already here, aborting...")
            return WriteNostrCredentialResult.AlreadyExists(walletId)
        }
        if (existing[stored.nostrPublicKey] is NostrCredential.Secret) {
            log.i("attempting to import a nostr key that is already here, aborting...")
            return WriteNostrCredentialResult.AlreadyExists(stored.id)
        }
        val upgrading = existing[stored.nostrPublicKey] is NostrCredential.Public

        val encrypted = EncryptedNostrCredentials.encrypt(
            existing + (stored.nostrPublicKey to NostrCredential.Secret(privateKey))
        )
        NostrCredentialManager.writeToDisk(phoenixGlobal, encrypted)
        if (upgrading) {
            log.i("read-only identity=${stored.id} now holds its key")
        } else {
            log.i("successfully imported nostr key for identity=${stored.id}")
        }

        prepareIdentity(phoenixGlobal, globalPrefs, stored.id, isTorEnabled, customElectrumServer)

        return WriteNostrCredentialResult.Written(stored.id)
    }

    /**
     * Adds [nostrPublicKey] to `nostr-credentials.dat` as a public entry -- an identity
     * the device can look at and not sign as -- and prepares its preferences. Refused if
     * the device already holds anything at all for the key, since anything is more than
     * this.
     */
    suspend fun writeNostrPublicKey(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        nostrPublicKey: HexKey,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ): WriteNostrCredentialResult {
        log.d("writing nostr public key to disk...")
        val stored = StoredIdentity.nostrPublic(nostrPublicKey)

        val seeds = seedWalletIds(phoenixGlobal)
        val existing = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        if (seeds == null || existing == null) {
            log.e("could not load the existing keys, aborting...")
            return WriteNostrCredentialResult.CannotLoadKeys
        }
        seeds[stored.nostrPublicKey]?.let { walletId ->
            log.i("attempting to add the public key of a wallet that is already here, aborting...")
            return WriteNostrCredentialResult.AlreadyExists(walletId)
        }
        if (existing.containsKey(stored.nostrPublicKey)) {
            log.i("attempting to add a public key the device already holds something for, aborting...")
            return WriteNostrCredentialResult.AlreadyExists(stored.id)
        }

        NostrCredentialManager.writeToDisk(
            phoenixGlobal,
            EncryptedNostrCredentials.encrypt(existing + (stored.nostrPublicKey to NostrCredential.Public)),
        )
        log.i("added read-only identity=${stored.id}")

        prepareIdentity(phoenixGlobal, globalPrefs, stored.id, isTorEnabled, customElectrumServer)

        return WriteNostrCredentialResult.Written(stored.id)
    }

    sealed class ForgetNostrCredentialResult {
        data object Forgotten : ForgetNostrCredentialResult()

        /**
         * A seed derives the key: the profile has a wallet attached, and removing a seed is
         * a wallet question this does not answer. Also the answer for a key that is not in
         * `nostr-credentials.dat` at all, which since the repair can only be a seed's key
         * the repair could not write -- and either way, taking the entry would not take the
         * key: the seed would derive it again and the next listing would put it back.
         */
        data object WalletAttached : ForgetNostrCredentialResult()

        data object CannotLoadKeys : ForgetNostrCredentialResult()
    }

    /**
     * The inverse of [writeNostrKey] and [writeNostrPublicKey] alike: removes the entry,
     * whichever kind it is, from `nostr-credentials.dat` and deletes the identity's
     * preference files. The profile stays on the relays, and the same key can be signed
     * in again. Only for a profile with no wallet attached -- see
     * [ForgetNostrCredentialResult.WalletAttached].
     */
    suspend fun forgetNostrCredential(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        id: WalletId,
        nostrPublicKey: HexKey,
    ): ForgetNostrCredentialResult {
        val seeds = seedWalletIds(phoenixGlobal)
        val existing = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        if (seeds == null || existing == null) {
            log.e("could not load the existing keys, aborting...")
            return ForgetNostrCredentialResult.CannotLoadKeys
        }
        if (seeds.containsKey(nostrPublicKey)) {
            log.i("asked to forget a key a seed derives; the wallet has to go first")
            return ForgetNostrCredentialResult.WalletAttached
        }
        if (!existing.containsKey(nostrPublicKey)) {
            log.i("asked to forget a key that is not a credential on this device")
            return ForgetNostrCredentialResult.WalletAttached
        }

        NostrCredentialManager.writeToDisk(phoenixGlobal, EncryptedNostrCredentials.encrypt(existing - nostrPublicKey))
        log.i("forgot nostr credential for identity=$id")

        DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain).deleteNodeUserPrefs(id)

        return ForgetNostrCredentialResult.Forgotten
    }
}
