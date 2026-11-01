# Bringing up the JVM target

What it would actually take to build Curare for desktop, phased, with the
blocking work separated from the mechanical work.

The headline is not what you would expect. The four-deep native chain — secp256k1
→ bitcoin-kmp → lightning-kmp → lightning-kmp-app — is **already building for
JVM**, and has been all along. The thing standing in the way is an empty source
set in our own phoenix fork.

One scoping note before anything else: **none of this is needed to test the
DAOs.** Host-speed Room tests run under `androidUnitTest` today, given one extra
dependency. The JVM target is a product decision — a desktop Curare — not a
testing prerequisite. See [Room DAO tests](#appendix-room-dao-tests-do-not-need-this)
at the end.

## What is already done for you

**The native chain is already JVM.** This is the expensive part, and it is
finished. The comment at [composeApp/build.gradle.kts:50](../composeApp/build.gradle.kts)
records why: `lightning-kmp-core` publishes no android variant, so our android
target resolves it to the **jvm** one, which in turn pulls
`secp256k1-kmp-jni-jvm` — desktop `.so`/`.dylib`/`.dll` files. That is why the
build has to name `secp256k1-kmp-jni-android` by hand.

Read that the other way round and it is good news: every JVM artifact in the
chain is already compiled from source, by the composite build, on every android
build we do. Turning on a JVM target adds no cinterop, no C compilation, and no
new native constraints.

**The third-party dependencies all have JVM variants.** Verified against the
repositories the build actually resolves from:

| dependency | JVM artifact | status |
|---|---|---|
| quartz 1.14.0 | `com.vitorpamplona.quartz:quartz-jvm` | on Maven Central |
| room3 3.0.1 | `androidx.room3:room3-runtime-jvm` | on Google Maven |
| sqlite 2.7.0 | `androidx.sqlite:sqlite-bundled-jvm` | on Google Maven |
| sqldelight 2.3.2 | `app.cash.sqldelight:jdbc-driver`, `:sqlite-driver` | on Maven Central |

**`commonMain` is clean.** No `android.*` and no `java.*` imports anywhere in it.
The three NFC files in `androidMain` are not referenced from common code either,
so there is nothing to stub out and no android-only API to route around. The
shared tree will compile for JVM as-is.

## What actually blocks it

`lightning-kmp-app/library` declares 25 `expect` symbols in `commonMain` and
implements them across 35 files in `androidMain`. Its `jvmMain` contains exactly
one file:

```kotlin
// lightning-kmp-app/library/src/jvmMain/kotlin/fibiprops.jvm.kt
package io.github.kotlin.fibonacci

actual val firstElement: Int = 2
actual val secondElement: Int = 3
```

That is the Kotlin multiplatform library template's Fibonacci boilerplate, left
over from whenever the module was scaffolded. It implements two of the 25
expects, and both of them are the template's own.

So **23 JVM actuals are missing**, one level down from us, and
`lightning-kmp-app/library/build.gradle.kts:18` has `jvm()` commented out because
of it. Curare cannot declare `jvm()` — commented out in turn at
[composeApp/build.gradle.kts:46](../composeApp/build.gradle.kts) — until phoenix
does.

Everything below is ordered by that dependency.

---

## Phase 0 — build configuration

**~half a day. No blockers.**

Nothing here needs a decision; it is the groundwork the later phases assume.

1. **Delete the stale `jvmMain` tree.** Six files under
   `composeApp/src/jvmMain/kotlin/ac/cord/auxiliary/` survive from the old Aux
   project. They are an orphan source set that nothing currently compiles, which
   is why they have gone unnoticed — they use the wrong package, import
   `androidx.room` (Room 2), and reference a long-gone `AuxDatabase`. The moment
   `jvm()` is declared they become compile errors.

   Keep them open in a scratch buffer while doing Phase 4: five of them are a
   usable skeleton for the actuals we still need.

2. **Add the missing catalog entries** to `gradle/libs.versions.toml`:

   ```toml
   androidx-sqlite-bundled-jvm = { module = "androidx.sqlite:sqlite-bundled-jvm", version.ref = "sqlite" }
   sqldelight-sqlite-driver    = { module = "app.cash.sqldelight:sqlite-driver",  version.ref = "sqldelight" }
   ```

   Only these two, and only because neither can be reached any other way.
   `sqlite-bundled-jvm` has to be named explicitly because variant-aware
   resolution hands the *android* artifact to anything running on the host —
   that is the whole trap described in the appendix. `sqlite-driver` is the jvm
   counterpart to the `android-driver` and `native-driver` entries already here.

   No `room3-runtime-jvm` entry: once `jvm()` exists, `commonMain`'s existing
   `androidx-room3-runtime` resolves to the `-jvm` variant on its own. And the
   SQLDelight drivers Phase 2 needs belong in **lightning-kmp-app's own
   catalog**, not this one — that is a separate gradle build with a separate
   version catalog, and putting them here would not make them visible there.

3. **Do not wire `kspJvm` yet.** It cannot be done at this point, and the reason
   is already written down a few lines above it in the build file:

   > These configurations only exist when the ios targets are declared, which
   > the kotlin block above does only on a mac.

   The same rule governs `kspJvm` — `dependencies { add("kspJvm", ...) }` throws
   `UnknownConfigurationException` until a `jvm()` target creates that
   configuration. So uncommenting [composeApp/build.gradle.kts:194](../composeApp/build.gradle.kts)
   belongs in **Phase 4**, in the same edit that turns the target on, not here.

4. **Leave `jvm()` commented out**, in both builds. It goes on at the start of
   Phase 4, once there is something for it to resolve against. Turning it on
   earlier just means living with a broken build through Phases 1–3.

**Verification:** `./gradlew :composeApp:compileDebugKotlinAndroid` still passes.
This phase changes nothing observable; the point is that it changes nothing
observable — a deleted orphan source set and two unreferenced catalog entries
cannot alter a build.

**If you are working in a git worktree, no gradle task will run at all** until
the submodule is checked out there. Worktrees do not get submodules
automatically, so `lightning-kmp-app/` is empty and the composite build fails
during configuration:

```
Project with path ':library' not found in build ':lightning-kmp-app'
```

`git submodule update --init --recursive` fixes it, but note that a linked
worktree shares `.git/modules/` with the main checkout, so both trees end up
sharing one submodule git dir. That is fine while both want the same commit —
check with `git submodule status` in each — and worth being careful about when
they do not.

Then the fresh clones need `local.properties`, which is gitignored and therefore
absent, at **five** levels — the curare root and each of the four nested builds
down to secp256k1-kmp. Without it configuration fails at
`:lightning-kmp-app:lightning-kmp:bitcoin-kmp:secp256k1-kmp:jni:android` with
"SDK location not found":

```bash
for d in . lightning-kmp-app \
         lightning-kmp-app/experimental/lightning-kmp \
         lightning-kmp-app/experimental/lightning-kmp/experimental/bitcoin-kmp \
         lightning-kmp-app/experimental/lightning-kmp/experimental/bitcoin-kmp/experimental/secp256k1-kmp; do
  echo "sdk.dir=$HOME/Android/Sdk" > "$d/local.properties"
done
```

---

## Phase 1 — phoenix: the mechanical actuals

**~1–2 days. Blocked by nothing. Do this first.**

**Start by turning on `jvm()` in the library** —
`lightning-kmp-app/library/build.gradle.kts:18`, not curare's, which still waits
for Phase 4. Without it `library/src/jvmMain/` is an orphan source set that
nothing compiles, and every actual in Phases 1–3 would be written blind. With it,
`./gradlew :library:compileKotlinJvm` prints the remaining expects by name, and
that list is a better worklist than any grep — it shrinks by exactly what you
implement and cannot drift from the truth.

Fifteen of the 23 are mechanical. None requires a decision — each is either a
direct copy of the android implementation or a few lines of JVM file handling.

**`DbHooks.jvm.kt` — six functions, and this one is free.** The android
implementation is 15 lines and every function is an empty body; the hooks only do
work on Apple platforms, where they drive CloudKit sync. Copy the file, change
the suffix.

```kotlin
actual fun didSaveWalletPayment(id: UUID, database: PaymentsDatabase) {}
actual fun didDeleteWalletPayment(id: UUID, database: PaymentsDatabase) {}
actual fun didUpdateWalletPaymentMetadata(id: UUID, database: PaymentsDatabase) {}
actual fun didSaveContact(contactId: UUID, database: PaymentsDatabase) {}
actual fun didDeleteContact(contactId: UUID, database: PaymentsDatabase) {}
actual fun makeCloudKitDb(appDb: SqliteAppDb, paymentsDb: SqlitePaymentsDb): CloudKitInterface? = null
```

**`PlatformContext.jvm.kt` — the class plus four directory paths.** On android
these come off a `Context`; on desktop there is no context object, so
`PlatformContext` becomes either an empty class or one holding an explicit root
directory. Prefer the latter — it makes tests and multi-profile desktop installs
possible later, and it costs nothing now.

The four paths (`getApplicationFilesDirectoryPath`,
`getDatabaseFilesDirectoryPath`, `getApplicationCacheDirectoryPath`,
`getTemporaryDirectoryPath`) should resolve to a per-OS application data
directory, not `java.io.tmpdir`. The old Aux code used tmpdir and left a `TODO`
about it; do not inherit that.

**The remaining singles:** `platformElectrumRegtestConf` (*not* a copy — android
uses `10.0.2.2`, the emulator's alias for the host loopback, and a jvm process is
already on the host), `AppVersion`, `phoenixLogWriters` (kermit's `CommonWriter`;
android routes into slf4j because android tooling reads that, and the jvm has no
equivalent convention), and `computePreferencePath`.

**Leave the Fibonacci template alone.** `fibiprops.jvm.kt` looks like stray
scaffolding, but it is the jvm half of a pair: `generateFibi` is exercised by
template tests in `commonTest`, `androidHostTest`, `iosTest`, `jvmTest` and
`linuxX64Test`, and `JvmFibiTest` asserts a value that depends on exactly the two
properties that file defines. It already satisfies two of the 25 expects, which
is why 23 are missing rather than 25. Deleting the template is a reasonable
cleanup of a lightning wallet library, but it is five test files plus four
`fibiprops.*` actuals, and it is not this work.

**Verification:** `./gradlew :library:compileKotlinJvm` from inside
`lightning-kmp-app/`. It still fails at the end of this phase — that is expected,
and the failure is the point. It should report **exactly eight** remaining
expects, and they should be exactly the contents of Phases 2 and 3:

```
DbFactory.kt          createChannelsDbDriver, createPaymentsDbDriver, createAppDbDriver
NetworkMonitor.kt     NetworkMonitor
KeyStoreFunctions.kt  keyStoreDecryption, keyStoreEncryption
TechnicalExtensions.kt gracefulSingleSeedDecryption, gracefulMultiSeedDecryption
```

Anything else in that list means something in this phase is wrong. Also re-run
`./gradlew :composeApp:compileDebugKotlinAndroid` from the curare root: adding a
jvm target to the library must not disturb how the android target resolves it.

---

## Phase 2 — phoenix: drivers and connectivity

**~2–3 days. Blocked by nothing, but do it after Phase 1.**

Three of the 23 are database drivers and one is the network monitor. These are
real implementations, but they are bounded — the shape is known and the failure
modes are ordinary.

**`DbFactory.jvm.kt` — `createChannelsDbDriver`, `createPaymentsDbDriver`,
`createAppDbDriver`.** Use SQLDelight's `sqlite-driver`. Structurally, follow the
**ios** actual rather than the android one: an explicit directory plus a file
name, because the jvm has no `Context` to hand a bare name to.

Schema handling does *not* need hand-rolling, contrary to what this plan first
said. SQLDelight 2.x ships a factory function that shadows the constructor:

```kotlin
JdbcSqliteDriver(url, properties, schema, migrateEmptySchema = false, vararg callbacks)
```

It creates the schema on an empty file, migrates an existing one, and maintains
`PRAGMA user_version` itself. The same-named *constructor* — `JdbcSqliteDriver(url,
properties)` — does none of that, and reaching for it by accident is the easy
mistake here. `AfterVersion10`/`AfterVersion11` go in as the trailing callbacks,
exactly as ios passes them to `schema.migrate`.

**Foreign keys are the part worth budgeting for.** They are off by default in
SQLite, and the pragma is *per connection* — `JdbcSqliteDriver` opens one per
thread, so issuing it once against the driver is not enough. Pass it as a
connection property instead:

```kotlin
Properties().apply { setProperty("foreign_keys", "true") }
```

xerial reads pragma-named properties back through `SQLiteConfig(Properties)` and
applies them as each connection opens, so this needs no compile-time dependency on
`org.xerial:sqlite-jdbc` — which is just as well, since `sqlite-driver` brings it
in at *runtime* scope only. Android gets the same effect from
`setForeignKeyConstraintsEnabled` in its driver callback and ios from
`DatabaseConfiguration.Extended(foreignKeyConstraints = true)`. All three platforms
state it separately; none inherits it from the schema.

One incidental discrepancy to be aware of: the app database is named
`appdb.sqlite` on android and `app.sqlite` on ios.

`createPaymentsDbDriver` also takes an `onError: (String) -> Unit` — make sure
corruption and migration failures actually reach it rather than throwing past it,
because on android that callback is what surfaces the problem to the user.

**`NetworkMonitor.jvm.kt`.** The android implementation is 90 lines built on
`ConnectivityManager` and its `NetworkCallback` — genuine push notification of
connectivity changes. The JVM has no equivalent. The options are a polling
reachability check, or treating the connection as always-available and letting
the lightning stack's own reconnect logic handle reality.

Start with polling on a slow interval. It is worse than the android behaviour and
that is acceptable — the alternative is pretending the network never changes,
which produces confusing UI on a laptop that gets closed and reopened.

**Verification: this is the first phase that can actually be run, and it should
be.** Everything before it is checked by the compiler alone. Two properties of the
drivers are not, and both fail silently in production if wrong — an uncreated
schema looks like a missing table at first query, and foreign keys being off means
cascading deletes quietly do not happen. `library/src/jvmTest/` already exists;
`DbFactoryJvmTest` covers schema creation for all three databases, the foreign-key
pragma on each, and that reopening an existing file migrates-or-noops rather than
re-creating.

Running any jvm test needs the module to compile, which means the two
`KeyStoreFunctions` actuals must exist before Phase 3 has decided anything. Give
them bodies that **throw**, with a message naming this document. A loud failure is
the right placeholder: the alternative is something that appears to work while
storing a seed weakly, which is the one outcome worth ruling out.

**And `commonTest` has an expect of its own**, which is easy to miss because the
23 counted at the top of this document are `commonMain`'s. Declaring `jvm()` also
creates `jvmTest`, which inherits `commonTest`, so `connect` in
`ElectrumServersTest.kt` needs a jvm actual before any jvm test compiles. Copy the
`androidHostTest` one — despite the name it contains no android API, only ktor,
`javax.net.ssl` and lightning-kmp's `JvmTcpSocket`.

Do **not** satisfy it with an empty body the way ios does. The class is `@Ignore`d
on every platform, so an empty actual compiles and looks harmless, but it turns
`connect_to_mainnet_servers` into an assertion that passes without connecting to
anything the moment somebody removes the `@Ignore`. The tidier long-term fix is a
shared source set that `androidHostTest` and `jvmTest` both depend on, which is a
change to how the module is wired rather than to what it does.

---

## Phase 3 — phoenix: key storage

**A decision, not a port. Unbounded until the decision is made.**

Four of the 23, but only **two** of them are actually a decision.

`gracefulSingleSeedDecryption` and `gracefulMultiSeedDecryption` are not. They
are pure exception mapping into a `DecryptSeedResult`, and the exception they
branch on is `java.security.KeyStoreException` — which exists on the jvm, since
`KeyStore` is a plain JCA type. Both are a near-copy of the android actuals and
can be written before any of the below is settled. Do them with Phase 2 and leave
two errors outstanding rather than four.

The decision is `keyStoreEncryption` and `keyStoreDecryption`.

The android implementation delegates to `KeystoreHelper.kt` — 116 lines against
`AndroidKeyStore`, with `KeyGenParameterSpec`, and `setIsStrongBoxBacked(true)`
attempted first and fallen back from when the device has no secure element. The
key material never leaves hardware.

**Desktop JVM has no equivalent.** There is no portable, hardware-backed keystore
on the JVM. The realistic options:

| approach | protects against | cost |
|---|---|---|
| passphrase-derived KEK (Argon2id → AES-GCM) | disk theft, if the passphrase is strong | low; but prompts the user on every launch |
| OS keychain via JNA (Keychain / DPAPI / libsecret) | other users on the machine, at rest | three separate platform integrations, three failure modes |
| JCEKS/PKCS12 file with a fixed key | nothing meaningful | low, and misleading |

This is wallet seed material. The third option is not a stopgap, it is a
liability, and it interacts directly with the plaintext-key finding already open
against this codebase — do not let a desktop build quietly become the weakest
place the seed lives.

**Recommendation for sequencing:** implement the passphrase-derived KEK, mark the
desktop build clearly as unsuitable for real funds, and treat OS-keychain
integration as its own piece of work with its own review. That unblocks Phases 4
and 5 without pretending the security question is answered.

### Three things that only surface once you build it

**The expect signature has nowhere to put a passphrase.** `keyStoreEncryption(keyName,
plainText)` takes no context and no secret, because on android the OS holds the key
and no secret is needed. A passphrase-derived KEK is therefore *not* a drop-in: it
needs an out-of-band unlock, so the jvm actual grows a `JvmKeyStore.unlock(passphrase,
storeDir)` that the application calls before any seed is touched — the same way the
android actual grows a `KeystoreHelper` beside it. **This lands in Phase 5**, which
must unlock before the wallet starts, so budget for a passphrase prompt in the
desktop entry point rather than discovering it there.

**The iv must be exactly 16 bytes**, which rules out a conventional GCM nonce.
`EncryptedSeed.V2.serialize` in commonMain throws on anything else and `deserialize`
reads exactly 16. GCM permits it, and for randomly generated nonces 128 bits is
actually the better choice — the whole risk with a random nonce is a repeat under one
key, and 128 bits makes that vanishingly unlikely where 96 merely makes it unlikely.
A constraint inherited from android's CBC format happens to help.

**The jdk has no memory-hard KDF.** `SecretKeyFactory` offers PBKDF2 and nothing else,
so Argon2id means a new dependency (`org.bouncycastle:bcprov-jdk18on`). Worth it: if
the build is dev-only *because* it has no hardware backing, weakening the KDF as well
to save a dependency gets the trade backwards.

Two smaller notes. Wrap a per-key-name data key under the KEK rather than encrypting
the seed with the KEK directly — a passphrase change then rewraps a 32-byte key
instead of re-encrypting and re-serialising the seed. And throw
`java.security.KeyStoreException` when locked: that is what android raises when it
cannot serve a key, and the `graceful*` wrappers already map it to
`DecryptSeedResult.Failure.KeyStoreFailure`, so a caller that forgets to unlock gets a
handled failure rather than a crash.

**This phase is the only one in the plan with no honest day estimate**, because
the estimate is a function of which row of that table gets chosen and how much
review it attracts.

**Verification:** `JvmKeyStoreTest`. The properties worth pinning are the ones that
fail quietly — a 16-byte iv (or `EncryptedSeed` refuses to serialise), tamper
detection (the reason for GCM over android's unauthenticated CBC), key separation
between the two names, a per-install salt, and that neither seed nor passphrase
lands in the store file. Add the damaged-store refusal too: a store with key
material but no salt must not be given a fresh one, since that turns a file a
backup could rescue into one whose data keys are gone.

Two limits to write down rather than fix. The first `unlock` on a new store accepts
any passphrase, because there is nothing yet to check it against — a wrong one only
surfaces when a data key fails to unwrap. And zeroing the derived key is best
effort; the jvm may have copied it during a gc, and nothing in process can reach
those copies.

---

## Phase 4 — curare's own actuals

**~1–2 days. Blocked by Phases 1–3.**

Now turn on `jvm()` — [composeApp/build.gradle.kts:46](../composeApp/build.gradle.kts)
and `lightning-kmp-app/library/build.gradle.kts:18` — and, in the same edit,
uncomment `kspJvm` at [composeApp/build.gradle.kts:194](../composeApp/build.gradle.kts).
Those two go together: the KSP configuration does not exist until the target
does, which is why Phase 0 deliberately left it alone. Then let the compiler
drive.

### Before any of that: Room will not generate a DAO for a non-android target

**This is the real content of Phase 4, and it is not the actuals.** The first jvm
compile fails with 58 copies of:

```
Only suspend functions are allowed in DAOs declared in source sets targeting
non-Android platforms.
```

Room permits blocking query methods **only** on android. Every `@Dao` function that
is neither `suspend` nor `Flow`-returning has to change, and there were 58 of them
across 25 files in `database/dao/`. KSP reports them in alphabetical batches, so the
count shrinks in stages and looks endless; scan for them directly instead — an
abstract `fun` in a `@Dao` that has no `suspend` and no `Flow<...>` return.

The saving grace is that it stops there. All 15 call sites outside the DAO layer
were **already inside `suspend` functions** — the repositories were written that way
throughout — so the change is `suspend` on 58 declarations, plus exactly one private
helper (`DatabaseNostrRepository.matchNegentropicNostrEvents`), whose single caller
was already suspend. Zero call-site edits.

It is not free, though, and the cost lands on **android**. A blocking DAO method runs
on its caller's thread; a `suspend` one is dispatched to the query coroutine context,
which `getRoomDatabase` sets to `Dispatchers.IO`. That is the better behaviour — it is
what stops a query running on the main thread — but it is a real change to a shipping
platform, made for the benefit of a target that does not exist yet. Run the android
unit tests, not just the compile.

### Then the actuals

Curare declares 16 expects across 8 files. They split cleanly:

**Six platform basics.** `getPlatform` ([Platform.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/Platform.kt)),
`PlatformContext`, `AppVersion`, `themeColorScheme`
([Theme.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/theme/Theme.kt)),
and `PlatformDatabaseBuilder`'s two functions. The deleted Aux files from Phase 0
are a working skeleton for five of these — repackage to `com.machankura.dedun.compose`,
update Room 2 → Room 3 (`androidx.room` → `androidx.room3`), and point at
`DedunDatabase` instead of `AuxDatabase`.

`DedunDatabaseConstructor` needs no hand-written actual; Room's KSP generates it
once `kspJvm` is wired above.

For `PlatformDatabaseBuilder.getDatabaseBuilder`, use the real application data
directory from Phase 1 — not `java.io.tmpdir`, which is what the old Aux
implementation did and which silently loses the database on reboot on most
systems.

**The library's android- and ios-only classes are not all expects.** The 23 counted
at the top of this document are `expect` declarations, and the compiler lists those
for you. `BusinessManager` is not one — it exists as a plain object in `androidMain`
and again in `iosMain`, with no common declaration, so nothing flags its absence until
curare's own actual tries to import it.

The ios one turns out to contain no ios API whatever: no `platform.*`, no cinterop, no
`NSObject`. It ports to `jvmMain` on a package rename alone and compiles unchanged. It
is also the right one to start from — the android manager is built around an android
`Application` it holds, while the ios one constructs its own `PlatformContext`, which
is exactly what the jvm can do. `NavigationViewModel.ios.kt` is likewise portable on
one changed import.

**Nine lightning wrappers.** Four in
[Phoenix.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/extensions/Phoenix.kt)
(`platformStartupLogic`, `schedulePlatformLogic`, `getShowIntroFlow`,
`getGlobalPrefs`) and five declared in
[SovereignWalletViewModel.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SovereignWalletViewModel.kt)
(`updateBusinessActiveInUI`, `loadAndDecryptSeed`, `getAvailableWalletsMeta`, and
the two `saveAvailableWalletMeta` overloads, plus `platformWriteSeed`) whose
android actuals live in `NavigationViewModel.android.kt`.

These are thin — they mostly forward into the phoenix library. They are thin
*because* Phases 1–3 did the work, which is why they are last.

`schedulePlatformLogic` is the one to look at properly, and the answer is that it
should schedule **nothing** and say so. On android it starts two WorkManager jobs, one
of which is `ChannelsWatcher` — it wakes periodically to notice a channel force-closed
while the app was shut. A desktop application has no process once its window closes,
so there is nothing for a scheduler to wake, and running the watcher in-process would
be strictly worse than not running it: it would only ever fire while the app was
already open and watching anyway.

The exposure is real and belongs in the release notes, not just a comment: a desktop
wallet left closed past a force-close deadline does not notice. Covering it needs
something outside this process, which is a separate piece of work from this plan.

**Verification:** `./gradlew :composeApp:compileKotlinJvm`. This is the first
point in the plan where the JVM target has to actually resolve, so expect the
dependency-substitution surprises to land here rather than earlier.

---

## Phase 5 — desktop entry point and shakeout

**~1–2 days. Blocked by Phase 4.**

[composeApp/build.gradle.kts:221](../composeApp/build.gradle.kts) already names
`com.machankura.dedun.desktop.MainKt` as the desktop main class. **That file does not
exist.** Write it: a `application { Window { ... } }` entry point constructing
`PlatformContext` and handing it to the same root composable android uses.

The UI itself is Compose Multiplatform and should largely come up as-is. What to
expect anyway:

- **Window sizing.** The layouts have only ever been laid out at phone widths.
  Nothing will crash; plenty will look wrong.
- **Back handling.** Android's system back has no desktop counterpart.
- **NFC.** The three `androidMain` NFC files are correctly android-only and are
  not referenced from `commonMain` — but any UI that offers an NFC affordance
  needs to not offer it here.
- **`Dispatchers.IO`.** Used in `getRoomDatabase` and available on JVM, so no
  change; noted because it is not available on all KMP targets and is easy to
  trip over later.

`main` also has to **unlock the key store before anything else runs**, which is the
consequence of Phase 3 that lands here. Android and ios have no screen like this
because their OS keystores serve keys without asking; on the jvm the passphrase *is*
the protection, so the window opens onto a gate rather than onto the app.

**Verification:** `./gradlew :composeApp:run`, and then a jvm test that opens the
database. The run proves the window paints; it does not prove Room works, because the
gate stops before anything touches the database. `DedunDatabaseJvmTest` covers what
the compiler cannot see — that Room's KSP output for this target is usable, that the
**host** SQLite native loads (the android artifact ships only android-ABI `.so` files
and would fail here), that the schema is created, and that the queries forced from
blocking to `suspend` still read back what they wrote.

Note that a gradle `run` killed with SIGTERM reports `BUILD FAILED` with exit value
143. That is the signal, not the app.

---

## Estimate

| phase | work | days | blocked by |
|---|---|---|---|
| 0 | build configuration | 0.5 | — |
| 1 | phoenix: mechanical actuals (14) | 1–2 | — |
| 2 | phoenix: drivers + network (4) | 2–3 | — |
| 3 | phoenix: key storage (4) | **decision** | — |
| 4 | curare actuals (16) | 1–2 | 1, 2, 3 |
| 5 | desktop entry point + shakeout | 1–2 | 4 |

Phases 0–5 are now implemented. What each phase actually turned out to require, as
opposed to what was predicted here, is recorded in the git history on
`claude/jvm-target-actuals` (the fork) and this branch. The two largest surprises were
not in any phase's description: Room refuses to generate a DAO with blocking methods
for a non-android target (Phase 4), and a passphrase-derived key store needs an unlock
step the `expect` signature cannot express, which lands in Phase 5 rather than 3.

**Roughly one focused week to a launchable desktop build**, assuming Phase 3
takes the passphrase-derived KEK and the build is marked dev-only. Real desktop
key storage is separate work that should not be folded into this estimate, and
should land before anyone holds funds on a desktop Curare.

Phases 1, 2 and 3 are independent of each other and can go in parallel if more
than one person is on it. Phase 4 cannot start until all three are done, because
it is where the compiler finally checks the whole thing.

## Out of scope

- **`linuxX64()`** — commented out in the phoenix library at line 53. A native
  Linux target is a different problem from a JVM one and buys nothing here.
- **iOS on a Linux host** — still impossible, for the reasons already documented
  in both build files. The JVM target does not change that.
- **Publishing desktop distributables** — the `compose.desktop` block already
  declares Dmg/Msi/Deb formats, but signing, notarisation and update channels are
  untouched by this plan.

## Appendix: Room DAO tests do not need this

Worth stating plainly, because the two questions arrived together and the answer
to one is not the answer to the other.

Room's own [testing guidance](https://developer.android.com/training/data-storage/room/testing-db)
recommends host-machine tests over instrumented ones. We can have those today,
without a JVM target, because `room3-runtime-android:3.0.1` exposes the
no-`Context` builder overload:

```
inMemoryDatabaseBuilder(kotlin.jvm.functions.Function0<? extends T>)
```

and [DedunDatabaseConstructor.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/DedunDatabaseConstructor.kt)
already supplies what it needs. So `Room.inMemoryDatabaseBuilder<DedunDatabase>()`
compiles in `commonTest` and runs under `testDebugUnitTest`.

The one trap is native, and it is the same shape as the secp256k1 problem
documented in the build file — in the opposite direction:

| artifact | ships |
|---|---|
| `sqlite-bundled-android` | `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libsqliteJni.so` |
| `sqlite-bundled-jvm` | `natives/{linux_x64,linux_arm64,osx_*,windows_x64}/` |

A local unit test resolves the **android** variant, whose `.so` files the host JVM
cannot load, so `BundledSQLiteDriver()` fails at construction. Naming
`sqlite-bundled-jvm` on the `androidUnitTest` classpath fixes it.

Robolectric does not help and is not needed — it cannot load android `.so` on the
host either, and Room's guidance advises against it regardless.
