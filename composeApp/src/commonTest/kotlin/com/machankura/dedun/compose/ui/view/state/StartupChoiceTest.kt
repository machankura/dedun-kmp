package com.machankura.dedun.compose.ui.view.state

import fr.acinq.phoenix.data.WalletId
import com.machankura.dedun.compose.identity.StoredIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The table, one case per row, and the two orderings Phase 3 of
 * docs/multiple-profiles.md exists for: what a switch or a sign-in named outranks the
 * user's earlier "show me the list", and one identity with "show me the list" still
 * shows it, because the user asked.
 */
class StartupChoiceTest {

    private val a = StoredIdentity.NostrPublic(WalletId("aa".repeat(20)), "a1".repeat(32))
    private val b = StoredIdentity.NostrPublic(WalletId("bb".repeat(20)), "b2".repeat(32))
    private val two = mapOf(a.id to a, b.id to b)

    private fun resolve(
        force: WalletId? = null,
        desired: WalletId? = null,
        startImmediately: Boolean = true,
        identities: Map<WalletId, StoredIdentity> = two,
        default: WalletId? = null,
    ) = StartupChoice.resolve(force, desired, startImmediately, identities, default)

    @Test
    fun `a forced id wins over everything`() {
        assertEquals(a, resolve(force = a.id, desired = b.id, startImmediately = false, default = b.id))
    }

    @Test
    fun `what a switch or a sign-in named is opened even after the user once asked for the list`() {
        assertEquals(b, resolve(desired = b.id, startImmediately = false))
    }

    @Test
    fun `the user asking for the list is honoured over a default and over a single identity`() {
        assertNull(resolve(startImmediately = false, default = a.id))
        assertNull(resolve(startImmediately = false, identities = mapOf(a.id to a)))
    }

    @Test
    fun `one identity opens without asking`() {
        assertEquals(a, resolve(identities = mapOf(a.id to a)))
    }

    @Test
    fun `the last profile opened is the one a cold boot opens`() {
        assertEquals(b, resolve(default = b.id))
    }

    @Test
    fun `a default the list no longer has or no default shows the list`() {
        assertNull(resolve(default = WalletId("cc".repeat(20))))
        assertNull(resolve())
    }

    @Test
    fun `an id that is not listed opens nothing`() {
        assertNull(resolve(desired = WalletId("cc".repeat(20))))
        assertNull(resolve(force = WalletId("cc".repeat(20))))
    }
}
