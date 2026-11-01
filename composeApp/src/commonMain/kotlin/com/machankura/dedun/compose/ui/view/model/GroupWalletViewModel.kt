package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.wallet.PayoutBuilder
import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.subgroup.isDecided
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.ui.view.state.SpendRow
import com.machankura.dedun.compose.ui.view.state.SpendStatus
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupTransactionWatcher
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.ui.view.state.GuardedWalletRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChangedBy
import com.machankura.dedun.compose.managers.GroupWalletDerivation
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
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.view.state.GroupWalletUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The group's wallet, read once for what the group has signed and then watched
 * for what the chain says.
 *
 * The statement is a read, like every other group-signed thing's screen; the
 * coins are a watch, because a payment arriving while the screen is open is the
 * one moment somebody is looking at it.
 *
 * [wallets] watch on the identity's Electrum client: its node's, or for an nsec
 * or npub profile one of its own (docs/standalone-electrum.md).
 */
class GroupWalletViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialGroupWalletUIState: GroupWalletUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    private val wallets: GroupOnchainWallets,
    private val watcher: GroupTransactionWatcher? = null,
    /** Who deposits with the group -- `docs/member-deposits.md`. The no-op hands nobody an address. */
    private val depositRepository: DepositRepository = DepositRepository.NO_OP,
    /** Whose each coin is -- `docs/payouts-only.md`. The no-op says every coin is somebody's. */
    private val payoutRepository: PayoutRepository = PayoutRepository.NO_OP,
): ViewModel() {
    private val logger = Logger.withTag(TAG)

    var groupWalletUIState: GroupWalletUIState by mutableStateOf(initialGroupWalletUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                groupWalletUIState = GroupWalletUIState.Error("Couldn't find the group")
                return@launch
            }

            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            val wallet = chatRepository.groupWallet(chatRoomId, chain)
            val guarded = chatRepository.guardedWallets(chatRoomId).filter { it.chain == chain }
            // The catch-up first, so what the screen offers to share is only what
            // could not be sent. docs/guarded-wallet-forwarding.md, Phase 4.
            chatRepository.forwardGuardedWallets(chatRoomId)
            val shareable = chatRepository.shareableGuardedWallets(chatRoomId, chain)
            val guardedRows = guarded.map { GuardedWalletRow(it, walletState = null) }

            watching?.cancel()

            if (wallet == null) {
                // What the room's parent decided at its birth: a decided policy
                // leaves one way in, and an undecided or absent one both, as
                // before policies. docs/subgroup-wallet-policy.md.
                val policy = chatRepository.walletPolicyOf(chatRoomId)
                val offersGuarded = !policy.isDecided(SubgroupWalletPolicy.UNGUARDED) &&
                    GroupWalletRollout.offersGuardiansOn(chain)
                val notOpened = GroupWalletUIState.NotOpened(
                    localChatRoom = localChatRoom,
                    account = chatRepository.groupWalletAccount(chatRoomId, chain),
                    canSign = canSign,
                    guarded = if (offersGuarded) {
                        chatRepository.groupWalletGuardedOpening(chatRoomId, chain)
                    } else null,
                    policy = policy,
                    offersPlain = !policy.isDecided(SubgroupWalletPolicy.GUARDED),
                    guardedWallets = guardedRows,
                    shareable = shareable,
                ).let { it.copy(guardianNames = namesOf(it.guarded?.account?.guardians.orEmpty())) }
                groupWalletUIState = notOpened
                if (guarded.isNotEmpty()) {
                    watching = viewModelScope.launch(Dispatchers.IO) {
                        guardedStates(guarded).collect { rows -> groupWalletUIState = notOpened.copy(guardedWallets = rows) }
                    }
                }
                return@launch
            }

            val loaded = GroupWalletUIState.Loaded(
                localChatRoom = localChatRoom,
                wallet = wallet,
                walletState = null,
                canSign = canSign,
                guardedWallets = guardedRows,
                shareable = shareable,
                guardianNames = namesOf(wallet.account.guardians),
                deposits = depositRepository.standing(chatRoomId, chain, canPropose = canSign),
                memberNames = localChatRoom.localParticipants.associate { participant ->
                    participant.participant.participantPublicKey.lowercase() to
                        (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
                },
            )
            groupWalletUIState = loaded

            // The chain, the room's spends and the wallets it guards, as one
            // state: any of them changing redraws the screen, and none may
            // overwrite another.
            val chainState = wallets.walletFor(wallet.account, chatRoomId).state
            noticeGuardianSpends(wallet.account)
            recordDeposits(chainState)
            watching = viewModelScope.launch(Dispatchers.IO) {
                combine(
                    chainState,
                    frostSigningRepository.observeGroupTransactions(chatRoomId),
                    guardedStates(guarded),
                ) { state, transactions, guardedWallets ->
                    Triple(state, transactions.map { rowOf(it) }, guardedWallets)
                }.collect { (state, rows, guardedWallets) ->
                    val unowned = payoutRepository.balances(chatRoomId, chain, state).unownedSat
                    groupWalletUIState = loaded.copy(walletState = state, spends = rows, guardedWallets = guardedWallets, unownedSat = unowned)
                }
            }
        }
    }

    /**
     * What this device calls each of [guardians]' rooms, for the ones it holds:
     * the wallet screen lists each guardian with its own delay, and a room id is
     * not a name. A guardian whose room this device does not have is left out,
     * and the screen says *a group above this one*.
     */
    private suspend fun namesOf(guardians: List<GuardianDerivation.Guardian>): Map<String, String> =
        guardians.mapNotNull { guardian ->
            chatRepository.getChatRoomByIdentifier(guardian.chatRoomId)?.chatRoom?.subject?.let { guardian.chatRoomId to it }
        }.toMap()

    /** Each guarded wallet with what the chain says of it, on the identity's Electrum client. */
    private suspend fun guardedStates(guarded: List<GuardianManager.GuardedWallet>): Flow<List<GuardedWalletRow>> {
        if (guarded.isEmpty()) return flowOf(emptyList())

        val states = guarded.map { wallet -> wallets.walletFor(wallet.account).state.map { GuardedWalletRow(wallet, it) } }
        return combine(states) { it.toList() }
    }

    private var noticing: Job? = null
    private var recording: Job? = null

    /**
     * Each time the coins or the used addresses change, files every output that has paid the
     * receive and deposit branches -- so a deposit counts in its member's tally, and keeps counting
     * once the group spends it. `docs/member-deposits.md`, Phase 8.
     */
    private fun recordDeposits(states: Flow<GroupWalletState>) {
        recording?.cancel()
        recording = viewModelScope.launch(Dispatchers.IO) {
            states
                .distinctUntilChangedBy { state ->
                    state.utxos.map { it.utxo.outPoint }.toSet() to
                        (listOf(state.receive) + state.members.values.map { it.state }).sumOf { branch -> branch.addresses.values.count { it.alreadyUsed } }
                }
                .collect { state -> depositRepository.recordOnchain(chatRoomId, chain, wallets.depositsIn(state)) }
        }
    }

    /**
     * For a guarded wallet: each time its coins change, reads the chain for
     * spends a guardian made from it and writes the transcript line for any that
     * has none -- the group's only notice, since nobody in it signed them.
     */
    private suspend fun noticeGuardianSpends(account: GroupWalletDerivation.Account) {
        if (!account.isGuarded) return
        val onchain = wallets.walletFor(account)

        noticing?.cancel()
        noticing = viewModelScope.launch(Dispatchers.IO) {
            onchain.state
                .distinctUntilChangedBy { state -> state.utxos.map { it.utxo.outPoint }.toSet() }
                .collect { state -> chatRepository.recordGuardianSpends(chatRoomId, wallets.guardianSpends(state)) }
        }
    }

    /**
     * Tells the groups above about [shareable]'s guarded wallet: one notice into
     * each of their rooms this device is in. What is told stays told, so the
     * row goes from the screen once it is sent.
     */
    fun share(shareable: GuardianManager.Shareable, onShared: () -> Unit, onFailure: () -> Unit) {
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val told = chatRepository.shareGuardedWallet(shareable, activeUserPublicKey)
            if (told > 0) {
                groupWalletUIState = when (val state = groupWalletUIState) {
                    is GroupWalletUIState.Loaded -> state.copy(shareable = state.shareable - shareable)
                    is GroupWalletUIState.NotOpened -> state.copy(shareable = state.shareable - shareable)
                    else -> state
                }
            }
            withContext(Dispatchers.Main) { if (told > 0) onShared() else onFailure() }
            isActionPending.value = false
        }
    }

    /** Checked once per session: the PSBT is write-once, so what it says cannot change. */
    private val checkedSpends = mutableMapOf<String, GroupSpend.Checked?>()
    private val checkedSweeps = mutableMapOf<String, GroupSweep.Checked?>()

    private suspend fun rowOf(transaction: GroupTransaction): SpendRow {
        val sessionId = transaction.frostSigningSessionId
        val spend = checkedSpends.getOrPut(sessionId) { frostSigningRepository.checkedSpend(sessionId) }
        val sweep = if (spend == null) checkedSweeps.getOrPut(sessionId) { frostSigningRepository.checkedSweep(sessionId) } else null
        val stage = frostSigningRepository.getSessionById(sessionId)?.stage
        val payout = spend?.takeIf { transaction.guardedChatRoomId == null }
            ?.let { payoutRepository.payoutOf(chatRoomId, it) as? PayoutRule.Result.Payout }
        return SpendRow(transaction = transaction, status = SpendStatus.of(stage, transaction), spend = spend, sweep = sweep, payout = payout)
    }

    /** Sends a signed spend again now. Only worth anything while Electrum has not reported it back. */
    fun sendAgain(sessionId: String, onSent: () -> Unit) {
        val watcher = watcher ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (runCatching { watcher.broadcastAgain(sessionId) }.getOrDefault(false)) {
                withContext(Dispatchers.Main) { onSent() }
            }
        }
    }

    /**
     * Asks the group to sign [sessionId]'s spend again at twice its feerate: the
     * same coins, the extra out of the change. A new session with new signatures --
     * a retry is never the old session again -- and whichever of the two confirms
     * settles the other.
     */
    fun bumpFee(sessionId: String, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = groupWalletUIState as? GroupWalletUIState.Loaded ?: return
        val row = state.spends.firstOrNull { it.transaction.frostSigningSessionId == sessionId } ?: return
        val spend = row.spend ?: return
        if (isActionPending.value) return
        isActionPending.value = true

        // A guardian's spend is bumped as one: the same leaf, the same descendant.
        val guardedChatRoomId = row.transaction.guardedChatRoomId

        viewModelScope.launch(Dispatchers.IO) {
            // A payout is bumped as a payout: the same coins and members, the larger fee split
            // again over all of them. docs/payouts-only.md, Phase 4.
            val psbt = if (row.payout != null) {
                (PayoutBuilder.bump(spend, payoutRepository.payoutContext(chatRoomId, chain), spend.feeratePerVbyte * 2) as? PayoutBuilder.Result.Built)?.psbt
            } else {
                (GroupSpendBuilder.bump(spend, spend.feeratePerVbyte * 2) as? GroupSpendBuilder.Result.Built)?.psbt
            }
            val session = psbt?.let {
                runCatching {
                    when (guardedChatRoomId) {
                        null -> frostSigningRepository.proposeSpend(state.localChatRoom, activeUserPublicKey, it, chain)
                        else -> frostSigningRepository.proposeGuardianSpend(state.localChatRoom, activeUserPublicKey, it, guardedChatRoomId, chain)
                    }
                }.onFailure { logger.e("Failed to bump $sessionId", it) }.getOrNull()
            }

            withContext(Dispatchers.Main) {
                if (session != null) onSuccess(session.id) else onFailure()
            }
            isActionPending.value = false
        }
    }

    /**
     * Asks the group to open its wallet: one signing session over one statement.
     *
     * Never batched with anything, for the reason `GroupWalletStatementEvent`
     * gives -- every spend is opened against this, and it should never be as
     * available as its worst co-passenger.
     *
     * [guarded] opens the guarded account the room's certificate grants instead
     * of the plain one: the statement then names the guardians and carries the
     * certificate, and the group's quorum signing it is the group agreeing to be
     * guarded.
     */
    fun openWallet(guarded: Boolean = false, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = groupWalletUIState as? GroupWalletUIState.NotOpened ?: return
        val template = if (guarded) {
            val opening = state.guarded ?: return
            GroupWalletStatementEvent.template(opening.account, opening.certificate)
        } else {
            // Not under a parent that decided this wallet is guarded: no state
            // offers it, and every other member would refuse it anyway.
            if (!state.offersPlain) return
            GroupWalletStatementEvent.template(state.account ?: return)
        }
        // A second statement for the same account is harmless -- the same values
        // under the same `d` tag -- but it is a second quorum asked for nothing.
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = state.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a wallet for $chatRoomId", error)
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (session != null) onSuccess(session.id) else onFailure()
            }

            isActionPending.value = false
        }
    }

    /**
     * Asks the group to open the active profile's deposits: a signing session over its
     * depositor statement, which proposing counts as approving. `docs/member-deposits.md`.
     */
    fun openMyDeposits(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = groupWalletUIState as? GroupWalletUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { depositRepository.open(loaded.localChatRoom, chain, activeUserPublicKey) }
                .onFailure { logger.e("Couldn't propose opening deposits in $chatRoomId", it) }
                .getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "GroupWalletViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            initialGroupWalletUIState: GroupWalletUIState = GroupWalletUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            wallets: GroupOnchainWallets,
            watcher: GroupTransactionWatcher? = null,
            depositRepository: DepositRepository = DepositRepository.NO_OP,
            payoutRepository: PayoutRepository = PayoutRepository.NO_OP,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupWalletViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    chain = chain,
                    initialGroupWalletUIState = initialGroupWalletUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                    wallets = wallets,
                    watcher = watcher,
                    depositRepository = depositRepository,
                    payoutRepository = payoutRepository,
                )
            }
        }
    }
}
