package com.machankura.dedun.compose.ui.view.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.repository.NostrRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sign out for a read-only identity: the confirmation, the in-flight state, and the
 * caller being told only once the device holds nothing for the identity. The sequence
 * itself is `ForgetIdentity`'s and is pinned there; this is the state around it, the
 * way `NostrSecretViewModelJvmTest` pins the nsec screen's.
 */
class SignOutViewModelJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val privateKey = PrivateKey(ByteVector32("05".repeat(32)))

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun identity() = Identity.readOnly(
        id = privateKey.publicKey().xOnly().toWalletId(),
        nostrPublicKey = privateKey.nostrPublicKeyHex(),
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
    )

    private class Recorder : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val effects = mutableListOf<String>()
        override suspend fun forgetLocalAccount(publicKey: HexKey) {
            effects += "forgetLocalAccount"
        }
    }

    private fun viewModel(recorder: Recorder, outcome: IdentityWriter.ForgetNostrCredentialResult) = SignOutViewModel(
        activeIdentityStateFlow = MutableStateFlow<Identity?>(identity()),
        nostrRepository = recorder,
        forgetNostrCredential = { recorder.effects += "forgetNostrCredential"; outcome },
        hideIdentityMetadata = { recorder.effects += "hideIdentityMetadata" },
    )

    @Test
    fun `asking, then confirming, forgets and tells the caller, and the state is idle again`() = runBlocking<Unit> {
        val recorder = Recorder()
        val viewModel = viewModel(recorder, IdentityWriter.ForgetNostrCredentialResult.Forgotten)
        val told = CompletableDeferred<Unit>()

        assertEquals(SignOutViewModel.State.Idle, viewModel.state.value)
        viewModel.askToSignOut()
        assertEquals(SignOutViewModel.State.Confirming, viewModel.state.value)
        assertEquals(privateKey.nostrPublicKeyHex(), viewModel.nostrPublicKey, "the confirmation names the identity")

        viewModel.signOut { told.complete(Unit) }
        withTimeout(10_000) { told.await() }

        assertEquals(listOf("forgetNostrCredential", "hideIdentityMetadata", "forgetLocalAccount"), recorder.effects)
        withTimeout(10_000) { viewModel.state.first { it == SignOutViewModel.State.Idle } }
    }

    @Test
    fun `cancelling from the confirmation touches nothing`() {
        val recorder = Recorder()
        val viewModel = viewModel(recorder, IdentityWriter.ForgetNostrCredentialResult.Forgotten)

        viewModel.askToSignOut()
        viewModel.cancel()

        assertEquals(SignOutViewModel.State.Idle, viewModel.state.value)
        assertEquals(emptyList(), recorder.effects)
    }

    @Test
    fun `a failure is a state, the caller is not told, and nothing after the first step ran`() = runBlocking<Unit> {
        val recorder = Recorder()
        val viewModel = viewModel(recorder, IdentityWriter.ForgetNostrCredentialResult.CannotLoadKeys)
        var told = false

        viewModel.askToSignOut()
        viewModel.signOut { told = true }
        withTimeout(10_000) { viewModel.state.first { it == SignOutViewModel.State.Failed } }

        assertEquals(false, told)
        assertEquals(listOf("forgetNostrCredential"), recorder.effects)
    }
}
