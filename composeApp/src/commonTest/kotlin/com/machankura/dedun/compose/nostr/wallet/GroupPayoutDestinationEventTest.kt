package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A member's payout destination, read and believed -- one mutation per check, each of which
 * has to be enough on its own to refuse it. `docs/group-payout-destination.md`, Phase 1.
 */
class GroupPayoutDestinationEventTest {
    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val alice = privateKey(0xa11ce)
    private val alicePublicKey = alice.xOnlyPublicKey().value.toHex()
    private val bob = privateKey(0xb0b)
    private val room = SharedKeyDerivation.marmotGroupId(privateKey(0x6a0b).publicKey().toString())
    private val otherRoom = SharedKeyDerivation.marmotGroupId(privateKey(0x9a9e).publicKey().toString())
    private val lightning = LightningAddress("alice", "example.com")
    private val onchain = Bitcoin.computeBIP86Address(privateKey(0xc01).publicKey(), chain.chainHash)
    private val sendable = Sendable(MilliSatoshi(1_000), MilliSatoshi(100_000_000))

    private fun destination(createdAt: Long = 1_700_000_000, onchain: String? = this.onchain, group: String = room, signer: PrivateKey = alice) =
        GroupPayoutDestinationEvent.build(signer, group, chain, lightning, sendable, onchain, createdAt)

    /** [event] with [tags], re-signed by Alice, so only the tags are wrong. */
    private fun resigned(event: Event, tags: Array<Array<String>>): Event {
        val id = EventHasher.hashId(event.pubKey, event.createdAt, event.kind, tags, event.content)
        val sig = Crypto.signSchnorr(ByteVector32(id), alice, null).toHex()
        return Event(id, event.pubKey, event.createdAt, event.kind, tags, event.content, sig)
    }

    private fun Event.replacing(name: String, vararg value: String): Array<Array<String>> =
        tags.map { if (it[0] == name) arrayOf(name, *value) else it }.toTypedArray()

    private fun Event.adding(vararg tag: String): Array<Array<String>> = tags + arrayOf(arrayOf(*tag))

    private fun Event.without(name: String): Array<Array<String>> = tags.filter { it[0] != name }.toTypedArray()

    /** An inner event of the kind, carrying [statement] whole, as put in the room by [carrier]. */
    private fun inner(statement: Event, carrier: String = statement.pubKey) =
        Event("00".repeat(32), carrier, statement.createdAt, GroupPayoutDestinationEvent.KIND, arrayOf(arrayOf("h", room)), statement.toJson(), "")

    @Test
    fun `a destination reads and is believed`() {
        val read = assertIs<GroupPayoutDestination>(GroupPayoutDestinationEvent.verified(destination()))
        assertEquals(alicePublicKey, read.member)
        assertEquals(room, read.chatRoomId)
        assertEquals(chain, read.chain)
        assertEquals(lightning, read.lightningAddress)
        assertEquals(sendable, read.sendable)
        assertEquals(onchain, read.onchainAddress)
    }

    @Test
    fun `a destination with no onchain address or range reads`() {
        val event = GroupPayoutDestinationEvent.build(alice, room, chain, lightning)
        val read = assertIs<GroupPayoutDestination>(GroupPayoutDestinationEvent.verified(event))
        assertNull(read.onchainAddress)
        assertNull(read.sendable)
    }

    @Test
    fun `a removal reads as one`() {
        val event = GroupPayoutDestinationEvent.buildRemoved(alice, room, chain)
        assertIs<PayoutDestinationRemoved>(GroupPayoutDestinationEvent.verified(event))
    }

    @Test
    fun `a removal carrying an address is refused`() {
        val removed = GroupPayoutDestinationEvent.buildRemoved(alice, room, chain)
        assertNull(GroupPayoutDestinationEvent.verified(resigned(removed, removed.adding("lightning", lightning.toString()))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(removed, removed.adding("onchain", onchain))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(removed, removed.adding("sendable", "1000", "2000"))))
    }

    @Test
    fun `a d tag that disagrees with the room or the chain is refused`() {
        val event = destination()
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("d", "$otherRoom:regtest"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("d", "$room:mainnet"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.without("d"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.adding("d", "$room:regtest"))))
    }

    @Test
    fun `no Lightning address, or two, is refused`() {
        val event = destination()
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.without("lightning"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.adding("lightning", "bob@example.com"))))
    }

    @Test
    fun `a Lightning address not written as it would be now is refused`() {
        val event = destination()
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("lightning", "Alice@Example.com"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("lightning", "lnurl1dp68gurn8ghj7"))))
    }

    @Test
    fun `another network's onchain address is refused`() {
        val event = destination()
        val mainnet = Bitcoin.computeBIP86Address(privateKey(0xc01).publicKey(), Chain.Mainnet.chainHash)
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("onchain", mainnet))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("onchain", onchain.uppercase()))))
    }

    @Test
    fun `a range that is not one is refused`() {
        val event = destination()
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("sendable", "2000", "1000"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("sendable", "0", "1000"))))
        assertNull(GroupPayoutDestinationEvent.verified(resigned(event, event.replacing("sendable", "1000"))))
    }

    @Test
    fun `a signature by anybody but the member is refused`() {
        val event = destination()
        val bobs = Crypto.signSchnorr(ByteVector32(event.id), bob, null).toHex()
        assertNull(GroupPayoutDestinationEvent.verified(Event(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, bobs)))
        // A changed tag under the old signature: the id no longer matches.
        assertNull(GroupPayoutDestinationEvent.verified(Event(event.id, event.pubKey, event.createdAt, event.kind, event.replacing("lightning", "bob@example.com"), event.content, event.sig)))
    }

    @Test
    fun `a statement is carried only by its author`() {
        val statement = destination()
        assertEquals(statement.id, GroupPayoutDestinationEvent.carried(inner(statement))?.id)
        assertNull(GroupPayoutDestinationEvent.carried(inner(statement, carrier = bob.xOnlyPublicKey().value.toHex())))
        assertNull(GroupPayoutDestinationEvent.carried(inner(statement).let { Event(it.id, it.pubKey, it.createdAt, it.kind, it.tags, "not json", "") }))
    }

    @Test
    fun `the newest statement of each member counts, a removal included`() {
        val older = destination(createdAt = 1_700_000_000)
        val newer = GroupPayoutDestinationEvent.build(alice, room, chain, LightningAddress("alice", "other.example"), createdAt = 1_700_000_100)
        val removed = GroupPayoutDestinationEvent.buildRemoved(alice, room, chain, createdAt = 1_700_000_200)
        val bobs = destination(createdAt = 1_700_000_050, signer = bob)
        val now = 1_700_001_000L

        val withoutRemoval = GroupPayoutDestinationEvent.newestPerMember(listOf(newer, bobs, older), room, chain, now)
        assertEquals(LightningAddress("alice", "other.example"), assertIs<GroupPayoutDestination>(withoutRemoval[alicePublicKey]).lightningAddress)
        assertTrue(withoutRemoval.containsKey(bob.xOnlyPublicKey().value.toHex()))

        val withRemoval = GroupPayoutDestinationEvent.newestPerMember(listOf(older, removed, newer), room, chain, now)
        assertIs<PayoutDestinationRemoved>(withRemoval[alicePublicKey])
    }

    @Test
    fun `two statements in the same second are ordered by id on every device`() {
        val one = destination(createdAt = 1_700_000_000)
        val two = GroupPayoutDestinationEvent.build(alice, room, chain, LightningAddress("alice", "other.example"), createdAt = 1_700_000_000)
        val expected = listOf(one, two).maxBy { it.id }
        val now = 1_700_001_000L
        assertEquals(expected.id, GroupPayoutDestinationEvent.newestPerMember(listOf(one, two), room, chain, now)[alicePublicKey]?.event?.id)
        assertEquals(expected.id, GroupPayoutDestinationEvent.newestPerMember(listOf(two, one), room, chain, now)[alicePublicKey]?.event?.id)
    }

    @Test
    fun `a statement dated an hour ahead does not count`() {
        val now = 1_700_000_000L
        val current = destination(createdAt = now - 10)
        val ahead = GroupPayoutDestinationEvent.buildRemoved(alice, room, chain, createdAt = now + 3_600)
        assertIs<GroupPayoutDestination>(GroupPayoutDestinationEvent.newestPerMember(listOf(current, ahead), room, chain, now)[alicePublicKey])
        val slightlyAhead = GroupPayoutDestinationEvent.buildRemoved(alice, room, chain, createdAt = now + 60)
        assertIs<PayoutDestinationRemoved>(GroupPayoutDestinationEvent.newestPerMember(listOf(current, slightlyAhead), room, chain, now)[alicePublicKey])
    }

    @Test
    fun `another room's or another chain's statements are not read`() {
        val now = 1_700_001_000L
        val elsewhere = destination(group = otherRoom)
        assertTrue(GroupPayoutDestinationEvent.newestPerMember(listOf(elsewhere), room, chain, now).isEmpty())
        assertTrue(GroupPayoutDestinationEvent.newestPerMember(listOf(destination()), room, Chain.Signet, now).isEmpty())
    }
}
