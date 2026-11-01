# Payouts only: the group's wallet pays a member their own money, to their own address

Dedun is a group savings wallet. Since [member-deposits.md](./member-deposits.md),
every sat that comes in is a member's: it lands on their branch or pays their invoice,
and the group page tallies it. Every sat that goes *out* is still anybody's guess. A
member who holds a share can propose a spend of everything the group has confirmed, to
any address, or an invoice to any node. The other signers see the address. Nothing
tells them whose coins are leaving, or whether the payee is the person they belong to.

This plan closes the way out to everything but one shape. **The group's wallet signs
only a *payout*: a transaction whose every output pays a member's own
`GroupPayoutDestination`, and whose every input is a coin that member owns. Each
member's output is all of their coins in the transaction, less their share of the fee.
There is no change. Over Lightning, the only payment the group authorises is a payout
to a member's Lightning address, for no more than their unspent Lightning deposits.
Every member can see every member's destination and balance, and propose paying any
member their whole balance.**

Read [member-deposits.md](./member-deposits.md) first, since this plan consumes its
branches, statements and ledger. Then read
[group-payout-destination.md](./group-payout-destination.md) for the destination and
its two checks, and [group-chain-wallet.md](./group-chain-wallet.md) Phase 10 for the
seven checks a signer makes. This plan adds an eighth.
[group-send-an-amount.md](./group-send-an-amount.md) is superseded: an amount is no
longer something the group can send.

**Built**, Phases 0-6, one commit each, written against `50c21a46` (schema v37, deposits
built through Phase 9), with no schema change. Both spikes ran first, and their results
are under each. `CoinOwnership`, `CoinAssignmentEvent`, `PayoutRule`, `PayoutBuilder`,
`MemberBalances`, `GroupLightningPayouts` and `GroupPayoutsScreen` are the entry points.
The phases below are kept as written. Where the build chose differently, the table says
so.

| what the plan said | what it turned out to be |
|---|---|
| D3: signers check an assigned coin is unspent, against their own wallet | against what the device already holds: a deposit on its ledger, or an output of one of the room's own spends. `acceptProposal` has no wallet to ask, and an assignment of a coin already spent gives nobody anything. A deposit on a member's own branch is refused, since an assignment of it would be inert |
| D3: shares for a closed channel's output prefilled from Lightning balances | every unowned coin is prefilled pro rata to each depositor's tally less what was paid to them. A coin's branch can't say whether it came from a close, a sweep or before deposits were attributed, and the card says *Before deposits were attributed, or from a closed channel* |
| Phase 2: the key notice's answer carries 30341 | not changed: it carries no depositor statement either. The chronicle carries 30341, after 30339 and 30340 |
| Phase 3: a rendered screen test of the assign form | the form's rules, read the fields and say what is left, are the view model's own functions, and are tested there |
| Phase 4: a separate preview screen, `GroupPayoutScreen` | the preview is a card at the foot of the payouts screen: the members chosen, what arrives for each, the fee, the address checks and *Ask the group to pay out* |
| D6: the approval screen says when this device sees coins left behind | not built: the approval screen has no wallet state to see them by. The payout's own lines say whose each input is and what each member gets |
| Phase 4: the transcript names the members paid | as planned: *The group signed a payout of 119400 sats to @alice*, and *The group's payout confirmed* |
| Phase 5: the approval line names the proposer, and the member's balance after | unchanged from the payout destination plan's *as the proposer resolved it*. The signing session already says who proposed it |
| Phase 5: `min(available, wallet spendable)` | the wallet's spendable is the operator's last report of what its normal channels hold for the group. No reserve is subtracted: Spike B found none to subtract on regtest |
| D8: the `lnurl` metadata is required | checked when present, and not required. It proves nothing a lying proposer couldn't forge, and an honest proposer always sends it. Refusing without it would have stranded authorisations proposed by Phase 5 builds that resolved no metadata |
| Phase 6: refused on accept and on complete | and on approve too: a member approving a spend this device accepted before the rule, or before it learnt whose the coins are, declines it instead |
| Phase 6: the subgroup's wallet says its parent can still spend | already said: a guarded wallet's screen lists each guardian and its delay (`guarded_the_groups_above_this_one_can_spend`) |
| Phase 6: the regtest run pays out over Lightning, closes, assigns the close output and pays it out jointly | the run pays the depositor's Lightning balance out as a payout, and files its liquidity purchase as signed before the rule. Assigning the close output and paying it out onchain are covered by `CoinAssignmentsJvmTest` and `GroupSpendSigningJvmTest`, not on regtest |

Tests that sent from the group's own wallet to an arbitrary address, funded a guarded
wallet, sent an amount with change or bought liquidity were rewritten: to pay members out,
to read a funding without proposing one, or to file what was signed before the rule as
signed (`GroupLightningFixture.fileSignedAsRoom`). One was removed: send-an-amount's end
to end, since change is no longer something the wallet makes.

Still to do by hand: Phase 7's four-emulator walkthrough.

---

## What is already there

| piece | where | what it does for this plan |
|---|---|---|
| a coin's branch, verified | `GroupSpend.check` checks 2 and 3. `claimedDerivation(…, memberBranches = true)` (`wallet/GroupSpend.kt:391`) reproduces each input's script from its claimed `…/<branch>/<index>` | an input's branch is a fact every signer already proves. It is the first half of "whose coin is this" |
| a branch's member | `Depositors.statementsIn` (`managers/Depositors.kt:23`), `GroupWalletState.memberOfBranch` (`wallet/GroupOnchainWallet.kt:248`) | maps that branch to a member, from group-signed 30339s, former members included |
| whose a deposit is, by the group's word | `DepositAttributionEvent` (30340), `DepositLedger.depositsIn` (`managers/DepositLedger.kt:137`) | the second half, for legacy branch-0 deposits and corrections |
| a member's payout address | `GroupPayoutDestinations.destinationsIn` (`managers/GroupPayoutDestinations.kt:47`): the newest 30338 per **current** member | the only destination this plan allows |
| a byte match of an output against a member's address | `GroupPayoutDestinations.payeesOf` (`:143`), used by `FrostSigningViewModel.payeesOf` | display only today. This plan makes it a check |
| the signer's acceptance path | `FrostSigningManager.acceptSpendProposal` (`managers/FrostSigningManager.kt:861`), re-run by `checkedSpend` (`:1322`) and `completeSpend` (`:1508`) | where the eighth check goes, on all three |
| per-kind refusal hooks for signed events | `acceptProposal` (`:481`): `Depositors.refuse` (`:611`), `DepositLedger.refuseAttribution` (`:622`) | **none for 30363.** A Lightning authorisation is checked by nobody before signing |
| the Lightning payment authorisation | `GroupLightningAuthorisationEvent.Pay` with an optional `["payout", member, destination]` tag (`nostr/lightning/GroupLightningAuthorisationEvent.kt:34`) | "a label, never a check" today |
| per-HTLC enforcement | `CoSignerPolicy.checkRemoteCommitment` (`wallet/lightning/CoSignerPolicy.kt:93`). Every offered HTLC needs an authorisation with budget left, from `GroupLightningAuthorisations.budgets` (`managers/GroupLightningAuthorisations.kt:50`) | `2t−1` devices answer every payment. This is the strongest place for the Lightning rule |
| the operator paying | `GroupLightningNode.payAuthorised` (`managers/GroupLightningNode.kt:301`) | pays every authorisation it holds and checks nothing else |
| the payout sheet | `ChatRoomDetailScreen` (`:1100-1170`). *Pay onchain* opens `GroupSendRoute(address = …)`. *Pay over Lightning* asks for a free amount (`PayoutAmountDialog`, `:1628`) and resolves the address (`LightningAddressResolver.invoiceFor`) | the starting point for the payout screens. The free amount goes |
| paid out to a member | `DepositLedger.paidOut` (`:154`) | has three faults, listed in fact 5 below |

Six facts decide how the plan is shaped.

1. **Change is the only output the signers don't treat as a payment, and a payout
   needs none.** A spend that takes all of one member's coins and pays them all to
   that member has nothing to give back. With no change, no coin the group holds
   later is a mixture of several members' money. The no-change rule is what keeps
   every future coin attributable.
2. **Not every coin is somebody's.** Before deposits were attributed, coins landed
   on branch 0. Every spend with change put a mixture on branch 1. A Lightning close
   pays `RECEIVE/0` (`GroupLightningNode.kt:208`), and a sweep pays the receive
   branch (`GroupSweep.check`). Under the restriction, a coin with no owner can't
   leave. So the group needs a way to say whose it is, and it has to cover a coin
   owned by several members in shares.
3. **A signer can prove an onchain payee but not a Lightning one.** The output's
   script either equals the member's signed address or it doesn't. An invoice from an
   LNURL server is signed by nobody (payout destination plan, fact 4). The Lightning
   half of this rule is therefore weaker, and the plan says so wherever it shows it.
4. **Nothing checks a 30363 before signing it.** The onchain wallet has seven checks.
   The Lightning authorisation has a human's approval and nothing else. The per-HTLC
   co-signer checks only hash and budget.
5. **`paidOut` can't yet be trusted with a balance.**
   - It matches onchain outputs against each member's *current* destination only, so
     a payout to an address the member has since replaced stops counting.
   - It reads 30363s without `verifies()`.
   - It reads `allPayments()`, which is not scoped to the room or wallet.

   It was good enough for a tally. A Lightning balance is computed from it, and a
   signer would refuse on it, so it has to be right.
6. **A co-signer rule binds only upgraded devices.** Any `t` devices running an
   older build, or a modified one, can sign anything (lightning/README.md: a group is
   only as covered as its slowest device). The restriction is every honest client's
   rule, as the guardian delay and the member-wallet policy are. It is not the
   chain's.

---

## Phase 0: decisions, before any code

**One day, and two spikes.** D1 to D12 change what every later phase builds. Two of
them were the owner's to decide, and are marked **(decided)**: D10 and D11, both as
recommended.

### D1. What a payout is

A spend of the group's own account (`Spender.Owner`, no `guarded` tag, no `sweep`
tag) is accepted only when:

- every input is owned (D2), entirely, by members named in the outputs;
- every output pays one member's payout address (D7), and no member is paid twice;
- each member's output equals what they own among the inputs, less their fee share
  (D5), exactly;
- no output is change, and none is a funding.

A payout to one member is the common case. Several members in one transaction is the
same rule. It happens when a shared coin is spent (D4), or when the proposer selects
several members to save fees. A fee bump of a payout is still a payout: same inputs,
same members, a larger fee re-split.

**Sweeps are not payouts and stay as they are.** A sweep moves a closed channel's
output *into* the wallet, and `GroupSweep.check` already refuses one that pays
anywhere else. **Guardian spends are out of reach.** An ancestor spends a
subgroup's coins through a taproot leaf, after a delay. The ancestor's signers sign
it, under the ancestor's rules, and the subgroup's signers never see it. That leaf
is the recovery path for a subgroup that can't sign. This plan leaves it alone and
says so on the subgroup's wallet screen (Phase 6).

### D2. Whose a coin is

A coin's owner is decided, in this order, by:

1. the newest coin assignment (30341, D3) that names it. This applies only to a coin
   on branch 0 or 1;
2. the newest deposit attribution (30340) that names it as a deposit. `group` there
   means nobody;
3. its branch, when that branch is a depositor's;
4. otherwise nobody.

Every rule reads only what a signer already holds: the input's claimed derivation,
which checks 2 and 3 have proved, and group-signed events, which the chronicle
carries. It needs no Electrum and no local ledger, so two devices with the same
signed history always agree on whose a coin is. `witness_utxo` gives the amount, and
taproot's sighash commits to every input's amount, so a proposer can't lie about it.

### D3. A coin nobody owns waits until the group signs whose it is: kind 30341

```
kind:    30341
pubkey:  <the room>
content: ""
tags:
  ["chain", "mainnet" | …]
  ["coin",  "<txid>:<vout>", "<sat>"]                       one per coin, the whole amount
  ["share", "<txid>:<vout>", <member>, "<sat>"]             one or more per coin, summing to it
```

- **A new kind rather than a new tag on 30340.** A 30340 says who *deposited*, and
  the tallies count it. A change coin assigned as a deposit would count its money
  twice, once when it came in and again now. A 30341 says who *owns* a coin. It moves
  no tally, and it is read only by the owner rule and the balances.
- **Branch 0 and 1 only.** A coin on a member's branch is that member's. A mistake
  there is corrected the way it is today, by a 30340.
- **Every sat is a member's.** No `group` share: a share nobody owns could never
  leave. Every share is at least the dust limit (546 sats), and every member named
  has a depositor statement on that chain.
- **Newest wins per coin**, as for 30340.
- **Signers check it against their own wallet**: the coin is unspent, it is on
  branch 0 or 1 of this room's account (`derivationOf`), and its amount is the one
  stated. A signer whose wallet hasn't loaded refuses with *can't see that coin yet*
  rather than signing on a guess.
- **Prefilled, never guessed.** The assign screen (Phase 3) suggests shares:
  - for a close or sweep output, from each member's Lightning balance at the close
    (D8), pro rata, with the closing fee shared the same way;
  - for a legacy change or branch-0 coin, pro rata to each member's tally.

  The group signs whatever the proposer ends up with, and each signer sees the
  shares.

Why 30341: the next number after 30340. Nothing in the code or the docs uses
30341 to 30348, and the Lightning kinds start at 30349.

### D4. A shared coin is paid out to all its owners at once

A coin owned in shares can't pay one owner without change, and change would make a
new mixed coin. So spending it pays every owner their share, each to their own
address, in the same transaction. When Alice's payout includes a coin she shares
with Bob, the preview says *This also pays @bob 40,000 sats, his share of a coin he
owns with you*. The signers check Bob's output exactly as they check Alice's.

The alternative was an internal split: one transaction that moves a shared coin onto
each owner's own branch, after which each owner is paid separately. It is more
pleasant, since nobody is paid before they asked. But it is a second shape of spend,
with outputs that stay in the wallet, and the rule this plan was asked for is *only to
members' destinations*. It is in the appendix, as the first follow-up to reach for if
joint payouts annoy people.

### D5. The fee is shared pro rata, by one function both sides run

`PayoutFees.split(owned: Map<member, sat>, fee): Map<member, sat>` gives each member
`floor(fee × owned / total)`. The remainder sats go one each to the members with the
most owned, ties broken by public key. The builder computes the fee from the
transaction's virtual size, which doesn't depend on amounts, and then the outputs. A
signer recomputes the split from the inputs and the fee and requires each output to
match it to the sat. Check 6's fee-rate ceiling still applies to the whole.

Pro rata by value is simple, and it is how a savings group shares a cost. Splitting by
each member's input weight is fairer to members with few coins, but the rounding gets
harder to agree on. With one member per payout, the usual case, the two are the same.

A member's output below dust after their share fails the whole build
(`BelowDust(member)`), and the preview names who.

### D6. "Everything they have" is the proposer's job. The signers check the shape

The proposer takes every coin of the selected member that is confirmed, not reserved
by an open spend, and past any lock. That is the member's whole spendable balance.
Signers check D1, and **not** that every coin of the member is included. A signer's
Electrum may know a coin the proposer's doesn't yet, and the other way round. A rule
that required "all" would stall every payout that raced a deposit. A payout that
leaves one of Alice's coins behind still pays Alice only Alice's money. The approval
screen says so when this device sees more: *Leaves 1 of @alice's coins, 30,000 sats,
for a later payout.*

A member has at most one open payout at a time. The builder refuses with *@alice
already has a payout waiting for signatures* while one is unsigned, the same
reservation `reservedOutPoints` already makes.

### D7. Which address

- **A current member:** their newest destination's onchain address, as today.
- **A former member:** their newest destination this device holds. Their 30338 rows
  outlive their membership (payout destination plan, D3). If the device holds none, it
  uses the destination embedded in their depositor statement, which is group-signed
  and chronicled. A member removed from the room is the case where the money most
  needs to go back, and `destinationsIn`'s filter to current members is right for
  display but wrong here. So payouts use a new `payoutDestinationOf`, not
  `destinationOf`.
- **A removed destination is honoured.** No payout goes out until the member sets a
  new one. For a former member who removed theirs before leaving, that means never,
  short of adding them again. The member row says so.
- **No onchain address, no onchain payout.** The destination's onchain address is
  optional. A member who has onchain coins and no onchain address sees *You have
  120,000 sats onchain in #Council. Add an onchain address to be paid them.* on their
  own payout row. Everybody else sees *@alice hasn't given an onchain address*.
- **Signers match the newest they hold, byte for byte.** A payout to an address the
  member has since replaced is refused, as *pays an address @alice has since
  replaced*. So is a payout to a removed destination. A signer on a stale destination
  refuses too, and the resend (payout destination plan, Phase 6) heals it.
- **Used addresses.** The proposer runs `UnusedAddressCheck`.
  - *Paid before*, for a current member: the screen says to ask them for a new
    address and won't build.
  - *Paid before*, for a former member: it builds after a warning, because they can't
    give a new one.
  - *Couldn't check*: it won't build.

  Signers don't check usage. The answer depends on each device's server, and reuse
  costs privacy, not money.
- **An address that two members both signed** can't be told apart, and a payout to it
  is refused, as *pays an address more than one member gave*.

### D8. Lightning: a member's balance, and what the signers can and can't check

**A member's Lightning balance** is computed per device from the ledger:

```
available = their Lightning deposits (net of the LSP's fee, 30340 applied)
          − payouts to them the node reports sent (amount + the fee reported, or maxfee)
          − their authorised payouts not yet sent or expired (amount + maxfee)
```

**A 30363 `pay` is signed only when:**

- it carries `payout`, and the member has a current or former destination (D7);
- its `amount + maxfee` is no more than that member's `available` on this device;
- it carries a new `["lnurl", <metadata>]` tag. The invoice's description hash is
  `sha256(metadata)`, and the metadata's `text/identifier` (or `text/email`) entry is
  the member's Lightning address, as LUD-16 requires.

**What that proves, and what it doesn't.** It proves the amount is the member's to
spend, on this device's ledger. It proves the invoice commits to metadata that names
their address. It does **not** prove the invoice pays them: a proposer can write that
metadata into an invoice from their own node. The approval line keeps *as the proposer
resolved it*, and names the proposer. The damage a lying proposer can do is bounded
by one member's Lightning balance, and the transcript records who proposed it. A
member-signed invoice, where Alice's own device hands over the invoice and signs it,
closes the gap. It is in the appendix as a follow-up.

**The per-HTLC co-signers enforce it too.** `GroupLightningAuthorisations.budgets`
counts only authorisations that carry `payout`. An authorisation signed by `t` older
devices without one then can't get past the `2t−1` co-signers who must answer for
each HTLC. The operator's `payAuthorised` skips one too, as defence in depth.

**The balance can promise more than the channel holds.** The channel reserve, the
commitment fee, and any liquidity bought before this plan make the channel's
spendable balance smaller than the sum of members' balances. The screen offers
`min(available, wallet spendable)` and says why when it is the smaller. The last
member to withdraw bears the difference. Sharing that cost is the group's rule to
make (member-deposits D8), and this plan doesn't make it.

### D9. What stops, and what stays

| path | under the restriction | why |
|---|---|---|
| send everything to any address (`GroupSendScreen`) | **gone** for the group's own account | the restriction |
| send an amount, with change | **gone** | not a payout, and change mixes coins (fact 1) |
| fund a subgroup or a member's guarded wallet (`Destination.Funding`) | **gone** | not a member's destination. Paying a member into their own guarded wallet is a follow-up |
| pay any BOLT11 invoice (`GroupLightningSendScreen` with free entry) | **gone** | the restriction |
| buy inbound liquidity (30363 `buy-liquidity`) | **refused by signers** | it pays the LSP, a cost nobody's balance is charged with. It comes back once the group has a cost-sharing rule |
| the LSP's on-the-fly channel open for an incoming payment | stays | its fee comes out of the deposit, which is already counted net |
| close, force close | stay | they pay the group's own wallet. Refusing them could strand the group's money with a lost operator. The output is assigned by 30341 (D3) |
| sweep a closed channel's output | stays | pays the group's own receive branch |
| guardian spend from a descendant | untouched | signed by the ancestor (D1) |
| fee bump | stays, as a payout's bump | D1 |
| a member's own guarded-wallet spend | untouched | the member's key, the member's coins |

### D10. The mainnet cap and payouts **(decided)**

`MAINNET_CAP` holds a spend proposed from this device to 100,000 sats. It applies
only at the proposer, and a full-balance payout over it can't be built. **Decided:
payouts are exempt.**

- The cap bounds a mistake in a spend to an arbitrary address.
- A payout's every output is checked by every signer against the payee's own
  signature.
- With the restriction, the cap is the only thing between a member with 150,000 sats
  and getting them back.

The alternative keeps the cap and lets a payout take coins up to it, a partial
payout, until the cap lifts. That breaks D6's "everything", and on mainnet it makes
large balances take several rounds.

### D11. Unconditional, not a policy each group turns on **(decided)**

**Decided: every group's own wallet is payouts-only from the release that ships
Phase 6.** There is no group-signed switch.

- It is what the app is for, and what was asked.
- A switch is a ceremony every existing group would have to hold before it is
  protected.
- A switch the quorum can turn off protects nobody from the quorum.

The cost is everything in D9 marked *gone*. A group that wants to pay an outside party
together can't do it from the group wallet. Each member is paid out and pays from
their own wallet.

The alternative is a group-signed policy event, like the member-wallet policy
(30337), which a group signs to opt in and perhaps can never sign off. It is in the
appendix.

### D12. Mixed versions, and what the restriction guarantees

- **A payout is an ordinary spend to an older signer.** Member-branch inputs are
  accepted since deposits Phase 2, and the transaction has one output per member and
  no change. Phases 4 and 5 therefore work in a group with older devices and need no
  release ahead.
- **30341 needs its transcript arm a release ahead.** Spike A checks what an older
  device does with a signing proposal of a kind it doesn't know. If it signs on a
  human's approval with no check, as `acceptProposal`'s structure suggests, the
  proposal is shown as raw JSON. The arm ships in Phase 2, before anybody can propose
  one.
- **The restriction is a refusal, and it binds only upgraded signers.** In a 2-of-3
  group with one upgraded device, two older devices can still sign anything. The
  wallet screen can't count which devices enforce it, since there is no capability
  signal, so the plan doesn't claim a group is covered. It says *Your device signs
  only payouts* (Phase 6).
- **Open proposals at the upgrade.** A non-payout spend still waiting for signatures
  is refused by `completeSpend`'s re-check on every upgraded device, and fails. A
  30363 already signed without `payout` stops being payable once the co-signers
  upgrade, and expires within 24 hours.

### Spike A: what an older signer does with an unknown kind

On two JVM test devices, one on `50c21a46`, propose a FROST signing session over a
hand-built 30341 and over a 30363 `pay` with no `payout` tag. Record whether the old
device shows it, signs it on approval, or drops it. The answer decides whether
Phase 2 must ship a release ahead (it signs or shows) or only should (it drops). It
also decides what Phase 6's notes say about older devices.

**Result: it shows it, and signs it on approval.** Answered by the code rather than a
run. `FrostSigningManager.acceptProposal` refuses a proposal for its shape (no key, a
batch too large, an id that doesn't rebuild), and then runs one hook per kind it knows:
the birth certificate, the member-wallet certificate, 30339, 30340, the wallet
statement, the key state. A kind with no hook falls through to a session, which the
member approves by hand. So an older device shows a 30341 as an unknown event and
signs it if its member approves it. It signs a 30363 `pay` with no `payout` tag in the
same way, which is what it does today. Phase 2's transcript arm therefore ships a
release ahead, as D12 says. Phase 6's notes say a group is covered only once enough
devices are upgraded.

### Spike B: the Lightning balance against the channel, on regtest

On the regtest network (`testing/regtest/regtest.sh up`), take the group's channel
through two attributed deposits, one payout, and a close. Write down, at each step:
the sum of members' `available` from D8, `PaymentView`'s reported fees, the
channel's spendable balance, and the close output's amount. That is the gap D8
promises to show and the shares D3 prefills, measured. It checks that the snapshot
reports the outgoing fee; if it doesn't, D8 uses `maxfee`.

**Result, in part.** The fee question is answered by the code: `PaymentView.feesMsat`
is reported for every payment, and `GroupLightningPaymentRow` keeps it, so D8 charges
the fee the node reports, and uses `maxfee` only for an authorisation not yet sent. The
measured gap between members' balances and the channel is left to Phase 6's regtest
case, which goes through deposits, a payout and a close in that order. That makes it
the same run, once rather than twice.

**Measured, 2026-11-01**, `GroupLightningRegtestJvmTest` after Phase 6. The depositor's
96,900 sats arrived, net of the LSP's fee. 50,000 more arrived unattributed after a
liquidity purchase that cost 2,521 sats. The payout was 20,000 sats with 84 in routing
fees. The mutual close paid 124,295 sats to the onchain wallet, which is
96,900 + 50,000 − 2,521 − 20,084 to the sat. The only gap between what members are owed
and what the channel holds is the liquidity purchase, which D9 now refuses. There is no
reserve to subtract.

---

## Phase 1: who owns what, and the rule, pure

**Three days.** Dark: nothing calls it.

- `wallet/CoinOwnership.kt`:
  - `Owner` = `Member(member)`, `Shares(Map<member, sat>)`, `Nobody(why)`;
  - `ownerOf(outPoint, derivation, amount, depositors, attributions, assignments)`
    in D2's order.
- `nostr/wallet/CoinAssignmentEvent.kt` (30341): `template`, `read` (shape only),
  `verify(event, room)`, `newestPerCoin`. Its kdoc carries *why a new kind* and *why
  30341*, as 30340's does.
- `wallet/PayoutFees.kt`: `split(owned, fee)`, D5.
- `wallet/PayoutRule.kt`: `check(checked: GroupSpend.Checked, context: PayoutContext):
  Result`.
  - `PayoutContext` holds the owners by outpoint and the payout script by member.
  - `Result` = `Payout(members: List<MemberPayout>)` or `Refused(reason)`.
  - Each `MemberPayout` carries `member`, `owned`, `feeShare`, `paid` and `script`.
  - It is pure, and next to `GroupSpend.check`, which it runs after.
- `wallet/lightning/LightningBalance.kt`: `available(deposits, sent, open)`, D8.
- Tests, one mutation per check, as `GroupSpendTest` does:
  - `CoinOwnershipTest`: precedence; a 30341 on a member branch is ignored; 30340
    `group` means nobody.
  - `CoinAssignmentEventTest`: shares that don't sum; a share below dust; a member
    twice; a `group` share; another room's chain.
  - `PayoutFeesTest`: the remainder; ties; one member.
  - `PayoutRuleTest`:
    - one member, all theirs;
    - an input nobody owns;
    - an input another member owns, with that member unpaid;
    - an output to a non-member;
    - an output a sat too large, and one a sat too small;
    - change;
    - a funding;
    - a member paid twice;
    - a shared coin with one owner left out;
    - a script two members gave;
    - a bump that re-splits.
  - `LightningBalanceTest`.

## Phase 2: the ledger fixed, balances read, and 30341 in the transcript

**Two days.** **Ships a release ahead of Phase 3** (D12).

- `DepositLedger.paidOut`, fixing fact 5:
  - onchain outputs are matched against every destination each member has signed,
    not just their newest, through a new `GroupPayoutDestinations.everSignedBy`;
  - 30363s are filtered by `verifies()`;
  - payments are read for this room's wallet only, through a new
    `paymentsOf(walletKey)` on the DAO, replacing `allPayments()` here.
- `managers/MemberBalances.kt`: `balancesIn(db, room, chain, walletState):
  Map<member, MemberBalance>`. Each balance holds:
  - `onchainConfirmed`, `onchainPending` and `coins`, which are the member's own,
    with their shares listed apart;
  - `lightningAvailable` and `lightningOpen`;
  - `destination`, from `payoutDestinationOf`;
  - `isFormerMember`.

  It also returns `unowned: List<GroupUtxo>`. It is built on Phase 1's pure pieces
  and on `GroupWalletState.utxos`.
- `GroupPayoutDestinations.payoutDestinationOf(db, room, chain, member)`, D7.
- The transcript arm for 30341, *#Council assigned 3 coins*, in
  `GROUP_IDENTITY_TYPES`. `ChronicleKinds` gains 30341
  (`nostr/chronicle/ChronicleEvent.kt:162`), and so does the key notice's answer, as
  for 30339 and 30340.
- Tests:
  - `DepositLedgerJvmTest`: a payout to a replaced address still counts; an
    unverified 30363 doesn't; another wallet's payments don't.
  - `MemberBalancesJvmTest`: a former member's balance; a shared coin; a coin nobody
    owns; Lightning available after a sent payout and with an open one.

## Phase 3: the group says whose the rest is

**Three days.** Ships with Phase 2's release or the next. **A release ahead of
Phase 6**, so a group can give every coin an owner before any coin is frozen.

- `managers/CoinAssignments.kt`: `propose(db, room, proposer, chain, shares)` and
  `refuse(db, room, proposed)`, D3's signer checks. The refusal is hooked into
  `acceptProposal` beside `refuseAttribution`.
- `GroupAssignCoinsScreen`, reached from the payouts screen's *Not anyone's yet*
  row (Phase 4), and from the wallet screen while any coin is unowned.
  - It lists each unowned coin: its amount, where it came from (*Before deposits were
    attributed*, *Change*, *From a closed channel*, *Swept from a closed channel*),
    and its suggested shares (D3).
  - The shares are editable per member, in whole sats, with `parseSats`. The screen
    shows what is left to assign and won't propose until it is zero.
  - It is shown only on a device that holds a share.
  - It follows CLAUDE.md: a `TopAppBar` with `NavigateBackButton`,
    `ScreenStateTransition`, `readableContent()`, spacing from the scale, strings in
    sentence case, and `rememberNotifier` for *Asked the group to assign 3 coins*.
- Tests:
  - `CoinAssignmentsJvmTest`: a coin the signer's wallet doesn't hold; a spent coin;
    a coin on a member branch; the wrong amount; a non-depositor share; a correction
    wins.
  - `GroupAssignCoinsScreenJvmTest`: it won't propose until fully assigned.

## Phase 4: paying a member onchain

**Four days.** **A release of its own.** Payouts work in any group from here, older
signers included (D12). The old send paths still exist beside them until Phase 6.

- `wallet/PayoutBuilder.kt`:
  - `build(state, members, context, feerate, reserved): Result`. It takes every
    spendable coin owned by the selected members, plus every shared coin any of them
    owns, which brings that coin's other owners in (D4). It computes the virtual size
    and the fee, splits the fee, and writes one output per member.
  - `bump(checked, payout, feerate)` re-splits a larger fee over the same inputs and
    members.
  - It reuses `GroupSpend.psbtOf` with no change map, and runs `PayoutRule.check` on
    its own output before handing it back, so the proposer never sends what a signer
    would refuse.
  - `Result` = `Built(psbt, payout, fee)`, `NothingToPay(member)`,
    `BelowDust(member)`, `NoOnchainAddress(member)`, `AlreadyWaiting(member)`,
    `FindingCoins`.
- `GroupPayoutsRoute(chatRoomId, chain)` and `GroupPayoutsScreen`, reached from the
  wallet screen and from the detail screen's *Payouts* section.
  - One row per member who has a balance or a destination, former members last. Each
    row shows the name, the Lightning address, the onchain address or *No onchain
    address*, *Onchain 120,000 sats in 3 coins*, *Lightning 30,000 sats*, and a
    *pending* line for unconfirmed coins.
  - A row's checkbox selects it for *Pay out onchain*. The FAB-sized button reads
    *Pay out 2 members*.
  - Each row has its own *Pay out over Lightning* (Phase 5).
  - A *Not anyone's yet* row with the total opens Phase 3's screen.
  - It has the four states, with the empty one reading *Nobody has a balance in this
    group yet*. Checkbox rows are `toggleable` with `minimumInteractiveComponentSize()`.
- `GroupPayoutScreen`, the preview, which the `GroupSendRoute` buttons now open for
  the group's own account.
  - It shows each member's coins and shares, their fee share, *Arrives: 119,400 sats*,
    the address, its usage check (D7), and D4's line when a shared coin brings others
    in.
  - It keeps the fee-rate field from `GroupSendScreen`, the *Nothing leaves until a
    quorum signs* line, and *Propose*.
- What a co-signer sees:
  - `ProposedSpend.summarize` reads *Pay out @alice: 119,400 sats to her onchain
    address*, or *Pay out 2 members*.
  - `FrostSigningScreen.WhatIsBeingSpent` labels each input's owner and how that is
    known (*by her branch*, *assigned by the group*, *her share of a shared coin*). It
    labels each output *@alice's payout address: her 120,000 less 600 fee*, and adds
    D6's line when this device sees coins left behind.
  - Before Phase 6 an upgraded device also shows a payout badge, *Every output pays
    a member their own coins*, which a non-payout spend lacks.
- The transcript's *signed* and *confirmed* lines for a payout read *paid out
  @alice 119,400 sats*, from `PayoutRule` run on the device that files them.
- Bump: `GroupWalletViewModel.bumpFee` routes a payout through `PayoutBuilder.bump`.
- Tests:
  - `PayoutBuilderTest`: one member; two; a shared coin pulling its co-owner in;
    dust; reserved coins; no onchain address; a bump.
  - `GroupPayoutsScreenJvmTest` and `GroupPayoutScreenJvmTest`: each row state, and a
    former member's warning on a used address.
  - `GroupSpendSigningJvmTest`, end to end: Alice's branch coins and her share of an
    assigned coin are paid out. The other device's `checkedSpend` reads the same
    `PayoutRule.Payout`. Both transactions pass `correctlySpends`. After
    `recordConfirmed`, Alice's tally shows the payout, and her balance is zero.

## Phase 5: paying a member over Lightning

**Three days.** With Phase 4, or the release after.

- `LightningAddressResolver.invoiceFor` keeps the metadata string it already hashes
  (`LnurlPay.Intent.Metadata.raw`) and returns it with `Ready`.
- `GroupLightningAuthorisationEvent.Pay` gains the optional `["lnurl", metadata]`
  tag. `read()` keeps it, and an older build ignores it.
- *Pay out over Lightning* on a payouts row computes the amount. It takes
  `min(available, wallet spendable)` (D8), less a `maxfee` of
  `max(10 sats, 1%)`, so that `amount + maxfee ≤ available`. It shows
  *Pays @alice 29,690 sats, up to 300 in routing fees, to alice@example.com*. Then it
  resolves, prepares and proposes with `payout` and `lnurl`. `PayoutAmountDialog` and
  its free amount go.
- `ProposedEvent`'s line for a payout keeps *as @bob resolved it* (D8) and adds the
  member's Lightning balance after the payment.
- Tests:
  - `PayoutInvoiceTest` on `MockEngine`: the metadata comes back with `Ready`.
  - `GroupLightningAuthorisationEventTest`: the `lnurl` tag round-trips, and an
    older-shape event reads.
  - A view model test where the amount plus `maxfee` never exceeds `available`,
    including at 10 sats.

## Phase 6: the restriction

**Three days.** **The release that makes the wallet payouts-only.** It comes after
Phases 2-5 have each had a release (D11, D12).

- **Onchain.** In `acceptSpendProposal`, `checkedSpend` and `completeSpend`, for the
  group's own account, `PayoutRule.check` runs after `GroupSpend.check` and
  `withFundings`. A refusal drops the proposal with its reason. On `completeSpend`, it
  fails the session. The context is built by `PayoutRule.contextFor(db, room, chain)`
  from the held 30339s, 30340s and 30341s and `payoutDestinationOf`. Guardian spends
  and sweeps skip it (D1).
- **Lightning, before signing.** `acceptProposal` gains a 30363 hook,
  `GroupLightningPayouts.refuse`, which refuses:
  - a `pay` without `payout`, or with one that fails D8;
  - every `buy-liquidity` (D9).

  `close` and `force-close` pass.
- **Lightning, per HTLC.** `GroupLightningAuthorisations.budgets` counts only
  payout-tagged authorisations (D8). `GroupLightningNode.payAuthorised` skips the
  rest.
- **The old ways out go:**
  - `GroupSendScreen`'s address field, *Send everything* switch and amount field are
    removed for the group's own account. The screen stays only for a guardian's
    spend, its one remaining caller. `GroupSendRoute(fundChatRoomId)` and the wallet
    screen's *Fund* button go.
  - `GroupLightningSendScreen`'s free invoice entry goes. The route opens only from a
    payout.
  - The *Buy liquidity* entry is hidden.
  - Strings that have lost their last reader are removed from `strings.xml`.
- **What the screens say.**
  - The wallet screen's send button becomes *Pay out*.
  - Under the balance, it says *Your device signs only payouts to members' own
    addresses* (D12).
  - On a subgroup's wallet, it adds *#Parent can still spend from this wallet after
    12 blocks* (D1).
- Tests:
  - `GroupSpendSigningJvmTest`:
    - a send-all to a stranger is refused on accept;
    - an open one fails on complete;
    - a payout is accepted;
    - a payout with an input another member owns, where that member is unpaid, is
      refused;
    - a guardian spend and a sweep are unaffected.
  - `GroupLightningPayoutsJvmTest`:
    - no `payout` tag;
    - over balance;
    - a removed destination;
    - metadata naming another address;
    - `buy-liquidity` refused;
    - `close` accepted.
  - `CoSignerPolicyTest`: an HTLC for an authorisation without `payout` has no
    budget.
  - `GroupLightningRegtestJvmTest`: a payout to a member's Lightning address at a
    test LNURL server; a close; a 30341 over the close output; then a joint onchain
    payout.

## Phase 7: docs, and checking it by hand

**One and a half days.**

- This note's *Built* line, and a table of where the build chose differently. A row in
  [README.md](./README.md).
- Pointers in the notes this one changes:
  - group-send-an-amount.md: superseded;
  - group-payout-destination.md Phase 7: the free amount is gone;
  - guarded-wallet-funding.md: funding is off for a group's own account;
  - group-lightning-liquidity.md: purchases are refused while the cost-sharing rule
    is missing;
  - member-deposits.md: the owner rule, and 30341.
- Run the checks:
  - `./gradlew :composeApp:m3Audit`
  - `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`
  - the regtest run
- Four emulators against regtest: Alice, Bob, Carol, and Dan. Dan is on the build
  before Phase 6.
  1. Alice and Bob deposit onchain and over Lightning. Every device's payouts screen
     shows both balances and both destinations.
  2. A legacy branch-0 coin shows as *Not anyone's yet*. Carol assigns it 60/40 to
     Alice and Bob, and the group signs.
  3. Carol selects Alice. The preview takes Alice's branch coins and the shared coin,
     and says it also pays Bob his 40%. Bob and Carol sign. Both outputs arrive, and
     Alice's balance is zero.
  4. Bob's onchain address is now *paid before*. Selecting him says to ask him for a
     new one. He sets one, and his next payout builds.
  5. Carol pays Alice's Lightning balance out. The authorisation shows *as @carol
     resolved it*. It is paid, and Alice's Lightning balance drops by the amount and
     the fee.
  6. Dan, on the older build, proposes a send-all to an outside address. Alice's and
     Carol's devices refuse it. Dan alone can't reach the threshold.
  7. Bob removes his onchain address. His onchain balance says it waits for one.
  8. Bob is removed from the room. His row moves to former members, with his balance.
     A payout to his last destination builds after the *paid before* warning.
  9. The channel is closed. The close output appears as *From a closed channel*, with
     shares prefilled from the Lightning balances. The group signs them, and a joint
     payout empties it.

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
testing/regtest/regtest.sh up
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 0 | this note, two spikes | nothing |
| 1 | owners, 30341, the fee split, the payout rule, the Lightning balance, pure | dark |
| 2 | the ledger's three fixes, balances, 30341's transcript arm and chronicle | **a release ahead of 3** (D12) |
| 3 | assigning coins: proposal, signer check, screen | **a release ahead of 6**, so every coin can have an owner before anything is frozen |
| 4 | the payout builder, the payouts and preview screens, approval labels | its own release. Works with older signers |
| 5 | Lightning payouts at the full balance, the `lnurl` tag | with 4, or right after |
| 6 | the signers' refusal on both rails, the co-signer budgets, removing the old ways out | **last, alone**: from here the wallet is payouts-only |
| 7 | docs and the emulator run | with 6 |

About four weeks of work for Phases 0-7. The restriction itself, Phase 6, is three
days of it. **Phase 6 must not ship before Phase 3 has had a release.** Otherwise
every group with a legacy or change coin, or a closed channel, finds that money
frozen the day it upgrades, with no screen to unfreeze it.

---

## What this does not do

**It does not bind a guardian.** An ancestor's spend through the leaf is the
ancestor's to sign (D1). A subgroup's members are told.

**It does not prove a Lightning payee.** It bounds a misdirected payment to one
member's Lightning balance and names the proposer (D8).

**It does not share costs.** Liquidity purchases are refused rather than charged to
somebody. The channel's reserve and fees fall on whoever withdraws last, and the
screen says so (D8).

**It does not pay a member's Lightning balance onchain, or the other way round.** There
is no swap, and no splice-out. A member with Lightning deposits and no way to receive
over Lightning waits, or the group closes the channel and assigns the output (D3).

**It does not let a group pay anybody else.** That is the point (D11). A collective
purchase is each member's payout, then each member's own payment.

**It is not enforced by the chain.** It binds upgraded signers only (fact 6, D12).

**It does not touch members' guarded wallets.** They have one owner already.

**It does not send part of a balance.** A payout is everything a member has that is
confirmed. A partial payout with change back to the member's own branch could be
checked in the same way, and is the second follow-up below.

## Appendix: what was considered and rejected

| idea | why not |
|---|---|
| a group-signed policy that turns payouts-only on | a ceremony before any group is protected, and one the quorum can undo (D11). The fallback if unconditional is too strong for some groups |
| splitting a shared coin onto its owners' branches, internally | a second shape of spend that stays in the wallet, outside *only to members' destinations* (D4). **First follow-up** if joint payouts annoy people |
| a partial payout, with change to the member's own branch | verifiable, since the change derives on the payee's branch, but the ask was the whole balance. **Second follow-up** |
| signers require *every* coin of the member | stalls every payout that races a deposit, and buys nothing: a partial payout still pays only the member's own money (D6) |
| attributing change coins with 30340 | counts a deposit twice in the tallies (D3) |
| a `group` share in a coin assignment | a share nobody owns can never leave (D3) |
| the fee split by input weight | fairer to a member with few coins, harder to round identically on every device. Pro rata is the same with one member (D5) |
| a `payout` tag on the spend proposal | nothing to add. Every signer finds the payees itself, as the payout destination plan's Phase 7 found |
| a member-signed Lightning invoice, required for every Lightning payout | proves the payee, but only the member themselves could start a payout, and the ask was that any member can. **Third follow-up**, as an option: a payout the member requested shows as verified |
| keeping buy-liquidity, charged to the proposer | makes one member pay for a channel everyone uses. It waits for the group's cost-sharing rule |
| refusing closes | could strand the money with a lost operator. A close pays the group's own wallet, and 30341 gives the output owners (D9) |
| reading balances from the tallies (deposits − paid out) onchain | the unspent coins are what can be spent. A tally is a history, and fees make it larger than the coins (member-deposits D8) |
