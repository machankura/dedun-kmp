package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.machankura.dedun.compose.nostr.MarmotDirectMessage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one hole deliberately left in the author check, and its edges.
 *
 * An inner application event's pubkey must equal the MLS sender's credential identity,
 * which is what stops a member minting events attributed to somebody else. In a legacy
 * room a gift wrap cannot satisfy it -- its pubkey is a throwaway key that names nobody
 * -- so kind:1059 is exempt there, and the authorship claim moves to the signed seal
 * inside. In a current-profile room there is no exemption: a direct message travels
 * inside an envelope the sender authors, and a bare wrap is refused like any other
 * payload claiming an author it does not own.
 *
 * The exemption is the most dangerous line in this feature. Widened to another kind,
 * stripped of its kind guard, or extended to current-profile rooms, it hands every
 * member of every group the ability to publish events as anybody, and nothing else in
 * the pipeline would notice. These tests exist so that widening it fails here rather
 * than in a group.
 */
class MarmotMip03CarveOutTest {
    private val sender = "a".repeat(64)
    private val somebodyElse = "b".repeat(64)
    private val throwaway = "c".repeat(64)

    private fun rejection(kind: Int, pubKey: String, identity: String?, legacyRoom: Boolean = true) =
        MarmotInboundManager.mip03Rejection(
            innerEventKind = kind,
            innerEventPubKey = pubKey,
            senderIdentity = identity,
            legacyRoom = legacyRoom,
        )

    @Test
    fun `an ordinary message from its own author is accepted in either room`() {
        assertNull(rejection(ChatEvent.KIND, sender, sender, legacyRoom = true))
        assertNull(rejection(ChatEvent.KIND, sender, sender, legacyRoom = false))
    }

    @Test
    fun `an ordinary message claiming somebody else is rejected in either room`() {
        listOf(true, false).forEach { legacy ->
            val reason = rejection(ChatEvent.KIND, somebodyElse, sender, legacyRoom = legacy)

            assertNotNull(reason)
            assertTrue(reason.startsWith("MIP-03"), "expected the MIP-03 rejection, got: $reason")
        }
    }

    @Test
    fun `in a legacy room a gift wrap is accepted despite naming nobody`() {
        // The carve-out. The wrap's pubkey is a throwaway key and matches no member, which
        // is the point of it; what authenticates the sender is the MLS frame, and what
        // authenticates the author is the seal inside. See MarmotDirectMessage.classify.
        assertNull(rejection(GiftWrapEvent.KIND, throwaway, sender, legacyRoom = true))
    }

    @Test
    fun `in a current-profile room a bare gift wrap is an author that is not the sender`() {
        // No carve-out. A direct message in a current-profile room is an envelope by the
        // sender; a bare wrap there is what every other conformant client refuses it as.
        assertNotNull(rejection(GiftWrapEvent.KIND, throwaway, sender, legacyRoom = false))
    }

    @Test
    fun `an envelope passes the ordinary check because its author is the sender`() {
        assertNull(rejection(MarmotDirectMessage.ENVELOPE_KIND, sender, sender, legacyRoom = false))
        assertNotNull(rejection(MarmotDirectMessage.ENVELOPE_KIND, somebodyElse, sender, legacyRoom = false))
    }

    @Test
    fun `the carve-out does not extend to any other kind`() {
        // Every kind carried as an application payload today, plus the seal and rumor
        // kinds a gift wrap contains -- none of which should ever arrive unwrapped, and
        // all of which would be a way to launder an author if the guard were widened.
        val kinds = listOf(
            ChatEvent.KIND,
            ChatMessageEvent.KIND,
            SealedRumorEvent.KIND,
            MarmotDirectMessage.ENVELOPE_KIND,
            GiftWrapEvent.KIND - 1,
            GiftWrapEvent.KIND + 1,
            0,
            1,
        )

        kinds.forEach { kind ->
            assertNotNull(
                rejection(kind, somebodyElse, sender, legacyRoom = true),
                "kind $kind was allowed to claim an author it does not own",
            )
        }
    }

    @Test
    fun `a sender with no credential identity is rejected wrap or not`() {
        // Required rather than merely compared: with no identity there is nothing to
        // attribute a line to, and for a gift wrap there is nothing to check the seal
        // against either. Both kinds must fail, not just the one that does a comparison.
        assertNotNull(rejection(ChatEvent.KIND, sender, null))
        assertNotNull(rejection(GiftWrapEvent.KIND, throwaway, null))
    }
}
