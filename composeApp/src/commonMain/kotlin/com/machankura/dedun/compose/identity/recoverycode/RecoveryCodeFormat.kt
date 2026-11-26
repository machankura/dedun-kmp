package com.machankura.dedun.compose.identity.recoverycode

/**
 * Muun's recovery code format, versions 1 to 3, as specified on the
 * `recovery-code-v3-checksum` branch of `kngako/libwallet` at `7d58eb2e`
 * (`recoverycode/SPEC.md`). One function per section of the spec, so that each is tested
 * against its own table of the spec's vectors; see docs/recovery-code-v3.md, Phase 1.
 *
 * Internal: the app reads codes through [RecoveryCode.parse] and makes them through
 * [RecoveryCode.generate]. This is the arithmetic underneath, ported line for line from
 * libwallet's `recoverycode.go` so that a disagreement with the Go is a bug here.
 */
internal object RecoveryCodeFormat {

    /** The thirty symbols of a version 2 or 3 code: no `0 1 6 G I O`. */
    const val ALPHABET = "ABCDEFHJKLMNPQRSTUVWXYZ2345789"

    /** The twenty-nine symbols of a version 1 code: [ALPHABET] without `L`. */
    const val ALPHABET_LEGACY = "ABCDEFHJKMNPQRSTUVWXYZ2345789"

    const val LATEST_VERSION = 3

    const val BLOCK_SIZE = 4
    const val RANDOM_SYMBOLS = 30

    /** `L`, the version symbol, and the random symbols: what the checksum covers. */
    const val BODY_SYMBOLS = 32

    /** A version 3 code without its dashes. */
    const val V3_SYMBOLS = 36

    const val LEGACY_BLOCKS = 8
    const val V3_BLOCKS = 9

    /** Prime, and above every weight times every symbol difference, so none of them is zero. */
    private const val CHECKSUM_MODULUS = 887

    sealed interface Checked<out T> {
        data class Ok<T>(val value: T) : Checked<T>
        data class Failed(val problem: RecoveryCodeProblem) : Checked<Nothing>
    }

    /** A single wrong symbol and the one valid code it is a symbol away from (§6). */
    data class Typo(val position: Int, val corrected: String)

    /** The version symbol of a version 2 or later code. Version 2 is the alphabet's first symbol. */
    fun versionChar(version: Int): Char = ALPHABET[version - 2]

    private fun versionFromChar(c: Char): Int? {
        val index = ALPHABET.indexOf(c)
        if (index == -1) return null
        val version = index + 2
        return if (version > LATEST_VERSION) null else version
    }

    private fun blockCountFor(version: Int): Int = if (version >= 3) V3_BLOCKS else LEGACY_BLOCKS

    private fun formattedLength(blocks: Int): Int = blocks * (BLOCK_SIZE + 1) - 1

    /**
     * §4. The version of a code that is already canonical: upper case, dashes in place, no
     * whitespace. Strict on purpose -- [normalize] is what forgives.
     */
    fun version(code: String): Checked<Int> {
        if (code.length >= 2 && code[0] == 'L') {
            val version = versionFromChar(code[1]) ?: return Checked.Failed(RecoveryCodeProblem.Version)
            if (code.length != formattedLength(blockCountFor(version))) return Checked.Failed(RecoveryCodeProblem.Length)
            if (!validateFormat(code, ALPHABET)) return Checked.Failed(RecoveryCodeProblem.Characters)
            if (version >= 3 && !checksumMatches(stripDashes(code))) return Checked.Failed(RecoveryCodeProblem.Checksum)
            return Checked.Ok(version)
        }
        if (code.length != formattedLength(LEGACY_BLOCKS)) return Checked.Failed(RecoveryCodeProblem.Length)
        if (!validateFormat(code, ALPHABET_LEGACY)) return Checked.Failed(RecoveryCodeProblem.Characters)
        return Checked.Ok(1)
    }

    /**
     * §5. User input in canonical form: upper case, separators dropped, the symbol count
     * checked for the version the first two symbols name, a dash after every four. Checks
     * neither the alphabet nor the checksum; [version] does, on the result.
     */
    fun normalize(input: String): Checked<String> {
        val symbols = stripSeparators(input)
        var want = LEGACY_BLOCKS * BLOCK_SIZE
        if (symbols.length >= 2 && symbols[0] == 'L') {
            val version = versionFromChar(symbols[1]) ?: return Checked.Failed(RecoveryCodeProblem.Version)
            want = blockCountFor(version) * BLOCK_SIZE
        }
        if (symbols.length != want) return Checked.Failed(RecoveryCodeProblem.Length)
        return Checked.Ok(format(symbols))
    }

    /**
     * §6. The one symbol of a not-quite-valid version 3 code that, changed, makes it valid --
     * or null: the input is valid already, is not 36 symbols, has two or more symbols outside
     * the alphabet, or is a symbol away from no valid code or from more than one.
     *
     * Exhaustive, as the spec states it: 36 positions by 29 replacements is nothing. The
     * result is a suggestion. Two errors can sit one symbol from a *different* valid code.
     */
    fun locateTypo(input: String): Typo? {
        val symbols = stripSeparators(input).toCharArray()
        if (symbols.size != V3_SYMBOLS) return null

        val bad = symbols.indices.filter { ALPHABET.indexOf(symbols[it]) == -1 }
        val candidates = when (bad.size) {
            0 -> if (isValidV3(symbols)) return null else symbols.indices.toList()
            1 -> bad
            else -> return null
        }

        var found: Typo? = null
        for (i in candidates) {
            val original = symbols[i]
            for (c in ALPHABET) {
                if (c == original) continue
                symbols[i] = c
                if (isValidV3(symbols)) {
                    if (found != null) return null // ambiguous; distance 3 says it cannot happen
                    found = Typo(i, format(symbols.concatToString()))
                }
            }
            symbols[i] = original
        }
        return found
    }

    /**
     * §8. How many blocks a code that starts with [prefix] has: 8 for versions 1 and 2, 9 for
     * version 3. Null when the prefix cannot tell -- empty, a lone `L` -- or names no version.
     */
    fun blockCount(prefix: String): Int? {
        val symbols = stripSeparators(prefix)
        if (symbols.isEmpty()) return null
        if (symbols[0] != 'L') return LEGACY_BLOCKS
        if (symbols.length < 2) return null
        return versionFromChar(symbols[1])?.let(::blockCountFor)
    }

    /** §3. The two weighted sums over symbols 0–31, which must all be in [ALPHABET]. */
    fun checksum(symbols: CharArray): Pair<Int, Int> {
        var s1 = 0
        var s2 = 0
        for (i in 0 until BODY_SYMBOLS) {
            val v = ALPHABET.indexOf(symbols[i])
            val w = i + 1
            s1 = (s1 + w * v) % CHECKSUM_MODULUS
            s2 = (s2 + w * w * v) % CHECKSUM_MODULUS
        }
        return s1 to s2
    }

    fun encodeChecksum(s1: Int, s2: Int): CharArray {
        val n = ALPHABET.length
        return charArrayOf(ALPHABET[s1 / n], ALPHABET[s1 % n], ALPHABET[s2 / n], ALPHABET[s2 % n])
    }

    private fun decodeChecksumPair(a: Char, b: Char): Int? {
        val hi = ALPHABET.indexOf(a)
        val lo = ALPHABET.indexOf(b)
        if (hi == -1 || lo == -1) return null
        val v = hi * ALPHABET.length + lo
        return if (v >= CHECKSUM_MODULUS) null else v
    }

    private fun checksumMatches(symbols: CharArray): Boolean {
        if (symbols.size != V3_SYMBOLS) return false
        val (s1, s2) = checksum(symbols)
        val d1 = decodeChecksumPair(symbols[32], symbols[33])
        val d2 = decodeChecksumPair(symbols[34], symbols[35])
        return d1 == s1 && d2 == s2
    }

    private fun isValidV3(symbols: CharArray): Boolean {
        if (symbols[0] != 'L' || symbols[1] != versionChar(3)) return false
        if (symbols.any { ALPHABET.indexOf(it) == -1 }) return false
        return checksumMatches(symbols)
    }

    /** Every fifth character a dash, every other one in [alphabet]. The caller checks the length. */
    private fun validateFormat(code: String, alphabet: String): Boolean =
        code.indices.all { i ->
            if (i % (BLOCK_SIZE + 1) == BLOCK_SIZE) code[i] == '-' else alphabet.indexOf(code[i]) != -1
        }

    private fun stripDashes(code: String): CharArray = code.filter { it != '-' }.toCharArray()

    /** Upper case, without dashes or whitespace. */
    fun stripSeparators(input: String): String =
        input.uppercase().filter { it != '-' && !it.isWhitespace() }

    /** A dash after every four symbols. */
    fun format(symbols: String): String = symbols.chunked(BLOCK_SIZE).joinToString("-")
}
