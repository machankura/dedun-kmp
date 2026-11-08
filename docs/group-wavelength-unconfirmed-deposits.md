# Money on its way in: unconfirmed onchain deposits into the group's Wavelength wallet

A member asks the group's Wavelength wallet for an address, gets one, and sends 10,000 sats to it.
Every screen in the app says nothing. On mainnet the coin needs six confirmations before the
operator can take it into a round, the round needs a quorum of the group's devices to co-sign, and
the commitment needs three more confirmations before any device believes it — so the silence lasts
an hour at the very best, a day if nobody is at their phone, and for ever if the operator has
stopped. Meanwhile the receive screen keeps handing out the same QR code, which is how one address
gets paid twice.

**Planned, not built.** Nothing here has a commit. Every figure below is read off the code as it
stands on 8 November 2026 from `9d22a7c0`, and the two numbers that matter — six confirmations and
how long after them the operator actually boards a coin — are Phase 0's to confirm.

**What this is, in one line.** Each device already holds every boarding address of the wallet, and
already has its own Electrum connection. This note has it watch those addresses, keep what it sees
in a table of its own, and show it: a line under the balance, a row in the history that says how
deep the coin is, and a receive screen that takes the QR down at first sight of money, which is
what [paid-requests.md](./paid-requests.md)'s D1 asked for and could not have. It adds no event
kind, no policy rule, no authorisation and nothing a device signs — and it must not touch the
deposit ledger, for the reason in A2, which is the one dangerous thing in the area.

### Where this stands

| phase | state |
|---|---|
| 0 spikes, against the real operators | **not run** |
| 1 the table, schema 45 | not built |
| 2 the watch | not built |
| 3 the history row | not built |
| 4 the balance card, the cap, the operator's figure | not built |
| 5 the receive screen pays off D1 | not built |
| 6 the guard tests, the strings, the notes | not built |
| 7 a real deposit, by hand | **not run**: needs a phone and a boarding address |

## What is invisible today

A boarding deposit passes through six states. The device holds a record for the first and the last
two, and nothing at all for the three in the middle.

| state | what this device holds | what any screen shows |
|---|---|---|
| the operator answered with an address | `GroupArkDepositAddress` with `address` and `script`, written after `isBoardingScript` checked it (`GroupWavelengthDeposits.kt:122`) | the receive screen's QR code |
| somebody paid it; the transaction is in the mempool | **nothing** | nothing |
| it confirmed, 1 to 5 deep | **nothing** | nothing |
| 6 deep, boardable, waiting for the operator to call a round | **nothing** | nothing |
| this device co-signed it into a round | `GroupArkBoarded` | a history row, `IN_FLIGHT` (`GroupWavelengthHistory.kt:271`); `addressPaid` on the receive screen |
| the commitment is `settleDepth` deep | `DepositRow(source = WAVELENGTH, confirmedAt = now)` | a history row, `DONE`; the member's payable balance; the room's tally |

The three blank rows are not an oversight. They are a decision that was made deliberately and
written down, in `GroupWavelengthRequests.paidOnAddress` (`GroupWavelengthRequests.kt:42`):

> There is no operator fallback: the snapshot's activity lines carry a payment hash and nothing that
> names an address, so a boarding payment the operator alone has seen cannot be told from any other
> member's. The panel therefore appears when the group signs for the coin rather than at first sight
> of it in the mempool.

That reasoning is sound and it is about the **operator's word**. The paid-requests note had asked
whether the operator's snapshot could fill the gap, found that it could not, and stopped there —
`docs/paid-requests.md:35` records exactly that. What neither note considered is that the device
does not need the operator at all here. It has the address, it checked the address, and it has a
connection to the chain.

### How long the blank window is

| wait | mainnet | signet | where it comes from |
|---|---|---|---|
| confirmations before a coin can board | **6** | 1 | `GroupWavelengthTerms.boardingConfirmations` |
| then a round, which needs `2t−1` devices to answer | unbounded | unbounded | `docs/group-wavelength-wallet.md`, D9 |
| then the commitment's depth before any device believes it | **3** | 1 | `GroupWavelengthRollout.settleDepth` |

So on mainnet the floor is nine blocks plus a quorum, and the ceiling is "never" — a wallet whose
operator has stopped holds boarding coins nobody will round in until an election. For all of that
time the depositor's own screen is indistinguishable from one where they mistyped the address.

## Why this is the device's own business, and needs nothing from the operator

Three facts, all already true, make the watch free of the operator:

**Every device holds every boarding script.** A `DEPOSIT_ANSWER` is an MLS inner event the whole
room reads; `GroupWavelengthDeposits.process` files it on every device, and `receiveAnswer` writes
`script` only after `GroupArkCoSigner.keysOf(wallet).isBoardingScript(...)` has recomputed it from
the group's own Iceberg key under the statement's operator key and boarding delay. A script in that
column has already been checked. There is nothing left to take on trust.

**Every device has a connection to the chain.** `GroupChainClients` gives it the identity's own
client for the node's network and a `StandaloneElectrum` for any other. `GroupArkWatcher` already
uses it to follow rounds, and already reads parent transactions off it in `boardingHoldsUp`.

**The amount comes off the address, not off a note.** `getScriptHashUnspents` returns
`UnspentItem(txid, outputIndex, value, blockHeight)`. The value is the chain's. Compare
`boardingHoldsUp`, which exists precisely because a round's *note* about a boarding coin is the
operator's word until the coin's parent is read — here there is no note to check, because nobody
sent one.

So this is the first incoming rail on this wallet where the device can keep the wallet's standing
rule — *this device's own record first, the operator's word second* — with no help at all. On the
Lightning rail it cannot: nothing can prove where an invoice pays (spike 6). On the boarding rail it
can, and today it does not try.

## The one thing that must not happen

`GroupWavelengthPayouts.balancesIn` (`GroupWavelengthPayouts.kt:145`) reads what a member has put
in straight off the deposit ledger:

```kotlin
val deposited = DepositLedger.depositsIn(database, chatRoomId, chain)
    .filter { it.source == DepositRow.WAVELENGTH && it.member != null }
```

**There is no filter on `confirmedAt`.** A `DepositRow` is a payable balance the moment it exists.
So filing an arriving coin into the deposit ledger — which is the obvious thing to do, and which the
group's *onchain* wallet already does, `confirmedAt = null` and all (`DepositLedger.recordOnchain`)
— would credit the depositor at once, before a single confirmation.

The damage is not only to the depositor. `balancesIn` ends by scaling every member's payable share
by what the wallet actually holds:

```kotlin
val share = if (totalNet == 0L || inTheWallet >= totalNet) sat else sat * inTheWallet / totalNet
```

`inTheWallet` is settled, unspent VTXOs. A 50,000-sat payment to a boarding address, replaced
before it confirms, would double `totalNet` in a 50,000-sat wallet and **halve every other member's
payable balance** until the row went away — and the row would never go away, because the deposit
ledger is kept rather than derived, by design ("a deposit the group has spent since still counts").
It costs one replaceable transaction to make, and the money never leaves the sender.

The group's onchain wallet can file unconfirmed rows safely because its payout balances come from
coins and ownership records (`MemberBalances.balancesIn` walks `state.utxos` through
`CoinOwnership`), and the ledger only feeds tallies. On the Wavelength rail **the ledger is the
balance.** So: arriving coins get a table of their own, and `GroupWavelengthDeposits.roundHappened`
goes on being the only thing in the app that writes a Wavelength deposit row.

## Read these first

- [paid-requests.md](./paid-requests.md): D1 (*paid means seen, not final*), D2, and the table row
  at `:35` that says the group's boarding address cannot do D1. Phase 5 is that row answered. The
  reason given there is right about the operator and wrong to stop at the operator.
- [group-wavelength-transaction-details.md](./group-wavelength-transaction-details.md): T2 (whose
  record a row is), T3 (why six tables got a time), T4 (ids), T11 (*a boarding address is not an
  entry*) and T12 (the operator's lines carry no outpoint). A4 and A6 below lean on T4 and T3; A5
  is the one place this note adds to T2's vocabulary.
- [group-wavelength-ui.md](./group-wavelength-ui.md): U2 (*the balance is the device's own ledger*,
  with the operator's beside it) and U4. A8 and A14 are U2 applied to a figure U2 never had.
- [member-deposits.md](./member-deposits.md): what a deposit row is for and why it is kept rather
  than derived. That property is exactly what makes A2 dangerous.
- [group-wavelength-wallet.md](./group-wavelength-wallet.md): D9 and D11, on who calls a round and
  what happens when nobody does — which is how long the blank window can get.
- [group-chain-wallet.md](./group-chain-wallet.md): `GroupWalletState.pendingBalance`, *"In the
  mempool: the group's, but not yet spendable by the send-all builder"*. The same sentence, for the
  same reason, is what A8 wants on this card.

## Decisions

**A1. An arriving coin is this device's own sight of the chain, and the operator is not asked.** The
address was checked when it was answered, the script hash is derived from it, the amount and the
height come from the address's own unspents. No new wire traffic exists except Electrum
subscriptions the same client already makes for the group's onchain wallet.

**A2. It is not a deposit, and nothing writes it to the deposit ledger.** For the reason above. A
guard test in Phase 6 pins `balancesIn` as unchanged by any number of arriving rows, and that test
is the point of the phase.

**A3. A table rather than memory.** `GroupOnchainWallet` keeps its coins in memory and refetches
them at start, which is the obvious precedent and the wrong one here, for three reasons:

- the history needs a time that survives a restart, which is the whole reason T3 put
  `atEpochSeconds` on six tables — an in-memory watch reintroduces exactly the bug T3 fixed, and an
  entry with no time of its own sorts *last*, which for the newest thing in the wallet is a lie;
- D1's panel has to stay down after a restart, or the QR comes back and somebody pays the address a
  second time, which is the one thing D1 exists to stop;
- the member it belongs to is resolved from the script, and the right moment to resolve it is when
  the coin is first seen, next to the check.

**A4. The row is keyed by the outpoint, spelled `DepositAttributionEvent.DepositKey.Onchain(txid,
vout).text`.** That is `"$txid:$vout"`, byte for byte what `GroupArkBoarded.outpoint` holds and what
`roundHappened` writes into `DepositRow.depositKey`. So the entry id stays `deposit:<txid>:<vout>`
through all four states a coin passes, the detail route survives the transition under the reader,
and T4's rule — the id is matched against the built list and never parsed back — keeps holding. Any
other spelling (an unpadded vout, upper-case hex) shows the same coin twice.

**A5. A new `GroupWavelengthEntry.Status.ARRIVING`, not a reuse.** `WAITING` is *"Asked for,
approved, or sitting at an address: nothing has moved"* and money has moved. `IN_FLIGHT` is
*"Signed for, and this device hasn't seen it take effect"* and nothing has been signed. The
difference matters to a reader: `ARRIVING` is the only state in the deposit's life where the answer
is *wait, and here is how long*. `isSettled` stays false for it, and the one `when` that needs a new
branch is `entryStatus` (`GroupWavelengthEntryText.kt:75`), which already has an `else`.

**A6. The depth is computed against the tip from a stored height, never stored as a count.** The row
holds `blockHeight: Long?` — null in the mempool — and the depth is `tip − height + 1`, the way
`Standing` computes it (`ElectrumConfirmations.kt`) and the way `GroupArkVtxo.expiresAtHeight`
works. Never `IElectrumClient.getConfirmations`, which counts from the height its connection began
at: that function's own doc records a signet run that sat over an hour on a coin ten blocks deep.
Storing a count would also mean a write per block per coin.

**A7. Three things a reader needs told apart, inside one status.** In the mempool; *n* of 6
confirmations; deep enough and waiting for a round. One status, one format string, the target taken
from `GroupWavelengthTerms.of(chain)?.boardingConfirmations`. The third is the one that stops the
member blaming their own wallet, and it is also the one that says the group should nominate someone
if nobody is running the wallet.

**A8. Shown beside the balance, never added to it.** The headline figure is what the wallet can
spend, and a boarding coin cannot be spent through the wallet at all until a round takes it in. It
becomes a third line under `pending` and `held`, in the voice the onchain wallet already uses for
the same fact. `GroupWavelengthLedger.isEmpty` stays about coins and is not touched: it gates the
Leave button (`GroupWavelengthWalletScreen.kt:642`), and a wallet with an arriving coin and no coins
has nothing to leave with.

**A9. The cap counts it.** `roomLeftSat` is `cap − settled − pending` today
(`GroupWavelengthReceiveViewModel.kt:103`). An arriving coin takes room under `MAINNET_CAP` that no
co-signer will sign past, so it becomes `cap − settled − pending − arriving`, and
`GroupWavelengthReceiveUIState.AtTheCap` becomes reachable by money already on its way. The same
subtraction applies to `maxInvoiceSat`: offering an invoice for room a deposit has already taken
produces a coin nobody signs for.

**A10. A coin that leaves the mempool unboarded is forgotten; one that was boarded hands over.**
Disappearance from the watch's state means two different things — spent into a round, or replaced.
One `getScriptHashHistory` on that address tells them apart: the txid still in the history means the
transaction happened and its output has been spent; absent means it was replaced or reorged out, and
the row goes. A row whose outpoint already has a `GroupArkBoarded` or `DepositRow` is handed over
and forgotten either way, and A11's ordering makes a moment where two exist harmless.

**A11. The dedupe order is deposit row, then boarded, then arriving.** Three tables can hold one
outpoint at once. `GroupWavelengthHistory.deposits` already drops a boarded row whose key is in the
landed set (`GroupWavelengthHistory.kt:271`); arriving rows are dropped against both sets, by the
same key, in the same function.

**A12. A coin boarded by a round this device did not sign is kept and marked, not dropped.** A
device that missed the round has no boarded row and never gets a deposit row — `roundHappened` only
files coins from `boardedIn`, which it has none of — so today that deposit is invisible on that
device for good, and under-counts its member in `balancesIn`. This note cannot fix that (it is
`roundHappened`'s hole and wants its own note), but it can stop hiding it: one nullable column,
`spentUnseenAtEpochSeconds`, set when A10 finds the output spent with no round of this device's
behind it; the history row then says the coin was taken into a round this device did not sign, and
the receive panel stops promising the money is on its way. **This plan makes a pre-existing hole
visible for the first time,** which is the point of U4 and worth the column.

**A13. Nothing new on the wire, in the policy, or in what a device signs.** No event kind, no
`ArkPolicy` rule, no authorisation type, no change to `checkRound`. The schema grows by one table
and the catalogue by a handful of strings.

**A14. The operator's own pending figure is shown beside the device's, and is read for the first
time.** `GroupWavelengthSnapshot.pendingInSat` is written by `snapshotOf`
(`GroupWavelengthDaemon.kt:161`), carried on the wire, filed in `GroupArkSnapshotRow` — and read by
nothing in the app. U2 says the device's own figure leads and the operator's sits beside it with
*matches* or *differs*; this is the one incoming figure where both exist, so it is also the first
place the two can disagree about money arriving. The two will not match exactly (the daemon's
`pendingInSat` covers outgoing-adjacent states too, and S6 settles what it actually contains), so
the line names the operator rather than asserting agreement.

**A15. No connection means "cannot say", never "nothing".** `UnusedAddressCheck` exists because
lightning-kmp answers a *failed* history call with an empty list, so an address checked while the
server is down reads as never used — the note that built it calls this out as Spike B. The same trap
here reads as "no money on its way", which would put the QR back up and zero the card's new line.
So the watch holds its last figure, a pass that could not reach a server changes nothing, and the
card says the chain has not been reached — as `standingOf` returns null rather than zero, and for
the same reason. Tor on with a wallet on a non-node network means there may be no connection at all.

## Phases

One commit per phase, each compiling and passing `./gradlew :composeApp:compileDebugKotlinAndroid
:composeApp:jvmTest` and `./gradlew :composeApp:m3Audit`. Messages end `(Group Wavelength
unconfirmed deposits, Phase N)`. No phase changes what a device signs, so none needs the adversarial
reading a **policy** phase gets — which is why this is seven phases of plumbing and screens rather
than the fifteen days of policy work its neighbours needed.

### Phase 0: spikes, against the real operators

No app code. Each answer is written back into this note, and A7, A9 and A14 are confirmed or
rewritten.

- **S1. What the operator does with a boarding coin under `minBoardingSat`.** 1,000 sats on both
  networks. Send 500 to a boarding address on signet and see whether it is ever boarded, refused, or
  silently ignored. **If it is never boarded, an arriving row under the minimum must say so** rather
  than promise a deposit that will sit there for ever — and the receive panel's wording changes with
  it. This is the likeliest way for this feature to lie to somebody.
- **S2. How long after the sixth confirmation the operator actually calls a round.** On signet,
  where it is one confirmation, and then on mainnet by reading `get-info`. Sets A7's third wording:
  "waiting for a round" is honest only if a round is a thing that happens on its own.
- **S3. `boardingConfirmations` from the live `get-info` against the 6 in
  `GroupWavelengthTerms.MAINNET`.** `GroupWavelengthTermsReader` already reads that endpoint. A
  statement carries only `exitDelay` and `boardingDelay` (`Terms.policy()`), so the confirmation
  target is either this build's constant or a live read, and question 5 turns on which.
- **S4. `ElectrumMiniWallet` with many single addresses on the shared client.** Add 80
  `addAddress(...)` calls to one mini-wallet on the identity's own client, against the node's
  server, and measure: time to the first `walletStateFlow` emission, whether `addAddress` after
  `start()` is picked up, whether the group's onchain wallet's own two mini-wallets on the same
  client are slowed, and what a disconnection does to the state it holds. **If the first answer is
  slow enough to be seen, the card needs A15's "not reached yet" from the first frame** rather than
  a zero.
- **S5. A replaced boarding payment, on signet.** Pay a boarding address, replace the transaction
  before it confirms, and watch what the mini-wallet's state and `getScriptHashHistory` do. A10's
  rule is written from the answer.
- **S6. What `pendingInSat` actually contains.** Read the SDK, and a real daemon's figure against a
  known boarding coin. A14's line is only worth drawing if the two figures are about the same thing.

**Done when** each spike's numbers are in this note and the owner has answered questions 1, 2 and 5.

### Phase 1: the table and the DAO, with nothing writing it

```kotlin
/**
 * A transaction paying one of this wallet's boarding addresses, as this device's own connection to
 * the chain has seen it, before any round has taken it in: `docs/group-wavelength-unconfirmed-deposits.md`.
 *
 * Not a deposit (A2). It becomes one when a round this device signed for happens, and until then it
 * is money on the chain that the wallet cannot spend.
 */
@Entity(primaryKeys = ["wallet", "outpoint"])
data class GroupArkArriving(
    val wallet: String,
    /** `DepositKey.Onchain(txid, vout).text`, so one id spans all four states (A4). */
    val outpoint: String,
    val amountSat: Long,
    /** The boarding script it paid, which this device had already recomputed as the group's. */
    val script: HexKey,
    /** Whose address it paid, from the answered request; null for a script no row claims. */
    val member: HexKey? = null,
    /** When this device first saw it, which is the only time a mempool transaction has (A3). */
    val firstSeenAtEpochSeconds: Long,
    /** The block it is in, or null in the mempool. The depth is computed against the tip (A6). */
    val blockHeight: Long? = null,
    /** When this device saw the output spent with no round of its own behind it (A12). */
    val spentUnseenAtEpochSeconds: Long? = null,
)
```

- `GroupArkDao`: `upsertArriving`, `arriving(wallet)`, `observeArriving(wallet)`,
  `forgetArriving(wallet, outpoint)`, `markArrivingSpentUnseen(...)`, and
  `walletsWithDepositAddresses()` beside `walletsWithCoins()` — the watch needs the wallets to look
  after, including earlier wallets, whose boarding addresses can still be paid.
- `DedunDatabase`: the entity, version **45**, `AutoMigration(from = 44, to = 45)` with the comment
  the others carry. A new table changes nothing existing, so Room generates it.

**Tests,** in `GroupArkHistoryDaoJvmTest`'s neighbourhood: a round trip, the primary key rejecting a
second row for one outpoint, `observeArriving` emitting on insert and on forget, and
`walletsWithDepositAddresses` returning an earlier wallet's key.

**Done when** the schema builds, the DAO tests pass, and no behaviour has changed anywhere.

### Phase 2: the watch

`GroupArkBoardingWatch`, in `wallet/wavelength/group/`, beside `GroupArkWatcher` and with the same
lifetime — wired in `DedunNavHost.kt` next to it (`:501`), on `groupChainClients`, with a
`DisposableEffect` that stops it.

- One `ElectrumMiniWallet` per (wallet, chain), fed `addAddress` for every
  `GroupArkDepositAddress.address` whose `script` is non-null. It follows
  `observeDepositAddresses`, so an address answered while the app is open is watched without a
  restart. Not an address generator: these are not an HD branch with a gap limit, they are a list
  the group was given, and `GroupArkKeys.window` is 512 scripts nobody has been handed.
- Collects `walletStateFlow`. For each utxo: upsert a `GroupArkArriving` with the value, the
  `blockHeight`, the member resolved from the script, and `firstSeenAtEpochSeconds` kept from the
  existing row where there is one. For each row no longer in the state: A10's one history call, then
  forget, hand over, or mark.
- Reads nothing from the operator and writes nothing to `DepositLedger`. A row's `member` comes from
  the answered request this device already checked.
- Honours A15: a pass that cannot reach a server leaves every row alone and reports that it could
  not, on a `StateFlow` the screens read.

**Tests,** in a new `GroupArkBoardingWatchJvmTest` on the `FakeElectrum` from
`GroupArkWatcherJvmTest.kt:52`: a mempool utxo becomes a row with a null height; a confirmation
fills the height and keeps `firstSeenAtEpochSeconds`; a second address of another member resolves to
that member; a utxo that vanishes with its txid still in the history and a boarded row present is
forgotten; one that vanishes with no boarded row and the txid still in history is marked
`spentUnseenAtEpochSeconds`; one that vanishes with its txid gone from the history is forgotten; a
disconnected client changes nothing; an address with no `script` is never watched. And one test that
asserts `database.depositDao()` is empty throughout.

**Done when** those pass and the deposit ledger is provably untouched.

### Phase 3: the history row

- `GroupWavelengthEntry.Status.ARRIVING` (A5), and `Detail.OnchainDeposit` gains
  `arriving: GroupArkArriving? = null` beside its `boarded`, `deposit` and `coin`.
- `GroupWavelengthHistory.Rows` gains `arriving: List<GroupArkArriving>`, and `deposits(...)`
  (`GroupWavelengthHistory.kt:229`) grows a third source after the landed rows and the boarded ones,
  dropped against both key sets (A11). `at` is `firstSeenAtEpochSeconds`, `direction` is `IN`,
  `source` is `THIS_DEVICE`, `amountSat` is the chain's, `feeSat` is 0.
- `GroupWavelengthRepository.historyRows` reads it; the interface, the database implementation and
  `NO_OP` all gain it, as T2's table says every new read must.
- `entryStatus` (`GroupWavelengthEntryText.kt:75`) grows A7's three wordings for the deposit kinds,
  with the target confirmations passed in, and A12's wording for a marked row.
- The entry screen and the technical sheet render the new detail: the outpoint, the amount, the
  script's address, the depth, the first-seen time, and the member.

**Tests,** in `GroupWavelengthHistoryJvmTest`: an arriving row alone becomes one `ARRIVING` entry
with the id `deposit:<txid>:<vout>`; the same outpoint as an arriving row *and* a boarded row
produces exactly one entry, `IN_FLIGHT`; all three tables at once produce exactly one, `DONE`; an
arriving row sorts above a landed deposit from an hour ago; a marked row reads as A12; a row whose
script no request claims has a null member and still shows.

**Done when** the pure tests pass, and a screen test renders all four states of one coin without the
id changing.

### Phase 4: the balance card, the cap, and the operator's figure

- `GroupWavelengthWalletUIState.Opened` gains `arriving: List<GroupArkArriving>` and the view model
  fills it from a flow beside `observeCoins`. The card's third line sits under `pending` and `held`
  (A8), and says the sum and how far along the nearest coin is.
- A15's "the chain has not been reached" state on the card, from the watch's flow — distinct from
  zero, as the deadline line already distinguishes `WalletExpiry.Nothing` from a known expiry.
- A14's line beside it where a snapshot holds a non-zero `pendingInSat`, naming the operator, in the
  voice `group_wavelength_entry_source_both` already uses.
- A9: `roomLeftSat` and `maxInvoiceSat` subtract the arriving sum, in both the initial read
  (`GroupWavelengthReceiveViewModel.kt:70`) and the combined flow (`:103`), so `AtTheCap` can be
  reached by a deposit on its way.

**Tests:** `GroupWavelengthWalletViewModelJvmTest` for the three figures and the unreachable state;
`GroupWavelengthReceiveViewModelJvmTest` for `AtTheCap` from an arriving coin and for
`maxInvoiceSat` shrinking; a screen test in `GroupWavelengthUiFixtures`' style for the card's four
shapes (nothing, settled only, settled plus arriving, unreachable).

**Done when** those pass and `m3Audit` is clean — the new lines are three `Text`s and no new
spacing, colour or target.

### Phase 5: the receive screen pays off D1

- `GroupWavelengthRequests.paidOnAddress` takes `arriving: List<GroupArkArriving>` beside `boarded`,
  and answers from either: the boarded rows first (they are the stronger record), the arriving rows
  otherwise, both `onTheOperatorsWord = false`. Its doc comment's last sentence — *"The panel
  therefore appears when the group signs for the coin rather than at first sight of it in the
  mempool"* — is deleted, and the reason it was there is recorded in this note instead.
- `GroupWavelengthReceiveViewModel` adds `observeArriving` to its `combine`.
- The paid panel says the amount, that it is still arriving, and how far along, as D2 requires: same
  place, same column, with the way to ask for another address.
- S1's answer, if it is that small coins are never boarded: a row under `minBoardingSat` says so
  instead of promising a deposit.

**Tests:** the paid-request tests in `GroupWavelengthReceiveViewModelJvmTest` extended — a mempool
coin takes the QR down, a boarded coin still does, a replaced coin puts it back up (which is correct:
nobody has paid), and a coin under the minimum says what S1 found.

**Done when** `paid-requests.md`'s D1 holds on all four of its surfaces, which it has never done.

### Phase 6: the guard tests, the strings, and the notes

- **The guard that matters:** a test in `GroupWavelengthPayoutsJvmTest` that builds a wallet with
  one member's 50,000-sat arriving row and asserts `balancesIn` is byte for byte what it is with no
  arriving rows at all — every member's `payable`, `share` and `bearsWhatsOpen`. And a second that
  does the same for `DepositLedger.tallies`. A2 is the dangerous decision in this note, and a test is
  what keeps a later commit from undoing it by helpfully calling `merge`.
- **The strings:** the new lines in `strings.xml`, sentence case, format arguments, in the
  `group_wavelength_*` family. `StringCatalogueJvmTest` where an escape is involved.
- **The notes:** this one marked built with what the build found; `paid-requests.md`'s table row at
  `:35` and its D1 updated; `group-wavelength-transaction-details.md`'s T11 given a sentence saying
  a boarding *transaction* is an entry though a boarding *address* is not, and T2's status
  vocabulary extended; `group-wavelength-ui.md`'s U2 given the arriving figure; `docs/README.md`'s
  table and its tail paragraph.

**Done when** `./gradlew check` passes and the two guard tests fail if A2 is undone.

### Phase 7: a real deposit, by hand

On signet first if coins can be found, then mainnet with a small amount under the cap. Record, in
this note: the time from broadcast to the row appearing, the depths the screen showed, the time to
the sixth confirmation, the time to the round, whether the QR went down at the right moment, whether
the card's figure matched the operator's `pendingInSat`, and whether the row handed over to a
boarded row and then to a deposit row with the id never changing.

**Done when** one coin has been watched through all four states on a phone, and the figures are
here.

## Order

| phase | depends on | touches | rough size |
|---|---|---|---|
| 0 spikes | signet coins, both operators | nothing in the app | 2 days |
| 1 the table | — | schema | half a day |
| 2 the watch | 1, S4, S5 | app | 2 days |
| 3 the history row | 2 | app | 1 day |
| 4 card, cap, operator's figure | 2, S3, S6 | app | 2 days |
| 5 the receive screen | 2, S1 | app | 1 day |
| 6 guards, strings, notes | 3, 4, 5 | app, docs | 1 day |
| 7 a real deposit | all | a phone, a boarding address | the owner's call |

Phase 1 can start before the spikes finish: no spike changes the table's shape. Phases 3, 4 and 5
run side by side after 2, and touch different files. The estimates are as unreliable as this note's
neighbours said theirs were, and for the same reason: what takes the time is always the real server.

## Risks

| risk | why it matters | what to do |
|---|---|---|
| it gets filed as a deposit, now or later | A2's dilution: one replaceable payment halves every other member's payable balance, for free | A2, and Phase 6's guard test, which is the phase's whole point |
| an empty Electrum answer read as "nothing arriving" | the card reads zero and the QR goes back up while money is on its way | A15; subscriptions rather than history calls, as `UnusedAddressCheck` already encodes |
| the first answer is slow | the card shows zero for a few seconds, which reads as "they never paid" | S4 measures it; A15's unreachable state is the first frame, not a zero |
| a coin under `minBoardingSat` | the panel promises a deposit the operator will never board, for ever | S1, and a row that says so |
| RBF or a reorg | a row for money that never arrives, or a confirmed row going back to the mempool | A10 and A6: the height is re-read each pass and the depth is computed, never stored |
| 80 subscriptions on the client the onchain wallet shares | a slow wallet screen for everybody | S4; and the set is bounded at `MAX_ADDRESSES_A_MEMBER` (16) per member |
| the linkage one Electrum server now sees | every device watches every member's boarding address on one connection | the same server already sees the group's receive branch, its change branch and every member's deposit branch through `GroupOnchainWallet`, and the operator's daemon already watches all the boarding ones. Named, and the narrower alternative is in the appendix |
| a device that missed the round | this plan makes a pre-existing hole visible: that device never counts the deposit and under-counts its member | A12 marks it. The hole is `roundHappened`'s and wants its own note |
| the card reads as *your money is fine* | U2's standing risk, one figure larger | the arriving line says what the money cannot do yet, in the onchain wallet's words |
| schema churn across branches | two branches bumping to 45 | one migration when it merges, as the UI note's D16 says |

## Questions, for the owner

1. **Does the arriving figure belong on the balance card, or only on the receive screen and in the
   history?** I would put it on the card, because A9 makes the cap depend on it and a cap that
   refuses an invoice for a reason the card does not show is the kind of thing that gets reported as
   a bug. The argument against is U2's standing risk: another figure on a card that already carries
   four.
2. **Every member's addresses, or only the reader's?** Watching all of them makes the card's figure
   the wallet's, which is what it claims to be. Watching only the reader's own halves the linkage a
   server sees and makes the figure the reader's own money, which is a different and smaller promise.
   I would watch all of them, and I would want this answered before Phase 2 rather than after.
3. **Is A12 worth a column?** It costs one nullable field and gives a member the first sight they
   have ever had of their device missing a round. It can be dropped to a log line with no other
   change.
4. **Should an arriving deposit put a line in the room?** This plan says no, on the grounds that
   nothing writes a line for a deposit that *landed* either, and a group that hears about money on
   its way but not about money arrived is worse off than one that hears about neither. If deposits
   should be in the transcript, that is its own small note covering both.
5. **Does the confirmation target come from this build's constant or a live read?** S3 says whether
   they agree today. A live read is honest and makes the number change under the reader; the
   constant is stable and can go stale, as `MIN_CHANGE_SAT` did.
6. **Signet coins for Phases 0 and 7,** by hand, into a fresh wallet. Four notes before this one
   asked for these and the rows that needed them still say *not run*.

## What this plan does not do

- **Make the money spendable any sooner.** Boarding needs the operator's confirmations and a round
  with a quorum. This is about saying where the money is, not moving it.
- **Change the deposit ledger, the policy, the wire, or anything a device signs.** A13.
- **Count an arriving coin towards anybody's payable balance.** A2, and that is deliberate: the
  depositor cannot be paid out of a coin the wallet does not hold.
- **Show unconfirmed Lightning deposits.** An HTLC in flight is a different rail with a different
  proof problem (spike 6), and an unpaid invoice already has a `WAITING` row of its own (T11).
- **Show the group onchain wallet's unconfirmed coins.** Already done:
  `GroupWalletState.pendingBalance`.
- **Touch the personal Wavelength wallet.** It already shows pending from its own daemon
  (`WavelengthWalletViewModel.pendingSat`, `WavelengthWalletScreen.kt:263`), because the daemon runs
  on the same device. The group's wallet is the one where the daemon is somebody else's.
- **Fix `roundHappened`'s hole** for a device that missed the round that boarded a deposit. A12
  makes it visible; fixing it means a device filing a deposit for a round it did not co-sign, which
  is a question about whose word a deposit is and belongs in its own note.
- **Put a line in the transcript.** Question 4.

## Appendix: what was considered and rejected

| considered | why not |
|---|---|
| **file it as a `DepositRow` with `confirmedAt = null`**, exactly as `DepositLedger.recordOnchain` does for the group's onchain wallet | A2. For the onchain wallet the ledger feeds tallies and the payable balance comes from coins; for Wavelength the ledger *is* the payable balance, and the ledger is kept rather than derived, so a replaced payment dilutes everybody for ever. This is the obvious design and it is a money bug |
| keep it in memory, as `GroupOnchainWallet` does | A3: loses the first-seen time T3 was added to six tables for, and lets the QR come back after a restart, which is what D1 exists to stop |
| ask the operator — show the snapshot's `pendingInSat` and stop there | one figure for the whole wallet, with no outpoint, no member and no depth, on the word of the party being checked. A14 shows it, beside the device's own, which is what U2 asks for |
| derive all 512 boarding scripts from `GroupArkKeys` and watch them | 512 subscriptions per wallet for addresses nobody has ever been given. The answered ones are the only ones anybody can pay, and every device holds all of them |
| poll each address every 30 seconds, as `GroupArkWatcher` polls rounds | about 80 RPCs a pass on the client the onchain wallet shares. `ElectrumMiniWallet` subscribes once and reacts to notifications, and the app already runs two of them |
| a new `Kind` rather than a new `Status` | it is the same deposit at an earlier moment, and T4 requires the id to stay the same string through every state so the detail route survives |
| count it in `settledSat`, or in `pendingSat` | A8: it cannot be spent through the wallet at all, and `pendingSat` already means *coins this device signed for and hasn't seen settle*, which is a claim about a signature this coin has none of |
| make it `isEmpty = false` on the ledger | it gates the Leave button; a wallet with an arriving coin and no coins has nothing to leave with |
| watch only the reader's own addresses | question 2. Halves the linkage, and makes the card's figure the reader's money rather than the wallet's — a smaller promise than the card makes everywhere else |
| a transcript line when a deposit arrives | question 4: nothing writes one when a deposit *lands*, and the asymmetry would read as a bug |
| have the watch verify the parent transaction, as `boardingHoldsUp` does | there is nothing to verify against. `boardingHoldsUp` exists because a round's *note* about a boarding coin is the operator's word; here the amount and the script come off the address's own unspents, and no note was sent |
