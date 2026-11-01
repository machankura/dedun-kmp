package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.text.grouped
import fr.acinq.bitcoin.Satoshi
import com.machankura.dedun.compose.database.model.ChatMessage
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.database.model.GroupLightningSnapshotRow
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferManager
import kotlin.time.Instant

/**
 * What the operator tells the room about the group's Lightning wallet, and how a member asks it for an
 * invoice (`docs/group-lightning-wallet.md`, Phases 10 and 11).
 *
 * Everything here is the operator's claim. A member keeps it to show, checks what it can -- that the
 * sender is the operator of the term it names, that an invoice pays the node the group signed for, that
 * a channel's balance is what this device last co-signed -- and decides nothing with it: the co-signer
 * reads its own ledgers, never a snapshot.
 */
object GroupLightningReports {
    private val logger = Logger.withTag("GroupLightningReports")

    /** One channel as the snapshot reports it, beside what this device last co-signed for it. */
    data class ChannelCheck(val channel: WalletWire.ChannelView, val signedOursMsat: Long?) {
        /** Whether the operator's figure is the one this device signed; null when it signed nothing for the channel. */
        val agrees: Boolean? get() = signedOursMsat?.let { it == channel.oursMsat }
    }

    /**
     * Whether a payment the node reports is a channel's close. lightning-kmp files a close as an
     * outgoing payment, and gives it no payment hash: every Lightning payment has one. Read here
     * for the wallet's payment list and the room's lines alike, so the two cannot disagree.
     */
    fun isChannelClose(incoming: Boolean, paymentHash: String?): Boolean = !incoming && paymentHash == null

    /** What reached an outgoing payment's recipient: lightning-kmp reports its amount with the fee in it. */
    fun recipientMsat(amountMsat: Long, feesMsat: Long): Long = amountMsat - feesMsat

    /** Sends [snapshot] to the room, and files it here as every member would. */
    suspend fun publishSnapshot(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, snapshot: WalletWire.Snapshot) {
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.SNAPSHOT, CommonJson.encodeToString(WalletWire.Snapshot.serializer(), snapshot))
        file(database, localChatRoom.chatRoom.id, userPublicKey, snapshot)
    }

    /** Routes one inbound snapshot or invoice. Invoice requests are read by the running node, not here. */
    suspend fun process(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        runCatching {
            when (innerEvent.kind) {
                GroupLightningEvents.SNAPSHOT -> receiveSnapshot(database, localChatRoom, innerEvent)
                GroupLightningEvents.INVOICE -> receiveInvoice(database, localChatRoom, innerEvent)
                GroupLightningEvents.INVOICE_REQUEST -> receiveInvoiceRequest(database, localChatRoom, innerEvent)
            }
        }.onFailure { logger.w("Could not read wallet report ${innerEvent.id}: ${it.message}") }
    }

    private suspend fun receiveSnapshot(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        val snapshot = CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), innerEvent.content)
        val wallet = GroupLightningCoSigner.walletOf(database, localChatRoom.chatRoom.id, snapshot.wallet) ?: return
        if (snapshot.nodeId != wallet.statement.nodeId) return logger.w("Snapshot ${innerEvent.id} is for a node the group did not sign for")
        // A snapshot from an earlier term's operator is still the truth about that term; one from
        // anybody else is nobody's.
        val operator = GroupLightningOperatorManager.ofTerm(database, localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet, snapshot.term)
        if (operator?.publicKey != innerEvent.pubKey) return logger.w("Snapshot ${innerEvent.id} is not from the operator of term ${snapshot.term}")
        // An offer is kept only if it is the one the group's node derives: the seed says which.
        file(database, localChatRoom.chatRoom.id, innerEvent.pubKey, snapshot.copy(offer = snapshot.offer?.takeIf { it == offerOf(wallet) }))
        // The round-one labels the operator handed out, so whoever operates next signs under them.
        val dao = database.icebergCoSigningDao()
        snapshot.assignments.forEach { (purpose, counter) ->
            if (dao.assignmentFor(snapshot.wallet, purpose) == null) {
                val label = GroupLightningCoSigner.poolLabel(wallet.statement.icebergKey, counter).toHex()
                runCatching { dao.insertAssignment(IcebergLabelAssignment(snapshot.wallet, counter, label, purpose)) }
            }
        }
    }

    /**
     * The Bolt12 offer the group's node publishes, recomputed from the node seed the way lightning-kmp
     * derives it ([OfferManager.deterministicOffer]); null on a device that does not hold the seed.
     */
    fun offerOf(wallet: GroupLightningWalletManager.Wallet): String? {
        val seed = wallet.seed ?: return null
        val nodeKey = GroupLightningCoSigner.keyManagerOf(ByteVector(seed.seed), wallet.statement.chain).nodeKeys.nodeKey.privateKey
        return OfferManager.deterministicOffer(wallet.statement.chain.chainHash, nodeKey, PublicKey.fromHex(wallet.statement.lspNodeId), null, null, null).offer.encode()
    }

    private suspend fun file(database: DedunDatabase, chatRoomId: String, sentBy: HexKey, snapshot: WalletWire.Snapshot) {
        val dao = database.groupLightningRecordsDao()
        val held = dao.snapshot(snapshot.wallet)
        val previous = held?.let { runCatching { CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), it.content) }.getOrNull() }
        val newer = held == null || snapshot.term > held.term || (snapshot.term == held.term && snapshot.sentAt >= held.sentAt.epochSeconds)
        if (newer) {
            dao.upsertSnapshot(GroupLightningSnapshotRow(snapshot.wallet, sentBy, snapshot.term, CommonJson.encodeToString(WalletWire.Snapshot.serializer(), snapshot), Instant.fromEpochSeconds(snapshot.sentAt)))
        }
        // Payments are kept whatever the snapshot's age: a list of what happened only grows.
        snapshot.payments.forEach { p ->
            dao.upsertPayment(GroupLightningPaymentRow(snapshot.wallet, p.id, p.incoming, p.amountMsat, p.feesMsat, p.paymentHash, p.description, p.completedAt?.let(Instant::fromEpochMilliseconds), p.succeeded, p.liquiditySat))
        }
        sayWhatHappened(database, chatRoomId, sentBy, snapshot, previous.takeIf { newer })
    }

    /** lightning-kmp's channel states from the moment a close begins: what a snapshot says a closing channel is. */
    private val CLOSING_STATES = setOf("ShuttingDown", "Negotiating", "Closing", "Closed")

    /**
     * The lines for what [snapshot] says happened (docs/group-lightning-transcript.md, Phase 5): a
     * channel this room has not been told of, a channel that began to close, a payment that settled or
     * failed. Each is written once whatever the number of snapshots that repeat it, because the line is
     * keyed on the channel or the payment. [previous] is the snapshot this one replaces, when it does:
     * a channel it held that this one does not is gone, and closed.
     */
    private suspend fun sayWhatHappened(
        database: DedunDatabase,
        chatRoomId: String,
        sentBy: HexKey,
        snapshot: WalletWire.Snapshot,
        previous: WalletWire.Snapshot?,
    ) {
        val sentAt = Instant.fromEpochSeconds(snapshot.sentAt)
        fun sats(msat: Long) = "${Satoshi(msat / 1000).grouped()} sats"

        suspend fun closed(channel: WalletWire.ChannelView) = GroupLightningLines.writeFact(
            database, chatRoomId, ChatMessage.TYPE_LIGHTNING_CHANNEL_CLOSED, channel.channelId, sentBy,
            if (channel.oursMsat >= 1000) "A Lightning channel closed. Its ${sats(channel.oursMsat)} go to the onchain wallet."
            else "A Lightning channel closed, with nothing of the group's in it.",
            sentAt,
        )

        snapshot.channels.forEach { channel ->
            if (channel.state in CLOSING_STATES) {
                closed(channel)
            } else {
                GroupLightningLines.writeFact(
                    database, chatRoomId, ChatMessage.TYPE_LIGHTNING_CHANNEL_OPENED, channel.channelId, sentBy,
                    "The group's Lightning node opened a channel of ${Satoshi(channel.capacitySat).grouped()} sats.",
                    sentAt,
                )
            }
        }
        previous?.channels
            ?.filter { held -> snapshot.channels.none { it.channelId == held.channelId } }
            ?.forEach { closed(it) }

        snapshot.payments.forEach { payment ->
            val completedAt = payment.completedAt?.let(Instant::fromEpochMilliseconds) ?: return@forEach
            // lightning-kmp files a channel's close as an outgoing payment, with no payment hash: every
            // Lightning payment has one. The channel's closed line already says it, and a second line
            // would read as the group having paid the money away. Found by the regtest run.
            if (isChannelClose(payment.incoming, payment.paymentHash)) return@forEach
            // A purchase of liquidity is said by its own line, below, as it locks or fails: it is not money sent.
            if (payment.kind == WalletWire.LIQUIDITY) return@forEach
            val (type, content) = when {
                payment.incoming && payment.succeeded -> ChatMessage.TYPE_LIGHTNING_PAYMENT_RECEIVED to buildString {
                    append("The group's Lightning wallet received ${sats(payment.amountMsat)}")
                    payment.description?.takeIf { it.isNotBlank() }?.let { append(", for \u201c$it\u201d") }
                    append(".")
                }
                payment.incoming -> return@forEach
                // An outgoing payment's amount is what left the wallet, its fee included; the line
                // says what reached the recipient and the fee beside it.
                payment.succeeded -> ChatMessage.TYPE_LIGHTNING_PAYMENT_SENT to buildString {
                    append("The group's Lightning wallet sent ${sats(recipientMsat(payment.amountMsat, payment.feesMsat))}")
                    if (payment.feesMsat > 0) append(", with ${Satoshi((payment.feesMsat + 999) / 1000).grouped()} sats in fees")
                    append(".")
                }
                else -> ChatMessage.TYPE_LIGHTNING_PAYMENT_FAILED to
                    "A Lightning payment of ${sats(recipientMsat(payment.amountMsat, payment.feesMsat))} failed. Nothing left the wallet."
            }
            GroupLightningLines.writeFact(database, chatRoomId, type, payment.id, sentBy, content, completedAt)
        }

        // A liquidity purchase, once it has an outcome: the channel is on the splice's output, or it failed.
        snapshot.purchases.forEach { purchase ->
            when (purchase.state) {
                WalletWire.LOCKED -> GroupLightningLines.writeFact(
                    database, chatRoomId, ChatMessage.TYPE_LIGHTNING_LIQUIDITY_ADDED, purchase.id, sentBy,
                    "The group's Lightning wallet can receive ${Satoshi(purchase.amountSat).grouped()} sats more" +
                        (purchase.feeSat?.let { ", for ${Satoshi(it).grouped()} sats in fees." } ?: "."),
                    sentAt,
                )
                WalletWire.FAILED -> GroupLightningLines.writeFact(
                    database, chatRoomId, ChatMessage.TYPE_LIGHTNING_LIQUIDITY_FAILED, purchase.id, sentBy,
                    "Adding ${Satoshi(purchase.amountSat).grouped()} sats of inbound liquidity failed" + (purchase.reason?.let { ": $it." } ?: ". Nothing left the wallet."),
                    sentAt,
                )
            }
        }
    }

    /** A snapshot, with each of its channels checked against this device's own ledger. */
    data class Report(val snapshot: WalletWire.Snapshot, val checks: List<ChannelCheck>)

    /** The newest snapshot this device holds for [walletKey], with each channel checked against its own ledger. */
    suspend fun latest(database: DedunDatabase, walletKey: String): Report? {
        val row = database.groupLightningRecordsDao().snapshot(walletKey) ?: return null
        val snapshot = CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), row.content)
        return Report(snapshot, snapshot.channels.map { ChannelCheck(it, GroupLightningCoSigner.signedOursMsat(database, walletKey, it.channelId)) })
    }

    /**
     * Asks the operator for an invoice; the answer arrives under the returned request id. Only for a
     * depositor: the request names the group's statement for [userPublicKey], which the invoice is
     * issued against, so the deposit is theirs. `docs/member-deposits.md`, Phase 6.
     *
     * @throws IllegalStateException when the group has not opened [userPublicKey]'s deposits
     */
    suspend fun requestInvoice(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        wallet: GroupLightningWalletManager.Wallet,
        amountMsat: Long?,
        description: String,
    ): String {
        val depositor = Depositors.statementOf(database, localChatRoom.chatRoom.id, wallet.statement.chain, userPublicKey)
            ?: throw IllegalStateException("The group has not opened ${userPublicKey.take(8)}'s deposits")
        val key = GroupLightningCoSigner.walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val requestId = RandomInstance.bytes(16).toHex()
        val request = WalletWire.InvoiceRequest(key, requestId, amountMsat, description, depositor = depositor.event.id)
        val queued = GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.INVOICE_REQUEST, CommonJson.encodeToString(WalletWire.InvoiceRequest.serializer(), request))
        GroupLightningLines.write(database, localChatRoom.chatRoom.id, queued.id, ChatMessage.TYPE_LIGHTNING_INVOICE_REQUESTED, userPublicKey, asked(request), queued.createdAt)
        return requestId
    }

    /** A member's invoice request, as its line: only for a wallet this room has. The operator's node answers it. */
    private suspend fun receiveInvoiceRequest(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        val request = CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), innerEvent.content)
        GroupLightningCoSigner.walletOf(database, localChatRoom.chatRoom.id, request.wallet) ?: return
        GroupLightningLines.write(
            database, localChatRoom.chatRoom.id, innerEvent.id, ChatMessage.TYPE_LIGHTNING_INVOICE_REQUESTED,
            innerEvent.pubKey, asked(request), Instant.fromEpochSeconds(innerEvent.createdAt),
        )
    }

    /** What a member asked for, to follow their name. The description is theirs and is quoted as it is. */
    private fun asked(request: WalletWire.InvoiceRequest): String = buildString {
        append("asked the operator's node for an invoice")
        request.amountMsat?.let { append(" of ${Satoshi(it / 1000).grouped()} sats") }
        request.description.takeIf { it.isNotBlank() }?.let { append(", for \u201c$it\u201d") }
        append(".")
    }

    /** The node's answer to [requestId], as its line: naming whoever asked, when this device has their request. */
    private suspend fun invoiceIssued(database: DedunDatabase, chatRoomId: String, requestId: String): String {
        val asker = database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.INVOICE_REQUEST))
            .firstOrNull { runCatching { CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), it.content) }.getOrNull()?.requestId == requestId }
            ?.publicKey
        return if (asker == null) "The operator's node made an invoice." else "The operator's node made @${ChatMessage.memberName(database, asker)}'s invoice."
    }

    /** An invoice request, and the member who asked: the MLS sender, which `mip03Rejection` has made its author. */
    data class Pending(val request: WalletWire.InvoiceRequest, val requester: HexKey)

    /**
     * The invoice requests for [walletKey] in the room that no invoice answers yet, oldest first --
     * only those that name the group's depositor statement for the member who sent them. A request
     * naming none, or somebody else's, is never answered: an invoice is issued against a depositor,
     * or not at all. `docs/member-deposits.md`, D5.
     */
    suspend fun unanswered(database: DedunDatabase, chatRoomId: String, walletKey: String): List<Pending> {
        val chain = GroupLightningCoSigner.walletOf(database, chatRoomId, walletKey)?.statement?.chain ?: return emptyList()
        val depositors = Depositors.statementsIn(database, chatRoomId, chain)
        return database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.INVOICE_REQUEST))
            .sortedBy { it.createdAt }
            .mapNotNull { row ->
                runCatching { CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), row.content) }.getOrNull()
                    ?.let { Pending(it, row.publicKey.lowercase()) }
            }
            .filter { (request, requester) ->
                request.wallet == walletKey && database.groupLightningRecordsDao().invoice(walletKey, request.requestId) == null &&
                    depositors[requester]?.event?.id == request.depositor
            }
    }

    /** Sends the room the operator's invoice for [pending], naming whose it is and its hash, and files it here. */
    suspend fun answer(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, pending: Pending, bolt11: String) {
        val request = pending.request
        val paymentHash = Bolt11Invoice.read(bolt11).get().paymentHash.toHex()
        val reply = WalletWire.InvoiceReply(request.wallet, request.requestId, bolt11, requester = pending.requester, paymentHash = paymentHash)
        val queued = GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.INVOICE, CommonJson.encodeToString(WalletWire.InvoiceReply.serializer(), reply))
        database.groupLightningRecordsDao().upsertInvoice(GroupLightningInvoiceRow(request.wallet, request.requestId, bolt11, requester = pending.requester, paymentHash = paymentHash))
        GroupLightningLines.write(
            database, localChatRoom.chatRoom.id, queued.id, ChatMessage.TYPE_LIGHTNING_INVOICE_ISSUED,
            userPublicKey, invoiceIssued(database, localChatRoom.chatRoom.id, request.requestId), queued.createdAt,
        )
    }

    private suspend fun receiveInvoice(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        val reply = CommonJson.decodeFromString(WalletWire.InvoiceReply.serializer(), innerEvent.content)
        val wallet = GroupLightningCoSigner.walletOf(database, localChatRoom.chatRoom.id, reply.wallet) ?: return
        val operator = GroupLightningOperatorManager.current(database, localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        if (operator?.publicKey != innerEvent.pubKey) return logger.w("Invoice ${innerEvent.id} is not from the operator")
        // Whoever sent it, an invoice that pays some other node is not the group's.
        val invoice = Bolt11Invoice.read(reply.bolt11).get()
        if (invoice.nodeId.toString() != wallet.statement.nodeId) return logger.w("Invoice ${innerEvent.id} pays a node that is not the group's")
        // And it has to be the invoice for the request it answers: whose it is decides whose deposit
        // its payment is. docs/member-deposits.md, D5.
        refuseReply(database, localChatRoom.chatRoom.id, reply, invoice)?.let { reason ->
            return logger.w("Invoice ${innerEvent.id} for request ${reply.requestId.take(8)} $reason; not filed")
        }
        val requester = requesterOf(database, localChatRoom.chatRoom.id, reply.requestId)
        database.groupLightningRecordsDao().upsertInvoice(
            GroupLightningInvoiceRow(reply.wallet, reply.requestId, reply.bolt11, requester = requester, paymentHash = invoice.paymentHash.toHex())
        )
        GroupLightningLines.write(
            database, localChatRoom.chatRoom.id, innerEvent.id, ChatMessage.TYPE_LIGHTNING_INVOICE_ISSUED,
            innerEvent.pubKey, invoiceIssued(database, localChatRoom.chatRoom.id, reply.requestId), Instant.fromEpochSeconds(innerEvent.createdAt),
        )
    }

    /** Who sent the invoice request [requestId] in [chatRoomId], when this device holds it. */
    private suspend fun requesterOf(database: DedunDatabase, chatRoomId: String, requestId: String): HexKey? =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.INVOICE_REQUEST))
            .firstOrNull { runCatching { CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), it.content) }.getOrNull()?.requestId == requestId }
            ?.publicKey?.lowercase()

    /**
     * Why [reply] is not the invoice for the request it names, or null when it is (D5): it names the
     * member who asked and the hash it is paid by, as the invoice says; it is for the amount and the
     * description that were asked for; and no other request's invoice has its hash. A device that
     * doesn't hold the request checks what it can -- the hash, and that it is unique.
     */
    internal suspend fun refuseReply(database: DedunDatabase, chatRoomId: String, reply: WalletWire.InvoiceReply, invoice: Bolt11Invoice): String? {
        val hash = invoice.paymentHash.toHex()
        if (reply.paymentHash != null && reply.paymentHash != hash) return "names a hash its invoice is not paid by"
        val others = database.groupLightningRecordsDao().invoicesByHash(reply.wallet, hash).filter { it.requestId != reply.requestId }
        if (others.isNotEmpty()) return "is paid by the hash of another request's invoice"

        val row = database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.INVOICE_REQUEST))
            .firstOrNull { runCatching { CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), it.content) }.getOrNull()?.requestId == reply.requestId }
            ?: return null
        val request = CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), row.content)
        if (reply.requester != null && !reply.requester.equals(row.publicKey, ignoreCase = true)) return "names somebody other than the member who asked"
        if (request.amountMsat != null && invoice.amount?.msat != request.amountMsat) return "is not for the amount that was asked for"
        if (request.description.isNotBlank() && invoice.description != request.description) return "does not carry the description that was asked for"
        return null
    }
}
