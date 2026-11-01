package com.machankura.dedun.compose.identity

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.data.WalletId

/** What a [NewProfileWriter] made: the key the new profile signs as, and the id it is listed under. */
data class NewProfile(val nostrPublicKey: HexKey, val id: WalletId)

/**
 * Makes the secret a new profile signs with and puts it on disk.
 *
 * Two live in the app, and the create screen does not know which it was given. One
 * generates twelve words and writes a seed -- for Landing, where the first profile on a
 * device is the one the wallet will belong to. The other generates a bare key and
 * writes a credential -- for the switcher, where a user who wants another profile has
 * not asked for another Lightning node, another set of channels, or a second phrase
 * with funds behind it. Under docs/multiple-profiles.md the two differ by exactly one
 * thing, whether a wallet is attached to the profile they make, which is what the model
 * was for.
 */
fun interface NewProfileWriter {
    /** Generates and writes the secret. A failure is a write that did not happen; nothing is half-made. */
    suspend fun write(): Result<NewProfile>
}
