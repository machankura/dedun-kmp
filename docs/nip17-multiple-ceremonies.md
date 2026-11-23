# More than one group from the same people

A plan to let the same set of people make a second group, a third, and so on,
each with its own ChillDKG key and its own admin room. Today they can make one.
A second attempt finds the first group's NIP-17 room, finds the ceremony that
finished there, and hands that back. The user typed a new name and picked a new
quorum, and gets the old group with no error.

Read [shared-key-ceremony.md](./shared-key-ceremony.md) first, especially
*Where a ceremony starts*. Read [subgroups.md](./subgroups.md) for the parallel
case: a parent's Marmot room already hosts any number of subgroup ceremonies,
and the code that made that work is the model for most of this plan.

**Built** except for the device run, one commit per phase on
`claude/nip17-multiple-chilldkg-6903b0` (`dd812950` to the commit carrying
this record), against the code as it stood at `3b0b3c9a`.
`TwoGroupsFromTheSamePeopleJvmTest` runs the whole thing across three
in-memory devices with real ChillDKG and real FROST: Ekklesia, then Agora at a
different quorum, both made at once, and one abandoned while the other
finishes. The phases below are kept as written, because they hold the
reasoning. Where the build differs from the plan:

| what the plan said | what it turned out to be |
|---|---|
| Phase 1 requires a name before the fold is checked | after it. A nameless ask while the room's first ceremony is still running is the repeat `making the same group twice` pins, and has to fold, not throw. The name is required only when nothing folds and the room already holds a group |
| Phase 2's card uses a `<plurals>` entry | two strings, one for a single group and one for several. The app's catalogue has no plurals, and adding the first one for one card was not worth it |
| Phase 3's `Loaded` gains a `name` field | the view model holds `name` beside `threshold`, as the quorum already was. The field is always shown, filled with the room's name for its first group, so a user can rename even the first. The groups already made are named in a sentence, using the creation screen's wording, not drawn as a list |
| Phase 3 deletes the DAO query behind `getLatestSessionForChatRoom` | `getLatestOwnSessionForChatRoom` and `observeLatestSessionFor` are deleted. `getLatestSessionFor`, `getLatestSessionForChatRoom` and `observeLatestSessionForChatRoom` stay, used only by the DAO's own tests. `CeremonyLookupsJvmTest` now expects no app code to call any of them |
| Phase 3's `completedKey` answers when the room holds exactly one completed own ceremony | it counts the ones this device can sign with. A room with one finished key and a second ceremony still running answers the finished one, where the old code answered null whenever the newest ceremony was unfinished |
| Phase 4 names the group on an own ceremony's lines | on every named ceremony's lines, subgroups included. "The group has a shared key", said in a parent's room about its subgroup, was already wrong before this |
| Phase 5's rows open the Marmot room | they open its details, as the admin-room card they replace did. The status icon is `Decorative`, because the row's supporting text states the status |
| Phase 6 signs in `&Ekklesia` and `&Agora` | each derived room id resolves, through `completedKey`, to its own ceremony on every device, and the NIP-17 room to neither. Creating the rooms is recorded through a stand-in `createMarmotGroup`, as `KeyStateSignsItselfJvmTest` does, so nothing is signed inside a Marmot room |
| Phase 7's upgrade-in-place check | the rule it rests on is pinned in `RobustRoomKeyCeremonyTest`: a ceremony row with no name is matched under its room's name. The check on a real upgraded device has not been done |

**Not done:** the device run in Phase 7. Nothing here has run on a phone or
on real relays, including a device upgraded mid-ceremony.

The headline: **a NIP-17 room is where a group's ceremony is held. It is not
the group.** The room's id is derived from its members
(`ChatRoom.deriveChatRoomId`), so the same people always land in the same room.
A group is the ceremony's key and the Marmot room derived from that key, and
two ceremonies give two keys, two rooms and two groups. Almost everything below
the creation screen already works this way. Signing sessions, key states,
transcript lines, the follow-through and the wire messages are all keyed by
ceremony id. The fold that blocks a second group lives in one `if`. The rest of
the plan covers the half-dozen places that read "the room's ceremony" as if
there could only be one, and the screens that never had to say which group they
meant.

---

## What happens today

Alice makes **Ekklesia** with Bob and Carol, at 2-of-3. A week later she makes
**Agora** with the same two people, at 3-of-3.

| # | step | where | what happens |
|---|---|---|---|
| 1 | room | `NostrNip17Dao.createNip17ChatRoom` | Same members, same id, so the existing room comes back (`"already exists; reusing it"`). Its subject stays "Ekklesia". The name "Agora" and the new description are dropped here |
| 2 | ceremony | `SelectQuorumViewModel.createGroup` → `ChillDkgRitualManager.proposeRitual` | `getLatestSessionFor(room, null)` finds Ekklesia's ceremony. It is `COMPLETE`, which is not `FAILED`, so it counts as "running" and is returned. No proposal goes out |
| 3 | navigation | `SelectQuorumViewModel` | Alice lands in the NIP-17 room with no error. The 3-of-3 she asked for was never used |

Even without the fold in step 2, a second ceremony would still be named after
the room. `proposeRitual` falls back to `localChatRoom.chatRoom.subject` when
no subject is passed, and `SelectQuorumViewModel` never passes one, because
until now the room's name and the group's name were the same thing. The result
would be two admin rooms both called `&Ekklesia`.

The test that pins this is
`RobustRoomKeyCeremonyTest.making the same group twice does not open a second ceremony`.
Its reasoning still holds for what it was written for: a creation screen
pressed twice should not open two ceremonies. It does not hold for a user who
asks for a different group.

### What already handles more than one

These were checked while writing this plan. Phase 0 pins each one in a test
before anything relies on it.

| piece | keyed by | why it already copes |
|---|---|---|
| wire messages (30310–30316) | `dkg_session` tag | every message names its session; `record` and `replayStoredMessages` filter on it |
| `acceptProposal` (member side) | session id | returns early only for a session it already has; nothing refuses a second proposal in a NIP-17 room |
| `DkgParticipantMessage` | `(sessionId, participantPublicKey, kind)` | redelivery and interleaving are absorbed per session |
| transcript lines | `ChatMessage.dkgSessionId` | `isAbout` matches a request to its answer by session, not by time |
| `CeremonyFollowThrough` | session id | each completed ceremony is followed on its own; the key-state lookup matches `GroupKeyStateEvent.parseDkgSessionId == current.id` |
| `GroupKeyStateManager.propose` | the ceremony passed in | names its key; never asks the room for one |
| signing sessions | `FrostSigningSession.dkgSessionId` | an inbound proposal names its key and is dropped if this device lacks it |
| admin room | `SharedKeyDerivation.marmotGroupId(key)` | two keys give two room ids, so `MarmotGroupCreation` cannot confuse them |
| `ChatMessageListViewModel.observeCeremoniesAwaitingYou` | every session in the room | already lists all of them; it just doesn't say which group each is |

### What assumes one

These reads take "the room's ceremony" and get the newest one. With two
ceremonies in a NIP-17 room, each one is either ambiguous or wrong.

| site | reads | goes wrong how |
|---|---|---|
| `ChillDkgRitualManager.proposeRitual` (the fold, ~L222) | `getLatestSessionFor(room, null)`, not `FAILED` | blocks every group after the first (the bug) |
| `ChillDkgRitualManager.proposeRitual` (subject, ~L272) | `localChatRoom.chatRoom.subject` | second group gets the first group's name |
| `SelectQuorumViewModel.createGroup` | passes no `subject` | same as above, from the caller's side |
| `CreateSharedKeyViewModel.initiate` (~L86) | `observeLatestSessionForChatRoom(room, null)` | any non-failed ceremony becomes `Existing`, so the screen can never start a second one |
| `DatabaseChatRepository.adminRoomOf` (~L151) | `getLatestSessionFor(room, null)`, `COMPLETE` | the detail screen links to one admin room: the newest group's, or none while a newer ceremony is still running |
| `FrostSigningManager.completedKey`, last fallback (~L1955) | `getLatestOwnSessionForChatRoom` | in a NIP-17 room, "the key this room signs with" has no single answer |
| `DkgRitualScreen.retryRoute` | `CreateSharedKeyRoute(room)` | a retry of Agora's failed ceremony lands on a screen that offers Ekklesia's slot |
| `announceStarted` and the other transcript lines | "started a shared key ceremony" | two ceremonies in one room read the same |
| `DkgRepository.getLatestSessionForChatRoom` | newest in room | no production caller left; delete it rather than fix it |

---

## Decisions

### D1. The fold covers a running ceremony with the same name, and nothing else

`proposeRitual` should still return an existing ceremony when the caller is
repeating itself: a double tap, a retried coroutine, or a user who comes back
to the creation screen while the first proposal is still out. It should open a
new ceremony when the caller is asking for a different group.

The rule: **fold into an own ceremony in this room that is still running
(neither `COMPLETE` nor `FAILED`) and whose subject matches the requested one**,
trimmed and compared case-insensitively. A finished ceremony never blocks a new
one. Subgroups made the same choice (`getLatestSubgroupSessionFor`, "a subgroup
that has been made is not a reason to refuse making another over the same
people"), and the reason is the same: a completed ceremony is a group that
exists, not a request that is still pending.

Alternatives considered:

- *Fold into any running own ceremony, whatever its name.* That makes ceremonies
  in a room run one at a time. It is simpler for members, who join one thing at
  once, but it blocks a group behind a ceremony waiting on one member's phone
  for days, with no visible reason. The protocol already separates concurrent
  sessions. Rejected.
- *Fold on the threshold too.* A user who asks for the same name at a different
  quorum is correcting a mistake, not making a second group. They should abandon
  the first ceremony, not get a second one with the same name. Rejected.
- *Add an explicit "new group" flag and never fold.* This moves the idempotency
  burden onto every caller, and the existing test shows why the fold was added
  in the first place. Rejected.

### D2. A second ceremony in a room has to carry its own name

The room's subject is a fallback **only for the room's first own ceremony**.
When the room already holds an own ceremony that has not failed,
`proposeRitual` requires a non-blank `subject` and throws otherwise. It's a bug
in the caller rather than user error, the same way the threshold check works.
Without this, the second admin room would get the first group's name.

Two groups with the same name over the same people are allowed: names are not
identities, and their room ids differ. The creation screen warns before it
happens (Phase 2). The manager does not refuse it.

### D3. The NIP-17 room is not renamed

The room keeps whatever subject it has, which is usually the first group's
name. In NIP-17 a subject is shared state: a kind-14 message carrying one
renames the conversation on every client (`NostrDao`, the `parseSubject()`
branch on chat messages). Renaming the room to "Agora" on Alice's device would
either disagree with Bob's or need a message to everyone saying so. Neither
helps anyone.

The ritual proposal is kind 30310, not 14, so its subject tag only names the
room on a device that has never seen it (`getOrCreateNip17ChatRoom` creating
the row). It does not rename an existing room. Phase 0 pins that, because if it
ever changed, every second ceremony would rename the room on the members'
devices and not on the coordinator's.

What changes instead is what the room's screens say about it (Phase 5): it is
the conversation these people share, and it lists the groups made in it.

### D4. One host key per member, reused across ceremonies

`deriveHostSecretKey(nostrPrivateKey)` is deterministic, so the same member
brings the same ChillDKG host key to every ceremony. Two ceremonies over the
same people at the same threshold therefore have **identical ChillDKG
parameters**: the same sorted host keys and the same `t`. Nothing in ChillDKG
sees the `dkg_session` tag.

ChillDKG is designed for this. The host key is long-term, and each session's
secrets come from the per-session `random` and `auxRand`, which `DkgSession`
already mints fresh for every row (`round1Random`, `round2AuxRandom`). That
claim gets a test, not an argument (Phase 0, P1):

- two sessions with identical parameters, interleaved, both complete with
  different threshold keys;
- a message from one fed into the other makes that ceremony fail; it must not
  complete.

If the second property fails, the plan stops at Phase 0. The fallback is
binding the session id into the host key derivation. That would break restoring
a share from the member's backup, which today needs only the nostr key, so it
would need its own plan.

### D5. Nothing here changes the wire

No new kinds, no new tags. A second ceremony is the same 30310 with a different
`dkg_session` and its own `subject`. A Bennu peer's `acceptProposal` is the
same code lineage and keys by session id. Phase 0 confirms that against
Bennu's current source, because a Bennu member who silently dropped the second
proposal would leave the ceremony stuck waiting on them.

---

## Phase 0: before any code

Tests only, plus this document. Each one pins a premise the later phases build
on. A premise that turns out false changes the plan before code is written.

**P1. ChillDKG with repeated parameters** (`ChillDkgRepeatedParamsJvmTest`, or
extend `CeremonyAcrossDevicesJvmTest`). Three devices on `CeremonyNetwork` run
two ceremonies over the same people at the same `t`, with rounds interleaved
(A's host keys, B's host keys, A's round 1, B's round 1, and so on). Both
complete, and their `thresholdPublicKey`s differ. Then a variant where one
device's round-1 message for A is delivered tagged as B: B fails with a fault,
and A is unaffected.

**P2. The member side already takes a second ceremony.** A device holding a
`COMPLETE` own ceremony in a NIP-17 room receives a second proposal from the
same coordinator. `acceptProposal` records it, asks to join, and the chat line
carries the new session id.

**P3. A ceremony proposal never renames an existing NIP-17 room.** Inbound
30310 with `subject = "Agora"` into a room named "Ekklesia": the room is still
"Ekklesia" afterwards (D3).

**P4. The audit table above, as a test where it can be one.** A source scan, in
the style of `CeremonyEntryPointsJvmTest`, that lists every call of
`getLatestSessionFor(…, null)`, `observeLatestSessionFor(…, null)`,
`getLatestOwnSessionForChatRoom` and `getLatestSessionForChatRoom` from
`commonMain`. It fails when a new caller appears. Phase 3 shrinks its expected
set to zero, or to the callers that are correct for Marmot rooms only, each
with a comment saying why.

**P5. Who reaches `completedKey` with a NIP-17 room id.** Trace every caller
(the earlier list is in `FrostSigningManager`, `AdminGovernance`,
`PromotionManager`, `ShareRefreshManager`, `GroupDmReceiver`,
`DatabaseChatRepository`, `InboxKeyRitualManager`). Expected result: only
`proposeSigningBatch` with `key = null`, and only from a flow that should be
naming its key anyway. Record the list in this document.

**P6. Bennu's `acceptProposal`** keys by session id and does not refuse a
second ceremony in a room (D5). Read it from the Bennu source; this one is not
a test.

Done when P1–P3 pass against unchanged code, P4 and P5 are written down, and
P6 is answered.

**Result.** Every premise held.

- P1, P2 and P3 are `SameMembersSecondCeremonyPremiseJvmTest`, and pass against
  the code at `3b0b3c9a`. Because today's code cannot open a second ceremony
  from the same coordinator, P1 has two members each open one before either
  hears of the other. P2 sets the first ceremony aside on the coordinator's
  device for the moment it takes to propose. Carol's Ekklesia contribution,
  relabelled as her Agora one, leaves Agora with no key on any device and
  Ekklesia untouched. D4 stands.
- P3 goes through `NostrDao.storeNostrEvent` with a real seal and wrap. Bob
  records Agora, and his room is still called "Ekklesia".
- P4 is `CeremonyLookupsJvmTest`. It finds the six reads in the audit table and
  no others.
- P5: the only signing proposal that runs in a NIP-17 room is
  `GroupKeyStateManager.propose`, which passes `key`. Every caller that reaches
  `completedKey` or `canSign` from a screen is behind
  `GroupStatementCapability.roomIsOpen` or a `mlsGroupState != null` check, both
  of which refuse a NIP-17 room first. The managers that call it
  (governance, promotion, refresh, group DMs, inbox, confederation) are all
  handed Marmot rooms. So the Phase 3 change to the fallback takes nothing away
  from any flow that runs today.
- P6: Bennu's `acceptProposal`, at `2db6dde5`, returns early for a session it
  holds and for nothing else, exactly as Giza's does.

---

## Phase 1: the coordinator can open a second ceremony

Manager and DAO only. No screen changes.

**`DkgSessionDao`**

- `getRunningOwnSessionsFor(chatRoomId)`: own (`parentChatRoomId IS NULL`),
  `stage NOT IN ('COMPLETE','FAILED')`, newest first.
- `observeOwnSessionsForChatRoom(chatRoomId)`: every own ceremony in the room,
  newest first. Used by Phases 3 and 5.
- `getCompletedOwnSessionsFor(chatRoomId)`: `COMPLETE` with a
  `thresholdPublicKey`. Used by Phase 3.

**`ChillDkgRitualManager.proposeRitual`**

1. Work out `ceremonySubject` *before* the fold, since the fold now compares
   against it. Order: `subject`, then the room's subject, but the room's
   subject only if the room holds no non-failed own ceremony (D2). Otherwise
   `require(subject non-blank)`.
2. Replace the own-ceremony branch of the fold with D1: the first of
   `getRunningOwnSessionsFor` whose `subject ?: room.subject` matches
   `ceremonySubject`, normalised. Comparing against `room.subject` covers rows
   written before `DkgSession.subject` existed.
3. Leave the subgroup branch alone. It already does the right thing.
4. Rewrite the comment above the fold. It currently says "a room runs one
   ceremony at a time for one purpose", which is the rule being dropped.

**Tests** (`RobustRoomKeyCeremonyTest`)

- `making the same group twice does not open a second ceremony` keeps passing
  unchanged. It asks again under the same name while the first ceremony is
  running, which is exactly the D1 fold.
- New: *a finished group does not stop the same people making another*. Mark
  the first ceremony `COMPLETE`, then open a second ceremony named "Agora". The
  ids differ, there are two proposals on the wire, and the new one carries
  `subject = "Agora"`.
- New: *two groups can be in the making at once*. The first ceremony is running
  and the second is opened under a different name. Two sessions, both running.
- New: *a second ceremony without a name is refused* (D2).
- `a room holding a ceremony will still take one for another purpose`: its doc
  comment says "the UI must not offer a third". Rewrite the comment. The
  assertion stays.

Done when the tests above pass and `CeremonyEntryPointsJvmTest` still counts
three callers.

---

## Phase 2: making a group names its ceremony

**`SelectQuorumViewModel.createGroup`** passes `subject = name` to
`proposeRitual`. It's one argument, and without it Phase 1's D2 check throws on
every second group.

The description the user typed still only reaches the NIP-17 room, and only if
the room is new. That was already true for the first group: the admin room's
description is `roomPurpose`, not what was typed. See *What this does not do*.

**Warning before a duplicate.** `SelectQuorumViewModel.initiate` looks up the
room these members would land in (`ChatRoom.deriveChatRoomId(members + me)`,
read-only, no row created) and that room's own ceremonies. `SelectQuorumUIState.Loaded`
gains `existingGroups: List<ExistingGroup>` (name, stage). The screen shows one
card above the quorum picker. It is information, not a gate:

- with any existing groups: "You already have a group with these people:
  Ekklesia." This uses a plural form for more than one group (a `<plurals>`
  entry in `strings.xml`, sentence case);
- if `name` matches one of them, a second line: "It will be a separate group
  with the same name."

Nothing is blocked (D2). The card helps a user who meant to open Ekklesia and
started making a new group instead.

**Where creation lands.** It still navigates to the NIP-17 room. Phase 4 makes
that room's transcript say which group was just started, so landing in a room
named "Ekklesia" after making "Agora" reads correctly.

**Tests**

- `SelectQuorumViewModelJvmTest` (new, or extend the existing one): creating
  "Agora" over Ekklesia's members opens a second ceremony named "Agora" at the
  chosen quorum and navigates to the shared room.
- The `existingGroups` state for a fresh set of members (empty), for one
  finished group, and for a running one.

Strings go in `strings.xml`. `./gradlew :composeApp:m3Audit` stays inside its
budgets.

---

## Phase 3: nothing picks "the room's ceremony" any more

Every row of *What assumes one*, other than the two Phases 1 and 2 fixed.

**`CreateSharedKeyViewModel`.** `Existing` is replaced. The screen is now *make
a group from this room's members*:

- `Loaded` gains `groups: List<DkgSession>` (from
  `observeOwnSessionsForChatRoom`, failed ones excluded) and a `name` field.
- With no groups yet, `name` starts as the room's subject, which is today's
  behaviour, and a user who only taps **Create** gets exactly what they got
  before.
- With groups, `name` starts empty and **Create** stays disabled until it is
  filled (D2).
- The hand-over to an existing ceremony goes away. Phase 5 lists existing
  ceremonies on the screen instead of jumping to the newest.
- `create` passes `subject = name`. A fold under D1 returns the running
  ceremony with that name, and the screen navigates to it, as `Existing` did.

**`DkgRitualScreen.retryRoute`.** `CreateSharedKeyRoute` gains
`retrying: String? = null`, the same field the subgroup picker already has.
When it is set, the screen fills `name` and the quorum from the failed
ceremony. A retry of Agora then reopens as Agora, not as the room's first slot.

**`ChatRepository.adminRoomOf` → `adminRoomsOf(chatRoomId): List<AdminRoom>`**
with `AdminRoom(name, chatRoomId)`: every completed own ceremony whose derived
room exists on this device, newest first. Callers:

- `ChatRoomDetailViewModel` (`adminRoomId` → `adminRooms`), drawn in Phase 5;
- `MemberActionUnavailableViewModel`, which sends a member to "the admin room"
  when an action is blocked by `NoAdminsInThisRoom`. With more than one, the
  explanation screen names each one and links to each. The member is in a
  conversation that runs several groups, and which one they meant is their
  call.

**`FrostSigningManager.completedKey`.** The last fallback
(`getLatestOwnSessionForChatRoom`) only answers when the room holds **exactly
one** completed own ceremony. With more than one it returns null and logs. This
follows P5: any flow that signs in a NIP-17 room has to name its key, as
`GroupKeyStateManager.propose` already does. If P5 finds a flow that does not,
fix that flow here to pass `key` explicitly. Don't loosen the fallback.

**`DkgRepository.getLatestSessionForChatRoom`** and the DAO query behind it:
delete them. They have no production caller.

**P4's scan** drops to its final set: what remains is correct because the room
is a Marmot room, and each remaining caller says so in a comment.

**Tests**

- `CreateSharedKeyViewModelJvmTest`: no groups gives the room's name; one
  finished group gives an empty name and a disabled create; retrying gives the
  failed ceremony's name and quorum; asking for a running ceremony's name
  navigates to that ceremony.
- `adminRoomsOf` with zero, one and two completed ceremonies, and one whose
  room is not created yet.
- `completedKey` on a NIP-17 room with two completed own ceremonies is null.
- `MemberActionUnavailableViewModelJvmTest`: two admin rooms, both offered.

---

## Phase 4: the transcript and notices say which group

The protocol messages were already separated per ceremony. Now the text has to
be.

**Transcript lines** (`ChillDkgRitualManager.announce*`). For an own ceremony
with a subject, every line names it:

- started: "started a key ceremony for Agora. It will take 3 of 3 members to
  sign with its key, and it finishes once everyone has taken part."
- complete, abandoned, failed, join request: the same, "for Agora".

This applies whether or not the room holds another ceremony. A line is written
once and read later, possibly after a second group has been started, and the
room's state when it was written can't be read back. A ceremony with no subject
(an old row) keeps today's wording.

These strings are built in the manager, not in a composable, so they stay as
format templates in the manager. Check how the subgroup lines are handled
today, since `announceStarted` already interpolates `session.subject`, and do
the same.

**Standing notice** (`ChatMessageListViewModel.observeCeremoniesAwaitingYou`).
`AwaitingCeremony` gains `subject: String?`, and the notice reads "Agora's key
ceremony needs you to join" or "Agora's room is waiting to be created". With
two ceremonies waiting it is already two notices. Each now says which group it
is for.

**Notifications** (`NotificationClassifier` and the text built from it). A join
request names the group. Check what the notification body is drawn from: if it
is the transcript line, this comes for free.

**Ceremony screens.** `DkgRitualScreen`'s title and the join screen use
`session.subject` for an own ceremony, as they already do for a subgroup's. The
join screen adds one line when the room holds other own ceremonies: "You
already share Ekklesia with these people. This makes a separate group with its
own key." Joining is the one decision a member makes (see
[automatic-key-ceremony.md](./automatic-key-ceremony.md)), so the member should
know exactly what they are joining.

**Tests**

- `RobustRoomKeyCeremonyTest`: a named ceremony's started line contains its
  name; an unnamed old row's line is unchanged.
- `ChatMessageListViewModelJvmTest`: two waiting ceremonies give two notices,
  each with its subject.

---

## Phase 5: the NIP-17 room says what it is

The room's detail screen is where a user goes to find out what this
conversation holds, and today it shows one "Shared key" button and at most one
admin room link.

**`ChatRoomDetailScreen`, NIP-17 room only** (`mlsGroupState == null`):

- A **Groups** section listing `observeOwnSessionsForChatRoom`, failed ones
  excluded. One row per ceremony, showing its name and status:
  - running: "Key ceremony in progress", opens `DkgRitualRoute(sessionId)`;
  - complete with its room missing: "Waiting for its room", opens the same
    ladder, which already says what it is waiting on;
  - complete with its room present: opens the Marmot room.

  Each row is a `ListItem` with `minimumInteractiveComponentSize()`, and the
  status icon gets a real `contentDescription`, since the status is not
  repeated in the text when the row is collapsed.
- The **Shared key** button becomes **New group with these members**. It
  leads to `CreateSharedKeyRoute`, which Phase 3 already changed to make a
  group with a name. With no groups yet it reads as it does now.
- The single `adminRoomId` link is removed. The section above replaces it.

Empty, loading and error states use the shared `EmptyState`, `LoadingDataIndicator`
and `ErrorState`. With no groups, the empty message is specific: "No groups
have been made in this conversation yet."

**`CreateSharedKeyScreen`** shows the same list above the name field, so a user
who came here to find a group sees it before making another one.

**Docs.** Rewrite *Where a ceremony starts, and how one ends early* in
[shared-key-ceremony.md](./shared-key-ceremony.md). "an existing NIP-17 group
that has no key yet" becomes "a new group from the members of an existing
NIP-17 room". Update `CeremonyEntryPointsJvmTest`'s doc comment to match. The
set of three callers does not change.

**Checks**

- `./gradlew :composeApp:m3Audit`: no new `.dp` literals in spacing positions,
  no hardcoded colours, no title-case strings, no unguarded `.clickable`, no
  untriaged `contentDescription = null`.
- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`.

---

## Phase 6: two groups across devices, end to end

One test that runs the whole thing, with real ChillDKG and real FROST, in the
style of `KeyStateSignsItselfJvmTest` and `CeremonyFollowThroughJvmTest`:

1. Three devices. Alice makes Ekklesia at 2-of-3. Bob and Carol join. It
   completes, its key state is signed in the NIP-17 room, and `&Ekklesia` is
   created with all three as admins.
2. Alice makes Agora at 3-of-3 over the same people. A second proposal goes
   out. Bob and Carol each get a join request that names Agora. They join, it
   completes, its key state is signed in the **same** NIP-17 room, and
   `&Agora` is created.
3. On every device: two `COMPLETE` own ceremonies in the NIP-17 room with
   different keys; two Marmot rooms with different ids and names; each room's
   `GroupKeyState.dkgSessionId` names its own ceremony; the NIP-17 room is
   still called "Ekklesia".
4. A signature in `&Ekklesia` is made with Ekklesia's shares and verifies
   against Ekklesia's key, and the same holds for `&Agora`. Neither room's
   `completedKey` returns the other's ceremony.

Two variants:

- **Concurrent.** Agora is opened while Ekklesia is still collecting host keys,
  and the rounds interleave on the network. Both complete.
- **One abandoned.** Agora is abandoned midway. Ekklesia completes as before,
  and Agora's FAILURE (30316) does not touch Ekklesia's row.

Done when all three pass in `:composeApp:jvmTest`.

---

## Phase 7: the record, and devices

- At the top of this document, a table of where the build chose differently,
  as the other built plans have, and the status line changed to **Built**.
- A row in [docs/README.md](./README.md)'s table.
- The device run: three emulators (or two and a desktop), Ekklesia then Agora,
  over the same accounts, on real relays. Watch for a relay dropping one
  ceremony's messages and not the other's, which no in-memory test can show.
- One upgrade-in-place check: a device that made a group on the current build
  updates and makes a second group with the same people. Its old ceremony has
  `subject = null` if it predates the column, and Phase 1's fold has to read
  that as the room's subject.

---

## What this does not do

- **The group's description.** What the user types on the creation screen goes
  onto the NIP-17 room, and only if the room is new. The admin room's
  description is `roomPurpose`, "Admins of Ekklesia.", for the first group as
  much as any later one. Carrying it on the proposal would need a new tag and a
  decision about what Bennu peers do with it. That is a separate change.
- **Renaming the NIP-17 room** (D3).
- **Subgroups.** A parent's Marmot room already holds any number of them.
  Nothing here touches `getLatestSubgroupSessionFor` or the subgroup picker.
- **Confederations and group inboxes.** The confederation's key ceremony
  (`ConfederationRitualManager`) and the inbox key (`InboxKeyRitualManager`,
  `InboxKeyCeremony`) have their own rooms, tables and rules. Neither comes
  through `proposeRitual`'s own-ceremony branch.
- **Changing the participant set.** Different people are a different NIP-17
  room already, and this plan is only about the same people.
- **Joining two groups into one, or moving a member's share between them.**
  The groups are independent. Each has its own key, its own room and its own
  quorum, and none of them knows the others exist.

---

## Order and size

| phase | touches | size | depends on |
|---|---|---|---|
| 0 | tests only, this doc | small to medium (P1 is the real work) | — |
| 1 | `ChillDkgRitualManager`, `DkgSessionDao` | small | 0 |
| 2 | `SelectQuorumViewModel`, its screen and state, strings | small | 1 |
| 3 | `CreateSharedKeyViewModel`, `DkgRitualScreen`, `ChatRepository`, `FrostSigningManager`, member-action screens | medium | 1 |
| 4 | ritual manager's announce lines, `ChatMessageListViewModel`, ritual and join screens | small to medium | 1 |
| 5 | `ChatRoomDetailScreen`, `CreateSharedKeyScreen`, docs | medium | 3 |
| 6 | one test file | medium | 1–4 |
| 7 | docs, devices | small | all |

Phases 1 and 2 together are the fix for the reported bug. On their own they
let a second group be made, but they leave its creator's screens pointing at
the wrong group in four places. So they should not ship without Phase 3. Phase
4 can run beside Phase 3, since they touch different files. Phase 5 needs Phase
3's `adminRoomsOf` and the reshaped creation screen.
