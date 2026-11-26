package com.machankura.dedun.compose.ui.view.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptRecoveryCodesResult
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeKeys
import com.machankura.dedun.compose.ui.view.state.BackupCheckState
import com.machankura.dedun.compose.ui.view.state.RecoveryCodeUIState
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The code screen's reads and its check, with the codes file faked at the one function that
 * reads it. See docs/recovery-code-v3.md, Phase 6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryCodeViewModelJvmTest {

    private val code = (RecoveryCode.parse("LBZC-MYTK-E9P3-7C7B-AHJN-KX79-22CY-WAY7-K7QZ") as RecoveryCode.Parse.Valid).code
    private val id = WalletId("ab".repeat(20))

    private fun prefsFile(name: String) =
        Files.createTempFile(name, ".preferences_pb").also { it.toFile().delete() }.toString().toPath()

    private val internalPrefs = InternalPrefs(PreferenceDataStoreFactory.createWithPath { prefsFile("internal") })

    private val identity = Identity.signing(
        id = id,
        kind = IdentityKind.RecoveryCode,
        nostrPrivateKey = PrivateKey(ByteVector32("0a".repeat(32))),
        userPrefs = UserPrefs(PreferenceDataStoreFactory.createWithPath { prefsFile("user") }),
        internalPrefs = internalPrefs,
        // A running code profile has its node, but the screen reads only the codes file.
        business = null,
    )

    private val phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = Files.createTempDirectory("dedun-code-vm").toFile()))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        active: Identity? = identity,
        read: () -> DecryptRecoveryCodesResult = { DecryptRecoveryCodesResult.Success(mapOf(id to code.canonical)) },
    ) = RecoveryCodeViewModel(phoenixGlobal, MutableStateFlow(active), Random(7), read)

    private suspend fun <T> RecoveryCodeViewModel.awaitState(pick: (RecoveryCodeViewModel) -> T, done: (T) -> Boolean): T =
        withTimeout(5_000) {
            while (!done(pick(this@awaitState))) delay(10)
            pick(this@awaitState)
        }

    @Test
    fun `the code is read on a press, and hidden again`() = runBlocking<Unit> {
        val viewModel = viewModel()
        assertIs<RecoveryCodeUIState.Hidden>(viewModel.uiState.value)

        viewModel.reveal()
        val revealed = assertIs<RecoveryCodeUIState.Revealed>(viewModel.awaitState({ it.uiState.value }) { it is RecoveryCodeUIState.Revealed })
        assertEquals(code, revealed.code)
        assertEquals(null, revealed.words)

        viewModel.hide()
        assertIs<RecoveryCodeUIState.Hidden>(viewModel.uiState.value)
    }

    @Test
    fun `the words are behind a press of their own, under the code`() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.revealWords()
        assertIs<RecoveryCodeUIState.Hidden>(viewModel.uiState.value, "no words without the code")

        viewModel.reveal()
        viewModel.awaitState({ it.uiState.value }) { it is RecoveryCodeUIState.Revealed }
        viewModel.revealWords()
        assertEquals(RecoveryCodeKeys.words(code), (viewModel.uiState.value as RecoveryCodeUIState.Revealed).words)

        viewModel.hideWords()
        assertEquals(null, (viewModel.uiState.value as RecoveryCodeUIState.Revealed).words)
    }

    @Test
    fun `an unreadable file and a missing code are two errors`() = runBlocking<Unit> {
        val unreadable = viewModel(read = { DecryptRecoveryCodesResult.Failure.FileUnreadable })
        unreadable.reveal()
        assertEquals(RecoveryCodeUIState.Error.CodesUnreadable, unreadable.awaitState({ it.uiState.value }) { it is RecoveryCodeUIState.Error })

        val missing = viewModel(read = { DecryptRecoveryCodesResult.Failure.FileNotFound })
        missing.reveal()
        assertEquals(RecoveryCodeUIState.Error.NoCodeForThisProfile, missing.awaitState({ it.uiState.value }) { it is RecoveryCodeUIState.Error })

        val noProfile = viewModel(active = null)
        noProfile.reveal()
        assertEquals(RecoveryCodeUIState.Error.NoActiveProfile, noProfile.uiState.value)
    }

    @Test
    fun `the check never asks for block 1`() {
        repeat(50) { seed ->
            val (first, second) = RecoveryCodeViewModel(phoenixGlobal, MutableStateFlow(identity), Random(seed)).checkBlocks
            assertTrue(first in 2..9 && second in 2..9 && first < second, "$first, $second")
        }
    }

    @Test
    fun `the right two blocks record the backup, typed any case`() = runBlocking<Unit> {
        val viewModel = viewModel()
        val (first, second) = viewModel.checkBlocks
        assertFalse(internalPrefs.isManualSeedBackupDone.first())

        viewModel.checkBackup(code.blocks[first - 1].lowercase(), " ${code.blocks[second - 1]} ")

        assertEquals(BackupCheckState.Matched, viewModel.awaitState({ it.backupCheck.value }) { it != BackupCheckState.Idle && it != BackupCheckState.Checking })
        assertTrue(internalPrefs.isManualSeedBackupDone.first())
    }

    @Test
    fun `the wrong blocks record nothing`() = runBlocking<Unit> {
        val viewModel = viewModel()
        val (first, second) = viewModel.checkBlocks
        assertNotEquals(code.blocks[first - 1], code.blocks[second - 1])

        viewModel.checkBackup(code.blocks[second - 1], code.blocks[first - 1])

        assertEquals(BackupCheckState.Mismatch, viewModel.awaitState({ it.backupCheck.value }) { it != BackupCheckState.Idle && it != BackupCheckState.Checking })
        assertFalse(internalPrefs.isManualSeedBackupDone.first())
    }
}
