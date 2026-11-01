# Sending an amount from the group's wallet

*Send from the group's wallet* today sends everything the group has confirmed, to
one address, less the fee. This note plans the other half: a member types an
amount in sats, the rest comes back to the group as change, and the other members
can see that the change is the group's before they sign.

Read [group-chain-wallet.md](./group-chain-wallet.md) first, Phases 8 and 10 in
particular. This plan is that note's third rollout gate, and it starts from the
code as it stands at `96055da5`.

**Superseded by [payouts-only.md](./payouts-only.md)**: the group's own wallet now signs
only payouts, which have no change, so an amount is no longer something it sends. The
builder below still serves a guardian's spend from a descendant's wallet.

**Built, Phases 1-6**, except Phase 6's by-hand walkthrough on regtest; Phase 7
is the first release. Written against `GroupSendScreen`, `GroupSendViewModel`,
`GroupSpendBuilder`, `GroupSpend` and `GroupWalletRollout` as they were at that
commit, and each phase says where the build chose differently. Phase 3 was
committed before Phase 2, as Phase 2 asks.

---

## What is already there

Most of this feature has been built and switched off. The plan is mostly about
why it is off and what has to be true before it can be switched on, not about a
new screen.

| piece | where | state |
|---|---|---|
| an amount, largest coin first, change to the next unused change address, dust change goes to the fee | `GroupSpendBuilder.send` | built, tested in `GroupSpendBuilderTest` |
| change counts as change only if its claimed derivation reproduces its script | `GroupSpend.check`, check 5 | built, runs on every member's device before signing |
| the approval screen shows every output, with change labelled as change | `FrostSigningScreen.WhatIsBeingSpent` | built |
| *Send everything* switch, *Amount in sats* field | `GroupSendScreen`, `GroupSendViewModel.amountField` / `sendEverything` | built, behind the gate |
| the gate | `GroupWalletRollout.SEND_AN_AMOUNT = false` | closed |
| the preview says how much change comes back | `SendPreview` | built |

The gate's own kdoc gives the lift condition: *"send-an-amount has had a release
with nobody reporting a mislabelled change output."* While the gate is a single
`const` that is false on every network, nobody can send an amount, so nobody can
report anything. **The condition cannot be met as the gate is written.** Phase 0
settles it by opening the gate on every network at once, and Phase 2 removes it.

Reading the gated code turned up five things to fix before the field is shown
to anyone:

1. **A decimal is silently multiplied.** `preview()` reads the amount with
   `filter { it.isDigit() }`. Someone who types `21000.5` gets 210,005 sats, and
   someone who types a bitcoin amount, `0.0005`, gets 5 sats. The first is a
   tenfold overpayment that the preview shows only as a number.
2. **An amount too long for a `Long` disappears.** `toLongOrNull()` returns null,
   `preview()` returns null, and the button stays disabled with no reason given.
3. **Two open spends with change pick the same change address.**
   `nextChangeIndex` is the first change address Electrum has not seen used, and
   a proposal waiting for signatures has not used it yet. Both proposals pay
   change to `…/1/0`. Nothing is lost, but the address is reused, and change is
   exactly the output whose job is to not be linked to anything.
4. **"Nothing to spend" is shown when the change branch has not loaded.**
   `send` returns `NothingToSpend` when `nextChangeIndex` is null. That happens
   before Electrum has answered for the change branch, while the group may hold
   plenty.
5. **Dust change becomes fee without a word.** Phase 8 of the wallet note said
   *"the remainder goes to fee, and the screen says so"*. The builder does it
   and the screen does not: the preview shows a fee a few hundred sats higher
   than the fee rate implies, and no reason.

---

## Phase 0: three decisions, before any code

**Half a day.** They change what Phases 2 and 4 build, so they come first.

1. **Where the gate opens first.** **Decided: on every network at once, relying
   on the mainnet cap.** `MAINNET_CAP` already holds any one spend proposed from
   this device to 100,000 sats, and that limit does not depend on the gate, so
   the most a mislabelled change output can put at risk on mainnet is one capped
   spend. Three things stand where the gate stood. The cap bounds the amount.
   Check 5 runs on every signer's device. And Phase 3's end-to-end test is the
   lab version of the report the gate was waiting for. The other option was
   test networks first and mainnet after a release, the way `capFor` treats
   networks. It was turned down because it delays mainnet by a release and buys
   less than it looks, since the cap already makes the first mainnet spends
   small.
2. **Whole sats only.** No bitcoin unit, no fiat, no decimal point. `Sats.kt`
   already gives the reason for the wallet: comparing two integers is easier
   than reading a decimal point eight places in. An input that contains a
   decimal separator is refused with a message, never rounded or stripped.
3. **"Send everything" stays a switch, not a *Max* button.** A *Max* button
   would fill the amount with `total − fee`. That value changes when the fee rate
   changes, and it gives the group change it does not want when a coin confirms
   between typing and pressing. The switch builds `sendAll`, which has no
   change at all. That is the one difference between the two paths, and it should
   stay visible in the form.

---

## Phase 1: the amount is sats, or says why not

**One day. Built.**

A parser and an output transformation in `text/Sats.kt`, next to `grouped()`,
so that reading and writing an amount are in one file:

```kotlin
sealed interface SatsInput {
    data object Empty : SatsInput
    data class Sats(val amount: Satoshi) : SatsInput
    data object NotWhole : SatsInput     // a decimal separator
    data object TooLarge : SatsInput     // more than 21,000,000 bitcoin
    data object Unreadable : SatsInput   // letters, or grouping that groups nothing
}

fun parseSats(text: CharSequence): SatsInput
val GroupedSats: OutputTransformation
```

- **Grouping separators are allowed and dropped:** `,`, space, no-break space,
  narrow no-break space, `'`, `_`. People paste `40,000` and `40 000`, and the
  second is how a South African locale groups thousands.
- **`.` is refused, and so is `,` followed by one or two final digits**
  (`40,5`, `40,50`), because in that locale those are decimals. `40,000` is
  grouping.
- **Grouping has to group.** The first group has one to three digits and every
  later group exactly three, or the input is `Unreadable`: `40,0000`, `4 0`,
  `1234,567`. Guessing is how the bug above got in, and a reason that is not
  about decimals deserved its own case rather than being filed under
  `NotWhole`.
- **ASCII digits only.** Kotlin's `isDigit` accepts the digits of every script,
  and so does the JVM's `toLong`.
- **At most `MAX_SATS`**, 2,100,000,000,000,000. Significant digits are counted
  before `toLong()`, so a forty-digit paste is `TooLarge`, not an overflow.

**On the field, display only.** The plan said an `inputTransformation` would
store only digits. Built that way, a pasted `40,5` would have its comma
stripped and become 405, which is the same bug moved to the keyboard. So the
field holds exactly what was typed, `parseSats` is the only thing that reads
it, and `GroupedSats`, an `outputTransformation`, shows the text grouped as
`grouped()` writes it, but only when the text is plain digits. Anything else
is shown as it was typed, because regrouping `40,5` would show a number it is
not.

`GroupSendViewModel.amount()` is `parseSats(amountField.text)`, and `preview()`
returns `GroupSpendBuilder.Result.InvalidAmount(input)` for anything that is
neither `Sats` nor `Empty`. It does this before it checks for an address or a
fee rate, because a wrong amount is not an incomplete form. Until Phase 4
moves amount errors onto the field, `SendPreview` shows the three reasons in
the preview line.

**Tests**: `commonTest/…/text/SatsTest.kt` covers every separator, the decimal
cases, the unreadable ones, empty versus zero, `MAX_SATS` and its overflow,
and what `GroupedSats` shows. In `GroupSendViewModelJvmTest`, typing
`21000.5` reads as `NotWhole` and never builds a spend of 210,005.

---

## Phase 2: the gate comes out

**Half a day. Built.** Phase 0 opened the gate on every network, so what is left is
removing it, not turning it into a per-network switch. A `const` that is
always true is a branch the compiler keeps and a reader still has to follow.

- **`GroupWalletRollout.SEND_AN_AMOUNT` is deleted**, and its kdoc goes with it.
  `ENABLED` and `MAINNET_CAP` stay. The object's own kdoc keeps its rule, that a
  gate decides what this device offers, never what a quorum may decide, and
  now names two gates.
- **Its three reads go.** In `GroupSendScreen`, the *Send everything* row is
  always drawn and the amount field depends only on the switch. In
  `GroupSendViewModel.preview()`, the condition becomes `sendEverything.value`
  alone.
- **The send-all sentence goes.** `for_now_a_spend_sends_everything` has no
  reader once the `else` branch is gone. Remove it from `strings.xml`, so the
  catalogue holds no text that the app never shows.
- **`GroupSendViewModelJvmTest` stops branching.** `the preview waits for the
  form, and what it builds is what is proposed` asserts the amount-with-change
  case outright: 40,000 sats sent, change not null, and `GroupSpend.check`
  accepting the proposed PSBT. A test whose assertions depended on a constant
  was testing whichever branch was compiled in.
- **The mainnet cap is now what stands between a member and a large mistake
  sent as an amount.** Add a view model test on `Chain.Mainnet` where an amount
  over 100,000 sats previews as `OverCap`. `GroupSpendBuilderTest` already
  covers the cap for send-all. This test covers it for an amount, which is the
  path the cap now protects.
- **Order the commit after Phase 3.** With the gate gone, two spends with
  change from one group can be open at once, so change-address reservation
  needs to be in the same release. Phases 1 and 3 can land behind the closed
  gate in any order. This phase is the one that makes them reachable.

The wallet note's Phase 10 table has lost its *send-all only* row, and its
"built" table says the gate was removed and why. That was done in this phase
rather than Phase 6, so the note never describes a gate the code no longer
has.

---

## Phase 3: change goes to an address the group can find

**One and a half days. Built.** This fixes problems 3 and 4 above, and the
silent dust in 5. It is the only phase that changes what the builder chooses.

- **Change indices are reserved like coins.**
  `FrostSigningManager.reservedChangeIndices(database, chatRoomId, account)`
  sits next to `reservedOutPoints`, and both read one private `openSpends`:
  the PSBT of every `GroupTransaction` whose session has not `FAILED`. The
  indices come from `GroupSpend.claimedChangeIndices`, which reads each
  output's claimed path under the account's fingerprint. It reads *claims*,
  not checked derivations, because holding an index too many costs nothing.
  The account is a parameter because the plan's `(database, chatRoomId)`
  signature could not tell one account's change from another's. The value is
  exposed on `FrostSigningRepository` and carried on `GroupSendUIState.Ready`
  as `reservedChange`.
- **`GroupWalletState.nextChangeIndex(reserved)`** is now a function. It
  returns the lowest change index the wallet *watches* that is unused and not
  reserved. The old `nextChangeIndex` property was the first unused index, and
  `changeKnown` separates "no answer yet" from "all held".
- **The window, checked.** The plan read `CHANGE_LOOKAHEAD` as "watched past
  the first unused address". `ElectrumMiniWallet.maybeGenerateNext` actually
  keeps `lastDerived ≥ firstUnused + lookAhead − 1`, so `lookAhead` counts the
  first unused address itself: at 3, three change addresses in all. Rather than
  depend on that arithmetic, `nextChangeIndex` chooses only among the addresses
  the wallet has actually generated, which is the watched set by definition.
  So change can never be sent to an address nobody is watching, whatever the
  look-ahead is. `CHANGE_LOOKAHEAD` is raised to 10, so a group can have ten
  spends with change open at once. Past that, `send` returns `TooManyOpen`,
  and the preview says to wait for one to settle or to send everything.
  Send-all never needs a change address, so it is never refused this way.
- **Still finding is not nothing.** When there are no coins, the result is
  `NothingToSpend` as before. When there are coins but no change address has
  been answered for, the result is `FindingChange`, and the form shows the
  *Finding the group's coins* indicator instead of an error.
- **Dust change is shown.** `Result.Built.absorbed` is what the fee holds beyond
  the fee rate. It is zero for `sendAll`, for a build with change, and for a
  bump. When it is positive, the preview adds *"N sats left over are too few
  to come back as change, and go to the fee."* This line was meant for Phase 4,
  but the new result cases meant `SendPreview` had to change in this phase, so
  it went in now.

**Tests.** `GroupSpendBuilderTest` adds three:
- with index 0 held, change goes to 1, and the built PSBT claims 1;
- with every watched index held, the result is `TooManyOpen`, while send-all
  still builds;
- with no change branch yet, the result is `FindingChange`, while no coins is
  still `NothingToSpend`.

The dust test now asserts that `absorbed` plus the fee at the fee rate is the
fee. `GroupOnchainWalletJvmTest` asserts that the change window is
`CHANGE_LOOKAHEAD` addresses, all subscribed, and that holding all of them
leaves `null`.

**The end-to-end case the removed gate was waiting for** is in
`GroupSpendSigningJvmTest`, not `GroupWalletSpendRoundTest` as planned: the
round test has no database or manager, and this case needs both. One spend is
left open, holding change index 0. Then `GroupSpendBuilder.send` builds a
second spend from a fresh coin, and two devices sign it to `COMPLETE`. The
test asserts:
- the device that did not build it labels index 1 as change;
- both devices' transactions pass `correctlySpends`;
- once confirmed, the change coin counts in `confirmedBalance`, and
  `derivationOf` finds it.

The same file's reservation test now also asserts that a failed session
releases its change index.

---

## Phase 4: the form

**Two days. Built.** Everything the screen draws, under the CLAUDE.md rules.

Top to bottom:

1. **Address.** Unchanged.
2. **Send everything.** The whole row is the control:
   `Modifier.toggleable(role = Role.Switch)` and
   `minimumInteractiveComponentSize()` on the `Row`, with the `Switch` taking
   `onCheckedChange = null`. That makes one 48dp target with one name, instead
   of a small switch beside a label that does nothing when tapped.
3. **Amount in sats**, shown when the switch is off:
   - the number keyboard with `ImeAction.Next`, and Phase 1's `GroupedSats`;
   - `suffix` *sats*, so the unit is part of the field and not only its label;
   - `supportingText` says how much can be spent: *"100,000 sats confirmed and
     free to spend"*. That is `GroupSpendBuilder.spendableBalance`, built on
     `spendable`, which is now public so the screen and the builder count the
     same coins. On mainnet a second line gives the cap, using the existing
     `while_the_wallet_is_new_at_most_s_sats` rather than a reworded copy, so
     the warning before the cap is hit and the error when it is hit are the
     same sentence;
   - amount problems go to the field, as `isError` plus the supporting text:
     `InvalidAmount`, `NotEnough`, `BelowDust` and `OverCap`. The decision is
     `GroupSendViewModel.isAboutTheAmount`, which is always false while sending
     everything, because then there is no field to put them on. While the field
     shows a problem, the preview below shows nothing, so the problem is said
     once.
4. **Fee rate.** Unchanged.
5. **Preview.** Built, or the reason it cannot be: address, nothing to spend,
   `TooManyOpen`. The *go to the fee* line and `TooManyOpen` landed in Phase 3.
   The wording of every reason is now one function, `problemWith`, shared by
   the field and the preview.
6. **Nothing leaves until a quorum signs** and the button. Unchanged.

Turning *Send everything* on hides the amount field without clearing it, so
turning it off again finds the amount typed.

**Strings:** `sats` and `s_sats_confirmed_and_free_to_spend` are new here. The
rest came in earlier phases. `sats_are_whole_numbers`,
`that_is_more_sats_than_there_will_ever_be` and `that_isn_t_an_amount_in_sats`
came in Phase 1, and `s_sats_left_over_go_to_the_fee` and
`too_many_spends_are_open_with_change` in Phase 3.

**Budgets:** the audit's budgets are all met. The amount field adds no icon,
and the switch row is a `toggleable` with a minimum size, not a `.clickable`.

**Test**, `jvmTest/…/ui/composable/GroupSendScreenJvmTest.kt`, five cases, on
regtest unless stated:
- typing `40000` shows `40,000`, *Sends 40,000 sats*, and change, and the
  button is enabled;
- the amount is shown as free to spend, and off mainnet there is no cap line;
- `0.5` puts the whole-numbers error on the field, which is in its error
  state, with no preview and a disabled button;
- the *Send everything* row hides the field and the change, and tapping it
  again brings back `40,000` and its preview;
- on mainnet, the cap line shows before typing, and `150000` puts the field
  in its error state with the button disabled.

---

## Phase 5: what the other members see

**Half a day, mostly reading. Built: tests only, nothing needed fixing.** The
approval side has been built since the wallet note's Phase 8. This phase
checked that every place a spend is described reports the payment, not the
payment plus change.

| place | reads | with change, it says | tested by |
|---|---|---|---|
| `FrostSigningScreen.WhatIsBeingSpent` | every output and its `isChange` | a payment row, a change row labelled *back to the group as change*, the fee | `GroupSpendSigningJvmTest`: the other device's `checkedSpend` labels exactly the change output |
| `ProposedSpend.summarize`, which the proposal list and the wallet screen's spend rows both use | the single payment's amount | *Send 30,000 sats to bcrt1…* | `ProposedSpendTest`, new |
| `GroupWalletScreen`'s spend rows | `ProposedSpend.summarize(row.spend)` | the same as the row above | the same test: the plan had not read this row, and it turned out to reuse the summary, not compute its own amount |
| the *signed* line (`TYPE_FROST_COMPLETE`) and the *confirmed* line (`TYPE_GROUP_TRANSACTION_CONFIRMED`) | `amountSent`, which excludes change | *sending 30000 sats* and *30000 sats sent* | `GroupSpendSigningJvmTest`'s end-to-end case, which now runs `recordConfirmed` and reads both lines off the other device's transcript |

---

## Phase 6: checked, and opened

**Half a day. The automated checks are done; the by-hand walkthrough is not.**

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

Both ran after every phase, and every phase was committed only once they
passed. At the end: 1,348 JVM tests passing, the Android compile clean, and
every audit budget and floor met.

**Still to do, by hand, on regtest**, with two devices in a group with an
opened wallet. This needs two devices with nodes, so it was not done while
building. Each step, and the automated test that comes closest:

| step | closest automated cover | what it cannot show |
|---|---|---|
| 1. fund it with two coins | — | |
| 2. send an amount that needs one coin, and check the preview's change matches the approval screen's on the other device | `GroupSpendSigningJvmTest`'s end-to-end case: the builder's `change` equals the other device's checked change output | the two screens drawn side by side |
| 3. with the first spend open, send a second, and check it picks the other coin and a different change address | the same test, with an open spend holding index 0 and the second paying index 1 | reservations read through the real repository after a real round of messages |
| 4. sign both, confirm both, and check that both change coins are in the confirmed balance | the same test's `confirmedBalance`, and `GroupOnchainWalletJvmTest`'s change coin over a fake Electrum | a real Electrum server reporting the change address |
| 5. send an amount that leaves dust change, and check the *go to the fee* line | `GroupSpendBuilderTest`'s dust case for `absorbed` | the line itself on screen: no screen test produces dust change |

The wallet note's tables were updated in Phase 2, and this note was added to
[README.md](./README.md) with the plan.

---

## Phase 7: the first release

**No code.** Mainnet gets send-an-amount in the same release as every other
network, so the first real reports come from both at once. The 100,000 sat cap
stays in force. It lifts on its own condition, which is about aggregation and
confirmation, not change, and lifting it is a separate decision from this
plan.

What to watch, and what each would mean:

- **A change output shown as a payment on the approval screen.** Check 5
  refused to label it, so its claimed derivation did not reproduce its script.
  That is a builder bug. Members are shown a payment that is really the
  group's own money, and anyone reading the approval screen closely sees an
  output to an unfamiliar address and declines. Fix it in the builder. Nothing
  needs to be put back behind a gate.
- **Change that never shows in the balance.** The change address is outside
  the watched window, which Phase 3 was meant to prevent. The coins are not
  lost, because the address derives, but the window or the reservation is
  wrong.
- **The same change address in two confirmed spends.** Reservation missed a
  session.

If either of the first two turns out to be widespread, the response is the
cap, not a new gate. Lowering `MAINNET_CAP` bounds the harm at once without
taking away the send-everything path that already works.

---

## What this does not do

- **Units other than sats.** No bitcoin, no fiat, no toggle (Phase 0, decision 2).
- **Several recipients.** `ProposedSpend` already describes a spend to several
  addresses, so the approval side would cope. The form, the cap and coin
  selection would each need rethinking.
- **Choosing coins.** Largest first, as built. Coin control is a disclosure on
  a form that most members never open.
- **Subtracting the fee from the amount.** The amount is what arrives. A member
  who wants "everything but some" sends an amount.

| phase | what | days |
|---|---|---|
| 0 | decisions | ½ |
| 1 | `parseSats`, field transformations | 1 |
| 2 | the gate comes out, committed after Phase 3 | ½ |
| 3 | reserved change, window, `FindingChange`, `absorbed` | 1½ |
| 4 | the form | 2 |
| 5 | what the other members see | ½ |
| 6 | checks, regtest by hand, docs | ½ |
| 7 | watching the first release | — |
