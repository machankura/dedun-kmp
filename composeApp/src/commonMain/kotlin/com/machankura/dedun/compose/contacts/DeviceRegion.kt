package com.machankura.dedun.compose.contacts

/**
 * The device's region as a two-letter country code (`ZA`), or null when it has none: what the
 * phone number picker starts at (`docs/non-member-deposits.md`, D2). A suggestion only -- the
 * member picks the country, and a number typed with `+` ignores it.
 */
expect fun deviceRegionCode(): String?
