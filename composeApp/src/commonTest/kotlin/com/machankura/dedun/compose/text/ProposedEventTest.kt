package com.machankura.dedun.compose.text

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.ByteVector32
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.nostr.frost.GovernanceChange
import com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the events a group signs are said to the member signing them.
 *
 * These are read on the signing screen and in the room's list of proposals, and
 * the same words in both is the point of there being one mapping. What is
 * pinned is that a kind the app has no words for is said by its number rather
 * than described wrongly.
 */
class ProposedEventTest {

    @Test
    fun `a kind with no words of its own keeps its number`() {
        // Refusing to describe an event is better than describing it wrongly.
        val summary = ProposedEvent.summarize(event(30023, content = "An article"))

        assertEquals("Event of kind 30023", summary.label)
        assertEquals("An article", summary.detail)
    }

    @Test
    fun `a birth certificate says what it decides about the child's wallet, either way`() {
        val parent = "1".repeat(64)
        val child = "2".repeat(64)
        val key = PrivateKey(ByteVector32("01".repeat(32))).publicKey().toHex()
        fun certificate(policy: SubgroupWalletPolicy?, guarded: Boolean) = event(
            SubgroupBirthCertificateEvent.KIND,
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = child,
                parentChatRoomId = parent,
                thresholdPublicKey = key,
                adminPublicKeys = listOf("a".repeat(64)),
                name = "#Translation",
                guardians = if (guarded) GuardianDerivation.grant(key, parent, emptyList(), child) else emptyList(),
                walletPolicy = policy,
            ),
        )

        assertEquals(
            "#Translation · run by 1 members · this group can spend from its wallet · this group after 12 blocks, about 2 hours · its wallet must be guarded",
            ProposedEvent.summarize(certificate(SubgroupWalletPolicy.GUARDED, guarded = true)).detail,
        )
        // Not left to an absent line: a signer approving this is taking the
        // subgroup's wallet out of every group's reach above it.
        assertEquals(
            "#Translation · run by 1 members · its wallet will not be guarded",
            ProposedEvent.summarize(certificate(SubgroupWalletPolicy.UNGUARDED, guarded = false)).detail,
        )
        assertEquals(
            "#Translation · run by 1 members · this group can spend from its wallet · this group after 12 blocks, about 2 hours",
            ProposedEvent.summarize(certificate(null, guarded = true)).detail,
            "an older certificate decides nothing, and says nothing about it",
        )
    }

    /** docs/member-admin-promotion.md, Phase 3: a member approves names, not a hash. */
    @Test
    fun `a change to the group says who, in names`() {
        val bob = "b".repeat(64)
        val carol = "c".repeat(64)
        val tags = GovernanceStatementEvent.assembleTags(
            chatRoomId = "1".repeat(64),
            epoch = 4,
            change = GovernanceChange(removes = setOf(carol), admins = setOf(bob)),
            proposer = bob,
        )
        val names = mapOf(bob to "Bob", carol to "Carol")

        val summary = ProposedEvent.summarize(event(GovernanceStatementEvent.KIND, tags = tags), nameOf = names::get)

        assertEquals("A change to who is in this group", summary.label)
        assertEquals("Removes Carol · Admins afterwards: Bob", summary.detail)
    }

    @Test
    fun `a profile is said by its name and what it says about itself`() {
        val summary = ProposedEvent.summarize(
            event(MetadataEvent.KIND, content = """{"name":"Translation collective","about":"Hard books"}""")
        )

        assertEquals("Nostr profile", summary.label)
        assertEquals("Translation collective · Hard books", summary.detail)

        assertEquals("An empty profile", ProposedEvent.summarize(event(MetadataEvent.KIND, content = "{}")).detail)
        assertEquals("Unreadable profile", ProposedEvent.summarize(event(MetadataEvent.KIND, content = "not json")).detail)
    }

    @Test
    fun `a post is said by its words`() {
        val summary = ProposedEvent.summarize(event(TextNoteEvent.KIND, content = "We meet on Thursday"))

        assertEquals("Post", summary.label)
        assertEquals("We meet on Thursday", summary.detail)
    }

    @Test
    fun `a relay list is still said by its number`() {
        // The group's relay lists were not brought back with its profile and posts
        // (docs/group-nostr-profile-and-posts.md, D1), so a proposal for one from a
        // Curare member is said like any other kind the app does not know.
        assertEquals(
            "Event of kind ${AdvertisedRelayListEvent.KIND}",
            ProposedEvent.summarize(event(AdvertisedRelayListEvent.KIND)).label,
        )
    }

    private fun event(
        kind: Int,
        tags: Array<Array<String>> = emptyArray(),
        content: String = "",
    ) = Event(
        id = "a".repeat(64),
        pubKey = "b".repeat(64),
        createdAt = 1_700_000_000,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128),
    )
}
