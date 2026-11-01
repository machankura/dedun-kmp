package com.machankura.dedun.compose.wallet.ark

/**
 * bark-jvm carries one native library, for Linux x86-64 with glibc 2.34 or later. Any other
 * desktop has a wallet when its own library has been built and put on the classpath under
 * JNA's prefix for it -- `docs/scripts/bark-natives.sh`, Phase 11 -- and says the wallet is
 * not available on this device when it has not. Checked from the system properties and the
 * classpath, so JNA never tries to load a library that is not there.
 */
actual object ArkPlatform {
    actual val supported: Boolean = ArkJvmLibrary.present(
        os = System.getProperty("os.name").orEmpty(),
        arch = System.getProperty("os.arch").orEmpty(),
        hasResource = { path -> ArkPlatform::class.java.classLoader?.getResource(path) != null },
    )

    actual suspend fun open(config: ArkConfig, words: String, datadir: String): ArkWallet {
        if (!supported) throw ArkError.Other("No Bark library for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        return openBarkWallet(config, words, datadir)
    }
}

/** Where Bark's library is on the desktop, by JNA's resource prefixes. */
internal object ArkJvmLibrary {
    /** JNA's prefix and the library's file name for [os] and [arch], or null for a platform Bark has no build for. */
    fun resourceFor(os: String, arch: String): String? {
        val system = os.lowercase()
        val cpu = when (arch.lowercase()) {
            "amd64", "x86_64" -> "x86-64"
            "aarch64", "arm64" -> "aarch64"
            else -> return null
        }
        return when {
            "linux" in system -> "linux-$cpu/libbark_ffi_kotlin.so"
            "mac" in system || "darwin" in system -> "darwin-$cpu/libbark_ffi_kotlin.dylib"
            "windows" in system -> "win32-$cpu/bark_ffi_kotlin.dll"
            else -> null
        }
    }

    fun present(os: String, arch: String, hasResource: (String) -> Boolean): Boolean {
        val system = os.lowercase()
        val cpu = arch.lowercase()
        // bark-jvm's own library sits at the jar's root rather than under a prefix.
        if ("linux" in system && (cpu == "amd64" || cpu == "x86_64")) return true
        return resourceFor(os, arch)?.let(hasResource) ?: false
    }
}
