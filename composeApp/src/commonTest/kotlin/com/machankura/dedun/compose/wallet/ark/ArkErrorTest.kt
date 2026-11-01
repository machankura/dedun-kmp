package com.machankura.dedun.compose.wallet.ark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Bark's flattened error messages, sorted into what the app can say about them. The
 * fixtures marked *seen* are verbatim from Phase 0's signet probe.
 */
class ArkErrorTest {
    @Test
    fun `a second open of a datadir is locked -- seen`() {
        val message = "failed to instantiate platform default lock manager: another process is already using datadir /data/ark/signet/ab12 (holder PID: 447641)"
        assertIs<ArkError.DatadirLocked>(ArkError.classify(message))
    }

    @Test
    fun `a datadir opened with other words is the wrong seed -- seen`() {
        val message = "Descriptor mismatch for External keychain: loaded tr([1dd1f61e/86'/1'/0']tpubDC6a/0/*)#7jkt434n, expected tr([72552dd2/86'/1'/0']tpubDDFB/0/*)#dydyes85"
        assertIs<ArkError.WrongSeed>(ArkError.classify(message))
        assertIs<ArkError.WrongSeed>(ArkError.classify("incorrect mnemonic"))
    }

    @Test
    fun `a server that cannot be reached says so`() {
        assertIs<ArkError.ServerUnreachable>(ArkError.classify("error creating new wallet: Failed to connect to provided server: Failed to connect to Ark server: transport error"))
        assertIs<ArkError.ServerUnreachable>(ArkError.classify("error sending request for url (https://esplora.signet.2nd.dev/blocks/tip/height)"))
    }

    @Test
    fun `a refused handshake reads as the server, and keeps Bark's words -- seen`() {
        // A bad user agent is the app's bug, not the user's: the message is kept for the log.
        val message = "error creating new wallet: Failed to connect to provided server: Failed to connect to Ark server: handshake request failed: code: 'Client specified an invalid argument', message: \"invalid x-user-agent: no slash present\""
        assertIs<ArkError.ServerUnreachable>(ArkError.classify(message))
        assertEquals(message, ArkError.classify(message).message)
    }

    @Test
    fun `money and amounts are named, and the rest keeps Bark's message`() {
        assertIs<ArkError.InsufficientFunds>(ArkError.classify("Insufficient money available. Needed 5000 sat but 0 sat is available"))
        assertIs<ArkError.AmountRequired>(ArkError.classify("amount is required for this payment method"))
        assertIs<ArkError.Other>(ArkError.classify("something else entirely"))
    }
}
