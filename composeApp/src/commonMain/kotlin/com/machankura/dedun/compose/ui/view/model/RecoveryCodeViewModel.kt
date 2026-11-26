package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptRecoveryCodesResult
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.RecoveryCodeManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeKeys
import com.machankura.dedun.compose.ui.view.state.BackupCheckState
import com.machankura.dedun.compose.ui.view.state.RecoveryCodeUIState
import kotlin.random.Random

/**
 * Reads back the recovery code behind the open profile, and checks a written-down copy.
 *
 * [RecoveryPhraseViewModel]'s counterpart, with the same rules: the code is read on a press
 * and held only while it is on screen, and the backup flags are the same per-profile
 * preferences. What is new is [checkBackup]: two blocks typed back from the paper. A typo in
 * them is a typo on the paper, and the code makes the check two short fields rather than a
 * list of words. See docs/recovery-code-v3.md, Phase 6.
 */
class RecoveryCodeViewModel(
    val phoenixGlobal: PhoenixGlobal,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    random: Random = Random.Default,
    private val readCodes: () -> DecryptRecoveryCodesResult = { RecoveryCodeManager.loadAndDecrypt(phoenixGlobal) },
) : ViewModel() {

    private val logger = Logger.withTag(TAG)

    private val _uiState = MutableStateFlow<RecoveryCodeUIState>(RecoveryCodeUIState.Hidden)
    val uiState = _uiState.asStateFlow()

    private val _backupCheck = MutableStateFlow<BackupCheckState>(BackupCheckState.Idle)
    val backupCheck = _backupCheck.asStateFlow()

    /**
     * The two blocks the check asks for, 1-based, in order. Never block 1, which starts `LB`
     * on every code and would check little. Chosen once per visit to the screen.
     */
    val checkBlocks: Pair<Int, Int> = (2..9).shuffled(random).take(2).sorted().let { it[0] to it[1] }

    @OptIn(ExperimentalCoroutinesApi::class)
    val isDisclaimerRead: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isSeedLossDisclaimerRead ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isBackupDone: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isManualSeedBackupDone ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True until the backup is checked and the disclaimer read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val showBackupNotice: StateFlow<Boolean> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.showSeedBackupNotice ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What [read] found: the code, or the error to show. */
    private sealed interface Read {
        data class Found(val code: RecoveryCode) : Read
        data class Missing(val error: RecoveryCodeUIState.Error) : Read
    }

    private suspend fun read(id: WalletId): Read {
        // Keystore-backed, and it reads a file: never on the main thread.
        val result = withContext(Dispatchers.IO) { readCodes() }
        val stored = when (result) {
            is DecryptRecoveryCodesResult.Success -> result.codes[id]
            is DecryptRecoveryCodesResult.Failure.FileNotFound -> null
            is DecryptRecoveryCodesResult.Failure -> {
                logger.e { "unable to read the recovery codes: $result" }
                return Read.Missing(RecoveryCodeUIState.Error.CodesUnreadable)
            }
        }
        val parsed = stored?.let { RecoveryCode.parse(it) as? RecoveryCode.Parse.Valid }
        if (parsed == null) {
            logger.e { "the codes file holds no valid code for wallet=$id" }
            return Read.Missing(RecoveryCodeUIState.Error.NoCodeForThisProfile)
        }
        return Read.Found(parsed.code)
    }

    fun reveal() {
        if (_uiState.value is RecoveryCodeUIState.Revealing) return
        val id = activeIdentityStateFlow.value?.id
        if (id == null) {
            _uiState.value = RecoveryCodeUIState.Error.NoActiveProfile
            return
        }
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("failed to read the recovery code", throwable)
            _uiState.value = RecoveryCodeUIState.Error.CodesUnreadable
        }) {
            _uiState.value = RecoveryCodeUIState.Revealing
            _uiState.value = when (val read = read(id)) {
                is Read.Found -> RecoveryCodeUIState.Revealed(read.code)
                is Read.Missing -> read.error
            }
        }
    }

    /** The 24 words, under the code that is already on screen. */
    fun revealWords() {
        val revealed = _uiState.value as? RecoveryCodeUIState.Revealed ?: return
        _uiState.value = revealed.copy(words = RecoveryCodeKeys.words(revealed.code))
    }

    fun hideWords() {
        val revealed = _uiState.value as? RecoveryCodeUIState.Revealed ?: return
        _uiState.value = revealed.copy(words = null)
    }

    fun hide() {
        _uiState.value = RecoveryCodeUIState.Hidden
    }

    /**
     * Compares [first] and [second], typed from the paper, with the code's [checkBlocks]. Reads
     * the code for the comparison and drops it after, whether or not it is on screen. A match
     * records the backup as done; a mismatch records nothing.
     */
    fun checkBackup(first: String, second: String) {
        if (_backupCheck.value is BackupCheckState.Checking) return
        val identity = activeIdentityStateFlow.value ?: return
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("failed to check the backup", throwable)
            _backupCheck.value = BackupCheckState.Unreadable
        }) {
            _backupCheck.value = BackupCheckState.Checking
            val code = when (val read = read(identity.id)) {
                is Read.Found -> read.code
                is Read.Missing -> {
                    _backupCheck.value = BackupCheckState.Unreadable
                    return@launch
                }
            }
            val blocks = code.blocks
            val matches = normalise(first) == blocks[checkBlocks.first - 1] && normalise(second) == blocks[checkBlocks.second - 1]
            if (matches) {
                identity.internalPrefs.saveManualSeedBackupDone(true)
                _backupCheck.value = BackupCheckState.Matched
            } else {
                _backupCheck.value = BackupCheckState.Mismatch
            }
        }
    }

    private fun normalise(block: String): String = block.uppercase().filter { !it.isWhitespace() && it != '-' }

    fun setDisclaimerRead(isRead: Boolean) {
        val internalPrefs = activeIdentityStateFlow.value?.internalPrefs ?: return
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("could not save the disclaimer flag", throwable)
        }) {
            internalPrefs.saveSeedLossDisclaimerRead(isRead)
        }
    }

    companion object {
        private const val TAG = "RecoveryCodeViewModel"

        fun factory(
            phoenixGlobal: PhoenixGlobal,
            activeIdentityStateFlow: StateFlow<Identity?>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecoveryCodeViewModel(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                )
            }
        }
    }
}
