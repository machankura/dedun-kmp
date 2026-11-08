# Emptying the group's Wavelength wallet: a closing payout for the last member out

How the last member with money in the group's Wavelength wallet is paid everything left in it --
their own balance, the few sats still attributed to members who have already been paid, and the sats
attributed to nobody -- in one leave that takes every coin and returns the group no change.

**Not built. Written on 8 November 2026 against `16a9ed87`,** from a mainnet room that cannot empty
its wallet. Nothing in this note has run, and every file and line it names is as it stood that day.

**Read *what the ceiling changed* before planning any of it.** The room this was written from was
unwedged the same day by a different and much smaller change, which leaves this note's subject
intact and its example and its urgency gone.

**Why it is not called a sweep.** The word is taken three times over in this codebase:
`GroupSweep.check` is a closed channel's output coming *into* the onchain wallet, `payouts-only.md`
D1 says in as many words that *"sweeps are not payouts"*, and an Ark batch has a sweep key and a
sweep delay that decide when the operator may take an expired batch back. A fourth meaning would be
read wrong by somebody, in a hurry, in the one file where being read wrong costs money. So the thing
here is a **closing payout**, the authorisation type is `payout-closing`, and the screen's word for
it is *empty the wallet*.

### Where this stands

| phase | state |
|---|---|
| 0 | not run: it needs signet coins and a question put to the mainnet operator |
| 1 to 6 | not started, and question 5 asks whether they should be |

## The deadlock, with the numbers it was found in

A mainnet room on 8 November 2026, its Wavelength wallet holding 4,048 sats:

| member | balance | destination |
|---|---|---|
| @Alice | 4 sats | a Lightning address, **no onchain address** |
| @Bob (`kgothatso@blink.sv`) | 8 sats | a Lightning address and `bc1q7lxnf…dz6v` |
| @Bob (`catalyst@54052.co.za`) | 4,036 sats | a Lightning address and `bc1qnghz…wqac` |

Both of the offers on @Bob's 4,036-sat row are refused, and each says the same thing:

> Paying @Bob Dedun out over Lightning would leave less change in the Wavelength wallet than its
> operator accepts. It can go once someone deposits 988 sats more.

The arithmetic is `GroupWavelengthRollout.changeShortSat` (`GroupWavelengthRollout.kt:77`): spending
4,036 of 4,048 leaves 12 sats of change, `MIN_CHANGE_SAT` is 1,000, so the wallet is 988 short. The
daemon would refuse the send before anything was signed, which is why the screen refuses first.

The 12 sats are the whole problem. They are **not** @Bob's, so no payout of his may take them; they
are too small for a payout of their own, on either rail; and while they sat there, the 4,036 could
not move either. The wallet was wedged by 12 sats, and a deposit of 988 sats was the only thing the
app offered -- putting more money into a wallet nobody could get money out of. That last part is no
longer true, and *what the ceiling changed* below says what that costs this note.

`docs/group-wavelength-payouts.md` already saw this, in its risks table, and wrote it off:

> Nothing can take the last of it: the SDK wants a vHTLC of exactly the invoice plus a fee quoted when
> it's paid, never turns change into credit, and only an onchain sweep empties the wallet, which a
> round's 1,000-sat fee makes worthless at these sizes.

Two of those three clauses hold. The third does not: a round's 1,000-sat fee against 4,048 sats
leaves 3,048 sats, which is not worthless, and is three times `MIN_ONCHAIN_PAYOUT_SAT`. **The wedge
is worth unwedging; the note's own figure says so.**

## What the ceiling changed

**3,048 of those sats have since come out, and not by this note.**
[group-wavelength-spendable-ceiling.md](./group-wavelength-spendable-ceiling.md) is built, phases 1
to 3: the screen offers the most the wallet can spend rather than the member's whole balance, so
@Bob is paid 3,017 sats over Lightning and the operator keeps its 1,000 sats of change. It needed no
policy change, no authorisation type and no new event, because a payout *smaller* than a member's
balance was already legal everywhere a device checks one.

That is good for the room and awkward for this note, in two steps.

**The urgency is gone.** Three quarters of the wedged sats are out, and what is left is 1,000 --
988 of @Bob's, 8 of his other row's, 4 of @Alice's -- with nobody waiting on them.

**The arithmetic is gone with it.** A closing payout pays `everything − fee`, and the fee is a
round's: `maxRoundFeeSat` is 2,000 by default and mainnet's operator asked 1,000 as its minimum. Out
of a 1,000-sat wallet that is nothing arriving, so **this note's own rule refuses it** -- the risks
table below has the screen refusing a close that arrives under `MIN_ONCHAIN_PAYOUT_SAT`. Emptying a
wallet is worth a round when what is in it beats the round; after the ceiling has taken everything it
can, by construction it usually does not.

So the ceiling did not make this note cheaper to build. It made it **unnecessary for the room it was
written from, and no less necessary in general.** A wallet whose residue is large is still wedged and
still worth a round: C6's unattributed surplus, a refund that landed in the pot, a room of a hundred
members each stranding ten sats. What the ceiling took away is this note's example, not its subject.
Question 5.

## Where the dust comes from

It is not bad luck, and it does not stop happening. Two mechanisms make it, every time:

**1. The unspent fee reserve on a Lightning payout.** `LightningBalance.payable`
(`LightningBalance.kt:57`) holds back a percent of the amount, never under `MIN_FEE_MSAT`'s ten
sats, and a payout is proposed for the rest. The member is then charged **what the vHTLC really
held** (W11, `sentFor`), which is the amount plus the fee that was actually quoted. The difference
between the reserve and the fee stays behind in their name for ever. @Alice's 4 sats and @Bob's 8
sats are each one of these: a ten-sat reserve, a fee of six and of two.

**2. Pro-rata floor division.** `balancesIn` (`GroupWavelengthPayouts.kt:145`) shrinks every
member's net deposit to their share of what is really in the wallet with `sat * inTheWallet /
totalNet`, which rounds down. The remainder belongs to nobody and appears on no row, so a wallet can
be wedged by sats no screen in the app shows.

There is a third, rarer: when `inTheWallet` exceeds `totalNet` nobody's share is shrunk at all, and
the surplus -- a refund landed in the pot, a deposit nobody attributed -- is likewise unattributed.
It is rare, and it can be large, which is why C6 below bounds what a closing payout may absorb
rather than trusting that it is always dust.

So every group that uses Lightning payouts accumulates unpayable residue, and every group that uses
the wallet at all will eventually be unable to empty it. This is a wallet that cannot be closed, not
an awkward balance in one room.

## What can empty the wallet today, with none of this built

**The group can already ask the wallet to leave.** `leaveIfAsked` (`GroupWavelengthRunner.kt:350`)
takes a signed `GroupWavelengthExitEvent` and sends **every live coin** to the group's own onchain
wallet, and `checkRound` signs a leave to `isOnchainWallet(script)` with no authorisation at all
(`ArkPolicy.kt:572`). The Wavelength wallet ends empty. The money is then onchain as **coins nobody
owns**, which `payouts-only.md` D3 holds until a kind 30341 assignment says whose they are; after
that, an ordinary onchain payout pays @Bob with `PayoutBuilder`, under rules that have run on real
money.

That path needs **no policy change, no new event a device signs, and no adversarial reading.** What
it is missing is the 30341 assignment, which both the payouts note and the wallet note already list
as owed work:

> Assign the coins a leave to the group's own wallet brings out. […] A coin assignment proposed from
> the Wavelength ledger at the moment of leaving would close that, and is worth its own note.

It costs two fees instead of one -- a round, then an onchain spend -- and it moves everybody's money
rather than one member's. Against that, it is a smaller change in the only file where a mistake is
paid for in somebody else's coins.

**This note plans the closing payout, because that is what was asked for.** But the owner should
decide between them with the comparison in front of them, and the honest summary is: *assignment
from the Wavelength ledger is cheaper to build and safer to sign; the closing payout is cheaper to
use and is the only one that works when the group's onchain wallet is not where the member wants
their money.* Question 1.

## Read these first

- [group-wavelength-spendable-ceiling.md](./group-wavelength-spendable-ceiling.md): built, and why
  the room above is no longer wedged. Read its B2 beside C2 here: aiming a spend at no change is
  payable only when the round costs the whole fee limit, because a fee is a limit and not a figure
  until it is quoted. That is why the ceiling aims at the operator's minimum instead, and why C1's
  *returns the group nothing* rests entirely on `sweepAll` being a spend the daemon sizes itself --
  K1.
- [group-wavelength-payouts.md](./group-wavelength-payouts.md): W4 to W11, and the last row of its
  risks table, which is this note's first paragraph. Every rule here is one of its rules relaxed by
  exactly as much as emptying a wallet needs and no more.
- [payouts-only.md](./payouts-only.md): D1, D3, D6 and D7. C3 below is the first thing in this app
  that pays a member money that is not theirs, and D1 is what it has to answer to.
- [group-wavelength-wallet.md](./group-wavelength-wallet.md): D9 and D11, on what leaves and when.
- [group-payout-destination.md](./group-payout-destination.md): why a destination's onchain address
  has to be unused when it is set, which C4 leans on.

## Decisions

**C1. A closing payout is a leave that takes every coin and returns the group nothing.** One leave,
to one member's onchain address, paying what the wallet holds less the round's fee. No change VTXO
comes back to the group. That last clause is not a nicety: it is the whole safety argument of C2.

**C2. The amount is a range, and the range is what makes it safe.** A payout's leave is exact today
(W5), because the daemon fixes the leave and takes the fee from the change, so a smaller leave is
the operator pocketing the difference. A closing payout has no change for the fee to come from, so
the leave is `everything − fee` and the fee is not known until the round. The authorisation
therefore names what the wallet held, and a co-signer accepts a leave in `[amountSat − maxFeeSat,
amountSat]`.

The operator cannot shave, because **there is nowhere for a shaved sat to go.** If the round returns
the group no VTXO and forfeits every coin, every sat not in the leave is in the round's fee, and the
fee is already bounded three times over: by `maxFeeSat` on this authorisation, by `maxRoundFeeSat`
(`ArkPolicy.kt:606`), and by `checkFeeBudget`. So the range costs nothing W6 has not already
accepted.

**C3. It may take another member's balance only when nothing could ever pay that member it.** This
is the rule the whole note turns on, and it is the one that breaks D1. A closing payout absorbs
@Alice's 4 sats and @Bob's 8. The justification is not that they are small; it is that they are
**provably unreachable**: `LightningBalance.payable(sat * 1000)` returns null for them, so no
Lightning payout of their own can be built, and they are far under `MIN_ONCHAIN_PAYOUT_SAT` plus a
round's fee, so no onchain one can be either. Money that no mechanism can deliver to its owner is
money the group may agree to give to the last member out rather than abandon to the operator at
batch expiry.

A device checks it, per member, against **its own** balances: `payable` null **and** under
`MAX_STRANDED_SAT` (C5). Anything a member could still be paid stops the closing payout, with the
screen saying who should pay out first.

**C4. A member may give up a balance that is still payable, in their own hand.** C3 leaves a real
deadlock: @Alice with 500 sats and @Bob with 600, in a wallet of 1,100, are each individually
payable and each blocked by the other's change. Nobody is last. So a member may sign, **with their
own identity key and not the room's FROST key**, that what they have left in this wallet goes to
whoever closes it:

```
kind 30383, GroupWavelengthEvents.ABANDONMENT, an MLS inner event, never broadcast
["d", "abandon:<chain>:<wallet>:<member>"]
["wallet", <chain>, <wallet>]
["at_most_sat", <the most they are giving up>]
["expires_at", <unix seconds>]
```

A quorum's approval cannot stand in for this. FROST's aggregate signature does not say who signed,
so the room's signature on a closing payout proves that *enough members agreed*, never that *this
member* did. Only the member's own key proves that, which is why this event is outside the
authorisation family. `at_most_sat` bounds what the word covers, so a member who abandons 500 sats
has not abandoned a deposit that arrives tomorrow.

**C5. Two ceilings, and both are checked by every device.**

| ceiling | value | what it stops |
|---|---|---|
| `MAX_STRANDED_SAT`, per member, un-abandoned | the Lightning floor: `payable(sat) == null`, so 10 sats today | one member quietly taking another's real balance |
| `MAX_ABSORBED_SAT`, over the whole payout | `MIN_CHANGE_SAT`, 1,000 sats | a room of a hundred members each stranding ten |

What a standing abandonment (C4) covers is consented and counts against neither. Everything else
does: other members' dust, and the unattributed remainder of C6.

**C6. The unattributed remainder is absorbed, and bounded with everything else.** `inTheWallet − Σ
share` is money on no row. A closing payout has to take it, since it is in the wallet and the wallet
is being emptied. It counts against `MAX_ABSORBED_SAT`, so a large surplus -- a refund that landed
in the pot, which `group-wavelength-payouts.md` lists as an open bug -- **refuses the closing
payout** instead of handing it to whoever proposes one. That is the right way round: a surplus that
size should be attributed, not swept.

**C7. One authorisation type, `payout-closing`, beside `payout-onchain`.**

```
["d", "payout-closing:<chain>:<wallet>:<member>:<asked_at>"]
["type", "payout-closing"]
["payout", <member>, <destination event id>]
["address", <the destination's onchain address>]
["amount_sat", <what the wallet held when this was asked>]
["max_fee_sat", <the most the round may cost, and the slack on the leave>]
["absorbs", <member>, <sat>]            -- one per member whose balance it takes
["unattributed_sat", <what was on nobody's row>]
["expires_at", <unix seconds>]
```

The `absorbs` rows are what the proposer believed, and a signer checks each against its own balances
rather than trusting them. They are carried so the approval screen can say *takes 4 sats of @alice's
and 8 of @bob's, which neither can be paid*, and so a device that disagrees refuses with a reason
naming the member rather than a bare mismatch. `GroupWavelengthPaymentEvent.read`,
`GroupWavelengthExitEvent.read` and `GroupWavelengthOnchainPayoutEvent.read` all return null for it,
so an older device refuses to sign, which is W10's way round.

**C8. Onchain only, and the screen says why.** The SDK will not let a Lightning payment empty a
wallet: it wants a vHTLC of exactly the invoice plus a fee quoted at payment time and never turns
change into credit. `SendRequest.Onchain(address, sweepAll = true)` is the only primitive that
empties, and the app already uses it for the personal wallet (`WavelengthAdvancedViewModel.kt:108`).
**A member with no onchain address therefore cannot close the wallet** -- @Alice, in the room above
-- and the row has to say so rather than leaving the button dead.

**C9. What each member is charged needs no new code.** Once the wallet is empty, `inTheWallet` is 0,
so every `share` in `balancesIn` is 0 and every member's `availableSat` with it. The pro-rata shrink
that makes the dust also clears it. What Phase 2 adds is counting an **open** closing payout against
the proposer's balance, and `leftWith` finding its leave row so it is charged as paid.

**C10. A closing payout stands for two days, and only one at a time, per wallet.** Not per member: a
second closing payout to a different member while one is open is two claims on one wallet. W9's rule
for a member becomes a rule for the wallet, since the subject is the wallet rather than a balance.

**C11. It is refused while any other payout of any kind is open in that wallet.** A Lightning payout
being signed changes what the wallet holds between the authorisation and the round, and the closing
payout's ceiling was computed from the old figure. Fail closed and propose again.

**C12. A gate of its own.** `GroupWavelengthRollout.closesWallet(chain)`, off on mainnet until a
closing payout has run on signet (Phase 5). Both of its neighbours were turned on for mainnet by
decision ahead of their conditions; the room this note came from is on mainnet, so an off gate means
the wedge stays. Question 4.

## Phases

One commit per phase, each compiling and passing `./gradlew :composeApp:compileDebugKotlinAndroid
:composeApp:jvmTest` and `./gradlew :composeApp:m3Audit`. Messages end `(Group Wavelength closing
payout, Phase N)`. A phase marked **policy** changes what a device signs: it gets its own commit and
an adversarial reading before it is pushed, by a reviewer given the code and not this note.

### Phase 0: spikes, on signet and against the mainnet operator

No app code. Each answer is written back into this note, and C2 and C5 are confirmed or rewritten.

- **K1. What `sweepAll = true` really does.** Through `TestHostedBridge` and
  `GroupWavelengthSignetJvmTest`, empty a funded group wallet to a plain signet address with the
  policy temporarily allowing that script. Record: whether any change VTXO comes back, whether every
  coin is forfeited in one round or several, where the fee lands, what the leave is against what
  went in, and whether boarding outputs are taken too. **If a sweep can come back in two rounds,
  C1's "returns the group nothing" is wrong for the first of them** and the policy rule needs a
  different anchor.
- **K2. The operator's real minimum change,** on mainnet and signet, over HTTP with no coins.
  `MIN_CHANGE_SAT` is our own guess; the note that set it only ever saw a refusal at 12 sats. If the
  true figure is 500, the wedge is narrower than this note says, and if there is no such figure the
  whole deadlock is ours and C13 below is the fix instead.
- **K3. What a round that returns the group nothing costs,** against `maxRoundFeeSat`. A closing
  payout of a 4,048-sat wallet is only worth proposing if the fee leaves `MIN_ONCHAIN_PAYOUT_SAT`
  behind.
- **K4. Does the operator take a leave to every address type** a destination may be -- P2WPKH, P2TR,
  P2SH, P2PKH -- when it is the round's only output to a stranger? S2 of the payouts note asked this
  for an ordinary leave and never ran.

**Done when** each spike has its numbers here, and the owner has answered question 1 with K2 and K3
in front of them.

### Phase 1: the policy signs a closing leave, once (policy)

Pure, in `ArkPolicy`, with nothing filling the new budget, so no device behaves differently.

- `AuthorisedLeave` gains `closing: Boolean`, or a sibling `AuthorisedClosure(id, amountSat,
  maxFeeSat)` in a map of its own. A map of its own is preferred: it cannot be reached by a typo in
  the exact-amount branch.
- In `checkRound` (`ArkPolicy.kt:559`), a leave to a closing script is accepted when **all** of:
  `leave.value` is in `[amountSat − maxFeeSat, amountSat]`; `ownOut == 0` (`ArkPolicy.kt:530`); it
  is the round's only approved leave; `forfeited` equals the wallet's whole unspent by this device's
  own ledger; and `leftIn` has not already given this authorisation to another commitment.
- `Effect.leaves` carries it as it does an ordinary leave, so the co-signer files what it gave.

**Tests,** in `ArkPolicyJvmTest`, against fixtures built by the fork's
`round/group_policy_fixtures_test.go` from a real `sweepAll` intent (K1's shape): a leave one sat
under the floor, one sat over the ceiling, a closing round that also returns the group a VTXO, one
that forfeits all but one coin, two closing leaves in a round, a closing leave beside an ordinary
one, the same authorisation in a second commitment, and a closing leave to a script one byte off.

**Done when** the fixtures pass and refuse as listed, and the reading has found nothing open.

### Phase 2: the authorisation, the abandonment, the rules, the ledger (policy)

- **`GroupWavelengthClosingPayoutEvent`** (C7), `read` and `template`, with the other three `read`s
  returning null for it by test.
- **`GroupWavelengthAbandonmentEvent`** (C4), kind 30383, member-signed, read only from the member
  it names; `GroupWavelengthEvents.ABANDONMENT` and its place in `WALLET_TRAFFIC`.
- **`GroupWavelengthPayouts.refuseClosing`**, beside `refuseOnchain`
  (`GroupWavelengthPayouts.kt:224`): every W3 rule that still applies -- the destination is the
  member's newest, has an onchain address, the address matches byte for byte and is no other
  member's -- then C3's per-member test, C5's two ceilings, C6's remainder, C10 and C11.
- **`GroupWavelengthPayouts.approvedClosures`**, beside `approvedLeaves`, filling Phase 1's map from
  approved, unexpired closing payouts that still fit this device's own balances.
- **The ledger:** `GroupArkLeave` (`GroupArkCoSigning.kt:85`) gains `closing: Boolean`, and
  `leftWith` charges the proposer `amountSat + feeSat` from it. One migration, schema 45
  (`DedunDatabase.kt:230` is at 44).
- **`ProposedEvent.summarize`:** *Empty the Wavelength wallet to @bob: up to 4,048 sats onchain to
  `bc1q…wqac`, at most 1,000 in fees. Takes 12 sats neither @alice nor @bob can be paid.* The
  address in full on `FrostSigningScreen`, with the date the member signed it, as an ordinary payout
  already does.

**Tests:** `GroupWavelengthPayoutsJvmTest` gains each refusal in C3, C5, C6, C10 and C11; a member
whose balance is one sat over `MAX_STRANDED_SAT`; the same member with an abandonment covering it;
an abandonment for less than the balance; an expired one; one signed by somebody else's key; a
remainder over `MAX_ABSORBED_SAT`; and every balance at zero once the leave row is filed. A
migration test.

**Done when** two devices in `jvmTest` approve a closing payout, each co-signer signs the round
once, refuses it in the next, and refuses one proposed for a wallet where any member is still
payable.

### Phase 3: the runner closes it

- `GroupWavelengthDaemon.closeTo(address, maxFeeSat, note)` through `SendRequest.Onchain(address,
  sweepAll = true, note, maxFeeSat)`, beside `sendOnchain` (`GroupWavelengthDaemon.kt:188`).
- `GroupWavelengthRunner.payApprovedClosing`, beside `payApprovedOnchain`
  (`GroupWavelengthRunner.kt:435`): the one approved, unexpired closing payout with no leave row
  here and none in the operator's last report, once a run, in its own job. Never one that is not
  approved, for the reason `payApproved` gives.
- **A dead round** holds the coins until the term ends, as a payout's does. The row says *Held until
  the next operator*, with the wallet screen's nomination beside it.
- **Confirmed:** the watcher already follows the commitment to `settleDepth`; when it confirms, the
  row is final, the transcript line is written and the wallet is empty.

**Tests:** in `GroupWavelengthRunnerJvmTest`: one approval asked once across restarts, an unapproved
one never asked, an expired one never asked, a handover mid-close leaving the next operator one try,
and a deposit arriving between approval and round leaving the round refused rather than paid.

**Done when** the fake daemon is asked exactly once per approval in every test, and never otherwise.

### Phase 4: the screens

- **The member's Wavelength row** gains *Empty the wallet to this address* under the two payout
  buttons, shown only when a closing payout is what is left: the member is payable, everybody else
  is not, and `closesWallet(chain)` is on.
- **It always says what it takes from whom,** before anyone proposes: *Pays @bob 3,048 sats onchain
  and empties the wallet. Takes 4 sats of @alice's and 8 of @bob's, which neither can be paid, and
  up to 1,000 sats in fees.* Someone else's money is in that number and the screen never rounds it
  away.
- **A reason when it cannot go:** *@bob hasn't given an onchain address, so they cannot be the one
  to empty the wallet* (C8); *@alice can still be paid their 500 sats -- they should pay out first*;
  *@alice's 500 sats need their word before anyone can take them*, with the way to ask.
- **On a member's own row, their own word:** *Give up my remaining 8 sats*, with a dialog saying it
  cannot be undone and that whoever empties the wallet gets them, writing C4's event.
- **The life of it,** on the row and in the wallet's activity: waiting for signatures, approved,
  waiting for a round, in a round, emptied; or expired, or held until the next operator.
- **Transcript lines** under `WAVELENGTH_TYPES`: approved, *emptied the Wavelength wallet to @bob,
  3,048 sats, taking 12 sats nobody could be paid*, expired, held, and *@alice gave up 4 sats*. Each
  written once, by the device that saw it.
- Strings in `strings.xml` beside `strings.xml:1342`, where the existing payout rows are,, sentence
  case, format arguments and not `"${…}"`; spacing from `MaterialTheme.spacing`, colour from a role,
  nothing under 48dp, `ScreenStateTransition` around the row's states.

**Tests:** `GroupPayoutsScreenJvmTest` for each reason the offer is absent and each reason it is
refused, at phone width and 1280dp; a view-model test that what it proposes never exceeds the
wallet, including at the floor and with a member one sat over `MAX_STRANDED_SAT`; the transcript
lines once each.

**Done when** `m3Audit` is clean and every state of the row has a test.

### Phase 5: signet, by hand

A test build with signet in `NETWORKS`, four devices, at least two of them phones.

- Three members deposit; two are paid out over Lightning, leaving the residue this note is about.
- The third empties the wallet to a P2TR address. Every balance goes to zero on every device, the
  wallet reports empty, and the transaction is linked here.
- **Thefts, refused,** with a patched daemon: a closing leave a sat under its floor; one that
  returns the group a change VTXO; one that leaves a coin unforfeited; the same authorisation in a
  second round; a closing payout proposed while a member is still payable; one proposed by a member
  whose own balance is the smaller. Every device refuses each, with the reason quoted here.
- **An abandonment:** a member with a payable balance gives it up, and the wallet closes to somebody
  else. Then the same with the abandonment expired, refused.
- **A dead round:** the operator's phone killed after forfeits, the close held, a new operator
  nominated, the close paid.

**Done when** every row above has a transaction or a quoted refusal in this note.

### Phase 6: mainnet, and the other notes

- `closesWallet` on mainnet, **by the owner's decision**, under the 100,000-sat cap.
- `group-wavelength-payouts.md`: the last row of its risks table stops saying nothing can take the
  last of it, and points here. `payouts-only.md`: D1 gains the one exception in the app, named and
  bounded. `group-wavelength-wallet.md`: its census line. The docs README gets this note's row.

**Done when** the owner has decided the gate and the notes agree with the code.

## Order

| phase | depends on | touches | rough size |
|---|---|---|---|
| 0 spikes | signet coins, the operator | signet, nothing in the app | 2 days |
| 1 the closing leave | 0 (K1) | **policy** | 3 days, plus a reading |
| 2 authorisation, abandonment, rules | 1 | app, **policy**, schema | 4 days |
| 3 the runner | 2 | app | 1 day |
| 4 screens | 2 | app | 3 days |
| 5 signet by hand | 3, 4 | signet, phones | 2 days |
| 6 mainnet | 5, the owner | app, docs | the owner's call |

Phases 3 and 4 run side by side after 2. The estimates are as unreliable as the two notes before
this one said theirs were, and for the same reason: what took the time was always the real server.

## Risks

| risk | why it matters | what to do |
|---|---|---|
| `sweepAll` returns change after all | C1's anchor is gone and C2's range stops being safe | K1 before Phase 1, and the fixture from a real intent |
| a deposit lands between approval and round | the round pays more than was approved, or is refused at the worst moment | the ceiling refuses it and the close is proposed again; C11 keeps other payouts out of the window |
| a member's dust is not dust | one member takes another's real balance with a quorum that did not look | C3's test is `payable == null` on each device's own figures, never the proposer's `absorbs` rows |
| a large unattributed surplus | whoever proposes a close takes a refund that should have gone back to its member | C6 counts it against `MAX_ABSORBED_SAT`, so it refuses instead |
| an abandonment signed under pressure | a member talked into giving up a real balance | `at_most_sat` and an expiry bound it; the transcript names it; the dialog says it cannot be undone |
| the fee eats the close | 4,048 sats in, 1,000 out | K3, and the screen refuses a close arriving under `MIN_ONCHAIN_PAYOUT_SAT` |
| older devices in the room | the close cannot gather a quorum, with nothing saying why | W10's line on the approval screen, which this type inherits |
| `MIN_CHANGE_SAT` is our own guess | we may be building a way around a wall we put up | K2 asks the operator; if there is no minimum, C13 is the whole fix |
| the member who closes has no onchain address | the one member left is the one who cannot do it | C8, said on the row; the exit path is then the only way out |

## Questions, for the owner

1. **The closing payout, or a 30341 assignment from the Wavelength ledger?** The exit path above
   works today, with no policy change, for two fees instead of one. This note plans the closing
   payout because that is what was asked for. Both notes already owe the assignment.
2. **Is C3 acceptable at all?** It is the first thing in this app that pays a member money that is
   not theirs. The argument is that nothing can pay the owner, so the alternative is abandoning it
   to the operator at batch expiry. If the answer is no, C4's abandonment is the only route and a
   wallet whose dust-holders have left the group can never be emptied.
3. **`MAX_STRANDED_SAT` at the Lightning floor, ten sats?** Tighter is safer and wedges more
   wallets. The onchain floor, about 2,000 sats on mainnet, would unwedge nearly everything and is
   far too much of someone else's money to move on a quorum's say-so.
4. **Does `closesWallet` open on mainnet before Phase 5?** Its two neighbours did. This mattered
   while 4,048 sats were stuck on mainnet; the ceiling has taken 3,017 of them out, so the answer
   can now be the careful one without anybody waiting on it.
5. **Is this note still worth building?** Its room is unwedged and its last 1,000 sats are under
   what a round costs, so nothing it would be run on today survives its own floor. The case for it
   is the general one: residue that is large rather than dust, which C6 bounds and refuses rather
   than sweeps. If that case is not real enough to build for, the honest move is to say so here and
   stop, rather than leave a plan that reads as owed work.
6. **Signet coins for Phases 0 and 5,** by hand, into fresh wallets. The payouts note asked for
   these for its own Phases 0 and 7 and never got them, which is why four of its rows still say
   *not run*.

## What this plan does not do

- **Pay anybody but a member,** or anywhere but their signed destination. No typed-in address.
- **Empty the wallet over Lightning.** C8: the SDK cannot, and this note does not ask it to.
- **Fix the dust at its source.** Charging a Lightning payout the fee it was quoted rather than the
  reserve, and handing the pro-rata remainder to somebody, would stop most residue being made. Both
  are changes to `balancesIn` that every device has to agree on to the sat, and both deserve their
  own note. This plan empties a wedged wallet; it does not stop wallets wedging.
- **Assign the coins an exit brings out.** Still owed, still worth its own note, and still the thing
  that would make the exit path a complete answer.
- **Close the wallet itself.** A closing payout empties it. Stopping the daemon, the election and
  the statement is `STOPPED` and the wallet screen's business.

## Appendix: what was considered and rejected

| considered | why not |
|---|---|
| **cap the payout at what the wallet can spend, rather than emptying the wallet** | not rejected: never considered, and now **built** as [group-wavelength-spendable-ceiling.md](./group-wavelength-spendable-ceiling.md), which is what unwedged the room above. It leaves the operator's minimum change behind for good, so it does not empty a wallet and does not replace this note. That it was missed is why this note planned fifteen days of policy work for a deadlock three days of arithmetic could open |
| **C13: lower `MIN_CHANGE_SAT`** | if K2 finds the operator has no such minimum, this is the whole fix and the rest of the note is unnecessary. If it has one, the constant is the operator's and not ours to lower: the daemon refuses the send before anything is signed |
| pay the dust to the operator as extra fee, so change is zero | the fee is the round's, quoted by the operator, not a number the group may inflate. The SDK returns change it did not need |
| let a payout overdraw by the dust, with no new type | it is the same rule as C3 with none of its ceilings, reachable by every ordinary payout, in the branch that runs on every payout. A separate type cannot be reached by a typo |
| a quorum's approval as the member's consent | FROST's aggregate signature does not say who signed. A quorum proves enough members agreed, never that *this* member did, and C4 needs exactly that |
| split the dust between everyone left | there is nobody left: the point of a closing payout is that it is the last one. Splitting 12 sats three ways pays nobody anything |
| hold the close until every dust-holder signs an abandonment | the common case is members who were paid months ago and have no reason to come back. The screenshot's @alice and @bob are both of them |
| a closing payout per member, taking their share | that is an ordinary payout, and it is what the operator's minimum change refuses. The whole wallet in one leave is the only shape that leaves no change |
