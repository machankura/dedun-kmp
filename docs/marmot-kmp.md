# Marmot comes from marmot-kmp, not from quartz

quartz 2.3.0 took the Marmot engine out of quartz. What this app imports from
`com.vitorpamplona.quartz.marmot` — the MLS engine, the three processors, the
convergence engine, the publish gate, the app components, the KeyPackage managers — is
not in quartz any more at any version above 2.2.0, and it is not coming back. It lives
in [marmot-kmp](https://github.com/vitorpamplona/marmot-kmp) (the Marmot protocol and a
headless client) over [mls-kmp](https://github.com/vitorpamplona/mls-kmp) (the RFC 9420
engine) over [crypto-kmp](https://github.com/vitorpamplona/crypto-kmp) (the primitives).
quartz keeps the Marmot *event* classes and nothing else.

So the app is not merely behind on a version. It is on the last quartz that still has an
engine inside it, and every fix the engine has had since — including the one
[mls-skipped-keys.md](./mls-skipped-keys.md) is about — has landed in a library the app
does not depend on. This is the plan to move onto that library.

Read [marmot-v2.md](./marmot-v2.md) first, in particular its Phase 5 and the first row of
its table of where the build chose differently: a room's profile picks its pipeline, and
everything here happens on the current-profile side of that branch.
[mls-skipped-keys.md](./mls-skipped-keys.md) second — it is the gap this move closes, and
the only one it closes for free.

**Not built.** This is a plan. Written against the code at `8b066795`, marmot-kmp
`2.2.1`, mls-kmp `1.3.0`, crypto-kmp `2.1.0` and quartz `2.7.3`.

## Where the engine went

| library | coordinate | holds | the app today |
|---|---|---|---|
| quartz | `com.vitorpamplona.quartz:quartz` | nostr: events, tags, signers, relay client — and the four Marmot *event* classes (`KeyPackageEvent`, `KeyPackageRelayListEvent`, `WelcomeEvent`, `GroupEvent`) plus the MIP-05 push events | `1.15.2`, where the engine is still inside it |
| marmot-kmp | `com.vitorpamplona.marmot:marmot` | MIP-00..05, `MarmotInboundProcessor`, `MarmotOutboundProcessor`, `MlsGroupManager`, `MlsGroupStateStore`, `MarmotPublishGate`, the convergence engine, `appComponents`, `foundation/appEvents`, `KeyPackageRotationManager`, and `MarmotManager` — a headless client the app does not have to use | not depended on |
| mls-kmp | `com.vitorpamplona.mls:mls` | RFC 9420: `MlsGroup`, `MlsGroupState`, the TLS codec, the ratchet tree, the key schedule, framing | not depended on |
| crypto-kmp | `com.vitorpamplona.crypto:crypto` | SHA-256, HKDF, HMAC, AES, ChaCha20-Poly1305, X25519, Ed25519, `RandomInstance` | arrives transitively under quartz 2.x; not declared |

All four are on Maven Central, as is `com.vitorpamplona.negentropy:kmp-negentropy`, which
quartz pulls in — so no new repository is needed. marmot-kmp publishes `jvm`, `android`,
`iosArm64`, `iosSimulatorArm64`, `macosArm64` and `linuxX64`: every target this app
declares. MIT, like quartz.

`marmot-quic` is a second artifact for agent text-stream previews over raw QUIC, JVM and
Android only. Nothing here wants it.

## The move, symbol by symbol

209 import lines across 57 files name `com.vitorpamplona.quartz.marmot` — 32 files under
`commonMain`, 25 under test. Every one of them resolves, and the rule is almost entirely
positional:

| from | to | lines |
|---|---|---|
| `quartz.marmot.mls.{codec,components,crypto,framing,group,messages,schedule,tree}.X` | `mls.{same}.X` | 80 |
| `quartz.marmot.mls.crypto.{Ed25519,X25519}` | `crypto.curve25519.{same}` | 8 |
| `quartz.marmot.mls.group.{MlsGroupManager,MlsGroupStateStore}` | `marmot.groups.{same}` | 5 |
| `quartz.marmot.{appComponents,foundation,protocolCore,mip00KeyPackages,mip01Groups,mip03GroupMessages}.X`, `quartz.marmot.{GroupEventResult,MarmotInboundProcessor,MarmotOutboundProcessor}` | `marmot.{same}` | 74 |
| `quartz.marmot.mip00KeyPackages.{KeyPackageEvent,KeyPackageRelayListEvent}`, `mip02Welcome.WelcomeEvent`, `mip03GroupMessages.GroupEvent` | unchanged | 42 |

Two symbol pairs that the compiler will catch but the rule above does not: the MLS group
*manager* and *store* are marmot-kmp's, in `marmot.groups`, while everything else called
`MlsGroup*` is mls-kmp's, in `mls.group` — one letter apart, and the only place the
positional rule breaks. And `RetainedEpochSecrets` sits in mls-kmp beside `MlsGroupState`,
which is where `RoomMlsGroupStateStore` reads it from.

Outside the Marmot tree, 19 more lines move to crypto-kmp because quartz 2.x no longer
declares them: `quartz.utils.RandomInstance` → `crypto.random.RandomInstance` (17 lines,
same `bytes(n)`/`int()`/`randomChars()` API) and `quartz.utils.sha256.sha256` →
`crypto.sha2.sha256` (2 lines; the package is gone from quartz entirely).

And then the part that is not a rename: 854 import lines name quartz outside
`quartz.marmot`, and all of them ride a 1.15.2 → 2.7.3 major bump. Resolving every one of
those 854 against a 2.15.1 checkout found the packages intact — `nip01Core.core`,
`nip01Core.crypto`, `nip01Core.signers`, `nip01Core.relay`, the nip-numbered event
classes, `utils.TimeUtils`, `utils.ensure`, `utils.EventFactory` are all where they were,
`eventTemplate` and `EventTemplate` included — but "the import resolves" is not "the
signature is the same", and reading cannot tell the difference. Phase 1 is where the
compiler tells us.

## Two constraints

**C1 — a group on a device today must still open tomorrow.** Group state is persisted
as `MlsGroupState.encodeTls()` hex in `ChatRoom.mlsGroupState`, and retained epochs as
`MarmotRetainedEpochSecret` rows. If the new engine cannot read what the old one wrote,
every existing room loses its membership and the only repair is a new group and a
re-invite — `LegacyGroupContractTest` in marmot-kmp is explicit that there is no in-place
migration from a legacy group. Reading mls-kmp says this holds: `MlsGroupState` is
versioned, `STATE_VERSION` is 10, `decodeTls` accepts `1..STATE_VERSION`, and the comments
say each version only appends ("optional (empty for STATE_VERSION 1 blobs) so older
persisted state…"). `RetainedEpochSecrets` carries the same five fields the app's rows do
and tolerates an absent `exporterSecret`. That is an argument, not a measurement. Phase 4
measures it.

**C2 — the fork still merges from Bennu.** [giza-rebrand.md](./giza-rebrand.md) keeps
cherry-picking from upstream, and upstream is on quartz 1.15.2 with the engine inside it.
Every file this plan rewrites is a file a future cherry-pick will conflict in. That argues
for one mechanical commit with a message that says exactly what the rule was, so the
conflicts are resolvable by re-applying the rule rather than by reading the diff.

`ForkCompatibilityTest` pins the npub and the profile id a recovery phrase derives, and
the `mantra/…` derivation tags are hash inputs. Nothing here touches either, and both are
in the gate at every phase boundary anyway.

## Decisions

**D1 — take `:marmot`, keep `MarmotEngine`.** marmot-kmp ships `MarmotManager`, a headless
client that owns the stores, the publisher, the receiver and the dedup store. The app has
`MarmotEngine` over the same three processors, wired into `DedunDatabase`, the outbound
queue, the broadcast status machine and `NostrDao.indexMarmotGroupEvent`. Swapping engines
*and* libraries in one move would make a failure impossible to attribute. The constructors
line up exactly as the app already calls them — `MlsGroupManager(store)`,
`MarmotInboundProcessor(groupManager, KeyPackageRotationManager())`,
`MarmotOutboundProcessor(groupManager)`, `MarmotPublishGate(groupManager, store)` — so the
seam holds with no redesign. `MarmotManager` is Phase 7's question, not this plan's.

**D2 — pin quartz to 2.7.3, the version marmot 2.2.1 was built against.** 2.15.1 is
published and marmot-kmp has not been compiled against it; a quartz that moved an API
marmot-kmp calls gives a `NoSuchMethodError` at runtime rather than a compile error.
Gradle resolves to the highest, so the catalog has to name 2.7.3 deliberately and the app
has to stay off any quartz API added after it. Moving both forward together is its own
change, with marmot-kmp's own CI as the evidence.

**D3 — crypto-kmp is declared, not inherited.** It arrives transitively under both quartz
2.x and mls-kmp, but after this move the app imports `RandomInstance`, `sha256`, `Ed25519`
and `X25519` from it directly in 27 places. A dependency the source names is a dependency
the build names.

**D4 — the legacy pipeline is touched only by the rename.** `MarmotInboundManager`,
`MlsGroupCache` and the app's own `CommitOrdering` keep their imports pointed at the same
classes, now in mls-kmp, and keep their behaviour. The reasoning is marmot-v2.md's and
unchanged: the library's processor has no carve-out for the bare gift wrap a legacy room
carries a direct message in ([marmot-direct-messages.md](./marmot-direct-messages.md)),
and the legacy path retires with the profile.

**D5 — no new behaviour inside the swap.** The sender-ratchet split, the durable ingest
dedup and the skipped-key persistence are all things the new engine makes possible. Each
is a change in what the app does, each is separately testable, and none of them belongs in
the commit that changes where the classes come from.

**D6 — the stand-in moves with the library.** `MarmotStandIn` is "another Marmot client,
played by quartz's own managers" — the other side of every two-implementation test in
`managers`. It must be played by marmot-kmp's managers, or the interop tests prove the new
engine agrees with itself.

## Phase 0 — the inventory, before anything moves

No code. Produce the three lists the later phases consume, and check them into
`docs/scripts/` if any of them turns out to be worth re-running:

- the 228 import lines and their destinations, by the rule in the table above — a
  `sed` script per row, not a hand edit;
- the five places the app implements or extends a library type, which are where the
  compiler will have the most to say: `RoomMlsGroupStateStore` (now `marmot.groups`, and
  the interface has gained defaulted `saveSenderRatchet`/`loadSenderRatchet`),
  `RoomPublishObligationStore` (all six methods it overrides are still there, gate trio
  included), `MlsGroupExtensions.exporterSecret`, `MlsGroupProfile`'s six accessors, and
  `MarmotGroupFactory`;
- the app's own copies of what the library now has, for Phase 7 to delete rather than
  carry: `CommitOrdering`, the in-memory dedup in `MarmotInboundProcessor`'s caller, and
  whatever `MarmotIngestDedupStore` turns out to replace.

**Check:** the lists exist and the `sed` scripts are reversible.

**Built.** The rule table is
[docs/scripts/marmot-kmp-imports.sh](./scripts/marmot-kmp-imports.sh), which takes
`--apply`, `--revert` and `--check`, and the destinations in it were derived by listing
what quartz 2.15.1 still has under `marmot/` against what marmot-kmp 2.2.1 and mls-kmp
1.3.0 declare rather than by reading the packages' names. Applied and reverted on a clean
tree it moves 187 lines and puts all 187 back: 79 onto marmot-kmp (74 by package plus the
manager and the store), 80 onto mls-kmp, 27 onto crypto-kmp, and one KDoc reference in
`CommitOrdering`. 42 `quartz.marmot` imports are left standing, which is the count the
table above predicts for the event classes.

Three things the split turned out to be finer than the table says, all of them inside a
package quartz keeps:

| stays in quartz | moved to marmot-kmp |
|---|---|
| `mip00KeyPackages`: the events, `PublishedKeyPackage`, the ten tag classes and the tag accessors (`encoding`, `keyPackageRef`, `mlsCiphersuite`, `mlsExtensions`, `mlsProposals`, `mlsProtocolVersion`) — 16 files | `KeyPackageBundleStore`, `KeyPackageEventFromMls`, `KeyPackageFetcher`, `KeyPackageRotationManager`, `KeyPackageUtils` |
| `mip02Welcome`: `WelcomeEvent` and its three tags — 6 files | `WelcomeGiftWrap` |
| `mip03GroupMessages`: `GroupEvent`, `GroupIdTag`, the two tag extensions — 4 files | `GroupEventEncryption` |
| `mip05PushNotifications`: all 13 event and record files | `MarmotPushStateStore`, `PushRecordStore`, `TokenEncryption` |

marmot-kmp imports the quartz side of that table itself, which is the clearest statement
that the halves are meant to sit together. And `mip01Groups` and `mip04EncryptedMedia`
have no quartz side at all.

The five places the app implements or extends a library type:
`RoomMlsGroupStateStore`, `RoomPublishObligationStore` (whose six overrides all still
exist, gate trio included), `MlsGroupExtensions.exporterSecret`, `MlsGroupProfile`'s six
accessors and `MarmotGroupFactory`. The app's own copies of what the library now has, for
Phase 7: `CommitOrdering`, and `MarmotEngine`'s reliance on the processor's in-memory
dedup.

One thing Phase 0 found that is not about imports at all: the `lightning-kmp-app`
submodule, and the four nested ones under it, have to be checked out before the build can
configure — `Project with path ':library' not found in build ':lightning-kmp-app'` is what
a fresh worktree says. `git submodule update --init --recursive`.

## Phase 1 — the swap, in one commit

The version catalog gains `marmot`, `mls` and `crypto`, `quartz` goes to `2.7.3`,
`composeApp`'s `commonMain` declares the three, and the Phase 0 scripts run. Nothing else.

```toml
quartz = "2.7.3"      # was 1.15.2 -- what marmot 2.2.1 was compiled against (D2)
marmot = "2.2.1"
mls = "1.3.0"
cryptoKmp = "2.1.0"
```

Expect the compiler to have plenty to say, and expect almost all of it to be in the 854
non-Marmot lines rather than in the renamed ones — quartz 2.0.0 is a repository split as
well as a major version, and the 1.x line was published from Amethyst's own tree, so there
is no upstream changelog that covers the gap. Three things are already known to be in it:
`RandomInstance` and `sha256` move (Phase 0's third script), quartz 2.x drops
`whyoleg.cryptography`, `andreypfau.kotlinx-crypto-*`, `charlietap.cachemap` and
`jackson-module-kotlin` in favour of crypto-kmp, and it brings `kotlin-stdlib` 2.4.21 and
`kotlinx-serialization` 1.12.0 against the app's Kotlin 2.4.10 and serialization 1.11.0.
The stdlib skew is not new — quartz 1.15.2 already brings 2.4.20 — but a klib built by
2.4.21 read by a 2.4.10 compiler is, and it will only show up on the native targets, on a
mac. Bumping `kotlin` to 2.4.21 is the cheaper answer if it does.

**Check:** `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`, then
`./gradlew check` for the three audits. On a mac, the ios targets too — they are the only
place a klib skew can appear.

**Built**, and the fear was misplaced. The 854 non-Marmot quartz imports came through the
1.15.2 → 2.7.3 bump without a single edit. Every compile error — 45 of them — was in the
engine seam, and 44 of the 45 were one thing: five accessors that were members of
`MlsGroup` in quartz's engine are extension functions in marmot-kmp
(`currentGroupState`, `currentMarmotData`, `currentNostrGroupId`,
`currentAdminIdentities`, `isLocalAdmin`, all in `marmot.groups`), so the 14 files that
call one now import it. The 45th was `KeyPackageEvent.Companion.buildCurrentProfileFrom`,
an extension too. Nothing in the API the app drives had changed shape: every constructor
the plan quoted in D1 took its arguments unaltered, and `GroupEventResult`'s nine variants
are still nine — `MarmotEngine`'s exhaustive `when` over them compiles, which is the
compiler proving there is no tenth to drop silently.

Two things the bump brings that are not errors. quartz 2.x keeps a deprecated typealias
for each class it renamed, so six the app uses still compile and warn:
`SealedRumorEvent` → `SealEvent` (22 sites), `ChatMessageRelayListEvent` →
`DmRelayListEvent` (19), `RelayFeedsListEvent` → `FavoriteRelayListEvent` (5),
`LnZapEvent` → `ZapReceiptEvent` (5), `LongTextNoteEvent` → `LongFormContentEvent` (1),
`ChannelListEvent` → `PublicChatListEvent` (1). And quartz 2.x drops
`whyoleg.cryptography`, `andreypfau.kotlinx-crypto-*`, `charlietap.cachemap` and
`jackson-module-kotlin` from the dependency graph in favour of crypto-kmp. The
`kotlin-stdlib` skew the plan worried about was already there — 1.15.2 brought 2.4.20
against the app's Kotlin 2.4.10 — and 2.4.21 behaves no differently.

**And one thing that is not a rename at all: the group policy.** `MlsGroupPolicy` is new
in mls-kmp. quartz's engine hardcoded Marmot's rules; mls-kmp made them an argument, and
`MarmotGroupPolicy` (marmot-kmp, `marmot.groups`) is the object that carries them. Every
factory — `create`, `restore`, `processWelcome`, `externalJoin`, `createKeyPackage` —
defaults it to `MlsGroupPolicy.Permissive`, so the eleven places the app builds a group
itself were silently building a non-Marmot group. What that costs, in the order it bites:

| `MarmotGroupPolicy` | `Permissive` |
|---|---|
| `commitExporter` = `MLS-Exporter("marmot", "group-event", 32)` | null, so `CommitResult.preCommitExporterSecret` comes back empty and `GroupEventEncryption.encrypt` refuses it: *"Group key must be 32 bytes"* |
| `greaseCapabilities` = false | true — GREASE values in the leaf's capabilities, which MDK "neither sends nor has been tested against" |
| `knownExtensionTypes` = `0xF2EE` and the app-data dictionary | empty, so RFC 9420 §13.4's all-members-support check refuses a GroupContextExtensions proposal in a group this client is happily a member of |
| `defaultLeafCapabilities` / `defaultRequiredCapabilities` = Marmot's | empty / none |
| `authorizeCommit`, `authorizeProposal`, `authorizeSelfRemove`, `validateJoin` | no-ops: no authorized-proposal-set check, no admin-depletion check |

Only the first of those five is loud, and it is what the single failing test found — the
second invite into an established legacy room, `MarmotOutboundDaoJvmTest`. The other four
are silent, and the GREASE line is wire-visible: a KeyPackage this app published would
have carried values the reference client has never been tested against. So the policy goes
in at every construction site, which is this phase's one behavioural change and the reason
the swap is not purely mechanical. Four in `commonMain` — `ChatRoom.toMlsGroup`,
`MarmotEngine.groupFor`, `MarmotGroupFactory`'s legacy branch, `NostrDao`'s Welcome path —
and seven in the fixtures. The current-profile paths needed none: they go through
marmot-kmp's own `CurrentProfileGroupFactory`, which passes the policy itself, and so does
every `MlsGroupManager` entry point.

With the policy in, the suite is green: 1149 tests, 0 failures, and `compileDebugKotlinAndroid`
clean. One housekeeping note: a rewritten import keeps its old alphabetical slot, so the
touched files' import blocks were re-sorted — but only the touched ones. Sorting every
block in `composeApp` would have moved lines in 253 further files whose imports were
already out of order before any of this, and C2 is the reason not to: each of those is a
line a cherry-pick would then conflict in for no reason.

## Phase 2 — the drift in the seam

Phase 1 ends when it compiles. This phase is for what compiles and means something else.
The engine moved from quartz 1.15.2 to a library that has had five releases of its own
since, so the behavioural questions are concentrated in `MarmotEngine` and its callers:

- `GroupEventResult`'s variants: `MarmotInboundProcessor` declares them in its own file
  and the app branches on them in `NostrDao.indexMarmotGroupEvent` and
  `DatabaseMarmotRepository`. A new variant the app does not handle is a silent drop.
- `ConvergenceStatus`, `ConvergenceDisposition` and `GroupLifecycleState` — the processor
  now exposes `settleDueConvergence()`, `openConvergencePasses()` and `groupLifecycle()`,
  which is what `MarmotEngine.settleLater` and `awaitSettled` are built on.
- `MlsGroupManager`'s staging API (`stageAddMember`, `stageCommit`, `installState`,
  `StagedCommit`) against what `MarmotPublishGate.prepare`/`resolve` expects, which is the
  publish-before-apply path marmot-v2.md's Phase 6 built.
- `KeyPackageUtils`, `CurrentProfileGroupFactory` and `MarmotGroupData` in
  `MarmotGroupFactory` and `DatabaseMarmotRepository` — the group-creation shapes.

**Check:** the existing suites are the specification here. `GroupLifecycleJvmTest`,
`ConvergenceForkJvmTest`, `PublishBeforeApplyJvmTest`, `CurrentProfileInteropJvmTest`,
`MarmotGroupCreationJvmTest`, `RobustRoomKeyCeremonyTest`,
`SubgroupCeremonyInParentRoomTest`. A test that needed editing to pass is a finding;
record it in this note's table rather than in the diff.

**Built**, and most of this phase turned out to be already answered by Phase 1 rather than
work of its own:

- **`GroupEventResult` has nine variants, the same nine.** Not argued from reading:
  `MarmotEngine.inRoom` is a `when` over the sealed class used as an expression, so the
  compiler requires it to be exhaustive. It compiles, which is the compiler stating there
  is no tenth variant for the app to drop silently.
- **The convergence and lifecycle surface is unchanged.** `convergenceStatus`,
  `resolveConvergence`, `settleDueConvergence`, `openConvergencePasses` and
  `groupLifecycle` all take and return what `MarmotEngine.settleLater` and `awaitSettled`
  already expected, down to `ConvergenceStatus.allowsOutboundWork`.
- **So is the staging API against the gate.** `stageAddMember` → `StagedCommit` →
  `gate.prepare` → `installState` needed no edit, and `PublishBeforeApplyJvmTest` and
  `ConvergenceForkJvmTest` pass with their assertions untouched.
- **No test's assertions had to move.** The fixtures changed, in that seven of them now
  construct their group with `MarmotGroupPolicy` like the app does — a construction-site
  change, not a changed expectation. Nothing in this migration has yet been accommodated
  by weakening a test.

What was left is the six renames quartz 2.x made and kept deprecated typealiases for, now
adopted: 104 references across 25 files. Same packages, so each is an identifier rename,
and none of them reaches a string the app persists — the diff touches code and log lines
only, no Room column, no string resource, no kind number.

| quartz 1.15.2 | quartz 2.x | why |
|---|---|---|
| `SealedRumorEvent` | `SealEvent` | NIP-59 names kind 13 the seal |
| `ChatMessageRelayListEvent` | `DmRelayListEvent` | NIP-17 kind 10050 is the relay list to receive DMs |
| `RelayFeedsListEvent` | `FavoriteRelayListEvent` | NIP-51 names kind 10012 the favorite relays list |
| `LnZapEvent` | `ZapReceiptEvent` | NIP-57 names kind 9735 the zap receipt |
| `LongTextNoteEvent` | `LongFormContentEvent` | NIP-23 names kind 30023 long-form content |
| `ChannelListEvent` | `PublicChatListEvent` | NIP-51 names kind 10005 the public chats list |

## Phase 3 — the stand-in, and what interop now means

`MarmotStandIn` goes over to marmot-kmp's managers, and then there is a choice worth
making here rather than later: the stand-in is the one place `MarmotManager` can be tried
with nothing depending on the answer. If the stand-in is a `MarmotManager`, every
two-implementation test in `managers` becomes the app's engine against the client Amethyst
actually ships, which is a stronger claim than the one those tests make today.

**Check:** `./gradlew :composeApp:jvmTest`. `ForkCompatibilityTest` and
`SignedGroupKeyStateTest` are in the same run and are the fork's own pins.

**Built, and both stand-ins are kept.** `MarmotStandIn` moved to marmot-kmp's managers in
Phase 1 with nothing but its imports changing, and replacing it with a `MarmotManager`
would have cost the convergence and lifecycle tests the thing they need, which is a
processor they can reach into: `recordLocalCommit` with a state snapshot of their own,
`buildAppEvent` with a payload they wrote. So the second one is additive —
`MarmotClientStandIn`, a `MarmotManager` over `InMemoryMlsGroupStateStore` and a publisher
that says yes, with `messageStore` and `keyPackageStore` left null because both are
nullable and a client that persists nothing is all these tests want — and
`MarmotClientInteropJvmTest` is the three tests it unlocks.

They are not redundant, and the difference is worth stating because it is the difference
between the two questions this migration has to answer. In
`CurrentProfileInteropJvmTest` the test's own code decides how the Welcome is sealed and
what an application payload looks like, and asks whether two engines agree. In the new one
the library decides: `addMemberInvites` picks the commit's shape and `MarmotWelcomeSender`
wraps the Welcome, and the question is whether the app's own orchestration —
`MarmotEngine`, the DAOs, the outbound queue — reaches the same group as the orchestration
Amethyst drives. It does: the app joins a group `MarmotManager` created and invited it to,
under the routing id the client minted; it files and attributes a `buildTextMessage` from
it; and the client decrypts and decodes what the app's outbound path sends as a canonical
payload. Three tests, green on the first run, which is the strongest statement available
that the move did not change what goes on the wire.

One carve-out rides along unchanged: the app's rumor still carries `"sig":""`, so the
assertion strips it before decoding, exactly as the older interop test does.
docs/marmot-v2.md's Phase 2 is where that is recorded as unfinished, and this note does
not change it.

## Phase 4 — C1, measured

The phase that decides whether this ships. Everything before it is a build; this is the
evidence that an upgrade does not cost a user their rooms.

1. **A real blob, decoded.** Take `ChatRoom.mlsGroupState` out of a database written by
   the current build — the first four hex characters are its `STATE_VERSION` — and decode
   it with mls-kmp 1.3.0. Assert the version is in `1..10` and that the decoded state's
   epoch, group id and leaf index are what the old engine reported. This belongs in
   `jvmTest` with the blob as a checked-in fixture, which is also how it stays true: it
   becomes the test that a future mls-kmp bump has to pass.
2. **A round trip through the store.** `RoomMlsGroupStateStoreJvmTest` already covers
   save/load and the retained-epoch rows; extend it to load an old blob, re-save it, and
   assert the group still decrypts a message encrypted before the upgrade.
3. **The window.** Retained epochs are re-encoded field by field by
   `RoomMlsGroupStateStore.loadRetainedEpochs`, so a field added to
   `RetainedEpochSecrets` would silently truncate them. Pin the five fields in the same
   test.

If any of the three fails, this is where the plan stops and a migration gets designed. If
all three pass, no schema change is needed at all — the blob is opaque to Room and the
version is inside it, so schema 36 stands.

**Check:** the three tests, and one manual upgrade on a device or desktop profile with
real rooms in it. The automated evidence is necessary and not sufficient; a room that has
been through several epochs and several members is not something a fixture reproduces.

**Built, and C1 holds — on real bytes rather than on the argument.** The argument turned
out to be checkable before any test was written: quartz 1.15.2's own sources, in the
sources jar beside the artifact in the gradle cache, declare `STATE_VERSION = 4`. mls-kmp
1.3.0 declares 10 and accepts `1..10`. So every blob in the wild is a version-4 blob and
the window covers it with six versions to spare.

Then the measurement. `docs/tools/old-state-blob/` is a project whose only dependency is
`com.vitorpamplona.quartz:quartz:1.15.2` — the engine the app shipped — and it prints a
real `MlsGroupState` blob: 1033 bytes, a legacy MIP-01 group at epoch 0, which is the
shape every room in the wild started as. It is checked in for provenance, because once it
is gone there is no way to tell by reading the hex whether the fixture came from the old
engine or the new one, and a fixture written by the new engine would make the test pass
while testing nothing. `MlsGroupStateCompatibilityTest` pins it, six ways:

| | |
|---|---|
| the fixture is still a version-4 blob | asserted on its own, so a fixture that quietly became version 10 cannot make the rest pass vacuously |
| mls-kmp decodes it | epoch 0, leaf 0, a 32-byte group id, an encryption secret |
| the group restores from it | key schedule, secret tree and ratchet tree rebuilt under `MarmotGroupPolicy`, `0xF2EE` intact, same `nostr_group_id`, same name, and it still reads as legacy rather than current-profile |
| the kind:445 outer key is derivable from it | 32 bytes — Phase 1's policy finding pinned against real pre-migration bytes rather than a fixture the new engine built |
| a re-save moves it forward and still decodes | version 10, same group id, same epoch, same leaf: the upgrade a room gets the first time the manager persists it |
| the retained-epoch record still has five fields | round-tripped, because `RoomMlsGroupStateStore` decodes one into five columns and re-encodes it from them, so a sixth field would be silently truncated on every load |

No schema change was needed, as the plan guessed: the blob is opaque to Room, the version
is inside it, and schema 36 stands.

**What is not done, and is not automatable here: the manual upgrade.** A fixture is a
group at epoch 0 with one member. The rooms that matter have been through several epochs,
several members and a retained-epoch window, and the only way to know they open is to open
one. That check needs a device or desktop profile with real rooms in it, and it is the last
thing between this work and a release.

## Phase 5 — the skipped keys, closed

[mls-skipped-keys.md](./mls-skipped-keys.md) describes a conformance gap in quartz's MLS
implementation: `SecretTree` caches the keys for generations that arrive early, exactly as
RFC 9420 §9.1 requires, and then never persists them — so two messages published in the
same second reliably lose one across a restart, and the loss is silent. mls-kmp's
`MlsGroupState` persists them: `STATE_VERSION` 5 is "skipped-generation secrets" and 7 is
"the secret tree's unexpanded node secrets". The app's partial mitigation — the live group
held in `MlsGroupManager` for the life of the process — is no longer all there is.

So: write the test the note's symptom section describes, from the receiver's side, across
a `save`/`restore` boundary. Two messages in the same epoch delivered in reverse order
after a restart; both decrypt. Then update that note to say which engine closed it and
leave its mitigation section as the history of how it was lived with.

**Check:** the new test, and `MarmotEngine`'s own doc comment, whose last paragraph
currently names this gap as open.

## Phase 6 — the authored fork

marmot-v2.md ends on a gap that is the library's and not the app's: a member cannot replay
a commit it authored, so a fork this device is half of is scored with its own branch stale,
and two committers swap branches rather than agree. `ConvergenceForkJvmTest` holds both
halves — the case the app depends on, proven, and the swap, pinned, with its last
assertion saying what to do the day it fails.

Reading marmot-kmp's `MlsCandidateStateEngine` and `MarmotConvergenceEngine` does not
settle whether that is still true; they talk about holding an authored commit and a
received one "to one rule rather than two", which is suggestive and not evidence. The test
is the probe. If the pinned swap now fails, the gap closed in the library and the test
inverts into the assertion it predicts. If it still passes, nothing has changed and
Phase 5's follow-up — marking a losing branch's rows withdrawn — still waits on the same
fix.

**Check:** `ConvergenceForkJvmTest`, and whichever way it goes, this note and
marmot-v2.md's closing paragraph both say so.

## Phase 7 — what the library now offers, and the guard

Four things, each its own commit, none of them required by the move:

- **The sender ratchet record.** `MlsGroupStateStore.saveSenderRatchet`/
  `loadSenderRatchet` default to `false`/`null`, and the manager then falls back to a full
  `save()` — correct, and it re-encrypts the ratchet tree, the group context and every
  epoch secret to persist a counter, per message, growing with the group. Implementing the
  pair over a small table is the fix. The interface's own words: the write MUST be durable
  before it returns, because a lost position re-emits a generation already used, which
  reuses an AEAD key and nonce pair.
- **Durable ingest dedup.** `MarmotIngestDedupStore` is what marmot-v2.md's Phase 5
  follow-up asked for — the processor's dedup is in memory for the life of the process,
  and gift wraps are backdated up to two days, so a caught-up `since` cursor re-delivers
  the whole band on every sync. One catch: the store is taken by `MarmotManager`, not by
  `MarmotInboundProcessor`, so this one is only available by adopting the client — which
  makes it the honest trigger for that decision rather than a change on its own.
- **The app's own copies.** Phase 0's third list, deleted.
- **A guard.** `docs/scripts/` already holds three audits that `./gradlew check` runs. A
  fourth, or a line in an existing one, that fails on `import com.vitorpamplona.quartz.marmot.`
  followed by anything but the four event classes — so the engine cannot come back from
  quartz by way of a cherry-pick from upstream, which is exactly the shape C2 predicts.

**Check:** `./gradlew check`, with the new guard in it.

## Out of scope

`marmot-quic` and agent text streams. MIP-04 encrypted media and the Blossom image
components, which marmot-kmp has and the app has no screen for. `MarmotManager` as the
app's engine — Phase 3 tries it in a test and Phase 7 names the reason to want it, and
that is as far as this plan goes. Moving quartz past 2.7.3, which is D2's other half and
needs marmot-kmp to move first.

## Where the numbers come from

Counted on the worktree at `8b066795`:

```bash
grep -rl 'import com.vitorpamplona.quartz.marmot' --include='*.kt' composeApp/src | wc -l   # 57 files
grep -rh 'import com.vitorpamplona.quartz.marmot' --include='*.kt' composeApp/src | wc -l   # 209 lines
grep -rh 'import com.vitorpamplona' --include='*.kt' composeApp/src | grep -v quartz.marmot | wc -l   # 854
```

The destinations were resolved by matching each of the 57 distinct symbols against
checkouts of marmot-kmp `e47856e`, mls-kmp `9702762` and quartz `v2.15.1`, and the
published coordinates and their transitive dependencies against the poms on Maven Central.
