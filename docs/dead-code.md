# Unreferenced code in the sync and relay stack

An inventory of code nothing calls, found while building the long-running chat
sync (see [long-running-sync.md](./long-running-sync.md)). Some of it was
orphaned by that change; most of it was already dead and only became visible
because the subsystem was being read closely.

None of it is removed yet. It is written down rather than deleted because
several items are one decision away from being wanted, and that decision is not
the sync change's to make.

## How this was checked

Every claim below is "the identifier appears exactly once in
`composeApp/src`, at its own declaration", verified with a script that tokenises
every `.kt` file outside `build/` and counts occurrences:

```bash
grep -rn "\bidentifierName\b" --include=*.kt composeApp/src | grep -v '/build/'
```

Line numbers below were correct at `f5eb744` and drift with every edit to those
files; treat them as a hint and confirm with the grep.

Two things that method cannot see, so each item was also read in context:

- **Room DAOs** are called through generated code as `database.xDao().method()`,
  so the method name does appear at the call site and a genuinely unused DAO
  query still counts as one occurrence. The DAO section below is therefore the
  least certain.
- **Compose** entry points and `@Composable` functions invoked from `setContent`
  or navigation graphs may be reached without a textual reference.

## Orphaned by the live-sync change

**`ChatRoomListViewModel.scheduleSynchronization`** —
[ChatRoomListViewModel.kt:80](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/ChatRoomListViewModel.kt:80)

No longer called by `initiate()`. It was kept as the entry point for a
user-initiated refresh, which is the one thing the live tier does not answer —
but no refresh affordance exists in the UI, so today it is unreferenced.

It also now duplicates `LiveSubscriptionManager.queueCatchUpSynchronization`,
which queues the same two negentropy requests on every foreground, including the
first one after launch. Drift between the two is harmless (a negentropy request
is keyed on a hash of its filter, and both are valid reconciliations), but there
is no reason for two copies.

**Decide one of:** wire a pull-to-refresh to it, or delete it. Deleting it also
orphans `nostrRepository` in that view model, which ripples into its `factory`
and into `HomeScreen` — the reason it was left alone rather than removed in the
same commit.

## Dead on arrival, in the relay stack

Each of these predates the sync work.

| what | where | note |
|---|---|---|
| `RelayPool.removeRelays` | [RelayPool.kt:123](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelayPool.kt:123) | never called; `changeRelays` and `closePool` cover every path that removes a relay |
| `RelayPool.hasRelays` | [RelayPool.kt:171](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelayPool.kt:171) | never called |
| `RelayPool.transformWhileEventsAreIncoming` | [RelayPool.kt:486](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelayPool.kt:486) | private, never called. Superseded by `completeOnSubscriptionEnd`, which ends a flow on EOSE/CLOSED/NEG-ERR rather than on "the last message was not an event" |
| the commented-out publish gate | [RelayPool.kt:547-560](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelayPool.kt:547) | the only thing keeping `import kotlinx.coroutines.flow.transform` ([:30](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelayPool.kt:30)) alive |
| `RelaysSocketManager.clearRelayPools` | [RelaysSocketManager.kt:100](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt:100) | private, never called. Nothing tears the pool down on sign-out |
| `RelaysSocketManager.tryConnectingToAllRelays` | [RelaysSocketManager.kt:131](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt:131) | never called; the only caller of `RelayPool.tryConnectingToRelay`, which is otherwise dead too |
| the commented `tryConnectingToUserRelay` | [RelaysSocketManager.kt:139](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt:139) | |
| `NostrIncomingMessage?.verifyOrThrow` | [NostrIncomingMessage.kt:59](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrIncomingMessage.kt:59) | never called. It also treats any NOTICE as a failure, which is wrong for the reason `filterByEventId` documents — a NOTICE has no subscription id and reaches every collector |
| `String?.decodeFromJsonStringOrNull` | [CommonJson.kt:37](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/serialization/CommonJson.kt:37) | never called |
| `NostrSocketClientImpl.compressMessage` | [NostrSocketClientImpl.kt:368](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClientImpl.kt:368) | already carries `@Suppress("unused")`. Outgoing compression is not a thing any relay here asks for |
| `DefaultNostrSocketClientFactory.create(httpClient = …)` | [NostrSocketClientFactory.kt](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClientFactory.kt) | the overload taking an explicit `HttpClient` has no caller; everything goes through the interface method and `defaultSocketsHttpClient` |

### `RelaysSocketManager.userRelays` is a field that is never written

[RelaysSocketManager.kt:52](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt:52)

Worth separating from the table, because it does not read as dead — it reads as
a bug.

```kotlin
private val userRelays = mutableSetOf<RelayDTO>()   // nothing ever adds to this
```

The `userRelays` at [:83](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt:83)
is a *different, shadowing local* inside `observeRelays`. The field itself is
only ever read, at [:107](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt:107):

```kotlin
return if (userRelays.isNotEmpty()) { ...publish to the user's relays... }
       else { ...publish to FALLBACK_RELAYS... }
```

So the single-argument `publishEvent` **always** takes the fallback branch, and
the user's own relay list is never used for publishing. Either populate the
field from `updateRelayPools` or delete it and the branch; leaving a permanently
false condition in place is the one option that keeps the bug.

### The unused halves of the socket protocol

`NostrSocketClient` declares five send methods. Only `sendEVENT` and
`sendMESSAGE` are ever called — everything else in the pool serialises its own
command with `OptimizedJsonMapper` and goes through `sendMESSAGE`.

| declaration | impl |
|---|---|
| `sendREQ` [:37](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClient.kt:37) | [:349](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClientImpl.kt:333) |
| `sendCLOSE` [:31](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClient.kt:31) | [:361](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClientImpl.kt:345) |
| `sendCOUNT` [:33](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClient.kt:33) | [:354](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClientImpl.kt:338) |
| `sendAUTH` [:29](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClient.kt:29) | [:365](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrSocketClientImpl.kt:349) |

Removing them also orphans `buildNostrREQMessage`, `buildNostrCOUNTMessage`,
`buildNostrCLOSEMessage` and `buildNostrAUTHMessage` in
`NostrOutgoingMessageBuilder`.

Two of the four are worth keeping for a named reason rather than out of habit:

- **`sendAUTH` is the NIP-42 hole.** `NostrIncomingMessage.AuthMessage`
  ([:34](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrIncomingMessage.kt:34))
  is parsed and then dropped — nothing anywhere handles an AUTH challenge. A
  relay that answers CLOSED with `auth-required` is currently retried with
  backoff forever and will never succeed. `sendAUTH` is half of the fix, so
  delete it only alongside a decision not to support authenticated relays.
- **`sendCOUNT` and `CountMessage`** ([:38](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/sockets/NostrIncomingMessage.kt:38))
  are a matched pair with no caller. `CountMessage` is parsed and admitted by
  `filterBySubscriptionId`, but since nothing sends a COUNT it can never arrive.
  Both go, or neither.

`sendREQ` and `sendCLOSE` have no such argument — the pool does not use them and
would not start.

## Dead all the way down: the publisher chain

**`NostrPublisherRepository`** —
[NostrPublisherRepository.kt:15](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/repository/NostrPublisherRepository.kt:15)

The whole class is unreferenced: nothing constructs it, and neither of its two
methods (`signPublishImportNostrEvent`, `publishRelayList`) is called. Publishing
goes through the broadcast queue in `SynchronizationViewModel` instead.

It matters beyond itself because it is the **only** consumer of
`CachingImportRepository.importEvents`. `RelayPool` and `RelaysSocketManager`
both take a `cachingImportRepository` constructor parameter, store it, and never
dereference it — and `SynchronizationViewModel` satisfies that parameter with
`NO_OP_CACHING_IMPORT_REPOSITORY`. So the entire caching-import seam is a
parameter passed from nowhere to nothing.

Removing `NostrPublisherRepository` and the two unused constructor parameters
would let `CachingImportRepository` go with them.

## Dead columns (need a migration)

`isRecommendedRelay` on both
[SynchronizeNostrEventRequest.kt:40](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/SynchronizeNostrEventRequest.kt:40)
and
[NegentropySynchronizeRequest.kt:39](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/NegentropySynchronizeRequest.kt:39)
is never written by any caller (every construction takes the `false` default)
and never read.

A different risk class from everything else here: these are Room columns, so
removing them is a schema change with a migration, not a delete. Cheap to leave;
worth folding into the next migration that touches those tables.

## Wider sweep, needing per-item judgement

The same sweep across all of `composeApp/src` turns up ~99 single-occurrence
function declarations. Most are outside the sync stack and were not read in
context. Two clusters stand out and are recorded here so the next person does
not have to re-derive them:

- **Unused DAO queries** — roughly 30, concentrated in
  `BroadcastNostrEventReceiptDao`, `BroadcastNostrEventRequestDao`,
  `UnsignedNostrEventDao`, `RepostedRelationDao`, `QuotedRelationDao`,
  `InReplyToRelationDao`, `ReactionDao` and `ZapDao`. Mostly
  `getAllX()`/`getXById()` shapes that look like they were written to round out
  a DAO rather than because something needed them. Cheap to delete and cheap to
  re-add, but see the Room caveat above: confirm each against the generated
  implementation, not just by grep.
- **Bech32/credential helpers** in
  [Credentials.kt](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/extensions/Credentials.kt) —
  `assureValidNpub`, `assureValidPubKeyHex`, `hexToNoteHrp`, `urlToLnUrlHrp`,
  `bechToBytesOrThrow`. A conversion toolkit where only some conversions are
  used. Worth keeping as a set if the intent is a complete encoding surface;
  worth deleting if not.

## Suggested order, if this gets done

1. `NostrPublisherRepository` + the two `cachingImportRepository` parameters +
   `CachingImportRepository`. Largest reduction, zero behavioural risk, and it
   removes a constructor parameter from two classes at the centre of the relay
   stack.
2. The relay-stack table above, plus the two commented-out blocks and the
   `transform` import they keep alive.
3. `userRelays` — but as a **fix**, not a deletion, unless publishing to the
   user's own relay list is deliberately not wanted.
4. `sendREQ`/`sendCLOSE`, keeping `sendAUTH` and `sendCOUNT` pending the NIP-42
   and COUNT decisions.
5. `ChatRoomListViewModel.scheduleSynchronization`, once there is a refresh
   affordance or a decision that there will not be one.
6. `isRecommendedRelay`, with the next migration that touches those tables.
