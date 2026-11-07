# One transaction of the group's Wavelength wallet, in general and in full

A member taps a line in the wallet's activity and gets a screen about that one thing: what it was, how
much, whose, where it got to, and what it cost. Behind a *Technical details* button, a bottom sheet with
every field this device holds and every field the operator claims, each marked as one or the other.

**Not built. Written against `9f99e06b`,** and the files and lines named below are as they stood at that
commit. It takes over nothing from the earlier notes: it adds a screen the UI plan never had, on top of
what [group-wavelength-ui.md](./group-wavelength-ui.md) Phase 5 and
[group-wavelength-payouts.md](./group-wavelength-payouts.md) built. Those notes' decisions (D1 to D16,
W1 to W12) all still apply, and **nothing in this plan changes what a device signs.** No phase here is
marked *policy*, because none touches `ArkPolicy`, `GroupArkCoSigner`, an event's wire shape, or a
refusal rule. If a phase below ever needs to, it stops and gets the reading those notes ask for first.

Read these first:

- [group-wavelength-ui.md](./group-wavelength-ui.md): the screens this adds to, and U2 above all — *this
  device's own figure first, and the operator's beside it, since only one of them has been checked.*
- [group-wavelength-payouts.md](./group-wavelength-payouts.md): what a payout is, on both rails, and
  W11's record of one.
- [member-deposits.md](./member-deposits.md) and [payouts-only.md](./payouts-only.md): whose money is
  whose, and why a coin is nobody's share.
- [wavelength-wallet.md](./wavelength-wallet.md): the profile's own wallet, whose detail screen this
  copies and shares widgets with.
- [CLAUDE.md](../CLAUDE.md): the rules every screen follows.

## Why this is more than a row that opens

The profile's own Wavelength wallet already has this: `walletHistory` lists `Entry` rows,
`WavelengthEntryDetail` (`WavelengthWalletScreen.kt:386`) shows one in full, `WavelengthEntryScreen`
(`:432`) pushes it below the expanded breakpoint, and a list-detail pane shows it above. That wallet has
one source of truth — the SDK's own payment list — so an entry is a fact and a detail screen is a
layout job.

The group's wallet has no such list. Its activity is three lists from three sources with three different
standings, and money coming in is in none of them:

| on the screen now | where it comes from | how far it can be believed |
|---|---|---|
| `PayoutRow` (`GroupWavelengthWalletScreen.kt:637`) | a signed authorisation this device holds, with a status looked up in the operator's snapshot by payment hash (`:640`) | the approval is the group's own signature; the status is the operator's word |
| `OnchainPayoutRow` (`:663`) | the approval, plus this device's own `GroupArkLeave` (`GroupArkCoSigning.kt:83`) | both are this device's own record |
| `ActivityRow` (`:684`) | `GroupWavelengthSnapshot.Line` (`GroupWavelengthSnapshot.kt:28`) | the operator's word, start to finish |
| nothing | deposits: `GroupArkDepositAddress` (`:129`), `GroupArkBoarded` (`:145`), `GroupArkClaim` (`:149`), the `GroupArkVtxo` each became (`:23`), `DepositRow` | mostly this device's own record |
| nothing | what the wallet costs: `GroupArkFee` (`:95`), `GroupArkRound` (`:99`) | this device's own record of what it agreed to pay |
| nothing | leaving: `GroupWavelengthExitEvent.Exit` | the group's own signature |

So the work is not a screen. It is a read model — one entry type both the list and the detail use, built
from this device's records first and the operator's claim second, each field carrying where it came
from. `GroupWavelengthLedger` (`GroupWavelengthLedger.kt`) is the precedent: a pure `of(rows)` that a
test can drive with no database and no daemon.

## What exists, and what is missing

From reading the code at `9f99e06b`.

| a detail screen needs | what exists | what is missing |
|---|---|---|
| one entry type | nothing | `GroupWavelengthEntry` and `GroupWavelengthHistory.of(…)`, beside the ledger |
| a stable id per entry | the onchain payout's `id`, a snapshot `Line.id`, a `requestId`, an outpoint, a commitment | nothing ties them together, and two could collide (T4) |
| **when each thing happened** | `GroupArkDepositAddress.requestedAt`, `GroupArkFee.atEpochSeconds`, `GroupArkSnapshotRow.sentAt`, the operator's `Line.at` | **six tables carry no time at all:** `GroupArkVtxo`, `GroupArkBoarded`, `GroupArkClaim`, `GroupArkPayment`, `GroupArkLeave`, `GroupArkRound`. A history sorted by time can't be built from this device's own records (T3) |
| the rows, in a flow | `observeVtxos` (`GroupArkDao.kt:95`), `observeLeaves` (`:168`), `observeDepositAddresses` (`:62`), `observeSnapshot` (`:83`) | `GroupArkBoarded`, `GroupArkClaim`, `GroupArkPayment`, `GroupArkFee`, `GroupArkRound` are suspend-only reads, some of them per-commitment (`boardedIn`, `:68`), and none is on `GroupWavelengthRepository` |
| the repository | 31 methods, and a `NO_OP` every screen test leans on (`GroupWavelengthRepository.kt:110`) | the new reads, in the interface, in `DatabaseGroupWavelengthRepository`, and in `NO_OP` |
| a route | `GroupWavelengthWalletRoute`, `…AdvancedRoute`, `…ReceiveRoute`, `…ExitRoute` | `GroupWavelengthEntryRoute`, and its `composable<…>` in `DedunNavHost.kt` |
| the detail itself | `WavelengthEntryDetail` and its `private fun DetailLine` (`WavelengthWalletScreen.kt:421`) | both are private to that file, so nothing can reuse them |
| a bottom sheet | the shared `ModalBottomSheet` (`widgets/dialogs/BottomSheetDialog.kt:45`), used by three screens, with three more reaching for material3's directly | nothing for a transaction |
| four states | `ErrorState`, `EmptyState`, `LoadingDataIndicator`, `ScreenStateTransition` | the activity list's empty case is a bare `Text` today (`GroupWavelengthWalletScreen.kt:471`) |
| a name for a member | `state.localChatRoom.localParticipants` lookups, three copies of them on the wallet screen | one helper, so the detail doesn't make a fourth |
| the strings | the personal wallet's 20-odd `wavelength_entry_*` (`strings.xml:1092`) | the group's, in sentence case, as format arguments |

## Decisions

**T1. One history, not three lists.** The *Payouts* and *Activity* sections (`GroupWavelengthWalletScreen.kt:463`
to `:473`) become one *History*, newest first, each row opening its entry. A row's words are the ones
already there — *Approved. Waiting for whoever runs the wallet*, *In a round. Paid once it confirms*,
*Didn't go: …* — so nobody loses a sentence they already understand; what changes is that one list holds
money in as well as money out, and that a row leads somewhere. The existing screen tests move onto the
merged list rather than being deleted.

**T2. An entry is built from this device's record, and the operator's claim is joined onto it — never
the other way round.** `GroupWavelengthHistory.of` walks the device's own rows first and produces an
entry for each. A snapshot line that joins to one (by payment hash, by outpoint, by commitment) fills in
*status*, *fee* and *when* where the device has none, and is marked as having done so. A snapshot line
that joins to nothing is still an entry — a member has to be able to see what the operator says happened
— but it is marked `THE_OPERATOR` and says so on its face, in the same voice the balance already uses
for `CANNOT_CONFIRM` and `DIFFERS` (`GroupWavelengthReports.Agreement`). An entry the device knows and
the operator contradicts is `MISMATCH`, and the device's figure is the headline.

This is U2 applied to a row instead of a balance, and it is the whole security value of the screen: a
member who can see *the operator says this happened and my device never signed it* can act on it.

**T3. Six tables get a nullable `atEpochSeconds`, and schema 44.** `GroupArkVtxo`, `GroupArkBoarded`,
`GroupArkClaim`, `GroupArkPayment`, `GroupArkLeave` and `GroupArkRound` each gain
`atEpochSeconds: Long? = null`, written where the row is written, meaning *when this device first filed
it*. Nullable, so `AutoMigration(from = 43, to = 44)` is all it takes and every existing row keeps its
silence. An entry with no time of its own falls back to the operator's `Line.at`, marked as that; an
entry with neither sorts last and says *when isn't known*. The alternative — taking the operator's `at`
as the time and sorting by it — puts a hostile operator in charge of the order a member reads their own
wallet's history in, and is refused.

Only one time per row, not two. *When this device saw it settle* would be a second column on
`GroupArkVtxo` and is not worth a column: the entry's *finished* comes from the round's or transfer's own
entry.

**T4. An entry's id is `<kind>:<key>`, and is never trusted for anything but lookup.** `deposit:<requestId>`,
`payout-ln:<payment hash>`, `payout-onchain:<the approval's d>`, `exit:<the approval's d>`,
`coin:<outpoint>`, `round:<commitment>`, `operator:<the line's id>`. The kind prefix is there because a
snapshot `Line.id` is a string the operator chose: without it, an operator could name a line so that it
collides with an entry the device knows, and a member tapping their own payout would read the operator's
story instead. The id goes into a route and into `rememberSaveable`, so it has to survive process death;
it is matched against the built list and never parsed back into a key to query with.

**T5. The detail screen says general information, and the sheet says everything.** The screen: what it
was, the signed amount big, status, amount, fee, whose it is, when it started and finished, where it
went in a form a person reads (a member's name, a shortened address, an invoice's description), and one
line naming where the figures come from. Nothing else. The sheet: every field, grouped, each long value
copyable. This is the split the request asks for and the one Phoenix's own payment screen uses, and it is
also the honest split here, because almost everything technical about this wallet is either *a thing
this device signed* or *a thing the operator says*, and a sheet can put those under two headings where a
summary cannot.

**T6. The sheet is called *Technical details*, not *Advanced*.** The word *Advanced* is taken: it is the
wallet's own screen (`GroupWavelengthAdvancedScreen`, "the one screen that says VTXO") and the personal
wallet's (`WavelengthAdvancedScreen`). A second *Advanced* one tap away, meaning this transaction rather
than this wallet, is two different things with one name on one screen. The request's word was *advanced*;
this is a one-line change if the owner prefers it (Q1).

**T7. A pushed screen at every width, not a third pane.** The wallet screen already uses both panes above
`Breakpoint.Expanded`: money on the left, the group on the right
(`GroupWavelengthWalletScreen.kt:425`). A selected entry has nowhere to go but over the group pane, which
would hide who runs the wallet, how many signers have been seen and the daemon's state while someone
reads about a payment — and those are the things that explain why a payment is stuck. So
`GroupWavelengthEntryRoute` is pushed at every width, with `NavigateBackButton`, and the detail itself
uses `readableContent()`. The personal wallet's pane split is right there because its right pane is
otherwise empty. The appendix records what was rejected.

**T8. `DetailLine` and the member-name lookup move out of the files that own them.** `DetailLine`
(`WavelengthWalletScreen.kt:421`) becomes a shared widget; the `localParticipants.firstOrNull { … }
?.profile?.humanReadableNameOrPubkey() ?: key.take(16)` idiom, which the group wallet screen already
writes four times (`:639`, `:665`, `:716`, `:742`), becomes one function. Both wallets then draw a detail line
the same way, which is the point of doing it now rather than after a second copy exists.

**T9. Nothing on the detail screen does anything.** No retry, no cancel, no re-propose, no *pay this
again*. A payout that failed needs a new invoice and a new approval, which is the payouts screen's job;
an approval that ran out is gone. A detail screen that offered an action would be offering it on the
operator's word, since that is where a failure is reported from. The screen reads, and says what the
member can do in words.

**T10. Fees and renewals are entries.** A round the device agreed to pay for (`GroupArkFee`,
`GroupArkRound`) is money that left and a member's balance shrank for it, so it belongs in the history,
not only in the Advanced screen's fee budget line. One entry per round, amount zero, fee what this
device agreed to, and the detail names the term and the budget it counted against. Phoenix does not show
channel upkeep as a payment; this wallet has to, because here the group is charged for it and nobody told
them (D16's "nobody pays alone" is a rule members have never seen the working of).

**T11. A boarding address is not an entry; an invoice nobody paid is.** An address is one member's for
good and can be paid any number of times, so a row for one would sit in the history for ever saying
nothing, and the receive screen already lists addresses. An invoice is asked for once, for an amount, and
either gets paid or doesn't — so one nobody has paid is a *waiting* row, as it is in the profile's own
wallet (`isUnpaidRequest`, `WavelengthWalletScreen.kt:360`). A request the operator hasn't answered yet is
no row either: there is nothing to pay.

**T12. The operator's own lines are grouped apart on the screen, not interleaved.** A line carries no
outpoint (`GroupWavelengthSnapshot.Line`), and the operator's activity is the SDK's own entry list, whose
kinds are `send`, `receive`, `deposit` and `exit` — so a boarding deposit *does* get a line, and nothing
can join it to the deposit this device recorded. Interleaved, one deposit would read as two transactions.
So the history shows this device's own entries, and the operator's unjoined lines under their own heading
after them. On a device that holds no records of its own that heading is the whole history, which is
Phase 6's subject. Joining by amount was considered and rejected: it would hide the disagreement the row
exists to show. The fix that would work is an outpoint on a line, which is a wire change this plan does
not make.

## What Phase 1 found

Written on 7 November 2026, building `GroupWavelengthHistory` against the code rather than the reading.

| found | what it was | what it changed |
|---|---|---|
| **a deposit's rows are deleted when it lands** | `roundHappened` files a `DepositRow` and then `forgetBoarded`; `claimSettled` files one and then `forgetClaim` (`GroupWavelengthDeposits.kt:165`, `:188`). So this device's lasting record of a finished deposit is the deposit ledger's, not the Ark tables' | the entry's id is the deposit key itself, `deposit:<txid>:<vout>` or `deposit:ln:<hash>`, which is the same string in all three states. `GroupArkBoarded.outpoint` and `DepositKey.Onchain.text` are both `txid:vout`, so the id survives the handover with nothing to translate |
| **`DepositRow` already carries times** | `seenAt` and `confirmedAt`, and `DepositDao.observeInRoom` already sorts by `seenAt` | **T3 shrinks.** Deposits need no new column. With `GroupArkFee.atEpochSeconds`, the onchain payout's `askedAt` and the exit's `askedAt`, the only times still missing are a Lightning payout's approval and both payouts' completion |
| a round that boarded a deposit loses the row that spoke for it | once the boarded row is forgotten, nothing claims the round, so it stands on its own | kept, deliberately: the group paid that round's fee and a fee nobody can see is what T10 exists to stop. It is `Kind.ROUND` and the screen's word for it is neutral, not *renewal* |
| a transfer costs a fee and files no round | `GroupArkRound` is written only where the effect has a `noteHash` (`GroupArkCoSigner.kt:631`), so a Lightning transfer's fee row has no round beside it | `ROUND` entries are built from the union of rounds and fees, and `Detail.Round.round` is nullable. Every fee the device agreed to is now in the history exactly once |
| an invoice that doesn't decode has no hash | two such approvals would have shared the id `payout-ln:` | the invoice stands in for the hash, since it is what the group signed |
| a Lightning payout has two different fees | the operator's line reports the routing fee; the fee row reports what the group paid for the Ark transfer | the entry's `feeSat` is the routing fee, which is what the member was told the payment would cost. The transfer's own fee stays on the fee row for the sheet to show (Phase 5) |
| a payout fixture can't be built on signet | `Bolt11Invoice.create(Chain.Signet, …)` throws *unknown chain hash* — the other half of the gap `GroupWavelengthPaymentEvent.readInvoice` exists for, which reads signet's prefix by hand because `Bolt11Invoice` doesn't know it | the test's invoices are regtest's, whose prefix both halves know. Eight tests failed on this and none of them on the model |

## The screens

### The history, on the wallet screen

```
History
  ┌───────────────────────────────────────────────────┐
  │ ↓  Added 50,000 sats                              │
  │    Settled · 14:02 2 Nov 2026                     │
  ├───────────────────────────────────────────────────┤
  │ ↑  12,000 sats to Thandi                   Fee 41 │
  │    Paid · 11:30 1 Nov 2026                        │
  ├───────────────────────────────────────────────────┤
  │ ↑  3,000 sats onchain to Sipho                    │
  │    In a round. Paid once it confirms               │
  ├───────────────────────────────────────────────────┤
  │ ⟳  Renewal                                 Fee 30 │
  │    Done · 09:14 28 Oct 2026                       │
  ├───────────────────────────────────────────────────┤
  │ !  Payment, 8,000 sats                            │
  │    Only whoever runs the wallet says this happened │
  └───────────────────────────────────────────────────┘
```

The last row is T2's whole purpose. It is not an error state and not an alarm; it is a line a member can
read and ask about.

### The detail

```
← Transaction

   12,000 sats to Thandi
   −12,000 sats

   Status      Paid
   Amount      12,000 sats
   Fee         41 sats
   Whose       Thandi's payout
   Approved    11:28 1 Nov 2026
   Finished    11:30 1 Nov 2026
   Paid to     Thandi's Lightning address

   Your device signed this payment. Whether it arrived is
   what whoever runs the wallet says.

   [ Technical details ]
```

### The sheet

```
   ──────────
   Technical details

   What your device signed
     Ark transaction        a3f1…9c2b          [copy]
     What the vHTLC held    12,000 sats
     Refund script          5120…               [copy]
     Checkpoints signed     3
     Term                   3

   What the group approved
     Approval               payout-ln:…         [copy]
     Invoice                lnbc120u1p…         [copy]
     Payment hash           7b1e…04af           [copy]
     Most it may cost       200 sats
     Refund by block        912,440
     Approval runs out      18:00 8 Nov 2026
     Lightning address      thandi@example.com

   What whoever runs the wallet says
     Their line             complete
     Their fee              41 sats
     When                   11:30 1 Nov 2026
     Said at                11:31 1 Nov 2026, tip 912,118

   Still untested
     A funded vHTLC the swap server won't refund comes
     home onchain, which has only met fixtures.
```

A field this device doesn't hold is left out, not shown empty. The *Still untested* block is per kind and
comes straight from the existing Advanced screen's `group_wavelength_advanced_untested_*` strings — a
member reading the full detail of a Lightning payout is exactly who should be told its refund path has
never run.

### What each kind shows

| kind | summary | the sheet adds |
|---|---|---|
| deposit, boarding | added, amount, settled or waiting, whose | the address and that this device recomputed it, the outpoint, the commitment, the round, the sweep height and blocks left |
| deposit, Lightning | added, amount, settled or waiting, whose | the invoice, its amount, the payment hash, the claim's Ark transaction, what the claim kept — and that nothing can prove where the invoice paid (spike 6), and that a claimed coin's deadline is the operator's word |
| payout, Lightning | amount, to whom, status, fee | the Ark transaction, the vHTLC's amount and script, the refund if one came home, the approval, the invoice, the hash, the fee cap, the refund height, the LNURL metadata |
| payout, onchain | amount, to whom, status, fee | the approval, the address and that it decodes on this chain, the leave's script, the commitment, the fee the round cost, whether it confirmed |
| renewal or round | nothing left, fee | the commitment, the term, what this device agreed to, the budget and the window it counted against, how many coins moved |
| leaving | amount, where it went | the approval, the onchain wallet it paid, the commitment |
| the operator's word alone | what the operator calls it, its amount, its status | the line verbatim, its id, its failure text, the snapshot's term, `sentAt` and `tip`, and that this device has no record of it |

## Phases

One commit per phase, each compiling and passing
`./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest` and `./gradlew :composeApp:m3Audit`.
Messages end `(Group Wavelength transaction details, Phase N)`. Every string added goes in
`composeApp/src/commonMain/composeResources/values/strings.xml`, in sentence case, with interpolation as
a format argument, in the same phase that uses it — the audit's budgets are zero and stay zero.

### Phase 1: the entry and the history, pure

No UI, no database change, nothing on a screen. `GroupWavelengthHistory.kt`, beside
`GroupWavelengthLedger.kt` in `wallet/wavelength/group/`.

- `GroupWavelengthEntry(id, kind, amountSat, feeSat, direction, status, at, member, source, …)` with a
  per-kind payload holding the raw rows the sheet reads, and `Kind`, `Status`, `Direction` and `Source`
  (`THIS_DEVICE`, `THE_OPERATOR`, `BOTH`, `MISMATCH`) enums.
- `GroupWavelengthHistory.of(coins, boarded, claims, payments, leaves, fees, rounds, addresses,
  approvals, onchainApprovals, exits, snapshot)` → `List<GroupWavelengthEntry>`, newest first, ids as T4.
- **Tests,** `GroupWavelengthHistoryJvmTest`: one entry per kind from its rows alone; a snapshot line
  that joins by payment hash fills a status and marks `BOTH`; one that joins to nothing is its own
  `THE_OPERATOR` entry; one that contradicts a device record is `MISMATCH` with the device's amount as
  the headline; an operator line whose `id` is `payout-ln:<a hash the device knows>` does **not** collide
  with the device's entry (T4); a boarding deposit through all four of its states; a Lightning payout
  with a refund; entries with no time sort last and keep their order; `MAX_ACTIVITY`'s 50-line cap drops
  no entry the device knows of; an empty everything is an empty list, not a list of zeros.

**Done when** the tests pass and nothing outside the new file and its test has changed.

### Phase 2: when each thing happened, and the reads to find it

Schema 44 and the plumbing. Still nothing on a screen. **Smaller than planned, because Phase 1 found
that the deposit ledger already carries times** (see above): six nullable columns across five tables,
not one per table of six.

- `atEpochSeconds: Long? = null` on `GroupArkBoarded`, `GroupArkClaim`, `GroupArkPayment`,
  `GroupArkLeave` and `GroupArkRound`, each meaning *when this device filed this row*, and
  `confirmedAtEpochSeconds` beside it on `GroupArkLeave` — the one moment a payout finished that nobody
  has to be believed about. All set in `GroupArkCoSigner.remember`, from **one** `Clock` read, so
  everything a single signature files reads in the order it happened; `confirmLeaves` takes the time as
  an argument. Nothing in the co-signer reads any of them.
  `GroupArkVtxo` gets none: a coin's settle time is not the `at` of any entry, since a deposit's comes
  from the deposit ledger and a round's from its fee row.
- `AutoMigration(from = 43, to = 44)` in `DedunDatabase.kt`, with a comment in the house form, and the
  exported `composeApp/schemas/…/44.json`. No manual migration and so no migration test: nullable
  columns are a shape Room migrates itself, and an older row's null is *this device doesn't know when*,
  which the history shows rather than guessing at.
- `GroupArkDao`: `boarded(wallet)`, `claims(wallet)` and `allFees(wallet)`. The reads already there are
  each shaped for the co-signer's own question — one commitment's boarding coins, one term's fees — and
  a history asks a different one.
- `GroupWavelengthPayouts.signedAt`: when the group signed each Lightning payout, by payment hash, from
  the signed event's own `createdAt`. An approval carries no time of its own, unlike an onchain payout's
  `askedAt`, so **this is the one time a Lightning payout has that isn't the operator's** — and it needs
  no wire change, which is why the alternative of adding `asked_at` to kind 30379 was dropped.
- `GroupWavelengthRepository.historyRows(chatRoomId, chain, wallet)`: one suspend read returning
  everything `of` takes. **Read, not observed** — the plan called for `observeHistoryRows`, but the view
  model already re-reads its facts whenever the coins or the operator's report change and on a timer
  (`refreshFacts`), and every one of these tables is written in the same transaction as one of those. A
  second combine of nine flows would be machinery for nothing. In the interface, in
  `DatabaseGroupWavelengthRepository`, and in `NO_OP`, which every screen test leans on.
- **Tests:** `GroupArkHistoryDaoJvmTest` for each new read and each new column, including that
  confirming one commitment's leaves doesn't touch another's; and in `GroupWavelengthHistoryJvmTest`,
  that each kind takes the soonest time it can know and that a row from before the columns existed still
  shows, with none.

**Done when** `jvmTest` passes and the exported schema is in the commit.

### Phase 3: one history on the wallet screen

- `entries: List<GroupWavelengthEntry>` **replaces** `payouts` and `onchainPayouts` on
  `GroupWavelengthWalletUIState.Opened`, rather than sitting beside them. Nothing outside this screen
  read either, and two sources of truth for one list is the thing this plan is about. Filled in
  `refreshFacts`, from the rows it now reads and the report the state holds.
- `moneyItems` loses its *Payouts* and *Activity* sections and gains *History*: `EmptyState` with a
  required message when there is nothing, and one `HistoryRow` per entry,
  `Modifier.clickable { … }.minimumInteractiveComponentSize()`, an `Icon` with a decided description
  (`Decorative`, since the headline says the direction in words), and the fee trailing as `ActivityRow`
  already did.
- `PayoutRow`, `OnchainPayoutRow` and `ActivityRow` collapse into the one row, keeping every sentence
  they say today, read from the entry instead of from three different shapes. The amount stays in the
  headline, where the group's rows already put it, rather than moving to the trailing slot as the
  profile wallet's does.
- The operator's own lines sit under their own heading, after the device's (T12).
- `memberName` comes out of this screen, which wrote the same lookup four times (T8), and the retired
  strings — *Activity*, *Nothing yet*, *Payouts* — come out of the catalogue with the lists they named.
- **Tests,** in `GroupWavelengthWalletScreenJvmTest` and `GroupWavelengthOnchainPayoutsJvmTest`: the
  existing payout and activity assertions, now against the merged list and built from the same rows a
  device holds rather than from a row made by hand; a deposit appears, which nothing asserted before; an
  operator-only line is under its own heading; a row opens the entry with the id it was built with; an
  empty history says so in words rather than *Nothing yet* under a heading.

**Done when** no assertion that used to hold has been deleted to make this pass. One changed shape, not
substance: a row's supporting line is now *status · when*, one `Text` as the profile wallet's
`entrySubtitle` is, so the assertions that named a status exactly now name it as a substring of the same
line.

### Phase 4: the detail screen

- `GroupWavelengthEntryRoute(activeUserPublicKey, chatRoomId, chain, entryId)` and its `composable<…>`
  in `DedunNavHost.kt`, beside the other three Wavelength routes.
- `GroupWavelengthEntryScreen`: `Scaffold`, `TopAppBar` with `NavigateBackButton`,
  `ScreenStateTransition`, and the four states — `LoadingDataIndicator`, `ErrorState(onRetry = null)`,
  `EmptyState` for an id the history doesn't hold, and the detail.
- `GroupWavelengthEntryDetail(entry, localChatRoom, reader)`: T5's summary, `readableContent()`, built
  from the shared `DetailLine` and `memberName`.
- **`DetailLine` comes out of the profile wallet's screen with a `copyable` flag** rather than an
  `onCopy` callback: it does the clipboard and the snackbar itself, which is three identical lambdas
  fewer there, and makes the value selectable as well — a hash read off a screen is no use to anybody.
- **`entryHeadline`, `entryStatus` and `entryIcon` come out of the wallet screen too**, into
  `widgets/wallet/GroupWavelengthEntryText.kt`, so the row in the history and the screen about one entry
  can never say it differently. That was not in the plan; written twice, they would have drifted.
- The one line naming where the figures come from, per `Source`, and the per-kind warning of T5 — which
  is the spike-6 invoice note for a Lightning deposit and the untested-refund note for a Lightning
  payout, both taken from the strings the receive and Advanced screens already use.
- **A round's figure is what it cost**, not its amount, and its detail has no *Amount* line: nothing
  moved in or out of the wallet, so an amount would have been *0 sats* said twice on one screen. A test
  caught that, which is what T10's claim — that the group is charged and nobody told them — asks for.
- An onchain payout is the one kind whose destination a device can name exactly (W2), so it is the one
  kind whose detail shows the address. A boarding deposit says only that the address was checked, because
  the address itself is gone once the round has happened (Phase 1's first finding).
- **Tests,** `GroupWavelengthEntryScreenJvmTest`: each kind's summary; a deposit nobody attributed; an
  onchain payout's address; the two warnings; an operator-only entry's line about whose word it is; a row
  with no time saying so; an id the history doesn't hold showing the empty state rather than an error;
  and the same detail in a wide window, since it is pushed rather than a pane (T7).

**Done when** every kind the history can build has a summary, and no kind falls through to a blank
screen.

### Phase 5: the technical sheet

- `GroupWavelengthEntryTechnicalSheet(entry, onDismiss)`, on the shared `ModalBottomSheet`
  (`BottomSheetDialog.kt:45`), sections as the sketch above, long values in a `SelectionContainer` with
  a copy button that notifies through `rememberNotifier` — the copied string read above the handler, as
  CLAUDE.md requires.
- A `TextButton` on the detail opens it; it is the only button on the screen (T9).
- The *Still untested* block per kind, from the existing strings.
- **Tests:** the sheet opens and dismisses; per kind, every field this device holds is displayed and a
  field it doesn't hold is absent rather than empty; an operator-only entry's sheet has the operator's
  section and says the device has no record; the sheet scrolls at phone height with the longest kind in
  it, which is the Lightning payout.

**Done when** nothing in the sheet is a `.dp` literal, a hardcoded colour or a title-cased string, and
`m3Audit` still reports every budget met.

### Phase 6: the device that can't confirm

The honesty pass, and the one most likely to be got wrong. Its own phase because it is the security
model, not a polish item.

- A restored phone has no `GroupArkVtxo` rows at all, so its history is the operator's claim end to end.
  Every entry is `THE_OPERATOR`; the list says so once at its head, not five times; the balance already
  says `CANNOT_CONFIRM` and the two must agree in voice.
- A member who holds no share (`isParticipant == false`) is in the same position and always was.
- An entry for a coin from a Lightning claim shows no deadline of this device's own, because none can be
  worked out — the known gap the UI note records. The sheet says that where it would have shown one.
- An earlier wallet's entries (`state.earlier`) are out of scope and the history says which wallet it is
  for only when there is more than one.
- **Tests:** the whole history with an empty ledger and a full snapshot; a half-restored device, where
  some entries are `BOTH` and some `THE_OPERATOR`; a snapshot that contradicts two entries; a Lightning
  claim's entry with no deadline.

**Done when** no screen in this plan can show a figure as this device's own that came from the operator,
and a test says so for each kind.

### Phase 7: the notes

- `group-wavelength-ui.md` links this note where it lists what the screens do, and its "Where the build
  stands" table gains a row.
- This note's own header says what was built, what was found and what is still open, in the form the
  other Wavelength notes use.
- `material-design-conformance.md` needs nothing unless a budget moved.

**Done when** someone reading `group-wavelength-ui.md` finds this without being told it exists.

## Order

1 → 2 → 3 → 4 → 5, in that order: each one is unusable without the one before. 6 can be written
alongside 4 and 5 and has to land before either is called done, because it is what stops them lying. 7
is last.

Phases 1 and 2 are the bulk of the work and neither shows anything, which is worth knowing before
starting: a reviewer looking for a screen at the end of Phase 2 will find none.

Nothing here blocks on the real round on phones (the UI note's Phase 4), on the unilateral exit, or on a
funded wallet. Everything can be built and tested against fixtures, and **everything it displays is
untested against a real wallet's history until a real wallet has one.** Phases 1 and 6's fixtures are
written from the shapes the signet harness produced, not from a mainnet round, and that is a known
limit, not a gap to close here.

## Risks

- **The operator's snapshot holds 50 lines** (`GroupWavelengthSnapshot.MAX_ACTIVITY`). An entry the
  device knows will outlive the operator's line about it, and its status will then be the last one seen
  and never change again. The history has to keep the device's entry and say the status is as last
  reported, rather than quietly reverting it to *approved*.
- **A hostile operator chooses its own line ids and times.** T4's prefix and T3's refusal to sort by the
  operator's clock are the two defences, and both need the tests named in Phase 1 or they are only
  intentions.
- **Six nullable columns in one schema version.** Room migrates them itself, but the exported schema has
  to be regenerated in the same commit or the build fails on the next one, and the plan's own phase
  boundary is where that gets forgotten.
- **A detail screen invites the wrong question.** A member reading *50,000 sats added* for their own
  deposit will take it as their share, which it is not: fees shrink everyone's in proportion (D16), and
  `GroupWavelengthPayouts.balancesIn` is the only answer to *what can I take out*. The deposit entry's
  summary has to say *your deposit*, never *your balance*, and the renewal entries of T10 are what make
  the shrinking visible.
- **Merging the lists touches rows a member already reads.** Every sentence is preserved by T1, but the
  order changes and a payout no longer sits under its own heading. If that turns out to be worse, the
  fix is a filter on the history rather than three lists again.
- **Scope.** The sheet's field list is long and will grow as the wallet does. Each kind's sheet is one
  composable reading one payload, so a new field is one line; a sheet that starts reading from several
  places is the sign this has gone wrong.

## Questions, for the owner

1. **The button's word.** T6 says *Technical details*, because *Advanced* already means the wallet's own
   screen. The request said *advanced*. One string either way.
2. **Renewals in the history.** T10 puts them in, on the grounds that members are charged for them and
   have never seen it. They will outnumber everything else on a wallet that renews every few days. In,
   out, or folded into one row per term?
3. **An earlier wallet's history.** Phase 6 leaves it out. A group that replaced a wallet keeps coins in
   the old one until they leave, and those movements are real. Worth a second history, or not worth it?
4. **Proof of payment.** The group's wallet never holds a preimage: a payout's proof, if it exists, is in
   the operator's daemon. Does the sheet say *no proof of payment held*, or leave the row out? The
   personal wallet shows one (`wavelength_preimage`, *Proof of payment*), so a member moving between the
   two will notice.
5. **Who may read a detail.** Today every member of the room sees the whole activity list, including
   another member's payouts and deposits. A detail screen makes that much more legible — a name, an
   address, an amount, a time. That is the same information, better presented, and it may still be the
   wrong default for a group that isn't four friends.

## What this plan does not do

- **It changes nothing a device signs.** No `ArkPolicy` rule, no refusal, no event's wire shape, no new
  kind, no new tag. No phase is marked *policy*, and if one needs to be, it stops first.
- It asks the operator for nothing new. No field is added to a snapshot, and no request is made to build
  a history: everything comes from rows this device already files and claims it already receives.
- It adds no action. Nothing on these screens retries, cancels, re-proposes, sweeps or exits (T9).
- It does not touch the personal Wavelength wallet's own detail screen, beyond sharing two widgets out of
  it (T8).
- No fiat, no exchange rate, no notes or labels a member can write on a transaction. The app has none of
  these anywhere.
- It does not show the group's Lightning wallet's payments, the onchain wallet's transactions, or a
  member's guarded wallet. Each has its own screen and its own trust model, and one history across all of
  them would have to reconcile three.
- It does not pretend to a history on a restored device. Phase 6 is the shape of that admission.

## Appendix: what was considered and rejected

**The detail replaces the group pane in a wide window.** Rejected in T7. The group pane holds who runs
the wallet, how many signers have been seen lately and what the daemon is doing — which is the
explanation for most of what a member would open a detail to ask about. Hiding it to show a payment
trades the answer for the question. A third pane was also considered: `listPaneWidthFor` gives one width,
`Panes.kt:26`, and a three-pane directive would be new machinery for one screen.

**Reading the history from the operator's snapshot alone.** It is one list, already sorted, already
statused, and it would have made this a two-day job. It would also have put every word on the screen in
the operator's mouth, on a wallet whose entire design is that a device believes what it signed. The UI
note's U2 exists for exactly this temptation at the balance; this is the same temptation one level down.

**A `GroupWavelengthEntry` table.** Writing entries to the database as they are learned, rather than
building them on read, would give a stable id, a real timestamp and a cheap query. It would also be a
second record of things the device already records, which can then disagree with the first — and the
first is what the co-signer refuses on. `GroupWavelengthLedger` builds on read for the same reason.

**Sorting by the operator's `Line.at`.** Rejected in T3: the order a member reads their own history in
would be the operator's to choose.
