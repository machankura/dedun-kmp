package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.PayoutDestination
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.wallet.CoinOwnership
import com.machankura.dedun.compose.wallet.GroupUtxo
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.machankura.dedun.compose.wallet.lightning.LightningBalance
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * What each member of a group may be paid out, and where to: `docs/payouts-only.md`, Phase 2.
 *
 * Onchain, a member's balance is the coins they own now ([CoinOwnership]): what can be spent,
 * not a history of what came in. Over Lightning there are no coins, so it is their deposits less
 * what has been paid to them or is authorised to be ([LightningBalance]).
 */
object MemberBalances {

    /** One coin, or the part of a shared one, that a member owns. */
    data class OwnedCoin(val utxo: GroupUtxo, val sat: Long, val shared: Boolean) {
        val isConfirmed: Boolean get() = utxo.isConfirmed
    }

    data class MemberBalance(
        /** A member's key, or a contact's id when [isContact]. */
        val member: HexKey,
        /** Where a payout to them goes, or null while they have given nowhere. A contact's is the group's (D9). */
        val destination: PayoutDestination?,
        val isFormerMember: Boolean,
        val coins: List<OwnedCoin>,
        val lightning: LightningBalance.Balance,
        /** What they hold in the group's Wavelength wallet, the third rail. */
        val wavelength: GroupWavelengthPayouts.Balance = GroupWavelengthPayouts.Balance.NONE,
        /** Someone outside the group it agreed to count, and hasn't said was a member: `docs/non-member-deposits.md`. */
        val isContact: Boolean = false,
    ) {
        val party: Party get() = Party.of(member, isContact)
        val onchainConfirmedSat: Long get() = coins.filter { it.isConfirmed }.sumOf { it.sat }
        val onchainPendingSat: Long get() = coins.filterNot { it.isConfirmed }.sumOf { it.sat }
        val hasOnchainAddress: Boolean get() = destination?.onchainAddress != null
    }

    /** A coin nobody owns yet, and where it came from. */
    data class UnownedCoin(val utxo: GroupUtxo, val why: CoinOwnership.Why)

    data class Balances(val members: Map<HexKey, MemberBalance>, val unowned: List<UnownedCoin>) {
        val unownedSat: Long get() = unowned.sumOf { it.utxo.amount.sat }
    }

    /** The group's signed word about whose its coins are, on [chain]: what [CoinOwnership] reads. */
    suspend fun recordsOf(database: DedunDatabase, chatRoomId: String, chain: Chain): CoinOwnership.Records {
        // Only a contact the group has a record of owns anything, and a claimed one's coins are their member's
        // (docs/non-member-deposits.md, D1 and D8).
        val contacts = Contacts.contactsIn(database, chatRoomId)
        val attributions = DepositAttributionEvent.newestPerDeposit(
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, DepositAttributionEvent.KIND).map { it.toEvent() },
            chatRoomId,
            chain,
        ).mapValues { it.value.first }
            .filterValues { !it.contact || it.member in contacts }
            .mapValues { (_, attribution) ->
                attribution.member?.let { contacts[it]?.claimedBy }?.let { attribution.copy(member = it, contact = false) } ?: attribution
            }
        val assignments = CoinAssignmentEvent.newestPerCoin(
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, CoinAssignmentEvent.KIND).map { it.toEvent() },
            chatRoomId,
            chain,
        )
        return CoinOwnership.Records.of(
            Depositors.statementsIn(database, chatRoomId, chain).values,
            attributions,
            assignments,
            MemberTransfers.signedIn(database, chatRoomId, chain),
            Contacts.depositorsIn(database, chatRoomId, chain).map { (id, depositor) -> depositor.branch to (contacts[id]?.claimedBy ?: id) }.toMap(),
        )
    }

    /**
     * What a signer checks a payout of [chatRoomId] on [chain] against: [recordsOf], and each
     * member's payout address as a script -- their newest destination, a former member's
     * included, with none for a member who has given no onchain address or withdrawn theirs.
     */
    suspend fun payoutContextOf(database: DedunDatabase, chatRoomId: String, chain: Chain): PayoutRule.Context {
        val scripts: Map<HexKey, ByteVector> = GroupPayoutDestinations.payoutDestinationsIn(database, chatRoomId, chain)
            .mapNotNull { (member, destination) ->
                destination.onchainAddress?.let { PayoutAddress.parseOrNull(chain, it) }?.script?.let { member to it }
            }.toMap()
        return PayoutRule.Context(recordsOf(database, chatRoomId, chain), scripts)
    }

    /**
     * Every member's balance in [chatRoomId] on [chain] -- everybody with a coin, a Lightning
     * deposit, a branch or a destination, former members included -- and the coins nobody owns.
     * [state] is the group's onchain wallet, or null while it has not loaded: then nobody has coins.
     */
    suspend fun balancesIn(
        database: DedunDatabase,
        chatRoomId: String,
        chain: Chain,
        state: GroupWalletState?,
        nowSeconds: Long = Clock.System.now().epochSeconds,
    ): Balances {
        val records = recordsOf(database, chatRoomId, chain)
        val coins = mutableMapOf<HexKey, MutableList<OwnedCoin>>()
        val unowned = mutableListOf<UnownedCoin>()
        state?.utxos.orEmpty().forEach { utxo ->
            when (val owner = CoinOwnership.ownerOf(utxo.utxo.outPoint, utxo.derivation.change, utxo.amount.sat, records)) {
                is CoinOwnership.Owner.Nobody -> unowned += UnownedCoin(utxo, owner.why)
                else -> CoinOwnership.sharesOf(owner, utxo.amount.sat).forEach { (member, sat) ->
                    coins.getOrPut(member) { mutableListOf() } += OwnedCoin(utxo, sat, owner is CoinOwnership.Owner.Shares)
                }
            }
        }

        val lightningDeposits = DepositLedger.depositsIn(database, chatRoomId, chain)
            .filter { it.source == DepositRow.LIGHTNING && it.member != null }
            .groupBy({ it.member!!.lowercase() }, { it.amountMsat })
            .mapValues { it.value.sum() }
        val payouts = DepositLedger.signedPayouts(database, chatRoomId, chain)
        val sentFees = payouts.map { it.wallet }.distinct()
            .flatMap { DepositLedger.sentFees(database, chatRoomId, chain, it).entries }
            .associate { it.key to it.value }

        val destinations = GroupPayoutDestinations.payoutDestinationsIn(database, chatRoomId, chain)
        val current = database.participantDao().findParticipantsByChatRoomId(chatRoomId).map { it.participantPublicKey.lowercase() }.toSet()
        val contacts = Contacts.contactsIn(database, chatRoomId).filterValues { it.claimedBy == null }.keys
        val wavelength = GroupWavelengthPayouts.balancesIn(database, chatRoomId, chain, nowSeconds)
        val lightningTransfers = MemberTransfers.netsIn(database, chatRoomId, chain, Rail.LIGHTNING)
        val everybody = coins.keys + lightningDeposits.keys + records.branches.values + destinations.keys + wavelength.keys + lightningTransfers.keys
        val members = everybody.associateWith { member ->
            MemberBalance(
                member = member,
                destination = destinations[member],
                isFormerMember = member !in current && member !in contacts,
                coins = coins[member].orEmpty(),
                lightning = LightningBalance.of(member, lightningDeposits[member] ?: 0, payouts, sentFees, nowSeconds, (lightningTransfers[member]?.sat ?: 0) * 1000),
                wavelength = wavelength[member] ?: GroupWavelengthPayouts.Balance.NONE,
                isContact = member in contacts,
            )
        }
        return Balances(members, unowned)
    }
}
