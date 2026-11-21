# The group's nostr profile and posts

A plan to bring back two things a group could do in Bennu and still can in Curare:

1. **Say who it is on nostr.** A kind:0, a name, a picture, a line about what the group is
   for, authored by the group's key rather than by the member who typed it.
2. **Post as itself.** A kind:1 signed by the group's key, which any nostr reader can check
   came from the group and not from whoever proposed it.

Both are proposed by one member and signed by a quorum through the FROST signing session
the app already runs. Both then reach the rest of nostr only when a member broadcasts the
signed event to relays. Signing publishes nothing, and that stays true here.

Read [shared-key-derivation.md](./shared-key-derivation.md) first: a room's id *is* the
pubkey it signs as, which is the only reason a group has a nostr identity at all. Then
read [member-chronicle.md](./member-chronicle.md), for why a group-signed event is not a
`NostrEvent`, and [giza-wallet-removal.md](./giza-wallet-removal.md), for the fail-closed
rule that D5 extends.

**Not built.** Written against `d0437d6c`, on 2026-11-21. The reference implementation is
Curare at `dc1895ee` (`databending/curated-kmp`, package `to.curare.compose`). Below,
paths starting `K/` are under `composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/`, and
paths starting `C/` are the same tree in Curare under `to/curare/compose/`.

---

## Where this came from, and why it is a port and not a revert

Bennu had these features. Commit `b21f4c4d` ("remove the group's nostr profile, relays and
posts", 2026-10-23) took them out, along with the group's relay lists, the broadcast
screen and the paste-an-event screen: 73 files, about 10,000 lines and 93 strings. Giza
forked from Bennu after that, so Giza never had them as Giza.

Curare forked from Bennu before the removal and kept all of it. Its feature files are
almost line for line what Bennu deleted. Apart from the package name, the differences are
Curare's curated-lists kinds, which Giza does not have.

`git revert b21f4c4d` is still the wrong tool. 108 commits have landed on top of it,
and they reshaped every file the revert would touch:

| what moved since `b21f4c4d` | what it means for this port |
|---|---|
| the rebrand (`com/machankura/dedun` → `mobi/giza`, `DedunNavHost` → `DedunNavHost`) | every path and import changes, and `brand-audit.sh` fails on a stray Bennu name |
| wallet removal | `SignedWorkRow` and `GroupSignedWorkRowsJvmTest` went with the wallet row (`8e0f1bd3`). The detail screen has no row helper to put these rows back into |
| member-admin-promotion, robust groups, rotations | `ChatRoomDetailScreen` is now 1,931 lines and its sections are different. `canSign` is computed once in the view model "for the two things that need it" |
| M3 conformance | each ported screen has to meet `m3Audit`'s budgets as it lands, not afterwards |
| quartz 1.15.2 → 2.20.1 | Curare is still on 1.15.2. The calls the port needs still exist on 2.20.1 (`MetadataEvent.createNew` / `updateFromPast` / `contactMetaData`, `UserMetadata.anyName`, `TextNoteEvent.build` / `isNewThread`). `updateFromPast` now also mirrors fields into tags, so Curare's template tests will not pass unchanged. `ChatMessageRelayListEvent` is now `DmRelayListEvent`, which matters only if relay lists are ever ported |

So the port copies Curare's files and changes each one to fit Giza as it is now. Use
`git show b21f4c4d^:<path>` in this repository to see Bennu's last copy of a file when a
Giza-specific line needs explaining. It is usually closer to Giza than Curare's copy is.

---

## What Giza does today with these kinds

These are traced through the current code and confirmed. Phase 1 pins them as tests
before anything changes.

| event | where it lands | what happens |
|---|---|---|
| a peer (Curare, or an old Bennu build) proposes a kind:0 for the group to sign | `FrostSigningManager.acceptProposal` (`K/managers/FrostSigningManager.kt:651-726`) | nothing declines it: `WalletRefusal` matches only retired wallet kinds and wallet-tagged certificates. It is filed as a session and the member is asked to approve "**Event of kind 0**", with the raw metadata JSON as the detail (`K/text/ProposedEvent.kt:95`). The member can approve it |
| a peer proposes a kind:1 | same | asked to approve "**Event of kind 1**", with the note as the detail. It can be approved |
| that kind:0 session completes | `complete` → `recordSignedEvents` → `applySignedEvent` → `ChatMessage.applyInnerEvent` (`K/database/model/ChatMessage.kt:1292-1302`) | a `GroupSignedEvent` row is written. The transcript gets **nothing**: the `MetadataEvent.KIND` arm returns `null` |
| that kind:1 session completes | same, falling to `else -> unsupported()` (`ChatMessage.kt:1334`) | a `GroupSignedEvent` row, and a **raw-JSON chat bubble** on every device |
| a Bennu-era `groupProfileSigned` / `groupPostSigned` row already in a transcript | `GROUP_IDENTITY_TYPES` (`ChatMessage.kt:487-503`) → `ChatTranscript.kt:371-385` | drawn as a settled system line that leads nowhere. The three type names survive as `private const val TYPE_LEGACY_GROUP_*` |
| the detail screen | `ChatRoomDetailScreen.kt` | no profile, posts or propose rows. `ChatRoomDetailWithoutWalletsJvmTest.kt:44-51` asserts their absence |

Four things already exist and need no change:

- `GroupSignedEvent`, its DAO (`getByChatRoomIdAndKind`, `getById`) and `verifies()` are
  intact (`K/database/model/GroupSignedEvent.kt:162`). Database version 42 has the table.
  **No migration is needed.** The profile and posts were always read models over these
  rows.
- `FrostSigningRepository.proposeSigning(localChatRoom, userPublicKey, kind, tags,
  content)` is still there and still generic (`K/repository/FrostSigningRepository.kt:89-95`).
  Nothing in the UI calls it today. Proposing is the proposer's approval
  (`FrostSigningManager.kt:366-367`).
- `RelaysSocketManager.publishEvent(nostrEvent, relays)` is still public
  (`K/network/relays/RelaysSocketManager.kt:119-125`). It lost only its `override` when
  the transport interface went.
- `signingPath` (`FrostSigningManager.kt:1825-1853`) still authors a derived room's events
  as the room's id, which is what `verifies()` requires.

---

## Decisions

### D1. Profile and posts only. Relay lists stay out

Curare has a third reader beside these two: the group's relay lists (kinds 10002, 10050,
10007 and 10006), with a screen and an editor. This plan leaves them out, for three
reasons:

- The request is for profile and posts.
- Neither feature needs relay lists. Curare's broadcast screen falls back to
  `Relays.eventPublishRelaySet` when the group has agreed no list, and the member can edit
  the targets before sending.
- Relay lists are the larger half: about 1,500 lines of reader, editor, screen and test,
  and their own batch-signing flow.

What a relay list would add is a better default for "where should this go". A later plan
can bring them back. Broadcast's seeding function is shaped in Phase 7 so that this
slots in later without changes elsewhere.

A Curare peer can still propose a relay list into a group Giza is in. Today Giza shows it
as "Event of kind 10002" and lets it be signed, and the signed result writes nothing.
That stays as it is. See *Out of scope*.

### D2. Port from Curare HEAD. Cross-check against Bennu's last copy

Curare's files are the source. They carry Curare's later fixes, such as the one-FAB
refactor `ca6c16be`, and that history is shared with Bennu up to `dddfa68b`. Strip every
Curare-only line listed under *What to strip*. When a Curare line and Bennu's
`b21f4c4d^` line disagree, Bennu's is usually the one that already fits Giza.

### D3. Keep Curare's names, and keep the transcript's stored strings

The class, route and screen names stay as Curare has them: `GroupNostrProfile`,
`GroupPost`, `GroupNostrProfileScreen`, `EditGroupNostrProfileScreen`, `GroupPostsScreen`,
`AddGroupPostScreen`, `BroadcastGroupSignedEventScreen`, `ProposeGroupEventScreen`.
Matching names make the next fix in either fork portable by `diff`.

Rename one thing. The UI state's `canEditNostrProfile` becomes `canProposeAsGroup`,
because it also gates posting and the paste.

The message types come back public, **with the stored strings unchanged**: `"groupProfileSigned"`
and `"groupPostSigned"`. Those strings are database values, not wire values, and rows
written under them already exist in old transcripts. Reusing them means those rows render
under the new types with no migration. `TYPE_LEGACY_GROUP_RELAYS_SIGNED` stays private
and legacy, per D1.

### D4. Who may propose: the same capability gate as Curare, plus "not disbanded"

Curare's gate is a Marmot room (`mlsGroupState != null`) where this device holds a share
of a completed key (`FrostSigningManager.canSign`). It has no admin check, by design.
Proposing costs nothing but the quorum's attention, and the quorum decides. Giza
computes exactly this `canSign` already (`ChatRoomDetailViewModel.kt:97-98`).

Giza adds two conditions Curare does not have:

- **`!isDisbanded`.** A disbanded group has ended, and a profile it signs after that
  says something false.
- **`LocalCanSign`.** An npub-only identity cannot send the proposal at all, so the
  editors are not offered to it.

Neither depends on governance. A governed room's profile or post is a signed event, not
an MLS commit, so it does not go through `GovernanceManager`.

The **read** screens (profile, posts) are open to every member of a Marmot room. So is
the broadcast button under each signed event, because the signature is already made and
no share is needed to send it.

### D5. Decline what the readers would refuse, out loud (Giza-only; recommended)

Curare checks a profile's content, and that a post is non-blank, only on the proposer's
side, in the editors and in `GroupEventProposal.read`. The signing manager accepts any
kind. Giza's rule is stricter, from
[giza-wallet-removal.md](./giza-wallet-removal.md#3-giza-and-bennu-share-a-network-and-bennu-keeps-its-wallets):
*a device that signs what it cannot check is a rubber stamp*, so it declines rather than
signs.

When a peer proposes a kind:0 whose content does not parse as a profile, or a blank
kind:1, every reader in this plan would drop the signed result. The quorum would be spent
on something nothing shows.

**Recommendation:** add `GroupStatementRefusal.of(events)`, beside `WalletRefusal` in
`acceptProposal`. It refuses exactly what `GroupEventProposal.read` refuses for kinds 0
and 1, using the same functions so the two cannot drift. It declines through the existing
`publishDecline` with a reason of its own. The decline is counted, and the proposer's
device shows it.

This is Phase 4. It is independent of the rest, and skipping it leaves Curare's behaviour.

### D6. The lightning address is neither offered nor shown, but it survives an edit

Curare's profile editor has a `lud16` field, and its profile screen shows one. Giza keeps
no wallet. A lightning address on the group's screen invites payments the app cannot
route, see or account for. It would also be the first payment affordance back in the UI
after the wallet removal.

So the editor drops the field and the screen does not show it. The template passes
`lnAddress = null`, which under `updateFromPast` means "leave it alone", rather than `""`,
which means "delete". A `lud16` that another tool, or a Curare peer, put on the group's
profile is therefore kept through a Giza edit instead of being wiped by it.
`GroupNostrProfile.lud16()` stays, so the reader still parses it.

The strings `lightning_address` and `eg_group_getalby_com` are not ported.
`wallet-audit.sh` would not catch them, since it greps only for sats and satoshis, so this
is a decision and not an audit.

### D7. Broadcast sends directly, through a seam, and never writes a `NostrEvent` row

Bennu's reasoning was in `EventPublishTransport`'s KDoc, and it still holds. A
`NostrEvent` row for a group's event would put the group's post in the home feed and in
sync reconciliation. For a kind:0 it would also make a `Profile` row for the group's
pubkey through `NostrDao.toProfile` (`K/database/dao/NostrDao.kt:444-446`). The durable
`BroadcastNostrEventRequest` queue needs such a row, so broadcast does not use it.

Instead:

- Reinstate `EventPublishTransport`, a one-method interface, as the test seam.
- Restore `override` on `RelaysSocketManager.publishEvent(nostrEvent, relays)`.
- Pass `synchronizationViewModel.relaysSocketManager` from `DedunNavHost`, as Bennu did.

The throwaway wire `NostrEvent` is built from `GroupSignedEvent.toEvent()` and never
saved.

### D8. Late members get nothing. Chronicle stays as it is

`ChronicleEvent.KINDS` is `ChronicleKinds.NONE`. Curare does not chronicle kinds 0 or 1
either. A member who joins after the group signed its profile does not hold it, and their
profile screen says the group has not described itself.

That is a known limit. Changing it is a decision about the chronicle, which
[ceremony-creation.md](./ceremony-creation.md) put in `ChronicleEvent`'s hands, and it
is out of scope here. The profile screen's empty state should not claim the group *has
never* described itself. It should say this device holds no profile for it (Phase 5).

### D9. The paste screen accepts kinds 0 and 1 only, and comes last

`ProposeGroupEventScreen` is the untyped way in: paste an unsigned event as JSON. In
Giza its `ACCEPTED_KINDS` is `{0, 1}`. That is "has a section on the group's screen",
which is Curare's own line, minus relay lists (D1) and schemas.

It is the most optional phase. Everything before it works without it.

---

## What to strip from Curare's files

| file | remove |
|---|---|
| `C/nostr/GroupEventProposal.kt` | `CuratedSchemaEvent` and `CuratedSchemaProblem` imports; `CuratedSchemaEvent.KIND` and `GroupRelaySet.KINDS` from `ACCEPTED_KINDS`; `Refused.SchemaUnusable` and its arm; the bitcoin.mov and `npm run schema:dry` KDoc |
| `C/text/ProposedEvent.kt` | port **only** the kind 0 and kind 1 arms (lines 168-183). Not the nip30303, schema, entry or relay arms |
| `C/database/model/ChatMessage.kt` | port only the `MetadataEvent.KIND` (1580-1600) and `TextNoteEvent.KIND` (1620-1635) arms and the two types. Not the relay, schema, entry or Mantra arms |
| `C/ui/view/model/BroadcastGroupSignedEventViewModel.kt` | the `schemas` parameter and its imports; the `schemaRelays` block; the General and Blocked relay-list logic and indexer addition in `defaultRelaysFor` (D1); the "Broadcast all" KDoc |
| `C/ui/view/state/BroadcastGroupSignedEventUIState.kt` | the `schemas` and `relayLists` fields |
| `C/ui/composable/BroadcastGroupSignedEventScreen.kt` | `schemas =` in `seedRelays` |
| `C/ui/composable/GroupNostrProfileScreen.kt`, `EditGroupNostrProfileScreen.kt` | the lud16 row and field (D6) |
| `C/ui/composable/ProposeGroupEventScreen.kt` | the "Curated schema · bitcoin.mov" KDoc; `this_schema_cannot_be_signed_as_it_is`; the relay-list preview |
| detail screen, state and view model | `groupCuratedSchemas`, `groupCuratedEntries`, `groupRelayLists`, `mantraRepository`, and their rows |
| tests | the schema, entry and relay cases in `GroupEventProposalTest`, `ProposedEventTest`, `BroadcastGroupSignedEventViewModelJvmTest` and `ProposeGroupEventScreenJvmTest`. That screen test's first case ("a pasted schema…") needs a kind:1 replacement |

`GroupRelaySet.relayUrlOrNull` (`C/nostr/GroupRelayList.kt:272-277`) is the one piece of
the relay-list file that broadcast needs: `wss://` only, no localhost. Move it into a
small `K/nostr/RelayUrlInput.kt` and do not port the rest.

---

## Phases

One commit per phase, in the repository's style (`feat: … (group-nostr-profile, Phase n)`).
Every phase leaves `./gradlew :composeApp:jvmTest` and `:composeApp:m3Audit` green.

### Phase 0: this document

This document and its row in [README.md](./README.md)'s index. Both are done.

### Phase 1: pin today

Write tests of the behaviour in *What Giza does today*, written to pass now and changed
on purpose by the phases that change it:

- `ProposedEventTest`: a kind:0 and a kind:1 summarise as "Event of kind 0" and "Event of
  kind 1". Phase 2 flips this.
- In `ProtocolKindDispatchJvmTest`, or a new `GroupStatementApplyJvmTest`:
  - a room-signed kind:0 applied through `applyInnerEvent` returns `null`;
  - a room-signed kind:1 returns an `"unsupported"` row;
  - a member's own kind:1 returns `"unsupported"`;
  - a row of type `"groupProfileSigned"` is in `GROUP_IDENTITY_TYPES`.

  Phase 3 flips the first two.
- **The premise test.** A kind:1 session run through the signing round in a *current-profile,
  keyed* room produces a `GroupSignedEvent` whose `verifies()` is true. Use the harness of
  `FrostSigningRoundTest` or `ShareRefreshRitualJvmTest`, which already sign kind:1s.

  This is the claim the whole plan rests on: a Giza room made today signs as its own id.
  If it fails, stop and report it. Every reader would reject every event, and the plan
  needs a decision about Marmot v2's routing id before it goes on.

### Phase 2: the readers, the summaries, the repository

No UI changes in this phase.

- `K/nostr/GroupNostrProfile.kt` and `K/nostr/GroupPost.kt`, from Curare. Change the
  template per D6 (`lnAddress = null`, and drop the `lud16` parameter).
- `K/text/ProposedEvent.kt`: the kind 0 arm ("Nostr profile", `anyName · about`, "An
  empty profile", "Unreadable profile") and the kind 1 arm ("Post", the content).
- `ChatRepository` and `DatabaseChatRepository`:
  - `groupNostrProfile(chatRoomId)`;
  - `groupPosts(chatRoomId)`;
  - `groupSignedEvent(chatRoomId, id)`, which returns `getById(id)` only when its
    `chatRoomId` matches;
  - all three on the no-op `ChatRepository` too (`K/repository/ChatRepository.kt:460`).

  Put them beside `signedGroupKeyStateEvent`.
- Tests:
  - `GroupNostrProfileTest` and `GroupPostTest`, ported. Rework the template assertions
    for quartz 2.20.1, which writes tags as well as content: assert on the parsed
    content, and separately that tags agree with it.
  - Add a D6 case: an edit of a profile carrying `lud16` keeps it.
  - Flip Phase 1's `ProposedEventTest` cases.

Done when the readers return what Curare's do for the same events, and `FrostSigningScreen`
and `ProposalListScreen` describe a peer's kind:0 or kind:1 by name rather than by number.
They both read `ProposedEvent`, so this phase fixes them for free.

### Phase 3: the transcript

- `ChatMessage`:
  - Make the two types public as `TYPE_GROUP_PROFILE_SIGNED` and
    `TYPE_GROUP_POST_SIGNED`, with the same strings (D3). Keep them in
    `GROUP_IDENTITY_TYPES`.
  - Split the `:1298-1302` arm. `MetadataEvent.KIND` gets Curare's arm: `null` unless the
    author is the room and `GroupNostrProfile.of` reads it, otherwise "The group is now
    called %s" or "The group signed a new profile for itself". The four relay-list kinds
    keep returning `null`, with their comment rewritten to say why (D1).
  - Add the `TextNoteEvent.KIND` arm: a room-signed, verifying kind:1 is "The group posted
    in its own name"; anything else falls to `unsupported()` as today.
  - The line text comes from the string catalogue. `ChatMessage` is not composable, so
    follow whatever the neighbouring arms do for their text. Do not add an interpolated
    literal.
- Update `ChronicleTestKinds`' comment (`commonTest/.../nostr/chronicle/ChronicleTestKinds.kt:27-33`).
  Its premise, that none of these kinds writes a `GroupSignedEvent` of its own on arrival,
  still holds, but its account of what a kind:0 and a kind:1 do does not.
- Flip Phase 1's apply tests.
- Add what Curare never had: a test of each arm, both the room-signed case and the
  member-signed case.

Done when completing a kind:0 or kind:1 session leaves a system line, not silence or a
JSON bubble.

### Phase 4: decline what nothing would show (D5)

- Add `K/managers/GroupStatementRefusal.kt`. Its `of(events): Refusal?` refuses a kind:0
  whose `contactMetaData()` is `null` and a kind:1 whose content is blank. Phase 8 will
  have `GroupEventProposal` call the same predicates, so put them here, where both can
  reach them.
- Call it in `acceptProposal` right after `WalletRefusal` (`FrostSigningManager.kt:651-659`),
  and in the `approve` backstop (`:2120-2127`).
- Give `publishDecline`'s reason selection (`:2601-2610`) its own reason string.
- Tests, in the `FrostSigningRoundTest` or `WalletRefusal` test harness:
  - an unreadable kind:0 from a peer is declined, the decline is published, and the owner
    is never asked;
  - a readable one is asked as before;
  - a blank kind:1 is declined.

Skippable. Phases 5 to 8 do not depend on it.

### Phase 5: the profile, seen and proposed

- Routes in `K/ui/composable/navigation/routes/`: `GroupNostrProfileRoute` and
  `EditGroupNostrProfileRoute(activeUserPublicKey, chatRoomId, relayHint)`. Register both
  in `DedunNavHost` the way `MemberActionUnavailableRoute` is (`:1211-1226`).
- `GroupNostrProfileViewModel` / `UIState` / `Screen`, and
  `EditGroupNostrProfileViewModel` / `UIState` / `Screen`, from Curare. Then bring each
  screen up to `CLAUDE.md`:
  - `onNavigateBack` and `NavigateBackButton` in the app bar;
  - `readableContent()` on the content root;
  - `ScreenStateTransition` around the whole `when`;
  - `EmptyState` with its own message, and `ErrorState` with a decided `onRetry`;
  - spacing from `MaterialTheme.spacing`, and colour from roles;
  - `Decorative` or a real description on every icon;
  - sentence-case strings from the catalogue, with format arguments, never `"${…}"`.

  Curare ran the same eight-phase M3 audit, so most of this should already hold. Check
  anyway.
- The editor proposes through `frostSigningRepository.proposeSigning(...)` and, on
  success, replaces itself with `FrostSigningRoute(…, sessionId)`. Use the host's
  pop-inclusive navigate, as Curare does, so back from the session returns to the profile
  and not to a stale form. Report failure through `rememberNotifier`.
- The read screen's empty state says this device holds no profile for the group (D8), not
  that the group has none.
- Detail screen:
  - `ChatRoomDetailUIState.Loaded` gets `groupNostrProfile: GroupNostrProfile?` and
    `canProposeAsGroup: Boolean`.
  - The view model fills them from the `canSign` it already computes, and `!isDisbanded`
    (D4).
  - Add a "Nostr profile" row as a `Card { ListItem(leading icon, supporting text,
    ChevronRight) }`, the shape of the signing-key card at `ChatRoomDetailScreen.kt:480-516`.
    Place it after the description divider (`:644-646`), before the Shares section, where
    Bennu had it. Show it only in Marmot rooms.
- Strings: port the profile screen's keys from Curare's `strings.xml`, minus D6's two.
  Add any escapes the copy depends on to `StringCatalogueJvmTest`.
- Tests:
  - `GroupNostrProfileScreenJvmTest`, ported. The lud16 assertions become "not shown".
    Add that the editor is not offered without `canProposeAsGroup`, and not to an npub
    identity.
  - Edit `ChatRoomDetailWithoutWalletsJvmTest`'s "no nostr profile, relays, posts or
    paste" test: "Nostr profile" is now displayed in a Marmot room and absent in a NIP-17
    one. "Relays" stays absent.
  - An `EditGroupNostrProfileViewModel` test with a fake `FrostSigningRepository`: the
    proposed template keeps unknown fields and `lud16`, a blank field deletes, and the
    name goes to both `name` and `display_name`.

### Phase 6: posts, seen and proposed

The same shape as Phase 5:

- `GroupPostsRoute` and `AddGroupPostRoute`.
- `GroupPostsViewModel` / `UIState` / `Screen`, and `AddGroupPostViewModel` / `UIState` /
  `Screen`.
- A "Posts" row with its count ("No posts yet", "1 post", "%1$d posts"), under the profile
  row.
- The posts screen's byline uses `groupNostrProfile()`, falling back to the key.
- The composer refuses a blank note before proposing (`onEmpty`). A kind:1 is not
  replaceable, so it cannot be taken back. The screen should say so above the button,
  which Curare's `the_group_signs_a_post_so_it_takes_a_quorum` string does.
- Tests: `GroupPostsScreenJvmTest`, ported; the detail-screen test's "Posts" assertion
  flips; an `AddGroupPostViewModel` blank-note test.

### Phase 7: broadcast

- `K/network/relays/EventPublishTransport.kt`, reinstated (D7). `RelaysSocketManager`
  implements it.
- `K/nostr/RelayUrlInput.kt`: `relayUrlOrNull`, lifted out of Curare's `GroupRelaySet`.
- `BroadcastGroupSignedEventRoute(…, signedEventId)`, and `BroadcastGroupSignedEventViewModel`
  / `UIState` / `Screen`, from Curare.
- `RelayOutcomeLabel` and `BroadcastButton` widgets.
- `defaultRelaysFor(event)` returns `Relays.eventPublishRelaySet` alone. Keep it as a
  function of the event, so relay lists (D1) or a per-kind rule can return without
  touching callers. The member adds and removes targets before sending, as in Curare.
- Put a `BroadcastButton` under the profile card and under each post. It is not gated on
  `canProposeAsGroup` (D4).
- The broadcast screen shows the event with `ProposedEvent.summarize`, offers to copy the
  raw JSON, and reports each relay as sending, accepted, rejected, could not send, or no
  answer, then sums up as "Accepted by %1$d of %2$d relays".
- Tests:
  - `BroadcastGroupSignedEventViewModelJvmTest`, ported and reduced: seeding once, outcome
    mapping, host matching, no-answer on timeout, the wire event identical to the signed
    one, and `defaultRelaysFor` is the publish set.
  - `BroadcastGroupSignedEventScreenJvmTest`, ported.
  - A test that a broadcast writes **no** `NostrEvent` row and **no** `Profile` row (D7).
    Curare has none, and it is the decision this phase most needs pinned.

Done when a signed profile or post can be sent to relays from the device that holds it,
with no feed or profile side effects.

### Phase 8: paste an event (D9)

- `K/nostr/GroupEventProposal.kt` with `ACCEPTED_KINDS = {0, 1}` and the stripped
  `Refused` set. Its kind:0 and kind:1 checks call `GroupStatementRefusal`'s predicates if
  Phase 4 was built, so the proposer and the signer refuse the same things.
- `ProposeGroupEventRoute`, and `ProposeGroupEventViewModel` / `UIState` / `Screen`.
- A "Propose event" `TextButton` under the two rows, shown when `canProposeAsGroup`.
- Tests: `GroupEventProposalTest` and `ProposeGroupEventScreenJvmTest`, ported and
  stripped. A kind:10002 paste reads `KindNotAccepted`.

### Phase 9: the record, and three devices

- Docs:
  - Mark this plan built, with a *what the plan said / what it turned out to be* table,
    as [admin-change-rotation.md](./admin-change-rotation.md) does.
  - Update the README row.
  - Correct the notes that say these features are gone:
    - [npub-profile-preview.md](./npub-profile-preview.md) lines 99, 351 and 458;
    - [ceremony-creation.md](./ceremony-creation.md) lines 28-29, 145 and 443 (443 is
      already stale);
    - [marmot-v2.md](./marmot-v2.md) line 51, about the kind:1 arm;
    - [retired/group-chain-wallet.md](./retired/group-chain-wallet.md) lines 455-458 get
      a "since reinstated" note. That note is retired, so add a note and leave the history
      as written.
- `CLAUDE.md`'s `Res.string.add_post` example becomes a real key again. Leave it.
- Device run, on three desktop clients or emulators in one keyed group with threshold 2:
  1. Member A proposes a profile.
  2. B approves, and the session completes.
  3. Every device shows the system line, and the profile screen shows the profile.
  4. C (non-signing) opens the profile and broadcasts it to two relays.
  5. A public nostr client shows the group's npub with that name.
  6. Repeat for a post.
  7. Edit the profile from a second device and confirm the edit keeps a field Giza does
     not offer.
  8. If a Curare build is available, have it propose a post into the same group and
     confirm a Giza member sees "Post" and can sign it. With Phase 4 built, also confirm
     that an unreadable kind:0 from Curare is declined and Curare shows the decline.

---

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

`check` also runs `brand-audit.sh`, which fails on any Bennu name a Curare file carried
in outside a comment, and `wallet-audit.sh`. Neither kinds 0 and 1 nor any file name in
this plan trips the wallet audit. Its file-name rule covers `GroupWallet*`, `GroupSend*`
and the like, not `GroupPost*`.

---

## Risks

| risk | what to do |
|---|---|
| A Giza room made today does not sign as its own id (Marmot v2's routing id) | Phase 1's premise test. If it fails, stop: every reader would refuse every event |
| quartz 2.20.1's `updateFromPast` behaves differently from 1.15.2's: tag mirroring, NIP-92 imeta pruning, and the `createdAt` default | Phase 2's template tests assert on 2.20.1's real output, not Curare's expectations |
| The detail screen is already long, and new rows push Shares and Members further down | two rows and one button, the same footprint Bennu had. Check at phone width in the device run |
| A string ported from Curare in title case, or with `\'` or `%%`, which Compose Resources does not unescape | `m3Audit`'s title-case budget is 0. `StringCatalogueJvmTest` covers escapes |
| Broadcast's timeout and outcome matching were tuned to Curare's `RelayPool` | `RelayPool` is shared ancestry. Confirm `PUBLISH_TIMEOUT` exists and the host matching works on Giza's `NormalizedRelayUrl` in Phase 7's view-model test |
| D5 declines a profile a Curare member meant to send | only a profile no reader could show. The decline is visible on the Curare side, and Curare's editor never produces one |

---

## Out of scope

- **The group's relay lists** (D1), and with them a better default for broadcast.
- **Relay-list proposals from a Curare peer.** Today they are signable as "Event of kind
  10002" and leave nothing behind once signed. Whether Giza should decline them, like
  wallet kinds, is a separate question from this plan.
- **Chronicling the profile and posts to late members** (D8).
- **Publishing on completion.** Signing still puts nothing on the wire. A member
  broadcasts.
- **Replies, mentions or reposts as the group.** The composer builds a bare kind:1, as in
  Curare.
- **Making the transcript's system lines open the profile or posts screen.** They lead
  nowhere in Curare too.
- **Curare's curated schemas and entries.**

---

## Order and size

| phase | rough size | depends on |
|---|---|---|
| 1 pin | ~200 lines of test | — |
| 2 readers | ~350 main, ~750 test (ported) | 1 |
| 3 transcript | ~80 main, ~150 test | 2 |
| 4 decline | ~100 main, ~150 test | 2 |
| 5 profile | ~900 main, ~250 test | 2 |
| 6 posts | ~800 main, ~200 test | 5 (the rows' place) |
| 7 broadcast | ~1,000 main, ~550 test | 5 and 6 (the buttons) |
| 8 paste | ~650 main, ~400 test | 2, and 4 if built |
| 9 record | docs and a device session | all |

Phases 3 and 4 can run beside 5 and 6, since they touch none of the same files. Everything
else is in order.
