# The "Founding members aren't admins" card goes

A phased plan to take out the warning card that the group detail screen shows when a keyed
room's admin policy leaves out someone who took part in the key ceremony. It covers the
card, its "Make them admins" repair, the transcript line written at the Welcome that sends
the reader to the card, and the check behind all three.

**Not built.** Written against `b7de119e` on `giza`. Read
[robust-group-admins.md](./robust-group-admins.md), Phases 3 and 5, which added what this
plan takes out, and [member-admin-promotion.md](./member-admin-promotion.md), Phase 9, which
is why it has to go.

---

## Why

`FoundingAdmins.check` answers one question: *founders minus the policy's admins*, where
"founders" means the ceremony's participants plus, for a subgroup, the admins named in
its birth certificate. When robust-group-admins was written, nobody could stop being an
admin of a keyed room. So the only way a founder could be missing from the policy was a
fault: a buggy or older creator, or a Bennu peer, had listed fewer admins. The card called
that an error, used the error container, and offered to repair it.

That is no longer the only way, so the check now gives wrong answers:

- **Demotion** (`51eda2d2`, member-admin-promotion Phase 9). The group's signature takes
  an admin out of the policy. Every device that took part in the ceremony then shows the
  card with that person's name, in red, saying the room "doesn't list them as admins".
  This is true, but it is something the group decided, not a fault.
- **Stepping down** (`bb35d73c`). An admin of a keyed room asks another admin to propose
  their removal. The result is the same: a founder who is no longer in the policy, because
  they asked to be taken out.
- **The repair undoes the decision.** "Make them admins" calls `addAdmins`. In a governed
  room that is `GovernanceManager.propose` with the old policy plus the demoted founder. One
  tap proposes reversing a change the quorum just signed. The offer is made to every admin,
  and nothing says what it reverses.

The check cannot tell a fault from a decision. The policy keeps no history, and a device
welcomed after the demotion has no record of it. The card can only be right where nobody
has ever been demoted, and that is less true with every release.

The guarantee the card was a backstop for is still in place: **every founding member is an
admin when the group is made**. `MarmotGroupCreation` makes it so (robust-group-admins
Phases 1, 2 and 4), and `RobustGroupAdminsJvmTest` pins it on every device. This plan takes
out only the after-the-fact warning.

## What stays

- **"Not every founding member was invited"**, the second `FoundingMembersCard`, and its
  "Invite them" action. It is shown only on the creator's device, only while the room is at
  epoch 0, and demotion cannot happen before epoch 0 ends. `uninvitedFounders`,
  `finishFoundingInvite`, `inviteFounders` and `founderNames` all stay.
- **`FoundingMembersCard`** stays, with the invite card as its one caller. Do not inline
  it; that only adds noise to the diff.
- **`ChatMessage.TYPE_FOUNDING_ADMINS_MISSING`** and its place in `MEMBERSHIP_TYPES`. See D3.
- Everything in `RobustGroupAdminsJvmTest` that asserts the founding set and the policy
  directly.

## Decisions

### D1. Remove the card. Do not teach it about demotion

The other way would keep the card and subtract the founders who were demoted or who stepped
down. That needs a record of signed demotions that every device agrees on. The
`TYPE_ADMIN_REMOVED` transcript rows are per device, and a member welcomed later has none of
them. The policy is the only shared record, and it holds only the present. A check fed
history that some devices have and others lack would show the card on some phones and not
others. That is worse than not showing it.

If detecting an under-listed policy from a Bennu peer is wanted again later, it belongs at
the Welcome, before any commit could have demoted anyone (see D3). It does not belong on the
detail screen, which runs at every later epoch.

### D2. The repair goes with the card

`ChatRoomDetailViewModel.addMissingFoundingAdmins`, `ChatRepository.addAdmins` and
`MarmotOutboundDao.addAdmins` have no other caller. Member promotion goes through
`PromotionManager` and its offer/accept flow, which also enrolls the new admin in the key.
`addAdmins` did not enroll anyone; it relied on founders already holding shares. A path
that adds an admin without the promotion's checks should not outlive the one screen that
needed it.

### D3. Stop writing the Welcome line. Keep its type

`FoundingAdmins.announceIfMissing` runs at the Welcome. At that moment the room is at
the epoch of the founding invite, so it cannot have been caused by a demotion. On its own
it is still correct. It goes anyway because its sentence ends "An admin can add them back
from the room's details", and after this plan nothing there does that. Rewording it to
point nowhere would leave a warning nobody can act on. That is the kind of line
`EmptyState` and `ErrorState` were introduced to remove.

The constant stays in `MEMBERSHIP_TYPES` permanently. Rows of this type are already
stored on devices. `MEMBERSHIP_TYPES`' own KDoc says that a type missing from it renders
"as a chat bubble -- silently, and looking exactly like the inviter having said" it. In
this case the "inviter" is the device's own user, because the row was written with
`isUserMessage = true`. The KDoc changes to "no longer written".

Do not delete the existing rows. They were true when they were written, and they appear
only in rooms whose creator under-listed admins, which is rare. Deleting history the user
has already read costs more than one stale pointer.

### D4. `FoundingAdmins` loses everything but `ceremonyOf`, and `ceremonyOf` moves

After D1-D3, the only code left in `FoundingAdmins` that anything calls is `ceremonyOf`,
which `DatabaseChatRepository.finishFoundingInvite` and `.uninvitedFounders` use. It moves
next to its callers' subject, as `MarmotGroupCreation.ceremonyOf`, and `FoundingAdmins.kt`
is deleted. A `FoundingAdmins` object with nothing about founding admins left in it would
be a misleading name.

`foundersOf` and `certifiedAdmins` are private to `check` and go with it. Nothing else reads
the birth certificate's admin list: `SubgroupBirthCertificateEvent.parseAdminPublicKeys`
stays, because certificate verification and its own tests use it.

---

## Phases

One commit each, in order, and each one compiles and passes `jvmTest` on its own. Phase 0
comes first so that nothing is deleted before what has to outlive it is pinned somewhere
else.

### Phase 0: pin what outlives the check

Two assertions currently live in code that Phase 4 deletes.

- **The caption type.** `FoundingAdminsJvmTest` line 178 asserts
  `TYPE_FOUNDING_ADMINS_MISSING in MEMBERSHIP_TYPES`. Move it to
  `ChatRoomLastMessagePreviewTest`, in `commonTest`, which already reads `MEMBERSHIP_TYPES`.
  Name it `a retired caption type still renders as a caption` and give it a one-line
  comment pointing here (D3).
- **The legacy-room assertion.** `RobustGroupAdminsJvmTest`, lines 483-487, asserts
  `FoundingAdmins.check(...) == NotKeyBacked` for a room run by its creator. What the
  assertion means is "there is no key behind it". Say that directly:
  `assertNull(joiner.db.groupKeyStateDao().getByChatRoomId(soloRoomId), ...)`, with the
  same message. Drop the `FoundingAdmins` import if this was its last use in the file.

**Done when** `:composeApp:jvmTest` passes, and `git grep FoundingAdmins -- '*Test*'` finds
only `FoundingAdminsJvmTest`.

### Phase 1: the card goes

All in `ui/composable/ChatRoomDetailScreen.kt`:

- Delete the `if (chatRoomDetailUIState.missingFoundingAdmins.isNotEmpty()) { item { … } }`
  block (lines 578-599).
- Rewrite the comment above it (lines 571-577), which describes both cards, to describe
  only the invite card: founders the founding invite never reached, under the key and the
  parent for the same reason as before.
- Delete `val madeAdminsRequested = …` (line 263).
- Delete the imports of `founding_members_are_not_admins`,
  `s_took_part_in_the_key_ceremony_but_are_not_admins`, `make_them_admins` and
  `founding_members_made_admins_requested`.
- `FoundingMembersCard`'s KDoc (lines ~1890-1898): keep the error-container rationale,
  and change "founders" wording that assumed two callers.

In `composeResources/values/strings.xml`, delete the same four strings (lines 533-536). No
other file uses any of them. Check with `git grep` before deleting, since the Compose
Resources accessor fails at compile time and not at review.

**Done when** the detail screen of a keyed room where a founder has been demoted shows the
members, the key and the Shares section, and no red card. `./gradlew :composeApp:m3Audit`
passes. Its literals report should not move, because the removed strings came from the
catalogue.

### Phase 2: the screen stops asking

- `ui/view/state/ChatRoomDetailUIState.kt`: delete `missingFoundingAdmins` and its KDoc
  (lines ~124-130). In `founderNames`' KDoc, "the two sets above" becomes
  "`uninvitedFounders`".
- `ui/view/model/ChatRoomDetailViewModel.kt`:
  - Delete the `missingFoundingAdmins` read (lines 125-127). Rewrite the comment above it to
    mention only Phase 4.
  - `founderNames = uninvitedFounders.associateWith { … }`.
  - Delete `missingFoundingAdmins = missingFoundingAdmins,`.
  - Delete `addMissingFoundingAdmins` and its KDoc (lines ~305-321).
  - Drop the `FoundingAdmins` import.

This also takes one repository call off every detail load. That call did a database read
and an MLS group decode on every visit.

**Done when** `git grep -n missingFoundingAdmins` finds nothing.

### Phase 3: the Welcome stops announcing

- `database/dao/NostrDao.kt`: delete the `FoundingAdmins.announceIfMissing(…)` call and the
  comment above it (lines ~982-992), and the import (line 70). `GroupKeyStateManager.adopt`
  and its comment stay exactly as they are.
- `database/model/ChatMessage.kt`: rewrite `TYPE_FOUNDING_ADMINS_MISSING`'s KDoc as
  retired. No longer written (link this note). Kept, and kept in `MEMBERSHIP_TYPES`,
  because rows written before are still on devices and would otherwise render as a
  bubble from the user.

**Done when** a device welcomed into a room whose policy under-lists the founders writes no
transcript line. `FoundingAdminsJvmTest` still passes here: it calls `announceIfMissing`
directly, not through the Welcome. Phase 4 deletes both.

### Phase 4: the check and the repair go

- `managers/FoundingAdmins.kt`: move `ceremonyOf` to `MarmotGroupCreation` (D4), keeping
  its KDoc, then delete the file.
- `database/repository/DatabaseChatRepository.kt`: the two `FoundingAdmins.ceremonyOf`
  calls (lines 120, 126) become `MarmotGroupCreation.ceremonyOf`. Delete `addAdmins`
  (line 791) and `foundingAdmins` (lines 795-802).
- `repository/ChatRepository.kt`: delete `addAdmins` and `foundingAdmins` from the
  interface (lines ~372-385) and from the preview stub (lines 653-656). Drop the
  `FoundingAdmins` import.
- `database/dao/MarmotOutboundDao.kt`: delete `addAdmins` and its KDoc (lines ~1091-1123).
  Look again at its imports; `GovernanceManager` and `AdminPolicyV1` are used elsewhere in
  the file, so they probably stay.
- `jvmTest/.../managers/FoundingAdminsJvmTest.kt`: delete it. Every case in it tests
  `check`, `announceIfMissing` or `addAdmins`.
- Search for test fakes of `ChatRepository` that override the two removed members
  (`git grep -n "override suspend fun addAdmins\|override suspend fun foundingAdmins"`)
  and delete those overrides.

**Done when**

```bash
git grep -nE "FoundingAdmins|addAdmins|foundingAdmins\(|announceIfMissing|founding_members_are_not_admins|make_them_admins" -- composeApp iosApp
```

finds nothing, `TYPE_FOUNDING_ADMINS_MISSING` appears only in `ChatMessage.kt` and the
Phase 0 test, and `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`
passes.

### Phase 5: docs

- **This note**: mark it built, with a "where the build chose differently" table if it did.
- **[robust-group-admins.md](./robust-group-admins.md)**: under the "Built" paragraph, add
  one line saying Phase 3's check, transcript line and repair, and the Phase 5 warning
  card, were removed after admins of a keyed room could be demoted, with a link here. Leave
  the phase text as written, as these notes do. Its phases are the reasoning.
- **[README.md](./README.md)**: add a row for this note. The robust-group-admins row's
  summary mentions "the joiner's check against…"; add "(since removed)".
- **[remove-convenient-groups.md](./remove-convenient-groups.md)**: it names
  `FoundingAdmins.Check.NotKeyBacked` four times (lines 54, 343, 356, 368) as the test for
  "no key behind the room". That is plan text. Leave it, and add one sentence to its notes
  section: the check is gone, and "no key behind it" now means no `GroupKeyState` row,
  which is what Phase 0 here asserts.

**Done when** `git grep -n FoundingAdmins docs` finds only those historical mentions and
this note.

---

## Checking the work

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew check
```

`check` also runs the brand and wallet audits. Nothing here touches either of them, but they
are the gate.

On a device, which is not covered by any test above:

1. Make a three-member group and let the ceremony finish. The detail screen shows no
   card on any phone. This is the same as before.
2. Demote one founder with the group's signature. Previously every founder's phone showed
   "Founding members aren't admins" naming them. Now none does.
3. Open a room that already has a "This room was created without … as an admin" line in
   its transcript. It still renders as a centred caption, not as a bubble from you.

## Risks

- **An under-listing Bennu peer goes unremarked.** A room created by a Bennu build that
  lists fewer admins is no longer flagged on the detail screen or in the transcript. The
  member list still shows who is an admin (the "Admin" supporting text), so this is
  visible but not called out. Accepted (D1). If it matters, the place to bring it back is
  the Welcome, where it cannot be confused with a demotion.
- **Stale pointer in old transcript lines.** Rows written before Phase 3 still say "an
  admin can add them back from the room's details". Accepted (D3).
- **A fake `ChatRepository` the search misses.** The compiler finds every one, so the risk
  is a slow first build, not a wrong one.

## Out of scope

- The "Not every founding member was invited" card, `uninvitedFounders` and
  `finishFoundingInvite`. See "What stays".
- Showing demotion history on the detail screen.
- Anything about how a room is created. The founding set is still every participant.
