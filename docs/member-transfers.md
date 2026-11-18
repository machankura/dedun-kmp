# Members transferring their balance to each other: signed by the giver, then by the group

Dedun is a group savings wallet. Since [member-deposits.md](./member-deposits.md),
every sat that comes in belongs to a member. Since [payouts-only.md](./payouts-only.md)
and [group-wavelength-payouts.md](./group-wavelength-payouts.md), every sat that goes
out is a member's own money, paid to that member's own destination. Between those two
points a member's balance can't change hands. If Alice owes Bob 20,000 sats for the
groceries, the only way to settle it from the group's wallet is to pay Alice out, have
Alice pay Bob from her own wallet, and have Bob deposit again. That costs two fees and
a round trip through three wallets, and on the way the money stops being saved.

This plan adds that missing shape. **A member can propose to transfer an amount of
their attributed balance, on one rail and one chain, to another member. The giver signs
the request with their own key. The group's quorum then signs a *transfer* that embeds
the request whole, after every signer checks it against their own count of the giver's
balance. No coin, VTXO or channel balance moves. Nothing reaches the chain, the
operator or the LSP, and nobody pays a fee. Each rail's balance function counts the
transfer from the moment the group has signed it: it comes off the giver and is added
to the receiver. The deposit tallies don't change, because they record who put money
in.**

Read [member-deposits.md](./member-deposits.md) first: its depositor statement (30339)
is how this plan knows who can receive, and its ledger is what a transfer must never
write to. Then read [payouts-only.md](./payouts-only.md) D2-D4 (whose a coin is, the
30341 assignment, and joint payouts of a shared coin) and D8 (the Lightning balance), and
[group-wavelength-payouts.md](./group-wavelength-payouts.md) W3, W9 and W11, along with
its review findings. Every balance rule here is one of those, with a new term added. Then
read [group-payout-destination.md](./group-payout-destination.md) on why a member-signed
statement is carried whole. The transfer request is built the same way.

**Planned, not built.** Written on 18 November 2026 against `f4752156`, database
schema v45. The file and line references below are as they stood then.

### Where the build stands

| phase | state |
|---|---|
| 0 | done: S1, S2 and S4 answered from the code; S3 not run, since no emulator was up |
| 1 | done: `TransferRequestEvent`, `MemberTransferEvent`, `TransferLedger` |
| 2 | done: every balance counts transfers; transcript lines; the chronicle carries 30343 |
| 3 | done, after an adversarial review that added T15: `MemberTransfers.request`, `putToGroup`, `withdraw`, `refuse`, both hooks |
| 4 | done: `GroupTransferScreen`, the payouts screen's offers and *Transfers waiting*, the approval summary |

**Where the build chose differently from the plan:**

| the plan said | what was built |
|---|---|
| T5: the amount in Wavelength ledger sats, checked against `net(giver)` | against the smaller of that and T15's capacity from signed records, so no honest transfer is one some device voids |
| Phase 2: `netsOf` sums every signed transfer | T15: applies them in order and voids what the giver can't cover, or what was signed outside the request's window. The change landed with Phase 3, where the review found it |
| T9: a withdrawal stops a session over it | it declines a session somebody else put to the group, on the giver's device. A session the giver proposed already counts their approval, and only the other signers' refusal at approval stops it |
| Phase 4: *Transfers waiting* on the room's detail screen | on the payouts screen, beside the balances it changes, with *Put to the group* and *Take back*. The detail screen already opens the payouts screen |
| Phase 4 and T14: transfer entries in the Wavelength history, the export and the receipt | **not built.** The history is built from rows this device signed and the operator's claims, each entry kind has its own detail sheet and filter, and a transfer moves nothing in the wallet. The payouts row says what a member received and gave, and the transcript records every transfer. Putting transfers in the history is a follow-up |
| Phase 4: the Wavelength balance card's disclosure says what was received and given | **not built**, for the same reason: the card is the wallet's, and a transfer is the members'. The payouts row says it |
| the form's preview: what the receiver will hold, on the ledger | what arrives by this device's count, with the ledger figure under it when the wallet holds less than the balances add up to (T5) |

---

## What is already there

| piece | where | what it does for this plan |
|---|---|---|
| a Wavelength balance | `GroupWavelengthPayouts.balancesIn` (`managers/GroupWavelengthPayouts.kt:145`): deposits less what was paid, scaled down pro rata to what is in the wallet by this device's count | a ledger: no coin belongs to anyone. A transfer is one more term in it |
| a Lightning balance | `LightningBalance.of` (`wallet/lightning/LightningBalance.kt`): deposits − sent − open | the same: one more term |
| an onchain balance | `MemberBalances.balancesIn` (`managers/MemberBalances.kt`) over `CoinOwnership.ownerOf` (`wallet/CoinOwnership.kt:70`): coins, whole or in shares, owned by the newest signed word | **not a ledger.** A transfer here has to move coin ownership, which is what a 30341 already does for branches 0 and 1 |
| one open payout per member, across rails | `refuseAgainstBalance` and `proposedFor` (`GroupWavelengthPayouts.kt:250`, `:259`) | where a transfer joins the "one open outflow at a time" rule (W9) |
| a co-signer that pays only what a balance bears | `approvedLeaves` (`GroupWavelengthPayouts.kt:187`) reads `balancesIn` | once `balancesIn` counts transfers, every co-signer counts them, and that needs no change of its own |
| a member-signed statement carried whole, then embedded in a group-signed one | `GroupPayoutDestinationEvent` (30338), embedded in `DepositorStatementEvent` (30339); `MemberWalletRequestEvent` with `MemberWalletManager.sponsorIfSigner` (`managers/MemberWalletManager.kt:349`) | the transfer request takes this shape. The sponsor flow lets a member who holds no share still propose |
| per-kind refusal hooks before a proposal is shown | `FrostSigningManager.acceptProposal` (`managers/FrostSigningManager.kt:~610-665`): `Depositors.refuse`, `DepositLedger.refuseAttribution`, `GroupWavelengthPayouts.refuse`, `GroupLightningPayouts.refuse`, `CoinAssignments.refuse` | where `MemberTransfers.refuse` goes |
| the same check again at approval | `FrostSigningManager` approve path (`:~2960`) re-runs `GroupWavelengthPayouts.refuse` | a balance can change between arrival and approval, for a transfer as for a payout |
| the chronicle | `ChronicleEvent.KINDS` (`nostr/chronicle/ChronicleEvent.kt:167`): 30339, 30340, 30341, in apply order | 30343 goes after 30341. **30379 is not in it** (Spike S1) |
| the room's members' balances on one screen | `GroupPayoutsScreen` (`ui/composable/GroupPayoutsScreen.kt`): one row per member, onchain, Lightning and Wavelength lines | where *Transfer to a member* starts, and where a received or given amount shows |
| the Wavelength history, filtered and exported | `GroupWavelengthHistoryView`, `GroupWavelengthExport.toCsv` (redesign Phase 7) | gains *transferred* and *received* entries, with whose word each one is |

Six facts shape the plan.

1. **Two of the three balances are already pure bookkeeping.** On the Wavelength
   wallet and the Lightning wallet, nothing on the chain or in the channel says
   whose a sat is. Each device computes it from the deposit ledger and from what the
   group signed. Moving a balance there means adding a signed record, and moving
   money would add nothing a payout could check afterwards.
2. **The onchain wallet's balances are coins, not history.** `payouts-only.md` chose
   that on purpose: a coin is what can be spent. An onchain transfer therefore has to
   say which coins, or which parts of them, change owner. Any part of a coin it moves
   makes that coin shared, and a shared coin is paid out to all its owners together
   (D4).
3. **Payouts-only rests on one promise: a quorum can't move a member's money to
   somebody else.** Every payout goes to the member's own signed destination. A
   transfer the group could sign without the giver would break that promise. Any `t`
   members could transfer a balance to themselves and then pay it out to their own
   addresses. So the giver's own signature has to be part of what every device checks,
   and stay checkable for a member who joins years later.
4. **Without a group signature, two transfers could each fit the same balance.** If
   Alice signs a transfer to Bob and another to Carol at once, each fits her balance,
   and devices that saw them in a different order would disagree about all three
   balances. The quorum's signature puts transfers in order, and lets every signer
   refuse the second, as it refuses a second payout today (W9).
5. **A device that counts transfers and one that doesn't will disagree, and the
   dangerous direction is the giver's.** A device that missed Alice's transfer, or
   predates this plan, still counts the 20,000 sats as Alice's. It will approve and
   co-sign a payout of money she has already given away. This is the same risk as
   the payouts review's *a payout a device missed*, now with a second way in. It
   decides the release order (T11).
6. **A member with no FROST share can't propose a signing session**
   (`proposeSigningBatch` refuses *This device is not a participant*). The member-wallet
   request already solves this: the member signs the request and carries it into the
   room, and a device that holds a share proposes it.

---

## Phase 0: decisions, before any code

**One day, and four spikes.** T1 to T14 change what later phases build. The ones
the owner should decide are listed under *Questions for the owner* with a
recommendation. Every decision below is written as that recommendation.

### T1. A transfer moves a claim, not money

No transaction, Ark round, HTLC or splice. The only artefact is a group-signed event.
On each rail, the transfer takes effect inside the balance function every device
already runs:

```
Wavelength  net(m)       = deposited(m) − paid(m) + received(m) − given(m)        then scaled pro rata, as now
Lightning   available(m) = deposited(m) + received(m) − given(m) − sent(m) − open(m)
onchain     the coins m owns, after each transfer's coin shares are applied (T6)
```

That makes a transfer free and instant once signed. Two conditions keep it honest:
the pro-rata scaling already stops the Wavelength ledger from promising more than
the wallet holds, and the onchain rail moves ownership of coins that exist and are
unspent.

### T2. Two events: the giver's request (30342) and the group's transfer (30343)

**30342, the transfer request, signed by the giver:**

```
kind:    30342
pubkey:  <the giver>
content: ""
tags:
  ["d",          "<room id>:<chain>:<rail>:<asked_at>"]
  ["h",          <room id>]
  ["chain",      "mainnet" | "signet" | …]
  ["rail",       "wavelength" | "lightning" | "onchain"]
  ["p",          <the receiver>]
  ["amount_sat", <what the giver gives, in the rail's ledger sats (T5)>]
  ["expires_at", <unix seconds>]
  ["note",       "<free text>"]                    optional, at most 140 characters
```

or, to take back one not yet signed:

```
  ["d", …], ["h", …], ["chain", …], ["withdrawn", <the request's id>]
```

The request is carried into the room whole, as an inner event's content, the same
way `GroupPayoutDestinationEvent.carried` and `MemberWalletRequestEvent.carried` work.
Its signature has to outlive the MLS frame, because the group's event embeds it.

**30343, the transfer, signed by the room:**

```
kind:    30343
pubkey:  <the room>
content: ""
tags:
  ["d",          "transfer:<request id>"]
  ["chain",      …]
  ["rail",       …]
  ["from",       <the giver>]
  ["p",          <the receiver>]
  ["amount_sat", <the request's amount>]
  ["request",    <the signed 30342, whole>]
  ["coin",       "<txid>:<vout>", "<sat>"]   onchain only: one or more, summing to amount_sat (T6)
```

`from`, `p`, `chain`, `rail` and `amount_sat` repeat what the request says. They are
there so a reader can index the event without parsing what it embeds, and `verify`
requires them to agree with the request byte for byte. That mirrors how 30339
repeats its embedded destination's member.

**Why 30342 and 30343:** they are the next numbers after the coin assignment (30341).
`CoinAssignmentEvent`'s kdoc records that nothing used 30341 to 30348, and the
Lightning kinds start at 30350.

**Why not one event, signed by the giver alone (fact 4):** it would have no order, and
no device's check would stand between two transfers that each fit. **Why not one
event, signed by the group alone (fact 3):** it would let a quorum move anybody's
money.

### T3. Who may transfer, and to whom

- **The giver** is a current member who signs with their own key. An npub-only profile
  can't sign a request, and its screen doesn't offer one (Spike S4). A former member
  can't send into the room, so they can't transfer either. Their balance waits for a
  payout, as it does now.
- **The receiver** is a current member other than the giver, with a depositor
  statement (30339) on that chain. That is the precondition for holding a balance
  everywhere else: a 30341 share must name a depositor, and a Wavelength deposit needs
  one (`GroupWavelengthDeposits.kt:72`). A receiver with no payout destination still
  receives. Their balance waits until they set one, exactly as for a depositor who
  removed theirs.
- **The receiver doesn't consent.** Receiving costs nobody anything on the ledger
  rails. On the onchain rail, a received share makes a coin joint (T6), and the
  receiver may then be paid out alongside the giver without asking. That is their own
  money arriving at their own address, which is what D4 already does. *(Question 2.)*

### T4. Every signer checks the request against its own count

`MemberTransfers.refuse(db, room, proposed)` refuses a 30343 when any of these fails:

1. the embedded 30342 verifies, is by `from`, is about this room and chain, is not
   withdrawn by a 30342 this device holds, and hasn't expired;
2. `from`, `p`, `chain`, `rail` and `amount_sat` agree with the request;
3. `from` is a current member; `p` is a current member, isn't `from`, and has a
   depositor statement on `chain`;
4. no 30343 this device holds already embeds this request (first wins: by
   `created_at`, then id);
5. the giver has nothing open on this rail and chain: no payout approved and not yet
   paid or expired, and no payout or transfer being signed (W9, extended in T8);
6. `amount_sat` is at least the floor (T5), and no more than the giver's balance by
   this device's count (T5);
7. onchain only: the coin shares pass T6.

It runs on arrival, so a refused proposal is dropped before a member sees it. It runs
again at approval, beside the Wavelength payment's re-check, because a balance can
change between the two. A device that can't count yet refuses: if the Wavelength
wallet's coins haven't loaded, the giver has nothing to give. The payouts code takes
the same safe direction.

### T5. The amount, per rail

- **Wavelength: ledger sats, before the pro-rata scaling.** `balancesIn` scales each
  member's net down when the wallet holds less than all the nets added up. The ratio
  differs a little from device to device, because each counts the wallet's coins
  itself. An amount the signers must agree on therefore can't be in scaled sats. The
  event carries ledger sats. Signers check `amount_sat ≤ net(giver)`, and that is
  enough, because T4.5 leaves nothing of the giver's open. The total of all nets is
  unchanged, so no device's ratio moves. The receiver then takes the same share of
  the shortfall the giver would have taken. *Transfer everything* sends `net` exactly.
  A typed amount is converted at the proposer, as `ceil(typed × totalNet / inWallet)`.
  When the ratio is below 1, the form says what arrives, by this device's count
  (Spike S3 measures how often it is).
- **Lightning: whole sats, compared in msat** against `available` with nothing open.
- **Onchain: sats, as coin shares.**
- **A floor of 546 sats on every rail.** It is the dust limit both payout rules already
  use. A smaller balance could never be paid out alone, so a transfer below it would
  only create balances that can never leave.
- **No cap.** `MAINNET_CAP` bounds what leaves the wallet, and nothing leaves here. A
  payout of a received balance still meets every cap and check a payout meets.

### T6. Onchain: the transfer names its coins, and each device applies it on top of the owner rule

`CoinOwnership.ownerOf` gains one step after D2's three. Each 30343 naming the coin is
applied in signed order (`created_at`, then id), and each moves `sat` of the coin
from `from` to `p`:

- the result is `Owner.Member` when one member is left holding all of it, and
  `Owner.Shares` otherwise;
- **a transfer whose giver doesn't hold `sat` of the coin at that point is void**, and
  is skipped on every device alike. Signers never sign one (T4.6). This covers a
  device that later learns of a correction that changed the coin's base owner, and
  every device reaches the same answer;
- every share left after the transfer, the giver's and the receiver's, is 0 or at
  least 546 sats (D3's rule for 30341 shares).

The proposer chooses the coins with `TransferCoins.choose(owned, amount)`. It first
takes whole confirmed coins of the giver's, largest that still fit, and then splits at
most one coin. A transfer therefore creates at most one shared coin. **Only confirmed
coins.** An unconfirmed coin can be replaced, which would void the transfer after it
was signed.

Signers check each named coin against what the device already holds, as
`CoinAssignments.refuse` does: the coin is on this room's account, unspent, has the
amount the device has for it, the giver holds `sat` of it, and the shares add up to
`amount_sat`.

**Corrections can't pull a coin out from under a transfer.** `CoinAssignments.refuse`
and `DepositLedger.refuseAttribution` gain *names a coin a member has transferred part
of*. Without that rule, a newer 30341 or 30340 would reassign the coin's base owner
and silently void every transfer on top of it.

### T7. The one thing that must not happen: a transfer counted as a deposit

`DepositRow` and `DepositLedger.tallies` record who put money in. A transfer must
never write a `DepositRow`, and the tallies must never read a 30343. The Wavelength
balance starts from `depositsIn`. If a transfer reached the deposit ledger as well as
the balance's new term, the receiver would be credited twice, and every other member's
pro-rata share would be diluted. The unconfirmed-deposits plan's *one thing that must
not happen* is the same mistake, made by an arriving coin. A test asserts that a signed
transfer leaves `depositsIn` and every tally unchanged.

### T8. One open outflow per member per rail

W9 becomes: a member has at most one open *outflow* per rail and chain, where an
outflow is a payout or a transfer they give.

- `proposedFor` counts 30343 sessions whose `from` is the member.
- `refuseAgainstBalance` refuses a payout while a transfer of the member's is being
  signed, and T4.5 refuses the reverse.
- A transfer the member *receives* while being signed blocks nothing. They can't pay
  it out until it is signed, because until then it isn't in their balance.

### T9. A transfer is final once signed

It is never undone. A receiver who wants to give it back makes a transfer of their
own. Until it is signed, the giver can withdraw it with a 30342 `withdrawn`, which
signers then refuse at accept and at approve. A giver who holds a share can also
decline their own session. **A request stands for at most seven days**
(`MAX_APPROVAL_SECONDS`, as for payouts), and the form defaults to 24 hours.

### T10. A member without a share asks, and a device with one puts it to the group

The member-wallet flow, reused:

- the giver's device signs the 30342 and carries it into the room;
- every device files it;
- a device that `canSign` the room offers *Put @alice's transfer to the group*. It is
  never automatic, because the transfer isn't the sponsor's money;
- if the giver holds a share, their own device proposes the 30343 straight away, in the
  same tap.

*(Question 3 asks whether sponsoring should be automatic, as `sponsorIfSigner` is for
wallet requests.)*

### T11. Mixed versions, and the release order

An older device has no hook for 30343. Spike A of `payouts-only.md` showed that such a
device shows an unknown kind and signs it if its member approves. Its balances also
ignore transfers, so it would still count Alice's given 20,000 sats as hers, and it
would approve and co-sign her payout of them (fact 5). So:

- **the readers ship a release ahead of anything that proposes** (Phase 2). Every
  balance, payout refusal and co-signer then counts transfers, before any transfer
  exists to be counted;
- **proposing ships behind a gate**, `MemberTransferRollout.offers(chain, rail)`. Like
  `GroupWavelengthRollout.ENABLED`, it decides what a device *offers* and not what it
  accepts. It opens on regtest and signet first. Mainnet is the owner's call
  *(Question 1)*;
- the screens can't count which devices are upgraded, because there is no capability
  signal. They say *Your device counts transfers*, and never claim the group as a
  whole does.

### T12. A device that missed a transfer

This is the same weakness the Wavelength payouts review left open, now with a second
cause. A device that missed a 30343 because of an MLS skipped key
(`docs/mls-skipped-keys.md`) under-counts the receiver, and refuses their payouts, which
is the safe direction. It also over-counts the giver, which is the unsafe one. That
device is still only one of the `2t−1` co-signers of a round, and one of the `t`
approvers of a payout. A double payment of the given money needs quorums made entirely
of devices that missed the transfer. That is the same assumption the payouts review
recorded. The plan adds two repairs:

- the group's signed 30343 rides the chronicle, so a late joiner and a restored device
  get it;
- the frost-session resend (`docs/frost-session-resend.md`) already re-sends a
  completed session's signed event to a member who asks. Phase 3 checks that this
  covers 30343.

### T13. What the screens call it

The UI word is *transfer* (*Transfer to a member*, *@alice transferred 20,000 sats to
@bob*), in sentence case. *Send* is wrong because nothing leaves the wallet, and *gift*
claims a motive. The approval line always says **Nothing leaves the wallet, and no fee
is paid.** A co-signer has to see that this is bookkeeping and not a spend.

### T14. What the history says

A transfer is an entry in the receiver's and the giver's history on that rail:
*Transferred to @bob* and *Received from @alice*. Its *whose word* is *signed by the
group*, and it has its own `source` value in the CSV export. In the Wavelength
history, transfer entries are never folded into a term, and the wallet's own activity
total ignores them, because they move nothing in the wallet.

### T15. Every device voids a transfer the signed records don't cover *(added by the Phase 3 review)*

T4's checks bind the signers. They didn't bind what the other devices count, and the
review of Phase 3 showed that this mattered twice:

- **A signed transfer was counted whatever it said.** A quorum that signed one for more
  than its giver held credited the receiver in full. The giver was clamped at zero, and
  the overdraft fell on everyone else's pro-rata share. Two ways in were an old request
  the giver had signed and never had sponsored, and a quorum that signs anything.
- **A Wavelength payout's expiry looked like "never paid" on a device that didn't
  co-sign its round.** A giver who had been paid out could wait for the approval to
  lapse. They could then transfer the same money with a quorum of `t`, which a transfer
  needs, rather than the `2t−1` co-signers a payout's round needs.

So every rail's count applies transfers in the group's order against a **capacity
computed from signed records alone**: the member's deposits, less **every payout the
group signed for them, charged at its most**, whether paid, open, expired, refunded or
gone nowhere. Received transfers are added and given ones taken off as the count goes
(`TransferLedger.netsOf`, `MemberTransfers.capacityIn`). A transfer its giver can't
cover at that point is **void on every device**. So is one the group signed before the
request's `created_at` or after its `expires_at`. Signers check against the same figure
(T4.6), so an honest transfer is never one that some device voids.

The cost is that a payout whose approval ran out unpaid still holds its amount against its
member's *transfers*. It no longer holds it against their next *payout*: the balance
shows that money back, and the member can be paid it, but can't give it away. Treating an
unpaid approval as paid on transfers alone is the price of devices agreeing without
knowing which rounds the others co-signed.

### Spike S1: what a late joiner can count on the Wavelength rail

`ChronicleEvent.KINDS` carries 30339, 30340 and 30341, and not the Wavelength
authorisations (30379). Read what a member added after a payout holds. Do they
receive the 30379 some other way, or is their `balancesIn` already wrong about every
payout made before they joined? If it is wrong, adding 30343 to the chronicle alone
fixes the transfer half and leaves the payout half wrong. **Decide here whether
Phase 2 also adds 30379 to the chronicle.** Its apply order would go after 30343,
since the payout refusal counts transfers.

**Result, 18 November 2026, from the code: a late joiner never receives a 30379.**
`ChronicleEvent.KINDS` doesn't carry it. No other path forwards one either: the only
reads of the kind are `GroupWavelengthPayouts`' own and the runner's count, and the
key notice's answer carries no authorisation. So a member added after a Wavelength
payout already counts that member as never paid. **30379 is not added here.**
Replaying an authorisation is a co-signing question, not a ledger one. A late joiner
holds no leave or payment rows, so it can't tell an approval the group has already
paid from one it hasn't. Making 30379 safe to replay is a plan of its own, and is
listed under *What this does not do*. Phase 2 adds 30343 alone. Only the first
transfer signed for a request counts, and a transfer moves no money, so replaying an
old copy changes nothing. It is as safe as replaying a 30339.

### Spike S2: what an older build does with 30342 and 30343

Expected answer, from the code: it shows the carried 30342 as an unknown inner event,
shows a 30343 proposal as raw JSON, and signs it on approval. Confirm it on two JVM
devices, one on `f4752156`. The answer fixes what T11's release note says.

**Result, from the code, as `payouts-only.md`'s Spike A found for 30341.**
`acceptProposal` runs a hook only for the kinds it knows, and a 30343 falls through to
a session that the member approves by hand. A carried 30342 has no transcript arm, so
it is filed as an unknown inner event and shows nothing. An older device can therefore
sign a transfer, and its balances will still ignore it. That is T11's case, and it is
why Phase 2 ships first.

### Spike S3: the Wavelength scaling ratio on a real wallet

Pull `aux.db` off the emulators (see the emulator-inspection memory note). Compute
`inWallet / totalNet` for the mainnet room wedged by 12 sats and for a healthy one. If
the ratio is 1 in practice outside a wedged wallet, the form's *what arrives* line can
stay in the disclosure. If it often isn't, the line goes on the face of the form.

**Not run:** no emulator was up on 18 November 2026. The form puts the line on its
face whenever the ratio is below 1 and omits it otherwise. That is right either way,
and it costs one string. Phase 7's run measures the ratio.

### Spike S4: who can sign a request

`GroupPayoutDestinationEvent.build` takes the member's `PrivateKey`. List which
sign-in types give the profile one (seed, nsec) and which don't (npub, and any
external signer). The transfer form is offered only where a request can be signed,
and elsewhere the row says why.

**Result, from the code.** `DatabasePayoutDestinationRepository.set` signs with
`Identity.nostrPrivateKey` and offers the form on `Identity.canSign`. A seed or nsec
profile has the key, and an npub profile doesn't. The app has no external-signer
identity. The transfer request uses the same two.

**Done when** the four spikes are written up here, and T1-T14 are confirmed or rewritten.

---

## Phase 1: the two events and the ledger arithmetic, pure

**Three days.** Dark: nothing produces or reads them.

- `nostr/wallet/TransferRequestEvent.kt` (30342): `build(memberPrivateKey, …)`,
  `buildWithdrawn`, `read`, `verified`, `carried`. Its kdoc carries *signed by the
  giver* and *why 30342*, as 30338's does.
- `nostr/wallet/MemberTransferEvent.kt` (30343): `template(request, coins?)`, `read`
  (shape only), `verify(event, room)`, `firstPerRequest`.
- `wallet/MemberTransfers.kt`, pure:
  - `Transfer(id, chain, rail, from, to, amountSat, coins, signedAt)`;
  - `netOf(transfers, chain, rail): Map<member, Long>`: received − given;
  - `applyToCoin(base: Owner, amountSat, transfers): Owner`, which is T6, with void
    transfers skipped;
  - `TransferCoins.choose(owned, amountSat)`, which is T6's selection.
- Tests, one mutation per check:
  - `TransferRequestEventTest`: another room; another chain; an unknown rail; no `p`; a
    zero or negative amount; a note over 140 characters; a withdrawal of nothing.
  - `MemberTransferEventTest`: `from` not the request's signer; a tag disagreeing with
    the request; an embedded request with a bad signature; coin shares on a ledger
    rail; coin shares that don't sum.
  - `MemberTransfersTest`: two transfers in either arrival order give the same nets;
    the same request embedded twice counts once.
  - `ApplyToCoinTest`: a whole coin; a split; a void transfer skipped; a chain of A→B
    then B→C on one coin; a share left below dust.
  - `TransferCoinsTest`: exact fit; one split; nothing confirmed; amount over what is
    owned.

## Phase 2: every device counts transfers, and nothing proposes one

**Three days.** **Ships alone, a release ahead of Phase 3** (T11).

- **Filing.** A completed 30343 is filed as a `GroupSignedEvent`, like every
  group-signed kind. A carried 30342 is filed where carried 30338s are, and read back
  through `TransferRequestEvent.carried`.
- **Wavelength.** `GroupWavelengthPayouts.balancesIn` adds `netOf(…, WAVELENGTH)` to
  each member's net before scaling, and includes receivers with no deposits in
  `everybody`. `Balance` gains `receivedSat` and `givenSat` for the screens.
  `approvedLeaves`, `refuseAgainstBalance` and every co-signer pick it up through
  `balancesIn`, with no change of their own.
- **Lightning.** `LightningBalance.of` takes the member's transfer net. `MemberBalances`
  passes it, and `GroupLightningPayouts.refuse` uses the new figure.
- **One open outflow** (T8). `proposedFor` counts 30343 sessions whose `from` is the
  member, and a payout is refused while one is being signed.
- **Onchain:** nothing yet. Nothing proposes an onchain transfer until Phase 5, and
  T6's coin rule ships with it, a release ahead of its screen.
- **Transcript.** The 30343 line *#Council recorded @alice's transfer of 20,000 sats to
  @bob* goes in `GROUP_IDENTITY_TYPES`. The carried 30342 line is *@alice asks to
  transfer 20,000 sats to @bob*, or *@alice withdrew a transfer*.
- **Chronicle.** `ChronicleEvent.KINDS` gains 30343, after 30341, and 30379 too if S1
  says so. The chronicle note's *what it carries* line is updated.
- Tests:
  - `GroupWavelengthPayoutsJvmTest`: a signed transfer moves the net; the scaling
    applies to the receiver's share; a giver with an open payout and a transfer being
    signed is refused a second payout; a co-signer refuses a leave of given money;
    **T7: `depositsIn` and the tallies are unchanged.**
  - `LightningBalanceTest`: received and given terms.
  - `MemberBalancesJvmTest`: a receiver with no deposits appears.
  - Transcript: both lines, and the withdrawn one.
  - Chronicle: a late joiner's balances after a transfer match everyone else's.

## Phase 3: signing a transfer (policy)

**Four days.** Its own commit, and an adversarial reading of the code before it is
pushed, as the Wavelength payouts' review had. Proposing is behind
`MemberTransferRollout`, open on regtest and signet only.

- `managers/MemberTransfers.kt`:
  - `request(db, room, chain, rail, to, amountSat, note, expiresIn)`: refuses what
    `refuse` would refuse, signs the 30342, carries it, and proposes the 30343 straight
    away if this device can sign;
  - `withdraw(db, room, requestId)`;
  - `putToGroup(db, room, request)`, for a device that holds a share (T10);
  - `requestsIn(db, room)`, giving `WAITING`, `SIGNING`, `SIGNED`, `WITHDRAWN`,
    `EXPIRED` and `REFUSED(reason)`;
  - `refuse(db, room, proposed)`, T4 on the two ledger rails. The onchain rail
    returns *can't transfer onchain yet* until Phase 5.
- `FrostSigningManager.acceptProposal` gains a 30343 hook beside the others, and the
  approve path re-runs `MemberTransfers.refuse`, as it does for a Wavelength payment.
- `MemberTransferRollout`: `offers(chain, rail)` and `NETWORKS`, with the kdoc naming
  what lifts each gate.
- Check that the frost-session resend re-sends a completed 30343 (T12), and add a
  test if it does. If it doesn't, the fix goes here.
- Tests (`MemberTransfersJvmTest`), one per T4 check, plus:
  - two devices: Alice proposes, Bob approves, both file the 30343, and both
    balances move;
  - Alice without a share: her request is carried, and Carol puts it to the group;
  - a quorum proposing a 30343 whose embedded request is by somebody other than
    `from` is refused **(fact 3, the case this plan exists to refuse)**;
  - the same request in two sessions: the second is refused, and if both are signed,
    only the first counts;
  - a request withdrawn while being signed is declined at approval;
  - a payout and a transfer racing for one balance: whichever was proposed second
    is refused.

**What the review found.** The review was an adversarial reading of the Phase 3 code,
by a reviewer given the code rather than this note, before the commit. Each finding is
fixed and has a test:

| found | what it would have let happen | now |
|---|---|---|
| **a signed transfer was counted whatever it said** | a quorum signing an old or overdrawn request credits the receiver in full, and the overdraft is shared out over every other member's balance | T15: a transfer the signed records don't cover is void on every device, and so is one signed outside its request's window |
| **a payout's expiry read as "never paid" on a device outside its round** | a member paid out lets the approval lapse, then transfers the same money with `t` signers, where a payout needs `2t−1` co-signers | T15: capacity charges every signed payout at its most, so signers and counters agree without knowing who co-signed what |
| a request could be dated a year ahead and stand that long | an old consent kept alive | refused more than ten minutes ahead of the signer's clock |
| a transfer batched with a second outflow passed the arrival check | a proposal shown that approval would then decline | a batch holding a transfer and anything else is dropped on arrival |
| a withdrawal didn't touch a session already open | the giver's share still counted towards a quorum over it | the withdrawal declines a session somebody else put to the group, on the giver's device |
| an amount up to `Long.MAX_VALUE` was readable | an overflow in the pro-rata share | at most 21 million bitcoin in sats |

**What it left open:** a session the giver proposed already counts their approval, and
nothing here can take that back. A withdrawal stops it only through the other signers
refusing it at approval. If enough of them approved before the withdrawal reached them,
it completes.

## Phase 4: the screens

**Four days.** Ships with Phase 3's gate. From here, transfers on the two ledger
rails are offered on regtest and signet.

- **Where it starts.** The member's own row on `GroupPayoutsScreen` gets *Transfer to a
  member* for each rail where they hold more than the floor. So does the Wavelength
  wallet's balance card, under *Pay out*.
- **`GroupTransferRoute(chatRoomId, chain, rail)` and `GroupTransferScreen`**, a pushed
  screen. It follows CLAUDE.md: `TopAppBar` with `NavigateBackButton`,
  `ScreenStateTransition` around the four states, `readableContent()`, spacing from
  the scale, strings in sentence case from the catalogue, and 48dp targets.
  - Receiver: a list of current members with a depositor statement on the chain,
    excluding the giver. Each row is `toggleable` with
    `minimumInteractiveComponentSize()`. Members without a statement are listed below
    with *Hasn't started depositing here*, and can't be chosen.
  - Amount: `parseSats`, with *Everything* (the whole ledger balance, T5) and the
    floor. Over the balance, the field says by how much.
  - Note: optional, with a counter.
  - Preview: *@bob will hold 20,000 sats more in the Wavelength wallet. Nothing leaves
    the wallet, and no fee is paid.* Below a ratio of 1, it adds *By this device's count
    that is 19,880 sats today* (T5, S3).
  - *Ask the group to sign*, or *Sign and send the request* where this device holds no
    share. Outcomes go through `rememberNotifier`: *Asked the group to record your
    transfer to @bob*.
  - Empty state: *You have nothing to transfer in this wallet*. Error state:
    `ErrorState` with retry.
- **What a co-signer sees.** `ProposedEvent.summarize` for a 30343 reads *@alice
  transfers 20,000 sats of her Wavelength balance to @bob*, followed by T13's line.
  `FrostSigningScreen` adds *Signed by @alice herself*, the note in quotes, and both
  balances after the transfer, by this device's count.
- **Waiting requests.** The room's detail screen gets a *Transfers waiting* row while
  any are open. It shows each request's state (Phase 3's `requestsIn`), with *Put to
  the group* on a device that can sign and *Withdraw* on the giver's own.
- **Balances.** A payouts row and the Wavelength balance card's disclosure show
  *Includes 5,000 sats received from members* and *20,000 sats transferred to
  members* when non-zero.
- **History** (T14). `GroupWavelengthHistoryView` and the export gain the two entry
  types and the `transfer` source. A receipt (redesign Phase 8) for a transfer names
  both members, the note, and *signed by the group on 18 November*.
- Tests:
  - `GroupTransferScreenJvmTest`: each state; *Everything*; over the balance; a member
    without a statement can't be chosen; an npub profile is offered nothing (S4).
  - `ProposedEventTest`: the summary and T13's line.
  - `GroupWavelengthHistoryViewJvmTest`: transfer entries searched and filtered, never
    folded, and absent from the activity total.
  - `GroupWavelengthExportJvmTest`: the `source` column.
- `./gradlew :composeApp:m3Audit` within every budget.

## Phase 5: the onchain rail, by coin shares (policy)

**Four days.** Its own commit and an adversarial reading. **Ships a release ahead of
Phase 6**, for T11's reason: every device must apply onchain transfers to coin
ownership before any onchain transfer can be proposed.

- `CoinOwnership.Records` gains `transfers`. `ownerOf` applies them after D2's
  steps (T6). `MemberBalances.recordsOf` reads them.
- `MemberTransfers.refuse` gains T4.7 and T6's checks against the device's own wallet
  state, as `CoinAssignments.refuse` does.
- `CoinAssignments.refuse` and `DepositLedger.refuseAttribution` refuse a coin with a
  transfer on it (T6).
- `PayoutRule` doesn't change. It already pays every owner of a shared coin. Tests
  prove it does so for a coin made shared by a transfer.
- Tests:
  - `CoinOwnershipTest`: a transfer on a member-branch coin; on an assigned
    branch-0 coin; a void transfer.
  - `MemberTransfersJvmTest`: a spent coin; a coin with the wrong amount; an
    unconfirmed coin; a giver holding less of the coin than named; shares that don't
    sum.
  - `CoinAssignmentsJvmTest`: a correction over a transferred coin is refused.
  - `GroupSpendSigningJvmTest` end to end: Alice transfers part of a coin to Bob; Bob
    is paid out; the payout pays Alice her remainder too, as D4 says; both devices'
    `PayoutRule` agree.

## Phase 6: the onchain screen

**Two days.** The release after Phase 5.

- `GroupTransferScreen` for `rail = onchain`: the preview lists the coins chosen by
  `TransferCoins.choose`. When one is split, it says *This makes one coin shared
  between you and @bob. When either of you is paid out, the other is paid their share
  at the same time.* (D4).
- The co-signer's approval lists each coin, its owner before the transfer and the
  share moved.
- `CoinRow` labels a coin made shared by a transfer *Shared with @bob, by a transfer*.
- Tests: the preview with and without a split; the approval's coin lines.

## Phase 7: the run, and the docs

**Two days.**

- This note's *Built* line, and a table of where the build chose differently. A row in
  [README.md](./README.md). Pointers in the notes this one changes:
  - `payouts-only.md` D2: a fourth step, transfers;
  - `group-wavelength-payouts.md` W9: outflows, not payouts;
  - `member-deposits.md`: transfers aren't deposits (T7);
  - `member-chronicle.md`: the chronicle carries 30343.
- The checks:

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

- Four emulators on signet, Alice, Bob, Carol and Dan, with a 2-of-3 key among
  Alice, Bob and Carol. Dan holds no share and is added after step 5.
  1. Alice and Bob deposit into the Wavelength wallet. Every device shows both
     balances.
  2. Alice transfers 20,000 sats to Bob with the note *groceries*. Carol's approval
     screen says *Signed by @alice herself* and *Nothing leaves the wallet*. Carol
     signs. Every device's balances move by 20,000, and the deposit tallies don't.
  3. Alice proposes a payout of everything she had before the transfer, by hand
     through a test build. Bob's and Carol's devices refuse it.
  4. Bob is paid out his whole balance, received sats included. The round's leave is
     the amount every device expected.
  5. Carol's device proposes a 30343 moving Alice's balance to Carol, with a request
     signed by Carol's key. Alice's and Bob's devices drop it before it is shown.
  6. Dan joins. After the chronicle, Dan's balances match everyone else's.
  7. Dan transfers to Alice. His request is carried, and Bob's device puts it to the
     group.
  8. Alice requests a transfer and withdraws it before anyone signs. The session is
     declined at approval.
  9. On regtest's onchain wallet: Alice transfers 15,000 sats out of a 40,000-sat
     coin to Bob. Bob is paid out. The transaction also pays Alice her 25,000, less her
     share of the fee.
- The mainnet decision for `MemberTransferRollout` is the owner's, per rail (Question 1).

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 0 | this note, four spikes | nothing |
| 1 | 30342, 30343, the ledger arithmetic, coin application, pure | dark |
| 2 | every balance, payout refusal and co-signer counts transfers; the transcript; the chronicle | **alone, a release ahead of 3** (T11) |
| 3 | proposing and signing, the refusal hook, the sponsor flow, the gate | with 4; regtest and signet only |
| 4 | the transfer screen, the approval lines, waiting requests, history | with 3 |
| 5 | onchain ownership through transfers, and the correction guard | **a release ahead of 6** |
| 6 | the onchain screen | after 5 has had a release |
| 7 | docs, the emulator run, the mainnet decision | last |

About four weeks of work for Phases 0-7. The two ledger rails can be in members' hands
after Phase 4, about two and a half weeks in. **The onchain rail (Phases 5 and 6) can
be left out without touching anything before it** *(Question 4)*.

---

## What this does not do

**It doesn't move money.** A transfer changes whose a balance is, and nothing else
(T1). A member who wants the money in their own wallet is paid out, as today.

**It doesn't cross rails.** A Wavelength balance can't be transferred into somebody's
Lightning balance. Each rail's money is in a different place, and moving between them
is a swap, which none of the wallets has.

**It doesn't let a member ask for money.** *@bob asks @alice for 20,000 sats* is a
request by the receiver that the giver then signs. It fits these two kinds unchanged
(Bob's ask is a chat line, and Alice's 30342 is the consent) and is the first
follow-up.

**It doesn't let the group move a balance without its owner.** A group that wants to
take a member's balance away, for a debt or a penalty, has no way to do it here, and
that is on purpose (fact 3). Such a rule would be the group's to make, in a plan of its
own, and would reopen what payouts-only closed.

**It doesn't reach former members or outsiders.** A transfer is between two current
members, and the receiver must have opened deposits (T3).

**It doesn't change the deposit tallies.** *You've put in 120,000 sats* stays true after
a transfer (T7).

**It isn't enforced by the chain or the operator.** Like payouts-only, it binds upgraded
devices, and a quorum of devices that missed or ignore a transfer can still pay its
giver what they gave away (T11, T12).

**It doesn't touch members' guarded wallets.** They have one owner already.

**It doesn't hand a late joiner the Wavelength payouts.** S1 found that a member added
after a payout never receives its 30379, so their balances count that member as
unpaid. Carrying 30379 safely needs a co-signer that can tell a replayed approval it
has never seen paid from one nobody has paid. That is a separate plan.

## Questions for the owner

1. **Mainnet.** Should transfers be offered on mainnet from Phase 4, as the Wavelength
   payouts were, or only after Phase 7's run? *Recommended: after the run, on the
   ledger rails first.* A transfer moves no money. Its risk is a device that counts it
   wrongly and then pays out, and nothing has yet tested that on real phones.
2. **Receiver consent.** Should the receiver have to accept? *Recommended: no* (T3). On
   the onchain rail, accepting would let a receiver refuse a joint coin, at the cost of
   one more signature before the transfer exists.
3. **Sponsoring.** Should a share-holding device put a non-holder's request to the group
   automatically, as `sponsorIfSigner` does for wallet requests? *Recommended: no, a
   tap* (T10). The quorum still approves either way, so this decides only who starts
   the session.
4. **The onchain rail at all.** Shared coins mean joint payouts, which a member might
   find surprising. *Recommended: build it, after the ledger rails.* The alternative,
   an internal spend that moves coins onto the receiver's branch, is `payouts-only.md`'s
   first follow-up and costs a fee per transfer.

## Appendix: what was considered and rejected

| idea | why not |
|---|---|
| a transfer signed by the group alone | lets any quorum move any member's balance to itself and pay it out, which reopens payouts-only (fact 3) |
| a transfer signed by the giver alone | no order between two that each fit, so devices disagree about three balances (fact 4) |
| moving real money: an Ark transfer, a Lightning self-payment, an onchain spend to the receiver's branch | a fee and a round for something the ledgers already express; on the onchain rail, a second spend shape outside payouts-only. The onchain variant is Question 4's alternative |
| amounts in scaled Wavelength sats | the scaling ratio is each device's own, so signers would disagree on whether an amount fits (T5) |
| a transfer that writes a deposit row for the receiver | credits them twice and dilutes every other member (T7) |
| a newer 30341 or 30340 overriding a transfer on the same coin | silently voids the transfer; corrections of a transferred coin are refused instead (T6) |
| transferring a former member's balance | they can't sign into the room; their balance waits for a payout to their last destination, as now |
| a cap on transfers like `MAINNET_CAP` | nothing leaves; the payout that eventually moves the money meets the cap (T5) |
| reusing 30341 for onchain transfers | a 30341 is the group's word on a coin nobody owned, newest wins, branches 0 and 1 only. A transfer is a member giving up what they own, in order, on any branch |
