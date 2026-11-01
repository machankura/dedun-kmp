# Batch signing with FROST

How to have a group sign several events in one ceremony instead of one at a
time, phased, with the cryptographic constraint that shapes every phase stated
first.

**Built.** All seven phases are in, one commit each, and the phases below are
kept as written -- they are the reasoning, and the code is easier to read
against the argument it came from than against a summary of itself. Where the
implementation chose differently from the first draft the section says so.
`FrostSigningManager.proposeSigningBatch` and
`FrostSigningRepository.proposeSigningBatch` are the entry points. Adding a
chapter is the first caller, and needed a second form of them; see
[The first caller](#the-first-caller-a-chapter-and-its-chunks).

*The chapter, the dialect and the artifact screens this note cites have since
been removed, with the rest of the nip30303 documents
([ceremony-creation.md](./ceremony-creation.md)). The batch machinery stays; the
group wallet's spends are its callers now.*

The headline: **there is no such thing as one FROST signature over many
messages, and no way to reuse a nonce across them.** What can be batched is the
ceremony — the rounds, the group events, and the approval a human is asked for.
A batch of `k` events is `k` independent FROST instances run in lockstep,
sharing one signer set, one transcript and one prompt. The saving is transport
and UX, not crypto, and that is the saving worth having: today
[FrostSigningManager](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/FrostSigningManager.kt)
spends five MLS group events and one human decision per event signed.

## The constraint

A Schnorr partial signature is `s = k + e·x`, with `e = H(R‖P‖m)`. Two different
messages under the same nonce `R` give two equations in one unknown and the
secret share falls out. That is not a subtlety to be careful around; it is the
one way a t-of-n key is lost, and it is already what the two write-once rules on
[FrostSigningSession](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/FrostSigningSession.kt)
exist to prevent.

So the rule every phase below is built to keep:

> **Every item in a batch has its own independent nonce, from every signer, and
> that nonce signs exactly one message for the life of the session.**

`fr.acinq.bitcoin.crypto.frost.Session.create` takes the message and the
aggregated nonce, so a batch is `k` `Session` objects sharing a signer set and a
`TweakCache`. There is no batch primitive in the library and none is needed.

What *is* shared across items, safely, and what is not:

| shared across the batch | per item |
|---|---|
| signer set (`signerIds`) | nonce seed (`nonceRandom`) |
| ceremony, threshold, participant count | secret + public nonce |
| derivation path and `TweakCache` | aggregated nonce |
| the human approval | `Session`, partial signature, signature |
| the chat transcript | the unsigned event and its id |

---

## Phase 1 — schema

**Half a day. No wire change, no behaviour change.**

Five columns move off `FrostSigningSession` onto a new child table: they are the
per-item ones in the table above.

```kotlin
@Entity(
    primaryKeys = ["sessionId", "itemIndex"],
    foreignKeys = [ForeignKey(
        entity = FrostSigningSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class FrostSigningItem(
    val sessionId: String,
    /** Position in the batch. Fixed at proposal; it is the wire ordering. */
    val itemIndex: Int,
    val unsignedEventJson: String,
    val eventId: HexKey,
    val nonceRandom: HexKey,
    val aggregatedNonce: HexKey? = null,
    val signature: HexKey? = null,
)
```

`itemIndex` is load-bearing rather than cosmetic: it is the order every device
joins nonces and partial signatures in, so two devices that disagree about it
produce aggregates nobody can verify. It is fixed by the proposal and never
re-sorted. Spelled `itemIndex` rather than `index` because `index` needs quoting
in every hand-written query it appears in, and one missing backtick is a compile
error at best.

**No `itemCount` column.** The count is `SELECT COUNT(*) FROM FrostSigningItem
WHERE sessionId = :id`, for the same reason `signerIds` is derived from the
ceremony's participant order rather than stored: a denormalised count is one
more thing that can disagree with the session it describes.

### Migration 9 → 10

Not an auto-migration. Room can add a table but cannot backfill one, and this
migration has to move data before it drops the columns it came from. Manual, in
the shape of `MIGRATION_3_4` — the existing precedent for a migration that is
about data rather than shape.

```sql
CREATE TABLE FrostSigningItem (...);
INSERT INTO FrostSigningItem (sessionId, itemIndex, unsignedEventJson, eventId,
                              nonceRandom, aggregatedNonce, signature)
     SELECT id, 0, unsignedEventJson, eventId, nonceRandom, aggregatedNonce, signature
       FROM FrostSigningSession;
ALTER TABLE FrostSigningSession DROP COLUMN unsignedEventJson;  -- and the other four
```

**`DROP COLUMN`, not a table rebuild.** The usual SQLite way to remove columns —
create a new table, copy, drop the old one, rename — is unsafe here and quietly
so. `FrostSignerMessage` and the new `FrostSigningItem` both reference
`FrostSigningSession(id)` `ON DELETE CASCADE`, and `DROP TABLE` fires cascades:
with foreign keys enforced it would delete every signer message and every item
the migration had just written. Whether it does depends on Room having turned
foreign keys off around the migration, which is not worth depending on when
`ALTER TABLE ... DROP COLUMN` cannot go wrong. It needs SQLite 3.35 and columns
free of indices and constraints; these five qualify, and `getRoomDatabase` pins
`BundledSQLiteDriver` on every platform, so the SQLite version is ours rather
than the host's.

**Why the backfill has to be exact.** A session in flight at upgrade time holds
its nonce seed and, possibly, its aggregated nonce in those columns. Losing
either means regenerating a different nonce on the next pass — publishing a
second partial signature over the same message against a different aggregate,
which is the extraction case. Copying them verbatim into item 0 means an
in-flight session resumes as though nothing happened. A migration that dropped
them and let sessions restart would be the one dangerous way to write this.

Update the entity list and `version = 10` in
[DedunDatabase.kt:175](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/DedunDatabase.kt),
regenerate `composeApp/schemas/10.json`, and add the DAO methods:
`getItems(sessionId)`, `observeItems(sessionId)`, `upsertItems(List<FrostSigningItem>)`,
`countItems(sessionId)`, `countSignedItems(sessionId)`.

**Test:** extend
[FrostSigningSessionDaoJvmTest](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/database/dao/FrostSigningSessionDaoJvmTest.kt)
with item CRUD, the `(sessionId, itemIndex)` dedupe, index ordering and the
cascade delete. Add a migration test that runs the migration over a v9 database
holding a half-finished session and asserts the seed and aggregate survive at
index 0 — the values, not merely that a row appeared.

---

## Phase 2 — vectorise the manager at k = 1

**Two days. Still no wire change, still no API change.**

The whole point of this phase is that **every existing test passes unchanged**.
`proposeSigning` still takes one event, still writes one item, and the messages
on the wire are byte-identical to today's. What changes is that `advance()`
loops.

In [FrostSigningManager.advance](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/FrostSigningManager.kt):

1. Load `items` once, ordered by `index`.
2. Nonce generation becomes a `map` over items, each with its own
   `session.nonceRandom` → `item.nonceRandom` and its own `message`.
3. `Session.create` becomes one per item; the `signerIds`, `publicShares`,
   `nParticipants`, `threshold` and `tweakCache` arguments are the same for all
   of them.
4. `sign` and `aggregateSigs` become per item.
5. `complete()` verifies each item's signature against `item.eventId` and calls
   `applySignedEvent` for each.

Three invariants to establish here, because Phase 3 depends on all three:

- **The signer set and every aggregated nonce are one write-once unit.** The
  coordinator writes `session.signerIds` and all `k` `item.aggregatedNonce`
  values in a single `@Transaction`, so "some items aggregated" is unreachable.
  `signerIds != null` stays the gate the rest of `advance()` reads, exactly as
  today.
- **Signatures likewise.** All `k` land in one transaction; `countSignedItems ==
  countItems` is the settled test, replacing today's `session.signature != null`
  in both `advance()` and `isAwaitingApproval`.
- **Item order is the wire order.** Every join and split goes through one pair
  of helpers, never an ad-hoc `map` at a call site.

### The cost that appears here

`advance()` regenerates nonces and creates FROST sessions **unconditionally on
every inbound message**, before checking whether this device has already
published. At k=1 that is one native call per message and nobody notices. At
k=64 it is 64 nonce generations, 64 `Session.create`s and 64 signs on every
message the group sends — several hundred native calls to discover there is
nothing to do.

Fix it in this phase, while it is still cheap to verify: generate nonces on
demand, and leave before building any `Session` when this device is neither
signing nor aggregating. Both are pure optimisations at k=1, which is the point
of doing them before k>1 exists.

**Generate them lazily, not behind a guard.** The obvious version — compute the
nonces only when `ownNonce == null || (isSigner() && ownPartial == null)` — is
wrong, and wrong in a way that passes a reading and fails every test. The
coordinator settles the signer set *further down the same pass*, so `isSigner()`
read at the top of `advance()` is false on the pass where the coordinator is
about to become a signer, and the nonces it then needs were never generated. A
`by lazy` has no such prediction to make: it generates at first use, at most
once per pass, and never on a pass with nothing to publish.

### The payload codec, early

`joinPayload`/`splitPayload` land here rather than in Phase 3, because at k=1
they are the identity — a one-element comma join is the bare value — so they
change no byte on the wire and leave Phase 3 to the proposal encoding alone.

**Test:** existing tests are the test. If
[FrostSigningRoundTest](../composeApp/src/commonTest/kotlin/com/machankura/dedun/compose/managers/FrostSigningRoundTest.kt)
and
[SignedGroupKeyStateTest](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/SignedGroupKeyStateTest.kt)
pass without edits beyond the schema move, the vectorisation is faithful.

---

## Phase 3 — k > 1 on the wire

**Two days. This is the phase with the compatibility trap in it.**

### Payload encoding

*(Landed in Phase 2 — see above. Restated here because the rest of this phase
depends on it.)*

`FrostSignerMessage` keeps its `(sessionId, signerPublicKey, kind)` primary key
and **one row carries all `k` values**, comma-joined — the same encoding
`FrostSigningSession.signerIds` already uses.

Per-item message rows were the obvious alternative and are worse. The current
key is what makes a redelivered message overwrite rather than accumulate, which
is what keeps the coordinator's signer set the right length; splitting by item
multiplies the ways a partial delivery can look like a complete one. One group
event carrying a signer's whole contribution also matches the transport: a
signer publishes all `k` nonces or none.

The parse is strict. A payload whose element count is not the session's item
count is dropped, not truncated and not padded:

```kotlin
private fun splitPayload(payload: String, expected: Int): List<String>? =
    payload.split(",").takeIf { it.size == expected }
```

Note *where* this runs. `record()` stores payloads without parsing them, which
is what lets a nonce arrive before its proposal; the count check therefore
belongs in `orderedNonces` and `orderedPartialSignatures`, where the session —
and so the count — is known. Do not move it earlier to "fail faster"; that
breaks the out-of-order replay that `replayStoredMessages` exists for.

### Proposal encoding, and the compatibility trap

The proposal's content becomes a JSON **array** of unsigned events — but only
when `k > 1`.

A single-event session must keep serialising as a bare JSON object, byte for
byte as today. The reason is what an old build does with each form:

| old build receives | outcome |
|---|---|
| bare object (k=1) | signs it, exactly as now |
| JSON array (k>1) | `Event.fromJsonOrNull` returns null → "does not carry an event; dropping" |

That is the correct failure. An old device refuses a batch rather than
mis-signing part of one, and a group with mixed versions keeps single signing
working throughout the rollout. Emitting an array unconditionally would break
every k=1 session for old devices and buy nothing.

`acceptProposal` accepts both: array if the content starts with `[`, otherwise a
one-element list. New devices understand old proposals forever; old devices
understand new single proposals forever.

### `acceptProposal` over a list

Each element is rebuilt from its own fields under the room's path and checked
against the id it claims — the existing check, per item, and the whole proposal
is dropped if any one fails. The write-once rule extends from "the event this
session signs" to "the ordered list of events this session signs": a second
proposal under the same id whose list differs anywhere is logged and ignored,
never applied.

### A cap on `k`

Enforce a maximum batch size — 64 is a sane starting number — in
`proposeSigning` **and independently in `acceptProposal`**.

The second check is the one that matters. Without it a proposer can hand every
member a batch of arbitrary size and have them do unbounded native work and
publish an unbounded group event, from a single message. The proposal is the one
place in this protocol where a remote party decides how much work everyone else
does, and it is currently bounded only by never having more than one item.

Size it against the MLS group event limit rather than picking a round number:
each signer's nonce message is `k × 133` bytes of hex-and-commas, the partial
message `k × 65`, and the proposal itself carries `k` whole events, which is the
term that actually binds. 64 to start.

### `proposeSigningBatch` on the manager, here

The public API on the manager lands in this phase rather than the next one, for
a plain reason: there is no other way to produce a `k > 1` session, so without it
everything above ships untested. `proposeSigning` becomes its one-event form and
keeps its signature, so no caller moves. Phase 4 is then the repository, the call
sites and the failure policy.

That also makes this the phase where the batch is proved end to end — a `k = 3`
session between two devices over two databases, plus the negative test that no
two items share a nonce. Both are described under Phase 6 and are worth reading
there; they simply run here, because this is where the thing they test exists.

---

## Phase 4 — the batch API and its call sites

**A day.**

*(`FrostSigningManager.proposeSigningBatch` itself landed in Phase 3 — see
above. This phase is what surrounds it.)*

```kotlin
suspend fun proposeSigningBatch(
    database: DedunDatabase,
    localChatRoom: LocalChatRoom,
    userPublicKey: HexKey,
    events: List<EventTemplate<*>>,   // each carries its own createdAt
    key: DkgSession? = null,
): FrostSigningSession
```

`proposeSigning` stays, as the one-element call into it, so
[AddDialectViewModel](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/AddDialectViewModel.kt),
[AddArtifactViewModel](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/AddArtifactViewModel.kt)
and
[GroupKeyStateManager.propose](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupKeyStateManager.kt)
need no edit at all. `GroupKeyStateManager` in particular should never batch: a
room's key state is the statement every other session is opened against, and
bundling it with anything else would make it as available as its worst
co-passenger.

Mirror both on
[FrostSigningRepository](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/repository/FrostSigningRepository.kt),
including the `NO_OP` implementation. `signedEvent(session): Event?` becomes
`signedEvents(session): List<Event>`.

### Nonce seeds

`k` independent 32-byte seeds, generated at proposal, one per item. Deriving
them from a single session seed by index would work and save nothing worth
having: independent seeds mean an off-by-one in index handling produces a
session that fails to aggregate, rather than one that signs two messages under
one nonce.

### Failure policy: all or nothing

A batch fails whole. If any item's aggregation fails, `fail()` runs as it does
today, nothing is applied, and the group is told once.

This is a real cost and should be stated where callers can see it: **a batch is
only as available as its worst item**, so bundling unrelated events makes both
less likely to get signed. Batch things that belong together.

The retry rule is the one that must not be got wrong, and deserves a comment on
`proposeSigningBatch` itself: **a retry is a new session id with new seeds.**
Never re-propose a failed batch under its own id, and never reuse an item's
`nonceRandom`. This is true of single sessions today; batches make it more
tempting to get wrong, because a batch that failed on item 5 looks like it has
four perfectly good nonces going spare. It does not — those four have already
been published against an aggregate.

Per-item partial success is deliberately out of scope. It would need mixed-state
UI, a transcript that can say "3 of 5", and a `complete()` that applies a subset,
which is a lot of surface for an outcome that indicates a bug or a dishonest
coordinator rather than a normal ending.

---

## Phase 5 — UI

**Two days, most of it in the screen.**

- `FrostSigningUIState.Loaded` gains `items: List<FrostSigningItem>`.
- [FrostSigningViewModel](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/FrostSigningViewModel.kt)
  combines `observeItems` into its existing `combine`; nothing else changes,
  since it already re-renders on every session write.
- [FrostSigningScreen](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/FrostSigningScreen.kt)
  renders a list where it renders one event today.

**The approval gate must show every event, not a count.** The argument in
`FrostSigningManager`'s own header — one approval rather than three, because the
event is fixed before the member is asked — only holds if the member can see
what they are approving. "Sign 12 events?" behind a chevron is a worse prompt
than the twelve prompts it replaces. A member who cannot scroll the whole list
should not be able to approve it.

`FrostSigningRoute` is unchanged: it already carries a session id, and a batch is
one session.

### Transcript

No new `ChatMessage` types. The existing `TYPE_FROST_*` constants,
`FROST_REQUEST_FULFILMENTS`, `FROST_SETTLEMENTS` and `FROST_TYPES` in
[ChatMessage.kt:220](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt)
all work as they stand — one line per step, per member, whatever `k` is. Only
the wording in `announceStep` and `announceStarted` gains a count: "signed their
part of 12 events". This is worth checking rather than assuming, because a new
type would need adding to five sets and would silently render as a chat bubble
if missed from `FROST_TYPES`.

**What this missed, and how it was fixed.** The claim above holds for `k` events
in one session and breaks for two sessions at once, which
[the first caller](#the-first-caller-a-chapter-and-its-chunks) went on to need: a
chapter and the translation scaffolding beside it are two proposals, open
together, writing the same line types into the same stretch of transcript.
`answeredRequests` and `settledRequests` matched a request against any later line
of the fulfilling type, so answering one of the two dropped the "Review" on both
— the second proposal became unreachable from the transcript the moment the first
was decided.

Nothing on a row could tell them apart, so `ChatMessage.frostSigningSessionId`
was added (schema v11, nullable, `AutoMigration(10, 11)`), stamped by
`FrostSigningManager.announce` on every line it writes. Both rules read it when
both rows have one and fall back to the clock when either does not — which is
what a line older than the column has to be read by, and is right for those
rooms: nothing that predates batch proposals ran two sessions at once. A ceremony
line still uses the clock always, since a room runs one ritual at a time.

The transcript answers one question about a proposal — is this still asking
something of you — and answers it in the middle of everything else the room said.
Two open proposals need a place that shows both, so `ProposalListScreen` lists a
room's sessions newest first with the ones waiting on the reader gathered at the
top, and the room's history under them. `FrostSigningRoute.sessionId` stopped
being optional at the same time: it existed for transcript lines that could not
say which session they meant, resolved to "the room's live session", and that
guess is exactly what two proposals make wrong. A line too old to name its
session opens the list instead.

---

## Phase 6 — the test that actually proves it

**A day, and do not skip it.**

*(The first two ran in Phase 3, where the batch first existed. Kept here because
this is the section anybody adding to these tests will read.)*

Extend
[SignedGroupKeyStateTest](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/SignedGroupKeyStateTest.kt),
which already runs a real signing session between two devices over two
databases, with a `k = 3` batch: both devices reach `COMPLETE`, all three
signatures verify against their own event ids under the room's key, all three
events are applied locally on both, and the whole thing costs five messages from
the coordinator and two from the other signer rather than one round per event.

**The negative one that matters**: assert the three aggregated nonces are
pairwise distinct, and that the three seeds are. Every positive test above still
passes if two items share a nonce — the signatures verify perfectly well; what
sharing costs is the secret share, to anyone who sees both partial signatures.
It is the cheapest possible guard against the one mistake in this document that
loses the key, and it catches an index bug nothing else here would.

Then two on the inbound path, driven by handing the manager a hand-built inner
event rather than one the other device queued — which is the only way to be a
faulty or dishonest member in this harness:

- **a wrong-length payload is left out, not truncated.** The length check is all
  that stands between a batch and a signer whose contribution lines up against
  the wrong messages. Assert that a one-value nonce for a three-item batch does
  not count towards the threshold — and then that the real nonce replaces it and
  the batch finishes, so it is a stall rather than damage.
- **a second proposal under the same id changes nothing.** Every item's seed is
  already committed to that item's message; a different batch under the same id
  would have those seeds produce a second partial signature over a second
  message.

And three in
[FrostSigningRoundTest](../composeApp/src/commonTest/kotlin/com/machankura/dedun/compose/managers/FrostSigningRoundTest.kt),
against real FROST with no database, for the same reason that file exists at
all — the library calls are checked with nothing in the way: a `k = 3` batch
from one signer set where all three verify, an item's signature refusing to
verify against its neighbour, and both halves of the no-shared-nonce property
(one seed under two messages gives two nonces, *and* the seeds differ anyway —
either alone is enough to be relied on by accident).

---

## Phase 7 — rollout

**No code.**

Nothing here needs a feature flag. `k = 1` is the entire behaviour of the app as
shipped — no caller batches anything yet — and at `k = 1` every message is
byte-identical to the app before Phase 1:

| message | at `k = 1` |
|---|---|
| proposal | `encodeProposal` returns the bare event object (asserted in `FrostProposalCodecTest`) |
| nonce, signer set, partial, signature | `joinPayload` of one value is that value |
| chat transcript | every line's plural branch is only taken above 1 |

Phases 1 and 2 are shippable on their own and were worth landing on their own —
a schema move and a refactor — because a bisect over a signing bug then lands on
one or the other rather than on all of it.

Before the first caller batches, confirm the group is on a build that
understands array proposals. There is no negotiation for this and adding one is
not worth it: the failure mode is a batch that never reaches threshold and is
abandoned, which is visible in the transcript and costs nothing but a retry.

### What is left, when a caller wants it

Nothing in the protocol. The remaining work is deciding *what* to batch, which
is a product question this document deliberately does not answer — beyond the
one rule that a batch is only as available as its worst item, so events that do
not belong together should not travel together, and the one prohibition that
`GroupKeyStateManager.propose` must never batch.

### The first caller: a chapter and its chunks

Adding a chapter is the first thing in the app to batch. A chapter is proposed
together with a chunk per paragraph — before this work, one quorum each, which
is why the chunks were briefly derived on arrival from the signed chapter's text
instead. They are signed now, and each carries the group's signature over its own
words: a translation is of a chunk, and a chunk that can be checked on its own is
worth more than one that can only be checked by re-deriving it.

Two things it needed that a flat list could not give:

**An item that names another item.** A chunk carries the id of its chapter, and
that id is a hash over the group's key at the room's derivation path — neither
resolved until the proposal is made. A caller computing it would be recomputing
`signingPath`, the one input that must never come from a proposer. So
`proposeSigningBatch` has a second form taking a `lead` template and a
`dependents` builder, which is handed the lead *after* it is authored and returns
the events referring to it. Every id still comes from `unsignedEventOf`, and an
item naming a chapter nobody signed stops being a mistake that can be made.

**The lead is item 0.** Items apply in `itemIndex` order, and a chunk row whose
chapter does not exist yet is a foreign key violation, so the thing being
referenced has to be signed first in the batch as well as named first.

**What it costs, in front of the user.** `MAX_BATCH_SIZE` is 64 and the chapter
takes one place, so a chapter is capped at 63 paragraphs and a longer one has to
be split in two. That is a real limit on real prose. `AddChapterScreen` shows the
chunk count against the cap as the text is typed and refuses to propose past it,
because the alternative is an `IllegalArgumentException` after the fact.

The general rule this leaves behind: **a batch is for events that arrive
together and are checked apart.** If the items are only ever read through one of
them, deriving is cheaper and has no cap; if each is something a member might
hold, hand it its own signature.

---

## Appendix — what was considered and rejected

**One signature over `k` messages.** Does not exist for Schnorr. Aggregate
signature schemes that do this (BLS) are a different curve and a different
verification story, and nostr verifies BIP-340.

**One nonce, `k` messages.** This is the extraction attack, described at the top.

**Nonce pre-processing** — the FROST paper's own batching, where signers
pre-publish a list of π nonce commitments before any message is known, and each
later signature consumes one commitment per signer in a single online round. The
library supports it: `SecretNonce.generate` takes `message` as nullable.

Rejected for now, and it is worth writing down why, because it is the thing
somebody will suggest next. It cuts *latency* rather than message count, which
is not the complaint. And it costs the property this whole design rests on:
today a nonce seed is safe to store and regenerate from **because** a session
signs one fixed message and cannot be made to sign another. Banked nonces have
no message to be bound to at generation time, so their safety moves from a
structural argument to a used/unused ledger that must be right across crashes,
redeliveries and two devices. A bug there leaks a share. Revisit only if
round-trip latency becomes the actual problem.

**`Frost.deterministicSign` (BIP-445)**, where a signer going last derives its
nonce from the other signers' aggregate and persists nothing, is orthogonal to
batching but relevant to the same file. It would let one signer per session hold
no nonce state at all. Not part of this work.

**Signing one manifest event that commits to `k` items** — a list of ids, or a
Merkle root. One signature, no protocol change, and by far the cheapest thing on
this page. Rejected as the general answer because each item stops carrying its
own verifiable signature, and the whole point of
[shared-key-derivation.md](./shared-key-derivation.md) is that a reader holding
one signed dialect can check it against the room it was found in without a
lookup. Still the right answer for any case where the items are only ever read
together.
