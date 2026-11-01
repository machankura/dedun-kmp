package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.GroupSwapInUIState
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.Lightning.randomBytes32
import kotlin.test.Test

/**
 * The group's onchain deposit screen in each of its states (docs/group-lightning-swap-in.md, Phase 6).
 */
@OptIn(ExperimentalTestApi::class)
class GroupSwapInScreenJvmTest {
    private val current = SwapInAddress("bc1pcurrentaddress", 1, SwapInAddress.Status.CURRENT, Satoshi(0))

    private fun coin(amount: Long, status: SwapInCoin.Status) = SwapInCoin(OutPoint(TxId(randomBytes32()), 0), Satoshi(amount), 0, 5, status)

    private fun loaded(coins: List<SwapInCoin> = emptyList(), used: List<SwapInAddress> = emptyList(), others: List<Pair<String, List<SwapInAddress>>> = emptyList()) =
        GroupSwapInUIState.Loaded(current, coins, used, others, DefaultSwapInParams.MinConfirmations, DefaultSwapInParams.RefundDelay)


    @Test
    fun `a fresh address says where its coins go and whose they count as`() = runDesktopComposeUiTest(400, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(loaded()) } } }
        onNodeWithText("Your deposit address").assertIsDisplayed()
        onNodeWithText("bc1pcurrentaddress").assertIsDisplayed()
        onNodeWithText("Coins sent here move into the group's Lightning balance once they have 3 confirmations, and count as your deposit, less the network fee.").assertIsDisplayed()
        onNodeWithText("Nothing has been sent to your deposit addresses yet.").assertIsDisplayed()
    }

    @Test
    fun `coins say where they are, and ready ones what they wait for`() = runDesktopComposeUiTest(400, 1600) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    GroupSwapInContent(loaded(coins = listOf(coin(1_000, SwapInCoin.Status.Confirming(1, 3)), coin(2_000, SwapInCoin.Status.Ready), coin(3_000, SwapInCoin.Status.Refundable))))
                }
            }
        }
        onNodeWithText("1 of 3 confirmations").assertIsDisplayed()
        onNodeWithText("Ready to move into Lightning").assertIsDisplayed()
        onNodeWithText("Too old to move into Lightning. Can be refunded now").assertIsDisplayed()
        onNodeWithText("Ready coins move into Lightning while the operator's device is online, and enough of the group's devices are online to co-sign.").assertIsDisplayed()
    }

    @Test
    fun `a coin near the end of its window is flagged first`() = runDesktopComposeUiTest(400, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(loaded(coins = listOf(coin(5_000, SwapInCoin.Status.ExpiringSoon(144 * 3))))) } } }
        onNodeWithText("5,000 sats must move into Lightning within about 3 days. After that they can only come back as a refund, about 6 months after they were sent.").assertIsDisplayed()
    }

    @Test
    fun `used addresses are listed to copy, the member's and everyone else's`() = runDesktopComposeUiTest(400, 1600) {
        val paid = SwapInAddress("bc1ppaidaddress", 0, SwapInAddress.Status.USED, Satoshi(0))
        val bobs = SwapInAddress("bc1pbobsaddress", 3, SwapInAddress.Status.USED, Satoshi(20_000))
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(loaded(used = listOf(paid), others = listOf("bob" to listOf(bobs)))) } } }
        onNodeWithText("Used addresses").assertIsDisplayed()
        onNodeWithText("Paid · spent since").assertIsDisplayed()
        onNodeWithText("@bob's used addresses").assertIsDisplayed()
        onNodeWithText("Paid · 20,000 sats").assertIsDisplayed()
    }

    @Test
    fun `no swap-in on the network, or no deposits yet, says so`() = runDesktopComposeUiTest(400, 800) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(GroupSwapInUIState.Unavailable) } } }
        onNodeWithText("The group's Lightning wallet takes no onchain deposits on this network.").assertIsDisplayed()
    }

    @Test
    fun `a member who is no depositor is told the step before`() = runDesktopComposeUiTest(400, 800) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(GroupSwapInUIState.NotDepositor) } } }
        onNodeWithText("The group opens your deposits first. Ask for an invoice to start.").assertIsDisplayed()
    }

    @Test
    fun `refundable coins can be refunded by the group, by a member who can ask`() = runDesktopComposeUiTest(400, 1600) {
        var asked = 0
        val coins = listOf(coin(7_000, SwapInCoin.Status.Refundable), coin(8_000, SwapInCoin.Status.Refundable), coin(1_000, SwapInCoin.Status.Ready))
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(loaded(coins = coins), onRefund = { asked++ }) } } }
        onNodeWithText("Ask the group to refund 15,000 sats").performClick()
        kotlin.test.assertEquals(1, asked)
    }

    @Test
    fun `a member who cannot ask is not offered a refund`() = runDesktopComposeUiTest(400, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupSwapInContent(loaded(coins = listOf(coin(7_000, SwapInCoin.Status.Refundable)))) } } }
        onNodeWithText("Ask the group to refund 7,000 sats").assertDoesNotExist()
    }
}
