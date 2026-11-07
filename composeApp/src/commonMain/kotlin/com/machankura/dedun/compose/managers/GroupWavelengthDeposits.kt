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
 * Lightning isn't here: nothing can yet prove an invoice pays the group (spike 6).
 */
object GroupWavelengthDeposits {
    private val logger = Logger.withTag("GroupWavelengthDeposits")

    /** Asks for an address for [userPublicKey]. Whichever device hears the answer checks it. */
    suspend fun request(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet): String {
        val key = GroupArkCoSigner.walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val requestId = RandomInstance.bytes(16).toHex()
        database.groupArkDao().insertDepositAddress(GroupArkDepositAddress(key, requestId, userPublicKey.lowercase(), Clock.System.now()))
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.DEPOSIT_REQUEST, CommonJson.encodeToString(GroupWavelengthDepositRequest.serializer(), GroupWavelengthDepositRequest(requestId, key)))
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
        database.groupArkDao().insertDepositAddress(GroupArkDepositAddress(request.wallet, request.requestId, from.lowercase(), at))
    }

    /** What the operator's device does with each request that's waiting. */
    suspend fun answerOutstanding(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, term: Long, newAddress: suspend () -> String) {
        val roomId = localChatRoom.chatRoom.id
        val key = GroupArkCoSigner.walletKey(roomId, wallet.statement.chain, wallet.statement.wallet)
        database.groupArkDao().depositAddresses(key).filter { it.address == null }.forEach { row ->
            val answer = GroupWavelengthDepositAnswer(row.requestId, key, term, newAddress())
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
