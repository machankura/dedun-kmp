package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Transaction
import kotlin.time.Clock

/**
 * Whose each deposit into a group is, and what each member has put in altogether:
 * `docs/member-deposits.md`, Phase 8.
 *
 * Deposits are recorded as they are learned -- from the wallet's branches and their history, and
 * from the operator's snapshots -- and kept, so a deposit the group has spent since still counts.
 * A tally is deposits in: not a share of the balance, which fees and liquidity costs make smaller
 * than the deposits by an amount the group has not decided how to split (D8).
 */
object DepositLedger {

    /**
     * Files [deposits], the outputs that paid [chatRoomId]'s receive or deposit branches on [chain].
     * One the room's own signed spends made -- change, or the group paying itself -- is not a
     * deposit. A member's branch is that member's; the receive branch is nobody's until the group
     * signs whose (Phase 9).
     */
    suspend fun recordOnchain(database: DedunDatabase, chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>) {
        val own = database.groupTransactionDao().getByChatRoomId(chatRoomId).map { it.txid.lowercase() }.toSet()
        val byBranch = Depositors.statementsIn(database, chatRoomId, chain).values.associate { it.branch to it.member }
        deposits.filter { it.outPoint.txid.toString().lowercase() !in own }.forEach { deposit ->
            val member = byBranch[deposit.branch]
            merge(
                database,
                DepositRow(
                    chatRoomId = chatRoomId,
                    chain = GroupWalletStatementEvent.chainName(chain),
                    depositKey = DepositAttributionEvent.DepositKey.Onchain(deposit.outPoint.txid.toString(), deposit.outPoint.index).text,
                    source = DepositRow.ONCHAIN,
                    amountMsat = deposit.amount.sat * 1000,
                    member = member,
                    attributedBy = member?.let { DepositRow.BY_BRANCH },
                    confirmedAt = if (deposit.confirmed) Clock.System.now() else null,
                ),
            )
        }
    }

    /**
     * Files the incoming payments of a snapshot for [walletKey]: each one this device can join to an
     * invoice it holds is that invoice's requester's, and one only the snapshot joins is the
     * requester the operator named, on its word (D6). A liquidity purchase is not a deposit.
     */
    suspend fun recordLightning(database: DedunDatabase, chatRoomId: String, chain: Chain, walletKey: String, payments: List<WalletWire.PaymentView>) {
        payments.filter { it.incoming && it.succeeded && it.kind == WalletWire.PAYMENT && it.paymentHash != null }.forEach { payment ->
            val hash = payment.paymentHash!!.lowercase()
            val held = database.groupLightningRecordsDao().invoicesByHash(walletKey, hash).singleOrNull()?.requester
            val (member, how) = when {
                held != null -> held to DepositRow.BY_INVOICE
                payment.requester != null -> payment.requester.lowercase() to DepositRow.BY_OPERATOR
                else -> null to null
            }
            merge(
                database,
                DepositRow(
                    chatRoomId = chatRoomId,
                    chain = GroupWalletStatementEvent.chainName(chain),
                    depositKey = DepositAttributionEvent.DepositKey.Lightning(hash).text,
                    source = DepositRow.LIGHTNING,
                    amountMsat = payment.amountMsat,
                    member = member,
                    attributedBy = how,
                    confirmedAt = Clock.System.now(),
                ),
            )
        }
    }

    /**
     * Files [incoming] beside what is on record for its deposit: the first sighting is kept, a
     * confirmation once seen stays, and the attribution only ever grows surer -- a branch or a held
     * invoice is not overwritten by the operator's word, and nothing overwrites the group's own.
     */
    internal suspend fun merge(database: DedunDatabase, incoming: DepositRow) {
        val dao = database.depositDao()
        val known = dao.get(incoming.chatRoomId, incoming.depositKey)
        if (known == null) return dao.upsert(incoming)
        val keepKnown = DepositRow.strength(known.attributedBy) >= DepositRow.strength(incoming.attributedBy)
        dao.upsert(
            known.copy(
                amountMsat = incoming.amountMsat,
                member = if (keepKnown) known.member else incoming.member,
                attributedBy = if (keepKnown) known.attributedBy else incoming.attributedBy,
                confirmedAt = known.confirmedAt ?: incoming.confirmedAt,
            )
        )
    }

    /** One member's deposits in, and what the group has paid out to them. */
    data class Tally(val member: HexKey, val onchainMsat: Long = 0, val lightningMsat: Long = 0, val paidOutMsat: Long = 0) {
        val depositedMsat: Long get() = onchainMsat + lightningMsat
    }

    /**
     * Every member's tally on [chain], the deposits nobody's yet, and the total. The members' and the
     * unattributed add up to the total by construction, so a deposit missing from a tally shows as
     * a mismatch against the chain, never as nothing.
     */
    data class Tallies(val members: Map<HexKey, Tally>, val unattributedMsat: Long, val totalMsat: Long)

    suspend fun tallies(database: DedunDatabase, chatRoomId: String, chain: Chain): Tallies {
        val rows = depositsIn(database, chatRoomId, chain)
        val tallies = mutableMapOf<HexKey, Tally>()
        var unattributed = 0L
        rows.forEach { row ->
            val member = row.member ?: return@forEach run { unattributed += row.amountMsat }
            val tally = tallies.getOrPut(member) { Tally(member) }
            tallies[member] = if (row.source == DepositRow.ONCHAIN) tally.copy(onchainMsat = tally.onchainMsat + row.amountMsat)
            else tally.copy(lightningMsat = tally.lightningMsat + row.amountMsat)
        }
        paidOut(database, chatRoomId, chain).forEach { (member, msat) ->
            val tally = tallies.getOrPut(member) { Tally(member) }
            tallies[member] = tally.copy(paidOutMsat = tally.paidOutMsat + msat)
        }
        return Tallies(tallies, unattributed, rows.sumOf { it.amountMsat })
    }

    /**
     * Every deposit on record for [chatRoomId] on [chain], newest first, with the group's signed
     * attribution of each applied over what this device found (D10): the group's word is the last.
     */
    suspend fun depositsIn(database: DedunDatabase, chatRoomId: String, chain: Chain): List<DepositRow> {
        val signed = DepositAttributionEvent.newestPerDeposit(
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, DepositAttributionEvent.KIND).map { it.toEvent() },
            chatRoomId,
            chain,
        )
        return database.depositDao().inRoom(chatRoomId, GroupWalletStatementEvent.chainName(chain)).map { row ->
            val attribution = DepositAttributionEvent.DepositKey.parse(row.depositKey)?.let { signed[it]?.first } ?: return@map row
            row.copy(member = attribution.member, attributedBy = DepositRow.BY_GROUP)
        }
    }

    /**
     * What the group has paid each member: onchain, the outputs of its confirmed spends that pay a
     * member's own signed payout address, matched on this device; over Lightning, the payments the
     * group authorised for a member's destination that the node reports sent.
     */
    private suspend fun paidOut(database: DedunDatabase, chatRoomId: String, chain: Chain): Map<HexKey, Long> {
        val paid = mutableMapOf<HexKey, Long>()
        val destinations = GroupPayoutDestinations.destinationsIn(database, chatRoomId, chain).values
        database.groupTransactionDao().getByChatRoomId(chatRoomId)
            .filter { it.confirmedAt != null && it.chain == GroupWalletStatementEvent.chainName(chain) }
            .mapNotNull { row -> row.signedTransaction?.let { runCatching { Transaction.read(it) }.getOrNull() } }
            .forEach { tx ->
                val payees = GroupPayoutDestinations.payeesOf(tx.txOut.map { it.publicKeyScript }, destinations) { it }
                tx.txOut.forEach { out -> payees[out.publicKeyScript.toHex()]?.let { member -> paid[member] = (paid[member] ?: 0) + out.amount.sat * 1000 } }
            }
        val sent = database.groupLightningRecordsDao().allPayments().filter { !it.incoming && it.succeeded }.mapNotNull { it.paymentHash?.lowercase() }.toSet()
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningAuthorisationEvent.KIND)
            .mapNotNull { GroupLightningAuthorisationEvent.read(it.toEvent().tags) }
            .filter { it.chain == chain && it.payout != null && it.paymentHash.lowercase() in sent }
            .forEach { pay -> paid[pay.payout!!.member] = (paid[pay.payout.member] ?: 0) + pay.amountMsat }
        return paid
    }
}
