# Every member row offers every action, and says why one is not open

A phased plan for the member rows on the group detail screen. Every row but the
viewer's own gets the overflow menu that only an admin sees today. The menu lists every
action that applies to that member, whether or not the viewer can take it right now.
An action that cannot be taken stays in the menu. Choosing it opens a screen that says
what stands in the way and what would change that.

**A plan, not built.** Written against `151f15ca` on `giza`, after
[member-admin-promotion.md](./member-admin-promotion.md) Phase 11 put the menu on the
row.

---

## What the row does today

`ChatRoomDetailScreen.kt` draws one `Card` per `localChatRoom.localParticipants`. The
card's `onClick` opens `ProfilePreviewRoute`. The trailing slot is one of two things:

- **`MemberActionsMenu`** (`MemberAdminControls.kt`), when
  `chatRoomDetailUIState.memberActions[member]` is non-empty. That is a `MoreVert`
  `IconButton` with a `DropdownMenu` of the allowed actions, in `MemberAction.entries`
  order.
- **`Icons.Default.ChevronRight`** with the description "View profile", a hardcoded
  string, in every other case.

`memberActions` comes from `AdminGovernance.memberActions(group, me, floor)`. The view
model calls it only when `canGovern` is true, meaning the room is on the current Marmot
profile, this device is an admin, and the room is neither disbanded nor left. The
function returns *only* the allowed actions and drops members with none. So:

| viewer | what a row shows today |
|---|---|
| not an admin, or any NIP-17 or legacy room | a chevron on every row; no actions, and no hint that any exist |
| an admin | the menu on rows where something is allowed, a chevron on the rest, with no explanation of the difference |
| an admin, own row | **Step down as admin** when `canDemote(me, me)`, otherwise a chevron |

The reasons an action is refused already exist, but only as exception messages from
`MarmotOutboundDao.promote`/`demote`/`removeMember`, shown in a snackbar after the
fact. Nothing tells a person *before* they try.

### Every reason an action can be refused, from the code

Read from `AdminGovernance`, `MarmotGroupPermissions` (marmot-kmp 2.2.1), the view
model's `canGovern`, and the refusals in `MarmotOutboundDao`:

| # | reason | where it is decided today | affects |
|---|---|---|---|
| 1 | The room is a NIP-17 room: it has no admins of its own; its members administer the room its key made | `mlsGroupState == null`; `adminRoomId` | all |
| 2 | The room is on the legacy Marmot profile | `marmotProfile() != CURRENT` → `group == null` | all |
| 3 | The group has been disbanded | `currentGroupState().isDisbanded` | all |
| 4 | This device has left the group | `chatRoom.leftGroupAt != null` | all |
| 5 | The viewer is not an admin | `MarmotGroupPermissions.canManage` | all |
| 6 | A keyed room that does not require the group's signature yet | `GovernanceGate.isGoverned` inside `keyedChangesAllowed` | make admin, remove as admin |
| 7 | A keyed room in a build without share rotation | `ExperimentalFeatures.shareRefresh` inside `keyedChangesAllowed` | make admin, remove as admin |
| 8 | The member holds no leaf in the group (still being added, or already being removed) | `target in members(group)` in `canPromote`; the participant row exists without a leaf | make admin, remove from group |
| 9 | They are the only admin among the members | `MarmotGroupPermissions.canDemote` (admin depletion) | remove as admin |
| 10 | It would leave a keyed room below its admin floor `max(3, t)` | `AdminGovernance.keepsFloor` with `adminFloor` | remove as admin, remove from group |
| 11 | An earlier change to the room is still waiting for a relay | `engine.gate.canPrepareLocalCommit` → `MarmotCommitPendingException` | all (transient) |
| 12 | They have already been offered admin and have not accepted | `PromotionManager` offers | make admin (transient) |

Reasons 1 to 10 are facts about the group's state and can be computed when the screen
loads. Reasons 11 and 12 are about work in flight; Phase 5 handles them separately.

---

## The decisions

### D1. The row's trailing control is the menu on every row but the viewer's own

Every other member gets the `MoreVert` overflow button whatever the viewer's role and
whatever the room kind. The chevron is removed from those rows.

The viewer's own row keeps what it has today: the menu with **Step down as admin**
when that is allowed, the chevron otherwise. "Except the current user" asks for no
always-on menu on that row. It does not ask to take away step down, which is the only
place that action lives.

Decided: your own row is unchanged by this plan. The alternative was a full menu there
too, with **Step down** explained when it is unavailable (a keyed room, or being the
only admin). It was turned down to keep the request as worded: your own row lists
nothing it cannot do.

### D2. "All possible actions" means every action that applies to *this member's* role

A menu that offers **Make admin** on somebody who is already an admin makes no sense.
That is a category error, not an action that is "disabled for the time being".
Whether a member is an admin decides which of the two role actions appears:

| member | items, in this order |
|---|---|
| not an admin | **Make admin**, **Remove from group** |
| an admin | **Remove as admin**, **Remove from group** |

The order is fixed (`MemberAction.entries`), so an item never moves when it becomes
available. Whether a member is an admin comes from `adminPublicKeys`, the group's
state, as D1 of member-admin-promotion requires. Where there is no group to read (a
NIP-17 or legacy room), it comes from `participant.adminAt`, the only source those
rooms have.

The member-admin-promotion table's **Retry enrollment** and **Rotate shares** rows
were never built as `MemberAction`s and are out of scope here (see *Out of scope*).

### D3. An action the viewer cannot take now is listed, marked, and explains itself

An unavailable item is **not** `enabled = false`. M3's disabled `DropdownMenuItem`
takes no click, so the explanation screen could never be reached from it. Instead:

- The text uses `MaterialTheme.colorScheme.onSurfaceVariant` through
  `MenuDefaults.itemColors(textColor = …)`, a role, with no `.copy(alpha = …)`.
- A trailing `Icons.Outlined.Info` icon with a decided description, "Why this is
  unavailable", from the catalogue.
- Semantics: `stateDescription = "Unavailable"` and `onClickLabel = "See why"`, so a
  screen reader says what the visual does.

Choosing it closes the menu and navigates to the explanation screen (D5). An
available item behaves exactly as it does now: the confirm dialog for an admin
change, and the direct call for **Remove from group**.

### D4. One function decides, and it returns reasons, not a set

Today `memberActions` returns `Set<MemberAction>`, which can say yes but not why not.
It is replaced at the source by a function that returns, for each member, each
applicable action together with an ordered list of what blocks it:

```kotlin
data class MemberActionOption(
    val action: MemberAction,
    val blockers: List<MemberActionBlocker>,   // empty = available
)
```

`AdminGovernance.memberActions` stays as a thin filter over it (`blockers.isEmpty()`).
Every caller and test that relies on it, including `AdminChangesJvmTest` and
`GovernanceGateJvmTest`, keeps passing unchanged. That is the proof the new function
agrees with the old one where the old one had an answer.

The rules stay in one place, as the comment on `AdminGovernance` asks. The screen
never decides an action itself, and neither does the explanation screen.

**Order of blockers.** Room-wide reasons come first, then the viewer's role, then the
room's rules, then the member, then work in flight. The screen leads with the first
blocker and lists the rest under it. That way a non-admin in a disbanded room hears
"the group was disbanded" rather than "you're not an admin".

### D5. The explanation is a pushed screen, and it recomputes its reason

A new route:

```kotlin
@Serializable
data class MemberActionUnavailableRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val memberPublicKey: HexKey,
    val action: MemberAction,
) : Route()
```

The route carries **what was asked**, not the answer. The screen's view model reloads
the room and runs the same D4 function. That has three consequences:

- The reasons cannot be stale, and they cannot be forged by whatever built the route.
- If the blocker clears while the screen is open, for example when a relay accepts
  the pending commit, the screen says so instead of explaining something no longer
  true.
- The route has no list of sealed classes to serialise.

navigation-compose 2.9.2 serialises an `@Serializable` enum in a type-safe route. A
route test pins this, so a regression fails in the test and not on a device.

It is a screen rather than a dialog because the request says "open a screen", and
because some reasons need room. Reason 6 names who has not updated Giza. Reason 10
explains a floor and points to the fix. A pushed screen also gets
`NavigateBackButton`, which the desktop target needs (CLAUDE.md, "A pushed screen has
a way back").

### D6. Each reason says what is in the way *and* what would change it, with the one action that helps

| blocker | title | what would change it | the screen's one action |
|---|---|---|---|
| `NoAdminsInThisRoom` | This room has no admins | Its members administer the group's admin room. Make the change there. | **Open the admin room** (when `adminRoomId` is known) |
| `LegacyRoom` | This group uses an older format | Groups made before the current format can't change who runs them. A new group can. | — |
| `Disbanded` | This group was disbanded | Nothing can change a disbanded group. | — |
| `YouLeft` | You've left this group | Only members can make changes. | — |
| `NotAnAdmin(admins)` | Only admins can do this | Ask one of the group's admins: {names}. | — |
| `SignaturesNotRequired(waitingOn)` | The group has to sign changes first | In a group with a shared key, a change to who runs it needs the group's signature. An admin turns that on under "Require signatures" (if {names} haven't updated Giza yet, it waits for them). | **Back to the group** (to the governance section) |
| `ShareRotationUnavailable` | Not in this version of Giza yet | Changing the admins of a group with a shared key moves a share of the key, and this version can't do that yet. | — |
| `NotInGroupYet` | {name} isn't fully in the group | They're still being added, or they're already being removed. Try again once that has landed. | — |
| `OnlyAdmin` | {name} is the only admin | A group always needs an admin. Make someone else an admin first. | — |
| `BelowFloor(floor)` | A group with a shared key needs at least {floor} admins | Make someone else an admin first, then try again. | — |
| `ChangePending` | An earlier change is still on its way | A relay hasn't accepted the last change to this group. This clears by itself. | — |
| `OfferPending` | {name} has already been asked | They've been offered admin and haven't answered yet. | — |

These lines are drafts. They go into `strings.xml` in sentence case, interpolated
with `%1$s` and never `"${…}"`, and they keep the apostrophes CLAUDE.md warns
Compose Resources does not unescape. `StringCatalogueJvmTest` gains a case for any
escape the new strings rely on.

When the screen recomputes and finds **no** blocker, it shows "You can do this now"
and a **Back to the group** button, which pops back. It does not perform the action
itself: the confirm dialogs and their wording live on the detail screen, and two
copies of them would drift apart.

### D7. The row still opens the profile, and says so

Removing the chevron removes the only visual hint that the card is a target. The
`Card` keeps its `onClick` to `ProfilePreviewRoute` and gains
`Modifier.semantics { onClick(label = "View profile", …) }` from the catalogue, so
assistive technology keeps the information the chevron's description used to carry.
This also replaces one of the hardcoded `contentDescription` literals.

---

## Phase 1 — the reasons, as a pure function

**One day.** No UI change.

1. `ui/view/state/MemberAction.kt`: add `MemberActionOption` and a
   `sealed interface MemberActionBlocker` with the cases in D6. The ones that carry
   data are `NotAnAdmin(admins: Set<HexKey>)`,
   `SignaturesNotRequired(waitingOn: Set<HexKey>)` and `BelowFloor(floor: Int)`;
   the rest are `data object`s.
2. `managers/MemberActionPolicy.kt`, new: a pure function over a plain context, so
   every case can be tested without building an MLS group:

   ```kotlin
   data class MemberActionContext(
       val room: RoomKind,              // NIP17, LEGACY, CURRENT
       val isDisbanded: Boolean,
       val hasLeft: Boolean,
       val me: HexKey,
       val admins: Set<HexKey>,         // the group's, or adminAt-derived (D2)
       val members: Set<HexKey>,        // holders of a leaf; empty off-CURRENT
       val isKeyed: Boolean,
       val governance: GovernanceStatus?,
       val shareRefresh: Boolean,
       val floor: Int,
   )

   object MemberActionPolicy {
       fun options(context: MemberActionContext, rows: List<HexKey>): Map<HexKey, List<MemberActionOption>>
   }
   ```

   The function defers to `MarmotGroupPermissions` for the library's rules (D4: one
   place). It checks blockers in D4's order. It returns an entry for every row except
   `me`, plus `me` with `STEP_DOWN` only when step down is available (D1).
3. `AdminGovernance`: add `context(group, me, floor, …)`, which builds the context
   from an `MlsGroup`. Re-express `memberActions` as
   `MemberActionPolicy.options(...)` filtered to available, with the same signature.

**Tests**, `MemberActionPolicyTest` (commonTest, no fixtures):

- one test per blocker, for each action it affects, asserting it is present and
  *first* where D4's order says it should be;
- the D2 table: which of make/remove-as-admin appears per role, in fixed order;
- no entry for `me` except step down;
- **the equivalence**: `AdminChangesJvmTest` and `GovernanceGateJvmTest` run
  unchanged against the re-expressed `memberActions`.

## Phase 2 — the state, for every viewer and every room kind

**Half a day.**

1. `ChatRepository`/`DatabaseChatRepository`: add
   `suspend fun memberActionOptions(localChatRoom): Map<HexKey, List<MemberActionOption>>`.
   It builds the context the way `ChatRoomDetailViewModel.initiateChatRoomDetail` does
   today: group, `isDisbanded`, `leftGroupAt`, `adminFloor`, `governanceStatus`. It is
   the **one** loader both screens use, so the detail screen and the explanation
   screen cannot disagree. Give the `NO_OP` repository an empty map.
2. `ChatRoomDetailUIState.Loaded`: replace
   `memberActions: Map<HexKey, Set<MemberAction>>` with
   `memberActions: Map<HexKey, List<MemberActionOption>>`, and fix the KDoc.
3. `ChatRoomDetailViewModel`: drop the `group?.takeIf { canGovern }` guard and fill
   the field from the repository call for **every** room. `canGovern` stays as it is
   for disband and the other admin controls.

**Tests:** extend the view model's existing loaded-state tests, or add a small one, for
a non-admin viewer (every row has options, all blocked by `NotAnAdmin`) and a NIP-17
room (every row blocked by `NoAdminsInThisRoom`).

## Phase 3 — the row

**One day.**

1. `MemberActionsMenu` takes `options: List<MemberActionOption>` and two callbacks:
   `onAction(MemberAction)` for available items and
   `onExplain(MemberAction)` for the rest. Unavailable items are styled as D3 says.
2. `ChatRoomDetailScreen`'s `trailingContent`:
   - another member → always `MemberActionsMenu`;
   - the viewer's own row → the menu when it has `STEP_DOWN`, otherwise the chevron
     (D1), with its description moved to the catalogue;
   - `onExplain` → `onNavigateToRoute(MemberActionUnavailableRoute(...))`.
3. The card's `onClick` semantics label (D7).
4. Strings: `why_this_is_unavailable`, `unavailable`, `see_why`, `view_profile`.

**Tests**, `ChatRoomMemberRowJvmTest`:

- update `onAllNodesWithContentDescription("Member actions").assertCountEquals(1)`. With
  both fixture rows being other members, the count is now 2, and that is the point of
  the change;
- a non-admin viewer opens the menu on each row and sees **Make admin** and **Remove
  from group**, both marked unavailable;
- choosing an unavailable item navigates to `MemberActionUnavailableRoute` with the
  member and the action, and opens no dialog;
- an available item still opens "Make Nomvula an admin?" (the existing test, with the
  new state shape);
- the viewer's own row has no menu unless step down is available;
- tapping the row body still opens `ProfilePreviewRoute`, as both existing tests
  require.

## Phase 4 — the explanation screen

**Two days.**

1. `routes/MemberActionUnavailableRoute.kt` (D5), and its `composable<…>` in
   `DedunNavHost.kt`, next to `ProfilePreviewRoute`. The host passes
   `popBackStack()` as `onNavigateBack`, and passes `onNavigateToRoute` for **Open the
   admin room**.
2. `MemberActionUnavailableViewModel`: loads the room, calls
   `chatRepository.memberActionOptions`, picks the option for `(member, action)`, and
   exposes:

   ```kotlin
   sealed interface MemberActionUnavailableUIState {
       data object Loading
       data class Error(val message: String)                 // room or member gone
       data class Blocked(val memberName: String, val action: MemberAction,
                          val blockers: List<MemberActionBlocker>, val adminNames: …, val adminRoomId: String?)
       data class NowAvailable(val memberName: String, val action: MemberAction)
   }
   ```

   It re-runs on `observeSharesInputs(chatRoomId)` and on a room change, so a blocker
   that clears while the screen is open is reflected there (D5).
3. `MemberActionUnavailableScreen`:
   - `TopAppBar` titled with the action ("Make Nomvula an admin"), with
     `NavigateBackButton(onNavigateBack)`;
   - content root `Modifier.padding(innerPadding).readableContent()`;
   - `ScreenStateTransition` over the `when`, which is the composable's whole body
     (CLAUDE.md);
   - `LoadingDataIndicator`, `ErrorState(onRetry = viewModel::reload)`, and the two
     loaded states. In `Blocked`, the first blocker's title is `headlineSmall` with
     its body and its D6 action below. Any further blockers follow as a list under
     "Also in the way". `NowAvailable` shows the D6 "You can do this now" layout;
   - spacing only from `MaterialTheme.spacing` semantic names, and colour only from
     roles;
   - a leading icon per blocker family (lock for role, group for room, schedule for
     transient), each `Decorative` because the title beside it says the same thing.
4. Strings: every D6 title and body, "Also in the way", "You can do this now",
   "Back to the group", "Open the admin room".

**Tests**, `MemberActionUnavailableScreenJvmTest`, rendered the way
`ChatRoomMemberRowJvmTest` renders (`runDesktopComposeUiTest`, `DedunTheme`):

- each blocker renders its title and body, with names and the floor interpolated;
- **Open the admin room** navigates to the admin room's route and appears only for
  `NoAdminsInThisRoom` with a known `adminRoomId`;
- two blockers: the first leads and the second is under "Also in the way";
- `NowAvailable` shows its message and **Back to the group** calls `onNavigateBack`;
- the back button exists (desktop has no system back);
- a route round-trip test: `MemberActionUnavailableRoute` encodes and decodes with
  each `MemberAction` (D5's pin).

## Phase 5 — reasons about work in flight

**One day.** These two need a query each, and they change without the group's state
changing, so they ship after the screen works for the eight structural reasons.

1. `ChangePending`: the repository asks `engine.gate.canPrepareLocalCommit(routingId)`
   when it builds the context. Two cases: a room with no live engine group, which is
   not this blocker, and a gate that clears, which the explanation screen's observer
   has to see.
2. `OfferPending`: a `PromotionManager` query for an open offer *from* this account
   *to* the member. Today `pendingOffer` answers only for an offer *to* `me`, so this
   is a new query over the same rows.
3. Both go last in D4's order, and both say "this clears by itself".

**Tests:** policy cases for both. A repository-level test with a gate held by an
unconfirmed commit shows `ChangePending`, and confirming the commit clears it.

## Phase 6 — docs and the audits

**Half a day.**

- Set this note's status line to built, with any choice the build made differently.
- Add an entry in [README.md](./README.md).
- In [member-admin-promotion.md](./member-admin-promotion.md) Phase 11, add a line
  pointing here: its "the view model hides an action that would break [the floor] and
  says why" (D8) is now done by explaining instead of hiding.
- Run `./gradlew :composeApp:m3Audit`. It must stay at **0** spacing `.dp`, **0**
  hardcoded colours, **0** title-case strings, **0** unguarded `.clickable` and **0**
  untriaged `contentDescription = null`. The two literals this work removes ("View
  profile" on the chevron, and the row's implicit one) may lower the reported count
  of literals in composables.
- `./gradlew check`, which also runs the brand and wallet audits. The new strings say
  Giza, never Bennu, and none of them mentions a wallet.

---

## Ship order

| commit | phases | users see |
|---|---|---|
| 1 | 1 | nothing; the rules gain reasons, with old behaviour pinned |
| 2 | 2, 3, 4 | the menu on every other member's row; every unavailable action explains itself |
| 3 | 5 | "an earlier change is still on its way" and "already asked" instead of a snackbar after the fact |
| 4 | 6 | — |

Phases 2, 3 and 4 ship together. A menu whose unavailable items navigate to a route
that does not exist yet is worse than today's chevron.

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew check
```

By hand, on the desktop target, which has no system back:

1. As a **non-admin** in a robust group: every other row has the menu, both items are
   marked, and each opens a screen naming the admins. Back returns to the group.
2. As an **admin** of a robust group at its floor of three: **Remove as admin** on
   another admin explains the floor. Make a fourth member an admin. Once the change
   lands, the open explanation screen switches to "You can do this now".
3. As an admin of a keyed room before **Require signatures**: **Make admin** explains
   the signature rule and names anyone who hasn't updated Giza.
4. In the group's **NIP-17 room**: every row explains that the room has no admins, and
   **Open the admin room** goes there.
5. With **TalkBack**/VoiceOver: an unavailable item reads as "Make admin, unavailable,
   double-tap to see why", and the row body reads as "View profile".

## Estimate

| phase | size |
|---|---|
| 1 — the reasons | one day |
| 2 — the state | half a day |
| 3 — the row | one day |
| 4 — the explanation screen | two days |
| 5 — work in flight | one day |
| 6 — docs and audits | half a day |

About six days. Nothing waits on marmot-kmp or mls-kmp; every rule it reports already
exists in this repository or in `MarmotGroupPermissions`.

## Open questions

- **Q1. "View profile" in the menu.** D7 relies on the row body. Adding **View
  profile** as the menu's first item would make the menu a complete list of what
  can be done to a member, at the cost of a duplicate target.

## Out of scope

- **Retry enrollment** and **Rotate shares** as row actions (member-admin-promotion's
  Phase 11 table). They are not `MemberAction`s. Rotation is already offered from the
  Shares section, and enrollment retries are driven by the ceremony.
- Performing an action from the explanation screen (D6).
- Direct-message rooms, whose detail screen has no member rows.
