package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An Ark wallet that only exists in a test: a balance, a history and an event stream
 * the test writes, and a record of what the code under test asked for.
 */
internal class FakeArkWallet(
    override val network: ArkNetwork = ArkNetwork.Signet,
    var balance: ArkBalance = ArkBalance.EMPTY,
    var movements: List<ArkMovement> = emptyList(),
    var recovery: ArkRecovery = ArkRecovery.NotRun,
    var tip: Long = 1_000,
    var firstExpiry: Long? = null,
) : ArkWallet {
    override val events = MutableSharedFlow<ArkEvent>(extraBufferCapacity = 64)

    /** What [parse] answers, by the text it is given. */
    val requests = mutableMapOf<String, ArkPaymentRequest>()

    /** Set to make the next call of the named method throw. */
    var failNext: Pair<String, ArkError>? = null

    val calls = mutableListOf<String>()
    var closed = false
        private set

    private var addresses = 0

    private fun call(name: String) {
        check(!closed) { "$name after close" }
        calls += name
        failNext?.let { (method, error) -> if (method == name) { failNext = null; throw error } }
    }

    override fun recovery(): ArkRecovery = recovery

    override suspend fun balance(): ArkBalance { call("balance"); return balance }

    override suspend fun history(): List<ArkMovement> { call("history"); return movements }

    override suspend fun sync() = call("sync")

    override suspend fun newArkAddress(): String { call("newArkAddress"); return "tark1fake${addresses++}" }

    override suspend fun receive(amount: Satoshi?, description: String?): ArkReceiveRequest {
        call("receive")
        val ark = "tark1fake${addresses++}"
        val bolt11 = amount?.let { "lntbs${it.sat}fake" }
        return ArkReceiveRequest(
            uri = "bitcoin:?ark=$ark" + (bolt11?.let { "&lightning=$it" } ?: ""),
            arkAddress = ark,
            bolt11 = bolt11,
            paymentHash = bolt11?.let { "00".repeat(32) },
            onchainAddress = null,
            amount = amount,
        )
    }

    override suspend fun lightningReceiveFee(amount: Satoshi): ArkFee { call("lightningReceiveFee"); return ArkFee(amount, Satoshi(0), amount) }

    override suspend fun claimLightningReceives() = call("claimLightningReceives")

    override suspend fun boardAll() = call("boardAll")

    override suspend fun parse(text: String): ArkPaymentRequest {
        call("parse")
        return requests[text] ?: throw ArkError.InvalidRequest("nothing in '$text' can be paid")
    }

    override suspend fun estimateFee(method: ArkPaymentMethod, amount: Satoshi): ArkFee {
        call("estimateFee")
        val fee = Satoshi(10)
        return ArkFee(amount + fee, fee, amount)
    }

    /** Every send, with the amount and comment it was given. */
    val sent = mutableListOf<Triple<ArkPaymentMethod, Satoshi?, String?>>()

    override suspend fun send(method: ArkPaymentMethod, amount: Satoshi?, comment: String?): ArkSendResult {
        call("send")
        sent += Triple(method, amount, comment)
        return when (method.rail) {
            ArkRail.Lightning -> ArkSendResult.Lightning("11".repeat(32))
            ArkRail.Onchain -> ArkSendResult.Onchain("22".repeat(32))
            else -> ArkSendResult.Ark(movements.size.toLong() + 1)
        }
    }

    override suspend fun maintenance() = call("maintenance")

    override suspend fun firstExpiryHeight(): Long? { call("firstExpiryHeight"); return firstExpiry }

    override suspend fun tipHeight(): Long { call("tipHeight"); return tip }

    override suspend fun close() {
        calls += "close"
        closed = true
    }
}
