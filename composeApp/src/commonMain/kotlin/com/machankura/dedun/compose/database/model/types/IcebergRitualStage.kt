package com.machankura.dedun.compose.database.model.types

/**
 * Where an Iceberg key ceremony has got to, for the screen. Declared in the order the ceremony runs,
 * so a label moves forward and never back; the ritual itself decides what to do from what it has
 * stored, as the ChillDKG one does, and never from this.
 */
enum class IcebergRitualStage {
    /** Waiting on every participant's commitments. */
    COLLECTING_COMMITMENTS,
    /** Waiting on every contribution addressed to this device. */
    COLLECTING_REVEALS,
    /** Waiting on every participant's public share and transcript. */
    COLLECTING_CONFIRMATIONS,
    COMPLETE,
    FAILED,
}
