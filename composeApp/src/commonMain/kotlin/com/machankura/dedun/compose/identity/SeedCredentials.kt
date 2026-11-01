package com.machankura.dedun.compose.identity

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.EncryptedNostrCredentials
import fr.acinq.phoenix.security.NostrCredential

/**
 * The credential a seed always implied.
 *
 * Until docs/multiple-profiles.md, a seed's nostr key was never written to the
 * credentials file: it was derived from the words at listing and from the running node
 * at activation, and the writers kept one key out of two files. That put the wallet
 * where the profile should be. Now the credentials file is the list of profiles and a
 * seed is a wallet attached to one, so every seed's key has to be in it -- written by
 * `IdentityWriter.writeMnemonic` for a seed created or restored from here on, and by
 * this for every seed that was already on the device.
 *
 * A named, idempotent write that runs from `SovereignWalletViewModel.listIdentities`,
 * beside `NostrCredentialManager.migrateFromNostrKeys` and for the same reason: a
 * listing that writes is a surprise, so the write is a step with a name, a result and
 * a test, rather than a side effect of reading. On a device with no seeds, or one
 * already repaired, it writes nothing.
 */
object SeedCredentials {

    sealed interface Result {
        /** The credentials the listing should use: repaired where the write succeeded, as read where it did not. */
        val credentials: Map<HexKey, NostrCredential>

        /** [count] seeds had no secret credential, and now do. One write. */
        data class Written(val count: Int, override val credentials: Map<HexKey, NostrCredential>) : Result

        /** Every seed's key was already a secret credential. Nothing was written. */
        data class NotNeeded(override val credentials: Map<HexKey, NostrCredential>) : Result

        /**
         * The write failed; the file is as it was and [credentials] is what was read. The
         * listing goes on: `StoredIdentity.merge` derives the key of a seed that has no
         * credential, so a seed this could not repair is still listed. A failed write must
         * never hide a wallet, which is why this is a result and not a throw.
         */
        data class Failed(val cause: Throwable, override val credentials: Map<HexKey, NostrCredential>) : Result
    }

    /**
     * Writes a `Secret` for every seed in [wallets] whose derived key is not one in
     * [credentials], replacing a `Public` entry for that key where there is one -- the
     * read-only upgrade landing on the wallet that was here all along.
     */
    fun reconcile(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        wallets: Map<WalletId, UserWallet>,
        credentials: Map<HexKey, NostrCredential>,
    ): Result {
        val missing = wallets.values
            .map { StoredIdentity.nostrPrivateKeyOf(it.words) }
            .filter { credentials[it.nostrPublicKeyHex()] !is NostrCredential.Secret }
        if (missing.isEmpty()) return Result.NotNeeded(credentials)

        val repaired = credentials + missing.associate { it.nostrPublicKeyHex() to NostrCredential.Secret(it) }
        return try {
            NostrCredentialManager.writeToDisk(phoenixGlobal, EncryptedNostrCredentials.encrypt(repaired))
            log.i { "wrote the credential for ${missing.size} seed(s) that had none" }
            Result.Written(missing.size, repaired)
        } catch (e: Exception) {
            log.e("could not write the credentials of ${missing.size} seed(s); listing them by derivation", e)
            Result.Failed(e, credentials)
        }
    }
}
