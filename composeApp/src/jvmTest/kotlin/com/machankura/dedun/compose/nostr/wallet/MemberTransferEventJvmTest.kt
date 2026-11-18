package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent.CoinShare
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The giver's request (30342) and the group's transfer (30343), read and believed -- one mutation per check, each enough on
 * its own to refuse it. `docs/member-transfers.md`, Phase 1.
 */
class MemberTransferEventJvmTest {
    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private val other = keyMaterial("9a9e")
    private val otherRoom = roomOf(other)

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val alice = privateKey(0xa11ce)
    private val aliceKey = alice.xOnlyPublicKey().value.toHex()
    private val carol = privateKey(0xca401)
    private val bobKey = privateKey(0xb0b).xOnlyPublicKey().value.toHex()

    private val coin = DepositKey.Onchain("c0".repeat(32), 1)
    private val second = DepositKey.Onchain("c1".repeat(32), 0)

    private fun request(
        rail: Rail = Rail.WAVELENGTH,
        amount: Long = 20_000,
        group: String = room,
        signer: PrivateKey = alice,
        note: String? = "groceries",
        createdAt: Long = 1_700_000_000,
    ) = TransferRequestEvent.build(signer, group, chain, rail, bobKey, amount, createdAt + 86_400, note, createdAt)

    private fun requestOf(event: Event) = assertIs<TransferRequestEvent.Request>(TransferRequestEvent.verified(event))

    /** [event] with [tags], re-signed by Alice, so only the tags are wrong. */
    private fun resigned(event: Event, tags: Array<Array<String>>, by: PrivateKey = alice): Event {
        val pubKey = by.xOnlyPublicKey().value.toHex()
        val id = EventHasher.hashId(pubKey, event.createdAt, event.kind, tags, event.content)
        return Event(id, pubKey, event.createdAt, event.kind, tags, event.content, Crypto.signSchnorr(ByteVector32(id), by, null).toHex())
    }

    /** [event] as it is, with a signature by [by] instead of its author. */
    private fun signedBy(event: Event, by: PrivateKey) =
        Event(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, Crypto.signSchnorr(ByteVector32(event.id), by, null).toHex())

    private fun Event.replacing(name: String, vararg value: String): Array<Array<String>> =
        tags.map { if (it[0] == name) arrayOf(name, *value) else it }.toTypedArray()

    private fun transfer(request: Event, coins: List<CoinShare> = emptyList(), by: KeyMaterial = group, createdAt: Long = 1_700_000_100): Event =
        MemberTransferEvent.template(requestOf(request), coins, createdAt).let { signedAsRoom(by, MemberTransferEvent.KIND, it.tags, "", createdAt) }

    private fun transferWith(tags: Array<Array<String>>, by: KeyMaterial = group) = signedAsRoom(by, MemberTransferEvent.KIND, tags, "", 1_700_000_100)

    // --- 30342 ---

    @Test
    fun `a request reads and is believed`() {
        val read = requestOf(request())
        assertEquals(aliceKey, read.giver)
        assertEquals(bobKey, read.receiver)
        assertEquals(room, read.chatRoomId)
        assertEquals(Rail.WAVELENGTH, read.rail)
        assertEquals(20_000, read.amountSat)
        assertEquals("groceries", read.note)
    }

    @Test
    fun `a request signed by somebody other than its pubkey is not believed`() {
        val forged = request().let { signedBy(it, carol) }
        assertNotNull(TransferRequestEvent.read(forged))
        assertNull(TransferRequestEvent.verified(forged))
    }

    @Test
    fun `an unknown rail, no receiver, a receiver who is the giver, or a malformed amount is refused`() {
        val base = request()
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("rail", "ark"))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.tags.filter { it[0] != "p" }.toTypedArray())))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("p", aliceKey))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("amount_sat", "545"))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("amount_sat", "-20000"))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("amount_sat", "020000"))))
    }

    @Test
    fun `a d that disagrees with the room, chain or rail is refused`() {
        val base = request()
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("h", otherRoom))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("chain", "signet"))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("rail", "lightning"))))
    }

    @Test
    fun `a request that expires before it was asked, or a note too long or doubled, is refused`() {
        val base = request()
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("expires_at", base.createdAt.toString()))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.replacing("note", "x".repeat(141)))))
        assertNull(TransferRequestEvent.verified(resigned(base, base.tags + arrayOf(arrayOf("note", "again")))))
        assertFailsWith<IllegalArgumentException> { request(note = "x".repeat(141)) }
        assertFailsWith<IllegalArgumentException> { request(amount = 545) }
    }

    @Test
    fun `a withdrawal reads, and one that names nothing or carries a request's tags is refused`() {
        val asked = request()
        val withdrawn = TransferRequestEvent.buildWithdrawn(alice, room, chain, asked.id, 1_700_000_050)
        val read = assertIs<TransferRequestEvent.Withdrawal>(TransferRequestEvent.verified(withdrawn))
        assertEquals(asked.id, read.requestId)
        assertNull(TransferRequestEvent.verified(resigned(withdrawn, withdrawn.replacing("withdrawn", "nothing"))))
        assertNull(TransferRequestEvent.verified(resigned(withdrawn, withdrawn.tags + arrayOf(arrayOf("amount_sat", "20000")))))
    }

    @Test
    fun `a carried request is the giver's only when the giver carried it`() {
        val asked = request()
        val inner = Event("00".repeat(32), aliceKey, asked.createdAt, TransferRequestEvent.KIND, arrayOf(arrayOf("h", room)), asked.toJson(), "")
        assertEquals(asked.id, TransferRequestEvent.carried(inner)?.id)
        assertNull(TransferRequestEvent.carried(Event(inner.id, bobKey, inner.createdAt, inner.kind, inner.tags, inner.content, inner.sig)))
    }

    // --- 30343 ---

    @Test
    fun `a transfer reads and is believed, its request with it`() {
        val asked = request()
        val read = assertNotNull(MemberTransferEvent.verify(transfer(asked), room))
        assertEquals(aliceKey, read.from)
        assertEquals(bobKey, read.to)
        assertEquals(20_000, read.amountSat)
        assertEquals(asked.id, read.requestId)
        assertEquals(emptyList(), read.coins)
    }

    @Test
    fun `a transfer another room signed, or of a request made in another room, is not this room's`() {
        assertNull(MemberTransferEvent.verify(transfer(request(), by = other), room))
        assertNull(MemberTransferEvent.verify(transfer(request(group = otherRoom)), room))
    }

    @Test
    fun `a transfer whose from is not the request's signer is refused`() {
        // Carol's request, put to the group as Alice's: the case this plan exists to refuse (fact 3).
        val carols = request(signer = carol)
        val tags = MemberTransferEvent.template(requestOf(carols), createdAt = 1_700_000_100).tags
            .map { if (it[0] == "from") arrayOf("from", aliceKey) else it }.toTypedArray()
        assertNull(MemberTransferEvent.verify(transferWith(tags), room))
    }

    @Test
    fun `a transfer whose embedded request is not signed by its giver is refused`() {
        val asked = request()
        val forged = signedBy(asked, carol)
        val tags = MemberTransferEvent.template(requestOf(asked), createdAt = 1_700_000_100).tags
            .map { if (it[0] == "request") arrayOf("request", forged.toJson()) else it }.toTypedArray()
        assertNull(MemberTransferEvent.verify(transferWith(tags), room))
    }

    @Test
    fun `a tag that disagrees with the request is refused`() {
        val tags = MemberTransferEvent.template(requestOf(request()), createdAt = 1_700_000_100).tags
        fun with(name: String, value: String) = tags.map { if (it[0] == name) arrayOf(name, value) else it }.toTypedArray()
        assertNull(MemberTransferEvent.verify(transferWith(with("p", aliceKey)), room))
        assertNull(MemberTransferEvent.verify(transferWith(with("amount_sat", "20001")), room))
        assertNull(MemberTransferEvent.verify(transferWith(with("rail", "lightning")), room))
        assertNull(MemberTransferEvent.verify(transferWith(with("chain", "signet")), room))
        assertNull(MemberTransferEvent.verify(transferWith(with("d", "transfer:" + "ab".repeat(32))), room))
    }

    @Test
    fun `coins on a ledger rail are refused, and an onchain transfer needs coins that sum to it`() {
        val wavelength = MemberTransferEvent.template(requestOf(request()), createdAt = 1_700_000_100).tags
        assertNull(MemberTransferEvent.verify(transferWith(wavelength + arrayOf(arrayOf("coin", coin.text, "20000"))), room))

        val onchain = request(rail = Rail.ONCHAIN)
        val read = assertNotNull(MemberTransferEvent.verify(transfer(onchain, listOf(CoinShare(coin, 15_000), CoinShare(second, 5_000))), room))
        assertEquals(setOf(CoinShare(coin, 15_000), CoinShare(second, 5_000)), read.coins.toSet())

        val short = MemberTransferEvent.template(requestOf(onchain), listOf(CoinShare(coin, 20_000)), 1_700_000_100).tags
            .map { if (it[0] == "coin") arrayOf("coin", coin.text, "19000") else it }.toTypedArray()
        assertNull(MemberTransferEvent.verify(transferWith(short), room))
        val none = MemberTransferEvent.template(requestOf(onchain), listOf(CoinShare(coin, 20_000)), 1_700_000_100).tags
            .filter { it[0] != "coin" }.toTypedArray()
        assertNull(MemberTransferEvent.verify(transferWith(none), room))
        assertFailsWith<IllegalArgumentException> { MemberTransferEvent.template(requestOf(onchain)) }
        assertFailsWith<IllegalArgumentException> { MemberTransferEvent.template(requestOf(request()), listOf(CoinShare(coin, 20_000))) }
    }

    @Test
    fun `the same request embedded twice counts once, the first the group signed`() {
        val asked = request()
        val first = transfer(asked, createdAt = 1_700_000_100)
        val again = transfer(asked, createdAt = 1_700_000_200)
        val otherAsk = request(createdAt = 1_700_000_010)
        val counted = MemberTransferEvent.firstPerRequest(listOf(again, transfer(otherAsk, createdAt = 1_700_000_150), first), room, chain)
        assertEquals(listOf(first.id, counted[1].id), counted.map { it.id })
        assertEquals(2, counted.size)
        assertEquals(otherAsk.id, counted[1].requestId)
    }
}
