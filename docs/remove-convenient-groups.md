# Every group a member makes is robust

Group creation ends on a choice between two kinds of group: **convenient**, which the
creator runs alone, and **robust**, which every member runs together under a t-of-n
key. This note is the plan that takes the choice away. Every group a Giza member makes
will be robust, on a threshold they pick, and convenient will survive only as
something Giza can still join, read and leave.

**Built.** Phases 0-6 and a Phase 2b that was not in the plan as written, one commit
each. The phases below are kept as written, because they are the reasoning. The table
lists where the build differed:

| what the plan said | what it turned out to be |
|---|---|
| D1: no path adds a member to a room after its founding, so a one-to-one chat cannot grow into a group | false. "Invite new member" on the detail screen reached `chatRepository.inviteMember` for any room. D7 and Phase 2b close it: only a room with a key behind it takes new members |
| D3: convenient rooms keep working, unchanged | unchanged except that, by D7, they take no new members. Their creator can still remove members and disband |
| Phase 2's test "members are added to a room only at its founding" | two tests. `addMembers` (the founding invite) is called only from `MarmotGroupCreation`. `inviteMember` (a later invite) is called only from `AddMemberToChatRoomConfirmationViewModel`, which checks for a key |
| the rule against inviting into a keyless room in the invite DAO | in `AddMemberToChatRoomConfirmationViewModel`, the one entrance. Twenty-odd MLS tests drive the DAO's invite on keyless fixture rooms |
| Phase 2: an `Error` state for a group below the floor | a state of its own, `TooFewPeople`, so its message comes from the string catalogue rather than the view model |
| Phase 2: the screen's title becomes "How many of you have to agree?" | the title is the group's name. The question heads the stepper, where it would otherwise be said twice |
| Phase 2: the stepper's descriptions become strings | `SelectSubgroupAdminsScreen`'s identical literals use the same two strings now |
| Phase 3 renames `selectChatRoomType` in Phase 4 | renamed to `selectQuorum` in Phase 3, which was already rewriting it |
| Phase 3: one empty-state message for an empty store and a store of one | the same body, but a heading that fits each: "No one to add yet" or "Not enough people for a group yet" |
| Phase 5's join test in `WelcomeBundleMatchJvmTest` or `MarmotGroupFactoryJvmTest` | in `RobustGroupAdminsJvmTest`, which already delivers real gift-wrapped Welcomes between phones |

Written against `e78dfe50`.

Read [robust-group-admins.md](./robust-group-admins.md) first; it describes what a robust
group is in the code, and this plan leaves that unchanged. [subgroups.md](./subgroups.md)
holds the reasoning for the three-member floor that Phase 3 moves.

## What a convenient group is, in the code

A convenient group is one Marmot room with one admin and no key.

```
SelectChatRoomTypeViewModel.createChatRoom
  │
  ├─ CONVENIENT → createMarmotChatRoom
  │     ├─ MarmotGroupFactory.create   roomId = random, adminPublicKeys = [creator]
  │     └─ inviteMembers               key packages, one addMembers commit
  │
  └─ ROBUST     → createNip17ChatRoom
        ├─ chatRepository.createNip17ChatRoom
        └─ dkgRepository.proposeRitual(threshold = quorum)
              └─ … the admin room, made by MarmotGroupCreation once the key exists
```

It is the screen's default (`selectedChatRoomType = CONVENIENT`) and the only choice
for a group of fewer than three people (`ChatRoomType.isRobustAvailable`).

`ChatRoomType` is **not persisted** and **not in a route**. Nothing on a device records
that a room was made convenient. A convenient room is just a Marmot room with no
`GroupKeyState`, which `FoundingAdmins.Check.NotKeyBacked` names.

Three sites call `MarmotGroupFactory.create`:

| site | room | admins | key behind it |
|---|---|---|---|
| `SelectChatRoomTypeViewModel.createMarmotChatRoom` | a convenient group | the creator | none |
| `MarmotOutboundDao.createMlsDirectMessageChatRoom` | a one-to-one chat started from a profile | the creator | none |
| `MarmotGroupCreation.create` | a robust group's admin room, or a subgroup's | the ceremony's participants | the threshold key, which the room's id is derived from |

Only the first is the subject of this plan. The second is discussed under D1.

`ChatRoomType`'s companion is not about convenient groups at all. It holds the quorum
rules, and `ChillDkgRitualManager`, `CreateSharedKeyViewModel`,
`SelectSubgroupAdminsViewModel` and `SubgroupManager`'s KDoc depend on them:
`MINIMUM_ROBUST_GROUP_SIZE`, `MINIMUM_QUORUM`, `defaultQuorum`, `quorumRange` and
`isRobustAvailable`. Those rules stay. Only the enum goes.

## Decisions

**D1. "A group a user creates" means the group-creation flow. A one-to-one chat is not a
group.** `createMlsDirectMessageChatRoom` makes a two-member room whose creator is its
only admin, which is structurally a convenient group. It stays as it is because:

- two people cannot carry a meaningful threshold (D2), so the robust alternative is a
  2-of-2 key that freezes the chat the day one phone is lost;
- ~~the app has no path that adds a third member to an existing room~~. **Wrong, and
  found while building Phase 2:** the detail screen's "Invite new member" reaches
  `chatRepository.inviteMember` through `AddMemberToChatRoomConfirmationViewModel`,
  for any room. So a one-to-one chat *could* grow into a group run by one person.
  D7 closes that, and Phase 2b builds it.
Confirmed before any phase was built. If one-to-one chats should ever be robust too,
that is a separate plan: a 2-of-2 key, the ceremony started from a profile, and an
answer for a peer who never comes online.

**D2. The floor stays at three people, the creator included, and moves to the member
picker.** `ChillDkgRitualManager.MINIMUM_PARTICIPANTS` would accept 2-of-2, but the app
has decided three separate times that two is not a group worth a quorum
(`ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE`, `SubgroupManager.MINIMUM_ADMINS`,
[subgroups.md](./subgroups.md)). The reason holds here too: 2-of-2 means either member
can freeze the group by going quiet. Today a group of fewer than three people becomes
convenient without anyone choosing it. After this plan, the picker will not let a group
of fewer than three be made at all, and will say why, pointing anyone with only one
person in mind at a direct message. Confirmed before any phase was built.

**D3. Convenient rooms that already exist keep working, unchanged -- except that, by D7, they take no new members.** That covers rooms
this device made before the change and rooms a Bennu peer makes and invites a Giza
member into. Receiving, reading, sending, leaving and the creator's own governance
(removing a member, disbanding) all stay. There is no migration, and there is **no
upgrade path**. A Marmot room cannot host its own ceremony
(`ChillDkgRitualManager.proposeRitual` requires it, see
[ceremony-creation.md](./ceremony-creation.md)), and a robust room's id is derived from
its key. "Make this group robust" therefore means making a new group with the same
people. That is a possible follow-up, not part of this plan.

**D4. Bennu peers are not declined.** This is unlike a wallet
([giza-wallet-removal.md](./giza-wallet-removal.md)): a convenient group is something
Giza fully understands and can take part in honestly. Giza stops *making* convenient
groups. It does not refuse to join them.

**D5. No wire change and no schema change.** `ChatRoomType` was never written anywhere,
so deleting it touches no stored row, no route argument and no event.

**D6. The quorum rules get a home of their own before the enum goes.** They move to a
new `Quorum` object in `managers/`, next to `ChillDkgRitualManager`, which enforces them.
They move in a phase of their own (Phase 1), so the deletion in Phase 4 changes no
behaviour.
`ChatRoomType.minimumAdmins`, which member-admin-promotion added while this was being
built, followed them there as `Quorum.minimumAdmins`.

**D7. Only a room with a key behind it takes members after its founding.** Added
during the build, when D1's second reason turned out to be false. A Marmot room with
no `GroupKeyState` -- a one-to-one chat, or a convenient group made before this plan
or by a Bennu peer -- takes no new members. One more person in either would be a group
its creator runs alone, which is what this plan stops Giza making. The people in it
can make a group together instead. This narrows D3: a legacy convenient group's
creator can still remove members and disband it, but no longer add to it.

The rule lives in `AddMemberToChatRoomConfirmationViewModel`, the app's one caller of
`chatRepository.inviteMember`. It is not in `MarmotOutboundDao.inviteMemberToChatRoom`,
because twenty-odd tests drive that DAO on keyless fixture rooms to exercise MLS
itself (epoch races, publish-before-apply, convergence). `MarmotRoomEntryPointsJvmTest`
pins the single entrance instead.

## What changes for a person making a group

| step | today | after |
|---|---|---|
| name and description | unchanged | unchanged |
| pick members | any number, including none. "You can still carry on and invite people" | at least two others. The button is disabled below that, with the reason shown. The "carry on and invite later" line goes, because nothing would offer the invite |
| how the group is run | two cards, convenient selected. Robust disabled below three, with a quorum stepper under it | one screen asking how many of the *n* have to agree, starting on a simple majority |
| create | convenient: a Marmot room plus invites, and a card listing who could not be added. Robust: a NIP-17 room plus the ceremony | always the NIP-17 room plus the ceremony. The "ceremony couldn't be started" card stays |

## Phases

One commit per phase. Each phase ends with:

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

Phases that touch UI also end with:

```bash
./gradlew :composeApp:m3Audit
```

### Phase 0: pin the target in failing tests

**Add** `composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/ui/view/model/GroupCreationJvmTest.kt`,
on the fake-repository pattern `SelectSubgroupAdminsViewModelJvmTest` uses. Use a
recording `ChatRepository` and `DkgRepository`, and three members plus the creator:

- `creating a group opens a NIP-17 room and its key ceremony`: with no choice made,
  `createChatRoom` calls `createNip17ChatRoom` once and `proposeRitual` once, with
  `threshold == Quorum.defaultQuorum(4)`. It never calls `addMembers` or
  `observeMarmotKeyPackageForPublicKey`. **Fails today**, because the default is
  convenient. Mark it `@Ignore("Phase 2")`.
- `the quorum picked is the quorum asked for`: set the quorum to 4 of 4 and assert
  `proposeRitual(threshold = 4)`. This passes today if robust is selected first.
- `a group below the floor is never created`: build the view model with one member
  and assert no repository call and an `Error` state. Mark it `@Ignore("Phase 2")`.

**Add** `composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/MarmotRoomEntryPointsJvmTest.kt`,
modelled on `CeremonyEntryPointsJvmTest`. It counts the files in `commonMain` that call
`MarmotGroupFactory.create(` and asserts exactly
`{MarmotGroupCreation, MarmotOutboundDao}`. Mark it `@Ignore("Phase 2")`, because
`SelectChatRoomTypeViewModel` is the third today. Its KDoc says what each caller is for,
and that the one-to-one chat is the only room without a key (D1).

**Done when** both files exist, the parts that pass today pass, and each ignored test
names its phase.

### Phase 1: `Quorum` takes the rules (D6)

**Add** `managers/Quorum.kt`:

```kotlin
object Quorum {
    const val MINIMUM_GROUP_SIZE = 3          // was ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE
    const val MINIMUM = 2                     // was ChatRoomType.MINIMUM_QUORUM
    fun default(memberCount: Int): Int        // was defaultQuorum
    fun range(memberCount: Int): IntRange     // was quorumRange
    fun isPossible(memberCount: Int): Boolean // was isRobustAvailable
}
```

The KDoc for each rule moves with it and gets rewritten without the word "convenient".
The reason a single approval is not a quorum stays: one person acting alone.

**Change** every caller to use it: `ChillDkgRitualManager` (three sites),
`CreateSharedKeyViewModel`, `SelectSubgroupAdminsViewModel` and
`SelectChatRoomTypeViewModel`. `SubgroupManager.MINIMUM_ADMINS` becomes
`= Quorum.MINIMUM_GROUP_SIZE`, so the floor is stated once and the KDoc points to it
instead of repeating it. `ChatRoomType` keeps its two values and loses its companion.

**Tests.** Add `QuorumTest` (commonTest): `default` is a simple majority and never
below `MINIMUM` for sizes 2 to 9, `range` runs from `MINIMUM` to *n*, and `isPossible`
flips at 3. `CreateSharedKeyViewModelJvmTest`, `SelectSubgroupAdminsViewModelJvmTest`
and `SubgroupManagerJvmTest` keep passing unchanged.

**Done when** nothing outside `SelectChatRoomType*` imports `ChatRoomType`.

### Phase 2: creation is always robust

**`SelectChatRoomTypeViewModel`**:

- Delete `selectedChatRoomType`, `selectChatRoomType`, `createMarmotChatRoom`,
  `inviteMembers`, `membersNotAdded`, `robustUnavailableReason` and
  `KEY_PACKAGE_LOOKUP_TIMEOUT`, along with the imports only they used
  (`MarmotGroupFactory`, `MarmotGroupName`, `RandomInstance`, `MarmotProfile`,
  `Relays`, `withTimeoutOrNull`, `async`/`awaitAll`).
- `createChatRoom` goes straight to what is now `createNip17ChatRoom`. Rename it
  `createGroup`, because the NIP-17 room is how a group is made, not one option among
  several. Keep the KDoc: it is the reasoning for opening the ceremony at once.
- In `initiate`, a member set below `Quorum.MINIMUM_GROUP_SIZE` sets
  `SelectChatRoomTypeUIState.Error` and makes no repository call. The picker stops
  this in Phase 3, but a route can still be built by hand, and the ceremony would
  refuse it later anyway (`ChillDkgRitualManager` requires the range). Failing on the
  screen is the honest place for it.
- Keep `keyCeremonyNotStarted`, `createdChatRoomId` and the idempotent second tap,
  unchanged.

**`SelectChatRoomTypeScreen`**:

- Delete both `ChatRoomTypeCard`s and the composable itself. The `QuorumPicker`
  becomes the screen's content: a heading, the stepper, and the explanation, which
  `quorumExplanation()` already writes.
- Delete the `membersNotAdded` error card. The NIP-17 room has nobody to invite, and
  the admin room's partial invites are `CeremonyFollowThrough` step 4's job
  ([robust-group-admins.md](./robust-group-admins.md), Phase 4).
- Keep the `keyCeremonyNotStarted` card and its `start_the_key_ceremony` button.
- Replace the error branch's bare `Text` with `ErrorState(onRetry = null)`. The error is
  structural and retrying will not help. Passing `null` is the decision CLAUDE.md asks
  for.
- Move the footnote that was on the robust card under the picker as body text, worded
  for a screen with no alternative: "No one can change the group alone, and it outlives
  any one of you. Creating it starts a key ceremony every member takes part in."
- Strings, sentence case, all new in `strings.xml`: the screen title ("How many of you
  have to agree?"), the footnote, the FAB's "Create group", and the stepper's two
  `contentDescription`s, which are literals today. Delete
  `this_decides_who_can_change_the_group_later`, `just_you_for_now`,
  `you_and_1_other` and `was_created_but_couldn_t_be_added_yet_invite`.
  `how_should_be_run` goes too: the title no longer asks how the group is run.
- The preview gets a third member so it renders at the floor.

**Un-ignore** both Phase 0 tests. `MarmotRoomEntryPointsJvmTest` now passes with two
minters.

**Add** to `MarmotRoomEntryPointsJvmTest`: `members are added to a room only at its
founding`. It asserts that `chatRepository.addMembers(` is called only from
`MarmotGroupCreation`. This is the test that keeps D1 true: a one-to-one chat cannot
become a group without someone changing this line.

**Done when** no path through the app makes a Marmot room with a single admin and
more than one member.

### Phase 2b: a room with no key takes no new members (D7)

Not in the plan as first written; see D1.

- `AddMemberToChatRoomConfirmationViewModel` checks `chatRepository.groupKeyState`
  twice. The first check is in `initiate`, before the invitee's key package is looked
  up. The second is in `inviteToChatRoom`, at the act itself. A keyless room gets an
  `Error` that says to make a group instead.
- `ChatRoomDetailUIState.canInvite` is true for a room with key state, MLS state, not
  disbanded and not left. The "Invite new member" button shows only when it is true.
  This also takes the button off NIP-17 rooms, where an invite could only fail for
  want of MLS state.
- `MarmotRoomEntryPointsJvmTest` adds a test that `chatRepository.inviteMember(` is
  called only from that view model.

**Tests.** Add `AddMemberToChatRoomConfirmationViewModelJvmTest` with three cases. A
keyless room is refused on load, and refused at the act with no invite sent. A keyed
room is invited into.

**Done when** a one-to-one chat cannot become a group, from any screen.

### Phase 3: the member picker holds the floor (D2)

**`SelectChatRoomMembersViewModel`**:

- Add `val minimumOthers = Quorum.MINIMUM_GROUP_SIZE - 1` and
  `fun canContinue() = selectedPublicKeys.size >= minimumOthers`. The creator counts
  toward the floor, as in `SubgroupManager`: asking for three *others* would quietly
  build a group of four.
- `selectChatRoomType` returns early unless `canContinue()`, matching
  `SelectChatRoomTypeViewModel.selectChatRoomType`'s old guard against a disabled card
  being tapped anyway.
- Rewrite the key-package prefetch's comment, but keep the prefetch. The NIP-17 room
  needs no key packages, but the admin room `MarmotGroupCreation` makes after the
  ceremony does. Fetching them here means they are usually on the device by then.

**`SelectChatRoomMembersScreen`**:

- The FAB gets `enabled = canContinue()`. `ExtendedFab` already takes `enabled`.
- The bottom bar's supporting text says what is still missing. It reads "Pick at least
  2 people" with nobody selected, "Pick 1 more" with one, and the existing
  "%1$s selected" from two up.
- The empty state, which shows when the store has no profiles, drops
  `you_can_still_carry_on_and_invite_people`. That line becomes false, because there is
  no later invite. It is replaced with a line saying a group needs at least two other
  people, and that searching for someone or starting a chat with them adds them here.
  The same message, through `EmptyState`, covers a store with exactly one profile,
  where a group could never be made.

**Tests.** Add `SelectChatRoomMembersViewModelJvmTest`:

- `the picker does not continue with fewer than two others`: one selected leads to no
  navigation.
- `two others is enough`: two selected leads to a `SelectChatRoomTypeRoute` carrying
  both.
- `the creator is not counted twice`: the active user is excluded from `profiles` and
  never counted.

**Done when** the only way to reach the quorum screen is with a group the ceremony
will accept.

### Phase 4: `ChatRoomType` goes, and the names follow

- Delete `database/model/types/ChatRoomType.kt`.
- Rename the screen to what it now does. `SelectChatRoomType{Screen,ViewModel,UIState,Route}`
  becomes `SelectQuorum{Screen,ViewModel,UIState,Route}`, and
  `SelectChatRoomMembersViewModel.selectChatRoomType` becomes `selectQuorum`. Update
  `DedunNavHost` and the route's `@Serializable` name. Nothing persists a route across
  an upgrade, so the rename is safe. Check this against `NavigationIdentityRoutingJvmTest`
  before relying on it.
- `CeremonyEntryPointsJvmTest`'s expected set names `SelectQuorumViewModel`.
- `MarmotGroupFactory`'s KDoc says two sites make rooms, not three.
  `FoundingAdmins.Check.NotKeyBacked` now reads: "No key behind the room: a one-to-one
  chat, a group made before every group was robust, or one a Bennu peer made. Run by
  its creator."
- Run `docs/scripts/m3-audit.sh`. The literals report should drop by the strings the
  two cards held.

**Done when** `git grep -i convenient -- composeApp` finds nothing but
`FrostSigningSession` and `GroupKeyStateEvent`, which use the ordinary English word.

### Phase 5: rooms that are already convenient (D3)

No migration. These are the visible effects only:

- **Detail screen.** A room whose `FoundingAdmins.check` is `NotKeyBacked`, with more
  than two members, shows one line of supporting text under its members: "Run by
  %1$s alone. Changes don't need anyone else's approval." It names the creator from
  `currentAdminIdentities()`. Without this line, the app no longer explains anywhere
  why this group behaves differently from every other group the member is in. A
  two-member room (D1) shows nothing, because a one-to-one chat being run by one
  person is not news.
- **Creator governance is unchanged.** `canGovern` still reads `isLocalAdmin()`, so the
  creator of a convenient room can still remove members and disband it.
- **Bennu invites are unchanged** (D4). Add a case to `WelcomeBundleMatchJvmTest` or
  `MarmotGroupFactoryJvmTest`: `a single-admin group made elsewhere is still joined`.
  It builds a room with `adminPublicKeys = [creator]` and three members, delivers the
  Welcome, and asserts the joiner holds the room and `FoundingAdmins.check` is
  `NotKeyBacked`. This pins D4 so a later cleanup cannot take the join path out with
  the creation path.

**Done when** a member can tell a legacy convenient group from a robust one on its
detail screen, and a Bennu peer's convenient group still reaches them.

### Phase 6: docs

- This note: mark it built, with a table of where the build differed from the plan.
- [robust-group-admins.md](./robust-group-admins.md): the diagram's first line names
  the renamed view model, and "Out of scope: Convenient groups" points here.
- [subgroups.md](./subgroups.md) (lines 801 and 859),
  [ceremony-creation.md](./ceremony-creation.md) (127 and 279),
  [shared-key-ceremony.md](./shared-key-ceremony.md) (158) and
  [marmot-membership.md](./marmot-membership.md) (108): rename
  `SelectChatRoomTypeViewModel` and `ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE`. The
  marmot-membership mention of `inviteMembers` at creation now refers to
  `MarmotGroupCreation` only.
- Leave [marmot-v2.md](./marmot-v2.md)'s and [nsec-sign-in.md](./nsec-sign-in.md)'s
  links to `createMarmotChatRoom` alone. They are history and say what was true when
  they were written. Add a parenthetical saying it was removed here.
- [README.md](./README.md): the table row, which this commit adds, says "built".

### After the build: an invited member holds the group back

Found on a device, with this plan merged alongside member-admin-promotion. A robust
group's founders were admins holding shares; a member invited afterwards (D7 lets a
keyed room take one) was a plain member, as designed. But nobody could make them an
admin: the ⋮ on their row offered only **Remove from group**, and the room said it was
waiting for them to update Giza when they already had.

Making a member of a keyed room an admin needs the room to require the group's
signature (`AdminGovernance.keyedChangesAllowed`), and that can be switched on only
once every member's device has said it follows signed commits
(`GovernanceSupport.missing`). A device said it when its room's screen was opened, and
back when it heard another member say it. The founders had all said it before the
invite, and an invited member cannot read anything sent at an epoch before its own --
so it never heard one to answer, nobody said it again, and the room waited on a
screen the member had no reason to open.

- **A device says it on joining.** The Welcome path in `NostrDao`, beside
  `AdminFlags.reconcile`, for a current-profile room.
- **And once each start** (`GovernanceSupport.announceAll`, from
  `SynchronizationViewModel` beside `reconcileAdminFlags`), for the members who joined
  before joining said it. A room that has it already is a read and no write.
- **The notice** (`governance_waiting`) names who the room is waiting on and what for --
  "Waiting for Alice to open an up-to-date Giza. Until then, this group can't require
  its signature on changes, and nobody else can be made an admin." -- instead of
  telling them to update, which they often had, with a verb that did not agree with
  one name.

Pinned in `GovernanceGateJvmTest`: a welcomed member has said it before anybody else
speaks, and a device whose saying is taken back says it again at start, once.

#### The switch is nobody's to press

Even then an admin had to find **Require the group's signature for changes** on the
room's screen and tap it. Nothing about the switch is a decision: the two conditions it
waits on -- every member can follow, enough of them are admins -- are already checked in
code, and a group whose point is a key its members hold together is a group that means
to sign its changes. So the button is gone, and one device switches the rule on by
itself.

- **Which device.** The admin in the room's lowest leaf (`GovernanceSupport.switcherOf`)
  -- in a room as it was made, its creator. Not every admin's: two commits at one epoch
  are settled for every device that watched them but not for the two that made them,
  which swap branches and stay apart ([marmot-v2.md](./marmot-v2.md), Phase 8). Every
  device reads the same leaf off the same state, so they agree on one without a word
  between them.
- **When.** `GovernanceFollowThrough`, which already runs while the app is open and
  reads everything again on each pass, now passes whenever a member says it follows
  signed commits, and on start. A room that is owed the switch (`switchOwed`) gets the
  commit `requireSignatures` always sent; one still waiting for a relay is not sent
  twice.
- **The screen** says which device it is waiting on: "Turning on the group's signature
  for changes." on that device, "Changes will need the group's signature as soon as
  Bob's Giza is next online." on the others.

*Not at creation*, though that was the first preference. A room born governed --
the marker in its epoch-0 description -- would need no switch, but:

- a Bennu founder, or one on an older Giza build, takes part in the ceremony like
  anyone else, and would apply the bare commits Giza drops: in a different group from
  the first change, with nobody told. The support check is the only thing that knows,
  and it can only be asked once the room exists;
- a founder whose founding invite has to be finished later (robust-group-admins,
  Phase 4) is added by a bare commit, which the founders already in a governed room
  would refuse.

Switching once everybody has joined and said it costs the seconds it takes the
founders' devices to process their Welcomes, and costs nothing when one of them cannot
follow -- the room simply stays as it is.

Pinned in `GovernanceGateJvmTest`: the first admin's device switches by itself once the
last member says it, and not before; any other admin's device sends nothing.

Then the member's row offers **Make admin** -- with the room's share rotation on, which
is a development build for now -- and the member accepts on their own device.

## Out of scope

- **One-to-one chats** (D1). If they are to become robust, that needs its own plan.
- **Upgrading an existing convenient group** (D3). A new group is the way.
- **Gating MLS commits in the admin room on the quorum.** Every founding member of a
  robust group is an admin, and any one admin can commit a change to the admin room
  alone. `canGovern` is `isLocalAdmin()`. The quorum governs the key: key states,
  subgroup certificates and share rotations. It does not govern each MLS commit. The
  creation screen's promise that "any change … has to be approved by a quorum" is
  therefore stronger than the code. Phase 2 removes that sentence with the robust card,
  and the new footnote claims only what the key enforces. Making the claim true for
  membership is a separate piece of work.
- **Declining Bennu's convenient groups** (D4).

## Risks

- **People with one contact.** Someone who has spoken to only one other person can no
  longer make a group with them, only a direct message. That is D2's cost, and
  Phase 3's empty state says so rather than leaving a disabled button unexplained.
- **The ceremony is now the only way to make a group.** A group is not usable as a
  governed group until enough members have come online for the ceremony. The NIP-17
  room works for chat at once, as it does for robust groups today. Groups that used to
  be convenient were usable the moment they were created. If that delay turns out to
  matter, the fix belongs in the ceremony
  ([automatic-key-ceremony.md](./automatic-key-ceremony.md)), not in bringing back a
  creator-run kind.
- **Merges from Bennu.** Bennu keeps `ChatRoomType` and the convenient path. A merge
  that brings `createMarmotChatRoom` back will fail `MarmotRoomEntryPointsJvmTest`,
  which is the purpose of that test. Resolve it in favour of this plan.
