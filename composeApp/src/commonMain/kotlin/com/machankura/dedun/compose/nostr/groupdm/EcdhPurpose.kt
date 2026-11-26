package com.machankura.dedun.compose.nostr.groupdm

/**
 * Why a device is asked to multiply by a point, which fixes what it must hold first (D5 of
 * docs/group-threshold-ecdh.md). The wire name is the lowercase name.
 */
enum class EcdhPurpose {
    /** The point is a gift wrap's pubkey `W`; the evidence is the wrap, p-tagged to the group. */
    WRAP,

    /** The point is a seal's pubkey `S`; the evidence is the seal, out of a wrap the device opened. */
    SEAL,

    /** The point is someone the group writes to, `R`; the evidence is any event `R` signed. */
    RECIPIENT;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): EcdhPurpose? = entries.firstOrNull { it.wire == value }
    }
}
