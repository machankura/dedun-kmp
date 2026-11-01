# The group decides whether it guards members' wallets, and at what delay

A member's guarded wallet carries one leaf for the group that guards it and one
for every group above it. Since [guardian-delay.md](./guardian-delay.md), each
ancestor's leaf keeps the delay that ancestor chose when it made the group below
it. The group's **own** leaf, though, is always `GuardianDerivation.DEFAULT_DELAY`,
12 blocks. The member's device builds the grant without a delay
(`MemberWalletManager.kt:193`), and every signer recomputes it the same way and
refuses anything else (`refuseCertificate`, line 518). Nobody chooses the number.

This plan lets the group choose it. **The group's quorum signs one standing
statement in its own room: wallets members ask this group to guard carry this
group's leaf at `d` blocks. A member's device builds its request under the
newest such statement and carries that statement in the request. Every signer
checks that the request's own leaf has exactly that delay, and that the
statement it was built under is the group's newest.** Each wallet's delay is
then fixed in its certificate, as every guardian tag already is. A later
statement changes the wallets members ask for after it, and never one already
guarded.

**The statement is also the group's opt-in.** A group that has never signed one
does not guard members' wallets: the member's group picker says so, and every
signer refuses a request to it. **A later statement can close it again** with a
`closed` tag, and from then on the group takes no new member wallets until a
newer statement opens it. Closing never releases a wallet already guarded, since
the group's leaf is in every one of its addresses.

Read [member-guarded-wallets.md](./member-guarded-wallets.md) and
[guardian-delay.md](./guardian-delay.md) first. This plan reuses the request,
the certificate, the key notice and the signer check from the first, and the
delay range, the formatter and the delay chooser from the second.

**Built**, phases 1-7, one commit each, written against the code as it stood at
`05bd6ed6`, schema v31, with no schema change. D1 (a group-signed event, not the
birth certificate) was decided before the build; the opt-in (D8) and the
`closed` tag (D9) were added on review. `MemberWalletPolicyEvent`,
`MemberWalletManager.policyFor` and `judgePolicy`, and `MemberWalletPolicyScreen`
are the entry points. The phases below are kept as written; where the build
chose differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 1: `verify` only | also `read`, the shape without the signature, for `ProposedEvent`: a signer reads a policy before the group has signed it. `ORDER` is public, for the signers' staleness check |
| Phase 4: a `POLICY_UNKNOWN` refusal | not built. `KEY_UNKNOWN` already covers a member who has had no key answer, with its button to ask, and the answer carries the policy. A member whose answer came from a device that held no policy reads `NOT_OFFERED` until an answer that does arrives |
| Phase 4: `verified()` refuses a request carrying no policy | it accepts one. Certificates signed before policies embed a request with none, and refusing it would stop every wallet already guarded from verifying. That a *new* request must carry an open policy is the signers' rule, in `judgePolicy` |
| Phase 4: *set by #Council* beside the group's line | not added: the reach sentence already names the group whose delay it is |
| Phase 5: the group page's request row says why | the member's wallet screen does, through `MemberWalletRow.refusedBecause` and `MemberWalletManager.Why`. *Ask again* on a refused request makes a new one under the newest policy with a new key and discards the old -- `askAgain` takes the member's key and returns the wallet now asked for, and the wallet screen follows it. For a closed group only *Discard* is offered |
| Phase 6: `MemberWalletPolicyViewModel` holds the input as typed | in `GuardianDelayInput`, shared with the subgroup picker, as `GuardianDelayChooser` is |
| Phase 6: the row shows for every member | for every member of a Marmot group that is not disbanded (`showsMemberWalletPolicy`); a device that cannot sign sees the state and no button |
| nothing about test fixtures | `GroupFixtures.openToMembers`: every member-wallet test now opens its group first, since D8 closes a group with no policy |

Still to do by hand: the three-emulator run in Phase 7.

---

## Where the delay should live

The question this plan has to settle first. There are four places a group-level
number could be signed, and only one of them is the group's own decision about
the group's own leaf.

| place | who signs it | why not |
|---|---|---|
| the group's **birth certificate** (30329) | the **parent's** quorum, once, at the group's birth | it is the parent's statement about the child, and the member-wallet delay is the group's decision about its own leaf. The group did not sign it and cannot change it, because a certificate is replaceable only by the parent, and the group's key state carries the one it was born with. A **root group has no certificate at all**, so the most common group could never set a delay |
| the group's **key state** (30326) | the group's own quorum | it answers one question, *what does this room sign with*, and `GroupKeyStateManager.stateFrom` drops a state that says anything it cannot check. Changing a wallet policy would mean re-signing the room's key state, which every device then re-adopts. The key notice already carries the key, so the delivery advantage is small |
| the group's **wallet statement** (30331) | the group's own quorum | it opens one account on one network, and only once. A group with no wallet of its own, or a group that guards members on signet and mainnet, has nowhere to put it. It is also immutable for its account by the guardians note's rule 2 |
| **a new group-signed event**, 30337 | the group's own quorum | **recommended.** Signed by the group about the group, changeable by the group, and verifiable by anyone holding the room's id, through the same `GroupKeyStateEvent.isSignedByRoom` check the chronicle and the certificates use |

So the answer to "birth certificate or group signed event" is **a group-signed
event of its own kind**. The birth certificate stays the parent's, and keeps
deciding only what the parent decides: whether the group is guarded, and the
parent's own leaf's delay in the group's wallet.

---

## The policy event

Two shapes, one kind:

```
open:       kind 30337, pubkey G, content ""
            ["d",              "member-wallets"]    addressable: the newest wins
            ["guardian_delay", "<blocks>"]          6..65,535, GuardianDerivation.DELAY_RANGE

closed:     kind 30337, pubkey G, content ""
            ["d",              "member-wallets"]
            ["closed"]
```

Exactly one of `guardian_delay` and `closed`. An event with both, or neither,
does not read: a group that has said two things about its members' wallets has
said nothing a device can act on, and guessing which it meant would be the device
deciding for the group.

- **Signed by `G`'s quorum** through the existing FROST proposal path, like the
  wallet statement. No new signing machinery.
- **Newest wins**, by the group's own `created_at` with the id breaking a tie,
  for the reason `GroupWallet.newestAmong` gives: two devices that read the same
  events in different orders have to agree.
- **No policy means not offered** (D8). A group guards members' wallets only
  once its quorum has said so, and at the delay it said.
- **The newest decides, open or closed** (D9). Closing and opening again are each
  one more statement; nothing is ever deleted, and every device agrees on which
  is newest.
- **The `d` tag names *member wallets*, not *delay***, so a later rule about
  members' wallets is another tag on the same event rather than another kind.

### Why the request carries it

A member's device needs the delay before it builds anything, because the leaf,
the tree and both descriptors depend on it. Two kinds of device may not hold the
newest policy:

- **A member who joined after it was signed.** MLS cannot decrypt messages from
  before a member's welcome, which is the reason the key notice (30336) exists.
- **A signer who missed it.** `docs/mls-skipped-keys.md` describes how a group
  event that arrives a moment late is dropped for good on one device.

So the request carries, whole, the policy event it was built under. Any reader
can check it with `G` alone. A signer that missed the newest policy then still
judges the request against what the group actually signed. A signer whose newest
is newer than the one carried refuses the request as stale, and the member asks
again. Because the certificate embeds the request, the ancestors can also see
why the leaf has the delay it has.

---

## Phase 0 — decisions, before any code

### D1. A group-signed event, not the birth certificate

**Decided.** Kind 30337, the next free number after the key
notice at 30336. The next, 30338, has since gone to a member's payout
destination ([group-payout-destination.md](./group-payout-destination.md)).

### D2. Can the group change it?

**Recommended: yes, for wallets asked for afterwards; never for one already
guarded.** A wallet's delay is in its certificate's guardian tags, and the
guardians note's rule 2 fixes an account's guardians, delays included. So a new
policy reaches only requests built under it. The screen that changes it says so:
*Wallets already guarded keep the delay they were guarded at.*

### D3. What the delay covers

**Recommended: the group's own leaf only.** Each group above keeps the delay it
chose for the group (guardian-delay D1), so a member's wallet under `M`, under
the root, reads:

```
leaf 1  M's own     delay from M's newest 30337, or 12
leaf 2  the root's  the delay the root chose for M, unchanged
```

A group cannot shorten the root's wait in its members' wallets any more than in
its subgroups'.

### D4. One delay per group, not per member

**Recommended: one.** A group that wants to treat one member differently is
asking for a different rule, and a per-member table would be a roster the group
signs over and keeps current. The member still chooses *whether* to ask. If they
do not like the group's delay, they do not have to ask that group.

### D5. Who may set it

**Recommended: any member holding a share proposes, and the group's quorum
decides,** like every other event the group signs. The entrance is shown only to
a device that can sign (`FrostSigningManager.canSign`), as the wallet's *Open*
button is.

### D6. Which request a signer accepts

A signer holds `held`, the newest verified 30337 it has, or none. The request
carries `carried`, or none. **Recommended:**

| carried | held | judged at | otherwise |
|---|---|---|---|
| an open 30337 `G` signed | none, or one -- open or closed -- not newer than it | `carried`'s delay | — |
| an open 30337 `G` signed | a newer open one | — | refused: *was asked under a delay the group has since changed* |
| an open 30337 `G` signed | a newer closed one | — | refused: *the group has stopped guarding members' wallets* |
| a closed 30337, or one not signed by `G`, or unreadable | any | — | refused: *carries a policy this group did not sign open* |
| none | any | — | refused: *does not say the group guards members' wallets* |

The first row covers the signer that missed the group's opening statement, and
the one whose newest is a closing the group has since reversed: the request
carries an open policy `G` signed, and nothing newer says otherwise, so the
signer is told what it missed rather than refusing it.

The last row is how a request from a build before this plan fails: it carries no
policy, so it never asked for the delay the group chose, or for a group that
chose to guard members at all. That is the right failure.

### D7. Mixed versions

**A signer on a build before this plan compares to `grantFor` at the default**,
so it refuses any request whose own leaf is not at 12. That is the safe way to
fail. It is also blind to D8 and D9: it would still sign a request at 12 to a
group that never opened, or has closed, if a member on an older build asked. A
group should open at a delay other than 12, or close, only once every member who
can sign is on a build from Phase 5 onward. The screen says this, because a
device cannot see other devices' versions.

### D8. No policy, no member wallets: the statement is the group's opt-in

**Recommended.** Today any member can ask any group, and the group only agrees
when its quorum signs that member's certificate. With D8 the group agrees up
front, once, for everybody, and every new member wallet's delay is one its group
chose. There is no default any more.

What this changes for groups that exist:

- **A group that wants to keep guarding members' wallets signs an open policy
  once**, even to stay at 12. Phase 6's screen offers *Guard members' wallets*
  with 12 selected, so that is one tap and a quorum.
- **Wallets already guarded are not touched.** Their certificates stand; the
  member still spends, and every guardian still watches. D8 is about new
  requests.
- **A request already asked and not yet signed** is refused by D6's last row
  once the signers update. The member's row says why and offers *Ask again*,
  which works once the group opens.

### D9. Closing, and opening again

**Recommended: `["closed"]` on a newer policy.**

- **Closed means no new member wallets.** The member's picker says *this group
  has stopped guarding members' wallets*; every signer refuses a request built
  under an older open policy (D6's fourth row) and puts nothing to the quorum.
- **Closed does not release a wallet already guarded.** The group's leaf is in
  every address of every wallet it guards, and no statement can take it out. The
  group can still take any coin there after its delay, and the screen that
  closes says so: *Wallets already guarded stay guarded.* A member who wants out
  moves their coins to a wallet of their own, which needs nobody's agreement.
- **Opening again is a newer open policy**, at any delay. It reaches only
  requests built after it, as every policy does.
- **Closing is not a refusal of a person.** It is the group's statement about
  every member at once, and D4 rules out a per-member list for the same reason.


---

## Phase 1 — the policy event, pure

New `nostr/wallet/MemberWalletPolicyEvent.kt`:

```kotlin
object MemberWalletPolicyEvent {
    val KIND: Kind = 30337
    const val IDENTIFIER = "member-wallets"

    fun open(delay: Long, createdAt: Long = now): EventTemplate      // requires DELAY_RANGE
    fun closed(createdAt: Long = now): EventTemplate

    /** What [event] says, as [chatRoomId] signed it. */
    sealed interface Policy {
        val event: Event
        val createdAt: Long get() = event.createdAt

        data class Open(val delay: Long, override val event: Event) : Policy
        data class Closed(override val event: Event) : Policy
    }

    /** [event] as [chatRoomId]'s policy: the kind, the `d`, exactly one of a readable delay or `closed`, signed by the room. */
    fun verify(event: Event, chatRoomId: String): Policy?

    /** The newest among [events] that verifies, by created_at then id -- open or closed. */
    fun newestAmong(events: List<Event>, chatRoomId: String): Policy?
}
```

`verify` checks the kind, the `d` tag, exactly one of a `guardian_delay` tag that
reads and is in `GuardianDerivation.DELAY_RANGE` or a bare `closed` tag, and
`GroupKeyStateEvent.isSignedByRoom(event, chatRoomId)`, all inside `runCatching`.

**Tests**, `MemberWalletPolicyEventTest`, pure:
- an open policy and a closed one each round-trip;
- each of these fails on its own: another kind, a missing `d`, two delays,
  `"5"`, `"65536"`, `"twelve"`, both a delay and `closed`, neither, signed by
  another room;
- of an open, a closed and a second open policy, the newest wins whatever order
  they arrive in, and a tie on `created_at` goes to the larger id on every device.

---

## Phase 2 — the group files it, and the transcript says it

The FROST completion path already files every signed event in
`GroupSignedEvent` (`FrostSigningManager.recordSignedEvents`), whatever its kind.
This phase reads them:

- `MemberWalletManager.policyFor(database, G): MemberWalletPolicyEvent.Policy?`
  is the newest verified among `G`'s stored 30337s and any carried in key-notice
  answers (Phase 3). It is the one reader, used by the member's device in Phase 4
  and by every signer in Phase 5. Null means the group has not opened (D8).
- `ChatMessage.applyInnerEvent` and `applySignedEvent` get a 30337 arm that
  writes one transcript line: *The group guards members' wallets, after 144
  blocks, about 1 day*, or *The group has stopped guarding new members' wallets*.
  Without the arm an older build shows a raw-JSON bubble, which is reason to ship
  this phase before Phase 6's entrance.
- `ProposedEvent.summarize` gets a 30337 arm: *Members' guarded wallets · this
  group after 144 blocks, about 1 day · for wallets asked for from now on*, or
  *Members' guarded wallets · no new ones · wallets already guarded stay
  guarded*.

**Tests**: `policyFor` prefers the newest of an open and a closed policy, either
way round; ignores one signed by another room; returns null for a group that
never signed one. The summary says the delay in both blocks and time, and says
what closing leaves alone.

---

## Phase 3 — the key notice carries it

A member who joined after the policy was signed cannot decrypt it. The key
notice already answers that member's other question, *what does this group sign
with*, so it answers this one too:

- `GroupKeyNoticeEvent.answeringTags` gains
  `["member_wallet_policy", <the newest 30337, whole>]` when the answering device
  holds one, open or closed.
- `read` returns it in `GroupKey.policy`, verified by
  `MemberWalletPolicyEvent.verify(it, G)`. A policy present and unreadable makes
  the whole answer unreadable, as an unreadable certificate already does. It is
  never read as "no policy".
- `MemberWalletManager.groupKeyFor` gains the policy, and `policyFor` considers
  it beside the stored ones.

Until an answer brings one, a late member's device holds no policy for a group
that may well be open. Phase 4's picker therefore tells *not known yet* apart
from *not offered*: a device that holds no key state and no answer says the
first, and offers the existing *Ask the group for its key*.

**Tests**: an answer carrying an open policy gives it, and so does one carrying
a closed one; one carrying a policy signed by another room does not read; an
answer without one reads, with no policy.

---

## Phase 4 — the member builds under it

`MemberWalletManager.refusal(database, G, chain)` gains three reasons, checked
after the ones it has:

| `MemberWalletManager.Refusal` | when | what the picker says |
|---|---|---|
| `POLICY_UNKNOWN` | no key state and no key answer yet, so no policy could be known | *Ask the group for its key* (the existing button) |
| `NOT_OFFERED` | the key is known and no policy is | *This group doesn't guard members' wallets* |
| `CLOSED` | the newest policy is closed | *This group has stopped guarding members' wallets* |

So the picker still lists every group, each with why it cannot be chosen, as it
does today. A group without an open policy is never offered silently.

In `MemberWalletManager.request`, which already refuses on any refusal:

```kotlin
val policy = policyFor(database, chatRoomId) as? MemberWalletPolicyEvent.Policy.Open
    ?: throw IllegalStateException("$chatRoomId does not guard members' wallets")
val grant = GuardianDerivation.grant(
    groupKey.thresholdPublicKey, chatRoomId.lowercase(), groupKey.guardians, walletId,
    delay = policy.delay,
)
```

- `MemberWalletRequestEvent` gains `["member_wallet_policy", <the open 30337,
  whole>]`, always. The member's nostr signature covers it, as it covers
  everything in the request.
- `Reading.verified()` gains a sixth check: the carried policy is open, verifies
  against the request's `h`, and the first guardian's delay equals its delay.
  This is everything a reader can check without knowing the group's newest. The
  newest is Phase 5's job.
- `MemberWalletManager.delaysOf` reads the open policy for the first delay, and
  is null for a group that is not open. The new-wallet screen already lists each
  guardian's delay from it (since `3b2d9bd8`), and gains *set by #Council* beside
  the group's own line.

**Tests**: a request under an open policy at 4,320 has its first guardian at
4,320 and carries the policy; `request` refuses a group with no policy and one
whose newest is closed; a request whose first delay disagrees with its carried
policy fails `verified()`, and so does one carrying a closed policy or none.

---

## Phase 5 — every signer checks it

`MemberWalletManager.refuseCertificate` stops computing the expected grant at
the default. Instead it:

1. reads the request out of the certificate, as it already does;
2. applies D6's table, with `held = policyFor(database, G)` and `carried` from
   the request, to get the delay or a reason to refuse;
3. compares the certificate's guardians to
   `SubgroupManager.grantFor(database, G, K_G, W, delay)`, which since
   guardian-delay Phase 6 takes the own leaf's delay. The keys, their order and
   every inherited delay are checked exactly as before.

`MemberWalletManager.putToGroup` runs the same check before it proposes, as it
already runs `refuseCertificate`. A sponsor is then never asked to put to the
quorum a request their own device knows is stale, or asks a closed group.

The member's request row on the group page (*Asked by @alice, not yet signed*)
reads the same reasons:

- **asked under an older delay**: offers the member *Ask again*, which builds a
  new request under the newest open policy, with a new key;
- **the group has stopped guarding members' wallets**: offers *Discard*, and
  says *Ask again* will be possible if the group opens again;
- **does not say the group guards members' wallets**, a request from before this
  plan: offers *Ask again* once the group has opened.

Either way the old request's key is discarded, because no address was ever
offered.

**Tests**, `MemberWalletSigningJvmTest`: every row of D6's table, through
`refuseCertificate`; a signer that missed the opening policy accepts a request
carrying it; a signer holding a newer closed policy refuses a request carrying an
older open one; two signers with different held policies agree once the request
carries the newer one.

---

## Phase 6 — the group opens it, sets it, and closes it

Phases 5 and 6 ship together (see the order table): once signers refuse a
request with no policy, a group needs this screen to open.

**The entrance.** On `ChatRoomDetailScreen`, in *Members' guarded wallets*, a
first row for every member, in one of three states:

```
Guards members' wallets            after 144 blocks, about 1 day       [Change]
Doesn't guard members' wallets                                         [Guard members' wallets]
Stopped guarding new members' wallets                                  [Guard members' wallets]
```

The buttons are shown only where the device can sign (D5); everybody sees the
state. Every button opens `MemberWalletPolicyScreen`, a pushed screen with a
`NavigateBackButton`, which holds:

- `GuardianDelayChooser`, **moved out of** `SelectSubgroupAdminsScreen` into
  `ui/composable/widgets/GuardianDelayChooser.kt`, with its presets, its custom
  field and its live `delayText`, starting at the group's current delay, or 12
  for a group opening for the first time. Its words are passed in, because *the
  subgroup* and *the member* differ;
- D2's line: *Wallets already guarded keep the delay they were guarded at*;
- D7's line: *Members whose app is older than this one can only be guarded at
  12 blocks, and don't know this group can close*;
- **Ask the group**, which proposes an open 30337 at the chosen delay through
  `FrostSigningRepository.proposeSigning` and goes to `FrostSigningRoute`, as
  opening a wallet does;
- on an open group only, **Stop guarding new wallets**, an `OutlinedButton`
  under it, which first asks in a dialog -- *Stop guarding new members' wallets?
  Wallets already guarded stay guarded, and this group can still take their
  coins after the delay.* -- and then proposes a closed 30337 the same way.

A view model, `MemberWalletPolicyViewModel`, holds the input as typed, refuses a
delay below 6 or above 65,535 blocks with the reasons the subgroup picker
already uses, does not propose the delay an open group already has, and does not
propose closing a group that is not open.

The member's own leaf is the group's, and the trade the subgroup picker states
applies here too, said for a person: *A coin the member moves starts waiting
again, so a long delay lets them keep their coins out of reach by moving them.*

CLAUDE.md applies in full: strings in the catalogue in sentence case, spacing
from `MaterialTheme.spacing`, the screen's four states, and the outcome reported
through `rememberNotifier`.

**Tests**, `MemberWalletPolicyViewModelJvmTest`: a group opening for the first
time starts at 12; an open group starts at its delay; a preset and a typed delay
are what it proposes; out-of-range delays are refused with their reasons;
proposing the current delay is refused; closing is offered only to an open
group, and proposes a closed policy.

---

## Phase 7 — docs, and checking it on the emulators

- [member-guarded-wallets.md](./member-guarded-wallets.md): *What "full control"
  means* stops saying 12 and links here; D3 (*which members may ask*) gains
  *and only of a group that has opened*. The request's tag list gains the policy.
- [guardian-delay.md](./guardian-delay.md): its build table's member-wallet row
  ("a group still cannot choose…") points here.
- [README.md](./README.md): this note's row.
- This note: *Built*, and a table of where the build chose differently.

By hand, regtest, three emulators: Alice (a signer), Bob (a signer), and Carol,
who joined the group after step 2.

1. Carol's picker, before anything, says *This group doesn't guard members'
   wallets*.
2. Alice and Bob open the group at 144 blocks. The transcript says so on the
   devices that were in the room.
3. Carol joins. Her picker says the group's key is not known yet; she asks; the
   answer carries the policy; her screen says *after 144 blocks, about 1 day*.
4. Bob puts Carol's request to the group. Both signers accept it, and the
   certificate's first guardian tag says 144.
5. Alice and Bob change the delay to 1,008. Carol's wallet still says 144. A new
   request from Carol is built at 1,008.
6. Alice and Bob close the group. Carol's picker says *stopped guarding*; a
   request she built before the close is refused by both signers; her wallet at
   144 is still guarded, and Alice can still take a coin from it after 144 blocks.
7. Alice and Bob open it again at 6. Carol can ask again, at 6.
8. A request built on an older build (12, no policy) is refused by both signers,
   with *does not say the group guards members' wallets*.

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
| 1 | `MemberWalletPolicyEvent` and its test | dark: a kind nothing produces |
| 2 | `policyFor`, the transcript and summary arms | dark, and should ship early: an older build shows a raw-JSON bubble for a 30337 it has no arm for |
| 3 | the key notice | dark |
| 4 | the request, the picker's refusals | **with 5 and 6**: from here a member can ask only an open group |
| 5 | the signers' check | **with 4 and 6**: from here signers refuse a request to a group that has not opened |
| 6 | the entrance and the screen | **with 4 and 5**: the only way a group opens |
| 7 | docs and the emulator run | last |

**Phases 4, 5 and 6 are one release.** Under D8, Phases 4 and 5 stop every group
taking new member wallets until it opens, and Phase 6 is how it opens. Shipped
apart, the member's picker would say *doesn't guard members' wallets* about every
group, with no way for any group to say otherwise. Shipped together, an existing
group loses nothing but one quorum: the first time anybody wants a new member
wallet from it, a signer opens it at 12. The release note should say so, since
for a few days every group will read *doesn't guard members' wallets* until
somebody opens it.

No schema change. Policies are `GroupSignedEvent` rows like every other event
the group signs, and the request already keeps its event whole.

---

## What this does not do

**It does not change a wallet already guarded** (D2). A different delay is a new
request, with a new key.

**Closing does not release a wallet** (D9). The group's leaf is in every address
of every wallet it already guards, and the group can still take a coin from one
after its delay. Closing stops new wallets, and nothing else. Releasing a
member's wallet would need the member to move their coins somewhere the group
holds no leaf, which they can do alone at any time.

**It does not open groups that existed before it** (D8). Each group's quorum
opens it, once. Nothing opens a group on its behalf, because the point of D8 is
that a group guards members' wallets only once it has said so.

**It does not let the member choose.** The member chooses whether to ask, and
sees the delay before they do. A member who wants a longer delay than the group
offers asks a different group, or asks this one to change it.

**It does not set the subgroup picker's default.** A group making a guarded
subgroup still chooses that subgroup's delay on the picker, starting from 12.
Using the member-wallet policy as the picker's starting point is a one-line
follow-up, and it is left out so that the policy means one thing.

**It does not reach a group's ancestors.** The root's delay in a member's wallet
under `M` is the delay the root chose for `M` (D3). A root that wants a
different wait in its descendants' members' wallets sets it when it makes the
group below it.

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| a tag on the group's birth certificate | the parent's statement, not the group's; fixed at birth; absent for a root group (see *Where the delay should live*) |
| a tag on the group's key state | re-signing what the room signs with to change a wallet rule, and every device re-adopting it |
| a tag on the group's wallet statement | one per network and account, immutable, and absent for a group with no wallet of its own |
| the member chooses, within a range the group sets | asked for as *the group sets the delay*; a range is a second number to sign and explain, and can be added later as a second tag on the same event |
| signers judge only by the policy they hold | a signer that missed the newest policy would refuse a correct request, and two signers holding different ones would never agree on the same request |
| a per-member delay | a roster the group signs and keeps current (D4) |
| no policy means guard at the default, 12 | the first draft of this plan. It made every group a guardian of any member who asked until its quorum refused, and made the closed tag an opt-out from something no group had opted into (D8) |
| a separate kind to close | two kinds to order against each other; one addressable kind whose newest says open or closed has one answer on every device (D9) |
| deleting the policy to close | a signed event is never deleted on other devices, and an absence cannot be told apart from a member who never received it |
