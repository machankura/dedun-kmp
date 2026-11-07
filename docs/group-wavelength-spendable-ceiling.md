# Paying out what the wallet can spend: the Wavelength wallet's spendable ceiling

How @Bob, last out of the group's Wavelength wallet with 4,036 sats of a 4,048-sat wallet, is paid
3,017 sats today instead of being told to deposit 988 more. The wallet keeps the 1,000 sats its
operator insists on as change, and nobody's money moves but @Bob's.

**Not built. Written on 8 November 2026 against `16a9ed87`,** from the same mainnet room as
[group-wavelength-closing-payout.md](./group-wavelength-closing-payout.md). Nothing here has run,
and every file and line it names is as it stood that day.

**What this is, in one line.** A payout is offered for the member's whole balance and refused when
that would leave the operator too little change. This note offers the *most the wallet can spend*
instead, which is the member's balance or `inTheWallet − MIN_CHANGE_SAT`, whichever is smaller, with
the fee's limit taken off inside it. It adds no authorisation type, no event, no schema column and no
policy rule. It is one pure function and the two offers that call it — and on the way it fixes a
live bug, because the branch it deletes offers a sole member a payout that is usually unpayable
(B2).

### Where this stands

| phase | state |
|---|---|
| 0 to 4 | not started |

## The deadlock, and why it is cheap to break

The room on 8 November 2026, its Wavelength wallet holding 4,048 sats:

| member | balance | destination |
|---|---|---|
| @Alice | 4 sats | a Lightning address, **no onchain address** |
| @Bob (`kgothatso@blink.sv`) | 8 sats | a Lightning address and `bc1q7lxnf…dz6v` |
| @Bob (`catalyst@54052.co.za`) | 4,036 sats | a Lightning address and `bc1qnghz…wqac` |

Both offers on the 4,036-sat row are refused, each saying:

> Paying @Bob Dedun out over Lightning would leave less change in the Wavelength wallet than its
> operator accepts. It can go once someone deposits 988 sats more.

Spending 4,036 of 4,048 leaves 12 sats of change, `MIN_CHANGE_SAT` is 1,000, so
`GroupWavelengthRollout.changeShortSat` (`GroupWavelengthRollout.kt:76`) reports the wallet 988
short and both offers stand down. The app's only suggestion is a deposit into a wallet nobody can
get money out of.

**But the operator's rule is a floor on change, not a ban on a smaller spend.** `changeShortSat`
already says so in its own test: *"no change at all is fine"*, *"and change of the operator's
least"*. The legal spends are the ones leaving zero change or at least 1,000; only the band from 1
to 999 is refused. The app picks a spend in that band and then gives up, when a legal spend 988 sats
smaller was available the whole time.

Of the two legal shapes only the second is reachable from here. Aiming at zero change means naming a
spend to the sat, and a payout cannot: its fee is a limit quoted in the round, not a figure. **So
this note aims at 1,000 sats of change and never at none** — B2, which is also where the branch that
aims at none today turns out to be a bug.

So the fix is not to empty the wallet. It is to **stop asking for a spend the operator will not
take**, and ask for the largest one it will.

### What @Bob gets, on each rail

| rail | spend | arrives | fee | change left |
|---|---|---|---|---|
| Lightning, today | 4,036 | — | — | 12, **refused** |
| Lightning, capped | 3,048 | **3,017 sats** | 31 limit | 1,000 |
| onchain, today | 4,036 | — | — | 12, **refused** |
| onchain, capped | 3,048 | **1,048 sats** | 2,000 limit | 1,000 |

Lightning pays nearly three times as much, because its fee reserve is one percent of the amount
(`LightningBalance.payable`, `LightningBalance.kt:57`) while an onchain payout reserves the policy's
whole `maxRoundFeeSat`, 2,000 sats by default. @Bob has a Lightning address, so the rail that
unwedges this room is the Lightning one. The onchain figure clears
`MIN_ONCHAIN_PAYOUT_SAT` (`GroupWavelengthRollout.kt:61`) by 48 sats, which is close enough to be
worth a test of its own (B6).

Afterwards the wallet holds exactly 1,000 sats: 988 of @Bob's, 8 of his other row's, 4 of @Alice's.
Nobody can be paid again, and **that is the end state this note delivers** — the last 1,000 sats are
the closing payout's business, not this note's. B8 says what the screen must then say instead of
asking for a deposit again.

## Why this needs no new rule anywhere

Four things were read to make sure a payout smaller than the member's balance is already legal. All
four are, and this is the whole reason the note is four phases instead of seven.

**The policy already signs it.** `checkRound` (`ArkPolicy.kt:564`) accepts a leave whose value is
exactly `approved.amountSat` to an authorised script, bounds the round's fee by `maxRoundFeeSat` and
again by the payout's own `maxFeeSat`, and constrains what comes back to the group (`ownOut`) only
against `capSat`. **A bigger change VTXO is not something the policy has an opinion about.** The
comment above that block — *"Exactly, because the daemon fixes a payout's leave and takes the fee
from the change: a smaller one is the difference taken"* — is about a leave smaller than the
*approval*, which this note does not do. The approval itself is smaller.

**The signers already accept it.** `refuseAgainstBalance` (`GroupWavelengthPayouts.kt:250`) refuses
when `cost > balance.availableSat`. A capped payout costs *less* than the balance, so it passes
unchanged, and no co-signer needs a new build to take one.

**The authorisation already carries it.** `GroupWavelengthOnchainPayoutEvent.Payout.amountSat` is
any value in `1..MAX_SAT`. Nothing on the wire says a payout is a whole balance.

**The co-signer already pays it.** `approvedLeaves` (`GroupWavelengthPayouts.kt:187`) gates on
`bearsWhatsOpen`, which is `heldSat >= openSat`; a capped payout's `openSat` is under the member's
share, so it holds.

**And the ledger already clears it.** `balancesIn` (`GroupWavelengthPayouts.kt:145`) charges the
member `amountSat + feeSat` from the leave row, leaves the remainder as their balance, and
re-computes every share against the smaller `inTheWallet`. 4,036 − 3,048 = 988, and 988 + 8 + 4 =
1,000, which is what the wallet holds. The arithmetic closes with no help.

**What this means for the risk of the change.** The cap is a *proposer-side* number. No device
checks it, because no device checks the change rule today either — the daemon does, before anything
is signed. So the worst case for a cap computed wrongly is the one the current gate exists to avoid:
the daemon refuses the send and a quorum's approval expires unused in two days. **It is not a path
by which anyone loses a coin.** That is a materially different risk profile from the closing
payout, which changes what a device signs.

## Read these first

- [group-wavelength-payouts.md](./group-wavelength-payouts.md): W5 to W9. This note changes nothing
  in them; it changes only what the screen asks for.
- [group-wavelength-closing-payout.md](./group-wavelength-closing-payout.md): the sibling plan, for
  the last 1,000 sats this one leaves. B9 and the appendix say how the two fit.
- [payouts-only.md](./payouts-only.md): D1 and D6. Unlike the closing payout, this note does not go
  near D1: every sat paid is the member's own.

## Decisions

**B1. The ceiling is the wallet's, and it is one pure function.** Beside `changeShortSat`:

```kotlin
/**
 * The most a spend of [balanceSat] may take out of a wallet holding [inWalletSat]: enough left behind for the least change the operator
 * accepts, and never more than the member has. 0 where nothing can go, which is the balance's to explain and not this. A spend that
 * leaves no change at all would also be taken, but nothing here can ask for one -- a fee is a limit and not a figure, so what is really
 * left is only known after the round (B2).
 */
fun spendableSat(inWalletSat: Long, balanceSat: Long): Long =
    (inWalletSat - MIN_CHANGE_SAT).coerceAtLeast(0).coerceAtMost(balanceSat.coerceAtLeast(0))
```

**B2. Nothing here may aim at no change, and the branch that does today is a bug.** The operator
takes a spend leaving no change as readily as one leaving 1,000, and `changeShortSat`'s own test says
so. It does not follow that the app can ask for one. **What leaves the wallet is `amount + fee`, and
the fee is a limit, not a figure** — it is quoted in the round. Aim at zero change and every round
that comes in cheaper than the limit lands in the forbidden band instead: a 3,000-sat wallet paid at
`amount = 1,000` leaves 999 sats when the round costs 1,001, and the daemon refuses it.

That is not hypothetical, and it is not this note's doing. Today `wavelengthOnchainOffer` reaches
`changeShortSat`'s `change <= 0` branch exactly when the member's balance is the whole wallet,
offers `balance − maxFeeSat`, and **the send then succeeds only if the round happens to cost the
full limit.** An exhaustive walk of every wallet up to 5,000 sats against
every fee up to a 2,000-sat limit finds no unsafe offer under the ceiling above and a million under
that branch. A sole member is today offered a payout that usually cannot be paid.

So the ceiling has one branch, aimed at `MIN_CHANGE_SAT` and never at zero, and **a member whose
balance is the whole wallet is offered 1,000 sats less than they hold** rather than an amount that
works only by luck. The last 1,000 sats need `sweepAll`, where the daemon picks the spend itself and
there is no change to get wrong — which is to say they need the closing payout, consistently with
every other sat this note leaves behind.

**B3. The fee reserve is inside the ceiling, and the ceiling is checked at the worst fee.** What the
operator sees leave the wallet is `amount + fee`, so the ceiling bounds that sum and the amount
offered is the ceiling less the reserve. The binding case is the **largest** fee, because
`change = inWallet − amount − fee` grows as the fee shrinks: a ceiling that leaves `MIN_CHANGE_SAT`
at `maxFeeSat` leaves more at every fee under it, and more is fine. **This is the whole reason B2's
target has to be `MIN_CHANGE_SAT` and not zero** — only an inequality survives a fee nobody knows
yet. An equality has to be hit exactly, and nothing here can.

**B4. `changeShortSat` and `LeavesTooLittle` are deleted, not kept beside it.** With B1 at both
offers the change is `MIN_CHANGE_SAT` or more by construction, so `changeShortSat` can only ever
return 0 and `WavelengthOnchainOffer.LeavesTooLittle` is unreachable. Deleting it is also what fixes
B2's bug: the unsound `change <= 0` branch goes with the function that holds it, rather than staying
behind as a shortcut back into it. Leaving a dead branch that says *deposit 988 sats more* is how
somebody reinstates the deadlock in a year. The two strings go with them.

**B5. Both rails, one ceiling.** `wavelengthPayable` (`GroupPayoutsViewModel.kt:241`) passes
`spendableSat(…) * 1000` to `LightningBalance.payable` instead of the whole balance, and
`wavelengthOnchainOffer` (`GroupPayoutsViewModel.kt:289`) takes `spendableSat(…) − maxFeeSat` as its
amount. Both are read again inside `payOutWavelength` (`:258`) and `payOutWavelengthOnchain` (`:327`)
to build the proposal, so capping the offer caps what is proposed with no second call site to keep
in step. That is why the ceiling goes in the offer and not in the propose.

**B6. `TooLittle` has to say which bound is binding.** It is reached now when the member's balance
cannot clear `MIN_ONCHAIN_PAYOUT_SAT` plus the reserve, and its string —
*"%1$s sats more and the Wavelength balance is worth paying out onchain"* — asks for a deposit to
that member, which is true. Under a ceiling it is also reachable because the *wallet* is too small,
where a deposit by anyone would do and one to that member alone would not. Two reasons, two strings,
and the wallet's one names the wallet.

**B7. The amount the screen shows is the amount, and it never calls it a balance.** The happy-path
string, `wavelength_onchain_payout_pays_s_s_s_s`, already says *"Pays %1$s %2$s sats onchain to
%3$s…"* and makes no claim about a balance, so it is correct for a capped payout unchanged. What has
to be added is the part that is new and would otherwise be a surprise: **that some of their balance
stays behind**. A member told *pays you 3,017 sats* with 4,036 on their row will ask why, and the
screen answers before they do.

**B8. When the ceiling reaches zero, the screen stops asking for a deposit.** After @Bob's payout the
wallet holds 1,000 sats that the operator will not let go in any spend: `spendableSat(1_000, 988)` is
0, and a deposit does not change that — it raises the ceiling by what it adds, so 988 sats of
deposit would be needed to release 988 sats of balance. Asking for that is the deadlock again. The
row says instead that this is the wallet's last change, that it cannot be paid out on its own, and
points at emptying the wallet. **There is no button here that works, and the screen's job is to say
so plainly rather than offer one.**

**B9. No gate.** `GroupWavelengthRollout` gains nothing. The rails this rides on —
`paysOverLightning` and `paysOnchain` — are already on for mainnet by decision, every rule a device
checks is unchanged, and a device on an older build co-signs a capped payout correctly because it is
an ordinary payout. A gate would only delay the room this note was written for. Question 3.

## Phases

One commit per phase, each compiling and passing `./gradlew :composeApp:compileDebugKotlinAndroid
:composeApp:jvmTest` and `./gradlew :composeApp:m3Audit`. Messages end `(Group Wavelength spendable
ceiling, Phase N)`. **No phase here is a policy phase** — nothing changes what a device signs —
which is the point of the note and should stay true; if a phase starts to need `ArkPolicy`, stop and
read *why this needs no new rule anywhere* again, because something has been misunderstood.

### Phase 1: the ceiling, with nothing calling it

Pure, in `GroupWavelengthRollout`, beside `changeShortSat`, which stays for now so the phase changes
no behaviour.

- `spendableSat(inWalletSat, balanceSat)` exactly as B1 writes it, with B1's comment.

**Tests,** in `GroupWavelengthOnchainPayoutsJvmTest` beside the `changeShortSat` ones:

| `inWalletSat` | `balanceSat` | expected | why |
|---|---|---|---|
| 4,048 | 4,036 | 3,048 | the room this note is about |
| 4,048 | 4,048 | 3,048 | B2: the whole wallet is one member's, and still 1,000 stays |
| 4,048 | 5,000 | 3,048 | a balance over the wallet is the wallet's limit |
| 10,500 | 10,000 | 9,500 | the existing `LeavesTooLittle(500)` case, now spendable |
| 11,000 | 10,000 | 10,000 | already fine, and unchanged |
| 1,000 | 988 | 0 | B8's end state |
| 500 | 400 | 0 | a wallet under the operator's change |
| 0 | 0 | 0 | an empty wallet |

Plus the property the whole note rests on, over `inWalletSat` in `0..5_000`, `balanceSat` in
`0..inWalletSat + 2_000` and **every fee from 0 to `maxFeeSat`**, for each of `maxFeeSat` 0, 500 and
2,000. Wherever the offer is made at all — that is, wherever
`spendableSat(…) − maxFeeSat >= MIN_ONCHAIN_PAYOUT_SAT` — the change left is `MIN_CHANGE_SAT` or
more at every one of those fees. The quantifier over fees is
not decoration: B2's bug is invisible at `maxFeeSat` alone and shows at every fee below it.

A second case pins B2 directly: the old `change <= 0` shape, `amount = balanceSat − maxFeeSat` with
`balanceSat == inWalletSat`, is **unsafe** at some fee under the limit. It belongs in the suite as a
guard, named for what it is, so that reinstating that branch fails a test rather than a mainnet send.

**Done when** the table holds, the property holds over all three fee limits, and the guard fails for
the shape B2 rejects.

### Phase 2: both offers ask for what the wallet can spend

- `wavelengthPayable` (`GroupPayoutsViewModel.kt:241`) passes
  `spendableSat(state.wavelengthInWalletSat, balance.wavelength.availableSat) * 1000` to
  `LightningBalance.payable` in place of the whole balance.
- `wavelengthOnchainOffer` (`:289`) takes `val spendable = spendableSat(state.wavelengthInWalletSat,
  wavelength.availableSat)`, then `amountSat = spendable - maxFeeSat`, and **deletes** the
  `changeShortSat` call and the `LeavesTooLittle` return.
- `WavelengthOnchainOffer.TooLittle` gains what B6 asks for, as a sibling `WalletTooSmall` rather
  than a flag on the existing one: a boolean in a `when` is read wrong, and these two reasons are
  told apart by whoever writes the next string.
- **Delete** `wavelengthChangeShortSat` (`:248`), `changeShortSat` (`GroupWavelengthRollout.kt:76`)
  and `WavelengthOnchainOffer.LeavesTooLittle` (B4), with the two `…leaves_too_little…` strings.
- `state.wavelengthInWalletSat` (`GroupPayoutsViewModel.kt:104`) is summed from settled, unspent
  VTXOs, which is the set `balancesIn` sums for its own `inTheWallet`. **The two have to agree or
  the ceiling is computed against a wallet the ledger does not believe in**; a test asserts that
  rather than a comment claiming it.

**Tests,** in `GroupWavelengthOnchainPayoutsJvmTest`:

- The two existing `LeavesTooLittle` assertions (`:134` and `:136`) become offers, with the amounts
  the table above gives. **These two lines flipping from a refusal to an offer is the whole
  behaviour change of this note**, and the test names should say so.
- @Bob's row end to end, on each rail: `inWalletSat = 4_048`, balance 4,036 → Lightning offers
  3,017 sats with a 31-sat limit, onchain offers 1,048 with 2,000.
- A `Proposing` repository asserting `proposeOnchainPayout` is called with 1,048 and not 2,036 —
  the cap reaching the proposal and not just the label.
- B2: balance 4,048 of a 4,048-sat wallet offers 3,048 and not 4,048 — **a deliberate change to
  today's behaviour**, because today's offer there is payable only if the round costs the full limit.
  The test says that in its name.
- B6: a 3,000-sat wallet with a 3,000-sat balance gives the wallet's reason, and a 2,500-sat balance
  in a 20,000-sat wallet gives the balance's.
- B8: `inWalletSat = 1_000`, balance 988 → no offer on either rail, and the new reason.
- A payout already waiting (`openSat > 0`) still says `Waiting`, ahead of any of this.

**Done when** no test anywhere mentions `LeavesTooLittle` and the room's two rails offer the figures
in this note.

### Phase 3: the screens say what stays behind

- `GroupPayoutsScreen.kt:395` and `:440`, where `LeavesTooLittle` was rendered, are **removed**.
- **Under each offer, when the ceiling bit:** *The wallet keeps 1,000 sats its operator won't let go,
  so 988 sats of @Bob's stays in it.* Only when `spendableSat` came in under the balance — a member
  being paid in full is told nothing, because nothing is being held back.
- **B6's two reasons,** each its own string: the balance's as it reads now, and the wallet's —
  *The Wavelength wallet is too small to pay anyone out onchain and keep the change its operator
  asks for.*
- **B8's end state,** on the row, with no button: *The 1,000 sats left are the least change the
  wallet's operator accepts. They can't be paid out one member at a time -- emptying the wallet is
  the only way they move.* Linked to nothing until the closing payout exists, and then to it.
- Strings in `strings.xml` beside `strings.xml:1348`, where the two deleted ones are: sentence case,
  format arguments and not `"${…}"`, no title case.
- Spacing from `MaterialTheme.spacing`, colour from a role (`onSurfaceVariant` for the held-back
  line, as the sibling lines use), nothing interactive under 48dp. The row's states already sit
  under the screen's `ScreenStateTransition`; no new state is added to it.

**Tests:** `GroupWavelengthOnchainPayoutsJvmTest`'s screen cases, at phone width and 1280dp — the
held-back line present when capped and absent when not, each of B6's two reasons, and B8's row with
no button in it. `./gradlew :composeApp:m3Audit` clean.

**Done when** every path through the row has a test and the audit is clean.

### Phase 4: the room, by hand, and the notes

- **The room this note came from**, on mainnet, on a real phone: @Bob's Lightning row offers 3,017
  sats, a quorum approves it, the operator pays it, and the wallet is left holding 1,000 sats with
  @Bob's balance at 988. **The transaction and the figures are written back into this note.** If the
  real round's figures differ from the table above, the table is wrong and is corrected here.
- **The refusal that matters:** a device on the build before this one is shown the approval and signs
  it, since it is an ordinary payout. If it refuses, B9's "no gate" is wrong and the note needs one.
- `group-wavelength-payouts.md`: the last row of its risks table stops saying nothing can take the
  last of it, and names both this note and the closing payout.
- `group-wavelength-closing-payout.md`: its deadlock section says the 3,048 sats come out first by
  this note, so what it is planning for is the last 1,000 and not 4,048. Its question 4 — whether
  `closesWallet` opens on mainnet early — **loses most of its urgency**, which is worth saying there.
- The docs README gets this note's row.

**Done when** the room's wallet holds 1,000 sats, the transaction is linked here, and the two
sibling notes agree with the code.

## Order

| phase | depends on | touches | rough size |
|---|---|---|---|
| 1 the ceiling | — | one pure function | half a day |
| 2 both offers | 1 | view model, deletions | 1 day |
| 3 the screens | 2 | app, strings | 1 day |
| 4 the room, by hand | 3, a phone | mainnet, docs | half a day |

Three days, against the closing payout's fifteen, and no phase of it needs an adversarial reading or
signet coins. The estimates are as unreliable as the sibling notes said theirs were; the difference
is that none of these phases waits on a server nobody controls.

## Risks

| risk | why it matters | what to do |
|---|---|---|
| **the bug B2 found is live now** | a member whose balance is the whole wallet is offered a payout the daemon refuses unless the round costs the full fee limit, with no reason given that fits | the ceiling removes the branch, and Phase 1's guard test keeps it removed. Worth saying to the owner ahead of the rest: it is a refusal nobody has a message for |
| `MIN_CHANGE_SAT` is our own guess | the ceiling is computed from a number no SDK states, so it may be cutting 1,000 sats off every payout for nothing | K2 of the closing-payout note asks the operator. **This note is safe either way**: if the true floor is lower the ceiling is conservative, if there is none the ceiling is unnecessary but harmless. It is the closing payout that depends on the answer |
| the wallet changes between the offer and the round | a deposit or another payout moves `inTheWallet`, so the cap was computed from a stale figure | a deposit only raises the ceiling, so the approval stays legal; another payout lowers it, and W9 already refuses a second payout while one is open |
| the onchain rail clears `MIN_ONCHAIN_PAYOUT_SAT` by 48 sats | a slightly bigger `maxRoundFeeSat` and the onchain row goes back to refusing, for a reason that reads like the old one | B6's wallet reason says it is the wallet and not the balance; the Lightning rail is the one that pays here, and the table says why |
| somebody reinstates `changeShortSat` | the deadlock comes back in a helper nobody re-reads | B4 deletes it rather than keeping it, and Phase 1's property test fails if a caller stops using the ceiling |
| a member is confused by being paid less than their row says | they think sats were lost | B7's line, on the row, before they propose |
| the last 1,000 sats are parked for ever | they still need the group's devices online every few days, or the operator takes them at batch expiry | renewal inside the free window costs no fee (`GroupWavelengthTerms`), so this is an obligation and not a bleed. The closing payout is what ends it |
| a proposer caps lower than they had to | a payout smaller than the ceiling, by malice or a stale figure | **already true of every payout today**: the authorisation carries any amount and signers check only the ceiling. This note does not widen it, and a smaller payout leaves the rest of the balance where it was |

## Questions, for the owner

1. **Is a payout that leaves part of the balance behind acceptable without anything new on the
   wire?** It is what the code already permits, and the note's case is that permitting it is correct
   rather than accidental. The alternative is a `max_spend_sat` tag on the authorisation so a signer
   can see the ceiling the proposer used — which buys nothing, since a signer that recomputed it
   would refuse honest payouts whenever its own VTXO set lagged by one coin.
2. **This note, the closing payout, or both?** They are not alternatives: this one frees 3,017 of
   4,048 sats in three days with no policy change, and the closing payout frees the last 1,000 in
   fifteen with one. **Doing this first makes the closing payout less urgent and no harder.** Doing
   only the closing payout leaves the room wedged for a fortnight.
3. **B9, no gate at all?** Every rule a device checks is unchanged and an older device signs one
   correctly, so there is nothing for a gate to protect. Phase 4 tests that claim on a real device
   before the note is closed.
4. **Was today's sole-member offer ever paid?** B2 says it is payable only when the round costs the
   full fee limit. If somebody has seen one go through on mainnet, the operator's behaviour is not
   what `MIN_CHANGE_SAT` describes and K2 of the sibling note matters more than this note does.
5. **B8's wording, when 1,000 sats are stuck.** The row has to say that money is there and cannot be
   moved, without reading as a bug. The draft above is the best this note has; the owner has better
   words for the people in that room.

## What this plan does not do

- **Empty the wallet.** The last `MIN_CHANGE_SAT` stays, by construction. That is
  [group-wavelength-closing-payout.md](./group-wavelength-closing-payout.md)'s subject, and this
  note's end state is its starting point.
- **Touch anybody else's sats.** Every sat paid is the member's own, which is why D1 of
  `payouts-only.md` is not in question here and is the closing payout's hardest problem.
- **Let a member choose an amount.** The offer is the most that can go, as it is today. An amount
  field is `group-send-an-amount.md`'s business and that note is superseded.
- **Change what a device signs.** No authorisation type, no event, no tag, no policy rule, no
  schema migration. If a phase needs one, the plan is wrong.
- **Fix the dust at its source.** The unspent fee reserve on a Lightning payout and `balancesIn`'s
  floor division keep making residue; this note stops the residue wedging a wallet, and does not
  stop it being made. Both deserve their own note, as the closing payout says.
- **Pay anybody but a member,** or anywhere but their signed destination.

## Appendix: what was considered and rejected

| considered | why not |
|---|---|
| **lower `MIN_CHANGE_SAT`** | it is the operator's number, not ours. The daemon refuses the send before anything is signed, so lowering it moves the refusal later and makes it harder to explain. C13 of the closing-payout note has the same answer |
| cap in `payOutWavelength` and `payOutWavelengthOnchain` rather than in the offers | the screen would then show one figure and propose another, and the two would drift the first time either was edited. B5 puts it where both read it |
| keep `changeShortSat` beside the ceiling, for safety | it can only return 0 once the ceiling is in place, so it is a branch that never runs and a message that cannot be true. B4 |
| **a second branch letting a sole member take the whole wallet** | B2: it aims at no change, and the fee is a limit rather than a figure, so every round cheaper than the limit lands in the band the operator refuses. It is what the code does today and it is a bug. Zero change needs `sweepAll`, which is the closing payout |
| ask the daemon what it will spend, instead of guessing | there is no such call, and `MIN_CHANGE_SAT` exists because the SDK does not say it. K2 of the sibling note is the nearest thing |
| propose the full balance and let the daemon take what it can | the daemon refuses the whole send; it does not shave. And a payout whose amount the operator chose is exactly what W5 and the policy's exact-leave rule exist to prevent |
| a `max_spend_sat` tag so signers can check the ceiling | a signer recomputing it from its own VTXO set would refuse honest payouts whenever that set lagged, which is the common case right after a round. Question 1 |
| wait for the closing payout and do nothing here | fifteen days against three, for a note that needs an adversarial reading, signet coins and a decision about paying members money that is not theirs. This frees three quarters of the wedged sats first |
