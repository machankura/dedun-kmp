package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.wallet.PayoutFees
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkCheckpoint
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkChecked
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupArkMark
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkPendingRound
import com.machankura.dedun.compose.database.model.GroupArkRound
import com.machankura.dedun.compose.database.model.GroupArkTreeBinding
import com.machankura.dedun.compose.database.model.GroupArkTreeShare
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergEchoRow
import com.machankura.dedun.compose.database.model.IcebergLabelLedger
import com.machankura.dedun.compose.database.model.IcebergNonceRow
import com.machankura.dedun.compose.database.model.IcebergShareRow
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy
import com.machankura.dedun.compose.wallet.wavelength.group.ArkRequest
import com.machankura.dedun.compose.wallet.wavelength.group.ArkWire
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkDerivation
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkKeys
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkSigner
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.machankura.dedun.compose.wallet.wavelength.group.IcebergChildKeys
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.iceberg.PublicShare
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.crypto.iceberg.SignatureShare
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.utils.Either
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A member's device answering for a group's Wavelength wallet -- `docs/group-wavelength-wallet.md`, Phase 6.
 *
 * The shape is the Lightning co-signer's, on the same tables under its own wallet key and label domain: round one published
 * ahead of need, and round two only after [ArkPolicy] has agreed, and `n-t+1` participants have echoed the same hash for the label,
 * because a coordinator can show three members three messages under one label, each signs once, and a share leaks with no error.
 * Two such quorums overlap in at least `n-2t+2` participants, which `n >= 3t-2` makes `t`, so one of them is honest and never echoes
 * twice. That is why only a deployable group can have this wallet ([GroupWavelengthWalletManager.verify]), and it is what the rule rests
 * on: an echo that disagrees raises the alarm and stops nothing, or any one member could veto every signature by disagreeing.
 *
 * It adds what that co-signer left unbuilt: a device answers nothing until `t` others have said how far they have answered, since a
 * restored phone would otherwise answer a label a second time.
 */
object GroupArkCoSigner {
    private val logger = Logger.withTag("GroupArkCoSigner")
    private val answeringLock = Mutex()

    /**
     * Runs [block] as the one caller answering, with the database's writer already held. The order is the point: a message arriving is
     * filed inside the inbound transaction, which holds the writer and then asks for this lock, so a caller taking the lock first and the
     * writer second -- the operator's own requests, a settled round -- waited on it while it waited on them, and every write in the app
     * stopped (`docs/group-wavelength-ui.md`). Inside a transaction already, this is a nested one on the same connection.
     */
    private suspend fun <T> answering(database: DedunDatabase, block: suspend () -> T): T =
        database.useWriterConnection { transactor -> transactor.immediateTransaction { answeringLock.withLock { block() } } }

    /** When a device last asked for each participant's round one, and last said its own again, by its own key first: under [answering]. */
    private val roundOneAsked = HashMap<String, Instant>()

    private const val LABEL_TAG = "dedun/group-wavelength/label/v1"

    /** How many signatures one tree key has labels for: one per transaction on its path, and a path is short. */
    const val TREE_LABELS = 12

    private const val MAX_PLAIN_BATCH = 128L

    /** How long ago a request can have been for what arrives now to still be worth answering: past the daemon's own 55 seconds, with room for clocks. */
    private val ROUND_ONE_WAIT = 2.minutes
    private const val MAX_TREE_BATCH = 32L

    /** How long the group's fee budget looks back. */
    private const val FEE_WINDOW_SECONDS = 30L * 24 * 60 * 60

    /** No group gets this far, and a mark past it isn't a mark. It keeps a floor from wrapping round to nothing. */
    private const val MAX_MARK = 1L shl 40

    /** How far past what `t` members agree on a mark is still believed: a real group's laggards are this close, and a liar's skip is this cheap. */
    private const val PLAIN_MARK_WINDOW = 4_096L
    private const val TREE_MARK_WINDOW = 256L

    fun walletKey(chatRoomId: String, chain: Chain, wallet: Long): String =
        "wavelength:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    /** Where a tree key's labels are filed: the shared nonce table, under a key of its own. */
    /** The chain and number of [walletKey], if it's one of [chatRoomId]'s wallets. */
    fun parseWalletKey(chatRoomId: String, walletKey: String): Pair<Chain, Long>? {
        val prefix = "wavelength:$chatRoomId:"
        if (!walletKey.startsWith(prefix)) return null
        val parts = walletKey.removePrefix(prefix).split(":")
        val chain = parts.getOrNull(0)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        val number = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return if (parts.size == 2) chain to number else null
    }

    fun treeKey(walletKey: String, child: Long): String = "$walletKey:tree:$child"

    /** The label for [counter] of [domain]: one sequence per wallet, under a tag of its own, so no other wallet's can collide even on one key. */
    fun label(statement: GroupWavelengthWalletStatementEvent.Statement, domain: ArkWire.Domain, counter: Long): ByteVector32 = Crypto.taggedHash(
        ByteVector(statement.icebergKey).toByteArray() + statement.chain.chainHash.value.toByteArray() + Pack.writeInt64BE(statement.wallet) +
            Pack.writeInt64BE(domain.child ?: -1L) + Pack.writeInt64BE(counter),
        LABEL_TAG,
    )

    private fun shareOf(wallet: GroupWavelengthWalletManager.Wallet, domain: ArkWire.Domain, base: Share): Share =
        domain.child?.let { IcebergChildKeys.share(base, it) } ?: base

    internal fun keysOf(wallet: GroupWavelengthWalletManager.Wallet) =
        GroupArkKeys(PublicKey.fromHex(wallet.statement.icebergKey), wallet.statement.chain, wallet.statement.wallet)

    private suspend fun baseShare(database: DedunDatabase, wallet: GroupWavelengthWalletManager.Wallet): Share? =
        database.icebergKeySessionDao().getSessionById(wallet.statement.ceremonyId)?.let(IcebergRitualManager::shareOf)

    private suspend fun walletOf(database: DedunDatabase, chatRoomId: String, key: String): GroupWavelengthWalletManager.Wallet? {
        // This room's and no other's: an operator here says nothing about a wallet in another room this device is also in.
        val (chain, number) = parseWalletKey(chatRoomId, key) ?: return null
        return GroupWavelengthWalletManager.wallets(database, chatRoomId, chain).firstOrNull { it.statement.wallet == number }
    }

    private fun indexOf(wallet: GroupWavelengthWalletManager.Wallet, participant: HexKey): Int? =
        wallet.statement.participants.indexOf(participant.lowercase()).takeIf { it >= 0 }?.plus(1)

    /** Publishes round one for this device's next labels and tree keys, up to what the operator asked for. */
    suspend fun provision(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, plainTo: Long, treeTo: Long) {
        val share = baseShare(database, wallet) ?: return
        val key = walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val me = userPublicKey.lowercase()
        val dao = database.icebergCoSigningDao()
        val arkDao = database.groupArkDao()

        // From the floor, if the group has got past this device: labels below it won't be answered, so they aren't worth anything.
        val floor = arkDao.floor(key)
        val plainFrom = maxOf((dao.highestNonce(key, me) ?: -1L) + 1, floor?.floor ?: 0)
        val plainEnd = minOf(plainTo, plainFrom + MAX_PLAIN_BATCH - 1)
        val plain = (plainFrom..plainEnd).map { counter ->
            GroupArkSigner.contribution(share, label(wallet.statement, ArkWire.Domain(), counter)).toByteArray().toHex()
                .also { dao.insertNonce(IcebergNonceRow(key, counter, me, it)) }
        }

        val treeFrom = maxOf((arkDao.highestTreeShare(key, me) ?: -1L) + 1, floor?.treeFloor ?: 0)
        val treeEnd = minOf(treeTo, treeFrom + MAX_TREE_BATCH - 1)
        val trees = (treeFrom..treeEnd).map { child ->
            val childShare = IcebergChildKeys.share(share, child)
            val publicShare = Iceberg.publicShare(childShare, null).toByteArray().toHex()
            arkDao.insertTreeShare(GroupArkTreeShare(key, child, me, publicShare))
            val domain = ArkWire.Domain(child)
            val nonces = (0L until TREE_LABELS).map { counter ->
                GroupArkSigner.contribution(childShare, label(wallet.statement, domain, counter)).toByteArray().toHex()
                    .also { dao.insertNonce(IcebergNonceRow(treeKey(key, child), counter, me, it)) }
            }
            ArkWire.TreeProvision(child, publicShare, nonces)
        }
        if (plain.isEmpty() && trees.isEmpty()) return
        queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.PROVISION, CommonJson.encodeToString(ArkWire.Provision.serializer(), ArkWire.Provision(key, plainFrom, plain, trees)))
    }

    private suspend fun queue(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, kind: Int, content: String) =
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, kind, content)

    /** Routes one inbound co-signing message. */
    suspend fun process(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event, userPublicKey: HexKey) {
        try {
            when (innerEvent.kind) {
                GroupWavelengthEvents.PROVISION -> fileProvision(database, localChatRoom, innerEvent, userPublicKey)
                GroupWavelengthEvents.SIGNING_REQUEST -> handleBatch(database, localChatRoom, innerEvent, userPublicKey)
                GroupWavelengthEvents.ECHO -> fileEchoes(database, localChatRoom, innerEvent, userPublicKey)
                GroupWavelengthEvents.SIGNATURE_SHARES -> fileShares(database, localChatRoom, innerEvent, userPublicKey)
                GroupWavelengthEvents.HIGH_WATER -> fileHighWater(database, localChatRoom, innerEvent, userPublicKey)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.e("Could not process co-signing kind ${innerEvent.kind}", e)
        }
    }

    private suspend fun fileProvision(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val batch = CommonJson.decodeFromString(ArkWire.Provision.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        if (batch.plain.size > MAX_PLAIN_BATCH || batch.trees.size > MAX_TREE_BATCH) return
        val participant = event.pubKey.lowercase()
        val dao = database.icebergCoSigningDao()
        batch.plain.forEachIndexed { i, c -> dao.insertNonce(IcebergNonceRow(batch.wallet, batch.plainFrom + i, participant, c)) }
        batch.trees.forEach { tree ->
            if (tree.nonces.size > TREE_LABELS) return@forEach
            database.groupArkDao().insertTreeShare(GroupArkTreeShare(batch.wallet, tree.child, participant, tree.publicShare))
            tree.nonces.forEachIndexed { i, c -> dao.insertNonce(IcebergNonceRow(treeKey(batch.wallet, tree.child), i.toLong(), participant, c)) }
        }
        batch.wanted.filter { it.participant == userPublicKey.lowercase() }.forEach { sayAgain(database, localChatRoom, userPublicKey, batch.wallet, it, event.id) }
        // Relays don't keep order, so a request can get here before what it's built from. Only lately's: the daemon has given up on anything older.
        catchUp(database, localChatRoom, wallet, batch.wallet, userPublicKey, since = Clock.System.now() - ROUND_ONE_WAIT)
    }

    /** Participants this device has nothing from, itself aside: whose round one, if they published it, never got here. */
    private fun unheard(wallet: GroupWavelengthWalletManager.Wallet, heard: Collection<String>, userPublicKey: HexKey): List<HexKey> =
        wallet.statement.participants.filter { it != userPublicKey.lowercase() && it !in heard }

    /**
     * Asks for the round one this device is waiting on, from whoever it never heard from. Waiting is right when it's on its way, and
     * forever when MLS lost it, which it does to a message that arrives out of order behind one of this device's own: the author
     * has to say it again, and only the device that's missing it can say so. Each participant once in [ROUND_ONE_WAIT], since an ask
     * can be lost too. A tree key they're missing and someone else isn't is asked for with it: it was likely in the same message.
     */
    private suspend fun askForRoundOne(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, key: String, wanted: List<ArkWire.Wanted>) {
        if (wanted.isEmpty()) return
        val now = Clock.System.now()
        val shares = database.groupArkDao().treeShares(key).groupBy { it.child }
        val asking = wanted.groupBy { it.participant }.mapNotNull { (participant, wants) ->
            val tag = "${userPublicKey.lowercase()}:$key:asked:$participant"
            if (roundOneAsked[tag]?.let { now - it < ROUND_ONE_WAIT } == true) return@mapNotNull null
            roundOneAsked[tag] = now
            val lostTree = shares.filterValues { rows -> rows.none { it.participant == participant } }.keys.minOrNull()
            ArkWire.Wanted(participant, wants.mapNotNull { it.plainFrom }.minOrNull(), (wants.mapNotNull { it.childFrom } + listOfNotNull(lostTree)).minOrNull())
        }
        if (asking.isEmpty()) return
        logger.i("Asking ${asking.joinToString { it.participant.take(8) }} for round one that never arrived")
        queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.PROVISION, CommonJson.encodeToString(ArkWire.Provision.serializer(), ArkWire.Provision(key, 0, emptyList(), emptyList(), asking)))
    }

    /**
     * This device's round one again, as [wanted] asks: what it filed when it first published, read back, so nothing new is said and a
     * device that has it already ignores it. A new message is at a generation the asker can read, which is the whole of the fix. Once
     * in [ROUND_ONE_WAIT] for each window, however many ask.
     */
    private suspend fun sayAgain(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, key: String, wanted: ArkWire.Wanted, asked: String) = answering(database) {
        val now = Clock.System.now()
        val me = userPublicKey.lowercase()
        val tag = "$me:$key:again:${wanted.plainFrom}:${wanted.childFrom}"
        if (roundOneAsked[tag]?.let { now - it < ROUND_ONE_WAIT } == true) return@answering
        roundOneAsked[tag] = now
        val dao = database.icebergCoSigningDao()

        // From the first this device has at or past what was asked, and only as far as there's no gap, since a provision is a run.
        val rows = wanted.plainFrom?.let { dao.noncesOf(key, me, it, it + MAX_PLAIN_BATCH - 1) }.orEmpty()
        val plainFrom = rows.firstOrNull()?.counter ?: 0
        val plain = rows.withIndex().takeWhile { (i, row) -> row.counter == plainFrom + i }.map { it.value.contribution }

        val trees = wanted.childFrom?.let { from ->
            database.groupArkDao().treeShares(key).filter { it.participant == me && it.child >= from }.sortedBy { it.child }.take(MAX_TREE_BATCH.toInt()).mapNotNull { share ->
                val nonces = dao.noncesOf(treeKey(key, share.child), me, 0, TREE_LABELS - 1L).map { it.contribution }
                if (nonces.size == TREE_LABELS) ArkWire.TreeProvision(share.child, share.share, nonces) else null
            }
        }.orEmpty()
        if (plain.isEmpty() && trees.isEmpty()) return@answering
        logger.i("Saying round one again: ${plain.size} labels from $plainFrom, ${trees.size} tree keys")
        queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.PROVISION, CommonJson.encodeToString(ArkWire.Provision.serializer(), ArkWire.Provision(key, plainFrom, plain, trees, answers = asked)))
    }

    private suspend fun fileShares(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val batch = CommonJson.decodeFromString(ArkWire.ShareBatch.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        batch.items.forEach { database.icebergCoSigningDao().insertShare(IcebergShareRow(batch.wallet, it.requestId, event.pubKey.lowercase(), it.share)) }
        settleTransfers(database, localChatRoom, wallet, batch.wallet, userPublicKey)
    }

    /** The tree keys the group has made, from `2t-1` public shares of each: what a VTXO's tree key has to be one of. */
    suspend fun treeKeys(database: DedunDatabase, wallet: GroupWavelengthWalletManager.Wallet, key: String): Map<Long, PublicKey> {
        val need = 2 * wallet.statement.threshold - 1
        val n = wallet.statement.participants.size
        return database.groupArkDao().treeShares(key).groupBy { it.child }.mapNotNull { (child, rows) ->
            if (rows.size < need) return@mapNotNull null
            val shares = rows.map { fr.acinq.bitcoin.crypto.iceberg.PublicShare(ByteVector(it.share)) }
            when (val group = IcebergChildKeys.key(shares, n, wallet.statement.threshold)) {
                is Either.Right -> child to group.value
                is Either.Left -> null
            }
        }.toMap()
    }

    /** The scripts of the group's onchain wallet this device derives, for the policy to check a leave or a sweep against. */
    private suspend fun onchainScripts(database: DedunDatabase, chatRoomId: String, wallet: GroupWavelengthWalletManager.Wallet): Set<ByteVector> {
        val account = GroupWalletManager.walletFor(database, chatRoomId, wallet.statement.chain, wallet.statement.onchainAccount)?.account ?: return emptySet()
        return (0L until ONCHAIN_WINDOW).flatMap { index ->
            listOf(GroupWalletDerivation.RECEIVE, GroupWalletDerivation.CHANGE).map { branch ->
                GroupWalletDerivation.derive(account, branch, index).pubkeyScript
            }
        }.toSet()
    }

    private const val ONCHAIN_WINDOW = 64L

    /** What [ArkPolicy] is told: what the group decided, and what this device remembers. */
    suspend fun contextOf(database: DedunDatabase, chatRoomId: String, wallet: GroupWavelengthWalletManager.Wallet, key: String, term: Long): ArkPolicy.Context {
        val dao = database.groupArkDao()
        val vtxos = dao.vtxos(key)
        val standing = vtxos.filter { !it.spent && (it.settled || it.term == term) }
        val tree = treeKeys(database, wallet, key).values.toSet()
        val onchain = onchainScripts(database, chatRoomId, wallet)
        val statement = wallet.statement
        return ArkPolicy.Context(
            keys = keysOf(wallet),
            operator = PublicKey.fromHex(statement.arkOperatorKey),
            exitDelay = statement.policy.exitDelay,
            boardingDelay = statement.policy.boardingDelay,
            maxRoundFeeSat = statement.policy.maxRoundFeeSat,
            maxOnchainFeeSat = statement.policy.maxOnchainFeeSat,
            isTreeKey = { it in tree },
            isOnchainWallet = { it in onchain },
            knownVtxos = vtxos.filter { it.settled && !it.spent }.associate { it.outpoint to it.amountSat },
            capSat = GroupWavelengthRollout.capFor(statement.chain)?.sat,
            unspentSat = standing.sumOf { it.amountSat },
            pendingFromSat = standing.filter { !it.settled && it.origin != null }.groupBy { it.origin!! }.mapValues { (_, rows) -> rows.sumOf { it.amountSat } },
            reservedFor = vtxos.filter { it.reservedBy != null && it.term == term }.associate { it.outpoint to it.reservedBy!! },
            recentFeesSat = dao.fees(key, term, Clock.System.now().epochSeconds - FEE_WINDOW_SECONDS).associate { it.tx to it.feeSat },
            maxRecentFeesSat = statement.policy.maxMonthlyFeeSat,
            approvedPayments = GroupWavelengthPayments.approved(database, chatRoomId, wallet),
            paidIn = dao.payments(key).associate { ByteVector32(it.paymentHash) to it.tx },
            authorisedLeaves = GroupWavelengthPayouts.approvedLeaves(database, chatRoomId, wallet),
            leftIn = dao.leaves(key).associate { it.authorisation to it.commitment },
            notedRounds = dao.rounds(key).associate { it.commitment to it.noteHash },
            treeKeyRounds = dao.treeBindings(key).associate { it.treeKey to it.commitment },
            minSweepDelay = statement.policy.minSweepDelay,
        )
    }

    /**
     * What this device has seen [origin], a commitment or an Ark transaction, take effect: the VTXOs it made are the group's to spend
     * now, and the ones it replaced are gone. What calls this has to have seen that for itself, on the chain or from the operator's
     * own signature, and never because the wallet's operator says so: [GroupArkWatcher][com.machankura.dedun.compose.wallet.wavelength.group.GroupArkWatcher]
     * for a round, and [settleTransfers] for a transfer.
     */
    suspend fun settled(database: DedunDatabase, key: String, origin: String) {
        database.groupArkDao().settle(key, origin)
        database.groupArkDao().spendReservedBy(key, origin)
        GroupWavelengthDeposits.claimSettled(database, key, origin)
    }

    /**
     * What this device has seen prove that [commitment] will never confirm, a conflict in a block. Its forfeits can't count, since the
     * connectors they spend don't exist, so what it reserved is free again, and what it would have made and cost never happened. A tree
     * key stays spent on it: that binding is for good, whatever became of the round.
     */
    suspend fun abandoned(database: DedunDatabase, key: String, commitment: String) {
        val dao = database.groupArkDao()
        dao.releaseReservedBy(key, commitment)
        dao.forgetPendingFrom(key, commitment)
        dao.forgetFee(key, commitment)
        dao.forgetBoarded(key, commitment)
        // A payout it would have made didn't leave, so its approval can be paid in another round while it stands.
        dao.forgetLeaves(key, commitment)
        dao.deletePendingRound(key, commitment)
        val roomId = key.split(":").getOrNull(1) ?: return
        val reader = database.chatRoomDao().findChatRoomById(roomId)?.chatRoom?.userPublicKey ?: return
        GroupLightningLines.writeFact(
            database, roomId, ChatMessage.TYPE_WAVELENGTH_ROUND_DIED, commitment, reader,
            "A round of the Wavelength wallet's didn't happen, so nothing it would have done has been done. Your device let go of the coins it had set aside for it.",
            Clock.System.now(),
        )
    }

    /** A round this device has now seen confirm at [height], and whatever it refused for not having seen that yet. */
    suspend fun roundSettled(database: DedunDatabase, row: GroupArkPendingRound, height: Long? = null) {
        height?.let { database.groupArkDao().expireFrom(row.wallet, row.commitment, it) }
        settled(database, row.wallet, row.commitment)
        paidOut(database, row)
        GroupWavelengthDeposits.roundHappened(database, row.chatRoomId, row.chain, row.wallet, row.commitment)
        database.groupArkDao().deletePendingRound(row.wallet, row.commitment)
        val localChatRoom = database.chatRoomDao().findChatRoomById(row.chatRoomId) ?: return
        val wallet = walletOf(database, row.chatRoomId, row.wallet) ?: return
        val userPublicKey = localChatRoom.chatRoom.userPublicKey ?: return
        catchUp(database, localChatRoom, wallet, row.wallet, userPublicKey)
        settleTransfers(database, localChatRoom, wallet, row.wallet, userPublicKey)
    }

    /** The onchain payouts [row]'s round made, confirmed with it, and said once in the room by each device that saw it. */
    private suspend fun paidOut(database: DedunDatabase, row: GroupArkPendingRound) {
        val dao = database.groupArkDao()
        val paid = dao.leaves(row.wallet).filter { it.commitment == row.commitment && !it.confirmed }
        if (paid.isEmpty()) return
        dao.confirmLeaves(row.wallet, row.commitment, Clock.System.now().epochSeconds)
        val reader = database.chatRoomDao().findChatRoomById(row.chatRoomId)?.chatRoom?.userPublicKey ?: return
        val chain = GroupWalletStatementEvent.chainNamed(row.chain) ?: return
        val payouts = GroupWavelengthPayouts.signedOnchain(database, row.chatRoomId, chain).associateBy { it.id }
        paid.forEach { leave ->
            val member = payouts[leave.authorisation]?.member ?: return@forEach
            GroupLightningLines.writeFact(
                database, row.chatRoomId, ChatMessage.TYPE_WAVELENGTH_PAID_OUT, leave.authorisation, reader,
                "The Wavelength wallet paid @${ChatMessage.memberName(database, member)} ${leave.amountSat} sats onchain, with ${leave.feeSat} sats in fees from their balance.",
                Clock.System.now(),
            )
        }
    }

    /**
     * Settles every transfer whose checkpoints all have `t` good shares. The operator's co-signature on the Ark transaction was checked
     * before this device gave its own share of a checkpoint, and a checkpoint with the group's whole signature hands the coin on: the
     * Ark transaction and it can now be published with nobody's further help. Whoever runs the daemon can still sit on them instead of
     * telling the Ark server, which leaves this device's record ahead of the server's until an election: the shares are all in the room,
     * so the next operator can finish it. That costs time and no coin.
     */
    private suspend fun settleTransfers(database: DedunDatabase, localChatRoom: LocalChatRoom, wallet: GroupWavelengthWalletManager.Wallet, key: String, userPublicKey: HexKey) {
        val dao = database.groupArkDao()
        while (true) {
            val done = dao.checkpoints(key).groupBy { it.ark }.filterValues { rows -> rows.all { complete(database, wallet, key, it.label) } }.keys
            if (done.isEmpty()) return
            done.forEach { ark ->
                settled(database, key, ark)
                dao.deleteCheckpoints(key, ark)
            }
            catchUp(database, localChatRoom, wallet, key, userPublicKey)
        }
    }

    /** Whether the signature this device gave a share of under [label] now has `t` shares that each verify, so a member can't fake it. */
    private suspend fun complete(database: DedunDatabase, wallet: GroupWavelengthWalletManager.Wallet, key: String, label: String?): Boolean {
        val checked = database.groupArkDao().checked(key, label ?: return false) ?: return false
        val item = CommonJson.decodeFromString(ArkWire.SigningItem.serializer(), checked.item)
        val cache = cacheOf(wallet, item.request) ?: return false
        val publicShares = publicSharesOf(database, wallet, key, item.domain)
        val contributions = item.contributions.map { NonceContribution(it) }
        val cosigners = item.cosignerNonce?.let { AggregatedNonce(ByteVector(it)) } ?: GroupArkSigner.NO_COSIGNERS
        val groupKey = PublicKey.fromHex(wallet.statement.icebergKey)
        val n = wallet.statement.participants.size
        val t = wallet.statement.threshold
        val good = database.icebergCoSigningDao().sharesFor(key, item.requestId).count { row ->
            val publicShare = publicShares[row.participant] ?: return@count false
            runCatching { Iceberg.verifyPartialSignature(SignatureShare(row.share), publicShare, contributions, n, t, groupKey, cache, ByteVector32(checked.message), cosigners) }.getOrDefault(false)
        }
        return good >= t
    }

    /** The public shares a signature's shares are checked against, by participant. */
    suspend fun publicSharesOf(database: DedunDatabase, wallet: GroupWavelengthWalletManager.Wallet, key: String, domain: ArkWire.Domain): Map<String, PublicShare> {
        val child = domain.child ?: return database.icebergKeySessionDao().getSessionById(wallet.statement.ceremonyId)?.publicShareList().orEmpty()
            .mapIndexedNotNull { i, share -> wallet.statement.participants.getOrNull(i)?.let { it to PublicShare(ByteVector(share)) } }.toMap()
        return database.groupArkDao().treeShares(key).filter { it.child == child }.associate { it.participant to PublicShare(ByteVector(it.share)) }
    }

    /**
     * Answers what the current operator asked this term and this device has no answer to, once it knows more than it did: a device a
     * block behind the others refuses a coin it hasn't seen settle, and if it stayed refused it would never learn what that spend made,
     * and go on refusing everything after. Every check is made again, so the worst this does is agree to a request late. It says nothing
     * about what it still refuses, having said so the first time.
     */
    private suspend fun catchUp(database: DedunDatabase, localChatRoom: LocalChatRoom, wallet: GroupWavelengthWalletManager.Wallet, key: String, userPublicKey: HexKey, since: Instant? = null) {
        if (userPublicKey.lowercase() !in wallet.statement.participants) return
        val roomId = localChatRoom.chatRoom.id
        val operator = GroupWavelengthOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet) ?: return
        val publicKey = operator.publicKey ?: return
        val unanswered = requestsOf(database, roomId, key, publicKey, operator.term, since)
            .filter { database.icebergCoSigningDao().ledgerFor(key, label(wallet.statement, it.domain, it.counter).toHex()) == null }
        if (unanswered.isEmpty()) return
        val floor = floorOf(database, localChatRoom, key, userPublicKey, wallet) ?: return
        answerAll(database, localChatRoom, wallet, key, operator.term, unanswered, floor, userPublicKey, publicKey, sayRefused = false)
    }

    /** Answers [items], one caller at a time: a label is looked up and then filed, and two at once could each find it free. */
    private suspend fun answerAll(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        wallet: GroupWavelengthWalletManager.Wallet,
        key: String,
        term: Long,
        items: List<ArkWire.SigningItem>,
        floor: GroupArkFloor,
        userPublicKey: HexKey,
        operator: HexKey,
        sayRefused: Boolean,
    ) = answering(database) {
        val roomId = localChatRoom.chatRoom.id
        val echoes = mutableListOf<ArkWire.EchoItem>()
        val wanted = mutableListOf<ArkWire.Wanted>()
        for (item in items) {
            val refusal = answer(database, roomId, wallet, key, term, item, floor, userPublicKey, operator, echoes, wanted)
            if (refusal != null && sayRefused) sayRefused(database, roomId, item.requestId, userPublicKey, refusal)
        }
        if (echoes.isNotEmpty()) {
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.ECHO, CommonJson.encodeToString(ArkWire.EchoBatch.serializer(), ArkWire.EchoBatch(key, echoes)))
        }
        askForRoundOne(database, localChatRoom, userPublicKey, key, wanted)
        maybeSign(database, localChatRoom, wallet, key, items.map { label(wallet.statement, it.domain, it.counter).toHex() }, userPublicKey)
    }

    private suspend fun handleBatch(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val batch = CommonJson.decodeFromString(ArkWire.SigningBatch.serializer(), event.content)
        val roomId = localChatRoom.chatRoom.id
        val wallet = walletOf(database, roomId, batch.wallet) ?: return
        if (userPublicKey.lowercase() !in wallet.statement.participants) return

        // The current operator, in the current term: the fence that holds whatever an old daemon does.
        val operator = GroupWavelengthOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet)
        if (operator?.publicKey != event.pubKey.lowercase() || operator.term != batch.term) {
            logger.w("Refusing a batch from ${event.pubKey.take(8)}: not the operator of term ${operator?.term}")
            return
        }

        if (batch.provisionPlainTo != null || batch.provisionTreeTo != null) {
            provision(database, localChatRoom, userPublicKey, wallet, batch.provisionPlainTo ?: -1, batch.provisionTreeTo ?: -1)
        }
        // Asked ahead of the first signature, which is inside a round's deadline.
        if (batch.askFloor) floorOf(database, localChatRoom, batch.wallet, userPublicKey, wallet)
        if (batch.items.isEmpty()) return

        // A device that doesn't yet know how far the group has answered asks, and answers nothing until it knows.
        val floor = floorOf(database, localChatRoom, batch.wallet, userPublicKey, wallet) ?: return
        answerAll(database, localChatRoom, wallet, batch.wallet, batch.term, batch.items, floor, userPublicKey, event.pubKey, sayRefused = true)
        settleTransfers(database, localChatRoom, wallet, batch.wallet, userPublicKey)
    }

    /** A person never sees what their device signs, so a refusal is the one thing about this they are told, and only on this device. */
    private suspend fun sayRefused(database: DedunDatabase, roomId: String, requestId: String, userPublicKey: HexKey, refusal: String) {
        logger.w("Refusing request $requestId: $refusal")
        GroupLightningLines.writeFact(
            database, roomId, ChatMessage.TYPE_WAVELENGTH_REFUSED, requestId, userPublicKey,
            "Your device refused to co-sign for the Wavelength wallet: $refusal.", Clock.System.now(),
        )
    }

    /**
     * What members echo for a label: not only the message but everything that goes into the challenge, since a share signed under one
     * label for two different challenges leaks like one signed for two messages. The operator picks the cosigners' nonce and the set
     * of contributions, so those are in it, and so is the key the signature is for.
     */
    fun bindingOf(item: ArkWire.SigningItem, label: String, message: String): String {
        val request = item.request
        val key = if (item.domain.child != null) "tree:${item.domain.child}:${request.signers.sorted()}:${request.scriptRoot}" else "plain:${request.kind}:${request.family}:${request.branch}:${request.index}:${request.method}"
        return Crypto.taggedHash("$label|$message|${item.cosignerNonce}|${item.contributions}|$key".encodeToByteArray(), BINDING_TAG).toHex()
    }

    private const val BINDING_TAG = "dedun/group-wavelength/echo/v1"

    /**
     * Checks one item, commits to it under its label for good, and notes an echo to send. Null when it may go on. What it's waiting on
     * goes in [wanted], to be asked for.
     */
    private suspend fun answer(
        database: DedunDatabase,
        roomId: String,
        wallet: GroupWavelengthWalletManager.Wallet,
        key: String,
        term: Long,
        item: ArkWire.SigningItem,
        floor: GroupArkFloor,
        userPublicKey: HexKey,
        operator: HexKey,
        echoes: MutableList<ArkWire.EchoItem>,
        wanted: MutableList<ArkWire.Wanted>,
    ): String? {
        val label = label(wallet.statement, item.domain, item.counter).toHex()
        val child = item.domain.child
        if (child == null && item.counter < (floor.floor ?: 0)) return "a label from before this device last knew how far the group had answered"
        if (child != null && child < (floor.treeFloor ?: 0)) return "a tree key from before this device last knew how far the group had answered"
        if (child != null && item.counter !in 0 until TREE_LABELS) return "a label a tree key doesn't have"

        // Not yet isn't no. The members' shares of a tree key are on their way, and nothing has been said or signed in the meantime.
        val sessionKey = child?.let {
            treeKeys(database, wallet, key)[it] ?: run {
                val heard = database.groupArkDao().treeShares(key).filter { share -> share.child == it }.map { share -> share.participant }
                unheard(wallet, heard, userPublicKey).forEach { participant -> wanted += ArkWire.Wanted(participant, childFrom = it) }
                return null
            }
        }
        val verdict = ArkPolicy.check(contextOf(database, roomId, wallet, key, term), item.request, sessionKey)
        val allowed = when (verdict) {
            is ArkPolicy.Verdict.Refuse -> return verdict.reason
            is ArkPolicy.Verdict.Allow -> verdict
        }
        // A plain label signs a plain request and a tree label a tree one, or the share under one signs for the other.
        if ((child != null) != (allowed.what == ArkPolicy.What.TREE)) return "a label for another kind of signature"

        // The nonces the challenge is built from are what members themselves published, not what the operator says they published.
        val round = database.icebergCoSigningDao().noncesFor(child?.let { treeKey(key, it) } ?: key, item.counter)
        val seen = round.map { it.contribution }.toSet()
        val enough = item.contributions.size >= 2 * wallet.statement.threshold - 1 && item.contributions.toSet().size == item.contributions.size
        if (!enough) return "too little of round one to sign from"
        // Waits rather than refuses, and [fileProvision] comes back to it. An operator naming nonces nobody published only ever gets this
        // silence, and asks nobody: a device that has heard from everyone has nobody to ask.
        if (!seen.containsAll(item.contributions)) {
            unheard(wallet, round.map { it.participant }, userPublicKey).forEach { participant ->
                wanted += if (child == null) ArkWire.Wanted(participant, plainFrom = item.counter) else ArkWire.Wanted(participant, childFrom = child)
            }
            return null
        }

        val message = allowed.message.toHex()
        val binding = bindingOf(item, label, message)
        val dao = database.icebergCoSigningDao()
        val existing = dao.ledgerFor(key, label)
        if (existing != null && existing.messageHash != binding) {
            raiseEquivocation(database, roomId, label, operator)
            return "a label already echoed for something else"
        }
        allowed.effect?.let { remember(database, roomId, wallet, key, term, it, sessionKey, label) }
        if (existing == null) {
            database.groupArkDao().insertChecked(GroupArkChecked(key, label, child, item.counter, CommonJson.encodeToString(ArkWire.SigningItem.serializer(), item), message))
            dao.insertLedger(IcebergLabelLedger(key, label, item.requestId, binding))
            dao.insertEcho(IcebergEchoRow(key, label, userPublicKey.lowercase(), binding))
            echoes += ArkWire.EchoItem(item.requestId, label, binding)
        }
        return null
    }

    /** What signing this means for this device's own record. Written before the echo, so it is never behind one. */
    private suspend fun remember(database: DedunDatabase, roomId: String, wallet: GroupWavelengthWalletManager.Wallet, key: String, term: Long, effect: ArkPolicy.Effect, sessionKey: PublicKey?, label: String) {
        val dao = database.groupArkDao()
        // One moment for everything this signature files, so a history reads them in the order they happened
        // (`docs/group-wavelength-transaction-details.md`, T3).
        val at = Clock.System.now().epochSeconds
        effect.commitmentTx?.let { dao.upsertPendingRound(GroupArkPendingRound(key, effect.towards, roomId, GroupWalletStatementEvent.chainName(wallet.statement.chain), it.toHex())) }
        effect.checkpoints.forEach { dao.insertCheckpoint(GroupArkCheckpoint(key, effect.towards, it)) }
        effect.signing?.let { dao.signedCheckpoint(key, effect.towards, it, label) }
        effect.noteHash?.let { dao.upsertRound(GroupArkRound(key, effect.towards, it, at)) }
        effect.payments.forEach { hash ->
            val funded = effect.funded.firstOrNull { it.paymentHash == hash }
            dao.insertPayment(GroupArkPayment(key, hash.toHex(), effect.towards, funded?.amountSat, funded?.script?.toHex(), atEpochSeconds = at))
        }
        effect.refunded?.let { dao.refundPayment(key, it.toHex(), effect.towards) }
        // A round's fee once, shared by the payouts in it in proportion, as the onchain wallet's payouts share theirs (payouts-only D5).
        val feeShares = if (effect.leaves.isEmpty()) emptyMap() else PayoutFees.split(effect.leaves.associate { it.authorisation to it.amountSat }, effect.feeSat)
        effect.leaves.forEach { dao.upsertLeave(GroupArkLeave(key, it.authorisation, effect.towards, it.script.toHex(), it.amountSat, feeShares[it.authorisation] ?: 0, atEpochSeconds = at)) }
        if (effect.feeSat > 0) dao.upsertFee(GroupArkFee(key, effect.towards, effect.feeSat, term, at))
        sessionKey?.let { dao.upsertTreeBinding(GroupArkTreeBinding(key, it.toString(), effect.towards)) }
        val known = dao.vtxos(key).associateBy { it.outpoint }
        effect.reserves.forEach { outpoint -> known[outpoint]?.let { dao.upsertVtxo(it.copy(reservedBy = effect.towards, term = term)) } }
        effect.claimedHash?.let { dao.upsertClaim(GroupArkClaim(key, effect.towards, it.toHex(), effect.claimedSat, at)) }
        effect.boarded.forEach { dao.upsertBoarded(GroupArkBoarded(key, it.outpoint, effect.towards, it.amountSat, it.script.toHex(), at)) }
        // A credit never overwrites: what this device has already seen settle isn't for a note to change.
        // A transfer makes coins in the batches it spent from, so they go no later than the soonest of those.
        // And if this device can't say for one of them, it can't say.
        val inherited = effect.reserves.map { known[it]?.expiresAtHeight }.takeIf { it.isNotEmpty() && null !in it }?.filterNotNull()?.min()
        effect.credits.forEach { (outpoint, amount) ->
            if (outpoint !in known) dao.upsertVtxo(GroupArkVtxo(key, outpoint, amount, origin = effect.towards, term = term, sweepDelay = effect.sweepDelay, expiresAtHeight = inherited))
        }
    }

    private suspend fun fileEchoes(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val batch = CommonJson.decodeFromString(ArkWire.EchoBatch.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        batch.items.forEach { database.icebergCoSigningDao().insertEcho(IcebergEchoRow(batch.wallet, it.label, event.pubKey.lowercase(), it.messageHash)) }
        answering(database) { maybeSign(database, localChatRoom, wallet, batch.wallet, batch.items.map { it.label }, userPublicKey) }
        settleTransfers(database, localChatRoom, wallet, batch.wallet, userPublicKey)
    }

    /** The alarm the echo rule exists to raise: two challenges under one label. Said once per label, on every device that sees it. */
    private suspend fun raiseEquivocation(database: DedunDatabase, chatRoomId: String, label: String, operator: HexKey) =
        GroupLightningLines.writeFact(
            database, chatRoomId, ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION, label, operator,
            "Devices in this group disagree about what the Wavelength wallet's operator asked them to sign. The operator or a member is misbehaving. Nothing is signed unless enough devices agree.",
            Clock.System.now(),
        )

    /** What the current operator has asked this wallet for in its term: a request from anyone else, or from before, is nothing now. */
    private suspend fun requestsOf(database: DedunDatabase, chatRoomId: String, key: String, operator: HexKey, term: Long, since: Instant? = null): List<ArkWire.SigningItem> =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupWavelengthEvents.SIGNING_REQUEST))
            .filter { it.publicKey.lowercase() == operator && (since == null || it.createdAt >= since) }
            .mapNotNull { runCatching { CommonJson.decodeFromString(ArkWire.SigningBatch.serializer(), it.content) }.getOrNull() }
            .filter { it.wallet == key && it.term == term }
            .flatMap { it.items }

    /**
     * Answers round two for each of [labels] once enough participants have echoed this device's hash, and nobody another. It signs
     * from what it checked and nothing newer: the same label with any other nonces or cosigners would be a second challenge.
     */
    private suspend fun maybeSign(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        wallet: GroupWavelengthWalletManager.Wallet,
        key: String,
        labels: List<String>,
        userPublicKey: HexKey,
    ) {
        val dao = database.icebergCoSigningDao()
        val roomId = localChatRoom.chatRoom.id
        val shares = mutableListOf<ArkWire.ShareItem>()
        val base = baseShare(database, wallet) ?: return
        val n = wallet.statement.participants.size
        val t = wallet.statement.threshold
        for (label in labels.distinct()) {
            val mine = dao.ledgerFor(key, label) ?: continue
            if (mine.signedAt != null) continue
            val echoes = dao.echoesFor(key, label)
            if (echoes.any { it.messageHash != mine.messageHash }) {
                val operator = GroupWavelengthOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet)
                raiseEquivocation(database, roomId, label, operator?.publicKey ?: userPublicKey)
            }
            if (echoes.filter { it.messageHash == mine.messageHash }.map { it.participant }.toSet().size < n - t + 1) continue

            val checked = database.groupArkDao().checked(key, label) ?: continue
            try {
                val item = CommonJson.decodeFromString(ArkWire.SigningItem.serializer(), checked.item)
                val share = shareOf(wallet, item.domain, base)
                val groupKey = item.domain.child?.let { treeKeys(database, wallet, key)[it] ?: continue } ?: PublicKey.fromHex(wallet.statement.icebergKey)
                val cache = cacheOf(wallet, item.request) ?: continue
                val cosigners = item.cosignerNonce?.let { AggregatedNonce(ByteVector(it)) } ?: GroupArkSigner.NO_COSIGNERS
                val signature = Iceberg.partialSign(share, null, ByteVector32(label), item.contributions.map { NonceContribution(it) }, groupKey, cache, ByteVector32(checked.message), cosigners)
                when (signature) {
                    is Either.Left -> logger.e("Could not sign under ${label.take(16)}", signature.value)
                    is Either.Right -> {
                        val hex = signature.value.toByteArray().toHex()
                        dao.upsertLedger(mine.copy(signedAt = Clock.System.now()))
                        dao.insertShare(IcebergShareRow(key, mine.requestId, userPublicKey.lowercase(), hex))
                        shares += ArkWire.ShareItem(mine.requestId, label, hex)
                    }
                }
            } catch (e: IllegalArgumentException) {
                // One the operator wrote badly costs that label, not everything else in the batch.
                logger.e("Could not sign under ${label.take(16)}", e)
            }
        }
        if (shares.isNotEmpty()) {
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.SIGNATURE_SHARES, CommonJson.encodeToString(ArkWire.ShareBatch.serializer(), ArkWire.ShareBatch(key, shares)))
        }
    }

    /** The aggregation a request signs under, rebuilt here: for a key below a slot, or a tree node's whole set of cosigners. */
    fun cacheOf(wallet: GroupWavelengthWalletManager.Wallet, request: ArkRequest): KeyAggCache? {
        val keys = keysOf(wallet)
        return when (request.kind) {
            ArkRequest.MUSIG2_SIGN -> GroupArkSigner.treeCache(request.signers.map { PublicKey.fromHex(it) }, ByteVector32(request.scriptRoot ?: return null)).first
            ArkRequest.INPUT, ArkRequest.MESSAGE -> {
                val slot = keys.slotOf(request.family) ?: return null
                val child = keys.child(slot, request.branch, request.index)
                if (request.method == ArkRequest.METHOD_BIP86) GroupArkDerivation.bip86Cache(child) else child.cache
            }
            else -> null
        }
    }

    // ---- how far has the group answered ----

    /** How far this device may answer from, or null when it has asked and doesn't know yet. */
    private suspend fun floorOf(database: DedunDatabase, localChatRoom: LocalChatRoom, key: String, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): GroupArkFloor? {
        val dao = database.groupArkDao()
        val row = dao.floor(key)
        if (row?.floor != null) return row
        if (row == null) {
            dao.upsertFloor(GroupArkFloor(key))
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), ArkWire.HighWater(key, asking = true, highest = -1)))
        }
        return settleFloor(database, key, userPublicKey, wallet)
    }

    private suspend fun settleFloor(database: DedunDatabase, key: String, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): GroupArkFloor? {
        val dao = database.groupArkDao()
        val marks = dao.marks(key).filter { it.participant != userPublicKey.lowercase() }
        val t = wallet.statement.threshold
        if (marks.size < t) return null
        return GroupArkFloor(key, floorFrom(marks.map { it.highest }, t, PLAIN_MARK_WINDOW), floorFrom(marks.map { it.highestChild }, t, TREE_MARK_WINDOW)).also { dao.upsertFloor(it) }
    }

    /**
     * The first counter past what anybody has answered: the highest mark, since one honest member who echoed is enough to protect a label,
     * but only near what `t` of them say. Of those at least one is honest, so a member lying upward can skip the group ahead by a window at
     * most, which costs some labels, and not out of reach, which would cost the wallet.
     */
    fun floorFrom(marks: List<Long>, t: Int, window: Long): Long {
        val agreed = marks.sortedDescending()[t - 1]
        return marks.filter { it <= agreed + window }.max() + 1
    }

    private suspend fun fileHighWater(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val message = CommonJson.decodeFromString(ArkWire.HighWater.serializer(), event.content)
        val roomId = localChatRoom.chatRoom.id
        val wallet = walletOf(database, roomId, message.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null || event.pubKey.lowercase() == userPublicKey.lowercase()) return
        val dao = database.groupArkDao()
        if (message.asking) {
            // How far this device has checked, which is further than it has signed: a label it echoed is already spoken for.
            val answer = ArkWire.HighWater(message.wallet, asking = false, highest = dao.highestPlainChecked(message.wallet) ?: -1, highestChild = dao.highestChildChecked(message.wallet) ?: -1, answers = event.id)
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), answer))
            return
        }
        // Only an answer: a device that isn't asking has nothing to learn, and nothing a member says unasked should wait for its next restore.
        val row = dao.floor(message.wallet)
        if (row == null || row.floor != null) return
        if (message.highest !in -1..MAX_MARK || message.highestChild !in -1..MAX_MARK) return
        dao.upsertMark(GroupArkMark(message.wallet, event.pubKey.lowercase(), message.highest, message.highestChild))
        val floor = settleFloor(database, message.wallet, userPublicKey, wallet) ?: return
        retryPending(database, localChatRoom, wallet, message.wallet, floor, userPublicKey)
    }

    /** Answers what arrived while this device was still asking how far the group had got. */
    private suspend fun retryPending(database: DedunDatabase, localChatRoom: LocalChatRoom, wallet: GroupWavelengthWalletManager.Wallet, key: String, floor: GroupArkFloor, userPublicKey: HexKey) {
        val roomId = localChatRoom.chatRoom.id
        val operator = GroupWavelengthOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet) ?: return
        val echoes = mutableListOf<ArkWire.EchoItem>()
        val wanted = mutableListOf<ArkWire.Wanted>()
        val publicKey = operator.publicKey ?: return
        val pending = requestsOf(database, roomId, key, publicKey, operator.term)
        pending.forEach { item ->
            val refusal = answer(database, roomId, wallet, key, operator.term, item, floor, userPublicKey, publicKey, echoes, wanted)
            if (refusal != null) sayRefused(database, roomId, item.requestId, userPublicKey, refusal)
        }
        if (echoes.isNotEmpty()) {
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.ECHO, CommonJson.encodeToString(ArkWire.EchoBatch.serializer(), ArkWire.EchoBatch(key, echoes)))
        }
        answering(database) { askForRoundOne(database, localChatRoom, userPublicKey, key, wanted) }
        maybeSign(database, localChatRoom, wallet, key, pending.map { label(wallet.statement, it.domain, it.counter).toHex() }, userPublicKey)
    }
}
