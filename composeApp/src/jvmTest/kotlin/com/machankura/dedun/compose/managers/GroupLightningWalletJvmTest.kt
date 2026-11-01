package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningOperatorStatementEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.crypto.LocalKeyManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Opening a group's Lightning wallet and choosing who runs it, between four devices
 * (`docs/group-lightning-wallet.md`, Phases 6 and 7).
 */
class GroupLightningWalletJvmTest {
    private val fixture = GroupLightningFixture()
    private val lsp = KeyPair().pubKey.toHexKey().let { "02$it" }

    @AfterTest
    fun close() = fixture.close()

    private suspend fun openLightningWallet(all: List<GroupLightningFixture.Device>, proposer: GroupLightningFixture.Device = all[0]) {
        fixture.runCeremony(all)
        val session = GroupLightningWalletManager.proposeWallet(proposer.db, proposer.room, proposer.publicKey, proposer.privateKey, fixture.chain, lsp)
        fixture.signSession(all, proposer, session.id)
    }

    @Test
    fun `the group opens a Lightning wallet, and every member holds its seed`() = runBlocking {
        val all = fixture.group()
        openLightningWallet(all)
        val wallets = all.map { assertNotNull(GroupLightningWalletManager.walletFor(it.db, fixture.roomId, fixture.chain), "device ${it.index}") }
        assertEquals(1, wallets.map { it.statement }.toSet().size)
        assertEquals(1, wallets.map { it.basepoints.forChannel }.toSet().size, "every device derives the same basepoints")
        wallets.forEach { wallet ->
            val seed = assertNotNull(wallet.seed, "the seed reached every participant")
            assertEquals(wallet.statement.nodeId, seed.nodeId)
        }
        // The node id is lightning-kmp's own derivation from the seed, not just this manager's.
        val seed = ByteVector(wallets[0].seed!!.seed)
        val anyTpub = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
        assertEquals(LocalKeyManager(seed, fixture.chain, anyTpub).nodeKeys.nodeKey.publicKey.toString(), wallets[0].statement.nodeId)
        // And the room's transcript says so, on every device.
        all.forEach { d ->
            val lines = d.db.chatMessageDao().getChatMessagesByChatRoomId(fixture.roomId).map { it.chatMessage.messageType }
            assertTrue(ChatMessage.TYPE_GROUP_LIGHTNING_WALLET_OPENED in lines)
            // And who handed the seed out: one line, written by the proposer on the way out
            // and by everyone else as it arrived. docs/group-lightning-transcript.md, Phase 4.
            val seedLine = fixture.lines(d, ChatMessage.TYPE_LIGHTNING_SEED_SHARED).single()
            assertEquals(all[0].publicKey, seedLine.senderPublicKey)
            assertEquals(d === all[0], seedLine.isUserMessage)
        }
        // Delivered again, it is not a second line.
        all.forEach { it.received.clear() }
        fixture.pump(all, setOf(GroupLightningEvents.NODE_SEED))
        all.forEach { assertEquals(1, fixture.lines(it, ChatMessage.TYPE_LIGHTNING_SEED_SHARED).size) }
    }

    @Test
    fun `a seed that does not produce the node it claims is dropped`() = runBlocking {
        val all = fixture.group()
        fixture.runCeremony(all)
        GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp)
        val message = fixture.outbox(all[0], setOf(GroupLightningEvents.NODE_SEED)).single()
        val body = CommonJson.parseToJsonElement(message.content).jsonObject
        val lying = JsonObject(body + ("nodeId" to JsonPrimitive("02" + "ab".repeat(32))))
        val event = with(fixture) { message.copy(content = lying.toString()).asEvent() }
        all[1].db.marmotInnerEventDao().upsert(message.copy(content = lying.toString()))
        GroupLightningWalletManager.receiveSeed(all[1].db, all[1].room, event, all[1].publicKey, all[1].privateKey)
        assertNull(all[1].db.groupLightningSeedDao().get(fixture.roomId, "02" + "ab".repeat(32)))
        assertTrue(fixture.lines(all[1], ChatMessage.TYPE_LIGHTNING_SEED_SHARED).isEmpty(), "and the room is not told a seed was shared")
    }

    @Test
    fun `a statement that disagrees with the ceremony is not believed`() = runBlocking {
        val all = fixture.group()
        val ceremony = fixture.runCeremony(all)
        val honest = all[0].ceremony(ceremony.id)!!
        val wrong = GroupLightningWalletStatementEvent.Statement(
            chain = fixture.chain, wallet = 0, onchainAccount = 0,
            icebergKey = "02" + "cd".repeat(32), ceremonyId = honest.id, threshold = honest.threshold,
            participants = honest.participants(), nodeId = "02" + "ef".repeat(32), lspNodeId = lsp,
            policy = GroupLightningWalletStatementEvent.Policy.DEFAULT,
        )
        val template = GroupLightningWalletStatementEvent.template(wrong)
        fixture.sign(all, all[0], template.kind, template.tags, template.content)
        all.forEach { assertNull(GroupLightningWalletManager.walletFor(it.db, fixture.roomId, fixture.chain), "device ${it.index} believed a key its ceremony did not make") }
    }

    @Test
    fun `a Lightning wallet is opened on the node's network, and nowhere else`() = runBlocking {
        assertEquals(Chain.Mainnet, GroupLightningRollout.NETWORK)
        assertTrue(GroupLightningRollout.allows(Chain.Mainnet))
        assertTrue(GroupLightningRollout.allows(Chain.Regtest), "regtest, for tests and development")
        assertTrue(!GroupLightningRollout.allows(Chain.Testnet3) && !GroupLightningRollout.allows(Chain.Signet))
        assertEquals(Satoshi(1_000_000), GroupLightningRollout.channelCapFor(Chain.Mainnet))
        assertEquals(null, GroupLightningRollout.channelCapFor(Chain.Regtest))
        val all = fixture.group()
        fixture.runCeremony(all)
        val refused = runCatching { GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, Chain.Testnet3, lsp) }
        assertTrue(refused.isFailure)
    }

    @Test
    fun `members elect themselves, the newest term wins, and only the operator can step down`() = runBlocking {
        val all = fixture.group()
        openLightningWallet(all)
        val first = GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain)
        fixture.signSession(all, all[1], first.id)
        all.forEach { d ->
            val current = assertNotNull(GroupLightningOperatorManager.current(d.db, fixture.roomId, fixture.chain))
            assertEquals(all[1].publicKey, current.publicKey)
            assertEquals(1, current.term)
            // The line says who, which is the one thing it is for.
            val elected = fixture.lines(d, ChatMessage.TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED).single()
            assertEquals("@${ChatMessage.memberName(d.db, all[1].publicKey)} runs the group's Lightning wallet now.", elected.content)
        }
        val second = GroupLightningOperatorManager.nominateSelf(all[2].db, all[2].room, all[2].publicKey, fixture.chain)
        fixture.signSession(all, all[2], second.id)
        all.forEach { d ->
            val current = GroupLightningOperatorManager.current(d.db, fixture.roomId, fixture.chain)!!
            assertEquals(all[2].publicKey, current.publicKey)
            assertEquals(2, current.term)
            assertEquals(all[1].publicKey, current.statement.previous)
        }
        assertTrue(runCatching { GroupLightningOperatorManager.stepDown(all[1].db, all[1].room, all[1].publicKey, fixture.chain) }.isFailure)
        val down = GroupLightningOperatorManager.stepDown(all[2].db, all[2].room, all[2].publicKey, fixture.chain)
        fixture.signSession(all, all[2], down.id)
        all.forEach { d ->
            val current = GroupLightningOperatorManager.current(d.db, fixture.roomId, fixture.chain)!!
            assertNull(current.publicKey)
            assertEquals(3, current.term)
        }
    }

    @Test
    fun `two elections for one term settle the same way on every device`() = runBlocking {
        val all = fixture.group()
        openLightningWallet(all)
        // Both proposed before either is signed: both say term 1.
        val a = GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain)
        val b = GroupLightningOperatorManager.nominateSelf(all[3].db, all[3].room, all[3].publicKey, fixture.chain)
        fixture.signSession(all, all[1], a.id)
        fixture.signSession(all, all[3], b.id)
        val winners = all.map { GroupLightningOperatorManager.current(it.db, fixture.roomId, fixture.chain)!! }
        assertEquals(1, winners.map { it.publicKey }.toSet().size, "every device picks the same operator")
        assertEquals(1L, winners.first().term)
    }

    @Test
    fun `only the elected operator, freshly synced, may start the node`() = runBlocking {
        val all = fixture.group()
        openLightningWallet(all)
        val elected = GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain)
        fixture.signSession(all, all[1], elected.id)
        val now = Clock.System.now()
        assertTrue(GroupLightningOperatorManager.mayRunNode(all[1].db, fixture.roomId, fixture.chain, all[1].publicKey, lastSyncedAt = now - 1.minutes, now = now))
        assertFalse(GroupLightningOperatorManager.mayRunNode(all[1].db, fixture.roomId, fixture.chain, all[1].publicKey, lastSyncedAt = now - 60.minutes, now = now))
        assertFalse(GroupLightningOperatorManager.mayRunNode(all[1].db, fixture.roomId, fixture.chain, all[1].publicKey, lastSyncedAt = null, now = now))
        assertFalse(GroupLightningOperatorManager.mayRunNode(all[2].db, fixture.roomId, fixture.chain, all[2].publicKey, lastSyncedAt = now, now = now))
    }

    @Test
    fun `an operator statement reads back what it says`() {
        val statement = GroupLightningOperatorStatementEvent.Statement(Chain.Testnet3, 2, "ab".repeat(32), 7, "cd".repeat(32))
        val template = GroupLightningOperatorStatementEvent.template(statement)
        assertEquals(statement, GroupLightningOperatorStatementEvent.read(template.tags))
        val nobody = statement.copy(operator = null, previous = null)
        assertEquals(nobody, GroupLightningOperatorStatementEvent.read(GroupLightningOperatorStatementEvent.template(nobody).tags))
        val walletStatement = GroupLightningWalletStatementEvent.Statement(Chain.Testnet3, 0, 0, "02" + "11".repeat(32), "22".repeat(32), 2, listOf("aa".repeat(32), "bb".repeat(32), "cc".repeat(32), "dd".repeat(32)), "03" + "33".repeat(32), "02" + "44".repeat(32), GroupLightningWalletStatementEvent.Policy.DEFAULT)
        assertEquals(walletStatement, GroupLightningWalletStatementEvent.read(GroupLightningWalletStatementEvent.template(walletStatement).tags))
        assertNull(GroupLightningWalletStatementEvent.read(GroupLightningWalletStatementEvent.template(walletStatement.copy(participants = walletStatement.participants.reversed())).tags), "an unsorted participant list is not one")
        Random.nextInt()
    }
}
