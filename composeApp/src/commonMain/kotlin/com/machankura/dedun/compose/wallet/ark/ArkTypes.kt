package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi

/**
 * The wallet's balance, in Bark's buckets. [spendable] is what can be sent now; the
 * rest is on its way somewhere and is shown as pending.
 */
data class ArkBalance(
    val spendable: Satoshi,
    val pendingLightningSend: Satoshi = Satoshi(0),
    val claimableLightningReceive: Satoshi = Satoshi(0),
    val pendingInRound: Satoshi = Satoshi(0),
    val pendingBoard: Satoshi = Satoshi(0),
    val pendingExit: Satoshi = Satoshi(0),
    val pendingOffboard: Satoshi = Satoshi(0),
    val pendingArkSend: Satoshi = Satoshi(0),
    /** Bark's own onchain wallet -- D8. Not part of [spendable]. */
    val onchain: Satoshi = Satoshi(0),
) {
    /** Money that is the wallet's but cannot be spent yet, or is leaving. */
    val pending: Satoshi
        get() = pendingLightningSend + claimableLightningReceive + pendingInRound + pendingBoard +
            pendingExit + pendingOffboard + pendingArkSend

    companion object {
        val EMPTY = ArkBalance(Satoshi(0))
    }
}

/** A fee estimate: what leaves the wallet, the fee within it, and what arrives. */
data class ArkFee(val gross: Satoshi, val fee: Satoshi, val net: Satoshi)

/** One way a pasted request can be paid. */
sealed interface ArkPaymentMethod {
    val value: String
    val rail: ArkRail

    data class ArkAddress(override val value: String) : ArkPaymentMethod {
        override val rail get() = ArkRail.Ark
    }

    data class Bolt11(override val value: String) : ArkPaymentMethod {
        override val rail get() = ArkRail.Lightning
    }

    data class Bolt12Offer(override val value: String) : ArkPaymentMethod {
        override val rail get() = ArkRail.Lightning
    }

    data class LightningAddress(override val value: String) : ArkPaymentMethod {
        override val rail get() = ArkRail.Lightning
    }

    data class Lnurl(override val value: String) : ArkPaymentMethod {
        override val rail get() = ArkRail.Lightning
    }

    data class Onchain(override val value: String) : ArkPaymentMethod {
        override val rail get() = ArkRail.Onchain
    }
}

/** One option of a parsed request: how it can be paid, and whether it needs an amount. */
data class ArkPaymentOption(
    val method: ArkPaymentMethod,
    val requiresAmount: Boolean,
    /** Why this option cannot be used -- another server's Ark address, an amount mismatch. */
    val problems: List<String> = emptyList(),
) {
    val usable: Boolean get() = problems.isEmpty()
}

/** What a pasted text turned out to be -- `parse_payment_request`. */
data class ArkPaymentRequest(
    val amount: Satoshi?,
    val description: String?,
    val options: List<ArkPaymentOption>,
    val preferred: ArkPaymentOption?,
) {
    /** The option a send uses: the request's own preference if usable, else the first usable one. */
    val chosen: ArkPaymentOption?
        get() = preferred?.takeIf { it.usable } ?: options.firstOrNull { it.usable }
}

/** What the receive screen shows: one URI for the QR, and each part on its own. */
data class ArkReceiveRequest(
    val uri: String,
    val arkAddress: String?,
    val bolt11: String?,
    val paymentHash: String?,
    val onchainAddress: String?,
    val amount: Satoshi?,
)

/** What a send returns at once. The movement it created follows through [ArkEvent]. */
sealed interface ArkSendResult {
    data class Ark(val movementId: Long) : ArkSendResult

    data class Lightning(val paymentHash: String) : ArkSendResult

    data class Onchain(val txid: String) : ArkSendResult
}

/** How the first open's mailbox scan went -- D11, Phase 3. */
sealed interface ArkRecovery {
    /** The open did not create the wallet, or skipped the scan. */
    data object NotRun : ArkRecovery

    data class Failed(val message: String) : ArkRecovery

    data class Completed(val complete: Boolean, val recovered: Satoshi, val failedIds: List<String>) : ArkRecovery
}

/** What the wallet tells the screens, so they need not poll. */
sealed interface ArkEvent {
    data class MovementChanged(val movement: ArkMovement) : ArkEvent

    /** Notifications were dropped: read everything again. */
    data object Lagging : ArkEvent
}
