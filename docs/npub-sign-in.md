# Signing in with an npub

How a user looks at this app as a profile they hold no secret for, what such an
identity can and cannot do here, and why the answer to "what is it for" decides
every screen it touches.

Read this after [nsec-sign-in.md](./nsec-sign-in.md). It inherits the `Identity`
type, the two key stores, the sign-in screen and the not-found exit from there, and
most of what follows is that plan's out-of-scope note taken at its word — "a
read-only mode is a product, not a branch" — and asked what the product would be.

**Built**, phases 1–7, one commit each, in the order given; Phase 8 is the rollout
and is process rather than code. The phases are kept as written because they are
the reasoning, and the code reads better against the argument it came from than
against a summary of itself. Where the implementation chose differently the table
below says so:

| what the plan said | what it turned out to be |
|---|---|
| the startup screen's third branch in Phase 3 | in Phase 2: `StoredIdentity` is sealed, and the compiler asked for the branch the moment `NostrPublic` existed. The right code either way, one phase early |
| `KeyRecoveryScreen`'s `NostrPublic` arm "unreachable, and says so" | for the option, `Unit` with the comment; for the sentence above it, grouped with `NostrSecret` — so that if the screen is ever reached it does not promise coins |
| "the old reader kept as `LegacyNostrKeysFile` for exactly one caller" | `LegacyNostrKeysFile` is the read half of what was `NostrKeyManager`, and `EncryptedNostrKeys` stays beside it, because the migration's test has to *produce* a v1 file and nothing else can |
| the migration, unspecified in its outcomes | `MigrationResult` — `Migrated(count)`, `NotNeeded`, `Failed(failure)` — and `listIdentities` maps a failure onto the same `ListWalletState.Error` an unreadable credentials file gets, per kind |
| the wire shape as "a `@Serializable sealed class`" | two types: `NostrCredential`, whose `Secret` carries a `PrivateKey` and cannot be built with a key that is not one, and a private serializable mirror inside the encrypted file whose `Secret` carries hex |
| the DAO guard as "belt and braces" | load-bearing between Phase 3 and Phase 5, when the room list's inbox sync still ran for a read-only identity; and verified both ways — the test fails with the guard removed |
| `ProvideSigningCapability` from the active identity | a null identity — startup, landing, sign-in — answers *true*: it is nobody, not read-only, and those screens have nothing to hide |
| `ForgetIdentity` "dispatches the first step on its kind" | no dispatch: since the credentials file the first step is one call for both credential kinds, and `forgetNostrCredential` itself answers `NotACredential` for a mnemonic |
| sign out and the not-found exit, shape unspecified | one `SignOutViewModel` owning the confirmation and the in-flight state, one `SignOutOfReadOnlyDialog` differing only in its title, and a `SignOutDependencies` bundle so each screen takes one nullable parameter rather than four lambdas that throw |
| `signInToProfile` "made idempotent by pubkey" | done, and tested by signing in twice and counting one; the sign-in view model's three writers now go through one shared write-and-map |
| the round trip "with the network faked at the repository" | with the *database* real: an in-memory Room under the app's own DAOs and a real `NotaryViewModel`, because "nothing was signed" is about what is not in the tables — plus a contrast case the plan did not ask for, the same harness as a signing identity producing a key package, so the assertions are known to bite |
| `use_a_different_key` as a new string | it existed: the sign-in screen's own "use a different key" button. One string, two screens, one meaning |
| one entry per pubkey *across both files*, with `merge` listing the seeds and adding the credentials | **superseded** by [multiple-profiles.md](./multiple-profiles.md), Phase 1: the credentials file is the list of profiles and a seed is attached to the entry its key derives, so a seed's key *is* in the file; `merge` lists the credentials and attaches the seeds. The upgrade table gains a row — a phrase over a bare secret attaches — and `NotACredential` becomes `WalletAttached` |

One thing found on the way that is in no phase: a compose test that looks for a
floating action button's label by text has to search the unmerged tree, because
`ExtendedFloatingActionButton` merges its label into the button's semantics — and
on the merged tree an `assertDoesNotExist` for a hidden control is vacuously true.
`ReadOnlyEntrancesJvmTest` uses the unmerged tree for every lookup for that reason.
(More precisely, the `text`/`icon` overload *clears* the label's semantics, so the
merged tree never had it; the shared `ExtendedFab` that replaced it keeps the label
in the merged tree, and the test's habit is now only a habit.)

The library commit is on `claude/nostr-credentials` in the submodule, at `84cc44c`,
one commit ahead of the library's `master` and bumped into the app by the Phase 2
commit; it has to be pushed with this branch and tagged for JitPack consumers
before any of this leaves the machine. The nsec plan's `59c11ed` reached `master`
through the library's PR #1 but was never tagged; Phase 8 below says why the two go
out on one tag.

## The constraint

A nostr public key can be addressed and can verify. It cannot do anything else,
and in this app "anything else" is most of the app:

| what needs the secret | where |
|---|---|
| signing every queued event — the kind 0, the follow list, key packages, group posts, proposals | `NotaryViewModel.observeUnsignedNostrEvents` |
| opening a NIP-17 gift wrap addressed to the user | [GiftWrapMessage.kt:126](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/GiftWrapMessage.kt) — NIP-44 with the recipient's key |
| joining a Marmot group — a key package has to be signed and published before any welcome can be addressed to it, and the welcome itself arrives as a gift wrap | `MarmotRepository.publishMarmotKeyPackageBundle`, `MarmotInboundManager` |
| the ChillDKG host key, `sha256(tag ‖ nostrPrivateKey)` | [ChillDkgRitualManager.kt:117](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/ChillDkgRitualManager.kt) |

What a public key *gets* is the events it has published — the kind 0, the kind 3,
the relay lists — and the profiles of the people it follows, because indexing a
kind 3 plants a placeholder for each and queues its sync
([NostrDao.kt:1128](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt)).
In a client with a public feed, that is the client: read-only in Damus or Amethyst
means "browse as this person".

**This app has no public feed.** The home tab is a list of chat rooms, and every
room is MLS or a gift wrap, both encrypted to the key the user did not paste. The
curated lists, proposals, ceremonies and translations are reached only through a
room (`ChatRoomDetailScreen`). Search is over profiles, and trending notes is a
string that says "coming soon". So a read-only identity here sees its own profile
card, the people it follows — as search results, by name, once the sync has
fetched them; nothing renders the list itself — any profile's detail, and share
profile. That is the honest inventory, and it is thin — thin
enough that the first thing to decide is not how to build it but what it is
[for](#the-one-decision-to-make-first).

## What is already built

More than the nsec plan's out-of-scope note suggests, because that plan built most
of it on the way.

| piece | where | state |
|---|---|---|
| the parser recognises `npub1…` and `nostr:npub1…` — and refuses it, as `PublicKeyOnly` | [CredentialParser.kt:94](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/CredentialParser.kt) | one branch from accepting it |
| `signInToProfile(pubkey)` plants the `GENESIS_AT` placeholder from a pubkey alone | [DatabaseNostrRepository.kt:258](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt) | built; needs one property, see [Phase 4](#a-sign-in-that-can-be-repeated) |
| the state machine from there — `UnqueuedProfileSynchronization → UnsyncedProfile → UnindexedProfile → ProfileLoaded` — reads `identity.nostrPublicKey` and never a key | [NavigationViewModel.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NavigationViewModel.kt) | built |
| the sign-in sync, the indexer relays, the one hop, and the not-found exit | `SignInSync`, `UnsyncedProfileViewModel` | built; the exit needs a third action, see [Phase 6](#phase-6--leaving) |
| `RelaysSocketManager` follows the identity's relay list by pubkey | [RelaysSocketManager.kt:70](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/network/relays/RelaysSocketManager.kt) | works unchanged |
| the notary returns before doing anything when there is no key | [NotaryViewModel.kt:72](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/NotaryViewModel.kt) | works unchanged — and so the key package bundle it would publish is never attempted |
| quartz's `KeyPair(pubKey = …)` with no private key, commented in its own source as "this is a read-only account" | `com.vitorpamplona.quartz.nip01Core.crypto.KeyPair` | usable; it is why the DAO carries `privKey!!` at all |
| `Identity.business` is nullable; a read-only identity is `NostrSecret` minus the secret | [Identity.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/Identity.kt) | one field from it |
| the forget flow — key out, prefs deleted, metadata hidden, account rows gone, re-list, selector | `NostrSecretViewModel.forgetKey`, `IdentityWriter.forgetNostrKey` | built for an nsec; the sequence transfers whole |
| `WalletsSelector` shows the npub for every kind | [WalletsSelector.kt:161](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/wallet/WalletsSelector.kt) | needs a "read only" label |
| `nostr-keys.dat`, with a version byte and a doc that says "a new shape would be a new version" | `EncryptedNostrKeys`, `NostrKeyManager`, `AtomicFileWrite` | the envelope, the atomic write and the manager shape all carry over; only the entries change |
| a string, `this_will_give_you_read_only_access_to_the` | `strings.xml` | present, unused, and says too little |

## What actually blocks it

Five things, in dependency order.

**The identity's key is not optional.** `Identity.nostrPrivateKey` is a
`PrivateKey` and `nostrPublicKey` is computed from it
([Identity.kt:50](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/Identity.kt),
62). Every reader of the identity compiles against that.

**There is nowhere to keep a public key.** Both stores hold secrets. `nostr-keys.dat`
is a map from public key to private key, and `EncryptedNostrKeys` refuses a file
whose entry does not derive the key it is filed under — the right rule for that
shape, which is why the answer is a new shape rather than a value smuggled into
this one ([Phase 2](#phase-2--one-credentials-file)).

**The pumps are gated on the private key.** `SynchronizationViewModel` runs its
four collectors inside `identity?.nostrPrivateKey?.let { … }`
([SynchronizationViewModel.kt:194](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SynchronizationViewModel.kt)).
A read-only identity's sign-in sync would be queued and never dispatched, and the
machine would park on `UnsyncedProfile` for good — the nsec plan's Phase 5 failure
again, from a different cause.

**The DAO opens what is addressed to it, or rolls back.** `storeNostrEvent` is
`@Transaction` and indexes inside it; a gift wrap addressed to the active key that
cannot be unsealed throws
([NostrDao.kt:448–459](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt)),
and the throw takes the event with it. Worse, and not obvious: `decryptGiftWrapSeal`
builds `KeyPair(privKey = keyPair.privKey)`
([GiftWrapMessage.kt:126](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/GiftWrapMessage.kt)),
and in quartz a `KeyPair` given neither key *generates a fresh random one*. A
read-only pair forwarded there would try to open the wrap with a key nobody has,
fail, and roll back — silently correct-looking in the logs.

**Every write entrance is unconditional.** Some twenty-five view models write
something the notary has to sign or a key has to seal. Most are behind a chat
room and unreachable without one; but the "New chat" button,
follow, send message, key package management, key recovery and the not-found
screen's "set one up" are each one tap from the three tabs. A pasted npub that
lands on a screen offering "New chat" is not a preview, it is a broken app.

## The one decision to make first

What is a read-only identity *for?* Three answers were considered.

**Browsing.** Needs a public feed. This app does not have one, and a mode whose
whole content is "coming soon" should not ship ahead of it.

**Auditing a group from outside.** The curated lists a group publishes are public
events (kinds 31889, 31888, 31890), and a member's device is not the only place they
could be read. But nothing today reaches them except through a room, and building
a public list browser is a feature with its own screens — the feed problem in a
smaller shape.

**A preview.** Look at the app as your own profile — the name it has for you, the
people it knows you follow, what it found on the relays — before you paste a secret
into it. And, having looked, paste it.

**This plan takes the third.** It is what the thin inventory is actually good for,
and it settles four questions that would otherwise each be an argument of their own:

- The Messages tab shows an empty state that says why it is empty and offers the
  one thing that fills it: signing in with the nsec.
- Pasting the nsec of a key already here read-only is an **upgrade in place**, not
  `AlreadyOnThisDevice`. The device does not have that key; refusing it would be
  false.
- Sign out is real for this kind, and only this kind: there is nothing on the
  device to lose. It is the first sign out in the app, and the general case stays
  pending for the reason the nsec plan gave.
- Not-found offers *try again* or *a different key*, never *set one up* — a profile
  cannot be set up for a key that cannot sign its kind 0.

Everything below is downstream of that one word, preview, and if it is ever
revisited — a public feed lands, say — the places to change are the four above.

---

## Phase 1 — a key the identity may not have

**App only. No behaviour change.** A type change that ships alone, so its diff is
boring.

### The type

```kotlin
enum class IdentityKind {
    Mnemonic,
    NostrSecret,
    /** A bare nostr public key. Nothing can be signed, opened or derived from it. */
    NostrPublic,
}

data class Identity(
    val id: WalletId,
    val kind: IdentityKind,
    val nostrPublicKey: HexKey,
    /** Null only for [IdentityKind.NostrPublic]. */
    val nostrPrivateKey: PrivateKey?,
    val userPrefs: UserPrefs,
    val internalPrefs: InternalPrefs,
    val business: PhoenixBusiness?,
) {
    /** What the notary, the pumps and every write entrance ask. */
    val canSign: Boolean get() = nostrPrivateKey != null

    init {
        require((nostrPrivateKey == null) == (kind == IdentityKind.NostrPublic)) { "kind and key disagree" }
        require(nostrPrivateKey == null || nostrPrivateKey.nostrPublicKeyHex() == nostrPublicKey) { "key does not derive its public key" }
    }

    override fun toString(): String = "Identity(id=$id, kind=$kind, key=<redacted>)"

    companion object {
        fun signing(id: WalletId, kind: IdentityKind, privateKey: PrivateKey, userPrefs: UserPrefs, internalPrefs: InternalPrefs, business: PhoenixBusiness?): Identity
        fun readOnly(id: WalletId, nostrPublicKey: HexKey, userPrefs: UserPrefs, internalPrefs: InternalPrefs): Identity
    }
}
```

`nostrPublicKey` moves from a derived property to a constructor field, because for
one kind there is nothing to derive it from; the two `require`s in `init` keep the
field and the key from disagreeing, which a data class would otherwise happily
allow. The factories exist so that the two places that build a signing identity
today — `SovereignWalletViewModel.setActiveWallet` and the nsec branch of
`SovereignWalletStartupScreen` — keep deriving the pubkey in one place, and so that
nobody constructs a read-only identity with a key by accident.

`id` for a read-only identity is `toWalletId()` of the x-only key — the **same id**
its nsec would have. That is deliberate and it is what makes the upgrade in
[Phase 2](#the-upgrade-rule) free: preferences and metadata are keyed by id, and a
read-only identity that becomes a signing one keeps both.

### The readers

Every reader of `nostrPrivateKey` already reaches it through `?.` on a nullable
identity — `activeIdentityStateFlow.value?.nostrPrivateKey` in the DKG, chat-room
and subgroup view models, `.map { it?.nostrPrivateKey }` in the notary,
`identity?.nostrPrivateKey?.let` in the pumps. Making the field nullable changes
the type of none of those expressions, and **the compiler will be silent**. That
is worth saying plainly, because it is the opposite of Phase 1 of the nsec plan,
where twenty-five parameter types forced every site to be looked at: here nothing
forces anything, and the sites that need a decision are found by reading, in
[Phase 5](#phase-5--what-a-read-only-screen-shows), not by compiling.

One site does change. `KeyRecoveryScreen` branches on `activeIdentity?.kind` with
`NostrSecret` in one arm and `else` in the other
([KeyRecoveryScreen.kt:177](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/KeyRecoveryScreen.kt)),
and the `else` meant *mnemonic*. A read-only identity would fall into it and be
offered a recovery phrase it does not have. Make both `when`s in that file
exhaustive; the `NostrPublic` arm is unreachable after Phase 5 hides the row, and
an unreachable arm that says so is better than an `else` that will mean something
different next time a kind is added.

### Tests

`NavigationIdentityRoutingJvmTest` has a `nostrSecretIdentity()` fixture and the
case that a nodeless identity is routed by its account rather than to startup. It
gains a `readOnlyIdentity()` fixture and the same case: with the placeholder
account, `UnqueuedProfileSynchronization`; with a kind 0 indexed, `ProfileLoaded`.
And the two `require`s each get a one-line negative test, because they are the
whole reason the field is a constructor parameter.

---

## Phase 2 — one credentials file

**Library, then app. The library half needs nothing above it; the app half needs
Phase 1.** The only phase that touches the library, and the one place this plan
changes one of the nsec plan's stores rather than adding to them.

### The file that was written expecting this

`EncryptedNostrKeys`'s own doc says it: "this file has one shape, and a new shape
would be a new version." A read-only credential is the new shape. Rather than a
second file beside `nostr-keys.dat` holding public keys in the clear — the design
this plan first had, and rejected for the reason in the
[appendix](#a-plaintext-sibling-file-for-public-keys) — the file becomes what its
name should have been: **`nostr-credentials.dat`**, one entry per nostr public key,
each entry saying what the device holds for it.

```json
{
  "<x-only pubkey hex>": { "type": "secret", "privateKey": "<private key hex>" },
  "<x-only pubkey hex>": { "type": "public" }
}
```

Same envelope — version byte, sixteen-byte iv, ciphertext of UTF-8 JSON under
`KeyStoreNames.KEY_NO_AUTH` — same atomic write through `AtomicFileWrite`, same
manager shape. The JSON is a `Map<String, NostrCredential>` with `NostrCredential`
a `@Serializable sealed class` and `type` its class discriminator — kotlinx's
standard sealed polymorphism, `Json { classDiscriminator = "type" }`, which the
library does not use elsewhere (its cloud payloads pick a variant by a version
field) and which is chosen here because the two variants have different fields and
the map then decodes in one call with no hand-written dispatch.

```kotlin
package fr.acinq.phoenix.security

@Serializable
sealed class NostrCredential {
    @Serializable @SerialName("secret") data class Secret(val privateKey: PrivateKey) : NostrCredential()
    @Serializable @SerialName("public") data object Public : NostrCredential()
}

class EncryptedNostrCredentials(val iv: ByteArray, val ciphertext: ByteArray) {
    fun decryptAndGetCredentials(): Map<String, NostrCredential>
    fun serialize(): ByteArray
    companion object {
        const val VERSION: Byte = 1
        fun deserialize(bytes: ByteArray): EncryptedNostrCredentials
        fun encrypt(credentials: Map<String, NostrCredential>): EncryptedNostrCredentials
    }
}
```

The read keeps the check that makes the file trustworthy and adds its counterpart:
a `secret` entry must derive the public key it is filed under, as today, and a
`public` entry's map key must be sixty-four hex characters naming a point on the
curve. Either failure is `SerializationError`, because either can only be
corruption.

Encrypting a public key protects nothing and costs nothing. What it buys is one
read path with one failure classification, and — a small thing, but real — the
list of profiles a user has looked at is a browsing record, and it is at rest
under the same key as everything else about them.

### Why the file is renamed rather than versioned in place

A version byte of 2 in `nostr-keys.dat` would be the natural move and it is the
wrong one. An older build's reader throws on an unknown version, `NostrKeyManager`
classifies that as `SerializationError`, and `listIdentities` **returns** on that
result before it publishes anything
([SovereignWalletViewModel.kt:218](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt)):
the old build would list no identities at all, seed wallets included. That is the
`seed.dat` failure the nsec plan's appendix rejected a version-4 payload for. A
file the old build does not look for cannot do that to it.

So: `nostr-credentials.dat`, version 1 of a new file, and the old reader kept as
`LegacyNostrKeysFile` for exactly one caller.

### Migration

`NostrCredentialManager.migrateFromNostrKeys(phoenixGlobal)`: if
`nostr-credentials.dat` is absent and `nostr-keys.dat` exists, read the old file,
convert every entry to `Secret`, write the new file through the verified atomic
write, and **delete the old one**. Called once from `listIdentities`, which runs
from `SovereignWalletViewModel.init` before anything else touches either file, so
a read stays a read and the migration is a named step with a test of its own.

Delete, rather than leave a frozen copy for an older build to find. A copy goes
stale in the one direction that matters: a key the user asks the new build to
*forget* would survive in a file only the old build reads, and come back on a
downgrade. Losing sight of every nsec identity on a downgrade until the next
upgrade is the lesser failure, and [Phase 8](#phase-8--rollout) says so out loud.

As far as the repository can show, the migration will run for nobody: the library
commit that introduced `nostr-keys.dat` carries no tag and the app has none. It
exists because the repository cannot prove a negative, and because it is thirty
lines.

### Listing

```kotlin
sealed interface StoredIdentity {
    …
    data class NostrPublic(
        override val id: WalletId,
        override val nostrPublicKey: HexKey,
    ) : StoredIdentity {
        override val kind: IdentityKind get() = IdentityKind.NostrPublic
    }
}
```

`StoredIdentity.merge(wallets, credentials)` keeps its two inputs; a `Secret` is a
`NostrSecret` and a `Public` a `NostrPublic`. One entry per pubkey is the file's
own invariant, so there is no precedence between credentials to decide. There is
one between a seed and a credential, and it exists for the one upgrade that has to
span two files (below): a `Public` whose pubkey a seed derives is dropped, with a
log line, and the next write repairs the file.

### The writers

```kotlin
object IdentityWriter {
    …
    suspend fun writeNostrPublicKey(log, phoenixGlobal, globalPrefs, publicKey: HexKey, isTorEnabled, customElectrumServer): WriteNostrCredentialResult
    suspend fun forgetNostrCredential(log, phoenixGlobal, id: WalletId, publicKey: HexKey): ForgetNostrCredentialResult
}
```

`writeNostrKey` stays and writes a `Secret`; `writeNostrPublicKey` writes a
`Public`; both refuse a duplicate by public key against the seeds *and* the
credentials, and both `prepareIdentity` — the Tor and Electrum preferences mean
even less to a read-only identity than to an nsec one, and are saved for the
reason the nsec plan gave. `forgetNostrKey` becomes `forgetNostrCredential` and
removes an entry of either kind; its `NotABareKey` becomes `NotACredential`, which
still means a mnemonic.

### The upgrade rule

`writeNostrKey`'s duplicate check learns one distinction:

| the pubkey is already here as | pasting its nsec | pasting its npub |
|---|---|---|
| a wallet (seed) | `AlreadyExists` | `AlreadyExists` |
| a `secret` credential | `AlreadyExists` | `AlreadyExists` |
| a `public` credential | **upgrade**: the entry becomes `secret`, in one write; `Written(id)` with the id unchanged | `AlreadyExists` |

One write, because it is one file. There is no window in which the device holds
both or neither, and nothing to reconcile afterwards — which is the whole reason
this phase is a library change and not a second file.

The one upgrade that still spans two files is a *recovery phrase* pasted over a
public credential: the seed goes to `seed.dat`, and that file cannot hold anything
else. `writeMnemonic` removes the `public` entry **first**, then writes the seed.
A crash between the two loses the read-only identity — recoverable by pasting the
npub again — rather than leaving one pubkey listed twice under two ids, which is
what the reverse order would do and what the merge rule above is there to catch
if it somehow happens anyway. The wallet's id is `hash160(nodeId)` and its
preferences are fresh, as a new wallet's are today.

### Tests

In the library, after the two that exist: `EncryptedNostrCredentialsTest` in
`commonTest`, the layout as literal bytes with one entry of each kind, because the
format is a compatibility contract from the moment it exists; and, in `jvmTest`, a
round trip through the key store, plus the migration — a v1 `nostr-keys.dat`
written by `LegacyNostrKeysFile`'s test fixture, read back as `Secret` entries in
`nostr-credentials.dat`, the old file gone.

In the app: `StoredIdentityJvmTest` gains a `Public` credential listed as
`NostrPublic`, and a `Public` whose pubkey a seed derives dropped.
`IdentityWriterJvmTest` gains: a public key is written once and refused the second
time; its nsec is then accepted, under the same id, and the entry is now `secret`;
the npub of an nsec already here is refused; forgetting a credential of either kind
leaves the others and takes its preferences.

---

## Phase 3 — listing, starting, and reading without a key

**App. Needs Phase 2.**

### Listing and starting

`SovereignWalletViewModel.listIdentities` runs the migration, then reads
`nostr-credentials.dat` where it read `nostr-keys.dat`, with the same five results
handled the same way; the change is the type of the map it hands to `merge`
([SovereignWalletViewModel.kt:242](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt)).

`SovereignWalletStartupScreen` gains its third branch, beside the nsec one
([SovereignWalletStartupScreen.kt:185](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SovereignWalletStartupScreen.kt)):

```kotlin
is StoredIdentity.NostrPublic -> sovereignWalletViewModel.setActiveIdentity(
    Identity.readOnly(
        id = identity.id,
        nostrPublicKey = identity.nostrPublicKey,
        userPrefs = dataStoreManager.loadUserPrefsForWallet(identity.id),
        internalPrefs = dataStoreManager.loadInternalPrefsForWallet(identity.id),
    )
)
```

No node, no `platformStartupLogic`, active the moment it is read — exactly the nsec
branch. The screen-lock gate wraps all three, as it was built to.

`WalletsSelector` shows the npub for every kind already; a read-only row adds the
words *read only* under it, in `labelSmall`, so the user can tell which of two
identities with the same avatar will let them send a message before they tap.

### The pumps

`SynchronizationViewModel`'s collector becomes

```kotlin
activeIdentityStateFlow.collectLatest { identity ->
    identity?.let {
        val keyPair = identity.toKeyPair()
        …
        supervisorScope {
            launch(Dispatchers.IO) { observePendingSyncNostrEventRequests(keyPair) }
            launch(Dispatchers.IO) { observePendingNegentropySynchronizeRequests(keyPair) }
            if (identity.canSign) {
                launch(Dispatchers.IO) { observePendingBroadcastNostrEventRequests(keyPair) }
                launch(Dispatchers.IO) { liveSubscriptionManager.observe(keyPair) }
            }
        }
    }
}
```

**A read-only identity runs the two pumps that read and neither of the two that
write.** Stated as a rule, because Phase 5 leans on it: *a read-only identity never
sends anything to a relay*, not a signature and not a copy. The broadcast pump
would find nothing — nothing is ever signed, and the one control that queues an
already-signed event is hidden — and the
live subscriptions are the gift-wrap inbox and the group-membership follow, both of
which fetch things this identity cannot open, and the catch-up negentropy for the
same. Not running them is not an optimisation; it is the identity not asking for
what it cannot use. The sync pump and the negentropy pump are what the sign-in
sync, the one hop and *try again* are queued on, and they are all a preview needs.

`Identity.toKeyPair()` is the one place a quartz `KeyPair` is built from an
identity:

```kotlin
fun Identity.toKeyPair(): KeyPair = KeyPair(
    privKey = nostrPrivateKey?.value?.toByteArray(),
    pubKey = nostrPublicKey.hexToByteArray(),
)
```

With a private key, quartz recomputes the public key from it and the second
argument is a cross-check. Without one, it is the read-only constructor. What it
must never be is `KeyPair(privKey = null)` with nothing else — that is quartz's
"make me a new key", and it is what `decryptGiftWrapSeal` does today
([GiftWrapMessage.kt:126](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/GiftWrapMessage.kt)).
The helper exists so that the trap has one place to be avoided; leave a comment on
it that says what the trap is.

### The one DAO guard

After the `isAddressedTo` check in `indexNostrEvent`
([NostrDao.kt:439](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt)):

```kotlin
if (activeKeyPair.privKey == null) {
    // Addressed to us, and we cannot open it. Keep the event and the wrap we just
    // stored, as the branch above does for wraps addressed to someone else; the
    // key that opens this one may be signed in later.
    logger.d("GiftWrap ${nostrEvent.id} is addressed to us, but this identity holds no key")
    return@let
}
```

The two `privKey!!` sites further down (lines 898 and 1376) are downstream of a
successful unseal and a Marmot room this device is in; a read-only identity reaches
neither, and they stay as they are. With the live subscriptions off, and once
Phase 5 leaves the room list uncomposed, a gift wrap addressed to a read-only
identity arrives only if something else asks for kind 1059 — nothing else does —
so this guard is belt and braces. It stays because the alternative, when something
does ask, is a rolled-back transaction that logs as a decryption failure and is
not one; and between this phase and Phase 5 it is not belt and braces at all, it
is what keeps the room list's inbox sync from rolling back every wrap it fetches.

### Tests

`NostrDaoJvmTest` gains one case: a gift wrap addressed to a read-only `KeyPair`
is stored, its `GiftWrapMessage` row is stored, no seal is stored, and nothing
throws — `NostrNip17DaoJvmTest` has the fixtures for building the wrap. The
listing itself is covered where the nsec plan ended up covering it: `merge`, in
`StoredIdentityJvmTest` (Phase 2), and the round trip in Phase 7, which lists
through the view model against a real directory and a real key store. The nsec plan asked for a
`SovereignWalletViewModel` listing test of its own and the build did not write
one; this plan does not pretend to.

---

## Phase 4 — the sign-in screen

**App. Needs Phase 3.** The field accepts a third thing.

### Shape

| pasted | recognised as | validation |
|---|---|---|
| words | recovery phrase | unchanged |
| `nsec1…`, `nostr:nsec1…` | nostr secret | unchanged |
| 64 hex characters | **nostr secret, still** | see below |
| `npub1…`, `nostr:npub1…` | nostr public key, read only | bech32 decodes with hrp `npub` to 32 bytes that are an x coordinate on the curve — `XonlyPublicKey(bytes).publicKey.isValid()`, the counterpart of the `isValid()` a secret is checked with |
| `ncryptsec1…` | rejected, with the reason | unchanged |

**Hex stays a secret.** A private key and an x-only public key are both thirty-two
bytes, and sixty-four hex characters cannot say which it is. The parser has always
read hex as a secret and shown the derived npub on the confirm step so a wrong
paste is visible; a user who pastes a public key as hex will see an npub they do
not recognise and go back. Guessing — "it is not a valid secret, so try it as a
public key" — would never fire when it mattered: a public key's x coordinate is,
with overwhelming probability, also a valid scalar, so the parser would accept it
as a secret, derive an unrelated npub, and never reach the guess. An npub has to
arrive as an npub.

`CredentialProblem.PublicKeyOnly` goes, and with it the string
`an_npub_is_a_public_key_curare_needs_the`. An npub that does not decode, or
decodes to a point that is not on the curve, is `InvalidKey`, which already says
"that nostr key is not valid".

### The confirm step

`SignInCredential` gains

```kotlin
data class NostrPublicKey(override val nostrPublicKey: HexKey) : SignInCredential
```

and `Confirm` a third arm, `Icons.Default.Visibility` beside a sentence that says
what the user is about to get and not get. `this_will_give_you_read_only_access_to_the`
says "This will give you read only access to the profile", which is true and not
enough: it does not say that messages stay closed, and it does not say what to do
about it. Replace it:

> Recognised as a public key. You will see this profile and the people it
> follows; messages stay closed and nothing can be sent. Paste the nsec later to
> open it.

Sentence case, one string, read on the confirm step and nowhere else. The button
still says *Sign in*.

### Committing

```kotlin
is SignInCredential.NostrPublicKey -> when (val result = writeNostrPublicKey(credential.nostrPublicKey)) {
    is Written -> Outcome.SignedIn(result.id)
    is AlreadyExists -> Outcome.Failed(CredentialProblem.AlreadyOnThisDevice)
    is CannotLoadKeys -> Outcome.Failed(CredentialProblem.CouldNotWrite)
}
```

then `signInToProfile`, then the same tail as the other two: re-list, select, go to
startup. Startup finds a `StoredIdentity.NostrPublic`, activates it, and the machine
runs from the placeholder exactly as it does for an nsec — the sync pump is running
because Phase 3 made it run.

### A sign-in that can be repeated

`signInToProfile` inserts a new kind-0 `UnsignedNostrEvent` every time it is called
([DatabaseNostrRepository.kt:258](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt)).
Until now nothing called it twice for one pubkey: the writers refused the second
sign-in before it got there. The upgrade is the first path that signs in a pubkey
whose account already exists, and a second placeholder would be "two kind-0 rows
for one pubkey, two accounts disagreeing about which is this one" — the failure
`setUpProfileForExistingKey`'s own comment describes.

Make it idempotent by pubkey: if a kind-0 `UnsignedNostrEvent` exists for the key,
do nothing. That is the right property regardless of the upgrade, and the existing
test `both credentials of one key plant the same account` becomes `…plant one
account, however many times they sign in`.

### Landing

The caption under *Sign in* names what the screen accepts, and the label and
placeholder on the field do too. Three strings change to say "a recovery phrase,
an nsec, or an npub to look around" — the last clause because a user who does not
know the word *read-only* should still be told the difference before they paste.

### Tests

`CredentialParserJvmTest`: an npub, a `nostr:`-prefixed npub and the upper-cased
form all name the same public key; an npub that does not decode is an invalid key;
sixty-four hex characters are still a secret. `SignInToProfileViewModelJvmTest`: a
public key is written, then its account is planted, then the id comes back; the
npub of a key already here is refused and plants nothing; the nsec of a key here
read-only is written under the same id and plants nothing new.

---

## Phase 5 — what a read-only screen shows

**App. Compiles on Phase 1; visible after Phase 3.** The half the nsec plan called
a product.

### One question, asked in one way

Screens do not receive the identity. `HomeScreen`, `ActiveProfileScreen` and the
detail widgets take an `activeUserPublicKey`, and `MetadataEventDetail` — where
*follow* and *send message* live — is several composables below anything that
could be handed more. Threading `canSign` down would be the snackbar host's
problem again: the same parameter in twenty-six lists and forgotten in the
twenty-seventh.

```kotlin
// ui/composable/widgets/SigningCapability.kt
val LocalCanSign: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

@Composable
fun ProvideSigningCapability(activeIdentity: StateFlow<Identity?>, content: @Composable () -> Unit)
```

Provided once, in `DedunNavHost` around the `NavHost`, from
`sovereignWalletViewModel.activeIdentity`. The default is `true` rather than an
error, and that is the one way this differs from `LocalSnackbarHostState`: the
provider sits above every screen and cannot be forgotten per screen, so the only
things composed outside it are previews and the existing compose tests, and those
should render as they always have.

It is a capability, not a kind. A screen asks "can this identity sign?", not "is
this an npub?", because the answer is what it needs and because a remote signer —
which can sign and holds no local key — would otherwise be a fourth value in every
`when` (see [Out of scope](#out-of-scope)).

### The inventory

Every write entrance reachable from the three tabs without a chat room, and what
a read-only identity gets instead. A row that says *hidden* is hidden, not
disabled: a disabled "New chat" invites the question "why", and the answer is on
the empty state beside it.

| screen | control | it writes | read only |
|---|---|---|---|
| `HomeScreen` | *New chat* (both layouts), the sheet, the npub dialog | key packages, welcomes, gift wraps | hidden; the tab shows the [empty state below](#the-messages-tab) |
| `HomeScreen` | `ChatRoomListViewModel.initiate` | queues the inbox sync and the MLS negentropy | not composed, so not queued |
| `MetadataEventDetail` | *follow*, *unfollow*, *follow back* ([:215](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/detail/MetadataEventDetail.kt)) | a kind 3 | hidden; the "follows you" state still shows |
| `MetadataEventDetail` | *send message* ([:303](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/detail/MetadataEventDetail.kt)) | a gift wrap | hidden |
| `MetadataEventDetail` | *edit profile* | a kind 0 | hidden (it is `ImplementationPendingRoute` today, and stays so for the other kinds) |
| `ActiveProfileScreen` | *key package management* ([:233](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/ActiveProfileScreen.kt)) | key packages | hidden |
| `ActiveProfileScreen` | *key recovery* ([:286](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/ActiveProfileScreen.kt)) | nothing, but there is nothing to recover | hidden |
| `ActiveProfileScreen` | *sign out* ([:341](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/ActiveProfileScreen.kt)) | — | **real**, see [Phase 6](#phase-6--leaving) |
| `ShareProfileScreen` | *re-broadcast* ([:191](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/ShareProfileScreen.kt)) | nothing is signed, but a copy of the kind 0 is queued for the finder relays | hidden; a preview puts nothing on a relay, not even a copy, and the broadcast pump is not running to carry it |
| `UnsyncedProfileScreen` | *set one up* ([:217](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/UnsyncedProfileScreen.kt)) | the six-event bootstrap | hidden; replaced, see Phase 6 |
| `SocialPreconditionScreen` | *invite a friend*, *view invites* | pending, and writes when they exist | hidden; only *skip for now* |
| `WriteNewNoteScreen` and the rest | — | — | unreachable: every route to them starts in a room or behind a control above |

Two things are *not* on the list because they are already handled. The notary's
key package bundle is behind its key check, so a read-only identity never publishes
one. And the DKG, chat-room and subgroup view models each answer a missing key with
an error state — they are unreachable, and if they were reached they would say so
rather than crash.

### The Messages tab

Where the room list would be, for a read-only identity:

```kotlin
EmptyState(
    message = stringResource(Res.string.messages_need_the_secret_key_this_profile),
    icon = Icons.Default.Lock,
    action = {
        TextButton(onClick = { onNavigateToRoute(SignInRoute) }) {
            Text(stringResource(Res.string.sign_in_with_the_nsec))
        }
    },
)
```

> Messages need the secret key. This profile is read only: you can see it and the
> people it follows, but nothing here can be opened or sent.

`EmptyState`'s message is required for exactly this reason — an absence has to say
which absence it is — and its `action` slot is where the upgrade lives. The
`SignInRoute` it opens is the same screen as landing's; the nsec pasted there hits
the upgrade rule, and the tail takes the user through startup to a Messages tab
with rooms in it.

One column at every width. The two-pane layout is a list beside a detail, and
there is no list; an empty state that is the only thing on the screen is the one
case `readableContent()`'s centring is for.

The tab stays. Hiding it would leave a navigation bar with two items — the
conformance work promoted search and profile to peers precisely so the bar would
not be "strictly worse than the app bar it replaced", and two items is halfway
back to that — and the bar is where the upgrade is found.

### Tests

`ReadOnlyEntrancesJvmTest`, with `runDesktopComposeUiTest` as `ChatPaneLayoutJvmTest`
does: `HomeScreen` in its loaded state under `LocalCanSign provides false` has no
node with the text *New chat* and one with *Sign in with the nsec*; under `true`,
the reverse. `ActiveProfileScreen` under `false` has no *Key recovery* and has
*Sign out*. The inventory above is the list of assertions; a row without one is a
row that will regress.

And `./gradlew :composeApp:m3Audit` — an empty state with an action and a new
`when` arm is where a `16.dp` and a "Read Only" arrive.

---

## Phase 6 — leaving

**App. Needs Phases 2 and 5.** Two exits, one sequence.

### Sign out, for the kind that can

`NostrSecretViewModel.forgetKey` does five things in an order that matters — key
out of the file, preferences deleted, metadata hidden, account rows gone, then the
caller re-lists and clears the active identity — and the order is documented on the
function: a failure partway leaves the key on disk rather than an identity the
selector lists but nothing can open. The sequence is the same for a read-only
identity, and since Phase 2 the first step is the same call — `forgetNostrCredential`
removes an entry of either kind. Lift the sequence into a `ForgetIdentity` helper
that takes the identity, returns `NotACredential` for a mnemonic one, and is
called by `NostrSecretViewModel` and the two exits below alike.

`ActiveProfileScreen`'s *sign out* then does, for a read-only identity only, what
its colour has been promising: a confirmation dialog naming the npub —

> Sign out of this profile? This device holds no key for it, so there is nothing
> to lose. The profile stays on the relays, and you can sign in again any time.

— and `ForgetIdentity`, then the nav host's existing tail
(`listIdentities { resetToSelector() }`, which shows the selector or, if this was
the last identity, Landing). For the other two kinds the button keeps routing to
`ImplementationPendingRoute("Sign out")`, and the nsec plan's reason stands:
removing a seed is a wallet question.

### The not-found exit

`UnsyncedProfileScreen`'s not-found state offers *try again* and *set one up*. For
a read-only identity the second is hidden by Phase 5 — and then the state has
**no exit**: the profile tab is not reachable before `ProfileLoaded`, so a user
whose npub was not found on any relay could try again for ever. That is the nsec
plan's "parks on a spinner" with the spinner replaced by a button.

A third action, shown only where the second is not: *use a different key*, which
is `ForgetIdentity` behind the same confirmation and the same tail, landing on the
selector or Landing. The screen learns the two callbacks the way `NostrSecretScreen`
did — passed from the nav host — and reads `LocalCanSign` to decide which of the
two it shows.

### Tests

`ForgetIdentity` gets the `IdentityWriterJvmTest` treatment: a read-only identity
is forgotten and its list entry, preferences and account rows are gone; the other
entries remain. `UnsyncedProfileViewModel`'s existing decision test is unchanged —
not-found is still not-found — and the screen test asserts which action each kind
gets.

---

## Phase 7 — the tests that actually prove it

Two, beside `NsecRestoreRoundTripJvmTest`, whose fixture they reuse.

**The preview, end to end.** Write an npub through Phase 2's writer, list
identities, activate the `NostrPublic` one, hand `NavigationViewModel` a repository
holding the placeholder: `UnqueuedProfileSynchronization`. The same account with a
kind 0 indexed: `ProfileLoaded`. Then the assertion the nsec round trip makes about
the node, made about the key: **nothing was signed**. The only `UnsignedNostrEvent`
for the pubkey is the placeholder, still at `GENESIS_AT`; no key package bundle
row; no broadcast request; `identity.business == null`; `identity.canSign` false.

**The upgrade.** From the state above, write the nsec of the same pubkey through
`writeNostrKey`: `Written` with the same id; the credentials file holds one entry
for the pubkey and it is `secret`; `signInToProfile` planted nothing new, and the
account routes to `ProfileLoaded` as before. Re-list: one identity, `NostrSecret`,
same id, same metadata.

---

## Phase 8 — rollout

**Library first, and on the tag the nsec work still owes.** Phase 2 is a commit
on the submodule, reaching the app as a pointer bump in the Phase 2 commit. The
library commit that introduced `nostr-keys.dat` is on the library's `master` — it
arrived through PR #1 — but is untagged, and the library has no tags at all; the
nsec plan says it has to be tagged before any of that work leaves the machine, and
Phase 2 goes out on the same tag, so that no JitPack consumer ever sees the v1 file
without the reader that migrates it.

**Phase 1 ships alone.** A type change with two `require`s and one exhaustive
`when`; if the release after it behaves differently, the cause is in one diff.

**Phases 3 through 5 ship together.** A build with the file but not the screen is
harmless; a build with the screen but not the file strands the user at
"Initializing…"; and a build with both but not Phase 5 is the thing this plan is
for — an identity that looks signed in and offers "New chat". Phase 6 can follow
by a release: without it a read-only identity has no sign out and no not-found
exit, which is a dead end but not a lie.

**Old builds and the new file.** A build before Phase 3 does not read
`nostr-credentials.dat` and does not know it exists. If it also predates the nsec
work it is unaffected in every way. If it is a build *with* `nostr-keys.dat` and
the migration has run on this device, that file is gone and the build lists no
nsec identities until the next upgrade — the failure Phase 2 chose over a
forgotten key coming back, and the one line of this rollout worth a release note.
`seed.dat` is untouched throughout.

## Estimate

| phase | work | days | blocked by |
|---|---|---|---|
| 1 | a key the identity may not have | 0.5–1 | — |
| 2 | the credentials file, migration, the writers, the upgrade rule (library + app) | 1.5 | 1 |
| 3 | listing, starting, the pumps, the DAO guard | 1 | 2 |
| 4 | the sign-in screen; an idempotent sign-in | 0.5–1 | 3 |
| 5 | the capability, the inventory, the Messages tab | 1 | 1 to compile, 3 to see |
| 6 | sign out, and the not-found exit | 0.5 | 2, 5 |
| 7 | the two round trips | 0.5 | 4, 5, 6 |
| 8 | rollout | — | all |

**Roughly one focused week.** Phase 5 is where the estimate is least reliable:
the inventory was made by reading, and the compiler will not say whether it is
complete. The compose tests are how it is checked, and a row found late is a row
added to both.

## Out of scope

- **A public feed.** The thing that would make read-only *browsing* rather than
  *previewing*. Its own plan, and when it lands the four decisions under
  [the one decision](#the-one-decision-to-make-first) are the places to revisit.
- **NIP-46 remote signer, NIP-55 Android signer.** This plan builds half of what
  they need — an identity whose secret is not on `Identity` — and none of the
  other half, a signing interface the notary calls instead of a `NostrSignerSync`.
  `LocalCanSign` is a Boolean on purpose: a remote signer answers it *true* —
  which is why `canSign` is a property of the identity and not `nostrPrivateKey != null`
  repeated at each site — and the question it adds is "can it derive?": the
  ChillDKG host key needs the raw bytes, and no signer protocol gives them. A
  second capability, not a third value of this one.
- **NIP-05 sign-in.** `alice@example.com` resolves to a pubkey with one HTTPS
  request, and the app already accepts a NIP-05 for starting a message. It is a
  natural fourth row in Phase 4's table, and it is a network round trip on a form
  field, which is why `EditGroupCuratedSchemaViewModel.pubkeyOrNull` declined it.
  A cheap follow-on once the npub path is proven.
- **NIP-49 `ncryptsec`.** Unchanged from the nsec plan.
- **Setting up a profile for a public key.** Impossible: the kind 0 has to be
  signed. Said here so that nobody looks for the branch Phase 5 hid.
- **Reading a group's curated lists from outside it.** Public events, but every
  path to them goes through a room today. The feed problem in a smaller shape.
- **Tightening secrets in memory.** Unchanged, and this plan adds no secret to
  tighten.

## Appendix — what was considered and rejected

### A plaintext sibling file for public keys

This plan's first design: `nostr-public-keys.json` beside the two `.dat` files,
unencrypted because a public key is public, written through the same atomic
helper, and app-side — no library change, no tag. It was rejected in review, and
rightly. The upgrade became two writes across two files with a crash window
between them and a "secret wins" rule in `merge` to repair the window; forget
became two paths; and the advantage that paid for all of that was worth less than
it looked, because the library commit that introduced `nostr-keys.dat` is itself
untagged, so a credentials format rides the tag that work already owes. One file,
one entry per pubkey, one write.

### A sentinel entry in the version-1 format

An empty-string value, or a zero key, under the pubkey, in `nostr-keys.dat` as it
is. Rejected because the file's contract is the reason it is trustworthy:
`EncryptedNostrKeys` refuses an entry whose key does not derive its pubkey, and a
sentinel is exactly such an entry. A typed credential is what a sentinel is trying
to be, and the file's doc had already reserved a version for it.

### Bumping the version in place

Version byte 2 in `nostr-keys.dat`, with the typed entries. Rejected in
[Phase 2](#why-the-file-is-renamed-rather-than-versioned-in-place): an older
build's `listIdentities` returns on `SerializationError` before it publishes
anything, so the old build would list no identities at all, seed wallets included.
A new file name is invisible to a build that does not know it.

### A fake private key

Give a read-only identity a random `PrivateKey` so that nothing has to become
nullable. The notary would sign as a key nobody follows, the DAO would try to
unseal wraps with it, the key package bundle would be published under it, and
every one of those would look, in the logs, like success. The nsec plan rejected
the fake `LocalKeyManager` for the same reason: a real object in a state its
authors never intended. Nullable is honest.

### Gating on the kind at each site

`if (identity.kind == IdentityKind.NostrPublic) hide()`. Works today, and is the
wrong question: the site wants to know whether it can sign. When a remote signer
arrives — the nsec plan already names it — every such `if` is a bug, and the
compiler will point at none of them.

### Hiding the Messages tab

A read-only identity lands on its profile and the bar has two items. Rejected: the
bar is the navigation, M3 gives a navigation bar three to five destinations and the
conformance work made an IA decision to reach three, and the empty state is where
the upgrade is offered. An identity that cannot find the way to become a signing
one is a preview of nothing.

### Running all four pumps regardless

Let the broadcast pump idle and the live subscriptions fetch what they fetch; the
DAO guard keeps the wraps. Rejected for what it costs the user for nothing:
`GiftWrapMessage` rows that will never be opened, placeholder profiles for their
senders, profile syncs for each, and a socket held open for an inbox that cannot
be read. The pumps that run are the ones with something to do.

### A Room table for the list

`ReadOnlyIdentity(publicKey)`, listed by the repository. Rejected because the
startup listing is built from the key stores by a view model that has no
repository, and because the nsec plan's listing test exists to catch two stores
disagreeing about what an id is; a store in a different layer, read at a different
time, is a second opinion where the credentials file is meant to be the only one.
