# Signing in with an nsec

How a user who already has a nostr key gets it onto this device, why that key can
never have a wallet behind it, and the ten call sites that make the whole thing
smaller than it looks.

Read this against [jvm-target.md](./jvm-target.md) for the key-storage decision it
inherits, and with `NavigationViewModel.processLocalAccount` open for the state
machine it drives — the machine is already built, and half of this plan is about
finding its unreachable entrance.

**Built**, phases 1–7, one commit each, in the order given; Phase 8 is the rollout
and is process rather than code. The phases are kept as written because they are
the reasoning, and the code reads better against the argument it came from than
against a summary of itself. Where the implementation chose differently the
table below says so, and it did so in ways worth reading before touching any of
it:

| what the plan said | what it turned out to be |
|---|---|
| `WalletManagerExtension.nostrPublicKey()` "can go with" the ten sites | it stays: `CreateProfileViewModel` derives a fresh key's pubkey with it. Wrong by one caller |
| pull the failure classification into `TechnicalExtensions.kt` | its own file, `DecryptionFailure.kt`. `androidMain` has a `TechnicalExtensions.kt` in the same package, and a common file of that name may hold only `expect`s — anything with a body is a second `TechnicalExtensionsKt` facade the android target refuses. The `graceful*Seed*` actuals were left as they were |
| "lift the write into a helper both managers call" | `AtomicFileWrite.writeVerified`, and `SeedManager.writeSeedToDir` now goes through it with its original check and exception type |
| the nsec branch of startup "reports the startup" itself | it only sets the identity; the existing `activeIdentity != null` branch reports it. The mnemonic path reports twice (once from `onStartupSuccess`, once from that branch); the nsec path does not repeat the mistake |
| read `loadNostrProfile(startupRoute)` by the active identity | done, with a fallback to the first account when there is no identity — which is how `NavigationRoutingTest` constructs it, and never how production reaches it |
| input problems "through `ErrorState`" | on the field, as supporting text, while the prompt is still showing. `ErrorState` is for what happens *after* confirming — the key was already here, the write failed — where there is no field to put the message under |
| `SignInToProfileUIState.Confirm(kind, npub, hexKey)` | `Confirm(credential)`, with the `SignInCredential` carrying the pubkey; the two writers are injected as functions so `commit` is a suspend function with a result, testable without a key store |
| the not-found exit as a route | a state of the *screen*: `UnsyncedProfileViewModel.decide` over the account, with `NavigationUIState` unchanged. A `processed` request — an event came back, just not a kind 0 — counts as finished; the plan's test sketch said the opposite and was wrong |
| "set one up" opens `CreateProfileScreen` | an inline form on the not-found state. `CreateProfileScreen` is built around generating a seed and says so in its copy; and the kind 0 has to be written *over* the placeholder row, not beside it — see `setUpProfileForExistingKey` |
| the one hop, unspecified where | in the sync pump, after `saveNostrEvent`, with a per-account set so five indexers answering with the same kind 10002 make one hop |
| forget: key, prefs, metadata, active identity | plus the account's unsigned rows (`forgetLocalAccount`), which the plan did not list: the kind 0 that made it a local account, and anything queued that can now never be signed. Published events and the profile cache stay |
| assert `platformStartupLogic` was not called | `identity.business == null` and the jvm `BusinessManager.businessFlow` empty afterwards — the observable fact rather than the call |
| the dedupe rule: a seed's key is kept out of the key file, and a seed whose key is here as a bare secret is `SeedAlreadyExists` | **superseded** by [multiple-profiles.md](./multiple-profiles.md), Phase 1: a seed's key is a `Secret` credential like any other, written when the seed is and repaired in for every seed already here; a bare secret's phrase *attaches* the wallet rather than being refused; the identity's key is read from the credential, with the node's as a cross-check |

Two things found on the way that are not in any phase. `DataStoreManager` caches
each id's preferences in a companion object for the life of the process, so a test
that reuses a key across temporary directories is served the wrong file
(`IdentityWriterJvmTest` uses fresh keys for that reason). And the library commit
is on `claude/nostr-key-store` in the submodule, at `59c11ed`, bumped into the app
by the Phase 3 commit; it has to be pushed with this branch, and tagged for JitPack
consumers, before any of this leaves the machine.

## The constraint

The profile's nostr key is not stored anywhere. It is derived, every time the
wallet starts, at the NIP-06 path:

```kotlin
// lightning-kmp-app/library/.../managers/WalletManager.kt:105
fun LocalKeyManager.nostrPrivateKey(): PrivateKey {
    val path = KeyPath(if (isMainnet()) "m/44'/1237'/0'/0/0" else "m/44'/1237'/1'/0/0")
    return derivePrivateKey(path).privateKey
}
```

That is BIP32 child derivation — `HMAC-SHA512(chainCode, parentKey ‖ index)` at
every step — and it runs one way. Holding the leaf tells you nothing about the
seed above it, and no seed can be chosen to land on a given leaf. So the object the
whole app hangs off, `LocalKeyManager`, is out of reach for an nsec by
construction:

```kotlin
// lightning-kmp/modules/core/.../crypto/LocalKeyManager.kt:34
data class LocalKeyManager(val seed: ByteVector, val chain: Chain, val remoteSwapInExtendedPublicKey: String)
```

No `LocalKeyManager` means no node key, no `PhoenixBusiness`, no channels, no
on-chain wallet. **"Restore only the nostr key" is not a product decision** — it
is the only thing an nsec can be restored *as*. Every design below starts from
that, and the ones in the [appendix](#appendix--what-was-considered-and-rejected)
that tried to get around it are there because they cannot.

One consequence worth stating early: the twelve words and the nsec are not two
encodings of one thing, so a user who signs in with an nsec and later wants a
wallet is creating a *second* secret with a *second* recovery story. That is out
of scope here and is said so at the end.

## What the app actually needs from the wallet

Nothing but the key. That is the finding that makes this tractable.

Every consumer of the wallet in `composeApp` is the same expression:

```kotlin
activeWallet?.business?.walletManager?.keyManager?.value?.nostrPrivateKey()
```

ten times, in eight files:

| site | what it does with the key |
|---|---|
| [NotaryViewModel.kt:71](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NotaryViewModel.kt) | signs the queues, seals Marmot bundles with `nsecPassword` |
| [SynchronizationViewModel.kt:193](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SynchronizationViewModel.kt) | the three pumps' key pair |
| [RelaysSocketManager.kt:68](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt) | the pubkey whose relay list to follow |
| [NavigationViewModel.kt:193](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NavigationViewModel.kt) | the pubkey whose account to observe |
| [DkgRitualViewModel.kt:476](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/DkgRitualViewModel.kt), 516, 570 | the ChillDKG host key, derived from the nostr key |
| [SelectChatRoomTypeViewModel.kt:193](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SelectChatRoomTypeViewModel.kt) | same |
| [SelectSubgroupAdminsViewModel.kt:208](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SelectSubgroupAdminsViewModel.kt) | same |
| [NostrNotaryRepository.kt:70](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/repository/NostrNotaryRepository.kt) | signs — but nothing constructs this class; it only has to compile |

And that is the whole list. There is no call into `peerManager`, `paymentsManager`,
`balanceManager`, `sendManager` or `lnurlManager` anywhere in the app. The
Lightning node — Electrum connection, peer connection to the LSP, the WorkManager
watchers `schedulePlatformLogic` sets up — is started on every cold boot as a
**side effect of reaching a 32-byte key**.

Everything downstream of the key is already independent of the seed.
`ChillDkgRitualManager.deriveHostSecretKey` hashes the nostr key
([ChillDkgRitualManager.kt:117](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/ChillDkgRitualManager.kt));
`nsecPassword` is `hash160` of it; the notary and pumps build a quartz `KeyPair`
from its bytes. None of them would notice where the key came from.

So the leverage point is one type: put a *signing identity* between the app and
the wallet, and an nsec becomes "an identity with no wallet behind it". The ten
sites collapse to one flow, and two of them get simpler — the `flatMapLatest` over
the key manager's `StateFlow` in the notary and the relay manager exists only
because the key arrives late, after the node; an identity is whole from the moment
it exists.

## What is already built

More than you would expect. The nostr half of "restore" was designed for the
app's earlier life as Torch and left in place, unreachable, when the seed became
the only way in.

| piece | where | state |
|---|---|---|
| `signInToProfile(pubkey)` — plants a kind‑0 `UnsignedNostrEvent` stamped `signedAt = GENESIS_AT`, so the notary skips it and the navigation machine treats the account as "signed, needs syncing" | [DatabaseNostrRepository.kt:215](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt) | built, no caller |
| the state machine that takes it from there: `UnqueuedProfileSynchronization → UnsyncedProfile → UnindexedProfile → ProfileLoaded` | [NavigationViewModel.kt:96](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NavigationViewModel.kt) | built |
| the `purpose = "sign-in"` relay sync for kinds 0, 3, 10002, 10005, 10007, 10012, 10050 and 10086 — profile, contacts, and every relay list the app reads | [UnqueuedProfileSynchronizationViewModel.kt:57](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/UnqueuedProfileSynchronizationViewModel.kt) | built, asks one relay — see [Phase 5](#phase-5--finding-the-profile-and-not-finding-it) |
| a `SignInToProfileViewModel` that parses `nsec1…`/`npub1…`/`nostr:` with quartz's `Nip19Parser` and calls `signInToProfile`; a `SignInToProfileUIState` with `InputPrompt`, `ConfirmNsecSignIn(nsec, hexKey)`, `ConfirmNpubSignIn`, `Error`; a `SignInToProfileFormState` | [SignInToProfileViewModel.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SignInToProfileViewModel.kt) | built, unreferenced — and it **drops the nsec on the floor**; nothing ever stored it |
| `String.extractKeyPairFromPrivateKeyOrThrow()` — hex or bech32 in, `(nsec, npub)` out, `InvalidNostrPrivateKeyException` otherwise | [Credentials.kt:60](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/extensions/Credentials.kt) | built |
| strings: `enter_the_nsec_or_npub_read_only_that_you`, `sign_in_to_nsec`, `sign_in_with_an_npub`, `be_sure_to_keep_this_nsec_safe`, `sign_in_to_torch_via_nsec_or_remote_signer` | `strings.xml` | present, unused |
| `ActiveWallet.business` is already `PhoenixBusiness?` | [Wallet.kt:50](../lightning-kmp-app/library/src/commonMain/kotlin/fr/acinq/phoenix/data/Wallet.kt) | nullable today |
| `keyStoreEncryption` / `keyStoreDecryption` — public `expect` functions with android, jvm and ios actuals, parameterised by key alias | [KeyStoreFunctions.kt](../lightning-kmp-app/library/src/commonMain/kotlin/fr/acinq/phoenix/security/KeyStoreFunctions.kt) | usable as-is |
| `platformWriteSeed(…, isRestoringWallet = …)` — the seed writer already has a restore flag | [NavigationViewModel.android.kt:64](../composeApp/src/androidMain/kotlin/com/machankura/dedun/compose/ui/view/model/NavigationViewModel.android.kt) | built; recovery-phrase sign-in is nearly free once a screen exists |
| `SignInToProfileScreen` | [SignInScreen.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SignInScreen.kt) | a sentence saying sign in is not available |

The gap, then, is exactly two things: **somewhere for the nsec to live**, and
**something for the app to read the key from that is not the node**. Everything
else is wiring.

## What actually blocks it

Four things, in dependency order.

**The at-rest store is shaped for words.** `seed.dat` is
`EncryptedSeed.V2.MultipleSeed`: the platform keystore's AES over a JSON
`Map<nodeIdHash, List<word>>`
([EncryptedSeed.kt:60](../lightning-kmp-app/library/src/commonMain/kotlin/fr/acinq/phoenix/security/EncryptedSeed.kt)),
and `SeedManager.loadAndDecrypt` runs `MnemonicCode.toSeed` on every entry and
builds a `LocalKeyManager` from it to learn the wallet id
([SeedManager.kt:48](../lightning-kmp-app/library/src/commonMain/kotlin/fr/acinq/phoenix/managers/SeedManager.kt)).
A 32-byte key has no place in that shape, and the [appendix](#extending-encryptedseed-with-a-version-4-payload)
says why it should not be given one.

**The identity is carried by the node.** `activeWalletInUI:
StateFlow<ActiveWallet?>` is set only from `StartBusinessResult.Success`
([SovereignWalletViewModel.kt:118](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt)),
and twenty-five parameters across eighteen files are typed on it.

**Startup only knows about wallets.** `SovereignWalletStartupScreen` sends an
empty `availableWallets` to the landing page
([SovereignWalletStartupScreen.kt:91](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SovereignWalletStartupScreen.kt))
and starts whatever is selected with `startupNode(words)` (line 171). An nsec
stored anywhere else is invisible to it, and `NavigationViewModel.observeProfile`
answers a null `business` by navigating back to startup
([NavigationViewModel.kt:187](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NavigationViewModel.kt))
— a loop, for an identity that will never have one.

**The bootstrap asks the wrong relay.** The sign-in sync queues its `REQ` at
`Relays.DefaultDMRelayList`, which is `listOf(ephemeral)` —
`wss://ephemeral.mantra.press`
([Relays.kt:61](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/Relays.kt)).
That is the right relay for a profile *this app* created. An identity that has
lived on Damus for three years has never heard of it, and the machine has no exit
for "nothing came back".

## The one decision to make first

Should a mnemonic identity still start the Lightning node to reach its key?

`CreateProfileViewModel` already derives the pubkey without one — it constructs
`LocalKeyManager(seed)` locally and reads `nostrPublicKey()` off it
([CreateProfileViewModel.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/CreateProfileViewModel.kt)).
Doing the same at startup would make both kinds of identity symmetric — secret →
key in memory, node optional — and would take the Electrum and LSP connections off
every cold boot of what is, today, a chat application.

**This plan keeps the node start for mnemonic identities.** Three reasons.
`BusinessManager.startNewBusiness` does more than load a key — it registers wallet
metadata, applies Electrum and Tor preferences, records the last-used app code,
and the comments in `SovereignWalletStartupScreen` and `SovereignWalletViewModel`
are the record of a flow that has already bitten twice; moving it is its own piece
of work with its own review. The lazy-start path will have to exist anyway the
day a wallet feature ships. And Phase 1 makes the later change small: once
everything reads an `Identity`, whether the node is behind it is one branch in one
place.

So: the nsec branch has no node, the mnemonic branch keeps the one it has, and
"start the node lazily" is a follow-on named under [Out of scope](#out-of-scope).

---

## Phase 1 — an identity in front of the wallet

**App only. No behaviour change.** This is the refactor everything else stands on,
and it should land alone so that its diff is boring.

### The type

```kotlin
package com.machankura.dedun.compose.identity

enum class IdentityKind {
    /** Twelve words. The nostr key is derived from them, and so is a wallet. */
    Mnemonic,
    /** A bare nostr secret. Nothing else can be derived from it. */
    NostrSecret,
}

data class Identity(
    val id: WalletId,
    val kind: IdentityKind,
    val nostrPrivateKey: PrivateKey,
    val userPrefs: UserPrefs,
    val internalPrefs: InternalPrefs,
    /** The running node. Non-null only for [IdentityKind.Mnemonic]. */
    val business: PhoenixBusiness?,
) {
    /** X-only, hex — the form every nostr call site wants. Computed once. */
    val nostrPublicKey: HexKey = nostrPrivateKey.publicKey().xOnly().value.toHex()

    override fun toString() = "Identity(id=$id, kind=$kind, key=<redacted>)"
}
```

It replaces `ActiveWallet` in the app rather than wrapping it. `ActiveWallet` is
declared in the library but nothing in the library uses it — it is an app type
that happens to live in the wrong module — and flattening its three fields into
`Identity` means `KeyRecoveryScreen` and `RecoveryPhraseViewModel`, which read
`internalPrefs` off the active wallet today, keep working for an identity that
has no wallet.

**`WalletId` stays as the id.** The field is called `nodeIdHash` and for an nsec
identity it will not be one, but every preference in the library keys on the type
— `loadUserPrefsForWallet`, `loadInternalPrefsForWallet`, `UserWalletMetadata`,
`getDefaultWallet` — and a second id type would mean a second copy of each. For
an nsec identity:

```kotlin
// app side; WalletId has no companion to hang this off
fun XonlyPublicKey.toWalletId(): WalletId =
    WalletId(Crypto.hash160(value).byteVector().toHex())
```

Same shape as `WalletId(nodeId: PublicKey)` — forty hex characters of a
`hash160` — so nothing downstream can tell the kinds apart by the id, which is
what you want from an id. The kind is on the `Identity`, where it can be asked.
The two hashes are over different keys (the node key at `m/50'/0'`, the nostr
key at `m/44'/1237'/…`), so a mnemonic wallet and its own nostr identity would
never share an id even if both were registered — which is exactly the case
Phase 4's dedupe has to catch by pubkey instead.

### The flow

`SovereignWalletViewModel` gains

```kotlin
private val _activeIdentity = MutableStateFlow<Identity?>(null)
val activeIdentity = _activeIdentity.asStateFlow()
```

and `setActiveWallet(walletId, business)` becomes `setActiveIdentity(identity)`,
with the mnemonic caller building the identity from the node it just started:

```kotlin
val keyManager = business.walletManager.keyManager.value
    ?: error("business started without a key manager")
Identity(
    id = walletId,
    kind = IdentityKind.Mnemonic,
    nostrPrivateKey = keyManager.nostrPrivateKey(),
    userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId),
    internalPrefs = dataStoreManager.loadInternalPrefsForWallet(walletId),
    business = business,
)
```

`activeWalletInUI` goes. The twenty-five `StateFlow<ActiveWallet?>` parameters
become `StateFlow<Identity?>` — a rename in most of them; the eighteen files are
listed by `grep -rl 'StateFlow<ActiveWallet?>' composeApp/src/commonMain`.

### The ten sites

Each becomes a read of `activeIdentity.value?.nostrPrivateKey` (or
`?.nostrPublicKey`). Two deserve a word:

- **NotaryViewModel** and **RelaysSocketManager** currently `flatMapLatest` from
  the wallet flow into the key manager flow, because the key arrives after the
  node. With an identity there is one flow and one `collectLatest`, and the
  children it launches are cancelled on identity change exactly as they are now.
  Keep the `distinctUntilChanged` on the key: a `data class` `Identity` compares
  by value and the prefs objects inside it are stable per id, so it already
  behaves, but the comment on `observeUnsignedNostrEvents` is about a
  `distinctUntilChanged` that bit once; do not remove one without reading it.
- **NavigationViewModel.observeProfile** loses its `business == null →
  StartupPhoenix` branch. That branch was the only thing that made a nodeless
  identity loop; a null *identity* still means "go start something", which is
  right.

`WalletManagerExtension.kt` — the app's own `LocalKeyManager.nostrPublicKey()` —
is used by exactly these sites and can go with them.

### Tests

`NavigationRoutingTest` constructs a `NavigationViewModel` around a
`MutableStateFlow<ActiveWallet?>`; it becomes `MutableStateFlow<Identity?>` and
gains one case: an identity with `business = null` and a local account routes to
`ProfileLoaded`, not `StartupPhoenix`. That is the regression this phase is for,
and it is the assertion Phase 3 will lean on.

---

## Phase 2 — the nostr key store

**Library. Independent of Phase 1.**

A sibling of the seed file, not a change to it: `node-data/nostr-keys.dat`,
encrypted under the same keystore key, with the same write discipline, read by a
manager shaped like `SeedManager`.

### Why the same alias

`KeyStoreNames.KEY_NO_AUTH`. Not for convenience — because Android gives no
choice:

```kotlin
// KeystoreHelper.kt:74
private fun getKeyForName(keyName: String): SecretKey = when (keyName) {
    KeyStoreNames.KEY_NO_AUTH -> getOrCreateKeyNoAuthRequired()
    KeyStoreNames.KEY_FOR_PINCODE_V1 -> getOrCreateKeyNoAuthRequired()
    else -> throw IllegalArgumentException("unhandled key=$keyName")
}
```

A new alias is a library change on that platform regardless, and both existing
aliases already resolve to the one key. On the jvm, `JvmKeyStore.unlock` runs in
`Main.kt` before anything reads
([Main.kt:146](../composeApp/src/jvmMain/kotlin/com/machankura/dedun/desktop/Main.kt)), so a
second file under the same alias costs nothing there either; the ios keychain
helper is likewise keyed by alias.

### The format

```
byte 0        file version = 1
bytes 1..16   iv
bytes 17..    ciphertext of UTF-8 JSON: { "<x-only pubkey hex>": "<private key hex>", … }
```

One version byte, not two. `EncryptedSeed.V2` carries a second because it has to
distinguish `SingleSeed` from `MultipleSeed`; this file has one shape and a new
shape would be a new version. Keyed by the **public** key so that a lookup and a
duplicate check are the same map operation, and so that the store can list what
it holds without decrypting anything more than it already has.

```kotlin
package fr.acinq.phoenix.security

class EncryptedNostrKeys(val iv: ByteArray, val ciphertext: ByteArray) {
    fun decryptAndGetKeyMap(): Map<String, PrivateKey>
    fun serialize(): ByteArray
    companion object {
        const val VERSION: Byte = 1
        fun deserialize(bytes: ByteArray): EncryptedNostrKeys
        fun encrypt(keys: Map<String, PrivateKey>): EncryptedNostrKeys   // KEY_NO_AUTH
    }
}
```

```kotlin
package fr.acinq.phoenix.managers

object NostrKeyManager {
    fun loadAndDecrypt(phoenixGlobal: PhoenixGlobal): DecryptNostrKeysResult
    fun loadAndDecryptOrNull(phoenixGlobal: PhoenixGlobal): Map<String, PrivateKey>?   // empty map when no file
    fun writeToDisk(phoenixGlobal: PhoenixGlobal, keys: EncryptedNostrKeys)
}
```

`writeToDisk` is `SeedManager.writeSeedToDir` with the type changed: encrypt to
`temporary_nostr_keys.dat`, read it back, compare `iv` and `ciphertext`
byte-for-byte, `atomicMove` over `nostr-keys.dat`, delete the temp file on any
failure. That discipline exists because a truncated seed file is an unrecoverable
wallet; a truncated key file is an unrecoverable identity, and the reasoning
transfers whole. Lift the body into a private helper both managers call rather
than copying it — the diff will show whether that is one function or two.

`DecryptNostrKeysResult` mirrors `DecryptSeedResult` — `Success(map)`,
`Failure.FileNotFound`, `SerializationError`, `KeyStoreFailure(cause)`,
`DecryptionError(cause)`, `FileUnreadable` — and the exception mapping is the
one `gracefulMultiSeedDecryption` does per platform
([TechnicalExtensions.android.kt:17](../lightning-kmp-app/library/src/androidMain/kotlin/fr/acinq/phoenix/utils/extensions/TechnicalExtensions.android.kt)):
`SerializationException`/`IllegalArgumentException` → serialization,
`KeyStoreException` → keystore, else → decryption. Those three `inline` actuals
are typed on `DecryptSeedResult`; rather than three more, pull the classification
into a common `expect fun classifyDecryptionFailure(e: Exception): DecryptionFailureKind`
and have both result types map from it.

### Two small things while in this file

**`LocalKeyManager.nostrPublicKey()` in the library is not a nostr public key.**
It returns `nostrPrivateKey().publicKey().toHex()` — the 33-byte compressed
encoding, sixty-six hex characters. Nostr keys are x-only, sixty-four. Nothing in
the app calls it (the app has its own, correct, `com.machankura.dedun.compose.extensions.nostrPublicKey`),
which is the only reason it has not mattered. Fix it or delete it; a helper this
plan does want is

```kotlin
fun PrivateKey.nostrPublicKeyHex(): String = publicKey().xOnly().value.toHex()
```

so that the app's `Identity.nostrPublicKey` and the store's map key are computed
the same way in one place.

**Testnet derives at account `1'`.** `m/44'/1237'/1'/0/0` is legal — NIP-06 leaves
the account index to the application — but an imported nsec bypasses the chain
entirely, so a testnet build signs with whatever key was pasted. That is correct;
it is only worth a comment next to the path so nobody "fixes" it.

### Tests

Two, in the library, each following a test that already exists.

`EncryptedNostrKeysTest` in `commonTest`, after `EncryptedSeedTest`: the
serialized layout spelled out as literal bytes — version byte, sixteen iv bytes,
the rest — because, as that test's header says, the format is a compatibility
contract and the expected bytes must not be derived from the constants under test.

A round trip in `jvmTest`, after `JvmKeyStoreTest`: `JvmKeyStore.unlock` against
a temp directory, `NostrKeyManager.writeToDisk`, `loadAndDecrypt`, and the same
map back. This is the one platform where encrypt-and-decrypt can be exercised on
the host, and it covers the atomic write path, which the layout test cannot.

---

## Phase 3 — listing and starting identities

**App. Needs Phases 1 and 2.**

### Listing

`SovereignWalletViewModel.listAvailableWallets` reads one store and exposes
`availableWallets: Map<WalletId, UserWallet>`. It becomes `listIdentities`,
reads both, and exposes

```kotlin
sealed interface StoredIdentity {
    val id: WalletId
    val nostrPublicKey: HexKey
    data class Mnemonic(val userWallet: UserWallet, override val nostrPublicKey: HexKey) : StoredIdentity {
        override val id get() = userWallet.walletId
    }
    data class NostrSecret(override val id: WalletId, override val nostrPublicKey: HexKey, val privateKey: PrivateKey) : StoredIdentity
}
val availableIdentities: StateFlow<Map<WalletId, StoredIdentity>>
```

For a mnemonic entry the nostr pubkey is one `LocalKeyManager(MnemonicCode.toSeed(words))`
away; `SeedManager.loadAndDecrypt` has already built exactly that key manager to
learn the node id, so this is the second derivation, not a new cost. The pubkey
is needed at listing time for one reason — see [dedupe](#dedupe-on-the-nostr-key-not-the-id)
in Phase 4 — and it is what the selector should show for an nsec identity, where
there is no node id to show.

On secrets in memory: `availableWallets` holds decrypted words today for the
lifetime of the view model, because `startupNode(words)` needs them.
`StoredIdentity.NostrSecret` holds the private key for the same reason and is no
worse. Tightening that — decrypt at activation, hold only ids and pubkeys in the
list — is a real improvement and applies to both kinds equally, which is why it
is not done here.

Metadata registration is unchanged: the loop that saves a `UserWalletMetadata`
with a random avatar for every id it has not seen
([SovereignWalletViewModel.kt:128](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt))
works on `WalletId` and does not care what is behind it.

### Starting

`SovereignWalletStartupScreen` picks a `StoredIdentity` where it now picks a
`UserWallet`, and `LoadWallet` — the screen-lock gate — takes the identity rather
than the wallet. It only ever read `walletId` off the wallet, to look up the lock
preferences, so the change is the parameter type; and it matters that the gate
sits *outside* the branch, so a future lock is not something to remember to add
twice:

```kotlin
LoadWallet(stored, metadata, userPrefs, promptScreenLockImmediately) { stored ->
    when (stored) {
        is StoredIdentity.Mnemonic ->
            sovereignWalletStartupViewModel.startupNode(stored.id, stored.userWallet.words) { business ->
                sovereignWalletViewModel.setActiveIdentity(identityFor(stored.id, business))
                onSuccessfulStartup()
            }
        is StoredIdentity.NostrSecret -> {
            sovereignWalletViewModel.setActiveIdentity(
                Identity(
                    id = stored.id,
                    kind = IdentityKind.NostrSecret,
                    nostrPrivateKey = stored.privateKey,
                    userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
                    internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
                    business = null,
                )
            )
            onSuccessfulStartup()
        }
    }
    loadingIdentity = null
}
```

No `platformStartupLogic`, no `schedulePlatformLogic`, no `StartupViewState`
transitions for the nsec branch — an nsec identity is active the moment it is
read. The `StartingBusiness`/`BusinessActive` spinners that follow the `when` in
the screen today are reached only from the mnemonic branch, which is the one that
still has something to wait for.

`availableWallets.isEmpty()` (line 91) becomes `availableIdentities.isEmpty()`.
The default-wallet and desired-wallet logic keys on `WalletId` and needs nothing.

### The other entrance, and the race it hides

`NavigationViewModel` decides where a boot goes from two places, and only one of
them is the flow the ten sites feed. `onSuccessfulStartup` calls
`loadNostrProfile(startupRoute)`, which reads

```kotlin
val activeUserPublicKey = nostrRepository.getLocalAccounts().firstOrNull()?.profile?.publicKey
if (activeUserPublicKey == null) { … Landing … }
```

Two things are wrong with that line for this plan. It takes the *first* kind-0
account on the device, whichever identity is active — harmless with one wallet,
wrong the moment there are two. And it keys off `profile.publicKey`, which is the
`Profile` row the notary upserts when it *signs* the kind 0. A placeholder
account planted by `signInToProfile` has an `UnsignedNostrEvent` and no
`Profile`, so this reads null and answers **`Landing`** — for a key that has just
been imported. Meanwhile `observeProfile`, collecting the same account through
the identity flow, answers `UnqueuedProfileSynchronization`. Both write
`_navigationUIState`; the order is whichever coroutine runs last, and `Landing`
navigates with `popUpTo(0)`.

The create flow has the same window today — between `createNewProfile` and the
notary signing the kind 0, there is no `Profile` either — and gets away with it
because the flow collector keeps emitting as the rows change. An imported
identity's rows change less often. So, in this phase:

```kotlin
val identity = activeIdentity.value
val account = nostrRepository.getLocalAccounts()
    .firstOrNull { it.unsignedNostrEvent?.pubKey == identity?.nostrPublicKey }
val activeUserPublicKey = account?.unsignedNostrEvent?.pubKey
```

Read by the active identity, keyed on the unsigned event's `pubKey` — the row
`signInToProfile` writes and `getLocalAccounts()` selects on — and pass the
account straight to `processLocalAccount`, which already knows what a placeholder
means. `NavigationRoutingTest` gains the fixture it is missing: an account whose
`profile` is null and whose `signedAt` is `GENESIS_AT`, asserted to land on
`UnqueuedProfileSynchronization` and never on `Landing`.

### The selector

The app's `WalletsSelector` takes `Map<WalletId, UserWallet>` and shows
`userWallet.nodeId` in monospace
([WalletsSelector.kt:55](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/wallet/WalletsSelector.kt)).
It takes `Map<WalletId, StoredIdentity>` and shows the npub for an nsec identity
and the node id for a mnemonic one — or the npub for both, which is the identifier
the rest of the app uses and the one a user might actually recognise. The
`onWalletClick` callback carries the `StoredIdentity`.

### Hoist the seed writer while you are here

The three `platformWriteSeed` actuals — android, jvm, ios — are byte-identical
(`diff` of the three bodies is empty). Every symbol they use is `commonMain`:
`SeedManager`, `EncryptedSeed`, `LocalKeyManager`, `DataStoreManager`,
`AppVersion`. They are an `expect` because something once needed to differ and
nothing does now. Before adding a second writer beside them, make them one
common function; then add

```kotlin
suspend fun writeNostrKey(phoenixGlobal, privateKey: PrivateKey, isTorEnabled, customElectrumServer): WalletId
```

next to it, with the same shape: load the existing map, refuse a duplicate, add,
encrypt, write, save the per-id prefs, return the id. The Tor and Electrum prefs
are meaningless for an nsec identity; save them anyway, because
`loadUserPrefsForWallet` is what creates the prefs file and the recovery screens
read it.

### Tests

`NavigationRoutingTest` already covers the state machine from a local account
inward. Add, in `jvmTest`, a `SovereignWalletViewModel` listing test that seeds
both stores through the jvm keystore (Phase 2's fixture) and asserts the merged
map: one entry per secret, ids of the right shape, pubkeys that match what
`Identity` computes. This is the test that would have caught the two stores
disagreeing about what an id is.

---

## Phase 4 — the sign-in screen

**App. Needs Phase 3.** `SignInToProfileScreen` becomes a screen.

### Shape

One screen, one text field, two things it accepts — because a user does not
choose an input type, they paste what they have, and the two are unambiguous:

| pasted | recognised as | validation |
|---|---|---|
| twelve (or twenty-four) space-separated words | recovery phrase | `MnemonicCode.validate(words, English.wordlist())` — the checksum catches a wrong word |
| `nsec1…`, or `nostr:nsec1…` | nostr secret | quartz `Nip19Parser.uriToRoute(...)?.entity is NSec`, as the skeleton already does |
| 64 hex characters | nostr secret | `extractKeyPairFromPrivateKeyOrThrow()` accepts it; the confirm step shows the npub so a wrong paste is visible |
| `npub1…` | rejected, with the reason | see [Out of scope](#out-of-scope) — the app cannot do anything with a key it cannot sign with |
| `ncryptsec1…` | rejected, with the reason | NIP-49; a cheap follow-on, named in Out of scope |

The field is a `TextFieldForm` with `SignInToProfileFormState`, which exists.
Trim, collapse whitespace, lower-case a hex string; do not lower-case words
(the wordlist is lower-case already and a capitalised word is a paste artefact
worth showing rather than silently fixing).

### Flow

`SignInToProfileUIState` already has the states; use them, with `ConfirmNsecSignIn`
generalised to a `Confirm(kind, npub, hexKey)`:

1. **InputPrompt.** The field, a paste button, and the sentence from
   `enter_the_nsec_or_npub_read_only_that_you` rewritten for what is actually
   accepted — a recovery phrase or an nsec. Sentence case. New string.
2. **Confirm.** The derived npub, in full, in monospace, with which kind was
   recognised, and one button. This is the step that catches the paste of the
   wrong nsec, and for a recovery phrase it is where the user learns which
   profile the words open. The nsec itself is never echoed back.
3. **Committing.** `LoadingDataIndicator`. What happens is in the next section.
4. **Error**, through `ErrorState` with an `onRetry` that returns to the prompt;
   `null` is not the right answer here. Failures are named: invalid checksum,
   not a key, already on this device, could not write.

Four states, said so, wrapped in `ScreenStateTransition` since the `when` is the
body. Snackbar for the one success message, read above the handler through
`rememberNotifier`. Content root has `readableContent()`; the field spans it.

### Committing an nsec

```kotlin
val privateKey = PrivateKey.fromHex(entity.hex)           // NSec.hex, 32 bytes, from the parser
val pubkey = privateKey.nostrPublicKeyHex()
rejectIfKnown(pubkey)                                      // below
val id = writeNostrKey(phoenixGlobal, privateKey, …)      // Phase 3's writer
nostrRepository.signInToProfile(publicKey = pubkey)        // the kind-0 placeholder, GENESIS_AT
sovereignWalletViewModel.listIdentities {
    sovereignWalletViewModel.switchToWallet(id)
    navController.navigate(SovereignWalletStartupRoute)
}
```

The tail is the one `CreateProfileRoute` already uses after `writeSeed`
([DedunNavHost.kt:505](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt)):
re-list, select, go to startup. Startup finds a `StoredIdentity.NostrSecret`,
activates it, `NavigationViewModel` observes its pubkey, finds the placeholder
account with `signedAt != null` and no sync requests, and lands on
`UnqueuedProfileSynchronization`. From there the existing machine runs.

The order of the two middle lines is not a style choice. The placeholder has to
be in the database **before** the identity is activated: `observeProfile` starts
collecting the account the moment the identity flow emits, and an account that
is not there yet reads as `null`, which `processLocalAccount` answers with
`Landing`. It would correct itself when the row landed, through a `popUpTo(0)`
the user can see. Write the key, plant the placeholder, then re-list.

### Committing a recovery phrase

```kotlin
MnemonicCode.validate(words, wordlist)
val pubkey = LocalKeyManager(MnemonicCode.toSeed(words, "").byteVector(), chain, xpub).nostrPrivateKey().nostrPublicKeyHex()
rejectIfKnown(pubkey)
sovereignWalletViewModel.writeSeed(words, isRestoringWallet = true, onSeedWritten = { id ->
    nostrRepository.signInToProfile(pubkey)
    listIdentities { switchToWallet(id); navigate(SovereignWalletStartupRoute) }
})
```

Same tail. `writeSeed` already carries `isRestoringWallet`; the only new line is
the `signInToProfile` that tells the machine this pubkey has a history to fetch
rather than a profile to create. Without it a restored wallet with no local rows
goes to `Landing` and is offered "create profile" for a key that already has one
— that is the state of restore today, and it is why the two inputs belong on one
screen.

### Dedupe on the nostr key, not the id

`platformWriteSeed` refuses a seed whose `WalletId` is already in the map. That
is the wrong key for this check. A mnemonic wallet and an imported nsec can be
the **same npub** with **different ids** — one is `hash160(nodeId)`, the other
`hash160(nostrPubkey)` — and the database is keyed by pubkey: `getLocalAccounts()`
is `SELECT * FROM UnsignedNostrEvent WHERE kind = 0`, joined to `Profile` on
`pubKey`. Two identities for one pubkey would share every row and disagree about
which is active.

So `rejectIfKnown(pubkey)` checks the merged `availableIdentities` by
`nostrPublicKey`, for both inputs. The existing `WalletId` check stays; it is a
cheaper first pass for the seed case, not a replacement.

### Landing

`LandingScreen`'s "Sign in" already navigates to `SignInRoute`. The caption
beneath it, `sign_in_to_torch_via_nsec_or_remote_signer`, promises a remote
signer this plan does not deliver; change it to name what the screen accepts.

### Tests

`SignInToProfileViewModel` gets a `jvmTest` beside
`EditGroupCuratedSchemaViewModelJvmTest`: each row of the table above, in and
out — the npub for a known nsec test vector (NIP-06's own vectors give a
mnemonic → nsec → npub triple, which covers both inputs against the same
expected key); a wrong word; an `npub`; an `ncryptsec`; a duplicate. The
repository is `NostrRepository.NO_OP_NOSTR_REPOSITORY` with `signInToProfile`
overridden to record, so the test also proves the placeholder is planted exactly
once and only after the write succeeds.

---

## Phase 5 — finding the profile, and not finding it

**App. Needs nothing above it to compile, but is pointless before Phase 4 gives
it a caller.**

### Ask the relays that would know

`UnqueuedProfileSynchronizationViewModel.queueSynchronization` fans the sign-in
`REQ` out over `Relays.DefaultDMRelayList` — one relay, ours. Change it to
`Relays.DefaultIndexerRelayList`
([Relays.kt:65](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/Relays.kt):
purplepag.es, indexer.coracle.social, user.kindpag.es, directory.yabu.me,
nostr1) **plus** `ephemeral`. Indexer relays exist to hold everyone's kinds 0, 3
and 10002; that is the whole of their purpose, and it is exactly the list the
sync asks for. Our own relay stays in the set so a profile created here is found
here.

Then follow the answer. When the sync indexes a kind 10002 for this pubkey, queue
a second, `level = 1`, `purpose = "sign-in"` request at the relays it names, for
the same kinds. That is the outbox model doing what it is for, and the
`SynchronizeNostrEventRequest.level` field is already there to mark the hop.
Bound it at one hop; the user's own relays are where their relay list is
authoritative, and anything past that is the feed, not the profile.

### Give the machine an exit

Today `processLocalAccount` reads

```kotlin
unsignedNostrEvent.signedAt != null && synchronizeNostrEventRequests.isNotEmpty() -> UnsyncedProfile
```

and `UnsyncedProfile` shows "we are searching the internet to find your profile"
with a spinner, forever. Nothing records that a search *finished empty*. A
request goes `pending → sent` when its `REQ` is dispatched
([DatabaseNostrRepository.kt:489](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt))
and `sent → processed` only when an event arrives for it (line 532, which also
stamps the `nostrEventId`). A relay that answers with EOSE and nothing else
leaves its request at `sent` for good; the EOSE branch in
`SynchronizationViewModel` closes the subscription and writes nothing down. So
"still searching" and "searched, found nothing" are the same row.

Two changes:

1. **Record completion.** On EOSE, CLOSED, or the bounded timeout the
   subscription already enforces, upsert any request still at `sent` with
   `status = "complete"`. A request already at `processed` stays there — an
   event arrived, which is the better answer. This is the one place the app
   learns a relay has said everything it has, and it is currently thrown away.
2. **Observe it.** `UnsyncedProfileViewModel` watches the account's sign-in
   requests; when none is still `pending` or `sent` and there is still no kind 0
   indexed for the pubkey, it moves the screen to a **not-found** state: "We could not
   find a profile for this key on the relays we asked" with two actions —
   *try again* (re-queue at level 0) and *set one up*. The second opens the
   existing `CreateProfileScreen` form (name, bio) and calls
   `nostrRepository.createNewProfile(pubkey, name, bio)` — the six-event
   bootstrap a new key gets — **without** the seed generation that
   `CreateProfileViewModel.createAccount` does first. The key already exists; only
   the events do not.

The relay list that "set one up" writes is the default one, which is the right
answer for a key that has never published anywhere.

This exit is not nsec-specific. A restored recovery phrase whose profile was
never published, or a device offline at sign-in, sits on the same spinner today.
It is listed here because an imported nsec is the first path that will hit it
routinely: many nostr keys are made in a client that never wrote a kind 0.

### Tests

The navigation state stays `UnsyncedProfile` throughout — not-found is a state
of the *screen*, decided by its view model, so `NavigationRoutingTest` needs
nothing here. `UnsyncedProfileViewModel` gets a `jvmTest` over a fake repository
that emits the account's sign-in requests in sequence — all `pending`, then
`sent`, then `complete` — with no kind 0 indexed, and asserts the screen state
flips to not-found on the last emission and not before; and a second run where
one request goes `processed` instead, asserting it never flips at all.

---

## Phase 6 — recovery, for an identity with no phrase

**App. Needs Phase 3.**

`KeyRecoveryScreen` offers one backup: *recovery phrase*, routing to
`RecoveryPhraseRoute`, whose view model reads `userWallet.words` out of the
decrypted seed map
([RecoveryPhraseViewModel.kt:114](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/RecoveryPhraseViewModel.kt)).
For an nsec identity there are no words, and `NoPhraseForThisWallet` is the
honest error it would show. Instead:

- **`KeyRecoveryScreen` branches on `identity.kind`.** A mnemonic identity keeps
  the phrase option. An nsec identity gets a *nostr secret key* option in its
  place — `Icons.Default.Key`, `be_sure_to_keep_this_nsec_safe` as the
  description, which exists — routing to a `NostrSecretRoute`.
- **`NostrSecretScreen`** is `RecoveryPhraseScreen` with the word grid replaced by
  the nsec: hidden until revealed, revealed in monospace with a copy action, the
  same "I have saved it" and "I understand" checkboxes, hidden again on leaving.
  It reads the key from `NostrKeyManager.loadAndDecrypt` at reveal time, as the
  phrase screen reads the seed file — not from the active identity — so the
  screen's contract ("nothing secret held longer than it is shown") is the same
  for both. The nsec is `privateKey.value.toHex().hexToNsecHrp()` —
  `Credentials.kt` already has the encoder.
- **The backup flags reuse.** `isManualSeedBackupDone` and
  `isSeedLossDisclaimerRead` live in the per-id `InternalPrefs`
  ([InternalPrefs.kt:66](../lightning-kmp-app/library/src/commonMain/kotlin/fr/acinq/phoenix/utils/preferences/InternalPrefs.kt)),
  so `showSeedBackupNotice` already means "this identity's secret is not backed
  up" for whichever secret it is. The screens say "phrase" in three strings;
  those become kind-aware or neutral ("recovery information", which the cloud
  backup row already uses).
- **The disclaimer changes meaning.** `i_understand_that_if_i_lose_this_phone_and`
  says "…I lose this profile and the funds in its wallet". An nsec identity has
  no wallet; a new string for that kind, so the app is not warning about funds
  it cannot hold.
- **Forget this key.** An import needs an inverse. Under the key options, for an
  nsec identity only: remove the key from `nostr-keys.dat`, delete its prefs
  (`DataStoreManager.deleteNodeUserPrefs` exists), hide its metadata, clear the
  active identity, and go to startup. Behind a confirmation that names the npub
  and says the profile stays on the relays. This is the first real "sign out"
  in the app — `ActiveProfileScreen`'s button routes to
  `ImplementationPendingRoute("Sign out")` — and the general case stays pending;
  removing a *seed* is a wallet question and is not this plan's to answer.

The two wallet-shaped rows on `KeyRecoveryScreen` — cloud backup, emergency kit
— are `ImplementationPendingRoute` today and stay for both kinds.

---

## Phase 7 — the tests that actually prove it

The per-phase tests above are unit tests of one seam each. Two more say the whole
thing works.

**A restore round trip, on the jvm.** Unlock the keystore into a temp dir, write
an nsec through Phase 3's writer, list identities, activate the `NostrSecret`,
hand the `NavigationViewModel` a repository holding the `GENESIS_AT` placeholder,
and assert it lands on `UnqueuedProfileSynchronization`; then hand it the same
account with a kind 0 indexed and assert `ProfileLoaded`. This is the path a user
takes, end to end, with the network faked at the repository.

**Nothing Lightning ran.** For the nsec activation above, assert
`platformStartupLogic` was not called. Wrap the `expect` in a counting fake for
the test, or — cheaper — assert `Identity.business == null` and that
`SovereignWalletStartupViewModel.state` stayed `Init`. An nsec identity that
quietly started a node would be a bug that nothing in the UI would reveal.

And the one that already exists: `./gradlew :composeApp:m3Audit` over the two
new screens. The string budget is zero title-case literals and the spacing budget
is zero `.dp` in spacing positions; a sign-in form is exactly where a stray `16.dp`
and a "Sign In" arrive.

---

## Phase 8 — rollout

**Library first.** Phase 2 is a commit on `kngako/lightning-kmp-app`; the app
builds against the submodule checkout through the composite build, so it reaches
the app as a submodule pointer bump in the same commit as Phase 3. Tag it, since
JitPack consumers resolve by tag, and the README there says so.

**Phase 1 ships alone,** before any of the rest is visible. It touches about
twenty files and changes nothing a user can see, and that is the point: if the release
after it behaves differently, the cause is in one diff.

**Phases 3 and 4 ship together.** A build that can list an nsec identity but not
create one is fine; a build that can create one and not list it strands the user
at "Initializing…", which is where the comment at the top of
`SovereignWalletStartupScreen` says this app has stranded people before.

**Phase 5 can ship before 4** — it fixes restore for recovery phrases too — but
should not ship *after* 4 by more than a release, for the reason given there.

**Old builds and the new file.** A build without Phase 3 does not read
`nostr-keys.dat` and does not know the file exists. A user who imports an nsec
and then downgrades sees the identity vanish from the selector and nothing else
breaks; the file is still there for the next upgrade. `seed.dat` is untouched
throughout, so no build old or new misreads a wallet.

## Estimate

| phase | work | days | blocked by |
|---|---|---|---|
| 1 | identity in front of the wallet (app) | 1–2 | — |
| 2 | nostr key store (library) | 1 | — |
| 3 | listing and starting identities; the other entrance; hoist the seed writer | 2 | 1, 2 |
| 4 | the sign-in screen, both inputs, dedupe | 1–2 | 3 |
| 5 | indexer relays, one hop, the not-found exit | 1 | — |
| 6 | recovery for an nsec identity; forget this key | 1 | 3 |
| 7 | round-trip tests | 0.5 | 4, 5 |
| 8 | rollout | — | all |

**Roughly one and a half to two focused weeks**, with Phases 1 and 2 in parallel
if two people are on it. Phase 3 is where the estimate is least reliable: it is
the first time both halves meet, and the startup screen has a history.

Phases 1–7 are now implemented, in one sitting and in order. What each turned out
to require, as against what was predicted here, is in the table at the top and in
the commit messages on this branch. The two surprises were not in any phase's
description: the android source set's file-name collision in Phase 2, and the
process-global preferences cache that only a test with a fixed key could have
found.

## Out of scope

- **A wallet for an nsec identity.** Not possible from the nsec, for the reason
  in [The constraint](#the-constraint). Possible as a *second* secret — generate
  a seed, attach it to the identity — but then one profile has two things to back
  up and every recovery screen in this plan has to say which. Its own plan.
  *Watching* the chain without a wallet of its own is built: an nsec profile
  sees its groups' balances on an Electrum client of its own —
  [standalone-electrum.md](./standalone-electrum.md).
- **Starting the node lazily for mnemonic identities.** Recommended, separable,
  and made small by Phase 1. See [The one decision](#the-one-decision-to-make-first).
- **`npub` sign-in (read only).** The skeleton parses it, and `signInToProfile`
  would accept it. But every write path in the app assumes a key: the notary
  would have nothing to sign with and the state machine would park on
  `UnsignedProfile` the first time anything was queued. A read-only mode is a
  product, not a branch — and [npub-sign-in.md](./npub-sign-in.md) is the plan
  for that product, starting from what it is for.
- **NIP-49 `ncryptsec`.** A passphrase-encrypted nsec. quartz 1.14.0 ships
  `nip49PrivKeyEnc.Nip49.decrypt`, so it is one call and a passphrase field; it
  belongs in Phase 4's table once the plain nsec path is proven, and is a cheap
  follow-on.
- **NIP-46 remote signer, NIP-55 Android signer.** The landing caption promises
  one. Both need a signing *interface* — sign, NIP-44 encrypt, decrypt — in place
  of a raw key, and `deriveHostSecretKey` (ChillDKG) needs the raw key bytes and
  cannot be done through a remote signer at all. That is a different design with
  a real constraint in it, and `NostrNotaryRepository`'s `isExternalSignerLogin`
  stub is where it would start.
- **Tightening secrets in memory.** Both `availableWallets` today and
  `StoredIdentity` here hold decrypted secrets for the view model's life.
  Decrypt-at-activation applies to both kinds and should be done once, for both.
- **General sign out.** Phase 6 removes an *nsec*. Removing a seed is a wallet
  operation with funds behind it, and `ActiveProfileScreen`'s button stays
  pending.

## Appendix — what was considered and rejected

### Extending `EncryptedSeed` with a version-4 payload

Put both kinds in `seed.dat`: a new `V2` variant (version byte 4 — 1 is single,
2 is retired, 3 is multiple) whose JSON is
`{ "<id>": { "type": "mnemonic", "words": [...] } | { "type": "nsec", "key": "…" } }`,
and make `UserWallet` a sealed type.

One file, one migration, one keystore alias — and rejected. The library is a
Phoenix fork whose history is visibly a stream of upstream ports; `seed.dat` is
Phoenix's format, and a fork of it is a merge conflict on every port from now on.
An older build reading the new file throws `unhandled V2 seed version=4` and
reports the *wallet* unreadable — a user who imported an nsec and downgraded
would lose access to their funds' seed until they upgraded, which is a worse
failure than the sibling file's "the nsec is not listed". And `BusinessManager.startNewBusiness(words)`
on three platforms, `RecoveryPhraseViewModel`, `WalletsSelector` and every
`UserWallet.words` site would have to learn a variant that has no words. A class
named `EncryptedSeed` should hold seeds.

### Encoding the nsec as a mnemonic

Thirty-two bytes is two hundred and fifty-six bits of entropy, which BIP39
encodes as twenty-four words. So an nsec *can* be written as a valid mnemonic.
It is not the same thing as a seed whose NIP-06 leaf is that nsec — the words
would be run through `MnemonicCode.toSeed` and `LocalKeyManager` and derive a
completely different nostr key — and a store that cannot tell "words that are a
seed" from "words that are a key in disguise" is a store with a silent
misinterpretation in it. Rejected without a second look.

### Deriving a seed from the nsec

Not possible. Stated in [The constraint](#the-constraint); listed here so nobody
spends an afternoon confirming it.

### The node as the key's carrier

Leave `activeWalletInUI` as it is and build a fake `PhoenixBusiness`, or a fake
`LocalKeyManager`, for an nsec identity so the ten sites need not change.
`LocalKeyManager` is a `data class` with a seed; there is no interface to fake,
and a `PhoenixBusiness` lazily constructs eleven managers that expect a node.
Every "fake" would be a real object in a state its authors never intended, and
the ten sites are ten lines.

### A new keystore alias for the key file

Cleaner in principle — a compromise of one key would not be a compromise of
both. But `KeystoreHelper.getKeyForName` maps both existing aliases to **the same
key**, so the separation is already notional on Android, and a third alias means
touching that switch. Reuse, and note that separating the seed's and the key's
keystore entries is a hardening item for the wallet as much as for this.

### Plaintext in DataStore

`UserPrefs` is a DataStore file per wallet id and it would be one line to put the
nsec in it. It is unencrypted on disk. The seed has never been stored that way
here, and the key that signs as the user should not be the first.
