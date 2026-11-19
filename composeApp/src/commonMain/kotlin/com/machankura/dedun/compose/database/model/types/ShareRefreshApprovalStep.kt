package com.machankura.dedun.compose.database.model.types

/**
 * The two decisions a recipient makes in a share refresh. Each publishes
 * something different and commits the member to something different.
 */
enum class ShareRefreshApprovalStep {
    /**
     * Deal round 1: join the refresh, and agree to the withheld list. Withholding
     * someone takes their signing away, so every recipient decides it, not only
     * the proposer.
     */
    DEAL,

    /**
     * Confirm round 2: store the new generation and say so. This is what stops a
     * coordinator substituting a table the recipients never dealt, and the one
     * confirmation per base generation this device will ever give.
     */
    CONFIRM,
}
