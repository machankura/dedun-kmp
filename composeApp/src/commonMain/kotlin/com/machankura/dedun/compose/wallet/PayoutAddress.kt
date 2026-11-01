package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.BitcoinError
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.utils.Either

/**
 * An onchain address a member gives as where the group pays them, read for one chain
 * (`docs/group-payout-destination.md`, D7).
 *
 * The five templates a wallet hands out -- P2PKH, P2SH, P2WPKH, P2WSH, P2TR. bitcoin-kmp also
 * reads a segwit version from 2 to 16, which nothing can spend from yet, so a payout to one
 * would be money nobody gets: refused as not an address. Unlike
 * `GroupSpendBuilder.scriptOf`, which keeps only "not an address", the error is kept, so a
 * signet address pasted into a mainnet group is told so rather than called nonsense.
 */
object PayoutAddress {

    data class Parsed(
        /** The address as it will be written down: trimmed, and a bech32 address in lowercase. */
        val address: String,
        val script: ByteVector,
    )

    sealed interface Problem {
        data object Empty : Problem
        data object NotAnAddress : Problem
        /** An address, but for another network. */
        data object OtherNetwork : Problem
    }

    private const val BITCOIN_SCHEME = "bitcoin:"
    private val BECH32_PREFIXES = listOf("bc1", "tb1", "bcrt1")

    /**
     * [input] as an address on [chain]. A `bitcoin:` URI gives its address and loses its
     * query -- an amount or a label in it is the payer's business, not the destination's.
     */
    fun parse(chain: Chain, input: String): Either<Problem, Parsed> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Either.Left(Problem.Empty)
        val bare = (if (trimmed.startsWith(BITCOIN_SCHEME, ignoreCase = true)) trimmed.drop(BITCOIN_SCHEME.length) else trimmed)
            .substringBefore('?')
            .trim()
        // Bech32 is one case or the other, and a QR code's is upper: written down in lower.
        val address = if (BECH32_PREFIXES.any { bare.startsWith(it, ignoreCase = true) } && bare == bare.uppercase()) bare.lowercase() else bare
        if (address.isEmpty() || address.any { it.isWhitespace() }) return Either.Left(Problem.NotAnAddress)

        return when (val parsed = Bitcoin.addressToPublicKeyScript(chain.chainHash, address)) {
            is Either.Right -> {
                val script = parsed.value
                val standard = Script.isPay2pkh(script) || Script.isPay2sh(script) || Script.isPay2wpkh(script) ||
                    Script.isPay2wsh(script) || Script.isPay2tr(script)
                if (standard) Either.Right(Parsed(address, ByteVector(Script.write(script)))) else Either.Left(Problem.NotAnAddress)
            }
            is Either.Left -> Either.Left(if (parsed.value == BitcoinError.ChainHashMismatch) Problem.OtherNetwork else Problem.NotAnAddress)
        }
    }

    /** [input] as an address on [chain], or null for any [Problem]. */
    fun parseOrNull(chain: Chain, input: String): Parsed? = parse(chain, input).right
}
