package com.machankura.dedun.compose.notifications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room3.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.ui.composable.NotificationSettingsScreen

/** The switches are stored per identity, and the screen writes them. Phase 7. */
class NotificationSettingsJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val store = NotificationSettingsStore(db)
    private val user = "a".repeat(64)

    @AfterTest
    fun closeDb() = db.close()

    private fun ledger() = runBlocking { db.notificationDao().getLedger(user)!! }

    @Test
    fun `the defaults are messages and approvals on, group activity off, text shown`() = runBlocking {
        db.notificationDao().arm(user, Instant.fromEpochSeconds(1_800_000_000))
        assertEquals(NotificationSettings(), ledger().settings)
        assertFalse(ledger().settings.groupActivity)
    }

    @Test
    fun `saving leaves the cursor alone`() = runBlocking {
        db.notificationDao().arm(user, Instant.fromEpochSeconds(1_800_000_000))
        db.notificationDao().advanceCursor(user, 42)
        store.save(user, NotificationSettings(messages = false, showMessageText = false))
        assertEquals(NotificationSettings(messages = false, showMessageText = false), ledger().settings)
        assertEquals(42, ledger().cursorMessageId)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `before arming the screen says why there is nothing to set, and after it a row is a switch`() {
        runDesktopComposeUiTest {
            setContent { NotificationSettingsScreen(activeUserPublicKey = user, store = store, onNavigateBack = {}) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTextPresent("first sync")
            }

            runBlocking { db.notificationDao().arm(user, Instant.fromEpochSeconds(1_800_000_000)) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextPresent("Show message text") }

            onNodeWithText("Show message text").performClick()
            waitUntil(timeoutMillis = 5_000) { !ledger().showMessageText }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextPresent(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
