# More than one profile on a device

How a user holds several profiles on one device, moves between them, and adds
another by signing in or by creating one — why a profile is a credential and a
wallet is a thing attached to one, and why the switch has to be a restart of the
signed-in app rather than a swap underneath it.

Read this after [nsec-sign-in.md](./nsec-sign-in.md) and
[npub-sign-in.md](./npub-sign-in.md). It takes the `Identity` type, the two key
stores, the sign-in screen and the forget sequence as given, changes one rule the
two of them share, and asks the question both deferred without naming it: what
happens when the device holds two of them.

**Built**, phases 1–8, one commit each, in the order given; Phase 9 is the rollout
and is process rather than code. The phases are kept as written because they are
the reasoning, and the code reads better against the argument it came from than
against a summary of itself. Where the implementation chose differently the table
below says so:

| what the plan said | what it turned out to be |
|---|---|
| the repair runs "before the credentials file is read for the listing" | after both files are read, and the listing is built from what it returns — the repaired map, or the one read if the write failed — so the file is decrypted once |
| `NotACredential` becomes `WalletAttached` for a key a seed derives | and for a key not in the file at all, which since the repair can only be a seed's key the repair could not write; one outcome, two ways in |
| `stopPlatformBusiness` called from `switchToIdentity` | injected into the view model as a function, defaulting to the `expect`, so the branch can be pinned with a recorder — a node cannot be started in a test, but a `PhoenixBusiness` can be built, since everything in it is lazy |
| the relay observer restructured | as planned, plus an injectable scope and a `relayUrls` read for the test that fails against the old observer |
| `setActiveIdentity` saves the default | wrapped: a default that could not be written is a selector on the next boot, not a crash now. The forget tails call `forgetDefaultIdentity()` on the view model rather than `clearDefaultWallet()` inline |
| "Initializing…" among the four that become *opening profile* | it and "Preparing wallet…" both became *preparing profiles*; they are the same wait |
| `ProfilesViewModel` "owns the four states" | takes the four flows it joins rather than the view model that holds them, so a compose test can drive it with `MutableStateFlow`s and a fake repository; the fourth state, empty, is not modelled, as the plan said it could not happen |
| a row's fallback "the npub and the metadata's emoji" | the npub as the title over the emoji avatar, with no second line; a key with a kind 0 gets the name over the picture with the npub under it |
| "`createAccount` already takes its writer as a function" | a `NewProfileWriter` interface returning the key it derived and the id it filed it under, with `seedProfileWriter` and `bareKeyProfileWriter` on the view model and `CreateProfileRoute(withWallet)` choosing; the view model plants the six events *after* the write and calls the tail after that, so the account is in the database before the identity is activated — an ordering the create flow used to get away with by luck |
| `CredentialProblem.AlreadyOnThisDevice` "carries it" | the id rides on `Outcome.Failed` and the `Error` state instead; `CredentialProblem` stays an enum and the parser's tests stay as they were |
| the DAO's own requests "stamped from the `activeKeyPair` it is already handed" | left unowned, on purpose: the placeholder-profile syncs and a Marmot join's participant syncs fetch public kinds that need no key to open, and any profile that is open may as well fetch them. Only what goes through the repository is stamped |
| `openGiftWrap` lifted out of `indexNostrEvent` | not lifted: the branch is a screenful of room and participant handling, and running the stored wrap through `indexNostrEvent` again, in a transaction of its own per wrap, gives the same result with one code path. `reopenGiftWrap` is that |
| `ForgetIdentityJvmTest` gains "forgetting B while A remains" | the round trip covers it, forgetting B and C and listing A alone; the per-outcome tests were done in Phase 1 |
| the round trip "creates A from a phrase" | through the create view model with the seed writer, as Landing does, rather than through a pasted phrase; and A's node is a `PhoenixBusiness` that was never started, which is enough for the switch to have something to stop |

Three things found on the way that are in no phase. `DataStoreManager` caches one
`GlobalPrefs` per process behind an unsynchronised check-then-set, and DataStore
refuses a second instance over the same file: two first calls at once — the listing
on IO and a sign-in's write — could both build one, and the loser threw at first
use. `JvmGlobalPrefs` is now the one way the jvm target reaches the prefs, under a
lock; Android goes through the `Application`'s single instance and never raced. The
same cache means a test that constructs `SovereignWalletViewModel` must not build a
`GlobalPrefs` of its own. And `GiftWrapSeal.giftWrapMessageId`, a foreign key to the
wrap a seal came out of, existed and was never written — so nothing in the database
could say which wraps had been opened, which is the question the inbox sweep asks;
`decryptGiftWrapSeal` now sets it, and a seal from before is swept once and comes
back with the link.

## The vocabulary

The user asks for "accounts". The code says *identity* (`Identity`,
`StoredIdentity`, `activeIdentity`), the storage layer says *wallet*
(`WalletId`, `WalletsSelector`, `select_a_wallet`, "Opening wallet"), and every
string a user reads says *profile* ("Create profile", "Sign out of this
profile?", "This profile is read only"). Three words, and they are not three
names for one thing.

**A profile is a nostr key. A wallet is a different thing that a profile may have
attached to it.** The two live in different files for a reason both sign-in plans
spelled out: `seed.dat` holds twelve words, from which a Lightning node *and* a
nostr key are derived; `nostr-credentials.dat` holds a bare key, or a bare public
key, from which nothing else can be. The relationship runs one way — a seed
always yields a key; a key does not imply a seed — and the code has, until this
plan, drawn the line in the wrong place: a seed's key is *not written to the
credentials file*, so a seed-backed profile exists only as a derivation, and the
app has to start a Lightning node to find out who it is
([What actually blocks it](#what-actually-blocks-it)).

This plan settles the model and the words together:

- **The credentials file is the list of profiles.** Every profile the device can
  sign as is a `Secret` entry in it, and every one it can only look at is a
  `Public` entry. A seed contributes a wallet, and the wallet is *attached* to
  the profile whose key it derives — matched by public key, and written as a
  credential when the seed is, which is [Phase 1](#phase-1--the-credential-a-seed-always-implied).
- **The code keeps saying identity; the user reads profile; and *wallet* is used
  only of a wallet.** *Account* is not used anywhere. The two Phoenix-era strings
  the user can still see, `select_a_wallet` and `change_account`, become *choose
  a profile* and *switch profile*; the startup screen's loading literals follow,
  except the one that is genuinely a wallet starting.
- **No renaming of types.** `WalletId` keys every preference file in the library
  and was left alone by both sign-in plans for that reason. A profile's id is its
  wallet's when it has one — `hash160(nodeId)`, as it has always been for a
  seed — and `hash160(pubkey)` when it has none, which is the rule the npub plan
  set and this plan keeps, for the reason in the
  [appendix](#one-id-space-for-every-profile).

What the distinction decides, phase by phase:

| the wallet is not the profile, so | where |
|---|---|
| a seed's key is a credential like any other, written when the seed is and repaired into the file for every seed already here; the identity's key comes from the credential, not the node | [Phase 1](#phase-1--the-credential-a-seed-always-implied) |
| a switch is between profiles; the node, where there is one, follows the profile it belongs to — stopped when that profile is left, never started for one that has none | [Phase 2](#phase-2--a-switch-that-tears-down-what-it-should) |
| the switcher says which profiles have a wallet attached, before the tap | [Phase 4](#phase-4--the-switcher) |
| a profile added from inside is a *key*, not a second seed: wanting another profile is not wanting another wallet | [Phase 5](#phase-5--adding-a-profile-from-inside) |
| a bare key can be forgotten; a key with a wallet attached has funds behind it, and its sign-out stays a wallet question | [Phase 7](#phase-7--leaving-one-profile-among-several) |
| a wallet attached to a profile that did not derive it, or one wallet every profile on the device pays from, is the model this leads to — and its own plan, which Phase 1 makes small | [Out of scope](#out-of-scope) |

## The constraint

One database, one process, one set of pumps, and every route on the stack
addressed by the key it was pushed for.

Every screen from the home tab inward takes `activeUserPublicKey` as a route
argument — `HomeRoute`, `SearchRoute`, `ActiveProfileRoute`, `ChatRoomDetailRoute`,
all of them. The navigation component reads the signed-in key *off the current
route* rather than holding it
([DedunNavHost.kt:418](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt)),
precisely so that it cannot drift from the screen underneath. That is the right
design for one profile and it decides the shape of a switch: a stack of routes
built for key A cannot be handed key B. The only honest switch is to clear the
stack and rebuild it for B — which is, as it happens, exactly what the app already
does when the active identity changes.

The second half of the constraint is the database. It is one Room file for the
device, and it is *mostly* keyed by whose it is: `ChatRoom.userPublicKey` — whose
comment reads "should help us have multiple user support"
([ChatRoom.kt:48](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatRoom.kt))
— `UnsignedNostrEvent.pubKey`, `MarmotKeyPackageBundle.publicKey`,
`GiftWrapMessage.receiverPublicKey`, the `Connection` relation by both keys. The
profile cache is shared, and should be: a kind 0 is the same event whoever fetched
it. What is *not* keyed by whose it is are the three request queues, and
[Phase 6](#phase-6--a-queue-per-identity-and-the-inbox-that-arrived-while-another-profile-was-open)
is about the one place that matters.

## What is already built

Most of a switch. The Phoenix fork this app grew from is a multi-wallet
application, and its switching machinery came across intact and unused.

| piece | where | state |
|---|---|---|
| the credentials file, its encrypted writer, and the app writing it from `writeMnemonic` — to *delete* a public entry a seed supersedes | [IdentityWriter.kt:148](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/IdentityWriter.kt) | built; writing a `Secret` from the same place is one line more |
| `migrateFromNostrKeys`: a named, idempotent write that runs from `listIdentities` before anything reads either file | [SovereignWalletViewModel.kt:183](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt) | built; the shape Phase 1's repair copies |
| `StoredIdentity.merge`, deriving every seed's nostr public key at listing to compare stores by pubkey | [StoredIdentity.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/StoredIdentity.kt) | built; the derivation is what attaches a seed to its credential |
| `switchToWallet(id)`: sets `desiredWalletId`, clears the active identity | [SovereignWalletViewModel.kt:304](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt) | built; called by the sign-in and create tails and the startup selector, never from a signed-in screen |
| `resetToSelector()`: clears both and asks startup for the list | [SovereignWalletViewModel.kt:315](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt) | built; the sign-out and forget tails use it |
| `NavigationViewModel.observeProfile`: a null identity is `StartupPhoenix`, which the nav host navigates to with `popUpTo(0)`; a new identity is observed from its account | [NavigationViewModel.kt:184](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NavigationViewModel.kt) | built — this *is* the restart |
| the notary, the sync pumps and the live subscriptions run as children of `collectLatest` over the identity, so a change cancels them — the comments say "a wallet switch" in as many words | [NotaryViewModel.kt:62](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NotaryViewModel.kt), [SynchronizationViewModel.kt:193](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SynchronizationViewModel.kt) | built |
| the startup screen: lists, picks by `forceWalletId` / `desiredWalletId` / the default, shows `WalletsSelector` otherwise, and wraps every activation in the lock gate | [SovereignWalletStartupScreen.kt:106](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SovereignWalletStartupScreen.kt) | built; the precedence is wrong for a switch, see below |
| `WalletsSelector`: a current section with a divider, the others below, a *read only* label, `topContent` and `bottomContent` slots | [WalletsSelector.kt:59](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/wallet/WalletsSelector.kt) | built; shows "Default name" and a random emoji for every row |
| `BusinessManager`: a map of running nodes by wallet id; starting one that runs returns it; `stopBusiness(walletId)` on all three platforms | [BusinessManager.kt:208](../lightning-kmp-app/library/src/androidMain/kotlin/fr/acinq/phoenix/android/BusinessManager.kt) | built; nothing in the app calls `stopBusiness` |
| `GlobalPrefs.getDefaultWallet`, `saveDefaultWallet`, `clearDefaultWallet` | [GlobalPrefs.kt:102](../lightning-kmp-app/library/src/commonMain/kotlin/fr/acinq/phoenix/utils/preferences/GlobalPrefs.kt) | read by startup; never written by anything |
| the sign-in and create tails: re-list, select, go to startup | [DedunNavHost.kt:746](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt), [:545](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt) | built; a shape that works from inside the app as well as from Landing |
| *Change account* on the profile tab | [ActiveProfileScreen.kt:309](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/ActiveProfileScreen.kt) | `ImplementationPendingRoute` |
| `ForgetIdentity` and `SignOutViewModel` for a read-only identity; *forget this key* for an nsec | `identity/ForgetIdentity.kt`, `NostrSecretScreen` | built; the tail lands on the selector or Landing, which is right for several profiles too |
| `NpubPreviewRoundTripJvmTest`: a real key store, a real in-memory database, real view models | `jvmTest/.../identity/` | the harness every test below reuses |

So the switch exists as a state transition and is reachable from nowhere, and
the list it would switch between is drawn from two files that disagree about
what a profile is. That is the whole shape of this plan: fix what a profile is,
build the two entrances, fix what the transition gets wrong, and make the one
data problem it exposes go away.

## What actually blocks it

Seven things, in the order a user would hit them.

**A seed's profile is not a credential.** `writeMnemonic` writes `seed.dat` and
nothing else — and if a `Public` entry for the seed's key exists, it *deletes*
it, so the seed becomes the only holder
([IdentityWriter.kt:148](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/IdentityWriter.kt)).
The key is then derived twice on every launch: at listing, from the words, to
learn the pubkey the selector shows; and at activation, from the *running node's*
key manager ([SovereignWalletViewModel.kt:151](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt)),
which is why a seed-backed profile has to start Lightning before it can be
anyone. Both sign-in plans made this an invariant rather than an accident — one
entry per pubkey *across both files*, `writeNostrKey` refusing a key a seed
derives, `writeMnemonic` refusing a seed whose key is here as a secret — and it
is the wrong invariant: it says a wallet is a profile, and the switcher this plan
builds would inherit a list in which one kind of row is a key and another is a
seed pretending to be one.

**There is no way in.** Landing is reached only when `availableIdentities` is
empty ([SovereignWalletStartupScreen.kt:95](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SovereignWalletStartupScreen.kt)),
and the sign-in and create screens are reached only from Landing. A device with
one profile cannot be given a second: the flow that adds one is behind a screen
the device never shows again.

**There is no way across.** The selector is drawn by the startup screen and
nowhere else, and nothing in the signed-in app calls `resetToSelector()` except
the sign-out tails. The row that promises it on the profile tab routes to the
pending screen.

**The startup precedence is wrong for a switch.** The `when` that picks what to
open reads `!startWalletImmediately -> null` *before* `desiredWalletId != null`
([:122](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SovereignWalletStartupScreen.kt)),
and `startWalletImmediately` is set to false by `resetToSelector()` and by the
lock prompt's back button and set back to true by nothing. So after the first
visit to the selector, every sign-in on that device lands on the selector instead
of the profile that was just signed in. And because `saveDefaultWallet` is never
called, `availableIdentities[defaultWallet]` is always null: a cold boot with two
profiles is the selector, every time, with no memory of which one was open.

**Two things are not torn down.** `RelaysSocketManager.observeActiveUserId` keeps
one relay-list observer *per pubkey* and only ever cancels the one for the pubkey
being started; its own comment says `TODO: Cancel all pending jobs?`
([RelaysSocketManager.kt:65](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt)).
After a switch, A's observer and B's observer both feed `updateRelayPools`, and
the one pool follows whichever relay list emitted last. And the previous
profile's node, where it had one, keeps running in `BusinessManager` — Electrum,
the LSP peer, the swap-in watcher — for a profile nobody is looking at. The nsec
plan established that nothing in this app reads the node but the key; a node
running for a profile that is not active is that finding with the sign flipped.

**The queues belong to the device, not the identity.** `SynchronizeNostrEventRequest`,
`NegentropySynchronizeRequest` and `BroadcastNostrEventRequest` have no owner
column, and the pumps drain whatever is `pending` with the *active* key pair
([DatabaseNostrRepository.kt:111](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt)).
Requests A queued a moment before the switch — its sign-in sync, its room
reconciliation — are answered while B is active, and everything that comes back
is indexed as B: a gift wrap addressed to A is stored, found not to be addressed
to the active key, and left ([NostrDao.kt:439](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt)).
Then `storeNostrEvent` never indexes an event it already holds
([NostrDao.kt:176](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt),
the `TODO: Check if event got indexed`), so when A is opened again, its live
subscription re-receives the wrap, the DAO sees a known id, and returns. **A
message that arrives while the other profile is open is lost to the one it was
for.** Silently, and it looks in the logs like nothing happened, because nothing
did.

**Two destructive controls assume one profile.** `CreateProfileScreen`'s *end
this* calls `wipeDatabase()` ([CreateProfileScreen.kt:381](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/CreateProfileScreen.kt))
— on a device with a second profile, that profile's rooms and MLS state go with
it, from a screen the user opened to *add* one. And once a default is remembered,
forgetting the identity it names has to clear it, or the next boot opens a
profile the list no longer has.

## The two decisions to make first

### What a profile is

Two models were on the table.

**A seed is a profile by derivation.** What the code does: the credentials file
holds only bare keys, a seed's key is recomputed from the words at listing and
from the node at activation, and the writers refuse to hold the same key in both
files. One copy of every secret, nothing to migrate, and the two sign-in plans'
tests pass as written. The costs are the ones the blocker above lists: the node
has to run for the profile to know its own key, the list of profiles is a merge
of two files with opposite ideas of what a row is, and there is no place for a
wallet that belongs to a profile it did not derive.

**A profile is a credential; a seed is a wallet attached to one.** Creating or
restoring a seed writes the seed *and* a `Secret` credential for the key it
derives; every seed already on the device gets its credential written at the
first listing; `merge` lists the credentials file and attaches each seed to the
entry it derives. The identity's key is read from the credential for every kind,
so the node stops being where a profile's key comes from and becomes what a
wallet is — which is the sentence this plan wants to be true, and the one that
makes "attach a wallet to this profile" a later plan instead of a redesign.

**This plan takes the second.** Its costs are real and Phase 1 pays them: a
repair step at listing, the derived secret held in a second file under the same
keystore key, one of the sign-in plans' invariants inverted, and every `when`
over `IdentityKind` re-read for what the kind now means. What it does *not* do is
make the node lazy, though it makes that possible — the reason is under Phase 1,
and the decision is named under [out of scope](#out-of-scope).

### What a switch is

Is a switch a *restart* or a *swap*?

**A swap** sets the new identity while the stack stands. Every route on that
stack carries the old key as an argument; every view model built from those
routes is observing the old key's rows; the notary and pumps restart under the
new key while the screens above them show the old one. Making that consistent
means every screen re-reads the identity from a flow instead of its route — the
snackbar-host problem again, in forty-three places — or a re-navigation that
amounts to a restart anyway. Rejected, and the [appendix](#a-live-swap-under-the-stack)
says so at more length.

**A restart** is what `observeProfile` already does when the identity goes null:
`StartupPhoenix`, `popUpTo(0)`, the startup screen activates the next one, the
machine routes it from its account. Every screen is torn down; every collector is
cancelled and rebuilt; nothing built for A survives into B. It costs a visible
pass through the startup screen — "Opening profile", under a second for a bare
key, a little over one for a profile with a wallet attached — and it is honest
about what it is.

**This plan takes the restart.** It is what the code does, it is the only design
that cannot leak one identity into another's screen, and it settles four things
that would otherwise each be an argument:

- **The back stack is cleared by a switch**, so the control that switches is a
  pushed screen reached by one deliberate tap from the profile tab — never a
  global control in an app bar, a long press, or a swipe that could fire from
  inside a half-written message.
- **The previous node is stopped.** It belongs to the profile being left, not
  to the device; nothing reads it, the restart tears down everything else, and a
  library that already handles "start one that exists" makes switching back
  cheap enough.
- **The profile that opens on launch is the last one used.** A restart through
  startup is also what a cold boot is, and the two should agree: `desiredWalletId`
  for a switch, the saved default for a boot, the selector only when the user
  asked for it.
- **A profile added from inside is switched to.** The sign-in and create tails
  already select what they wrote; the one they left stays on the device.

Everything below is downstream of those two words: credential, and restart.

---

## Phase 1 — the credential a seed always implied

**App only. No library change.** The file format, the encrypted writer and the
manager all exist; the app already writes the credentials file from the seed
writer. This phase changes what it writes there, and what the listing believes.

### The write

`writeMnemonic` writes two things, in this order:

1. the credentials file, with a `Secret` for `keyManager.nostrPrivateKey()` under
   its x-only public key — replacing a `Public` entry for that key if one is
   there, which is the read-only upgrade the npub plan built, now landing on a
   secret rather than on nothing;
2. `seed.dat`, as today.

Credential first, for the reason the npub plan gave for the order it chose: a
crash between the two leaves a *bare-key profile* whose key the phrase derives,
which is a valid thing for the device to hold and which pasting the phrase again
completes. The reverse order would leave a seed with no credential, which the
listing below tolerates and the repair fixes — also recoverable, but the first
order says the model out loud: the profile exists, and then a wallet is attached
to it.

`writeNostrKey` and `writeNostrPublicKey` are unchanged. The duplicate table
becomes:

| the pubkey is already here as | pasting its nsec | pasting its npub | pasting its phrase |
|---|---|---|---|
| a secret with a wallet attached | `AlreadyExists` | `AlreadyExists` | `AlreadyExists` |
| a secret, bare | `AlreadyExists` | `AlreadyExists` | **attach**: the seed is written, the entry stays as it is, `Written` with the wallet's id |
| a public entry | upgrade to secret, one write | `AlreadyExists` | upgrade to secret and attach, two writes |
| nothing | `Written` | `Written` | `Written`, two writes |

The one new cell is the phrase pasted over a bare key, which the nsec plan
refused as `SeedAlreadyExists`. It was a duplicate under the old model because
two files would have held one key; under this one it is a profile acquiring the
wallet that derives it, and it is the first attachment the app performs. The
profile keeps its key and every row keyed by its pubkey. Its id changes to the
wallet's, so its preferences and metadata are fresh, as a new wallet's are — the
backup flags reset, which is right: there is a new secret to back up.

### The repair

Every seed already on a device has no credential. `SeedCredentials.reconcile`,
beside `migrateFromNostrKeys` in `listIdentities` and shaped like it — a named,
idempotent write that runs before anything reads the merged list:

```kotlin
object SeedCredentials {
    sealed interface Result {
        data class Written(val count: Int) : Result
        data object NotNeeded : Result
        data class Failed(val cause: Throwable) : Result
    }

    /**
     * Writes a Secret credential for every seed whose derived key has none, replacing a
     * Public entry for that key where there is one. One write, or none. A failure is
     * logged and the listing goes on: a seed that cannot be repaired into the file is
     * still listed, with its key derived, by [StoredIdentity.merge].
     */
    suspend fun reconcile(phoenixGlobal, wallets: Map<WalletId, UserWallet>, credentials: Map<HexKey, NostrCredential>): Result
}
```

It runs after both files are read and the nostr-keys migration has run, and the
listing is built from what it returns — the repaired map, or the one read if the
write failed — so the listing sees the repaired file without decrypting it twice.
On a device with no seeds it does nothing; on a device already repaired it does
nothing; on the first launch after this phase it writes once. `Failed` is logged,
not surfaced: this is a repair of something the app can still work around, and a
listing that goes red because a background write failed would be worse than the
derivation it was replacing.

### The listing

`merge` inverts. Today the seeds are the list and the credentials are added to
it; now the credentials are the list and the seeds are attached to it.

```kotlin
fun merge(wallets, credentials): Map<WalletId, StoredIdentity> {
    val walletsByPubkey = wallets.values.associateBy { nostrPublicKeyOf(it.words) }
    val merged = LinkedHashMap<WalletId, StoredIdentity>()
    credentials.forEach { (pubkey, credential) ->
        when (credential) {
            is Secret -> {
                val wallet = walletsByPubkey[pubkey]
                val stored = if (wallet != null) mnemonic(wallet, credential.privateKey) else nostrSecret(credential.privateKey)
                if (stored.nostrPublicKey == pubkey) merged[stored.id] = stored
            }
            is Public -> if (pubkey in walletsByPubkey) log.w { "public entry for a key a seed derives; the next repair upgrades it" }
                         else nostrPublic(pubkey)?.let { merged[it.id] = it }
        }
    }
    // A seed the repair could not write a credential for. Listed as it always was,
    // with the key derived from the words, so that a failed write never hides a wallet.
    walletsByPubkey.forEach { (pubkey, wallet) ->
        if (pubkey !in credentials) { log.w { "seed with no credential; deriving" }; mnemonic(wallet).let { merged[it.id] = it } }
    }
    return merged
}
```

`StoredIdentity.Mnemonic` gains the key: `Mnemonic(userWallet, privateKey)`, with
`nostrPublicKey` derived from the key and checked at construction against what
the words derive — a mismatch is corruption, and the entry is dropped with a log
line rather than listed under a key it cannot sign as. The derivation from the
words stays, because it is what matches a seed to its credential; the nsec plan
noted `SeedManager` had already built that key manager once, so this is the same
second derivation it was.

`IdentityKind` keeps its three values and its doc comment changes:

```kotlin
/** A key the device also holds a seed for. The key is a credential like any other; the seed is the wallet attached to it. */
Mnemonic,
```

Every `when` over the kind is re-read in this phase for what the kind now means,
and none of them change: `KeyRecoveryScreen` offers the phrase for this kind
because the phrase is the wallet's backup and derives the key; `NostrSecretScreen`
refuses to forget it because a wallet is attached; the startup branch starts the
node because a wallet is attached. The meaning shifted under them and the answers
held, which is what a kind that names the *attachment* rather than the *source*
buys.

### The activation

`setActiveWallet(walletId, business)` becomes `setActiveWallet(stored: StoredIdentity.Mnemonic, business)`
and builds the identity from the **credential's** key:

```kotlin
val derived = business.walletManager.keyManager.value?.nostrPrivateKey()
check(derived == stored.privateKey) { "the node derives a different nostr key than the credential holds" }
setActiveIdentity(Identity.signing(id = stored.id, kind = IdentityKind.Mnemonic, nostrPrivateKey = stored.privateKey, …, business = business))
```

The check cannot fail on a file the repair wrote — it derived the key from the
same seed — and it is kept because a credentials file is now the thing the app
signs with, and a node disagreeing with it is the one corruption worth refusing
to run under.

**The node still starts.** Not for the key any more: for what `startNewBusiness`
does besides — it registers the wallet's metadata, applies its Electrum and Tor
preferences, records the last-used build, and on Android schedules the channel
watcher that notices a force-close while the app is shut. A phrase pasted from a
real Phoenix wallet can have channels behind it, and a build that stopped
watching them to save a second at startup would be trading a spinner for funds.
With the key in the credential, making the node lazy is one branch in
`doLoadWallet` and a decision about who watches channels while it is not running;
it is named under [out of scope](#out-of-scope) and this phase does not take it.

### The forget

`forgetNostrCredential` refuses two things instead of one: a key that is not in
the file, as today, and — new — a key that a seed derives. The seed would
re-derive it and the next repair would write it back, so a forget that succeeded
would undo itself at the next launch. `ForgetNostrCredentialResult.NotACredential`
becomes `WalletAttached`, because that is now the only reason a signing profile
cannot be forgotten, and `ForgetIdentity.Outcome` follows.

### The two plans that said otherwise

Each of the sign-in docs carries a table of where the build chose differently
from the plan. Each gains one row at the bottom, pointing here: the
one-entry-per-pubkey rule across both files, superseded — a seed's key is a
credential, and the seed is attached to it. Their tests change in the same
places: `IdentityWriterJvmTest`'s "the npub of a key a seed derives is refused"
stays; its "a seed whose key is here as a secret is refused" becomes "…is
attached to it".

### Tests

`SeedCredentialsJvmTest`, through the jvm key store: two seeds and an empty
credentials file — `Written(2)`, two `Secret` entries under the keys the words
derive; run again — `NotNeeded`, and the file's bytes are unchanged; a `Public`
for one seed's key — upgraded to `Secret`, `Written(1)`.

`StoredIdentityJvmTest`: a `Secret` whose key a seed derives lists once, as
`Mnemonic`, under the wallet's id, carrying the credential's key; a seed with no
credential lists as `Mnemonic` with a derived key; a `Secret` whose key no seed
derives is `NostrSecret`; a `Public` for a seed's key is dropped.

`IdentityWriterJvmTest`: creating from a phrase writes both files and the
credential is a `Secret` for the derived key; the phrase of a bare key attaches —
`Written` with the wallet's id, the entry unchanged; the nsec of a key with a
wallet attached is `AlreadyExists`; forgetting a key with a wallet attached is
`WalletAttached` and changes nothing.

And a mnemonic activation in the round-trip harness, which neither sign-in plan
wrote because both were about the other kinds: the identity's key equals the
credential's and the node's.

---

## Phase 2 — a switch that tears down what it should

**App only. No UI. Needs Phase 1.** The transition, made correct, with nothing
yet calling it from a screen. Its diff is the view model, one manager, and three
one-line actuals.

### The call

```kotlin
// SovereignWalletViewModel
/**
 * Makes [id] the identity to open next and clears the active one, which is the
 * whole of a switch: the navigation observer sends a null identity to startup,
 * startup opens [id] through the lock gate, and the machine routes it from its
 * account. Everything reading [activeIdentity] is cancelled by the null and
 * rebuilt by the activation.
 */
fun switchToIdentity(id: WalletId) {
    val previous = _activeIdentity.value
    _desiredWalletId.value = id
    startWalletImmediately.value = true
    _activeIdentity.value = null
    if (previous?.business != null) {
        viewModelScope.launch(Dispatchers.IO) { stopPlatformBusiness(previous.id) }
    }
}
```

`switchToWallet` is renamed to this; its three callers — the two tails and the
startup selector's `onWalletClick` — follow. `startWalletImmediately` is set
back to true because a switch is the user saying which one, and the flag was
only ever the user saying *show me the list*. Nothing set it back before because
nothing could switch.

The order inside is deliberate. The identity is cleared **before** the node is
stopped: clearing cancels every collector that could reach the business, so the
stop finds nothing reading it. The stop is `BusinessManager.stopBusiness` behind

```kotlin
expect fun stopPlatformBusiness(walletId: WalletId)
```

with an actual per platform beside `updateBusinessActiveInUI`, which has the
same shape and the same reason to be an `expect`: the manager is a per-platform
object.

The test is `business != null`, not `kind == Mnemonic`. The question is whether
the profile being left has a node behind it, and today those are the same fact —
but the first is the fact, and the second is how it currently comes to be true.
`previous.id` is the wallet's id when a wallet is attached, which is why
`stopBusiness` can take it, and why a profile's id is its wallet's. For a bare
key there is nothing to stop and the branch is not taken; for a read-only profile
likewise.

**The tail keeps its own `navigate`.** The sign-in tail both switches and
navigates to startup with `popUpTo(0)`, and so does the observer on
`StartupPhoenix`; two writers to the stack, one entry popped by the other. It
looks redundant and is not: `_navigationUIState` is a `StateFlow`, and a
`getAndUpdate` to a state equal to the current one emits nothing. The explicit
navigation is what guarantees the stack moves even on the day the state does not.
Leave both, and leave a comment saying so, because the next reader will want to
remove one.

### The relay observer

`observeActiveUserId` becomes what the pumps already are — a child of
`collectLatest`, cancelled by the next emission:

```kotlin
private fun observeActiveUserId() = scope.launch {
    activeIdentityStateFlow.collectLatest { identity ->
        val pubkey = identity?.nostrPublicKey ?: return@collectLatest
        relayRepository.observePublicKeyRelays(pubkey).collect { relays ->
            updateRelayPools(relays.filter { it.type == "user" }.map { it.mapToRelayDTO() })
        }
    }
}
```

The `observeRelayJobs` map goes; it existed to replace an observer per pubkey and
could only ever leak. A null identity closes nothing — the pool is shared by the
pumps that a null identity has already cancelled, and the next identity's list
replaces it through `changeRelays` as it does today.

### What needs nothing

Listed so that nobody adds a guard that already exists.
`SynchronizationViewModel`'s pumps and `LiveSubscriptionManager.observe` are
children of `collectLatest`; `hasRequeuedStaleBroadcasts` is once per process
*on purpose*, and its comment already names the wallet switch as the reason.
`NotaryViewModel` keys on the private key and restarts its four observers.
`MlsGroupCache` and `MarmotInboundManager`'s pending commits are keyed by room,
and a room belongs to one identity by its `userPublicKey`. `DataStoreManager`
caches preferences per id for the life of the process, which is right for a
switch and only wrong for a test that reuses a key across directories, as the
nsec plan found.

### Tests

`IdentitySwitchJvmTest`, in the round-trip harness beside `NpubPreviewRoundTripJvmTest`:
write an nsec A and an nsec B through the writers, list, activate A with a real
`NotaryViewModel` and `NavigationViewModel` on the same database. Queue an
unsigned kind 1 for A: it is signed. Switch to B, activate B the way startup
does: the machine lands on B's state. Queue an unsigned kind 1 for A: it is
**not** signed while B is active, and is when A is opened again. That is the
assertion a switch is for — the notary signs as whoever is open and nobody else.

`switchToIdentity` on its own, with `stopPlatformBusiness` injected as a recorder
the way the writers are injected into `SignInToProfileViewModel`: called once
with A's id when A had a node; not called for a bare key; not called when nothing
was active. And a `RelaysSocketManager` test over a fake relay repository with two
pubkeys' lists: after the switch the pool holds B's relays, and a re-emission of
A's list changes nothing.

---

## Phase 3 — which profile opens, on launch and after a switch

**App. Needs Phase 2.** The startup screen learns to agree with the switch.

### The precedence

The `when` that picks what to open, lifted out of the composable's `remember`
into a function that can be read and tested, in `ui/view/state`:

```kotlin
object StartupChoice {
    /** Which stored identity startup opens without asking, or null to show the selector. */
    fun resolve(
        force: WalletId?,
        desired: WalletId?,
        startImmediately: Boolean,
        identities: Map<WalletId, StoredIdentity>,
        default: WalletId?,
    ): StoredIdentity? = when {
        force != null -> identities[force]
        desired != null -> identities[desired]      // a switch or a sign-in said which
        !startImmediately -> null                   // the user asked for the list
        identities.size == 1 -> identities.values.single()
        default != null -> identities[default]      // the last one used
        else -> null
    }
}
```

Two rows move. `desired` goes above `!startImmediately` — it is the more
specific instruction and the one a switch relies on — and the `size == 1` row
drops below it, because when both are set they name the same thing and the order
only mattered for reading. Everything else is as it was.

### The default

`setActiveIdentity` saves what it activates:

```kotlin
fun setActiveIdentity(identity: Identity) {
    _activeIdentity.value = identity
    viewModelScope.launch(Dispatchers.IO) { getGlobalPrefs().saveDefaultWallet(identity.id) }
}
```

Every activation goes through here — startup for all three kinds, and the
sign-in and create tails through startup — so the default is always the profile
most recently open, and a cold boot with two profiles opens it. The selector is
one tap away in Phase 4, and the lock prompt's back-to-selector button stays for
the case it was built for.

The inverse: the two forget tails — sign out of a read-only identity, *forget
this key* for a bare key — call `clearDefaultWallet()` before `resetToSelector()`.
`resetToSelector` already nulls `desiredWalletId`; the default is the one memory
of the identity that would otherwise outlive it, and `identities[default]` on a
forgotten id is a selector anyway, but a null read is better than a miss that
happens to be handled.

### The words

The five literals the startup screen shows — "Decrypting...", "Initializing...",
"Preparing wallet...", "Opening wallet", "Starting wallet" — move to the catalogue
in this phase because the phase rewrites the `when` they sit in. Four of them say
"wallet" about something that is not one and become *preparing profiles* and
*opening profile*. The fifth is right as it is: "Starting wallet" is shown from
`StartupViewState.StartingBusiness`, which only the branch with a wallet attached
reaches, and what is starting there is a Lightning node. It stays *starting
wallet*, so that a user with a bare-key profile never sees the word and a user
with a wallet sees it at the one moment it is true. The selector's title,
`select_a_wallet`, becomes `choose_a_profile`. The lock prompt's "coming soon"
literal stays; it is a stub and marked as one.

### Tests

`StartupChoiceTest` in `commonTest`, one case per row and the two orderings this
phase exists for: `desired` set and `startImmediately` false opens the desired
one; one identity and `startImmediately` false shows the selector, because the
user asked. `IdentitySwitchJvmTest` gains: after activating B, `getDefaultWallet`
is B's id; after forgetting B, it is empty; a fresh `SovereignWalletViewModel`
over the same store and a `StartupChoice.resolve` with its list and default
answers A.

---

## Phase 4 — the switcher

**App. Needs Phases 2 and 3.** One pushed screen, and the startup selector
brought up to match it.

### The screen

`ProfilesRoute`, reached from the profile tab's row — *switch profile*, where it
said *change account* — and drawn by `ProfilesScreen`: a `TopAppBar` titled
*profiles* with `NavigateBackButton` in the leading slot, and `WalletsSelector`
as its whole content, with `activeWalletId` set so the current profile sits above
the divider and the others below.

Tapping another row is `switchToIdentity(id)` and nothing else — the observer
restarts the app as that identity, through its lock gate. Tapping the current row
does nothing; `canEdit` stays false, the edit dialog behind it is a stub. The
`bottomContent` slot holds the two rows [Phase 5](#phase-5--adding-a-profile-from-inside)
adds.

Four states, said so. *Loading* while `listWalletState` is `Init`, which in
practice is never seen — the list was read at startup — but the state exists and
the screen says what it is waiting for. *Loaded* is the list. *Error* is
`ListWalletState.Error`, through `ErrorState` with `onRetry = listIdentities`,
because an unreadable credentials file is exactly the failure a retry can answer.
*Empty* cannot happen while something is signed in and the screen does not
pretend it can. `ScreenStateTransition` wraps the `when`; `readableContent()` on
the root; one column at every width, since a list of a handful of rows has no
detail to pair with.

### What a row shows

`WalletsSelector` shows `metadata.nameOrDefault()` — "Default name", for every
row, because nothing in this app writes the wallet metadata's name — over a
random emoji ([WalletsSelector.kt:162](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/wallet/WalletsSelector.kt)).
Two profiles side by side, both called Default name, distinguished by a bech32
string, is not a switcher.

The row takes the nostr profile instead: `WalletsSelector` gains a
`profiles: Map<HexKey, Profile?>` parameter, and the row shows
`humanReadableNameOrPubkey()` over `ProfileAvatar(publicKey, profile)` — the
widgets the profile tab already draws itself with — falling back to the npub and
the metadata's emoji for a profile the device has no kind 0 for yet: one just
created, or a read-only one that was never found. `ProfilesScreen` collects the
map through `observeProfileWithPublicKey` for every listed pubkey, in a
`ProfilesViewModel` that owns the four states above. The *read only* label stays
where it is and means what it meant.

And a second label of the same kind, *wallet*, on a row with a seed attached.
What the device holds for a profile — nothing, a key, or a key and a wallet — is
the one thing the switcher knows that the profile tab never shows. It is the
difference between a profile that can be signed out of and one whose sign-out is
a wallet question, and between two profiles with the same name, and it belongs
before the tap for the reason the read-only label does. A bare-key profile
carries no label; it is the plain case.

The startup screen draws the same widget and gets the same map: `DedunNavHost`
already holds `databaseNostrRepository` where it composes
`SovereignWalletStartupScreen`, and passes it through. Both lists then look the
same, which is what a user who has just seen one and is now looking at the other
expects.

### Tests

`ProfilesScreenJvmTest`, with `runDesktopComposeUiTest` and the unmerged tree, as
`ReadOnlyEntrancesJvmTest` does: two identities, A current — A's name is above the
divider and B's below; tapping B invokes `onSwitch(B.id)` exactly once; tapping A
invokes nothing; a read-only B carries *read only*, an A with a seed attached
carries *wallet*, and a bare key carries neither; a B with no profile row shows
its npub. And `ActiveProfileScreen` under `LocalCanSign` true and false both have
*switch profile*: a read-only identity can leave for another profile the same way
a signing one can.

---

## Phase 5 — adding a profile from inside

**App. Needs Phase 4.** The two entrances, and the two things that were only
safe with one profile.

### The rows

Under the list, in `bottomContent`, on the switcher and on the startup selector
alike:

| row | goes to | why not Landing |
|---|---|---|
| *sign in with a key* | `SignInRoute` | Landing has no back button and clears the stack on its way out: it is the first-run screen, and pushing it from inside would make it a pushed screen on some days and a root on others. The sign-in screen has `NavigateBackButton` already |
| *create a new profile* | `CreateProfileRoute` | same; and its tail is the one the create flow uses today |

Landing stays exactly what it is. The caption on each row says what the tails
below make true: *the profile you are in stays on this device.*

### The sign-in tail

Unchanged in shape — `listIdentities { switchToIdentity(id); navigate(Startup) { popUpTo(0) } }`
— and now correct from inside: Phase 2 stops the previous node and Phase 3 makes
startup open the id it was handed. A read-only identity upgrading itself through
the Messages tab's *sign in with the nsec* is the same tail with the same id, and
keeps working; a bare key acquiring its wallet through a pasted phrase is the
same tail with the wallet's id.

One thing changes. `IdentityWriter.WriteNostrCredentialResult.AlreadyExists` is a
`data object` and says only that the key was refused. From Landing that was the
whole message; from inside, "this key is already on this device" is a sentence
with an obvious next step, and the writers know which id they collided with —
the credentials map by pubkey, and the attached seed's wallet id where there is
one. It becomes `AlreadyExists(val id: WalletId)`,
`CredentialProblem.AlreadyOnThisDevice` carries it, and the sign-in screen's
error state gains one action, *switch to it*, which is `switchToIdentity(id)` and
the same tail. The recovery-phrase writer's `SeedAlreadyExists` maps the same way.
Two writers, one distinction, and a duplicate paste becomes the fastest switch in
the app.

### A profile created from inside is a key

`CreateProfileScreen` generates twelve words behind its form
([CreateProfileViewModel.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/CreateProfileViewModel.kt)):
a seed, a node, a wallet, and — at the NIP-06 path — the key that becomes the
profile. That is the right thing for the first profile on a device, which is the
one the wallet will belong to, and the wrong thing for the second: a user who
wants another profile has not asked for another Lightning node, another set of
channels, or a second twelve-word phrase with funds behind it, and would not thank
the app for handing them one.

From the switcher, *create a new profile* makes a **bare key**: thirty-two random
bytes as a `PrivateKey`, written through `writeNostrKey` — the writer the sign-in
screen uses for a pasted nsec — then `createNewProfile(pubkey, name, bio)`, the
six-event bootstrap a new key gets, then the tail. Same screen, same form:
`createAccount` already takes its writer as a function, so the nav host passes
the seed writer from Landing and the key writer from the switcher, and the
screen does not know which it was given. Under this plan's model the two writers
differ by exactly one thing — whether a wallet is attached to the profile they
make — which is what the model was for. The declaration and confirmation copy say
"profile" and "keys" and are true of both. The backup lives where it does for a
signed-in nsec: the key recovery screen already shows the secret for that kind,
and the backup flags are per id, so a profile made this way is reminded to back
up its key the way a seed's profile is reminded about its phrase.

The previous identity's notary observes `observeUnsignedNostrEvents(publicKey =
its own)`, so it never sees the new key's events; they wait for the new
identity's notary, which starts when startup activates it — at once, since there
is no node to start. Nothing crosses. The tail gains the `popUpTo(0)` the sign-in
tail has, so that back from the new profile's startup does not return to a form
whose key is already on disk.

A second *wallet* is not offered from inside. Restoring one is the sign-in row —
a pasted recovery phrase brings its wallet with it, and the confirm step already
says so — and creating a fresh one waits for a wallet feature to want it, which
is named under out of scope. Landing's *create profile* keeps making a seed; if
the product decides the first profile should be a bare key as well, that is the
same writer swapped in one more place.

**And *end this* goes.** It wipes the database — every profile's rooms, MLS state,
key packages and queues — from a screen whose purpose is to add a profile, and it
was only ever a development exit. Remove the button and the string;
`wipeDatabase` stays on the repository for the tests that use it. If a
device-level reset is ever wanted, it is a settings screen with a confirmation
naming every profile it will take, and not this.

### Tests

`SignInToProfileViewModelJvmTest`: the nsec of a key already here fails with the
id it is already here under, for each of the three writers.
`AddProfileFromInsideJvmTest`, in the round-trip harness: A active; B's nsec
committed through the sign-in view model; list — two entries; switch; B active;
switch back — A active, and `getLocalAccounts()` holds two accounts, each with its
own kind 0 and neither with the other's. A profile created from inside through
the key writer lists as `NostrSecret`, its account holds the six bootstrap rows,
and `BusinessManager.businessFlow` is empty afterwards — no node ran, the
assertion the nsec round trip already makes. `CreateProfileScreen` composed has
no node with the text *end this*.

---

## Phase 6 — a queue per identity, and the inbox that arrived while another profile was open

**App. Needs nothing above it to compile; Phase 2 to matter.** The one data
problem, closed from both ends.

### Whose request

The two queues that *fetch* gain an owner, because what a fetch brings back is
opened with the fetcher's key. The one that *sends* does not, because a signed
event is anyone's to carry: the signature is the author's whoever puts it on the
wire, and holding A's outgoing message until A is opened again would be worse
than sending it as B. A read-only identity does not run the broadcast pump at all
and the npub plan's rule stands.

```kotlin
// SynchronizeNostrEventRequest, NegentropySynchronizeRequest
/** The identity that asked. Null for rows written before there was one to record, which any identity may drain. */
val ownerPublicKey: HexKey? = null,
```

Room database version 20, an `AutoMigration` with a comment in the list the way
every nullable addition before it has one: rows written before it read back null,
meaning "the device's", which is what they were.

**The stamp is the repository's, not the call site's.** Eighteen view models and
two DAO paths queue requests, and every one of them does so *as the active
identity* — a screen cannot queue anything as anyone else. `DatabaseNostrRepository`
takes `activeIdentity: StateFlow<Identity?>` at construction, which the nav host
has in hand where it builds the repository, and `queueSynchronizeNostrEvent` and
`queueNegentropySynchronizeRequest` stamp `ownerPublicKey = activeIdentity.value?.nostrPublicKey`
on every row. The DAO's own requests — the placeholder-profile syncs it plants
while indexing — are stamped from the `activeKeyPair` it is already handed. No
call site changes, and no call site can forget.

The pumps then ask for their own:

```sql
SELECT * FROM SynchronizeNostrEventRequest
WHERE status = 'pending' AND (ownerPublicKey = :publicKey OR ownerPublicKey IS NULL)
ORDER BY createdAt ASC LIMIT 1
```

`observePendingSynchronizeNostrEventRequests(publicKey)` and its negentropy twin
take the pubkey the pump already builds its key pair from. A's requests wait for
A; B never opens a wrap that was fetched for A, because B never fetches it.

### The inbox, reopened

The other end: wraps that *were* fetched under the wrong key — before this
migration, or by a read-only identity whose nsec was pasted later, the case the
npub plan's DAO guard left with the words "the key that opens this one may be
signed in later" and no code behind them.

```kotlin
// NostrDao
/**
 * Opens every gift wrap addressed to [activeKeyPair] that the device stored but
 * could not open at the time -- because another identity fetched it, or because
 * this one held no key yet. One pass: wraps do not depend on one another, so a
 * wrap that cannot be opened now will not be helped by opening its neighbours.
 */
open suspend fun reopenInbox(activeKeyPair: KeyPair): Int
```

The query is every `GiftWrapMessage` with `receiverPublicKey = me` that no
`GiftWrapSeal` names as its `giftWrapMessageId`; the body is the unseal branch of
`indexNostrEvent` from the `isAddressedTo` check down, lifted into an
`openGiftWrap(giftWrapMessage, activeKeyPair)` that both call. A Marmot welcome
inside one of them reaches `MarmotInboundManager` the way it would have on the
day; a direct message is filed as one.

It runs once per activation, from `SynchronizationViewModel`'s collector, inside
`if (identity.canSign)` and **before** the live subscriptions and the broadcast
pump are launched — so that the sweep and the live inbox are not opening the same
wrap at the same moment. Both are idempotent by event id, so the order is about
not doing the work twice rather than about correctness.

### Tests

`ReadOnlyGiftWrapDaoJvmTest` has the fixture: a wrap addressed to A, stored
through B's key pair — event and wrap rows, no seal. `reopenInbox(A)` returns one,
and afterwards the seal and payload are stored and the message is filed;
`reopenInbox(A)` again returns zero and changes nothing; `reopenInbox(B)` returns
zero. A DAO test for the filter: A's pending request is not emitted to B's pump,
a null-owner row is emitted to both, and A's is emitted to A's. And the
repository stamp: a request queued while A is active carries A's pubkey, while
nothing is active carries null.

---

## Phase 7 — leaving one profile among several

**App. Needs Phases 3 and 4.** Small, because the forget sequence was built
right.

Sign out of a read-only identity and *forget this key* for a bare key both run
`ForgetIdentity` and then the tail `listIdentities { resetToSelector() }`, which
shows the selector with what remains or Landing if nothing does. That is the right
behaviour with several profiles as well; Phase 3 added `clearDefaultWallet()` to
it, and this phase adds nothing else to the sequence. A profile with a wallet
attached answers `WalletAttached` from Phase 1 and is not offered either exit.

Two exits learn about the others:

- **The not-found screen** offers *try again* and, for a read-only identity, *use
  a different key*, which forgets it. With other profiles on the device a third
  action makes sense and costs one row: *switch profile*, pushing `ProfilesRoute`.
  The identity that was not found stays listed; forgetting it remains a separate
  decision.
- **Sign out for a profile with a wallet attached** stays `ImplementationPendingRoute`,
  for the reason both sign-in plans gave: removing a seed is a wallet question,
  and now — since the credential would come back at the next repair — it is
  also the only way that profile *can* leave. What changes is that the user is no
  longer stuck behind it: *switch profile* is the row above.

### Tests

`ForgetIdentityJvmTest` gains: forgetting B while A remains leaves A listed and
the default cleared; forgetting a profile with a wallet attached is
`WalletAttached`. `UnsyncedProfileScreen` composed with two identities has *switch
profile*; with one, it does not.

---

## Phase 8 — the tests that actually prove it

One round trip, beside the two that exist.

**Two profiles, both ways, and a boot.** Create A from a phrase — both files
written, A listed once with a wallet attached; write B's npub; list; activate A;
sign B in from inside through the sign-in view model; the tail switches; B is
active and read-only, A's node was stopped and B's never started;
`NavigationViewModel` lands on B's state; switch back; A's state and A's node
again. Queue a kind 1 for A while B is open — not signed; open A — signed. Create
C from inside: a `NostrSecret`, active, with no node. Forget B and C; one
identity, default cleared. Then a *fresh* `SovereignWalletViewModel` over the
same directory, which is what a cold boot is: the repair finds nothing to do, it
lists A, and `StartupChoice` resolves A without asking.

And `./gradlew :composeApp:m3Audit`: two new screens, a new route, and eight new
strings are where a `16.dp`, a "Switch Profile" and an untriaged
`contentDescription` arrive.

---

## Phase 9 — rollout

**No library change.** `BusinessManager.stopBusiness` exists on all three
platforms, `saveDefaultWallet` has been in `GlobalPrefs` since Phoenix, and the
credentials file's format already holds what Phase 1 writes into it. This plan
owes no tag, which is the first of the three sign-in plans that can say so.

**Phase 1 ships alone, and first.** It changes what is on disk: the first launch
after it writes a credential for every seed on the device. A build *before* it,
reached by a downgrade after that write, lists each seed-backed profile twice —
once as a wallet with a derived key, once as a bare key under a different id —
because the old `merge` kept a secret for a seed's key and trusted the writers to
have prevented it. Nothing is lost: both rows sign as the same key and share
every row keyed by it, and the next upgrade lists them as one. It is the one
line of this rollout worth a release note.

**Phases 2 and 3 ship together.** A switch without the precedence fix lands on
the selector instead of the profile it was asked for — not broken, but it is the
thing the phase is for. Phase 3 without Phase 2 is a startup screen that
remembers a default nobody can change without it.

**Phases 4 and 5 ship together.** A switcher with no way to add a profile is half
a feature; the add rows without the switcher leave a newly added profile reachable
only through startup, which is the state the device is in today with two.

**Phase 6 ships whenever it is ready, and should ship early if it can.** It is a
Room migration and a DAO change, and it fixes a case that exists *today*: a device
with two identities, switched at the startup selector a moment after one of them
signed in. The migration is a nullable column, which Room generates and an older
build cannot open — the same downgrade story every migration in this database has
had, and `PlatformDatabaseBuilder` has the same answer.

**Phase 7 can follow by a release.** Without it, the not-found screen has two
exits instead of three and a wallet-attached profile's sign-out row is the same
pending route it is now.

**Old builds.** A build before Phase 3 ignores the saved default and shows the
selector, as it does today. A build before Phase 6 drains every queue as whoever
is open, as it does today. Apart from the double listing above, nothing written
by this plan is misread by a build that predates it; it is only not read.

## Estimate

| phase | work | days | blocked by |
|---|---|---|---|
| 1 | the credential written and repaired, `merge` inverted, the activation, the forget guard | 1.5–2 | — |
| 2 | the switch, the node stop, the relay observer | 1 | 1 |
| 3 | the precedence, the default, the words | 0.5–1 | 2 |
| 4 | the switcher screen, the profile on the row | 1 | 3 |
| 5 | the add rows, a key-only create, the duplicate that switches, *end this* | 1–1.5 | 4 |
| 6 | the owner column, the pump filter, the inbox reopened | 1.5 | — |
| 7 | the exits | 0.5 | 3, 4 |
| 8 | the round trip | 0.5 | all |
| 9 | rollout | — | all |

**Roughly a week and a half**, with Phase 6 in parallel from the start if two
people are on it. Two places carry the uncertainty. Phase 1 is where the two
sign-in plans' tests are re-read against a rule they were written to enforce, and
a test that turns out to have been asserting the old model somewhere unexpected
is a test to rewrite, not to delete. Phase 6's unseal branch of `indexNostrEvent`
has never been called from anywhere but `indexNostrEvent`, and lifting it out is
where a transaction boundary will turn out to have been load-bearing.

Phases 1–8 are now implemented, in one sitting and in order. What each turned out
to require, as against what was predicted here, is in the table at the top and in
the commit messages on this branch. The Phase 6 uncertainty resolved itself by not
lifting the branch at all; the uncertainty that did bite was in no phase — a
process-wide cache in the library, reached by two threads at once for the first
time, which the tests for Phase 5 hit one run in three.

## Out of scope

- **A lazy node.** Phase 1 puts the key in the credential, so a profile with a
  wallet attached could be activated without starting its node and start it when
  a wallet feature asks. Not done here, for the reason under Phase 1: the node is
  also where the channel watcher is scheduled, and a restored Phoenix phrase can
  have channels. Its own decision, one branch in `doLoadWallet`, and the nsec
  plan's "start the node lazily" now has nothing in front of it.
- **A wallet attached to a profile that did not derive it**, or one wallet on the
  device that every profile pays from. Phase 1 makes attachment a fact about the
  listing — a seed matched to a credential by pubkey — and both sign-in plans
  deferred what it would mean to attach by *choice*. `Identity.business` is the
  seam, the identity would carry the wallet's id beside its own, and Phase 2's
  stop reads `business` rather than the kind so that the switch does not have to
  be rewritten when it lands.
- **A second fresh wallet from inside.** Reachable today only through a pasted
  recovery phrase. Until a wallet feature exists there is nothing to want it
  for.
- **Showing the nsec of a profile with a wallet attached.** Trivially possible
  after Phase 1 — the key is in the file — and a real want, for a user taking
  their profile to another client. A row on `KeyRecoveryScreen` beside the
  phrase; its own small change, with the phrase's warnings rewritten for a
  secret that has a wallet behind it.
- **Sign out for a profile with a wallet attached.** Unchanged from both sign-in
  plans, and now the only way such a profile can leave the device.
- **Two of the device's own profiles in one Marmot group.** `ChatRoom`'s primary
  key is the group id and `userPublicKey` says whose the row is; a second local
  member of the same group would overwrite the first's MLS state. Today it cannot
  happen — one profile per device — and after this plan it can, by inviting
  yourself. Refusing the invite at the join is the cheap fix; keying the room by
  `(id, userPublicKey)` is the real one and is a migration of every table that
  references a room. Named here so that the first report of it is not a mystery.
- **A database per identity.** The only design that isolates the queues, the
  cache and the collision above wholesale. Rejected below, but it is the shape
  the collision would eventually force.
- **The social precondition gate.** Every `ProfileLoaded` routes through it, on
  every launch, and so on every switch. It is an onboarding screen shown to
  people who have finished onboarding; a machine that skips it for an account it
  has seen before is one branch in `processLocalAccount`, and its own decision.
- **Per-profile lock, per-profile notifications.** The lock prompt is a stub and
  the app has no push. Both are per-id already in the preferences and would slot
  into the activation this plan routes everything through.
- **Editing a profile's local name and avatar.** The metadata dialog is a stub,
  and after Phase 4 the row shows the nostr profile, which is the name.
- **Tightening secrets in memory; NIP-46; NIP-49.** Inherited, unchanged. Phase 1
  adds one copy of a secret the device already held in derivable form, under the
  same keystore key, and the decrypt-at-activation both plans deferred applies
  to it as to the others.

## Appendix — what was considered and rejected

### A seed's profile by derivation only

The model the code had, and the one this plan's first draft built on: the
credentials file holds only bare keys, a seed is a profile because its key can
be derived, and the writers keep one key out of two files. It needs no repair
and holds every secret once. It was rejected because it puts the wallet where the
profile should be: the list of profiles is a merge of two files with opposite
ideas of what a row is, the node has to run for a profile to know its own key,
and a wallet can never belong to a profile it did not derive. The cost of
changing it — Phase 1 — is a day and a half; the cost of building a switcher on
it and changing later is that switcher, twice.

### One id space for every profile

Under the model this plan takes, a profile could be keyed by `hash160(pubkey)`
whether or not a wallet is attached, with the wallet's `hash160(nodeId)` carried
beside it. Cleaner on paper, and rejected: the library keys the node's
preferences and metadata by the wallet's id inside `startNewBusiness`, so a
seed-backed profile would have two preference files — one the app writes, one
the node reads — and every existing device would need its metadata and backup
flags copied from the wallet's id to the profile's. A profile's id being its
wallet's when it has one keeps every file where it is, keeps `stopBusiness` and
`updateBusinessActiveInUI` one argument, and costs only what the npub plan already
accepted for the read-only upgrade: a profile that acquires a wallet gets fresh
preferences.

### A live swap under the stack

Set the identity and keep the screens. Every route carries the old key, every
view model was built from a route, and the navigation component reads the key off
the current route by design. Either every screen re-reads the identity from a
flow — the parameter-in-forty-three-lists problem the snackbar host and the
signing capability were both invented to avoid — or the stack is rebuilt, which
is the restart. The restart also gets the lock gate for free: an identity is only
ever activated through `LoadWallet`.

### A global switcher control

The avatar in the app bar, as Google's apps do it; a long press on the profile
tab; a swipe on the navigation bar. The home app bar lost its avatar by an
information-architecture decision recorded in `TopLevelDestination`, and putting
one back for a different purpose is two meanings on one control. A long press is
undiscoverable. And every one of them fires a `popUpTo(0)` from wherever the user
is — a switch is one deliberate tap on a screen that says what it does, and the
profile tab's row is where the app has been promising it.

### A bottom sheet instead of a screen

The other common shape, and the app has a `ModalBottomSheet`. Rejected for
consistency rather than on merit: the startup selector is a screen, both lists
are the same widget with the same rows, and the add rows push screens anyway. A
sheet that pushes screens from under itself is a screen with a worse back story.

### Keeping the previous node running

The cheaper switch-back — `startNewBusiness` returns a running node in
milliseconds. But nothing reads the node except the key, and after Phase 1 not
even that; the restart tears down everything else built for that identity, and a
background node is Electrum, the LSP peer and the swap-in watcher for a profile
that is not open. If a wallet feature ships and a node has something to do while
its profile is closed, this is the decision to revisit, and it is one branch in
`switchToIdentity`.

### Stamping the owner at every call site

Eighteen view models each passing `activeUserPublicKey` into a queue call.
Mechanical, wide, and forgettable: the nineteenth would pass nothing. The
repository is built once, has the identity flow one parameter away, and every
request it writes is made as the active identity by construction.

### Filtering the broadcast queue by owner

Symmetry with the fetch queues. Rejected because a signed event needs no key to
send and holding A's message until A returns is a delivery failure the user would
never be told about. The asymmetry is the point: fetches are opened with a key,
sends are not.

### A database per identity

Isolates everything at once, including the group-membership collision. Costs the
shared profile cache — every kind 0 fetched once per profile — a Room instance
rebuilt on every switch, and a migration that splits the existing file by
`userPublicKey` across every table that has one and guesses for the tables that
do not. Deferred rather than rejected: it is the answer if the collision becomes
common, and nothing in this plan makes it harder to do later.
