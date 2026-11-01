package com.machankura.dedun.compose.wallet.ark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which desktops have a Bark library -- `docs/bark-wallet.md`, Phase 11. */
class ArkJvmLibraryJvmTest {
    @Test
    fun `each desktop looks under JNA's prefix for it`() {
        assertEquals("darwin-aarch64/libbark_ffi_kotlin.dylib", ArkJvmLibrary.resourceFor("Mac OS X", "aarch64"))
        assertEquals("darwin-x86-64/libbark_ffi_kotlin.dylib", ArkJvmLibrary.resourceFor("Mac OS X", "x86_64"))
        assertEquals("win32-x86-64/bark_ffi_kotlin.dll", ArkJvmLibrary.resourceFor("Windows 11", "amd64"))
        assertEquals("linux-aarch64/libbark_ffi_kotlin.so", ArkJvmLibrary.resourceFor("Linux", "aarch64"))
        assertNull(ArkJvmLibrary.resourceFor("Linux", "riscv64"))
    }

    @Test
    fun `Linux x86-64 always has one, and any other desktop only once its library is built`() {
        assertTrue(ArkJvmLibrary.present("Linux", "amd64") { false })
        assertFalse(ArkJvmLibrary.present("Mac OS X", "aarch64") { false })
        assertTrue(ArkJvmLibrary.present("Mac OS X", "aarch64") { it == "darwin-aarch64/libbark_ffi_kotlin.dylib" })
        assertFalse(ArkJvmLibrary.present("Windows 11", "amd64") { it == "darwin-aarch64/libbark_ffi_kotlin.dylib" })
    }
}
