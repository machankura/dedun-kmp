# Review: the group Wavelength wallet

What this covers: the 39 commits from `eb2456b9` ("a group Wavelength wallet, planned") to `a17dbd26`,
which is about 16,000 lines of Kotlin plus 24,000 lines of generated Room schema. Read against
[group-wavelength-wallet.md](./group-wavelength-wallet.md) and
[group-wavelength-ui.md](./group-wavelength-ui.md).

`docs/scripts/m3-audit.sh --check` passes on this branch — all budgets met, floors held — and none of the
21 remaining string literals in composables is in a Wavelength file. The policy engine, the co-signer and
the watcher are better defended than anything else in this repository: every digest recomputed, every
script rebuilt, an unknown kind refused, and `ArkPolicyJvmTest` alone carries 82 adversarial cases. The
findings below are the places where that discipline stops short of the edge of the system.

**Blocking: [F1](#f1), [F2](#f2), [F4](#f4).** The other four should be fixed, and none of them stops a
release.

| # | Title | Blocking |
|---|---|---|
| [F1](#f1) | The statement's fee ceilings are unbounded and invisible at approval | **yes** |
| [F2](#f2) | The default monthly fee budget is half the mainnet cap | **yes** |
| [F3](#f3) | `forceExit` drives the shared daemon without the runner's mutex | no |
| [F4](#f4) | The receive screen's cap figure is frozen for the screen's life | **yes** |
| [F5](#f5) | `WaitingForSeed`, `NotOpened` and `NeedsKey` never progress on screen | no |
| [F6](#f6) | On-chain change is recognised to index 63 while inputs sign to 511 | no |
| [F7](#f7) | An operator's own clock pins the room's snapshot for the term | no |

---

## Findings

### F1 {#f1}
### The statement's fee ceilings are unbounded and invisible at approval — **blocking**

`GroupWavelengthWalletStatementEvent.read` pulls `maxRoundFeeSat`, `maxOnchainFeeSat`, `maxMonthlyFeeSat`
and `minSweepDelay` out of the `policy` tag with `toLongOrNull()` and checks nothing else, and
`GroupWavelengthWalletManager.checked` — which does verify the chain, the group shape, the onchain wallet,
the key holders, the ceremony and the manifest — never looks at `policy` at all. `ArkPolicy` then holds the
operator to exactly those numbers:
[`ArkPolicy.kt:562`](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/group/ArkPolicy.kt#L562)
refuses a round whose fee is over `maxRoundFeeSat`, and
[`:168`](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/group/ArkPolicy.kt#L168)
over `maxRecentFeesSat`.

**When it comes up.** A member runs a patched build and proposes a statement with `maxRoundFeeSat` at
100,000 and `maxMonthlyFeeSat` at `Long.MAX_VALUE`. Their group sees the line
`ProposedEvent.summarize` writes for kind 30370 — "On mainnet, 3 of 4 to sign, 5 devices online every few
days to keep its coins renewed" — which names no fee figure anywhere, and a quorum signs it. Once that
member is elected operator, a single round whose fee is the whole balance passes every co-signer's check,
because each device is comparing the fee against the proposer's own number.

**Proposal.** Bound the policy where it is read, so that a device neither believes nor co-signs for such a
wallet: `checked()` gains a `Refusal.POLICY_REFUSED` when any field falls outside limits derived from
`GroupWavelengthTerms.of(chain)` and `GroupWavelengthRollout.capFor(chain)` — say `maxRoundFeeSat` at a
small multiple of `minOperatorFeeSat`, and `maxMonthlyFeeSat` under a stated fraction of the cap. Then put
the three fee figures into the statement's line in `ProposedEvent.summarize`, exactly as the Lightning
statement already shows its `inFlightCapSat`. The bound is the part that matters: the summary is a second
line of defence, since the signer may not read it.

### F2 {#f2}
### The default monthly fee budget is half the mainnet cap — **blocking**

`Policy.DEFAULT` sets `maxMonthlyFeeSat = 50_000` while `GroupWavelengthRollout.MAINNET_CAP` is
`Satoshi(100_000)`. A round's fee in `ArkPolicy` is `into - out` — value that leaves the group and reaches
the Ark operator — and a device signs each round automatically with nobody approving anything, so the only
brake on the total is this budget. It also resets per operator term, because
`GroupArkDao.fees(wallet, term, since)` filters on `term`.

**When it comes up.** No patched client is needed for this one. The elected operator and the Ark operator
each take 2,000 sats a round, under the per-round cap, and ask for rounds as often as the daemon will. In
one 30-day window the group's devices will have signed away 50,000 of the 100,000 sats the wallet is
allowed to hold — half the wallet, with no member ever being asked. A fresh election resets the window, and
mainnet's real `minOperatorFeeSat` is 1,000, so 50 rounds of honest renewal also exhausts it, which means
the number cannot simply be raised either.

**Proposal.** Derive the default budget from the cap rather than guessing it: a fraction named in
`GroupWavelengthRollout` beside `MAINNET_CAP`, so the two move together and the ratio is a decision
somebody wrote down. Make the window run on wall-clock alone rather than per term, so an election is not a
reset — a new operator inherits what the last one spent, which is what a monthly budget is for. If the
50,000 is deliberate, that belongs in the KDoc on `Policy` as a stated acceptance, since the current
comment says only that "these defaults are guesses".

### F3 {#f3}
### `forceExit` drives the shared daemon without the runner's mutex — not blocking

Every other entry point into `GroupWavelengthRunner` that touches the daemon takes `mutex.withLock`:
`refresh` (`:183`), `stop` (`:179`), `exitPlan` (`:374`) and `rescan` (`:387`).
[`forceExit` (`:401`)](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/group/GroupWavelengthRunner.kt#L401)
reads `running` bare and then calls `daemon.liveCoins()` and `daemon.forceExit(it)`. The daemon is the one
`HostedWalletClient` in the process, which the group's wallet and the person's own take turns with through
`GroupDaemonSlot` (D13) — the same hazard the KDoc on `Running.jobs` already names.

**When it comes up.** A member on the unilateral-exit screen taps confirm. In the same minute `refresh()`
sees `slot.personalWanted` go true, calls `halt()`, stops the daemon and releases the slot, and the
person's own wallet reopens on the same client. `forceExit` has already passed its `running` check, so
`daemon.liveCoins()` now lists the person's *own* coins and force-exits them one by one. Today this cannot
fire: `require(offersUnilateralExit(live.chain))` throws on mainnet and `NETWORKS` is mainnet only — it
fires the day signet joins `NETWORKS` or the mainnet gate opens.

**Proposal.** Wrap the whole body in `mutex.withLock`, re-read `running` inside it, and take the coin list
and issue the exits in that same critical section, as `exitPlan` does. While the lock is held `halt()`
cannot run, so the client cannot change hands under the loop. It is also worth having
`GroupWavelengthDaemon` carry the identity it was opened for and refuse a call naming another, so that a
future caller which forgets the lock fails closed rather than spending the wrong wallet.

### F4 {#f4}
### The receive screen's cap figure is frozen for the screen's life — **blocking**

`GroupWavelengthReceiveViewModel.initiate` reads the ledger, computes `roomLeft`, decides `AtTheCap` and
computes `enoughSeen` — all **before** entering `observeDepositAddresses(...).collect`, and then re-emits
those same frozen values in every `Loaded` it builds. `Loaded.maxInvoiceSat`, which is what
`askInvoice` validates against, is derived from the frozen `roomLeftSat`.

**When it comes up.** A member opens the receive screen when the wallet holds 20,000 of its 100,000 sats
and is told there is room for 80,000. They leave the screen open, or come back to it an hour later on a
phone that never dropped the composition, while other members' deposits fill the wallet. They send 50,000
sats to the boarding address, and no co-signer will put it in a round, because
[`ArkPolicy.kt:565`](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/group/ArkPolicy.kt#L565)
refuses "a round taking the wallet over its cap". Their coins sit at the boarding script: the daemon's own
timeout exit is a unilateral exit, which `offersUnilateralExit` turns off on mainnet, so the only way out
is for the group to leave the wallet entirely and free the cap.

**Proposal.** Move the ledger, cap and `seen` reads inside the collect — combine `observeCoins` with
`observeDepositAddresses` as `GroupWavelengthWalletViewModel.watch` already combines coins with the
snapshot — so `roomLeftSat`, `enoughSeen` and `maxInvoiceSat` follow the wallet. Let `AtTheCap` be a state
the loaded screen transitions into rather than a decision taken once at entry, so a member watching the
screen sees the room close. The on-chain rail should also say the figure is this device's own count, since
a device with no record of the coins reads the room as the whole cap.

### F5 {#f5}
### `WaitingForSeed`, `NotOpened` and `NeedsKey` never progress on screen — not blocking

`GroupWavelengthWalletViewModel.initiate` calls `read()` once, and `watch()` is started only for `Opened`.
`refreshFacts` returns early for anything else, and even for `Opened` it refreshes only the leaf fields —
never `wallet`, `operator`, `isOperator`, `departed` or `canOpenNew`. The screen's
`LaunchedEffect(true) { viewModel.initiate() }` runs once per entry, so nothing re-reads the state class
while the screen is up.

**When it comes up.** A member opens the wallet screen while the opener's authentication seed is still in
flight. They are told "waiting for the seed", the seed arrives seconds later and is filed correctly, and
the screen keeps saying it until they navigate away and come back. The same holds while the group's first
statement is being signed, and after an election: `isOperator` stays false, so the operator's own controls
and the daemon-status collector — which is launched only `if (opened.isOperator)` — never appear on the
device that was just elected.

**Proposal.** Re-run `read()` on the existing `FACTS_EVERY` tick whenever the state is not `Opened`, which
is three lines and makes every pre-wallet state self-healing. For `Opened`, observe
`groupSignedEventDao` for the wallet and operator kinds and re-read on a change, so an election reaches the
screen without a round trip through the navigator. `GroupLightningWalletViewModel` has the same shape, so
whichever fix is chosen is worth applying to both rather than only here.

### F6 {#f6}
### On-chain change is recognised to index 63 while inputs sign to 511 — not blocking

[`ArkPolicy.kt:319`](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/group/ArkPolicy.kt#L319)
admits an on-chain input at `request.index in 0 until context.keys.window`, which is 512 by default, but
the loop at
[`:330`](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/group/ArkPolicy.kt#L330)
that recognises the account's *own* outputs is hardcoded `0L until 64L`. The same 64 appears again as
`GroupArkCoSigner.ONCHAIN_WINDOW`, for the group's onchain wallet scripts, and nothing ties either of them
to `GroupArkKeys.window`.

**When it comes up.** btcwallet hands out a fresh change address per spend, so after 64 on-chain spends the
daemon's next change output is at index 64. Every device then refuses that spend as "an on-chain spend to a
script nobody authorised" — the input was signable, the change was not — and it stays refused for every
later spend too, because the index only ever goes up. It fails closed, and it is far off on mainnet, where
the on-chain account is reached only by a unilateral exit's sweep and those are gated off.

**Proposal.** Use `context.keys.window` for the output loop, as the input check on the line above already
does, so the two cannot drift. Give the group's onchain-wallet window a named constant shared between
`GroupArkCoSigner.onchainScripts` and wherever else it is needed, rather than a literal in two files.
`ArkPolicyJvmTest` already has "an on-chain key past the window is refused" for the input side; the output
side wants the mirror case.

### F7 {#f7}
### An operator's own clock pins the room's snapshot for the term — not blocking

`GroupWavelengthReports.receive` (`:78`) discards any snapshot whose `sentAt` is not later than the stored
one, and `sentAt` is whatever the operator's own `Clock.System.now()` said in
`GroupWavelengthDaemon.snapshotOf`. Nothing bounds it against the receiver's clock or against the inner
event's `createdAt`, which the room already has.

**When it comes up.** An operator's phone is a month ahead — a dead battery and a bad RTC will do it. It
sends one snapshot, and from then on every later snapshot of that term, including its own after NTP
corrects the clock, is silently dropped by every member: the room's view of what the wallet holds freezes
at that one reading until a new operator is elected. `GroupWavelengthRunner.report` (`:440`) compounds it
from the other side, because `snapshot.sentAt - last.sentAt` goes negative when the clock steps back, so
`< REPORT_AT_LEAST` holds and the 30-minute heartbeat stops while the content is unchanged.

**Proposal.** Order snapshots by the inner event's `createdAt` rather than the operator's self-reported
`sentAt` — `GroupLightningWalletManager.queue` already returns the queued event, and `halt()` uses its
`createdAt` for exactly this reason. Failing that, refuse a `sentAt` more than a few minutes past the
receiver's own clock, which is the same shape as `mayRunDaemon`'s `SYNC_WINDOW`. In `report`, compare the
absolute difference so a backwards step reports rather than suppresses.

---

## Test coverage gaps

The suite is unusually strong — `ArkPolicyJvmTest` has 82 cases, `GroupArkCoSigningJvmTest` 27 over four
real devices, and `GroupWavelengthWalletScreenJvmTest` 37 composable cases. These are the holes.

**T1. Nothing exercises the statement's `policy` tag.** `GroupWavelengthWalletJvmTest` covers the chain
gate, the manifest binding, the seed and the ceremony, but no case asserts anything about a policy — which
is consistent, because [F1](#f1) means there is nothing to assert. The fix for F1 wants a case per field:
a statement with each number out of bounds is refused by every device, and the statement's summary names
the fee caps.

**T2. `forceExit` has no test at all.** `GroupWavelengthRunnerJvmTest` covers open, halt, pause, top-up,
report, leave, rescan and `payApproved`, and never calls `forceExit`; the screen tests drive it through a
fake `GroupWavelengthDaemonSource`. [F3](#f3) wants a case where `halt()` runs between the `running` check
and the daemon call and the person's own coins are untouched.

**T3. Neither view model's state transitions are tested.** Every case in
`GroupWavelengthWalletViewModelJvmTest` asserts one initial `read()`, so [F5](#f5) is invisible to it, and
`GroupWavelengthReceiveViewModel` has no test file of its own — the `WavelengthReceiveViewModelJvmTest`
beside it is the personal wallet's. The receive screen's behaviour is covered only by composable tests over
hand-built `Loaded` states, which is why [F4](#f4) does not show up.

**T4. `GroupArkTransport` has no unit test.** 293 lines holding the operator's whole side of co-signing —
`allocate`'s uniqueness under the `ABORT` insert, `registerNonce`'s point subtraction, `createSession`'s
12-label ceiling, and `ask`'s permanently-`bad` participant set — are exercised only incidentally, through
the co-signing fixture's happy paths.

**T5. The on-chain output window has no mirror case.** `ArkPolicyJvmTest:601` covers an input index past
the window and `:695` covers an unauthorised destination, but nothing covers a legitimate change output at
an index inside `keys.window` and outside the hardcoded 64 ([F6](#f6)).

**T6. Snapshot ordering is tested forward, not backward.** `an older snapshot doesn't replace a newer`
exists; a `sentAt` in the future pinning the term, and the operator's own heartbeat stopping after a
backwards clock step, do not ([F7](#f7)).

**T7. No automated run meets a real daemon on the network that ships.**
`GroupWavelengthSignetJvmTest` is the only end-to-end suite and it is `Assume.assumeTrue`-gated on
`DEDUN_SIGNET_DIR`, so `./gradlew check` on a build with `ENABLED = true` and `NETWORKS = [Mainnet]`
exercises no daemon, no round and no coin. `GroupLightningRegtestJvmTest` covers the Lightning wallet, not
Ark. The docs are candid about this ("no phone has co-signed, and no screen or runner has met a real daemon
or a coin"), which makes it a known gap rather than an unknown one — but it is the gap that matters most
for the two blocking findings, since both are about numbers nothing has measured against a real operator.

**T8. `retryPending` takes the answering path without the lock and has no test.** The new case
`a message filed inside the inbound transaction and the device's own work beside it never wait on each
other` pins the deadlock fix for `answerAll`; the `fileHighWater` → `retryPending` path, which calls
`answer()` bare, is not covered either way (see [S1](#s1)).

**T9. Nothing covers a deposit arriving at the cap.** `GroupWavelengthDepositsJvmTest` covers a made-up
address, a shared address, a shared hash, a non-depositor and a retried invoice, but not a boarding coin
that cannot be boarded because the wallet is full — which is the end state of [F4](#f4).

---

## Code smells

**S1. `retryPending` answers outside `answering { }`.** {#s1}
`GroupArkCoSigner.kt:777-780` calls `answer()` in a loop with no lock and no transaction, while every other
caller goes through `answering`, whose KDoc says why: "a label is looked up and then filed, and two at once
could each find it free". It is harmless today only because the inbound path happens to hold the database's
writer for the whole of `process`, which is exactly the coupling `30a9f5e3` was written to make explicit.

**S2. `remember()` runs on every answer, not only on a new label.**
`GroupArkCoSigner.kt:589` sits above the `if (existing == null)` guard, so a redelivered batch re-runs the
whole of it. The one observable effect is `upsertFee` rewriting `atEpochSeconds`, which quietly slides the
30-day fee window forward each time a message is redelivered. It fails conservative, so it is a smell
rather than a finding — but "written before the echo, so it is never behind one" explains the ordering, not
the repetition.

**S3. `observeUnansweredDeposits()` has no `wallet` in its `WHERE`.**
`GroupArkDao.kt:39` counts every unanswered deposit row in the database, so one room's pending request
wakes `GroupWavelengthRunner` for every room's wallet. As a nudge it is harmless; as a query on a table
whose every other accessor is keyed by `wallet`, it reads like an omission.

**S4. `Policy` is six `Long`s read positionally out of a tag array.**
`template()` writes `p[1]..p[6]` in constructor order and `read()` reads them back the same way, with no
names in between. Reordering the `data class` compiles, passes every test, and silently swaps `exitDelay`
with `minSweepDelay` on any statement written by the new build and read by the old — on a structure whose
own KDoc says it is "frozen once a release holds money". A tag per field, or a name beside each value,
removes the whole class of mistake.

**S5. `GroupWavelengthExitScreen` launches `forceExit` in the composable's scope.**
`:87` uses `rememberCoroutineScope()` for the whole call, not just the notifier, so navigating back
mid-flight cancels a loop that force-exits coins one at a time — leaving some exiting and some not. This is
the same hazard CLAUDE.md documents for `rememberNotifier`, and the runner's own scope is the right one.

**S6. `ask()`'s `bad` set and an `IGNORE` insert decide one thing twice.**
`GroupArkTransport.kt:266` never re-reads a participant once their share failed to verify, and
`IcebergCoSigningDao.insertShare` is `OnConflictStrategy.IGNORE`, so a replacement would not land anyway.
Either alone is a defensible rule; together it is unclear which one is carrying the decision.

**S7. Two KDoc blocks stacked on `parseWalletKey`.**
`GroupArkCoSigner.kt:114-115`: "Where a tree key's labels are filed: the shared nonce table, under a key of
its own" is orphaned from whatever it used to describe — `treeKey` is three lines below it — and now reads
as documentation for `parseWalletKey`, which it is not.

**S8. `GroupWavelengthLedger.coins` counts rows, not coins.**
`of()` sets `coins = rows.size` including spent rows, while every other field filters `!spent`, and
`isEmpty` is `coins == 0`. The semantics are right for what `isEmpty` is for — "has this device ever signed
for a coin of this wallet" — but the field name says otherwise, and the advanced screen reads it.

**S9. CLAUDE.md's literal count is stale.**
It states 19 string literals remain in composables; the audit reports 21. Neither of the two is in a
Wavelength file, so this is drift in the document rather than in this work — worth correcting while the
number is being looked at.
