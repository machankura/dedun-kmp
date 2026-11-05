# A Bark wallet under the profile

The profile gets a wallet a person can see: a balance, a way to receive, a way to send and a
history, on the profile tab above the member wallets. It is an Ark wallet, run on the device by
Second's Bark SDK. One balance can be spent to an Ark address, a Lightning invoice or an onchain
address, and paid into the same three ways. There are no channels and no inbound liquidity.

**Removed on 5 November 2026** ([bark-wallet-removal.md](./bark-wallet-removal.md)): no build had held
real sats. The wallet was last whole at `56031156`, and that commit is where to find the files, commands
and scripts this note names. The note stays for its spikes and findings. Everything below it is as it
stood.

**Built, phases 0 to 13, one commit each, and on mainnet by decision.** Written against `7658c8ed`
(`dedun`), with the `lightning-kmp-app` submodule at `7aaacadc`, which the build did not touch, and
against the Bark FFI bindings `v0.26.1+bark-0.8.0`. Those bindings wrap `bark-ffi` `0.30.1`, which pins
`bark` `0.8.0` and uniffi `0.31.1`. What is checked and what is not:
- Android and Linux desktop compile, and the JVM suite passes (2,014 tests after `dedun`'s group
  swap-in was merged in).
- The adapter runs against Second's signet server, and end to end against `captaind` on regtest,
  emergency exit included. `ArkMainnetJvmTest` (`DEDUN_ARK_MAINNET=1`) opens a wallet with no money
  against the mainnet server with the endpoints the app ships, and asks for an Ark address, an
  invoice and an onchain address; it passes.
- Not checked: the app run on a device; iOS (its Kotlin actual and Swift half have not been
  compiled); macOS and Windows natives (the script has not been run); Lightning with money.
- `ArkWalletRollout.NETWORK` is mainnet. It was switched by decision, ahead of Phase 12's
  hand-made mainnet receive, send and renewal, which have still not been made; `MAINNET_CAP`
  (100,000 sats a send or request) is what bounds that. With the group wallets also on mainnet,
  the two group bridges of Phase 10 now show.

The phases are kept as written; the table below says where the build chose differently.

Where the build chose differently, phase by phase:

| phase | what the plan said | what it turned out to be |
|---|---|---|
| 1 | `ArkConfig.kt` refuses with `Unavailable(TorOn)` | `ArkSetup.resolve` returns `Ready(ArkConfig)` or `Unavailable(reason)`, checking the gate, the seed, the platform, Tor and consent in that order. `ArkConfig` itself is only what Bark is opened with |
| 1 | strings for each `ArkUnavailability` in Phase 1 | added in Phase 4 with the row that shows them, so the catalogue never holds a string nothing reads |
| 1 | the warning threshold is "Bark's config" | `ArkExpiry.thresholdFor`: 144 blocks on mainnet and 12 elsewhere, which are `bark`'s own `network_default`s |
| 2 | `expect fun openArkWallet(...)` | `expect object ArkPlatform { supported; open(...) }`, so the setup can ask whether the platform has a library without trying to open anything |
| 2 | add `bark-android` | add it with JNA's jar excluded and `jna:5.15.0@aar` named. Bark's Gradle metadata asks for the jar, which has no `libjnidispatch.so`, so the first call would fail on a device. The debug APK now carries both libraries for every ABI. Later raised to `jna:5.19.1@aar`: 5.15.0's x86_64 `libjnidispatch.so` is 4 KB aligned, which fails to load where pages are 16 KB; 5.17.0 is the first with 16 KB on both 64-bit ABIs |
| 2 | R8 keep rules from spike 1 | none: release builds are not minified (`isMinifyEnabled = false`) |
| 2 | the signet test pays itself from a second wallet | it cannot: there is no faucet API. It covers everything a wallet with no money can do, and the funded flows are Phase 12's |
| 2 | the debug APK starts on an emulator | not done: the emulators on the build host belong to other work, and nothing calls the binding until Phase 3 |
| 3 | consent in `InternalPrefs`, next to the Tor flag | a file, `<appFiles>/ark/consented/<walletId>`, behind `ArkFiles`. `InternalPrefs` keeps its DataStore private to the library, and one boolean was not worth a submodule commit. The Tor flag is in `UserPrefs`, not `InternalPrefs` |
| 3 | the words reach `IdentityArk` through the startup screen's `Mnemonic` branch | the nav host reads them from `availableIdentities`, the seeds the selector has already decrypted, when the identity changes. The startup screen is unchanged, and the words go into an `ArkProfile`, never into `Identity` |
| 3 | `Recovering(status)`, and a retry that calls `recover_vtxos` on the failed ids | `Recovering` is shown while a datadir that did not exist is opened, because the scan runs inside `open`. The scan's outcome rides on `Open(wallet, recovery)` for the screen to state, and `retry()` reopens after a failed open. Re-scanning failed ids is left to Phase 9's advanced screen |
| 3 | forget refuses with `ArkFundsOrExits` and deletes the datadirs | unchanged, because it cannot be reached: `ForgetIdentity` already refuses every profile with a seed (`WalletAttached`), and only those have an Ark wallet. Phase 13's nsec wallets are the first that forget will meet |
| 3 | Tor read once | followed: the nav host collects the profile's Tor preference, so turning it on closes the wallet and turning it off reopens it |
| 4 | *Receive* and *Send* on the wallet screen | an `actions` slot under the balance, filled by Phases 5 and 6 as each lands, so no button leads nowhere in between |
| 4 | `ArkMovementScreen` reads one movement | it reuses `ArkWalletViewModel` on its own back-stack entry and finds the id in the history. Bark's history is one call, and a view model per movement would duplicate the event handling |
| 4 | the row names the reason for every `Unavailable` | and only *Not turned on* acts: a *Turn on* button opens the sheet. Tor, this device and a profile without a phrase are rows that say why and do nothing, because nothing on this screen changes them |
| 4 | a partial recovery is a retry | it is a card that says some coins could not be restored yet. A reopen would not rerun the scan (`recoveryStatus` is `NotRun` after the first open), so a retry would promise what it cannot do |
| 5 | a cancel of receives older than their invoice's expiry on every open | not built: Bark cancels an expired receive itself when it syncs, and `cancel_lightning_receive` is for one the user gives up on. The open claims only (`claimLightningReceives`, inside the holder's lock) |
| 5 | *Board* offered on the receive screen | on the wallet screen, as *Move onto Ark*, because that is where the onchain balance is shown. It boards everything (`boardAll`) after a confirmation that the fee comes out of the amount. The receive screen's *Onchain* tab says an onchain payment lands there first |
| 5 | Share as well as Copy | Copy only: the app has no share sheet on any platform yet |
| 5 | the cap is checked against the balance | against the spendable balance plus what is pending, so two invoices in a row cannot each pass on their own |
| 6 | after *Send*, the movement's detail screen in its pending state | back to the wallet screen with a snackbar, where the payment is the top row and its status follows `MovementUpdated`. The send call returns a movement id only for Ark payments, a payment hash for Lightning and a txid for onchain, so the detail screen could not be opened by id for two of the three rails |
| 6 | a Lightning send Bark marks stuck is explained | not built: a stuck send shows as *Pending* in the history. Explaining it, and offering `allow_lightning_send_to_exit`, goes with Phase 9's advanced screen |
| 6 | scanning, if the app has a camera path | it has none (no scanner anywhere in the tree), so the form is paste-only and the open question is answered |
| 6 | an amount the request names is passed to Bark | it is not: Bark refuses a second amount for an invoice that names one, so the amount goes only to a method that `requiresAmount` |
| 7 | `IdentityArk` held by the nav host for the app's life | held by `ArkProcess`, one per process. Bark asks that the wallet not be closed when Android destroys an activity, which a composition-held holder would do on every rotation, and the Android worker has no composition to borrow one from. The nav host no longer stops it on dispose |
| 7 | the worker opens the default profile's wallet "if it is not already open" | through `maintainInBackground`, under the holder's lock: the open wallet if it is the default profile's, a wallet opened and closed for the purpose if no profile is active, and nothing if another profile is (the app has that one open). It also checks the profile's consent file before it decrypts a seed |
| 7 | the foreground maintenance runs outside the open | inside the holder's lock, on open and on every foreground, at most hourly. A switch can wait behind one delegated maintenance; in exchange nothing can close the wallet mid-call |
| 7 | *Renew now* calls `maintenance_refresh` | `refresh_vtxos_delegated` for the coins Bark says are due, after `estimate_refresh_fee` for the same coins. Shown when the deadline is inside the threshold or past it |
| 7 | iOS's `BGAppRefreshTask` | moved to Phase 8 with the rest of iOS. The worker's body is `maintainInBackground`, which the iOS task will call |
| 7 | checked by running the worker with `adb shell cmd jobscheduler run` | not run on a device. The worker builds into the debug APK, and its logic is `maintainInBackground`, whose three cases are tested in `IdentityArkJvmTest`. The signet test runs Bark's delegated maintenance, renewal fee, renewal and claim against Second's server, and maintenance returns without waiting for a round |
| 8 | `interface ArkEngine`, "the port in callback form" | four methods with JSON across the boundary: `open`, `call(method, argumentsJson)`, `nextEvent` and `close`, each answering once through an `ArkEngineReply`. Swift cannot implement a Kotlin `suspend` function, and a protocol with one method per port call would change with every call the port gains. `EngineArkWallet` in commonMain is the Kotlin half, and `EngineArkWalletJvmTest` pins the method names and JSON shapes against a fake engine on the JVM |
| 8 | `iosMain` wraps the engine with `suspendCancellableCoroutine` | commonMain does (`openEngineWallet`), so the wrapping is tested on the JVM. `iosMain`'s `ArkPlatform` only reads `ArkEngineRegistry` |
| 8 | `iosApp` adds the package through Xcode | `project.pbxproj` gains the `XCRemoteSwiftPackageReference` (exact `0.26.1`) and the `Bark` product dependency by hand. `BarkEngine.swift` sits in the synchronized `iosApp` folder, so it needs no project entry, and `iOSApp.init` registers it |
| 8 | the flows checked on an iOS simulator | **not compiled.** iOS targets are declared only on a macOS host, and this one is Linux. The Kotlin `iosMain` actual, `BarkEngine.swift` and the project edit have not been built. The Swift is written against Bark's Swift API as its documentation and uniffi's naming give it, and two lines are most at risk: ``OnchainWallet.`default` `` (a keyword, escaped) and the exact-version match on a tag with build metadata (`v0.26.1+bark-0.8.0`) |
| 8 | Phase 7's `BGAppRefreshTask` | not built. It needs the seed decrypted headless from Swift, which nothing on iOS does yet. iOS renews in the foreground only, with the in-app warning as the safeguard |
| 11 | built in CI, published as jars of JNA resources | there is no CI. `docs/scripts/bark-natives.sh` builds the library on a macOS or Windows host from the same `bark-ffi-bindings` tag and puts it under JNA's prefix in `composeApp/src/jvmMain/resources/`, which `.gitignore` keeps out of the repository. A desktop package carries whatever was built on the host that made it |
| 11 | drop the platform check for those three targets | the check became the presence of the library: Linux x86-64 always (bark-jvm's own), any other desktop when its library is on the classpath under JNA's prefix (`ArkJvmLibrary`). So a macOS build without the library still says *Not available on this device* rather than failing in JNA |
| 11 | the signet test passes on a macOS host, and the `Dmg` and `Msi` open a wallet | **not done.** There is no macOS or Windows host here, and running the script on this one was refused by the session's safety check: it clones and builds Bark's repository. The script has not been run anywhere |
| 12 | captaind, PostgreSQL and Core Lightning added to `testing/regtest/` | a separate project in `testing/regtest/ark/`, with its own bitcoind on 38443, so it runs beside the group network. Second's stack as it is cannot start: `captaind:0.8.0`'s bundled config lacks fields 0.8.0 requires (so `ark/captaind.toml` is generated from `server/captaind.default.toml` at `server-0.8.0` and mounted through `CAPTAIND_CONFIG`), 0.8.0 refuses bitcoind 30.0 (so 31.1), Core Lightning's start script assumes a directory a fresh volume lacks (so the entrypoint makes it), and nothing serves Esplora (so `mempool/electrs:v3.3.0`). The images are pinned; PostgreSQL is inside the `captaind` image |
| 12 | a short VTXO lifetime so expiry is reachable in minutes | the default 4,320 blocks: the vtxopool's settings are tied to it, and the test reaches renewal through delegated maintenance rather than through expiry |
| 12 | Lightning send to the eclair payer and receive from it | not in the run: the Ark network's Core Lightning has no channel to anything. Lightning is the one rail that has run only on signet, without money |
| 12 | the run: board, Ark send, Lightning, renewal, offboard, an exit with captaind stopped, a restore | two tests, both passing on this host: `boardPayRenewOffboardAndRestore` (66 s) and `anEmergencyExitWithTheServerGoneIsClaimedOnchain` (175 s: the exit broadcast, the 144-block wait mined through, the claim signed and broadcast) |
| 12 | then flip `ArkWalletRollout.NETWORK` to mainnet | **flipped by decision, ahead of its condition.** The plan's condition was a mainnet receive, send and renewal made by hand on Android, which needs a device and real money and has not been done. The wallet was left on signet, then switched to mainnet when asked; `ArkMainnetJvmTest` checks the shipped endpoints with an empty wallet |
| 13 | the phrase screen reached from the wallet's advanced screen | it is, and only for an nsec profile: a seed profile's wallet has no words of its own, and its phrase is the profile's |
| 13 | forget deletes the Ark seed with the datadir | forget is refused while an nsec has Ark words (`ForgetIdentity.Outcome.ArkWalletAttached`, checked before anything is touched), because those words are the only way back to that money. The refusal says to move the money out first. Deleting the words and the datadir once the wallet is empty is not built |
| 13 | `ArkSeedManager` next to `SeedManager` | `ArkSeedStore` in composeApp, with the keystore calls injected so the encryption is tested on the JVM. The file is `node-data/ark-seeds.dat`, written through the library's `AtomicFileWrite` |
| 13 | an nsec is `NoSeed` | `NoSeed` is an npub's alone now. An nsec without words is *Not turned on*, and *Turn on* makes its words, records the consent and opens, in that order and under the holder's lock |
| 9 | the exit calls on `ArkWallet` | on `ArkExits`, which `ArkWallet` extends, so the everyday screens' fakes and readers do not carry ten calls they never make |
| 9 | *Claim* | `drain_exits` builds the claim, `sign_exit_claim_inputs` signs the exit outputs, and `extract_tx_from_psbt` and `broadcast_tx` send it. The claim is enabled once an exit is *Ready to claim* and the tip has reached `all_exits_claimable_at_height` |
| 9 | *Move everything onchain* behind a confirmation | two taps on one button: the first prices the offboard for the address typed and shows the fee, the second moves. The emergency exit has a dialog of its own, because it cannot be undone |
| 9 | a profile switch warns while an exit is under way | not built. Forget already cannot reach a seed profile (Phase 3), and the warning belongs with the switcher's own screen |
| 9 | re-scanning a partial recovery's failed ids | not built. The card from Phase 4 still says some coins could not be restored yet |
| 9 | checked on regtest with captaind stopped | the view model's paths are tested over the fake, and the adapter's exit calls run against Second's signet server for a wallet with no coins. A real exit waits for Phase 12's regtest harness |
| 10 | both bridges shown whenever the wallet is open | only when the Ark wallet's network is the group wallet's (`ArkNetwork.matches`). With both on mainnet since the flip, both bridges show; on regtest they line up too |
| 10 | an `ArkMovementNote` row, so the history reads *Deposit to Treasury* | not built: no Room table yet. The payment shows as *Sent over Lightning* with the group's invoice as its counterparty. The label is the first thing that would need the table D9 kept in reserve |
| 10 | the payout address is filled in one tap | it is, from the wallet's onchain side (BDK, a new address each time, so unused by construction), and the form's own unused-address check then runs on it as on anything typed |
| 10 | checked on regtest against the group Lightning wallet's harness | the bridge is tested over the fake on mainnet and regtest networks; a real deposit paid from the Ark wallet waits for Phase 12's harness, which adds an Ark server beside the LSP |
| 4 | checked on Android and Linux desktop with a signet profile | checked in compose tests over every state, at phone width and at 1280 dp for the two panes. Not yet run in the app, because that would mean a passphrase and a profile in this host's real desktop data directory |

The headline: **a seed profile already holds the twelve words Bark needs. Bark's keys sit at
`m/350'` and its onchain wallet at BIP86, so it can open those same words without touching a
key the Phoenix node or the nostr identity uses. What is missing is the binding on every platform
(iOS has none in Kotlin), one wallet per active profile, the screens, and a way to keep coins
alive. A coin left alone for 28 days can be swept by the server.**

Read [multiple-profiles.md](./multiple-profiles.md) (*The vocabulary*, *The constraint*, Phase 2)
first: a profile is a credential, and a wallet is attached to one. Then read
[standalone-electrum.md](./standalone-electrum.md) D4 and D5. Its rule that Tor on never falls back
to clearnet is applied here unchanged. Its `IdentityElectrum` is the shape this plan copies for a
per-profile resource that must close before the next one opens.

---

## What the profile has today

| fact | where | what it means here |
|---|---|---|
| Only a `Mnemonic` identity has a wallet: a Phoenix-fork node, a `PhoenixBusiness`. `NostrSecret` and `NostrPublic` have `business == null` | [Identity.kt:68](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/identity/Identity.kt), [SovereignWalletStartupScreen.kt:204-247](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/SovereignWalletStartupScreen.kt) | the profiles that can have a Bark wallet without a new seed are exactly the ones with a node |
| **No screen shows that node.** It has no balance, send, receive, history or swap-in screen. `SovereignWalletUIState.Success(balanceInSatoshis = 0 // TODO)` is a stub | `SovereignWalletViewModel.loadSovereignData` (`:137-146`) | the Bark wallet is the first personal wallet the user ever sees. There is no UI to replace |
| The profile tab shows avatar, name and npub, then the guarded member wallets, then key packages, switch, recovery and sign out | [ActiveProfileScreen.kt:295](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/ActiveProfileScreen.kt) `memberWalletItems`, wired at [DedunNavHost.kt:1147](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt) | the wallet's row goes above `memberWalletItems` |
| There is no wallet, payment or balance interface in `composeApp`. The interfaces are group repositories and `ElectrumLink` | `compose/repository/`, `compose/wallet/StandaloneElectrum.kt:169` | the port in Phase 1 is new, not an adaptation |
| Seeds are 12 words with an empty passphrase, in `seed.dat` (an `EncryptedSeed.V2.MultipleSeed` map of `walletId → words`), encrypted under the platform keystore | `SeedManager.kt`, `IdentityWriter.kt:91-201`, `StoredIdentity.kt:85-89` | Bark is opened with `StoredIdentity.Mnemonic.userWallet.words`; nothing new is stored for a seed profile |
| `StoredIdentity.merge` attaches a seed to the credential whose key it derives. A seed with no credential is listed as a profile of its own | `StoredIdentity.kt:129-159`, `SeedCredentials.reconcile` | a Bark-only seed for an nsec profile **cannot** go in `seed.dat`. See D13 |
| A switch sets `desiredWalletId`, clears the active identity and stops the previous node | `SovereignWalletViewModel.switchToIdentity` (`:388-396`) | the wallet closes on the same edge |
| `IdentityElectrum` closes the last client before making the next, because a `remember` keyed on the identity would build the new one before `DisposableEffect` disposes the old | [IdentityElectrum.kt:28](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/IdentityElectrum.kt), held at `DedunNavHost.kt:308` | Bark has the same hazard in a harder form: a second open of a datadir fails |
| The network is fixed: `NodeParamsManager.chain = Chain.Mainnet`, with no runtime or build switch | `NodeParamsManager.kt:92` | the Ark wallet needs a network of its own, from its rollout object, not from the node |
| Tor is a per-profile preference that swaps server lists for onion ones over plain TCP. There is no SOCKS proxy or embedded Tor anywhere | `UserPrefs.getIsTorEnabled`, `PeerManager.kt:193`, `ElectrumServers.kt` | Bark can be given no proxy. See D6 |
| Gates are compile-time objects with a mainnet cap | [GroupWalletRollout.kt:14](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/GroupWalletRollout.kt) (`MAINNET_CAP = Satoshi(100_000)`), `GroupLightningRollout.kt` | `ArkWalletRollout` copies the pattern |
| Android has WorkManager jobs (`ChannelsWatcher`), but no foreground service and no FCM. iOS schedules nothing. JVM schedules nothing on purpose | `Phoenix.android.kt:21-24`, `Phoenix.ios.kt:23-25`, `Phoenix.jvm.kt:104-124` | background renewal is real work on Android and new work on iOS, and stays a foreground-only promise on desktop |
| Native code reaches the app only through the composite build: secp256k1 JNI jars per OS, an Android AAR and an iOS cinterop. No Rust, uniffi or JNA in `composeApp` | `composeApp/build.gradle.kts:54-64`, secp256k1-kmp `jni/` | the first uniffi/JNA dependency. Packaging is new on every target |
| Desktop ships `Dmg`, `Msi` and `Deb` | `composeApp/build.gradle.kts:336` | `bark-jvm` is Linux x86-64 only. See D4 |
| The regtest harness is bitcoind, electrs and two eclair nodes, gated by `DEDUN_REGTEST=1` | [testing/regtest/](../testing/regtest/README.md) | an Ark server (captaind) is added in Phase 12 |

## What Bark gives, and what it asks

From the SDK's own pages ([Bark SDK](https://second.tech/docs/bark-sdk.md),
[Kotlin](https://second.tech/docs/bark-sdk/kotlin.md), [Swift](https://second.tech/docs/bark-sdk/swift.md),
[backups](https://second.tech/docs/backups.md), [how Ark works](https://second.tech/docs/learn/intro.md))
and from `bark-ffi`'s `src/muniffi/wallet.rs` at the pinned revision:

| Bark fact | consequence here |
|---|---|
| `Wallet.open(network, mnemonicOrSeed, config, WalletOpenArgs(datadir, onchain, …))` creates on first launch and reopens afterwards. `mnemonicOrSeed` takes a BIP39 phrase or a 64-byte hex seed | the profile's words are passed as they are (D1) |
| Keys: `m/350'` is the purpose; under it `/0'` holds the VTXO keys, `/1'` the mailbox key and `/2'` the recovery mailbox. The BDK onchain wallet is `Bip86(master)`, i.e. `m/86'/coin'/0'` (`bark/src/lib.rs:481-726`, `bark/src/onchain/bdk.rs:163`) | disjoint from every path the app derives (table in D1) |
| **Only one open wallet per datadir, even within one process.** A second `open` fails with "another process is already using datadir". Reopening with a different seed fails with "incorrect mnemonic" | one holder per process, keyed by profile, shared by the UI and the background job (D2) |
| Kotlin: `tech.second.bark:bark-android` (API 24+) and `bark-jvm` (**Linux x86-64, glibc 2.34+, JDK 17+ only**), from a GitLab Maven registry rather than Maven Central. The bindings are uniffi over JNA, in package `uniffi.bark` | Android and Linux desktop work out of the box. macOS and Windows desktop need natives built by us (Phase 11) |
| Swift: a Swift package (`Bark`, iOS 14+/macOS 14+) built as an XCFramework. **There is no Kotlin/Native binding** | iOS goes through a Kotlin interface that Swift implements (D4, Phase 8) |
| `Config` has no defaults: all 18 fields, `serverAddress` and `esploraAddress` required. **Esplora only** in every binding but Rust | the Ark wallet does not use the app's Electrum servers (D7) |
| `bark` has a `socks5_proxy` config, but behind a `socks5-proxy` cargo feature. `bark-ffi` neither enables the feature nor exposes the field | Tor cannot be honoured through the shipped bindings (D6) |
| Second's servers: mainnet `https://ark.second.tech` with Esplora `https://mempool.second.tech/api`; signet `https://ark.signet.2nd.dev` with `https://esplora.signet.2nd.dev` and a faucet | the defaults in `ArkWalletRollout` |
| VTXOs expire. On Second's server the lifetime is 28 days. After expiry the server can sweep, and recovery depends on its cooperation. Refreshing costs less the closer a VTXO is to expiry | renewal is a correctness feature, not housekeeping (D12, Phase 7) |
| `maintenance()`, `maintenance_delegated()`, `refresh_vtxos_delegated` and `refresh_vtxos_scheduled(ids, height)`: a delegated refresh is priced at the scheduled height, *"so scheduling further out never costs more than refreshing now"* | the device can arrange a renewal ahead of time instead of having to be awake on the day (to be confirmed in Phase 0) |
| Bark runs a daemon while the wallet is open: background sync, plus `run_daemon`, `stop_daemon` and `stop_daemon_wait`. `Drop` stops the daemon | closing on a switch is `stop_daemon_wait` and then `close`, in that order |
| `notifications()`: `MovementCreated`, `MovementUpdated`, `ChannelLagging` | the screens follow movements without polling. `ChannelLagging` means re-read everything |
| `history()` returns `Movement`s: status, subsystem, intended/effective balance, fee, addresses, VTXO ids, `payment_hash`, `lightning_invoice`, timestamps | history is Bark's database read through the port. There is no Room mirror (D9) |
| Lightning receive is an HTLC that this wallet must claim (`try_claim_all_lightning_receives`, `htlc_recv_claim_delta`) | a Lightning payment completes only while the app is running (D12, Phase 5) |
| `parse_payment_request`, `estimate_payment_fee` and `send_payment` cover an Ark address, BOLT11, a BOLT12 offer, a Lightning address, LNURL and an onchain address | one send form, not one per rail |
| `bip321_uri(options)` builds a `bitcoin:` URI with a new Ark address, a BOLT11 invoice when there is an amount, and an onchain address | one receive QR |
| The phrase alone restores the **balance**, through a mailbox on the server, if the server cooperates. History and in-progress exits need the database. `recovery_status()` reports how the first open's scan went | restore works, with a caveat the UI must state (D11, Out of scope) |
| Emergency exit: `start_exit_for_vtxos`, `progress_exits`, `drain_exits`, CPFP-bumped and paid from the onchain wallet, then a claim after `exit_delta` (144 blocks on mainnet) | needs an onchain wallet with funds, so Bark's own onchain wallet is opened (D8) |

## Decisions

**D1. A seed profile's own twelve words open Bark.** They are passed unchanged as
`mnemonicOrSeed`. Every path either side derives:

| user of the seed | path (mainnet) |
|---|---|
| Phoenix node key / channel keys / eclair key | `m/50'/0'`, `m/50'/1'`, `m/47'/0'` |
| Phoenix final wallet | `m/84'/0'/0'` |
| Phoenix swap-in | `m/52'/0'/…` |
| cloud key | `m/51'/0'/0'/0` |
| nostr (NIP-06) | `m/44'/1237'/0'/0/0` |
| **Bark VTXO, mailbox and recovery keys** | **`m/350'/0'`, `m/350'/1'`, `m/350'/2'`** |
| **Bark onchain (BDK, BIP86)** | **`m/86'/0'/0'`** |

No two collide. The alternative was a child seed (BIP85 or HMAC) passed as hex: it would isolate
Bark further, but a user typing their words into another Bark wallet such as Noah would restore
nothing. One phrase that restores the profile, its node and its Ark balance is worth more than the
isolation, since the paths are already disjoint. **Consequence:** the recovery-phrase screen's copy
now covers money held off the node. Phase 4 changes it.

**D2. One wallet per process, keyed by profile, owned by `IdentityArk`.** The datadir is
`<appFiles>/ark/<network>/<walletId>/`, where `walletId` is the profile's `WalletId`, the hash160
of the node id. The network sits in the path, so a signet wallet and a mainnet wallet for the same
profile never share a file. `IdentityArk` is a class held for the app's life, like
`IdentityElectrum`. It closes the previous wallet (`stop_daemon_wait`, then `close`) before it opens
the next, behind a `Mutex`, with the open under `NonCancellable` as the SDK asks. Android's
background worker runs in the same process and goes through the same holder, so the UI and the
worker never race for the datadir lock.

**D3. The app sees a port, never `uniffi.bark`.** `ArkWallet` in `commonMain` has the app's own
types: `Satoshi` from bitcoin-kmp rather than `ULong`, sealed payment requests, a `Movement` the
screens can render, and errors as a sealed class. Three reasons:
- iOS has no Kotlin binding, so `commonMain` cannot name one;
- the screens and the lifecycle are tested against a `FakeArkWallet`, as the group wallets are
  against `FakeElectrum`;
- `uniffi.bark.Exception` shadows `kotlin.Exception` under a star import, and generated types
  should stay inside one adapter file per platform.

**D4. Platforms.**
- **Android:** `bark-android`.
- **Linux x86-64 desktop:** `bark-jvm`.
- **iOS:** the Swift package, behind a Kotlin interface the iOS app implements in Swift and
  registers at start (Phase 8). Phase 0 also tries Gobley (uniffi to Kotlin/Native) against
  uniffi 0.31.1. If Gobley works, it replaces the Swift bridge and the Android/JVM adapter becomes
  `commonMain` code.
- **macOS and Windows desktop:** "not available on this device" until Phase 11 builds the
  `bark-ffi` cdylib for `darwin-aarch64`, `darwin-x86-64` and `win32-x86-64`.

The row on the profile says so in words. It does not hide.

**D5. Its own network and its own gate.** `ArkWalletRollout` holds:
- `ENABLED`;
- `NETWORK`: **signet** until Phase 12 passes, then mainnet (switched to mainnet by decision; see the status above);
- server and Esplora URLs per network;
- `MAINNET_CAP = Satoshi(100_000)`, the same figure as `GroupWalletRollout`.

Off mainnet the cap is null. On mainnet it bounds two things: a send, and an invoice or amount
request that would take the balance above the cap. It cannot bound an Ark or onchain payment
someone simply sends, so the wallet screen states the cap. This is independent of
`NodeParamsManager.chain`: the Ark wallet shares no LSP with the node. Bark's own rule, *"never use
mainnet without user confirmation"*, is met by the rollout object and by the first-open sheet in
Phase 4. That sheet names the server and what it can and cannot do.

**D6. Tor on means no Ark wallet.** `bark-ffi` ships without the `socks5-proxy` feature and exposes
no proxy field, and the app has no SOCKS proxy to point it at. A profile with Tor on would connect
to `ark.second.tech` and an Esplora server over clearnet, against the user's choice. So
`IdentityArk` does not open a wallet for it. The row reads *Not available with Tor on*.
Standalone-electrum D4 makes the same call for the same reason. A Tor transport for the whole app
is its own plan. If one lands, Bark needs a `bark-ffi` fork that enables the feature and adds
`socks5Proxy` to `Config`. Phase 0 measures that fork's cost.

**D7. Esplora, not Electrum, and a new third party.** The bindings accept Esplora only. The Ark
wallet uses Second's Esplora for its network. The profile's custom Electrum server does not apply,
because it is a different protocol. This puts Second in a position to see the wallet's onchain
addresses and the device's IP, on top of the Ark server, which already sees every VTXO. The
first-open sheet says so. A custom Esplora URL is an open question, not a v1 setting.

**D8. Bark's own onchain wallet is opened.** `WalletOpenArgs.onchain = OnchainWallet.default(...)`,
BDK at `m/86'/coin'/0'`. Boarding and fee-bumping an exit both need it. A callback wallet over the
Phoenix node's final wallet (`CustomOnchainWalletCallbacks`) was considered and rejected: it would
couple Bark to a node the UI never shows, and to a key manager the app cannot reach without one.
The user sees one balance, plus the onchain part where it is non-zero. *Board* is offered when the
onchain part is non-zero.

**D9. Bark's database is the record.** No Room table mirrors history or balance. The port reads
`history()` and follows `notifications()`. Room gets an `ArkMovementNote` table only if a later
phase needs app-side data: who a payment was to (an npub), or which group invoice it paid
(Phase 10). One store means there is never a second ledger to reconcile.

**D10. This is a behaviour change.** A seed profile with the rollout enabled and Tor off opens a
connection to Second's Ark server and Esplora on every start. It previously made none to Second.
The note's rollout section and the first-open sheet both say so. The wallet opens when the profile
becomes active, not when the wallet screen opens, for the same reason as standalone-electrum D5:
claims, renewals and pending Lightning receives must not depend on someone looking. The first-open
sheet is the consent: until it is accepted, `IdentityArk` opens nothing.

**D11. The Phoenix personal node is left alone.** It keeps starting, as it does now, and nothing
here reads it. Whether a profile with a visible Ark wallet still needs an invisible node, which
holds a connection to ACINQ, runs WorkManager jobs and is checked at activation against the nostr
key, is a separate decision. It is listed under open questions. Removing the node would touch
`setActiveWallet`, `BusinessManager` and the forget sequence, and none of that is needed to add a
wallet.

**D12. Coins are kept alive in three layers, and the deadline is shown in time:**
1. Bark's daemon, while the app is open.
2. `maintenance_delegated()` on open and on every foreground. Phase 0 found that a delegated
   refresh needs no device online during the round, and that the interactive `maintenance()` blocks
   until the next round starts, which is up to an hour on mainnet. So the app never calls
   `maintenance()`.
3. A platform job: WorkManager on Android, `BGAppRefreshTask` on iOS, nothing on desktop.

The wallet screen states the earliest deadline (`get_first_expiring_vtxo_blockheight`) as a date,
the way guardian-delay states blocks as time. Inside the refresh threshold it becomes a warning with
a *Renew now* action. A Lightning receive likewise needs the app running until it is claimed. The
receive screen says *keep Dedun open until it arrives* and claims on the next open if it did not.

**D13. nsec profiles come later, and with a seed of their own.** An nsec profile has no words. A
fresh Bark mnemonic cannot go into `seed.dat`, because `merge` would list it as a second profile
whose key no credential matches. Phase 13 stores it in `ark-seeds.dat`, keyed by the profile's
`WalletId` and encrypted under the same keystore alias, with a recovery-phrase screen of its own.
An npub profile never gets one: it cannot sign, and a wallet whose words it holds would contradict
the npub plan's *a key that cannot sign*.

**D14. Words.** User-visible strings follow the catalogue's existing usage: *onchain*, not
*on-chain*, as in *Group onchain wallet*. *Ark* is a proper noun. *VTXO* appears only on the
advanced screen. *Refresh* becomes *renew* in the UI, because *refresh* already means *reload this
screen* to a reader. Everything is in sentence case and goes through `strings.xml`.

---

## Phases

One commit each, in this order. Phases 8, 9, 10 and 11 depend only on Phase 3 and can be reordered.

### Phase 0: spikes

Each spike's result is a short section in this note, added in the commit that finishes the phase,
in the form the swap-in plan's Phase 0 used.

1. **Android packaging.** Add `bark-android:0.26.1+bark-0.8.0` to a scratch build of `composeApp`.
   Record:
   - the APK size delta per ABI;
   - whether its `.so` files are 16 KB page-aligned (Play requires it for `targetSdk ≥ 35`, and the
     app targets 37);
   - whether its JNA version conflicts with anything on the classpath;
   - whether R8 keeps the uniffi callbacks, or needs keep rules.
2. **Linux desktop, through `jvmTest`.** Open a signet wallet in a temp datadir, get an address,
   pay it from the faucet, `sync`, read `balance`, pay a signet Lightning invoice, `close`. Then
   open the same datadir twice and record the exact error. Then open it with a different phrase
   and record that error too.
3. **Recovery.** Open a fresh datadir with the phrase from spike 2, read `recovery_status()`, and
   time how long the mailbox scan takes.
4. **Delegated and scheduled refresh.** Do `refresh_vtxos_scheduled` and `maintenance_delegated`
   need the client online at the scheduled height? Read `bark/src/round` and ask on the Second
   community forum, then record the answer and its source. D12's third layer depends on it.
5. **Lightning receive with the app killed.** Issue an invoice, kill the process, pay it, wait,
   then reopen. Record whether `try_claim_all_lightning_receives` claims it, and how long the
   payer's HTLC waited.
6. **iOS route.** Try Gobley against `bark-ffi` at the pinned revision. If it generates Kotlin/Native
   bindings that link into the `ComposeApp` static framework, D4 changes. Otherwise prototype the
   Swift bridge: one method (`balance`) on a Kotlin interface implemented in `iOSApp.swift`. This
   needs a macOS host, since iOS targets are declared only there.
7. **macOS and Windows natives.** Cross-build the `bark-ffi-kotlin` cdylib for the three missing
   targets, as the secp256k1 fork does for its JNI jars, and load it through JNA's resource path.
   Record the toolchain each one needs.
8. **A proxy fork.** Measure the diff to `bark-ffi` that enables `socks5-proxy` and adds the field.
   Measuring it is enough for now: it builds nothing until the app has a proxy (D6).

**Done when** this note carries the eight answers, and D4, D6 and D12 are amended where the
answers differ from what is written here.

#### What the spikes found

Run on 2 November 2026 from a Linux x86-64 host (glibc 2.39, JDK 17), against
`bark-jvm`/`bark-android` `0.26.1+bark-0.8.0` and Second's signet server. The probe was a
throwaway Gradle project outside the repo, opening wallets in temporary datadirs.

| spike | answer |
|---|---|
| 1. Android packaging | The AAR carries `libbark_ffi_kotlin.so` for `arm64-v8a` (13.8 MB), `armeabi-v7a` (10.7 MB), `x86` (14.8 MB) and `x86_64` (15.1 MB) uncompressed: 31.8 MB as a compressed AAR, so about 8 MB per ABI in a split APK. **Every `LOAD` segment is aligned to `0x4000`**, so 16 KB pages are fine. It depends on `jna:5.15.0` (no other JNA on the app's classpath), `kotlin-stdlib:1.9.24` and `kotlinx-coroutines-android:1.8.1`, all of which the app's newer versions supersede. R8 keep rules are checked in Phase 2 against the release build |
| 2. Linux desktop | `Wallet.open` against signet takes about 4 s on a first open and creates the datadir. Address, sync (0.7 s), balance, BOLT11 (0.2 s) and a BIP321 URI with an amount (Ark address, BOLT11 and no onchain address) all work with no funds. **The server refuses a `userAgent` with no `/`** (`invalid x-user-agent: no slash present`), so it is `dedun/<version>`. A second open of an open datadir fails with `failed to instantiate platform default lock manager: another process is already using datadir … (holder PID: <our own pid>)`. Reopening a datadir with other words fails in the onchain wallet first, with `Descriptor mismatch for External keychain`, not "incorrect mnemonic". Errors cross the FFI as **one class, `uniffi.bark.Exception.Inner`, carrying a flattened message**, so `ArkError` classifies by message, with the messages above as test fixtures. The funded half (a faucet payment, a Lightning payment) is moved to Phase 12's regtest: the faucet is a web app, not an API |
| signet's parameters | `arkInfo`: rounds every 300 s; VTXO lifetime 144 blocks (about a day on signet, against 28 days on mainnet); exit delta 12; minimum board 10,000 sat; maximum VTXO 1 BTC; Lightning receive fee 0 at 10,000 sat |
| 3. Recovery | A fresh datadir opened with the same words runs the mailbox scan inside `open`, which then takes about 4.4 s, and reports `Completed(isComplete = true)` with empty buckets for an unfunded wallet. `recoveryStatus()` on an open that did not create the wallet is `NotRun`, as documented |
| 4. Delegated refresh | **Delegated means non-interactive.** `maintenance_delegated` syncs, progresses pending rounds and submits every VTXO due for refresh to the *next* round in delegated mode (`maybe_schedule_maintenance_refresh_delegated`, `bark/src/lib.rs:1910`). The server includes the participation without the device online, and the device collects the new VTXOs on its next sync (`progress_delegated`, `bark/src/round/mod.rs:744`). The interactive `maintenance()` instead **waits for the next round to start** (`maintenance_refresh`, `:1984`): up to an hour on mainnet. So the foreground and background paths call `maintenance_delegated`, never `maintenance`. Hand-scheduling `refresh_vtxos_scheduled` adds nothing. D12 is amended |
| 5. Lightning receive with the app killed | Not run: it needs funds. Moved to Phase 12 |
| 6. iOS route | **Gobley is out.** Its latest release, `gobley-uniffi-bindgen` 0.3.7, pins `uniffi_bindgen =0.29.4`, and `bark-ffi` is built with uniffi 0.31.1. The generated scaffolding has to match the Rust side's uniffi version. The Swift bridge in D4 stands. The prototype needs a macOS host, which this spike did not have, so Phase 8 starts with it |
| 7. macOS and Windows natives | This host has the `aarch64-apple-darwin` and `x86_64-apple-darwin` rust targets, but no Apple linker (osxcross) and no MinGW. The libraries have to be built on macOS and Windows runners, not cross-built here. Phase 11 builds them in CI |
| 8. A proxy fork | Small: add `"bark/socks5-proxy"` to `bark-ffi`'s uniffi feature list (`bark`'s feature is `socks5-proxy = ["bark-bitcoin-ext/rpc-socks5-proxy", "bark-server-rpc/socks5-proxy", "http"]`), add `socks5_proxy: Option<String>` to `bark-ffi`'s `Config`, and map it in its `From` impl. That is about ten lines, plus building and publishing the bindings ourselves. That cost is the reason not to do it before the app has a proxy |

### Phase 1: the port, pure

`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/ark/`:
- `ArkWalletRollout.kt`: D5's object, with `capFor(network)` and `endpointsFor(network)`.
- `ArkNetwork.kt`: `Signet`, `Mainnet`, `Regtest`, mapped to Bark's enum only inside adapters.
- `ArkWallet.kt`: the port, all `suspend` unless noted:
  - `balance(): ArkBalance` (spendable, pending Lightning send, claimable Lightning receive,
    pending board, pending exit, onchain);
  - `history(): List<ArkMovement>`;
  - `notifications: Flow<ArkEvent>` (property);
  - `newArkAddress()`;
  - `receive(amount: Satoshi?, description): ArkReceiveRequest` (BIP321 URI, Ark address,
    optional BOLT11);
  - `parse(text): ArkPaymentRequest`;
  - `estimateFee(request, amount)`;
  - `send(request, amount, comment): ArkSendResult`;
  - `maintenance()`;
  - `firstExpiryHeight()`;
  - `tipHeight()`;
  - `recoveryStatus()` (non-suspend);
  - `close()`.
- `ArkPaymentRequest.kt`: a sealed type with `ArkAddress`, `Bolt11`, `Bolt12Offer`,
  `LightningAddress`, `Lnurl` and `Onchain`, each carrying whether it needs an amount.
- `ArkMovement.kt`: id, status (pending / succeeded / failed / cancelled), direction, rail (Ark,
  Lightning, onchain, board, offboard, renew, exit), amount, fee, counterparty text, created and
  completed times. `ArkMovementMapping` turns Bark's `subsystem_kind` and `status` strings into
  these.
- `ArkError.kt`: `ServerUnreachable`, `InsufficientFunds`, `AmountRequired`, `AboveCap`,
  `InvalidRequest`, `DatadirLocked`, `WrongSeed`, `Unavailable(reason)` and `Other(message)`.
- `ArkConfig.kt`: builds the 18-field config as a plain data class. It refuses with
  `Unavailable(TorOn)` when the profile's Tor preference is set.
- `ArkUnavailability.kt`: `TorOn`, `UnsupportedPlatform`, `NotConsented`, `NoSeed` and
  `RolloutOff`. Each one maps to one string.

Tests, in `commonTest`:
- the movement mapping, over every `subsystem_kind` and `status` string `bark` 0.8.0 emits (taken
  from `bark/src/movement`);
- cap arithmetic;
- endpoint selection;
- the Tor refusal;
- the expiry-height-to-date conversion, reusing guardian-delay's blocks-as-time helper.

In `jvmTest`: `FakeArkWallet` with scriptable balance, history and events.

**Done when** `:composeApp:jvmTest` passes and nothing outside `wallet/ark/` changed but the string
catalogue.

### Phase 2: the binding on Android and Linux

- `settings.gradle.kts`: add the GitLab registry
  (`https://gitlab.com/api/v4/projects/78057981/packages/maven`) with
  `mavenContent { includeGroup("tech.second.bark") }`, so no other coordinate can resolve from it.
- `gradle/libs.versions.toml`: `bark = "0.26.1+bark-0.8.0"` as an exact version, plus `bark-android`
  and `bark-jvm`. If the repo adopts dependency verification, add these checksums first.
- One adapter, `BarkArkWallet.kt`, in a directory added as a source dir to both `androidMain` and
  `jvmMain`. The generated `uniffi.bark` API is the same on both, so the adapter is written once.
  It maps types and errors, runs `notifications()` into a `callbackFlow`, and imports `uniffi.bark`
  names one by one, never with a star import.
- `expect fun openArkWallet(network, words, config, datadir): ArkWallet`:
  - Android and JVM call the adapter;
  - iOS throws `Unavailable(UnsupportedPlatform)` until Phase 8;
  - JVM on anything but Linux x86-64 throws the same, checked through `os.name`/`os.arch` before JNA
    loads anything.
- `AndroidManifest.xml` already has `INTERNET`.
- R8 keep rules from spike 1, if it found any are needed.

Tests: `ArkSignetJvmTest`, gated by `DEDUN_ARK_SIGNET=1` like the regtest tests and skipped
otherwise. It is spike 2 made permanent, through the port: open, address, balance, a self-payment
from a second wallet, history shows it, close, reopen. A second test opens the same datadir twice
and asserts `DatadirLocked`.

**Done when** both targets compile, `jvmTest` passes with the gate off, the signet test passes with
it on (on Linux x86-64), and the debug APK starts on an emulator. Nothing calls the binding yet.

### Phase 3: one wallet per active profile

- `wallet/ark/IdentityArk.kt` is held for the app's life next to `IdentityElectrum` in
  `DedunNavHost`. `walletFor(identity): StateFlow<ArkWalletState>` takes one of these values:
  - `Unavailable(reason)`;
  - `Opening`;
  - `Recovering(status)`;
  - `Open(wallet)`;
  - `Failed(error)`.

  On an identity change it closes the previous wallet (`stop_daemon_wait`, `close`) under the
  mutex, then opens the next one. `Recovering` is shown when the datadir did not exist before this
  open, as on a restore or a first install.
- The words come from `StoredIdentity.Mnemonic.userWallet.words`, through the startup screen's
  `Mnemonic` branch. They are passed to `IdentityArk` and never held in a `StateFlow`, logged or put
  in an exception message.
- Consent: an internal per-profile preference, `arkConsented`, stored next to the Tor flag in
  `InternalPrefs`. Until it is set the state is `Unavailable(NotConsented)` and nothing opens.
- Switch: `switchToIdentity` already clears the identity, which is the edge `IdentityArk` follows.
  A test asserts the close happens before the next open.
- Forget: the `ForgetIdentity.forget` sequence (`identity/ForgetIdentity.kt:34`) stops the wallet,
  then refuses with a new `ForgetProblem.ArkFundsOrExits` if the balance is non-zero or exits are
  pending, unless the user confirmed *Forget anyway*. It then deletes the profile's `ark/` datadirs
  after the seed. The confirmation names the amount.
- Restore: a sign-in from a phrase reaches `Recovering`. `RecoveryStatus.Failed` or `isComplete ==
  false` is a `Failed` state with a retry that calls `recover_vtxos` on the report's failed ids.

Tests in `jvmTest`, over `FakeArkWallet` and a recording opener:
- switch order;
- Tor refusal;
- no-consent refusal;
- forget refusal and forget anyway;
- a restore that reports partial recovery;
- an nsec or npub identity resolving to `Unavailable(NoSeed)`;
- reuse of the `IdentitySwitchJvmTest` harness.

**Done when** the tests pass and, with the signet gate on, a profile switch between two seed
profiles leaves exactly one datadir locked at every step.

### Phase 4: the wallet on the profile, and its screen

- `ActiveProfileScreen`: a *Wallet* row above `memberWalletItems`. It shows the spendable balance
  and a second line that is one of:
  - *Pending: …*;
  - *Renew by 14 Dec*;
  - the `Unavailable` reason;
  - *Opening…*.

  The row is a `ListItem` with `minimumInteractiveComponentSize`. For `NotConsented` it opens the
  first-open sheet, which says, in the catalogue's words:
  - what Ark is;
  - that Second runs the server;
  - that coins must be renewed every few weeks, and that the app does this when it can;
  - what the server can see;
  - that the twelve words restore the balance but not the history.

  It has *Turn on* and *Not now*.
- `ArkWalletRoute(activeUserPublicKey)` and `ArkWalletScreen`: a pushed screen with
  `NavigateBackButton`. It shows:
  - a balance header;
  - *Receive* and *Send* buttons;
  - the deadline line;
  - the history list (`LazyColumn`, newest first, a pending section on top).

  It has four states through `ScreenStateTransition`, `EmptyState` with its own message (*No
  payments yet*) and `ErrorState` with a retry. It uses `readableContent()`, and two panes from
  `Breakpoint.Expanded` with the movement detail on the right.
- `ArkMovementRoute` and `ArkMovementScreen`: a detail view with the amount, fee, rail, status,
  times, counterparty and, for Lightning, the payment hash. Ids can be copied.
- `ArkWalletViewModel` reads `IdentityArk` and folds `notifications` into the list.
  `ChannelLagging` triggers a full re-read.
- `RecoveryPhraseScreen`: one added sentence, saying these words also restore the wallet's balance.

Tests: compose tests over `FakeArkWallet` for each state of the row and the screen. A view model
test checks that a `MovementUpdated` replaces the row in place. `m3Audit` stays at budget.

**Done when** a signet profile shows its balance and history on the profile tab on Android and
Linux desktop, and `./gradlew check` passes.

### Phase 5: receiving

`ArkReceiveRoute` and `ArkReceiveScreen` (pushed):
- An optional amount field. Without an amount the QR is a BIP321 URI with the Ark address and the
  onchain address, which accepts any Ark or onchain payment. With an amount it also carries a
  BOLT11, and the Lightning receive fee (`estimate_lightning_receive_fee`) is shown under the field
  before the invoice is made.
- The QR comes from `qrose`, with the `m3-color-exempt` comment its modules need. Copy and Share
  are offered, and each text form has its own tab: *All*, *Ark*, *Lightning* and *Onchain*.
- On mainnet the cap check runs before an invoice is issued (D5), with `ArkError.AboveCap` shown
  inline.
- While a Lightning receive is pending, the screen says *Keep Dedun open until it arrives*.
  `MovementCreated` or `MovementUpdated` with a matching payment hash completes it, with a snackbar
  through `rememberNotifier`.
- On every wallet open, `IdentityArk` calls `try_claim_all_lightning_receives`. It cancels pending
  receives older than their invoice expiry.
- *Board*: shown only when the onchain balance is non-zero. It confirms an amount and a fee
  estimate, and shows the pending board until it confirms.

Tests: view model tests for the amount, fee and cap paths, and the pending-to-complete transition
from a fake event. A signet test covers a Lightning self-payment from a second wallet.

**Done when** a signet profile receives through each of the three rails and the history says which.

### Phase 6: sending

`ArkSendRoute` and `ArkSendScreen` (pushed):
- One field: paste or scan. `parse` decides the rail, the form asks for an amount only when the
  request needs one, and a Lightning address or LNURL may carry a comment.
- Review: amount, `estimateFee`, the total and what remains. The rail is named (*Ark*,
  *Lightning*, *Onchain*) because the fees and the settlement time differ. On mainnet the cap is
  checked here.
- *Send* calls `send`, then shows the movement's detail screen in its pending state, which
  `MovementUpdated` moves to succeeded or failed. A failed Lightning send that Bark marks stuck
  (`stuck_failed_lightning_sends`) is explained, not retried silently.
- An onchain send is an offboard. Its review says it settles in the next round, roughly within the
  hour on Second's server, because the user would otherwise expect a broadcast.
- The scanner is whatever the app already uses for npubs and invoices, or none: if no camera path
  exists, paste-only is the v1 and scanning is an open question.

Tests: view model tests covering each request type's amount rule, the cap refusal, insufficient
funds, and the pending-to-failed path. A signet test covers Ark-to-Ark and Lightning sends between
two wallets.

**Done when** a signet profile can pay each request type it can parse.

### Phase 7: keeping coins alive

- **Foreground:** `IdentityArk` calls `maintenance_delegated()` on open and on every
  `AppLifecycle` foreground, at most once an hour.
- **Android:** `ArkMaintenanceWorker`, a `CoroutineWorker` scheduled periodically (daily, with
  network required) from `schedulePlatformLogic`. Through `IdentityArk` it opens the *default*
  profile's wallet if it is not already open, runs `maintenance_delegated()` and
  `try_claim_all_lightning_receives`, and closes only what it opened. It needs the seed, which
  means decrypting `seed.dat` headless. `ChannelsWatcher` already does this through
  `BusinessManager`, so the worker reuses that path.
- **iOS:** a `BGAppRefreshTask` with the same body, registered in `iOSApp.swift`, with
  `BGTaskSchedulerPermittedIdentifiers` in `Info.plist`. iOS gives no guarantee it runs, so the
  in-app warning stays the real safeguard.
- **Desktop:** nothing scheduled, as `Phoenix.jvm.kt:104-124` argues for the node. The warning
  carries it.
- **The warning:** inside the refresh threshold (default from Bark's config, shown in days), the
  profile row and the wallet screen turn to an error-container style with *Renew now*. That
  shows `estimate_refresh_fee`, then calls `refresh_vtxos_delegated` for the VTXOs due and shows
  the renewal as pending until a sync collects it. Past expiry the screen says the coins now depend on the
  server and offers the recovery call.

Tests: the threshold-to-warning mapping across tip heights; the worker opening and closing only
what it opened, with a fake `IdentityArk`; the hourly debounce.

**Done when** a signet wallet with a VTXO near expiry (shortened through regtest in Phase 12)
renews from the foreground path, and the Android worker runs under `adb shell cmd jobscheduler run`.

### Phase 8: iOS

If spike 6 chose the Swift bridge:
- `commonMain` gets `interface ArkEngine`, the port in callback form, since Swift cannot implement
  Kotlin `suspend` functions directly. It is exported from the `ComposeApp` framework.
- `iosMain` gets `ArkEngineRegistry.register(engine)`.
- `iosApp` adds the `Bark` Swift package through Xcode, at the exact version the Kotlin binding
  pins (`0.26.1`), and
  implements `ArkEngine` in `BarkEngine.swift` over `Wallet.open` and friends.
- `iosMain`'s `openArkWallet` wraps the registered engine in an `ArkWallet` with
  `suspendCancellableCoroutine`.

If Gobley won, this phase deletes the Phase 2 adapter's duplication instead, and iOS uses the
common adapter.

The datadir lives under Application Support, which is excluded from iCloud backup by flag. A
VTXO database restored onto another device behind the server's back is worse than none.

Tests: an `iosSimulatorArm64Test` smoke test that registers a fake engine. The device check is
manual, on signet.

**Done when** the Phase 4 to 7 flows work on an iOS simulator against signet.

### Phase 9: getting out: offboard and emergency exit

An *Advanced* screen off the wallet screen, behind a sentence that says when it is needed:
- the VTXO list: amount, expiry date and state;
- *Move everything onchain* (`offboard_all`, to an address the user pastes, with the fee shown);
- *Emergency exit*, for when the server is unreachable:
  - `estimate_emergency_exit_fee` first;
  - then `start_exit_for_entire_wallet`;
  - then a progress view that calls `progress_exits` on each open and shows each VTXO's state;
  - then *Claim* once `all_exits_claimable_at_height` has passed, to an address the user gives.

The exit needs onchain funds for CPFP (D8). The screen shows the onchain balance and an address to
fund it.

Forget (Phase 3) already refuses while exits are pending. This phase adds the same refusal to
*switch profile* only as a warning: a switch closes the wallet, and an exit makes no progress while
it is closed.

Tests: view model tests over a fake exit sequence. Regtest coverage comes in Phase 12. Signet exits
take hours, so they are a manual check.

**Done when** a regtest wallet exits and claims with captaind stopped.

### Phase 10: the wallet and the groups

Two small bridges, each optional and each its own commit:
- **Pay a group invoice from my wallet.** Where the app shows a member a deposit invoice
  ([member-deposits.md](./member-deposits.md)), add *Pay from my wallet* when the profile's Ark
  wallet is open and can cover it. This is `send` with the parsed BOLT11. An `ArkMovementNote` row
  records the room, so the history line reads *Deposit to Treasury*.
- **Use my wallet's address as my onchain payout destination.** In the payout-destination form
  ([group-payout-destination.md](./group-payout-destination.md)), offer the Ark wallet's onchain
  BDK address, which is unused by construction, as a one-tap fill. A Lightning address is not
  offered: Bark has none without an LNURL server, which is out of scope.

**Done when** each bridge works on regtest against the group Lightning wallet's harness.

### Phase 11: macOS and Windows desktop

Build the `bark-ffi-kotlin` cdylib for `darwin-aarch64`, `darwin-x86-64` and `win32-x86-64` with
spike 7's toolchain. Publish each as a jar of JNA resources, the way secp256k1's per-OS JNI jars
are, in a small included build or a submodule, pinned to the same `bark-ffi` revision as the
binding. Then drop the platform check in `openArkWallet` for those three targets. Linux arm64 is
listed and left out unless asked for.

**Done when** the signet test passes on a macOS host, and the `Dmg` and `Msi` packages open a
wallet.

### Phase 12: end to end on regtest, then mainnet

- `testing/regtest/`: add captaind, its PostgreSQL and Core Lightning with the hold plugin, from
  `bark`'s `contrib/docker/docker-compose.yml` at `bark-0.8.0`. Use the same project, bound to
  localhost, on ports beside the existing ones. `regtest.sh up` funds captaind's round wallet.
  `regtest.sh ark …` passes commands through. Add a short VTXO lifetime so expiry is reachable in
  minutes. The README gains a section.
- `ArkRegtestJvmTest`, gated by `DEDUN_REGTEST=1`. Its flow:
  1. board;
  2. Ark send between two profiles;
  3. Lightning send to the eclair payer, and receive from it;
  4. a renewal near expiry;
  5. offboard;
  6. an exit with captaind stopped, then a claim;
  7. a fresh datadir restoring the balance from the phrase.
- Then mainnet: flip `ArkWalletRollout.NETWORK` in its own commit, with the cap. That commit
  rewrites this note's status paragraph and adds the table of where the build chose differently.

**Done when** the regtest run passes, and one mainnet receive, send and renewal have been done by
hand on Android, with their movements recorded in this note.

### Phase 13: an Ark wallet for an nsec profile

D13's design: generate a mnemonic, write it to `ark-seeds.dat` (keyed by `WalletId`, under the
same keystore alias as `seed.dat`, through a new `ArkSeedManager` next to `SeedManager`), and open
it through the same `IdentityArk`. Add a phrase screen for it, reached from the wallet's advanced
screen. Forget deletes it with the datadir. `StoredIdentity.merge` never reads the file, which is
the point.

**Done when** an nsec profile can turn the wallet on and back it up, and `merge` lists the same
profiles as before.

---

## What this plan does not do

- **Continuous backup of Bark's database.** Second recommends an encrypted, off-device snapshot
  after every state change. Without it, a lost device restores the balance through the server's
  mailbox but loses the history and any in-progress exit. It is its own plan: platform cloud
  storage, a key derived from the words, and restore. The first-open sheet and the recovery-phrase
  screen state the limit until then.
- **A Lightning address** for the wallet. That needs an LNURL server, such as Second's `arkzap-me`.
- **Tor**, until the app has a proxy (D6).
- **A custom Ark server or Esplora URL** in settings. The rollout object holds them.
- **Removing or surfacing the Phoenix personal node** (D11).
- **Fiat display.** The node's fiat preferences are not read.
- **Payment authentication.** The app's lock is a desktop passphrase gate and *coming soon* on
  mobile, and sending from this wallet is no more gated than the rest of the app.

## Open questions

1. Does a seed profile still need its Phoenix node once it has a visible Ark wallet? (D11)
2. Should the wallet be on by default for new seed profiles once mainnet is on, or stay behind the
   first-open sheet for good? (D10)
3. Is the 100,000 sat cap right for a wallet whose money is the user's own and not a group's?
4. ~~Is there a camera path for scanning?~~ No: Phase 6 shipped paste-only. A scanner is its own piece of work, for every send screen in the app.
5. Is Second's server the only one offered, or should a profile be able to choose another Ark
   server? An Ark address is per server, so a choice also means a migration story.

## Checking the work

Every phase:

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

From Phase 2, on Linux x86-64, with network access:

```bash
DEDUN_ARK_SIGNET=1 ./gradlew :composeApp:jvmTest --tests '*ArkSignetJvmTest'
```

From Phase 12:

```bash
testing/regtest/regtest.sh up
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*ArkRegtestJvmTest'
```
