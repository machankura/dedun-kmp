package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ServerVersionResponse
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.managers.global.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The connection loop of a profile with no node, against a link whose connection
 * status the test moves. No socket is opened: the loop's job is to decide when to
 * connect and to where, and that is all it is asked here.
 */
class StandaloneElectrumJvmTest {
    private class FakeLink : ElectrumLink {
        override val client = FakeElectrum()
        val attempts = mutableListOf<Pair<ServerAddress, Duration>>()
        var succeeds = true
        var stopped = false

        override suspend fun connect(server: ServerAddress, timeout: Duration) {
            attempts += server to timeout
            client.connectionStatus.value = ElectrumConnectionStatus.Connecting(server)
            client.connectionStatus.value = if (succeeds) {
                ElectrumConnectionStatus.Connected(ServerVersionResponse("fake", "1.4"), 0, Block.RegtestGenesisBlock.header)
            } else {
                // A fresh exception each time, as the real client makes: two closes in a
                // row are then two values, not one the StateFlow swallows.
                ElectrumConnectionStatus.Closed(TcpSocket.IOException.ConnectionRefused())
            }
        }

        /** The server hanging up, as the client's listen job reports it. */
        fun drop() {
            client.connectionStatus.value = ElectrumConnectionStatus.Closed(TcpSocket.IOException.ConnectionRefused())
        }

        override fun disconnect() {
            client.connectionStatus.value = ElectrumConnectionStatus.Closed(null)
        }

        override fun stop() {
            stopped = true
        }
    }

    private val random = ServerAddress("random.example", 50002, TcpSocket.TLS.TRUSTED_CERTIFICATES())
    private val onion = ServerAddress("onionaddressexample.onion", 50001, TcpSocket.TLS.DISABLED)
    private val custom = ElectrumConfig.Custom.create(ServerAddress("mine.example", 50002, TcpSocket.TLS.TRUSTED_CERTIFICATES()), requireOnionIfTorEnabled = true)

    private val network = MutableStateFlow(NetworkState.Available)
    private val tor = MutableStateFlow(false)
    private val customServer = MutableStateFlow<ElectrumConfig.Custom?>(null)
    private val link = FakeLink()

    private fun TestScope.electrum() = StandaloneElectrum(
        chain = Chain.Testnet3,
        networkState = network,
        isTorEnabled = tor,
        customServer = customServer,
        link = link,
        randomServer = { _, torIsEnabled -> if (torIsEnabled) onion else random },
        context = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `with no custom server, a random one for the network is used`() = runTest {
        val electrum = electrum().also { it.start() }
        advanceTimeBy(1.seconds)
        assertEquals(listOf(random), link.attempts.map { it.first })
        electrum.stop()
    }

    @Test
    fun `a custom server is used when one is set, and setting one reconnects`() = runTest {
        val electrum = electrum().also { it.start() }
        advanceTimeBy(1.seconds)
        customServer.value = custom
        advanceTimeBy(1.seconds)
        assertEquals(listOf(random, custom.server), link.attempts.map { it.first })
        electrum.stop()
    }

    @Test
    fun `a closed connection is retried after a pause that grows, and a connection resets it`() = runTest {
        link.succeeds = false
        val electrum = electrum().also { it.start() }
        // 0.1 + 0.25 + 0.5 + 1 seconds: four attempts, the fifth two seconds off.
        advanceTimeBy(1.9.seconds)
        // And the handshake is given longer once three have failed.
        assertEquals(listOf(4.seconds, 4.seconds, 4.seconds, 7.seconds), link.attempts.map { it.second })

        link.succeeds = true
        advanceTimeBy(2.seconds)
        assertEquals(5, link.attempts.size)

        // Connected, so the count starts again: the next drop is retried after a tenth of a second.
        link.drop()
        advanceTimeBy(0.2.seconds)
        assertEquals(6, link.attempts.size)
        electrum.stop()
    }

    @Test
    fun `losing the network disconnects, and getting it back connects`() = runTest {
        val electrum = electrum().also { it.start() }
        advanceTimeBy(1.seconds)
        network.value = NetworkState.NotAvailable
        advanceTimeBy(30.seconds)
        assertEquals(1, link.attempts.size)
        assertTrue(link.client.connectionStatus.value is ElectrumConnectionStatus.Closed)

        network.value = NetworkState.Available
        advanceTimeBy(1.seconds)
        assertEquals(2, link.attempts.size)
        electrum.stop()
    }

    @Test
    fun `with Tor on, a clearnet custom server that must be an onion is never connected to`() = runTest {
        tor.value = true
        customServer.value = custom
        val electrum = electrum().also { it.start() }
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), link.attempts)
        electrum.stop()
    }

    @Test
    fun `with Tor on and no custom server, only an onion server is tried`() = runTest {
        tor.value = true
        val electrum = electrum().also { it.start() }
        advanceTimeBy(1.seconds)
        assertEquals(listOf(onion), link.attempts.map { it.first })
        assertEquals(20.seconds, link.attempts.single().second)
        electrum.stop()
    }

    @Test
    fun `a network with no servers is not connected to, and does not crash`() = runTest {
        val electrum = StandaloneElectrum(
            chain = Chain.Testnet4,
            networkState = network,
            isTorEnabled = tor,
            customServer = customServer,
            link = link,
            context = StandardTestDispatcher(testScheduler),
        ).also { it.start() }
        advanceTimeBy(30.seconds)
        assertEquals(emptyList(), link.attempts)
        electrum.stop()
    }

    @Test
    fun `after stop nothing is retried, and the client is stopped`() = runTest {
        link.succeeds = false
        val electrum = electrum().also { it.start() }
        advanceTimeBy(0.5.seconds)
        val before = link.attempts.size
        electrum.stop()
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(before, link.attempts.size)
        assertTrue(link.stopped)
    }
}
