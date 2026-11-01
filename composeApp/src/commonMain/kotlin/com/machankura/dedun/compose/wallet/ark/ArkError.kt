package com.machankura.dedun.compose.wallet.ark

/**
 * What can go wrong with an Ark wallet, in the app's terms.
 *
 * Bark sends every error across the FFI as one class carrying one flattened message
 * -- Phase 0, spike 2 -- so the adapters build these with [classify], and the messages
 * it matches are the fixtures in `ArkErrorTest`.
 */
sealed class ArkError(message: String) : Exception(message) {
    /** The Ark server or the Esplora could not be reached. */
    class ServerUnreachable(message: String) : ArkError(message)

    /** Not enough spendable balance for the amount and its fee. */
    class InsufficientFunds(message: String) : ArkError(message)

    /** The request names no amount, and none was given. */
    class AmountRequired(message: String) : ArkError(message)

    /** Over [ArkWalletRollout.MAINNET_CAP]. Raised by the app, never by Bark. */
    class AboveCap(message: String) : ArkError(message)

    /** Nothing in the text can be paid. */
    class InvalidRequest(message: String) : ArkError(message)

    /** The datadir is open already, in this process or another -- D2. */
    class DatadirLocked(message: String) : ArkError(message)

    /** The datadir belongs to other words. */
    class WrongSeed(message: String) : ArkError(message)

    /** Anything else, with Bark's message. */
    class Other(message: String) : ArkError(message)

    companion object {
        fun classify(message: String): ArkError {
            val text = message.lowercase()
            return when {
                "already using datadir" in text -> DatadirLocked(message)
                "descriptor mismatch" in text || "incorrect mnemonic" in text || "fingerprint mismatch" in text -> WrongSeed(message)
                "insufficient" in text || "not enough" in text -> InsufficientFunds(message)
                "amount" in text && ("required" in text || "missing" in text) -> AmountRequired(message)
                "failed to connect" in text || "connection refused" in text || "dns error" in text ||
                    "timed out" in text || "error sending request" in text -> ServerUnreachable(message)
                "nothing in" in text || "invalid payment" in text || "could not parse" in text ||
                    "unknown payment" in text -> InvalidRequest(message)
                else -> Other(message)
            }
        }
    }
}
