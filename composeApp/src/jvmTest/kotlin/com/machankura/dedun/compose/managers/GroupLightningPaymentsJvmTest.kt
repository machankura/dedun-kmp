package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.text.ProposedEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Paying from the group's Lightning wallet (`docs/group-lightning-wallet.md`, Phase 12): the group signs an
 * authorisation, every co-signer reads it as a budget, and the operator's node pays it once. Nothing the
 * group did not sign becomes a budget.
 */
class GroupLightningPaymentsJvmTest {
    private val fixture = GroupLightningFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()
    private val payee = PrivateKey(randomBytes32())
    private val nodes = mutableListOf<FakeGroupNode>()

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    private fun invoice(amountMsat: Long? = 12_000_000, chain: Chain = fixture.chain, signer: PrivateKey = payee, expirySeconds: Long? = null, timestamp: Long = Clock.System.now().epochSeconds) =
        Bolt11Invoice.create(chain, amountMsat?.msat, randomBytes32(), signer, Either.Left("coffee"), CltvExpiryDelta(144), INVOICE_FEATURES, expirySeconds = expirySeconds, timestampSeconds = timestamp)

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupLightningWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        fixture.signSession(all, all[1], GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain).id)
        return all to GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
    }

    /**
     * [pay] as the group signs it: a payout of a member's Lightning balance, the one payment its
     * wallet authorises (`docs/payouts-only.md`, Phase 6). The payment as signed.
     */
    private suspend fun authorise(all: List<GroupLightningFixture.Device>, pay: GroupLightningAuthorisationEvent.Pay, proposer: GroupLightningFixture.Device = all[3]): GroupLightningAuthorisationEvent.Pay {
        val payout = pay.copy(payout = fixture.payoutFor(all, all[0]))
        fixture.signSession(all, proposer, GroupLightningAuthorisations.propose(proposer.db, proposer.room, proposer.publicKey, payout).id)
        return payout
    }

    @Test
    fun `an authorisation reads back as what it was, and refuses to disagree with its invoice`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        val pay = GroupLightningAuthorisations.prepare(wallet, "lightning:" + invoice().write(), null, 1_000_000).getOrThrow()
        assertEquals(12_000_000, pay.amountMsat)
        assertEquals(13_000_000, pay.budgetMsat)
        val tags = GroupLightningAuthorisationEvent.template(pay).tags
        assertEquals(pay, GroupLightningAuthorisationEvent.read(tags))

        fun with(name: String, value: String) = tags.map { if (it[0] == name) arrayOf(name, value) else it }.toTypedArray()
        assertNull(GroupLightningAuthorisationEvent.read(with("amount", "13000000")), "an amount the invoice does not name")
        assertNull(GroupLightningAuthorisationEvent.read(with("hash", "00".repeat(32))), "a hash the invoice does not carry")
        assertNull(GroupLightningAuthorisationEvent.read(with("expires", (Clock.System.now() + 2.hours).epochSeconds.toString())), "an expiry past the invoice's")
        assertNull(GroupLightningAuthorisationEvent.read(with("action", "splice")), "an action this device does not know")

        val summary = ProposedEvent.summarize(Event("", "", 0, GroupLightningAuthorisationEvent.KIND, tags, "", ""))
        assertEquals("Pay 12,000 sats", summary.label)
        assertTrue("coffee" in summary.detail && "1,000 sats" in summary.detail, summary.detail)
    }

    @Test
    fun `what cannot be paid is said before the group is asked`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        fun problem(bolt11: String, amount: Long? = null) =
            (GroupLightningAuthorisations.prepare(wallet, bolt11, amount, 0).exceptionOrNull() as GroupLightningAuthorisations.ProblemException).problem
        assertEquals(GroupLightningAuthorisations.Problem.Unreadable, problem("lnbc1nonsense"))
        assertEquals(GroupLightningAuthorisations.Problem.OtherNetwork, problem(invoice(chain = Chain.Mainnet).write()))
        assertEquals(GroupLightningAuthorisations.Problem.Expired, problem(invoice(expirySeconds = 60, timestamp = Clock.System.now().epochSeconds - 120).write()))
        assertEquals(GroupLightningAuthorisations.Problem.NeedsAmount, problem(invoice(amountMsat = null).write()))
        val nodeKey = GroupLightningCoSigner.keyManagerOf(fr.acinq.bitcoin.ByteVector(wallet.seed!!.seed), fixture.chain).nodeKeys.nodeKey.privateKey
        assertEquals(GroupLightningAuthorisations.Problem.PaysTheGroup, problem(invoice(signer = nodeKey).write()))
        assertEquals(5_000_000, GroupLightningAuthorisations.prepare(wallet, invoice(amountMsat = null).write(), 5_000_000, 0).getOrThrow().amountMsat)
    }

    @Test
    fun `once the group signs, every co-signer has the budget, and an expired one is closed`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        assertTrue(GroupLightningAuthorisations.budgets(all[2].db, wallet).isEmpty(), "nothing is authorised yet")
        val pay = authorise(all, GroupLightningAuthorisations.prepare(wallet, invoice().write(), null, 1_000_000).getOrThrow())
        all.forEach { d ->
            val mine = GroupLightningWalletManager.walletFor(d.db, fixture.roomId, fixture.chain)!!
            assertEquals(mapOf(pay.paymentHash to 13_000_000L), GroupLightningAuthorisations.budgets(d.db, mine), "device ${d.index}")
            assertTrue(GroupLightningAuthorisations.expired(d.db, mine).isEmpty())
            assertEquals(listOf(pay), GroupLightningAuthorisations.toPay(d.db, mine))
        }
        val later = Clock.System.now() + 2.hours
        assertEquals(setOf(pay.paymentHash), GroupLightningAuthorisations.expired(all[2].db, wallet, later))
        assertTrue(GroupLightningAuthorisations.toPay(all[2].db, wallet, later).isEmpty())
        // Still a budget: an HTLC that left before the expiry settles against it.
        assertEquals(13_000_000L, GroupLightningAuthorisations.budgets(all[2].db, wallet)[pay.paymentHash])
    }

    @Test
    fun `a payment the group signed without naming a member is no budget, and nothing the node pays`() = runBlocking<Unit> {
        // Signed by a quorum of older builds, or before the wallet paid only members: the co-signers
        // and the operator's node pass it over. docs/payouts-only.md, D8.
        val (all, wallet) = opened()
        val pay = GroupLightningAuthorisations.prepare(wallet, invoice().write(), null, 1_000_000).getOrThrow()
        fixture.fileSignedAsRoom(all, GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(pay).tags)
        all.forEach { d ->
            val mine = GroupLightningWalletManager.walletFor(d.db, fixture.roomId, fixture.chain)!!
            assertTrue(GroupLightningAuthorisations.budgets(d.db, mine).isEmpty(), "device ${d.index}")
            assertTrue(GroupLightningAuthorisations.toPay(d.db, mine).isEmpty(), "device ${d.index}")
        }
    }

    @Test
    fun `the operator's node pays what the group authorised, once`() = runBlocking<Unit> {
        val (all, _) = opened()
        val operator = all[1]
        val id = randomBytes32().toHex()
        operator.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = operator.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        operator.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = operator.publicKey, publicKey = operator.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
        val node = GroupLightningNode(
            operator.db, operator.room, operator.publicKey, GroupLightningWalletManager.walletFor(operator.db, fixture.roomId, fixture.chain)!!, scope,
            { configuration -> FakeGroupNode(configuration).also { nodes += it } },
            handoverCheck = 10.minutes,
            minimumPool = 0,
        )
        assertIs<GroupLightningNode.State.Running>(node.start())
        node.payAuthorised()
        assertTrue(nodes.single().paid.isEmpty(), "nothing is authorised")

        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        val bolt11 = invoice().write()
        authorise(all, GroupLightningAuthorisations.prepare(wallet, bolt11, null, 1_000_000).getOrThrow())
        node.payAuthorised()
        withTimeout(5.seconds) { while (nodes.single().paid.isEmpty()) delay(20) }
        node.payAuthorised()
        delay(200)
        assertEquals(listOf(bolt11 to 12_000_000L), nodes.single().paid)
    }

    @Test
    fun `a payment leaves as the group signs it, not at the next poll`() = runBlocking<Unit> {
        val (all, _) = opened()
        val operator = all[1]
        val id = randomBytes32().toHex()
        operator.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = operator.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        operator.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = operator.publicKey, publicKey = operator.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
        val node = GroupLightningNode(
            operator.db, operator.room, operator.publicKey, GroupLightningWalletManager.walletFor(operator.db, fixture.roomId, fixture.chain)!!, scope,
            { configuration -> FakeGroupNode(configuration).also { nodes += it } },
            handoverCheck = 10.minutes,
            minimumPool = 0,
        )
        assertIs<GroupLightningNode.State.Running>(node.start())
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        val bolt11 = invoice().write()
        authorise(all, GroupLightningAuthorisations.prepare(wallet, bolt11, null, 1_000_000).getOrThrow())
        withTimeout(5.seconds) { while (nodes.single().paid.isEmpty()) delay(20) }
        delay(200)
        assertEquals(listOf(bolt11 to 12_000_000L), nodes.single().paid)
        node.stop()
    }

    @Test
    fun `a close the group authorises is made once, to the onchain wallet, and a force close by force`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val operator = all[1]
        val id = randomBytes32().toHex()
        operator.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = operator.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        operator.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = operator.publicKey, publicKey = operator.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
        val node = GroupLightningNode(
            operator.db, operator.room, operator.publicKey, GroupLightningWalletManager.walletFor(operator.db, fixture.roomId, fixture.chain)!!, scope,
            { configuration -> FakeGroupNode(configuration).also { nodes += it } },
            handoverCheck = 10.minutes,
            minimumPool = 0,
        )
        assertIs<GroupLightningNode.State.Running>(node.start())
        val one = "c1".repeat(32)
        val two = "c2".repeat(32)
        nodes.single().channels = listOf(one, two).map { com.machankura.dedun.compose.wallet.lightning.WalletWire.ChannelView(it, "Normal", 1_000_000, 1, 1, "dd".repeat(32), 0, 0) }

        fixture.signSession(all, all[3], GroupLightningAuthorisations.proposeChannelAction(all[3].db, all[3].room, all[3].publicKey, wallet, one, force = false).id)
        fixture.signSession(all, all[2], GroupLightningAuthorisations.proposeChannelAction(all[2].db, all[2].room, all[2].publicKey, wallet, two, force = true).id)
        node.closeAuthorised()
        node.closeAuthorised()
        assertEquals(listOf(one to GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript), nodes.single().closed)
        assertEquals(listOf(two), nodes.single().forceClosed)

        val actions = GroupLightningAuthorisations.channelActions(all[0].db, fixture.roomId, wallet.statement)
        // Both signed in the same second, so in no particular order.
        assertEquals(mapOf(one to GroupLightningAuthorisationEvent.CLOSE, two to GroupLightningAuthorisationEvent.FORCE_CLOSE), actions.associate { it.channelId to it.action })
        val forced = actions.single { it.action == GroupLightningAuthorisationEvent.FORCE_CLOSE }
        val summary = ProposedEvent.summarize(Event("", "", 0, GroupLightningAuthorisationEvent.KIND, GroupLightningAuthorisationEvent.template(forced).tags, "", ""))
        assertEquals("Force-close a Lightning channel", summary.label)
        assertTrue(GroupLightningAuthorisations.channelActions(all[0].db, fixture.roomId, wallet.statement, Clock.System.now() + 4.days).isEmpty(), "both have expired")
        assertNull(GroupLightningAuthorisationEvent.read(GroupLightningAuthorisationEvent.template(forced).tags), "a close is not a payment")
    }
}
