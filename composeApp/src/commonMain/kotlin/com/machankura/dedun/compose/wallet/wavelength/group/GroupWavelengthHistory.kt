package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkRound
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock

/**
 * One thing a group's Wavelength wallet did, as a screen reads it:
 * `docs/group-wavelength-transaction-details.md`, T2.
 *
 * Built from what this device itself signed and filed, with the operator's claim joined onto it and
 * never the other way round, so [source] always says whose word a row is. [detail] carries the rows it
 * was built from, which is what the technical sheet reads: nothing is re-queried to show one.
 */
data class GroupWavelengthEntry(
    /** `<kind>:<key>`, stable across a row's whole life, and never parsed back into a key (T4). */
    val id: String,
    val kind: Kind,
    val status: Status,
    val source: Source,
    /** What moved, in sats, never negative. Zero for a round, which only costs. */
    val amountSat: Long,
    /** What it cost, as far as is known: a round's own fee, or one payout's share of it. */
    val feeSat: Long,
    /** Unix seconds, or null where nobody holds a time for it. Such a row sorts last and says so. */
    val at: Long?,
    /** Whose it is: the member who deposited or was paid. Null for the group's own, and for the unattributed. */
    val member: HexKey? = null,
    /** Which way the money went, as far as can be told: a line of the operator's may not say. */
    val direction: Direction,
    val detail: Detail,
) {
    enum class Kind {
        /** A boarding coin: money paid to an address this device recomputed as a member's. */
        DEPOSIT_ONCHAIN,

        /** A Lightning payment claimed into the wallet. Nothing can prove where its invoice paid (spike 6). */
        DEPOSIT_LIGHTNING,

        PAYOUT_LIGHTNING,
        PAYOUT_ONCHAIN,

        /** A round that moved the group's own coins and cost it a fee: no money in or out (T10). */
        ROUND,

        /** The wallet leaving to the group's onchain wallet. */
        EXIT,

        /** Something only the operator says happened. */
        OPERATOR,
    }

    enum class Status {
        /** Asked for, approved, or sitting at an address: nothing has moved. */
        WAITING,

        /**
         * Money this device has seen on the chain, paying a script it checked, that nothing has signed for yet
         * (`docs/group-wavelength-unconfirmed-deposits.md`, A5). Not [WAITING], because money has moved, and not
         * [IN_FLIGHT], because nothing has been signed. The one state whose answer to a member is *wait, and here
         * is how long*.
         */
        ARRIVING,

        /** Signed for, and this device hasn't seen it take effect. */
        IN_FLIGHT,
        DONE,
        FAILED,

        /** Its approval ran out before anything paid it. There is nothing left to wait for. */
        EXPIRED,
    }

    enum class Direction { IN, OUT, NONE, UNKNOWN }

    enum class Source {
        /** This device's own record, start to finish. */
        THIS_DEVICE,

        /** This device's record, with the operator's claim filling in what it can't see. */
        BOTH,

        /** The two disagree. The device's figure is the headline. */
        MISMATCH,

        /** The operator's word alone: this device has no record of it. */
        THE_OPERATOR,
    }

    /** Whether a member can still be told something will change. */
    val isSettled: Boolean get() = status == Status.DONE || status == Status.FAILED || status == Status.EXPIRED

    /** The rows an entry was built from, for the technical sheet. */
    sealed interface Detail {
        data class OnchainDeposit(
            /** Null once the round happened: the boarding row is deleted then, and nothing joins the address back. */
            val boarded: GroupArkBoarded? = null,
            val deposit: DepositRow? = null,
            /** The coin the round made from it, where this device holds one. */
            val coin: GroupArkVtxo? = null,
            /** Null once a round has it: a boarding payment this device has seen on chain and nothing has signed for. */
            val arriving: Arriving? = null,
        ) : Detail

        /**
         * A boarding payment on the chain, with how far along it is worked out against the tip rather than stored
         * (`docs/group-wavelength-unconfirmed-deposits.md`, A6), so that nothing is re-queried to show one.
         */
        data class Arriving(
            val row: GroupArkArriving,
            /** 0 in the mempool, 1 in the block it is in, and 1 where the tip isn't known. */
            val depth: Int,
            /** How deep the operator needs it before it can join a round: six blocks on mainnet. */
            val needed: Int,
        )

        data class LightningDeposit(
            val request: GroupArkDepositAddress? = null,
            /** Null once it settled: the claim is deleted then. */
            val claim: GroupArkClaim? = null,
            val deposit: DepositRow? = null,
        ) : Detail

        data class LightningPayout(
            val approval: GroupWavelengthPaymentEvent.Payment,
            /** What this device signed the payment into, with what its vHTLC held. */
            val signed: GroupArkPayment? = null,
            /** The operator's line about it, which is the only word on whether it arrived. */
            val line: GroupWavelengthSnapshot.Line? = null,
            /**
             * Whether it failed before this device signed anything of it, so nothing of it left the wallet
             * and the member's balance is free again (`GroupWavelengthPayouts.wentNowhere`). A different
             * thing from a payout that failed, and the only one of the two a member can act on.
             */
            val wentNowhere: Boolean = false,
        ) : Detail

        data class OnchainPayout(
            val approval: GroupWavelengthOnchainPayoutEvent.Payout,
            val leave: GroupArkLeave? = null,
        ) : Detail

        data class Round(
            /** Null for a transfer, which costs a fee and files no round. */
            val round: GroupArkRound? = null,
            val fee: GroupArkFee? = null,
            /** The coins this round made, by this device's own record. */
            val made: List<GroupArkVtxo> = emptyList(),
        ) : Detail

        data class Exit(val approval: GroupWavelengthExitEvent.Exit) : Detail

        data class OperatorLine(
            val line: GroupWavelengthSnapshot.Line,
            val term: Long,
            val sentAt: Long,
            val tip: Long,
        ) : Detail
    }
}

/**
 * Everything a group's Wavelength wallet has done, from this device's own records and the operator's
 * newest claim: `docs/group-wavelength-transaction-details.md`, Phase 1.
 *
 * Pure, like [GroupWavelengthLedger], so a test drives it with no database and no daemon. Built on read
 * rather than stored, for the reason the ledger is: a second record of what the co-signer already
 * records could disagree with the first, and the first is what it refuses on.
 */
object GroupWavelengthHistory {
    /** What the operator's daemon calls a line it has finished with, and one that didn't go: `EntryStatus.wire`. */
    const val LINE_COMPLETE = "complete"
    const val LINE_FAILED = "failed"

    /** `EntryKind.wire`, which is all a line says about which way its money went. */
    const val LINE_SEND = "send"
    const val LINE_RECEIVE = "receive"
    const val LINE_DEPOSIT = "deposit"
    const val LINE_EXIT = "exit"

    /** Everything [of] reads. One record so a view model combines two flows rather than eleven. */
    data class Rows(
        /** This device's own coins, settled and pending. */
        val coins: List<GroupArkVtxo> = emptyList(),
        /** Boarding coins signed into a round this device hasn't seen happen. */
        val boarded: List<GroupArkBoarded> = emptyList(),
        /** Boarding payments this device has seen on chain that no round has taken in: `GroupArkBoardingWatch`. */
        val arriving: List<GroupArkArriving> = emptyList(),
        /** The chain's tip, for how deep an arriving payment is. Null where this device hasn't reached a server. */
        val tip: Long? = null,
        /** How deep the operator needs a boarding payment before it can join a round, from its own terms. */
        val boardingConfirmations: Int = 1,
        /** Lightning claims signed and not seen settle. */
        val claims: List<GroupArkClaim> = emptyList(),
        val payments: List<GroupArkPayment> = emptyList(),
        val leaves: List<GroupArkLeave> = emptyList(),
        val fees: List<GroupArkFee> = emptyList(),
        val rounds: List<GroupArkRound> = emptyList(),
        /** Deposit requests and what the operator answered, for the invoices nobody has paid. */
        val addresses: List<GroupArkDepositAddress> = emptyList(),
        /** Deposits this device has seen land, which outlive the rows above (`docs/member-deposits.md`). */
        val deposits: List<DepositRow> = emptyList(),
        val payouts: List<GroupWavelengthPaymentEvent.Payment> = emptyList(),
        /**
         * When the group signed each Lightning payout, by payment hash. An approval carries no time of its own, so
         * without this a payout waiting on the operator has none either.
         */
        val payoutsAskedAt: Map<HexKey, Long> = emptyMap(),
        /** The payment hashes of [payouts] that failed before anything of them was signed. */
        val payoutsWentNowhere: Set<HexKey> = emptySet(),
        val onchainPayouts: List<GroupWavelengthOnchainPayoutEvent.Payout> = emptyList(),
        val exits: List<GroupWavelengthExitEvent.Exit> = emptyList(),
    )

    /**
     * [rows] and [snapshot] as one history, newest first. A row with no time of its own sorts last, in a
     * stable order, rather than being given the operator's clock to sort by (T3).
     */
    fun of(rows: Rows, snapshot: GroupWavelengthSnapshot? = null, nowSeconds: Long = Clock.System.now().epochSeconds): List<GroupWavelengthEntry> {
        val lines = snapshot?.activity.orEmpty().distinctBy { it.id }
        val byHash = lines.filter { it.paymentHash.isNotBlank() }.associateBy { it.paymentHash.lowercase() }
        // The lines a device-known entry has spoken for. Whatever is left is the operator's word alone.
        val spoken = HashSet<String>()
        val entries = ArrayList<GroupWavelengthEntry>()

        entries += deposits(rows, byHash, spoken)
        entries += lightningPayouts(rows, byHash, spoken, nowSeconds)
        entries += onchainPayouts(rows, nowSeconds)
        entries += rounds(rows)
        entries += exits(rows, nowSeconds)
        entries += operatorOnly(lines, spoken, snapshot)

        // Newest first; a row with no time of its own goes after every row that has one, ordered by id so
        // that two reads of the same wallet never disagree.
        return entries.sortedWith(compareByDescending<GroupWavelengthEntry> { it.at ?: Long.MIN_VALUE }.thenBy { it.id })
    }

    // ---------------------------------------------------------------------------------------------
    // Money coming in. A deposit outlives the rows that made it: `roundHappened` and `claimSettled`
    // file a DepositRow and then forget the boarding coin or the claim, so the three states are three
    // different tables and the id has to be the same string in all of them.
    // ---------------------------------------------------------------------------------------------

    private fun deposits(rows: Rows, byHash: Map<String, GroupWavelengthSnapshot.Line>, spoken: MutableSet<String>): List<GroupWavelengthEntry> {
        val entries = ArrayList<GroupWavelengthEntry>()
        val landed = rows.deposits.filter { it.source == DepositRow.WAVELENGTH }
        val keys = landed.map { it.depositKey.lowercase() }.toSet()
        val coins = rows.coins.associateBy { it.outpoint.lowercase() }

        landed.forEach { row ->
            val hash = lightningHashOf(row.depositKey)
            if (hash != null) {
                byHash[hash]?.let { spoken += it.id }
                entries += GroupWavelengthEntry(
                    id = "$DEPOSIT:${row.depositKey.lowercase()}",
                    kind = GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING,
                    status = GroupWavelengthEntry.Status.DONE,
                    source = GroupWavelengthEntry.Source.THIS_DEVICE,
                    amountSat = row.amountMsat / 1000,
                    feeSat = 0,
                    at = (row.confirmedAt ?: row.seenAt).epochSeconds,
                    member = row.member,
                    direction = GroupWavelengthEntry.Direction.IN,
                    detail = GroupWavelengthEntry.Detail.LightningDeposit(
                        request = rows.addresses.firstOrNull { it.paymentHash?.lowercase() == hash },
                        deposit = row,
                    ),
                )
            } else {
                entries += GroupWavelengthEntry(
                    id = "$DEPOSIT:${row.depositKey.lowercase()}",
                    kind = GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN,
                    status = GroupWavelengthEntry.Status.DONE,
                    source = GroupWavelengthEntry.Source.THIS_DEVICE,
                    amountSat = row.amountMsat / 1000,
                    feeSat = 0,
                    at = (row.confirmedAt ?: row.seenAt).epochSeconds,
                    member = row.member,
                    direction = GroupWavelengthEntry.Direction.IN,
                    detail = GroupWavelengthEntry.Detail.OnchainDeposit(deposit = row, coin = coins[row.depositKey.lowercase()]),
                )
            }
        }

        // Boarding coins signed into a round this device hasn't seen happen.
        rows.boarded.filter { it.outpoint.lowercase() !in keys }.forEach { coin ->
            entries += GroupWavelengthEntry(
                id = "$DEPOSIT:${coin.outpoint.lowercase()}",
                kind = GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN,
                status = GroupWavelengthEntry.Status.IN_FLIGHT,
                source = GroupWavelengthEntry.Source.THIS_DEVICE,
                amountSat = coin.amountSat,
                feeSat = 0,
                at = coin.atEpochSeconds,
                member = rows.addresses.firstOrNull { it.script?.lowercase() == coin.script.lowercase() }?.requester,
                direction = GroupWavelengthEntry.Direction.IN,
                detail = GroupWavelengthEntry.Detail.OnchainDeposit(boarded = coin),
            )
        }

        // Boarding payments this device has seen on chain that no round has taken in. Three tables can hold one
        // outpoint at once, so the order is the strength of the record: a landed deposit, then a boarding coin
        // this device signed for, then this (A11). The id is the same string in all three, so a row moving from
        // one to the next keeps its place and its detail route (A4).
        val boardedOutpoints = rows.boarded.map { it.outpoint.lowercase() }.toSet()
        rows.arriving.filter { it.outpoint.lowercase() !in keys && it.outpoint.lowercase() !in boardedOutpoints }.forEach { coin ->
            entries += GroupWavelengthEntry(
                id = "$DEPOSIT:${coin.outpoint.lowercase()}",
                kind = GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN,
                status = GroupWavelengthEntry.Status.ARRIVING,
                source = GroupWavelengthEntry.Source.THIS_DEVICE,
                amountSat = coin.amountSat,
                feeSat = 0,
                at = coin.firstSeenAtEpochSeconds,
                member = coin.member,
                direction = GroupWavelengthEntry.Direction.IN,
                detail = GroupWavelengthEntry.Detail.OnchainDeposit(
                    arriving = GroupWavelengthEntry.Detail.Arriving(
                        row = coin,
                        depth = GroupArkBoardingWatch.depthOf(coin, rows.tip),
                        needed = rows.boardingConfirmations,
                    ),
                ),
            )
        }

        // Lightning claims signed and not seen settle.
        rows.claims.forEach { claim ->
            val key = DepositAttributionEvent.DepositKey.Lightning(claim.paymentHash.lowercase()).text
            if (key.lowercase() in keys) return@forEach
            byHash[claim.paymentHash.lowercase()]?.let { spoken += it.id }
            val request = rows.addresses.firstOrNull { it.paymentHash?.lowercase() == claim.paymentHash.lowercase() }
            entries += GroupWavelengthEntry(
                id = "$DEPOSIT:${key.lowercase()}",
                kind = GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING,
                status = GroupWavelengthEntry.Status.IN_FLIGHT,
                source = GroupWavelengthEntry.Source.THIS_DEVICE,
                amountSat = claim.amountSat,
                feeSat = 0,
                at = claim.atEpochSeconds ?: request?.requestedAt?.epochSeconds,
                member = request?.requester,
                direction = GroupWavelengthEntry.Direction.IN,
                detail = GroupWavelengthEntry.Detail.LightningDeposit(request = request, claim = claim),
            )
        }

        // An invoice the operator issued that nobody has paid. A boarding address is not an entry: it is
        // permanent and reusable, so a row for one would sit in the history for good saying nothing (T11).
        rows.addresses.filter { it.invoiceAmountSat != null && it.invoice != null }.forEach { request ->
            val hash = request.paymentHash?.lowercase() ?: return@forEach
            if (DepositAttributionEvent.DepositKey.Lightning(hash).text.lowercase() in keys) return@forEach
            if (rows.claims.any { it.paymentHash.lowercase() == hash }) return@forEach
            val line = byHash[hash]
            line?.let { spoken += it.id }
            entries += GroupWavelengthEntry(
                id = "$DEPOSIT_REQUEST:${request.requestId}",
                kind = GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING,
                status = if (line?.status == LINE_FAILED) GroupWavelengthEntry.Status.FAILED else GroupWavelengthEntry.Status.WAITING,
                source = if (line == null) GroupWavelengthEntry.Source.THIS_DEVICE else GroupWavelengthEntry.Source.BOTH,
                amountSat = request.invoiceAmountSat ?: 0,
                feeSat = 0,
                at = request.requestedAt.epochSeconds,
                member = request.requester,
                direction = GroupWavelengthEntry.Direction.IN,
                detail = GroupWavelengthEntry.Detail.LightningDeposit(request = request),
            )
        }
        return entries
    }

    /**
     * The payment hash a Lightning deposit key carries, or null for a boarding coin's `txid:vout`. Keys are
     * written by `DepositKey.text` and so always parse; one that doesn't is read as a boarding coin, which
     * shows it rather than dropping it.
     */
    private fun lightningHashOf(depositKey: String): String? =
        (DepositAttributionEvent.DepositKey.parse(depositKey.lowercase()) as? DepositAttributionEvent.DepositKey.Lightning)?.paymentHash

    // ---------------------------------------------------------------------------------------------
    // Money going out. The approval is the group's own signature; where a Lightning payout got to is
    // only ever the operator's word, which is why one is BOTH as soon as a line joins it.
    // ---------------------------------------------------------------------------------------------

    private fun lightningPayouts(
        rows: Rows,
        byHash: Map<String, GroupWavelengthSnapshot.Line>,
        spoken: MutableSet<String>,
        nowSeconds: Long,
    ): List<GroupWavelengthEntry> = rows.payouts.map { approval ->
        val hash = approval.paymentHash?.toString()?.lowercase()
        val signed = hash?.let { h -> rows.payments.firstOrNull { it.paymentHash.lowercase() == h } }
        val line = hash?.let { byHash[it] }
        line?.let { spoken += it.id }
        val approved = approval.amountSat ?: 0
        // What the vHTLC actually held is what the member is charged, and it is the device's own figure.
        val amountSat = signed?.amountSat ?: approved
        val status = when {
            line?.status == LINE_FAILED -> GroupWavelengthEntry.Status.FAILED
            line?.status == LINE_COMPLETE -> GroupWavelengthEntry.Status.DONE
            line != null || signed != null -> GroupWavelengthEntry.Status.IN_FLIGHT
            approval.expiresAt <= nowSeconds -> GroupWavelengthEntry.Status.EXPIRED
            else -> GroupWavelengthEntry.Status.WAITING
        }
        GroupWavelengthEntry(
            // An invoice that doesn't decode has no hash to be known by, and two of them would otherwise
            // share an id. The invoice itself is what the group signed, so it stands in.
            id = "$PAYOUT_LIGHTNING:${hash ?: approval.invoice.lowercase()}",
            kind = GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING,
            status = status,
            source = when {
                line == null -> GroupWavelengthEntry.Source.THIS_DEVICE
                signed != null && line.amountSat > 0 && line.amountSat != amountSat -> GroupWavelengthEntry.Source.MISMATCH
                else -> GroupWavelengthEntry.Source.BOTH
            },
            amountSat = amountSat,
            feeSat = line?.feeSat ?: 0,
            at = line?.at?.takeIf { it > 0 } ?: signed?.atEpochSeconds ?: hash?.let { rows.payoutsAskedAt[it] },
            member = approval.payout?.member,
            direction = GroupWavelengthEntry.Direction.OUT,
            detail = GroupWavelengthEntry.Detail.LightningPayout(
                approval = approval,
                signed = signed,
                line = line,
                wentNowhere = hash != null && hash in rows.payoutsWentNowhere,
            ),
        )
    }

    /** An onchain payout is this device's own record end to end: it signed the leave and watched the round (W11). */
    private fun onchainPayouts(rows: Rows, nowSeconds: Long): List<GroupWavelengthEntry> = rows.onchainPayouts.map { approval ->
        val leave = rows.leaves.firstOrNull { it.authorisation == approval.id }
        GroupWavelengthEntry(
            id = "$PAYOUT_ONCHAIN:${approval.id}",
            kind = GroupWavelengthEntry.Kind.PAYOUT_ONCHAIN,
            status = when {
                leave?.confirmed == true -> GroupWavelengthEntry.Status.DONE
                leave != null -> GroupWavelengthEntry.Status.IN_FLIGHT
                approval.expiresAt <= nowSeconds -> GroupWavelengthEntry.Status.EXPIRED
                else -> GroupWavelengthEntry.Status.WAITING
            },
            source = GroupWavelengthEntry.Source.THIS_DEVICE,
            amountSat = leave?.amountSat ?: approval.amountSat,
            feeSat = leave?.feeSat ?: 0,
            at = leave?.confirmedAtEpochSeconds ?: leave?.atEpochSeconds ?: approval.askedAt,
            member = approval.member,
            direction = GroupWavelengthEntry.Direction.OUT,
            detail = GroupWavelengthEntry.Detail.OnchainPayout(approval, leave),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // What the wallet costs to keep. A round that a deposit or a payout already speaks for is not a
    // renewal and gets no entry of its own: its fee is on that entry, and counting it twice would
    // tell the group it had paid twice (T10).
    // ---------------------------------------------------------------------------------------------

    private fun rounds(rows: Rows): List<GroupWavelengthEntry> {
        // A payout's own entry carries its share of the round's fee, so the round is not a cost of its own.
        // A boarding coin's is transient: once the round happens the boarded row is forgotten, and the round
        // the deposit came in on then stands as what it also was, a round the group paid for.
        val spokenFor = (rows.leaves.map { it.commitment } + rows.boarded.map { it.commitment } + rows.payments.map { it.tx })
            .map { it.lowercase() }.toSet()
        val rounds = rows.rounds.associateBy { it.commitment.lowercase() }
        val fees = rows.fees.associateBy { it.tx.lowercase() }
        // Every key either side knows: a transfer costs a fee and files no round, and a round that cost
        // nothing still moved coins.
        return (rounds.keys + fees.keys).filter { it !in spokenFor }.map { key ->
            val made = rows.coins.filter { it.origin?.lowercase() == key }
            GroupWavelengthEntry(
                id = "$ROUND:$key",
                kind = GroupWavelengthEntry.Kind.ROUND,
                status = if (made.isNotEmpty() && made.all { it.settled }) GroupWavelengthEntry.Status.DONE else GroupWavelengthEntry.Status.IN_FLIGHT,
                source = GroupWavelengthEntry.Source.THIS_DEVICE,
                amountSat = 0,
                feeSat = fees[key]?.feeSat ?: 0,
                at = fees[key]?.atEpochSeconds ?: rounds[key]?.atEpochSeconds,
                direction = GroupWavelengthEntry.Direction.NONE,
                detail = GroupWavelengthEntry.Detail.Round(rounds[key], fees[key], made),
            )
        }
    }

    /**
     * The group asking its wallet to leave. A device can't tell a finished exit from an approval nothing
     * acted on, except by the wallet being empty: if it held coins and holds none now, it left.
     */
    private fun exits(rows: Rows, nowSeconds: Long): List<GroupWavelengthEntry> {
        val emptied = rows.coins.isNotEmpty() && rows.coins.all { it.spent }
        return rows.exits.map { approval ->
            GroupWavelengthEntry(
                id = "$EXIT:${approval.askedAt}",
                kind = GroupWavelengthEntry.Kind.EXIT,
                status = when {
                    emptied -> GroupWavelengthEntry.Status.DONE
                    approval.expiresAt <= nowSeconds -> GroupWavelengthEntry.Status.EXPIRED
                    else -> GroupWavelengthEntry.Status.WAITING
                },
                source = GroupWavelengthEntry.Source.THIS_DEVICE,
                amountSat = rows.coins.filter { !it.spent }.sumOf { it.amountSat },
                feeSat = 0,
                at = approval.askedAt,
                direction = GroupWavelengthEntry.Direction.OUT,
                detail = GroupWavelengthEntry.Detail.Exit(approval),
            )
        }
    }

    /**
     * Every line left over: something the operator says happened that this device has no record of. Shown,
     * because a member has to be able to see it, and marked, because believing it is a different thing.
     *
     * The id is prefixed like every other kind's, which is what stops an operator naming a line so that it
     * collides with an entry the device knows and telling its story instead (T4).
     */
    private fun operatorOnly(
        lines: List<GroupWavelengthSnapshot.Line>,
        spoken: Set<String>,
        snapshot: GroupWavelengthSnapshot?,
    ): List<GroupWavelengthEntry> = lines.filter { it.id !in spoken }.map { line ->
        GroupWavelengthEntry(
            id = "$OPERATOR:${line.id}",
            kind = GroupWavelengthEntry.Kind.OPERATOR,
            status = when (line.status) {
                LINE_COMPLETE -> GroupWavelengthEntry.Status.DONE
                LINE_FAILED -> GroupWavelengthEntry.Status.FAILED
                else -> GroupWavelengthEntry.Status.IN_FLIGHT
            },
            source = GroupWavelengthEntry.Source.THE_OPERATOR,
            amountSat = line.amountSat,
            feeSat = line.feeSat,
            at = line.at.takeIf { it > 0 },
            // All a line says about which way its money went is its kind, and a newer daemon's kinds
            // arrive as a word this one doesn't know.
            direction = when (line.kind) {
                LINE_RECEIVE, LINE_DEPOSIT -> GroupWavelengthEntry.Direction.IN
                LINE_SEND, LINE_EXIT -> GroupWavelengthEntry.Direction.OUT
                else -> GroupWavelengthEntry.Direction.UNKNOWN
            },
            detail = GroupWavelengthEntry.Detail.OperatorLine(
                line = line,
                term = snapshot?.term ?: 0,
                sentAt = snapshot?.sentAt ?: 0,
                tip = snapshot?.tip ?: 0,
            ),
        )
    }

    private const val DEPOSIT = "deposit"
    private const val DEPOSIT_REQUEST = "deposit-request"
    private const val PAYOUT_LIGHTNING = "payout-ln"
    private const val PAYOUT_ONCHAIN = "payout-onchain"
    private const val ROUND = "round"
    private const val EXIT = "exit"
    private const val OPERATOR = "operator"
}
