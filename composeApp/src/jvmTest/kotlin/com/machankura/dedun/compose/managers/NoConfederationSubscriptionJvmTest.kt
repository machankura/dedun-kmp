package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dedun takes no confederations (docs/giza-second-batch.md, D12 and Phase 9).
 *
 * A confederation's public events, 9430-9438, are regular kinds signed by a group's key: nothing
 * reaches a device unless it asks a relay for them. Giza asks; Dedun must not, and a later change
 * that starts asking is the change this pins. Two ways: every filter the live subscriptions
 * build leaves those kinds out, and no Kotlin source in the app names one of them.
 */
class NoConfederationSubscriptionJvmTest {

    private val confederationKinds = 9430..9438
    private val someone = "a".repeat(64)

    @Test
    fun `no live subscription asks for a confederation kind`() {
        val filters: List<Filter> = listOf(
            LiveSubscriptionManager.giftWrapFilter(someone),
            LiveSubscriptionManager.groupFilter(listOf(someone)),
        ) + LiveSubscriptionManager.groupInboxFilters(listOf(someone)) +
            LiveSubscriptionManager.groupDmFilters(listOf(GroupDmInbox(someone, listOf("wss://relay.example.com"), null))).values.flatten()

        val asked = filters.flatMap { it.kinds.orEmpty() }.toSet()
        assertTrue(asked.isNotEmpty(), "the filters asked for nothing, so this checks nothing")
        assertEquals(emptySet(), asked.filter { it in confederationKinds }.toSet())
    }

    @Test
    fun `no source in the app names a confederation kind`() {
        val sources = (File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src"))
        val main = listOf("commonMain", "androidMain", "jvmMain", "iosMain").map { sources.resolve(it) }.filter { it.isDirectory }
        assertTrue(main.isNotEmpty(), "no source sets found from ${File(".").absolutePath}")

        val literal = Regex("""\b943[0-8]\b""")
        val found = main.flatMap { set ->
            set.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trimStart()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!isComment && (literal.containsMatchIn(line) || "ConfederationKinds" in line)) "${file.name}:${i + 1}" else null
                }
            }
        }
        assertEquals(emptyList(), found)
    }
}
