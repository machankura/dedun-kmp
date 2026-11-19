package com.machankura.dedun.compose.database.model.types

/**
 * Where one generation of a key's shares stands on this device
 * (docs/frost-share-refresh.md).
 *
 * Declaration order is the life of a generation, and nothing goes backwards.
 */
enum class ShareGenerationState {
    /**
     * Stored, and this device has confirmed it to the group, but the refresh
     * that made it has no certificate yet. Never signed with: until every
     * recipient has confirmed, the group may yet abandon it, and the previous
     * generation is still the one the group signs with.
     */
    PENDING,

    /** The generation the group signs with. At most one per key. */
    ACTIVE,

    /**
     * Superseded by a newer active generation, and kept only so that sessions
     * opened under it can finish. Wiped once the group has signed with its
     * successor.
     */
    RETIRING,

    /** The secret share is gone from this device. The public table is kept. */
    WIPED,
}
