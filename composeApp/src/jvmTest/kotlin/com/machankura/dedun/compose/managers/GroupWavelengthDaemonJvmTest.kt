package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.IcebergKeySession
import engineering.lightning.wavelength.FacadeBindings
import engineering.lightning.wavelength.FacadeWavelengthClient
import engineering.lightning.wavelength.SignerHost
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** The rest of Phase 5 of `docs/group-wavelength-wallet.md`, over the SDK's fake runtime: there is no runtime in `jvmTest`. */
class GroupWavelengthDaemonJvmTest {
    private val fixture = GroupLightningFixture()
    private val arkOperator = "02" + "ab".repeat(32)

    @AfterTest
    fun close() = fixture.close()

    /** The fake runtime, reporting the manifest and identity it is told to. */
    private class Runtime(val fake: FakeFacadeBindings = FakeFacadeBindings(), var manifest: String = "", var identity: String = "") : FacadeBindings by fake {
        override fun startHostedWatchOnlyWallet(request: ByteArray, host: SignerHost): ByteArray {
            // The fake has one identity of its own, so it hears and answers in that one's name.
            val heard = request.decodeToString().replace(identity, fake.identityPubKey).encodeToByteArray()
            val body = fake.startHostedWatchOnlyWallet(heard, host).decodeToString()
            return body.replace(fake.identityPubKey, identity).replace(Regex("\"ManifestHash\":\"[^\"]*\""), "\"ManifestHash\":\"$manifest\"").encodeToByteArray()
        }
    }

    private suspend fun elected(): Pair<List<GroupLightningFixture.Device>, GroupWavelengthWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        fixture.signSession(all, all[0], session.id)
        val nominated = GroupWavelengthOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain)
        fixture.signSession(all, all[0], nominated.id)
        return all to assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
    }

    private val now = Clock.System.now()

    @Test
    fun `the operator opens with the manifest the group signed, the seed's keys and no signer named`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val runtime = Runtime(manifest = wallet.statement.manifestHash, identity = wallet.statement.identity)
        val daemon = GroupWavelengthDaemon(FacadeWavelengthClient(runtime, Dispatchers.Unconfined), "/tmp/g")
        val outcome = daemon.open(all[0].db, all[0].room, all[0].publicKey, wallet, recoveryWindow = 3, lastSyncedAt = now, now = now)
        assertIs<GroupWavelengthDaemon.Outcome.Running>(outcome, "$outcome")

        val sent = runtime.fake.requests.first().second
        assertTrue(sent.contains("\"remote_signer_request_timeout_seconds\":120"), sent)
        assertFalse(sent.contains("remote_signer_rpc_host"), sent)
        assertTrue(sent.contains("\"recovery_window\":3"), sent)
        assertTrue(sent.contains("tprv"), "the authentication keys go to the daemon")
        assertTrue(runtime.fake.signerHost === outcome.transport)
    }

    @Test
    fun `a daemon that opens as anything else is stopped`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val runtime = Runtime(manifest = "00".repeat(32), identity = wallet.statement.identity)
        val client = FacadeWavelengthClient(runtime, Dispatchers.Unconfined)
        val outcome = GroupWavelengthDaemon(client, "/tmp/g").open(all[0].db, all[0].room, all[0].publicKey, wallet, null, now, now)
        assertIs<GroupWavelengthDaemon.Outcome.Refused>(outcome)
        assertFalse(client.isRunning(), "stopped, not left running")
    }

    @Test
    fun `only the elected operator, freshly synced, may start`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val runtime = Runtime(manifest = wallet.statement.manifestHash, identity = wallet.statement.identity)
        val daemon = GroupWavelengthDaemon(FacadeWavelengthClient(runtime, Dispatchers.Unconfined), "/tmp/g")
        assertIs<GroupWavelengthDaemon.Outcome.Refused>(daemon.open(all[1].db, all[1].room, all[1].publicKey, wallet, null, now, now), "not elected")
        assertIs<GroupWavelengthDaemon.Outcome.Refused>(daemon.open(all[0].db, all[0].room, all[0].publicKey, wallet, null, now - 2.hours, now), "a week offline")
        assertIs<GroupWavelengthDaemon.Outcome.Refused>(daemon.open(all[0].db, all[0].room, all[0].publicKey, wallet, null, null, now), "never synced")
        assertEquals(0, runtime.fake.requests.size, "nothing was started")
    }

    @Test
    fun `the wallet's password is the same on every device, and isn't the seed`() {
        val seed = "a5".repeat(32)
        assertEquals(32, GroupWavelengthDaemon.passwordOf(seed).size)
        assertFalse(fr.acinq.secp256k1.Hex.encode(GroupWavelengthDaemon.passwordOf(seed)) == seed)
    }
}
