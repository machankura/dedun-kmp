package com.machankura.dedun.compose.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One Iceberg key ceremony, as this device took part in it: the key a group's Lightning wallet signs
 * its channels with (`docs/group-lightning-wallet.md`, Phase 5).
 *
 * Like [DkgSession] it stores the ceremony's *inputs* -- the random root this device's contributions
 * come from, and every message received in [IcebergParticipantMessage] -- rather than its state, so
 * every step can be recomputed after a restart.
 *
 * [share] is the member's Iceberg share, plaintext, which is the same decision the onchain wallet
 * made for ChillDKG shares (`docs/group-chain-wallet.md`, Phase 0) and the same cost: a stolen phone
 * plus `t - 1` colluders signs.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("chatRoomId")],
)
data class IcebergKeySession(
    /** Minted by whoever proposed the ceremony, carried on every message as `iceberg_session`. */
    @PrimaryKey
    val id: String,
    val chatRoomId: String,
    val coordinatorPublicKey: HexKey,
    /** Whose device this row belongs to. */
    val userPublicKey: HexKey,
    val threshold: Int,
    val participantCount: Int,
    /** The participants' nostr keys, sorted, comma-separated: the set and its order. */
    val participantPublicKeys: String,
    /**
     * The ChillDKG ceremony whose key the onchain wallet is, and whose participants these are
     * (decision D1): a Lightning wallet signed by different people from the wallet it closes to would
     * answer to a different quorum.
     */
    val onchainDkgSessionId: String,
    /**
     * Which wallet this key is for: [PURPOSE_LIGHTNING] or [PURPOSE_WAVELENGTH]. A shared key would share a label space
     * and a removal, and a wallet opens under the newest key none of its kind uses, so without this a Lightning statement
     * could claim a key made for the other. Rows from before there was a second wallet were all for the first.
     */
    @ColumnInfo(defaultValue = PURPOSE_LIGHTNING)
    val purpose: String = PURPOSE_LIGHTNING,
    val stage: IcebergRitualStage = IcebergRitualStage.COLLECTING_COMMITMENTS,
    /** 32 random bytes, hex, that this device's contributions are derived from. Dropped once [share] exists. */
    val root: HexKey? = null,
    /** The serialised Iceberg share, hex. Secret. */
    val share: HexKey? = null,
    /** The group's Iceberg public key, compressed hex, once the ceremony completes. */
    val groupPublicKey: HexKey? = null,
    /** Every participant's public share, in participant order, comma-separated hex. */
    val publicShares: String? = null,
    val joinApprovedAt: Instant? = null,
    /** Written by builds that asked before revealing; nothing reads it now. */
    val revealApprovedAt: Instant? = null,
    /** Written by builds that asked before confirming; nothing reads it now. */
    val confirmApprovedAt: Instant? = null,
    /** The furthest step this device has asked its owner about, so each request is written once. */
    val approvalRequestedThrough: IcebergApprovalStep? = null,
    val failureReason: String? = null,
    /** When the ceremony was proposed, by its proposal's timestamp: its deadline runs from here. */
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
) {
    /** How a chat line names the wallet this key is for. */
    val walletName: String get() = if (purpose == PURPOSE_WAVELENGTH) "Wavelength wallet" else "Lightning wallet"

    fun participants(): List<HexKey> = participantPublicKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun isCoordinator(): Boolean = coordinatorPublicKey == userPublicKey

    fun publicShareList(): List<HexKey>? = publicShares?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

    override fun toString(): String = "IcebergKeySession(id=$id, chatRoomId=$chatRoomId, $threshold-of-$participantCount, stage=$stage)"

    companion object {
        const val PURPOSE_LIGHTNING = "lightning"
        const val PURPOSE_WAVELENGTH = "wavelength"

        fun formatParticipants(publicKeys: Collection<HexKey>): String = publicKeys.map { it.lowercase() }.distinct().sorted().joinToString(",")
    }
}

/** One protocol message of an Iceberg ceremony, as received or sent, keyed so a redelivery is absorbed. */
@Entity(
    primaryKeys = ["sessionId", "participantPublicKey", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = IcebergKeySession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class IcebergParticipantMessage(
    val sessionId: String,
    /** The sender's nostr key, as the MLS frame vouched for it. */
    val participantPublicKey: HexKey,
    /** One of `GroupLightningEvents.CEREMONY`. */
    val kind: Int,
    /** The message's content, as received: JSON for every round. */
    val payload: String,
    val createdAt: Instant = Clock.System.now(),
)
