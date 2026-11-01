# Forwarding guarded wallets upward, without anybody pressing a button

A guarded wallet carries a leaf for every group above it, but a group above can
use its leaf only once it has been *told* about the wallet. Today that telling
is a button. A member who sits in both rooms opens the lower group's wallet
screen, finds *Tell the groups above*, and taps *Share* for each wallet. One tap
carries one wallet one generation up. Nobody is prompted to do it. A root whose
grandchildren's wallets nobody shared has a leaf in every address and no way to
find a single coin.

This plan makes the telling automatic. **Whenever a device files a guarded
wallet it believes -- a subgroup's own guarded statement, a member wallet
certificate its group signed, or a notice forwarded to it from below -- and it
sits in a group above that has not been told, it tells that group. The same
check runs again each time a group's room or wallet screen is opened, which
catches up on everything filed before this existed or missed while offline.**
The button stays, for the case a notice was lost on the wire.

Read [group-wallet-guardians.md](./group-wallet-guardians.md), *How an ancestor
finds the coins*, and [member-guarded-wallets.md](./member-guarded-wallets.md),
Phase 10, first. This plan changes no format: the notice is the 30333
`GuardedWalletNoticeEvent` it has always been, and everything an ancestor checks
before believing one is unchanged. What changes is who sends it, when, and to
which groups.

**Built**, phases 1-7, one commit each, written against the code as it stood at
`263a25a7`, schema v30; the build changes no schema and no format.
`GuardianManager.believable`, `upwardOf`, `forward`, `forwardOnArrival` and
`forwardAll` are the entry points, and `GroupWalletRollout.FORWARDS_GUARDED_WALLETS`
is the switch. The phases below are kept as written; where the build chose
differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 1's tests are in commonTest | jvmTest, `GuardedWalletForwardingRulesJvmTest`: `believable` is tested against certificates a real quorum signed, and FROST runs on the JVM |
| Phase 2: `forward` writes the forwarder's own line | `share` writes it, so the button writes it too -- the forwarder never seeing its own line was the button's gap as much as the automation's (Phase 5) |
| Phase 3: the notice arm forwards after `describeNotice` believes it | before, and whether or not it does. `describeNotice` needs this room's key to make the ancestor's walk; forwarding needs only `believable`, so a device in a middle group that holds no key state for it still carries a notice on up |
| Phase 3: a test with the switch off | not written: the switch is a `const`, and a test cannot turn it. Each trigger reads it in one line, `forwardOnArrival` and `forwardAll` |
| Phase 3's notice test arrives through the inbound path | through `GuardianManager.forwardOnArrival`, which the inbound path calls: the 30333 arm sits inside MLS decryption, which a jvmTest does not drive. The certificate and statement arms are driven through `ChatMessage.applyInnerEvent` itself |
| Phase 4: `ChatRepository.forwardGuardedWallets` | with a default body, so every fake repository in the tests needed no change |
| Phase 5: nothing else changes | two older tests described a notice under an id other than the one they filed. Under the one-line rule that is a second notice, and silent; they now describe the notice they filed |
| Phase 6: each row's supporting line explains the fallback | one line under the section's heading says it, and each row keeps *Not yet shared with the groups above that you're in*: the same sentence on every row was the same sentence N times |
| Phase 7: the three-device run | not made |

Still to do by hand: Phase 7's run on signet.

---

## What is there, and the two things wrong with it

`GuardianManager.shareableFrom(chatRoomId, chain)` lists what a device could
tell from a room. That is the room's own guarded wallet, if it has one, plus
every guarded wallet the room holds: those it was told about, and those of its
members it signed itself. Each comes with the guarding rooms this device is in
and has not told yet (`targetsFor`). `GuardianManager.share` posts one notice
per room. `GroupWalletViewModel.share` is the only caller, behind the button.

Read as the basis for doing this without a person, two of its rules are wrong.

**1. It tells groups below as well as above.** `targetsFor` takes every room
the statement's guardian list names, except the room it is in. The list names
*all* the wallet's guardians, nearest first. So from a middle group `M`, the
targets of a grandchild `C`'s statement include the root `R`, which is right,
and nothing below `M`. But from the root `R`, holding the same statement, the
targets include `M`, which already holds it. Each tap there tells a group
something it was told first. By hand that is a wasted tap. Automatically it is
a notice into `M` from every device in both rooms, every time `R` is opened.

**2. A guarding group's own certificate never counts as told.** `alreadyTold`
reads the target room's 30333 notice rows. A member wallet certificate is held
in the group that signed it as a `GroupSignedEvent` and never as a notice. So
`R` holding a member's wallet that `G` signed would tell `G` again, for the
same reason as above.

Both are fixed by one rule: **a statement is told upward only**, to the guardian
rooms after the room it is in. A room that holds the statement in any form,
signed or as a notice, has been told.

---

## Phase 0 — decisions, before any code

### D1. Which statements are forwarded

**Recommended: exactly what `shareableFrom` lists today**, from a room this
device is in:

| statement | held in the room as | forwarded to |
|---|---|---|
| the room's own guarded wallet statement (30331) | a `GroupSignedEvent` the room's quorum signed | every guardian it names |
| a member wallet certificate (30334) the room signed | a `GroupSignedEvent` | the guardians after the room, which is guardian 0 |
| a statement or certificate a notice carried here (30333) | a `MarmotInnerEvent` row | the guardians after the room in the statement's list |

A plain (unguarded) wallet statement is never forwarded: it names nobody.

### D2. What the forwarder believes first

The forwarder vouches for nothing. Every device in the target room runs
`GuardianManager.verify` on what arrives, including the walk down from its own
guardian root. But the forwarder should not pass on what it cannot believe
itself, or a single bad notice is copied into every group above.

**Recommended: everything `verify` checks except the ancestor's own walk**,
which only a device holding that ancestor's key can make:

- a 30331 statement: signed by its room, guarded, carrying a certificate whose
  key makes that room, and with every value rederiving from it
  (`Reading.verifiedAgainst`);
- a 30334 certificate: `MemberWalletCertificateEvent.verify(certificate, certificate.pubKey)`.

This is `GuardianManager.believable(statement)`, split out of `verify` so the
two cannot drift. `verify` becomes `believable` plus the walk.

### D3. Which groups count as "above"

**Decided by the bug above: upward only.** In room `X`, a statement's targets are
the guardians listed *after* `X`'s own position in its guardian list, nearest
first. A room not in the list at all is the wallet's own room (a subgroup's own
statement), and gets every guardian. For a member's wallet signed by `G`, `G` is
guardian 0, so the targets start at `G`'s parent.

A target counts as told when it holds the statement in any form: a 30333 row
carrying it, or a `GroupSignedEvent` with its id.

### D4. Who sends it, when several devices could

Every device in both the lower group and the upper one could send the notice.
When a member wallet certificate completes, every device in `G` files it within
seconds of the others. Each of them that is also in `P` would then post into `P`
at once. A burst like that is the shape [mls-skipped-keys.md](./mls-skipped-keys.md)
warns of: one of two group events arriving back to back can be dropped for good.

**Recommended: one sender is elected for the immediate send, and every device is a
fallback for the catch-up.**

- The **elected** device is the one whose profile's key sorts lowest among the
  members of *both* rooms, as this device's participant lists have them. It
  forwards on arrival.
- Every **other** device forwards only in the catch-up pass (D5). By then the
  elected device's notice has usually arrived, and the target counts as told.
- Devices disagreeing about the participant lists can elect two senders. The
  cost is one duplicate notice, which D6 keeps out of the transcript.

The alternative, everybody sending at once, is simpler and correct, since
`guardedWalletsOf` already collapses duplicates. It is rejected for the burst,
not for the duplicates.

### D5. When the catch-up runs

**Recommended: when a Marmot room's chat is opened, and when a group's wallet
screen is opened**, for that room. That is `ChatMessageListViewModel.initiate`,
beside `askForGroupHistory`, and `GroupWalletViewModel.initiate`, which already
reads `shareableGuardedWallets`.

That covers:
- certificates and statements signed before this ships;
- a device that was offline when the elected one should have sent;
- an elected device that never sent.

A pass over every room at launch would cover more, and would put a notice burst
on every room at once, on every launch. It is rejected for now and named under
*what this does not do*.

### D6. What the transcript says

Today each notice writes a *Shared @alice's guarded wallet with this group*
line, attributed to the member who forwarded it. The forwarder never sees its
own line: a sender is not handed its own MLS message back.

**Recommended:**
- a line only for the **first** notice of a statement in a room. A notice whose
  statement this room already holds, in any form, writes none;
- the forwarding device writes the same line into the target room on its own
  side as it sends, so it sees what it did, as the button's snackbar did.

### D7. Which profile sends

One database holds every profile on the device. A room row's `userPublicKey` is
the profile that is in it.

**Recommended: the notice is sent as the target room's own profile, and only when
that is the source room's profile too.** The arrival path that files a 30334 or
a 30331 (`ChatMessage.applyInnerEvent`) has no active key to hand. The room row
has it, and a device must never post into a room as a profile that is not the
one in it.

### D8. A way to turn it off

**Recommended: `GroupWalletRollout.FORWARDS_GUARDED_WALLETS = true`**, read by the
arrival triggers and the catch-up, not by the button. It is a behaviour that
sends messages without a person, so it gets one switch, in the same place as the
other wallet gates, for the release where it first ships.

---

## Phase 1 — the rules, pure

New in `GuardianManager` (or a small `GuardianForwarding` beside it, if it reads
better):

```kotlin
/** [statement] believed on its own merits: everything [verify] checks except the ancestor's own walk. D2. */
fun believable(statement: Event): Believable?   // the guardians, the descendant id, the chain

/** The guardian rooms [statement] is forwarded to from [chatRoomId]: those after it in the list, nearest first. D3. */
fun upwardOf(guardians: List<GuardianDerivation.Guardian>, chatRoomId: String): List<String>

/** Who sends at once, of [candidates] -- the members of both rooms: the lowest key. D4. */
fun electedSender(candidates: Set<HexKey>): HexKey?
```

`verify` is rewritten as `believable(statement)` plus the walk, with its kdoc's
four rules unchanged.

**Tests**, commonTest:
- `upwardOf` for a grandchild's statement: from the grandchild's own room, both
  guardians; from the middle group, the root alone; from the root, nothing;
- `upwardOf` for a member wallet from its guarding group, two levels up: the
  parent and the root;
- `believable` accepts what `verify` accepts and refuses each mutation
  `verify`'s tests refuse, apart from the walk;
- `electedSender` is the lowest key and is null for an empty set.

---

## Phase 2 — telling, as a function a trigger can call

```kotlin
/**
 * Tells every group above [chatRoomId] that this device's profile is in, and that has
 * not been told, about [statement]. [whenElected] restricts it to the targets this
 * device is the elected sender for (D4); the catch-up passes false.
 * Returns the rooms told.
 */
suspend fun forward(database: DedunDatabase, statement: Event, chatRoomId: String, whenElected: Boolean): List<LocalChatRoom>
```

1. `believable(statement)`, or nothing;
2. targets: `upwardOf(guardians, chatRoomId)` → rooms on this device that are
   Marmot rooms, belong to the source room's profile (D7), and have not been told
   in either form (D3);
3. with `whenElected`, keep a target only if this profile is
   `electedSender(participants(source) ∩ participants(target))`;
4. `share` into each, as that profile, and write the forwarder's own line (D6).

`shareableFrom` and `targetsFor` move onto the same upward rule and the same
*told* check, so the button and the automation cannot disagree about what is
left to share. That fixes both bugs above for the button too.

**Tests**, jvmTest, in the shape of `MemberWalletGuardianJvmTest`:
- a member wallet certificate in `G`, this device in `G` and `P`: `forward`
  tells `P` and not `G`;
- the same from `P` holding the forwarded certificate: tells `R`, not `G`;
- a target holding the statement as a `GroupSignedEvent` counts as told;
- a target belonging to another profile on the device is never told;
- `whenElected` with a lower-keyed member in both rooms tells nobody, and
  without it tells `P`;
- a statement that is not `believable` is forwarded nowhere.

---

## Phase 3 — the triggers on arrival

Three places, each after the statement has been believed and filed, so the
row the *told* check reads already exists:

| arrival | where | forwards |
|---|---|---|
| a member wallet certificate is filed in `G` | `ChatMessage.applyInnerEvent`'s 30334 arm, reached from `FrostSigningManager.applySignedEvent` on every device that followed the session | the certificate, from `G`, `whenElected = true` |
| a guarded wallet statement is filed in a subgroup | the 30331 arm, when `wallet.account.isGuarded` | the statement, from the subgroup's room |
| a notice arrives in a group | the 30333 intercept in `ChatMessage`'s inbound path, after `describeNotice` believes it | the statement inside, from this group, onward |

Each call is wrapped so a failure to forward is logged and never stops the line
being written. Forwarding is an extra, and the arrival is the thing.

All three read `GroupWalletRollout.FORWARDS_GUARDED_WALLETS` (D8).

**Tests**, jvmTest: a three-level hierarchy on one device -- root `R`, middle
`M`, grandchild `C`, this device a member of all three and the elected sender
throughout:
- `C`'s guarded statement filed in `C` ends with notices in `M` and in `R`, with
  nobody calling `share`;
- a member wallet certificate filed in `C` reaches `M` and `R` the same way;
- the notice `M` receives does not travel back down into `C`;
- with the switch off, nothing is sent.

---

## Phase 4 — the catch-up

`GuardianManager.forwardAll(database, chatRoomId)`: every statement
`shareableFrom` would list from the room, on every chain, through `forward` with
`whenElected = false`. Called from:

- `ChatMessageListViewModel.initiate`, beside `askForGroupHistory`, for a Marmot
  room;
- `GroupWalletViewModel.initiate`, before `shareableGuardedWallets` is read, so
  the *Tell the groups above* section shows only what is truly left.

Both go through `ChatRepository.forwardGuardedWallets(chatRoomId)`, so the view
models still talk to repositories.

**Tests**: a certificate signed before this existed -- filed with no trigger --
is forwarded the first time its group's room is opened, and not again the second
time. A non-elected device forwards in the catch-up what the elected one never
sent.

---

## Phase 5 — the transcript

- The 30333 intercept writes its line only when no other stored notice in the
  room carries the same statement id, and the room does not hold the statement as
  a `GroupSignedEvent`. A duplicate is filed and silent.
- `forward` writes the forwarder's own line into each room it tells, typed
  `TYPE_GUARDED_WALLET_SHARED`, `isUserMessage = true`, with the words the
  receivers' devices write.

**Tests**: two notices for one statement from two forwarders give one line; the
forwarder's own device shows its line in the room it told.

---

## Phase 6 — the screen

The *Tell the groups above* section on `GroupWalletScreen` stays, now as the
fallback, and lists only what the catch-up could not send. In practice that
means a notice lost on the wire, or a group above in which this device holds no
row. Its supporting line becomes *This device shares these on its own when it
can. Share now if a group above has not heard*. The strings go in
`strings.xml`, in sentence case.

The snackbar the button shows today stays as it is.

---

## Phase 7 — docs, and checking it on the emulators

- [group-wallet-guardians.md](./group-wallet-guardians.md), *How an ancestor
  finds the coins*: forwarding is automatic, one generation per device in both
  rooms, with the button as a fallback.
- [member-guarded-wallets.md](./member-guarded-wallets.md), Phase 10: a member's
  wallet reaches the groups above without anybody sharing it.
- [README.md](./README.md): this note's row.
- This note: *Built*, and a table of where the build chose differently.

By hand, signet, three devices. `R` is the root, `M` its guarded subgroup, `G`
`M`'s guarded subgroup, and Alice is in `G`. Device 1 is in `G` and `M`, device
2 in `M` and `R`.

1. Alice asks `G` to guard a wallet, and `G` signs. With nobody touching
   device 1, `M`'s wallet screen lists `@alice` within a minute.
2. With nobody touching device 2, `R`'s does too, and `R`'s transcript has one
   *Shared @alice's guarded wallet* line.
3. `G`'s transcript has no line about its own member's wallet coming back down.
4. Device 2 offline while `G` signs a second wallet: it appears in `R` the first
   time device 2 opens `M`.
5. `R` makes a guardian spend from Alice's wallet through its leaf.

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
| 1 | `GuardianManager`, pure | dark |
| 2 | `forward`, and the button's rules | **fixes both bugs in the button** |
| 3 | three arrival arms | **the switch**: nothing sends without a person before this |
| 4 | two view models, `ChatRepository` | with 3 |
| 5 | the 30333 intercept, `forward`'s own line | with 3 |
| 6 | `GroupWalletScreen` | any time after 4 |
| 7 | docs and the run | last |

Phase 2 is worth landing alone. It is a fix to the button that stands without the
automation.

---

## What this does not do

**It does not reach a group nobody sits in.** Forwarding needs a device in both
rooms, one generation at a time. A group above that shares no member with the
group below still cannot be told, and the remedy is the one the guardians note
gives: a member in common.

**It does not tell a member welcomed into a group above after the notice.**
Notices are not chroniclable, so a device that joins `R` later holds none of the
notices `R` was sent, and lists none of the wallets. The catch-up does not help
it: the forwarder's device already holds its own copy of the notice and counts
`R` as told. Telling late joiners is the chronicle's job, and this plan does not
take it on.

**It does not sweep every room at launch.** The catch-up runs per room as the
room or its wallet screen is opened (D5). A device that never opens the lower
group never forwards in the catch-up, though it still forwards on arrival when
it is the elected sender.

**It does not make a notice reliable on the wire.** A notice lost to the skipped-keys
bug stays lost until a catch-up on another device sends one again, or somebody
taps *Share*.

**It does not change what a group above can do with what it is told.** It finds
the wallet, watches it and can spend through its leaf after the delay. All of
that is unchanged; only the finding no longer waits on a tap.

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| every device sends at once | correct, since duplicates collapse, but it puts a burst of group events into every group above at the moment a certificate completes, the one shape `mls-skipped-keys.md` says loses messages (D4) |
| the member's own device forwards their wallet | the member is in `G` and seldom in the groups above, and a subgroup's statement has no single owner; the device in both rooms is the one that can |
| a pass over every room at launch | catches the most, and sends the most at once, on every launch (D5) |
| removing the button | a notice lost on the wire has no other way back until somebody opens the right room on the right device |
| a new kind for an automatic notice | the 30333 notice already says everything, and every build that reads it today reads these |
