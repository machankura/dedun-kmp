package com.machankura.dedun.compose.identity

import com.machankura.dedun.compose.extensions.hexToNpubHrp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Every row of the sign-in screen's table, in and out.
 *
 * The accepted rows all use NIP-06's own vector, so the three ways of writing one key --
 * twelve words, an nsec, sixty-four hex characters -- are asserted to land on the same
 * public key. That equality is what the duplicate check in `IdentityWriter` rests on.
 */
class CredentialParserJvmTest {

    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean"
    private val nsec = "nsec10allq0gjx7fddtzef0ax00mdps9t2kmtrldkyjfs8l5xruwvh2dq0lhhkp"
    private val hex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val publicKey = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"
    private val npub = "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu"

    private fun recognised(input: String): SignInCredential =
        assertIs<CredentialParser.Result.Recognised>(CredentialParser.parse(input), "for '$input'").credential

    private fun refused(input: String): CredentialProblem =
        assertIs<CredentialParser.Result.Refused>(CredentialParser.parse(input), "for '$input'").problem

    @Test
    fun `twelve words are a recovery phrase, and derive the NIP-06 key`() {
        val credential = assertIs<SignInCredential.RecoveryPhrase>(recognised(words))
        assertEquals(words.split(" "), credential.words)
        assertEquals(publicKey, credential.nostrPublicKey)
    }

    @Test
    fun `a phrase pasted with line breaks and extra spaces is the same phrase`() {
        val credential = assertIs<SignInCredential.RecoveryPhrase>(
            recognised("  leader  monkey\nparrot ring\tguide accident before fence cannon height naive bean \n")
        )
        assertEquals(publicKey, credential.nostrPublicKey)
    }

    @Test
    fun `an nsec, a nostr-prefixed nsec and the hex all name the same key`() {
        for (input in listOf(nsec, "nostr:$nsec", hex, nsec.uppercase(), hex.uppercase())) {
            val credential = assertIs<SignInCredential.NostrSecret>(recognised(input), "for '$input'")
            assertEquals(publicKey, credential.nostrPublicKey, "for '$input'")
            assertEquals(hex, credential.privateKey.value.toHex(), "for '$input'")
        }
    }

    @Test
    fun `nothing is nothing`() {
        assertEquals(CredentialProblem.Empty, refused(""))
        assertEquals(CredentialProblem.Empty, refused("   \n "))
    }

    @Test
    fun `a single word that is not a key is not recognised`() {
        assertEquals(CredentialProblem.NotRecognised, refused("hello"))
        assertEquals(CredentialProblem.NotRecognised, refused("nostr:"))
        assertEquals(CredentialProblem.NotRecognised, refused("7f7ff03d")) // hex, but not 64 of it
    }

    @Test
    fun `a wrong word, a missing word or a capitalised word is an invalid phrase`() {
        assertEquals(CredentialProblem.InvalidPhrase, refused(words.replace("bean", "beans")))
        assertEquals(CredentialProblem.InvalidPhrase, refused(words.substringBeforeLast(" ")))
        // The checksum would pass if this were lower-cased; the wordlist is lower-case and
        // a capital is a paste artefact the user should see rather than have silently fixed.
        assertEquals(CredentialProblem.InvalidPhrase, refused(words.replaceFirstChar { it.uppercase() }))
    }

    @Test
    fun `an npub, a nostr-prefixed npub and the upper-cased form all name the same public key`() {
        for (input in listOf(npub, "nostr:$npub", npub.uppercase())) {
            val credential = assertIs<SignInCredential.NostrPublicKey>(recognised(input), "for '$input'")
            assertEquals(publicKey, credential.nostrPublicKey)
        }
    }

    /**
     * Sixty-four hex characters are a secret and stay one: a private key and an x-only
     * public key are the same size, and the parser cannot tell them apart -- nor may it
     * guess, since an x coordinate is almost always also a valid scalar and the guess
     * would never fire. The public key of the vector, pasted as hex, is a *different*
     * secret whose npub the confirm step shows.
     */
    @Test
    fun `sixty-four hex characters are still a secret, even when they are a public key`() {
        val credential = assertIs<SignInCredential.NostrSecret>(recognised(publicKey))
        assertNotEquals(publicKey, credential.nostrPublicKey)
    }

    @Test
    fun `an npub that does not decode, or names no point on the curve, is an invalid key`() {
        assertEquals(CredentialProblem.InvalidKey, refused(npub.dropLast(1) + "q"))
        // The field prime, bech32-encoded under npub: sixty-four hex characters that are
        // not an x coordinate.
        val notOnTheCurve = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f".hexToNpubHrp()
        assertEquals(CredentialProblem.InvalidKey, refused(notOnTheCurve))
    }

    @Test
    fun `an encrypted key is named for what it is`() {
        assertEquals(CredentialProblem.EncryptedKey, refused("ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"))
    }

    @Test
    fun `an nsec that does not decode, or a hex that is not a valid secret, is an invalid key`() {
        assertEquals(CredentialProblem.InvalidKey, refused(nsec.dropLast(1) + "q"))
        assertEquals(CredentialProblem.InvalidKey, refused("0".repeat(64)))
        assertEquals(CredentialProblem.InvalidKey, refused("f".repeat(64)))
    }

    // --- the address form: an npub or nothing ---

    /** The three spellings the sign-in accepts, plus the whitespace a paste brings. */
    @Test
    fun `an address is an npub in any of its spellings`() {
        for (input in listOf(npub, "nostr:$npub", npub.uppercase(), "  $npub\n")) {
            assertEquals(publicKey, CredentialParser.npubOrNull(input), "for '$input'")
        }
    }

    /**
     * A field asking whom to write to must never take a secret for an answer, and it
     * takes nothing else either: the hex the sign-in reads as a secret, an nprofile's
     * TLV, and an npub with a bad checksum are all nothing here.
     */
    @Test
    fun `an address is not a secret, a hex, an nprofile or a broken npub`() {
        val nprofile = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"
        for (input in listOf(nsec, "nostr:$nsec", hex, publicKey, nprofile, npub.dropLast(1) + "q", "", "   ")) {
            assertNull(CredentialParser.npubOrNull(input), "for '$input'")
        }
    }

    // --- recovery codes (docs/recovery-code-v3.md, Phase 5) ---

    private val code = "LBZC-MYTK-E9P3-7C7B-AHJN-KX79-22CY-WAY7-K7QZ"
    /** RecoveryCodeKeysTest's pinned npub for [code]. */
    private val codePublicKey = "ece2bc031552ccd5062f6fb933f7e00187e6391df2b81fe0a83e697a7589b89f"

    @Test
    fun `a recovery code, typed any way, is the same code`() {
        for (input in listOf(code, code.lowercase(), code.replace('-', ' '), code.replace("-", ""), "  $code\n", code.replace("-", "  "))) {
            val credential = assertIs<SignInCredential.RecoveryCode>(recognised(input), "for '$input'")
            assertEquals(code, credential.code.canonical, "for '$input'")
            assertEquals(codePublicKey, credential.nostrPublicKey, "for '$input'")
        }
    }

    @Test
    fun `one wrong symbol is a typo, with the one code it could be`() {
        val result = assertIs<CredentialParser.Result.Refused>(CredentialParser.parse("LBTP-WJRN-FXBT-U8KT-ZP5P-AQN9-XK3J-K3CV-2TB5"))
        assertEquals(CredentialProblem.RecoveryCodeTypo, result.problem)
        val suggestion = result.suggestion ?: error("no suggestion")
        assertEquals(5, suggestion.block)
        assertEquals(2, suggestion.character)
        assertEquals("LBTP-WJRN-FXBT-U8KT-Z45P-AQN9-XK3J-K3CV-2TB5", suggestion.suggested.canonical)
    }

    /**
     * Two typos: refused as a typo, and with no suggestion -- or, where two errors happen to
     * sit one symbol from another valid code, with a suggestion that is *not* the original.
     * That second case is why a suggestion is never applied without a press.
     */
    @Test
    fun `two wrong symbols are a typo without the original as a suggestion`() {
        val result = assertIs<CredentialParser.Result.Refused>(CredentialParser.parse("LBTP-RJRN-FXBT-U8KT-Z45P-AQN9-XK33-K3CV-2TB5"))
        assertEquals(CredentialProblem.RecoveryCodeTypo, result.problem)
        assertNotEquals("LBTP-WJRN-FXBT-U8KT-Z45P-AQN9-XK3J-K3CV-2TB5", result.suggestion?.suggested?.canonical)
    }

    @Test
    fun `an older code is named for what it is`() {
        assertEquals(CredentialProblem.UnsupportedRecoveryCode, refused("LA2Q-48Z3-25JR-S5JB-5SUS-HXHJ-RCMM-8YUA"))
        assertEquals(CredentialProblem.UnsupportedRecoveryCode, refused("R52Q-48Z3-25JR-S5JB-5SUS-HXHJ-RCMM-8YUA"))
    }

    @Test
    fun `the shape of a code that is not one is refused as a code`() {
        assertEquals(CredentialProblem.InvalidRecoveryCode, refused("LBZC-MOTK-OOOO-7C7B-AHJN-KX79-22CY-WAY7-K7QZ"))
        assertEquals(CredentialProblem.InvalidRecoveryCode, refused("ABCD EFGH JKLM NPQR STUV WXYZ 2345 7899"))
    }

    /** Twelve groups is a phrase, however short its words: only eight or nine is a code. */
    @Test
    fun `twelve four-letter words are still a phrase`() {
        assertEquals(CredentialProblem.InvalidPhrase, refused("able acid also area army atom aunt auto away baby back bake"))
    }

    /** The code's 24 words are a phrase, and the same person. */
    @Test
    fun `a code's words are the code's key`() {
        val parsed = assertIs<SignInCredential.RecoveryCode>(recognised(code))
        val words = com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeKeys.words(parsed.code).joinToString(" ")
        assertEquals(codePublicKey, assertIs<SignInCredential.RecoveryPhrase>(recognised(words)).nostrPublicKey)
    }
}
