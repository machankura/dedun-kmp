# Taking part is the only approval

A plan to make a share rotation run by itself once a member has agreed to take
part. The member decides one thing: whether to take part in this rotation, with
these members withheld. Their device does everything after that. It deals its
part, checks the combined result, stores the new share, confirms it, signs the
group's new key state, and deletes the old share. **Abandon** stays, because the
one stall automation cannot fix is a member who agreed and then went quiet.

This is the same change [automatic-key-ceremony.md](./automatic-key-ceremony.md)
made to the ceremony, applied to [frost-share-refresh.md](./frost-share-refresh.md).
Read both first. This plan follows the first one's shape, because the reasoning
is the same and so are most of the traps.

**Not built.** Written against `fc437d05` on 2026-11-20.

The headline: **a device asks its owner only for decisions it cannot make on
its own.** Taking part is one of those decisions, because it agrees to the
withheld list, and withholding someone takes their signing away. Storing the new
share is not a decision. The device has already checked the combined result
against the commitments and the table before it asks, and a person tapping
**Approve** under "Store your new share" adds nothing to that check.

---

## What a member is asked today

A rotation in a room whose key is `t`-of-`n`. Today every recipient is asked
**three** times, not two. The third ask is easy to miss because it looks like an
ordinary signing request rather than part of the rotation.

| # | step | whose | today | after this plan |
|---|---|---|---|---|
| 1 | propose the rotation | proposer | **Rotate shares** button, no dialog, or the dialog after a removal | a dialog naming who takes part and who is withheld. Pressing it is the proposer's approval (R2) |
| 2 | deal round 1 (`DEAL`) | every recipient, proposer included | Shares section row, *Take part in rotating the shares* | **unchanged: the one approval**. The row now names who is withheld |
| 3 | sum round 1 (`cmsg1`) | coordinator | automatic | automatic |
| 4 | check, store the pending generation, send CertEq (`CONFIRM`) | every recipient | Shares section row, *Store your new share* | automatic. If the check fails, the rotation fails and says so, as it does today |
| 5 | certificate, activate `g+1` | coordinator, then every device | automatic | automatic |
| 6 | propose the `g+1` key state | coordinator | automatic (`proposeGeneration`) | automatic |
| 7 | sign the `g+1` key state | every recipient, `t` needed | signing approval, because `vouchesFor` rule 8 refuses any state with a generation (`fc437d05`) | automatic when the state is exactly the one this device's own completed rotation implies (Phase 5). Asked as today otherwise |
| 8 | wipe generation `g` | every device | automatic once 7 is signed | automatic |
| — | abandon | any recipient | only on a pending approval row | **kept**, and reachable for the whole run (Phase 6) |

Today a recipient taps at rows 2, 4 and 7, and the proposer taps the button and
then rows 2, 4 and 7 as well. After this plan a recipient taps once, and the
proposer taps once.

### Where the gates are

| finding | where |
|---|---|
| G1. `advance` stops before round 1 until `dealApprovedAt` is set | `ShareRefreshManager.kt:391` |
| G2. `advance` stops before round 2 until `confirmApprovedAt` is set | `ShareRefreshManager.kt:415` |
| G3. `pendingApproval` mirrors G1 and G2, and must keep doing so | `ShareRefreshManager.kt:589-600` |
| G4. The proposer is approved for nothing on proposal, by design | `ShareRefreshManager.kt:111-116`, asserted by `ShareRefreshRitualJvmTest` "nothing of a member's own goes out before they approve" |
| G5. A rotation's key state is never vouched for: `vouchesFor` rule 8 | `GroupKeyStateManager.kt:659-660` |
| G6. Vouching is decided once, on the proposal's arrival. A device that receives the `g+1` key state before its own rotation has activated `g+1` cannot vouch for it then, and nothing asks again | `FrostSigningManager.kt:447`, `resumeAt` at `:1945` |
| G7. Nothing resumes a rotation except an arriving message. The sweep covers ceremonies only | `DatabaseNostrRepository.resumeRunningCeremonies` calls `ChillDkgRitualManager.resumeRunning` and nothing else |
| G8. The Shares section shows `REFRESH_CONFIRM` as its own row, with its own string | `SharesSummary.kt:24`, `DatabaseChatRepository.kt:674`, `ChatRoomDetailScreen.kt:406`, `pending_rotation_confirm` |
| G9. The only abandon button is on a pending approval row. Once a member has approved the last thing asked of them, they can no longer abandon a rotation stuck on somebody else | `ChatRoomDetailScreen.kt:413` |
| G10. The `DEAL` row does not say who is withheld, which is the whole of what that approval decides | `ChatRoomDetailScreen.kt:399-410` |

G9 is a bug today only in a narrow case: a member who has confirmed and is
waiting on the certificate. Removing `CONFIRM` makes it the normal case. Every
member who has taken part would have no way out of a stalled rotation, and
`canRotate` stays false while one runs, so the group could not start another.
Phase 6 is not optional.

G10 is the same kind of gap. Once `DEAL` is the only approval, it carries all
the weight of the decision, and today the row shows none of what is being
decided.

---

## Why `CONFIRM` was never a decision

`ShareRefreshApprovalStep.CONFIRM`'s KDoc gives two reasons for the gate. Neither
needs a person.

- **"This is what stops a coordinator substituting a table the recipients never
  dealt."** The check that stops it is `FrostRefresh.participantStep2`. It
  decrypts and sums the dealt values, runs the Feldman check against the
  commitments, recomputes every public share, and checks the new share against
  its public share and the table against the threshold key. It runs before the
  prompt appears, whatever the person does. A substituted table fails there and
  the rotation fails out loud (`raiseIfFaulty`, `fail`, FAILURE). It never
  reaches the prompt. The prompt shows nothing the person could check
  themselves.
- **"The one confirmation per base generation this device will ever give."** The
  write-once claim is `ShareRefreshConfirmation`, taken in the same transaction
  as the `PENDING` row (`confirmAndStore`). It holds whether or not a person
  tapped. A second refresh from the same base is refused by the database, not by
  the owner.

The only thing a person could do at `CONFIRM` that the device cannot is change
their mind. Abandon still covers that, from the whole run once Phase 6 lands.

The same argument covers the key state at row 7. For a rotation this device
completed, the new generation's public-share table, the refresh id and the
retired ids are all facts the device holds and has checked against the
certificate. Exactly one key state can be true. If the proposal matches it, a
person has nothing to add. That is the rule automatic-key-ceremony.md Phase 0.5
set for the ceremony's own key state. `fc437d05` kept rotation states out of it
because they say more than a ceremony does. Phase 5 adds the rule for what they
say.

---

## Phase 0: decisions

**An hour. Each has a recommendation.**

**Decided 2026-11-20: all six as recommended.** Execution began the same day.

| # | question | recommendation | if decided otherwise |
|---|---|---|---|
| R1 | What does taking part consent to? | **The whole rotation.** `dealApprovedAt` keeps its column and name and now means "agreed to take part, with these members withheld". The member is not asked about this rotation again, and neither is the signature on its key state when that state is exactly what the rotation implies | Keep row 7 asked. A rotation still needs `t` taps after the certificate before the old shares are wiped, and in the meantime the old shares stay on every device |
| R2 | Is proposing the proposer's approval? | **Yes, once the proposal is a dialog that names who takes part and who is withheld, stale members included.** The reason the proposer is asked today is that "proposing is not agreeing to withhold whomever the form defaulted to". The fix for that is a form that shows its defaults. Asking again afterwards does not fix it | The proposer taps twice: the button, then the row. Harmless, but it is the same decision twice |
| R3 | Anything new on the wire? | **No.** Kinds 30340-30345, the tags and the ordering stay. A Giza build from before this change keeps its gates, and a rotation that includes it finishes when that member taps, as today. Rotation is behind `ExperimentalFeatures.shareRefresh`, off in release, so no shipped build is affected | — |
| R4 | The schema | **No migration.** `confirmApprovedAt` stays, unread, with KDoc saying earlier builds wrote it. `ShareRefreshApprovalStep.CONFIRM` stays in the enum because `approvalRequestedThrough` stores it by name on rows already written. This matches automatic-key-ceremony.md Phase 0.3 | Drop both in a schema bump: a migration and a migration test for two nullable values nothing reads |
| R5 | Bindings (10b) | **Still asked.** A key state that adds a `frost_member` binding re-binds an id to a new identity. That is governance, not arithmetic, and `fc437d05` was right to keep it asked. Phase 5 vouches only for a state whose bindings are exactly the ones already signed for that generation, which right after a rotation means none | — |
| R6 | A timer that abandons by itself? | **No**, as in the ceremony. The Shares section says who the rotation is waiting on and since when. Whether that is too long is for the group to judge | — |

---

## Phase 1: pin today's gates

**Two hours. Do this before changing anything.**

`ShareRefreshRitualJvmTest` already has the multi-device harness (`devicesFor`,
`pump`, `approveAll`, `approveSigning`). What it lacks is a test that says where
the gates are. `approveAll` approves "whatever they are asked, until nobody is
asked anything", which passes whatever the gates are.

Add, against today's behaviour:

- **today's gates, pinned:** propose in a 2-of-3. Every recipient approves
  exactly once (`DEAL`), then `pump` until quiet. Every recipient is at
  `pendingApproval == CONFIRM`, nobody has a `PENDING` generation, and the
  outbox holds round 1 and `cmsg1` and nothing more. Phase 2 flips this
  assertion, and that flip is the change.
- **the key state, pinned:** run a rotation to COMPLETE with `approveAll`, then
  `pump`. The `g+1` key-state session is open and awaiting approval on every
  recipient, and generation `g` is still `RETIRING`, not `WIPED`. Phase 5 flips
  this one.
- **counted asks:** a helper `asksOf(device)` that counts the
  `TYPE_SHARE_REFRESH_APPROVAL_NEEDED` lines plus signing approval lines a device
  wrote for one rotation. Today it is 3 per recipient. Phases 2, 4 and 5 take it
  to 1.

---

## Phase 2: the manager stops at taking part and nowhere else

**Half a day. Closes G1 to G3.**

In `ShareRefreshManager`:

- **`advance`** loses the `confirmApprovedAt` block (`:415-418`). The `DEAL`
  gate at `:391` stays and gets a comment: it is now the only gate. The steps
  after it are already idempotent. `ownMessage(ROUND_2)` guards the send, and
  `confirmAndStore` is idempotent for the session that holds the claim. Removing
  the gate adds no path. It only stops cutting the existing one short.
- **`pendingApproval`** becomes:

  ```kotlin
  if (session.stage == COMPLETE || session.stage == FAILED) return null
  // ...key, base, myId as now...
  if (myId !in session.recipientIdList()) return null
  return if (session.dealApprovedAt == null) ShareRefreshApprovalStep.DEAL else null
  ```

  It is still "a step is pending exactly when `advance` would stop at it", which
  is now easy to see.
- **`approve`** acts on `DEAL` only. A `CONFIRM` approval from a screen left open
  across the upgrade cannot arrive any more (`pendingApproval` never returns
  it), but the `when` keeps the arm as a logged no-op so the enum stays
  exhaustive.
- **`announceApprovalNeeded`** only ever receives `DEAL`. The `CONFIRM` arm of
  its `when` stays with a one-line comment saying nothing reaches it, so nobody
  takes it for live wording.
- **KDoc.** The object's KDoc (`:63-64`, "at two gates") and
  `ShareRefreshApprovalStep`'s become the record of what a rotation has *ever*
  asked: one step still asked, one kept so old rows decode. Move the reasoning in
  [Why `CONFIRM` was never a decision](#why-confirm-was-never-a-decision) into
  the `CONFIRM` KDoc, so the next reader does not put the gate back.
  `ShareRefreshSession.confirmApprovedAt` gets "written by builds before
  docs/rotate-shares-single-approval.md; read by nothing".

A round-2 fault keeps the failure path it has today: `raiseIfFaulty`, `fail`,
broadcast FAILURE. The difference is that it can now happen with nobody looking.
The transcript's *ended* line already covers that, and Phase 6 puts it in the
Shares section too.

**Tests**, in `ShareRefreshRitualJvmTest`:

- Phase 1's pinned assertion, flipped: each recipient approves once and every
  device reaches COMPLETE at generation `g+1`, with no other call;
- `pendingApproval` never returns `CONFIRM`, sampled after every `pump` pass;
- the last recipient to approve sends round 1, and on the next pass round 2
  goes out with no further call;
- a coordinator that substitutes `cmsg1` in transit (the harness tampers with it)
  fails the rotation on every honest recipient, with no approval in between, and
  nobody holds a `PENDING` generation. This was always the protection, and the
  test shows it no longer depends on a person;
- **the fork rule still holds without a person:** two proposals from one base,
  both approved at `DEAL` by an overlapping recipient. That recipient confirms
  one and fails the other, and at most one certificate exists. This is the
  existing "a device confirms one refresh per generation" test, run with the
  confirm gate gone. If it needed the gate to pass, the gate was carrying D3a,
  and that has to be found now;
- "nothing of a member's own goes out before they approve" passes unchanged
  (until Phase 4 changes the proposer's half of it).

---

## Phase 3: running rotations resume without a message

**Half a day. Closes G7. Ships with Phase 2.**

With the `CONFIRM` tap gone, a rotation paused at `CONFIRM` by the old build has
`cmsg1` stored, is waiting on its own round 2, and will receive no more messages.
Without a sweep it never moves.

```kotlin
/**
 * Moves every running rotation of this user's as far as its stored messages
 * allow. What a tap on "Store your new share" used to do, for rotations that
 * already have everything they need and will receive nothing more.
 */
suspend fun resumeRunning(database: DedunDatabase, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Int
```

- **What it selects:** a new `ShareRefreshDao.getRunningSessionsFor(userPublicKey)`,
  sessions not COMPLETE or FAILED, oldest first. It does **not** filter on
  `dealApprovedAt`. A coordinator waiting on others' round 1 and a withheld
  device waiting on the certificate also have work a sweep can finish, and
  `advance` stops at the `DEAL` gate for anyone not yet approved.
- **What it calls:** for each, look up the room (skip a gone room with a warning)
  and `advance`. `advance` already returns at its first missing input.
- **Where it is wired:** `DatabaseNostrRepository.resumeRunningCeremonies`, after
  `ChillDkgRitualManager.resumeRunning`, under the same `storeNostrEventMutex`.
  Rename it to `resumeRunningRituals` while there, or leave the name and widen
  its KDoc. It runs from `SynchronizationViewModel` once the live subscription is
  open, so anything it publishes goes into a pipeline that is already running.
- **Behind the flag.** With `ExperimentalFeatures.shareRefresh` off it resumes
  nothing. A release build has no rotations to resume. If one somehow exists, it
  was refused on arrival.

The same sweep covers the second half of G6 for upgrades. A `g+1` key-state
session that an old build left awaiting approval is re-checked against
`vouchesFor` (Phase 5). That part lands with Phase 5, which owns the rule.

**Tests:**

- **the upgrade case:** a session with `cmsg1` stored, `dealApprovedAt` set,
  `confirmApprovedAt` null, `approvalRequestedThrough = CONFIRM`, which is
  exactly what the old build leaves. Run the sweep with no message arriving. It
  publishes round 2, and on the harness the rotation completes;
- a session never approved at `DEAL` is advanced but sends nothing;
- a FAILED or COMPLETE session is not touched;
- two sweeps in a row publish nothing twice;
- with the flag off, the sweep does nothing.

---

## Phase 4: proposing is the proposer's approval

**Half a day. Closes G4. Only if R2 is decided as recommended.**

The proposer is asked today because the button commits them to a withheld list
they were never shown. The fix is to show it.

- **A dialog behind *Rotate shares*.** `AlertDialog` in `ChatRoomDetailScreen`,
  in the style of the existing `rotate_shares_without_*` dialog. It shows:
  - *Taking part*: every live member's name;
  - *Left out until they are brought back*: every stale member, who
    `proposeRefresh` withholds again automatically (`:139-143`). Today the
    proposer never sees this list;
  - the existing `shares_rotation_caveat` copy;
  - confirm **Rotate shares**, dismiss **Not now**.

  It reads from a new `ChatRepository.rotationPreview(localChatRoom)` that runs
  the same selection `proposeRefresh` does, and `refusal`, so a rotation that
  would be refused says why before the tap rather than as a snackbar after it.
  `proposeRefresh` and the preview share one private function for the
  recipient/withheld split, so they cannot drift.
- **The removal dialog** (`rotate_shares_without_body`) gains the same stale list
  when there is one. The removed member is already named.
- **`proposeRefresh`** sets `dealApprovedAt = now` on the session it creates. The
  KDoc at `:111-116` becomes: proposing, from a dialog that named everyone
  withheld, is agreeing to the rotation, and the proposer is not asked again.
  `proposeWithout` inherits it.
- **The repository API** keeps `rotateShares(localChatRoom)`. The dialog is where
  the decision is shown, and the manager does not need to know a dialog exists.

Strings go in `strings.xml`, sentence case: `rotation_taking_part`,
`rotation_left_out_until_brought_back`, `not_now` if not already there.

**Tests:**

- the proposer's half of "nothing of a member's own goes out before they
  approve" flips. The proposer publishes the proposal and its round 1 in the one
  `proposeRefresh` call, and writes no approval-needed line. The other
  recipients' half is unchanged;
- `rotationPreview` and `proposeRefresh` agree on recipients and withheld for
  every fixture: 0, 1 and 2 stale, and a retired member;
- a preview of a rotation that `refusal` rejects carries the reason and no
  recipients.

---

## Phase 5: a rotation's key state signs itself

**A day. Closes G5 and G6.**

`GroupKeyStateManager.vouchesFor` signs exactly one statement per room: the key
state of a ceremony this device finished. Rule 8 (`fc437d05`) keeps every
generation state out. This phase replaces rule 8 with the rule for the one
generation state that can be true.

**The rule.** For a claim with `generation = h ≥ 1`, rules 1 to 6 stand as they
are. Rules 7 and 8 become:

7. **No state for this room is signed here at generation `h` or later.** This
   replaces "no state at all", which would refuse every rotation, since a room
   that rotates already has a generation-0 state.
8. **This device completed the rotation that made `h`:**
   - `KeyShareGeneration(key, h)` exists here, is `ACTIVE`, and holds a secret
     share. A device withheld from `h` cannot sign at `h` anyway, and should not
     vouch for a table it did not help make;
   - its `refreshSessionId` equals the claim's `frost_refresh`, and that
     `ShareRefreshSession` is COMPLETE here;
   - the claim's `frost_pubshares` equals
     `ShareGenerations.publicSharesHash(` that row's `publicShares` `)`;
   - the claim's `frost_retired` ids equal the ids `RETIRED` in this device's
     roster at `h`;
   - the claim's bindings equal the bindings already in a group-signed state at
     `h` on this device. Right after a rotation that is none. A state that adds
     a binding is a 10b re-bind and stays asked (R5);
   - the signing session's `generation` is `h`.
9. **Path and parent match the room's signed state**, not only the admin path.
   `proposeGeneration` copies them from `keyStateFor`, so a claim whose path or
   birth certificate differs from what the room already signed is not the one
   true statement.

Each condition calls what already exists (`claimFrom`, `stateAmong`,
`ShareGenerations.publicSharesHash`, the key-share DAO). None of it is copied,
for the reason automatic-key-ceremony.md Phase 7 gives: the rule for signing and
the rule for keeping must be one function.

**Asking again when the answer can change (G6).** Vouching is decided once, when
the proposal arrives (`FrostSigningManager.kt:447`). For a rotation that is
often too early. The coordinator proposes the `g+1` state the moment its own
certificate verifies, and a recipient can receive the proposal before the
certificate, while `h` is still `PENDING` here. Two changes:

- `FrostSigningManager.resumeAt(database, room, dkgSessionId, generation)`,
  which `ShareRefreshManager.advance` already calls after `activate`, runs
  `signsAsItsOwnKeyState` on each open session at that generation before
  advancing it, and approves the ones it vouches for;
- Phase 3's sweep does the same for open key-state sessions, for the upgrade
  case: a session an old build left asking.

`signsAsItsOwnKeyState` already refuses a session that has been approved or
declined, so a person who answered first keeps their answer.

**What stays asked.** Anything that fails a check takes the normal path: the
approval line, the proposal, the member's decision. Failing a check is not a
reason to decline. It means the device cannot vouch for the statement, so a
person has to. That includes a key state proposed by a member whose rotation
this device never saw. That should not happen, and if it does, a person should
look.

**Tests**, in `ShareRefreshRitualJvmTest` and `KeyStateSignsItselfJvmTest`:

- Phase 1's key-state assertion, flipped: each recipient approves `DEAL` once.
  With no other call, the group signs the `g+1` key state, every device wipes
  generation `g`, and the group signs at `g+1` as the same room. `asksOf` is 1
  per recipient (0 for the proposer after Phase 4);
- **the race:** the proposal for the `g+1` state is delivered before the
  certificate. It is not vouched for on arrival, and is vouched for when the
  certificate activates `h`;
- each check broken on its own leaves the session at `isAwaitingApproval`:
  - a different `frost_pubshares`;
  - a `frost_refresh` naming a rotation this device did not complete;
  - a retired id added or missing;
  - a new binding (the 10b test's re-bind state stays asked, so its
    `approveSigning(listOf(alice, bob))` call is still needed);
  - a session at generation `h−1`;
  - a device withheld from `h`;
  - a room already holding a signed state at `h`;
  - a path or parent that differs from the room's signed state;
- `fc437d05`'s regression stays covered: a generation-1 state on devices that
  hold no first state still needs every rule to pass, and is asked when one
  fails;
- every existing `KeyStateSignsItselfJvmTest` case passes unedited for
  generation-0 states.

---

## Phase 6: the Shares section, with one approval

**A day. Closes G8 to G10.**

Under CLAUDE.md's rules: strings in the catalogue in sentence case, spacing and
colour from the theme, outcomes through `rememberNotifier`, `Decorative` on any
icon whose label sits beside it.

**The approval row says what it decides (G10).**

- `SharesSummary.PendingApproval` gains `withheld: List<Withheld>`, each with a
  display name and `STALE` or `RETIRED`, filled in by
  `DatabaseChatRepository.sharesSummary` from the session's `withheldStandings()`.
- The `REFRESH_DEAL` row's headline becomes *Take part in rotating the shares*,
  as now. Its `supportingContent` becomes one of:
  - *Every member takes part.*
  - *Without Alice until they are brought back, and without Bob for good.* Built
    from format arguments, not concatenation, with a plural-safe form for one
    name or several.
  - A line on what taking part now commits to: *Your device sends its part,
    stores your new share and signs the group's new key state without asking
    again.*
- The row keeps **Abandon** and **Approve**.

**`REFRESH_CONFIRM` goes (G8).** The enum value in `SharesSummary.PendingApproval.Kind`
is UI state only, so it is removed outright, along with its arm in
`sharesSummary` and `approveShares` and the `pending_rotation_confirm` string.
Run `m3-extract-strings.py --list` afterwards to catch any other orphans.

**A running rotation is visible, and can be abandoned, for its whole run (G9).**

- `SharesSummary` gains `running: Running?`, with the session id, the stage in
  words (*dealing*, *storing the new shares*, *signing the new key state*), the
  members it is waiting on, the time of the newest `ShareRefreshMessage` for the
  session, and `canAbandon` (this device is a recipient). The rotation counts as
  running until the `g+1` key state is signed, not only until the certificate,
  because that signature is what the old shares wait on.
- "Waiting on" comes from stored rows: recipients with no round 1, then no round
  2, then the coordinator's sum or certificate, then the key-state session's
  missing signers. The stall clock is dated from the newest message, not the
  newest transcript line, for the reason automatic-key-ceremony.md's build table
  gives: `ShareRefreshManager.record` must store a message once, on first
  arrival. Check that it does (`:343-344` upserts on every redelivery, which
  rewrites the row) and fix it the way `ChillDkgRitualManager.record` was fixed.
- The Shares section shows it as a card above the members: *Rotating the
  shares*, the stage, *Waiting on Carol since yesterday*, and **Abandon** when
  `canAbandon`, behind the existing confirmation. It sits above the member list
  because a stall is the most important thing in the section when there is one.
- A rotation that ended shows its reason from `failureReason` until the next one
  starts, so a fault found with nobody looking is not lost in the transcript.

**The transcript** needs no code change. Check it rather than assume it.
`CONFIRM` approval-needed lines already written by the old build still render,
and the automatic round 2 writes the same `TYPE_SHARE_REFRESH_CONFIRMED` line a
tapped one did. Each device now writes one approval-needed line per rotation
instead of two.

**Tests:**

- a `SharesSummary` test that the `DEAL` row carries the withheld list, and that
  no `REFRESH_CONFIRM` kind exists;
- `running` names the right members at each stage, including the key-state
  stage after the certificate;
- `canAbandon` is true for a recipient who has already approved, and false for a
  withheld member;
- a redelivered round 1 does not move the stall clock;
- `./gradlew :composeApp:m3Audit` at its budgets, floors not moved.

---

## Phase 7: the record, and three emulators

**Half a day of docs, then a device session.**

- **frost-share-refresh.md**: Phase 7's *Approval gates* table shrinks to one
  row, with a short history of `CONFIRM` (why it existed, why it went, why the
  enum value and column remain). Phase 8's step 2 says the key state signs itself
  under the rule in this plan's Phase 5. Phase 11's bullet *the two approvals*
  becomes one. The build table gains a row pointing here.
- **automatic-key-ceremony.md**, *What this does not do*: "It does not automate
  signatures other than the key state" gains "a rotation's key state is
  automated too, by rotate-shares-single-approval.md".
- **README.md**: this note's row, and frost-share-refresh.md's row, which still
  says "not built" although it is.
- **This note**: a *Built* header and a table of where the build chose
  differently.

**On three emulators** (Bob, Charlie, Medium), a debug build so the flag is on:

1. A 2-of-3 room. Bob presses **Rotate shares** and confirms the dialog. Charlie
   and Medium each tap **Approve** once. Nobody touches anything else. All three
   show generation 1, and the transcript shows the key state signed and no
   second approval line.
2. Remove Medium. Bob rotates without them from the dialog. Charlie taps once.
   Medium's standing is *retired*.
3. **The upgrade:** on the current build, stop a rotation at *Store your new
   share* on all three, install this build over it, and open the app. It should
   finish from the sweep alone.
4. Charlie approves and then force-stops the app. Bob's and Medium's Shares
   section name Charlie and how long they have been waiting, and **Abandon**
   ends the rotation on all three.

`./gradlew check` must pass: `m3-audit`, `brand-audit`, `wallet-audit`.

---

## What this does not do

- **It does not run while the app is closed.** The live subscription is
  foreground-only ([long-running-sync.md](./long-running-sync.md)). After
  approving, each member's device still has to be opened once the others have
  dealt, again once the coordinator has summed, and again for the key state.
  What changes is that opening the app is enough.
- **It does not change the protocol.** Every recipient still has to take part
  (D2), the majority and write-once rules (D3) still hold, and the old share is
  still wiped only after the group signs the new generation (D6). This plan only
  changes who presses what.
- **It does not change older peers.** A build from before this change still asks
  at both gates, and a rotation that includes one waits for that member's taps.
  D5's capability check is still unbuilt, so nothing tells the proposer in
  advance.
- **It does not automate repair.** A helper's approval in a repair (10a) spends
  its share's secrecy on the target's behalf. That is a decision about someone
  else, and stays asked.
- **It does not automate re-binding an id to a new identity** (10b, R5).
- **It does not add a way to decline.** A member who does not want to take part
  abandons, which ends the rotation for everyone. A rotation cannot finish
  without every recipient, so a quiet "no" and a loud one end the same way. The
  loud one is just faster.

## Order and size

| phase | size | depends on |
|---|---|---|
| 0 decisions | an hour | — |
| 1 pin today's gates | two hours | — |
| 2 manager: one gate | half a day | 1 |
| 3 resume sweep | half a day | 2 |
| 4 proposing is approving | half a day | 2, R2 |
| 5 the key state signs itself | a day | 2 |
| 6 Shares section | a day | 2, 5 (for the key-state stage) |
| 7 docs, emulators | half a day plus a session | all |

**Phases 2 and 3 ship together.** With 2 and without 3, a rotation paused by the
old build at *Store your new share* sits still until a message happens to
arrive. **Phase 6's abandon card ships with Phase 2 or before it.** Without it, a
member who has approved has no way to end a stalled rotation (G9). Phases 4 and 5
are independent of each other and can land in either order. Everything is behind
`ExperimentalFeatures.shareRefresh`, so no phase changes a release build.
