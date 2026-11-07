package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.data.mainnetElectrumServers
import fr.acinq.phoenix.data.mainnetElectrumServersOnion
import fr.acinq.phoenix.data.platformElectrumRegtestConf
import fr.acinq.phoenix.data.testnetElectrumServers
import fr.acinq.phoenix.data.testnetElectrumServersOnion
import fr.acinq.phoenix.managers.global.NetworkState
import fr.acinq.phoenix.utils.extensions.isOnion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An Electrum connection for a profile with no node: an nsec or an npub.
 *
 * A mnemonic profile watches the chain on its node's client, which the node's
 * `AppConnectionsDaemon` keeps connected. That daemon is built from a
 * `PhoenixBusiness` and cannot be had without one, so this is its Electrum branch
 * with the business taken out: the same server choice, the same pauses between
 * attempts, the same handshake timeouts, and the same refusal of a clearnet server
 * the user asked to reach only over Tor. See `docs/standalone-electrum.md`.
 *
 * The server comes from the identity's own preferences -- a custom server if one
 * is set, a random one for [chain] if not -- and any change to them, or to Tor, or
 * to the network, drops the connection and starts again, as the daemon does.
 *
 * One per identity, and never restarted: [stop] stops the client for good, which
 * is what `ElectrumClient.stop` means, so a profile switch makes a new one.
 */
class StandaloneElectrum(
    private val chain: Chain,
    private val networkState: Flow<NetworkState>,
    private val isTorEnabled: Flow<Boolean>,
    private val customServer: Flow<ElectrumConfig.Custom?>,
    private val link: ElectrumLink,
    private val randomServer: (Chain, Boolean) -> ServerAddress? = ::randomElectrumServer,
    context: CoroutineContext = Dispatchers.Default,
) {
    private val logger = Logger.withTag("StandaloneElectrum")
    private val scope = CoroutineScope(SupervisorJob() + context)
    private var job: Job? = null

    /** What every wallet of the identity watches on. */
    val client: IElectrumClient get() = link.client

    /** What the connection depends on: a change in any of it is a fresh start. */
    private data class Control(
        val internetIsAvailable: Boolean,
        val torIsEnabled: Boolean,
        val customServer: ElectrumConfig.Custom?,
    )

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(
                networkState.map { it == NetworkState.Available },
                isTorEnabled,
                customServer,
            ) { internet, tor, custom -> Control(internet, tor, custom) }
                .distinctUntilChanged()
                .collectLatest { control ->
                    // collectLatest has already cancelled the last loop; the socket it
                    // left open is closed here, so the new control starts from CLOSED.
                    link.disconnect()
                    if (control.internetIsAvailable) connectionLoop(control)
                }
        }
    }

    /** Stops the client for good. It cannot be started again. */
    fun stop() {
        job?.cancel()
        job = null
        link.stop()
        scope.cancel()
    }

    /**
     * Connects whenever the connection is closed, after a pause that grows with each
     * failure in a row and resets once a connection is made. The daemon's
     * `connectionLoop`, for one connection.
     */
    private suspend fun connectionLoop(control: Control) {
        var attempt = 0
        link.client.connectionStatus.map { it.toConnectionState() }.collect { state ->
            if (state is Connection.CLOSED) {
                delay(pauseBefore(attempt))
                attempt++
                connect(control, attempt)
            } else if (state == Connection.ESTABLISHED) {
                attempt = 0
            }
        }
    }

    private suspend fun connect(control: Control, attempt: Int) {
        val server = control.customServer?.server ?: randomServer(chain, control.torIsEnabled)
        if (server == null) {
            logger.w { "no Electrum server for $chain; not connecting" }
            return
        }
        // The same rule as the node's: a custom server the user wants reached only
        // through Tor is not reached at all while Tor is on and it is not an onion.
        if (control.torIsEnabled && !server.isOnion && control.customServer?.requireOnionIfTorEnabled == true) {
            logger.e { "not connecting to ${server.host}: Tor is on and the server must be an onion address" }
            return
        }
        logger.i { "connecting to ${server.host}:${server.port}, attempt $attempt" }
        try {
            link.connect(server, handshakeTimeout(attempt, control.torIsEnabled))
        } catch (e: Exception) {
            logger.e("could not connect to Electrum", e)
        }
    }

    companion object {
        /** The daemon's `connectionPause`, unchanged. */
        fun pauseBefore(attempt: Int): Duration = when {
            attempt <= 0 -> 0.1.seconds
            attempt == 1 -> 0.25.seconds
            attempt == 2 -> 0.5.seconds
            attempt == 3 -> 1.seconds
            attempt == 4 -> 2.seconds
            attempt == 5 -> 4.seconds
            else -> 8.seconds
        }

        /** The daemon's handshake timeouts: short at first for a snappy start, longer as failures add up. */
        fun handshakeTimeout(attempt: Int, torIsEnabled: Boolean): Duration = when {
            torIsEnabled && attempt <= 6 -> 20.seconds
            torIsEnabled -> 40.seconds
            attempt <= 3 -> 4.seconds
            attempt <= 6 -> 7.seconds
            attempt <= 10 -> 15.seconds
            else -> 20.seconds
        }
    }
}

/**
 * The part of an Electrum client the loop drives, beyond what the wallets read.
 *
 * A seam so the loop can be tested against a connection status the test moves,
 * with no socket. [ElectrumClientLink] is the real one.
 */
interface ElectrumLink {
    val client: IElectrumClient
    suspend fun connect(server: ServerAddress, timeout: Duration)
    fun disconnect()
    fun stop()
}

/** An [ElectrumLink] over lightning-kmp's own client, with the platform's sockets. */
class ElectrumClientLink(loggerFactory: LoggerFactory) : ElectrumLink {
    // Its own scope, as PhoenixBusiness gives the node's: the client's listen job
    // must not take the app's scope down with it, and [stop] cancels it.
    private val electrumScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val electrum = ElectrumClient(scope = electrumScope, loggerFactory = loggerFactory, pingInterval = 30.seconds, rpcTimeout = 10.seconds)

    override val client: IElectrumClient get() = electrum

    override suspend fun connect(server: ServerAddress, timeout: Duration) {
        electrum.connect(server, TcpSocket.Builder(), timeout)
    }

    override fun disconnect() = electrum.disconnect()

    override fun stop() {
        electrum.stop()
        electrumScope.cancel()
    }
}

/**
 * A random server from the hard-coded list for [chain]: `AppConfigurationManager.randomElectrumServer`,
 * which needs a business to be called. Null for the networks that list has no
 * servers for, where the manager's own answer is `TODO()`.
 */
fun randomElectrumServer(chain: Chain, isTorEnabled: Boolean): ServerAddress? = when (chain) {
    Chain.Mainnet -> if (isTorEnabled) mainnetElectrumServersOnion.random() else mainnetElectrumServers.random()
    Chain.Testnet3 -> if (isTorEnabled) testnetElectrumServersOnion.random() else testnetElectrumServers.random()
    Chain.Regtest -> platformElectrumRegtestConf()
    // The one public server known, and a group's Wavelength wallet is what asks: `docs/group-wavelength-wallet.md`.
    Chain.Signet -> ServerAddress("mempool.space", 60602, TcpSocket.TLS.TRUSTED_CERTIFICATES())
    else -> null
}
