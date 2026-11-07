package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

/** What a group's Wavelength wallet screens read and propose (`docs/group-wavelength-ui.md`). Nothing here moves money: a proposal is signed by the group. */
interface GroupWavelengthRepository {
    suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean

    /** The sizes of key the group could make, which is empty for a group too small. */
    suspend fun ceremonyThresholds(chatRoomId: String): List<Int>

    /** The room's ceremonies for this wallet's key, newest first. */
    fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>>

    suspend fun unusedKey(chatRoomId: String): IcebergKeySession?

    /** The signing session already asking the group to open a wallet with [keyId], so a second isn't proposed beside it. */
    suspend fun openingSession(chatRoomId: String, keyId: String): String?

    /** Every wallet this device believes on [chain], lowest number first. */
    suspend fun wallets(chatRoomId: String, chain: Chain): List<GroupWavelengthWalletManager.Wallet>

    /** Why the room's newest statement on [chain] isn't believed, or null if it is or there is none. */
    suspend fun refusal(chatRoomId: String, chain: Chain): GroupWavelengthWalletManager.Refusal?

    suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthOperatorManager.Operator?

    /** The terms this build would propose a wallet under, or null where there is no public operator. */
    fun terms(chain: Chain): GroupWavelengthTerms.Terms?

    /** What the operator says now, or null if it can't be asked. A proposer's check, and nothing a device signs depends on it. */
    suspend fun liveTerms(chain: Chain): GroupWavelengthTerms.Terms?

    suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession

    suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession

    suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession

    /** Signers of [wallet] who have left the room and still hold a share. */
    suspend fun departed(wallet: GroupWavelengthWalletManager.Wallet): List<HexKey>

    /** Every payout the group has approved from [wallet], for the screen to follow. */
    suspend fun payouts(chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthPaymentEvent.Payment>

    /** Asks the group to pay a member from their balance: a proposal `t` people have to sign, like any other. */
    suspend fun proposePayout(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, invoice: String, maxFeeSat: Long, payout: GroupWavelengthPaymentEvent.Payout): FrostSigningSession

    /** Every onchain payout the group has approved from [wallet], with what this device has seen become of it. */
    suspend fun onchainPayouts(chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthOnchainPayout>

    /** Asks the group to pay a member onchain, [amountSat] to the address they gave, as a leave in a round. */
    suspend fun proposeOnchainPayout(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, destination: GroupPayoutDestination, amountSat: Long, maxFeeSat: Long): FrostSigningSession

    /** Asks the group to leave [wallet] for its onchain wallet. */
    suspend fun proposeExit(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): FrostSigningSession

    /** Whether the group has asked [wallet] to leave, and that request still stands. */
    suspend fun leaveAsked(chatRoomId: String, chain: Chain, wallet: Long): Boolean

    /** Whether [member] has a depositor statement on [chain]: nobody else gets an address. */
    suspend fun isDepositor(chatRoomId: String, chain: Chain, member: HexKey): Boolean

    /** Asks the operator for a boarding address for this device's member. */
    suspend fun requestDepositAddress(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): String

    /** Asks the operator for a Lightning invoice for this device's member. */
    suspend fun requestDepositInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, amountSat: Long): String

    /** [member]'s requests for addresses, and what's been answered and checked. */
    fun observeDepositAddresses(chatRoomId: String, chain: Chain, wallet: Long, member: HexKey): Flow<List<GroupArkDepositAddress>>

    /** What the wallet's operator last said it holds, and when, which is a claim. */
    fun observeSnapshot(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthSnapshot?>

    /** The chain's tip, from this device's own connection, or null if it has none just now. */
    suspend fun tipHeight(chain: Chain): Long?

    /** When each device in the room last answered anything for a wallet. */
    suspend fun seen(chatRoomId: String): Map<HexKey, Instant>

    /** What this device has refused or been alarmed by, newest first: its own words, for the wallet's screen. */
    suspend fun alarms(chatRoomId: String): List<String>

    /** What this device has agreed to pay in fees since [since], against the statement's budget, in [term]. */
    suspend fun feesSince(chatRoomId: String, chain: Chain, wallet: Long, term: Long, since: Long): Long

    /** How many signatures round one is ready for beyond what's been used, on this device's count. */
    suspend fun labelsLeft(chatRoomId: String, chain: Chain, wallet: Long, userPublicKey: HexKey): Long

    /** What this device has itself signed for the wallet: its own ledger, never the operator's word. */
    fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>>

    companion object {
        val NO_OP: GroupWavelengthRepository = object : GroupWavelengthRepository {
            override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean = false
            override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> = emptyList()
            override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(emptyList())
            override suspend fun unusedKey(chatRoomId: String): IcebergKeySession? = null
            override suspend fun openingSession(chatRoomId: String, keyId: String): String? = null
            override suspend fun wallets(chatRoomId: String, chain: Chain): List<GroupWavelengthWalletManager.Wallet> = emptyList()
            override suspend fun refusal(chatRoomId: String, chain: Chain): GroupWavelengthWalletManager.Refusal? = null
            override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthOperatorManager.Operator? = null
            override fun terms(chain: Chain): GroupWavelengthTerms.Terms? = null
            override suspend fun liveTerms(chain: Chain): GroupWavelengthTerms.Terms? = null
            override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession = error("No room to propose in")
            override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("No room to propose in")
            override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("No room to propose in")
            override suspend fun departed(wallet: GroupWavelengthWalletManager.Wallet): List<HexKey> = emptyList()
            override suspend fun payouts(chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthPaymentEvent.Payment> = emptyList()
            override suspend fun proposePayout(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, invoice: String, maxFeeSat: Long, payout: GroupWavelengthPaymentEvent.Payout): FrostSigningSession = error("No room to propose in")
            override suspend fun onchainPayouts(chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthOnchainPayout> = emptyList()
            override suspend fun proposeOnchainPayout(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, destination: GroupPayoutDestination, amountSat: Long, maxFeeSat: Long): FrostSigningSession = error("No room to propose in")
            override suspend fun proposeExit(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): FrostSigningSession = error("No room to propose in")
            override suspend fun leaveAsked(chatRoomId: String, chain: Chain, wallet: Long): Boolean = false
            override suspend fun isDepositor(chatRoomId: String, chain: Chain, member: HexKey): Boolean = false
            override suspend fun requestDepositAddress(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): String = error("No room to ask in")
            override suspend fun requestDepositInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, amountSat: Long): String = error("No room to ask in")
            override fun observeDepositAddresses(chatRoomId: String, chain: Chain, wallet: Long, member: HexKey): Flow<List<GroupArkDepositAddress>> = flowOf(emptyList())
            override fun observeSnapshot(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthSnapshot?> = flowOf(null)
            override suspend fun tipHeight(chain: Chain): Long? = null
            override suspend fun seen(chatRoomId: String): Map<HexKey, Instant> = emptyMap()
            override suspend fun alarms(chatRoomId: String): List<String> = emptyList()
            override suspend fun feesSince(chatRoomId: String, chain: Chain, wallet: Long, term: Long, since: Long): Long = 0
            override suspend fun labelsLeft(chatRoomId: String, chain: Chain, wallet: Long, userPublicKey: HexKey): Long = 0
            override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> = flowOf(emptyList())
        }
    }
}

/**
 * An onchain payout the group approved, and where it has got to by this device's own record (`docs/group-wavelength-payouts.md`, W11):
 * waiting for a round while [leftSat] is null, in one once this device signed it, and paid once that round confirmed.
 */
data class GroupWavelengthOnchainPayout(
    val payout: GroupWavelengthOnchainPayoutEvent.Payout,
    /** What left with it, the leave and the round's fee, once this device signed a round paying it. */
    val leftSat: Long? = null,
    val confirmed: Boolean = false,
) {
    fun isExpired(nowSeconds: Long): Boolean = leftSat == null && payout.expiresAt <= nowSeconds
}
