package com.machankura.dedun.compose.database.model.types

/**
 * Everything a share refresh has ever asked a recipient. One step is still
 * asked; the other is kept so rows that stored it by name still decode
 * (`ShareRefreshSession.approvalRequestedThrough`).
 */
enum class ShareRefreshApprovalStep {
    /**
     * Take part: deal round 1, and agree to the withheld list. Withholding
     * someone takes their signing away, so every recipient decides it, not only
     * the proposer. **The only approval a refresh asks for**: the device does
     * everything after it on its own (docs/rotate-shares-single-approval.md).
     */
    DEAL,

    /**
     * Was: confirm round 2, storing the new generation and saying so. **No
     * longer asked**, and nothing produces it.
     *
     * Its two reasons never needed a person. Stopping a coordinator substituting
     * a table nobody dealt is `FrostRefresh.participantStep2`'s check, which runs
     * before any prompt could appear and fails the refresh out loud. The one
     * confirmation per base generation is `ShareRefreshConfirmation`, claimed in
     * the same transaction as the pending share. Neither depended on the tap.
     * Don't put the gate back: a person shown "store your new share" has nothing
     * to check that the device has not already checked.
     */
    CONFIRM,
}
