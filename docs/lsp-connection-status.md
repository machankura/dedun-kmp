# Whether the group's node can reach the LSP: the operator sees it, and so does the group

The group's Lightning wallet runs one node, on the elected operator's device, and
that node is only any use while it is connected to the LSP. Every invoice the group
issues is paid through that connection, and so is every payment it sends, every
liquidity purchase and every mutual close. Today nobody can tell whether it is up.
`GroupLightningNode.stayConnected` knows exactly what is happening: whether the last
dial held, why the connection ended, how many attempts in a row failed, and how long
until the next. All of it goes to `logger.w` and nowhere else. A node that
`GroupLightningNodes.refresh` refused to start says why, also only in a log line. The
operator's wallet screen says *Run by you, term 3* whether the node has been connected
for a week or has been failing to dial for an hour.

This plan makes the connection a state the app can see. **The operator's node
publishes a typed status: not running and why, starting, connecting, connected since
when, or unable to reach the LSP, with the cause and when it will try again. The
operator's wallet screen shows it, with *Try now* to skip the backoff. The operator's
snapshot then carries the link to the rest of the group, so every member's wallet
screen can say whether the operator's node could reach the LSP when it last reported,
and how long ago that was.**

Read [group-lightning-wallet.md](./group-lightning-wallet.md) Phases 7, 8 and 10
first (the operator, the node and the snapshot), then
[material-design-conformance.md](./material-design-conformance.md) for the rules
the new rows follow.

**Built**, Phases 0-6, one commit each, against `50c21a46`. No schema change. One
wire change, optional in both directions (D8). `GroupLightningNodeStatus`,
`LspLink`, `LspDrop`, `GroupNodeStatusSource`, `NodeStatusRow` and `OperatorLinkRow`
are the entry points. The phases below are kept as written. Where the build chose
differently, the table at the end says so. Not done: the hand check on Android and
desktop (Phase 6).

## What is there today

| piece | where | what it knows | where it goes |
|---|---|---|---|
| the node's life | `GroupLightningNode.State` (`managers/GroupLightningNode.kt:158`) | `Stopped`, `Running(term)`, `Refused(reason: String)` | a `StateFlow` nobody outside the class and `GroupLightningNodes` reads |
| why a start was refused | `GroupLightningNode.start()` (`:181`) | five reasons, as English sentences | `logger.i` in `GroupLightningNodes.refresh` (`wallet/lightning/GroupLightningNodes.kt:77`) |
| the connection | `stayConnected` (`:380`) | connected or not, how long it lasted, the drop reason, the failure count, the wait | `logger.w` |
| why a drop happened | `dropReason` (`:497`) | socket reason, plus the feature the LSP would not offer or the one it requires | a string returned by `PeerHandle.awaitDisconnect` |
| the link, below us | lightning-kmp `Peer.connectionState` | `CLOSED(reason)`, `ESTABLISHING`, `ESTABLISHED` | read once, in `awaitDisconnect` |
| the nodes | `GroupLightningNodes.nodes` | one node per wallet this device operates | a private `mutableMapOf`, built in a `remember` in `DedunNavHost` (`:333`), after the repository the screen is given (`:247`) |
| the screen | `GroupLightningWalletScreen.OpenedWallet`, the operator card | the operator's name and term | `Run by you, term N`, *Step down* |
| the group's view | `WalletWire.Snapshot` | channels, payments, rates, `sentAt` | `Reported by the operator in term N`, with no age |

So the information exists. It was never turned into state, and the screen has no
path to the nodes even if it were.

One thing about lightning-kmp matters here. `Peer.connect` sets `ESTABLISHED` as
soon as the Noise handshake completes, before either side has sent `init`. An LSP
that hangs up on our features does it after its `init`, so a connection can be
`ESTABLISHED` and useless for the second it lasts. lightning-kmp only emits
`PeerConnected` (which carries `theirInit`) once the `init` exchange has completed.
That event is what *connected* means in this plan (D2).

## Decisions

| # | decision | why |
|---|---|---|
| D1 | The status is the node's own `StateFlow`, written where the facts are, in `start()` and `stayConnected`. Nothing parses logs or polls `Peer` from the UI. | The loop already holds the reason, failure count and wait. Anything that tried to reconstruct them would just drift from the backoff they describe. |
| D2 | *Connected* means the LSP's `init` has arrived (`PeerConnected`) on the current connection, not `Connection.ESTABLISHED`. | `ESTABLISHED` comes before features are checked (above). The drops worth explaining are the ones that happen between the two. |
| D3 | A node that isn't running is a status too, with a typed reason: `Refused(reason: String)` becomes `Refused(reason: Refusal)`, an enum. The UI maps each value to a catalogue string. | The operator most needs a status when the node never started at all: the pool is short, or the room hasn't synced. The catalogue rule (CLAUDE.md) puts every UI string in `strings.xml`, so a manager's English sentence can't be the headline. |
| D4 | A drop gets a typed `DropCause` for the headline, and the raw reason is kept as selectable detail text. Causes: `FeatureMismatch`, `Unreachable` (refused, timed out, DNS), `TorWithoutOnion`, `Handshake`, `ClosedByUs`, `Other`. | A raw string like `Connection closed: the LSP does not offer simple_taproot_channels` is right for a bug report and wrong for a headline. The socket's own message comes from the platform and can't be translated, so it stays as detail. |
| D5 | On a device that isn't the operator, the source returns nothing. On a device that *is* the operator but runs no node (the rollout gate is off, no chain client yet, or the same identity's other device runs it), it returns `NotOnThisDevice`. | `isOperator` compares public keys, so one identity on two devices is "the operator" on both. The screen shouldn't claim a node is running just because the key matches. |
| D6 | *Try now* skips the current backoff wait, on the operator's device only. No proposal, no signature. | The backoff reaches five minutes (`RECONNECT_MAX`). An operator who has just fixed their network shouldn't have to wait it out. Reconnecting changes nothing the group agreed to, so there's nothing for anyone to sign. |
| D7 | The screen reads the nodes through a small interface, `GroupNodeStatusSource`, kept apart from `GroupLightningRepository`. `DedunNavHost` implements it over a `MutableStateFlow<GroupLightningNodes?>`. | The repository is database-only and is built before the nodes exist. The nodes are rebuilt whenever `groupChainClients` changes, so the screen needs a flow of whichever instance is current, not a reference to one of them. |
| D8 | The group sees the link through the snapshot: a new optional `Snapshot.lsp: LspLink?` (connected or not, since when, and the cause if not). It's absent from older operators and ignored by older members (`ignoreUnknownKeys`, `explicitNulls = false`). | The snapshot is already the operator's claim about the node, sent through the room, and it's what members read the balance from. A connection status is the same kind of claim and belongs beside it. A separate event would be one more kind to gate, store and age. |
| D9 | The link is part of what makes a snapshot due, but only a change that has lasted `LINK_SETTLE` (30 s) counts, and at most one snapshot goes out per `LINK_REPORT_MIN` (1 min) for link changes. | Without that, a flapping LSP would send the room a message on every redial, and the backoff starts at 2 s. |
| D10 | Members see the age as well as the state: *as of 2 minutes ago*. A snapshot older than `2 × REPORT_EVERY` is shown as *The operator hasn't reported for 40 minutes*, whatever it says about the link. | A snapshot saying *connected* from an operator whose phone has been off for a day is the case that misleads most. `sentAt` is already there. Only the screen ignores it. |
| D11 | Nothing about the connection goes into the chat. | [group-lightning-transcript.md](./group-lightning-transcript.md) decides which operator messages earn a line. A drop every few minutes on a bad network is exactly the noise it keeps out. The wallet screen is where someone looks when a payment didn't go through. |

## Phase 0: what the LSP's connections actually do

**Half a day.** Nothing ships.

Against regtest (`testing/regtest/regtest.sh up`) and the first mainnet operator's
logs:

1. Confirm the order on both LSPs: `connectionState = ESTABLISHED`, then `connect()`
   returns true, then `PeerConnected`. Record how long the gap between them is.
   That gap sets `INIT_DEADLINE` in Phase 2: a connection that has had no `init`
   within it counts as a failed attempt.
2. Collect every drop reason the mainnet logs hold (`The group's node lost the LSP`,
   `could not reach the LSP`) and check that each one maps to a `DropCause`. If one
   doesn't, it gets a cause or goes into `Other`, and the table below is updated.
3. Check that eclair's `disconnect` API (`regtest.sh lsp disconnect -d
   nodeId=<group node>`) ends the group node's connection the way a real drop
   does. Phase 6's regtest run depends on it.

Results go under this heading, as in [member-deposits.md](./member-deposits.md).

**Ran**, against regtest (`GroupLightningRegtestJvmTest`, eclair 0.14.4) and the
lightning-kmp source. No mainnet operator logs were available on this machine.

1. The order holds. `Peer.connect` sets `ESTABLISHED` and returns true once the
   Noise handshake completes. `PeerConnected` is emitted when the LSP's `init`
   arrives and passes `validateFeatureGraph` (`Peer.kt:1245-1254`). The run logs the
   dial, then *peer is using features…*, then the node running. The log has no
   timestamps, so the gap wasn't measured. **One correction to D2:** an LSP that
   hangs up on our features sends its own `init` first, so `PeerConnected` fires
   before that hangup, and *connected* can show for a moment before *can't reach*.
   That is still more honest than the handshake. **No `INIT_DEADLINE`:** cutting a
   connection that never sends `init` would change how the node connects, not only
   what it shows. The link stays *connecting* until `init` arrives or the socket
   closes.
2. lightning-kmp gives four closing reasons:
   - `ConnectionRefused`: every socket failure, including timeouts and DNS, wrapped
     by `openSocket`;
   - `ConnectionClosed`;
   - `Unknown`;
   - none: our own `disconnect`, a failed or timed-out handshake, or an invalid
     feature graph.

   The reconnect loop cancels itself before any disconnect of ours, so in the loop
   *none* means the handshake. `ClosedByUs` becomes `HANDSHAKE`, and
   `CLOSED_BY_LSP` is added for a connection the LSP ended with features agreed.
   The Tor check now throws its own `TorWithoutOnion`, so it doesn't have to be
   recognised by its message.
3. Phase 6 drives it. `lsp disconnect -d nodeId=…` ends the group node's
   connection and reads as `CLOSED_BY_LSP`, after a connection of about 100 ms in
   the run.

## Phase 1: the status, pure

**One day.** Dark.

- `managers/GroupLightningNodeStatus.kt`, new:
  ```kotlin
  sealed interface GroupLightningNodeStatus {
      data object NotOnThisDevice : GroupLightningNodeStatus                     // D5
      data class NotRunning(val reason: Refusal) : GroupLightningNodeStatus      // D3
      data object Starting : GroupLightningNodeStatus                            // provisioning the pool
      data class Running(val term: Long, val link: LspLink) : GroupLightningNodeStatus
  }
  sealed interface LspLink {
      data class Connecting(val attempt: Int) : LspLink
      data class Connected(val since: Instant) : LspLink
      data class Unreachable(val cause: DropCause, val detail: String, val failures: Int, val retryAt: Instant, val lastConnected: Instant?) : LspLink
  }
  enum class Refusal { NOT_OPERATOR, ROOM_NOT_SYNCED, NO_SEED, POOL_SHORT, NO_ONCHAIN_WALLET }
  ```
- `DropCause` and `GroupLightningNode.dropCause(ours, theirs, closed)`, next to
  `dropReason` and fed by the same inputs. `dropReason` stays as the detail
  string, so the existing tests keep passing.
  - The missing-feature branches → `FeatureMismatch`.
  - `TcpSocket.IOException.ConnectionRefused`, a timeout or an unresolved host →
    `Unreachable`.
  - A failed `check` for the onion address → `TorWithoutOnion`.
  - A Noise failure → `Handshake`.
  - `CLOSED(null)` → `ClosedByUs`.
  - Everything else → `Other`.
- Tests, `GroupLightningNodeStatusJvmTest`: every `dropReason` case in
  `GroupLightningNodeJvmTest` has a matching `dropCause`. Each Phase 0 reason maps
  to the cause the table gives it.

## Phase 2: the node keeps its status

**Two days.** Dark: nothing reads it yet.

- `PeerHandle` gains `suspend fun awaitInit(): Boolean`. It suspends until the
  current connection's LSP `init` arrives (true) or the connection closes first
  (false). The real handle builds it on the `lspInit` flow it already keeps
  (`GroupLightningNode.kt:592`). `FakeGroupNode` gets an `inits` channel beside
  `drops`, so a test can hold a connection at "handshaken, no `init`".
- `GroupLightningNode`:
  - `val status: StateFlow<GroupLightningNodeStatus>`, derived from `_state` plus
    a private `_link: MutableStateFlow<LspLink>`. `State.Refused` carries a
    `Refusal` (D3). The five `State.Refused("…")` sites in `start()` each name
    their enum value. The 26 test references are updated to match.
  - `start()` sets `Starting` before `transport.awaitPool` and `Running(term,
    Connecting(1))` before the first `connect`.
  - `stayConnected` writes `_link`:
    - `Connecting(attempt)` before each dial;
    - `Connected(now())` once `awaitInit` returns true within `INIT_DEADLINE`;
    - `Unreachable(cause, detail, failures, retryAt = now() + wait, lastConnected)`
      before each `delay(wait)`.
    The log lines stay. They're how the mainnet run was debugged.
  - `fun reconnectNow()` (D6). The loop's `delay(wait)` becomes a `select` on the
    delay and a `Channel<Unit>(CONFLATED)` that `reconnectNow` sends to. It does
    nothing unless the link is `Unreachable`, so a tap during `Connecting` can't
    stack a second dial (`Peer.connect` refuses one anyway, and logs a warning).
    A connection forced this way still counts against `failures`. *Try now* skips
    one wait. It doesn't reset the backoff.
- `GroupLightningNodes`:
  - `nodes` becomes a `MutableStateFlow<Map<String, GroupLightningNode>>`. Every
    write in `refresh` and `stop` goes through `update`.
  - `fun statusOf(chatRoomId: String, wallet: Long): Flow<GroupLightningNodeStatus>`
    flat-maps the map to that node's `status`, or to `NotOnThisDevice` when there
    is none.
  - The `Refused` log line keeps logging, now with the enum's name.
- Tests, in `GroupLightningNodeJvmTest`, with `FakeGroupNode`:
  - a start with a short pool reads `Starting`, then `NotRunning(POOL_SHORT)`;
  - a node that isn't the operator reads `NotRunning(NOT_OPERATOR)`;
  - a dial that handshakes but gets no `init` reads `Connecting` and then
    `Unreachable`, not `Connected`;
  - a drop reads `Unreachable` with the drop's cause, `failures = 1` and `retryAt`
    set from `reconnectAfter`, then `Connected` again after the next dial;
  - `reconnectNow` during a 10-minute wait dials at once and leaves `failures`
    counting;
  - `reconnectNow` while connected does nothing;
  - `stop()` reads `NotRunning`, or `NotOnThisDevice` once `GroupLightningNodes`
    drops the node;
  - `statusOf` follows a node that `refresh` adds and removes.

## Phase 3: the status reaches the screen

**One day.** Dark: the state carries it and the screen ignores it.

- `repository/GroupNodeStatusSource.kt`, new (D7):
  ```kotlin
  interface GroupNodeStatusSource {
      /** This device's node for the wallet, or null when this device isn't its operator. */
      fun observe(chatRoomId: String, chain: Chain, wallet: Long, isOperator: Boolean): Flow<GroupLightningNodeStatus?>
      fun reconnectNow(chatRoomId: String, wallet: Long)
  }
  ```
- `DedunNavHost`:
  - keeps `val groupLightningNodesFlow = remember { MutableStateFlow<GroupLightningNodes?>(null) }`
    and sets it beside the existing `remember(groupChainClients)`. It resets to null in
    the `DisposableEffect` that stops them;
  - builds a `NodesStatusSource(groupLightningNodesFlow)` once;
  - passes it to `GroupLightningWalletScreen`, which passes it to the view model's
    factory.

  While the nodes are null (rollout off, no chain client yet), an operator device
  reads `NotOnThisDevice`.
- `GroupLightningWalletUIState.Opened` gains `nodeStatus: GroupLightningNodeStatus? = null`,
  and `Earlier` the same, for an earlier wallet this device still runs.
- `GroupLightningWalletViewModel.watchReports` combines in
  `statusSource.observe(…, isOperator)` and copies it into `Opened`. It also gains
  `fun reconnectNow()`.
- Tests, `GroupLightningWalletViewModelJvmTest`, with a fake source:
  - the operator's state carries each status the source emits;
  - a member's state carries null, and the source is never asked for another
    device's node;
  - `reconnectNow` reaches the source for the current wallet only.

## Phase 4: the operator sees it

**Two days.** **The first release an operator sees.**

- `GroupLightningWalletScreen`, the operator card. Under *Run by you, term N*, a
  `NodeStatusRow(status, onTryNow)` replaces the generic *The operator's device
  runs the node* line on the operator's own device. It's a `ListItem`, and its
  icon is `Decorative` because the headline says the same thing:

  | status | icon, colour role | headline | supporting |
  |---|---|---|---|
  | `Running`, `Connected` | `Icons.Default.CloudDone`, `primary` | *Connected to the LSP* | *Since 14:02* (today) or *Since 3 Nov* |
  | `Running`, `Connecting` | `CircularProgressIndicator` (small) | *Connecting to the LSP* | *Attempt 4* once past the first |
  | `Running`, `Unreachable` | `Icons.Default.CloudOff`, `error` | *Can't reach the LSP* | the cause's sentence, then *Trying again in 32 seconds*, then the detail in `SelectionContainer`, `bodySmall` |
  | `Starting` | progress indicator | *Starting your node* | *Waiting for enough of the group to be ready to sign* |
  | `NotRunning(…)` | `Icons.Default.PowerOff`, `onSurfaceVariant` | *Your node isn't running* | the refusal's sentence, then *This device checks again every minute* |
  | `NotOnThisDevice` | `Icons.Default.PhonelinkOff`, `onSurfaceVariant` | *Your node isn't running on this device* | *Open Dedun on the device that runs it, or step down so somebody else can* |

  For `Unreachable`, a `TextButton` reads *Try now* (D6). It's disabled for
  `RECONNECT_FIRST` after a tap, so repeated taps don't each dial.
- The countdown re-reads `retryAt` once a second, through a `produceState` that
  ticks only while the row is `Unreachable` and on screen. It never animates, so
  `reducedMotion` has nothing to drop.
- The Balance card, on the operator's device only: when the node isn't
  `Connected`, one `bodySmall` line under the figures reads *Payments wait until
  your node reaches the LSP*. The buttons stay enabled. Invoice requests and
  authorisations go through the room and are acted on once the node reconnects
  (`wakeOnRequests`), so disabling them would block something that does work.
- `EarlierWallet`: an earlier wallet this device runs gets the same row, compact
  (headline only).
- Strings, about twenty, in sentence case, into `strings.xml`. Durations through
  the existing `DelayText` helpers, where they fit.
- Tests:
  - `NodeStatusRowJvmTest` (as `LightningPaymentRowJvmTest`): each status renders
    its headline and supporting text; *Try now* only on `Unreachable`; the detail
    is selectable; the row is a 48 dp target where it's clickable.
  - `GroupLightningWalletScreen` shows no status row on a member's device.
  - `./gradlew :composeApp:m3Audit` stays within every budget: no `.dp` literal, no
    `Color(…)`, no title case, no untriaged `contentDescription = null`.

## Phase 5: the group sees it

**Two days.** Ships alone, a release after 4, so older members have had time to
update before the first operator sends the new field (it's ignored if they haven't).

- Wire, `WalletWire.kt`:
  ```kotlin
  @Serializable
  data class LspLink(val connected: Boolean, val since: Long, val cause: String? = null)
  ```
  This is the wire shape, so `cause` is the `DropCause` name and there's no
  detail. A raw socket message can carry a host or an address, and it stays on the
  operator's device. `Snapshot.lsp: LspLink? = null`.
- `GroupLightningNode.report`:
  - fills `lsp` from `_link`. `Connecting` counts as not connected, `since` is the
    last change, and `cause` is set only when `Unreachable`;
  - adds the link to what makes a snapshot due, under D9: a change counts once it
    has held for `LINK_SETTLE`, and link-driven snapshots are rate-limited to one
    per `LINK_REPORT_MIN`;
  - also reports from `stayConnected` when the link settles, rather than waiting
    for the next `handoverCheck` tick.
- `GroupLightningWalletScreen`, every device that isn't the operator, in the
  operator card under *Run by @alice, term N*:

  | the snapshot says | headline | supporting |
  |---|---|---|
  | `lsp.connected` | *@alice's node was connected to the LSP* | *As of 2 minutes ago* |
  | not connected | *@alice's node couldn't reach the LSP* | the cause's sentence, *As of 2 minutes ago* |
  | no `lsp` (an older operator) | the current *The operator's device runs the node* | *Last reported 2 minutes ago* |
  | older than `2 × REPORT_EVERY` (D10) | *@alice hasn't reported for 40 minutes* | *Payments may wait until they do* |
  | no snapshot yet | unchanged | unchanged |

  `Balance`'s *Reported by the operator in term N* gains the same age.
- Tests:
  - `WalletWire` round-trips a snapshot with and without `lsp`, and an older
    snapshot's JSON decodes with `lsp = null`;
  - `report` sends one snapshot for a drop that lasts a minute, none for a drop
    that recovers within `LINK_SETTLE`, and at most one per `LINK_REPORT_MIN`
    across a flapping link;
  - the snapshot never carries the detail string;
  - the member card renders each row of the table, with the stale rule taking
    precedence over the link.

## Phase 6: regtest, docs, and checking it by hand

**One day.**

- `GroupLightningRegtestJvmTest`:
  - after the node starts, `status` reaches `Running(_, Connected)` against the
    real LSP;
  - `regtest.sh lsp disconnect` (Phase 0, question 3) moves it to `Unreachable`
    with `failures = 1`, then back to `Connected`;
  - `reconnectNow` during a wait reconnects at once;
  - a member's device, given the operator's snapshot, reads `lsp.connected`.
- [group-lightning-wallet.md](./group-lightning-wallet.md), Phases 8 and 10: one
  paragraph each, pointing here.
- [README.md](./README.md): this document's row.
- By hand, on Android and desktop:
  - turn the network off and on under a running operator, and watch the row go
    *Connecting* → *Can't reach* → *Connected*;
  - tap *Try now*;
  - on a second identity in the room, watch the member card follow within a
    minute;
  - force-quit the operator, and watch the member card turn stale after ten
    minutes;
  - check in the dark and high-contrast schemes.
- This document's status line becomes **Built**, with a table of where the build
  chose differently.

## Where the build chose differently

| plan | build | why |
|---|---|---|
| `INIT_DEADLINE`: no `init` in time counts as a failed attempt (Phase 2) | none: the link stays *connecting* until `init` arrives or the socket closes | Phase 0: a deadline would change how the node connects, not only what it shows |
| causes `ClosedByUs` and five others (D4) | `HANDSHAKE` in its place, plus `CLOSED_BY_LSP` | Phase 0: in the reconnect loop, no reason means the handshake. A connection the LSP ends with features agreed is the commonest drop and needed its own words |
| `awaitDisconnect` returns the reason string | it returns an `LspDrop` (cause and detail), and `dropReason` is still the detail | the cause needs the socket's exception type and both sides' features, which only the real handle has |
| the wait is a `select` on a delay and a channel | `withTimeoutOrNull(wait) { skipWait.receive() }`, draining any earlier tap first | same effect, one call |
| `observe(chatRoomId, chain, wallet, isOperator)` | `observe(chatRoomId, wallet)`. The view model only asks on the operator's device | the nodes run on one chain, and asking is the view model's decision |
| a stopped node | `NotRunning(reason = null)` | a stop and a refusal look the same to the operator, except for the reason |
| *Try now* disabled for `RECONNECT_FIRST` after a tap | disabled until the status changes | the next status (*connecting*) arrives within milliseconds, so a timer would be dead code |
| the `Balance` card's *Reported by the operator* gains the age (Phase 5) | the age is on the members' operator card only | the operator's own device shows its live status. Saying the age twice on a member's screen added nothing |
| — | the members' row only for a snapshot from the current operator's term | an older term's snapshot describes somebody else's node |
| — | a settled link back where the room last heard it keeps the reported time | without this, a recovered blip moved `since` and sent a snapshot anyway |
| a test that the screen shows no row on a member's device | the view model test that a member's state carries no node status, and never asks for one | the screen shows the row only from that state |

## Order and size

| phase | touches | ships |
|---|---|---|
| 0 | this note, a spike on both LSPs | nothing |
| 1 | `GroupLightningNodeStatus`, `DropCause`, pure | dark |
| 2 | `GroupLightningNode`, `PeerHandle.awaitInit`, `GroupLightningNodes`, `FakeGroupNode` | dark |
| 3 | `GroupNodeStatusSource`, `DedunNavHost`, the view model and UI state | dark |
| 4 | the operator card, the balance note, strings | **the operator's release** |
| 5 | `Snapshot.lsp`, `report`'s due rule, the member card | **a release after 4** |
| 6 | the regtest run, the docs, the hand check | last |

About a week and a half for Phases 0-6. Phases 1-3 can land as one commit or three,
since none is visible. **4 doesn't wait for 5.** The operator is the one person who
can fix a dead connection, so their screen comes first. **5 doesn't ship with 4**,
so that a release has passed in which every member's app ignores `lsp` (D8).

## What this does not do

**It does not keep the node running in the background.** On Android the node runs
while the app does (`GroupLightningNodes`' own comment). If the operator puts the
app away, members will see *hasn't reported for 40 minutes*, which is accurate. A
foreground service is still the next piece of work there, and it would also be the
natural place for a system notification when the link has been down for a while.

**It does not notify anyone.** No push, no chat line (D11), no badge. The status is
there for whoever opens the wallet screen. Alerting is a decision about thresholds
and about who gets woken up, and it belongs to the foreground-service plan.

**It does not show the Electrum connection.** The node also needs the chain, and an
Electrum outage looks to the operator like payments that don't confirm. That's
`GroupChainClients`' status and a parallel row. It's small and worth doing, but it's
a different connection with a different fix.

**It does not let members reconnect the operator's node, or vote on it.** *Try
now* is local (D6). A member who thinks the operator is absent already has *Run this
wallet on my device*.

**It does not make the link trustworthy.** `lsp.connected` is the operator's claim,
as the balance is. A hostile operator can say *connected* and answer nothing. The
co-signers' checks are still the fence, and this is only a status line.

## Appendix: what was considered and rejected

**Reading `Peer.connectionState` from the screen.** It's one flow and would show
something in an afternoon. But it says `ESTABLISHED` before features are checked
(D2). It knows nothing about the backoff, a refused start or a node that isn't on
this device. And the screen would need a reference to a lightning-kmp `Peer`, which
nothing outside `lightningKmpPeers` has today.

**A group-signed or separate status event.** That's another kind to gate, store,
age and teach the transcript to ignore, for a fact the snapshot is already sent to
carry (D8). It also doesn't need a quorum: nobody agrees to a connection.

**Showing the raw drop reason as the headline.** It's the most precise thing we
have, and it's what explained the `simple_taproot_channels` drop. But it
isn't in the catalogue, it can't be translated, and over the wire it could carry an
address (D4). It's kept as selectable detail, on the operator's device only.

**Disabling *Receive* and *Send* while the node is offline.** A request made then
is answered when the node reconnects, so disabling the buttons would stop something
that works. A one-line note does the job (Phase 4).

**A snapshot on every link change.** The backoff's first wait is 2 s. On a bad
network that's a room message every few seconds, sent to every member's device.
D9's settle-and-rate-limit costs at most a minute of staleness, and a member
looking at the screen can't act any faster than that.
