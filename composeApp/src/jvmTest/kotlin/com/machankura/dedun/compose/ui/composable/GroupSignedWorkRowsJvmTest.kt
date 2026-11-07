package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWavelengthWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The row on the group's screen that stands for its signed work, and what it
 * opens.
 *
 * The group's nostr profile, relay lists and posts had rows here, and a paste
 * for proposing any of them; they are gone, and the wallet is what is left.
 */
@OptIn(ExperimentalTestApi::class)
class GroupSignedWorkRowsJvmTest {

    @Test
    fun `the wallet row sits under the signing key and above the subgroups`() = render(state()) {
        if (!GroupWalletRollout.ENABLED) return@render

        val signingKey = onNodeWithText("Signing key").getBoundsInRoot().top
        val wallet = onNodeWithText(WALLET_ROW).getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(signingKey < wallet, "the wallet row climbed above the signing key row")
        assertTrue(wallet < subgroups, "the wallet row fell below the group's subgroups")
    }

    @Test
    fun `the wallet row opens the wallet`() {
        if (!GroupWalletRollout.ENABLED) return

        var opened: Route? = null

        render(state(), onNavigateToRoute = { opened = it }) {
            onNode(hasText(WALLET_ROW) and hasText("Not opened yet")).assertIsDisplayed()
            onNodeWithText(WALLET_ROW).performClick()
        }

        assertEquals(GroupWalletRoute(activeUserPublicKey, chatRoomId, relayHint = null), opened)
    }

    @Test
    fun `the Lightning wallet's row opens it, and there is no second onchain wallet`() {
        if (!GroupWalletRollout.ENABLED || !GroupLightningRollout.ENABLED) return
        var opened: Route? = null

        render(state(groupWallet = onchainWallet), onNavigateToRoute = { opened = it }) {
            onNode(hasText("Lightning wallet") and hasText(GroupWalletStatementEvent.chainName(GroupLightningRollout.NETWORK))).assertIsDisplayed()
            onNodeWithText("Group onchain wallet on", substring = true).assertDoesNotExist()
            onNodeWithText("Lightning wallet").performClick()
        }

        assertEquals(GroupLightningWalletRoute(activeUserPublicKey, chatRoomId), opened)
    }

    /** It closes to the onchain wallet, so until there is one it says so and opens nothing. */
    @Test
    fun `the Lightning wallet's row waits for an onchain wallet`() {
        if (!GroupWalletRollout.ENABLED || !GroupLightningRollout.ENABLED) return
        var opened: Route? = null

        render(state(), onNavigateToRoute = { opened = it }) {
            onNode(hasText("Lightning wallet") and hasText("Lightning is only available once the group has an onchain wallet.")).assertIsDisplayed()
            onNodeWithText("Lightning wallet").performClick()
        }

        assertNull(opened)
    }

    @Test
    fun `the Wavelength wallet's row opens it`() {
        if (!GroupWalletRollout.ENABLED || !GroupWavelengthRollout.ENABLED) return
        val chain = GroupWavelengthRollout.NETWORKS.first()
        var opened: Route? = null

        render(state(wavelengthOnchainChains = setOf(chain)), onNavigateToRoute = { opened = it }) {
            onNode(hasText("Wavelength wallet") and hasText("On " + GroupWalletStatementEvent.chainName(chain))).assertIsDisplayed()
            onNodeWithText("Wavelength wallet").performClick()
        }

        assertEquals(
            GroupWavelengthWalletRoute(activeUserPublicKey, chatRoomId, GroupWalletStatementEvent.chainName(chain)),
            opened,
        )
    }

    /** Its coins leave to the onchain wallet, so until there is one it says so and opens nothing. */
    @Test
    fun `the Wavelength wallet's row waits for an onchain wallet`() {
        if (!GroupWalletRollout.ENABLED || !GroupWavelengthRollout.ENABLED) return
        var opened: Route? = null

        render(state(), onNavigateToRoute = { opened = it }) {
            onNode(hasText("Wavelength wallet") and hasText(WAVELENGTH_WAITS)).assertIsDisplayed()
            onNodeWithText("Wavelength wallet").performClick()
        }

        assertNull(opened)
    }

    /**
     * The row asks about its own network and not whether the group has any wallet at all. A
     * network that is not offered standing in for one that is would make the gate meaningless
     * the moment `NETWORKS` holds more than one chain again -- it held signet until 7 November
     * 2026, and `allows` still takes it.
     */
    @Test
    fun `the Wavelength wallet's row waits on its own network, not another`() {
        if (!GroupWalletRollout.ENABLED || !GroupWavelengthRollout.ENABLED) return
        val other = Chain.Signet.takeIf { it !in GroupWavelengthRollout.NETWORKS } ?: return
        var opened: Route? = null

        render(state(wavelengthOnchainChains = setOf(other)), onNavigateToRoute = { opened = it }) {
            onNode(hasText("Wavelength wallet") and hasText(WAVELENGTH_WAITS)).assertIsDisplayed()
            onNodeWithText("Wavelength wallet").performClick()
        }

        assertNull(opened)
    }

    @Test
    fun `the group's screen has no nostr profile, relays, posts or paste`() = render(state()) {
        onNodeWithText("Nostr profile").assertDoesNotExist()
        onNodeWithText("Relays").assertDoesNotExist()
        onNodeWithText("Posts").assertDoesNotExist()
        onNodeWithText("Propose event").assertDoesNotExist()

        // The rest of the screen is there, so the rows' absence is an absence
        // rather than a screen that failed to draw.
        onNodeWithText("Subgroups").assertIsDisplayed()
    }

    @Test
    fun `a NIP-17 room has no key to sign with and so no wallet row`() = render(
        state(mlsGroupState = null)
    ) {
        onNodeWithText(WALLET_ROW).assertDoesNotExist()
        onNodeWithText("Subgroups").assertIsDisplayed()
    }

    /** The wallet row is named for its room: the fixture's subject. */
    private val WALLET_ROW = "Translation room onchain wallet"

    /** What a Wavelength row says while there is no onchain wallet on its network. */
    private val WAVELENGTH_WAITS = "A Wavelength wallet is only available once the group has an onchain wallet."

    private fun state(
        mlsGroupState: String? = "state",
        groupWallet: GroupWallet? = null,
        wavelengthOnchainChains: Set<Chain> = emptySet(),
    ) = ChatRoomDetailUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(mlsGroupState),
        groupKeyState = GroupSignedWorkFixtures.groupKeyState,
        groupWallet = groupWallet,
        wavelengthOnchainChains = wavelengthOnchainChains,
    )

    /** An onchain wallet on the Lightning wallet's network; only its presence matters to these rows. */
    private val onchainWallet: GroupWallet by lazy {
        val account = GroupWalletDerivation.account(
            PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
            GroupLightningRollout.NETWORK,
        )
        GroupWallet(
            signedEvent = GroupSignedEvent(
                id = "e".repeat(64),
                chatRoomId = chatRoomId,
                publicKey = chatRoomId,
                kind = GroupWalletStatementEvent.KIND,
                tags = GroupWalletStatementEvent.template(account).tags,
                content = account.xpub,
                signature = "f".repeat(128),
                createdAt = Instant.fromEpochSeconds(1_700_000_000),
            ),
            account = account,
        )
    }

    /**
     * The screen at a phone's width and a window tall enough to compose the whole
     * of it, since a `LazyColumn` does not lay out what it would not show.
     *
     * The state is passed in rather than loaded, so the NO_OP repositories are
     * never asked for anything: `initiateChatRoomDetail` only runs for a screen
     * that arrives in [ChatRoomDetailUIState.Loading].
     */
    private fun render(
        uiState: ChatRoomDetailUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
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
