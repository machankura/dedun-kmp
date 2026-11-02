package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.ArkReceiveUIState
import com.machankura.dedun.compose.ui.view.model.ArkReceiveViewModel
import com.machankura.dedun.compose.wallet.ark.ArkRecovery
import com.machankura.dedun.compose.wallet.ark.ArkWalletState
import com.machankura.dedun.compose.wallet.ark.FakeArkWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The receive screen says that Lightning needs an amount -- `docs/bark-wallet.md`, Phase 5.
 * Bark makes an invoice only for an amount, so without one there is no Lightning choice,
 * and the screen has to say so rather than let the choice be missed.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArkReceiveScreenJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val lightningNeedsAnAmount = "To be paid over Lightning, ask for an amount."

    @Test
    fun `with no amount there is no Lightning choice, and the screen says an amount adds one`() = runDesktopComposeUiTest(400, 1400) {
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(FakeArkWallet(), ArkRecovery.NotRun)))
        setContent { Ready(vm) }
        onNodeWithText(lightningNeedsAnAmount, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Leave it empty to let the payer choose, over Ark or onchain only.", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Lightning", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an amount adds the Lightning choice, and the hint goes`() = runDesktopComposeUiTest(400, 1400) {
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(FakeArkWallet(), ArkRecovery.NotRun)))
        vm.amountField.setTextAndPlaceCursorAtEnd("10000")
        vm.request()
        setContent { Ready(vm) }
        onNodeWithText("Lightning", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(lightningNeedsAnAmount, useUnmergedTree = true).assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun Ready(vm: ArkReceiveViewModel) {
        DedunTheme {
            ProvideSnackbarHost {
                ArkReceiveReady(vm.uiState as ArkReceiveUIState.Ready, vm, Modifier)
            }
        }
    }
}
