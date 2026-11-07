package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.ui.view.state.GroupSwapInUIState
import com.machankura.dedun.compose.managers.GroupLightningSweeps
import com.machankura.dedun.compose.wallet.PaidAddressWatch
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.SwapInWalletView
import com.machankura.dedun.compose.wallet.lightning.GroupSwapInState
import com.machankura.dedun.compose.wallet.lightning.GroupSwapInWallet
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.DefaultSwapInParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The member's swap-in addresses for the group's Lightning wallet and the coins on them, watched for as
 * long as the screen is open (docs/group-lightning-swap-in.md, Phase 6). Every device watches every
 * depositor's addresses, so nothing here asks the operator.
 */
class GroupSwapInViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
    private val swapIns: (suspend (chatRoomId: String, wallet: GroupLightningWalletManager.Wallet) -> GroupSwapInWallet?)?,
) : ViewModel() {
    var uiState: GroupSwapInUIState by mutableStateOf(GroupSwapInUIState.Loading)
        private set

    /** Whether this profile can ask the group for anything: a read-only identity cannot sign a proposal. */
    var canPropose: Boolean by mutableStateOf(false)
        private set

    var isRefunding: Boolean by mutableStateOf(false)
        private set

    /**
     * Asks the group to refund every refundable coin on this member's addresses, to address 0 of their own
     * deposit branch -- one the onchain wallet always watches, so the refund shows as it arrives (D9).
     */
    fun refund(onProposed: () -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? GroupSwapInUIState.Loaded ?: return
        val coins = loaded.coins.filter { it.status == SwapInCoin.Status.Refundable }
            .mapNotNull { coin -> coin.index?.let { GroupLightningSweeps.RefundableCoin(coin.outPoint, coin.amount, it.toLong()) } }
        if (coins.isEmpty() || isRefunding) return
        isRefunding = true
        viewModelScope.launch(Dispatchers.IO) {
            val proposed = runCatching {
                val room = chatRepository.getChatRoomByIdentifier(chatRoomId) ?: error("no room")
                val wallet = lightningRepository.lightningWallet(chatRoomId, chain) ?: error("no wallet")
                lightningRepository.proposeSwapInRefund(room, activeUserPublicKey, wallet, activeUserPublicKey, coins, toIndex = 0)
            }
            isRefunding = false
            withContext(Dispatchers.Main) { if (proposed.isSuccess) onProposed() else onFailure() }
        }
    }

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
            val swapIn = wallet?.let { swapIns?.invoke(chatRoomId, it) }
            if (swapIn == null) {
                uiState = GroupSwapInUIState.Unavailable
                return@launch
            }
            canPropose = chatRepository.canSign(chatRoomId)
            val names = chatRepository.getChatRoomByIdentifier(chatRoomId)?.localParticipants.orEmpty().associate { p ->
                p.participant.participantPublicKey.lowercase() to (p.profile?.humanReadableNameOrPubkey() ?: p.participant.participantPublicKey.take(8))
            }
            val watch = PaidAddressWatch(SwapInAddress::address)
            swapIn.state.collect { state ->
                val next = stateOf(state, activeUserPublicKey, names)
                uiState = if (next is GroupSwapInUIState.Loaded) {
                    next.copy(justPaid = watch.seen(next.current.address, next.used))
                } else {
                    next
                }
            }
        }
    }

    companion object {
        /** The protocol's windows, which every swap-in address of the group has. */
        private val PARAMS = SwapInParams(DefaultSwapInParams.MinConfirmations, DefaultSwapInParams.MaxConfirmations, DefaultSwapInParams.RefundDelay)

        /**
         * What [member] sees of [state]. A member who is no depositor has no addresses; one whose addresses
         * Electrum has not answered for is still loading, for the reason [SwapInWalletView.addresses] is.
         *
         * No coin reads as *moving into Lightning*: which coins a splice holds is the operator's node's to
         * know, and a coin it spliced in is spent, and gone from the list.
         */
        fun stateOf(state: GroupSwapInState, member: HexKey, names: Map<HexKey, String> = emptyMap()): GroupSwapInUIState {
            val mine = state.depositors[member.lowercase()] ?: return GroupSwapInUIState.NotDepositor
            val addresses = SwapInWalletView.addresses(mine.state, legacy = "") ?: return GroupSwapInUIState.Loading
            val tip = state.tipHeight ?: return GroupSwapInUIState.Loading
            val current = addresses.firstOrNull { it.status == SwapInAddress.Status.CURRENT } ?: return GroupSwapInUIState.Loading
            val others = state.depositors.values.filter { it.member != mine.member }.sortedBy { names[it.member] ?: it.member }.mapNotNull { other ->
                SwapInWalletView.addresses(other.state, legacy = "")?.filter { it.status == SwapInAddress.Status.USED }?.takeIf { it.isNotEmpty() }
                    ?.let { (names[other.member] ?: other.member.take(8)) to it }
            }
            return GroupSwapInUIState.Loaded(
                current = current,
                coins = SwapInWalletView.coins(mine.state, tip, PARAMS, reserved = emptySet()),
                used = addresses.filter { it.status == SwapInAddress.Status.USED },
                others = others,
                minConfirmations = PARAMS.minConfirmations,
                refundDelay = PARAMS.refundDelay,
            )
        }

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
            swapIns: (suspend (chatRoomId: String, wallet: GroupLightningWalletManager.Wallet) -> GroupSwapInWallet?)?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupSwapInViewModel(activeUserPublicKey, chatRoomId, chain, chatRepository, lightningRepository, swapIns) }
        }
    }
}
