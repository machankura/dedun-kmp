package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupArkMark
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
import com.machankura.dedun.compose.wallet.wavelength.group.ArkNote
import com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy
import com.machankura.dedun.compose.wallet.wavelength.group.ArkRequest
import com.machankura.dedun.compose.wallet.wavelength.group.ArkWire
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkDerivation
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkKeys
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkSigner
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.machankura.dedun.compose.wallet.wavelength.group.IcebergChildKeys
import com.machankura.dedun.compose.wallet.wavelength.group.NotedRound
import com.machankura.dedun.compose.wallet.wavelength.group.NotedTransfer
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.utils.Either
import kotlin.time.Clock

/**
 * A member's device answering for a group's Wavelength wallet -- `docs/group-wavelength-wallet.md`, Phase 6.
 *
 * The shape is the Lightning co-signer's, on the same tables under its own wallet key and label domain: round one published
 * ahead of need, and round two only after [ArkPolicy] has agreed, and `n-t+1` participants have echoed the same hash for the label,
 * because a coordinator can show three members three messages under one label, each signs once, and a share leaks with no error.
 *
 * It adds what that co-signer left unbuilt: a device answers nothing until `t` others have said how far they have answered, since a
 * restored phone would otherwise answer a label a second time.
 */
object GroupArkCoSigner {
    private val logger = Logger.withTag("GroupArkCoSigner")

    private const val LABEL_TAG = "dedun/group-wavelength/label/v1"

    /** How many signatures one tree key has labels for: one per transaction on its path, and a path is short. */
    const val TREE_LABELS = 12

    private const val MAX_PLAIN_BATCH = 128L
    private const val MAX_TREE_BATCH = 32L

    fun walletKey(chatRoomId: String, chain: Chain, wallet: Long): String =
        "wavelength:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    /** Where a tree key's labels are filed: the shared nonce table, under a key of its own. */
    fun treeKey(walletKey: String, child: Long): String = "$walletKey:tree:$child"

    /** The label for [counter] of [domain]: one sequence per key, under a tag of its own, so no other wallet's can collide. */
    fun label(icebergKey: HexKey, domain: ArkWire.Domain, counter: Long): ByteVector32 = Crypto.taggedHash(
        ByteVector(icebergKey).toByteArray() + Pack.writeInt64BE(domain.child ?: -1L) + Pack.writeInt64BE(counter),
        LABEL_TAG,
    )

    private fun shareOf(wallet: GroupWavelengthWalletManager.Wallet, domain: ArkWire.Domain, base: Share): Share =
        domain.child?.let { IcebergChildKeys.share(base, it) } ?: base

    private fun keysOf(wallet: GroupWavelengthWalletManager.Wallet) =
        GroupArkKeys(PublicKey.fromHex(wallet.statement.icebergKey), wallet.statement.chain, wallet.statement.wallet)

    private suspend fun baseShare(database: DedunDatabase, wallet: GroupWavelengthWalletManager.Wallet): Share? =
        database.icebergKeySessionDao().getSessionById(wallet.statement.ceremonyId)?.let(IcebergRitualManager::shareOf)

    private suspend fun walletOf(database: DedunDatabase, chatRoomId: String, key: String): GroupWavelengthWalletManager.Wallet? {
        val parts = key.removePrefix("wavelength:").split(":").takeIf { it.size >= 3 } ?: return null
        val chain = GroupWalletStatementEvent.chainNamed(parts[parts.size - 2]) ?: return null
        val number = parts.last().toLongOrNull() ?: return null
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

        val plainFrom = (dao.highestNonce(key, me) ?: -1L) + 1
        val plainEnd = minOf(plainTo, plainFrom + MAX_PLAIN_BATCH - 1)
        val plain = (plainFrom..plainEnd).map { counter ->
            GroupArkSigner.contribution(share, label(wallet.statement.icebergKey, ArkWire.Domain(), counter)).toByteArray().toHex()
                .also { dao.insertNonce(IcebergNonceRow(key, counter, me, it)) }
        }

        val treeFrom = (arkDao.highestTreeShare(key, me) ?: -1L) + 1
        val treeEnd = minOf(treeTo, treeFrom + MAX_TREE_BATCH - 1)
        val trees = (treeFrom..treeEnd).map { child ->
            val childShare = IcebergChildKeys.share(share, child)
            val publicShare = Iceberg.publicShare(childShare, null).toByteArray().toHex()
            arkDao.insertTreeShare(GroupArkTreeShare(key, child, me, publicShare))
            val domain = ArkWire.Domain(child)
            val nonces = (0L until TREE_LABELS).map { counter ->
                GroupArkSigner.contribution(childShare, label(wallet.statement.icebergKey, domain, counter)).toByteArray().toHex()
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
                GroupWavelengthEvents.PROVISION -> fileProvision(database, localChatRoom, innerEvent)
                GroupWavelengthEvents.SIGNING_REQUEST -> handleBatch(database, localChatRoom, innerEvent, userPublicKey)
                GroupWavelengthEvents.ECHO -> fileEchoes(database, localChatRoom, innerEvent, userPublicKey)
                GroupWavelengthEvents.SIGNATURE_SHARES -> fileShares(database, localChatRoom, innerEvent)
                GroupWavelengthEvents.HIGH_WATER -> fileHighWater(database, localChatRoom, innerEvent, userPublicKey)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.e("Could not process co-signing kind ${innerEvent.kind}", e)
        }
    }

    private suspend fun fileProvision(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event) {
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
    }

    private suspend fun fileShares(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event) {
        val batch = CommonJson.decodeFromString(ArkWire.ShareBatch.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        batch.items.forEach { database.icebergCoSigningDao().insertShare(IcebergShareRow(batch.wallet, it.requestId, event.pubKey.lowercase(), it.share)) }
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
    suspend fun contextOf(database: DedunDatabase, chatRoomId: String, wallet: GroupWavelengthWalletManager.Wallet, key: String): ArkPolicy.Context {
        val vtxos = database.groupArkDao().vtxos(key)
        val tree = treeKeys(database, wallet, key).values.toSet()
        val onchain = onchainScripts(database, chatRoomId, wallet)
        val statement = wallet.statement
        return ArkPolicy.Context(
            keys = keysOf(wallet),
            operator = PublicKey.fromHex(statement.arkOperatorKey),
            exitDelay = statement.policy.exitDelay,
            maxRoundFeeSat = statement.policy.maxRoundFeeSat,
            maxOnchainFeeSat = statement.policy.maxOnchainFeeSat,
            isTreeKey = { it in tree },
            isOnchainWallet = { it in onchain },
            knownVtxos = vtxos.filter { !it.spent }.associate { it.outpoint to it.amountSat },
            capSat = GroupWavelengthRollout.capFor(statement.chain)?.sat,
            unspentSat = vtxos.filter { !it.spent && it.forfeitedIn == null }.sumOf { it.amountSat },
            forfeitedIn = vtxos.mapNotNull { row -> row.forfeitedIn?.let { row.outpoint to it } }.toMap(),
        )
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
        if (batch.items.isEmpty()) return

        // A device that doesn't yet know how far the group has answered asks, and answers nothing until it knows.
        val floor = floorOf(database, localChatRoom, batch.wallet, userPublicKey, wallet) ?: return
        val echoes = mutableListOf<ArkWire.EchoItem>()
        for (item in batch.items) {
            val refusal = answer(database, roomId, wallet, batch.wallet, item, floor, userPublicKey, event.pubKey, echoes)
            if (refusal != null) sayRefused(database, roomId, item.requestId, userPublicKey, refusal)
        }
        if (echoes.isNotEmpty()) {
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.ECHO, CommonJson.encodeToString(ArkWire.EchoBatch.serializer(), ArkWire.EchoBatch(batch.wallet, echoes)))
        }
        maybeSign(database, localChatRoom, wallet, batch.wallet, batch.items.map { label(wallet.statement.icebergKey, it.domain, it.counter).toHex() }, userPublicKey, batch.items)
    }

    /** A person never sees what their device signs, so a refusal is the one thing about this they are told, and only on this device. */
    private suspend fun sayRefused(database: DedunDatabase, roomId: String, requestId: String, userPublicKey: HexKey, refusal: String) {
        logger.w("Refusing request $requestId: $refusal")
        GroupLightningLines.writeFact(
            database, roomId, ChatMessage.TYPE_LIGHTNING_REFUSED, requestId, userPublicKey,
            "Your device refused to co-sign for the Wavelength wallet: $refusal.", Clock.System.now(),
        )
    }

    /** Checks one item, commits to its hash under its label for good, and notes an echo to send. Null when it may go on. */
    private suspend fun answer(
        database: DedunDatabase,
        roomId: String,
        wallet: GroupWavelengthWalletManager.Wallet,
        key: String,
        item: ArkWire.SigningItem,
        floor: Long,
        userPublicKey: HexKey,
        operator: HexKey,
        echoes: MutableList<ArkWire.EchoItem>,
    ): String? {
        val label = label(wallet.statement.icebergKey, item.domain, item.counter).toHex()
        val domainKey = item.domain.child
        if (domainKey == null && item.counter < floor) return "a label from before this device last knew how far the group had answered"

        val sessionKey = domainKey?.let { child -> treeKeys(database, wallet, key)[child] ?: return "a tree key the group hasn't finished making" }
        val verdict = ArkPolicy.check(contextOf(database, roomId, wallet, key), item.request, sessionKey)
        val allowed = when (verdict) {
            is ArkPolicy.Verdict.Refuse -> return verdict.reason
            is ArkPolicy.Verdict.Allow -> verdict
        }
        // A plain label signs a plain request and a tree label a tree one, or the share under one signs for the other.
        if ((domainKey != null) != (allowed.what == ArkPolicy.What.TREE)) return "a label for another kind of signature"

        val dao = database.icebergCoSigningDao()
        val message = allowed.message.toHex()
        val existing = dao.ledgerFor(key, label)
        if (existing != null && existing.messageHash != message) {
            raiseEquivocation(database, roomId, label, operator)
            return "a label already echoed for another message"
        }
        remember(database, key, allowed, item.request)
        if (existing == null) {
            dao.insertLedger(IcebergLabelLedger(key, label, item.requestId, message))
            dao.insertEcho(IcebergEchoRow(key, label, userPublicKey.lowercase(), message))
            echoes += ArkWire.EchoItem(item.requestId, label, message)
        }
        return null
    }

    /** What signing this means for this device's own record of the wallet. Written before the echo, so it is never behind one. */
    private suspend fun remember(database: DedunDatabase, key: String, allowed: ArkPolicy.Verdict.Allow, request: ArkRequest) {
        val note = request.note ?: return
        val dao = database.groupArkDao()
        when {
            note.kind == ArkNote.ROUND && allowed.what in setOf(ArkPolicy.What.FORFEIT, ArkPolicy.What.BOARDING, ArkPolicy.What.TREE) -> {
                val round = CommonJson.decodeFromJsonElement(NotedRound.serializer(), note.material)
                val known = dao.vtxos(key).associateBy { it.outpoint }
                round.vtxos.forEach { vtxo ->
                    val script = ByteVector(vtxo.pkScript)
                    round.trees.flatMap { it.nodes }.forEach { node ->
                        val tx = Transaction.read(node.tx)
                        tx.txOut.forEachIndexed { i, out ->
                            if (out.publicKeyScript == script && out.amount.sat == vtxo.amount) {
                                val outpoint = "${tx.txid}:$i"
                                if (outpoint !in known) dao.upsertVtxo(GroupArkVtxo(key, outpoint, vtxo.amount))
                            }
                        }
                    }
                }
                if (allowed.what == ArkPolicy.What.FORFEIT) {
                    val tx = Transaction.read(request.tx!!)
                    val spent = tx.txIn[0].outPoint.let { "${it.txid}:${it.index}" }
                    known[spent]?.let { dao.upsertVtxo(it.copy(forfeitedIn = note.id)) }
                }
            }
            note.kind == ArkNote.OOR && allowed.what == ArkPolicy.What.TRANSFER -> {
                val transfer = CommonJson.decodeFromJsonElement(NotedTransfer.serializer(), note.material)
                val tx = Transaction.read(request.tx!!)
                if (tx.txid != Transaction.read(transfer.ark.tx).txid) return
                val known = dao.vtxos(key).associateBy { it.outpoint }
                transfer.checkpoints.forEach { checkpoint ->
                    val input = Transaction.read(checkpoint.tx).txIn[0].outPoint.let { "${it.txid}:${it.index}" }
                    known[input]?.let { dao.upsertVtxo(it.copy(spent = true)) }
                }
                tx.txOut.forEachIndexed { i, out ->
                    if (out.amount.sat > 0) dao.upsertVtxo(GroupArkVtxo(key, "${tx.txid}:$i", out.amount.sat))
                }
            }
        }
    }

    private suspend fun fileEchoes(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val batch = CommonJson.decodeFromString(ArkWire.EchoBatch.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        batch.items.forEach { database.icebergCoSigningDao().insertEcho(IcebergEchoRow(batch.wallet, it.label, event.pubKey.lowercase(), it.messageHash)) }
        maybeSign(database, localChatRoom, wallet, batch.wallet, batch.items.map { it.label }, userPublicKey, null)
    }

    /** The alarm the echo rule exists to raise: two messages under one label. Said once per label, on every device that sees it. */
    private suspend fun raiseEquivocation(database: DedunDatabase, chatRoomId: String, label: String, operator: HexKey) =
        GroupLightningLines.writeFact(
            database, chatRoomId, ChatMessage.TYPE_LIGHTNING_EQUIVOCATION, label, operator,
            "The Wavelength wallet's operator asked the group to sign two different things under one label. Nobody will sign either.",
            Clock.System.now(),
        )

    private suspend fun requestsOf(database: DedunDatabase, chatRoomId: String): List<ArkWire.SigningItem> =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupWavelengthEvents.SIGNING_REQUEST))
            .mapNotNull { runCatching { CommonJson.decodeFromString(ArkWire.SigningBatch.serializer(), it.content) }.getOrNull() }
            .flatMap { it.items }

    /** Answers round two for each of [labels] once enough participants have echoed this device's hash, and nobody another. */
    private suspend fun maybeSign(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        wallet: GroupWavelengthWalletManager.Wallet,
        key: String,
        labels: List<String>,
        userPublicKey: HexKey,
        known: List<ArkWire.SigningItem>?,
    ) {
        val dao = database.icebergCoSigningDao()
        val roomId = localChatRoom.chatRoom.id
        val shares = mutableListOf<ArkWire.ShareItem>()
        val base = baseShare(database, wallet) ?: return
        val n = wallet.statement.participants.size
        val t = wallet.statement.threshold
        var items = known
        for (label in labels.distinct()) {
            val mine = dao.ledgerFor(key, label) ?: continue
            if (mine.signedAt != null) continue
            val echoes = dao.echoesFor(key, label)
            if (echoes.any { it.messageHash != mine.messageHash }) {
                val operator = GroupWavelengthOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet)
                raiseEquivocation(database, roomId, label, operator?.publicKey ?: userPublicKey)
                continue
            }
            if (echoes.map { it.participant }.toSet().size < n - t + 1) continue

            val item = (items ?: requestsOf(database, roomId).also { items = it }).firstOrNull { it.requestId == mine.requestId }
                ?: requestsOf(database, roomId).firstOrNull { it.requestId == mine.requestId } ?: continue
            val share = shareOf(wallet, item.domain, base)
            val groupKey = item.domain.child?.let { treeKeys(database, wallet, key)[it] ?: continue } ?: PublicKey.fromHex(wallet.statement.icebergKey)
            val cache = cacheOf(wallet, item.request) ?: continue
            val cosigners = item.cosignerNonce?.let { AggregatedNonce(ByteVector(it)) } ?: GroupArkSigner.NO_COSIGNERS
            val signature = Iceberg.partialSign(share, null, ByteVector32(label), item.contributions.map { NonceContribution(it) }, groupKey, cache, ByteVector32(mine.messageHash), cosigners)
            when (signature) {
                is Either.Left -> logger.e("Could not sign under ${label.take(16)}", signature.value)
                is Either.Right -> {
                    val hex = signature.value.toByteArray().toHex()
                    dao.upsertLedger(mine.copy(signedAt = Clock.System.now()))
                    dao.insertShare(IcebergShareRow(key, mine.requestId, userPublicKey.lowercase(), hex))
                    shares += ArkWire.ShareItem(mine.requestId, label, hex)
                }
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
    private suspend fun floorOf(database: DedunDatabase, localChatRoom: LocalChatRoom, key: String, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): Long? {
        val dao = database.groupArkDao()
        val row = dao.floor(key)
        row?.floor?.let { return it }
        if (row == null) {
            dao.upsertFloor(GroupArkFloor(key))
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), ArkWire.HighWater(key, asking = true, highest = -1)))
        }
        return settleFloor(database, key, userPublicKey, wallet)
    }

    private suspend fun settleFloor(database: DedunDatabase, key: String, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): Long? {
        val dao = database.groupArkDao()
        val marks = dao.marks(key).filter { it.participant != userPublicKey.lowercase() }
        if (marks.size < wallet.statement.threshold) return null
        val floor = marks.maxOf { it.highest } + 1
        dao.upsertFloor(GroupArkFloor(key, floor))
        return floor
    }

    /** The highest plain counter this device has seen anybody answer, from its own ledger of what it echoed. */
    private suspend fun highestAnswered(database: DedunDatabase, localChatRoom: LocalChatRoom, wallet: GroupWavelengthWalletManager.Wallet, key: String): Long =
        requestsOf(database, localChatRoom.chatRoom.id).filter { it.domain.child == null }
            .filter { database.icebergCoSigningDao().ledgerFor(key, label(wallet.statement.icebergKey, it.domain, it.counter).toHex()) != null }
            .maxOfOrNull { it.counter } ?: -1L

    private suspend fun fileHighWater(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val message = CommonJson.decodeFromString(ArkWire.HighWater.serializer(), event.content)
        val roomId = localChatRoom.chatRoom.id
        val wallet = walletOf(database, roomId, message.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null || event.pubKey.lowercase() == userPublicKey.lowercase()) return
        if (message.asking) {
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), ArkWire.HighWater(message.wallet, asking = false, highest = highestAnswered(database, localChatRoom, wallet, message.wallet))))
            return
        }
        val dao = database.groupArkDao()
        dao.upsertMark(GroupArkMark(message.wallet, event.pubKey.lowercase(), message.highest))
        if (dao.floor(message.wallet)?.floor == null && dao.floor(message.wallet) != null) {
            val floor = settleFloor(database, message.wallet, userPublicKey, wallet) ?: return
            retryPending(database, localChatRoom, wallet, message.wallet, floor, userPublicKey)
        }
    }

    /** Answers what arrived while this device was still asking how far the group had got. */
    private suspend fun retryPending(database: DedunDatabase, localChatRoom: LocalChatRoom, wallet: GroupWavelengthWalletManager.Wallet, key: String, floor: Long, userPublicKey: HexKey) {
        val roomId = localChatRoom.chatRoom.id
        val operator = GroupWavelengthOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet)?.publicKey ?: return
        val echoes = mutableListOf<ArkWire.EchoItem>()
        val pending = requestsOf(database, roomId)
        pending.forEach { item ->
            val refusal = answer(database, roomId, wallet, key, item, floor, userPublicKey, operator, echoes)
            if (refusal != null) sayRefused(database, roomId, item.requestId, userPublicKey, refusal)
        }
        if (echoes.isNotEmpty()) {
            queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.ECHO, CommonJson.encodeToString(ArkWire.EchoBatch.serializer(), ArkWire.EchoBatch(key, echoes)))
        }
        maybeSign(database, localChatRoom, wallet, key, pending.map { label(wallet.statement.icebergKey, it.domain, it.counter).toHex() }, userPublicKey, pending)
    }
}
