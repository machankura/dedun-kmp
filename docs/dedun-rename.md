# Renaming Bennu to Dedun

The app becomes Dedun everywhere a user can read it, and its code moves from
`com.machankura.bennu` to `com.machankura.dedun`. This is the fifth name this tree has
had. Mantra became Curated, Curated became Curare (`e6aee792`), and Curare became Bennu
(`b75d4b42`). Spelling the new name is easy. The real work is sorting the 7,722
occurrences of the old one, in 931 files, into three groups: the brand, which changes;
the protocol, which must not change; and the identity a store or an operating system
holds the app by, which changes only after a decision about existing installs.

**Built, but for Phase 7, which D5 made unnecessary.** Written against `4f1b4a43` on `claude/bennu-dedun-rename-a9d7af`.
Read these first:

- `git show e6aee792`. Its message records what the Curare rename found: a blanket sweep
  makes some history sentences false, the generated-resources package follows the root
  project name, and the schema directory moves with the database class.
- `git show b75d4b42`, the Bennu rebrand, and its list of names "left alone on purpose".
- The comment on `APP_DIR_NAME` in `composeApp/src/jvmMain/.../PlatformContext.jvm.kt`.
- [lightning/restored-device-marks.md](./lightning/restored-device-marks.md). On Android and
  iOS, a Dedun install restored from a Bennu user's seed is a restored device with an
  empty ledger, which is exactly the case that document covers.

### Where the build stands

One commit per phase. The phases below are kept as written. Where the build chose
differently, this table says so.

| phase | state |
|---|---|
| 0 | done. `DomainTagsPinnedTest` pins the eight tags that had no vector. Each vector was checked against a separate BIP340 tagged hash, computed in Python over the literal tag. Baseline at `4f1b4a43`: the audit reads 1090 `stringResource` sites and 191 `Icon(` sites with every budget met, and `jvmTest` runs 1650 tests: 2 skipped (the regtest runs), none failed |
| 1 | done, `b00b50f5`. 896 files are recorded as renames. Git shows 24 very small files as delete-and-add, because the package line is most of their content. Room found the 35 schemas at the new directory and wrote no 36th. 1650 tests, none failing |
| 2 | done. The generated package is `dedun.composeapp.generated.resources` (confirmed from the build output, not inferred). 86 files and the two extractors were rewritten. The audit still reads 1090 and 191 |
| 3 | done. The rename also covers each type's logger tag, since a tag is the class name. 1650 tests, none failing |
| 4 | done. `ProfilePreviewScreenJvmTest` renders one of the changed strings and passes. The desktop window and launcher labels were not looked at on screen: no display was available to the build |
| 5 | done. The merged debug manifest reads `com.machankura.dedun.android.debug`, with `DedunApplication` and `MainActivity` under `com.machankura.dedun.android`. `packageDeb` builds `com.machankura.dedun.desktop_1.0.0_amd64.deb`. No `upgradeUuid` was pinned, because under D5 there is no Bennu MSI to upgrade. The iOS bundle id was changed but not built, because this was a Linux machine. Registering the App ID is still the owner's to do. `versionCode` stays at 9: Play starts counting afresh for a new listing anyway |
| 6 | done. One addition to the plan: Phase 3's sweep had turned HomeScreen's list of former themes into "`CurareTheme`, is `DedunTheme`", which drops Bennu. That is the falsified-history case from constraint 4, and it is fixed by hand. A link check over the docs found 50 targets missing. None was caused by this rename: `dead-code.md` and `long-running-sync.md` link from `docs/` without `../`, and the rest point at files deleted before `4f1b4a43`. Two of three full runs each had one timing failure (`IdentitySwitchJvmTest`, then `LiveSubscriptionReconcileTest`, the second from another test's write to a closed database). That is the flake `frost-session-resend.md:523` describes. Each test passed when run on its own, and the third full run passed |
| 7 | not built (D5) |
| 8 | done. The final search outside `docs/` finds only the twelve tags and the formulas in their doc comments, the history sentences and the note above `applicationId`. Inside `docs/` it finds the tags, two history sentences, and the `bennu-lightning` branch named in one plan's header. On a fresh `dedun-regtest` network, `GroupLightningRegtestJvmTest` passes: receive, buy liquidity, pay, close, sweep. That is the only check that runs the twelve tags against a real LSP. `LiquidityPurchaseRegtestJvmTest` passes alone on its own fresh network. Run straight after the group test on the same network, it timed out waiting for its on-the-fly channel. That combination was not tried on the tree before the rename. The test uses a stock node and none of the tags. Not checked: an iOS build, which needs a Mac, and the window and launcher labels on screen |

## What the name is attached to

| what | where | count | verdict |
|---|---|---|---|
| Kotlin packages | `composeApp/src/{commonMain,androidMain,iosMain,jvmMain,commonTest,jvmTest}/kotlin/com/machankura/bennu/` | 884 files (631, 11, 10, 11, 77, 144) | move |
| Room schema directory | `composeApp/schemas/com.machankura.bennu.compose.database.BennuDatabase/` | 35 JSON files, versions 1–35 | move, unmodified |
| Generated resources package | `bennu.composeapp.generated.resources`, derived from `rootProject.name = "Bennu"` | 1,062 imports in 86 files | follows the root project |
| Brand-named types | `BennuDatabase` (578 uses), `BennuTheme` (138), `BennuApp`, `BennuGlobal`/`bennuGlobal`, `BennuApplication`, `BennuConverters`, `BennuNavHost`, `BennuNavigationSuite`, `BennuShapes`, `BennuDatabaseConstructor`, `Readiness.NotYetOnBennu`, `defaultBennuDir` | 9 files named for the brand | rename |
| Strings a user reads | `strings.xml` (11 lines, including keys `bennu`, `s_hasn_t_set_up_messaging_on_bennu_yet` and `bennu_will_delete_the_nsec_for_s_from_this`), `androidMain/res/values/strings.xml`, the desktop window title and *Unlock Bennu* in `Main.kt`, and two literal error messages in view models | about 17 | rename |
| Tags this app writes on the wire | `UserAgent.APP_NAME`/`CLIENT_NAME = "Bennu"` (the relay list's `client` tag), `DatabaseMarmotRepository.CLIENT_NAME = "bennu"` (the kind:30443 `client` tag) | 3 | rename (D4) |
| **Hash domain separators** | 12 `"bennu/…/v1"` constants in `GroupLightningDerivation`, `GroupWalletDerivation`, `GuardianDerivation` (2), `IcebergDkg` (5), `GroupLightningCoSigner` (iceberg pool), `MemberWalletDerivation`, `MemberWalletRequestEvent` (possession) | 12 | **keep** (D2) |
| Store and OS identities | Android `applicationId`, iOS `PRODUCT_BUNDLE_IDENTIFIER`, desktop `nativeDistributions.packageName` | 3 | rename (Phase 5) |
| iOS product | `PRODUCT_NAME=Bennu`, `Bennu.app` in `project.pbxproj` (3 lines) | 4 | rename |
| Desktop data directory | `APP_DIR_NAME = "curated"` | 1 | **keep** (D3) |
| Developer tooling | `BENNU_REGTEST`, `BENNU_SPLICE_DELAYS`, the `bennuRegtest` task input, compose project `bennu-regtest`, node aliases `bennu-lsp`/`bennu-payer`, test temp-dir prefixes, `UI=` paths in seven `docs/scripts/` tools | about 40 | rename |
| History sentences | the `mantra/` notes in `ChillDkgRitualManager`, `SharedKeyDerivation` and `Relays.kt`; the title comment in `HomeScreen`; the `APP_DIR_NAME` note; `material-design-conformance.md:660`; the `strings.xml` header | 8 places | edit by hand |
| Docs | 35 files, 338 occurrences, mostly source paths | 338 | paths change; dated prose stays (D6) |

Some values do not carry the brand, so nothing has to be done to them. They are listed
here so nobody spends time checking them again. The database file is `aux.db` on every
platform. The Android DataStore is `globalprefs`. The iOS keychain `serviceName` is
`Machankura` and its `accessGroup` is `null`. The relay host is `ephemeral.mantra.press`.
The two `mantra/` tags stay as they are. Nothing in the Gitea workflow names the app.

## The constraints

### 1. A hash domain separator is a protocol, not a brand

`ChillDkgRitualManager` puts it this way about `mantra/`: the tag "is an input to the hash,
so renaming it derives a different host key from the same seed". The twelve `bennu/` tags
work the same way. If `bennu/group-wallet/account/v1` changes, every group wallet derives a
different address and its coins stay at the old ones. If `bennu/iceberg-dkg/*` changes,
existing members can no longer verify a group Lightning key's transcript. If
`bennu/member-wallet/possession/v1` changes, a guarded-wallet request no longer verifies
against the requests already on the relays. Renaming any of them is a protocol fork.

Only four of the twelve are pinned by a test vector: `GroupLightningDerivationTest`,
`GroupWalletDerivationTest` and `GuardianDerivationTest` (two tags). The five in
`IcebergDkg`, the iceberg pool tag, `MemberWalletDerivation.ID_TAG` and
`POSSESSION_HASH_TAG` have no vector. A sweep that changed them would pass every test in
the tree. Phase 0 adds the missing vectors.

### 2. An application id is an identity, and the data does not follow it

On Android, the `applicationId` names the sandbox, `/data/data/<applicationId>`. On iOS,
the bundle identifier names the container and the default keychain access group. A
Dedun install under `com.machankura.dedun.*` is a different app to both operating systems.
It is also a different app to Play and to the App Store: a new listing, with no update path
from Bennu. It starts empty. The following do not carry over:

- `aux.db`: chat history, Marmot (MLS) group state, DKG and FROST sessions, the Iceberg
  co-signing ledgers, and the group wallets' records;
- the DataStore preferences and the Phoenix wallet's own state;
- the encrypted seed and nostr keys, wherever the platform keeps them.

The desktop app behaves differently. Its data lives in `APP_DIR_NAME`, which stays
`curated` (D3), so a Dedun desktop build opens the Bennu desktop data where it already
is. Changing `nativeDistributions.packageName` does change what the installer registers.
See *Risks*.

### 3. Two installs, one seed

On Android, Bennu and Dedun can be installed side by side, and a user who restores their
phrase into Dedun without uninstalling Bennu has two devices with one identity. For nostr,
that is untidy. For the Lightning wallet and the group co-signer, it is the hazard that
[restored-device-marks.md](./lightning/restored-device-marks.md) and
[force-close-revoked-commitment.md](./lightning/force-close-revoked-commitment.md) describe:
a co-signer answering from an empty ledger, and a node that can be made to sign an old
state. The rename does not create this risk, because any reinstall from seed does the same.
It does make the risk likely, because the old app keeps running after the user has
"upgraded".

### 4. A global sweep edits the sentences that describe earlier renames

`e6aee792` found that "survived the rebrand to Curated" turned into a statement about a
change that had not happened when it was written. This tree has eight such places (listed
in the table above). They are edited by hand, after the sweep, and "three rebrands" becomes
"four".

### 5. Two directory names are derived, not declared

Compose Resources derives the generated `Res` package from `rootProject.name`. If the root
project name changes and the imports do not, 86 files break at once. Room names the schema
directory after the `@Database` class's fully qualified name. If the package or the class
changes, the directory has to move with them, or Room writes a second schema 35 beside the
first and the two migration tests that hardcode the path (`LightningProtocolLinesMigrationJvmTest:31`,
`RemoveDocumentsMigrationJvmTest:45`) read the old one. The entities are still `Mantra*` or
otherwise brand-free and declare no `tableName` that mentions Bennu, so **no table is renamed**.

## Decisions

**D1. The identifiers.** Packages become `com.machankura.dedun.{compose,android,desktop}`.
The Android `namespace` and `applicationId` stay one value, `com.machankura.dedun.android`,
and debug builds are `com.machankura.dedun.android.debug` through the existing suffix. The
iOS bundle id is `com.machankura.dedun.ios$(TEAM_ID)`, the same pattern as today. The
desktop `packageName` and `mainClass` follow the package. The root project is `Dedun`, so
generated resources are `dedun.composeapp.generated.resources`.

**D2. The twelve `bennu/` tags stay byte-identical.** They get the same treatment as the
`mantra/` tags: a comment at each saying the prefix has survived a rebrand on purpose and
what renaming it would break. A fork to `dedun/` is a protocol version, and if it is ever
wanted it belongs in its own plan, with `v2` tags and a migration.

**D3. The desktop data directory stays `curated`.** The reasoning is the one already in
`PlatformContext.jvm.kt`. Moving it is a first-run migration, not part of a rebrand, and
the comment gets one more name in its list.

**D4. The `client` tags become `Dedun` and `dedun`.** The Bennu rebrand changed them, which
is precedent. Nothing in the tree reads a `client` tag back. `UserAgent.CLIENT_NAME` and
`DatabaseMarmotRepository.CLIENT_NAME` are only ever written, so other clients see a new
name and no behaviour changes. Old Bennu installs will keep publishing `bennu`, which is
harmless.

**D5. Existing Android and iOS installs: not supported. Decided by the owner.** Dedun's users
are new users. Bennu installs are not migrated, no final Bennu release is shipped, and
Phase 5 lands with the rest of the phases. Phase 7 is therefore reduced to the one note in
*Phase 7* below. Constraint 3 still applies to anyone who installs both apps. It is
documented here, but nothing in the app handles it.

**D6. Docs: change the paths and the present tense, keep the dated prose.** A source path
in a doc is a link, and it changes so the link still resolves. A sentence in a plan written
"against `84984ca8` on `bennu-lightning`" is a record, and it stays as written. The Bennu
rename handled `jvm-target.md` the same way, and that file still says Curare. The
living documents change throughout: `CLAUDE.md`, `README.md`, `docs/README.md` (whose title
still says "curare docs") and `material-design-conformance.md`.

**D7. Developer-facing names change with no aliases.** `BENNU_REGTEST` becomes `DEDUN_REGTEST`,
`BENNU_SPLICE_DELAYS` becomes `DEDUN_SPLICE_DELAYS`, the compose project becomes
`dedun-regtest`, and the aliases become `dedun-lsp` and `dedun-payer`. Accepting the old
variable as well would leave a Bennu name in the tree permanently, in exchange for saving
one shell edit per developer.

## Phase 0 — pin what must not change, and record the baseline

**Half a day. No product code.**

1. Add a pinned vector for each unpinned tag. Use a fixed input and assert the output hex,
   the way `GuardianDerivationTest` does:
   - `IcebergDkgTest`: one vector each for `TAG_SESSION`, `TAG_CONTRIBUTION`, `TAG_COMMIT`,
     `TAG_SEED` and `TAG_TRANSCRIPT`, computed through the public functions that use them.
     If a tag can only be reached through a whole ceremony, pin the ceremony's output for
     a fixed seed instead.
   - `GroupLightningCoSigningJvmTest` or a new common test: the pool tag in
     `GroupLightningCoSigner:83`.
   - `MemberWalletDerivationTest`: `ID_TAG`.
   - `MemberWalletRequestEventTest`: `POSSESSION_HASH_TAG`.

   Compute each vector on the current tree and commit the vectors before any rename. After
   that, a sweep that touches a tag fails `jvmTest` instead of failing silently.
2. Run `git submodule update --init` and then `git -C lightning-kmp-app grep -i bennu`. The
   submodule is not checked out in this worktree, so this plan has not seen it. Nothing
   in its coordinates (`fr.acinq.phoenix:lightning-kmp-app`, project `:library`) carries
   the brand.
3. Record the baseline: `./gradlew :composeApp:m3Audit` (the `stringResource` and `Icon(`
   site counts in particular, which `e6aee792` used as evidence that the tools still
   see the whole tree), and the pass and skip counts of `:composeApp:jvmTest`.

**Done when** the new vectors pass on the unrenamed tree and the baseline numbers are
written in this document's build table.

## Phase 1 — move the packages and the database

**One commit. Mechanical, but it has to be one step, because nothing compiles halfway.**

1. Move each source set's package directory with `git mv`, so that history follows the
   files:

   ```bash
   for s in commonMain androidMain iosMain jvmMain commonTest jvmTest; do
     git mv composeApp/src/$s/kotlin/com/machankura/bennu composeApp/src/$s/kotlin/com/machankura/dedun
   done
   ```

2. Rewrite `com.machankura.bennu` to `com.machankura.dedun` and `com/machankura/bennu` to
   `com/machankura/dedun` in `composeApp/src`, `composeApp/build.gradle.kts`,
   `AndroidManifest.xml` and `docs/scripts/`. **Do not touch the `applicationId` line**, which
   is Phase 5. The `namespace` does change here. It is where `BuildConfig` is generated and
   where the manifest's relative `.BennuApplication` resolves, so it has to match the moved
   package.
3. Rename `BennuDatabase` to `DedunDatabase`, which also covers `BennuDatabaseConstructor`
   and `BennuDatabaseJvmTest`, both the files and the identifiers. This happens here and not
   in Phase 3 so that the schema directory moves once:

   ```bash
   git mv composeApp/schemas/com.machankura.bennu.compose.database.BennuDatabase \
          composeApp/schemas/com.machankura.dedun.compose.database.DedunDatabase
   ```

   Then update the path string in the two migration tests.

**Check.** Run `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`.
`git status composeApp/schemas` must show 35 renames and no new file. A 36th file, or a
second directory, means Room did not find the moved schemas. `git diff -M --stat` must show
the 884 sources as renames. A file shown as deleted and re-added has lost its history.

## Phase 2 — the root project and the generated resources

**One commit.**

1. Set `rootProject.name = "Dedun"` in `settings.gradle.kts`.
2. Rewrite `bennu.composeapp.generated.resources` to `dedun.composeapp.generated.resources`
   in the 86 files that import it, and in `m3-extract-strings.py` and
   `m3-extract-formatted.py`. Before the rewrite, build once to confirm the exact generated
   package name, because Compose Resources lowercases the root project name.

**Check.** The same compile and test run as Phase 1. `m3Audit` must report the same site
counts as the Phase 0 baseline. A drop means a tool has stopped seeing the tree.

## Phase 3 — the names in the code

**One commit.** Rename these, with their files where the file is named after the type:
`BennuApp`, `BennuGlobal` and the `bennuGlobal` parameter, `BennuApplication` (and the
manifest's `android:name`), `BennuConverters` and its logger tag, `BennuNavHost` and its
logger tag, `BennuNavigationSuite`, `BennuTheme`, `BennuShapes`, `Readiness.NotYetOnBennu`
(which becomes `NotYetOnDedun`) and `defaultBennuDir`. Rename the three string keys that
carry the brand, and their call sites: `bennu` becomes `dedun`,
`s_hasn_t_set_up_messaging_on_bennu_yet` becomes `…_on_dedun_yet`, and
`bennu_will_delete_the_nsec_for_s_from_this` becomes `dedun_will_…`.

This phase changes identifiers only, not the words in strings. If the two are kept apart,
a reviewer can read this commit as "no behaviour changed".

**Check.** Compile and `jvmTest`. Then run
`git grep -n -E '\bBennu[A-Z]|[a-z]Bennu|bennu[A-Z]' -- composeApp/src`, which should print
nothing.

## Phase 4 — the words a user reads

**One commit.**

- `composeResources/values/strings.xml`: the 11 lines that say Bennu, including `app_name`,
  the recovery-phrase and nsec explanations, *Bennu broadcasts what you publish…*, the
  sign-in reassurance, and the not-on-Bennu messages. Keep sentence case and keep the
  `%1$s` arguments. `StringCatalogueJvmTest` catches escape regressions.
- `androidMain/res/values/strings.xml`: `app_name`, which is the launcher label.
- `jvmMain/.../desktop/Main.kt`: the window `title` and *Unlock Bennu*.
- `AddMemberToChatRoomConfirmationViewModel:81` and `ChatRoomMessagingViewModel:100`: two
  error messages written as literals. Change the words. Moving them into the catalogue is
  separate work.
- `UserAgent.APP_NAME`, `UserAgent.CLIENT_NAME` and `DatabaseMarmotRepository.CLIENT_NAME`
  (D4).
- iOS: `PRODUCT_NAME=Dedun` in `Config.xcconfig`, and the three `Bennu.app` lines in
  `project.pbxproj`. The bundle id waits for Phase 5.
- `ProfilePreviewScreenJvmTest:137` asserts the old sentence. Update it in this commit.

**Check.** `jvmTest` and `m3Audit` (title-case budget 0). Launch the desktop app
(`./gradlew :composeApp:run`) and confirm the window title, the unlock screen and the home
app bar. Install a debug build on Android and confirm the launcher label.

## Phase 5 — the store and OS identities

**One small commit. D5 means nothing holds it back.** It is the only commit in this plan that
users can feel, and the only one that cannot be undone once it has shipped.

- `applicationId = "com.machankura.dedun.android"`, the same value as the namespace again.
  Update the comment above it, which explains why the two values are one.
- `PRODUCT_BUNDLE_IDENTIFIER=com.machankura.dedun.ios$(TEAM_ID)`. The owner registers the
  App ID and the provisioning profiles in the Apple developer account first. That has to be
  done by hand, by them.
- `nativeDistributions.packageName = "com.machankura.dedun.desktop"`. Before changing it,
  check whether a `windows { upgradeUuid }` is set or derived. If a Bennu MSI has been
  given to anyone, pin the upgrade UUID it shipped with, so that the Dedun MSI upgrades it
  in place instead of installing beside it.

**Check.** Install the Bennu and Dedun debug builds on one Android device and confirm they
appear as two apps. That is the expected result, and it shows Phase 7's instructions are
needed. Build the iOS app with the new identifier. Run `packageDeb` and `packageDmg` and
confirm the package names.

## Phase 6 — history, docs and tooling

**One commit.**

1. Edit the history sentences by hand. Add Dedun to each list of rebrands. "Three rebrands"
   becomes "four" in `ChillDkgRitualManager:99`, `SharedKeyDerivation:65` and `Relays.kt:9`.
   Rewrite the `HomeScreen:170` comment so that `BennuTheme` joins the list of former
   themes. Add Dedun to the `APP_DIR_NAME` comment ("through the rebrands to Curare, Bennu
   and Dedun"). Add one more step to `material-design-conformance.md:660`. Update the
   header of `strings.xml` and the comment above `applicationId` if Phase 5 has not
   already done so.
2. Add the same note at each of the twelve `bennu/` constants (D2). Today none of them says
   why its prefix is safe from a rebrand. After this rename, each one is a name that looks
   like a leftover, so the note matters.
3. Docs (D6). Rewrite the source paths in all 35 files. Update the living documents.
   `docs/README.md` gets the title "dedun docs" and a row for this plan.
4. Tooling (D7). Update `composeApp/build.gradle.kts:316-320`, the two regtest tests,
   `testing/regtest/{compose.yml,lsp.conf,payer.conf,README.md}` and `CLAUDE.md:210`.
   Bring the old network down (`testing/regtest/regtest.sh down`) before you pull this
   commit. Otherwise the `bennu-regtest` containers keep the ports that `dedun-regtest`
   needs.
5. Rename the temp-directory prefixes in the jvm tests (`bennu-db-test`,
   `bennu-switch-store`, and so on). The change has no effect beyond the final grep, but
   it keeps that grep clean.

**Check.** Run `testing/regtest/regtest.sh up`, then
`DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'` and
the liquidity run. This is the only check in the plan that exercises the twelve tags
against a real LSP.

## Phase 7 — the people already on Bennu: not built (D5)

The owner decided that Dedun's users are new users, so nothing is migrated and no last Bennu
release is shipped. The analysis is kept here because it is what anyone revisiting D5 would
need:
a restored seed brings back the nostr key, and the personal wallet's channels through the
LSP's peer backup. It does not bring back anything held only in `aux.db`: Marmot group
state, ChillDKG and Iceberg shares and their ledgers, FROST sessions in flight, and chat
history.

## Phase 8 — verify, and the last grep

The plan is done when all of these pass on the final tree:

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
git grep -n -i bennu -- . ':!docs'
```

The last command should print only these:

- the twelve `bennu/…/v1` constants, their doc comments, and the notes from Phase 6;
- history sentences that name Bennu as a former brand.

A match anywhere else is a miss. In `docs/`, the matches that remain should all be dated
prose (D6).

Beyond those commands: run an iOS build in Xcode, check the window title from
`./gradlew :composeApp:run`, and check the regtest run from Phase 6.

## Order, and what can run in parallel

Phases 1 to 4 run strictly in order, one commit each, because each one rewrites files the
next one touches. Phase 0 comes first. Phases 5 and 6 follow Phase 4. With D5 settled, there
is no Phase 7 to wait for.

## What this does not do

- Rename the twelve `bennu/` tags, the two `mantra/` tags or the `curated` data directory.
- Change the launcher icon or other artwork. Check `mipmap-*` and `Assets.xcassets` for a
  wordmark. If there is one, it is a design task.
- Rename git branches, the Gitea repository, or the `lightning-kmp-app` remote.
- Create the Play and App Store listings for Dedun. That is the owner's work.

## Risks and open questions

- **The fork has a thirteenth tag.** `RemoteIcebergFundingSigner.kt:250` in
  `experimental/lightning-kmp` hashes under `bennu/iceberg/fresh/v1`. It is protocol in the
  same way as the other twelve (D2) and stays. It lives in another repository, so this
  rename does not touch it. The `lightning-kmp-app` submodule has no `bennu` in it at all:
  its own package is `com.machankura.compose`, and its notification channels and
  `FileProvider` authority are derived at runtime from whatever the app's `applicationId`
  is.
- **Desktop installers.** Changing `packageName` may change the MSI upgrade code and the
  `.deb` package name. Whether that matters depends on whether a Bennu desktop installer
  was ever given to anyone.
- **Docs that reference other docs by line number**, such as `CLAUDE.md`'s links and
  `restored-device-marks.md:83`. Rewriting a path does not change line counts, but
  hand-edits in Phase 6 can. Check the links again at the end.
