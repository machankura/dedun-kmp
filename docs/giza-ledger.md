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
| `051b339a` | `a0d2e4c2` | submodule refresh | 4 | taken | pending | build: lightning-kmp-app with reduced FROST enrollment down the chain |
| `70a38d06` | — | submodule refresh | — | skip | skip | docs: the submodule refresh is built, and what is left to push and check |
| `d4dd87f2` | — | submodule refresh | — | skip | skip | docs: the submodule refresh is pushed, and what is left |
| `8dd710a1` | `cf210849` | share refresh | 7 | taken | pending | docs: rotating a group's shares without moving its key (plan) |
| `4040ae74` | `91d0771a` | share refresh | 7 | adapted; backup half held (D5) | pending | fix: a deleted share is gone from aux.db, and aux.db leaves the phone in no backup (frost-share-refresh, Phase 5) |
| `ebe240a4` | `9e2b06bf` | automatic key ceremony | 5 | taken | pending | docs: joining is the only approval in a key ceremony (plan) |
| `e9c413ac` | `97841ef5` | automatic key ceremony | 5 | taken | pending | test: a key ceremony run across devices with real ChillDKG (automatic ceremony, Phase 1) |
| `fce51e20` | `af804765` | share refresh | 6 | adapted | pending | feat: a key's shares have generations, and signing reads the one it names (frost-share-refresh, Phase 6) |
| `ca5cda81` | `445d57e5` | automatic key ceremony | 5 | taken | pending | feat: joining is the only approval a key ceremony asks for (automatic ceremony, Phase 2) |
| `9278ccd5` | `16df4c8d` | automatic key ceremony | 5 | taken | pending | feat: running key ceremonies resume when the app starts (automatic ceremony, Phase 3) |
| `d71cf0c0` | `2f51e0ae` | automatic key ceremony | 5 | adapted | pending | feat: one approval screen for a key ceremony (automatic ceremony, Phase 4) |
| `4c75ca1b` | `97f0cf2a` | automatic key ceremony | 5 | taken | pending | feat: a stalled key ceremony says on whom, and offers abandon there (automatic ceremony, Phase 5) |
| `73fcfff0` | `2a90b3f0` | automatic key ceremony | 5 | taken | pending | feat: the coordinator's device takes a finished ceremony to its room (automatic ceremony, Phase 6) |
| `c8a529a1` | `9277ee80` | submodule refresh | 4 | doc taken; pointer in 4 | pending | build: lightning-kmp-app with FROST share refresh down the chain (frost-share-refresh, Phase 4) |
| `f8974aec` | `b0e193a7` | share refresh | 7 | adapted | pending | feat: a group rotates its shares, end to end between devices (frost-share-refresh, Phase 7) |
| `0a60da6e` | `ca129b5e` | automatic key ceremony | 5 | adapted | pending | feat: a ceremony's own key state signs itself (automatic ceremony, Phase 7) |
| `6491d2aa` | `aa88a097` | automatic key ceremony | 5 | taken | pending | docs: joining is the only approval, as built (automatic ceremony, Phase 8) |
| `51615992` | `b343cfb7` | share refresh | 7 | adapted | pending | feat: the group signs its new generation, and every device deletes the old share (frost-share-refresh, Phase 8) |
| `c4fe4c9c` | `b7c15d2b` | share refresh | 7 | adapted | pending | feat: rotating a group's shares without a member it removed (frost-share-refresh, Phase 9) |
| `1c3e0e93` | `73631aa5` | share refresh | 7 | adapted | pending | feat: re-enrollment, a stale member repaired into the generation the group signs with (frost-share-refresh, Phase 10) |
| `99229090` | `4f1a5c3c` | share refresh | 7 | adapted | pending | feat: a Shares section, behind a flag that is off in release (frost-share-refresh, Phases 11 and 13) |
| `4949f0fe` | `3a9e6e61` | share refresh | 7 | taken | pending | feat: the recovery walk, from the host key to the live share (frost-share-refresh, Phase 12) |
| `8278ffd5` | `fb344fd5` | share refresh | 7 | taken | pending | docs: share refresh is built, phase by phase, and what is left |
| `08b5c56f` | `5a0cd033` | share refresh | 7 | taken | pending | docs: the share refresh chain is pushed |
| `fc437d05` | `f0810010` | share refresh | 7 | taken | pending | fix: a device does not vouch for a rotation's key state on its own |
| `a25ff2e2` | `9b3e7ba9` | rotation waits for key state | 7 | taken | pending | docs: a rotation waits for the last one to be signed |
| `159ae402` | `8be9d92f` | rotation waits for key state | 7 | taken | pending | feat: a rotation waits for the group to sign the last one (Phase 1) |
| `d358a0db` | `571d5b80` | rotation waits for key state | 7 | adapted | pending | feat: the Shares section holds Rotate shares and says why (Phase 2) |
| `98567629` | `49728f45` | rotation waits for key state | 7 | taken | pending | feat: the Shares section follows the group while it is open (Phase 3) |
| `95131248` | `0cf21a24` | rotation waits for key state | 7 | taken | pending | feat: the proposer is not asked about their own rotation (Phase 4) |
| `5820be9b` | `4217f056` | rotate shares, single approval | 7 | taken | pending | docs: taking part is the only approval a share rotation needs, as a plan |
| `f2b0aa91` | `94ddaa86` | robust-group-admins | 8 | taken | pending | docs: every founding member of a robust group is its admin, phased |
| `2664f6ca` | `8abff8be` | rotation waits for key state | 7 | taken | pending | docs: a rotation waits for the last one to be signed, built (Phase 5) |
| `18f426ca` | `9317fad9` | rotate shares, single approval | 7 | taken | pending | test: a rotation's approvals, counted as they are today (rotate-shares-single-approval, Phase 1) |
| `b4103bcb` | `3ecc7bd2` | rotate shares, single approval | 7 | taken | pending | feat: taking part is a rotation's only approval (rotate-shares-single-approval, Phase 2) |
| `959c120d` | `a7e329e6` | rotate shares, single approval | 7 | taken | pending | feat: running rotations resume without a message (rotate-shares-single-approval, Phase 3) |
| `3efa1af5` | `f13a2478` | robust-group-admins | 8 | taken | pending | test: every founding member of a robust group is its admin (robust-group-admins, Phase 0) |
| `69871c68` | `e6b26e0d` | rotate shares, single approval | 7 | adapted | pending | feat: proposing a rotation is the proposer's approval (rotate-shares-single-approval, Phase 4) |
| `d7fb7103` | `51fe16f9` | robust-group-admins | 8 | taken | pending | feat: a robust group's admin room takes its admins from the ceremony (robust-group-admins, Phase 1) |
| `8088efd7` | `c63026c4` | member-admin-promotion | 8 | taken | pending | docs: making a member an admin and back, a phased plan |
| `83711419` | `93235e3f` | robust-group-admins | 8 | taken | pending | fix: a member welcomed into a room sees its admins at once (robust-group-admins, Phase 2) |
| `46eb23b7` | `554ddfdd` | rotate shares, single approval | 7 | adapted | pending | feat: a rotation's key state signs itself (rotate-shares-single-approval, Phase 5) |
| `2ed83c55` | `8ba35258` | member-admin-promotion | 1 | taken | `f1954991` | fix: an admin's refused leave is shown, not thrown out of the view model (member-admin-promotion, Phase 10a) |
| `53acae68` | `cccb2c12` | member-admin-promotion | 8 | adapted | pending | feat: a member's admin flag is true from the room's first moment (member-admin-promotion, Phase 1) |
| `4671a86a` | `f4c03ad0` | robust-group-admins | 8 | taken | pending | feat: a device checks its admin room against the ceremony it took part in (robust-group-admins, Phase 3) |
| `11f2cb2b` | `baedb44d` | rotate shares, single approval | 7 | adapted | pending | feat: the Shares section, with one approval (rotate-shares-single-approval, Phase 6) |
| `19f0fc0f` | `8bc893c2` | member-admin-promotion | 8 | adapted | pending | feat: the room says who became an admin, and who stopped (member-admin-promotion, Phase 2) |
| `99261876` | `f1b4a0f9` | rotate shares, single approval | 7 | taken | pending | docs: taking part is the only approval, as built (rotate-shares-single-approval, Phase 7) |
| `1dc94ad4` | `004be0ff` | robust-group-admins | 8 | taken | pending | feat: a founding invite that never went out is finished (robust-group-admins, Phase 4) |
| `baf63b8d` | `f627c8e3` | robust-group-admins | 8 | adapted | pending | feat: the room's details say who administers it, and whether the founders do (robust-group-admins, Phase 5) |
| `d95412ff` | `c0be080e` | member-admin-promotion | 8 | adapted | pending | feat: an admin makes a member an admin, or not, in a convenient room (member-admin-promotion, Phase 6) |
| `01e33f5a` | `7defde35` | robust-group-admins | 8 | adapted | pending | fix: rooms joined before the Welcome wrote flags are caught up at start (robust-group-admins, Phase 6) |
| `a95d6db1` | `67252e9e` | robust-group-admins | 8 | taken | pending | docs: every founding member of a robust group is its admin, built (robust-group-admins, Phase 7) |
| `ef33349e` | `551a74bc` | member-admin-promotion | 8 | adapted | pending | feat: the member row's menu, and leaving as an admin (member-admin-promotion, Phases 10 and 11, convenient rooms) |
| `6e3ed2bb` | `5980e86d` | member-admin-promotion | 8 | taken | pending | feat: a robust group keeps at least three admins, and never fewer than its quorum (member-admin-promotion, Phase 7, app side) |
| `d5fbc568` | `9b8eb8da` | member-admin-promotion | 8 | adapted | pending | feat: the statement a keyed room's quorum signs before it changes (member-admin-promotion, Phase 3) |
| `32360994` | `2a28604b` | member-admin-promotion | 8 | taken | pending | docs: admin promotion is built for convenient rooms, and what waits on Phase 0 |
| `b1d62d9e` | `9574b2e3` | member-admin-promotion | 8 | taken | pending | docs: the commit travels inside the group's signature; no upstream work |
| `1deff002` | `7ad607f5` | member-admin-promotion | 8 | adapted | pending | feat: a governed room applies a commit only from inside the group's signature (member-admin-promotion, Phase 4a) |
| `dd00d0f7` | `aca3e169` | member-admin-promotion | 8 | adapted | pending | feat: a keyed room's changes can be made to need the group's signature (member-admin-promotion, Phase 4b) |
| `9008d4fe` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | docs: plan to make every group a member creates robust (remove-convenient-groups) |
| `b9396643` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | test: pin every new group as robust, ignored until it is (remove-convenient-groups, Phase 0) |
| `0a4453da` | `362b708d` | remove-convenient-groups | 8 | adapted | pending | refactor: Quorum takes the threshold rules from ChatRoomType (remove-convenient-groups, Phase 1) |
| `eb2c8aa0` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: every group a member creates is robust (remove-convenient-groups, Phase 2) |
| `59509e42` | `54567e99` | member-admin-promotion | 8 | adapted | pending | feat: a change to a governed room is proposed, signed by the group, then published (member-admin-promotion, Phase 5) |
| `c2010abe` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: a room with no key behind it takes no new members (remove-convenient-groups, Phase 2b) |
| `7fc1233b` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: the member picker holds the three-person floor (remove-convenient-groups, Phase 3) |
| `03704db3` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | refactor: ChatRoomType goes, and the screen is named for the quorum (remove-convenient-groups, Phase 4) |
| `76fbca3e` | `7c1d79e6` | member-admin-promotion | 8 | adapted | pending | feat: a governed room's ordinary changes go through the group's signature (member-admin-promotion, Phase 5b) |
| `7de80452` | `58d138b8` | member-admin-promotion | 8 | taken | pending | fix: the statement's KDoc names the retired wallet range by its constant |
| `9d2a13b8` | `43069679` | member-admin-promotion | 8 | taken | pending | docs: admin promotion is built but for its share work, and where the build chose differently |
| `41409434` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | feat: a group run by one person says so, and is still joined (remove-convenient-groups, Phase 5) |
| `9b7b1a5f` | — | remove-convenient-groups | 10 | held (D3) | held (D3) | docs: every group a member makes is robust, built (remove-convenient-groups, Phase 6) |
| `546b2a2b` | `bac8b963` | robust-group-admins | 8 | taken | pending | docs: Phase 1's flag writer is robust-group-admins' AdminFlags |
| `51eda2d2` | `f27c1153` | member-admin-promotion | 9 | adapted | pending | feat: a keyed room demotes an admin with the group's signature, then rotates them out (member-admin-promotion, Phase 9) |
| `638d1d29` | `f3d16fc2` | member-admin-promotion | 9 | adapted | pending | feat: a member is made an admin of a keyed room, and enrolled into its key (member-admin-promotion, Phase 8) |
| `c64ba8aa` | `6928eeb0` | member-admin-promotion | 9 | taken | pending | docs: Phases 8 and 9 are built under the experimental enrollment, and where the build chose differently |
| `b17a2b1a` | — | marmot-v2 Phase 9 | 10 | pending (D6) | pending (D6) | feat: new rooms are made on the Marmot current profile (marmot-v2, Phase 9, the flip) |
| `01da88ce` | — | marmot-v2 Phase 9 | 10 | pending (D6) | pending (D6) | feat: the legacy KeyPackage slot is retired (marmot-v2, Phase 9, the retirement) |
| `f90506aa` | — | marmot-v2 Phase 9 | 10 | pending (D6) | pending (D6) | docs: the Marmot v2 rollout is done -- new rooms on the current profile, the legacy slot retired (v2 Phase 9) |
| `6aae5652` | `63953322` | member-admin-promotion | 8 | taken | pending | fix: a member invited into a keyed room says it follows signed commits on joining |
| `950ed247` | `4b549646` | member-admin-promotion | 8 | taken | pending | feat: a keyed room switches on the group's signature by itself |
| `35d0b1b0` | `cfa35073` | member-row-actions | 9 | taken | pending | docs: a plan for member rows that offer every action and explain the ones not open |
| `76f8e053` | `9ab9f6aa` | member-row-actions | 9 | taken | pending | feat: member actions carry the reasons they are not open (member-row-actions, Phase 1) |
| `b09fd320` | `513c866d` | member-row-actions | 9 | adapted | pending | feat: every viewer gets the member rows' options, with their reasons (member-row-actions, Phase 2) |
| `1ccd8c7f` | — | member-admin-promotion | 10 | skipped (D2) | skip | feat: a keyed room is born needing the group's signature; the switch is gone |
| `7c39c7b9` | `2ac712f1` | member-row-actions | 9 | adapted | pending | feat: a screen that says why a member action is not open (member-row-actions, Phase 4) |
| `3447fa11` | `5f0745cd` | member-row-actions | 9 | adapted | pending | feat: every other member's row offers every action (member-row-actions, Phase 3) |
| `709adb57` | `9079efc0` | member-row-actions | 9 | taken | pending | feat: the member rows say what is still on its way (member-row-actions, Phase 5) |
| `599cef7d` | `ec1bf0b1` | member-row-actions | 9 | taken | pending | docs: the member rows are built, and where the build chose differently (member-row-actions, Phase 6) |
| `bb35d73c` | `3c2d7d6b` | member-admin-promotion | 9 | adapted | pending | feat: an admin of a keyed room steps down by asking another admin |
| `aaabc519` | `4b39341b` | remove-founding-admins-card | 9 | taken | pending | docs: a plan to remove the "Founding members aren't admins" card |
| `47eb4852` | `2408b20c` | remove-founding-admins-card | 9 | adapted | pending | test: pin what outlives the founding-admins check (remove-founding-admins-card, Phase 0) |
| `1efe486e` | `ab32600d` | remove-founding-admins-card | 9 | adapted | pending | feat: the "Founding members aren't admins" card goes (remove-founding-admins-card, Phase 1) |
| `4550201d` | `373c6e7f` | admin-change-rotation | 9 | taken | pending | docs: a plan for admin changes that carry their rotation, and take turns with it |
| `b04f3047` | `2019dce4` | remove-founding-admins-card | 9 | adapted | pending | refactor: the detail screen stops checking founding admins (remove-founding-admins-card, Phase 2) |
| `b5c1d9eb` | `8729cc04` | remove-founding-admins-card | 9 | taken | pending | feat: the Welcome stops announcing missing founding admins (remove-founding-admins-card, Phase 3) |
| `d21483ef` | `947605db` | remove-founding-admins-card | 9 | adapted | pending | refactor: the founding-admins check and its repair go (remove-founding-admins-card, Phase 4) |
| `e682b06f` | `b0092b91` | remove-founding-admins-card | 9 | taken | pending | docs: the founding-admins card is gone, and where the notes say so (remove-founding-admins-card, Phase 5) |
| `926f79da` | `f32aa019` | admin-change-rotation | 9 | taken | pending | test: pin what admin changes and rotations do to each other today (admin-change-rotation, Phase 1) |
| `521135c8` | `af8d4bee` | admin-change-rotation | 9 | adapted | pending | feat: one rule for what a rotation and an admin change wait on (admin-change-rotation, Phase 2) |
| `c7d46258` | `bc9ff6a0` | admin-change-rotation | 9 | adapted | pending | feat: admin changes and rotations take turns (admin-change-rotation, Phases 3 and 4) |
| `8bf0a240` | `ce21e236` | admin-change-rotation | 9 | adapted | pending | feat: the rotation an admin change owes asks nobody (admin-change-rotation, Phase 5) |
| `d51fdde0` | `41466e33` | quartz/mls/marmot update | 3 | taken | `e3ad4380` | docs: plan the move to quartz 2.20.1, mls-kmp 1.4.0, marmot-kmp 2.3.0 and crypto-kmp 2.1.1 |
| `9fddb0c3` | `dc04d41d` | admin-change-rotation | 9 | taken | pending | feat: the first promotion rotates the shares itself (admin-change-rotation, Phase 6) |
| `3a1dbf87` | `bbcf8e7a` | quartz/mls/marmot update | 3 | taken | `e9ebfcde` | docs: Magpie adopted, and mls-kmp 1.4.0 is a tag not a release (quartz-mls-marmot-update, Phase 0) |
| `971dd459` | `1ace8417` | quartz/mls/marmot update | 3 | adapted | `b2bd1390` | build: the Gradle daemon runs on Java 21 whatever java is on the path (quartz-mls-marmot-update, Phase 1) |
| `cc21d683` | `cd67377f` | admin-change-rotation | 9 | adapted | pending | feat: the Shares section says whose share a rotation takes away; the record (admin-change-rotation, Phases 7 and 8) |
| `f56be2b3` | `eb4c120e` | quartz/mls/marmot update | 3 | taken | `d66b1330` | build: quartz 2.20.0, marmot-kmp 2.3.0, mls-kmp 1.3.1 and crypto-kmp 2.1.1, through Magpie (quartz-mls-marmot-update, Phase 2) |
| `c81e4a2d` | `6db5af5c` | quartz/mls/marmot update | 3 | taken | `2dbf74c0` | test: an old epoch-0 room moves forward on mls-kmp 1.3.1 (quartz-mls-marmot-update, Phase 3) |
| `a08a8789` | `822b924b` | quartz/mls/marmot update | 3 | taken | `027fd301` | docs(tools): the shipped engine against the old one, both ways, three epochs (quartz-mls-marmot-update, Phase 3) |
| `0b43295a` | `4e63c4f1` | quartz/mls/marmot update | 3 | taken | `5acf9a70` | test: pin the state version a save is written at (quartz-mls-marmot-update, Phase 4) |
| `0ecc179d` | `269410be` | quartz/mls/marmot update | 3 | taken | `e3cb2816` | build: quartz 2.20.1, one past marmot's pin (quartz-mls-marmot-update, Phase 5a) |
| `d6c34a77` | `1ff82201` | admin-change-rotation | 9 | taken | pending | fix: a rotation reads the admin policy alone, now the founding-admins check is gone |
| `7222df01` | `5f38e389` | quartz/mls/marmot update | 3 | taken | `83c00bc8` | build: the lock holds only what resolves (quartz-mls-marmot-update, Phase 6, Linux half) |
| `7fe64930` | `bea28974` | quartz/mls/marmot update | 3 | taken | `29e345dc` | build: check fails when the catalogue and the Magpie lock disagree (quartz-mls-marmot-update, Phase 7) |
| `a6d04ec5` | `1775b3ff` | quartz/mls/marmot update | 3 | taken | `0aaad433` | docs: the notes that still said quartz 2.7.3 (quartz-mls-marmot-update, Phase 7) |
| `25f14aa4` | `7ebcd810` | dependency update | 4 | taken | pending | docs: a phased plan to update every dependency outside the Magpie four |
| `2e07fda0` | — | dependency update | — | skip | skip | docs: the baselines (dependency-update, Phase 0) |
| `dafcff2c` | `2f889b25` | dependency update | 4 | taken | pending | build: the catalogue entries nothing reads (dependency-update, Phase 1) |
| `56df0a2b` | `f5ef3160` | dependency update | 4 | taken | pending | build: serialization 1.13.0, kermit 2.2.0, okio 3.18.2 (dependency-update, Phase 2a) |
| `a4f54ad4` | — | icon | — | skip | skip | Update icon. |
| `3371fb3a` | `e227e0a7` | dependency update | 4 | taken | pending | build: ktor 3.6.1 (dependency-update, Phase 2b) |
| `873d76aa` | — | icon | — | skip | skip | docs: the sphinx icon on iOS, desktop and in the app, a phased plan (giza-app-icon) |
| `572515b9` | `c91a674e` | dependency update | 4 | taken | pending | build: room3 3.0.3, sqlite 2.7.1, DataStore 1.3.0, KSP 2.3.14 (dependency-update, Phase 2c) |
| `16dde956` | — | icon | — | skip | skip | docs: the sphinx master, and the baseline it is matched against (giza-app-icon, Phase 0) |
| `0cbc9cc5` | — | icon | — | skip | skip | build: one generator for every icon outside Android (giza-app-icon, Phase 1) |
| `ae418b5d` | `9ddfa8c0` | dependency update | 4 | taken | pending | build: Compose 1.12.2, material3 1.12.0-alpha03, navigation 2.10.0, coil 3.6.3, qrose 1.3.1, Hot Reload 1.3.0 (dependency-update, Phase 3) |
| `554c653d` | — | icon | — | skip | skip | feat: the in-app logo is the sphinx on black (giza-app-icon, Phase 2) |
| `658a69a3` | — | icon | — | skip | skip | feat(ios): the sphinx app icon in light, dark and tinted (giza-app-icon, Phase 3) |
| `aaf037bf` | — | icon | — | skip | skip | feat(desktop): the sphinx on the installers and the window (giza-app-icon, Phase 4) |
| `b6d4c709` | — | icon | — | skip | skip | docs: the icon is built, and what is left to see on devices (giza-app-icon, Phase 5) |
| `3f8336fc` | `cc3a8e30` | dependency update | 4 | taken | pending | build: Gradle 9.9.2 (dependency-update, Phase 4) |
| `3e0a16b1` | `cb6912ca` | dependency update | 4 | taken | pending | fix: the three lint errors AGP 9.5's lint finds in commonMain |
| `a3c0b830` | `86679a93` | dependency update | 4 | adapted | pending | build: AGP 9.5.1 across the composite (dependency-update, Phase 5) |
| `302ce3ca` | `01d4d8e7` | dependency update | 4 | taken | pending | build: Kotlin 2.4.21 across the composite (dependency-update, Phase 6) |
| `65a2507a` | `fc7a045d` | dependency update | 4 | taken | pending | docs: the comments that named the old versions (dependency-update, Phase 7) |
| `36be1efc` | — | — | — | pending | pending | fix: one Shares row per member, so a reinstated admin no longer crashes the room detail |

## Bennu-only commits in the port

| bennu port | Dedun | subject |
|---|---|---|
| `040e5746` | skip: Bennu's own notes | docs: a phased plan to take Giza's work back into Bennu |
| `e242ab59` | skip: Bennu's own notes | docs: the Giza mirror, a ledger of every Giza commit, and the baseline (giza-into-bennu, Phase 0) |
| `360f772c` | `c33a3fb3` | feat: Bennu makes no bare commit in a room Giza governs (giza-into-bennu, Phase 1) |
| `4f49e6a7` | skip: Bennu's own notes | docs: the marmot-kmp group is in, and Bennu's schema is 36 (giza-into-bennu, Phase 2) |
| `505df0ac` | skip: Bennu's own notes | docs: quartz 2.20.1 through Magpie is in (giza-into-bennu, Phase 3) |
| `9fd354c6` | skip: Bennu's own notes | docs: the toolchain and the submodule chain are in, measured in Bennu (giza-into-bennu, Phase 4) |
| `168f4524` | pending | test: an automatic ceremony's subgroup keeps its wallet policy and guardian keys (giza-into-bennu, Phase 5) |
| `44fba704` | skip: Bennu's own notes | docs: share generations are in, with Bennu's wallets signing at them (giza-into-bennu, Phase 6) |
| `993f26b8` | pending | feat: rotation and the Lightning wallet, and a rotation that waits for a spend (giza-into-bennu, Phase 7) |
| `5ce77c08` | skip: Bennu's own notes | docs: rotation is in, and D5 stays open (giza-into-bennu, Phase 7) |
| `b86b3a0d` | skip: Bennu's own notes | docs: governed rooms are in, receiving and sending (giza-into-bennu, Phase 8) |
| `1785a284` | skip: Bennu's own notes | docs: Bennu keeps convenient groups, said where Giza's plan to remove them landed |
| `8a91b664` | pending | feat: no admin change in a keyed room that keeps a Lightning wallet (giza-into-bennu, Phase 9, D4) |
| `f96d2f47` | pending | test: an admin's row is labelled from its admin flag, as the screen now reads it |
| `0ba1c5bd` | skip: Bennu's own notes | docs: promotion, demotion and their rotations are in (giza-into-bennu, Phase 9) |
| `a2528010` | skip: Bennu's own notes | docs: Phases 0 to 9 are built, and what is left (giza-into-bennu) |
| `2db6dde5` | pending | test: a Giza-shaped device and two Bennu devices share one governed room (giza-into-bennu, Phase 8) |
