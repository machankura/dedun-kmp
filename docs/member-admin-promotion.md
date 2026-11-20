# Making a member an admin, and back

A plan for promoting a member of a group to admin and demoting an admin back to
member. It also covers the jobs that need the same machinery: removing an admin, an
admin stepping down, and an admin leaving.

**A phased plan, built but for its keyed-room leave.** Written against the code as it stood at `fc437d05`, with
marmot-kmp 2.2.1 and mls-kmp 1.3.0 as the catalogue pins them. The second draft adds
three rules the first draft lacked; they are stated in the next section, and most of
the plan follows from them.

**Built**, one commit per phase on `claude/member-admin-promotion-plan-d9d716`: the
first release's phases (10a, 1, 2, 6, and 10 and 11 for convenient rooms), Phases 3, 4a,
4b, 5 and 5b, Phase 7 on both sides, and Phases 8 and 9 under the experimental FROST
enrollment (`ExperimentalFeatures.shareRefresh`, off in release). The first draft's
Phase 0 -- upstream work in mls-kmp and marmot-kmp -- was dropped when the commit moved
inside the signature (the headline). **Not built:** Phase 10's keyed-room rows (an admin
of a keyed room still cannot step down or leave) and Phase 12. **Not run:** nothing here
has been on a device. The enrollment itself is tested end to end with real reduced FROST
across in-memory devices (`ShareRefreshRitualJvmTest`): a member who never took part in
the ceremony is enrolled at the next id and signs. What no test runs is the whole
promotion in one go -- offer, acceptance, the signed batch through
`GovernanceManager.propose`, the commit, then the enrollment -- since the governed-room
tests stand a key pair in for the room's key and the enrollment tests run in a room
that is not governed; each half is tested on its own.

Where the build chose differently:

| what the plan said | what it turned out to be |
|---|---|
| Phase 1's `reconcileAdmins` as the single writer of `adminAt` | `AdminFlags.reconcile`, from [robust-group-admins.md](./robust-group-admins.md), which was built at the same time for the same reason and also runs on the Welcome and once at start. Phase 1's own function was dropped when the two met; its creation-site calls now go through `AdminFlags` |
| Phase 2's lines in the library's order: departures, arrivals, demotions, promotions | after the membership lines, which keep the order they had (arrivals, then departures); then demotions, then promotions |
| Phase 3's statement has a `disband` line | a `component` line per GroupContext component the commit changes, by id and the hash of its new value. Disband, enabling disband and a rename are all that one rule, and a statement cannot sign off one component while the commit changes another |
| Phase 3's kind "in the 303xx range" | 30370, past the last retired wallet kind. 30371 is the own-commit envelope, 30372 the support announcement, 30373 the carrier |
| Phase 4a: the signed statement is published as an application message | inside a **carrier** (30373) sent by a member. The inbound path holds every application message to its sender's own key (MIP-03), and a statement is the room's, so it cannot travel as one -- which is also why `FrostSigningManager.complete` never publishes a group-signed event. The carrier vouches for nothing; the statement inside is checked on its own signature |
| Phase 4a: an own commit (30371) is "signed by the committer's account key" | sent by it: the inner event is an unsigned rumor whose author MLS has already authenticated as its sender, so the check is that the sender is the committer |
| D4: the marker is `giza-governance: threshold/v1` | `Changes need the group's signature; at least N admins.` -- it carries the **floor** too. A floor each device worked out from the ceremony it holds would differ between a founder and a member welcomed later, and the two would disagree about a commit |
| Phase 4a's copy-check reads a commit's proposals off the copy | the proposals off the copy, and who a Remove names off the live group as it stood before the commit: the copy's view can already have the removed leaf blank. Found by the inbound removal test; without it every signed removal was refused at the door |
| Phase 4b: an invite into a governed room is refused for an account that has not announced | not built. A joiner announces as soon as it hears another member say it, which every device does once per room; a joiner on a build that cannot follow is the open problem D4 names |
| Phase 5: any other admin publishes a signed change after a short wait | only the proposer publishes. A proposer who goes quiet after the signature leaves the change waiting until it comes back |
| Phase 5: a stale statement is proposed again automatically | it is marked stale and the room says so; proposing again is the person's |
| Phase 5b: a removal of an admin is two signed commits | one: the proposer builds it on its own copy of the group, which can stage a Remove and the admin-list update together where the library's manager cannot |
| Phase 5b: disband proposes each of its commits | in a room that has not agreed it can be disbanded, the first press proposes that agreement and says to disband again once it is signed |
| D7: founding commits authorised by the key state | dropped: the rule is switched on after founding (Phase 4b), so founding needs nothing |
| Phase 6's removal of an admin is "chained in memory" | through `MarmotEngine.afterConfirmed`, registered *before* the commit it waits on so a relay's OK cannot overtake it, and taken back if publishing throws. Leaving as an admin uses the same hook. In a governed room a removal is one signed commit instead |
| Phase 7's floor is checked on demotion, removal and leaving | on removal and on every governed commit at the door; a keyed room offers no demotion and no leave for an admin until Phases 8 to 10 |
| Phase 10's keyed-room rows: "above the floor" and "at the floor" | one row for now. A keyed room's admin who presses **Leave group** is told, as before, that an admin cannot leave; `AdminLeave.CANNOT` |
| Phase 8: promotion from any keyed room | only from a key past its ceremony's generation. Generation 0 lives in the ceremony's own record, which has no room for an id the ceremony did not make; the first rotation makes the generation rows an enrollment extends. **Make admin** says so when it is refused |
| Phase 8: offer and acceptance as "a new 303xx kind" | 30374 and 30375, and a third, 30376, for the **handover**: once the change lands, the offering device sends the new admin the table, roster and signed key state. The new member's device holds nothing of the key before that, and checks all of it against the room's own signature -- the state has to be one the room signed, the table has to hash to what it names, and it has to bind the new id to this account under this device's host key |
| Phase 8: `P_n` "interpolated in the exponent from the existing table" | from the table's first `t` entries, with `FrostEnrollment.derivePublicShare`. Every device does it, and adds the id only when the extended table hashes to what the group signed |
| Phase 8: enrollment as `ShareRepairManager` generalised | the repair protocol unchanged but for its `n`: a request names the `n` it runs with (the new id, the table's size before it), helpers check that against their own table, and an enrollment asks no helper for approval -- the signed binding was everyone's chance to say no |
| Phase 8: `n` grows in "the stored key" | a signing session's and a repair's `n` is the generation's table size, which the enrollment grew; the ceremony's `participantCount` is left as the ceremony made it |
| Phase 9: every remaining holder's deal and confirm made automatic | the rotation is started by the proposer's device once the change lands (`GovernanceFollowThrough`), and each holder is asked once to take part, as every rotation now asks (rotate-shares-single-approval). A rotation that cannot start is said in the room and offered from the Shares section |
| Phase 9: an admin can step down in a keyed room | not offered. The rotation that has to follow is the remaining holders', so another admin proposes the demotion |
| Phase 11's dialogs for every action | removal keeps the immediate action it had; promotion, demotion and stepping down ask first |

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
| **R2** | **Every commit is first a proposal, and becomes a commit only once the group's threshold signature is on it**, except a member's own leave and a member's own key update (D6) | No single admin can change a keyed room. An admin builds the commit and proposes it, `t` share holders sign an event that carries it, and only that signed event is published. Every device refuses a bare commit. |
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

## The headline: the signature carries the commit

Changing who is an admin needs nothing new. In a current-profile group it is an
`AppDataUpdate` on component `0x8003` (`marmot.group.admin-policy.v1`), and
marmot-kmp 2.2.1 stages it (`MlsGroupManager.stageAppDataUpdate`), checks it
(`MarmotGroupPermissions.canPromote` / `canDemote`), and refuses one that leaves no
admin (`MarmotGroupPolicy.enforceNoAdminDepletion`). The app even receives one today:
[ConvergenceForkJvmTest.kt:186](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/ConvergenceForkJvmTest.kt:186)
has a stand-in member promote the app over the wire.

R2 is the hard part: every device has to refuse a commit the group did not sign. The
library cannot be told to -- its commit hook sees neither the commit's
`authenticated_data` nor its epoch, and `MlsGroupManager` hard-codes Marmot's policy --
so the first draft made that upstream work. It does not have to be. **The commit
travels inside the group's signature instead of the signature inside the commit**:

1. The proposer's device stages the commit on a copy and builds the kind:445 event that
   carries it, exactly as it would publish it -- and does not publish it.
2. The group signs one event whose content *is* that kind:445 event, with Phase 3's
   statement as its tags. Each signer's device runs the commit on a throwaway copy of the
   group and checks it does what the tags say before approving.
3. The signed event goes out as an ordinary application message at the commit's epoch.
4. Every device verifies it and hands the inner kind:445 to the engine as if it had
   arrived from a relay. In a room where the rule is on, a kind:445 that is a bare
   commit is dropped before the engine sees it.

Everything that needs is public and already in the app's hands:

| what the envelope needs | where it is |
|---|---|
| one place every inbound group event passes through, before the engine applies it | `NostrDao.indexCurrentProfileGroupEvent` calls `engine.processGroupEvent` and nothing else does ([NostrDao.kt:1419](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt:1419)) |
| telling a commit from a message without processing it | after the Marmot outer layer, an MLS `PrivateMessage` or `PublicMessage` carries its `contentType` in the clear; `MarmotInboundManager.tryDecryptOuterLayer` already does the outer half |
| running a commit without applying it | `MlsGroup.restore(state, policy)` and `processFramedCommit` are public, and a policy passed to the copy is handed the commit's real proposals |
| applying a commit the app was handed rather than received | the inner kind:445 is a complete signed event; `engine.processGroupEvent` takes it like any other, and convergence sees it as it would any commit |
| the proposer's own commit, which it cannot process | the staged state and the publish gate: `gate.prepare` with the state staged at proposal time, confirmed when a relay accepts the envelope |

Because the inner kind:445 is the proposer's own event, byte for byte, every device
hands the engine the same event with the same id -- which is what convergence needs to
pick the same branch everywhere when two signed commits race.

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
| The group's key state carries the id-to-member table, `MemberBinding(participantId, memberPublicKey, hostPublicKey)`, signed by the group | [GroupKeyStateEvent.kt:163](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/frost/GroupKeyStateEvent.kt:163) | the authenticated table an enrollment reads its expected public share from |
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

### D4. The commit rides inside the signature, and the rule lives in the group's state (R2)

Two choices here decide whether R2 holds at all.

**The commit inside the signature, not beside it.** The group signs an event whose
content is the complete kind:445 commit event. A signature published as a separate
message would race the commit on relays that impose no order: a device that saw the
commit first would refuse it, a device that saw the signature first would apply it, and
the group would split into two epochs. With the commit inside the signed event, every
device reaches the same verdict from the same bytes at the same moment.

**The rule in the group's state, not in the app's settings.** Whether a room needs
signed commits is a marker line in its profile description, read the way
`MarmotRoomIdentity` reads the room id from the same place:
`giza-governance: threshold/v1`. It is switched on by an ordinary commit -- the room's
last bare one -- and from the next epoch every device that applies that commit enforces
the rule, from the state it just applied. Taking the line out again is itself a change
the group has to sign. A rule read from the state cannot be on for some members and off
for others.

What the marker cannot do is keep out a client that does not know it: Bennu, or an
older Giza build, applies the bare commits Giza drops and reads none of the envelopes.
So the switch is refused until every member's account has said, in the room, that it
applies signed commits (Phase 4b). A member who joins later on such a client cannot
follow the room, and the invite says so.

*Rejected:* MLS proposals by reference. MIP-03 lets a member send only `SelfRemove` on
its own (`MarmotGroupPolicy.authorizeProposal`). "Proposal" in R2 is a Giza signing
proposal, the kind the room already uses for key states and subgroup certificates.
*Rejected, from the first draft:* the signature in the commit's `authenticated_data`,
which every device's library would have had to be changed to read.

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

### D7. A room's founding commits come before the rule

A keyed room is founded by its coordinator's bare commits, which every member who joins
from the Welcome never processes. The rule is switched on afterwards (D4), once every
member has said it can follow it, so nothing at founding needs a signature. The first
draft authorised founding commits by the key state; with the switch, that is not needed.

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

## Phase 0 — none: the commit travels inside the signature

The first draft's Phase 0 changed mls-kmp and marmot-kmp so that a device could refuse an
unsigned commit. The headline shows the app can do it with the libraries as they are, so
there is no upstream work. Two library changes would still be welcome and are not needed:
`stageRemoveMember` dropping a removed account's admin entry in the same commit (the app
does it in two, Phase 6), and a public way to stage several proposals in one commit (a
promotion is one proposal; a removal of an admin is two commits until then).

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

## Phase 4 — the envelope, the filter, and the switch

**4a. The envelope and the filter. Three days.**

`GovernanceStatementEvent` (Phase 3) gains its content: the complete signed kind:445
commit event, as JSON. A second kind, `OwnCommitEvent` (30371), carries a commit that
needs nobody's signature (D6) -- a member's own leave folded in by an admin's device, or a
member's own key update -- signed by the committer's account key.

`GovernanceGate`, one function the inbound path calls for a current-profile room:

1. If the room's state does not carry the marker, do what is done today.
2. Peel the outer layer. A bare `COMMIT` is dropped before the engine sees it, with a
   line in the room saying a change arrived without the group's signature. A proposal or
   an application message goes on as today.
3. An application message whose inner event is a 30370 is checked: signed by the room,
   at the group's current epoch, and its inner commit -- run on a throwaway copy with a
   policy that captures the proposals -- does exactly what its tags say, and leaves at
   least the floor of admins (R3). Then the inner kind:445 is handed to the engine.
4. A 30371 is checked the same way, except that it must be exempt (D6) and signed by the
   account whose leaf committed it.

The copy is `MlsGroup.restore(liveState, policy)`; the policy delegates to
`MarmotGroupPolicy` and records what `authorizeCommit` is given. A commit the copy
refuses is refused.

**Tests**, with the stand-in as the other member: an unsigned commit in a governed room is
dropped and the room stays at its epoch; a signed one lands, on both sides, with the same
event id; one signed for another epoch, by a member's own key, or carrying an extra
proposal is refused; a member's leave in a 30371 lands; a governance change in a 30371
does not; nothing changes in a room without the marker.

**4b. Support, and the switch. Two days.**

- Each device on this build says once per keyed room, as an application message signed by
  its account (`GovernanceSupportEvent`, 30372), that it applies signed commits. The room
  records who has said it.
- **Require signatures for changes**, on a keyed room's detail screen, for an admin, once
  every current member has said it. It is one bare commit adding the marker line to the
  profile description. Until every member has, the screen names who has not. (Since
  made automatic -- one device, the admin in the lowest leaf, sends it once everybody has
  said it, and the button is gone; [remove-convenient-groups.md](./remove-convenient-groups.md),
  "After the build".)
- An invite into a governed room is refused for an account that has not said it, with
  words that say why, once it is in the room (it says so from its own device on joining).

**Tests:** the switch is refused while a member has not said it; after it lands, both
sides enforce from the next epoch; a member added later announces on joining.

## Phase 5 — propose, sign, publish

**Four days.**

`GovernanceManager` turns a change in a governed room into a commit:

1. **Propose.** The admin's device reads the live group (D1), checks the change against
   `AdminGovernance` and the floor, stages it on a copy at the current epoch `E`, and builds
   the kind:445 that carries it -- signed by a throwaway key, outer-encrypted, exactly as
   `publishCommit` would publish it. Nothing is published. The staged state (prior and
   pending), the commit event and any Welcome are stored in a `GovernanceProposal` row,
   because signing outlasts a session. Then it opens a FROST signing session over the
   unsigned 30370 whose tags are the statement and whose content is that kind:445.
2. **Sign.** Members approve through the existing signing approval, which reads the
   statement's summary. Before a device puts its share to it, it runs the inner commit on
   a copy and checks the tags (as the gate will), so nobody signs a commit that does not
   say what it does.
3. **Publish.** When the signature completes, the proposer's device -- if its group is
   still at `E` -- raises the publish gate with the stored staged state and publishes the
   signed 30370 as an application message. A relay's OK confirms the obligation, which
   installs the pending state, captions the change and sends any Welcome. Any other
   admin's device that holds the signed event and is still at `E` may publish it instead
   after a short wait, so a proposer who went quiet does not strand the change.

**Stale statements.** If the group moves past `E` first, the proposal is dead: the
proposer's device marks it and proposes again at the new epoch, which nobody's earlier
approval covers.

| today, in a keyed room | after the switch |
|---|---|
| `inviteMember` | propose an add |
| `removeMember` | propose a remove (with the admin list, if they were one) |
| `disband` | propose each of its commits |
| `commitStagedProposals` (a member's leave) | the same commit, in a 30371 |
| leaving as an admin | propose your own demotion; leave once it lands |

**Tests** across devices with real FROST (`CeremonyNetwork`): a removal runs propose →
sign → publish and lands on every device; a statement staled by a member leaving is
proposed again and lands; a restart between signature and publish still publishes.

## Phase 6 — convenient rooms: one admin's commit

**One day.** No upstream dependency. Can land straight after Phase 2.

This is the first draft's Phase 3, kept for the rooms R2 cannot reach.
`MarmotOutboundDao.setAdmins(localChatRoom, admins)` stages
`stageAppDataUpdate(0x8003, AdminPolicyV1.ofHex(admins))` through `publishGatedCommit`.
`promote` and `demote` build the list from the live group, refusing a target not in the
tree, a demotion that leaves no admin among the members (`canDemote`), and a commit
while another is waiting.

Removing an admin here needs the `Remove` and the list update together. Until the
library can stage both in one commit (Phase 0), that is two commits chained in
memory: demote, then remove when the
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
   statement covers both commits -- the demotion and then the removal -- so a removed admin
never stays on the list.
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
| second | 3, 4, 5, 7 | keyed rooms: every change is proposed and signed; the floor; no promotion yet, because R1 cannot be met |
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
| 1 — `adminAt` | half a day |
| 2 — captions | half a day |
| 3 — the statement | two days |
| 4 — the envelope, the filter and the switch | five days |
| 5 — propose, sign, publish | four days |
| 6 — convenient rooms | one day |
| 7 — the floor | one day |
| 8 — promotion with a share | four days |
| 9 — demotion with a refresh | three days |
| 10 — leaving as an admin | two days |
| 11 — the member row | two days |
| 12 — across devices, docs | two days |

About five weeks of work, and none of it waits on another repository. The first
release's phases need about three days and wait on nothing.

## Out of scope

- **Changing `t`.** A refresh keeps the polynomial's degree. Raising or lowering the
  threshold is a resharing protocol of its own.
- **Legacy-profile rooms** (D9) and **robust NIP-17 rooms**, which have no admins.
- **R2 in convenient rooms.** There is no key to sign with. A convenient room that
  wants R2 becomes a robust group by running a ceremony, which is a different act.
- **Quorum-signed chat.** R2 is about commits. Application messages stay each member's
  own.

## Appendix — what was considered and rejected

**Holding a commit until its signature arrives.** The verdict would depend on what a
device has seen and when, which is how D4's split happens. The envelope avoids it by
putting the commit and its signature in one event: every device decides from the same
bytes. (The first draft also rejected checking a commit on a copy under the app's own
policy, for the same reason; with the commit inside the envelope, the copy-check sees the
same bytes everywhere and the objection falls away.)

**The signature in the commit's `authenticated_data`, enforced by the library.** The
first draft's Phase 0: a week of changes to two libraries this app does not own, for a
rule the app can enforce at its one inbound door.

**Signing the commit's bytes alone, without a statement.** It binds just as tightly,
but signers would approve an opaque hash, which is what the subgroup notes spent a
section removing. The envelope signs both: the statement as tags a person reads, and the
commit as content their device checks against them.

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
