package com.machankura.dedun.compose.ui.view.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.NostrSecretUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Forgetting a key is four effects, and their order is what this pins: the key comes
 * out of the file first, and only then are the metadata hidden, the account rows
 * removed and the caller told. A failure at the first step must leave everything else
 * untouched -- an identity the selector lists but nothing can open is worse than one
 * that is still there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NostrSecretViewModelJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val privateKey = PrivateKey(ByteVector32("01".repeat(32)))

    private class Recorder : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val effects = mutableListOf<String>()
        override suspend fun forgetLocalAccount(publicKey: HexKey) {
            effects += "forgetLocalAccount"
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun identity() = Identity.signing(
        id = privateKey.publicKey().xOnly().toWalletId(),
        kind = IdentityKind.NostrSecret,
        nostrPrivateKey = privateKey,
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
        business = null,
    )

    private fun viewModel(
        recorder: Recorder,
        forgetOutcome: IdentityWriter.ForgetNostrCredentialResult,
    ) = NostrSecretViewModel(
        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = temporaryFolder.newFolder())),
        activeIdentityStateFlow = MutableStateFlow<Identity?>(identity()),
        nostrRepository = recorder,
        forgetNostrCredential = { recorder.effects += "forgetNostrCredential"; forgetOutcome },
        hideIdentityMetadata = { recorder.effects += "hideIdentityMetadata" },
    )

    @Test
    fun `the key comes out first, then the metadata, the account, and the caller is told`() {
        val recorder = Recorder()
        val viewModel = viewModel(recorder, IdentityWriter.ForgetNostrCredentialResult.Forgotten)
        val told = CompletableDeferred<Unit>()

        viewModel.forgetKey { told.complete(Unit) }
        runBlocking { withTimeout(10_000) { told.await() } }

        assertEquals(
            listOf("forgetNostrCredential", "hideIdentityMetadata", "forgetLocalAccount"),
            recorder.effects,
        )
        assertEquals(NostrSecretUIState.Forgetting.Idle, viewModel.forgetting.value)
    }

    @Test
    fun `a key that could not be removed leaves everything else alone`() {
        val recorder = Recorder()
        val viewModel = viewModel(recorder, IdentityWriter.ForgetNostrCredentialResult.CannotLoadKeys)

        viewModel.forgetKey { error("must not be told") }
        val state = runBlocking {
            withTimeout(10_000) { viewModel.forgetting.first { it !is NostrSecretUIState.Forgetting.InProgress } }
        }

        assertEquals(NostrSecretUIState.Forgetting.Failed, state)
        assertEquals(listOf("forgetNostrCredential"), recorder.effects)
    }
}
