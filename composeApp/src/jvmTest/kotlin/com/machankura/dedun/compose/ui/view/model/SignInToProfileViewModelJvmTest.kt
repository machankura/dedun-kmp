package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.identity.CredentialProblem
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.SignInCredential
import com.machankura.dedun.compose.identity.StoredIdentity
import com.machankura.dedun.compose.repository.NostrRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a confirmed sign-in does, and in what order.
 *
 * The write and the placeholder are two effects, and the second must follow the first
 * and only on success: a placeholder for a key that was not saved would send the user
 * to fetch a profile they can never sign for, and a placeholder planted before the
 * write is a race the navigation observer can lose. The repository fake records every
 * call, so both "exactly once" and "after" are assertable.
 */
class SignInToProfileViewModelJvmTest {

    private val privateKey = PrivateKey(ByteVector32("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"))
    private val publicKey = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"
    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean".split(" ")
    private val id = WalletId("ab".repeat(20))

    /** The id a key already on the device is listed under, which the refusal carries so the screen can offer to switch to it. */
    private val alreadyHere = WalletId("cd".repeat(20))

    private val nostrSecret = SignInCredential.NostrSecret(privateKey, publicKey)
    private val recoveryPhrase = SignInCredential.RecoveryPhrase(words, publicKey)
    private val nostrPublicKey = SignInCredential.NostrPublicKey(publicKey)

    /** Records the order of effects; throws on anything the sign-in should not touch. */
    private class Recorder : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val effects = mutableListOf<String>()
        override suspend fun signInToProfile(publicKey: HexKey) {
            effects += "signInToProfile:$publicKey"
        }
    }

    private fun viewModel(
        recorder: Recorder,
        nostrKeyOutcome: () -> IdentityWriter.WriteNostrCredentialResult = { IdentityWriter.WriteNostrCredentialResult.Written(id) },
        publicKeyOutcome: () -> IdentityWriter.WriteNostrCredentialResult = { IdentityWriter.WriteNostrCredentialResult.Written(id) },
        seedOutcome: ((WalletId) -> Unit, (WritingSeedState.Error) -> Unit) -> Unit = { onWritten, _ -> onWritten(id) },
    ) = SignInToProfileViewModel(
        nostrRepository = recorder,
        writeNostrKey = { recorder.effects += "writeNostrKey"; nostrKeyOutcome() },
        writeNostrPublicKey = { recorder.effects += "writeNostrPublicKey"; publicKeyOutcome() },
        writeRecoveryPhrase = { _, onWritten, onError -> recorder.effects += "writeRecoveryPhrase"; seedOutcome(onWritten, onError) },
    )

    @Test
    fun `a public key is written, then its account is planted, then the id comes back`() = runBlocking {
        val recorder = Recorder()

        val outcome = viewModel(recorder).commit(nostrPublicKey)

        assertEquals(SignInToProfileViewModel.Outcome.SignedIn(id), outcome)
        assertEquals(listOf("writeNostrPublicKey", "signInToProfile:$publicKey"), recorder.effects)
    }

    @Test
    fun `the npub of a key already here is refused, and no account is planted`() = runBlocking {
        val recorder = Recorder()

        val outcome = viewModel(recorder, publicKeyOutcome = { IdentityWriter.WriteNostrCredentialResult.AlreadyExists(alreadyHere) }).commit(nostrPublicKey)

        assertEquals(SignInToProfileViewModel.Outcome.Failed(CredentialProblem.AlreadyOnThisDevice, alreadyHere), outcome)
        assertEquals(listOf("writeNostrPublicKey"), recorder.effects)
    }

    @Test
    fun `an nsec is written, then its account is planted, then the id comes back`() = runBlocking {
        val recorder = Recorder()

        val outcome = viewModel(recorder).commit(nostrSecret)

        assertEquals(SignInToProfileViewModel.Outcome.SignedIn(id), outcome)
        assertEquals(listOf("writeNostrKey", "signInToProfile:$publicKey"), recorder.effects)
    }

    @Test
    fun `a recovery phrase goes through the seed writer and plants the same account`() = runBlocking {
        val recorder = Recorder()

        val outcome = viewModel(recorder).commit(recoveryPhrase)

        assertEquals(SignInToProfileViewModel.Outcome.SignedIn(id), outcome)
        assertEquals(listOf("writeRecoveryPhrase", "signInToProfile:$publicKey"), recorder.effects)
    }

    @Test
    fun `a key already on the device is refused, and no account is planted`() = runBlocking {
        val recorder = Recorder()

        val outcome = viewModel(recorder, nostrKeyOutcome = { IdentityWriter.WriteNostrCredentialResult.AlreadyExists(alreadyHere) }).commit(nostrSecret)

        assertEquals(SignInToProfileViewModel.Outcome.Failed(CredentialProblem.AlreadyOnThisDevice, alreadyHere), outcome)
        assertEquals(listOf("writeNostrKey"), recorder.effects)
    }

    @Test
    fun `a seed already on the device is the same refusal`() = runBlocking {
        val recorder = Recorder()

        val outcome = viewModel(recorder, seedOutcome = { _, onError -> onError(WritingSeedState.Error.SeedAlreadyExists(alreadyHere)) })
            .commit(recoveryPhrase)

        assertEquals(SignInToProfileViewModel.Outcome.Failed(CredentialProblem.AlreadyOnThisDevice, alreadyHere), outcome)
        assertEquals(listOf("writeRecoveryPhrase"), recorder.effects)
    }

    @Test
    fun `a store that cannot be read, or a write that throws, is could-not-write`() = runBlocking {
        val recorder = Recorder()

        assertEquals(
            SignInToProfileViewModel.Outcome.Failed(CredentialProblem.CouldNotWrite),
            viewModel(recorder, nostrKeyOutcome = { IdentityWriter.WriteNostrCredentialResult.CannotLoadKeys }).commit(nostrSecret),
        )
        assertEquals(
            SignInToProfileViewModel.Outcome.Failed(CredentialProblem.CouldNotWrite),
            viewModel(recorder, nostrKeyOutcome = { throw IllegalStateException("disk") }).commit(nostrSecret),
        )
        assertEquals(
            SignInToProfileViewModel.Outcome.Failed(CredentialProblem.CouldNotWrite),
            viewModel(recorder, seedOutcome = { _, onError -> onError(WritingSeedState.Error.Generic(IllegalStateException("disk"))) })
                .commit(recoveryPhrase),
        )
        assertEquals(emptyList(), recorder.effects.filter { it.startsWith("signInToProfile") })
    }

    /** The credential the parser builds for the phrase and the one it builds for the key agree on the account. */
    @Test
    fun `both credentials of one key plant the same account`() {
        assertEquals(StoredIdentity.nostrPublicKeyOf(words), nostrSecret.nostrPublicKey)
    }
}
