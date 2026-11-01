package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupFixtures
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.wallet.GroupUtxo
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The receive card's four states, and whose a coin is -- `docs/member-deposits.md`, Phase 5.
 */
@OptIn(ExperimentalTestApi::class)
class DepositCardJvmTest {
    private val group = GroupFixtures.keyMaterial("6a0b")
    private val room = GroupFixtures.roomOf(group)
    private val aliceKey = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0'))))
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val address = "bcrt1p" + "q".repeat(58)

    private val depositor = run {
        val destination = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(aliceKey, room, Chain.Regtest, LightningAddress("alice", "example.com"), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination
        val template = DepositorStatementEvent.template(room, destination, 0, 1_700_000_100)
        DepositorStatementEvent.verify(GroupFixtures.signedAsRoom(group, template.kind, template.tags, template.content, 1_700_000_100), room)!!
    }

    private fun render(standing: DepositRepository.Standing, address: String? = this.address, onEvent: (String) -> Unit = {}, assertions: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 1600) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        DepositCard(
                            standing = standing,
                            address = address,
                            isPending = false,
                            onCopy = { onEvent("copy") },
                            onOpenAddresses = { onEvent("addresses") },
                            onSetDestination = { onEvent("destination") },
                            onStartDepositing = { onEvent("start") },
                        )
                    }
                }
            }
            assertions()
        }

    @Test
    fun `a profile that can't sign is handed nothing`() = render(DepositRepository.Standing.CannotSign) {
        assertEquals(0, onAllNodesWithText("Your deposit address").fetchSemanticsNodes().size)
    }

    @Test
    fun `a member with no destination is sent to set one`() {
        var event: String? = null
        render(DepositRepository.Standing.NeedsDestination, onEvent = { event = it }) {
            onNodeWithText("Set where this group pays you first").performClick()
            assertEquals(0, onAllNodesWithText(address).fetchSemanticsNodes().size)
        }
        assertEquals("destination", event)
    }

    @Test
    fun `a member with a destination starts depositing, or is told who can open them`() {
        var event: String? = null
        render(DepositRepository.Standing.CanOpen(canPropose = true), onEvent = { event = it }) {
            onNodeWithText("Start depositing").performClick()
        }
        assertEquals("start", event)
        render(DepositRepository.Standing.CanOpen(canPropose = false)) {
            onNodeWithText("A member who holds a share of the group's key can open your deposits.").assertIsDisplayed()
            assertEquals(0, onAllNodesWithText("Start depositing").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `a depositor is shown their own address`() = render(DepositRepository.Standing.Open(depositor)) {
        onNodeWithText(address).assertIsDisplayed()
        onNodeWithText("Everything paid to your addresses is counted as yours.").assertIsDisplayed()
    }

    private fun coin(branch: Long): GroupUtxo {
        val derived = GroupWalletDerivation.derive(GroupWalletDerivation.account(GroupFixtures.thresholdKeyOf(group), Chain.Regtest), branch, 0)
        val funding = fr.acinq.bitcoin.Transaction(
            2,
            listOf(fr.acinq.bitcoin.TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { branch.toByte() })), 0), fr.acinq.bitcoin.ByteVector.empty, 0xffffffffL)),
            listOf(fr.acinq.bitcoin.TxOut(Satoshi(1_000), derived.pubkeyScript)),
            0,
        )
        return GroupUtxo(WalletState.Utxo(funding.txid, 0, 100, funding, WalletState.AddressMeta.Derived(0)), derived)
    }

    @Test
    fun `a coin says whose it is`() = runDesktopComposeUiTest(width = 400, height = 800) {
        setContent {
            DedunTheme {
                androidx.compose.foundation.layout.Column {
                    CoinRow(coin(DepositBranch.of(room, alice)), confirmations = 3, from = "alice")
                    CoinRow(coin(GroupWalletDerivation.RECEIVE), confirmations = 3)
                    CoinRow(coin(GroupWalletDerivation.CHANGE), confirmations = 3)
                }
            }
        }
        onNodeWithText("From @alice", substring = true).assertIsDisplayed()
        onNodeWithText("Unattributed", substring = true).assertIsDisplayed()
        onNodeWithText("Change", substring = true).assertIsDisplayed()
    }
}
