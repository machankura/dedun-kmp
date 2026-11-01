package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.SwapInWalletViewModel
import com.machankura.dedun.compose.ui.view.state.SwapInWalletUIState
import androidx.compose.foundation.text.input.TextFieldState
import com.machankura.dedun.compose.wallet.FirstDeposit
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.SwapInRefusal
import com.machankura.dedun.compose.wallet.SwapInWaiting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning.randomBytes32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The swap-in screen in each of its states, in the catalogue's words (docs/personal-swap-in-wallet.md, Phase 3).
 */
@OptIn(ExperimentalTestApi::class)
class SwapInWalletScreenJvmTest {
    private val current = SwapInAddress("bc1pcurrentaddress", 1, SwapInAddress.Status.CURRENT, Satoshi(0))
    private val paid = SwapInAddress("bc1ppaidaddress", 0, SwapInAddress.Status.USED, Satoshi(50_000))
    private val legacy = SwapInAddress("bc1qlegacyaddress", null, SwapInAddress.Status.USED, Satoshi(0))

    private fun coin(amount: Long, status: SwapInCoin.Status, confirmations: Int = 0) =
        SwapInCoin(OutPoint(TxId(randomBytes32()), 0), Satoshi(amount), 0, confirmations, status)

    private fun loaded(coins: List<SwapInCoin> = emptyList(), used: List<SwapInAddress> = emptyList(), firstDeposit: FirstDeposit? = null, waiting: SwapInWaiting? = null) =
        SwapInWalletUIState.Loaded(current = current, used = used, coins = coins, minConfirmations = 3, firstDeposit = firstDeposit, waiting = waiting)

    @Test
    fun `a fresh wallet shows its address, what a deposit does, and that nothing has come yet`() = runDesktopComposeUiTest(400, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(), onRetry = {}) } } }
        onNodeWithText("Deposit address").assertIsDisplayed()
        onNodeWithText("bc1pcurrentaddress").assertIsDisplayed()
        onNodeWithText("Coins sent here move into your Lightning balance once they have 3 confirmations.").assertIsDisplayed()
        onNodeWithText("Nothing has been sent to your deposit addresses yet.").assertIsDisplayed()
        onNodeWithText("Used addresses").assertDoesNotExist()
    }

    @Test
    fun `a first deposit says what its channel costs and the least to send`() = runDesktopComposeUiTest(400, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(firstDeposit = FirstDeposit(Satoshi(1_287), Satoshi(2_574), null)), onRetry = {}) } } }
        onNodeWithText("Your first deposit opens a Lightning channel, for a fee of about 1,287 sats at today's rates. Send at least 2,574 sats.").assertIsDisplayed()
    }

    @Test
    fun `a first deposit over the fee limit says it will wait`() = runDesktopComposeUiTest(400, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(firstDeposit = FirstDeposit(Satoshi(6_200), null, Satoshi(5_000))), onRetry = {}) } } }
        onNodeWithText("more than your limit of 5,000 sats", substring = true).assertIsDisplayed()
    }

    @Test
    fun `each coin says where it is on its way into Lightning`() = runDesktopComposeUiTest(400, 2400) {
        setContent {
            DedunTheme { ProvideSnackbarHost {
                SwapInWalletContent(
                    loaded(
                        coins = listOf(
                            coin(1_000, SwapInCoin.Status.Unconfirmed),
                            coin(2_000, SwapInCoin.Status.Confirming(2, 3), 2),
                            coin(3_000, SwapInCoin.Status.Ready, 10),
                            coin(4_000, SwapInCoin.Status.Swapping, 10),
                            coin(5_000, SwapInCoin.Status.ExpiringSoon(144 * 3), 17_000),
                            coin(6_000, SwapInCoin.Status.Locked(4_320), 21_600),
                            coin(7_000, SwapInCoin.Status.Refundable, 26_000),
                        ),
                    ),
                    onRetry = {},
                )
            } }
        }
        onNodeWithText("1,000 sats").assertIsDisplayed()
        onNodeWithText("Waiting for its first confirmation").assertIsDisplayed()
        onNodeWithText("2 of 3 confirmations").assertIsDisplayed()
        onNodeWithText("Ready to move into Lightning").assertIsDisplayed()
        onNodeWithText("Moving into Lightning").assertIsDisplayed()
        onNodeWithText("Must move into Lightning within about 3 days").assertIsDisplayed()
        onNodeWithText("Too old to move into Lightning. Can be refunded in about 1 month").assertIsDisplayed()
        onNodeWithText("Too old to move into Lightning. Can be refunded now").assertIsDisplayed()
    }

    @Test
    fun `ready coins that have not moved say why, once for all of them`() = runDesktopComposeUiTest(400, 1400) {
        val ready = listOf(coin(3_000, SwapInCoin.Status.Ready, 10), coin(4_000, SwapInCoin.Status.Ready, 12))
        var waiting by mutableStateOf<SwapInWaiting>(SwapInWaiting.NotConnected)
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(coins = ready, waiting = waiting), onRetry = {}) } } }
        onNodeWithText("Waiting for your node to connect to the LSP.").assertIsDisplayed()

        waiting = SwapInWaiting.Refused(SwapInRefusal.OverLimit(Satoshi(6_200), Satoshi(5_000), Satoshi(7_000)))
        onNodeWithText("Waiting: moving these coins costs about 6,200 sats, more than your limit of 5,000 sats.").assertIsDisplayed()

        waiting = SwapInWaiting.Refused(SwapInRefusal.OverShare(Satoshi(6_200), Satoshi(7_000), 50_00))
        onNodeWithText("Waiting: moving these coins costs about 6,200 sats, more than 50% of the 7,000 sats being moved.").assertIsDisplayed()

        waiting = SwapInWaiting.Refused(SwapInRefusal.Disabled)
        onNodeWithText("Moving deposits into Lightning is turned off.").assertIsDisplayed()

        waiting = SwapInWaiting.ForNode
        onNodeWithText("Waiting for your node to move them.").assertIsDisplayed()
    }

    @Test
    fun `a refused fee can be reviewed, and nothing else can`() = runDesktopComposeUiTest(400, 1400) {
        val ready = listOf(coin(3_000, SwapInCoin.Status.Ready, 10))
        val refusal = SwapInRefusal.OverLimit(Satoshi(6_200), Satoshi(5_000), Satoshi(3_000))
        var waiting by mutableStateOf<SwapInWaiting>(SwapInWaiting.Refused(refusal))
        val reviewed = mutableListOf<SwapInRefusal>()
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(coins = ready, waiting = waiting), onRetry = {}, onReview = { reviewed += it }) } } }
        onNodeWithText("Review fee limit").performClick()
        assertEquals(listOf<SwapInRefusal>(refusal), reviewed)

        waiting = SwapInWaiting.Refused(SwapInRefusal.Disabled)
        onNodeWithText("Review fee limit").assertDoesNotExist()
    }

    @Test
    fun `the fee-limit sheet offers the fee once, or a new limit`() = runDesktopComposeUiTest(400, 1400) {
        var once = 0
        var saved = 0
        setContent {
            DedunTheme {
                FeeLimitSheet(
                    refusal = SwapInRefusal.OverLimit(Satoshi(6_200), Satoshi(5_000), Satoshi(50_000)),
                    limitField = TextFieldState("6820"),
                    problem = null,
                    isApplying = false,
                    onAllowOnce = { once++ },
                    onSave = { saved++ },
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("Moving these coins costs about 6,200 sats. Your limit is 5,000 sats.").assertIsDisplayed()
        onNodeWithText("Allow this fee once").performClick()
        onNodeWithText("Save as my limit").performClick()
        assertEquals(1, once)
        assertEquals(1, saved)
    }

    @Test
    fun `a fee over the share is allowed once only, since a limit in sats would not change it`() = runDesktopComposeUiTest(400, 1400) {
        setContent {
            DedunTheme {
                FeeLimitSheet(
                    refusal = SwapInRefusal.OverShare(Satoshi(1_287), Satoshi(2_000), 50_00),
                    limitField = TextFieldState(),
                    problem = null,
                    isApplying = false,
                    onAllowOnce = {},
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("Moving these coins costs about 1,287 sats, more than 50% of the 2,000 sats being moved.").assertIsDisplayed()
        onNodeWithText("Allow this fee once").assertIsDisplayed()
        onNodeWithText("Save as my limit").assertDoesNotExist()
    }

    @Test
    fun `a limit over the coins says so under the field`() = runDesktopComposeUiTest(400, 1400) {
        setContent {
            DedunTheme {
                FeeLimitSheet(
                    refusal = SwapInRefusal.OverLimit(Satoshi(6_200), Satoshi(5_000), Satoshi(50_000)),
                    limitField = TextFieldState("60000"),
                    problem = SwapInWalletViewModel.LimitProblem.OverAmount(Satoshi(50_000)),
                    isApplying = false,
                    onAllowOnce = {},
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("The limit can't be more than the 50,000 sats being moved.").assertIsDisplayed()
    }

    @Test
    fun `coins close to the end of the swap window are flagged above everything`() = runDesktopComposeUiTest(400, 1400) {
        val expiring = listOf(coin(5_000, SwapInCoin.Status.ExpiringSoon(144 * 3), 17_000), coin(6_000, SwapInCoin.Status.ExpiringSoon(144 * 10), 15_000))
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(coins = expiring), onRetry = {}) } } }
        onNodeWithText("11,000 sats must move into Lightning within about 3 days. After that they can only come back as a refund, about 6 months after they were sent.").assertIsDisplayed()
    }

    @Test
    fun `refundable coins are offered a refund, all of them at once`() = runDesktopComposeUiTest(400, 1400) {
        var refunds = 0
        val coins = listOf(coin(7_000, SwapInCoin.Status.Refundable, 26_000), coin(8_000, SwapInCoin.Status.Refundable, 27_000), coin(1_000, SwapInCoin.Status.Ready, 10))
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(coins = coins), onRetry = {}, onRefund = { refunds++ }) } } }
        onNodeWithText("Refund 15,000 sats").performClick()
        assertEquals(1, refunds)
    }

    @Test
    fun `without a refunder there is no refund button`() = runDesktopComposeUiTest(400, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(coins = listOf(coin(7_000, SwapInCoin.Status.Refundable, 26_000))), onRetry = {}) } } }
        onNodeWithText("Refund 7,000 sats").assertDoesNotExist()
    }

    @Test
    fun `the refund sheet asks for an address, and says what is wrong with one`() = runDesktopComposeUiTest(400, 1400) {
        var step by mutableStateOf<SwapInWalletViewModel.RefundStep>(SwapInWalletViewModel.RefundStep.Entering)
        setContent { DedunTheme { RefundSheet(step, TextFieldState("tb1p"), onReview = {}, onEdit = {}, onSend = {}, onDismiss = {}) } }
        onNodeWithText("Address to refund to").assertIsDisplayed()
        onNodeWithText("Review refund").assertIsDisplayed()

        step = SwapInWalletViewModel.RefundStep.Problem(SwapInWalletViewModel.RefundProblem.OWN_DEPOSIT)
        onNodeWithText("That is one of your deposit addresses, and the coins would be locked again. Use a wallet of your own.").assertIsDisplayed()
    }

    @Test
    fun `a tap on a coin shows its transaction`() = runDesktopComposeUiTest(400, 1400) {
        val ready = coin(3_000, SwapInCoin.Status.Ready, 10)
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(coins = listOf(ready)), onRetry = {}) } } }
        onNodeWithText("3,000 sats").performClick()
        onNodeWithText("10 confirmations").assertIsDisplayed()
        onNodeWithText("${ready.txId}:0").assertIsDisplayed()
    }

    @Test
    fun `used addresses are listed to copy, the legacy one by name`() = runDesktopComposeUiTest(400, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(loaded(used = listOf(paid, legacy)), onRetry = {}) } } }
        onNodeWithText("Used addresses").assertIsDisplayed()
        onNodeWithText("Paid · 50,000 sats").assertIsDisplayed()
        onNodeWithText("Legacy").assertIsDisplayed()
        onNodeWithText("Paid · spent since").assertIsDisplayed()
    }

    @Test
    fun `a profile without a node is told so, with nothing to retry`() = runDesktopComposeUiTest(400, 800) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(SwapInWalletUIState.NoNode, onRetry = {}) } } }
        onNodeWithText("This profile has no wallet, so it has no deposit addresses.").assertIsDisplayed()
        onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `an error offers to read the node again`() = runDesktopComposeUiTest(400, 800) {
        var retries = 0
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(SwapInWalletUIState.Error, onRetry = { retries++ }) } } }
        onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `loading says what it is waiting for`() = runDesktopComposeUiTest(400, 800) {
        setContent { DedunTheme { ProvideSnackbarHost { SwapInWalletContent(SwapInWalletUIState.Loading, onRetry = {}) } } }
        onNodeWithText("Finding your deposits").assertIsDisplayed()
    }
}
