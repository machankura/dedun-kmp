package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.wallet.UnusedAddressCheck.Usage
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.ScriptHashSubscriptionResponse
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * An onchain payout address, checked: never paid, paid in the mempool, paid and confirmed, no
 * server, and the group's own. `docs/group-payout-destination.md`, Phase 3; the same four
 * answers against a real electrs are `PayoutAddressRegtestJvmTest`'s.
 */
class UnusedAddressCheckJvmTest {
    private fun scriptOf(seed: String): ByteVector =
        ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0')))).publicKey())))

    private val fresh = scriptOf("f1")
    private val inMempool = scriptOf("f2")
    private val confirmed = scriptOf("f3")

    @Test
    fun `a script nobody has paid is unused, and a paid one used`(): Unit = runBlocking {
        val electrum = FakeElectrum()
        electrum.pay(inMempool, Satoshi(10_000), height = 0)
        electrum.pay(confirmed, Satoshi(10_000), height = 100)

        assertEquals(Usage.Unused, UnusedAddressCheck.check(electrum, fresh))
        assertEquals(Usage.Used, UnusedAddressCheck.check(electrum, inMempool))
        assertEquals(Usage.Used, UnusedAddressCheck.check(electrum, confirmed))
    }

    @Test
    fun `a spent script is still used`(): Unit = runBlocking {
        val electrum = FakeElectrum()
        electrum.pay(confirmed, Satoshi(10_000))
        electrum.spend(confirmed)
        assertEquals(Usage.Used, UnusedAddressCheck.check(electrum, confirmed))
    }

    @Test
    fun `no answer is unknown, never unused`(): Unit = runBlocking {
        val silent = object : IElectrumClient by FakeElectrum() {
            override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse = awaitCancellation()
        }
        assertEquals(Usage.Unknown, UnusedAddressCheck.check(silent, fresh, timeout = 200.milliseconds))
    }

    @Test
    fun `a server that throws is unknown too`(): Unit = runBlocking {
        val failing = object : IElectrumClient by FakeElectrum() {
            override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse = error("disconnected")
        }
        assertEquals(Usage.Unknown, UnusedAddressCheck.check(failing, fresh))
    }

    @Test
    fun `the group's own address is refused without asking`(): Unit = runBlocking {
        val electrum = FakeElectrum()
        assertEquals(Usage.TheGroups, UnusedAddressCheck.check(electrum, fresh, isTheGroups = { it == fresh }))
        assertEquals(emptySet(), electrum.subscribed)
    }
}
