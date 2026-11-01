package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutDestinationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the group pays each member, on the room's screen -- `docs/group-payout-destination.md`,
 * Phase 5: a member who has said shows their Lightning address and opens a sheet with both
 * addresses to copy, one who has not says so, and the active profile's own row leads to
 * setting theirs.
 */
@OptIn(ExperimentalTestApi::class)
class MemberPayoutRowsJvmTest {
    private val chain = Chain.Mainnet
    private val aliceKey = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0'))))
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val silent = "c".repeat(64)
    private val onchain = Bitcoin.computeBIP86Address(PrivateKey(ByteVector32(Hex.decode("f1".padStart(64, '0')))).publicKey(), chain.chainHash)

    private val destination = GroupPayoutDestinationEvent.verified(
        GroupPayoutDestinationEvent.build(aliceKey, chatRoomId, chain, LightningAddress("alice", "example.com"), onchainAddress = onchain)
    ) as GroupPayoutDestination

    private fun member(publicKey: String, name: String?) = LocalParticipant(
        participant = Participant(participantPublicKey = publicKey, chatRoomId = chatRoomId, relayHint = null),
        profile = name?.let { Profile(publicKey = publicKey, displayName = it, nostrEventId = publicKey.take(63) + "e") },
    )

    private fun state() = ChatRoomDetailUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom().copy(
            localParticipants = listOf(member(alice, "Alice"), member(silent, "Sipho"), member(activeUserPublicKey, "Me")),
        ),
        payouts = PayoutDestinationRepository.Standing(isGroup = true, canSet = true, current = null, profileLightningAddress = null),
        payoutDestinations = mapOf(alice to destination),
    )

    /** Answers the sheet's check as [usage]. */
    private fun repository(usage: UnusedAddressCheck.Usage) = object : PayoutDestinationRepository by PayoutDestinationRepository.NO_OP {
        override suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector) = usage
    }

    @Test
    fun `a member who has said shows their Lightning address, and one who has not says so`() = render {
        onNodeWithText("alice@example.com").assertIsDisplayed()
        onNodeWithText("Hasn't said where to be paid").assertIsDisplayed()
    }

    @Test
    fun `the member's sheet has both addresses to copy, and says the onchain one is unused`() = render(usage = UnusedAddressCheck.Usage.Unused) {
        onNodeWithText("alice@example.com").performClick()
        waitForIdle()
        onNodeWithText("Where Alice is paid").assertIsDisplayed()
        onNodeWithText(onchain).assertIsDisplayed()
        onNodeWithContentDescription("Copy the Lightning address").assertIsDisplayed()
        onNodeWithContentDescription("Copy the onchain address").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Unused").assertIsDisplayed() }.isSuccess }
    }

    @Test
    fun `an onchain address paid since is said to be`() = render(usage = UnusedAddressCheck.Usage.Used) {
        onNodeWithText("alice@example.com").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("Paid before. Ask for a new one before paying it again.").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun `the active profile's own row leads to setting theirs`() {
        var opened: Route? = null
        render(onNavigateToRoute = { opened = it }) {
            // The section's row says Set; the member row says where this group pays you.
            onNodeWithText("Set").performClick()
        }
        assertEquals(GroupPayoutDestinationRoute(activeUserPublicKey, chatRoomId), opened)
    }

    private fun render(
        usage: UnusedAddressCheck.Usage = UnusedAddressCheck.Usage.Unknown,
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = state(),
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            payoutDestinationRepository = repository(usage),
                            onNavigateToRoute = onNavigateToRoute,
                            onPopBackToRoute = {},
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }
        assertions()
    }
}
