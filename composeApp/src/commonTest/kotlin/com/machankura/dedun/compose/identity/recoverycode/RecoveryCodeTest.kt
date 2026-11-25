package com.machankura.dedun.compose.identity.recoverycode

import fr.acinq.bitcoin.crypto.hmac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The codec on every target. The spec's full vector table runs in
 * `RecoveryCodeVectorsJvmTest`; these are a handful of its rows inline, so that Android and
 * iOS run them too, and the properties the app leans on that no vector states.
 */
class RecoveryCodeTest {

    private val base = "LBZC-MYTK-E9P3-7C7B-AHJN-KX79-22CY-WAY7-K7QZ"

    private fun valid(input: String): RecoveryCode =
        assertIs<RecoveryCode.Parse.Valid>(RecoveryCode.parse(input)).code

    // From v3_vectors.json: the base code and its key, and two random codes and theirs.
    @Test
    fun `the spec's keys`() {
        assertEquals("74b36e622ce1e120de4e8fcd91be629498ffa196fc565da20a3676867693207b", valid(base).rootKey().toHex())
        assertEquals(
            "bcb2551cd4ea5bf335ed9ff74cddfbddb2aa848afce13497e62e9f682bb02137",
            valid("LB33-MXW2-RPRD-ZREJ-QRHW-RBW9-982X-HLSC-Z2U8").rootKey().toHex(),
        )
        assertEquals(
            "950788cea9b20692c244849317668baa82130b1ebe091786b7cca66d241f2233",
            valid("LBDD-UYP8-Z59U-FV4M-VZXR-HRV9-LAZA-FCMD-UB8Q").rootKey().toHex(),
        )
    }

    @Test
    fun `a code typed any way is the same code`() {
        val canonical = valid(base)
        assertEquals(canonical, valid(base.lowercase()))
        assertEquals(canonical, valid(base.replace('-', ' ')))
        assertEquals(canonical, valid(base.replace("-", "")))
        assertEquals(canonical, valid("  ${base.replace('-', '\t')}\n"))
        assertEquals(base, canonical.canonical)
    }

    /**
     * The key hashes the canonical string *with* its dashes. Two implementations that
     * disagree about that derive two different people from one piece of paper.
     */
    @Test
    fun `the dashes are part of what is hashed`() {
        val withoutDashes = fr.acinq.bitcoin.crypto.Digest.sha256().hmac(
                "muun:rc".encodeToByteArray(),
            base.replace("-", "").encodeToByteArray(),
            64,
        )
        assertNotEquals(valid(base).rootKey().toByteArray().toList(), withoutDashes.toList())
    }

    @Test
    fun `one wrong symbol is a suggestion, not a code`() {
        val typo = assertIs<RecoveryCode.Parse.Typo>(RecoveryCode.parse("LBTP-WJRN-FXBT-U8KT-ZP5P-AQN9-XK3J-K3CV-2TB5"))
        assertEquals(17, typo.position)
        assertEquals(5, typo.block)
        assertEquals(2, typo.character)
        assertEquals('4', typo.suggestedSymbol)
        assertEquals("LBTP-WJRN-FXBT-U8KT-Z45P-AQN9-XK3J-K3CV-2TB5", typo.suggested.canonical)
    }

    @Test
    fun `two wrong symbols are refused without a suggestion`() {
        assertEquals(
            RecoveryCode.Parse.Invalid(RecoveryCodeProblem.Checksum),
            RecoveryCode.parse("LBTP-RJRN-FXBT-U8KT-Z45P-AQN9-XK33-K3CV-2TB5"),
        )
    }

    @Test
    fun `older versions are recognised and not accepted`() {
        assertEquals(RecoveryCode.Parse.UnsupportedVersion(2), RecoveryCode.parse("LA2Q-48Z3-25JR-S5JB-5SUS-HXHJ-RCMM-8YUA"))
        assertEquals(RecoveryCode.Parse.UnsupportedVersion(1), RecoveryCode.parse("R52Q-48Z3-25JR-S5JB-5SUS-HXHJ-RCMM-8YUA"))
    }

    @Test
    fun `not a code`() {
        assertEquals(RecoveryCode.Parse.Invalid(RecoveryCodeProblem.Length), RecoveryCode.parse(""))
        assertEquals(RecoveryCode.Parse.Invalid(RecoveryCodeProblem.Length), RecoveryCode.parse("abandon ability able"))
        assertEquals(RecoveryCode.Parse.Invalid(RecoveryCodeProblem.Version), RecoveryCode.parse("LC"))
    }

    @Test
    fun `a generated code parses back to itself`() {
        repeat(20) {
            val code = RecoveryCode.generate()
            assertEquals(code, valid(code.canonical))
            assertEquals(9, code.blocks.size)
            assertTrue(code.canonical.startsWith("LB"))
        }
    }

    /**
     * Bytes below 16 are thrown away, so that the 240 kept are eight of each of the thirty
     * symbols. Fed 0..15 and then 16 over and over, every random symbol is the alphabet's
     * `16 % 30`th, and none is its first.
     */
    @Test
    fun `generation rejects the biased bytes`() {
        var next = 0
        val source: (Int) -> ByteArray = { n -> ByteArray(n) { (if (next < 16) next++ else 16).toByte() } }
        val code = RecoveryCode.generate(source)
        val random = code.canonical.replace("-", "").substring(2, 32)
        assertEquals(RecoveryCodeFormat.ALPHABET[16].toString().repeat(30), random)
    }

    @Test
    fun `the code is never printed`() {
        val code = valid(base)
        assertFalse(code.toString().contains("LBZC"))
        assertFalse(RecoveryCode.Parse.Valid(code).toString().contains("LBZC"))
    }

    @Test
    fun `block count from a prefix`() {
        assertEquals(9, RecoveryCode.blockCount("lb"))
        assertEquals(8, RecoveryCode.blockCount("LA2Q"))
        assertEquals(8, RecoveryCode.blockCount("R5"))
        assertEquals(null, RecoveryCode.blockCount("L"))
        assertEquals(null, RecoveryCode.blockCount("LC"))
    }
}
