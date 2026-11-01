# Marmot v2: bringing the group layer onto the current profile

quartz 1.15.2 implements the restructured Marmot protocol -- what its own comments
call the *current profile*, against the *legacy* MIP-era profile this app speaks --
and bumping the version gives the app almost none of it. The pipeline that decides
how a group event is read, how a fork is settled, when a commit is published and
where the state is persisted is the app's own, not the library's, and the bump left
every one of those decisions exactly where it was. This is what would have to change
for the app to behave like a v2 client, in the order it can safely change, and the
two places where v2 and this app's own design genuinely disagree.

Read [marmot-membership.md](./marmot-membership.md) and
[shared-key-derivation.md](./shared-key-derivation.md) first. The first describes the
invite race the current pipeline has, which v2's publish rule is the real fix for.
The second explains why a room's id *is* a key -- and that is the thing v2's routing
rule is about to collide with.

**Built**, phases 0-8, one commit each and three follow-ups where a phase's commit
had left a line of its own phase unbuilt; Phase 9 is the rollout and is process
rather than code, and where it stands is noted at its end. The phases are kept as
written because they are the reasoning, and the code reads better against the
argument it came from than against a summary of itself. It is deliberately ordered
so that the phases that buy interoperability with other v2 clients come before the
phases that make the app *conform* -- a member can talk to White Noise before its
forks settle the way White Noise's do. Where the implementation chose differently
the table below says so, and the first of its rows is the one to read before any
of the code:

| what the plan said | what it turned out to be |
|---|---|
| Phase 5: the library's engine *replaces* the app's pipeline -- `MarmotInboundManager` shrinks to the membership helpers, `MlsGroupCache` and the vendored `CommitOrdering` are deleted | **a room's profile picks its pipeline.** A legacy room keeps the app's pipeline exactly as it was, comparator and cache included; a current-profile room goes through `MarmotEngine`, and `NostrDao.indexMarmotGroupEvent` branches on `ChatRoom.marmotProfile()`. The library's processor has no bare-wrap carve-out, so under it every legacy direct message would have stopped decrypting, and the legacy pipeline is frozen anyway: it retires with the profile, not before |
| Phase 1: the accessors, "in a small extension file beside `MlsGroupCache`" | `MlsGroupProfile.kt`, and six of them rather than three, because Phase 4 made the room's id a thing a group has to say about itself: `roomId()` reads the "Room id:" line `MarmotRoomIdentity` puts in the profile's description, then `0x8004`, then the MLS group id; `routingId()` is the address without the first step |
| Phase 2: the kind guard in `mip03Rejection` "goes", and `MarmotMip03CarveOutTest` inverts into the test that it is gone | it stays, for legacy rooms: `mip03Rejection(..., legacyRoom)`. A current-profile room refuses the bare wrap as a conformant peer would; a legacy room admits it, because every build in one still sends it. The test holds both halves |
| Phase 2: the envelope's kind is "the next free number above 30329" | 30330, `MarmotDirectMessage.ENVELOPE_KIND`; `wrap` is unchanged and `envelope` goes around it, `wrapInside` takes it off |
| Phase 2: bring `MAX_PAGE_BYTES` to 48 KiB and re-size `MAX_PAGE_EVENTS` | 48 KiB and 96 -- measured *escaped*. A page's JSON travels as a string inside the payload's `content`, so the bound that matters is `ChronicleEvent.escapedByteSize`, and a dense page that measured 48 KiB raw escaped to 70 KiB |
| Phase 3: `createKeyPackage(signer, lastResort = true)` | not last-resort. The notary republishes a fresh package for whichever profile is left without one when a Welcome consumes a bundle; a last-resort leaf is for a client that cannot |
| Phase 3: run `isValid` and `isCryptographicallyValid` at ingest | the second alone, which runs the first |
| Phase 3: schema 20 -> 21 | 20 -> 23: the two `profile` columns (Phase 3), `MarmotRouting` (Phase 4), `MarmotPublishObligation` and `MarmotOutboundGate` (Phase 6), each an `AutoMigration` |
| Phase 4: the two creation sites call `CurrentProfileGroupFactory.createGroup` directly | `MarmotGroupFactory.create`, one wrapper for both profiles and three sites -- the plan missed `createMlsDirectMessageChatRoom`, which makes the room behind a direct message. On the current profile it mints the routing id, and the description carries the derivation sentence *and* a "Room id: <hex>" line, because a member joining from a Welcome has nothing but the group's own state to learn the room's id from |
| Phase 4: "extend `Outcome.BlockedOn` to a package that cannot be used" | `MarmotKeyPackageProfileException` at the add step, whose message the confirmation screen shows. `BlockedOn` still means missing, because the picker -- `getMarmotKeyPackageForPublicKey(publicKey, roomProfile)` -- refuses the wrong profile before creation ever asks, so there a wrong-profile package *is* a missing one |
| Phase 5: four stores | one, `RoomMlsGroupStateStore`, over the state blob, `MarmotRetainedEpochSecret` and `MarmotRouting`. No `KeyPackageBundleStore` and no snapshot table: the `KeyPackageRotationManager` is constructed because the processor's constructor demands one and is never consulted, and bundles stay the app's rows with the notary's per-profile republish over them. No `MarmotIngestDedupStore`: 1.15.2's processor takes no such store, so there is nothing to mark into |
| Phase 5: `MarmotGroupEvent` gains a `marmotMessageId` column | not added. The processor dedups by message id in memory for the life of the process; the reindex sweep still dedups by event id. The follow-up if a relay ever redelivers under a second id |
| Phase 5: one engine "held by the identity's scope" | `DedunDatabase.marmotEngine`, one per database, restored on first use. The DAOs that need it hold the database and not a view model's scope, and a switch of identity is a restart |
| Phase 5: the timer "beside `LiveSubscriptionManager`"; an outbound row "stays on the queue" while a pass is open | `MarmotEngine.settleLater`, armed by the ingest whenever a pass opens and ticking on the engine's own scope; and `awaitSettled` on the outbound side -- a wait of up to three ticks rather than a refusal, because the outbound queue re-emits on a table change and a pass settling is not one |
| Phase 6: "a terminal rejection resolves `FAILED`" | built in a follow-up, once "terminal" had a definition: a NIP-01 `OK` reason a relay will keep giving (`blocked:`, `invalid:`, `pow:`, `restricted:`) is a new "rejected" status that the launch-time requeue leaves alone, `rate-limited:` and `error:` are "failed" like a timeout, and `duplicate:` is "published". The obligation goes only when every relay in scope has rejected -- `BroadcastStatus`, `MarmotEngine.discardRejected` -- and the room gets a line in the relay's words |
| Phase 6: `gate.satisfyEmptyObligation` for the group that is only its creator | `installState` straight after `stageAddMember`, keyed off the `isOneMemberInitialGroupCreation` the app already computed |
| Phase 7: leaving is `leaveGroup` behind a `LEAVING` gate; an admin commits the proposal "on receipt, or behind a confirmation" | on receipt (`commitStagedProposals` from `ProposalStaged`), and an admin is refused before anything is raised or dropped: the library refuses an admin's SelfRemove, so the app says "hand over admin first", or, for the only admin, "make another member an admin, or disband the group" |
| Phase 7: captions from `MarmotGroupSnapshot` against a `marmotSnapshot` column | from the diff `processGroupMembershipChanges` already made against the participants table, on every device that applies a commit, and without a `TYPE_MEMBER_REMOVED`: the tree does not say whether a member left or was removed, so `TYPE_MEMBER_LEFT` covers both. No snapshot column |
| Phase 7: rename is `stageAppDataUpdate` and "the button is small" | not built. There is no rename for either profile today; the subject on the detail screen is display only |
| Phase 7: a received 1210 or 1009 gets no bubble, "and neither does any other unknown kind" | those two only, in a follow-up. The bubble stays for the rest: the kind:1 arm reaches it on purpose, and it is the only trace an unrecognised payload leaves |
| Phase 8: four scenarios in one test | spread over four: `CurrentProfileInteropJvmTest` (the join, both directions, envelope included), `GroupLifecycleJvmTest.roomWithMember` (the app's room, the stand-in joining, routing id not room id), `ConvergenceForkJvmTest` (the fork) and `PublishBeforeApplyJvmTest` (the restart). The wire-vector test is `MarmotAppPayloadTest` and `MarmotPayloadBoundsTest` in `commonTest` |
| Phase 8: the fork -- "whichever branch won, they agree" | proven for the case the app depends on and pinned for the case it cannot yet have. Two *other* members fork; the app and a second observer are handed both commits in opposite orders and settle on the same state byte for byte. A fork *this device authored* does not: see below |

One thing found on the way that is the library's and not this app's. quartz 1.15.2's
convergence rebuilds each candidate branch by replaying its commits against the
retained parent state, and a member cannot replay a commit it authored --
`processFramedCommit` on its own commit fails with "UpdatePath at common ancestor
carries no ciphertext for us", since a committer holds no key for its own copath.
So a fork this device is half of is scored with its own branch stale, and two
committers *swap* branches rather than agree; every device that only observed
still agrees, which the first fork test holds. The second fork test pins the swap
exactly as observed and says in its last assertion what to do the day it fails:
the state the missing replay would produce is already retained, and using it is
the fix. Until then the practical exposure is small -- a device that never applies
before publishing rarely authors one side of a fork -- and the follow-up in Phase 5
(marking a losing branch's rows withdrawn) waits on the same fix.

Written against the code as it stood at `2bbe1fc9`.

## Where "v2" comes from, and what it asks

The Marmot spec was reorganised from a list of MIPs into four layers --
`foundation/`, `protocol-core/`, `transports/`, `app-components/` -- and quartz
1.15.2 mirrors that under `marmot/foundation`, `marmot/protocolCore`,
`marmot/appComponents` and `marmot/mls/components`. Groups on the old layout are
what the library calls *legacy*; groups on the new one are the *current profile*.
The reference engine (MDK, which White Noise is built on) has already cut over: its
comments, quoted in quartz, say it "refuses to add a member to a legacy group at
all", and it rejects a Welcome into a group that requires neither identity-proof
component. Amethyst, on quartz, still reads both. So the set of clients this app can
reach is decided by which profile its groups and its KeyPackages are on, and today
that is legacy for both.

Nine rules reach this app. Each row says what v2 requires, what the app does now, and
the phase that closes the gap.

| v2 says | the app today | phase |
|---|---|---|
| an application payload is an **unsigned** Nostr-shaped JSON object with exactly six members; a decoder rejects any `sig` member, any unknown member, and any id that does not rehash | a NIP-59 rumor serialised with `"sig":""`; direct messages as a kind:1059 wrap signed by a throwaway key | 2 |
| group state is a dictionary of versioned **components** -- profile `0x8001`, admin policy `0x8003`, routing `0x8004`, retention `0x8005`, lifecycle `0x800c` -- and every member leaf carries an **account identity proof** (`0x8009`) signed by the account's Nostr key | one monolithic `0xF2EE` extension read in seventeen places; a leaf with no proof, which a v2 group refuses at add time | 1 (read), 3 (leaf), 4 (groups) |
| `nostr_group_id` is a delivery address: random, and "MUST NOT be derived from any ... public key" | the room's id, which is a public key derived from the group's threshold key, doubles as the `h` tag | 4 |
| duplicates are recognised by the **Marmot message id** -- SHA-256 over the MLS bytes -- never the Nostr event id, because every relay copy has a different one | event id | 5 |
| competing commits are settled by **convergence**: retained states, a bounded pass, a six-step deterministic comparison; the created_at-then-event-id rule is gone because both are sender-chosen and unauthenticated | that rule, vendored into the app when the library deleted it | 5 |
| a local commit is **published before it is applied**, as a durable obligation that survives restart, and outbound work is held while a group is unsettled | apply, persist, then broadcast; the Welcome waits for a relay OK but the state already moved | 6 |
| leaving is a `SelfRemove` proposal an admin commits; a group can be disbanded; membership captions are **derived** from state, never received | a local `leftGroupAt` flag and a "Left group" chat line | 7 |
| KeyPackages go to the NIP-65 write set; kind:10051 is a legacy hint; a Welcome is matched to a bundle by `KeyPackageRef`, not its `e` tag | `DefaultDMRelayList` and 10051; bundle by `e` tag | 3 |
| no `encoding` tag on any event shape | one on every application kind:445 | 2 |

Two things the bump *did* do are worth knowing before reading on. `MlsGroupState`'s
on-disk blob went from version 2 to version 4 -- 1.15.2 reads both, 1.14.0 refuses
v4 -- so every room touched under the new library is unreadable by a build on the
old one. And `Event.fromJsonOrNull` defaults a missing `sig` to `""`, so the build
already in the field can *read* a canonical v2 payload; it just cannot produce one.
Both facts shape the rollout in Phase 9.

## The constraint

Two independent facts, and the phases are shaped around both.

**The app does not use the library's pipeline.** `MarmotInboundManager`,
`MarmotOutboundDao` and `DatabaseMarmotRepository` drive `MlsGroup` directly, with
Room as the store: their own outer-layer decryption, their own retained-epoch
window (in memory -- the table is written by the outbound path and never read for a
decrypt, so a restart forgets every epoch the inbound path retained), their own
commit conflict handling, their own persistence in `ChatRoom.mlsGroupState`. The
inbound manager is a copy of the library's as it stood in July, when the ingestion
was written -- `DatabaseMarmotRepository` says as much in its header -- and every
fix the library has made since has had to be carried across by hand, or missed.
Everything in the v2 column above lives in
`MlsGroupManager`, `MarmotInboundProcessor`, `MarmotConvergenceEngine` and
`MarmotPublishGate`, and those are built around four store interfaces --
`MlsGroupStateStore`, `KeyPackageBundleStore`, `MarmotIngestDedupStore`,
`MarmotPublishObligationStore` -- the app has never implemented.

So the choice is between adopting the library's managers over Room, or
re-implementing v2 inside the app's pipeline. This plan adopts. The convergence
engine alone is two thousand lines with a spec behind it, and its whole value is
*determinism*: two clients that resolve the same batch must pick the same branch,
and a second implementation that disagrees with the first in any corner forks the
group in exactly the situation convergence exists to prevent. Adopting also holds
live `MlsGroup` instances in the manager, which is what `MlsGroupCache` exists to do
by hand, and makes retained epochs durable for free. The cost is Phase 5, which is
the biggest phase, and the fact that the four stores must be written over tables
that were shaped for the old pipeline.

**The room's id is a key.** `SharedKeyDerivation.marmotGroupId` derives a room's id
from the group's threshold key so that every member can compute it unaided and the
room can sign as itself -- the derivation note calls that "one function because they
are one key", and the chronicle note builds its entire verification story on it.
v2's routing component says the opposite of its `nostr_group_id`: random, never
derived, because a derivable address lets relays link a group's traffic to the
identity behind it. Both are right about their own value. The resolution in Phase 4
is that they are *two* values: the room keeps its derived id as its identity and the
key it signs with, and gets a random routing id as the address its kind:445s carry.
That costs a table and a lookup on every inbound path, and it is the one design
decision in here that cannot be reversed once a v2 room exists.

---

## Phase 0 -- the bump

**Done, in a worktree. Half a day.**

`quartz = "1.15.2"` in the version catalogue, and two compile fixes:

- `CommitOrdering` was deleted from the library. It is vendored, unchanged, as
  [managers/CommitOrdering.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/CommitOrdering.kt)
  with a header saying why the library dropped it. It goes again in Phase 5.
- `GroupEventResult` gained `AppMessageOnCandidateBranch` and `RefusedByLifecycle`,
  and the exhaustive `when` in
  [ChatMessage.fromGroupEventResult](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt:777)
  now returns `null` for both. Neither is a delivery, and until Phase 5 the app never
  constructs either.

Nothing else moved: all 1,128 JVM tests pass, the rewritten Curve25519 arithmetic
matches the RFC 8032 and RFC 7748 vectors, and the app's own KeyPackages sit inside
the new 84-day lifetime ceiling at 30. The one-way door is the state blob: a room
re-persisted under 1.15.2 cannot be opened by 1.14.0 again.

---

## Phase 1 -- read both profiles

**Two days. No wire change.**

After this phase the app can be a member of a current-profile group and can invite
a current-profile peer into one of its own legacy rooms -- which is where interop
with Amethyst users starts, since Amethyst still joins legacy groups. Nothing it
*sends* changes.

### One accessor per field

Seventeen call sites read `currentMarmotData()`, which is the `0xF2EE` extension
and is null on a v2 group. Ten of them read `nostrGroupId`, and the Welcome path in
[NostrDao.kt:701](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt:701)
throws `MarmotMissingNostrGroupDataExtension` when it gets null -- so today a v2
Welcome is not a room the app cannot read but an exception it cannot get past.

The library already reads both profiles: `MlsGroup.currentNostrGroupId()`,
`currentAdminIdentities()`, `currentMemberIdentities()`, `isLeafAdmin()`, and
`currentGroupState()` for the profile, routing, retention and lifecycle components.
Write the fallbacks once, in a small extension file beside `MlsGroupCache`:

```kotlin
fun MlsGroup.roomName(): String? =
    currentGroupState().profile?.name?.ifBlank { null }
        ?: currentMarmotData()?.name?.ifBlank { null }

fun MlsGroup.roomRelays(): List<String> =
    currentGroupState().routing?.relays
        ?: currentMarmotData()?.relays
        ?: emptyList()

fun MlsGroup.retentionSeconds(): Long? =
    currentGroupState().retention?.takeIf { it.isEnabled }?.disappearingMessageSecs?.toLong()
        ?: currentMarmotData()?.disappearingMessageSecs?.toLong()
```

and point the seventeen sites at those, `currentNostrGroupId()` and
`currentAdminIdentities()`. The two that matter most are
[SubgroupManager.kt:123](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/SubgroupManager.kt:123),
which decides who may open a subgroup ceremony, and
[MarmotInboundManager.processGroupMembershipChanges](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotInboundManager.kt:104),
which stamps `adminAt`. On a v2 group both currently see no admins at all, so a
member who is an admin is shown as none, and a subgroup cannot be started.
[MarmotInnerEvent.disappearingExpiration](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MarmotInnerEvent.kt:117)
gets the same treatment; the library's own outbound processor had precisely this
bug -- it read only `0xF2EE` and disappearing messages silently did not disappear in
v2 groups -- and fixed it the same way.

### KeyPackages that arrive framed

A current-profile kind:30443's content is not a bare `KeyPackage` but an
`MLSMessage{KEY_PACKAGE}` around one.
[MarmotOutboundDao.inviteMember](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/MarmotOutboundDao.kt:453)
does `MlsKeyPackage.decodeTls(TlsReader(bytes))` on whatever the ingest stored,
which reads the framing's version and wire-format words as the KeyPackage's own
and fails. `KeyPackageUtils.decodeKeyPackage(bytes)` sniffs the first four bytes
and handles both; use it there.

While in the ingest at
[NostrDao.kt:429](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt:429):
nothing validates a KeyPackage today. Run `KeyPackageUtils.isValid(event)` and
`isCryptographicallyValid(event)` before storing one. The second now checks the
leaf's lifetime and, for a current-profile package, the identity proof -- which is
the check that makes a proof worth carrying at all. A package that fails is not
stored, and the invite screen's "no key package" state already says the right thing
for that.

### Payloads without a signature

In [MarmotInboundManager.processPrivateMessage](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotInboundManager.kt:289)
the author check reads `Event.fromJsonOrNull(innerJson)`. That accepts a canonical
payload by accident -- the deserialiser defaults `sig` -- but it also accepts
payloads the spec says to refuse: duplicate keys, unknown members, an id that does
not rehash. Decode the strict way first and fall back only for the legacy shape:

```kotlin
val author = MarmotAppEvent.decodeOrNull(innerJson)?.pubKey
    ?: Event.fromJsonOrNull(innerJson)?.pubKey
```

The `ApplicationMessage` result keeps carrying the JSON as received; every consumer
downstream goes through `Event.fromJsonOrNull` and copes with either shape. The
fallback is what lets a room full of old builds keep working through Phase 2, and it
is the same fallback the library keeps for its own history.

### The Welcome, by reference

The Welcome's `e` tag names the kind:30443 the inviter *thinks* it used. MDK stamps
the id of the copy it cached, so a peer that rotated its KeyPackage while an
inviter kept inviting from cache cannot be joined by the tag alone. RFC 9420 says a
joiner takes the first `EncryptedGroupSecrets` whose `new_member` ref it holds keys
for, and the library now does exactly that before falling back to the tag. Do the
same in the Welcome branch: decode the Welcome, walk `secrets`, and match
`newMember` against `MlsKeyPackage.decodeTls(bundle.tlsEncodedMarmotKeyPackage).reference()`
over the unconsumed rows in `MarmotKeyPackageBundle`. There are at most a handful of
rows, so this is a scan rather than a column, and no schema moves.

**Tests.** `RumorIdAgreementTest` in `commonTest` grows two cases: a canonical
payload and a legacy rumor with the same members decode to the same id, and a
payload with a `sig` member is refused by the strict decoder and accepted by the
fallback. In `jvmTest`, `MarmotOutboundDaoJvmTest` gains an invite of a
current-profile KeyPackage -- built with
`CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair()))` and
published through `KeyPackageEvent.buildCurrentProfileFrom` -- into a legacy room,
asserting the member lands. That is the Amethyst-user case, end to end.

---

## Phase 2 -- the canonical payload

**Two days.**

### What goes out

In [encryptAndSendMarmotInnerEvent](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/MarmotOutboundDao.kt:735)
the plaintext is `innerEvent.toJson().encodeToByteArray()`, which carries
`"sig":""`. The library's outbound processor is explicit about why that is refused
by a conformant peer rather than merely untidy: a payload with a real signature
would be a valid standalone relay event, so one leaked plaintext could be
republished as a signed public statement by its author. The fix is one line --

```kotlin
val plainTextBytes = MarmotAppEvent.fromEvent(innerEvent).encodeToPayload()
```

-- and the id does not move, because NIP-01 never hashed the signature. Every row
already filed under an inner event id stays valid. Drop `EncodingTag.assemble()`
from the kind:445 template in the same function; `transports/nostr.md` forbids it
on every shape and the library's `GroupEvent.build` no longer adds one.

Old builds read this shape (see the note under the table), and Amethyst reads it,
so the switch can be made everywhere at once. It is the only wire change in this
plan that needs no gating.

### The two limits

A conformant decoder rejects a payload over 64 KiB, deeper than 16 levels, or with
64 or more elements in any one collection -- and the `tags` array is a collection.
The app has payloads near both edges. `ChronicleEvent.MAX_PAGE_BYTES` is 64 KiB of
*content*, so a page plus its envelope is over; bring it under with room to spare
(48 KiB is safe and costs nothing but a few more pages), and re-size
`MAX_PAGE_EVENTS` with it, as the comment on the two caps insists. Any kind that
carries one tag per member -- the contributor lists are the ones to audit -- must
stay under 64 tags, which is a cap to enforce where those events are built, not a
limit to discover from a dropped message. Within this app's own rooms the library's fallback would still read an
oversized payload; an MDK peer drops it without a word. Add a `commonTest` that
builds one of every app kind at its maximum and asserts `MarmotAppEvent.decode`
accepts it.

### Direct messages get an envelope

A kind:1059 wrap is signed -- by a throwaway key, but signed -- and its `pubkey`
is that key, not the sender's. Under v2 it is refused twice over: for the `sig`
member and for the author mismatch. The carve-out in
[mip03Rejection](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotInboundManager.kt:88)
that admits it is exactly what other clients do not have, and the direct-message
note already says those clients drop these as impersonation.

The wrap does not need to *be* the payload; it needs to be *in* one. The new shape
is an unsigned app event whose `pubkey` is the sender's account, whose `kind` is a
new app kind (the next free number in the app's range above 30329), whose single
tag is `["p", recipient]`, and whose `content` is the wrap's JSON. Read the
direct-message note's [Why a throwaway key](./marmot-direct-messages.md#why-a-throwaway-key-and-what-it-costs)
before objecting that the envelope names the sender: the MLS frame already did, and
the property the throwaway key buys -- a wrap lifted out of the group is not
attributable -- survives, because the envelope is unsigned and so is a claim rather
than a proof, and the wrap inside it is still a stock NIP-59 wrap signed by nobody.
`MarmotDirectMessage.wrap` returns the envelope; `classify` and `open` take one;
the seal-to-leaf binding in `classify` stays, since it is the wrap's own
authentication and nothing above it replaces it. The kind guard in `mip03Rejection`
goes, and `MarmotMip03CarveOutTest` inverts into the test that it is gone.

This one *is* gated, because an old build renders the envelope as a raw-JSON
bubble. Legacy rooms keep sending the bare wrap until Phase 9 retires it;
current-profile rooms send the envelope from the day they exist. That is safe by
construction rather than by policy: a current-profile room admits only leaves that
carry an identity proof, and only a Phase 3 build can make one. **The room's
profile is the feature flag**, and it is the same flag for every gated change
after this.

**Tests.** `MarmotDirectMessageTest` round-trips the envelope through
`MarmotAppEvent.decode` and opens the wrap inside; `MarmotDirectMessageDeliveryTest`
asserts the bare wrap is still what a legacy room sends.

---

## Phase 3 -- identity proofs and the current-profile KeyPackage

**Three days. One schema change.**

After this phase White Noise and Amethyst users can add this app's users to their
current-profile groups, and the app can join them.

### What a v2 leaf carries

A current-profile leaf has an `app_data_dictionary` extension holding three
components: the list of component ids it supports, an explicitly empty `safe_aad`
list, and the 104-byte account identity proof -- the account's Nostr key signing a
kind:450 event (never published) that names this leaf's Ed25519 signature key under
this ciphersuite. The proof is why a leaf cannot be forged: MLS never checked that
the account named in the credential agreed to the signature key beside it.

The proof needs a `NostrSigner`, and the order is fixed -- generate the leaf
keypair, have the account sign its public half, then build the leaf around both --
because a proof cannot be attached to a finished leaf. `Identity` yields a
`KeyPair` for every signing kind, so `NostrSignerInternal(KeyPair(privKey))` is the
signer; a `NostrPublic` identity has nothing to sign with and could never publish a
kind:30443 anyway -- the notary returns before signing anything for it, so the
bundle is never attempted, as [the npub sign-in note](./npub-sign-in.md) records.

Replace
[DatabaseMarmotRepository.generateKeyPackage](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseMarmotRepository.kt:164)
and `buildKeyPackageLeafNode` with `CurrentProfileGroupFactory.createKeyPackage(signer, lastResort = true)`,
and the event with `KeyPackageEvent.buildCurrentProfileFrom(bundle.keyPackage, dTagSlot, clientName = "curare")`.
The event's tags change shape -- `mls_extensions` names `0x0006`, `mls_proposals`
names `0x0008` and `0x000a`, an `app_components` tag lists the components, and
there is no `encoding` and no `relays` tag -- and `KeyPackageUtils.isValid` checks
all of it, so the jvmTest below runs the validator a peer runs.

### What the leaf promises

The factory advertises the library's `SUPPORTED_COMPONENTS`: profile, both avatar
carriers, admin policy, routing, retention, the identity proof, encrypted media v2,
lifecycle -- and the agent-text-stream component `0x8006`, together with its
*receive* role capability. The library's comment on that last one is the reason to
copy the list rather than narrow it: the reference client puts `0x8006` into the
required set of every group it creates and refuses any leaf that omits it, so a
narrower list makes this app's users un-addable to every White Noise group.

A component in that list is a promise to *understand* it, not to have a screen for
it. What this obliges the app to is: decode every one of them when reading group
state (Phase 1's accessors do, via `MarmotGroupState`), apply an `AppDataUpdate`
proposal that changes one (the library does, inside `processCommit`), and never
render a payload of a kind it does not know as if it were chat. Avatars, media and
streams are state the app reads and never edits; the appendix says why that is
enough.

### Two slots, for now

`KeyPackageUtils.MAX_SLOTS` is ten. Publish the current-profile package in
`PRIMARY_SLOT` and a legacy package in a second slot for the length of the
transition, because an old build of this app decodes kind:30443 content as a bare
KeyPackage and would otherwise be unable to invite anyone who upgraded. The
current-profile leaf also advertises `0xF2EE`, so a *new* build can be invited into
a legacy room with either package; the second slot exists purely for old inviters
and is retired in Phase 9.

One wrinkle decides the publish order. `MarmotKeyPackageDao` hands an inviter the
peer's *newest* package by `created_at`, and an old build does exactly that and
then fails to decode a framed one. So the legacy package is stamped one second
after the current-profile package, every time both are published, and stays the
newest until the slot is retired. A new build stops choosing by age: in a
current-profile room it takes the peer's current-profile package or reports none,
and in a legacy room it takes whichever decodes.

`MarmotKeyPackageBundle` gains a nullable `profile` column (schema 20 -> 21, an
`AutoMigration`; existing rows are legacy) so rotation republishes the right shape
into the right slot when a bundle is consumed, and `MarmotKeyPackage` gains the
same column, filled at ingest from `KeyPackageEvent.isCurrentProfile()`, so the
picker above is a query rather than a decode.

### Where they go, and where to look

`transports/nostr.md` publishes KeyPackages to the account's NIP-65 write set, and
the library's fetcher looks for a peer's in their NIP-65 write set unioned with the
legacy kind:10051 hint and our own outbox. The app publishes to
`Relays.DefaultDMRelayList` and reads 10051. Union rather than replace, in both
directions: publish to the write set plus the default list, and add kind:10002 to
the per-member sync request in `processGroupMembershipChanges`, which today asks
for kinds 0, 10050, 30443 and 10051.

**Tests.** In `jvmTest`: the built kind:30443 passes `KeyPackageUtils.isValid` and
`isCryptographicallyValid`, and the leaf's proof passes
`AccountIdentityProofV2.isValid` against the credential identity; a group created
by the library's own `CurrentProfileGroupFactory.createGroup` (standing in for
Amethyst) adds the app's KeyPackage and the app processes the resulting Welcome
through the NostrDao path into a room whose id is the routing id from `0x8004` and
whose admin is the creator. That test is the one that fails first whenever the leaf
shape drifts.

---

## Phase 4 -- current-profile rooms, and the routing id

**Four days. One schema change.**

### Splitting the id

A `MarmotRouting` table -- `routingId` (primary key), `chatRoomId` (foreign key to
`ChatRoom`), `activeFromEpoch`, `retiredAtEpoch` (nullable) -- and every room gets
a row. Rooms that already exist, and every room created by another client, get one
whose `routingId` equals `chatRoomId`, so nothing about them moves. A room this
app creates on the current profile gets a fresh `RandomInstance.bytes(32)` as its
routing id and keeps `SharedKeyDerivation.marmotGroupId` as its `ChatRoom.id`.

The lookups that change are the ones keyed by the `h` tag:
[NostrDao.kt:441](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt:441),
where an inbound kind:445's `groupId()` becomes the room via the table;
`getMarmotGroupNostrEventsByChatRoomId`, whose `LIKE` on the tag narrows by the
room's routing ids; the `#h` filter `LiveSubscriptionManager` keeps open, which
lists routing ids rather than room ids; and the outbound templates in
`MarmotOutboundDao`, which put the routing id in `GroupEvent.build`. `ChatRoom.id`
remains the key of every other table, the FROST cache, the chronicle verifier and
the derivation. `retiredAtEpoch` is unused until routing rotation exists; it is in
the schema now because v2 says a member "has to be able to map several routing ids
to one group" and a second migration later would be the same column.

The alternative -- keep the derived id as the routing id and accept the spec's
MUST NOT -- is rejected in the appendix, and not on purity grounds: the app
broadcasts the entries a room's group signs, authored by the room's id, and a
relay that also carries the room's kind:445s under that same id can link the
group's private traffic to its public work with one string comparison.

### Creating one

[MarmotGroupCreation.create](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MarmotGroupCreation.kt:105)
and
[SelectChatRoomTypeViewModel.createMarmotChatRoom](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/SelectChatRoomTypeViewModel.kt:219)
both build a `MarmotGroupData` and hand it to `MlsGroup.create`. Both become

```kotlin
CurrentProfileGroupFactory.createGroup(
    signer = signer,
    nostrGroupId = routingId,
    relays = Relays.DefaultDMRelayList.map { it.url },
    profile = GroupProfileV1(name = groupName, description = description),
    additionalAdmins = adminPublicKeys.filterNot { it == userPublicKey }.map { it.hexToByteArray() },
)
```

which writes epoch 0 with `required_capabilities` naming extension `0x0006` and
proposal `0x0008`, a GroupContext dictionary holding the required-component list,
the admin policy, the routing and the profile, and the creator's leaf with its
proof. The description keeps carrying the derivation path exactly as
[the derivation note records it](./shared-key-derivation.md#recording-the-path);
`GroupProfileV1.description` allows 4096 bytes and byte equality, which is more
than the sentence needs.

No `0xF2EE` goes into a current-profile group. RFC 9420 requires every member to
support every GroupContext extension, and an MDK leaf does not advertise `0xF2EE`,
so carrying both would make the room un-joinable by the clients the profile exists
to reach. A current-profile room is pure, and Phase 1's accessors are what make
that invisible to the rest of the app.

### Which profile a new room gets

One constant, `MarmotProfile.DEFAULT_FOR_NEW_ROOMS`, legacy until Phase 9 flips it,
and read only by the two creation sites. A current-profile room can only ever hold
Phase 3 builds, so flipping the constant is the moment this app's users stop being
able to make rooms for members who have not upgraded -- which is why it is the last
act of the rollout and not part of this phase.

### Refusals at the add step

`MlsGroup.addMember` refuses a leaf whose capabilities do not cover the group's
`required_capabilities`, naming what is missing. In a current-profile room that is
what adding an old build's KeyPackage does: its leaf advertises neither extension
`0x0006` nor proposal `0x0008`. (MDK goes further and also checks the component
list in the leaf's dictionary; quartz checks only the MLS-level requirements, which
is why Phase 3 copies the library's supported list rather than trimming it.)
`MarmotGroupCreation`
already reports `Outcome.BlockedOn` for a peer with no package; extend it to a
package that cannot be used, and let the add-member confirmation screen say
"their key package is from an older Marmot profile" rather than the generic
failure, because the member can fix that and cannot fix a generic failure.

**Tests.** `MarmotGroupCreationJvmTest` gets a current-profile variant: the room's
state decodes to a group whose `currentGroupState().isCurrentProfile` is true, whose
routing id is not the room id, whose admin policy lists the admins; a kind:445
`h`-tagged with the routing id lands in the room; `SubgroupManagerJvmTest` still
derives the child's id from the parent's key. A legacy room created by the same
code with the constant unflipped is byte-for-byte what it makes today.

---

## Phase 5 -- the library's engine over Room

**A week. Two schema changes. The phase everything after depends on.**

### The four stores

- `MlsGroupStateStore` over `ChatRoom.mlsGroupState`, keyed by routing id and
  resolved through `MarmotRouting`, since the manager's map is keyed by the
  `nostr_group_id` a group carries. `saveRetainedEpochs` / `loadRetainedEpochs`
  over `MarmotRetainedEpochSecret`, which is finally read back: today the inbound
  path's `retainedEpochs` is a map in an `object`, and every epoch it retained is
  gone at the next launch.
- `KeyPackageBundleStore` is one opaque snapshot, so it is one row in a new
  `MarmotKeyPackageBundleSnapshot` table. On the first run the existing
  `MarmotKeyPackageBundle` rows are fed in through
  `KeyPackageRotationManager.installBundle` and the old table becomes what the
  key-package screen reads, written back from the manager's state.
- `MarmotIngestDedupStore` over a new `MarmotIngestDecision(eventId)` table. Nothing
  in the library calls it -- it is the app's to mark terminal decisions with: a
  Welcome joined from, a Welcome naming a bundle this device never held. Never an
  event that is merely unreadable right now, which the library's comment on the
  interface is emphatic about.
- `MarmotPublishObligationStore` is Phase 6.

Encryption at rest is unchanged by this: the library's interfaces say MUST, the
app's TODO(security) H1 says the same, and moving the blobs behind an interface
neither fixes nor worsens it. It does make the eventual fix one class.

### One engine per identity

`MlsGroupManager`, `KeyPackageRotationManager`, `MarmotInboundProcessor`,
`MarmotOutboundProcessor` and the `MarmotPublishGate`, constructed together and
held by the identity's scope -- the same place the notary and the sync pumps get
their `KeyPair`. `restoreAll()` and `restoreFromStore()` at startup, then
`trackGroup` for every restored group, because convergence cannot see a fork in a
group whose current state it never retained.

### Inbound

[NostrDao.indexMarmotGroupEvent](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt:1311)
replaces its `MlsGroupCache.withGroup { MarmotInboundManager.processGroupEvent(...) }`
with `engine.inbound.processGroupEvent(groupEvent)`. Everything after the result --
`ChatMessage.fromGroupEventResult`, the FROST and DKG dispatch, membership changes
on `CommitProcessed` -- stays, which is why Phase 0's two `when` branches were
written to return `null` rather than to throw. `MarmotInboundManager` shrinks to
`processGroupMembershipChanges` and the payload helpers; `MlsGroupCache` and the
vendored `CommitOrdering` are deleted.

Two things the app did by hand that the library does differently:

- **Dedup.** The processor recognises a redelivered event by SHA-256 over the MLS
  bytes, computed after the outer layer opens. `MarmotGroupEvent` gains a nullable
  `marmotMessageId` column so the reindex sweep and the transcript can use the same
  identity. The Nostr event id stays the row's key -- it is still what the relay
  gives back.
- **Order.** `reindexMarmotGroupEvents` sorts by the vendored comparator so that
  "the first one replayed is the one every other member also picked". Under
  convergence no replay order decides anything; the retained window and the
  six-step comparison do. The sort goes, and `forgetPendingCommits` becomes
  `resolveConvergence(groupId)` before the replay.

The retained-epoch decrypt path's security note -- a late application message
under a prior epoch is decrypted without verifying the sender's signature --
survives this phase, because the library's `tryDecryptWithRetainedEpoch` has the
same gap. Say so in the new code where the old warning was, rather than letting
the rewrite look like it closed it.

### Driving convergence

Inbound traffic is only an opportunistic tick. After every `processGroupEvent`,
if `openConvergencePasses()` is non-empty, arm a timer that calls
`settleDueConvergence()` at the quiescence window (one second) and keeps calling it
until the map is empty; a group that goes quiet mid-pass has nothing else to drive
it. `LiveSubscriptionManager` already owns a loop that outlives screens, and this
belongs beside it.

A `ConvergenceResolution` with `rewound = true` means the state that was canonical
lost. The first cut logs the outcomes and withdraws nothing, and the plan is
honest about why: a rewind needs two members committing against the same epoch
before either has seen the other's commit, the lines it leaves behind are
membership captions filed under the losing commit, and every payload the losing
branch delivered was still an authenticated member's words. The follow-up, once `MarmotInnerEvent` records the
epoch each message arrived under (the result already carries it), is to mark rows
from a losing branch withdrawn rather than delete them, so a transcript shows what
the group contradicted instead of silently rewriting itself.

### Welcome and outbound

The Welcome branch at NostrDao.kt:701 becomes `engine.inbound.processWelcome(welcomeEvent, hint)`.
`WelcomeResult.Joined` carries the routing id and whether a rotation is due; the
room, its `MarmotRouting` row, its participants and the consumed-bundle bookkeeping
are built from the manager's group as they are today from the local one.
`AlreadyJoined` and a missing bundle are the two decisions to mark in the dedup
store.

In `encryptAndSendMarmotInnerEvent` the middle -- MLS encrypt, outer layer,
template, ephemeral signer -- becomes `engine.outbound.buildAppEvent(routingId, appEvent)`;
persistence and broadcast stay the app's. Before either, read
`convergenceStatus(groupId).allowsOutboundWork`: while a pass is open the row stays
on the outbound queue, which already retries, rather than being encrypted against a
state that may lose.

**Tests.** The DAO tests keep their shape; their `MlsGroup.create` seeds become
`engine.groupManager.adoptGroup`. `MarmotRetainedEpochSecretDaoJvmTest` gains the
case the old code could not pass: a message from the prior epoch decrypts after the
engine is rebuilt over the same database. `MarmotReindexSweepTest` gains a case
that a replay of a duplicate is a `Duplicate` by message id even though every relay
copy has a different event id, and the sort in `reindexMarmotGroupEvents` goes with
the comparator it used.

---

## Phase 6 -- publish before apply

**Three days. One schema change.**

### Why not apply and roll back

The library's gate explains it better than a paraphrase: between applying a local
commit and undoing it on failure there is a window in which this client's canonical
state is an epoch no peer has, and a crash inside the window makes the fork
permanent. So the commit is prepared on a *clone*, the live group never advances,
and the pending state becomes canonical only on a confirmed accept. What counts as
confirmed is at least one acknowledged accept from a relay in the obligation's
scope; queued, sent, or "no error yet" are explicitly not.

### The flow

`MarmotPublishObligationStore` over a new `MarmotPublishObligation(obligationId,
chatRoomId, bytes)` table plus a `MarmotOutboundGate(chatRoomId, gate)` table, the
second because a `Disbanding` or `Leaving` gate must survive a restart.
`MarmotCommitResult` stays as the Welcome half; it already holds the framed commit,
the Welcome bytes and the pre-commit exporter secret.

[inviteMember](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/MarmotOutboundDao.kt:453)
becomes: `stageAddMembers` on the manager (a clone; the live group keeps its
pending proposals) -> `buildCommitEvent(routingId, staged.framedCommitBytes, staged.preCommitExporterSecret)`
-> `gate.prepare(groupId, staged, outboundBytes, recipientScope = relays)`, which is
durable *before* anything is broadcast -> the existing rows -> the existing broadcast
requests. `recordLocalCommit(groupId, staged.framedCommitBytes, sourceEpoch, staged.priorState)`
goes to the inbound processor at the same time, because the gate does not tell
convergence about our own commits and a fork we are half of would otherwise look
like an orphan.

[DatabaseNostrRepository.broadcastProcessed](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseNostrRepository.kt:473)
is already the place a relay OK arrives and already turns it into the deferred
Welcome. It gains `gate.resolve(obligationId, CONFIRMED)` first, which installs the
pending state, and only then delivers the Welcome -- which is the MIP-02 ordering
rule the Welcome sender's comment calls critical, enforced by sequence rather than
by hope. A terminal rejection resolves `FAILED`, discarding the pending state with
the live group already correct. A timeout resolves nothing: the obligation stays
`PENDING_PUBLISH`, and the broadcast queue's requeue re-sends the *same signed
event*, which is the byte-identical retry the rule demands -- a replacement commit
would be a second commit at the same epoch, a fork this client caused itself.

The group that is only its creator keeps its exception. `gate.satisfyEmptyObligation`
is the library's name for what `isOneMemberInitialGroupCreation` decides today, and
its comment draws the boundary in the same place: creation and its founding Add,
never a later commit.

### What it closes

While an obligation is pending, `canPrepareLocalCommit` is false and a second
invite waits in the queue instead of forking. The membership note's batching closed
the race *inside* one invite; this closes it *between* invites, and between an
invite and any other commit -- a rename, a removal -- that lands while the first is
unacknowledged.

**Tests.** In `jvmTest`: an invite whose relay never answers leaves the live group
at its old epoch and an obligation on disk; a new engine over the same database
resumes it and a late OK installs the state; a rejection discards it and the room
can invite again; an OK installs the state *and then* queues the Welcome, which is
the existing test `an invite into an established group is in the room before its
welcome goes out` with the order it asserts made strict.

---

## Phase 7 -- lifecycle: leaving, disbanding, system rows

**Three days.**

### Leaving

[leaveChatRoom](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/repository/DatabaseChatRepository.kt:655)
sets `leftGroupAt` and sends the text "Left group". The group does not learn of
it: the leaf stays in the tree, holding the group's keys. v2 leaving is a
`SelfRemove` proposal published as a `PublicMessage` kind:445, which the app already
*stages* when it receives one from a White Noise member, and which an admin folds
into their next commit. `engine.groupManager.leaveGroup(routingId)` produces the
framed proposal and the exporter key to wrap it with, and drops the local state in
the same call; the gate is raised to `LEAVING` first, which blocks further outbound
from this device, and the leaver's row is marked as it is now. On the admin side,
`ProposalStaged` sets `hasPendingProposals`, and an admin's device commits it
through Phase 6 -- on receipt, as the library's comment expects, or behind a
confirmation on the room detail screen, which is a UI choice and not a protocol
one. The MIP-03 gate in the library bounds what a non-admin may commit to their
own removal or their own key update, on both the sending and the receiving side.

### Removal and disbanding

`stageRemoveMember` from the member sheet on the room detail screen, admins only,
with the library's admin-depletion guard refusing a commit that would leave no
admin in the tree. `stageEnableDisbanding` then `stageDisband` for a group that
has run its course; after that every inbound is `RefusedByLifecycle(DISBANDED)`,
the room renders as closed, and the outbound queue refuses it. Disbanding is
absorbing and there is no rejoin; the UI should say that before the tap.

### Captions derived, never received

The app already synthesises its membership lines --
`processGroupMembershipChanges` diffs the participants table against the tree.
v2 formalises the same idea as kind:1210 rows derived from a `MarmotGroupSnapshot`
of canonical state, and it is explicit that a 1210 which *arrives* over the wire is
one member's assertion, not a fact. Keep the app's `TYPE_MEMBER_*` line types and
change their source: after each applied commit, `MarmotGroupSnapshot.of(state, memberAccounts)`
against the last snapshot (a `marmotSnapshot` text column on `ChatRoom`, which is
what the library's `MarmotMessageStore.recordGroupSnapshot` is for) yields the
rows. A received kind:1210 or kind:1009 gets no bubble, and neither does any other
unknown kind -- which retires the raw-JSON bubble the chronicle note complained
about, for every kind at once.

Rename is `stageAppDataUpdate` with a new `GroupProfileV1`; the room detail screen
already has the subject, so the button is small. Receive-side retention -- pinning a
message's expiry to the retention of the epoch that carried it, and deleting rather
than hiding -- is deferred to the list at the end; the app honours retention on
send today and that does not change.

**Tests.** A leave produces a `SelfRemove` the library's stand-in processor stages;
the admin's commit removes the leaf and the leaver's later kind:445 is refused; a
disband is absorbing across a rebuilt engine; the snapshot diff yields exactly one
`member_added` row per added member and none on a commit that changed nothing.

---

## Phase 8 -- the test that actually proves it

**Two days.**

Two implementations in one group, in `jvmTest`: the app's engine over a
`DedunDatabase`, and a bare library stand-in -- `MlsGroupManager` and
`MarmotInboundProcessor` over the in-memory stores -- playing Amethyst. The
existing subgroup tests already run two databases in one process; this is the same
shape with a foreign member.

1. **Join.** The stand-in creates a current-profile group and invites the app's
   KeyPackage; the app joins from the Welcome, and both sides exchange canonical
   payloads including a direct-message envelope. Assert each side's `MlsGroupState`
   agrees on epoch, tree hash and members.
2. **Invite.** The app creates the room; the stand-in joins; same assertions, plus
   the routing id in the stand-in's `0x8004` is the app's `MarmotRouting` row and
   not its `ChatRoom.id`.
3. **The fork.** Both sides add a different third member at the same epoch and
   each is handed the other's commit. Drive `settleDueConvergence` on both with the
   pass window elapsed. Assert both report `SETTLED`, the same canonical epoch and
   the same member set -- whichever branch won, they agree, and the test does not
   care which.
4. **The restart.** The app prepares an obligation, is torn down, is rebuilt over
   the same database, and receives the OK. Assert the state installed once and the
   Welcome went out after it.

Plus a wire-vector test in `commonTest`: the bytes the app's outbound produces for
each of its kinds decode with `MarmotAppEvent.decode`, and the id equals the one
`RumorAssembler` computed before the switch.

---

## Phase 9 -- rollout

**No code, and three one-way doors.**

The order is the phase order, with two holds in it.

Phases 0 to 3 ship together or in sequence; none of them changes what an existing
room can do and each is readable by the build already in the field. Phase 2's
chat payload switch is safe everywhere; its envelope is confined to current-profile
rooms, of which there are none yet. Phase 3 publishes both KeyPackage slots.

Phase 4 ships built and unflipped. Phases 5 to 7 ship as they land; none of them
is visible on the wire except that forks now settle and commits now wait for an
OK.

**The flip.** `MarmotProfile.DEFAULT_FOR_NEW_ROOMS` goes to the current profile
when every member this app's users are likely to invite runs a Phase 3 build. There
is no negotiation for this and adding one is not worth it: a current-profile room
refuses an old build's KeyPackage with a clear message at the add step, which is
ugly rather than dangerous, and it stops as soon as they upgrade. Existing legacy
rooms are unaffected forever; a room that wants to be on the current profile is a
new room, and the members who join it late are handed its history by
[a chronicle](./member-chronicle.md), which is the mechanism that already exists
for exactly that gap.

**The retirement.** One KeyPackage lifetime -- thirty days -- after the flip, the
legacy slot stops being republished, and `KeyPackageEvent.build` has no caller left.

The three doors: the state blob (Phase 0; a downgrade loses every room touched),
the first current-profile room a user creates (Phase 4 flipped; it can never hold
an old build), and the envelope (Phase 2; a legacy room that ever sends one has a
member who cannot read it, which is why it never does).

**Where it stands.** Everything above ships built and unflipped:
`MarmotProfile.DEFAULT_FOR_NEW_ROOMS` is `LEGACY`, both KeyPackage slots are
published and kept, and no room this app has made is on the current profile
unless a caller asked for one by name -- the tests do; the creation screens do
not. The first door is behind us the moment a build with the bump runs. The
other two wait on the flip, which waits on the people this app's users invite.

---

## What this does not do

- **Media, avatars, streams.** Components `0x8002`, `0x8007`, `0x800b` and `0x8006`
  are advertised and decoded, never edited or rendered. The app has no in-group
  media today and nothing here adds it.
- **Push.** `mip05PushNotifications` was rewritten in 1.15.2; the app has no push
  path to attach it to.
- **External join** by GroupInfo, and **routing rotation**. The `retiredAtEpoch`
  column is ready for the second; nothing writes it.
- **Message edits** (kind:1009) and **receive-side retention**. Both are
  `MarmotMessageStore` features the app's own tables would carry; both are small
  and neither is needed to interoperate.
- **Encryption at rest.** Unchanged, and now behind one interface.
- **Two library gaps** the rewrite does not close and should not be read as
  closing: the retained-epoch decrypt without sender verification, and the secret
  tree's skipped keys not surviving `saveState`
  ([mls-skipped-keys.md](./mls-skipped-keys.md)). The engine holding live groups
  covers the second within a process, exactly as `MlsGroupCache` did.
- **Migrating a legacy room in place.** See the appendix.
- **iOS.** Nothing here was compiled for it; the JSON deserialiser's `sig` default
  that Phase 2 leans on is the JVM and Android actual.

---

## Appendix -- what was considered and rejected

**Re-implementing v2 inside the app's pipeline.** The app's `MarmotInboundManager`
is already a fork of the library's manager as it stood in July, carrying every fix
made since by hand and missing the ones it did not notice -- the framed KeyPackage,
the Welcome by reference, the retention component. Convergence is two thousand
lines whose only property that matters is agreeing with every other implementation;
a second copy that agrees today drifts tomorrow. Adopting costs a week once;
re-implementing costs the same week and then every release.

**Keeping the derived id as the routing id.** It reads as the smaller change: no
table, no lookup, no second id. It violates a MUST NOT, and the reason for the
MUST NOT applies to this app more than to most: the entries a room's group signs
are published under the room's id, so the routing id would be the one string that
ties a group's encrypted traffic to its public output. The split costs a table.

**Adding `0xF2EE` to current-profile groups so old builds could join.** A
GroupContext extension must be supported by every member's leaf; an MDK leaf does
not advertise `0xF2EE`; the room would then refuse exactly the clients the profile
exists to reach. The old build's problem is solved by the old build upgrading.

**Migrating a legacy room in place.** The identity proof lives in the leaf and
cannot be added afterwards, so migrating means every member re-keying their leaf
by Update proposal and an admin committing a GroupContext change on top -- a
ceremony across every device in the room, during which any member on an old build
breaks the commit. The library's comment on the proof says it in four words: "no
fallback and no in-place migration". A new room and a chronicle is what the app
already knows how to do.

**Sending the bare wrap into current-profile rooms.** Every conformant peer drops
it, and the app's own carve-out that admits it is the one place a member could
render another member's words as their own if it were ever loosened. The envelope
keeps every property the wrap was chosen for and removes the carve-out.

**Signing the envelope.** It would make the direct message attributable outside
the group, which is the exact property the throwaway key was chosen to avoid, and
a conformant decoder refuses it anyway.

**Applying a commit and rolling it back on failure.** Equivalent-looking and not
equivalent; the gate's comment on the crash window is the whole argument, and the
membership note's race is what that window looks like in practice.

**One KeyPackage slot from the start.** A current-profile package is framed; an old
build of this app cannot decode it and so cannot invite anyone who upgraded. The
second slot costs one more kind:30443 per rotation for one lifetime.
