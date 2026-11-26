# Notifications, and the sync that has to run for them

<!--
  Giza's note, as Giza wrote it at a5a802c5. Dedun's port follows its phases; where Dedun
  differs, docs/giza-second-batch.md, Phase 3, says so. Read "Giza" as the app it was written for.
-->

> **In Dedun.** The runtime is `DedunRuntime`, and it holds the shared repositories and the two
> engines. The wallets' own objects (Electrum, the group Lightning nodes, the chain watchers and
> Wavelength) stay in `DedunNavHost`, because a background pass files a signing proposal without
> them. The default identity for a pass is the profile's nostr key, with no Lightning node started.
> Every Dedun transcript type is classified, its wallet lines included: payments and payouts are
> group activity, which is off by default, and an Iceberg approval or an operator's equivocation
> needs you. Read marks and the ledger are schema 50. Stay connected is still a second foreground
> service beside `GroupWavelengthService`.

A plan for three things Giza cannot do today:

1. **Say that something arrived.** A message, a group that added you, or a signing session
   waiting on your approval appears only to somebody who opens the app and looks.
2. **Fetch while closed.** Messages sync only while the app is in the foreground. The moment
   it is backgrounded, the live subscriptions close, and nothing fetches again until it is
   reopened. So a notification would have nothing to report even if one existed.
3. **Know what you have read.** No room records how far you have read, so there is no unread
   count, nothing to clear a notification against, and no rule for which messages are new to
   *you* rather than new to the database.

The first depends on the second, and both depend on the third, so the plan builds them in
reverse: read state, then notifications whenever the app process is alive, then a background
pass that gives the process a reason to be alive.

Read [long-running-sync.md](./long-running-sync.md) first. Everything that delivers a message
here is built on it, and two of its decisions are decisions here too: derive what to watch from
the table rather than from the places that write to it, and never put a `since` on a gift-wrap
filter. Then read [multiple-profiles.md](./multiple-profiles.md) for why a profile switch is a
restart, which is why the background sync serves one profile (D6).

**Built** on `claude/giza-notifications-background-sync-1a8e0b`, against `2563572e`, on
2026-11-22, one commit per phase (`5683199c` to the commit that adds this header; Phase 10 is two).
The background half is behind `ExperimentalFeatures.backgroundSync`, on in development builds and
off in release; read state and notifications while the app runs are not flagged. jvmTest 1,774, all
passing (1,837 after merging `giza`); `m3Audit`, `brandAudit` and `walletAudit` at their budgets; `compileDebugKotlinAndroid`
builds and the debug manifest merges; `./gradlew check` passes. The phases are kept as written, because they are the
reasoning. Where the build chose differently:

| what the plan said | what it turned out to be |
|---|---|
| Phase 2: `SyncEngine` and `OutboundEngine` moved out of their view models | **the view models stay, built once by `GizaRuntime`.** Neither ever used `viewModelScope` -- both run on the scope they are given -- so constructing them outside a `ViewModelStore` changed nothing about how they run, and a 950-line move bought nothing. `GizaRuntime.synchronization` and `.notary` are them |
| (not in the plan) | **`SovereignWalletViewModel` resets the identity to null when it is created.** The flow it writes is the runtime's now and outlives an activity, so an activity recreated over a living process would otherwise start with the last one's identity set and skip the startup that chooses it |
| Phase 2: `IdentityStore`, extracted from `listIdentities` | `DefaultIdentity.load` (Phase 9), the reading half only: no key-file migration and no credential repair, which belong to a launch somebody is watching. A seed missing its credential is still found by derivation |
| Phase 3: a manual `Migration(46, 47)` | an `AutoMigration` with `ReadMarkMigrationSpec`, whose `onPostMigrate` runs the `UPDATE`. Room generates the shape. **47 → 48** since the merge: the group's direct messages (docs/group-threshold-ecdh.md) took 47 on `giza` first, as the phase said might happen |
| Phase 5: the switches in the identity's `UserPrefs` | **on `NotificationLedger`**, folded into the notifications schema (47 when built, 48 since the merge; neither has shipped) in Phase 5. `UserPrefs` lives in the wallet library's submodule. Before an identity is armed there is no row, and the settings screen says why there is nothing to set |
| D7: approval lines are someone else's | **they are written as the user's own** (`isUserMessage = true`, from `announce`'s actor), so the classifier exempts Needs you from the own-line rule, and the policy never drops one as already read. `NotificationClassifierJvmTest` pins both |
| D9: hide every line's text when Show message text is off | only a message's. A Needs you or Group activity line is the app's own sentence, never what anybody wrote |
| D3: armed after the first sync settles | armed once the identity's negentropy queue has been empty for **30 seconds without a break** -- the only signal that a first sync is over. Arming also marks every room read up to its newest line, so a new device starts with nothing unread |
| D6: a notification names the profile when there are several | **not done**: `RoomNotification.profileName` is always null |
| Phase 6: sender names from the room's participants | and, for a sender who is not a participant row (a NIP-17 peer), from their `Profile`. `NotificationDispatcherJvmTest` found the gap |
| Phase 0, check 4 | a group message filed first as a placeholder and decrypted later keeps the placeholder's id, which the cursor may have passed: it counts as unread and posts nothing. Kept as a known gap |
| Phase 7: a Notifications screen pushed from settings | from the **Profile tab**, the only list of settings the app has; shown only for a profile that can sign |
| Phase 8: `isVisible` and `holdConnections` | as planned; `holdConnections` is visible, or any of a set of named reasons to keep connected (`desktop`, `grace`, `stayConnected`, `backgroundPass`) |
| D5: a quick pass of REQs to EOSE and a full pass of negentropy | **one pass that runs the app's own engines**: it activates the default profile when no screen has and holds the connections, which makes `LiveSubscriptionManager` reconnect, open its subscriptions (whose `limit` is the quick catch-up) and queue the negentropy reconciliation (the full one), exactly as returning to the foreground does. Quick and full differ in their minimum wait and their deadline. The second fetch after a Welcome is the live tier's own room-list loop |
| D5: wait for the outbound queue to empty | wait for it to **stop moving** for five seconds. A row that can never be sent would otherwise hold every pass to its deadline -- nine minutes on Android |
| (not in the plan) | a `Disabled` outcome: a profile whose notifications are off is not synced in the background |
| (not in the plan) | **Android's application starts not visible**, until an activity's `ON_START` says otherwise. A process started for the worker has no activity, and the pass declines to run while the app is "visible" |
| Phase 10: the worker returns at once while Stay connected runs | it runs anyway; the reconciliation it queues is the one thing the live tier cannot do, and holding the connections twice is harmless |
| Phase 10: Stay connected's switch | stored in Android `SharedPreferences` -- a device's choice, not a profile's -- and the service started only from `MainActivity`, because Android refuses a foreground service started from the background |
| Phase 12: Open Giza, Mute all for an hour, Quit | **Open window** and **Quit**. `m3-title-case.py` reads "Open Giza" as title case; muting everything for an hour is the master switch already |
| Phase 13: a reply runs the outbound engine | through `BackgroundSyncPass.withProfile`, under the pass's own lock so a reply and a sync never activate and deactivate the profile under each other. A reply for a profile other than the default, or than the one open, is refused, not sent as somebody else |
| Phase 15 | not built |
| (not in the plan) | merging `giza` brought `TYPE_GROUP_DM_FORGOTTEN`, which `NotificationClassifierJvmTest` refused until it was placed: silent, as `TYPE_INBOX_FORGOTTEN` is. The test doing what it was written for |
| (not in the plan) | the worktree's build needed `lightning-kmp-app` and its nested submodules initialised from the main checkout's copies, with their `local.properties` and the prebuilt native secp256k1 |

**Not done:**

- **Nothing on iOS has been compiled.** The iosMain actuals (`PlatformNotifier.ios.kt`,
  `BackgroundSync.ios.kt`, `BackgroundTime.ios.kt`, `BackgroundSyncSection.ios.kt`), the
  `MainViewController` change and `iOSApp.swift` were written on linux, where the iOS targets are not
  declared. The likeliest breaks are the Kotlin spellings of the UIKit and UserNotifications
  enums (`UNAuthorizationStatus…`, `UIApplicationState…`, `UIBackgroundRefreshStatus…`, the
  `…OptionNone` constants) and the Swift names of Kotlin's top-level functions
  (`PlatformNotifier_iosKt`, `BackgroundSync_iosKt`).
- **Phase 14's device run**, on every platform: nothing here has posted a notification on a phone,
  run a WorkManager period, been woken by BGTaskScheduler, or closed to a real tray.
- **Phase 0, check 3** (iOS file protection), which needs a device.
- **`ExperimentalFeatures.backgroundSync` stays**, as D12 says it must until the device run.
- **Part C**, which is the owner's decision.

Written against `2563572e`, on 2026-11-22. Paths starting `K/` are under
`composeApp/src/commonMain/kotlin/mobi/giza/compose/`.

---

## What Giza does today

| what | where | what it means here |
|---|---|---|
| The object graph — database, repositories, `RelaysSocketManager`, the sync and notary view models — is built in `remember { }` blocks inside a composable | `K/ui/composable/navigation/GizaNavHost.kt:170-290` | **Nothing can run without a UI.** A background job has no database or relay pool to use unless it builds a second copy, and a second copy would mean two Room instances on one file and two sockets per relay (D4) |
| `GizaGlobal` is a bag holding a `PlatformContext`, built inside `setContent` / `ComposeUIViewController` / `application { }` | `K/GizaGlobal.kt`, `MainActivity.kt`, `MainViewController.kt`, `desktop/Main.kt` | Same problem, one level up. On Android, `GizaApplication` builds its own `PhoenixGlobal` and `MainActivity` builds a second one |
| `AppLifecycle.isForeground` is fed by `ON_START`/`ON_STOP` in `GizaNavHost`, and `LiveSubscriptionManager.observe` closes every subscription when it goes false | `K/AppLifecycle.kt`, `K/managers/LiveSubscriptionManager.kt:265` | Correct for a phone. On desktop it probably also fires when the window is minimised (Phase 0, check 1), so a minimised desktop app may stop receiving too |
| Returning to the foreground runs `reconnectAll` and queues a negentropy catch-up over `1059 #p=me` and `445 #h=groups` on every DM relay | `LiveSubscriptionManager.runWhileForeground`, `queueCatchUpSynchronization` | **This is the background pass, minus the background.** Phase 9 runs the same work on a timer, with a deadline |
| Inbound events are opened and filed inside `nostrRepository.saveNostrEvent` → `NostrDao.storeNostrEvent`: a NIP-17 line by `persistInboundChatMessage`, a Marmot line by `persistMarmotChatMessage`, a Welcome by making the room | `K/database/dao/NostrDao.kt:1837`, `:2004` | Good news: **ingest needs no view model.** A headless pass that calls `saveNostrEvent` gets chat lines, rooms and ceremony steps exactly as the foreground does |
| Outbound — sealing gift wraps, encrypting Marmot inner events, publishing — runs in `NotaryViewModel`'s observers and `SynchronizationViewModel`'s broadcast pump | `K/ui/view/model/NotaryViewModel.kt:66-90` | A ceremony step that ingest queues as a reply sits unsent until a UI exists (D11) |
| `ChatMessage.id` is an auto-generated `Long` | `K/database/model/ChatMessage.kt` | A monotonic local cursor, for free (D2) |
| `ChatRoom.viewedAt` exists (from the timestamps trait) and nothing writes it | `K/database/model/ChatRoom.kt:144` | No read state at all |
| `ChatMessage.messageType` has about fifty values: `message`, `directMessage`, and the transcript lines of ceremonies, signing, rotation, repair, chronicle and governance | `ChatMessage.kt:170-470` | Most of these must **not** notify. Five kinds of `…ApprovalNeeded` must (D7) |
| The seed and credentials on iOS live in the keychain through `KeyChainHelper`, which uses `kSecAttrAccessibleAfterFirstUnlock` | `lightning-kmp-app/library/src/iosMain/.../KeyChainHelper.kt` (main checkout; the submodule is not initialised in this worktree) | A background task after first unlock can read the key. Phase 0, check 2 confirms which constant each item uses |
| `aux.db` on iOS is in `Documents` with no explicit file-protection class | `PlatformDatabaseBuilder.ios.kt` | The default class, "complete until first user authentication", allows background reads. Must stay that way (Risks) |
| marmot-kmp 2.3.0 ships MIP-05 (Marmot push): kinds 446–449, `MarmotPushCoordinator`, token gossip with owner proofs | `com.vitorpamplona.marmot.client.MarmotPushCoordinator` | The one standard route to real-time push for groups. Part C; not built by this plan |
| No notification code, no `POST_NOTIFICATIONS` permission, no WorkManager, no `BGTaskScheduler`, no `UIBackgroundModes` | `AndroidManifest.xml`, `iosApp/iosApp/Info.plist`, `composeApp/build.gradle.kts` | Everything platform-side is new |

---

## Decisions

### D1. Notify from the table, not from the wire

One `NotificationDispatcher`, in common code, observes `ChatMessage` rows and decides what
to post. It is never called by the code that receives events.

The reason is the one [long-running-sync.md](./long-running-sync.md#following-group-membership)
gives for deriving the group filter from the room list: a line can be written from at least
six places (the live subscription, the foreground negentropy catch-up, the background pass,
"load older messages", the chronicle, a commit that changes membership), and a notification
wired into one of them misses the other five. Deriving from the table catches all of them, and
it means every path a platform can wake us through (a worker, a background task, a push) only
has to get the event into the database.

It also keeps the decision testable without a phone: rows in, notifications out, through a fake
`PlatformNotifier`.

### D2. The cursor is the row id, never a timestamp

"New since last time" is `ChatMessage.id > cursor`, with the cursor stored per identity.

No timestamp works:

- A gift wrap's `created_at` is fuzzed up to two days into the past (`TimeUtils.randomWithTwoDays()`),
  which is why the gift-wrap filter has no `since`.
- A rumor's `created_at` is whatever the sender's clock said.
- "Load older messages", a chronicle and a reindex sweep all write *old* lines *now*.

The row id is assigned locally and increases monotonically. Phase 0, check 4 confirms that
`reconcileMarmotLine`'s upsert keeps an existing line's id rather than taking a new one,
since a line that is re-filed under a new id would notify twice.

The id alone over-includes backfill, so the policy (D7) also drops a line whose `createdAt`
is older than the room's read mark, or older than a horizon (72 hours). Those two rules
together exclude history without excluding a message that genuinely arrived late.

### D3. Armed after the first sync, never before

An identity's cursor is created at the current maximum id **once its first sync has
settled**: after sign-in sync, or for an identity that already exists on upgrade, at first
launch of the build that adds this. Until then, nothing for that identity notifies.

Without this, signing in on a new phone or reinstalling posts one notification per message
in your history. With it, the first notification is the first message that arrives after the
device knows what you already had.

### D4. One graph per process, owned by the process

The database, the repositories, the relay pool and the managers move out of `GizaNavHost`
into a process-wide `GizaRuntime` (Phase 2). The UI takes it. A worker, a background task
and a notification action take the same instance.

A second graph would mean:

- two Room instances on `aux.db`, whose invalidation trackers do not see each other's writes
  (KMP Room has no multi-instance invalidation off Android);
- two `RelayPool`s, so two sockets per relay;
- `storeNostrEventMutex` is a companion-object `Mutex`, so it covers one process but means
  nothing between graphs that each think they own the MLS state;
- and, worst, two writers ratcheting one room's MLS state, the failure the notary's
  "serially, in the order the query gives them" comment exists to prevent.

`GizaRuntime` is created lazily from a `PlatformContext`: by `GizaApplication` on Android
(where a worker's process starts too), by a Kotlin `object` reached from Swift on iOS, and by
`main()` on desktop after the passphrase gate. `GizaGlobal` becomes its constructor argument.

### D5. The background pass is the foreground catch-up, bounded

`BackgroundSyncPass.run(deadline)` does what returning to the foreground already does, then
stops:

1. If the app is visible, return. The live subscriptions already cover it.
2. Resolve the identity (D6). No signing key, no pass.
3. `reconnectAll`.
4. Fetch, in one of two strengths:
   - **quick**: open the same filters `LiveSubscriptionManager` uses (`giftWrapFilter`,
     `groupFilter` per chunk, `INITIAL_HISTORY_LIMIT`), and wait until each has seen EOSE or
     its relay has timed out. For iOS's app-refresh budget.
   - **full**: the negentropy catch-up `queueCatchUpSynchronization` queues, drained by the
     same pumps. For Android's worker and iOS's processing task.
5. If a Welcome made a new room during the fetch, widen the group filter and fetch once more,
   the same loop the live tier closes by observing the room list. Once, not until quiet.
6. Drain the outbound work that ingest queued (D11).
7. Wait for the dispatcher to reach the newest row, so the notifications are posted before
   the platform suspends us.
8. Close every subscription and record the outcome and time in the ledger (Phase 3), which is
   what the settings screen shows and what a "my notifications are late" report starts from.

Every step checks the deadline. A pass that is cut off has still filed everything it filed:
each event is written in its own transaction, as it is today.

Never a `since` on the gift-wrap filter, for the reason in D2. `limit` bounds the quick pass; a
quick pass on a busy account can miss a wrap whose fuzzed timestamp falls outside the newest
hundred. The full pass, and the foreground catch-up, repair that.

### D6. The default signing identity, and only that one

The background pass runs for the profile that opens on launch (`GlobalPrefs.getDefaultWallet`),
if it holds a secret.

- A **public-key** identity can open no gift wrap and decrypt no group, so it has nothing to
  notify about.
- **Other profiles** on the same device would need their keys loaded in the background too, and
  a profile switch is a restart ([multiple-profiles.md](./multiple-profiles.md)). Their wraps
  are still picked up by `reopenInbox` when they are next opened. Notifying for every profile
  is Phase 15's to consider and is not in the core plan.

A notification names the profile when the device has more than one, so "Alice: hi" is not
read as being to the wrong profile.

### D7. What notifies

A pure `NotificationClassifier` maps a line to a category, or to nothing:

| category | Android channel | which lines |
|---|---|---|
| **Messages** | high importance | `message`, `directMessage` from someone else |
| **Needs you** | high importance | `frostApprovalNeeded`, `dkgApprovalNeeded*`, `shareRefreshApprovalNeeded`, `shareRepairApprovalNeeded`, and an inbox or confederation proposal that waits on this device |
| **Group activity** | default importance, off by default | added to a group (the room a Welcome makes), a ceremony that finished or failed, a rotation that finished |
| (nothing) | | every other transcript line, every `isUserMessage`, every chronicle line, every refusal this device wrote |

`NotificationClassifierTest` walks **every** `TYPE_` constant on `ChatMessage` and fails on
one the classifier has not placed. A new transcript type therefore has to decide whether it
notifies when it is added, not after someone complains.

A pure `NotificationPolicy` then drops a classified line when:

- its room is muted, left or deleted;
- the category is switched off in settings;
- the app is visible **and** that room is the one on screen;
- the line is older than its room's read mark, or older than the 72-hour horizon (D2);
- the identity is not armed (D3).

Lines that pass are grouped by room: one notification per room, updated in place, showing the
newest few lines (Android `MessagingStyle`, iOS `threadIdentifier` = room id). A pass that
files 40 lines in one room makes one notification that says so, not 40.

### D8. Read state is local, per room, and a row id

`ChatRoom.lastReadMessageId: Long?`. A room is read up to the newest line on screen when its
messaging screen is visible and the app is in front. Unread = lines in the Messages category
above the mark.

Not synced between devices. Doing that needs a private, replaceable "read up to" event per
room, which no NIP defines for NIP-17 or Marmot. Noted under *Out of scope*.

Opening a room, or reading it on screen, also cancels its notification.

### D9. What the notification shows

By default: the room's name, the sender's name, and the text, because a messaging app whose
notifications only say "New message" is one people turn off. The platforms already handle the
locked case:

- Android: `VISIBILITY_PRIVATE` with a public version that says "New message" and the room's
  name only when the user allows details on the lock screen.
- iOS: the system's *Show previews* setting applies without any code.

A setting, **Show message text**, turns the text off everywhere. "Needs you" notifications
never carry the proposal's content, only what kind of decision is waiting ("A signing session
is waiting for your approval in Book club").

Plaintext never goes to a log, a crash report, or a push server (Part C).

### D10. What each platform can honestly offer

| | while visible | backgrounded, process alive | backgrounded, process gone |
|---|---|---|---|
| **Android** | live subscriptions; notify other rooms | a few seconds, then the cached-app freezer stops us | WorkManager every 15 minutes at best, deferred by Doze to maintenance windows. Opt-in: a **Stay connected** foreground service keeps live subscriptions open |
| **iOS** | live subscriptions; notify other rooms | about 30 seconds with `beginBackgroundTask`, used to flush outbound | `BGAppRefreshTask` when the system chooses, often a few times a day, never if the user force-quit the app; `BGProcessingTask` overnight on charge |
| **Desktop** | live subscriptions | live subscriptions: minimised, or closed to the tray (Phase 12) | nothing. The app is not running |

The settings screen says which of these applies, in plain words, instead of letting a periodic
check pass for real-time. Real-time on iOS, and on Android without a persistent notification,
needs a push server, which is Part C.

### D11. A background pass sends what ingest queued, and starts nothing

Ceremonies move when a message arrives ([automatic-key-ceremony.md](./automatic-key-ceremony.md)):
filing a round can queue this device's reply as a `MarmotInnerEvent` or a gift-wrap payload.
In the foreground the notary sends it within a second. In the background, nothing would,
and the whole group would wait on this phone being opened.

So the pass runs the notary's three outbound observers and the broadcast pump for the rest of
its deadline, through the same `GizaRuntime`, serially per room as they already are. It does
not start a ceremony, rotate a key package or propose anything. Only what the arrival already
decided is sent.

### D12. Behind a flag until the device run

`ExperimentalFeatures.backgroundSync`, on in development builds. It gates the worker, the
background tasks, the foreground service and the tray. The read state, the dispatcher and
in-process notifications are not flagged: they carry no protocol risk, and they are what makes
the background sync worth checking on a device at all. The flag goes in Phase 14, once a device
run says the background pass behaves.

---

## Phases

One commit per phase, in the repository's style
(`feat: … (notifications, Phase n)`). Every phase leaves
`./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest` green and `check`'s
audits at budget. Every new string goes in `strings.xml`, in sentence case. Every new screen
follows the four states, `NavigateBackButton` and `readableContent()`. Nothing names Bennu.

**Part A** (Phases 0–7) gives notifications for as long as the app process is alive.
**Part B** (Phases 8–14) gives the process a reason to be alive in the background.
**Part C** is real-time push, written down and not planned.

### Phase 0: this document, and six checks

This document and its row in [README.md](./README.md). Then, before any code:

1. **Desktop lifecycle.** Does minimising, or unfocusing, the Compose Desktop window send
   `ON_STOP` through `LocalLifecycleOwner`? If it does, a minimised desktop app closes its
   subscriptions today, and Phase 8 is a bug fix as well as a feature.
2. **iOS key access in the background.** In the main checkout's `KeyChainHelper`, which
   accessibility constant the seed and the nostr credentials file's key are written with. Both
   must be `AfterFirstUnlock` (with or without `ThisDeviceOnly`). If either is
   `WhenUnlocked`, the background pass cannot load the key, and Phase 11 needs a migration of
   that item first.
3. **iOS file protection.** `aux.db`, its `-wal` and `-shm`, and the DataStore files report
   `NSFileProtectionCompleteUntilFirstUserAuthentication` (or none) on a device.
4. **The row id.** In `ChatMessageDaoJvmTest` or a scratch test: `reconcileMarmotLine` replacing a
   line keeps its id; a NIP-17 redelivery files nothing. If a replacement takes a new id,
   D2 needs a "notified" column instead of a cursor, and Phase 3 changes.
5. **Ingest without a UI.** A jvmTest that builds the repositories with no view model, feeds
   `saveNostrEvent` a Welcome, a group message and a NIP-17 wrap, and finds the room and both
   lines. This is the premise of Part B: if any of them needs a view model's observer, that
   observer moves into `GizaRuntime` in Phase 2.
6. **Android's long-running option.** Which foreground-service type fits a service whose
   whole job is to hold relay subscriptions for incoming messages, on `targetSdk = 37`:
   `dataSync` (time-limited on Android 15 and later), `specialUse`, or `remoteMessaging`.
   Record the choice, the limits and the distribution consequence (a store listing may need
   a declaration). If none is acceptable, Phase 10's second half is dropped and D10 says so.

**Done**, on 2026-11-22, as far as a linux host goes:

| check | result |
|---|---|
| 1. desktop lifecycle | **it does.** Compose Desktop 1.12.2's `ComposeContainer.updateLifecycleState` sets `CREATED` when the window is iconified (`isDetached \|\| isMinimized -> State.CREATED`), which dispatches `ON_STOP` to `GizaNavHost`'s observer. A minimised desktop app closes every live subscription today. Phase 8 fixes it |
| 2. iOS key access | `KeyChainHelper.accessibility` is `Accessible.AfterFirstUnlock`, and every `SecItemAdd` passes it, so the seed and the credentials file's key both read after first unlock. Not yet confirmed on a device, where an item written by an older build could carry another class |
| 3. iOS file protection | **not done**: needs a device. `aux.db` is created with no explicit class, so it inherits the container's default |
| 4. the row id | holds. `reconcileMarmotLine` returns `fresh.copy(id = existing.id, …)`, and `ChatMessageDao.upsert` is a Room `@Upsert`, so a re-filed line keeps its id. One consequence, kept: a group message that is first filed as a placeholder (its epoch had not arrived) and decrypted later keeps the placeholder's id, which the cursor may already have passed, so it raises the room's unread count but posts no notification. Rare (it takes a message ahead of its commit), and kept as a known gap rather than given a second watermark |
| 5. ingest without a UI | holds, and is already tested: `MarmotClientInteropJvmTest` and `GroupLifecycleJvmTest` build `DatabaseNostrRepository` and the DAOs over an in-memory database with no view model, deliver a Welcome through `storeNostrEvent`, and find the room and the member's message filed. `NotificationTodayJvmTest` (Phase 1) repeats it as the premise of Part B |
| 6. Android's long-running option | `specialUse`, with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` saying the service holds relay connections for incoming messages. `dataSync` is capped at six hours a day for apps targeting Android 15 and later, which is no good for a service whose point is to stay up; `remoteMessaging` is meant for handing a conversation between a user's devices. A Play listing has to justify `specialUse`; a build distributed elsewhere does not. Re-read the platform's current list before Phase 10 |

### Phase 1: pin today

Tests written to pass now and flipped on purpose later:

- `NotificationTodayJvmTest`:
  - filing a message in a room writes no read mark (Phase 4 flips);
  - `AppLifecycle.enteredBackground()` closes every live subscription, through a fake
    `LiveSubscriptionTransport` like `LiveSubscriptionReconcileTest`'s (Phase 8 makes it
    platform policy);
  - `GizaNavHost` is the only place a `DatabaseNostrRepository` is constructed outside a test
    (a source grep in a test, like the audits; Phase 2 flips it to `GizaRuntime`).

### Phase 2: one graph per process (D4)

No behaviour change. The largest phase, and the one everything after it stands on.

- `K/GizaRuntime.kt`: holds `AuxDatabaseManager`, every `Database…Repository`, the IO scope and
  its exception handler, `RelaysSocketManager`, `LiveSubscriptionManager`, the publishers, and
  an `activeIdentity: StateFlow<Identity?>`. Built once per process from a `GizaGlobal`.
- `SyncEngine` (`K/managers/SyncEngine.kt`): the body of `SynchronizationViewModel`'s
  active-identity `collectLatest` (the pumps, `reopenInbox`, `resumeRunningCeremonies`,
  `reconcileAdminFlags`, the live subscriptions, the publishers), moved out of the view model.
  `SynchronizationViewModel` becomes a facade over it for the screens that queue requests.
- `OutboundEngine`: the same for `NotaryViewModel`'s four observers.
- `IdentityStore`: the reading half of `SovereignWalletViewModel.listIdentities`, plus
  "load the default identity", callable without a view model. The view model keeps the UI
  state and calls it.
- `GizaNavHost` takes a `GizaRuntime` instead of building one. `GizaApplication` owns it on
  Android (and stops building its own `PhoenixGlobal` beside `MainActivity`'s); a
  `GizaRuntimeHolder` object owns it on iOS; `main()` on desktop builds it after the passphrase.

Tests: the existing suite unchanged; `GizaRuntimeJvmTest` that two `get()`s return one database
and one pool, and that a wallet switch tears `SyncEngine` down and rebuilds it as the view model
used to.

### Phase 3: storage, schema 46 → 47

| where | what |
|---|---|
| `ChatRoom.lastReadMessageId: Long?` | D8 |
| `ChatRoom.notifications: String` | `all`, `muted`; default `all` |
| `NotificationLedger` table, keyed on `userPublicKey` | `armedAt`, `cursorMessageId` (D2, D3), `lastBackgroundSyncAt`, `lastBackgroundSyncOutcome`, `lastBackgroundSyncKind` (quick, full) |

Not an `AutoMigration`: the migration also sets every room's `lastReadMessageId` to its newest
line, so upgrading does not make the whole history unread. A manual `Migration(46, 47)` with that
`UPDATE`, the exported `47.json`, and `ShippedProfileUpgradeJvmTest`'s `current` moved to 47.
(If another branch takes 47 first, this moves up one, as the inbox's did.)

DAO methods: `observeNotifiableAfter(userPublicKey, cursor)`, `unreadCount(roomId)`,
`observeUnreadCounts(userPublicKey)`, `markRead(roomId, upTo)`, ledger get/arm/advance/record.

`NotificationStorageJvmTest`: the migration's `UPDATE`; `markRead` never moves a mark backwards;
the cursor never moves backwards; an unarmed identity yields nothing from `observeNotifiableAfter`.

### Phase 4: read state, seen (D8)

Useful on its own, before any notification exists.

- `ChatRoomMessagingScreen` marks the room read while it is visible and `AppLifecycle` says the app
  is in front, and again when a line arrives while it is. In the two-pane layout, only the room in
  the detail pane.
- The chat list shows an unread count per row (an M3 `Badge`), and the room's name in a heavier
  weight while it has unread lines.
- The navigation bar's chats destination carries a badge with the number of rooms that have
  unread lines.

`ReadStateJvmTest` and a compose test: opening a room clears its count; a line arriving in a room
not on screen raises it; the user's own lines never count; transcript lines never count.

### Phase 5: deciding (D7), pure

No platform code.

- `K/notifications/NotificationClassifier.kt` and its exhaustive test over every `TYPE_`.
- `K/notifications/NotificationPolicy.kt`: classified line, room, settings, visible room, read mark,
  ledger → post or drop, with the reason (the reason is what a debug log and a test assert on).
- `K/notifications/NotificationContent.kt`: room title (`MarmotGroupName` for a group, the peer's
  profile name for a NIP-17 room), sender name, body, profile name when there are several
  (D6), and D9's redaction. Strings from the catalogue.
- `NotificationSettings`: the toggles of Phase 7, stored in the identity's `UserPrefs`.

Tests: one per drop reason, and grouping 40 lines in one room into one notification of the newest
few.

### Phase 6: posting, while the process lives (D1)

- `K/notifications/NotificationDispatcher.kt`: started by `GizaRuntime` for the active identity.
  Observes `observeNotifiableAfter(cursor)`, runs the policy, hands the survivors to a
  `PlatformNotifier`, and only then advances the cursor. If it is killed between posting and
  advancing, the next run re-posts the same room's notification in place, which replaces it, so
  nothing doubles.
- `PlatformNotifier`, an `expect` with three actuals:
  - **Android**: three channels (D7), created at startup; `NotificationCompat.MessagingStyle` with
    a `Person` per sender; notification id derived from the room id; a `PendingIntent` to
    `MainActivity` carrying the room id. `MainActivity` becomes `singleTop` and handles the intent
    in `onCreate` and `onNewIntent`. `POST_NOTIFICATIONS` is asked for the first time it is needed,
    after the user has turned notifications on, not on first launch.
  - **iOS**: `UNUserNotificationCenter` authorisation, a delegate that presents a notification while
    the app is in front (D7 decides whether), and a tap handler; `threadIdentifier` = room id.
  - **Desktop**: Compose Desktop's `TrayState.sendNotification`. A tray icon has to exist for that,
    which Phase 12 builds; until then, the desktop actual is a no-op.
- A `NotificationRoute(userPublicKey, chatRoomId)` that `GizaNavHost` resolves to
  `ChatRoomMessagingRoute`, or to the profile picker first when the notification is for a profile
  that is not the open one.
- `markRead` cancels the room's notification through the notifier.

After this phase: an Android or iOS user in one room is told about a message in another;
somebody who switches to another app sees notifications for the few seconds before the process
is suspended.

Tests: the dispatcher with a fake notifier, from rows to calls, including a restart between post
and advance.

### Phase 7: settings, and muting a room

- **Notifications** screen, pushed from settings (`NavigateBackButton`): the master switch (which
  asks the platform for permission and says what to do if it was refused); Messages, Needs you and
  Group activity; **Show message text** (D9); the background options of Part B, once they exist,
  with D10's sentence for this platform.
- Room detail: **Mute notifications**, writing `ChatRoom.notifications`. A muted room still counts
  unread; it just does not notify.

M3 rules apply: switches in `ListItem`s, 48dp targets, strings in sentence case, four states.

### Phase 8: what "backgrounded" means, per platform

Split `AppLifecycle` into two flows:

- `isVisible`: somebody is looking. Read by the policy (D7) and by read marking (Phase 4).
- `holdConnections`: the live subscriptions should stay open. Read by `LiveSubscriptionManager`
  in place of `isForeground`.

Android and iOS derive `holdConnections` from `isVisible`, or from the Stay connected service
(Phase 10). Desktop holds connections for as long as the process runs, whatever Phase 0, check 1
found.

On iOS, entering the background starts a `beginBackgroundTask` that lets the outbound engine
finish what it is sending (D11), for at most the time the system grants, then ends it.

Phase 1's lifecycle test flips: backgrounding on desktop keeps the subscriptions.

### Phase 9: the background pass (D5, D6, D11), in common code

- `K/managers/BackgroundSyncPass.kt`: D5's eight steps, both strengths, with a deadline and a
  `Mutex` so two triggers in one process never overlap. If `isVisible` turns true mid-pass, the
  pass returns and the foreground takes over the same sockets.
- `LiveSubscriptionManager` gains a bounded mode for the quick strength: open the usual filters,
  complete at EOSE or a per-relay timeout, close. The same filters, so nothing fetched in the
  background is something the foreground would not have fetched.
- The negentropy catch-up becomes a function both the foreground and the full pass call.
- The outbound drain runs `OutboundEngine` until its queues are empty or the deadline is near.
- The ledger records the outcome: `completed`, `deadline`, `no identity`, `no network`, `visible`.

Tests, through a fake transport and a real in-memory database: a wrap and a group message
arriving in the pass produce lines and a notification call; a Welcome in the pass leads to a
second fetch that brings the new group's first message; a deadline mid-fetch leaves every filed
event filed and records `deadline`; an approval-needed line from a ceremony round is posted
under Needs you, and the reply that round queued is sent.

### Phase 10: Android

- `androidx.work:work-runtime` in the catalogue and in `androidMain`.
- `BackgroundSyncWorker : CoroutineWorker`: gets `GizaRuntime` from the application, runs the full
  pass with a nine-minute deadline (inside WorkManager's ten).
- Scheduled from `GizaApplication.onCreate` as unique periodic work (`KEEP`), 15 minutes, network
  required, battery-not-low; cancelled when notifications are off or no signing identity exists.
  WorkManager reschedules itself after a reboot; nothing else is needed for that.
- A second commit: the **Stay connected** foreground service, off by default, with the type
  Phase 0, check 6 chose. Its notification is on its own minimum-importance channel and says
  that Giza is connected for messages. While it runs, `holdConnections` is true and the worker
  returns at once.

Device checks: the worker under `adb shell cmd jobscheduler run`; Doze with
`adb shell dumpsys deviceidle force-idle`; app standby buckets; a reboot.

### Phase 11: iOS

- `Info.plist`: `UIBackgroundModes` = `fetch`, `processing`;
  `BGTaskSchedulerPermittedIdentifiers` = `mobi.giza.refresh`, `mobi.giza.sync`.
- `iOSApp.swift` registers both identifiers at launch, before the first scene, and calls into
  Kotlin: `BackgroundSyncIos.runRefresh(task)` (quick, deadline 25 seconds) and
  `runProcessing(task)` (full, requires network). Each sets the task's `expirationHandler` to
  cancel the coroutine, calls `setTaskCompleted(success:)` exactly once, and schedules the next
  request before it returns.
- Both are requested whenever the app enters the background.
- The settings screen reads `UIApplication.backgroundRefreshStatus` and says so when Background
  App Refresh is off for Giza.
- `UNUserNotificationCenter` delegate set in Swift and forwarded to Kotlin for the tap.

Device checks: Xcode's `_simulateLaunchForTaskWithIdentifier` for both tasks; a locked phone after
first unlock (Phase 0, checks 2 and 3, again on a device); a force-quit app (expected: nothing,
and the settings screen should not pretend otherwise).

### Phase 12: desktop

- A `Tray` with the app's icon and three items: Open Giza, Mute all for an hour, Quit.
- Closing the window hides it to the tray when notifications are on, and quits otherwise. The first
  time it hides, a notification says it is still running and how to quit.
- The desktop `PlatformNotifier` actual becomes real through `TrayState.sendNotification`. A click
  brings the window back; AWT cannot say which notification was clicked, so the window opens on the
  chat list.
- Where the desktop has no tray (some Linux sessions report `SystemTray.isSupported() == false`),
  closing quits, as it does today, and the settings screen says notifications work only while the
  window is open.

### Phase 13: acting from the notification

Two actions, each through `GizaRuntime` without opening the UI:

- **Mark as read** on every message notification.
- **Reply**, on Android (`RemoteInput`) and iOS (`UNTextInputNotificationAction`): writes the line
  through `ChatRepository.sendChatMessage`, then runs the outbound engine until that line is sent
  or a deadline passes. A reply that could not be sent stays queued and the notification says so.

Not Approve: approving a signing session from a notification shade, without seeing what is being
signed, is exactly what the approval screens exist to prevent.

### Phase 14: the record, and devices

Write back into this document what was built and where the build chose differently, as the other
plans do. Run on an Android phone, an iPhone and a desktop:

- a message to a backgrounded Android app arrives within one worker period, and with Stay connected
  within seconds;
- a message to a backgrounded iPhone arrives at the next app refresh, and how long that was, recorded;
- a Welcome while backgrounded produces a "you were added to" notification and the group's first
  message;
- a signing request while backgrounded produces a Needs you notification, and the ceremony round
  this device owed was sent by the pass;
- reinstalling and signing in posts no notification for the history;
- a muted room, a room on screen, and **Show message text** off all behave.

Then remove `ExperimentalFeatures.backgroundSync`.

### Phase 15 (optional): every profile on the device

Run the pass for each signing identity in turn, each with its own cursor, under one deadline,
and route a notification's tap through a profile switch. Only if Phase 14 shows the pass fits its
budget with room to spare.

---

## Part C: real-time push, written down and not planned

Everything above is polling, however disguised. A message reaches a closed iPhone when iOS next
decides to run us, and a closed Android phone in up to fifteen minutes plus Doze. The only way to
do better is for something that is always awake to wake the phone, which on iOS means APNs and on
Android means FCM or UnifiedPush. That something is a server, and it is the part that needs the
owner's decision rather than a phase:

- **Groups: MIP-05.** marmot-kmp already has it (`MarmotPushCoordinator`, kinds 446–449). Each
  device gossips an encrypted push token inside the group; a sender gift-wraps a kind 446 trigger
  to a notification server, which decrypts the tokens and sends a content-free push. The server
  learns that *some* member of *some* group got a message, and which tokens to wake; never the
  content. Needs a notification server (run by whom?), an APNs key and an FCM project or a
  UnifiedPush distributor, a `MarmotPushStateStore` table, and the push handler to run the quick
  pass.
- **Direct messages: nothing standard.** NIP-17 has no push. A relay-side or third-party watcher
  that pushes when a kind 1059 `#p` arrives for a registered key would work, and would learn your
  npub and the timing of every message you receive. That is a real privacy cost, and the reason this
  plan does not choose it.

Whatever is chosen, the push handler does exactly one thing: run `BackgroundSyncPass` quick. D1
means nothing else changes.

---

## Risks

| risk | what to do |
|---|---|
| Phase 2 moves the whole object graph, and a missed `remember` leaves two of something | `GizaRuntimeJvmTest`, and Phase 1's grep test flipped to assert one construction site |
| The OS kills a pass mid-commit and leaves a room's MLS state half-written | Already the app's assumption: it can be killed at any time in the foreground too, and each event is filed in its own transaction. The pass adds no new kind of write. Phase 9's deadline test kills mid-fetch on purpose |
| A notification for every message in history, after reinstall or a new profile | D3, and Phase 14's reinstall check |
| A notification twice for one line | D2's cursor, advanced only after posting, and notifications replaced in place by room; Phase 0, check 4 |
| Battery | The worker runs at most every 15 minutes with network required; the pass has a deadline and closes everything; Stay connected is opt-in and visible. Measure in Phase 14 with Android's battery historian |
| iOS runs the refresh rarely, or never | Said in the settings screen (D10). The real fix is Part C |
| iOS cannot read the key or the database while locked | Phase 0, checks 2 and 3. Anything that later raises `aux.db`'s protection class breaks background sync silently; a test that asserts the class is the guard |
| A store rejects the foreground-service type | Phase 0, check 6; Stay connected is a separate commit and can be left out |
| The quick pass misses a gift wrap whose fuzzed timestamp falls outside the newest hundred | D5. The full pass and the foreground catch-up repair it; the quick pass is only ever the iOS refresh |
| Notification text visible to someone holding the phone | D9: platform lock-screen redaction, and Show message text |
| A desktop with no tray | Phase 12: closing quits, and the screen says so |

---

## Out of scope

- **Real-time push** (Part C), until the owner chooses a server.
- **Read state across devices** (D8). Needs a private replaceable event per room that no NIP defines.
- **Approving from a notification** (Phase 13).
- **Mentions-only** notifications for a room. Needs mentions in Marmot and NIP-17 messages, which the
  app does not parse yet.
- **Notifications for the group inbox** ([group-inbox.md](./group-inbox.md) lists it out of scope
  too). Its messages are not `ChatMessage` rows, so D1 does not see them. The same dispatcher over
  `GroupInboxMessage` is the natural follow-up.
- **Feed and post notifications** (replies, reactions, zaps to the user's posts). Chat only.
- **Starting the desktop app at login.** It would stop at the passphrase gate anyway.

---

## Order and size

| phase | rough size | depends on |
|---|---|---|
| 0 checks | this document, scratch tests, two device checks | — |
| 1 pin | ~150 lines of test | 0 |
| 2 one graph | ~1,000 moved, ~300 new, ~200 test | 0 |
| 3 storage | ~250 main, ~200 test | 2 |
| 4 read state | ~400 main, ~250 test | 3 |
| 5 deciding | ~400 main, ~400 test | 3 |
| 6 posting | ~700 main (three actuals), ~300 test | 4, 5 |
| 7 settings | ~500 main, ~100 test | 6 |
| 8 lifecycle | ~200 main, ~100 test | 2 |
| 9 the pass | ~600 main, ~500 test | 2, 6, 8 |
| 10 Android | ~350 main, plus Stay connected ~250 | 9 |
| 11 iOS | ~250 Kotlin, ~100 Swift, plist | 9 |
| 12 desktop | ~250 main | 6, 8 |
| 13 actions | ~400 main, ~200 test | 6, 9 |
| 14 record | docs and a device session per platform | all |

Part A is useful by itself, and Phase 4 is useful before any notification exists. Phases 4 and 5
can be written side by side once 3 lands, and 8 beside them. 10, 11 and 12 are independent of each
other once 9 lands, and are the natural place to split the work across people or sessions.

---

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew check
```

The iOS phases need a mac (`iosArm64` is declared only there) and a device for anything involving
`BGTaskScheduler` or the keychain while locked. `brand-audit.sh` applies to channel names,
notification text and the tray's menu: Giza, never Bennu. `wallet-audit.sh` is untouched; nothing
here goes near a retired kind.
