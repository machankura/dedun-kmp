package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.managers.GroupLightningAuthorisations
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.GroupLightningLiquidityUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.lightning.LiquidityPrice
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A liquidity purchase, as a proposal (`docs/group-lightning-liquidity.md`, Phase 7): one of Phoenix's
 * amounts that the LSP sells and the channel has room for, priced on this device from what the
 * operator reported, and a fee cap with room above the estimate. The group signs it like any decision,
 * and the operator's node buys once it has.
 */
class GroupLightningLiquidityViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupLightningLiquidityUIState,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupLightningLiquidityUIState by mutableStateOf(initialUIState)
        private set

    /** Which of the offered amounts is chosen. */
    var choice by mutableIntStateOf(0)

    val maxFeeField = TextFieldState()
    /** The review sheet is open, and whether its acknowledgement is ticked. */
    var reviewing by mutableStateOf(false)
    var acknowledged by mutableStateOf(false)
    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupLightningLiquidityUIState.Error("Couldn't find the group") }
            val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
                ?: return@launch run { uiState = GroupLightningLiquidityUIState.Unavailable(LiquidityPrice.Availability.NoOperator) }
            val hasOperator = lightningRepository.operator(chatRoomId, chain, wallet.statement.wallet)?.publicKey != null
            lightningRepository.observeReport(chatRoomId, chain, wallet.statement.wallet).collect { report ->
                uiState = when (val availability = LiquidityPrice.availability(report?.snapshot, hasOperator, GroupLightningRollout.channelCapFor(chain)?.sat)) {
                    is LiquidityPrice.Availability.Available -> {
                        choice = choice.coerceIn(0, availability.offered.lastIndex)
                        GroupLightningLiquidityUIState.Form(localChatRoom, wallet, report!!.snapshot, availability)
                    }
                    else -> GroupLightningLiquidityUIState.Unavailable(availability)
                }
            }
        }
    }

    /** The amount chosen, in sats. */
    fun amountSat(form: GroupLightningLiquidityUIState.Form): Long = form.available.offered[choice.coerceIn(0, form.available.offered.lastIndex)]

    fun price(form: GroupLightningLiquidityUIState.Form): LiquidityPrice.Price? =
        LiquidityPrice.price(form.available.rates, amountSat(form), form.available.feeratePerByte)

    fun maxFee(): SatsInput = parseSats(maxFeeField.text)

    /** The cap the proposal will carry: the field's, or the estimate with room above it when the field is empty. */
    fun maxFeeSat(form: GroupLightningLiquidityUIState.Form): Long? = when (val fee = maxFee()) {
        SatsInput.Empty -> price(form)?.let(LiquidityPrice::defaultCap)
        is SatsInput.Sats -> fee.amount.sat
        else -> null
    }

    fun propose(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val form = uiState as? GroupLightningLiquidityUIState.Form ?: return
        val cap = maxFeeSat(form) ?: return onFailure()
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(form.wallet, form.available.channel.channelId, form.available.channel.capacitySat, amountSat(form), cap).getOrNull() ?: return onFailure()
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { lightningRepository.proposeLiquidity(form.localChatRoom, activeUserPublicKey, buy) }
                .onFailure { logger.e("Liquidity proposal in $chatRoomId failed", it) }.getOrNull()
            isActionPending.value = false
            withContext(Dispatchers.Main) {
                reviewing = false
                if (session != null) onSession(session.id) else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "GroupLightningLiquidityViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupLightningLiquidityViewModel(activeUserPublicKey, chatRoomId, chain, GroupLightningLiquidityUIState.Loading, chatRepository, lightningRepository)
            }
        }
    }
}
