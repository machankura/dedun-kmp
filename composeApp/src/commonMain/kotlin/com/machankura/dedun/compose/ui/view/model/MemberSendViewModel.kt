package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.MemberSendUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * A spend from the member's own guarded wallet, built as they type and signed
 * alone when they ask.
 *
 * The group's send form, read the same way -- the preview is the spend, so the
 * fee shown is the fee paid -- with one difference that matters: there is no
 * quorum after this. Pressing the button signs and broadcasts.
 */
class MemberSendViewModel(
    private val ownerPublicKey: HexKey,
    val walletId: String,
    val chain: Chain,
    initialUIState: MemberSendUIState,
    private val memberWalletRepository: MemberWalletRepository,
    private val wallets: GroupOnchainWallets,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: MemberSendUIState by mutableStateOf(initialUIState)
        private set

    val addressField = TextFieldState()
    val amountField = TextFieldState()
    val feerateField = TextFieldState()
    val sendEverything: MutableState<Boolean> = mutableStateOf(false)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = memberWalletRepository.walletOf(ownerPublicKey, walletId)
            if (wallet == null) {
                uiState = MemberSendUIState.Error("Couldn't find this wallet")
                return@launch
            }
            if (wallet.state != MemberWalletManager.WalletState.OPEN) {
                uiState = MemberSendUIState.Unavailable
                return@launch
            }

            val ready = MemberSendUIState.Ready(wallet = wallet, walletState = null)
            uiState = ready

            wallets.suggestedFeeratePerVbyte(chain)?.let { suggested ->
                withContext(Dispatchers.Main) {
                    if (feerateField.text.isEmpty()) feerateField.setTextAndPlaceCursorAtEnd(suggested.toString())
                }
            }

            watching?.cancel()
            watching = viewModelScope.launch(Dispatchers.IO) {
                combine(wallets.walletFor(wallet.account).state, memberWalletRepository.observeSpends(walletId)) { state, spends ->
                    val (reserved, reservedChange) = reservationsOf(spends, ready)
                    ready.copy(walletState = state, reserved = reserved, reservedChange = reservedChange)
                }.collect { uiState = it }
            }
        }
    }

    /**
     * What the member's own spends still on their way hold: their coins, and the
     * change they pay back to. Electrum stops listing a coin once a spend of it
     * is in the mempool, so this matters for the moment between signing and the
     * server hearing of it -- a second send then would spend the same coin.
     */
    private fun reservationsOf(spends: List<MemberWalletSpend>, ready: MemberSendUIState.Ready): Pair<Set<OutPoint>, Set<Long>> {
        val open = spends.filter { it.seenAt == null && it.replacedByTxid == null }
            .mapNotNull { GroupSpend.decode(it.psbt) }
            .mapNotNull { (GroupSpend.check(it, ready.wallet.account) as? GroupSpend.Result.Ok)?.checked }
        return open.flatMap { spend -> spend.inputs.map { it.outPoint } }.toSet() to
            open.flatMap { spend -> spend.outputs.mapNotNull { it.change?.index } }.toSet()
    }

    fun amount(): SatsInput = parseSats(amountField.text)

    fun spendable(): Satoshi? {
        val state = uiState as? MemberSendUIState.Ready ?: return null
        return state.walletState?.let { GroupSpendBuilder.spendableBalance(it, state.reserved, GroupSpend.Spender.Owner) }
    }

    fun isAboutTheAmount(result: GroupSpendBuilder.Result?): Boolean =
        !sendEverything.value && (
            result is GroupSpendBuilder.Result.InvalidAmount ||
                result is GroupSpendBuilder.Result.OverCap ||
                result == GroupSpendBuilder.Result.NotEnough ||
                result == GroupSpendBuilder.Result.BelowDust
            )

    /** The spend the form describes, or null while it is incomplete -- the same build the group's form makes. */
    fun preview(): GroupSpendBuilder.Result? {
        val state = uiState as? MemberSendUIState.Ready ?: return null
        val walletState = state.walletState ?: return null

        val amount = if (sendEverything.value) {
            null
        } else {
            when (val input = amount()) {
                SatsInput.Empty -> return null
                is SatsInput.Sats -> input.amount
                else -> return GroupSpendBuilder.Result.InvalidAmount(input)
            }
        }
        val feerate = feerateField.text.toString().trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        val address = addressField.text.toString().trim().takeIf { it.isNotEmpty() } ?: return null

        val built = if (amount == null) {
            GroupSpendBuilder.sendAll(walletState, address, feerate, state.reserved)
        } else {
            GroupSpendBuilder.send(walletState, address, amount, feerate, state.reserved, state.reservedChange)
        }
        return GroupSpendBuilder.capped(built, GroupWalletRollout.memberCapFor(chain))
    }

    /** Signs what the form describes and hands it to Electrum. [onSent] when it went, [onFailure] when it did not. */
    fun send(onSent: () -> Unit, onFailure: () -> Unit) {
        val built = preview() as? GroupSpendBuilder.Result.Built ?: return
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val sent = runCatching {
                val signed = memberWalletRepository.signSpend(walletId, built.psbt)
                wallets.broadcast(signed, chain)
                memberWalletRepository.recordChain(signed.txid.toString(), confirmations = null, broadcastAt = Clock.System.now())
            }.onFailure { logger.e("Couldn't send from wallet $walletId", it) }.isSuccess

            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (sent) onSent() else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "MemberSendViewModel"

        fun factory(
            ownerPublicKey: HexKey,
            walletId: String,
            chain: Chain,
            initialUIState: MemberSendUIState = MemberSendUIState.Loading,
            memberWalletRepository: MemberWalletRepository,
            wallets: GroupOnchainWallets,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemberSendViewModel(ownerPublicKey, walletId, chain, initialUIState, memberWalletRepository, wallets) }
        }
    }
}
