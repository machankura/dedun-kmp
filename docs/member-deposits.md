# Whose deposit it is: every address and invoice attributed to a member before it is shown

Dedun is a group savings wallet. A group saves together, so it needs to say who put
each sat in, and how much each member has put in altogether. Today it can't say
either. The group's onchain wallet shows one receive address, the lowest unused one,
and every device shows the same address to every member. Nothing records who it was
handed to, and a deposit's row says only *Received*. The Lightning wallet hands out
invoices to whoever asks, and it also shows a BOLT12 offer that anybody can pay any
number of times. An incoming payment's row also says only *Received*. Nothing on
either side joins a deposit to a person.

This plan makes attribution come first. **No receive address or invoice is shown in
the app until it belongs to one member, and a member can be given one only once
they have a `GroupPayoutDestination` in the group. The group's quorum signs, once
per member per network, a *depositor statement* that embeds that destination whole.
That statement gives the member a branch of the group's onchain wallet that is
theirs alone. Every Lightning invoice is issued against it. Every deposit is then
counted to the member whose branch or invoice it paid, and the group page shows a
tally per member. A deposit that matches nobody's branch or invoice is counted as
*unattributed* until the quorum signs whose it is.** BOLT12 is switched off for
now: an offer is one reusable code for every payer, so there's no one person to
attribute it to.

Read [group-payout-destination.md](./group-payout-destination.md) first. This plan
is its natural consumer: the destination is where a member's money goes back to, and
this plan is about where it comes in from. Then read
[group-chain-wallet.md](./group-chain-wallet.md) Phases 4, 5 and 10 (the derivation
path and the seven signer checks), [group-wallet-addresses.md](./group-wallet-addresses.md)
(why the address list stops at current + 2), and the Lightning wallet's Phases 11
and 12 in [group-lightning-wallet.md](./group-lightning-wallet.md) (snapshots, the
invoice request, the offer).

**Planned.** Written against `088a33f5` (the payout destination, built), schema v35.
Phase 0 is done: the three spikes have run, and their results are under each.

---

## What is already there

| piece | where | what it does for this plan |
|---|---|---|
| a member's signed payout destination, carried whole | `GroupPayoutDestinationEvent` (30338), `GroupPayoutDestinations` | the precondition, and what the depositor statement embeds |
| the group's onchain account, BIP86's unhardened tail under the group key | `GroupWalletDerivation.derive(account, change, index)` (`managers/GroupWalletDerivation.kt:257`). `RECEIVE = 0`, `CHANGE = 1` | the branch argument is already just a number; the derivation takes any branch |
| two Electrum watchers per account, lookahead 10 | `GroupOnchainWallet` (`wallet/GroupOnchainWallet.kt:42-43, 88, 96`) | nothing is stored; everything is re-fetched from Electrum at start |
| the address every device shows | `GroupWalletState.receiveAddress` = `firstUnusedDerivedAddress` (`:177`), the receive card on `GroupWalletScreen` (`:543-588`), `GroupWalletAddressesScreen` | the address this plan stops showing |
| the coin list | `CoinRow` (`GroupWalletScreen.kt:997-1013`): amount, *Received* or *Change*, confirmations | no address, no txid, no sender; and a spent coin disappears, so there is no history at all |
| what a signer accepts as the group's own input or change | `GroupSpend` (`wallet/GroupSpend.kt:222-232, 360, 373, 392`): a claimed derivation is `RECEIVE` or `CHANGE`, index ≤ 100,000 | **refuses any other branch**, so a coin on a member's branch would be unspendable on every device that has not upgraded |
| the invoice request and reply | `InvoiceRequest(wallet, requestId, amountMsat?, description)` (30364), `InvoiceReply(wallet, requestId, bolt11)` (30365), `GroupLightningReports.requestInvoice` / `answer` / `receiveInvoice` | the requester is MLS-authenticated (`MarmotInboundManager.mip03Rejection`) but named nowhere in the record; the reply is checked only for coming from the operator and paying the node -- not against the request |
| invoices on the operator | `GroupLightningInvoiceRow(wallet, requestId, bolt11)` | no requester, no payment hash column |
| incoming payments | `PaymentView` in the operator's snapshot (`wallet/lightning/WalletWire.kt:30-41`), stored as `GroupLightningPaymentRow` | carries `paymentHash` for incoming payments; nothing joins it to a request |
| the BOLT12 offer | `GroupLightningNode` `offer()` (`:147, 266, 652`), `GroupLightningReports.offerOf`, the receive screen (`GroupLightningReceiveScreen.kt:164-176`) | shown as text beside the invoice request |
| lightning-kmp answering offers | `Peer` builds an `OfferManager` unconditionally (`io/Peer.kt:287`) and hands it every onion message (`:1407-1422`); `isOurOffer` re-derives the default offer from the node key (`payment/OfferManager.kt:237-243`) | **no switch.** The default offer has no expiry, and every operator runs the same node key, so a saved offer stays payable for the wallet's life |
| the regtest run paying the offer | `GroupLightningRegtestJvmTest.receive()` (`:186-200`) | public eclair can't route BOLT11 to the wallet's peer-id route hint, so the run pays the offer instead |
| group-signed events delivered to a member who joins late | `ChronicleManager` (pull-based), `ChronicleKinds` (`nostr/chronicle/ChronicleEvent.kt:151`), now `NONE` | the way a depositor statement reaches a late joiner |
| a member-signed record resent to members added later | `GroupPayoutDestinations.resendOwn` | the pattern for Lightning attribution evidence, which is not group-signed |

Six facts in that table decide how the plan is shaped.

1. **One shared receive address can't be attributed.** Every device computes the
   same lowest-unused address, and two members who both press *Receive* get the
   same address. Attributing it to whoever looked last is a guess; recording "I was
   given address 7" as a claim races with every other member making the same claim
   for address 7. Attribution has to be a property of the address, not a note about
   who saw it.
2. **The signers refuse every branch but 0 and 1.** A member branch is therefore a
   change to what every co-signer accepts, and has to reach the group's devices
   before the first coin lands on one (D9).
3. **Coins disappear when spent.** A tally is a history. The wallet holds a UTXO
   set, which forgets a deposit the moment the group spends it. The ledger has to
   be kept, not derived from what is unspent now.
4. **The invoice pair already authenticates the requester**, through MLS, but
   names them nowhere, so a device that missed the request (a late joiner, a
   restored device) can't attribute the payment, and the operator's reply is never
   compared with the request.
5. **An offer can't be attributed**, and lightning-kmp can't be told to stop
   answering for one. Switching BOLT12 off is a change in the fork as well as in
   the app.
6. **The regtest run depends on the offer** to receive at all. Switching it off
   changes how the end-to-end run pays the group.

---

## Phase 0: decisions, before any code

**One day, and three spikes.** D1 to D12 change what every later phase builds.

### D1. What "attributed via the GroupPayoutDestination" means

**A member is a depositor in a group, on a network, once the group's quorum has
signed a depositor statement that embeds that member's signed payout destination.**
Every address on the member's branch, and every invoice issued against the
statement, is theirs. The plan reads the requirement as: no address or invoice is
shown in the app until it is a depositor's, and nobody is a depositor without a
destination, so every sat that comes in has both a named owner and a way back.

| how attribution could be made | why not, or why |
|---|---|
| a note "I showed address *i* to Alice" | races (fact 1): two members are shown the same lowest-unused address, and two notes can claim one index |
| each member claims the next index with a member-signed event | still races, and a payer may already have paid the losing claim's address. Misattribution can't be undone once money moved |
| a branch derived from the member's key alone, no signature | race-free, but nobody records that the branch exists. A late joiner or a restored device does not know a former member's branch, and their coins vanish from its balance |
| **a group-signed depositor statement naming a hash-derived branch** | **recommended.** Race-free (the branch is a function of room and member), durable (group-signed, chronicled to late joiners), and it embeds the destination, so the statement is the attribution's proof and the refund's address in one record |

### D2. The depositor statement, kind 30339

Group-signed, by the room's FROST key, so `GroupKeyStateEvent.isSignedByRoom`
verifies it with nothing but the room id.

```
kind:    30339
pubkey:  <the room>
content: ""
tags:
  ["d",           "<chain>:<member>"]
  ["p",           <member>]
  ["chain",       "mainnet" | …]
  ["branch",      "<b>", "<salt>"]               b = depositBranch(room, member, salt)
  ["destination", <the member's signed 30338, whole>]
```

One per member per network, **write-once**, the way a wallet statement is: a
signer refuses a second statement for a `(member, chain)` it already holds one for.
Changing a destination later does not need a new statement. The embedded
destination records where the member wanted refunds *when they started depositing*.
Payouts use the member's newest destination, as the payout plan already does.

Why 30339: the next number after the payout destination. 30339 to 30349 appear
nowhere in the code or the docs.

### D3. The branch, derived from room and member

```
depositBranch(room, member, salt) =
    2 + (u32be(taggedHash("dedun/deposit-branch/v1", room ‖ member ‖ salt)) mod (2³¹ − 2))
```

The branch is unhardened, as the whole tail is, because the group's key is a
threshold key and can only be tweaked publicly
([shared-key-derivation.md](./shared-key-derivation.md)). Branches 0 and 1 stay
receive and change. Collisions between two members are about one in a billion
for a group of ten. Still, a signer refuses a statement whose branch equals another
statement's in the same room. The proposer then tries salt + 1, and the statement
carries the salt so every reader recomputes the same branch. Unlike a counter kept
by the group, this needs no coordination: two members opened at the same time get
different branches without either knowing about the other.

### D4. What branch 0 becomes

**The legacy receive branch, kept for what already landed on it and for fundings.**
Nothing new is shown on it. Its used addresses still list (copy only, no QR, as
group-wallet-addresses.md already does for used addresses). Coins on it count as
*unattributed* until a signed attribution (D10) says otherwise. A parent group
funding a guarded subgroup still pays the child's branch 0 at current + 2 through
the existing funding claim. That deposit is the parent's, and the child's ledger
says *from #Parent*, not a member.

### D5. Lightning: the invoice is issued against the depositor statement

- The request (30364) gains `depositor`: the 30339 statement's id. The requesting
  member's device sends a request only once it holds a statement for itself.
- The operator's node answers only a request whose sender is the member that
  statement names. A hostile operator can skip this check, but only to attribute a
  payment to the member who really asked.
- The reply (30365) gains `requester` and `payment_hash`, explicitly, so a device
  doesn't have to parse the invoice to join it.
- Every device checks a reply against the request it answers: same requester, same
  amount, same description, and a payment hash no other reply has used. Today it
  checks only that the operator sent it and that it pays the node. A reply that
  fails is a *refused invoice* line and attributes nothing.
- The description says who the deposit is from (*Deposit from @alice to #Council*),
  so a payer sees on their own wallet whose name the money goes in.

### D6. Lightning attribution reaches devices that missed the pair

Inner events are not chronicled (fact 4). Two layers:

1. **The operator's word, checked where it can be.** The snapshot's `PaymentView`
   gains `requestId` for an incoming payment the node issued an invoice for. A
   device that holds the request and reply checks it against them and
   attributes; one that doesn't (a late joiner, a restored device) attributes *as
   the operator reported*, and the row says so.
2. **The quorum's word, durable.** A signed deposit attribution (D10) can confirm a
   batch of Lightning deposits, after which every device, including late ones,
   attributes them without trusting any operator.

### D7. BOLT12 off, in the app and in the fork

- **App:** the receive screen stops showing the offer; the snapshot stops carrying
  it (`offer = null`); members ignore one if an older operator sends it;
  `GroupLightningReports.offerOf` stays for the check, as the payout plan's
  `LightningAddress.Parsed.Offer` refusal does.
- **Fork:** `Peer` gets a flag, `answerOfferRequests` (default true, so stock
  Phoenix keeps its behaviour), and when it is false onion messages carrying an
  `invoice_request` are dropped before `OfferManager` sees them. The group's
  node sets it false. This is a commit on the fork's `threshold` branch and a
  submodule bump. It is the only way to stop a saved offer from being paid
  (fact 5).
- **Anything that arrives anyway** (a payment against an offer invoice issued
  before the flag, from an operator on an older build) is an ordinary incoming
  payment with no request: *unattributed*, until a signed attribution says whose.

### D8. A tally is deposits in, not a share of the balance

Each member's tally is the sum of deposits attributed to them, onchain (as they
confirm) and Lightning (net of the LSP's fee, which is what `PaymentView` reports,
and which the row says). Beside it, *paid to them*: payouts matched to their
destination (the payout plan's onchain match and Lightning `payout` tag). **No
share of the current balance is shown.** Mining fees, LSP fees and liquidity
purchases make the balance smaller than the deposits, and splitting those costs is
the group's rule to make, not the app's. A follow-up can add it once the group
has a way to say how costs are shared.

The tallies reconcile: the sum of every member's deposits plus unattributed equals
everything the group has received. The screen shows that sum, so a missing deposit
shows up as a mismatch rather than going unnoticed.

### D9. Signers learn member branches a release before any address is shown

A coin on a member branch, signed for by a device that still refuses branch 2+,
can't be spent until enough devices have upgraded (lightning/README.md's rule: a
group is only as covered as its slowest device). Phase 2, the signers' half, ships
alone and dark, a release ahead of Phase 5, which is the first that shows a
member-branch address.

### D10. Deposits nobody's branch or invoice explains: a signed attribution, kind 30340

```
kind:    30340
pubkey:  <the room>
tags:
  ["chain", …]
  ["deposit", "<txid>:<vout>" | "ln:<payment hash>", <member> | "group", "<amount sat|msat>"]   one per deposit
```

The quorum assigns legacy branch-0 deposits, stray payments to change addresses,
offer payments, and Lightning payments a device could only take on the
operator's word. `group` means "this isn't a member's deposit", for example a
transfer from the group's other wallet. Each signer checks, against its own
ledger, that every named deposit exists with that amount, that it is unattributed
or attributed the same way already, and that the member is a depositor. Newest
signed attribution of a deposit wins, so a mistake can be corrected by signing again.
The quorum's word overrides a derivation only by signing a correction that the
screen labels as one.

### D11. Who can do what

| act | who |
|---|---|
| set a payout destination | any member who can sign (the payout plan) |
| ask to become a depositor | a member with a current destination; it proposes the statement about itself, which counts as its approval |
| sign a depositor statement | the quorum; each signer checks D2 and D3 and that the member is a current participant |
| be shown an address or an invoice | a depositor with a current destination. Removing a destination stops new ones from being shown; deposits already made stay attributed |
| sign an attribution | the quorum, D10 |
| see tallies | every member, an npub profile included |

### D12. Mixed versions

- Phase 2 (signers accept member branches) ships a release ahead (D9).
- An older device sees 30339 and 30340 as raw-JSON bubbles until it has their
  transcript arms. The arms ship with Phase 2 as well.
- An older operator ignores `depositor` on a request and answers anyway. Its
  reply lacks `requester` and `payment_hash`; a new device then attributes by
  the MLS sender of the request it can match by `requestId`, or not at all.
- An older operator still puts its offer in snapshots, and a new device drops it.

### Spike A: dropping `invoice_request` in the fork

In the fork (`lightning-kmp-app/experimental/lightning-kmp`), find the narrowest
place to drop an `invoice_request`: in `Peer`'s onion-message branch, before
`offerManager.receiveMessage`. Check that a payer's `payoffer` then times out
rather than paying, and that onion messages the node *sends* (paying an offer, if
that is ever built) are untouched. Write down the commit.

**Result.** The narrowest place is `OfferManager.receiveMessage`, which is where
`Peer` hands every onion message: the branch that answers an `invoice_request`.
It is a flag on `NodeParams` rather than on `Peer`, `answerOfferRequests`, true by
default, so stock Phoenix and every existing construction keep their behaviour.
When it is false, a valid request for the node's own default offer is dropped and
`receiveMessage` returns nothing. The branch that receives the invoice answering
the node's *own* request to pay an offer comes after it and is untouched.
`OfferManagerTestsCommon` has a test for the drop, and all 11 of its tests pass.
The fork commit is `3ae9302a` on `dedun/answer-offer-requests` in
`experimental/lightning-kmp`, bumped by `5478993` in `lightning-kmp-app`.
**Neither is pushed yet**, and this repository's submodule pointer moves to them
in Phase 7.

### Spike B: receiving BOLT11 on regtest

With BOLT12 off, the run has to pay a BOLT11 invoice. Against the regtest network:

1. Does public eclair pay the group's invoice once a channel exists? Does the
   route hint then name the channel's alias rather than the peer id
   (`Peer.kt:860-875`)?
2. If it does, the run opens its first channel by buying liquidity
   ([group-lightning-liquidity.md](./group-lightning-liquidity.md)), then receives
   by BOLT11.
3. If it doesn't, the regtest node is built with `answerOfferRequests = true` as a
   fixture-only parameter, and the run's offer payments become *unattributed*
   deposits that it then attributes with a signed 30340. That tests D10 too.

Either way, the production node never answers offers.

**Result: option 3.** The question is answered by the code, not by a run.
`Peer.createInvoice` always adds one route hint, from the LSP to the wallet,
over `ShortChannelId.peerId(nodeId)`, whether or not a channel exists. It never
names a channel's alias. So public eclair can't route a BOLT11 payment to the
group's node at any point, which is the failure group-lightning-wallet.md
recorded. The regtest fixture builds the group's node with
`answerOfferRequests = true`. The run's offer payments arrive unattributed, and
the run attributes them with a signed 30340, which tests Phase 9 end to end. The
BOLT11 request and reply are still checked in the run, as they are today.

### Spike C: one Electrum watcher per member

Open a regtest group wallet with ten member branches (twelve watchers with
lookahead 10: about 120 subscriptions) on the node's client and on a
`StandaloneElectrum`. Measure the time to first state and check that electrs
doesn't throttle. If it does, the watchers are started lazily: your own branch at
once, the others when the tallies screen or a spend needs them.

**Result, 2026-11-01.** `MemberBranchesRegtestJvmTest`: 120 subscriptions, all
sent at once on one connection to the regtest electrs, were answered in 76 ms.
That is far inside what a wallet's start can afford, so every branch is watched
from the start, and none lazily. The test stays, as Phase 4's regtest
measurement.

---

## Phase 1: the two statements and the branch, pure

**Two days.** Dark: kinds nothing produces.

- `managers/DepositBranch.kt`: `depositBranch(room, member, salt)`, D3. Its test
  pins the tagged hash (fixture values), never returns 0 or 1, and stays under
  2³¹.
- `nostr/wallet/DepositorStatementEvent.kt` (30339): `template`, `read` (shape
  only, for a signer reading a proposal), `verify(event, room)` checks:
  - the room's signature;
  - `d` agrees with `p` and `chain`;
  - the branch recomputes from room, member and salt;
  - the embedded destination is `GroupPayoutDestinationEvent.verified`, by that
    member, about this room and chain.

  Also `newestPerMember` (write-once: the first by `created_at`, then id, wins;
  a later one for the same member is ignored, not newest-wins).
- `nostr/wallet/DepositAttributionEvent.kt` (30340): `template`, `read`, `verify`,
  `newestPerDeposit` (newest wins, D10).
- Tests, one mutation per check, as the payout destination's: another room's
  destination embedded; someone else's destination; a branch that doesn't
  recompute; a branch of 0 or 1; a second statement for the same member; an
  attribution of an unknown deposit kind; two attributions of one deposit.

## Phase 2: signers accept member branches, and the transcript knows both kinds

**Two days.** **Ships alone, a release ahead of Phase 5 (D9).**

- `GroupSpend`: a claimed input or change derivation may be on a branch that a
  depositor statement this device holds names for this account's room and chain,
  besides 0 and 1. Change still goes to branch 1, never to a member's branch; a
  member branch is for deposits only. `claimedChangeIndices` is unchanged, and
  `claimedFundingIndices` stays branch 0.
- `FrostSigningManager.acceptSpendProposal` passes the held statements in.
  Guardian spends (`proposeGuardianSpend`) and the member-wallet path are
  unchanged: member branches exist only on a group's own account.
- `GroupWalletDerivation`: nothing to change; `derive` already takes any branch.
  A test derives branch 2+ addresses for a guarded account, guardian leaves
  included, and checks that they match the descriptor `tr(…/<b>/*)`.
- Transcript arms for 30339 (*#Council opened deposits for @alice*) and 30340
  (*#Council attributed 3 deposits*). They are group-signed lines, so they go
  in `GROUP_IDENTITY_TYPES`.
- Tests: a spend whose input claims a member branch the device holds is
  accepted; one claiming a branch no statement names is refused; change to a
  member branch is refused; an older-shape spend is unaffected.

## Phase 3: becoming a depositor

**Three days.**

- `managers/Depositors.kt`:
  - `statementsIn(db, room, chain)` and `branchOf(db, room, chain, member)`, from
    `GroupSignedEvent` rows;
  - `propose(db, room, member, chain)`: refuses without a current destination,
    picks the first salt whose branch collides with nobody's, and calls
    `FrostSigningManager.proposeSigning`.
- Signers' check, in the session's acceptance path:
  - the subject is a current participant;
  - the embedded destination is that member's, and newer or equal to the newest
    this device holds (an older one means the proposer is stale);
  - no statement already exists for `(member, chain)`;
  - no other member's branch equals this one.
- `ChronicleKinds` gains 30339 and 30340, so a member added later is handed
  every depositor statement and attribution when they ask for the group's history.
  This is the chronicle's first carried kinds since member-chronicle.md emptied it,
  so its comment and that note say so.
- The key notice's answer carries the newest depositor statements too, as it
  carries the member-wallet policy, so a member who asks for the key learns the
  branches at the same time.
- Tests (`DepositorsJvmTest`): the proposal is refused without a destination;
  a collision moves to salt 1; a second statement is refused by signers; a late
  joiner's chronicle carries the statements.

## Phase 4: the wallet watches every depositor's branch

**Three days.**

- `GroupOnchainWallet` gains a watcher per depositor statement on its account,
  next to receive and change, each with lookahead 10 (Spike C may make the
  others lazy). `GroupWalletState` gains `branches: Map<HexKey, BranchState>` and
  `depositAddressOf(member)`, which is the lowest unused address on the member's
  branch, with +1 and +2 as the addresses screen shows today.
- The statements are read from `GroupSignedEvent` when the wallet starts, and a
  new statement adds a watcher without restarting the wallet.
- `derivationOf(script)` searches every branch, so the signer checks and the
  payout match see member-branch coins.
- A restored device finds every branch from the statements it gets from the
  chronicle, and rescans each from index 0, as it does branches 0 and 1 today.
- Tests (`GroupOnchainWalletJvmTest`, `FakeElectrum`): coins on two member
  branches; a statement that arrives after start; `derivationOf` on a member
  branch; the balance sums all branches.

## Phase 5: only an attributed address is shown

**Three days.** **The first release a member sees.**

- `GroupWalletScreen`'s receive card becomes *Your deposit address*:
  - a depositor with a current destination is shown `depositAddressOf(self)`,
    with a QR code and copy;
  - a member with a destination but no statement is shown *Start depositing*,
    which proposes the statement and moves to the signing session;
  - a member with no destination is shown *Set where this group pays you
    first*, which leads to the payout destination screen;
  - an npub profile sees no card.
- `GroupWalletAddressesScreen` lists *your* branch by default, and the others by
  member (*@alice's deposit addresses*, used ones only, copy only, so nobody
  hands out another member's address by accident). Branch 0 appears as *Before
  deposits were attributed*: used rows only, copy only, no QR.
- `CoinRow` says whose a coin is (*From @alice*, *Unattributed*, *Change*, *From
  #Parent* for a funding), from the branch and D10.
- No other screen shows a group receive address. A test that greps the composables
  for `receiveAddress` is the guard: the property is removed from `GroupWalletState`'s
  public surface, so a new screen can't reach it.
- Tests: each of the four receive-card states; the addresses screen shows no QR
  for another member's branch or for branch 0; `CoinRow` labels.

## Phase 6: only an attributed invoice is issued

**Three days.** Schema v36.

- Wire (`WalletWire.kt`): `InvoiceRequest.depositor`, `InvoiceReply.requester`
  and `.paymentHash`, `PaymentView.requestId`, all optional for older peers
  (D12).
- `GroupLightningReceiveViewModel`: *Ask for an invoice* only for a depositor
  with a current destination; otherwise the same two steps as the onchain card.
  The description is prefilled *Deposit from @alice to #Council* and editable
  after that prefix. The wait for a reply gets the timeout the doc promised and the
  code lacks (*No reply from the operator's device yet*), with *Ask again*.
- Operator (`GroupLightningNode.answerInvoiceRequests`): answers only a request
  whose `depositor` statement names its MLS sender; records `requester` and
  `paymentHash` on `GroupLightningInvoiceRow`; puts `requestId` on each incoming
  `PaymentView` it can join by hash.
- Every device (`GroupLightningReports.receiveInvoice`): checks the reply
  against its request (D5) and stores requester and hash on its own row.
- `GroupLightningInvoiceRow` gains `requester` and `paymentHash`, both nullable
  (`AutoMigration(35, 36)`), with an index on `paymentHash`.
- Tests: a request with no statement is not answered; a reply for another
  requester, another amount or a reused hash is refused; an incoming payment
  joins to its requester by hash; a late joiner attributes from `requestId` *as the
  operator reported*.

## Phase 7: BOLT12 off

**Two days, and the fork commit from Spike A.**

- Fork: `answerOfferRequests` on `Peer` (D7), tested in the fork against a
  `payoffer` that must not be answered. Submodule bump in this repository.
- `GroupLightningNode`: builds its `Peer` with `answerOfferRequests = false`;
  `offer()` goes and the snapshot carries `offer = null`.
- `GroupLightningReports` drops a snapshot's offer before filing it. The receive
  screen and its two strings (`the_group_s_offer`, `anyone_can_pay_this_offer`)
  go.
- `GroupLightningRegtestJvmTest`: per Spike B, either BOLT11 through a channel
  opened by a liquidity purchase, or a fixture-only `answerOfferRequests = true`
  with the run's offer payments attributed by a signed 30340.
  `docs/group-lightning-wallet.md`'s rows that say the offer is part of the wallet
  are corrected to say it was, and why it isn't now.
- Tests: the node's peer is built with the flag off; a snapshot carrying an
  offer is filed without it; the receive state has no `offer`.

## Phase 8: the ledger and the tallies

**Four days.** Schema v37.

- `DepositRow` (new table): `chatRoomId`, `chain`, `source` (`onchain` or
  `lightning`), `key` (`txid:vout` or payment hash), `amount`, `member` (nullable),
  `attributedBy` (`branch`, `invoice`, `operator`, `signed`, or null for
  unattributed), `seenAt`, `confirmedAt`. Written as deposits are learned and
  never deleted when coins are spent (fact 3). It is a cache of what the chain,
  the snapshots and the signed events say, so a restored device rebuilds it.
- Onchain: each watched branch's history, not just its unspent coins. A
  deposit is an output of a transaction in a branch's history that pays that
  branch and spends none of the group's coins, so a change output or a self-transfer
  is never counted as a deposit. History is fetched with the subscription's status
  as the guard, because `getScriptHashHistory` reads a failure as empty
  (payout plan, fact 1). A history that can't be fetched leaves the row pending,
  never absent.
- Lightning: each incoming `GroupLightningPaymentRow`, joined by hash to an invoice
  row (Phase 6) or a signed attribution.
- `managers/DepositLedger.kt`: `tallies(db, room, chain)` gives per member deposited
  onchain, deposited over Lightning, and paid to them; plus unattributed, plus the
  total, with the reconciliation of D8.
- A *Deposits* section on the group page with each member's tally, the
  unattributed line and the total, which opens a *Deposits* screen: every deposit,
  newest first, with whose it is and how that's known (*by their address*, *by
  their invoice*, *as the operator reported*, *signed by the group*).
- A member's own tally on their payout row, *You've put in 120,000 sats*.
- Tests (`DepositLedgerJvmTest`): deposits on two branches and one legacy
  address; a spend that empties a branch leaves its deposits counted; a change
  output is not a deposit; a self-transfer between branches is not a deposit;
  Lightning deposits net of fees; the reconciliation holds.

## Phase 9: the group says whose the rest is

**Three days.**

- *Attribute* on an unattributed deposit (and *Correct* on an attributed one)
  proposes a 30340 for it, or for every deposit selected; a device that holds a
  share is offered it.
- Signers' check (D10), against their own `DepositRow`s.
- The ledger applies the newest signed attribution per deposit; the Deposits
  screen shows *signed by the group*, and a correction as one.
- Tests: legacy deposits attributed; an attribution naming a deposit a signer
  doesn't have is refused; a correction wins over the earlier one; a late joiner's
  chronicle carries attributions and their tallies match.

## Phase 10: docs, and checking it by hand

**One day.**

- This note's *Built* line and the table of where the build chose differently;
  a row in [README.md](./README.md); the corrections to group-lightning-wallet.md
  (the offer), group-wallet-addresses.md (branch 0 is legacy) and
  member-chronicle.md (the chronicle carries something again).
- `./gradlew :composeApp:m3Audit`,
  `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`, and the
  regtest run.
- Four emulators against regtest, Alice, Bob, Carol and Dan, where Dan is added
  after step 6:
  1. Alice has no destination. Her receive card asks her to set one, and her
     Lightning receive screen does the same.
  2. Alice sets one and starts depositing. Bob and Carol sign. Her card shows an
     address on her branch; Bob's card still asks him to start.
  3. Bob starts. Alice and Bob each see a different address; each pays theirs.
     Every device's tallies show Alice's and Bob's deposits separately.
  4. Alice asks for a 10,000-sat invoice; the description names her; a payer pays
     it; every device's tallies count it to Alice, net of the LSP's fee.
  5. The old offer, saved before the upgrade, is paid. It doesn't complete.
     (With an older operator, the payment arrives *unattributed*.)
  6. The group spends from Alice's branch; her tally is unchanged.
  7. Dan joins. After the chronicle, his tallies match everyone else's.
     Lightning deposits show *as the operator reported* until step 8.
  8. The quorum attributes the legacy branch-0 deposits and the Lightning ones in
     one 30340. Every device's tallies agree, Dan's included.
  9. Alice removes her destination. Her card asks for one again; her tally stays.

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 0 | this note, three spikes | nothing |
| 1 | the branch, 30339, 30340, pure | dark |
| 2 | the signers' checks, the transcript arms | **alone, a release ahead of 5** (D9) |
| 3 | proposing and signing depositor statements, the chronicle | dark: statements can be signed, nothing shows an address from them |
| 4 | the wallet watching member branches | dark |
| 5 | the receive card, the addresses screen, coin rows | **with 6 and 7**: from here every address shown is attributed |
| 6 | the invoice request, reply and snapshot; schema 36 | **with 5 and 7**: from here every invoice is |
| 7 | BOLT12 off, in the fork and the app; the regtest run | **with 5 and 6**: the offer is the last thing shown that isn't attributed |
| 8 | the ledger, the tallies; schema 37 | after 5-7 have had a release |
| 9 | signed attributions | with 8, or right after |
| 10 | docs and the emulator run | last |

About five weeks of work for Phases 0-10, two of them before anything is shown.
**Phases 5, 6 and 7 are one release.** Shipped apart, the app would show attributed
addresses beside an unattributed offer, or the other way round, and the promise
in this plan's first paragraph would hold on one side only.

---

## What this does not do

**It does not divide the balance.** Tallies are deposits in and payouts out (D8).
How fees and liquidity costs are shared is the group's rule to make, and a later
plan's.

**It does not attribute a deposit to anybody but the member who was given the
address or invoice.** A member paying in on another's behalf deposits under their
own name. *Deposit for @bob* is a follow-up: a request naming another depositor,
which the operator answers only if that depositor agreed.

**It does not stop a payer from reusing an address.** A second payment to Alice's
address is still Alice's deposit. The screen hands out the next unused address on
her branch, as today.

**It does not reach member guarded wallets.** A member's guarded wallet has one owner
already. Every deposit into it is theirs, and the group only guards it.

**It does not make Lightning attribution trustless for a device that missed the
invoice pair.** Until a signed attribution, that device has the operator's word, and
the row says so (D6).

**It does not bring BOLT12 back.** Doing that needs a per-payer offer (a single-use
offer issued against a depositor statement, as an invoice is) and payer notes the
co-signers can read. That's a plan of its own.

## Appendix: what was considered and rejected

| idea | why not |
|---|---|
| attribute the shared current address to whoever was shown it last | races; two members see the same address (fact 1) |
| a member-signed claim on the next receive index | races, and a paid losing claim can't be re-attributed safely |
| a branch from the member's key, unsigned | nothing records it, so late joiners and restored devices lose former members' coins |
| a counter the group keeps for branch numbers | two openings at once take the same number; the hash needs no coordination (D3) |
| a group signature for every address or invoice | a quorum round per deposit. One statement per member makes every later address and invoice attributable with no signing |
| a separate account per member instead of a branch | an account is a wallet statement (30331) with its own descriptors, fee and change handling, and spends couldn't combine members' coins. A branch keeps one wallet |
| attributing Lightning payments by invoice description alone | the description is free text anybody can type; the request/reply pair and the payment hash are the evidence (D5) |
| keep BOLT12 and attribute offer payments by payer note | payer notes are the payer's word, not the member's, and nothing in lightning-kmp exposes them to the co-signers; and lightning-kmp would still answer anybody's request for the offer |
| show the balance split per member | depends on a cost-sharing rule the group has not made (D8) |
| derive tallies from the unspent set | spent coins disappear, and the tally with them (fact 3) |
