# Joining is the only approval

A plan to make a key ceremony run by itself once a member has joined it. The
member decides one thing, whether to take part, and their device does everything
after that: it contributes to the key, checks the combined result, confirms it,
and keeps its share. The coordinator's device also takes the follow-up steps that
leave the group with a working room. **Abandon** stays, because the one stall
automation cannot fix is a member who joined and then went quiet.

Read [shared-key-ceremony.md](./shared-key-ceremony.md) first, especially
*Nothing publishes without approval*, which this plan mostly undoes. Read
[subgroups.md](./subgroups.md) for the four steps after a subgroup's ceremony,
since Phases 6 and 7 automate three of them.

**Not built.** Written against the code at `d4dd87f2`, on
`claude/automate-key-ceremony-803bb9`.

The headline: **the device asks its owner only for decisions the device cannot
make on its own.** Joining is one of those decisions. It puts the member in a
group's signing quorum for good, and nothing on the device can decide that for
them. Contributing and confirming are not decisions. Contributing follows from
having joined. Confirming is a check the device already runs, and a person
tapping **Confirm** adds nothing to it. The round-2 screen says as much itself:
*"Your device has checked the combined result against what it sent, and they
match."*

---

## What a member is asked today

A group's own ceremony with `n` members. A subgroup's ceremony adds the rows
marked *(subgroup)*.

| # | step | whose | today | after this plan |
|---|---|---|---|---|
| 1 | open the ceremony | coordinator | button on `CreateSharedKeyScreen` or the subgroup picker | unchanged: it is a decision |
| 2 | publish host key (join) | each other participant | `DkgJoinApprovalScreen` | **unchanged: the one approval** |
| 3 | publish round-1 contribution | every participant, coordinator included | `DkgRound1ApprovalScreen` | automatic once every host key is in |
| 4 | combine the contributions (`cmsg1`) | coordinator | automatic | automatic |
| 5 | publish the CertEq signature (round 2) | every participant | `DkgRound2ApprovalScreen` | automatic. If the device's check fails, the ceremony fails and says so, as it does today |
| 6 | certificate (`cmsg2`), finalize, keep share | coordinator, then every participant | automatic | automatic |
| 7 | propose the birth certificate *(subgroup)* | coordinator, or any parent admin in it | button on the ladder | automatic (Phase 6) |
| 8 | sign the birth certificate *(subgroup)* | the parent's quorum | signing approval | **unchanged**: the parent's own decision, see Phase 0 |
| 9 | propose the key state | coordinator, or any participant | button on the ladder | automatic (Phase 6) |
| 10 | sign the key state | each participant, `t` needed | signing approval | automatic when the statement passes the checks in Phase 7, asked as today otherwise |
| 11 | create the room | coordinator, or any participant | button on the ladder | automatic, retried when it is blocked on key packages (Phase 6) |
| — | abandon | any participant | button on the ritual screen | **kept** |

Today a member who joins a group's own ceremony is asked three more times before
the group has a working room (rows 3, 5 and 10), and the coordinator presses
three more buttons (rows 9 and 11, plus 3 and 5 for themselves). After this plan
the member is asked nothing after row 2, and the coordinator nothing after row 1.

### Where the gates are

| finding | where |
|---|---|
| G1. `advance` stops at round 1 until `round1ApprovedAt` is set, and asks for it | `ChillDkgRitualManager.kt:751` |
| G2. `advance` stops at round 2 until `round2ApprovedAt` is set, and asks for it | `ChillDkgRitualManager.kt:788` |
| G3. `pendingApproval` mirrors G1 and G2, and must keep doing so | `ChillDkgRitualManager.kt:1123` |
| G4. The coordinator is approved for the host key on proposal but still asked for both rounds | `ChillDkgRitualManager.kt:305-309` |
| G5. The ritual screen's bottom bar offers a review button per step, and three routes and three screens back it | `DkgRitualScreen.kt:209-246`, `DedunNavHost.kt:621-659` |
| G6. The room's chat notice names three steps | `ChatTranscript.kt:627-631`, `ChatMessageListViewModel.kt:275` |
| G7. The join screen promises the member two more asks | `DkgJoinApprovalScreen.kt:82` |
| G8. Key state, certificate and room are buttons wired to view-model actions, so they run only while somebody has the screen open | `DkgRitualViewModel.kt:308, 370, 499` |
| G9. A key-state signing session waits on `signApprovedAt` like every other signature | `FrostSigningManager.kt:977` |
| G10. Nothing resumes a ritual except an arriving message. A ceremony paused at round 1 when the upgrade lands has every message it needs already and will receive no more, so it would never move | `NostrDao.kt:995, 1600` are the only callers of `processRitualPayload` |

G10 matters beyond the upgrade. The rounds have always been safe to resume, but
until now a person's tap was the thing that resumed them. Take the tap away and
something else has to do that job.

---

## Phase 0: before any code

**An hour, to settle eight things the phases assume.**

1. **Joining means consenting to the whole ceremony.** `hostKeyApprovedAt` keeps
   its column and name. It now means "agreed to take part", and a member is not
   asked about this ceremony again. The join screen has to show everything the
   round-1 screen used to show, the participant list in particular. That costs
   nothing: the list is the proposal's p-tags, fixed before anyone joins, so the
   round-1 screen never showed anything the join screen could not have.

2. **Nothing new goes on the wire.** The kinds, tags and ordering stay as they
   are. Older Giza and Bennu peers keep their own gates, and a ceremony with one
   of them in it finishes when that member taps, as it does today.

3. **`round1ApprovedAt` and `round2ApprovedAt` stay in the schema, unread.** A
   version bump to drop two nullable columns costs a migration and a migration
   test and buys nothing. Their KDoc says they were written by builds before
   this one. `DkgApprovalStep.ROUND_1` and `ROUND_2` stay in the enum for the
   same reason: `approvalRequestedThrough` stores them by name on rows already
   written, and removing them would stop those rows decoding.

4. **The coordinator's open-ceremony button stays a decision, and so does the
   parent's signature on a birth certificate (row 8).** That signature is the
   parent group agreeing that a subgroup exists, under a name, with these
   admins. Most of the parent's signers were never in the ceremony, and what
   they are judging is whether the parent wants this subgroup. That is
   governance, and a device cannot answer it. The proposal is a request, so it
   can go out automatically (Phase 6). The answer cannot.

5. **A key state signs itself only when exactly one statement could be true.**
   For a ceremony this device completed, there is one key, one path and, for a
   subgroup, one certificate the parent's quorum signed. So there is exactly one
   key state that is correct. If the statement matches it, a person has nothing
   to add. If anything differs, the device asks, as it does today. Phase 7 lists
   the checks.

6. **The resume sweep runs once the live subscription has started and the active
   key pair is in reach.** The likely place is next to where
   `SynchronizationViewModel` builds `LiveSubscriptionManager`. If the key pair
   is not available there, use wherever `NostrDao` gets the `activeKeyPair` it
   passes to `processRitualPayload`. Decide which before Phase 3. Either way a
   ceremony moves whenever the app is in the foreground, and never while it is
   closed (see *What this does not do*).

7. **Abandon keeps its rule: any participant, only participants, at any point
   before COMPLETE.** Automation makes a joined member's silence the only
   remaining way for a ceremony to stall, so abandon becomes the normal way out
   of a stall. Phase 5 makes the screen say so.

8. **Phases 6 and 7 are in scope.** "The ceremony" means everything up to a
   working room, not just ChillDKG. A ceremony is only useful once its room
   exists, and none of the buttons between the key and the room asks anyone
   anything. Phases 1 to 5 would be complete on their own, but they would leave
   the coordinator pressing two more buttons (three for a subgroup), so they are
   not a stopping point.

---

## Phase 1: a ceremony that really runs, across devices, in a test

**A day. Do this before changing anything, so every later phase has something
to change.**

No test today runs ChillDKG from proposal to COMPLETE. `GroupFixtures` gets its
key material from `Frost.trustedDealerKeygen`, and the ceremony tests stop after
the host key. The rounds this plan automates have never been exercised end to end
off a device.

A `CeremonyNetwork` fixture in `jvmTest/.../managers/`:

- `n` in-memory databases, one per member, each with the room seeded the way
  `SubgroupCeremonyInParentRoomTest` seeds its two;
- `deliver()` reads every database's outbound rows (`giftWrapPayloadDao` in a
  NIP-17 room, `marmotInnerEventDao` in a Marmot one). It writes each row into
  every other member's matching store first, because `replayStoredMessages` reads
  that store, and then calls `processRitualPayload` on it;
- `deliverUntilQuiet()` repeats until a pass moves nothing;
- an optional shuffle of each pass's order. Relays reorder events, and the
  replay path exists because of it.

Tests, written against today's behaviour:

- three members, 2-of-3, NIP-17: with every approval given, all three reach
  COMPLETE and hold the same `thresholdPublicKey` and three different shares;
- the same in a parent's Marmot room for a subgroup over two of its three
  members, where the third records nothing;
- **today's gates, pinned:** after the joins and nothing more, the network goes
  quiet with every member at `pendingApproval == ROUND_1`. Phase 2 flips this
  assertion, and that flip is the change this plan makes.

This is also the harness Phases 2, 3, 6 and 7 assert against.

---

## Phase 2: the manager stops at the join and nowhere else

**Half a day. Closes G1 to G4.**

In `ChillDkgRitualManager`:

- **`advance`** loses the two `if (session.roundNApprovedAt == null)` blocks. The
  host-key gate at the top stays, with its comment rewritten: it is now the only
  gate. The steps between are already idempotent through `ownMessage` and the
  `coordinatorRound1`/`certificate` checks, so removing the gates adds no new
  path. It only stops cutting the existing path short.
- **`pendingApproval`** becomes:

  ```kotlin
  if (session.stage == COMPLETE || session.stage == FAILED) return null
  return if (session.hostKeyApprovedAt == null) DkgApprovalStep.HOST_KEY else null
  ```

  It is still "a step is pending exactly when `advance` would stop at it", which
  is now easy to see. Its KDoc says the other two steps used to be gates.
- **`approve`** accepts only `HOST_KEY`. For the other two it logs and does
  nothing, the same answer it gives a stale screen today. A `ROUND_*` approval
  from a screen left open across the upgrade then does no harm.
- **`proposeRitual`**: the comment at `:305` changes from "Rounds 1 and 2 are
  still asked for" to say that opening a ceremony is agreeing to all of it, and
  the coordinator is never asked.
- **`announceApprovalNeeded`** only ever receives `HOST_KEY`. The `ROUND_1` and
  `ROUND_2` arms of its `when` stay, because the enum keeps those values, but
  nothing reaches them. They get a one-line comment saying so, so nobody mistakes
  them for live wording.
- **`DkgApprovalStep`**'s KDoc is rewritten. It becomes the list of things a
  ritual has *ever* asked for: one that is still asked, two kept so old rows
  decode.

A round-2 fault keeps the failure path it has today: `raiseIfFaulty`, then
`fail`, then a broadcast FAILURE. The difference is that it can now happen with
nobody looking at the screen. The transcript's *failed* line and the ritual
screen's failed state already cover that case, and Phase 4 checks the room's
notice does too.

**Tests**, on the Phase 1 network:

- after each member joins, and with no other call, every member reaches COMPLETE
  with the same key. This is Phase 1's pinned assertion, flipped;
- the coordinator is never asked, so `pendingApproval` is null for them from the
  moment they propose;
- the last member to join publishes host key and round 1 in that one `approve`
  call, because the host keys were already all in;
- a coordinator who substitutes `cmsg1` (the fixture tampers with it in transit)
  fails the ceremony on every honest device, with no approval in between, and
  each of them broadcasts FAILURE. This was always the protection, and the test
  shows it no longer depends on a person;
- `approve(ROUND_1)` and `approve(ROUND_2)` publish nothing;
- a guarded subgroup still cannot be joined, and nothing goes out.

`SubgroupCeremonyInParentRoomTest` and `RobustRoomKeyCeremonyTest` assert only
the host-key step and should pass unchanged. Check that they do.

---

## Phase 3: running ceremonies resume without a message

**Half a day. Closes G10.**

```kotlin
/**
 * Moves every running ceremony of this user's as far as its stored messages
 * allow. What a tap on "Contribute" used to do, for ceremonies that already
 * have everything they need and will receive nothing more.
 */
suspend fun resumeRunning(database: DedunDatabase, userPublicKey: HexKey, nostrPrivateKey: ByteArray)
```

It selects sessions for `userPublicKey` that are not COMPLETE or FAILED and have
`hostKeyApprovedAt` set. It looks up each session's room (skipping a session
whose room is gone, with a warning) and calls `advance`. That needs one DAO
query, `getRunningSessionsFor(userPublicKey)`. `advance` is already safe to
re-run, so the sweep can run every time sync starts at no real cost: a
ceremony with nothing to do returns at its first missing input.

It is wired into `SynchronizationViewModel` where the live subscription is built,
once per identity, after the subscription opens. That way a sweep that publishes
something is publishing into a pipeline that is already running.

**Tests:**

- **the upgrade case:** a session with all host keys stored, `round1ApprovedAt`
  null and `approvalRequestedThrough = ROUND_1`, which is exactly what a
  ceremony paused by the old build leaves. Run the sweep with no message
  arriving: it publishes round 1, and on the Phase 1 network the ceremony
  completes;
- a session whose host key was never approved is not touched;
- a FAILED or COMPLETE session is not touched;
- two sweeps in a row publish nothing twice.

---

## Phase 4: one approval screen

**A day. Closes G5 to G7.**

**The join screen takes on what the round-1 screen showed.** In
`DkgJoinApprovalScreen`:

- the participant list, moved from `DkgRound1ApprovalScreen`, under the existing
  `taking_part_with_these_members` string;
- the three points become what joining now commits to: *your device publishes
  the key it is identified by, then contributes to the shared key and confirms
  the result once everyone has, without asking you again*; *your share never
  leaves this device*; *the ceremony cannot finish until every member has taken
  part, and any of them can abandon it if it stalls*;
- the footnote keeps "Nothing has been published from this device yet."

Every new string goes in `strings.xml` in sentence case. The existing literals on
this screen move there too, since the screen is being rewritten anyway.

**Removed:** `DkgRound1ApprovalScreen.kt`, `DkgRound2ApprovalScreen.kt`,
`DkgRound1ApprovalRoute`, `DkgRound2ApprovalRoute`, their two `composable<>`
entries in `DedunNavHost`, and the strings only they used: `review_and_contribute`,
`review_and_confirm`, `this_is_the_last_thing_the_ceremony_needs`. Run
`m3-extract-strings.py --list` afterwards to catch any others.

**`DkgRitualScreen`'s bottom bar** keeps its `pendingApproval` branch, which can
now only be `HOST_KEY` → **Review and join**. The `when` over steps becomes an
`if`.

**The ladder** shows the member's own rung for rounds 1 and 2 as something their
device is doing ("Your device will contribute once everyone has joined"), not
something waiting on them. Wording is chosen in this phase. The rule it follows:
nothing on the screen suggests the reader has something to do unless they do.

**The room's notice** (`CeremoniesAwaitingYouNotice`): the `ROUND_1` and `ROUND_2`
arms go, along with `send_your_contribution_to_the_key` and
`check_the_combined_result`. The notice now appears for two reasons only: a
ceremony to join, or (until Phase 6) a room to create.

**The transcript** needs no code change, and that should be checked rather than
assumed. Request lines already written by the old build still render, and
`DKG_REQUEST_FULFILMENTS` still marks them answered, because the automatic round
writes the same `TYPE_DKG_ROUND_1`/`_2` line a tapped one did. The `3n + 4` step
lines per ceremony are unchanged, and each device writes one request line
instead of three.

**Tests:**

- `DkgRitualViewModelJvmTest`: its fake repository's `pendingApproval` returns
  `ROUND_1`. That fixture moves to `HOST_KEY`, and the "state holds both
  session and pending approval" test asserts on the join;
- `TranscriptRequestStateTest`: add a case where a `ROUND_1` request line from
  the old build is followed by the automatic `TYPE_DKG_ROUND_1` line and reads
  as answered;
- `./gradlew :composeApp:m3Audit` at its budgets.

---

## Phase 5: abandon is how a stalled ceremony ends

**Half a day.** The button and its rules are unchanged. What changes is that the
screen shows when abandoning is the right move.

Once the rounds are automatic, a running ceremony is waiting on one of two
things:

- **a member who has not joined.** This is a person's decision, and it may be a
  no. Today a member declines by doing nothing.
- **a member who joined and whose device has not been opened since.**

The ladder already names who it is waiting on. Phase 5 adds *since when*: the
time of the newest transcript line for this ceremony, read from the
`ChatMessage` rows it already writes, so no schema change. When that time is
more than a day old, the waiting rung shows the abandon action inline, with the
existing confirmation dialog, and says what abandoning does: *no key is made,
and the group can start again*. The top-level abandon button stays where it is.

There is deliberately no timer that abandons on its own. Whether a day is too
long is for the group to judge.

**Tests:** a UI-state test that the stall hint appears only past the threshold
and only for a participant (`canAbandon`).

---

## Phase 6: the coordinator's follow-through runs itself

**A day. Closes G8.**

The ladder's three buttons call view-model functions, so they run only while
the ritual screen is open. A new `CeremonyFollowThrough` in `managers/` takes the
next step from stored rows, in the same style as `advance`:

```
on a COMPLETE ceremony this device coordinated:
  subgroup, no certificate session (or only failed ones)  -> propose the birth certificate
  subgroup, certificate not yet signed                    -> wait
  no key-state session (or only failed ones)              -> propose the key state
  key state not yet signed                                -> wait
  room does not exist                                     -> create it
```

- **What it calls:** the code behind `DkgRitualViewModel.proposeBirthCertificate`,
  `proposeKeyState` and `createAdminGroup`. That code moves from the view model
  into the manager (and `DkgRepository`), along with each one's "already asked"
  guard, for example the check at `DkgRitualViewModel.kt:320`. The view model
  then calls the manager, so a button and the automatic path cannot drift apart.
- **When it runs:** at the end of an `advance` that reached COMPLETE; when a
  signing session for a certificate or key state completes; and on the Phase 3
  sweep.
- **Only the coordinator runs it automatically.** If every participant's device
  proposed the key state, there would be `n` competing sessions. The buttons
  stay on the ladder for any participant, as `subgroups.md` intends, so a
  coordinator whose phone is lost does not strand the group.
- **A room blocked on key packages** (`MarmotGroupCreation.Outcome.BlockedOn`)
  is not an error. The follow-through records nothing. It tries again on the next
  sweep and whenever a key package arrives for one of the missing members. The ladder keeps naming those members from the same outcome.
- **No navigation.** A room created in the background does not take over the
  screen. The ladder's last rung becomes **Open the room**.

**Tests**, on the Phase 1 network extended with `GroupFixtures`' signing
helpers:

- a group's own ceremony ends with its admin room created on the coordinator,
  with no button pressed;
- a subgroup's ceremony proposes its certificate and then stops. Once the parent
  quorum signs it (in the test, by calling approve for them), the follow-through
  proposes the key state with the certificate attached;
- blocked on one member's key package, the room is created when that package
  arrives;
- a non-coordinator's device proposes nothing on its own, and its button still
  works.

---

## Phase 7: a key state for a ceremony this device finished signs itself

**A day. Closes G9, for key states only.**

In `FrostSigningManager.processSigningPayload`, next to the wallet decline at
`:425`, a proposal is approved on arrival (`signApprovedAt = now`) when **every**
one of these holds:

1. the session has exactly one item, of kind `GroupKeyStateEvent.KIND`;
2. its `dkgSessionId` names a `DkgSession` on this device, for this user, at
   COMPLETE;
3. the item's content is that session's `thresholdPublicKey`;
4. its path is `MARMOT_ADMIN_GROUP_PATH`, and the signing session's
   `derivationPath` is the same;
5. its room is `marmotGroupId(thresholdPublicKey, path)`, which is what
   `GroupKeyState.verifies()` checks;
6. for a subgroup, it carries a certificate that
   `SubgroupBirthCertificateEvent.certifies` accepts for that room and the
   session's `parentChatRoomId`, which is the check `parentageOf` already makes
   on receipt;
7. no key state for that room is already signed on this device.

These checks are what `GroupKeyStateManager.stateFrom` and `.propose` already
apply. They should be called, not copied, so the auto-sign rule and the rule for
what gets kept are the same code.

Anything that fails a check goes through the normal path: the approval line, the
proposal screen and the member's decision. Failing a check is not a reason to
decline. It means the device cannot vouch for the statement, so a person has to.

**Tests:**

- on the Phase 6 network, a group's own ceremony reaches a signed key state and
  a room with no approval after the joins;
- each check, broken on its own, leaves the session waiting at
  `isAwaitingApproval`: a batch of two, a different key, a different path, a
  mismatched room id, a subgroup state with no certificate or a non-certifying
  one, a ceremony that is not COMPLETE here, and a room already holding a signed
  state.

---

## Phase 8: the record, and three emulators

**Half a day of docs, then a device session.**

- `shared-key-ceremony.md`: *Nothing publishes without approval* becomes
  *Joining is the only approval*, with the reasoning from this plan's headline.
  The gate table shrinks to one row. Add a short history of the two gates that
  were removed, since `round1ApprovedAt`/`round2ApprovedAt` and the enum values
  still exist and need explaining.
- `subgroups.md`: the order section names which steps run themselves and keeps
  the parent's signature as a decision.
- `README.md`: this note's row, added with the plan, changes to say it has been
  built.
- This note: a *Built* header and a table of where the build chose differently,
  the way `ceremony-creation.md` keeps one.

**On the three emulators** (Bob, Charlie, Medium):

1. A NIP-17 group of three, 2-of-3. Bob opens it, Charlie and Medium each tap
   **Join** once. Nobody touches anything else, and the `#` room should appear
   on all three.
2. A subgroup of two in `#Checkers`. The only taps should be the join and the
   parent quorum's signature on the certificate.
3. **The upgrade:** on the current build, stop a ceremony at round 1 on all
   three, install this build over it, and open the app. It should finish from
   the sweep alone.
4. Charlie joins and then force-stops the app. The others' ladders name Charlie,
   the stall hint appears past its threshold, and abandon ends the ceremony on
   all three.

`./gradlew check` must pass: `m3-audit`, `brand-audit`, `wallet-audit`.

---

## What this does not do

- **It does not run while the app is closed.** The live subscription is
  foreground-only ([long-running-sync.md](./long-running-sync.md)). Each member's
  device still has to be opened once after everyone has joined, and once more
  after the coordinator combines the contributions. What changes is that opening
  the app is enough. Background execution is its own piece of work on each
  platform.
- **It adds no way to decline a join.** A member who does not want to take part
  still says so by not joining, and the others still abandon. A *decline* that
  broadcasts FAILURE would be a small, separate change. It belongs with Phase 5
  if the emulator run shows a silent decline is confusing.
- **It does not change older peers.** A Giza build before this one, or a Bennu
  device, still asks its owner at each round, and a ceremony that includes one
  waits for that member's taps.
- **It does not automate signatures other than the key state.** A post, a
  profile, a certificate for some other group, or anything in a batch is still
  asked about, exactly as today.

## Order and size

| phase | size | depends on |
|---|---|---|
| 0 decisions | an hour | — |
| 1 cross-device test | a day | — |
| 2 manager | half a day | 1 |
| 3 resume sweep | half a day | 2 |
| 4 UI | a day | 2 |
| 5 abandon on stall | half a day | 4 |
| 6 follow-through | a day | 3 |
| 7 key state signs itself | a day | 6 |
| 8 docs, emulators | half a day plus a session | all |

Phases 2 and 3 have to ship together. With 2 and without 3, a ceremony paused by
the old build sits still until a message happens to arrive. Phase 4 can follow in
the same release or the next: until it lands, the two removed screens are
reachable but do nothing, which is untidy but harmless.
