package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mls.codec.TlsReader
import com.vitorpamplona.quartz.marmot.mls.framing.MlsMessage
import com.vitorpamplona.quartz.marmot.mls.framing.WireFormat
import com.vitorpamplona.quartz.marmot.mls.messages.Welcome

/**
 * Which of this device's KeyPackage bundles a Welcome is addressed to.
 *
 * A Welcome's `e` tag names the kind:30443 the inviter *thinks* it consumed, and
 * that can be wrong: MDK stamps the event id of the copy cached in its user
 * directory, so a peer that rotated its published KeyPackage while an inviter kept
 * inviting from cache is unjoinable by the tag alone. The Welcome itself is the
 * authority. RFC 9420 §12.4.3.1 has the joiner walk its `EncryptedGroupSecrets`
 * in order and take the first whose `new_member` is a KeyPackageRef it holds the
 * private keys for -- which is what OpenMLS does, and what quartz's own inbound
 * processor does before it falls back to the tag.
 *
 * Pure: a Welcome in, a choice out, no database. The refs are compared over the
 * bundle's stored KeyPackage bytes, decoded through [KeyPackageUtils.decodeKeyPackage]
 * so a bundle stored framed (the current profile's shape) and one stored bare (the
 * legacy shape) both resolve.
 *
 * See docs/marmot-v2.md, Phase 1.
 */
object WelcomeBundleMatch {
    /**
     * The KeyPackageRefs [welcomeBytes] is addressed to, in the sender's order.
     *
     * Empty when the bytes are not a Welcome or do not parse, which the caller
     * treats as "nothing to match", not as an error: the `e` tag is still there.
     */
    fun addressedRefs(welcomeBytes: ByteArray): List<ByteArray> =
        try {
            val message = MlsMessage.decodeTls(TlsReader(welcomeBytes))
            if (message.wireFormat != WireFormat.WELCOME) {
                emptyList()
            } else {
                Welcome.decodeTls(TlsReader(message.payload)).secrets.map { it.newMember }
            }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * The first of [candidates] whose KeyPackage the Welcome names, or null.
     *
     * Order is the Welcome's, not the candidates': a sender that addressed several of
     * our KeyPackages picked one first, and RFC 9420 says the joiner honours that.
     * A candidate whose bytes do not decode is skipped rather than fatal -- one
     * unreadable row must not hide the one that matches.
     */
    fun <T> select(
        welcomeBytes: ByteArray,
        candidates: List<T>,
        keyPackageBytesOf: (T) -> ByteArray,
    ): T? {
        val refs = addressedRefs(welcomeBytes)
        if (refs.isEmpty() || candidates.isEmpty()) return null

        val byRef = candidates.mapNotNull { candidate ->
            runCatching {
                KeyPackageUtils.decodeKeyPackage(keyPackageBytesOf(candidate)).reference()
            }.getOrNull()?.let { ref -> ref to candidate }
        }

        for (ref in refs) {
            byRef.firstOrNull { (candidateRef, _) -> candidateRef.contentEquals(ref) }
                ?.let { return it.second }
        }
        return null
    }
}
