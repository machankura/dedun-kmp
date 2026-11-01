package com.machankura.dedun.compose.wallet

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector

/**
 * The one spend a group's own wallet signs: a payout. `docs/payouts-only.md`, D1.
 *
 *  - every input is a coin owned, all of it, by members the spend pays ([CoinOwnership]);
 *  - every output pays one member's payout address, and no member is paid twice;
 *  - each member's output is what they own among the inputs, less their share of the fee
 *    ([PayoutFees]), to the sat;
 *  - no output is change, and none funds a guarded wallet.
 *
 * Run after [GroupSpend.check], whose seven checks it relies on: an input's branch is proved by
 * its derivation, its amount by the sighash, and the fee is the inputs less the outputs. With no
 * change, no coin the group holds afterwards is a mixture of members' money, which is what keeps
 * every later coin somebody's.
 */
object PayoutRule {

    /**
     * What a signer holds to check a payout with: the group's signed word about coins, and each
     * member's payout address on this network as a script -- absent for a member who has given
     * none, or has withdrawn theirs.
     */
    data class Context(
        val records: CoinOwnership.Records,
        val scripts: Map<HexKey, ByteVector>,
    )

    /** One member's part of a payout. */
    data class MemberPayout(
        val member: HexKey,
        /** What they own among the inputs, in sats. */
        val owned: Long,
        /** Their share of the fee, in sats. */
        val feeShare: Long,
        val outputIndex: Int,
        val script: ByteVector,
    ) {
        /** What arrives at their address. */
        val paid: Long get() = owned - feeShare
    }

    /** One input, whose it is, and how that is known. */
    data class InputOwner(val index: Int, val owner: CoinOwnership.Owner)

    sealed interface Result {
        data class Payout(val members: List<MemberPayout>, val inputs: List<InputOwner>) : Result
        data class Refused(val reason: String) : Result
    }

    /** Whose each of [checked]'s inputs is, by [records]. */
    fun ownersOf(checked: GroupSpend.Checked, records: CoinOwnership.Records): List<InputOwner> =
        checked.inputs.map { input ->
            InputOwner(input.index, CoinOwnership.ownerOf(input.outPoint, input.derivation.change, input.txOut.amount.sat, records))
        }

    fun check(checked: GroupSpend.Checked, context: Context): Result {
        if (checked.spender != GroupSpend.Spender.Owner) return Result.Refused("is a guardian's spend, not a payout")
        if (checked.fundings.isNotEmpty()) return Result.Refused("funds a guarded wallet, which is not a payout")
        checked.outputs.firstOrNull { it.isChange }?.let { return Result.Refused("pays change to output ${it.index}, and a payout has none") }

        // Whose every input is.
        val inputs = ownersOf(checked, context.records)
        val owned = mutableMapOf<HexKey, Long>()
        inputs.forEach { (index, owner) ->
            if (owner is CoinOwnership.Owner.Nobody) return Result.Refused("spends input $index, a coin nobody owns yet")
            CoinOwnership.sharesOf(owner, checked.inputs[index].txOut.amount.sat).forEach { (member, sat) ->
                owned[member] = (owned[member] ?: 0) + sat
            }
        }

        // Whose address every output pays.
        val byScript = context.scripts.entries.groupBy({ it.value.toHex() }, { it.key.lowercase() })
        val paid = mutableMapOf<HexKey, Pair<Int, ByteVector>>()
        checked.outputs.forEach { output ->
            val script = output.txOut.publicKeyScript
            val members = byScript[script.toHex()]
                ?: return Result.Refused("pays output ${output.index} to an address that is no member's payout address")
            val member = members.singleOrNull()
                ?: return Result.Refused("pays output ${output.index} to an address more than one member gave")
            if (member in paid) return Result.Refused("pays ${member.take(8)} twice")
            paid[member] = output.index to script
        }

        // The members it spends from are exactly the members it pays.
        owned.keys.firstOrNull { it !in paid }?.let { return Result.Refused("spends coins of ${it.take(8)} without paying them") }
        paid.keys.firstOrNull { it !in owned }?.let { return Result.Refused("pays ${it.take(8)}, none of whose coins it spends") }

        // Each gets all of theirs, less their share of the fee.
        val shares = PayoutFees.split(owned, checked.fee.sat)
        val members = paid.map { (member, at) ->
            val (outputIndex, script) = at
            val payout = MemberPayout(member, owned.getValue(member), shares.getValue(member), outputIndex, script)
            val amount = checked.outputs[outputIndex].txOut.amount.sat
            if (amount != payout.paid) {
                return Result.Refused(
                    "pays ${member.take(8)} $amount sats where their ${payout.owned} less a ${payout.feeShare} sat share of the fee is ${payout.paid}"
                )
            }
            payout
        }.sortedBy { it.outputIndex }
        return Result.Payout(members, inputs)
    }
}
