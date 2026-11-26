package com.machankura.dedun.compose.identity.recoverycode

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.phoenix.utils.MnemonicLanguage

/**
 * What a recovery code derives: twenty-four words, and from them everything a phrase does.
 *
 * The code's root key is read as BIP39 entropy -- thirty-two bytes and their eight checksum
 * bits are exactly twenty-four words -- and the words go where a phrase's go: `seed.dat`, and
 * lightning-kmp's `LocalKeyManager`, which derives the nostr key at NIP-06's path, the
 * Lightning node at `m/50'/0'`, and the profile id as the node id's hash160. So a code is a
 * 24-word phrase in another notation. Its words typed into any NIP-06 client land on the npub
 * Dedun shows, the node they start is the wallet the code backs, and every path a phrase will
 * ever have, a code has too.
 *
 * The words do not give the code back: the HMAC under [RecoveryCode.rootKey] runs one way.
 * See docs/recovery-code-v3.md, "The one decision". `RecoveryCodeKeysTest` pins three codes
 * to their words, npub and id, cross-checked against docs/scripts/recovery-code-keys.py.
 */
object RecoveryCodeKeys {

    /** The 24 words [code] is. */
    fun words(code: RecoveryCode): List<String> =
        MnemonicCode.toMnemonics(code.rootKey().toByteArray(), MnemonicLanguage.English.wordlist())
}
