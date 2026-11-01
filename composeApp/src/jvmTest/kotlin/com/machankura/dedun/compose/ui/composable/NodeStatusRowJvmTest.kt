package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.managers.GroupLightningNodeStatus
import com.machankura.dedun.compose.managers.LspDrop
import com.machankura.dedun.compose.managers.LspLink
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The operator's own row for the group's node: whether it runs, and whether it can reach the LSP, each in
 * the catalogue's words (`docs/lsp-connection-status.md`, Phase 4).
 */
@OptIn(ExperimentalTestApi::class)
class NodeStatusRowJvmTest {
    private val unreachable = GroupLightningNodeStatus.Running(
        3,
        LspLink.Unreachable(LspDrop(LspDrop.Cause.CLOSED_BY_LSP, "Connection closed"), failures = 4, retryAt = Clock.System.now() + 10.minutes, lastConnected = null),
    )

    @Test
    fun `a connected node says since when`() = runDesktopComposeUiTest {
        setContent { DedunTheme { NodeStatusRow(GroupLightningNodeStatus.Running(3, LspLink.Connected(Clock.System.now())), onTryNow = {}) } }
        onNodeWithText("Connected to the LSP").assertIsDisplayed()
        onNodeWithText("Since ", substring = true).assertIsDisplayed()
        onNodeWithText("Try now").assertDoesNotExist()
    }

    @Test
    fun `a node dialling again says which attempt it is on`() = runDesktopComposeUiTest {
        setContent {
            DedunTheme {
                Column {
                    NodeStatusRow(GroupLightningNodeStatus.Running(3, LspLink.Connecting(1)), onTryNow = {})
                    NodeStatusRow(GroupLightningNodeStatus.Running(3, LspLink.Connecting(4)), onTryNow = {})
                }
            }
        }
        onNodeWithText("Attempt 4").assertIsDisplayed()
        onNodeWithText("Attempt 1").assertDoesNotExist()
    }

    @Test
    fun `an unreachable LSP says why, when the next try is, and lets the operator try now once`() = runDesktopComposeUiTest {
        var taps = 0
        setContent { DedunTheme { NodeStatusRow(unreachable, onTryNow = { taps++ }) } }
        onNodeWithText("Can't reach the LSP").assertIsDisplayed()
        onNodeWithText("The LSP closed the connection.").assertIsDisplayed()
        onNodeWithText("Trying again in 10 minutes").assertIsDisplayed()
        onNodeWithText("Connection closed").assertIsDisplayed()
        onNodeWithText("Try now").assertIsEnabled().performClick()
        onNodeWithText("Try now").assertIsNotEnabled()
        assertEquals(1, taps)
    }

    @Test
    fun `the countdown says seconds under a minute`() = runDesktopComposeUiTest {
        val soon = unreachable.copy(link = (unreachable.link as LspLink.Unreachable).copy(retryAt = Clock.System.now() + 30.seconds))
        setContent { DedunTheme { NodeStatusRow(soon, onTryNow = {}) } }
        onNodeWithText("Trying again in ", substring = true).assertIsDisplayed()
        onNodeWithText("seconds", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a node that is not running says why, or where it might be`() = runDesktopComposeUiTest {
        setContent {
            DedunTheme {
                Column {
                    NodeStatusRow(GroupLightningNodeStatus.Starting, onTryNow = {})
                    NodeStatusRow(GroupLightningNodeStatus.NotRunning(GroupLightningNodeStatus.Refusal.POOL_SHORT), onTryNow = {})
                    NodeStatusRow(GroupLightningNodeStatus.NotOnThisDevice, onTryNow = {})
                }
            }
        }
        onNodeWithText("Starting your node").assertIsDisplayed()
        onNodeWithText("Waiting for enough of the group to be ready to sign.").assertIsDisplayed()
        onNodeWithText("Your node isn't running").assertIsDisplayed()
        onNodeWithText("The group hasn't sent enough to sign with yet.").assertIsDisplayed()
        onNodeWithText("This device checks again every minute.").assertIsDisplayed()
        onNodeWithText("Your node isn't running on this device").assertIsDisplayed()
        onNodeWithText("Try now").assertDoesNotExist()
    }

    @Test
    fun `an earlier wallet's row is the headline alone`() = runDesktopComposeUiTest {
        setContent { DedunTheme { NodeStatusRow(unreachable, onTryNow = {}, compact = true) } }
        onNodeWithText("Can't reach the LSP").assertIsDisplayed()
        onNodeWithText("The LSP closed the connection.").assertDoesNotExist()
        onNodeWithText("Try now").assertDoesNotExist()
    }
}
