# Taking the Bark wallet out

The profile's Ark wallet ([bark-wallet.md](./bark-wallet.md)) comes out of the app: its screens, its
background job, Second's SDK on every platform, its test network and its build wiring.

**Built, phases 0 and 2 to 8, one commit each, and merged into `dedun`, not yet released.** Written on 5 November 2026 against `56031156` (`dedun`), with the
`lightning-kmp-app` submodule at `949bfcb9`, which this plan did not touch. Files and lines named below
are as they stood at that commit. What is checked and what is not:
- Android and Linux desktop compile, the desktop package builds, the JVM suite passes (1,930 tests, 6
  skipped, down from 2,017 and 11), and the audit's budgets and floors are met. Every test that went is
  in a deleted Ark class, apart from the one forget-guard test Phase 4 removed on purpose.
- Nothing names Bark outside `docs/`. Neither classpath has `tech.second.bark` or JNA, and the debug APK
  has neither library.
- The packaged desktop app starts against a scratch data directory and runs for 30 seconds with no
  exception in its log. No screen was driven.
- **Not checked:** the app on Android, including an upgrade over a build that had the wallet on; any
  screen by hand; iOS, whose project and Swift have not been built because this host is Linux; test
  devices (Phase 0).

The phases are kept as written; the table below says where the build chose differently.

| phase | what the plan said | what it turned out to be |
|---|---|---|
| 0 | `git submodule update --init` | it needs `--recursive`, and `ANDROID_HOME` for the nested Android builds. In a worktree of this checkout the submodule's URL is a local path, so git also had to be allowed the file transport for that one command: `git -c protocol.file.allow=always submodule update --init --recursive` |
| 3 | `adaptive APIs in use` lands on the floor | it did: 12 against a floor of 12. `string literals` fell from 21 to 20 |
| 4 | the guard's string goes in Phase 6, if Phase 4 leaves it | it went in Phase 4 with its last reader |
| 7 | the APK is about 8 MB per ABI smaller | about 14 MB per ABI: 106,629,985 bytes down to 50,476,926 across four ABIs. The 8 MB was an estimate from the compressed AAR |
| 7 | check that nothing else leans on Bark's JNA | nothing does. On the desktop classpath only `bark-jvm`, its JNA and one edge to coroutines went. WorkManager still resolves on Android, at 2.11.1, through the library |
| 8 | take the network down where it is running | it was: left by the integration session, three containers exited and Core Lightning restart-looping (6,790 restarts). `ark.sh down` removed it and its volumes |

`ChatPaneSelectionJvmTest` failed twice in seven full runs, in two of its three tests, and passed every
time alone. Its `open()` helper clicks a room's row without waiting for the list to load, so it can
lose that race under a full run's load. The removal did not cause it, and the helper now waits for the
row. A 500 ms delay on the room list failed all three tests before that and none after, and three more
full runs passed.

The headline: **the code comes out cleanly, and the money was the hard part.** The port kept Bark behind
one adapter, so 52 files can be deleted and only 14 surviving source and test files need an edit. But
the wallet has been on mainnet since 2 November, and the obvious first step, setting
`ArkWalletRollout.ENABLED = false`, would strand any money in it (see *What makes this more than a
delete*). So one question decided the shape of this plan:

> **Has any build from 2 November onward held real sats, on any device?**

**No, as decided on 5 November 2026.** So the phases are 0 and 2 to 8, in one release, with no wind-down,
nothing left behind for an nsec's words and no job cancellation. Phases 1 and 9 exist only for the other
answer, and are in the [appendix](#appendix-if-a-build-did-hold-money).

The repository agrees with that answer but cannot prove it. Every Bark commit is dated 2 November. The
last version bump (`dddfa68b`, `versionCode` 9) is from 20 October. There are no tags and no CI, and
`bark-wallet.md` says the app has not been run on a device and no mainnet payment has been made. **If a
build with money turns up before a release carries Phase 5, stop and use the appendix.**

---

## What is there today

### Deleted whole

| where | what | files |
|---|---|---|
| `composeApp/src/commonMain/.../wallet/ark/` | the port, types, gate, holder, seed store and engine bridge | 15 |
| `.../ui/composable/Ark*`, `.../ui/view/model/Ark*`, `.../routes/ArkWalletRoute.kt` | seven screen files, four view models, six routes in one file | 12 |
| `composeApp/src/barkMain/` | `BarkArkWallet.kt`, the only file that names `uniffi.bark` | 1 |
| `androidMain/.../wallet/ark/` | `ArkMaintenanceWorker.kt`, `ArkPlatform.android.kt` | 2 |
| `iosMain/.../wallet/ark/`, `jvmMain/.../wallet/ark/` | the two other `ArkPlatform` actuals | 2 |
| `commonTest/.../wallet/ark/`, `jvmTest/**/*Ark*` | the tests and `FakeArkWallet` | 19 |
| `iosApp/iosApp/BarkEngine.swift` | the Swift half | 1 |

That is 52 files and about 6,400 lines. Also deleted:
- `testing/regtest/ark/` (3 files) and `docs/scripts/bark-natives.sh`;
- 135 `ark_*` strings in `strings.xml`, plus `could_not_forget_the_key_it_has_a_wallet`, the only other
  string the Bark commits added.

### Edited

| file | lines | what goes |
|---|---|---|
| `ui/composable/navigation/DedunNavHost.kt` | 196-212, 340-364, 872, 1241, 1631, 1244-1297 | imports, holder and effects, three `identityArk` arguments, six destinations |
| `ui/composable/ActiveProfileScreen.kt` | 92-94, 121, 163-171, 314-320 | the parameter, the view model and consent sheet, the *Wallet* row |
| `ui/composable/GroupLightningReceiveScreen.kt` | 81, 110, 258 | *Pay from my wallet* |
| `ui/composable/GroupPayoutDestinationScreen.kt` | 22, 114, 210 | *Use my wallet's address* |
| `ui/composable/RecoveryPhraseScreen.kt` | 64, 163-170 | the sentence saying these words also restore the wallet |
| `identity/ForgetIdentity.kt` | 33-37, 45-46 | `Outcome.ArkWalletAttached` and the `hasArkWallet` guard |
| `ui/view/model/NostrSecretViewModel.kt` | 3, 186-189, 194-196 | the guard's caller |
| `ui/view/state/NostrSecretUIState.kt`, `ui/composable/NostrSecretScreen.kt` | 36; 77, 382-383 | the refusal's state and text |
| `ui/view/model/SignOutViewModel.kt` | 80 | a branch that can no longer be reached |
| `jvmTest/.../identity/ForgetIdentityJvmTest.kt` | 55, 61, 91-93 | the guard's test |
| `androidMain/.../extensions/Phoenix.android.kt` | 4, 25-26 | scheduling the worker |
| `composeApp/build.gradle.kts` | 72-81, 165-173, 349-352 | bark-android, JNA, WorkManager, bark-jvm, the source dir, three test inputs |
| `settings.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore` | 28-33; 3-4, 45-46; 23-25 | the GitLab registry, the version and two libraries, the natives' ignore rules |
| `iosApp/iosApp/iOSApp.swift`, `iosApp/iosApp.xcodeproj/project.pbxproj` | 2, 6-11; 10, 48, 93, 124, 382-396 | the registration, the Swift package |
| `docs/README.md`, `docs/bark-wallet.md`, `testing/regtest/README.md` | 47, 169-174; status; 102-136 | see Phase 8 |

### Staying, though the wallet uses it

- `AppLifecycle` (`SynchronizationViewModel` reads it) and `AppVersion` (`IdentityWriter` reads it).
- `approximateDuration`, `grouped`, and the shared strings the Ark screens read, such as `wallet`,
  `send`, `receive`, `copy` and `s_sats`. Each of these strings has a reader outside the Ark files.
- `a92513ae`, the QR logo's colour exemption. It sits between Bark commits but is unrelated.

---

## What makes this more than a delete

1. **`ENABLED = false` does not retire the wallet. It locks people out of it.** It is the first check
   in `ArkSetup.resolve` (`ArkConfig.kt:55`), so nothing opens. The row in `ArkWalletItems.kt` becomes
   text with no action, so Send, Advanced and the phrase can't be reached. The Android worker's
   `maintainInBackground` finds no wallet, so coins stop being renewed, and after 28 days the server
   can sweep them. With no money anywhere this costs nothing, but it is why no phase here uses the flag
   as a stepping stone, and why the appendix doesn't either.
2. **Once the library is gone, nothing can tell whether a wallet is empty.** Balance and exits live in
   Bark's own database, with no Room mirror (`bark-wallet.md`, D9). So a test device has to be checked
   in Phase 0, while it still has Bark.
3. **An nsec profile's words exist only in `node-data/ark-seeds.dat`.** Its only screen is reached from
   `ArkAdvancedLoaded`, so it needs a wallet that is open. Removing that screen removes the only way to
   read them, which is fine only because those wallets are empty.
4. **The forget guard goes with the wallet.** Today an nsec that ever turned the wallet on can never be
   forgotten: the guard is `ArkSeedStore.has(id)`, which stays true however empty the wallet is, and the
   store has no `remove`. Phase 4 takes the guard out, and that restores forget for those profiles.
5. **WorkManager outlives the class.** `ArkMaintenanceWorker.schedule` enqueues a unique periodic job,
   and WorkManager keeps that row across an upgrade. After the class is deleted, the row names something
   that doesn't exist. Only a test device can have one, and a reinstall clears it.
6. **A plan on another branch depends on all this.** `docs/group-ark-wallet.md`, uncommitted in the
   `group-bark-wallet-plan-81b489` worktree, says of `bark-wallet.md`: "Everything here reuses it."
   That covers the port, the adapter, delegated renewal and the regtest network.
7. **iOS can't be checked from Linux.** Its Kotlin actual, Swift half and project edit have never been
   compiled (`bark-wallet.md`, Phase 8 row), and the removal has to be built on a Mac to know the
   project still opens.
8. **The audit's floor gets tight.** `adaptive APIs in use` is 14 against a floor of 12, and
   `ArkWalletScreen.kt` holds two of them. Removal lands exactly on the floor. That passes, so leave
   the floor alone.

---

## Decisions

**D1. Delete, don't hide.** No flag and no dormant code: a flag keeps the SDK, its 8 MB per ABI and its
registry in the build, and locks people out as described above.

**D2. Forward commits, not `git revert`.** The Bark work is 18 commits with a merge of `dedun` in the
middle, and later commits touched the same screens.

**D3. No cleanup code.** The app gains nothing that deletes `files/ark/` or `ark-seeds.dat`. A file left
on a test device is harmless and goes with a reinstall, and code that deletes a wallet's files is the
one thing here that could lose money if the answer at the top were wrong.

**D4. The note stays, with a new status.** `bark-wallet.md` holds spikes and findings that were
expensive to get (uniffi and JNA packaging, 16 KB pages, delegated renewal), and it remains useful if
Ark ever returns. Its status paragraph says the wallet was removed and names the last commit with it.

**D5. Nothing replaces it, and the Phoenix node is left alone.** The profile goes back to having no
personal wallet screen. The node keeps starting as it does today (`bark-wallet.md`, D11).

**D6. One commit per phase, each one compiling and passing.** The same habit as the rest of `docs/`:
consumers go before what they consume, and dependencies go last. Commit messages end
`(Bark removal, Phase N)`.

---

## Phases

Phases 2 to 8 ship together. The numbers skip 1 because that phase is in the appendix.

### Phase 0: decide and measure

No app code changes.

1. Which path: decided, at the top of this note.
2. **Check any device that turned the wallet on**, while it still has Bark. Empty means a balance of zero
   on the wallet screen, onchain part included, and no exit under way on Advanced. Then reinstall the app
   after Phase 5, or the old job's row stays behind. A device with money sends this plan to the appendix.
3. Decide what happens to `group-ark-wallet.md`. If a group Ark wallet is still wanted soon, removal may
   be the wrong move: pausing it would be cheaper than rebuilding the adapter, registry and regtest
   network. If it isn't, mark that plan shelved.
4. Record baselines to compare against in Phase 7 and in the final check. The nested submodules' own
   Android builds need `ANDROID_HOME`, because `local.properties` at the root does not reach them:

   ```bash
   git submodule update --init --recursive
   ```

   ```bash
   ./gradlew :composeApp:dependencies --configuration jvmRuntimeClasspath > /tmp/jvm-before.txt
   ```

   ```bash
   ./gradlew :composeApp:assembleDebug :composeApp:jvmTest :composeApp:m3Audit
   ```

   Keep the APK size, the test count and the audit's output.

**Done when** test devices are checked, the group plan's fate is decided, and the baselines exist.

Where it stands, 5 November 2026:

| item | state |
|---|---|
| baselines at `56031156` | 2,017 tests, 11 of them skipped, none failing. Debug APK 106,629,985 bytes. All audit budgets met, `adaptive APIs in use` 14, `string literals` 21. On the desktop classpath JNA comes only through `bark-jvm` |
| the group plan | execution was asked for with the question open, so the removal goes ahead. `group-ark-wallet.md` has not been touched, and it needs marking shelved or rewriting in its own worktree |
| test devices | **not checked.** This can only be done on the devices, and has to happen before a build from this branch is installed on them, while they still have Bark |

### Phase 2: the group screens let go

- Remove the `identityArk` parameter and the bridge button from `GroupLightningReceiveScreen` and
  `GroupPayoutDestinationScreen`, and their arguments in `DedunNavHost` (872, 1631).
- Delete `ArkGroupBridgeViews.kt`, `ArkGroupBridge.kt` and `ArkGroupBridgeJvmTest.kt`. Nothing else reads
  them.

Both parameters are already nullable, and both screens work without them, so this should change nothing
else in those flows.

**Done when** no `Group*` file imports `wallet.ark`, and the deposit and payout tests pass unchanged.

### Phase 3: the profile and the screens

- `ActiveProfileScreen`: remove the parameter, the view model, the consent sheet and the row. The
  *Wallet* heading goes with `arkWalletItems`; `memberWalletItems` keeps its own place.
- `DedunNavHost`: remove the argument at 1241 and the six destinations at 1244-1297. `identityArk` stays
  for one more phase, with `arkSeeds`.
- `RecoveryPhraseScreen`: remove the sentence.
- Delete the remaining screens, the four view models, `ArkWalletRoute.kt` and their six test files.

**Done when** the app builds and shows no Ark surface, and `:composeApp:m3Audit` passes with
`adaptive APIs in use` at 12 or more.

### Phase 4: forget

Remove `hasArkWallet` and `Outcome.ArkWalletAttached` from `ForgetIdentity`, the guard from
`NostrSecretViewModel`, the `Forgetting.ArkWalletAttached` state, its branch in `NostrSecretScreen`, the
branch in `SignOutViewModel`, the string and the test.

**Done when** an nsec profile can be forgotten with no wallet code involved, and `ForgetIdentityJvmTest`
passes.

### Phase 5: the background job and the holder

- `Phoenix.android.kt`: stop scheduling the worker.
- Delete `ArkMaintenanceWorker.kt`.
- `DedunNavHost`: remove `identityArk`, `arkSeeds` and both `LaunchedEffect`s (340-364), then whatever
  imports that leaves unused. `AppLifecycle` itself stays.

From this commit on, the app makes no connection to `ark.second.tech` or `mempool.second.tech`. That
reverses `bark-wallet.md`, D10.

**Done when** nothing outside `wallet/ark/` names it.

### Phase 6: the port, the adapter and the iOS half

One commit, because `ArkEngineRegistry` is called from Swift and the two sides have to go together.

- Delete what is left of `wallet/ark/`, the three `ArkPlatform` actuals and `composeApp/src/barkMain/`.
- Delete the remaining tests in `commonTest` and `jvmTest`, including the gated
  `ArkSignetJvmTest`, `ArkRegtestJvmTest` and `ArkMainnetJvmTest`.
- Delete every `ark_*` string, and `could_not_forget_the_key_it_has_a_wallet` if Phase 4 left it.
- iOS: delete `BarkEngine.swift`. In `iOSApp.swift` remove `init()` and `import ComposeApp`, both added
  by the Bark work; `ContentView.swift` has its own import. Restore the project file from before Bark.
  No commit has touched it since `65bcbee3`, so that is exact:

  ```bash
  git checkout 65bcbee3^ -- iosApp/iosApp.xcodeproj/project.pbxproj
  ```

In `build.gradle.kts`, only `srcDir(barkMain)` and its comment go here, with the directory. The
dependencies wait for Phase 7.

**Done when** Android and JVM compile and `jvmTest` passes, with the count down by the deleted tests
and no others. iOS is checked in the final check.

### Phase 7: the build

- `composeApp/build.gradle.kts`: remove `bark.android`, the `jna:5.19.1@aar` line, `work-runtime-ktx`,
  `bark.jvm` and the three `dedunArk*` test inputs. The library declares its own WorkManager as
  `implementation`, so its channels watcher doesn't rely on the line going here.
- `gradle/libs.versions.toml`: the `bark` version and both libraries.
- `settings.gradle.kts`: the GitLab registry. Nothing else may resolve from it.
- `.gitignore`: the natives' two lines. Delete any built copies under `composeApp/src/jvmMain/resources/`.
- No R8 rules to remove: release builds aren't minified.

**Check that nothing else was leaning on Bark's JNA.** Compare the dependency tree with Phase 0's. If
something else asks for `net.java.dev.jna`, confirm it still resolves and runs on the desktop target.

**Done when** the tree has no `tech.second.bark`, the debug APK has no `libbark_ffi_kotlin.so` or
`libjnidispatch.so`, and it is about 8 MB per ABI smaller than the baseline.

### Phase 8: the harness and the docs

- On any host where it is running, take the network down first. Its volumes aren't otherwise removed:

  ```bash
  testing/regtest/ark/ark.sh down
  ```

- Delete `testing/regtest/ark/` and `docs/scripts/bark-natives.sh`, and remove *The profile's Ark
  wallet* from `testing/regtest/README.md` (102-136). The group network shares nothing with it:
  `regtest.sh` and `compose.yml` don't refer to it.
- `docs/bark-wallet.md`: a new status paragraph (D4). The phases stay as written.
- `docs/README.md`: rewrite the row at line 47 and the paragraph at 169-174 to say *removed*.
- This note: mark the phases done, and add a table of where the build chose differently, as the others
  do.

`CLAUDE.md` doesn't mention Bark and needs nothing.

**Done when** the final check below passes.

---

## The final check

Before the release that carries these phases, on a clean checkout:

```bash
git submodule update --init --recursive
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest :composeApp:m3Audit
```

```bash
./gradlew :composeApp:packageDeb
```

Nothing should remain but the docs:

```bash
git grep -il -E '\bbark\b|barkMain|wallet\.ark|DEDUN_ARK|ark_wallet|ArkEngine' -- . ':!docs/bark-wallet.md' ':!docs/bark-wallet-removal.md' ':!docs/README.md'
```

The group Lightning regtest run isn't needed: nothing here touches the node, co-signing or sweeps, and
that network never depended on the Ark one.

By hand, because no test covers these:

| where | what to see |
|---|---|
| Android, a fresh install | starts, no Ark row, and no `libbark_ffi_kotlin.so` in the APK |
| Android, **an upgrade** over a build that had the wallet on | starts and doesn't crash, whatever the leftover job does. Then reinstall |
| Android and Linux desktop, a seed profile | profile tab, recovery phrase, switching profile |
| an nsec profile | forget works |
| a group | a deposit invoice with no *Pay from my wallet*; a payout address form with no wallet button |
| macOS, in Xcode | the project opens, resolves no Bark package, builds and starts. This is the only check of the iOS half |

## Rollback

Until a release carries Phase 5, every phase is an ordinary revert. Nothing here deletes user data (D3),
so a revert before release brings back everything as it was.

## What this plan does not do

- **Replace the wallet.** The profile has no personal wallet afterwards.
- **Touch the Phoenix node** or answer whether a profile needs it (`bark-wallet.md`, open question 1).
- **Change the group wallets**, beyond taking the two buttons off their screens.
- **Fix Android's backup rules** generally, though the absence of any is worth its own look: the manifest
  has `allowBackup="true"` and no rules, so `seed.dat` and the rest of `files/` are all in the same position.
- **Edit `group-ark-wallet.md`**, which belongs to its own worktree. Phase 0 only decides its fate.

## Open questions

1. What becomes of `group-ark-wallet.md`? This work went ahead, so that plan now stands on code that is
   only in history at `56031156`. Shelve it, or rewrite it to bring that code back.

---

## Appendix: if a build did hold money

Not the path taken. It is kept because a wrong *no* can cost someone their money, and this is the part
that would have to be designed again in a hurry. The order becomes: Phase 0, then Phase 1 as its own
release and a wait of at least 30 days, then Phases 2 to 8 with the changes below as the next release,
then Phase 9.

### What else matters when money is there

- **The ways out are uneven.** `ArkSendViewModel` refuses a send above the 100,000 sat cap, but
  `offboardAll` has no cap, so *Move everything onchain* always applies. The port has **no onchain
  send**: Bark's onchain coins leave only through `boardAll` and then an offboard, or by the words in
  a BIP86 wallet (`m/86'/0'/0'`). Coins below the server's minimum board (10,000 sat on signet, not
  recorded for mainnet) can leave only by the words.
- **An exit under way can't be restored from the words.** It lives in the database. It waits at least
  144 blocks on mainnet, about a day, then needs a claim in the app.
- **The nsec phrase screen needs an open wallet**: not with Tor on, and not after a failed open.

**The rule that replaces D3:** code that can lose money never runs automatically. Nothing deletes a
datadir or words unless a person pressed a button in a build that had just confirmed the wallet was empty.

### Phase 1: the wind-down release

The app still has Bark, and the wallet can only be emptied. This is not `ENABLED = false`.

- **Gate.** `ArkWalletRollout.WINDING_DOWN = true`. In `ArkSetup.resolve`, a profile that has not
  consented gets a new `ArkUnavailability.WoundDown`, and its row is not shown. No new consent and no
  new nsec words. A consented profile opens as it does today.
- **Renewal keeps running**: the daemon, foreground maintenance and the Android worker, until the
  wallet is removed. Otherwise coins expire during the wait.
- **Receiving stops.** No *Receive* button. The two group bridges stop offering themselves
  (`ArkGroupBridge.offered` returns false).
- **The way out goes first.** A notice on the wallet screen says the wallet is closing, and by when.
  *Move everything onchain* moves from Advanced to the wallet screen. *Move onto Ark* stays, because it
  is the only way the onchain side can leave, and its text says so. Send stays.
- **The words can always be reached.** For an nsec with words, the phrase screen opens from the profile
  row whatever state the wallet is in, including Tor on and a failed open. A Tor-on row gains a
  sentence: turn Tor off to move this money.
- **Removing an empty wallet.** New: *Remove this wallet* shows only when every field of `ArkBalance`
  is zero (`onchain` included), `progressExits()` is empty, and no movement is pending. It confirms,
  closes the wallet, deletes the datadir and consent file, and for an nsec shows the words one last time
  before deleting them through a new `ArkSeedStore.remove`. This also unblocks forget, which nothing
  currently can.
- **Platforms.** Android and Linux desktop are what has been tested. iOS, macOS and Windows have never
  run the wallet, so they need no wind-down unless someone built them with it.

Tests:
- `ArkSetupTest`: the new gate, for consented and unconsented profiles, each with and without words.
- View model tests over `FakeArkWallet`: no receive; the remove button across every non-zero balance
  field, an exit under way and a pending movement.
- `ArkSeedStoreJvmTest`: `remove` keeps other profiles' words.
- `ArkRegtestJvmTest`: board, offboard all, remove; and an exit, claim, remove.

Then ship it and wait. **The wait is at least 30 days**, longer than one 28-day coin lifetime, so that
someone who opens the app monthly sees the notice while their coins can still be renewed. There is no
push channel, so the notice reaches only people who open the app, and anyone reachable another way
should be told that way too.

**Done when** the release is out, the wait has passed, and everyone you can reach has been told.

### What changes in Phases 3 to 7

- **Phase 3.** Keep the `RecoveryPhraseScreen` sentence for one release, reworded for someone restoring
  in another wallet, since it's the only pointer left for a seed profile with coins. Keep
  `ArkRecoveryPhraseScreen` and its route for Phase 4.
- **Phase 4.** Keep what protects words nothing else holds, and nothing more:
  - Move `ArkSeedStore` to `identity/`, with `words`, `has` and `remove`. It imports no Bark code, only
    bitcoin-kmp, the library's keystore and okio. Drop `create`.
  - Keep `ArkRecoveryPhraseScreen`, which imports only shared widgets and the theme.
  - Add a row on the profile, shown only when `has(id)`: *Words from your old wallet*. The screen has a
    confirmed *I've saved these, remove them*, which calls `remove`. It says plainly that the wallet is
    no longer in the app, and that these words restore its money in another Ark wallet while the server
    still holds it.
  - Keep the forget guard, with its string reworded to point at this row.
- **Phase 5.** Put an explicit cancel where the scheduling was, with the name written out because the
  class is going. Check that an upgrade from the Phase 1 build leaves no such job
  (`adb shell dumpsys jobscheduler`):

  ```kotlin
  // The Ark wallet's job, removed with it: docs/bark-wallet-removal.md, Phase 5.
  WorkManager.getInstance(context).cancelUniqueWork("com.machankura.dedun.compose.wallet.ark.ArkMaintenanceWorker")
  ```

- **Phase 6.** Keep the strings the Phase 4 row reads.
- **Phase 7.** Keep `work-runtime-ktx` until Phase 9, for the cancel.

### Phase 9: the last of it

At least one release after Phase 8, and only after an announced date:
- Remove the WorkManager cancel and `work-runtime-ktx`.
- Remove what Phase 4 left behind: the row, the screen, the route, the store, the guard and the strings.
- Leftover files: **delete nothing automatically.** People who pressed *Remove* have no files left.
  Whatever `files/ark/` and `node-data/ark-seeds.dat` remain belong to people who never acted, and with
  this code gone the app can't tell whether they hold money. They cost a little disk until the app is
  uninstalled, and on Android they stay in backups. If that cost ever matters, a cleanup is its own
  decision, taken knowing it is final.

**Done when** `git grep -il 'ark-seeds'` finds only docs.
