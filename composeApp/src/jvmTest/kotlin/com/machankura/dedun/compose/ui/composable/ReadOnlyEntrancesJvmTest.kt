package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.GroupLightningRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path.Companion.toPath
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.nostr.SignInSync
import java.nio.file.Files
import kotlin.time.Instant
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ActiveProfileUIState
import com.machankura.dedun.compose.ui.view.state.HomeScreenUIState
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The inventory in Phase 5 of docs/npub-sign-in.md, as assertions.
 *
 * Every write entrance one tap from the three tabs has a row there, and a row without an
 * assertion is a row that will regress: the compiler cannot say whether the inventory is
 * complete, since `LocalCanSign` is read where a control is drawn and nothing forces a
 * control to read it. So each screen is composed twice, once as an identity that can
 * sign and once as one that cannot, and the controls are looked for by their text.
 *
 * The repositories are the no-op ones with the reads each screen makes delegated to a
 * fixed answer, as `ChatPaneLayoutJvmTest` does.
 */
@OptIn(ExperimentalTestApi::class)
class ReadOnlyEntrancesJvmTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"
    private val eventId = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"

    // --- Home: the room list, or the empty state with the upgrade ---

    @Test
    fun `an identity that can sign is offered a new chat, and no upgrade`() = runDesktopComposeUiTest(400, 900) {
        setContent { AsIdentity(canSign = true) { Home() } }

        onNodeWithText("New chat", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sign in with the nsec", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a read-only identity is offered the upgrade, and no new chat`() = runDesktopComposeUiTest(400, 900) {
        setContent { AsIdentity(canSign = false) { Home() } }

        onNodeWithText("Sign in with the nsec", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("New chat", useUnmergedTree = true).assertDoesNotExist()
    }

    /** One column at every width: there is no list to put a detail beside. */
    @Test
    fun `a read-only identity in a large window still has one column`() = runDesktopComposeUiTest(1400, 900) {
        setContent { AsIdentity(canSign = false) { Home() } }

        onNodeWithText("Sign in with the nsec", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("New chat", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Pick a conversation to read it here.", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Profile preview: the one thing to do about a person, or nothing ---

    @Test
    fun `an identity that can sign is offered a chat with the person it looked up`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { ProfilePreview() } }

        onNodeWithText("Reader", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Start new chat", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a read-only identity is shown the person and offered nothing`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = false) { ProfilePreview() } }

        onNodeWithText("Reader", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Start new chat", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Profile: key packages and key recovery, or neither ---

    @Test
    fun `an identity that can sign is offered key recovery and key packages`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { ActiveProfile() } }

        onNodeWithText("Key recovery", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Key package management", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sign out", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Switch profile", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a read-only identity is offered neither, and can still sign out`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = false) { ActiveProfile() } }

        onNodeWithText("Key recovery", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Key package management", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Sign out", useUnmergedTree = true).assertIsDisplayed()
        // A read-only identity can leave for another profile the same way a signing one can.
        onNodeWithText("Switch profile", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- Profile: deposit addresses only where there is a node (docs/personal-swap-in-wallet.md, Phase 3) ---

    @Test
    fun `a profile with a node is offered its onchain deposits`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { ActiveProfile(hasNode = true) } }
        onNodeWithText("Onchain deposits", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a profile without a node is not, even one that can sign`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { ActiveProfile(hasNode = false) } }
        onNodeWithText("Onchain deposits", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Profile: sign out is real for a read-only identity, and confirms first ---

    @Test
    fun `a read-only identity's sign out asks before it acts, naming the npub`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = false) { ActiveProfile(signOut = signOutDependencies) } }

        onNodeWithText("Sign out", useUnmergedTree = true).performClick()

        onNodeWithText("Sign out of this profile?", useUnmergedTree = true).assertIsDisplayed()
        // The screen shows the npub once already; the dialog names it a second time.
        onAllNodesWithText(publicKey.hexToNpubHrp(), substring = true, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithText("nothing to lose", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    // --- Not found: set one up, or use a different key ---

    @Test
    fun `an identity that can sign is offered the set-up form when its profile is not found`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { NotFound() } }

        onNodeWithText("Try again", useUnmergedTree = true).assertIsDisplayed()
        // Heading and button both say it; either is the form.
        onAllNodesWithText("Set up a profile", useUnmergedTree = true).onFirst().assertIsDisplayed()
        onNodeWithText("Use a different key", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a read-only identity is offered a different key instead, and it confirms first`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = false) { NotFound() } }

        onNodeWithText("Try again", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Set up a profile", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Use a different key", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithText("Use a different key", useUnmergedTree = true).performClick()

        onNodeWithText("Use a different key?", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- Not found: the third exit, when there is another profile to go to ---

    @Test
    fun `with another profile on the device the not-found state offers to switch, for either kind`() = runDesktopComposeUiTest(400, 1200) {
        var switched = 0
        setContent { AsIdentity(canSign = false) { NotFound(onSwitchProfile = { switched++ }) } }

        onNodeWithText("Switch profile", useUnmergedTree = true).performClick()
        assertEquals(1, switched)
        // The read-only exit is still there beside it; switching does not forget.
        onNodeWithText("Use a different key", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a signing identity that was not found is offered the switch above the set-up form`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { NotFound(onSwitchProfile = {}) } }

        onNodeWithText("Switch profile", useUnmergedTree = true).assertIsDisplayed()
        // The heading and the button both say it; either is enough to know the form is there.
        onAllNodesWithText("Set up a profile", useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun `with one profile on the device there is nothing to switch to`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = false) { NotFound(onSwitchProfile = null) } }

        onNodeWithText("Use a different key", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Switch profile", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- harness ---

    private val readOnlyIdentity = MutableStateFlow<Identity?>(
        Identity.readOnly(
            id = WalletId("ab".repeat(20)),
            nostrPublicKey = publicKey,
            userPrefs = UserPrefs(PreferenceDataStoreFactory.createWithPath { Files.createTempFile("user", ".preferences_pb").also { it.toFile().delete() }.toString().toPath() }),
            internalPrefs = InternalPrefs(PreferenceDataStoreFactory.createWithPath { Files.createTempFile("internal", ".preferences_pb").also { it.toFile().delete() }.toString().toPath() }),
        )
    )

    /** Never run in these tests: the dialogs are looked at, not confirmed. */
    private val signOutDependencies = SignOutDependencies(
        activeIdentityStateFlow = readOnlyIdentity,
        forgetNostrCredential = { error("not reached") },
        hideIdentityMetadata = { error("not reached") },
        onSignedOut = { error("not reached") },
    )

    @Composable
    private fun NotFound(onSwitchProfile: (() -> Unit)? = null) {
        UnsyncedProfileScreen(
            unsyncedProfilePublicKey = publicKey,
            nostrRepository = NotFoundAccount,
            signOut = signOutDependencies,
            onSwitchProfile = onSwitchProfile,
        )
    }

    private val at = Instant.fromEpochSeconds(1_700_000_000)

    /** An account whose one sign-in request has finished with nothing: the not-found state. */
    private val NotFoundAccount = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeLocalAccount(publicKey: String): Flow<LocalAccount?> = flowOf(
            LocalAccount(
                unsignedNostrEvent = UnsignedNostrEvent(
                    id = 1, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}",
                    signedAt = GENESIS_AT, createdAt = at, updatedAt = at, savedAt = at,
                ),
                nostrEvent = null,
                profile = null,
                broadcastNostrEventRequest = null,
                broadcastNostrEventReceipt = null,
                synchronizeNostrEventRequests = listOf(
                    SynchronizeNostrEventRequest(
                        id = "req", purpose = SignInSync.PURPOSE, status = SignInSync.STATUS_COMPLETE,
                        relayURL = "wss://relay.example", level = 0,
                        synchronizationFilters = arrayOf(SynchronizationFilter(authors = arrayOf(publicKey), kinds = SignInSync.KINDS)),
                        unsignedNostrEventId = 1, createdAt = at, updatedAt = at,
                    )
                ),
            )
        )
    }

    @Composable
    private fun AsIdentity(canSign: Boolean, content: @Composable () -> Unit) {
        DedunTheme {
            ProvideSnackbarHost {
                CompositionLocalProvider(LocalCanSign provides canSign) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun Home() {
        HomeScreen(
            activeUserPublicKey = publicKey,
            initialHomeScreenUIState = HomeScreenUIState.Loaded(profileWithFollowing = profile),
            onNavigateToRoute = {},
            onNavigateToDirectMessageDetail = {},
            onNavigateToChatRoomCreation = {},
            nostrRepository = FixedProfile,
            chatRepository = NoRooms,
            frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
            dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
            lightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
        )
    }

    /** The person behind a pasted npub, already found: the state is passed in, so nothing is asked. */
    @Composable
    private fun ProfilePreview() {
        ProfilePreviewScreen(
            activeUserPublicKey = publicKey,
            profilePublicKey = publicKey,
            initialProfilePreviewUIState = ProfilePreviewUIState.Loaded(
                profile = profile.profile,
                readiness = ProfilePreviewUIState.Readiness.CanStartChat,
            ),
            nostrRepository = FixedProfile,
            chatRepository = NoRooms,
            onStartChat = {},
            onNavigateBack = {},
        )
    }

    @Composable
    private fun ActiveProfile(signOut: SignOutDependencies? = null, hasNode: Boolean = false) {
        ActiveProfileScreen(
            initialActiveProfileUIState = ActiveProfileUIState.Loaded(
                localNostrEvent = LocalNostrEvent(nostrEvent = metadataEvent, profile = profile.profile),
            ),
            activeUserPublicKey = publicKey,
            nostrEventId = eventId,
            onNavigateBack = {},
            onNavigateToRoute = {},
            nostrRepository = FixedProfile,
            signOut = signOut,
            hasNode = hasNode,
        )
    }

    private val metadataEvent = NostrEvent(
        id = eventId,
        pubKey = publicKey,
        content = """{"name":"Reader"}""",
        tags = emptyArray(),
        sig = "",
        kind = MetadataEvent.KIND,
    )

    private val profile = LocalProfileWithFollowing(
        nostrEvent = NostrEvent(
            id = eventId,
            pubKey = publicKey,
            content = "",
            tags = emptyArray(),
            sig = "",
            kind = TextNoteEvent.KIND,
        ),
        profile = Profile(
            publicKey = publicKey,
            displayName = "Reader",
            nostrEventId = eventId,
        ),
        following = emptyList(),
    )

    private val FixedProfile = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeProfileWithFollowing(
            publicKey: String,
        ): Flow<LocalProfileWithFollowing?> = flowOf(profile)

        override suspend fun observeLocalNostrEventById(
            nostrEventId: String,
        ): Flow<LocalNostrEvent?> = flowOf(LocalNostrEvent(nostrEvent = metadataEvent, profile = profile.profile))
    }

    private val NoRooms = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun observeChatRoomListByPublicKey(
            publicKey: String,
        ): Flow<List<LocalChatRoom>> = flowOf(emptyList())
    }
}
