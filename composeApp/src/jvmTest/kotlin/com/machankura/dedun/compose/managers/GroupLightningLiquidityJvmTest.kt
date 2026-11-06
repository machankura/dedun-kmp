package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.Lightning.randomBytes32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The group deciding to buy inbound liquidity (`docs/group-lightning-liquidity.md`, Phase 4): a
 * `buy-liquidity` authorisation reads back as what it was, an older build reads it as nothing, the
 * proposal says what a signer weighs, and a purchase past the mainnet cap is refused before anyone is asked.
 */
abstract class GroupLightningLiquidityCases(chain: Chain) {
    private val fixture = GroupLightningFixture(chain = chain)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()
    private val channelId = "ab".repeat(32)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = mutableListOf<FakeGroupNode>()

    @AfterTest
    fun close() {
        scope.cancelAndWait()
        fixture.close()
    }

    /** The operator's node, on a stand-in peer holding one channel of [capacitySat], [live] funding outputs live. */
    private suspend fun runningNode(all: List<GroupLightningFixture.Device>, live: Int = 1): GroupLightningNode {
        val operator = all[1]
        val id = randomBytes32().toHex()
        operator.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = operator.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        operator.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = operator.publicKey, publicKey = operator.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
        val node = GroupLightningNode(
            operator.db, operator.room, operator.publicKey, GroupLightningWalletManager.walletFor(operator.db, fixture.roomId, fixture.chain)!!, scope,
            { configuration -> FakeGroupNode(configuration).also { fake ->
                fake.channels = listOf(WalletWire.ChannelView(channelId, "Normal", 25_000, 21_900_000, 3_100_000, "ab".repeat(32), 0, 3, liveFundingOutputs = live))
                fake.rates = WalletWire.FundingRates(listOf(WalletWire.FundingRate(10_000, 10_000_000, 400, 100, 100, 1_000)), listOf("from_channel_balance"))
                nodes += fake
            } },
            handoverCheck = 10.minutes,
            minimumPool = 0,
        )
        assertIs<GroupLightningNode.State.Running>(node.start())
        return node
    }

    /**
     * A purchase the group signed before its wallet paid only members: every signer refuses a new
     * one now (`docs/payouts-only.md`, D9), so it is filed as signed, and the machinery that makes an
     * old one is what is tested.
     */
    private suspend fun authorise(all: List<GroupLightningFixture.Device>, wallet: GroupLightningWalletManager.Wallet, maxFeeSat: Long = 4_000): GroupLightningAuthorisationEvent.BuyLiquidity {
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, 25_000, 200_000, maxFeeSat).getOrThrow()
        val template = GroupLightningAuthorisationEvent.template(buy)
        fixture.fileSignedAsRoom(all, template.kind, template.tags, template.content)
        return buy
    }

    private suspend fun reported(all: List<GroupLightningFixture.Device>, node: GroupLightningNode): WalletWire.Snapshot {
        node.report(term = 1, force = true)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        return GroupLightningReports.latest(all[3].db, GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0))!!.snapshot
    }

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupLightningWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        fixture.signSession(all, all[1], GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain).id)
        return all to GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
    }

    @Test
    fun `a purchase reads back as what it was, and nothing else reads it`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, capacitySat = 25_000, amountSat = 500_000, maxFeeSat = 7_500).getOrThrow()
        assertEquals(500_000, buy.amountSat)
        assertEquals(7_500, buy.maxFeeSat)
        assertTrue(buy.expiresAt > Clock.System.now().epochSeconds)
        val tags = GroupLightningAuthorisationEvent.template(buy).tags
        assertEquals(buy, GroupLightningAuthorisationEvent.readBuyLiquidity(tags))
        // An older build, which knows only payments and channel actions, reads it as no authorisation at all.
        assertNull(GroupLightningAuthorisationEvent.read(tags))
        assertNull(GroupLightningAuthorisationEvent.readChannelAction(tags))
        // And the purchase reader reads nothing else.
        val close = GroupLightningAuthorisationEvent.ChannelAction(GroupLightningAuthorisationEvent.CLOSE, fixture.chain, 0, channelId, buy.expiresAt)
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(GroupLightningAuthorisationEvent.template(close).tags))

        fun with(name: String, value: String) = tags.map { if (it[0] == name) arrayOf(name, value) else it }.toTypedArray()
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("channel", "cd".repeat(32))), "a channel the identifier does not name")
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("id", "other")), "an id the identifier does not name")
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("amount", "0")), "nothing to buy")
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("maxfee", "-1")), "a negative fee cap")

        // Two purchases for one channel are two events, not one replacing the other.
        val again = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, 25_000, 500_000, 7_500).getOrThrow()
        assertNotEquals(GroupLightningAuthorisationEvent.template(buy).tags.first { it[0] == "d" }[1], GroupLightningAuthorisationEvent.template(again).tags.first { it[0] == "d" }[1])

        val summary = ProposedEvent.summarize(Event("", "", 0, GroupLightningAuthorisationEvent.KIND, tags, "", ""))
        assertEquals("Add 500,000 sats of inbound liquidity", summary.label)
        assertTrue("7,500 sats" in summary.detail && "after a year" in summary.detail, summary.detail)
    }

    @Test
    fun `a purchase proposed now is refused by every signer, and nobody reads one`() = runBlocking<Unit> {
        // It pays the LSP and no member: docs/payouts-only.md, D9.
        val (all, wallet) = opened()
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, 25_000, 200_000, 4_000).getOrThrow()
        fixture.signSession(all, all[2], GroupLightningAuthorisations.proposeBuyLiquidity(all[2].db, all[2].room, all[2].publicKey, buy).id)
        all.forEach { d -> assertTrue(GroupLightningAuthorisations.liquidityPurchases(d.db, wallet).isEmpty(), "device ${d.index}") }
    }

    @Test
    fun `once the group signs, every member reads the open purchase, until it expires`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val buy = authorise(all, wallet)
        all.forEach { d ->
            val mine = GroupLightningWalletManager.walletFor(d.db, fixture.roomId, fixture.chain)!!
            assertEquals(listOf(buy), GroupLightningAuthorisations.liquidityPurchases(d.db, mine), "device ${d.index}")
            assertTrue(GroupLightningAuthorisations.liquidityPurchases(d.db, mine, Clock.System.now() + 4.days).isEmpty(), "device ${d.index}: expired")
            // A purchase is not a payment budget, nor a close.
            assertTrue(GroupLightningAuthorisations.budgets(d.db, mine).isEmpty())
            assertTrue(GroupLightningAuthorisations.channelActions(d.db, fixture.roomId, mine.statement).isEmpty())
        }
    }

    @Test
    fun `the operator's node buys what the group authorised, once, and every member hears how it went`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val node = runningNode(all)
        node.buyAuthorised()
        assertTrue(nodes.single().bought.isEmpty(), "nothing is authorised")
        val buy = authorise(all, wallet)
        node.buyAuthorised()
        withTimeout(5.seconds) { while (nodes.single().bought.isEmpty()) delay(20) }
        node.buyAuthorised()
        delay(200)
        assertEquals(listOf(Triple(buy.id, 200_000L, 4_000L)), nodes.single().bought, "bought once, for the amount and cap the group signed")

        val snapshot = reported(all, node)
        assertEquals(listOf(WalletWire.LiquidityPurchase(buy.id, channelId, 200_000, WalletWire.PENDING, "cd".repeat(32), 2_521)), snapshot.purchases)
        assertEquals(nodes.single().rates, snapshot.fundingRates, "what the LSP sells, for a member to price from")
        assertEquals(2L, snapshot.feeratePerByte)
        // Once the channel is on the splice's output alone, the purchase reads as locked.
        nodes.single().channels = nodes.single().channels.map { it.copy(fundingTxId = "cd".repeat(32), capacitySat = 224_779) }
        assertEquals(WalletWire.LOCKED, reported(all, node).purchases.single().state)
        node.stop()
    }

    @Test
    fun `a purchase that would cost more than the cap is never sent, and is not tried again`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val node = runningNode(all)
        nodes.single().purchaseFee = 9_000
        val buy = authorise(all, wallet)
        node.buyAuthorised()
        val failed = withTimeout(5.seconds) {
            var p: WalletWire.LiquidityPurchase? = null
            while (p?.state != WalletWire.FAILED) { p = reported(all, node).purchases.singleOrNull(); delay(20) }
            p
        }
        assertEquals(buy.id, failed.id)
        assertTrue("over the 4000 the group agreed to" in failed.reason!!, failed.reason)
        nodes.single().purchaseFee = 2_521
        node.buyAuthorised()
        delay(200)
        assertTrue(nodes.single().bought.isEmpty(), "a failed purchase waits for the group to ask again")
        node.stop()
    }

    @Test
    fun `while an earlier splice has not locked, the purchase waits, and is made once it has`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val node = runningNode(all, live = 2)
        val buy = authorise(all, wallet)
        node.buyAuthorised()
        delay(200)
        assertTrue(nodes.single().bought.isEmpty())
        assertEquals(WalletWire.WAITING, reported(all, node).purchases.single().state)
        nodes.single().channels = nodes.single().channels.map { it.copy(liveFundingOutputs = 1) }
        node.buyAuthorised()
        withTimeout(5.seconds) { while (nodes.single().bought.isEmpty()) delay(20) }
        assertEquals(buy.id, nodes.single().bought.single().first)
        node.stop()
    }

    @Test
    fun `a snapshot from a build before purchases still reads, and one with them round-trips`() {
        val old = """{"wallet":"w","term":1,"nodeId":"n","sentAt":1,"channels":[{"channelId":"c","state":"Normal","capacitySat":1,"oursMsat":1,"theirsMsat":0,"fundingTxId":"f","fundingIndex":0,"commitIndex":0}],"payments":[{"id":"p","incoming":true,"amountMsat":1,"feesMsat":0,"succeeded":true}]}"""
        val read = CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), old)
        assertEquals(1, read.channels.single().liveFundingOutputs)
        assertEquals(WalletWire.PAYMENT, read.payments.single().kind)
        assertTrue(read.purchases.isEmpty() && read.fundingRates == null)
        val full = read.copy(
            fundingRates = WalletWire.FundingRates(listOf(WalletWire.FundingRate(1, 2, 3, 4, 5, 6)), listOf("from_channel_balance")),
            feeratePerByte = 3,
            purchases = listOf(WalletWire.LiquidityPurchase("i", "c", 200_000, WalletWire.FAILED, reason = "r")),
            payments = listOf(WalletWire.PaymentView("liquidity:t", false, 2_521_000, 2_521_000, succeeded = true, kind = WalletWire.LIQUIDITY, liquiditySat = 200_000)),
        )
        assertEquals(full, CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), CommonJson.encodeToString(WalletWire.Snapshot.serializer(), full)))
    }

    @Test
    fun `a purchase past what a channel may hold is refused before anyone is asked`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        fun problem(capacity: Long, amount: Long) =
            (GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, capacity, amount, 1_000).exceptionOrNull() as? GroupLightningAuthorisations.LiquidityProblemException)?.problem
        assertEquals(GroupLightningAuthorisations.LiquidityProblem.NothingToBuy, problem(25_000, 0))
        val cap = GroupLightningRollout.channelCapFor(fixture.chain)
        if (cap == null) {
            assertNull(problem(25_000, 10_000_000), "no cap off mainnet")
        } else {
            assertNull(problem(25_000, cap.sat - 25_000), "up to the cap exactly")
            assertIs<GroupLightningAuthorisations.LiquidityProblem.OverTheCap>(problem(25_000, cap.sat - 24_999))
        }
    }
}

class GroupLightningLiquidityJvmTest : GroupLightningLiquidityCases(Chain.Regtest)

class GroupLightningLiquidityMainnetJvmTest : GroupLightningLiquidityCases(Chain.Mainnet)
