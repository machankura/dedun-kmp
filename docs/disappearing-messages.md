# Disappearing NIP-17 messages, one day by default

A plan to give every NIP-17 event Giza sends a NIP-40 `expiration`, with no exceptions. That
includes what a person writes, what a group sends, and every ceremony round, signing round,
confederation statement and Welcome that travels the same way. What a person writes expires one
day after it is written unless they choose otherwise. When that time comes, both people's Giza
deletes it, and the relays holding the wrap are asked to drop it. Protocol messages expire too,
on a clock that is never shorter than the protocol needs (D2). A person can change the length
for what they write, or turn it off. They can do this for everything they send, or for one
conversation.

Written against `3b0b3c9a` on 2026-11-22. **Plan, not built.** Revised the same day: protocol
messages were first left out, and now expire too.

Read [marmot-direct-messages.md](./marmot-direct-messages.md) before changing anything on the
send path. It explains why a `GiftWrapPayload` row with a null seal id leaves the device, and
that rule is the one this plan touches most. [frost-session-resend.md](./frost-session-resend.md)
already treats a NIP-40 expiry as one of the reasons a member can miss a round, and that is
why signing rounds can expire with the person's own setting. [group-threshold-ecdh.md](./group-threshold-ecdh.md)
and [group-inbox.md](./group-inbox.md) cover the two ways a *group* sends NIP-17.

## What the NIPs say, and where they disagree

- **NIP-17**: clients "MAY offer disappearing messages by setting an `expiration` tag in the gift
  wrap of each receiver". The tag "SHOULD be included on the `kind:13` seal as well, in case it
  leaks". NIP-17 says nothing about the rumor (kind 14), and it defines no event for a shared
  or preferred expiry. Each sender decides for their own messages.
- **NIP-59**: "Tags MUST always be empty in a `kind:13`." This conflicts directly with NIP-17's
  seal advice. NIP-59 also says that when both layers carry expirations, each layer should use
  its own random timestamp.
- **NIP-40**: the expiration is an absolute unix time. Relays "SHOULD drop" events that are
  already expired when published, and "SHOULD NOT send expired events to clients", but they
  "MAY persist them indefinitely". Clients "SHOULD ignore events that have expired" and "SHOULD
  NOT send expiration events to relays that do not support this NIP". NIP-40 also says plainly
  that expiration is **not a security feature**.

The plan follows from these. The rumor's expiration is what Giza obeys, because it is the only
copy the sender signed (through the seal) and the only one the recipient can trust. The wrap's
expiration is a request to relays. The seal stays empty (D5).

## Where NIP-17 leaves Giza today

Every outbound gift wrap comes from one of these sources, and none of them sets an expiration
today. Under this plan, all of them do.

| Path | Builds the wrap | What it carries | Clock (D2) |
|---|---|---|---|
| A person writing in a NIP-17 room | `DatabaseChatRepository.sendChatMessage` → `GiftWrapPayload` → `sealGiftWrapPayload` → `NostrNip17Dao.persistAndBroadcastGiftWrap` | kind 14 and the other kinds a person writes | the person's policy |
| The notification quick reply | `NotificationActions` → `sendChatMessage` | kind 14 | the person's policy |
| A group writing to someone | `GroupDmSender.wrapOwed` (`GiftWrapEvent.create`) | a rumor signed by the group, sealed by threshold ECDH | the person's policy, signed by the quorum |
| A group's inbox reply | `InboxReplies.wrapAndOwe` (`InboxWrap.wrap`) | a rumor signed by the group, sealed to the NIP-4E key | the person's policy, signed by the quorum |
| Key ceremony rounds | `GiftWrapPayload` rows from `ChillDkgRitualManager` | DKG rounds | protocol floor |
| Signing rounds | `GiftWrapPayload` rows from `FrostSigningManager` | FROST proposal, nonce, signer set, partials, signature, failure, decline | protocol floor |
| Confederation statements | `GiftWrapPayload` rows from `ConfederationRitualManager` | statements, which already carry an `expiration` of their own | the proposal's own expiry |
| The Marmot Welcome | `MarmotOutboundDao.deliveryWelcome` → `GiftWrapPayload` | kind 444 | protocol floor |

Three other facts matter here:

- `sealGiftWrapPayload` seals a copy to **every** participant, including the sender, so a second
  device of the sender's can read their own messages. NIP-17 suggests leaving out the sender's
  copy as one way to make messages disappear. This plan keeps the copy (D5).
- **Ceremonies read their own rounds back out of the payload table.** In a NIP-17 room,
  `ChillDkgRitualManager` (around line 610), `ConfederationRitualManager` (around line 603) and
  `FrostSigningManager` (around line 869) rebuild a session from the `GiftWrapPayload` rows
  stored for it. `FrostSigningManager.isQueued` (around line 3241) uses the same table to avoid
  queueing a round twice. Deleting a protocol payload is therefore not like deleting a chat
  line. It is what D6 and Phase 5 deal with.
- The only NIP-17 relay is `Relays.DefaultDMRelayList = [wss://ephemeral.mantra.press]`. Sending
  to the recipient's kind 10050 relays is written in `persistAndBroadcastGiftWrap` but commented
  out. Whether the relay drops anything depends on that one relay (D8).

Marmot (MLS) rooms are not NIP-17 and are out of scope. Their messages travel as kind 445, which
already carries the group's own retention (`MlsGroup.retentionSeconds`,
`MarmotInnerEvent.disappearingExpiration`). The retention applies to *every* inner kind there,
ceremony rounds included. That is the precedent this plan follows for NIP-17 rooms. A Marmot
direct message is a NIP-59 wrap that never reaches a relay. See D11.

## Decisions

### D1. The sender decides, and the setting is about the messages *you* send

NIP-17 has no shared conversation timer. Each message carries its sender's choice, so the setting
is worded as **"Messages you send disappear after…"** everywhere. It is never worded as "this
chat disappears". Two Giza users who both keep the default will see the whole conversation
disappear after a day. A peer who chooses a week makes their own messages last a week. A peer on
a client without expirations sends messages that never expire, and Giza does not invent an
expiry for them.

A local "also delete what I receive after…" retention is a separate feature. It is listed under
[Out of scope](#out-of-scope) so it does not get mixed in here.

### D2. Everything expires, on one of two clocks

Every event that goes into a NIP-17 wrap gets an expiration. There is no allowlist and no
exception. What differs is how long it lives:

- **What a person writes** (kinds 14, 15, 7, and 5 when it deletes one of these) expires after the
  person's policy (D3, D4). The policy can be Off, and Off means these messages carry no
  expiration.
- **Protocol messages** expire after `max(policy, floor)`, where each protocol has its own floor.
  They **always** expire. Off turns off expiry for what a person writes, not for ceremonies. A
  ceremony round that lived forever on a relay would be the one exception to "everything
  expires", and the point of this revision is that there are none.

The floors start from each protocol's own timeline. Phase 0 checks them against the code:

| Protocol | Kinds | Floor | Why |
|---|---|---|---|
| FROST signing | 30320–30325, 30330 | 1 day | `frost-session-resend.md` already handles a round that expired off a relay. The proposer resends, and a fresh event carries a fresh expiry. The floor only stops a 1-hour setting from making every signing a race |
| Key ceremonies (DKG, inbox key, share refresh) | each ritual's `ALL` set | 7 days | A ceremony waits for every admin, and an admin can be away for days. There is no resend for a DKG round |
| Confederation statements | the confederation statement kinds | the proposal's `expiresAt` (at most 30 days, `ConfederationKinds.MAX_EXPIRY_SECONDS`) | The rumor already carries `["expiration", expiresAt]`, meaning the proposal lapses then. That tag is already NIP-40-shaped, so it *is* the rumor's expiration. Giza does not add a second one (D5) |
| Marmot Welcome | 444 | 7 days | The invitee holds no group state yet and may not open the app for days |
| Anything not listed | any other kind | 7 days | A new kind still expires, and a test fails until someone classifies it on purpose (Phase 1) |

`DisappearingMessages.classOf(kind)` returns `Written` or `Protocol(floor)`. Classifying a new
kind is a one-line change with a reason. Forgetting to classify it gives it the safe default of
seven days, not "never".

**Expiring on the relay is not the same as deleting on the device.** A protocol payload's rows
stay on the device until its session has finished *and* its expiry has passed (D6). The keys,
shares, signatures and session records a ceremony produces are not messages. They do not expire,
because they are the result the ceremony existed to produce.

### D3. One day by default, presets, and a bounded custom length

The presets are **Off, 1 hour, 8 hours, 1 day (default), 1 week, 4 weeks**, plus **Custom**,
which takes a number and a unit (minutes, hours, days). Custom allows **5 minutes to 365 days**.

- The 5-minute floor covers clock skew between two phones and the time it takes a message to be
  delivered while the notary is catching up. Below that, a message can arrive already expired
  and be dropped (D6).
- The 365-day ceiling keeps the value inside a range that relays and other clients handle without
  surprises, and still lets "a long time" be a real choice.

Values are stored as seconds. `0` means Off and `null` means "follow the default" (D4).

### D4. A default for the identity, and an override per conversation

- **The identity's default** lives in the aux database, keyed by `userPublicKey`. This is the same
  place and shape as `NotificationLedger`, which `NotificationSettingsStore` already reads and
  writes. A new identity starts at 86 400 seconds. So does an existing identity when the
  migration runs (D9).
- **A conversation's override** is a nullable `ChatRoom.disappearingMessageSecs`. `null` follows
  the default, `0` is Off for this conversation only, and any other value is that many seconds.
  Only NIP-17 rooms show the override (`mlsGroupState == null`). A quorum's or confederation's
  NIP-17 room has an override like any other room, and its protocol messages read their policy
  from it.

The expiry for a message is worked out once, when it is queued:
`DisappearingMessages.expiryFor(kind, createdAt, policy = room.disappearingMessageSecs ?: identityDefault)`.
The result is written into the payload's tags. Changing the setting later never changes a message
that has already been sent. It cannot, because the tag is part of the rumor's id.

### D5. Which layers get a timestamp, and which timestamp

- **Rumor**: `["expiration", expiry]`, worked out from the rumor's real `created_at` (D2). Giza
  obeys this value, on both sides. A rumor that already carries an `expiration` keeps it and gets
  no second one. Today that is only a confederation statement. A second tag would get the
  statement refused: `ConfederationStatements` reads its tags with `singleOrNull`, and two
  `expiration` tags read as none.
- **Wrap**: `["expiration", wrap.created_at + (rumorExpiration - rumor.created_at) + 2 days]`.
  This is quartz's own formula (`GiftWrapEvent.build`'s `expirationDelta`). The wrap's
  `created_at` is already pushed up to two days into the past so that a relay cannot see when a
  message was really sent, and the formula carries that same random offset into the expiry. The
  result is the rumor's expiry plus a random `[0, 2 days]`. A wrap expiry of exactly
  `created_at + 86400` would give the send time away again. The offset only ever moves the expiry
  *later*, so a relay never drops a wrap before the sender meant it to. The cost is that the
  ciphertext can sit on the relay for up to two extra days. When the rumor has no expiration (a
  person's message with the policy Off), the wrap has none either.
- **Seal**: no tag. Phase 0 found that quartz does tag the seal. Giza still does not, for three
  reasons. NIP-59 says seal tags MUST be empty. Giza's own `GroupDmRefusal` refuses a group's seal
  with any tag, and `InboxRefusal` refuses an inbox seal with more than one. And quartz's
  `Rumor.mergeWith`, which Giza's receive path uses, appends the seal's tags to the rumor's. So a
  tagged seal would put a second `expiration` on every rumor Giza receives.
- **The sender's own copy** is kept and given the same rumor expiry. A second device of the
  sender's can then read the sender's half of the conversation for as long as the recipient can.
  It also lets that device join a ceremony that a first device started. NIP-17's other option,
  sending no self-copy, would silently break both.

The randomness comes from the wrap's own `created_at`, so tests control it by passing that value
in.

### D6. Deleting locally is the feature, and protocol payloads wait for their session

The relay part is best effort. What a person actually experiences is their own device deleting
the message, so:

- **What a person wrote is deleted at the rumor's expiration, on both sides.** The deletion is a
  hard delete of the `ChatMessage` row and everything that holds its words: the
  `GiftWrapPayload`, the `GiftWrapSeal`, the wrap's `NostrEvent` and `GiftWrapMessage`, the
  broadcast-request relation rows, and any notification-ledger entry that stores message text. A
  soft delete would leave the words on disk, which defeats the point.
- **A protocol payload is deleted at the later of its expiration and its release.** "Released"
  means the session it belongs to has finished: completed, failed or abandoned. A
  `PayloadRetention` hook per protocol answers that question (Phase 5). Until then the rows stay,
  because the manager rebuilds the session from them. When the payload goes, any chat line it
  rendered ("Alice proposed signing…") goes with it. The session's own record, and whatever the
  session produced, stays.
- **A message that has already expired when it arrives is not filed and does not notify.** This
  is NIP-40's "ignore expired events". It can happen because the wrap outlives the rumor on the
  relay (D5), and because re-syncs happen. An expired protocol round that arrives late is ignored
  the same way. Its session either had what it needed already, or a resend brings a fresh copy.
- **A wrap that has been deleted is not decrypted again.** A small
  `ExpiredGiftWrap(id, dropAfter)` tombstone remembers wraps that were already handled until the
  wrap's own expiration has passed. Without it, every sync would download the jittered wrap
  again, decrypt it, find it expired, and throw it away. The outbound side needs no tombstone.
  `FrostSigningManager.isQueued` only stops a re-published round from overwriting a row that is
  still stored, and once a swept row has gone there is nothing for it to overwrite.
- **Expirations from other people are obeyed whatever this device's own setting is.** A message
  that a Bennu user sent with a one-hour expiry disappears after an hour.
- **A reply that quotes a deleted message** shows "This message has disappeared" in place of the
  quote. It never shows the quoted text, which would be a second copy.
- **An OS notification** showing a message's text is cancelled when that message is deleted.

### D7. Honest about what it is

The settings screen and the conversation say what the feature does, and nothing more:

> Messages you send are deleted from your devices and from the recipient's Giza after this time,
> and relays are asked to delete them too. Other apps, screenshots and relays that ignore the
> request can keep a copy.

And, under the list of lengths:

> Signing and key-ceremony messages always expire. They are kept for at least a day, or a week
> for key ceremonies, and are not deleted from this device until the ceremony they belong to has
> finished.

The description also says what happens if the recipient is offline for longer than the chosen
time: the relay may drop the message before the recipient ever downloads it. That consequence is
real, and the default makes it more likely, so people should be told about it.

### D8. The relay has to support NIP-40, and Phase 0 checks that it does

NIP-40 says clients "SHOULD NOT send expiration events to relays that do not support this NIP".
Giza sends every NIP-17 wrap to `ephemeral.mantra.press`. Phase 0 reads that relay's NIP-11
document. If `supported_nips` includes 40, nothing more is needed. If it does not, the fix is to
add support on the relay, or to add a second DM relay that supports it. The fix is **not** to
leave the tag off: the local deletion still works without relay support, and it is the larger
part of the feature. Turning sending to the recipient's 10050 relays back on is outside this
plan's scope. Phase 0 records the finding so that whoever re-enables it filters by
`supported_nips`.

### D9. Existing users get the default from the next message they send

The migration gives every existing identity the one-day default. Messages and rounds sent before
the upgrade stay as they were: the policy applies to what is sent after it and is never applied
retroactively. A ceremony that is open during the upgrade keeps running. Its earlier rounds have
no expiry, its later ones do, and the session does not care which is which. The first time a
person opens a NIP-17 conversation after the upgrade, a one-time notice says *"New messages you
send here disappear after 1 day"*, with an action that opens the setting. Without that notice, a
person's own words would start disappearing when they had changed nothing.

### D10. When a group sends, the expiry is part of what its quorum signs

For the two group paths, the rumor goes inside a seal that the group signs in a FROST round. That
puts the rumor's expiration inside what the admins sign:

- The proposer's device sets the rumor expiration when it proposes, from the room's override or
  the proposer's default. The signing review screen shows it, for example "Disappears 1 day after
  it is sent", so every signer approves the expiry along with the words.
- A signer's device checks that the expiration is within D3's bounds. If it is not, the device
  refuses the proposal out loud. It does not sign a message that would expire unreasonably early
  or late. A message the group sends with no expiration is also refused, unless the proposer's
  policy is Off.
- The device that wraps the message adds the wrap expiration with jitter (D5), as on the
  one-to-one path.
- Both paths stay behind their existing flags (`groupDirectMessages`, `groupInbox`).

### D11. MLS rooms are not covered, and the screen says so

A group's Marmot room already has a group-wide retention that every member's client applies.
That is a different model: one value the group agrees on, set through a commit. The settings
screen states that the setting covers direct messages and quorum and confederation
conversations, and that a group's own room follows the group's setting. Letting a person set the
group's retention from Giza is a natural next step. It is a separate plan, because it is an MLS
proposal, not a tag.

### D12. You can see a message is going to disappear

- The app bar of a NIP-17 conversation shows an hourglass and the current length ("1 day") when
  a policy is in force. Tapping it opens the conversation's setting.
- An expiring message has a small hourglass next to its time. Its `contentDescription` gives the
  time left, because the icon carries information the text beside it does not.
- When an incoming message's expiry differs from the conversation's setting (D1), nothing extra
  is shown. The hourglass already says that the message will go.
- Protocol lines get no hourglass. They always expire, so a marker on every one would carry no
  information.

## Phases

### Phase 0: this document, and nine checks

1. **quartz.** Does quartz's NIP-17 builder (`NIP17Factory` or its successor) put `expiration` on
   the seal, the wrap, or both? Does `SealEvent` reject a seal with tags? This decides the seal
   question in D5.
2. **The relay.** Fetch `ephemeral.mantra.press`'s NIP-11 document and look for 40 in
   `supported_nips`. Also publish an already-expired test event and confirm the relay refuses it
   (D8).
3. **Bennu.** Check that a Bennu build accepts an `expiration` tag on a kind 14 rumor **and on
   every protocol rumor**. A Bennu device that refuses a DKG round it does not recognise would
   stall every mixed Giza–Bennu ceremony. This needs a test, not an assumption.
4. **The kinds.** List every `kind` written to `GiftWrapPayload` today, from the code and from a
   real database. Each one gets a class in D2's table.
5. **The floors.** For each protocol in D2's table, find how long a real session stays open: from
   the code's timeouts and from the device runs in the protocol's own document. Raise a floor
   that is too short.
6. **Readers of the payload table.** Confirm that `ChillDkgRitualManager`,
   `ConfederationRitualManager` and `FrostSigningManager` are the only code that reads protocol
   payloads back from `GiftWrapPayload`. For each one, find the session state that says
   "finished". For confederation statements, find out whether the confederation's status is
   *derived from the statements each time it is read*. If it is, deleting them erases a formed
   confederation, and Phase 5 has to store the status first.
7. **Where words are stored.** Check the notification ledger, any search or full-text index,
   draft storage, reply previews and the logs (`sealGiftWrapPayload` logs the rumor JSON at debug
   level). Each place found gets a deletion step in Phase 4, or the log line is removed.
8. **Participants.** Confirm that every NIP-17 room includes the local user as a participant, so
   that the sender's copy really is sealed (D5).
9. **The Welcome.** Read MIP-02 for anything it says about a Welcome's lifetime or expiration, and
   adjust its floor to match.

Done when: each check's answer is written under this phase, and D2, D5 and D8 are updated if an
answer changes them.

**Done, 2026-11-22.** The checks were run against quartz 2.20.1 and marmot-kmp 2.3.0 from the
Magpie mirror, against the relay itself, and against Bennu's `bennu-kmp` at `2db6dde5`.

1. **quartz** tags all three layers. The rumor carries the expiry. `SealEvent.build` and
   `GiftWrapEvent.build` each add `created_at + delta + 2 days`, where `delta` is the rumor's
   expiry minus its `created_at`. `SealEvent` does not refuse a tagged seal. `Rumor.mergeWith`
   appends the seal's tags to the rumor's when it unseals, and `GiftWrapSeal.decryptGiftWrapPayload`
   unseals that way. D5 now uses quartz's wrap formula and keeps the seal empty, for the reasons
   given there.
2. **The relay.** `ephemeral.mantra.press` lists 40 in `supported_nips`, so D8 needs nothing
   more. It also describes itself as `nak serve`, "a local relay for testing, debugging and
   development". Phase 8's relay check should keep that in mind.
3. **Bennu** reads rumor tags by name. It has no check on the number of tags, or on unknown tags,
   anywhere in `composeApp`. An extra `expiration` on the rumor is safe there. Bennu has no group
   DM or inbox refusals, so the seal question does not reach it.
4. **The kinds.** Five producers write `GiftWrapPayload`: `sendChatMessage` (14, plus whatever
   `messageType` a caller passes), `ChillDkgRitualManager` (its ritual kinds),
   `FrostSigningManager` (the signing kinds, and any kind its queue is handed),
   `ConfederationRitualManager` (the confederation DKG kinds) and `MarmotOutboundDao.deliveryWelcome`
   (444). Every one of them hashes the rumor id from its tags itself, so each one has to stamp
   before it hashes (Phase 3).
5. **The floors.** No ceremony has a timeout in code. A DKG, signing or confederation ceremony stays
   open until it completes, fails or is abandoned. The floors in D2 stand.
6. **Readers of the payload table.** `ChillDkgRitualManager` (around line 610),
   `ConfederationRitualManager` (around line 603) and `FrostSigningManager` (around line 869) are
   the only readers. `ShareRefreshManager`, `InboxKeyRitualManager`, `ConfederationSigningManager`
   and `ChronicleManager` read `MarmotInnerEvent` only, which is out of scope. Confederation
   *statements* do not travel on this path at all. They go through the group inbox
   (`GroupInboxManager`, `InboxReplies`), so the question of the confederation's stored status
   moves to Phase 7.
7. **Where words are stored.** `NotificationLedger` stores a cursor and switches, but no text.
   There is no full-text index and no draft table. `ChatMessage` has foreign keys to
   `GiftWrapPayload` with `ON DELETE CASCADE`, so deleting a payload deletes its chat line. The
   debug log of the rumor JSON in `sealGiftWrapPayload` is real, and Phase 3 removes it.
8. **Participants.** `createNip17ChatRoom` puts the local user in the room, and
   `persistInboundChatMessage` already discards "our own message coming back". The self-copy is
   sealed.
9. **The Welcome.** marmot-kmp's own `WelcomeGiftWrap` sets no expiration, and its sources say
   nothing about a Welcome's lifetime. The seven-day floor stands.

### Phase 1: pin today's behaviour, and the pure core

- Tests that pin today: a payload sealed by `sealGiftWrapPayload` has no `expiration` on any
  layer, and a confederation statement's existing `expiration` tag comes through unchanged.
- New `nostr/DisappearingMessages.kt`, pure functions only, with no database (the same pattern as
  `MarmotDirectMessage`):
  - `MIN_SECONDS`, `MAX_SECONDS`, `DEFAULT_SECONDS = 86_400`, `PRESETS`
  - `classOf(kind): ExpiryClass`, which is `Written` or `Protocol(floorSeconds)`, with
    `UNCLASSIFIED_FLOOR = 7 days`
  - `policyFor(roomOverride: Long?, identityDefault: Long): Long?`
  - `expiryFor(kind, createdAt, policy, existingTags): Long?`. It is null only for a `Written`
    kind with the policy Off, and it returns the existing tag's value when there is one (D5).
  - `wrapExpiration(rumorExpiration, rumorCreatedAt, wrapCreatedAt): Long`
  - `expirationOf(tags): Long?`
- `commonTest`:
  - **Every kind the app writes to the queue is classified explicitly.** The test lists the
    ritual `ALL` sets, the FROST kinds, the confederation kinds and 444, and fails on any kind
    that falls through to `UNCLASSIFIED_FLOOR`. This replaces the allowlist test from the first
    draft.
  - Protocol kinds expire even with the policy Off, at their floor.
  - The bounds, Off versus `null`, the jitter staying within `[0, 2 days]` and never moving
    earlier, and a malformed tag (non-numeric, or before `created_at`) parsing as "no
    expiration".

### Phase 2: storage

Migration 49 → 50, following the pattern in `database/migrations/`:

- `ChatRoom.disappearingMessageSecs: Long?`
- `ChatMessage.expiresAt: Instant?`, with an index so the sweeper's "next to expire" query is
  cheap
- `GiftWrapPayload.expiresAt: Instant?`, so the sweeper can find protocol payloads without parsing
  tags
- New table `ExpiredGiftWrap(id PRIMARY KEY, dropAfter)`, for wrap ids inbound and payload ids
  outbound (D6)
- In the aux database: the identity default, stored next to (or as a column of)
  `NotificationLedger`, with existing rows set to 86 400 (D9). A `DisappearingSettingsStore` has
  the same shape as `NotificationSettingsStore`.
- `ChatRoom.disappearingNoticeShownAt: Instant?` for D9's one-time notice

Tests: a migration JVM test in the style of `ReadMarkMigrationJvmTest`, run against a version-49
database with NIP-17 and MLS rooms in it.

**Built.** `AutoMigration(from = 49, to = 50)`, with no spec, because nothing moves. It differs from
the plan in two ways:

- The identity's default is a table of its own, `DisappearingSetting(userPublicKey, defaultSeconds)`,
  not a column of `NotificationLedger`. A ledger row exists only once notifications are armed, and
  a policy has to apply before that. A missing row reads as one day, so D9's upgrade needs no data
  step.
- `ExpiredGiftWrap` holds inbound wrap ids only (D6 dropped the outbound tombstone).
- `GiftWrapMessage` gains `giftWrapPayloadId` and `expiresAt`, which the plan did not have. The
  cascade runs wrap → seal → payload → chat line, so deleting a wrap early would delete an
  unfinished ceremony's round with it. The sender's one-per-recipient wraps also had no link to
  their payload at all, only the first seal did. The sweeper needs both columns (Phase 4).
- `GiftWrapPayload.expiresAt` defaults to the value read off the row's own tags. Every producer
  and the inbound path therefore record it without being changed.

`DisappearingDao` and `DisappearingSettingsStore` sit beside them. `DisappearingMessagesMigrationJvmTest`
covers the upgrade, the store and the tombstones, and `ShippedProfileUpgradeJvmTest` now expects
version 50.

**Found while building, and Phase 4 has to handle it:** `ChatRoom.initialGiftWrapPayloadId` is a
foreign key to `GiftWrapPayload` with `ON DELETE CASCADE`. `NostrDao` sets it to the payload that
first created a NIP-17 room on this device. Deleting that payload would delete the whole
conversation. The sweeper clears the reference before it deletes the payload.

### Phase 3: sending, for every kind

- **One place applies the policy.** A single `DisappearingMessages.stamp(payload, room,
  identityDefault)` runs before *every* `GiftWrapPayload` is written. It appends the rumor
  expiration to the tags, **before** the payload id is hashed, so the payload id is the id of the
  rumor the recipient will see. It also sets `GiftWrapPayload.expiresAt`. The callers are
  `sendChatMessage`, `ChillDkgRitualManager`, `FrostSigningManager`,
  `ConfederationRitualManager` and `MarmotOutboundDao.deliveryWelcome`. Each of them hashes its
  own id today, so each one moves to calling `stamp` first. A JVM test inserts a payload through
  each caller and asserts that it has an expiration. That test is what stops a sixth caller from
  skipping it.
- `sendChatMessage` also writes `ChatMessage.expiresAt`.
- `sealGiftWrapPayload` reads `expirationOf(giftWrapPayload.tags)`. When it finds one, it adds the
  jittered expiration to the wrap's tags. The seal is left alone (D5, after Phase 0). The rumor
  needs no change, because `RumorAssembler` already copies the payload's tags.
- `persistAndBroadcastGiftWrap` sets `NostrEvent.expiresAt` from the wrap. The existing
  `NostrEventDao` predicate (`expiresAt IS NULL OR expiresAt > :now`) then covers it with no
  further change.
- **A message whose rumor expired before it was sent** (the device was offline for longer than
  the policy) is not sealed and not broadcast. For a person's message, the sweeper deletes its
  chat message like any other, and a snackbar tells the person: *"1 message expired before it
  could be sent"*. For a protocol round, the round is dropped and its manager is told, so the
  session can re-propose or fail out loud rather than wait for nothing. Sending either one late
  would publish something its own tag says should already be gone.
- The `sealGiftWrapPayload` debug log of the rumor JSON is removed (Phase 0, check 7).

Tests (JVM, in `NostrNip17DaoJvmTest` or a new `DisappearingSendJvmTest`): every combination of
room override and default; protocol kinds at their floor, including with the policy Off; a
confederation statement keeping its single tag; the payload id equal to the rumor's id; wrap and
rumor expirations as D5 describes; the self-copy carrying the same rumor expiry; an expired
queued payload never reaching `BroadcastNostrEventRequest`.

### Phase 4: receiving, and the sweeper

- `GiftWrapSeal.decryptGiftWrapPayload` carries the rumor's tags through, as it already does. It
  sets `GiftWrapPayload.expiresAt`, and `persistInboundChatMessage` sets `ChatMessage.expiresAt`.
- On arrival: if the rumor has already expired, nothing is filed, nothing is notified, no protocol
  manager is handed it, and an `ExpiredGiftWrap` tombstone is written. The NIP-17 receive path
  checks the tombstone before it decrypts.
- The notification classifier skips expired messages, and `NotificationDispatcher` cancels the
  OS notification when a message is deleted.
- New `managers/DisappearingMessageSweeper`:
  - `sweep(now)` hard-deletes, in one transaction, every expired written message (D6's list) and
    every expired protocol payload that its `PayloadRetention` hook has released (Phase 5). It
    returns what it deleted.
  - It runs at app start, on returning to the foreground (`AppLifecycle`), in `BackgroundSyncPass`,
    and from a timer set for the next `expiresAt` while the app is open.
  - It prunes tombstones past their `dropAfter`.
  - Until Phase 5 lands, protocol payloads are never released. The sweeper skips them, so
    shipping Phase 4 alone deletes nothing a ceremony needs.
- Reply previews and quoting read through a lookup that turns a missing message into "This
  message has disappeared".

Tests: a JVM test of the sweeper for each row type; inbound drop and tombstone; a re-sync of a
tombstoned wrap not decrypting it; another sender's one-hour expiry being obeyed while this
device's setting is Off; the notification not being raised; a protocol payload past its expiry
but not released surviving a sweep. Add a case to `NotificationClassifierJvmTest`.

### Phase 5: protocol payloads, released when their session is done

- A `PayloadRetention` interface with `isReleased(database, payload): Boolean`, implemented by
  each protocol that reads payloads back (Phase 0, check 6):
  - **`ChillDkgRitualManager`**: released when the DKG session has completed or failed.
  - **`FrostSigningManager`**: released when the signing session has a signature, a failure, or
    has been abandoned.
  - **`ConfederationRitualManager`**: released when the confederation's key ceremony has
    completed, failed or been abandoned. Its statements are not on this path (Phase 0, check 6).
  - **The Welcome**: released once the payload is sealed. Nothing reads a Welcome back.
  - **Any other kind**: released when its expiry passes, which is the same rule as a written
    message.
- The chat lines that protocol payloads render are deleted with them.
- The sweeper asks the hook for each expired protocol payload, and deletes it once the hook says
  it is released.
- Tests: for each protocol, an open session keeps its expired payloads, and a finished one loses
  them. A DKG run with the policy at its minimum still completes when one admin is offline for
  six days. A signing session whose proposal expired off the relay is completed by a resend
  (`frost-session-resend.md`). A formed confederation still reads as formed after its statements
  have been swept. In `ChillDkg…JvmTest`, `CeremonyFollowThroughJvmTest`,
  `ConfederationCeremonyJvmTest` and `ConfederationStatusTest`.

### Phase 6: the screens

Every point in CLAUDE.md applies: strings in `strings.xml` written in sentence case, spacing from
`MaterialTheme.spacing`, 48dp targets, icon descriptions decided, `ScreenStateTransition`,
`readableContent()`, and `NavigateBackButton` on pushed screens.

- **Settings → "Disappearing messages"**: a new pushed screen and route. It has a radio list of
  D3's presets and "Custom…", which opens a dialog with a number field and a unit selector,
  checked against the bounds, with an inline error. Below the list are D7's two paragraphs and
  D11's note about groups. Saving reports through `rememberNotifier`.
- **Conversation detail** (`ChatRoomDetailScreen`, NIP-17 rooms only): a "Disappearing messages"
  row that shows "Default (1 day)", "Off" or a length, and opens the same picker with an extra
  "Use default" option at the top.
- **Conversation**: the app-bar hourglass (D12), the per-message hourglass, the one-time notice
  (D9), and the "disappeared" reply placeholder.
- Run `./gradlew :composeApp:m3Audit` with all budgets at zero.

Tests: view-model JVM tests for the picker's validation and for override versus default, plus a
string-catalogue entry for every format string this adds.

### Phase 7: when a group sends (D10)

- `GroupDmSender` and `InboxReplies`: put the rumor expiration in at proposal time, show it on
  the signing review, refuse out loud a proposal outside the bounds or missing its expiry, and
  add the jittered wrap expiration when wrapping.
- The group's receive paths (`GroupDmReceiver` and the inbox's) obey the rumor expiry and use the
  same sweeper.
- Tests in `GroupDmTodayJvmTest`, `InboxReplyJvmTest` and `FrostSigningDeclineJvmTest`, for the
  out-of-bounds and missing-expiry refusals.

All of this stays behind `groupDirectMessages` and `groupInbox`.

### Phase 8: the record, and devices

A device run, written up under this phase:

1. Giza ↔ Giza with the default: both sides delete the message after a day. Use a shortened custom
   length for the run, then repeat once with the real default.
2. Giza ↔ Bennu: Bennu files the message, and Giza obeys an expiry that Bennu sends.
3. Giza ↔ another NIP-17 client: the message is still readable there, and Giza obeys the other
   client's expiry.
4. After the wrap's expiration, query the relay directly and confirm it no longer serves the wrap.
5. A second device of the sender: the sender's half is readable there until the expiry.
6. Offline longer than the policy, both as sender and as recipient (the Phase 3 snackbar, and
   D7's warning shown to be accurate).
7. A quorum DKG and a signing with the policy at 1 hour and one admin offline overnight. Both
   finish, the signing through a resend. Afterwards the relay no longer serves their rounds, and
   the device keeps the shares and the signature.
8. A mixed Giza–Bennu quorum running a signing, with expiring rounds.

Then update the status line at the top of this document, note under each phase what was built and
where it differs from the plan, and add a short CLAUDE.md rule: *every NIP-17 payload goes
through `DisappearingMessages.stamp`, and a new kind gets a class in `classOf` with the reason for
its floor.*

## What this does not do

- It does not stop a recipient, another client or a relay from keeping a copy (D7).
- It does not change messages or rounds that were sent before it shipped (D9).
- It does not apply to Marmot rooms or Marmot direct messages (D11).
- It does not expire what a ceremony produces (keys, shares, signatures, session records). Only
  the messages that carried the ceremony expire (D2).

## Risks

- **A ceremony that loses a round.** Protocol messages now leave the relay. A key ceremony that
  outlasts its seven-day floor, or a signing whose resend never happens, ends up waiting for a
  round that no longer exists. The floors (D2), Phase 0's fifth check and device-run step 7 are
  the guard. Where there is no resend, as with DKG, the ceremony has to fail out loud when its
  rounds have expired, never stall quietly.
- **Deleting state that is really a record.** If anything derives lasting state from protocol
  payloads each time it is read, sweeping them erases that state. Confederation status is the
  known suspect. Phase 0's sixth check finds the others, and Phase 5 does not release a protocol
  until its result is stored.
- **Bennu refusing what it now receives.** Every protocol rumor gains a tag. A peer that validates
  tags strictly could stop taking part in mixed groups. Phase 0's third check and device-run step
  8 cover this.
- **Messages the recipient never receives.** A recipient who stays offline longer than the policy
  may never get the message. The default makes this a normal case rather than a rare one. D7 tells
  people. Phase 8 measures how often it happens on a real relay.
- **A wrong clock.** A sender whose clock is behind sends messages that arrive already expired, and
  D6 drops them. The 5-minute floor (D3) narrows this. It does not remove it. Log a dropped
  message's lateness, so that a pattern can be seen.
- **A deletion that misses a copy.** Words stored somewhere the sweeper does not know about would
  survive. Phase 0's seventh check, and a sweeper test for each table, guard against it. A new
  table that stores message text should go through the sweeper's review.

## Out of scope

- A local "delete what I receive after…" retention (D1).
- Setting a Marmot group's retention from Giza (D11).
- Sending to the recipient's kind 10050 relays (D8).
- A resend for DKG rounds. If Phase 8 shows that the seven-day floor is not enough, that resend is
  the follow-up, not a longer floor.

## Order and size

| Phase | Depends on | Size |
|---|---|---|
| 0 Checks | none | small |
| 1 Pin and pure core | 0 | small |
| 2 Storage | 1 | small |
| 3 Sending, every kind | 2 | medium |
| 4 Receiving and sweeper | 2 | medium |
| 5 Protocol release | 3, 4 | medium to large, depending on Phase 0's sixth check, and the largest risk |
| 6 Screens | 2 (3 and 4 to be useful) | medium |
| 7 Group sends | 3, 4 | medium |
| 8 Record and devices | all | small |

Phases 3 and 4 can be built in parallel once Phase 2 has landed. Phase 6 can start on Phase 2's
store at the same time. Phase 3 can ship before Phase 5: protocol rounds then expire on the relay
but are never swept on the device, which is safe. The reverse order is not.

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew check
```
