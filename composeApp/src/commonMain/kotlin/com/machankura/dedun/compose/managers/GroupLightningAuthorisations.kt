package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.IcebergBudgetSpend
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.payment.Bolt11Invoice
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * What the group has authorised its Lightning wallet to spend, as a co-signer reads it and the operator's
 * node acts on it (`docs/group-lightning-wallet.md`, Phases 12 and 13).
 *
 * An authorisation is a group-signed event (kind 30363, [GroupLightningAuthorisationEvent]). The group
 * may sign two for one invoice -- once more after the first expired, say. They are one payment: the
 * budget is the larger, and it is open while either is. A close or force close names one channel, and
 * lasts [CHANNEL_ACTION_LIFETIME].
 */
object GroupLightningAuthorisations {
    /** How long an authorisation stays open when its invoice does not say sooner. */
    val DEFAULT_LIFETIME: Duration = 24.hours

    /**
     * Every payment the group has authorised for [wallet], as this device verifies it, oldest first --
     * payouts to a member only. One signed without a member's name, by a quorum of older builds or
     * before the wallet paid only members, is no budget for any HTLC this device co-signs and nothing
     * the operator's node pays. `docs/payouts-only.md`, D8.
     */
    suspend fun payments(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet): List<GroupLightningAuthorisationEvent.Pay> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(wallet.signedEvent.chatRoomId, GroupLightningAuthorisationEvent.KIND)
            .filter { it.verifies() }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .mapNotNull { GroupLightningAuthorisationEvent.read(it.tags) }
            .filter { it.chain == wallet.statement.chain && it.wallet == wallet.statement.wallet && it.payout != null }

    /** Every payment the group has authorised, with its whole budget, by payment hash, in millisatoshi. */
    suspend fun authorisedPayments(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet): Map<String, Long> =
        payments(database, wallet).groupBy { it.paymentHash }.mapValues { (_, all) -> all.maxOf { it.budgetMsat } }

    /** The payment hashes whose every authorisation has expired at [now]: no new HTLC pays them. */
    suspend fun expired(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet, now: Instant = Clock.System.now()): Set<String> =
        payments(database, wallet).groupBy { it.paymentHash }.filterValues { all -> all.all { it.expiresAt <= now.epochSeconds } }.keys

    /** What each authorised payment has left to spend, by payment hash, in millisatoshi. */
    suspend fun budgets(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet): Map<String, Long> {
        val key = GroupLightningCoSigner.walletKey(wallet.signedEvent.chatRoomId, wallet.statement.chain, wallet.statement.wallet)
        val spent = database.icebergCoSigningDao().budgetSpends(key).associate { it.paymentHash to it.consumedMsat }
        return authorisedPayments(database, wallet).mapValues { (hash, amount) -> amount - (spent[hash] ?: 0) }.filterValues { it > 0 }
    }

    /** Records that a commitment spent [consumed] of each authorisation, by payment hash. */
    suspend fun consume(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet, consumed: Map<String, Long>) {
        if (consumed.isEmpty()) return
        val key = GroupLightningCoSigner.walletKey(wallet.signedEvent.chatRoomId, wallet.statement.chain, wallet.statement.wallet)
        val dao = database.icebergCoSigningDao()
        val spent = dao.budgetSpends(key).associate { it.paymentHash to it.consumedMsat }
        consumed.forEach { (hash, amount) -> dao.upsertBudgetSpend(IcebergBudgetSpend(key, hash, (spent[hash] ?: 0) + amount)) }
    }

    /** The authorised payments the operator's node should make at [now]: open, one per payment hash, oldest first. */
    suspend fun toPay(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet, now: Instant = Clock.System.now()): List<GroupLightningAuthorisationEvent.Pay> =
        payments(database, wallet).filter { it.expiresAt > now.epochSeconds }.distinctBy { it.paymentHash }

    /** Why [bolt11] cannot be authorised for [wallet], or null when it can. */
    sealed class Problem {
        data object Unreadable : Problem()
        data object OtherNetwork : Problem()
        data object Expired : Problem()
        data object NeedsAmount : Problem()
        /** The invoice pays the group's own node: a payment to itself that would only cost fees. */
        data object PaysTheGroup : Problem()
    }

    /** The authorisation [bolt11] would be, or the [Problem] with it. [amountMsat] is used only when the invoice names none. */
    fun prepare(wallet: GroupLightningWalletManager.Wallet, bolt11: String, amountMsat: Long?, maxFeeMsat: Long, now: Instant = Clock.System.now()): Result<GroupLightningAuthorisationEvent.Pay> {
        val text = bolt11.trim().removePrefix("lightning:").removePrefix("LIGHTNING:")
        val invoice = runCatching { Bolt11Invoice.read(text).get() }.getOrNull() ?: return Result.failure(ProblemException(Problem.Unreadable))
        if (invoice.chain != wallet.statement.chain) return Result.failure(ProblemException(Problem.OtherNetwork))
        if (invoice.isExpired(now.epochSeconds)) return Result.failure(ProblemException(Problem.Expired))
        if (invoice.nodeId.toString() == wallet.statement.nodeId) return Result.failure(ProblemException(Problem.PaysTheGroup))
        val amount = invoice.amount?.msat ?: amountMsat?.takeIf { it > 0 } ?: return Result.failure(ProblemException(Problem.NeedsAmount))
        val invoiceExpiry = invoice.timestampSeconds + (invoice.expirySeconds ?: Bolt11Invoice.DEFAULT_EXPIRY_SECONDS.toLong())
        val expires = minOf(invoiceExpiry, (now + DEFAULT_LIFETIME).epochSeconds)
        return Result.success(
            GroupLightningAuthorisationEvent.Pay(wallet.statement.chain, wallet.statement.wallet, text, amount, maxFeeMsat, invoice.paymentHash.toHex(), expires)
        )
    }

    class ProblemException(val problem: Problem) : IllegalArgumentException(problem.toString())

    /** Proposes [pay] to the group; it is authorised once a quorum signs. */
    suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, pay: GroupLightningAuthorisationEvent.Pay): FrostSigningSession {
        val template = GroupLightningAuthorisationEvent.template(pay)
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /** How long a close or force close stays open: long enough for the operator's device to come online. */
    val CHANNEL_ACTION_LIFETIME: Duration = 72.hours

    /** Every close and force close the group has authorised in [chatRoomId] for the wallet [statement] names, open at [now]. */
    suspend fun channelActions(database: DedunDatabase, chatRoomId: String, statement: GroupLightningWalletStatementEvent.Statement, now: Instant = Clock.System.now()): List<GroupLightningAuthorisationEvent.ChannelAction> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningAuthorisationEvent.KIND)
            .filter { it.verifies() }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .mapNotNull { GroupLightningAuthorisationEvent.readChannelAction(it.tags) }
            .filter { it.chain == statement.chain && it.wallet == statement.wallet && it.expiresAt > now.epochSeconds }

    /** Whether the group has authorised a force close of [channelId] that is still open. */
    suspend fun forceCloseAuthorised(database: DedunDatabase, chatRoomId: String, statement: GroupLightningWalletStatementEvent.Statement, channelId: ByteVector32): Boolean =
        channelActions(database, chatRoomId, statement).any { it.action == GroupLightningAuthorisationEvent.FORCE_CLOSE && it.channelId == channelId.toHex() }

    /** Proposes closing every one of [channelIds] of [wallet] to the onchain wallet, as one signing session. */
    suspend fun proposeCloseAll(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelIds: List<String>): FrostSigningSession {
        require(channelIds.isNotEmpty()) { "There are no channels to close" }
        val expires = (Clock.System.now() + CHANNEL_ACTION_LIFETIME).epochSeconds
        val templates = channelIds.map { channelId ->
            GroupLightningAuthorisationEvent.template(GroupLightningAuthorisationEvent.ChannelAction(GroupLightningAuthorisationEvent.CLOSE, wallet.statement.chain, wallet.statement.wallet, channelId, expires))
        }
        return FrostSigningManager.proposeSigningBatch(database, localChatRoom, userPublicKey, templates)
    }

    /** Proposes that the operator's node close [channelId] -- mutually, or by force when [force]. */
    suspend fun proposeChannelAction(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelId: String, force: Boolean): FrostSigningSession {
        val action = GroupLightningAuthorisationEvent.ChannelAction(
            if (force) GroupLightningAuthorisationEvent.FORCE_CLOSE else GroupLightningAuthorisationEvent.CLOSE,
            wallet.statement.chain, wallet.statement.wallet, channelId, (Clock.System.now() + CHANNEL_ACTION_LIFETIME).epochSeconds,
        )
        val template = GroupLightningAuthorisationEvent.template(action)
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /** Why a liquidity purchase cannot be proposed. */
    sealed class LiquidityProblem {
        /** A purchase of nothing. */
        data object NothingToBuy : LiquidityProblem()
        /** A purchase that would take the channel over what a channel may hold on this network. */
        data class OverTheCap(val capSat: Long) : LiquidityProblem()
    }

    class LiquidityProblemException(val problem: LiquidityProblem) : IllegalArgumentException(problem.toString())

    /** How long a liquidity purchase stays open: as long as a close, for the same reason. */
    val LIQUIDITY_LIFETIME: Duration = CHANNEL_ACTION_LIFETIME

    /**
     * Every liquidity purchase the group has authorised for [wallet], open at [now], oldest first
     * (`docs/group-lightning-liquidity.md`, Phase 4). Whether one has already been spent is each
     * co-signer's ledger to say.
     */
    suspend fun liquidityPurchases(database: DedunDatabase, wallet: GroupLightningWalletManager.Wallet, now: Instant = Clock.System.now()): List<GroupLightningAuthorisationEvent.BuyLiquidity> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(wallet.signedEvent.chatRoomId, GroupLightningAuthorisationEvent.KIND)
            .filter { it.verifies() }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .mapNotNull { GroupLightningAuthorisationEvent.readBuyLiquidity(it.tags) }
            .filter { it.chain == wallet.statement.chain && it.wallet == wallet.statement.wallet && it.expiresAt > now.epochSeconds }

    /**
     * The purchase of [amountSat] for [channelId], which holds [capacitySat] now, at most [maxFeeSat] in
     * fees, or the [LiquidityProblem] with it. The proposer is not trusted on the cap: every co-signer holds the
     * new funding output to it again.
     */
    fun prepareBuyLiquidity(wallet: GroupLightningWalletManager.Wallet, channelId: String, capacitySat: Long, amountSat: Long, maxFeeSat: Long, now: Instant = Clock.System.now()): Result<GroupLightningAuthorisationEvent.BuyLiquidity> {
        if (amountSat <= 0) return Result.failure(LiquidityProblemException(LiquidityProblem.NothingToBuy))
        GroupLightningRollout.channelCapFor(wallet.statement.chain)?.let { cap ->
            if (capacitySat + amountSat > cap.sat) return Result.failure(LiquidityProblemException(LiquidityProblem.OverTheCap(cap.sat)))
        }
        return Result.success(
            GroupLightningAuthorisationEvent.BuyLiquidity(
                wallet.statement.chain, wallet.statement.wallet, channelId, amountSat, maxFeeSat.coerceAtLeast(0),
                (now + LIQUIDITY_LIFETIME).epochSeconds, RandomInstance.bytes(8).toHex(),
            )
        )
    }

    /** Proposes [buy] to the group; the operator's node buys once a quorum signs. */
    suspend fun proposeBuyLiquidity(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, buy: GroupLightningAuthorisationEvent.BuyLiquidity): FrostSigningSession {
        val template = GroupLightningAuthorisationEvent.template(buy)
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }
}
