package com.machankura.dedun.compose.nostr.wallet

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A group's policy on its members' wallets -- `docs/member-wallet-delay.md`,
 * Phase 1. The room is a key whose secret the test holds: `isSignedByRoom` asks
 * only that the room's id signed it.
 */
class MemberWalletPolicyEventTest {
    private val roomKey = PrivateKey(ByteVector32(Hex.decode("07".repeat(32))))
    private val roomId = roomKey.publicKey().xOnly().value.toString()
    private val strangerKey = PrivateKey(ByteVector32(Hex.decode("09".repeat(32))))

    private fun signed(
        tags: Array<Array<String>>,
        createdAt: Long = 1_700_000_000,
        by: PrivateKey = roomKey,
        kind: Int = MemberWalletPolicyEvent.KIND,
    ): Event {
        val pubKey = by.publicKey().xOnly().value.toString()
        val id = EventHasher.hashId(pubKey, createdAt, kind, tags, "")
        return Event(id, pubKey, createdAt, kind, tags, "", Crypto.signSchnorr(ByteVector32(id), by, null).toString())
    }

    private fun open(delay: Long, createdAt: Long = 1_700_000_000) = signed(MemberWalletPolicyEvent.open(delay, createdAt).tags, createdAt)
    private fun closed(createdAt: Long = 1_700_000_000) = signed(MemberWalletPolicyEvent.closed(createdAt).tags, createdAt)

    @Test
    fun `an open policy and a closed one each read back`() {
        val opened = assertIs<MemberWalletPolicyEvent.Policy.Open>(MemberWalletPolicyEvent.verify(open(144), roomId))
        assertEquals(144L, opened.delay)
        assertIs<MemberWalletPolicyEvent.Policy.Closed>(MemberWalletPolicyEvent.verify(closed(), roomId))
    }

    @Test
    fun `a policy that does not say one thing a device can act on does not read`() {
        val d = arrayOf("d", MemberWalletPolicyEvent.IDENTIFIER)
        val cases = mapOf(
            "another kind" to signed(arrayOf(d, arrayOf("guardian_delay", "144")), kind = 30336),
            "no d tag" to signed(arrayOf(arrayOf("guardian_delay", "144"))),
            "another d tag" to signed(arrayOf(arrayOf("d", "subgroups"), arrayOf("guardian_delay", "144"))),
            "two delays" to signed(arrayOf(d, arrayOf("guardian_delay", "144"), arrayOf("guardian_delay", "12"))),
            "five blocks" to signed(arrayOf(d, arrayOf("guardian_delay", "5"))),
            "65,536 blocks" to signed(arrayOf(d, arrayOf("guardian_delay", "65536"))),
            "a word" to signed(arrayOf(d, arrayOf("guardian_delay", "twelve"))),
            "both a delay and closed" to signed(arrayOf(d, arrayOf("guardian_delay", "144"), arrayOf("closed"))),
            "neither" to signed(arrayOf(d)),
            "another room's signature" to signed(MemberWalletPolicyEvent.open(144).tags, by = strangerKey),
        )
        cases.forEach { (name, event) -> assertNull(MemberWalletPolicyEvent.verify(event, roomId), "a policy with $name read") }
    }

    @Test
    fun `the newest decides, open or closed, in any order`() {
        val first = open(12, createdAt = 1_700_000_000)
        val shut = closed(createdAt = 1_700_000_100)
        val reopened = open(1_008, createdAt = 1_700_000_200)

        listOf(listOf(first, shut, reopened), listOf(reopened, first, shut), listOf(shut, reopened, first)).forEach { events ->
            assertEquals(1_008L, assertIs<MemberWalletPolicyEvent.Policy.Open>(MemberWalletPolicyEvent.newestAmong(events, roomId)).delay)
        }
        assertIs<MemberWalletPolicyEvent.Policy.Closed>(MemberWalletPolicyEvent.newestAmong(listOf(shut, first), roomId))
        assertNull(MemberWalletPolicyEvent.newestAmong(emptyList(), roomId))
    }

    @Test
    fun `a tie on the timestamp goes to the larger id, on every device`() {
        val a = open(144, createdAt = 1_700_000_000)
        val b = open(1_008, createdAt = 1_700_000_000)
        val larger = listOf(a, b).maxBy { it.id }

        assertEquals(larger.id, MemberWalletPolicyEvent.newestAmong(listOf(a, b), roomId)?.event?.id)
        assertEquals(larger.id, MemberWalletPolicyEvent.newestAmong(listOf(b, a), roomId)?.event?.id)
    }

    @Test
    fun `an open policy outside the range cannot be built`() {
        assertFailsWith<IllegalArgumentException> { MemberWalletPolicyEvent.open(5) }
        assertFailsWith<IllegalArgumentException> { MemberWalletPolicyEvent.open(65_536) }
    }
}
