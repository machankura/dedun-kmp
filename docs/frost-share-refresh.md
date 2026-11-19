# FROST share refresh and re-enrollment

The goal: give every member of a group a new share of the **same** threshold key, so the group
signs as it always has (same key, same room, same certificates) while the shares it held before
stop being useful. Those old shares may be a lost phone's, a removed member's, or a database a
backup service still holds.

This is a phased plan, being built. It was written against `d4dd87f2` on 2026-11-19,
right after [submodule-refresh.md](./submodule-refresh.md) carried reduced (v2) FROST enrollment
up to Giza. Read [shared-key-ceremony.md](./shared-key-ceremony.md) and
[shared-key-derivation.md](./shared-key-derivation.md) first. Everything below assumes the
ceremony they describe and the rule that a room's id *is* the key it signs with.

## Where it stands

| phase | commit | checked |
|---|---|---|
| 1 | zkp `8477b7c1`, `a7c54598`, `e30c8a16`, `31ea015c` | `tests -t=frost_refresh` (vectors, roundtrip n 2..5, withheld, sequential, recovery, faults, binding, params, lengths); full `tests` suite; `ctime_tests` under valgrind (RelWithDebInfo) clean; autotools build. **Not reviewed** (D4) |
| 2 | secp256k1-kmp `7fd0d34`, `8beb781`, `0f5a710` | `:tests:jvmTest` and `:tests:linuxX64Test` 112/112, the zkp vector byte for byte through both backends |
| 3 | bitcoin-kmp `d2e15b8`, `3cd82d1` | `jvmTest` 274/274, including withheld → repaired with `FrostReducedEnrollment` → signs |
| 4 | lightning-kmp `2043f254`, lightning-kmp-app `3794eb8`, Giza Phase 4 | lightning-kmp `crypto` tests 104/104; Giza `compileDebugKotlinAndroid`, `jvmTest` 1183/1183, audits |
| 7 | `f8974aec` | `ShareRefreshRitualJvmTest` (7: three devices with real host keys refresh through the real zkp module and then sign at generation 1 as the same room; nothing published before approval; a withheld member follows to the new table with no share and declines to sign; a minority refused; one confirmation per generation; a second proposal hands back the first; a redelivered transcript changes nothing); `jvmTest` 1190/1190; `compileDebugKotlinAndroid`; audits |
| 8 | `51615992` | three more in `ShareRefreshRitualJvmTest`: once the group signs the generation-1 key state every device deletes the ceremony's share and its randomness and still signs; a gen-0 session left open fails with "This share was rotated before the session finished"; a key state at an older generation loses whatever its timestamp. `jvmTest` 1193/1193; `compileDebugKotlinAndroid`; audits |
| 9 | `c4fe4c9c` | two more in `ShareRefreshRitualJvmTest`: rotating without a removed member of a 2-of-3 leaves the other two signing at generation 1 and the removed member's old share signing nothing with a new one; a 2-of-2 is refused with "fewer than the 2 it takes to sign". `jvmTest` 1195/1195; `compileDebugKotlinAndroid`; audits. The dialog has not been seen on a device |
| 10 | Phase 10 | three more in `ShareRefreshRitualJvmTest`: 10a, a stale member repaired by two helpers into generation 1 and signing with one of them; a repair checked against the wrong public share fails at round 2 and stores nothing, and nobody marks the member live without a proof; 10b, a new identity's request waits with nothing sent until the group signs the binding, then is repaired and holds the id's share. `jvmTest` 1198/1198; `compileDebugKotlinAndroid`; audits |
| 5 | `4040ae74` | `SecureDeleteJvmTest` (5, two of them controls that fail without the fix); `jvmTest` 1176/1176; `compileDebugKotlinAndroid`, `processDebugResources` |
| 6 | `fce51e20` | `ShareGenerationsJvmTest` (7: a real 2-of-3 signature with hand-refreshed shares verifying as the unchanged room, a decline from a device without the generation, a withheld member who is not a signer); every existing signing test unedited; `jvmTest` 1183/1183; `compileDebugKotlinAndroid`; the M3, brand and wallet audits |

**Nothing below Giza is pushed.** Each level's work is on a local `claude/frost-share-refresh`
branch (zkp, secp256k1-kmp, bitcoin-kmp, lightning-kmp, lightning-kmp-app), and every parent pins
its child's local commit. A clone of Giza at these commits cannot fetch them until the branches are
pushed bottom up, which waits on an explicit go-ahead. The tracked branches (`main`, `threshold`,
`master`) are untouched.

Where the build chose differently from the plan:

| what the plan said | what it turned out to be |
|---|---|
| Phase 5: a test overwrites a share and finds no copy | three. An overwrite in this schema happens to write the new cell over the old share, so it passed with `secure_delete` off too and proved nothing; a deleted row is the control that fails without it. And `secure_delete` alone leaves the share in `-wal` until a truncating checkpoint, so `DedunDatabase.scrubFreedPages` is part of the fix, with its own control |
| Phase 1: the module's C API | adds `secp256k1_frost_refresh_params_parse`, so the bindings carry the parameters as their serialization and parse them in every call, holding nothing between calls. Faults reuse `secp256k1_chilldkg_fault`, with the fault index a position in the recipient list; bitcoin-kmp's `FrostRefreshFault` translates it to an identifier. The round-1 zero-randomness check was variable time, which `ctime_tests` found; it is `secp256k1_is_zero_array` now |
| Phase 1: an investigation procedure | none. A dealt value that does not match the commitments is `UNKNOWN_FAULTY_PARTICIPANT_OR_COORDINATOR` and the session must be abandoned; frost_refresh.md names it as future work |
| Phase 6: a manual `MIGRATION_37_38` backfills generation 0 into the new tables | no backfill, and no manual migration. Generation 0 stays where the ceremony wrote it, read by `ShareGenerations` with ids derived from the host keys exactly as `signerIds` derived them; rows exist from generation 1 on. A copy of generation 0 would have been a second source of truth for the one fact signing most needs to agree with the ceremony about, and `ShareGenerationsJvmTest` asserts the derivation instead of a copy. The migration is a Room `AutoMigration` |
| Phase 6: a session at a generation this device lacks declines | so does one whose `frost_generation` is present and unreadable, filed at generation -1 so nothing reads it as a share this device holds. A decline's reason is `FrostSigningManager.GENERATION_REFUSAL` |
| Phase 6: (not in the plan) | `DkgSessionDao.getKeyHoldingSessions` now counts a share of any generation, and `completedKey` asks `ShareGenerations.canSign`, so a refreshed key whose ceremony share is wiped is still found -- and a member withheld from the newest refresh is offered nothing to sign. `ShippedProfileUpgradeJvmTest` pins the built version at 38 |
| Phase 7: the refusals | one more: every participant not retired has to be a recipient or withheld, so a proposal cannot leave someone in neither list. A participant stale at the base generation is withheld again, as stale, automatically: a refresh brings nobody back, a repair does. A recipient that refuses a proposal on arrival broadcasts a failure, since the certificate needs it; anyone else refuses silently. D5's capability check waits for Phase 13 |
| Phase 7: a kill between storing the pending generation and sending the confirmation | handled the way the plan said -- `confirmAndStore` is idempotent for the refresh that holds the claim, so the next pass stores it again and sends -- but not tested on its own |
| Phase 7: the transcript | system lines through `RitualNotice`, settled. The request line opens nothing until the refresh screen exists (Phase 11) |
| Phase 8: any recipient proposes the new key state | the coordinator does, automatically, the moment its refresh completes; anyone can again. That races the certificate to the other devices, and lost in the first test run: a device still holding the generation as pending declined. A proposal at a generation this device has confirmed and is waiting on the certificate for now waits instead, and the refresh resumes it on activation (`FrostSigningManager.resumeAt`) |
| Phase 8: `ShareRefreshCutoverJvmTest` | the cutover tests live in `ShareRefreshRitualJvmTest`, which has the multi-device harness. The bytes-on-disk check is `SecureDeleteJvmTest`'s, against a file; these devices are in memory. A device that disagrees with the group's table keeps its share and logs it, untested |
| Phase 8: gen-0's randomness nulled | emptied: `round1Random` and `round2AuxRandom` are not nullable, and a complete ceremony never reads them again |
| Phase 9: an action on the members screen | a dialog the room detail screen raises after a removal succeeds, when the member held a live share (asked before the removal, while they are still in the roster). "Not now" leaves it; nothing offers it again yet -- Phase 11's Shares section is where a rotation can be started at any time. `MemberRemovalRefreshJvmTest` is two tests in the ritual harness |
| Phase 10: pairwise channels -- a gift wrap to one npub, or the MIP-03 carve-out | NIP-44 between the two ChillDKG host keys, as room-wide inner events. Only the two endpoints hold the conversation key, so the room can carry the message without reading it, and the keys are the ones the refresh already encrypts to -- including a new identity's, which it names in its request |
| Phase 10: other devices learn a repair succeeded | the target proves it: a Schnorr signature by the repaired share over the session id, checked against the group-signed public share before any device marks the id live (kind 30349, `DONE`). Kinds 30346-30349 |
| Phase 10b: a `frost_member` tag | `id:npub:hostkey`, kept on `GroupKeyState.memberBindings` (schema 40) and applied to the roster of that generation when the group's signature is filed -- only to an id that is not live. A key state at a generation already on file is stamped at least a second after it: the first 10b test run lost a binding to a tie with the state it amended, in the same second |
| Phase 10b: the newcomer's device | seeded in the test with the key's public record, as the member chronicle would hand it over; that hand-over is not built for share generations yet |
| Phase 5: `dataExtractionRules` or `allowBackup="false"` | both. From Android 12 `allowBackup="false"` stops cloud backup but not a device-to-device transfer |

## The headline: enrollment cannot rotate a share

The word in the request is *re-enrollment*, and the enrollment module that just arrived looks
like the tool for it. It is not, and the reason is what the rest of the plan rests on.

A `t`-of-`n` FROST key is a polynomial `f` of degree `t−1` with `f(0) = x`, the group secret.
Member `j` holds `s_j = f(x_j)`. Any `t` points on `f` reconstruct `x`.

**Enrollment, v1 and v2, and in both its modes, evaluates the *same* `f` somewhere else.**

- `newId == n` gives a new member `f(x_n)`.
- `newId < n` ("repair") gives back exactly `f(x_k)`.

`FrostEnrollment`'s own KDoc says it: "every existing share [is] unchanged by an enrollment". So
re-enrolling every member, even at fresh identifiers, hands out new points on the old polynomial.
Every old share stays a valid point on it. A removed member's share plus `t−1` new ones still
signs, and a leaked backup is exactly as dangerous as before.

**Rotation needs a different `f`.** That is a proactive share refresh (Herzberg et al., 1995):

1. Every dealer `i` picks a random polynomial `δ_i` with `δ_i(0) = 0`.
2. Each member adds the sum to their share: `s_j' = s_j + Σ_i δ_i(x_j)`.
3. The new shares lie on `f' = f + Σ δ_i`. Because every `δ_i(0) = 0`, `f'(0) = x`, so the key
   does not move.
4. A mix of old and new shares interpolates to garbage. Only `t` *old* shares together still
   reconstruct `x`, which is why "rotating away" also needs the old ones deleted (see
   [What it protects against](#what-a-refresh-protects-against-and-what-it-cannot)).

Neither secp256k1-zkp, secp256k1-kmp nor bitcoin-kmp has this today: the grep is for `refresh`
and `reshare` across `Secp256k1.kt` and the `frost`/`chill` wrappers. Building it is the first
half of this plan.

Enrollment still has a job here, and it is the half that earns the word *re-enrollment*:

- **Refresh** rotates the shares of the members who take part.
- **Repair** (v2 `FrostReducedEnrollment`, `newId < n`) brings in a member who could not take
  part: offline, or on a new device. It gives them their point on the **new** polynomial, which
  their old device never had.

A member who was withheld from a refresh and is never repaired is, cryptographically, removed.
So rotation, removal and recovery from a lost device are one protocol plus one repair.

## Vocabulary

| term | meaning |
|---|---|
| **key** | a ChillDKG output: threshold public key `X`, identified by its `dkgSessionId`, which is also the `frost_key` tag. Never changes |
| **participant id** | a member's index in the ChillDKG order (the sorted host keys; `FrostSigningManager.signerIds`). Fixed for the life of the key; `n` fixed until an enrollment grows it |
| **generation** `g` | which polynomial the shares are on. `0` is ChillDKG's output; each completed refresh adds one |
| **standing** | per id, per generation: `live` (holds a share of `g`), `stale` (withheld, awaiting repair) or `retired` (withheld for good; never repaired) |
| **recipients** `R` | the ids a refresh delivers to. Every recipient is also a dealer |
| **withheld** `W` | the live ids a refresh delivers nothing to. They come out `stale` or `retired`, as the proposal says |

## What a refresh protects against, and what it cannot

This section matters most for the UI copy, because the action sounds stronger than it is.

**It protects against** an attacker who collects fewer than `t` shares of any one generation:

- a stolen or lost phone's database;
- `aux.db` in a cloud backup;
- a removed member's share, once the others have deleted theirs.

After a refresh the attacker needs `t` shares of the *new* generation, and what they hold is of
the old one.

**It cannot protect against:**

- **Honest deletion failing.** Two members who keep their generation-`g` shares after a refresh
  still hold two points on `f`. If `t` old shares ever meet, the key is gone, refresh or not. The
  plan wipes old shares (Phase 8), but a wipe on one device proves nothing to the others.
- **A compromised nsec.** The ChillDKG host key is `sha256("mantra/chilldkg/host-key/v1" ||
  nostrPriv)`. ChillDKG's `participantRecover(hostSecretKey, recovery)` rebuilds the
  generation-0 share from it and the public transcript. So **every member's generation-0 share
  can always be rebuilt from their nsec**, whatever is wiped. A refresh moves the group past a
  leaked *share*, never past a leaked *identity*. A member whose nsec is compromised has to be
  withheld and repaired under a new identity (Phase 10b), not refreshed.
- **An adversary already holding `t` shares of the current generation.** They have the key. A
  refresh is not a recovery from that; only a new ceremony is, and it gives a new key and a new
  room.
- **The threshold.** A refresh keeps `t` and `n`. Changing `t` is a reshare, which is out of scope
  ([what this does not do](#what-this-does-not-do)).

Two things in the tree today undercut even the protections listed above, and they are
preconditions for the rest (Phase 5):

- `DkgSession.secretShare` is **plaintext hex** in `aux.db`, under an "encrypted local storage"
  assumption (`DkgSession.kt`) that the database does not meet: `BundledSQLiteDriver`, no
  SQLCipher, no keystore. `round1Random` and `round2AuxRandom` sit beside it and rebuild the
  share on their own.
- `AndroidManifest.xml` has `android:allowBackup="true"` with no `dataExtractionRules`, so
  `aux.db` goes to the platform backup. **A share that has been backed up cannot be rotated away
  from by anything this app does afterwards**, short of every other member deleting theirs.

## Decisions this plan asks for

Each has a recommendation. Decide them before Phase 1; several change the wire.

**Decided 2026-11-19: all eight as recommended.** Execution began the same day. Part B (Phases 5–6)
runs first because it needs nothing from Part A; Part A's pushes wait on an explicit go-ahead,
since a parent may only pin what the child's remote has.

| # | question | recommendation | if decided otherwise |
|---|---|---|---|
| D1 | Where does the refresh primitive live? | **In secp256k1-zkp, as a `frost_refresh` module beside `frost_enrollment`**, with bindings up the chain the way enrollment came. It shares the frost module's identifier-to-evaluation-point convention, gets Python vectors, and goes through the same review | In bitcoin-kmp, as Kotlin over `PrivateKey`/`PublicKey` arithmetic (each op calls libsecp256k1). Phases 1–2 collapse into Phase 3 and the chain bump shrinks. The cost: protocol code with no reference implementation and no vectors, in a place no cryptographer reviews |
| D2 | Who has to take part? | **Every recipient.** A refresh cannot finish until all of `R` confirms, the same rule ChillDKG has. Anyone not taking part is withheld and repaired afterwards | Letting a refresh finish without some recipients means a member who later finds their delta inconsistent has no one to complain to. Repair already covers the absent member |
| D3 | How are two concurrent refreshes kept from forking the group? | **Two rules together.** (a) A device confirms at most one refresh per (key, base generation), write-once, like the nonce rules on `FrostSigningSession`. (b) `|R|` must exceed half the ids not yet retired, and must be at least `t`. Any two recipient sets then overlap, so at most one certificate can exist | Without (b), a 2-of-5 group could finish two disjoint refreshes, each with its own generation `g+1`, and sign with neither |
| D4 | Ship gate | **Behind a build flag, off in release, until upstream drops "experimental" from `frost_enrollment` and `frost_refresh` has had independent review.** submodule-refresh.md Phase 6 already says this for enrollment | Shipping earlier means accepting unreviewed key-handling code on the group's only key |
| D5 | Groups with Bennu members | **Refuse a refresh whose recipients include a member whose client has not advertised support.** A Bennu peer cannot take part, so the refresh would withhold them, which removes them from signing | Port the protocol to Bennu first (Giza → Bennu is a cherry-pick, the reverse of [giza-rebrand.md](./giza-rebrand.md) Phase 9) |
| D6 | When is the old share wiped? | **Once the group has signed the generation-`g+1` key state (30326).** That signature is the proof that `t` members can sign with the new shares. Before it, a broken refresh would otherwise leave nothing to sign with | Wiping at the certificate is earlier, but trusts that the new shares work without a signature showing it |
| D7 | Where does a refresh run? | **In the Marmot room whose id the key derives**, p-tagging the recipients, as a subgroup's ceremony does in its parent. A key with no room yet cannot be refreshed. Its holders can just run the ceremony again | The NIP-17 room the ceremony ran in. That room has no record of removals, which is what a refresh is most often for |
| D8 | Does removing a share holder from the room offer a refresh? | **Yes, as a separate, named step after the MLS removal, never folded into it.** Removal is one member's decision; a refresh needs every recipient | Silent: the removed member keeps a working share indefinitely, which is today's behaviour (`MarmotOutboundDao.removeMember` touches MLS only) |

## The protocol

This is the shape the primitive should have, written so Phase 1 has a spec to start from and
Phases 7–8 have something to persist. It is EncPedPop and CertEq from ChillDKG with the constant
term removed. Reusing ChillDKG's structure means reusing its review, its blame and its recovery
story.

### Inputs everyone already holds

- The threshold key `X`, participant count `n` and threshold `t`.
- The host public keys in ChillDKG order.
- The generation-`g` public-share table `P_0 … P_{n−1}`.
- The recipient set `R`, which the proposal names and which sorts to a canonical order.
- A `refreshId`: 32 fresh bytes chosen by the proposer, bound into every hash the way v2
  enrollment binds `sessionId`.

**Session parameters hash.** `H(tag ‖ X ‖ n ‖ t ‖ g ‖ hostkeys ‖ R ‖ P-table ‖ refreshId)`.
Every message carries it. As with ChillDKG's ordering, a device that disagrees on any input gets
no refresh rather than a weaker one.

### Round 1: each recipient deals

Each recipient `i` in `R` does the following:

1. Draws `δ_i(x) = a_{i,1}x + … + a_{i,t−1}x^{t−1}`. There is no constant term and so no proof of
   possession: there is no secret contribution to prove.
2. Publishes the commitments `A_{i,m} = a_{i,m}·G` for `m = 1…t−1`: `t−1` points.
3. For every `j` in `R`, encrypts `δ_i(x_j)` to `j`'s host key, with a pad derived from ECDH and
   bound to the parameters hash, `i` and `j`. This is EncPedPop's construction.

**Nothing is encrypted to a withheld id.** That is what withholding means. A withheld member
never learns their point on `f'`.

### Coordinator: aggregate

The coordinator sums the commitments, `A_m = Σ_i A_{i,m}`, and packages each recipient's
ciphertexts. It is untrusted, as in ChillDKG: it relays and sums, and can neither learn a delta
nor bias one.

### Round 2: each recipient checks, persists, confirms

1. Decrypt and sum: `d_j = Σ_i δ_i(x_j)`.
2. Feldman check: `d_j·G == Σ_m A_m · x_j^m`. On failure, blame by investigation, as ChillDKG
   does.
3. Compute the new share `s_j' = s_j + d_j`.
4. Compute **every** id's new public share, including withheld ones:
   `P_k' = P_k + Σ_m A_m · x_k^m`. This needs only public data.
5. Check `s_j'·G == P_j'` and `frostThresholdInfoValidate(X, P', t)`.
6. **Persist generation `g+1` as `pending`.**
7. Only then sign CertEq over the transcript with the host key. A confirmation means "I have
   stored it", so a certificate means everyone has.

### Certificate

The coordinator collects every recipient's CertEq signature into a certificate. A device that
verifies it moves `g+1` from `pending` to `active`.

**Recovery data** for generation `g+1` is the round-1 transcript (commitments and ciphertexts)
plus the certificate: everything public. With the host key it rebuilds `d_j`; on top of
generation `g` it rebuilds `s_j'`. So the chain ChillDKG recovery → refresh 1 → … → refresh `g`
restores any generation from the nsec. That is the same property generation 0 has today, and
the same limit.

### Repair of a withheld id (re-enrollment)

Repair uses v2 `FrostReducedEnrollment` with `newId = k < n` and helpers drawn from the live ids
of the current generation (`t ≤ u`).

The expected public share is `P_k'` from the **group-signed** generation table (Phase 8): never
from the helpers, as submodule-refresh.md Phase 6 requires.

v2's messages are raw scalars, so they need a confidential, authenticated channel per pair. That
is a gift wrap to one npub in a NIP-17 room, and the MIP-03 one-to-one carve-out in a Marmot room
([marmot-direct-messages.md](./marmot-direct-messages.md)). **Never an MLS application message:
every member of the room can read those.**

## Phases

Four parts.

- **A** (1–4) is library work, below Giza. It follows [submodule-refresh.md](./submodule-refresh.md)
  procedurally.
- **B** (5–6) makes Giza ready for a share to change, and changes no behaviour.
- **C** (7–8) is the refresh itself.
- **D** (9–13) is what makes it usable.

One commit per phase, as the other plans do.

---

### Part A: the primitive

#### Phase 1: `frost_refresh` in secp256k1-zkp

On a branch of `frost/secp256k1-zkp` `main`, beside `frost_enrollment`.

- The spec text first, in the module's README, covering everything in [the protocol](#the-protocol):
  - the parameters hash;
  - round 1 (dealer);
  - coordinator aggregate;
  - round 2 (recipient verify and new share);
  - public-share table update;
  - CertEq;
  - recovery;
  - participant and coordinator investigate.
- A Python reference in the style of the enrollment generator, emitting frozen vectors.
- C functions, roughly mirroring `chilldkg_*`:
  - `frost_refresh_params_hash`
  - `frost_refresh_participant_step1`
  - `frost_refresh_coordinator_step1`
  - `frost_refresh_participant_step2`, which returns the new secret share, the new public-share
    table and the CertEq signature
  - `frost_refresh_coordinator_finalize`
  - `frost_refresh_participant_finalize`
  - `frost_refresh_participant_recover`
  - `frost_refresh_*_investigate`
- Tests, each one a property worth its own name:
  - refresh then sign: the signature verifies under the unchanged `X`;
  - `t` new shares sign, and a mix of old and new does not (with overwhelming probability);
  - `t` old shares still reconstruct. This is the honest-deletion caveat as a test, so nobody
    mistakes it for a bug later;
  - `P_k'` for a withheld `k` equals what a repair at `k` produces;
  - every malformed and inconsistent input lands on the right blame;
  - recovery from host key and transcript equals the share computed live;
  - two consecutive refreshes compose.

**Done when** the vectors, the C tests and the Python reference agree, and the module carries
the same "experimental" warning enrollment does. **Not done** until somebody other than its
author has reviewed it (D4). That does not block Phases 2–9, only release.

#### Phase 2: secp256k1-kmp bindings

On `claude/frost-share-refresh` from `threshold`.

1. Bump zkp to the Phase 1 commit.
2. Add JNI glue, iOS cinterop declarations, and `Secp256k1` functions that return result types
   carrying `ChilldkgFault`-style blame.
3. Rebuild the natives from scratch (`native/build/`). A stale `libsecp256k1` shows up as a
   missing `secp256k1_frost_refresh_*` symbol at load time.
4. Add JVM tests over the vectors.
5. Run `connectedAndroidTest` if an emulator is to hand. The JNI marshalling is where a mistake
   hides.

**Done when** `:tests:jvmTest` passes with natives built from the Phase 1 commit.

#### Phase 3: bitcoin-kmp `FrostRefresh`

`crypto/frost/FrostRefresh.kt`, an object in the shape of `ChillDKG.kt`:

- opaque `RefreshParticipantState1/2` and `RefreshCoordinatorState`;
- results that carry a fault rather than throw;
- a `RefreshResult(secretShare, publicShares, recovery)`.

Rules carried over from `FrostReducedEnrollment`:

- **Messages keyed by participant id, never by position.** A missing or extra sender is
  `Either.Left`, a caller error, never a participant at fault.
- **Refresh and enrollment are separate objects with separate message types.** Feeding one into
  the other is a type error.

Tests in `FrostRefreshTestsCommon` cover each Phase 1 property through the wrapper, plus the one
that matters most for Giza:

- **refresh with `k` withheld, then a `FrostReducedEnrollment` repair at `k`, then sign with `k`
  in the signer set.** This is the whole of re-enrollment, end to end, below the app.

**Done when** those pass and `FrostEnrollmentTestsCommon` and `FrostReducedEnrollmentTestsCommon`
are unchanged.

#### Phase 4: carry it up the chain

Follow [submodule-refresh.md](./submodule-refresh.md) exactly:

- a worktree with its own submodule checkout;
- a branch per level;
- bitcoin-kmp → lightning-kmp → lightning-kmp-app → Giza;
- pushes bottom up;
- fast-forward the tracked branches only once Giza is green.

If [giza-wallet-removal.md](./giza-wallet-removal.md) Phase 7b has landed by then, the chain is
three levels and this is one bump.

**Done when** Giza's `compileDebugKotlinAndroid`, `jvmTest` and `check` pass on the bump with no
app change.

---

### Part B: a share that can change

Parts A and B are independent. B can start the day this plan is accepted.

#### Phase 5: preconditions, the share at rest

These are a refresh's preconditions, but each is worth doing on its own; the backup one is a
standing leak.

1. **Keep `aux.db` out of platform backups.**
   - Add `dataExtractionRules` (API 31+) and `fullBackupContent` (below 31) that exclude the
     database directory, or set `allowBackup="false"`.
   - The identity is already restored from the recovery phrase, and the shares have their own
     recovery path (Phase 12), so nothing is lost but a copy of secrets.
2. **Secure delete.** Open the database with `PRAGMA secure_delete = ON` so an overwritten share
   is zeroed on disk, not left in a free page.
   - A one-time `VACUUM` after the first wipe (Phase 8) clears what earlier writes left behind.
   - Not a substitute for encryption at rest, but without it a wipe is a promise the file does
     not keep.
3. **Name encryption at rest as the open item it is.** It already has `TODO(security)` notes in
   `RoomMlsGroupStateStore` and `DatabaseMarmotRepository`. This plan does not do it, and the UI
   copy (Phase 11) must not claim it.

**Done when** an Android backup taken with `adb backup` / `bmgr` holds no `aux.db`, and a test
writes a share, overwrites it, and finds no copy of the old bytes in the database file.

#### Phase 6: schema 37 → 38, and generations in signing

**The schema.** Two new tables, plus generation columns on the signing session and the key state.

| table / column | holds |
|---|---|
| `KeyShareGeneration` PK (`dkgSessionId`, `generation`) | `secretShare` (nullable: null once wiped, or while `stale`), `publicShares` CSV in id order, `state` (`PENDING`, `ACTIVE`, `RETIRING`, `WIPED`), `refreshSessionId` (null for 0), `recoveryData`, `activatedAt`, `wipedAt` |
| `KeyShareMember` PK (`dkgSessionId`, `generation`, `participantId`) | `memberPublicKey`, `hostPublicKey`, `publicShare`, `standing` (`LIVE`, `STALE`, `RETIRED`) |
| `FrostSigningSession.generation` | nullable `Int`. Null reads as 0, so rows from before the migration mean what they meant |
| `GroupKeyState.generation`, `.publicSharesHash` | nullable. Null is generation 0 |

**The migration.**

- The tables come in through an `AutoMigration`. A manual `MIGRATION_37_38` backfills generation
  0 from every `DkgSession` with a `secretShare`: the share and table come from `DkgSession`, and
  the members come from the `HOST_KEY` rows sorted exactly as `signerIds()` sorts them.
- `DkgSession.secretShare` stays for now and is nulled at the generation-0 wipe.

**Reading through one door.** `FrostSigningManager.advance`, `completedKey` and `signerIds` read
the share, the public shares and the ids through one function:
`ShareGenerations.active(dkgSessionId)`.

**This reverses a decision, so the doc has to say why.** `signerIds` is derived on purpose ("so
signing cannot disagree with the ceremony that made the key"). From generation 1 on, the roster
is a certified fact: the refresh certificate and the group-signed 30326. Generation 0 stays
derived. `ShareGenerationBackfillJvmTest` asserts the backfilled rows equal `signerIds()` and
`publicShareList()` for every fixture in `GroupFixtures`. The day they disagree is the day the
migration is wrong.

**Generation on the wire.**

- A `frost_generation` tag on 30320, alongside `frost_key`. No tag means 0, so proposals from
  older builds still mean what they meant.
- A receiver whose active generation differs **declines out loud**: a 30330 with a reason, as
  `WalletRefusal` does. It never drops the proposal silently, and never signs with a share of
  another generation.
- `SecretNonce.generate` already takes the share, so binding the session to a generation also
  binds its nonce. Write the test anyway: a session opened under `g` whose device has moved to
  `g+1` fails rather than producing a partial signature from the wrong share.

**Done when** `jvmTest` passes with no behaviour change: every existing signing test, unedited
except fixtures, signs at generation 0. A new `FrostSigningGenerationJvmTest` covers the
mismatch decline.

---

### Part C: the refresh

#### Phase 7: `ShareRefreshManager`

A stateless `object` in the image of `ChillDkgRitualManager`:

- persist each inbound message;
- ask whether the ritual can move;
- recompute every step from stored inputs (`round1Random`, `auxRandom` and the received
  messages), so an app killed mid-round resumes on the next message.

**Kinds.** The proposal is 30340–30345. 30338–30349 are unused, and `RetiredKindsTest` already
asserts 30338 and 30349 are not retired. Check Bennu's kinds before fixing the numbers.

| kind | from | carries |
|---|---|---|
| 30340 | proposer (coordinator) | proposal: `frost_key`, `frost_generation` (base `g`), `refresh_id`, `p` per recipient, `withheld` per withheld id with `stale` or `retired` |
| 30341 | recipient | round 1: commitments and ciphertexts |
| 30342 | coordinator | aggregate |
| 30343 | recipient | CertEq signature |
| 30344 | coordinator | certificate |
| 30345 | any recipient | failure: abort and blame, or abandon |

They travel as Marmot inner events in the key's room (D7). The ciphertexts are encrypted to
host keys, so a room-wide message is fine here, which it is **not** for repair (Phase 10).

**Entities.**

- `ShareRefreshSession`: id, `dkgSessionId`, `baseGeneration`, coordinator, recipients, withheld
  and their standings, stage, `round1Random`, `auxRandom`, approvals, the certificate, the
  failure reason.
- `ShareRefreshMessage`, keyed (`sessionId`, `participantPublicKey`, `kind`), which absorbs
  redelivery the way `DkgParticipantMessage` does.

**Refusals at proposal and on arrival.** Each is a value, with a reason the screen can show:

- the key has no room, or this device is not `live` at the base generation;
- `|R| < t`, or `|R|` is not more than half the non-retired ids (D3b);
- a recipient's client has not advertised refresh support (D5);
- the base generation is not this device's active one;
- **this device has already confirmed a refresh from this base generation (D3a).** This rule is
  enforced with a write-once column on `KeyShareGeneration`, set in the same transaction that
  stores the `PENDING` row. **It is as important as the nonce rules, and for the same reason: it
  is the one way the group splits.**
- an open ChillDKG or refresh for the same key.

**Approval gates.** Two, mirroring the ceremony's, with `pendingApproval` mirroring `advance`
exactly:

| step | publishes | why it is its own decision |
|---|---|---|
| `DEAL` | round 1 | joins the refresh and agrees to the withheld list. Withholding someone is a decision about them, so every recipient makes it, not only the proposer |
| `CONFIRM` | CertEq | commits to the new generation. This is what stops a coordinator substituting a table the recipients never dealt |

The proposer is auto-approved for nothing. Proposing a refresh is not agreeing to withhold
whomever the form defaulted to.

**Transcript.** System lines in the room, as the ceremony writes them, idempotent by reading the
row before writing:

- started (naming who is withheld, and whether as stale or retired);
- each member's contribution;
- complete;
- abandoned.

**Done when** `ShareRefreshRitualTest` (commonTest, real primitive, in-memory multi-device in the
manner of `RobustRoomKeyCeremonyTest`) passes for:

- n 2..5, t 2..n, with 0, 1 and 2 withheld;
- redelivery of every message;
- a kill between "persist `PENDING`" and "send CertEq";
- a faulty dealer blamed by id;
- abandon;
- `ShareRefreshForkTest`: two proposals from one base generation, with the majority rule and the
  write-once rule each shown to be necessary by a test that drops it and gets two certificates.

#### Phase 8: cutover, the signed table, and the wipe

What a device does with a certificate, and when the old share goes.

1. **Certificate verified** → `g+1` becomes `ACTIVE`, `g` becomes `RETIRING`.
   - New signing proposals use `g+1`.
   - Sessions already open under `g` may finish while `g` is still `RETIRING`.
2. **The group signs the new key state.** Any recipient proposes a 30326 for the room. It is
   unchanged in content and `d`, and carries new tags:
   - `frost_generation` = `g+1`;
   - `frost_pubshares` = hash of the new public-share table;
   - `frost_refresh` = the refresh id;
   - `frost_retired` per retired id.

   It is signed under `g+1`, which makes it the first signature the new shares make: the proof
   D6 waits for.
3. **`GroupKeyStateManager` changes, and the comment in `GroupKeyStateEvent.kt` (L84–87) gets
   answered.**
   - The comment expects that rotation would break `verifies()`. It does not: the *key* is
     unchanged, so `marmotGroupId(X, path) == chatRoomId` still holds. What changes is that a
     state now names a generation.
   - `GroupKeyStateDao.replace()` keeps the **highest generation**, then the newest `announcedAt`,
     not the newest `announcedAt` alone. `created_at` is chosen by the author, and an older
     generation's state is still validly signed by the group.
   - `stateFrom` checks `frost_pubshares` against the local `KeyShareGeneration` table. A
     mismatch is a refusal with a reason, because it means this device and the group disagree
     about who holds what.
4. **On seeing that signed state:** wipe `g`.
   - `KeyShareGeneration(g).secretShare` becomes null and the state becomes `WIPED`.
   - Every open session under `g` fails, with "this share was rotated".
   - **At generation 0, also null `DkgSession.secretShare`, `round1Random` and
     `round2AuxRandom`.** The last two rebuild the share on their own, and a wipe that leaves them
     wipes nothing.
   - Then the one-time `VACUUM` from Phase 5.
   - Check first that `ChillDkgRitualManager.advance` returns early for a `COMPLETE` session and
     never reaches for the nulled randomness. If it does not, that is the bug this step will find.

**Done when** `ShareRefreshCutoverJvmTest` covers:

- a refresh followed by signing at `g+1`;
- a signature attempted at `g` after the wipe, which fails out loud;
- the wiped bytes, absent from the database file;
- a late 30326 at generation `g`, which loses to `g+1` whatever its `created_at`;
- a device that missed the certificate, which comes back from the transcript in its own backlog
  replay.

`GroupKeyStateTest` and `SignedGroupKeyStateTest` must pass unedited for generation 0.

---

### Part D: membership, recovery, UI, rollout

#### Phase 9: removing a share holder

`MarmotOutboundDao.removeMember` stays as it is (D8). After it succeeds for a member who is
`live` in the room's key, the members screen offers a second action: "Rotate shares without
them". That opens a refresh proposal withholding their id as `retired`.

The removal and the rotation are two acts, for three reasons:

- the removal is one admin's commit;
- the rotation needs every remaining holder;
- a removal that silently started a refresh would leave the group in a ritual no one chose.

**Refusals.**

- Withholding would leave fewer than `t` non-retired ids. The copy says the group would no longer
  be able to sign at all, and that the way out is a new ceremony.
- The majority rule (D3b) fails. Remove fewer at a time: in a 2-of-5, one at a time always passes.

A retired id is never reused, and `n` does not shrink. FROST identifiers are positions below `n`,
and `frostSessionInit` takes `nParticipants`, so a gap is fine. Renumbering would invalidate
every public share.

**Done when** `MemberRemovalRefreshJvmTest` removes a member from a 2-of-3 room, rotates without
them, and shows that:

- the remaining two sign at `g+1`;
- the removed member's share does not appear in any signer set;
- their share combined with one remaining member's `g+1` share produces no valid signature.

#### Phase 10: re-enrollment

The repair path, for the members a refresh left `stale`. It uses `FrostReducedEnrollment` only,
never v1, as submodule-refresh.md decided.

**10a. Same identity, missed the refresh.** The member was offline or had no build that supported
it.

1. They (the target) propose a repair with a fresh `sessionId`.
2. `t` or more `live` helpers each approve. Approval is a gate, because a helper's round 1.1
   message spends its share's secrecy on the target's behalf.
3. Round 1.1 shares go helper to larger-id helper, and round 1.2 messages go helper to target,
   every one over a pairwise channel (see [the protocol](#repair-of-a-withheld-id-re-enrollment)).
4. The target derives `P_k'` from the group-signed table, runs `generateSecretShare` with it as
   `expectedPublicShare`, and persists `g` as `live`.

Kinds 30346 (request), 30347 (round 1.1) and 30348 (round 1.2).

**10b. New identity: lost device, compromised nsec.**

- The member's id `k` is re-bound to a new npub, and so a new host key, by a 30326 the group
  signs. The state gains a `frost_member` tag per id that has changed hands, and the
  `KeyShareMember` row follows it. Only then can a repair target `k`.
- The order is forced. **A repair to an npub the group has not bound to `k` is a repair to
  whoever asked.**
- Withholding the old identity first, by a refresh, is what makes the old device's share
  worthless. A repair alone would hand the new device the same point the old one has.

**10c. A new member (`newId = n`)** is enrollment proper. It belongs in submodule-refresh.md's
Phase 6 document, not here. This plan leaves it one thing: an enrollment is a 30326 that grows
`n` and the table, at the current generation. The schema above already has room for it.

**Done when** `ReEnrollmentJvmTest` runs 10a and 10b end to end, and shows that:

- the repaired member signs;
- a repair whose helpers present a table that disagrees with the signed one fails at round 2,
  not at signing;
- a 10b repair to an unbound npub is refused before any helper sends anything.

#### Phase 11: UI

Under CLAUDE.md's rules:

- strings in the catalogue, sentence case;
- spacing and colour from the theme;
- `NavigateBackButton` on the pushed screen;
- four states through `ScreenStateTransition`;
- outcomes through `rememberNotifier`.

**What to build.**

- **A "Shares" section on the key's screen.** It shows:
  - the generation;
  - each id's member and standing;
  - when the generation became active;
  - an action to rotate.

  Standing gets a text label, not only an icon, so the icon gets `Decorative`.
- **The refresh screen.** A progress ladder that names who it waits on, like the ceremony's,
  with the two approvals.
  - The `DEAL` prompt lists who is withheld, and as what.
  - The `CONFIRM` prompt is the ceremony's CertEq prompt in different words.
- **The copy has to tell the truth from [what it protects against](#what-a-refresh-protects-against-and-what-it-cannot).**
  - Say "Shares held before today stop working once everyone's device has deleted them."
  - Never write "the removed member can no longer sign" without that clause.
  - Never write anything implying a compromised account is fixed by a rotation.
- **The repair screen**, and a banner on the key's screen for a `stale` member: "You missed a
  share rotation. Ask the group to bring you back in."

**Done when** `./gradlew :composeApp:m3Audit` and `check` pass at their budgets, and the floors
have not moved down.

#### Phase 12: restore

Today nothing calls `ChillDKG.participantRecover`. `DkgSession.recoveryData` is written at the
end of a ceremony and never read. So the line "restorable from that member's wallet backup" in
shared-key-ceremony.md describes a path that is not built. Refresh makes this gap longer, since a
restored device now needs generation 0 *and* every refresh after it. It does not make the gap
wider.

This phase:

- makes the refresh transcripts (round 1 messages and certificate) part of what a room's backlog
  replay keeps;
- adds `ShareGenerations.recover(dkgSessionId, hostSecretKey)`, which walks ChillDKG recovery
  and then each refresh to the active generation;
- tests that it lands on the live share at every generation.

Wiring that into a restore flow belongs with whoever builds the restore flow. Until then, the
honest copy is that a reinstalled device is repaired (10a), not restored.

**Done when** the recovery walk is tested to generation 3 against the shares computed live.

#### Phase 13: rollout

- **The flag (D4).** `BuildConfig`-style, off in release. The key screen shows no rotate action,
  and inbound 30340s are declined out loud with "this build cannot rotate shares".
- **Capability.** The support D5 checks for is a value in the client's key package or profile
  tags, so a proposer can refuse before anyone deals.
- **Compatibility.**
  - An old build sees a 30340 as an unknown kind and does nothing. That is acceptable only
    because D5 has already refused to make it a recipient.
  - An old build that sees a 30320 with `frost_generation` ≥ 1 ignores the tag and signs with
    generation 0. The partial signature then fails verification, and the coordinator blames it.
    That is noisy, not dangerous. D5 keeps it from happening in a group that has refreshed.
- **Checks this tree cannot run.**
  - Android install over the current build, then a refresh across three devices.
  - A desktop member in the same group.
  - iOS on a Mac.
  - The `adb backup` check from Phase 5.

  Say so in the PR until done, as submodule-refresh.md does.

## What this does not do

- **Change the threshold, or add members.** A reshare with a different `t'` deals each member's
  Lagrange-weighted share as a fresh VSS secret. It is a different protocol, with its own
  review. Adding a member is enrollment (10c).
- **Encrypt `aux.db`.** Phase 5 shrinks the exposure; it does not close it.
- **Recover from `t` compromised shares.** A refresh is for before that happens. After it, only a
  new ceremony helps, and it gives a new key, a new room and new certificates.
- **Prove deletion.** Every member's wipe is on their own device, on trust.
- **Rotate subgroup keys from the parent.** A subgroup refreshes its own key in its own room. The
  birth certificate names the child's key, which a refresh does not change, so it stays valid.

## Appendix: considered and rejected

| idea | why not |
|---|---|
| Re-enroll every member at fresh ids, then retire the old ids | Same polynomial. Every old share is still a point on it ([the headline](#the-headline-enrollment-cannot-rotate-a-share)) |
| Run ChillDKG again | A new key: a new room id (`marmotGroupId`), every `GroupSignedEvent` author changes, and subgroup birth certificates and the chronicle no longer verify. That is the right tool after `t` shares leak, and the wrong one for a routine rotation |
| Refresh in Kotlin inside Giza | Protocol code in the app, with no vectors and no reviewer. If not zkp, then bitcoin-kmp (D1), never `composeApp` |
| Let the refresh finish without everyone | A recipient who cannot confirm could hold an inconsistent delta and nobody would know. Repair covers the absent member cleanly (D2) |
| Fold the refresh into `removeMember` | A removal is one admin's commit, and a refresh needs every holder. Coupling them makes a removal hang on the slowest phone (D8) |
| Send repair messages as MLS application messages | Every member of the room can read them. The v2 messages are raw additive shares |

## Summary

| phase | where | what | done when |
|---|---|---|---|
| 1 | secp256k1-zkp | `frost_refresh`: spec, Python vectors, C, tests | vectors agree; review requested |
| 2 | secp256k1-kmp | bindings, natives | `:tests:jvmTest` |
| 3 | bitcoin-kmp | `FrostRefresh` wrapper; refresh → repair → sign test | wrapper tests; enrollment tests unchanged |
| 4 | chain → Giza | bumps, per submodule-refresh.md | Giza `check` with no app change |
| 5 | Giza | `aux.db` out of backups; `secure_delete` | no `aux.db` in a backup; overwritten bytes gone |
| 6 | Giza | schema 38: generations and roster; `frost_generation` on signing | existing tests unedited; mismatch declines |
| 7 | Giza | `ShareRefreshManager`, kinds 30340–30345, gates, fork rules | ritual and fork tests |
| 8 | Giza | cutover, signed 30326 with generation, wipe | cutover test; old share gone from file |
| 9 | Giza | rotate after removal | removed member's share signs nothing |
| 10 | Giza | repair: same identity, new identity | re-enrollment tests |
| 11 | Giza | Shares section, refresh and repair screens | `m3Audit`, `check` |
| 12 | Giza | recovery walk across generations | recovery equals live to `g = 3` |
| 13 | Giza | flag, capability, device checks | flag off in release; device checks named in PR |
