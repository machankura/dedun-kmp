package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey

/**
 * Which keys are this wallet's: the slots a co-signer derives for itself, to compare with what a request names. Nothing
 * here is ever told by the operator, whose word is what is being checked.
 */
class GroupArkKeys(private val icebergKey: PublicKey, private val chain: Chain, private val wallet: Long, private val window: Long = 512) {
    private val accounts = HashMap<GroupArkDerivation.Slot, GroupArkDerivation.Account>()
    private val children = HashMap<Triple<GroupArkDerivation.Slot, Long, Long>, PublicKey>()

    private fun account(slot: GroupArkDerivation.Slot) = accounts.getOrPut(slot) { GroupArkDerivation.account(icebergKey, chain, wallet, slot) }

    /** The key at `/branch/index` below [slot]. */
    fun key(slot: GroupArkDerivation.Slot, branch: Long, index: Long): PublicKey =
        children.getOrPut(Triple(slot, branch, index)) { account(slot).xpub.derivePublicKey(branch).derivePublicKey(index).publicKey }

    /** The child that signs as [key] on branch 0 of [slot]. */
    fun child(slot: GroupArkDerivation.Slot, branch: Long, index: Long): GroupArkDerivation.Child =
        GroupArkDerivation.child(account(slot), branch, index)

    /** The slot a request's family names, or null for one the group holds no key in. */
    fun slotOf(family: Int): GroupArkDerivation.Slot? = when (family) {
        ArkRequest.FAMILY_ONCHAIN -> GroupArkDerivation.Slot.ONCHAIN
        else -> GroupArkDerivation.Slot.entries.firstOrNull { it.family == family.toLong() && it != GroupArkDerivation.Slot.VTXO_TREE }
    }

    /** Whether [key] is one a VTXO of this wallet may be owned by. */
    fun ownsVtxoKey(key: PublicKey): Boolean = listOf(GroupArkDerivation.Slot.VTXO_OWNER, GroupArkDerivation.Slot.OOR_RECEIVE).any { slot ->
        (0 until window).any { key(slot, 0, it) == key }
    }
}
