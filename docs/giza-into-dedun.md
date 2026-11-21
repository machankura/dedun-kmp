# Taking Giza's work into Dedun

Dedun, Bennu and Giza are three apps grown from one tree. Their last shared commit is
`4f1b4a43`, "buying inbound liquidity is on, on mainnet", from 2026-11-01. After it:

- **Dedun** renamed itself (`b00b50f5`..`37fb6842`). It then built payout destinations,
  member deposits, payouts-only, the LSP status, the personal and group swap-in, Bark (added
  and then removed), the profile and group Wavelength wallets, paid requests, and member
  transfers. That is 236 commits, and they took the schema from 35 to 45.
- **Bennu** added two commits: the GPL licence (`8dd09aa0`) and a README (`4e106fb5`).
- **Giza** forked from Bennu at `4e106fb5` and has gone 172 commits further. Giza's local
  `giza` (`d0437d6c`) is one fix ahead of `origin/giza` (`25ce74cd`); the fix is `36be1efc`.

Most of Giza's work is wanted here too:

- an MLS engine that is still maintained;
- a fix to how a room's sender ratchet is saved;
- automatic key ceremonies;
- FROST share generations and rotation;
- rooms whose changes need the group's signature;
- a year's dependency updates.

Bennu has already done this port once. A session on 2026-11-21 built
`docs/giza-into-bennu.md`, Phases 0 to 9, on `claude/giza-kmp-bennu-integration-3a5d53`
(tip `2db6dde5`). That branch is 142 commits, 125 of them carrying a `Giza: <sha>` trailer.
It has not been pushed, and it is not merged into `bennu`.

Read that plan first. This plan follows its phases and does not repeat what it already
explains. What follows covers what is different for Dedun:

- where the commits come from;
- the event kinds that collide;
- the schema offset;
- the wallets Dedun has and Bennu does not.

It was written against Dedun `693e522a`, Giza `d0437d6c` and Bennu's port `2db6dde5`. It was
**not** checked by a trial merge or a build. Phase 0 does both before anything is
cherry-picked.

## Where the commits come from: Bennu's port, not Giza directly

Each Giza commit can reach Dedun by one of two routes.

| route | what it costs |
|---|---|
| **Bennu's port, debranded `bennu` → `dedun`** (recommended) | One rename: Bennu → Dedun, which Dedun already did once, and whose rules are in [dedun-rename.md](./dedun-rename.md). Bennu's port already has the wallets put back into every Giza diff: the group Lightning wallet, Iceberg, the onchain wallet, guardians, member guarded wallets and the subgroup policy. Giza had deleted all of them before writing its features. It also has the wallet work no Giza commit covers: generation stamps on the five wallet sessions, the Lightning holder set read at the active generation, a rotation that waits for an open spend, and no admin changes where a Lightning wallet is kept |
| Giza directly, debranded `giza` → `dedun` | Two renames composed, and the wallet re-adaptation done a second time. Every conflict Bennu's port resolved in `FrostSigningManager`, `ChillDkgRitualManager` and `GroupKeyStateManager` would be resolved again |

Bennu's port is also a better base for merging. Bennu's `4e106fb5` is Dedun's `4f1b4a43`
plus a licence and a README. A debranded Bennu commit therefore three-way merges onto Dedun
against nearly the right base, and a conflict there means a real Dedun difference.

**Measured overlap.** This plan compared two diffs file by file, after mapping
`com/machankura/bennu` → `com/machankura/dedun` and `Bennu` → `Dedun` and debranding the
old side:

- Bennu's port, `4e106fb5..2db6dde5`: 277 files (125 added, 4 deleted, 148 modified);
- Dedun since `4f1b4a43`.

**35 files carry real edits on both sides.** No file Bennu's port modifies has been deleted
by Dedun. The heavy ones (lines changed: Bennu's port / Dedun):

| file | Bennu | Dedun |
|---|---|---|
| `ChatRoomDetailScreen.kt` | 819 | 477 |
| `FrostSigningManager.kt` | 415 | 168 |
| `ChatRoomDetailViewModel.kt` | 321 | 68 |
| `ChatMessage.kt` | 149 | 318 |
| `GroupSpendSigningJvmTest.kt` | 129 | 182 |
| `DedunDatabase.kt` | 85 | 90 |
| `DedunNavHost.kt` | 48 | 562 |
| `NostrDao.kt` | 283 | 41 |
| `ProposedEvent.kt` | 19 | 110 |
| `GroupLightningFixture.kt` | 39 | 119 |
| `IcebergRitualManager.kt` | 34 | 32 |
| `ChatRoomDetailUIState.kt`, `ChatTranscript.kt`, `GuardianSigningJvmTest.kt` | 30–60 each | 30–40 each |

The rest change by under 35 lines on at least one side. That is a small surface. Most of
Dedun's own work is in files Bennu's port never touches: `GroupWavelength*`, `Deposit*`,
`MemberTransfers`, `GroupPayout*` and `CoinAssignments`. Those files need *semantic* work,
which the phases below list, but they will not conflict.

**What Bennu's port does not have**, and so comes from Giza through the same mirror:

- `36be1efc`, one Shares row per member, which mends a crash in the Shares section the port
  brings in;
- the commits Bennu held for D3 and D6 (Phase 10);
- anything Giza pushes after `d0437d6c`.

## The kinds collide, and Dedun's move

This is the one thing in the plan that is new. It decides the shape of Phases 7 and 8, and it
adds Phase K.

Dedun allocated its new kinds after the fork without knowing what Giza had taken:

| range | Dedun (in builds now) | Giza and Bennu's port |
|---|---|---|
| 30340 | `DepositAttributionEvent` (deposits, on mainnet) | refresh `PROPOSAL` |
| 30341 | `CoinAssignmentEvent` (payouts, on mainnet) | refresh `ROUND_1` |
| 30342 | `TransferRequestEvent` (regtest and signet only) | refresh `COORDINATOR_ROUND_1` |
| 30343 | `MemberTransferEvent` (regtest and signet only) | refresh `ROUND_2` |
| 30344–30345 | free | refresh `CERTIFICATE`, `FAILURE` |
| 30346–30349 | free | repair `REQUEST`, `SHARE`, `SIGMA`, `DONE` |
| 30370–30377 | `GroupWavelengthEvents`: `WALLET_STATEMENT` … `HIGH_WATER` (on mainnet) | governance: statement, own commit 30371, carrier 30373, offer, accept, handover, step down (30372 retired) |
| 30378–30382 | `GroupWavelengthEvents`: `SNAPSHOT` … `STOPPED` | free |

**Decided (D1): Dedun moves its own kinds, and takes Giza's at Giza's numbers.** That keeps
Dedun wire-compatible with Bennu and Giza. A Dedun device can then follow a governed room or
a share rotation another app started, and the mirror's commits apply without a kind rewrite.

### Where they go

| Dedun kind | from | to | rule |
|---|---|---|---|
| `DepositAttributionEvent` | 30340 | **30390** | +50 |
| `CoinAssignmentEvent` | 30341 | **30391** | +50 |
| `TransferRequestEvent` | 30342 | **30392** | +50 |
| `MemberTransferEvent` | 30343 | **30393** | +50 |
| `GroupWavelengthEvents`, all thirteen | 30370–30382 | **30400–30412** | +30 |

Only 30370–30377 of the Wavelength family collide. The whole family moves anyway, so that
it stays one contiguous block and the `CO_SIGNING`, `WALLET_TRAFFIC` and `GROUP_SIGNED` sets
keep being one range. The five that did not need to move add five lines to the legacy map
and nothing else. 30383–30412 is free in Dedun, in Giza at `d0437d6c` and in Bennu's port.
Phase 11's register claims the range for Dedun before anything ships on it.

### Why the old numbers stay readable for good

A group-signed event's id is the 32 bytes the group signed (`GroupSignedEvent.id`), and a
Nostr id hashes the kind. A deposit attribution stored at 30340 cannot be relabelled 30390
without breaking its signature. Neither can a Wavelength wallet statement at 30370.

Those stored events also travel. `ChronicleEvent.KINDS` carries `DepositAttributionEvent`,
`CoinAssignmentEvent` and `MemberTransferEvent` to members added later. A device that joins
a year from now will still be handed 30340s and 30341s, and has to read them as Dedun's.

So the move is not a migration. The code learns a second number for each kind, called the
**legacy** number, and reads it only where it is unambiguous:

- in a row stored before this device's cut-over (K3 below), which was classified when it
  arrived;
- inside a chronicle page, which never carries refresh, repair or governance traffic;
- live, only until the cut-over.

After the cut-over, a live 30340–30343 or 30370–30377 is Giza's meaning. The two sides never
compete for one event.

### Three releases

The kinds are read by every member's device, and a device on an older build that missed a
statement would let the group's ledger diverge. So the move ships the way
[member-transfers.md](./member-transfers.md) shipped transfers: every device learns to read
before any device writes.

| step | the release | reads | writes |
|---|---|---|---|
| K1 | read both | new and legacy, live and stored | legacy |
| K2 | write new | new and legacy, live and stored | new |
| K3 | cut-over | new, plus legacy from stored rows and chronicles only | new |

Wait between steps until the previous release is on the devices that matter. The thing to
watch is a group where a K1 device co-signs the Wavelength wallet beside a K2 device. They
interoperate, because K1 reads the new numbers. A pre-K1 device in that group stops
co-signing the moment any device reaches K2, and that is the reason for waiting.

**K3 is a hard prerequisite.** No release takes Giza's kinds before K3: not Phase 7 with the
flag on, and not Phase 8 at all. Before K3, a Dedun device would read a refresh proposal as a
deposit attribution. Phases 2 to 6 don't depend on the move, so they proceed in parallel.

If no build carrying these kinds has reached anyone, K1 and K2 collapse into one change, and
legacy reads remain only for the developers' own test data. The transfer kinds may already
be in that position, since transfers have only been offered on regtest and signet. Phase K
starts by finding out.

## Decisions for the owner

The recommendation is listed first. Where Bennu decided the same question, Dedun follows
unless there is a Dedun reason not to.

| | question | recommendation | other choice and its cost |
|---|---|---|---|
| D0 | Where do the commits come from? | **Bennu's port**, pinned at `2db6dde5` until it is pushed or merged, and Giza through the same mirror for what the port lacks | Giza directly: the wallet adaptation done twice |
| D1 | Whose kinds move? | **Decided 2026-11-21: Dedun's own 17**: 30340–30343 → 30390–30393 and 30370–30382 → 30400–30412, read under both numbers, in three releases (Phase K). Giza's kinds and governance marker are taken as Giza wrote them, so Dedun stays wire-compatible with Bennu and Giza | Not chosen: renumber Giza's kinds in Dedun and write a Dedun-only marker. Cheaper, but a Dedun device could never follow a room or a rotation another app started |
| D2 | Are Dedun's keyed rooms born governed (`1ccd8c7f`)? | **No, not yet**: the opt-in switch, as Bennu chose. Dedun has released builds | Take `1ccd8c7f` and fork every room with a member on a pre-Phase-8 build |
| D3 | Remove convenient groups? | **No.** Take `0a4453da` (`Quorum`) only, as Bennu did | All of remove-convenient-groups |
| D4 | Does promotion make an admin a signer? | **No admin change in a keyed room that keeps a Lightning *or a Wavelength* wallet.** Bennu's `8a91b664` covers Lightning. Dedun's Wavelength wallet is an Iceberg ceremony too (`IcebergKeySession.PURPOSE_WAVELENGTH`), and Iceberg has no refresh. Elsewhere, ask once more where the onchain wallet holds funds: in Dedun, the FROST key also signs payouts, attributions, coin assignments and transfers | Allow it, and a demoted admin keeps co-signing both Iceberg wallets |
| D5 | Exclude app data from Android backup (`4040ae74`)? | **Held, as in Bennu.** Take `secure_delete` only. `allowBackup` stays `true` | Exclude it, after an export path for member wallet keys |
| D6 | New rooms on the Marmot current profile, legacy KeyPackage retired? | **Yes, last** (Phase 10), once Phase 8 has been in a release | Stay on legacy |
| D7 | Take Bennu's licence commit `8dd09aa0` (GPL-3.0-or-later)? | **Yes**, in Phase 0. Every commit this plan brings in was written in a tree under that licence. Dedun has no `LICENSE` today | Keep Dedun unlicensed, and confirm with whoever holds the copyright that GPL-licensed code may come in |

## The schema numbers

Dedun is at 45. Bennu's port moved Bennu from 35 to 41. Giza's migrations land here at
**Giza + 9, or Bennu's port + 10**. Regenerate every schema JSON, and never copy one. Bennu's
JSONs describe a database without Dedun's ten tables of deposits, payouts, Wavelength and
transfers, and Room would fail validation on them.

| Giza | Bennu port | **Dedun** | what | phase |
|---|---|---|---|---|
| 36 | — | — | Giza's wallet drop | never |
| 37 | 36 | **46** | `MarmotSenderRatchet` | 2 |
| 38 | 37 | **47** | `KeyShareGeneration`, `KeyShareMember`, generation columns | 6 |
| 39 | 38 | **48** | refresh session, message and confirmation | 7 |
| 40 | 39 | **49** | repair tables, `GroupKeyState.memberBindings` | 7 |
| 41 | 40 | **50** | `GovernanceProposal` | 8 |
| 42 | 41 | **51** | `thenRotateWithout`, `ShareRepairSession.nParticipants` | 9 |

All of them are `AutoMigration`s. `ShippedProfileUpgradeJvmTest` hardcodes its versions and
is renumbered to 45/46. If Dedun adds a migration of its own while this is in progress, every
later row shifts, and the ledger records the actual numbers.

Phase K is not expected to need a migration, because stored events keep the kind they were
signed under. If K3's record of this device's cut-over has to be a column rather than a
preference, it takes the next number, and every row above shifts by one.

## Phase 0: source, mirror, ledger, baseline

No change to the app.

1. **Pin the source.** Fetch Bennu's port into this repository from the local checkout:

   ```bash
   git fetch ~/Documents/development/machankura/bennu-kmp claude/giza-kmp-bennu-integration-3a5d53:refs/remotes/bennu/giza-port
   ```

   Record `2db6dde5` as the tip ported from. Ask for the branch to be pushed. If it is
   rebased before it merges into `bennu`, rebuild the mirror and diff the ledger.
2. **`docs/scripts/bennu-mirror.sh`.** For each Bennu commit `C` in `4e106fb5..2db6dde5`
   that is taken, make `M(C)` with parent `M(C^1)`, whose tree is `C`'s with the rename
   applied. The rename is [dedun-rename.md](./dedun-rename.md) Phases 1 to 4 as rules:
   - move `kotlin/com/machankura/bennu` to `kotlin/com/machankura/dedun` in every source
     set;
   - rename `*Bennu*` files and types to `*Dedun*`;
   - move the schema directory to `com.machankura.dedun.compose.database.DedunDatabase`;
   - rewrite `bennu.composeapp.generated.resources` to `dedun.composeapp...`;
   - change the app's name in strings.

   **Leave the twelve `bennu/` hash tags alone.** Dedun kept them on purpose, and
   `DomainTagsPinnedTest` fails if the script touches one. `M(4e106fb5)` should differ from
   Dedun's `37fb6842` (the end of the rename) only by the licence, the README and the rename
   phases' hand-worded strings. If it differs by more, the rules are wrong.
3. **Giza commits the port lacks.** Run Bennu's `docs/scripts/giza-mirror.sh`
   (giza → bennu), then `bennu-mirror.sh` on its output. The first candidate is `36be1efc`.
4. **The schema rewrite is not in the mirror.** It is a Dedun-only commit in each phase that
   brings a migration. That way a later mirror rebuild never fights it, and `git log` shows
   each renumbering as its own change. Giza's kinds need no rewrite: under D1 they come in
   at Giza's numbers.
5. **A trial merge.** Merge `M(2db6dde5)` onto `693e522a` and record:
   - the conflicted files and hunks, which should resemble the overlap table above;
   - the files where a schema version needs rewriting;
   - the files where Giza's kinds meet Dedun's old ones, which Phase K has to clear before
     those phases land.

   Throw the merge away.
6. **The ledger, `docs/giza-ledger.md`.** One row per Giza commit after `4f1b4a43`
   (172 plus `8dd09aa0` and `4e106fb5`). Columns:
   - the Giza hash;
   - the Bennu port hash, from its `Giza:` trailer;
   - the group;
   - the phase;
   - the state (*taken*, *adapted*, *skipped*, *held*, *pending*);
   - the Dedun commit;
   - the schema number.

   Bennu-only commits in the port get rows of their own, keyed by Bennu hash, because Dedun
   wants most of them. Every Dedun commit taken carries both `Giza: <sha>` and
   `Bennu: <sha>` trailers.
7. **D7.** Take `8dd09aa0` if the answer is yes.
8. **The baseline**, on `693e522a`:
   - `./gradlew :composeApp:jvmTest`: run, failed, skipped. Note the known timing flakes
     `IdentitySwitchJvmTest` and `LiveSubscriptionReconcileTest`;
   - `./gradlew :composeApp:m3Audit`, line by line;
   - `testing/regtest/regtest.sh up`, then with `DEDUN_REGTEST=1` run
     `GroupLightningRegtestJvmTest`, `LiquidityPurchaseRegtestJvmTest`,
     `MemberBranchesRegtestJvmTest` and `PayoutAddressRegtestJvmTest`, each on a fresh
     network;
   - `GroupWavelengthSignetJvmTest` with `DEDUN_SIGNET_DIR`, if a signet operator is
     available. Say so if it is not.

**Done when** the mirror rebuilds idempotently, the trial merge is recorded, the ledger has
every row with a verdict, and the baseline is saved.

## Phase 1: fixes, and guards for rooms other apps made

Small, and shippable alone.

1. **`2ed83c55`** (Bennu `8ba35258`): an admin's refused leave is shown rather than thrown.
   Dedun's `ChatRoomDetailViewModel.leaveGroup` has the same uncaught call.
2. **The governance guard** (Bennu `360f772c`, adapted). Read Giza's `GovernanceMarker` line
   from a room's epoch-0 description. In a room that carries it:
   - disable invite, remove and disband, with a sentence saying why;
   - don't auto-commit a leaver's SelfRemove;
   - show a card.

   This is a stopgap, as in Bennu, and Phase 8 lifts it.
3. **Foreign kinds refused, with tests.** Until K3, Giza's and Bennu's devices can put their
   refresh and governance kinds into a room that has Dedun devices in it, at numbers Dedun
   still reads as its own. For each colliding kind, feed in a fixture event as Giza writes
   it: a refresh proposal, round 1, coordinator round 1 and round 2, and a governance
   statement, carrier, offer and step-down. Assert that Dedun's reader for that number
   refuses it:
   - `DepositAttributionEvent`, `CoinAssignmentEvent`, `TransferRequestEvent` and
     `MemberTransferEvent` for 30340–30343;
   - `GroupWavelengthWalletManager.checked`, the operator statement, the authorisation and
     the co-signing readers for 30370–30377.

   Also assert that no ledger row, balance or Wavelength state changes, and that `ChatMessage`
   shows nothing in the transcript. These tests are re-pointed at the legacy readers in K1,
   and K3 turns them round.

**Done when** jvmTest is at baseline plus the new tests, and no Dedun build offers a bare
commit in a room that carries Giza's marker.

## Phase K: Dedun's kinds move, in three releases (D1)

The move set out under "The kinds collide, and Dedun's move". Each step is its own release.
Phases 2 to 6 go on alongside it. Nothing in Phases 7 to 10 reaches a release before K3.

**Before K1, find out what has shipped.** For each of the 17 kinds, find out whether a build
that writes it has reached anyone outside development, on any chain. A kind no such build
has written skips K1 and K2: it moves in one change, with legacy reads kept only so that
developers' own test rooms still open. The transfer kinds, 30342 and 30343, are the likely
case.

**One table of numbers.** Every site names these kinds through a constant, not a literal.
That is 21 files, the heaviest being `GroupArkCoSigner` (14 uses), `MemberTransfers` (7),
`ChatMessage` (6), `FrostSigningManager` (5), `GroupWavelengthDeposits` and `NostrDao` (4
each). Give each kind a `LEGACY_KIND` beside its `KIND`, and give `GroupWavelengthEvents` a
legacy map for its sets. Then a site asks one question, "is this one of mine?", and does not
compare against a number itself.

### K1: read both

- **Readers.** Every reader and verifier accepts both numbers: `read`, `checked`,
  `verifies`, the co-signer's dispatch, and the signers' acceptance checks in
  `FrostSigningManager` and `MemberTransfers`. Those last two are the checks that decide
  whether this device signs a proposal.
- **Queries.** Every query that filters on kind asks for both: `MarmotInnerEventDao`,
  `NostrDao`, the `GroupSignedEvent` lookups behind `DepositLedger`, `MemberBalances` and
  `GroupWavelengthReports`, and `DatabaseGroupWavelengthRepository`.
- **The transcript.** `ChatMessage` silences both numbers.
- **The chronicle.** `ChronicleEvent.KINDS` carries both, and a page from an older device
  still reads.
- **Writers** still write the legacy number.
- **Tests:**
  - every existing deposit, payout, transfer and Wavelength test, unedited;
  - a copy of each, run with the new numbers on the way in;
  - a two-device test: a K1 device and a device writing the new numbers co-sign one
    Wavelength payment and agree on one ledger.

**K1 as built.** One object, `MovedKinds`, holds the map of 17 legacy numbers to new ones.
Each kind's `KIND` constant is now its new number. Writers go through `MovedKinds.written`,
which gives the legacy number while `writesNew` is false. The event builders and the shared
`GroupLightningWalletManager.queue` are the only places that write. Dispatch is on
`MovedKinds.current(kind)`: `ChatMessage`, `ProposedEvent`, `NostrDao`'s routing, the
Wavelength co-signer, reports and deposits. So are the signers' checks in
`FrostSigningManager` and `MemberTransfers`.

Every kind query goes through the DAOs, which ask for both numbers themselves.
`GroupSignedEventDao.getByChatRoomIdAndKind`, its counts, and `MarmotInnerEventDao`'s three kind
queries are now concrete functions over `MovedKinds.both`, so no caller had to change.
`ChronicleKinds` carries both numbers, and ranks them as one kind.

The tests:

- `MovedKindsJvmTest`;
- nine wallet suites rerun with `writesNew` on (`UnderNewKindsJvmTest`);
- `GroupArkCoSigningAcrossReleasesJvmTest`: the whole Wavelength co-signing suite, with alternate
  devices on the two releases.

### K2: write new

- **Writers** switch to the new numbers. That covers the FROST proposals for attributions,
  coin assignments, transfers and the three Wavelength group-signed kinds, and the
  Wavelength co-signing and wallet traffic.
- A statement that is already part-signed under the legacy number is finished under the
  legacy number. A signing session's kind is fixed when it is proposed.
- **Tests:**
  - a K2 device and a K1 device co-sign, both ways round;
  - a legacy-numbered session open across the upgrade completes.

### K3: cut-over

- **Live legacy reads stop.** A live 30340–30343 or 30370–30382 is no longer Dedun's: a
  30340–30343 from then on is Giza's, as are 30344–30349 and 30370–30377, and 30378–30382
  are unused. Rows stored before the cut-over keep their meaning, because they were
  classified when they arrived. Chronicle pages keep reading legacy numbers, because a
  chronicle carries only Dedun's ledger kinds.
- **The cut-over is recorded per device.** It is the moment this device first ran K3, not a
  date, so that a device left offline across the cut-over does not misread its backlog. A
  backlog event older than that moment is still read under the legacy number.
- **Tests:**
  - the Phase 1 tests, turned round: a live Giza 30340 or 30370 no longer reaches a Dedun
    ledger or Wavelength reader;
  - a stored legacy attribution still counts in a member's balance;
  - a chronicle of legacy events hands a new member the same balances as the founders'.

**Done when** K3 is in a release on the devices that matter, and `docs/kinds.md` (Phase 11)
lists 30390–30393 and 30400–30412 as Dedun's and 30340–30343 and 30370–30382 as
legacy-only in Dedun.

## Phase 2: the Marmot engine from marmot-kmp

Bennu's port Phase 2, as Bennu did it:

- **Imports.** Run `docs/scripts/marmot-kmp-imports.sh` over Dedun's tree; don't
  cherry-pick the import rewrites. Dedun has about 209 `quartz.marmot` import lines in 57
  files, against Bennu's 185 in 54. The difference is the Wavelength, deposit and transfer
  files.
- **`policy = MarmotGroupPolicy`** at every `MlsGroup.create`, `restore` and
  `processWelcome`. Grep for them; a missed one fails silently.
- **The sender ratchet:** `e07bb680`, **schema 45 → 46**.
- `ShippedProfileUpgradeJvmTest` renumbered to 45/46.
- **The rest of the group:** the guard `f0f1c179`, the kind renames `9bc05c11` (nothing on
  the wire), and the tests.

**The one-way door.** `STATE_VERSION` 10 cannot be read by the old engine, so a downgrade
loses rooms. Keep a real pre-port state blob from a Dedun device as a fixture
(`docs/tools/old-state-blob`).

**Dedun-only:** none of Dedun's wallet code constructs an `MlsGroup`. The construction
sites are in `NostrDao`, `MarmotGroupFactory`, `ChatRoom` and `MarmotEngine`, which are
Bennu's files, so Bennu's list of sites applies here.

**Done when** `compileDebugKotlinAndroid`, jvmTest and `marmotAudit` pass, every wallet test
passes unedited, and a device with rooms from a `693e522a` build upgrades and still reads
them.

## Phase 3: quartz 2.20.1 through Magpie

Bennu's port Phase 3:

- `971dd459`, the Java 21 daemon, first;
- `f56be2b3`, `0ecc179d`, `7222df01`, `7fe64930`;
- the tests `c81e4a2d`, `0b43295a`, `a08a8789`;
- the docs.

**Dedun-only: Magpie and the Wavelength bindings.** Dedun's `settings.gradle.kts` declares
a local repository for the Wavelength daemon's gomobile bindings, built from the source
`experimental/wavelength-kmp` pins. Find out how Magpie and `magpieLockAudit` treat
artifacts that no publisher signed. They must neither fail the build on them nor accept them
silently into the lock. If they do either, record how this phase settles it.

**Done when** `./gradlew check` passes with the Magpie lock audit in it, and
`engine-interop` passes.

**As built.** Magpie is declared for the six `com.vitorpamplona` groups and nothing else, so the
Wavelength bindings' file repository never reaches it, and neither the build nor the lock
audit sees them. The commits applied without a conflict except `971dd459`'s README line,
which went into Dedun's own README. jvmTest: 2,915 run, 0 failed. `magpieLockAudit`,
`marmotAudit`, `m3Audit` and `compileDebugKotlinAndroid` are green, and `engine-interop`
passes 20/20.

## Phase 4: the toolchain and the submodule chain

Bennu's port Phase 4, in one PR. Take `3e0a16b1`'s lint fixes first, then the catalogue
changes, AGP 9.5.1 (`a3c0b830`) and Kotlin 2.4.21 (`302ce3ca`).

**`lightning-kmp-app` moves `949bfcb9` → `d87af507`.** This was checked: it is a
fast-forward of four commits, reduced FROST enrollment, FROST share refresh, AGP 9.5.1 and
Kotlin 2.4.21. The swap-in signer that Bennu's plan lists as a change under the wallet is
Dedun's own (`5b95f674`, already at `949bfcb9`), so Dedun has less to re-check than Bennu
had.

**Dedun-only work:**

- **`experimental/wavelength-kmp` is an included build** on AGP 9.4.0, Kotlin 2.4.10 and
  Gradle 9.6.1. The app moves to AGP 9.5.1 and Kotlin 2.4.21. AGP refuses two versions in
  one build. Find out first whether the composite still configures. If it does not,
  wavelength-kmp's `descriptors` branch has to move. That is a change in another
  repository, `machankura/wavelength-kmp`, so it is the owner's to approve, and this phase
  waits for it.
- **Catalogue entries Giza deleted as unused** (`dafcff2c`, `56df0a2b` and the rest):
  `ktor-client-mock`, `junit`, the `androidx` test and appcompat entries,
  `room3-sqlite-wrapper`, `sqlite-bundled-jvm` and `sqldelight`. Before deleting each one,
  grep Dedun's build files, including the Wavelength tests'. Keep any that Dedun still uses.
- **Lint.** AGP 9.5's lint reads commonMain. Bennu fixed what it found on Bennu's screens.
  Dedun's payouts, deposits, swap-in, Wavelength and transfer screens have never been
  through it.

**Done when:**

- `./gradlew check` passes;
- jvmTest is at baseline;
- the four regtest suites pass on fresh networks;
- the Wavelength signet run passes, or is recorded as not run;
- a desktop run pays out a member over Lightning.

**As built: half of it, held at AGP 9.4.0 and Kotlin 2.4.10.** The composite does not
configure with wavelength-kmp on AGP 9.4.0 beside the app on 9.5.1. The error is "Using
multiple versions of the Android Gradle plugin(9.4.0, 9.5.1) in the same build is not
allowed", and `wavelength-core`, `-engine` and `-embedded` stop resolving for Android. So
`a3c0b830` and `302ce3ca` were taken and then reverted in one commit, `a8331b90`, which says
how to undo it.

`lightning-kmp-app` is pinned at `3794eb8`. That is the last commit before its own AGP bump,
so it still builds with AGP 9.4.0 and Kotlin 2.4.10, and it carries reduced FROST enrollment
and FROST share refresh. Both `3794eb8` and Giza's `d87af507` are on GitHub's `master`, and
their lightning-kmp commits are on `threshold`.

Everything else in the phase is in:

- `dafcff2c`, keeping the two test entries Dedun's instrumented tests read;
- the library bumps;
- Gradle 9.9.2;
- the three lint fixes.

**What finishes the phase:** move wavelength-kmp's `descriptors` branch to AGP 9.5.1 and
Kotlin 2.4.21, move Dedun's pin to it, revert `a8331b90`, and run this phase's checks again.

## Phase 5: automatic key ceremonies

Bennu's port Phase 5: `e9c413ac`, `ca5cda81`, `9278ccd5`, `d71cf0c0`, `4c75ca1b`,
`73fcfff0`, `0a60da6e`, plus Bennu's `168f4524` (a guarded subgroup made by the automatic
ceremony keeps its policy and guardian keys). No schema change and no kinds.

Dedun's `DkgJoinApprovalScreen` and the round approval screens changed only by the rename,
so Bennu's resolution applies as it stands. Keep the wallet-policy and guardian-delay rows
on the one join screen.

**Done when** the ceremony tests and Bennu's subgroup test pass, and `m3Audit` is within
budget.

**As built.** All ten commits are in, Bennu's subgroup test among them. The one join screen keeps
the wallet-policy and guardian-delay rows. jvmTest: 2,946 run, 0 failed.

## Phase 6: share generations in every signer

`fce51e20` (**schema 46 → 47**) and the signing half of `51615992`, as Bennu adapted them.
This is on the wire only from generation 1, so nothing a user sees changes. Bennu's port
already stamps the generation in the five wallet sessions (`proposeSpend`,
`proposeGuardianSpend`, `proposeSweep`, and the two accepts). Dedun has the same seven
`FrostSigningSession(` constructors as Bennu. Every Dedun-only statement goes through the
generic `proposeSigning`, which Giza's change already covers: deposit attribution, coin
assignment, depositor statement, transfer, the Wavelength wallet and operator statements,
and the Wavelength payout approvals.

**Dedun-only work: what reads the key after the wipe.** From generation 1, `wipeBefore`
nulls `DkgSession.secretShare`. Every caller of `completedKey`, `thresholdKeyFor` or
`canSign` that Dedun added since `4f1b4a43` must still get an answer:

- `GroupWavelengthWalletManager`;
- `DepositLedger`;
- `MemberTransfers`;
- `GroupSwapInWallets`;
- `DepositsViewModel`, `GroupAssignCoinsViewModel`, `GroupPayoutsViewModel` and
  `GroupSwapInViewModel`.

Bennu's port mended the shared ones. List these in the PR with what each now reads.

**Tests:**

- Bennu's generation-1 tests: an onchain spend, a guardian spend and a sweep sign after the
  refresh and the wipe.
- **New**, at generation 1 after the wipe:
  - an onchain payout signs under the payouts-only rule (`ee142c61`);
  - a deposit attribution and a coin assignment sign;
  - a transfer signs;
  - a Wavelength payout approval signs.
- **New:** a group swap-in coin is spliced in at generation 1.
- **New:** a stale member declines a payout out loud, not with silence.

**Done when** those pass and the regtest suites are at baseline.

**As built.** `fce51e20` came in as Bennu adapted it, at schema 47. Two things it needed in Dedun:

- **Room's schema check outgrew a JVM method.** With 83 tables (Bennu has 64), Room's generated
  `onValidateSchema2` passed the 64 KB limit, so `composeApp` sets Room's own processor option
  `room.validationSplitSize` to 200.
- **Bennu's generation-1 spend tests paid change,** which payouts-only refuses. They now pay a
  member out.

The Dedun-only tests are in: an attribution, a coin assignment, a transfer and a Wavelength
payment approval, each signed at generation 1 after the wipe. The group swap-in reads the key
through `thresholdKeyFor`, and its inputs are Iceberg's, so it needed nothing. jvmTest:
2,966 run, 0 failed.

## Phase 7: rotation, re-enrollment and the Shares section, behind a flag

Bennu's port Phase 7:

- `f8974aec` (**47 → 48**), `c4fe4c9c`, `1c3e0e93` (**48 → 49**);
- `99229090`, `4949f0fe`, `fc437d05`, `8278ffd5`, `08b5c56f`;
- the rotation-flow pairs;
- `4040ae74`'s `secure_delete` half;
- Bennu's `993f26b8`;
- **plus `36be1efc`** from Giza, which Bennu's port lacks.

**The kinds.** Refresh (30340–30345) and repair (30346–30349) come in at Giza's numbers
(D1). This phase can be built and merged before K3, but only because the flag is off in
release, and its code must not see a live 30340–30343 until K3 has handed those numbers
over. Until then, Dedun's inbound dispatch sends 30340–30343 to the legacy ledger readers.
Add a test that a debug build before K3 does not start a refresh from a stored deposit
attribution.

**The flag.** Call it `dedun.experimental`, with `isDevelopmentBuild()`, off in release. With
the flag off and K3 in, an inbound 30340 is declined out loud. That is better than today,
when a Giza member's rotation stalls on a Dedun device without a word.

**Dedun-only work:**

- **Both Iceberg holder sets** read the active generation's `signerIds`, not the
  generation-0 `participantPublicKeySet()`. Bennu's `993f26b8` does this for Lightning.
  Dedun has a second site, `GroupWavelengthWalletManager.checked`, at the `NOT_KEY_HOLDERS`
  refusal. Left alone, a re-enrolled member's Wavelength statement is refused, and a
  retired member still counts as a holder.
- **Rotation cannot take away an Iceberg share**, for either wallet. Say so in
  `c4fe4c9c`'s removal dialog and in the "whose share a rotation takes away" line (Phase 9),
  for the Lightning and the Wavelength wallet both. Point at starting a new wallet without
  that member.
- **What a rotation waits for.** Bennu's port holds a rotation while an onchain spend or a
  sweep is open. In Dedun, also hold it while any of these is open below the new
  generation:
  - an onchain payout;
  - a Wavelength payout approval;
  - a transfer or transfer request;
  - a deposit attribution or coin assignment.

  `failRotated` would otherwise fail them. Nothing is lost by that, but a member's payout
  would quietly need proposing again.
- **Re-enrollment and who owns what.** Balances, attributions, coin assignments and payout
  destinations are keyed by the member's Nostr key. Confirm that re-enrollment under a new
  FROST identity leaves the Nostr key alone, so that nothing in the ledger moves. Pin it
  with a test.
- **D5** stays held.

**Tests:** Bennu's flag-on suite (`ShareRefreshRitualJvmTest`,
`ChatRoomDetailSharesJvmTest`, `SecureDeleteJvmTest`), plus:

- **new:** a Wavelength holder set without a retired member;
- **new:** a rotation held by an open payout;
- **new:** a payout signs after a rotation;
- **new:** a release build after K3 declines a 30340;
- **new:** a Dedun device and a Giza-shaped device rotate one group's shares together, flag
  on. This is the interoperability D1 was chosen for.

**Done when** the flag-on suite passes, a release build has no Rotate button, and a debug
desktop build rotates a three-device group and then pays a member out onchain.

**As built.** Bennu's commits #55-#80 and `993f26b8` are in, with Giza's `36be1efc`, which
Bennu's port lacks. The schema is 49: refresh is 48 and repair is 49. The flag is
`dedun.experimental`, off in release.

Before K3, the refresh kinds and Dedun's legacy numbers share 30340-30343. A refresh message is
told apart by its `refresh_session` tag (`ShareRefreshEvents.isShareRefresh`), which no Dedun
event carries, so refresh works between Dedun devices in a debug build before K3. `ChatMessage`
dispatches on the current number, so a refresh round at those four numbers reaches the ledger
kinds' arms, which refuse it.

Dedun-only work:

- the Wavelength wallet's holder check reads every generation's holders, through Bennu's
  `IcebergRitualManager.everHeld`, as the Lightning wallet's does;
- `RotationHold.StatementOpen`, with its own line on the Shares section, holds a rotation while a
  deposit attribution, coin assignment, member transfer, or Wavelength or Lightning payout is
  being signed. A transfer *request* is the giver's own signature, which a rotation never
  touches, so it holds nothing.

Tests: a Wavelength wallet is still believed after a rotation; a rotation waits for a payment
approval, and not once it is signed.

Not done here:

- a release build declining a 30340, which only means something after K3;
- a Dedun device rotating with a Giza-shaped one, for the same reason;
- a test pinning that re-enrollment moves nothing in the ledger. No ledger code reads
  `KeyShareMember` or a participant id -- balances, attributions, coin assignments and payout
  destinations are all keyed by the member's Nostr key -- but that is a reading of the code, not
  a test.

## Phase 8: governed rooms, receiving and sending (D1, D2)

Bennu's port Phase 8: robust-group-admins, `0a4453da` (`Quorum`), admin changes in
convenient rooms, the signed path (`d5fbc568`, `1deff002`, `59509e42` at **49 → 50**,
`76fbca3e`, `7de80452`), and the D2 opt-in switch.

**Only after K3.** Governance takes 30370–30377 at Giza's numbers, and Giza's
`GovernanceMarker` line as it stands, so a Dedun device follows a room Giza or Bennu governs
and the other way round. Until K3, those numbers are Dedun's Wavelength wallet. This phase
can be built on a branch before then, but it does not merge to a release branch until K3 is
in a release. Phase 1's guard is lifted here, as Bennu lifted it.

**`Quorum`.** As well as the Bennu-only uses Bennu's port moved (`couldGuard`,
`ChillDkgRitualManager`'s `require`s, `SubgroupManager.MINIMUM_ADMINS`), move
`IcebergDkg.isDeployable`'s size rule if it reads `ChatRoomType`.

**Routing every commit through `GovernanceManager`.** Bennu's port routes invite,
`removeMember`, disband and `commitStagedProposals`. Dedun has the same sites. Confirm that
Dedun added no others: the Wavelength operator's stop and a signer leaving are both
application messages, not commits.

**Dedun-only: what reacts to a member leaving.** Dedun pays on departure:

- the approved-leave payout (`1a8b2124`);
- the Wavelength wallet "when a signer leaves" (`d44d5510`);
- the closing payout, once [group-wavelength-closing-payout.md](./group-wavelength-closing-payout.md)
  is built.

In a governed room a leave arrives as a SelfRemove committed by an admin inside a carrier,
not as a bare commit. Test that each of these still fires, exactly once.

**Tests:**

- Bennu's `MixedDeviceGovernedRoomJvmTest`, ported. A Giza-shaped device and two Dedun
  devices go through invite, leave and remove, and end on one epoch with no bare commit sent.
- Then an onchain payout and a Wavelength payout approval while governed. The Wavelength
  traffic is on 30400–30412 and the governance traffic is on 30370–30377, in the same room
  at the same time.
- **New:** the leave flows above, in a governed room.
- **New:** a stored legacy Wavelength statement at 30370 is still believed after this phase,
  and is never taken for a governance statement.

**Done when** those pass.

## Phase 9: promotion, demotion and the rotations they owe (D4)

Bennu's port Phase 9:

- `638d1d29`, `51eda2d2` (**50 → 51**), `bb35d73c`;
- admin-change-rotation;
- remove-founding-admins-card;
- member-row-actions;
- Bennu's own `8a91b664` and `f96d2f47`.

**D4, extended.** `8a91b664` refuses admin changes in a keyed room that keeps a Lightning
wallet. Extend it to one that keeps a Wavelength wallet, with the reason on
member-row-actions' "why this is not open" screen. Where the onchain wallet holds funds,
ask once more before an admin becomes a signer. The wording should say that in Dedun the key
also signs who owns which coins.

**The member row.** This is the largest conflict in the plan. member-row-actions rewrites the
row that Dedun's payouts work (`bd71e4ba`, `b1fa4047`) put "where the group pays this
member" on, and `ChatRoomDetailScreen` has 819 Bennu-port lines against 477 Dedun ones. Keep
Dedun's line, and add the actions around it. Then add Dedun's payout line to
member-row-actions' tests, which never saw it.

**Tests:**

- Bennu's Phase 9 suite;
- **new:** a promotion refused in a room with a Wavelength wallet;
- **new:** a whole promotion end to end: promote, sign a payout as the new admin, demote,
  and check that the share is gone.

**Done when** those pass, with the flag on.

## Phase 10: the product choices left (D3, D6)

Only after Phase 8 has been in a Dedun release long enough for members to be on it.

- **D6.** `b17a2b1a`, `01da88ce` and `f90506aa`, from the Giza mirror, since Bennu has not
  taken them.
- **D3 or D2, if the answer changes.** The rest of remove-convenient-groups, or `1ccd8c7f`.

## Phase 11: keeping up

- **The order of travel is Giza → Bennu → Dedun.** After Bennu ports a batch, rebuild
  `bennu-mirror.sh` and add the new rows to the ledger. Take straight from Giza only what
  Bennu skips and Dedun wants.
- **A kind register.** Add `docs/kinds.md` to all three repositories. It lists every kind
  each app uses and who allocated it, and new allocations go there first. Write it before K1
  ships, so that neither Giza nor Bennu takes Dedun's new range while the move is under way.
  The map after K3:
  - shared, at the same meaning in all three: 30310–30337, 30350–30366, and Giza's
    30340–30349 and 30370–30377;
  - Dedun's own: 30338–30339, 30390–30393 and 30400–30412;
  - legacy in Dedun only, never to be reallocated by Dedun: 30340–30343 and 30370–30382,
    read from stored rows and chronicles alone.

  Confirm the shared row against Bennu's and Giza's trees when the register is written.
  Dedun's 30338 and 30339 (payout destination and depositor statement) are free in both of
  them today.
- **Schemas.** Every Giza or Bennu `AutoMigration` is renumbered by hand, and the ledger
  records the mapping.
- **The submodule.** After Phase 4 the three apps pin the same `lightning-kmp-app`. Move
  Dedun's pin only to commits the other two can reach, or tell them. Dedun's own
  lightning-kmp work goes to the shared branch, as `5b95f674` did.
- **Work going the other way.** Dedun's work that is neither brand nor Dedun-only wallet is
  worth offering to Bennu by the same mirror run backwards. Examples are `e4d506b5` (a
  tearDown that waits for its scope) and `ad723865` (the default profile is the last one
  opened).

## Checking each phase

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

From Phase 3 on, also run `./gradlew check`. Phases 4, 6, 7 and 9 change what signs for the
group's money, so they also run each regtest suite on a fresh network. Bring the network
down and up before each one, because `LiquidityPurchaseRegtestJvmTest` times out when it
runs after another suite on the same network. For example:

```bash
testing/regtest/regtest.sh up
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

Then do the same for `LiquidityPurchaseRegtestJvmTest`, `MemberBranchesRegtestJvmTest` and
`PayoutAddressRegtestJvmTest`. Run `GroupWavelengthSignetJvmTest` too where a signet operator
is available.

None of this has been seen on an iOS build, and the Magpie lock has no iOS lines. The first
phase that ships needs a Mac.
