package com.machankura.dedun.compose.ui.view.state

/**
 * A share rotation as the proposer's dialog shows it before anything is sent
 * (docs/rotate-shares-single-approval.md, Phase 4): who takes part and who is
 * left out, by name. Pressing the dialog's button is the proposer's agreement
 * to take part, so it shows everything that agreement covers.
 */
data class RotationPreview(
    val takingPart: List<String>,
    /** Withheld as stale: left out until a repair brings them back. */
    val untilBroughtBack: List<String>,
    /** Withheld as retired: left out for good. */
    val forGood: List<String>,
    /** Why the rotation cannot be proposed, or null when it can. */
    val refusal: String?,
)
