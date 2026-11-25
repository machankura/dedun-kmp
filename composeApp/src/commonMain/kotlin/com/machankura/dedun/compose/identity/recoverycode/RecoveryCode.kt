package com.machankura.dedun.compose.identity.recoverycode

import com.vitorpamplona.crypto.random.RandomInstance
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeFormat.Checked

/**
 * Why an input is not a recovery code. The first check to fail decides it, in the order of
 * the spec's §4 -- which the spec's vectors assert.
 */
enum class RecoveryCodeProblem {
    /** The wrong number of symbols for the version the first two name. */
    Length,

    /** A symbol outside the alphabet, or a dash out of place. */
    Characters,

    /** `L` followed by a symbol that names no version this app knows. */
    Version,

    /** Well formed, and the ninth block disagrees: a typo, more than one symbol's worth. */
    Checksum,
}

/**
 * A version 3 recovery code, canonical and checksummed: nine blocks of four, 44 characters
 * with their dashes. The only way to hold one is through [parse] or [generate], so a
 * `RecoveryCode` is always one that derives a key.
 *
 * The format is Muun's (libwallet's `recoverycode`, at the `recovery-code-v3-checksum`
 * branch of `kngako/libwallet`); what Dedun derives from it is Dedun's. See
 * docs/recovery-code-v3.md.
 */
class RecoveryCode private constructor(
    /** The 44-character form, dashes included. This exact string is what [rootKey] hashes. */
    val canonical: String,
) {
    /** The nine blocks, in order, for showing a person one at a time. */
    val blocks: List<String> get() = canonical.split('-')

    /**
     * `HMAC-SHA256(key = "muun:rc", message = canonical)` -- the spec's §7 for versions 2 and
     * 3. Thirty-two bytes, and the root of everything a code derives.
     */
    fun rootKey(): ByteVector32 = ByteVector32(rootKeyOf(canonical))

    override fun equals(other: Any?): Boolean = other is RecoveryCode && other.canonical == canonical
    override fun hashCode(): Int = canonical.hashCode()

    /** Never the code: it is a secret, and a log line is not a place for one. */
    override fun toString(): String = "RecoveryCode(***)"

    companion object {
        private val KDF_KEY = "muun:rc".encodeToByteArray()

        private fun rootKeyOf(canonical: String): ByteArray =
            Digest.sha256().hmac(KDF_KEY, canonical.encodeToByteArray(), 64)

        /**
         * What [input] is, read as a person types a code: any case, dashes or spaces or
         * neither. A [Parse.Typo] is a suggestion and nothing more; the only way to turn one
         * into a [RecoveryCode] is for a person to accept [Parse.Typo.suggested].
         */
        fun parse(input: String): Parse {
            val canonical = when (val normalized = RecoveryCodeFormat.normalize(input)) {
                is Checked.Ok -> normalized.value
                is Checked.Failed -> return typoOr(input, normalized.problem)
            }
            return when (val version = RecoveryCodeFormat.version(canonical)) {
                is Checked.Failed -> typoOr(input, version.problem)
                is Checked.Ok -> when (version.value) {
                    3 -> Parse.Valid(RecoveryCode(canonical))
                    else -> Parse.UnsupportedVersion(version.value)
                }
            }
        }

        /**
         * A typo where there is exactly one, and otherwise the problem as found. A version
         * symbol mistyped -- `LC…` for `LB…` -- fails normalisation before it fails the
         * checksum, so a 36-symbol input is searched whatever the first failure was.
         */
        private fun typoOr(input: String, problem: RecoveryCodeProblem): Parse {
            val typo = RecoveryCodeFormat.locateTypo(input)
            if (typo != null) return Parse.Typo(typo.position, RecoveryCode(typo.corrected))
            return Parse.Invalid(problem)
        }

        /**
         * A fresh version 3 code: `L`, `B`, thirty symbols drawn without modulo bias, and the
         * checksum block.
         *
         * Each symbol is one byte from [random], rejected below `256 % 30 = 16` so that the
         * bytes kept are an exact multiple of thirty. That threshold is the one libwallet
         * fixed in `75e43b45`: the idiom it replaced rejected nothing, and the first sixteen
         * symbols came up 9/256 of the time instead of 8/256.
         *
         * A code whose root key is not a valid secp256k1 scalar is drawn again -- about one in
         * 2¹²⁸ -- so that every code Dedun makes is a valid key to every implementation of the
         * spec, which calls the key a scalar.
         */
        fun generate(random: (Int) -> ByteArray = RandomInstance::bytes): RecoveryCode {
            while (true) {
                val symbols = CharArray(RecoveryCodeFormat.V3_SYMBOLS)
                symbols[0] = 'L'
                symbols[1] = RecoveryCodeFormat.versionChar(3)
                for (i in 0 until RecoveryCodeFormat.RANDOM_SYMBOLS) symbols[2 + i] = randomSymbol(random)
                val (s1, s2) = RecoveryCodeFormat.checksum(symbols)
                RecoveryCodeFormat.encodeChecksum(s1, s2).copyInto(symbols, RecoveryCodeFormat.BODY_SYMBOLS)
                val canonical = RecoveryCodeFormat.format(symbols.concatToString())
                if (PrivateKey(rootKeyOf(canonical)).isValid()) return RecoveryCode(canonical)
            }
        }

        private fun randomSymbol(random: (Int) -> ByteArray): Char {
            val alphabet = RecoveryCodeFormat.ALPHABET
            val min = 256 % alphabet.length
            while (true) {
                val r = random(1)[0].toInt() and 0xff
                if (r < min) continue
                return alphabet[r % alphabet.length]
            }
        }

        /**
         * How many blocks a code beginning with [prefix] has -- 8 for the older versions, 9 for
         * version 3 -- or null while the prefix cannot tell. For an input that sizes itself.
         */
        fun blockCount(prefix: String): Int? = RecoveryCodeFormat.blockCount(prefix)
    }

    /** What an input turned out to be. */
    sealed interface Parse {
        data class Valid(val code: RecoveryCode) : Parse

        /**
         * One symbol away from exactly one valid code. [position] is 0-based over the 36
         * symbols, dashes excluded; [block] and [character] are 1-based, for a sentence.
         */
        data class Typo(val position: Int, val suggested: RecoveryCode) : Parse {
            val block: Int get() = position / RecoveryCodeFormat.BLOCK_SIZE + 1
            val character: Int get() = position % RecoveryCodeFormat.BLOCK_SIZE + 1

            /** The symbol the suggestion puts at [position]. */
            val suggestedSymbol: Char get() = suggested.canonical.filter { it != '-' }[position]
        }

        /** Not a code, or a code with more wrong than one symbol can explain. */
        data class Invalid(val problem: RecoveryCodeProblem) : Parse

        /** A well-formed code of version 1 or 2. Dedun never made either. */
        data class UnsupportedVersion(val version: Int) : Parse
    }
}
