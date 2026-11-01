package com.machankura.dedun.compose.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * What the group Lightning wallet's co-signing protocol keeps on each device
 * (`docs/group-lightning-wallet.md`, Phase 9). Every table is keyed by [wallet], the room, network and
 * wallet number the rows belong to, as `GroupLightningCoSigner.walletKey` spells it.
 */

/** One participant's round-one contribution for one pool counter: published ahead of any need for it. */
@Entity(primaryKeys = ["wallet", "counter", "participant"])
data class IcebergNonceRow(
    val wallet: String,
    val counter: Long,
    val participant: HexKey,
    val contribution: HexKey,
)

/** On the operator: which pool counter was given to which need, so a verification nonce re-derives. */
@Entity(primaryKeys = ["wallet", "counter"], indices = [Index("wallet", "purpose")])
data class IcebergLabelAssignment(
    val wallet: String,
    val counter: Long,
    val label: HexKey,
    /** `verification:<funding txid>:<remote key>:<commit index>` or `fresh:<funding txid>`. */
    val purpose: String,
    val assignedAt: Instant = Clock.System.now(),
)

/**
 * On a co-signer: every label it has echoed, with the message it echoed, and whether it then signed.
 * Never pruned. A label in here is never answered again under any other message.
 */
@Entity(primaryKeys = ["wallet", "label"])
data class IcebergLabelLedger(
    val wallet: String,
    val label: HexKey,
    val requestId: String,
    val messageHash: HexKey,
    val echoedAt: Instant = Clock.System.now(),
    val signedAt: Instant? = null,
)

/** Every participant's echo, as received. Two hashes under one label is a coordinator equivocating. */
@Entity(primaryKeys = ["wallet", "label", "participant"])
data class IcebergEchoRow(
    val wallet: String,
    val label: HexKey,
    val participant: HexKey,
    val messageHash: HexKey,
)

/** On the operator: the signature shares that came back for one request. */
@Entity(primaryKeys = ["wallet", "requestId", "participant"])
data class IcebergShareRow(
    val wallet: String,
    val requestId: String,
    val participant: HexKey,
    val share: HexKey,
)

/**
 * On a co-signer: what it has let one funding output's commitments do, as `CoSignerPolicy.Ledger` in
 * JSON. A channel has one per funding output this device has signed for: a splice adds one, at
 * [fundingTxIndex], and until it confirms both carry commitments (`docs/group-lightning-liquidity.md`,
 * Phase 5). A splice's output names the [parentOutPoint] it spent and the `buy-liquidity`
 * authorisation, [purchaseId], that let it.
 */
@Entity(primaryKeys = ["wallet", "channelId", "fundingOutPoint"])
data class IcebergChannelLedger(
    val wallet: String,
    val channelId: HexKey,
    val fundingOutPoint: String,
    val remoteFundingPubKey: HexKey,
    val ledger: String,
    val updatedAt: Instant = Clock.System.now(),
    @ColumnInfo(defaultValue = "0")
    val fundingTxIndex: Long = 0,
    val parentOutPoint: String? = null,
    val purchaseId: String? = null,
)

/** On a co-signer: how much of each authorised payment's budget the channel has spent. */
@Entity(primaryKeys = ["wallet", "paymentHash"])
data class IcebergBudgetSpend(
    val wallet: String,
    val paymentHash: HexKey,
    val consumedMsat: Long,
)
