package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthDepositAnswer
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthDepositRequest
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Money coming into a group's Wavelength wallet by boarding (`docs/group-wavelength-ui.md`, Phase 6): a member with a depositor statement
 * asks, the operator's daemon makes an address, and every device checks it's one the group's key makes under the statement's operator and
 * delay before believing it's that member's. So a hostile operator can't hand out an address of its own, and can't give one member
 * another's. What a deposit is worth then comes from the round a device itself signed, once it has seen that round happen.
 *
 * A Lightning invoice takes the same request and answer, and a device checks all it can of one. It can't check who gets paid (spike 6), so
 * there a member takes the operator's word, and is told that before they use it.
 */
object GroupWavelengthDeposits {
    private val logger = Logger.withTag("GroupWavelengthDeposits")

    /** Well inside the 512 keys a device checks an address against, shared among a group. */
    private const val MAX_ADDRESSES_A_MEMBER = 16

    private const val MAX_INVOICES_WAITING = 3

    /** Asks for an address for [userPublicKey]. Whichever device hears the answer checks it. */
    suspend fun request(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, invoiceAmountSat: Long? = null): String {
        val key = GroupArkCoSigner.walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val requestId = RandomInstance.bytes(16).toHex()
        database.groupArkDao().insertDepositAddress(GroupArkDepositAddress(key, requestId, userPublicKey.lowercase(), Clock.System.now(), invoiceAmountSat = invoiceAmountSat))
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.DEPOSIT_REQUEST, CommonJson.encodeToString(GroupWavelengthDepositRequest.serializer(), GroupWavelengthDepositRequest(requestId, key, invoiceAmountSat)))
        return requestId
    }

    suspend fun process(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        runCatching {
            when (innerEvent.kind) {
                GroupWavelengthEvents.DEPOSIT_REQUEST -> {
                    val request = CommonJson.decodeFromString(GroupWavelengthDepositRequest.serializer(), innerEvent.content)
                    fileRequest(database, localChatRoom.chatRoom.id, innerEvent.pubKey, request, Instant.fromEpochSeconds(innerEvent.createdAt))
                }
                GroupWavelengthEvents.DEPOSIT_ANSWER -> {
                    val answer = CommonJson.decodeFromString(GroupWavelengthDepositAnswer.serializer(), innerEvent.content)
                    receiveAnswer(database, localChatRoom.chatRoom.id, innerEvent.pubKey, answer)
                }
            }
        }.onFailure { logger.w("Could not read deposit message ${innerEvent.id}: ${it.message}") }
    }

    private suspend fun fileRequest(database: DedunDatabase, chatRoomId: String, from: HexKey, request: GroupWavelengthDepositRequest, at: Instant) {
        val (chain, _) = GroupArkCoSigner.parseWalletKey(chatRoomId, request.wallet) ?: return
        if (from.lowercase() !in Depositors.statementsIn(database, chatRoomId, chain).keys.map { it.lowercase() }) return logger.w("A request for an address from someone who hasn't started depositing")
        val theirs = database.groupArkDao().depositAddresses(request.wallet).filter { it.requester == from.lowercase() }
        val amount = request.invoiceAmountSat
        if (amount != null) {
            // Below a coin's least it would land as credit, which no threshold guards (D10), and past a coin's most the server won't take it.
            val terms = GroupWavelengthTerms.of(chain)
            if (amount <= 0 || (terms != null && amount !in terms.minVtxoSat..terms.maxVtxoSat)) return logger.w("An invoice asked for outside what a coin can hold")
            // Each one takes the group's signatures, so a member can't keep asking while the last few are unanswered.
            if (theirs.count { it.invoiceAmountSat != null && it.invoice == null } >= MAX_INVOICES_WAITING) return logger.w("Too many invoices asked for by one member")
        } else if (theirs.count { it.invoiceAmountSat == null } >= MAX_ADDRESSES_A_MEMBER) {
            // A member can ask without limit, and each address is one more key the group's window has to cover.
            return logger.w("Too many addresses asked for by one member")
        }
        database.groupArkDao().insertDepositAddress(GroupArkDepositAddress(request.wallet, request.requestId, from.lowercase(), at, invoiceAmountSat = amount))
    }

    /** What the operator's device does with each request that's waiting. */
    suspend fun answerOutstanding(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, term: Long, asked: MutableSet<String> = HashSet(), newInvoice: suspend (amountSat: Long) -> String = { error("No way to make an invoice") }, newAddress: suspend () -> String) {
        val roomId = localChatRoom.chatRoom.id
        val key = GroupArkCoSigner.walletKey(roomId, wallet.statement.chain, wallet.statement.wallet)
        // Each once a run, or one a device refuses would use up another address every time.
        database.groupArkDao().depositAddresses(key).filter { it.address == null && it.invoice == null && asked.add(it.requestId) }.forEach { row ->
            val amount = row.invoiceAmountSat
            val answer = try {
                if (amount != null) GroupWavelengthDepositAnswer(row.requestId, key, term, invoice = newInvoice(amount))
                else GroupWavelengthDepositAnswer(row.requestId, key, term, address = newAddress())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was given out, so it can be tried again: an invoice takes the group's signatures, and they may not be around just now.
                logger.w(e) { "Couldn't answer request ${row.requestId.take(8)}" }
                asked.remove(row.requestId)
                return@forEach
            }
            GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.DEPOSIT_ANSWER, CommonJson.encodeToString(GroupWavelengthDepositAnswer.serializer(), answer))
            receiveAnswer(database, roomId, userPublicKey, answer)
        }
    }

    private suspend fun receiveAnswer(database: DedunDatabase, chatRoomId: String, from: HexKey, answer: GroupWavelengthDepositAnswer) {
        val (chain, number) = GroupArkCoSigner.parseWalletKey(chatRoomId, answer.wallet) ?: return
        val operator = GroupWavelengthOperatorManager.ofTerm(database, chatRoomId, chain, number, answer.term)
        if (operator?.publicKey != from.lowercase()) return logger.w("An address from someone other than the operator of term ${answer.term}")
        val wallet = GroupWavelengthWalletManager.wallets(database, chatRoomId, chain).firstOrNull { it.statement.wallet == number } ?: return
        val dao = database.groupArkDao()
        val rows = dao.depositAddresses(answer.wallet)
        val row = rows.firstOrNull { it.requestId == answer.requestId } ?: return
        if (row.invoiceAmountSat != null || answer.invoice != null) return receiveInvoice(database, chatRoomId, chain, number, answer, row, rows)
        val script = PayoutAddress.parseOrNull(chain, answer.address)?.script
        val statement = wallet.statement
        val isTheGroups = script != null && GroupArkCoSigner.keysOf(wallet).isBoardingScript(script, PublicKey.fromHex(statement.arkOperatorKey), statement.policy.boardingDelay)
        // One member's for good, or a deposit there couldn't be said to be anyone's.
        val isSomeoneElses = script != null && rows.any { it.script == script.toHex() && it.requester != row.requester }
        if (!isTheGroups || isSomeoneElses) {
            val reader = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey ?: return
            GroupLightningLines.writeFact(
                database, chatRoomId, ChatMessage.TYPE_WAVELENGTH_REFUSED, "address:${answer.address}", reader,
                if (isSomeoneElses) "The Wavelength wallet's operator gave one member an address it had already given another. Your device won't count it as either's."
                else "The Wavelength wallet's operator gave out an address your device can't confirm is the group's. Don't send anything to it.",
                Clock.System.now(),
            )
            return
        }
        dao.answerDepositAddress(answer.wallet, answer.requestId, answer.address, script.toHex())
    }

    /**
     * What a device can check of an invoice: that it was asked for, on this chain, for that amount, and that its hash is nobody else's, since
     * that's whose a payment to it is. It can't check who gets paid (spike 6), and the member's screen says so.
     */
    private suspend fun receiveInvoice(database: DedunDatabase, chatRoomId: String, chain: Chain, number: Long, answer: GroupWavelengthDepositAnswer, row: GroupArkDepositAddress, rows: List<GroupArkDepositAddress>) {
        val invoice = answer.invoice
        val read = invoice?.let { GroupWavelengthPaymentEvent.Payment(chain, number, it, 0, 0, 0) }
        val hash = read?.paymentHash?.toHex()
        val isWhatWasAsked = hash != null && row.invoiceAmountSat != null && read.amountSat == row.invoiceAmountSat
        val isSomeoneElses = hash != null && rows.any { it.paymentHash == hash && it.requester != row.requester }
        if (!isWhatWasAsked || isSomeoneElses) {
            val reader = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey ?: return
            GroupLightningLines.writeFact(
                database, chatRoomId, ChatMessage.TYPE_WAVELENGTH_REFUSED, "invoice:${answer.requestId}", reader,
                if (isSomeoneElses) "The Wavelength wallet's operator gave one member an invoice it had already given another. Your device won't count it as either's."
                else "The Wavelength wallet's operator answered a request with an invoice that isn't what was asked for. Don't pay it.",
                Clock.System.now(),
            )
            return
        }
        database.groupArkDao().answerDepositInvoice(answer.wallet, answer.requestId, invoice!!, hash!!)
    }

    /**
     * A claim this device signed and has now seen settle: the Ark server co-signed it and `t` of the group signed it, so it's real money,
     * and it's the deposit of whoever was given that hash, or nobody's yet. A refund has no row here, since that's the group's own coming back.
     */
    suspend fun claimSettled(database: DedunDatabase, wallet: String, ark: String) {
        val dao = database.groupArkDao()
        val claim = dao.claim(wallet, ark) ?: return
        val roomId = wallet.split(":").getOrNull(1) ?: return
        val (chain, _) = GroupArkCoSigner.parseWalletKey(roomId, wallet) ?: return
        val member = dao.depositAddresses(wallet).firstOrNull { it.paymentHash == claim.paymentHash }?.requester
        DepositLedger.merge(
            database,
            DepositRow(
                chatRoomId = roomId,
                chain = GroupWalletStatementEvent.chainName(chain),
                depositKey = DepositAttributionEvent.DepositKey.Lightning(claim.paymentHash.lowercase()).text,
                source = DepositRow.WAVELENGTH,
                amountMsat = claim.amountSat * 1000,
                member = member,
                attributedBy = DepositRow.BY_INVOICE.takeIf { member != null },
                confirmedAt = Clock.System.now(),
            ),
        )
        dao.forgetClaim(wallet, ark)
    }

    /** The boarding coins of a round this device has now seen happen: deposits, each the member's whose address it paid, or nobody's yet. */
    suspend fun roundHappened(database: DedunDatabase, chatRoomId: String, chain: String, wallet: String, commitment: String) {
        val dao = database.groupArkDao()
        val addresses = dao.depositAddresses(wallet)
        dao.boardedIn(wallet, commitment).forEach { coin ->
            val member = addresses.firstOrNull { it.script == coin.script }?.requester
            val (txid, vout) = coin.outpoint.split(":").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: return@forEach) }
            DepositLedger.merge(
                database,
                DepositRow(
                    chatRoomId = chatRoomId,
                    chain = chain,
                    depositKey = DepositAttributionEvent.DepositKey.Onchain(txid.lowercase(), vout).text,
                    source = DepositRow.WAVELENGTH,
                    amountMsat = coin.amountSat * 1000,
                    member = member,
                    attributedBy = DepositRow.BY_BOARDING_ADDRESS.takeIf { member != null },
                    confirmedAt = Clock.System.now(),
                ),
            )
        }
        dao.forgetBoarded(wallet, commitment)
    }
}
