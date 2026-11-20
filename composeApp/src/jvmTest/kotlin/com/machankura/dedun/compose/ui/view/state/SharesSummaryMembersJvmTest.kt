package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.types.ShareStanding
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Shares section draws a row per member, keyed by their nostr key, from a
 * roster with a row per participant id. An admin removed and reinstated has two
 * ids under one key, and two rows with one key crashed the room detail screen.
 */
class SharesSummaryMembersJvmTest {
    private val founder = "f".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private fun member(key: String, standing: ShareStanding, isSelf: Boolean = false) =
        SharesSummary.Member(key, key.take(8), standing, isSelf)

    @Test
    fun aReinstatedAdminIsOneRowShowingTheNewId() {
        // Ids in participant order: the founder's first id, retired when they were
        // removed as admin, and the one bound when they were made admin again.
        val perId = listOf(
            member(founder, ShareStanding.RETIRED),
            member(bob, ShareStanding.LIVE),
            member(carol, ShareStanding.LIVE),
            member(founder, ShareStanding.LIVE),
        )

        val members = SharesSummary.byMember(perId)

        assertEquals(listOf(founder, bob, carol), members.map { it.publicKey })
        assertEquals(ShareStanding.LIVE, members.first().standing)
    }

    @Test
    fun aNewIdNotYetEnrolledReadsStaleNotRetired() {
        val members = SharesSummary.byMember(
            listOf(member(founder, ShareStanding.RETIRED, isSelf = true), member(founder, ShareStanding.STALE, isSelf = true))
        )

        assertEquals(listOf(member(founder, ShareStanding.STALE, isSelf = true)), members)
    }

    @Test
    fun aMemberRemovedForGoodStaysRetired() {
        val members = SharesSummary.byMember(listOf(member(bob, ShareStanding.LIVE), member(carol, ShareStanding.RETIRED)))

        assertEquals(listOf(ShareStanding.LIVE, ShareStanding.RETIRED), members.map { it.standing })
    }
}
