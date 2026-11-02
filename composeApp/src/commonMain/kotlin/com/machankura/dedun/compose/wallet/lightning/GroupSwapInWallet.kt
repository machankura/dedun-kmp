package com.machankura.dedun.compose.wallet.lightning

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.managers.GroupSwapInDerivation
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.OutPoint
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ElectrumMiniWallet
import fr.acinq.lightning.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.transactions.SwapInProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every depositor's swap-in addresses for one group Lightning wallet, watched on Electrum
 * (`docs/group-lightning-swap-in.md`, Phase 3).
 *
 * Every device runs one, not only the operator's: the addresses are a derivation from what every member
 * holds ([GroupSwapInDerivation]), so each member sees their own coins arrive and confirm without asking
 * the operator, and a co-signer can check that a coin it is asked to splice in is on a depositor's
 * address. The operator's node is handed [GroupSwapInState.forNode] as its swap-in wallet.
 *
 * Like [com.machankura.dedun.compose.wallet.GroupOnchainWallet]'s depositor branches, one mini-wallet per
 * depositor, added as their statements become known and never taken away: a member who leaves may still
 * have a coin on an address.
 */
class GroupSwapInWallet(
    val keys: GroupSwapInDerivation.Keys,
    private val electrum: IElectrumClient,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.withTag("GroupSwapInWallet"),
) {
    private class Watched(val branch: Long, val ordinal: Int, val wallet: ElectrumMiniWallet)

    private val members = MutableStateFlow<Map<HexKey, Watched>>(emptyMap())

    private val tip = MutableStateFlow((electrum.connectionStatus.value as? ElectrumConnectionStatus.Connected)?.height)

    /** The best block this wallet has heard of, or null before Electrum has connected. */
    val tipHeight: StateFlow<Int?> = tip.asStateFlow()

    init {
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

    /**
     * Starts watching each depositor in [branches] -- by member, their deposit branch -- that is not watched
     * yet. A depositor's ordinal is the order this wallet first saw them in, newcomers in branch order: it
     * only numbers their coins for this device's node (see [GroupSwapInState.forNode]), and is never sent.
     */
    fun watchDepositors(branches: Map<HexKey, Long>) {
        members.update { watched ->
            val added = branches.mapKeys { it.key.lowercase() }
                .filterKeys { it !in watched }
                .entries.sortedBy { it.value }
                .mapIndexed { i, (member, branch) ->
                    require(watched.size + i < MAX_DEPOSITORS) { "a group swap-in wallet numbers at most $MAX_DEPOSITORS depositors" }
                    logger.d { "watching ${member.take(8)}'s swap-in addresses on branch $branch" }
                    val wallet = ElectrumMiniWallet(keys.chain.chainHash, electrum, scope, logger, lookAhead = LOOKAHEAD)
                    wallet.addAddressGenerator { index -> keys.address(branch, index.toLong()).address }
                    member to Watched(branch, watched.size + i, wallet)
                }
            if (added.isEmpty()) watched else watched + added
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val depositorStates: Flow<Map<HexKey, GroupSwapInState.Depositor>> = members.flatMapLatest { watched ->
        if (watched.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(watched.map { (member, w) -> w.wallet.walletStateFlow.map { member to GroupSwapInState.Depositor(member, w.branch, w.ordinal, it) } }) { it.toMap() }
        }
    }

    /** Every depositor's addresses and coins, and the tip, as one value. */
    val state: Flow<GroupSwapInState> = combine(depositorStates, tip) { depositors, height -> GroupSwapInState(keys, height, depositors) }

    /** The latest [state], for the node's key manager, which looks an address up by the number it gave it. */
    val latest: StateFlow<GroupSwapInState?> = state.stateIn(scope, SharingStarted.Eagerly, null)

    fun stop() {
        members.value.values.forEach { it.wallet.stop() }
    }

    companion object {
        /**
         * Addresses watched past each depositor's first unused one. A swap-in branch is one member's, who
         * hands out the current address, so Phoenix's look-ahead does; a member with several devices
         * shows the same current address on each.
         */
        const val LOOKAHEAD: UInt = 3u

        /** How [GroupSwapInState.forNode] numbers an address: `ordinal * STRIDE + index`. */
        const val STRIDE: Int = 1 shl 16

        /** As many depositors as [STRIDE] leaves room for in an `Int`. */
        const val MAX_DEPOSITORS: Int = Int.MAX_VALUE / STRIDE
    }
}

/**
 * What a [GroupSwapInWallet] knows at one moment: each depositor's swap-in addresses and coins, and the tip.
 */
data class GroupSwapInState(
    val keys: GroupSwapInDerivation.Keys,
    val tipHeight: Int?,
    val depositors: Map<HexKey, Depositor>,
) {
    data class Depositor(val member: HexKey, val branch: Long, val ordinal: Int, val state: WalletState)

    /**
     * Every depositor's addresses as one wallet, for this device's node: each address renumbered
     * `ordinal * STRIDE + index`, which is the number lightning-kmp carries in a swap-in input and
     * [protocolAt] turns back into the address's protocol.
     */
    val forNode: WalletState by lazy {
        WalletState(depositors.values.flatMap { depositor ->
            depositor.state.addresses.mapNotNull { (address, addressState) ->
                val index = (addressState.meta as? WalletState.AddressMeta.Derived)?.index ?: return@mapNotNull null
                val meta = WalletState.AddressMeta.Derived(nodeIndex(depositor.ordinal, index))
                address to addressState.copy(meta = meta, utxos = addressState.utxos.map { it.copy(addressMeta = meta) })
            }
        }.toMap())
    }

    /** The protocol of the address [forNode] numbered [nodeIndex], or null for a number it never gave. */
    fun protocolAt(nodeIndex: Int): SwapInProtocol? {
        val ordinal = nodeIndex / GroupSwapInWallet.STRIDE
        val index = nodeIndex % GroupSwapInWallet.STRIDE
        val depositor = depositors.values.firstOrNull { it.ordinal == ordinal } ?: return null
        return keys.address(depositor.branch, index.toLong()).protocol
    }

    /** The depositor whose address holds the coin at [outPoint], or null for a coin on none of them. */
    fun depositorOf(outPoint: OutPoint): HexKey? =
        depositors.values.firstOrNull { d -> d.state.utxos.any { it.outPoint == outPoint } }?.member

    /**
     * The depositor whose address pays [protocol], with the address's index, or null when it is no
     * depositor's address this wallet watches -- what a co-signer asks of an input it is asked to sign.
     */
    fun ownerOf(protocol: SwapInProtocol): Pair<HexKey, Long>? {
        // The address commits to all four of the protocol's parts, so finding it among a depositor's is the check.
        val address = protocol.address(keys.chain)
        return depositors.values.firstNotNullOfOrNull { d ->
            (d.state.addresses[address]?.meta as? WalletState.AddressMeta.Derived)?.let { d.member to it.index.toLong() }
        }
    }

    /** The address [member] hands out now: the lowest unused one, or null before Electrum has answered or for a non-depositor. */
    fun currentAddressOf(member: HexKey): GroupSwapInDerivation.Address? {
        val depositor = depositors[member.lowercase()] ?: return null
        val (_, meta) = depositor.state.firstUnusedDerivedAddress ?: return null
        return keys.address(depositor.branch, meta.index.toLong())
    }

    companion object {
        fun nodeIndex(ordinal: Int, index: Int): Int {
            require(index in 0 until GroupSwapInWallet.STRIDE) { "address index $index is past what a node number holds" }
            return ordinal * GroupSwapInWallet.STRIDE + index
        }
    }
}
