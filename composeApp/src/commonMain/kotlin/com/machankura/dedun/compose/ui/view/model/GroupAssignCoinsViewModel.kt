package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.GroupAssignCoinsUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The coins nobody owns yet, each with a share for every depositor -- suggested pro rata to what
 * each has put in, and editable -- and asking the group to sign them. `docs/payouts-only.md`, Phase 3.
 */
class GroupAssignCoinsViewModel(
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupAssignCoinsUIState,
    private val chatRepository: ChatRepository,
    private val payoutRepository: PayoutRepository,
    private val wallets: GroupOnchainWallets?,
) : ViewModel() {
    var uiState: GroupAssignCoinsUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Each coin's share fields, by coin and then member: kept across a wallet update, so nothing typed is lost. */
    private val fields = mutableMapOf<String, Map<HexKey, TextFieldState>>()

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupAssignCoinsUIState.Error("Couldn't find the group") }
            val wallet = chatRepository.groupWallet(chatRoomId, chain)
                ?: return@launch run { uiState = GroupAssignCoinsUIState.Error("The group has no wallet on this network") }
            val names = localChatRoom.localParticipants.associate { participant ->
                participant.participant.participantPublicKey.lowercase() to
                    (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
            }
            val depositors = payoutRepository.depositors(chatRoomId, chain)
            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            val states = wallets?.walletFor(wallet.account, chatRoomId)?.state
                ?: return@launch run { uiState = GroupAssignCoinsUIState.Error("This device isn't watching the chain") }

            watching?.cancel()
            watching = viewModelScope.launch(Dispatchers.IO) {
                states.collect { state ->
                    val unowned = payoutRepository.balances(chatRoomId, chain, state).unowned
                    unowned.forEach { coin ->
                        val key = keyOf(coin)
                        if (key !in fields) {
                            val suggested = payoutRepository.suggestedShares(chatRoomId, chain, coin.utxo.amount.sat)
                            fields[key] = depositors.associateWith { member -> TextFieldState(suggested[member]?.toString() ?: "") }
                        }
                    }
                    uiState = GroupAssignCoinsUIState.Loaded(localChatRoom, unowned, depositors, names, canSign)
                }
            }
        }
    }

    /** [coin]'s share field for each depositor. */
    fun fieldsOf(coin: MemberBalances.UnownedCoin): Map<HexKey, TextFieldState> = fields[keyOf(coin)].orEmpty()

    /** What [coin]'s fields say, as shares: null while any field is not a whole number of sats. */
    fun sharesOf(coin: MemberBalances.UnownedCoin): Map<HexKey, Long>? = sharesFrom(fieldsOf(coin).mapValues { it.value.text })

    /**
     * Asks the group to sign whose [coin] is, in the shares its fields say: a signing session over
     * one assignment, which proposing counts as approving.
     */
    fun assign(coin: MemberBalances.UnownedCoin, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? GroupAssignCoinsUIState.Loaded ?: return
        val shares = sharesOf(coin) ?: return
        if (problemWith(coin.utxo.amount.sat, shares) != null || isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                val outPoint = coin.utxo.utxo.outPoint
                val key = DepositKey.Onchain(outPoint.txid.toString().lowercase(), outPoint.index)
                payoutRepository.assign(loaded.localChatRoom, chain, listOf(CoinAssignmentEvent.Coin(key, coin.utxo.amount.sat, shares)))
            }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    /** Why shares can't be proposed for a coin. */
    sealed interface Problem {
        data class LeftToAssign(val sats: Long) : Problem
        data class TooMany(val sats: Long) : Problem
        data object BelowDust : Problem
    }

    companion object {
        private fun keyOf(coin: MemberBalances.UnownedCoin): String =
            "${coin.utxo.utxo.outPoint.txid.toString().lowercase()}:${coin.utxo.utxo.outPoint.index}"

        /** Each field read as whole sats, an empty one as no share; null when any field is not a number of sats. */
        fun sharesFrom(texts: Map<HexKey, CharSequence>): Map<HexKey, Long>? =
            texts.mapNotNull { (member, text) ->
                when (val input = parseSats(text)) {
                    SatsInput.Empty -> null
                    is SatsInput.Sats -> input.amount.sat.takeIf { it > 0 }?.let { member to it }
                    else -> return null
                }
            }.toMap()

        /** Why [shares] of a coin of [amount] sats can't be proposed, or null when they can. */
        fun problemWith(amount: Long, shares: Map<HexKey, Long>): Problem? {
            val assigned = shares.values.sum()
            return when {
                assigned < amount -> Problem.LeftToAssign(amount - assigned)
                assigned > amount -> Problem.TooMany(assigned - amount)
                shares.values.any { it < CoinAssignmentEvent.MIN_SHARE_SAT } -> Problem.BelowDust
                else -> null
            }
        }

        fun factory(
            chatRoomId: String,
            chain: Chain,
            initialUIState: GroupAssignCoinsUIState = GroupAssignCoinsUIState.Loading,
            chatRepository: ChatRepository,
            payoutRepository: PayoutRepository,
            wallets: GroupOnchainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupAssignCoinsViewModel(chatRoomId, chain, initialUIState, chatRepository, payoutRepository, wallets) }
        }
    }
}
