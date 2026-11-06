package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.GroupWavelengthSeed
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkManifest
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import kotlinx.serialization.Serializable

/**
 * A group's Wavelength wallet as a statement the room signed -- `docs/group-wavelength-wallet.md`, Phase 4. Shaped like
 * [GroupLightningWalletManager], and with no table: the signed events are the record.
 */
object GroupWavelengthWalletManager {
    private val logger = Logger.withTag("GroupWavelengthWallet")

    class Wallet(val signedEvent: GroupSignedEvent, val statement: GroupWavelengthWalletStatementEvent.Statement)

    /** A seed for each participant, encrypted to them, inside the room. */
    @Serializable
    internal data class SeedMessage(val chain: String, val wallet: Long, val identity: String, val to: Map<String, String>)

    /** The identity and manifest hash an authentication seed and an Iceberg key make: everything a statement claims of them. */
    fun bindingOf(icebergKey: PublicKey, seed: ByteVector32, chain: Chain, wallet: Long): Pair<HexKey, String> {
        val auth = GroupArkAuth.of(seed, chain)
        return auth.identity().toString() to GroupArkManifest.of(icebergKey, auth, chain, wallet).hash
    }

    /** Every wallet [chatRoomId] has opened on [chain] that this device believes, the newest statement for each number, lowest first. */
    suspend fun wallets(database: DedunDatabase, chatRoomId: String, chain: Chain): List<Wallet> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthWalletStatementEvent.KIND)
            .sortedWith(compareByDescending<GroupSignedEvent> { it.createdAt }.thenBy { it.id })
            .mapNotNull { verify(database, chatRoomId, it)?.takeIf { wallet -> wallet.statement.chain == chain } }
            .distinctBy { it.statement.wallet }
            .sortedBy { it.statement.wallet }

    /** The wallet a room uses on [chain] now: the highest-numbered one it has opened, or null. */
    suspend fun current(database: DedunDatabase, chatRoomId: String, chain: Chain): Wallet? = wallets(database, chatRoomId, chain).lastOrNull()

    /** A completed Wavelength key in [chatRoomId] that this device holds a share of and no wallet uses yet. */
    suspend fun unusedKey(database: DedunDatabase, chatRoomId: String): IcebergKeySession? {
        val used = database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthWalletStatementEvent.KIND)
            .mapNotNull { GroupWavelengthWalletStatementEvent.read(it.tags)?.ceremonyId }.toSet()
        return database.icebergKeySessionDao().getSessionsForChatRoom(chatRoomId)
            .firstOrNull { it.purpose == IcebergKeySession.PURPOSE_WAVELENGTH && it.stage == IcebergRitualStage.COMPLETE && it.share != null && it.id !in used }
    }

    /**
     * Whether [signed] is a statement for [chatRoomId] this device can stand behind: the room signed it, the onchain
     * wallet it leaves to is open, its participants hold the onchain key, its Iceberg key is the one this device's ceremony
     * made for this purpose, and the authentication seed this device holds makes the identity and manifest it names. A
     * reader who is not a participant checks everything but the last two. Null otherwise, with the reason in the log.
     */
    suspend fun verify(database: DedunDatabase, chatRoomId: String, signed: GroupSignedEvent): Wallet? {
        if (signed.kind != GroupWavelengthWalletStatementEvent.KIND || !signed.verifies()) return null
        val statement = GroupWavelengthWalletStatementEvent.read(signed.tags) ?: return null
        GroupWalletManager.thresholdKeyFor(database, chatRoomId) ?: return null
        if (GroupWalletManager.walletFor(database, chatRoomId, statement.chain, statement.onchainAccount) == null) {
            logger.w("Wavelength wallet ${signed.id} leaves to an onchain wallet this room has not opened")
            return null
        }
        FrostSigningManager.completedKey(database, chatRoomId)?.participantPublicKeySet()?.let { holders ->
            if (!holders.map { it.lowercase() }.containsAll(statement.participants)) {
                logger.w("Wavelength wallet ${signed.id} names participants who do not hold the onchain key")
                return null
            }
        }

        val ceremony = database.icebergKeySessionDao().getSessionById(statement.ceremonyId)
        val user = ceremony?.userPublicKey ?: database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey
        val isParticipant = user != null && user.lowercase() in statement.participants
        if (ceremony == null && isParticipant) {
            logger.w("Wavelength wallet ${signed.id} names a ceremony this participant has no record of")
            return null
        }
        if (ceremony != null) {
            val agrees = ceremony.purpose == IcebergKeySession.PURPOSE_WAVELENGTH &&
                ceremony.stage == IcebergRitualStage.COMPLETE &&
                ceremony.groupPublicKey == statement.icebergKey &&
                ceremony.threshold == statement.threshold &&
                ceremony.participants() == statement.participants
            if (!agrees) {
                logger.w("Wavelength wallet ${signed.id} disagrees with this device's ceremony ${ceremony.id}")
                return null
            }
        }

        if (isParticipant) {
            val seed = database.groupWavelengthSeedDao().get(chatRoomId, statement.identity)
            if (seed == null) {
                logger.w("Wavelength wallet ${signed.id} names an identity this participant holds no seed for")
                return null
            }
            val (identity, manifest) = bindingOf(PublicKey.fromHex(statement.icebergKey), ByteVector32(seed.seed), statement.chain, statement.wallet)
            if (identity != statement.identity || manifest != statement.manifestHash) {
                logger.w("Wavelength wallet ${signed.id} names a manifest this device's keys don't make")
                return null
            }
        }
        return Wallet(signed, statement)
    }

    /**
     * Proposes the wallet: makes its authentication seed, sends it to every other participant encrypted to them, and opens
     * a signing session over the statement. The wallet exists once a quorum signs.
     */
    suspend fun proposeWallet(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        chain: Chain,
        arkOperatorKey: HexKey,
        wallet: Long? = null,
        onchainAccount: Long = 0L,
        policy: GroupWavelengthWalletStatementEvent.Policy = GroupWavelengthWalletStatementEvent.Policy.DEFAULT,
    ): FrostSigningSession {
        require(GroupWavelengthRollout.allows(chain)) { "A Wavelength wallet cannot be opened on ${chain.name}" }
        val roomId = localChatRoom.chatRoom.id
        val ceremony = unusedKey(database, roomId)
            ?: throw IllegalStateException("This group has no Wavelength wallet key that no wallet uses yet")
        val walletNumber = wallet ?: ((wallets(database, roomId, chain).maxOfOrNull { it.statement.wallet } ?: -1L) + 1)
        requireNotNull(GroupWalletManager.walletFor(database, roomId, chain, onchainAccount)) {
            "Open a group onchain wallet on ${chain.name} first: it is where money leaving this wallet goes"
        }

        val seed = ByteVector32(RandomInstance.bytes(32))
        val (identity, manifestHash) = bindingOf(PublicKey.fromHex(ceremony.groupPublicKey!!), seed, chain, walletNumber)
        val chainName = GroupWalletStatementEvent.chainName(chain)
        database.groupWavelengthSeedDao().insertIfAbsent(GroupWavelengthSeed(roomId, identity, chainName, walletNumber, seed.toHex(), userPublicKey))
        val to = ceremony.participants().filter { it != userPublicKey.lowercase() }.associateWith { recipient ->
            Nip44.encrypt(seed.toHex(), nostrPrivateKey, recipient.hexToByteArray()).encodePayload()
        }
        GroupLightningWalletManager.queue(
            database, localChatRoom, userPublicKey, GroupWavelengthEvents.AUTH_SEED,
            CommonJson.encodeToString(SeedMessage.serializer(), SeedMessage(chainName, walletNumber, identity, to)),
        )

        val template = GroupWavelengthWalletStatementEvent.template(
            GroupWavelengthWalletStatementEvent.Statement(
                chain = chain,
                wallet = walletNumber,
                onchainAccount = onchainAccount,
                icebergKey = ceremony.groupPublicKey,
                ceremonyId = ceremony.id,
                threshold = ceremony.threshold,
                participants = ceremony.participants(),
                identity = identity,
                manifestHash = manifestHash,
                arkOperatorKey = arkOperatorKey,
                policy = policy,
            )
        )
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * Files the seed a member sent this device, after checking it produces the identity the member claimed. The claim is
     * all the sender gets to say: the identity is recomputed here, and a statement names an identity, not a seed.
     */
    suspend fun receiveSeed(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event, userPublicKey: HexKey, nostrPrivateKey: ByteArray) {
        val message = runCatching { CommonJson.decodeFromString(SeedMessage.serializer(), innerEvent.content) }.getOrNull() ?: return
        val chain = GroupWalletStatementEvent.chainNamed(message.chain) ?: return
        val ciphertext = message.to[userPublicKey.lowercase()] ?: return
        val seedHex = runCatching { Nip44.decrypt(ciphertext, nostrPrivateKey, innerEvent.pubKey.hexToByteArray()) }.getOrNull() ?: return
        val seed = runCatching { ByteVector32(seedHex) }.getOrNull() ?: return
        val identity = GroupArkAuth.of(seed, chain).identity().toString()
        if (identity != message.identity) {
            logger.w("An authentication seed from ${innerEvent.pubKey.take(8)} does not produce the identity it claims; dropping")
            return
        }
        database.groupWavelengthSeedDao().insertIfAbsent(GroupWavelengthSeed(localChatRoom.chatRoom.id, identity, message.chain, message.wallet, seedHex, innerEvent.pubKey))
    }
}
