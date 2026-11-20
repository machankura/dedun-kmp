# Every founding member of a robust group is its admin

A robust group promises, on the screen that makes it, that "everyone administers the
group together". This note is the plan that made the promise true on every device from
the moment the group exists: what "founding member" means, the four places the promise
broke before, and the eight phases that closed them.

**Built**, phases 0-7, one commit each. The phases below are kept as written,
because they are the reasoning; where the build chose differently the table says
so, and it did nine times worth reading:

| what the plan said | what it turned out to be |
|---|---|
| a new `ChillDkgRitualManager.foundingMembersOf` | never built. `participantsOf` already was that function, fallback and all; Phase 1 made `MarmotGroupCreation.createFor` its one caller on the way to a room, and took the list off `ChatRepository.createMarmotGroup` altogether |
| Phase 2's `stampAdmins` replaced by `AdminFlags.reconcile` with the creation's list | with the group's policy instead. Reading the list back would have kept two sources for one flag, which is the drift D2 is about |
| Phase 2's Welcome test in `WelcomeBundleMatchJvmTest` | in `RobustGroupAdminsJvmTest`, where Phase 0 already delivers real gift-wrapped Welcomes between three app devices. The bundle test is about which key package a Welcome names, not what it leaves behind |
| Phase 3 reports `Differs(missing, unexpected)` and offers removing unexpected admins | `Missing` only. Removing an admin is out of scope, so an admin the ceremony did not have is not this plan's to act on |
| Phase 3's repair on every room | on the current profile only. The app makes no governance commit on a legacy room -- no removal, no disband -- so a legacy room gets the warning and the transcript line and not the button |
| Phase 4 invites founders "who have never had a row in the room, not even a soft-deleted one" | founders without a leaf in a room still at **epoch 0**. Rows are hard-deleted when a member leaves, so "never had a row" cannot be told from "left"; epoch 0 can, because leaving is a commit and the founding invite is the commit that ends epoch 0 |
| Phase 4 is about `notAdded` | it also fixed what Phase 0 found: on the current profile `MarmotGroupCreation` invited the founders through a room read before its routing id was recorded, so the engine adopted a second copy of the group under the room id, and every later invite into that room took the founding path -- no commit, and nobody already in the room told |
| Phase 4's "Invite again" for any other active admin | only on the device holding the room at epoch 0, which is its creator's: nobody else holds the room until the invite reaches them. The coordinator's device does it on its own as step 4 |
| Phase 6 reconciles behind a stored "reconciled" preference | on every start, with no flag. `AdminFlags.reconcile` writes nothing when the rows already agree, so running it again costs one decode per room and a preference would only be a second thing to keep right |

Written against `fc437d05`.

**Since removed:** Phase 3's check, its transcript line and its repair, and the Phase 5
warning card that showed them. Once an admin of a keyed room could be demoted or step
down, a founder missing from the policy was usually the group's decision, and the repair
offered to undo it. [remove-founding-admins-card.md](./remove-founding-admins-card.md)
has the reasoning. Phase 4's invite card stays.

Read [shared-key-ceremony.md](./shared-key-ceremony.md) and
[ceremony-creation.md](./ceremony-creation.md) first.

## What a robust group is, in the code

A robust group is two rooms and a key.

```
SelectChatRoomTypeViewModel.createNip17ChatRoom
  │
  ├─ the NIP-17 room            members = picked + creator; no admins, no MLS
  │     └─ proposeRitual         ceremony participants = room members + creator
  │
  ├─ the ChillDKG key            t-of-n over those participants
  │
  └─ the admin room (Marmot)     id = marmotGroupId(threshold key)
        created by CeremonyFollowThrough (step 3) or DkgRitualViewModel,
        both through MarmotGroupCreation.create(adminPublicKeys = participantsOf(session))
```

The NIP-17 room has no admin concept, and that is correct: membership is the p-tag set
and nothing in it can be governed. **Admin means admin of the Marmot room**, whose
epoch-0 admin policy (`AdminPolicyV1` on the current profile, `MarmotGroupData.admin_pubkeys`
on the legacy one) is what the library enforces and what `isLocalAdmin()` reads.
`Participant.adminAt` is a local cache of it.

A subgroup is the same shape with a different participant set, and everything below
applies to it unchanged.

`ChatRoomType` is not persisted. Nothing on a device records that a room was made robust;
what marks one is a `GroupKeyState` -- a room whose id is derived from a threshold key.

## What "founding member" means

**D1. The founding members of a key-backed room are the participants of the ceremony that
made its key** -- `DkgSession.participantPublicKeySet()` on the row
`GroupKeyState.dkgSessionId` names. For a top-level robust group that is the picked members
plus the creator; for a subgroup, the picked admins plus the coordinator.

Not the NIP-17 room's rows, which `ChillDkgRitualManager.memberPublicKeys` already warns
can drift. Not the admin list a Welcome arrives with, which is one device's claim. The
session row is the one record every founding member's device built independently from the
proposal it agreed to, and the ceremony would not have completed if they disagreed about `n`.

A member added to the admin room later is not a founding member and is not made an admin by
anything here.

## Where the promise breaks today

| | gap | effect | phase |
|---|---|---|---|
| G1 | The founding set is computed at three call sites -- `SelectChatRoomTypeViewModel.adminCount`, `CeremonyFollowThrough.takeNextStep` step 3, `DkgRitualViewModel` -- and passed into `MarmotGroupCreation.create` as a free parameter. `participantsOf` falls back to the room's rows when the session carries no set | nothing stops a caller passing a different set; a room's admin list is fixed at a derived address forever, so one wrong call is permanent | 1 |
| G2 | The Welcome path in `NostrDao` writes `Participant.fromMlsGroup(...)` rows with no `adminAt`. Only the creator (`stampAdmins`) and a later commit (`processGroupMembershipChanges`) ever set it | on every device but the creator's, the admin room shows no admins until some other commit arrives -- which may be never | 2 |
| G3 | A joiner never checks the admin list it was welcomed into against the ceremony it took part in | a device that created the room with a different list (a bug, an older build, a Bennu peer) goes unnoticed | 3 |
| G4 | `MarmotGroupCreation.create` returns `notAdded` when `addMembers` partly fails. Those founding members are listed as admins but hold no leaf, so the policy treats them as inactive, and nothing invites them again | a founding admin who is not in the room | 4 |

G2 has a smaller blast radius than it sounds: `canGovern` reads `isLocalAdmin()`, and
`SubgroupManager` reads `currentAdminIdentities()`, so permissions are right. What is wrong
is every reader of `adminAt` -- today the subgroup picker's "Admin of this group" line --
and the fact that two devices disagree about the same room.

## Decisions

**D2. The admin policy is the truth; `adminAt` is derived from it on every path.** One
function writes the flags, from `mlsGroup.currentAdminIdentities()`, and the creation,
Welcome and commit paths all call it. `stampAdmins` stops taking a list of its own.

**D3. No wire change.** Putting the founding set on the signed `GroupKeyStateEvent` (so a
quorum signs it) was considered and left out: every founding member already holds the set
on its own session row, a later joiner is by definition not one of them, and the 30326 event
is shared with Bennu. Revisit if a device ever needs to check the founding set without
having taken part.

**D4. No schema change.** Every check below is derived on demand from rows that already
exist -- the session, the key state, the stored MLS group, the participant rows -- so no
Room migration and no new column.

## Phases

One commit per phase. Each phase ends with:

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

and phases that touch UI also with:

```bash
./gradlew :composeApp:m3Audit
```

### Phase 0: pin the invariant in a failing test

Before changing anything, a test that states the promise and shows where it fails.

**Add** `composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/RobustGroupAdminsJvmTest.kt`,
on the `CeremonyNetwork` harness `RobustRoomKeyCeremonyTest` uses, with three devices:

- `every founding member is an admin of the room the key makes, on every device` -- run the
  ceremony and follow-through, deliver the Welcomes, then on each device assert
  `currentAdminIdentities() == participants`, `isLocalAdmin()` and `adminAt != null` on
  exactly the founding rows. **Expected to fail on `adminAt` for the two joiners (G2).**
- `the founding set is the same set at every step` -- picked + creator, the NIP-17 room's
  members, the session's participants and the admin policy are one set.
- `a member added after the founding is not an admin` -- the boundary D1 draws.
- The same three for a subgroup, on the `SubgroupCeremonyInParentRoomTest` fixtures.

Mark the failing assertions `@Ignore("Phase 2")` and the like, so the build stays green and
each phase un-ignores what it fixes.

**Done when** the test exists, the passing parts pass, and each ignored part names its phase.

### Phase 1: one derivation of the founding set (G1)

**Change** `ChatRepository.createMarmotGroup` / `MarmotGroupCreation.create` to take the
`DkgSession` instead of `adminPublicKeys`, and derive the set inside, through a new
`ChillDkgRitualManager.foundingMembersOf(session, room)`:

- returns `session.participantPublicKeySet()` when present;
- falls back to the room's members only for a row that predates the column (logged), as
  `participantsOf` does today;
- requires the creating device to be in the set -- a device outside the ceremony holds no
  key state for the room and should never get here, so reaching it is a bug, not an outcome.

The two call sites (`CeremonyFollowThrough.takeNextStep` step 3 and
`DkgRitualViewModel`'s admin-room action) stop computing a set. `SelectChatRoomTypeViewModel`
keeps `adminCount` -- the session does not exist yet on that screen -- but computes it with
the same `(members + creator).distinct()` rule `proposeRitual` uses, and a comment points at
the other.

**Tests.** `MarmotGroupCreationJvmTest`: `the room's admins are the ceremony's participants,
whoever creates it` -- the coordinator's path and a non-coordinator's manual path produce
the same admin policy. `CeremonyFollowThroughJvmTest` keeps passing unchanged.

**Done when** no caller can name a room's admins.

### Phase 2: the Welcome writes admin flags (G2)

**Add** `AdminFlags.reconcile(database, localChatRoom, mlsGroup)` in `managers/` -- flags
only, never membership: sets `adminAt` on rows the policy names and clears it on rows it
does not. It is `processGroupMembershipChanges`'s `graduatedAdmins` / `removedAdmins` /
new-participant logic, lifted out.

**Call it** from:

- the Welcome path in `NostrDao` (after the participant upsert, before
  `GroupKeyStateManager.adopt`);
- `MarmotGroupCreation`, replacing `stampAdmins`. Its reasoning -- flags are safe, a
  membership reconciliation on a best-effort step is not -- moves to the new function's KDoc;
- `processGroupMembershipChanges`, replacing its three inline admin computations.

Having `Participant.fromMlsGroup` set `adminAt` itself would fix the Welcome alone, but it
only ever builds new rows: it cannot clear a flag on a stored one, so half the logic would
still live somewhere else. One function that does both is the point of D2.

**Tests.** Un-ignore Phase 0's joiner assertions. Add to `WelcomeBundleMatchJvmTest`:
`a member welcomed into a room sees its admins at once`.

**Done when** every device shows the same admins the moment it holds the room.

### Phase 3: a joiner checks what it was welcomed into (G3)

**Add** `FoundingAdmins.check(database, localChatRoom): Check` in `managers/`, returning one
of:

- `NotKeyBacked` -- no key state; a convenient room, nothing to check;
- `CannotCheck` -- key state, but this device holds no session for it (a later joiner);
- `Matches` -- every founding member is in the admin policy;
- `Missing(publicKeys: Set<HexKey>)` -- founding members the policy does not name, against
  `foundingMembersOf`. For a subgroup, also against the birth certificate's
  `SubgroupBirthCertificateEvent.parseAdminPublicKeys`, which the parent's quorum signed.

**Call it** once at the Welcome, after `adopt`, and write a transcript line on `Missing`
(a new `ChatMessage.TYPE_FOUNDING_ADMINS_MISSING`, derived like the membership captions).
And on every load of `ChatRoomDetailViewModel`, into a new
`ChatRoomDetailUIState.Loaded.foundingAdmins` field.

The room is not left or refused. Its address is the key, and there is no second room to go
to; a warning that names who is missing is what can be acted on.

**Repair**, where this device is an active admin: an "Make founding members admins" action
adds the missing ones to the policy in one admin-policy update commit. Additions only -- the
commit is built from the current policy plus `Missing`, never as a replacement list.

**Tests.** `FoundingAdminsJvmTest`: one case per `Check`, including a Welcome built by a
device that listed only itself, and the repair commit leaving every existing admin in place.

**Done when** a founding member left out of the admin policy is said, on the device that
can see it, and can be put back by any admin.

### Phase 4: every founding admin is invited (G4)

**Add step 4 to `CeremonyFollowThrough.takeNextStep`**, after the room exists: founding
members who have never had a row in the room -- not even a soft-deleted one -- are invited
with `addMembers`, one commit for all of them. "Never had a row" keeps this step to
finishing the founding invite: it never re-invites anybody who was in the room and left it.
Read-then-act like the other steps, so a repeat does nothing.

Who acts: the coordinator, as for step 3, so two devices do not race the same epoch. Any
other active admin gets an "Invite again" action on the detail screen for the same set, for
the case the coordinator's phone is in a drawer.

A founding member with no key package is reported as `BlockedOn`, exactly as creation does,
and the step is retried the next time the follow-through runs.

**Tests.** `CeremonyFollowThroughJvmTest`: `a founding member the first invite missed is
invited again`, `a founding member who was in the room is not`, and `a second run invites
nobody`.

**Done when** `Outcome.Created.notAdded` is something the follow-through finishes, not
something a screen reports and forgets.

### Phase 5: show it

- The admin room's member list shows "Admin" as supporting text on rows with `adminAt`.
- Phase 3's warning card and repair action, and Phase 4's "Invite again", on the detail
  screen.
- The NIP-17 room's detail says its members administer the group through the admin room,
  with a link to it, rather than showing no admins.

Strings in `strings.xml`, sentence case; every new button a 48dp target; the card's states
through `ScreenStateTransition`. The m3 audit is the check.

**Done when** a person can tell who administers the group, and whether that is everybody who
made it, without reading a transcript.

### Phase 6: rooms that already exist

No migration is needed (D4), but the rooms made before Phase 2 still carry unflagged
joiner rows and possibly uninvited founding admins.

- `AdminFlags.reconcile` runs once per Marmot room at startup, behind a stored
  "reconciled" preference, so G2 is repaired without waiting for a commit.
- Phase 4's step already runs over every completed session; it covers G4 for old rooms.
- Phase 3's check runs on every detail load; it covers G3 for old rooms.

**Tests.** A fixture room with joiner rows missing `adminAt` is reconciled on start, once.

### Phase 7: docs

- This note: mark it built, with a table of where the build chose differently.
- [ceremony-creation.md](./ceremony-creation.md) and [subgroups.md](./subgroups.md): link
  here from the admin-room creation sections.
- [README.md](./README.md): the table row is added with this note.

## Out of scope

- **Removing or demoting an admin**, founding or not. This plan makes the founding members
  admins; what happens to them afterwards is left as it is.
- **Making a later member an admin.** A separate feature, being planned separately.
- **Convenient groups.** Their only admin is the creator, by design.
- **The NIP-17 room.** It has no state to govern; see *What a robust group is*.

## Risks

- **Bennu peers.** One can create the admin room with a different list; Phase 3 can say so
  and repair it, but not prevent it.
- **Racing invites.** Phase 4 is coordinator-first to avoid two commits on one epoch; the
  manual "Invite again" can still race, and convergence already resolves that fork
  (`ConvergenceForkJvmTest`).
