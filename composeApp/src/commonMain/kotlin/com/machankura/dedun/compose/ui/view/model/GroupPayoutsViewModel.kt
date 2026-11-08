package com.machankura.dedun.compose.ui.view.model

import kotlinx.coroutines.flow.first
import fr.acinq.lightning.MilliSatoshi
import com.machankura.dedun.compose.wallet.lightning.LightningBalance
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.managers.GroupLightningAuthorisations
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.ui.view.state.GroupPayoutsUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.PayoutBuilder
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every member's destination and balance, and paying the ones chosen everything they own:
 * `docs/payouts-only.md`, Phase 4.
 */
class GroupPayoutsViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupPayoutsUIState,
    private val chatRepository: ChatRepository,
    private val frostSigningRepository: FrostSigningRepository,
    private val payoutRepository: PayoutRepository,
    private val payoutDestinationRepository: PayoutDestinationRepository,
    private val wallets: GroupOnchainWallets?,
    private val lightningRepository: GroupLightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
    private val wavelengthRepository: GroupWavelengthRepository = GroupWavelengthRepository.NO_OP,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupPayoutsUIState by mutableStateOf(initialUIState)
        private set

    /** The members chosen to be paid out onchain. */
    var selected: Set<HexKey> by mutableStateOf(emptySet())
        private set

    val feerateField = TextFieldState()

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Whether each payee's address has been paid before, as the proposer checks it (D7): absent while checking. */
    val usage = mutableStateMapOf<HexKey, UnusedAddressCheck.Usage>()

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupPayoutsUIState.Error("Couldn't find the group") }
            val wallet = chatRepository.groupWallet(chatRoomId, chain)
            val names = localChatRoom.localParticipants.associate { participant ->
                participant.participant.participantPublicKey.lowercase() to
                    (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
            }
            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            // A Lightning wallet pays only through its operator's node: without one, nothing is paid.
            val lightningWallet = lightningRepository.lightningWallet(chatRoomId, chain)
                ?.takeIf { lightningRepository.operator(chatRoomId, chain, it.statement.wallet)?.publicKey != null }
            val lightningSpendable = lightningWallet?.let { wallet ->
                lightningRepository.observeReport(chatRoomId, chain, wallet.statement.wallet).first()
                    ?.snapshot?.channels?.filter { it.state.equals("Normal", ignoreCase = true) }?.sumOf { it.oursMsat }
            }
            val wavelengthPaysLightning = GroupWavelengthRollout.paysOverLightning(chain)
            val wavelengthPaysOnchain = GroupWavelengthRollout.paysOnchain(chain)
            val wavelengthWallet = wavelengthRepository.wallets(chatRoomId, chain).lastOrNull()
                ?.takeIf { (wavelengthPaysLightning || wavelengthPaysOnchain) && wavelengthRepository.operator(chatRoomId, chain, it.statement.wallet)?.publicKey != null }
            val wavelengthInWallet = wavelengthWallet?.let { wallet ->
                wavelengthRepository.observeCoins(chatRoomId, chain, wallet.statement.wallet).first().filter { it.settled && !it.spent }.sumOf { it.amountSat }
            } ?: 0
            val loaded = GroupPayoutsUIState.Loaded(
                localChatRoom = localChatRoom,
                balances = payoutRepository.balances(chatRoomId, chain, null),
                walletState = null,
                reserved = frostSigningRepository.reservedOutPoints(chatRoomId),
                context = payoutRepository.payoutContext(chatRoomId, chain),
                names = names,
                canSign = canSign && wallet != null,
                self = activeUserPublicKey.lowercase(),
                lightningWallet = lightningWallet,
                lightningSpendableMsat = lightningSpendable,
                wavelengthWallet = wavelengthWallet,
                wavelengthPaysLightning = wavelengthPaysLightning,
                wavelengthPaysOnchain = wavelengthPaysOnchain,
                wavelengthInWalletSat = wavelengthInWallet,
            )
            uiState = loaded

            wallets?.suggestedFeeratePerVbyte(chain)?.let { suggested ->
                withContext(Dispatchers.Main) {
                    if (feerateField.text.isEmpty()) feerateField.setTextAndPlaceCursorAtEnd(suggested.toString())
                }
            }
            val states = wallet?.let { wallets?.walletFor(it.account, chatRoomId)?.state } ?: return@launch
            follow(loaded, states)
        }
    }

    /**
     * Keeps [loaded] current: the coins from [chainState], whose they are, and which of them the
     * room's open spends hold, re-read every time a spend is proposed, fails or settles.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun follow(loaded: GroupPayoutsUIState.Loaded, chainState: Flow<GroupWalletState?>) {
        val reservations = frostSigningRepository.observeGroupTransactions(chatRoomId).mapLatest { frostSigningRepository.reservedOutPoints(chatRoomId) }
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            combine(chainState, reservations) { state, reserved ->
                loaded.copy(
                    balances = payoutRepository.balances(chatRoomId, chain, state),
                    walletState = state,
                    reserved = reserved,
                    context = payoutRepository.payoutContext(chatRoomId, chain),
                )
            }.collect { uiState = it }
        }
    }

    fun toggle(member: HexKey) {
        selected = if (member in selected) selected - member else selected + member
    }

    /** The payout the screen describes now, or null while nobody is chosen, the coins are not known, or there is no fee rate. */
    fun preview(): PayoutBuilder.Result? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        val walletState = state.walletState ?: return null
        if (selected.isEmpty()) return null
        val feerate = feerateField.text.toString().trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        return PayoutBuilder.build(walletState, selected, state.context, feerate, state.reserved)
    }

    /** Checks the payout address of each of [members] that has not been checked yet (D7). */
    fun checkUsage(members: Collection<HexKey>) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        members.filter { it !in usage }.forEach { member ->
            val script = state.context.scripts[member] ?: return@forEach
            viewModelScope.launch(Dispatchers.IO) {
                val answer = payoutDestinationRepository.usageOf(chatRoomId, chain, script)
                withContext(Dispatchers.Main) { usage[member] = answer }
            }
        }
    }

    /** Why the payout built can't be proposed yet, by the usage of its payees' addresses, or null when it can (D7). */
    fun usageProblem(built: PayoutBuilder.Result.Built): UsageProblem? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        built.payout.members.forEach { payee ->
            val former = state.balances.members[payee.member]?.isFormerMember == true
            when (usage[payee.member]) {
                null -> return UsageProblem.Checking(payee.member)
                UnusedAddressCheck.Usage.Unknown -> return UsageProblem.CouldNotCheck(payee.member)
                UnusedAddressCheck.Usage.Used -> if (!former) return UsageProblem.PaidBefore(payee.member)
                else -> Unit
            }
        }
        return null
    }

    /** A former member whose address has been paid before: paid anyway, after a warning, since they can't give another. */
    fun reusedByFormer(built: PayoutBuilder.Result.Built): List<HexKey> {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return emptyList()
        return built.payout.members.map { it.member }.filter { usage[it] == UnusedAddressCheck.Usage.Used && state.balances.members[it]?.isFormerMember == true }
    }

    sealed interface UsageProblem {
        data class Checking(val member: HexKey) : UsageProblem
        data class CouldNotCheck(val member: HexKey) : UsageProblem
        data class PaidBefore(val member: HexKey) : UsageProblem
    }

    fun propose(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        val built = preview() as? PayoutBuilder.Result.Built ?: return
        if (usageProblem(built) != null || isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSpend(localChatRoom = state.localChatRoom, userPublicKey = activeUserPublicKey, psbt = built.psbt, chain = chain)
            }.onFailure { logger.e("Failed to propose a payout from $chatRoomId", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) {
                    selected = emptySet()
                    onSuccess(session.id)
                } else onFailure()
            }
        }
    }

    /**
     * What a Lightning payout of [balance] would be: the most its whole balance pays after a fee
     * limit, held to what the channels hold -- the member who withdraws last bears what the channel
     * can't cover (D8) -- as (amount, fee limit) in msat, or null when nothing can be paid.
     */
    fun lightningPayable(balance: MemberBalances.MemberBalance): Pair<Long, Long>? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        val available = state.lightningSpendableMsat?.let { minOf(it, balance.lightning.availableMsat) } ?: balance.lightning.availableMsat
        return LightningBalance.payable(available)
    }

    /**
     * What a Lightning payout from the Wavelength wallet would be, as (amount, fee limit) in msat, or null when nothing can be paid --
     * including while another payout of theirs, on either rail, is approved and waiting to be paid, which every device refuses (W9).
     *
     * Bounded by the most the wallet can spend as well as by the balance, so what's offered is a payment the operator will take rather
     * than the whole balance and a refusal (`docs/group-wavelength-spendable-ceiling.md`, B5). The rest of the balance stays in the
     * wallet and on their row.
     */
    fun wavelengthPayable(balance: MemberBalances.MemberBalance): Pair<Long, Long>? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        if (balance.wavelength.openSat > 0) return null
        return LightningBalance.payable(GroupWavelengthRollout.spendableSat(state.wavelengthInWalletSat, balance.wavelength.availableSat) * 1000)
    }

    /**
     * How much of [balance] a Wavelength payout would leave behind in the wallet, because the operator won't let it go: 0 where the
     * whole balance is being paid. What the row says before anyone proposes, so that being paid less than their row shows is explained
     * rather than noticed (B7).
     */
    fun wavelengthHeldBackSat(balance: MemberBalances.MemberBalance): Long {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return 0
        val available = balance.wavelength.availableSat
        return available - GroupWavelengthRollout.spendableSat(state.wavelengthInWalletSat, available)
    }

    /**
     * Asks the group to pay [member] from their Wavelength balance, whole: as [payOutLightning] does, their address is resolved again on
     * this device and asked for an invoice of the amount, and a device signs only if it fits what it can see of that balance.
     */
    fun payOutWavelength(member: HexKey, onSuccess: (sessionId: String) -> Unit, onProblem: (LightningProblem) -> Unit) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        val wallet = state.wavelengthWallet ?: return
        val balance = state.balances.members[member] ?: return
        val destination = balance.destination ?: return
        val (amountMsat, maxFeeMsat) = wavelengthPayable(balance) ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val problem: LightningProblem? = run {
                val invoice = payoutDestinationRepository.invoiceFor(destination, MilliSatoshi(amountMsat), wallet.statement.chain)
                val ready = invoice as? LightningAddressResolver.PayoutInvoice.Ready ?: return@run LightningProblem.NoInvoice(invoice)
                val payout = GroupWavelengthPaymentEvent.Payout(member, destination.event.id)
                val session = runCatching { wavelengthRepository.proposePayout(state.localChatRoom, activeUserPublicKey, wallet, ready.bolt11, (maxFeeMsat + 999) / 1000, payout, ready.metadata) }
                    .onFailure { logger.e("Failed to propose a Wavelength payout from $chatRoomId", it) }.getOrNull()
                    ?: return@run LightningProblem.CouldNotPropose
                withContext(Dispatchers.Main) { onSuccess(session.id) }
                null
            }
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                problem?.let(onProblem)
            }
        }
    }

    /**
     * What an onchain payout from the Wavelength wallet would be for [balance], or why there is none: the whole balance less the most a
     * round may cost, arriving exactly at the address the member gave (`docs/group-wavelength-payouts.md`, W7 and W8). Null where the
     * member has nothing in that wallet, or onchain payouts are off.
     */
    fun wavelengthOnchainOffer(balance: MemberBalances.MemberBalance): WavelengthOnchainOffer? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        val wallet = state.wavelengthWallet?.takeIf { state.wavelengthPaysOnchain } ?: return null
        val wavelength = balance.wavelength
        if (wavelength.availableSat <= 0 && wavelength.openSat <= 0) return null
        if (wavelength.openSat > 0) return WavelengthOnchainOffer.Waiting
        val address = balance.destination?.onchainAddress ?: return WavelengthOnchainOffer.NoAddress
        val maxFeeSat = wallet.statement.policy.maxRoundFeeSat
        val amountSat = GroupWavelengthRollout.spendableSat(state.wavelengthInWalletSat, wavelength.availableSat) - maxFeeSat
        if (amountSat < GroupWavelengthRollout.MIN_ONCHAIN_PAYOUT_SAT) {
            val shortSat = GroupWavelengthRollout.MIN_ONCHAIN_PAYOUT_SAT - amountSat
            // Whose shortfall it is decides what can be done about it: a deposit to this member raises their balance, where a wallet
            // too small to leave the operator's change and pay a round needs any deposit at all (B6).
            return if (wavelength.availableSat - maxFeeSat >= GroupWavelengthRollout.MIN_ONCHAIN_PAYOUT_SAT) {
                WavelengthOnchainOffer.WalletTooSmall
            } else {
                WavelengthOnchainOffer.TooLittle(shortSat)
            }
        }
        return WavelengthOnchainOffer.Offer(amountSat, maxFeeSat, address)
    }

    sealed interface WavelengthOnchainOffer {
        data class Offer(val amountSat: Long, val maxFeeSat: Long, val address: String) : WavelengthOnchainOffer
        data object NoAddress : WavelengthOnchainOffer
        /** Another payout of theirs, on either rail, is approved and waiting to be paid (W9). One still being signed shows only once approved. */
        data object Waiting : WavelengthOnchainOffer
        /** The member's own balance can't carry a round's fee and still arrive worth having. [shortSat] of theirs would. */
        data class TooLittle(val shortSat: Long) : WavelengthOnchainOffer
        /**
         * The balance would do, but the wallet can't both keep the operator's least change and pay for a round. Nobody's balance is
         * the problem and any deposit helps, which is why this isn't [TooLittle] with a figure on it (B6).
         */
        data object WalletTooSmall : WavelengthOnchainOffer
    }

    /** Why [member]'s onchain address can't be paid yet by what was found of its use, or null when it can (`docs/payouts-only.md`, D7). */
    fun usageProblemOf(member: HexKey): UsageProblem? {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return null
        val former = state.balances.members[member]?.isFormerMember == true
        return when (usage[member]) {
            null -> UsageProblem.Checking(member)
            UnusedAddressCheck.Usage.Unknown -> UsageProblem.CouldNotCheck(member)
            UnusedAddressCheck.Usage.Used -> if (former) null else UsageProblem.PaidBefore(member)
            else -> null
        }
    }

    /** Asks the group to pay [member] their whole Wavelength balance onchain, as [wavelengthOnchainOffer] says it would. */
    fun payOutWavelengthOnchain(member: HexKey, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        val wallet = state.wavelengthWallet ?: return
        val balance = state.balances.members[member] ?: return
        val destination = balance.destination ?: return
        val offer = wavelengthOnchainOffer(balance) as? WavelengthOnchainOffer.Offer ?: return
        if (usageProblemOf(member) != null || isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { wavelengthRepository.proposeOnchainPayout(state.localChatRoom, activeUserPublicKey, wallet, destination, offer.amountSat, offer.maxFeeSat) }
                .onFailure { logger.e("Failed to propose an onchain Wavelength payout from $chatRoomId", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    /** Why a Lightning payout went no further: the address's answer, or the authorisation's. */
    sealed interface LightningProblem {
        data class NoInvoice(val answer: LightningAddressResolver.PayoutInvoice) : LightningProblem
        data class NotAuthorisable(val problem: Throwable) : LightningProblem
        data object CouldNotPropose : LightningProblem
    }

    /**
     * Asks the group to pay [member] their whole Lightning balance (D8): their address is resolved
     * again on this device, asked for an invoice of the amount, and the authorisation carries whose
     * destination it is and the metadata the invoice commits to.
     */
    fun payOutLightning(member: HexKey, onSuccess: (sessionId: String) -> Unit, onProblem: (LightningProblem) -> Unit) {
        val state = uiState as? GroupPayoutsUIState.Loaded ?: return
        val wallet = state.lightningWallet ?: return
        val balance = state.balances.members[member] ?: return
        val destination = balance.destination ?: return
        val (amountMsat, maxFeeMsat) = lightningPayable(balance) ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val problem: LightningProblem? = run {
                val invoice = payoutDestinationRepository.invoiceFor(destination, MilliSatoshi(amountMsat), wallet.statement.chain)
                val ready = invoice as? LightningAddressResolver.PayoutInvoice.Ready ?: return@run LightningProblem.NoInvoice(invoice)
                val pay = GroupLightningAuthorisations.prepare(wallet, ready.bolt11, null, maxFeeMsat)
                    .getOrElse { return@run LightningProblem.NotAuthorisable(it) }
                    .copy(payout = GroupLightningAuthorisationEvent.Payout(member, destination.event.id), lnurl = ready.metadata)
                val session = runCatching { lightningRepository.proposePayment(state.localChatRoom, activeUserPublicKey, pay) }
                    .onFailure { logger.e("Failed to propose a Lightning payout from $chatRoomId", it) }.getOrNull()
                    ?: return@run LightningProblem.CouldNotPropose
                withContext(Dispatchers.Main) { onSuccess(session.id) }
                null
            }
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                problem?.let(onProblem)
            }
        }
    }

    companion object {
        private const val TAG = "GroupPayoutsViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            initialUIState: GroupPayoutsUIState = GroupPayoutsUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            payoutRepository: PayoutRepository,
            payoutDestinationRepository: PayoutDestinationRepository,
            wallets: GroupOnchainWallets?,
            lightningRepository: GroupLightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
            wavelengthRepository: GroupWavelengthRepository = GroupWavelengthRepository.NO_OP,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupPayoutsViewModel(
                    activeUserPublicKey, chatRoomId, chain, initialUIState,
                    chatRepository, frostSigningRepository, payoutRepository, payoutDestinationRepository, wallets, lightningRepository, wavelengthRepository,
                )
            }
        }
    }
}
