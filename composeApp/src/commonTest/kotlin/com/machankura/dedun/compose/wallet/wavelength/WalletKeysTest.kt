package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These vectors were computed by the daemon's own code, `hdkeychain` from the words' seed, and
 * `walletdesc.ParseAccountKeys` accepted them, at `63d545fb` of `machankura/wavelength`. The BIP86
 * account is also BIP86's published vector. **A change here moves where the coins are**, so it is
 * not to be fixed by updating the vector.
 */
class WalletKeysTest {
    private val words = ("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about").split(" ")

    @Test
    fun `mainnet gives the daemon's own account and family keys`() {
        assertEquals(
            listOf(
                "tr([73c5da0a/86h/0h/0h]xprv9xgqHN7yz9MwCkxsBPN5qetuNdQSUttZNKw1dcYTV4mkaAFiBVGQziHs3NRSWMkCzvgjEe3n9xV8oYywvM8at9yRqyaZVz6TYYhX98VjsUk/<0;1>/*)",
                "[73c5da0a/1017h/0h]xprv9xVs9YduMWm6AVGJbM79g6N6tFhYJqckYVJ2puRTd9QGcqrmcH6c2aXgsUoPP3QeDsDGBZGqPTyejwwNBgoQpYg8JpCirrChtzmdzzsSvZQ",
            ),
            WalletKeys.descriptors(words, Network.MAINNET),
        )
    }

    @Test
    fun `signet keeps coin type 0 on the chain and takes 1 for the families`() {
        assertEquals(
            listOf(
                "tr([73c5da0a/86h/0h/0h]tprv8fMn4hSKPRC1oaCPqxDb1JWtgkpeiQvZhsr8W2xuy3GEMkzoArcAWTfJxYb6Wj8XNNDWEjfYKK4wGQXh3ZUXhDF2NcnsALpWTeSwarJt7Vc/<0;1>/*)",
                "[73c5da0a/1017h/1h]tprv8fAovsxEknbAojFrXzViymrR9UwgHkwkuSM7hLpjUVmG4xLmkyDiVpxVn3d8YKXapwK7hs2hu4P3aDzrurneF9zkvciaTfFVZTLiWGnHNKt",
            ),
            WalletKeys.descriptors(words, Network.SIGNET),
        )
    }

    @Test
    fun `the phoenix final wallet's account never reaches the daemon`() {
        for (network in listOf(Network.MAINNET, Network.SIGNET)) {
            val descriptors = WalletKeys.descriptors(words, network)
            assertFalse(descriptors.any { it.startsWith("wpkh") || "/84h" in it }, "a wpkh account would share m/84' with Phoenix")
            assertEquals(2, descriptors.size)
        }
    }

    @Test
    fun `no entry is the master key`() {
        // Depth is in every entry's origin: three steps for the account, two for the families.
        for (entry in WalletKeys.descriptors(words, Network.MAINNET)) {
            val origin = entry.substringAfter('[').substringBefore(']').split('/')
            assertTrue(origin.size >= 3, "$origin has no path under the fingerprint")
        }
    }
}
