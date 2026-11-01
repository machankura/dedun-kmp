package com.machankura.dedun.compose.ui.view.model

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.MnemonicLanguage
import kotlinx.coroutines.CompletableDeferred
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.NewProfile
import com.machankura.dedun.compose.identity.NewProfileWriter
import com.machankura.dedun.compose.identity.StoredIdentity

/**
 * A profile with a wallet attached: twelve fresh words through the seed writer, which
 * since docs/multiple-profiles.md writes the derived key to the credentials file as
 * well. Callback-shaped underneath, awaited here: exactly one of the two callbacks
 * fires.
 */
fun SovereignWalletViewModel.seedProfileWriter(): NewProfileWriter = NewProfileWriter {
    val words = MnemonicCode.toMnemonics(Lightning.randomBytes(16), MnemonicLanguage.English.wordlist())
    val outcome = CompletableDeferred<Result<NewProfile>>()
    writeSeed(
        mnemonics = words,
        isRestoringWallet = false,
        onSeedWritten = { id -> outcome.complete(Result.success(NewProfile(StoredIdentity.nostrPublicKeyOf(words), id))) },
        onSeedWriteError = { error -> outcome.complete(Result.failure(IllegalStateException("seed write failed: $error"))) },
    )
    outcome.await()
}

/** A profile that is a key: thirty-two random bytes, written the way a pasted nsec is. */
fun SovereignWalletViewModel.bareKeyProfileWriter(): NewProfileWriter = NewProfileWriter {
    val key = PrivateKey(Lightning.randomBytes(32))
    when (val result = writeNostrKey(key)) {
        is IdentityWriter.WriteNostrCredentialResult.Written -> Result.success(NewProfile(key.nostrPublicKeyHex(), result.id))
        is IdentityWriter.WriteNostrCredentialResult.AlreadyExists -> Result.failure(IllegalStateException("a fresh key collided with one already here"))
        is IdentityWriter.WriteNostrCredentialResult.CannotLoadKeys -> Result.failure(IllegalStateException("the credentials could not be read"))
    }
}
