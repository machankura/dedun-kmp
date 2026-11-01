# The group wallet's addresses

A screen that lists the group wallet's receive addresses: every one that has been
paid, the one the wallet is handing out now, and the next two after it. It is
modelled on Phoenix's *Swap-in addresses* screen, and this note says where it
follows that screen and the three places it does not.

Read [group-chain-wallet.md](./group-chain-wallet.md) first, Phases 4 and 5 in
particular: this list is a view over the same `ElectrumMiniWallet` state that
picks the receive address, and it adds nothing to what the wallet watches.

**Since [member-deposits.md](./member-deposits.md)**, the receive branch this note
lists is legacy. No screen hands out an address on it any more: each member is
handed one on their own deposit branch, and the addresses screen lists the
profile's own, then other members' used ones, then the receive branch's used ones,
copy only.

**Built**, phases 1-5, one commit per phase. Written against the code as it stood
at `707e1465`, with the `lightning-kmp-app` submodule at `e0d2b82`.
`GroupWalletState.receiveAddresses` and `changeAddresses` are the entry points,
and `GroupWalletAddressesScreen` is the screen they feed. The phases are kept as
written; where the build chose differently the table below says so.

| what the plan said | what it turned out to be |
|---|---|
| the explainer ends *share one of the next two* | *share one of the ones after it*, so the sentence does not have to change if `ADDRESSES_AHEAD` does |
| `FakeElectrum` gains a `spend` and a history | and moves out of `GroupOnchainWalletJvmTest` into a file of its own, `FakeElectrum.kt`, so the view model's tests can drive a chain too |
| rows keyed by index | keyed by branch and index: Phase 4's change section starts at index 0 too, and a `LazyColumn` with two rows under one key throws |
| `receiveAddresses` is one function over the receive branch | one private `listed(branch, state, current)` behind both it and Phase 4's `changeAddresses`; a branch with no current index lists only its used addresses |
| an unused row is clickable | and says what a click does: `onClickLabel` is *Show as a QR code*, so a screen reader announces it rather than an unlabelled action |
| the sheet is the `BottomSheetDialog` widget | the `ModalBottomSheet` wrapper that `BottomSheetDialog.kt` declares, as `ChatRoomDetailScreen` calls it |
| the sheet closes when its address's status is anything other than current or next | when it is `USED`, which is the same thing: those are the three there are |
| one view-model test for the sheet | two, the second that a payment to *another* address leaves the sheet open: paying the current address can turn the shown one from next into current, and that is not a reason to close it |
| the copy button's description is *Copy address %1$s* with the index | it is, and a receive row and a change row with the same index therefore share it. The section heading between them is what tells them apart to a screen reader |

A real wallet has not been looked at through this screen yet. Every phase was
checked with `:composeApp:jvmTest`, `:composeApp:compileDebugKotlinAndroid` and
`m3-audit.sh --check`, and the list's rules are tested against the fake chain,
but no device has drawn it over an Electrum server.

The headline: **the list is indices `0` through `max(current + 2, highest used)`,
newest first, read straight off the receive mini-wallet's `WalletState`. Nothing
is stored and nothing new is subscribed to.** The two addresses after the current
one are there so that up to three members can each hand out a different address
on purpose. The `highest used` term exists because the wallet's lowest-unused rule
can put the current address *below* a paid one.

---

## What Phoenix's screen is, and what carries over

`SwapInAddresses.kt` and `SwapInAddressesViewModel.kt` in
`phoenix-android/.../settings/walletinfo/`. The view model collects
`swapInWallet.wallet.walletStateFlow`. On each emission it takes every
`AddressMeta.Derived` entry, sorts them by index with the newest first, and marks
the one that equals `firstUnusedDerivedAddress` as current. Each row has:

- a 3dp bar on the leading edge: primary for the current address, a border grey
  for a used one, and nothing for one not used yet;
- the derivation index, in monospace;
- the address, in monospace on one line with a middle ellipsis, bold if current;
- a copy button;
- and a tap on the whole row, which opens the address in a block explorer.

A legacy single address gets a section of its own underneath. While the list is
empty the screen shows *Synchronizing…*.

What carries over unchanged: the source of truth (the mini-wallet's state, nothing
stored), newest first, the current address marked, index and address on one row,
copy on every row, and a loading state until Electrum has answered.

What does not:

| Phoenix | here | why |
|---|---|---|
| every generated address, the whole look-ahead | `0 … max(current + 2, highest used)` | a swap-in wallet belongs to one person, so a look-ahead address is never handed out by anyone else. A group's look-ahead is 10 (`RECEIVE_LOOKAHEAD`), and seven unused addresses past the useful ones is a list that reads as "this wallet has seventeen addresses", when it really has the ones that were paid |
| tapping a row opens a block explorer | tapping a row opens the address's QR code, and only for an address not used yet | the app has no explorer helper, and handing every address of a group's wallet to a third party's server ties them to this member's IP. That is the leak the watch-only export already warns about, made without being asked. And the thing a member wants to do with a *next* address is show it to the payer |
| one section per address type | a receive section, then (Phase 4) a short section of used change addresses | the group wallet has one address type and two branches. Change is the group's money too, but it is never handed out, so it gets no current marker and no next addresses |

---

## The bound

`current` is `receive.firstUnusedDerivedAddress`'s index: the lowest index whose
Electrum status has always been null. `highest used` is the largest index whose
`AddressState.alreadyUsed` is true, or −1 if none is.

```
last = max(current + AHEAD, highestUsed)      AHEAD = 2
rows = 0 … last, newest first
```

**Why `+ 2`.** The address the wallet screen shows is computed on each device
separately, and every device computes the same one. Two members showing the
wallet to two payers in the same hour hand out the same address, and the group
reuses it. Offering `current + 1` and `current + 2` beside it gives a second and
third member something else to hand out. Both are already watched, because the
look-ahead keeps the wallet subscribed to `current … current + 9`, so a payment
to either is seen the moment it reaches the mempool. An ancestor funding a
guarded wallet starts at the top of that window, `current + 2`, and goes up, so
it stays out of the members' way -- [guarded-wallet-funding.md](./guarded-wallet-funding.md), D2.

**Why `highest used`.** `firstUnusedDerivedAddress` takes the *lowest* unused
index, so a payment above `current` does not move `current`. If `/0/3` is
current and a payer uses `/0/5`, current stays at 3 and `current + 2` is still 5,
so the paid address is listed. This screen never offers anything past
`current + 2`, so on its own it never needs the second term. But a watch-only
wallet importing the group's descriptors can hand out any address up to the
look-ahead. If it is paid at `/0/8` while `/0/3` is current, `current + 2` stops
at 5. A list of "every address that has been paid" that leaves out `/0/8` would
be false, so the bound reaches up to the highest used address too.

**Why `AHEAD` must stay below the look-ahead.** A row this screen offers has to be
one the wallet is subscribed to, or a payment to it is invisible until the
look-ahead catches up. `current + AHEAD ≤ current + RECEIVE_LOOKAHEAD - 1` holds
for anything up to 9. Phase 1 states it as a `require` next to the constant, so
anyone who raises one has to look at the other.

**Before Electrum has answered,** there is no list. This is the same rule
`receiveAddress` already follows: before the first answer every address reads as
unused, and a list showing `/0/0` as current to a group that has been paid there
fifty times would misstate the history. The list is null whenever
`receiveAddress` is.

---

## What "used" means, and what a row says

`AddressState.alreadyUsed` is true once Electrum's status for the script hash is
non-null, which means the address has *any* history, in a block or in the
mempool. It does not go back to false when the coins are spent. That is
the right meaning for this screen: a paid address whose coins have since moved
has still been used, and handing it out again is still reuse.

Each row can therefore say one of four things:

| status | condition | supporting text |
|---|---|---|
| current | index == `current` | *Being handed out now* |
| next | index > `current`, not used | *Not used yet* |
| used, holding coins | `alreadyUsed`, `utxos` not empty | *Paid · 12,000 sats* |
| used, empty | `alreadyUsed`, `utxos` empty | *Paid · spent since* |

An unused address *below* `current` cannot happen, because `current` is the
lowest unused index by definition. An unused address *between* `current` and
`highest used` can. It reads as *next*, which is true.

The amount is `AddressState.utxos`' sum, which already includes coins still in
the mempool. Whether they are confirmed is on the wallet screen's coin rows, and
is not repeated here.

---

## Phase 0 — before any code

**An hour, to settle three things the phases assume.**

1. **The bound is `max(current + 2, highest used)`, and `AHEAD` is a constant.**
   Not a setting: two is what the request asked for and is enough for three
   members at once. A setting nobody changes is a decision deferred to someone
   who has less context.
2. **No block explorer.** If one is wanted later, it goes behind its own
   decision about which explorer and what it learns. It should not ride in on a
   list screen.
3. **A used address offers copy but never a QR code.** Copy is for checking, for
   example pasting into a watch-only wallet or matching a payer's receipt. A
   QR code is for being paid, and a used address should not be.

---

## Phase 1 — `GroupWalletState.receiveAddresses`

**Half a day, most of it the test.**

In `composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/GroupOnchainWallet.kt`,
beside `receiveAddress`:

```kotlin
/** One receive address as the address list shows it. */
data class GroupAddress(
    val derivation: GroupWalletDerivation.Derived,
    val address: String,
    val status: Status,
    /** What it holds now: zero for an address never paid, and for one paid and spent. */
    val balance: Satoshi,
) {
    enum class Status { CURRENT, NEXT, USED }
}

/**
 * The receive addresses worth listing, newest first: every one paid, the one
 * being handed out, and [ADDRESSES_AHEAD] after it -- or null until Electrum
 * has said which are used, for the reason [receiveAddress] is.
 */
val receiveAddresses: List<GroupAddress>? by lazy { … }
```

and in `GroupOnchainWallet.Companion`:

```kotlin
/**
 * Unused receive addresses the address list offers past the current one, so
 * several members can each hand out a different one. Every one of them has to
 * be watched already, or a payment to it would go unseen.
 */
const val ADDRESSES_AHEAD: Int = 2

init { require(ADDRESSES_AHEAD < RECEIVE_LOOKAHEAD.toInt()) }
```

The body reads `receive.addresses`, keeps the `AddressMeta.Derived` entries,
computes `current` and `highestUsed`, then filters to the bound, maps each entry
through `GroupWalletDerivation.derive(account, RECEIVE, index)` and sorts
descending. It is a pure function of the state, `lazy` like `utxos`, because the
screen reads it on every recomposition.

A `Status.USED` with a zero balance is the *spent since* row. There is no fourth
enum value, because the balance already says it.

**The test fake needs one change first.** `FakeElectrum.status` in
`GroupOnchainWalletJvmTest` reports `"used"` only while a script has unspents.
Real Electrum reports a status for any history, so an address that has been spent
from reads as unused in the fake and as used on the chain. Phase 1 adds a
`spend(script)` to the fake that removes the unspents but keeps a `history`
entry, and makes `status` read the history. The existing four tests do not depend
on the old behaviour.

**Tests**, in `GroupOnchainWalletJvmTest`:

- a fresh wallet lists `/0/2`, `/0/1`, `/0/0`, with `/0/0` current and the
  other two next;
- paying `/0/0` lists `/0/3 … /0/0`: `/0/1` current, `/0/0` used with its amount;
- paying `/0/0` and then spending it leaves `/0/0` used with a zero balance, and
  current does not go back to 0;
- paying `/0/6` while `/0/0` is current lists `/0/6 … /0/0`, with `/0/0` current,
  `/0/6` used and `/0/1 … /0/5` next. This is the gap rule;
- every listed index is one the wallet has generated. The test checks this
  against `receive.addresses.keys` and fails if `ADDRESSES_AHEAD` ever outgrows
  the look-ahead;
- change addresses never appear, even when `/1/0` has been paid.

---

## Phase 2 — the screen

**A day and a half.**

- `GroupWalletAddressesRoute(activeUserPublicKey, chatRoomId)` in
  `ui/composable/navigation/routes/`, and a `composable<GroupWalletAddressesRoute>`
  in `DedunNavHost` beside `GroupWalletRoute`'s, with `onNavigateBack =
  { navController.popBackStack() }`. It is a pushed screen, so it gets
  `NavigateBackButton` in the leading slot of its `TopAppBar`.
- **The way in** is a `TextButton` at the bottom of the receive-address card in
  `OpenWallet` (`GroupWalletScreen.kt`), under *Copy address*: *All addresses*,
  with a `Decorative` list icon. It is in the card rather than in the app bar
  because it answers the question the card raises: *which address do I give?*
- **`GroupWalletAddressesViewModel`** reads the room the way
  `GroupWalletViewModel.initiate` does (`chatRepository.groupWallet(chatRoomId,
  chain)`), then collects `wallets.walletFor(wallet.account).state` and maps it
  to `receiveAddresses`. `GroupOnchainWallets` keeps one wallet per account for
  the identity's life, so this is the same instance the wallet screen is already
  watching: it adds no subscriptions and does not need a second Electrum answer.
  Its own `GroupWalletAddressesUIState` has `Loading`, `Error`, `CannotWatch`
  (no node, the same case as `this_profile_cannot_watch_the_chain`; both removed
  by [standalone-electrum.md](./standalone-electrum.md)) and
  `Loaded(addresses: List<GroupAddress>?)`, where a null list is the
  waiting-for-Electrum state.
- **Four states**, through `ScreenStateTransition` over the whole body:
  `LoadingDataIndicator` while the room loads and again (with
  *Finding the group's coins…*) while the list is null. `ErrorState` with
  `onRetry = null`, for the same reason the wallet screen gives. `EmptyState`
  for `CannotWatch`, with that screen's sentence. Then the list. A loaded list is
  never empty, because a fresh wallet has three rows.
- **The rows**, a `LazyColumn` with `readableContent()` on its root, keyed by
  index. Each row is a `ListItem`:
  - `leadingContent`: the index in `labelLarge`. The leading-edge bar from Phoenix
    is replaced by the M3 pairing: the current row's `ListItem` takes
    `colors = ListItemDefaults.colors(containerColor = secondaryContainer)`, and
    its text takes the matching `onSecondaryContainer`. A 3dp bar would be a
    dimension literal and a colour with no pairing; a container role is both
    already decided.
  - `headlineContent`: the address, `bodyMedium` in the monospace family
    already used for keys elsewhere, one line, `TextOverflow.MiddleEllipsis`.
    `DkgRitualScreen` and `KeyPackageManagementScreen` use the same overflow, so
    it is known to work on the pinned Compose 1.11.1.
  - `supportingContent`: the status sentence from the table above.
  - `trailingContent`: an `IconButton` with `ContentCopy`, whose
    `contentDescription` is *Copy address %1$s* with the index as the format
    argument. The icon carries the row's meaning and the index tells a screen
    reader which row it is, so it is not decorative.
- **Copy** writes the full address to the clipboard and reports *Address copied*
  through `rememberNotifier`, which is the string the wallet screen already uses,
  read above the handler.
- **Strings**, sentence case, in the group-wallet block of `strings.xml`:
  `all_addresses` (*All addresses*), `addresses` (*Addresses*, the title),
  `being_handed_out_now`, `not_used_yet`, `paid_s_sats` (*Paid · %1$s sats*),
  `paid_spent_since`, `copy_address_s`. Plus one line above the list,
  `addresses_explainer`: *Each payment should go to a new address. If someone
  else in the group is already sharing the current one, share one of the next
  two.* That sentence is the reason the next two rows exist, and a member who
  is not told it will only ever use the top one.

`./gradlew :composeApp:m3Audit`, then
`./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`, before the
commit. The audit counts this screen's spacing, colour, string, target and
description budgets like any other screen's.

---

## Phase 3 — handing out a next address

**A day.**

Tapping a *current* or *next* row opens a `ModalBottomSheet` holding the same
three things the wallet screen's receive card holds for its one address: a
`QRCodeView(bip21Uri(address))` (whose modules are already
`m3-color-exempt`), the full address in a `SelectionContainer`, and *Copy
address*. Its title is *Address %1$s*, with the index as the argument, so a member
reading it to a payer over the phone can say which one they gave. The sheet is
the existing `BottomSheetDialog` widget, as `ChatRoomDetailScreen` uses it.

A *used* row is not clickable. It has the copy button and nothing else, which is
Phase 0's third decision. The row stays a plain `ListItem` with no
`Modifier.clickable`, so it is not an unguarded target.

The sheet closes by itself when its address stops being unused, which means a
payment arrived while it was open. The view model already emits a new list,
the sheet's address is looked up in it by index, and a status other than
`CURRENT` or `NEXT` dismisses it with *Address %1$s was just paid*. The payer
scanning it at that moment has already paid, and nobody else should.

**Test**, in `jvmTest`, on the view model with the Phase 1 fake: open the sheet
on `/0/1`, pay `/0/1`, and assert the sheet state clears and the notifier was
called once.

---

## Phase 4 — used change addresses

**Half a day. Optional, and the first thing to drop if the rest runs long.**

A second section under the receive rows, *Change*, listing only the change
addresses with `alreadyUsed`. That means no current row and no next rows,
because change is chosen by a spend and never handed out. Each row has an
index, an address, *Paid · %1$s sats* or *Paid · spent since*, and copy. There is
no sheet and no QR code. The section is hidden when no change address has been
used, which is every wallet that has never sent.

It is worth having because "every address this wallet has been paid at" without
change is not the whole of that, and a member reconciling against a watch-only
import will see change addresses there. `GroupWalletState.changeAddresses` is
the same function as Phase 1's, over `change` and with the bound set to
`highestUsed`.

**Test**: after one spend with change to `/1/0`, the change section lists exactly
`/1/0`, and the receive section is unchanged.

---

## Phase 5 — the notes

**An hour.**

- This note's status line becomes **Built**, with a table of where the build
  chose differently, as every built plan here has.
- A row in [README.md](./README.md)'s table, and a sentence in its reading order
  that places it after the group-chain-wallet note.
- In [group-chain-wallet.md](./group-chain-wallet.md)'s Phase 4, the sentence
  "two members can show the same next address" gains a pointer here: that is the
  hazard this screen exists to reduce.

---

## What is left, when someone wants it

- **Saying which member has handed out which address.** It would make "share one
  of the next two" a choice the app makes rather than one the member makes. The
  app cannot know it without a group event per address handed out, which would
  put every receive address in the room's history before it is paid. That is a
  privacy cost for a coordination gain, and a decision on its own.
- **A label per address**, for example *Thandi's invoice*. It is local or
  group-shared, with the same split. Local is simple and is all Phoenix's swap
  list has (none). Group-shared is an event kind.
- **A block explorer link**, behind Phase 0's second decision.

---

## Appendix — what was considered and rejected

**Recording "shown" on this device.** "Used or shown" could mean every address
this device has ever displayed. It would need storage the wallet has
deliberately never had (*"nothing about the wallet is stored"*,
`GroupOnchainWallet`'s kdoc). It would differ from device to device, so two
members' lists would disagree about the same wallet. And it would still miss
the case that matters, which is the address another member showed. The chain is
the only record every device shares, and `current` is the address this device
is showing.

**Raising `RECEIVE_LOOKAHEAD` and listing all of it.** That is Phoenix's choice,
and it makes the list longer without making it more useful. Every extra
look-ahead address is one more Electrum subscription per group for every member,
and a member in twenty groups pays for all of them (group-chain-wallet.md,
Phase 4).

**A separate `ElectrumMiniWallet` for the list.** A second subscriber to the same
script hashes would answer the same question twice, and could briefly disagree
with the wallet screen about which address is current. `walletFor` returns the
existing instance, and that is the one to read.
