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

    /** Signers of [wallet] who have left the room, and still hold a share and its seed: Iceberg has no resharing, so only a new wallet leaves them out. */
    suspend fun departed(database: DedunDatabase, wallet: Wallet): List<HexKey> {
        val inRoom = database.participantDao().findParticipantsByChatRoomId(wallet.signedEvent.chatRoomId).filter { it.deletedAt == null }.map { it.participantPublicKey.lowercase() }.toSet()
        return wallet.statement.participants.filter { it !in inRoom }
    }

    /** A completed Wavelength key in [chatRoomId] that this device holds a share of and no wallet uses yet. */
    suspend fun unusedKey(database: DedunDatabase, chatRoomId: String): IcebergKeySession? {
        val used = database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthWalletStatementEvent.KIND)
            .mapNotNull { GroupWavelengthWalletStatementEvent.read(it.tags)?.ceremonyId }.toSet()
        return database.icebergKeySessionDao().getSessionsForChatRoom(chatRoomId)
            .firstOrNull { it.purpose == IcebergKeySession.PURPOSE_WAVELENGTH && it.stage == IcebergRitualStage.COMPLETE && it.share != null && it.id !in used }
    }

    /**
     * Whether [signed] is a statement for [chatRoomId] this device can stand behind: the room signed it on a chain this
     * build allows, for a group shape the echo rule holds in, the onchain
     * wallet it leaves to is open, its participants hold the onchain key, its Iceberg key is the one this device's ceremony
     * made for this purpose, and the authentication seed this device holds makes the identity and manifest it names. A
     * reader who is not a participant checks everything but the last two. Null otherwise, with the reason in the log.
     */
    suspend fun verify(database: DedunDatabase, chatRoomId: String, signed: GroupSignedEvent): Wallet? =
        when (val verdict = checked(database, chatRoomId, signed)) {
            is Verdict.Believed -> verdict.wallet
            is Verdict.Refused -> {
                logger.w("Wavelength wallet ${signed.id} refused: ${verdict.why}")
                null
            }
        }

    /** Why a device doesn't stand behind a statement, for the screen that has to say so. */
    enum class Refusal {
        /** Not signed by the room, or not a statement anybody can read. */
        UNREADABLE,
        CHAIN_REFUSED,
        NO_GROUP_KEY,
        SIZE_NOT_DEPLOYABLE,
        NO_ONCHAIN_WALLET,
        NOT_KEY_HOLDERS,
        NO_CEREMONY,
        CEREMONY_DISAGREES,
        /** Nothing is wrong yet: the seed the opener sent is still on its way to this device. */
        WAITING_FOR_SEED,
        KEYS_DISAGREE,
    }

    sealed interface Verdict {
        data class Believed(val wallet: Wallet) : Verdict
        data class Refused(val why: Refusal, val statement: GroupWavelengthWalletStatementEvent.Statement? = null) : Verdict
    }

    /** [verify], saying why. */
    suspend fun checked(database: DedunDatabase, chatRoomId: String, signed: GroupSignedEvent): Verdict {
        if (signed.kind != GroupWavelengthWalletStatementEvent.KIND || !signed.verifies()) return Verdict.Refused(Refusal.UNREADABLE)
        val statement = GroupWavelengthWalletStatementEvent.read(signed.tags) ?: return Verdict.Refused(Refusal.UNREADABLE)
        fun refused(why: Refusal) = Verdict.Refused(why, statement)
        // Not only what this device offers: a statement it doesn't believe is a wallet it has nothing to co-sign for.
        if (!GroupWavelengthRollout.allows(statement.chain)) return refused(Refusal.CHAIN_REFUSED)
        GroupWalletManager.thresholdKeyFor(database, chatRoomId) ?: return refused(Refusal.NO_GROUP_KEY)
        // The echo rule only holds for a group of this shape: see [GroupArkCoSigner].
        if (!IcebergDkg.isDeployable(statement.participants.size, statement.threshold)) return refused(Refusal.SIZE_NOT_DEPLOYABLE)
        if (GroupWalletManager.walletFor(database, chatRoomId, statement.chain, statement.onchainAccount) == null) return refused(Refusal.NO_ONCHAIN_WALLET)
        FrostSigningManager.completedKey(database, chatRoomId)?.participantPublicKeySet()?.let { holders ->
            if (!holders.map { it.lowercase() }.containsAll(statement.participants)) return refused(Refusal.NOT_KEY_HOLDERS)
        }

        val ceremony = database.icebergKeySessionDao().getSessionById(statement.ceremonyId)
        val user = ceremony?.userPublicKey ?: database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey
        val isParticipant = user != null && user.lowercase() in statement.participants
        if (ceremony == null && isParticipant) return refused(Refusal.NO_CEREMONY)
        if (ceremony != null) {
            val agrees = ceremony.purpose == IcebergKeySession.PURPOSE_WAVELENGTH &&
                ceremony.stage == IcebergRitualStage.COMPLETE &&
                ceremony.groupPublicKey == statement.icebergKey &&
                ceremony.threshold == statement.threshold &&
                ceremony.participants() == statement.participants
            if (!agrees) return refused(Refusal.CEREMONY_DISAGREES)
        }

        if (isParticipant) {
            val seed = database.groupWavelengthSeedDao().get(chatRoomId, statement.identity) ?: return refused(Refusal.WAITING_FOR_SEED)
            val (identity, manifest) = bindingOf(PublicKey.fromHex(statement.icebergKey), ByteVector32(seed.seed), statement.chain, statement.wallet)
            if (identity != statement.identity || manifest != statement.manifestHash) return refused(Refusal.KEYS_DISAGREE)
        }
        return Verdict.Believed(Wallet(signed, statement))
    }

    /**
     * Why [chatRoomId] seems to have a wallet on [chain] that this device doesn't believe, or null when it believes them all or
     * there are none. A screen has to tell a member waiting for a seed from a group with no wallet.
     */
    suspend fun refusalOn(database: DedunDatabase, chatRoomId: String, chain: Chain): Refusal? =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthWalletStatementEvent.KIND)
            .sortedWith(compareByDescending<GroupSignedEvent> { it.createdAt }.thenBy { it.id })
            .map { checked(database, chatRoomId, it) }
            .firstOrNull { (it as? Verdict.Refused)?.statement?.chain == chain || (it as? Verdict.Believed)?.wallet?.statement?.chain == chain }
            .let { (it as? Verdict.Refused)?.why }

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
