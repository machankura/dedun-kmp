# Every other dependency: the update the composite decides the order of

[quartz-mls-marmot-update.md](./quartz-mls-marmot-update.md) moved the four libraries the group
layer stands on, and moved them yesterday. This plan is for everything else in
`gradle/libs.versions.toml`: the toolchain, Compose, and the eighteen or so libraries around them.

Three findings shape it:

- **Most of it is small, and none of it needs a newer Kotlin.** Every library target below asks
  for a `kotlin-stdlib` at or under 2.4.10, the version the app compiles with. The libraries can
  move first, on their own, without touching the toolchain.
- **The toolchain cannot move in Giza alone.** A composite build has to settle on one Android
  Gradle Plugin version across every build that applies it, and Gradle refuses to configure
  otherwise. That is what [546095b0](#where-the-numbers-come-from) ran into in September. AGP
  9.4.0 is pinned in Giza, in `lightning-kmp-app`, and twice in `secp256k1-kmp`, which is four
  submodules down. Kotlin 2.4.10 is pinned in all five builds. A toolchain bump is therefore a
  trip down the submodule chain, the way [submodule-refresh.md](./submodule-refresh.md) did it.
- **Material3 has been running against an older Compose than the app's.** The pinned
  `material3:1.10.0-alpha05` was compiled against Compose foundation **1.9.3**, while the app is
  on Compose **1.11.1**. Every JetBrains material3 since then is published with the Compose
  version it was built for, and 1.13.0 is the first since 1.9.0 to reach a release candidate.

Researched on 2026-11-20 against `390d4bc3`. The composite's pins were read from
`lightning-kmp-app` at `3794eb8`, the commit Giza's tree points at.

### Where the build stands

**Being built** on `claude/dependency-update-plan-1b853a`, one commit per step. Each phase
records what it turned out to be in the results table below.

| phase | result |
|---|---|
| 0 | the baseline, at `25f14aa4`. `check` green. `jvmTest` 1,407 run, 0 failed, 0 skipped, 182 classes; `testDebugUnitTest` 529 run, 0 failed, 65 classes. `m3-audit`: every budget 0, adaptive APIs **12 against a floor of 12**, navigation components 2 against 2. Resolved graphs: 277 coordinates on `jvmRuntimeClasspath`, 342 on `debugRuntimeClasspath`, 336 on `releaseRuntimeClasspath`. The newest versions were as the tables below say. The build's own warnings are AGP 9's seven deprecated opt-out flags in `gradle.properties` (each "removed in version 10.0"), the obsolete `applicationVariants` API, Kotlin's deprecation of KMP beside `com.android.application`, and three Gradle 10 deprecations (`by getting`, `by registering`), all of them in the composite's scripts rather than Giza's |
| 1 | built. Eight library entries, the `sqldelight` plugin alias and six version keys removed, with the six commented-out lines in `composeApp/build.gradle.kts` that named them or Room 2's aliases. All three resolved graphs are identical to Phase 0's, line for line. `check` green |
| 2a | built. serialization 1.13.0, kermit 2.2.0, okio 3.18.2. The graph diff is those three families and nothing else; serialization moved 1.12.0 → 1.13.0, since quartz had already lifted it past the declared 1.11.0. kermit 2.2.0 publishes one `kermit-android` where 2.1.0 had `kermit-android-debug`. `jvmTest` 1,407 run, 0 failed, once the `IdentitySwitchJvmTest` 5 s timeout seen under the first full run (the one [giza-wallet-removal.md](./giza-wallet-removal.md) Phase 3 records) passed three times alone and in a second full run |
| 2b | built, as ktor 3.6.1 alone: coil went to Phase 3 (every coil 3.6.x needs Compose 1.12.0), and so did qrose (2d; 1.2.0 on is built for Compose 1.12). ktor 3.6's `ktor-serialization-kotlinx` now depends on `ktor-openapi-schema`, which brings kaml 0.79.0, snakeyaml-engine-kmp 3.1.1 and urlencoder-lib 1.6.0 onto every runtime classpath, and asks for `kotlinx-datetime:0.8.0-0.6.x-compat`, which Gradle ranks above the app's 0.8.0 and picks. The compat build is 0.8.0 plus the old `kotlinx.datetime.Instant` and `Clock`, so it is a superset, and the app compiles unchanged. It is recorded here because it is a change of artifact, not just of version. `IdentitySwitchJvmTest`'s 5 s wait failed in the first runs, and with the build cache off it fails 1 run in 4 at the Phase 1 baseline and 1 in 4 on ktor 3.6.1: a flake that predates this plan. (The cache had been replaying one failing result as if it were three.) `check` otherwise green |
| 2c | built. room3 3.0.3, sqlite 2.7.1, DataStore 1.3.0, KSP 2.3.14. sqlite was already resolving 2.7.1 through room3, so only the declaration moved. Room's KSP run regenerated nothing under `composeApp/schemas`. DataStore 1.3.0 brings `androidx.tracing` 2.0.1 (from 1.2.0) and atomicfu 0.28.0 onto the Android classpath. KSP 2.3.14's notes (2.3.7–2.3.14) add language support behind an opt-in and deprecate the bare `ksp(...)` configuration in KMP, which `composeApp` already avoids with `kspAndroid`, `kspJvm` and the iOS pair. `jvmTest` 1,407 run, 0 failed; `testDebugUnitTest` 529, 0 failed; `check` green. C1's proof on real files is Phase 8.1 |
| 3 | built, on **1.12.2 with material3 1.12.0-alpha03** (D3): 1.13.0 was still `rc01` when the phase started. Also navigation-compose 2.10.0, Hot Reload 1.3.0, coil 3.6.3 and qrose 1.3.1. It compiled and passed on the first build: `jvmTest` 1,407 run, 0 failed, with `StringCatalogueJvmTest`, `ColorSchemeContrastTest` and the `readableContent()` test among them; `testDebugUnitTest` 529, 0 failed; `m3-audit` adaptive 12/12, navigation 2/2, every budget 0. `QRCodeView.kt` needed no change for qrose's moved declarations. One new deprecation in the app's code: the no-argument `rememberModalBottomSheetState()` at `HomeScreen.kt:105` ("Use rememberBottomSheetState with Hidden initial value"). It is left, like the five `LocalClipboardManager` deprecations already there, because it is UI work and not a version. One "condition is always true" warning went away. Where the plan was wrong: on Android, JetBrains' material3 is a redirect to Google's, so the Android classpath carries `androidx.compose.material3:material3` and `-adaptive-navigation-suite` **1.5.0-alpha22** (from 1.5.0-alpha08). Google's suite did not leave; it moved with the rest. `material3-window-size-class` does not arrive at runtime. Seven legacy androidx libraries (`documentfile`, `legacy-support-core-utils`, `loader`, `print`, `transition` and two more) left the Android classpath. Adaptive stays 1.2.0 on both platforms. **Not done here:** looking at the app (3.5) and scanning QR codes (2d). Both are in Phase 8 |
| 4 | built. Gradle 9.9.2, with the wrapper run twice. 9.9.2's wrapper adds `retries=0` and `retryBackOffMs=500` to `gradle-wrapper.properties` and regenerates both scripts. The daemon still resolves Java 21 from `gradle-daemon-jvm.properties`. Resolved graphs identical to Phase 3's. `check` green, `jvmTest` 1,407 / 0, `testDebugUnitTest` 529 / 0. Gradle 10 deprecations are the same as before, all from the composite's scripts or plugins: `by getting` ×3, `by registering` ×2, `AbstractTestTask.afterSuite(Closure)` and `Configuration.getTaskDependencyFromProjectDependency` |

| phase | what it is | rough size |
|---|---|---|
| 0 | the baselines: the resolved graph, the test counts, the warnings, the newest versions checked again | half a day |
| 1 | the catalogue's dead entries removed, with no version moved | an hour |
| 2 | the libraries that touch neither the UI nor the composite: network, serialization, persistence, QR | a day |
| 3 | Compose Multiplatform and the material3 that was built for it | one to two days |
| 4 | the Gradle wrapper | an hour |
| 5 | AGP, down the composite and back up | a day, most of it in the submodules |
| 6 | Kotlin, down the composite and back up, on the same trip as Phase 5 | a day |
| 7 | the notes and comments that still name the old versions | half a day |
| 8 | the checks Linux cannot make: a mac, a device, a desktop run on real profiles | a mac session and a device session |

## Where everything is

Newest stable and newest pre-release are as of 2026-11-20, from the repository each one
resolves from. **Bold** is a version that moves.

### The toolchain

| what | where it is pinned | today | newest stable | newest pre-release | target |
|---|---|---|---|---|---|
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | 9.7.1 | 9.9.2 | — | **9.9.2** |
| Android Gradle Plugin | `agp`, and the composite (C2) | 9.4.0 | 9.5.1 | 9.6.0-alpha04 | **9.5.1** |
| Kotlin (multiplatform, compose compiler, serialization) | `kotlin`, and the composite | 2.4.10 | 2.4.21 | 2.5.0-RC | **2.4.21** |
| KSP | `ksp` | 2.3.6 | 2.3.14 | — | **2.3.14** |
| Compose Hot Reload | `composeHotReload` | 1.2.0 | 1.3.0 (published today) | — | **1.3.0** |
| foojay resolver | `settings.gradle.kts` | 1.0.0 | 1.0.0 | — | 1.0.0 |
| Magpie | `settings.gradle.kts` | 0.2.3 | 0.2.3 | — | 0.2.3 |
| Daemon JVM | `gradle/gradle-daemon-jvm.properties` | 21 | — | — | 21 |

### Compose

| library | today | newest stable | newest pre-release | target |
|---|---|---|---|---|
| Compose Multiplatform (plugin, runtime, foundation, ui, resources, tooling) | 1.11.1 | 1.12.2 | 1.13.0-rc01 (2026-11-18) | **1.13.0**, or **1.12.2** (D3) |
| `org.jetbrains.compose.material3:material3` and `-adaptive-navigation-suite` | 1.10.0-alpha05 | 1.9.0 | 1.13.0-rc01 | **1.13.0**, or **1.12.0-alpha03** (D3) |
| `org.jetbrains.compose.material3.adaptive:adaptive` | 1.2.0 | 1.3.0 | 1.4.0-alpha01 | 1.2.0 (see below) |
| `org.jetbrains.compose.material:material-icons-*` | 1.7.3 | 1.7.3 | — | 1.7.3; nothing newer exists |
| `org.jetbrains.androidx.navigation:navigation-compose` | 2.9.2 | 2.10.0 | — | **2.10.0** |
| `org.jetbrains.androidx.lifecycle:*` | 2.11.0 | 2.11.0 | — | 2.11.0 |
| `androidx.activity:activity-compose` | 1.13.0 | 1.13.0 | 1.14.0-alpha03 | 1.13.0 |

Which material3 goes with which Compose, from each material3 pom:

| material3 | built against Compose | its navigation suite asks for adaptive |
|---|---|---|
| 1.10.0-alpha05 (today) | 1.9.3 | 1.2.0, plus Google's `androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-alpha08` |
| 1.12.0-alpha03 | 1.12.0-beta01 | 1.2.0 |
| 1.13.0-rc01 | 1.13.0-rc01 | 1.2.0 |

The catalogue's comment on `material3Adaptive` still holds at every target. The navigation
suite asks for adaptive 1.2.0 and window-core 1.4.0, and adaptive 1.3.0 would bring in
window-core 1.5.0 beside them. Adaptive stays at 1.2.0. Only the version the comment names
changes (Phase 7).

From 1.11 onward material3 also depends on `material3-window-size-class` from the same group.
That is a new transitive dependency, and Phase 3 checks that nothing in the app collides with
it.

### Libraries

| library | today | newest stable | target | asks for kotlin-stdlib |
|---|---|---|---|---|
| `kotlinx-serialization` (json, cbor) | 1.11.0 (Gradle already resolves 1.12.0, via quartz) | 1.13.0 | **1.13.0** | 2.4.10 |
| `kotlinx-coroutines` | 1.11.0 | 1.11.0 | 1.11.0 | — |
| `kotlinx-datetime` | 0.8.0 | 0.8.0 | 0.8.0 | — |
| `io.ktor:*` | 3.5.2 | 3.6.1 | **3.6.1** | 2.3.21 |
| `io.coil-kt.coil3:*` | 3.5.0 | 3.6.3 | **3.6.3**, in Phase 3: it needs Compose 1.12.0 | 2.4.10 |
| `com.squareup.okio:okio` | 3.18.1 | 3.18.2 | **3.18.2** | 2.1.21 |
| `co.touchlab:kermit` | 2.1.0 | 2.2.0 | **2.2.0** | 2.3.21 |
| `io.github.alexzhirkevich:qrose` | 1.1.2 | 1.3.1 | **1.3.1**, in Phase 3: 1.2.0 on needs Compose 1.12 | 2.4.0 |
| `androidx.room3:*` (runtime, compiler, plugin) | 3.0.1 | 3.0.3 | **3.0.3** | 2.1.20 |
| `androidx.sqlite:sqlite-bundled` | 2.7.0 | 2.7.1 | **2.7.1** | 2.1.20 |
| `androidx.datastore:*` | 1.2.1 | 1.3.0 | **1.3.0** | 2.2.20 |
| `app.cash.sqldelight:*` | 2.3.2 | 2.4.1 | 2.3.2 (D5) | 2.3.10 |

Nothing in the last column is above 2.4.10, so Phase 2 does not wait on Phase 6.

### Left alone, and why

| what | today | why |
|---|---|---|
| quartz, marmot-kmp, mls-kmp, crypto-kmp | 2.20.1, 2.3.0, 1.3.1, 2.1.1 | [quartz-mls-marmot-update.md](./quartz-mls-marmot-update.md) owns them, and they are the newest released as of yesterday. mls-kmp 1.4.0 is its Phase 5b |
| `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0` | — | substituted by the composite and never resolved; the version is a placeholder |
| `android-compileSdk`, `-targetSdk`, `-minSdk` | 37, 37, 26 | not revisited here; a target SDK move is a behaviour change with its own checks |
| `docs/tools/engine-interop/old`, `docs/tools/old-state-blob` | old engines | pinned to old versions on purpose; they exist to reproduce them |

### Dead catalogue entries

Nothing in any build script reads these:

| entry | note |
|---|---|
| `androidx-core-ktx`, `androidx-appcompat` | |
| `androidx-testExt-junit`, `androidx-espresso-core`, `junit` | there are no instrumented tests; `jvmTest` uses `kotlin-test-junit` |
| `androidx-sqlite-bundled-jvm`, `androidx-room3-sqlite-wrapper` | |
| `lightning-kmp-core` | commented out in `composeApp`; [giza-wallet-removal.md](./giza-wallet-removal.md) Phase 7 step 3 also drops it |
| `plugins.sqldelight` | not applied, as the comment in `composeApp`'s `plugins {}` explains |

`plugins.androidLibrary` is read once, in the root `apply false`. It stays. Taking it out
changes which class loader AGP's library plugin is first loaded in, and there is nothing to gain.

## Constraints

**C1 — what is on a device today still opens tomorrow.** No change in this plan touches the
group state. The Magpie four do not move. But three of the moves sit under a profile's files:

- `androidx.datastore` 1.2.1 → 1.3.0 reads every `*.preferences_pb`. That includes the ones
  `lightning-kmp-app`'s `DataStoreManager` writes, because Gradle resolves the highest
  version in the app's graph, and the library's own pin of 1.2.1 loses to the app's.
- Room 3.0.1 → 3.0.3 is two patches. There is no schema change and no migration. The exported
  `schemas/…/36.json` should come out byte-identical, and Phase 2 checks that.
- `androidx.sqlite` 2.7.0 → 2.7.1 is the bundled SQLite that opens the database file.

The proof is the one [giza-wallet-removal.md](./giza-wallet-removal.md) Phase 7 names: an
Android install over the current build, and a desktop run on an existing data directory, with
the same profiles and groups opening. That is Phase 8.

**C2 — one AGP across the composite.** The error, from 546095b0:

    Using multiple versions of the Android Gradle Plugin [9.1.1, 9.4.0] across Gradle builds is
    not allowed.

These builds pin it:

| build | where | today |
|---|---|---|
| Giza | `gradle/libs.versions.toml`, `agp` | 9.4.0 |
| `lightning-kmp-app` | `gradle/libs.versions.toml`, `agp` | 9.4.0 |
| `secp256k1-kmp` | `build.gradle.kts`, a buildscript classpath entry | 9.4.0 |
| `secp256k1-kmp` | `settings.gradle.kts`, a `resolutionStrategy` that rewrites every `com.android` plugin request | 9.4.0 |

`lightning-kmp` and `bitcoin-kmp` apply no Android plugin. They take part only to carry the
submodule pointer.

**C3 — one Kotlin across the composite.** This is a choice, not something Gradle enforces (D2).
Every build pins 2.4.10 today:

| build | where |
|---|---|
| Giza | `kotlin` in the catalogue (multiplatform, compose compiler, serialization) |
| `lightning-kmp-app` | `kotlin` in its catalogue |
| `lightning-kmp` | `kotlin` in its catalogue |
| `bitcoin-kmp` | `kotlin` in its catalogue |
| `secp256k1-kmp` | `kotlin("multiplatform") version "2.4.10"` in `build.gradle.kts` |
| `docs/tools/engine-interop/new` | `kotlin("jvm") version "2.4.10"`, whose comment says to keep it at the app's versions |

**C4 — green at every commit.** Each commit passes:

- `./gradlew check`, which runs `m3Audit`, `brandAudit`, `walletAudit`, `marmotAudit` and
  `magpieLockAudit`;
- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`, with the test count
  equal to Phase 0's;
- `./gradlew -Pmagpie.frozenLock=true :composeApp:jvmTest` once the lock is frozen. None of the
  moves here may add a lock line. If one does, a library has started asking for a newer quartz
  or crypto, and the `strictly` pins will already have failed resolution. That is the intended
  tripwire.

**C5 — iOS is built on a mac or not at all.** The `isMacOsX` gate in `composeApp` stays. So does
the `-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase` workaround for Kotlin
2.4.10's release linker, until a mac links a release binary without it (Phase 6). Every phase
that touches iOS-visible code leaves an iOS line for Phase 8.

## Decisions

The recommended option is first, and the plan below assumes it.

| # | question | recommended | alternative |
|---|---|---|---|
| D1 | Libraries or toolchain first? | **Libraries first** (Phases 2–3), then the toolchain (4–6). The libraries need no newer Kotlin and no submodule commit, so each is a single bisectable Giza commit. | Toolchain first, the usual order. Here it only puts the most expensive phase, the one that leaves this repository, in front of the cheap ones. |
| D2 | Kotlin in Giza alone, or down the chain as well? | **Down the chain, on the same trip as AGP.** The composite compiles all five builds in one daemon, and keeping them on one compiler is what has been done every time so far. The trip is already being made for AGP, so the extra cost is one line per submodule. | Giza alone. A newer compiler reads klibs from an older one, so it would probably work, but nothing here has been run that way. |
| D3 | Compose 1.13.0 or 1.12.2? | **1.13.0 with material3 1.13.0, if both are final when Phase 3 starts.** Otherwise 1.12.2 with material3 1.12.0-alpha03 now, and a follow-up that changes two lines once 1.13.0 ships. Either way the material3 alpha is retired onto the Compose it was built for. | Hold at 1.11.1 until 1.13.0. That keeps a material3 built for Compose 1.9.3 for however long that takes. |
| D4 | Pre-releases? | **Stable only**, except material3, which has had no stable release in JetBrains' line since 1.9.0 and is already an alpha here. Not taken: Kotlin 2.5.0-RC, AGP 9.6.0-alpha, room3 3.1.0-alpha, sqlite 2.8.0-alpha, activity 1.14.0-alpha, core 1.20.0-alpha, adaptive 1.4.0-alpha. | Take release candidates where they exist. |
| D5 | SQLDelight? | **Hold at 2.3.2.** Nothing under `mobi.giza` uses it. The runtime and drivers are there for `lightning-kmp-app`'s Phoenix databases, whose code its own 2.3.2 plugin generates. A 2.4.1 runtime under 2.3.2-generated code is a mismatch for no gain, and the whole dependency goes with wallet-removal Phase 7. | Bump the runtime to 2.4.1 and the library's plugin with it. That is a submodule commit for a dependency already on its way out. |
| D6 | One commit per library, or per phase? | **One per group that moves together**: a plugin with its runtime, Compose with material3, AGP with its composite. Each commit is green on its own (C4). | One commit per phase. That is fewer commits, but a regression found in Phase 8 then bisects to a commit that moved eight things. |
| D7 | What if wallet-removal Phase 7 lands first? | **Then Phases 5–6 shrink.** The composite becomes bitcoin-kmp → secp256k1-kmp: one AGP pin pair, and two Kotlin pins. Nothing else in this plan changes, so neither plan waits for the other. | — |

## Phase 0 — the baselines

**Half a day. No commit.**

1. **Check the newest versions again.** This plan's tables are from 2026-11-20, and Compose
   1.13.0 and Kotlin 2.5.0 are both days from final. For each coordinate, read the
   `maven-metadata.xml` from the repository it resolves from:
   - Google Maven: `androidx.*`, `com.android.tools.build:gradle`;
   - Maven Central: everything else, including the KSP plugin as
     `com.google.devtools.ksp:symbol-processing-gradle-plugin`;
   - the Plugin Portal: Hot Reload, foojay, Magpie;
   - `services.gradle.org/versions/current`.

   Update the tables before going on. A pairing that has changed, such as a new material3,
   gets its pom read the way the table above was.
2. **Dump the resolved graph** for the configurations that ship:

   ```bash
   ./gradlew :composeApp:dependencies --configuration jvmRuntimeClasspath > build/deps-jvm-before.txt
   ```

   ```bash
   ./gradlew :composeApp:dependencies --configuration debugRuntimeClasspath > build/deps-android-before.txt
   ```

   Every later phase diffs against these. A transitive move nobody asked for shows up there
   first. For example, serialization is already at 1.12.0 through quartz, and stdlib is
   already at 2.4.21.
3. **Record the counts.** `jvmTest`'s run, failed and skipped counts. `m3-audit`'s numbers,
   including the adaptive floor, which sits exactly at 12. The debug and release APK sizes.
4. **Record the warnings**: `./gradlew :composeApp:compileKotlinJvm --warning-mode all`, saved
   beside the dependency dumps. Compose and Gradle deprecations show up here a release before
   they become errors.

## Phase 1 — the dead entries

**An hour. One commit.**

Delete the entries in [Dead catalogue entries](#dead-catalogue-entries), along with the
`[versions]` keys only they referenced: `androidx-appcompat`, `androidx-core`,
`androidx-espresso`, `androidx-testExt`, `junit` and `lightningKmpCore`. Also delete the
commented-out lines in `composeApp/build.gradle.kts` that name an alias which no longer exists
(the Room 2 ones: `androidx.room`, `room.runtime`, `room.sqlite.wrapper`, `room.compiler`) or
which this phase deletes (`lightning.kmp.core`).

**Check:** both dependency dumps are identical to Phase 0's. If they are not, something did
read an entry, and it goes back.

## Phase 2 — the libraries around the app

**A day. Four commits.** None of them touches the composite or the toolchain.

**2a — serialization and logging.** `kotlinxSerialization` 1.13.0, `kermit` 2.2.0 and `okio`
3.18.2.
- Serialization changes only which version is *declared*, since Gradle already resolves 1.12.0.
- Check: `jvmTest`. The event and CBOR round trips are the ones that would notice.

**2b — network.** `ktor` 3.6.1. The plan first paired coil 3.6.3 with it. As built, coil went
to Phase 3, because every coil 3.6.x needs Compose 1.12.0: `coil-compose-core`'s module
metadata requires foundation 1.12.0. Taking it here would have moved Compose under a 1.11.1
plugin. `coil-network-ktor3` 3.5.0 runs on ktor 3.6.1 meanwhile, as it ran on 3.5.2.
- `lightning-kmp` asks for ktor 3.4.1. Gradle gives it 3.6.1, as it already gives it 3.5.2.
  ktor keeps binary compatibility inside 3.x, and nothing of the node runs any more, but
  `compileDebugKotlinAndroid` compiles the library against it, and that is the check.
- Check: `jvmTest`'s relay and websocket tests, then a desktop run that connects to relays,
  loads a room, and shows avatars. That run goes in Phase 8 if it cannot be made here.

**2c — persistence.** `room3` 3.0.3 (runtime, compiler, plugin), `sqlite` 2.7.1,
`datastorePreferences` 1.3.0 and `ksp` 2.3.14, in one commit. Room's compiler runs under KSP,
and the four are the stack under every profile (C1).
- Check: `./gradlew :composeApp:kspKotlinJvm`, then confirm that `git diff composeApp/schemas`
  is empty. The migration tests in `jvmTest` must pass, `RemoveWalletsMigrationJvmTest` among
  them.
- Confirm in KSP's release notes that 2.3.14 supports Kotlin 2.4.10 *and* 2.4.21, so Phase 6
  does not have to move it again.
- An Android install over the current build is Phase 8's first item.

**2d — QR codes.** `qrose` 1.1.2 → 1.3.1. This is two minors of a library with a rendering
surface. As built, it went to Phase 3 for the same reason as coil: 1.2.0's release notes say
"Compose updated to 1.12". Those notes also move "some declarations" to the core module under
a new package, and `QRCodeView.kt` imports `QrLogoShape`, `QrLogoPadding`, `QrBrush` and
`solid`. 1.3.0 mirrored the code, and 1.3.1 fixed that, so 1.3.0 is never the target.
- Check: the share-profile and key-package QR codes still scan from another device. The
  `m3-color-exempt` site keeps its pure black on pure white.

Each commit diffs the dependency dumps against the one before it. The diff should show only
what that commit named, plus its own transitive dependencies.

## Phase 3 — Compose and its material3

**One to two days. One commit.** Compose and material3 are released as a pair (D3), and a
commit that moves only one of them is the skew this phase exists to remove.

Move together:
- `composeMultiplatform` (plugin, runtime, foundation, ui, resources, tooling);
- `material3`, which also versions `material3-adaptive-navigation-suite`;
- `navigationCompose` 2.10.0;
- `composeHotReload` 1.3.0;
- `coilCompose` 3.6.3, which needs Compose 1.12.0 (moved here from 2b);
- `qrose` 1.3.1, which needs Compose 1.12 (moved here from 2d).

`material3Adaptive` stays at 1.2.0. `lifecycle` and `activity` are already current.

What to look at, in this order:

1. **The compile, and the deprecations.** Diff `--warning-mode all` against Phase 0.
   - `MaterialTheme.motionScheme` has to still be public. `CLAUDE.md` rests the motion rule on it.
   - `compose.desktop.currentOs` and `compose.desktop.uiTestJUnit4` are the two plugin accessors
     the build still uses. If either is deprecated, replace it with its coordinate in this commit.
2. **Compose Resources.** `CLAUDE.md` records that it does not unescape `\'` and does not
   collapse `%%`, but does process `\n`. That is behaviour, not a contract, and it can change
   in any Compose release. `StringCatalogueJvmTest` asserts each escape the app depends on, and
   it must pass unchanged. If an assertion has to change, the strings using that escape change
   in the same commit.
3. **The audits.** `m3Audit`, which needs at least 12 adaptive API uses (currently exactly 12)
   and at least 2 navigation components. A renamed adaptive API drops the count and fails the
   floor. Then `ColorSchemeContrastTest`, which runs in all six schemes, and the
   `readableContent()` measurement test.
4. **The Android graph.** Today's navigation suite pulls Google's
   `androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-alpha08` onto the
   Android classpath, and the new one should not. Confirm that in the dump diff. Confirm too
   that `material3-window-size-class` arrives once, at the new material3 version.
5. **Looking at it.** Run the desktop app at a compact, a medium and an expanded width:
   - the home screen's list and detail panes;
   - a pushed screen's back button;
   - the navigation bar against the rail;
   - a dialog, a snackbar, and a screen-state transition.

   material3 jumps two or three alpha lines here, and component defaults move between alphas.
   Screenshots before and after go in the commit message's notes or the PR.

## Phase 4 — Gradle

**An hour. One commit.**

```bash
./gradlew wrapper --gradle-version 9.9.2
```

Run it twice, so the second run updates the wrapper jar and scripts with the new version's own
code.
- The submodules' wrappers do not matter to a Giza build, because the composite runs on the
  root build's Gradle. They are left alone.
- `gradle-daemon-jvm.properties` should not change, since `toolchainVersion` stays 21.
- The configuration cache stays off (`gradle.properties`) for the reason written there.

This comes before AGP so that AGP 9.5.1's minimum Gradle version, whatever its release notes
say it is, is already met.

**Check:** C4, and diff `--warning-mode all` against Phase 0. Anything new marked "will fail
in Gradle 10" gets a line in this phase's record, not a fix.

## Phases 5 and 6 — AGP and Kotlin, down the composite

**Two days together, most of it in the submodules.** Both versions have to move in every build
that pins them (C2, C3), so they share one trip down the chain, bottom up, as
[submodule-refresh.md](./submodule-refresh.md) did:

| level | repository | branch it tracks | AGP edit | Kotlin edit |
|---|---|---|---|---|
| 4 | `kngako/secp256k1-kmp` | `threshold` | `build.gradle.kts` classpath and `settings.gradle.kts` `resolutionStrategy` → 9.5.1 | `build.gradle.kts` → 2.4.21 |
| 3 | `kngako/bitcoin-kmp` | `threshold` | pointer only | catalogue → 2.4.21, and the pointer |
| 2 | `kngako/lightning-kmp` | `threshold` | pointer only | catalogue → 2.4.21, and the pointer |
| 1 | `kngako/lightning-kmp-app` | `master` | catalogue → 9.5.1 | catalogue → 2.4.21, and the pointer |
| 0 | Giza | `giza` | `agp` → 9.5.1 | `kotlin` → 2.4.21; `engine-interop/new` too |

In each repository that changes a version, commit AGP and Kotlin separately, so either can be
reverted alone. Fast-forward each tracked branch and **push before the level above moves its
pointer**. 546095b0 left two pointers at unpushed commits, and a fresh clone could not resolve
them.

Giza gets two commits as well: Phase 5 (AGP, with `lightning-kmp-app`'s pointer at its AGP
commit) and Phase 6 (Kotlin, with the pointer at its tip).

If wallet-removal Phase 7 has landed by now, the table is two rows, secp256k1-kmp and
bitcoin-kmp (D7).

**Phase 5 checks — AGP 9.5.1:**
- The submodules: `secp256k1-kmp`'s `:tests:jvmTest`, `bitcoin-kmp`'s `jvmTest`, and
  `lightning-kmp-app`'s `:library:compileKotlinJvm` and `:library:compileAndroidMain`. These
  are the same checks submodule-refresh.md made at each level.
- Giza: C4, then `assembleDebug` and `assembleRelease`. `mergeDexRelease` is where duplicate
  classes have failed before (the SQLDelight comment in `composeApp`).
- Read the merged manifest, and diff it against Phase 0's.
- Every flag under `#Android` in `gradle.properties` must still be accepted. Several of them
  (`builtInKotlin=false`, `newDsl=false`, the r8 settings) are AGP 9's opt-outs back to AGP 8
  behaviour. Record any that 9.5 now warns about, because the next AGP major removes them (see
  [Out of scope](#out-of-scope)).
- Android Studio syncs the project. That sync is what failed in September.

**Phase 6 checks — Kotlin 2.4.21:**
- The same submodule checks, then C4.
- The Phase 0 dump should no longer show stdlib resolving above the declared Kotlin. The skew
  [quartz-mls-marmot-update.md](./quartz-mls-marmot-update.md) recorded (declared 2.4.10,
  resolved 2.4.21) is gone.
- `docs/tools/engine-interop/new` builds, and its test passes.
- **Leave on a mac:** link a release iOS framework *without* the
  `-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase` workaround. If it links,
  remove the workaround and its comment in a commit of their own. If it does not, keep the
  workaround, and change the comment's "Kotlin 2.4.10" to the versions it has been seen on.

## Phase 7 — the notes that still name the old versions

**Half a day. One commit.**

```bash
git grep -nE '2\.4\.10|9\.4\.0|1\.11\.1|1\.10\.0-alpha05|9\.7\.1|room3 3\.0\.1' -- ':!docs/retired' ':!docs/tools/engine-interop/old' ':!docs/tools/old-state-blob'
```

Each hit is one of three things:
- **A comment that states the current version.** Update it. Examples are the `material3Adaptive`
  comment in the catalogue, the Kotlin/Native comment in `composeApp`, and the "Kotlin 2.4.10"
  line in quartz-mls-marmot-update.md's transitive table.
- **A record of what was true then.** Leave it. A phase's result in another plan is history, and
  rewriting it would make that plan wrong about its own past.
- **A table that claims to be current.** Update it, or add a *Since* line the way the wallet
  removal did, for example jvm-target.md's dependency table.

Add a row for this plan to [README.md](./README.md), and fill this plan's [status
table](#where-the-build-stands) with each phase's result.

No new audit. The one guard this plan could want, AGP agreeing across the composite, Gradle
already enforces by refusing to configure (C2).

## Phase 8 — what Linux cannot check

These are the checks every earlier phase deferred. They can share one session with
quartz-mls-marmot-update.md's Phase 6 mac half and Phase 8, since all three need a mac and
devices.

1. **Android, over the current build.** Install a Phase 7 debug build over a build from
   `390d4bc3`, on a device that has a recovery-phrase profile, an nsec profile and an npub
   profile, and is in at least one group. Every profile opens. Every group opens, and sends and
   receives. This is C1: DataStore 1.3.0, Room 3.0.3 and SQLite 2.7.1 on real files.
2. **Desktop, on an existing data directory.** The same profiles and groups open. The relays
   connect (ktor). Avatars load (coil). The window resizes across the three breakpoints.
3. **iOS on a mac.** A debug build and a release framework link (C5, Phase 6). The app runs on
   a device. The keychain-held profiles open.
4. **QR codes** from both platforms scan on the other (2d).
5. **A release APK** installs, and runs the same as debug.

Until these pass, the status line says "built but for a mac and devices", the way the other
plans in this folder do.

## Risks, and what each one costs

| risk | where it shows | cost if missed |
|---|---|---|
| DataStore 1.3.0 reads a 1.2.1 preferences file differently | Phase 8.1 | profiles fail to open on update. High, which is why it is C1 and not a footnote |
| A material3 default changed between alphas: padding, shape, colour role | Phase 3.5 screenshots | visual regressions. Low cost, and caught by looking |
| A renamed adaptive API drops the `m3Audit` floor | Phase 3.3 | `check` fails. That is the guard working |
| Compose Resources changes its escape handling | `StringCatalogueJvmTest` | strings render with a stray backslash or a doubled `%`, a visible bug on every screen using that string |
| A submodule push is forgotten | a fresh clone fails to resolve the pointer | broken clones for everyone else. Push before the pointer moves, every level |
| The Kotlin/Native workaround is still needed at 2.4.21 | Phase 6, on a mac | none, if the rule is followed: keep it unless a link without it passes |
| ktor 3.6 changes websocket behaviour under the relay pool | `jvmTest`, then Phase 8.2 | relays that connect and then go quiet. Medium, and it looks like a network problem |

## Out of scope

- **AGP 10 and the KMP application module.** The AGP 9 opt-out flags in `gradle.properties`
  (`android.builtInKotlin=false`, `android.newDsl=false`, and the others) exist because
  `composeApp` applies `com.android.application` beside the Kotlin Multiplatform plugin. AGP's
  next major is expected to drop those opt-outs. That would mean splitting the Android entry
  point into its own module, with `composeApp` as a `com.android.kotlin.multiplatform.library`.
  It is a restructure, not an update. It needs its own plan, and no AGP 10 exists yet: the
  newest is 9.6.0-alpha04.
- **Kotlin 2.5.0.** It is RC as of 2026-11-11. Once it is stable it takes the same route as
  Phase 6, as its own change.
- **The Magpie four.** These are quartz, marmot-kmp, mls-kmp and crypto-kmp, under the rules of
  [quartz-mls-marmot-update.md](./quartz-mls-marmot-update.md): marmot's pins first, each step
  past them its own commit, and lock lines for every target.
- **The composite's own libraries.** `lightning-kmp` asks for ktor 3.4.1, coroutines 1.10.2 and
  serialization 1.9.0, and `lightning-kmp-app` asks for okio 3.17.0. Gradle resolves the highest
  version in Giza's graph, so Giza's catalogue already decides them. Only the plugins have to
  agree (C2, C3).
- **SQLDelight and `lightning-kmp-core`.** These go with wallet-removal Phase 7 (D5).
- **Material icons.** 1.7.3 is the last version published, and no newer artifact exists in
  either JetBrains' line or Google's. Moving to vendored icons or Material Symbols is a design
  change, not a version bump.
- **Re-enabling the configuration cache.** It is tied to secp256k1-kmp's two tasks, not to any
  version here.

## Where the numbers come from

- **Newest versions:** each coordinate's `maven-metadata.xml`, on 2026-11-20, read from:
  - Google Maven (`dl.google.com/android/maven2`);
  - Maven Central (`repo1.maven.org/maven2`);
  - the Gradle Plugin Portal (`plugins.gradle.org/m2`);
  - and, for Gradle, `services.gradle.org/versions/current`.

  "Newest stable" excludes alpha, beta, rc, RC and dev versions.
- **Pairings and stdlib floors:** each target's `.pom`. For kotlinx-serialization and okio,
  which declare stdlib only in Gradle module metadata, the `.module` file's `requires`.
- **The composite's pins:** `lightning-kmp-app` at `3794eb8` and the submodules it pins, read
  in the main checkout.
- **The AGP rule:** 546095b0, "build: unify the composite on AGP 9.4.0 so Android Studio can
  sync". The error text is quoted in its message.
- **The last time these moved:** 086b2f4e (2026-08-15, Kotlin, Compose, Room, ktor and others),
  546095b0 (2026-09-04, AGP), and 4e939c45 (2026-09-04, Gradle 9.7.1, carried up from the
  chain).
