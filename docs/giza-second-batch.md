# Giza's second batch: recovery codes, notifications, and what came with them

[giza-into-dedun.md](./giza-into-dedun.md) brings in Giza up to `d0437d6c`. Since then Giza
has gone **193 commits further** (166 of them not merges), to `a5a802c5` on 2026-11-26. This
plan covers those commits: which ones Dedun wants, in what order, and what has to change
first.

The headline is **recovery codes** (`dffe64c3`..`7d479e2b`). A new profile gets a nine-block
code instead of twelve words. It is shorter to write down, and its checksum can say which
character is wrong. Most of the rest is worth having too, but one thing has to be settled
before most of it can land. Giza's new features took event kinds that Dedun had already
claimed. See "The kinds collide again".

Written against Dedun `a5ab9c10` (schema 49), Giza `a5a802c5` (schema 55) and
`lightning-kmp-app` `3ca351a`. It was **not** checked by a trial merge or a build. Phase 0
does both.

## What is in the batch

| group | commits | Giza schema | new kinds | value to Dedun | verdict |
|---|---|---|---|---|---|
| **recovery codes** (`recovery-code-v3`) | 10 | none | none | high: a shorter backup a person can check, and a typo it can point to | **take first** (Phase 2) |
| fixes: desktop runtime `9a1abc61`, `ProposalListRoute` NavType `b7e5dd19` | 2 | — | — | high: both break the desktop build Dedun ships | **take** (Phase 1) |
| fix: signing lock order `22d36931` | 1 | — | — | the fix is in confederation code; the bug pattern may be in Dedun's managers | **audit** (Phase 1) |
| **notifications and background sync** | 16 | 47→48 | none | high: today Dedun says nothing while it is closed | **take** (Phase 3) |
| a second group from the same people (`nip17-multiple-ceremonies`, `fff891a4`) | 12 | none | none | medium: two savings circles with the same members | take (Phase 5) |
| the group's nostr profile and posts (`group-nostr-profile`) | 11 | none | none (kind 0 and 1, group-signed) | medium: a group outsiders can look up, which suits contacts' deposits | take (Phase 5) |
| the group's inbox (`group-inbox`, NIP-4E) | 13 | 45→46 | 30410–30416 | medium–high: someone outside the group can write to it | take after Phase 4 (Phase 6) |
| the group's direct messages (`group-threshold-ecdh`) | 14 | 46→47 | 30400–30401 | medium: the group answers as itself | take after Phase 4 (Phase 6) |
| disappearing messages | 10 | 49→50 | none | medium: NIP-17 conversations and ceremony rounds stop living on relays forever. Risky wherever a Dedun flow reads a NIP-17 round after its session ends | **take (D11)**, with Dedun's own classification of its kinds (Phase 7) |
| group names `{name}` (`group-name-prefix`, `group-name-braces`) | 16 | 52→53, 54→55 | none | low: looks only, but it changes the name on the wire | take, adapted (Phase 8) |
| **confederations** (a group made of groups: Parts A–C, `-without-coordinator`, `-profile`, `-room-removal`, `-dkg-inboxes`, `-auto-join`, upstream, shared seats, `50f73e17`) | ~75 | 42→45, 48→49, 50→52, 53→54 | 9430–9438, 30379–30399 | none: Dedun does not need them (D12) | **skip**; refuse their kinds (Phases 4 and 9) |
| `bitcoin-kmp` confederation API down the submodule chain | 4 | — | — | none of its own, but recovery codes' submodule commit sits on top of it | comes with Phase 2 |
| icon | 1 | — | — | — | skip |

### Where they come from: Giza directly, this time

Bennu has not ported any of it: `bennu-kmp` has had one commit since 2026-11-21. So the
first plan's route, Bennu's port with the wallets already put back in, does not exist for this
batch. Each commit comes from Giza through both mirrors, `giza-mirror.sh` (giza → bennu) and
then `bennu-mirror.sh` (bennu → dedun), as Phase 0 step 3 of the first plan did for
`36be1efc`.

That costs less than it did the first time. Giza deleted the wallets *before* the first batch,
so every diff in that batch had to have them put back in. This batch is mostly written in
places that never had a wallet. Counting files touched by name (wallet, Iceberg, Lightning):
recovery codes touch six, notifications one, the group profile two, and the name rename one.
The inbox, group direct messages, disappearing messages and the second ceremony touch none.
The wallet work Dedun has to do is semantic, not textual. The phases list it.

## The kinds collide again

Giza's new features allocated 30379–30416, without a register to check against. Dedun's
[kinds.md](./kinds.md) had claimed 30383–30412 for Dedun, after measuring Giza at
`d0437d6c`. Phase K of the first plan moved Dedun's kinds *into* that range:

| kind | Dedun's meaning | Giza's new meaning (from `a5a802c5`) |
|---|---|---|
| 30379–30382 | legacy group Wavelength numbers, read-only after K3 | confederation ceremony: `CARRIED` 30379, `HOST_KEYS` 30381, `DEALINGS` 30382 (30380 retired) |
| 30383–30389 | free, "Dedun's range" | confederation ceremony: `AUTHORIZATIONS` 30384, `ACKNOWLEDGMENTS` 30385, `FAILURE` 30387 (30383 retired, 30386 and 30388 reserved) |
| **30390–30393** | deposit attribution, coin assignment, transfer request, member transfer (K1 reads them; nothing writes them yet) | confederation signing: `PROPOSAL`, `AVAILABILITY`, `SESSION`, `NONCES` |
| **30394–30399** | contacts: record, amendment, depositor statement, claim, request, payout destination (built 2026-11-26) | confederation signing: `CONFIRMATIONS`, `PARTIALS`, `VOUCHER` (30396), `CARRIED` (30398), `FAILURE` (30399) |
| **30400–30401** | group Wavelength (moved), K1 reads them | group direct messages: ECDH `PARTIALS`, `FAILURE` |
| **30410–30412** | group Wavelength (moved), K1 reads them | inbox key ceremony: `PROPOSAL`, `HOST_KEY`, `ROUND_1` |
| 30413–30416 | free | inbox key ceremony: `COORDINATOR_ROUND_1`, `ROUND_2`, `CERTIFICATE`, `FAILURE` |

Neither side was careless. Giza allocated its numbers on 2026-11-22 and 23. Dedun's K1 and
its register were measured on 2026-11-21 and 25, against a Giza that did not have them yet.
The first plan's Phase 11 asked for a `kinds.md` in all three repositories. Only Dedun's was
written.

All of these travel *inside a Marmot room*. Confederation messages are carried by each group's
admins between the group's room and its inbox. Group direct-message rounds and the inbox key
ceremony run among a group's admins in its room. So in a room that has both a Giza and a Dedun
device, the numbers would meet on one device. That is exactly the wire compatibility that
decision D1 of the first plan was chosen to keep.

**What makes this cheap now:** `MovedKinds.writesNew` is still `false`. No Dedun build has
written 30390–30393 or 30400–30412. K1 only *reads* them. The contacts kinds are a day old.
Retargeting before K2 means changing the 17 targets in one map, and moving the six contacts
kinds. If this waits until after K2, a third move would need three more releases.

**Decided (D8): Dedun moves.** Phase 4 carries it out. Nothing in Phase 6, and no K2 release,
ships before Phase 4 is done.

## Decisions

D0–D7 are the first plan's and stand as decided. The owner decided D8–D13 on 2026-11-26.

| | question | decided | what it means for this plan |
|---|---|---|---|
| D8 | Who moves out of 30379–30416? | **Dedun moves**, before K2 | Phase 4 retargets the 17 moved kinds and the six contacts kinds to one block that is free in all three apps and in the NIP list. The candidate is 30420–30442: 23 numbers for 23 kinds, ending just below 30443, which is Marmot's KeyPackage. Phase 4 then writes `docs/kinds.md` into Giza and Bennu with Dedun's block in it |
| D9 | When does a new profile from Landing get a recovery code? | **Starting now**: the release that brings in recovery codes hands them out | Phase 2f lands in the same release as 2a–2e, not one release later. Giza's safety rule still holds within that one build: a build that gives out a code can also restore it and show it again. An older build that sees a code profile sees its 24 words (Phase 2b), so a downgrade loses nothing |
| D10 | What does a Dedun recovery code say it backs up? | **The profile and its wallet** | The copy says that the code brings back the profile and its wallet. Giza's rule that "no user-facing string says wallet" does not carry over. Giza's warning that the code belongs to this profile only stays |
| D11 | Disappearing messages | **Taken** | Phase 7. **What a person writes does not disappear by default (Off)**; each person can turn it on. Every Dedun kind that travels over NIP-17 is classified explicitly, because Giza's table only knows Giza's kinds |
| D12 | Confederations | **Not needed in Dedun** | Not taken. Their kinds are refused out loud inside rooms (Phase 4), and their relay kinds 9430–9438 are never subscribed to (Phase 9). `{{name}}` is left out of Phase 8 |
| D13 | Send a group's bare name on the wire (`cc64856c`)? | **Yes, as Giza does now** | Phase 8 |

## The schema numbers

Dedun is at 49. The first plan still has two migrations to come: governed rooms (Phase 8) and
promotion (Phase 9). Each migration below takes the next free number *when it lands*, and the
ledger records the real one. Regenerate every schema JSON, and never copy one. Giza's
describe a database with no wallets in it.

| Giza | what | spec | Dedun phase | expected Dedun number (if this order holds) |
|---|---|---|---|---|
| 47→48 | read marks, mute, `NotificationLedger` | `ReadMarkMigrationSpec` (marks every room read up to its newest line) | 3 | 50 |
| — | first plan's governed rooms | — | first plan, 8 | 51 |
| — | first plan's promotion | — | first plan, 9 | 52 |
| 45→46 | the group's inbox | — | 6 | 53 (as built) |
| 46→47 | the group's direct messages | — | 6 | 54 (as built) |
| 49→50 | disappearing messages: the policy columns, `ChatMessage.expiresAt`, `GiftWrapPayload.expiresAt`, `DisappearingSetting`, `ExpiredGiftWrap` | — (Giza needs no data step; see Phase 7 for Dedun's default) | 7 | 55 (as built) |
| 52→53, 54→55 | group names stored bare | `MarmotGroupBareNameMigrationSpec`: Dedun goes from `#name` straight to the bare name, in one migration and one spec | 8 | 56 (as built) |
| 42→45, 48→49, 50→52, 53→54 | confederations | three specs, two of which assume Giza's earlier versions | never (D12) | — |

`ShippedProfileUpgradeJvmTest` gets a new step for each migration taken. The specs come in
by hand, not through the mirror. They name Giza's version numbers in their KDoc, and
`ConfederationCeremonyInboxMigration` hardcodes kind numbers.

## Phase 0: source, ledger, the facts Phase 4 needs, baseline

No change to the app.

1. **Pin the source.** Fetch Giza into this repository:

   ```bash
   git fetch ~/Documents/development/giza.mobi/giza-kmp giza:refs/remotes/giza/giza
   ```

   Record `a5a802c5` as the tip ported from. Run `giza-mirror.sh` and then `bennu-mirror.sh` over
   `d0437d6c..a5a802c5`. Check that the mirror of an untouched file, such as
   `RecoveryCodeFormat.kt`, comes out under `com/machankura/dedun` with nothing Giza- or
   Bennu-branded left in it.
2. **The ledger.** Add 166 rows to [giza-ledger.md](./giza-ledger.md) with
   `giza-ledger.py`, measured from `d0437d6c`. Each row gets its group from the table above
   and a verdict: *take*, *adapt*, or *skip* (confederations, under D12). The Bennu column says
   "not ported". Merge commits get no rows.
3. **What has shipped.** For Phase 4: has any release (`versionName` ≤ 0.1.5, on any chain)
   been built from a commit that has `e12565be` (K1) or `2fdbc2bf` (contacts)? The answer
   decides whether Phase 4 needs an extra read-both release (K1′) before K2. Record it in the
   ledger.
4. **A free block.** Grep Giza, Bennu, Dedun and the version of quartz that Magpie resolves,
   and read the NIP README, for 30413–30449. Confirm 30420–30442 or propose another block.
5. **The submodule.** `lightning-kmp-app` `3ca351a` (recovery codes' Phase 3) is on
   `origin/claude/recovery-codes`, not on `master`. It sits on `e6be5e5` and `572b931`, which
   move `lightning-kmp`, and through it `bitcoin-kmp`, to the confederation API. Check:
   - that the chain still builds in Dedun's composite next to `experimental/wavelength-kmp`, on
     the same AGP and Kotlin;
   - that no change in `bitcoin-kmp` touches FROST signing, ChillDKG or Iceberg code that
     Dedun's wallets call.

   Ask for `claude/recovery-codes` to be merged to `master`. The first plan's Phase 11 rule is
   to pin only what Giza and Bennu can also reach.
6. **A trial merge.** Merge the mirrored range for Phases 1–3 onto `a5ab9c10` and record the
   conflicted files. The expected heavy ones are `DedunNavHost.kt`, from both the runtime
   refactor `1ac51905` and the recovery routes; `SovereignWalletViewModel.kt`; `Identity.kt`;
   `StoredIdentity.kt`; `SignInScreen.kt`; `strings.xml`; `DedunDatabase.kt`; and the Android
   and desktop entry points. Throw the merge away.
7. **Baseline** on `a5ab9c10`: jvmTest, `m3Audit`, `check`, and the four regtest suites, each
   on a fresh network.

**Done when** the ledger has a row and a verdict for every commit, the shipped-or-not answer
and the free block are written down, and the baseline is saved.

## Phase 1: the fixes

Small, and can ship on their own.

1. **`9a1abc61`, the desktop runtime's modules.** Dedun's `composeApp/build.gradle.kts` lists
   no `modules(...)` for `nativeDistributions`. So its jlinked runtime probably lacks
   `jdk.unsupported` too, and DataStore writes fail in the packaged app. Run
   `./gradlew :composeApp:suggestRuntimeModules` first. Dedun links more than Giza does
   (Phoenix's node, the Wavelength engine), so take what that task reports rather than Giza's
   list. Check it in a packaged app, with a throwaway `XDG_DATA_HOME`: create a profile, open
   the wallet, receive.
2. **`b7e5dd19`, `ProposalListRoute`'s enum filter.** Dedun has the same route, so the desktop
   and iOS nav graphs throw when they are built. Take `RouteNavTypes.kt` and
   `RouteNavTypesJvmTest`. The test finds every `@Serializable` route on the classpath. That
   includes Dedun's many wallet routes, so any other enum argument they have shows up there
   and gets an entry in the map.
3. **The lock-order audit (`22d36931`).** Giza's bug: a manager took its own `Mutex` and then
   waited for Room's single writer, while the same event, arriving inside
   `storeNostrEvent`'s transaction, held the writer and waited for that mutex. Every write on
   the device stopped. Look for the same pattern in Dedun: any `Mutex` or `withLock` in a
   manager that also writes to Room and can be reached from inside `storeNostrEvent`. The
   places to look are `FrostSigningManager`, `GroupLightningWalletManager`,
   `GroupWavelengthWalletManager`, `IcebergRitualManager`, `MemberTransfers` and
   `DepositLedger`. Fix any that match with Giza's `locked()` shape (writer first, then
   mutex), with a test that hears one event both ways at once. If none match, write that down
   in the ledger.

**Done when** a packaged desktop build creates a profile and opens its wallet, and
`RouteNavTypesJvmTest` passes.

**As built.**

- `b7e5dd19` came in as written. `RouteNavTypesJvmTest` builds every route Dedun has into one
  graph, and no other route needed an entry.
- `9a1abc61` came in without Giza's icon lines. Dedun's `suggestRuntimeModules` reports the same
  five modules as Giza's.

**The lock-order audit found nothing to change:**

- `MlsGroupCache`'s per-group lock is taken only from `NostrDao`, inside `storeNostrEvent`'s
  transaction, so it is always taken after the writer.
- `GroupArkCoSigner` already takes the writer first, then its lock.
- `CeremonyFollowThrough` and `GroupArkTransport` hold their mutexes on paths that start from
  database flows and from the Wavelength daemon, never from inside `storeNostrEvent`.
- `MarmotEngine`'s restore lock covers reads only.

**Not done:** a packaged desktop build making a profile and opening its wallet.

## Phase 2: recovery codes

**Starts first (D9).** Of Phase 0 this needs only step 5 (the submodule), step 7 (the
baseline), and the ledger rows for its ten commits. It does not wait for Phase 1, and it
touches no kinds, so Phase 4 does not hold it up.

Giza's plan is `docs/recovery-code-v3.md` in Giza's tree (copied here, debranded, in 2a). Read it first. This phase follows its
phases one for one and lists only what is different in Dedun.

**The one Dedun fact that changes this feature.** In Giza, a phrase is "a key backup and not
a wallet". In Dedun, `seed.dat`'s words start the personal Lightning node
(`Identity.business` is non-null only for `IdentityKind.Mnemonic`), and the profile id is the
hash160 of that node's id. Giza's derivation choice B helps here. It turns the code into 24
BIP39 words and keeps them in `seed.dat` as a phrase, so the node is derived from a code
profile's words exactly as from typed ones. The code **does** restore the wallet, and every
step below is about making sure it does that and says so (D10).

### 2a: the codec and what a code derives (Giza Phases 1–2)

- Take `RecoveryCode`, `RecoveryCodeFormat` and `RecoveryCodeKeys` as Giza wrote them, along
  with the 77 normative vectors and `docs/scripts/recovery-code-keys.py`. These are pure
  Kotlin with nothing to adapt.
- **Dedun-only vector.** Pin a code's node id, not just its npub and profile id. Take one of
  Giza's vector codes, derive its 24 words, start a Phoenix node from them in jvmTest, and pin
  the node id. Once a code has been handed out, changing this would change whose money the
  code opens.
- 24 words into the node: Phoenix's seed is `MnemonicCode.toSeed(words, "")`, whatever the
  number of words. Check that nothing between `seed.dat` and `NodeParams` assumes twelve
  (`CredentialParser.recoveryPhrase`, the phrase screen's grid, `RecoveryPhraseViewModel`).

### 2b: where a code is kept (Giza Phase 3)

- Bump `lightning-kmp-app` to `3ca351a`, or the merge of it on `master` (Phase 0 step 5).
  `recovery-codes.dat` sits beside `seed.dat` and `nostr-credentials.dat` under
  `KEY_NO_AUTH`.
- Giza's rule applies unchanged: *a code must derive the words beside it*, or it is dropped
  and the profile lists as its phrase. In Dedun it matters more, because the words are what
  start the node.
- An older Dedun build sees a 24-word phrase profile with its wallet, with the right npub,
  id and node. Test that by downgrading.

### 2c: the code as an identity (Giza Phase 4)

- Giza adds `IdentityKind.RecoveryCode`. Dedun has six `IdentityKind.Mnemonic` sites, in
  `SovereignWalletViewModel`, `KeyRecoveryScreen`, `Identity` and `StoredIdentity`. Each one
  asks either "does this profile have words?" or "is this the twelve-word kind?". The first
  question gets a predicate, `IdentityKind.hasWords`, that is true for both kinds. Above all,
  `Identity`'s `require` that `business` is non-null must hold for `RecoveryCode`, so a code
  profile runs its node. List the six sites in the PR, with the answer each one now gives.
- `WalletsSelector` and `SovereignWalletStartupScreen` are among Giza's six wallet-named
  files. In Dedun they are real wallet screens. Merge Giza's lines into them by hand.

### 2d: signing in with a code (Giza Phase 5)

- Giza changed `CredentialParser`, `SignInScreen` and `SignInToProfileViewModel`. Dedun's
  sign-in also restores a wallet: the `isRestoringWallet` path of `writeSeed`. A code typed at
  sign-in takes that path, so a restored code profile finds its channels and its swap-in
  coins.
- The typo suggestion is never applied without a press. The confirm step shows the npub.
  Dedun also shows the restored node's balance once it has synced, the same as a restored
  phrase does today.
- Versions 1 and 2 are refused by name. A Muun **wallet's** v3 code would be accepted as a
  profile. Dedun's copy says, before the write, that the code has to be one this app gave out
  (D10).

### 2e: showing the code, and checking a written-down copy (Giza Phase 6)

- `RecoveryCodeScreen` and `RecoveryCodeViewModel` come in as their own screen. Dedun's
  backup reminder, currently tied to `RecoveryPhraseViewModel` and shown because a wallet
  exists, leads to the code screen for a code profile and to the phrase screen otherwise.
- **Copy (D10):** "This code brings back this profile *and its wallet* on any device. Write it
  down and keep it somewhere only you can reach." The "words for other apps" export also says
  that a 24-word import elsewhere opens the same Lightning node, so the person should not run
  both.
- Follow CLAUDE.md: strings in the catalogue in sentence case, `readableContent()`, 48dp
  targets, a decided description on each icon, four states, and `NavigateBackButton` (it is a
  pushed screen). The grid of nine blocks gets a dimension `.dp` for its cells and nothing in
  spacing positions.

**2a–2f ship together (D9).** Giza's §11.1 rule is that no build hands out a code it cannot
restore or show again. Giza kept that by landing Phases 1–6 before Phase 7 on one branch, and
Dedun does the same: one branch, these steps in this order, one release. Nobody gets a code
from a build that lacks 2a–2e. The only step that cannot be undone is 2f, so the regtest
restore test below has to pass before 2f is merged.

### 2f: new profiles get a code (Giza Phase 7)

- `recoveryCodeProfileWriter()` replaces `seedProfileWriter()` on the Landing path.
  `CreateProfileRoute.withWallet` stays named `withWallet` in Dedun. It is still true that
  the first profile on a device is the one the wallet belongs to. What changes is that the
  wallet is now backed by a code.
- **Guard.** Giza's `wallet-audit.sh` fails on `MnemonicCode.toMnemonics` anywhere outside
  `RecoveryCodeKeys.kt`. Dedun has a second, legitimate caller:
  `wallet/wavelength/WalletSeedStore.kt`, the Wavelength wallet's own twelve words. Add the
  guard to `m3Audit`'s neighbour scripts with that file exempted by a marker comment, in the
  same style as `m3-color-exempt`. Whether the Wavelength seed should also come from the code
  is a separate question, and out of scope here.
- The switcher's "add a profile" stays a bare key, as now.

### Tests

- Giza's suites: `RecoveryCodeTest`, `RecoveryCodeKeysTest`, `RecoveryCodeVectorsJvmTest`,
  `StoredIdentityJvmTest`, `IdentityWriterJvmTest`, `CredentialParserJvmTest`,
  `RecoveryCodeScreenJvmTest`, `RecoveryCodeViewModelJvmTest`,
  `SignInToProfileViewModelJvmTest`, and the changed `MultipleProfilesRoundTripJvmTest`.
- **New:** the node-id vector (2a).
- **New:** create with a code, forget the device's files, sign in with the code. Check that
  the npub, id, node id and preferences are the same, and that the wallet restores. Then the
  same through the exported 24 words.
- **New, regtest:** `GroupLightningRegtestJvmTest`'s personal-wallet setup made from a code.
  Open a channel, wipe the device, restore from the code, and find the channel and its
  balance.
- **New:** a downgrade to a build without 2b lists the profile as a 24-word phrase with the
  same wallet.

**Done when** those pass, `m3Audit` is within budget, and a packaged desktop build and an
Android emulator have each made a code profile, received a payment, been wiped, and been
restored from the code with the payment there. iOS needs a Mac, as in the first plan, and is
recorded as not run.

**As built.** One commit for each step, 2a–2f, plus Giza's Landing caption fix. Giza's own note
is in [recovery-code-v3.md](./recovery-code-v3.md), with a Dedun preface.

- **2a.** The codec, the 77 vectors and `RecoveryCodeKeys` came in unchanged. Dedun has no
  `PhraseKeys`: an npub, a profile id and a node all come from `LocalKeyManager` on the build's
  chain. So `RecoveryCodeKeysTest` pins Giza's three codes through `LocalKeyManager` on mainnet,
  and adds each code's node id. The node id's hash160 is the profile id that
  `recovery-code-keys.py` derives independently, so the script vouches for the node id too.
- **2b.** Giza's `lightning-kmp-app` `3ca351a` also moves `lightning-kmp` across "Merge the
  threshold line into master, reconciling the two Iceberg ports". That touches the FundingSigner
  seam under the group Lightning wallet, which Giza never runs. So the one library commit was
  cherry-picked onto Dedun's `d87af507` instead, as `d493370` on
  `claude/recovery-codes-on-d87af507`. **It is local to the submodule until it is pushed.** The
  `lightning-kmp` move is left for a phase of its own, with the regtest suites.
- **2c.** `StoredIdentity.Seeded` covers both `Mnemonic` and `RecoveryCode`. Startup starts the
  node for either kind, and `setActiveWallet` takes either and keeps its kind. `writeRecoveryCode`
  derives the profile through `LocalKeyManager`, as `writeMnemonic` does, so a code and the same
  words typed are one profile on every chain. Giza's `DefaultIdentity` belongs to its background
  sync, so it waits for Phase 3.
- **2d, 2e.** Giza's screens, with Dedun's copy (D10). The "recognised" line says the code brings
  back a profile and its wallet, and to use only a code Dedun gave out. The backup screen says
  that anyone holding the code can spend the money, and that losing both phone and code loses
  the money. The export says not to run the wallet in two apps at once.
- **2f.** Landing makes a code profile. `withWallet` stays. The guard is
  `docs/scripts/phrase-audit.sh` (`phraseAudit` in `check`), with the Wavelength wallet's own seed
  exempted by a `phrase-audit-exempt:` line.

jvmTest for the identity, sign-in and recovery suites: 113 run, 0 failed. `m3Audit`: all budgets
met. **Not done:**
- the regtest restore of a code-made wallet, which is the gate before 2f reaches `dedun`;
- the downgrade test;
- the Android emulator and packaged desktop runs;
- iOS.

## Phase 3: notifications and background sync

Giza's `docs/notifications-and-background-sync.md`, Phases 1–14. Of all the Giza work besides recovery codes, a member feels this most: today a
payout to sign waits until somebody happens to open the app.

The order:

- the runtime refactor (`1ac51905`, one runtime per process, owned by the process);
- read marks and mute, with the schema migration (`c248ab52`);
- the pure rules for what notifies (`f2e57edc`);
- foreground notifications;
- the bounded off-screen sync (`4a6f45aa`);
- the Android 15-minute worker and "Stay connected";
- iOS `BGTaskScheduler`;
- the desktop tray;
- mark-as-read and reply from the notification.

**Dedun-only work:**

- **One foreground service, not two.** Dedun already runs `GroupWavelengthService` and the
  `WavelengthMaintenanceWorker` and `GroupWavelengthHealthWorker`, and posts its own alerts
  (`GroupWavelengthAlertsPlatform`). Giza's "Stay connected" is a second opt-in service that
  holds the relays open. Fold them into one service with one ongoing notification. Keep the
  Wavelength alerts on a channel of their own, so muting chat does not mute "your group's
  wallet needs a signer".
- **The runtime refactor and the wallets.** `1ac51905` moves what `DedunNavHost` and
  `SovereignWalletViewModel` start into a process-owned runtime. In Dedun that also starts the
  Phoenix node and the group wallet managers. Starting them in the runtime is what lets the
  background sync see a payout proposal at all. Pin it with a test: a cold background sync,
  with no UI, files a signing proposal and posts its notification.
- **What notifies.** Giza's pure rule (`NotificationRules`, or whatever `f2e57edc` named it)
  is about chat lines. Add Dedun's own: a signing proposal this device can sign, a payout to
  this member, a deposit attributed to them, a transfer to them. Keep them in the same pure
  place, with tests. Say which are on by default.
- **The bounded sync's budget.** Android gives a worker about 10 minutes, and iOS gives about
  30 seconds. The sync reads relays and files events. It must not *sign* in the background
  unless the existing signing rules already sign without asking, which the automatic-ceremony
  and single-approval work defined. Check that no signing path is added by moving to the
  runtime.

**Tests:** Giza's notification suites, plus the background-sync test above, plus the
Wavelength alert still firing with chat muted.

**Done when** an Android emulator with the app swiped away shows a notification for a payout
to sign within one worker period, and the desktop app closed to the tray does the same.

**As built.** Giza's Phases 1–13 came in as eight commits, with Giza's note in
[notifications-and-background-sync.md](./notifications-and-background-sync.md) and a Dedun preface.

- **The runtime.** `DedunRuntime` holds the database, the six shared repositories and the
  notary and synchronization engines. The wallets' objects and their five repositories stay in
  `DedunNavHost`, on the runtime's database.
- **Schema 50.** Read marks, mute and the notification ledger. Giza widened its unshipped 47 in
  place when the switches came, and Dedun's 50 is widened the same way.
- **What notifies.** All 59 transcript types that Dedun has and Giza doesn't are classified:
  - *needs you:* the three Iceberg approvals, an operator's equivocation, quiet Wavelength
    signers, a Wavelength deadline;
  - *group activity*, off by default: money that moved, and wallets and ceremonies that began or
    ended;
  - *silent:* everything else.

  The categories go by line type, so a payout to *you* is not yet told apart from a payout to
  another member.
- **The background pass.** `DefaultIdentity` (held back from 2c) runs a profile with words as its
  nostr key without starting its node. It lists a recovery-code profile as one. The Wavelength
  daemon follows `AppLifecycle.isVisible`.
- **Platforms.** Android's worker and Stay connected, iOS's tasks, the desktop tray and the
  notification actions are all in. Background sync is behind `ExperimentalFeatures.backgroundSync`,
  off in release.

**Not done:**

- Stay connected and `GroupWavelengthService` are still two foreground services.
- No test runs a cold background pass that files a FROST signing proposal and posts its
  notification.
- No device run (an emulator with the app swiped away, the desktop closed to the tray).
- The iOS half is not compiled: there is no Mac here.

## Phase 4: the kinds again (D8: Dedun moves)

This runs alongside Phases 2 and 3. It has to be done before Phase 6, before any K2 release,
and before confederations are taken up again.

As decided under D8, Dedun moves:

1. **Register first.** `docs/kinds.md` gets the new block. Then 30379–30389 move from "free,
   Dedun's range" to Giza's confederation ceremony, 30390–30399 to Giza's confederation
   signing, 30400–30401 to Giza's group direct messages, and 30410–30416 to Giza's inbox key
   ceremony. The same file goes into Giza's and Bennu's trees, as a PR to each, so the next
   allocation on any side checks the other two.
2. **`MovedKinds` retargets.** `LEGACY_TO_NEW` maps 30340–30343 and 30370–30382 to the new
   block. Legacy numbers do not change, so stored rows and chronicles read as before.
   `writesNew` stays `false`.
3. **The numbers K1 read become foreign.** A K1 build reads a live 30390–30393 or
   30400–30412 as Dedun's. If Phase 0 found that K1 never reached a release, this is a
   developers-only concern: delete those reads. If it did, ship this retarget as K1′ (read
   legacy and the new block, and refuse 30390–30416 out loud) and wait for it to be on the
   devices that matter before K2.
4. **Contacts move.** The six contacts kinds go to the new block. If Phase 0 found contacts
   in a release, they get a legacy entry in `MovedKinds` like the others. If not, they simply
   change.
5. **Refusal tests, the first plan's Phase 1 pattern.** For each Giza kind in 30379–30416, feed
   in a fixture as Giza writes it (a confederation dealing, a signing proposal, an ECDH
   partial, an inbox `ROUND_1`) and assert that:
   - no Dedun reader takes it;
   - no ledger, balance, contact or Wavelength state changes;
   - the transcript shows nothing, or one line saying it was declined.

**Done when** `MovedKindsJvmTest` and the refusal tests pass, `kinds.md` is in all three
trees, and the first plan's K2 and K3 are written against the new block.

**As built.** Phase 0 found that no release carries K1 or contacts, so this is one change with no
K1′. Every Dedun kind in 30390–30412 moved up by 30 and kept its order:

- the deposit attribution, coin assignment, transfer request and member transfer: 30420–30423;
- the six contacts kinds: 30424–30429;
- the thirteen Wavelength kinds: 30430–30442.

`MovedKinds` maps the legacy 30340–30343 and 30370–30382 straight to the new block, and
`writesNew` is still `false`. The interim numbers are not legacy numbers. No device holds an
event signed under one, and they are Giza's now.

`GizaProtocolEvents` reserves Giza's confederation ceremony (30379–30388), confederation signing
(30390–30399), group direct messages (30400–30401) and inbox key ceremony (30410–30416), so the
transcript shows none of them. `GizaKindsRefusedJvmTest` gains four tests:

- none of Giza's new numbers reaches a Dedun reader;
- a carried confederation envelope at 30379 is no Wavelength authorisation;
- host keys and dealings are no deposit answer or stop;
- none of Giza's new kinds leaves a line in the transcript.

`docs/kinds.md` is the new map, and the two plans that name the old numbers say so at the top.
jvmTest: 3,144 run, 0 failed.

**Not done here:** the register in Giza's and Bennu's trees. Phase 10 wrote it on a branch in
each, not pushed.

## Phase 5: a second group from the same people, and the group's profile

Two features, independent of each other, with no schema and no kinds.

**`nip17-multiple-ceremonies`** (`dd812950`..`03e2d10d`, plus `fff891a4`): the same people
can start a second group's ceremony from one NIP-17 room, and the room lists the groups made
in it. Leave out `50f73e17`, which is confederation-only.

Dedun-only checks:

- The group Lightning and Wavelength wallets run their own Iceberg ceremonies
  (`IcebergKeySession`). Find every lookup that assumes one FROST ceremony per NIP-17 room and
  make it key on the ceremony instead. The suspects are the automatic-ceremony resume sweep
  and the subgroup birth path.
- A subgroup's ceremony is not a "second group from the same people", and must not show up in
  that list.

**`group-nostr-profile`** (`bdb0b3f0`..`d039daf6`): the group signs its own kind 0 and kind 1
with FROST, and members propose them. It uses the generic `proposeSigning` path.

Dedun-only checks:

- A profile or post being signed is a statement that is open. Add it to
  `RotationHold.StatementOpen`, the same as attributions and payouts.
- Payouts-only (`ee142c61`) refuses to sign a spend that pays change. A kind 0 or kind 1 is
  not a spend. Make sure the signers' checks in `FrostSigningManager` route these kinds to
  Giza's acceptance check and not to the spend checks.
- Suggested follow-on, not in this port: the contact's page
  ([non-member-deposits.md](./non-member-deposits.md)) links to the group's profile, so a
  depositor outside the group can see who they are paying.

**Done when** Giza's suites for both features pass, and in a three-device debug run one NIP-17
room makes two groups, each with its own wallet, and one group publishes a profile.

**As built, in part.**

The group's profile and posts, Giza's Phases 1–4, are in:

- `GroupNostrProfile` and `GroupPost` read a group's kind 0 and kind 1 off what the room's key
  signed.
- The transcript has their lines. The two legacy types, `groupProfileSigned` and
  `groupPostSigned`, are live again under their old strings.
- `ProposedEvent` names them.
- The signer declines, out loud, a profile or a post nothing could show. From Giza's side the
  signer takes only `GroupStatementRefusal`, not Giza's wallet refusal or governance check.

jvmTest: 3,246 run, 0 failed.

**Not taken, and why.** What follows in Phase 5 lands on code Dedun has not built.

- **The profile and post screens** (Giza's Phases 5–9). They rewrite `ChatRoomDetailScreen`,
  its view model and its state. Those are where Dedun and Giza differ most: the first plan
  measured 819 lines of change on Giza's side against 477 on Dedun's. Giza's side also carries
  `ChatRoomDetailWithoutWalletsJvmTest`, which only makes sense for an app with no wallet.
- **A second group from the same people.** Steps 1 and 2 go across: re-applied by hand to
  Dedun's `SelectChatRoomTypeViewModel`, since Giza's `SelectQuorumViewModel` comes from
  remove-convenient-groups, which D3 declined. They were then backed out unmerged. Step 3,
  "nothing asks a NIP-17 room for its one ceremony", edits the member-row-action and
  governed-room files. Those come from the first plan's Phases 8–9, which Dedun has not built.
  Step 1 alone would let the same people open a second ceremony while other code still asks for
  "the" ceremony of a room, so none of it is taken until step 3 can be.

**What the rest of the batch needs first:**

- Phase 6 (the inbox and the group's direct messages) needs the first plan's Phase 9, as this
  plan already said. A probe of its commits found Giza's `GovernanceFollowThrough` among the
  files they change, from the first plan's Phase 8.
- Phase 7 (disappearing messages) is self-contained apart from `ada95867`. That commit waits
  for Phase 6.
- Phase 8 (names in braces) runs through 26 confederation files. They have to be read out of it
  by hand.

**Since then:** the first plan's Phases 8 and 9 are built ([giza-into-dedun.md](./giza-into-dedun.md),
their "As built" notes), on this branch and before K3. That lifts the first two blocks: step 3
of the second group, and Phase 6, now have the member-row-action, governed-room and
`GovernanceFollowThrough` code they edit. Their migrations take 53 and up, since the first
plan's took 51 and 52.

**The rest, as built.** The profile and post screens (Giza's Phases 5-9) and a second group from
the same people (`dd812950`..`03e2d10d` and `fff891a4`) are in. The first plan's Phases 8 and 9
had unblocked them.

- **Screens.** The group's screen gets Giza's rows for the profile, the posts and the paste, in
  Marmot rooms, beside the wallet rows. Dedun's `GroupSignedWorkRowsJvmTest` had said the rows
  were gone; it now checks they are there. Giza's wallet-less detail-screen test is not taken.
  The broadcast screen also sends the inbox's 10044 and 10050, as Giza's has since its inbox.
- **A second group.** Giza's `SelectQuorumViewModel` comes from remove-convenient-groups, which
  D3 declined. So naming the ceremony, and listing the groups these people already share, go
  into Dedun's `SelectChatRoomTypeViewModel` and screen, where a robust group's ceremony is
  proposed.
- **Step 3: nothing asks a room for its one ceremony.** Giza's six lookups are fixed by its
  commit. Dedun's own lookups were read for the same assumption:
  - the Iceberg keys of the Lightning and Wavelength wallets are found per Marmot room and
    purpose;
  - the ceremony follow-through finds ceremonies by session id, and the subgroup path by parent
    and members;
  - every `completedKey` caller asks about a Marmot room, whose key state names its ceremony.
  
  The fallback now answers only when a room holds a single key of its own.
- **Subgroups stay out of "groups made here".** Both lists read `getOwnSessionsForChatRoom`,
  which leaves out any ceremony with a parent.
- **A profile or post being signed holds a share rotation back** (`RotationHold.StatementOpen`),
  as Dedun's money statements do (`GroupStatementHoldsRotationJvmTest`). They are `EVENTS`
  sessions, so they never reach the payouts-only spend checks.

jvmTest after the hold's test was fixed: 3,678 run. One failure in the full run,
`ShareRefreshRitualJvmTest`, came from that hold: it signed a post across a rotation. It now uses
a kind the hold does not cover, and it passes. `compileDebugKotlinAndroid` and `m3Audit` pass.
Not run: the three-device debug run (one NIP-17 room makes two groups, each with its own wallet,
and one publishes a profile).

## Phase 6: the group's inbox and its direct messages

`group-inbox` (`ccc439b2`..`31bcb3a3`) and `group-threshold-ecdh` (`dae0a345`..`082d0971`):
- the group gets a NIP-4E inbox, with a key its admins hold at threshold 1;
- outsiders can write to it;
- the group replies once its quorum has read and signed the reply;
- with threshold ECDH, the group holds direct-message conversations as itself.

**Prerequisites:**

- Phase 4: the kinds.
- The first plan's Phase 9 (promotion and demotion). `8bc7ab3e` ("the inbox key changes with
  the group's admins") and `6f33f6f1` react to admin changes that Dedun does not have until
  then.
- D4, extended in the first plan. In a keyed room that keeps a Lightning or Wavelength wallet,
  admins do not change. So the inbox key's own rotation only ever runs where no admin change
  is refused. Test both cases.

**Dedun-only work:**

- **The inbox key must never sign for money.** Giza keeps `InboxKey` apart from `DkgSession`
  "so no signing path can find a threshold-1 key". Dedun has more signing paths. Run Giza's
  test of that rule against every Dedun `thresholdKeyFor` and `completedKey` caller listed in
  the first plan's Phase 6, and add one for each wallet.
- **Contacts' synergy, as a separate plan.** A contact outside the group could send their
  claim or their payout destination to the group's inbox, instead of having a member pass it
  on. Write that up as its own plan once this phase is in. It is a protocol change to
  non-member deposits, and not part of this port.

**Done when** Giza's inbox and ECDH suites pass, the "no signing path finds the inbox key"
test covers every Dedun wallet, and a debug run has an outside npub write to a group and get
a group-signed reply.

**As built.** All 27 commits are in, behind `ExperimentalFeatures.groupInbox` and
`groupDirectMessages`, which are off in a release as Giza's are. The inbox is schema 53 and the
direct messages 54, generated against Dedun's 52 and 53.

- **Giza's merge.** Giza built the two features on separate branches and joined them in
  `f57e9b8d`. That merge decided which code handles what: a seal naming an inbox key is the
  inbox's, any other seal is the direct messages'; a group-signed 10050 is read once, by the
  inbox; and a wrap the inbox key opens is never sent to an ECDH round. The commits are picked
  one at a time here, so the merge's decisions land in two places. Those touching files Dedun
  resolved by hand went in with those picks. The rest is a commit of its own
  (`fix: ... as Giza's merge of them resolved`), with the merge's new `GroupSealRoutingJvmTest`.
- **Taken from commits Dedun did not take.** These came out of Giza's group profile, broadcast
  and confederation work, because the inbox and direct messages need them:
  - `RelayUrlInput` and `EventPublishTransport`;
  - `SharedKeyDerivation.Derived.tweaks`;
  - `proposeSigningBatch(proposalTags = ...)`;
  - `LiveSubscriptionManager`'s `onEvent`;
  - `SigningProposalHarness`, a test helper.
- **Wired where Dedun builds things.** The inbox and direct-message repositories and publishers
  are created in `DedunRuntime`, beside the process-owned `SynchronizationViewModel`. Their rows
  on the group's screen sit above the Wavelength wallet's. 30400-30401 and 30410-30416 are no
  longer in `GizaProtocolEvents`.
- **The inbox key reaches no wallet** (`InboxKeyReachesNoWalletJvmTest`). A room is set up with
  both the group's key and an announced inbox key, and each Dedun lookup that signs for money
  is checked. The onchain wallet and the signer find the group's key. The Lightning and
  Wavelength wallets' Iceberg lookups find nothing. `WalletKeysHeld` does not count an inbox as
  a wallet.
- **D4 and the inbox's rotation.** These are tested apart, not together. A rotation is owed only
  when the admins differ from those the key was made over (`InboxRotationJvmTest`). In a keyed
  room that keeps a wallet, no admin change is made (Phase 9's tests). No test has yet run an
  inbox rotation in a room that holds a real wallet.
- Seven new line types are classified as Giza classifies them.

jvmTest: 3,542 run, 0 failed, 7 skipped. `compileDebugKotlinAndroid` and `m3Audit` pass. Not
run: the device test (an outside npub writes to a group and gets a group-signed reply). The
contacts plan this phase suggested is not written.

## Phase 7: disappearing messages (D11)

Giza's `docs/disappearing-messages.md`, Phases 1–8 (`962bdbf6`..`d30cea78`).

**What it does.** Every NIP-17 payload gets a NIP-40 expiry, and the device deletes what has
expired. There is a setting for each identity, and one for each conversation that overrides
it.

- **What a person writes** (kinds 14, 15 and 7, and 5 when it deletes one of those) expires
  after the policy. The policy can be *Off*.
- **Protocol messages always expire**, after `max(policy, floor)`. Signing has a 1-day floor,
  key ceremonies have 7 days, and a Marmot Welcome has 7 days. Any kind nobody classified gets
  7 days, and a test fails until someone classifies it.
- On the device, `ProtocolRetention` keeps an expired ceremony or signing round until its
  session has completed or failed.

It reaches **NIP-17 only**. Marmot-room traffic is not touched.

**Where Dedun stands.** A first reading of the code suggests that most of Dedun's money traffic
stays out of reach. The Iceberg, guardian, chronicle and group-Lightning lines are filed with
`giftWrapPayloadId = null`, which means they arrive through Marmot rooms. The NIP-17 users are
`ChillDkgRitualManager` and `FrostSigningManager`: the key ceremony and signing in NIP-17
rooms, which Giza already classifies. Step 1 below confirms this rather than assuming it.

**Order.** After Phase 3, because `c50288a9` puts the sweeper in the process-owned runtime
that `1ac51905` adds. `ada95867` ("what a group sends disappears too") changes the group
inbox and direct-message senders, so it lands with or after Phase 6. The other seven commits
do not wait for Phase 6.

**Dedun-only work:**

1. **An inventory of kinds.** Every kind Dedun puts into, or reads out of, a `GiftWrapPayload`,
   with the code that reads it. Put it in a table in the PR. Each one is classified in
   `DisappearingMessages.classOf`, with a floor that comes from its own timeline. Nothing is
   left to the 7-day fallback by accident. Dedun's kinds that are not in Giza's table are
   classified explicitly: the Iceberg key ceremony if any of it runs over NIP-17, subgroup
   ceremonies, guardian and member-wallet requests, and anything in 30390–30442 after
   Phase 4.
2. **What reads a round after its session.** A round may be deleted from the device once its
   session has finished and its expiry has passed. Find every reader of stored NIP-17 rows
   that runs *after* a session ends, and make sure that what it needs is kept as a result
   (key, share, certificate, session row) and not as a message. Start with:
   - the recovery walk (`57fdaa05`, from the host key to the live share);
   - `ChillDkgRitualManager`'s `giftWrapPayloadDao` reads;
   - `FrostSigningManager`'s;
   - re-enrollment (`edad2c7c`);
   - the resend in `frost-session-resend.md`.

   `ProtocolRetention` keeps a round only while it names a session that this device has. A
   round for a session this device never joined is released at its expiry, so check that no
   Dedun flow reads such a round later.
3. **Long waits.** Group Lightning and Wavelength co-signing can wait days for a signer.
   Wherever one of those waits on a NIP-17 round, its floor must be longer than the longest
   wait the wallet allows (the Wavelength `HIGH_WATER` timings, and the guardian delay is
   measured in blocks). If no such NIP-17 round exists, step 1 says so.
4. **The default for what a person writes is Off (decided 2026-11-26).** Giza gives one day
   to every identity, existing ones included (its D3 and D9). Dedun does not:
   - members settle disagreements about money by pointing at what was said;
   - protocol messages still always expire, so relays stop keeping ceremony rounds forever,
     which is most of the gain.

   Make these changes to Giza's commits:
   - the identity default constant is `0` (Off), not one day;
   - an identity with no `DisappearingSetting` row reads as Off, so the migration still needs
     no data step and no existing conversation changes when someone upgrades;
   - the setting screen offers Giza's presets, with *Off* first and marked as the default;
   - the notice that a conversation's setting changed (`disappearingNoticeShownAt`) shows only
     when someone turns it on;
   - the migration's KDoc and Giza's tests that assume a one-day default are rewritten for
     Off.
5. **The setting's copy** names what never disappears: the group's records (payouts,
   deposits, transfers, contacts) live in the group's room, and this setting does not reach
   them.

**Tests:**

- Giza's suites (`DisappearingMessagesTest`, `ProtocolRetentionJvmTest` and the rest).
- **New:** every kind in the step 1 inventory has a class. The test fails on an unclassified
  Dedun kind, the same as Giza's does for its own.
- **New:** a three-device debug run makes a group from a NIP-17 room with a Lightning wallet.
  The sweeper runs with the clock moved past every floor. Then the group still pays a member
  out over Lightning, and a re-enrollment still repairs a stale member.
- **New:** a Marmot-room payout or attribution is untouched by the sweeper, whatever the
  policy.
- **New:** after the upgrade, a new identity and an existing conversation both read as Off. A
  message written then carries no expiration, and a ceremony round written then still carries
  its floor.

**Done when** those pass, and the inventory and the reader list are in the PR.

**As built.** All ten commits are in. The storage is schema 55, generated against 54. Giza's
`ada95867` adds expiry columns to the inbox and direct-message rows; those columns fold into 55,
which has not shipped.

- **Off by default (step 4).** `DisappearingMessages.DEFAULT_SECONDS` is `OFF`.
  - An identity with no setting row, which is every identity on upgrade, reads as Off. So the
    migration needs no data step.
  - `PRESETS` already put Off first. On the profile's own setting, Off is now marked as the
    default.
  - The one-time notice in a conversation already shows only under a policy other than Off.
  - Giza's tests that assumed a day are rewritten. The upgrade test checks that an existing
    conversation and a new identity both read as Off.
- **The copy (step 5).** The setting says it does not reach a group's own room, where payouts,
  deposits, transfers and contacts are kept and never disappear. The profile row mentions direct
  messages and the conversations where groups are made, not confederations.
- **No confederation classes (D12).** The expiry classes lose their confederation kinds, and
  `ProtocolRetention` loses its confederation ceremony case.

**Step 1: the inventory.** Every place Dedun writes a `GiftWrapPayload`, and the class of what it
writes:

| writer | kinds | class |
|---|---|---|
| `DatabaseChatRepository.sendChatMessage` | 14, and 15, 7 and 5 where sent | written: the person's policy, Off by default |
| `MarmotOutboundDao` (a Welcome) | 444 | ceremony floor, 7 days |
| `ChillDkgRitualManager.broadcast` (a key ceremony, a subgroup's included) | `DkgRitualEvents.ALL` | ceremony floor, 7 days |
| `FrostSigningManager.broadcast` in a NIP-17 room (a wallet's spend and sweep proposals included) | `FrostSigningEvents.ALL` | signing floor, 1 day |
| (Marmot rooms today, classified anyway) | inbox key ceremony, share refresh and repair, threshold ECDH | ceremony or signing floor, as Giza |

`NostrDao` also writes a row: the incoming copy of a message, which carries its sender's expiry.
Nothing of these goes over NIP-17: the Iceberg ceremonies, Lightning and Wavelength co-signing,
guardians, member wallets, contacts, governance, or the kinds moved to 30420-30442. All of them
travel in Marmot rooms with `giftWrapPayloadId = null`. `DisappearingMessagesTest` checks that
every kind in the table has a class chosen on purpose.

**Step 2: what reads a round after its session.**

- Only two places read stored NIP-17 rounds back: the replays in `ChillDkgRitualManager` and
  `FrostSigningManager`. Both rebuild an open session. `ProtocolRetention` keeps a round while its
  session is open.
- The recovery walk reads `DkgSession` rows, not messages.
- Re-enrolment (share repair) and the resend run in Marmot rooms.
- A finished session keeps what it produced on its own row: the shares, the key, the signature.

**Step 3: long waits.** No Lightning or Wavelength co-signing waits on a NIP-17 round; it runs in
the group's Marmot room. So no floor has to outlast a wallet's timings.

**Tests.** Giza's suites pass, adapted as above. `DisappearingLeavesGroupRecordsJvmTest` is new: a
Marmot room's deposit attribution, coin assignment and their line survive a sweep two years on,
under the shortest policy, with every payload released. jvmTest: 3,585 run, 0 failed, 7 skipped.
`compileDebugKotlinAndroid`, `m3Audit`, `marmotAudit`, `magpieLockAudit` and `phraseAudit` pass.

**Not run:** the three-device debug run. In it, a group would be made from a NIP-17 room with a
Lightning wallet, the clock moved past every floor, and then the group would still pay out over
Lightning and re-enrol a stale member.

## Phase 8: group names in braces (D13)

`group-name-prefix` and `group-name-braces`. Giza went `#name` → `&name` → bare name, drawn as
`{name}`, in two migrations. Dedun goes straight from `#name` to the bare name:

- Take Giza's drawing rule (`{name}` for a group). Leave out `{{name}}`, which is for
  confederations, which Dedun does not take (D12).
- One migration, with one spec that strips Dedun's `#`. Giza's v53 and v55 bodies, combined.
- `cc64856c` stores and sends the bare name (D13). A Welcome from an older Dedun or Bennu build
  still carries `#name`. The rule in `85e96902` strips it on the way in.
- Many places in Dedun show a group's name next to money: a payout line, a guarded wallet
  shown as `@name`, the member-wallet guardian name (`member-wallet-guarding-group.md`). Each
  of them draws through the new rule, or keeps the name it had, deliberately. List them in the
  PR.
- The 26 confederation files in `group-name-braces` are not taken.

**Done when** the name tests pass, a Giza-made room and a Dedun-made room show the same way
on both apps, and `m3Audit`'s string budget holds.

**As built.** Giza's 14 commits are in, apart from `0e5fd96f`, which touched only confederation
files and was empty here. Dedun goes from `#name` straight to the bare name:

- **What is stored and sent (D13).** `MarmotGroupName.bare` names a group stored and sent, and
  `display`, or `ChatRoom.title`, draws it as `{name}`. Dedun keeps the convenient group, which
  Giza removed; it is named in `SelectChatRoomTypeViewModel`, not `MarmotGroupCreation`, so it is
  made bare there too. A Welcome from an older build carrying `#name`, or a Giza or Bennu one
  carrying `&name`, is named bare on the way in.
- **One migration, schema 56.** `MarmotGroupBareNameMigrationSpec` renames every Marmot room
  from `#name` to the bare name. Giza's intermediate `&name` spec is not registered: no Dedun
  device ever stores `&`. NIP-17 rooms keep what their people called them, a `#` included.
  `MarmotGroupBareNameMigrationJvmTest` has Dedun's cases.
- **No `{{name}}` (D12).** `ConfederationName`, its test, and the confederation halves of each
  commit are not taken.
- **Group names beside money, listed as the plan asked.** Drawn through the new rule:
  - the onchain wallet's row on the group's screen, and the wallet screen's title;
  - a member wallet's guarding group: on the wallet pages, the guardian lines and the guarded
    list, whether the name comes from the birth certificate (under any old mark) or the room;
  - the member-wallet picker's group choices, and a member wallet's row;
  - the description a Lightning deposit invoice is filled with, which the payer reads.
  
  Left as they were, on purpose: the key ceremony's own lines ("Stokvel has a shared key"),
  which Giza leaves bare too, and the Wavelength runner's log name.

jvmTest: 3,693 run, 0 failed, 7 skipped. `compileDebugKotlinAndroid`, `m3Audit` (string budget
held), `marmotAudit`, `magpieLockAudit` and `phraseAudit` pass. Not checked on devices: that a
Giza-made room and a Dedun-made room look the same on both apps. Both store the bare name and
draw `{name}`, which the tests cover on Dedun's side.

## Phase 9: confederations, not taken (D12)

Dedun does not need confederations, so none of the ~75 commits come in. They are marked
*skip* in the ledger. What Dedun does about them:

- Refuses 30379–30399 inside rooms, out loud (Phase 4). A Giza device in a mixed room may
  still carry a confederation's ceremony or signing traffic through it.
- Does not subscribe to 9430–9438 on relays. These are regular kinds signed by a group key,
  so nothing reaches Dedun unless it asks for them. Add a test that pins the subscription
  filter, so no later change starts asking.
- Leaves out the confederation halves of commits it does take. These are `{{name}}` in
  Phase 8, `ConfederationRitualManager` in `ae0468bb` (Phase 7), and the confederation rows in
  Giza's expiry table.
- Keeps `lightning-kmp-app`'s confederation API (Phase 2b brings it in down the chain) unused.
  Nothing in `composeApp` calls it.

If that changes, it is a plan of its own. It would need the wallet question this plan avoids:
a confederation signs with a FROST key made from its groups' admins, and Dedun's groups also
sign for money with theirs.

**As built.**

- **Refusing the kinds.** Inside rooms, 30379–30399 are refused out loud, as Phase 4 built it
  (`GizaProtocolEvents`, `GizaKindsRefusedJvmTest`).
- **Not asking for them.** `NoConfederationSubscriptionJvmTest` pins that no relay is asked for
  9430–9438. Every filter the live subscriptions build leaves them out: the personal inbox, the
  groups, the group inbox and the group's direct messages. It also checks that no Kotlin source
  in the app names one of those kinds or `ConfederationKinds`.
- **The confederation halves of the commits Dedun took.** They are out: `{{name}}` and
  `ConfederationName` in Phase 8, the confederation ceremony's stamp and retention in Phase 7,
  and its expiry classes.
- **`lightning-kmp-app`'s confederation API** is not even in Dedun's pin. Phase 10 says why.

## Phase 10: keeping up

- **The register in all three trees** (Phase 4). This is the change that stops a third
  collision.
- **The ledger's high-water mark** is `a5a802c5`. The next batch is measured from there.
- **Bennu.** None of this batch is in Bennu. If Bennu ports it later, check its wallet
  adaptations against Phases 2 and 3 here, and offer Dedun's Phase 2 wallet work (the node-id
  vector, the restore test) back by the mirror run in reverse.
- **`lightning-kmp-app`.** After Phase 2, Dedun pins the same commit as Giza.

**As built.**

- **The register in all three trees.** Each register is a commit on a branch of its own, not
  pushed:
  - Bennu: `claude/kinds-register` (`1252fd88`) in the bennu-kmp checkout, from `bennu` at
    `2db6dde5`.
  - Giza: `claude/giza-kinds-register` (`f21c1397`) in this repository, one commit on top of
    `giza/giza` at `a5a802c5`. There is no Giza checkout here; push the branch to Giza's remote.
  
  The two are the same file. It maps every kind in 30300–30449 with a column for each app,
  names Dedun's legacy numbers (a pre-K3 Dedun device still writes 30370–30382), and lists the
  numbers free in all three. Dedun's own `kinds.md` now says 30400–30401 and 30410–30416 are in
  use since Phase 6. Pushing the branches, and merging them, is the owner's call.
- **The ledger's high-water mark** is `a5a802c5`, recorded at the top of the ledger's second
  batch.
- **Bennu.** None of this batch is in Bennu, so there is nothing to check against yet.
- **`lightning-kmp-app`: Dedun still pins its own commit.** Giza's `3ca351af` and Dedun's
  `d4933708` carry the same recovery-code change, on the same toolchain. They differ only in the
  nested `lightning-kmp`. Giza's moves it to `47a0f585`, which adds the bitcoin-kmp
  Confederation API and a newer libsecp256k1-zkp under it. On that pin, the FROST, Iceberg,
  Lightning, share-refresh and ECDH JVM suites pass.
  
  The regtest failure first blamed on that pin is not the pin's. `LiquidityPurchaseRegtestJvmTest`
  races eclair whenever it runs straight after GroupLightning's 145-block burst, and fails that
  way on Dedun's pin too. The ledger's regtest section has the runs and the timeline. Nothing
  measured stands in the way of pinning Giza's commit. Doing so is a decision still to make,
  separate from fixing the test, which needs to wait for eclair to reach the tip before its first
  channel open.

## Checking each phase

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

Phases 1–8 also run `./gradlew check`. Phase 2 changes what starts the personal Lightning
node, and Phase 3 changes what the background starts. So both also run each regtest suite on
a fresh network, bringing the network down and up between suites as the first plan describes:

```bash
testing/regtest/regtest.sh up
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

None of this has been seen on iOS. Phase 2's restore and Phase 3's `BGTaskScheduler` both need
a Mac before they ship.
