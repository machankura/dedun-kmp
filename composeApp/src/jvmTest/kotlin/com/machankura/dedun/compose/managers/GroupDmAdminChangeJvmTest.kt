package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
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
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.groupdm.AdminSeal
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhEvents

/**
 * Phase 10 of docs/group-threshold-ecdh.md: admin changes need nothing new. A removed admin is
 * cut off from new mail at once and forgets what it kept; a promoted one reads the history;
 * and a share rotation leaves every kept key valid, because the group's key never changed (D1).
 */
class GroupDmAdminChangeJvmTest {
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
    }

    private val sender = KeyPair()

    private suspend fun texts(device: GroupDmNetwork.Device) =
        device.db.groupDmDao().getMessages(net.roomId).map { Event.fromJson(it.rumorJson).content }.sorted()

    @Test
    fun `after C's removal a new message opens on A and B, and C gets no slot, opens nothing, and forgets`(): Unit = runBlocking {
        net.receive(net.wrapFrom(sender, "before"), a, b, c)
        net.pump()
        assertEquals(listOf("before"), texts(c))
        assertNotNull(c.db.groupDmDao().getConversationKey(net.roomId, sender.pubKey.toHexKey()))

        net.admins = setOf(net.members[0], net.members[1])
        val after = net.wrapFrom(sender, "after")
        net.receive(after, a, b, c)
        val sentBefore = net.outbox(a).map { it.id }.toSet()
        net.pump()
        assertEquals(listOf("after", "before"), texts(a))
        assertEquals(listOf("after", "before"), texts(b))
        net.outbox(a).filter { it.id !in sentBefore && it.kind == GroupEcdhEvents.PARTIALS }.forEach {
            assertFalse(net.hostKeys[2] in AdminSeal.parseSlots(it.tags).keys, "no slot for C")
        }

        // C's device sees itself removed on its next sweep, and forgets.
        GroupEcdhManager.sweep(c.db, c.publicKey, c.privateKey)
        assertEquals(emptyList(), texts(c))
        assertEquals(emptyList(), c.db.groupDmDao().getConversationKeys(net.roomId))
        val line = c.db.chatMessageDao().getChatMessagesByChatRoomId(net.roomId).map { it.chatMessage }
            .single { it.messageType == ChatMessage.TYPE_GROUP_DM_FORGOTTEN }
        assertTrue("forgot" in line.content)
        // Once: a second sweep has nothing to forget.
        assertFalse(GroupDmReceiver.forgetIfRemoved(c.db, net.roomId, c.publicKey))
    }

    @Test
    fun `after a promotion, the new admin's device answers rounds and reads the history`(): Unit = runBlocking {
        net.receive(net.wrapFrom(sender, "history"), a, b, c, d)
        net.pump()
        assertEquals(emptyList(), texts(d))

        net.admins = net.members.toSet()
        // D's catch-up brings the old wrap; the others answer it although they combined long ago.
        net.devices.forEach { device -> device.db.groupDmDao().getWrapsInState(net.roomId, "OPENED").firstOrNull()?.let { net.receive(Event.fromJson(it.wrapJson), d) } }
        net.pump()
        assertEquals(listOf("history"), texts(d))

        // And D answers a new round as one of the admins, with A's app closed.
        val fresh = net.wrapFrom(KeyPair(), "new")
        net.receive(fresh, b, d)
        net.pump(between = listOf(b, d))
        assertEquals(listOf("history", "new"), texts(d))
        assertEquals(listOf("history", "new"), texts(b))
    }

    @Test
    fun `a share rotation after the removal leaves every kept key valid on A and B`(): Unit = runBlocking {
        net.receive(net.wrapFrom(sender, "one"), a, b, c)
        net.pump()
        val keyBefore = a.db.groupDmDao().getConversationKey(net.roomId, sender.pubKey.toHexKey())!!.conversationKey

        net.admins = setOf(net.members[0], net.members[1])
        rotate(without = c)
        assertEquals(1, a.context()!!.generation)

        // The kept key opens the next message's seal; only the wrap's round runs, at generation 1.
        net.receive(net.wrapFrom(sender, "two"), a, b)
        net.pump(between = listOf(a, b))
        assertEquals(listOf("one", "two"), texts(a))
        assertEquals(keyBefore, a.db.groupDmDao().getConversationKey(net.roomId, sender.pubKey.toHexKey())!!.conversationKey)

        // A new correspondent's key, made at generation 1, is the one their own app computes.
        val newcomer = KeyPair()
        net.receive(net.wrapFrom(newcomer, "hello"), a, b)
        net.pump(between = listOf(a, b))
        val kept = a.db.groupDmDao().getConversationKey(net.roomId, newcomer.pubKey.toHexKey())!!.conversationKey
        assertEquals(Nip44v2().computeConversationKey(newcomer.privKey!!, net.roomId.hexToByteArray()).toHex(), kept)
        // C, rotated out and no longer an admin, takes no part.
        assertNull(c.context())
    }

    /** Generation 1 on the same key, `s'ᵢ = sᵢ + k·(i+1)`, with [without] withheld, as a rotation would leave it. */
    private suspend fun rotate(without: GroupDmNetwork.Device) {
        val k = PrivateKey(Crypto.sha256("rotation".encodeToByteArray()))
        val shares = net.keyMaterial.secretShares.mapIndexed { id, s -> s + k * PrivateKey(ByteArray(32).also { it[31] = (id + 1).toByte() }) }
        val publicShares = shares.joinToString(",") { it.publicKey().value.toHex() }
        net.devices.forEach { device ->
            val withheld = device === without
            device.db.keyShareDao().upsert(
                KeyShareGeneration(
                    dkgSessionId = net.ceremonyId, generation = 1,
                    secretShare = if (withheld) null else shares[net.idOf[device.index]].value.toHex(),
                    publicShares = publicShares, state = ShareGenerationState.ACTIVE, activatedAt = Clock.System.now(),
                )
            )
            device.db.keyShareDao().upsert(net.members.indices.map { i ->
                KeyShareMember(net.ceremonyId, 1, net.idOf[i], net.members[i], net.hostKeys[i], if (net.devices[i] === without) ShareStanding.RETIRED else ShareStanding.LIVE)
            })
        }
    }
}
