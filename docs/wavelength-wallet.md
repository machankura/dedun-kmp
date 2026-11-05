# A Wavelength wallet under the profile

The profile gets a wallet a person can see, on mainnet: a balance, a way to receive, a way to send, a
history and a way out, on the profile tab above the member wallets. It is an Ark wallet, run inside the
app's process by Lightning Labs' `waved` daemon, through the `wavelength-kmp` submodule at
`experimental/wavelength-kmp`.

**Planned, nothing built.** Written on 6 November 2026 against `22935627` (`dedun`), with `wavelength-kmp`
at `cb9474b0` (`descriptors`) and `lightning-kmp-app` at `949bfcb9`. Files and lines named below are as
they stood at those commits. What this rests on:
- **Read:** the SDK at that commit; `lightninglabs/wavelength` at `v0.2.1` and `main`; our fork
  `machankura/wavelength` at `63d545fb` (`descriptors`); the Bark wallet at `56031156`.
- **Probed, read-only, on 6 November:** `wavelength.lightning.finance:443` and
  `swap.wavelength.lightning.finance:443` resolve and complete a TLS handshake offering HTTP/2, with
  certificates for `lumosd.prod` and `swapd.prod.lightningcluster.com`. The daemon's mainnet Esplora default
  answered with a tip height. No wallet was opened and no RPC was made.
- **Not known:** whether the operator accepts a new wallet on mainnet, on what terms, and what its limits
  are. See *Is mainnet open?* below. Phase 0 answers this with an empty wallet before anything else is built.

Read [bark-wallet.md](./bark-wallet.md) first, for its reasoning about a profile's wallet, and
[bark-wallet-removal.md](./bark-wallet-removal.md), for why a rollout flag can strand money. Then read
[multiple-profiles.md](./multiple-profiles.md) and `experimental/wavelength-kmp/docs/api-guide.md`.

The headline: **the screens and the lifecycle are mostly a known shape, because the Bark wallet already had
them. The work is in four things Bark did not have.**

1. **The descriptor runtime is not released.** The SDK's `descriptors` branch calls
   `Mobile.startDescriptorWallet` (`AndroidFacadeBindings.kt:17`), which the pinned `v0.2.1` bindings do not
   contain. It lives only in our daemon fork, 109 commits ahead of and 50 behind upstream `main`, with no
   release. The submodule as committed cannot build against what its own pin downloads (D2, Phase 1).
2. **The obvious key scheme is wrong.** A descriptor wallet built from a profile's master key also watches
   and spends `m/84'/0'/0'`, which is the Phoenix node's final wallet, and the daemon would hold the root of
   the nostr and node keys. Account-level keys avoid both (D1).
3. **There is no desktop runtime.** `wavelength-embedded` has Android and iOS targets only. A desktop build
   says *Not available on this device* until upstream ships a C ABI (D4).
4. **The size.** `Wavewalletdk.aar` is 191 MB compressed, against a 50 MB debug APK today (D15).

---

## What the profile has today

| fact | where | what it means here |
|---|---|---|
| The profile has no personal wallet screen. Bark's was removed on 5 November, with no real sats held | [bark-wallet-removal.md](./bark-wallet-removal.md) | nothing to migrate. Its code is at `56031156` for the taking |
| A seed profile holds 12 BIP39 words with an empty passphrase. `Identity.business` is its Phoenix node | `StoredIdentity.Mnemonic`, `Identity.kt` | the words are the root of everything this plan derives |
| An nsec holds a key, an npub holds a public key | `StoredIdentity.kt` | Phase 13 and never, respectively (D13) |
| `IdentityElectrum` closes the last client before making the next | `wallet/IdentityElectrum.kt:28` | the shape for a per-profile resource with a singleton underneath |
| Gates are objects with a `MAINNET_CAP` of 100,000 sat | `wallet/GroupWalletRollout.kt` | the rollout object copies it |
| `allowBackup="true"`, with no rules | `androidMain/AndroidManifest.xml:11` | a wallet database in `filesDir` would be backed up. See D8 |
| Tor is a per-profile preference with no SOCKS proxy behind it | `UserPrefs.getIsTorEnabled` | D7 |
| Native code on Android arrives through the composite build. No splits, no ABI filters, no minification | `composeApp/build.gradle.kts:212-222` | D15 |
| `androidx.work` left `composeApp`'s own dependencies in the Bark removal; it still resolves through the library as `implementation` | removal note, Phase 7 | the worker has to add the line back (Phase 8) |
| No `androidInstrumentedTest` source set, and `jvmTest` cannot load a gomobile library | `composeApp/src/` | the real runtime can only be tested on an emulator, which is new for this module (Phase 10) |

## What Wavelength gives, and what it asks

From the SDK and from upstream's `docs/wavewalletdk_mobile.md`, `docs/seed_restore_recovery.md`,
`docs/credit_system.md` and `docs/daemon_cli_guide.md`:

| fact | consequence here |
|---|---|
| One daemon per process, serialised by a lifecycle lock in `Wavelength.client`. Stop before starting another directory | one holder, keyed by profile, shared by the UI and the background job (D8) |
| Core, engine and testing have `jvm`, Android and iOS targets. Only `wavelength-embedded` is native | the client interface and fakes live in `commonMain` and `jvmTest`. Bark's port is not needed (D3) |
| `startDescriptorWallet` boots and opens in one call, derives the database password from the keys, and needs the descriptors on **every** open | no password to manage. The words stay in memory while the profile is active, as they already do in `availableWallets` |
| Account-level keys: `tr([fp/86h/0h/0h]xprv/<0;1>/*)` and `[fp/1017h/0h]xprv`. BIP86 is required and `wpkh` is optional (`walletdesc/accountkeys.go`) | D1 |
| `expectedIdentityPubKey` pins a directory to an identity, and a mismatch fails | stored after the first open |
| `recoverState` is idempotent and safe to retry, and rebuilds boarding keys, VTXO keys and out-of-round receive keys from the chain and the operator's indexer | it runs on every first open of a directory, since the app can't tell a restore from a new wallet (D12) |
| "Running two daemons with the same seed at the same time can make responses arrive at the wrong process" | the same words active on two devices is a real failure. The app has to say so (D11) |
| The Go runtime can't see the app lifecycle. A suspended daemon keeps dead connections, so upstream recommends stop on background and restart on resume | `BackgroundRestartPolicy.reopening`. **Nothing is processed while the app is in the background** (D10) |
| Automatic VTXO maintenance waits for the operator's free window (`ServerInfo.freeRefreshWindowBlocks`) and accepts only zero-fee quotes. A paid refresh exists in the CLI and RPC | the SDK has **no refresh verb**: `FacadeVerb` lists none, so there is no *Renew now* yet (D10, Phase 0) |
| Send takes a BOLT11 invoice or an onchain address (`SendRequest`, `Destination.classify`). Receive takes an amount and gives an invoice; `deposit()` gives a boarding address | **narrower than Bark.** There is no Ark address send, no BOLT12, no LNURL and no amountless QR (Phases 6 and 7) |
| `Balance` has `confirmedSat`, `pendingInSat`, `pendingOutSat`, `creditAvailableSat` and `creditReservedSat` | **credit is not a coin.** It is a row in the swap server's ledger, used for amounts below the operator's VTXO floor, and it is custodial. It is shown apart and named as what it is (Phase 5) |
| `exit` (cooperative or forced), `exitBatch`, `getExitPlan`, `exitSummary`, `sweepWallet` | Phase 9 |
| Preimages are reported once, and the engine keeps them in memory only | with a restart on every foreground, proof of payment needs its own store (Phase 7) |
| Empty endpoints select the daemon's default per network, including mainnet. `allowMainnet` must be true | D5, D6 |
| The default mainnet Esplora is `https://mempool.staging.lightningcluster.com/api` | a *staging* host holding a mainnet wallet's view of the chain. Not shipped (D6) |
| `RuntimeConfig` has no proxy field | D7 |
| The README says the project is under active development, and that RPC surfaces, on-disk schema and commands may change across minor versions. The SDK calls itself alpha, and its iOS code has never been compiled | every daemon bump is a migration to test (Phase 1), and the cap stays (D5) |

### Is mainnet open?

The evidence disagrees, so nothing here assumes it. The daemon has mainnet hosts compiled in, and they answer
TLS. Upstream's `docs/signet.md` says mainnet "resolves to the public operator". Press coverage of the July
launch described mainnet as invitation-only, and one milestones page dates a mainnet opening to 23 October.
None of Lightning Labs' pages that could be read state an access rule, a balance cap, a mainnet VTXO lifetime,
a fee or a round cadence. Phase 0's first spike settles it.

---

## Decisions

**D1. A seed profile's own twelve words, as account-level keys.** The app derives two extended private keys
from the words and passes them through `startDescriptorWallet`:

| user of the seed | path (mainnet) |
|---|---|
| Phoenix node key / channel keys / eclair key | `m/50'/0'`, `m/50'/1'`, `m/47'/0'` |
| Phoenix final wallet | `m/84'/0'/0'` |
| Phoenix swap-in | `m/52'/0'/…` |
| cloud key | `m/51'/0'/0'/0` |
| nostr (NIP-06) | `m/44'/1237'/0'/0/0` |
| **Wavelength onchain (BIP86)** | **`m/86'/0'/0'`** |
| **Wavelength key families** | **`m/1017'/0'/family'`** |

No two collide. Nothing in `composeApp` or `lightning-kmp-app` derives under `m/1017'`, and member and group
wallets don't use the seed for `m/86'`. Bark used `m/86'/0'/0'`, on test devices, with no real sats.

The alternatives, and why not:
- **The master key, as `tr(xprv/86h/0h/0h/<0;1>/*)`.** `walletdesc.CanonicalPublic` says a wallet built from a
  root "watches and spends from" both BIP86 and BIP84, and BIP84 here is Phoenix's final wallet: two wallets
  handing out and spending the same addresses. The root would also sit in the daemon's database, and it
  derives the nostr key and the node key.
- **External seed: 16 bytes through `startExternalSeedWallet`.** It is in released `v0.2.1`, and it is the
  fallback in D2. Its cost is that it depends on a derivation of our own, with no spec (upstream U5), and the
  onchain side can't be swept by another wallet from a standard path.
- **A child seed for the master-key form.** It is isolated too, but a fresh root is more to explain and gives
  up the standard BIP86 path, for nothing the account keys don't already give.

**Consequence:** a compromised daemon can spend Wavelength's keys and nothing else on the profile, and the
recovery phrase now covers money held off the node, so its screen says so. The derivation is frozen once a
release holds money, like the group wallet's tags. A golden-vector test pins it.

A known wrinkle, and why it doesn't bite: btcwallet's legacy hardened derivation differs from BIP32 for about
one hardened step in 256, so a coin-level key can disagree with a seed wallet of the same words. Dedun has no
seed wallet of those words to agree with. It derives with bitcoin-kmp, always the same way, and the identity pin
catches any drift. What this does give up is that typing the same words into a stock Wavelength wallet is not
promised to find the same wallet. Words restore in Dedun, and the keys can be exported as descriptors.

**D2. Which daemon: a tagged release of our fork.** The descriptor verbs exist only in `machankura/wavelength`.
Money should not run on a locally built branch, so Phase 1 cuts a release: rebased on a released upstream tag,
with assets on the fork's releases and digests in `bindings.digests`. `fetch-bindings.sh` already takes
`WAVELENGTH_REPO`. If upstream merges the work first, the pin moves there and the fork goes.

**The fallback is D1's external seed on upstream `v0.2.1`,** on an SDK commit from before the descriptor work
(`33b076c`; the fork's remote has only the `descriptors` branch). It needs no fork, and the app changes only in
`WalletKeys` and the open call. **This has to be settled before any real money is held,** because it moves the
keys and so the coins. The recommendation is the fork, because the branch was built for this. The honest cost
is that we would own a security-critical daemon release, at a pace set by an upstream that moves fast.

**D3. No port.** Bark needed `ArkWallet` because iOS had no Kotlin binding and generated types had to stay in one
file. Here `WavelengthClient` is already a Kotlin interface in `commonMain` with typed models, and
`FakeWavelengthClient` is already its fake. The screens read `WalletEngine`'s `StateFlow<WalletSnapshot>`. The
only platform seam is:

```kotlin
expect object WavelengthPlatform {
    val client: EmbeddedWavelengthClient?   // null: this platform has no runtime
    fun dataDirFor(network: Network, walletId: WalletId): String?
}
```

**D4. Platforms.**
- **Android:** first, and what the mainnet flip is judged on.
- **iOS:** Phase 12, on a Mac. The SDK's iOS code has never been compiled, and neither was Bark's. Until then the
  actual returns null.
- **Desktop:** *Not available on this device*. Upstream has no C ABI (its U4). Running `waved` as a separate
  process over REST is a different design and isn't planned here. This is less than Bark, which ran on Linux.

The row says so in words. It does not hide.

**D5. Its own gate, and the gate never locks anyone out.** `WavelengthWalletRollout` holds `OFFERED`, `NETWORK`,
per-network endpoints and `MAINNET_CAP = Satoshi(100_000)`. `NETWORK` is signet until Phase 11's condition is met,
and `allowMainnet` is set only from this object.

The Bark removal's first finding becomes a rule here: **turning the gate off stops new consent, and a consented
profile always opens.** There is no `ENABLED` that strands money, so a wind-down is the flag and a release rather
than a rewrite. The cap bounds a send, and an invoice that would take the balance over it. It can't bound an onchain
deposit, so the wallet screen states it.

**D6. Endpoints are written down, and the chain view is not a staging host.** The rollout names the Ark and swap hosts
rather than leaving them to the daemon's defaults, so a pin bump can't move them silently. Esplora is set explicitly,
to one Phase 0 chooses. The recommendation is `https://mempool.space/api` unless `lwwallet`'s polling trips its limits,
then a Machankura-run instance. Lightning Labs sees the wallet's VTXOs, Lightning payments and the device's IP, and the
Esplora host sees its onchain addresses. The sheet says so.

**D7. Tor on means no wallet.** There is no proxy to give the daemon, and a profile with Tor on would connect over
clearnet against the user's choice. This is the same call as `bark-wallet.md` D6 and `standalone-electrum.md` D4.

**D8. One wallet per process, keyed by profile, in a directory that is not backed up.** The directory is
`<noBackupFilesDir>/wavelength/<network>/<walletId>/` on Android and Application Support, excluded from iCloud, on iOS.
Not `Wavelength.defaultDataDir`, which is `filesDir` and so inside `allowBackup="true"`: a database restored behind the
server's back is worse than none, and restoring it on a second device runs into D11.

`IdentityWavelength` copies `IdentityElectrum`'s order: it stops the last daemon before it opens the next, behind a
mutex. Held per process as `ArkProcess` was, because an Android activity is destroyed on rotation and the worker has no
composition.

**D9. Consent first, because this is a behaviour change.** Until the sheet is accepted nothing opens and no connection is
made. The sheet says what Ark is, that Lightning Labs runs the server, what it and the Esplora host see, that the app has
to be opened often enough to keep coins renewed, that the words restore the balance, not to use the same words on two
devices at once, and that the software is alpha with a cap. Consent is a file, `<dataDir>/../consented/<walletId>`, as Bark
found one boolean isn't worth a submodule commit.

**D10. Coins are kept alive by the daemon being up, so the app has to be up.** Three layers:
1. The daemon's automatic maintenance while the app is in the foreground.
2. On Android, a periodic worker that opens the default profile's wallet, gives maintenance time, then stops it. It goes
   through the same holder, and does nothing if the app is in the foreground.
3. The deadline stated in time on the wallet screen, from `WalletVTXO` and the tip, and a warning as it nears.

iOS and desktop have layers 1 and 3 only. **Bark's delegated renewal has no equivalent that this plan has found:** how long
a worker has to stay up for a round is unknown, and there is no *Renew now* because the SDK has no refresh verb. Spike 5
measures the first. If the free window is too short for a device that's opened weekly, a `refresh` verb goes into the SDK
and this decision is amended.

**D11. The same words on two devices is a stated limit.** The app can't detect it. The sheet, and a sentence on the
recovery-phrase screen, tell a person to turn the wallet off on one device first. Restoring on a new device means the old
one is gone or its app is closed.

**D12. Every first open of a directory recovers.** `recoverState = true` when the directory is new, because a restore and a
new wallet look the same from here. The birthday is a constant, the release that first holds money, since no key can have
received anything earlier. It bounds the rescan and is frozen. Recovery's result is shown, and a partial one is safe to
retry, so here, unlike Bark, the retry is real.

**D13. nsec profiles get words of their own, later. npub profiles never.** Deriving from the nsec would be simpler, with no
new file. But an nsec gets pasted into other nostr clients, and leaking one shouldn't empty a wallet. So Phase 13 brings back
`ArkSeedStore`'s design with its known flaw fixed: forget is refused while the wallet holds money or the words haven't been
confirmed saved, and not merely because the words exist. An npub can't sign, and a wallet whose words it holds would contradict
the npub plan.

**D14. The Phoenix node is left alone,** and it keeps starting. Whether a profile with a visible wallet needs an invisible node
is `bark-wallet.md`'s open question, and it stays open.

**D15. ABI splits ship with the runtime.** The AAR is 191 MB compressed across four ABIs, so about 48 MB per ABI compressed, and
libraries are stored uncompressed in an APK at this `minSdk`. Bark's estimate of 8 MB turned out to be 14 MB for the same reason.
Phase 0 measures this. Phase 2 adds `splits.abi` for direct APKs and an AAB for Play. x86 and armeabi-v7a are dropped from release
if the numbers argue for it. Upstream's U3 (slim AARs) would help the build, not the app.

**D16. One commit per phase, each one compiling and passing.** Messages end `(Wavelength wallet, Phase N)`. Strings go through
`strings.xml` in sentence case, with *onchain*, not *on-chain*; *Ark* is a proper noun; *VTXO* appears only on the advanced screen;
*refresh* is *renew*. Every screen follows `CLAUDE.md`.

---

## Phases

Phases 1 to 11 are in order. Phases 12 and 13 follow the mainnet release and can swap. Phase 0 and Phase 1 hold no app code.

### Phase 0: prove it before building it

Each answer is a short section in this note, in the commit that finishes the phase. Run from a throwaway module or
`wavelength-embedded`'s device tests, on a dedicated emulator: the ones running on the build host may belong to other work. **No
real money.** The bindings come from a local build of the fork:

```bash
WAVELENGTH_DIR=../wavelength ANDROID_HOME=$HOME/Android/Sdk experimental/wavelength-kmp/scripts/fetch-bindings.sh android
```

1. **Mainnet access. This one comes first, and can stop the plan.** Open an empty wallet with `allowMainnet`, the default endpoints
   and a throwaway key. Call `getInfo`, `deposit` and a 1,000 sat `receive`, then read `ServerInfo` and the operator's terms. Record
   any refusal word for word. If it is refused, ask Lightning Labs for access, and the plan waits on signet at Phase 10.
2. **Account keys on `lwwallet`.** Open with `tr` and the coin-level key only. Does anything ask for `wpkh`? Is the identity stable
   across reopen? Does a wiped directory recover a funded signet wallet? **Confirm no address under `m/84'` is ever generated.** Time
   the first open and the recovery. Record the golden vector: fixed words, the two keys, the identity, the fingerprint.
3. **The engine after an atomic start.** The sample drives the engine through `start`, and `startDescriptorWallet` bypasses it. Does
   `engine.start()` against the running daemon reach `READY`? If not, the SDK needs a small change, which goes in Phase 1.
4. **Size, memory and start.** APK and AAB per ABI against the 50,476,926-byte baseline. Cold start, and resident memory with Phoenix
   and the Go runtime in one process. Page alignment on a 16 KB emulator. Do the Go runtime and the rest of the app's native libraries
   coexist across a day's soak?
5. **Keeping coins alive.** On signet: what are the lifetime and the free window? Fund a wallet, close the app, and let a worker run. How
   long must the daemon stay up for a due coin to renew? Does that fit WorkManager's 10 minutes? Without a foreground service? What
   happens to a coin past expiry that the device opens late? This decides D10.
6. **Lightning with the app closed.** Issue an invoice, kill the process, pay it, reopen. Does the payment complete? How long does the
   payer wait? Same for a send interrupted by a kill.
7. **Foreground return.** `BackgroundRestartPolicy.reopening` costs a daemon boot every time the app returns. Measure it. The app opens
   often for chat, so if it is slow, stop only after a grace period. Does a stop during a pending send or receive lose anything?
8. **The composite build.** `includeBuild` of the submodule beside `lightning-kmp-app`, with AGP 9.1 against the app's 9.4 and the app's
   `android.newDsl=false`. Does `:composeApp:jvmJar` stay free of native code?
9. **Esplora.** Does `lwwallet` run on the chosen host at its default poll rate, for a day?

**Done when** this note carries the nine answers, and D1, D2, D6, D10 and D15 are amended where they differ. D2's choice is confirmed
by the owner here.

### Phase 1: a release to pin to

In `machankura/wavelength` and `wavelength-kmp`, then a submodule bump here.

- Rebase the fork's `descriptors` on the latest upstream tag. Run its suite, including `TestAccountKeysDaemon`, on the release build tags.
- Tag it, build `Wavewalletdk.aar` and `Wavewalletdk.xcframework.tar.gz`, and attach them to a fork release.
- In the SDK, follow `docs/daemon-sync.md`: `wavelength.version`, `scripts/generate-models.sh`, `fetch-bindings.sh all --update-digests`,
  the error strings, the presets. Add a mainnet preset to `NetworkPresets`, and the engine fix if spike 3 asked for one.
- `./gradlew check` and `:wavelength-embedded:connectedAndroidDeviceTest` pass on the released binaries. The SDK's plan names both as
  its unfinished Milestone 8.
- Make `generate-models.sh --check` pass, which it can't until the pin is a release.

**Done when** a clean clone stages verified bindings and the submodule here points at a commit whose pin is a real release. The SDK has no
published Maven artifacts, and this plan doesn't need any: the composite build is how it arrives.

### Phase 2: the build

- `settings.gradle.kts`: `includeBuild("experimental/wavelength-kmp")`, substituting `wavelength-core`, `-engine`, `-embedded` and `-testing`.
- **The bindings' file repository.** The SDK's `exclusiveContent` for `wavelength-bindings-android` is in its own settings, and `embedded`'s
  `implementation` dependency reaches the app's runtime classpath, so the app's `dependencyResolutionManagement` needs the same
  `experimental/wavelength-kmp/bindings/maven` entry, restricted to that one module.
- **A clean checkout has to build.** The bindings are ignored by git. Add a Gradle task that runs `fetch-bindings.sh android` before Android
  dependency resolution needs it, or fails with the command to run. `README.md` and the final check gain the step.
- `composeApp/build.gradle.kts`:
  - `commonMain`: core and engine. `androidMain`: embedded. `jvmTest`: testing. Nothing native in `jvmMain`.
  - `iosMain` gets embedded only in Phase 12, since its cinterop needs a staged xcframework on any Mac that builds this.
  - D15's splits.
- `AndroidManifest.xml`: nothing. `INTERNET` is already there.

**Done when** Android and desktop compile, `jvmTest` passes, the audit is unchanged, the APK holds `libgojni.so` and the desktop package
doesn't. Nothing calls the runtime yet.

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest :composeApp:m3Audit :composeApp:packageDeb
```

### Phase 3: keys, gate and setup, pure

`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/wavelength/`:
- `WalletKeys.kt`: words to a `DescriptorWalletRequest`, with BIP380 checksums, on bitcoin-kmp. No `toString` that prints a key, and no key in
  an exception. A `String` can't be zeroed, so the request lives only for the call.
- `WavelengthWalletRollout.kt`: D5 and D6, with `capFor(network)` and `configFor(network, dataDir)`.
- `WalletSetup.kt`: `resolve` to `Ready(config)` or `Unavailable(reason)`, in Bark's order of explanation: no words, no runtime, Tor, consent.
  `Unavailable` has `NotOffered` for an unconsented profile only (D5).
- `WalletFiles.kt`: the consent file and the pinned identity, neither secret.
- `WavelengthPlatform`: the `expect`, and three actuals: Android has the client, iOS and JVM return null.

Tests in `commonTest`: Phase 0's golden vector, on two sets of words. Every path against D1's table. A test that fails if `wpkh` or `m/84'` ever
appears in a request. Redaction of every type. Setup's order. **A consented profile resolves `Ready` with `OFFERED = false`.**

**Done when** `jvmTest` passes and nothing outside `wallet/wavelength/` changed.

### Phase 4: one wallet per active profile

- `IdentityWavelength.kt`, held per process, with `state: StateFlow<WalletState>` of `Unavailable(reason)`, `Opening`, `Recovering`, `Open(engine)`,
  `Failed(error)`, and `Locked` where a reopen is refused.
- The open: under the mutex, first open sets `recoverState` and the birthday (D12), later opens set `expectedIdentityPubKey`. Pin the identity after
  the first success. An identity mismatch is a hard stop with its own state, and it never wipes anything.
- The switch follows `DedunNavHost`'s identity: stop, then open. The words come from `availableIdentities`, as in Bark's Phase 3, and are never held
  in a `StateFlow`, logged or put in a message.
- `BackgroundRestartPolicy.reopening`, bound in `DedunApplication`, with whatever grace spike 7 asked for.
- Forget: unchanged. A seed profile is already refused (`WalletAttached`), and Phase 13 is the first that needs more.

**No code deletes a wallet directory, anywhere** (the removal note's D3).

Tests in `jvmTest` over `FakeWavelengthClient` and a recording opener, on `IdentitySwitchJvmTest`'s harness: close-before-open, Tor, consent, npub,
identity mismatch, partial recovery, a failed open leaving the next attempt working, background and foreground while opening.

**Done when** on a signet emulator, switching between two seed profiles opens each one's own wallet and only ever runs one daemon.

### Phase 5: the profile row and the wallet screen

Taken from `ArkWalletItems.kt`, `ArkWalletScreen.kt` and `ArkWalletViewModel.kt` at `56031156`, reshaped for `WalletSnapshot`:

- `ActiveProfileScreen`: a *Wallet* row above `memberWalletItems`: the balance, then *Pending: …*, *Renew by 14 Dec*, *Opening…* or the reason it isn't
  available. `NotConsented` opens D9's sheet.
- `WavelengthWalletRoute` and screen: pushed, with `NavigateBackButton`. Balance header, *Receive* and *Send*, the deadline, the cap and the history, newest
  first, pending on top. `ScreenStateTransition` over four states, `EmptyState` with its own message, `ErrorState` with a retry, `readableContent()`, and two
  panes from `Breakpoint.Expanded` for the entry detail.
- **Credit is its own line.** Where `creditAvailableSat` is non-zero: *Held by Lightning Labs as credit*, with a sentence on what that means. It is never added
  to the balance.
- `EntryMapping.kt`: `EntryKind` and `EntryStatus` to words, with the `UNKNOWN` fallback rendering rather than failing.
- `RecoveryPhraseScreen`: the sentence that these words also restore this wallet, and D11's.

Tests: compose tests over the fake for each row state and screen state, at phone width and 1280 dp. `MovementUpdated`'s equivalent, an entry updating in place.
The audit stays at budget. `adaptive APIs in use` is exactly on its floor of 12, so this adds to it.

**Done when** a signet profile shows its balance and history on Android, and `./gradlew check` passes.

### Phase 6: receiving

From `ArkReceiveScreen.kt`:
- Two tabs, *Lightning* and *Onchain*, because the SDK has no single URI. Lightning requires an amount (the removed Bark screen's last fix already says why);
  onchain is `deposit()`, with what boarding costs and how many confirmations it waits for.
- `ReceiveOutcomeUncertain` reconciles the activity list before offering another invoice, never retrying blindly.
- An amount below the operator's floor says it arrives as credit, before the invoice is made.
- The cap is checked against the balance plus what is pending. `qrose` with the `m3-color-exempt` comment. Copy only, as there is still no share sheet.
- Completion through the snackbar, with `rememberNotifier`. While pending: whatever spike 6 found to be true about keeping the app open.

Tests: view model paths for amount, cap, floor and uncertain outcome, and pending to complete from a fake event.

**Done when** a signet profile receives over Lightning and onchain, and the history says which.

### Phase 7: sending

From `ArkSendScreen.kt`:
- One field, paste only. `Destination.classify` decides. The form asks for an amount only for `Amountless`, and refuses `Unrepresentable` in words.
- `prepareSend`, then a review naming the rail, the amount, `expectedFeeSat` and what remains, then `sendPrepared`. An expired or reused quote re-quotes. `maxFeeSat`
  is set from the review. `maxCreditSat` defaults to zero unless the user is paying from credit.
- A Lightning address, resolved to an invoice through the resolver `group-payout-destination.md` already built, with Tor's rule. BOLT12, LNURL-pay and Ark addresses say
  plainly they aren't supported.
- An onchain send says it settles in a round, and how long the operator's cadence makes that.
- `PreimageStore`: a small Room table, so a payment's proof survives the restarts of D10. A schema migration, tested as the others are.

Tests: each destination's amount rule, cap, insufficient funds, each typed send error, and pending to failed.

**Done when** a signet profile pays an invoice and an onchain address, and the proof is still there after a restart.

### Phase 8: keeping coins alive

- `WavelengthMaintenanceWorker`: a `CoroutineWorker`, periodic and network-required, scheduled from `schedulePlatformLogic`. Add `work-runtime-ktx` back. It opens the default
  profile's wallet only if consented, Tor is off, and the app isn't in the foreground, decrypting headless as `ChannelsWatcher` does. It waits as long as spike 5 said, then stops
  what it opened. A profile switch or a foreground return cancels it cleanly through the holder.
- The deadline: earliest `WalletVTXO` expiry against the tip, in time, using guardian-delay's blocks-as-time helper. Inside the operator's free window it says the app is renewing. Near the
  critical threshold it becomes a warning: *Open Dedun on Wi-Fi and leave it open*, or *Renew now* if Phase 0 put a verb in the SDK.
- The expired state says the coins now depend on the operator, and offers Phase 9's way out.

Tests: threshold mapping across tip heights, the worker opening and stopping only what it opened, and refusal cases. On a device: `adb shell cmd jobscheduler run`, which Bark never did.

**Done when** a signet coin renews with the app closed and untouched, across at least one full lifetime.

### Phase 9: getting out

From `ArkAdvancedScreen.kt`, behind a sentence saying when it is needed:
- The VTXO list, with amount, expiry and state, and credit.
- *Move everything onchain*: `SendRequest.Onchain(sweepAll = true)` or `exitBatch(Cooperative)`, whichever Phase 0 shows leaves least behind, to an address the user pastes, with the fee shown.
- *Emergency exit*: `getExitPlan` first, then `exitBatch(Unilateral)` with `FORCE_UNROLL_ACK` behind its own dialog. Progress from `exitStatus`, resumable across a restart. The plan's funding need
  is stated, with a `deposit` address to fund it. Exit state is in the database, so the words alone can't restore an exit under way, and the screen says so.
- `sweepWallet` for what is left in the backing wallet.
- Profile switching warns while an exit is under way.

**This phase comes before any mainnet money.** A wallet with no tested way out does not get a mainnet flip.

Tests: over the fake for each step and stop. Against signet with the operator blocked at the network level: the whole exit through to a claim.

**Done when** a signet wallet has left both ways, and nothing it held is unaccounted for.

### Phase 10: on a device, against the real runtime

- A new `androidInstrumentedTest` source set: open, reopen, switch, background and foreground, recovery from a wiped directory, and a mainnet empty wallet behind `DEDUN_WAVELENGTH_MAINNET=1`, like Bark's gated test.
- Local regtest isn't a prerequisite. If signet flakes block the rest, wavelength's own `harness/` is the base for `testing/regtest/wavelength/`, and that is its own commit.
- A soak: chaos by hand and by script. Process death during recovery, during a send and during a boarding. Network changes. Low storage. A day in the background. A rotation storm.
- **A secrets check:** search all of logcat through every flow for `prv`, as the SDK's plan did. `debugLevel` is off in release. A crash report holds no words.
- **An upgrade test:** a wallet from the previous pin opens on the next. Every daemon bump repeats this, because upstream says the on-disk schema may change.
- Backup: `adb backup`, or a restored device, doesn't carry the directory.
- A security review of this diff (`/security-review`), with D1's derivation read by a second person who didn't write it.

**Done when** the suite passes on arm64 and x86_64 emulators, 16 KB pages included, and on one real mid-range phone, which no phase of the Bark wallet ever reached.

### Phase 11: mainnet

Bark flipped its network by decision, ahead of its own condition. This plan's condition, by hand, on a real Android device with an amount someone can afford to lose, under the cap:

| step | what to see |
|---|---|
| Consent, then the first open | what the sheet says is what the app connected to |
| Receive over Lightning, and onchain through boarding | both in the history, with the fees stated |
| Send over Lightning, and onchain | settled, with the fee the review showed |
| Leave it closed past a free window | renewed by the worker, at zero fee |
| Restore: wipe the app, type the words | the same identity, balance and history as far as recovery rebuilds it |
| Cooperative exit of part, then an emergency exit of the rest | the coins arrive, and the wallet reads zero |
| Credit: receive below the floor | shown as credit, and spendable or explained |

Then `WavelengthWalletRollout.NETWORK = Mainnet`, one commit, with the operator's terms from Phase 0 re-read, and `versionCode` bumped. A signet build type stays available for testing.

**Done when** the table is done and written here with dates, amounts and txids. Lifting the cap is its own later decision, with the same shape of condition `GroupWalletRollout` uses.

### Phase 12: iOS

On a Mac, starting with the SDK's own unfinished Milestone 6, since its iOS has never been compiled.
- `./gradlew :wavelength-embedded:iosSimulatorArm64Test` passes against the fork's xcframework. ADR 0004's validation list: soak the Go and Kotlin/Native runtimes in one process, and check the header rewrite.
- `iosApp`: link `Wavewalletdk.xcframework` by SPM (`binaryTarget`) and `-lresolv`. `iosMain` gains embedded. The actual returns the client, with its directory in Application Support, excluded from iCloud.
- Foreground only (D10). A `BGAppRefreshTask` needs a headless seed decrypt from Swift, which nothing does yet, and iOS guarantees nothing about when it runs anyway.
- Checked on a simulator, then a device, on signet, then Phase 11's table again.

**Done when** Phase 11's table passes on an iPhone.

### Phase 13: nsec profiles

D13. Bring back `ArkSeedStore` at `56031156` as `WalletSeedStore`, `node-data/wavelength-seeds.dat`, encrypted under the same keystore alias. `ArkRecoveryPhraseScreen` comes back reachable from the profile row in any wallet state, never only from an open wallet,
which was Bark's trap. Words made at consent, saved-confirmation required before receiving. `ForgetIdentity` gains an outcome: refused while the balance, credit or an exit is non-zero, or while words are unconfirmed, and only then. Same derivation as D1 from those words.

**Done when** an nsec profile runs Phase 11's table, and can be forgotten once empty.

---

## The final check

Before each release that carries wallet changes, on a clean checkout:

```bash
git submodule update --init --recursive
```

```bash
experimental/wavelength-kmp/scripts/fetch-bindings.sh android
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest :composeApp:m3Audit
```

```bash
./gradlew :composeApp:connectedDebugAndroidTest
```

The group Lightning regtest run isn't needed unless a phase touches the node: nothing here does.

## Rollback

Until Phase 11 ships, every phase is an ordinary revert. **Afterward there is no revert.** Someone may hold money, so taking it out means the removal note's appendix: `OFFERED = false`, renewal running, receiving off, the way out on the front screen, a wait of more than one
coin lifetime. D5 is what makes that a flag and not a rewrite.

## What this plan does not do

- **Group bridges.** *Pay from my wallet* on a deposit invoice is a natural follow-up, with `ArkGroupBridge` in history. *Use my wallet's address* isn't, as it stands: `deposit()` is a boarding address, not a payout destination.
- **A group Ark wallet.** `group-ark-wallet.md` stands on removed code, and this plan doesn't answer it. It should be marked shelved, or rewritten against this one.
- **A watch-only or remote-signer wallet.** The SDK has it, and it is what guardians might want one day. A phone-only wallet needs none of it.
- **Touch the Phoenix node** (D14), or **fix Android's backup rules** generally. Only this wallet's directory is excluded.
- **Desktop** (D4).

## Open questions

1. **The daemon (D2).** Our fork as a release, or external seed on upstream `v0.2.1`? Recommended: the fork. It must be settled by the end of Phase 0, because it fixes where the coins' keys are.
2. **Distribution (D15).** Play, direct APKs or both? It decides how much the size matters, and which ABIs ship.
3. **Is desktop acceptable as unavailable (D4)?** If not, a remote-daemon or C ABI plan goes ahead of this one's iOS.
4. **Who runs Esplora long term (D6)?** A third party's public instance is fine for a capped wallet. Lifting the cap is a reason to run one.
5. **The SDK's licence.** Its README says the Apache licence "came with the project template and is still under review"; upstream is MIT. Fix before shipping it in the app.
