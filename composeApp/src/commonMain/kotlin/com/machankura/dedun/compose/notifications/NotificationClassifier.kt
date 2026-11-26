package com.machankura.dedun.compose.notifications

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatMessage.Companion as Line

/** What kind of notification a line is, which decides its channel and its switch. D7. */
enum class NotificationCategory {
    /** What somebody said to you. */
    Messages,

    /** A decision this device is waiting on. */
    NeedsYou,

    /** Something that happened to a group you are in. */
    GroupActivity,
}

/**
 * Which lines notify, and as what. docs/notifications-and-background-sync.md, D7.
 *
 * Every `TYPE_` on [ChatMessage] is in exactly one of the four sets below, and
 * `NotificationClassifierTest` fails on one that is in none: a new transcript line has to decide
 * whether it notifies when it is added, not after somebody complains that it did, or did not.
 * A type nobody declared -- a literal written somewhere without a constant -- is silent.
 */
object NotificationClassifier {

    val MESSAGES: Set<String> = setOf(Line.TYPE_MESSAGE, Line.TYPE_DIRECT_MESSAGE)

    /**
     * Addressed to the reader, and written by the reader's own device: these lines carry
     * `isUserMessage = true` (`ChillDkgRitualManager.announce` and its siblings set it from the
     * session's own key), so unlike the other two categories they are not dropped for being ours.
     */
    val NEEDS_YOU: Set<String> = setOf(
        Line.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY,
        Line.TYPE_DKG_APPROVAL_NEEDED_ROUND_1,
        Line.TYPE_DKG_APPROVAL_NEEDED_ROUND_2,
        Line.TYPE_DKG_APPROVAL_NEEDED_LEGACY,
        Line.TYPE_SHARE_REFRESH_APPROVAL_NEEDED,
        Line.TYPE_SHARE_REPAIR_APPROVAL_NEEDED,
        Line.TYPE_FROST_APPROVAL_NEEDED,
        // Dedun's wallets (docs/giza-second-batch.md, Phase 3): an Iceberg ceremony waiting on
        // this device, an operator caught saying two things, and a Wavelength wallet whose
        // signers have gone quiet or whose deadline is near -- each a thing to act on.
        Line.TYPE_ICEBERG_APPROVAL_NEEDED_JOIN,
        Line.TYPE_ICEBERG_APPROVAL_NEEDED_REVEAL,
        Line.TYPE_ICEBERG_APPROVAL_NEEDED_CONFIRM,
        Line.TYPE_LIGHTNING_EQUIVOCATION,
        Line.TYPE_WAVELENGTH_EQUIVOCATION,
        Line.TYPE_WAVELENGTH_DEVICES_QUIET,
        Line.TYPE_WAVELENGTH_DEADLINE,
    )

    /** The end of something, or a change in who is in the group. Not the steps in between. */
    val GROUP_ACTIVITY: Set<String> = setOf(
        // Governed rooms and promotion (giza-into-dedun, Phases 8 and 9), as Giza files them.
        Line.TYPE_ADMIN_ADDED,
        Line.TYPE_ADMIN_REMOVED,
        Line.TYPE_DKG_COMPLETE,
        Line.TYPE_DKG_FAILED,
        Line.TYPE_SHARE_REFRESH_COMPLETE,
        Line.TYPE_SHARE_REFRESH_FAILED,
        Line.TYPE_SHARE_REPAIRED,
        Line.TYPE_SHARE_REPAIR_FAILED,
        Line.TYPE_FROST_COMPLETE,
        Line.TYPE_FROST_FAILED,
        Line.TYPE_SUBGROUP_CERTIFIED,
        Line.TYPE_MEMBER_ADDED,
        Line.TYPE_MEMBER_LEFT,
        Line.TYPE_GROUP_DISBANDED,
        // Dedun's wallets: money that moved, and a wallet or a ceremony that began or ended.
        Line.TYPE_ICEBERG_COMPLETE,
        Line.TYPE_ICEBERG_FAILED,
        Line.TYPE_GROUP_WALLET_OPENED,
        Line.TYPE_GROUP_LIGHTNING_WALLET_OPENED,
        Line.TYPE_GROUP_WAVELENGTH_WALLET_OPENED,
        Line.TYPE_GROUP_LIGHTNING_PAYMENT_AUTHORISED,
        Line.TYPE_GROUP_WAVELENGTH_PAYMENT_APPROVED,
        Line.TYPE_GROUP_TRANSACTION_CONFIRMED,
        Line.TYPE_GUARDIAN_SPEND,
        Line.TYPE_MEMBER_WALLET_GUARDED,
        Line.TYPE_DEPOSITS_ATTRIBUTED,
        Line.TYPE_COINS_ASSIGNED,
        Line.TYPE_CONTACT_ADDED,
        Line.TYPE_CONTACT_CLAIMED,
        Line.TYPE_MEMBER_TRANSFER_RECORDED,
        Line.TYPE_LIGHTNING_CHANNEL_OPENED,
        Line.TYPE_LIGHTNING_CHANNEL_CLOSED,
        Line.TYPE_LIGHTNING_PAYMENT_RECEIVED,
        Line.TYPE_LIGHTNING_PAYMENT_SENT,
        Line.TYPE_LIGHTNING_PAYMENT_FAILED,
        Line.TYPE_LIGHTNING_LIQUIDITY_ADDED,
        Line.TYPE_LIGHTNING_LIQUIDITY_FAILED,
        Line.TYPE_LIGHTNING_OPERATOR_STOPPED,
        Line.TYPE_WAVELENGTH_OPERATOR_STOPPED,
        Line.TYPE_WAVELENGTH_PAID_OUT,
        Line.TYPE_WAVELENGTH_ROUND_DIED,
    )

    /** Steps, protocol, history and refusals: the transcript, not news. */
    val SILENT: Set<String> = setOf(
        // This device's own account of an offer it made or answered, a refusal every device
        // writes alike, a rotation it took part in, and the founding-admins line no build writes
        // any more: Giza files each of them silent.
        Line.TYPE_ADMIN_OFFERED,
        Line.TYPE_CHANGE_NOT_APPLIED,
        Line.TYPE_SHARE_REFRESH_TOOK_PART,
        Line.TYPE_FOUNDING_ADMINS_MISSING,
        Line.TYPE_DKG_STARTED,
        Line.TYPE_DKG_HOST_KEY,
        Line.TYPE_DKG_ROUND_1,
        Line.TYPE_DKG_COORDINATOR_ROUND_1,
        Line.TYPE_DKG_ROUND_2,
        Line.TYPE_DKG_CERTIFICATE,
        Line.TYPE_SHARE_REFRESH_STARTED,
        Line.TYPE_SHARE_REFRESH_DEALT,
        Line.TYPE_SHARE_REFRESH_CONFIRMED,
        Line.TYPE_SHARE_REPAIR_REQUESTED,
        Line.TYPE_FROST_STARTED,
        Line.TYPE_FROST_NONCE,
        Line.TYPE_FROST_SIGNER_SET,
        Line.TYPE_FROST_PARTIAL_SIGNATURE,
        Line.TYPE_FROST_SIGNATURE,
        Line.TYPE_FROST_DECLINED,
        Line.TYPE_FROST_RESENT,
        Line.TYPE_CHRONICLE_REQUESTED,
        Line.TYPE_CHRONICLE_SENT,
        Line.TYPE_CHRONICLE_RECEIVED,
        // The five legacy group-statement types are private to ChatMessage, kept only so old rows
        // still read; named here by value. The reflection in the test sees them all the same.
        "groupSchemaSigned",
        "groupEntryCurated",
        "groupProfileSigned",
        "groupRelaysSigned",
        "groupPostSigned",
        Line.TYPE_MEMBER_INVITED,
        Line.TYPE_MEMBER_INVITE_SENT,
        Line.TYPE_MEMBER_INVITE_FAILED,
        Line.TYPE_CHANGE_REJECTED,
        Line.TYPE_UNDECRYPTABLE_OUTER_LAYER,
        Line.TYPE_PENDING_COMMIT,
        // Dedun's wallets: the steps of a ceremony, who operates, what was asked and answered,
        // and refusals -- the transcript's record, with the news in the sets above.
        Line.TYPE_ICEBERG_STARTED,
        Line.TYPE_ICEBERG_COMMITTED,
        Line.TYPE_ICEBERG_REVEALED,
        Line.TYPE_ICEBERG_CONFIRMED,
        Line.TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED,
        Line.TYPE_GROUP_WAVELENGTH_OPERATOR_ELECTED,
        Line.TYPE_GUARDED_WALLET_SHARED,
        Line.TYPE_MEMBER_WALLET_REQUESTED,
        Line.TYPE_MEMBER_WALLET_POLICY,
        Line.TYPE_PAYOUT_DESTINATION_SET,
        Line.TYPE_PAYOUT_DESTINATION_REMOVED,
        Line.TYPE_DEPOSITS_OPENED,
        Line.TYPE_CONTACT_AMENDED,
        Line.TYPE_CONTACT_DEPOSITS_OPENED,
        Line.TYPE_CONTACT_PAYOUT_DESTINATION,
        Line.TYPE_MEMBER_TRANSFER_ASKED,
        Line.TYPE_LIGHTNING_SEED_SHARED,
        Line.TYPE_LIGHTNING_INVOICE_REQUESTED,
        Line.TYPE_LIGHTNING_INVOICE_ISSUED,
        Line.TYPE_LIGHTNING_REFUSED,
        Line.TYPE_WAVELENGTH_REFUSED,
    )

    /** [line]'s category, or null for a line that never notifies. */
    fun categoryOf(line: ChatMessage): NotificationCategory? = when (line.messageType) {
        in NEEDS_YOU -> NotificationCategory.NeedsYou
        in MESSAGES -> NotificationCategory.Messages.takeUnless { line.isUserMessage }
        in GROUP_ACTIVITY -> NotificationCategory.GroupActivity.takeUnless { line.isUserMessage }
        else -> null
    }
}
