package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.DepositBranch
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.Attribution
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The depositor statement (30339) and the deposit attribution (30340), read and believed --
 * one mutation per check. `docs/member-deposits.md`, Phase 1.
 */
class DepositStatementsJvmTest {
    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private val other = keyMaterial("9a9e")
    private val otherRoom = roomOf(other)

    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val alice = key("a11ce")
    private val alicePublicKey = alice.xOnlyPublicKey().value.toHex()
    private val bob = key("b0b")

    private fun destination(member: PrivateKey = alice, inRoom: String = room, on: Chain = chain) =
        GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(member, inRoom, on, LightningAddress("me", "example.com"), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination

    private fun statement(tags: Array<Array<String>>, createdAt: Long = 1_700_000_100, by: KeyMaterial = group): Event =
        signedAsRoom(by, DepositorStatementEvent.KIND, tags, "", createdAt)

    private fun tagsFor(destination: GroupPayoutDestination = destination(), salt: Int = 0) =
        DepositorStatementEvent.template(room, destination, salt, 1_700_000_100).tags

    private fun Array<Array<String>>.replacing(name: String, vararg value: String) =
        map { if (it[0] == name) arrayOf(name, *value) else it }.toTypedArray()

    @Test
    fun `a member's branch is theirs alone, never receive or change, and the same on every device`() {
        val a = DepositBranch.of(room, alicePublicKey)
        assertEquals(a, DepositBranch.of(room.uppercase(), alicePublicKey.uppercase()))
        assertTrue(DepositBranch.isMemberBranch(a))
        assertNotEquals(a, DepositBranch.of(room, bob.xOnlyPublicKey().value.toHex()))
        assertNotEquals(a, DepositBranch.of(otherRoom, alicePublicKey))
        assertNotEquals(a, DepositBranch.of(room, alicePublicKey, salt = 1))
        // Pinned against a value computed outside the app: a change here moves every member's addresses.
        assertEquals(497203201L, DepositBranch.of("00".repeat(32), "11".repeat(32), salt = 0))
        (0 until 1_000).forEach { salt -> assertTrue(DepositBranch.isMemberBranch(DepositBranch.of(room, alicePublicKey, salt))) }
    }

    @Test
    fun `a statement reads and is believed`() {
        val depositor = assertNotNull(DepositorStatementEvent.verify(statement(tagsFor()), room))
        assertEquals(alicePublicKey, depositor.member)
        assertEquals(DepositBranch.of(room, alicePublicKey), depositor.branch)
        assertEquals(LightningAddress("me", "example.com"), depositor.destination.lightningAddress)
    }

    @Test
    fun `a statement another room signed is not this room's`() {
        assertNull(DepositorStatementEvent.verify(statement(tagsFor(), by = other), room))
    }

    @Test
    fun `a branch that does not recompute, or is receive or change, is refused`() {
        val tags = tagsFor()
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("branch", "0", "0")), room))
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("branch", "1", "0")), room))
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("branch", DepositBranch.of(room, alicePublicKey, 1).toString(), "0")), room))
        assertNotNull(DepositorStatementEvent.verify(statement(tagsFor(salt = 1)), room))
    }

    @Test
    fun `an embedded destination that is not the member's own, or not this room's, is refused`() {
        val tags = tagsFor()
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("destination", destination(member = bob).event.toJson())), room))
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("destination", destination(inRoom = otherRoom).event.toJson())), room))
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("destination", destination(on = Chain.Signet).event.toJson())), room))
        val removed = GroupPayoutDestinationEvent.buildRemoved(alice, room, chain)
        assertNull(DepositorStatementEvent.verify(statement(tags.replacing("destination", removed.toJson())), room))
    }

    @Test
    fun `a d tag that disagrees with the member is refused`() {
        val tags = tagsFor().replacing("d", "regtest:" + "ab".repeat(32))
        assertNull(DepositorStatementEvent.verify(statement(tags), room))
    }

    @Test
    fun `the first statement for a member is the one that counts`() {
        val first = statement(tagsFor(), createdAt = 1_700_000_100)
        val second = statement(tagsFor(salt = 1), createdAt = 1_700_000_200)
        val counted = DepositorStatementEvent.firstPerMember(listOf(second, first), room, chain)
        assertEquals(first.id, counted[alicePublicKey]?.event?.id)
        assertTrue(DepositorStatementEvent.firstPerMember(listOf(first), room, Chain.Signet).isEmpty())
    }

    private val txid = "ab".repeat(32)
    private val hash = "cd".repeat(32)

    private fun attribution(tags: Array<Array<String>>, createdAt: Long = 1_700_000_100) =
        signedAsRoom(group, DepositAttributionEvent.KIND, tags, "", createdAt)

    @Test
    fun `an attribution of onchain and Lightning deposits reads and is believed`() {
        val template = DepositAttributionEvent.template(chain, listOf(
            Attribution(DepositKey.Onchain(txid, 1), alicePublicKey, 50_000),
            Attribution(DepositKey.Lightning(hash), null, 21_000_000),
        ), 1_700_000_100)
        val read = assertNotNull(DepositAttributionEvent.verify(attribution(template.tags), room))
        assertEquals(2, read.attributions.size)
        assertEquals(alicePublicKey, read.attributions[0].member)
        assertNull(read.attributions[1].member)
        assertNull(DepositAttributionEvent.verify(signedAsRoom(other, DepositAttributionEvent.KIND, template.tags, "", 1_700_000_100), room))
    }

    @Test
    fun `a deposit that does not read, twice, or with no amount is refused`() {
        listOf(
            arrayOf(arrayOf("chain", "regtest"), arrayOf("deposit", "nope", alicePublicKey, "1")),
            arrayOf(arrayOf("chain", "regtest"), arrayOf("deposit", "$txid:01", alicePublicKey, "1")),
            arrayOf(arrayOf("chain", "regtest"), arrayOf("deposit", "ln:xyz", alicePublicKey, "1")),
            arrayOf(arrayOf("chain", "regtest"), arrayOf("deposit", "$txid:0", "someone", "1")),
            arrayOf(arrayOf("chain", "regtest"), arrayOf("deposit", "$txid:0", alicePublicKey, "0")),
            arrayOf(arrayOf("chain", "regtest"), arrayOf("deposit", "$txid:0", alicePublicKey, "1"), arrayOf("deposit", "$txid:0", "group", "1")),
            arrayOf(arrayOf("chain", "regtest")),
            arrayOf(arrayOf("deposit", "$txid:0", alicePublicKey, "1")),
        ).forEach { tags -> assertNull(DepositAttributionEvent.verify(attribution(tags), room), tags.joinToString { it.toList().toString() }) }
    }

    @Test
    fun `a later attribution of a deposit corrects an earlier one`() {
        val key = DepositKey.Onchain(txid, 0)
        val earlier = attribution(DepositAttributionEvent.template(chain, listOf(Attribution(key, alicePublicKey, 1_000))).tags, createdAt = 1_700_000_100)
        val later = attribution(DepositAttributionEvent.template(chain, listOf(Attribution(key, null, 1_000))).tags, createdAt = 1_700_000_200)
        val newest = DepositAttributionEvent.newestPerDeposit(listOf(later, earlier), room, chain)
        assertNull(newest[key]?.first?.member)
        assertEquals(later.id, newest[key]?.second?.event?.id)
    }
}
