package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Bark's movements turned into history rows -- `docs/bark-wallet.md`, Phase 1. */
class ArkMovementMappingTest {
    private fun raw(
        name: String,
        kind: String,
        balance: Long,
        status: String = "successful",
        sentTo: List<String> = emptyList(),
        receivedOn: List<String> = emptyList(),
        invoice: String? = null,
    ) = RawArkMovement(
        id = 7,
        status = status,
        subsystemName = name,
        subsystemKind = kind,
        effectiveBalanceSats = balance,
        offchainFeeSats = 12,
        sentTo = sentTo,
        receivedOn = receivedOn,
        paymentHash = invoice?.let { "81be".repeat(16) },
        lightningInvoice = invoice,
        createdAt = "2026-11-02T00:45:12.123+02:00",
        completedAt = null,
    )

    @Test
    fun `every subsystem bark 0_8_0 emits has a rail`() {
        val cases = listOf(
            "bark.arkoor" to "send" to ArkRail.Ark,
            "bark.arkoor" to "receive" to ArkRail.Ark,
            "bark.board" to "board" to ArkRail.Board,
            "bark.offboard" to "offboard" to ArkRail.Offboard,
            "bark.offboard" to "send_onchain" to ArkRail.Onchain,
            "bark.exit" to "start" to ArkRail.Exit,
            "bark.lightning_receive" to "receive" to ArkRail.Lightning,
            "bark.lightning_send" to "send" to ArkRail.Lightning,
            "bark.round" to "refresh" to ArkRail.Renewal,
        )
        cases.forEach { (subsystem, rail) -> assertEquals(rail, ArkMovementMapping.railOf(subsystem.first, subsystem.second), "$subsystem") }
        assertEquals(ArkRail.Other, ArkMovementMapping.railOf("bark.something_new", "x"))
    }

    @Test
    fun `every status string has a status, and an unknown one is pending`() {
        assertEquals(ArkMovementStatus.Pending, ArkMovementMapping.statusOf("pending"))
        assertEquals(ArkMovementStatus.Succeeded, ArkMovementMapping.statusOf("successful"))
        assertEquals(ArkMovementStatus.Failed, ArkMovementMapping.statusOf("failed"))
        assertEquals(ArkMovementStatus.Canceled, ArkMovementMapping.statusOf("canceled"))
        assertEquals(ArkMovementStatus.Pending, ArkMovementMapping.statusOf("in-limbo"))
    }

    @Test
    fun `the sign of the balance change is the direction, and the amount is unsigned`() {
        val sent = ArkMovementMapping.map(raw("bark.arkoor", "send", -5_000, sentTo = listOf("""{"type":"ark","value":"tark1abc"}""")))
        assertEquals(ArkDirection.Outgoing, sent.direction)
        assertEquals(Satoshi(5_000), sent.amount)
        assertEquals(Satoshi(12), sent.fee)
        assertEquals("tark1abc", sent.counterparty)

        val received = ArkMovementMapping.map(raw("bark.lightning_receive", "receive", 10_000, invoice = "lntbs100u1p"))
        assertEquals(ArkDirection.Incoming, received.direction)
        assertEquals("lntbs100u1p", received.counterparty)
        assertEquals("81be".repeat(16), received.paymentHash)
    }

    @Test
    fun `a renewal, a board and an exit are the wallet's own, whatever their sign`() {
        assertEquals(ArkDirection.Internal, ArkMovementMapping.map(raw("bark.round", "refresh", -30)).direction)
        assertEquals(ArkDirection.Internal, ArkMovementMapping.map(raw("bark.board", "board", 20_000)).direction)
        assertEquals(ArkDirection.Internal, ArkMovementMapping.map(raw("bark.exit", "start", -20_000)).direction)
        assertNull(ArkMovementMapping.map(raw("bark.round", "refresh", -30)).counterparty)
    }

    @Test
    fun `a destination is read out of whatever JSON serde made of it`() {
        assertEquals("tark1abc", ArkMovementMapping.destinationOf("""{"type":"ark","value":"tark1abc"}"""))
        assertEquals("tb1qxyz", ArkMovementMapping.destinationOf("""{"Bitcoin":"tb1qxyz"}"""))
        assertEquals("alice@example.com", ArkMovementMapping.destinationOf("alice@example.com"))
        assertNull(ArkMovementMapping.destinationOf(""))
    }

    @Test
    fun `an RFC 3339 time with an offset is an instant, and anything else is none`() {
        val movement = ArkMovementMapping.map(raw("bark.arkoor", "receive", 1))
        assertEquals(Instant.parse("2026-11-01T22:45:12.123Z"), movement.createdAt)
        assertNull(movement.completedAt)
        assertNull(ArkMovementMapping.map(raw("bark.arkoor", "receive", 1).copy(createdAt = "yesterday")).createdAt)
    }
}
