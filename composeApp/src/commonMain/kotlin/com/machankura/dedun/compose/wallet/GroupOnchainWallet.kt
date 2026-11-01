package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ElectrumMiniWallet
import fr.acinq.lightning.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.utils.sum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * A group's wallet on chain: the counterpart of Phoenix's `FinalWallet`, watching
 * the addresses [account] derives.
 *
 * Phoenix's final wallet watches one address forever. A group hands addresses out
 * from several devices and keeps change, so this watches both BIP44 branches, each
 * gap-limited -- and because an `ElectrumMiniWallet` holds one address generator,
 * that is two of them. Both share the identity's Electrum client, which is safe:
 * each ignores script hashes it does not know.
 *
 * In memory, as Phoenix's is. Everything it knows is fetched again from Electrum
 * when it starts, and nothing about the wallet is stored -- the addresses are a
 * derivation, and the coins are on chain.
 */
class GroupOnchainWallet(
    val account: GroupWalletDerivation.Account,
    electrum: IElectrumClient,
    scope: CoroutineScope,
    logger: Logger = Logger.withTag("GroupOnchainWallet"),
) {
    private val receive = ElectrumMiniWallet(account.chain.chainHash, electrum, scope, logger, lookAhead = RECEIVE_LOOKAHEAD)
    private val change = ElectrumMiniWallet(account.chain.chainHash, electrum, scope, logger, lookAhead = CHANGE_LOOKAHEAD)

    private val tip = MutableStateFlow((electrum.connectionStatus.value as? ElectrumConnectionStatus.Connected)?.height)

    /** The best block this wallet has heard of, or null before Electrum has connected. */
    val tipHeight: StateFlow<Int?> = tip.asStateFlow()

    init {
        receive.addAddressGenerator { index -> addressAt(GroupWalletDerivation.RECEIVE, index) }
        change.addAddressGenerator { index -> addressAt(GroupWalletDerivation.CHANGE, index) }

        // The tip, for confirmations. A connection reports the height it found and
        // every new block arrives as a header notification on the shared client.
        scope.launch {
            electrum.connectionStatus.filterIsInstance<ElectrumConnectionStatus.Connected>().collect { connected ->
                tip.value = maxOf(tip.value ?: 0, connected.height)
            }
        }
        scope.launch {
            electrum.notifications.filterIsInstance<HeaderSubscriptionResponse>().collect { header ->
                tip.value = maxOf(tip.value ?: 0, header.blockHeight)
            }
        }
    }

    /** Both branches and the tip, as one value. */
    val state: Flow<GroupWalletState> =
        combine(receive.walletStateFlow, change.walletStateFlow, tip) { receiveState, changeState, height ->
            GroupWalletState(account, receiveState, changeState, height)
        }

    fun stop() {
        receive.stop()
        change.stop()
    }

    private fun addressAt(change: Long, index: Int): String =
        GroupWalletDerivation.derive(account, change, index.toLong()).address(account.chain)

    companion object {
        /**
         * Receive addresses watched past the first unused one. Phoenix's swap-in
         * wallet uses 3; a group has several devices showing "the next address",
         * so two members can each hand one out before either is paid.
         */
        const val RECEIVE_LOOKAHEAD: UInt = 10u

        /**
         * Change addresses watched, counting the first unused one. Each open
         * spend with change holds one until it confirms, and several members
         * can each have one open, so this is how many such spends a group can
         * have at once before the next is told to wait.
         */
        const val CHANGE_LOOKAHEAD: UInt = 10u

        /**
         * Unused receive addresses the address list offers past the current one,
         * so several members can each hand out a different one. Every one of them
         * has to be watched already, or a payment to it would go unseen.
         */
        const val ADDRESSES_AHEAD: Int = 2

        init {
            require(ADDRESSES_AHEAD < RECEIVE_LOOKAHEAD.toInt()) { "the address list would offer addresses the wallet does not watch" }
        }
    }
}

/** One of the wallet's addresses as the address list shows it. */
data class GroupAddress(
    val derivation: GroupWalletDerivation.Derived,
    val address: String,
    val status: Status,
    /** What it holds now: zero for an address never paid, and for one paid and spent. */
    val balance: Satoshi,
) {
    enum class Status {
        /** The address the wallet screen is handing out. */
        CURRENT,

        /** Not paid yet, and past the current one. */
        NEXT,

        /** Paid at least once, whether or not the coins have moved since. */
        USED,
    }
}

/**
 * One coin the group holds, and where under the account it is.
 *
 * [derivation] is what a spend needs to sign it -- the cache -- and what a signer
 * checks the coin's script against.
 */
data class GroupUtxo(
    val utxo: WalletState.Utxo,
    val derivation: GroupWalletDerivation.Derived,
) {
    val amount: Satoshi get() = utxo.amount
    val isConfirmed: Boolean get() = utxo.blockHeight > 0
}

/**
 * The group's wallet at one moment: both branches' coins, the next addresses to
 * use, and how deep each coin is.
 */
data class GroupWalletState(
    val account: GroupWalletDerivation.Account,
    val receive: WalletState,
    val change: WalletState,
    val tipHeight: Int?,
) {
    /** Every coin, receive and change alike, with the derivation that spends it. */
    val utxos: List<GroupUtxo> by lazy {
        listOf(GroupWalletDerivation.RECEIVE to receive, GroupWalletDerivation.CHANGE to change).flatMap { (branch, state) ->
            state.utxos.mapNotNull { utxo ->
                val index = utxo.addressMeta.indexOrNull ?: return@mapNotNull null
                GroupUtxo(utxo, GroupWalletDerivation.derive(account, branch, index.toLong()))
            }
        }
    }

    val confirmedBalance: Satoshi get() = utxos.filter { it.isConfirmed }.map { it.amount }.sum()

    /** In the mempool: the group's, but not yet spendable by the send-all builder. */
    val pendingBalance: Satoshi get() = utxos.filterNot { it.isConfirmed }.map { it.amount }.sum()

    /**
     * The address to hand out, or null until Electrum has said which are used.
     *
     * Null rather than index 0 on purpose: before the first answer every address
     * reads as unused, and showing `/0/0` to a group that has been paid there
     * fifty times would be address reuse with a straight face.
     */
    val receiveAddress: String? get() = receive.firstUnusedDerivedAddress?.first

    /**
     * The receive addresses worth listing, newest first: every one paid, the one
     * being handed out, and [GroupOnchainWallet.ADDRESSES_AHEAD] after it -- or
     * null until Electrum has said which are used, for the reason
     * [receiveAddress] is.
     *
     * The list reaches the highest paid address even past the current one plus
     * the addresses ahead: the current address is the *lowest* unused one, so a
     * watch-only wallet paid further up leaves it where it was.
     */
    val receiveAddresses: List<GroupAddress>? by lazy {
        val current = receive.firstUnusedDerivedAddress?.second?.index ?: return@lazy null
        listed(GroupWalletDerivation.RECEIVE, receive, current)
    }

    /**
     * The change addresses that have been used, newest first, or null until
     * Electrum has answered. Never a current or a next one: change is chosen by
     * a spend, and is not handed out to be paid.
     */
    val changeAddresses: List<GroupAddress>? by lazy {
        change.firstUnusedDerivedAddress ?: return@lazy null
        listed(GroupWalletDerivation.CHANGE, change, current = null)
    }

    /**
     * [branch]'s addresses up to the highest used one, and -- given the branch's
     * [current] index -- up to [GroupOnchainWallet.ADDRESSES_AHEAD] past that.
     * Without a current index, only the used ones.
     */
    private fun listed(branch: Long, state: WalletState, current: Int?): List<GroupAddress> {
        val derived = state.addresses.mapNotNull { (address, addressState) ->
            (addressState.meta as? WalletState.AddressMeta.Derived)?.let { Triple(address, it.index, addressState) }
        }
        val highestUsed = derived.filter { it.third.alreadyUsed }.maxOfOrNull { it.second } ?: -1
        val last = current?.let { maxOf(it + GroupOnchainWallet.ADDRESSES_AHEAD, highestUsed) } ?: highestUsed

        return derived
            .filter { (_, index, addressState) -> index <= last && (current != null || addressState.alreadyUsed) }
            .sortedByDescending { (_, index, _) -> index }
            .map { (address, index, addressState) ->
                GroupAddress(
                    derivation = GroupWalletDerivation.derive(account, branch, index.toLong()),
                    address = address,
                    status = when {
                        addressState.alreadyUsed -> GroupAddress.Status.USED
                        index == current -> GroupAddress.Status.CURRENT
                        else -> GroupAddress.Status.NEXT
                    },
                    balance = addressState.utxos.map { it.amount }.sum(),
                )
            }
    }

    /**
     * The receive address an ancestor pays when it funds this wallet: the lowest
     * unused index from [GroupOnchainWallet.ADDRESSES_AHEAD] past the current one
     * that [reserved] -- the indices open fundings already pay -- does not hold,
     * and that the wallet watches. Null before Electrum has answered, for the
     * reason [receiveAddress] is, and when every such index is held.
     *
     * Not the current address: the wallet's own members are handing that one
     * out, and every device computes the same one. Starting at the top of the
     * address list's window and going up keeps an ancestor out of their way.
     * See `docs/guarded-wallet-funding.md`, D2.
     */
    fun fundingAddress(reserved: Set<Long> = emptySet()): GroupAddress? {
        val current = receive.firstUnusedDerivedAddress?.second?.index ?: return null
        val window = (current + GroupOnchainWallet.ADDRESSES_AHEAD) until (current + GroupOnchainWallet.RECEIVE_LOOKAHEAD.toInt())
        val (address, index) = receive.addresses.entries
            .filterNot { it.value.alreadyUsed }
            .mapNotNull { (address, addressState) -> addressState.meta.indexOrNull?.let { address to it } }
            .filter { (_, index) -> index in window && index.toLong() !in reserved }
            .minByOrNull { (_, index) -> index }
            ?: return null
        return GroupAddress(
            derivation = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index.toLong()),
            address = address,
            status = GroupAddress.Status.NEXT,
            balance = Satoshi(0),
        )
    }

    /** Whether Electrum has answered for any change address yet: before it has, there is no next one to name. */
    val changeKnown: Boolean get() = change.addresses.values.any { !it.alreadyUsed }

    /**
     * The change index a spend should pay back to: the lowest this wallet
     * watches that is unused and not in [reserved] -- the indices open spends
     * already pay to. Null when there is none: before Electrum has answered,
     * or when open spends hold every one inside the watched window, past which
     * change would land on an address nobody is watching.
     */
    fun nextChangeIndex(reserved: Set<Long> = emptySet()): Long? =
        change.addresses.values
            .filterNot { it.alreadyUsed }
            .mapNotNull { it.meta.indexOrNull?.toLong() }
            .filterNot { it in reserved }
            .minOrNull()

    /** How many confirmations [utxo] has: 0 in the mempool, and 0 until the tip is known. */
    fun confirmations(utxo: GroupUtxo): Int {
        val height = tipHeight ?: return 0
        val confirmedAt = utxo.utxo.blockHeight.toInt()
        return if (confirmedAt == 0) 0 else height - confirmedAt + 1
    }

    /**
     * Where under the account [script] is, among the addresses this wallet has
     * generated, or null if it is not one of them.
     *
     * The generated range is the bound on how far a check of somebody else's
     * claim will look: a derivation the wallet has never watched is not one it
     * will vouch for.
     */
    fun derivationOf(script: ByteVector): GroupWalletDerivation.Derived? =
        listOf(GroupWalletDerivation.RECEIVE to receive, GroupWalletDerivation.CHANGE to change)
            .asSequence()
            .flatMap { (branch, state) ->
                state.addresses.values.asSequence().mapNotNull { it.meta.indexOrNull }.map { branch to it.toLong() }
            }
            .map { (branch, index) -> GroupWalletDerivation.derive(account, branch, index) }
            .firstOrNull { it.pubkeyScript == script }
}

/** A BIP21 URI for [address], so a scanning wallet knows what the text it read is. */
fun bip21Uri(address: String): String = "bitcoin:$address"
