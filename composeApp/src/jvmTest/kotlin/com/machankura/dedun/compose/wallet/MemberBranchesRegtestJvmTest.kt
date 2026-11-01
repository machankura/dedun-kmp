package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Spike C of `docs/member-deposits.md`: a group of ten depositors watches twelve branches, each
 * with a lookahead of ten -- about 120 script-hash subscriptions on one connection. This measures
 * how long electrs takes to answer them all, at once, as the wallets do when they start.
 *
 * Skipped unless `DEDUN_REGTEST=1`, against `testing/regtest/regtest.sh up`.
 */
class MemberBranchesRegtestJvmTest {
    private val enabled = System.getenv("DEDUN_REGTEST") == "1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loggerFactory = LoggerFactory(StaticConfig(Severity.Warn, listOf(platformLogWriter())))

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun twelveBranchesOfTenAreAnsweredQuickly() = runBlocking {
        assumeTrue("set DEDUN_REGTEST=1 with testing/regtest/regtest.sh up", enabled)
        val electrum = ElectrumClient(scope, loggerFactory)
        check(electrum.connect(ServerAddress("127.0.0.1", 28001, TcpSocket.TLS.DISABLED), TcpSocket.Builder())) {
            "electrs is not reachable: testing/regtest/regtest.sh up"
        }
        val scriptHashes: List<ByteVector32> = (0 until 12 * 10).map { Crypto.sha256(("spike-c-$it").encodeToByteArray()).byteVector32() }

        val took = measureTime {
            withTimeout(60.seconds) {
                scriptHashes.map { hash -> async { electrum.startScriptHashSubscription(hash) } }.awaitAll()
            }
        }
        println("Spike C: ${scriptHashes.size} subscriptions answered in $took")
        assertTrue(took < 10.seconds, "120 subscriptions took $took")
        electrum.disconnect()
    }
}
