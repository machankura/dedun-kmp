# The parent decides: a subgroup's wallet policy, fixed at birth

Today a subgroup's parent agrees to *guard* it — it signs guardian keys into the
child's birth certificate — and the child decides whether to use them: its wallet
screen offers a guarded wallet and a plain one beside it, and whichever statement
its quorum signs is the wallet. This plan moves that decision up one level and
forward to the moment the child is made. **The parent's coordinator chooses
*guarded* or *unguarded* on the subgroup picker, the parent's quorum signs the
choice into the birth certificate, the child's admins agree to it by joining, and
every honest device thereafter offers, signs, derives and watches only the wallet
the certificate allows.**

Read [subgroups.md](./subgroups.md) and
[group-wallet-guardians.md](./group-wallet-guardians.md) first. This builds on the
certificate from the first and the guardian keys from the second, and it reverses
one section of the second — [*whose choice it is*](./group-wallet-guardians.md#what-this-reverses-and-whose-choice-it-is).

**Built**, phases 1-9, one commit each, written against the code as it stood at
`b4002804`, schema v28; the build is schema v29. `SubgroupWalletPolicy`,
`SubgroupBirthCertificateEvent.parseWalletPolicy`, `SubgroupManager.walletPolicyOf`,
`refuseCertificate` and `refuseKeyState` are the entry points. The phases below
are kept as written, line references included, which have since moved; where the
build chose differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 2: a migration test like the other bumps have | none. The manual migrations have tests; the nullable auto-migrations, 24→25, 25→26 and 27→28, have none, and 28→29 is one of them. The in-memory databases every jvmTest opens run at v29 |
| Phase 2: `proposeRitual` takes the policy | it also checks it first. A policy on a ceremony with no parent is refused before the running-ceremony lookup, which would otherwise hand back an existing ceremony for a question that should never have been asked |
| Phase 3: the picker reads the parent's policy | through `ChatRepository.walletPolicyOf` and `guardiansOf`, both new; the picker's view models talk to repositories, not managers. The policy refusals come before the key-package wait in `refusal()`, since they do not depend on it |
| Phase 3: a test that `confirm` passes the policy to `proposeRitual` | not written. `confirm` needs an `Identity` with a private key and settled key packages, and the tests cover the state it passes instead |
| Phase 4: `DkgRitualViewModel` passes `session.walletPolicy` | `proposeBirthCertificate` reads it off the ceremony it is already handed. A caller naming the policy would be a caller choosing what the parent signs, for the reason the key is read off the ceremony too |
| Phase 5: the child-signer check sits beside the certificate check | it is `SubgroupManager.refuseKeyState`, called from there and from `proposeSubgroupKeyState`, so the proposer refuses what every other child signer would |
| Phase 6: `account` is null under a guarded policy | `NotOpened.offersPlain`. A null `account` already meant "a room with no key" and hides the way in altogether, so the guarded button would have gone with it |
| Phase 6: `GroupWalletManager.walletPolicyFor` | arrived in Phase 7, where `walletFor` needed it. The wallet screen reads `ChatRepository.walletPolicyOf`, the same `SubgroupManager.walletPolicyOf` underneath |
| Phase 7: `GuardianManager.guardedFrom` | `GuardianManager.verify`, the function that believes a forwarded statement |
| Phase 8: *guarded* or *unguarded* added to the supporting line | a second line under it, on the subgroup rows and on the parent row. An undecided certificate shows nothing |

Still to do by hand: the three-emulator run in Phase 9.

---

## What the parent can and cannot decide

The guardians note says it plainly and it stays true:

> The child's threshold key is the child's. Its quorum can always pay to a plain
> BIP86 address under its own key, and no script in any address can stop it.

So "the parent decides" cannot mean a cryptographic rule. What it can mean, and
what this plan builds, is a **signed rule every honest client enforces at every
point it touches a wallet**:

| point | what enforcing the policy means there |
|---|---|
| offering | the child's wallet screen shows only the opening the certificate allows |
| proposing | `GroupWalletViewModel.openWallet` cannot build the other template |
| signing | every member's device refuses a wallet statement proposal that contradicts the policy, before it is shown — the same place a mismatched certificate grant is refused today |
| reading | `GroupWallet.newestAmong` ignores a statement that contradicts the policy, so no honest device derives, watches, or proposes a spend from it |

A quorum of modified clients can still sign whatever it likes with the child's
key. Nothing short of a covenant changes that, and the UI must never describe the
policy as more than it is. What changes is that the child's *members*, running
this app, can no longer open the wallet the parent ruled out — and that a
coordinator cannot quietly swap the choice, because three parties sign over it.

### Who agrees to what

The subgroups note settled a pattern for the threshold — "the one value the
coordinator picks *for other people*" — and this follows it exactly:

1. **the coordinator picks** the policy on `SelectSubgroupAdminsScreen`, beside
   the threshold;
2. **the child's admins agree** by joining: the policy rides on the ceremony
   proposal and is shown at the `HOST_KEY` gate (`DkgJoinApprovalScreen`), so
   nobody joins a group whose wallet the ancestors can take from without having
   been told;
3. **the parent's quorum signs it** into the birth certificate, and the proposal
   screen says it in words;
4. **the child's quorum signs it again** when it signs a key state carrying that
   certificate, and each child signer's device refuses a certificate whose policy
   differs from the one its ceremony announced.

Step 4 is what stops a second parent admin, resuming the flow after the
coordinator's phone died, from certifying the child under a policy its admins
never agreed to.

---

## Phase 0 — decisions, before any code

Five, each with a recommendation except D4, which is decided. The phases below
assume the recommended answer; where another answer changes a phase the decision
says which.

### D1. Which choices does the picker offer?

**Recommended: two — *guarded* (the default) and *unguarded*.** The certificate's
reading has a third state anyway, *undecided*, for every certificate signed
before this ships; that state keeps today's behaviour, both buttons. Offering
*undecided* in the picker as well ("let the subgroup decide") is a one-line
change once Phase 1 exists, and is worth adding only if somebody asks for it: the
point of the feature is that the parent stops delegating this.

### D2. What does *unguarded* put in the certificate?

**Recommended: no guardian keys at all**, plus the policy tag saying so.

- It is enforced by code that already exists. `verifiedAgainst` refuses a guarded
  statement whose guardians are not the certificate's, and a certificate with
  none makes every guarded statement fail. Old clients enforce it too, which no
  other choice here can say.
- Its cost is that the line of guardians **stops at the unguarded child**.
  `SubgroupManager.grantFor` reads the parent's own certificate for the keys to
  pass down; an unguarded child has none, so its own children receive only its
  key. The root reaches nothing below an unguarded branch. Under D4 such a branch
  can only start below a group that is not itself guarded, so a root that makes
  its children guarded keeps its reach into the whole tree.

The alternative is to keep the keys in the certificate and have the tag say
*unguarded*. That keeps the root's reach into the grandchildren while ruling out
the child's own guarded wallet. It costs a new rule in `verifiedAgainst`, and old
clients would offer a guarded wallet the parent forbade, because they see keys
and no tag they read. D4 takes away most of the reason to choose it: a root that
wants to reach everything makes its children guarded, and D4 then carries that to
every group beneath them.

### D3. Can the parent change its mind?

**Recommended: no — the policy is fixed at birth.** A certificate is replaceable
by `d` tag, but the child's key state carries the certificate it was born with,
and the guardians note's rule 2 already fixes an account's guardian set at its
first statement. Changing policy is therefore a new subgroup. This matches the
request ("at the time the child group is being created") and is named again
under [what this does not do](#what-this-does-not-do).

### D4. Does a guarded group have to make guarded children?

**Decided: yes.** A group whose own certificate reads `Decided(GUARDED)` can
only make guarded subgroups. Guarded is therefore inherited all the way down:
once a group is guarded, every group beneath it is guarded too, and every
ancestor above the first guarded group keeps its leaf in every wallet in that
subtree. A middle group cannot take a branch out of the root's reach by making
an unguarded child.

The rule needs no new tag and no inheritance machinery. The constraint is read
off the parent's own verified certificate, which every parent signer already
holds because `grantFor` reads it. It is enforced in two places:

- **the picker** (Phase 3) does not let a guarded parent's coordinator choose
  *unguarded*;
- **every parent signer** (Phase 4) refuses a certificate from a guarded group
  whose policy is not *guarded*. The picker can be skipped by a coordinator; the
  signers cannot.

What the rule reads, exactly:

| the parent's own certificate | the child may be |
|---|---|
| `Decided(GUARDED)` | guarded only |
| `Decided(UNGUARDED)` | guarded or unguarded |
| `Undecided`, from before this | guarded or unguarded |
| none — a root group | guarded or unguarded |

**An undecided parent is not constrained**, even when its certificate carries
guardian keys. Nobody decided anything for it: its own parent offered to guard
it and left the choice to it, and it may have opened a plain wallet. Treating
"keys present" as "guarded" would decide on the parent's behalf something its
own ancestors never signed. A group that wants its whole subtree guarded starts
that subtree with a decided certificate.

An unguarded group may make a guarded child. That child is guarded by its
unguarded parent, and by nothing above it, because D2's unguarded certificate
carries no keys to pass on. Guarded subtrees therefore start at a root, at an
undecided group or at an unguarded group, and run to the leaves.

### D5. What does a root group's wallet screen do?

**Unchanged: a plain wallet.** A group with no parent has no certificate and
nobody to guard it. The policy is only ever read off a verified birth
certificate.

---

## Phase 1 — the policy, pure

New, in `nostr/subgroup/`:

```kotlin
enum class SubgroupWalletPolicy { GUARDED, UNGUARDED }

/** What a certificate says about the child's wallet. */
sealed interface WalletPolicyReading {
    data class Decided(val policy: SubgroupWalletPolicy) : WalletPolicyReading
    /** No policy tag: a certificate from before this, which leaves it to the child. */
    data object Undecided : WalletPolicyReading
}
```

**`WalletPolicyTag`** in `nostr/subgroup/tags/`, in `GuardianTag`'s shape:

```
["wallet", "guarded" | "unguarded"]
```

`SubgroupBirthCertificateEvent` grows:

- `assembleTags(..., walletPolicy: SubgroupWalletPolicy?)` — written after the
  guardian tags; null writes none, which only an older build's path should take;
- `parseWalletPolicy(tags): WalletPolicyReading?` — null when the reading is
  unusable: a `wallet` tag that does not read, two of them, or a policy that
  contradicts the guardian tags beside it. **`GUARDED` requires a non-empty,
  readable guardian list; `UNGUARDED` requires none** (under D2). A certificate
  that says one thing in its policy and another in its keys is not a thing to
  guess the meaning of.

**`certifies` does not change.** It is about lineage, and its six questions stay
six. The policy is read where it is used, like the guardian keys are.

**Tests**, `SubgroupBirthCertificateEventTest`: a guarded certificate round-trips;
an unguarded one round-trips with no guardian tags; a certificate with no `wallet`
tag reads `Undecided`; `guarded` with no keys, `unguarded` with keys, two
`wallet` tags, and `["wallet", "maybe"]` each read null; and a certificate with a
policy still `certifies`, so the policy cannot break lineage.

One commit. Nothing produces the tag yet.

---

## Phase 2 — the ceremony carries the choice

The coordinator chooses before the ceremony, but the certificate is proposed
after it, possibly by somebody else — any parent admin holding a share can resume
the flow, which is why every step reads stored rows. So the choice travels the
way the subgroup's name already does: on the ceremony proposal, onto
`DkgSession`.

- `DkgRitualEvents`: write and parse a `wallet` tag on a ceremony proposal
  (`WalletPolicyTag` reused), beside `parseSubject` and `parseParentChatRoomId`.
- `ChillDkgRitualManager.proposeRitual` — both overloads, at `:197` and `:1445` —
  take `walletPolicy: SubgroupWalletPolicy?`, required non-null when
  `parentChatRoomId` is set.
- `acceptProposal` (`:412`) files it on the session, and drops a proposal that
  carries a policy but no parent: a policy for a group nobody is the parent of is
  a bug, and the ceremony is the cheapest place to refuse it.
- **Schema 28 → 29**: `DkgSession.walletPolicy: String?`. Null for every
  ceremony before this and for every non-subgroup ceremony; nothing reads it but
  the certificate rung. A migration adding a nullable column, with the migration
  test the other bumps have.

**Tests**: a proposal round-trips its policy onto the session; a subgroup
proposal from an older build (no tag) files null; a parentless proposal carrying
a policy is dropped.

---

## Phase 3 — the picker, and the join gate

### `SelectSubgroupAdminsScreen`

A *Wallet* section under the threshold stepper, two radio rows in one
`selectableGroup`, *guarded* selected by default:

| choice | headline | supporting line |
|---|---|---|
| guarded | Guarded wallet | This group and the `N` above it can spend its coins once they are 12 blocks old |
| unguarded | Unguarded wallet | Only the subgroup can spend from its wallet. Groups above it cannot reach it or anything beneath it |

Since [guardian-delay.md](./guardian-delay.md), the guarded row no longer quotes
12: under it the coordinator chooses the delay on this group's own leaf, from 6 to
65,535 blocks, and each group above keeps the delay it chose.

`N` is the count of the parent's own guardians (`SubgroupManager.guardiansOf`),
so the line names the reach the parent is actually granting rather than "the
groups above". The second sentence of the unguarded line is D2's cost; drop it if
D2 goes the other way.

### The parent's own policy, read once

The picker is the first thing that needs to know the parent's policy, so this
phase adds the one reader the later phases reuse:

```kotlin
/** What [chatRoomId]'s own verified certificate says of its wallet; null for a room with none. */
suspend fun walletPolicyOf(database: DedunDatabase, chatRoomId: String): WalletPolicyReading?
```

It goes on `SubgroupManager` beside `guardiansOf`, and like it reads the
certificate off the verified key state. Phase 4's signer check and Phase 6's
`GroupWalletManager.walletPolicyFor` both call it, so the picker, the signers and
the wallet screen cannot disagree about what a group is.

`SelectSubgroupAdminsUIState.Loaded` carries the result as
`parentPolicy: WalletPolicyReading?`, read once in `initiate()` beside the
parent room.

### A guarded parent's picker

When `parentPolicy` is `Decided(GUARDED)`, D4 leaves one choice. The section
still shows both rows rather than hiding the one that cannot be picked, because
the reason is something the coordinator needs to know about their own group:

- *guarded* is selected and cannot be changed;
- *unguarded* is shown disabled, with its supporting line replaced by the reason:
  *This group is guarded, so its subgroups are too*.

Hiding the row would make the picker look the same as an older build's, where
nobody chose anything. Showing it disabled, with the reason, says that a decision
was made and who made it.

Rules, each one the picker's because the ceremony fixes everything after it:

- the choice goes into `SelectSubgroupAdminsViewModel` as
  `walletPolicy: MutableState<SubgroupWalletPolicy>`, and `confirm` passes it to
  `proposeRitual`;
- `setWalletPolicy(UNGUARDED)` does nothing under a guarded parent. The state is
  never able to hold a choice the screen would show as disabled;
- `prefillFromRetry` (`:123`) restores it from the failed session, as it already
  restores the name and threshold. It is coerced to *guarded* under a guarded
  parent, the way the threshold is coerced into `quorumRange()`. The failed
  session can predate the parent's own certificate, so the restored value can
  disagree with what the parent now is;
- `refusal()` refuses *unguarded* under a guarded parent, with the same reason the
  row shows. `confirm` runs `refusal()` again, so a state that got past the
  screen is still stopped before a ceremony is proposed;
- **guarded is refused when the parent cannot grant**: `grantFor` returns null
  when the parent's own certificate carries keys this build cannot read, and
  today that is discovered at the certificate step, after a full ceremony. Add it
  to `refusal()` and disable the row with the reason. It cannot coincide with a
  guarded parent: Phase 1 reads a `guarded` policy with unreadable keys as null,
  and Phase 5 drops the key state that would carry it, so a parent whose policy
  reads `Decided(GUARDED)` always has keys this build can read;
- guarded is offered while `GroupWalletRollout.offersGuardiansOn` is true for
  some network, which it is for all of them today. If the mainnet gate is ever
  shut again, say on the row that a guarded subgroup will have no wallet on
  mainnet until it opens — the policy is network-agnostic and the gate is not.

CLAUDE.md applies in full: strings in `strings.xml` in sentence case with `%1$d`
for `N`, spacing from `MaterialTheme.spacing`, colour from roles, the rows
`minimumInteractiveComponentSize`, `Decorative` on any icon beside a label.

### `DkgJoinApprovalScreen`

One line under the threshold line the screen already shows: *Its wallet will be
guarded by the group above it and N more* or *Its wallet will not be guarded*.
Read off the session's `walletPolicy`; a ceremony with none shows nothing. This
is the child admins' consent, and it is the only place they see the choice
before their device is committed to a ceremony with it.

**Tests**:
- `SelectSubgroupAdminsViewModel` passes the chosen policy to `proposeRitual`;
- a retry restores the policy;
- guarded is refused with a reason for a parent whose certificate carries
  unreadable keys;
- under a guarded parent:
  - the state starts at *guarded* and `setWalletPolicy(UNGUARDED)` leaves it
    there;
  - a retry of an unguarded session restores as *guarded*;
  - `refusal()` names the reason if the state is forced to *unguarded*;
- under an unguarded parent, an undecided parent and a root group, both choices
  are open.

---

## Phase 4 — the certificate carries the policy

### Proposing

`SubgroupManager.proposeBirthCertificate` takes `walletPolicy:
SubgroupWalletPolicy?`, and `DkgRitualViewModel.proposeBirthCertificate` (`:370`)
passes `session.walletPolicy` — off the session, not a route, for the reason the
name is: whoever resumes has no route.

| session's policy | parent's own policy | guardian tags | `wallet` tag |
|---|---|---|---|
| `GUARDED` | any | `grantFor(...)`, as today | `guarded` |
| `UNGUARDED` | not guarded | none | `unguarded` |
| `UNGUARDED` | `Decided(GUARDED)` | refused, nothing proposed | — |
| null (an older build's ceremony) | not guarded | `grantFor(...)`, as today | none — the child decides, as today |
| null (an older build's ceremony) | `Decided(GUARDED)` | `grantFor(...)` | `guarded` |

The two rows with a guarded parent are D4 at the proposer.

**An unguarded session under a guarded parent is refused with a reason.**
`proposeBirthCertificate` reads `SubgroupManager.walletPolicyOf` for the parent
and throws *this group is guarded, so its subgroups are too*. Every signer would
refuse the certificate anyway, so proposing it would only cost a quorum's
attention. The picker stops this in Phase 3, so it is reached only by:
- a picker that was skipped or had a bug;
- a coordinator whose device held no verified key state for the parent when the
  picker opened. `walletPolicyOf` then read null, and the picker treated the
  parent as a root group. The certificate is proposed later, possibly from
  another device that does hold the parent's key state.

The subgroup is stranded either way: its ceremony has fixed `unguarded`, and
Phase 5's child signers will refuse any certificate that says otherwise. The
error message tells the coordinator to start the subgroup again. It does not
offer to certify the subgroup as guarded instead, because the subgroup's admins
joined on the understanding that it would be unguarded.

**An older build's ceremony under a guarded parent is certified as guarded.**
That ceremony carried no policy, so none of its admins was shown one. *Guarded*
is the only certificate the parent's signers will accept. Certifying it as
undecided would hand the child the plain button that D4 exists to remove, and
Phase 5's child check does not apply to a session with no policy, so nothing
downstream would stop it.

### Every parent signer checks it

`SubgroupManager.refuseGrant` becomes `refuseCertificate` and grows the policy,
called where it is today (`FrostSigningManager.kt:567`), before anything is
shown. The signing group is `rebuilt.pubKey`, the parent, and its own policy
comes from `walletPolicyOf` on this device's own verified key state, never from
the proposal:

- the policy reading is null → *carries a wallet policy that does not read*;
- the signing group's own policy is `Decided(GUARDED)` and the certificate's
  reading is anything but `Decided(GUARDED)` → *would make an unguarded
  subgroup of a guarded group*. This includes `Undecided`, so an older
  proposer's certificate with no `wallet` tag is refused under a guarded parent.
  That is deliberate: an undecided child gets the plain button, which is the
  thing D4 forbids. This check runs first, before the key check below;
- `GUARDED` → the keys must equal `grantFor`, exactly today's check;
- `UNGUARDED` → no keys (Phase 1's parse already says so; restated here so the
  refusal names it);
- `Undecided`, under a parent that is not guarded → today's check, unchanged, so
  certificates from older proposers are still signable.

**Where this check reaches, and where it does not.** It is binding on every
signer running a build that has it. A parent signer on an older build does not
read the tag and signs whatever its key check allows. A guarded group should
therefore not make subgroups until every member of it who can sign is on a build
from Phase 4 onward. It is the mixed-version risk again, placed one level up.

### What the parent's admins read

`ProposedEvent`'s certificate summary (`text/ProposedEvent.kt:50`) states the
policy in both directions. The guarded line exists — *this group and the one
above it can spend from its wallet* — and gains *, and it cannot open a wallet
without that*; the unguarded case gains its own line, *its wallet will not be
guarded*. An absent line is not a decision anybody can be said to have read, so
the unguarded certificate must say so rather than say nothing.

**Tests**:
- each row of the proposing table, including the throw for an unguarded session
  under a guarded parent;
- a guarded certificate with a swapped key, an unguarded certificate with a key,
  and an unreadable policy are each refused;
- an undecided certificate from an older proposer is still accepted under an
  undecided, unguarded or root parent;
- under a guarded parent, signers refuse:
  - an unguarded certificate;
  - an undecided one.

  The second case is the one a reviewer will want to loosen, and the test is
  there to fail if anyone does;
- the parent's own policy is read from the signer's key state. A proposal from a
  coordinator whose device saw no parent certificate is still refused by a
  signer whose device has one.

---

## Phase 5 — the child agrees, and the key state keeps it

### Child signers refuse a swapped policy

The child's key state is signed in the parent's room by the child's quorum, and
it carries the certificate. Beside the certificate check in
`FrostSigningManager`'s proposal path, add one for a `GroupKeyStateEvent`
proposal: when this device's `DkgSession` for the ceremony has a `walletPolicy`,
the carried certificate's policy must be `Decided` with the same value. A
mismatch drops the proposal with *carries a certificate whose wallet policy is
not the one this group was made under*.

This is the step-4 consent from [who agrees to what](#who-agrees-to-what), and
it is what makes the picker's choice binding on whoever proposes the certificate.

### The verified key state

`GroupKeyStateManager.parentageOf` drops a state whose certificate's policy
reads null, in the same way it drops one whose parent tags disagree. The
subgroups note learned this the hard way with `claimsParentage`: a tag present
and unreadable must never look like a tag absent, or a corrupt policy reads as
*undecided* and hands the child both buttons.

**Tests**: a key state whose certificate says `unguarded` under a ceremony that
announced `guarded` is refused by a child signer; a key state carrying an
unreadable policy is dropped by `stateFrom`; one carrying a certificate with no
policy is still adopted.

---

## Phase 6 — the child's wallet screen follows it

`GroupWalletManager` grows the one place a room's policy is read:

```kotlin
/** What [chatRoomId]'s verified certificate allows it to open. */
suspend fun walletPolicyFor(database: DedunDatabase, chatRoomId: String): WalletPolicyReading?
```

This delegates to Phase 3's `SubgroupManager.walletPolicyOf`, which reads the
same verified key state `guardedAccountFor` reads. It is null for a room with no
certificate, a root group (D5), which opens plain as today.

`GroupWalletUIState.NotOpened` gains the reading, and
`GroupWalletViewModel.initiate` (`:87`) fills `account` and `guarded` from it:

| policy | `account` (plain) | `guarded` |
|---|---|---|
| `GUARDED` | null | the guarded opening |
| `UNGUARDED` | the plain account | null |
| `Undecided` | the plain account | the guarded opening — today |
| none (root) | the plain account | null |

`OpenWalletAction` (`GroupWalletScreen.kt:340`) then needs only one new branch:
with a decided policy it shows one button and a line naming who decided —
*The group above this one decided its wallet is guarded*, or *…is not guarded*.
The comment at `:355` that calls being guarded "the group's own choice" is
rewritten for the undecided case only, which is now the only case it describes.

`openWallet(guarded = false)` returns early when `account` is null, as
`openWallet(guarded = true)` already does when `guarded` is — no new guard, only
the state no longer offering what the policy forbids.

**Tests**, `GroupWalletViewModelJvmTest`: each row of the table above, the
existing `guardedRepository` fixture extended with a policy tag.

---

## Phase 7 — every member enforces it

The screen only stops this device from proposing. This phase stops the room
accepting.

### Signing

`FrostSigningManager`'s proposal path, beside the certificate check: a
`GroupWalletStatementEvent` proposal in a room whose policy is decided is refused
when its guardedness contradicts it — *opens an unguarded wallet, and this
group's parent decided it is guarded*, and the converse. Refused before it is
shown, for the reason a mismatched id is: a member should not be asked to approve
something their device already knows the room has ruled out.

### Reading

`GroupWallet.newestAmong` takes the policy, and before its two existing rules
drops every statement that contradicts it. `GroupWalletManager.walletFor` and
the other callers of `newestAmong` pass `walletPolicyFor(...)`.

Under `GUARDED` this is stronger than today's rule 1. Rule 1 only lets a guarded
statement outrank a plain one; this makes a plain statement nothing at all, even
when no guarded one exists. So a plain statement signed by older clients in a
guarded-only child opens no wallet on any new device — no addresses derived, no
coins watched, and every spend from it refused at `GroupSpend.check`'s caller
because `walletFor` returns null. Under `UNGUARDED` (with D2) nothing new is
needed: a guarded statement cannot verify against a certificate with no keys.

### Ancestors

`GuardianManager.guardedFrom` already refuses a statement whose certificate does
not grant its guardians. Add the policy: an ancestor ignores a forwarded
statement whose own certificate contradicts it. It cannot arise from an honest
child, and it costs one line to stop a modified one from showing up in the
ancestor's *Guarded wallets* list.

**Tests**, jvmTest in the shape of `GroupWalletViewModelJvmTest` and
`GuardianSigningJvmTest`: a plain statement proposed in a guarded-only room is
refused by every signer; a plain statement already stored in a guarded-only room
opens no wallet and a spend proposed against it is refused; an undecided room
behaves exactly as today, both rules included.

---

## Phase 8 — showing the decision from above

Small, and the reason the parent's members can see what they decided:

- `ChatRoomDetailScreen`'s subgroup rows (`:260`, `:317`) add *guarded* or
  *unguarded* to their supporting line, read off the certificate. It is a fact
  about the founding, like the name and roster, and is rendered as one.
- The child's parent row says the same, read off the verified key state.

---

## Phase 9 — docs, and checking it on the emulators

- [group-wallet-guardians.md](./group-wallet-guardians.md): rewrite *What this
  reverses, and why it is still the child's choice*, now *whose choice it is* — the parent now decides, the
  child agrees by joining, and the paragraph saying "the parent cannot impose
  this" keeps its cryptographic half and loses its policy half. Its *What this
  does not do* gains a pointer here.
- [subgroups.md](./subgroups.md): the certificate's tag list gains `wallet`, and
  the consent section's paragraph on the threshold gains the policy as the second
  value the coordinator picks for other people.
- [README.md](./README.md): this note's row and a sentence on reading order.
- This note: *Built*, and a table of where the build chose differently.

By hand, regtest, three emulators. The root makes two subgroups: a **guarded**
one, `M`, and an **unguarded** one, `U`. Then:

1. `M`'s join screen and the root's certificate proposal both say *guarded*.
   `U`'s both say *unguarded*.
2. `M`'s wallet screen offers one button, guarded. `U`'s offers one, plain.
3. **D4, at the picker.** `M`'s *add subgroup* picker shows *guarded* selected
   and *unguarded* disabled with its reason. `M` makes a child `C`. `C`'s
   certificate carries `M`'s key and the root's key one step further, and the
   root's *Guarded wallets* list shows both `M` and `C`, the latter once
   forwarded.
4. **D4, at the signers.** From `M`, propose an unguarded certificate directly.
   This needs a debug build or a test hook, since the picker will not do it.
   Every `M` signer's device drops it, and the reason is in the log.
5. **No downward rule under an unguarded parent.** `U`'s picker offers both
   choices. `U` makes a guarded child, whose certificate carries only `U`'s key
   (D2). The root's list does not show it.
6. A legacy subgroup made on the previous build still offers both buttons, and
   so does its own picker.

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 1 | `nostr/subgroup/` and its test | dark — nothing writes the tag |
| 2 | DKG proposal tags, `DkgSession`, schema 29 | dark — nothing sets the policy |
| 3 | picker and join gate | **the switch**: the first phase a user can reach |
| 4 | certificate proposal and parent signer check | with 3 |
| 5 | key state, child signer check | with 3 |
| 6 | the child's wallet screen | with 3 |
| 7 | signing and reading statements | before or with 3 |
| 8 | detail screens | any time after 4 |
| 9 | docs and the emulator run | last |

Phases 1, 2 and 7 can land before anybody can choose anything, and 7 should:
it is the one that makes a decided policy binding on a device that did not draw
the screen, so it wants to be on every member's phone before the first decided
certificate exists. Phases 3-6 go out together — a picker whose choice the
certificate did not carry would be a choice that meant nothing.

---

## What this does not do

**It does not make the policy cryptographic.** The child's key is the child's; a
quorum of modified clients can sign a plain statement in a guarded-only room, or
pay its coins anywhere. Every honest client refuses to take part, and that is the
whole of the guarantee. The UI says *decided*, never *cannot*.

**It does not close the mixed-version risk for guarded-only children.** A child
member on a build older than Phase 7 sees keys and no tag it reads, offers both
buttons, and will sign a plain statement. New devices ignore that wallet
entirely, so coins paid to its addresses are watched only by the old ones. That
is today's risk in a new place, and the remedy is the same: everybody in a
guarded subgroup on a build that reads the policy. Unguarded children (D2) do not
have it.

**It does not touch subgroups that already exist.** Their certificates read
*undecided* and keep both buttons. A fresh certificate cannot change that: the
key state carries the certificate the child was born with (D3).

**It does not reach back into existing trees.** D4 binds only groups whose own
certificate is decided *guarded*, and none exists until this ships. A subtree
made before this keeps undecided groups, and an undecided group may still make
an unguarded child, which ends its ancestors' reach (D2). A root that wants its
whole tree guarded has to start new subgroups.

**It does not let a group step out of a guarded subtree.** That is D4 working as
intended, and it is permanent: a guarded group's subgroups are guarded, theirs
are too, and no quorum below the first guarded certificate can change that. A
group that needs an unguarded wallet has to be made from somewhere outside that
subtree.

**It does not give the parent a say over accounts.** A policy covers every
network and every account of the child's wallet; there is no "account 0
guarded, account 1 not".

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| the policy only on the ceremony proposal | the parent's quorum never signs it, so it is the coordinator's word; and a member welcomed later, who holds no ceremony, cannot read it. The certificate is the only artefact that is both parent-signed and carried on every guarded statement |
| a separate parent-signed policy event | a second thing to disagree with the certificate, and one the statement would have to carry beside it |
| *unguarded* as keys present and a tag saying not to use them | D2's alternative: keeps the root's reach below the child, at the price of a new rule in `verifiedAgainst` and old clients offering a wallet the parent forbade |
| enforcing it in the address | there is no script that stops a key-path spend, and no covenant on mainnet to restrict where coins go |
| a separate policy *for descendants*, signed beside the child's own | D4 reads the constraint off the parent's own policy instead. A second tag would let a group be guarded while allowing unguarded children, or the reverse. That is two values to keep consistent, for a case nobody has asked for |
| treating an undecided parent with guardian keys as guarded | it would decide for a legacy group something its own ancestors left to it, and that group may already have opened a plain wallet |
| no downward rule: a guarded group may make an unguarded child | the first draft of D4. It lets any middle group cut the root out of a branch, which defeats the point of the root guarding the middle group |
