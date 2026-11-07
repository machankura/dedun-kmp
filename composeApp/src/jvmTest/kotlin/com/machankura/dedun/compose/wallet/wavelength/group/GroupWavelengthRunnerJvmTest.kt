package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupLightningFixture
import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthPayments
import com.machankura.dedun.compose.managers.GroupWavelengthPayouts
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import engineering.lightning.wavelength.FacadeBindings
import engineering.lightning.wavelength.FacadeVerb
import engineering.lightning.wavelength.FacadeWavelengthClient
import engineering.lightning.wavelength.SignerHost
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What runs a group's wallet on the elected device, over the SDK's fake runtime: there is no runtime in `jvmTest`. */
class GroupWavelengthRunnerJvmTest {
    private val fixture = GroupLightningFixture().also { f ->
        val inherited = f.dispatch
        f.dispatch = { to, event ->
            when (event.kind) {
                in GroupWavelengthEvents.CO_SIGNING -> GroupArkCoSigner.process(to.db, to.room, event, to.publicKey)
                else -> inherited(to, event)
            }
        }
    }
    private val root = Files.createTempDirectory("group-wavelength").toFile()
    private val arkOperator = "02" + "ab".repeat(32)
    private val scope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun close() {
        fixture.close()
        root.deleteRecursively()
    }

    private class Runtime(val fake: FakeFacadeBindings = FakeFacadeBindings()) : FacadeBindings by fake {
        var manifest = ""
        var identity = ""
        var coinsSaid: String? = null
        var failNextStart = false
        override fun call(verb: FacadeVerb, request: ByteArray): ByteArray =
            if (verb == FacadeVerb.LIST && coinsSaid != null && request.decodeToString().contains("vtxos")) {
                coinsSaid!!.encodeToByteArray().also { fake.call(verb, request) }
            } else fake.call(verb, request)

        override fun startHostedWatchOnlyWallet(request: ByteArray, host: SignerHost): ByteArray {
            if (failNextStart) {
                failNextStart = false
                throw IllegalStateException("recover wallet state: the group didn't answer")
            }
            val heard = request.decodeToString().replace(identity, fake.identityPubKey).encodeToByteArray()
            val body = fake.startHostedWatchOnlyWallet(heard, host).decodeToString()
            return body.replace(fake.identityPubKey, identity).replace(Regex("\"ManifestHash\":\"[^\"]*\""), "\"ManifestHash\":\"$manifest\"").encodeToByteArray()
        }
    }

    private class Slot : GroupDaemonSlot {
        override val personalWanted = MutableStateFlow(false)
        val held = mutableListOf<String>()
        var released = 0
        override suspend fun acquire(group: String): Boolean {
            if (personalWanted.value) return false
            held += group
            return true
        }
        override suspend fun release() {
            released++
        }
    }

    private inner class Setup(val all: List<GroupLightningFixture.Device>, val wallet: GroupWavelengthWalletManager.Wallet) {
        val runtime = Runtime().also {
            it.manifest = wallet.statement.manifestHash
            it.identity = wallet.statement.identity
        }
        val slot = Slot()
        val held = mutableListOf<String?>()
        val runner = runnerFor(all[0], runtime, slot, held)

        /** Runs the other devices while [block] waits on them. */
        suspend fun <T> pumped(block: suspend () -> T): T {
            val pump = scope.launch {
                while (isActive) {
                    // Whole passes: a message counted as delivered and cancelled on its way would be lost, which no relay here does.
                    withContext(NonCancellable) { fixture.pump(all, GroupWavelengthEvents.CO_SIGNING) }
                    delay(20)
                }
            }
            return try {
                block()
            } finally {
                pump.cancelAndJoin()
            }
        }

        suspend fun status() = runner.observe(fixture.roomId, fixture.chain, 0).first()
    }

    private fun runnerFor(
        device: GroupLightningFixture.Device,
        runtime: Runtime,
        slot: Slot,
        held: MutableList<String?>,
        poolWait: kotlin.time.Duration = 20.seconds,
        enabled: Boolean = true,
        lastHeard: suspend (String) -> kotlin.time.Instant? = { Clock.System.now() },
    ) = GroupWavelengthRunner(
        database = device.db,
        userPublicKey = device.publicKey,
        scope = scope,
        slot = slot,
        daemon = GroupWavelengthDaemon(FacadeWavelengthClient(runtime, Dispatchers.Unconfined), root.path),
        enabled = enabled,
        chains = listOf(fixture.chain),
        onHolding = { held += it },
        poolWait = poolWait,
        lastHeard = lastHeard,
    )

    private suspend fun opened(): Setup {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        fixture.signSession(all, all[0], session.id)
        return Setup(all, assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain)))
    }

    private suspend fun elect(setup: Setup, device: GroupLightningFixture.Device = setup.all[0]) {
        val nominated = GroupWavelengthOperatorManager.nominateSelf(device.db, device.room, device.publicKey, fixture.chain)
        fixture.signSession(setup.all, device, nominated.id)
    }

    @Test
    fun `a device nobody elected runs nothing`() = runBlocking<Unit> {
        val setup = opened()
        setup.runner.refresh()
        assertEquals(0, setup.slot.held.size)
        assertEquals(GroupWavelengthDaemonStatus.NotOnThisDevice, setup.status())
        assertFalse(setup.runtime.isRunning())
    }

    @Test
    fun `behind a shut gate it runs nothing, elected or not`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        val slot = Slot()
        runnerFor(setup.all[0], setup.runtime, slot, mutableListOf(), enabled = false).refresh()
        assertEquals(0, slot.held.size)
    }

    @Test
    fun `an elected device gets the group's devices ready first, then opens, and scans the first time`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }

        assertEquals(GroupWavelengthDaemonStatus.Running(1), setup.status())
        assertEquals(1, setup.slot.held.size)
        assertEquals(1, setup.held.size)
        assertTrue(setup.runtime.isRunning())
        val sent = setup.runtime.fake.requests.first().second
        assertTrue(sent.contains("\"recovery_window\":${GroupWavelengthRunner.RECOVERY_WINDOW}"), sent)
        // And it's the round one the daemon's first signature needs: every device published it.
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        val quorum = 2 * setup.wallet.statement.threshold - 1
        assertTrue(setup.all[0].db.icebergCoSigningDao().noncesFor(key, 0).size >= quorum)
        // And no device finds out how far the group has answered during a round, where it'd cost the deadline.
        setup.pumped {
            withTimeoutOrNull(5.seconds) {
                while (setup.all.any { it.db.groupArkDao().floor(key)?.floor == null }) delay(20)
            }
        }
        setup.all.forEach { assertEquals(0L, it.db.groupArkDao().floor(key)?.floor, "device ${it.index}") }
    }

    @Test
    fun `a quiet room is asked, and the answers let the daemon start`() = runBlocking<Unit> {
        // As after the first mainnet operator's app was restarted: the room's last word was an hour old, and nobody would speak first.
        val setup = opened()
        elect(setup)
        val operator = setup.all[0]
        val answers = suspend {
            operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.HIGH_WATER))
                .filter { it.publicKey != operator.publicKey }
        }
        val silence = Clock.System.now() - 1.hours
        val asked = suspend {
            operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.HIGH_WATER))
                .count { it.publicKey == operator.publicKey && it.content.contains("\"asking\":true") }
        }
        val runner = runnerFor(operator, setup.runtime, setup.slot, setup.held, lastHeard = { answers().maxOfOrNull { it.createdAt } ?: silence })

        runner.refresh()
        assertEquals(GroupWavelengthDaemonStatus.CatchingUp, runner.observe(fixture.roomId, fixture.chain, 0).first())
        assertEquals(1, asked(), "it asks the room")
        runner.refresh()
        assertEquals(1, asked(), "and not again straight away")

        setup.pumped {
            withTimeoutOrNull(5.seconds) { while (answers().size < setup.all.size - 1) delay(20) }
            runner.refresh()
        }
        val question = operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.HIGH_WATER))
            .first { it.publicKey == operator.publicKey && it.content.contains("\"asking\":true") }
        assertEquals(setup.all.size - 1, answers().filter { it.content.contains(question.id) }.map { it.publicKey }.toSet().size, "every other device answered it")
        assertEquals(GroupWavelengthDaemonStatus.Running(1), runner.observe(fixture.roomId, fixture.chain, 0).first())
        assertTrue(setup.runtime.isRunning())
    }

    @Test
    fun `devices that don't get ready in time hold the daemon back, and it's tried again`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        val slot = Slot()
        val runner = runnerFor(setup.all[0], setup.runtime, slot, mutableListOf(), poolWait = 100.milliseconds)
        runner.refresh()
        assertEquals(GroupWavelengthDaemonStatus.Refused(GroupWavelengthDaemon.Refusal.SIGNERS_NOT_READY), runner.observe(fixture.roomId, fixture.chain, 0).first())
        assertFalse(setup.runtime.isRunning())
        assertEquals(0, slot.held.size, "and the person's wallet was never asked for the daemon")
    }

    @Test
    fun `a later term stops it, and hands the daemon back`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        assertTrue(setup.runtime.isRunning())
        elect(setup, setup.all[1])
        setup.runner.refresh()
        assertFalse(setup.runtime.isRunning(), "the old operator stops")
        assertEquals(1, setup.slot.released)
        assertEquals(null, setup.held.last())
        assertEquals(GroupWavelengthDaemonStatus.NotOnThisDevice, setup.status())
    }

    @Test
    fun `the person opening their own wallet pauses the group's, which comes back when they leave`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        setup.slot.personalWanted.value = true
        setup.runner.refresh()
        assertFalse(setup.runtime.isRunning())
        assertEquals(GroupWavelengthDaemonStatus.PausedForPersonal, setup.status())
        setup.slot.personalWanted.value = false
        setup.pumped { setup.runner.refresh() }
        assertIs<GroupWavelengthDaemonStatus.Running>(setup.status())
    }

    @Test
    fun `round one is kept ahead of what the daemon has used`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        val dao = setup.all[0].db.icebergCoSigningDao()
        val before = dao.noncesFor(key, GroupWavelengthRunner.PLAIN_WINDOW + 10).size
        assertEquals(0, before)
        dao.insertAssignment(IcebergLabelAssignment(key, GroupWavelengthRunner.PLAIN_WINDOW - GroupWavelengthRunner.PLAIN_LOW + 5, "", "plain"))
        setup.runner.refresh()
        setup.pumped { delay(300) }
        assertTrue(dao.noncesFor(key, GroupWavelengthRunner.PLAIN_WINDOW + 10).isNotEmpty(), "asked for more before it ran out")
    }

    @Test
    fun `the group is told what the daemon says it holds, and a later term ends with a line`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        assertNotNull(setup.all[0].db.groupArkDao().snapshot(key), "the operator files its own")
        elect(setup, setup.all[1])
        setup.runner.refresh()
        assertTrue(fixture.lines(setup.all[0], ChatMessage.TYPE_WAVELENGTH_OPERATOR_STOPPED).isNotEmpty())
    }

    @Test
    fun `a first open scans as wide as this device's record says, and no narrower than the least`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        repeat(12) { setup.all[0].db.groupArkDao().upsertVtxo(GroupArkVtxo(key, "c$it:0", 1)) }
        setup.pumped { setup.runner.refresh() }
        val sent = setup.runtime.fake.requests.first().second
        assertTrue(sent.contains("\"recovery_window\":${12 + GroupWavelengthRunner.RECOVERY_WINDOW}"), sent)
    }

    private fun invoiceFor(sat: Long): String = Bolt11Invoice.create(
        fixture.chain, (sat * 1000).msat, randomBytes32(), PrivateKey(ByteVector32("11".repeat(32))), Either.Left("pay"), CltvExpiryDelta(18),
        Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), randomBytes32(),
    ).write()

    @Test
    fun `it pays what a quorum approved, once, and nothing else`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        val invoice = invoiceFor(1_000)
        val payout = fixture.payable(setup.all, setup.all[1], setup.wallet, 100_000)

        // Nothing approved yet, so nothing is tried: an unapproved attempt holds the coin while every device refuses it.
        setup.runner.refresh()
        assertFalse(setup.runtime.fake.requests.any { invoice in it.second })

        val session = GroupWavelengthPayments.propose(setup.all[1].db, setup.all[1].room, setup.all[1].publicKey, setup.wallet, invoice, 50, 500, Clock.System.now().epochSeconds + 3600, payout)
        fixture.signSession(setup.all, setup.all[1], session.id)
        setup.runner.refresh()
        setup.runner.refresh()
        withTimeoutOrNull(5.seconds) { while (setup.runtime.fake.requests.none { invoice in it.second }) delay(20) }
        assertEquals(1, setup.runtime.fake.requests.count { invoice in it.second }, "once")
        val sent = setup.runtime.fake.requests.first { invoice in it.second }.second
        assertFalse(sent.contains("MaxCredit"), "credit stays closed, which on the wire is saying nothing of it: $sent")
    }

    @Test
    fun `it pays an onchain payout a quorum approved, once, to the member's address, and nothing it already paid`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        val address = "bcrt1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3lgth6c"
        val payout = fixture.payable(setup.all, setup.all[1], setup.wallet, 100_000, onchainAddress = address)
        fun sends() = setup.runtime.fake.requests.filter { address in it.second }

        setup.runner.refresh()
        assertTrue(sends().isEmpty(), "nothing approved, nothing tried")

        val approved = GroupWavelengthOnchainPayoutEvent.paying(fixture.chain, 0, payout.member, payout.destinationEventId, address, 50_000, 1_000)
        val session = GroupWavelengthPayouts.proposeOnchain(setup.all[1].db, setup.all[1].room, setup.all[1].publicKey, approved)
        fixture.signSession(setup.all, setup.all[1], session.id)
        setup.runner.refresh()
        setup.runner.refresh()
        withTimeoutOrNull(5.seconds) { while (sends().isEmpty()) delay(20) }
        delay(100)
        assertEquals(1, sends().size, "once")
        val sent = sends().single().second
        assertTrue("50000" in sent && approved.id in sent && "\"SweepAll\":true" !in sent, "the amount approved, noted with the approval, and never everything: $sent")

        // Another approval this device already signed a round paying isn't sent again, whoever runs the wallet next.
        val again = GroupWavelengthOnchainPayoutEvent.paying(fixture.chain, 0, payout.member, payout.destinationEventId, address, 1_000, 1_000, Clock.System.now().epochSeconds + 5)
        fixture.fileSignedAsRoom(setup.all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(again).tags)
        setup.all[0].db.groupArkDao().upsertLeave(GroupArkLeave(GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0), again.id, "commitment", "00", 1_000, 300))
        setup.runner.refresh()
        delay(100)
        assertTrue(sends().none { again.id in it.second })
    }

    /** The runner reads the coins it leaves with from the daemon. */
    private fun Runtime.withCoin(outpoint: String) = apply { coinsSaid = """{"View":"vtxos","VTXOs":{"VTXOs":[{"Outpoint":"$outpoint","AmountSat":10000,"Status":"live"}],"Total":1}}""" }

    private fun exitRequests(setup: Setup) = setup.runtime.fake.requests.filter { it.first == FacadeVerb.EXIT.wireName }

    @Test
    fun `nobody asking and plenty of time means nothing leaves`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.runtime.withCoin("cc:0")
        setup.pumped { setup.runner.refresh() }
        setup.runner.refresh()
        assertEquals(emptyList(), exitRequests(setup).map { it.second })
    }

    @Test
    fun `when a quorum asks, it leaves, once a coin, for the group's own onchain wallet`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.runtime.withCoin("cc:0")
        setup.pumped { setup.runner.refresh() }
        val asking = GroupWavelengthPayouts.proposeExit(setup.all[1].db, setup.all[1].room, setup.all[1].publicKey, setup.wallet)
        fixture.signSession(setup.all, setup.all[1], asking.id)
        setup.runner.refresh()
        setup.runner.refresh()
        withTimeoutOrNull(5.seconds) { while (exitRequests(setup).isEmpty()) delay(20) }
        delay(100)
        val leaving = exitRequests(setup)
        assertEquals(1, leaving.size, "once")
        assertTrue("cc:0" in leaving.single().second, leaving.single().second)
    }

    @Test
    fun `a coin close to its deadline leaves by itself, with nobody asking`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.runtime.withCoin("cc:0")
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        setup.all[0].db.groupArkDao().upsertVtxo(GroupArkVtxo(key, "cc:0", 10_000, settled = true, expiresAtHeight = 100))
        setup.pumped { setup.runner.refresh() }
        setup.runner.refresh()
        withTimeoutOrNull(5.seconds) { while (exitRequests(setup).isEmpty()) delay(20) }
        assertEquals(1, exitRequests(setup).size, "nobody asked: its deadline did")
    }

    @Test
    fun `an old coin near its deadline doesn't take a new one with it`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.runtime.coinsSaid = """{"View":"vtxos","VTXOs":{"VTXOs":[{"Outpoint":"old:0","AmountSat":1000,"Status":"live"},{"Outpoint":"new:0","AmountSat":1000,"Status":"live"}],"Total":2}}"""
        val key = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        setup.all[0].db.groupArkDao().upsertVtxo(GroupArkVtxo(key, "old:0", 1_000, settled = true, expiresAtHeight = 100))
        setup.all[0].db.groupArkDao().upsertVtxo(GroupArkVtxo(key, "new:0", 1_000, settled = true, expiresAtHeight = 100_000_000))
        setup.pumped { setup.runner.refresh() }
        setup.runner.refresh()
        withTimeoutOrNull(5.seconds) { while (exitRequests(setup).isEmpty()) delay(20) }
        delay(100)
        assertEquals(1, exitRequests(setup).size)
        assertTrue("old:0" in exitRequests(setup).single().second)
    }

    @Test
    fun `devices that aren't ready leave the person's own wallet alone`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        val slot = Slot()
        runnerFor(setup.all[0], setup.runtime, slot, mutableListOf(), poolWait = 100.milliseconds).refresh()
        assertEquals(0, slot.held.size, "it never asked for the daemon")
        assertEquals(0, slot.released)
    }

    @Test
    fun `an open that fails partway leaves its directory, and the retry still scans`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        val daemon = GroupWavelengthDaemon(FacadeWavelengthClient(setup.runtime, Dispatchers.Unconfined), root.path)
        setup.runtime.failNextStart = true
        setup.pumped { setup.runner.refresh() }
        assertEquals(GroupWavelengthDaemonStatus.Failed, setup.status())
        assertFalse(daemon.hasOpened(setup.wallet.statement), "a scan that failed found nothing")
        // What the daemon leaves behind when it fails inside its recovery scan.
        val leftBehind = root.resolve("group/${GroupWalletStatementEvent.chainName(fixture.chain)}/${setup.wallet.statement.identity.take(32)}/0")
        leftBehind.mkdirs()
        assertEquals(1, setup.slot.released, "and the person's wallet gets the daemon back")

        setup.pumped { setup.runner.refresh() }
        assertEquals(GroupWavelengthDaemonStatus.Running(1), setup.status())
        assertTrue(setup.runtime.fake.requests.last { it.second.contains("manifest") }.second.contains("\"recovery_window\":"), "scanned again")
        assertTrue(daemon.hasOpened(setup.wallet.statement), "and now it knows")
    }

    @Test
    fun `once an open has finished, the next doesn't scan`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        setup.pumped { setup.runner.refresh() }
        setup.slot.personalWanted.value = true
        setup.runner.refresh()
        setup.slot.personalWanted.value = false
        setup.pumped { setup.runner.refresh() }
        assertIs<GroupWavelengthDaemonStatus.Running>(setup.status())
        val opens = setup.runtime.fake.requests.filter { it.second.contains("manifest") }
        assertTrue(opens.size >= 2)
        assertFalse(opens.last().second.contains("\"recovery_window\":"), opens.last().second)
    }
}
