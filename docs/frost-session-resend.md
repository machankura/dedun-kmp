# Resending a signing session

A proposer can ask the group to sign something and never hear back from a member
who would have agreed, because the message asking them never arrived. Today there
is nothing to do about it. The session sits in `COLLECTING_NONCES` until somebody
abandons it and proposes afresh, and the member it was waiting on never knew they
were being waited on.

This is the plan for a resend: the proposer re-publishing what they authored for a
session, and — the half that is not obvious — the silent member republishing what
*they* authored, because the proposer cannot do it for them and must not be able to.

Read [`docs/mls-skipped-keys.md`](mls-skipped-keys.md) first. It documents the
failure that makes this necessary and ends with the sentence this plan is a reply
to: *"nothing asks the sender to resend."*

## The problem

"Missed it" is five different things, and only some of them are a resend's business.

| why a member has nothing | does a resend help? |
|---|---|
| offline, and the relay still holds the kind:445 | no — ordinary sync catches up |
| **MLS skipped keys**: the group event decrypted to nothing | **yes, and nothing else does** |
| NIP-40 expiration: a disappearing-message group dropped it off the relay | **yes, and nothing else does** |
| the member's read relays never had it | **yes** |
| the member was not in the room when it was sent | **yes** |
| the session already failed | no — `fail` already says "it is safe to ask again" |

The second row is the one that forces the feature. From the skipped-keys doc:

> the key is derived from a ratchet that has moved past it and cannot be
> recovered

Terminal at the receiver, invisible to the sender, and the doc's own worked
example is this exact flow — a FROST proposal lost to it, leaving the proposer's
nonce filed against a session that will never exist. `MlsGroupCache` narrows the
window; it does not close it, and a restart or another writer reopens it.

A resend fixes it because a resent message is a *new* application message at a
*new* generation. The old generation's key is gone; the new one is derivable. The
same is true of the expiration case: a fresh event carries a fresh expiry.

## What is safe to resend, and what is not

Every kind in a session has a direction, and the direction decides whether a
resend is even meaningful.

| kind | author | who must actually receive it | proposer can resend? |
|---|---|---|---|
| 30320 `PROPOSAL` | proposer | every share holder | **yes** |
| 30321 `NONCE` | a signer | **only the coordinator** | no, and not needed |
| 30322 `SIGNER_SET` | coordinator | the chosen signers | **yes** |
| 30323 `PARTIAL_SIGNATURE` | a signer | **only the coordinator** | no, and not needed |
| 30324 `SIGNATURE` | coordinator | every share holder | **yes** |
| 30325 `FAILURE` | anyone | everyone | n/a — the session is over |
| 30330 `DECLINE` | a share holder | every share holder, for the count | no — see below |

The third column is read off the code, not guessed. `orderedNonces` and
`orderedPartialSignatures` are both called under `session.isCoordinator()`;
nothing else in `advance` reads another member's nonce or partial. A non-coordinator
device uses other members' nonces for exactly one thing — the "Ready to sign" row
in `FrostSigningUIState.offeredNonce` — which is cosmetic.

**So the parenthetical in the request is the right answer.** A nonce is a signal only
the proposer needs, and the proposer already has it, which is how they know the
member is not silent. There is nothing to resend.

### Receiver-side idempotence already holds

A resend is only cheap because every arm of `record` is already write-once. This is
an audit of the gates, not a wish list; each one is a line that exists today.

- `PROPOSAL` → `acceptProposal` finds the session, compares the proposed ids against
  the stored `FrostSigningItem.eventId`s, finds them equal, returns the existing
  session. No write, no log line.
- `SIGNER_SET` → `isFromCoordinator`, then `if (current(database, session).signerIds != null) return true`.
- `SIGNATURE` → `if (isSigned(database, session)) return true`.
- `NONCE` / `PARTIAL_SIGNATURE` → upsert keyed on `(sessionId, signerPublicKey, kind)`;
  `known` is true, so no second transcript line.
- `DECLINE` → `recordDecline` recomputes a `toSet()` count; `fail` has its own
  already-settled guard.

`publishOwn` already states the policy this plan generalises:

> A crash between the two therefore costs a duplicate broadcast — which every
> receiver folds away

**No protocol change is required for the proposer to resend its own messages.** That
is the whole of Phase 2.

### Why the proposer cannot resend another member's message

Three reasons, in increasing order of seriousness.

**It would be filed under the wrong author.** `record` writes
`FrostSignerMessage(signerPublicKey = innerEvent.pubKey, …)`. A forwarded copy is
authored by the forwarder, so a nonce the proposer relayed on Bob's behalf is filed
as the *proposer's* nonce, overwriting their own. On Marmot there is no way around
this: MIP-03 rejects an inner event whose `pubKey` is not the MLS sender's identity,
and `docs/marmot-direct-messages.md` describes the single carve-out that exists and
why it is narrow.

**For a decline it is a forgery primitive.** `recordDecline` counts distinct
`signerPublicKey`s against `declinesThatAbandon()`. A proposer who could relay a
decline under their own key would be able to manufacture refusals; in an n-of-n
group one is enough to abandon a session nobody refused. Forwarding would have to
carry a signature over the original, verified before counting — a new envelope kind,
new verification code, and a new way to get it wrong, to deliver a message that
nobody but the coordinator needed in the first place.

**For the proposal it is already load-bearing.** `acceptProposal` sets
`coordinatorPublicKey = innerEvent.pubKey` for a session it has never seen. A
non-coordinator who resends the proposal to a member with no session row makes that
member believe the *resender* coordinates — and that member will then drop the real
coordinator's signer set through `isFromCoordinator`. **Resend is coordinator-only,
and the restriction is correctness rather than etiquette.** (A member could already
mint a proposal under someone else's session id today; resend does not widen that,
but it must not make it a routine code path either.)

## The half the request is actually about

The request asks whether the proposer needs to resend a member's availability
signal. It does not, but there is a real gap hiding behind the question, and it is
the mirror image:

> A nonce lost on the way to the proposer is a permanent stall, and no device will
> ever republish it.

`advance` republishes a message it has **no local copy of** — `if (ownNonces == null)`.
Bob's device, having published and recorded its nonce, will never send it again,
however long the proposer waits. The member who can fix it is Bob, and Bob has no
way to know.

So the resend has two halves:

1. the proposer republishes what the proposer authored (Phase 2), and
2. the resent proposal **names the members the proposer has not heard from**, and a
   named device republishes what *it* authored (Phase 3).

Half 2 is what the request is reaching for, reached from the only side that can
author the message. It needs no forwarding, no new envelope and no signature
checking, because every message still travels under the key that made it.

## Phase 1 — a tag for the silent set

`FrostSilentMembersTag`, in `nostr/frost/tags/`, following `FrostSignerIdsTag`
exactly: a `TAG_NAME` const, `parse`/`assemble`, and all-or-nothing parsing because
a partially-read list is a list naming the wrong people.

```kotlin
const val TAG_NAME = "frost_silent"
```

Hex pubkeys, comma separated, in `tag[1]`. An entry that is not 64 hex characters
invalidates the whole tag.

`FrostSigningEvents.assembleTags` grows a fourth optional parameter:

```kotlin
fun assembleTags(
    sessionId: String,
    dkgSessionId: String? = null,
    signerIds: List<Int>? = null,
    silentMembers: List<HexKey>? = null
): Array<Array<String>>
```

and a `parseSilentMembers` beside the other three parsers. `broadcast` gains a
matching optional parameter and passes it through.

**Why a tag on the proposal rather than a kind of its own.** A bare nudge reaches a
device with no session to apply it to and is dropped —
`getSessionById` returns null and `processSigningPayload` logs *"leaving it stored"*.
That is exactly the device we are trying to reach. The member who is silent is
overwhelmingly silent *because* they never got the proposal, so the nudge and the
thing they are missing have to travel in one message or the common case is not
fixed. See the appendix for the kind-30331 version and why it loses.

**It is invisible to the id check.** `acceptProposal` rebuilds the signed events from
`innerEvent.content`; the carrier's tags are not part of any id. Adding a tag cannot
change what a signer is putting their share behind.

**It leaks nothing.** On Marmot every nonce is a group event the room already saw;
on NIP-17 every member is p-tagged on every message. Who has signalled is not a
secret the tag gives away.

**The silent set comes off the ceremony, not the room.** `signerIds(database, key).keys`
minus everyone with any `FrostSignerMessage` for the session — the same source
`recordDecline` counts against and `observeShareHolders` draws. The room's roster is
wrong twice over, for the reasons `FrostSigningUIState.Loaded.members` already gives:
it can hold members added after the key was made, and a subgroup's ceremony room is
the parent.

## Phase 2 — the proposer resends what it authored

```kotlin
suspend fun resend(
    database: DedunDatabase,
    localChatRoom: LocalChatRoom,
    sessionId: String
): Boolean
```

Returns whether anything went out, so the caller can say so.

**Refused unless it is this device's session to resend.** `session.isCoordinator()`,
for the `acceptProposal` reason above. Refused for `FAILED` — nothing can be
resent into a dead session and `fail` already tells the group to ask again — and
refused for a session whose items are empty.

**It sends the minimum the stage requires**, which is at most two events:

| stage | what goes out |
|---|---|
| `COLLECTING_NONCES` | the proposal |
| `COLLECTING_PARTIAL_SIGNATURES` | the proposal, then the signer set |
| `COMPLETE` | the proposal, then the signature |
| `FAILED` | refused |

The proposal goes in every case because a device with no session row cannot apply
anything else: a signer set naming ids it has no items for, or a signature over
events it has never seen, are both dropped. `COMPLETE` is worth resending rather
than skipping — a member who never received the signature never ran `complete`, so
the signed events were never applied to their local store.

The proposal's content is `FrostSigningEvents.encodeProposal` over the stored
items' `unsignedEventJson`. Re-serialising is safe: the receiver compares rebuilt
ids, and an id is over the canonical form, so any JSON with the same fields hashes
the same. A batch of one must still go out as a bare object — `encodeProposal`
guarantees it, and `decodeProposal` dispatching on a leading `[` is why it matters.

### Two hazards worth naming before writing the code

**The id collision.** `broadcast` derives the queue row's primary key from
`EventHasher.hashId(pubKey, createdAt, tags, content, kind)` with `createdAt` in
whole seconds. A resend whose content, tags and second all match the original
produces the **same id**, and `@Upsert` then replaces the sent row with an
unsent one: `marmotGroupEventId` goes back to null, the already-delivered event is
queued a second time, and its mapping to the group event it became is lost. The
cooldown in Phase 4 makes this improbable; it must not be the only defence.
`broadcast` should refuse to overwrite a row whose `marmotGroupEventId` is already
set, and the resend path should carry `createdAt` forward a second at a time until
the id is unused. Worth a test of its own.

**The burst.** Resending two events back-to-back recreates precisely the condition
the skipped-keys bug needs. `MlsGroupCache` covers a burst decrypted in one sync,
which is what makes `proposeSigningBatch`'s own two-message burst work today, so
the mitigation is the existing one — send in protocol order, in one pass, and do not
add a third event. It is the strongest argument for the stage table above being a
minimum rather than "send everything".

### Transport

`broadcast` already knows the difference and needs no new branch for Marmot: one
group event reaches everybody, and every receiver folds the duplicate away, which
is cheaper than one message per silent member and tells the room nothing it does not
already know.

NIP-17 names everybody too, and the reason is worth getting right because the
first version of this got it wrong. **On that transport the p-tag set is the
room's identity, not a recipient list.** A receiver works out which room a payload
belongs to by hashing the sender together with the p-tags —
`GiftWrapPayload.aggregatedParticipantsPublicKey`, via `ChatRoom.deriveChatRoomId`
— so leaving a member out changes the id, and the resend is filed under a room
that does not exist. `getOrCreateChatRoom` then mints that room rather than
rejecting it, which turns a repaired session into a phantom conversation in the
reader's list.

There is no saving to trade against that, either: `sealGiftWrapPayload` wraps for
every participant of the room it looks up, not for the p-tags it was handed, so a
narrowed payload costs exactly as many wraps as a full one. Who a resend is *for*
travels in `frost_silent`, which is not part of the hash. This is the first
signature a group makes, on gift wraps, per `GroupKeyStateManager.propose`.

## Phase 3 — a named member republishes what it authored

In `processSigningPayload`, after the session is in hand and before `advance`:

```kotlin
if (FrostSigningEvents.parseSilentMembers(innerEvent.tags)
        ?.contains(session.userPublicKey) == true &&
    isFromCoordinator(session, innerEvent)
) {
    republishOwn(database, localChatRoom, session)
}
```

`republishOwn` re-broadcasts this device's own stored `FrostSignerMessage` rows for
`NONCE`, `PARTIAL_SIGNATURE` and `DECLINE` — **the stored bytes, never regenerated**.
They are byte-identical by construction, so they cannot produce a second aggregate,
and reading the row avoids k native `SecretNonce.generate` calls to arrive at the
same answer.

Three constraints, each of which is a rule that already exists somewhere else:

**Only from the coordinator.** A member who could name another member as silent could
make them republish on demand. It changes nothing on the wire — the bytes are the
same ones already sent — but it is a publish anybody could trigger, and that is not
a thing to leave lying around.

**Nothing new is published.** `republishOwn` sends only what is already on file. A
device with no `FrostSignerMessage` of its own sends nothing and falls through to
`advance`, which does what it has always done: ask whether the owner has decided,
and if they have not, write the approval request and stop. **A message from the
proposer must never be able to talk a device into publishing a nonce its owner has
not approved** — that is `acceptProposal`'s "Publishes nothing" rule, and the gate
that enforces it is `advance`'s `if (session.signApprovedAt == null)`, untouched.

**Once per delivery.** A redelivered resend has the same inner-event id and is
deduplicated upstream; a *second* resend from the proposer is a new id and will
republish again, which costs a duplicate everybody folds away. The Phase 4 cooldown
bounds how often that can happen.

### What this fixes, concretely

- Bob approved, published a nonce, and the proposer never got it. Bob is named,
  republishes the same bytes, and the proposer's `orderedNonces` finds a quorum.
- Bob approved and his device died before `publishOwn` queued anything. Today the
  resent proposal alone already fixes this, because `acceptProposal` → `record` →
  `advance` re-enters the `ownNonces == null` branch. Worth knowing: part of the
  fix is free.
- Bob declined and the refusal was lost. He republishes it, and every device's count
  converges on the same arithmetic instead of waiting on a member who has answered.
- Bob never saw the proposal at all. He sees it now, and `announceApprovalNeeded`
  puts the request in his transcript.

## Phase 4 — schema 24 → 25: `resentAt`

*Landed with Phase 2 rather than after it: `resend` needs the column for its
cooldown, and writing it twice to keep the phases apart would have been
ceremony.*

A nullable `Instant?` on `FrostSigningSession`, written by `resend`:

```kotlin
/**
 * When the coordinator last re-published this session, or null if never.
 *
 * Two jobs. It is the cooldown -- a resend is a publish, and a button that
 * publishes on every tap is a button that fills a room with duplicates. And it
 * keeps a second resend from landing in the same second as the first, where the
 * queue row's id is a hash over `createdAt` and a collision un-sends the
 * message it collides with.
 */
val resentAt: Instant? = null
```

`AutoMigration(from = 24, to = 25)` with the comment the others carry, and the
generated `composeApp/schemas/25.json` committed. Nothing is backfilled: a session
that has never been resent reads null, which is what every session before the column
was.

A cooldown of five minutes, as a `const` on the manager beside `MAX_BATCH_SIZE`,
with the reasoning on it. `resend` returns false inside the window rather than
throwing; the screen disables the button and says when it may be used again.

The alternative — deriving the last resend from the `TYPE_FROST_RESENT` transcript
row — needs no migration but makes the manager read the chat table to decide a
protocol question, and makes a user-deletable row load-bearing. The column is
cheaper than that.

## Phase 5 — the transcript

`ChatMessage.TYPE_FROST_RESENT = "frostResent"`, added to the frost block and to
`FROST_TYPES` (whatever the rendering set is called where `TYPE_FROST_DECLINED`
appears), with the house comment explaining why it is its own type: it is not a
protocol step, it is the proposer knocking, and the ladder should show that the
knock happened so a second one is visibly a second one.

The line is the `announce` shape the other frost lines use, with the actor as the
coordinator:

> asked the 2 members who have not answered to take another look.

Named rather than counted when there are few, for the reason `FrostSigningScreen`
already gives — *"1 of 2" does not tell anyone whose door to knock on*. Written
once per resend, which `resentAt` makes true.

A resent message must **not** write a second step line. That is already how it
works: `announceStep` is called from `publishOwn` and `record` only when `known` is
false, and `resend` calls `broadcast` directly rather than `publishOwn` — which is
also why `resend` must not be routed through `publishOwn`, whose whole job is to
record a local copy that already exists.

## Phase 6 — repository and view model

`FrostSigningRepository` grows one method, documented with the directionality that
makes it one method rather than two:

```kotlin
/**
 * Re-publishes the messages this device authored for a session, for the members
 * it has not heard from.
 *
 * Coordinator only, and a resend of *this device's* messages only. A member's
 * nonce is read by nobody but the coordinator, who already has it; and a message
 * relayed by somebody else would be filed under the relayer, which for a refusal
 * is a way to manufacture one. What the resend does for a silent member is name
 * them, so their own device republishes what it already said.
 *
 * Returns false when there was nothing to do: not the coordinator, already
 * failed, nobody silent, or inside the cooldown.
 */
suspend fun resend(localChatRoom: LocalChatRoom, sessionId: String): Boolean
```

`NO_OP_FROST_SIGNING_REPOSITORY` returns false. `DatabaseFrostSigningRepository`
delegates to the manager, as its other methods do.

`FrostSigningUIState.Loaded` gains two derived values rather than two fields:

```kotlin
/** Share holders this session has heard nothing from. Whose door to knock on. */
val silent: Set<HexKey> get() = shareHolders - offeredNonce - signed - declined

/** Whether this device may knock: it proposed, the session is open, somebody is quiet. */
val canResend: Boolean get() = …
```

`silent` is already computable from what the view model collects — `offeredNonce`,
`signed`, `declined` and `shareHolders` are all there — so the screen needs no new
flow. `FrostSigningViewModel` gains a `resend(onDone:)` on the `approve`/`decline`
pattern, guarded by the same `isActionPending`.

## Phase 7 — UI

The affordance belongs on `FrostSigningScreen`, below the member list and above the
approval block, in a section the coordinator alone sees. The member list is already
the screen's answer to "whose door to knock on"; this is the knock, and it goes
where the doors are.

```kotlin
if (state.canResend) {
    HorizontalDivider()

    Text(
        text = stringResource(Res.string.these_members_have_not_answered),
        style = MaterialTheme.typography.bodySmall
    )

    Button(
        enabled = !frostSigningViewModel.isActionPending.value,
        onClick = { frostSigningViewModel.resend { notify(resent) } }
    ) {
        Icon(Icons.Default.Send, contentDescription = Decorative)
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
        Text(stringResource(Res.string.ask_again))
    }
}
```

Per `CLAUDE.md`, and each of these is enforced by `./gradlew :composeApp:m3Audit`:

- strings in `composeApp/src/commonMain/composeResources/values/strings.xml`, in
  **sentence case**: `asked_again`, `one_member_has_not_answered`,
  `members_have_not_answered`, `asked_again_recently`,
  `everyone_has_answered_this_session`. The count interpolates as `%1$s`, which
  is what the catalogue's other counted strings use. `ask_again` was already
  there for re-querying a list's relays and means exactly this, so it is reused
  rather than duplicated — two keys with one label is how a translator comes to
  pick two different words for one idea.
- spacing from `MaterialTheme.spacing`; no `.dp` literal in a spacing position.
- the icon is beside its label, so `contentDescription = Decorative` — decided, not
  `null`.
- the outcome goes through `rememberNotifier(rememberCoroutineScope())`, with the
  `stringResource` read **above** the handler, since `onClick` is not composable.
- no new state branch: the screen keeps its four, and this sits inside `Loaded`.

`ProposalListScreen` gets nothing. A resend is a decision about one session made
while looking at who has answered, and the list shows neither.

Within the cooldown the button is disabled with a supporting line saying so, rather
than hidden — a control that vanishes after a tap reads as a control that failed.

## Phase 8 — the tests

`FrostSigningResendJvmTest`, on `FrostSigningDeclineJvmTest`'s harness: a 2-of-3
`Frost.trustedDealerKeygen`, a room at `SharedKeyDerivation.marmotGroupId`, and its
`openDevice` / `propose` / `proposalArrivedAt` / `receive` / `queued(kind)` helpers.
That test is already a two-device simulation over one real database, which is what
this needs.

The properties, each one a thing that would otherwise be found in a group:

1. **A resend reaches a device that missed the proposal.** Propose on Alice, never
   deliver, resend, deliver the resent proposal to Bob: Bob has a session and an
   approval request. This is the skipped-keys case.
2. **A resend is idempotent on a device that has the session.** Deliver the proposal,
   approve, then deliver the resend: the item ids are unchanged, no second
   transcript line, no second nonce row, and `signerIds` unchanged once set.
3. **A named member republishes their nonce.** Bob approves, his nonce is dropped,
   the proposer resends naming Bob, and Bob's device queues the **same payload** a
   second time. Assert byte equality against the first — a different nonce for the
   same message against two aggregates is the share extraction the whole design
   forbids.
4. **A named member who has not decided publishes nothing.** The strongest test here.
   Bob is named without having approved: nothing is queued, and
   `isAwaitingApproval` is still true.
5. **A named member republishes their refusal**, and counts converge.
6. **A non-coordinator cannot resend**, and a resend tag from a non-coordinator is
   ignored.
7. **A resend of a `FAILED` session is refused**, and nothing is queued.
8. **Two resends in the same second do not collide.** Two ids, both rows unsent, the
   first not reverted. The hazard from Phase 2.
9. **The cooldown holds**: the second call inside the window returns false and queues
   nothing.

A pure `commonTest` for `FrostSilentMembersTag` round-tripping, beside
`FrostProposalCodecTest`: an empty list, a malformed entry invalidating the whole
tag, and an unknown tag surviving `assembleTags`/`parse` untouched.

Then the project's two gates:

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

One unrelated 5-second-timeout failure per full `jvmTest` run is a known flake;
re-run the suite alone before investigating.

## Rollout and compatibility

Nothing here is a wire break, and that is by design.

**A build predating this plan receiving a resent proposal** reads an unknown
`frost_silent` tag, ignores it, and runs `acceptProposal` — which either creates the
session it was missing or returns the one it has. **The half that matters works on
old builds without a change.** Only the republish-when-named half needs both ends.

**A build predating this plan receiving a resent signer set or signature** applies
them if it was missing them and hits its own write-once gate if it was not. Both
behaviours are today's.

**An old coordinator** never resends, which is today's behaviour exactly.

The one ordering constraint: Phase 1's tag must ship no later than Phase 2, because
a resent proposal with no silent tag is a resend whose second half silently does
nothing. Phases 4 through 7 can land after, and Phase 8's tests should land with the
phase they test rather than at the end.

### What landing it turned up

Two things worth recording, both of them in `broadcast` and neither of them about
resend as such.

**The id collision was real, and the guard needed a second half.** `broadcast`
now steps past a taken id rather than upserting over it, and returns whether it
queued anything. That return is load-bearing: `publishOwn` writes the local copy
that stops [advance] from publishing a message again, so a `broadcast` that
queued nothing must record nothing, or the group waits forever on a message that
never went out. The comment on `publishOwn` had the trade written down in one
direction only.

**Narrowing the recipients is not something this transport allows, and the test
meant to prove it instead encoded the bug.** A resend was narrowed to the quiet
members, with a passing test asserting the p-tags were narrowed. Both the code and
the test were wrong about what a p-tag is for: on NIP-17 the set is hashed into
the room id a receiver files the payload under, so a narrowed resend would have
landed in a room nobody is in — and `getOrCreateChatRoom` would have created it.
Found by asking whether the change was backwards compatible. It was; this was
never a compatibility bug, it broke old and new receivers identically.

The lesson is narrower than "assert behaviour, not bytes". The p-tags *are* the
right thing to assert — they just mean something other than what they look like,
and the assertion needed to be that they do not change.

## What this does not do

- **It does not retry automatically.** See the appendix.
- **It does not resend another member's message**, and cannot be extended to without
  a signed forwarding envelope. The appendix says why that is not worth building.
- **It does not recover a failed session.** A retry is a new batch, always:
  `proposeSigningBatch`'s own notes say why, and `itemsOver` mints fresh nonce seeds
  so a retry is a new session by construction.
- **It does not change the quorum arithmetic.** A resend publishes nothing new and
  counts nothing twice; `declinesThatAbandon` is untouched.
- **It does not help a member who holds no share.** They were never in the session.
  `signerIds(database, key)` is the list, for the reason
  `FrostSigningUIState.Loaded.members` gives.
- **It does not fix the skipped-keys bug.** It is a recovery for it, driven by a
  person who noticed. The fix is in quartz, and `docs/mls-skipped-keys.md` has it.

## Appendix — what was considered and rejected

**A resend kind of its own (30331).** Cleaner to read, and it could nudge without
re-sending a proposal carrying up to `MAX_BATCH_SIZE` events. It loses on the common
case: a bare nudge arriving at a device with no session for it is dropped —
`getSessionById` returns null, `processSigningPayload` logs *"leaving it stored"* —
and that device is the one we are trying to reach. It would also be unrouted by any
build predating it, since `ALL` is what the inbound path dispatches on, which is the
same degradation the decline kind at 30330 documents. If the proposal's size ever
becomes the binding cost, the nudge can be added *alongside* the resent proposal
rather than instead of it.

**Forwarding another member's message in a signed envelope.** Technically possible:
wrap the original event, verify the inner signature before `record` files it under
the inner author. Rejected on cost against benefit. It is new verification code on
the path that decides whether a refusal counts — see the forgery note above — and it
exists to deliver nonces and partial signatures that nobody but the coordinator ever
needed. The coordinator already has them. Naming the silent member and letting their
own device speak costs one tag.

**Automatic resend on a timer.** A background publisher that fires on a stalled
session. Rejected: it publishes without anybody deciding to, it would write a
transcript line per tick in a room whose protocol lines are deliberately one per
step, and every amplification bug in the app's history has been something that
published on a schedule. A person noticing a stalled session and knocking is the
same shape as everything else on this screen. The cooldown column would make a timer
straightforward to add later if a group asks for it.

**A Marmot direct message per silent member.** `MarmotInnerEvent` has
`directMessageRecipientPublicKey` and it would work. Rejected twice over: it is m
events where one does, and per `docs/marmot-direct-messages.md` every member of the
group sees that a private message was sent and to whom — so a nudge would read as a
side channel in a room where the nudge is public information anyway.

**Narrowing the resend to the silent set.** Tried, landed, and reverted. On Marmot
there is nothing to narrow — a group event is encrypted to the group and names
nobody. On NIP-17 it is actively wrong: the p-tag set is what a receiver hashes
into the room id, so a narrowed resend lands in a room that does not exist, and it
buys nothing anyway because the sealer wraps per room participant rather than per
p-tag. See the transport note in Phase 2.

**Resetting the queue row instead of writing a new one.** `@Upsert` of a
`MarmotInnerEvent` with `marmotGroupEventId = null` puts an already-sent row back on
the outbound queue, which looks like a free resend. Rejected: it destroys the row's
mapping to the group event it became, which the transcript and
`MarmotPublishObligation` both read, and it re-sends the *old* ciphertext's plaintext
at an id that no longer describes a new event. A resend is a new message with a new
`created_at`, which is also what makes it decryptable at a generation the receiver
has not passed.
