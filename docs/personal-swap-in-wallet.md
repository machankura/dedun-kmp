# The personal node's swap-in wallet

A screen that shows a profile's own Lightning node's swap-in wallet: the addresses
it hands out for onchain deposits, the coins that have arrived on them, and where
each coin is on its way into a channel. It also says why a coin that ought to have
moved has not.

**Planned.** Written against `d8f44154`, with the `lightning-kmp-app` submodule at
`67a0376` and its `lightning-kmp` at `688d36ad`.

The headline: **the swap-in already happens, and nobody can see it.** Every
profile with a seed runs a Phoenix node (`PhoenixBusiness`), and `BusinessManager`
calls `peer.startWatchSwapInWallet()` as soon as that node starts. From then on,
any coin on a swap-in address that reaches **3 confirmations** is spliced into
the node's channel, or opens one if there is none. It does not need a tap. What
the app lacks is everything around it: no screen shows the address, no screen
shows a coin waiting, and when the swap is refused, which the node does silently
for at least four reasons, nothing in the app says so. The plan is mostly about
showing what is already true, plus two things a user will need sooner or later:
raising the fee limit, and the refund.

This is the **personal** node only. The group's Lightning wallet never swaps in
(D1).

---

## Does the wallet swap in once a coin has enough confirmations?

Yes, with conditions. Here is the machine, read from lightning-kmp.

**The trigger.** `Peer.startWatchSwapInWallet` combines the swap-in wallet's
`walletStateFlow`, the chain tip, the funding feerate and the liquidity policy,
and sends a `SwapInCommand.TrySwapIn` on every emission
([Peer.kt:551](../lightning-kmp-app/experimental/lightning-kmp/modules/core/src/commonMain/kotlin/fr/acinq/lightning/io/Peer.kt)).
So it retries on every new block, every fee change, every wallet change and every
policy change. A changed fee limit is retried at once, without waiting for the
next block. The call is in all three `BusinessManager`s, just after `business.start`.

**The selection.** `SwapInManager.process` takes the wallet without the coins
already reserved for a funding attempt. It keeps the **deeply confirmed** ones,
meaning `minConfirmations ≤ confirmations < maxConfirmations`, and asks for a
channel with all of them at once
([SwapInManager.kt](../lightning-kmp-app/experimental/lightning-kmp/modules/core/src/commonMain/kotlin/fr/acinq/lightning/blockchain/electrum/SwapInManager.kt)).
The parameters are lightning-kmp's defaults, which both `PeerManager` and `Lsp.kt` pass unchanged:

| `DefaultSwapInParams` | blocks | about | what it means for a coin |
|---|---|---|---|
| `MinConfirmations` | 3 | 30 minutes | below this the coin is *weakly confirmed* and waits |
| `MaxConfirmations` | 17,280 | 4 months | from here on it can no longer be swapped |
| `RefundDelay` | 25,920 | 6 months | from here on the user alone can spend it |

Between 4 and 6 months the coin is locked: the LSP will not co-sign a swap, and
the refund path is not yet open.

**What happens to the selection** (`AddWalletInputsToChannel`, [Peer.kt:1470](../lightning-kmp-app/experimental/lightning-kmp/modules/core/src/commonMain/kotlin/fr/acinq/lightning/io/Peer.kt)):

| the node's channels | what it does | what it costs | when it gives up |
|---|---|---|---|
| a channel ready to splice, and connected | splice-in | mining fee only, no service fee | the liquidity policy rejects the fee → `LiquidityEvents.Rejected` |
| channels, but none ready (offline, being created, mid-splice) | nothing; unlocks the coins | - | silently; it retries on the next emission |
| none at all | opens a channel, buying 1 sat of inbound liquidity so the LSP is refunded its mining fees | mining fees plus the LSP's service fee for that purchase | no funding rate from the LSP (logged only); not enough left after fees (logged only); the policy rejects the fee → `LiquidityEvents.Rejected` |

**The conditions,** then:

1. **The app is running with this profile open.** The swap-in runs inside the
   node, and a profile switch stops the node of the profile being left
   ([multiple-profiles.md](./multiple-profiles.md)). A coin that matures while
   the app is closed is swapped the next time the profile is opened.
2. **The node is connected to the LSP.** Both branches need the LSP's `init`,
   and the funding rates are part of it.
3. **The fee is within the liquidity policy.** The default is
   `NodeParamsManager.defaultLiquidityPolicy`: at most **5,000 sat** and at most
   **50 %** of the amount. A small first deposit on a busy chain can exceed the
   5,000 sat when it has to open a channel. It is then refused every block until
   fees drop, and nothing in the app says so.
4. **The coin is swapped before 4 months.** After that it can only come back
   through the refund at 6 months, and the app has no screen for that.

`PeerManager.monitorNodeEvents` saves each `LiquidityEvents.Rejected` through
`NotificationsManager`, but nothing in `composeApp` reads it. Grep finds no reader
of `LiquidityEvents`, `SwapInEvents` or `notificationsManager`. The other refusals
are log lines only.

---

## Decisions

| | decision | why |
|---|---|---|
| D1 | **The personal node only. The group's node is never shown and never swaps in.** | [group-lightning-wallet.md](./group-lightning-wallet.md) D8. A swap-in address is a 2-of-2 between one user key and the LSP, and a group cannot be that one user key. The group node's `LocalKeyManager` is built with an *unused* swap-in xpub ([GroupLightningCoSigner.kt:69](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupLightningCoSigner.kt)). An address derived from it would be a 2-of-2 with a key nobody serves, so any coin sent to it could only come back through the 6-month refund. Its `Peer` still builds a `SwapInWallet`, but nothing must ever read that wallet's addresses. Phase 2 states this as a test |
| D2 | **Read the node, store nothing.** Addresses come from `peer.phoenixSwapInWallet.wallet.walletStateFlow`, and coins from `BalanceManager.swapInWallet` | the same rule as [group-wallet-addresses.md](./group-wallet-addresses.md). The mini-wallet is the source of truth, and `BalanceManager` already filters out the coins reserved for a funding attempt, so a coin being swapped is not counted twice |
| D3 | **The address list is the used ones plus the current one, with no addresses ahead.** | the group screen offers `current + 2` because three members may hand out addresses in the same hour. A swap-in wallet is one person's, so that reason is gone. The wallet rotates to the next address once the current one is paid. Phoenix shows the whole look-ahead, which reads as more addresses than the wallet has |
| D4 | **The current address is shown as a QR code; a used one can be copied, never shown as a QR code.** The legacy address is listed only if it has been paid | the group screen's reuse rule. The legacy address (`legacySwapInProtocol`) is from before taproot swap-ins and should never be handed out again |
| D5 | **Every coin gets a status, and a *ready* coin that has not moved gets a reason.** | condition 3 is the one a user will hit, and seeing *Waiting* forever is the bug report. The reason is the last `LiquidityEvents.Rejected` that covers the coin, or the node's connection state. Where the node gives no event, the status says it is waiting for the node, rather than guessing |
| D6 | **The fee limit is the user's to raise, per profile, for the next swap only, or for good** | it is already a user preference (`UserPrefs.saveLiquidityPolicy`), and `PeerManager.updatePeerLiquidityPolicy` changes it on the running node. Because the policy is part of the swap-in trigger, a raise is retried at once. Raising it *for the next swap only* is Phoenix's pattern, so a single expensive deposit does not leave the limit raised for good |
| D7 | **The refund is to an address the user enters, checked unused, after a confirmation that shows the fee.** It is built with `WalletState.spendExpiredSwapIn` | lightning-kmp already builds and signs it with the user's refund key ([ElectrumMiniWallet.kt:99](../lightning-kmp-app/experimental/lightning-kmp/modules/core/src/commonMain/kotlin/fr/acinq/lightning/blockchain/electrum/ElectrumMiniWallet.kt)). `UnusedAddressCheck` is the same check the payout destination uses |
| D8 | **Mainnet is what users get; tests run the pure parts on a fake chain.** | `NodeParamsManager.chain` is a constant `Chain.Mainnet`, and `Lsp.kt` knows only mainnet and testnet3, so the personal node cannot be pointed at the regtest harness without a change to the fork. Phase 0 decides whether that change is worth making |

---

## Phases

One commit per phase, each green on `:composeApp:jvmTest`,
`:composeApp:compileDebugKotlinAndroid` and `m3Audit`.

### Phase 0: spikes

Three questions the code cannot answer by reading.

1. **Does the personal node's swap-in wallet emit in this app as it does in
   Phoenix?** Start a mnemonic profile on mainnet and log
   `balanceManager.swapInWallet` and `swapInAddressFlow`. Confirm three things:
   the current address appears after Electrum answers, a profile switch cancels
   the collection, and the address survives a restart (it is derived, so it
   should).
2. **What does a first swap-in cost today?** Read `remoteFundingRates` from the
   LSP's `init` for a 1-sat purchase at the current feerate, and compute the fee
   for a channel-creating swap-in of 20,000, 50,000 and 100,000 sat. If the
   smallest of these is over 5,000 sat, D6 is a necessity for a first deposit,
   not an option, and the screen should say so before the user sends anything
   (Phase 3).
3. **Can the personal node run on regtest?** Making `NodeParamsManager.chain`
   a parameter, and teaching `Lsp.kt` a regtest LSP, is what Phase 7 needs.
   Measure what else assumes mainnet (`BlockchainExplorer`, the Tor onion URI,
   the stored wallet id), and decide whether Phase 7 is a fork change or is cut.

Done when the answers are written into this note under *Phase 0 outcomes*.

### Phase 1: the list and the statuses, pure

`wallet/SwapInWalletView.kt`, no Compose, no node.

- `swapInAddresses(state: WalletState, legacy: String): List<SwapInAddressRow>?`
  returns the used derived addresses and the current one, newest first, plus the
  legacy address if it is used. It returns null before Electrum has answered
  (D3, D4), the same rule as `GroupOnchainWallet.receiveAddresses`.
- `swapInCoins(wallet: WalletState.WalletWithConfirmations, reserved: Set<OutPoint>): List<SwapInCoin>`,
  where each coin has one status:

  | status | condition | supporting text |
  |---|---|---|
  | `Unconfirmed` | in the mempool | *Waiting for its first confirmation* |
  | `Confirming(n)` | `0 < confirmations < 3` | *%1$d of 3 confirmations* |
  | `Ready` | deeply confirmed, not reserved | *Ready to move into Lightning* |
  | `Swapping` | reserved by a funding attempt | *Moving into Lightning* |
  | `ExpiringSoon(blocks)` | ready, with `timeoutIn < 144 * 14` | *Must move within %1$s* |
  | `Locked(blocks)` | `lockedUntilRefund` | *Can be refunded in %1$s* |
  | `Refundable` | `readyForRefund` | *Can be refunded now* |

  A coin is `Swapping` from the reservation, not from `SwapInEvents.Requested`,
  because the reservation survives a restart (it is read back from the channels)
  and the event does not.

`SwapInWalletViewJvmTest` drives both functions over hand-built `WalletState`s.
It covers each status at its boundary block, the rotation of the current address
on payment, a used legacy address, and a null list before the first answer.

### Phase 2: the view model, over the running node

`ui/view/model/SwapInWalletViewModel.kt`.

- It takes an `Identity`. If `identity.business == null` (a bare nsec or an npub),
  there is no state, and the entry point in Phase 3 is not drawn.
- It collects `peerManager.getPeer().phoenixSwapInWallet.wallet.walletStateFlow`
  for addresses, and `balanceManager.swapInWallet` for coins. The two are combined
  with `peerManager.peerState`'s `connectionState`, which Phase 4 uses.
- Its UI state has the four states CLAUDE.md asks for: loading until the
  address list is non-null, error if the node fails to start, and loaded after
  that. The *empty* state applies when there are no coins:
  the address section is still drawn, because the empty case is exactly when the
  user wants the address.
- The test for D1: the view model is built only from a `PhoenixBusiness`, and
  nothing in `GroupLightningNode` exposes a `SwapInWallet`. A JVM test asserts
  that a `GroupLightningNode`'s public surface has no swap-in accessor, so
  wiring one in later fails a test rather than a review.

### Phase 3: the screen

`ui/composable/SwapInWalletScreen.kt`, the route `SwapInWalletRoute`, and a row on
`ActiveProfileScreen` (*Onchain deposits*), drawn only for a mnemonic profile.

- It is a pushed screen with `NavigateBackButton`, `readableContent()`, and
  `ScreenStateTransition` around the state `when`.
- **Receive** comes first. It shows the current address as a QR code, with copy
  and share, and one line of explanation: *Coins sent here move into your
  Lightning balance after 3 confirmations.* If Phase 0's second answer said a
  first deposit cannot fit the fee limit, a second line gives the smallest amount
  that will fit, computed from the funding rate. While the node has no channel,
  the line says that the first deposit opens one and shows the fee it will cost.
- **Coins** is next, one row per coin: amount, status, and the supporting text
  from Phase 1. A tap opens a sheet with the outpoint (copy only) and the
  confirmations. There is no block-explorer link, for the reason
  [group-wallet-addresses.md](./group-wallet-addresses.md) gives.
- **Addresses** is last: used ones, copy only, with the legacy address in a
  section of its own if it is used.
- All strings go in the catalogue, in sentence case. Every icon has a decided
  description, and every row has `minimumInteractiveComponentSize`.

View-model tests cover what each of the four states puts in each section. A
profile without a seed gets no row.

### Phase 4: why a ready coin has not moved

This is D5. The view model also collects `nodeParams.nodeEvents` and keeps:

- the latest `LiquidityEvents.Rejected` with `source == OnChainWallet`, with its
  amount and fee;
- whether a `SwapInEvents.Requested` has been seen for the current set of ready
  coins.

A `Ready` coin then reads as one of:

| situation | text |
|---|---|
| node not connected | *Waiting for your node to connect* |
| rejected, `OverAbsoluteFee(max)` | *Waiting: the fee of %1$s is above your limit of %2$s* with an action, *Review fee limit* (Phase 5) |
| rejected, `OverRelativeFee` | the same, with the percentage |
| rejected, `PolicySetToDisabled` | *Automatic deposits are turned off* |
| connected, no event since the last block | *Waiting for your node* |

`Requested` is shown as a snackbar through `rememberNotifier`, reading *Moving
%1$s into Lightning*. The payment that lands afterwards is the node's own
`PaymentEvents.PaymentReceived` for an `OnChainIncomingPayment`. It also gets a
snackbar, reading *%1$s added to your Lightning balance*.

The node logs two refusals without an event: no matching funding rate, and an
amount too small after fees. Both read as *Waiting for your node*. The plan does
not patch lightning-kmp to emit them. If Phase 0 finds them common, a follow-up
can emit `NoMatchingFundingRate`, which the enum already has, from the swap-in
branch.

Tests feed a fake `nodeEvents` flow and check each row of the table.

### Phase 5: the fee limit

This is D6. A sheet from *Review fee limit* shows the rejected fee, the current
limit, and two choices:

- **Allow this fee once.** This sets a policy whose `maxAbsoluteFee` is the
  rejected fee plus a 10 % margin on the running node only
  (`updatePeerLiquidityPolicy`), and restores the saved policy after the next
  `SwapInEvents.Accepted` or on restart.
- **Change my limit.** This saves the new limit with `saveLiquidityPolicy` and
  applies it to the running node.

Both are retried at once, because the policy is part of the swap-in trigger. The
limit cannot be set below 0 or above the amount being swapped in.

Tests cover the one-off policy being restored on `Accepted` and on a restart (it
must not persist), and the saved policy surviving both.

### Phase 6: the 4-month and 6-month edges

This is D7.

- `ExpiringSoon` coins put a banner at the top of the screen. The check runs
  whenever the profile opens, not only on this screen. The two-week threshold in
  Phase 1 is so that a user who opens the app weekly sees the banner twice.
- `Refundable` coins get a *Refund* action. It opens a form with the destination
  address, checked by `UnusedAddressCheck` and refused if it is one of this
  wallet's own swap-in addresses, and the feerate from
  `peerManager.recommendedFeerateFlow`. The form shows the transaction from
  `spendExpiredSwapIn`, with the fee, before anything is broadcast. The broadcast
  goes through the node's Electrum client.
- `Locked` coins say when the refund opens, as a date as well as a block count,
  using [guardian-delay.md](./guardian-delay.md)'s blocks-to-time wording.

Tests build the refund over a fake chain at `RefundDelay - 1` (refused) and at
`RefundDelay` (built). They check that the transaction spends exactly the
refundable coins and pays the entered address.

### Phase 7: end to end, if Phase 0 allowed it

This phase depends on the third spike. If the personal node can be pointed at
regtest, `PersonalSwapInRegtestJvmTest` sits beside `GroupLightningRegtestJvmTest`
and is gated on `DEDUN_REGTEST=1`. It runs:

1. Pay the current swap-in address from bitcoind.
2. Mine 2 blocks and see `Confirming(2)`.
3. Mine 1 more and see a channel opened.
4. Pay again, mine 3 blocks, and see a splice-in.
5. Set the limit to 1 sat, pay again, mine 3 blocks, and see a `Rejected` with
   `OverAbsoluteFee`.
6. Raise the limit once and see the splice-in.

The refund at 25,920 blocks is mined in the same run, since regtest blocks are
free.

If the fork change is too wide, this phase is cut, and the note says so in its
table of where the build chose differently.

---

## What this plan does not do

- **Swap in while the app is closed.** The node runs only while its profile is
  open. Android's headless business could carry it, but that is the *keeping
  the previous node running* decision in
  [multiple-profiles.md](./multiple-profiles.md), and it is not made here.
- **A swap-in for the group.** It would need the swap-in's user key to be a
  FROST key, a co-signing flow for the MuSig2 input, and an LSP that accepts it.
  That is a design of its own, and D1 keeps the door shut until then.
- **A manual "swap in now".** lightning-kmp has no such command. The trigger
  already fires on every block and on every policy change, so the fee limit in
  Phase 5 is the manual control.
- **A personal Lightning balance or payments screen.** `SovereignWalletUIState`
  still says `balanceInSatoshis = 0 // TODO`. The plan touches it only if
  Phase 3's entry point needs a home. If it does, the home is the profile
  screen, not a new top-level destination.

## Open questions

- Should the *Onchain deposits* row live on `ActiveProfileScreen`, or wait for a
  personal wallet screen that does not exist yet? The plan picks the profile
  screen because it exists.
- Is 5,000 sat the right default for this app's users? Phoenix's default
  assumes a channel already exists. Phase 0's second spike gives the number to
  decide with.
