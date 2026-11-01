package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ServerVersionResponse
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.managers.global.NetworkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * One Electrum connection per network: the node's own is used as it is, any other is made once, on
 * first use, and closed with the identity.
 */
class GroupChainClientsJvmTest {
    @Test
    fun `the node's network uses the node's client and connects nothing`() = runBlocking<Unit> {
        val node = FakeElectrum()
        val made = mutableListOf<Chain>()
        val clients = GroupChainClients(Chain.Mainnet, node) { chain -> made += chain; GroupChainClients.Connection(FakeElectrum()) {} }
        assertSame(node, clients.clientFor(Chain.Mainnet))
        assertEquals(emptyList(), made)
    }

    @Test
    fun `another network gets one connection of its own, reused, and closed on stop`() = runBlocking<Unit> {
        val node = FakeElectrum()
        val made = mutableListOf<Chain>()
        var closed = 0
        val clients = GroupChainClients(Chain.Mainnet, node) { chain -> made += chain; GroupChainClients.Connection(FakeElectrum()) { closed++ } }
        val first = clients.clientFor(Chain.Testnet3)
        assertSame(first, clients.clientFor(Chain.Testnet3))
        assertEquals(listOf<Chain>(Chain.Testnet3), made)
        clients.stop()
        assertEquals(1, closed)
        // A wallet asked for after a stop -- a profile switched back -- connects again.
        clients.clientFor(Chain.Testnet3)
        assertEquals(listOf<Chain>(Chain.Testnet3, Chain.Testnet3), made)
    }

    /** A link that only records where the loop sends it. */
    private class RecordingLink : ElectrumLink {
        override val client = FakeElectrum()
        val servers = mutableListOf<ServerAddress>()
        var stopped = false
        override suspend fun connect(server: ServerAddress, timeout: Duration) {
            servers += server
            client.connectionStatus.value = ElectrumConnectionStatus.Connected(ServerVersionResponse("fake", "1.4"), 0, Block.RegtestGenesisBlock.header)
        }
        override fun disconnect() { client.connectionStatus.value = ElectrumConnectionStatus.Closed(null) }
        override fun stop() { stopped = true }
    }

    @Test
    fun `another network's connection is the identity's own loop, so it keeps to Tor`() = runTest {
        val onion = ServerAddress("testnetexample.onion", 50001, TcpSocket.TLS.DISABLED)
        val clearnet = ServerAddress("testnet.example", 50002, TcpSocket.TLS.TRUSTED_CERTIFICATES())
        val asked = mutableListOf<Pair<Chain, Boolean>>()
        val link = RecordingLink()
        val node = FakeElectrum()
        val clients = GroupChainClients.forNode(Chain.Mainnet, node) { chain ->
            StandaloneElectrum(
                chain = chain,
                networkState = MutableStateFlow(NetworkState.Available),
                isTorEnabled = MutableStateFlow(true),
                customServer = MutableStateFlow<ElectrumConfig.Custom?>(null),
                link = link,
                randomServer = { c, tor -> asked += c to tor; if (tor) onion else clearnet },
                context = StandardTestDispatcher(testScheduler),
            )
        }
        assertSame(node, clients.clientFor(Chain.Mainnet), "the node's own network is the node's client")
        assertSame(link.client, clients.clientFor(Chain.Testnet3))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf<Pair<Chain, Boolean>>(Chain.Testnet3 to true), asked.toList())
        assertEquals(listOf(onion), link.servers, "with Tor on, the testnet3 connection goes to an onion server")
        clients.stop()
        assertTrue(link.stopped, "stopping the identity's clients stops the standalone one")
    }
}
