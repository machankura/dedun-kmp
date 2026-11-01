package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A guarded wallet this device holds the key of: one member's random key, and
 * the request that asked a group to guard it. `docs/member-guarded-wallets.md`.
 *
 * The key is why there is a table at all. Everything else about a member's
 * wallet is read off signed events -- whether it is certified is whether the
 * group's room holds a 30334 for [walletId] that verifies, read again each time
 * the way `GroupWallet` reads statements -- so there is no *certified* column to
 * disagree with the signature.
 *
 * ### `guardingChatRoomId` is a plain column, never a foreign key
 *
 * The argument `docs/subgroups.md` makes against cascading `parentChatRoomId`,
 * with money at the end of it. Deleting the group's room on this device must
 * never delete a private key that coins may sit behind; and a member who leaves
 * the group keeps a wallet the group still guards.
 *
 * ### One database holds every profile
 *
 * So every read is scoped by [ownerPublicKey], the profile the key belongs to.
 */
@Entity(
    indices = [
        Index("ownerPublicKey"),
        Index("guardingChatRoomId"),
    ],
)
data class MemberWallet(
    /** `W`: `MemberWalletDerivation.walletId` of the wallet key. */
    @PrimaryKey
    val walletId: String,

    /** The profile the wallet belongs to: the npub that signed the request. */
    val ownerPublicKey: HexKey,

    /** The group asked to guard it. */
    val guardingChatRoomId: String,

    /** The network, as `GroupWalletStatementEvent.chainName` writes it. */
    val chain: String,

    /**
     * `k`, hex. Stored as `DkgSession.secretShare` is, in the profile's database;
     * never logged, never sent. Lost with the device unless exported -- and then
     * the guardians can still move the coins after 12 blocks.
     */
    val walletPrivateKey: HexKey,

    /** The member's name for it, as the request carries it. */
    val label: String? = null,

    /** The signed 30335, whole: kept so a request the group did not sign can be asked again, unchanged. */
    val requestJson: String,

    val createdAt: Instant = Clock.System.now(),
) {
    override fun toString(): String =
        "MemberWallet(walletId=$walletId, owner=$ownerPublicKey, guardedBy=$guardingChatRoomId, chain=$chain, key=<redacted>)"
}
