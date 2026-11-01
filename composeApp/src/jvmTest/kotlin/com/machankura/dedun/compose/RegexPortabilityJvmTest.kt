package com.machankura.dedun.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every regex the app compiles is one Android's engine will compile too.
 *
 * `Regex` is `java.util.regex` on the JVM and ICU on Android, and they disagree about a
 * closing brace that closes nothing: the JVM reads a lone `}` as a literal, ICU refuses
 * the pattern. So `^\{"change":(\d+)}$` passes every test here and throws on a phone --
 * and because it sat in a `private val` of `FrostSigningManager`, it threw from the
 * class initialiser, which Android does not retry. Every signing session on the device
 * failed with `NoClassDefFoundError` until the app was restarted, the first symptom
 * being a ceremony that "couldn't ask the group to agree its signing key".
 *
 * These tests run on the JVM, so they cannot ask ICU. They read the pattern literals out
 * of the source instead and hold each to the stricter rule: a `}` is either escaped,
 * inside a character class, or the end of a `{n}`, `{n,}` or `{n,m}` quantifier.
 */
class RegexPortabilityJvmTest {

    @Test
    fun `no regex in the app has a brace that only the JVM accepts`() {
        val sources = SOURCE_SETS.flatMap { set ->
            sourceRoot().resolve(set).walkTopDown().filter { it.extension == "kt" }.toList()
        }
        assertTrue(sources.isNotEmpty(), "found no sources under ${sourceRoot().absolutePath}")

        val offenders = sources.flatMap { file ->
            patternLiterals(file.readText()).mapNotNull { pattern ->
                strayBrace(pattern)?.let { at -> "${file.name}: $pattern (index $at)" }
            }
        }

        assertEquals(emptyList(), offenders, "escape these as \\}")
    }

    @Test
    fun `the check catches the pattern that broke signing on Android`() {
        val broken = """^\{"change":(\d+),"index":(\d+)}$"""
        assertEquals(broken.lastIndexOf('}'), strayBrace(broken))
    }

    @Test
    fun `the check accepts escaped braces, quantifiers and braces in a class`() {
        assertEquals(null, strayBrace("""^\{"change":(\d+),"index":(\d+)\}$"""))
        assertEquals(null, strayBrace("[0-9a-f]{64}"))
        assertEquals(null, strayBrace("(?:\\r?\\n){2,}"))
        assertEquals(null, strayBrace("[0-9a-z]{50,80}"))
        assertEquals(null, strayBrace("[{}]+"))
    }

    @Test
    fun `the scan finds raw and escaped string literals`() {
        val source = """
            val a = Regex(${"\"\"\""}^\{"a":(\d+)}${'$'}${"\"\"\""})
            val b = Regex("\\s+", RegexOption.IGNORE_CASE)
            val c = "x{2}".toRegex()
        """
        assertEquals(
            listOf("""^\{"a":(\d+)}$""", """\s+""", "x{2}"),
            patternLiterals(source),
        )
    }

    private companion object {
        val SOURCE_SETS = listOf("commonMain", "androidMain", "jvmMain", "iosMain")

        val RAW_LITERAL = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val QUOTED_LITERAL = Regex("Regex\\(\\s*\"(?!\"\")((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val QUOTED_TO_REGEX = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\.toRegex\\(")
        val QUANTIFIER = Regex("\\{\\d+(,\\d*)?\\}")

        /** Gradle runs tests from the module directory; an IDE may run them from the root. */
        fun sourceRoot(): File =
            File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")

        /** The patterns as the regex engine sees them, Kotlin's own escapes undone. */
        fun patternLiterals(source: String): List<String> =
            RAW_LITERAL.findAll(source).map { it.groupValues[1] }.toList() +
                (QUOTED_LITERAL.findAll(source) + QUOTED_TO_REGEX.findAll(source))
                    .map { unescapeKotlin(it.groupValues[1]) }

        fun unescapeKotlin(literal: String): String = buildString {
            var i = 0
            while (i < literal.length) {
                val c = literal[i]
                if (c == '\\' && i + 1 < literal.length) {
                    when (val next = literal[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        /** Where the first `}` that closes nothing sits in [pattern], or null if none does. */
        fun strayBrace(pattern: String): Int? {
            var i = 0
            var inClass = false
            while (i < pattern.length) {
                when (pattern[i]) {
                    '\\' -> i++
                    '[' -> inClass = true
                    ']' -> inClass = false
                    '{' -> if (!inClass) {
                        QUANTIFIER.matchAt(pattern, i)?.let { i = it.range.last }
                    }
                    '}' -> if (!inClass) return i
                }
                i++
            }
            return null
        }
    }
}
