package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.bitcoin.utils.Either
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every device counts a transfer the group signed, and nothing counts it as a deposit: `docs/member-transfers.md`, Phase 2.
 * Nothing in this phase proposes one, so each is filed as the room signed it.
 */
class MemberTransferBalancesJvmTest {
    private val fixture = GroupLightningFixture()
    private val now get() = Clock.System.now().epochSeconds

    @AfterTest
    fun close() = fixture.close()

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupWavelengthWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, "02" + "ab".repeat(32))
        fixture.signSession(all, all[0], session.id)
        return all to assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
    }

    private fun request(from: GroupLightningFixture.Device, to: GroupLightningFixture.Device, sat: Long, rail: Rail = Rail.WAVELENGTH, askedAt: Long = now): TransferRequestEvent.Request {
        val signed = TransferRequestEvent.build(PrivateKey(ByteVector32(from.privateKey)), fixture.roomId, fixture.chain, rail, to.publicKey, sat, askedAt + 86_400, "groceries", askedAt)
        return TransferRequestEvent.verified(signed) as TransferRequestEvent.Request
    }

    /** [from]'s transfer of [sat] to [to], signed by the room and filed on every device. */
    private suspend fun transferred(all: List<GroupLightningFixture.Device>, from: GroupLightningFixture.Device, to: GroupLightningFixture.Device, sat: Long, request: TransferRequestEvent.Request = request(from, to, sat), createdAt: Long = now): Event {
        val template = MemberTransferEvent.template(request, createdAt = createdAt)
        return fixture.fileSignedAsRoom(all, MemberTransferEvent.KIND, template.tags, createdAt = createdAt)
    }

    private fun invoice(sat: Long, metadata: String): String = Bolt11Invoice.create(
        fixture.chain, (sat * 1000).msat, randomBytes32(), PrivateKey(ByteVector32("11".repeat(32))),
        Either.Right(ByteVector32(fr.acinq.bitcoin.Crypto.sha256(metadata.encodeToByteArray()))), CltvExpiryDelta(18),
        Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), randomBytes32(),
    ).write()

    private suspend fun refusedPayout(all: List<GroupLightningFixture.Device>, device: GroupLightningFixture.Device, payout: GroupWavelengthPaymentEvent.Payout, sat: Long): String? {
        val metadata = fixture.metadataOf(all, payout.member)
        val payment = GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(sat, metadata), 50, 500, now + 3600, payout, metadata)
        val template = GroupWavelengthPaymentEvent.template(payment)
        return GroupWavelengthPayouts.refuse(device.db, fixture.roomId, Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, ""))
    }

    @Test
    fun `a signed transfer moves a balance on every device, and no deposit`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        fixture.payable(all, all[2], wallet, 10_000)
        val talliedBefore = DepositLedger.tallies(all[3].db, fixture.roomId, fixture.chain)
        val depositsBefore = DepositLedger.depositsIn(all[3].db, fixture.roomId, fixture.chain)

        transferred(all, all[1], all[2], 20_000)

        all.forEach { device ->
            val balances = GroupWavelengthPayouts.balancesIn(device.db, fixture.roomId, fixture.chain)
            assertEquals(10_000, balances.getValue(all[1].publicKey.lowercase()).availableSat, "device ${device.index}")
            assertEquals(30_000, balances.getValue(all[2].publicKey.lowercase()).availableSat, "device ${device.index}")
            assertEquals(20_000, balances.getValue(all[1].publicKey.lowercase()).givenSat)
            assertEquals(20_000, balances.getValue(all[2].publicKey.lowercase()).receivedSat)
        }
        // T7: who put money in is unchanged.
        assertEquals(talliedBefore, DepositLedger.tallies(all[3].db, fixture.roomId, fixture.chain))
        assertEquals(depositsBefore, DepositLedger.depositsIn(all[3].db, fixture.roomId, fixture.chain))
    }

    @Test
    fun `a member with nothing but what they were given has a balance`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        transferred(all, all[1], all[3], 5_000)
        val balances = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain)
        assertEquals(5_000, balances.getValue(all[3].publicKey.lowercase()).availableSat)
        assertEquals(0, balances.getValue(all[3].publicKey.lowercase()).depositedSat)
    }

    @Test
    fun `a shortfall is shared by the receiver as it would have been by the giver, and the total held doesn't move`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        fixture.payable(all, all[2], wallet, 10_000)
        // The wallet holds 36,000 of the 40,000 the ledger promises: 90%.
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, wallet.statement.wallet)
        all[0].db.groupArkDao().upsertVtxo(GroupArkVtxo(key, "coin1:0", 26_000, settled = true))
        val before = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).values.sumOf { it.heldSat }

        transferred(all, all[1], all[2], 10_000)

        val after = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain)
        assertEquals(18_000, after.getValue(all[1].publicKey.lowercase()).heldSat)
        assertEquals(18_000, after.getValue(all[2].publicKey.lowercase()).heldSat)
        assertEquals(before, after.values.sumOf { it.heldSat })
    }

    @Test
    fun `money given away can't be paid out to its giver, and the receiver can be paid it`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val alice = fixture.payable(all, all[1], wallet, 10_000)
        val bob = fixture.payable(all, all[2], wallet, 10_000)
        transferred(all, all[1], all[2], 6_000)

        assertTrue("more than the 4000 sats" in refusedPayout(all, all[0], alice, 9_950).orEmpty())
        assertNull(refusedPayout(all, all[0], alice, 3_950))
        assertNull(refusedPayout(all, all[0], bob, 15_950))
    }

    @Test
    fun `a request the group signed twice moves its amount once`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        val asked = request(all[1], all[2], 7_000)
        transferred(all, all[1], all[2], 7_000, asked, createdAt = now)
        transferred(all, all[1], all[2], 7_000, asked, createdAt = now + 10)
        val balances = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain)
        assertEquals(23_000, balances.getValue(all[1].publicKey.lowercase()).availableSat)
        assertEquals(7_000, balances.getValue(all[2].publicKey.lowercase()).availableSat)
    }

    @Test
    fun `a transfer on another rail leaves the Wavelength balance alone`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        lightningDeposit(all, all[1], 5_000)
        transferred(all, all[1], all[2], 5_000, request(all[1], all[2], 5_000, Rail.LIGHTNING))
        assertEquals(30_000, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(all[1].publicKey.lowercase()).availableSat)
        assertEquals(5_000, MemberTransfers.netsIn(all[0].db, fixture.roomId, fixture.chain, Rail.LIGHTNING).getValue(all[2].publicKey.lowercase()).sat)
    }

    /** [sat] of Lightning deposits for [member] on every device. */
    private suspend fun lightningDeposit(all: List<GroupLightningFixture.Device>, member: GroupLightningFixture.Device, sat: Long) = all.forEach { d ->
        d.db.depositDao().upsert(
            com.machankura.dedun.compose.database.model.DepositRow(
                chatRoomId = fixture.roomId, chain = com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent.chainName(fixture.chain),
                depositKey = "ln:" + randomBytes32().toHex(), source = com.machankura.dedun.compose.database.model.DepositRow.LIGHTNING,
                amountMsat = sat * 1000, member = member.publicKey.lowercase(), attributedBy = com.machankura.dedun.compose.database.model.DepositRow.BY_INVOICE,
            )
        )
    }

    /**
     * The review's first finding: a quorum signs a transfer of more than the giver has -- an old request, or one signed after
     * the giver was paid out. It counts on no device, so the receiver gains nothing and nobody's share pays for it.
     */
    @Test
    fun `a signed transfer its giver can't cover moves nothing on any device`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 10_000)
        fixture.payable(all, all[2], wallet, 10_000)
        transferred(all, all[1], all[3], 50_000)
        all.forEach { device ->
            val balances = GroupWavelengthPayouts.balancesIn(device.db, fixture.roomId, fixture.chain)
            assertEquals(10_000, balances.getValue(all[1].publicKey.lowercase()).availableSat)
            assertEquals(10_000, balances.getValue(all[2].publicKey.lowercase()).availableSat)
            assertNull(balances[all[3].publicKey.lowercase()])
        }
    }

    @Test
    fun `money paid out to its member, approved and expired here or not, can't then be transferred`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val alice = fixture.payable(all, all[1], wallet, 30_000)
        // The group approved paying Alice 29,000 and its approval ran out on this device without a round it signed: it may
        // have been paid in a round other devices co-signed.
        val metadata = fixture.metadataOf(all, alice.member)
        val payment = GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(28_500, metadata), 500, 500, now - 1, alice, metadata)
        val template = GroupWavelengthPaymentEvent.template(payment)
        fixture.fileSignedAsRoom(all, template.kind, template.tags, template.content, createdAt = now - 3_600)
        assertEquals(1_000, MemberTransfers.capacityIn(all[0].db, fixture.roomId, fixture.chain, Rail.WAVELENGTH)[all[1].publicKey.lowercase()])

        transferred(all, all[1], all[2], 20_000)
        assertNull(GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain)[all[2].publicKey.lowercase()], "the transfer counts nowhere")
    }

    @Test
    fun `a payout waits while its member's transfer is being signed`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val alice = fixture.payable(all, all[1], wallet, 30_000)
        val template = MemberTransferEvent.template(request(all[1], all[2], 5_000))
        FrostSigningManager.proposeSigning(all[1].db, all[1].room, all[1].publicKey, template.kind, template.tags, template.content)

        assertEquals("pays a member who is transferring part of their balance", refusedPayout(all, all[1], alice, 1_000))
        assertTrue(MemberTransfers.isGiving(all[1].db, fixture.roomId, fixture.chain, Rail.WAVELENGTH, all[1].publicKey))
        assertTrue(!MemberTransfers.isGiving(all[1].db, fixture.roomId, fixture.chain, Rail.LIGHTNING, all[1].publicKey))
    }

    @Test
    fun `a late joiner is handed every transfer`() {
        assertTrue(ChronicleEvent.isChroniclable(MemberTransferEvent.KIND))
        assertEquals(ChronicleEvent.KINDS.all.size - 1, ChronicleEvent.KINDS.applyRank(MemberTransferEvent.KIND), "and applied last")
    }

    @Test
    fun `the transcript says who asked, and what the group recorded`() = runBlocking<Unit> {
        val (all, _) = opened()
        val asked = request(all[1], all[2], 20_000)
        val inner = Event("00".repeat(32), all[1].publicKey, asked.createdAt, TransferRequestEvent.KIND, arrayOf(arrayOf("h", fixture.roomId)), asked.event.toJson(), "")
        val askedLine = assertNotNull(applied(all[0], inner, all[1].publicKey))
        assertEquals(ChatMessage.TYPE_MEMBER_TRANSFER_ASKED, askedLine.messageType)
        assertTrue("20,000 sats" in askedLine.content && "Wavelength" in askedLine.content, askedLine.content)

        val recorded = transferred(all, all[1], all[2], 20_000, asked)
        val recordedLine = assertNotNull(applied(all[0], recorded, fixture.roomId))
        assertEquals(ChatMessage.TYPE_MEMBER_TRANSFER_RECORDED, recordedLine.messageType)
        assertTrue("Nothing left the wallet" in recordedLine.content, recordedLine.content)
        assertTrue(recordedLine.messageType in ChatMessage.GROUP_IDENTITY_TYPES && askedLine.messageType in ChatMessage.GROUP_IDENTITY_TYPES)

        // Carried by somebody other than its giver: no line.
        val relayed = Event("00".repeat(32), all[3].publicKey, asked.createdAt, TransferRequestEvent.KIND, arrayOf(arrayOf("h", fixture.roomId)), asked.event.toJson(), "")
        assertNull(applied(all[0], relayed, all[3].publicKey))
    }

    private suspend fun applied(device: GroupLightningFixture.Device, event: Event, sender: String): ChatMessage? =
        ChatMessage.applyInnerEvent(
            database = device.db,
            groupId = fixture.roomId,
            event = event,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            senderPublicKey = sender,
            isUserMessage = false,
            createdAt = Instant.fromEpochSeconds(event.createdAt),
        )
}
