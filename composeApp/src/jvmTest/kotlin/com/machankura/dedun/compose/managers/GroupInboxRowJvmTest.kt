package com.machankura.dedun.compose.managers

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.ui.view.state.InboxRowSummary

/**
 * Who sees the group's inbox row (docs/group-inbox.md, D9): an admin who could set one up, an
 * admin while the ceremony runs, an admin who holds the key with what is unread -- and a member
 * who is not an admin, nobody.
 */
class GroupInboxRowJvmTest {
    private val harness = GroupInboxHarness(watchers = listOf("dineo"))

    @AfterTest
    fun close() = harness.close()

    private suspend fun GroupInboxHarness.Device.row(): InboxRowSummary? =
        DatabaseChatRepository(db, CoroutineScope(Dispatchers.Default)).inboxSummary(room())

    @Test
    fun `an admin is offered to set one up, a member is shown nothing`() = runBlocking<Unit> {
        val alice = harness.device("alice")
        val dineo = harness.device("dineo")
        assertEquals(InboxRowSummary(holdsKey = false, unread = 0, ceremonyRunning = false, canSetUp = true), alice.row())
        assertNull(dineo.row())
    }

    @Test
    fun `a running ceremony, then the key, show on the row`() = runBlocking<Unit> {
        val (alice, bob, carol) = listOf("alice", "bob", "carol").map { harness.device(it) }
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        assertEquals(true, alice.row()?.ceremonyRunning)
        harness.pump(alice, bob, carol)
        val row = assertNotNull(bob.row())
        assertEquals(true, row.holdsKey)
        assertEquals(0, row.unread)
    }

    @Test
    fun `a release build shows no row`() = runBlocking<Unit> {
        val alice = harness.device("alice")
        ExperimentalFeatures.groupInboxOverride = false
        assertNull(alice.row())
    }
}
