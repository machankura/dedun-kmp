package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.database.model.GroupSignedEvent
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
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.FundingTarget
import com.machankura.dedun.compose.ui.view.state.GroupSendUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A spend from the group's wallet, built as the member types and proposed when
 * they ask.
 *
 * The preview the screen shows is [preview] over the same inputs the proposal is
 * built from, so the fee a member reads before pressing the button is the fee
 * the group is then asked to sign -- there is no second build.
 */
class GroupSendViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialGroupSendUIState: GroupSendUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    private val wallets: GroupOnchainWallets,
    /** The descendant whose guarded wallet this form spends from, or null for the room's own. */
    val guardedChatRoomId: String? = null,
    /** The descendant whose guarded wallet this form pays, or null for a payment to a typed address. */
    val fundChatRoomId: String? = null,
    /** An address to start the form with: a member's payout address. */
    initialAddress: String? = null,
): ViewModel() {
    private val logger = Logger.withTag(TAG)

    var groupSendUIState: GroupSendUIState by mutableStateOf(initialGroupSendUIState)
        private set

    val addressField = TextFieldState(initialAddress.orEmpty())
    val amountField = TextFieldState()
    val feerateField = TextFieldState()
    val sendEverything: MutableState<Boolean> = mutableStateOf(false)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                groupSendUIState = GroupSendUIState.Error("Couldn't find the group")
                return@launch
            }

            // Spending from one descendant's wallet into another's is a decision
            // of its own, not one this form makes -- docs/guarded-wallet-funding.md, D6.
            if (guardedChatRoomId != null && fundChatRoomId != null) {
                groupSendUIState = GroupSendUIState.Unavailable
                return@launch
            }

            // A descendant's guarded wallet, as this room was told it, with the
            // group as its guardian -- or the room's own wallet.
            val heldByRoom = if (guardedChatRoomId != null || fundChatRoomId != null) chatRepository.guardedWallets(chatRoomId) else emptyList()
            fun held(descendant: String?) = descendant?.let {
                heldByRoom.firstOrNull { wallet -> wallet.descendantChatRoomId.equals(it, ignoreCase = true) && wallet.chain == chain }
            }
            val guarded = held(guardedChatRoomId)
            // The wallet a send from the room's own pays, when it funds one.
            val funded = held(fundChatRoomId)
            val wallet = when {
                guardedChatRoomId == null -> chatRepository.groupWallet(chatRoomId, chain)
                guarded == null -> null
                else -> GroupWallet(GroupSignedEvent.fromEvent(guarded.statement, chatRoomId = guarded.descendantChatRoomId), guarded.account)
            }
            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            if (wallet == null || !canSign || (fundChatRoomId != null && funded == null)) {
                groupSendUIState = GroupSendUIState.Unavailable
                return@launch
            }

            val ready = GroupSendUIState.Ready(
                localChatRoom = localChatRoom,
                wallet = wallet,
                walletState = null,
                reserved = frostSigningRepository.reservedOutPoints(chatRoomId),
                reservedChange = frostSigningRepository.reservedChangeIndices(chatRoomId, wallet.account),
                spender = guarded?.spender() ?: GroupSpend.Spender.Owner,
                funding = funded?.let { FundingTarget(it, walletState = null, reserved = frostSigningRepository.reservedFundingIndices(chatRoomId, it.account)) },
            )
            groupSendUIState = ready

            wallets.suggestedFeeratePerVbyte(chain)?.let { suggested ->
                withContext(Dispatchers.Main) {
                    if (feerateField.text.isEmpty()) feerateField.setTextAndPlaceCursorAtEnd(suggested.toString())
                }
            }

            // The room's own wallet watches its depositors' branches too, so their coins can be spent.
            val own = if (guardedChatRoomId == null) wallets.walletFor(wallet.account, chatRoomId) else wallets.walletFor(wallet.account)
            follow(ready, own.state, funded?.let { wallets.walletFor(it.account).state })
        }
    }

    /**
     * Keeps [ready] current: the coins from [chainState], and which of them --
     * and which change addresses -- the room's open spends hold, re-read every
     * time a spend is proposed, fails or settles. Read once, a spend another
     * member proposed while this form was open would still look free, and
     * sending everything would ask the group to sign a second spend of coins
     * only one spend can have.
     *
     * One state from both, as the wallet screen does, so no frame shows new
     * coins against old reservations. Apart from [initiate] so that it can be
     * followed without an Electrum client.
     *
     * A send that funds a descendant's wallet follows [fundingState] too, and
     * the addresses the room's open fundings hold: if the address it was going
     * to pay is paid or taken while the form is open, the next one is shown.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun follow(
        ready: GroupSendUIState.Ready,
        chainState: Flow<GroupWalletState?>,
        fundingState: Flow<GroupWalletState?>? = null,
    ) {
        val account = ready.wallet.account
        val fundedAccount = ready.funding?.wallet?.account
        val reservations = frostSigningRepository.observeGroupTransactions(chatRoomId).mapLatest {
            Triple(
                frostSigningRepository.reservedOutPoints(chatRoomId),
                frostSigningRepository.reservedChangeIndices(chatRoomId, account),
                fundedAccount?.let { frostSigningRepository.reservedFundingIndices(chatRoomId, it) }.orEmpty(),
            )
        }
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            combine(chainState, fundingState ?: flowOf(null), reservations) { state, funded, (reserved, reservedChange, reservedFunding) ->
                ready.copy(
                    walletState = state,
                    reserved = reserved,
                    reservedChange = reservedChange,
                    funding = ready.funding?.copy(walletState = funded, reserved = reservedFunding),
                )
            }.collect { groupSendUIState = it }
        }
    }

    /** What the amount field says, read the one way everything that reads it does. */
    fun amount(): SatsInput = parseSats(amountField.text)

    /** The confirmed coins no open spend holds, summed, or null before Electrum has answered. */
    /**
     * How long a coin has to have waited before this group, as a guardian, can
     * spend it -- its own leaf's delay -- or null when the group spends its own
     * wallet. `docs/guardian-delay.md`.
     */
    fun guardianDelay(): Long? {
        val state = groupSendUIState as? GroupSendUIState.Ready ?: return null
        val guardian = state.spender as? GroupSpend.Spender.Guardian ?: return null
        return state.wallet.account.guardians.getOrNull(guardian.position)?.delay
    }

    fun spendable(): Satoshi? {
        val state = groupSendUIState as? GroupSendUIState.Ready ?: return null
        return state.walletState?.let { GroupSpendBuilder.spendableBalance(it, state.reserved, state.spender) }
    }

    /**
     * Whether [result] is about the amount typed, and so belongs on the amount
     * field rather than under the form. Never while sending everything: there
     * is no amount field then, and a send-all too small or over the cap is the
     * form's problem as a whole.
     */
    fun isAboutTheAmount(result: GroupSpendBuilder.Result?): Boolean =
        !sendEverything.value && (
            result is GroupSpendBuilder.Result.InvalidAmount ||
                result is GroupSpendBuilder.Result.OverCap ||
                result == GroupSpendBuilder.Result.NotEnough ||
                result == GroupSpendBuilder.Result.BelowDust
            )

    /**
     * The spend the form describes right now, or null while it is incomplete --
     * no wallet state yet, no feerate, no amount. An amount that is not one is
     * not incomplete, and says so before the rest of the form is filled in.
     */
    fun preview(): GroupSpendBuilder.Result? {
        val state = groupSendUIState as? GroupSendUIState.Ready ?: return null
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
        // A funding pays the address its target chose, never one typed: the
        // title says whose wallet it is, and a typed one would make that a claim.
        val destination = when (val funding = state.funding) {
            null -> GroupSpendBuilder.Destination.Address(addressField.text.toString().trim().takeIf { it.isNotEmpty() } ?: return null)
            else -> GroupSpendBuilder.Destination.Funding(funding.wallet.account, funding.address?.derivation ?: return null)
        }

        val built = if (amount == null) {
            GroupSpendBuilder.sendAll(walletState, destination, feerate, state.reserved, state.spender)
        } else {
            GroupSpendBuilder.send(walletState, destination, amount, feerate, state.reserved, state.reservedChange, state.spender)
        }
        return GroupSpendBuilder.capped(built, GroupWalletRollout.capFor(chain))
    }

    fun propose(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = groupSendUIState as? GroupSendUIState.Ready ?: return
        val built = preview() as? GroupSpendBuilder.Result.Built ?: return
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                when (val descendant = guardedChatRoomId.takeIf { state.spender is GroupSpend.Spender.Guardian }) {
                    null -> frostSigningRepository.proposeSpend(
                        localChatRoom = state.localChatRoom,
                        userPublicKey = activeUserPublicKey,
                        psbt = built.psbt,
                        chain = chain,
                    )
                    else -> frostSigningRepository.proposeGuardianSpend(
                        localChatRoom = state.localChatRoom,
                        userPublicKey = activeUserPublicKey,
                        psbt = built.psbt,
                        descendantChatRoomId = descendant,
                        chain = chain,
                    )
                }
            }.onFailure { logger.e("Failed to propose a spend from $chatRoomId", it) }.getOrNull()

            withContext(Dispatchers.Main) {
                if (session != null) onSuccess(session.id) else onFailure()
            }
            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "GroupSendViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            initialGroupSendUIState: GroupSendUIState = GroupSendUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            wallets: GroupOnchainWallets,
            guardedChatRoomId: String? = null,
            fundChatRoomId: String? = null,
            initialAddress: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupSendViewModel(
                    activeUserPublicKey, chatRoomId, chain, initialGroupSendUIState,
                    chatRepository, frostSigningRepository, wallets, guardedChatRoomId, fundChatRoomId, initialAddress,
                )
            }
        }
    }
}
