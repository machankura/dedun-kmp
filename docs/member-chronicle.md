# Handing a new member the group's history

A member added after the work was done sees none of it, and no amount of waiting
fixes that. This is how to send them the group's signed record, why the sending
member cannot be trusted and does not need to be, and the one thing a chronicle
cannot give them.

Read [shared-key-derivation.md](./shared-key-derivation.md) first. The property
this whole design rests on -- that a room's id *is* the key it signs with -- is
stated there, and everything cheap about what follows is downstream of it.

**Built, and carrying nothing at the moment.** The document kinds it was built
for were removed with their screens ([ceremony-creation.md](./ceremony-creation.md),
Phase 7), and nothing else the group signs is safe or useful to replay. What a
chronicle may carry is now a `ChronicleKinds` value, `ChronicleEvent.KINDS`, and
it is empty: a device asks for no chronicle, sends none, and applies nothing from
a page an older build sends. The machinery below is kept and tested with a kind
set of the tests' own. Adding a kind back means editing `ChronicleEvent.KINDS` on
purpose, with the reason it is safe to replay. The row rebuild and translation
supersession described below went with the documents.

Built, phases 1-9, one commit each, with two exceptions named in Phase 7. The
phases are kept as written because they are the reasoning, and the code reads
better against the argument it came from than against a summary of itself. Where
the implementation chose differently the section says so, and it did so five
times worth reading:

| what the plan said | what it turned out to be |
|---|---|
| nine chroniclable kinds | six at first, eight now. The three that were unsigned were fixed in the app rather than worked around here -- see [Phase 3](#what-is-actually-chroniclable) |
| `MAX_PAGE_EVENTS = 256` | 128. At 256 the byte cap always binds first and the count cap can never fire |
| "assemble, order, pack and queue" | assemble only; queueing moved to Phase 5, next to the thing that decides when |
| "re-read the room between the invite and the assembly" | unnecessary; that rule is about the MLS snapshot a commit is built on |
| an old build "files it as unsupported" and nothing breaks | true, and it renders as a raw-JSON chat bubble per page -- see [Phase 9](#phase-9--rollout) |

The gate in Phase 3 is the one to keep if any of this is ever rewritten: it found
a rebuild that would have shipped payloads every receiver drops as forgeries,
silently, one kind at a time.

## The constraint

Two independent facts, and both have to be understood before the design makes
sense.

**MLS gives no history.** A Welcome carries the ratchet tree at the current
epoch, not the transcript. `MarmotInboundManager` drops anything from an epoch it
holds no keys for, and nothing replays. This is not a gap to be closed -- it is
forward secrecy working, and a design that quietly undid it would be worse than
the problem.

**Group-signed events never travel at all.** This is the one that surprises
people. `FrostSigningManager.complete` says so in as many words:

> Nothing goes on the wire: a signed event authored by the threshold key cannot
> travel as an inner event anyway, because the outbound pipeline re-authors
> rumors as their sender and would strip the group's signature off.

Every device *derives* the finished event from its own `FrostSigningItem` rows
once the signature aggregates. A member who was not in the session has no items,
and no message ever sent afterwards carries the event. So the second fact does
not follow from the first and is not fixed by fixing it: even a member who could
decrypt the entire back-transcript would still hold nothing an artifact, chapter
or chunk could be built from.

Which makes a chronicle not a convenience but the only path, and fixes the line
the design has to hold:

> **A chronicle carries what the group signed. Never the chat.**

Two reasons, and the second is the load-bearing one. Restoring the chat would
undo forward secrecy on purpose. And a signed event is the only thing a new
member can check for themselves -- everything else would have to be believed
because a member said it, which is a worse property than the gap it fills.

## Verification costs a room id and nothing else

A new member holds their Welcome, and so the room's id. That turns out to be
everything they need.

`GroupKeyStateEvent.isSignedByGroup` already asks exactly the right question --
did *this room's* key sign this event -- in three parts: the author is the key
derivation reaches, the id is the hash of the fields sitting next to it, and the
signature verifies. And its first line is:

```kotlin
val author = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path)
if (!event.pubKey.equals(author, ignoreCase = true)) return false
```

That derived value is the room's id. `GroupKeyState.isMatchedBy` enforces it,
`FrostSigningManager.signingPath` resolves the path by it, and
`DkgRitualViewModel` creates the admin room *at* it. So for any room with a
shared key, `marmotGroupId(thresholdPublicKey, path) == chatRoomId`, and the
check collapses to:

```kotlin
event.pubKey == chatRoomId && hashIdCheck(...) && Nip01Crypto.verify(...)
```

No `GroupKeyState` row, no threshold key, no derivation path, no lookup. A member
who can name the room can verify its signatures. That single fact decides most of
what follows:

| question | answer, and why |
|---|---|
| Who may send a chronicle? | Anyone in the room. The receiver checks every payload, so a hostile sender can inject nothing. |
| Does it need encrypting to the recipient? | No. It is the group's own history going back to the group. |
| Does the new member need the key state first? | No. That was the ordering problem this removes. |
| What can a hostile chronicle do? | Omit. Not forge. See [What this does not do](#what-this-does-not-do). |

### The guard that is not optional

Nothing on the inbound nip30303 path verifies a signature today.
`ChatMessage.applyInnerEvent` parses and upserts, and that is *correct* as things
stand: rumors carry `sig = ""` and are authenticated by the MLS frame, so nothing
on the wire has ever claimed group authorship. A chronicle is the first thing that
does.

So `isSignedByRoom` is not hardening. It is the feature's entire security, and
without it any member can submit a fabricated `ArtifactEvent` with `pubKey` set
to the room id and a junk signature, and a new member files it as agreed group
work.

### And the guard behind that one

Verification admits an event to the apply path on the strength of the group's
signature. That makes **every kind the group has ever signed replayable by any
member at any time**, which is a larger door than it first looks.

`GroupKeyStateEvent` (30326) is group-signed and would pass `isSignedByRoom`
perfectly. A chronicle carrying an old one is a validly signed statement about
which key the room signs with, replayed by whoever kept a copy.

> **A chronicle carries an allowlist of document kinds, never everything that
> verifies.** The list is the nip30303 kinds `applyInnerEvent` dispatches, and
> the rule is checked on the way out *and independently on the way in*.

Same shape as the cap on `k` in
[frost-batch-signing.md](./frost-batch-signing.md#a-cap-on-k), and the same
reasoning: the outbound check is politeness, the inbound one is the security
boundary.

## Push and pull

The obvious trigger is the invite: send the chronicle right after the Welcome. That
works, and on its own it is unreliable in the way
[marmot-membership.md](./marmot-membership.md#why-this-fails-silently) describes.
A chronicle is an application message in the epoch the add created. If it reaches
the invitee before their Welcome does -- different transports, no ordering
guarantee -- it is **dropped, not deferred**, and the sender sees nothing wrong.

The fix is not to make the push more careful. It is to let the joiner ask:

- A request is proof of arrival. A device that can send an application message in
  the room has processed its Welcome; the race has nothing left to lose.
- It covers what a push never can. A reinstall, a second device, a member whose
  inviter has since left, a chronicle that was lost -- none of those has an invite
  to hang off.
- It converges. Requests repeat, chronicles are idempotent, and any member can
  answer.

So both, on the same two events: **the pull is the mechanism and the push is a
latency optimisation on top of it.** Phase 6 is the push, and it is deliberately
after the phase that makes it unnecessary.

---

## Phase 1 -- the verifier

**Half a day. No wire change, no behaviour change.**

In [GroupKeyStateEvent.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/frost/GroupKeyStateEvent.kt),
split the existing check in two and keep the existing one as a caller:

```kotlin
/**
 * Whether the room with id [chatRoomId] signed [event].
 *
 * The room's id is the group's signing key -- see shared-key-derivation.md --
 * so this needs nothing but an id the caller already has. That is what makes
 * a chronicle checkable by a member who holds no key state and no share.
 */
fun isSignedByRoom(event: Event, chatRoomId: HexKey): Boolean = runCatching {
    if (!event.pubKey.equals(chatRoomId, ignoreCase = true)) return false
    if (!EventHasher.hashIdCheck(...)) return false
    Nip01Crypto.verify(...)
}.getOrDefault(false)

fun isSignedByGroup(event: Event, thresholdPublicKey: HexKey, path: List<Long>) =
    isSignedByRoom(event, SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path))
```

Everything already caught stays caught: every input is off the wire, and a pubkey
that is not a point, a signature that is not 64 bytes and hex that is not hex all
mean the same thing here.

**Test**, in `commonTest` beside the existing ones -- pure functions, no database:
a real group-signed event passes against its own room id and fails against
another's; a member-authored rumor (`sig = ""`, member pubkey) fails on both
counts; an event with the room's pubkey and a random signature fails; an event
whose content is edited after signing fails on the id check before the signature
is even reached.

---

## Phase 2 -- the events and their codec

**A day.**

A new package, `com.machankura.dedun.compose.nostr.chronicle`, with `ChronicleEvents.kt`
holding the kinds -- mirroring `FrostSigningEvents`.

```
 holder    --[ 30327 chronicle         ]-> one member   a page of signed events
 joiner    --[ 30328 chronicle request ]-> everyone     "I have none of this"
```

**Why 3032x and not 30313.** The nip30303 family runs 30300 to `SubmissionEvent`
at 30312, and 30313 is free *in the Marmot inner-event space*. It is not free in
the NIP-17 gift-wrap space, where the DKG sits on 30310-30316.
`FrostSigningEvents`' own header calls that overlap "an accident of routing
rather than a decision, and the next family added should not rely on it." This is
that next family, so it does not. 30327 and 30328 sit past `GroupKeyStateEvent`
at 30326 and clash with nothing on either transport.

It is also the right neighbourhood on the merits. A chronicle is not a document
kind; it is a statement about the record, which is what `GroupKeyStateEvent` is
too.

### Why not just send N `SubmissionEvent`s

The envelope is right there, it already carries a payload whole "keeping its own
id, author and signature", and its header even names the case. It is still the
wrong kind here, for three reasons:

- **A submission is an act** -- *this member is putting this event in front of
  this group*. A chronicle asserts nothing; it re-delivers what the group already
  agreed. On one kind, a 400-event backfill is indistinguishable from 400 new
  submissions, and every device has to guess which it is looking at.
- **N submissions are N inner events and N kind:445s.** A page is one.
- **The submission arm files a `ChatMessage` per payload.** A chronicle must not
  -- see Phase 4.

### Shape

Content is a JSON array of the signed events, whole. Always an array, even for
one: there is no old build to stay compatible with, which is the only reason
`FrostSigningEvents.encodeProposal` has a bare-object form. Do not copy that
shape here.

Tags, one value each, per the house convention:

| tag | holds | why |
|---|---|---|
| `ChronicleIdTag` | 32-byte hex | Ties pages of one chronicle together, so two members answering the same request do not interleave into one nonsense sequence. |
| `ChroniclePageTag` | index, total | The receiver can say whether it holds a whole chronicle. |
| `p` | recipient pubkey | **A hint, not access control** -- see Phase 4. |

### Two caps, both enforced on receive

```kotlin
const val MAX_PAGE_BYTES = 64 * 1024
const val MAX_PAGE_EVENTS = 128
```

A byte cap rather than a count alone, because the events vary by two orders of
magnitude -- a chunk is a paragraph, an artifact is a URL. The count cap bounds
the receiver's *work* where the byte cap bounds the *transport*.

**The count was 256 when this was written, and 256 can never fire.** An event
carries 64 characters of id, 64 of pubkey and 128 of signature before it says
anything, so the floor is about 370 bytes and 64 KB cannot hold much past 170 of
them -- the byte cap always binds first and the count cap is a check that never
runs. The two have to be sized against each other or one of them is decoration.
At 128 both bind something: the count stops a page of many small payloads, the
bytes stop a page of few large ones. The test that says so asserts a page at
exactly the cap still decodes, which is the assertion that fails when somebody
raises one number without the other.

Both are checked independently on the way in, for the reason the batch cap is:
a chronicle is the second place in this protocol where a remote party decides how
much work everyone else does. Measure the 64 KB against a finished kind:445
rather than trusting it -- MLS framing and NIP-44 expansion both sit outside it.

**Re-sized since: 48 KiB and 96.** A conformant Marmot decoder bounds the whole
app payload at 64 KiB, and a page rides inside one as its JSON-escaped `content`
plus six envelope members, so a 64 KiB page was over that bound by construction
and an MDK peer dropped it without a word. 48 KiB leaves room for the escaping
whatever the mix of payloads, and 96 is the count that still binds under it.
See [marmot-v2.md](./marmot-v2.md), Phase 2.

**Test:** codec round-trip; a page over either cap is refused on receive; an
array containing a non-event is refused whole.

---

## Phase 3 -- assembling a chronicle

**A day.**

`ChronicleManager.assemble(database, chatRoomId, recipient): List<EventTemplate<*>>`

Read every group-signed event this device holds for the room, order it, and pack
it into pages.

**Assembling only.** Queueing each page as a `MarmotInnerEvent` moved to Phase 5,
where the thing that decides *when* to send one lives. Splitting them keeps this
phase testable against a real database with no outbound path in the way, and
keeps the decision about transcript lines next to the decision about triggers.

### Where the events come from

> **Since the `GroupSignedEvent` table landed**, a signed event *is* stored as an
> event -- `FrostSigningManager` files one per batch it completes, and
> `ChronicleManager.applyPage` files one per payload it accepts, each with the
> derivation path its author was reached at. `assemble` reads that table first
> and rebuilds only what it does not hold, which is work signed before the table
> existed. So the rest of this section describes the *fallback*: the round-trip
> gate it argues for is what holds those older rooms up, and it can go once no
> install still carries pre-v13 work.
>
> Two things changed with the source, both worth knowing before reading on:
>
> - **The allowlist now does real work on the way out.** The rebuild could only
>   ever produce document kinds; the table holds everything the group has signed,
>   `GroupKeyStateEvent` included. `assemble` filters on
>   `ChronicleEvent.isChroniclable` before anything else -- without it
>   `ChronicleEvent.build` refuses the page and takes the whole chronicle with it.
>
>   Written when a room signed its own key state as its first act. It no longer
>   does: the state is agreed before the room exists, in the NIP-17 room the
>   ceremony ran in, and is filed under that room -- see `GroupKeyStateManager`.
>   The filter is therefore a narrower guard than it was and still not a
>   redundant one; see "What only looks like it goes" below.
> - **An artifact whose initial version row is missing now chronicles.** The
>   rebuild has to recover the version label from that row and logs and gives up
>   without it; on file as an event, the label never left.

Signed events are not stored as events; they are stored as rows. So the chronicle
is rebuilt from `Mantra*` rows via each entity's `toXEvent()`, which is exactly
what the round-trip convention exists for: `toXEvent` emits tags in the same
order as `build`, so the id round-trips, and the row carries `signature` and
`publicKey` alongside. Reassembled event, original signature, verifies.

**This is the assumption to test first, before writing anything else in this
phase.** If any entity's `toXEvent` does not round-trip to an id whose signature
still verifies, that entity cannot be chronicled at all, and it is better to find
out in an afternoon than in Phase 8. A round-trip test per kind, over rows
produced by a real signing session, is the gate on the rest of this work.

**It was right to run it first.** Every `toXEvent()` in the codebase turned out
to be unused in production -- written for exactly this and never called, so the
"tag order matches build so the event id round-trips" comments on them were
claims nothing had checked. One was wrong. `MantraArtifact.toArtifactEvent` put
the alt tag last where `ArtifactEvent.build` puts it first, *and* left out the
version metadata tag entirely -- because that tag is not on the artifact row at
all. `fromArtifactEvent` reads the artifact's own fields and drops the version
label, which `applyInnerEvent` has by then turned into the artifact's first
`MantraArtifactVersion`. So the label comes back as a parameter, read off the
initial version -- the one whose `createdAt` is the artifact's, since
`initialVersionOf` derives it from the same event.

Neither fault would have shown up as an error. Both produce a well-formed
artifact whose id no longer matches its fields, which every receiver drops as a
forgery, silently, one kind at a time.

### What is actually chroniclable

A chronicle can only carry what its receiver can check, so the list is exactly the
kinds a signing session produces. Eight of the thirteen nip30303 kinds do.

| kind | | why |
|---|---|---|
| 30304 Dialect, 30300 Artifact, 30301 ArtifactVersion, 30302 Chapter, 30303 Chunk, 30306 TranslationArtifactVersion, 30308 TranslationChapter, 30309 TranslationChunk | chroniclable | proposed through `proposeSigning` / `proposeSigningBatch` |
| 30311 Translation | no | nothing builds one; the inbound arm exists and no producer does |
| 30305, 30307, 30310 contributor lists | no | `applyInnerEvent` has no arm that writes a row for any of them |

**It was six when this was written, and the two that were missing were the two
that mattered.** An artifact version was derived from the signed artifact on
arrival -- a row naming the group as its author with no signature to show for it
-- and a translated chunk was submitted as its author's rumor by
`MantraDao.saveTranslation`. Neither could be put in front of somebody with no
way to check it, so a chronicle restored everything a translation hangs on and not
the translation.

Both were fixed in the app rather than worked around here, in parallel with this
work and for their own reasons: `feat: sign an artifact's first version with it,
not derive it after` makes the version the second item of the artifact's batch,
and `feat: ask the group to sign a chunk's translation, not just save it` puts a
quorum behind the prose. Once each of them carried a signature there was nothing
left to argue about -- the allowlist grew by two and the caveat went away.

The order is forced by the foreign keys, and 30301 and 30309 do not go on the
end: a version sits between its artifact and the chapters hanging off it, and a
translated chunk hangs off both a source chunk and a translation chapter, so it
really is last.

**A retranslated passage chronicles once, and that stopped being free.** The arm
that applies a translation chunk drops the row it supersedes -- newest by the
timestamp the group signed at, id breaking a tie -- so while the chronicle was
rebuilt from rows, a sender simply had nothing but the group's current answer to
each passage and that is what travelled.

Reading from `GroupSignedEvent` changed it. The record keeps every event the
group ever signed, deliberately: a signature is the group's statement and
discarding one is not that table's business. So a passage translated three times
leaves one row and three events, and an unfiltered read would put every draft a
group ever signed into every chronicle it ever sends, for as long as the room
exists.

`ChronicleManager.currentTranslationsOnly` is what holds the original property
up. It restates the applying arm's rule rather than approximating it -- newest by
signed timestamp, id breaking a tie -- because a chronicle that shipped one
translation as current while the recipient settled on another would have both
validly signed and nothing downstream to notice the disagreement. Dropping the
drafts is safe precisely *because* the recipient applies the same rule: it is not
what keeps them correct, only what stops them being sent work they would discard
on arrival.

### Ordering

Room enforces the shape, so a chronicle out of order is a foreign key violation
rather than a wrong answer. The rank:

| # | kind | event | depends on |
|---|---|---|---|
| 1 | 30304 | Dialect | -- |
| 2 | 30300 | Artifact | Dialect |
| 3 | 30301 | ArtifactVersion | Artifact |
| 4 | 30302 | Chapter | ArtifactVersion |
| 5 | 30303 | Chunk | Chapter |
| 6 | 30306 | TranslationArtifactVersion | ArtifactVersion, Dialect |
| 7 | 30305 | TranslationArtifactVersionContributorList | TranslationArtifactVersion |
| 8 | 30308 | TranslationChapter | TranslationArtifactVersion, Chapter |
| 9 | 30307 | TranslationChapterContributorList | TranslationChapter |
| 10 | 30309 | TranslationChunk | Chunk, TranslationChapter |
| 11 | 30311 | Translation | TranslationChunk, TranslationArtifactVersion |
| 12 | 30310 | TranslationContributorList | Translation |

Kind order is not rank order -- 30305 and 30307 are contributor lists that hang
off things numbered above them -- so the rank is a table, not a `sortedBy { kind }`.
Note also that `TranslationChunkEvent` and `TranslationChunkProposalEvent` share
kind 30309; they have identical dependencies, so one rank covers both, and
`applyInnerEvent` dispatches 30309 to the chunk arm regardless.

The same rule the batch signing work landed on -- *the thing being referenced is
signed first* -- and the same reason.

**Pages preserve the rank across the whole chronicle**, not within each page. Page
boundaries fall wherever the byte cap lands.

### Packing

Greedy: serialise, accumulate, cut when the next event would cross either cap.
An event that alone exceeds `MAX_PAGE_BYTES` cannot be chronicled; log it by id and
carry on rather than failing the chronicle. That is a real hole and should be
visible -- but a chapter nobody can chronicle is better than a member who gets
nothing.

---

## Phase 4 -- applying one, and the sweep

**Two days. The phase with the correctness in it.**

### Who applies

A page names its recipient in a `p` tag, and **a device that is not the named
recipient stores the inner event and does nothing else.** It already holds the
work; re-applying would rewrite `marmotGroupEventId` on every one of its rows to
point at a chronicle page rather than at the event that actually introduced it,
which is provenance loss for no gain.

So the `p` tag is an addressing hint and not a secret. Say so where it is
defined. The group can read the page and is welcome to -- it is their own
history. What the tag decides is who *acts*.

### Applying

```kotlin
ChronicleManager.apply(database, chatRoomId, page: MarmotInnerEvent)
```

1. Parse the content array. A page that will not parse is dropped whole.
2. Check both caps.
3. For each payload, in this order and all of it per payload:
   - kind is in the allowlist, else drop and log the id;
   - `isSignedByRoom(payload, chatRoomId)`, else drop and log the id;
   - `applyInnerEvent(...)` with the page's ids, inside `try/catch`.
4. Discard every `ChatMessage` it returns.

**Per payload, not per page.** A forged payload sitting beside honest ones must
cost itself and nothing else -- the same reasoning `MarmotInboundManager` uses
for a forged direct message, and for the same reason: the caller is inside a
transaction and one bad event should not take the room down with it.

**Discard the chat lines.** `ChatMessage` has an `autoGenerate` primary key, so
every applied payload mints a *new* row -- there is no id to dedupe on. An
chronicle that filed them would give the new member a synthetic transcript dated
now, and give them a second one on every re-run of the sweep. The chronicle
restores the work; the conversation is forward secret and stays gone.

`applyInnerEvent` already does its entity upserts internally and merely *returns*
the line for the caller to file, so this is a matter of not calling `upsert`.
No change to `ChatMessage.kt` at all.

### The sweep, and why it needs no table

Pages arrive over relays with no ordering guarantee, so page 3 can land before
page 2 and its chunks have no chapter to hang off yet. Those payloads throw a
foreign key violation, get caught, and are lost -- unless something re-runs them.

Nothing has to be stored for that, because the inbound path already stores every
inner event it decrypts. This is precisely the situation
`FrostSigningManager.replayStoredMessages` is built for, and it takes the same
shape:

```kotlin
database.marmotInnerEventDao()
    .getByChatRoomAndKinds(chatRoomId, listOf(ChronicleEvents.CHRONICLE))
```

Re-apply every stored page for the room, oldest first, after each new page
arrives. Everything in it is an `upsert` keyed on the event id, so a re-run is
free and a converged chronicle costs one no-op pass.

**Progress is falling failures, not rows written.** "Repeat while a pass applies
something new" is the obvious loop condition and it does not terminate: an upsert
succeeds every time, so every pass applies something forever. What strictly
decreases is the number of payloads that threw. A pass that fails fewer than the
last one learned something; a pass that does not is as far as these pages get.

**And the answer is the last pass, not the sum of them.** Accumulating counts a
payload once per pass it survived and reports failures that a later pass went on
to fix, so `failed > 0` stops meaning "still missing" -- which is exactly the
question the caller is asking. Found by asserting that the page completing an
out-of-order chronicle leaves nothing behind, which failed against the sum.

Only the recipient sweeps, which is what bounds it: the members who skip apply
never build the list.

**Test:** a chronicle delivered in reverse page order converges to the same rows
as one delivered in order; a page whose payloads are all already applied changes
nothing; a page containing one forged payload applies the rest.

---

## Phase 5 -- the request, and self-healing

**A day, including one schema change.**

`ChronicleRequestEvent` (30328), sent into the room, content empty.

**When a device sends one.** On entering a room it holds no signed work for --
no `MantraArtifact` and no `MantraDialect` rows -- having processed its Welcome.
That covers the new member, the reinstall and the second device with one rule,
because all three look identical from inside the database, which is the point.

**Who answers.** Any member holding the work. Answering costs bandwidth and
nothing else -- pages are idempotent and non-recipients skip them -- so a
duplicate answer is waste, not damage. A random 0-30 s stand-down, skipped if
another member's chronicle for that request id is already on the wire, is worth
adding and is worth adding *last*: it is an optimisation, and shipping it with
the correctness would make it look like part of it.

### Schema 11 -> 12

One nullable column, so Room generates it:

```kotlin
val chronicleRequestedAt: Instant? = null   // on ChatRoom
AutoMigration(from = 11, to = 12)
```

It stops a device re-requesting on every launch while an answer is in flight.
Rooms written before it read back null, meaning "never asked" -- true of all of
them, and harmless: the request is only sent for a room with no work in it, and
a room that has work will not ask.

Clear it when a chronicle for the room applies anything, so a partial answer is
followed by another request rather than by silence.

---

## Phase 6 -- the push, from the invite

**Half a day.**

Now that the request exists, the push is a latency optimisation and can be
written as one.

`MarmotOutboundDao.deliveryWelcome` is the seam -- both branches of
`inviteMember` reach it, the immediate one and the ack-triggered one in
`DatabaseNostrRepository`. Assemble a chronicle for the invitee there and queue
its pages behind the Welcome.

One thing to be honest about at that call site, in a comment: **queued behind the
Welcome is not delivered after it.** They are different transports -- a
relay-borne gift wrap and a kind:445 -- and a page that arrives before the
invitee has processed their Welcome is from an epoch ahead of theirs, so it is
dropped outright rather than deferred. The request is what recovers that, and
this push is worth having only because it usually wins.

The first draft of this section also said the room must be re-read between the
invite and the assembly, for the same reason sequential invites re-read it. It
does not: that rule is about the MLS snapshot a commit is built on, and
`deliveryWelcome` is downstream of the commit and reads `Mantra*` rows, which no
commit touches.

Nothing here is allowed to report failure to the inviter. A push that does not
land is not an error; it is the ordinary case the pull exists for. It sits inside
`deliveryWelcome`'s own catch for that reason.

**One call, two occasions.** Answering a request and pushing behind a Welcome are
the same operation and differ only in who decided, so they are one function named
for what it does -- `ChronicleManager.sendTo` -- rather than two named for their
occasions.

---

## Phase 7 -- UI

**A day.**

**The transcript gets one line per chronicle**, not one per event. Three types --
`TYPE_CHRONICLE_REQUESTED`, `TYPE_CHRONICLE_SENT`, `TYPE_CHRONICLE_RECEIVED` -- in
`CHRONICLE_TYPES`, with an arm in the transcript that renders them as notices. A
type missing from that set renders as a chat bubble, silently, looking exactly
like a member having said *"Caught up on 12 items"*.

Three decisions inside that:

- **The received line is written when the request stamp is cleared**, which is as
  close to one-per-chronicle as this can get: a chronicle's pages are not
  distinguishable from each other at apply time, and clearing the stamp is
  exactly the moment a catch-up stops being pending.
- **A push behind a Welcome writes no line at all**, because the room was never
  asked. It lands before the member has opened the room, and *"caught up on work
  you have not seen yet"* is a line about nothing.
- **The received line names no sender.** A chronicle can be assembled from pages
  sent by more than one member, so attributing the catch-up to one of them would
  be a guess dressed as a fact.

**Not done, and deliberately.** Two items from this phase's first draft are
left out rather than written blind:

- *A banner on the room saying it is catching up.* Worth having -- the first
  minutes in a new room otherwise look like a group that has done nothing -- but
  it is UI state plumbed through a view model into a layout, and the transcript
  line covers the same ground badly rather than not at all. Do it with the app
  running.
- *A "Send history" action on the member row.* A convenience, not a mechanism:
  both real paths are automatic, so this is for the case the automation misses,
  and it wants a screen to live on.

**Say what the new member cannot do.** Still unwritten, and now down to one
thing rather than two: a chronicle hands its recipient the group's whole signed
record, prose included, and does not make them able to *sign* anything. That is
the sentence a member wants the first time they open a room they were added to
late, and the first thing this will be reported as a bug for.

---

## Phase 8 -- the tests that actually prove it

**A day and a half, and do not skip it.**

**They ran in the phases where the code they test first existed**, the way the
batch-signing note's did, so this section is the index rather than the work.
Every claim below is asserted somewhere; what is here is which claim and where.

**The whole thing, end to end** --
[ChronicleApplyJvmTest](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/ChronicleApplyJvmTest.kt),
over two real databases with the pages carried by hand. The sender's room is
seeded through `ChatMessage.applyInnerEvent` itself, so what is chronicled is what
a member's device really holds rather than rows built to suit the test. The
receiver holds no share, no `DkgSession`, no `FrostSigningSession` and no
`GroupKeyState`, and ends with the sender's rows.

Compared as `(id, author, signature)` per row rather than by count, and then
asserted that every chronicled row is authored by the room and carries a signature.
Counting is not the claim: two databases can hold the same number of artifacts
and disagree about all of them, and a rebuild that lost the group's signature --
or re-authored a row as whoever sent it -- would pass a count and fail the only
thing this is for. The artifact version is the one exception and has to be:
nobody signs it, it is derived from the signed artifact on arrival, which is why
it is not chronicled and why a chapter's foreign key survives anyway.

**The negative one that matters** -- four ways to be a dishonest member in one
page beside one honest dialect: the room's id as author with a made-up signature,
a real quorum of another group, an event edited after signing, and a member's own
rumor, which is what everything on the wire looks like today. The receiver ends
with exactly the honest one. The only way to be dishonest in this harness is to
build the inner event by hand rather than let a device queue it, which is what
this does.

**The replay that must not work** -- a genuine, still-verifying
`GroupKeyStateEvent` in a hand-rolled page. It passes every signature check there
is; the allowlist is the only thing that stops it, and the page has to be
hand-rolled because `ChronicleEvent.build` refuses the kind, which is the outbound
half of the same rule.

**The `toXEvent` round trip per chronicled kind** --
[ChronicleRoundTripTest](../composeApp/src/commonTest/kotlin/com/machankura/dedun/compose/nostr/chronicle/ChronicleRoundTripTest.kt),
against real FROST with no database. This is the one that earned its place: it
found that `MantraArtifact.toArtifactEvent` had the alt tag in the wrong position
*and* omitted the version metadata entirely, either of which produces payloads
every receiver silently drops as forgeries. It also holds the negative -- a
rebuild with the wrong version label fails as a forgery rather than as a mistake
-- and a guard that the case list and `CHRONICLABLE_KINDS` move together.

**And three that were not in the first draft**, each written because a test
passed for the wrong reason or a bound could not fire:

- *A page at exactly `MAX_PAGE_EVENTS` still decodes.* Without it the
  page-over-the-cap test passes while the count cap is unreachable behind the
  byte cap, which is how it was first written.
- *Pages delivered backwards really did fail first.* Otherwise "out of order
  converges" would pass on a chronicle that was never out of order, and the sweep
  -- the only reason it converges -- would be untested.
- *The page that completes a chronicle leaves nothing behind.* This is what caught
  the sweep returning the sum of its passes rather than the settled one, which
  made `failed > 0` stop meaning "still missing".

---

## Phase 9 -- rollout

**No code, and one constraint that is sharper than the first draft said.**

The receiving half is safe to ship on its own, and phases 1-4 are exactly that:
nothing sends a chronicle until Phase 5 asks for one. That is the half to have in
the field first.

**Sending into a group with an old build is not free.** The first draft said an
old build "files it as unsupported, exactly as it does today for anything it does
not know", which is true and reads better than it lives. The unsupported row's
content is `event.toJson()`, and it renders as an ordinary chat bubble -- so
every member on an old build sees each chronicle page as a raw-JSON bubble of up to
`MAX_PAGE_BYTES`, in a transcript, once per page.

Nothing breaks and nothing is lost. But a group mid-upgrade gets a genuinely
unpleasant transcript, and that is worth knowing before the first chronicle goes
out rather than after. The rule:

> Confirm every member is on a build that understands kind 30327 before any
> member starts sending. There is no negotiation for this and adding one is not
> worth it -- the cost of getting it wrong is ugly rather than dangerous, and it
> stops as soon as they upgrade.

The mitigation, if that ever proves unacceptable, is the one the appendix rejects
for other reasons: carrying pages as Marmot direct messages, where an old build
sees a gift wrap it cannot open and renders *"sent a private message"* with no
content. It buys graceful degradation and costs everything listed under
[Carrying the chronicle as a Marmot direct message](#appendix--what-was-considered-and-rejected).

---

## Retiring the rebuild

`assemble` reads `GroupSignedEvent` and rebuilds from `Mantra*` rows only what
that table does not hold, which by now is work signed before the table existed.
Everything on the rebuild side is marked `@Deprecated` so the compiler names it
at every call site, and it comes out in one piece rather than a method at a time
-- a half-removed rebuild is a rebuild that covers some kinds and silently drops
others.

### The one precondition

**No install still holds work signed before schema v13.** Nothing in the app can
check this, and no query answers it: a device that upgraded is indistinguishable
from one that never had pre-v13 work, and the rows that need rebuilding are on
*other people's* devices. It is a judgement about the installed base, not a
condition to test for. What can be checked, on any given device, is that the
rebuild is contributing nothing:

```
Chronicling <room>: N event(s) as the group signed them, M rebuilt from rows that predate the record
```

`ChronicleManager.signedEventsOf` logs that line only when `M > 0`. Silence across
the fleet is the evidence; it is not proof.

A member whose device still needs it and does not get it is not broken loudly.
They keep their own rows and read the room normally. What they lose is the
ability to *answer* a chronicle request with the older half of the group's work,
so a newer member asks, is answered, and receives a chronicle that is quietly
short. That is the failure mode to weigh -- it looks like success on both ends.

### What goes

| what | where |
|---|---|
| `rebuiltEventsOf` | [ChronicleManager.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/ChronicleManager.kt) -- the tree walk, and the version-label recovery inside it |
| the union in `signedEventsOf` | same file -- it collapses to the `GroupSignedEvent` read plus the `isChroniclable` filter, which **stays**: see below |
| `MantraDialect.toDialectEvent` | [MantraDialect.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraDialect.kt) |
| `MantraArtifact.toArtifactEvent` | [MantraArtifact.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraArtifact.kt) -- and with it the `versionLabel` parameter that exists only because the label is not on the row |
| `MantraArtifactVersion.toArtifactVersionEvent` | [MantraArtifactVersion.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraArtifactVersion.kt) |
| `MantraChapter.toChapterEvent` | [MantraChapter.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraChapter.kt) |
| `MantraChunk.toChunkEvent` | [MantraChunk.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraChunk.kt) |
| `MantraTranslationArtifactVersion.toTranslationArtifactVersionEvent` | [MantraTranslationArtifactVersion.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraTranslationArtifactVersion.kt) |
| `MantraTranslationChapter.toTranslationChapterEvent` | [MantraTranslationChapter.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraTranslationChapter.kt) |
| `MantraTranslationChunk.toTranslationChunkEvent` | [MantraTranslationChunk.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/MantraTranslationChunk.kt) |
| `ChronicleRoundTripTest`, all ten cases | [ChronicleRoundTripTest.kt](../composeApp/src/commonTest/kotlin/com/machankura/dedun/compose/nostr/chronicle/ChronicleRoundTripTest.kt) -- it exists to hold the rebuild up and covers nothing else |
| the "Where the events come from" reasoning above | this file |

**Two already-dead cousins to sweep at the same time**, neither of which is part
of the rebuild and both of which will look like it to whoever does the removal:
`MantraTranslation.toTranslationEvent` (nothing has ever called it -- 30311 is
not chroniclable and nothing builds one) and
`MantraTranslationChunkProposal.toTranslationChunkEvent` (on a model that is not
even a `@Database` entity). See [dead-code.md](./dead-code.md) for the house
style on writing those down rather than deleting them blind.

**The tests that seed without recording go too**, or they go on proving a path
that no longer exists. In `ChronicleAssemblyJvmTest` the `apply`-only seeding is
the rebuild path and `recordEverythingApplied` is the real one; the cases named
*work held both ways travels exactly once* and *an artifact the rebuild has to
leave out still chronicles from the record* are about the union specifically and
have no meaning without it. `ChronicleApplyJvmTest` seeds the sender the same way,
so it needs the recording call added rather than removed -- it is testing
delivery, not assembly, and would otherwise start asserting against an empty
chronicle.

### What only looks like it goes

**The `isChroniclable` filter in `signedEventsOf` stays, and becomes the only
thing standing.** It is not part of the rebuild; it is there *because* of the
record. The rebuild could only ever produce document kinds, so nothing needed
filtering while it was the source. The table holds every kind the group has
signed, so removing that filter along with the walk turns a chronicle carrying
any of them into an `IllegalArgumentException` from `ChronicleEvent.build`. Two
cases in `ChronicleAssemblyJvmTest` fail with exactly that if it is dropped,
which is the guard against removing it by association.

What it is really stopping is a member replaying a group-signed statement *about*
the record as though it were work. `applyPage` refuses the same kinds on the way
in; the two are a pair, and neither is safe to drop on the strength of the other.

The example used to be a room's own key state, which every room signed as its
first act. That is no longer where one lives -- a group agrees its key state
before the room exists and files it under the NIP-17 room its ceremony ran in --
so the filter now guards a case that has to be reasoned about rather than one
every room walks into.

**The verify filter in `assemble` stays.** With the rebuild gone it is checking
events that were verified before they were recorded, so it can never fail in
practice -- which is an argument for keeping it, not for dropping it. It is one
signature check standing between a corrupted row and a payload every receiver
reads as a forgery, and "cannot happen" is the state it is meant to preserve.

**`Mantra*.signature` and `Mantra*.publicKey` are not obviously removable, and
are a separate decision.** They were what made a row rebuildable, but they are
also what `SignedArtifactTest`, `SignedChapterTest`, `SignedGroupKeyStateTest`
and `ChronicleApplyJvmTest.rowFingerprints` assert on, and
`MantraTranslationContributor` builds a contributor list out of one. Since v13,
`groupSignedEventId` says whether the group signed a row and points at the proof,
so the columns are arguably redundant -- but that is a schema migration across
twelve tables with its own tests to rewrite, and it should not ride along with
this.

---

## What this does not do

Each of these will be reported as a bug. None of them is.

**A new member still cannot sign, and a chronicle cannot change that.** This is
the big one. `proposeSigningBatch` resolves a `DkgSession` with a non-null
`secretShare` and then `signerIdOf`, or throws *"This device is not a participant
in ceremony ..."*. `GroupKeyState` states it plainly: *"A member can be in the
room without holding a share -- they were added after the ceremony, or
reinstalled -- and the state is still worth keeping: it says what the room signs
with, which is what tells them they cannot."*

Re-running the ceremony is not an escape either: *"a group that re-runs its
ceremony derives a different room rather than re-keying this one."* A post-chronicle
member can read everything and can still submit what needs no quorum --
`saveTranslation` and `addArtifactVersion` go through `MantraDao.submitToGroup`
with no share -- but cannot add a dialect, artifact, chapter or translation
version, and cannot sign anyone else's.

Closing that needs share resharing on the threshold key: a t-of-n key issuing a
share to a new participant without changing the public key it derives from.
It is a real protocol, it is a great deal more work than this document, and it is
the thing to build after this one.

**A chronicle can omit.** Verification stops forgery and does nothing about
silence: a sender can leave things out, and the receiver has no way to know. Any
member can send one and they merge idempotently, so asking a second member is the
practical answer, and a group that suspects one member is not the threat model
this app is otherwise built for. Making omission *detectable* needs a manifest of
ids that the group signs periodically -- one quorum, cheap, and rejected as the
general answer for the reason
[frost-batch-signing.md](./frost-batch-signing.md#appendix--what-was-considered-and-rejected)
gives for manifests. Worth revisiting once anything depends on completeness.

**Nothing unsigned is chronicled, and that is the whole list.** For a while it read
larger: the translated text was its author's rumor and an artifact's first
version was derived rather than signed, so neither could travel and a new member
got the structure and none of the prose. Both are signed now. What is left out is
`TranslationEvent`, which nothing builds, and the contributor lists, which
nothing applies -- so the rule and the list have stopped diverging, and the thing
to watch is that they do not drift apart again. The guard is
`ChronicleRoundTripTest`, which fails when a kind is added to the allowlist without
a case proving it can be rebuilt.

**The chat is gone and stays gone.** By design, restated here because it is the
first thing a new member will notice and the chronicle is what makes them expect
otherwise.

**A room with no shared key gets an empty chronicle.** An ordinary Marmot room's id
is `RandomInstance.bytes(32)`, not a derived key, so nothing can be signed by it
and there is nothing to chronicle. Correct, and worth a log line rather than a
silent empty result.

**An oversized single event cannot be chronicled.** A chapter whose text exceeds
`MAX_PAGE_BYTES` on its own is skipped with a log. Splitting a page mid-event
means a reassembly protocol, and that is not worth building before something hits
the limit.

**Nothing expires.** A chronicle grows with the group forever, and a member
joining a five-year-old room downloads five years. A cursor -- *everything since
event X* -- is the obvious next thing and is deliberately not in v1, because
"since" is a partial order over a dependency graph, not a timestamp, and getting
it wrong means a chronicle that references rows the receiver does not have.

---

## Appendix -- what was considered and rejected

**Re-sending the FROST session instead of the event.** Give the new member the
`FrostSigningSession` and its items and let them derive the signed events the way
everyone else did. It works and it is strictly worse: it ships nonce seeds and
signer sets to somebody who has no business holding them, to reconstruct an event
that could simply have been sent.

**Publishing signed events to relays.** They are already signed by a key anyone
can verify, so a relay could hold them and a new member could fetch them with an
ordinary REQ on `authors: [chatRoomId]`. Rejected, and it is the tempting one: it
would make the group's work public. Every artifact, chapter and chunk a private
group has agreed becomes readable by anyone who knows the room id -- and the room
id is in the `h` tag of every kind:445 the group has ever sent. A separate,
deliberate publication step for work a group *chooses* to publish is a good
feature; making it the backfill mechanism is a leak.

**One `SubmissionEvent` per chronicled event.** Covered in Phase 2. The envelope
fits and the meaning does not.

**Carrying the chronicle as a Marmot direct message.** The natural reading of "send
it to the new member" -- an NIP-59 wrap inside the group, per
[marmot-direct-messages.md](./marmot-direct-messages.md). Rejected: it encrypts
the group's own history to one member, which protects nothing; it costs a *"sent
a private message"* line per page in everyone's transcript; and its inner layers
are not forward secret, so it would be the weakest-protected copy of the group's
record on any device holding it. The `p` tag as a hint gets the addressing
without any of that.

**A dedicated table for unapplied chronicle payloads.** Phase 4's sweep reads
`MarmotInnerEvent`, which already holds every page. A second copy is a second
thing that can disagree with the first.

**Pushing on invite only.** The design that was asked for, and it works right up
until the epoch race in
[marmot-membership.md](./marmot-membership.md#why-this-fails-silently) -- where
it fails silently, looks like a successful invite, and leaves a member with a
room full of nothing. Kept as Phase 6, on top of the pull that makes it safe.
