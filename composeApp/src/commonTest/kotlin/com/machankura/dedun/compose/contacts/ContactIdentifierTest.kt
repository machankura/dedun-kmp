package com.machankura.dedun.compose.contacts

import com.machankura.dedun.compose.contacts.PhoneNumbers.Normalised
import com.machankura.dedun.compose.contacts.PhoneNumbers.Refusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One canonical spelling per number and address, and the contact's id over it:
 * `docs/non-member-deposits.md`, D2, D3 and Spike S1.
 */
class ContactIdentifierTest {
    private val za = PhoneNumbers.region("ZA")!!

    private fun phone(typed: String, region: String? = "ZA"): String =
        (PhoneNumbers.normalise(typed, region?.let(PhoneNumbers::region)) as Normalised.Number).e164

    private fun refused(typed: String, region: String? = "ZA"): Refusal =
        (PhoneNumbers.normalise(typed, region?.let(PhoneNumbers::region)) as Normalised.Refused).why

    @Test
    fun `every way of typing a South African mobile number is one spelling`() {
        listOf("082 123 4567", "0821234567", "+27 82 123 4567", "+27821234567", "0027821234567", "27821234567", "(082) 123-4567", "+27 (0)82 123 4567".replace("(0)", ""))
            .forEach { assertEquals("+27821234567", phone(it), it) }
    }

    @Test
    fun `the countries in the picker each take their national numbers`() {
        assertEquals("+2348031234567", phone("0803 123 4567", "NG"))
        assertEquals("+254712345678", phone("0712 345678", "KE"))
        assertEquals("+233241234567", phone("024 123 4567", "GH"))
        assertEquals("+256772123456", phone("0772 123456", "UG"))
        assertEquals("+255754123456", phone("0754 123 456", "TZ"))
        assertEquals("+263771234567", phone("077 123 4567", "ZW"))
        assertEquals("+15551234567", phone("(555) 123-4567", "US"))
        assertEquals("+15551234567", phone("1 555 123 4567", "US"))
        assertEquals("+15551234567", phone("011 1 555 123 4567", "US"))
        assertEquals("+447911123456", phone("07911 123456", "GB"))
    }

    @Test
    fun `a number with a plus is taken whatever the picker says`() {
        assertEquals("+2348031234567", phone("+234 803 123 4567", "ZA"))
        assertEquals("+4915112345678", phone("+49 151 12345678", null))
    }

    @Test
    fun `what is not a number is refused, and says why`() {
        assertEquals(Refusal.EMPTY, refused("  "))
        assertEquals(Refusal.NOT_A_NUMBER, refused("082 ABC 4567"))
        assertEquals(Refusal.NOT_A_NUMBER, refused("+0821234567"))
        assertEquals(Refusal.TOO_SHORT, refused("+2712"))
        assertEquals(Refusal.TOO_LONG, refused("+2782123456789012"))
        assertEquals(Refusal.NEEDS_A_COUNTRY, refused("0821234567", null))
        assertEquals(Refusal.WRONG_LENGTH_FOR_COUNTRY, refused("082123456"))
    }

    @Test
    fun `a signer takes only a spelling that is already canonical`() {
        assertTrue(PhoneNumbers.isCanonical("+27821234567"))
        listOf("27821234567", "+27 82 123 4567", "+027821234567", "+2782123456789012", "+27821a34567").forEach {
            assertFalse(PhoneNumbers.isCanonical(it), it)
            assertNull(ContactIdentifier.parse(ContactIdentifier.PHONE, it), it)
        }
    }

    @Test
    fun `a number reads in groups, and the groups are never what is stored`() {
        assertEquals("+27 82 123 4567", PhoneNumbers.formatted("+27821234567"))
        assertEquals("+234 803 123 4567", PhoneNumbers.formatted("+2348031234567"))
        assertEquals("+1 555 123 4567", PhoneNumbers.formatted("+15551234567"))
        assertEquals("+27 82 123 4567", ContactIdentifier.Phone("+27821234567").formatted)
        assertEquals("+27821234567", ContactIdentifier.Phone("+27821234567").text)
    }

    @Test
    fun `an email address is trimmed and lowercased, and nothing else`() {
        assertEquals(Emails.Normalised.Address("mum.k@gmail.com"), Emails.normalise("  Mum.K@Gmail.COM "))
        assertEquals(Emails.Normalised.Address("a+b@x.co.za"), Emails.normalise("a+b@x.co.za"))
        assertEquals(Emails.Normalised.Refused(Emails.Refusal.EMPTY), Emails.normalise(" "))
        assertEquals(Emails.Normalised.Refused(Emails.Refusal.NOT_ASCII), Emails.normalise("mämä@example.com"))
        listOf("mum", "mum@", "@example.com", "mum@example", "mum@@example.com", "mum@example..com", "mum k@example.com").forEach {
            assertIs<Emails.Normalised.Refused>(Emails.normalise(it), it)
        }
        assertIs<Emails.Normalised.Refused>(Emails.normalise("a".repeat(65) + "@example.com"))
        assertNull(ContactIdentifier.parse(ContactIdentifier.EMAIL, "Mum@example.com"))
    }

    @Test
    fun `the contact's id is pinned, and differs by room, type and spelling`() {
        val room = "00".repeat(32)
        // Computed outside the app: a change here gives every contact a new id.
        assertEquals("ed4ff4dacffdaae2bf394fb685056b510207350155b453f1ea8ba4153c3c5f27", ContactIdentifier.Phone("+27821234567").contactId(room))
        assertEquals("11f55462d68fa2bad6963bf219359e6e4c1c617730c940e215a1094a818cba30", ContactIdentifier.Email("mum@example.com").contactId(room))
        assertEquals(ContactIdentifier.Phone("+27821234567").contactId(room), ContactIdentifier.Phone("+27821234567").contactId(room.uppercase()))
        assertNotEquals(ContactIdentifier.Phone("+27821234567").contactId(room), ContactIdentifier.Phone("+27821234567").contactId("11".repeat(32)))
        assertNotEquals(ContactIdentifier.Phone("+27821234567").contactId(room), ContactIdentifier.Phone("+27821234568").contactId(room))
    }

    @Test
    fun `a party is a member or a contact, on the wire and resolved`() {
        val id = "ab".repeat(32)
        val key = "cd".repeat(32)
        assertEquals(Party.Member(id), Party.parseWire(id.uppercase()))
        assertEquals(Party.Contact(id), Party.parseWire("contact:$id"))
        assertEquals("contact:$id", Party.Contact(id).wire)
        assertNull(Party.parseWire("contact:${id.drop(2)}"))
        assertNull(Party.parseWire("group"))
        assertEquals(Party.Member(key), Party.Contact(id).resolve(mapOf(id to key)))
        assertEquals(Party.Contact(id), Party.Contact(id).resolve(emptyMap()))
        assertEquals(Party.Member(id), Party.Member(id).resolve(mapOf(id to key)))
        assertTrue(za.nationalLengths.contains(9))
    }
}
