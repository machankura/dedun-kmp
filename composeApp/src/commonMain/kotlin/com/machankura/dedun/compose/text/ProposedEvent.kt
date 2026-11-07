package com.machankura.dedun.compose.text

import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningOperatorStatementEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOperatorStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.payment.Bolt11Invoice

/**
 * An event a group is being asked to sign, said in words.
 *
 * Shown as the thing rather than as an event: a member deciding whether to sign
 * is deciding about a wallet or a subgroup, and "kind 30331" answers a question
 * nobody asked. The raw kind stays for anything not recognised, since
 * refusing to describe an event is better than describing it wrongly.
 *
 * Not a composable, and deliberately: the same words appear on the signing
 * screen and in the room's list of proposals, and two copies of this mapping
 * would drift into two different names for one thing.
 */
object ProposedEvent {
    /**
     * [label] is what kind of thing it is, [detail] which one. Both are shown
     * together, so neither repeats the other.
     */
    data class Summary(val label: String, val detail: String)

    /**
     * [nameOf] names a member by public key, for the one summary that is about a
     * person: a caller with profiles to hand passes them, and one without gets
     * the summary without a name rather than a wrong one.
     */
    fun summarize(event: Event, nameOf: (HexKey) -> String? = { null }): Summary = when (event.kind) {
        // The one thing a group signs that is about the group rather than about
        // its work, and the only one a member sees before the room has done
        // anything. Shown as the path and the ceremony rather than as the key:
        // the key is the room's own id, which the member is already looking at.
        GroupKeyStateEvent.KIND -> Summary(
            label = "This group's shared key",
            detail = listOfNotNull(
                GroupKeyStateEvent.parsePath(event.tags)?.let(SharedKeyDerivation::formatPath),
                GroupKeyStateEvent.parseDkgSessionId(event.tags)?.take(12)
            ).joinToString(" · ")
        )

        // The other thing a group signs that is about groups rather than about
        // work, and the only one where the *proposal* is the last chance to
        // check. A parent admin approving this is putting the group's signature
        // to a room id produced by a ceremony most of them were not in, so the
        // summary is who it is for and what it is called -- the two things they
        // can actually weigh. The id is shown by the screen, and their device has
        // already checked it derives from the key the certificate names.
        SubgroupBirthCertificateEvent.KIND -> Summary(
            label = "A new subgroup of this group",
            detail = listOfNotNull(
                SubgroupBirthCertificateEvent.parseName(event.tags),
                SubgroupBirthCertificateEvent.parseAdminPublicKeys(event.tags)
                    .size
                    .takeIf { it > 0 }
                    ?.let { "run by $it members" },
                // The promise this certificate makes on the group's behalf, said
                // as one: a guardian key is this group, and every group above it,
                // able to spend from the subgroup's wallet if it opens a guarded one.
                SubgroupBirthCertificateEvent.parseGuardians(event.tags)
                    ?.size
                    ?.takeIf { it > 0 }
                    ?.let { count ->
                        when (count) {
                            1 -> "this group can spend from its wallet"
                            2 -> "this group and the one above it can spend from its wallet"
                            else -> "this group and the ${count - 1} above it can spend from its wallet"
                        }
                    },
                // How long this group's own leaf waits, which its coordinator
                // chose; every inherited one keeps its own. docs/guardian-delay.md.
                SubgroupBirthCertificateEvent.parseGuardians(event.tags)
                    ?.firstOrNull()
                    ?.let { "this group after ${BlockDuration.describe(it.delay)}" },
                // The decision this certificate makes for the child, said in both
                // directions: an absent line is not a decision anybody can be said
                // to have read. An older certificate decides nothing and says so
                // by saying nothing, as it always has.
                when (SubgroupBirthCertificateEvent.parseWalletPolicy(event.tags)) {
                    WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED) -> "its wallet must be guarded"
                    WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED) -> "its wallet will not be guarded"
                    else -> null
                }
            ).joinToString(" · ")
        )

        // The group opening a wallet. What a member is agreeing to is that the
        // group keeps coins here, so the summary names the network -- the one
        // thing that changes what the coins are worth -- and the account, and
        // not the xpub, which is a derivation of the room's key nobody can check
        // by eye.
        GroupWalletStatementEvent.KIND -> Summary(
            label = "Open a group onchain wallet",
            detail = GroupWalletStatementEvent.read(event.tags, event.content)?.let { statement ->
                "On ${GroupWalletStatementEvent.chainName(statement.chain)}, account ${statement.account}"
            } ?: "A wallet statement this device cannot read"
        )

        // The group keeping a Lightning wallet. What a member agrees to is a key, a node and an LSP
        // holding money under a policy, so the summary names the network, the threshold and the cap
        // on what can be in flight at once.
        GroupLightningWalletStatementEvent.KIND -> Summary(
            label = "Open a Lightning wallet",
            detail = GroupLightningWalletStatementEvent.read(event.tags)?.let { statement ->
                "On ${GroupWalletStatementEvent.chainName(statement.chain)}, ${statement.threshold} of ${statement.participants.size} to sign, " +
                    "at most ${statement.policy.inFlightCapSat} sats in flight"
            } ?: "A Lightning wallet statement this device cannot read"
        )

        // The group keeping a Wavelength wallet: a key, an operator and an expiry clock.
        GroupWavelengthWalletStatementEvent.KIND -> Summary(
            label = "Open a Wavelength wallet",
            detail = GroupWavelengthWalletStatementEvent.read(event.tags)?.let { statement ->
                "On ${GroupWalletStatementEvent.chainName(statement.chain)}, ${statement.threshold} of ${statement.participants.size} to sign, " +
                    "${2 * statement.threshold - 1} devices online every few days to keep its coins renewed"
            } ?: "A Wavelength wallet statement this device cannot read"
        )

        // A payment from the Wavelength wallet: as with the Lightning wallet's, the amount and the most the fee can be come first.
        GroupWavelengthPaymentEvent.KIND -> GroupWavelengthPaymentEvent.read(event.tags)?.let { pay ->
            val invoice = runCatching { Bolt11Invoice.read(pay.invoice).get() }.getOrNull()
            Summary(
                label = "Pay ${Satoshi(pay.amountSat ?: 0).grouped()} sats from the Wavelength wallet",
                detail = buildString {
                    pay.payout?.let { append("To @${nameOf(it.member) ?: it.member.take(8)}'s Lightning address, as the proposer resolved it, ") } ?: append("To ${invoice?.nodeId?.toString()?.take(16) ?: "a node"}, ")
                    append("with a fee of at most ${Satoshi(pay.maxFeeSat).grouped()} sats")
                    invoice?.description?.takeIf { it.isNotBlank() }?.let { append(", for \"$it\"") }
                },
            )
        } ?: Summary(label = "Pay from the Wavelength wallet", detail = "A payment this device cannot read")

        GroupWavelengthOperatorStatementEvent.KIND -> Summary(
            label = "Choose who runs the Wavelength wallet",
            detail = GroupWavelengthOperatorStatementEvent.read(event.tags)?.let { statement ->
                statement.operator?.let { "Term ${statement.term}: ${nameOf(it) ?: it.take(8)} runs the wallet on their device" } ?: "Term ${statement.term}: nobody runs the wallet"
            } ?: "An operator statement this device cannot read"
        )

        GroupLightningOperatorStatementEvent.KIND -> Summary(
            label = "Choose who runs the Lightning wallet",
            detail = GroupLightningOperatorStatementEvent.read(event.tags)?.let { statement ->
                statement.operator?.let { "Term ${statement.term}: ${it.take(8)} runs the node" } ?: "Term ${statement.term}: nobody runs the node"
            } ?: "An operator statement this device cannot read"
        )

        // A payment from the Lightning wallet. What a member agrees to is money leaving, so the
        // summary leads with the amount and the most the fee can be, then who is paid and for what as
        // the invoice says; the payee is a node key, shortened, since nobody checks one by eye.
        GroupLightningAuthorisationEvent.KIND -> GroupLightningAuthorisationEvent.read(event.tags)?.let { pay ->
            val invoice = runCatching { Bolt11Invoice.read(pay.invoice).get() }.getOrNull()
            Summary(
                label = "Pay ${Satoshi(pay.amountMsat / 1000).grouped()} sats",
                detail = buildString {
                    // A member's payout destination, on the proposer's word: the Lightning address's
                    // server signs nothing, so the node key stays beside the name.
                    // docs/group-payout-destination.md, Phase 7.
                    pay.payout?.let { payout ->
                        append("To @${nameOf(payout.member) ?: payout.member.take(8)}'s Lightning address, as the proposer resolved it: ")
                    }
                    append("${if (pay.payout != null) "node" else "To"} ${invoice?.nodeId?.toString()?.take(16) ?: "a node"}, with a fee of at most ${Satoshi(pay.maxFeeMsat / 1000).grouped()} sats")
                    invoice?.description?.takeIf { it.isNotBlank() }?.let { append(", for \"$it\"") }
                },
            )
        } ?: GroupLightningAuthorisationEvent.readChannelAction(event.tags)?.let { action ->
            // Closing moves the channel's money to the onchain wallet; forcing it does so slowly, at a
            // cost, and is for when the LSP has gone. The summary says which, because that is the choice.
            if (action.action == GroupLightningAuthorisationEvent.FORCE_CLOSE) Summary(
                label = "Force-close a Lightning channel",
                detail = "Channel ${action.channelId.take(16)}: the group's own commitment is published, and its money reaches the onchain wallet after a delay",
            ) else Summary(
                label = "Close a Lightning channel",
                detail = "Channel ${action.channelId.take(16)}: its money goes to the group's onchain wallet",
            )
        } ?: GroupLightningAuthorisationEvent.readBuyLiquidity(event.tags)?.let { buy ->
            // Buying liquidity spends the group's money on room to be paid. A signer weighs how much
            // room, what it costs at most, and that the LSP may take back what is unused after a year
            // (docs/group-lightning-liquidity.md, Phase 4).
            Summary(
                label = "Add ${Satoshi(buy.amountSat).grouped()} sats of inbound liquidity",
                detail = "Channel ${buy.channelId.take(16)}: for at most ${Satoshi(buy.maxFeeSat).grouped()} sats in fees, from the group's balance. The LSP may reclaim what is unused after a year",
            )
        } ?: Summary(label = "Authorise the Lightning wallet", detail = "An authorisation this device cannot read")

        // The group agreeing to guard one member's wallet. What a signer weighs
        // is whose wallet, and what the group can do with it: take any coin that
        // has sat for its leaf's delay, alongside every group above it after
        // theirs, while only the member can spend before then. The keys are their device's to check.
        // The group deciding whether it guards its members' wallets, and at what
        // delay. A signer weighs the delay, and that it reaches only wallets
        // asked for from now on; closing leaves every guarded wallet guarded.
        // docs/member-wallet-delay.md.
        // A member starting to deposit with the group. A signer weighs who, and where
        // their money goes back to: the destination the statement carries, signed by them.
        // docs/member-deposits.md.
        DepositorStatementEvent.KIND -> DepositorStatementEvent.read(event, event.pubKey)?.let { depositor ->
            Summary(
                label = "Open deposits for @${nameOf(depositor.member) ?: depositor.member.take(8)}",
                detail = "On ${GroupWalletStatementEvent.chainName(depositor.chain)} · paid back to ${depositor.destination.lightningAddress}" +
                    (depositor.destination.onchainAddress?.let { " or $it" } ?: ""),
            )
        } ?: Summary(label = "Open deposits for a member", detail = "A depositor statement this device cannot read")

        // The group saying whose some deposits are. Every signer checks each against its own
        // ledger before signing; the summary is the count and the total.
        DepositAttributionEvent.KIND -> DepositAttributionEvent.read(event)?.let { signed ->
            val members = signed.attributions.mapNotNull { it.member }.toSet()
            Summary(
                label = "Attribute ${signed.attributions.size} deposit${if (signed.attributions.size == 1) "" else "s"}",
                detail = if (members.isEmpty()) "To the group itself" else "To " + members.joinToString { "@" + (nameOf(it) ?: it.take(8)) },
            )
        } ?: Summary(label = "Attribute deposits", detail = "An attribution this device cannot read")

        // The group saying whose some of its coins are, so they can be paid out. Every signer
        // checks each coin against its own wallet; the summary is the count, and who gets what.
        // docs/payouts-only.md.
        CoinAssignmentEvent.KIND -> CoinAssignmentEvent.read(event)?.let { signed ->
            val owners = signed.coins.flatMap { it.shares.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            Summary(
                label = "Assign ${signed.coins.size} coin${if (signed.coins.size == 1) "" else "s"}",
                detail = owners.entries.sortedByDescending { it.value }.joinToString { (member, sat) ->
                    "@${nameOf(member) ?: member.take(8)} ${Satoshi(sat).grouped()} sats"
                },
            )
        } ?: Summary(label = "Assign coins", detail = "An assignment this device cannot read")

        MemberWalletPolicyEvent.KIND -> Summary(
            label = "Members' guarded wallets",
            detail = when (val policy = MemberWalletPolicyEvent.read(event)) {
                is MemberWalletPolicyEvent.Policy.Open ->
                    "this group after ${BlockDuration.describe(policy.delay)} · for wallets asked for from now on"
                is MemberWalletPolicyEvent.Policy.Closed ->
                    "no new ones · wallets already guarded stay guarded"
                null -> "A policy this device cannot read"
            }
        )

        MemberWalletCertificateEvent.KIND -> Summary(
            label = "Guard a member's wallet",
            detail = MemberWalletCertificateEvent.requestOf(event)
                ?.let { MemberWalletRequestEvent.read(it) }
                ?.let { reading ->
                    // Each guardian's own delay, nearest first: the group's own
                    // leaf, and every group above it at the delay it chose.
                    // docs/guardian-delay.md.
                    val delays = reading.guardians.map { BlockDuration.describe(it.delay) }
                    val reach = when (delays.size) {
                        1 -> "this group can take its coins after ${delays[0]}"
                        2 -> "this group can take its coins after ${delays[0]}, and the one above it after ${delays[1]}"
                        else -> "this group can take its coins after ${delays[0]}, and the ${delays.size - 1} above it after ${delays.drop(1).joinToString(", ")}"
                    }
                    listOfNotNull(
                        nameOf(reading.memberPublicKey)?.let { "For @$it" },
                        reading.label,
                        "on ${GroupWalletStatementEvent.chainName(reading.chain)}",
                        reach,
                        "only the member can spend from it before then",
                    ).joinToString(" · ")
                }
                ?: "A member wallet this device cannot read"
        )

        else -> Summary(label = "Event of kind ${event.kind}", detail = event.content)
    }
}
