# A request that has been paid

An invoice or an address this app has handed out stops being shown as a QR code
once money has arrived against it, and the place it was says what arrived
instead. Today three screens show a paid request for as long as they are open,
and four more quietly swap the address for the next one and say nothing at all.

Read [group-wallet-addresses.md](./group-wallet-addresses.md) first. Its rule --
*a used address can be copied but never shown as a QR code* -- is already built,
for one screen's bottom sheet, and this note is that rule spread to the other six
surfaces and across to Lightning. [member-deposits.md](./member-deposits.md) says
why each of these requests belongs to one member, which is what makes "paid"
answerable per request rather than per wallet.

**Planned, not built.** Written against the code at `a17dbd26`, with
`experimental/wavelength-kmp` uninitialised in this worktree -- which is why
Phase 2 opens with a spike instead of a change: the `Entry` model it has to match
against can be read here only through its call sites.

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

**First, a spike, before any change.** Initialise `experimental/wavelength-kmp`
and read the `Entry`, `EntryProgress` and request models. Two questions, and the
rest of the phase is written against the answers:

1. what field of a `DEPOSIT` entry carries the boarding address -- `counterparty`
   is the candidate, since `WavelengthEntryDetail` shows it under *To*, but an
   entry for money coming *in* naming the payer there would make it the wrong
   field;
2. whether `EntryPhase` has a phase between `WAITING_FOR_PAYMENT` and completion
   that `isUnpaidRequest` is already reading correctly, or whether a receive jumps
   from waiting to `COMPLETE`.

If (1) has no answer, the Lightning half of this phase ships alone and the
boarding address keeps its QR, with a line in this note's outcome table saying so.
That is a real possible outcome and not a reason to hold the phase.

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

### Phase 7 — Expiry, if it is wanted

Out of scope as asked, in scope as a bug: a bolt11 that expired unpaid is as
unscannable as one that was paid, and all three one-shot screens show it anyway.
`LightningInvoiceCard` already decodes `Bolt11Invoice.isExpired()` for the chat
bubble, so the predicate exists. It is a separate state from paid -- *this invoice
expired*, with *ask for another* -- and a separate commit, and it is listed here
only so that the next person does not have to rediscover that the two states share
every line of plumbing Phases 0 through 3 lay down.

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
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

The audit's budgets are the ones that bite here: the panel's spacing comes from
`MaterialTheme.spacing`, its colours from a theme pair rather than a success
green, its icon has a decided `contentDescription`, and its strings are sentence
case in `strings.xml`. All four are budgeted at zero and `--check` fails on any
of them.

Phase 1 can additionally be proved against a real payment:

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```
