package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.GroupLightningNodes
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.UnknownFeature
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The group's node on the operator's device: built with the group's keys, started only by the elected
 * operator who has heard from the room recently, and stopped -- with a word to the room -- the moment a
 * later term begins (`docs/group-lightning-wallet.md`, Phase 8). The node itself is a stand-in: there is
 * no LSP here to connect to.
 */
class GroupLightningNodeJvmTest {
    private val fixture = GroupLightningFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    private var configurations = mutableListOf<GroupLightningNode.NodeConfiguration>()
    private val peers = mutableListOf<FakeGroupNode>()
    private val factory: suspend (GroupLightningNode.NodeConfiguration) -> GroupLightningNode.PeerHandle = { configuration ->
        configurations += configuration
        FakeGroupNode(configuration).also { peers += it }
    }

    private suspend fun ready(): List<GroupLightningFixture.Device> {
        val all = fixture.group()
        fixture.runCeremony(all)
        val session = GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp)
        fixture.signSession(all, all[0], session.id)
        return all
    }

    private suspend fun elect(all: List<GroupLightningFixture.Device>, who: GroupLightningFixture.Device) {
        val session = GroupLightningOperatorManager.nominateSelf(who.db, who.room, who.publicKey, fixture.chain)
        fixture.signSession(all, who, session.id)
    }

    /** As if this device had just heard from the room. */
    private suspend fun heardFromRoom(device: GroupLightningFixture.Device) {
        // A kind:445 is a nostr event first, and its row hangs off that one.
        val id = randomBytes32().toHex()
        device.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = device.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        device.db.marmotGroupEventDao().upsert(
            MarmotGroupEvent(id = id, userPublicKey = device.publicKey, publicKey = device.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now())
        )
    }

    private suspend fun node(device: GroupLightningFixture.Device, reconnectAfter: (Int) -> Duration = { 10.milliseconds }) = GroupLightningNode(
        device.db, device.room, device.publicKey, GroupLightningWalletManager.walletFor(device.db, fixture.roomId, fixture.chain)!!, scope, factory,
        handoverCheck = 100.milliseconds,
        minimumPool = 0,
        reconnectAfter = reconnectAfter,
    )

    private suspend fun runningNode(reconnectAfter: (Int) -> Duration = { 10.milliseconds }): Pair<GroupLightningNode, FakeGroupNode> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[1])
        val node = node(all[1], reconnectAfter)
        assertIs<GroupLightningNode.State.Running>(node.start())
        return node to peers.single()
    }

    @Test
    fun `the node runs on the group's keys`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[1])
        val node = node(all[1])
        assertIs<GroupLightningNode.State.Running>(node.start())
        val keys = configurations.single().keyManager
        val wallet = node.wallet
        assertEquals(wallet.statement.nodeId, keys.nodeKeys.nodeKey.publicKey.toString())
        val channelKeys = keys.channelKeys(keys.newFundingKeyPath(isChannelOpener = false))
        assertEquals(PublicKey.fromHex(wallet.statement.icebergKey), channelKeys.fundingPublicKey(0))
        assertEquals(wallet.basepoints.forChannel.payment, channelKeys.paymentBasePoint)
        assertEquals(wallet.basepoints.forChannel.delayedPayment, channelKeys.delayedPaymentBasePoint)
        assertEquals(GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript, keys.defaultFinalScriptPubKey)
        assertTrue(peers.single().connected)
        // Starting asked the group for round one ahead of any need.
        assertTrue(fixture.outbox(all[1], setOf(GroupLightningEvents.SIGNING_REQUEST)).isNotEmpty())
        assertTrue(fixture.outbox(all[1], setOf(GroupLightningEvents.NONCE_BATCH)).isNotEmpty())
        node.stop()
    }

    @Test
    fun `only the elected operator, who has heard from the room, may run it`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[2])
        assertIs<GroupLightningNode.State.Refused>(node(all[2]).start(), "a member who was not elected")
        assertIs<GroupLightningNode.State.Refused>(node(all[1]).start(), "the operator, who has heard nothing from the room")
        assertTrue(peers.isEmpty())
    }

    @Test
    fun `a later term stops the node, and the room is told where its channels were`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[1])
        val node = node(all[1])
        assertIs<GroupLightningNode.State.Running>(node.start())
        elect(all, all[2])
        withTimeout(5.seconds) { while (node.state.value is GroupLightningNode.State.Running) delay(50) }
        assertTrue(peers.single().disconnected)
        val stopped = fixture.outbox(all[1], setOf(GroupLightningEvents.STOPPED)).single()
        val body = CommonJson.decodeFromString(GroupLightningOperatorManager.Stopped.serializer(), stopped.content)
        assertEquals(1, body.term)
        assertEquals(mapOf("ab".repeat(32) to 7L), body.commitmentNumbers)

        // The room is told in words too, on every device, by the operator of the term it names.
        fixture.pump(all, setOf(GroupLightningEvents.STOPPED))
        all.forEach { d ->
            val line = fixture.lines(d, ChatMessage.TYPE_LIGHTNING_OPERATOR_STOPPED).single()
            assertEquals(all[1].publicKey, line.senderPublicKey)
            assertEquals("stopped running the group's Lightning node.", line.content)
        }
        // A stop from anybody else is nobody's.
        val forged = with(fixture) { stopped.copy(id = "f".repeat(64), publicKey = all[3].publicKey).asEvent() }
        all[2].db.marmotInnerEventDao().upsert(stopped.copy(id = "f".repeat(64), publicKey = all[3].publicKey))
        GroupLightningOperatorManager.receiveStopped(all[2].db, all[2].room, forged)
        assertEquals(1, fixture.lines(all[2], ChatMessage.TYPE_LIGHTNING_OPERATOR_STOPPED).size)
    }

    @Test
    fun `the identity's nodes start where it operates and nowhere else`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[3])
        all.forEach { heardFromRoom(it) }
        val operatorNodes = GroupLightningNodes(all[3].db, all[3].publicKey, scope, factory, fixture.chain, minimumPool = 0)
        val otherNodes = GroupLightningNodes(all[0].db, all[0].publicKey, scope, factory, fixture.chain, minimumPool = 0)
        operatorNodes.refresh()
        otherNodes.refresh()
        assertIs<GroupLightningNode.State.Running>(operatorNodes.nodeFor(fixture.roomId)?.state?.value)
        assertEquals(null, otherNodes.nodeFor(fixture.roomId))
        operatorNodes.stop()
        assertTrue(peers.single().disconnected)
        assertTrue(1.minutes > 0.seconds)
    }

    @Test
    fun `a node does not connect before the group has sent round one`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[1])
        // Nobody answers the provision request here, so the pool never fills.
        val node = GroupLightningNode(
            all[1].db, all[1].room, all[1].publicKey, GroupLightningWalletManager.walletFor(all[1].db, fixture.roomId, fixture.chain)!!, scope, factory,
            minimumPool = 4, poolWait = 1.seconds,
        )
        assertIs<GroupLightningNode.State.Refused>(node.start())
        assertTrue(peers.isEmpty(), "it never connected")
    }

    @Test
    fun `a node the LSP drops dials it again`() = runBlocking<Unit> {
        val (node, peer) = runningNode()
        assertEquals(1, peer.connects)
        peer.drops.send("Connection closed")
        withTimeout(5.seconds) { while (peer.connects < 2) delay(10) }
        peer.drops.send("Connection closed")
        withTimeout(5.seconds) { while (peer.connects < 3) delay(10) }
        assertIs<GroupLightningNode.State.Running>(node.state.value)
        node.stop()
    }

    @Test
    fun `connections that drop at once back off further each time`() = runBlocking<Unit> {
        val failures = mutableListOf<Int>()
        val (node, peer) = runningNode(reconnectAfter = { failures += it; 10.milliseconds })
        repeat(3) { i ->
            peer.drops.send("Connection closed")
            withTimeout(5.seconds) { while (peer.connects < i + 2) delay(10) }
        }
        assertEquals(listOf(1, 2, 3), failures)
        // Each dial carries its place in the run, for Phoenix's growing timeouts.
        assertEquals(listOf(1, 2, 3, 4), peer.attempts)
        node.stop()
    }

    @Test
    fun `a stopped node does not dial again`() = runBlocking<Unit> {
        val (node, peer) = runningNode()
        node.stop()
        peer.drops.send("Connection closed")
        delay(200)
        assertEquals(1, peer.connects)
        assertTrue(peer.disconnected)
    }

    @Test
    fun `the backoff doubles from two seconds to five minutes`() {
        assertEquals(
            listOf(2.seconds, 4.seconds, 8.seconds, 16.seconds, 32.seconds, 64.seconds, 128.seconds, 256.seconds, 5.minutes, 5.minutes),
            (1..10).map(GroupLightningNode::reconnectDelay),
        )
        assertEquals(5.minutes, GroupLightningNode.reconnectDelay(1_000))
    }

    @Test
    fun `the timeouts grow as Phoenix's do, three times as long over Tor`() {
        assertEquals(1.seconds to 2.seconds, GroupLightningNode.connectTimeouts(1, torIsEnabled = false))
        assertEquals(4.seconds to 7.seconds, GroupLightningNode.connectTimeouts(5, torIsEnabled = false))
        assertEquals(10.seconds to 15.seconds, GroupLightningNode.connectTimeouts(50, torIsEnabled = false))
        assertEquals(3.seconds to 6.seconds, GroupLightningNode.connectTimeouts(1, torIsEnabled = true))
    }

    @Test
    fun `a drop names the feature the LSP would not offer`() {
        val ours = Features(Feature.SimpleTaprootChannels to FeatureSupport.Mandatory, Feature.VariableLengthOnion to FeatureSupport.Mandatory)
        val closed = Connection.CLOSED(TcpSocket.IOException.ConnectionClosed())
        assertEquals(
            "Connection closed: the LSP does not offer simple_taproot_channels, which this node requires",
            GroupLightningNode.dropReason(ours, Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory), closed),
        )
        assertEquals(
            "Connection closed: the LSP requires bit 600, which this node does not know",
            GroupLightningNode.dropReason(ours, Features(ours.activated, setOf(UnknownFeature(600), UnknownFeature(601))), closed),
        )
        assertEquals("Connection closed", GroupLightningNode.dropReason(ours, ours, closed))
        assertEquals("Connection closed, before the LSP sent its features", GroupLightningNode.dropReason(ours, null, closed))
        assertEquals("closed by this node", GroupLightningNode.dropReason(ours, ours, Connection.CLOSED(null)))
    }
}
