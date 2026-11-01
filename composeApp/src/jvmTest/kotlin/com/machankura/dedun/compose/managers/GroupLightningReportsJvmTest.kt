package com.machankura.dedun.compose.managers

import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.IcebergChannelLedger
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.CoSignerPolicy
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferManager
import fr.acinq.lightning.utils.msat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What the operator's node tells the room, and what each member makes of it
 * (`docs/group-lightning-wallet.md`, Phase 10): a snapshot is believed only from the operator of the term
 * it names, every member files it with each channel checked against its own co-signing ledger, and the
 * round-one assignments it carries reach whoever operates next. And an invoice a member asks for (Phase 11)
 * is made by the operator's node and believed only if it pays the group's node.
 */
class GroupLightningReportsJvmTest {
    private val fixture = GroupLightningFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()
    private val key = GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
    private val nodes = mutableListOf<FakeGroupNode>()

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    private val channel = WalletWire.ChannelView("cc".repeat(32), "Normal", 1_000_000, 400_000_000, 600_000_000, "dd".repeat(32), 0, 3)
    private val payment = WalletWire.PaymentView("p1", incoming = true, amountMsat = 21_000_000, feesMsat = 0, paymentHash = "ee".repeat(32), description = "lunch", completedAt = 1_700_000_000_000, succeeded = true)

    /** Four devices with a Lightning wallet open, [operator] elected and running the group's node. */
    private suspend fun running(operator: Int = 1): Pair<List<GroupLightningFixture.Device>, GroupLightningNode> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        val who = all[operator]
        fixture.signSession(all, who, GroupLightningOperatorManager.nominateSelf(who.db, who.room, who.publicKey, fixture.chain).id)
        val id = randomBytes32().toHex()
        who.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = who.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        who.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = who.publicKey, publicKey = who.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
        val node = GroupLightningNode(
            who.db, who.room, who.publicKey, GroupLightningWalletManager.walletFor(who.db, fixture.roomId, fixture.chain)!!, scope,
            { configuration -> FakeGroupNode(configuration).also { nodes += it } },
            // The tests drive reporting themselves.
            handoverCheck = 10.minutes,
            minimumPool = 0,
        )
        assertIs<GroupLightningNode.State.Running>(node.start())
        return all to node
    }

    private fun snapshotEvent(from: GroupLightningFixture.Device, snapshot: WalletWire.Snapshot) =
        Event(randomBytes32().toHex(), from.publicKey, Clock.System.now().epochSeconds, GroupLightningEvents.SNAPSHOT, emptyArray(), CommonJson.encodeToString(WalletWire.Snapshot.serializer(), snapshot), "")

    @Test
    fun `the operator's snapshot reaches every member, checked against what each one signed`() = runBlocking<Unit> {
        val (all, node) = running()
        nodes.single().channels = listOf(channel)
        nodes.single().payments += payment
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))

        // One member signed the operator's figure, one signed another, one signed nothing for the channel.
        fun ledger(oursMsat: Long) = IcebergChannelLedger(key, channel.channelId, "${channel.fundingTxId}:0", lsp, GroupLightningCoSigner.store(CoSignerPolicy.Ledger(3, "tx", oursMsat, emptyMap(), emptyMap(), 0)))
        all[2].db.icebergCoSigningDao().upsertChannelLedger(ledger(400_000_000))
        all[3].db.icebergCoSigningDao().upsertChannelLedger(ledger(390_000_000))

        val reports = all.map { assertNotNull(GroupLightningReports.latest(it.db, key), "device ${it.index} filed the snapshot") }
        reports.forEach {
            assertEquals(1, it.snapshot.term)
            assertEquals(400_000_000, it.snapshot.spendableMsat)
            assertEquals(600_000_000, it.snapshot.inboundMsat)
        }
        assertEquals(listOf(null, null, true, false), reports.map { it.checks.single().agrees })
        all.forEach { d ->
            val filed = d.db.groupLightningRecordsDao().observePayments(key).first()
            assertEquals(listOf("lunch"), filed.map { it.description }, "device ${d.index} kept the payment")
        }
    }

    @Test
    fun `nothing changed is not sent again until the snapshot is due`() = runBlocking<Unit> {
        val (all, node) = running()
        val sent = { runBlocking { fixture.outbox(all[1], setOf(GroupLightningEvents.SNAPSHOT)).size } }
        assertEquals(1, sent(), "starting sends one")
        node.report(term = 1)
        assertEquals(1, sent())
        nodes.single().channels = listOf(channel)
        node.report(term = 1)
        assertEquals(2, sent())
    }

    @Test
    fun `only the operator of the term a snapshot names is believed`() = runBlocking<Unit> {
        val (all, _) = running()
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        val forged = WalletWire.Snapshot(key, 1, wallet.statement.nodeId, Clock.System.now().epochSeconds + 60, listOf(channel.copy(oursMsat = 999_000_000)), emptyList())
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[2], forged))
        assertEquals(0, GroupLightningReports.latest(all[3].db, key)!!.snapshot.spendableMsat, "the forged one did not replace the operator's")

        val otherNode = forged.copy(nodeId = "02" + "77".repeat(32))
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[1], otherNode))
        assertEquals(0, GroupLightningReports.latest(all[3].db, key)!!.snapshot.spendableMsat, "nor one about some other node")

        // The operator's own, carrying the labels it handed out, files them for whoever operates next.
        val handover = forged.copy(assignments = mapOf("verification:x:y:0" to 5L))
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[1], handover))
        assertEquals(999_000_000, GroupLightningReports.latest(all[3].db, key)!!.snapshot.spendableMsat)
        val assignment = assertNotNull(all[3].db.icebergCoSigningDao().assignmentFor(key, "verification:x:y:0"))
        assertEquals(5L, assignment.counter)
        assertEquals(GroupLightningCoSigner.poolLabel(wallet.statement.icebergKey, 5).toHex(), assignment.label)
        assertNull(all[2].db.icebergCoSigningDao().assignmentFor(key, "verification:x:y:0"))
    }

    @Test
    fun `a member asks for an invoice, and the operator's node makes one that pays the group's node`() = runBlocking<Unit> {
        val (all, node) = running()
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        fixture.openDeposits(all, all[3])
        val requestId = GroupLightningReports.requestInvoice(all[3].db, all[3].room, all[3].publicKey, wallet, 21_000_000, "lunch")
        fixture.pump(all, setOf(GroupLightningEvents.INVOICE_REQUEST))
        node.answerInvoiceRequests()
        node.answerInvoiceRequests()
        assertEquals(1, nodes.single().invoices.size, "a request is answered once")
        fixture.pump(all, setOf(GroupLightningEvents.INVOICE))

        all.forEach { d ->
            val row = assertNotNull(d.db.groupLightningRecordsDao().invoice(key, requestId), "device ${d.index} has the invoice")
            val invoice = Bolt11Invoice.read(row.bolt11).get()
            assertEquals(wallet.statement.nodeId, invoice.nodeId.toString())
            assertEquals(21_000_000.msat, invoice.amount)
            assertEquals("lunch", invoice.description)

            // Who asked, for how much, and that the node answered, on every device.
            val asked = fixture.lines(d, ChatMessage.TYPE_LIGHTNING_INVOICE_REQUESTED).single()
            assertEquals(all[3].publicKey, asked.senderPublicKey)
            assertEquals("asked the operator's node for an invoice of 21,000 sats, for \u201clunch\u201d.", asked.content)
            val issued = fixture.lines(d, ChatMessage.TYPE_LIGHTNING_INVOICE_ISSUED).single()
            assertEquals("The operator's node made @${ChatMessage.memberName(d.db, all[3].publicKey)}'s invoice.", issued.content)
        }
    }

    @Test
    fun `a request is answered as it arrives, not at the next poll`() = runBlocking<Unit> {
        // The poll is ten minutes off: only the node's watch on the room can answer in time.
        val (all, _) = running()
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        fixture.openDeposits(all, all[3])
        GroupLightningReports.requestInvoice(all[3].db, all[3].room, all[3].publicKey, wallet, 21_000_000, "lunch")
        fixture.pump(all, setOf(GroupLightningEvents.INVOICE_REQUEST))
        withTimeout(5.seconds) { while (nodes.single().invoices.isEmpty()) delay(20) }
        delay(200)
        assertEquals(1, nodes.single().invoices.size, "a request is answered once")
    }

    @Test
    fun `an invoice is issued only against the requester's own depositor statement, and says whose it is`() = runBlocking<Unit> {
        val (all, node) = running()
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        assertFailsWith<IllegalStateException>("no statement, no request") {
            GroupLightningReports.requestInvoice(all[3].db, all[3].room, all[3].publicKey, wallet, 21_000_000, "lunch")
        }

        // A request naming somebody else's statement, as an older or a dishonest device could send it.
        fixture.openDeposits(all, all[2])
        val someoneElses = Depositors.statementOf(all[3].db, fixture.roomId, fixture.chain, all[2].publicKey)!!.event.id
        GroupLightningWalletManager.queue(
            all[3].db, all[3].room, all[3].publicKey, GroupLightningEvents.INVOICE_REQUEST,
            CommonJson.encodeToString(WalletWire.InvoiceRequest.serializer(), WalletWire.InvoiceRequest(key, "borrowed", 1_000_000, "x", depositor = someoneElses)),
        )
        fixture.pump(all, setOf(GroupLightningEvents.INVOICE_REQUEST))
        node.answerInvoiceRequests()
        assertTrue(nodes.single().invoices.isEmpty(), "a request under another member's statement is never answered")

        fixture.openDeposits(all, all[3])
        val requestId = GroupLightningReports.requestInvoice(all[3].db, all[3].room, all[3].publicKey, wallet, 21_000_000, "lunch")
        fixture.pump(all, setOf(GroupLightningEvents.INVOICE_REQUEST))
        node.answerInvoiceRequests()
        fixture.pump(all, setOf(GroupLightningEvents.INVOICE))
        all.forEach { d ->
            val row = assertNotNull(d.db.groupLightningRecordsDao().invoice(key, requestId))
            assertEquals(all[3].publicKey, row.requester, "device ${d.index} knows whose invoice it is")
            assertEquals(Bolt11Invoice.read(row.bolt11).get().paymentHash.toHex(), row.paymentHash)
        }

        // Paid: the snapshot joins the payment to the request it paid, for a device that missed it.
        val hash = nodes.single().invoices.single().paymentHash.toHex()
        nodes.single().payments += WalletWire.PaymentView("in-deposit", incoming = true, amountMsat = 20_000_000, feesMsat = 1_000_000, paymentHash = hash, completedAt = 1, succeeded = true)
        node.report(term = 1, force = true)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        assertEquals(requestId, GroupLightningReports.latest(all[0].db, key)!!.snapshot.payments.single { it.id == "in-deposit" }.requestId)
    }

    @Test
    fun `a reply that is not the invoice its request asked for is not filed`() = runBlocking<Unit> {
        val (all, _) = running()
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        fixture.openDeposits(all, all[3])
        val requestId = GroupLightningReports.requestInvoice(all[3].db, all[3].room, all[3].publicKey, wallet, 21_000_000, "lunch")
        val nodeKey = GroupLightningCoSigner.keyManagerOf(ByteVector(wallet.seed!!.seed), fixture.chain).nodeKeys.nodeKey.privateKey
        fun invoice(amount: Long, description: String) = Bolt11Invoice.create(fixture.chain, amount.msat, randomBytes32(), nodeKey, Either.Left(description), CltvExpiryDelta(144), INVOICE_FEATURES)
        fun reply(bolt11: Bolt11Invoice, requester: String? = all[3].publicKey, hash: String? = bolt11.paymentHash.toHex()) = Event(
            randomBytes32().toHex(), all[1].publicKey, Clock.System.now().epochSeconds, GroupLightningEvents.INVOICE, emptyArray(),
            CommonJson.encodeToString(WalletWire.InvoiceReply.serializer(), WalletWire.InvoiceReply(key, requestId, bolt11.write(), requester, hash)), "",
        )
        val good = invoice(21_000_000, "lunch")
        listOf(
            reply(invoice(1_000, "lunch")),
            reply(invoice(21_000_000, "dinner")),
            reply(good, requester = all[2].publicKey),
            reply(good, hash = "ab".repeat(32)),
        ).forEach { GroupLightningReports.process(all[3].db, all[3].room, it) }
        assertNull(all[3].db.groupLightningRecordsDao().invoice(key, requestId), "another amount, description, requester or hash")

        GroupLightningReports.process(all[3].db, all[3].room, reply(good))
        assertEquals(all[3].publicKey, all[3].db.groupLightningRecordsDao().invoice(key, requestId)?.requester)
    }

    @Test
    fun `an invoice is believed only from the operator, and only if it pays the group's node`() = runBlocking<Unit> {
        val (all, _) = running()
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        val nodeKey = GroupLightningCoSigner.keyManagerOf(ByteVector(wallet.seed!!.seed), fixture.chain).nodeKeys.nodeKey.privateKey
        fun invoice(signer: PrivateKey) = Bolt11Invoice.create(fixture.chain, 1_000.msat, randomBytes32(), signer, Either.Left("x"), CltvExpiryDelta(144), INVOICE_FEATURES).write()
        fun reply(from: GroupLightningFixture.Device, requestId: String, bolt11: String) = Event(
            randomBytes32().toHex(), from.publicKey, Clock.System.now().epochSeconds, GroupLightningEvents.INVOICE, emptyArray(),
            CommonJson.encodeToString(WalletWire.InvoiceReply.serializer(), WalletWire.InvoiceReply(key, requestId, bolt11)), "",
        )
        GroupLightningReports.process(all[3].db, all[3].room, reply(all[1], "elsewhere", invoice(PrivateKey(randomBytes32()))))
        GroupLightningReports.process(all[3].db, all[3].room, reply(all[2], "not-the-operator", invoice(nodeKey)))
        GroupLightningReports.process(all[3].db, all[3].room, reply(all[1], "good", invoice(nodeKey)))
        assertNull(all[3].db.groupLightningRecordsDao().invoice(key, "elsewhere"), "an invoice paying another node")
        assertNull(all[3].db.groupLightningRecordsDao().invoice(key, "not-the-operator"), "an invoice from a member who is not the operator")
        assertNotNull(all[3].db.groupLightningRecordsDao().invoice(key, "good"))
    }

    @Test
    fun `the node's offer reaches every member, and only the one its seed derives is kept`() = runBlocking<Unit> {
        val (all, _) = running()
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        val expected = GroupLightningReports.offerOf(wallet)
        all.forEach { assertEquals(expected, GroupLightningReports.latest(it.db, key)!!.snapshot.offer, "device ${it.index}") }

        val someoneElse = OfferManager.deterministicOffer(fixture.chain.chainHash, PrivateKey(randomBytes32()), PublicKey.fromHex(lsp), null, null, null).offer.encode()
        val lying = WalletWire.Snapshot(key, 1, wallet.statement.nodeId, Clock.System.now().epochSeconds + 60, emptyList(), emptyList(), offer = someoneElse)
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[1], lying))
        assertNull(GroupLightningReports.latest(all[3].db, key)!!.snapshot.offer, "an offer that pays some other node is dropped")
    }

    /**
     * What the snapshots say happened, said once per fact however many snapshots repeat it
     * (docs/group-lightning-transcript.md, Phase 5): a channel opening and closing, a payment
     * received, sent and failed -- on every device, the operator's own included.
     */
    @Test
    fun `what the snapshots say happened is said once, on every device`() = runBlocking<Unit> {
        val (all, node) = running()
        // As lightning-kmp reports them: an outgoing amount includes its fee, and a channel's close is
        // an outgoing payment with no payment hash.
        val sent = WalletWire.PaymentView("p2", incoming = false, amountMsat = 5_001_500, feesMsat = 1_500, paymentHash = "ab".repeat(32), completedAt = 1_700_000_100_000, succeeded = true)
        val close = WalletWire.PaymentView("p5", incoming = false, amountMsat = 400_000_000, feesMsat = 0, completedAt = 1_700_000_300_000, succeeded = true)
        val failed = WalletWire.PaymentView("p3", incoming = false, amountMsat = 7_000_000, feesMsat = 0, paymentHash = "cd".repeat(32), completedAt = 1_700_000_200_000, succeeded = false)
        val pending = WalletWire.PaymentView("p4", incoming = true, amountMsat = 1_000_000, feesMsat = 0, completedAt = null, succeeded = false)
        nodes.single().channels = listOf(channel)
        nodes.single().payments += listOf(payment, sent, failed, pending, close)
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        // The same facts again, and then the channel closing.
        nodes.single().channels = listOf(channel.copy(commitIndex = 4))
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        nodes.single().channels = listOf(channel.copy(state = "Closing", commitIndex = 5))
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))

        all.forEach { d ->
            fun one(type: String) = runBlocking { fixture.lines(d, type) }.also { assertEquals(1, it.size, "device ${d.index}: $type") }.single().content
            assertEquals("The group's Lightning node opened a channel of 1,000,000 sats.", one(ChatMessage.TYPE_LIGHTNING_CHANNEL_OPENED))
            assertEquals("A Lightning channel closed. Its 400,000 sats go to the onchain wallet.", one(ChatMessage.TYPE_LIGHTNING_CHANNEL_CLOSED))
            assertEquals("The group's Lightning wallet received 21,000 sats, for \u201clunch\u201d.", one(ChatMessage.TYPE_LIGHTNING_PAYMENT_RECEIVED))
            assertEquals("The group's Lightning wallet sent 5,000 sats, with 2 sats in fees.", one(ChatMessage.TYPE_LIGHTNING_PAYMENT_SENT))
            assertEquals("A Lightning payment of 7,000 sats failed. Nothing left the wallet.", one(ChatMessage.TYPE_LIGHTNING_PAYMENT_FAILED))
            assertEquals(
                Instant.fromEpochMilliseconds(payment.completedAt!!),
                fixture.lines(d, ChatMessage.TYPE_LIGHTNING_PAYMENT_RECEIVED).single().createdAt,
                "a payment's line sits where the payment happened",
            )
        }
    }

    @Test
    fun `a channel a newer snapshot no longer holds has closed`() = runBlocking<Unit> {
        val (all, node) = running()
        nodes.single().channels = listOf(channel)
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        // A payment beside it, so this snapshot is not byte for byte the empty one the node
        // started with, which in the same second would be the same event.
        nodes.single().channels = emptyList()
        nodes.single().payments += payment
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        all.forEach { d -> assertEquals(1, fixture.lines(d, ChatMessage.TYPE_LIGHTNING_CHANNEL_CLOSED).size, "device ${d.index}") }
    }
}
