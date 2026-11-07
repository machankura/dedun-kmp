package com.machankura.dedun.compose.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rule three rotating receive cards share: the address I was handing out is now used. */
class PaidAddressWatchTest {

    private data class Row(val address: String)

    private fun watch() = PaidAddressWatch(Row::address)

    @Test
    fun `nothing is claimed on the first look`() {
        // An address already paid when the screen opened is old news, not an event: saying
        // "payment received" for it would be a lie about when.
        assertNull(watch().seen(current = "two", used = listOf(Row("one"))))
    }

    @Test
    fun `the address that was handed out, once used, is the one reported`() {
        val watch = watch()
        assertNull(watch.seen(current = "one", used = emptyList()))
        assertEquals(Row("one"), watch.seen(current = "two", used = listOf(Row("one"))))
    }

    @Test
    fun `a payment to an address that was never handed out is not reported`() {
        val watch = watch()
        watch.seen(current = "one", used = emptyList())
        // Paying an address further up the branch leaves the current one unpaid and on screen.
        assertNull(watch.seen(current = "one", used = listOf(Row("three"))))
    }

    @Test
    fun `it holds until a later address is paid`() {
        val watch = watch()
        watch.seen(current = "one", used = emptyList())
        assertEquals(Row("one"), watch.seen(current = "two", used = listOf(Row("one"))))
        // A block arriving is not a reason to take the announcement away.
        assertEquals(Row("one"), watch.seen(current = "two", used = listOf(Row("one"))))
        assertEquals(Row("two"), watch.seen(current = "three", used = listOf(Row("one"), Row("two"))))
    }

    @Test
    fun `an address the wallet cannot name yet leaves the last one standing`() {
        val watch = watch()
        watch.seen(current = "one", used = emptyList())
        assertNull(watch.seen(current = null, used = emptyList()))
        // Electrum answering again still knows which address was being handed out.
        assertEquals(Row("one"), watch.seen(current = "two", used = listOf(Row("one"))))
    }
}
