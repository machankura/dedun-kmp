package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.identity.NewProfileWriter
import com.machankura.dedun.compose.repository.MarmotRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.CreateProfileUIState
import kotlin.test.Test

/**
 * What the create screen no longer offers -- Phase 5 of docs/multiple-profiles.md.
 *
 * "End this" wiped the database: every profile's rooms, MLS state, key packages and
 * queues, from a screen whose purpose is to add a profile. With several profiles on a
 * device it was a way to lose the others. The state that carried it is composed and
 * looked at; the words are not there.
 */
@OptIn(ExperimentalTestApi::class)
class CreateProfileScreenJvmTest {

    @Test
    fun `the screen that adds a profile cannot wipe the others`() = runDesktopComposeUiTest(400, 900) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    CreateProfileScreen(
                        initialCreateProfileUIState = CreateProfileUIState.ProfileReady(
                            profile = Profile(publicKey = "ab".repeat(32), nostrEventId = "e".repeat(64), displayName = "Alan"),
                        ),
                        onNavigateBack = {},
                        nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                        marmotRepository = MarmotRepository.NO_OP_MARMOT_KEY_PACKAGE_BUNDLE,
                        newProfile = NewProfileWriter { Result.failure(IllegalStateException("not reached")) },
                        onProfileCreated = {},
                    )
                }
            }
        }

        onNodeWithText("Profile is ready", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("End this", useUnmergedTree = true).assertDoesNotExist()
    }
}
