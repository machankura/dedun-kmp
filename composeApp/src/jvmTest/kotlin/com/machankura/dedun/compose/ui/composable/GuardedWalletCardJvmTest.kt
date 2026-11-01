package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.FakeElectrum
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.LineageExample
import com.machankura.dedun.compose.managers.LineageExample.heldBy
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupSendRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.state.GroupWalletUIState
import com.machankura.dedun.compose.ui.view.state.GuardedWalletRow
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * A descendant's guarded wallet on an ancestor's wallet screen: a card whose
 * actions sit under the name rather than squeezing it, each one telling a
 * screen reader which wallet it acts on. `docs/guarded-wallet-funding.md`, Phase 5.
 */
@OptIn(ExperimentalTestApi::class)
class GuardedWalletCardJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val descendantRoomId = "c".repeat(64)

    private fun account(seed: String) = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
    )

    /** A descendant's wallet guarded by this room, at the default delay, as a verified statement would be. */
    private fun guardedAccount(seed: String) = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
        guardians = GuardianDerivation.grant(PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0')))).publicKey().toString(), chatRoomId, emptyList(), descendantRoomId),
    )

    private fun holding(account: GroupWalletDerivation.Account, sats: Long, height: Long, tip: Int): GroupWalletState {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val next = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 1)
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 3 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(sats), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(0)
        return GroupWalletState(
            account,
            WalletState(mapOf(
                derived.address(Chain.Regtest) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(tx.txid, 0, height, tx, meta))),
                next.address(Chain.Regtest) to WalletState.AddressState(WalletState.AddressMeta.Derived(1), false, emptyList()),
            )),
            WalletState.empty,
            tipHeight = tip,
        )
    }

    private fun loaded(name: String): GroupWalletUIState.Loaded {
        val own = account("1c0ffee")
        val descendant = guardedAccount("decaf")
        val guarded = GuardianManager.GuardedWallet(
            ancestorChatRoomId = chatRoomId,
            descendantChatRoomId = descendantRoomId,
            statement = Event("1".repeat(64), descendantRoomId, 1_700_000_000, GroupWalletStatementEvent.KIND, emptyArray(), "", ""),
            account = descendant,
            certificate = Event("2".repeat(64), chatRoomId, 1_700_000_000, 0, arrayOf(arrayOf("name", name)), "", ""),
            lineage = listOf(descendantRoomId),
        )
        val wallet = GroupWallet(
            GroupSignedEvent(
                id = "e".repeat(64), chatRoomId = chatRoomId, publicKey = chatRoomId, kind = GroupWalletStatementEvent.KIND,
                tags = GroupWalletStatementEvent.template(own).tags, content = own.xpub, signature = "f".repeat(128),
                createdAt = Instant.fromEpochSeconds(1_700_000_000),
            ),
            own,
        )
        val room = LocalChatRoom(ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "Treasury", description = null, initialGiftWrapPayloadId = "p", mlsGroupState = "state"))
        return GroupWalletUIState.Loaded(
            localChatRoom = room,
            wallet = wallet,
            walletState = holding(own, 100_000, height = 100, tip = 200),
            canSign = true,
            guardedWallets = listOf(GuardedWalletRow(guarded, holding(descendant, 50_000, height = 0, tip = 200))),
        )
    }

    private fun render(state: GroupWalletUIState, fontScale: Float = 1f, width: Int = 400, onRoute: (Route) -> Unit = {}, assertions: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = 2400) {
            setContent {
                BennuTheme {
                    ProvideSnackbarHost {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                GroupWalletScreen(
                                    activeUserPublicKey = "a".repeat(64),
                                    chatRoomId = chatRoomId,
                                    chain = Chain.Regtest,
                                    initialGroupWalletUIState = state,
                                    chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                                    frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                                    // Never asked: the state is given, so nothing is watched.
                                    wallets = GroupOnchainWallets(FakeElectrum(), CoroutineScope(SupervisorJob())),
                                    onNavigateToRoute = onRoute,
                                    onNavigateBack = {},
                                )
                            }
                        }
                    }
                }
            }
            assertions()
        }

    @Test
    fun `each guarded wallet offers fund beside spend, each naming its wallet`() = render(loaded("Savings")) {
        onNodeWithText("Savings").assertIsDisplayed()
        onNodeWithText("0 sats within this group's reach · 50,000 sats arriving").assertIsDisplayed()
        onNodeWithContentDescription("Fund Savings's guarded wallet").assertIsDisplayed()
        onNodeWithContentDescription("Spend from Savings's guarded wallet").assertIsDisplayed()
    }

    @Test
    fun `fund opens the send form paying the descendant, from the room's own wallet`() {
        var opened: Route? = null
        render(loaded("Savings"), onRoute = { opened = it }) {
            onNodeWithContentDescription("Fund Savings's guarded wallet").performClick()
        }
        assertEquals(
            GroupSendRoute(activeUserPublicKey = "a".repeat(64), chatRoomId = chatRoomId, relayHint = null, fundChatRoomId = descendantRoomId),
            opened,
        )
    }

    @Test
    fun `a device that cannot sign is offered neither`() = render(loaded("Savings").copy(canSign = false)) {
        onNodeWithText("Savings").assertIsDisplayed()
        onNodeWithText("Fund").assertDoesNotExist()
        onNodeWithText("Spend").assertDoesNotExist()
    }

    @Test
    fun `at twice the text size the buttons stay whole`() = render(loaded("Savings"), fontScale = 2f) {
        onNodeWithContentDescription("Fund Savings's guarded wallet").assertIsDisplayed()
        onNodeWithContentDescription("Spend from Savings's guarded wallet").assertIsDisplayed()
    }

    // ---- Members' wallets by person: docs/member-wallet-guarding-group.md, Phase 5

    /** R's wallet screen, guarding Alice's Savings (under M, below P) and Rent (under N). */
    private fun atR(vararg guarded: GuardianManager.GuardedWallet): GroupWalletUIState.Loaded =
        loaded("Savings").copy(guardedWallets = guarded.map { GuardedWalletRow(it, walletState = null) })

    private val savings = heldBy(LineageExample.r, LineageExample.savings, LineageExample.savingsGuardians, label = "Savings", guardianName = "#M", branchName = "#P")
    private val rent = heldBy(LineageExample.r, LineageExample.rent, LineageExample.rentGuardians, label = "Rent", guardianName = "#N", branchName = "#N")

    @Test
    fun `a member's two wallets under two groups are one header, each card naming its group`() = render(atR(savings, rent)) {
        onNodeWithText("Members' wallets").assertIsDisplayed()
        onNodeWithText("@alice").assertIsDisplayed()
        onNodeWithText("2 wallets, guarded by 2 groups").assertIsDisplayed()
        onNodeWithText("Rent").assertIsDisplayed()
        onNodeWithText("Guarded by #N").assertIsDisplayed()
        onNodeWithText("Savings").assertIsDisplayed()
        onNodeWithText("Guarded by #M, below #P").assertIsDisplayed()
    }

    @Test
    fun `an unlabelled wallet is named by the group that guards it`() =
        render(atR(heldBy(LineageExample.r, LineageExample.savings, LineageExample.savingsGuardians, branchName = "#P"))) {
            // No name this device can check for M: where it sits instead.
            onNodeWithText("Guarded by a group below #P").assertIsDisplayed()
            onNodeWithText("1 wallet, guarded by 1 group").assertIsDisplayed()
        }

    @Test
    fun `each of a member's buttons names its wallet`() = render(atR(savings, rent, heldBy(LineageExample.r, "9".repeat(64), LineageExample.rentGuardians, guardianName = "#N", branchName = "#N"))) {
        onNodeWithContentDescription("Fund @alice's guarded wallet “Savings”").assertIsDisplayed()
        onNodeWithContentDescription("Spend from @alice's guarded wallet “Savings”").assertIsDisplayed()
        onNodeWithContentDescription("Fund @alice's guarded wallet “Rent”").assertIsDisplayed()
        onNodeWithContentDescription("Spend from @alice's guarded wallet “Rent”").assertIsDisplayed()
        onNodeWithContentDescription("Fund @alice's wallet guarded by #N").assertIsDisplayed()
        onNodeWithContentDescription("Spend from @alice's wallet guarded by #N").assertIsDisplayed()
    }

    @Test
    fun `a wallet this group guards directly says so`() =
        render(atR(heldBy(LineageExample.m, LineageExample.savings, LineageExample.savingsGuardians, label = "Savings", guardianName = "#M"))) {
            onNodeWithText("Guarded by this group").assertIsDisplayed()
        }

    @Test
    fun `the header opens the member's profile`() {
        var opened: Route? = null
        render(atR(savings), onRoute = { opened = it }) {
            onNodeWithText("@alice").performClick()
        }
        assertEquals(ProfilePreviewRoute(activeUserPublicKey = "a".repeat(64), profilePublicKey = LineageExample.alice), opened)
    }
}
