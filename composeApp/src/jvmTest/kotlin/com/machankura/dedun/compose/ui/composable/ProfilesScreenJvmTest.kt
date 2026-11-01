package com.machankura.dedun.compose.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.ListWalletState
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import okio.Path.Companion.toPath
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.StoredIdentity
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.ProfilesViewModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The switcher, drawn -- Phase 4 of docs/multiple-profiles.md.
 *
 * Three profiles: A, with a wallet attached and open; B, a bare key; C, read only. What
 * is pinned is what each row says before the tap -- the nostr name where the device has
 * one and the npub where it does not, *wallet* and *read only* where they apply and
 * nothing for the plain case -- that the open one sits above the others, and that
 * tapping another row is exactly one call with its id while tapping the open one is
 * none. On the unmerged tree throughout, as `ReadOnlyEntrancesJvmTest` explains.
 */
@OptIn(ExperimentalTestApi::class)
class ProfilesScreenJvmTest {

    private val keyA = PrivateKey(ByteVector32("0a".repeat(32)))
    private val keyB = PrivateKey(ByteVector32("0b".repeat(32)))
    private val keyC = PrivateKey(ByteVector32("0c".repeat(32)))

    private val walletId = WalletId("aa".repeat(20))
    private val a = StoredIdentity.Mnemonic(UserWallet(walletId, nodeId = "02" + "cd".repeat(32), words = List(12) { "abandon" }), keyA)
    private val b = StoredIdentity.nostrSecret(keyB)
    private val c = StoredIdentity.nostrPublic(keyC.nostrPublicKeyHex())
    private val identities = linkedMapOf(a.id to a, b.id to b, c.id to c)

    private fun prefsFile(name: String) =
        Files.createTempFile(name, ".preferences_pb").also { it.toFile().delete() }.toString().toPath()

    private val open = Identity.signing(
        id = a.id,
        kind = IdentityKind.Mnemonic,
        nostrPrivateKey = keyA,
        userPrefs = UserPrefs(PreferenceDataStoreFactory.createWithPath { prefsFile("user") }),
        internalPrefs = InternalPrefs(PreferenceDataStoreFactory.createWithPath { prefsFile("internal") }),
        business = null,
    )

    /** A has a kind 0; B and C do not. */
    private val profiles = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?> = flowOf(
            if (publicKey == a.nostrPublicKey) Profile(publicKey = publicKey, displayName = "Alice", nostrEventId = "e".repeat(64)) else null
        )
    }

    private val metadata = identities.keys.associateWith { id ->
        UserWalletMetadata(walletId = id, name = null, avatar = "🦊", createdAt = null, isHidden = false)
    }

    private val switched = mutableListOf<WalletId>()
    private val taps = mutableListOf<String>()

    private fun viewModel(listState: ListWalletState = ListWalletState.Success) = ProfilesViewModel(
        listWalletState = MutableStateFlow(listState),
        availableIdentities = MutableStateFlow(identities),
        activeIdentity = MutableStateFlow(open),
        walletsMetadata = flowOf(metadata),
        nostrRepository = profiles,
    )

    @Composable
    private fun Screen(viewModel: ProfilesViewModel, onRetry: () -> Unit = {}) {
        DedunTheme {
            ProvideSnackbarHost {
                ProfilesScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onSwitch = { switched += it },
                    onRetry = onRetry,
                    onSignIn = { taps += "sign in" },
                    onCreateProfile = { taps += "create" },
                )
            }
        }
    }

    private fun ComposeUiTest.awaitLoaded() = waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithText("Alice", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `each row says what the device holds, and the open one sits above the others`() = runDesktopComposeUiTest(400, 900) {
        setContent { Screen(viewModel()) }
        awaitLoaded()

        // A by its nostr name, with its npub under it and a wallet attached.
        onNodeWithText("Alice", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(a.nostrPublicKey.hexToNpubHrp(), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Wallet", useUnmergedTree = true).assertIsDisplayed()
        // B and C by their npub, since the device has no kind 0 for either; only C carries a label.
        onNodeWithText(b.nostrPublicKey.hexToNpubHrp(), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(c.nostrPublicKey.hexToNpubHrp(), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Read only", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Default name", useUnmergedTree = true).assertDoesNotExist()

        val openTop = onNodeWithText("Alice", useUnmergedTree = true).getBoundsInRoot().top
        val otherTop = onNodeWithText(b.nostrPublicKey.hexToNpubHrp(), useUnmergedTree = true).getBoundsInRoot().top
        assertTrue(openTop < otherTop, "the open profile is drawn above the others")
    }

    @Test
    fun `tapping another profile switches to it once, and tapping the open one does nothing`() = runDesktopComposeUiTest(400, 900) {
        setContent { Screen(viewModel()) }
        awaitLoaded()

        onNodeWithText("Alice", useUnmergedTree = true).performClick()
        assertEquals(emptyList(), switched)

        onNodeWithText(b.nostrPublicKey.hexToNpubHrp(), useUnmergedTree = true).performClick()
        assertEquals(listOf(b.id), switched)
    }

    @Test
    fun `an unreadable key file is an error with a retry`() = runDesktopComposeUiTest(400, 900) {
        var retried = 0
        setContent { Screen(viewModel(ListWalletState.Error.Serialization), onRetry = { retried++ }) }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Try again", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Try again", useUnmergedTree = true).performClick()
        assertEquals(1, retried)
    }

    /** The two ways to add a profile, under the list, each pushing its own screen. */
    @Test
    fun `under the list are the two ways to add a profile, and the one that is open stays`() = runDesktopComposeUiTest(400, 900) {
        setContent { Screen(viewModel()) }
        awaitLoaded()

        onNodeWithText("The profile you are in stays on this device.", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sign in with a key", useUnmergedTree = true).performClick()
        onNodeWithText("Create a new profile", useUnmergedTree = true).performClick()

        assertEquals(listOf("sign in", "create"), taps)
        assertEquals(emptyList(), switched, "adding is not switching")
    }

    @Test
    fun `the list is a pushed screen, with a way back`() = runDesktopComposeUiTest(400, 900) {
        setContent { Screen(viewModel()) }
        awaitLoaded()

        onNodeWithText("Profiles", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("Back", useUnmergedTree = true).assertIsDisplayed()
    }
}
