package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import fr.acinq.bitcoin.Chain

/**
 * Where this device offers a member a transfer of their balance: `docs/member-transfers.md`, T11.
 *
 * It decides what a device *offers*, not what it accepts. With a rail shut here, a device still counts every transfer the
 * group signs and still checks and signs one another member proposes -- which is what has to be true everywhere before any
 * device offers one, or a device that ignores a transfer would approve paying its giver what they gave away.
 */
object MemberTransferRollout {
    /**
     * The chains a transfer is offered on, per rail. The two ledger rails on regtest and signet, until the emulator run of
     * Phase 7 and the owner's word for mainnet (question 1). The onchain rail the same since its screen (Phase 6), a release
     * after every device began applying onchain transfers to coin ownership (Phase 5).
     */
    fun offers(chain: Chain, rail: Rail): Boolean = chain == Chain.Regtest || chain == Chain.Signet

    /**
     * Whether this device signs an onchain transfer at all. Off for the release in which every device starts applying onchain
     * transfers to coin ownership (Phase 5), so that no device which ignores them can be outvoted by one that signed one --
     * the order Phase 2 and 3 shipped the ledger rails in. On from the release after (Phase 6).
     */
    const val SIGNS_ONCHAIN: Boolean = true
}
