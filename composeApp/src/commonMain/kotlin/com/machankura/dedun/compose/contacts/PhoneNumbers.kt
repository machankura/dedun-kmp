package com.machankura.dedun.compose.contacts

/**
 * Phone numbers as one canonical E.164 spelling: `docs/non-member-deposits.md`, D2 and Spike S1.
 *
 * Hand-written rather than libphonenumber, on purpose. A contact's id is a hash of the spelling
 * (D3), so every build has to spell one number the same way for as long as a group's records
 * last -- and a library's metadata moves between releases. This checks lengths, never whether a
 * range is allocated: the group wants one spelling, not validation.
 *
 * A number typed with `+` or `00` is taken whatever its country. A national number takes its
 * country from the [Region] the member picked, loses its trunk prefix, and has to be one of the
 * lengths that country's numbers have. A number typed with the region's calling code but no `+`
 * is taken too, when its length says so.
 */
object PhoneNumbers {

    /** E.164's bounds on the digits after `+`, country code included. */
    const val MIN_DIGITS: Int = 8
    const val MAX_DIGITS: Int = 15

    /**
     * A country the picker offers: its calling code, the trunk prefix a national number may start
     * with (dropped), how many digits a national number has after that prefix, and, where the
     * country's plan says, the digits one can start with.
     */
    data class Region(
        val code: String,
        val callingCode: String,
        val trunkPrefix: String?,
        val nationalLengths: Set<Int>,
        val nationalStarts: CharRange? = null,
    )

    /** No North American area code starts with 0 or 1: a 0 there is a number from somewhere else, typed without its `+`. */
    private val NANP_STARTS: CharRange = '2'..'9'

    /**
     * The countries Machankura serves, and the three a member abroad most often has a number in.
     * A country missing here is still reachable: its numbers are typed with `+`.
     */
    val REGIONS: List<Region> = listOf(
        Region("ZA", "27", "0", setOf(9)),
        Region("NG", "234", "0", setOf(10)),
        Region("KE", "254", "0", setOf(9)),
        Region("GH", "233", "0", setOf(9)),
        Region("UG", "256", "0", setOf(9)),
        Region("TZ", "255", "0", setOf(9)),
        Region("ZW", "263", "0", setOf(9)),
        Region("MW", "265", "0", setOf(9)),
        Region("ZM", "260", "0", setOf(9)),
        Region("RW", "250", "0", setOf(9)),
        Region("ET", "251", "0", setOf(9)),
        Region("NA", "264", "0", setOf(8, 9)),
        Region("BI", "257", null, setOf(8)),
        Region("CM", "237", null, setOf(9)),
        Region("CI", "225", null, setOf(10)),
        Region("SN", "221", null, setOf(9)),
        Region("BJ", "229", null, setOf(8, 10)),
        Region("TG", "228", null, setOf(8)),
        Region("MZ", "258", null, setOf(8, 9)),
        Region("BW", "267", null, setOf(7, 8)),
        Region("LS", "266", null, setOf(8)),
        Region("SZ", "268", null, setOf(8)),
        Region("HT", "509", null, setOf(8)),
        Region("JM", "1", "1", setOf(10), NANP_STARTS),
        Region("US", "1", "1", setOf(10), NANP_STARTS),
        Region("CA", "1", "1", setOf(10), NANP_STARTS),
        Region("GB", "44", "0", setOf(9, 10)),
    )

    fun region(code: String): Region? = REGIONS.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** Why a typed number has no canonical spelling. */
    enum class Refusal { EMPTY, NOT_A_NUMBER, TOO_SHORT, TOO_LONG, NEEDS_A_COUNTRY, WRONG_LENGTH_FOR_COUNTRY, WRONG_START_FOR_COUNTRY }

    sealed interface Normalised {
        data class Number(val e164: String) : Normalised
        data class Refused(val why: Refusal) : Normalised
    }

    /** The characters a typed number may carry besides its digits and a leading `+`. */
    private val SEPARATORS = setOf(' ', '-', '.', '(', ')', '/', ' ')

    /**
     * [typed] as `+<digits>`, or why it can't be. [region] is the country a number typed without
     * `+` belongs to, or null when the member hasn't picked one.
     */
    fun normalise(typed: String, region: Region?): Normalised {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) return Normalised.Refused(Refusal.EMPTY)
        val plus = trimmed.startsWith('+')
        val body = if (plus) trimmed.drop(1) else trimmed
        if (body.any { !it.isAsciiDigit() && it !in SEPARATORS }) return Normalised.Refused(Refusal.NOT_A_NUMBER)
        val digits = body.filter { it.isAsciiDigit() }
        if (digits.isEmpty()) return Normalised.Refused(Refusal.NOT_A_NUMBER)

        val international = when {
            plus -> digits
            digits.startsWith("00") -> digits.drop(2)
            region?.callingCode == "1" && digits.startsWith("011") -> digits.drop(3)
            else -> null
        }
        if (international != null) return checked(international)

        region ?: return Normalised.Refused(Refusal.NEEDS_A_COUNTRY)
        // A national number never starts with its country's trunk prefix, so one that does loses it, fitting or not.
        val national = region.trunkPrefix?.takeIf { digits.startsWith(it) }?.let { digits.drop(it.length) } ?: digits
        if (national.length in region.nationalLengths) return nationalChecked(region, national)
        // The calling code typed without `+`: 27821234567 for a South African picker.
        if (digits.startsWith(region.callingCode) && digits.length - region.callingCode.length in region.nationalLengths) {
            return nationalChecked(region, digits.drop(region.callingCode.length))
        }
        return Normalised.Refused(Refusal.WRONG_LENGTH_FOR_COUNTRY)
    }

    /** [national], the right length for [region], if it starts the way that country's numbers do. */
    private fun nationalChecked(region: Region, national: String): Normalised {
        if (region.nationalStarts != null && national.first() !in region.nationalStarts) return Normalised.Refused(Refusal.WRONG_START_FOR_COUNTRY)
        return checked(region.callingCode + national)
    }

    private fun checked(digits: String): Normalised = when {
        digits.startsWith('0') -> Normalised.Refused(Refusal.NOT_A_NUMBER)
        digits.length < MIN_DIGITS -> Normalised.Refused(Refusal.TOO_SHORT)
        digits.length > MAX_DIGITS -> Normalised.Refused(Refusal.TOO_LONG)
        else -> Normalised.Number("+$digits")
    }

    /** Whether [text] is already a canonical spelling, as a signer checks a record's (D2). */
    fun isCanonical(text: String): Boolean =
        text.startsWith('+') && text.drop(1).let { it.all { c -> c in '0'..'9' } && it.length in MIN_DIGITS..MAX_DIGITS && !it.startsWith('0') }

    /**
     * [e164] for reading: `+27 82 123 4567`. The calling code is the longest of the table's that
     * the number starts with, and the national number goes in groups. Display only: never stored,
     * never hashed.
     */
    fun formatted(e164: String): String {
        if (!isCanonical(e164)) return e164
        val digits = e164.drop(1)
        val code = REGIONS.map { it.callingCode }.distinct().sortedByDescending { it.length }
            .firstOrNull { digits.startsWith(it) && (digits.length - it.length) in REGIONS.filter { r -> r.callingCode == it }.flatMap { r -> r.nationalLengths } }
            ?: return "+$digits"
        val national = digits.drop(code.length)
        val groups = when (national.length) {
            7 -> listOf(3, 4)
            8 -> listOf(4, 4)
            9 -> listOf(2, 3, 4)
            10 -> listOf(3, 3, 4)
            else -> return "+$code $national"
        }
        var at = 0
        val parts = groups.map { n -> national.substring(at, at + n).also { at += n } }
        return "+$code ${parts.joinToString(" ")}"
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
