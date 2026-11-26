package com.machankura.dedun.compose.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.hasScrollAction
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.BackupCheckState
import com.machankura.dedun.compose.ui.view.state.RecoveryCodeUIState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The code screen, drawn in each state. What is pinned: the code is not on screen until it
 * is revealed; revealed, all nine blocks are there, the ninth with the others; an unreadable
 * file offers the phrase; and the check says which two blocks it wants. On the unmerged tree,
 * as `ReadOnlyEntrancesJvmTest` explains.
 */
@OptIn(ExperimentalTestApi::class)
class RecoveryCodeScreenJvmTest {

    private val code = (RecoveryCode.parse("LBZC-MYTK-E9P3-7C7B-AHJN-KX79-22CY-WAY7-K7QZ") as RecoveryCode.Parse.Valid).code
    private val taps = mutableListOf<String>()

    @Composable
    private fun Screen(state: RecoveryCodeUIState, check: BackupCheckState = BackupCheckState.Idle) {
        DedunTheme {
            RecoveryCodeContent(
                uiState = state,
                backupCheck = check,
                checkBlocks = 3 to 7,
                isBackupDone = false,
                isDisclaimerRead = false,
                showBackupNotice = true,
                onReveal = { taps += "reveal" },
                onHide = { taps += "hide" },
                onRevealWords = { taps += "words" },
                onHideWords = {},
                onCheckBackup = { a, b -> taps += "check:$a,$b" },
                onDisclaimerReadChange = {},
                onShowRecoveryPhrase = { taps += "phrase" },
                onNavigateBack = {},
            )
        }
    }

    private fun ComposeUiTest.scrollTo(text: String) {
        onAllNodes(hasScrollAction(), useUnmergedTree = true)[0].performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `hidden, the code is not on screen`() = runDesktopComposeUiTest {
        setContent { Screen(RecoveryCodeUIState.Hidden) }

        onNodeWithText("Display recovery code", useUnmergedTree = true).performClick()
        assertEquals(listOf("reveal"), taps)
        onNodeWithText("LBZC", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `revealed, all nine blocks are on screen`() = runDesktopComposeUiTest {
        setContent { Screen(RecoveryCodeUIState.Revealed(code)) }

        code.blocks.forEachIndexed { index, block ->
            onNodeWithContentDescription("Block ${index + 1}: $block", useUnmergedTree = true).assertExists()
        }
        scrollTo("Show the words")
        onNodeWithText("Show the words", useUnmergedTree = true).performClick()
        assertEquals(listOf("words"), taps)
    }

    @Test
    fun `an unreadable codes file offers the phrase`() = runDesktopComposeUiTest {
        setContent { Screen(RecoveryCodeUIState.Error.CodesUnreadable) }

        onNodeWithText("Show the recovery phrase instead", useUnmergedTree = true).performClick()
        assertEquals(listOf("phrase"), taps)
    }

    @Test
    fun `the check names the two blocks it wants`() = runDesktopComposeUiTest {
        setContent { Screen(RecoveryCodeUIState.Hidden, check = BackupCheckState.Mismatch) }

        scrollTo("type block 3 and block 7")
        onNodeWithText("type block 3 and block 7", substring = true, useUnmergedTree = true).assertExists()
        scrollTo("don't match")
        onNodeWithText("don't match", substring = true, useUnmergedTree = true).assertExists()
    }
}
