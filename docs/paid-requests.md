# A request that has been paid

An invoice or an address this app has handed out stops being shown as a QR code
once money has arrived against it, and the place it was says what arrived
instead. Before this, three screens showed a paid request for as long as they
were open, and four more quietly swapped the address for the next one and said
nothing at all. The survey below is that *before* picture, and the phases are
kept as they were written.

Read [group-wallet-addresses.md](./group-wallet-addresses.md) first. Its rule --
*a used address can be copied but never shown as a QR code* -- is already built,
for one screen's bottom sheet, and this note is that rule spread to the other six
surfaces and across to Lightning. [member-deposits.md](./member-deposits.md) says
why each of these requests belongs to one member, which is what makes "paid"
answerable per request rather than per wallet.

**Built, phases 0-7**, one commit per phase. Written against the code as it stood
at `a17dbd26`, and merged with `dedun` after Phase 6. Phase 6 is this note; Phase 7,
expiry, was out of scope as first asked and was built when it was.

The plan was written with `experimental/wavelength-kmp` uninitialised in this
worktree, which is why Phase 2 opened with a spike. Initialising it answered both
of the spike's questions and changed Phase 2's design; the phases are kept as
written, and the table below says where the build chose differently.

| what the plan said | what it turned out to be |
|---|---|
| the worktree builds | it did not: both submodules were uninitialised, so no Gradle task could even configure, and `git submodule update` was refused by `protocol.file.allow`. Initialising them with `-c protocol.file.allow=always` and writing a `local.properties` in each build root was the first work of Phase 0 |
| Phase 2's spike: `counterparty` is the candidate field for a deposit's address | `EntryRequest.onchainAddress` is the field. But neither it nor `lightningInvoice` is used in the end: `Receive` and `Deposit` each hand back the entry they created, so the entry's **id** is the match. `Entry.request` is documented as nil when the backing subsystem persisted none, and the SDK's own fake daemon never sets it at all -- a screen matching on it would take its QR code down never, or at the daemon's discretion |
| Phase 2's spike: is there a phase between waiting and completion? | `PAYMENT_DETECTED`, and `WAITING_FOR_CONFIRMATION` for its onchain twin. There is also an `UNSPECIFIED` that `isUnpaidRequest` left out, which labelled a request the daemon had said nothing about as *Pending* |
| `isUnpaidRequest` moves to the model layer so both read one predicate | it stays where it is, with `UNSPECIFIED` added, and the receive screen states paid the other way round -- from the phases that say a payment happened. Negating a list of waiting phases claims a payment for a failed receive, a refund, and any status a newer daemon invents |
| `GroupLightningReceiveUIState.Invoice` gains `paymentHash` and `amountMsat` | it gains neither: the view model holds the invoice row, and the amount comes from the payment rather than from the invoice. `Paid` carries the operator's name instead, which the plan did not foresee needing |
| `PaymentReceived(amount, note, onTheWordOf, action)` | that, and it prints nothing for a zero amount -- a deposit's amount is zero until the chain says otherwise, and a zero is as uninformative as no figure. `note` is a parameter the plan's signature left out |
| the group Wavelength invoice falls back to the operator's snapshot | only on `status == "complete"`. A `"pending"` line exists from the moment the invoice is made, so pending can never mean paid |
| the group Wavelength address falls back to the operator's snapshot too | it cannot: the snapshot's activity lines carry a payment hash and nothing that names an address. That rail is this device's own `GroupArkBoarded` rows alone, so the panel comes when the group signs for the coin rather than at first sight of it in the mempool -- and those rows are deleted when the round confirms, so it is the window between the two |
| the cap is recomputed on every emission | the limits are; the `AtTheCap` gate is left one-shot, so a member whose deposit filled the cap sees the payment rather than the cap |
| each of the three rotating view models grows a `justPaid` | one `PaidAddressWatch`, because all three turned out to have the same shape -- a current address and a list with a `USED` status -- with `GroupWalletState` serving two of them |
| `DepositCard` takes the just-paid address | it takes the amount. `GroupWalletDerivation.Derived` needs real keys and a tweak cache, which is an expensive fixture for a card that only prints a figure |
| expiry is a separate state beside paid | a separate *panel*, not a separate state. Expiry is a pure function of the invoice and the clock, so no UI state carries it and the three screens work it out as they draw; the phase's only view-model change is `askAgain` accepting `Invoice` so the panel's button can return the form |
| `LightningInvoiceCard` already decodes `isExpired()`, so the predicate exists | it exists for a chat bubble, which is drawn once and never again. A receive screen is open *while* the window closes, so a predicate was not enough: it had to become a moment and a timer, or an invoice would expire with its QR code still up until something unrelated recomposed |
| (not foreseen) | lightning-kmp has no signet prefix, so `lntbs` matches testnet's `lntb` and then fails on the amount. An invoice that will not read has no expiry and keeps its QR code -- the safe way round -- and the wallets this is wired into are on mainnet by decision |
| the per-phase tests are enough to say a phase is done | they are not. Every phase passed its own tests and the full suite still broke, two classes away: these screens now collect flows that never end -- the operator's payments, the engine's snapshot, six combined device-local flows -- and a `viewModelScope` cannot be cancelled from outside, so each test leaked a coroutine onto the test dispatcher and the next class to call `Dispatchers.setMain` failed with *Main is used concurrently with setting it*. A `ViewModelStore` per test class, cleared before `resetMain`, is the fix. Run the whole suite before believing a phase |
| Phase 5 is a source scan over `QRCodeView` call sites | a Compose UI test. `runDesktopComposeUiTest` is already in this tree, which the plan missed, and `QRCodeView` gives its image the request as a content description -- so *is this scannable?* is a question the semantics tree answers. A new rail that forgets the rule fails on what it draws rather than passing a scan of its imports |

Nothing here has been seen on a phone, or against a real payment. The full
`:composeApp:jvmTest` suite is green -- 2,318 tests, 7 skipped, which are the
regtest ones -- along with `:composeApp:compileKotlinJvm` and
`m3-audit.sh --check`. The regtest run named under Phase 1 has not been done.

The headline: **a payment request has three states and only the first of them is
a QR code -- waiting, paid, and no longer this screen's business. The signal for
the second one already exists on every rail; no screen reads it.** Four of the
seven surfaces already rotate to a fresh address when one is paid, so the work
there is an acknowledgement rather than a hiding; three keep the paid request on
screen, and those are the bug.

---

## The seven surfaces

| screen | what it hands out | when paid, today |
|---|---|---|
| [WavelengthReceiveScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/WavelengthReceiveScreen.kt) | the profile's own bolt11, and a boarding address | both stay, QR and all, until the amount field is edited or the screen is left |
| [GroupWavelengthReceiveScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/GroupWavelengthReceiveScreen.kt) | the group's boarding address for this member, and the newest invoice | both stay; `invoice = newest?.invoice` is the newest row whatever became of it |
| [GroupLightningReceiveScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/GroupLightningReceiveScreen.kt) | the operator's bolt11 | stays: `GroupLightningReceiveUIState.Invoice` is terminal and nothing moves it |
| [GroupSwapInScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/GroupSwapInScreen.kt) | `state.current`, the member's lowest unused swap-in address | rotates to the next unused one, silently; the paid one appears under *Used addresses*, copy only |
| [GroupWalletScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/GroupWalletScreen.kt) receive card | the member's current deposit address | rotates, silently |
| [MemberWalletScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/MemberWalletScreen.kt) | the wallet's receive address | rotates, silently |
| [GroupWalletAddressesScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/GroupWalletAddressesScreen.kt) sheet | one address by index | **correct already**: the sheet closes and the snackbar says *Address 3 was just paid* |

The last row is the model for all of this, and it is twelve lines of view model
([GroupWalletAddressesViewModel.kt:50](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/GroupWalletAddressesViewModel.kt)):

```kotlin
/**
 * Opens the sheet on the receive address at [index]. [onPaid] runs once if
 * that address is paid while the sheet is open, as the sheet closes: whoever
 * is scanning it then has already paid, and nobody else should.
 */
fun show(index: Long, onPaid: () -> Unit) { … }
```

*…and nobody else should* is the whole argument. A QR code left on screen after
payment is an invitation to pay twice -- and on Lightning a bolt11 paid twice is
the second payer's money gone, not a second credit.

## What "paid" can be known from, rail by rail

Every rail already has the signal. None of it is new work; it is all reachable
from where the screen already stands.

**Group Lightning.** `GroupLightningInvoiceRow.paymentHash` is documented as
"what joins a payment to it", and `observePayments(chatRoomId, chain, wallet)`
emits `GroupLightningPaymentRow`s carrying `incoming`, `succeeded`, `amountMsat`
and the same `paymentHash`. Paid is one `first { it.incoming && it.succeeded &&
it.paymentHash == hash }`. The screen's view model already holds the wallet it
would ask about (`form.wallet.statement.wallet`) and already collects a flow for
60 seconds waiting for the invoice itself.

**Personal Wavelength.** `WavelengthWalletScreen.kt:360` already names the
predicate, for the activity list:

```kotlin
/** An invoice or address nobody has paid yet, which is no money on its way. */
private val Entry.isUnpaidRequest: Boolean
    get() = status == EntryStatus.PENDING && (kind == EntryKind.RECEIVE || kind == EntryKind.DEPOSIT) &&
        (progress?.phase == null || progress?.phase == EntryPhase.REQUEST_CREATED || progress?.phase == EntryPhase.WAITING_FOR_PAYMENT)
```

Its negation, for the entry that *is* this request, is paid. `WalletEngine`'s
`snapshot` is a flow and `WavelengthReceiveViewModel` already reaches into
`snapshot.value.activity` to reconcile a timed-out receive against
`request.lightningInvoice`. Matching a boarding address to its `DEPOSIT` entry is
the one unknown, and is Phase 2's spike.

**Group Wavelength.** Two joins, both on this device's own records rather than
the operator's word, which is this wallet's standing rule:

- a boarding address is paid when a `GroupArkBoarded` row exists whose `script`
  equals the `GroupArkDepositAddress.script` the screen is showing -- both hex,
  both written by this device after it checked the round;
- an invoice is paid when `GroupArkClaim` or `GroupArkPayment` holds its
  `paymentHash`, which `GroupArkDepositAddress.paymentHash` already carries.

Where this device co-signed neither -- possible, since a depositor need not have
answered the round that took its own money in -- the operator's snapshot activity
has a `paymentHash` too, and `GroupWavelengthWalletScreen.kt:638` already joins on
it. That fallback is the operator's claim and D4 says how it has to read.

**Onchain, all four surfaces.** `GroupAddress.Status.USED` -- "paid at least
once, whether or not the coins have moved since" -- and `SwapInAddress`'s
equivalent. Both come off the mini-wallet's `WalletState`, which these screens
already collect; `UnusedAddressCheck.Usage.Used` is the same question asked of a
bare address, "confirmed or in the mempool".

---

## Decisions

**D1 — Paid means seen, not final.** The request stops being a QR code at first
sight of money: a mempool transaction, an in-flight HTLC, a `PENDING` entry past
`WAITING_FOR_PAYMENT`. Not at confirmation, not at settlement. The reason is D1's
only reason: the payer has already paid, so the code has already done its job,
and every second it stays up is a second somebody can pay it again. Confirmation
depth belongs to the wallet screen, which already says it, and the paid panel
says plainly that the money is still arriving.

**D2 — The request is replaced, not hidden.** Removing the QR and leaving a gap
reads as a bug. The paid panel takes the same place in the same column: an icon,
the amount, a line saying what happened, and the way to ask for another request.
This is also M3's own structure guidance, the same one `ScreenState.kt` cites --
say what to do next, not only what happened.

**D3 — One shared composable, next to the other three screen states.** `ErrorState`,
`EmptyState` and `LoadingDataIndicator` are shared because sixteen hand-rolled
copies of one of them was how this app got sixteen dead ends. A fourth state --
*this worked* -- earns the same treatment before the first of seven screens writes
its own. It goes in `widgets/PaymentReceived.kt` beside `ScreenState.kt`, not in
`ScreenState.kt` itself: the other three are a screen's whole body and this one is
a panel inside a column.

**D4 — The operator's word reads as the operator's word.** On both group wallets
a payment can be known two ways, and they are not equal. Where this device's own
records say paid, the panel says it flatly. Where only the operator's snapshot
says it, the line names the operator -- *the operator says this was paid* -- and
the QR still goes, because an operator claiming a payment that never happened
gains nothing by it and a member paying twice loses. `GroupWavelengthReceiveScreen`
already opens its Lightning tab with "the one place in this wallet a member takes
the operator's word for something, so it says so first"; this is the second
sentence of that paragraph, not a new policy.

**D5 — The graphic is an icon, an amount and motion from the scheme.** No
confetti, no Lottie, no new dependency. `Icons.Default.CheckCircle` on a
`tertiaryContainer`/`onTertiaryContainer` pair -- a pairing the theme has already
measured in all six schemes, which a success-green literal would not be -- and
the amount at `displaySmall`, as `WavelengthEntryDetail` already sets an amount.
It arrives through `MaterialTheme.motionScheme.defaultEffectsSpec<Float>()` and
`defaultSpatialSpec<IntOffset>()`, and under `MaterialTheme.reducedMotion` it
appears without the movement. The icon is `Decorative`: the amount beside it is
the announcement.

**D6 — A paid request stays readable, never scannable.** Copy stays. The text of
the invoice or address stays, in its `SelectionContainer`, so a member can check
what they handed out against what a payer says they paid. Only `QRCodeView` goes.
This is exactly the distinction `UsedAddressRow` already draws -- "a paid address:
there to check and copy, never to be shown as a QR code again".

**D7 — A rotating address acknowledges, a one-shot request waits.** The three
silent onchain surfaces hand out the *next* address immediately and correctly;
their bug is that nobody is told. There the panel appears above the card, for one
sighting, and the card below it already shows the fresh address. On the three
one-shot surfaces the panel takes the QR's place and stays until the member asks
for another. The distinction is whether the screen has something else to hand out.

**D8 — One sighting, not a stored flag.** Nothing is written to the database.
The panel is a view-model transition: it appears when a request the screen is
showing turns paid under it, and it is gone on the next visit, where the wallet
screen's history is the record. A paid request found *already* paid when the
screen opens is never shown as a QR either, but gets no panel -- that is old news,
not an event.

---

## Phases

One commit each. Every phase ends with the three checks at the bottom of this
note; the phases that touch strings also end with `StringCatalogueJvmTest`.

### Phase 0 — The shared panel, and the words

`widgets/PaymentReceived.kt`, called by nothing yet.

```kotlin
@Composable
fun PaymentReceived(
    amount: Satoshi?,
    modifier: Modifier = Modifier,
    /** Null where this device knows the payment itself; the operator's name where only it says so (D4). */
    onTheWordOf: String? = null,
    action: (@Composable () -> Unit)? = null,
)
```

`amount` is nullable because an address paid and spent before the screen could
attribute a coin has no amount to name (`GroupAddress.balance` is "zero for an
address never paid, and for one paid and spent"), and *Payment received* with no
figure is honest where a zero would not be.

Strings into `values/strings.xml`, sentence case:

| key | text |
|---|---|
| `payment_received` | Payment received |
| `the_operator_says_a_payment_arrived` | %1$s says a payment arrived |
| `ask_for_another_invoice` | Ask for another invoice |
| `ask_for_another_address` | Ask for another address |
| `this_invoice_was_paid_and_should_not_be_paid_again` | This invoice was paid. Paying it again would send money nobody can credit. |
| `this_address_was_paid_and_the_next_one_is_below` | This address was paid. The one below it is fresh. |

`received_s_sats` ("Received %1$s sats") already exists at line 616 and is the
amount line; `paid_s_sats` and `address_s_was_just_paid` stay where they are.

*Tests:* none of its own -- there is no Compose UI test harness in this tree, and
inventing one for a panel is not this plan's job. The guard that it is used
correctly is Phase 5.

### Phase 1 — Group Lightning

The cleanest join, so it goes first and sets the shape the other two follow.

- `GroupLightningReceiveUIState.Invoice` gains `paymentHash: String?` and
  `amountMsat: Long?` from the row the view model already has in hand, and a
  sibling state `Paid(bolt11, amount: Satoshi?)` is added.
- `GroupLightningReceiveViewModel.request` keeps its `withTimeoutOrNull` as it is,
  and on reaching `Invoice` launches a second collection:
  `lightningRepository.observePayments(chatRoomId, chain, wallet).first { rows -> rows.any { it.incoming && it.succeeded && it.paymentHash.equals(hash, ignoreCase = true) } }`,
  then sets `Paid`. A null `paymentHash` -- rows from before deposits were
  attributed -- never matches, and the screen behaves as it does today.
- The screen's `Invoice` branch loses nothing; a `Paid` branch beside it draws
  `PaymentReceived` with *Ask for another invoice* wired to `viewModel.initiate()`,
  which already returns the form, and keeps the bolt11 text and its copy button
  under the panel (D6).

A `Paid` state is a screen state, so it goes inside the existing
`ScreenStateTransition` and arrives as a transition rather than a cut.

*Tests*, `GroupLightningReceiveViewModelJvmTest` (new, on the pattern of
`WavelengthReceiveViewModelJvmTest`'s fakes): a matching incoming payment moves
`Invoice` to `Paid`; an outgoing one with the same hash does not; a failed one
does not; a payment on a different hash does not; and *ask for another* lands
back on `Form`.

*Live check:* this is also the one phase the regtest harness can prove end to end,
since it already drives a real payment into the group's node --
`DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'`.
No node, co-signing or sweep code changes here, so the harness is a check rather
than a requirement.

### Phase 2 — Personal Wavelength

**The spike ran first and changed this phase.** Initialising
`experimental/wavelength-kmp` made the `Entry`, `EntryProgress` and `EntryRequest`
models readable, and they answered both questions:

1. a `DEPOSIT` entry's address is `EntryRequest.onchainAddress`, not
   `counterparty` -- which `WavelengthEntryDetail` renders as *To* and is the
   wrong end of a payment coming in. But the field is moot: `Receive` and
   `Deposit` each return the entry they created alongside the request, so the
   **entry's id** is an exact match, and `EntryRequest` is documented as nil when
   the backing subsystem persisted none. Both halves of this phase ship.
2. `EntryPhase` has `PAYMENT_DETECTED` between waiting and settling, and
   `WAITING_FOR_CONFIRMATION` for an onchain payment seen but unconfirmed, so
   money seen is nameable. It also has `UNSPECIFIED`, meaning "no lifecycle
   hint", which `isUnpaidRequest` did not list -- so a request the daemon had said
   nothing about read as *Pending* rather than *Waiting for payment*.

Then:

- `WavelengthReceiveUIState` gains `invoicePaid: Satoshi?` and `addressPaid:
  Satoshi?`. Not booleans: the amount is what the panel says, and on Lightning it
  is the entry's `amountSat` rather than the typed figure, which can differ by the
  operator's floor on a credit receive.
- `WavelengthReceiveViewModel` grows one collector over `open.engine.snapshot`,
  started when a request is first shown and cancelled when it is replaced. It
  looks for the entry whose `request.lightningInvoice` equals `uiState.invoice`
  (the same match `reconcile` already makes) and sets `invoicePaid` when that entry
  stops being an unpaid request. `isUnpaidRequest` moves out of
  `WavelengthWalletScreen.kt` into the model layer so both read one predicate --
  it is already written as an `Entry` extension, so this is a file move.
- `LightningTab` and `OnchainTab` each put `PaymentReceived` where `RequestCode`
  was, keeping the text and copy. *Make an invoice* becomes *Ask for another
  invoice* while a paid one is on screen; the onchain tab's button returns.
- `onAmountChange` clears `invoicePaid` along with `invoice`, which it already
  clears.

*Tests*, into `WavelengthReceiveViewModelJvmTest`: `FakeFacadeBindings` already
backs `makeInvoice`, so drive an activity entry for the issued invoice from
pending-waiting to complete and assert `invoicePaid`; assert an entry for a
*different* invoice leaves it null; assert editing the amount clears it.

### Phase 3 — Group Wavelength

Both rails, on this device's own records first and the operator's second (D4).

- `GroupWavelengthReceiveUIState.Loaded` gains `addressPaid: Satoshi?`,
  `invoicePaid: Satoshi?` and `paidOnTheOperatorsWord: Boolean`.
- `GroupWavelengthRepository` gains two observers beside `observeCoins`:
  `observeBoarded(chatRoomId, chain, wallet): Flow<List<GroupArkBoarded>>` and
  `observeClaims(chatRoomId, chain, wallet): Flow<List<GroupArkClaim>>`, both over
  queries `GroupArkDao` already has in non-flow form (lines 51 and 66), plus
  `NO_OP` overrides returning `flowOf(emptyList())`.
- `GroupWavelengthReceiveViewModel.initiate` already collects
  `observeDepositAddresses`; it becomes a `combine` of that with the two new flows
  and the snapshot it can already reach. The address row's `script` joins to
  `GroupArkBoarded.script`; its `paymentHash` joins to `GroupArkClaim.paymentHash`
  and `GroupArkPayment.paymentHash`; where neither hits, the operator's snapshot
  activity is joined on `paymentHash` as `GroupWavelengthWalletScreen` already
  does, and sets `paidOnTheOperatorsWord`.
- `onchainItems` and `lightningItems` each drop their `QRCodeView` item for a
  `PaymentReceived` item when their rail is paid, keeping the `Copyable` beneath
  it. The Lightning tab's *Ask for another* button is already there, as
  `group_wavelength_invoice_ask_another`.

The cap matters here and is easy to get wrong: a paid invoice has consumed room
under `roomLeftSat`, so *ask for another* has to recompute `maxInvoiceSat` from
the ledger rather than reuse the figure the screen was drawn with. The ledger flow
is already in `initiate`; it needs to stay in the `combine` rather than being read
once with `.first()` as it is today.

*Tests*, `GroupWavelengthReceiveViewModelJvmTest` (new): a boarded row on the
shown address's script sets `addressPaid`; a boarded row on another script does
not; a claim on the invoice's hash sets `invoicePaid` with
`paidOnTheOperatorsWord` false; the operator's snapshot alone sets it with the
flag true; and the fake repository's `NO_OP` flows leave today's behaviour intact.

### Phase 4 — The three silent rotations

No QR is hidden here; the money is acknowledged (D7).

- `GroupSwapInUIState.Loaded` gains `justPaid: SwapInAddress?`, set when an
  address that was `current` on a previous emission appears in `used` on this one.
  The panel goes in the `"receive"` item above the address column.
- The same shape for `GroupWalletScreen`'s receive card, whose state already
  carries the member's addresses, and for `MemberWalletScreen`, whose
  `walletState.receiveAddress` moving while its old value has a new utxo is the
  same test.
- All three say the amount from the arriving coin -- `state.coins` and
  `walletState.utxos` both carry it -- and *The one below it is fresh*.

`GroupWalletAddressesScreen` is untouched: it is already right, and Phase 5's
guard has to pass for it unchanged, which is the check that the guard is written
against behaviour and not against these three diffs.

*Tests:* `GroupSwapInViewModel`'s existing fake chain drives the first; the other
two get the same two-emission test. A payment to an address that was never
`current` -- another member's, or one handed out two rotations ago -- sets nothing.

### Phase 5 — The guard

A source-scanning test on the pattern of
[OnlyAttributedAddressesJvmTest.kt](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/ui/composable/OnlyAttributedAddressesJvmTest.kt),
which already does exactly this job for a different rule: walk
`ui/composable/**.kt`, and for every `QRCodeView(` call assert the enclosing
composable is reachable only from a state or branch this test knows to be an
unpaid request. The honest version of that is narrow: assert that no file holding
a `QRCodeView(` call also holds a `Paid`/`paid` state it does not branch on, and
list the seven call sites explicitly with the state each is guarded by, so adding
an eighth fails until the list is updated.

`ShareProfileScreen`'s QR is a profile, not a request, and is exempt by name --
as `MemberWalletScreen` is exempt in the test this is modelled on.

### Phase 6 — This note, and the table

Add the *what the plan said / what it turned out to be* table, as
`group-wallet-addresses.md` and the rest carry one, and a row in
[README.md](./README.md)'s table. Phase 2's spike answers go in the body, not the
table: the next person reading this needs them before the phases, not after.

### Phase 7 — Expiry

A bolt11 that expired unpaid is as unscannable as one that was paid, for the same
reason -- nobody can pay it now -- and all three one-shot screens showed it anyway.

- `InvoiceExpiry.expiresAt(bolt11)` reads the moment off the invoice: its timestamp
  plus its expiry tag, or BOLT 11's own hour where it carries none. Null for
  anything that will not read, which keeps the QR code: the cost of being wrong
  that way is an invoice shown a little too long, and the cost of being wrong the
  other way is hiding a code somebody was about to pay.
- `rememberInvoiceExpired(bolt11)` turns the moment into state with one timer per
  invoice rather than a poll.
- `PaymentReceived` and a new `InvoiceExpired` share a private `RequestPanel`, the
  way `ErrorState` and `EmptyState` share `StateMessage`. `PaymentReceived.kt`
  becomes `RequestState.kt` for the pair; the symbols are unchanged, so no call
  site moved.
- Expired is neutral, not the error pair: an invoice lapsing is what invoices do,
  the member has lost nothing, and the alarm the error colour carries belongs to a
  payment that failed.
- Every call site checks paid before expired, because a payment that landed in the
  last second is still a payment.

**No UI state carries expiry.** It is a pure function of the invoice and the clock,
so it is worked out in the composable; the phase's only view-model change is
`askAgain` accepting `Invoice`, so the expired panel's button can return the form.

---

## What this does not do

- **No notification.** A payment arriving while the app is backgrounded is not
  this note's business; the panel is for a member watching the screen they handed
  out.
- **No stored paid flag and no new table.** D8. The wallet screens are the record,
  and all three already list a received payment.
- **No change to any confirmation or settlement rule.** D1 moves the QR at first
  sight; what the wallet counts as spendable is untouched, and the panel says the
  money is still arriving rather than implying it has landed.
- **No change to `GroupWalletAddressesScreen`.** It is the precedent, not a
  subject.
- **Nothing on the payer's side.** `LightningInvoiceCard`'s three-second
  `Success` in a chat bubble is a different screen with a different question.

## Checking

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileKotlinJvm :composeApp:jvmTest
```

`compileKotlinJvm` rather than `compileDebugKotlinAndroid`, which this worktree
cannot run: the Android build needs the Wavelength bindings staged under
`experimental/wavelength-kmp/bindings/android`, and a fresh worktree has none.
`settings.gradle.kts` warns rather than fails, and the desktop target and
`jvmTest` go on without them, which is what every phase here was checked with.
A change to this work that touches the Android target needs
`experimental/wavelength-kmp/scripts/fetch-bindings.sh android` first.

The audit's budgets are the ones that bite here: the panel's spacing comes from
`MaterialTheme.spacing`, its colours from a theme pair rather than a success
green, its icon has a decided `contentDescription`, and its strings are sentence
case in `strings.xml`. All four are budgeted at zero and `--check` fails on any
of them.

Phase 1 can additionally be proved against a real payment:

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```
