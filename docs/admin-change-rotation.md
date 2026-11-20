# An admin change carries its rotation with it

A plan for two changes that belong together:

1. **The rotation an admin change needs runs without asking anyone again.** The group's
   threshold signature on "remove Alice as admin" is the decision. The rotation that takes
   Alice's share away is what that decision means, and asking every remaining holder to
   take part in it repeats a question the group has already answered.
2. **Admin changes and rotations take turns.** No admin is added or removed while a
   rotation is pending, and no rotation starts while an admin change is on its way.

Read [member-admin-promotion.md](./member-admin-promotion.md) first (R1, D2, D10 and
Phases 8 and 9), then [rotate-shares-single-approval.md](./rotate-shares-single-approval.md)
and [rotation-waits-for-key-state.md](./rotation-waits-for-key-state.md). This plan
follows the rule those two set, that **a device asks its owner only for decisions it cannot
make on its own**, to the one ask they left in the admin-change flow.

**Built**, one commit per phase on `claude/rotating-shares-admin-approval-edd90c`
(`4550201d` to the commit that adds this header; Phases 3 and 4 are one commit, since
they ship together), against `b7de119e`, on 2026-11-20. A removal's rotation now asks
nobody: the only taps in a removal are the `t` signatures on the change. jvmTest
1411, `m3Audit` at its budgets. The phases are kept as written because they are the
reasoning. Where the build chose differently:

| what the plan said | what it turned out to be |
|---|---|
| H1: `rotateAway` is refused "a rotation is still running", says so, and gives up | worse: it is not refused. `proposeRefresh` hands a running rotation back, so `rotateAway` marked the change done **silently**, and the removed member got a share of the next generation with nothing said. Phase 4 checks the rotation it is handed and says so when it still deals to them |
| H2: the new id is left `STALE` at `g+1` | worse: the rotation **fails**. The binding grows the base generation's table under a rotation dealt against the old one, and round 2's own check refuses the result ("what was dealt does not match the commitments") on every device |
| Phase 1: pin H5 as "the request is deleted" | the governance harness's room is at its admin floor, so the request was used up by the floor whatever the rotation did. Pinned as used up, then rewritten in Phase 3 to show it kept through the rotation and taken up after |
| Phase 2: the Shares section's two new held reasons land in Phase 4 | in Phase 2, since the `when` over `RotationHold` is exhaustive |
| Phase 2: `AdminChangeInFlight` counts the proposer's rows "signed or published and not yet applied" | `SIGNING` or `PUBLISHED`, and the group not yet past the proposal's epoch, which is how `rotationsOwed` tells a change that landed unwatched. A statement session this device declined does not hold it, as for key states |
| Phase 2: `Enrolling` holds until the enrollment completes or fails | also not for a bound member the live group does not name an admin. A promotion whose commit went stale leaves a signed binding nobody will enroll, which would otherwise hold every rotation for good |
| (not in the plan) | the share rituals' test devices hold no MLS group, so nothing there can read an admin policy. `RoomAdmins` reads it off the live group, with an `override` the tests set, as `ExperimentalFeatures.shareRefreshOverride` stands in for the build. As with member-admin-promotion, no test runs a whole removal with real MLS and real FROST together: the governed-room tests have no FROST key, and the ritual tests stand the policy in |
| Phase 3: a test that the freeze also holds while the key state is unsigned | in the ritual harness (`an admin change waits on a rotation until its key state is signed`), where a real rotation produces one; the governance harness's seeded key has no generations |
| Phase 3: **See the rotation** goes to the group's Shares section | back, to the group's screen beneath, where the Shares section is |
| Phase 3: *"Waiting on Carol since yesterday"* | the names only, while the rotation is dealing. The card in the Shares section has the time |
| A12's test goes through `GovernanceManager.propose` | through `ShareRefreshManager.owedRotationRefusal` on a real six-holder roster with a stale member. The governance harness cannot make a member stale |
| Phase 5: `takePartIfNothingNew` called on arrival, after a commit that changes admins, and in the sweep | once, at `advance`'s one gate, which arrival, a replay and the sweep all reach. The commit-time trigger was not built: a rotation travels as an inner event at the epoch after the commit it follows, so no device reads it before applying that commit, and the sweep covers a session an older build left asking |
| Phase 5: the transcript line in place of the approval-needed one | with a type of its own, `TYPE_SHARE_REFRESH_TOOK_PART`, in both share-refresh sets so it renders as a notice |
| Phase 6: `offer` proposes the rotation | `offer` takes the account key, which `DatabaseChatRepository.promoteMember` passes through `MarmotOutboundDao.promote`. Without it, the refusal is as before |
| Phase 6: two admins offering at once start one rotation, and both offers go ahead after it | the plan contradicted itself: its own freeze refuses the second offer while the first one's rotation runs, and that is what the build does. Two devices offering in the same moment each propose a rotation; the two refuse each other on arrival, as any crossing rotations do, and both offers end out loud once nothing is running |
| Phase 6: the whole first promotion in one test | in parts: the offer starting its rotation, the wait, and the abandon in the governance harness, and the rotation running unasked in the ritual harness (A2). The same harness gap as above |
| Phase 7: the member row's line comes from member-row-actions Phase 5's pending lines | that phase did not leave a line on the row to extend. **Holds a share** reads **Share being rotated away** instead while a running rotation takes it, from `SharesSummary.Running.takingAway` |
| (not in the plan) | two full `jvmTest` runs each had one identity test time out at 5 seconds (`MultipleProfilesRoundTripJvmTest`, then `IdentitySwitchJvmTest`). Both pass alone, repeatedly, neither starts the governance follow-through, and a third full run passed whole. Load, not this work; not investigated |
| (not in the plan) | this worktree's build needed its submodules initialised, from the main checkout's copies, and their `local.properties` copied in |

**Not done:** the emulator session in Phase 8. Nothing here has run on a device: not the
held menu, not the first promotion's rotation, not a removal on three phones.

Written against `b7de119e`, on 2026-11-20.

---

## What a group goes through today

### Removing an admin (Remove as admin, Remove from group, or Step down)

| # | step | who | asked? |
|---|---|---|---|
| 1 | propose the change: `MarmotOutboundDao.demote` / `removeMember` → `GovernanceManager.propose`, with `thenRotateWithout` set when the member holds a live share | an admin (for a step-down, `AdminGovernance.stepDownProposer`) | the button, behind a dialog |
| 2 | sign the governance statement (30370) | `t` holders | **yes, the decision.** Stays asked |
| 3 | publish, apply the commit everywhere | proposer, then every device | no |
| 4 | start the rotation without the member: `GovernanceFollowThrough.rotateAway` → `ShareRefreshManager.proposeWithout` | proposer | no (proposing is the proposer's approval) |
| 5 | take part in the rotation (`ShareRefreshApprovalStep.DEAL`) | **every other remaining holder** | **yes: the ask this plan removes** |
| 6 | rounds, certificate, key state at `g+1`, wipe `g` | every device | no (rotate-shares-single-approval) |

So a removal in a 3-of-5 room asks three people to sign it, and then asks all four
remaining holders, including those three, whether they agree to the rotation the
signature already required. A holder who never opens the Shares section holds the
rotation open indefinitely. Meanwhile the demoted member's share still counts toward `t`
(D10), and their row reads **Holds a share** without **Admin**.

### Adding an admin (Make admin)

Promotion is an enrollment, not a rotation (member-admin-promotion Phase 8). After the
target accepts, the group signs one batch (the statement and the key state binding id `n`),
the handover goes out, and `t` helpers enroll the new id without being asked. The only
rotation in this flow is the one that has to come first:

```kotlin
// PromotionManager.offer
check(ShareGenerations.active(database, key).generation >= 1) {
    "Rotate the group's shares once before adding an admin: the key's first generation is its ceremony's, and has no room for a new member"
}
```

Every keyed room starts at generation 0, so the **first** promotion in every room fails
with this message. The admin then has to go to the Shares section, rotate, wait until
every holder has tapped **Approve**, wait until the key state is signed, and come back to
press **Make admin** again.

### What nothing prevents today

Admin changes and rotations both act on the same thing, the roster of who holds which id
at which generation. Nothing stops them from overlapping:

| | overlap | what goes wrong |
|---|---|---|
| H1 | an admin is demoted while a rotation `g → g+1` is running | `rotateAway` is refused ("a rotation is still running"), says so in the room, and gives up. The running rotation was proposed before the demotion, so **the demoted member is one of its recipients and receives a fresh share at `g+1`.** D10's window stays open until somebody notices and rotates again |
| H2 | a promotion's key state is signed while a rotation is running | `enrollNewId` adds id `n` at `g`, as `STALE`. The rotation's proposal was fixed against the roster before `n` existed, so id `n` is neither a recipient nor withheld. At `g+1` the new admin is stale, and needs a repair instead of the enrollment they were promised |
| H3 | a rotation is started while an enrollment is owed or running | the new id is `STALE` at `g` until enrolled, so `plan` withholds it as stale. Same outcome as H2, reached from the other side |
| H4 | a governance statement is being signed at `g` when the rotation's key state at `g+1` is signed | `FrostSigningManager.failRotated` fails every open session below `g+1`, the statement included. Its signers' approvals are lost, and the change has to be proposed again |
| H5 | a step-down request or an accepted offer arrives during a rotation | `followStepDowns` and the accepted-offer loop each run once, are refused, and **drop** the request (`deleteById`, `failedOffers`). The person who asked has to ask again |

H1 is the serious one. It is a removed admin who can still help sign, and the room shows
nothing different from the case where the rotation succeeded.

---

## Why the rotation's ask is not a decision

`DEAL` asks one question: *do you agree to take part, with these members withheld?*
(rotate-shares-single-approval R1). Withholding someone takes their signing power away, so
the withheld list is the whole of the decision. Everything else about a rotation is
arithmetic the device checks for itself.

The withheld list a rotation after an admin change carries has two parts, and the group
has already decided both:

- **Retired:** the member the group just removed as admin. In a governed room the admin
  policy changes only through a commit the group's threshold signature carries
  (member-admin-promotion R2, `GovernanceGate`). If the live group no longer names them an
  admin, `t` holders already signed that, and R1 says a non-admin holds no share.
- **Stale:** members who already missed a rotation. `plan` withholds them again as what
  they were (`ShareRefreshManager.kt:124`). Withholding them takes nothing new: they cannot
  sign at `g` now and will not at `g+1`. A repair, not a rotation, brings them back.

A device can check both against state it already trusts, the live MLS group and its own
roster at the base generation. If both hold, a person has nothing to add. If either fails,
someone is being withheld whom the group has not decided about, and the owner is asked as
they are today.

The same rule covers the rotation before a first promotion, which withholds no one new at
all.

---

## Phase 0: decisions

**An hour. Each has a recommendation.**

| # | question | recommendation | if decided otherwise |
|---|---|---|---|
| A1 | How does a recipient know a rotation follows an admin change? | **It doesn't need to. It checks that the rotation withholds nobody new** (the rule above), from the live group and its roster. No new tag, nothing to look up, and nothing a proposer can claim that the device does not check | A `frost_follows` tag naming the signed statement (30370) the rotation follows. The recipient looks up the statement and checks that it removes exactly the retired ids. The coupling is narrower, but it adds a wire change, and the statement has to have been stored and found, which a device that caught up from a later epoch may not have done |
| A2 | Does A1 also take part, unasked, in a **manual** rotation that withholds nobody new (the Shares section's **Rotate shares** with only stale members left out)? | **Yes.** It follows from A1. That rotation takes nobody's signing power, which is the only thing `DEAL` ever decided. Abandon stays for a member who objects to the rotation itself | Only with A1's alternative tag. A manual rotation then keeps its ask, and the generation-0 rotation before a promotion (A7) needs a tag of its own |
| A3 | Should every rotation withhold, as retired, each live holder the group no longer names an admin? | **Yes.** `plan` adds them, so R1 holds whichever rotation runs first. This closes H1's race from the proposer's side: a manual rotation that runs between the commit and `rotateAway` takes the demoted share away too | `rotateAway` stays the only rotation that retires anyone, and the race in H1 has to be closed by the freeze alone |
| A4 | Where does A1 and A3 apply? | **Governed keyed rooms only, and never to a founding member that `FoundingAdmins.check` reports `Missing`.** The epoch-0 admin list is the creator's and is not signed. A founder left out of it by a buggy creator is not a demoted admin, and their share is not to be taken away silently | — |
| A5 | What counts as "a rotation pending" for the freeze? | **`ShareRefreshManager.rotationHold != null`**: a refresh running, the new generation's key state not yet signed, or this device behind. It is the same rule the **Rotate shares** button already follows, and it ends at the same moment the old shares are wiped | Only a running refresh. H4 stays open: a statement being signed when the key state lands is failed by `failRotated` |
| A6 | Which changes does the freeze stop? | **Every change to who is an admin, and every removal of a member who holds a live share.** Promotion offers, accepting an offer, proposing a promotion, demotion, removal of a holder, and step-down. **Not** inviting or removing a member who holds no share. Those do not touch the roster, and the worst H4 does to them is a signing session failed out loud and safe to repeat | Freeze every governed commit. Simpler to state, but a rotation stalled on an offline holder would then stop the room from inviting anyone until someone abandons it |
| A7 | What does the freeze do to a request that arrives during it, such as a step-down or an accepted offer? | **It waits.** The request is kept and taken up by the follow-through's next pass after the hold clears. Today's failure path (drop and announce) stays for real refusals, such as the floor | Refused and dropped, as H5. The requester asks again |
| A8 | Does an enrollment hold rotation? | **Yes, while one is owed or running**: an id the signed key state binds at the active generation, `STALE` here, and with no enrollment for it that has failed. A failed enrollment releases the hold, since a later repair brings that id back at any generation | No, as rotation-waits-for-key-state R3 decided for repairs. H2 and H3 stay open |
| A9 | A promotion at generation 0? | **The offer starts the rotation, and the promotion waits for it.** **Make admin** at generation 0 says, in its dialog, that the shares are rotated first. The offer goes out at once. Its proposal (step 3) waits in the follow-through until the generation-1 key state is signed. Nobody presses anything twice | (b) The refusal stays and says what to do, as today. (c) Every keyed room rotates once at birth, from `CeremonyFollowThrough`, so generation 0 is never seen by an admin. That costs a rotation, which needs every member online, for every room, including the many that never promote |
| A10 | A timer? | **No**, as in both earlier notes. The freeze raises the cost of a stalled rotation, so the explanation screen for a frozen action names who the rotation waits on and links to the Shares section's running card, where **Abandon** is | — |
| A11 | Wire or schema? | **Neither.** No kind or tag (A1). No column: `dealApprovedAt` records the unasked approval as it records the asked one, and the transcript line says which | — |
| A12 | Is a demotion or removal of a holder refused when the rotation it owes would be? | **Yes, before anyone signs.** The admin floor counts admins; the rotation's `refusal` counts recipients against every id not yet retired, stale ones included. After an abandoned rotation the two can disagree (see [After an abandon](#after-an-abandon)), and a demotion that lands with a rotation nobody can run leaves a non-admin holding a live share with no way to take it away but a repair first | The change lands, `rotateAway` says the rotation was refused, and the room repairs before it can rotate |

**Decided 2026-11-20: all twelve as recommended.** A12 was added after the first draft,
from a review of what an abandon leaves behind.

### After an abandon

Abandoning a rotation releases the freeze at once: `abandon` fails the session everywhere,
nothing is running, and the active generation is still the one the group signed, so
`rotationHold` and `adminChangeHold` are both null. What it leaves is a live share held by
someone who is no longer an admin, D10's window still open, and a **Rotate shares** offer
in the Shares section. Nothing restarts the rotation by itself (A10).

Under A3 the next rotation, whoever starts it and for whatever reason, retires that share
too, so an abandon delays the cleanup rather than losing it. But retirements owed by
abandoned rotations add up. Six holders at `t = 3`, Carol stale, Alice demoted and her
rotation abandoned: demoting Bob keeps four admins, above the floor, yet the rotation it
owes withholds Alice, Bob and Carol, three of six, which is not a majority. A12 refuses
Bob's demotion before it is signed, with words that say what to fix: rotate first, or
bring Carol back.

---

## Phase 1: pin today's behaviour

**Half a day. Before changing anything.**

The overlaps are bugs and the asks are by design, so both need a test that states them
before either changes. Every test below fails, or is inverted, in a later phase that names
it.

In `GovernanceGateJvmTest` and `ShareRefreshRitualJvmTest`, on their existing fixtures:

- **the ask, counted.** A governed 2-of-4 room with real FROST. Demote one admin through
  `GovernanceManager.propose` with `thenRotateWithout`, sign with `approveSigning`, land it,
  and run `GovernanceFollowThrough.pass` on the proposer. Then `pump` without `approveAll`.
  Every remaining holder but the proposer is at `pendingApproval == DEAL`, and nobody has
  reached `g+1`. `asksOf` (from rotate-shares-single-approval Phase 1) is 1 for each.
  *Phase 5 inverts this.*
- **H1, pinned.** Start a rotation, then demote and land the demotion before the rotation
  completes. `rotateAway` writes `TYPE_CHANGE_NOT_APPLIED`, and at `g+1` the demoted member
  holds a `LIVE` share. *Phases 3 and 4 make this impossible, and the test then asserts
  that the demotion was refused while the rotation ran.*
- **H2, pinned**, on `a new member is enrolled at the next id`'s fixture: sign a promotion's
  key state while a rotation runs. At `g+1` the new id is `STALE`.
- **H4, pinned.** A statement opened for signing at `g`, then the rotation's key state
  signed. The statement's session is `FAILED` with `failRotated`'s reason.
- **H5, pinned.** A step-down request taken up while a rotation runs is deleted.
- **The first promotion, pinned.** `PromotionManager.offer` at generation 0 throws the
  generation message. *Phase 6 inverts this.*

**Done when** each pinned test passes against today's code and names the phase that
changes it.

---

## Phase 2: one rule for what waits on what

**Half a day.**

Both directions read one function each, from one file, so the menu, the Shares section,
the managers and the follow-through cannot disagree. This is the same discipline as
`rotationHold` and `pendingApproval`.

**`RotationHold`** gains the two reasons a rotation now waits on an admin change:

```kotlin
/** A change to who is an admin, or a removal of a holder, is being signed or has not landed yet. */
data class AdminChangeInFlight(val signingSessionId: String) : RotationHold

/** A new admin's id is bound and not yet enrolled; a rotation now would leave them stale. */
data class Enrolling(val memberPublicKey: HexKey) : RotationHold
```

`ShareRefreshManager.rotationHold` adds them after its existing checks:

- `AdminChangeInFlight`: an open `FrostSigningSession` in the room whose items include a
  `GovernanceStatementEvent` that changes `admins`, or that `remove`s an account holding a
  live share (read with `GovernanceStatementEvent`'s parsers, which the gate already uses).
  Every signer holds that session, so every device that could propose a rotation sees it.
  The proposer also counts its own `GovernanceProposal` rows that are signed or published
  and not yet applied, which no other device has a session row for any more.
  **Not** a row that is `APPLIED` and owes a rotation: that rotation is the next step, and
  `rotateAway` must not be held by the change it follows.
- `Enrolling` (A8): an id the room's signed key state binds at the active generation,
  `STALE` in the roster, with no `ShareRepairSession` for it `FAILED`.

**`AdminGovernance.adminChangeHold(database, localChatRoom): RotationHold?`** is the other
direction. It returns `rotationHold` restricted to `Running`, `AwaitingKeyState` and
`Behind` (A5). It does **not** return the two new reasons, since an admin change must not
wait on itself.

**Tests**, in `ShareRefreshRitualJvmTest`:

- each new reason, from a fixture built for it, and `null` once it clears: the statement
  signed and applied, the enrollment complete, the enrollment failed;
- an `APPLIED` proposal that owes a rotation does not hold `rotateAway`;
- `adminChangeHold` is `null` while only an admin change is in flight.

---

## Phase 3: no admin change while a rotation is pending

**A day. Closes H1 from the admin side, H4 and H5.**

**The managers refuse**, in words a person can act on, before anything is proposed:

- `GovernanceManager.propose`, after `prepare`, when `prepared.change` changes `admins` or
  removes a live holder (A6) and `adminChangeHold` is not null:
  *"The group's shares are still being rotated. Admins can change once the group has signed
  the new shares."* `MarmotOutboundDao.demote`, `removeMember` and `PromotionManager.propose`
  all pass through here, so one check covers them.
- `GovernanceManager.propose` again, for a change that demotes or removes a live holder
  (A12): it runs `ShareRefreshManager.plan`'s `refusal` over the rotation that change
  would owe -- every live holder who is not an admin after it retired, this member
  included, and the stale withheld again -- and refuses the change in that refusal's
  words when there is one: *"Removing them would owe a rotation the group can't run: …
  Rotate the shares or bring stale members back first."*
- `PromotionManager.offer`, with the same words. An offer is not a commit, but an accepted
  one is proposed at once, and an offer the group cannot act on only asks the target to
  wait.
- `PromotionManager.requestStepDown`, with the same words. A step-down request does not
  change the roster itself, but the demotion it leads to does.

**The follow-through waits (A7).** In `GovernanceFollowThrough.pass`:

- `followStepDowns` checks `adminChangeHold` for the request's room before `demote`. While it
  holds, it skips the request and keeps it, so neither the delete nor the announce happens.
- The accepted-offer loop does the same before `PromotionManager.propose`, and does not add
  to `failedOffers`.
- `observe` already re-runs `pass` when a key state (`GroupKeyStateEvent.KIND`) is filed,
  which is the moment `AwaitingKeyState` clears. Add `shareRefreshDao().observeAll()` (or the
  running count) to the `combine`, so an abandoned or failed rotation also wakes it.

**The menu says why.** A new `MemberActionBlocker.RotationPending`, filled into
`MemberActionContext` by `DatabaseChatRepository.memberActionOptions` from
`adminChangeHold`. `MemberActionPolicy` adds it to the role action of every row and to
**Remove from group** for a holder (not for a member who holds no share, A6), and to
**Step down as admin**. The step-down row lists nothing it cannot do (member-row-actions
D1), so it is omitted there rather than shown blocked. It is listed with the work in flight,
after `ChangePending`, as member-row-actions D4 orders them.

`MemberActionUnavailableScreen` gets its arm: an icon (`Icons.Default.Sync`, with a
`contentDescription` decided), *"The group's shares are being rotated"*, and a line built
from `SharesSummary.running`: *"Waiting on Carol since yesterday. Admins can change once the
group has signed the new shares."* A **See the rotation** button goes to the room's detail
screen at its Shares section, where **Abandon** is (A10).

Strings go in `strings.xml`, in sentence case. Spacing, colour and targets come from the
theme, as CLAUDE.md requires.

**Tests:**

- `GovernanceGateJvmTest`: a demotion proposed while a rotation runs is refused with the
  words, and nothing is signed or stored. The same holds while the rotation's key state is
  still unsigned. An invite of a member who holds no share is **not** refused (A6).
- H1's pinned test, rewritten: the demotion is refused, the rotation completes, the demotion
  is proposed again and lands, and the next rotation retires the member.
- A12: with a non-admin's live share left by an abandoned rotation and a stale member, a
  demotion whose owed rotation would not be a majority is refused before signing, and
  one that would be is allowed.
- H5's pinned test, inverted: the step-down request survives the rotation and is proposed by
  the first pass after the key state is signed, with no new request.
- `MemberActionPolicyTest`: `RotationPending` on each row it applies to, absent on a
  non-holder's **Remove from group**, absent once the hold clears.
- `MemberActionUnavailableScreenJvmTest`: the new arm renders with its waiting-on line.

---

## Phase 4: no rotation while an admin change is on its way

**Half a day. Closes H2, H3, and H1 from the rotation side.**

Phase 2's new reasons already reach every rotation path through `rotationHold`:
`proposeRefresh`, `preview`, the Shares section's button, and `proposeOwedKeyState`. What is
left:

- **`SharesSummary.Rotation.Reason`** gains `ADMIN_CHANGE` and `ENROLLING`, mapped in
  `sharesSummary`. The Shares section's held line:
  - `rotation_held_admin_change`: *"Waiting for a change to the group's admins to finish"*;
  - `rotation_held_enrolling`: *"Waiting for %1$s to receive their share"*.
- **`rotateAway`** treats one outcome as success rather than failure: a rotation that is
  already running here and already withholds the member as `RETIRED` (which, after Phase 5's
  A3, any rotation started after the commit does). It marks the proposal `DONE` and says
  nothing. Every other refusal keeps today's announcement.

**Tests:**

- H2's and H3's pinned tests, rewritten: the rotation is refused while the promotion's
  statement is being signed and while the enrollment runs. Once the enrollment completes, it
  proposes, and the new admin is a recipient at `g+1`.
- a rotation is allowed once the enrollment fails, and the new id is withheld as stale.
- `rotateAway` after a manual rotation that already retired the member marks it done and
  writes no `TYPE_CHANGE_NOT_APPLIED`.

---

## Phase 5: the rotation after an admin change asks nobody

**A day. The headline. Needs Phase 2.**

**The proposer withholds what the group removed (A3, A4).** `ShareRefreshManager.plan`
takes the live group's admins and, in a governed keyed room, adds to the withheld map as
`RETIRED` every `LIVE` holder whose account is not among them, except any founding member
that `FoundingAdmins.check` reports `Missing`. `proposeWithout` keeps working, and its
explicit retirement is now one the plan would have made anyway. `preview` reads the same
`plan`, so the removal dialog and the Shares dialog name the retired member without new
code. The KDoc on `plan` states R1 as the reason.

**The recipient takes part when nothing new is withheld (A1, A2).** A new function, the one
rule both the device and its tests read:

```kotlin
/**
 * Whether [session] withholds nobody the group has not already decided about, so
 * that taking part is not a decision and this device takes part unasked:
 * every stale-withheld id is stale in this device's roster at the base generation,
 * and every retired-withheld id is bound to an account the governed room's live
 * admin policy no longer names, and is not a founding admin the policy is missing.
 * docs/admin-change-rotation.md, Phase 5.
 */
suspend fun withholdsNothingNew(database: DedunDatabase, localChatRoom: LocalChatRoom, session: ShareRefreshSession): Boolean
```

It reads `ShareGenerations.active(database, key)` at the session's base, the room's live
group through `database.marmotEngine.groupFor`, `GovernanceGate.isGoverned`, and
`FoundingAdmins.check`. It copies none of them.

It is called in one place behind three triggers, as `approveIfVouched` is for key states
(rotate-shares-single-approval, Phase 5):

```kotlin
/** Takes part on the owner's behalf when [withholdsNothingNew] says there is nothing to decide. */
private suspend fun takePartIfNothingNew(database: DedunDatabase, localChatRoom: LocalChatRoom, session: ShareRefreshSession): Boolean
```

1. **On arrival**, at the end of `acceptProposal`, before `replayStoredMessages`. A
   rotation is sent as an inner event of the keyed room, so it is readable only at the
   epoch after the commit it follows. By the time it arrives, the device has applied the
   demotion. This is the path almost every rotation takes.
2. **After a commit that changes admins**, in `MarmotInboundManager` beside its
   `AdminFlags.reconcile` call (`MarmotInboundManager.kt:234`). For each session in the room
   at `pendingApproval == DEAL`, call it and `advance`. This covers a proposal that was
   stored and replayed before its commit, which the epoch ordering should prevent. It is
   kept because the cost is one query and the alternative is a holder asked for no reason.
3. **The sweep**, in `resumeRunning`, before `advance`. This covers a session an older build
   left asking.

`takePartIfNothingNew` sets `dealApprovedAt` exactly as `approve` does. It skips a session a
person has already answered or abandoned, and it writes a transcript line in place of the
approval-needed line: *"Took part in rotating the shares without asking: it leaves out only
Alice, whom the group removed as admin."* or *"…: it leaves out nobody new."* That line is
built from the session's withheld standings, and `announceApprovalNeeded` is not called for
a session this approves.

**What stays asked.** Any rotation that withholds a member who is still an admin, or marks
as stale a member this device holds as live, or that arrives in a room that is not governed,
is asked exactly as today. Failing the check is not a reason to refuse. It means the device
cannot answer, so a person has to.

**Tests**, in `ShareRefreshRitualJvmTest` and `GovernanceGateJvmTest`:

- Phase 1's counted-ask test, inverted. Demote, sign, land, `pass`, then `pump` with no other
  call. Every device reaches `g+1`, the key state signs itself, `g` is wiped, the demoted
  member's row reads neither **Admin** nor **Holds a share**, and `asksOf` is 0 for every
  holder. The only taps in the whole flow are the `t` signatures on the statement.
- the same for **Remove from group** of an admin, and for a step-down request;
- a manual rotation with one stale member is taken part in unasked (A2);
- each check broken on its own leaves `pendingApproval == DEAL` and writes the
  approval-needed line:
  - a retired-withheld member who is still an admin;
  - a stale-withheld member who is live here;
  - an ungoverned keyed room;
  - a founding admin the policy is `Missing`;
- the proposal replayed before its commit (the harness holds the commit back) is asked on
  arrival and taken part in when the commit applies, with no tap;
- `plan` retires a live non-admin holder in a governed room, and does not in an ungoverned
  one or for a `Missing` founder;
- a person who abandoned before the sweep is not overridden.

---

## Phase 6: the first promotion waits for its rotation instead of failing

**A day. Needs Phases 3 and 5. A9 as recommended.**

- **`PromotionManager.offer`** at generation 0 no longer throws. If no rotation is running,
  it calls `ShareRefreshManager.proposeRefresh` with no withheld members. Every holder takes
  part unasked (Phase 5, A2). Then it sends the offer as usual. A running rotation is
  `running(...)` handed back, as today. Any other refusal from `proposeRefresh`, such as too
  few live holders, is the offer's refusal, in its words.
- **The freeze must not stop this offer.** `offer` checks `adminChangeHold` *before* it
  starts its own rotation, not after. A promotion already waiting on a rotation someone else
  started is refused as in Phase 3.
- **The proposal waits.** The accepted-offer loop in `GovernanceFollowThrough` already
  re-runs on every key state. At generation 0, or while `adminChangeHold` holds, it skips
  the offer (A7) instead of failing it. If there is no running rotation and no hold, but the
  key is still at generation 0, then the rotation was abandoned or failed. The loop announces
  *"Couldn't make %1$s an admin: the rotation it needed did not finish. Make them an admin
  again."* once, and deletes the offer on this device, as `followStepDowns` deletes a
  request it has used.
- **The dialog says so.** `MemberActionUnavailable` is not involved, since the action is
  open. **Make %1$s an admin?** gains a line at generation 0: *"The group's shares are
  rotated first. Nobody needs to approve that, and they are asked once it is done."* The
  repository's `memberActionOptions` already knows the generation, through the same key
  lookup `offer` makes.
- **The target's acceptance** is unchanged. They can accept while the rotation runs, since
  accepting only publishes a host key.

**Tests**, in `PromotionOffersJvmTest` and `ShareRefreshRitualJvmTest`:

- the first-promotion pinned test, inverted. At generation 0, **Make admin** starts a
  rotation and sends the offer. The target accepts at once. With no further call, the
  rotation completes, its key state signs itself, the promotion is proposed, and after the
  `t` signatures the enrollment completes. The target's row reads **Admin · Holds a share**.
- an abandoned preparatory rotation ends the offer out loud, once, and a second pass says
  nothing;
- two admins offering at generation 0 at once start one rotation. The second gets the first
  handed back, and both offers are proposed after it.

---

## Phase 7: the screens

**Half a day. Mostly done by Phases 3, 4 and 6. This phase checks what remains.**

- **The removal and demotion dialogs.** *"Their share is rotated away once the group signs"*
  (member-admin-promotion Phase 11) becomes *"Once the group signs, their share is rotated
  away. Nobody is asked again."*
- **The `DEAL` row** keeps its withheld list and copy. After Phase 5 it appears only for a
  rotation that withholds someone new, which is exactly when its *Without Alice…* line
  matters.
- **The running card** (rotate-shares-single-approval Phase 6) gains a cause line when the
  rotation retires a member who is no longer an admin: *"Taking Alice's share away after
  they stopped being an admin."* This is derived the same way as the transcript line.
- **The member row's pending line** (member-row-actions Phase 5): a demoted member's
  **Holds a share** row says *"Share being rotated away"* while the rotation runs, from
  `SharesSummary.running`, so D10's window is visible as being closed rather than only open.
- `./gradlew :composeApp:m3Audit` at its budgets, floors not moved. Run
  `docs/scripts/m3-title-case.py --list` over the new strings.

**Tests:** a `SharesSummary` test for the cause line and the pending row line, on the
ritual harness.

---

## Phase 8: the record, and three emulators

**Half a day of docs, then a device session.**

- **member-admin-promotion.md**: the build table's Phase 9 row ("each holder is asked once
  to take part") points here. D10's first bullet becomes true as first written: *"the
  refresh starts automatically, with the signed demotion as every holder's approval."* The
  table row about generation 0 ("only from a key past its ceremony's generation") gains
  "since then, the offer starts that rotation itself".
- **rotate-shares-single-approval.md**, *What this does not do*: a line that a rotation
  withholding nobody new is now taken part in unasked, from here.
- **rotation-waits-for-key-state.md**, R3: a repair still does not hold rotation, but an
  enrollment does (A8).
- **frost-share-refresh.md**, Phase 7's approval-gates table: `DEAL` is asked only when the
  withheld list holds a decision.
- **README.md**: this note's row.
- **This note**: a *Built* header and a table of where the build chose differently.

**On three emulators** (Bob, Charlie, Medium) and a fourth device or a desktop window
(Dana), in a debug build so the flag is on, in a 2-of-4 governed room:

1. Bob presses **Make admin** on a new member at generation 0. Nobody approves a rotation.
   The new member accepts, one other admin signs, and the new member's row reads
   **Admin · Holds a share**. The only taps are the offer, the acceptance and one signature.
2. Bob removes Dana as admin. Charlie signs. Nobody else touches anything, and Dana's row
   goes from **Holds a share** to neither.
3. While step 2's rotation runs, Charlie's **Remove as admin** on Medium is listed as not
   open, and its screen names who the rotation waits on. Once it finishes, the action opens
   without leaving the screen.
4. Medium asks to step down during a rotation. The request is taken up when the rotation is
   signed, and Medium does not ask again.
5. Force-stop Charlie mid-rotation. Bob's member menu says what it waits on, and **Abandon**
   from the Shares section releases the freeze on all devices.

`./gradlew check` must pass: `m3-audit`, `brand-audit`, `wallet-audit`.

---

## What this does not do

- **It does not make the governance signature automatic.** `t` holders still sign "remove
  Alice as admin". That is the decision. Everything after it is now its consequence.
- **It does not change older peers.** A Giza build from before this plan asks at `DEAL` as
  today, and a rotation that includes it waits for that tap.
- **It does not run with the app closed.** Each holder's device still has to be opened for
  the rotation to move ([long-running-sync.md](./long-running-sync.md)). The freeze makes
  that matter more: an offline holder now holds every admin change too, and **Abandon** is
  the way out (A10).
- **It does not stand in for a proposer who goes quiet.** `rotateAway` still runs only on
  the proposer's device. A3 means any member's **Rotate shares** does the same work, and the
  Shares section offers it. An automatic stand-in, such as the next admin after a delay, is a
  separate decision.
- **It does not let a governance statement survive a rotation.** H4 is closed by not
  overlapping, not by re-running the statement at `g+1`. Plain invites and removals (A6)
  can still meet it, and are failed out loud as today.
- **It does not freeze membership changes that leave the roster alone** (A6).

## Order and size

| phase | size | depends on |
|---|---|---|
| 0 decisions | an hour | — |
| 1 pin today | half a day | — |
| 2 one rule | half a day | 1 |
| 3 freeze admin changes | a day | 2 |
| 4 hold rotations | half a day | 2 |
| 5 the rotation asks nobody | a day | 2 |
| 6 the first promotion | a day | 3, 5, A9 |
| 7 screens | half a day | 3, 4, 6 |
| 8 docs, emulators | half a day plus a session | all |

About five days. **Phases 3 and 4 ship together**, since each closes one side of the same
overlap, and half a lock invites the race through the other side. **Phase 5 ships after 3
and 4, or with them**: a rotation that runs unasked is a rotation that runs sooner, and
without the freeze it runs sooner into H1. Phase 6 needs both. Everything sits behind
`ExperimentalFeatures.shareRefresh`, so no phase changes a release build.
