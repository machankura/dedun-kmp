package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupLightningFixture
import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthPayments
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import engineering.lightning.wavelength.FacadeBindings
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
        override fun startHostedWatchOnlyWallet(request: ByteArray, host: SignerHost): ByteArray {
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

    private fun runnerFor(device: GroupLightningFixture.Device, runtime: Runtime, slot: Slot, held: MutableList<String?>, poolWait: kotlin.time.Duration = 20.seconds, enabled: Boolean = true) = GroupWavelengthRunner(
        database = device.db,
        userPublicKey = device.publicKey,
        scope = scope,
        slot = slot,
        daemon = GroupWavelengthDaemon(FacadeWavelengthClient(runtime, Dispatchers.Unconfined), root.path),
        enabled = enabled,
        chains = listOf(fixture.chain),
        onHolding = { held += it },
        poolWait = poolWait,
        lastHeard = { Clock.System.now() },
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
    fun `devices that don't get ready in time hold the daemon back, and it's tried again`() = runBlocking<Unit> {
        val setup = opened()
        elect(setup)
        val slot = Slot()
        val runner = runnerFor(setup.all[0], setup.runtime, slot, mutableListOf(), poolWait = 100.milliseconds)
        runner.refresh()
        assertEquals(GroupWavelengthDaemonStatus.Refused(GroupWavelengthDaemon.Refusal.SIGNERS_NOT_READY), runner.observe(fixture.roomId, fixture.chain, 0).first())
        assertFalse(setup.runtime.isRunning())
        assertEquals(1, slot.released, "and the person's wallet gets the daemon back meanwhile")
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
}
