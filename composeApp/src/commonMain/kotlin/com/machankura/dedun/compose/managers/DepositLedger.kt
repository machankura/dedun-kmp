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
     * Files each swap-in coin a snapshot's splice-ins spent (`docs/group-lightning-swap-in.md`, Phase 5): a
     * Lightning deposit of the coin less its share of the splice's fee (D5), shared by amount. The operator
     * names the coin's depositor and address; this device derives that address and counts the coin to the
     * member only when the script is theirs -- a coin named on another member's address is nobody's.
     */
    suspend fun recordSwapIns(database: DedunDatabase, chatRoomId: String, wallet: GroupLightningWalletManager.Wallet, payments: List<WalletWire.PaymentView>) {
        val statement = wallet.statement
        val keys = GroupWalletManager.thresholdKeyFor(database, chatRoomId)?.let { roomKey ->
            GroupSwapInDerivation.lspSwapInXpub(statement.chain)?.let { xpub -> GroupSwapInDerivation.keys(roomKey, statement.chain, statement.wallet, statement.icebergKey, statement.nodeId, xpub) }
        }
        val depositors = Depositors.statementsIn(database, chatRoomId, statement.chain)
        payments.filter { it.incoming && it.succeeded && it.swapIns.isNotEmpty() }.forEach { payment ->
            val totalMsat = payment.swapIns.sumOf { it.amountSat * 1000 }
            val shares = feeShares(payment.swapIns.map { it.amountSat * 1000 }, (totalMsat - payment.amountMsat).coerceAtLeast(0))
            payment.swapIns.zip(shares).forEach { (coin, share) ->
                val verified = keys?.let { k ->
                    depositors[coin.member.lowercase()]?.let { d -> k.address(d.branch, coin.index).protocol.serializedPubkeyScript.toHex() == coin.script.lowercase() }
                } == true
                val (txid, vout) = coin.outPoint.split(':').let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: return@forEach) }
                merge(
                    database,
                    DepositRow(
                        chatRoomId = chatRoomId,
                        chain = GroupWalletStatementEvent.chainName(statement.chain),
                        depositKey = DepositAttributionEvent.DepositKey.SwapIn(txid.lowercase(), vout).text,
                        source = DepositRow.LIGHTNING,
                        amountMsat = coin.amountSat * 1000 - share,
                        member = coin.member.lowercase().takeIf { verified },
                        attributedBy = DepositRow.BY_SWAP_IN.takeIf { verified },
                        confirmedAt = Clock.System.now(),
                    ),
                )
            }
        }
    }

    /** [feeMsat] shared over [amountsMsat] in proportion, the remainder on the last, so the shares add up to the fee. */
    fun feeShares(amountsMsat: List<Long>, feeMsat: Long): List<Long> {
        val total = amountsMsat.sum()
        if (total == 0L) return amountsMsat.map { 0L }
        val shares = amountsMsat.dropLast(1).map { feeMsat * it / total }
        return shares + (feeMsat - shares.sum())
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
     * What the group has paid each member: onchain, the outputs of its confirmed spends that pay an
     * address the member signed -- any they ever signed, so a payout still counts after they give a
     * new one; over Lightning, the payments the group signed for a member's destination that this
     * room's wallet reports sent. `docs/payouts-only.md`, Phase 2.
     */
    private suspend fun paidOut(database: DedunDatabase, chatRoomId: String, chain: Chain): Map<HexKey, Long> {
        val paid = mutableMapOf<HexKey, Long>()
        val destinations = GroupPayoutDestinations.everSignedIn(database, chatRoomId, chain)
        database.groupTransactionDao().getByChatRoomId(chatRoomId)
            .filter { it.confirmedAt != null && it.chain == GroupWalletStatementEvent.chainName(chain) }
            .mapNotNull { row -> row.signedTransaction?.let { runCatching { Transaction.read(it) }.getOrNull() } }
            .forEach { tx ->
                val payees = GroupPayoutDestinations.payeesOf(tx.txOut.map { it.publicKeyScript }, destinations) { it }
                tx.txOut.forEach { out -> payees[out.publicKeyScript.toHex()]?.let { member -> paid[member] = (paid[member] ?: 0) + out.amount.sat * 1000 } }
            }
        lightningPayouts(database, chatRoomId, chain).forEach { (pay, _) ->
            paid[pay.payout!!.member] = (paid[pay.payout.member] ?: 0) + pay.amountMsat
        }
        return paid
    }

    /**
     * Every payment the group signed for a member's destination on [chain] that the room's own
     * wallet reports sent, with the fee it reports: a signed authorisation that verifies, matched by
     * hash against the payments of the wallet it names -- never another room's.
     */
    suspend fun lightningPayouts(database: DedunDatabase, chatRoomId: String, chain: Chain): List<Pair<GroupLightningAuthorisationEvent.Pay, Long>> =
        signedPayouts(database, chatRoomId, chain).mapNotNull { pay ->
            sentFees(database, chatRoomId, chain, pay.wallet)[pay.paymentHash.lowercase()]?.let { pay to it }
        }

    /** Every payment authorisation for a member's destination on [chain] the room signed, verified. */
    suspend fun signedPayouts(database: DedunDatabase, chatRoomId: String, chain: Chain): List<GroupLightningAuthorisationEvent.Pay> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningAuthorisationEvent.KIND)
            .filter { it.verifies() }
            .mapNotNull { GroupLightningAuthorisationEvent.read(it.toEvent().tags) }
            .filter { it.chain == chain && it.payout != null }
            .distinctBy { it.paymentHash.lowercase() }

    /** The fee of each outgoing payment [wallet] of [chatRoomId] reports settled, by payment hash. */
    suspend fun sentFees(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long): Map<String, Long> =
        database.groupLightningRecordsDao().payments(GroupLightningCoSigner.walletKey(chatRoomId, chain, wallet))
            .filter { !it.incoming && it.succeeded && it.paymentHash != null }
            .associate { it.paymentHash!!.lowercase() to it.feesMsat }

    /**
     * Why a signer won't sign [proposed], a deposit attribution for [chatRoomId], or null when it will
     * (D10): every deposit it names is one this device has on record, for the amount it says, and
     * every member it names is somebody the group has opened deposits for. Whether the deposit was
     * unattributed is not asked: correcting an attribution is what a second one is for, and the
     * screen says when one is a correction. `docs/member-deposits.md`, Phase 9.
     */
    suspend fun refuseAttribution(database: DedunDatabase, chatRoomId: String, proposed: com.vitorpamplona.quartz.nip01Core.core.Event): String? {
        val signed = DepositAttributionEvent.read(proposed) ?: return "does not read as an attribution"
        val depositors = Depositors.statementsIn(database, chatRoomId, signed.chain)
        signed.attributions.forEach { attribution ->
            val row = database.depositDao().get(chatRoomId, attribution.key.text) ?: return "names a deposit this device has not seen"
            if (row.chain != GroupWalletStatementEvent.chainName(signed.chain)) return "names a deposit on another network"
            val amount = if (row.source == DepositRow.ONCHAIN) row.amountMsat / 1000 else row.amountMsat
            if (amount != attribution.amount) return "names a deposit for another amount than this device has"
            if (attribution.member != null && attribution.member !in depositors) return "gives a deposit to somebody the group has not opened deposits for"
        }
        return null
    }

    /**
     * Asks the group to sign whose [attributions] are -- a signing session over one 30340, which
     * proposing counts as approving. Each amount is the one on record: sats onchain, msat over
     * Lightning, as the attribution says them.
     */
    suspend fun proposeAttribution(
        database: DedunDatabase,
        localChatRoom: com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom,
        proposerPublicKey: HexKey,
        chain: Chain,
        assignments: Map<String, HexKey?>,
    ): com.machankura.dedun.compose.database.model.FrostSigningSession {
        val chatRoomId = localChatRoom.chatRoom.id
        val attributions = assignments.map { (depositKey, member) ->
            val row = database.depositDao().get(chatRoomId, depositKey) ?: throw IllegalStateException("No deposit $depositKey on record")
            val key = DepositAttributionEvent.DepositKey.parse(depositKey) ?: throw IllegalStateException("Not a deposit: $depositKey")
            DepositAttributionEvent.Attribution(key, member?.lowercase(), if (row.source == DepositRow.ONCHAIN) row.amountMsat / 1000 else row.amountMsat)
        }
        val template = DepositAttributionEvent.template(chain, attributions)
        return FrostSigningManager.proposeSigning(database, localChatRoom, proposerPublicKey, template.kind, template.tags, template.content)
    }
}
