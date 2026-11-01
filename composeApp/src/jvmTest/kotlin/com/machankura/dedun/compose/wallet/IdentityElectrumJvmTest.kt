package com.machankura.dedun.compose.wallet

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.phoenix.managers.global.NetworkState
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.flow.flowOf
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Which client each kind of identity watches on, and that a switch never leaves
 * the last profile's connection open (D1 of `docs/standalone-electrum.md`).
 */
class IdentityElectrumJvmTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Never connects: the network is down. What is under test is which client is made, and when it is stopped. */
    private class IdleLink : ElectrumLink {
        override val client = FakeElectrum()
        var stopped = false
        override suspend fun connect(server: ServerAddress, timeout: Duration) {}
        override fun disconnect() {}
        override fun stop() {
            stopped = true
        }
    }

    private val made = mutableListOf<Pair<Identity, IdleLink>>()

    /** For each client made, whether every one made before it had been stopped by then. */
    private val earlierStopped = mutableListOf<Boolean>()

    private val identityElectrum = IdentityElectrum { identity ->
        earlierStopped += made.all { it.second.stopped }
        val link = IdleLink()
        made += identity to link
        StandaloneElectrum(
            chain = Chain.Regtest,
            networkState = flowOf(NetworkState.NotAvailable),
            isTorEnabled = flowOf(false),
            customServer = flowOf(null),
            link = link,
        )
    }

    private fun prefsStore() = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("prefs.preferences_pb").path.toPath()
    }

    private fun key() = PrivateKey(Random.nextBytes(32).byteVector32())

    private fun signing(kind: IdentityKind, key: PrivateKey = key()) = Identity.signing(
        id = key.publicKey().xOnly().toWalletId(),
        kind = kind,
        nostrPrivateKey = key,
        userPrefs = UserPrefs(prefsStore()),
        internalPrefs = InternalPrefs(prefsStore()),
        business = null,
    )

    private fun readOnly(key: PrivateKey = key()) = Identity.readOnly(
        id = key.publicKey().xOnly().toWalletId(),
        nostrPublicKey = key.nostrPublicKeyHex(),
        userPrefs = UserPrefs(prefsStore()),
        internalPrefs = InternalPrefs(prefsStore()),
    )

    @Test
    fun `a profile with a node watches on the node's client, and no other is made`() {
        val node = FakeElectrum()
        val client = identityElectrum.clientFor(signing(IdentityKind.Mnemonic), nodeClient = node)
        assertSame(node, client)
        assertEquals(emptyList(), made)
    }

    @Test
    fun `an nsec profile and an npub profile each get a client of their own`() {
        val nsec = signing(IdentityKind.NostrSecret)
        val nsecClient = identityElectrum.clientFor(nsec)
        assertSame(made.single { it.first == nsec }.second.client, nsecClient)

        val npub = readOnly()
        val npubClient = identityElectrum.clientFor(npub)
        assertSame(made.single { it.first == npub }.second.client, npubClient)
    }

    @Test
    fun `the same identity asked again keeps its client`() {
        val nsec = signing(IdentityKind.NostrSecret)
        val first = identityElectrum.clientFor(nsec)
        assertSame(first, identityElectrum.clientFor(nsec))
        assertEquals(1, made.size)
    }

    @Test
    fun `switching from one nsec to another stops the first before the second is made`() {
        val firstClient = identityElectrum.clientFor(signing(IdentityKind.NostrSecret))
        val secondClient = identityElectrum.clientFor(signing(IdentityKind.NostrSecret))

        assertEquals(listOf(true, true), earlierStopped)
        assertNotSame(firstClient, secondClient)
        assertEquals(listOf(true, false), made.map { it.second.stopped })
    }

    @Test
    fun `switching to a profile with a node stops the standalone client`() {
        identityElectrum.clientFor(signing(IdentityKind.NostrSecret))
        identityElectrum.clientFor(signing(IdentityKind.Mnemonic), nodeClient = FakeElectrum())
        assertTrue(made.single().second.stopped)
    }

    @Test
    fun `no identity has no client, and the last one is stopped`() {
        identityElectrum.clientFor(signing(IdentityKind.NostrSecret))
        assertNull(identityElectrum.clientFor(null))
        assertTrue(made.single().second.stopped)
    }
}
