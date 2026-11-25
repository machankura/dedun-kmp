package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.Contacts
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.managers.GroupFixtures
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ContactUIState
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A contact's page: their name and number in full, what they put in, and what any member can ask
 * the group to change. `docs/non-member-deposits.md`, Phase 4.
 */
@OptIn(ExperimentalTestApi::class)
class ContactScreenJvmTest {
    private val group = GroupFixtures.keyMaterial("6a0b")
    private val room = GroupFixtures.roomOf(group)
    private val alice = "a1".repeat(32)
    private val mum = ContactIdentifier.Phone("+27821234567")
    private val mumId = mum.contactId(room)

    private fun contact(name: String = "Mum", closed: Boolean = false, claimedBy: String? = null): Contacts.Contact {
        val template = ContactRecordEvent.template(room, alice, "Mum", mum)
        val record = ContactRecordEvent.verify(GroupFixtures.signedAsRoom(group, template.kind, template.tags, template.content), room)!!
        return Contacts.Contact(record, name, closed, claimedBy = claimedBy)
    }

    private fun loaded(contact: Contacts.Contact, deposits: List<DepositRow> = emptyList(), tally: DepositLedger.Tally? = null) = ContactUIState.Loaded(
        localChatRoom = LocalChatRoom(chatRoom = ChatRoom(id = room, userPublicKey = alice, subject = "#Council", description = null, mlsGroupState = "00")),
        contact = contact,
        tally = tally,
        deposits = deposits,
        destination = null,
        names = mapOf(alice to "alice"),
        members = listOf(alice),
    )

    private fun render(state: ContactUIState, assertions: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 400, height = 1600) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    ContactScreen(
                        activeUserPublicKey = alice,
                        chatRoomId = room,
                        contactId = mumId,
                        chain = Chain.Regtest,
                        initialUIState = state,
                        chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                        depositRepository = DepositRepository.NO_OP,
                        onNavigateBack = {},
                    )
                }
            }
        }
        assertions()
    }

    @Test
    fun `the page shows the name and the number in full, who added them, and what they put in`() = render(
        loaded(
            contact(),
            deposits = listOf(DepositRow(room, "regtest", "ab".repeat(32) + ":0", DepositRow.ONCHAIN, 20_000_000, mumId, DepositRow.BY_GROUP)),
            tally = DepositLedger.Tally(mumId, onchainMsat = 20_000_000),
        )
    ) {
        onNodeWithText("Mum").assertExists()
        onNodeWithText("+27 82 123 4567").assertExists()
        onNodeWithText("Added by @alice").assertExists()
        onNodeWithText("Mum put in 20,000 sats").assertExists()
        onNodeWithText("20,000 sats").assertExists()
        onNodeWithText("Stop counting deposits for them").assertExists()
    }

    @Test
    fun `a contact with nothing deposited says so`() = render(loaded(contact())) {
        onNodeWithText("Nothing has been deposited for them yet.").assertExists()
    }

    @Test
    fun `a closed contact says so, and offers to reopen rather than close`() = render(loaded(contact(closed = true))) {
        onNodeWithText("The group no longer counts new deposits for them.").assertExists()
        onNodeWithText("Reopen Mum").assertExists()
        assertEquals(0, onAllNodesWithText("Stop counting deposits for them").fetchSemanticsNodes().size)
    }

    @Test
    fun `a renamed contact is called by the new name, and renaming asks for one`() = render(loaded(contact(name = "Gogo"))) {
        onNodeWithText("Gogo").assertExists()
        onNodeWithText("Rename").performClick()
        onNodeWithText("What the group will call them").assertExists()
    }

    @Test
    fun `an error is said, with a way to try again`() = render(ContactUIState.Error("The group has no record of this contact")) {
        onNodeWithText("The group has no record of this contact").assertExists()
    }

    @Test
    fun `an unclaimed contact can be said to have joined, and a claimed one corrected`() {
        render(loaded(contact())) {
            onNodeWithText("They've joined the group").performClick()
            onNodeWithText("Which member is Mum?").assertExists()
            onNodeWithText("@alice").assertExists()
        }
        render(loaded(contact(claimedBy = alice))) {
            onNodeWithText("Mum is @alice").assertExists()
            onNodeWithText("Not them: correct this").performClick()
            onNodeWithText("Nobody in the group").assertExists()
        }
    }
}
