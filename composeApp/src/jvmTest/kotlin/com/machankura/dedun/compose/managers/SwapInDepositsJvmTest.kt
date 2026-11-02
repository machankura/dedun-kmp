package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whose a spliced-in swap-in coin is (docs/group-lightning-swap-in.md, Phase 5): a Lightning deposit of
 * the coin less its share of the fee, counted to the member whose address the operator named -- once this
 * device has derived that address as theirs.
 */
class SwapInDepositsJvmTest {
    private val fixture = GroupLightningFixture(chain = Chain.Mainnet)

    @AfterTest
    fun close() = fixture.close()

    private class Setup(val all: List<GroupLightningFixture.Device>, val wallet: GroupLightningWalletManager.Wallet, val keys: GroupSwapInDerivation.Keys)

    private suspend fun setup(): Setup {
        val all = fixture.group()
        fixture.runCeremony(all)
        val lsp = PrivateKey(Random.nextBytes(32)).publicKey().toString()
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        fixture.openDeposits(all, all[1])
        fixture.openDeposits(all, all[2])
        val wallet = GroupLightningWalletManager.walletFor(all[0].db, fixture.roomId, fixture.chain)!!
        return Setup(all, wallet, GroupSwapInDerivation.keys(fixture.thresholdPublicKey, fixture.chain, 0, wallet.statement.icebergKey, wallet.statement.nodeId))
    }

    private fun txid() = Random.nextBytes(32).joinToString("") { "%02x".format(it) }

    private fun credit(s: Setup, member: GroupLightningFixture.Device, index: Long, amountSat: Long, onAddressOf: GroupLightningFixture.Device = member) =
        WalletWire.SwapInCredit(
            outPoint = "${txid()}:0",
            member = member.publicKey,
            index = index,
            amountSat = amountSat,
            script = s.keys.address(DepositBranch.of(fixture.roomId, onAddressOf.publicKey), index).protocol.serializedPubkeyScript.toHex(),
        )

    private fun spliceIn(receivedMsat: Long, vararg coins: WalletWire.SwapInCredit) =
        WalletWire.PaymentView(id = txid(), incoming = true, amountMsat = receivedMsat, feesMsat = 0, succeeded = true, swapIns = coins.toList())

    private suspend fun rowOf(s: Setup, coin: WalletWire.SwapInCredit): DepositRow {
        val (txid, vout) = coin.outPoint.split(':')
        return s.all[0].db.depositDao().get(fixture.roomId, DepositAttributionEvent.DepositKey.SwapIn(txid, vout.toLong()).text)!!
    }

    @Test
    fun `a coin is its depositor's Lightning deposit, less the splice's fee`() = runBlocking {
        val s = setup()
        val coin = credit(s, s.all[1], 0, 50_000)
        DepositLedger.recordSwapIns(s.all[0].db, fixture.roomId, s.wallet, listOf(spliceIn(49_700_000, coin)))

        val row = rowOf(s, coin)
        assertEquals(DepositRow.LIGHTNING, row.source)
        assertEquals(49_700_000, row.amountMsat)
        assertEquals(s.all[1].publicKey.lowercase(), row.member)
        assertEquals(DepositRow.BY_SWAP_IN, row.attributedBy)
        assertEquals(49_700_000, DepositLedger.tallies(s.all[0].db, fixture.roomId, fixture.chain).members.getValue(s.all[1].publicKey.lowercase()).lightningMsat)
    }

    @Test
    fun `two members' coins in one splice share its fee by amount, and the shares add up to the fee`() = runBlocking {
        val s = setup()
        val small = credit(s, s.all[1], 0, 30_000)
        val large = credit(s, s.all[2], 4, 70_000)
        // 1,000 sats of fee: 300 on the 30,000 and 700 on the 70,000.
        DepositLedger.recordSwapIns(s.all[0].db, fixture.roomId, s.wallet, listOf(spliceIn(99_000_000, small, large)))
        assertEquals(29_700_000, rowOf(s, small).amountMsat)
        assertEquals(69_300_000, rowOf(s, large).amountMsat)
        assertEquals(listOf(333L, 333L, 334L), DepositLedger.feeShares(listOf(1L, 1L, 1L), 1_000))
    }

    @Test
    fun `a coin named as one member's but on another's address is nobody's`() = runBlocking {
        val s = setup()
        val misnamed = credit(s, s.all[1], 0, 50_000, onAddressOf = s.all[2])
        DepositLedger.recordSwapIns(s.all[0].db, fixture.roomId, s.wallet, listOf(spliceIn(49_700_000, misnamed)))
        assertNull(rowOf(s, misnamed).member)
        assertNull(rowOf(s, misnamed).attributedBy)
    }

    @Test
    fun `a coin named as a member who is no depositor is nobody's`() = runBlocking {
        val s = setup()
        // Device 3 has no depositor statement, so it has no swap-in addresses to have been paid on.
        val coin = credit(s, s.all[3], 0, 50_000)
        DepositLedger.recordSwapIns(s.all[0].db, fixture.roomId, s.wallet, listOf(spliceIn(49_700_000, coin)))
        assertNull(rowOf(s, coin).member)
    }

    @Test
    fun `a swap-in deposit's key reads back as itself`() {
        val key = DepositAttributionEvent.DepositKey.SwapIn("ab".repeat(32), 3)
        assertEquals(key, DepositAttributionEvent.DepositKey.parse(key.text))
        assertEquals(DepositAttributionEvent.DepositKey.Onchain("ab".repeat(32), 3), DepositAttributionEvent.DepositKey.parse("ab".repeat(32) + ":3"))
    }
}
