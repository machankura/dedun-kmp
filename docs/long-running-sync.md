# The long-running chat sync

Chat messages arrive because a subscription is open, not because a screen asked.
This note is what that means, what it replaced, and the handful of decisions
inside it that look arbitrary and are not.

Two subscriptions carry conversation, and they are held open for as long as the
app is in the foreground:

- **kind 1059**, NIP-59 gift wraps p-tagged to us — DMs, and the Marmot Welcome
  events that make us a member of a new group.
- **kind 445**, Marmot group events h-tagged with every group id we belong to.

The code is [`LiveSubscriptionManager`](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/LiveSubscriptionManager.kt),
built and scoped by `SynchronizationViewModel`.

## What this replaced

Every chat sync used to be a *pull*. A screen queued a request row, a pump in
`SynchronizationViewModel` drained it, the relay answered, the subscription was
closed at EOSE. Nothing arrived between pulls, so a message sent one second after
EOSE waited for the next time someone opened a screen.

| screen | queued |
|---|---|
| `ChatRoomListViewModel.scheduleSynchronization` | negentropy over `1059 #p=[me]`, then over `445 #h=[every group id]`, per DM relay |
| `ChatMessageListViewModel.scheduleSynchronization` | for an MLS room, negentropy over `445 #h=[this group]`; for a NIP-17 room, gift wraps p-tagged at the peer |

Neither runs on open any more. The first stays as the entry point for an
explicit user-initiated refresh — the one thing the live tier does not answer,
because it is the user saying they believe something is missing. The second now
does discovery only: if we do not hold a participant's kind-10050 we cannot
address a message to them, and that is worth resolving when a chat is opened
rather than whenever a background pass reaches it.

## Why the old pipeline could not simply stop closing

Five properties, each of which had to be undone deliberately. None was wrong;
all of them assume a subscription is short.

**1. EOSE ends the flow.** `completeOnSubscriptionEnd` is what makes a collector
finish at all — the socket's `incomingMessages` is hot, and a filtered view of it
never completes on its own. `RelayPool.openLiveSubscription` is `query()` without
it: for a live subscription EOSE is only the boundary between stored history and
the live tail, and CLOSED is the only genuinely terminal message.

**2. Every subscription was force-killed at 120 seconds.** `SUBSCRIPTION_TIMEOUT`
exists so a relay that goes quiet cannot park a slot forever. It applies to the
queue, and nothing else.

**3. There are four subscription slots, total, across all relays.**
`subscriptionSlots` is a `Semaphore(4)`. A permanently-open subscription is a
permanently-held permit, so the live tier deliberately does not draw from it. The
two budgets have to be read together against what a relay tolerates per
connection (commonly ~20), not separately.

**4. The queue row was the subscription identity and the provenance record.** Both
original `saveNostrEvent` overloads take a request object, use its `relayURL` and
`level`, and flip its status to "processed". A live subscription has no row and
never finishes, so there is a third overload carrying relay and level itself.

**5. Nothing reconnected.** `NostrSocketClientImpl` caught a failure, called
`close()`, and fired a callback that only flipped a boolean nobody read. That was
survivable *only* because every subscription was short: the next queued request
opened a fresh socket on its way out. See "Reconnecting" below.

There was a sixth, quieter one. `_incomingMessages` was a rendezvous
`MutableSharedFlow` emitted into from a coroutine launched per message, so
messages reached collectors in scheduling order rather than wire order, and an
emit with every collector busy blocked on the slowest. It now has a 256-message
buffer and is emitted into inline, on the reader. That also retired a 75ms sleep
before every EOSE, which existed to *hope* the events preceding it had already
been delivered.

## How it holds together

### Reconnecting

A socket that drops now comes back on its own, with exponential backoff (1s
doubling to 60s, plus up to 25% jitter — every relay drops at once when the
network does, and without jitter they return in lockstep). `autoReconnect` is off
by default and owned by the pool, which turns it on for exactly as long as it is
retaining a subscription for that relay: reconnecting a socket nobody is
subscribed on is battery spent on nothing.

`RelayPool` retains the REQ text per `(relay, subscription id)` and replays it
when that relay's socket is re-established. A relay answers a repeated REQ on the
same id by *replacing* that subscription's filter, so replay is a send rather
than a close-and-reopen — and the collector, attached to the socket **client**
rather than to a session, simply starts receiving again. A live subscription
therefore needs no drop handling of its own.

Negentropy is deliberately not retained. NEG-OPEN carries a fingerprint of the
local set and each round depends on the last, so replaying one mid-exchange would
reconcile against a conversation the relay is no longer having.

### The filters, and their timestamps

The two kinds need opposite treatment, and it is visible in our own outbound code.

Gift wraps are stamped with `TimeUtils.randomWithTwoDays()`
([`DatabaseChatRepository.kt:344`](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseChatRepository.kt:344)),
so a wrap published *now* can carry a `created_at` two days old. **A `since`
anywhere near the present would silently drop a large fraction of genuinely new
messages** — and "some DMs just don't arrive" is the worst failure mode to debug.
There is none.

Group events use `TimeUtils.now()`
([`MarmotOutboundDao.kt:578`](composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/MarmotOutboundDao.kt:578)),
so a watermark would be safe there. It still is not used: `limit` already bounds
the initial burst, and a watermark would have to be recomputed every time a
chunk's membership changed.

`limit` does the bounding instead, because NIP-01 scopes it to the initial query
— the stored events sent before EOSE — and explicitly not to the stream that
follows. So it caps what a reconnect costs without touching the live tail at all.
100 for gift wraps; 500 for a group chunk, which covers up to 100 conversations.

`#h` is chunked 100 group ids to a subscription. One subscription per group would
be simpler, but every Marmot group here lives on the same DM relay set, so
carrying many ids in one filter costs a fraction of the subscriptions.

### Following group membership

The group filter is derived from `chatRepository.observeChatRoomListByPublicKey`
— the same Flow the chat list screen renders, collected at wallet scope so it
keeps running with no chat screen open. Rooms are filtered to those with MLS
state (a NIP-17 room has none and is served by the gift wrap subscription), minus
any we have left or deleted; ids are sorted, de-duplicated with
`distinctUntilChanged`, and debounced 500ms.

Deriving it from the table rather than from the join sites is the decision that
keeps this correct, because a group id can appear four ways and only one of them
is somewhere anyone would think to call a subscribe function:

- we create a group (`MarmotOutboundDao`),
- we are added to one — the Welcome arrives inside a gift wrap and is processed
  deep inside `NostrDao.storeNostrEvent`, on the *inbound* path,
- membership shifts under us via a commit
  (`MarmotInboundManager.processGroupMembershipChanges`),
- we leave, or the room is deleted.

It also closes the loop: **a Welcome lands on the gift wrap subscription → a
`ChatRoom` row is written → the Flow re-emits → the group filter widens → the
first message in that new group arrives without anyone opening a chat.**

Reconciling is an update, not a rebuild. A chunk that already has a subscription
gets a repeated REQ under the same id, so adding a group does not interrupt
delivery on the groups already being watched. Chunks that no longer exist are
`cancelAndJoin`ed — the CLOSE goes out from the coroutine's `finally`, and
returning before it lands would let a later reconcile open a subscription on that
id which the old one then closes out from under.

### Lifecycle

`AppLifecycle` is a singleton `StateFlow<Boolean>` fed by a `LifecycleEventObserver`
in `DedunNavHost`. A singleton rather than something threaded through the
composition, because the consumers are application-scoped coroutines that outlive
any screen. It defaults to foreground: on a platform where the observer is not
wired up, "always on" is the behaviour that predates it, and a subscription that
never opens is far worse than one that stays open too long.

`collectLatest` over that flow is the whole mechanism. Backgrounding cancels the
block holding the subscriptions and each `finally` sends its CLOSE.

Returning does two things before anything else:

1. **`RelayPool.reconnectAll`** tears every socket down and immediately rebuilds
   it. Trusting the connection is the mistake here — a socket that was open when
   the OS suspended the process reports itself connected on the way back while
   being functionally dead. Rebuilding eagerly rather than leaving it to the next
   send is what makes it a *re*-connection, so retained subscriptions are replayed
   and collectors still attached from before the gap resume.
2. **A catch-up reconciliation.** A live subscription answers "what is new since I
   connected"; negentropy answers "what do you have that I don't". A background
   gap is exactly the question only the second can answer — `limit` on the
   re-opened subscriptions is a window, not a guarantee.

### What negentropy is still for

Live subscriptions replaced *polling*, not *reconciliation*. The queue and its
three pumps are unchanged, and negentropy remains the right tool for first
login, the foreground catch-up, "load older messages", and repairing a relay that
was unreachable while we were online.

## Two things found while building this

**The `"sent-messages"` filter could never match anything.** It asked for kind
1059 with `authors=[userPublicKey]`, but gift wraps are signed with a fresh
throwaway `KeyPair()`, so the pubkey is random and never ours. It was also
unnecessary: `createNip17ChatRoom` puts the user in their own participant list, so
we wrap a copy to ourselves and the account-wide `#p=[me]` subscription picks our
own sent messages up on every device. Deleted, in the view model and at both
sites in `NostrDao`.

**The negentropy `saveNostrEvent` wrote outside `storeNostrEventMutex`** while
the other overload held it. `storeNostrEvent` reads an event and then writes it
and its indexes, so two of those interleaving is a lost update — survivable while
a single queue was the only writer, not survivable with a live subscription
writing alongside a backfill.

## What is tested, and what is not

Every failure mode in this subsystem is silent — a filter that drops messages, a
subscription that stops being replayed, a group id that never reaches an `#h`
tag. None of them throws, and all of them present as "some messages didn't
arrive", later, on someone else's phone. So the tests are aimed at the decisions
rather than at the plumbing:

| file | pins |
|---|---|
| `RelayPoolSubscriptionTest` | retain on query, replay on reconnect, release on close, never replay negentropy, per-relay scoping — and that a live subscription keeps delivering after EOSE while a one-shot query still ends at it |
| `LiveSubscriptionReconcileTest` | the group filter following membership: widening in place rather than reopening, dropping a left group, closing when the last one goes, collapsing churn inside the debounce, and the CLOSED/back-pressure reopen behaviour |
| `LiveSubscriptionPlanTest` | the filter shapes, led by `since` being absent from the gift wrap filter |
| `RelayBackPressureTest`, `ReconnectBackoffTest` | the two pure decisions: which CLOSED reasons mean "ease off", and the backoff arithmetic including the exponent clamp |

They were checked by mutation rather than by passing: reintroducing a `since`,
making EOSE terminal, dropping the `leftGroupAt` filter, removing retention from
`query()`, reconciling by close-and-reopen, removing the debounce and removing
the back-pressure branch each produce failures.

Three things this does **not** cover, and could not without a real relay or a
database:

- **`NostrSocketClientImpl` itself.** The reconnect loop, the session-identity
  check on teardown, and the ordered inbound emission are exercised only through
  their extracted arithmetic. Testing them wants a fake `WebSocketSession`.
- **Anything downstream of `saveNostrEvent`.** Indexing, gift wrap unwrapping,
  Welcome handling and MLS decryption are Room-backed, and there is no sqlite
  driver on the JVM test classpath (see the note in `build-verification-commands`).
  The tests assert the event reaches the repository with the right relay and
  level, and stop there.
- **The app on a device.** Nothing here proves a DM lands with the chat list
  closed; that still wants the two manual checks — send yourself a message from
  another client, and have a second device add you to a group.

## Not done

- **Connectivity changes.** A network switch mid-foreground is only noticed by
  the socket's own reconnect loop, which handles the common case but cannot know
  the network changed underneath it. Wisp's `RelayLifecycleManager` is the model:
  observe connectivity, debounce, and suppress a network-change reconnect shortly
  after a resume so the two do not fire twice.
- **NIP-42 AUTH.** A relay that answers CLOSED with `auth-required` is treated as
  any other refusal and retried with backoff. It will never succeed. Worth
  knowing before pointing this at a relay that requires auth.
- **A router.** The original design called for one permanent collector per socket
  dispatching by subscription id prefix. With the inbound flow buffered and only a
  handful of live subscriptions per relay, a collector per subscription has the
  same properties for less machinery. If that count grows, the router is the next
  move.
- **`Relays.DefaultDMRelayList` is one relay.** Every per-relay budget above is
  currently a budget of one socket, which makes this cheap and makes a single
  relay outage total.
