package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent

/**
 * The manifest a group's watch-only daemon opens with -- `docs/group-wavelength-wallet.md`, D4 -- as the exact bytes
 * `walletdesc.Manifest.Canonical` writes, because the statement carries their hash and `info.manifestHash` is compared
 * with it: sorted keys, no whitespace, checksummed entries, and `<` and `>` escaped as Go's `encoding/json` does.
 *
 * Public: it reveals every address the wallet will use, and that is what lets a member check one for itself.
 */
class GroupArkManifest private constructor(val canonical: String) {
    /** `walletdesc.Manifest.HashHex`. */
    val hash: String get() = Hex.encode(Crypto.sha256(canonical.encodeToByteArray()))

    companion object {
        fun of(icebergKey: PublicKey, auth: GroupArkAuth, chain: Chain, wallet: Long): GroupArkManifest {
            val prefix = if (chain.isMainnet()) DeterministicWallet.xpub else DeterministicWallet.tpub
            val slot = { s: GroupArkDerivation.Slot -> GroupArkDerivation.account(icebergKey, chain, wallet, s).xpub }
            // The group's slots name a fingerprint of the group's own: nothing derives through it, and it is what says these slots are not the primary root's.
            val origin = slot(GroupArkDerivation.Slot.ONCHAIN).fingerprint().toString(16).padStart(8, '0')
            val coin = GroupArkDerivation.coinType(chain)

            val onChain = BIP380.withChecksum("tr([$origin/86h/0h/0h]${slot(GroupArkDerivation.Slot.ONCHAIN).encode(prefix)}/<0;1>/*)")
            val entry = { fingerprint: String, family: Long, xpub: DeterministicWallet.ExtendedPublicKey ->
                family to BIP380.withChecksum("[$fingerprint/1017h/${coin}h/${family}h]${xpub.encode(prefix)}/0/*")
            }
            val families = buildList {
                GroupArkDerivation.Slot.entries.filter { it.family != null }.forEach { add(entry(origin, it.family!!, slot(it))) }
                add(entry(auth.fingerprint, GroupArkAuth.IDENTITY, auth.identityXpub()))
            }.sortedBy { it.first.toString() } // encoding/json sorts a map's keys as strings: "202" before "42"

            val json = buildString {
                append("{\"families\":{")
                append(families.joinToString(",") { (family, text) -> "\"$family\":\"$text\"" })
                append("},\"network\":\"").append(GroupWalletStatementEvent.chainName(chain)).append('"')
                append(",\"onchain\":\"").append(onChain).append('"')
                append(",\"primary_fingerprint\":\"").append(auth.fingerprint).append('"')
                append(",\"version\":1}")
            }
            return GroupArkManifest(json.replace("<", "\\u003c").replace(">", "\\u003e"))
        }
    }
}

/** BIP380's descriptor checksum, as `walletdesc/checksum.go` has it. */
internal object BIP380 {
    private const val INPUT = "0123456789()[],'/*abcdefgh@:\$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
    private const val CHECKSUM = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = longArrayOf(0xf5dee51989L, 0xa9fdca3312L, 0x1bab10e32dL, 0x3706b1677aL, 0x644d626ffdL)

    private fun polymod(symbols: List<Long>): Long = symbols.fold(1L) { chk, value ->
        val top = chk ushr 35
        GENERATOR.foldIndexed(((chk and 0x7ffffffffL) shl 5) xor value) { i, acc, gen -> if ((top ushr i) and 1L == 1L) acc xor gen else acc }
    }

    fun checksum(text: String): String {
        val symbols = mutableListOf<Long>()
        val groups = mutableListOf<Long>()
        text.forEach { c ->
            val position = INPUT.indexOf(c)
            require(position >= 0) { "a character outside BIP380's set" }
            symbols += (position and 31).toLong()
            groups += (position shr 5).toLong()
            if (groups.size == 3) {
                symbols += groups[0] * 9 + groups[1] * 3 + groups[2]
                groups.clear()
            }
        }
        when (groups.size) {
            1 -> symbols += groups[0]
            2 -> symbols += groups[0] * 3 + groups[1]
        }
        val value = polymod(symbols + List(8) { 0L }) xor 1L
        return (0 until 8).joinToString("") { i -> CHECKSUM[((value ushr (5 * (7 - i))) and 31).toInt()].toString() }
    }

    fun withChecksum(text: String): String = "$text#${checksum(text)}"
}
