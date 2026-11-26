package com.machankura.dedun.compose.identity

import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptNostrCredentialsResult
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.RecoveryCodeManager
import fr.acinq.phoenix.data.DecryptRecoveryCodesResult
import kotlinx.coroutines.flow.first
import com.machankura.dedun.compose.extensions.getGlobalPrefs

import com.machankura.dedun.compose.ui.view.model.loadAndDecryptSeed

/**
 * The profile that opens on launch, read with no screen and no view model: what a background
 * sync runs as. docs/notifications-and-background-sync.md, D6 and Phase 9.
 *
 * The reading half of `SovereignWalletViewModel.listIdentities`, without its two writes -- the
 * migration of an old key file and the repair of a seed's missing credential. Both belong to a
 * launch somebody is watching; a background job that rewrote a credentials file nobody could see
 * fail would be the wrong place for either. A seed whose credential is missing is still found, by
 * the derivation the repair would have written.
 *
 * Null for anything but a signing profile: none chosen, its files unreadable, or a public key
 * only -- which can open no gift wrap and so has nothing to sync for.
 *
 * In Dedun a profile with words -- a phrase or a recovery code -- has a Lightning node, which this
 * does not start: the background pass reads relays and files events as the profile's nostr key,
 * and needs no node to do it. So the identity it returns has no `business`, and nothing in a pass
 * may ask it for one (docs/giza-second-batch.md, Phase 3).
 */
object DefaultIdentity {
    private val logger = Logger.withTag("DefaultIdentity")

    suspend fun load(phoenixGlobal: PhoenixGlobal): Identity? {
        val defaultId = getGlobalPrefs(phoenixGlobal).getDefaultWallet.first() as? WalletId ?: return null

        val wallets = when (val result = loadAndDecryptSeed(phoenixGlobal)) {
            is DecryptSeedResult.Success -> result.userWalletsMap
            is DecryptSeedResult.Failure.SeedFileNotFound -> emptyMap()
            else -> {
                logger.w { "Seed file unreadable in the background: $result" }
                emptyMap()
            }
        }
        val credentials = when (val result = NostrCredentialManager.loadAndDecrypt(phoenixGlobal)) {
            is DecryptNostrCredentialsResult.Success -> result.credentials
            is DecryptNostrCredentialsResult.Failure.FileNotFound -> emptyMap()
            else -> {
                logger.w { "Credentials unreadable in the background: $result" }
                emptyMap()
            }
        }

        val codes = when (val result = RecoveryCodeManager.loadAndDecrypt(phoenixGlobal)) {
            is DecryptRecoveryCodesResult.Success -> StoredIdentity.parseCodes(result.codes)
            else -> emptyMap()
        }

        val stored = StoredIdentity.merge(wallets, credentials, codes)[defaultId]
            ?: wallets[defaultId]?.let { StoredIdentity.mnemonic(it) }
            ?: return null

        val dataStoreManager = DataStoreManager(ctx = phoenixGlobal.ctx, chain = NodeParamsManager.chain)
        return when (stored) {
            is StoredIdentity.Seeded -> Identity.signing(
                id = stored.id,
                kind = stored.kind,
                nostrPrivateKey = stored.privateKey,
                userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
                internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
                business = null,
            )
            is StoredIdentity.NostrSecret -> Identity.signing(
                id = stored.id,
                kind = IdentityKind.NostrSecret,
                nostrPrivateKey = stored.privateKey,
                userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
                internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
                business = null,
            )
            is StoredIdentity.NostrPublic -> null
        }
    }
}
