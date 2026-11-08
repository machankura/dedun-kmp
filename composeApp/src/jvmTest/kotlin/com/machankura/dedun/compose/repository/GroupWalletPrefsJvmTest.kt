package com.machankura.dedun.compose.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The store Phases 3 and 10 each needed and neither had.
 *
 * What is worth asserting is the scoping. One member with two wallets, two members on one device: each
 * of those is a way for a preference to leak somewhere it does not belong, and the one that would
 * matter is an acknowledgement standing in for a member who never made it.
 */
class GroupWalletPrefsJvmTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val wallet = "wavelength:room:signet:0"
    private val other = "wavelength:room:signet:1"

    private fun prefs(name: String) = GroupWalletPrefs(
        PreferenceDataStoreFactory.createWithPath {
            Files.createTempFile(name, ".preferences_pb").also { it.toFile().delete() }.toString().toPath()
        }
    )

    @Test
    fun `a balance nobody has hidden is not hidden`() = runBlocking {
        assertFalse(prefs("fresh").balanceHidden(wallet).first())
    }

    @Test
    fun `hiding a balance is remembered, and can be undone`() = runBlocking {
        val store = prefs("hide")
        store.setBalanceHidden(wallet, true)
        assertTrue(store.balanceHidden(wallet).first())
        store.setBalanceHidden(wallet, false)
        assertFalse(store.balanceHidden(wallet).first())
    }

    /** A group with two wallets, or the same group on two networks, keeps them apart. */
    @Test
    fun `hiding one wallet's balance does not hide another's`() = runBlocking {
        val store = prefs("two-wallets")
        store.setBalanceHidden(wallet, true)
        assertTrue(store.balanceHidden(wallet).first())
        assertFalse(store.balanceHidden(other).first())
    }

    /**
     * False where there is no record, which is the whole point: a member who joined after the wallet
     * was opened has never been shown the six sentences, and this is how the screen finds out.
     */
    @Test
    fun `a member who has not acknowledged has not acknowledged`() = runBlocking {
        assertFalse(prefs("unacked").acknowledged(wallet, alice).first())
    }

    @Test
    fun `acknowledging is remembered`() = runBlocking {
        val store = prefs("ack")
        store.acknowledge(wallet, alice)
        assertTrue(store.acknowledged(wallet, alice).first())
    }

    /** The one that would matter: nobody acknowledges on anybody else's behalf. */
    @Test
    fun `one member acknowledging does not acknowledge for another on the same device`() = runBlocking {
        val store = prefs("two-members")
        store.acknowledge(wallet, alice)
        assertTrue(store.acknowledged(wallet, alice).first())
        assertFalse(store.acknowledged(wallet, bob).first())
    }

    /** Nor for the same member in a different wallet of the group's. */
    @Test
    fun `acknowledging one wallet does not acknowledge another`() = runBlocking {
        val store = prefs("ack-two-wallets")
        store.acknowledge(wallet, alice)
        assertFalse(store.acknowledged(other, alice).first())
    }

    /** A key's case is not a different member. */
    @Test
    fun `a member's key is matched whatever case it is written in`() = runBlocking {
        val store = prefs("case")
        store.acknowledge(wallet, alice.uppercase())
        assertTrue(store.acknowledged(wallet, alice).first())
    }

    /** The two preferences do not stand in for each other. */
    @Test
    fun `hiding a balance is not acknowledging a wallet`() = runBlocking {
        val store = prefs("apart")
        store.setBalanceHidden(wallet, true)
        assertFalse(store.acknowledged(wallet, alice).first())
        store.acknowledge(other, alice)
        assertFalse(store.balanceHidden(other).first())
    }
}
