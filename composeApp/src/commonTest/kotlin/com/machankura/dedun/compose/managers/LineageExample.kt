package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex

/**
 * The tree `docs/member-wallet-guarding-group.md` uses throughout, with Alice's
 * two wallets in it:
 *
 * ```
 * R                       root
 * ├── P                   R's guarded subgroup
 * │   └── M               P's guarded subgroup — guards Alice's "Savings"
 * └── N                   R's guarded subgroup — guards Alice's "Rent"
 * ```
 *
 * The guardians are real grants, so `position` and `lineage` are what a
 * verified certificate would give; the events are stand-ins, since nothing here
 * checks a signature.
 */
object LineageExample {
    private fun key(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    val rKey = key(0x600d)
    val pKey = key(0xbeef)
    val mKey = key(0xcafe)
    val nKey = key(0xf00d)

    val r = SharedKeyDerivation.marmotGroupId(rKey)
    val p = SharedKeyDerivation.marmotGroupId(pKey)
    val m = SharedKeyDerivation.marmotGroupId(mKey)
    val n = SharedKeyDerivation.marmotGroupId(nKey)

    val alice = key(0xa11ce)
    val savings = "5".repeat(64)
    val rent = "7".repeat(64)

    private val pGrant = GuardianDerivation.grant(rKey, r, emptyList(), p)
    private val mGrant = GuardianDerivation.grant(pKey, p, pGrant, m)
    private val nGrant = GuardianDerivation.grant(rKey, r, emptyList(), n)

    /** Savings' guardians, `[M, P, R]`. */
    val savingsGuardians = GuardianDerivation.grant(mKey, m, mGrant, savings)

    /** Rent's guardians, `[N, R]`. */
    val rentGuardians = GuardianDerivation.grant(nKey, n, nGrant, rent)

    /**
     * Alice's wallet [walletId] under [guardians], as [ancestor] holds it.
     * [label] and the names are what `GuardianManager.named` would fill.
     */
    fun heldBy(
        ancestor: String,
        walletId: String,
        guardians: List<GuardianDerivation.Guardian>,
        label: String? = null,
        ownerPublicKey: String = alice,
        ownerName: String? = "alice",
        guardianName: String? = null,
        branchName: String? = null,
        walletKey: Int = 0xdecaf,
    ): GuardianManager.GuardedWallet {
        val account = GroupWalletDerivation.account(key(walletKey), Chain.Regtest, guardians = guardians)
        val certificate = Event(walletId.take(63) + "c", guardians.first().chatRoomId, 1_700_000_000, MemberWalletCertificateEvent.KIND, emptyArray(), walletId, "")
        return GuardianManager.GuardedWallet(
            ancestorChatRoomId = ancestor,
            descendantChatRoomId = walletId,
            statement = certificate,
            account = account,
            certificate = certificate,
            lineage = requireNotNull(GuardianDerivation.lineage(guardians, walletId, ancestor)) { "$ancestor does not guard $walletId" },
            subject = GuardianManager.GuardedSubject.Member(
                id = walletId,
                ownerPublicKey = ownerPublicKey,
                label = label,
                ownerName = ownerName,
                guardianName = guardianName,
                branchName = branchName,
            ),
        )
    }
}
