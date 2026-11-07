package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupWavelengthSeed
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.network.GroupWavelengthTermsReader
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOperatorStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 4 of `docs/group-wavelength-wallet.md`: the ceremony's purpose, the statement and the seed. */
class GroupWavelengthWalletJvmTest {
    private val fixture = GroupLightningFixture()
    private val arkOperator = "02" + "ab".repeat(32)

    @AfterTest
    fun close() = fixture.close()

    private suspend fun GroupLightningFixture.openWallet(all: List<GroupLightningFixture.Device>, proposer: GroupLightningFixture.Device = all[0]) {
        val session = GroupWavelengthWalletManager.proposeWallet(proposer.db, proposer.room, proposer.publicKey, proposer.privateKey, chain, arkOperator)
        signSession(all, proposer, session.id)
    }

    @Test
    fun `the group opens a wallet, and every member holds its seed and agrees on its manifest`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)

        val wallets = all.map { assertNotNull(GroupWavelengthWalletManager.current(it.db, fixture.roomId, fixture.chain), "device ${it.index}") }
        val statement = wallets.first().statement
        assertEquals(1, wallets.map { it.statement.manifestHash }.toSet().size)
        assertEquals(1, wallets.map { it.statement.identity }.toSet().size)
        assertEquals(arkOperator, statement.arkOperatorKey)
        all.forEach {
            val seed = assertNotNull(it.db.groupWavelengthSeedDao().get(fixture.roomId, statement.identity))
            assertEquals(statement.identity, GroupArkAuth.of(ByteVector32(seed.seed), fixture.chain).identity().toString())
        }
    }

    @Test
    fun `the transcript says the group opened it and who runs it, on every device`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)
        val nomination = GroupWavelengthOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain)
        fixture.signSession(all, all[1], nomination.id)
        all.forEach { d ->
            assertTrue("a Wavelength wallet" in fixture.lines(d, ChatMessage.TYPE_GROUP_WAVELENGTH_WALLET_OPENED).single().content)
            assertTrue("runs the group's Wavelength wallet now" in fixture.lines(d, ChatMessage.TYPE_GROUP_WAVELENGTH_OPERATOR_ELECTED).single().content)
        }
    }

    @Test
    fun `a key made for one wallet can't open the other`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        all.forEach { d ->
            assertNull(GroupLightningWalletManager.unusedKey(d.db, fixture.roomId, fixture.chain), "a Lightning wallet must not claim this key")
            assertNotNull(GroupWavelengthWalletManager.unusedKey(d.db, fixture.roomId))
        }

        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_LIGHTNING)
        all.forEach { d ->
            val forLightning = assertNotNull(GroupLightningWalletManager.unusedKey(d.db, fixture.roomId, fixture.chain))
            assertEquals(IcebergKeySession.PURPOSE_LIGHTNING, forLightning.purpose)
            val forWavelength = assertNotNull(GroupWavelengthWalletManager.unusedKey(d.db, fixture.roomId))
            assertEquals(IcebergKeySession.PURPOSE_WAVELENGTH, forWavelength.purpose)
        }
    }

    @Test
    fun `a key made for this wallet isn't the Lightning wallet's completed key, nor the reverse`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        all.forEach { d ->
            assertNull(IcebergRitualManager.completedKey(d.db, fixture.roomId), "the Lightning screen must not see this key")
            assertNotNull(IcebergRitualManager.completedKey(d.db, fixture.roomId, IcebergKeySession.PURPOSE_WAVELENGTH))
        }
    }

    @Test
    fun `a ceremony running for one wallet doesn't stand in for the other's`() = runBlocking<Unit> {
        val all = fixture.group()
        val first = IcebergRitualManager.proposeCeremony(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, 2, fixture.chain, IcebergKeySession.PURPOSE_LIGHTNING)
        val second = IcebergRitualManager.proposeCeremony(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, 2, fixture.chain, IcebergKeySession.PURPOSE_WAVELENGTH)
        assertTrue(first.id != second.id)
        assertEquals(IcebergKeySession.PURPOSE_WAVELENGTH, second.purpose)
    }

    @Test
    fun `a proposal that names no purpose is the Lightning wallet's, as every old one was`() = runBlocking<Unit> {
        val all = fixture.group()
        val proposed = fixture.runCeremony(all)
        all.forEach { assertEquals(IcebergKeySession.PURPOSE_LIGHTNING, it.ceremony(proposed.id)?.purpose) }
    }

    @Test
    fun `a ceremony's lines name the wallet it is for`() = runBlocking<Unit> {
        val all = fixture.group()
        val proposed = fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        assertEquals("Wavelength wallet", all[1].ceremony(proposed.id)?.walletName)
        assertEquals(IcebergRitualStage.COMPLETE, all[1].ceremony(proposed.id)?.stage)
    }

    @Test
    fun `a seed that does not produce the identity it claims is dropped`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        val queued = fixture.outbox(all[0], setOf(GroupWavelengthEvents.AUTH_SEED)).single()
        val message = CommonJson.decodeFromString(GroupWavelengthWalletManager.SeedMessage.serializer(), queued.content)
        val forged = message.copy(identity = "02" + "cd".repeat(32))
        val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, CommonJson.encodeToString(GroupWavelengthWalletManager.SeedMessage.serializer(), forged), "")
        GroupWavelengthWalletManager.receiveSeed(all[1].db, all[1].room, event, all[1].publicKey, all[1].privateKey)
        assertNull(all[1].db.groupWavelengthSeedDao().get(fixture.roomId, forged.identity))
    }

    @Test
    fun `a participant who holds no seed for the statement does not believe it`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        // Device 1 hears everything but the seed.
        fixture.pump(all, FrostSigningEvents.ALL)
        all.filter { it !== all[0] }.forEach { FrostSigningManager.approve(it.db, it.room, session.id) }
        fixture.pump(all, FrostSigningEvents.ALL)
        assertNull(GroupWavelengthWalletManager.current(all[1].db, fixture.roomId, fixture.chain))
        assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
        // And a screen can tell that from a group with no wallet.
        assertEquals(GroupWavelengthWalletManager.Refusal.WAITING_FOR_SEED, GroupWavelengthWalletManager.refusalOn(all[1].db, fixture.roomId, fixture.chain))
        assertNull(GroupWavelengthWalletManager.refusalOn(all[0].db, fixture.roomId, fixture.chain))
        assertNull(GroupWavelengthWalletManager.refusalOn(all[0].db, fixture.roomId, Chain.Signet.takeIf { it != fixture.chain } ?: Chain.Mainnet), "nor is another chain's business")
    }

    @Test
    fun `a statement with a manifest the keys don't make is not believed`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)
        val good = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain)).statement
        val forged = GroupWavelengthWalletStatementEvent.template(good.copy(wallet = good.wallet, manifestHash = "00".repeat(32)))
        val event = fixture.fileSignedAsRoom(all, forged.kind, forged.tags, forged.content, createdAt = 4_000_000_000)
        assertFalse(event.id.isEmpty())
        all.forEach { d ->
            val row = assertNotNull(d.db.groupSignedEventDao().getByChatRoomIdAndKind(fixture.roomId, forged.kind).firstOrNull { it.id == event.id })
            assertNull(GroupWavelengthWalletManager.verify(d.db, fixture.roomId, row))
        }
        assertEquals(good.manifestHash, assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain)).statement.manifestHash, "the group's own still stands")
    }

    @Test
    fun `mainnet is allowed and capped, testnets are refused, and the screen's gate is shut`() = runBlocking<Unit> {
        assertFalse(GroupWavelengthRollout.ENABLED)
        assertTrue(GroupWavelengthRollout.allows(Chain.Mainnet))
        assertTrue(GroupWavelengthRollout.allows(Chain.Signet))
        assertFalse(GroupWavelengthRollout.allows(Chain.Testnet3))
        assertFalse(GroupWavelengthRollout.allows(Chain.Testnet4))
        assertEquals(100_000L, GroupWavelengthRollout.capFor(Chain.Mainnet)?.sat)
        assertNull(GroupWavelengthRollout.capFor(Chain.Signet))
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val refused = runCatching { GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, Chain.Testnet4, arkOperator) }
        assertTrue(refused.isFailure)
    }

    /** A statement for [chain], signed by the room and right in every way but whether the chain is allowed: what a modified build would send. */
    private fun statementOn(chain: Chain, check: suspend (GroupLightningFixture, List<GroupLightningFixture.Device>, String) -> Unit) = runBlocking<Unit> {
        val on = GroupLightningFixture(chain = chain)
        try {
            val all = on.group()
            on.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
            val ceremony = assertNotNull(GroupWavelengthWalletManager.unusedKey(all[0].db, on.roomId))
            val seed = ByteVector32("77".repeat(32))
            val (identity, manifest) = GroupWavelengthWalletManager.bindingOf(PublicKey.fromHex(ceremony.groupPublicKey!!), seed, chain, 0)
            val chainName = GroupWalletStatementEvent.chainName(chain)
            all.forEach { it.db.groupWavelengthSeedDao().insertIfAbsent(GroupWavelengthSeed(on.roomId, identity, chainName, 0, Hex.encode(seed.toByteArray()), all[0].publicKey)) }
            val statement = GroupWavelengthWalletStatementEvent.template(
                GroupWavelengthWalletStatementEvent.Statement(
                    chain = chain, wallet = 0, onchainAccount = 0, icebergKey = ceremony.groupPublicKey!!, ceremonyId = ceremony.id,
                    threshold = ceremony.threshold, participants = ceremony.participants(), identity = identity, manifestHash = manifest,
                    arkOperatorKey = arkOperator, policy = GroupWavelengthWalletStatementEvent.Policy.DEFAULT,
                ),
            )
            val event = on.fileSignedAsRoom(all, statement.kind, statement.tags, statement.content, createdAt = 4_000_000_000)
            check(on, all, event.id)
        } finally {
            on.close()
        }
    }

    @Test
    fun `no device believes a statement for a chain the gate refuses, however well it is made, so none co-signs for it`() = statementOn(Chain.Testnet4) { on, all, id ->
        all.forEach { d ->
            val row = assertNotNull(d.db.groupSignedEventDao().getByChatRoomIdAndKind(on.roomId, GroupWavelengthWalletStatementEvent.KIND).firstOrNull { it.id == id })
            assertNull(GroupWavelengthWalletManager.verify(d.db, on.roomId, row), "device ${d.index} doesn't believe it")
            assertEquals(emptyList(), GroupWavelengthWalletManager.wallets(d.db, on.roomId, Chain.Testnet4), "so it has no wallet to co-sign for")
        }
    }

    @Test
    fun `every device believes a statement for mainnet`() = statementOn(Chain.Mainnet) { on, all, id ->
        all.forEach { d ->
            val row = assertNotNull(d.db.groupSignedEventDao().getByChatRoomIdAndKind(on.roomId, GroupWavelengthWalletStatementEvent.KIND).firstOrNull { it.id == id })
            assertNotNull(GroupWavelengthWalletManager.verify(d.db, on.roomId, row), "device ${d.index} believes it")
            assertEquals(1, GroupWavelengthWalletManager.wallets(d.db, on.roomId, Chain.Mainnet).size)
        }
    }

    @Test
    fun `a real mainnet invoice from the swap server is read, and only on mainnet`() {
        // From an empty throwaway group on 6 November 2026, whose keys no longer exist.
        val invoice = "lnbc10u1p4wu0ffpp5m4mwpxhhsj3vgwgcefrg39nvl68sy0pp9p4qrtw2j8z49qwc0etsdp9vysxwun0w4czwueqve5hyum5ypex2cm9d9mx2cqzpgxqrrssrzjqgwf02g2gy0l9vgdc25wxt0z72wjlfyagxlmk54ag9hyvrdsw37smpc2x8783ehxycqqqqqqqqqqn3qp8csp5ghvhtpjr4zpd8vwhu8ghasld6dly4589ju4gm7urfnf92kpfqwcq9qyysgq4m08rhpt5j85xv2979e9nxqmzrnl3d9amhun7gs26fjsymhp7sczeggpukw33xvndffjah9s70ylncf9ggjg0h7zemka60yyzgas0gspcl5flh"
        val payment = GroupWavelengthPaymentEvent.Payment(Chain.Mainnet, 0, invoice, 500, 1_000_000, 4_000_000_000)
        assertEquals(ByteVector32("dd76e09af784a2c43918ca4688966cfe8f023c21286a01adca91c55281d87e57"), payment.paymentHash)
        assertEquals(1_000L, payment.amountSat)
        assertNull(payment.copy(chain = Chain.Regtest).paymentHash)
    }

    @Test
    fun `a mainnet payment reads a mainnet invoice, and no other chain's`() {
        fun payment(chain: Chain, invoice: String) = GroupWavelengthPaymentEvent.Payment(chain, 0, invoice, 500, 1_000_000, 4_000_000_000)
        val mainnet = invoice(5_000_000, Chain.Mainnet)
        assertTrue(mainnet.write().startsWith("lnbc50u"))
        assertEquals(mainnet.paymentHash, payment(Chain.Mainnet, mainnet.write()).paymentHash)
        assertEquals(5_000L, payment(Chain.Mainnet, mainnet.write()).amountSat)
        assertNull(payment(Chain.Signet, mainnet.write()).paymentHash)
        assertNull(payment(Chain.Regtest, mainnet.write()).paymentHash)
        val regtest = invoice(5_000_000, Chain.Regtest).write()
        assertTrue(regtest.startsWith("lnbcrt"))
        assertNull(payment(Chain.Mainnet, regtest).paymentHash, "regtest's prefix starts with mainnet's")
    }

    private fun invoice(amountMsat: Long?, chain: Chain = fixture.chain) = Bolt11Invoice.create(
        chain, amountMsat?.msat, randomBytes32(), PrivateKey(randomBytes32()), Either.Left("the group's lunch"), CltvExpiryDelta(144),
        Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory),
    )

    @Test
    fun `a payment exists once a quorum has signed it, for the invoice it names`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)
        val wallet = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
        val pay = invoice(10_000_500)

        val session = GroupWavelengthPayments.propose(all[1].db, all[1].room, all[1].publicKey, wallet, pay.write(), 1_000, 400_000, 4_000_000_000)
        assertEquals(emptyMap(), GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet), "proposed is not approved")
        fixture.signSession(all, all[1], session.id)

        all.forEach { d ->
            val approved = GroupWavelengthPayments.approved(d.db, fixture.roomId, wallet, now = 3_999_999_999).getValue(pay.paymentHash)
            assertEquals(10_001L + 1_000, approved.maxSat, "device ${d.index} holds it to the invoice, rounded up, and the fee")
            assertEquals(400_000L, approved.maxRefundHeight)
            assertEquals(emptyMap(), GroupWavelengthPayments.approved(d.db, fixture.roomId, wallet, now = 4_000_000_000), "and not once it has expired")
        }
    }

    @Test
    fun `an invoice with no amount can't be approved, and a label can't say another hash than its invoice`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)
        val wallet = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
        assertTrue(runCatching { GroupWavelengthPayments.propose(all[0].db, all[0].room, all[0].publicKey, wallet, invoice(null).write(), 1_000, 400_000, 4_000_000_000) }.isFailure)

        val pay = invoice(10_000_000)
        val good = GroupWavelengthPaymentEvent.template(GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, pay.write(), 1_000, 400_000, 4_000_000_000))
        assertNotNull(GroupWavelengthPaymentEvent.read(good.tags))
        val lying = good.tags.map { if (it[0] == "d") arrayOf("d", "${it[1].substringBeforeLast(":")}:${"77".repeat(32)}") else it }.toTypedArray()
        assertNull(GroupWavelengthPaymentEvent.read(lying))
    }

    @Test
    fun `a real signet invoice from the swap server is read, which the library can't, and only on signet`() {
        val invoice = "lntbs50u1p4wugxspp5zh0y9lsme9hcpc7u6kg3n4ys869p09x2ulguasgh9djymnspnzcqdqcwpskjepqvfujqcfqvaex7atscqzpgxqrrssrzjq0205vahju52xdkpc2hkgdtns2grzu9stmedee8usaetmldc3t540e7pwtnnqdaptqqqqqqqqqqqqeqp8crzjqd55d6radn2xsvfsgrn3sh44dafls79az5qxz56ejft704js0hz40e7pwtnnqdaptqqqqqqqqqqqqeqp8crzjqg4eu39n4qynm9gjkc04lqa894trggq7l3yhrrhaxnewapgm8tagae7pwtnnqdaptqqqqqqqqqqqqeqp8csp5l8nr0jzdmvyq78elev4tkj2jj60gu2chxgac7pmyn7uy6k6g7utq9qyysgq6ch20zssxqqxqsfk34aec856l88q7096je97ll5xxue0vupywa6puen0slqvtaph9zr5t5sqqutzkc5qegw66prum65cx6aft8l7a3qq3lzhct"
        val payment = GroupWavelengthPaymentEvent.Payment(Chain.Signet, 0, invoice, 500, 400_000, 4_000_000_000)
        assertEquals(ByteVector32("15de42fe1bc96f80e3dcd59119d4903e8a1794cae7d1cec1172b644dce0198b0"), payment.paymentHash)
        assertEquals(5_000L, payment.amountSat)
        assertEquals(5_500L, payment.maxSat)
        assertNull(payment.copy(chain = Chain.Regtest).paymentHash, "another chain's prefix isn't this invoice")
        assertNull(payment.copy(invoice = invoice.dropLast(1) + "q").paymentHash, "nor is one with a bad checksum")
    }

    @Test
    fun `this reads what the library reads, on a chain it knows`() {
        listOf(10_000_500L, 1L, 2_100_000_000_000_000L).forEach { msat ->
            val pay = invoice(msat)
            val read = GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, pay.write(), 0, 0, 0)
            assertEquals(pay.paymentHash, read.paymentHash)
            assertEquals((msat + 999) / 1000, read.amountSat)
        }
    }

    @Test
    fun `a payment for another wallet or chain isn't this wallet's, even signed by the room`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)
        val wallet = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
        listOf(GroupWavelengthPaymentEvent.Payment(fixture.chain, 7, invoice(10_000_000).write(), 1_000, 400_000, 4_000_000_000)).forEachIndexed { i, payment ->
            val template = GroupWavelengthPaymentEvent.template(payment)
            fixture.fileSignedAsRoom(all, template.kind, template.tags, template.content, createdAt = 4_000_000_000 + i)
        }
        assertEquals(emptyMap(), GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet))
    }

    @Test
    fun `a member proposing it is told what the wallet asks of the group`() {
        val template = GroupWavelengthWalletStatementEvent.template(
            GroupWavelengthWalletStatementEvent.Statement(
                Chain.Signet, 0, 0, arkOperator, "c".repeat(64), 2, listOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)),
                arkOperator, "00".repeat(32), arkOperator, GroupWavelengthWalletStatementEvent.Policy.DEFAULT,
            )
        )
        val summary = ProposedEvent.summarize(Event("0".repeat(64), "0".repeat(64), 0, template.kind, template.tags, template.content, ""))
        assertEquals("Open a Wavelength wallet", summary.label)
        assertTrue(summary.detail.contains("3 devices online"), summary.detail)
    }

    @Test
    fun `a member approving a nomination is told who and which term`() {
        val template = GroupWavelengthOperatorStatementEvent.template(GroupWavelengthOperatorStatementEvent.Statement(Chain.Signet, 0, "ab".repeat(32), 3))
        val event = Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
        val summary = ProposedEvent.summarize(event) { "Thandi" }
        assertEquals("Choose who runs the Wavelength wallet", summary.label)
        assertTrue("Term 3" in summary.detail && "Thandi" in summary.detail, summary.detail)
    }

    @Test
    fun `the terms this build proposes under are the ones a real operator sent`() {
        val mainnet = """{"pubkey":"Axm1Ki1Wc+WPk7rT1bpZ2FVl8li8PakIe3ZZ5n3MIpsX","boarding_exit_delay":512,"vtxo_exit_delay":144,"min_boarding_amount":"1000","max_vtxo_amount":"50000","min_confirmations":6,"min_operator_fee":"1000","max_user_balance":"300000","free_refresh_window_blocks":648}"""
        val signet = """{"pubkey":"A302LPU/X+OrdSl04rgAgwI1nPeuQqXzFscvkERQjzC5","boarding_exit_delay":512,"vtxo_exit_delay":144,"min_boarding_amount":"1000","max_vtxo_amount":"200000","min_confirmations":1,"min_operator_fee":"1000","max_user_balance":"100000000","free_refresh_window_blocks":504}"""
        assertEquals(GroupWavelengthTerms.of(Chain.Mainnet), GroupWavelengthTermsReader.parse(mainnet))
        assertEquals(GroupWavelengthTerms.of(Chain.Signet), GroupWavelengthTermsReader.parse(signet))
        assertNull(GroupWavelengthTermsReader.parse("not an answer"))
        assertNull(GroupWavelengthTerms.of(Chain.Regtest))
        GroupWavelengthRollout.NETWORKS.forEach { assertNotNull(GroupWavelengthTerms.of(it), "a network the screen offers needs terms") }
    }

    @Test
    fun `its kinds stand apart from the Lightning wallet's and each other's`() {
        assertEquals((30370..30382).toSet(), GroupWavelengthEvents.ALL)
        assertTrue(GroupWavelengthEvents.ALL.intersect(GroupLightningEvents.ALL).isEmpty())
        assertTrue(GroupWavelengthEvents.ALL.min() > GroupLightningEvents.ALL.max())
    }

}
