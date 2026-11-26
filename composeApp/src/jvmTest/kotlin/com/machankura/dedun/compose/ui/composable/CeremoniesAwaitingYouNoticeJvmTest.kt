package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.ui.composable.widgets.chat.CeremoniesAwaitingYouNotice
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.ChatMessageListViewModel.AwaitingCeremony
import com.machankura.dedun.compose.ui.view.model.ChatMessageListViewModel.Ask
import kotlin.test.Test

/**
 * The notice above a room's transcript that a ceremony wants this member. The same
 * people may run more than one ceremony in a room, so it names the group each one
 * makes. docs/nip17-multiple-ceremonies.md, Phase 4.
 */
@OptIn(ExperimentalTestApi::class)
class CeremoniesAwaitingYouNoticeJvmTest {

    private fun waiting(id: String, subject: String?, isSubgroup: Boolean = false) = AwaitingCeremony(
        sessionId = id,
        ask = Ask.SharedKeyStep(DkgApprovalStep.HOST_KEY),
        isSubgroup = isSubgroup,
        subject = subject,
    )

    private fun render(ceremonies: List<AwaitingCeremony>, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 400) {
            setContent { DedunTheme { CeremoniesAwaitingYouNotice(ceremonies = ceremonies, onClick = {}) } }
            assertions()
        }

    @Test
    fun `one ceremony is named by the group it makes`() = render(listOf(waiting("a", "Agora"))) {
        onNodeWithText("The key ceremony for {Agora} needs you").assertExists()
    }

    @Test
    fun `several are counted, and named under the count`() =
        render(listOf(waiting("a", "Agora"), waiting("b", "Ekklesia"))) {
            onNodeWithText("2 key ceremonies need you").assertExists()
            onNodeWithText("{Agora}, {Ekklesia}").assertExists()
        }

    @Test
    fun `an unnamed ceremony reads as it always did`() {
        render(listOf(waiting("a", null))) {
            onNodeWithText("The key ceremony needs you").assertExists()
        }
        render(listOf(waiting("a", null, isSubgroup = true))) {
            onNodeWithText("The subgroup's key ceremony needs you").assertExists()
        }
    }
}
