package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.repository.GroupWavelengthOnchainPayout
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.GroupWavelengthDeposits
import com.machankura.dedun.compose.managers.GroupWavelengthPayments
import com.machankura.dedun.compose.managers.GroupWavelengthPayouts
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.managers.IcebergRitualManager
import com.machankura.dedun.compose.network.GroupWavelengthTermsReader
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.machankura.dedun.compose.wallet.GroupChainClients
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

class DatabaseGroupWavelengthRepository(
    private val database: DedunDatabase,
    private val termsReader: GroupWavelengthTermsReader = GroupWavelengthTermsReader(),
    /** The identity's chain connections, which come and go with it. */
    private val clients: () -> GroupChainClients? = { null },
) : GroupWavelengthRepository {
    override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean =
        GroupWalletManager.walletFor(database, chatRoomId, chain) != null

    override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> =
        IcebergRitualManager.thresholds(database, chatRoomId)

    override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> =
        database.icebergKeySessionDao().observeSessionsForChatRoom(chatRoomId).map { sessions -> sessions.filter { it.purpose == IcebergKeySession.PURPOSE_WAVELENGTH } }

    override suspend fun unusedKey(chatRoomId: String): IcebergKeySession? =
        GroupWavelengthWalletManager.unusedKey(database, chatRoomId)

    override suspend fun openingSession(chatRoomId: String, keyId: String): String? =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId).first()
            .filter { it.session.stage != FrostSigningStage.FAILED && it.session.stage != FrostSigningStage.COMPLETE }
            .firstOrNull { signing ->
                signing.items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
                    .any { it.kind == GroupWavelengthWalletStatementEvent.KIND && GroupWavelengthWalletStatementEvent.read(it.tags)?.ceremonyId == keyId }
            }?.session?.id

    override suspend fun wallets(chatRoomId: String, chain: Chain): List<GroupWavelengthWalletManager.Wallet> =
        GroupWavelengthWalletManager.wallets(database, chatRoomId, chain)

    override suspend fun refusal(chatRoomId: String, chain: Chain): GroupWavelengthWalletManager.Refusal? =
        GroupWavelengthWalletManager.refusalOn(database, chatRoomId, chain)

    override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthOperatorManager.Operator? =
        GroupWavelengthOperatorManager.current(database, chatRoomId, chain, wallet)

    override fun terms(chain: Chain): GroupWavelengthTerms.Terms? = GroupWavelengthTerms.of(chain)

    override suspend fun liveTerms(chain: Chain): GroupWavelengthTerms.Terms? = termsReader.read(chain)

    override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession {
        val terms = requireNotNull(GroupWavelengthTerms.of(chain)) { "This build knows no Ark operator on ${chain.name}" }
        return GroupWavelengthWalletManager.proposeWallet(database, localChatRoom, userPublicKey, nostrPrivateKey, chain, terms.operatorKey, policy = terms.policy())
    }

    override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession =
        GroupWavelengthOperatorManager.nominateSelf(database, localChatRoom, userPublicKey, chain, wallet)

    override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession =
        GroupWavelengthOperatorManager.stepDown(database, localChatRoom, userPublicKey, chain, wallet)

    override suspend fun departed(wallet: GroupWavelengthWalletManager.Wallet): List<HexKey> =
        GroupWavelengthWalletManager.departed(database, wallet)

    override suspend fun payouts(chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthPaymentEvent.Payment> =
        GroupWavelengthPayouts.signed(database, chatRoomId, chain).filter { it.wallet == wallet }

    override suspend fun wentNowhere(chatRoomId: String, chain: Chain, wallet: Long): Set<String> =
        payouts(chatRoomId, chain, wallet).filter { GroupWavelengthPayouts.wentNowhere(database, chatRoomId, it) }.mapNotNull { it.paymentHash?.toString()?.lowercase() }.toSet()

    override suspend fun proposePayout(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, invoice: String, maxFeeSat: Long, payout: GroupWavelengthPaymentEvent.Payout, lnurl: String?): FrostSigningSession {
        val chain = wallet.statement.chain
        require(GroupWavelengthRollout.paysOverLightning(chain)) { "Payouts over Lightning are off on ${chain.name}" }
        val tip = requireNotNull(tipHeight(chain)) { "No chain connection to set a refund limit from" }
        val expiresAt = Clock.System.now().epochSeconds + APPROVAL_LASTS_SECONDS
        return GroupWavelengthPayments.propose(database, localChatRoom, userPublicKey, wallet, invoice, maxFeeSat, tip + GroupWavelengthRollout.MAX_REFUND_BLOCKS, expiresAt, payout, lnurl)
    }

    override suspend fun onchainPayouts(chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthOnchainPayout> {
        val leaves = database.groupArkDao().leaves(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet)).associateBy { it.authorisation }
        return GroupWavelengthPayouts.signedOnchain(database, chatRoomId, chain).filter { it.wallet == wallet }.map { payout ->
            val leave = leaves[payout.id]
            GroupWavelengthOnchainPayout(payout, leave?.let { it.amountSat + it.feeSat }, leave?.confirmed == true)
        }
    }

    override suspend fun proposeOnchainPayout(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, destination: GroupPayoutDestination, amountSat: Long, maxFeeSat: Long): FrostSigningSession {
        val chain = wallet.statement.chain
        require(GroupWavelengthRollout.paysOnchain(chain)) { "Onchain payouts are off on ${chain.name}" }
        val address = requireNotNull(destination.onchainAddress) { "The member has given no onchain address" }
        val payout = GroupWavelengthOnchainPayoutEvent.paying(chain, wallet.statement.wallet, destination.member, destination.event.id, address, amountSat, maxFeeSat)
        return GroupWavelengthPayouts.proposeOnchain(database, localChatRoom, userPublicKey, payout)
    }

    override suspend fun proposeExit(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): FrostSigningSession =
        GroupWavelengthPayouts.proposeExit(database, localChatRoom, userPublicKey, wallet)

    override suspend fun leaveAsked(chatRoomId: String, chain: Chain, wallet: Long): Boolean =
        GroupWavelengthPayouts.signedExits(database, chatRoomId, chain, wallet).any { it.expiresAt > Clock.System.now().epochSeconds }

    override suspend fun isDepositor(chatRoomId: String, chain: Chain, member: HexKey): Boolean =
        member.lowercase() in Depositors.statementsIn(database, chatRoomId, chain).keys.map { it.lowercase() }

    override suspend fun requestDepositAddress(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): String =
        GroupWavelengthDeposits.request(database, localChatRoom, userPublicKey, wallet)

    override suspend fun requestDepositInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, amountSat: Long): String =
        GroupWavelengthDeposits.request(database, localChatRoom, userPublicKey, wallet, amountSat)

    override fun observeDepositAddresses(chatRoomId: String, chain: Chain, wallet: Long, member: HexKey): Flow<List<GroupArkDepositAddress>> =
        database.groupArkDao().observeDepositAddresses(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet)).map { rows -> rows.filter { it.requester == member.lowercase() } }

    override fun observeBoarded(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkBoarded>> =
        database.groupArkDao().observeBoarded(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet))

    override fun observeClaims(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkClaim>> =
        database.groupArkDao().observeClaims(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet))

    override fun observeSnapshot(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthSnapshot?> =
        database.groupArkDao().observeSnapshot(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet)).map { row ->
            row?.let { runCatching { CommonJson.decodeFromString(GroupWavelengthSnapshot.serializer(), it.content) }.getOrNull() }
        }

    override suspend fun tipHeight(chain: Chain): Long? =
        runCatching { clients()?.clientFor(chain)?.startHeaderSubscription()?.blockHeight?.toLong() }.getOrNull()

    override suspend fun seen(chatRoomId: String): Map<HexKey, Instant> =
        database.marmotInnerEventDao().newestBySender(chatRoomId, GroupWavelengthEvents.CO_SIGNING.toList()).associate { it.publicKey.lowercase() to it.at }

    override suspend fun alarms(chatRoomId: String): List<String> =
        database.chatMessageDao().linesOfTypes(chatRoomId, ChatMessage.WAVELENGTH_ALARM_TYPES, ALARMS_SHOWN).map { it.content }

    override suspend fun feesSince(chatRoomId: String, chain: Chain, wallet: Long, term: Long, since: Long): Long =
        database.groupArkDao().fees(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet), term, since).sumOf { it.feeSat }

    override suspend fun labelsLeft(chatRoomId: String, chain: Chain, wallet: Long, userPublicKey: HexKey): Long {
        val key = GroupArkCoSigner.walletKey(chatRoomId, chain, wallet)
        val coSigning = database.icebergCoSigningDao()
        val made = coSigning.highestNonce(key, userPublicKey.lowercase()) ?: return 0
        return (made - (coSigning.highestAssignment(key) ?: -1)).coerceAtLeast(0)
    }

    override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> =
        database.groupArkDao().observeVtxos(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet))

    private companion object {
        const val ALARMS_SHOWN = 3

        /** Long enough for people to get to it. An invoice can run out first, and then it takes a new one. */
        const val APPROVAL_LASTS_SECONDS = 24L * 60 * 60
    }
}
