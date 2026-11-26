package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.crypto.random.RandomInstance

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.NewProfile
import com.machankura.dedun.compose.identity.NewProfileWriter
import com.machankura.dedun.compose.identity.StoredIdentity
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeKeys

/**
 * A profile with a wallet attached, backed by a fresh nine-block recovery code: written with
 * its 24 words and its key by [IdentityWriter.writeRecoveryCode], and its node started from
 * those words like any phrase profile's. What Landing makes, since docs/giza-second-batch.md,
 * Phase 2f (D9); before that it was twelve random words, and no profile's words are made
 * from random bytes any more (`docs/scripts/phrase-audit.sh`).
 */
fun SovereignWalletViewModel.recoveryCodeProfileWriter(): NewProfileWriter = NewProfileWriter {
    val code = RecoveryCode.generate()
    when (val result = writeRecoveryCode(code)) {
        is IdentityWriter.WriteRecoveryCodeResult.Written ->
            Result.success(NewProfile(StoredIdentity.nostrPublicKeyOf(RecoveryCodeKeys.words(code)), result.id))
        is IdentityWriter.WriteRecoveryCodeResult.AlreadyExists -> Result.failure(IllegalStateException("a fresh code collided with one already here"))
        is IdentityWriter.WriteRecoveryCodeResult.CannotLoadKeys -> Result.failure(IllegalStateException("the key files could not be read"))
    }
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
