package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.model.GroupDmWrapState
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose

/**
 * Phase 7 of docs/group-threshold-ecdh.md: a person on a stock NIP-17 client writes to the
 * group's npub, and the admins' devices read it, with a share-holder who is not an admin
 * reading nothing.
 */
class GroupDmReceiveJvmTest {
    private val net = GroupDmNetwork(n = 4, t = 2)
    private val a get() = net.devices[0]
    private val b get() = net.devices[1]
    private val c get() = net.devices[2]
    private val d get() = net.devices[3]

    @BeforeTest
    fun setUp() = runBlocking {
        ExperimentalFeatures.groupDirectMessagesOverride = true
        net.admins = net.members.take(3).toSet()
        net.install()
        net.all()
        net.openMessages()
    }

    @AfterTest
    fun tearDown() {
        net.close()
        ExperimentalFeatures.groupDirectMessagesOverride = null
        GroupDmReceiver.maxNewWrapRoundsPerHour = 60
    }

    private val sender = KeyPair()
    private val holdC = { from: GroupDmNetwork.Device, _: MarmotInnerEvent -> from === c }

    private suspend fun texts(device: GroupDmNetwork.Device) =
        device.db.groupDmDao().getMessages(net.roomId).sortedBy { it.createdAt }.map { Event.fromJson(it.rumorJson).content }

    @Test
    fun `a message from an outside identity opens on all three admin devices once two have answered`(): Unit = runBlocking {
        val wrap = net.wrapFrom(sender, "dumela, group")
        net.receive(wrap, a, b, c, d)
        net.pump(hold = holdC)

        listOf(a, b, c).forEach { device ->
            assertEquals(listOf("dumela, group"), texts(device), "$device")
            val message = device.db.groupDmDao().getMessages(net.roomId).single()
            assertEquals(sender.pubKey.toHexKey(), message.senderPublicKey)
            assertFalse(message.outgoing)
            assertEquals(GroupDmWrapState.OPENED, device.db.groupDmDao().getWrap(wrap.id)?.state)
        }
    }

    @Test
    fun `a share-holder who is not an admin files nothing, and no NostrEvent or gift wrap row is written`(): Unit = runBlocking {
        val wrap = net.wrapFrom(sender, "for the admins")
        net.receive(wrap, a, b, c, d)
        net.pump()

        assertFalse(GroupDmReceiver.readsFor(d.db, net.roomId, d.publicKey))
        assertEquals(emptyList(), d.db.groupDmDao().getMessages(net.roomId))
        assertEquals(null, d.db.groupDmDao().getWrap(wrap.id))
        // On an admin's device, through storeNostrEvent as the old path would see it.
        val relay = "wss://dm.example.com"
        val again = net.wrapFrom(sender, "through the old door")
        a.db.nostrDao().storeNostrEvent(
            com.machankura.dedun.compose.database.model.NostrEvent(
                id = again.id, pubKey = again.pubKey, kind = again.kind, tags = again.tags, content = again.content,
                sig = again.sig, createdAt = kotlin.time.Instant.fromEpochSeconds(again.createdAt),
            ),
            relay, listOf(relay), 0, a.keyPair,
        )
        assertEquals(null, a.db.nostrEventDao().getNostrEventById(again.id), "no NostrEvent row")
        assertEquals(emptyList(), a.db.giftWrapMessageDao().getAllGiftWrapMessages(), "no GiftWrapMessage row")
        assertTrue(a.db.groupDmDao().getWrap(again.id) != null, "the group's ledger has it")
    }

    @Test
    fun `a second message from the same sender takes one round`(): Unit = runBlocking {
        net.receive(net.wrapFrom(sender, "one"), a, b, c)
        net.pump()
        val roundsBefore = a.db.groupDmDao().getAllOpenRounds().size to roundsOn(a)

        net.receive(net.wrapFrom(sender, "two"), a, b, c)
        net.pump()
        assertEquals(listOf("one", "two"), texts(a).sorted())
        assertEquals(roundsBefore.second + 1, roundsOn(a), "the wrap's round only; the seal's key was kept")
    }

    private suspend fun roundsOn(device: GroupDmNetwork.Device): Int =
        device.db.groupDmDao().countWrapRoundsSince(net.roomId, kotlin.time.Instant.fromEpochSeconds(0)) +
            listOf(EcdhPurpose.SEAL).sumOf { 0 } +
            device.db.groupDmDao().getConversationKeys(net.roomId).size

    @Test
    fun `the same rumor in two wraps is one message`(): Unit = runBlocking {
        val rumor = Event("", sender.pubKey.toHexKey(), 1_700_000_000, 14, arrayOf(arrayOf("p", net.roomId)), "once", "")
        val seal = SealEvent.create(rumor, net.roomId, NostrSignerInternal(sender))
        net.receive(GiftWrapEvent.create(seal, net.roomId), a, b, c)
        net.receive(GiftWrapEvent.create(seal, net.roomId), a, b, c)
        net.pump()
        listOf(a, b, c).forEach { assertEquals(listOf("once"), texts(it)) }
    }

    @Test
    fun `a promoted admin's device opens a wrap from before the promotion`(): Unit = runBlocking {
        net.admins = setOf(net.members[0], net.members[1])
        val wrap = net.wrapFrom(sender, "before you were an admin")
        net.receive(wrap, a, b, c)
        net.pump()
        assertEquals(emptyList(), texts(c), "not an admin yet")

        net.admins = net.members.take(3).toSet()
        net.receive(wrap, c)
        net.pump()
        assertEquals(listOf("before you were an admin"), texts(c))
    }

    @Test
    fun `the rate cap queues rather than drops`(): Unit = runBlocking {
        GroupDmReceiver.maxNewWrapRoundsPerHour = 2
        val wraps = (1..3).map { net.wrapFrom(KeyPair(), "message $it") }
        wraps.forEach { net.receive(it, a) }
        val dao = a.db.groupDmDao()
        assertEquals(1, dao.getWrapsInState(net.roomId, GroupDmWrapState.QUEUED.name).size, "one waits")
        assertEquals(2, dao.countWrapRoundsSince(net.roomId, Clock.System.now() - 1.hours))

        // An hour on: the queued one opens.
        dao.getAllOpenRounds().forEach { dao.upsertRound(it.copy(openedAt = Clock.System.now() - 2.hours)) }
        GroupEcdhManager.advance(a.db, a.context()!!)
        assertEquals(emptyList(), dao.getWrapsInState(net.roomId, GroupDmWrapState.QUEUED.name))
        net.receive(wraps[0], b); net.receive(wraps[1], b); net.receive(wraps[2], b)
        GroupDmReceiver.maxNewWrapRoundsPerHour = 60
        net.pump(between = listOf(a, b))
        assertEquals(listOf("message 1", "message 2", "message 3"), texts(a).sorted())
    }

    @Test
    fun `live-group-dm asks each relay for the groups that list it, two days before the newest wrap`() {
        val dm = com.machankura.dedun.compose.nostr.Relays.DefaultDMRelayList.first().url
        val filters = LiveSubscriptionManager.groupDmFilters(
            listOf(
                GroupDmInbox("a".repeat(64), listOf("wss://one.example/"), newestWrapAt = 1_700_000_000),
                GroupDmInbox("b".repeat(64), listOf("wss://two.example/"), newestWrapAt = null),
            )
        )
        val one = filters.getValue("wss://one.example/").single()
        assertEquals(listOf(GiftWrapEvent.KIND), one.kinds)
        assertEquals(listOf("a".repeat(64)), one.tags?.get("p"))
        assertEquals(1_700_000_000L - 2 * 24 * 60 * 60, one.since)
        assertEquals(null, filters.getValue("wss://two.example/").single().since, "a room with nothing yet asks from the start")
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), filters.getValue(dm).single().tags?.get("p"), "the app's own DM relays carry every room")
        assertEquals(emptyMap(), LiveSubscriptionManager.groupDmFilters(emptyList()))
    }
}
