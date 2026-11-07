# A UI for the group Wavelength wallet

How a group gets from nothing to a Wavelength wallet it can see, fund, pay from and leave, using screens rather than a test harness.

**Built, on 7 November 2026, and not yet on a phone or behind an open gate.** Written against `3648764f`, and files and lines named in the plan below are as they stood at that commit. It takes over the screen work in Phases 4, 5 and 9 to 13 of [group-wavelength-wallet.md](./group-wavelength-wallet.md), and that note's decisions (D1 to D16) still apply. **`GroupWavelengthRollout.ENABLED` is still off, so nobody sees any of this.** Phase 4, the real round on phones, hasn't run.

### Where the build stands

| phase | state |
|---|---|
| 1 | done. Three purpose-blind key lookups fixed, 30371 summarised, `verify` says why, expiry on the ledger, schema 40 |
| 2 | done, in `jvmTest` only: rows, ceremony by purpose, every state before a wallet exists, transcript lines |
| 3 | done, in `jvmTest` only, over the SDK's fake: `GroupWavelengthRunner`, `GroupWavelengthProcess`, the holder in `IdentityWavelength`, `GroupWavelengthService`. **The service has never started on a device.** The type is `specialUse`, from memory of Android 15's limits, and still needs checking |
| 4 | **not run, by decision:** it waits until everything is merged and pushed. It needs fresh signet coins |
| 5 | done, in `jvmTest` only: snapshot, comparison, deadlines, who's been seen, two panes, Advanced, transcript lines |
| 6 | done, for boarding. Lightning receive isn't built (question 3) |
| 7 | done, with payouts-only now enforced on this wallet. Off on mainnet. Nobody has read the new rule but a reviewer that isn't a person (below) |
| 8 | done except the part that matters most: **a unilateral exit still hasn't run.** Its screen is there, signet only |
| 9 | done, by leaving through the onchain wallet (below) |
| 10 | the final pass is done for `jvmTest` and the audit. **The gate is left for the owner** |

**An adversarial reading of the signing code, by a reviewer that isn't a person, before anything was pushed.** It found these, and all are fixed, each with a test:

| found | what it would have let happen | now |
|---|---|---|
| **a txid doesn't commit to what its inputs held** | whoever runs the wallet could describe any confirmed transaction as a round that boarded coins, and make a deposit for anyone, which a payout would then pass against | a device reads each boarding coin's parent from its own Electrum before counting it |
| **a payment job could outlive the daemon it was for** | the client is the process's one, so a job still queued after a handover would have been asked of the person's own wallet, and spent from it | a handover cancels and waits for them before giving the daemon back |
| an amount or fee limit could overflow to negative | a negative cost passes any balance check, and frees the member's balance for as long as it stands | amounts are bounded when read, and a far-off expiry is refused |
| two approvals for one hash read differently | one charged the balance and the other set what the device would pay | the first, everywhere, and a second isn't signed |
| several proposals could each pass against one balance | they were only checked as they arrived | proposals being signed count, and approving checks again, on the proposer's device too |
| cancelling mid-open stranded the person's wallet | it would have stayed marked as the group's | handed back, whatever happens |
| one coin at its deadline took every coin with it | a new deposit would have left at once, as nobody's | only the coins that close |
| an operator in one room could write to another room's ledger on a device in both | wrong records, and a share question for the outside review | a key is read against the room it arrived in |
| others | a member asking for addresses without limit, stopped lines without limit, stopping the person's wallet every few minutes while the group's devices are offline | bounded, once a term, and only once the group is ready |

**Still open from that reading, and not fixed here:**
- **A hostile operator can still get a forfeit signed for a made-up round, up to the 2,000 sats of the fee cap, using any confirmed transaction as its commitment.** It also marks that coin spent on the ledger. A device has no way to tell a real commitment from any other confirmed transaction with the right outputs. That predates this work and needs the outside review the wallet note asks for.
- **A coin from a Lightning claim has no deadline a device can work out.** It shows no deadline rather than a wrong one. Nothing here receives over Lightning yet.
- **While a device runs a group's wallet, the person's own wallet doesn't renew in the background.** The nomination says so, and to open it every few days.

**What the plan said, and what it turned out to be:**

| the plan said | what happened |
|---|---|
| the Phase 3 runner replaces the harness's driving code, and the harness runs it | not done. The harness runs its daemon in another process, because the bindings are Android's, and the runner needs one in-process. So the two share the pool numbers and nothing else, and **a signet pass no longer exercises the runner.** Phase 4 is the first thing that will |
| Phase 8: a `GroupWavelengthLeaveRoute` | a dialog on the wallet screen. There was nothing for a second screen to say |
| Phase 9: re-key through a round, once the wallet note's Phase 13 exists | the policy has no such rule, so the old wallet leaves to the onchain wallet instead. It costs a fee more and works now. **A group of four that loses a member can't make a new key at all:** Iceberg needs four holders, so leaving is all that's left to it |
| question 2: who pays renewal and exit costs | answered here by default, not by the owner: a member's balance shrinks in proportion to what's left in the wallet. So nobody pays alone, and nobody can be paid more than there is. A balance is then a device's own sum, so two devices can differ slightly, and one that hasn't seen a settle refuses a payout it would otherwise sign. That fails the safe way |
| U10: `walletName` becomes a string resource | not done. It only ever goes into transcript lines, which are stored English like every other line here |
| Phase 5: count what a snapshot costs before choosing a schedule | can't be done without a funded wallet. The runner reports on change, and at least every 30 minutes. **If it turns out each read costs group signatures, this needs changing** |

**What building it found:**

| found | what it was |
|---|---|
| **two answers to "how far has the group got?" were one message** | they'd be identical within a second, so a member who asked a moment after another heard nothing and sat with no floor. On a real group that would have held up a first round past its deadline. A runner test found it. Each answer now names its question |
| the first signature was where a device found out its floor | inside the round's deadline. The runner now asks ahead of the daemon starting (`askFloor`) |
| round one wasn't there before the daemon started | a daemon's first signature would have failed. The harness had always provisioned first, by hand. `GroupWavelengthDaemon.open` now takes a `prepare`, and the runner waits for the pool |
| `Refused` carried English | now a type, so a screen says it |

Read these first:

- [group-wavelength-wallet.md](./group-wavelength-wallet.md): the wallet itself, what the signet runs found, and "What mainnet has and hasn't been shown".
- [group-lightning-wallet.md](./group-lightning-wallet.md) and [group-lightning-transcript.md](./group-lightning-transcript.md): the screens and transcript lines this copies.
- [payouts-only.md](./payouts-only.md) and [member-deposits.md](./member-deposits.md): the rules for money coming in and going out.
- [CLAUDE.md](../CLAUDE.md): the rules every screen follows.

The headline: **the screens are the smaller half of this.** Everything the wallet has done so far, a harness did: `GroupWavelengthSignetJvmTest` and `TestHostedBridge` started the daemon, topped up the signing pool, paid approved invoices and drove the rounds. None of that is in the app. A screen with a *Run this wallet on my device* button needs something behind it, so this plan builds that too.

**Decided by the owner on 7 November 2026, after this was first written:** mainnet is included, beside signet. Everything gets built first, and the real round on real phones (Phase 4) runs once every change is merged and pushed. So Phase 4 keeps its number and runs last. This accepts the risk below: if phones can't make the window, some of what's built gets rewritten or shelved.

Three things shape the work:

1. **Phones may not be fast enough.** Forfeits get 60 seconds, and the group's own 160 to 700 ms a signature was measured in one process with no network. Spike 4's condition is still unmet. The wallet note held back its screens for this reason: *"a screen for a wallet that may turn out to need always-on co-signers would be written twice."* Phase 4 is where that gets answered.
2. **Some of what the screens could offer isn't safe to offer yet.** A payment can tie up its value indefinitely, and a unilateral exit has never run. Those actions stay off or held back until the gap closes (see "What the screens may not promise").
3. **A device's own ledger comes before the operator's word.** That is the wallet's security model, and the balance on the screen has to follow it.

## What exists, and what is missing

From reading the code at `3648764f`.

| a screen needs | what exists | what is missing |
|---|---|---|
| a way in | nothing | a row on `ChatRoomDetailScreen`, below the Lightning wallet's (`ChatRoomDetailScreen.kt:478`). `GroupWavelengthRollout.ENABLED` is off, and nothing reads it |
| a key | `IcebergRitualManager.proposeCeremony(purpose = …)` | the repository doesn't pass a purpose (`DatabaseGroupLightningRepository.kt:119`), `IcebergCeremonyRoute` has none, and the screen says *Lightning* throughout |
| keys kept apart | `unusedKey` on both wallets filters by purpose | **three lookups don't.** `IcebergRitualManager.completedKey:557`, `IcebergCeremonyViewModel.kt:78` and `GroupLightningWalletViewModel.kt:91` take any ceremony. The first Wavelength ceremony would appear on the Lightning screen as its key, and *Open a Lightning wallet* would then fail |
| a network | `allows` and `settleDepth` | no `GroupWavelengthRollout.NETWORK`. I haven't traced whether any screen can open a signet onchain wallet, which `verify` requires first |
| opening the wallet | `proposeWallet`, summarised in `ProposedEvent` | it takes `arkOperatorKey`, and only the harness finds one (`GroupWavelengthSignetJvmTest.kt:90`). `verify` returns null and logs why, so a screen can't tell a member *waiting for the seed* from *no wallet* |
| choosing an operator | `nominateSelf`, `stepDown`, `current` | `ProposedEvent.summarize` has no case for kind 30371, so a member wouldn't be told what they're approving |
| running the daemon | `GroupWavelengthDaemon.open` | nothing calls it. It can't share the process's one client with the personal wallet (D13), and the app has no foreground service at all |
| keeping it running | test code only | topping up the pool, stopping at a later term, paying approved invoices, answering deposits. `GroupArkTransport.provision` has no caller outside tests |
| a balance | `GroupArkVtxo`, `GroupArkDao.vtxos` | one-shot queries only, and **no expiry on the row**, so a member can't work out a deadline on their own |
| what the operator says | kinds 30378, 30380, 30381 and 30382, as numbers | no event classes and no handling. `NostrDao` routes co-signing and the seed only (`NostrDao.kt:1632`, `:1642`) |
| paying out | `GroupWavelengthPayments.propose`, `approved` | it names no member, so **payouts-only isn't enforced on this wallet.** `MemberBalance` has onchain and Lightning, with no third rail |
| leaving | the daemon's `exit`, with the policy's rule | kind 30379 carries only a Lightning payment, so nobody can ask for an exit |
| the transcript | one refusal line and one equivocation line, under Lightning's types | no Wavelength line types, and elections, rounds and deadlines write nothing |
| desktop and iOS | co-signing is common code | `WavelengthPlatform.client` is null on desktop, so the screen has to say why it can't run the wallet there |

## What the screens may not promise

Each row is a known gap from the wallet note, and what the UI does about it until it closes.

| gap | what the UI does |
|---|---|
| **a funded vHTLC has no way back but the cooperative refund, which has only met fixtures** | *Pay out* over Lightning stays off on mainnet. The wallet note says this path *"isn't ready for anything but signet"* |
| **a unilateral exit has never run on any network** | no emergency-exit button until one has run on signet (Phase 8). The Advanced screen says it hasn't been tested |
| **an invoice can't be proven to pay the group** (spike 6) | the first receive option is boarding, whose address each member recomputes. Lightning receive is an owner decision (question 3) |
| **the operator can hand out or report anything** | every figure from a snapshot is labelled as the operator's, and compared with the device's ledger |
| **the ledger misses things.** A restored device starts empty, and coins sent by anyone else aren't in it | the balance says *what your device has signed*. An empty ledger beside a non-empty report says this device can't confirm the figure, not that the wallet is empty |
| **a transfer aborted after the Ark signature stays reserved until the term ends** | shown as *held until the next operator*, with a way to nominate |
| **a hosted wallet's lookups can each cost a quorum** (a vHTLC lookup every 15 s, 5 signatures per key on recovery) | no screen polls the daemon, and there is no pull to refresh. The runner decides when to read, and a screen reads the database |
| **the iceberg module says not to hold money behind it, and nobody outside has reviewed it** | said on the opening screen on every network, with the cap on mainnet |

## Decisions

**U1. Its own screen, view model and state, in the Lightning wallet's shape.** `GroupWavelengthWalletScreen`, `…ViewModel`, `…UIState` and a `GroupWavelengthRepository` with a `NO_OP` form. Merging with `GroupLightningWalletScreen` would tangle channels and rounds for little gain, and that screen is on mainnet. Only small widgets move out, such as `SignedWorkRow`, which is private today.

**U2. The balance is the device's own ledger.** The headline figure is this device's settled, unspent coins, with pending below it. The operator's figure sits beside it with *matches what your device signed* or *differs*, as the Lightning wallet does per channel. The Lightning wallet leads with the operator's number because a member can't compute one. Here a member can.

**U3. Nothing on these screens asks a person to approve a device's work.** Renewals, forfeits and claims are the device's to answer (D7), so they show as history. What a person approves is a proposal, through the existing `FrostSigningRoute`. A screen never moves money itself: it asks the group or asks the operator's device.

**U4. A refusal is loud.** A device's refusal is the one thing about co-signing a person ever sees, so it gets a card on the wallet screen as well as a transcript line. An equivocation gets the error colour and a way to nominate.

**U5. "Seen recently", never "online".** Relays can't say who is online. The screen says when each member's device last answered, and whether `2t-1` have within the window. That's a fact a member can check.

**U6. The ceremony screen gets a purpose, and isn't copied.** `IcebergCeremonyRoute` gains `purpose`, and the strings that differ go through `IcebergKeySession.walletName`'s replacement. `walletName` itself is an English literal, so it becomes a string resource.

**U7. Only Android operates.** Desktop and iOS co-sign (D2). Their screen shows everything except the nomination button, and says why. On desktop: *leave this open*.

**U8. Mainnet and signet, side by side.** `GroupWavelengthRollout.NETWORKS` lists them. `ChatRoomDetailScreen` has a row for each, the route carries its chain, and the runner looks after every wallet on every network. A signet wallet says it's a test wallet, whose coins have no value. Tests pass a chain in.

**U9. `ENABLED` is read in two places:** the row on `ChatRoomDetailScreen` and the runner. A device with it off still verifies and co-signs, as its comment says. Screen tests take state directly, so they don't depend on it. A build for phones turns it on in a commit on a test branch, which never merges.

**U10. Words come from `strings.xml`.** `GroupWavelengthDaemon.Outcome.Refused`, `GroupArkCoSigner.sayRefused` and `walletName` hold English today. They become reason types that a screen renders, so the literals budget doesn't grow. *Renew*, never *refresh*. *Coin* on the main screen, and *VTXO* only on Advanced (D16).

## The screens

| route | who sees what | phase |
|---|---|---|
| row on `ChatRoomDetailScreen` | everyone in the room, disabled with a reason until the onchain wallet exists | 2 |
| `IcebergCeremonyRoute(purpose)` | share holders take part, others watch | 2 |
| `GroupWavelengthWalletRoute`, not yet open | what's still needed, in the order below | 2 |
| `GroupWavelengthWalletRoute`, open | balance, deadline, who's been seen, activity, operator, limits. Two panes from `Breakpoint.Expanded` | 2, 3 and 5 |
| `GroupWavelengthAdvancedRoute` | coins with their state, the statement's terms, fee budget used, signing pool, untested paths | 5 |
| `GroupWavelengthReceiveRoute` | a depositor's boarding address, and later an invoice | 6 |
| `GroupPayoutsRoute`, with a Wavelength rail | a member's payout, as for the other two wallets | 7 |
| `GroupWavelengthLeaveRoute` | what leaves, where it goes, the next round's time, the cost so far | 8 |
| `GroupWavelengthExitRoute` | the plan and stages of a unilateral exit | 8, after a real one |

**The states of the wallet screen, in order:**

| state | says | offers |
|---|---|---|
| `Loading`, `Error` | `LoadingDataIndicator`, `ErrorState` | retry where it would help |
| `NeedsOnchainWallet` | money leaving this wallet goes to the group's onchain wallet, so that comes first | the group wallet |
| `NeedsKey` | the key needs at least four signers, or a ceremony is under way | make a key, or see the ceremony |
| `NotOpened` | **the commitment** (below) | open a wallet, which goes to the signing session |
| `WaitingForSeed` | the group opened a wallet, and this device is still waiting for its seed | nothing to do but wait |
| `Refused` | the group opened a wallet this device doesn't accept, and why | nothing. Offering a way past it would defeat the check |
| `Opened` | everything else | see below |

**The commitment,** which should decide whether a group wants this wallet (D11). Drafts:

> This wallet needs %1$s of your %2$s devices online every few days, or the money stops being safe.

> Whoever runs it needs an Android phone left on and connected. The group can change who that is at any time.

> Coins last about a week before they need renewing. Each renewal, and leaving, costs a fee, which comes out of the wallet.

> The way this key works hasn't been reviewed by anyone outside the project. On mainnet it holds at most %1$s sats. Keep only what you could afford to lose in it.

**`Opened`, top to bottom on a phone.** At `Breakpoint.Expanded`, the first four stay left and the rest go right, at `listPaneWidthFor`.

1. **Cards, only when true:** a refusal, an equivocation, a deadline, departed members who still hold a share.
2. **Balance:** the device's figure, pending below it, the operator's figure and whether it agrees. *Receive*, *Pay out* and *Leave* where allowed.
3. **Deadline:** *Coins need renewing within 3 days*, using the same words as `WalletExpiry` and the personal wallet.
4. **Activity:** rounds, transfers, payments, each with its fee. A round says when it settled. No figure appears before this device has seen it.
5. **Who's been seen:** each signer and when last, and whether `2t-1` have within the window.
6. **Operator:** who, which term, what the daemon is doing. *Run this wallet on my device* with D13's sentence, or *Step down*.
7. **Limits:** `t` of `n`, the cap, the fee budget, and a link to Advanced.

## Phases

One commit per phase, each compiling and passing. Messages end `(Group Wavelength UI, Phase N)`. Each phase's checks are the same two commands from `CLAUDE.md`, and screens get `*ScreenJvmTest` and `*ViewModelJvmTest` tests in the style already there, at phone width and 1280 dp.

### Phase 1: the read model, and three repairs

No new screen. It fixes what would break as soon as there is one.

- **Purpose everywhere.** `completedKey` and the two ceremony lookups filter by purpose. This changes Lightning's path, so its tests and regtest run are part of this phase. `GroupLightningRepository.proposeCeremony` takes a purpose, defaulting to Lightning.
- **`ProposedEvent`** gets kind 30371, so a nomination says who and which term.
- **`verify` says why.** `GroupWavelengthWalletManager.verify` returns a verdict with a reason, and `wallets` and `current` keep their signatures. `WaitingForSeed` and `Refused` come from this.
- **`GroupWavelengthRepository`**, with `Database…` and `NO_OP` forms.
- **`GroupArkDao`** gets `Flow` queries for coins, fees and rounds.
- **Expiry on the ledger:** `GroupArkVtxo.expiresAtHeight`, filled from the batch life each round note already carries, with the commitment's height added when the watcher sees it confirm. Schema 40, one migration. It has to be the device's own figure, or the deadline is the operator's word.
- **`GroupWavelengthRollout.NETWORKS`**, and the operator's key and terms. Pin the key and terms per chain in the rollout. A proposer's device reads the live terms over HTTP, as the harness does, which also works on desktop, and warns if they differ from the pin. Then the statement holds what the group agreed.

**Tests:** a Lightning screen ignores a Wavelength ceremony and the reverse, each `verify` reason, expiry filled in when a round settles, a migration test, and the summary of 30371.

**Done when** nothing visible has changed, Lightning's regtest run still passes, and `jvmTest` covers the above.

### Phase 2: from nothing to a wallet

The first screens, all of them reachable on a test build.

- The row on `ChatRoomDetailScreen`, gated by `ENABLED`.
- `IcebergCeremonyRoute(purpose)`, with sentences that follow the purpose (U6). The threshold chooser explains refused sizes, as it does now.
- `GroupWavelengthWalletScreen` with every not-yet-open state, and `Opened` showing only limits and the operator card. Balance is absent, and says so, until Phase 3.
- The commitment on `NotOpened`, and the signing session on tap.
- Nominate and step down. On desktop, U7's sentence instead.
- Transcript lines for the seed being shared, the wallet being opened, and elections.

**Tests:** each state at both widths, a device waiting for its seed, a non-participant's read-only view, `m3Audit`.

**Done when** four devices on a signet test build, two of them phones, make a key, open a wallet and elect an operator, all by tapping, and each verifies the same statement. **No coin has moved yet.** It needs an onchain wallet on signet, which Phase 1 finds out how to reach.

### Phase 3: something actually runs it

**Not a screen, but no screen works without it.** Three pieces, in this order:

- **The holder (D13).** `IdentityWavelength` gains an owner: personal or group. `WavelengthProcess` remains the one holder. On a device running a group's wallet, the personal wallet's row says *This device is running %1$s's wallet*, and opening it pauses the group's wallet while that screen is up. `WavelengthMaintenanceWorker` gives way. This touches the personal wallet on mainnet, so its tests have to stay green, and it gets a commit of its own. The wallet note says `IdentityWavelength` was being changed on another branch: check that first.
- **`GroupWavelengthRunner`**, shaped like `GroupLightningNodes` and made where that is (`DedunNavHost.kt:416`). Every minute: is this device elected, has it heard from the room lately (`marmotGroupEventDao().newestSavedAt`, as the Lightning node does), and does the daemon match? It opens through `GroupWavelengthDaemon`, provisions the pool, stops at a later term and handles recovery with the window from D14. It exposes a `GroupWavelengthDaemonStatus` flow: not on this device, no runtime, paused for the personal wallet, waiting to catch up, opening, recovering, running, refused, failed. **Lift the harness's driving code into this, and make the harness use it,** so the signet run tests what ships.
- **The Android foreground service,** the app's first. A daemon stopped on background can't answer a round. As I recall, Android 15 limits the `dataSync` type to 6 hours in 24, which would make it wrong for this, so `specialUse` is the likely type. **That's from memory: check it, and what it means for store review, before building.** The Lightning wallet's node can use the same service later, though this plan doesn't change it.

On the screen: the daemon's status row on the operator card, with reasons from strings (U10).

**Tests:** the runner with the SDK's fake: elected, not elected, later term, stale, a second daemon refused, a personal and a group owner swapping. A handover between two runners.

**Done when** a phone elected through the screen opens the daemon with its app in the background, a second phone takes over by election, and only one daemon ever runs.

### Phase 4: a real round on real phones, and the decision

**Runs last, once every change is merged and pushed (the owner's decision).** It answers spike 4, which no harness can. It needs signet coins sent by hand, and fresh keys, since the last run's seed should be treated as exposed. The "done when" lines of other phases that name phones are checked here too: until this runs, those phases have been tested in `jvmTest` only.

- The smallest possible receive: the operator's device shows a boarding address that each member recomputes and compares.
- The balance from the device's own ledger, pending then settled, and the deadline.
- A timing log, kept out of the release: for each round, when the commitment arrived, when each device answered, and whether the forfeit made 60 seconds.

**The run:** four devices, at least three of them real phones on real relays over Marmot. Board, renew, transfer to self and leave. Then again with screens off and apps in the background, with one phone off during each of nonce, tree and forfeit, and with the operator's phone dying after forfeits are handed over. Record every number in the wallet note. **A group that misses the window also costs everyone else in that round** (see "What Lightning found"), so don't push this much further than it needs.

**The bar for going on is the owner's to set** (question 4). I suggest: a quorum answers within 30 seconds in 19 of 20 rounds, with phones in the background, including one round per VTXO count the group would really have.

| result | what happens next |
|---|---|
| phones clear the bar | the gate can lift (Phase 10) |
| only with a desktop or always-on co-signer | the commitment says so, the ceremony asks for one, and the gate can lift |
| not at all | everything stays behind the gate, and `ENABLED` stays off |

**Done when** the wallet note has the numbers and the owner has said which row applies.

### Phase 5: what the group sees

- **The snapshot,** kind 30378, and `GroupWavelengthReports`, on the shape of `GroupLightningReports`: balance, coins with expiry, boarding coins, activity, indices, any credit, all as the operator's claim. The runner sends it on change, rate-limited, and reading for it must not itself cost signatures. **Count the signatures a snapshot costs before settling on a schedule.**
- **The comparison:** per coin, whether the operator's list and the device's ledger agree, including the daemon and devices disagreeing about a coin at a key the daemon didn't issue, which the second run found. Credit, if any ever appears, is *Not protected by the group* and outside the balance (D10).
- **The rest of `Opened`:** activity, who's been seen (from the newest inner event per participant), two panes, and refusal and equivocation cards.
- **Advanced:** coins with their state (pending, settled, reserved, spent), origin and expiry. The statement's terms. Fee budget used against `maxMonthlyFeeSat`. Signing pool. A list of what hasn't been tested.
- **The transcript,** per [group-lightning-transcript.md](./group-lightning-transcript.md): new `TYPE_WAVELENGTH_*` types and a `WAVELENGTH_TYPES` set. Moves the refusal and equivocation off Lightning's types. Adds a renewal, a missed renewal, an approaching deadline, a dead round and a transfer held by the term. Protocol kinds never appear as raw JSON.
- **Kind 30382**, stopped, so a handover names what the new operator can't rebuild (D14).

**Tests:** a snapshot that lies, at the balance, per coin, and about expiry. A restored device with an empty ledger. A hostile snapshot that is huge or malformed. Each transcript line once only.

**Done when** all four members see the signet wallet and agree on it, and a planted lie shows as *differs* on every device.

### Phase 6: deposits

Needs question 2 answered first, as the wallet note already says of its Phase 10.

- **`GroupWavelengthReceiveRoute`:** only for a member with a depositor statement, using the existing *start depositing* flow.
- **A boarding address:** request 30380, answer 30381, an index the operator allocates. The member **recomputes the script from the statement and refuses one that differs,** and the index and member are recorded on every device. The screen states confirmations (6 on mainnet's current terms), the next round's time, the cost, and the operator's minimum fee of 1,000 sats, with a floor on the amount. The last signet run paid 1,484 of 19,305 sats in fees, so a small deposit can lose a real share of itself, and the member should know before sending.
- **A Lightning invoice,** if question 3 allows: the requester's device files the hash, the invoice comes with a sentence that the operator made it and a device can't confirm where it pays, and the screen shows whether `2t-1` have been seen recently, since a claim needs them.
- **A third rail in `MemberBalance`,** beside onchain and Lightning, and through `DepositLedger`. A deposit nobody can be matched to is *unattributed*, as elsewhere.
- **The cap:** on mainnet, a deposit that would pass `MAINNET_CAP` counting what's pending is refused before an address is shown. Every co-signer would refuse it anyway, too late for the depositor.

**Tests:** an address off by one key refused, a member without a statement, a deposit landing on the same tally on all devices, over the cap.

**Done when** a member's deposit lands on their tally on every device on signet.

### Phase 7: payouts

- **Bind a payment to a member.** `GroupWavelengthPaymentEvent` gains the member and their destination, `PayoutRule` applies, and a co-signer refuses a payment without one. **This is a change to what devices sign,** so it goes in on its own and gets the adversarial reading the policy got.
- **A rail in `GroupPayoutsScreen`,** not a new send screen: the Lightning wallet's comment there is the rule, *no invoice typed in*. The proposer's device reads the invoice from the member's Lightning address, as now, and picks `maxFeeSat`, `maxRefundHeight` and an expiry the screen states in time.
- **A payment's life, visible:** proposed, approved by a quorum, waiting for a coin, sent, paid, or failed with what's held and until when. The runner pays what's approved, once per hash, and **never tries what isn't approved**, because a refused attempt holds the coin for 50 s and can block the group's real payment (see "What Lightning found").
- **Failure:** a failed payment says it needs a new invoice. An approval nobody used shows until it expires, since nothing withdraws one.
- **Off on mainnet** until the refund path has run for real (see "What the screens may not promise").
- **Onchain payouts** by a leave output in a round, if the census shows it leaves least behind.

**Tests:** over a member's balance, a former member, an expired approval, two approvals for one hash, the rail hidden on mainnet, a screen through each stage.

**Done when** a member is paid out on signet, their tally goes to zero on every device, and a deliberately failed payment gets its money back with the screen right at each step. **That last part is also the first real test of the refund rule.**

### Phase 8: staying alive and getting out

- **Renewal shown:** when the last one happened, what it cost, when the next is due. D11's two thresholds become a transcript line naming who hasn't answered, and a card.
- **Leaving:** `GroupWavelengthLeaveRoute`. Kind 30379 gains an exit, so anyone can ask and not only the operator. Confirmation states the amount, the onchain wallet it reaches, and that it joins the next round. Progress follows the ledger, and finishes when the group's onchain wallet sees the coins. Automatic leaving at the second threshold starts here.
- **Run a unilateral exit on signet first.** It takes a day of blocks, needs a coin, and has never been done. Without that, this phase can't honestly offer the rest.
- **Then `GroupWavelengthExitRoute`:** the plan from `getExitPlan`, its cost, each stage from `exitStatus`, and that it can't be recovered on another device (D14). Reached from Advanced, and from the deadline card once a round is no longer possible.
- **An operator nobody can reach:** the deadline card offers *Run this wallet on my device*, and says it recovers from the chain at about 5 signatures per key.

**Tests:** threshold mapping across tip heights, each stage of an exit, leaving with one signer short.

**Done when** a signet group has left both ways from the screens, the money is spendable by FROST from the onchain wallet, and a coin left near expiry with devices off warned everyone when they came back.

### Phase 9: members change

As `GroupLightningWalletScreen` does it, once the wallet note's Phase 13 exists: a card for departed members who still hold a share, a new ceremony and wallet, the re-key authorisation, an `Earlier` section until the old wallet is empty, and the removal flow's sentence on `ChatRoomDetailScreen` beside the Lightning one. **The screen can't come first:** the policy needs its re-key row.

**Done when** a removal on signet ends with the money under the new key and the old wallet empty, all from the screens.

### Phase 10: the gate

- **`ENABLED`** lifts on the wallet note's condition: Phases 8 to 13 by hand on a signet group, which Phases 4 to 9 here make possible with people rather than a harness.
- **The final pass:** `m3Audit`, each screen on a phone, a 1280 dp window and desktop, light, dark and high contrast, reduced motion, large text.

## Order

| phase | depends on | touches | rough size |
|---|---|---|---|
| 1 read model | - | app, Lightning's lookups, schema | 1 week |
| 2 to a wallet | 1 | app | 1 week |
| 3 runner, holder, service | 2 | app, the personal wallet, Android | 2 to 3 weeks |
| 5 what the group sees | 3 | app | 1.5 weeks |
| 6 deposits | 5, question 2 | app, balances | 1.5 weeks |
| 7 payouts | 6 | app, **policy** | 1.5 weeks, plus a reading |
| 8 alive and out | 5, a unilateral exit on signet | app, signet | 2 weeks |
| 9 members change | 8, the wallet note's Phase 13 | app, policy | 1 week |
| **4 phone round** | **everything merged and pushed, signet coins, phones** | **signet** | **1 week, and the decision** |
| 10 gate | 4 | app | owner's call |

**The estimate below was for a person, and turned out to be nothing to go by: it was built in one session, and what that session couldn't do is listed at the top.** About three months for one engineer, and a guess: my last time estimate in this work was wrong, and most of what took the time so far was found by running against real servers. The first thing a person can see is the end of Phase 2. The first thing that can change what was built is Phase 4. Phases 7 and 8 can run side by side after 6.

## Risks

| risk | why it matters | what to do |
|---|---|---|
| phones miss the forfeit window | the wallet can't renew on phones, and each miss costs other people in the round. Everything else is built before this is known, by decision | Phase 4 with a bar agreed in advance, and `ENABLED` off until it has run |
| the foreground service is refused or limited | no background daemon, so no operator on a phone | check the type first. A plugged-in, always-open phone is the fallback, and the commitment says so |
| the holder breaks the personal wallet | it's on mainnet and shares the client | its own commit, its tests kept green, and a run by hand |
| the screen gives a false sense of safety | a clean balance card reads as *your money is fine* | U2, and "What the screens may not promise", which goes with each phase's review |
| signatures from reads | a screen or snapshot that polls costs quorums and pool | no screen polls the daemon, and Phase 5 counts the signatures |
| purpose-blind code remains | a key used by the wrong wallet | Phase 1, and a test per lookup. Search for other callers then |
| the harness and the runner diverge | a signet pass stops meaning the app works | Phase 3 moves the driving code into the runner, and the harness runs that |
| schema churn across sessions | two branches bump the schema | one migration when it merges (D16) |

## Questions, for the owner

1. ~~Which network does the first build offer?~~ **Decided: mainnet and signet.** A unilateral exit still hasn't run and a payment's refund still hasn't been tested, so Lightning payouts stay off on mainnet until the refund has run (see "What the screens may not promise"). Money can still leave a mainnet wallet by leaving to the onchain wallet. Rolling back gets hard with the first mainnet coin (`group-wavelength-wallet.md`, Phase 14).
2. **Who pays renewal and exit costs under payouts-only?** The wallet note's open question 3, and Phase 6 can't finish without it. Pro rata by balance is the likely answer.
3. **Is Lightning receive allowed with no way to prove an invoice pays the group?** A hostile operator can hand a depositor one that pays itself. **Recommended: boarding only, with Lightning receive on signet for testing,** until spike 6 has an answer. Payout over Lightning doesn't have this problem, since a quorum approves it.
4. **What bar does Phase 4 have to clear?** I suggested 30 seconds in 19 of 20 rounds. A miss costs other people in that round too, so the number is partly about them.
5. **Does a group really want both Lightning and Ark?** The wallet note's question 4. If this replaces one, the entry row and Phase 9 change. It's cheapest to answer before Phase 2.
6. **Can Phase 4 use your signet coins again?** They come by hand, since signet has no faucet API. The last run's seed should be treated as exposed, so these need a fresh wallet.

## What this plan does not do

- **Change the Lightning wallet's screens,** beyond Phase 1's purpose filter and an optional move of the service.
- **A daemon on desktop or iOS** (D2).
- **Typed-in invoices or free-form sends.** Payouts-only applies.
- **Push notifications.** A deadline reaches a member through the transcript and the wallet screen, so a member who doesn't open the app won't see it. **That's a real weakness of D11,** and worth its own note once Phase 4 has shown whether co-signers stay awake.
- **Close the wallet's own gaps.** The vHTLC's unilateral leaves, the forfeit of a funded vHTLC, a reorg past the depth and an outside review all stay as the wallet note lists them. This plan keeps the UI honest about them.
