package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What a member's device keeps of what the operator said (`docs/group-lightning-wallet.md`, Phase 10).
 * Each row is the operator's claim and is shown as one; nothing the co-signer decides reads it.
 */

/** The newest snapshot for a wallet, whole, as `WalletWire.Snapshot` in JSON. */
@Entity(primaryKeys = ["wallet"])
data class GroupLightningSnapshotRow(
    val wallet: String,
    val sentBy: HexKey,
    val term: Long,
    val content: String,
    val sentAt: Instant,
    val receivedAt: Instant = Clock.System.now(),
)

/** Every payment any snapshot reported, so the list outlives the snapshot's page. */
@Entity(primaryKeys = ["wallet", "id"])
data class GroupLightningPaymentRow(
    val wallet: String,
    val id: String,
    val incoming: Boolean,
    val amountMsat: Long,
    val feesMsat: Long,
    val paymentHash: String?,
    val description: String?,
    val completedAt: Instant?,
    val succeeded: Boolean,
    /** For inbound liquidity bought, how much: then [amountMsat] is what it cost (docs/group-lightning-liquidity.md, Phase 7). */
    val liquiditySat: Long? = null,
)

/**
 * An invoice the operator made for a member's request: [requester] is whose deposit it is and
 * [paymentHash] what joins a payment to it (`docs/member-deposits.md`, Phase 6). Null on rows from
 * before deposits were attributed.
 */
@Entity(primaryKeys = ["wallet", "requestId"], indices = [Index(value = ["wallet", "paymentHash"])])
data class GroupLightningInvoiceRow(
    val wallet: String,
    val requestId: String,
    val bolt11: String,
    val receivedAt: Instant = Clock.System.now(),
    val requester: String? = null,
    val paymentHash: String? = null,
)
