package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent.Coin
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The coin assignment (30341), read and believed -- one mutation per check.
 * `docs/payouts-only.md`, Phase 1.
 */
class CoinAssignmentEventJvmTest {
    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private val other = keyMaterial("9a9e")

    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val coin = DepositKey.Onchain("c0".repeat(32), 1)
    private val second = DepositKey.Onchain("c1".repeat(32), 0)

    private fun signed(tags: Array<Array<String>>, createdAt: Long = 1_700_000_100, by: KeyMaterial = group): Event =
        signedAsRoom(by, CoinAssignmentEvent.KIND, tags, "", createdAt)

    private fun tags(vararg coins: Coin) = CoinAssignmentEvent.template(chain, coins.toList(), 1_700_000_100).tags

    private val split = Coin(coin, 100_000, mapOf(alice to 60_000, bob to 40_000))

    @Test
    fun `an assignment reads and is believed, a coin in shares`() {
        val read = assertNotNull(CoinAssignmentEvent.verify(signed(tags(split)), room))
        assertEquals(chain, read.chain)
        assertEquals(listOf(split), read.coins)
    }

    @Test
    fun `an assignment another room signed is not this room's`() {
        assertNull(CoinAssignmentEvent.verify(signed(tags(split), by = other), room))
    }

    @Test
    fun `shares that don't add up to the coin are refused`() {
        val tags = tags(split).map { if (it[0] == "coin") arrayOf("coin", coin.text, "100001") else it }.toTypedArray()
        assertNull(CoinAssignmentEvent.read(signed(tags)))
    }

    @Test
    fun `a share below dust, a member twice, a group share, or a share of an unlisted coin is refused`() {
        val base = tags(split)
        val belowDust = base.map { if (it[0] == "share" && it[2] == bob) arrayOf("share", coin.text, bob, "500") else it }
            .map { if (it[0] == "coin") arrayOf("coin", coin.text, "60500") else it }.toTypedArray()
        assertNull(CoinAssignmentEvent.read(signed(belowDust)))

        val twice = base.map { if (it[0] == "share" && it[2] == bob) arrayOf("share", coin.text, alice, "40000") else it }.toTypedArray()
        assertNull(CoinAssignmentEvent.read(signed(twice)))

        val group = base.map { if (it[0] == "share" && it[2] == bob) arrayOf("share", coin.text, "group", "40000") else it }.toTypedArray()
        assertNull(CoinAssignmentEvent.read(signed(group)))

        val stray = base + arrayOf(arrayOf("share", second.text, bob, "1000"))
        assertNull(CoinAssignmentEvent.read(signed(stray)))
    }

    @Test
    fun `a coin with no shares, a coin twice, or a lightning key is refused`() {
        val base = tags(split)
        assertNull(CoinAssignmentEvent.read(signed(base.filter { it[0] != "share" }.toTypedArray())))
        assertNull(CoinAssignmentEvent.read(signed(base + base.filter { it[0] != "chain" })))
        val lightning = base.map { if (it.size > 1 && it[1] == coin.text) it.copyOf().also { tag -> tag[1] = "ln:" + "ab".repeat(32) } else it }.toTypedArray()
        assertNull(CoinAssignmentEvent.read(signed(lightning)))
    }

    @Test
    fun `the template refuses what read would`() {
        assertFailsWith<IllegalArgumentException> { tags(Coin(coin, 100_000, mapOf(alice to 99_999))) }
        assertFailsWith<IllegalArgumentException> { tags(Coin(coin, 100_000, mapOf(alice to 99_600, bob to 400))) }
        assertFailsWith<IllegalArgumentException> { tags(split, split) }
    }

    @Test
    fun `the newest assignment of a coin wins, and another network's is not read`() {
        val first = signed(tags(split), createdAt = 1_700_000_100)
        val correction = Coin(coin, 100_000, mapOf(alice to 100_000))
        val later = signed(CoinAssignmentEvent.template(chain, listOf(correction, Coin(second, 5_000, mapOf(bob to 5_000))), 1_700_000_200).tags, createdAt = 1_700_000_200)
        val elsewhere = signedAsRoom(group, CoinAssignmentEvent.KIND, CoinAssignmentEvent.template(Chain.Signet, listOf(split), 1_700_000_300).tags, "", 1_700_000_300)

        val newest = CoinAssignmentEvent.newestPerCoin(listOf(later, first, elsewhere), room, chain)
        assertEquals(correction, newest[coin])
        assertEquals(mapOf(bob to 5_000L), newest[second]?.shares)
    }
}
