package com.machankura.dedun.compose.managers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every place that asks a room for "its ceremony" and takes the newest one.
 *
 * In a NIP-17 room that question has no single answer once the same people have made
 * a second group there (docs/nip17-multiple-ceremonies.md). Each of these reads was
 * written while it did, and each one either has to stop asking or has to be right
 * because the room it asks is never a NIP-17 room.
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
    fun `the reads that take a room's newest ceremony are these and no others`() {
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

        assertEquals(
            mapOf(
                // The fold that keeps a second group from being made.
                "ChillDkgRitualManager: dkgSessionDao().getLatestSessionFor(" to 1,
                // The one admin room a NIP-17 room's details link to.
                "DatabaseChatRepository: dkgSessionDao().getLatestSessionFor(" to 1,
                // The repository's pass-throughs.
                "DatabaseDkgRepository: dkgSessionDao().observeLatestSessionFor(" to 1,
                "DatabaseDkgRepository: dkgSessionDao().getLatestSessionForChatRoom(" to 1,
                // The key a room signs with, last fallback.
                "FrostSigningManager: dkgSessionDao().getLatestOwnSessionForChatRoom(" to 1,
                // The shared-key screen handing over to the room's ceremony.
                "CreateSharedKeyViewModel: dkgRepository.observeLatestSessionForChatRoom(" to 1,
            ),
            found,
        )
    }
}
