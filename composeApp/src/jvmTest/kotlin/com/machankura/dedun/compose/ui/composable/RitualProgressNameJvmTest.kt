package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test

/**
 * A finished ceremony's screen names the group it made, as its transcript line does:
 * the same people may have made more than one group in the room.
 * docs/nip17-multiple-ceremonies.md.
 */
@OptIn(ExperimentalTestApi::class)
class RitualProgressNameJvmTest {

    private fun finished(subject: String?) = DkgSession(
        id = "5e55".repeat(16),
        chatRoomId = "c3a1".repeat(16),
        coordinatorPublicKey = "b".repeat(64),
        userPublicKey = "a".repeat(64),
        threshold = 3,
        participantCount = 3,
        subject = subject,
        stage = DkgRitualStage.COMPLETE,
        hostPublicKey = "02" + "11".repeat(32),
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        thresholdPublicKey = "03" + "ae".repeat(32),
    )

    private fun render(session: DkgSession, text: String) =
        runDesktopComposeUiTest(width = 400, height = 1600) {
            setContent {
                DedunTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RitualProgress(
                            session = session,
                            participantCount = 3,
                            activeUserPublicKey = "a".repeat(64),
                            members = emptyList(),
                            hostKeyParticipants = emptySet(),
                            round1Participants = emptySet(),
                            round2Participants = emptySet(),
                            isActionPending = false,
                            adminGroupBlockedOn = emptyList(),
                            keyStateSession = null,
                            isKeyStateSigned = true,
                            isRoomCreated = true,
                            parentChatRoomId = null,
                            isCertified = false,
                            certificateSession = null,
                            onProposeBirthCertificate = {},
                            onProposeKeyState = {},
                            onCreateAdminGroup = {},
                        )
                    }
                }
            }
            onNodeWithText(text).assertExists()
        }

    @Test
    fun `a finished group is named`() = render(finished("Agora"), "Agora has a shared key.")

    @Test
    fun `a ceremony from before names keeps its words`() = render(finished(null), "The group has a shared key.")
}
