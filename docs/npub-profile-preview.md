# A profile before a chat

How the "Direct message via npub" option comes to show the person before it starts
anything, why the preview is a screen reached by public key rather than a step inside
the dialog, and what the "Start new chat" button on it is allowed to do.

Read this with `HomeScreen.kt`, `StartDirectMessageToNpubOrNip05Dialog.kt` and
`ChatRoomMessagingViewModel.kt` open. It changes the entrance to the direct-message
flow and nothing past it: the room is still created where it is created today, by
the code that creates it today.

**Built**, phases 1–4, one commit each, in the order given. Phases 1–3 are the
request as stated — a preview, and a button on it — and ship as a strict improvement
on today. Phase 4 is what makes the button honest, and it is last so that the first
three do not wait on it. The phases are kept as written because they are the
reasoning; where the implementation chose differently the table below says so:

| what the plan said | what it turned out to be |
|---|---|
| a new string, `no_profile_was_found_for_this_npub` | the sign-in screen's `we_could_not_find_a_profile_for_this_key` — "We could not find a profile for this key on the relays we asked." One string, two screens, one meaning, as the npub plan found with `use_a_different_key` |
| a new string, `open_chat` | it existed: `SelectChatRoomTypeScreen`'s. The build failed on the duplicate key, which is the catalogue doing its job |
| `findDirectMessageRoom` as "the rule `initiateNewChat` applied" | the rule's *intent*: a room whose members are exactly the two keys, or the one key when they are the same. The old size-one branch would have opened any room in which the peer sat alone, whoever the peer was; the new rule needs that lone member to be you, and the repository test pins a room the peer has with somebody else as not yours |
| `retry()` from `NotYetOnDedun` | from any `Loaded`: it re-asks the DM relays alone and touches the readiness only when it was `NotYetOnDedun`, so a retry never takes an "Open chat" back to "Checking" |
| the timeout "fires with a person on screen" | a flag, `relaysHadTheirTime`, because the profile can arrive *after* the timeout and has to read not-yet-on-Curare rather than checking; cleared by a retry, and `@Volatile` because the timeout and the collect are different coroutines |
| the view model tested on `TestScope` virtual time | real time, as the other view-model tests: `Dispatchers.setMain(UnconfinedTestDispatcher())`, a poll under `withTimeout`, and the twenty seconds cut to three hundred milliseconds through the constructor |
| `initiate()` "runs from a `LaunchedEffect`" | and is idempotent besides: a recomposition that calls it again opens no second collect, which the view-model test asserts |
| a screenshot pass under `docs/material-design-conformance.md` | five PNGs from a throwaway `jvmTest` with `captureToImage`, looked at and deleted — the found, not-found, checking, not-yet-on-Curare and existing-chat states, at a phone's width |
| Phase 2's screen with one repository | Phase 4 adds `chatRepository` to the screen, its host entry and its tests, as the plan said it would; the Phase 2 commit is the screen without it |
| a `Button` under the block, "because a FAB has no disabled state in M3 or in the API" | **superseded on the way in**: `curated` gained `ExtendedFab` while this was built — every screen's bottom-bar action, with an `enabled` that borrows the disabled colours, marks the node disabled and drops the press — so the reason for the `Button` was gone before the branch merged. The action is that FAB in a `BottomAppBar`, the indicator in its icon slot while the key package is owed, and the reason line stays in the body above it |

One thing found on the way that is in no phase: the dialog's `Dialog` renders inside
the desktop test root, so `onNodeWithText` finds its field and its button without a
popup matcher, and `performTextInput` drives the `inputTransformation` on every
edit — which is what made the `nostr:npub1…` row a four-line test.

## The flow today

1. The "New chat" button on the home screen opens the sheet
   ([HomeScreen.kt:193](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/HomeScreen.kt),
   and again at 300 for the two-pane layout).
2. `NewChatBottomSheetDialog` — titled "Create new chat" — offers two cards. The
   first, "Direct message via npub", hides the sheet and opens a dialog
   ([HomeScreen.kt:353](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/HomeScreen.kt)).
3. `StartDirectMessageToNpubOrNip05Dialog` is one field and a "Start chat" button.
   The button is enabled when the text starts with `npub1` or `nostr:npub1`, or parses
   as an email-like address
   ([StartDirectMessageToNpubOrNip05Dialog.kt:125](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/dialogs/StartDirectMessageToNpubOrNip05Dialog.kt)).
4. "Start chat" decodes the npub and pushes `ChatRoomMessagingRoute(chatRoomId = <pubkey>)`
   (line 162). A nip05 goes to `ImplementationPendingRoute` (line 153).
5. `ChatRoomMessagingScreen` finds no room under that id and moves to
   `InitiatingNewChat`
   ([ChatRoomMessagingViewModel.kt:52](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/ChatRoomMessagingViewModel.kt)),
   and `initiateNewChat` (line 69) looks for a room that already has the two of you
   in it, otherwise waits on the peer's profile *and* key package, with a twenty-second
   timeout, then calls `createMlsDirectMessage` (line 153) and replaces itself with the
   room it made.

Three things are wrong with it, in the order a user meets them.

**Nothing is confirmed.** The pasted string goes straight to creation. What the user
sees is "Creating new chat." and then a transcript with a name in the bar — or the
twenty seconds and "Couldn't find this profile's chat details on the relays yet" —
and in neither case a way to tell *wrong npub* from *right person, not on Curare yet*.
The one place in the app that shows a person and offers "Send message",
`MetadataEventDetail`
([MetadataEventDetail.kt:296](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/detail/MetadataEventDetail.kt)),
is reached by the *event id* of a kind 0 the device already holds, and a pasted npub
has neither.

**`nostr:npub1…` is accepted and then dropped.** The field's check allows the prefix;
`bech32ToHexOrNull` on line 158 is handed the text with the prefix still on it,
fails, and the null goes unhandled. The dialog has already closed. An npub
with a bad checksum takes the same silent exit.

**The creation is committed before anything is confirmed.** When a key package is
there — a real person on Curare, only not the one meant — `createMlsDirectMessage`
writes a room, an MLS group and a Welcome on the strength of a pasted string, and
the wrong person is invited to a conversation before the right one has been looked
at.

## What is already built

Almost all of it. The preview is a new screen over pieces that exist.

| piece | where | state |
|---|---|---|
| a person plus one action, reached by pubkey: `AddMemberToChatRoomConfirmationScreen` waits on a key package with a timeout and shows the profile's name over a bottom-bar button | [AddMemberToChatRoomConfirmationViewModel.kt:74](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/AddMemberToChatRoomConfirmationViewModel.kt) | the shape, whole |
| the existing-room check, the profile-and-key-package wait, the timeout, the creation, the self-replacement | [ChatRoomMessagingViewModel.kt:69](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/ChatRoomMessagingViewModel.kt) | stays exactly where it is; the preview hands over to it |
| the three-kind filter a chat needs — kind 0, key package, DM relay list — on the DM relays | `ChatRoomMessagingViewModel.scheduleProfileAndKeyPackageSync`, line 182 | private; lifted out in Phase 1 |
| a sync object with tests, and the rule that a placeholder is not a profile: `createdAt > GENESIS_AT` | [MemberProfileSync.kt:52](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/MemberProfileSync.kt) | the precedent for both |
| where a kind 0 is found for a key that has lived elsewhere: the indexer relays plus our own | [SignInSync.kt:53](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/SignInSync.kt), `bootstrapRelays` | reused as is |
| the full npub validation — `nostr:` stripped, case folded, hrp checked, thirty-two bytes, a point on the curve | [CredentialParser.kt:139](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/CredentialParser.kt) | private; exposed in Phase 3 |
| `observeProfileWithPublicKey`, `observeMarmotKeyPackageForPublicKey`, `getAllParticipantsWithPubKey`, `getChatRoomByIdentifier` | `NostrRepository`, `ChatRepository` | built |
| the queue stamps the asking identity on every request | [DatabaseNostrRepository.kt:686](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt) | nothing for a view model to do |
| a re-queued negentropy request inside the same minute re-arms the row rather than being ignored | [NegentropySynchronizeRequestDao.kt:32](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NegentropySynchronizeRequestDao.kt) | "try again" works |
| a kind 0 arriving overwrites the placeholder row | [NostrDao.kt:424](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt) | the observed flow emits it |
| the stack shape for "a profile becomes a chat": push the room, pop the profile | [DedunNavHost.kt:1706](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt), `NostrEventDetailRoute`'s DM exit | copied |
| `ProfileAvatar`, `humanReadableNameOrPubkey()`, `staticIdentifier()`, `hexToNpubHrp()` | `Profile.kt`, `Credentials.kt` | built |
| a constant app bar with the state transition inside it | `GroupNostrProfileScreen`, since removed with the group's nostr profile | the layout to copy |
| `LoadingDataIndicator(text = …)`, `EmptyState(message, icon, action)`, `ErrorState(onRetry)`, `NavigateBackButton`, `LocalCanSign` | `widgets/` | built |
| strings: `profile`, `try_again`, `start_chat`, `send_message` | `strings.xml` | present; the new ones are listed per phase |

## The four decisions

### Where the preview lives: a pushed screen, by public key

Three places were possible. Inside the dialog, under the field, as a card that
appears once the npub parses. On a screen that replaces the dialog, with the field at
its top. Or on a screen of its own, reached from the dialog by the pubkey it parsed.

The third. The preview has real states — asked, found, not found after the relays
have had their time, and failed — and the conventions in `CLAUDE.md` put those on a
screen: `ScreenStateTransition` over a `when`, `EmptyState` with an action,
`ErrorState` with a retry, and a `NavigateBackButton` so the desktop has a way off
it. A `Dialog` has none of that, and one that spends twenty seconds searching relays
is a dialog doing a screen's job. Replacing the dialog with a screen is the better
product and is the one refactor this plan leaves for later, on purpose: it changes
the entrance, where the request was about what comes after it, and with the preview
keyed by pubkey the field can move onto the same screen without the screen changing
(see the [appendix](#the-field-on-the-preview-screen)).

Keyed by *pubkey*, not by the kind 0's event id, because that is all the entrance
has. It is also why the same route will serve a scanned QR code, a `nostr:npub…`
link and, once one exists, a resolved nip05: every one of them arrives as a key.

### What "found" means: a resolved kind 0, from the relays that hold everyone's

A pubkey gets a `Profile` row the moment the device first sees it, and that row is
the "LOADING..." placeholder stamped `GENESIS_AT`
([NostrDao.kt:273](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt)).
The preview shows a profile only when a kind 0 has been read for it — the rule
`ChatRoomMessagingViewModel` already applies at line 136 and `MemberProfileSync` at
line 58, given a name in Phase 1 so that it is applied once.

Whom to ask is the sign-in plan's answer, for the sign-in plan's reason: an npub
pasted from elsewhere has lived elsewhere, and the indexer relays exist to hold
everyone's kind 0. So the kind 0 is asked of `SignInSync.bootstrapRelays` — the
indexers plus our own relay — and the key package and DM relay list, which only our
relay holds, are asked of the DM relays with the filter the chat screen already
uses. Two requests, not one, because a three-kind filter sent to an indexer returns
the kind 0 and nothing else, and a kind-0-only filter sent to our relay finds a
person who is not on Curare nowhere.

Cached first. The observed flow emits the row the device already holds before any
relay answers, so a person already known — a room member, a follow, a search result
— is on screen at once, and the relays refresh them behind it.

### What the button does: hands over; it does not create

"Start new chat" pushes `ChatRoomMessagingRoute(chatRoomId = <pubkey>, relayHint = null)`
and pops the preview — exactly the route the dialog pushes today, and exactly the
exit `MetadataEventDetail`'s "Send message" takes. `initiateNewChat` then does what
it has always done: finds the existing room or creates one, and replaces itself with
it. Back from the room is the home screen, as it is from a chat opened off a profile.

Not creating on the preview keeps one creation path with one set of failure modes,
and keeps the preview a *reader*: nothing on it writes until the button is pressed,
and the button writes by leaving. It also means a read-only identity can be shown
the screen with the button hidden behind `LocalCanSign`, as `MetadataEventDetail`
hides its own, and the inventory in `ReadOnlyEntrancesJvmTest` gains a row rather
than an exception.

### Whether the button waits: yes, in its own phase

With Phases 1–3 alone the preview moves the twenty-second dead end one screen later:
the person is found on an indexer, the button is pressed, and `InitiatingNewChat`
waits for a key package that a person who has never opened Curare will never have
published. Phase 4 answers that on the preview, where the answer can be read before
the button is pressed — a room that already exists opens; a key package that is
here enables the button; one that is not, after the relays have had their time,
disables it and says why. The two DM view models already wait on the key package
with the same timeout; Phase 4 waits on it one screen earlier, and the screen after
it never waits at all.

Its own phase because it changes what the button is allowed to do, and because it
adds a repository method whose rule — what counts as a direct message between two
keys — is today a loop with a `TODO` in it. That rule deserves its own commit and its
own test.

---

## Phase 1 — the lookup, and the sync it shares

**No screen.** A sync object, one rule named, and a view model — everything the
screen will read, testable without a composition.

### `DirectMessagePeerSync`

`ChatRoomMessagingViewModel.scheduleProfileAndKeyPackageSync` lifted into
`com.machankura.dedun.compose.nostr`, beside `MemberProfileSync` and shaped like it, with the
kind 0 request the preview adds:

```kotlin
/**
 * Asking the relays for what a direct message with one person needs.
 *
 * Two asks, because two sets of relays hold two different things. The DM relays
 * hold the key package and the DM relay list, and the kind 0 of anyone who has
 * published one here; the indexer relays hold everyone's kind 0 and nothing else
 * a chat needs. A three-kind filter to the indexers comes back with the kind 0
 * alone; a kind-0-only filter to our relay finds a person who has never opened
 * Curare nowhere. See docs/npub-profile-preview.md.
 */
object DirectMessagePeerSync {
    /** The purpose the chat screen has queued under since it was written; kept so nothing reading the queue by purpose changes. */
    const val PURPOSE = "initiate-chat"
    const val PROFILE_PURPOSE = "profile-preview"

    /** What a chat needs. No `limit`: ignored on the negentropy path and, on the REQ fallback, `limit = 1` across three kinds returned the kind 0 alone. */
    val KINDS = arrayOf(MetadataEvent.KIND, KeyPackageEvent.KIND, ChatMessageRelayListEvent.KIND)

    /** One negentropy request per DM relay, level 0, the id bucketed by minute so a retry re-arms the row. */
    fun requests(peerPublicKey: HexKey): List<NegentropySynchronizeRequest>

    /** The kind 0 alone, as a REQ to `SignInSync.bootstrapRelays`, level 0. */
    fun profileRequests(peerPublicKey: HexKey): List<SynchronizeNostrEventRequest>
}
```

`ChatRoomMessagingViewModel.scheduleProfileAndKeyPackageSync` becomes one line that
queues `DirectMessagePeerSync.requests(chatRoomId)`. Its comment about `limit` moves
onto `KINDS`, where the next reader will look for it. `AddMemberToChatRoomConfirmationViewModel.scheduleKeyPackageSync`
asks for the key package alone under a different purpose and is left as it is.

Level 0 on both, for the reason `MemberProfileSync` gives: it marks a request as one
somebody is waiting on, and a kind 0 arriving at level 0 over a placeholder queues the
rest of that person's profile kinds off the back of it
([NostrDao.kt:1243](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt)).
From an indexer that follow-up asks the indexer for key packages it does not hold,
which is harmless and is why the DM-relay request is queued explicitly rather than
relied on.

### `Profile.isResolved()`

```kotlin
/** Read off a kind 0, as against the "LOADING..." placeholder minted when a pubkey is first seen. */
fun isResolved(): Boolean = createdAt > GENESIS_AT
```

A member function on `Profile`, beside `humanReadableNameOrPubkey()`. The two
call sites that spell the rule out — `ChatRoomMessagingViewModel` line 136 and
`MemberProfileSync` line 58 — switch to it in this commit, so that there is one
definition of resolved. The DAO's own `== GENESIS_AT` comparisons stay: they are
on the write side, deciding whether a row *is* the placeholder, and read better as
they are.

### `ProfilePreviewViewModel`

```kotlin
sealed interface ProfilePreviewUIState {
    data object Loading : ProfilePreviewUIState
    data class Loaded(val profile: Profile) : ProfilePreviewUIState
    /** Every relay asked has had its time, and no kind 0 came. Not an error: a search that found nothing. */
    data object NotFound : ProfilePreviewUIState
    /** The queue itself refused: nothing was asked. */
    data object Error : ProfilePreviewUIState
}

class ProfilePreviewViewModel(
    val activeUserPublicKey: HexKey,
    val profilePublicKey: HexKey,
    initialProfilePreviewUIState: ProfilePreviewUIState,
    val nostrRepository: NostrRepository,
) : ViewModel() {
    var profilePreviewUIState by mutableStateOf(initialProfilePreviewUIState); private set

    fun initiate() { observeProfile(); askTheRelays() }

    /** Asks again. The observation is still live, so the answer arrives through the same collect. */
    fun retry() { profilePreviewUIState = ProfilePreviewUIState.Loading; askTheRelays() }
}
```

`observeProfile` collects `observeProfileWithPublicKey(profilePublicKey)`, maps a
row that is not resolved to null, `distinctUntilChanged`, and on a non-null profile
cancels the timeout and sets `Loaded`. A null keeps waiting; the timeout is what
says when to stop.

`askTheRelays` cancels any running timeout, starts one — `RELAY_LOOKUP_TIMEOUT`,
twenty seconds, the value both DM view models use — that moves `Loading` to
`NotFound` when it fires, and queues `profileRequests` and `requests`. A throw from
either queue call sets `Error`. The collect is never cancelled by the timeout: a
kind 0 that arrives on the twenty-first second still loads, exactly as
`ChatRoomMessagingViewModel` keeps its collect live past its own.

The timer rather than the queue's own "every relay has answered" signal, which the
sign-in screen reads through `LocalAccount`: that signal is joined to the account's
unsigned event, and a peer has none. The two DM screens use the timer; this is a
third, and the signal is a follow-up for all three (see out of scope).

### Tests

`DirectMessagePeerSyncTest` in `commonTest`, beside `MemberProfileSyncTest`:

- `requests` yields one row per DM relay, purpose `initiate-chat`, level 0, the three
  kinds and the one author, no limit, and an id equal to `computeId` of its relay and
  filter;
- `profileRequests` yields one row per bootstrap relay, purpose `profile-preview`,
  kind 0 alone, the one author, level 0, no `unsignedNostrEventId`;
- the two relay sets overlap on `ephemeral` and nowhere else — pinned so that a
  change to either set is noticed here.

`ProfilePreviewViewModelJvmTest`, with the recording fake `SignInToProfileViewModelJvmTest`
uses — `NostrRepository by NO_OP_NOSTR_REPOSITORY` with the profile flow a
`MutableStateFlow<Profile?>` and both queue calls appended to an `effects` list:

- `initiate` queues both request lists, once each, and the state is `Loading`;
- a placeholder row (`createdAt = GENESIS_AT`) does not load;
- a resolved row loads, and loads *before* the queue is asked if it was already
  there — cached first;
- with nothing arriving the state is `NotFound` after the timeout, and a resolved
  row arriving after that still loads;
- `retry` from `NotFound` returns to `Loading` and queues both lists again;
- a queue call that throws sets `Error`, and `retry` from `Error` asks again.

The view model launches on `Dispatchers.IO`, as the others do, so virtual time is
no help: the test does what `AcceptCuratedSuggestionViewModelJvmTest` does —
`Dispatchers.setMain(UnconfinedTestDispatcher())`, and `withTimeout(5_000)` around a
poll of the state — and the view model takes `RELAY_LOOKUP_TIMEOUT` as a
constructor parameter with the twenty-second default, so the timeout cases pass a
few hundred milliseconds instead.

`MemberProfileSyncTest` and whatever covers `ChatRoomMessagingViewModel` stay green
through the `isResolved()` switch, which is the point of switching them in the same
commit.

---

## Phase 2 — the screen and its route

**Reachable by nothing.** The route is registered and the screen is complete, and
no button in the app navigates to it until Phase 3. That is deliberate: the screen
is reviewed as a screen, against its previews and its tests, before the flow that
depends on it changes.

### The route

```kotlin
@Serializable
data class ProfilePreviewRoute(
    val activeUserPublicKey: String,
    val profilePublicKey: HexKey,
): Route()
```

No `relayHint`. The dialog has none to give, and `ChatRoomMessagingRoute` takes
null for it today from the same dialog.

### The screen

`ProfilePreviewScreen`, in `ui/composable/`, laid out as `GroupNostrProfileScreen`
is: a constant `Scaffold` with a `TopAppBar` — title `profile`, `NavigateBackButton`
in the leading slot — and `ScreenStateTransition` over the view model's state
inside the content lambda. The `when` is not the composable's whole body, but none
of its branches needs `ColumnScope`, which is the one thing that layout costs.

```kotlin
@Composable
fun ProfilePreviewScreen(
    activeUserPublicKey: HexKey,
    profilePublicKey: HexKey,
    initialProfilePreviewUIState: ProfilePreviewUIState = ProfilePreviewUIState.Loading,
    nostrRepository: NostrRepository,
    onStartChat: (Route) -> Unit,
    onNavigateBack: () -> Unit,
)
```

The four states:

| state | what is drawn |
|---|---|
| `Loading` | the npub, in `bodySmall` and `onSurfaceVariant`, so the user can already check it is the one they pasted; under it `LoadingDataIndicator(fillScreen = false, text = looking_for_this_profile_on_the_relays)` |
| `Loaded` | the profile block and the button, below |
| `NotFound` | `EmptyState(message = no_profile_was_found_for_this_npub, icon = Icons.Default.PersonSearch, action = TextButton(try_again) → viewModel.retry())`. `EmptyState`, not `ErrorState`: nothing went wrong, the search came back empty, and that is the one absence this screen has to name |
| `Error` | `ErrorState(onRetry = viewModel::retry)` — the queue refused, and asking again is the right retry |

The profile block, in a `Column` under `readableContent()`, leading-aligned — rows
with an avatar align to a leading edge, and centring is for a block that is the only
thing on the screen, which this is not once the button is under it:

- `ProfileAvatar(size = 75.dp, profile, publicKey)` — 75dp is a dimension, not a
  spacing, and is what `MetadataEventDetail` uses;
- `humanReadableNameOrPubkey()` in `titleLarge`;
- `staticIdentifier()` in `labelMedium`, when there is one — the nip05 or the
  lightning address, which is the thing a user compares against what they were
  told;
- `about` in `bodyMedium`, the whole of it: this is the one screen whose job is to
  let the user read it;
- the npub, `hexToNpubHrp()` remembered once, in `bodySmall` and
  `onSurfaceVariant`, wrapped — sixty-three characters of bech32 are what the user
  pasted, and the block is not a confirmation without them.

The button, under the block, full width, only when `LocalCanSign.current`:

```kotlin
Button(onClick = {
    onStartChat(ChatRoomMessagingRoute(activeUserPublicKey, chatRoomId = profilePublicKey, relayHint = null))
}) {
    Icon(Icons.Default.Mail, contentDescription = Decorative)   // the label is beside it
    Spacer(Modifier.width(MaterialTheme.spacing.relatedGap))
    Text(stringResource(Res.string.start_new_chat))
}
```

A `Button`, not the bottom-bar `ExtendedFloatingActionButton` the invite
confirmation uses, because Phase 4 will disable it with a reason beside it, and a
FAB has no disabled state in M3 or in the API. (Superseded before the merge — see the
table at the top.)

A read-only identity sees the block and no button. The screen is not reachable by
one today — the sheet is behind `canSign` — but the route will be reachable from a
link one day, and `MetadataEventDetail` already makes the same choice for the same
control.

`initiate()` runs from a `LaunchedEffect` only when the screen arrives `Loading`, as
every screen that takes an initial state does; a test that passes `Loaded` never
touches the repository.

### The words

| name | text |
|---|---|
| `start_new_chat` | Start new chat |
| `looking_for_this_profile_on_the_relays` | Looking for this profile on the relays. |
| `no_profile_was_found_for_this_npub` | No profile was found for this npub on the relays it was asked of. Check the npub, or try again later. |

Sentence case throughout. Apostrophes are written plainly — `StringCatalogueJvmTest`
is the record that `Don't` survives the round trip and `Don\'t` does not.

### The host

In `DedunNavHost`, after `NostrEventDetailRoute`:

```kotlin
composable<ProfilePreviewRoute> { backStackEntry ->
    val route = backStackEntry.toRoute<ProfilePreviewRoute>()
    ProfilePreviewScreen(
        activeUserPublicKey = route.activeUserPublicKey,
        profilePublicKey = route.profilePublicKey,
        nostrRepository = databaseNostrRepository,
        // The room replaces the preview, as it replaces the profile detail: back
        // from a chat is the list, not the person it was started from.
        onStartChat = { chatRoom ->
            navController.navigate(route = chatRoom) { popUpTo(route) { inclusive = true } }
        },
        onNavigateBack = { navController.popBackStack() },
    )
}
```

The stack after the button: `Home → ChatRoomMessaging(pubkey)`, and once
`initiateNewChat` has made the room, `Home → ChatRoomMessaging(roomId)` — the
same two steps the dialog produces today, with the preview gone from between them.

### Tests

`ProfilePreviewScreenJvmTest`, on the pattern of `GroupNostrProfileScreenJvmTest`
— `runDesktopComposeUiTest` at a phone's width, `DedunTheme`, `ProvideSnackbarHost`,
the state passed in, the NO_OP repository never asked:

- `Loaded` draws the name, the nip05, the whole of the about, and the npub;
- "Start new chat" is there, and pressing it hands over exactly
  `ChatRoomMessagingRoute(activeUserPublicKey, chatRoomId = pubkey, relayHint = null)`;
- with `LocalCanSign` false the block is drawn and the button is not — searched on
  the unmerged tree, as `ReadOnlyEntrancesJvmTest` explains;
- `NotFound` shows its message and a "Try again";
- `Loading` shows the npub above the indicator.

`ReadOnlyEntrancesJvmTest` gains the row: the preview is a write entrance, and the
inventory is the list of those.

A `@ConformancePreviews` preview of the `Loaded` state, as every screen has, so the
screenshot pass in `docs/material-design-conformance.md` covers it.

---

## Phase 3 — the dialog hands over

**The user-visible change.** After this commit "Direct message via npub" shows the
person before anything is started.

### The parse

`CredentialParser.npub` is exactly the validation the dialog needs and is private
to a parser for sign-in credentials, whose `parse` would also recognise a pasted
nsec — which a message-address field must never treat as an address. So the branch
is exposed on its own:

```kotlin
/**
 * The x-only key under an `npub1…`, with or without a `nostr:` prefix, or null.
 * The one shape a message address can arrive in: hex stays a secret here for the
 * reason [parse] gives, and an nsec is refused because it is not an address.
 */
fun npubOrNull(raw: String): HexKey?
```

`parse` calls it from its `npub1` branch, so the two cannot disagree. A pasted
`nprofile1…` returns null: the TLV form is a different shape, and reading its
pubkey and relay hints out is its own small change (out of scope, and the route
already has room for the hint).

### The dialog

`StartDirectMessageToNpubOrNip05Dialog` keeps its title, its field, its focus rule
and its nip05 branch. What changes:

- the `inputTransformation` keeps what it parsed rather than whether it parsed:
  `parsedPublicKey`, a `MutableState<HexKey?>` set from `npubOrNull(text)`, replaces
  the boolean `startChatButtonEnabled`, and the button is enabled when it or the
  email-like parse is non-null. The `nostr:` bug and the bad-checksum bug were one
  bug — the check and the decode were two different functions — and holding the
  decoded key is the fix, because there is no second decode to disagree;
- the button reads `find_profile` with `Icons.Default.PersonSearch`, because it no
  longer starts a chat and a label that says it does is the thing M3's "tell users
  what will happen" is about;
- on confirm, the npub branch pushes
  `ProfilePreviewRoute(activeUserPublicKey, profilePublicKey = parsedPublicKey)`
  through the same `onNavigateToRoute` — `HomeScreen` passes a push and needs no
  change; the nip05 branch is untouched.

The imports of `bech32ToHexOrNull` and `ChatRoomMessagingRoute` go with the old
branch. `NewChatBottomSheetDialog` is not touched.

### The words

| name | text |
|---|---|
| `find_profile` | Find profile |

`start_chat` goes with its only reader, the button it labelled; `start_chat_via_npub_or_nip05`, the dialog's title, stays.

### Tests

`CredentialParserJvmTest` gains rows for `npubOrNull`: a bare npub, `nostr:npub1…`,
an upper-cased npub, a bad checksum, an `nsec1…`, an `nprofile1…`, sixty-four hex
characters — the last four all null. The npub the sign-in tests already use is the
fixture.

A `StartDirectMessageToNpubOrNip05DialogJvmTest`:

- typing a valid npub enables "Find profile", and confirming hands over
  `ProfilePreviewRoute` with the decoded key;
- `nostr:npub1…` does the same — the test that would have failed on the old code;
- an nsec leaves the button disabled;
- an email-like address enables it and still lands on `ImplementationPendingRoute`,
  pinned so that the nip05 branch's behaviour is recorded rather than assumed.

The compose test drives the `TextField` through `performTextInput`; the
`inputTransformation` runs on every edit, so no other trigger is needed.

---

## Phase 4 — ready before the button

**The button becomes honest.** It opens a chat that exists, starts one that can be
started, and otherwise says why not, before it is pressed rather than twenty seconds
after.

### The existing room

`initiateNewChat`'s loop — every `Participant` row for the peer, its room, the
size-one-or-two rule, the `TODO` about groups of two — becomes a repository method
with the rule as its contract:

```kotlin
/**
 * The room that is a conversation between [userPublicKey] and [peerPublicKey]
 * alone, if there is one: two participants and both of them, or one when the two
 * keys are the same. A group of two is not told apart from a direct message here —
 * the schema does not record which a room is — so this is the rule `initiateNewChat`
 * has applied since it was written, in one place with its name on it.
 */
suspend fun findDirectMessageRoom(userPublicKey: HexKey, peerPublicKey: HexKey): LocalChatRoom?
```

On `ChatRepository`, implemented in `DatabaseChatRepository` from
`getAllParticipantsWithPubKey` and `getChatRoomByIdentifier`, as the loop is today.
`initiateNewChat` calls it and navigates to what it returns; its own loop goes.
Whether a room the user has *left* (`leftGroupAt`) should count is a question the
loop never asked, and this phase does not answer it: same rule, one place.

### Readiness

`Loaded` grows a second field:

```kotlin
data class Loaded(val profile: Profile, val readiness: Readiness)

/** Whether pressing the button would get anywhere, read here rather than twenty seconds into the next screen. */
sealed interface Readiness {
    /** A conversation with this person exists; the button opens it. */
    data class ExistingChat(val chatRoomId: String, val relayHint: String?) : Readiness
    /** Their key package is here; `initiateNewChat` will create the room without waiting. */
    data object CanStartChat : Readiness
    /** Asked, not answered yet. */
    data object Checking : Readiness
    /** The relays have had their time and no key package came. */
    data object NotYetOnDedun : Readiness
}
```

The view model takes `chatRepository` and, in `initiate`, reads
`findDirectMessageRoom` once before observing. Then it collects, as
`ChatRoomMessagingViewModel` does at line 130, the profile flow *combined with*
`observeMarmotKeyPackageForPublicKey`, and on each emission with a resolved profile
sets `Loaded(profile, readiness)` where readiness is `ExistingChat` if the room was
found, else `CanStartChat` if the key package is non-null, else `NotYetOnDedun` if
the timeout has fired, else `Checking`. The one timeout now does two things when it
fires: `Loading` becomes `NotFound`, and `Loaded(_, Checking)` becomes
`Loaded(_, NotYetOnDedun)`. A key package cancels it only if the profile has also
arrived; a profile cancels nothing on its own, since the key package is still owed.

`retry()` from `NotYetOnDedun` keeps the profile on screen — it is found; it is the
key package being asked for again — sets readiness back to `Checking`, restarts the
timeout and queues `DirectMessagePeerSync.requests` alone.

`ExistingChat` carries the room's `relayHint` from its `Participant` row for the
peer, which is what `ChatRoomMessagingRoute` wants and what the dialog never had.

Readiness is a fact about a *loaded* profile, and a room does not make a person
known: a member still "LOADING..." — the case `MemberProfileSync` exists for —
waits on their kind 0 here like anyone else, and the open button appears with the
name. The alternative, a `Loaded` over a placeholder, would put "LOADING..." in
`titleLarge` on a screen whose one job is to show who this is.

### The screen

`ProfilePreviewScreen` and its host entry gain `chatRepository: ChatRepository`,
passed as `databaseChatRepository` like every screen that reads a room; the
factory hands it to the view model. The button reads its state:

| readiness | button | under it |
|---|---|---|
| `ExistingChat` | `open_chat`, enabled → `ChatRoomMessagingRoute(chatRoomId = room id, relayHint)` | `you_already_have_a_chat_with_s` |
| `CanStartChat` | `start_new_chat`, enabled → as Phase 2 | nothing; the button is the answer |
| `Checking` | `start_new_chat`, disabled | a `LoadingIndicator` and `checking_whether_s_can_receive_messages_here` |
| `NotYetOnDedun` | `start_new_chat`, disabled | `s_hasn_t_set_up_messaging_on_curare_yet`, and a `TextButton(try_again)` → `retry()` |

`%1$s` is `humanReadableNameOrPubkey()` in every one. The line under the button is
`bodySmall` in `onSurfaceVariant` — a supporting line, not a second message. A
disabled button with the reason beside it, rather than a hidden one: the action
exists and is unavailable, which is what M3's disabled state is for, and a screen
that lost its only button would read as broken.

Pressing "Open chat" pops the preview exactly as "Start new chat" does; the
`onStartChat` callback and its host wiring are unchanged.

### The words

| name | text |
|---|---|
| `open_chat` | Open chat |
| `you_already_have_a_chat_with_s` | You already have a chat with %1$s. |
| `checking_whether_s_can_receive_messages_here` | Checking whether %1$s can receive messages here. |
| `s_hasn_t_set_up_messaging_on_curare_yet` | %1$s hasn't set up messaging on Curare yet. Try again later, or ask them to open Curare. |

### Tests

`DatabaseChatRepositoryJvmTest` — or a new `FindDirectMessageRoomJvmTest` beside
`SignInToProfileTwiceJvmTest`, on an in-memory Room under the app's own DAOs:

- a room with exactly the two participants is found;
- a room with the peer and a third member is not;
- a room with the user alone is found when the peer is the user;
- no rows, null.

`ProfilePreviewViewModelJvmTest` grows a `ChatRepository` fake beside the
`NostrRepository` one — `findDirectMessageRoom` answering a fixed room or null, the
key package flow a `MutableStateFlow<MarmotKeyPackage?>`:

- an existing room is `ExistingChat` the moment the profile loads, key package or
  not — the case that would otherwise read "hasn't set up messaging" for someone
  you talk to every day, since a key package is meant to be used once and the
  relays need not hold a fresh one for a person you already have a room with;
- profile then key package: `Checking` then `CanStartChat`, and the timeout is
  cancelled;
- key package then profile: `Loading` until the profile, then `CanStartChat` at once;
- profile and no key package: `Checking` until the timeout, then `NotYetOnDedun`,
  and a late key package still flips it to `CanStartChat`;
- `retry` from `NotYetOnDedun` queues `requests` alone, not `profileRequests`, and
  the profile stays on screen.

`ProfilePreviewScreenJvmTest` grows the four rows of the table above: the label,
whether the button is enabled, the line under it, and for `ExistingChat` the route
handed over.

`initiateNewChat` is covered wherever `ChatRoomMessagingViewModel` is; the
existing-room cases move to the repository test and stay green through the switch.

---

## Checking the work

Each phase, before its commit:

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

The audit's budgets this plan can touch: spacing literals (none — the avatar's
`75.dp` is a dimension), hardcoded colours (none), title case (every new string is
sentence case; `docs/scripts/m3-title-case.py --list` shows any that is not),
untriaged `contentDescription = null` (the button's icon is `Decorative`, the
avatar carries its own), and the bare-`clickable` budget, which nothing here uses.
The adaptive floor is untouched: the preview reads no breakpoint and needs none.

Phase 3 is the one to run on a device or the desktop target as well as in tests,
because it is the one that changes what a tap does: the sheet, the dialog, "Find
profile", the preview, "Start new chat", the room, and back to the list.

## Estimate

| phase | work | days | blocked by |
|---|---|---|---|
| 1 | `DirectMessagePeerSync`, `isResolved()`, the view model, both tests | 0.5–1 | — |
| 2 | the route, the screen, the host, the strings, the screen test, the `@ConformancePreviews` entry | 1 | 1 |
| 3 | `npubOrNull`, the dialog, the two tests, a run on a device | 0.5 | 2 |
| 4 | `findDirectMessageRoom`, readiness, the four words, three tests | 1 | 3 |

**About three days.** The uncertainty is in Phase 4, in one place: the combined
collect and the one timer that means two things. `ChatRoomMessagingViewModel` has
the same shape and got it wrong twice before it was right — the comments at lines
116 and 138 are the record — so the view model test's ordering cases (profile first, key
package first, neither, late) are the phase, and the screen is what is left.

Phases 1–4 are now implemented, in one sitting and in order. The Phase 4
uncertainty resolved as predicted: the ordering cases are where the flag in the
table above came from, and the screen was what was left.

## Out of scope

- **nip05.** The dialog's email-like branch still lands on `ImplementationPendingRoute`.
  Resolving one is an HTTPS fetch of `/.well-known/nostr.json` and a lookup, and
  the result is a pubkey — which is the preview's route. When it is built it ends
  here: the dialog's nip05 branch resolves, then pushes the same route the npub
  branch does.
- **The field on the preview screen.** See the appendix. One refactor, after Phase 3,
  that removes the dialog rather than changing it.
- **`nprofile1…`**, whose TLV carries relay hints the route could use. A second
  branch in `npubOrNull`'s caller, and the route's `relayHint` when it grows one.
- **The two-pane home screen.** From the preview, "Start new chat" pushes the room
  full-screen on a wide window, as "Send message" on a profile does today; selecting
  it in the detail pane instead means the home screen owning the preview, which is
  a question for its list-detail layout (the adaptive phase of
  [material-design-conformance.md](./material-design-conformance.md)) and not this
  plan's.
- **Rooms the user has left.** `findDirectMessageRoom` applies the rule
  `initiateNewChat` applied; whether a left room should open, or a fresh one be
  made beside it, is a rule that needs deciding on its own.
- **A "chat with yourself" preview.** Pasting your own npub shows your own profile
  and, through `findDirectMessageRoom`, opens the one-participant room if you have
  one — exactly what `initiateNewChat` does today. Whether the button should say
  something else for yourself is a product question this plan does not raise.
- **The queue's "every relay has answered" signal for a peer.** Three screens now
  use a twenty-second timer where the sign-in screen reads a status. Giving the
  queue a per-request handle that any screen can wait on is a change to the queue,
  and would retire all three timers at once.
- **`ChatRoomMessagingScreen`'s `Error` state**, which after Phase 4 is reached
  only by a key package that vanished between the preview and the press. It still
  has no retry, as it has none today.

## Appendix — what was considered and rejected

### The preview inside the dialog

A card under the field once the npub parses, with the button on the card. Fewest
taps. Rejected because the preview has states a dialog cannot carry well — twenty
seconds of searching, a not-found with a retry, a queue failure — and because a
`Dialog` on the desktop target is the surface this app has the least of and the
least reason to grow. The screen is where those states have a home, and the dialog
stays exactly as small as it is.

### The field on the preview screen

Replace the dialog with a screen: the field at the top, the preview filling in
under it as the npub parses, the button under that. The better product — the sheet's
other card already pushes a screen, and this would make the two cards symmetrical —
and the one this plan does not build, because it changes the entrance where the
request was about what follows it. With the preview keyed by pubkey it is a
follow-up that moves the field and deletes the dialog, and touches the screen only
to give it a state before `Loading`.

### Reaching `MetadataEventDetail` instead

Wait for the kind 0, then push `NostrEventDetailRoute` with its event id. It is the
app's profile screen and it has "Send message". Rejected because it needs the event
id, which the entrance does not have and would have to wait for on a screen of its
own — at which point that screen *is* the preview — and because it offers follow,
unfollow, add to group and block on the way to a chat, which is a profile, not a
confirmation.

### Creating the chat from the preview

Call `createMlsDirectMessage` on the button and push the room's id. It would spare
one `InitiatingNewChat` frame. Rejected because it would be a second creation path
with the same key-package wait, the same timeout and the same error, one screen
apart from the first; and because the preview is worth keeping a reader. The frame
it would spare is, after Phase 4, the one frame `InitiatingNewChat` shows when the
key package is already here.

### A bottom sheet for the preview

The sheet is already open when the flow starts. Rejected for the reason the
multiple-profiles plan gave for its own switcher: a sheet that pushes a screen from
under itself is a screen with a worse back story, and this one's whole purpose is
to push a screen.
