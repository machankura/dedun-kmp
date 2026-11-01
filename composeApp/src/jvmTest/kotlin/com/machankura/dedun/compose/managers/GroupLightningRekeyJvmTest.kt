package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.GroupLightningNodes
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.lightning.Lightning.randomBytes32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A member leaves a group with a Lightning wallet (`docs/group-lightning-wallet.md`, Phase 14). Iceberg
 * cannot reshare, so the group makes a new key without them and opens a second wallet beside the first,
 * whose channels it then closes. Five members, so that four remain: a 2-of-n Iceberg key needs n >= 4.
 */
class GroupLightningRekeyJvmTest {
    private val fixture = GroupLightningFixture(n = 5, frostThreshold = 3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()
    private val nodes = mutableListOf<FakeGroupNode>()

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    private suspend fun heardFromRoom(device: GroupLightningFixture.Device) {
        val id = randomBytes32().toHex()
        device.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = device.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        device.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = device.publicKey, publicKey = device.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
    }

    @Test
    fun `the group re-keys without a member who left, and keeps the old wallet until its channels close`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        fixture.signSession(all, all[1], GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain, 0).id)
        val first = GroupLightningWalletManager.current(all[0].db, fixture.roomId, fixture.chain)!!
        assertEquals(0, first.statement.wallet)
        assertEquals(5, first.statement.participants.size)

        // The last member is removed; the four who remain apply the commit.
        val leaver = all[4]
        val remaining = all.take(4)
        fixture.remove(leaver.publicKey, remaining)
        assertEquals(listOf(leaver.publicKey.lowercase()), GroupLightningWalletManager.departed(all[0].db, first))
        assertEquals(listOf(2), IcebergRitualManager.thresholds(all[0].db, fixture.roomId), "four remain, so 2-of-4")

        // The leaver's device, which never saw its own removal, cannot pull the others into a ceremony with it.
        val stale = IcebergRitualManager.proposeCeremony(leaver.db, leaver.room, leaver.publicKey, leaver.privateKey, 2, fixture.chain)
        fixture.pump(all, GroupLightningEvents.CEREMONY)
        remaining.forEach { assertNull(it.ceremony(stale.id), "device ${it.index} refused a ceremony naming someone who left") }

        val second = fixture.runCeremony(remaining, proposer = remaining[0])
        remaining.forEach { assertEquals(IcebergRitualStage.COMPLETE, it.ceremony(second.id)?.stage) }
        assertEquals(4, remaining[0].ceremony(second.id)!!.participants().size)
        assertTrue(leaver.publicKey.lowercase() !in remaining[0].ceremony(second.id)!!.participants())

        fixture.signSession(remaining, remaining[0], GroupLightningWalletManager.proposeWallet(remaining[0].db, remaining[0].room, remaining[0].publicKey, remaining[0].privateKey, fixture.chain, lsp).id)
        remaining.forEach { d ->
            val wallets = GroupLightningWalletManager.wallets(d.db, fixture.roomId, fixture.chain)
            assertEquals(listOf(0L, 1L), wallets.map { it.statement.wallet }, "device ${d.index} believes both")
            val current = wallets.last()
            assertEquals(second.id, current.statement.ceremonyId)
            assertNotEquals(first.statement.nodeId, current.statement.nodeId)
            assertTrue(GroupLightningWalletManager.departed(d.db, current).isEmpty())
            assertEquals(null, GroupLightningWalletManager.unusedKey(d.db, fixture.roomId, fixture.chain), "both keys are in use")
        }

        // The new wallet gets its own operator; the old one keeps its own until its channels are closed.
        fixture.signSession(remaining, remaining[2], GroupLightningOperatorManager.nominateSelf(remaining[2].db, remaining[2].room, remaining[2].publicKey, fixture.chain, 1).id)
        assertEquals(remaining[1].publicKey, GroupLightningOperatorManager.current(remaining[0].db, fixture.roomId, fixture.chain, 0)?.publicKey)
        assertEquals(remaining[2].publicKey, GroupLightningOperatorManager.current(remaining[0].db, fixture.roomId, fixture.chain, 1)?.publicKey)
        val factory: suspend (GroupLightningNode.NodeConfiguration) -> GroupLightningNode.PeerHandle = { FakeGroupNode(it).also { node -> nodes += node } }
        remaining.forEach { heardFromRoom(it) }
        val oldOperator = GroupLightningNodes(remaining[1].db, remaining[1].publicKey, scope, factory, fixture.chain, minimumPool = 0)
        val newOperator = GroupLightningNodes(remaining[2].db, remaining[2].publicKey, scope, factory, fixture.chain, minimumPool = 0)
        oldOperator.refresh()
        newOperator.refresh()
        assertIs<GroupLightningNode.State.Running>(oldOperator.nodeFor(fixture.roomId, 0)?.state?.value)
        assertNull(oldOperator.nodeFor(fixture.roomId, 1))
        assertIs<GroupLightningNode.State.Running>(newOperator.nodeFor(fixture.roomId, 1)?.state?.value)
        assertEquals(1, newOperator.nodeFor(fixture.roomId)?.wallet?.statement?.wallet, "a room's node is its current wallet's")

        // Closing the old wallet's channels is one decision, whatever their number.
        val channels = listOf("a1".repeat(32), "a2".repeat(32))
        val old = GroupLightningWalletManager.walletFor(remaining[3].db, fixture.roomId, fixture.chain, 0)!!
        val session = GroupLightningAuthorisations.proposeCloseAll(remaining[3].db, remaining[3].room, remaining[3].publicKey, old, channels)
        assertEquals(2, remaining[3].db.frostSigningSessionDao().getItems(session.id).size)
        fixture.signSession(remaining, remaining[3], session.id)
        val actions = GroupLightningAuthorisations.channelActions(remaining[1].db, fixture.roomId, old.statement)
        assertEquals(channels.toSet(), actions.map { it.channelId }.toSet())
        assertTrue(actions.all { it.action == GroupLightningAuthorisationEvent.CLOSE && it.wallet == 0L })
        assertTrue(GroupLightningAuthorisations.channelActions(remaining[1].db, fixture.roomId, GroupLightningWalletManager.walletFor(remaining[1].db, fixture.roomId, fixture.chain, 1)!!.statement).isEmpty(), "the new wallet's channels are not touched")
        oldOperator.stop()
        newOperator.stop()
    }
}
