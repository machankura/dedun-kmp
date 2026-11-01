package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.managers.LineageExample
import com.machankura.dedun.compose.managers.LineageExample.heldBy
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What an ancestor's wallet screen says about a descendant's guarded wallet: a
 * funding is *arriving* until the leaf's lock lets it go, and then *within
 * reach* -- never neither, which would make the money look gone.
 */
class GuardedWalletRowTest {
    private val descendantRoomId = "c".repeat(64)
    /** Guarded by the ancestor below, at the default delay, as a verified statement would be. */
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("decaf".padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
        guardians = GuardianDerivation.grant(PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0')))).publicKey().toString(), "d".repeat(64), emptyList(), descendantRoomId),
    )
    private val wallet = GuardianManager.GuardedWallet(
        ancestorChatRoomId = "d".repeat(64),
        descendantChatRoomId = descendantRoomId,
        statement = Event("1".repeat(64), descendantRoomId, 1_700_000_000, GroupWalletStatementEvent.KIND, emptyArray(), "", ""),
        account = account,
        certificate = Event("2".repeat(64), "d".repeat(64), 1_700_000_000, 0, emptyArray(), "", ""),
        lineage = listOf(descendantRoomId),
    )

    /** One coin of 50,000 sats at the first receive address, confirmed at [height] (0 in the mempool), with the tip at [tip]. */
    private fun holding(height: Long, tip: Int): GroupWalletState {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 7 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(50_000), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(0)
        return GroupWalletState(
            account,
            WalletState(mapOf(derived.address(Chain.Regtest) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(tx.txid, 0, height, tx, meta))))),
            WalletState.empty,
            tipHeight = tip,
        )
    }

    @Test
    fun `a coin is arriving until the lock lets it go, then within reach`() {
        val lock = GuardianDerivation.DEFAULT_DELAY.toInt()

        val mempool = GuardedWalletRow(wallet, holding(height = 0, tip = 100))
        assertEquals(Satoshi(50_000), mempool.arriving)
        assertEquals(Satoshi(0), mempool.withinReach)

        val young = GuardedWalletRow(wallet, holding(height = 100, tip = 100 + lock - 2))
        assertEquals(Satoshi(50_000), young.arriving, "${lock - 1} confirmations")
        assertEquals(Satoshi(0), young.withinReach)

        val old = GuardedWalletRow(wallet, holding(height = 100, tip = 100 + lock - 1))
        assertEquals(Satoshi(0), old.arriving, "$lock confirmations")
        assertEquals(Satoshi(50_000), old.withinReach)
    }

    @Test
    fun `nothing is said before the chain is known`() {
        val row = GuardedWalletRow(wallet, walletState = null)
        assertNull(row.arriving)
        assertNull(row.withinReach)
    }

    // ---- By person: docs/member-wallet-guarding-group.md, Phase 4 ---------

    private val r = LineageExample.r
    private val savingsAtR = GuardedWalletRow(heldBy(r, LineageExample.savings, LineageExample.savingsGuardians, label = "Savings", guardianName = "#M"), walletState = null)
    private val rentAtR = GuardedWalletRow(heldBy(r, LineageExample.rent, LineageExample.rentGuardians, label = "Rent", guardianName = "#N"), walletState = null)

    @Test
    fun `a member's two wallets under two groups are one person, the nearer first`() {
        val people = listOf(savingsAtR, rentAtR).byPerson()

        val alice = people.single()
        assertEquals(LineageExample.alice, alice.ownerPublicKey)
        assertEquals("alice", alice.ownerName)
        assertEquals(2, alice.guardianCount)
        assertEquals(listOf("Rent", "Savings"), alice.rows.map { (it.wallet.subject as GuardianManager.GuardedSubject.Member).label })
    }

    @Test
    fun `two wallets under one group count one guardian`() {
        // A second wallet N guards for her: the guardians name the same rooms.
        val holiday = GuardedWalletRow(heldBy(r, "9".repeat(64), LineageExample.rentGuardians, label = "Holiday"), walletState = null)
        val alice = listOf(rentAtR, holiday).byPerson().single()
        assertEquals(1, alice.guardianCount)
    }

    @Test
    fun `a subgroup's wallet is not a person`() {
        assertEquals(emptyList(), listOf(GuardedWalletRow(wallet, walletState = null)).byPerson())
    }

    @Test
    fun `people sort by name, and by key where there is none`() {
        val bob = GuardedWalletRow(heldBy(r, "8".repeat(64), LineageExample.rentGuardians, ownerPublicKey = "b".repeat(64), ownerName = "bob"), walletState = null)
        val nameless = GuardedWalletRow(heldBy(r, "6".repeat(64), LineageExample.rentGuardians, ownerPublicKey = "0".repeat(64), ownerName = null), walletState = null)

        val people = listOf(bob, nameless, savingsAtR).byPerson()
        assertEquals(listOf("0".repeat(64), LineageExample.alice, "b".repeat(64)), people.map { it.ownerPublicKey })
    }
}
