package com.machankura.dedun.compose.managers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every place that asks a room for "its ceremony" and takes the newest one.
 *
 * In a NIP-17 room that question has no single answer once the same people have made
 * a second group there (docs/nip17-multiple-ceremonies.md). Phase 0 found six such
 * reads in the app; Phase 3 took every one of them out. What is left of the queries
 * serves tests of the DAO itself.
 *
 * Like `CeremonyEntryPointsJvmTest`, this counts rather than proves: it fails the day
 * a new caller appears, so that adding one is a decision somebody makes here.
 * Whitespace is dropped before matching, so a call split across lines still counts.
 */
class CeremonyLookupsJvmTest {

    private val source: File =
        File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")

    private val lookups = listOf(
        "dkgSessionDao().getLatestSessionFor(",
        "dkgSessionDao().observeLatestSessionFor(",
        "dkgSessionDao().getLatestOwnSessionForChatRoom(",
        "dkgSessionDao().getLatestSessionForChatRoom(",
        "dkgSessionDao().observeLatestSessionForChatRoom(",
        "dkgRepository.observeLatestSessionForChatRoom(",
        "dkgRepository.getLatestSessionForChatRoom(",
    )

    @Test
    fun `nothing in the app takes a room's newest ceremony`() {
        val found = source.resolve("commonMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText().filterNot { it.isWhitespace() }
                lookups.mapNotNull { lookup ->
                    text.split(lookup).size.minus(1).takeIf { it > 0 }
                        ?.let { "${file.nameWithoutExtension}: $lookup" to it }
                }
            }
            .toMap()

        // None, since docs/nip17-multiple-ceremonies.md, Phase 3. Every screen and
        // manager names the ceremony it means, or asks for all of a room's.
        assertEquals(emptyMap(), found)
    }
}
