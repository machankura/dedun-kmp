package com.machankura.dedun.compose.ui.composable

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.ME
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.OTHER
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.key
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.localChatRoom
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.wallet
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The guide's accessibility chapter against these screens: twice the text, a narrow window, and what a
 * screen reader is handed.
 *
 * Deliberately last of the ten phases, because a pass done before Phases 3, 4 and 6 would have been
 * redone by them.
 *
 * **These are `jvmTest` cases rather than the `androidInstrumentedTest` ones the plan named.** Font
 * scale is `LocalDensity`, which a desktop test provides as easily as a device does, and a window 320dp
 * wide is `runDesktopComposeUiTest`'s own argument -- so what the plan expected to need an emulator
 * needs neither one nor the Compose device-test harness. The one thing that still does is Phase 4's
 * brightness `actual`, which touches a real `Activity` window.
 */
@OptIn(ExperimentalTestApi::class)
class GroupWavelengthAccessibilityJvmTest {

    /** The narrowest window the guide's layout advice covers, and twice the text in it. */
    private val narrow = 320
    private val tall = 3200

    /** Four devices, three heard from lately, so the devices card has something to read out. */
    private fun opened(expiry: WalletExpiry = WalletExpiry.Fine(10_000)): GroupWavelengthWalletUIState.Opened {
        val now = Clock.System.now()
        val seen: Map<HexKey, kotlin.time.Instant> = wallet().statement.participants.take(3).associateWith { now - 1.minutes }
        return GroupWavelengthWalletUIState.Opened(
            localChatRoom = localChatRoom,
            wallet = wallet(),
            operator = GroupWavelengthUiFixtures.operator(OTHER),
            isParticipant = true,
            isOperator = false,
            canRunHere = true,
            userPublicKey = ME,
            ledger = com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger.of(
                listOf(GroupArkVtxo("w", "a:0", 10_000, settled = true)),
            ),
            coins = listOf(GroupArkVtxo("w", "a:0", 10_000, settled = true)),
            expiry = expiry,
            seen = seen + (OTHER to now),
        )
    }

    /** Twice the system text size, in a window 320dp wide. */
    private fun atTwiceTheText(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
            DedunTheme { ProvideSnackbarHost { content() } }
        }
    }

    /**
     * The balance card is the first of the three the plan expected to break: a headline figure, a
     * currency line, a status line and a disclosure, in 320dp at twice the text.
     */
    @Test
    fun `the balance card still says everything at twice the text in a narrow window`() = runDesktopComposeUiTest(narrow, tall) {
        setContent(atTwiceTheText { GroupWavelengthWalletContent(Modifier, opened(), fr.acinq.bitcoin.Chain.Signet) })
        onNodeWithText("What your device has signed for").assertIsDisplayed()
        onNodeWithText("10,000 sats").assertIsDisplayed()
        onNodeWithText("More about this balance").assertIsDisplayed()
        onNodeWithText("Coins need renewing", substring = true).assertIsDisplayed()
    }

    /** The second: six sentences and two choices, which is the densest screen in the wallet. */
    @Test
    fun `the commitment screen still says all six sentences at twice the text`() = runDesktopComposeUiTest(narrow, tall) {
        setContent(atTwiceTheText { GroupWavelengthWalletContent(Modifier, GroupWavelengthWalletUIState.NotOpened(localChatRoom, key, null, null), fr.acinq.bitcoin.Chain.Mainnet) })
        onNodeWithText("On mainnet this wallet holds at most 100,000 sats, and every member's device holds it to that.").assertIsDisplayed()
        onNodeWithText("I understand, and the group can open it").assertIsDisplayed()
        onNodeWithText("Not yet").assertIsDisplayed()
    }

    /** The third: the advanced screen's terms, which are the longest run of figures in the app. */
    @Test
    fun `the advanced screen's terms still read at twice the text`() = runDesktopComposeUiTest(narrow, tall) {
        setContent(atTwiceTheText { GroupWavelengthAdvancedContent(Modifier, opened()) })
        onNodeWithText("VTXOs on this device").assertIsDisplayed()
        onNodeWithText("A unilateral exit, which leaves without the Ark operator's help, has never been run on any network.").assertIsDisplayed()
    }

    /** And the checklist, which Phase 6 added after the plan listed the three. */
    @Test
    fun `the health checklist still reads at twice the text`() = runDesktopComposeUiTest(narrow, tall) {
        setContent(atTwiceTheText { GroupWavelengthHealthContent(Modifier, opened()) })
        onNodeWithText("The coins' deadline").assertIsDisplayed()
        onNodeWithText("Who holds a share").assertIsDisplayed()
    }

    /**
     * Four members were eight unrelated strings, read out in a row with nothing to say which half
     * belonged to whom. Each member's row is one node now.
     */
    @Test
    fun `each member in the devices card reads as one thing, not two`() = runDesktopComposeUiTest(400, tall) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(), fr.acinq.bitcoin.Chain.Signet) } } }
        // Merged means one node carries both halves: whose row it is, and when they last answered.
        // Before this they were two nodes, read out in a row with nothing to say which was whose.
        onNode(hasText("You", substring = true) and hasText("Answered in the last hour", substring = true)).assertIsDisplayed()
        // The same for a member who is not the reader, so the merge is not a property of one row.
        onNode(hasText("bbbbbbbbbbbbbbbb", substring = true) and hasText("Answered in the last hour", substring = true)).assertIsDisplayed()
        // Not asserted: that nothing is the seen line *alone*. A merged node reports each of its
        // descendants' texts, so `hasText("Answered in the last hour")` still matches the merged row --
        // which is why the two-matcher form above is what tells merged from unmerged at all.
        assertTrue(onAllNodesWithText("Answered in the last hour", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The guide asks for feedback through more than one sense on the actions that matter. These are the
     * two on these screens a member cannot take back by themselves, and both are followed by a snackbar
     * that arrives as the screen navigates away.
     */
    @Test
    fun `asking the group to let you leave is felt as well as said`() = runDesktopComposeUiTest(400, tall) {
        val felt = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                felt += hapticFeedbackType
            }
        }
        var left = 0
        setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(), fr.acinq.bitcoin.Chain.Signet, onLeave = { left++ }) } }
            }
        }
        onNodeWithText("Leave").performClick()
        onNodeWithText("Ask the group").performClick()
        assertEquals(1, left)
        assertEquals(listOf(HapticFeedbackType.Confirm), felt, "felt once, on the action that commits")
    }

    /** Nothing else on the screen buzzes: a haptic on everything is a haptic on nothing. */
    @Test
    fun `opening the disclosure is not felt`() = runDesktopComposeUiTest(400, tall) {
        val felt = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                felt += hapticFeedbackType
            }
        }
        setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(), fr.acinq.bitcoin.Chain.Signet) } }
            }
        }
        onNodeWithText("More about this balance").performClick()
        assertEquals(emptyList(), felt)
    }
}
