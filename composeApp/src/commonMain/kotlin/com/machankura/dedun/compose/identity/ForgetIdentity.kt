package com.machankura.dedun.compose.identity

import com.machankura.dedun.compose.repository.NostrRepository

/**
 * Takes an identity off the device: the credential out of the file, its preference
 * files deleted, its metadata hidden, its account rows gone -- in that order, so a
 * failure partway leaves the credential on disk rather than an identity the selector
 * lists but nothing can open. The caller then re-lists and clears the active identity.
 *
 * Lifted out of `NostrSecretViewModel.forgetKey`, where it was written for an nsec, so
 * that the two exits a read-only identity has -- sign out, and *use a different key* on
 * the not-found screen -- run the same sequence rather than a copy of it. The first step
 * is the same call for both credential kinds since the credentials file; only a profile
 * with a wallet attached is refused, because removing a seed is a wallet question this
 * does not answer, and because the seed would derive the key again.
 *
 * The two writers are injected as functions so the sequence can be pinned in a test
 * without a key store, as the nsec view model's already is.
 */
object ForgetIdentity {

    sealed interface Outcome {
        /** The device no longer holds anything for the identity. */
        data object Forgotten : Outcome

        /** A profile with a wallet attached: the seed derives its key, and this does not remove seeds. */
        data object WalletAttached : Outcome

        /** The credentials file could not be read; nothing was changed. */
        data object CannotForget : Outcome
    }

    suspend fun forget(
        identity: Identity,
        nostrRepository: NostrRepository,
        forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
        hideIdentityMetadata: suspend (Identity) -> Unit,
    ): Outcome = when (forgetNostrCredential(identity)) {
        is IdentityWriter.ForgetNostrCredentialResult.Forgotten -> {
            hideIdentityMetadata(identity)
            // The kind 0 that made it a local account, and anything queued that can now
            // never be signed. Published events and the profile cache stay.
            nostrRepository.forgetLocalAccount(identity.nostrPublicKey)
            Outcome.Forgotten
        }
        is IdentityWriter.ForgetNostrCredentialResult.WalletAttached -> Outcome.WalletAttached
        is IdentityWriter.ForgetNostrCredentialResult.CannotLoadKeys -> Outcome.CannotForget
    }
}
