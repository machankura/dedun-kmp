package com.machankura.dedun.compose.database.model.types

/**
 * What one participant id holds at one generation of a key's shares
 * (docs/frost-share-refresh.md).
 */
enum class ShareStanding {
    /** Holds a share of this generation, and can sign with it. */
    LIVE,

    /**
     * Was withheld from the refresh that made this generation, and holds only an
     * older share, which signs nothing at this one. A repair (re-enrollment)
     * makes them [LIVE] again.
     */
    STALE,

    /**
     * Withheld for good. Never repaired, never reused: participant ids are FROST
     * identifiers, positions below `n`, and renumbering would invalidate every
     * public share.
     */
    RETIRED,
}
