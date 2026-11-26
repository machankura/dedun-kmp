package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Chain

/**
 * Where the group pays one party on one chain: a member's own signed 30338, or the 30429 the group
 * signed for a contact (`docs/non-member-deposits.md`, D9). Every rail checks a payout against the
 * payee's newest one by its [event]'s id, so the check reads either unchanged.
 */
interface PayoutDestination {
    val payee: Party
    val chain: Chain
    /** Always set for a member; a contact may have only an onchain address. */
    val lightningAddress: LightningAddress?
    val onchainAddress: String?
    val event: Event
}
