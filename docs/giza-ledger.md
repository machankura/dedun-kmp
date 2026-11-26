# Giza ledger

Every commit on `giza` after Dedun's fork point `4f1b4a43`, and what Dedun did with it. The
plan is [giza-into-dedun.md](./giza-into-dedun.md), and the phase column is the phase there.
Most commits reach Dedun through Bennu's port (`claude/giza-kmp-bennu-integration-3a5d53`,
fetched as `refs/remotes/bennu/giza-port`). A Dedun commit taken from it carries a
`Giza: <sha>` trailer, a `Bennu: <sha>` trailer, or both, so `git log --grep 'Giza: <sha>'`
finds the port.

The tables below are generated: run `docs/scripts/giza-ledger.py` after each phase. The
*Bennu* column is Bennu's verdict, copied from the port's own ledger. The *Dedun* column is
the Dedun commit that carries the change, or else the verdict Dedun inherits: *skip*, *held*
on the owner decision named, or *pending*.

Measured against `giza/giza` at `d0437d6c` and Bennu's port at `2db6dde5`.

## Baseline, on `693e522a`

- `./gradlew :composeApp:jvmTest`: 2,700 run, 1 failed, 7 skipped. The failure is
  `IdentitySwitchJvmTest` "the last profile opened is the one a cold boot resolves, until it
  is forgotten", a coroutine timeout under the full suite's load -- the flake
  [dedun-rename.md](./dedun-rename.md) and `frost-session-resend.md` describe. Bennu's
  baseline had the same one.
- `./gradlew :composeApp:m3Audit`: all budgets met. `.copy(alpha =)` 9, string literals in
  composables 21, adaptive APIs 16 (floor 12), navigation components 2 (floor 2),
  "Something went wrong" sites 3.
- The regtest suites and the Wavelength signet run: not run for the baseline. Phase 4 runs
  the regtest suites before and after it moves the toolchain, which is the comparison that
  matters; no signet operator was available.
- The trial merge of Bennu's whole port (`4e106fb5..2db6dde5`, renamed) onto `693e522a`
  conflicts in 22 files: six schema JSONs (36-41, regenerated rather than merged),
  `DedunDatabase`, `ChatMessage`, `ChatRoomDetailScreen`, `ChatRoomDetailViewModel`,
  `ChatRoomDetailUIState`, `ChatTranscript`, `strings.xml`, `composeApp/build.gradle.kts`,
  `gradle/libs.versions.toml`, five jvmTest files, `docs/README.md` and `README.md`.
  `FrostSigningManager` merges without a conflict, which says nothing about whether it is
  right: Phases 6 and 7 read it.

## The second batch, Phase 0

[giza-second-batch.md](./giza-second-batch.md), measured against Giza `a5a802c5`, fetched as
`refs/remotes/giza/giza`. Its rows are the last table below.

**High-water mark: `a5a802c5`** (2026-11-26). Every Giza commit up to it has a row here. The
next batch is measured from it: `git log --no-merges a5a802c5..giza/giza`.

- **What has shipped.** `versionCode 9` (0.1.5) was set in `dddfa68b` on 2026-10-20. That is
  before K1 (`e12565be`, 2026-11-21) and before contacts (`2fdbc2bf`, 2026-11-26), and the
  repository has no tags. An Android update needs a higher `versionCode`, so no installed
  build reads 30390-30393 or 30400-30412, or writes 30394-30399. Phase 4 retargets in place,
  with no K1' release.
- **A free block.** 30420-30442 is used by neither Giza at `a5a802c5`, Bennu at `2db6dde5`,
  Dedun at `a5ab9c10`, nor quartz 2.20.1. In 30410-30449 those trees use only Giza's inbox
  ceremony (30410-30416), Dedun's moved Wavelength kinds (30410-30412), and Marmot's
  KeyPackage, 30443.
- **Baseline** on `a5ab9c10`: jvmTest 3,085 run, 0 failed, 7 skipped.
- **The trial merge** is not done separately. Each phase cherry-picks its mirrored commits
  and records its conflicts in the phase's commit, which is the same information.

## Regtest

| when | GroupLightning | LiquidityPurchase | MemberBranches | PayoutAddress |
|---|---|---|---|---|
| before Phase 4, on `87ebf456` | pass | pass | pass | pass (3) |
| after Phase 4, on `a8331b90` | pass | pass | pass | pass (3) |
| AGP 9.5.1, Kotlin 2.4.21 and `lightning-kmp-app` at `d87af507`, on `27957e12` | pass | pass | pass | pass (3) |
| second batch done, `lightning-kmp-app` at Dedun's `d4933708`, on `ffebce46` | pass | pass | pass | pass (3) |
| the same, `lightning-kmp-app` at Giza's `3ca351af` | pass | pass alone and straight after GroupLightning in a second Gradle run (3 of 3); fails straight after it in the same run (3 of 3) | pass | pass (3) |
| the same, back on Dedun's `d4933708`, all four in one run with `--rerun` | pass | **fail**, straight after GroupLightning, as on Giza's pin | pass | pass (3) |
| the same, with the liquidity test waiting for eclair before its first channel open, all four in one run with `--rerun`, twice | pass | pass, once first and once straight after GroupLightning | pass | pass (3) |

Each suite ran on a fresh `dedun-regtest` network, apart from the last rows, which shared networks
across runs.

**`LiquidityPurchaseRegtestJvmTest` races eclair, on either pin.** GroupLightning ends by mining
its sweep's CSV delay, about 145 blocks in one burst. eclair does not follow the chain block by
block: it sits at one height and then jumps to the tip a few seconds after the burst ends. The
liquidity test opens its on-the-fly channel within a second of starting, with our node's lock
time at the tip, and its `lspCaughtUp()` wait guards only the later splices. When the test starts
in the same Gradle run, straight after GroupLightning, eclair is still behind. It refuses the
interactive transaction ("lock time is too far in the future": `lockTime=332,
currentBlockHeight=186` on Giza's pin, `lockTime=1144, currentBlockHeight=1003` on Dedun's), and
the test times out after three minutes waiting for a channel nobody will open. In a second Gradle
run the JVM's start-up gives eclair the two seconds it needs. Every pass measured there had a
margin of one second.

The pin looked to blame because Gradle ran the classes in a different order. The passing
"all four" run on Dedun's pin had the liquidity test first. The failing ones on Giza's had it
second. The investigation also found two results Gradle had restored from its build cache
(`org.gradle.caching`) without running anything; every run in the last two rows was made with
`--rerun`, and the chain's height was polled through each.

The fix is in the test. `LiquidityPurchaseRegtestJvmTest` now waits for eclair to reach the tip
(`lspCaughtUp()`) before it opens its channel, as it already did before each splice. Run straight
after GroupLightning in one Gradle run, it passed, and eclair refused nothing.

| giza | bennu port | group | phase | Bennu | Dedun | subject |
|---|---|---|---|---|---|---|
| `8dd09aa0` | — | bennu | 0 | pending | held (D7) | chore: license the project under GPL-3.0-or-later |
| `4e106fb5` | — | bennu | — | pending | skip | docs: a README that describes Bennu |
| `2a7fc7a0` | — | rebrand | — | skip | skip | docs: a plan to fork Bennu as Giza under mobi.giza |
| `afd43434` | — | rebrand | — | skip | skip | chore: Giza is a fork of Bennu; CI runs on giza (fork, Phase 1) |
| `78d913aa` | — | rebrand | — | skip | skip | feat: Giza installs beside Bennu and never opens its data (fork, Phase 2) |
| `30d5feaf` | — | rebrand | — | skip | skip | refactor: move the sources to mobi/giza, paths only (fork, Phase 3a) |
| `34e802ce` | — | rebrand | — | skip | skip | refactor: packages, classes and Res under mobi.giza (fork, Phase 3b) |
| `4f3f0da3` | — | rebrand | — | skip | skip | feat: Giza in everything a person reads and on the wire (fork, Phase 4) |
| `5b92b838` | — | rebrand | — | skip | skip | chore: tooling, docs and a guard for the name (fork, Phase 6) |
| `c0f1b3d5` | — | rebrand | — | skip | skip | build: Giza's version numbers and an upload key from outside the repo (fork, Phase 7) |
| `851ff0c8` | — | rebrand | — | skip | skip | test: Giza derives what Bennu derives, every tag pinned (fork, Phase 8) |
| `bad60129` | — | rebrand | — | skip | skip | docs: the repository is giza.mobi/giza-kmp (fork, Phase 1) |
| `caf1fb18` | — | rebrand | — | skip | skip | test: name the pinned tags in comments, not test names (fork, Phase 8) |
| `60e878e7` | — | wallet removal | — | skip | skip | docs: a phased plan to remove the wallets from Giza |
| `94b45c0b` | — | wallet removal | — | skip | skip | test: pin a recovery phrase's npub, profile id and host key (wallet removal, Phase 0) |
| `8e0f1bd3` | — | wallet removal | — | skip | skip | feat: Giza neither offers nor signs a wallet (wallet removal, Phase 1) |
| `1bd07613` | — | wallet removal | — | skip | skip | refactor: remove the group Lightning wallet and Iceberg (wallet removal, Phase 2) |
| `db38640e` | — | wallet removal | — | skip | skip | refactor: remove member guarded wallets (wallet removal, Phase 3) |
| `c6b231fa` | — | wallet removal | — | skip | skip | refactor: remove the group's on-chain wallet, guardians and the subgroup wallet policy (wallet removal, Phase 4) |
| `9c2cbf02` | — | wallet removal | — | skip | skip | feat: schema 35 -> 36, the wallets' tables and rows dropped (wallet removal, Phase 5) |
| `84be1c5b` | — | wallet removal | — | skip | skip | refactor: no node behind a recovery phrase (wallet removal, Phase 6) |
| `005cbc2f` | — | wallet removal | — | skip | skip | refactor: composeApp stops importing lightning-kmp (wallet removal, Phase 7a) |
| `b27ed2a0` | — | wallet removal | — | skip | skip | docs: retire the wallet notes, and guard against a wallet coming back (wallet removal, Phase 8) |
| `8b066795` | — | wallet removal | — | skip | skip | docs: follow Bennu by cherry-picking, and record what is left (wallet removal, Phase 9) |
| `f2059eb1` | `28c010bf` | marmot-kmp | 2 | taken | `522cbc72` | docs: the Marmot engine left quartz, and a plan to follow it (marmot-kmp, the plan) |
| `1775ae7d` | `696e83c4` | marmot-kmp | 2 | taken | `e5e4cd5e` | build: the rule that says where a Marmot class comes from (marmot-kmp, Phase 0) |
| `fc80a089` | `56548a56` | marmot-kmp | 2 | adapted | `3e5a6807` | build: the Marmot engine comes from marmot-kmp (marmot-kmp, Phase 1) |
| `9bc05c11` | `8d946e6b` | marmot-kmp | 2 | taken | `a7d4053b` | refactor: the names quartz 2.x gave the kinds (marmot-kmp, Phase 2) |
| `763edcde` | `799906c5` | marmot-kmp | 2 | taken | `afa5b2c7` | test: the app's orchestration against the library's own client (marmot-kmp, Phase 3) |
| `b2539773` | `0c8a0b47` | marmot-kmp | 2 | taken | `968452ca` | test: the state on every device still opens (marmot-kmp, Phase 4) |
| `3b2b5e36` | `3798a1ce` | marmot-kmp | 2 | taken | `384d7557` | test: the skipped keys, half closed and half pinned (marmot-kmp, Phases 5 and 6) |
| `f0f1c179` | `371597c2` | marmot-kmp | 2 | adapted | `55a80f1c` | build: the engine cannot come back from quartz (marmot-kmp, Phase 7) |
| `7fb56b8f` | `a8dc2e44` | marmot-kmp | 2 | taken | `af0732b5` | docs: the move is built, and what it does not claim (marmot-kmp) |
| `e07bb680` | `de800c1a` | marmot-kmp | 2 | adapted | `ce1a62d7` | feat: the sender ratchet as its own row, and the receive side beside it (marmot-kmp, Phase 8) |
| `1321962b` | `3c5d0505` | marmot-kmp | 2 | adapted | `0fd95ee2` | test: the version the app ships, opened by the version it builds (marmot-kmp, Phase 9) |
| `33492cff` | — | submodule refresh | — | skip | skip | docs: carrying reduced FROST enrollment up the submodule chain (plan) |
| `051b339a` | `a0d2e4c2` | submodule refresh | 4 | taken | `613b1405` | build: lightning-kmp-app with reduced FROST enrollment down the chain |
| `70a38d06` | — | submodule refresh | — | skip | skip | docs: the submodule refresh is built, and what is left to push and check |
| `d4dd87f2` | — | submodule refresh | — | skip | skip | docs: the submodule refresh is pushed, and what is left |
| `8dd710a1` | `cf210849` | share refresh | 7 | taken | `a60855ad` | docs: rotating a group's shares without moving its key (plan) |
| `4040ae74` | `91d0771a` | share refresh | 7 | adapted; backup half held (D5) | `db9e7e3d` | fix: a deleted share is gone from aux.db, and aux.db leaves the phone in no backup (frost-share-refresh, Phase 5) |
| `ebe240a4` | `9e2b06bf` | automatic key ceremony | 5 | taken | `f512a960` | docs: joining is the only approval in a key ceremony (plan) |
| `e9c413ac` | `97841ef5` | automatic key ceremony | 5 | taken | `78287114` | test: a key ceremony run across devices with real ChillDKG (automatic ceremony, Phase 1) |
| `fce51e20` | `af804765` | share refresh | 6 | adapted | `1be93830` | feat: a key's shares have generations, and signing reads the one it names (frost-share-refresh, Phase 6) |
| `ca5cda81` | `445d57e5` | automatic key ceremony | 5 | taken | `a3329959` | feat: joining is the only approval a key ceremony asks for (automatic ceremony, Phase 2) |
| `9278ccd5` | `16df4c8d` | automatic key ceremony | 5 | taken | `10a9f6e1` | feat: running key ceremonies resume when the app starts (automatic ceremony, Phase 3) |
| `d71cf0c0` | `2f51e0ae` | automatic key ceremony | 5 | adapted | `206f2d08` | feat: one approval screen for a key ceremony (automatic ceremony, Phase 4) |
| `4c75ca1b` | `97f0cf2a` | automatic key ceremony | 5 | taken | `6ca7a6a5` | feat: a stalled key ceremony says on whom, and offers abandon there (automatic ceremony, Phase 5) |
| `73fcfff0` | `2a90b3f0` | automatic key ceremony | 5 | taken | `10f24092` | feat: the coordinator's device takes a finished ceremony to its room (automatic ceremony, Phase 6) |
| `c8a529a1` | `9277ee80` | submodule refresh | 4 | doc taken; pointer in 4 | `d71198fd` | build: lightning-kmp-app with FROST share refresh down the chain (frost-share-refresh, Phase 4) |
| `f8974aec` | `b0e193a7` | share refresh | 7 | adapted | `9e3a0bed` | feat: a group rotates its shares, end to end between devices (frost-share-refresh, Phase 7) |
| `0a60da6e` | `ca129b5e` | automatic key ceremony | 5 | adapted | `c8f3f932` | feat: a ceremony's own key state signs itself (automatic ceremony, Phase 7) |
| `6491d2aa` | `aa88a097` | automatic key ceremony | 5 | taken | `117c9b43` | docs: joining is the only approval, as built (automatic ceremony, Phase 8) |
| `51615992` | `b343cfb7` | share refresh | 7 | adapted | `58ede176` | feat: the group signs its new generation, and every device deletes the old share (frost-share-refresh, Phase 8) |
| `c4fe4c9c` | `b7c15d2b` | share refresh | 7 | adapted | `f92c017a` | feat: rotating a group's shares without a member it removed (frost-share-refresh, Phase 9) |
| `1c3e0e93` | `73631aa5` | share refresh | 7 | adapted | `edad2c7c` | feat: re-enrollment, a stale member repaired into the generation the group signs with (frost-share-refresh, Phase 10) |
| `99229090` | `4f1a5c3c` | share refresh | 7 | adapted | `0aac9a02` | feat: a Shares section, behind a flag that is off in release (frost-share-refresh, Phases 11 and 13) |
| `4949f0fe` | `3a9e6e61` | share refresh | 7 | taken | `57fdaa05` | feat: the recovery walk, from the host key to the live share (frost-share-refresh, Phase 12) |
| `8278ffd5` | `fb344fd5` | share refresh | 7 | taken | `73729852` | docs: share refresh is built, phase by phase, and what is left |
| `08b5c56f` | `5a0cd033` | share refresh | 7 | taken | `58473c07` | docs: the share refresh chain is pushed |
| `fc437d05` | `f0810010` | share refresh | 7 | taken | `51ed5728` | fix: a device does not vouch for a rotation's key state on its own |
| `a25ff2e2` | `9b3e7ba9` | rotation waits for key state | 7 | taken | `f21f6256` | docs: a rotation waits for the last one to be signed |
| `159ae402` | `8be9d92f` | rotation waits for key state | 7 | taken | `fc0f892d` | feat: a rotation waits for the group to sign the last one (Phase 1) |
| `d358a0db` | `571d5b80` | rotation waits for key state | 7 | adapted | `5112bd55` | feat: the Shares section holds Rotate shares and says why (Phase 2) |
| `98567629` | `49728f45` | rotation waits for key state | 7 | taken | `6f60f483` | feat: the Shares section follows the group while it is open (Phase 3) |
| `95131248` | `0cf21a24` | rotation waits for key state | 7 | taken | `ebd72d89` | feat: the proposer is not asked about their own rotation (Phase 4) |
| `5820be9b` | `4217f056` | rotate shares, single approval | 7 | taken | `867ddaf9` | docs: taking part is the only approval a share rotation needs, as a plan |
| `f2b0aa91` | `94ddaa86` | robust-group-admins | 8 | taken | `6606e803` | docs: every founding member of a robust group is its admin, phased |
| `2664f6ca` | `8abff8be` | rotation waits for key state | 7 | taken | `d4b038a1` | docs: a rotation waits for the last one to be signed, built (Phase 5) |
| `18f426ca` | `9317fad9` | rotate shares, single approval | 7 | taken | `344fa455` | test: a rotation's approvals, counted as they are today (rotate-shares-single-approval, Phase 1) |
| `b4103bcb` | `3ecc7bd2` | rotate shares, single approval | 7 | taken | `fc484e48` | feat: taking part is a rotation's only approval (rotate-shares-single-approval, Phase 2) |
| `959c120d` | `a7e329e6` | rotate shares, single approval | 7 | taken | `d467f6f7` | feat: running rotations resume without a message (rotate-shares-single-approval, Phase 3) |
| `3efa1af5` | `f13a2478` | robust-group-admins | 8 | taken | `89ab373a` | test: every founding member of a robust group is its admin (robust-group-admins, Phase 0) |
| `69871c68` | `e6b26e0d` | rotate shares, single approval | 7 | adapted | `81032e9e` | feat: proposing a rotation is the proposer's approval (rotate-shares-single-approval, Phase 4) |
| `d7fb7103` | `51fe16f9` | robust-group-admins | 8 | taken | `84bf20be` | feat: a robust group's admin room takes its admins from the ceremony (robust-group-admins, Phase 1) |
| `8088efd7` | `c63026c4` | member-admin-promotion | 8 | taken | `c8148d5e` | docs: making a member an admin and back, a phased plan |
| `83711419` | `93235e3f` | robust-group-admins | 8 | taken | `0491c12c` | fix: a member welcomed into a room sees its admins at once (robust-group-admins, Phase 2) |
| `46eb23b7` | `554ddfdd` | rotate shares, single approval | 7 | adapted | `58c58a1f` | feat: a rotation's key state signs itself (rotate-shares-single-approval, Phase 5) |
| `2ed83c55` | `8ba35258` | member-admin-promotion | 1 | taken | `f1954991` | fix: an admin's refused leave is shown, not thrown out of the view model (member-admin-promotion, Phase 10a) |
| `53acae68` | `cccb2c12` | member-admin-promotion | 8 | adapted | `5e87c0af` | feat: a member's admin flag is true from the room's first moment (member-admin-promotion, Phase 1) |
| `4671a86a` | `f4c03ad0` | robust-group-admins | 8 | taken | `7ebb153a` | feat: a device checks its admin room against the ceremony it took part in (robust-group-admins, Phase 3) |
| `11f2cb2b` | `baedb44d` | rotate shares, single approval | 7 | adapted | `0edaccc2` | feat: the Shares section, with one approval (rotate-shares-single-approval, Phase 6) |
| `19f0fc0f` | `8bc893c2` | member-admin-promotion | 8 | adapted | `14950509` | feat: the room says who became an admin, and who stopped (member-admin-promotion, Phase 2) |
| `99261876` | `f1b4a0f9` | rotate shares, single approval | 7 | taken | `817a6e00` | docs: taking part is the only approval, as built (rotate-shares-single-approval, Phase 7) |
| `1dc94ad4` | `004be0ff` | robust-group-admins | 8 | taken | `e6a9d20c` | feat: a founding invite that never went out is finished (robust-group-admins, Phase 4) |
| `baf63b8d` | `f627c8e3` | robust-group-admins | 8 | adapted | `3ac7ee6c` | feat: the room's details say who administers it, and whether the founders do (robust-group-admins, Phase 5) |
| `d95412ff` | `c0be080e` | member-admin-promotion | 8 | adapted | `172ed1b1` | feat: an admin makes a member an admin, or not, in a convenient room (member-admin-promotion, Phase 6) |
| `01e33f5a` | `7defde35` | robust-group-admins | 8 | adapted | `b7082db0` | fix: rooms joined before the Welcome wrote flags are caught up at start (robust-group-admins, Phase 6) |
| `a95d6db1` | `67252e9e` | robust-group-admins | 8 | taken | `65231568` | docs: every founding member of a robust group is its admin, built (robust-group-admins, Phase 7) |
| `ef33349e` | `551a74bc` | member-admin-promotion | 8 | adapted | `e56ae59f` | feat: the member row's menu, and leaving as an admin (member-admin-promotion, Phases 10 and 11, convenient rooms) |
| `6e3ed2bb` | `5980e86d` | member-admin-promotion | 8 | taken | `ce370136` | feat: a robust group keeps at least three admins, and never fewer than its quorum (member-admin-promotion, Phase 7, app side) |
| `d5fbc568` | `9b8eb8da` | member-admin-promotion | 8 | adapted | `8aa702bb` | feat: the statement a keyed room's quorum signs before it changes (member-admin-promotion, Phase 3) |
| `32360994` | `2a28604b` | member-admin-promotion | 8 | taken | `d3f1a7a7` | docs: admin promotion is built for convenient rooms, and what waits on Phase 0 |
| `b1d62d9e` | `9574b2e3` | member-admin-promotion | 8 | taken | `9916919d` | docs: the commit travels inside the group's signature; no upstream work |
| `1deff002` | `7ad607f5` | member-admin-promotion | 8 | adapted | `2a054f88` | feat: a governed room applies a commit only from inside the group's signature (member-admin-promotion, Phase 4a) |
| `dd00d0f7` | `aca3e169` | member-admin-promotion | 8 | adapted | `f7faa82f` | feat: a keyed room's changes can be made to need the group's signature (member-admin-promotion, Phase 4b) |
| `9008d4fe` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | docs: plan to make every group a member creates robust (remove-convenient-groups) |
| `b9396643` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | test: pin every new group as robust, ignored until it is (remove-convenient-groups, Phase 0) |
| `0a4453da` | `362b708d` | remove-convenient-groups | 8 | adapted | `347b6626` | refactor: Quorum takes the threshold rules from ChatRoomType (remove-convenient-groups, Phase 1) |
| `eb2c8aa0` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: every group a member creates is robust (remove-convenient-groups, Phase 2) |
| `59509e42` | `54567e99` | member-admin-promotion | 8 | adapted | `fd6a48c0` | feat: a change to a governed room is proposed, signed by the group, then published (member-admin-promotion, Phase 5) |
| `c2010abe` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: a room with no key behind it takes no new members (remove-convenient-groups, Phase 2b) |
| `7fc1233b` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: the member picker holds the three-person floor (remove-convenient-groups, Phase 3) |
| `03704db3` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | refactor: ChatRoomType goes, and the screen is named for the quorum (remove-convenient-groups, Phase 4) |
| `76fbca3e` | `7c1d79e6` | member-admin-promotion | 8 | adapted | `9f00093e` | feat: a governed room's ordinary changes go through the group's signature (member-admin-promotion, Phase 5b) |
| `7de80452` | `58d138b8` | member-admin-promotion | 8 | taken | `19bf8924` | fix: the statement's KDoc names the retired wallet range by its constant |
| `9d2a13b8` | `43069679` | member-admin-promotion | 8 | taken | `a15ac22d` | docs: admin promotion is built but for its share work, and where the build chose differently |
| `41409434` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: a group run by one person says so, and is still joined (remove-convenient-groups, Phase 5) |
| `9b7b1a5f` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | docs: every group a member makes is robust, built (remove-convenient-groups, Phase 6) |
| `546b2a2b` | `bac8b963` | robust-group-admins | 8 | taken | `b3e289a4` | docs: Phase 1's flag writer is robust-group-admins' AdminFlags |
| `51eda2d2` | `f27c1153` | member-admin-promotion | 9 | adapted | `5f1e0c50` | feat: a keyed room demotes an admin with the group's signature, then rotates them out (member-admin-promotion, Phase 9) |
| `638d1d29` | `f3d16fc2` | member-admin-promotion | 9 | adapted | `7c2d5b9a` | feat: a member is made an admin of a keyed room, and enrolled into its key (member-admin-promotion, Phase 8) |
| `c64ba8aa` | `6928eeb0` | member-admin-promotion | 9 | taken | `64b71e9c` | docs: Phases 8 and 9 are built under the experimental enrollment, and where the build chose differently |
| `b17a2b1a` | — | marmot-v2 Phase 9 | 10 | pending (D6) | pending (D6) | feat: new rooms are made on the Marmot current profile (marmot-v2, Phase 9, the flip) |
| `01da88ce` | — | marmot-v2 Phase 9 | 10 | pending (D6) | pending (D6) | feat: the legacy KeyPackage slot is retired (marmot-v2, Phase 9, the retirement) |
| `f90506aa` | — | marmot-v2 Phase 9 | 10 | pending (D6) | pending (D6) | docs: the Marmot v2 rollout is done -- new rooms on the current profile, the legacy slot retired (v2 Phase 9) |
| `6aae5652` | `63953322` | member-admin-promotion | 8 | taken | `45e96f50` | fix: a member invited into a keyed room says it follows signed commits on joining |
| `950ed247` | `4b549646` | member-admin-promotion | 8 | taken | `d9e59b27` | feat: a keyed room switches on the group's signature by itself |
| `35d0b1b0` | `cfa35073` | member-row-actions | 9 | taken | `c80dcec7` | docs: a plan for member rows that offer every action and explain the ones not open |
| `76f8e053` | `9ab9f6aa` | member-row-actions | 9 | taken | `eda7ac05` | feat: member actions carry the reasons they are not open (member-row-actions, Phase 1) |
| `b09fd320` | `513c866d` | member-row-actions | 9 | adapted | `f561520e` | feat: every viewer gets the member rows' options, with their reasons (member-row-actions, Phase 2) |
| `1ccd8c7f` | — | member-admin-promotion | 10 | skipped (D2) | skip | feat: a keyed room is born needing the group's signature; the switch is gone |
| `7c39c7b9` | `2ac712f1` | member-row-actions | 9 | adapted | `506c026c` | feat: a screen that says why a member action is not open (member-row-actions, Phase 4) |
| `3447fa11` | `5f0745cd` | member-row-actions | 9 | adapted | `334fd393` | feat: every other member's row offers every action (member-row-actions, Phase 3) |
| `709adb57` | `9079efc0` | member-row-actions | 9 | taken | `919958e2` | feat: the member rows say what is still on its way (member-row-actions, Phase 5) |
| `599cef7d` | `ec1bf0b1` | member-row-actions | 9 | taken | `1ef60d91` | docs: the member rows are built, and where the build chose differently (member-row-actions, Phase 6) |
| `bb35d73c` | `3c2d7d6b` | member-admin-promotion | 9 | adapted | `0007e13d` | feat: an admin of a keyed room steps down by asking another admin |
| `aaabc519` | `4b39341b` | remove-founding-admins-card | 9 | taken | `80095354` | docs: a plan to remove the "Founding members aren't admins" card |
| `47eb4852` | `2408b20c` | remove-founding-admins-card | 9 | adapted | `0745f802` | test: pin what outlives the founding-admins check (remove-founding-admins-card, Phase 0) |
| `1efe486e` | `ab32600d` | remove-founding-admins-card | 9 | adapted | `608f973b` | feat: the "Founding members aren't admins" card goes (remove-founding-admins-card, Phase 1) |
| `4550201d` | `373c6e7f` | admin-change-rotation | 9 | taken | `9dc4f324` | docs: a plan for admin changes that carry their rotation, and take turns with it |
| `b04f3047` | `2019dce4` | remove-founding-admins-card | 9 | adapted | `016a12ed` | refactor: the detail screen stops checking founding admins (remove-founding-admins-card, Phase 2) |
| `b5c1d9eb` | `8729cc04` | remove-founding-admins-card | 9 | taken | `c9eba184` | feat: the Welcome stops announcing missing founding admins (remove-founding-admins-card, Phase 3) |
| `d21483ef` | `947605db` | remove-founding-admins-card | 9 | adapted | `5703a2e4` | refactor: the founding-admins check and its repair go (remove-founding-admins-card, Phase 4) |
| `e682b06f` | `b0092b91` | remove-founding-admins-card | 9 | taken | `80fdba42` | docs: the founding-admins card is gone, and where the notes say so (remove-founding-admins-card, Phase 5) |
| `926f79da` | `f32aa019` | admin-change-rotation | 9 | taken | `437a5f97` | test: pin what admin changes and rotations do to each other today (admin-change-rotation, Phase 1) |
| `521135c8` | `af8d4bee` | admin-change-rotation | 9 | adapted | `f43a9d8c` | feat: one rule for what a rotation and an admin change wait on (admin-change-rotation, Phase 2) |
| `c7d46258` | `bc9ff6a0` | admin-change-rotation | 9 | adapted | `1c4fb484` | feat: admin changes and rotations take turns (admin-change-rotation, Phases 3 and 4) |
| `8bf0a240` | `ce21e236` | admin-change-rotation | 9 | adapted | `1a271004` | feat: the rotation an admin change owes asks nobody (admin-change-rotation, Phase 5) |
| `d51fdde0` | `41466e33` | quartz/mls/marmot update | 3 | taken | `e3ad4380` | docs: plan the move to quartz 2.20.1, mls-kmp 1.4.0, marmot-kmp 2.3.0 and crypto-kmp 2.1.1 |
| `9fddb0c3` | `dc04d41d` | admin-change-rotation | 9 | taken | `e41e239c` | feat: the first promotion rotates the shares itself (admin-change-rotation, Phase 6) |
| `3a1dbf87` | `bbcf8e7a` | quartz/mls/marmot update | 3 | taken | `e9ebfcde` | docs: Magpie adopted, and mls-kmp 1.4.0 is a tag not a release (quartz-mls-marmot-update, Phase 0) |
| `971dd459` | `1ace8417` | quartz/mls/marmot update | 3 | adapted | `b2bd1390` | build: the Gradle daemon runs on Java 21 whatever java is on the path (quartz-mls-marmot-update, Phase 1) |
| `cc21d683` | `cd67377f` | admin-change-rotation | 9 | adapted | `bc6a15de` | feat: the Shares section says whose share a rotation takes away; the record (admin-change-rotation, Phases 7 and 8) |
| `f56be2b3` | `eb4c120e` | quartz/mls/marmot update | 3 | taken | `d66b1330` | build: quartz 2.20.0, marmot-kmp 2.3.0, mls-kmp 1.3.1 and crypto-kmp 2.1.1, through Magpie (quartz-mls-marmot-update, Phase 2) |
| `c81e4a2d` | `6db5af5c` | quartz/mls/marmot update | 3 | taken | `2dbf74c0` | test: an old epoch-0 room moves forward on mls-kmp 1.3.1 (quartz-mls-marmot-update, Phase 3) |
| `a08a8789` | `822b924b` | quartz/mls/marmot update | 3 | taken | `027fd301` | docs(tools): the shipped engine against the old one, both ways, three epochs (quartz-mls-marmot-update, Phase 3) |
| `0b43295a` | `4e63c4f1` | quartz/mls/marmot update | 3 | taken | `5acf9a70` | test: pin the state version a save is written at (quartz-mls-marmot-update, Phase 4) |
| `0ecc179d` | `269410be` | quartz/mls/marmot update | 3 | taken | `e3cb2816` | build: quartz 2.20.1, one past marmot's pin (quartz-mls-marmot-update, Phase 5a) |
| `d6c34a77` | `1ff82201` | admin-change-rotation | 9 | taken | `fe1d76bb` | fix: a rotation reads the admin policy alone, now the founding-admins check is gone |
| `7222df01` | `5f38e389` | quartz/mls/marmot update | 3 | taken | `83c00bc8` | build: the lock holds only what resolves (quartz-mls-marmot-update, Phase 6, Linux half) |
| `7fe64930` | `bea28974` | quartz/mls/marmot update | 3 | taken | `29e345dc` | build: check fails when the catalogue and the Magpie lock disagree (quartz-mls-marmot-update, Phase 7) |
| `a6d04ec5` | `1775b3ff` | quartz/mls/marmot update | 3 | taken | `0aaad433` | docs: the notes that still said quartz 2.7.3 (quartz-mls-marmot-update, Phase 7) |
| `25f14aa4` | `7ebcd810` | dependency update | 4 | taken | `b925b3f6` | docs: a phased plan to update every dependency outside the Magpie four |
| `2e07fda0` | — | dependency update | — | skip | skip | docs: the baselines (dependency-update, Phase 0) |
| `dafcff2c` | `2f889b25` | dependency update | 4 | taken | `b5f0ad60` | build: the catalogue entries nothing reads (dependency-update, Phase 1) |
| `56df0a2b` | `f5ef3160` | dependency update | 4 | taken | `a0002de5` | build: serialization 1.13.0, kermit 2.2.0, okio 3.18.2 (dependency-update, Phase 2a) |
| `a4f54ad4` | — | icon | — | skip | skip | Update icon. |
| `3371fb3a` | `e227e0a7` | dependency update | 4 | taken | `e765ab0c` | build: ktor 3.6.1 (dependency-update, Phase 2b) |
| `873d76aa` | — | icon | — | skip | skip | docs: the sphinx icon on iOS, desktop and in the app, a phased plan (giza-app-icon) |
| `572515b9` | `c91a674e` | dependency update | 4 | taken | `d5043c0b` | build: room3 3.0.3, sqlite 2.7.1, DataStore 1.3.0, KSP 2.3.14 (dependency-update, Phase 2c) |
| `16dde956` | — | icon | — | skip | skip | docs: the sphinx master, and the baseline it is matched against (giza-app-icon, Phase 0) |
| `0cbc9cc5` | — | icon | — | skip | skip | build: one generator for every icon outside Android (giza-app-icon, Phase 1) |
| `ae418b5d` | `9ddfa8c0` | dependency update | 4 | taken | `3c2ab780` | build: Compose 1.12.2, material3 1.12.0-alpha03, navigation 2.10.0, coil 3.6.3, qrose 1.3.1, Hot Reload 1.3.0 (dependency-update, Phase 3) |
| `554c653d` | — | icon | — | skip | skip | feat: the in-app logo is the sphinx on black (giza-app-icon, Phase 2) |
| `658a69a3` | — | icon | — | skip | skip | feat(ios): the sphinx app icon in light, dark and tinted (giza-app-icon, Phase 3) |
| `aaf037bf` | — | icon | — | skip | skip | feat(desktop): the sphinx on the installers and the window (giza-app-icon, Phase 4) |
| `b6d4c709` | — | icon | — | skip | skip | docs: the icon is built, and what is left to see on devices (giza-app-icon, Phase 5) |
| `3f8336fc` | `cc3a8e30` | dependency update | 4 | taken | `9dd3732f` | build: Gradle 9.9.2 (dependency-update, Phase 4) |
| `3e0a16b1` | `cb6912ca` | dependency update | 4 | taken | `ce0d3bc1` | fix: the three lint errors AGP 9.5's lint finds in commonMain |
| `a3c0b830` | `86679a93` | dependency update | 4 | adapted | `27957e12` | build: AGP 9.5.1 across the composite (dependency-update, Phase 5) |
| `302ce3ca` | `01d4d8e7` | dependency update | 4 | taken | `27957e12` | build: Kotlin 2.4.21 across the composite (dependency-update, Phase 6) |
| `65a2507a` | `fc7a045d` | dependency update | 4 | taken | `fe17f43b` | docs: the comments that named the old versions (dependency-update, Phase 7) |
| `36be1efc` | — | — | — | pending | `8692bebd` | fix: one Shares row per member, so a reinstated admin no longer crashes the room detail |

## Bennu-only commits in the port

| bennu port | Dedun | subject |
|---|---|---|
| `040e5746` | skip: Bennu's own notes | docs: a phased plan to take Giza's work back into Bennu |
| `e242ab59` | skip: Bennu's own notes | docs: the Giza mirror, a ledger of every Giza commit, and the baseline (giza-into-bennu, Phase 0) |
| `360f772c` | `c33a3fb3` | feat: Bennu makes no bare commit in a room Giza governs (giza-into-bennu, Phase 1) |
| `4f49e6a7` | skip: Bennu's own notes | docs: the marmot-kmp group is in, and Bennu's schema is 36 (giza-into-bennu, Phase 2) |
| `505df0ac` | skip: Bennu's own notes | docs: quartz 2.20.1 through Magpie is in (giza-into-bennu, Phase 3) |
| `9fd354c6` | skip: Bennu's own notes | docs: the toolchain and the submodule chain are in, measured in Bennu (giza-into-bennu, Phase 4) |
| `168f4524` | `7a13435d` | test: an automatic ceremony's subgroup keeps its wallet policy and guardian keys (giza-into-bennu, Phase 5) |
| `44fba704` | skip: Bennu's own notes | docs: share generations are in, with Bennu's wallets signing at them (giza-into-bennu, Phase 6) |
| `993f26b8` | `04699c7d` | feat: rotation and the Lightning wallet, and a rotation that waits for a spend (giza-into-bennu, Phase 7) |
| `5ce77c08` | skip: Bennu's own notes | docs: rotation is in, and D5 stays open (giza-into-bennu, Phase 7) |
| `b86b3a0d` | skip: Bennu's own notes | docs: governed rooms are in, receiving and sending (giza-into-bennu, Phase 8) |
| `1785a284` | skip: Bennu's own notes | docs: Bennu keeps convenient groups, said where Giza's plan to remove them landed |
| `8a91b664` | `7e2c82ec` | feat: no admin change in a keyed room that keeps a Lightning wallet (giza-into-bennu, Phase 9, D4) |
| `f96d2f47` | `c08e8d42` | test: an admin's row is labelled from its admin flag, as the screen now reads it |
| `0ba1c5bd` | skip: Bennu's own notes | docs: promotion, demotion and their rotations are in (giza-into-bennu, Phase 9) |
| `a2528010` | skip: Bennu's own notes | docs: Phases 0 to 9 are built, and what is left (giza-into-bennu) |
| `2db6dde5` | `e47478cf` | test: a Giza-shaped device and two Bennu devices share one governed room (giza-into-bennu, Phase 8) |

## The second batch

Giza after `d0437d6c`, planned in [giza-second-batch.md](./giza-second-batch.md). Bennu has ported none of it, so each commit comes through `giza-mirror.sh` and then `bennu-mirror.sh`. The phase column is the phase there.

| giza | group | phase | Dedun | subject |
|---|---|---|---|---|
| `baf603ac` | confederations | — | skip (D12) | docs: plan for carrying bitcoin-kmp's confederation API up to Giza |
| `1b3b5f86` | confederations | — | skip (D12) | docs: confederation update, Phase 0 verified |
| `4555f5d1` | confederations | — | skip (D12) | build: lightning-kmp-app with bitcoin-kmp's confederation down the chain |
| `37079ee4` | confederations | — | skip (D12) | test: Giza reaches bitcoin-kmp's confederation API (confederation update, Phase 4) |
| `bdb0b3f0` | group profile | 5 | `53ea1c1b` | docs: the group's nostr profile and posts, planned as a port from Curare (group-nostr-profile, Phase 0) |
| `b4d0e483` | group profile | 5 | `6562678a` | test: pin what a group-signed profile and post do today (group-nostr-profile, Phase 1) |
| `b944a411` | group profile | 5 | `6562678a` | feat: the group's profile and posts read off what it signed (group-nostr-profile, Phase 2) |
| `5b75c2c2` | group profile | 5 | `6562678a` | feat: the transcript says when the group signs its profile or a post (group-nostr-profile, Phase 3) |
| `09649d9a` | group profile | 5 | `6562678a` | feat: decline a profile or a post nothing could show, out loud (group-nostr-profile, Phase 4) |
| `add309f7` | group profile | 5 | `c540cb4b` | feat: the group's nostr profile, seen and proposed (group-nostr-profile, Phase 5) |
| `ffde0553` | group profile | 5 | `a4400f06` | feat: the group's posts, seen and proposed (group-nostr-profile, Phase 6) |
| `f98378ca` | group profile | 5 | `2f63531c` | feat: a group-signed profile or post sent to relays (group-nostr-profile, Phase 7) |
| `aa820c20` | group profile | 5 | `6c75161f` | feat: an unsigned profile or post, pasted for the group to sign (group-nostr-profile, Phase 8) |
| `34c8faa4` | group profile | 5 | `36b86add` | fix: the broadcast screen sends only the group's profile and posts (group-nostr-profile, Phase 7) |
| `d039daf6` | group profile | 5 | `0fe03bc5` | docs: the group's nostr profile and posts are built, and the notes they touched say so (group-nostr-profile, Phase 9) |
| `6cb9dd7d` | confederations | — | skip (D12) | docs: a group made of groups, planned (confederation, Phase 0) |
| `e84fc9b5` | confederations | — | skip (D12) | docs: the three checks before any code (confederation, Phase 0) |
| `abe555e5` | confederations | — | skip (D12) | test: a confederation signs as its room's id, and today's answer to its kinds (confederation, Phase 1) |
| `f1ad108b` | confederations | — | skip (D12) | feat: the confederation's events, its sealed admin list and its state (confederation, Phase 2) |
| `8b38dc87` | confederations | — | skip (D12) | feat: where a device keeps what it has heard of a confederation (confederation, Phase 4) |
| `24e4eec3` | confederations | — | skip (D12) | test: a shipped profile upgrades to schema 43 (confederation, Phase 4) |
| `f7b361c9` | confederations | — | skip (D12) | feat: a device declines a confederation event it finds wrong, out loud (confederation, Phase 3) |
| `bd1b5d56` | confederations | — | skip (D12) | feat: confederation events reach relays, and come back from them (confederation, Phase 5) |
| `0f68cc53` | confederations | — | skip (D12) | feat: proposing a confederation, and the lines a group's room gets about one (confederation, Phase 6) |
| `8408cd46` | confederations | — | skip (D12) | feat: a group answers a confederation, and three groups agree end to end (confederation, Phase 7) |
| `190cb9e9` | confederations | — | skip (D12) | feat: the coordinator's device says what forming a confederation would take (confederation, Phase 8) |
| `ea7cf819` | confederations | — | skip (D12) | docs: the confederation's rounds made public upstream, planned |
| `9be5a635` | confederations | — | skip (D12) | feat: the confederation's rounds reach Giza, one role per device (confederation, Phase 9) |
| `e1a18f83` | confederations | — | skip (D12) | feat: the confederation's roster, the same bytes on every device (confederation, Phase 10) |
| `02f04143` | confederations | — | skip (D12) | feat: the confederation's key ceremony, one person per device (confederation, Phase 11) |
| `615afc30` | confederations | — | skip (D12) | feat: what a finished ceremony leaves: the confederation, certified (confederation, Phase 12) |
| `c9f7d08b` | confederations | — | skip (D12) | feat: the confederation's room, the key's own (confederation, Phase 13) |
| `8bb34e6c` | confederations | — | skip (D12) | docs: Part B is built, and what it chose differently (confederation, Phases 10-13) |
| `e723e70a` | confederations | — | skip (D12) | feat: a confederation signs as itself, one person per device (confederation, Phase 14) |
| `bacd53a1` | confederations | — | skip (D12) | feat: what a confederation signs: its profile, its posts and its charter (confederation, Phase 15) |
| `e909313c` | confederations | — | skip (D12) | docs: Part C is built, and what it chose differently (confederation, Phase 16) |
| `ccc439b2` | group inbox | 6 | `52c4eac5` | docs: the group's inbox, planned as NIP-4E on a group's npub (group-inbox, Phase 0) |
| `877f5bbe` | group inbox | 6 | `49d51b5a` | test: a threshold-1 ceremony leaves every admin one secret, and a group-signed seal verifies (group-inbox, Phase 0) |
| `dd16df9c` | group inbox | 6 | `b1ab1469` | test: pin what a group's inbox events do today (group-inbox, Phase 1) |
| `e215cff8` | group inbox | 6 | `47be48b0` | feat: the group inbox's wire, in one package (group-inbox, Phase 2) |
| `1f66c39f` | group inbox | 6 | `9bb2a13b` | feat: where a group's inbox is kept, schema 44 to 45 (group-inbox, Phase 3) |
| `057d0b79` | group inbox | 6 | `b8e28b61` | feat: the group's inbox key, made by its admins at threshold 1 (group-inbox, Phase 4) |
| `1c29df61` | group inbox | 6 | `06bb9f36` | feat: the group announces its inbox, and declines a wrong announcement out loud (group-inbox, Phase 5) |
| `5370527a` | group inbox | 6 | `1fcbf318` | feat: the group's inbox, read on its admins' devices (group-inbox, Phase 6) |
| `12a93bd4` | group inbox | 6 | `22303299` | feat: the group's inbox, seen (group-inbox, Phase 7) |
| `d21d6ecd` | group inbox | 6 | `84712e0b` | feat: the group answers from its inbox, once its quorum has read and signed the reply (group-inbox, Phase 8) |
| `6b59282e` | group inbox | 6 | `51b71c42` | test: two pins Phase 8 moved, moved with it (group-inbox, Phase 8) |
| `8bc7ab3e` | group inbox | 6 | `43a27bfd` | feat: the inbox key changes with the group's admins (group-inbox, Phase 9) |
| `31bcb3a3` | group inbox | 6 | `454ebe93` | docs: the group's inbox is built, and what it chose differently (group-inbox, Phase 10) |
| `dae0a345` | group messages | 6 | `a3b03215` | docs: the group's direct messages, by threshold ECDH (group-threshold-ecdh, Phase 0) |
| `0d339238` | group messages | 6 | `f12f3463` | test: threshold ECDH's six premises, against the real libraries (group-threshold-ecdh, Phase 0) |
| `c8df3633` | group messages | 6 | `3f6012f9` | test: what Giza does with a group's direct messages today (group-threshold-ecdh, Phase 1) |
| `77e154d4` | group messages | 6 | `2868e2fd` | feat: threshold ECDH's arithmetic, alone (group-threshold-ecdh, Phase 2) |
| `fcdc2db7` | group messages | 6 | `7f7a9576` | feat: the wire for a group's direct messages (group-threshold-ecdh, Phase 3) |
| `42ebfc45` | group messages | 6 | `530daa40` | feat: storage for a group's direct messages, schema 45 (group-threshold-ecdh, Phase 4) |
| `311f61ab` | group messages | 6 | `2d6976a2` | feat: the admins answer and combine rounds (group-threshold-ecdh, Phase 5) |
| `b2cb0aaa` | group messages | 6 | `a59c1fab` | feat: where the group receives direct messages (group-threshold-ecdh, Phase 6) |
| `ffb254da` | group messages | 6 | `0e327ff9` | feat: receiving the group's direct messages (group-threshold-ecdh, Phase 7) |
| `0e952f99` | group messages | 6 | `cb538548` | feat: the group's messages, seen (group-threshold-ecdh, Phase 8) |
| `5683199c` | notifications | 3 | `02fad206` | docs: notifications, and the background sync they need, planned (notifications, Phase 0) |
| `be6e29b2` | notifications | 3 | `a9058e83` | test: pin what notifications change, before they change it (notifications, Phase 1) |
| `7ddc3f3b` | group messages | 6 | `68e36d60` | feat: the group writes, and every signer reads it first (group-threshold-ecdh, Phase 9) |
| `1ac51905` | notifications | 3 | `b41c758f` | refactor: one runtime per process, owned by the process (notifications, Phase 2) |
| `6f33f6f1` | group messages | 6 | `65588a39` | feat: admin changes for the group's direct messages (group-threshold-ecdh, Phase 10) |
| `49fb8a92` | group messages | 6 | `10a6f950` | test: a non-admin device now forgets the wraps it held (group-threshold-ecdh, Phase 10) |
| `082d0971` | group messages | 6 | `45daf218` | docs: the group's direct messages, built (group-threshold-ecdh, Phase 11) |
| `c248ab52` | notifications | 3 | `38719f7f` | feat: read marks, mute and the notification ledger, schema 46 to 47 (notifications, Phase 3) |
| `b6db6d04` | notifications | 3 | `fee7bb05` | feat: a room on screen is read, and what is not is counted (notifications, Phase 4) |
| `f2e57edc` | notifications | 3 | `fee7bb05` | feat: which lines notify, and when not, decided in one pure place (notifications, Phase 5) |
| `467d14c1` | notifications | 3 | `1c49aebe` | feat: new lines become notifications while the app runs (notifications, Phase 6) |
| `53d1beb9` | notifications | 3 | `1c49aebe` | feat: the switches, on the profile, and mute on the room (notifications, Phase 7) |
| `74c7a0dd` | notifications | 3 | `c61dd126` | fix: a minimised desktop stays connected, and iOS gets its grace (notifications, Phase 8) |
| `4a6f45aa` | notifications | 3 | `c61dd126` | feat: one bounded sync while off screen, on the app's own engines (notifications, Phase 9) |
| `c9faf62f` | notifications | 3 | `55b5d859` | feat: Android syncs every fifteen minutes while closed (notifications, Phase 10) |
| `a60f6b93` | notifications | 3 | `55b5d859` | feat: Stay connected, an opt-in service that holds the relays open (notifications, Phase 10) |
| `51c2423a` | notifications | 3 | `4bf88358` | feat: iOS syncs when BGTaskScheduler lets it (notifications, Phase 11) |
| `206a44e2` | notifications | 3 | `4bf88358` | feat: the desktop closes to the tray and notifies from it (notifications, Phase 12) |
| `ac8d6488` | notifications | 3 | `4bf88358` | feat: mark as read and reply, from the notification (notifications, Phase 13) |
| `900dfd8c` | notifications | 3 | `02fad206` | docs: notifications are built, and what they chose differently (notifications, Phase 14) |
| `f78bd16b` | confederations | — | skip (D12) | docs: a confederation with no coordinator (confederation-without-coordinator, Phase 0) |
| `b2f22f2c` | confederations | — | skip (D12) | test: the premises of a confederation with no coordinator (confederation-without-coordinator, Phase 0) |
| `e2de3fa8` | confederations | — | skip (D12) | test: pin the coordinator's part in a confederation (confederation-without-coordinator, Phase 1) |
| `c3d70776` | confederations | — | skip (D12) | feat: the confederation's statements as rumors (confederation-without-coordinator, Phase 2) |
| `f64f1619` | confederations | — | skip (D12) | feat: the confederation's statements between inboxes, and a ceremony nobody opens (confederation-without-coordinator, Phases 3 to 5 and 7) |
| `66932a84` | confederations | — | skip (D12) | feat: the confederation's screens look for every group's inbox (confederation-without-coordinator, Phase 6) |
| `bad5d122` | confederations | — | skip (D12) | feat: who makes the confederation's room, when nobody coordinates (confederation-without-coordinator, Phase 8) |
| `d1513ef2` | confederations | — | skip (D12) | docs: the record of a confederation with no coordinator (confederation-without-coordinator, Phase 9) |
| `3b0b3c9a` | icon | — | skip | Update icon |
| `dd812950` | second group | 5 | `a7dde566` | test: the premises of a second group from the same people (nip17-multiple-ceremonies, Phase 0) |
| `9c2f55aa` | second group | 5 | `cb33b677` | feat: the same people can open a second group's ceremony (nip17-multiple-ceremonies, Phase 1) |
| `1d6f77cf` | second group | 5 | `10c03bfe` | feat: making a group names its ceremony, and says what these people already have (nip17-multiple-ceremonies, Phase 2) |
| `4baf5d8f` | second group | 5 | `f9966a0e` | feat: nothing asks a NIP-17 room for its one ceremony (nip17-multiple-ceremonies, Phase 3) |
| `d1a9b995` | disappearing | 7 | `685464be` | docs: a plan for disappearing NIP-17 messages (disappearing-messages, Phase 0) |
| `4e16592a` | second group | 5 | `7a2e8a01` | feat: a ceremony's lines, notice and join screen name the group it makes (nip17-multiple-ceremonies, Phase 4) |
| `c5618a70` | disappearing | 7 | `b949c954` | docs: disappearing messages, the Phase 0 checks answered (disappearing-messages, Phase 0) |
| `962bdbf6` | disappearing | 7 | `f85bf81a` | feat: when a NIP-17 rumor expires, as pure rules (disappearing-messages, Phase 1) |
| `088e86ac` | second group | 5 | `73a79696` | feat: a NIP-17 room lists the groups made in it (nip17-multiple-ceremonies, Phase 5) |
| `729efdac` | second group | 5 | `7d305b9f` | test: the same people make two groups, end to end (nip17-multiple-ceremonies, Phase 6) |
| `50f73e17` | confederations | — | skip (D12) | feat: a member who has not joined finds the key ceremony from its room |
| `03e2d10d` | second group | 5 | `5e70fbc9` | docs: the record of more than one group from the same people (nip17-multiple-ceremonies, Phase 7) |
| `b7f6df0c` | disappearing | 7 | `5d8f55f2` | feat: storage for disappearing messages, v50 (disappearing-messages, Phase 2) |
| `ae0468bb` | disappearing | 7 | `9b7784ab` | feat: every NIP-17 payload is stamped with its expiry (disappearing-messages, Phase 3) |
| `a4c12eee` | disappearing | 7 | `55a62e11` | feat: what has disappeared is deleted from the device (disappearing-messages, Phase 4) |
| `3e05a28b` | confederations | — | skip (D12) | docs: a confederation's profile, decided by its groups (confederation-profile, Phase 0) |
| `c50288a9` | disappearing | 7 | `324bce29` | feat: a ceremony's rounds leave the device when its session is done (disappearing-messages, Phase 5) |
| `4994248d` | confederations | — | skip (D12) | feat: the wire and storage of a confederation's profile changes (confederation-profile, Phase 1) |
| `2e747f90` | disappearing | 7 | `39013eee` | feat: the disappearing-messages setting, and the conversation showing it (disappearing-messages, Phase 6) |
| `fff891a4` | second group | 5 | `e5e84cfd` | feat: a finished ceremony's screen names the group it made (nip17-multiple-ceremonies) |
| `c293514d` | confederations | — | skip (D12) | feat: a group proposes, endorses or objects to its confederation's profile (confederation-profile, Phase 2) |
| `44cbe596` | confederations | — | skip (D12) | feat: each group's room hears of a change to its confederation's profile (confederation-profile, Phase 3) |
| `ada95867` | disappearing | 7 | `fd00e9a4` | feat: what a group sends disappears too (disappearing-messages, Phase 7) |
| `d30cea78` | disappearing | 7 | `ff5da69d` | docs: the record of disappearing messages (disappearing-messages, Phase 8) |
| `5d6bbcd5` | confederations | — | skip (D12) | feat: the confederation's own screen, its profile form and its changes (confederation-profile, Phase 4) |
| `8d494ddb` | confederations | — | skip (D12) | feat: a confederation signs the profile its groups endorsed, with nobody asked (confederation-profile, Phase 5) |
| `19cd1aae` | confederations | — | skip (D12) | feat: the confederation's new profile reaches relays and every member group (confederation-profile, Phase 6) |
| `6285033c` | confederations | — | skip (D12) | feat: the confederation's room and its key ceremony's room say what they are (confederation-profile, Phase 7) |
| `33e88f62` | confederations | — | skip (D12) | test: a confederation's profile decided end to end, and the record (confederation-profile, Phases 8 and 9) |
| `139f5192` | confederations | — | skip (D12) | fix: a confederation signs when its admins hold seats in several groups |
| `bcb3c4d4` | confederations | — | skip (D12) | docs: a plan to take away the confederation's own Marmot room (confederation-room-removal, Phase 0) |
| `27f8387d` | confederations | — | skip (D12) | docs: Phase 0's checks, and what the room leaves behind (confederation-room-removal, Phase 0) |
| `6f95672a` | confederations | — | skip (D12) | feat: a confederation's storage stands without its room (confederation-room-removal, Phase 1) |
| `35acce71` | names | 8 | `8eb2d58a` | docs: a group's name starts with & rather than # (group-name-prefix, Phase 0) |
| `036488bc` | confederations | — | skip (D12) | feat: every confederation signing message is signed by its author, and carried (confederation-room-removal, Phases 2 and 5) |
| `1fc69be6` | names | 8 | `7cab0e9b` | feat: a group's name starts with & rather than # (group-name-prefix, Phase 1) |
| `44a9b242` | confederations | — | skip (D12) | feat: each group's own room carries its role holders' signing messages (confederation-room-removal, Phase 3) |
| `85e96902` | names | 8 | `3a398d0a` | feat: a room joined from a Welcome is named by the same rule (group-name-prefix, Phase 2) |
| `3f546402` | names | 8 | `842e278d` | feat: the groups already on a device are renamed to &name, v52 (group-name-prefix, Phase 3) |
| `1c9e1fc5` | confederations | — | skip (D12) | feat: each group's admins carry the confederation's signing between rooms and inboxes, and nothing needs its room (confederation-room-removal, Phase 4) |
| `342127bc` | names | 8 | `5350cf1d` | feat: a subgroup certified as #name is shown as &name (group-name-prefix, Phase 4) |
| `cfa5f75f` | names | 8 | `483ff76d` | test: fixtures and previews name groups &name (group-name-prefix, Phase 5) |
| `60322537` | names | 8 | `e582050a` | docs: the docs say &name, and the record of the build (group-name-prefix, Phase 6) |
| `b75bbaf5` | confederations | — | skip (D12) | feat: the confederation's screen takes over what its room offered (confederation-room-removal, Phase 6) |
| `a4b67dc4` | confederations | — | skip (D12) | feat: no confederation has a room, and the ones on devices are dropped (confederation-room-removal, Phase 7) |
| `6b5fb76c` | confederations | — | skip (D12) | test: a confederation's post end to end with no room anywhere, and the record (confederation-room-removal, Phase 8) |
| `7e42609a` | confederations | — | skip (D12) | feat: everything a confederation signs goes through its groups, as proposals |
| `6a6ce006` | confederations | — | skip (D12) | docs: forming a confederation without its NIP-17 room, a report and a plan, and Phase 0's checks (confederation-dkg-inboxes, Phase 0) |
| `c117995f` | confederations | — | skip (D12) | test: the ceremony's room, its unsigned rumors and the bridge's one envelope, pinned (confederation-dkg-inboxes, Phase 1) |
| `58442efd` | confederations | — | skip (D12) | feat: a ceremony keeps what it heard by any path, and the bridge has a scope, v54 (confederation-dkg-inboxes, Phase 2) |
| `2be378cc` | confederations | — | skip (D12) | feat: every ceremony message is signed by its author and travels in an envelope (confederation-dkg-inboxes, Phase 3) |
| `8bf7145f` | confederations | — | skip (D12) | feat: each group's admins carry the key ceremony between rooms and inboxes, beside the NIP-17 room (confederation-dkg-inboxes, Phase 4) |
| `266bee2a` | confederations | — | skip (D12) | feat: the key ceremony runs in no NIP-17 room, only the groups' rooms and inboxes (confederation-dkg-inboxes, Phase 5) |
| `1fd13942` | confederations | — | skip (D12) | feat: the ceremony's lines open it from the group rooms, and the ceremony room's banner goes (confederation-dkg-inboxes, Phase 6) |
| `1a857e6d` | confederations | — | skip (D12) | feat: the ceremony rooms already on devices go, and a founders' chat that was one stays (confederation-dkg-inboxes, Phase 7) |
| `8ef02129` | confederations | — | skip (D12) | test: a confederation formed over its groups' rooms and inboxes end to end, and the record (confederation-dkg-inboxes, Phase 8) |
| `22d36931` | fix | 1 | audited: the bug is in confederation code | fix: the signing lock is taken after the database's writer, never before it |
| `e7336d8e` | confederations | — | skip (D12) | feat: an admin's device joins the confederation's key ceremony on its own (confederation-auto-join) |
| `9f91f505` | names | 8 | `3ee1223c` | docs: draw a group's name as {name} and a confederation's as {{name}}, stored bare (group-name-braces, Phase 0) |
| `916b5c35` | names | 8 | `b5c189ac` | feat: draw a group's name as {name} and a confederation's as {{name}} (group-name-braces, Phase 1) |
| `cc64856c` | names | 8 | `2a5bfe86` | feat: store and send a group's bare name (group-name-braces, Phase 2) |
| `435590d6` | names | 8 | `ae4a0fba` | feat: store the Marmot rooms already on a device under their bare names (group-name-braces, Phase 3) |
| `0e5fd96f` | names | 8 | skip (D12): confederation files only, empty here | feat: write confederation and group names into stored lines in braces (group-name-braces, Phase 4) |
| `6035a5bf` | names | 8 | `db41b5c1` | test: seed groups under their bare names (group-name-braces, Phase 5) |
| `1d8e9cb5` | names | 8 | `02c983b2` | docs: record the braces as built (group-name-braces, Phase 6) |
| `5017547f` | names | — | skip (D12) | test: a confederation proposed as {Atlas} keeps that name and signs its charter (group-name-braces, D10) |
| `dffe64c3` | recovery codes | 2 | `c17207bd` | docs: plan v3 recovery codes as the backup for new profiles (recovery-code-v3) |
| `ed706d5f` | recovery codes | 2 | `9ec67238` | feat: a pure-Kotlin codec for v3 recovery codes (recovery-code-v3, Phase 1) |
| `a14dcc9c` | recovery codes | 2 | `35555bd0` | feat: a recovery code derives 24 words, and through them a profile (recovery-code-v3, Phase 2) |
| `b959c70f` | recovery codes | 2 | `9f44f649` | chore: bump lightning-kmp-app for recovery-codes.dat (recovery-code-v3, Phase 3) |
| `ee5eb680` | recovery codes | 2 | `098b6c6e` | feat: a recovery code is an identity, attached to the phrase it is (recovery-code-v3, Phase 4) |
| `37efc106` | recovery codes | 2 | `7c2773aa` | feat: sign in with a recovery code, and a typo is a suggestion (recovery-code-v3, Phase 5) |
| `5c6082d7` | recovery codes | 2 | `ba718c5a` | feat: show the recovery code, and check a written-down copy (recovery-code-v3, Phase 6) |
| `f367294c` | recovery codes | 2 | `cb65a159` | feat: a new profile from Landing is backed by a recovery code (recovery-code-v3, Phase 7) |
| `028c1a48` | recovery codes | 2 | `c17207bd` | docs: record recovery codes as built (recovery-code-v3, Phase 8) |
| `7d479e2b` | recovery codes | 2 | `2ff4089b` | fix: Landing's sign-in caption names recovery codes (recovery-code-v3) |
| `b7e5dd19` | fix | 1 | `a45879b7` | fix: give ProposalListRoute's enum filter a NavType off Android |
| `9a1abc61` | fix | 1 | `5513cad7` | fix: include jdk.unsupported and the other needed modules in the desktop runtime |
