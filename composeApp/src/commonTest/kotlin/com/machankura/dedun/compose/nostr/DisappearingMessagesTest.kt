package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.machankura.dedun.compose.nostr.DisappearingMessages.DAY
import com.machankura.dedun.compose.nostr.DisappearingMessages.ExpiryClass
import com.machankura.dedun.compose.nostr.DisappearingMessages.HOUR
import com.machankura.dedun.compose.nostr.DisappearingMessages.OFF
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.ShareRefreshEvents
import com.machankura.dedun.compose.nostr.frost.ShareRepairEvents
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhEvents
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** docs/disappearing-messages.md, D2 to D5: the rules, without a database. */
class DisappearingMessagesTest {

    private val createdAt = 1_800_000_000L
    private val chat = ChatMessageEvent.KIND

    /**
     * Every kind Dedun is known to put in a NIP-17 wrap, or in a ceremony that could. A kind missing
     * here falls through to the unclassified floor, which still expires but was nobody's decision;
     * classifying it means adding it to `DisappearingMessages` with a reason, and then here.
     *
     * Dedun's inventory (docs/giza-second-batch.md, Phase 7, step 1). The six places that write a
     * `GiftWrapPayload` send: what a person writes (`DatabaseChatRepository`); a Welcome
     * (`MarmotOutboundDao`); a key ceremony's rounds, a subgroup's included (`ChillDkgRitualManager`);
     * and signing in a NIP-17 room, a wallet's spend and sweep proposals included
     * (`FrostSigningManager.broadcast`). The inbox key, share refresh and repair and threshold ECDH
     * run in Marmot rooms today and are classified anyway, as Giza classifies them. Nothing of the
     * Lightning or Wavelength wallets, guardians, contacts or the moved kinds goes over NIP-17.
     */
    private val knownProtocolKinds =
        DkgRitualEvents.ALL + FrostSigningEvents.ALL +
            InboxKeyRitualEvents.ALL + ShareRefreshEvents.ALL + ShareRepairEvents.ALL + GroupEcdhEvents.ALL +
            setOf(WelcomeEvent.KIND)

    @Test
    fun `what a person writes is Off by default in Dedun`() {
        // D11: members settle disagreements about money by pointing at what was said.
        assertEquals(OFF, DisappearingMessages.DEFAULT_SECONDS)
        assertEquals(OFF, DisappearingMessages.PRESETS.first())
        assertNull(DisappearingMessages.expiryFor(chat, createdAt, DisappearingMessages.DEFAULT_SECONDS, emptyArray()))
        // A ceremony round written then still carries its floor.
        assertEquals(
            createdAt + DisappearingMessages.CEREMONY_FLOOR,
            DisappearingMessages.expiryFor(DkgRitualEvents.ROUND_1, createdAt, DisappearingMessages.DEFAULT_SECONDS, emptyArray()),
        )
    }

    @Test
    fun `every kind the app sends is classified on purpose`() {
        val unclassified = knownProtocolKinds.filter { (DisappearingMessages.classOf(it) as? ExpiryClass.Protocol)?.classified == false }
        assertEquals(emptyList(), unclassified)
        DisappearingMessages.WRITTEN_KINDS.forEach { assertSame(ExpiryClass.Written, DisappearingMessages.classOf(it)) }
    }

    @Test
    fun `a kind nobody classified still expires, after a week`() {
        val expiryClass = DisappearingMessages.classOf(99_999)
        assertEquals(ExpiryClass.Protocol(DisappearingMessages.UNCLASSIFIED_FLOOR, classified = false), expiryClass)
        assertEquals(createdAt + 7 * DAY, DisappearingMessages.expiryFor(99_999, createdAt, OFF, emptyArray()))
    }

    @Test
    fun `what a person writes follows the policy, and Off means no expiry`() {
        assertEquals(createdAt + DAY, DisappearingMessages.expiryFor(chat, createdAt, DAY, emptyArray()))
        assertEquals(createdAt + HOUR, DisappearingMessages.expiryFor(chat, createdAt, HOUR, emptyArray()))
        assertNull(DisappearingMessages.expiryFor(chat, createdAt, OFF, emptyArray()))
    }

    @Test
    fun `a protocol message expires even with the policy Off, at its floor`() {
        val nonce = FrostSigningEvents.NONCE
        assertEquals(createdAt + DisappearingMessages.SIGNING_FLOOR, DisappearingMessages.expiryFor(nonce, createdAt, OFF, emptyArray()))
        assertEquals(createdAt + DisappearingMessages.SIGNING_FLOOR, DisappearingMessages.expiryFor(nonce, createdAt, HOUR, emptyArray()))
        assertEquals(createdAt + 7 * DAY, DisappearingMessages.expiryFor(DkgRitualEvents.ROUND_1, createdAt, DAY, emptyArray()))
        assertEquals(createdAt + 7 * DAY, DisappearingMessages.expiryFor(WelcomeEvent.KIND, createdAt, OFF, emptyArray()))
    }

    @Test
    fun `a policy longer than the floor wins`() {
        assertEquals(createdAt + 28 * DAY, DisappearingMessages.expiryFor(DkgRitualEvents.ROUND_1, createdAt, 28 * DAY, emptyArray()))
    }

    @Test
    fun `a rumor that carries an expiry keeps it and gets no second one`() {
        val proposalLapses = createdAt + 3 * DAY
        val tags = arrayOf(arrayOf("p", "a".repeat(64)), arrayOf("expiration", proposalLapses.toString()))
        assertEquals(proposalLapses, DisappearingMessages.expiryFor(FrostSigningEvents.PROPOSAL, createdAt, DAY, tags))
        val stamped = DisappearingMessages.stamp(FrostSigningEvents.PROPOSAL, createdAt, DAY, tags)
        assertSame(tags, stamped)
        assertEquals(1, stamped.count { it[0] == "expiration" })
    }

    @Test
    fun `stamping appends one tag, after the others`() {
        val tags = arrayOf(arrayOf("p", "b".repeat(64)))
        val stamped = DisappearingMessages.stamp(chat, createdAt, DAY, tags)
        assertEquals(2, stamped.size)
        assertEquals(listOf("p", "b".repeat(64)), stamped[0].toList())
        assertEquals(listOf("expiration", (createdAt + DAY).toString()), stamped[1].toList())
        assertSame(tags, DisappearingMessages.stamp(chat, createdAt, OFF, tags))
    }

    @Test
    fun `the wrap expires later than the rumor by at most two days, never earlier`() {
        val rumorExpiry = createdAt + DAY
        // A wrap dated anywhere in the two days before the rumor, as quartz's randomization does.
        listOf(0L, 1L, HOUR, DAY, 2 * DAY - 1, 2 * DAY).forEach { pushedBack ->
            val wrapExpiry = DisappearingMessages.wrapExpiration(rumorExpiry, createdAt, createdAt - pushedBack)!!
            assertTrue(wrapExpiry >= rumorExpiry, "pushed back $pushedBack: $wrapExpiry < $rumorExpiry")
            assertTrue(wrapExpiry <= rumorExpiry + 2 * DAY, "pushed back $pushedBack: $wrapExpiry too late")
        }
    }

    @Test
    fun `a rumor with no expiry gives a wrap with none`() {
        assertNull(DisappearingMessages.wrapExpiration(null, createdAt, createdAt - HOUR))
        assertNull(DisappearingMessages.wrapExpiration(createdAt, createdAt, createdAt - HOUR))
    }

    @Test
    fun `a malformed expiry reads as none`() {
        assertNull(DisappearingMessages.expirationOf(arrayOf(arrayOf("expiration", "soon"))))
        assertNull(DisappearingMessages.expirationOf(arrayOf(arrayOf("expiration"))))
        assertNull(DisappearingMessages.expirationOf(arrayOf(arrayOf("expiration", createdAt.toString())), createdAt))
        assertNull(DisappearingMessages.expirationOf(arrayOf(arrayOf("expiration", (createdAt - 1).toString())), createdAt))
        assertEquals(createdAt + 1, DisappearingMessages.expirationOf(arrayOf(arrayOf("expiration", (createdAt + 1).toString())), createdAt))
    }

    @Test
    fun `the policy bounds`() {
        assertTrue(DisappearingMessages.isValidPolicy(OFF))
        assertTrue(DisappearingMessages.isValidPolicy(DisappearingMessages.MIN_SECONDS))
        assertTrue(DisappearingMessages.isValidPolicy(DisappearingMessages.MAX_SECONDS))
        assertFalse(DisappearingMessages.isValidPolicy(DisappearingMessages.MIN_SECONDS - 1))
        assertFalse(DisappearingMessages.isValidPolicy(DisappearingMessages.MAX_SECONDS + 1))
        assertFalse(DisappearingMessages.isValidPolicy(-1))
        DisappearingMessages.PRESETS.forEach { assertTrue(DisappearingMessages.isValidPolicy(it)) }
        assertEquals(OFF, DisappearingMessages.DEFAULT_SECONDS)
    }

    @Test
    fun `the override wins over the default, and null follows it`() {
        assertEquals(DAY, DisappearingMessages.policyFor(null, DAY))
        assertEquals(OFF, DisappearingMessages.policyFor(OFF, DAY))
        assertEquals(HOUR, DisappearingMessages.policyFor(HOUR, DAY))
    }

    @Test
    fun `a signer accepts no expiry or a choosable one, and nothing else`() {
        assertTrue(DisappearingMessages.isAcceptableExpiry(emptyArray(), createdAt))
        assertTrue(DisappearingMessages.isAcceptableExpiry(arrayOf(arrayOf("expiration", (createdAt + DAY).toString())), createdAt))
        assertFalse(DisappearingMessages.isAcceptableExpiry(arrayOf(arrayOf("expiration", (createdAt + 1).toString())), createdAt))
        assertFalse(DisappearingMessages.isAcceptableExpiry(arrayOf(arrayOf("expiration", (createdAt + 400 * DAY).toString())), createdAt))
        assertFalse(DisappearingMessages.isAcceptableExpiry(arrayOf(arrayOf("expiration", "soon")), createdAt))
    }

    @Test
    fun `an expiry is read off a rumor kept as JSON`() {
        val json = """{"id":"","pubkey":"${"a".repeat(64)}","created_at":$createdAt,"kind":14,"tags":[["expiration","${createdAt + HOUR}"]],"content":"hi"}"""
        assertEquals(createdAt + HOUR, DisappearingMessages.expirationOfRumorJson(json))
        assertNull(DisappearingMessages.expirationOfRumorJson("not json"))
    }

    @Test
    fun `expired from its expiry on`() {
        assertFalse(DisappearingMessages.isExpired(createdAt, createdAt - 1))
        assertTrue(DisappearingMessages.isExpired(createdAt, createdAt))
    }
}
