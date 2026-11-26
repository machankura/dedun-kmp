package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.cryptography.threshold.Dleq
import com.machankura.dedun.compose.cryptography.threshold.EcdhPartial
import com.machankura.dedun.compose.cryptography.threshold.ThresholdEcdh
import com.machankura.dedun.compose.database.model.GroupEcdhRoundState
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.groupdm.AdminSeal
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhEvents
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhPayload

/**
 * Phase 5 of docs/group-threshold-ecdh.md: three admins at `t = 2` and a share-holder who is
 * not an admin, answering and combining rounds between devices that share nothing but the
 * inner events the network ferries.
 */
class GroupEcdhRoundJvmTest {
    private val net = GroupDmNetwork(n = 4, t = 2)
    private val a get() = net.devices[0]
    private val b get() = net.devices[1]
    private val c get() = net.devices[2]
    private val d get() = net.devices[3]

    @BeforeTest
    fun setUp() = runBlocking {
        net.admins = net.members.take(3).toSet()
        net.install()
        net.all()
        Unit
    }

    @AfterTest
    fun tearDown() = net.close()

    private val sender = KeyPair()

    private suspend fun wrapEverywhere(text: String = "dumela") =
        net.wrapFrom(sender, text).also { wrap -> net.devices.forEach { net.ledger(it, wrap) } }

    private fun holdFrom(device: GroupDmNetwork.Device) = { from: GroupDmNetwork.Device, _: MarmotInnerEvent -> from === device }

    /** What the sender's own app computes: the interoperability claim, end to end. */
    private val expectedWithSender get() = Nip44v2().computeConversationKey(sender.privKey!!, net.roomId.hexToByteArray()).toHex()

    @Test
    fun `two admins' partials give the same conversation key on all three admin devices`(): Unit = runBlocking {
        val wrap = wrapEverywhere()
        GroupEcdhManager.open(a.db, a.context()!!, wrap.pubKey, EcdhPurpose.WRAP, ref = wrap.id)
        net.pump(hold = holdFrom(c))

        listOf(a, b, c).forEach { device ->
            val ledger = assertNotNull(device.db.groupDmDao().getWrap(wrap.id))
            val seal = Event.fromJson(assertNotNull(ledger.sealJson, "$device opened the wrap"))
            assertEquals(sender.pubKey.toHexKey(), seal.pubKey)
        }

        // The seal round, carrying the seal by the wrap's id.
        val seal = Event.fromJson(a.db.groupDmDao().getWrap(wrap.id)!!.sealJson!!)
        GroupEcdhManager.open(a.db, a.context()!!, seal.pubKey, EcdhPurpose.SEAL, ref = wrap.id)
        net.pump(hold = holdFrom(c))

        listOf(a, b, c).forEach { device ->
            val key = assertNotNull(device.db.groupDmDao().getConversationKey(net.roomId, seal.pubKey), "$device kept the key")
            assertEquals(expectedWithSender, key.conversationKey, "$device's key is the one the sender's app computed")
        }
        // The wrap's key is spent, not kept (D6).
        listOf(a, b, c).forEach { assertNull(it.db.groupDmDao().getConversationKey(net.roomId, wrap.pubKey)) }
    }

    @Test
    fun `a share-holder who is not an admin stores nothing it can read and combines nothing`(): Unit = runBlocking {
        val wrap = wrapEverywhere()
        GroupEcdhManager.open(a.db, a.context()!!, wrap.pubKey, EcdhPurpose.WRAP, ref = wrap.id)
        net.pump()

        assertNull(d.context(), "no part to take")
        assertEquals(emptyList(), d.db.groupDmDao().getAllOpenRounds())
        assertNull(d.db.groupDmDao().getRound(GroupEcdhPayload.roundId(net.roomId, 0, wrap.pubKey)))
        // Partials reaching a device that is not an admin make it forget what it held (D14).
        assertNull(d.db.groupDmDao().getWrap(wrap.id)?.sealJson)
        assertEquals(emptyList(), net.outbox(d), "and sends nothing")
        // The slots on every partials message name only the three admins' host keys.
        val adminHosts = net.hostKeys.take(3).toSet()
        listOf(a, b, c).flatMap { net.outbox(it) }.filter { it.kind == GroupEcdhEvents.PARTIALS }.forEach { message ->
            assertTrue(AdminSeal.parseSlots(message.tags).keys.all { it in adminHosts })
            assertFalse(net.hostKeys[3] in AdminSeal.parseSlots(message.tags).keys)
        }
    }

    @Test
    fun `a removed admin's device gets no slot in partials sent after the removal`(): Unit = runBlocking {
        net.admins = net.members.take(2).toSet()
        val wrap = wrapEverywhere()
        GroupEcdhManager.open(a.db, a.context()!!, wrap.pubKey, EcdhPurpose.WRAP, ref = wrap.id)
        net.pump()

        assertNotNull(b.db.groupDmDao().getWrap(wrap.id)!!.sealJson, "the two remaining admins read it")
        assertNull(c.context())
        assertNull(c.db.groupDmDao().getWrap(wrap.id)?.sealJson, "the removed admin does not, and forgets the wrap")
        assertEquals(emptyList(), c.db.groupDmDao().getAllOpenRounds())
        net.outbox(a).filter { it.kind == GroupEcdhEvents.PARTIALS }.forEach {
            assertFalse(net.hostKeys[2] in AdminSeal.parseSlots(it.tags).keys)
        }
    }

    @Test
    fun `a forged partial is named, and the round completes with the third admin`(): Unit = runBlocking {
        val wrap = wrapEverywhere()
        GroupEcdhManager.open(b.db, b.context()!!, wrap.pubKey, EcdhPurpose.WRAP, ref = wrap.id)

        // A partial "from A" made with another scalar, sealed to B as A's device would seal it.
        val point = ThresholdEcdh.pointOf(wrap.pubKey)!!
        val aId = net.idOf[0]
        val forger = PrivateKey(Crypto.sha256("forger".encodeToByteArray()))
        val forged = EcdhPartial.compute(forger, point)
        val payload = GroupEcdhPayload(
            generation = 0, from = aId,
            items = listOf(
                GroupEcdhPayload.Item(
                    point = wrap.pubKey, purpose = EcdhPurpose.WRAP, ref = wrap.id, partial = forged,
                    proof = Dleq.prove(forger, net.keyMaterial.publicShares[aId], point, forged, Dleq.context(net.roomId, 0, aId), ByteArray(32)),
                )
            ),
        )
        val sealed = AdminSeal.seal(payload.encode(), ChillDkgRitualManager.deriveHostSecretKey(a.privateKey), listOf(net.hostKeys[1]))
        net.deliver(
            MarmotInnerEvent(
                id = "f".repeat(64), publicKey = a.publicKey, kind = GroupEcdhEvents.PARTIALS,
                createdAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds), tags = GroupEcdhEvents.partialsTags(sealed),
                content = sealed.content, chatRoomId = net.roomId,
            ),
            b,
        )

        val roundId = GroupEcdhPayload.roundId(net.roomId, 0, wrap.pubKey)
        val bad = b.db.groupDmDao().getPartials(roundId).single { it.participantId == aId }
        assertFalse(bad.verified)
        assertNotNull(bad.blamedAt, "named")
        val blame = net.outbox(b, listOf(GroupEcdhEvents.FAILURE)).single()
        assertEquals(roundId to aId, GroupEcdhEvents.parseFailure(blame.tags))
        assertNull(b.db.groupDmDao().getWrap(wrap.id)!!.sealJson, "one proven partial is not two")

        net.pump(between = listOf(b, c))
        assertNotNull(b.db.groupDmDao().getWrap(wrap.id)!!.sealJson, "B and C completed it")
        assertEquals(GroupEcdhRoundState.COMBINED, b.db.groupDmDao().getRound(roundId)!!.state)
    }

    @Test
    fun `a point without evidence is never answered`(): Unit = runBlocking {
        // A recipient whose "evidence" was signed by somebody else.
        val someone = KeyPair()
        val impostor = NostrSignerSync(KeyPair()).sign<Event>(1_700_000_000, 0, emptyArray(), "{}")
        GroupEcdhManager.open(a.db, a.context()!!, someone.pubKey.toHexKey(), EcdhPurpose.RECIPIENT, evidence = impostor)
        // A wrap this device has not seen.
        val unseen = net.wrapFrom(sender, "unseen")
        GroupEcdhManager.open(a.db, a.context()!!, unseen.pubKey, EcdhPurpose.WRAP, ref = unseen.id)
        assertEquals(emptyList(), net.outbox(a), "nothing answered")

        // Once the wrap is in the ledger the sweep answers it.
        net.ledger(a, unseen)
        GroupEcdhManager.advance(a.db, a.context()!!)
        assertEquals(1, net.outbox(a).count { it.kind == GroupEcdhEvents.PARTIALS })

        // B holds A's partial but no wrap: it stores it and does not answer.
        net.pump(between = listOf(a, b))
        val roundId = GroupEcdhPayload.roundId(net.roomId, 0, unseen.pubKey)
        assertEquals(listOf(net.idOf[0]), b.db.groupDmDao().getPartials(roundId).map { it.participantId })
        assertEquals(emptyList(), net.outbox(b))
        net.ledger(b, unseen)
        GroupEcdhManager.advance(b.db, b.context()!!)
        assertNotNull(b.db.groupDmDao().getWrap(unseen.id)!!.sealJson)
    }

    // --- a refresh mid-round

    /** Generation 1: the same key on another polynomial, `s'ᵢ = sᵢ + c·(i+1)`, as a refresh would leave it. */
    private suspend fun refreshAll() {
        val c = PrivateKey(Crypto.sha256("refresh".encodeToByteArray()))
        val newShares = net.keyMaterial.secretShares.mapIndexed { id, s -> s + c * PrivateKey(ByteArray(32).also { it[31] = (id + 1).toByte() }) }
        val publicShares = newShares.joinToString(",") { it.publicKey().value.toHex() }
        net.devices.forEach { device ->
            device.db.keyShareDao().upsert(
                KeyShareGeneration(
                    dkgSessionId = net.ceremonyId, generation = 1, secretShare = newShares[net.idOf[device.index]].value.toHex(),
                    publicShares = publicShares, state = ShareGenerationState.ACTIVE, activatedAt = Clock.System.now(),
                )
            )
            device.db.keyShareDao().upsert(net.members.indices.map { i ->
                KeyShareMember(net.ceremonyId, 1, net.idOf[i], net.members[i], net.hostKeys[i], ShareStanding.LIVE)
            })
        }
    }

    @Test
    fun `a refresh mid-round restarts it, and partials of two generations never combine`(): Unit = runBlocking {
        val wrap = wrapEverywhere()
        GroupEcdhManager.open(a.db, a.context()!!, wrap.pubKey, EcdhPurpose.WRAP, ref = wrap.id)
        val stale = net.outbox(a).single { it.kind == GroupEcdhEvents.PARTIALS }

        refreshAll()
        assertEquals(1, b.context()!!.generation)

        // A generation-0 partial reaches B at generation 1: ignored, not combined with anything.
        net.deliver(stale, b)
        b.received += stale.id
        assertNull(b.db.groupDmDao().getRound(GroupEcdhPayload.roundId(net.roomId, 0, wrap.pubKey)))

        // A's sweep restarts its round at the new generation.
        GroupEcdhManager.advance(a.db, a.context()!!)
        assertEquals(GroupEcdhRoundState.FAILED, a.db.groupDmDao().getRound(GroupEcdhPayload.roundId(net.roomId, 0, wrap.pubKey))!!.state)
        val fresh = assertNotNull(a.db.groupDmDao().getRound(GroupEcdhPayload.roundId(net.roomId, 1, wrap.pubKey)))
        assertNotNull(fresh.partialSentAt)

        net.pump()
        listOf(a, b, c).forEach { device ->
            assertNotNull(device.db.groupDmDao().getWrap(wrap.id)!!.sealJson, "$device opened it at generation 1")
        }
    }
}
