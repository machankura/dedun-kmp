package com.machankura.dedun.compose.database

import com.machankura.dedun.compose.database.model.GroupLightningSnapshotRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.dao.GroupLightningRecordsDao
import com.machankura.dedun.compose.database.model.IcebergShareRow
import com.machankura.dedun.compose.database.model.IcebergNonceRow
import com.machankura.dedun.compose.database.model.IcebergLabelLedger
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.database.model.IcebergEchoRow
import com.machankura.dedun.compose.database.model.IcebergChannelLedger
import com.machankura.dedun.compose.database.model.IcebergBudgetSpend
import com.machankura.dedun.compose.database.dao.IcebergCoSigningDao
import com.machankura.dedun.compose.database.dao.GroupLightningSeedDao
import com.machankura.dedun.compose.database.model.GroupLightningSeed
import com.machankura.dedun.compose.database.dao.IcebergKeySessionDao
import com.machankura.dedun.compose.database.model.IcebergParticipantMessage
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.dao.GroupTransactionDao
import com.machankura.dedun.compose.database.dao.MemberWalletDao
import androidx.room3.ColumnTypeConverters
import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.machankura.dedun.compose.database.converters.DedunConverters
import com.machankura.dedun.compose.database.dao.BroadcastNostrEventReceiptDao
import com.machankura.dedun.compose.database.dao.BroadcastNostrEventRequestDao
import com.machankura.dedun.compose.database.dao.ChatMessageBroadcastNostrEventReceiptRelationDao
import com.machankura.dedun.compose.database.dao.ChatMessageBroadcastNostrEventRequestRelationDao
import com.machankura.dedun.compose.database.dao.ChatMessageDao
import com.machankura.dedun.compose.database.dao.ChatMessageNostrEventRelationDao
import com.machankura.dedun.compose.database.dao.ChatRoomDao
import com.machankura.dedun.compose.database.dao.ConnectionDao
import com.machankura.dedun.compose.database.dao.DkgSessionDao
import com.machankura.dedun.compose.database.dao.FrostSigningSessionDao
import com.machankura.dedun.compose.database.dao.GiftWrapMessageDao
import com.machankura.dedun.compose.database.dao.GiftWrapPayloadDao
import com.machankura.dedun.compose.database.dao.GiftWrapSealDao
import com.machankura.dedun.compose.database.dao.GroupKeyStateDao
import com.machankura.dedun.compose.database.dao.GroupSignedEventDao
import com.machankura.dedun.compose.database.dao.InReplyToRelationDao
import com.machankura.dedun.compose.database.dao.MarmotCommitResultDao
import com.machankura.dedun.compose.database.dao.MarmotOutboundDao
import com.machankura.dedun.compose.database.dao.MarmotGroupEventDao
import com.machankura.dedun.compose.database.dao.MarmotInnerEventDao
import com.machankura.dedun.compose.database.dao.MarmotKeyPackageBundleDao
import com.machankura.dedun.compose.database.dao.MarmotRoutingDao
import com.machankura.dedun.compose.database.dao.MarmotPublishObligationDao
import com.machankura.dedun.compose.database.dao.MarmotKeyPackageDao
import com.machankura.dedun.compose.database.dao.MarmotRetainedEpochSecretDao
import com.machankura.dedun.compose.database.dao.MentionDao
import com.machankura.dedun.compose.database.dao.NegentropySynchronizeRequestDao
import com.machankura.dedun.compose.database.dao.NegentropySynchronizeResultDao
import com.machankura.dedun.compose.database.dao.NostrDao
import com.machankura.dedun.compose.database.dao.NostrEventDao
import com.machankura.dedun.compose.database.dao.NostrEventRelayDao
import com.machankura.dedun.compose.database.dao.NostrNip17Dao
import com.machankura.dedun.compose.database.dao.ParticipantDao
import com.machankura.dedun.compose.database.dao.PostDao
import com.machankura.dedun.compose.database.dao.ProfileDao
import com.machankura.dedun.compose.database.dao.QuotedRelationDao
import com.machankura.dedun.compose.database.dao.ReactionDao
import com.machankura.dedun.compose.database.dao.RecentSearchDao
import com.machankura.dedun.compose.database.dao.RelayDao
import com.machankura.dedun.compose.database.dao.RepostedRelationDao
import com.machankura.dedun.compose.database.dao.SynchronizeNostrEventRequestDao
import com.machankura.dedun.compose.database.dao.SynchronizeNostrEventResultDao
import com.machankura.dedun.compose.database.dao.UnsignedNostrEventDao
import com.machankura.dedun.compose.database.dao.ZapDao
import com.machankura.dedun.compose.database.model.BroadcastNostrEventReceipt
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation
import com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation
import com.machankura.dedun.compose.database.model.ChatMessageNostrEventRelation
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.Connection
import com.machankura.dedun.compose.database.model.GiftWrapMessage
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.GiftWrapSeal
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.InReplyToRelation
import com.machankura.dedun.compose.database.model.MarmotCommitResult
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle
import com.machankura.dedun.compose.database.model.MarmotRouting
import com.machankura.dedun.compose.database.model.MarmotPublishObligation
import com.machankura.dedun.compose.database.model.MarmotOutboundGate
import com.machankura.dedun.compose.managers.MarmotEngine
import com.machankura.dedun.compose.database.model.MarmotRetainedEpochSecret
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import com.machankura.dedun.compose.database.model.Mention
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.NegentropySynchronizeResult
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.NostrEventRelay
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Post
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.QuotedRelation
import com.machankura.dedun.compose.database.model.Reaction
import com.machankura.dedun.compose.database.model.RecentSearch
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.Relay
import com.machankura.dedun.compose.database.model.RepostedRelation
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventResult
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.Zap
import kotlin.time.Instant

val GENESIS_AT = Instant.fromEpochMilliseconds(1231006505000L)

@Database(
    entities = [
        BroadcastNostrEventReceipt::class,
        BroadcastNostrEventRequest::class,
        Connection::class,
        ChatMessage::class,
        ChatMessageBroadcastNostrEventRequestRelation::class,
        ChatMessageBroadcastNostrEventReceiptRelation::class,
        ChatMessageNostrEventRelation::class,
        ChatRoom::class,
        DkgParticipantMessage::class,
        DkgSession::class,
        FrostSignerMessage::class,
        FrostSigningItem::class,
        FrostSigningSession::class,
        GiftWrapMessage::class,
        GiftWrapSeal::class,
        GiftWrapPayload::class,
        GroupKeyState::class,
        GroupLightningInvoiceRow::class,
        GroupLightningPaymentRow::class,
        GroupLightningSeed::class,
        GroupLightningSnapshotRow::class,
        GroupSignedEvent::class,
        GroupTransaction::class,
        IcebergBudgetSpend::class,
        IcebergChannelLedger::class,
        IcebergEchoRow::class,
        IcebergKeySession::class,
        IcebergLabelAssignment::class,
        IcebergLabelLedger::class,
        IcebergNonceRow::class,
        IcebergParticipantMessage::class,
        IcebergShareRow::class,
        InReplyToRelation::class,
        MarmotCommitResult::class,
        MarmotGroupEvent::class,
        MarmotInnerEvent::class,
        MarmotKeyPackage::class,
        MarmotKeyPackageBundle::class,
        MarmotRouting::class,
        MarmotPublishObligation::class,
        MarmotOutboundGate::class,
        MarmotRetainedEpochSecret::class,
        MemberWallet::class,
        MemberWalletSpend::class,
        Mention::class,
        NegentropySynchronizeRequest::class,
        NegentropySynchronizeResult::class,
        NostrEvent::class,
        NostrEventRelay::class,
        Participant::class,
        Post::class,
        Profile::class,
        QuotedRelation::class,
        Reaction::class,
        RecentSearch::class,
        Relay::class,
        RepostedRelation::class,
        SynchronizeNostrEventRequest::class,
        SynchronizeNostrEventResult::class,
        UnsignedNostrEvent::class,
        Zap::class
    ],
    version = 36,
    autoMigrations = [
        // v2 only adds the DkgSession/DkgParticipantMessage tables, so Room can
        // generate the migration itself — nothing existing changes shape.
        AutoMigration(from = 1, to = 2),
        // v3 adds the three nullable approval timestamps to DkgSession. Nullable
        // additions need no default and drop no data, so Room generates this one
        // too. Rituals already in flight come back with all three null, which reads
        // as "not approved yet" and simply asks the member for each step.
        AutoMigration(from = 2, to = 3),
        // v4 changes no schema at all -- it rewrites `dkgApprovalNeeded` chat rows
        // into one type per ritual step. Data, not shape, so it is a manual
        // migration passed to the builder rather than an entry here. See
        // MIGRATION_3_4.
        //
        // v5 adds the nullable MarmotInnerEvent.payloadEventId, which names the
        // nip30303 event a SubmissionEvent rumor carries. Rumors queued before
        // this come back null, which reads as "not a submission" -- correct,
        // since none of them were.
        AutoMigration(from = 4, to = 5),
        // v6 adds the FrostSigningSession/FrostSignerMessage tables and the
        // nullable DkgSession.publicShares. New tables and a nullable column are
        // both shapes Room can migrate itself. A ceremony that completed before
        // this reads back null, and signing falls back to not cross-checking
        // shares rather than refusing to run.
        AutoMigration(from = 5, to = 6),
        // v7 adds the GroupKeyState table, which records what shared key a room
        // signs with instead of leaving it to be rederived. A new table is a
        // shape Room migrates itself. Rooms created before this have no row and
        // fall back to the rederivation scan in FrostSigningManager.completedKey,
        // which is why that scan stays.
        AutoMigration(from = 6, to = 7),
        // v8 adds a nullable direct message recipient to MarmotInnerEvent and
        // ChatMessage. Nullable additions need no default and drop no data, so Room
        // generates this one. Rows written before it come back null, which reads as
        // "not a direct message" -- the only answer that is true of all of them.
        AutoMigration(from = 7, to = 8),
        // v9 adds the nullable FrostSigningSession.derivationPath, which says
        // what key off the ceremony's the session signs as. Sessions started
        // before it read back null, meaning the untweaked threshold key -- which
        // is what they were signing as, so one caught mid-flight finishes the way
        // it began rather than switching keys between two of its own rounds.
        AutoMigration(from = 8, to = 9),
        // v10 moves FrostSigningSession's five per-event columns onto the new
        // FrostSigningItem table, so one session can sign a batch. It copies
        // before it drops, which no AutoMigration can express -- see
        // MIGRATION_9_10, and the note there on why an in-flight session losing
        // its nonce seed would be worse than losing the session.
        //
        // v11 adds the nullable ChatMessage.frostSigningSessionId, which says
        // which signing session a transcript line is about. Lines written before
        // it read back null and are read by the clock, the way they always were:
        // a room that could only propose one thing at a time cannot have written
        // two sessions' lines into the same stretch of transcript.
        AutoMigration(from = 10, to = 11),
        // v12 adds the nullable ChatRoom.archiveRequestedAt -- renamed to
        // chronicleRequestedAt in v14 -- which stops a device with no signed work
        // in a room asking the group for its history on every launch while an
        // answer is in flight. Rooms written before it read back null, meaning
        // "never asked" -- true of all of them, and harmless: the request only
        // goes out for a room that holds no signed work, and a room that holds
        // some will not ask.
        AutoMigration(from = 11, to = 12),
        // v13 adds the GroupSignedEvent table and the nullable
        // `groupSignedEventId` on every row a signed event turns into. A new
        // table and nullable additions are both shapes Room migrates itself.
        // Rows written before it read back null, meaning "this device does not
        // hold the event behind this row" -- true of all of them, since nothing
        // kept it. They are not backfilled: the events are gone, and inventing
        // an id for one would point a row at a signature nobody can produce.
        AutoMigration(from = 12, to = 13),
        // v14 changes one column name and three stored strings, all of which say
        // "archive" about the group's signed record. The word was wanted back for
        // the ordinary thing a user does to a chat, and a column called
        // `archiveRequestedAt` sitting on ChatRoom is exactly where the two
        // meanings would have met. Room can rename a column and cannot rewrite the
        // rows in the same breath, so this is a manual migration passed to the
        // builder rather than an entry here. See MIGRATION_13_14.
        //
        // v15 adds the nullable ChatRoom.joinedGroupAt, which says when this
        // device became a member and so which of the group's messages were never
        // its to read. Adding a nullable column is a shape Room migrates itself;
        // deleting the placeholder chat lines already written for those messages
        // is not, and a member who joined a busy room is looking at a screenful of
        // them. Manual for that half, so it too is passed to the builder rather
        // than listed here. See MIGRATION_14_15.
        //
        // v16 adds an index on ChatMessage.chatRoomId. No column changes and no
        // rows move -- the chat list now looks up each room's newest line, and
        // without the index that lookup reads every message on the device. Room
        // creates an index on its own.
        AutoMigration(from = 15, to = 16),
        // v17 adds four nullable columns for subgroups -- a group made by
        // another group, which certifies it with its own quorum. See
        // docs/subgroups.md.
        //
        // GroupKeyState gains `parentChatRoomId` and `birthCertificateJson`, the
        // parent's signature over this room's id and the index into it. ChatRoom
        // gains `parentChatRoomId`, a copy of the same verified value so the room
        // list needs no second read. DkgSession gains one too, which is an
        // unverified claim off a ceremony proposal and is read for nothing but a
        // screen's title.
        //
        // **None of the three is a foreign key.** A self-referential cascade on
        // ChatRoom would mean deleting a parent room deletes its subgroups and,
        // by their own cascades, those rooms' messages, participants, key states
        // and signed events. Each column also routinely names a room this device
        // does not have, since a subgroup member need never have been in the
        // parent, so a dangling value is the normal state. Adding nullable
        // columns is a shape Room migrates itself.
        AutoMigration(from = 16, to = 17),
        // v18 adds the nullable ChatMessage.dkgSessionId, the pair to
        // frostSigningSessionId, because "the room's ceremony" stopped naming one
        // thing. A subgroup whose admins are the whole group runs in the room the
        // group's own ceremony ran in, so a transcript line has to say which of
        // the two it is about -- otherwise a member tapping "your approval is
        // needed" is shown whichever ceremony is newest, which is routinely the
        // wrong one. Rows written before it read back null and fall back to the
        // clock, which is correct for them: nothing that predates subgroups ran
        // two ceremonies in one room.
        AutoMigration(from = 17, to = 18),
        // v19 adds two nullable DkgSession columns, both of which the ceremony's
        // room used to answer and no longer can: a subgroup's ChillDKG now runs
        // in the **parent's** Marmot room rather than in a NIP-17 room derived
        // from the child's admins. See docs/subgroups.md.
        //
        // `participantPublicKeys` is who the ceremony is with -- a subset of the
        // parent's members, so the room's roster is the wrong set -- and
        // `subject` is what the room it will make is to be called, which used to
        // be the ceremony room's own name. Both are read off the proposal, which
        // has carried both since before this move; what changed is where they
        // land. Rows written before this read back null and fall back to the
        // room, which is correct for them: a ceremony held in its own NIP-17 room
        // ran over exactly that room's members and was named after it.
        AutoMigration(from = 18, to = 19),
        // v20 adds the nullable ownerPublicKey to SynchronizeNostrEventRequest and
        // NegentropySynchronizeRequest: the identity that asked, so that only its
        // pump answers. The two queues used to be the device's, drained by whoever
        // was open with their key pair, and a gift wrap fetched for one profile
        // while another was open was stored under the wrong key and never opened
        // again. Rows written before this read back null, meaning "the device's",
        // which is what they were, and any identity may drain them. The broadcast
        // queue deliberately gets no owner: a signed event is anyone's to carry.
        AutoMigration(from = 19, to = 20),
        // v21 adds the nullable FrostSigningSession.signDeclinedAt, the other
        // answer to the question signApprovedAt records: a member who refused to
        // sign, on a session that runs on without them. Refusing used to end the
        // session outright, so no row written before this can be one where the
        // owner declined and the session is still open -- every such session is
        // FAILED, and null reads as "not declined", which is true of all the
        // rest. A nullable column is a shape Room migrates itself.
        AutoMigration(from = 20, to = 21),
        // v22 adds three nullable columns for the Marmot current profile (see
        // docs/marmot-v2.md, Phase 3): MarmotKeyPackageBundle.profile and .dTag, and
        // MarmotKeyPackage.profile. A bundle's profile decides which shape a rotation
        // republishes into which slot, its dTag is the kind:30443 slot it reuses so a
        // replacement supersedes rather than accumulates, and a peer package's profile
        // is what a room picks by. Rows from before read back null, which every
        // reader takes as legacy -- what they were. (v22-v24 were v21-v23 on the
        // branch that made them; they moved up one when the branch met v21 above.)
        AutoMigration(from = 21, to = 22),
        // v23 adds MarmotRouting: the address a room's kind:445 traffic is carried
        // under, separate from the room's id (docs/marmot-v2.md, Phase 4). A
        // current-profile room this app creates mints a random routing id -- the
        // room's own id is a derived key, which v2's routing component says must
        // never be the address -- and every reader keyed on the `h` tag resolves it
        // through this table. No rows are backfilled: a room with none is addressed
        // by its own id, which is what every room before the table was.
        AutoMigration(from = 22, to = 23),
        // v24 adds MarmotPublishObligation and MarmotOutboundGate: the commits this
        // device prepared and has not yet heard a relay accept, and the gates a room
        // is under (docs/marmot-v2.md, Phase 6). Both have to outlive the process --
        // an obligation forgotten at a crash is a replacement commit at the next
        // start, which forks the group against whoever took the first one.
        AutoMigration(from = 23, to = 24),
        // v25 adds FrostSigningSession.resentAt: when the coordinator last
        // re-published a signing session for the members it had heard nothing
        // from (docs/frost-session-resend.md, Phase 2). Nullable and nothing is
        // backfilled -- a session that has never been resent reads null, which
        // is what every session before the column was.
        AutoMigration(from = 24, to = 25),
        // v26 is the group wallet's spends (docs/group-chain-wallet.md, Phase 6):
        // the GroupTransaction table, FrostSigningSession.subject -- NOT NULL with
        // a default of EVENTS, which is what every session before it was -- and
        // the nullable FrostSigningItem.inputIndex. Nothing is backfilled and
        // nothing is rebuilt; a rebuild of either signing table would fire the
        // cascades from FrostSigningSession, see docs/frost-batch-signing.md.
        AutoMigration(from = 25, to = 26),
        // v27 drops the nip30303 document tables and MarmotInnerEvent's
        // payloadEventId, and deletes the chat lines nothing renders any more
        // (docs/ceremony-creation.md, Phase 8). Manual -- it deletes rows --
        // so it is MIGRATION_26_27 in `getRoomDatabase`, not an entry here.
        //
        // v28 adds the nullable GroupTransaction.guardedChatRoomId: the descendant
        // whose guarded wallet a guardian's spend draws from
        // (docs/group-wallet-guardians.md, Phase 7). Every spend before it was the
        // room's own, which is what null means.
        AutoMigration(from = 27, to = 28),
        // v29 adds the nullable DkgSession.walletPolicy: the wallet policy a
        // subgroup's ceremony was proposed under (docs/subgroup-wallet-policy.md,
        // Phase 2). Every ceremony before it chose none, which is what null means.
        AutoMigration(from = 28, to = 29),
        // v30 adds MemberWallet and MemberWalletSpend: the random key of a
        // guarded wallet one member holds and a group guards, and the spends the
        // member signed alone from it (docs/member-guarded-wallets.md, Phase 5).
        // New tables, and no foreign keys -- deleting a group's room must never
        // delete a key coins may sit behind.
        AutoMigration(from = 29, to = 30),
        // v31 adds the nullable DkgSession.guardianDelay: the delay a guarded
        // subgroup's ceremony was proposed under for the parent's own leaf
        // (docs/guardian-delay.md, Phase 4). Null for every ceremony before it.
        AutoMigration(from = 30, to = 31),
        // v32 is the group Lightning wallet (docs/group-lightning-wallet.md): the Iceberg key
        // ceremony (IcebergKeySession, IcebergParticipantMessage), the node seed
        // (GroupLightningSeed), the co-signing protocol (seven Iceberg tables, from the round-one
        // pool to the per-channel ledger), and what members keep of the operator's reports
        // (GroupLightningSnapshotRow, GroupLightningPaymentRow, GroupLightningInvoiceRow). New
        // tables and nothing else. They were four versions on the branch that built them, before
        // it merged after v31; no installed database ever had those, so they are one step here.
        AutoMigration(from = 31, to = 32),
        // v33 deletes the raw-JSON chat lines the group Lightning wallet's own messages left
        // (docs/group-lightning-transcript.md, Phase 1). Manual -- it deletes rows -- so it is
        // MIGRATION_32_33 in `getRoomDatabase`, not an entry here.
        //
        // v34 adds the nullable ChatMessage.lightningFactKey: the channel or payment a Lightning
        // wallet line reports (docs/group-lightning-transcript.md, Phase 5). Null on every line
        // before it, none of which is one.
        AutoMigration(from = 33, to = 34),
        // v35 keys IcebergChannelLedger by funding output as well as channel, so a splice's new output
        // keeps a ledger beside its parent's, and adds fundingTxIndex (0 on every row before it, all of
        // them first outputs), parentOutPoint and purchaseId (docs/group-lightning-liquidity.md, Phase 5).
        AutoMigration(from = 34, to = 35),
        // v36 adds the nullable GroupLightningInvoiceRow.requester and .paymentHash, and an index on
        // (wallet, paymentHash): whose deposit an invoice is, and the hash a payment joins it by
        // (docs/member-deposits.md, Phase 6). Null on every row before it.
        AutoMigration(from = 35, to = 36),
    ]
)
@ConstructedBy(DedunDatabaseConstructor::class)
@ColumnTypeConverters(DedunConverters::class)
abstract class DedunDatabase: RoomDatabase() {
    /**
     * The Marmot engine for this database's current-profile rooms, made on first use
     * and living as long as the database does. Owned here rather than registered
     * elsewhere because the DAOs that need it are made by Room from this instance and
     * can be handed nothing else. See [MarmotEngine].
     */
    val marmotEngine: MarmotEngine by lazy { MarmotEngine(this) }

    abstract fun broadcastNostrEventReceiptDao(): BroadcastNostrEventReceiptDao
    abstract fun broadcastNostrEventRequestDao(): BroadcastNostrEventRequestDao

    abstract fun chatMessageDao(): ChatMessageDao

    abstract fun chatMessageBroadcastNostrEventRequestRelationDao(): ChatMessageBroadcastNostrEventRequestRelationDao

    abstract fun chatMessageBroadcastNostrEventReceiptRelationDao(): ChatMessageBroadcastNostrEventReceiptRelationDao

    abstract fun chatMessageNostrEventRelationDao(): ChatMessageNostrEventRelationDao

    abstract fun chatRoomDao(): ChatRoomDao

    abstract fun dkgSessionDao(): DkgSessionDao

    abstract fun frostSigningSessionDao(): FrostSigningSessionDao

    abstract fun groupKeyStateDao(): GroupKeyStateDao

    abstract fun groupSignedEventDao(): GroupSignedEventDao

    abstract fun groupTransactionDao(): GroupTransactionDao

    abstract fun memberWalletDao(): MemberWalletDao

    abstract fun connectionDao(): ConnectionDao

    abstract fun giftWrapMessageDao(): GiftWrapMessageDao

    abstract fun giftWrapSealDao(): GiftWrapSealDao

    abstract fun giftWrapPayloadDao(): GiftWrapPayloadDao

    abstract fun inReplyToRelationDao(): InReplyToRelationDao


    abstract fun icebergKeySessionDao(): IcebergKeySessionDao

    abstract fun groupLightningSeedDao(): GroupLightningSeedDao

    abstract fun icebergCoSigningDao(): IcebergCoSigningDao

    abstract fun groupLightningRecordsDao(): GroupLightningRecordsDao

    abstract fun marmotCommitResultDao(): MarmotCommitResultDao

    abstract fun marmotGroupEventDao(): MarmotGroupEventDao

    abstract fun marmotInnerEventDao(): MarmotInnerEventDao

    abstract fun marmotKeyPackageBundleDao(): MarmotKeyPackageBundleDao
    abstract fun marmotRoutingDao(): MarmotRoutingDao
    abstract fun marmotPublishObligationDao(): MarmotPublishObligationDao

    abstract fun marmotKeyPackageDao(): MarmotKeyPackageDao

    abstract fun marmotOutboundDao(): MarmotOutboundDao

    abstract fun marmotRetainedEpochSecretDao(): MarmotRetainedEpochSecretDao

    abstract fun mentionDao(): MentionDao

    abstract fun negentropySynchronizeRequestDao(): NegentropySynchronizeRequestDao

    abstract fun negentropySynchronizeResultDao(): NegentropySynchronizeResultDao

    abstract fun nostrDao(): NostrDao

    abstract fun nostrEventDao(): NostrEventDao

    abstract fun nostrEventRelayDao(): NostrEventRelayDao

    abstract fun nostrNip17Dao(): NostrNip17Dao

    abstract fun participantDao(): ParticipantDao

    abstract fun postDao(): PostDao

    abstract fun profileDao(): ProfileDao

    abstract fun quotedRelationDao(): QuotedRelationDao

    abstract fun reactionDao(): ReactionDao

    abstract fun recentSearchDao(): RecentSearchDao

    abstract fun relayDao(): RelayDao

    abstract fun repostedRelationDao(): RepostedRelationDao

    abstract fun synchronizeNostrEventRequestDao(): SynchronizeNostrEventRequestDao

    abstract fun synchronizeNostrEventResultDao(): SynchronizeNostrEventResultDao

    abstract fun unsignedNostrEventDao(): UnsignedNostrEventDao
    abstract fun zapDao(): ZapDao

    suspend fun wipeData() {
        useWriterConnection { transactor ->
            transactor.immediateTransaction {
                // TODO: Actually delete the data...
                //
                // When this does: MemberWallet holds the only copy of keys that
                // coins may sit behind (docs/member-guarded-wallets.md, D6). A
                // caller wiping a profile that holds an open member wallet has to
                // say so first and offer the key's export, before anything goes.
            }
        }
    }
}