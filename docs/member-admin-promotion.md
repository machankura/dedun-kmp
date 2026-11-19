# Making a member an admin, and back

A plan for promoting a member of a group to admin and demoting an admin back to
member. It also covers the jobs that need the same machinery: removing an admin, an
admin stepping down, and an admin leaving.

**A phased plan, not built.** Written against the code as it stood at `fc437d05`, with
marmot-kmp 2.2.1 and mls-kmp 1.3.0 as the catalogue pins them. The second draft adds
three rules the first draft lacked; they are stated in the next section, and most of
the plan follows from them.

Read [marmot-v2.md](./marmot-v2.md) Phase 7 first. Removing a member, disbanding and
the derived membership captions all come from there. Read
[frost-share-refresh.md](./frost-share-refresh.md) before Phases 8 and 9: a promotion
is an enrollment and a demotion is a refresh. Read
[frost-batch-signing.md](./frost-batch-signing.md) before Phase 5, because a
governance change is signed as a batch.

---

## The three rules

| | rule | what it means here |
|---|---|---|
| **R1** | **Every admin holds a share of the group's key** | Promotion includes enrolling the new admin into the key. Demotion includes rotating the shares without them. A member row still shows **Admin** and **Holds a share** as two separate labels, because between the commit and the share work they disagree, and that disagreement is how a person sees that an enrollment or a rotation is still owed. |
| **R2** | **Every commit is first a proposal, and becomes a commit only once the group's threshold signature is on it**, except a member's own leave and a member's own key update (D6) | No single admin can change a keyed room. An admin proposes, `t` share holders sign, and only then is the MLS commit built. It carries the signature, and every device refuses a commit without one. |
| **R3** | **A robust group always has at least three admins** | The floor `ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE` already sets at creation becomes an invariant. Demotion, removal and leaving are refused when they would break it, and so is anything that would leave fewer admins than the quorum `t`. |

### Which rooms they apply to

All three rules are about a group's **threshold key**, so they apply to *keyed
rooms*. That means the Marmot room `MarmotGroupCreation` opens around a robust
group's key, and every subgroup's room. In those rooms every member is an admin today
(`ChatRoomType.ROBUST`: "Every member administers the room"). Promotion and demotion
add a second kind of member: someone who is in the room but does not govern it.

A **convenient room** has no key. Nothing can produce a threshold signature for it,
so R2 cannot apply there. R1 has no share to give and R3 is a robust-group rule. A
convenient room keeps the single-admin commit it has today, and this plan gives it
promotion and demotion on those terms (Phase 6). **Direct messages** are unaffected.

---

## The headline: changing the admin list is easy; requiring a signature is not

Changing who is an admin needs nothing new. In a current-profile group it is an
`AppDataUpdate` on component `0x8003` (`marmot.group.admin-policy.v1`), and
marmot-kmp 2.2.1 stages it (`MlsGroupManager.stageAppDataUpdate`), checks it
(`MarmotGroupPermissions.canPromote` / `canDemote`), and refuses one that leaves no
admin (`MarmotGroupPolicy.enforceNoAdminDepletion`). The app even receives one today:
[ConvergenceForkJvmTest.kt:186](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/ConvergenceForkJvmTest.kt:186)
has a stand-in member promote the app over the wire.

R2 is different. To refuse an unsigned commit, every device has to look at a commit
before applying it and ask whether the group signed it. The engine cannot be told to
do that today:

| what R2 needs | what the libraries do | where |
|---|---|---|
| a hook that sees each commit before it applies | `MlsGroupPolicy.authorizeCommit` does, on the local build, on inbound, and in convergence replay | mls-kmp `group/MlsGroup.kt:1255`, `:2282`, `:2763` |
| the hook sees the signature the commit carries | it sees the proposals and the committer's leaf, not the commit's `authenticated_data` | `MlsGroupPolicy.authorizeCommit(group, proposals, committerLeafIndex)` |
| the hook knows which epoch the commit is for | `GroupView` has extensions, leaf count and identities. It has no epoch and no group id | mls-kmp `group/MlsGroupPolicy.kt:248` |
| the app can supply its own policy | `MlsGroupManager` hard-codes `MarmotGroupPolicy` everywhere it restores a group, and so do convergence and `ChatRoom.toMlsGroup` | marmot-kmp `groups/MlsGroupManager.kt:155`, `:246`, `:310`, `:341`, `:381`; `protocolCore/MlsCandidateStateEngine.kt:63` |
| a commit can carry a signature | `MlsGroup.commit(authenticatedData)` can, but no `stage…` function in `MlsGroupManager` passes it | mls-kmp `group/MlsGroup.kt:1158`; marmot-kmp `groups/MlsGroupManager.kt:438` |
| one commit can carry two changes (remove *and* drop from the admin list) | `stage` is private, and each public `stage…` makes a commit of one kind | marmot-kmp `groups/MlsGroupManager.kt:438` |

So **Phase 0 is upstream work.** Nothing in Phases 3–5 can be enforced on a receiving
device without it. The plan does not try to work around it in the app (by holding
commits back, or by checking them on a cloned group), for a reason D4 gives: a rule
that devices apply at different moments, from different inputs, splits the group.

---

## What the code does today

| fact | where | why it matters here |
|---|---|---|
| The app has no control for promoting or demoting, but [MarmotOutboundDao.leave](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/MarmotOutboundDao.kt:950) tells an admin who presses **Leave group** to "hand over admin first" or "make another member an admin, or disband the group" | — | an admin today can disband a group but cannot leave it |
| That message is thrown inside `viewModelScope.launch` in [ChatRoomDetailViewModel.leaveGroup](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/ChatRoomDetailViewModel.kt:197), and nothing catches it | — | the person is never shown it, and on Android an uncaught coroutine exception is a crash. Fixed at the top of Phase 10 |
| `Participant.adminAt` is a cache of the group's admin set, written on each applied commit and, for keyed rooms only, at creation | [MarmotInboundManager.kt:132](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotInboundManager.kt:132), [MarmotGroupCreation.kt:227](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotGroupCreation.kt:227) | it is wrong on the creating device of every convenient room and DM until the first commit. Phase 1 |
| Removing a member stages a bare `Remove`, so a removed admin's key stays in `0x8003` | [MarmotOutboundDao.kt:853](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/MarmotOutboundDao.kt:853) | **a removed admin who is invited back is an admin again**, with no caption. `AdminPolicyV1` says the key must go in the same commit. Phases 6 and 9 |
| Removal and disband are one admin's commit through the publish gate | `MarmotOutboundDao.removeMember`, `disband` | under R2 that changes in keyed rooms. Phase 5 |
| Repair already runs `FrostReducedEnrollment` for an existing id (`newId < n`) | [ShareRepairManager.kt:268](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/ShareRepairManager.kt:268) | promotion is the same primitive at `newId = n`, which nothing builds yet ([frost-share-refresh.md](./frost-share-refresh.md) 10c; [submodule-refresh.md](./submodule-refresh.md) Phase 6). Phase 8 |
| A refresh that withholds a removed member is built | `ChatRepository.rotateSharesWithout`, frost-share-refresh Phase 9 | demotion's share half. Phase 9 |
| The group's key state carries the id-to-member table, `MemberBinding(participantId, memberPublicKey, hostPublicKey)`, signed by the group | [GroupKeyStateEvent.kt:163](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/frost/GroupKeyStateEvent.kt:163) | the authenticated table an enrollment reads its expected public share from, and the authority for a room's founding members (D7) |
| Enrollment, refresh and repair sit behind `ExperimentalFeatures.shareRefresh`, off in release, until upstream drops "experimental" | frost-share-refresh D4 | **R1 cannot ship to release users before that.** R2 and R3 can. See *Ship order* |
| `CommitProcessed` names the epoch and not the committer | marmot-kmp `MarmotInboundProcessor.kt:75` | captions are unattributed, as the membership lines are. A keyed room's lines can name the proposer instead, from the signed statement (Phase 2) |

---

## The decisions

### D1. The admin set comes from the group's state, never from `adminAt`

Every check that decides an action reads `currentAdminIdentities()` and the tree off
the live group, as `SubgroupManager` already does
([SubgroupManager.kt:107](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/SubgroupManager.kt:107)).
`adminAt` is for display and caption diffs only. Phase 1 makes it accurate enough for
those.

### D2. Admin and share are kept in step, and shown apart (R1)

In a keyed room, a member's two facts move together but cannot change in the same
instant. The MLS commit is atomic, while an enrollment or a refresh is a multi-round
protocol between devices. So the plan keeps them in step by sequence, and the member
row shows each one on its own:

| row shows | meaning | what the room offers |
|---|---|---|
| **Admin · Holds a share** | settled | — |
| **Admin** only | promoted, enrollment still running or failed | **Retry enrollment** |
| **Holds a share** only | demoted or removed, rotation not finished | **Rotate shares** (the existing offer) |
| neither | a member who does not govern | — |

The commit always goes **first**, for both directions:

- **Promotion: commit, then enrollment.** An admin without a share can publish a
  commit only once the group has signed it (R2), so they can do nothing alone. A
  share without the admin role would be signing power with nobody having agreed to
  give it. That is the worse half-state, so the order avoids it.
- **Demotion: commit, then refresh.** The demoted member is out of governance at
  once. Their share still counts towards `t` until the refresh finishes, so the
  refresh starts automatically, not as an offer (Phase 9). D10 names the risk that
  remains in the meantime.

### D3. Admin rights are an offer the new admin accepts

Holding a share "puts the member in a group's signing quorum for good, and nothing on
the device can decide that for them"
([automatic-key-ceremony.md](./automatic-key-ceremony.md)). A promotion in a keyed
room is therefore an offer: the target accepts by publishing an enrollment host key,
and only then does the quorum sign. That order is not a choice, because the key
state that binds the new id to them needs their host key (`MemberBinding`). After
that one acceptance, everything runs by itself, which is that note's rule: the
device asks only for what it cannot decide.

A convenient room has no share, so its promotion needs no acceptance, the same as
being invited.

### D4. The signature rides inside the commit, and the rule lives in the group's state (R2)

Two choices here decide whether R2 holds at all.

**Inside the commit, not beside it.** The group-signed statement goes in the commit's
`authenticated_data`. That field is authenticated by MLS and, under Marmot's outer
layer, readable only by members. A statement published as a separate message would
race the commit on relays that impose no order. A device that saw the commit first
would refuse it, a device that saw the statement first would apply it, and the group
would split into two epochs. With the statement inside the commit, every device
reaches the same verdict from the same bytes, at the same moment.

**In the group's state, not in the app's settings.** Whether a room needs signed
commits is a GroupContext component, `giza.governance.threshold.v1`, in the private
component range. It holds the room's signing key, `t`, and the admin floor (R3).
Marking it *required* makes RFC 9420's capability check enforce it on membership: a
leaf that does not advertise the component cannot be added, and the component cannot
be activated while such a leaf is present. So the rule can never be on for some
members and off for others. A device that does not know the rule cannot be in the
room.

*Rejected:* MLS proposals by reference. MIP-03 lets a member send only `SelfRemove`
on its own (`MarmotGroupPolicy.authorizeProposal`). "Proposal" in R2 is a Giza signing
proposal, the kind the room already uses for key states and subgroup certificates.

### D5. A statement is bound to one epoch, and names exactly what the commit does

The quorum signs a statement that is readable on its face, as with
[subgroups.md](./subgroups.md)'s informed consent:

```
room   <room id>
epoch  <E>             -- the epoch the commit is built at
add    <key package event id>…
remove <account>…
admins <the whole sorted list after the commit>
disband
```

On receipt, the policy checks the commit's non-exempt proposals realise *exactly*
those lines at epoch `E`: every Add's KeyPackage matches a named event, every Remove's
leaf belongs to a named account, and the `0x8003` update equals `admins`. Nothing
more and nothing less.

**Why one epoch:** a statement valid at any epoch could be replayed. "Demote B",
signed once and applied, could be applied again after B has been promoted back. The
exact epoch makes each statement single-use.

**What it costs:** a statement whose epoch has passed is dead. Signing takes as long
as the slowest of `t` members. Meanwhile an exempt commit (D6) or a rival statement
can advance the epoch, and the proposer's device then re-proposes at the new epoch by
itself (Phase 5). To keep exempt commits from killing statements needlessly, a
pending member `SelfRemove` is folded *into* the governance commit when one is due,
because exempt proposals may ride along with a signed commit.

### D6. Two exemptions: a member's own leave, and a member's own key update

**Decided, and the one place this plan narrows R2 as first stated.** MIP-03 lets any
member make exactly two kinds of commit without being an admin:

- a commit made only of `SelfRemove`s, which is someone leaving;
- a single self-Update, which is a member refreshing their own leaf key.

Neither changes who governs the room or who is in it, beyond the leaver themselves.
Requiring the quorum's signature for them would mean:

- **a member cannot leave without the group's permission.** Their leaf stays in the
  tree and keeps decrypting everything until `t` others agree to let them out. That
  is the opposite of what leaving is for, and it gives a quorum a way to hold someone
  in a conversation;
- **post-compromise recovery waits on a vote.** A member who suspects their device
  was compromised cannot rotate their own leaf key until the quorum signs.

So the policy exempts these two, and only these two. R2 with no exceptions was
considered and turned down for the two reasons above. Taking the exemption out later
would be one `if` in the policy, plus a signing step in Phase 10's leave flow for a
member who is not an admin.

### D7. A room's founding commits are authorised by its key state

A keyed room is born at epoch 0 holding only its coordinator, who then adds the
ceremony's participants. At that moment no governance statement could exist yet. But
the key state the quorum has already signed (`GroupKeyStateEvent`, which every
`MarmotGroupCreation` waits for) names exactly those people in its `MemberBinding`s.
So founding Add commits carry the signed key state as their authorisation, and the
policy accepts an Add-only commit whose new members are all named in it. No new
signing round is needed, and creating a room stays fully automatic.

### D8. Admins at least `max(3, t)` in a robust group (R3)

Three is the floor `MINIMUM_ROBUST_GROUP_SIZE` already uses: below it, a majority is
not a check. The second term matters as much. In a keyed room the admins *are* the
share holders (R1), so a demotion that leaves fewer admins than `t` leaves a key that
can never sign again, and nothing could ever change the room after that. The floor is
checked in three places:

- the view model, which hides an action that would break it and says why;
- the outbound DAO, which refuses before a proposal is opened, because a statement
  nobody can lawfully sign wastes everyone's approval;
- the policy, which reads the floor from the component, so a commit that breaks it is
  refused by every device even if the quorum signed it.

`t` itself does not change here. A refresh keeps the polynomial's degree, so lowering
the threshold is a different protocol and is out of scope.

### D9. Current-profile rooms only

The same rule removal and disband follow. A legacy room rewrites its whole `0xF2EE`
blob on every admin change, and the app governs no legacy room today.

### D10. What the room is exposed to between a demotion and its refresh

Until the refresh completes, the demoted member's share still counts. They plus `t-1`
others can still sign anything, including a statement that promotes them back. That
is the window frost-share-refresh's headline describes, and this plan narrows it
rather than closing it:

- the refresh starts automatically, with the signed demotion as every holder's
  approval (Phase 9);
- the row reads **Holds a share** without **Admin** until it finishes, so the room
  can see the window is open;
- while it is open, the room shows a banner with the Shares section's **Rotate
  shares**, for the case where the automatic refresh stalled on an offline holder.

---

## Phase 0 — upstream: let a group demand a signature

**About a week, most of it review time. Blocks Phases 4, 5, 7, 8 and 9.**

In **mls-kmp**:

1. `authorizeCommit` receives what it needs to check a signature. Add a
   `CommitContext(epoch, groupId, authenticatedData)` parameter, with a default
   overload so existing policies compile unchanged. Pass it at all three call sites
   (local build, inbound, convergence replay), so that a commit refused on receipt is
   also refused before it is built.
2. A test in mls-kmp shows a policy refusing an inbound commit by its
   `authenticated_data`, with the group left at the old epoch.

In **marmot-kmp**:

3. `MlsGroupManager`, `MlsCandidateStateEngine` and the convergence engine take an
   `MlsGroupPolicy` that defaults to `MarmotGroupPolicy`, and use it everywhere they
   restore a group (the six sites in the table above).
4. Every `stage…` function takes `authenticatedData: ByteArray = ByteArray(0)`.
5. A public `stageProposals(id, proposals, authenticatedData)` that builds one commit
   from several proposals. Removal (`Remove` plus the `0x8003` update) and invite
   (`Add`, optionally plus the update) need it.
6. Independently: `stageRemoveMember` drops the removed account's key from `0x8003`
   when it removes their last leaf, which is `AdminPolicyV1`'s own invariant. That
   fixes the re-invite hole for every Marmot client, not just this one.

Check, and write down the answer in this note:

7. Whether the current Marmot profile says anything about `authenticated_data` on a
   commit. Keyed rooms with the component are Giza-only by construction (D4), so a
   conflict would not break interop, but it should be known.
8. Whether `FrostReducedEnrollment` accepts `targetId == n` (a new id, growing the
   group), not only `targetId < n`. Upstream's KDoc calls this "enrollment proper".
   If it does not, Phase 8 waits on that and R1 waits with it.

Upstream is vitorpamplona's. If the review is slow, the fallback is a fork published
the way `lightning-kmp-app` is: on a branch, pinned by commit in the catalogue, with
the PRs open against upstream so the fork can be dropped later.

## Phase 1 — `adminAt` is true from the first moment

**Half a day.** Unchanged from the first draft. Nothing a user sees, and every caption
stands on it.

1. One function, `MarmotInboundManager.reconcileAdmins(database, localChatRoom,
   mlsGroup)`, writes `adminAt` from `currentAdminIdentities()` and captions nothing.
2. Call it wherever a room is made: `SelectChatRoomTypeViewModel.createMarmotChatRoom`,
   `MarmotOutboundDao.createMlsDirectMessageChatRoom`, and `MarmotGroupCreation`, in
   place of `stampAdmins`.
3. Call it once, silently, for rooms already on devices, from
   `initiateChatRoomDetail`.
4. Captions get their own prior admin set, never the cache. On a confirmed own commit
   that is `obligation.priorState` in
   [MarmotEngine.confirmPublished](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotEngine.kt:209).
   On an inbound commit it is the live group's `currentAdminIdentities()`, copied
   before `engine.processGroupEvent` at
   [NostrDao.kt:1413](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt:1413).
   It is passed in as `priorAdmins`. With no `priorAdmins`, there is no admin
   caption.
5. Fix the KDoc on [ChatRoomType.kt:8](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/types/ChatRoomType.kt:8).
   It says the admin list is "stamped into epoch 0", and it says a robust room's
   every member administers it. After this plan neither is true.

**Tests:** a fresh convenient room and a fresh DM flag the creator before any commit.
An existing room with no flags is reconciled when opened, with no line written. The
first invite in a convenient room writes "joined" and no admin line.

## Phase 2 — the group says who became an admin

**Half a day.**

Add `TYPE_ADMIN_ADDED` and `TYPE_ADMIN_REMOVED` beside the membership lines in
[ChatMessage.kt:569](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt:569).
Add both to `MEMBERSHIP_TYPES`, since a type missing from it renders as a chat bubble.
Give them an icon in `ChatTranscript`'s `when`
([ChatTranscript.kt:882](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/chat/ChatTranscript.kt:882)),
or they fall through to `PanTool`, the icon for a request.

They are written in `processGroupMembershipChanges` from the Phase 1 diff, in the
library's order (`MarmotSystemRowDiff`): departures, arrivals, demotions, promotions.
Every device writes them, including the committer. An account that left the tree gets
"left the group" and no admin line.

| change | line | on the subject's device |
|---|---|---|
| promoted | `{name} is now an admin` | `You are now an admin` |
| demoted | `{name} is no longer an admin` | `You are no longer an admin` |
| enrolled (Phase 8) | `{name} now holds a share of the group's key` | `You now hold a share of the group's key` |

In a keyed room, the commit's statement names who proposed it. The line can then say
`{proposer} made {name} an admin, with {k} of {n} signatures`, which is an attribution
the convenient room cannot give. That is optional, and Phase 5 decides once the
statement is parsed on the inbound path anyway.

## Phase 3 — the governance statement

**Two days.** Pure, and in `commonTest`.

`GovernanceStatementEvent`, under `nostr/frost/` beside `GroupKeyStateEvent`. It is a
new parameterised kind in the 303xx range, chosen against `RetiredKinds` so the wallet
audit stays green. The content is D5's lines as tags (`epoch`, `add`, `remove`,
`admins`, `disband`, `proposer`), with the room id as its `d` tag, the way the key
state is self-addressing.

Three pure functions carry the whole rule, so the policy, the signing approval and the
tests share one definition:

```kotlin
/** What [proposals] at [epoch] would do to the room, in a statement's terms. */
fun GovernanceChange.of(group: GroupView, epoch: Long, proposals: List<PendingProposal>): GovernanceChange

/** The proposals that need no signature (D6): own SelfRemoves, a lone self-Update. */
fun GovernanceChange.isExempt(group: GroupView, proposals: List<PendingProposal>, committer: Int): Boolean

/** Whether [statement] authorises exactly [change], signed by [roomKey]. */
fun GovernanceStatementEvent.authorises(change: GovernanceChange, roomKey: XOnlyKey): Boolean
```

`ProposedEvent.summaryOf` gets an arm for the new kind, so the signing approval reads
"Make Bob an admin" and not a hash, as the subgroup notes insisted for certificates.

**Tests:** statement round-trips; one test per refusal (wrong epoch, an extra Add, a
missing Remove, an admin list off by one, a signature by another key, a statement for
another room); `isExempt` for each MIP-03 case and for a SelfRemove riding in a signed
commit.

## Phase 4 — the policy, and the component that turns it on

**Three days.** Needs Phase 0.

**`GizaGroupPolicy`**, an `MlsGroupPolicy` that delegates to `MarmotGroupPolicy`,
then, when the group carries `giza.governance.threshold.v1`:

1. returns if the commit is exempt (D6);
2. otherwise requires `authenticated_data` to hold a statement that `authorises` the
   commit, or, for the founding commits, a key state that names every new member
   (D7);
3. requires the admin count after the commit to be at least the component's floor
   (D8).

It is supplied everywhere a group is restored: the engine, convergence, and
`ChatRoom.toMlsGroup` ([ChatRoom.kt:228](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatRoom.kt:228)).
Then a grep in `build.gradle.kts`, in the manner of the marmot-kmp import check, fails
the build on a `MarmotGroupPolicy` named anywhere else, because a group restored with
the bare policy silently drops the rule.

**`ThresholdGovernanceV1`**, the component: the room's x-only signing key, `t`, and
`minimumAdmins`, TLS-encoded like the library's components. It is advertised in the
app's leaf capabilities from this phase on, so every device on this build can be in
an enabled room.

**New keyed rooms** get it in epoch 0, in `MarmotGroupFactory.create`, when
`MarmotGroupCreation` passes a key, and it is listed as required.

**Existing keyed rooms** are switched on by an enablement commit, which is itself a
signed statement (`enable governance`, Phase 5), and only once every leaf advertises
the component. Until then the room is governed the way it is today, and its detail
screen says why: "{name} needs to update Giza before this group's changes can require
signatures". The library's `componentSupportBlockers` already names those leaves.

**A Bennu member blocks this.** Bennu does not advertise the component, so a keyed
room with a Bennu member in it cannot enable R2, and an enabled room cannot add one.
That is D4 doing its job, but it is a real consequence for groups that span the fork,
and the screen should say so plainly, not report a generic failure.

**Tests** (`jvmTest`, beside `ConvergenceForkJvmTest`, with `MarmotStandIn` patched to
use the same policy):

- an unsigned admin commit in an enabled room is refused on receipt, and the room stays
  at its epoch on every device;
- a commit signed for epoch `E` arriving at `E+1` is refused;
- a signed commit that also carries an extra Add is refused;
- a member's SelfRemove-only commit, unsigned, is accepted;
- a signed demotion below the floor is refused by every receiver;
- founding Adds named by the key state are accepted, and one more Add is refused;
- two devices handed the same commits in opposite orders end in the same state, which
  is D4's claim.

## Phase 5 — propose, sign, commit

**Four days.**

`GovernanceManager` turns a change into a commit in three steps, all through machinery
the room already has:

1. **Propose.** The admin's device reads the live group (D1), checks the change against
   `MarmotGroupPermissions` and the floor, builds the statement at the current epoch
   `E`, and opens a FROST signing session for it with
   `FrostSigningManager.proposeSigningBatch`. A promotion's batch also carries the new
   key state (Phase 8), so one round of approvals signs both.
2. **Sign.** Members approve through the existing signing approval, which now reads the
   statement's summary. `t` signatures complete it, as for any group-signed event.
3. **Commit.** When the signed statement is filed, any admin's device that holds it and
   whose group is still at `E` stages the commit. It uses `stageProposals` with the
   statement as `authenticatedData`, through `publishGatedCommit`, so relay rejection,
   restart and confirmation already behave. The proposer's device goes first. The
   others wait a short, randomised delay and then look again, so a proposer who went
   offline after signing does not strand the change, and two devices racing make an
   ordinary fork that convergence settles. Both branches carry the same signed change,
   so either winning is the same outcome.

**Stale statements.** When the group moves past `E` before the commit, the session is
marked stale and the proposer's device re-proposes at the new epoch automatically.
That is a new signing round, which nobody's earlier approval covers, so it is asked
again. Pending `SelfRemove`s are folded into the governance commit, not committed on
their own, while a statement at the current epoch is being signed (D5).

**Every existing commit path in a keyed room moves onto this:**

| today | after |
|---|---|
| `inviteMember` | propose `add` |
| `removeMember` | propose `remove` (and `admins` without them, if they were one) |
| `disband` | propose `disband`, then the lifecycle's two commits under one statement each |
| `commitStagedProposals` | unchanged: SelfRemove-only commits are exempt |
| founding adds in `MarmotGroupCreation` | unchanged in shape, with the key state in `authenticatedData` (D7) |

The detail screen shows a pending change on the member's row ("Being made an admin:
1 of 2 signatures"). Snackbars say "Proposed; it takes effect once {t} of you sign"
instead of "sent".

**Tests**, across three in-memory devices with real FROST (`CeremonyNetwork`):

- invite, remove and disband each run propose → sign → commit and land on every
  device;
- a statement staled by a member leaving is re-proposed at the new epoch and lands;
  the leaver's SelfRemove lands inside it;
- the proposer goes offline after the signature: another admin commits it;
- a restart between the signature and the commit: the commit is still made.

## Phase 6 — convenient rooms: one admin's commit

**One day.** No upstream dependency. Can land straight after Phase 2.

This is the first draft's Phase 3, kept for the rooms R2 cannot reach.
`MarmotOutboundDao.setAdmins(localChatRoom, admins)` stages
`stageAppDataUpdate(0x8003, AdminPolicyV1.ofHex(admins))` through `publishGatedCommit`.
`promote` and `demote` build the list from the live group, refusing a target not in the
tree, a demotion that leaves no admin among the members (`canDemote`), and a commit
while another is waiting.

Removing an admin here needs the `Remove` and the list update together. Until Phase 0
step 5 lands, that is two commits chained in memory: demote, then remove when the
first is confirmed. A restart in between leaves a demoted member, and **Remove** works
in one commit after that. Once `stageProposals` exists, it becomes one commit.

**Tests** (`GroupLifecycleJvmTest`): the app promotes the stand-in, which can then
remove a member; demotion takes that ability away; the only admin cannot demote
themselves; **a removed admin re-invited is not an admin**. That last test fails on
today's code.

## Phase 7 — the floor (R3)

**One day.** Needs Phase 4 for the policy half. The UI and DAO halves can land
earlier.

- `ChatRoomType.minimumAdmins(t) = max(MINIMUM_ROBUST_GROUP_SIZE, t)`, one function
  for all three checks (D8).
- `GovernanceManager` refuses a demotion, an admin's removal or an admin's leave that
  would cross it, *before* proposing. The words name the fix: "A group needs at least
  {floor} admins. Make someone else an admin first".
- The view model hides **Remove as admin** and **Step down** on rows where it would
  cross it, and says why on the dialog that would have offered it.
- The policy checks it on receipt (Phase 4).

Rooms made before this plan are at least three already: the robust picker and the
subgroup picker both refuse fewer.

**Tests:** a three-admin room cannot demote; a four-admin room with `t = 3` can demote
one, and not a second.

## Phase 8 — promotion in a keyed room: offer, sign, commit, enroll

**Four days.** Needs Phases 0 (step 8), 4 and 5, and the enrollment primitive. Behind
`ExperimentalFeatures.shareRefresh`.

1. **Offer.** An admin taps **Make admin**. A promotion offer goes to the room, naming
   the target (a new 303xx kind; it carries no authority, it is a request).
2. **Accept.** The target's device asks them, once: "{name} wants to make you an admin
   of {group}. You'll hold a share of the group's key, and you'll be asked to approve
   its changes". If they accept, the device publishes an enrollment host key, in the
   ceremony's format. This is D3's one approval.
3. **Sign.** The proposer's device opens one batch with two events:
   - the governance statement (`admins` with them added);
   - the next key state, whose `MemberBinding`s add `n → (target, host key)` and whose
     public-share table adds `P_n`, interpolated in the exponent from the existing
     table. That gives the enrollment an expected public share from authenticated
     state, never from the helpers ([submodule-refresh.md](./submodule-refresh.md)
     Phase 6).
4. **Commit**, as in Phase 5. The row now reads **Admin**.
5. **Enroll.** `t` live holders run `FrostReducedEnrollment` at `targetId = n`, with no
   further approval: the signed key state naming the target at `n` is the approval,
   the same rule automatic-key-ceremony Phase 7 uses for a key state. This is
   `ShareRepairManager` generalised from `targetId < n` to `targetId = n`. Its rounds,
   pairwise sealing and persistence are already built, and `n` grows by one in the
   stored key. When the target's share checks against `P_n`, the row reads **Admin ·
   Holds a share**.

If enrollment fails, the room shows **Retry enrollment** on the row (D2). The retry is
a new enrollment session under the same signed key state. It needs no new governance
signature, because nobody's role changes.

**Tests** (beside the repair cases in `ShareRefreshRitualJvmTest`, on its fixtures,
with real FROST):

- a member accepts, the batch signs, the commit lands, the enrollment completes, and
  the new admin's partial signature then helps sign an event;
- a member declines, and nothing is signed;
- enrollment with a helper who presents a table that disagrees with the signed one
  fails before the share is stored.

## Phase 9 — demotion and removal in a keyed room: sign, commit, refresh

**Three days.** Needs Phases 4, 5 and 7, and the refresh. Behind the same flag.

1. **Sign** a statement: `admins` without them, plus `remove` for a removal. One
   commit carries both (Phase 0 step 5), so a removed admin never stays on the list.
2. **Commit.** The row reads **Holds a share** only.
3. **Refresh** without them, started automatically by the proposer's device. This is
   `rotateSharesWithout`, from frost-share-refresh Phase 9, with each remaining
   holder's deal and confirm made automatic when the refresh names a signed demotion
   or removal of the member it withholds. The refresh still needs every remaining
   holder (frost-share-refresh D8), so a stalled refresh is the case D10's banner is
   for.

The demoted member's own device drops nothing by itself: its share becomes worthless
when the refresh completes, which is when frost-share-refresh's Phase 8 deletes the
old generation everywhere.

**Tests:** demote, and the demoted member can no longer help reach `t` after the
refresh, but could before it; remove an admin, and they are off `0x8003` in the same
commit; demote below the floor is refused before anything is proposed.

## Phase 10 — leaving as an admin

**Two days.**

**First, the crash.** `leaveGroup` in the view model wraps the call in `runCatching`
and reports the failure through the notifier, as `disbandGroup` does. Ship this alone
if everything else slips.

Then **Leave group**, pressed by an admin, opens a dialog in place of firing:

| room | dialog | what happens |
|---|---|---|
| convenient, another admin remains | "You'll step down first, then leave" | one-admin step-down commit (Phase 6), then the `SelfRemove` when it is confirmed |
| convenient, the only admin | pick a member to make admin, or **Disband group instead** | promote the pick, then step down and leave, chained |
| keyed, above the floor | "You'll step down, the group signs it, then you leave" | Phase 9's demotion of yourself, then the exempt `SelfRemove` once it lands. The refresh without you runs after you are gone, on the others' devices |
| keyed, at the floor | "A group needs at least {floor} admins. Make someone else an admin before you leave" | nothing until a promotion has landed |

The chain from step-down to `SelfRemove` is held in memory. A restart leaves a
non-admin member who has not left yet, which is safe, and a plain **Leave group** then
works.

**Tests:** each row of the table, and the existing `an admin cannot leave, and is told
why` test changed to check that the message reaches the caller.

## Phase 11 — the member row

**Two days.**

Under each member's name, the labels from D2 (**Admin**, **Holds a share**, joined with
` · `), read from the group and from `SharesSummary.members` with
`ShareStanding.LIVE`, and a third line while a change is pending ("Being made an
admin: 1 of 2 signatures"). **Holds a share** follows the experimental flag, so a
release build shows **Admin** only until R1 ships.

An admin's trailing control becomes an overflow `IconButton` with a `DropdownMenu`.
Its items come from a per-member `memberActions` the view model computes from the
library's permissions, the floor and the room kind. The screen never decides an action
itself.

| row | items |
|---|---|
| a member | **Make admin**, **Remove from group** |
| another admin | **Remove as admin** (above the floor), **Remove from group** |
| an admin with no share | **Retry enrollment** |
| a non-admin with a share | **Rotate shares** |
| yourself, an admin | **Step down as admin** (above the floor) |

The dialogs say what each act gives or takes:

- **Make {name} an admin?** — in a convenient room: "They'll be able to add and remove
  members, including you, and disband the group". In a keyed room: "They'll be asked
  to accept, then hold a share of the group's key. This needs {t} of you to sign".
- **Remove {name} as admin?** — "They stay in the group as a member". In a keyed room:
  "Their share is rotated away once the group signs".
- **Step down as admin?** — "You stay in the group. Only the group can make you an
  admin again".

All text comes from the catalogue, in sentence case. Outcomes go through
`rememberNotifier`, read above the handler. The overflow button is a 48dp `IconButton`
with a decided description. `./gradlew :composeApp:m3Audit` stays at its budgets.

**Tests:** `memberActions` for every row above, in both room kinds, at and above the
floor.

## Phase 12 — across devices, and the docs

**Two days.**

- **Fork:** two admins' devices commit the same signed statement at once, and both end
  in one state with one caption (Phase 5).
- **Split check:** a stand-in using the bare `MarmotGroupPolicy` commits unsigned into
  an enabled room. Every app device refuses it, and the stand-in is the one that
  diverges. That is the case D4's required component exists to prevent, and this test
  shows the component refusing to let such a leaf in at all.
- **Restart:** with `PublishBeforeApplyJvmTest`'s pattern, through propose, sign and
  commit.
- Docs: this note's status line and a table of where the build chose differently, the
  README entry, a pointer from marmot-v2's Phase 7 row about leaving, and a line in
  [shared-key-ceremony.md](./shared-key-ceremony.md) noting that the group's key now
  also governs its room.

---

## Ship order

R2 is a security property for every keyed room and does not need enrollment. R1 does,
and enrollment cannot reach release users until upstream drops "experimental". So:

| release | phases | users get |
|---|---|---|
| first | 10 (the crash fix alone), 1, 2, 6 | convenient rooms: promote, demote, step down, leave as admin; the re-invite hole closed there |
| second | 0, 3, 4, 5, 7 | keyed rooms: every change is proposed and signed; the floor; no promotion yet, because R1 cannot be met |
| third, when enrollment leaves experimental | 8, 9, the rest of 10, 11, 12 | keyed rooms: promotion with a share, demotion with a refresh |

In the second release, a keyed room's **Make admin** is hidden, not offered without a
share. That would break R1, and an admin without a share is exactly the half-state D2
keeps temporary.

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew check
```

By hand, with three devices in a robust group and the experimental flag on:

1. A proposes making D (a member) an admin. D accepts. B signs. The commit lands, then
   D's row goes from **Admin** to **Admin · Holds a share**.
2. D helps sign a message, and the signature verifies under the group's key.
3. A tries to demote B in a group of three admins and is told the floor. A promotes
   someone first, then demotes B. B's row reads **Holds a share** until the refresh
   ends.
4. A stand-in sends an unsigned removal. No device applies it.

## Estimate

| phase | size |
|---|---|
| 0 — upstream | about a week, mostly review |
| 1 — `adminAt` | half a day |
| 2 — captions | half a day |
| 3 — the statement | two days |
| 4 — the policy and component | three days |
| 5 — propose, sign, commit | four days |
| 6 — convenient rooms | one day |
| 7 — the floor | one day |
| 8 — promotion with a share | four days |
| 9 — demotion with a refresh | three days |
| 10 — leaving as an admin | two days |
| 11 — the member row | two days |
| 12 — across devices, docs | two days |

About five weeks of work, with Phase 0's review time on top. The first release's
phases need about three days and wait on nothing.

## Out of scope

- **Changing `t`.** A refresh keeps the polynomial's degree. Raising or lowering the
  threshold is a resharing protocol of its own.
- **Legacy-profile rooms** (D9) and **robust NIP-17 rooms**, which have no admins.
- **R2 in convenient rooms.** There is no key to sign with. A convenient room that
  wants R2 becomes a robust group by running a ceremony, which is a different act.
- **Quorum-signed chat.** R2 is about commits. Application messages stay each member's
  own.

## Appendix — what was considered and rejected

**Enforcing R2 in the app without upstream changes.** Either by holding a commit until
its statement arrives, or by running it on a cloned group under the app's own policy
first. Both make the verdict depend on what a device has seen and when. That is how
D4's split happens, and it is worse than no rule, because it looks like one.

**Signing the commit's bytes, not a statement.** It binds just as tightly, but signers
would approve an opaque hash, which is what the subgroup notes spent a section
removing. Every commit would also have to be built before signing and rebuilt if the
epoch moved, so the hash would change with it.

**A statement valid at any epoch, with a precondition ("admins are A").** It survives
staleness, but it can be replayed whenever the room comes back round to `A`. D5.

**Share first, then the admin commit.** It leaves a share holder who governs nothing
but can sign, which is signing power nobody agreed to give. D2.

**No exemptions.** It was considered and turned down: a member could not leave
without the group's permission, and could not replace a compromised leaf key without
a vote. D6.

**The library's `MarmotManager.leave`.** It picks the heir by leaf order and publishes
outside the gate.

**Reading the admin set from `adminAt`.** It is wrong on the creating device. D1.
