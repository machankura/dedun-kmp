package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent.CoinShare
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What signed transfers do to balances: the ledger rails' nets, a coin's shares, and the coins an onchain transfer names.
 * `docs/member-transfers.md`, Phase 1. The transfers here are built already read, since what is pure about them is the arithmetic.
 */
class TransferLedgerTest {
    private val chain = Chain.Regtest
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val carol = "ca".repeat(32)
    private val coin = DepositKey.Onchain("c0".repeat(32), 0)
    private val second = DepositKey.Onchain("c1".repeat(32), 0)
    private val third = DepositKey.Onchain("c2".repeat(32), 0)

    private var counter = 0

    /** Enough for every transfer below to be covered, unless a test says otherwise. */
    private val plenty = mapOf(alice to 1_000_000L, bob to 1_000_000L, carol to 1_000_000L)

    private fun transfer(
        from: String, to: String, amount: Long, rail: Rail = Rail.WAVELENGTH, coins: List<CoinShare> = emptyList(), on: Chain = chain,
        askedAt: Long? = null, signedAt: Long? = null,
    ): MemberTransferEvent.Transfer {
        val n = counter++
        val asked = askedAt ?: (1_700_000_000L + n)
        val requestEvent = Event(n.toString(16).padStart(64, '0'), from, asked, TransferRequestEvent.KIND, emptyArray(), "", "")
        val request = TransferRequestEvent.Request(from, "00".repeat(32), on, rail, to, amount, asked + 86_400, null, requestEvent)
        val event = Event((n + 1000).toString(16).padStart(64, '0'), "00".repeat(32), signedAt ?: asked, MemberTransferEvent.KIND, emptyArray(), "", "")
        return MemberTransferEvent.Transfer(on, rail, from, to, amount, request, coins, event)
    }

    @Test
    fun `a net is what was received less what was given, on one rail and chain`() {
        val transfers = listOf(
            transfer(alice, bob, 20_000),
            transfer(bob, carol, 5_000),
            transfer(alice, bob, 1_000, rail = Rail.LIGHTNING),
            transfer(alice, bob, 9_000, on = Chain.Signet),
        )
        val nets = TransferLedger.netsOf(transfers, chain, Rail.WAVELENGTH, plenty)
        assertEquals(-20_000, nets[alice]?.sat)
        assertEquals(TransferLedger.Net(20_000, 5_000), nets[bob])
        assertEquals(5_000, nets[carol]?.sat)
        assertEquals(0, nets.values.sumOf { it.sat })
        assertEquals(TransferLedger.Net(1_000, 0), TransferLedger.netsOf(transfers, chain, Rail.LIGHTNING, plenty)[bob])
    }

    @Test
    fun `two transfers give the same nets whichever came first`() {
        val one = transfer(alice, bob, 3_000)
        val two = transfer(carol, alice, 7_000)
        assertEquals(TransferLedger.netsOf(listOf(one, two), chain, Rail.WAVELENGTH, plenty), TransferLedger.netsOf(listOf(two, one), chain, Rail.WAVELENGTH, plenty))
    }

    @Test
    fun `a transfer its giver can't cover counts for nothing, and nobody else pays for it`() {
        val capacity = mapOf(alice to 10_000L)
        val over = transfer(alice, bob, 10_001)
        assertEquals(emptyMap(), TransferLedger.netsOf(listOf(over), chain, Rail.WAVELENGTH, capacity))
        // Two that each fit, and together don't: the first the group signed counts, the second is void.
        val first = transfer(alice, bob, 7_000)
        val second = transfer(alice, carol, 7_000)
        val nets = TransferLedger.netsOf(listOf(second, first), chain, Rail.WAVELENGTH, capacity)
        assertEquals(-7_000, nets[alice]?.sat)
        assertNull(nets[carol])
    }

    @Test
    fun `what a member was given can be given on, in order`() {
        val capacity = mapOf(alice to 10_000L)
        val ab = transfer(alice, bob, 10_000)
        val bc = transfer(bob, carol, 4_000)
        assertEquals(4_000, TransferLedger.netsOf(listOf(ab, bc), chain, Rail.WAVELENGTH, capacity)[carol]?.sat)
        assertEquals(6_000, TransferLedger.capacityAfter(listOf(ab, bc), chain, Rail.WAVELENGTH, capacity, bob))
        assertEquals(0, TransferLedger.capacityAfter(listOf(ab, bc), chain, Rail.WAVELENGTH, capacity, alice))
    }

    @Test
    fun `a transfer the group signed before its giver asked, or after the request ran out, counts for nothing`() {
        val early = transfer(alice, bob, 1_000, askedAt = 1_700_000_000, signedAt = 1_699_999_999)
        val late = transfer(alice, bob, 1_000, askedAt = 1_700_000_000, signedAt = 1_700_000_000 + 86_401)
        val inTime = transfer(alice, bob, 1_000, askedAt = 1_700_000_000, signedAt = 1_700_000_000 + 86_400)
        assertEquals(1_000, TransferLedger.netsOf(listOf(early, late, inTime), chain, Rail.WAVELENGTH, plenty)[bob]?.sat)
    }

    @Test
    fun `a whole coin changes owner, and a split makes it shared`() {
        val whole = transfer(alice, bob, 40_000, Rail.ONCHAIN, listOf(CoinShare(coin, 40_000)))
        assertEquals(mapOf(bob to 40_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(whole)))
        val part = transfer(alice, bob, 15_000, Rail.ONCHAIN, listOf(CoinShare(coin, 15_000)))
        assertEquals(mapOf(alice to 25_000L, bob to 15_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(part)))
    }

    @Test
    fun `a coin passes along a chain of transfers, and one naming another coin leaves it alone`() {
        val ab = transfer(alice, bob, 15_000, Rail.ONCHAIN, listOf(CoinShare(coin, 15_000)))
        val bc = transfer(bob, carol, 10_000, Rail.ONCHAIN, listOf(CoinShare(coin, 10_000)))
        val elsewhere = transfer(alice, carol, 5_000, Rail.ONCHAIN, listOf(CoinShare(second, 5_000)))
        assertEquals(mapOf(alice to 25_000L, bob to 5_000L, carol to 10_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(ab, bc, elsewhere)))
    }

    @Test
    fun `a transfer whose giver holds too little of the coin, or would keep less than dust, is void`() {
        val tooMuch = transfer(bob, carol, 10_000, Rail.ONCHAIN, listOf(CoinShare(coin, 10_000)))
        assertEquals(mapOf(alice to 40_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(tooMuch)))
        val belowDust = transfer(alice, bob, 39_600, Rail.ONCHAIN, listOf(CoinShare(coin, 39_600)))
        assertEquals(mapOf(alice to 40_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(belowDust)))
        // And a later one still applies after a void one.
        val fine = transfer(alice, carol, 1_000, Rail.ONCHAIN, listOf(CoinShare(coin, 1_000)))
        assertEquals(mapOf(alice to 39_000L, carol to 1_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(belowDust, fine)))
    }

    @Test
    fun `a ledger rail's transfer never touches a coin`() {
        val wavelength = transfer(alice, bob, 40_000)
        assertEquals(mapOf(alice to 40_000L), TransferLedger.applyToCoin(coin, mapOf(alice to 40_000L), listOf(wavelength)))
    }

    @Test
    fun `whole coins first, then at most one split`() {
        val held = mapOf(coin to 40_000L, second to 10_000L, third to 3_000L)
        assertEquals(listOf(CoinShare(second, 10_000)), TransferLedger.chooseCoins(held, 10_000))
        assertEquals(listOf(CoinShare(coin, 40_000), CoinShare(second, 10_000)), TransferLedger.chooseCoins(held, 50_000))
        // 45,000: 40,000 and 3,000 whole, and the last 2,000 split from the 10,000 -- still one shared coin.
        assertEquals(listOf(CoinShare(coin, 40_000), CoinShare(second, 2_000), CoinShare(third, 3_000)), TransferLedger.chooseCoins(held, 45_000))
        // 12,000: the 10,000 coin whole, then 2,000 split from the smallest coin that leaves at least dust behind: the 3,000.
        assertEquals(listOf(CoinShare(second, 10_000), CoinShare(third, 2_000)), TransferLedger.chooseCoins(held, 12_000))
        // 12,600: the 3,000 would keep 400, below dust, so the 40,000 is split instead.
        assertEquals(listOf(CoinShare(coin, 2_600), CoinShare(second, 10_000)), TransferLedger.chooseCoins(held, 12_600))
    }

    @Test
    fun `nothing is chosen for more than is held, below dust, or where no split leaves dust on both sides`() {
        assertNull(TransferLedger.chooseCoins(mapOf(coin to 10_000L), 10_001))
        assertNull(TransferLedger.chooseCoins(mapOf(coin to 10_000L), 545))
        assertNull(TransferLedger.chooseCoins(mapOf(coin to 10_000L), 9_600))
        assertNull(TransferLedger.chooseCoins(emptyMap(), 1_000))
    }
}
