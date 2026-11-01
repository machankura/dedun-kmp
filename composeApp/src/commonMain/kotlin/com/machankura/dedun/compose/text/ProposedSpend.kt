package com.machankura.dedun.compose.text

import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.PayoutRule
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/**
 * A spend the group is being asked to sign, said in words -- [ProposedEvent]'s
 * counterpart for a transaction, and for the same reason not a composable: the
 * signing screen and the room's list of proposals say it the same way.
 */
object ProposedSpend {
    /**
     * [spend] in words, as a payout when [payout] says it is one -- this device's own reading of whose
     * coins it spends and whose addresses it pays, never the proposer's -- with members named by
     * [nameOf]. `docs/payouts-only.md`, Phase 4.
     */
    fun summarize(spend: GroupSpend.Checked, chain: Chain, payout: PayoutRule.Result.Payout?, nameOf: (HexKey) -> String?): ProposedEvent.Summary {
        if (payout == null) return summarize(spend, chain)
        fun name(member: HexKey) = "@" + (nameOf(member) ?: member.take(8))
        return when (val only = payout.members.singleOrNull()) {
            null -> ProposedEvent.Summary(
                label = "Pay out ${payout.members.size} members",
                detail = payout.members.joinToString { "${name(it.member)} ${Satoshi(it.paid).grouped()} sats" },
            )
            else -> ProposedEvent.Summary(
                label = "Pay out ${name(only.member)}",
                detail = "${Satoshi(only.paid).grouped()} sats to their onchain address, ${addressOf(only.script, chain)}",
            )
        }
    }

    fun summarize(spend: GroupSpend.Checked, chain: Chain): ProposedEvent.Summary =
        ProposedEvent.Summary(
            // A guardian's spend takes a descendant's coins, so whose they are
            // is the first thing a signer reads.
            // A funding comes next: where the money goes, as every signer's
            // device derived it -- never the proposer's word for it.
            label = when (val guardian = spend.spender as? GroupSpend.Spender.Guardian) {
                null -> spend.fundings.singleOrNull()?.takeIf { spend.payments.size == 1 }
                    ?.let { "Fund ${walletOf(it)}" }
                    ?: "Spend from the group's wallet"
                else -> "Spend from ${guardedWallet(guardian.name, guardian.label)}"
            },
            detail = when (val payment = spend.payments.singleOrNull()) {
                null -> "Send ${spend.amountSent.grouped()} sats to ${spend.payments.size} addresses" + when (val funding = spend.fundings.singleOrNull()) {
                    null -> if (spend.fundings.isEmpty()) "" else
                        ", ${Satoshi(spend.fundings.sumOf { spend.tx.txOut[it.outputIndex].amount.sat }).grouped()} of them to ${spend.fundings.size} guarded wallets"
                    else -> ", ${spend.tx.txOut[funding.outputIndex].amount.grouped()} of them to ${walletOf(funding)}"
                }
                else -> when (val funding = spend.fundings.singleOrNull()) {
                    null -> "Send ${payment.txOut.amount.grouped()} sats to ${addressOf(payment.txOut.publicKeyScript, chain)}"
                    else -> "Send ${payment.txOut.amount.grouped()} sats to ${walletOf(funding)}, address ${funding.derived.index}"
                }
            }
        )

    private fun walletOf(funding: GroupSpend.Funding): String = guardedWallet(funding.wallet.displayName, funding.wallet.label)

    /**
     * A guarded wallet in a sentence: *#Translation's guarded wallet*, *@alice's
     * guarded wallet* -- and, for a member's wallet with a label, *@alice's
     * guarded wallet “Savings”*, since one person can have several and a signer
     * should know which is being spent from. `docs/member-wallet-guarding-group.md`, Phase 5.
     */
    fun guardedWallet(name: String?, label: String?): String =
        "${name ?: "a subgroup"}'s guarded wallet" + (label?.let { " “$it”" } ?: "")

    /** The address [script] pays, or the script itself where it has no address. */
    fun addressOf(script: ByteVector, chain: Chain): String =
        Bitcoin.addressFromPublicKeyScript(chain.chainHash, script.toByteArray()).right ?: script.toHex()

    /** A sweep from the Lightning wallet: what reaches the onchain wallet, which is all a member is agreeing to. */
    fun summarize(sweep: GroupSweep.Checked): ProposedEvent.Summary =
        ProposedEvent.Summary(
            label = "Sweep into the group's onchain wallet",
            detail = "${sweep.amount.grouped()} sats a closed Lightning channel left the group, after ${sweep.fee.grouped()} sats in fees"
        )

    val UNREADABLE_SWEEP = ProposedEvent.Summary(
        label = "Sweep into the group's onchain wallet",
        detail = "A sweep this device cannot check"
    )

    /** What a summary says for a spend this device cannot check: nothing a member could sign on. */
    val UNREADABLE = ProposedEvent.Summary(
        label = "Spend from the group's wallet",
        detail = "A spend this device cannot check"
    )
}
