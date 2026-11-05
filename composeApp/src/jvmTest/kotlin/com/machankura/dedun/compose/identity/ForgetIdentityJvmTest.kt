package com.machankura.dedun.compose.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.repository.NostrRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sequence, and where it stops.
 *
 * Forgetting is four effects in an order that matters -- the credential out of the
 * file, the metadata hidden, the account rows gone, then the caller's tail -- so that a
 * failure partway leaves the credential on disk rather than an identity the selector
 * lists but nothing can open. A refusal at the first step must leave the other two
 * untouched: an identity that is still on the device must still have its account.
 *
 * The two writers are recorded rather than run; `IdentityWriterJvmTest` covers what
 * they do to the disk.
 */
class ForgetIdentityJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val privateKey = PrivateKey(ByteVector32("04".repeat(32)))

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun readOnlyIdentity() = Identity.readOnly(
        id = privateKey.publicKey().xOnly().toWalletId(),
        nostrPublicKey = privateKey.nostrPublicKeyHex(),
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
    )

    private class Recorder : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val effects = mutableListOf<String>()
        override suspend fun forgetLocalAccount(publicKey: HexKey) {
            effects += "forgetLocalAccount:$publicKey"
        }
    }

    private suspend fun forget(recorder: Recorder, result: IdentityWriter.ForgetNostrCredentialResult) =
        ForgetIdentity.forget(
            identity = readOnlyIdentity(),
            nostrRepository = recorder,
            forgetNostrCredential = { recorder.effects += "forgetNostrCredential"; result },
            hideIdentityMetadata = { recorder.effects += "hideIdentityMetadata" },
        )

    @Test
    fun `a wallet that may hold money refuses before anything is touched`() = runBlocking {
        val recorder = Recorder()

        val outcome = ForgetIdentity.forget(
            identity = readOnlyIdentity(),
            nostrRepository = recorder,
            forgetNostrCredential = { recorder.effects += "forgetNostrCredential"; IdentityWriter.ForgetNostrCredentialResult.Forgotten },
            hideIdentityMetadata = { recorder.effects += "hideIdentityMetadata" },
            walletMayHoldMoney = { true },
            forgetWalletWords = { recorder.effects += "forgetWalletWords" },
        )

        assertEquals(ForgetIdentity.Outcome.WalletMayHoldMoney, outcome)
        assertEquals(emptyList(), recorder.effects)
    }

    @Test
    fun `an empty wallet's words go with the credential, and stay if the credential does`() = runBlocking {
        val recorder = Recorder()
        val forgotten = ForgetIdentity.forget(
            identity = readOnlyIdentity(),
            nostrRepository = recorder,
            forgetNostrCredential = { recorder.effects += "forgetNostrCredential"; IdentityWriter.ForgetNostrCredentialResult.Forgotten },
            hideIdentityMetadata = {},
            forgetWalletWords = { recorder.effects += "forgetWalletWords" },
        )
        assertEquals(ForgetIdentity.Outcome.Forgotten, forgotten)
        assertEquals(listOf("forgetNostrCredential", "forgetWalletWords", "forgetLocalAccount:${privateKey.nostrPublicKeyHex()}"), recorder.effects)

        val kept = Recorder()
        val refused = ForgetIdentity.forget(
            identity = readOnlyIdentity(),
            nostrRepository = kept,
            forgetNostrCredential = { IdentityWriter.ForgetNostrCredentialResult.CannotLoadKeys },
            hideIdentityMetadata = {},
            forgetWalletWords = { kept.effects += "forgetWalletWords" },
        )
        assertEquals(ForgetIdentity.Outcome.CannotForget, refused)
        assertEquals(emptyList(), kept.effects)
    }

    @Test
    fun `the credential comes out first, then the metadata, then the account`() = runBlocking {
        val recorder = Recorder()

        val outcome = forget(recorder, IdentityWriter.ForgetNostrCredentialResult.Forgotten)

        assertEquals(ForgetIdentity.Outcome.Forgotten, outcome)
        assertEquals(
            listOf("forgetNostrCredential", "hideIdentityMetadata", "forgetLocalAccount:${privateKey.nostrPublicKeyHex()}"),
            recorder.effects,
        )
    }

    @Test
    fun `a profile with a wallet attached is refused before anything else is touched`() = runBlocking {
        val recorder = Recorder()

        val outcome = forget(recorder, IdentityWriter.ForgetNostrCredentialResult.WalletAttached)

        assertEquals(ForgetIdentity.Outcome.WalletAttached, outcome)
        assertEquals(listOf("forgetNostrCredential"), recorder.effects)
    }

    @Test
    fun `a store that cannot be read stops the sequence at its first step`() = runBlocking {
        val recorder = Recorder()

        val outcome = forget(recorder, IdentityWriter.ForgetNostrCredentialResult.CannotLoadKeys)

        assertEquals(ForgetIdentity.Outcome.CannotForget, outcome)
        assertEquals(listOf("forgetNostrCredential"), recorder.effects)
    }
}
