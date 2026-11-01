package com.machankura.dedun.compose.wallet

import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Raising the personal node's fee limit, once or for good (docs/personal-swap-in-wallet.md, Phase 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapInFeeLimitJvmTest {
    private val saved = LiquidityPolicy.Auto(null, 5_000.sat, 50_00, skipAbsoluteFeeCheck = false, maxAllowedFeeCredit = 0.msat)

    // ---- the rules ----

    @Test
    fun `the margin is a tenth of the fee, rounded up`() {
        assertEquals(6_820.sat, SwapInFeeLimits.withMargin(6_200.sat))
        assertEquals(2.sat, SwapInFeeLimits.withMargin(1.sat))
    }

    @Test
    fun `a fee over the cap raises the cap, and nothing else`() {
        val raised = SwapInFeeLimits.allowingOnce(saved, SwapInRefusal.OverLimit(6_200.sat, 5_000.sat, 50_000.sat))!!
        assertEquals(saved.copy(maxAbsoluteFee = 6_820.sat), raised)
    }

    @Test
    fun `a fee over the share raises the share, and the cap if it would refuse it next`() {
        // 6,820 of 10,000 sats is 68.2 %, rounded up to 6,820 basis points.
        val raised = SwapInFeeLimits.allowingOnce(saved, SwapInRefusal.OverShare(6_200.sat, 10_000.sat, 50_00))!!
        assertEquals(6_820, raised.maxRelativeFeeBasisPoints)
        assertEquals(6_820.sat, raised.maxAbsoluteFee)
        // A small fee over the share leaves a cap that is already higher where it was.
        val small = SwapInFeeLimits.allowingOnce(saved, SwapInRefusal.OverShare(1_287.sat, 2_000.sat, 50_00))!!
        assertEquals(5_000.sat, small.maxAbsoluteFee)
        assertEquals(7_080, small.maxRelativeFeeBasisPoints) // 1,416 of 2,000
    }

    @Test
    fun `no fee gets past a policy that is off, or a reason that is not the fee`() {
        assertNull(SwapInFeeLimits.allowingOnce(saved, SwapInRefusal.Disabled))
        assertNull(SwapInFeeLimits.allowingOnce(saved, SwapInRefusal.Other))
    }

    // ---- over a node ----

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class FakeNode(saved: LiquidityPolicy) {
        val running = MutableStateFlow<LiquidityPolicy>(saved)
        val stored = MutableStateFlow(saved)
        val accepted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    private fun limitOver(node: FakeNode) = NodeFeeLimit(
        apply = { node.running.value = it },
        stored = node.stored,
        store = { node.stored.value = it },
        accepted = node.accepted,
        scope = scope,
    )

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun `a fee allowed once runs until the next swap-in is accepted, and is never stored`() = runBlocking {
        val node = FakeNode(saved)
        val limit = limitOver(node)
        val raised = saved.copy(maxAbsoluteFee = 6_820.sat)

        limit.allowOnce(raised)
        assertEquals(raised, node.running.value)
        assertEquals(saved, node.stored.value, "a restart starts from what was stored")

        // The put-back is listening by the time allowOnce returns its launch; give it a turn to subscribe.
        eventually { while (node.accepted.subscriptionCount.value == 0) yield() }
        node.accepted.emit(Unit)
        eventually { node.running.first { it == saved } }
        Unit
    }

    @Test
    fun `a saved limit is stored and run, and a pending put-back no longer undoes it`() = runBlocking {
        val node = FakeNode(saved)
        val limit = limitOver(node)
        limit.allowOnce(saved.copy(maxAbsoluteFee = 6_820.sat))
        val higher = saved.copy(maxAbsoluteFee = 8_000.sat)

        limit.save(higher)
        assertEquals(higher, node.stored.value)
        assertEquals(higher, node.running.value)

        node.accepted.tryEmit(Unit)
        yield()
        assertEquals(higher, node.running.value)
        assertEquals(higher, limit.saved())
    }
}
