# Subgroups

A group makes another group, and the child can prove where it came from. This is
how the four ceremonies fit together, what the parent's signature actually
covers, and the one thing a subgroup cannot inherit.

Read [shared-key-derivation.md](./shared-key-derivation.md) and
[shared-key-ceremony.md](./shared-key-ceremony.md) first. Everything below is
built on the property they state — that a room's id *is* the key it signs with —
and on the ceremony they describe, which a subgroup runs again from scratch.

**Built**, phases 1-9, one commit each, and then **Phase 10 moved the child's
ceremony into the parent's Marmot room** -- the thing [Phase 4](#why-not-the-parents-marmot-room)
deferred and said to revisit. Phases 1-9 are kept as written because they are the
reasoning, and the code reads better against the argument it came from than
against a summary of itself; where Phase 10 overtook one of them the section says
so and points forward. Where the implementation chose differently
the section says so, and it did so six times worth reading:

| what the plan said | what it turned out to be |
|---|---|
| `SubgroupManager.openCeremony` | never built. The picker calls `createNip17ChatRoom` and `proposeRitual` directly, exactly as robust-group creation does -- a wrapper over two repository calls would have been a third name for one act |
| `MarmotGroupCreation.create` called from the view model | reached through `ChatRepository.createMarmotGroup`. View models here talk to repositories and managers take the database; the first cut reached for a `database` the interface does not expose, and should not |
| a pure `SubgroupGuardsTest` in commonTest | jvmTest. Every refusal reads the database -- a key-holding session, an admin flag, a ceremony in the derived room -- so a pure version would have tested less, not differently |
| Phase 4 adds one tag | two. `subject` fixes a bug older than subgroups: `getOrCreateNip17ChatRoom` has always read a subject off the payload and the ritual path never wrote one, so every robust group arrived nameless on every device but its creator's |
| `stateFrom` reads the parentage with two parsers | three, and a wrapper. A tag present and unreadable looks absent through a parser, so `claimsParentage` reads the tag *names*; and "refused" has to be told from "none claimed", which a bare nullable cannot carry |
| Phase 8 adds three refusals to a settled function | it short-circuited the tests that were already there. `canSign` and `isAdmin` run before the picking rules, so the fixtures had to grow a parent that can actually sign and a coordinator who is actually an admin -- see the note in that phase |

Two things the plan got right that are worth keeping if any of this is rewritten.
The key-package check moved to the picker before a line of it was built, on
review, and it is the difference between a subgroup failing in a second and
failing after three ceremonies. And the founding-roster rule -- that `certifies`
must not compare the name or the `p` tags against anything current -- has a test
whose job is to *fail* the day somebody adds the comparison that looks obviously
missing.

Written against the code as it stood at `b50b1762`.

## What a subgroup is

A subgroup is an ordinary Marmot room with an ordinary threshold key, made by an
ordinary ChillDKG ceremony, plus a **birth certificate** — the parent group's
signature over the child's room id — carried on the child's key state.

The key state grows two tags for it, not one, and only the first is an artefact:

| tag | what it is |
|---|---|
| `birth_certificate` | the parent's signed statement, whole. The claim itself |
| `parent_group` | the parent's room id — an **index** into the certificate, which already carries the same value in its own `parent_group` tag and as its author |

The second is redundant by construction and is kept anyway, so a reader can
answer "whose child is this" without parsing an event out of a tag value. What
stops it being a second, weaker source of truth is Phase 3's rule that a state
carrying one without the other, or the two disagreeing, is dropped entirely.
There is no state in which the index is believed and the certificate is not.

That is the whole of the relationship. The child does not derive its key from the
parent's, does not share a share with it, and cannot be signed for by it. What
the certificate buys is a checkable claim, and only that claim:

> The group holding key `P` said, with a quorum, that the room `C` is its child.

Everything else about a subgroup — how it signs, who administers it, what it
holds — is the same as any other robust group in this app, and deliberately so.

### Why the key is fresh rather than derived

`SharedKeyDerivation` can already walk a group's key to any path, and
`m/9420/1/0` would give a child room for free with no ceremony at all. It is the
wrong answer for one reason, stated in that file:

> Anyone who learns one derived private key recovers the threshold key and can
> sign as the group without any quorum at all.

A derived child is the parent wearing a different hat. Its members would be the
parent's members, its quorum the parent's quorum, and the parent's admins would
be able to sign as it. A subgroup exists precisely so that a *different* set of
people — including people the parent does not trust to administer the parent —
can act on their own. A fresh ceremony is what makes the child's authority the
child's.

The cost is that a subgroup is not cheap: four multi-party rounds, every one of
which needs every selected member to show up. That is named again under
[what this does not do](#what-this-does-not-do).

## The order, and why it is forced

```
  1. ChillDKG among the new admins       -> threshold key K, and so C = marmotGroupId(K)
  2. birth certificate, in the parent    -> parent's quorum signs C
  3. key state, among the new admins     -> child's quorum signs "C signs with K", carrying the certificate
  4. the Marmot room                     -> coordinator creates C and welcomes the admins
```

Nothing here is a policy choice. Step 2 needs `C`, which does not exist until
step 1 produces `K`. Step 3 carries the certificate, so it needs step 2. Step 4
is gated on the key state for the same reason `DkgRitualViewModel.createAdminGroup`
is today — a room created before its group has agreed what it signs with is a
room whose founding fact is settled after the founding.

The coordinator is the parent admin who pressed the button. They coordinate the
child's ceremony, propose the certificate in the parent, propose the key state in
the child, and create the room. ChillDKG and FROST both treat a coordinator as
untrusted, so this buys them nothing but work — the same bargain the existing
ceremony makes.

**Only step 1 is theirs alone.** After the ceremony fixes the participant set,
every remaining step is open to somebody else: the certificate can be proposed by
any parent admin holding a share of the parent's key, and the key state and the
room by any of the child's admins, who by then all hold shares of `K` and know
`C`. So a coordinator whose phone dies after the ceremony does not strand a
subgroup — the flow is resumable by anyone who was in it, which is why every step
reads its state off stored rows rather than off a session object. The UI should
make that reachable rather than merely true: Phase 7's rung is offered to whoever
opens the screen and can act, not only to the member who started.

## Three ceremonies, two quorums, one coordinator

| step | runs in | signed by | transport |
|---|---|---|---|
| ChillDKG | the **parent** Marmot room, over the child's admins | n/a — every participant | MLS group events |
| birth certificate | the **parent** Marmot room | the parent's quorum, as the parent's key | MLS group events |
| key state | the **parent** Marmot room | the **child's** quorum, as the child's key | MLS group events |

One room, three ceremonies, two quorums. The middle row was the only new shape
and it is not new machinery: `FrostSigningManager` already runs on both
transports and already gates each signer's approval behind the existing proposal
UI. What is new is a kind for it to carry and a reason for the parent's admins to
say yes.

The first and third rows read like that since [Phase 10](#phase-10--the-ceremony-moves-into-the-parents-room).
They used to run in a sibling NIP-17 room whose id was an aggregate of the child's
admins, and the reasons for moving them -- and the two ways it can silently go
wrong -- are there.

The parent's room hosting a ceremony its own members are not all in is the one
thing that needs saying twice. An MLS message reaches the whole tree, so the
ceremony has to name who it is with: the proposal's p-tags are the participant
set, and a parent member outside them drops the proposal rather than joining.
"Can read the message" and "is in the ceremony" are different questions, and a
member who published a host key would be in the child's signing quorum for good.

## Informed consent, and what the parent's admins are actually signing

The spec for the certificate is "the hex of the new groupId, signed with the
existing groupId's key". Taken literally that is 32 opaque bytes: a parent admin
would be asked to put the group's signature to a number they cannot check,
produced by a ceremony most of them were not in, on behalf of people they have
only the coordinator's word about.

So the **content** is exactly the new group id, as specified, and the **tags**
carry what makes it checkable. The signature covers both — an event id hashes
over its tags — so nothing is added to the claim by putting it there, only to
what a signer can see before agreeing:

```
kind:    30329
pubkey:  <the parent room's id>            (set by the signing session, not the proposer)
content: <the child's room id, 32-byte hex>
tags:
  ["d",            <the child's room id>]        addressable; newest certificate per child wins
  ["parent_group", <the parent room's id>]
  ["subgroup_key", <the child's threshold key, 33-byte hex>]
  ["frost_path",   "m/9420/0/0"]
  ["name",         <the subgroup's name, as the coordinator typed it>]
  ["wallet",       "guarded" | "unguarded"]          since subgroup-wallet-policy.md; absent before
  ["p",            <each of the child's admins>]
```

A member's guarded wallet is not a subgroup and never a 30329: every reader of
this kind assumes a room. It has its own certificate, 30334 --
[member-guarded-wallets.md](./member-guarded-wallets.md).

With those, a parent admin's device can check the thing that actually matters
before it signs — `marmotGroupId(subgroup_key, frost_path) == content` — and
`ProposedEvent` can render "Translation team, for Alice, Bob and Carol" rather
than a hash. A coordinator who lies about who is in the child is then lying in a
field the parent's signature covers, which is the difference between a mistake
and evidence.

The name is in there for the same reason and not for the room's benefit: the room
takes its name from `MarmotGroupData` like every other Marmot room, and this copy
exists so that what the parent approved is legible in the transcript and in the
proposal screen. Two consequences to hold:

**The name and the `p` tags are the founding roster, and they are frozen.** A
certificate is signed once and members move afterwards — somebody is added to the
child, somebody leaves, the room is renamed. None of that reaches a signature
already made, and none of it should: the certificate says who the parent
certified and under what name, which is a historical fact and stays true. So the
UI must never render either as the *current* state of the subgroup. Where the
room exists locally, its own row is the live answer; the certificate is what it
was born as. The Phase 7 list follows that rule and the plan says so again there.

**A rename therefore drifts from what was signed, on purpose.** The alternative —
leaving the name out so nothing can drift — buys consistency by making the
parent's admins approve an unnamed hash, which is the problem this section
exists to fix. Drift in a historical record is not an error; an unreadable
approval is.

## Phase 1 — the certificate, and checking one

New package `nostr/subgroup/`, no database, no coroutines — the same shape as
`GroupKeyStateEvent`, and testable the same way.

**`SubgroupBirthCertificateEvent`** at kind **30329**, the next free kind after
`ChronicleRequestEvent` (30328). Sits in the private 303xx range with everything
else that never leaves an encryption.

```kotlin
object SubgroupBirthCertificateEvent {
    val KIND: Kind = 30329

    fun assembleTags(
        subgroupChatRoomId: String,
        parentChatRoomId: String,
        thresholdPublicKey: HexKey,
        adminPublicKeys: List<HexKey>,
        name: String,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    ): Array<Array<String>>

    fun parseSubgroupChatRoomId(tags: Array<Array<String>>): String?
    fun parseParentChatRoomId(tags: Array<Array<String>>): String?
    fun parseThresholdPublicKey(tags: Array<Array<String>>): HexKey?
    fun parsePath(tags: Array<Array<String>>): List<Long>?
    fun parseAdminPublicKeys(tags: Array<Array<String>>): List<HexKey>
    fun parseName(tags: Array<Array<String>>): String?

    /** Whether [event] is a certificate the room [parentChatRoomId] actually signed for [subgroupChatRoomId]. */
    fun certifies(event: Event, subgroupChatRoomId: String, parentChatRoomId: String): Boolean
}
```

`certifies` is where the whole of the trust in a parent link lives, and it is six
questions, all answerable from the event:

1. the kind is 30329;
2. `content == subgroupChatRoomId`, and the `d` tag agrees with it;
3. the `parent_group` tag is `parentChatRoomId`;
4. `SharedKeyDerivation.marmotGroupId(subgroup_key, frost_path) == content` — the
   id really is that key's room, so a certificate cannot be pointed at a room the
   key does not derive;
5. `GroupKeyStateEvent.isSignedByRoom(event, parentChatRoomId)` — the author is
   the parent's id, the id is the hash of the fields beside it, and the signature
   verifies;
6. everything above inside a `runCatching`, because every input is off the wire.

Point 5 is `GroupKeyStateEvent.isSignedByRoom` used verbatim, not reimplemented.
It already asks "did *this room* sign this", and a room id is a public key here —
that is the whole economy of `docs/member-chronicle.md` and it applies unchanged.

**The name and the `p` tags are deliberately not among the six.** They are
covered by the signature — everything in the tags is — but nothing downstream may
*require* them to match anything, because they are the founding roster and the
world moves. A `certifies` that compared the `p` tags against the room's current
members would start rejecting a valid certificate the first time somebody joined
the child, and the failure would look like a forgery.

Two tag classes beside it, in `nostr/subgroup/tags/`, matching
`FrostDerivationPathTag`'s shape: `SubgroupParentTag` (`parent_group`) and
`SubgroupKeyTag` (`subgroup_key`). The `d` and `p` tags are quartz's; `name` is a
bare two-element tag with no class of its own, since nothing parses it but the
proposal screen.

**Tests** — `SubgroupBirthCertificateEventTest`, commonTest, pure: a certificate
that verifies; one whose content does not match its `d` tag; one whose id does
not derive from its key; one signed by a different room; one whose signature is
64 bytes of nonsense; one whose key is not a point on the curve; and one whose
name and admin set have nothing to do with the room's current ones, which must
still verify — that last is there to fail loudly if anybody later adds the
roster check the paragraph above forbids.

## Phase 2 — schema 16 → 17

Four nullable columns and nothing else, so Room migrates itself and the entry
joins the list in `DedunDatabase`.

| table | column | filled from | trusted? |
|---|---|---|---|
| `GroupKeyState` | `parentChatRoomId` | the state's parent tag | **yes** — `stateFrom` verified the certificate |
| `GroupKeyState` | `birthCertificateJson` | the state's certificate tag | **yes** — same |
| `ChatRoom` | `parentChatRoomId` | the child's key state, as the room is created or adopted | **yes** |
| `DkgSession` | `parentChatRoomId` | a tag on the ceremony proposal | **no** — a hint for the UI, see Phase 4 |

The trust column is the point of the table. Two of these are written only after a
signature has been checked and one is written from an unauthenticated claim on a
wire message; a column that mixes the two is a column no reader can act on. The
`DkgSession` one is never read for anything but a screen's title.

### `parentChatRoomId` is a plain column, never a foreign key

Worth its own heading because the reflex points the wrong way. `GroupKeyState`,
`DkgSession` and `GroupSignedEvent` each declare

```kotlin
ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"],
           onDelete = ForeignKey.CASCADE)
```

and the obvious next move is to give the parent pointer the same treatment. It
must not have it. A self-referential foreign key on `ChatRoom` with `CASCADE`
means deleting a parent room deletes every subgroup row beneath it — and then,
by their own cascades, each subgroup's messages, participants, key state, signing
sessions and signed events. A user tidying up a group they have left would silently
destroy a group they are still in.

`RESTRICT` is not the answer either: it would make a parent undeletable while any
child row exists, which is a foreign key deciding a product question. The parent
pointer is a **reference to a room that may not be on this device at all** — a
member of a subgroup who was never in its parent has the id and nothing else —
so it cannot be a foreign key in the first place. A dangling pointer is the
normal, expected state, and readers resolve it with a lookup that is allowed to
return null.

The whole certificate event is stored as JSON rather than the signature alone.
That is deliberate and is argued in the [appendix](#appendix--what-was-considered-and-rejected):
a signature plus a rule for rebuilding the event it covers is a rule that breaks
silently the first time the event's shape changes.

New DAO reads, all straightforward:

- `GroupKeyStateDao.getByParentChatRoomId(parentChatRoomId)` and an
  `observe` beside it.
- `ChatRoomDao.observeByParentChatRoomId(parentChatRoomId)`.
- `DkgSessionDao.getByParentChatRoomId(parentChatRoomId)`, for resuming a
  ceremony whose room has not been made yet.

## Phase 3 — a key state that names its parent

`GroupKeyStateEvent` gains two optional tags, and `GroupKeyStateManager` gains
the checks that make them mean something.

```
["parent_group",     <the parent room's id>]
["birth_certificate", <the signed 30329 event, as JSON>]
```

`assembleTags` takes an optional `parent: SubgroupParentage?` carrying both, and
emits neither when it is null — so every existing call site and every state
already signed is untouched.

The work is in `stateFrom`, which is where a state earns its row. After the two
checks it already makes — the room rederives from the key, and the group signed
it — a state carrying either new tag has to pass four more:

1. **Both or neither.** A parent tag with no certificate, or a certificate with
   no parent tag, is dropped. A half-claim is not a weaker claim; it is a claim
   with the checkable part removed.
2. The certificate parses as an `Event`.
3. `SubgroupBirthCertificateEvent.certifies(certificate, state.chatRoomId, parentChatRoomId)`
   — the parent signed *this* child, not some other one.
4. The certificate's `subgroup_key` equals the state's own threshold key.
   Redundant with (3) via the derivation, and kept because it costs nothing and
   the two facts are stated in different places.

A failure at any of them **drops the whole state**, rather than keeping it as a
parentless one. A state that claims a parentage it cannot back is not a state
with one field wrong — it is a device asserting a relationship the parent group
never agreed to, and half-believing it is worse than believing none of it.

`GroupKeyStateManager.propose` takes the same optional parentage and passes it
through. `record` and `adopt` carry the two verified values onto the row; they
already funnel through `stateFrom`, so nothing else is needed to keep the row
honest.

**Tests** — added to `GroupKeyStateTest` (commonTest, pure `stateFrom` cases) and
`SignedGroupKeyStateTest` (jvmTest, real FROST signatures end to end): a subgroup
state that verifies; one whose certificate names another child; one whose
certificate is signed by a room that is not the named parent; one carrying a
parent tag and no certificate; one carrying a certificate whose key is not the
state's.

## Phase 4 — where the subgroup's ceremony runs

> **Overtaken by [Phase 10](#phase-10--the-ceremony-moves-into-the-parents-room).**
> The ceremony now runs in the parent's Marmot room. Everything below is what was
> built first and why; the two tags it adds are unchanged and still travel on the
> proposal, and the collision it argues about is the thing Phase 10 retired.

The child's ChillDKG needs a room whose participant set is exactly the child's
admins, and whose transport is gift wraps. There is already one:
`chatRepository.createNip17ChatRoom` over the selected admins plus the
coordinator. Its id is `ChatRoom.deriveChatRoomId` over the member set, so every
device lands on the same room, and `ChillDkgRitualManager` needs no change at all
— its `broadcast` p-tags come from that room's participants and `acceptProposal`
derives `n` from those same p-tags.

This is the same move `SelectChatRoomTypeViewModel.createNip17ChatRoom` already
makes for a robust group, and the reason step 1 costs almost no new code.

**Two tags are added to the ceremony proposal**, on `DkgRitualEvents.PROPOSAL`
only, and both are cosmetic in the strict sense that nothing acts on either.

`parent_group` is parsed by `acceptProposal` onto `DkgSession.parentChatRoomId`.
It authenticates nothing — anyone can claim any parent — and is read for exactly
two things: the ritual screen saying "a subgroup of Ekklesia" instead of "a shared
key ceremony", and a member finding their way back into a flow they closed the app
halfway through. The load-bearing claim is the certificate, three steps later, and
the code should say so where the column is declared.

`subject` is the fix for a gap this flow inherits and makes worse.
`NostrDao.getOrCreateNip17ChatRoom` already builds the receiving side's room with
`subject = decryptedGiftWrapPayload.parseSubject()`, and
`ChillDkgRitualManager.broadcast` writes no subject tag — so today a member
selected for a ceremony watches an **unnamed** chat room appear on their device
with a key ceremony already running in it. That is survivable when the user just
agreed to make a group with those people; it is not when the room is a means to an
end they were not consulted about. The proposal carries the subgroup's name, the
receiving room gets it for free through a reader that already exists, and the
change is one tag on one broadcast.

It is worth fixing for the existing robust-group flow in the same commit, since
`SelectChatRoomTypeViewModel` passes a `subject` to `createNip17ChatRoom` that only
ever reaches the creator's own device.

### The collision this buys, and it is real

> Retired by [Phase 10](#phase-10--the-ceremony-moves-into-the-parents-room):
> there is no member-derived ceremony room left to collide. What survives is a
> narrower refusal -- a ceremony for the same admins under the same parent that is
> still *running* -- and the reasoning below is why that one is worth keeping.

A NIP-17 room's id is a pure function of its members, so **one admin set gets one
ceremony room, forever**. Two consequences, and both need a guard rather than a
comment:

- Selecting an admin set that already holds a completed ceremony returns that
  ceremony (`proposeRitual` hands back anything not `FAILED`), which would make
  the "new" subgroup the old group under a new name — same key, same room id.
- Selecting *every* member of the parent lands in the parent's own ceremony room,
  which is the room over all its members.

The second is **not** refused, and the first is refused more narrowly than it
first shipped. A subgroup over everybody is legitimate -- a subgroup is a logical
division rather than a smaller membership -- and sharing a room was never the
problem; sharing a *ceremony* was. `DkgSession.parentChatRoomId` tells two
ceremonies in one room apart, and `DkgSessionDao.getLatestSessionFor` is scoped by
it, so what Phase 8 refuses is only the same parent asking twice over the same
admins. See [that phase](#phase-8--the-refusals).

### Why not the parent's Marmot room

> **It landed** — see [Phase 10](#phase-10--the-ceremony-moves-into-the-parents-room),
> which is this section's shopping list carried out. Kept as written because the
> three mechanical costs it enumerates are exactly the three that had to be paid,
> and two of them fail silently if they are not.

The obvious alternative is to hold all three steps in the parent room: no sibling
room, no member-derived id, and the collision above disappears. It is a better
design and it is not available yet. What follows is why, because "we did not"
without "and here is the price" is how a decision gets re-litigated every six
months.

**It is one decision, not three.** The certificate already runs in the parent
room and is the only one of the three free to run anywhere. The key state is not:

```kotlin
// GroupKeyStateManager.propose
check(chatRoomId == signingRoomId || key.chatRoomId == signingRoomId)

// FrostSigningManager.signingPath, the one case that cannot be self-checked
if (localChatRoom.chatRoom.mlsGroupState == null && key.chatRoomId == chatRoomId) {
    return SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
}
```

The child's room does not exist, so `chatRoomId == signingRoomId` cannot hold and
the key state has to be signed **where its ceremony ran**. Both guards say so
independently. So the ceremony and the key state move together or not at all, and
the question is only ever "which transport does the child's ChillDKG ride".

**What moving it would cost, mechanically.** Three things, all enumerable:

1. `ChillDkgRitualManager.broadcast` writes only a `GiftWrapPayload`; it needs the
   two-transport shape `FrostSigningManager.broadcast` already has, plus a Marmot
   inbound arm in `NostrDao` beside the one dispatching FROST signing at kind
   30320-30325. The p-tags stay on **both** transports, unlike FROST's — for a
   ceremony the p-tag set *is* the participant set every device derives `n` from,
   and inside MLS encryption naming the participants leaks nothing.
2. `DkgSession.chatRoomId` stops identifying a ceremony. Three queries key on it
   and all three break: `proposeRitual`'s one-ritual-at-a-time guard would return
   the parent's *own* completed session and refuse to open a subgroup ceremony at
   all; `observeLatestSessionForChatRoom` would show the child's ceremony on the
   parent's shared-key screen; and `completedKey`'s third fallback would resolve
   the **child's** key for the parent room, for any parent member holding no key
   state row — which is every member welcomed after the parent's own ceremony.
   Fixable with a `subjectChatRoomId` column and three scoped queries, but that
   third one is a load-bearing fallback and the failure is silent.
3. `signingPath`'s special case is gated on `mlsGroupState == null` and would have
   to admit a Marmot room. Still device-local — both inputs are read from this
   device's own database, so a proposer still chooses nothing — but it widens the
   one function whose contract is that the path can never come off a proposal.

**Why not, even after all that.** `docs/mls-skipped-keys.md` names
`ChillDkgRitualManager.proposeRitual` in its table of reliable triggers —
"proposal, then the host key" — and today that costs nothing, because the ritual
runs on gift wraps and the bug is in MLS. Moving it onto group events puts a
ceremony on the one transport where a message arriving a moment late is dropped
for good.

A DKG is the worst possible payload for that. It **cannot finish until every
participant takes part**, so one lost round-1 message stalls it permanently for
everybody, and the ritual's own resume machinery cannot help: relays redeliver
gift wraps and `replayStoredMessages` re-feeds the backlog, while a group event
whose inner event never materialised leaves a stored `MarmotGroupEvent` row and
nothing to replay. FROST signing survives the same transport only because it
needs `t` of `n` and routes around the signer whose nonce was lost.

`MlsGroupCache` covers the burst-in-one-sync case and nothing else: a restart
loses the skipped keys, and any other writer in the room — a chat message, a
member being added — invalidates the cache mid-ceremony.

**So: revisit when the quartz fix lands.** Once `saveState`/`restore` carry the
skipped keys, the parent room is strictly the better home for all three steps,
the collision above stops being a refusal and becomes a non-issue, and
`DkgSession.parentChatRoomId` stops being needed at all. Until then the child's
ceremony rides the transport that tolerates reordering, and pays for it with one
sibling room and one refusal.

## Phase 5 — the manager, and the four steps in order

New `managers/SubgroupManager.kt`, holding the orchestration that does not belong
to any one of the three existing managers. It calls them; it does not
reimplement them.

```kotlin
object SubgroupManager {
    /** The NIP-17 room a ceremony for these admins would run in. Pure. */
    fun ceremonyRoomIdFor(adminPublicKeys: Set<HexKey>, coordinator: HexKey): String  // deleted in Phase 10

    /** Step 1: stand up the ceremony room and open the ritual in it. */
    suspend fun openCeremony(...): DkgSession  // never built

    /** Step 2: ask the parent's quorum to certify the child. Runs in the parent room. */
    suspend fun proposeBirthCertificate(
        database: DedunDatabase,
        parentRoom: LocalChatRoom,
        userPublicKey: HexKey,
        key: DkgSession,
        adminPublicKeys: List<HexKey>,
        name: String,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    ): FrostSigningSession

    /** The newest certificate for this child that actually certifies it, or null. */
    suspend fun certificateFor(
        database: DedunDatabase,
        subgroupChatRoomId: String,
        parentChatRoomId: String
    ): GroupSignedEvent?

    /** Step 3: ask the child's quorum to sign its key state, carrying the certificate. */
    suspend fun proposeSubgroupKeyState(...): FrostSigningSession

    /** Everything the parent has certified, whether or not the room exists here. */
    suspend fun subgroupsOf(database: DedunDatabase, parentChatRoomId: String): List<Subgroup>

    /** Files a certificate that arrived, or drops it and says why. */
    suspend fun record(database: DedunDatabase, chatRoomId: String, innerEvent: Event): GroupSignedEvent?
}
```

`proposeBirthCertificate` is one `FrostSigningManager.proposeSigning` call in the
parent room with `key = null` — the parent's key is resolved from the parent room
the way every other signature in that room is, and a caller naming a ceremony
here would be a caller choosing what the group signs as. A session of one, never
batched, for the same reason `GroupKeyStateManager.propose` gives: a batch is only
as available as its worst item, and this one is a precondition of everything
after it.

`proposeSubgroupKeyState` is `GroupKeyStateManager.propose` with the parentage
filled in, and it must refuse to run without a certificate that passes
`certifies`. Proposing a state the coordinator's own device would drop is not a
mistake worth letting the group discover by signing it.

`subgroupsOf` reads the parent's `GroupSignedEvent` rows of kind 30329, filters
them through `certifies`, and joins each to the local `ChatRoom` if there is one.
No table of subgroups: the certificates *are* the record, they are the group's own
signed statement, and every device in the parent room already has them —
`FrostSigningManager.complete` files a `GroupSignedEvent` on every device that
followed the session, not only on the signers'.

**Two certificates for one child is a normal outcome, not a conflict.** Two parent
admins can press the button on the same admin set: the second's `proposeRitual`
folds into the ceremony the first opened, but both may go on to propose a
certificate, and both sessions can complete. `GroupSignedEvent` is keyed
on the event id, so the rows coexist. `certificateFor` therefore takes the
**newest** of those that pass `certifies`, the way `GroupKeyStateManager.signedAmong`
already picks the newest state — and because both certificates say the same true
thing about the same child, which one wins does not matter. The `d` tag makes them
replacements of each other rather than an accumulation, which is what it is for.

### Two dispatch arms, both easy to forget

`ChatMessage.applyInnerEvent` needs a **30329** arm. Without one the certificate
falls to `else ->` and every parent member gets a raw-JSON chat bubble; that arm
is the failure mode `docs/member-chronicle.md` names for an old build meeting a
new kind, and here it is avoidable. The arm delegates to `SubgroupManager.record`,
which verifies against the room it arrived in — the event reaches this path both
from `FrostSigningManager.applySignedEvent`, where it is already checked, and
from an arriving inner event, where it is not, and the arm cannot tell which.

Unlike the key-state arm it **does** write a chat line. A key state is standing
state whose session already wrote the transcript; a subgroup being born is
something that happened, and it happened on behalf of parent members who are not
in the child and will otherwise never be told.

`ProposedEvent.summaryOf` needs a 30329 arm too, or the parent's admins approve
"Event of kind 30329" with the JSON underneath. It should read as the members and
the path — the id is the thing being decided and is shown by the screen anyway.

### Not chroniclable

30329 does **not** join `ChronicleEvent.CHRONICLABLE_KINDS`, for the reason that
list gives for excluding `GroupKeyStateEvent`: verification admits an event on the
strength of the group's signature, so any chroniclable kind is replayable by any
member forever. A member added to the parent after a subgroup was made therefore
sees an empty subgroup list — named under
[what this does not do](#what-this-does-not-do), with the argument for changing
it later.

## Phase 6 — creating the room, once, for both flows

`DkgRitualViewModel.createAdminGroup` is 120 lines of Marmot room creation living
in a ViewModel: resolve every member's key package or refuse, build
`MarmotGroupData` with the admin list baked into epoch 0, `MlsGroup.create`,
`getOrCreateChatRoom`, adopt the key state, add the members. The subgroup needs
all of it and differs in four values.

So it moves, unchanged in behaviour, to `managers/MarmotGroupCreation.kt`:

```kotlin
suspend fun create(
    database: DedunDatabase,
    chatRepository: ChatRepository,
    groupId: String,
    name: String,
    purpose: String,
    adminPublicKeys: Set<HexKey>,
    userPublicKey: HexKey,
    keyPair: KeyPair,
    path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
    parentChatRoomId: String? = null
): Outcome   // Created(room, notAdded) | BlockedOn(missingKeyPackages) | Existing(room) | Failed(reason)
```

Both call sites then read as four values and a result. The rules that are already
right stay right for the subgroup for free, and they are the ones worth not
re-deriving:

- **Every key package before anything exists.** The id is derived, so there is
  exactly one room per key at this path; a half-created one occupies that address
  permanently and there is no second id to retry with. Better to create nothing
  and name who is missing.
- **`adminPubkeys` baked into epoch 0**, so a member welcomed later gets a
  populated group rather than chasing a bootstrap commit that predates them.
- **`adopt` before the members are added**, because filing the key state is local
  and certain and adding members is neither.

`parentChatRoomId` is the one genuinely new parameter, written onto the
`ChatRoom` row. The subgroup's description carries the path through
`SharedKeyDerivation.describe`, exactly as the admin room's does — MIP-01 has no
field for either, and the path is what rebuilds the `TweakCache`.

### The description carries the parent too, as a hint

`describe` gains a second optional line, beside the path:

```
Admins of Ekklesia's translation work.

Shared key path: m/9420/0/0
Parent group: 4f2b…
```

It is there for one member and one problem: somebody welcomed into the subgroup
*after* it was founded. The verified parent link lives on `GroupKeyState`, filed
by `adopt` from the signed key state — which only ceremony participants hold — and
certificates are not chroniclable, so a later joiner has a room, no key state, and
no way to learn the room is anybody's child. `MarmotGroupData` is the one thing
that does reach them: it rides in the Welcome and is baked into the epoch-0 group
context, so every member gets it however late they arrive.

**It is a hint and must be rendered as one.** Group data is written by the room's
creator and agreed by MLS, not by the parent — so this line says "this room claims
Ekklesia as its parent", which is a strictly weaker statement than the certificate
makes. The rule for the UI is the one Phase 2's table states: where a verified
`GroupKeyState.parentChatRoomId` exists, it wins and the hint is never consulted;
where it does not, the hint may be shown as unconfirmed and must not be written
into the verified column. The upgrade path is the certificate itself — see
[what this does not do](#what-this-does-not-do).

## Phase 7 — the UI

Four pieces, three of them small. Every one of them is subject to `./gradlew
:composeApp:m3Audit`: strings in `strings.xml` in sentence case, spacing from
`MaterialTheme.spacing`, colour from a role, `minimumInteractiveComponentSize`
on anything clickable that is not an `IconButton`, and a decided
`contentDescription`.

**A subgroups section on `ChatRoomDetailScreen`**, between members and the
danger zone, listing `SubgroupManager.subgroupsOf` with one of three supporting
lines per row — *certified, not yet created* / *created, you are not a member* /
the room's own subject if this device is in it — and an "add subgroup" `TextButton`
above them in the shape the existing "invite new member" one has. The row's tap
target navigates into the child's room if this device has it and to the
certificate's `NostrEventDetailRoute` if it does not.

**The row's title comes from the room where there is one, and only otherwise from
the certificate.** That ordering is the frozen-roster rule from
[the consent section](#informed-consent-and-what-the-parents-admins-are-actually-signing)
applied at the only place it is visible: the certificate's `name` and `p` tags are
what the parent approved at founding, and a subgroup that has since been renamed
or has added members would otherwise be listed under a name nobody uses and a
membership nobody has. Where this device holds the child's room, the room is the
live answer. Where it does not, the certificate is all there is, and the row says
so by carrying its supporting line rather than pretending to be current.

Offered only where it can work: this device is an admin of the parent
(`Participant.adminAt != null`), the parent can sign
(`FrostSigningManager.canSign`), and the parent is a Marmot room. Hidden, not
disabled, in the same way the shared-key entry is hidden on an MLS room.

**A parent row on the child's detail screen**, read from the verified
`GroupKeyState.parentChatRoomId`, sitting directly under the signing-key card —
where a member looks to find out what the room is.

**`SelectSubgroupAdminsScreen`** and its route and ViewModel, modelled on
`SelectChatRoomMembersScreen`. The pool is the parent's own participants, admins
and non-admins alike, with the parent's admins marked rather than filtered — the
point of a subgroup is that it can be run by people the parent does not let run
the parent.

**A name field at the top**, because nothing else in the flow can supply one.
`createAdminGroup` gets away with naming the admin room after the group it
administers — a group has exactly one — but a group can have many subgroups, and
naming them all after the parent names none of them. The name is required, travels
on the route to Phase 6's `MarmotGroupData`, and is copied into the birth
certificate so the parent's admins approve something legible.

`MarmotGroupName.of` puts the `#` on it. The field shows the prefix beside what is
typed rather than in it, so the caret does not move under somebody mid-word, and
the bare name is what goes down — since Phase 10 onto `DkgSession.subject`, which
is where a ceremony held in somebody else's room has to keep it. The subgroup's
own name is derived from that twice, by two unrelated callers:
`proposeBirthCertificate` normalises the name the parent signs,
`MarmotGroupCreation` normalises the name the room carries. Those two have to be the same string or the subgroup is not called
what its parent certified, which is what the rule being idempotent buys.

### Key packages, checked here rather than discovered at step 4

The single most likely way this flow fails, and the cheapest to prevent.

A key package is **one-time-use**: `NostrDao` marks the bundle `consumed = true`
as the device processes its own Welcome, and `MarmotKeyPackageBundleDao` only ever
hands back one with `consumed = false AND rotated = false`. Every group a member
joins burns one. So a member who is in the parent room and has not published a
fresh key package since has none left, and Phase 6 will refuse to create the
subgroup for exactly the right reason — after a ChillDKG, a parent quorum and a
child quorum have all completed, each of which required every selected admin to
show up and approve. Three multi-party ceremonies, then a dead end, and no second
room id to retry against.

So availability is a property of the **picker**, not of the create step:

- Prefetch every candidate's key package as the screen opens, the way
  `SelectChatRoomMembersViewModel.scheduleKeyPackageEventSynchronization` already
  does for group creation — by the time somebody has finished ticking names and
  set a threshold, the relay round trip is usually done.
- Mark a member with none, and refuse to select them, with the reason and the
  remedy in the same line: they need to publish a new key package. Anything vaguer
  makes it the coordinator's problem to diagnose.
- Re-check at confirm, because the screen may have been open a while.

Phase 6's refusal stays exactly as it is. It is now a **backstop** rather than the
first line of defence — a member's package can be consumed by some other group
between the picker and the create step — and a backstop is worth having precisely
because the address it protects is permanent.

**Three admins in total, the coordinator included**, so the picker asks for at
least **two** others. Three is `ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE`, and it
is the same floor for the same reason it states: below three a quorum is not a
check, because two admins means every decision needs both of them and one means
the coordinator is deciding alone. The coordinator counts because they are a
ChillDKG participant by construction — they hold a share whether or not anybody
ticked their name — so the screen shows their own row selected and locked rather
than leaving them out of the count the confirm button is testing.

**The threshold is the coordinator's to set, on this screen, and only here.**
A stepper over `ChatRoomType.quorumRange(adminCount)` — `2..3` for the smallest
subgroup — starting at `ChatRoomType.defaultQuorum(adminCount)`, which is `2` of
`3`. It has to be settled before anything is published, and that is a protocol
fact rather than a layout preference: ChillDKG hashes the threshold and the host
keys into the session identity, so `t` is fixed the moment the proposal goes out
and a group that disagrees about it gets no key at all rather than a weak one.
There is no later screen where it could be changed, and the plan should not
pretend otherwise.

It is also the one value the coordinator picks *for other people*. Consent is not
lost by that: the threshold rides on the proposal in `DkgThresholdTag`, every
invitee's `acceptProposal` re-checks it against `quorumRange` and drops a
proposal outside it, and the `HOST_KEY` gate is where each of them agrees to the
`t`-of-`n` they can now see. Joining is the agreement; the coordinator only
writes down what is being joined.

The subgroup's wallet policy, *guarded* or *unguarded*, is the second value the
coordinator picks for other people, and it is agreed to the same way: it rides on
the proposal, the join gate shows it, the parent's quorum signs it into the
certificate, and the child's quorum refuses a key state whose certificate says
otherwise. See [subgroup-wallet-policy.md](./subgroup-wallet-policy.md).

Confirm stays disabled below two selections, and below a threshold outside the
range, with the reason stated rather than left to be guessed.

**`DkgRitualRoute` gains an optional `parentChatRoomId`**, and the ritual screen
grows one rung. The alternative — a parallel subgroup screen — duplicates a
ladder, a threshold picker, three approval gates and a key-state rung in order to
insert one step, and the two copies would drift within a release. The rung is
rendered only when a parent is present, and it is where the coordinator proposes
the certificate and watches the parent's quorum answer.

The approvals themselves need **no new UI**. The certificate is a
`FrostSigningEvents.PROPOSAL` in the parent room and so, since Phase 10, is the
key state; `ProposalListScreen` and `FrostSigningScreen` already show and approve
both. Which quorum each asks is not the room's business either way — a proposal
names the ceremony it signs under, and `signerIdOf` drops one for a ceremony this
device was not in, so a parent admin outside the subgroup never sees the child's
key state as theirs to answer.

## Phase 8 — the refusals

A subgroup is expensive and its room id is permanent, so every one of these is
checked before the button is offered *and* again in the manager. The UI check is
what keeps a user out of a dead end; the manager check is what keeps a bug out of
a room nobody can replace.

| refused | because |
|---|---|
| fewer than three admins in total — the coordinator plus two | below three a quorum is not a check — the same floor `ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE` states |
| a threshold outside `ChatRoomType.quorumRange(adminCount)` | `t = 1` is a threshold key any one member signs with, and ChillDKG will happily generate one. `acceptProposal` already refuses such a proposal, so an unchecked one produces a ceremony every invitee silently drops |
| an empty or blank name | Phase 6 has nothing to put in `MarmotGroupData.name`, and the parent's admins would be certifying an unnamed hash |
| a selected member with no unconsumed key package | they cannot be welcomed into the room at step 4, and finding that out at step 4 wastes three ceremonies. Checked at the picker and again at confirm |
| ~~the selection is not a proper subset of the parent's members~~ | **withdrawn.** A subgroup is a logical division, not a smaller membership, so "everybody" is a normal answer. What this was standing in for is the row below, checked precisely rather than by set size |
| a ceremony for **this parent over these same admins** is still running | every admin would be asked twice, on two ladders, for two keys, one of which nobody will make a room from. Matched on `DkgSession.participantPublicKeys` since Phase 10, and only while the ceremony is live: a *finished* one means that subgroup was made, and a second run by the same people is a legitimate ask |
| this device holds no share of the parent's key | it cannot open the certificate session; `proposeSigningBatch` throws, and throwing at the button is not a UI |
| this device is not an admin of the parent | a non-admin proposing the parent's signature is a proposal the parent's admins have to decline by hand |
| the parent has no signed key state and no resolvable key | there is nothing for the certificate to be signed with |
| a certificate already stands for this child | one certificate per child; a second is a `d`-tag replacement of the first, not a second subgroup |

The first is the one a user will actually meet, and it says what to do: "pick at
least two more people".

### Why "a subgroup cannot be the whole group" was withdrawn

It shipped, and it was wrong twice.

It refused something legitimate. A subgroup is a group deciding that some of its
work belongs to a differently-keyed room, not a group carving out a smaller
membership — so every member being in it is an ordinary case, and no rule here had
any business deciding otherwise.

And it was a proxy rather than a check. The thing it stood in for is real: a
ceremony room is derived from its admins, so a subgroup over everybody lands in
the room the group's *own* ceremony was held in, and `proposeRitual` handing back
that ceremony would make the child the parent. But set size does not detect that.
A parent whose membership has changed since its own ceremony derives a different
room, so the sizes can match with no collision and differ with one.

**Sharing the room was never the problem; sharing a ceremony was.**
`DkgSession.parentChatRoomId` already told them apart, so the fix was to scope the
lookup by it — `DkgSessionDao.getLatestSessionFor(room, parent)` — rather than to
forbid the selection. A room may now hold two ceremonies, and everything below
that lookup already keyed off the session id, so nothing else had to learn about
the second one.

Phase 10 finished the thought. A parent's room hosts *every* subgroup ceremony
the group ever runs, so `(room, parent)` stopped telling two of them apart and the
lookup moved to `(room, parent, admins)` — `getLatestSubgroupSessionFor`.

One collision survives, and it is degenerate: the same parent, over the same
admins, twice. Those two have nothing left to distinguish them, which is another
way of saying they are one subgroup asked for twice, and that is what the message
now says.

## Phase 9 — the tests that prove it

The pure ones carry the weight, because every check that matters is pure:

- `SubgroupBirthCertificateEventTest` (commonTest) — Phase 1's seven cases,
  including the one that must **pass**: a certificate whose name and admin set no
  longer match the room's, which fails the day somebody adds the roster check
  Phase 1 forbids.
- `GroupKeyStateTest` (commonTest) — Phase 3's five `stateFrom` cases.
- `SubgroupGuardsTest` (commonTest) — Phase 8's table, one case each, against
  the pure predicates rather than the ViewModel. The key-package row is the one
  worth writing first: it is the guard that exists to stop three ceremonies being
  spent on a room that cannot be created.

Two that need a database, in jvmTest, where `secp256k1` loads and Room runs:

- `SignedGroupKeyStateTest` extension — a real ChillDKG key, a real parent key, a
  real certificate signed by the parent, a real child key state carrying it, and
  the assertion that `stateFrom` accepts it and rejects each single-field
  mutation of it.
- `SubgroupDaoJvmTest` — schema 17 round-trips the four columns, and
  `subgroupsOf` returns a certified child whose room does not exist locally.
- `SubgroupCeremonyInParentRoomTest` (Phase 10) — the ceremony queued as inner
  events rather than gift wraps, the p-tags naming only the picked admins, a
  parent member outside them opening no session, a backlog replayed out of the
  inner-event store, and `completedKey` refusing to hand the parent its child's
  key.

One end-to-end, and it is the one that would have caught the collision in Phase
4: two ceremonies proposed over the same admin set land in the same room, and the
second is refused rather than silently returning the first's key.

## Phase 10 — the ceremony moves into the parent's room

*Followed up by [ceremony-creation.md](./ceremony-creation.md): with the
ceremony in the parent's room, the shared-key screen's "Start key ceremony"
offered a whole-room ceremony there, and a failed subgroup was retried as one.
A Marmot room now refuses a ceremony of its own, and a failed subgroup is tried
again from this picker, filled in from the ceremony that failed.*

The thing [Phase 4](#why-not-the-parents-marmot-room) deferred, done, because the
reason to wait was `docs/mls-skipped-keys.md` and that is being fixed. The child's
ChillDKG and its key state now run in the parent's Marmot room on group events;
the sibling NIP-17 room is gone, and with it the rule that one admin set could
hold one subgroup forever.

Schema 18 → 19, two nullable columns, both on `DkgSession`, and both are things
the sibling room used to answer:

| column | why the room cannot answer it any more |
|---|---|
| `participantPublicKeys` | the ceremony is with a *subset* of the parent's members, so the room's roster names every person the subgroup is not |
| `subject` | the room this ceremony's key derives is not called what the parent is called, and the parent's name is the one name a child must not take |

Both are read off the proposal, which has carried both since Phase 4 — the p-tags
and the `subject` tag. What changed is where they land: on the session, which is
the only thing in a shared room that knows which ceremony it is.

### Three costs, and two of them are silent

Phase 4 enumerated them. All three had to be paid, and the two that fail silently
are worth stating as rules rather than as changes.

**1. `ChillDkgRitualManager` grows the two-transport shape.** `broadcast` writes a
`MarmotInnerEvent` where the room has MLS state and a `GiftWrapPayload` where it
does not, `replayStoredMessages` reads the backlog out of whichever store that
same reading names, `processRitualPayload` takes an `Event` rather than a
gift-wrap payload, and `NostrDao`'s Marmot arm dispatches the DKG kinds beside
the FROST ones it already did.

**The p-tags stay on both transports**, unlike `FrostSigningManager`'s. There a
p-tag is an address and a group event needs none; here the p-tag set *is* the
participant set every device derives `n` from — and in the parent's room it is
also the only thing separating the subgroup's admins from everyone else who can
read the message. Dropping them the way the signing manager correctly does would
enrol the whole parent in the child's permanent signing quorum. Inside MLS
encryption, naming them leaks nothing.

**2. `DkgSession.chatRoomId` stopped identifying a ceremony**, and the failure is
silent in the one place it matters. `FrostSigningManager.completedKey`'s last
fallback is "a ceremony held in this very room" — the fallback every parent member
welcomed after the group's own ceremony lands on, since they hold neither a share
nor a key state. The parent's room now holds a *completed* ceremony whose key is
the child's, and handing that back would have the parent sign as its own subgroup
with nothing to see. `getLatestOwnSessionForChatRoom` is that fallback scoped to
`parentChatRoomId IS NULL`: a ceremony run to make a subgroup is never the room's
own key, and that is the whole of what has to be read to know it.

`proposeRitual`'s guard moved for the same reason and in the other direction —
see the Phase 8 row above.

**3. `signingPath` admits a Marmot room**, narrowly. It has one case that cannot
be self-checked: the room a ceremony ran in, signing the statement that lets the
room the ceremony's key derives be created. That used to mean "a NIP-17 room whose
ceremony is its own"; it now also means "the parent's room, where the ceremony
claims that parent". The claim is unverified and grants nothing — the signature it
enables is by the *child's* key over the *child's* own id, both derived from a
ceremony every signer contributed to, so a member who lied about the parent gets
what telling the truth would have got them.

### What it buys

- **A subgroup no longer costs a room.** Nothing appears in anybody's chat list
  that is not a group.
- **One admin set can run many subgroups.** The permanent collision is gone,
  because there is no member-derived id left to collide; what is refused is only a
  ceremony for the same people under the same parent that is still running.
- **Every step of a subgroup happens in one place.** The picker hands back to the
  parent's transcript, and the ceremony, the certificate and the key state are all
  answered from there — which is where `observeCeremoniesAwaitingYou` and
  `observeProposalsAwaitingYou` were already looking.
- **`DkgSession.parentChatRoomId` is no longer a hint alone.** It still
  authenticates nothing, and nothing may be granted on it that would not be
  granted without it — but two lookups now scope on it, so the note where it is
  declared has to be read before either is widened.

### What it costs

A ceremony now rides the transport where a message arriving a moment late is
dropped for good, which is the whole of Phase 4's objection and is not answered by
this phase — it is answered by the quartz fix `docs/mls-skipped-keys.md`
describes. A DKG is the worst payload for that bug: it cannot finish until every
participant takes part, so one lost round-1 message stalls it for everybody.
`replayStoredMessages` covers a message that lost a race to the proposal, since
the inner event is stored before it is dispatched; it cannot cover a message the
tree could never decrypt.

## Rollout

Nothing here changes an existing flow's behaviour. The two shared pieces are the
risk, and both are additive:

- `GroupKeyStateEvent.assembleTags` emits the new tags only when a parentage is
  passed, so every state signed before this ships verifies exactly as it did.
- `GroupKeyStateManager.stateFrom`'s new checks fire only on a state carrying one
  of the new tags. A state with neither takes the path it takes today.

An older build meeting a subgroup's key state ignores two unknown tags and files
the state as an ordinary one — it loses the parent link and keeps a correct
answer to what the room signs with, which is the right way round. An older build
meeting a **certificate** has no 30329 arm and writes a raw-JSON chat bubble in
the parent room, once per subgroup. That is cosmetic, it is the same thing
`docs/member-chronicle.md` reports for chronicle pages, and it is the reason to
ship Phase 5's dispatch arm before anybody creates a subgroup rather than after.

Phases 1-3 can ship dark: they add a kind nothing produces and checks nothing
triggers. Phases 4-6 are inert without Phase 7's entry point. So the flag, if one
is wanted, is the button.

## What this does not do

**A subgroup cannot sign for its parent, and the parent cannot sign for it.**
Two independent keys, two independent quorums. The certificate is a claim about
lineage, not a delegation of authority, and nothing in this design lets one group
act as the other. Delegation is a different mechanism, and it was built as one:
[group-wallet-guardians.md](./group-wallet-guardians.md) lets a parent guard a
child's wallet, but only when the parent's quorum signs guardian keys into the
certificate *and* the child's quorum opens a guarded wallet naming them. The
certificate on its own still delegates nothing.

**A member added to the parent later sees no subgroups, and a member added to the
subgroup later cannot verify its parent.** One cause, two faces, and both are the
chronicle allowlist. Certificates are not chroniclable, for the reason Phase 5
gives, so a member welcomed into the *parent* after a subgroup was made holds no
certificate and gets an empty list. A member welcomed into the *child* holds no
key state either — `adopt` files one only from the signed event, which only
ceremony participants have — so their verified parent link is null, and the
description hint from Phase 6 is all they get: a claim by the room's creator
rather than a statement by the parent.

Chronicling the certificate fixes both at once, and the argument for admitting it
is better than it is for a key state: a certificate says "P certified C", a fixed
historical fact, whereas a key state decides what a live room signs with, so a
replayed certificate changes nothing a fresh one would not. It still deserves its
own change — its own idempotency test, its own apply-order slot, and a decision
about whether the child's chronicle may carry an event its own key did not sign,
which no chroniclable kind does today. That last one is the real work, and it is
why this is a follow-up rather than a line in Phase 5.

**~~One parent, one admin set, one subgroup.~~ Gone in Phase 10.** It narrowed
twice and then disappeared. First to "the same parent asking twice over the same
admins", once `DkgSession.parentChatRoomId` told two ceremonies in a room apart.
Then to nothing permanent at all, because moving the ceremony into the parent's
Marmot room left no member-derived id to collide: what is refused now is only a
ceremony for those admins under that parent that is **still running**, and a
finished one is a subgroup that exists rather than a slot that is used up.

**Every selected admin has to show up, twice.** A ChillDKG cannot finish without
all `n`, and the key state then needs a quorum of them. A member who never
approves stalls the subgroup at step 1 indefinitely, and the only signal is the
ritual screen naming who it is waiting on. This is inherited from
`docs/shared-key-ceremony.md` and is the single biggest practical cost of a
subgroup.

**A subgroup of a subgroup is untested.** Nothing forbids it — the child is an
ordinary robust group with a key of its own, so it can certify a child in turn —
and the chain is checkable link by link. But no phase here exercises depth 2, and
the parent row on the detail screen shows one level rather than a path.

**Removing a subgroup is not a thing.** There is no revocation, and a certificate
is a signature that exists forever once made. Deleting the child's room locally
leaves the parent's list showing a certified child nobody has.

## Appendix — what was considered and rejected

**A derived child key at `m/9420/1/0`, with no ceremony.** Free, instant, and
wrong: `docs/shared-key-derivation.md` states that anyone learning one derived
private key recovers the threshold key, and more to the point the child would be
administered by the parent's members with the parent's quorum. That is a channel,
not a subgroup.

**The certificate as a bare 64-byte signature plus a rebuild rule.** Smaller on
the wire, and it makes `GroupKeyStateManager.stateFrom` responsible for
reconstructing byte-for-byte the event the parent signed — kind, author, tags, and
`created_at` — from fields carried alongside. The first change to the
certificate's tags then invalidates every state already signed, silently, because
a rebuilt event that differs by one byte hashes to an id whose signature does not
verify and is indistinguishable from a forgery. Storing the whole event costs a
few hundred bytes inside an encryption and removes the class.

**A `Subgroup` table.** The certificates already are the record: signed, held by
every parent member, and verifiable without the table. A table would be a second
copy that can disagree with them, and the only thing it would add is flow state
for the coordinator — which `DkgSession.parentChatRoomId` covers with one column.

**~~Running the child's ChillDKG and key state inside the parent's Marmot room.~~**
Not rejected — deferred, argued in full in
[Phase 4](#why-not-the-parents-marmot-room), and then
[built](#phase-10--the-ceremony-moves-into-the-parents-room) once the reason to
wait was being fixed. The two did move together, the mechanical cost was the
three changes Phase 4 enumerated, and `docs/mls-skipped-keys.md` remains the thing
this leans on: a ceremony needs every participant, and group events lose one of
any two that arrive back to back until that lands.

**A dedicated subgroup derivation path.** `m/9420/0/0` is reused, because the
path is walked from the *child's own* key and two different keys at the same path
derive two different rooms. A separate constant would say something true about
lineage in a place nothing reads for lineage.

**A parallel subgroup ritual screen.** Rejected in Phase 7: it duplicates four
pieces of a five-piece screen to insert one, and the copies drift.
