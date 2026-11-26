package com.machankura.dedun.compose.notifications

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatMessage

/** docs/notifications-and-background-sync.md, D7 and Phase 5. */
class NotificationClassifierJvmTest {

    /** Every `TYPE_` constant on [ChatMessage], read off the class so a new one cannot be missed. */
    private val allTypes: Map<String, String> =
        ChatMessage::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TYPE_") && it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .associate { it.name to it.get(null) as String }

    @Test
    fun `every transcript type has decided whether it notifies, exactly once`() {
        assertTrue(allTypes.size > 50, "found only ${allTypes.size} types; is the reflection reading the right class?")

        val sets = listOf(
            NotificationClassifier.MESSAGES,
            NotificationClassifier.NEEDS_YOU,
            NotificationClassifier.GROUP_ACTIVITY,
            NotificationClassifier.SILENT,
        )
        val undecided = allTypes.filterValues { type -> sets.none { type in it } }
        assertEquals(emptyMap(), undecided, "these types notify or not by accident; put each in one of the classifier's sets")

        val twice = allTypes.filterValues { type -> sets.count { type in it } > 1 }
        assertEquals(emptyMap(), twice, "these types are in more than one set")
    }

    private fun line(type: String, mine: Boolean) = ChatMessage(
        senderPublicKey = "a".repeat(64),
        isUserMessage = mine,
        giftWrapPayloadId = null,
        marmotGroupEventId = null,
        marmotInnerEventId = null,
        chatRoomId = "room",
        content = "x",
        messageType = type,
        createdAt = Instant.fromEpochSeconds(1_800_000_000),
    )

    @Test
    fun `a message notifies when somebody else said it`() {
        assertEquals(NotificationCategory.Messages, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_MESSAGE, mine = false)))
        assertEquals(NotificationCategory.Messages, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_DIRECT_MESSAGE, mine = false)))
        assertNull(NotificationClassifier.categoryOf(line(ChatMessage.TYPE_MESSAGE, mine = true)))
    }

    /**
     * The device writes these to its own reader, as `isUserMessage = true`. Dropping our own lines
     * here, as for the other two, would silence every approval request there is.
     */
    @Test
    fun `an approval request notifies though this device wrote it`() {
        NotificationClassifier.NEEDS_YOU.forEach { type ->
            assertEquals(NotificationCategory.NeedsYou, NotificationClassifier.categoryOf(line(type, mine = true)), type)
        }
    }

    @Test
    fun `group activity this device caused does not notify it`() {
        assertEquals(NotificationCategory.GroupActivity, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_MEMBER_LEFT, mine = false)))
        assertNull(NotificationClassifier.categoryOf(line(ChatMessage.TYPE_MEMBER_LEFT, mine = true)))
    }

    @Test
    fun `a step, a refusal or a type nobody declared is silent`() {
        assertNull(NotificationClassifier.categoryOf(line(ChatMessage.TYPE_FROST_NONCE, mine = false)))
        assertNull(NotificationClassifier.categoryOf(line(ChatMessage.TYPE_LIGHTNING_REFUSED, mine = false)))
        assertNull(NotificationClassifier.categoryOf(line("unsupported", mine = false)))
    }

    /** Dedun's wallets: money that moved is group activity, and a wallet waiting on this device needs it. */
    @Test
    fun `a payment is group activity and an Iceberg approval needs you`() {
        assertEquals(NotificationCategory.GroupActivity, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_LIGHTNING_PAYMENT_RECEIVED, mine = false)))
        assertEquals(NotificationCategory.GroupActivity, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_WAVELENGTH_PAID_OUT, mine = false)))
        assertEquals(NotificationCategory.NeedsYou, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_JOIN, mine = true)))
        assertEquals(NotificationCategory.NeedsYou, NotificationClassifier.categoryOf(line(ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION, mine = false)))
    }
}
