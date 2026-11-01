package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.PeerConnected
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.channel.FundingContributions
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenix.data.mainnetElectrumServers
import fr.acinq.phoenix.db.createChannelsDbDriver
import fr.acinq.phoenix.db.createPaymentsDbDriver
import fr.acinq.phoenix.db.createSqliteChannelsDb
import fr.acinq.phoenix.db.createSqlitePaymentsDb
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.PhoenixDatabases
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenixd.conf.LSP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 0 of `docs/personal-swap-in-wallet.md`: what the personal node's swap-in wallet does on
 * mainnet, and what a first deposit would cost there today.
 *
 * A throwaway node -- a random seed, never written anywhere -- connects to ACINQ's LSP and to a public
 * Electrum server, prints its first swap-in address and the LSP's funding rates, and works out the fee a
 * channel-opening swap-in of three sizes would be charged, the way `Peer` works it out. It opens nothing.
 *
 * Skipped unless `DEDUN_MAINNET_SPIKE=1`, because it reaches the real network.
 */
class SwapInMainnetSpikeJvmTest {
    private val loggerFactory = LoggerFactory(StaticConfig(Severity.Warn, listOf(platformLogWriter())))

    @Test
    fun aFirstDepositOnMainnet() = runBlocking {
        assumeTrue("set DEDUN_MAINNET_SPIKE=1 to reach mainnet", System.getenv("DEDUN_MAINNET_SPIKE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lsp = LSP.from(Chain.Mainnet)
            val keyManager = LocalKeyManager(randomBytes32(), Chain.Mainnet, lsp.swapInXpub)
            val nodeParams = NodeParams(Chain.Mainnet, loggerFactory, keyManager).copy(
                zeroConfPeers = setOf(lsp.walletParams.trampolineNode.id),
                liquidityPolicy = kotlinx.coroutines.flow.MutableStateFlow(NodeParamsManager.defaultLiquidityPolicy),
            )
            val electrum = ElectrumClient(scope, loggerFactory)
            val server = mainnetElectrumServers.shuffled().first { electrum.connect(it, TcpSocket.Builder(), 20.seconds) }
            println("SPIKE electrum ${server.host}")
            val ctx = PlatformContext(Files.createTempDirectory("dedun-swap-in-spike").toFile())
            val databases = PhoenixDatabases(
                channels = createSqliteChannelsDb(createChannelsDbDriver(ctx, "channels.sqlite")),
                payments = createSqlitePaymentsDb(createPaymentsDbDriver(ctx, "payments.sqlite") {}, null, loggerFactory),
                cloudKit = null,
            )
            val peer = Peer(nodeParams, lsp.walletParams, electrum, ElectrumWatcher(electrum, scope, loggerFactory), databases, TcpSocket.Builder(), scope)
            scope.launch { runCatching { peer.currentTipFlow.value = electrum.startHeaderSubscription().blockHeight } }

            // 1. The swap-in wallet emits once Electrum has answered for its addresses.
            val (address, index) = withTimeout(2.minutes) { peer.swapInWallet!!.swapInAddressFlow.filterNotNull().first() }
            println("SPIKE swap-in address index=$index address=$address legacy=${peer.swapInWallet!!.legacySwapInAddress}")
            val state = peer.swapInWallet!!.wallet.walletStateFlow.value
            println("SPIKE wallet addresses watched=${state.addresses.size} derived=${state.addresses.values.count { it.meta is WalletState.AddressMeta.Derived }}")

            // 2. What the LSP charges for the channel a first deposit opens.
            val connected = scope.launch { nodeParams.nodeEvents.filterIsInstance<PeerConnected>().first() }
            check(peer.connect(connectTimeout = 20.seconds, handshakeTimeout = 20.seconds)) { "could not reach the LSP" }
            withTimeout(1.minutes) { connected.join() }
            val feerates = withTimeout(1.minutes) { peer.peerFeeratesFlow.filterNotNull().first() }
            val rates = withTimeout(1.minutes) { peer.remoteFundingRates.filterNotNull().first() }
            println("SPIKE funding feerate=${feerates.fundingFeerate} (${feerates.fundingFeerate.toLong() * 4 / 1000} sat/vB)")
            rates.fundingRates.forEach { println("SPIKE rate $it") }
            val rate = rates.findRate(1.sat)
            println("SPIKE rate for a 1 sat purchase: $rate")
            if (rate != null) {
                val policy = NodeParamsManager.defaultLiquidityPolicy
                for (amount in listOf(20_000.sat, 50_000.sat, 100_000.sat, 1_000_000.sat)) {
                    val utxo = utxoPaying(address, amount)
                    val dummyFundingScript = Script.write(Scripts.multiSig2of2(randomKey().publicKey(), randomKey().publicKey())).byteVector()
                    val weight = FundingContributions.computeWeightPaid(isInitiator = true, null, dummyFundingScript, listOf(utxo), emptyList())
                    val localMiningFee = Transactions.weight2fee(feerates.fundingFeerate, weight)
                    val fundingFees = LiquidityAds.RequestFunding(1.sat, rate, LiquidityAds.PaymentDetails.FromChannelBalance).fees(feerates.fundingFeerate, isChannelCreation = true)
                    val total = localMiningFee + fundingFees.miningFee + fundingFees.serviceFee
                    val accepted = total <= policy.maxAbsoluteFee && total.toLong() * 10_000 <= amount.toLong() * policy.maxRelativeFeeBasisPoints
                    println("SPIKE open with $amount: our mining=$localMiningFee theirs mining=${fundingFees.miningFee} service=${fundingFees.serviceFee} total=$total default policy accepts=$accepted")
                }
            }
            peer.disconnect()
        } finally {
            scope.cancel()
        }
    }

    /** A coin on [address] that nobody has seen: only its shape matters to the fee, and the fee is all this computes. */
    private fun utxoPaying(address: String, amount: fr.acinq.bitcoin.Satoshi): WalletState.Utxo {
        val script = fr.acinq.bitcoin.Bitcoin.addressToPublicKeyScript(Chain.Mainnet.chainHash, address).right!!
        val previousTx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(amount, script)), 0)
        return WalletState.Utxo(previousTx.txid, 0, 900_000, previousTx, WalletState.AddressMeta.Derived(0))
    }
}
