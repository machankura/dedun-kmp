package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import uniffi.bark.AvailablePaymentMethod
import uniffi.bark.Bip321UriOptions
import uniffi.bark.Config
import uniffi.bark.Movement
import uniffi.bark.Network
import uniffi.bark.OnchainWallet
import uniffi.bark.PaymentMethod
import uniffi.bark.PaymentResult
import uniffi.bark.RecoveryStatus
import uniffi.bark.Wallet
import uniffi.bark.WalletNotification
import uniffi.bark.WalletOpenArgs
import uniffi.bark.ExitState
import uniffi.bark.VtxoState
import uniffi.bark.extractTxFromPsbt
import uniffi.bark.Exception as BarkException

/*
 * The one place `uniffi.bark` is named -- `docs/bark-wallet.md`, D3. Compiled into both
 * androidMain (against bark-android) and jvmMain (against bark-jvm), whose generated
 * Kotlin is the same; iOS has no Kotlin binding and goes through Phase 8's bridge.
 *
 * Names are imported one by one: a star import brings in `uniffi.bark.Exception`,
 * which shadows `kotlin.Exception`.
 */

/** Opens the wallet in [datadir], creating it on the first open. */
internal suspend fun openBarkWallet(config: ArkConfig, words: String, datadir: String): ArkWallet {
    val network = config.network.toBark()
    val barkConfig = config.toBark()
    val onchain = bark { OnchainWallet.default(network, words, barkConfig, datadir) }
    return try {
        val wallet = bark { Wallet.open(network, words, barkConfig, WalletOpenArgs(datadir = datadir, onchain = onchain)) }
        BarkArkWallet(wallet, onchain, config.network)
    } catch (e: ArkError) {
        onchain.close()
        throw e
    }
}

/** Every Bark call goes through this, so nothing above the adapter sees Bark's exception. */
private inline fun <T> bark(block: () -> T): T = try {
    block()
} catch (e: BarkException) {
    throw ArkError.classify(e.message ?: e.toString())
}

private fun ArkNetwork.toBark(): Network = when (this) {
    ArkNetwork.Mainnet -> Network.BITCOIN
    ArkNetwork.Signet -> Network.SIGNET
    ArkNetwork.Regtest -> Network.REGTEST
}

/** Every field Bark does not need from the app is left null, which is Bark's default. */
private fun ArkConfig.toBark() = Config(
    serverAddress = endpoints.server,
    serverAccessToken = null,
    esploraAddress = endpoints.esplora,
    bitcoindAddress = null,
    bitcoindCookiefile = null,
    bitcoindUser = null,
    bitcoindPass = null,
    vtxoRefreshExpiryThreshold = null,
    vtxoExitMargin = null,
    htlcRecvClaimDelta = null,
    fallbackFeeRate = null,
    roundTxRequiredConfirmations = null,
    daemonSyncIntervalSecs = null,
    offboardRequiredConfirmations = null,
    daemonManualSync = null,
    lightningReceiveClaimRetries = null,
    userAgent = userAgent,
    vtxoKeyGapLimit = null,
)

private fun ULong.sat() = Satoshi(toLong())

private class BarkArkWallet(
    private val wallet: Wallet,
    private val onchain: OnchainWallet,
    override val network: ArkNetwork,
) : ArkWallet {
    override val events: Flow<ArkEvent> = flow {
        val holder = wallet.notifications()
        try {
            while (true) {
                // None means the stream ended: the wallet closed, or the wait was cancelled.
                val notification = bark { holder.nextNotification() } ?: break
                val event = when (notification) {
                    is WalletNotification.MovementCreated -> ArkEvent.MovementChanged(notification.movement.toArk())
                    is WalletNotification.MovementUpdated -> ArkEvent.MovementChanged(notification.movement.toArk())
                    WalletNotification.ChannelLagging -> ArkEvent.Lagging
                }
                emit(event)
            }
        } finally {
            holder.cancelNextNotificationWait()
            holder.close()
        }
    }

    override fun recovery(): ArkRecovery = when (val status = wallet.recoveryStatus()) {
        RecoveryStatus.NotRun -> ArkRecovery.NotRun
        is RecoveryStatus.Failed -> ArkRecovery.Failed(status.message)
        is RecoveryStatus.Completed -> ArkRecovery.Completed(
            complete = status.report.isComplete,
            recovered = status.report.recovered.totalSats.sat(),
            failedIds = status.report.failed.vtxoIds,
        )
    }

    override suspend fun balance(): ArkBalance {
        val b = bark { wallet.balance() }
        val chain = bark { onchain.balance() }
        return ArkBalance(
            spendable = b.spendableSats.sat(),
            pendingLightningSend = b.pendingLightningSendSats.sat(),
            claimableLightningReceive = b.claimableLightningReceiveSats.sat(),
            pendingInRound = b.pendingInRoundSats.sat(),
            pendingBoard = b.pendingBoardSats.sat(),
            pendingExit = b.pendingExitSats.sat(),
            pendingOffboard = b.pendingOffboardSats.sat(),
            pendingArkSend = b.pendingArkoorSendSats.sat(),
            onchain = chain.totalSats.sat(),
        )
    }

    override suspend fun history(): List<ArkMovement> =
        bark { wallet.history() }.map { it.toArk() }.sortedByDescending { it.id }

    override suspend fun sync() = bark { wallet.sync() }

    override suspend fun newArkAddress(): String = bark { wallet.newAddress() }

    override suspend fun receive(amount: Satoshi?, description: String?): ArkReceiveRequest {
        val onchainAddress = bark { onchain.newAddress() }
        val uri = bark {
            wallet.bip321Uri(
                Bip321UriOptions(
                    amountSats = amount?.toULong(),
                    label = null,
                    message = description,
                    ark = true,
                    lightning = amount != null,
                    onchainAddress = onchainAddress,
                ),
            )
        }
        return ArkReceiveRequest(
            uri = uri.uri,
            arkAddress = uri.arkAddress,
            bolt11 = uri.lightningInvoice?.invoice,
            paymentHash = uri.lightningInvoice?.paymentHash,
            onchainAddress = uri.onchainAddress,
            amount = amount,
        )
    }

    override suspend fun lightningReceiveFee(amount: Satoshi): ArkFee =
        bark { wallet.estimateLightningReceiveFee(amount.toULong()) }.let { ArkFee(it.grossAmountSats.sat(), it.feeSats.sat(), it.netAmountSats.sat()) }

    override suspend fun claimLightningReceives() {
        bark { wallet.tryClaimAllLightningReceives(false) }
    }

    override suspend fun boardAll() {
        bark { wallet.boardAll() }
    }

    override suspend fun parse(text: String): ArkPaymentRequest {
        val request = bark { wallet.parsePaymentRequest(text.trim()) }
        val options = request.options.mapNotNull { it.toArk() }
        return ArkPaymentRequest(
            amount = request.amountSats?.sat(),
            description = request.message ?: request.label,
            options = options,
            preferred = request.defaultOption?.toArk(),
        )
    }

    override suspend fun estimateFee(method: ArkPaymentMethod, amount: Satoshi): ArkFee =
        bark { wallet.estimatePaymentFee(method.toBark(), amount.toULong()) }
            .let { ArkFee(it.grossAmountSats.sat(), it.feeSats.sat(), it.netAmountSats.sat()) }

    override suspend fun send(method: ArkPaymentMethod, amount: Satoshi?, comment: String?): ArkSendResult =
        when (val result = bark { wallet.sendPayment(method.toBark(), amount?.toULong(), comment, false, null) }) {
            is PaymentResult.Ark -> ArkSendResult.Ark(result.movementId.toLong())
            is PaymentResult.Lightning -> ArkSendResult.Lightning(result.paymentHash)
            is PaymentResult.Onchain -> ArkSendResult.Onchain(result.txid)
        }

    override suspend fun maintenance() = bark { wallet.maintenanceDelegated() }

    override suspend fun renewalFee(): ArkFee? {
        val due = bark { wallet.getVtxosToRefresh() }.map { it.id }
        if (due.isEmpty()) return null
        return bark { wallet.estimateRefreshFee(due) }.let { ArkFee(it.grossAmountSats.sat(), it.feeSats.sat(), it.netAmountSats.sat()) }
    }

    override suspend fun renew() {
        val due = bark { wallet.getVtxosToRefresh() }.map { it.id }
        if (due.isNotEmpty()) bark { wallet.refreshVtxosDelegated(due) }
    }

    override suspend fun firstExpiryHeight(): Long? = bark { wallet.getFirstExpiringVtxoBlockheight() }?.toLong()

    override suspend fun tipHeight(): Long = bark { onchain.tipHeight() }.toLong()

    override suspend fun vtxos(): List<ArkVtxo> = bark { wallet.vtxos() }.map { vtxo ->
        ArkVtxo(
            id = vtxo.id,
            amount = vtxo.amountSats.sat(),
            expiryHeight = vtxo.expiryHeight.toLong(),
            state = when (vtxo.state) {
                is VtxoState.Spendable -> ArkVtxo.State.Spendable
                is VtxoState.Locked -> ArkVtxo.State.Locked
                is VtxoState.Spent -> ArkVtxo.State.Spent
                is VtxoState.Exited -> ArkVtxo.State.Exited
            },
        )
    }

    override suspend fun onchainAddress(): String = bark { onchain.newAddress() }

    override suspend fun offboardAllFee(address: String): ArkFee =
        bark { wallet.estimateOffboardAllFee(address) }.let { ArkFee(it.grossAmountSats.sat(), it.feeSats.sat(), it.netAmountSats.sat()) }

    override suspend fun offboardAll(address: String): String = bark { wallet.offboardAll(address) }.txid

    override suspend fun emergencyExitFee(): ArkExitFee {
        val ids = bark { wallet.spendableVtxos() }.map { it.id }
        val estimate = bark { wallet.estimateEmergencyExitFee(ids, null, null, null) }
        return ArkExitFee(estimate.totalFeeSats.sat(), estimate.fundable)
    }

    override suspend fun startEmergencyExit() = bark { wallet.startExitForEntireWallet() }

    override suspend fun progressExits(): List<ArkExitStatus> = bark { wallet.progressExits(null) }.map { status ->
        ArkExitStatus(
            vtxoId = status.vtxoId,
            state = when (status.state) {
                is ExitState.Start -> ArkExitStatus.State.Starting
                is ExitState.Processing -> ArkExitStatus.State.Broadcasting
                is ExitState.AwaitingDelta -> ArkExitStatus.State.AwaitingDelay
                is ExitState.Claimable -> ArkExitStatus.State.Claimable
                is ExitState.ClaimInProgress -> ArkExitStatus.State.Claiming
                is ExitState.Claimed -> ArkExitStatus.State.Claimed
                is ExitState.Canceled -> ArkExitStatus.State.Canceled
                is ExitState.VtxoAlreadySpent -> ArkExitStatus.State.AlreadySpent
                is ExitState.VtxoSwept -> ArkExitStatus.State.Swept
            },
            error = status.error,
        )
    }

    override suspend fun exitsClaimableAt(): Long? = bark { wallet.allExitsClaimableAtHeight() }?.toLong()

    override suspend fun claimExits(address: String): String {
        val claim = bark { wallet.drainExits(emptyList(), true, address, null) }
        val signed = bark { wallet.signExitClaimInputs(claim.psbtBase64) }
        return bark { wallet.broadcastTx(extractTxFromPsbt(signed)) }
    }

    override suspend fun close() {
        try {
            bark { wallet.stopDaemonWait() }
        } finally {
            wallet.close()
            onchain.close()
        }
    }
}

private fun Satoshi.toULong(): ULong = sat.toULong()

private fun Movement.toArk(): ArkMovement = ArkMovementMapping.map(
    RawArkMovement(
        id = id.toLong(),
        status = status,
        subsystemName = subsystemName,
        subsystemKind = subsystemKind,
        effectiveBalanceSats = effectiveBalanceSats,
        offchainFeeSats = offchainFeeSats.toLong(),
        sentTo = sentToAddresses,
        receivedOn = receivedOnAddresses,
        paymentHash = paymentHash,
        lightningInvoice = lightningInvoice,
        createdAt = createdAt,
        completedAt = completedAt,
    ),
)

/** A payment method the app can pay, or null for one it does not offer (a raw script, a custom one). */
private fun AvailablePaymentMethod.toArk(): ArkPaymentOption? {
    val method = when (val m = method) {
        is PaymentMethod.Ark -> ArkPaymentMethod.ArkAddress(m.value)
        is PaymentMethod.Bitcoin -> ArkPaymentMethod.Onchain(m.value)
        is PaymentMethod.Invoice -> ArkPaymentMethod.Bolt11(m.value)
        is PaymentMethod.Offer -> ArkPaymentMethod.Bolt12Offer(m.value)
        is PaymentMethod.LightningAddress -> ArkPaymentMethod.LightningAddress(m.value)
        is PaymentMethod.Lnurl -> ArkPaymentMethod.Lnurl(m.value)
        is PaymentMethod.OutputScript, is PaymentMethod.Custom -> return null
    }
    return ArkPaymentOption(method, requiresAmount, errors.map { it.toString() })
}

private fun ArkPaymentMethod.toBark(): PaymentMethod = when (this) {
    is ArkPaymentMethod.ArkAddress -> PaymentMethod.Ark(value)
    is ArkPaymentMethod.Onchain -> PaymentMethod.Bitcoin(value)
    is ArkPaymentMethod.Bolt11 -> PaymentMethod.Invoice(value)
    is ArkPaymentMethod.Bolt12Offer -> PaymentMethod.Offer(value)
    is ArkPaymentMethod.LightningAddress -> PaymentMethod.LightningAddress(value)
    is ArkPaymentMethod.Lnurl -> PaymentMethod.Lnurl(value)
}
