# quartz, mls-kmp and marmot-kmp: the update that is mostly a change of address

The app is on quartz `2.7.3`, marmot-kmp `2.2.1`, mls-kmp `1.3.0` and crypto-kmp `2.1.0`,
pinned together by [marmot-kmp.md](./marmot-kmp.md)'s D2. Every one of the four has had a
release since, and none of those releases is on Maven Central. From quartz `2.19.0`,
mls-kmp `1.3.1`, marmot-kmp `2.3.0` and crypto-kmp `2.1.1`, the libraries are published on
Nostr + Blossom only, and a build reaches them through a Gradle settings plugin,
[Magpie](https://github.com/vitorpamplona/magpie). This app's settings know only `google()`
and `mavenCentral()`.

So the headline is the opposite of the last update's. Last time the libraries changed shape
and the distribution stayed put. This time almost nothing the app calls has changed, and
the way the build gets them has:

- **marmot-kmp** — `git diff v2.2.1 v2.3.0 -- marmot/src` is empty, and so is the diff to
  `main`. 2.3.0 is a build release: new pins, JVM 21, Magpie.
- **crypto-kmp** — `git diff v2.1.0 v2.1.1 -- crypto/src` is empty. Only the publishing
  changed.
- **mls-kmp** — the only one with code changes, all in `MlsGroup.kt` and
  `MlsGroupPolicy.kt`. `MlsGroupState`, the codec, `RetainedEpochSecrets`,
  `OwnSenderRatchet` and the staging API are byte-for-byte unchanged, and `STATE_VERSION` is
  still 10.
- **quartz** — eighteen releases, and all 101 distinct quartz symbols the app imports still
  resolve at `2.20.1` in the same package. Nothing deprecated at 2.7.3 has been removed. The
  real removals (the in-tree secp256k1 curve code, the `drss` listen builders, the namecoin
  resolver internals) are in code the app never calls.

That is read from source, not compiled. Phase 2 is where the compiler gets its say.

Researched on 2026-11-20 against the code at `b7de119e`. **Not built.** Each phase records
what it turned out to be, the way [marmot-kmp.md](./marmot-kmp.md) did, once it is done.

| phase | what it is |
|---|---|
| 0 | the go/no-go on Magpie, and the baselines every later phase is measured against |
| 1 | the Gradle daemon pinned to Java 21, with no dependency change |
| 2 | the swap: Magpie and marmot-kmp 2.3.0's own pins, in one commit |
| 3 | what the swap means: mls-kmp 1.3.1's epoch-0 changes, and the wire |
| 4 | C1 measured again, and the authored fork probed again |
| 5 | past marmot's pins: quartz 2.20.1 and mls-kmp 1.4.0, one commit each |
| 6 | every target in the lock, then the lock frozen |
| 7 | the guards and the notes that still say 2.7.3 |
| 8 | the checks a fixture cannot make: real profiles, real peers, a mac |

## Where everything is

| library | coordinate | the app | newest on Central | newest released | marmot 2.3.0 pins | target |
|---|---|---|---|---|---|---|
| quartz | `com.vitorpamplona.quartz:quartz` | 2.7.3 | 2.15.1 | 2.20.1 (2026-11-19) | 2.20.0 | **2.20.1** |
| marmot-kmp | `com.vitorpamplona.marmot:marmot` | 2.2.1 | 2.2.1 | 2.3.0 (2026-11-19) | — | **2.3.0** |
| mls-kmp | `com.vitorpamplona.mls:mls` | 1.3.0 | 1.3.0 | 1.3.1; 1.4.0 is a tag, not yet released (Phase 0) | 1.3.1 | **1.4.0** once released; 1.3.1 until then |
| crypto-kmp | `com.vitorpamplona.crypto:crypto` | 2.1.0 | 2.1.0 | 2.1.1 (2026-11-18) | 2.1.1 | **2.1.1** |

The newest version of each group arrives with them as a transitive dependency:

| transitive | today | after | note |
|---|---|---|---|
| `com.vitorpamplona.negentropy:kmp-negentropy` | v1.2.0 | v1.2.3 | the `v` is part of the version string; not on Central |
| `com.vitorpamplona.schnorr256k1:schnorr256k1-kotlin` | — | 1.0.3 | new in quartz 2.17.0; replaces quartz's in-tree curve code; not on Central |
| `fr.acinq.secp256k1:secp256k1-kmp` | 0.24.0 | 0.24.0 | unchanged; still substituted by the `lightning-kmp-app` composite |
| `kotlin-stdlib` | 2.4.21 | 2.4.21 | the skew against the app's Kotlin 2.4.10 is the one it already has |
| `kotlinx-serialization` | 1.12.0 | 1.12.0 | the app declares 1.11.0 and Gradle already resolves 1.12.0 |

And one plugin: `com.vitorpamplona.magpie`, **0.2.3**, from the Gradle Plugin Portal.

Three of the cells need a caveat:

- **mls-kmp 1.4.0** is a tag on GitHub. Whether it is on the release relay yet is the first
  thing Phase 0 checks. Its own build still has a Maven Central publish configured (the
  "Nostr + Blossom only" commit was reverted in `d7d0ae5`), but it depends on crypto-kmp
  2.1.1, which is Nostr-only. So it cannot be resolved without Magpie either way.
- **quartz's README** at v2.20.1 says "2.18.0 and older are on Central". They are not:
  2.16.0–2.18.0 return 404. Central stops at 2.15.1.
- **marmot-kmp's `main`** has commits after v2.3.0, all of them build changes. 2.3.0 is the
  latest code.

## What Magpie is

The plan stands on this, so here it is in enough detail to judge. The sources are the
plugin at v0.2.3: `MagpieSettingsPlugin.kt`, `docs/ARCHITECTURE.md`, `LockFile.kt`,
`TrustPolicy.kt` and `OPEN-QUESTIONS.md`.

- **Resolution.** The settings plugin registers a Maven repository at
  `http://127.0.0.1:<port>`, served by an engine inside the Gradle daemon. It wraps that
  repository in `exclusiveContent { includeGroupAndSubgroups(group) }` for every group named
  in its `magpie { }` block.
  - The engine finds releases as kind-3071 events on the publisher's relays: nostr.mom,
    nos.lol, nostr.bitcoiner.social and vitor.nostr1.com.
  - It fetches the files by sha256 from Blossom. Every release so far hints
    `nostr.download`, with `cdn.satellite.earth` as the fallback.
- **Trust.** The publisher is the key that `vitorpamplona.com/.well-known/nostr.json` names
  as `releases`. It is checked once per daemon, and the lock is used when the domain is
  unreachable.
- **The lock.** `gradle/magpie.lock` records the publisher and one `release <g:a:v>
  <manifest-sha256> <event-id>` line per module *actually resolved*. That means one line per
  platform variant: `crypto-android`, `crypto-iosarm64` and so on.
  - The lock is written as a side effect of resolution. There is no task for it.
  - `-Pmagpie.frozenLock=true` makes any new line a build failure.
  - A different manifest for a version already locked fails with "Refusing to resolve
    different files".
- **Exclusivity is per group.** Once `com.vitorpamplona.quartz` is a Magpie group, no
  version of it can come from Central, and the relay holds only the new releases. So
  neither half can move without the other: Magpie and the version bump are one commit.
- **Requirements.**
  - The Gradle daemon must run on Java 21+, or the build throws at configuration time
    (`MIN_DAEMON_JAVA = 21`).
  - Gradle 8.8+. The app is on 9.7.1.
  - Configuration cache is supported, which is moot here: the app has it off for
    `lightning-kmp-app`'s sake.
  - The engine is about 21 MB in its own class loader per daemon.
- **Offline.** `--offline` reads a mirror under `~/.gradle/magpie/mirror/<pubkey>/` that an
  earlier online build filled.
- **Age.** v0.1.0 is from 2026-11-14 and v0.2.3 from 2026-11-19, so Magpie is six days old.
  0.2.0 changed its config incompatibly. No yanks exist (#5), and without a lock the
  earliest release of a version wins (#6). There are no open issues.
- **Adoption.** Amethyst has not adopted it. On `main` it pins quartz 2.17.0 overridden by
  a JitPack build of a quartz commit, with marmot 2.2.1 and mls 1.3.0. Its comment says
  Central refused 2.17.0 over a monthly release-size limit that "resets 2026-12-01", which
  suggests the move off Central began as a quota workaround and may not be the last word.

Nothing in the app's composite build (`lightning-kmp-app` and the four repositories under
it) names a `com.vitorpamplona` group, so the exclusive content reaches only `composeApp`.

## Constraints

**C1 — a group on a device today must still open tomorrow.** This is unchanged from
[marmot-kmp.md](./marmot-kmp.md), and it is cheaper to satisfy this time. `MlsGroupState.kt`
is not in mls-kmp's v1.3.0..v1.4.0 diff: `STATE_VERSION` is 10 and `decodeTls` still
accepts `1..10`. `RetainedEpochSecrets` still has five fields, the five
`RoomMlsGroupStateStore` decodes into columns. That is reading. Phase 4 runs the tests that
measure it.

**C2 — the fork still merges from Bennu.** This update touches three files a cherry-pick
can conflict in: `settings.gradle.kts`, the catalogue and the new `gradle/magpie.lock`. It
touches no Kotlin source, if Phase 2 confirms what Phase 0 predicts. A Bennu change that
brings an old quartz spelling back is still caught by `marmotAudit`.

**C3 — a build is reproducible from the lock.** Moving off Central swaps one trust model
for another, and the lock is what makes the new one checkable. It pins the publisher key
and the sha256 of every module's manifest. So a lock that is committed, complete and frozen
is a stronger statement than a version number on Central. A lock that is missing,
incomplete or unfrozen is weaker: trust on first use, against one relay and one Blossom
server.

**C4 — every target resolves.** The app's iOS targets exist only on a mac (see
`composeApp/build.gradle.kts`). The lock is per module, so a lock written on Linux has no
`iosarm64` or `iossimulatorarm64` lines, and a frozen-lock build on a mac fails until a mac
has written them. schnorr256k1-kotlin adds a small C cinterop on Kotlin/Native (`mulhi.def`,
128-bit multiply helpers, no libsecp256k1 symbols), so it is one more iOS klib that only a
mac resolves.

`ForkCompatibilityTest` and the `mantra/…` tags are untouched, as before. Both stay in the
gate at every phase boundary.

## Decisions

**D1 — adopt Magpie; it is the only road to any of these versions.** The alternatives, and
why each fails:

| alternative | why not |
|---|---|
| stay where we are | defensible, and the fallback if Phase 0 says no. The cost grows with each release the app is not on, and every fix to the engine now lands only on the relay. |
| quartz 2.15.1 from Central, with marmot 2.2.1 | breaks D2: marmot 2.2.1 was built against 2.7.3. It buys nothing the app uses and still leaves mls, marmot and crypto where they are. |
| JitPack | no build exists for any of these tags, and the POMs it would serve name crypto 2.1.1 and negentropy v1.2.3, which only Magpie resolves. |
| source composites, as `lightning-kmp-app` is | each library's own `settings.gradle.kts` applies Magpie for *its* dependencies, so including quartz, mls, crypto, marmot, negentropy and schnorr256k1 from source still needs Magpie, six levels deep. |

This is the one decision in the plan that is the owner's rather than the code's: it adds a
build-time dependency on a six-day-old plugin and a publisher's relays. The recommendation
is yes, with the lock committed and frozen (C3), and with this table as the record of what
was weighed.

**D2 — two steps, not one: marmot's own pins first, then the newest.** The old D2's rule
holds. A library running against a version it was not built with fails at run time, not
compile time. So Phase 2 takes exactly what marmot-kmp 2.3.0 was built and tested against:
quartz 2.20.0, mls-kmp 1.3.1, crypto-kmp 2.1.1. Phase 5 then moves quartz to 2.20.1 and
mls-kmp to 1.4.0, one commit each, with the evidence for each. If either misbehaves, it goes
back on its own and the record says why.

**D3 — the catalogue states what resolves, strictly.** Gradle resolves the highest version
anything asks for, which is why the old D2 needed a comment as long as the pin. Declare the
four versions with `strictly`, as a rich version in the catalogue's `[versions]` table. Then
a transitive request cannot move one silently: marmot 2.3.0 asking for quartz 2.20.0 when
Phase 5 says 2.20.1, say, or a future library asking for a newer crypto. The catalogue then
is the resolved graph, rather than a request Gradle is free to overrule.

**D4 — the daemon's Java is a file, not a habit.** Magpie refuses a daemon below 21. The
shell here happens to have `JAVA_HOME` on 21 and `java` on 17. `gradle/gradle-daemon-jvm.properties`,
written by `./gradlew updateDaemonJvm --jvm-version=21`, makes Gradle choose the daemon's
JVM itself, and Android Studio honours it.

**D5 — no new behaviour inside the update.** mls-kmp 1.4.0's
`validateJoin(group, groupInfoSigner, groupInfoExtensions)` is the shape
[robust-group-admins.md](./robust-group-admins.md)'s joiner check would want. `MarmotManager`
and `MarmotIngestDedupStore` are still where marmot-kmp.md left them. Each is its own change.

## Phase 0 — the go/no-go, and the baselines

No code. Four things, all of which later phases consume:

1. **D1, decided.** If the answer is no, this note stops here and records why.
2. **Availability, confirmed on the relay rather than on GitHub.** Do this in a throwaway
   project in a scratch directory with Magpie applied: quartz 2.20.0 and 2.20.1, marmot
   2.3.0, mls 1.3.1 and 1.4.0, crypto 2.1.1, negentropy v1.2.3, schnorr256k1-kotlin 1.0.3,
   each for jvm and android. A tag is not a release. mls 1.4.0 is the one in doubt, and if
   it is absent Phase 5 takes 1.3.1's place and waits.
3. **The resolved graph, before.** Run `./gradlew :composeApp:dependencies` for
   `jvmRuntimeClasspath`, `debugRuntimeClasspath` and `releaseRuntimeClasspath` into
   `before-*.txt`, plus `dependencyInsight` for `secp256k1-kmp` to show the composite's
   substitution. Phase 2's check is a diff against these files.
4. **The suite, before.** Note the test and class counts from
   `./gradlew :composeApp:jvmTest` and that `check` is green. Run
   `git submodule update --init --recursive` first: a fresh worktree cannot configure
   without `lightning-kmp-app`, and this one has not initialised it.

**Check:** the decision, the availability table and the two baselines exist.

**Done.** D1 is decided: adopt Magpie. The owner made that call on 2026-11-20, after reading
this note.

**Availability.** I checked with a throwaway project that applies Magpie 0.2.3 and resolves
each coordinate as a detached configuration under jvm and androidJvm attributes:

| coordinate | on the relay | brings |
|---|---|---|
| quartz 2.20.0 | yes | crypto 2.1.1, negentropy v1.2.3, schnorr256k1-kotlin 1.0.3 |
| quartz 2.20.1 | yes | the same three |
| marmot 2.3.0 | yes | quartz 2.20.0, mls 1.3.1, crypto 2.1.1 |
| mls 1.3.1 | yes | crypto 2.1.1 |
| **mls 1.4.0** | **no** — "Could not find", and a 404 on Central | — |
| crypto 2.1.1 | yes | — |

So mls-kmp 1.4.0 is a tag and not a release, and Phase 5b waits for it. The newest mls-kmp
anyone can resolve is 1.3.1, which is what marmot 2.3.0 was built against. The probe's lock
also shows the lock's shape. The `-android`, `-jvm` and root modules of one release all
carry the same manifest hash, so a lock line is per module and a manifest is per release.

**Baselines,** at `d51fdde0`:

- The `jvmRuntimeClasspath`, `debugRuntimeClasspath` and `releaseRuntimeClasspath` reports
  are reduced to sets of 268, 332 and 326 resolved coordinates.
- `secp256k1-kmp` resolves to the composite's project substitution, which is the line to
  watch.
- `:composeApp:jvmTest` runs 1389 tests in 182 classes, with 0 failures.
- `./gradlew check` is green, and all four audits pass.

The submodules had to be initialised, as Phase 0 of marmot-kmp.md found. So did the
gitignored `local.properties` at each of the five composite levels, as the README's
Building section says. Without them, configuring fails with "SDK location not found" from
`secp256k1-kmp`.

## Phase 1 — Java 21, pinned

`./gradlew updateDaemonJvm --jvm-version=21`, committing `gradle/gradle-daemon-jvm.properties`.
A line in the README for Android Studio: Settings → Build → Gradle → Gradle JDK set to 21,
and the first sync after Phase 2 needs the network.

It is its own commit because it is the only part of this plan that changes how every
developer's build starts. If it bites, it should bite before any dependency has moved.

**Check:** `./gradlew --stop`, then `./gradlew :composeApp:jvmTest` with `java` on the path
at 17. The daemon comes up on 21 and the suite matches Phase 0's count.

**Built.** `updateDaemonJvm` wrote `toolchainVersion=21` and foojay download URLs for each
OS and architecture. The foojay resolver was already applied in `settings.gradle.kts`, so a
machine with no local 21 gets one downloaded. A machine with one, like this one with
`/usr/lib/jvm/java-21-openjdk-amd64`, uses it.

With `JAVA_HOME` pointed at 17, `./gradlew --version` reports a 17 launcher and "Daemon JVM:
Compatible with Java 21 … (from gradle/gradle-daemon-jvm.properties)". The suite, rerun from
that launcher, matches Phase 0. The README's Building section says the same for Android
Studio.

## Phase 2 — the swap, in one commit

Everything Magpie needs and nothing else.

```kotlin
// settings.gradle.kts -- pluginManagement already lists gradlePluginPortal()
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // quartz, mls-kmp, marmot-kmp and crypto-kmp are released on Nostr + Blossom from
    // quartz 2.19.0 / mls 1.3.1 / marmot 2.3.0 / crypto 2.1.1. docs/quartz-mls-marmot-update.md
    id("com.vitorpamplona.magpie") version "0.2.3"
}

magpie {
    group("com.vitorpamplona.quartz")
    group("com.vitorpamplona.marmot")
    group("com.vitorpamplona.mls")
    group("com.vitorpamplona.crypto")
    group("com.vitorpamplona.negentropy")
    group("com.vitorpamplona.schnorr256k1")
}
```

`com.vitorpamplona.quic` is left out: it is `marmot-quic`'s, and nothing here wants that
artifact.

```toml
# gradle/libs.versions.toml -- marmot-kmp 2.3.0's own pins (D2), strictly (D3)
quartz = { strictly = "2.20.0" }
marmot = { strictly = "2.3.0" }
mls = { strictly = "1.3.1" }
cryptoKmp = { strictly = "2.1.1" }
```

The catalogue's comments above those four lines are rewritten in the same commit. Each one
currently explains a 2.7.3-era choice that will no longer be true.

The lock is written by this phase's first build and committed with it. On Linux it covers
`jvm`, `android` and the metadata variants. C4 is why it is not complete yet, and Phase 6
completes it.

**What to expect from the compiler: nothing.** All 101 quartz symbols, all of marmot-kmp
and all of crypto-kmp resolve unchanged. mls-kmp 1.3.1's changes are inside `MlsGroup`'s
behaviour, not its signatures. A compile error here is a finding: record it in this note,
not only in the diff.

**Check:**

- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`, then `./gradlew check`.
- The resolved graph diffed against Phase 0's. The expected delta is exactly:
  - the four libraries;
  - negentropy v1.2.0 → v1.2.3;
  - schnorr256k1-kotlin added.

  Anything else moving is a finding, and `secp256k1-kmp` still resolving to the composite's
  project substitution is the one to look at first.
- `releaseRuntimeClasspath` as well as debug, because R8 is where a class that quartz moved
  between modules would show.

**Built, and the compiler had nothing to say.** No Kotlin file changed. `settings.gradle.kts`
applies Magpie 0.2.3 over the six groups, the catalogue names marmot 2.3.0's own pins with
`strictly`, and the first build wrote `gradle/magpie.lock`. Its 27 lines are a header, six
`publisher` lines (all the same key, vouched for by `nip05:releases@vitorpamplona.com`) and
eighteen `release` lines: the root, `-jvm` and `-android` module of each of the six releases.
As C4 predicted, there are no iOS lines.

- **Compile.** `compileDebugKotlinAndroid`, `compileKotlinJvm` and `compileTestKotlinJvm`
  were clean. The 85 deprecation warnings are all Compose's and AGP's (`LocalClipboardManager`,
  the non-mirrored icons, `runDesktopComposeUiTest`), which were there already. None names
  a quartz symbol.
- **The resolved graph.** It moved by exactly the expected delta in all three runtime
  classpaths: the four libraries, negentropy v1.2.0 → v1.2.3, and schnorr256k1-kotlin (root
  plus `-jvm` or `-android`) added. That is 268 → 273 on jvm, 332 → 337 on debug and
  326 → 331 on release. Nothing outside the six groups moved. `secp256k1-kmp`, `-jni-jvm`
  and `-jni-android` still resolve to the composite's projects.
- **The suite.** `./gradlew check` is green, and `jvmTest` runs 1389 tests in 182 classes
  with 0 failures, the same count as Phase 0. No test was edited.
- **R8.** An unsigned `assembleRelease` minifies with the new graph and produces a 37 MB
  APK. No missing-class warnings. The one R8 warning is the deprecated
  `android.r8.optimizedResourceShrinking=false` in this repo's own `gradle.properties`.

Phase 1's commit also had to take `gradle/gradle-daemon-jvm.properties` out of
`.gitignore`. It had been ignored since `8de05742`, most likely to keep an IDE-generated
copy out of the tree. A file that states the build's daemon JVM has to be tracked.

## Phase 3 — what the swap means

Phase 2 ends when it compiles and the suite is green. This phase is for what is green and
means something else, and nearly all of it is mls-kmp 1.3.1:

| mls-kmp 1.3.1 change | where it reaches the app | spec |
|---|---|---|
| a new group's epoch-0 epoch secret is random, not derived from the GroupContext with a zero init secret | `MarmotGroupFactory`, and the ten fixtures that call `MlsGroup.create` | `MarmotGroupCreationJvmTest`, `RobustRoomKeyCeremonyTest`, `SubgroupCeremonyInParentRoomTest` |
| epoch-0 `interimTranscriptHash` is computed from the confirmation tag (RFC 9420 §11), where it was empty; a group saved at epoch 0 by 1.3.0 or older keeps the empty hash until its first commit | every room created before the update and never committed in | the new test below |
| an external join into an epoch-0 group fails between 1.3.0 and 1.3.1 | nowhere — the app has no `externalJoin` call site | grep, recorded |
| the resumption-PSK lookup for ReInit and external commits | nowhere today | — |

Two new tests, because the existing ones cannot see the second row:

1. **An old epoch-0 group moves forward.** This is the case the second row changes: a room
   created before the update and never committed in. The epoch-0 interim hash only feeds the
   *next* commit, and a Marmot creator normally adds its members in that commit, so this is
   the path every pre-update room still at epoch 0 takes.
   - Restore `OldEngineBlob` (epoch 0, empty interim hash) on 1.3.1.
   - Commit an add from it.
   - Join through the Welcome on `MarmotStandIn`.
   - Have the joiner read a message the creator sends, and the creator read one back.
2. **The wire, both ways round, against the old engine.** Extend
   `docs/tools/old-state-blob/`, which already depends on nothing but quartz 1.15.2, into a
   probe. The old engine joins a group 1.3.1 created and reads a message, then the reverse.
   It stays out of the suite, for the same provenance reason it was written. This is the
   Bennu peer's side of C2, if Bennu is still on that engine. Check which engine Bennu ships
   before writing it, and pin that version instead if it has moved.

Then quartz's behavioural deltas that reach the app, both small:

- **`GiftWrapEvent.create`** (16 call sites) now encrypts with the throwaway key directly.
  Same signature, same NIP-59 envelope. The NIP-17 and Welcome-wrapping tests are the spec,
  and `MarmotClientInteropJvmTest` covers a Welcome wrapped by the library and opened by the
  app.
- **`EventFactory`** now returns new subclasses for kinds 30078, 30100 and 30102 when their
  tags match. The app names none of those kinds (grep, recorded). It is listed so the next
  person does not have to check.

**Check:** the suite, the two new tests, and the probe run once by hand with its output
recorded here. A test that needed its assertions edited to pass is a finding, as in
marmot-kmp.md's Phase 2.

**Built.** The existing suite needed nothing: it was already green on 1.3.1 in Phase 2, with
no assertion moved.

**`OldEpochZeroGroupJvmTest`**, two tests, both green. The first draft of the first one
was wrong, and the way it was wrong is worth keeping.

mls-kmp's commit `6a1ba88` says a creator's epoch-0 state saved by 1.3.0 or older "is
repaired on restore", and the test asserted that. It failed. The *released* 1.3.1 has no
repair: its audit commit, `125614a`, took it out ("repairing only the creator forked the two
apart" from a PublicGroup saved beside it). The library's upgrading guide says what ships:
"an older release['s epoch-0 group] keeps the old hash, so external joins into it fail until
its first commit". So the test now pins both sides of that:

- an old epoch-0 room keeps its empty interim hash across a restore;
- a room created now gets the §11 hash;
- the old room still adds its first member, and the joiner and the creator agree on the
  epoch authenticator and the kind:445 key and read each other.

A future mls-kmp that brings the repair back fails the first assertion, and its KDoc says
to re-read the upgrading guide before inverting it. The app has no `externalJoin`, so the
one thing an old epoch-0 room cannot do is something this app never asks of it.

**`docs/tools/engine-interop/`** is the probe, built as two programs rather than an
extension of `old-state-blob`, for two reasons:

- That tool's whole job is provenance for one blob, and should stay that small.
- The probe cannot be one build. Both halves depend on the `com.vitorpamplona.quartz`
  group, at 1.15.2 and 2.20.0, and Magpie serves that group exclusively from a relay with
  nothing older than 2.19.0. So `old/` is quartz 1.15.2 from Central and nothing else,
  answering one command per line on stdin, and `new/` is the app's versions through
  Magpie, driving it as a child process.

Each scenario goes three epochs:

1. the Welcome;
2. two further adds by the room's creator, which the other engine applies, checking each
   confirmation tag against a transcript it computed itself;
3. a message each way at every epoch.

The first draft had the joiner commit an add too, and both engines refused it on MIP-03's
rule that only an admin may. That was correct of them, and the scenario was wrong. Across
the two scenarios each engine authors commits and each applies the other's. The run:

```
== the new engine creates the room, the old one joins
   ok   after the Welcome: both at epoch 1 with the same kind:445 key
   ok   epoch 1: the old engine reads the new one / the new engine reads the old one
   ok   after the new engine's first commit past the Welcome: both at epoch 2 …
   ok   after its second: both at epoch 3 …   (and both ways at 2 and 3)
== the old engine creates the room, the new one joins
   …the same ten checks, with the old engine committing…
ALL PASSED
```

That is 20 checks, 20 green. So a room either engine creates, at either epoch-0 shape, works
with the other through three epochs. **What it does not say** is which engine Bennu ships
today. Its repository is not readable without signing in, so quartz 1.15.2 is
marmot-kmp.md's last record of it, not a fresh one. If Bennu has moved, the probe's `old/`
pins that version instead, and Phase 8's real-peer check covers whatever it is.

**quartz's two behavioural deltas**, both checked:

- **`GiftWrapEvent.create`** is called at 16 sites with the same signature. The NIP-17,
  Welcome-wrapping and `MarmotClientInteropJvmTest` suites pass unchanged, and the last
  of those has the library's `MarmotWelcomeSender` wrap a Welcome the app opens.
- **`EventFactory`'s new subclasses** are for kinds 30078, 30100 and 30102.
  `grep -rnE '\b(30078|30100|30102)\b' composeApp/src` finds nothing.

## Phase 4 — C1, and the fork, measured again

No new code expected. This phase runs the tests marmot-kmp.md left as tripwires and records
what they say:

| test | what it proves on the new engine |
|---|---|
| `MlsGroupStateCompatibilityTest` | the version-4 blob still decodes, restores under `MarmotGroupPolicy` and re-saves at version 10. If mls had bumped `STATE_VERSION`, the "re-save is version 10" assertion fails, and that is the signal to re-read the decoder, not to edit the number |
| `ShippedProfileUpgradeJvmTest` | a database at the shipped schema opens, with the old blob inside it, and opening rewrites nothing |
| `RoomMlsGroupStateStoreJvmTest`, `SenderRatchetPersistenceJvmTest` | the five retained-epoch columns and the sender-ratchet record, including the ordering hazard |
| `SkippedGenerationJvmTest` | skipped generations still persist. `consumedGenerations` is still a single `SecretTree`'s field (`SecretTree.kt:64`) and still is not persisted |
| `ConvergenceForkJvmTest` | the authored fork. marmot-kmp's source is unchanged and `MlsCandidateStateEngine.kt:103` still replays with `processFramedCommit`, so its `assertNotEquals` should still hold. If it fires, the library was fixed and the test inverts, as its message says |

No schema change is expected: no table and no column moves.

**Check:** the five tests green with their assertions untouched, and this table filled in.

## Phase 5 — past marmot's pins

Two commits, in this order, each reverting on its own.

**5a — quartz 2.20.0 → 2.20.1.** The release's one behavioural change is in quartz's relay
client: a REQ's live events are held until its EOSE. The app does not use that client. It
imports only `ReqCmd`, `CloseCmd`, `Filter` and the relay-URL normaliser from
`nip01Core.relay` and drives its own sockets. marmot-kmp does consume `INostrClient`, though,
so `MarmotClientInteropJvmTest` is the one test whose library-side code runs on a quartz
version it was not built against.

**5b — mls-kmp 1.3.1 → 1.4.0.** Three changes, all reaching code the app has:

| change | where | spec |
|---|---|---|
| `MlsGroupPolicy` gains `validateJoin(group, groupInfoSigner, groupInfoExtensions)` (default calls the old one) and `validateResultingState(...)`, run after `authorizeCommit` on send and receive | `GovernanceGate.RecordingPolicy : MlsGroupPolicy by MarmotGroupPolicy` forwards both; marmot's `MarmotGroupPolicy` was compiled against 1.3.1 and inherits the defaults | `GovernanceGateJvmTest` |
| `addMembers`/`removeMember` put their proposals back in the pool when the commit is refused | marmot's `MlsGroupManager.removeMember` (`:918`) is the direct path; its `stage()` clones under `MarmotGroupPolicy` and is unaffected | `RobustGroupAdminsJvmTest`, `AdminChangesJvmTest`, `GroupLifecycleJvmTest`, the member-admin-promotion and member-row-actions suites |
| `GroupView` gains `leafAt` and `leafNode()`, keeping the five-argument constructor as a secondary one | `GovernanceStatementEventTest.kt:36` | that test |

The runtime question D2 exists for is the first row. marmot-kmp's classes compiled against
1.3.1 have to link against 1.4.0's interface. The API dump says every `MlsGroupPolicy`
member is non-abstract, which on the JVM means a default method and no
`AbstractMethodError`. Confirm it on the jar with `javap -c` before trusting it. Android
desugaring is the place it could differ, so the check includes a debug install that creates
a group. On Kotlin/Native everything links when the app is built, so the compiler answers it
there.

**Check:** the suite after each commit, and the resolved graph showing exactly the one
library moved. If 5b fails in marmot's code rather than the app's, hold mls at 1.3.1, record
why here, and leave the strict pin to say so (D3).

## Phase 6 — every target, then the lock frozen

On a mac:

- `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64`.
  This lets Magpie write the `iosarm64`, `iossimulatorarm64` and schnorr256k1 cinterop lines
  the Linux lock lacks.
- Commit the lock. The diff must contain additions only. A changed line is a different
  manifest for a version already locked, and Magpie should have refused it.

Then freeze it. `magpie.frozenLock=true` goes in `gradle.properties`, so every build,
developers' and any future CI's, fails on a new line rather than writing one. Changing a
version becomes a deliberate act: `-Pmagpie.frozenLock=false` once, on a machine that
resolves every target, and the lock diff is reviewed like code.

A short section in this note on offline work: one online build fills
`~/.gradle/magpie/mirror/`, after which `--offline` works.

**Check:** a frozen-lock build passes on Linux and on the mac. A deliberately bumped
version fails the frozen build with Magpie's message.

## Phase 7 — the guards, and the notes that still say 2.7.3

- **A lock guard.** Extend the `marmotAudit` script, or add a sibling, to fail `check` when a
  catalogue version of a Magpie group has no `release` line in `gradle/magpie.lock`. The
  frozen lock catches this at resolution time on the machine that resolves it. The audit
  catches it on any machine, including one that never resolved iOS.
- **[marmot-kmp.md](./marmot-kmp.md):**
  - D2 gains a line saying it was superseded here.
  - Its out-of-scope item "moving quartz past 2.7.3 … needs marmot-kmp to move first" gains a
    pointer to this note, since marmot-kmp has moved.
  - Its phases are left as written, as the history they are.
- **`docs/scripts/marmot-kmp-imports.sh`** says its rule table was derived against quartz
  2.15.1, marmot-kmp 2.2.1 and mls-kmp 1.3.0. That stays true as provenance. Re-run
  `--check` against the new versions to confirm the rule still describes where the classes
  live. If any class has moved, the script changes and the header says against what.
- **[README.md](./README.md)** gets a paragraph on reading this note after marmot-kmp.md.
- **CLAUDE.md**'s "Checking your work" gets one line: Gradle needs Java 21, and the first
  build needs the network.

**Check:** `./gradlew check`, with the guard verified both ways: silent on a clean tree, and
failing with a pointer to this note when a catalogue version is bumped without its lock
lines.

## Phase 8 — what a fixture cannot check

None of these can be automated here, and all of them stand between this work and a release:

- **A real profile, upgraded in place.** Android: install over the current release build.
  Desktop: open an existing profile. Use rooms that have been through several epochs,
  members and a retained-epoch window, and send and receive in each. This is the check
  marmot-kmp.md left open, still owed, and it now covers two engine changes rather than one.
- **A real Bennu peer, and a real MDK peer** (whitenoise). Create a group on each side and
  invite the other. Message both ways, then commit from each side. Phase 3's probe is the
  old engine in a test harness; this is the clients people run.
- **iOS on a device**, after Phase 6.
- **Android Studio sync from clean**: `~/.gradle/magpie` removed, Gradle JDK on 21.

## Risks, and what each one costs

| risk | if it happens | what this plan does about it |
|---|---|---|
| Magpie is 0.x and six days old, and 0.2.0 already broke its config | a plugin bump is a migration | pinned version; plugin bumps are their own change |
| the release key's `nostr.json` names one relay, and every release hints one Blossom server | a fresh machine cannot resolve until they are back | the frozen lock and the mirror keep existing machines building; a fresh one waits |
| no yanks, and without a lock the earliest release of a version wins | a bad release cannot be withdrawn upstream | the lock pins manifests, so a re-published version is refused, not taken |
| the lock is per module, and only a mac resolves iOS | a frozen build on a mac fails after a Linux-only bump | Phase 6, and Phase 7's guard |
| the move off Central was a quota workaround and may reverse (Amethyst's comment: the limit "resets 2026-12-01") | Central gets these versions back later | re-evaluate D1 then; going back is a revert of Phase 2's settings lines |
| marmot-kmp running on mls 1.4.0 / quartz 2.20.1, ahead of what it was built against | a link error or a changed default at run time | Phase 5, one commit each, held back on failure |

## Out of scope

`MarmotManager` as the app's engine, `MarmotIngestDedupStore`, adopting mls-kmp 1.4.0's
GroupInfo-aware `validateJoin` for the robust-room joiner check, and the authored-fork fix.
All are library-enabled behaviour (D5). `marmot-quic` and the `com.vitorpamplona.quic`
group. Kotlin 2.4.10 → 2.4.21, which would close the stdlib skew the app already lives with
and is a toolchain change rather than a library one.

## Where the numbers come from

On the worktree at `b7de119e`, 2026-11-20:

```bash
for p in quartz marmot mls crypto; do
  grep -rh "import com.vitorpamplona.$p\." --include='*.kt' composeApp/src | wc -l   # 1041, 178, 110, 29
done
curl -s https://repo1.maven.org/maven2/com/vitorpamplona/quartz/quartz/maven-metadata.xml   # <release>2.15.1
gh api repos/vitorpamplona/marmot-kmp/contents/gradle/libs.versions.toml?ref=v2.3.0        # quartz 2.20.0, mls 1.3.1, crypto 2.1.1
```

The source claims come from checkouts of quartz `v2.7.3`/`v2.20.1`, marmot-kmp
`v2.2.1`/`v2.3.0`/`main`, mls-kmp `v1.3.0`/`v1.3.1`/`v1.4.0`, crypto-kmp `v2.1.0`/`v2.1.1`
and magpie `v0.2.3`:

- the 101 quartz symbols, resolved by package and declaration at both tags;
- `git diff` of each library's `src/*Main` between tags;
- the mls-kmp 1.3.0 sources jar in the Gradle cache, compared with the tag;
- the poms and module files on Central, fetched directly rather than through its search
  index.
