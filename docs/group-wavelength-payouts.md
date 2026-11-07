# Paying a member out of the group's Wavelength wallet, onchain and over Lightning

How the group's Wavelength wallet pays a depositor their balance at the destination they signed into the room
(`GroupPayoutDestination`): onchain, as a leave output in an Ark round to their onchain address, and over Lightning, to
an invoice from their Lightning address. Both under payouts-only: a member's own money, to that member's own
destination, approved by a quorum and checked by every co-signer.

**Built on 7 November 2026, in `jvmTest` only, except what needs coins or the owner.** Written the same day against
`30a9f5e3`, with `wavelength-kmp` at `80569131`, read in the main checkout because a fresh worktree doesn't have the
submodule. Files and lines named in the plan below are as they stood then. **No payout of either kind has met a real
daemon, a real round or a coin.** **Both rails were turned on for mainnet by the owner's decision the same day, with Phases 0
and 7 not run:** any build from `dedun` offers to pay a member out of a mainnet wallet, onchain or over Lightning, with real
money. What bounds a loss is one member's balance, the 100,000-sat cap and every device's checks.

### Where the build stands

| phase | state |
|---|---|
| 0 | **not run: it needs signet coins.** One answer came from the SDK instead: `SendResult.actualAmountSat` says a bounded onchain send *"matches the requested amount (the seal-time fee handshake returns change)"*, so W5's exact leave is what the daemon means to do. Nothing has shown that a real round does it |
| 1 | done, `1a8b2124`: the leave budget, exact and once, and no approved script reaching a transfer or an on-chain spend |
| 2 | done, `300e8cc2`: `payout-onchain`, its rules, `GroupArkLeave`, the balance. Schema 42 |
| 3 | done, `b163c0e6`: the runner sends each approval once, noted with its id |
| 4 | done, `45db1971`: the row's offer, the wallet screen's rows, the line when a round confirms |
| 5 | done, `1bff41c9`: `lnurl`, charging what the vHTLC held, a settled refund given back. Schema 43 |
| 6 | **half.** The vHTLC's unilateral refund without the receiver is signed when it pays the group's onchain wallet, which is the daemon's own on-chain recovery. The cooperative refund without the receiver turned out to be allowed already. **Forfeiting a funded vHTLC into a round has no daemon path,** so it stays refused |
| 7 | **not run:** signet coins and phones |
| 8 | **done by the owner's decision on 7 November 2026, with W13's conditions unmet.** `paysOnchain` and `paysOverLightning` are both on for every chain. The unilateral exit stays signet only. Nothing in this table's "not run" rows has run |

**Where the build chose differently from the plan:**

| the plan said | what was built |
|---|---|
| a dead round holds the payout until the term ends | `abandoned` forgets its leave row, so a device's balance shows the approval open again and its co-signer will pay it in another round while it stands. The runner tries each approval once a term, though, so in practice the next try is the next operator's, or this one's after a restart |
| W7: a floor from the operator's onchain terms | the signer refuses a leave under 546 sats, the dust limit of the costliest address type. The screen offers nothing that arrives with less than 1,000 sats (`MIN_ONCHAIN_PAYOUT_SAT`), a stand-in until Phase 0 reads the operator's terms |
| `max_fee_sat` chosen per payout | the statement's `maxRoundFeeSat`, so it is never tighter than what a round may cost anyway, and the screen states it |
| Phase 4: a preview dialog before proposing | the row's own line says what arrives where and what it may cost, as the Lightning offer beside it does. The approval screen says it again |
| W9 for onchain payouts | for both rails. A second Lightning payout while one is open was allowed within the balance, and is now refused |
| W12 when an older build is in the room | a Lightning payout without `lnurl` is refused, and no build has proposed one outside a test since this wallet's payouts have only been on off mainnet |
| a refunded payout shown on the wallet screen | not built. The operator's activity line still says it failed, and the member's balance comes back once the refund settles. No transcript line says so |
| Phase 6's recovery credits the member | it can't, on this wallet: the money lands in the group's onchain wallet as a coin nobody owns, and the member stays charged here. A 30341 assignment from the Wavelength ledger would close it, as it would for leaving (see "What this plan does not do") |
| a migration test | none exists for any version of this database. Room's generated `42.json` and `43.json` are the check |

**What the review found.** An adversarial reading of everything above that changes what a device signs, by a reviewer that
isn't a person and was given the code rather than this note, before anything was pushed. Fixed, each with a test:

| found | what it would have let happen | now |
|---|---|---|
| **a round paid any standing approval, whatever this device knew of the member's balance** | a member paid in a round one device missed could get a second payout of the same money approved by themselves and that device, and every device that saw the first would still pay the second. The loss would fall on everyone else's share. Lightning payouts had the same gap | a co-signer pays an approval only if everything open for that member fits what they hold by its own count, on both rails. One already given a round still finishes |
| **a paid approval stood in for a later one to the same address** | after a member's first payout confirmed, every later one to the same address was refused by every device, and each try killed the whole round, renewals included | a confirmed leave no longer counts, and approving refuses a second payout to an address while one is still being paid to it |
| two payouts in one round were each charged its whole fee | sats charged that left nobody's balance | the fee is split between them in proportion, as the onchain wallet's payouts split theirs |
| a refund recorded once, never replaced | a refund signed and never settled kept the member charged after a later one brought the money home | an unsettled refund gives way to the next |
| a vHTLC's recovery paid a fee outside the group's budget | fees past `maxMonthlyFeeSat` | it is counted against the budget and filed |

**Still open from that reading, and not fixed here:**
- **A Lightning payee is still the proposer's word,** `lnurl` or not: a member's metadata is public, and a proposer can put it in an invoice from their own node. W12 says so. Accepting a Lightning payout only from the member it pays, or with their own device's word on the invoice, would close it, and is the owner's call.
- **A member who signs another's onchain address as their own blocks that member's onchain payouts** until one of them changes it. The group's onchain wallet has the same rule (`docs/payouts-only.md`, D7), and both should change together if either does.
- **A device that missed a round still doesn't count the payout it made.** Its co-signer now refuses to pay that money again, but its screen shows the member's balance as unpaid. Crediting a leave from the chain, by script and amount, would fix the screen.

Read these first:

- [group-wavelength-wallet.md](./group-wavelength-wallet.md): D7, D9 and D15, its Phase 11, and "What Lightning found".
- [group-wavelength-ui.md](./group-wavelength-ui.md): its Phase 7, which built the Lightning half of this, and "What the
  screens may not promise".
- [payouts-only.md](./payouts-only.md): D1, D5, D6, D7 and D8. Every rule here is one of those on a new rail.
- [group-payout-destination.md](./group-payout-destination.md): what a destination is, and why its onchain address has
  to be unused when it is set.

The headline: **the Lightning half exists and is off on mainnet; the onchain half doesn't exist at all, and the
co-signing policy already has an unfilled slot for it.** `ArkPolicy.Context.authorisedScripts` is checked by a round's
leaves, by an Ark transfer's outputs and by the on-chain account's spends, and nothing in the app ever fills it. So
today every co-signer refuses a leave to anything but the group's own onchain wallet, which is right, and also why no
member can be paid onchain.

Three things shape the work:

1. **An onchain payee can be named exactly; a Lightning payee can't.** A device can compare a leave's script with the
   member's signed address byte for byte. It can only check that an invoice commits to the member's Lightning address
   metadata, which the proposer could have written into an invoice of their own. That makes the onchain rail the safer
   of the two, and the one that can reach mainnet first.
2. **A payout's money has to come back when it fails, and to the member's tally, not the pot.** Today a Lightning
   payout that is refunded comes back as the group's coin with no hash, while the member stays charged for it
   (below). A leave that dies in a round holds its coins until the term ends.
3. **The policy is the security boundary.** Every new thing a device signs gets the adversarial reading Phase 7 of the
   UI plan got, before anything is pushed, and its own commit.

## What exists, and what is missing

| a payout needs | what exists | what is missing |
|---|---|---|
| a member's balance | `GroupWavelengthPayouts.balancesIn`: deposits less what's paid, shrunk pro rata to what's in the wallet | onchain payouts in it. Lightning payouts are charged `amount + maxFee` for ever, even when the fee was smaller, and **even when the payment was refunded** |
| a destination | `GroupPayoutDestinations.payoutDestinationOf`, current and former members, with an optional onchain address | nothing |
| an authorisation | kind 30379: `GroupWavelengthPaymentEvent` (invoice, payout), `GroupWavelengthExitEvent` (leave to the group's own wallet) | **an onchain payout.** And the Lightning one has no `["lnurl", …]` tag, so a device doesn't check the invoice commits to the member's address, as the Lightning wallet's does (`payouts-only.md`, D8) |
| a co-signer's rule | `ArkPolicy.checkRound` budgets leaves by `authorisedScripts` (`ArkPolicy.kt:523`); `transferInput` (`:620`) and `onchainSpend` (`:326`) take the same budget | **nobody fills it** (`GroupArkCoSigner.kt:320`). The budget is rebuilt per request, so nothing stops one approval paying twice in two rounds. And the same map licenses an Ark transfer to the member's script, which would make a VTXO at their onchain key that their wallet can't see |
| the daemon | `GroupWavelengthDaemon.pay` (invoice) and `.leave(outpoint, destination)`, which leaves a whole coin | an amount. The SDK has `SendRequest.Onchain(address, amountSat)`, which the daemon's router turns into *forfeits, one fixed leave output and one change VTXO* (`swapwallet/router.go:581`) |
| the runner | `payApproved` (`GroupWavelengthRunner.kt:375`) pays each approved hash once and nothing else; `leaveIfAsked` (`:331`) | the onchain twin of `payApproved` |
| the ledger | `GroupArkPayment(wallet, paymentHash, tx)` | the vHTLC's amount and script, so a device can charge what really left and recognise the refund. A row per leave |
| screens | a *Pay out over Lightning* button on a member's Wavelength row (`GroupPayoutsScreen.kt:363`), shown only off mainnet (`GroupWavelengthRollout.paysOverLightning`) | *Pay out onchain*, the life of a payout, transcript lines for both |

## Decisions

**W1. An onchain payout is a leave in an Ark round, not a hop through the onchain wallet.** The daemon's bounded
`SendOnChain` puts the payout and the group's change in the same round. A device checks the leave against the
authorisation and the change against its own keys, which `checkRound` already does for the change. The alternative,
leaving to the group's onchain wallet and paying from there with `PayoutBuilder`, costs two fees, takes every coin out
rather than one member's share, and lands as coins nobody owns until a 30341 assignment (see the appendix).

**W2. A new authorisation type in kind 30379, `payout-onchain`.** `GroupWavelengthOnchainPayoutEvent`, beside the
payment and the exit:

```
["d", "payout-onchain:<chain>:<wallet>:<member>:<asked_at>"]
["type", "payout-onchain"]
["payout", <member>, <destination event id>]
["address", <the destination's onchain address>]
["amount_sat", <what arrives, exactly>]
["max_fee_sat", <the most the round may cost the member>]
["expires_at", <unix seconds>]
```

The address is carried as well as the destination's id, so the summary and the policy read the same string, and a
device checks they agree. An older device reads none of 30379's existing shapes from it and refuses to sign, which is
the safe way round (W10).

**W3. A device signs the authorisation only when** it carries `payout`; the destination is the member's newest one this
device holds (`payoutDestinationOf`, so former members too) and has an onchain address; `address` equals it byte for
byte and decodes for this chain; no other member signed that address; `amount_sat + max_fee_sat` fits the member's
available Wavelength balance, counting proposals still being signed on **either** rail; the member has no other open
Wavelength payout; `amount_sat` is at least the floor (W7); and it stands for no more than seven days. These are
`payouts-only.md` D7's rules with the Lightning rail's `MAX_APPROVAL_SECONDS`, in `GroupWavelengthPayouts.refuse`.
Address usage is the proposer's check (`UnusedAddressCheck`), not a signer's, as D7 says.

**W4. Leaves get their own budget, and transfers lose theirs.** `authorisedScripts` splits. `authorisedLeaves:
Map<script, AuthorisedLeave(id, amountSat, maxFeeSat)>` goes to `checkRound` alone. `transferInput` and `onchainSpend`
take an empty map, which is what they get today, so nothing that has run changes. An onchain address is only ever
paid by a commitment output.

**W5. A leave pays exactly what was approved, once.** A round's leave to an authorised script has to equal
`amount_sat`, not merely fit under it: in bounded mode the daemon fixes the leave and takes the fee from the change,
so there is no reason for it to be smaller, and a smaller one is the operator taking the difference. A new
`leftIn: Map<authorisation id, commitment txid>` in the context, filled from a new ledger row, refuses the same
authorisation in a second commitment, as `paidIn` does for a hash. One commitment may be asked about many times (one
forfeit per VTXO), and that is the same commitment.

**W6. The member pays their own leave's fee.** In a round that pays a leave, the round's whole fee counts against the
payout's `max_fee_sat` as well as the statement's `maxRoundFeeSat`, and the member is charged `amount_sat + fee`. If
the same round also renews other coins of the group's, the payee pays that renewal's share too. It is bounded by their
`max_fee_sat`, renewals near a payout are rare, and attributing a round's fee across intents would need the daemon to
say which input was for what, which a device can't check. The alternative is refusing any round that both pays and
renews, which could fail a payout at the very moment the wallet most needs to renew. **The owner may prefer that**
(question 1).

**W7. A floor.** No payout below the operator's minimum leave plus `max_fee_sat` plus dust for the address type, read
from the operator's onchain terms when the proposal is made. Phase 0 reads what mainnet's are. Below it, the row says
how much more the member needs, as a Lightning payout's floor does.

**W8. A payout is the member's whole available balance, on either rail.** As today's Lightning payout does:
`amount_sat = available − max_fee_sat`. No amount field. Partial payouts are question 3.

**W9. One open payout per member per wallet, across both rails.** A Lightning proposal being signed blocks an onchain
one, and the reverse. `proposedFor` and `balancesIn`'s `open` count both kinds.

**W10. Mixed versions fail closed.** An older device refuses to sign the new 30379 and refuses the leave, since its
budget is empty. A payout then needs `t` upgraded people to approve and `2t−1` upgraded devices to co-sign the round.
The approval screen says when this device can see older ones in the room, as `payouts-only.md` D12 does.

**W11. What a member is charged follows what this device signed and saw, never the operator's report.**

| payout | open (charged `amount + max fee`) | paid (charged what left) | back (charged nothing) |
|---|---|---|---|
| onchain | approved, not expired, no round signed | this device signed a round with its leave: `amount + that round's fee` | expired unused, or its round was seen dead and the term ended |
| Lightning | approved, not expired, no transfer signed | this device signed the transfer: **the vHTLC's amount**, which includes the swap fee | a refund of that vHTLC came home |

**A refund credits the member and pays nobody (decided by the owner on 7 November 2026).** A refunded Lightning payout
comes home to the group's own keys, as `claimInput` already makes it, and goes back on the member's balance. It is
not sent on to their destination: a refund is an Ark transaction whose outputs are VTXOs, so it can't pay an onchain
address, and paying their Lightning address again would be a new payment. The member, or anyone, proposes again.

**W12. Lightning payouts check the invoice against the member's address.** The `lnurl` tag and check from
`GroupLightningPayouts` (`payouts-only.md`, D8) come to `GroupWavelengthPaymentEvent`. It proves the invoice commits to
metadata naming the member's Lightning address. It doesn't prove the invoice pays them, and the approval line keeps *as
@bob resolved it*. A payment without the tag is refused for a payout, so it lands with W10's mixed-version note.

**W13. The gates are per rail.** `GroupWavelengthRollout.paysOnchain(chain)` beside `paysOverLightning(chain)`. Onchain
can open on mainnet after the signet run (Phase 7) by the owner's decision. Lightning stays shut on mainnet until a
refund has run for real and a funded vHTLC has a way home that doesn't need the swap server (Phase 6), or until the
owner decides otherwise. **Decided on 7 November 2026: both open on mainnet now, ahead of either condition.** So a
payout leave's first real round, and a Lightning payout's first real refund, may happen on mainnet with a member's money.

## Phases

One commit per phase, each compiling and passing `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`
and `./gradlew :composeApp:m3Audit`. Messages end `(Group Wavelength payouts, Phase N)`. A phase that changes what a
device signs is marked **policy**, gets its own commit, and gets an adversarial reading before it is pushed.

### Phase 0: spikes, on signet through the harness

No app code. Each answer is written into this note.

- **S1. What a bounded `SendOnChain` asks of the hosted signer.** Through `TestHostedBridge` and
  `GroupWavelengthSignetJvmTest`, send 10,000 sats to a plain signet address from a funded group wallet, with the
  policy temporarily allowing that one script. Record: the note's leave and change, whether the leave is exactly the
  amount (the first run found a leave noted as asked and paid less the fee, `group-wavelength-wallet.md`), where the
  fee comes from, which requests reach a device and in what order, and the commitment. **If the leave carries the fee,
  W5 and W6 change:** the authorisation then names a debit and a device accepts a leave between `debit − max fee` and
  the debit.
- **S2. Address types.** Does the operator take P2WPKH, P2TR, P2SH and P2PKH destinations in a leave? A destination
  can be any of them today. Whatever it refuses, the payout row has to say before anyone proposes.
- **S3. The operator's onchain terms on mainnet and signet,** read over HTTP as Phase 1 of the UI plan reads the
  others, with no coins: the minimum leave, the fee, and how a leave's fee is quoted. That gives W7's floor.
- **S4. A Lightning payout refunded.** Pay an approved invoice the payee never settles, wait out the swap, and watch
  `tryCooperativeRefund`. Does the co-signature reach a device in the form `claimInput` asks for? Record the vHTLC's
  amount, its script and the refund's Ark transaction. This is the first real test of the refund rule, which the UI
  plan's Phase 7 left open.

**Done when** each spike has its numbers here and W5, W6 and W7 are confirmed or rewritten.

### Phase 1: the policy pays a leave it was told to, once (policy)

Pure, in `ArkPolicy`, with nothing yet filling the new budget, so no device behaves differently.

- `authorisedScripts` becomes `authorisedLeaves` (W4), read by `checkRound` alone. `transferInput` and `onchainSpend`
  lose the clause, with a test each that a transfer or an on-chain spend to an authorised leave script is refused.
- `checkRound`: a leave to an authorised script equals its `amountSat` exactly (W5); the round's fee is under the
  smallest of `maxRoundFeeSat` and every paid authorisation's `maxFeeSat` (W6); no authorisation pays twice in one
  commitment; `leftIn` refuses one already given to another commitment.
- `Effect` gains `leaves: List<Leave(authorisation id, script, amountSat)>`, so the co-signer can file what it gave.
- **Tests,** in `ArkPolicyJvmTest`, against fixtures built by the fork's `round/group_policy_fixtures_test.go` from a
  real bounded `SendOnChain` intent (S1's shape): a leave one sat short, one sat over, to a script one byte off, the
  same approval in a second commitment, two leaves for one approval, a fee one sat over the payout's limit, change to
  a key the group didn't issue, and a leave with no authorisation at all, which is what every device does today.

**Done when** the fixtures pass and refuse as listed, and the reading has found nothing open.

### Phase 2: the authorisation, the ledger and the balance (policy)

- **`GroupWavelengthOnchainPayoutEvent`** (W2), `read` and `template`, with `GroupWavelengthPaymentEvent.read` and
  `GroupWavelengthExitEvent.read` both returning null for it, by test.
- **`GroupWavelengthPayouts.refuse`** takes it (W3). `proposedFor` counts both kinds (W9).
- **The ledger:** a `GroupArkLeave(wallet, authorisation, script, amountSat, commitment, feeSat, term)` row, filed from
  `Effect.leaves` when a device signs, and `GroupArkPayment` gains `amountSat` and `vhtlcScript` for Phase 5. One
  migration, schema 42. If another branch has bumped it first, one migration at merge.
- **`GroupArkCoSigner.contextOf`** fills `authorisedLeaves` from approved, unexpired onchain payouts for this wallet, and
  `leftIn` from the leave rows.
- **`balancesIn`** follows W11 for onchain payouts. `signed` becomes a list of both kinds, newest statement per id, with
  the Lightning one keyed by hash as now.
- **`ProposedEvent.summarize`:** *Pay out @alice: 48,000 sats to their onchain address bc1q…x7, at most 1,500 in
  fees.* The address in full on `FrostSigningScreen`, with *the address @alice signed on 3 November*.

**Tests:** `GroupWavelengthPayoutsJvmTest` gains each W3 refusal, a former member paid to their last destination, a
member with a Lightning proposal pending refused onchain and the reverse, and a balance through open, paid and
expired. A migration test.

**Done when** two devices in `jvmTest` approve an onchain payout and each co-signer, given a round with that leave,
signs it once and refuses it in the next.

### Phase 3: the runner pays it

- `GroupWavelengthDaemon.sendOnchain(address, amountSat, maxFeeSat)` through `SendRequest.Onchain`, with
  `sweepAll = false`.
- `GroupWavelengthRunner.payApprovedOnchain`, beside `payApproved`: each approved, unexpired onchain payout with no
  leave row on this device and none in the operator's last report, once a run, in its own job. **Never one that isn't
  approved,** for the reason `payApproved` gives: an attempt every device refuses holds the coins for the transport's
  50 s, and a round missed costs everyone in it.
- **Confirmed:** the watcher already follows a round's commitment to `settleDepth`. When it confirms, the leave row is
  final and the transcript line is written.
- **A dead round.** The coins it forfeited stay reserved until the term ends, as a dead transfer's do today, and so
  does the leave. The payout row says *Held until the next operator*, with the way to nominate the wallet screen
  already has. A new approval after that pays again; the old one has expired by then or is refused by `leftIn`.

**Tests:** in `GroupWavelengthRunnerJvmTest` over the SDK's fake: one approval paid once across restarts, an
unapproved payout never asked, an expired one never asked, a handover mid-payout leaving the next operator to try once.

**Done when** the fake daemon is asked exactly once per approval in every test, and never otherwise.

### Phase 4: the screens

- **`GroupPayoutsScreen`, the member's Wavelength row:** *Pay out onchain* beside *Pay out over Lightning*, each with
  a line when it can't: *@alice hasn't given an onchain address*, *That address has been paid before*, *Needs 1,800
  more sats to be worth sending onchain*, *A payout to @alice is waiting for signatures*, *Nobody is running this
  wallet*. Both behind `paysOnchain` and `paysOverLightning` (W13).
- **A preview before proposing,** a dialog as the leave confirmation is: *Arrives at bc1q…x7: 48,000 sats. Up to 1,500
  sats in fees, from @alice's balance. It joins the next round, about every 20 minutes, and then needs 3
  confirmations.* The proposer's `UnusedAddressCheck` runs here, with `payouts-only.md` D7's outcomes.
- **The life of a payout,** on the member's row and in the wallet's activity: waiting for signatures, approved,
  waiting for a round, in a round, confirmed; or expired, or held until the next operator. For Lightning: sent, paid,
  refunded (Phase 5).
- **Transcript lines** under `WAVELENGTH_TYPES`: approved, paid out (*paid out @alice 48,000 sats onchain*, *paid out
  @alice 29,690 sats over Lightning*), refunded, expired unused, held. Each written once, by the device that saw it.
- Strings in `strings.xml`, sentence case, `ScreenStateTransition` around the row's states, nothing below 48dp.

**Tests:** `GroupPayoutsScreenJvmTest` for each reason a rail is off, at phone width and 1280dp; a view model test that
`amount + max fee` never passes `available`, including at the floor; the transcript lines once each.

**Done when** `m3Audit` is clean and every state of the row has a test.

### Phase 5: Lightning payouts, brought level with the Lightning wallet's (policy)

Independent of Phases 1 to 4, and can run beside them.

- **The `lnurl` tag** (W12): `LightningAddressResolver`'s metadata on the proposal, and `GroupWavelengthPayouts.refuse`
  checking the invoice's description hash against it and its identifier against the member's address, by the code
  `GroupLightningPayouts` already has. Moved somewhere both can call, not copied.
- **Charge what left** (W11): `transferInput`'s `Effect.payments` carries each vHTLC's amount and script, and the
  payment row keeps them. `balancesIn` charges the vHTLC's amount once sent, in place of `maxSat`.
- **A refund un-charges the member.** `claimInput` already lets a refund home into the group's keys, with no hash. The
  device matches the refunded prevout's script against the vHTLC scripts it filed, marks that payment refunded, and
  the member's balance gets it back. Today it lands in the pot and inflates everyone's pro-rata share, at the payee's
  expense.
- **A refunded payout needs a new invoice,** since a hash pays once. The row says so and offers to propose again.

**Tests:** an invoice whose metadata names another address, an invoice with no description hash, a member charged
the vHTLC's amount and not the limit, a refund matched to its payment and the balance restored on every device, a
refund matching nothing staying the pot's.

**Done when** S4's refund, replayed as a fixture, gives the member their balance back on every device.

### Phase 6: a funded vHTLC always has a way home (policy)

The wallet note's open gap, and what keeps Lightning payouts off mainnet. **It may be big enough for a note of its
own; this phase is the minimum the payout rail needs.**

- **The refund-without-receiver leaf after its locktime,** to a VTXO of the group's own keys, signed automatically like
  any other money coming home. It needs the operator's co-signature, so it doesn't need the swap server.
- **The forfeit of a funded vHTLC into a round** whose outputs are the group's own, so a payment stuck past the swap's
  life can renew or leave with the wallet.
- **What a device does when neither works:** the payment's value stays charged to the member as *held*, never to the
  pot, and the wallet screen's card names it.

**Tests:** Go fixtures for each leaf, refusals for each to anywhere but the group's keys, and a locktime one block
short.

**Done when** the fixtures pass and the reading is clean. The real test is Phase 7's.

### Phase 7: signet, by hand

A test build with signet in `NETWORKS`, four devices, at least two of them phones, fresh signet coins sent by hand.

- Two members deposit, one by boarding and one over Lightning.
- **Onchain:** Alice is paid out to a P2WPKH address and Bob to a P2TR one. Their tallies go to zero on every device,
  and the transactions are linked here.
- **Thefts, refused:** with a patched daemon, the same approval in a second round, a leave one sat short, a leave to a
  stranger's script, and an Ark transfer to Alice's onchain script. Every device refuses each, with the reason quoted
  here.
- **Lightning:** a payout paid; a payout whose payee never settles, refunded, with Alice's balance back on every
  device; and if Phase 6 is built, one left to its locktime and brought home without the swap server.
- **A dead round:** the operator's phone killed after forfeits are handed over, and the payout held until a new
  operator, then paid.

**Done when** every row above has a transaction or a quoted refusal in this note, and the fees are recorded against W6.

### Phase 8: mainnet, and the other notes

- `paysOnchain` on mainnet, **by the owner's decision,** under the 100,000-sat cap. `paysOverLightning` follows only on
  W13's condition.
- `group-wavelength-ui.md`: Phase 7's row and "What the screens may not promise". `group-wavelength-wallet.md`: Phase
  11 and its census line. `payouts-only.md`: a Wavelength column wherever it lists rails. The docs README gets this
  note's row.

**Done when** the owner has decided each gate and the notes agree with the code.

## Order

| phase | depends on | touches | rough size |
|---|---|---|---|
| 0 spikes | signet coins | signet, nothing in the app | 2 days |
| 1 the leave rule | 0 (S1) | **policy** | 3 days, plus a reading |
| 2 authorisation, ledger, balance | 1 | app, **policy**, schema | 4 days |
| 3 the runner | 2 | app | 2 days |
| 4 screens | 2 | app | 3 days |
| 5 Lightning level | 0 (S4) | app, **policy**, schema | 3 days, plus a reading |
| 6 a way home | 5 | **policy** | 1 to 2 weeks, plus a reading |
| 7 signet by hand | 3, 4, 5, and 6 for its last row | signet, phones | 3 days |
| 8 mainnet | 7, the owner | app, docs | the owner's call |

Phases 3 and 4 can run side by side after 2, and 5 beside all of 1 to 4. One schema migration if 2 and 5 land
together. The estimates are as unreliable as the UI plan says its own were: what took the time before was found
against real servers.

## Risks

| risk | why it matters | what to do |
|---|---|---|
| the leave isn't exactly the amount | W5 refuses every real payout, failing closed, and the round's 60 s go with it | S1 before Phase 1, and the fixture from a real intent |
| one approval paid twice | the member's balance is charged once and the group pays twice | `leftIn`, filed when signing, not when confirming; tests in Phase 1 and a theft in Phase 7 |
| an authorised script reaching a transfer | a VTXO at the member's onchain key, which their wallet can't see or spend alone | W4: the budget is for leaves alone, with a refusal test |
| a refund lands in the pot | the payee loses their balance and everyone else gains | Phase 5, before Lightning opens on mainnet |
| a payout in a dead round | the member waits a term for their money | held, said on screen, and a nomination away |
| the payee pays a renewal's fee | a little of the member's money pays for other people's coins | bounded by `max_fee_sat`, stated in W6, and question 1 |
| a Lightning payee is still the proposer's word | a lying proposer can take one member's balance | `lnurl` narrows it; the transcript names the proposer; the cap bounds it |
| older devices in the room | payouts fail to gather a quorum, with nothing saying why | W10's line on the approval screen |

## Questions, for the owner

1. **Does the payee pay the whole fee of the round their payout is in** (W6), or should a device refuse a round that
   both pays and renews?
2. ~~Can onchain payouts open on mainnet before the UI plan's Phase 4,~~ **Decided: yes,** along with Lightning (question 4).
3. **Whole balance only, or an amount?** Whole is what the Lightning rail does and what `payouts-only.md` D6 calls a
   payout. An amount needs a floor on what stays behind too.
4. ~~What opens Lightning on mainnet?~~ **Decided: the owner's word, on 7 November 2026,** with neither S4's refund nor Phase 6's
   recovery having run. A payment whose swap the server abandons now depends on the daemon's on-chain recovery, which has never run,
   and lands in the onchain wallet as a coin nobody owns.
5. **Signet coins for Phases 0 and 7,** by hand, into fresh wallets.

## What this plan does not do

- **Pay anybody but a member,** or to anywhere but their signed destination. No typed-in address or invoice.
- **Assign the coins a leave to the group's own wallet brings out.** Leaving by deadline (UI Phase 8) moves every
  member's Wavelength balance onchain as coins nobody owns, which payouts-only then holds until a 30341 assignment.
  A coin assignment proposed from the Wavelength ledger at the moment of leaving would close that, and is worth its own
  note.
- **A unilateral exit as a payout.** It has never run, and it pays the group's onchain wallet.
- **Prove a Lightning invoice pays the member.** A member-signed invoice would; it is `payouts-only.md`'s follow-up too.

## Appendix: what was considered and rejected

| considered | why not |
|---|---|
| leave to the group's onchain wallet, then pay with `PayoutBuilder` | works today with no policy change, but costs two fees, moves everyone's money rather than one member's, and lands as coins nobody owns until a 30341 assignment. It stays the way money leaves unattended (D9) |
| `ExitRequest.Cooperative(outpoint, destination)`, a whole coin to the member | a coin is rarely one member's balance once renewals have merged them, and a device would have to check that the coin's value is the member's, which it can't |
| a budget (`leave ≤ authorised`) rather than an exact amount | lets the operator shave a payout and keep the difference under the round's fee cap |
| an Ark transfer to the member's own Ark address | a destination has no Ark address, and no member has a Wavelength wallet in this group to receive one |
| re-using `authorisedScripts` as it is | it licenses transfers and on-chain-account spends to the same script, and is rebuilt per request, so it can be used twice |
