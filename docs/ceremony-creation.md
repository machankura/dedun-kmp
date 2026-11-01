# One place to start a key ceremony

A plan to fix the bug where joining a subgroup's key ceremony could start a
second, unrelated one. It also removes the nip30303 document and translation
code, whose event kinds overlap the ceremony's own. The member chronicle, which
carried nothing but nip30303 events, is kept as an empty shell that no longer
asks for or sends anything.

Read [shared-key-ceremony.md](./shared-key-ceremony.md) and
[subgroups.md](./subgroups.md) first, Phase 10 of the second in particular: the
bug exists because a subgroup's ceremony moved into the parent's Marmot room, and
the screens that start and show ceremonies were written for a room that only ever
held its own.

**Built**, one commit per phase on `claude/ceremony-creation` (Phase 7 in two),
against the code as it stood at `e3839da5`. `CreateSharedKeyScreen` is the one
screen that starts a group's own ceremony, and `DkgRitualScreen` only ever shows
one. The phases are kept as written because they are the reasoning; where the
build chose differently it did so these times:

| what the plan said | what it turned out to be |
|---|---|
| Phase 7 deletes the 103 files, Phase 8 the tables | the line moved. The `Mantra*` entities convert to and from the nip30303 event classes, so the classes, entities and DAOs went in Phase 8 with the tables. Phase 7 removed the screens (part 1) and the inbound handling, `ProposedEvent`'s arms and the chronicle's document parts (part 2) |
| the group details' **Shared key** opens an existing ceremony itself, else the creation screen | it always opens `CreateSharedKeyRoute`, which hands over to an existing ceremony. One place decides, and back from the ceremony still returns to the details |
| only `DkgRitualRoute.dkgSessionId` becomes required | the three approval routes' too, and `DkgRitualUIState.Loaded.session` is non-null. Every approval was already named by the ceremony screen that opened it |
| the subgroup picker's quorum stepper and the shared-key one are one component | two. The picker's asks a different question ("how many admins have to approve a change"), so the shared-key one is `SharedKeyQuorumStepper`, private to the creation screen |
| `requestIfEmpty` keeps its "holds work" check | it had to change: the check read the dialect and artifact tables. A room now holds work if it holds a `GroupSignedEvent` of a chroniclable kind, and with none chroniclable it never asks |
| the chronicle's tests re-fixture | three of four did, with `TEST_KINDS` (a relay list, a profile, a post -- group-signed kinds whose arrival writes no row of its own, so the `GroupSignedEvent` rows are the observable). `ChronicleRoundTripTest` was entirely the rebuild through document rows and is deleted. The positive invite push could not take a test set -- `deliveryWelcome` calls `sendTo` with the app's kinds -- and became the "sends nothing" case |
| `SignedGroupKeyStateTest` and `GroupSignedEventDaoJvmTest` re-fixture in Phase 8 | Phase 7: they failed at runtime the moment document arms stopped writing rows. They sign posts now, and "the row a signed event becomes points back at it" is deleted -- no kept kind writes a row |
| a migration test with tables copied from `26.json` | built from the exported `26.json` at run time and compared with `27.json`, so it cannot drift from what Room expects |
| `CLAUDE.md`'s literal count goes to 20 | 19, after Phase 5 took another |
| 61 strings plus four already unused | 65 in all: the string catalogue test's two chapter strings went once it checked its escapes on `recovered_of_still_unreadable` instead |

**Checked on the three emulators that found the bug**, upgraded in place from
v26 with their data. The migration ran on each: v27, no document tables, and the
raw-hex bubbles gone from Bob's and Charlie's `#Checkers`. Bob abandoned the
stray whole-room ceremony `8199d186` from its screen and it ended on all three;
its **Try again** led to the creation screen's Marmot-room refusal. Then
Tapscript (`89d4c4d0`) ran to completion: Bob and Charlie each approved round
one and came back to its screen with no start button on it -- the step where
Charlie had opened the second ceremony -- and all three confirmed round two and
hold the same key. No new ceremony, no `unsupported` line, and no chronicle
request, page or line on any device. The NIP-17 `Checkers` group's **Shared
key** handed over to its finished ceremony, and back returned to its details.
Not exercised on a device: the certificate and key state after Tapscript, and
starting a ceremony from `CreateSharedKeyScreen` in a group without a key.

**A regression Phase 6 shipped, and its fix.** `FrostSigningEvents.DECLINE` and
`MarmotDirectMessage.ENVELOPE_KIND` are both 30330. Phase 6 returned early for
every protocol kind before the direct-message branch, so every direct message in
a current-profile room was dropped unopened. Found while mapping this fix onto
Curare. An event of kind 30330 that holds a gift wrap is now an envelope, never a
decline -- the test `MarmotDirectMessage.wrapInside` already made -- and
`ProtocolKindDispatchJvmTest` opens one. Any port of Phase 6 has to carry this.

The headline: **a ceremony is started only from a screen that knows what it is
for, and the screen that shows a ceremony can never start one.** Today
`DkgRitualScreen` does three jobs (showing progress, asking for approvals, and
starting a ceremony) and puts the button for the first and the last in the same
place. Separating them makes the bug impossible, not just this path to it. A
guard in `proposeRitual` makes it impossible a second time, in case a future
screen gets it wrong.

---

## What happened

Three emulators, all admins of `#Checkers` (`639f795d…`, a Marmot room): Bob
(`44d9ed79`), Charlie (`73015ef7`) and Medium (`e51b5720`). Bob made a subgroup
called *Tapscript* over all three of them, 2-of-3. From each device's logcat and
`aux.db`:

| time | device | what |
|---|---|---|
| 13:17:00 | Bob | proposes `89d4c4d0`, 2-of-3, `parent_group` = `#Checkers`, subject *Tapscript* |
| 13:17:00 | Charlie, Medium | record it, awaiting approval |
| 13:17:23 | Medium | approves joining; 2 of 3 host keys |
| 13:17:35 | Charlie | approves joining; now 3 of 3 host keys |
| 13:17:39 | Charlie | **proposes `8199d186`**: 2-of-3 over the whole room, **no `parent_group`**, subject `#Checkers` |
| 13:17:40 | Bob, Medium | record `8199d186` as a new ceremony awaiting approval |
| 13:17:53 | Medium | approves round 1 of Tapscript |
| 13:18:03 | Medium | approves joining `8199d186` |

Tapscript never failed. All three devices hold all three host keys, Medium has
sent its round-1 contribution, and Bob and Charlie are each waiting for their own
round-1 approval. No approval threw on any device. The "errors" seen while
joining and confirming were screens showing the wrong state, and one chat line:
on Bob and Charlie, Medium's round-1 contribution also appears as an
`unsupported` bubble of raw hex (`ChatMessage` rows 66 and 62), because kind
30312 is read as a nip30303 `SubmissionEvent` first. That is the
`Leading zeroes not allowed` warning in both logs at 13:17:53.

`8199d186` could only have come from `DkgRitualViewModel.startRitual()`. It is
the one caller of `proposeRitual` that passes no participants, no parent and no
subject, which is what produces a whole-room ceremony named after the room.
Charlie pressed **Start key ceremony** four seconds after approving, in the
place where **Review and join** had just been.

---

## The findings

| # | finding | where | fixed in |
|---|---|---|---|
| F1 | `proposeRitual` will open a group's own ceremony in a Marmot room. The key it makes can never be that room's, since a Marmot room's id is derived from the key it already has. `ChatRoomDetailScreen` hides the button there; nothing refuses the call | `ChillDkgRitualManager.kt:178` | Phase 1 |
| F2 | `DkgRitualScreen` shows progress, asks for approvals *and* starts ceremonies, and the approval button and the start button share one slot in the bottom bar | `DkgRitualScreen.kt:194-262` | Phases 3, 4 |
| F3 | `initiate()` runs again every time the screen comes back into view, because its `LaunchedEffect` checks the *parameter* `initialDkgRitualUIState`, which is always `Loading`. Each run resets the state to `Loaded(session = null)` and starts another set of collectors without stopping the last | `DkgRitualScreen.kt:344`, `DkgRitualViewModel.kt:136` | Phase 2 |
| F4 | Five coroutines on `Dispatchers.IO` each read `dkgRitualUIState`, copy one field and write it back. The session collector suspends on a database query between its read and its write. A write based on an old copy wipes out `session` and `pendingApproval`, and nothing re-sends them | `DkgRitualViewModel.kt:174-235, 251-347` | Phase 2 |
| F5 | `roomHasCeremony` counts only the room's *own* ceremonies (`observeLatestSessionForChatRoom` defaults `parentChatRoomId` to null), so in a parent room hosting a subgroup's ceremony it says there is none | `DkgRitualViewModel.kt:175` | Phase 4 (deleted) |
| F6 | The approval button calls `approve(step)` and then `onDone()` straight away. Popping the screen clears its view model and cancels the approval's coroutine, so a slow publish can be cut off. On the last approval of a round nothing arrives afterwards to resume it | `DkgApprovalScaffold.kt:205` | Phase 2 |
| F7 | A failed ceremony's **Try again** calls `startRitual()`, which opens a group's own ceremony whatever the failed one was for. A failed subgroup ceremony is retried as a whole-room ceremony in the parent's room, which is F1 again | `DkgRitualScreen.kt:239-251` | Phase 4 |
| F8 | There is no way to abandon a ceremony. `DkgApprovalScaffold.kt:191-195` says "Abandoning is on the ritual screen"; nothing implements it. A ceremony opened by mistake, like `8199d186`, can only be left running | `DkgApprovalScaffold.kt:191` | Phase 5 |
| F9 | `record()` accepts a FAILURE message from any sender. In a parent room, a member who is not in the subgroup can read its proposal and end it | `ChillDkgRitualManager.kt:636` | Phase 5 |
| F10 | In a Marmot room, `ChatMessage.applyInnerEvent` matches `TranslationContributorListEvent.KIND` (30310) and `TranslationEvent.KIND` (30311) before `in DkgRitualEvents.ALL`. A subgroup's proposal and host keys reach the translation arms first. It does nothing today only because one arm is an empty TODO and the other's parser returns null without translation tags | `ChatMessage.kt:1439-1493` | Phase 6 |
| F11 | Kind 30312 is both the ceremony's round 1 and nip30303's `SubmissionEvent`. `ChatMessage.fromGroupEventResult` checks for a submission *before* `applyInnerEvent` runs, tries to parse the round-1 hex as an event, fails, and writes an `unsupported` chat line with the raw hex. Reordering `applyInnerEvent` does not reach it. Seen on Bob and Charlie | `ChatMessage.kt:880, 986-1012` | Phase 6 |
| F12 | The comments that call the kind overlap safe are out of date: they say ceremony kinds only travel in NIP-17 gift wraps, which stopped being true with subgroups' Phase 10 | `FrostSigningEvents.kt:52-70`, `ChronicleEvent.kt:89-101`, `DkgRitualEvents.kt:15` | Phase 6 |
| F13 | The nip30303 document and translation feature is unreachable: nothing constructs `ArtifactDetailRoute`, `AddArtifactRoute` or `AddDialectRoute`, and the other six routes are only built from inside those screens. It is still wired into inbound dispatch, `ProposedEvent`, the chat transcript and 12 database tables | `nostr/nip30303/` and below | Phases 7, 9 |
| F14 | The member chronicle can carry only nip30303 kinds (`ChronicleEvent.APPLY_ORDER`), yet it is live: every room asks for one when it opens (`ChatMessageListViewModel.kt:206`) and every invite sends one (`MarmotOutboundDao.kt:1086`). Without nip30303 it has nothing it is allowed to carry | `managers/ChronicleManager.kt`, `nostr/chronicle/` | Phase 7 |

---

## Phase 0 — before any code

**An hour, to settle five things the phases assume.**

1. **Ceremonies start in exactly three places.** Group creation
   (`SelectChatRoomTypeViewModel`) and the subgroup picker
   (`SelectSubgroupAdminsViewModel`) already know what their ceremony is for and
   stay as they are. The third is new: `CreateSharedKeyScreen`, for a NIP-17
   group that has no key yet. `DkgRitualScreen` starts nothing.
2. **`DkgRitualRoute.dkgSessionId` becomes required.** Every caller that has a
   ceremony to show knows which one it is. A caller that does not is asking to
   *start* one, and goes to the creation screen instead.
3. **Abandoning is open to any participant, and only to participants.** ChillDKG
   has no way to say no short of ending the ceremony, and any participant can
   already end it by never approving. Making it explicit just lets the others
   stop waiting.
4. **The whole nip30303 family goes, not only translation.** Artifacts,
   versions, chapters, chunks, dialects and translations share one entry point,
   and it is unreachable. Removing only the translation kinds would leave the
   rest of the screens and the database tables behind them, and would leave
   30312 colliding with round 1.
5. **The member chronicle stays, empty and quiet.** It was built to hand a late
   member the group's signed document history, and `CHRONICLABLE_KINDS`
   deliberately excludes everything else (posts, profiles, relay lists, key
   states, subgroup certificates, wallet statements). With nip30303 gone it
   carries nothing. Its machinery (paging, verification against the room's
   key, the request and the push) is kept for the next kind that should join
   it, and the set it may carry becomes empty. "Quiet" means that with an empty
   set it puts nothing on the wire and nothing in the transcript: no request
   when a room opens, no pages on an invite, no reply to a request from an
   older build. Adding a kind back is a decision that set's comment asks for,
   not a side effect.

---

## Phase 1 — `proposeRitual` refuses a group's own ceremony in a Marmot room

**An hour. Closes F1 whatever any screen does.**

In `ChillDkgRitualManager.proposeRitual`, beside the existing `require`s:

```kotlin
// A Marmot room's id is derived from the key it already has, so a ceremony of
// its own would make a key the room could never answer to. It hosts its
// subgroups' ceremonies and nothing else.
require(parentChatRoomId != null || localChatRoom.chatRoom.mlsGroupState == null) {
    "${localChatRoom.chatRoom.id} is a Marmot room; it can only host a subgroup's ceremony"
}
```

It goes *after* the "already running" lookup, like the other `require`s, so a
caller who asks again for a running ceremony still gets it back.

`acceptProposal` gets the matching refusal: a proposal with no `parent_group`
arriving in a Marmot room is dropped with a warning and no session is recorded.
Without it, a build that predates this phase can still put one on the wire and
every member's device would show it.

**Tests**, in `SubgroupCeremonyInParentRoomTest` (it already builds a parent
Marmot room with `parentRoom()`):

- proposing with no parent in a Marmot room throws, and writes no session and
  no inner event;
- a proposal with no `parent_group` arriving in a Marmot room records nothing;
- proposing a subgroup in the same room still works (the existing tests cover
  this; check they still pass).

---

## Phase 2 — the ceremony screen stops losing its ceremony

**Half a day. Closes F3, F4 and F6.**

### `initiate()` runs once

`DkgRitualViewModel` gets an `initiated` flag that `initiate()` checks and sets
first. `DkgRitualScreen`'s `LaunchedEffect` stays as it is; it is the view model
that decides whether starting again means anything. Returning from an approval
screen then reuses the collectors already running, and the state is never reset
to "no session".

### One writer

Replace the five read-copy-write collectors with one state built by `combine`:

```kotlin
combine(
    sessionFlow,                     // observeSessionById(dkgSessionId)
    dkgRepository.observeMessages(dkgSessionId),
    keyStateFlow,
    certificateFlow,
) { session, messages, keyState, certificate -> … }
    .mapLatest { parts -> parts.toLoaded(pendingApproval = dkgRepository.pendingApproval(parts.session)) }
    .collect { dkgRitualUIState = it }
```

`pendingApproval` is computed inside the pipeline, before the single write, so
there is no suspension between reading the state and writing it.
`adminGroupBlockedOn` is the one field set by an action rather than a flow; it
becomes a `MutableStateFlow` that is a fifth input to `combine`.

This is easier once Phase 4 makes `dkgSessionId` required, because the
"which session" question disappears. The two phases can be done in one commit
if that reads better; the order here keeps Phase 2 small enough to land first.

### Approving finishes before the screen closes

`DkgRitualViewModel.approve(step, onApproved: () -> Unit)` calls `onApproved` on
`Dispatchers.Main` after `dkgRepository.approve` returns. `DkgApprovalScaffold`
passes `onDone` there instead of calling it straight after. The button already
shows a spinner while `isActionPending` is true, so the member sees the
approval being sent rather than a screen that closed and might not have.

**Tests**, a new `DkgRitualViewModelJvmTest` beside the other view model tests
in `jvmTest/.../ui/view/model/`:

- calling `initiate()` twice leaves one set of collectors and never emits a
  state with `session == null` after the first non-null one;
- a burst of message inserts while the session row changes ends with the state
  holding both the session and the messages (the lost-update case);
- `approve` calls `onApproved` only after the repository call returns.

---

## Phase 3 — `CreateSharedKeyScreen`

**A day. The first half of F2.**

A screen that does one thing: start a group's own key ceremony in a NIP-17 room
that has no key and no ceremony running.

- `CreateSharedKeyRoute(activeUserPublicKey, chatRoomId)`.
- `CreateSharedKeyViewModel` takes over from `DkgRitualViewModel` the parts that
  are about starting: `threshold`, `quorumRange()`, `setThreshold()`, the
  minimum-participants check, and `startRitual()` as `create()`.
- `CreateSharedKeyScreen` shows what `DkgRitualScreen` shows today when
  `session == null`: the two paragraphs about holding one key between the group
  and needing everyone online, the `QuorumStepper`, and one button,
  **Start key ceremony**. Its app bar has `NavigateBackButton`.
- The four states: loading while the room loads; an `ErrorState` with a retry
  if the room cannot be read; an `EmptyState` saying why not when the group is
  too small or is a Marmot room; loaded otherwise.
- The view model refuses, rather than hides, the cases Phase 1 refuses, so the
  screen can say why. A room that already has a ceremony running sends the
  member to that ceremony instead of offering a second.
- On success it navigates to `DkgRitualRoute(chatRoomId, dkgSessionId)` with
  `popUpTo<CreateSharedKeyRoute> { inclusive = true }`. Back from the new
  ceremony goes to wherever the member came from, never to the creation screen.

**Entry points:**

- `ChatRoomDetailScreen`'s **Shared key** button (NIP-17 rooms only, as now).
  If the room has its own ceremony that has not failed, it opens that one with
  `DkgRitualRoute(…, dkgSessionId = it.id)`. Otherwise it opens
  `CreateSharedKeyRoute`. The detail view model reads this with
  `getLatestSessionFor(chatRoomId, parentChatRoomId = null)`, which is the
  right question here because a NIP-17 room hosts no subgroups.
- The error card `SelectChatRoomTypeScreen` shows when a robust group was
  created but its ceremony was not (`keyCeremonyNotStarted`,
  `was_created_but_its_shared_key_ceremony`) gets a button to
  `CreateSharedKeyRoute` for the room it just made. Today the only way back is
  through the group's details, which the card does not mention.

Strings go in `strings.xml` in sentence case. Spacing comes from
`MaterialTheme.spacing`, the content root gets `readableContent()`, and
`./gradlew :composeApp:m3Audit` passes with no budget raised.

**Tests**: `CreateSharedKeyViewModelJvmTest` — a NIP-17 room with no ceremony
can create one; a room with one running is sent to it; a Marmot room and a
one-member room are refused with a reason.

---

## Phase 4 — `DkgRitualScreen` only shows a ceremony

**Half a day. The second half of F2, and F5 and F7.**

- `DkgRitualRoute.dkgSessionId` becomes non-null. The two callers left
  (`ChatRoomMessagingScreen`'s transcript links and the detail screen's
  **Shared key** from Phase 3) already pass one. A transcript line that somehow
  has no `dkgSessionId` shows no link rather than opening the room's newest.
- `DkgRitualViewModel` loses `startRitual()`, `canStartRitual()`, `threshold`,
  `quorumRange()`, `setThreshold()` and the fallback to
  `observeLatestSessionForChatRoom`. `DkgRitualUIState.Loaded` loses
  `roomHasCeremony` and its comment, and `session` becomes non-null once
  loaded. A session id that matches no row is the error state, not an
  invitation to start one.
- `DkgRitualScreen` loses the `session == null` branch and the start button.
  Its bottom bar has one job: the pending approval, when there is one.
- The app bar title comes from the ceremony, not the room: a subgroup's ceremony
  in `#Checkers` reads as *Tapscript*, not *Shared key for #Checkers*.
- **Try again** on a failed ceremony goes back to the screen that knows what it
  was for:
  - a group's own ceremony → `CreateSharedKeyRoute(chatRoomId)`;
  - a subgroup's → `SelectSubgroupAdminsRoute(parentChatRoomId, retrying = dkgSessionId)`.
    `SelectSubgroupAdminsViewModel` fills in the name, the admins and the
    quorum from the failed session (`subject`, `participantPublicKeys` minus
    this device, `threshold`). The member can change any of them before
    confirming, and the usual refusals run again.
- `SelectSubgroupAdminsViewModel.kt:263-276`, the comment about the coordinator
  landing on a **Start key ceremony** button, is shortened to say why the
  transcript is still the right place to land: that is where every admin
  answers from.

After this phase `proposeRitual` has exactly three callers, which is the
promise in the title. A test that greps `commonMain` for `proposeRitual(` and
counts three, with a comment naming them, is cheap and fails the day a fourth
appears without anyone deciding it should.

**Tests**: extend `DkgRitualViewModelJvmTest` — the view model has no way to
start a ceremony; a failed subgroup ceremony's retry route carries the parent
and the session; a failed group ceremony's goes to the creation screen. Extend
the subgroup picker's tests with prefilling from a failed session.

---

## Phase 5 — abandoning a ceremony

**Half a day. Closes F8 and F9.**

- `ChillDkgRitualManager.abandon(database, localChatRoom, sessionId,
  nostrPrivateKey)`: refuses unless this device is a participant and the
  ceremony is neither complete nor failed; then calls `fail(...)` with this
  device as the culprit and broadcasts `DkgRitualEvents.FAILURE`, exactly as a
  local fault does today. `DkgRepository.abandon` wraps it.
- `record()`'s FAILURE arm ignores a sender who is not in
  `participantsOf(session, room)`, with a warning like `isFromCoordinator`'s.
- `DkgRitualScreen` gets **Abandon ceremony** as a secondary action, shown while
  the ceremony is running and this member is a participant. It opens a
  confirmation dialog saying what happens: the ceremony ends for everyone, no
  key is created, and it can be started again. That matches what the existing
  `TYPE_DKG_FAILED` line already tells the room.
- `DkgApprovalScaffold.kt:191-195` then describes something that exists. The
  approval screens keep **Not now** and do not get an abandon button: the
  consequence belongs on the screen that shows the whole ceremony.

**Tests**, in `SubgroupCeremonyInParentRoomTest`: a participant's abandon fails
the session on every device; a FAILURE from a parent member outside the
subgroup is ignored; abandoning a completed ceremony does nothing.

**On the test devices**, once this lands: abandon `8199d186` from any of the
three. Tapscript (`89d4c4d0`) is untouched and finishes once Bob and Charlie
approve round 1. Until this lands, do not approve `8199d186` on Bob. Medium has
already joined it, so Bob's approval would complete a key for `#Checkers` that
the room can never use.

---

## Phase 6 — the kind overlap, closed first

**Two hours. Closes F10, F11 and F12 before the removal, so the removal is only
deletion, and so the fix can ship on its own if the removal waits.**

- In `ChatMessage.fromGroupEventResult`, decide on protocol kinds first: an
  inner event whose kind is in `DkgRitualEvents.ALL` or `FrostSigningEvents.ALL`
  is stored as a `MarmotInnerEvent` as now, never treated as a submission
  envelope, and produces no chat line. Their managers are dispatched separately
  from `NostrDao.fileGroupEventResult` and write their own transcript lines. A
  real `SubmissionEvent` loses its envelope handling until Phase 7 removes it;
  nothing can send one, because the screens that did are unreachable.
- In `ChatMessage.applyInnerEvent`, move `in FrostSigningEvents.ALL -> null` and
  `in DkgRitualEvents.ALL -> null` to the top of the `when`. Protocol kinds are
  never content, whatever else shares their number, and the first match wins.
- Rewrite the comments in `FrostSigningEvents.kt`, `ChronicleEvent.kt` and
  `DkgRitualEvents.kt` to say what is true now: ceremony kinds travel as Marmot
  inner events in a parent room, and they stopped colliding when the dispatch
  order above was fixed. After Phase 7 they shrink again, to say 30300-30309 are
  free and 30310-30316 are the ceremony's alone.
- Lower `NostrDao.fileGroupEventResult`'s `No app logic to handle` from info to
  debug, or skip it for ceremony and signing kinds. It reads like a failure on
  every application message and sent this investigation the wrong way.
- **Tests**, in a new `ProtocolKindDispatchJvmTest`: every kind in
  `DkgRitualEvents.ALL`, arriving as a Marmot application message with a real
  ceremony payload (round 1's hex content in particular), writes no chat line
  and no row outside the DKG tables; the same for `FrostSigningEvents.ALL`.

The `unsupported` rows already on devices are cleaned up by Phase 8's
migration, which can find them by kind.

---

## Phase 7 — removing the nip30303 code

**A day and a half: a day of deletion, and half a day giving the chronicle's
tests a kind set of their own. Closes F13 and F14 in code; the tables go in
Phase 8.**

Paths below are under `composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/`
unless they say otherwise. Line numbers were right at `e3839da5`; confirm them
with a grep.

### Deleted outright (103 files)

| what | where | files |
|---|---|---|
| the 14 event classes and their 22 tags | `nostr/nip30303/`, `nostr/nip30303/tags/` | 36 |
| the 12 `Mantra*` entities, plus `MantraTranslationChunkProposal`, which is annotated `@Entity` but never registered | `database/model/Mantra*.kt` | 13 |
| the 12 table DAOs and `MantraDao` | `database/dao/Mantra*Dao.kt` | 13 |
| `MantraRepository`, `DatabaseMantraRepository` | `repository/`, `database/repository/` | 2 |
| `TranslationScaffold` | `managers/` | 1 |
| nine screens, nine view models, nine UI states, nine routes: AddArtifact, AddChapter, AddDialect, AddTranslationArtifactVersion (route `AddTranslation`), ArtifactDetail, ChapterDetail, TranslateChunk, TranslationArtifactVersionDetail, TranslationChapter | `ui/composable/`, `ui/view/model/`, `ui/view/state/`, `ui/composable/navigation/routes/` | 36 |
| helpers nothing else uses: `Markdown.kt`, `DetailRow.kt` | `text/`, `ui/composable/widgets/` | 2 |

### Edited

- **`DedunNavHost.kt`**: the feature's imports, `databaseMantraRepository`,
  the `mantraRepository =` argument in the chat room detail block, and the
  `composable<>` blocks for the nine routes.
- **`ChatRoomDetailScreen.kt`, `ChatRoomDetailViewModel.kt`**: the
  `mantraRepository` parameter and field, which is stored and never read, and
  the preview's `NO_OP_MANTRA_REPOSITORY`.
- **`ChatMessage.kt`**:
  - the nip30303 imports;
  - the submission envelope in `fromGroupEventResult` (`submission`, `payload`,
    `payloadEventId = payload?.id`, and the `unsupported` branch, whose `else`
    becomes a plain `applyInnerEvent(event = event)`);
  - the eleven nip30303 arms of `applyInnerEvent` and the KDoc above it.
  - The `messageType`s those arms wrote (`artifact`, `chapter`, `dialect`,
    `translationArtifactVersion`, `translation`) have no renderer; Phase 8
    deletes any rows of them.
- **`text/ProposedEvent.kt`**: the seven nip30303 arms of `summaryOf`. The rest
  (key state, subgroup certificate, metadata, text note, relay lists, wallet)
  stays.
- **`MarmotInnerEventDao.deleteByPayloadEventId`**: deleted; it has no callers.
  The `payloadEventId` column goes in Phase 8.
- **`strings.xml`**: the 61 keys only the deleted files use (59, plus
  `add_chapter_to` and `chapter_words_characters` once `StringCatalogueJvmTest`
  stops asserting them), and four feature strings that are already unused:
  `add_artifact_to_library`, `dialects`,
  `no_artifacts_exists_in_this_groups_library`,
  `no_dialects_have_been_defined_in_this_group_2`. Keep `direct_message_detail`
  and `direct_message_functionality_will_be_here`, which chat screens share.
- **Leave alone**: the `mantra/` prefix in `SharedKeyDerivation.kt:74` and
  `ChillDkgRitualManager.kt:101`, which is hashed into derived keys and must
  never change; the relay host in `Relays.kt`; preview and sample text that
  only *says* "translation" (`ProposalListScreen` previews, "Translation room"
  subjects), which uses no removed type.

### Tests

- **Delete** the twelve that test only the feature:
  - commonTest: `nostr/nip30303/{ArtifactEventTest, ChapterChunkSplitTest, InitialArtifactVersionTest, SubmissionEventTest}`, `managers/{TranslationScaffoldTest, SignedArtifactTest, SignedChapterTest}`, `database/model/RumorIdAgreementTest`;
  - jvmTest: `database/dao/MantraDaoJvmTest`, `database/model/TranslationChunkApplyJvmTest`, `managers/{ChapterBatchProposalJvmTest, TranslationBatchProposalJvmTest}`.
- **Re-fixture** the ones that used a nip30303 event as an arbitrary payload:
  - `GroupKeyStateTest` and `GroupSignedEventDaoJvmTest` need any kind;
    `TextNoteEvent` or a relay list works.
  - `SignedGroupKeyStateTest` (tests at 1046 and 1221) checks that a batch the
    group signs lands on every device. It needs a kind that writes a row on
    arrival, and `GroupWalletStatementEvent` or the relay lists do.
  - `MarmotPayloadBoundsTest`'s chronicle cases (at 75 and 88) size a page of
    nip30303 events; they re-fixture with the test kind set described under
    "The chronicle, emptied and quiet" below.
- **Edit** `ChatRoomMemberRowJvmTest`, `GroupSignedWorkRowsJvmTest` (the
  `NO_OP_MANTRA_REPOSITORY` argument) and `StringCatalogueJvmTest` (the two
  chapter strings).

**Audit**: neither floor moves; the deleted screens hold none of the 12
adaptive uses or the 2 navigation components. The unbudgeted string-literal
count drops from 29 to 20; update the number in `CLAUDE.md`'s "39 remain" line
while there, which is already stale.

### The chronicle, emptied and quiet

Kept, per Phase 0 decision 5. What changes is what it may carry, and that it
says nothing when the answer is nothing.

- **`ChronicleEvent.APPLY_ORDER` becomes empty**, and with it
  `CHRONICLABLE_KINDS`. Its comment says why: the document kinds it was built
  for were removed by this plan, everything else is excluded for the reasons
  already listed there, and a kind joins only by a decision that edits this
  list. `applyPage`'s and the outbound filter's existing checks then refuse
  everything without any change of their own, including a page from an older
  build that still carries document kinds.
- **`ChronicleManager.requestIfEmpty` returns false while that set is empty**,
  before it queues anything, stamps `chronicleRequestedAt` or writes a
  transcript line. Its "holds work" check (`ChronicleManager.kt:169-170`) reads
  the Mantra DAOs and goes; the empty-set check replaces it. Opening a room then
  sends nothing, and `ChatMessageListViewModel.askForGroupHistory` needs no
  change.
- **`sendTo` needs no change.** It already sends nothing and writes no line
  when `assemble` comes back empty ("silence is the honest reply"), which is
  what an empty set makes it do. That covers the push on every invite
  (`MarmotOutboundDao.kt:1086`) and the reply to a request from an older build
  (`ChatMessage.kt:972`). Phase 7's tests assert both stay silent.
- **Removed** from `ChronicleManager`: the rebuild fallback that reads the Mantra
  tables (`rebuiltEventsOf`, 748-811) and `currentTranslationsOnly` (657-718),
  which exists only for translation chunks. `signedEventsOf` stays; it reads
  `GroupSignedEvent` by kind and will find nothing.
- **Kept as they are**: `ChronicleRequestEvent`, the three chronicle tags,
  `TYPE_CHRONICLE_*` and `CHRONICLE_TYPES`, `ChatRoom.chronicleRequestedAt`,
  and the comments that say why a kind is *not* chronicled. All of them still
  describe the machinery accurately. Rows already written stay too.
- **Tests.** `ChronicleEventTest`, `ChronicleRoundTripTest`,
  `ChronicleApplyJvmTest` and `ChronicleAssemblyJvmTest` exercise paging,
  verification and ordering with document events as the payload. With an empty
  production set they have nothing to page. So `ChronicleEvent` and
  `ChronicleManager` take the chroniclable kinds as a parameter, defaulting to
  the production set, and those tests pass a test set (`TextNoteEvent` and a
  relay list, say) built in a shared fixture. That keeps the machinery covered
  without making any kind chroniclable in the app. Assertions of the count
  (`ChronicleEventTest:195` expects 8; `ChronicleRoundTripTest:418`,
  `ChronicleApplyJvmTest:801`, `ChronicleAssemblyJvmTest:336, 511`) assert
  against the test set, and one new test asserts the production set is empty.
  The other new tests: `requestIfEmpty` sends nothing and writes no line;
  `sendTo` on an invite sends nothing and writes no line; a page from an older
  build carrying document kinds applies nothing.

---

## Phase 8 — schema 26 → 27

**Half a day, most of it the migration test.**

One manual `MIGRATION_26_27` in `database/migrations/RemoveDocumentsMigration.kt`,
registered in `getRoomDatabase` beside the other four. Manual rather than an
`AutoMigration`, because it deletes chat rows as well as changing shape, which
is the line that file's comment already draws.

```sql
-- the chat lines the removed arms wrote, and the ceremony round-1 lines
-- misread as submissions (F11)
DELETE FROM ChatMessage WHERE messageType IN
  ('artifact', 'chapter', 'dialect', 'translationArtifactVersion', 'translation');
DELETE FROM ChatMessage WHERE messageType = 'unsupported' AND marmotInnerEventId IN
  (SELECT id FROM MarmotInnerEvent WHERE kind BETWEEN 30310 AND 30316);

DROP TABLE MantraArtifact;  -- … and the other eleven, children before parents
ALTER TABLE MarmotInnerEvent DROP COLUMN payloadEventId;
```

The chronicle's column (`ChatRoom.chronicleRequestedAt`) and its chat lines
stay, since the chronicle does.

- The table names come from `composeApp/schemas/…/26.json`; check them there,
  not here. `MarmotInnerEvent.kind` is the column the second `DELETE` reads.
- `DROP COLUMN` is safe because `getRoomDatabase` pins `BundledSQLiteDriver` on
  every platform, the argument `ChronicleRenameMigration` already makes for
  `RENAME COLUMN`. Check first that `payloadEventId` is not indexed or part of
  a foreign key, since SQLite refuses to drop a column that is.
- No kept table has a foreign key into a Mantra table, so dropping them cascades
  into nothing that stays. Drop children before parents anyway, so the order
  holds if foreign keys are ever enforced during migration.
- `GroupSignedEvent` keeps any rows of kinds 30300-30309. The table is generic
  and keyed by kind, nothing reads those kinds any more, and deleting a group's
  signed record is not this plan's call.
- `DedunDatabase`: `version = 27`, the twelve entities and thirteen DAO
  accessors removed, and the migration-history comment gets a line for 26 → 27.
  Commit `27.json`.

**Test**, `RemoveDocumentsMigrationJvmTest`, in the same style as the three
migration tests already there: the version-26 tables created by hand from
`26.json`, seeded with a Mantra row, a `translation` chat line, an `unsupported`
line pointing at a kind-30312 inner event, and an `unsupported` line pointing at
some other kind. After the migration, the Mantra tables and `payloadEventId`
are gone, `chronicleRequestedAt` is still there, the first two lines are
deleted, and the last one is still there.

---

## Phase 9 — docs, and checking it on the emulators

**Half a day.**

- `shared-key-ceremony.md`: a section on where a ceremony can be started (the
  three places), on abandoning, and the kind table noting that 30310-30316 now
  travel in both transports.
- `subgroups.md`: a line under Phase 10 pointing here for the retry path and the
  guard. Its chronicle mentions stay true: a subgroup certificate is still not
  chroniclable.
- `member-chronicle.md`: a status line at the top saying the chronicle is built
  but carries no kinds since this plan removed the document kinds, that it is
  silent while that is so, and that adding a kind means editing
  `ChronicleEvent.APPLY_ORDER` on purpose. `docs/README.md:39` already
  contradicts the note's status (it says the chronicle was never built); fix
  both together.
- `frost-batch-signing.md` (its first caller was a chapter and its chunks,
  519-546) and `mls-skipped-keys.md` (`MantraDao.addArtifact`): a line each
  saying the example was removed, rather than rewriting history.
  `marmot-v2.md` and `group-chain-wallet.md` mention the chronicle, not the
  documents, and need nothing.
- `material-design-conformance.md`: the screen count (43) and the
  "artifacts → chapters" list-detail family.
- `docs/README.md`: a row for this note, and its place in the reading order
  (after the subgroups note).
- This note: mark it built, and add a table of where the build chose
  differently, as the other plans do.

Then the scenario that found the bug, on the same three emulators:

1. Bob makes a subgroup over all three admins of a parent room.
2. Charlie and Medium each open it from the transcript, approve joining, and
   land back on the ceremony screen. It shows the subgroup's ceremony and
   offers **Review and contribute** once all host keys are in, and there is no
   start button anywhere on it.
3. All three approve round 1 and round 2; the ceremony completes; the
   certificate and key state follow as `subgroups.md` describes.
4. In a NIP-17 group without a key, **Shared key** from the group's details opens
   `CreateSharedKeyScreen`, and starting a ceremony there lands on its progress
   screen, with back returning to the details.
5. Abandon a ceremony from one device and watch it end on the other two.
6. No `unsupported` bubble appears in the parent room at any point, and the
   two left over from the original run (Bob and Charlie) are gone after the
   upgrade.
7. Inviting a member to a group and opening a room both work, and neither
   puts a chronicle request, a chronicle page, or a chronicle line in the
   transcript on any device (Phase 7's quiet shell).

`./gradlew check` passes, which runs the m3 audit.

---

## What this does not do

- **It does not change the protocol.** No new kinds, no new tags; the FAILURE
  message `abandon` sends is the one a fault already sends.
- **It does not migrate ceremonies already on devices.** A whole-room ceremony
  already recorded in a Marmot room, like `8199d186`, stays until it is
  abandoned. Phase 1 stops new ones being opened or recorded.
- **It does not touch the group's signed record.** Rows of kinds 30300-30309
  in `GroupSignedEvent` stay; see Phase 8.
- **It does not remove the member chronicle.** Its machinery stays, carrying
  nothing and saying nothing, until a kind is chosen to join it.
- **It does not free the `mantra/` derivation prefix.** It is an input to the
  host-key and shared-key hashes, not a reference to the removed feature.

---

## Order and size

| phase | what | size | depends on |
|---|---|---|---|
| 0 | decisions | an hour | — |
| 1 | `proposeRitual` guard | an hour | — |
| 2 | ceremony screen keeps its ceremony | half a day | — |
| 3 | `CreateSharedKeyScreen` | a day | 1 |
| 4 | ceremony screen only shows | half a day | 2, 3 |
| 5 | abandoning | half a day | 4 |
| 6 | protocol kinds dispatched first | two hours | — |
| 7 | remove nip30303 code, quiet the chronicle | a day and a half | 6 |
| 8 | schema 26 → 27 | half a day | 7 |
| 9 | docs and emulator check | half a day | all |

Phases 1, 2 and 6 are independent and the most urgent; each can ship alone.
Phases 3-5 are one line of work on the ceremony screens, and 7-8 another on the
removal, and the two lines touch different files apart from `ChatMessage.kt`
(Phase 6 against Phase 7), so they can run side by side once Phase 6 is in.
