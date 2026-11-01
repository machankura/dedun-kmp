package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.repository.DepositRepository
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.network.LightningAddressResolver.Resolution
import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.ui.view.state.GroupPayoutDestinationUIState
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.machankura.dedun.compose.wallet.UnusedAddressCheck.Usage
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.utils.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a group pays this profile: a Lightning address, and optionally an onchain address
 * that has never been paid. `docs/group-payout-destination.md`, Phase 4.
 *
 * Nothing is sent until both checks have run on this device: the Lightning address asked of
 * its server (unless Tor is on, D6) and the onchain address asked of Electrum (D7). A check
 * that fails says why, under the field it is about, and sends nothing.
 */
class GroupPayoutDestinationViewModel(
    private val chatRoomId: String,
    private val chain: Chain,
    initialUIState: GroupPayoutDestinationUIState,
    private val repository: PayoutDestinationRepository,
    /** What a first destination leads to: the group asked to open this profile's deposits (`docs/member-deposits.md`). */
    private val depositRepository: DepositRepository = DepositRepository.NO_OP,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupPayoutDestinationUIState by mutableStateOf(initialUIState)
        private set

    val lightningField = TextFieldState()
    val onchainField = TextFieldState()

    /** Whether the Lightning field was filled from the profile's own `lud16`, which its label says. */
    var lightningFromProfile: Boolean by mutableStateOf(false)
        private set

    /** Why the Lightning address was refused, from the last [save]. */
    var lightningProblem: LightningProblem? by mutableStateOf(null)
        private set

    /** Why the onchain address was refused, from the last [save]. */
    var onchainProblem: OnchainProblem? by mutableStateOf(null)
        private set

    /** Whether a save or a removal is under way: the checks take seconds, and the fields are read-only meanwhile. */
    var isWorking: Boolean by mutableStateOf(false)
        private set

    /** Whether [initiate] has filled the fields, which it does once. */
    private var filled = false

    sealed interface LightningProblem {
        data object Empty : LightningProblem
        data object NotAnAddress : LightningProblem
        data object Lnurl : LightningProblem
        data object Offer : LightningProblem
        data object Invoice : LightningProblem
        data object Bip353 : LightningProblem
        data object OnionDomain : LightningProblem
        data object Unreachable : LightningProblem
        data object NotFound : LightningProblem
        data class Refused(val reason: String?) : LightningProblem
        data object NotAPayRequest : LightningProblem
        data object Malformed : LightningProblem
    }

    sealed interface OnchainProblem {
        data object NotAnAddress : OnchainProblem
        data object OtherNetwork : OnchainProblem
        data object Used : OnchainProblem
        data object Unknown : OnchainProblem
        data object TheGroups : OnchainProblem
    }

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val standing = repository.standing(chatRoomId, chain)
            withContext(Dispatchers.Main) {
                if (standing == null) {
                    uiState = GroupPayoutDestinationUIState.Error("Couldn't find the group")
                    return@withContext
                }
                if (!filled) {
                    filled = true
                    val current = standing.current
                    if (current != null) {
                        lightningField.setTextAndPlaceCursorAtEnd(current.lightningAddress.toString())
                        current.onchainAddress?.let { onchainField.setTextAndPlaceCursorAtEnd(it) }
                    } else {
                        standing.profileLightningAddress?.let(LightningAddress::parseOrNull)?.let {
                            lightningField.setTextAndPlaceCursorAtEnd(it.toString())
                            lightningFromProfile = true
                        }
                    }
                }
                uiState = GroupPayoutDestinationUIState.Loaded(standing)
            }
        }
    }

    /** Whether [save] can do anything: a group, a profile that signs, and nothing already under way. */
    fun canSave(): Boolean = (uiState as? GroupPayoutDestinationUIState.Loaded)?.standing?.let { it.isGroup && it.canSet } == true && !isWorking

    /** Whether there is a destination to remove. */
    fun canRemove(): Boolean = canSave() && (uiState as? GroupPayoutDestinationUIState.Loaded)?.standing?.current != null

    /**
     * Reads both fields, checks both addresses at once, and -- when neither is refused -- signs
     * and sends the destination. [onSaved] is told whether the Lightning address went unchecked
     * because Tor is on, so the screen can say so, and -- when this was the destination the
     * profile's deposits were waiting on and this device holds a share -- the signing session
     * that asks the group to open them, for the screen to move onto.
     */
    fun save(onSaved: (notChecked: Boolean, depositsSession: String?) -> Unit, onFailure: () -> Unit) {
        if (!canSave()) return
        lightningProblem = null
        onchainProblem = null

        val address = when (val parsed = LightningAddress.parse(lightningField.text.toString())) {
            is LightningAddress.Parsed.Address -> parsed.address
            LightningAddress.Parsed.Empty -> return run { lightningProblem = LightningProblem.Empty }
            LightningAddress.Parsed.NotAnAddress -> return run { lightningProblem = LightningProblem.NotAnAddress }
            LightningAddress.Parsed.Lnurl -> return run { lightningProblem = LightningProblem.Lnurl }
            LightningAddress.Parsed.Offer -> return run { lightningProblem = LightningProblem.Offer }
            LightningAddress.Parsed.Invoice -> return run { lightningProblem = LightningProblem.Invoice }
            LightningAddress.Parsed.Bip353 -> return run { lightningProblem = LightningProblem.Bip353 }
            LightningAddress.Parsed.OnionDomain -> return run { lightningProblem = LightningProblem.OnionDomain }
        }
        val onchain = when (val parsed = PayoutAddress.parse(chain, onchainField.text.toString())) {
            is Either.Right -> parsed.value
            is Either.Left -> when (parsed.value) {
                PayoutAddress.Problem.Empty -> null
                PayoutAddress.Problem.NotAnAddress -> return run { onchainProblem = OnchainProblem.NotAnAddress }
                PayoutAddress.Problem.OtherNetwork -> return run { onchainProblem = OnchainProblem.OtherNetwork }
            }
        }

        isWorking = true
        viewModelScope.launch(Dispatchers.IO) {
            val resolving = async { repository.resolve(address) }
            val usage = onchain?.let { repository.usageOf(chatRoomId, chain, it.script) }
            val resolution = resolving.await()

            val lightning: LightningProblem? = when (resolution) {
                is Resolution.Resolved, Resolution.NotCheckedTor -> null
                Resolution.Unreachable -> LightningProblem.Unreachable
                Resolution.NotFound -> LightningProblem.NotFound
                is Resolution.Refused -> LightningProblem.Refused(resolution.reason)
                Resolution.NotAPayRequest -> LightningProblem.NotAPayRequest
                is Resolution.Malformed -> LightningProblem.Malformed
            }
            val onchainRefusal: OnchainProblem? = when (usage) {
                null, Usage.Unused -> null
                Usage.Used -> OnchainProblem.Used
                Usage.Unknown -> OnchainProblem.Unknown
                Usage.TheGroups -> OnchainProblem.TheGroups
            }
            val sendable: Sendable? = (resolution as? Resolution.Resolved)?.sendable

            val sent = if (lightning == null && onchainRefusal == null) {
                runCatching { repository.set(chatRoomId, chain, address, sendable, onchain?.address) }
                    .onFailure { logger.e("Failed to send a payout destination into $chatRoomId", it) }
                    .isSuccess
            } else {
                false
            }
            // Saved, so nothing stands between this profile and depositing but the group: ask it now,
            // rather than leaving the member to find the wallet's card. A refusal here is not a failed
            // save; the card still offers to start.
            val depositsSession = if (sent) {
                runCatching { depositRepository.openOwnIfReady(chatRoomId, chain) }
                    .onFailure { logger.e("Couldn't ask $chatRoomId to open this profile's deposits", it) }
                    .getOrNull()?.id
            } else null

            withContext(Dispatchers.Main) {
                lightningProblem = lightning
                onchainProblem = onchainRefusal
                isWorking = false
                when {
                    sent -> {
                        lightningFromProfile = false
                        onSaved(resolution == Resolution.NotCheckedTor, depositsSession)
                    }
                    lightning == null && onchainRefusal == null -> onFailure()
                }
            }
            if (sent) initiate()
        }
    }

    /** Signs and sends that this group should no longer pay this profile here. */
    fun remove(onRemoved: () -> Unit, onFailure: () -> Unit) {
        if (!canRemove()) return
        isWorking = true
        viewModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { repository.remove(chatRoomId, chain) }
                .onFailure { logger.e("Failed to remove the payout destination in $chatRoomId", it) }
                .isSuccess
            withContext(Dispatchers.Main) {
                isWorking = false
                if (removed) {
                    lightningField.setTextAndPlaceCursorAtEnd("")
                    onchainField.setTextAndPlaceCursorAtEnd("")
                    onRemoved()
                } else {
                    onFailure()
                }
            }
            if (removed) initiate()
        }
    }

    companion object {
        private const val TAG = "GroupPayoutDestinationViewModel"

        fun factory(
            chatRoomId: String,
            chain: Chain,
            initialUIState: GroupPayoutDestinationUIState = GroupPayoutDestinationUIState.Loading,
            repository: PayoutDestinationRepository,
            depositRepository: DepositRepository = DepositRepository.NO_OP,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupPayoutDestinationViewModel(chatRoomId, chain, initialUIState, repository, depositRepository) }
        }
    }
}
