package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.repository.GroupMadeHere
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A NIP-17 room's details list every group its members have made in it, each with
 * where it stands. It used to be one admin-room card, which showed the newest group
 * and hid the rest. docs/nip17-multiple-ceremonies.md, Phase 5.
 */
@OptIn(ExperimentalTestApi::class)
class GroupsMadeHereJvmTest {

    private val agora = GroupMadeHere("a".repeat(64), "Agora", GroupMadeHere.Status.KeyCeremonyRunning)
    private val forum = GroupMadeHere("f".repeat(64), "Forum", GroupMadeHere.Status.WaitingForRoom)
    private val ekklesia = GroupMadeHere("e".repeat(64), "Ekklesia", GroupMadeHere.Status.Ready("9".repeat(64)))

    private fun render(
        groups: List<GroupMadeHere>,
        onOpen: (GroupMadeHere) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 800) {
        setContent { DedunTheme { GroupsMadeHere(groups = groups, onOpen = onOpen) } }
        assertions()
    }

    @Test
    fun `every group is listed, with where it stands`() = render(listOf(agora, forum, ekklesia)) {
        onNodeWithText("{Agora}").assertExists()
        onNodeWithText("Key ceremony in progress").assertExists()
        onNodeWithText("{Forum}").assertExists()
        onNodeWithText("Has its key, and is waiting for its room").assertExists()
        onNodeWithText("{Ekklesia}").assertExists()
        onNodeWithText("Every member of this group is an admin there.").assertExists()
    }

    @Test
    fun `a group is opened by the one tapped`() {
        var opened: GroupMadeHere? = null
        render(listOf(agora, ekklesia), onOpen = { opened = it }) {
            onNodeWithText("{Ekklesia}").performClick()
        }
        assertEquals(ekklesia, opened)
    }

    @Test
    fun `a room that has made no group says so`() = render(emptyList()) {
        onNodeWithText("No groups have been made in this conversation yet.").assertExists()
    }

    @Test
    fun `a group with no name is not a blank row`() =
        render(listOf(agora.copy(name = null))) {
            onNodeWithText("an unnamed group").assertExists()
        }
}
