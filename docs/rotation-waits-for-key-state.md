# A rotation waits for the last one to be signed

A plan to hold the **Rotate shares** button until the group has signed the key state
the previous rotation produced, and to stop asking the person who proposed a rotation
to approve it.

Read [frost-share-refresh.md](./frost-share-refresh.md) first, especially Phase 7's
approval gates, Phase 8 (the cutover) and D6 (when the old share is wiped). Read
[automatic-key-ceremony.md](./automatic-key-ceremony.md) as well. Its rule, "the device
asks its owner only for decisions the device cannot make on its own", is the precedent
for Phase 4 here.

**Built**, one commit per phase on `claude/rotate-shares-pending-proposal-9d72d7`
(`159ae402`, `d358a0db`, `98567629`, `95131248`), against the code as it stood at
`fc437d05`. The phases are kept as written because they are the reasoning. Where the
build chose differently:

| what the plan said | what it turned out to be |
|---|---|
| R1(c)'s open question: does a key-state session nobody finishes ever fail here? | Only once `participantCount - threshold + 1` members have declined (`FrostSigningSession.declinesThatAbandon`), so one refusal does not end it. An open session counts **unless this device declined it**. The coordinator who proposed it cannot decline its own, and stays held until others do or it completes |
| Phase 2's `ChatRepository.proposeKeyState` does the work | the work is `ShareRefreshManager.proposeOwedKeyState`, which the repository wraps, so it is tested in the ritual harness without building an `Identity` |
| Phase 2's held line links to the proposals | a **See the proposal** button under the line, to `ProposalListRoute` |
| Phase 2's summary tests are their own file | two tests in `ShareRefreshRitualJvmTest`, reading `DatabaseChatRepository.sharesSummary` on the harness's devices through every `Rotation` value, the re-ask included |
| Phase 3 recomputes on repairs too, or leaves them out | left out: `ShareRepairDao` has no observable query |
| Phase 4's dialog says "%1$s will be left out again…" | a line, then one name per line. Joining names into one string needs a separator the catalogue cannot hold |
| Phase 4's crossing proposals end in one completed refresh or one a recipient can abandon | each proposer is a recipient of the other's proposal and refuses it on arrival ("another refresh of this key is still running here"), out loud, so neither is left running. The test asserts the property, not which one survives: nothing running, at most one complete, every device on one table |

**Not done:** none of it has been seen on a device or a desktop window: the disabled
button and its line, the re-ask, the confirmation dialog, or the section updating while
open.

---

## The bug

A rotation does not end when its refresh session reaches `COMPLETE`. It ends when the
group has signed the new generation's key state (kind 30326). That signature is the
first one the new shares make, and every device waits for it before it wipes the old
shares (D6). Here is the sequence:

```
 Rotate shares
   └─ ShareRefreshSession  PROPOSED … COMPLETE          ← canRotate checks only this
        └─ coordinator: GroupKeyStateManager.proposeGeneration
             └─ FrostSigningSession over a 30326 at g+1   ← open, waiting on a quorum
                  └─ signed → record() → retireOlderShares → wipe g
```

`DatabaseChatRepository.sharesSummary` (`DatabaseChatRepository.kt:681-688`) works
out `canRotate` as:

```kotlin
val running = refreshes.any { it.stage != COMPLETE && it.stage != FAILED }
canRotate = ExperimentalFeatures.shareRefresh && shares.secretShare != null && !running
```

So the button comes back once the refresh is `COMPLETE`, while the key-state
proposal for `g+1` is still collecting signatures. `ShareRefreshManager.proposeRefresh`
has no further check either. Its `running(...)` guard (`ShareRefreshManager.kt:207`)
looks only at refresh sessions. A second tap then does the following:

1. It starts a refresh from base `g+1` before anyone has shown that `g+1` can sign.
   That is the thing D6 exists to prevent. Two unproven generations are now stacked.
2. It turns the open `g+1` key-state proposal into a dead end. Once `g+2` is active
   and its key state is signed, `retireOlderShares` calls `failRotated(g+2)`, and every
   open session below `g+2` fails. That includes the `g+1` key-state session that
   members may still be approving. They approve a proposal that can only fail.
3. A device that has not received the `g+1` certificate yet refuses the new proposal
   on arrival ("it refreshes generation g+1, and this device is at generation g"), and
   if it is a recipient it broadcasts a failure.

The same gap reaches the room detail screen's "Rotate shares without them?" dialog
(`ChatRoomDetailScreen.kt:192-207`). `rotateSharesWithout` never reads `canRotate`.

Two smaller problems sit next to the main one:

- **The screen is not reactive.** `ChatRoomDetailViewModel.initiateChatRoomDetail`
  reads `sharesSummary` once and reads it again only after one of the screen's own
  actions. A key state signed in the background does not re-enable anything until the
  screen is opened again.
- **Nothing guards a double tap.** `sharesAction` (`ChatRoomDetailViewModel.kt:146`)
  does not disable the button while it runs. A second tap is caught by `running(...)`,
  which hands back the first session, and the screen still says "Share rotation
  proposed" a second time. `isActionPending` exists on the view model but nothing uses
  it.

## The question: should the proposer approve their own rotation?

At the moment the proposer is asked like everyone else:

> The proposer is auto-approved for nothing. Proposing a refresh is not agreeing to
> withhold whomever the form defaulted to. (frost-share-refresh.md, Phase 7)

`nothing of a member's own goes out before they approve` in `ShareRefreshRitualJvmTest`
asserts it. So tapping **Rotate shares** puts a "Take part in rotating the shares" row
in front of the person who just tapped it, and later a "Store your new share" row.

The reason given is real, but it describes the screen, not the protocol. The plain
button runs no confirmation step at all. It withholds every stale member automatically
and never says so. The way to fix that is to have the tap state what it agrees to, not
to ask a second time. The ceremony has already settled this:
`ChillDkgRitualManager` approves the coordinator's host key at proposal
(`ChillDkgRitualManager.kt:308`), and since automatic-key-ceremony.md both rounds are
automatic for everyone.

**Recommendation: yes.** Once the tap goes through a confirmation that names who is
withheld and as what, approve the proposer's `DEAL` and `CONFIRM` at proposal.

- `DEAL` is the decision about the withheld list. A proposer who has been shown the
  list and confirmed it has made that decision.
- `CONFIRM` guards against "a coordinator substituting a table the recipients never
  dealt". The proposer *is* the coordinator, so it cannot defend against itself. The
  CertEq check its device runs is what does the work, and a tap adds nothing to that.

Approval stays exactly as it is for every *other* recipient. Whether `CONFIRM` should
become automatic for everyone, as round 2 did in the ceremony, is a separate decision.
It is listed under [Not in this plan](#not-in-this-plan).

The key-state proposal at the end of a rotation already approves its proposer:
`FrostSigningManager.proposeSigning` sets `signApprovedAt` (`FrostSigningManager.kt:364`).
Nothing changes there.

---

## Phase 0: decisions

| # | Question | Recommendation | Why |
|---|---|---|---|
| R1 | What counts as "a proposal for a group-signed key is pending"? | Any of: (a) a refresh for the key not yet `COMPLETE`/`FAILED`, which is today's check; (b) the key state the group has signed for the room names a generation **below** this device's active one; (c) an open `FrostSigningSession` for the key whose single item is a 30326, at any generation, rebinds included; (d) the signed state names a generation **above** this device's active one, so this device is behind | (b) covers the window before the coordinator's proposal arrives and the case where it failed. (c) covers a Phase 10b rebind and the ceremony's first state. (d) catches a device that missed a certificate, whose proposal every other device would refuse |
| R2 | Is the check made on the proposer's device only, or by recipients on arrival as well? | **On the proposer's device only.** Recipients keep today's refusals | A recipient that refuses on arrival broadcasts a failure. A device a few seconds behind on the key-state signature would then kill a valid rotation. The rule is "don't stack unproven generations from here", and the proposer is the device that can follow it |
| R3 | Does a running **repair** block rotation? | **No**, unless its rebind key state is open, which R1(c) already covers. *Since then, an enrollment does, until it completes or fails* ([admin-change-rotation.md](./admin-change-rotation.md), A8) | `ShareRepairManager` has no abandon. A repair whose target never comes back would disable the button for good |
| R4 | What if the key state is never signed? | The Shares section offers **Ask the group to sign the new shares**, which calls `proposeGeneration` again ("anyone can propose it again", Phase 8), but only when R1(b) holds and no R1(c) session is open | Without this, a coordinator who disappears after `COMPLETE` disables the button for everyone, with nothing offered to fix it |
| R5 | Is the button hidden or disabled? | **Disabled, with a line saying why** | The user asked for it disabled. A missing button looks like a lost feature, and a disabled one with a reason tells people what they are waiting for |
| R6 | Does the proposer's confirmation name the withheld? | Yes. The plain button gets a dialog in the style of "Rotate shares without them?", listing every stale member who will be withheld again (or saying nobody is) | That is the condition the Phase 7 sentence was protecting, and Phase 4 depends on it |

The question R1(c) has to answer before it is relied on: **does a 30326 signing session
that never reaches a quorum ever reach `FAILED` on this device?** If declining it
(`FrostSigningManager.decline`, `:2090`) only stops this device's own participation,
an open session would disable the button indefinitely. Phase 1 checks this first. If the
answer is no, R1(c) counts a session only while it is younger than the signing stall
window, or only while this device has not declined it.

---

## Phase 1: the rule, in the manager

One function that the summary and the propose paths both read, so the screen and the
protocol cannot drift apart. That is the same discipline `pendingApproval` follows with
`advance`.

**`ShareRefreshManager`**

```kotlin
/** Why this device must not propose a rotation of [key] now, or null when it may. */
sealed interface RotationHold {
    data class Running(val refreshId: String) : RotationHold
    data class AwaitingKeyState(val generation: Int, val signingSessionId: String?) : RotationHold
    data class Behind(val groupGeneration: Int, val localGeneration: Int) : RotationHold
}

suspend fun rotationHold(database: DedunDatabase, localChatRoom: LocalChatRoom, key: DkgSession): RotationHold?
```

- `Running`: the current `running(database, key)`.
- `AwaitingKeyState(g, id)`: covers R1(b) and R1(c). `g` is the active generation,
  read through `ShareGenerations.active`. `id` is the open 30326 session, if there is
  one, found from `frostSigningSessionDao().getOpenSessionsForKey(key.id)` with the
  item read by `getItems`. The signed generation is
  `GroupKeyStateManager.keyStateFor(database, roomId)?.generation ?: 0`.
- `Behind`: R1(d).
- Generation 0 with no open 30326 session is never held. It needs no new key state
  (`proposeGeneration` refuses it).

**`proposeRefresh`** keeps handing back a running refresh. That is what
`a second proposal while one runs hands back the first` asserts. For `AwaitingKeyState`
and `Behind` it throws an `IllegalStateException` carrying the reason, which
`DatabaseChatRepository.failureOf` already turns into the snackbar text.
`proposeWithout` goes through `proposeRefresh`, so the removal dialog is covered too.

**Tests**, in `ShareRefreshRitualJvmTest`:

- `the next rotation waits until the group has signed the last one`: refresh to
  generation 1, then check that `proposeRefresh` throws naming generation 1. Approve
  the key-state signing on a quorum (`approveSigning`), and the next refresh proposes.
- `a rebind waiting on signatures holds a rotation`: uses the Phase 10b fixture.
- `a device that missed the certificate cannot propose from the old generation`.
- `rotating without a member waits as well`: `proposeWithout` while generation 1 is
  unsigned.
- the R1(c) question above, answered by a test whichever way it goes.

**Existing tests that will break, as intended:**
`the recovery walk rebuilds the live share three refreshes on` runs three refreshes
back to back with no key state signed in between, which is exactly what this phase
forbids. Add `approveSigning(all)` after each round. Before relying on it, check that
`ShareGenerations.wipeBefore` leaves the recovery data the walk reads, which should hold
because it nulls only `secretShare`. If it does not, that is a real bug for restoring
after a wipe, and it has to be fixed rather than worked around in the test.

**Done when** the new tests pass and `jvmTest` is green.

---

## Phase 2: the Shares section says why

**`SharesSummary`.** `canRotate: Boolean` becomes something the screen can explain:

```kotlin
/** Whether this device may propose a rotation, and if not, what it waits on. */
val rotation: Rotation

sealed interface Rotation {
    data object Unavailable : Rotation                     // no live share, or the flag is off: no button
    data object Available : Rotation
    data class Held(val reason: Reason, val canAskForSignature: Boolean) : Rotation
    enum class Reason { ROTATION_RUNNING, AWAITING_SIGNATURES, BEHIND }
}
```

`sharesSummary` maps `rotationHold` onto it. `canAskForSignature` is
`AwaitingKeyState` with `signingSessionId == null` (R4).

**`ChatRoomDetailScreen`** (`:438-469`):

- The caveat text and the button are shown for `Available` and `Held`. For `Held` the
  button has `enabled = false`, and the line below it comes from the reason:
  - `rotation_held_running`: "A rotation is in progress"
  - `rotation_held_awaiting_signatures`: "Waiting for the group to sign the new shares
    before another rotation"
  - `rotation_held_behind`: "This device is catching up with the group's latest
    rotation"

  These go in sentence case, through the catalogue, coloured `onSurfaceVariant` like
  the caveat.
- When `canAskForSignature` is true, a `TextButton` **Ask the group to sign the new
  shares** calls a new `ChatRepository.proposeKeyState(localChatRoom)`. That
  calls `GroupKeyStateManager.proposeGeneration(..., refreshId = <the completed
  refresh's id>)` and reports through `notify`.
- With `AWAITING_SIGNATURES` and an open session, the line links to the room's
  proposals, using the same `ProposalListRoute` the overflow already opens (`:622`).
  The proposal waiting on the reader is one tap away.
- **The removal dialog.** `ChatRoomDetailViewModel.removeMember` raises
  `rotationOffer` only when `holdsShare` is true *and* rotation is `Available`. When
  it is `Held`, the snackbar says the member still holds a share and a rotation can be
  started once the group has signed the current one. The offer is not shown and then
  refused.

**The double tap.** `sharesAction` sets `isActionPending` for its duration, and every
Shares section button reads it as `enabled = !isActionPending.value && …`.

**Tests.** A `sharesSummary` test in `jvmTest` for each `Rotation` value, built on the
ritual harness. Plus the M3 audit: no literals, no `.dp`, sentence case.

**Done when** `./gradlew :composeApp:m3Audit` and `:composeApp:jvmTest` pass, and the
section has been looked at on the desktop target in each of the three held states.

---

## Phase 3: the button comes back on its own

Re-read the shares summary whenever something it depends on changes, instead of only
after this screen's own actions.

- In `ChatRoomDetailViewModel`, collect `combine(groupKeyStateDao().observeByChatRoomId(id),
  frostSigningSessionDao().observeSessionsForChatRoom(id),
  shareRefreshDao().observeSessionsForRoom(id))` through a new
  `ChatRepository.observeSharesInputs(chatRoomId): Flow<Unit>`. Debounce it, and
  recompute **only** `shares` on the `Loaded` state with `copy(shares = …)`. A full
  `initiateChatRoomDetail` would reload the subgroups and the profile as well.
- Repairs are left out on purpose (R3). Their pending approvals still refresh on the
  existing paths.

**Done when** a test drives the view model with a fake repository whose flow emits
after a key state is filed and sees `rotation` move from `Held` to `Available`. Also
check by hand on desktop that approving the key state from a second device re-enables
the button without leaving the screen.

---

## Phase 4: the proposer is not asked about their own rotation

Only after Phases 1–3, and only together with R6's dialog.

**The confirmation.** The plain **Rotate shares** button opens an `AlertDialog`:

- title: `rotate_shares_confirm_title`, "Rotate the group's shares?"
- body: the existing caveat, plus a line built from `SharesSummary.members` with
  `standing == STALE`: "%1$s will be left out again until they are brought back", or
  "Every member with a share takes part" when there are none.
- confirm: **Rotate shares**. Dismiss: **Not now**.

The "Rotate shares without them?" dialog already names the person being retired, so it
counts as R6's confirmation as it stands.

**The manager.** In `proposeRefresh`, the new `ShareRefreshSession` is created with
`dealApprovedAt = now` and `confirmApprovedAt = now`, and the KDoc at
`ShareRefreshManager.kt:111-116` is rewritten to state the rule and its reason: the
proposer confirmed the withheld list, and is the coordinator whose substitution
`CONFIRM` guards against. As a result:

- `advance` publishes the proposer's round 1 straight after the proposal, so other
  devices see "dealt" from the proposer at once.
- `pendingApproval` is null for the proposer throughout, so no "Take part" or
  "Store your new share" row appears for them, and `announceApprovalNeeded` writes no
  "approval needed" line for them.
- Other recipients are asked exactly as before.

**Watch:** two members who propose from the same base before either hears of the
other. With both proposers auto-confirmed, each has confirmed its own refresh, and D3a
stops it confirming the other. Each refresh needs every recipient, so at most one can
certify, and possibly neither. That is the same outcome as two people who each tapped
**Approve** on their own, so no new failure mode appears. It just arrives faster. Add a
test that shows it ends in one completed refresh or in a state a recipient can
**Abandon**, and never in two certificates.

**Tests.**

- `nothing of a member's own goes out before they approve` is rewritten: the proposer's
  outbox holds `PROPOSAL` and `ROUND_1`, every other device's holds nothing, and only
  the others are asked. Its last assertion, that the proposer *is* asked, is inverted.
  This test is the one that records the decision, so its name and comment change along
  with it.
- `approveAll` still works unchanged, since it skips devices that have nothing pending.
- the concurrent-proposal test above.

**Strings.** `share_rotation_requested` becomes "Share rotation proposed; every other
member is asked to take part". `StringCatalogueJvmTest` needs no change.

---

## Phase 5: the record

- In `frost-share-refresh.md`, add rows to "Where it stands" for this work. Rewrite the
  Phase 7 sentence "The proposer is auto-approved for nothing" as the new rule and its
  reason. Add "a rotation whose key state the group has not signed" to Phase 7's list of
  refusals, marked as the proposer's own check only (R2).
- Mark this file **Built**, with the commits, in the manner of
  automatic-key-ceremony.md's table of "what the plan said / what it turned out to be".
- `./gradlew check` runs the m3, brand and wallet audits.

---

## Not in this plan

- **`CONFIRM` automatic for every recipient**, the ceremony's round-2 decision applied
  to refresh. It is defensible on the same argument, but it changes what every member
  is asked, so it needs its own decision. *Since decided and built:*
  [rotate-shares-single-approval.md](./rotate-shares-single-approval.md), which also
  lets the new generation's key state sign itself and replaced this note's confirmation
  dialog with one read from the proposal's own plan.
- **Recipients refusing a proposal whose base key state they have not seen signed**
  (R2). Without D5's capability check there is no way to tell "behind" from "never
  will".
- **An abandon for repairs**, which R3 would need before a repair could hold rotation.
- **The refresh screen** that Phase 11 of frost-share-refresh.md described and the
  Shares section replaced. The held state's line is the only progress shown.

## Files

| file | phase |
|---|---|
| `managers/ShareRefreshManager.kt`: `rotationHold`, `proposeRefresh` | 1, 4 |
| `database/repository/DatabaseChatRepository.kt`: `sharesSummary`, `proposeKeyState`, `observeSharesInputs` | 2, 3 |
| `repository/ChatRepository.kt` | 2, 3 |
| `ui/view/state/SharesSummary.kt`: `Rotation` | 2 |
| `ui/view/model/ChatRoomDetailViewModel.kt`: `isActionPending`, the observer, the removal offer | 2, 3 |
| `ui/composable/ChatRoomDetailScreen.kt`: disabled button and reason, ask-to-sign, the confirmation dialog | 2, 4 |
| `composeResources/values/strings.xml` | 2, 4 |
| `jvmTest/.../ShareRefreshRitualJvmTest.kt` | 1, 4 |
| `docs/frost-share-refresh.md` | 5 |
