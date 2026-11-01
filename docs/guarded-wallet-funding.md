# Funding a guarded wallet: a group paying into a descendant's wallet

An ancestor group can already *take* from a descendant's guarded wallet. This
plan is about the other direction: **the group pays coins from its own wallet
into the descendant's guarded wallet, using an address its own devices derive
and every signer checks.** It is a send from the group's wallet with the
destination filled in, as the request guessed. What the request did not settle
is what that destination should be, how a signer can tell it is really the
descendant's address and not one the proposer typed, and how the guarded
wallet row on the wallet screen fits a second button. This plan answers those
three.

Read [group-wallet-guardians.md](./group-wallet-guardians.md),
[group-send-an-amount.md](./group-send-an-amount.md) and
[group-wallet-addresses.md](./group-wallet-addresses.md) first. This plan uses
the forwarded statement from the first, the send form and its reservations from
the second, and the "current plus two" rule from the third. It changes no
format, no derivation and no schema.

**Built**, phases 1-6, one commit each, written against the code as it stood
at `8cb6f04e`, schema v29, with the `lightning-kmp-app` submodule at `e0d2b82`;
the build changes no schema. `GroupWalletState.fundingAddress`,
`GroupSpendBuilder.Destination.Funding`, `GroupSpend.fundingsOf` and
`GroupSendRoute.fundChatRoomId` are the entry points. The phases below are kept
as written; where the build chose differently the table says so.

**Since [payouts-only.md](./payouts-only.md):** a group's own wallet no longer funds a
guarded wallet. A funding pays no member's payout address, so it is not a payout, and the
wallet screen's *Fund* is gone. How a funding is read, by every device and in a bump, stays
for the fundings signed before.

| what the plan said | what it turned out to be |
|---|---|
| Phase 1 tests `claimedFundingIndices` | Phase 2 does: until the builder writes a claim there is no PSBT to read one from. Phase 1's watched-window test also counts the slots, eight, so the bound and the look-ahead cannot drift apart unnoticed |
| Phase 2: `send` and `sendAll` take a destination rather than an address string | both, as overloads: the string versions build a `Destination.Address` and call through, so the thirty-odd existing call sites, most of them tests, did not change. The claim always goes on output 0, where both builders put the payment |
| Phase 3: the check runs in `acceptSpendProposal` and `checkedSpend` | and in `proposeSpend` and `proposeGuardianSpend`, so a proposal every other device would refuse is refused before it is sent. It is one extension, `GroupSpend.Result.withFundings`, and a spend none of whose payments claims anything reads no guarded wallets at all |
| nothing about bumping | `GroupSpendBuilder.bump` rebuilt the PSBT without the claim, so a bumped funding would have read as a payment to a bare address and released its index. It carries `Checked.fundings` across |
| Phase 3's test is in the shape of `GuardianSpendRoundTest` | it is three tests in `GuardianSigningJvmTest`, which already holds an ancestor, a guarded child and the forwarding. The refusal test has the lie proposed by a device never told about the wallet -- the realistic way one arrives, since a device that holds the wallet refuses to propose it |
| Phase 4: the card says *To* and then the wallet's name | *To* and then *Savings's guarded wallet*. The title already says *Fund …*, and the card repeating it read twice. The index uses the address list's own *Address %1$s* |
| Phase 4: `MiddleEllipsis` on the address | as written, and on the desktop target it ellipsises at the end, as the address list does there |
| Phase 5: the name stays on one line | two: at 200% text a one-line name was cut to a word, and the name is what says which wallet the buttons act on |
| Phase 5: a `GroupWalletViewModelJvmTest` case for `arriving` | `GuardedWalletRowTest` in commonTest, since `arriving` is a property of the row and needs no view model; and `GuardedWalletCardJvmTest` renders the card, including at twice the text size |

Still to do by hand: the signet funding between two real devices that the end
of Phase 6 asks for.

The headline: **the group already watches every guarded wallet below it, so its
devices already know which of that wallet's receive addresses are unused. A
funding spend pays the first unreserved address from `current + 2` upward, and
the PSBT output carries that address's derivation under the descendant's
account, the same field change already uses. Every signer derives that address
again from the forwarded statement it already holds, and then shows the spend as
*Fund Thandi's group's guarded wallet* rather than as a bare address. If the
derivation does not reach the address, the signer refuses the spend.**

---

## What is already there

Funding needs no new knowledge, because the ancestor already has it:

| what funding needs | where it already is |
|---|---|
| the descendant's account xpub and script tree | the forwarded guarded statement, checked by `GuardianManager.verify` and listed by `guardedWalletsOf` |
| which of its receive addresses are used | `GroupWalletViewModel` already subscribes to `wallets.walletFor(guarded.account)` for every guarded row; `GroupWalletState.receiveAddresses` reads that state |
| coins to send, change, reservations, the mainnet cap | `GroupSendViewModel` and `GroupSpendBuilder`, unchanged for the room's own wallet |
| a place in the PSBT to say *this output is under that account at that index* | `taprootDerivationPaths` on an output, which `GroupSpend.psbtOf` already writes for change |
| a signer who rebuilds a claim rather than trusting it | `GroupSpend.check`, step 5: change is change only if it derives |

So the work is: choosing the address, writing the claim, checking it, and
showing it. There is no protocol work.

---

## Phase 0 — decisions, before any code

Seven, each with a recommendation. The phases assume the recommended answer.

### D1. Pre-fill the address field, or replace it?

**Recommended: replace it with a fixed destination card.** The request guessed
"pre-fill the spend screen", and that is close. But an editable field that
starts with the descendant's address and can then be changed makes the title
(*Fund X's guarded wallet*) a claim the form cannot keep. A member who wants to
send somewhere else already has *Send*. In fund mode the form shows:

```
┌──────────────────────────────────────────────┐
│ To   Thandi's group's guarded wallet         │
│      bc1p8q…x7kd · address 5                 │
│                                              │
│ This group can spend these coins again once  │
│ they have 12 confirmations.                  │
└──────────────────────────────────────────────┘
  Send everything                        ( )
  Amount in sats  [            ] sats
  Feerate in sats per byte  [   ]
  Sends 50,000 sats to Thandi's group's
  guarded wallet with a fee of 420 sats
  [ Ask the group to send ]
```

The amount, feerate, send-everything and preview all work as they do now.

### D2. Which address?

**Recommended: the lowest index from `current + ADDRESSES_AHEAD` upward that no
open spend in this room already pays, and below `current + RECEIVE_LOOKAHEAD`.**

- **Not `current`.** The child's own members are handing out `current` to their
  own payers right now; that is the reason the address list exists. Every
  device computes the same `current`, the ancestor's included, so funding to it
  is the address-reuse hazard
  [group-wallet-addresses.md](./group-wallet-addresses.md#the-bound) describes,
  caused by an ancestor this time.
- **From `current + 2` upward.** The child's members start at `current` and go
  up through the next two. Starting the ancestor at the top of that window and
  going higher keeps the two sides out of each other's way. Only one index is
  shared (`current + 2`), and only when a third child member is handing out
  addresses at the same moment.
- **Reserved like change.** Until the funding confirms, its address still reads
  as unused, so a second funding proposal would pick the same address. Open
  spends' claimed funding indices are held exactly as
  `reservedChangeIndices` holds change (Phase 3).
- **Bounded by the look-ahead.** The child watches `current … current + 9`. An
  address past that would be paid and the child would not see it until its own
  look-ahead caught up. With eight slots (`current + 2 … current + 9`), a
  room with that many open fundings into one wallet is told to wait, the way
  `TooManyOpen` already handles change.

### D3. How does the proposal say where the money goes?

**Recommended: in the PSBT, as the output's `tap_bip32_derivation` under the
descendant's account fingerprint, with no new nostr tag.** That is the field a
change output already carries, read with the same code, and it is where
[the guardian spend chose to put its claim](./group-wallet-guardians.md)
for the same reason: a second place to say it would be a second thing to
disagree. An old client's `check` sees a derivation whose fingerprint is not its
account's, treats the output as a plain payment, and shows the address, which is
the right thing for an old client to do.

### D4. What does a signer do with the claim?

**Recommended: one rule for each of three cases.**

| the output's claim | the signer's device | result |
|---|---|---|
| a fingerprint of a guarded wallet it holds, at an index that derives to this output's script | shows *Fund X's guarded wallet* | signed as usual |
| a fingerprint of a guarded wallet it holds, at an index that does **not** derive to the script | refuses the proposal before it is shown | the same refusal step 5 gives a change claim that does not derive |
| a fingerprint it does not recognise (no forwarded statement on this device, or a wallet it never verified) | ignores the claim and shows the bare address | as today |

The second row is what makes the label safe: no proposer can put a friendly
name on a payment to an address the descendant does not own. The third row
means a member who never got the forwarded statement still sees the truth,
just less of it.

### D5. A rollout gate?

**Recommended: none.** A funding spend is a send from the group's own wallet to
an address, and it is already held to `GroupWalletRollout.MAINNET_CAP`. A
mixed-version group loses nothing: old signers see a payment to an address,
which is exactly what it is.

### D6. What can be funded, and from what?

**Recommended: only guarded wallets of groups below this one that this room
holds a verified statement for, and only from the room's own wallet.**

- Unguarded subgroups: no. Their statements are never forwarded upward, so the
  ancestor has no xpub to derive an address from. *What is left* covers this.
- Siblings, ancestors, unrelated groups: no. There is no statement for them in
  this room, and *Send* with a pasted address works.
- From a guarded wallet into another guarded wallet (a guardian moving coins
  between two descendants): no. `GroupSendRoute` already uses
  `guardedChatRoomId` to mean the *source*. A route naming both a source and a
  destination is refused (Phase 4). Rebalancing is its own decision.

### D7. Does the child learn who paid it?

**Recommended: not in this plan.** The child sees a coin arrive at an address it
did not hand out, on its address list as *Paid · 50,000 sats*. Saying *from the
parent* means sending a notice down the hierarchy, which no flow does today.
Only a member who sits in both rooms could carry it, and the child could verify
only that the transaction pays its own address, not who funded it. *What is
left* has the shape of it.

---

## Phase 1 — choosing the address, pure

**Half a day, most of it tests.**

In `wallet/GroupOnchainWallet.kt`, beside `receiveAddresses`:

```kotlin
/**
 * The receive address an ancestor pays when it funds this wallet: the lowest
 * unused index from [GroupOnchainWallet.ADDRESSES_AHEAD] past the current one
 * that [reserved] does not already hold, and that the wallet still watches --
 * or null before Electrum has answered, and when every such index is held.
 * See `docs/guarded-wallet-funding.md`, D2.
 */
fun fundingAddress(reserved: Set<Long>): GroupAddress?
```

It reads the same `current` as `receiveAddresses`, so the two never disagree,
and it skips any index that is already `alreadyUsed` (the gap rule from the
address note: a watch-only wallet may have been paid above `current`).

In `wallet/GroupSpend.kt`, beside `claimedChangeIndices`:

```kotlin
/** The receive indices under [account] that [psbt]'s outputs claim to fund. Claims, not checks, as for change. */
fun claimedFundingIndices(psbt: Psbt, account: GroupWalletDerivation.Account): Set<Long>
```

**Tests**, in `GroupOnchainWalletJvmTest` against `FakeElectrum`:

- a fresh wallet funds `/0/2`;
- with `/0/2` reserved it funds `/0/3`; with `/0/2 … /0/9` reserved it returns null;
- paying `/0/0` moves the funding address to `/0/3`;
- a payment to `/0/4` while `/0/0` is current is skipped: `/0/2`, then `/0/3`, then `/0/5`;
- the address is null until Electrum has answered, as `receiveAddress` is;
- every index returned is one `receive.addresses` has generated. This fails if
  the bound and the look-ahead ever drift apart.

---

## Phase 2 — the builder writes the claim

**Half a day.**

`GroupSpendBuilder.send` and `sendAll` take a destination rather than an
address string:

```kotlin
sealed interface Destination {
    /** What the member typed: whatever it is, it is not the group's to vouch for. */
    data class Address(val address: String) : Destination
    /** A descendant's guarded wallet, at the receive address Phase 1 chose. */
    data class Funding(val account: GroupWalletDerivation.Account, val derived: GroupWalletDerivation.Derived) : Destination
}
```

`Funding` pays `derived.pubkeyScript`, the guarded address with its tree. There
is no string round trip, so `NotAnAddress` cannot happen for it.
`GroupSpend.psbtOf` gains a `funding: Map<Int, Pair<Account, Derived>>` beside
`change`, and writes each one with `pathOf(derived, descendantAccount)`. This
is the same call change uses, with the descendant's fingerprint instead of the
room's. `Built` gains `funded: Funding?` so the preview can say where it goes.

**Tests**, in `commonTest` beside the builder's existing ones:

- a funding output's derivation survives `Psbt.write` / `Psbt.read` with its
  fingerprint intact, for a fingerprint whose top bit is set. This is the
  unsigned-read bug the guardians note fixed, checked again on a new field;
- `GroupSpend.check` against the room's own account accepts the PSBT and lists
  the funding output among `payments`, not as change;
- a `send` to a typed address builds exactly the PSBT it built before.

---

## Phase 3 — every signer checks the claim, and says it

**A day and a half.** This is the phase that makes the label safe, so it lands
before any button that produces one.

**The check.** A pure function in `GroupSpend`, separate from `check` so that
`check` stays about the room's own account:

```kotlin
data class Funding(val outputIndex: Int, val wallet: GuardianManager.GuardedWallet, val derived: GroupWalletDerivation.Derived)

sealed interface FundingReading {
    data class Ok(val fundings: List<Funding>) : FundingReading
    data class Refused(val reason: String) : FundingReading
}

/** D4's three rows, over [checked]'s payments, against the guarded wallets this room holds. */
fun fundingsOf(checked: Checked, guarded: List<GuardianManager.GuardedWallet>): FundingReading
```

Fingerprints are four bytes, so two held wallets can share one. Every wallet
with a matching fingerprint is tried, and the claim is refused only if none of
them derives to the script.

**Where it runs:**

- `FrostSigningManager.acceptSpendProposal`, straight after `GroupSpend.check`.
  A `Refused` drops the proposal with the reason logged, as a failed `check`
  does. Guardian spends (`spender is Guardian`) are checked too, since a
  guardian could pay one descendant from another's coins once D6 is revisited.
- `FrostSigningManager.checkedSpend`, so the approval screen and the proposal
  list read the same answer. `Checked` gains `fundings: List<Funding> =
  emptyList()`, filled here rather than in `check`.
- `FrostSigningManager.reservedFundingIndices(database, chatRoomId, account)`,
  over `openSpends` like `reservedChangeIndices`, with a
  `FrostSigningRepository` method for the form.

**What it says.** `ProposedSpend.summarize` reads `spend.fundings`:

| spend | label | detail |
|---|---|---|
| one payment, a funding | *Fund Thandi's group's guarded wallet* | *Send 50,000 sats to Thandi's group's guarded wallet, address 5* |
| several payments, one a funding | *Spend from the group's wallet* | *Send 70,000 sats to 2 addresses, 50,000 of them to Thandi's group's guarded wallet* |
| no funding | unchanged | unchanged |

That one function feeds the signing screen, the room's proposal list
(`ProposalListViewModel`) and the wallet screen's *Spends* rows
(`SpendRowCard`), so all three change together. A name is the descendant's
certified name, `GuardedWallet.name`, falling back to *a subgroup* as the
guardian spend's label does.

**Tests**, a jvmTest in the shape of `GuardianSpendRoundTest`, with a parent
and child ceremony and the child's statement forwarded:

- a true claim is accepted and summarised as *Fund … guarded wallet*;
- a claim at the wrong index is refused at `acceptSpendProposal`, and no session
  is written;
- the same PSBT read by a member whose device holds no forwarded statement is
  accepted and shown as a bare address;
- an open funding's index is reserved, and a failed one's is released.

---

## Phase 4 — the form in fund mode

**A day.**

**The route.** `GroupSendRoute` gains `fundChatRoomId: String? = null`: the
descendant whose guarded wallet this send pays. It is a second field, not a
second meaning for `guardedChatRoomId`, which names where coins come *from*. A
route with both set is `GroupSendUIState.Unavailable` (D6).

**The view model.** `GroupSendViewModel.initiate` finds the guarded wallet the
same way it already does for a guardian spend
(`chatRepository.guardedWallets(chatRoomId)`, matched on room and chain). The
wallet being spent is still the room's own. `Ready` gains:

```kotlin
/** The descendant's guarded wallet this send pays, with where it is up to -- or null for a send to a typed address. */
val funding: FundingTarget? = null

data class FundingTarget(
    val wallet: GuardianManager.GuardedWallet,
    val walletState: GroupWalletState?,
    val reserved: Set<Long>,
) {
    val address: GroupAddress? get() = walletState?.fundingAddress(reserved)
}
```

`follow` combines a third flow, `wallets.walletFor(guarded.account).state`,
with the two it already has, and re-reads `reservedFundingIndices` whenever
the room's transactions change. If somebody pays the chosen address while the
form is open, the next emission moves the destination to the next index, and
the preview follows. That is why the card shows the index: a member who sees it
change knows why. `preview()` builds with `Destination.Funding` when `funding`
is set, and returns null while its address is null.

**The screen** (`GroupSendScreen`):

- the title becomes *Fund %1$s's guarded wallet*, beside the existing guardian
  title, for the same reason that one is there: the title is where the form says
  whose money moves where;
- `OutlinedTextField(addressField)` is replaced by the destination card from
  D1: an `OutlinedCard` with *To* in `labelMedium`, the wallet's name in
  `titleMedium`, the address in the monospace style with
  `TextOverflow.MiddleEllipsis` and *address %1$d*, and under it, in
  `bodyMedium`/`onSurfaceVariant`, *This group can spend these coins again once
  they have %1$d confirmations.* with `ANCESTOR_DELAY`. That sentence answers
  the request's last question: what is worth saying while funding a guarded
  wallet is that the money stays within the group's reach;
- while the address is null, a `LoadingDataIndicator(fillScreen = false)` with
  *Finding an address in %1$s's wallet…* in the card's place, and a null address
  once Electrum has answered (D2's eight slots all held) shows *Wait for this
  group's open payments to %1$s to confirm* in `error`;
- `SendPreview` says *Sends %1$s sats to %2$s's guarded wallet with a fee of
  %3$s sats* for a funding;
- the propose button is unchanged and calls `proposeSpend`, because this is the
  room's own spend.

**Tests**, in `GroupSendViewModelJvmTest`, with `follow` and the fake chain:

- the preview builds to `/0/2` of the descendant's account, with the claim;
- an open funding at `/0/2` makes the next one go to `/0/3`;
- paying `/0/2` while the form is open moves it to `/0/3`;
- both `guardedChatRoomId` and `fundChatRoomId` set gives `Unavailable`.

---

## Phase 5 — *Fund* on the wallet screen, without crowding the row

**A day.**

**Where the button goes.** On each row of the *Guarded wallets* section
(`guardianItems` in `GroupWalletScreen.kt`), next to *Spend*, as the request
asks. Today the row is a `ListItem` with *Spend* as a `TextButton` in
`trailingContent`. A second button there would squeeze the headline (the
group's name, already ellipsised), and at large text sizes the two buttons
would leave the name a few characters. So the actions move out of the trailing
slot and below the content, in the row's own card:

```
 today                                   after
┌─────────────────────────────────────┐ ┌─────────────────────────────────────┐
│ ▣ Thandi's group's…   [ Spend ]     │ │ ▣ Thandi's group's savings          │
│   12,000 sats within this group's…  │ │   12,000 sats within this group's   │
└─────────────────────────────────────┘ │   reach · 50,000 sats arriving      │
                                        │                   [ Fund ] [ Spend ]│
                                        └─────────────────────────────────────┘
```

- **An `OutlinedCard` per guarded wallet**, holding the existing `ListItem`
  (with no `trailingContent`) and then the actions. A card is how M3 groups
  content with the actions that apply to it. With several wallets listed, a bare
  row of buttons under each `ListItem` would leave it unclear which wallet a
  button belongs to.
- **The actions in a `FlowRow`**, aligned to the end with
  `Arrangement.spacedBy(MaterialTheme.spacing.targetGap, Alignment.End)`, so at
  200% text the two buttons wrap onto two lines instead of truncating. This is
  `SpendRowCard`'s *Send again* / *Bump the fee* row, with wrapping added.
  `FlowRow` is new to this codebase. It is in `foundation.layout`, and the audit
  counts it as neither spacing nor a dimension.
- **Both `TextButton`s**, equal weight: neither is the obvious primary action
  for every wallet, and a filled *Fund* would read as a recommendation. *Fund*
  first and *Spend* second, so money in reads before money out.
- **Each button names its wallet to a screen reader.** Two rows of *Fund* /
  *Spend* are four identical labels when read without the layout. Each gets
  `Modifier.semantics { contentDescription = … }` with *Fund %1$s's guarded
  wallet* / *Spend from %1$s's guarded wallet*. The second string already
  exists. This fixes the *Spend* button as it is today too.
- **Each button has its own gate**, so a row can show either one alone. *Fund*
  appears only in the `Loaded` state (the room has a wallet of its own to pay
  from), behind `canSign && canWatch`, the same gates as the room's *Send*.
  The `NotOpened` branch passes `onFund = null`, because a group with no wallet
  has nothing to fund with. *Spend* keeps the gates it has.
- **What is arriving.** After a funding, `withinReach` stays the same for two
  hours, which looks like the money vanished. `GuardedWalletRow` gains
  `arriving: Satoshi?`: coins in the wallet with fewer than `ANCESTOR_DELAY`
  confirmations. The supporting line adds *· %1$s sats arriving* when it is
  non-zero. It counts every such coin, not only this group's funding. That is
  true, and it is all the ancestor can know.

`toGuardianFund` sits beside `toGuardianSpend` in `GroupWalletScreen` and
navigates to `GroupSendRoute(…, fundChatRoomId = row.wallet.descendantChatRoomId)`.

**Strings**, sentence case, in the guardian block of `strings.xml`: `fund`,
`fund_s_s_guarded_wallet`, `to`, `address_d`,
`this_group_can_spend_these_coins_again_once_they_have_d_confirmations`,
`finding_an_address_in_s_s_wallet`, `wait_for_this_group_s_open_payments_to_s_to_confirm`,
`sends_s_sats_to_s_s_guarded_wallet_with_a_fee_of_s_sats`, `s_sats_arriving`.
Apostrophes are written plain, as `spend_from_s_s_guarded_wallet` is, because
Compose Resources does not unescape `\'`.

**Tests**: a `GroupWalletViewModelJvmTest` case that `arriving` counts an
unconfirmed coin and drops it at 12 confirmations. The layout is checked by
`m3Audit`, which has to stay at 0 on every budget, and by looking at it at
compact width and at 200% text.

---

## Phase 6 — the notes

**An hour.**

- This note becomes **Built**, with a table of where the build chose
  differently.
- A row in [README.md](./README.md), and a sentence in its reading order after
  the guardians and policy notes.
- [group-wallet-guardians.md](./group-wallet-guardians.md)'s *What this does not
  do* says guardians cannot constrain where coins go. It gains a line pointing
  here for the direction that is supported: coins coming in.
- [group-wallet-addresses.md](./group-wallet-addresses.md)'s *Why `+ 2`* gains a
  sentence: an ancestor funding the wallet starts at the top of that window
  and goes up.

Each phase is one commit, checked with
`./gradlew :composeApp:m3Audit` and
`./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`. Before
Phase 6's status line says built, a funding should be made by hand on signet
between two real devices, one in each room, and the child's address list
checked to show it at `current + 2`.

---

## What this does not do

**It does not fund an unguarded subgroup.** The parent has no xpub for it. The
child could forward its plain statement upward the way a guarded one is, but
that gives the parent a full view of a wallet whose point is that the parent
has no part in it, so it is a decision of its own.

**It does not fund a subgroup that has not opened a wallet.** There is no
statement and so no address. The guarded row only exists once one is forwarded.

**It does not tell the child who paid.** See D7.

**It does not stop the child spending the coins at once.** Funding is a gift
into a wallet whose own quorum holds the key path. The 12-block delay is on the
ancestor's leaf, not the child's key. The destination card says what the
ancestor keeps (a way back after 12 confirmations), and it says nothing that
suggests the coins are locked for the child.

**It does not make the address private from the child's members.** It is one of
the child's own addresses. They see the payment and its amount, and anyone in
both rooms can match it to the parent's spend.

---

## What is left, when someone wants it

- **A notice to the child.** A member in both rooms, on seeing a funding of this
  room confirm, posts a transcript line in the child's room: *Parent group sent
  50,000 sats to this wallet*. The child's devices check that the transaction
  pays one of their addresses, and attribute it to the member who posted it,
  since the child cannot check that the parent sent it. This is the reverse of
  the upward forwarding, and it needs the same overlap one level at a time.
- **Funding an unguarded child**, behind the decision in *What this does not do*.
- **Moving coins between two guarded descendants** in one guardian spend: a
  source and a destination on one route, D6's third bullet.
- **A guarded wallet's own screen**, reached by tapping its card, with its
  coins, its address list and both actions. The card's two buttons are enough
  for now. A third action would be the time to build it.

---

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| pre-fill the address field and leave it editable | the label would be a claim the form cannot keep; *Send* is already there for any other address (D1) |
| fund `current` | it is the address the child's own members are handing out (D2) |
| a nostr tag `["funds", <room>, <index>]` on the proposal | the PSBT already has a field for *this output is under that account*, read by code that exists; a second place would be a second thing to disagree (D3) |
| recognise a funding by scanning every payment against the descendant's addresses, with no claim | it costs a derivation per address per output per signer, it misses an address past the window, and it would quietly label a typed address that happens to be the child's. The claim states what the proposer meant, and the signer checks it |
| refuse a claim the signer cannot verify | it would make a funding unsignable by every member who missed the forwarded statement, for no safety gain: the bare address is the truth (D4) |
| *Fund* in an overflow menu on the row | it hides the one action this plan adds, behind an icon whose meaning nobody can guess; there are only two actions |
| *Fund* in the trailing slot beside *Spend* | the name, the only thing that says which wallet a row is, shrinks to a few characters at large text sizes |
| *Fund* as a filled button | neither action is the one every member wants; a filled button would be a recommendation the app cannot make |
