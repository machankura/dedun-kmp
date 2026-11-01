package com.machankura.dedun.compose.identity

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.MnemonicLanguage

/**
 * What a user pasted into the sign-in field, once it has been recognised.
 *
 * All three carry the nostr public key, because the confirm step shows it and the
 * duplicate check compares it. None prints a secret.
 */
sealed interface SignInCredential {
    val nostrPublicKey: HexKey

    /** Twelve (or more) words: a wallet, and the nostr key derived from it. */
    data class RecoveryPhrase(
        val words: List<String>,
        override val nostrPublicKey: HexKey,
    ) : SignInCredential {
        override fun toString(): String = "RecoveryPhrase(npub=$nostrPublicKey, words=***)"
    }

    /** A bare nostr secret. No wallet comes with it. */
    data class NostrSecret(
        val privateKey: PrivateKey,
        override val nostrPublicKey: HexKey,
    ) : SignInCredential {
        override fun toString(): String = "NostrSecret(npub=$nostrPublicKey, key=<redacted>)"
    }

    /** A bare nostr public key. Nothing can be signed with it: a profile to look at. */
    data class NostrPublicKey(
        override val nostrPublicKey: HexKey,
    ) : SignInCredential
}

/** Why an input was not accepted. The screen turns each into a sentence. */
enum class CredentialProblem {
    /** Nothing was entered. */
    Empty,

    /** Not words, not an nsec, not hex: nothing this screen knows how to read. */
    NotRecognised,

    /** Words, but the checksum or the wordlist says no: a wrong word, or one out of order. */
    InvalidPhrase,

    /** An nsec or hex that does not decode to a valid secp256k1 secret, or an npub that does not decode to a point on the curve. */
    InvalidKey,

    /** An ncryptsec (NIP-49). Decrypting one is a follow-on; say so rather than fail opaquely. */
    EncryptedKey,

    /** An identity with this nostr public key is already on the device, as a wallet or a key. */
    AlreadyOnThisDevice,

    /** The secret could not be written to disk. */
    CouldNotWrite,
}

/**
 * Recognises a pasted credential. Pure, so it can be pinned row by row.
 *
 * A user does not choose an input type; they paste what they have. The three accepted
 * shapes are unambiguous -- words have spaces, keys do not, and a bech32 prefix says
 * which key -- and the one refused shape is named for what it is rather than lumped in
 * with garbage, because a user who pasted an ncryptsec did something reasonable and
 * should be told what to do instead.
 *
 * **Hex stays a secret.** A private key and an x-only public key are both thirty-two
 * bytes, and sixty-four hex characters cannot say which. The confirm step shows the
 * derived npub, so a public key pasted as hex is visible for what it is; guessing --
 * "not a valid secret, so try it as a public key" -- would never fire when it mattered,
 * since an x coordinate is, with overwhelming probability, also a valid scalar. An npub
 * has to arrive as an npub.
 */
object CredentialParser {

    sealed interface Result {
        data class Recognised(val credential: SignInCredential) : Result
        data class Refused(val problem: CredentialProblem) : Result
    }

    private val hex64 = Regex("[0-9a-f]{64}")

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Refused(CredentialProblem.Empty)

        // Whitespace anywhere inside means words. Not lower-cased: the wordlist already
        // is, and a capitalised word is a paste artefact worth showing rather than fixing.
        val words = text.split(Regex("\\s+"))
        if (words.size > 1) return recoveryPhrase(words)

        // Bech32 is case-insensitive as a whole but rejects mixed case, and a `nostr:`
        // prefix is how a key arrives from a link.
        val token = text.removePrefix("nostr:").lowercase()
        return when {
            token.startsWith("npub1") -> npub(token)
            token.startsWith("ncryptsec1") -> Result.Refused(CredentialProblem.EncryptedKey)
            token.startsWith("nsec1") -> nsec(token)
            hex64.matches(token) -> secret(runCatching { PrivateKey.fromHex(token) }.getOrNull())
            else -> Result.Refused(CredentialProblem.NotRecognised)
        }
    }

    private fun recoveryPhrase(words: List<String>): Result = try {
        MnemonicCode.validate(mnemonics = words, wordlist = MnemonicLanguage.English.wordlist())
        Result.Recognised(SignInCredential.RecoveryPhrase(words, StoredIdentity.nostrPublicKeyOf(words)))
    } catch (e: Exception) {
        Result.Refused(CredentialProblem.InvalidPhrase)
    }

    private fun nsec(token: String): Result {
        val bytes = runCatching {
            val (hrp, data) = Bech32.decodeBytes(token)
            require(hrp == "nsec") { "not an nsec" }
            data
        }.getOrNull() ?: return Result.Refused(CredentialProblem.InvalidKey)
        return secret(runCatching { PrivateKey(bytes) }.getOrNull())
    }

    private fun secret(privateKey: PrivateKey?): Result {
        if (privateKey == null || !privateKey.isValid()) return Result.Refused(CredentialProblem.InvalidKey)
        return Result.Recognised(SignInCredential.NostrSecret(privateKey, privateKey.nostrPublicKeyHex()))
    }

    /**
     * The x-only key under an `npub1…`, with or without a `nostr:` prefix, or null.
     *
     * The one shape a message address can arrive in, for the field that takes one. Hex
     * stays a secret here for the reason [parse] gives, and an nsec is null because it is
     * not an address -- a field asking whom to write to must never take a secret for an
     * answer. Same decode as the sign-in branch, so the two cannot disagree.
     */
    fun npubOrNull(raw: String): HexKey? {
        val token = raw.trim().removePrefix("nostr:").lowercase()
        if (!token.startsWith("npub1")) return null
        return decodeNpub(token)
    }

    private fun npub(token: String): Result =
        decodeNpub(token)
            ?.let { Result.Recognised(SignInCredential.NostrPublicKey(it)) }
            ?: Result.Refused(CredentialProblem.InvalidKey)

    /**
     * Thirty-two bytes under the `npub` prefix that name a point on the curve -- the
     * counterpart of the `isValid()` a secret is checked with, since not every x
     * coordinate has a point above it.
     */
    private fun decodeNpub(token: String): HexKey? {
        val bytes = runCatching {
            val (hrp, data) = Bech32.decodeBytes(token)
            require(hrp == "npub") { "not an npub" }
            require(data.size == 32) { "not an x-only key" }
            data
        }.getOrNull() ?: return null
        val xOnly = XonlyPublicKey(ByteVector32(bytes))
        if (!xOnly.publicKey.isValid()) return null
        return xOnly.value.toHex()
    }
}
