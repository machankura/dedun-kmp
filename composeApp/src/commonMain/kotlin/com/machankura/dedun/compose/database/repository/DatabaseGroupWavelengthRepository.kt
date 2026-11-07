package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.managers.IcebergRitualManager
import com.machankura.dedun.compose.network.GroupWavelengthTermsReader
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

class DatabaseGroupWavelengthRepository(
    private val database: DedunDatabase,
    private val termsReader: GroupWavelengthTermsReader = GroupWavelengthTermsReader(),
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

    override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> =
        database.groupArkDao().observeVtxos(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet))
}
