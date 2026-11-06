package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupLightningSeed
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.crypto.LocalKeyManager
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A group's Lightning wallet as the room has agreed it: the statement the group signed
 * ([GroupLightningWalletStatementEvent]), checked against everything this device can recompute, and the
 * node seed the members hold (`docs/group-lightning-wallet.md`, Phase 6).
 */
object GroupLightningWalletManager {
    private val logger = Logger.withTag("GroupLightningWalletManager")

    /**
     * A statement this device believes, with what it was checked against.
     *
     * @param ceremony this device's own record of the Iceberg ceremony, or null when this device is not
     *   one of its participants and reads the wallet without being able to sign for it.
     * @param seed the node seed, when this device holds the one the statement names.
     */
    data class Wallet(
        val statement: GroupLightningWalletStatementEvent.Statement,
        val signedEvent: GroupSignedEvent,
        val basepoints: GroupLightningDerivation.Basepoints,
        val ceremony: IcebergKeySession?,
        val seed: GroupLightningSeed?,
    )

    @Serializable
    private data class SeedMessage(val chain: String, val wallet: Long, val nodeId: String, val to: Map<String, String>)

    /** The node id a group node running from [seed] on [chain] has: lightning-kmp's own derivation. */
    fun nodeIdOf(seed: ByteVector, chain: Chain): PublicKey =
        DeterministicWallet.generate(seed).derivePrivateKey(LocalKeyManager.nodeKeyBasePath(chain)).publicKey

    /**
     * Every Lightning wallet [chatRoomId] has opened on [chain] that this device believes, the newest
     * statement for each wallet number, lowest number first. The last is the current one; the others are
     * wallets a re-key left behind, whose channels are still to be closed (Phase 14).
     */
    suspend fun wallets(database: DedunDatabase, chatRoomId: String, chain: Chain): List<Wallet> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningWalletStatementEvent.KIND)
            .sortedWith(compareByDescending<GroupSignedEvent> { it.createdAt }.thenBy { it.id })
            .mapNotNull { verify(database, chatRoomId, it)?.takeIf { wallet -> wallet.statement.chain == chain } }
            .distinctBy { it.statement.wallet }
            .sortedBy { it.statement.wallet }

    /** The wallet a room uses on [chain] now: the highest-numbered one it has opened, or null. */
    suspend fun current(database: DedunDatabase, chatRoomId: String, chain: Chain): Wallet? = wallets(database, chatRoomId, chain).lastOrNull()

    /** The participants of [wallet] who are no longer in its room: each still holds a share and the seed. */
    suspend fun departed(database: DedunDatabase, wallet: Wallet): List<HexKey> {
        val inRoom = database.participantDao().findParticipantsByChatRoomId(wallet.signedEvent.chatRoomId).filter { it.deletedAt == null }.map { it.participantPublicKey.lowercase() }.toSet()
        return wallet.statement.participants.filter { it !in inRoom }
    }

    /**
     * A completed Iceberg key in [chatRoomId] that this device holds a share of and that no wallet the
     * room opened uses yet: what a new wallet -- the first, or the one a re-key makes -- is opened under.
     */
    suspend fun unusedKey(database: DedunDatabase, chatRoomId: String, chain: Chain): IcebergKeySession? {
        val used = database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningWalletStatementEvent.KIND)
            .mapNotNull { GroupLightningWalletStatementEvent.read(it.tags)?.ceremonyId }.toSet()
        return database.icebergKeySessionDao().getSessionsForChatRoom(chatRoomId)
            .firstOrNull { it.purpose == IcebergKeySession.PURPOSE_LIGHTNING && it.stage == IcebergRitualStage.COMPLETE && it.share != null && it.id !in used }
    }

    /** The newest statement for [chain] and [wallet] in [chatRoomId] that this device believes, or null. */
    suspend fun walletFor(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long = 0L): Wallet? =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningWalletStatementEvent.KIND)
            .sortedWith(compareByDescending<GroupSignedEvent> { it.createdAt }.thenBy { it.id })
            .firstNotNullOfOrNull { signed ->
                verify(database, chatRoomId, signed)?.takeIf { it.statement.chain == chain && it.statement.wallet == wallet }
            }

    /**
     * Whether [signed] is a Lightning wallet statement for [chatRoomId] that this device can stand behind:
     * the room signed it, the onchain wallet it closes to is open, its Iceberg key is the one this
     * device's ceremony made (when this device was in it), and its participants are the people who hold
     * the onchain key (D1). Null otherwise, with the reason in the log.
     */
    suspend fun verify(database: DedunDatabase, chatRoomId: String, signed: GroupSignedEvent): Wallet? {
        if (signed.kind != GroupLightningWalletStatementEvent.KIND || !signed.verifies()) return null
        val statement = GroupLightningWalletStatementEvent.read(signed.tags) ?: return null
        val roomKey = GroupWalletManager.thresholdKeyFor(database, chatRoomId) ?: return null
        if (GroupWalletManager.walletFor(database, chatRoomId, statement.chain, statement.onchainAccount) == null) {
            logger.w("Lightning wallet ${signed.id} closes to an onchain wallet this room has not opened")
            return null
        }
        // D1 as Phase 14 narrows it: every participant holds the onchain key. A wallet made after a member
        // left names fewer of them; the one before still names everyone, and both stay believable.
        FrostSigningManager.completedKey(database, chatRoomId)?.participantPublicKeySet()?.let { holders ->
            if (!holders.map { it.lowercase() }.containsAll(statement.participants)) {
                logger.w("Lightning wallet ${signed.id} names participants who do not hold the onchain key")
                return null
            }
        }
        val ceremony = database.icebergKeySessionDao().getSessionById(statement.ceremonyId)
        val user = ceremony?.userPublicKey ?: database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey
        if (ceremony == null && user != null && user.lowercase() in statement.participants) {
            logger.w("Lightning wallet ${signed.id} names a ceremony this participant has no record of")
            return null
        }
        if (ceremony != null) {
            val agrees = ceremony.stage == IcebergRitualStage.COMPLETE &&
                ceremony.groupPublicKey == statement.icebergKey &&
                ceremony.threshold == statement.threshold &&
                ceremony.participants() == statement.participants
            if (!agrees) {
                logger.w("Lightning wallet ${signed.id} disagrees with this device's ceremony ${ceremony.id}")
                return null
            }
        }
        val seed = database.groupLightningSeedDao().get(chatRoomId, statement.nodeId)
        return Wallet(statement, signed, GroupLightningDerivation.basepoints(roomKey, statement.chain, statement.wallet), ceremony, seed)
    }

    /**
     * Proposes the wallet: makes its node seed, sends it to every other participant encrypted to them
     * (kind 30357), and opens a signing session over the statement. The wallet exists once a quorum signs.
     */
    suspend fun proposeWallet(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        chain: Chain,
        lspNodeId: HexKey,
        wallet: Long? = null,
        onchainAccount: Long = 0L,
        policy: GroupLightningWalletStatementEvent.Policy = GroupLightningWalletStatementEvent.Policy.DEFAULT,
    ): FrostSigningSession {
        require(GroupLightningRollout.allows(chain)) { "A Lightning wallet cannot be opened on ${chain.name}" }
        val roomId = localChatRoom.chatRoom.id
        val ceremony = unusedKey(database, roomId, chain)
            ?: throw IllegalStateException("This group has no Lightning wallet key that no wallet uses yet")
        // The next number: a re-key opens a new wallet beside the old one, whose channels still need closing.
        val wallet = wallet ?: ((wallets(database, roomId, chain).maxOfOrNull { it.statement.wallet } ?: -1L) + 1)
        requireNotNull(GroupWalletManager.walletFor(database, roomId, chain, onchainAccount)) {
            "Open a group onchain wallet on ${chain.name} first: it is where the Lightning wallet closes to"
        }

        val seed = ByteVector(RandomInstance.bytes(32))
        val nodeId = nodeIdOf(seed, chain).value.toHex()
        val chainName = GroupWalletStatementEvent.chainName(chain)
        database.groupLightningSeedDao().insertIfAbsent(GroupLightningSeed(roomId, nodeId, chainName, wallet, seed.toHex(), userPublicKey))
        val to = ceremony.participants().filter { it != userPublicKey.lowercase() }.associateWith { recipient ->
            Nip44.encrypt(seed.toHex(), nostrPrivateKey, recipient.hexToByteArray()).encodePayload()
        }
        val seedMessage = queue(database, localChatRoom, userPublicKey, GroupLightningEvents.NODE_SEED, CommonJson.encodeToString(SeedMessage.serializer(), SeedMessage(chainName, wallet, nodeId, to)))
        GroupLightningLines.write(database, roomId, seedMessage.id, ChatMessage.TYPE_LIGHTNING_SEED_SHARED, userPublicKey, SEED_SHARED, seedMessage.createdAt)

        val statement = GroupLightningWalletStatementEvent.Statement(
            chain = chain,
            wallet = wallet,
            onchainAccount = onchainAccount,
            icebergKey = ceremony.groupPublicKey!!,
            ceremonyId = ceremony.id,
            threshold = ceremony.threshold,
            participants = ceremony.participants(),
            nodeId = nodeId,
            lspNodeId = lspNodeId,
            policy = policy,
        )
        val template = GroupLightningWalletStatementEvent.template(statement)
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * Files the seed a member sent this device, after checking it produces the node id the member
     * claimed. The claim is all the sender gets to say: the node id is recomputed here, and a statement
     * names a node, not a seed.
     */
    suspend fun receiveSeed(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event, userPublicKey: HexKey, nostrPrivateKey: ByteArray) {
        val message = runCatching { CommonJson.decodeFromString(SeedMessage.serializer(), innerEvent.content) }.getOrNull() ?: return
        val chain = GroupWalletStatementEvent.chainNamed(message.chain) ?: return
        suspend fun line() = GroupLightningLines.write(
            database, localChatRoom.chatRoom.id, innerEvent.id, ChatMessage.TYPE_LIGHTNING_SEED_SHARED,
            innerEvent.pubKey, SEED_SHARED, Instant.fromEpochSeconds(innerEvent.createdAt),
        )
        // A member it was not sent to cannot check it, and is told it happened all the same:
        // that the node has a seed, and who made it, is the group's business. A member it was
        // sent to is told only once the seed proves to be the node's.
        val ciphertext = message.to[userPublicKey.lowercase()] ?: return line()
        val seedHex = runCatching { Nip44.decrypt(ciphertext, nostrPrivateKey, innerEvent.pubKey.hexToByteArray()) }.getOrNull() ?: return
        val seed = runCatching { ByteVector(seedHex) }.getOrNull()?.takeIf { it.size() == 32 } ?: return
        val nodeId = nodeIdOf(seed, chain).value.toHex()
        if (nodeId != message.nodeId) {
            logger.w("A node seed from ${innerEvent.pubKey.take(8)} does not produce the node it claims; dropping")
            return
        }
        database.groupLightningSeedDao().insertIfAbsent(GroupLightningSeed(localChatRoom.chatRoom.id, nodeId, message.chain, message.wallet, seed.toHex(), innerEvent.pubKey))
        line()
    }

    /**
     * Queues one of this device's messages as a Marmot inner event for the room, as the ritual managers do,
     * and returns the row: a message worth a line gets it from here, since this device never reads its own back.
     */
    internal suspend fun queue(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, kind: Int, content: String, tags: Array<Array<String>> = emptyArray()): MarmotInnerEvent {
        val createdAt = Clock.System.now().epochSeconds
        val id = EventHasher.hashId(pubKey = userPublicKey, createdAt = createdAt, kind = kind, tags = tags, content = content)
        val queued = MarmotInnerEvent(id = id, publicKey = userPublicKey, kind = kind, createdAt = Instant.fromEpochSeconds(createdAt), tags = tags, content = content, chatRoomId = localChatRoom.chatRoom.id)
        database.marmotInnerEventDao().upsert(queued)
        return queued
    }

    private const val SEED_SHARED = "gave each key holder the Lightning node's seed, encrypted to each of them."

}
