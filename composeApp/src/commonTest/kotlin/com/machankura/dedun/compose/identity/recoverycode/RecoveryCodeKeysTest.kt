package com.machankura.dedun.compose.identity.recoverycode

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.MnemonicLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Who a recovery code is. These values are permanent: a code's words, npub, profile id and
 * Lightning node are the person and their wallet, and a change here is a different person
 * behind the same piece of paper.
 *
 * Dedun derives a phrase's keys through lightning-kmp's `LocalKeyManager` on
 * `NodeParamsManager.chain` (`StoredIdentity.nostrPrivateKeyOf`, `IdentityWriter.writeMnemonic`),
 * so a code does too, and the values below are mainnet's -- the chain every release runs on.
 * The node id is Dedun's own vector: Giza keeps no node. Its hash160 is the profile id, which
 * is how the independent script below vouches for it.
 *
 * The codes are the spec's (v3_vectors.json: the base code for its error cases, and two of
 * its random codes); the root keys are the spec's too. Everything after the root key is
 * Dedun's -- no other app turns a recovery code into a phrase -- so it was computed by an
 * independent derivation, docs/scripts/recovery-code-keys.py, which shares no code with
 * the app and reproduces NIP-06's own vector and `ForkCompatibilityTest`'s profile id.
 */
class RecoveryCodeKeysTest {

    private data class Pinned(
        val code: String,
        val rootKey: String,
        val words: String,
        val nostrSecret: String,
        val npub: String,
        val profileId: String,
        val nodeId: String,
    )

    private val pinned = listOf(
        Pinned(
            code = "LBZC-MYTK-E9P3-7C7B-AHJN-KX79-22CY-WAY7-K7QZ",
            rootKey = "74b36e622ce1e120de4e8fcd91be629498ffa196fc565da20a3676867693207b",
            words = "input opinion obtain flush bulk motor junior spin snow mistake obvious circle morning speak fossil member frozen aware mirror unfair guess end limit honey",
            nostrSecret = "9f1941e369a8d8a6a09297ed8e301a86bdfb41a7f7938ba0eb4b91e3e61930ed",
            npub = "ece2bc031552ccd5062f6fb933f7e00187e6391df2b81fe0a83e697a7589b89f",
            profileId = "afa04673cf64f3f40933f5ab74479cb58e3340b6",
            nodeId = "029d6b57a406d5c0f27dd00e858d84dbdea7026939d36c7adc749e99e3b3a70fce",
        ),
        Pinned(
            code = "LB33-MXW2-RPRD-ZREJ-QRHW-RBW9-982X-HLSC-Z2U8",
            rootKey = "bcb2551cd4ea5bf335ed9ff74cddfbddb2aa848afce13497e62e9f682bb02137",
            words = "royal naive elbow prefer pizza wet stumble recycle warrior group leave rocket click drastic cloud order cruel sand merry disagree live submit luggage twin",
            nostrSecret = "0177ca48584dd3fe733ee5b484e16e3c0979291011e5d94caaf1452735847f02",
            npub = "2873b2daf9474090d4cec5901730407b7460af4d05fb81bd9e4c37a9c7af7fbd",
            profileId = "a67aacd81a80dcdb007cc3ce40df5ef9a7e467af",
            nodeId = "02fb0755918d5e632d11fd33410ad3c334fff353611cc0c3a11088de02ef5a0d3a",
        ),
        Pinned(
            code = "LBDD-UYP8-Z59U-FV4M-VZXR-HRV9-LAZA-FCMD-UB8Q",
            rootKey = "950788cea9b20692c244849317668baa82130b1ebe091786b7cca66d241f2233",
            words = "never detail crisp fat call enact animal mountain narrow robot pepper fence cancel machine kit scorpion bleak cup veteran error spoil auto match gossip",
            nostrSecret = "152e3a544de5076c5282b2305d6d2a86b57b9064e48088cbe0878214c75b3c7c",
            npub = "2070ddc08f7eb62864da9adaf1f1aeba1bb2f6bda49c224dfde2c6d82ccd81f7",
            profileId = "fccf643a975aa477a936690d56123e668dd2b1bf",
            nodeId = "031a01b1b3935b0c7dabee9d5866b10f0af8cd1a2a1af7c3cc95f0726ba8a0e6f5",
        ),
    )

    private fun mainnetKeys(words: List<String>) = LocalKeyManager(
        seed = MnemonicCode.toSeed(words, "").byteVector(),
        chain = Chain.Mainnet,
        remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub,
    )

    private fun code(text: String): RecoveryCode = assertIs<RecoveryCode.Parse.Valid>(RecoveryCode.parse(text)).code

    @Test
    fun `a code's words, npub, profile id and node`() {
        pinned.forEach { p ->
            val code = code(p.code)
            assertEquals(p.rootKey, code.rootKey().toHex(), p.code)
            val words = RecoveryCodeKeys.words(code)
            assertEquals(p.words, words.joinToString(" "), p.code)
            val keys = mainnetKeys(words)
            assertEquals(p.nostrSecret, keys.nostrPrivateKey().value.toHex(), p.code)
            assertEquals(p.npub, keys.nostrPrivateKey().nostrPublicKeyHex(), p.code)
            val nodeId = keys.nodeKeys.nodeKey.publicKey
            assertEquals(p.nodeId, nodeId.toHex(), p.code)
            assertEquals(p.profileId, WalletId(nodeId).nodeIdHash, p.code)
        }
    }

    /**
     * The words are a phrase, which is the whole point of the derivation: typed into any
     * NIP-06 client, or into Dedun's sign-in as words, they are the same person.
     */
    @Test
    fun `a code's words are a valid 24-word phrase`() {
        repeat(10) {
            val words = RecoveryCodeKeys.words(RecoveryCode.generate())
            assertEquals(24, words.size)
            MnemonicCode.validate(mnemonics = words, wordlist = MnemonicLanguage.English.wordlist())
        }
    }
}
