package com.machankura.dedun.compose.database.model

import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.nostr.lightning.GroupLightningOperatorStatementEvent
import com.machankura.dedun.compose.text.grouped
import fr.acinq.bitcoin.Satoshi
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.text.describe
import com.machankura.dedun.compose.text.BlockDuration
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.application.name
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.database.model.traits.UserViewableEntity
import com.machankura.dedun.compose.exceptions.MarmotUnprocessableInnerEventException
import com.machankura.dedun.compose.managers.ChronicleManager
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.nostr.wallet.GroupKeyNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.PayoutDestinationRemoved
import com.machankura.dedun.compose.managers.GroupKeyStateManager
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.managers.SubgroupManager
import com.machankura.dedun.compose.extensions.toHex
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.BlockedRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.chronicle.ChronicleRequestEvent
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.firstTagValue
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.machankura.dedun.compose.nostr.MarmotDirectMessage
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GiftWrapPayload::class,
            parentColumns = ["id"],
            childColumns = ["giftWrapPayloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MarmotGroupEvent::class,
            parentColumns = ["id"],
            childColumns = ["marmotGroupEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MarmotInnerEvent::class,
            parentColumns = ["id"],
            childColumns = ["marmotInnerEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // The chat list reads the newest line of every room the user is in, once per
    // room, and re-reads all of them each time a message lands anywhere. Without
    // this that is a scan of every message this device holds, per room, per
    // arrival -- work that grows with the whole history rather than with the room.
    indices = [Index("chatRoomId")],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val senderPublicKey: String,
    val isUserMessage: Boolean,

    val giftWrapPayloadId: HexKey?,
    val marmotGroupEventId: HexKey?,
    val marmotInnerEventId: HexKey?,
    val chatRoomId: String,

    val replyToMessageId: Long? = null,
    val quotedMessageId: Long? = null,

    val content: String,
    val messageType: String = "message",

    /**
     * Who a direct message was for, or null for anything else.
     *
     * Carried on the row rather than read back off the wrap so the transcript can name
     * the recipient without a join, on both the line the two of them can read and the
     * line everybody else gets. See docs/marmot-direct-messages.md.
     */
    val directMessageRecipientPublicKey: HexKey? = null,

    /**
     * The signing session a [FROST_TYPES] line belongs to, or null for anything else.
     *
     * The transcript used to work this out from the type and the clock: a request
     * was over once a line of the right type appeared after it. That reads a room
     * signing one thing at a time correctly and a room signing two things at once
     * wrongly -- a chapter and the translation that depends on it are proposed as
     * two sessions, and either one finishing dropped the summons on both. There is
     * nothing in a row's type or time that can tell them apart, so the session has
     * to be on the row.
     *
     * Null on every line written before this column existed, which the rules below
     * fall back to their old behaviour for. Correct for those rooms too: nothing
     * that predates the batch proposals ran two sessions at once.
     */
    val frostSigningSessionId: String? = null,

    /**
     * The ceremony a [DKG_TYPES] line belongs to, or null for anything else.
     *
     * The pair to [frostSigningSessionId], and added for the same reason one
     * ceremony at a time stopped being true. A room's ceremony room is derived
     * from its members, so a subgroup whose admins are the whole group runs in
     * the room the group's own ceremony ran in -- and then "the room's ceremony"
     * names two things. A screen opened from one of these lines has to reach the
     * ceremony the line is *about*, not the newest one in the room, or a member
     * asked to join a subgroup is shown a ceremony that finished last week and
     * told there is nothing to do.
     *
     * Null on every line written before this column existed, which the readers
     * below fall back to the clock for. Correct for those rooms too: nothing that
     * predates subgroups ran two ceremonies at once.
     */
    val dkgSessionId: String? = null,

    /**
     * The fact a Lightning wallet line reports, for the lines that come from the operator's
     * snapshots rather than from a message: a channel's id, or a payment's. Null for
     * anything else.
     *
     * A snapshot restates the whole wallet every few minutes, so the message it came in
     * cannot be what makes its line one line -- the channel or the payment is. Kept on the
     * row, like [frostSigningSessionId], because nothing in its type or time can say which
     * channel it was. docs/group-lightning-transcript.md, Phase 5.
     */
    val lightningFactKey: String? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null, // Who this message should be seen by (used to derive conversationId)
): TimestampedEntity, LocalStoreEntity, UserViewableEntity, SoftDeletableEntity {

    companion object {
        private val logger = Logger.withTag("ChatMessage")

        /**
         * A one-to-one message inside a group, as a line in the group's chat.
         *
         * Written by both sides of the room. For its two parties [content] is the words;
         * for everybody else it is empty, and the line says only that a private message
         * was sent and to whom -- which is all the group can learn, and all it should.
         *
         * [directMessageRecipientPublicKey] is set on every one of these regardless of
         * who can read it, because naming the recipient is the point of the line the
         * bystanders get. See docs/marmot-direct-messages.md.
         */
        const val TYPE_DIRECT_MESSAGE = "directMessage"

        /**
         * A ChillDKG ritual message, as a line in the group's chat.
         *
         * There is one per protocol message the ritual sends -- every member's host
         * key, both of their rounds, and both of the coordinator's aggregates --
         * plus the three that bracket them: started, complete, abandoned.
         *
         * These rows are not anybody's words: no [ChatMessage.giftWrapPayloadId], no
         * event behind them, and nothing sent to say them. Each device writes its own
         * from the ritual messages it already received, which is why they cost no
         * traffic and cannot disagree with the ritual they describe.
         *
         * [ChatMessageListViewModel] renders these as a system line rather than a
         * bubble, since attributing "a shared key ceremony started" to the
         * coordinator would read as something they said.
         */
        const val TYPE_DKG_STARTED = "dkgStarted"
        const val TYPE_DKG_HOST_KEY = "dkgHostKey"
        const val TYPE_DKG_ROUND_1 = "dkgRound1"
        const val TYPE_DKG_COORDINATOR_ROUND_1 = "dkgCoordinatorRound1"
        const val TYPE_DKG_ROUND_2 = "dkgRound2"
        const val TYPE_DKG_CERTIFICATE = "dkgCertificate"
        const val TYPE_DKG_COMPLETE = "dkgComplete"
        const val TYPE_DKG_FAILED = "dkgFailed"

        /**
         * The ritual is waiting on this device's owner and will publish nothing
         * until they act. Unauthored on purpose: these are addressed to the reader
         * rather than said by anyone, so they read "Your approval is needed", not
         * "You your approval is needed".
         *
         * One type per step rather than one for all three, because the row is the
         * only thing the transcript keeps: a line rendered days later has no session
         * to ask what was being requested, so which step it was has to be in the
         * type. Sharing one type left every request wearing the same icon.
         */
        const val TYPE_DKG_APPROVAL_NEEDED_HOST_KEY = "dkgApprovalNeededHostKey"
        const val TYPE_DKG_APPROVAL_NEEDED_ROUND_1 = "dkgApprovalNeededRound1"
        const val TYPE_DKG_APPROVAL_NEEDED_ROUND_2 = "dkgApprovalNeededRound2"

        /**
         * The single type the three above replaced. Still recognised so rows already
         * written keep rendering as system lines instead of turning into chat
         * bubbles attributed to the reader. Nothing writes it any more.
         */
        const val TYPE_DKG_APPROVAL_NEEDED_LEGACY = "dkgApprovalNeeded"

        /**
         * The line each request is answered by: the step it asked for, published by
         * this device. Approving is the only thing that causes that to be written,
         * so its presence is what marks a request done.
         *
         * The legacy type is absent on purpose -- it does not say which step it was
         * asking for, so nothing can be said about whether it was answered.
         */
        val DKG_REQUEST_FULFILMENTS = mapOf(
            TYPE_DKG_APPROVAL_NEEDED_HOST_KEY to TYPE_DKG_HOST_KEY,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_1 to TYPE_DKG_ROUND_1,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_2 to TYPE_DKG_ROUND_2,
        )

        /** The lines that are asking rather than reporting. */
        val DKG_REQUEST_TYPES = setOf(
            TYPE_DKG_APPROVAL_NEEDED_HOST_KEY,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_1,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_2,
            TYPE_DKG_APPROVAL_NEEDED_LEGACY,
        )

        val DKG_TYPES = setOf(
            TYPE_DKG_STARTED,
            TYPE_DKG_HOST_KEY,
            TYPE_DKG_ROUND_1,
            TYPE_DKG_COORDINATOR_ROUND_1,
            TYPE_DKG_ROUND_2,
            TYPE_DKG_CERTIFICATE,
            TYPE_DKG_COMPLETE,
            TYPE_DKG_FAILED,
        ) + DKG_REQUEST_TYPES

        /**
         * The ritual lines somebody did, as opposed to ones that simply happened.
         * For these [senderPublicKey] is the member who acted, and their content is
         * written as a predicate for their name to be read in front of.
         *
         * Every protocol message is one of these: each is a thing a named member's
         * device sent, and which member it was is the whole point of showing it --
         * a ritual finishes only once every member has taken part, so a stalled one
         * is a question about who has not.
         *
         * A finished ceremony is the exception, and has no actor: the group ends up
         * with a key, nobody hands it to them.
         */
        /**
         * An Iceberg key ceremony -- the group Lightning wallet's key -- as lines in the group's
         * chat. The same shape as the ChillDKG ceremony's, and kept apart from [DKG_TYPES] because a
         * line has to open the ceremony it is about, and the two ceremonies have different screens.
         * [dkgSessionId] carries the Iceberg session's id on these lines.
         */
        const val TYPE_ICEBERG_STARTED = "icebergStarted"
        const val TYPE_ICEBERG_COMMITTED = "icebergCommitted"
        const val TYPE_ICEBERG_REVEALED = "icebergRevealed"
        const val TYPE_ICEBERG_CONFIRMED = "icebergConfirmed"
        const val TYPE_ICEBERG_COMPLETE = "icebergComplete"
        const val TYPE_ICEBERG_FAILED = "icebergFailed"
        const val TYPE_ICEBERG_APPROVAL_NEEDED_JOIN = "icebergApprovalNeededJoin"
        const val TYPE_ICEBERG_APPROVAL_NEEDED_REVEAL = "icebergApprovalNeededReveal"
        const val TYPE_ICEBERG_APPROVAL_NEEDED_CONFIRM = "icebergApprovalNeededConfirm"

        val ICEBERG_REQUEST_FULFILMENTS = mapOf(
            TYPE_ICEBERG_APPROVAL_NEEDED_JOIN to TYPE_ICEBERG_COMMITTED,
            TYPE_ICEBERG_APPROVAL_NEEDED_REVEAL to TYPE_ICEBERG_REVEALED,
            TYPE_ICEBERG_APPROVAL_NEEDED_CONFIRM to TYPE_ICEBERG_CONFIRMED,
        )

        val ICEBERG_REQUEST_TYPES = ICEBERG_REQUEST_FULFILMENTS.keys

        val ICEBERG_TYPES = setOf(
            TYPE_ICEBERG_STARTED,
            TYPE_ICEBERG_COMMITTED,
            TYPE_ICEBERG_REVEALED,
            TYPE_ICEBERG_CONFIRMED,
            TYPE_ICEBERG_COMPLETE,
            TYPE_ICEBERG_FAILED,
        ) + ICEBERG_REQUEST_TYPES

        /** As [DKG_AUTHORED_TYPES]: content written to follow the actor's name. A finished key has no actor. */
        val ICEBERG_AUTHORED_TYPES = setOf(
            TYPE_ICEBERG_STARTED,
            TYPE_ICEBERG_COMMITTED,
            TYPE_ICEBERG_REVEALED,
            TYPE_ICEBERG_CONFIRMED,
            TYPE_ICEBERG_FAILED,
        )

        /**
         * A FROST signing session, as lines in the group's chat.
         *
         * The same shape as the ceremony's, and for the same reason: signing with
         * the group's key is otherwise a black box, and a session stalled on one
         * member gives no way to see whose door to knock on.
         */
        const val TYPE_FROST_STARTED = "frostStarted"
        const val TYPE_FROST_NONCE = "frostNonce"
        const val TYPE_FROST_SIGNER_SET = "frostSignerSet"
        const val TYPE_FROST_PARTIAL_SIGNATURE = "frostPartialSignature"
        const val TYPE_FROST_SIGNATURE = "frostSignature"
        const val TYPE_FROST_COMPLETE = "frostComplete"
        const val TYPE_FROST_FAILED = "frostFailed"

        /**
         * A member refused to sign, and the session went on without them.
         *
         * Its own type rather than a failure, because it is not one: a t-of-n key
         * can do without `n - t` members, and this line is one of the refusals
         * the key can absorb. The refusal that leaves too few to sign is written
         * as [TYPE_FROST_FAILED] instead, saying both that the member declined
         * and that it was the one that ended the session.
         */
        const val TYPE_FROST_DECLINED = "frostDeclined"

        /**
         * The coordinator re-published a session for the members it had heard
         * nothing from.
         *
         * Its own type rather than a step, because nothing moved: somebody
         * knocked. A session can be left waiting on a member whose copy of the
         * proposal never arrived -- MLS dropped the generation, or a
         * disappearing-message group expired it off the relay -- and a resend is
         * the only thing that reaches them. The line exists so a second knock
         * reads as a second knock. See docs/frost-session-resend.md.
         */
        const val TYPE_FROST_RESENT = "frostResent"

        /** Addressed to the reader rather than said by anyone -- see [DKG_REQUEST_TYPES]. */
        const val TYPE_FROST_APPROVAL_NEEDED = "frostApprovalNeeded"

        /**
         * Answering the request publishes this device's nonce, not its signature:
         * approving is agreeing to take part, and the coordinator may then pick a
         * quorum that does not include this member. Keying the answer on the
         * partial signature would leave a member who agreed, and was not needed,
         * looking like they never replied.
         */
        val FROST_REQUEST_FULFILMENTS = mapOf(
            TYPE_FROST_APPROVAL_NEEDED to TYPE_FROST_NONCE,
        )

        /** The signing lines that ask rather than report. */
        val FROST_REQUEST_TYPES = setOf(TYPE_FROST_APPROVAL_NEEDED)

        /**
         * The signing lines that end a session, whichever way it went.
         *
         * A request usually stops being one by being answered, but that is not
         * the only way. A quorum that signs without the reader asks them for
         * nothing further, and a session that is abandoned asks nobody for
         * anything -- in both there is nothing of the reader's own either side
         * of the request, so the session's own ending is the only thing left to
         * read it from.
         *
         * A ceremony has no equivalent because a ceremony step can only be
         * taken or waited for. Signing is the one thing a member can refuse --
         * and a refusal the session survives is the third way a request can be
         * over, read by [settledRequests] from the reader's own
         * [TYPE_FROST_DECLINED] line rather than from here: it settles only
         * the refuser's request, where these settle everybody's.
         */
        val FROST_SETTLEMENTS = setOf(TYPE_FROST_COMPLETE, TYPE_FROST_FAILED)

        /** Every signing line, for rendering them as system lines rather than bubbles. */
        val FROST_TYPES = setOf(
            TYPE_FROST_STARTED,
            TYPE_FROST_NONCE,
            TYPE_FROST_SIGNER_SET,
            TYPE_FROST_PARTIAL_SIGNATURE,
            TYPE_FROST_SIGNATURE,
            TYPE_FROST_COMPLETE,
            TYPE_FROST_FAILED,
            TYPE_FROST_DECLINED,
            TYPE_FROST_RESENT,
            TYPE_FROST_APPROVAL_NEEDED,
        )

        /**
         * The signing lines somebody did, as opposed to ones that simply happened.
         * Their content is written as a predicate for the actor's name to be read
         * in front of. A finished signature has no actor: the group ends up with
         * one, nobody hands it to them.
         */
        val FROST_AUTHORED_TYPES = setOf(
            TYPE_FROST_STARTED,
            TYPE_FROST_NONCE,
            TYPE_FROST_SIGNER_SET,
            TYPE_FROST_PARTIAL_SIGNATURE,
            TYPE_FROST_SIGNATURE,
            TYPE_FROST_FAILED,
            TYPE_FROST_DECLINED,
            TYPE_FROST_RESENT,
        )

        val DKG_AUTHORED_TYPES = setOf(
            TYPE_DKG_STARTED,
            TYPE_DKG_HOST_KEY,
            TYPE_DKG_ROUND_1,
            TYPE_DKG_COORDINATOR_ROUND_1,
            TYPE_DKG_ROUND_2,
            TYPE_DKG_CERTIFICATE,
            TYPE_DKG_FAILED,
        )

        /**
         * A kind:445 arrived that the group could not read at the time.
         *
         * Both are placeholders for a message still to come: the outer layer was
         * encrypted under an epoch whose exporter secret we did not hold yet, or
         * the commit is one of several competing for an epoch and none has been
         * applied. Neither says anything a member wrote.
         */
        /**
         * Handing a member the group's signed record, as lines in the chat.
         *
         * One line per chronicle rather than per page. A member who joins a working
         * group has a room that fills itself in without explanation, and the
         * alternative to saying so once is either silence -- which looks like a
         * group that has done nothing -- or a line per applied payload, which is
         * the transcript the chronicle deliberately does not write.
         *
         * Content is self-contained: these are not predicates for a name to be
         * read in front of, so they stay out of the AUTHORED sets. A recipient's
         * name is written into the sent line the way the invite line writes one,
         * which does not follow a rename and is the accepted cost for a line
         * about a thing that happened once.
         *
         * The received line names no sender on purpose. A chronicle can be
         * assembled out of pages from more than one member, so attributing the
         * catch-up to one of them would be a guess dressed as a fact.
         */
        const val TYPE_CHRONICLE_REQUESTED = "chronicleRequested"
        const val TYPE_CHRONICLE_SENT = "chronicleSent"
        const val TYPE_CHRONICLE_RECEIVED = "chronicleReceived"

        /**
         * The group certified another group as its child.
         *
         * A system line rather than a bubble: nobody said it, the group signed
         * it. It is written in the *parent's* room, which is the only place the
         * certificate is ever made, and it is there for the members of that room
         * who are not in the child -- for whom this is the only notice a subgroup
         * exists at all.
         */
        const val TYPE_SUBGROUP_CERTIFIED = "subgroupCertified"

        /**
         * Lines the curated-lists feature wrote: the group signing a list's schema,
         * and an entry into one. The feature is gone and nothing writes either any
         * more, but rows already in a transcript still carry them, and a type
         * missing from [GROUP_IDENTITY_TYPES] renders as a chat bubble -- so they
         * stay, as names only, to keep those rows system lines.
         */
        private const val TYPE_LEGACY_GROUP_SCHEMA_SIGNED = "groupSchemaSigned"
        private const val TYPE_LEGACY_GROUP_ENTRY_CURATED = "groupEntryCurated"

        /**
         * Lines the group's nostr profile, relay lists and posts wrote. Those
         * features are gone too, and these stay for the same reason: rows already
         * in a transcript carry them and have to stay system lines.
         */
        private const val TYPE_LEGACY_GROUP_PROFILE_SIGNED = "groupProfileSigned"
        private const val TYPE_LEGACY_GROUP_RELAYS_SIGNED = "groupRelaysSigned"
        private const val TYPE_LEGACY_GROUP_POST_SIGNED = "groupPostSigned"

        /**
         * The group opened a wallet.
         *
         * A system line like the others in [GROUP_IDENTITY_TYPES]: nobody said it,
         * the group signed it. It names the network and nothing else -- the
         * addresses are on the wallet's screen, derived there from the room's key,
         * and the statement's xpub is not something a transcript should repeat.
         */
        const val TYPE_GROUP_WALLET_OPENED = "groupWalletOpened"
        const val TYPE_GROUP_LIGHTNING_WALLET_OPENED = "groupLightningWalletOpened"
        const val TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED = "groupLightningOperatorElected"
        const val TYPE_GROUP_LIGHTNING_PAYMENT_AUTHORISED = "groupLightningPaymentAuthorised"

        /**
         * A transaction from the group's wallet confirmed. Written by each device
         * from what its own Electrum client reported, once -- the confirmation is
         * a fact about the chain, and every device can read the chain.
         */
        const val TYPE_GROUP_TRANSACTION_CONFIRMED = "groupTransactionConfirmed"

        /**
         * A member told this group about a descendant's guarded wallet -- a
         * `GuardedWalletNoticeEvent` this device checked and believes. Written
         * from the notice, attributed to the member who forwarded it. See
         * `docs/group-wallet-guardians.md`.
         */
        const val TYPE_GUARDED_WALLET_SHARED = "guardedWalletShared"

        /**
         * One of this group's guardians spent from its guarded wallet through its
         * leaf. Read off the chain by `GuardianManager.takenIn`, and the child's
         * only notice of it: nobody in this room signed it.
         */
        const val TYPE_GUARDIAN_SPEND = "guardianSpend"

        /**
         * A member asked this group to guard a wallet only they can spend from --
         * a `MemberWalletRequestEvent` this device checked. A request, not a
         * decision: the group decides when a signer puts it to the quorum. See
         * `docs/member-guarded-wallets.md`.
         */
        const val TYPE_MEMBER_WALLET_REQUESTED = "memberWalletRequested"

        /** The group signed a member wallet certificate: it now guards that member's wallet. */
        const val TYPE_MEMBER_WALLET_GUARDED = "memberWalletGuarded"

        /**
         * The group signed a policy on its members' wallets: it guards them, at a
         * delay, or it has stopped. See `docs/member-wallet-delay.md`.
         */
        const val TYPE_MEMBER_WALLET_POLICY = "memberWalletPolicy"

        /**
         * A member said where this group pays them -- a `GroupPayoutDestinationEvent` this
         * device checked. The line names the member and never the address: the member's row
         * shows the address, and a line stays in the scrollback after it changes. See
         * `docs/group-payout-destination.md`.
         */
        const val TYPE_PAYOUT_DESTINATION_SET = "payoutDestinationSet"

        /** A member withdrew where this group pays them. */
        const val TYPE_PAYOUT_DESTINATION_REMOVED = "payoutDestinationRemoved"

        /**
         * Every line the group signed, for the one check the transcript
         * dispatches on. A type missing from here renders as a chat bubble,
         * silently, looking exactly like somebody having said it.
         */
        val GROUP_IDENTITY_TYPES = setOf(
            TYPE_LEGACY_GROUP_PROFILE_SIGNED,
            TYPE_LEGACY_GROUP_RELAYS_SIGNED,
            TYPE_LEGACY_GROUP_POST_SIGNED,
            TYPE_LEGACY_GROUP_SCHEMA_SIGNED,
            TYPE_LEGACY_GROUP_ENTRY_CURATED,
            TYPE_GROUP_WALLET_OPENED,
            TYPE_GROUP_TRANSACTION_CONFIRMED,
            TYPE_GROUP_LIGHTNING_WALLET_OPENED,
            TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED,
            TYPE_GROUP_LIGHTNING_PAYMENT_AUTHORISED,
            TYPE_GUARDED_WALLET_SHARED,
            TYPE_GUARDIAN_SPEND,
            TYPE_MEMBER_WALLET_REQUESTED,
            TYPE_MEMBER_WALLET_GUARDED,
            TYPE_MEMBER_WALLET_POLICY,
            TYPE_PAYOUT_DESTINATION_SET,
            TYPE_PAYOUT_DESTINATION_REMOVED,
        )

        /** The lines in [GROUP_IDENTITY_TYPES] about the Lightning wallet, which open its screen. */
        val GROUP_LIGHTNING_SIGNED_TYPES = setOf(
            TYPE_GROUP_LIGHTNING_WALLET_OPENED,
            TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED,
            TYPE_GROUP_LIGHTNING_PAYMENT_AUTHORISED,
        )

        /**
         * What the group Lightning wallet's devices tell each other outside its two protocols,
         * as lines: a member handing out the node's seed, a member asking the operator's node
         * to be paid and the node's answer, and an operator stopping. docs/group-lightning-
         * transcript.md, Phase 4.
         *
         * One line per message, written by the manager that acts on it -- on the way out by
         * the device that sends it, which cannot read its own message back, and on arrival by
         * every other -- and keyed on the message's inner event, so a redelivery is not a
         * second line. Nonce batches, echoes, signing requests and signature shares are never
         * lines: there are thousands of them and none says anything a member would tell
         * another.
         *
         * Not group-signed, so not in [GROUP_IDENTITY_TYPES], and none asks the reader for
         * anything: an invoice request is answered by the operator's node, not by a person.
         */
        const val TYPE_LIGHTNING_SEED_SHARED = "lightningSeedShared"
        const val TYPE_LIGHTNING_INVOICE_REQUESTED = "lightningInvoiceRequested"
        const val TYPE_LIGHTNING_INVOICE_ISSUED = "lightningInvoiceIssued"
        const val TYPE_LIGHTNING_OPERATOR_STOPPED = "lightningOperatorStopped"

        /**
         * What the operator's snapshots say happened, one line per fact rather than per
         * snapshot: a channel opening and closing, a payment settling or failing. No actor --
         * the node did these, and naming the operator would read as them having chosen to.
         * Keyed on [lightningFactKey]. docs/group-lightning-transcript.md, Phase 5.
         */
        const val TYPE_LIGHTNING_CHANNEL_OPENED = "lightningChannelOpened"
        const val TYPE_LIGHTNING_CHANNEL_CLOSED = "lightningChannelClosed"
        const val TYPE_LIGHTNING_PAYMENT_RECEIVED = "lightningPaymentReceived"
        const val TYPE_LIGHTNING_PAYMENT_SENT = "lightningPaymentSent"
        const val TYPE_LIGHTNING_PAYMENT_FAILED = "lightningPaymentFailed"
        /** A liquidity purchase locked or failed (docs/group-lightning-liquidity.md, Phase 7). Keyed on the authorisation's id. */
        const val TYPE_LIGHTNING_LIQUIDITY_ADDED = "lightningLiquidityAdded"
        const val TYPE_LIGHTNING_LIQUIDITY_FAILED = "lightningLiquidityFailed"

        /**
         * This device's co-signer refused what the operator's node asked it to sign, and why --
         * written on this device only, once per request, since a refusal names a check the
         * operator's node failed and the other members' devices made their own. Keyed on
         * [lightningFactKey], the request's id.
         */
        const val TYPE_LIGHTNING_REFUSED = "lightningRefused"

        /**
         * The operator's node asked for two different things to be signed under one label, which
         * is the one thing a co-signer must never answer (docs/group-lightning-wallet.md, the echo
         * rule). Written by every device that sees both, once per label.
         */
        const val TYPE_LIGHTNING_EQUIVOCATION = "lightningEquivocation"

        /** Every Lightning wallet line, for the one check the transcript dispatches on. */
        val LIGHTNING_TYPES = setOf(
            TYPE_LIGHTNING_SEED_SHARED,
            TYPE_LIGHTNING_INVOICE_REQUESTED,
            TYPE_LIGHTNING_INVOICE_ISSUED,
            TYPE_LIGHTNING_OPERATOR_STOPPED,
            TYPE_LIGHTNING_CHANNEL_OPENED,
            TYPE_LIGHTNING_CHANNEL_CLOSED,
            TYPE_LIGHTNING_PAYMENT_RECEIVED,
            TYPE_LIGHTNING_PAYMENT_SENT,
            TYPE_LIGHTNING_PAYMENT_FAILED,
            TYPE_LIGHTNING_LIQUIDITY_ADDED,
            TYPE_LIGHTNING_LIQUIDITY_FAILED,
            TYPE_LIGHTNING_REFUSED,
            TYPE_LIGHTNING_EQUIVOCATION,
        )

        /** As [DKG_AUTHORED_TYPES]: content written to follow the actor's name. The node's answer has no actor. */
        val LIGHTNING_AUTHORED_TYPES = setOf(
            TYPE_LIGHTNING_SEED_SHARED,
            TYPE_LIGHTNING_INVOICE_REQUESTED,
            TYPE_LIGHTNING_OPERATOR_STOPPED,
        )

        /**
         * Every subgroup line, for the one check the transcript dispatches on.
         *
         * One member today, and a set anyway. A type missing from here renders as
         * a chat bubble -- silently, and looking exactly like a member having said
         * "The group made Translation team a subgroup".
         */
        val SUBGROUP_TYPES = setOf(
            TYPE_SUBGROUP_CERTIFIED,
        )

        /**
         * Every chronicle line, for the one check the transcript dispatches on.
         *
         * A type missing from here renders as a chat bubble -- silently, and
         * looking exactly like a member having said "Caught up on 12 items".
         */
        val CHRONICLE_TYPES = setOf(
            TYPE_CHRONICLE_REQUESTED,
            TYPE_CHRONICLE_SENT,
            TYPE_CHRONICLE_RECEIVED,
        )

        /**
         * Adding a member to the group, as lines in the group's chat.
         *
         * An invite is two separate things, and in an established group they can be
         * minutes apart: the membership change this device commits, and the Welcome
         * that lets the invitee in going on the wire. The second waits on a relay
         * acknowledging the commit -- see docs/marmot-membership.md -- and it is the
         * one that can fail after the screen that asked for it has closed, which
         * leaves the transcript as the only place able to say so.
         *
         * So [TYPE_MEMBER_INVITED] is written the moment the invite is made, and
         * [TYPE_MEMBER_INVITE_SENT] only where the Welcome is delivered later than
         * that. Where the two happen together -- a group that is still only its
         * creator has nobody to inform, so its Welcome goes out immediately -- there
         * is one line, because there was one event.
         *
         * Written by the inviter's device, for itself. None of these travels: what
         * the group receives is the commit, and each member's transcript is written
         * from what it made of that. A member watching from the side sees nothing
         * here, correctly.
         *
         * Content is a whole sentence rather than a predicate, so these stay out of
         * the AUTHORED sets and nothing prefixes a name to them. The invitee's name
         * is written into the content the way the chronicle's is, which does not
         * follow a rename and is the accepted cost for a line about a thing that
         * happened once.
         */
        const val TYPE_MEMBER_INVITED = "memberInvited"
        const val TYPE_MEMBER_INVITE_SENT = "memberInviteSent"
        const val TYPE_MEMBER_INVITE_FAILED = "memberInviteFailed"

        /**
         * What the group's state said changed, as lines in the group's chat.
         *
         * Derived, never received. v2 spells these as kind `1210` system rows and
         * is explicit that a row which arrives over the wire is one member's
         * assertion, not a fact; a row derived from the commit this device applied
         * cannot be forged by one member, and every device applying that commit
         * derives the same one. So these are written by `processGroupMembershipChanges`
         * from the difference between the room's members before a commit and after
         * it, on every device that applies it -- and by the device that made the
         * change, for what it did. See docs/marmot-v2.md, Phase 7.
         *
         * [TYPE_MEMBER_LEFT] covers a member who left and one an admin removed
         * alike: the tree does not say which, and a line that guessed would be a
         * caption no other device writes.
         */
        const val TYPE_MEMBER_ADDED = "memberAdded"
        const val TYPE_MEMBER_LEFT = "memberLeft"
        const val TYPE_GROUP_DISBANDED = "groupDisbanded"

        /**
         * A change this device asked for that every relay refused, so the line
         * written when it was asked for -- a removal, a disband -- did not come
         * true. The invite's own version is [TYPE_MEMBER_INVITE_FAILED].
         */
        const val TYPE_CHANGE_REJECTED = "changeRejected"

        /**
         * Every membership line, for the one check the transcript dispatches on.
         *
         * A type missing from here renders as a chat bubble -- silently, and looking
         * exactly like the inviter having said "Invited Bob to the group".
         */
        val MEMBERSHIP_TYPES = setOf(
            TYPE_MEMBER_INVITED,
            TYPE_MEMBER_INVITE_SENT,
            TYPE_MEMBER_INVITE_FAILED,
            TYPE_MEMBER_ADDED,
            TYPE_MEMBER_LEFT,
            TYPE_GROUP_DISBANDED,
            TYPE_CHANGE_REJECTED,
        )

        const val TYPE_UNDECRYPTABLE_OUTER_LAYER = "undecryptableOuterLayer"
        const val TYPE_PENDING_COMMIT = "pendingCommit"

        /**
         * The lines a reindex is allowed to replace.
         *
         * A group event that left one of these behind was never read, so replaying
         * it can only improve on what is there. Every other line -- a message, an
         * applied commit, anything this device sent -- is the final word on its
         * group event and a replay must leave it alone.
         */
        val UNRESOLVED_MARMOT_TYPES = setOf(
            TYPE_UNDECRYPTABLE_OUTER_LAYER,
            TYPE_PENDING_COMMIT,
        )

        /**
         * The row to write for a group event that was just read, given the line that
         * event already has. Null means leave what is there alone.
         *
         * [ChatMessage.id] is autogenerated, so writing [fresh] as it comes always
         * inserts. That is right the first time a group event is read and wrong every
         * time after: a replay would leave the room showing each recovered message
         * twice, once as "Undecryptable Message" and once as itself. A group event has
         * exactly one line and a deterministic id, so [existing] is what makes a
         * re-read an update instead.
         *
         * A placeholder never overwrites a line that says something. A group event
         * this device sent already has its line, written on the way out, and a replay
         * that cannot read back our own ratchet-consumed message must not replace it
         * with "Undecryptable Message".
         *
         * [viewedAt] and [savedAt] stay with [existing]: when a line was first seen is
         * a fact about the reader, not about the event, and a re-read is not a new
         * arrival. Its id stays too, so the broadcast and nostr-event relations
         * pointing at that row keep pointing at it.
         */
        fun reconcileMarmotLine(
            fresh: ChatMessage,
            existing: ChatMessage?,
        ): ChatMessage? {
            if (existing == null) return fresh

            val freshIsPlaceholder = fresh.messageType in UNRESOLVED_MARMOT_TYPES
            val existingIsPlaceholder = existing.messageType in UNRESOLVED_MARMOT_TYPES

            if (freshIsPlaceholder && !existingIsPlaceholder) return null

            return fresh.copy(
                id = existing.id,
                savedAt = existing.savedAt,
                viewedAt = existing.viewedAt,
            )
        }

        /**
         * The request lines this device has since answered, by row id.
         *
         * A request is answered when the step it asked for has been published by
         * this device -- which is exactly what approving it does. The transcript
         * already records that as an authored line, so the answer is read from
         * the list rather than from the session, and it stays right for a room
         * that has run more than one ceremony.
         */
        fun answeredRequests(messages: List<ChatMessage>): Set<Long> = messages
            .mapNotNull { request ->
                val published = (DKG_REQUEST_FULFILMENTS + FROST_REQUEST_FULFILMENTS + ICEBERG_REQUEST_FULFILMENTS)[request.messageType]
                    ?: return@mapNotNull null

                val done = messages.any {
                    it.messageType == published &&
                        it.isUserMessage &&
                        it.isAbout(request)
                }

                request.id.takeIf { done }
            }
            .toSet()

        /**
         * Whether this line is part of the same piece of business as [request].
         *
         * A signing line says which session it belongs to, and that answer is
         * exact: two sessions running at once are two separate decisions, and
         * one of them ending says nothing about the other.
         *
         * Everything else falls back to the clock, which is what the transcript
         * always used. It is right for a ceremony -- a room runs one at a time --
         * and it is what a line written before [frostSigningSessionId] existed
         * has to be read by.
         */
        private fun ChatMessage.isAbout(request: ChatMessage): Boolean = when {
            frostSigningSessionId != null && request.frostSigningSessionId != null ->
                frostSigningSessionId == request.frostSigningSessionId

            // Ceremonies say which one they are now too. The clock used to be
            // right here because a room ran one at a time; a room that holds a
            // group's own ceremony and a subgroup's would otherwise credit an
            // answer given to one as an answer to the other.
            dkgSessionId != null && request.dkgSessionId != null ->
                dkgSessionId == request.dkgSessionId

            else -> createdAt >= request.createdAt
        }

        /**
         * The signing requests that are no longer open, by row id.
         *
         * Not the same thing as [answeredRequests], and deliberately kept apart
         * from it: these are requests the reader never answered, so they have
         * earned no tick and claiming otherwise would credit them with a
         * signature they refused or were never needed for. What they have run
         * out of is a decision to make -- see [FROST_SETTLEMENTS].
         *
         * The reader's own refusal settles their request too, and only theirs.
         * The session is still open -- the others may yet sign it -- so it is
         * not an ending, and another member's refusal leaves the reader with
         * exactly the decision they had.
         */
        fun settledRequests(messages: List<ChatMessage>): Set<Long> = messages
            .filter { request ->
                request.messageType in FROST_REQUEST_TYPES &&
                    messages.any { it.settles() && it.isAbout(request) }
            }
            .map { it.id }
            .toSet()

        private fun ChatMessage.settles(): Boolean =
            messageType in FROST_SETTLEMENTS ||
                (messageType == TYPE_FROST_DECLINED && isUserMessage)

        /**
         * Files one direct message, from whichever side of it this device is on.
         *
         * The decision is [MarmotDirectMessage.classify]'s, which is where it is tested;
         * this turns it into rows. See docs/marmot-direct-messages.md.
         *
         * [filedAs] is the id of the `MarmotInnerEvent` row the caller wrote for the
         * payload as it arrived -- the wrap's own id in a legacy room, the envelope's in
         * a current-profile one. The line for a message this device cannot read has to
         * hang off a row that exists, and `ChatMessage.marmotInnerEventId` is a foreign
         * key that says so.
         */
        private suspend fun directMessage(
            database: DedunDatabase,
            activeKeyPair: KeyPair,
            groupEvent: GroupEvent,
            chatRoomId: String,
            wrap: Event,
            filedAs: HexKey,
            senderIdentity: HexKey?
        ): ChatMessage? {
            val recipientPublicKey = wrap.tags.firstTagValue("p")

            return when (val delivery = MarmotDirectMessage.classify(wrap, activeKeyPair, senderIdentity)) {
                // Our own words coming back. We cannot open our own wrap -- its key was
                // discarded at send time -- so this is indistinguishable from a
                // bystander's view by decryption alone, and filing it as one would
                // replace the words on the row `sendChatMessage` wrote with an empty
                // placeholder. That row is the only copy of them that exists.
                // `NostrDao.persistInboundChatMessage` carries the same guard, for the
                // same reason, on the NIP-17 path.
                MarmotDirectMessage.Delivery.Ours -> {
                    logger.d("Direct message ${wrap.id} is ours; the row written on send is the only copy")
                    null
                }

                // A forged or malformed message costs its own line and nothing else. It
                // is dropped rather than thrown because the caller is inside
                // storeNostrEvent's transaction, and this must not cost the whole event.
                is MarmotDirectMessage.Delivery.Rejected -> {
                    logger.e("Dropping direct message ${wrap.id}: ${delivery.reason}")
                    null
                }

                // Not ours to read. The group is meant to see that a private message was
                // sent and to whom, and nothing more.
                MarmotDirectMessage.Delivery.Unreadable -> {
                    // Relays redeliver and negentropy re-syncs; the wrap yields the same
                    // id every time, so this is what keeps a second delivery from becoming
                    // a second line. ChatMessage.id is autogenerated and would take a
                    // duplicate.
                    if (database.chatMessageDao().getChatMessagesByMarmotInnerEventId(filedAs) != null) {
                        return null
                    }

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_DIRECT_MESSAGE,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = filedAs,
                        senderPublicKey = senderIdentity ?: wrap.pubKey,
                        directMessageRecipientPublicKey = recipientPublicKey,
                        isUserMessage = false,
                        chatRoomId = chatRoomId,
                        createdAt = Instant.fromEpochSeconds(wrap.createdAt),
                        content = ""
                    )
                }

                is MarmotDirectMessage.Delivery.Readable -> {
                    val opened = delivery.opened

                    if (database.chatMessageDao().getChatMessagesByMarmotInnerEventId(opened.rumor.id) != null) {
                        return null
                    }

                    // Keyed on the rumor, not the wrap. This is the id the sender queued,
                    // so both sides of the conversation hold the same message under the
                    // same identity.
                    database.marmotInnerEventDao().upsert(
                        MarmotInnerEvent(
                            id = opened.rumor.id,
                            publicKey = opened.rumor.pubKey,
                            marmotGroupEventId = groupEvent.id,
                            tags = opened.rumor.tags,
                            content = opened.rumor.content,
                            chatRoomId = chatRoomId,
                            kind = opened.rumor.kind,
                            createdAt = Instant.fromEpochSeconds(opened.rumor.createdAt)
                        )
                    )

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_DIRECT_MESSAGE,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = opened.rumor.id,
                        senderPublicKey = opened.seal.pubKey,
                        directMessageRecipientPublicKey = recipientPublicKey,
                        isUserMessage = false,
                        chatRoomId = chatRoomId,
                        createdAt = Instant.fromEpochSeconds(opened.rumor.createdAt),
                        content = opened.rumor.content
                    )
                }
            }
        }

        /**
         * [senderIdentity] is the MLS credential identity of the leaf that sent this,
         * resolved by the caller, which holds the group. It is not derivable from the
         * payload: a direct message's wrap is keyed to a throwaway key and names nobody,
         * and even for kinds that do carry a pubkey the MLS frame is the authenticated
         * source while the field is merely asserted.
         *
         * It is also the only thing [ChatMessage.isUserMessage] can honestly be computed
         * from. Every arm below used to compare the active key against `groupEvent.pubKey`
         * -- the kind:445's signer, which is a fresh random ephemeral key on every send
         * (`NostrSignerInternal(KeyPair())` in `encryptAndSendMarmotInnerEvent`). That can
         * never equal anybody's identity, so it was false for every Marmot message in
         * every room, and every one of them rendered as somebody else's.
         *
         * Null for results that carry no leaf index -- the commit and proposal statuses --
         * which yields false, exactly as before.
         */
        suspend fun fromGroupEventResult(
            database: DedunDatabase,
            activeKeyPair: KeyPair,
            groupEvent: GroupEvent,
            groupEventResult: GroupEventResult,
            senderIdentity: HexKey? = null
        ): ChatMessage? {
            return when(groupEventResult) {
                is GroupEventResult.ApplicationMessage -> {
                    val event = Event.fromJsonOrNull(groupEventResult.innerEventJson)
                        ?: throw MarmotUnprocessableInnerEventException("Can't process ${groupEventResult.innerEventJson}")

                    // A key ceremony's or a signing session's own message. Decided
                    // first: 30310-30312 were nip30303 document kinds too, and round
                    // 1 of a subgroup's ceremony (30312) was read as a document
                    // submission, failed to parse, and landed in the parent's chat
                    // as raw hex -- see docs/ceremony-creation.md, finding F11. Stored like
                    // every inner event, so a ceremony can replay what arrived
                    // before its proposal; acted on by the managers
                    // `NostrDao.fileGroupEventResult` dispatches to, which write
                    // their own transcript lines.
                    //
                    // Except a direct message's envelope. It shares 30330 with a
                    // signing session's decline, and is told apart by what it
                    // carries: an envelope holds a gift wrap, a decline does not.
                    // Treating every 30330 as a decline dropped every direct
                    // message in a current-profile room.
                    //
                    // The Lightning wallet's protocol is the third family, and was
                    // missed: its ceremony, its co-signing and the operator's
                    // traffic each left a raw-JSON bubble beside the lines their
                    // managers write. docs/group-lightning-transcript.md, Phase 1.
                    // The kinds the group signs are not among them -- they reach
                    // `applyInnerEvent`, which checks and describes them.
                    val isDirectMessageEnvelope = MarmotDirectMessage.wrapInside(event) != null
                    val isProtocolMessage = !isDirectMessageEnvelope && (
                        event.kind in DkgRitualEvents.ALL ||
                            event.kind in FrostSigningEvents.ALL ||
                            event.kind in GroupLightningEvents.PROTOCOL
                    )

                    database.marmotInnerEventDao().upsert(
                        MarmotInnerEvent(
                            id = event.id,
                            publicKey = event.pubKey,
                            marmotGroupEventId = groupEvent.id,
                            tags = event.tags,
                            content = event.content,
                            chatRoomId = groupEventResult.groupId,
                            kind = event.kind,
                            createdAt = Instant.fromEpochSeconds(event.createdAt)
                        )
                    )

                    if (isProtocolMessage) return null

                    // A direct message is not a payload to apply. It is
                    // gift wrapped for one member, and what this device does with it
                    // depends only on whether its key opens it. A legacy room carries
                    // the wrap itself; a current-profile room carries it inside an
                    // envelope authored by the sender -- see MarmotDirectMessage. The
                    // row above was filed under the payload's own id either way, and
                    // that is the id the line for a message we cannot read hangs off.
                    if (event.kind == GiftWrapEvent.KIND) {
                        return directMessage(
                            database = database,
                            activeKeyPair = activeKeyPair,
                            groupEvent = groupEvent,
                            chatRoomId = groupEventResult.groupId,
                            wrap = event,
                            filedAs = event.id,
                            senderIdentity = senderIdentity
                        )
                    }

                    if (event.kind == MarmotDirectMessage.ENVELOPE_KIND) {
                        val wrap = MarmotDirectMessage.wrapInside(event)
                        if (wrap == null) {
                            logger.e("Dropping direct message envelope ${event.id}: it holds no wrap")
                            return null
                        }
                        return directMessage(
                            database = database,
                            activeKeyPair = activeKeyPair,
                            groupEvent = groupEvent,
                            chatRoomId = groupEventResult.groupId,
                            wrap = wrap,
                            filedAs = event.id,
                            senderIdentity = senderIdentity
                        )
                    }

                    // A chronicle is neither a document nor a submission: it is a
                    // bundle of documents addressed to one member who is missing
                    // them. Intercepted here rather than in applyInnerEvent for
                    // the same reason the gift wrap above is -- deciding whether
                    // to act needs the active key, which applyInnerEvent has no
                    // business knowing.
                    if (event.kind == ChronicleEvent.KIND) {
                        ChronicleManager.receive(
                            database = database,
                            chatRoomId = groupEventResult.groupId,
                            userPublicKey = activeKeyPair.pubKey.toHex(),
                            page = event,
                        )

                        // No chat line, and not for want of one worth writing.
                        // One line per chronicle is right; one per page is not, and
                        // the pages of a chronicle are not distinguishable from
                        // each other here. That is Phase 7's, and until then a
                        // silent catch-up beats a transcript full of envelopes.
                        return null
                    }

                    // A descendant's guarded wallet, carried up by a member who is in
                    // both rooms. The row above is the whole record of it -- there is
                    // no table -- so all that is left is the line, and only for a
                    // notice this room can actually believe.
                    if (event.kind == GuardedWalletNoticeEvent.KIND) {
                        // And on, a generation further: the statement inside, to
                        // the groups above this one that this device is in. Any
                        // device that believes it can, whether or not it holds this
                        // room's key. docs/guarded-wallet-forwarding.md.
                        GuardedWalletNoticeEvent.statementOf(event)?.let {
                            GuardianManager.forwardOnArrival(database, it, groupEventResult.groupId)
                        }
                        val content = GuardianManager.describeNotice(database, groupEventResult.groupId, event)
                            ?: return null
                        return ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = TYPE_GUARDED_WALLET_SHARED,
                            marmotGroupEventId = groupEvent.id,
                            marmotInnerEventId = event.id,
                            senderPublicKey = senderIdentity ?: event.pubKey,
                            isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                            chatRoomId = groupEventResult.groupId,
                            createdAt = Instant.fromEpochSeconds(event.createdAt),
                            content = content,
                        )
                    }

                    // Somebody in the room holds none of its signed work and is
                    // asking. Any member may answer and none is elected to: a
                    // duplicate answer costs bandwidth and nothing else, since
                    // pages are idempotent and everyone but the recipient ignores
                    // them. A member with nothing signed answers nothing, which
                    // is the honest reply from one still catching up themselves.
                    if (event.kind == ChronicleRequestEvent.KIND) {
                        ChronicleManager.sendTo(
                            database = database,
                            chatRoomId = groupEventResult.groupId,
                            userPublicKey = activeKeyPair.pubKey.toHex(),
                            // The MLS frame is the authenticated source. For these
                            // kinds MIP-03 already forces the two to agree, so the
                            // fallback is for a result that carries no leaf index.
                            recipient = senderIdentity ?: event.pubKey,
                        )

                        return null
                    }

                    // A member asking what the group signs with, so their device
                    // can work out a guarded wallet's leaves, or a member
                    // answering. Plumbing, so no line either way: the row above
                    // is the record, and an answer is checked each time it is
                    // read. Answered like the chronicle's request -- by any
                    // member holding the key state, none elected.
                    // docs/member-guarded-wallets.md, Phase 2.
                    if (event.kind == GroupKeyNoticeEvent.KIND) {
                        MemberWalletManager.answerKeyRequest(
                            database = database,
                            chatRoomId = groupEventResult.groupId,
                            request = event,
                            userPublicKey = activeKeyPair.pubKey.toHex(),
                        )

                        return null
                    }

                    applyInnerEvent(
                        database = database,
                        groupId = groupEventResult.groupId,
                        event = event,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = event.id,
                        senderPublicKey = event.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        createdAt = Instant.fromEpochSeconds(event.createdAt),
                    )
                }
                is GroupEventResult.CommitPending -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = TYPE_PENDING_COMMIT,
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Pending Commit in epoch ${groupEventResult.epoch}", // TODO: Figure out what to do here...
                    )
                }
                is GroupEventResult.CommitProcessed -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = "processedCommit",
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Processed Commit in epoch ${groupEventResult.newEpoch}", // TODO: Figure out what to do here...
                    )
                }
                is GroupEventResult.Duplicate -> {

//                    ChatMessage(
//                        giftWrapPayloadId = null,
//                        groupEventId = groupEvent.id,
//                        messageType = "duplicate",
//                        senderPublicKey = groupEvent.pubKey,
//                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
//                        chatRoomId = groupEventResult.groupId,
//                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
//                        content = groupEventResult.innerEventJson, // TODO: Figure out what to do here...
//                    )
                    null
                }
                is GroupEventResult.Error -> {
//                    ChatMessage(
//                        giftWrapPayloadId = null,
//                        groupEventId = groupEvent.id,
//                        messageType = "error",
//                        senderPublicKey = groupEvent.pubKey,
//                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
//                        chatRoomId = groupEventResult.groupId,
//                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
//                        content = groupEventResult.innerEventJson, // TODO: Figure out what to do here...
//                    )
                    null
                }
                is GroupEventResult.ProposalStaged -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = "proposalStaged",
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Proposal staged: ${groupEventResult.senderLeafIndex}", // TODO: Figure out what to do here...
                    )
                }
                is GroupEventResult.UndecryptableOuterLayer -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = TYPE_UNDECRYPTABLE_OUTER_LAYER,
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Undecryptable Message", // TODO: Figure out what to do here...
                    )
                }
                // Neither is a delivery. quartz 1.15.2's inbound processor produces both
                // from its convergence engine: the first is a payload that decrypted only
                // on a losing fork, which the library is explicit must not be rendered,
                // and the second is input a disbanded or unrecoverable group refuses
                // outright. [MarmotInboundManager] drives MlsGroup itself and never
                // constructs either, so today these are unreachable; if it adopts the
                // engine they get no chat line, the same as a duplicate.
                is GroupEventResult.AppMessageOnCandidateBranch -> null
                is GroupEventResult.RefusedByLifecycle -> null
            }
        }

        /**
         * Apply one [event] the group delivered, and describe it for the
         * transcript.
         *
         * [event] is what is being applied; the other parameters are how it
         * arrived -- [marmotInnerEventId], [senderPublicKey] and [createdAt]
         * belong to the message a member actually sent.
         *
         * [groupSignedEventId] is the third way in, and the one with no
         * envelope at all: a `GroupSignedEvent` the group put its own signature
         * to, applied by `FrostSigningManager` when a session completes or by
         * `ChronicleManager` when a page arrives. Both Marmot ids are null for
         * those -- there is no group event and no inner event behind them --
         * which is why they need an id of their own, and why every row this
         * writes carries it.
         */
        /** A member as a transcript line names them: their profile's name, or the start of their key. */
        internal suspend fun memberName(database: DedunDatabase, publicKey: HexKey): String =
            database.profileDao().getProfileByPublicKey(publicKey)?.humanReadableNameOrPubkey() ?: publicKey.take(8)

        internal suspend fun applyInnerEvent(
            database: DedunDatabase,
            groupId: String,
            event: Event,
            marmotGroupEventId: HexKey?,
            marmotInnerEventId: HexKey?,
            senderPublicKey: HexKey,
            isUserMessage: Boolean,
            createdAt: Instant,
            groupSignedEventId: HexKey? = null,
        ): ChatMessage? {
            // The last resort: raw JSON in a bubble, the only trace an
            // unrecognised payload leaves.
            fun unsupported() = ChatMessage(
                giftWrapPayloadId = null,
                messageType = "unsupported",
                marmotGroupEventId = marmotGroupEventId,
                marmotInnerEventId = marmotInnerEventId,
                senderPublicKey = senderPublicKey,
                isUserMessage = isUserMessage,
                chatRoomId = groupId,
                createdAt = createdAt,
                content = event.toJson(),
            )

            return when (event.kind) {
                // First, above every content kind: protocol kinds are never
                // content, whatever else might share their number, and a `when`
                // takes the first arm that matches. The ceremony's 30310 and 30311
                // once reached document arms that sat above this one.
                //
                // A signing session's own protocol messages. FrostSigningManager
                // applies them and writes its own transcript lines naming who did
                // what, so an "unsupported" row here would be a second, worse
                // account of the same thing.
                in FrostSigningEvents.ALL -> null

                // A key ceremony's own protocol messages, for the same reason.
                // These reach a Marmot room when the group is standing up a
                // subgroup: the child's ChillDKG runs in the parent's room,
                // because the child has no room until the ceremony has made the
                // key its id is derived from. `ChillDkgRitualManager` applies
                // them and writes the ladder of lines naming who joined, who
                // contributed and who confirmed.
                //
                // Null for every member of the room, including the ones the
                // ceremony is not with. They were never in the participant set
                // and their device drops the proposal, so the alternative to no
                // line at all is an "unsupported" one for a ceremony that is
                // none of their business.
                in DkgRitualEvents.ALL -> null

                // The Lightning wallet's ceremony, its co-signing and the
                // operator's traffic, for the same reason again: the managers
                // `NostrDao` dispatches them to act on them and write what is
                // worth a line. Not the three the group signs, which have arms
                // of their own below.
                in GroupLightningEvents.PROTOCOL -> null

                ChatEvent.KIND -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = "message",
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = event.content, // TODO: Figure out what to do here...
                    )
                }
                // The group saying what key one of its rooms signs with, arriving
                // here the way every group-signed event does: applied by
                // `FrostSigningManager` once a quorum has signed the proposal it
                // was carried in.
                //
                // [groupId] is where the signing happened, which is usually not
                // the room the state is about -- a group agrees this before the
                // room exists, so there is routinely nothing to write yet and
                // `GroupKeyStateManager.adopt` files it when the room appears.
                //
                // Recorded rather than believed either way. `GroupKeyStateManager`
                // keeps a state only if the room it *names* rederives from the key
                // it names, and the group holding that key signed it, so a state
                // reaching this line still has to earn the row.
                //
                // No chat line: this is standing state rather than something
                // that happened, and the session that produced it has already
                // written the transcript of it happening. Falling through to
                // "unsupported" would put the raw event in the chat underneath
                // that.
                GroupKeyStateEvent.KIND -> {
                    GroupKeyStateManager.record(
                        database = database,
                        chatRoomId = groupId,
                        innerEvent = event
                    )
                    null
                }

                // The group has certified another group as its child. Verified
                // again here rather than trusted: this arm is reached both from a
                // completed signing session, where the signature has already been
                // checked, and from an arriving inner event, where a member could
                // have sent a rumor of this kind -- and it cannot tell which.
                //
                // Unlike the key state above this *does* write a line. A key state
                // is standing state whose session already wrote the transcript; a
                // subgroup being born is something that happened, and it happened
                // on behalf of members of this room who are not in the child and
                // will otherwise never be told it exists.
                //
                // The name is the one the parent certified, which is the only one
                // that was ever put to this room -- the child may since have been
                // renamed, and this line is a record of what was agreed rather
                // than a view of what is.
                SubgroupBirthCertificateEvent.KIND -> {
                    SubgroupManager.record(
                        database = database,
                        chatRoomId = groupId,
                        innerEvent = event
                    ) ?: return null

                    val name = SubgroupBirthCertificateEvent.parseName(event.tags)
                    val admins = SubgroupBirthCertificateEvent.parseAdminPublicKeys(event.tags).size

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_SUBGROUP_CERTIFIED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group made ${name ?: "a subgroup"} a subgroup, " +
                            "run by $admins of its members"
                    )
                }

                // A member asking the group to guard a wallet of theirs. The
                // inner event carries the signed request whole; only a request
                // that checks out and asks this room gets a line, and it is a
                // line and nothing more -- the group decides when a signer puts
                // it to the quorum. docs/member-guarded-wallets.md.
                MemberWalletRequestEvent.KIND -> {
                    val request = MemberWalletRequestEvent.carried(event) ?: return null
                    val reading = MemberWalletRequestEvent.verified(request)?.reading ?: return null
                    if (!reading.groupChatRoomId.equals(groupId, ignoreCase = true)) return null

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_MEMBER_WALLET_REQUESTED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "@${memberName(database, reading.memberPublicKey)} asked this group to guard a wallet",
                    )
                }

                // The group agreeing to guard a member's wallet. Checked whole --
                // the room's signature and the request inside -- because the line
                // says the group made a promise about somebody's money. It is
                // filed as a `GroupSignedEvent` either way, and every reader
                // checks it there again.
                MemberWalletCertificateEvent.KIND -> {
                    val verified = MemberWalletCertificateEvent.verify(event, groupId) ?: return null
                    // The groups above this one guard the wallet too, and learn
                    // of it only by being told. docs/guarded-wallet-forwarding.md.
                    GuardianManager.forwardOnArrival(database, event, groupId)

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_MEMBER_WALLET_GUARDED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "This group now guards a wallet for @${memberName(database, verified.memberPublicKey)}",
                    )
                }

                // A member saying where this group pays them, or that it should not.
                // The inner event carries the signed statement whole; only one that
                // checks out, was put here by its author and is about this room gets a
                // line, and the line names nobody's address.
                // docs/group-payout-destination.md.
                GroupPayoutDestinationEvent.KIND -> {
                    val signed = GroupPayoutDestinationEvent.carried(event) ?: return null
                    val statement = GroupPayoutDestinationEvent.verified(signed) ?: return null
                    if (!statement.chatRoomId.equals(groupId, ignoreCase = true)) return null
                    val name = memberName(database, statement.member)

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = when (statement) {
                            is GroupPayoutDestination -> TYPE_PAYOUT_DESTINATION_SET
                            is PayoutDestinationRemoved -> TYPE_PAYOUT_DESTINATION_REMOVED
                        },
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = when (statement) {
                            is GroupPayoutDestination -> "@$name set where this group pays them"
                            is PayoutDestinationRemoved -> "@$name removed where this group pays them"
                        },
                    )
                }

                // The group saying whether it guards its members' wallets, and at
                // what delay. Checked as the room's own signature, which is all a
                // policy needs; one that does not verify gets no line, and is
                // ignored where it is filed too. docs/member-wallet-delay.md.
                MemberWalletPolicyEvent.KIND -> {
                    val policy = MemberWalletPolicyEvent.verify(event, groupId) ?: return null

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_MEMBER_WALLET_POLICY,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = when (policy) {
                            is MemberWalletPolicyEvent.Policy.Open ->
                                "The group guards members' wallets, after ${BlockDuration.describe(policy.delay)}"
                            is MemberWalletPolicyEvent.Policy.Closed ->
                                "The group has stopped guarding new members' wallets"
                        },
                    )
                }

                // The group opening a wallet. Checked the whole way -- the room's
                // signature, and the statement rederiving from the room's own key --
                // because a line saying "the group opened a wallet" is a line people
                // will send money on. A statement that fails either gets no line;
                // it is filed as a `GroupSignedEvent` all the same, and `GroupWallet`
                // refuses it there for the same reasons.
                GroupWalletStatementEvent.KIND -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    val thresholdPublicKey = GroupWalletManager.thresholdKeyFor(database, groupId)
                        ?: return null
                    val wallet = GroupWallet.of(signed, thresholdPublicKey) ?: return null
                    // A guarded wallet is one the groups above can find only by
                    // being told of it. docs/guarded-wallet-forwarding.md.
                    if (wallet.account.isGuarded) GuardianManager.forwardOnArrival(database, event, groupId)

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_WALLET_OPENED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group opened a wallet on ${GroupWalletStatementEvent.chainName(wallet.chain)}"
                    )
                }

                // The group keeping a Lightning wallet, checked as the wallet screen checks it.
                GroupLightningWalletStatementEvent.KIND -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    val wallet = GroupLightningWalletManager.verify(database, groupId, signed) ?: return null
                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_LIGHTNING_WALLET_OPENED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group opened a Lightning wallet on ${GroupWalletStatementEvent.chainName(wallet.statement.chain)}. " +
                            "${wallet.statement.threshold} of ${wallet.statement.participants.size} sign for it."
                    )
                }

                // A new operator, or none. Signed by the room, which is all the line claims.
                GroupLightningOperatorStatementEvent.KIND -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    if (!signed.verifies()) return null
                    val statement = GroupLightningOperatorStatementEvent.read(event.tags) ?: return null
                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        // Who, since that is the one thing the line is for. The term is
                        // a counter the wallet's screen keeps; it means nothing to a reader.
                        content = when (val operator = statement.operator) {
                            null -> "Nobody runs the group's Lightning wallet now. It stops until someone is chosen."
                            else -> "@${memberName(database, operator)} runs the group's Lightning wallet now."
                        }
                    )
                }

                // A payment the group signed for. The line says how much and that the operator's node
                // pays it; whether it went is the snapshot's to say, on the wallet's screen.
                GroupLightningAuthorisationEvent.KIND -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    if (!signed.verifies()) return null
                    val pay = GroupLightningAuthorisationEvent.read(event.tags)
                    val buy = if (pay == null) GroupLightningAuthorisationEvent.readBuyLiquidity(event.tags) else null
                    val channelAction = if (pay == null && buy == null) GroupLightningAuthorisationEvent.readChannelAction(event.tags) ?: return null else null
                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_LIGHTNING_PAYMENT_AUTHORISED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = when {
                            pay != null -> "The group authorised a Lightning payment of ${Satoshi(pay.amountMsat / 1000).grouped()} sats. The operator's node pays it."
                            buy != null -> "The group authorised adding ${Satoshi(buy.amountSat).grouped()} sats of inbound liquidity, for at most ${Satoshi(buy.maxFeeSat).grouped()} sats in fees. The operator's node buys it."
                            channelAction?.action == GroupLightningAuthorisationEvent.FORCE_CLOSE -> "The group authorised force-closing a Lightning channel. Its money reaches the onchain wallet after a delay."
                            else -> "The group authorised closing a Lightning channel to its onchain wallet."
                        }
                    )
                }

                // A profile or a relay list -- the group's or a member's own --
                // travelling through the room. The app no longer reads either as
                // the group's, and both are identity plumbing nobody sends into a
                // room on purpose, so neither is a line nor a raw-JSON bubble: a
                // signing session an older build opened for one ends in silence.
                // A kind:1 is content, and keeps the bubble it always had.
                MetadataEvent.KIND,
                AdvertisedRelayListEvent.KIND,
                ChatMessageRelayListEvent.KIND,
                SearchRelayListEvent.KIND,
                BlockedRelayListEvent.KIND -> null

                // v2's two non-chat kinds. A kind:1210 that arrives is one member's
                // assertion about what the group's state became, and this device
                // derives that for itself from the commit it applied (see
                // [TYPE_MEMBER_ADDED]); a kind:1009 is an edit the app does not
                // apply. Neither is a line, and neither is a raw-JSON bubble from
                // a White Noise member. Every other unknown kind keeps the bubble,
                // which is the only trace an unrecognised payload leaves.
                MarmotAppEvent.KIND_SYSTEM, MarmotAppEvent.KIND_EDIT -> null

                else -> unsupported()
            }
        }
    }
}