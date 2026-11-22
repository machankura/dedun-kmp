# The group's direct messages, by threshold ECDH

A plan for a group to send and receive NIP-17 direct messages as its own npub, with
nothing on the wire that a stock NIP-17 client would not send or expect:

1. **Anyone can write to the group.** A person on any NIP-17 app writes to the group's
   npub exactly as they would write to a person. They need no app that knows about groups,
   and no key the group announced.
2. **The admins read it.** Every current admin's device can read what the group is sent, and
   nobody else's can: not the group's other members, not a former admin, and not a relay.
3. **The group writes back, or first.** An admin drafts a message; it is sent only once the
   group's quorum has signed it, and it arrives as an ordinary NIP-17 message from the
   group's npub, readable by any NIP-17 app.

The difficulty is that NIP-17 is built on ECDH with the recipient's key, and nobody holds
the group's key. [confederation.md](./confederation.md#d5-encrypting-the-admin-list-a-seal-every-signer-can-recompute)
draws the conclusion that "nothing can be gift-wrapped *to* a group's npub". This plan
removes that conclusion without changing the premise. ECDH, like a signature, can be
computed by the share-holders together: each multiplies the other party's key by its own
share, and any `t` of the results combine into the one point the whole key would have
given. Nobody learns the group's key, and the conversation key that comes out is exactly
the one a single-key NIP-44 implementation would have derived.

Read [shared-key-derivation.md](./shared-key-derivation.md) first. Its rule (never
reconstruct a derived key in the clear) is the rule this plan is built around, and its
tweaks are the one subtle part of the arithmetic (D2). Then read
[frost-share-refresh.md](./frost-share-refresh.md), for share generations and why the
group's key outlives every rotation. After those,
[group-nostr-profile-and-posts.md](./group-nostr-profile-and-posts.md) shows how a group
proposes, signs and broadcasts in its own name, and
[marmot-direct-messages.md](./marmot-direct-messages.md) covers the last time this app
built a gift wrap by hand.

There is a sibling plan for the same goal by another route: a NIP-4E inbox key, generated
by its own ceremony and held whole by every admin (`group-inbox.md`, written alongside this
one and not merged). *Against the NIP-4E plan* below says where the two differ, and how
they fit together if both are built.

**Being built.** Phase 0 is done, and its findings changed D4 and D11. Written against `5ac4c053`, on 2026-11-22. Paths starting `K/` are
under `composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/`, and `B/` is bitcoin-kmp's
`src/commonMain/kotlin/fr/acinq/bitcoin/`, under
`lightning-kmp-app/experimental/lightning-kmp/experimental/bitcoin-kmp/`.

---

## The arithmetic

The group's threshold key is `X = x·G`. Nobody holds `x`. Each share-holder `i` of the
active generation holds a Shamir share `sᵢ`, and for any set `S` of `t` holders,
`x = Σᵢ∈S λᵢ·sᵢ`, where `λᵢ` are the Lagrange coefficients at zero over the ids in `S`.
FROST signing relies on exactly this, inside libsecp256k1-zkp.

The group's npub is not `X`. It is the key derived at `m/9420/0/0` by three x-only tweaks
([shared-key-derivation.md](./shared-key-derivation.md)), and its secret, which nobody
holds either, is

```
d = a·x + b          where a ∈ {1, −1} and b are public, fixed by replaying the tweaks
```

NIP-44's conversation key between the group and a nostr key `P` is

```
convKey = HKDF-extract(salt = "nip44-v2", IKM = x-coordinate of d·P)
```

and `d·P` can be computed without `d`:

```
each holder i ∈ S:     Dᵢ = sᵢ·P                       one scalar multiplication
                       πᵢ = DLEQ(Yᵢ = sᵢ·G, Dᵢ = sᵢ·P)  a proof that Dᵢ is honest (D3)
the combiner:          x·P = Σ λᵢ·Dᵢ
                       d·P = a·(x·P) + b·P
```

Three properties do all the work:

- **Every subset gives the same point.** Any `t` honest holders produce `x·P`, so whoever
  answers first is enough.
- **A partial alone is useless.** `Dᵢ` reveals nothing about `sᵢ` (it is a Diffie-Hellman
  value), and fewer than `t` partials reveal nothing about `x·P`.
- **The sign of the whole does not matter.** NIP-44 reads only the x-coordinate, and
  `x(Q) = x(−Q)`. So neither `P`'s lifted parity nor the npub's final even-y normalisation
  needs care. The sign of `a` *inside* the sum does matter, which is D2.

What comes out is the conversation key of the pair (group, `P`), the one any NIP-44 library
computes from `d` and `P`. That is the whole interoperability argument: a sender's app
cannot tell the group's key apart from a person's.

---

## What NIP-17 asks of the group

A message to the group arrives as:

```
gift wrap (1059)   pubkey W, a fresh random key        p = the group's npub
                   content = nip44(seal, conv(w, group))
seal (13)          pubkey S, the sender                 signed by S
                   content = nip44(rumor, conv(s, group))
rumor (14)         pubkey S, the message
```

So reading one message takes two group ECDHs. **`d·W` is new on every message**, because the
wrap key is fresh. **`d·S` is the same for every message from that sender**, and once
computed it can be kept (D6).

A message from the group is:

```
rumor (14)   pubkey = the group's npub, unsigned
seal (13)    pubkey = the group's npub, signed by FROST
             content = nip44(rumor, conv(group, R))       one group ECDH per recipient R, kept
gift wrap    pubkey = a fresh key this device holds       ordinary: no group secret involved
             content = nip44(seal, conv(w, R))
```

So writing takes one group ECDH per recipient (kept afterwards) and one FROST signature
per recipient's seal. The signature is the existing signing session; the ECDH is this plan.

---

## What Giza does today

Traced through the code at `5ac4c053`. Phase 1 pins them.

| event | where it lands | what happens |
|---|---|---|
| a kind 1059 `#p`-tagged to a group's npub | nowhere: `LiveSubscriptionManager` subscribes to `#p` = the active identity, on `Relays.DefaultDMRelayList` | never fetched. If one arrived another way, `NostrDao.indexNostrEvent` stores a `GiftWrapMessage` and never opens it, because only the active identity's key is tried |
| a peer proposes a kind 13 for the group to sign | `FrostSigningManager.acceptProposal` | no refusal fires, because the signer has no allowlist. It is asked as "Event of kind 13" and can be signed: a ciphertext the signer cannot read, which is the rubber stamp [giza-wallet-removal.md](./giza-wallet-removal.md) says a Giza device must never be |
| a peer proposes a kind 10050 | same | "Event of kind 10050", signable. Signing it leaves nothing behind |
| either completes | `ChatMessage.applyInnerEvent` | `unsupported()`: a raw-JSON bubble |
| the user's own NIP-17 send | `NostrNip17Dao.persistAndBroadcastGiftWrap` | to `DefaultDMRelayList` only. The recipient's 10050 is looked up, and the block that would use it is commented out (`:186-218`) |

What already exists and is reused:

- **The share, the public shares and the ids.** `ShareGenerations.active(database, key)`
  returns this device's `secretShare` (a `PrivateKey`), every holder's `publicShares`, the
  `signerIds` by member, and the `roster` with each holder's **host key**. That is every
  input the arithmetic needs, and none of it leaves the device.
- **The point and scalar operations.** bitcoin-kmp's `PublicKey.plus`, `unaryMinus` and
  `times(PrivateKey)`, and `PrivateKey.plus`, `times` and `unaryMinus` (`B/PublicKey.kt:48-60`,
  `B/PrivateKey.kt:47-53`). Each is a libsecp256k1 call, so **every operation on a secret
  stays inside libsecp256k1's constant-time code**. Kotlin only handles public values.
- **The tweaks.** `SharedKeyDerivation.Derived.tweaks` carries the three scalars `derive`
  applied. The confederation already replays them by hand to reach the same key
  ([confederation.md](./confederation.md) Phase 1, `ConfederationRoomKeyJvmTest`).
- **The pairwise channel.** `ShareRepairManager.seal`/`open` (`:414-421`) NIP-44-encrypts
  between two host keys, so that only those two devices can read what passes inside an MLS
  room every member receives. D4 uses the same channel.
- **The signing session, the refusals, and the owed-publisher.** `proposeSigningBatch` with
  hidden `proposalTags`, the four places a refusal sits, and `ConfederationPublisher`'s
  pattern for sending a signed event with backoff.

---

## Decisions

### D1. The group's own key, not a second one

The group reads and writes with the key it already has. There is no new ceremony, no
announcement, no second secret on any device, and nothing to rotate. The shares already
rotate ([frost-share-refresh.md](./frost-share-refresh.md)), and a refresh changes every
`sᵢ` without changing `x`. So the conversation keys stay the same across rotations, while
the shares a former admin holds stop combining with anyone's.

### D2. The tweaks are replayed in Kotlin, with their signs

The npub is `X` walked through three x-only tweaks. For an x-only tweak, BIP 327 (which
FROST's tweak cache follows) negates the key first when its y is odd:

```
a₀ = 1, b₀ = 0, Q₀ = X
for each tweak τ:
    g      = 1 if Qₖ has even y, else −1
    Qₖ₊₁   = g·Qₖ + τ·G
    aₖ₊₁   = g·aₖ
    bₖ₊₁   = g·bₖ + τ
npub = x-only(Q₃),   d = a₃·x + b₃
```

`a` and `b` are public, computed by every device from `X` and the tweak list. Getting `a`
wrong gives `−x·P + b·P` instead of `x·P + b·P`: a different point whose x-coordinate does
not match, so every decryption fails. That is why Phase 0 checks it against the library's
own tweak cache, and against a NIP-44 key derived the ordinary way from a test key. Only in
the test is `d` built whole, from a dealer key made for the test.

`TweakCache` is opaque bytes from libsecp256k1-zkp (`B/crypto/frost/Frost.kt:60`), so the
replay cannot read `a` and `b` out of it. It recomputes them, and checks that its `Q₃`
equals `Derived.publicKey`.

### D3. Every partial carries a proof, and a bad one is named

A holder who sends a wrong `Dᵢ` would make the combined point wrong. NIP-44's MAC would
catch that, but not say whose partial it was. So every partial carries a Chaum-Pedersen
proof that `log_G(Yᵢ) = log_P(Dᵢ)`, where `Yᵢ` is the holder's public share at the stated
generation:

```
k   = TaggedHash("mantra/threshold-ecdh/dleq-nonce/v1", sᵢ || P || Dᵢ || aux) mod n
R₁  = k·G,   R₂ = k·P
c   = TaggedHash("mantra/threshold-ecdh/dleq/v1", room || generation || i || Yᵢ || P || Dᵢ || R₁ || R₂) mod n
z   = k + c·sᵢ
proof = (c, z)

verify:  R₁ = z·G − c·Yᵢ,   R₂ = z·P − c·Dᵢ,   and c recomputes
```

`aux` is 32 fresh random bytes, so a fault in one run cannot repeat a nonce across two.
The challenge binds the room, the generation and the id, so a proof cannot be replayed into
another round. A hash that lands at or above `n` fails the proof rather than being reduced,
which happens with probability about 2⁻¹²⁸. bitcoin-kmp's `PrivateKey` constructor does not
range-check (Phase 0, check 4), so the proof calls `isValid()` on every hash it reads as a
scalar. The tags are hash inputs, spelled `mantra/` as
every protocol tag is ([giza-rebrand.md](./giza-rebrand.md)).

A partial whose proof fails is dropped, its sender is named in the inbox's diagnostics
once per round, and the round waits for another holder. This is the only cryptography the
plan writes by hand. It is about forty lines over libsecp256k1 calls, and Phase 2 tests it
against published Chaum-Pedersen vectors where they exist and against itself where they
do not.

### D4. Partials go to the current admins, sealed to their host keys

Partials travel as MLS inner events in the group's Marmot room, the room its key lives in,
which every member receives. A member who is not an admin must not be able to combine
them, so each partial message's payload is NIP-44-encrypted once per current admin, to that
admin's host key from the roster (the share-repair channel). Phase 0 (check 6) changed how:
the payload is NIP-44-encrypted **once**, under a fresh 32-byte key, and only that key is
sealed per admin. Sealing the whole payload per admin multiplied it by the number of admins.

**Current admin** means `RoomAdmins.of(room)` intersected with the holders of the active
generation. A device sends its partials only if its own member is in that set, and seals
them only to the others in it. That one rule gives the plan its strongest property:

- **A removed admin stops being able to read new messages the moment their removal lands.**
  Nobody sends them partials any more, even before the share rotation that follows takes
  their share away.

A share-holder who is no longer an admin (possible while share refresh is experimental)
neither sends nor receives partials.

### D5. A device multiplies only by a key whose owner has signed something it can check

Answering partials makes the group a "multiply by `x`" service for its admins. The known
weakness of such a service is the static Diffie-Hellman oracle (Brown–Gallant, Cheon): fed
chosen points that are themselves powers of the secret, `x·G`, `x²·G`, and so on, enough
answers wear the secret's security margin down. That needs points whose discrete log the
requester does not know.

So a device computes `sᵢ·P` only when it holds a **Schnorr signature by `P`** that it has
verified, on an event of the right shape for the purpose:

| purpose | `P` | the signed evidence |
|---|---|---|
| open a wrap | the wrap's pubkey `W` | the kind 1059 itself, `p`-tagged to the group's npub |
| open a seal | the seal's pubkey `S` | the kind 13 itself, out of a wrap this round opened |
| write to someone | the recipient `R` | any event signed by `R` (a kind 0, 10050 or 14 the device has, or one the proposer carries) |

A signature by `P` proves its signer knows `log_G(P)`, so no point can be `x^k·G`. The rule
also keeps out the group's own npub (`x²·G` is exactly the dangerous query), so the group
never encrypts to itself. D10 is shaped around that.

### D6. Wrap points are spent once; sender and recipient points are kept

`d·W` opens one wrap and is not kept; the opened message is. `d·S` and `d·R` give a
conversation key that is the same for every message with that person. It is kept in
`GroupConversationKey`, so after the first message from or to someone, reading their next
message costs one round instead of two, and writing to them costs none.

A kept key is only as safe as the device it is on. A stolen admin phone gives up the kept
conversation keys, but not the means to open new wraps, which still take `t` holders, and
the phone is not sent partials once its owner is removed (D4). This trade is stated in
*Risks*.

### D7. Nobody is asked to read; the group is asked to write

An admin's device answers partials **without asking**, whenever the app is open and the
evidence checks out (D5). Being an admin is the decision to read the group's mail. A person
cannot add anything to "should my phone help open a message the group was sent", and one
who could say no would only be holding the group's mail shut.

Writing is different. Every message the group sends is a FROST signing session over its
seals, asked of `t` holders like any statement in the group's name. Proposing is the
proposer's approval, as always.

The group's decision to *receive* direct messages at all is its signature on a kind 10050
(D8). Until it signs one, devices do not subscribe for the group's mail.

### D8. The group's DM relays: a group-signed 10050

A sender's app sends NIP-17 to the recipient's 10050 relays, and many send nothing when
there are none. **Open the group's messages** proposes a kind 10050 for the group to sign,
filled with `Relays.DefaultDMRelayList`, which the member can edit first. Its approval
screen says, in plain words, that anyone on nostr will be able to write to the group and
every admin will be able to read it.

A refusal, `GroupDmRelayRefusal`, declines a 10050 whose relays are not all public `wss://`
URLs (the profile plan's `relayUrlOrNull`). Once signed, the proposer's device publishes it
through an owed-publisher, with backoff. `BROADCASTABLE_KINDS` gains 10050 so any member can
send it again. It writes no `NostrEvent` row, for the same reasons as the profile (D7 of
that plan).

This brings back one of the four relay lists that
[group-nostr-profile-and-posts.md](./group-nostr-profile-and-posts.md#d1-profile-and-posts-only-relay-lists-stay-out)
left out, and only that one.

### D9. Reading is its own pipeline, never the personal one

The personal DM path is built around one identity, and it files what it opens into rooms
keyed by participants. The group's mail would mix the group's correspondents into the
admin's own DM list. So the group's mail has its own path, and writes no `GiftWrapMessage`,
`GiftWrapPayload` or `NostrEvent` row.

1. **Subscribe.** `LiveSubscriptionManager` holds `live-group-dm` while the flag is on: kind
   1059, `#p` = the npub of every room where this device's member is a current admin and
   the group has signed a 10050, on those 10050s' relays and the app's DM relays. Catch-up
   uses `since` = the newest wrap seen minus two days, since NIP-59 back-dates wraps.
2. **Ledger.** Every wrap goes into `GroupDmWrap`, keyed by id, with its state. Redelivery
   is a no-op.
3. **Wrap round.** For a new wrap, the device opens a round for `W` (D11) and sends its own
   partial at once. The partial names the wrap by its id rather than carrying it (Phase 0,
   check 6): every admin's device subscribes to the same wraps, and one wrap of a long
   message is most of a relay's event on its own. A device that has not seen the wrap yet
   holds the partial and answers when the wrap arrives.
4. **Open the wrap.** At `t` valid partials: `d·W`, the conversation key, the seal. Verify
   the seal's signature.
5. **Seal round.** If `conv(group, S)` is kept, use it. Otherwise open a round for `S`,
   carrying the seal as evidence.
6. **Open the seal.** Check the rumor's pubkey is `S`, and file it in `GroupDmMessage`:
   rumor id (unique), wrap id, sender, the rumor JSON, its time, and a conversation id (the
   sorted set of the sender and the rumor's `p` tags, without the group, as NIP-17 defines
   a chat). A rumor that is not kind 14 is filed as one this screen cannot show yet, not
   dropped.

Every admin's device runs all six steps and combines for itself, since each receives every
partial. Nothing is decrypted once and passed round, so nothing but partials ever crosses
the room.

**History comes with the role.** `x` never changes. So a newly promoted admin's device can
open any wrap still on the relays, by asking the other admins for partials like any other
round. Unlike the NIP-4E plan, a promoted admin reads the group's past mail. Whether that is
right is a decision, and D14 makes it.

### D10. Writing: one seal per recipient, signed by the group, wrapped by the proposer

An admin writing to a conversation, or to someone new, builds:

```
rumor   kind 14, pubkey = the group's npub (the room id), unsigned
        tags: ["p", <each recipient>] …, and ["e", <message>, <relay>, "reply"] for a reply
        content: the text

for each recipient R:
  seal  kind 13, pubkey = the group's npub, tags = []
        content    = nip44(rumor, conv(group, R))        conv from a kept key, or a round for R
        created_at = now minus up to two days, at random (NIP-59)
```

The seals go to the quorum in one batch through `proposeSigningBatch`. Once the session
completes, the proposer's device wraps each seal under a fresh key of its own, `p` = `R`,
encrypted to `R`, and sends it to `R`'s 10050 relays, falling back to the publish set. That
wrap is ordinary NIP-59: no group secret is involved. It is filed as owed and sent with
backoff by a `GroupDmPublisher`. If the proposer's device is gone, any admin can **Send
again**, since the signed seals are `GroupSignedEvent` rows on every signer's device.

There is **no copy to the group itself**. NIP-17 suggests a sender wrap a copy to their own
key so their other devices see it. Here that would mean ECDH with the group's own npub,
which D5 forbids. It is not needed either: the signing session reaches every admin's device
through the room, and that is where the group's sent messages are read from (D12).

### D11. Rounds are keyed by the point, and settle themselves

A round is `(room, generation, P)`. Its id is a hash of those three, so two devices that
open a round for the same wrap open the same round. A partial message carries, per item,
the round's point, the purpose, the evidence's reference, `Dᵢ` and the proof. A wrap and a
seal are referenced by the wrap's id: every admin's device holds the wrap, and opens the
same seal from it. Only a recipient's evidence (D10) is carried, since it is a small event
no other device need have. Each device:

- **batches**: every point it owes a partial for goes into one message per sync pass, not
  one message per point, up to 16,000 bytes of plaintext (about thirty items) per message;
- **answers** a round it has evidence for and has not answered, once (keyed by round and
  participant id, as `DkgParticipantMessage` absorbs redelivery);
- **combines** as soon as it holds `t` verified partials of the same generation;
- **restarts at the new generation** a round a share refresh overtook. Partials of
  different generations never combine, and a device answers only with its active share.

There are no transcript lines for rounds. One per message the group is sent would bury the
room. The inbox screen shows what is waiting, and on whom.

### D12. Only admins see anything, including what the group sends

A signing proposal reaches every member of the room. Its content is the unsigned seals,
which are ciphertext. What the signers also need is which seal is for whom, and the
evidence for each recipient's key. That rides as a proposal tag, NIP-44-encrypted once per
current admin's host key (D4's channel):

```
["dm_manifest", <host key>, <nip44(manifest, conv(host secret, that admin's host key))>]   one per admin

manifest:
{"v":1,"seals":[{"recipient":"<pubkey hex>","evidence":<an event signed by the recipient>}, …],
 "replying_to":"<rumor id or null>"}
```

A member who is not an admin sees that the group is signing so many seals, which is how
many people the message goes to, and nothing else. An admin's device decrypts the manifest,
opens each seal with the kept `conv(group, R)`, and files the message as outgoing in its
conversation. That is how every admin sees what the group sent, whether or not they signed
it.

### D13. Every signer reads the message before signing it

`GroupDmRefusal` joins `WalletRefusal`, `GroupStatementRefusal`, `GovernanceManager.refusalOf`
and `ConfederationRefusal` in the four places: `acceptProposal`, the `approve` backstop,
`publishDecline`'s reason, and the unshowable-session check. A signer:

1. decrypts the manifest addressed to its host key. A signer that has none, or is not a
   current admin, **declines** out loud ("This device can't read what the group would be
   sending"). It is a share-holder outside D4's set, and signing what it cannot read is the
   rubber stamp;
2. checks every item is kind 13, authored by the room, with no tags;
3. checks each recipient's evidence is validly signed by that recipient;
4. gets `conv(group, R)` for each recipient, from its kept keys or by joining the
   proposer's round for `R`. **Until it has them, the approval waits rather than
   declining**, and the screen says it is waiting for the admins to compute the key;
5. opens every seal and checks they hold the **same** rumor, byte for byte: kind 14,
   pubkey the room, no signature, non-blank content, and `p` tags exactly the manifest's
   recipients;
6. checks each `created_at` is within two days before now and not in the future.

A proposer who wrote one thing to one recipient and another thing to another, sealed to a
key that is not the recipient's, or named a recipient the rumor does not, is declined by
every honest signer. The approval screen shows the message, what it answers, and its
recipients by name.

### D14. Admin changes need nothing new, and a promoted admin reads the past

- **A removal or demotion** takes effect for reading at once (D4), and for signing when the
  share rotation the change already owes completes
  ([admin-change-rotation.md](./admin-change-rotation.md)). When a device sees itself
  removed, it wipes its kept conversation keys and opened messages for the room, and writes
  a transcript line saying so. That is cooperation, not enforcement: what a removed admin
  already read, they keep.
- **A promotion** gives the new admin a share at an id (member-admin-promotion), and with it
  the ability to answer and combine. Their device fetches the wraps still on the group's
  relays and opens them like any others, so the group's history comes with the role.
  **Recommended:** keep it that way. The admins are the group's correspondents, and an
  admin who cannot see a conversation already under way cannot answer it. If the group
  wants otherwise, D4's rule can be narrowed to "answer only for wraps created after the
  requester's promotion". That is a policy every device can check, and it is listed under
  *Out of scope* rather than built.

### D15. Behind a flag

`ExperimentalFeatures.groupDirectMessages`, `override ?: isDevelopmentBuild()`, as
`confederation` and `shareRefresh` are. Since a group's admins hold shares only in a keyed
room, and refresh is itself experimental, the flag stays off in release until the device
run (Phase 11) has passed. The refusals in D8 and D13 run whatever the flag says, as every
refusal does. A release build declines a peer's kind 13 proposal out loud rather than
signing it blind.

---

## Against the NIP-4E plan

| | threshold ECDH (this plan) | NIP-4E inbox key (`group-inbox.md`) |
|---|---|---|
| a sender on a stock NIP-17 app | **reaches the group** | does not: encrypts to the npub, which only this plan can open |
| a recipient on a stock NIP-17 app | **reads the group's message** | may not: it has to read the seal's `n` tag |
| reading a new message | when `t` admin devices have answered: they answer unasked, but only while their apps are open | at once, by any one admin |
| one stolen admin phone | kept conversation keys only; no new message opens without `t` holders | the whole inbox, until the next rotation |
| a removed admin | cut off from new mail at once | until the rotation's ceremony and announcement complete |
| a promoted admin | reads the past (D14) | starts empty |
| rotation | none: share refresh already does it | a ceremony and a signed 10044 per admin change |
| spec | NIP-17 and NIP-44 as published | two open draft pull requests |
| new cryptography | partials, a DLEQ proof, tweak replay | none (ChillDKG at `t = 1`) |

The two compose. If both are built, a group that announces a 10044 reads 4E senders at once
through the inbox key, and everyone else through this plan. Each pipeline subscribes to the
same `#p`, so the ledger has to try the inbox key first and open a round only for what that
key cannot open. This plan does not depend on the other and is written to stand alone.

---

## Who is asked what

| step | who | asked? |
|---|---|---|
| open the group's messages (propose the 10050) | any admin | the button |
| sign the 10050 | `t` holders | **yes: the group's decision to receive** (D7, D8) |
| publish it | the proposer's device | no |
| answer a partial | every admin's device, while open | no (D7) |
| combine and read | every admin's device | no |
| propose a message | any admin | the Send button |
| answer the recipient's round | every admin's device | no |
| sign the message's seals | `t` holders | **yes: each message** (D13) |
| wrap and send it | the proposer's device | no |

---

## Phases

One commit per phase, in the repository's style
(`feat: … (group-threshold-ecdh, Phase n)`). Every phase leaves
`./gradlew :composeApp:jvmTest` and `:composeApp:m3Audit` green, and `check`'s brand and
wallet audits at budget.

### Phase 0: this document, and six checks (done)

`ThresholdEcdhPremiseJvmTest` keeps all six. What they found:

| check | result |
|---|---|
| 1. ids | the evaluation point is the id plus one, as BIP 445 says; every 3-subset of a 3-of-5 dealer key rebuilds `x`, and the unshifted ids do not |
| 2. tweaks | D2's replay reaches `Derived.publicKey` for forty dealer seeds, which between them meet both parities at all three steps |
| 3. the conversation key | **holds.** Every 3-subset, either lift of `P`, gives quartz's `computeConversationKey(d, P)`, which equals the sender's side; the wrong sign of `a` gives another key |
| 4. scalars | `times` and `plus` reduce mod `n`; Fermat's inverse works; **the `PrivateKey` constructor does not range-check**, so D3 calls `isValid()` |
| 5. kinds | 30400 and 30401 are free in the NIP README, quartz, Bennu's tree, this one and `RetiredKinds` (30402 and 30403 are NIP-99's) |
| 6. size | **D4 and D11 changed.** Sealing each payload per admin with the wrap as evidence was 918 KB for 20 items and 7 admins. The payload is now encrypted once under a key sealed per admin, and wraps and seals travel by reference: 20 items for 7 admins is 10.9 KB |

The original text of the checks:

This document and its row in [README.md](./README.md). Then, before code, as scratch
jvmTests against bitcoin-kmp's `Frost.trustedDealerKeygen` (`B/crypto/frost/Frost.kt:313`),
which gives a known `x` to check against:

1. **Ids.** `KeyMaterial.publicSharesOf` indexes by a 0-based id
   (`B/crypto/frost/Frost.kt:48`). Confirm the Shamir evaluation point is `id + 1`, as BIP
   445 specifies, by reconstructing the dealer's `x` from every `t`-subset with
   Kotlin-computed `λᵢ`. If not, record the convention the library uses.
2. **Tweaks** (D2). Replaying `Derived.tweaks` from `X` with D2's signs gives
   `Derived.publicKey`, for a key where every step's `Qₖ` parity case occurs. Search dealer
   seeds until one does, and pin that seed.
3. **The conversation key.** For a dealer key, D2's `d`, and a random `P`:
   `HKDF-extract("nip44-v2", x(a·Σλᵢsᵢ·P + b·P))` equals quartz's
   `Nip44v2.computeConversationKey(d, P)`. This is the interoperability claim, tested in the
   one place `d` may exist: a test with a throwaway key.
4. **Scalars.** `PrivateKey.times` and `plus` reduce mod `n`. The inverse `λᵢ` needs is
   computed as `v^(n−2)` by square-and-multiply over `times`. A hash at or above `n` is
   rejected by the `PrivateKey` constructor rather than reduced, which is what D3 wants.
5. **Kinds.** Two new kinds, candidates **30400** (partials) and **30401** (a round's
   failure, for D3's blame). 30388 is reserved by the confederation
   (`ConfederationRitualEvents.kt:34`), and the sibling plan proposes 30390–30396. Check the
   candidates against the NIP README's table, quartz, Curare, Bennu and `RetiredKinds`, and
   record the result.
6. **Size.** A partial message for a batch of 20 points, sealed to 7 admins, fits an MLS
   application message on the relays the app uses. If it does not, D11's batch is capped.

If check 3 fails, stop. Every message would be unreadable, and the arithmetic needs
another look before anything else.

### Phase 1: pin today

`GroupDmTodayJvmTest`, written to pass now and flipped on purpose:

- a kind 1059 `p`-tagged to a group's npub is stored by `storeNostrEvent` and never opened
  (Phase 7 flips it: the subscription hands it to the new pipeline, and the old path skips
  it);
- a peer's kind 13 and kind 10050 proposals reach the owner's approval prompt as "Event of
  kind N" (Phases 6 and 10 turn them into declines unless they check out);
- `live-group-dm` does not exist.

### Phase 2: the arithmetic, alone

No database, no wire. `K/crypto/threshold/`:

- `Lagrange.coefficient(id, ids)` over the confirmed convention, with `inverse` by Fermat.
- `NpubTweak.of(thresholdPublicKey, path)`: D2's `(a, b)`, checked against
  `SharedKeyDerivation.derive`.
- `EcdhPartial.compute(share, P)`, `Dleq.prove(share, Yᵢ, P, Dᵢ, context)` and
  `Dleq.verify(...)`: D3.
- `ThresholdEcdh.combine(partials, ids, tweak, P)`, which gives `d·P`, and
  `ThresholdEcdh.conversationKey(point)`, which gives NIP-44's key.

Tests (`ThresholdEcdhTest`, pure):

- every `t`-subset of a 3-of-5 dealer key gives quartz's conversation key, and so does a
  subset larger than `t`;
- `t − 1` partials give a key that does not match;
- a proof verifies, and fails for another point, another generation, another id, another
  `Dᵢ`, and a swapped `c` or `z`;
- the tweak replay matches `derive` in all parity cases;
- `P` given as x-only lifts to the same key whichever parity is chosen.

Nothing in `K/crypto/threshold/` may build `x` or `d`. A test reads the package's sources and
fails on a call that sums `λᵢ·sᵢ` as scalars. It is crude, and it is the rule from
[shared-key-derivation.md](./shared-key-derivation.md) made into a test.

### Phase 3: the wire

`K/nostr/groupdm/`:

- `GroupEcdhEvents`: kind 30400, whose payload maps each admin's host key to a NIP-44
  ciphertext of
  `{"v":1,"generation":g,"from":id,"items":[{"point","purpose","evidence"?,"partial","proof"}]}`;
  and kind 30401, a round's failure naming a participant whose proof failed.
- `GroupDmWrap.open(wrap, convKey)`, `GroupDmSeal.open(seal, convKey)`, and
  `GroupDmSeal.build(rumor, convKey, createdAt)`, unsigned.
- `GroupDmManifest`: D12's JSON, sealed per admin, with a decoder that accepts only its own
  encoding.
- `GroupDmConversation.idOf(rumor)`.
- `Evidence.check(purpose, P, event, groupNpub)`: D5's table, in one function, so the
  answering device and the signer check the same thing.

Tests (`GroupDmWireTest`, pure):

- a wrap and seal built by quartz's stock `GiftWrapEvent.create` and `SealEvent.create`,
  from a person to a dealer-key group, open through `ThresholdEcdh`;
- a seal built here opens in quartz's stock `unsealOrNull` with the recipient's own key.
  This is the claim that a stock app reads the group's messages;
- evidence: a wrap not `p`-tagged to the group, an unsigned seal, the group's own npub as a
  recipient, and an event signed by someone else are each refused.

### Phase 4: storage, schema 44 → 45

Additive: an `AutoMigration(44, 45)` with its comment, the exported
`composeApp/schemas/…/45.json`, and `ShippedProfileUpgradeJvmTest`'s `current` moved to 45.

| table | key | holds |
|---|---|---|
| `GroupEcdhRound` | round id | room, generation, point, purpose, the evidence's JSON, state (`OPEN`, `COMBINED`, `FAILED`), opened at |
| `GroupEcdhPartial` | `(round, participant id)` | `Dᵢ`, the proof, verified or not, received at |
| `GroupConversationKey` | `(room, peer)` | the NIP-44 conversation key, computed at, the round it came from |
| `GroupDmWrap` | wrap id | room, received at, state (`WAITING`, `OPENED`, `UNREADABLE`), the round |
| `GroupDmMessage` | rumor id | room, wrap id or signing session, conversation id, sender, rumor JSON, created at, outgoing or not |
| `GroupDmOutbound` | `(signing session, seal id)` | recipient, relays, the wrap once built, `publishedAt`, the backoff |

`GroupConversationKey` holds secrets and is wiped as shares are. `GroupDmStorageJvmTest`:
redelivery of a partial, wrap or rumor is a no-op, and wiping a room removes its keys and
messages.

### Phase 5: answering and combining (D3, D4, D5, D11)

- `GroupEcdhManager`:
  - `open(room, P, purpose, evidence)`: makes the round and owes this device's partial;
  - `process(message)`: decrypts this device's slot, verifies each item's evidence and
    proof, stores it, owes a partial for any round this device has not answered, and
    combines where it can;
  - `flush(room)`: sends every owed partial in one 30400;
  - `onCombined`: hands the point to whoever opened the round (D9 or D10).
- Inbound dispatch in `NostrDao.fileGroupEventResult`, beside the share refresh's arm.
- The sweep: `SynchronizationViewModel` flushes and combines on each pass, as it resumes
  ceremonies.
- Generations: a refresh that completes marks open rounds of the old generation for restart.
- Blame: a failed proof publishes a 30401 once per round and participant, and is shown in
  the inbox's diagnostics.
- Tests (`GroupEcdhRoundJvmTest`, the ritual harness with three admins at `t = 2` and a
  member):
  - two admins' partials give the same conversation key on all three admin devices;
  - the member's device stores nothing it can read and combines nothing;
  - a removed admin's device gets no slot in partials sent after the removal;
  - a forged partial is named and the round completes with the third admin;
  - a point without evidence is never answered;
  - a refresh mid-round restarts it, and partials of two generations never combine.

### Phase 6: the group's DM relays (D8)

- `GroupDmRelayRefusal` in the four places. `ProposedEvent`'s 10050 arm: "Where the group
  receives messages", with the relays.
- The "Open the group's messages" row on the group's screen, for an admin of a keyed room
  with no 10050. A setup screen shows the relays to edit, explains the decision, and
  proposes. After that, a "Messages" row.
- `ChatMessage.applyInnerEvent`'s 10050 arm: "The group can now receive messages" the first
  time, and "The group changed where it receives messages" after.
- `GroupDmRelayPublisher`, owed and with backoff. `BROADCASTABLE_KINDS` gains 10050.
- Tests: `GroupDmRelaysJvmTest`. A signed 10050 is published unchanged and writes no
  `NostrEvent` row; a 10050 listing a non-`wss` relay is declined with its reason. The
  Phase 1 pin flips.

### Phase 7: receiving (D9)

- `live-group-dm` in `LiveSubscriptionManager`, re-sent as rooms gain or lose a 10050 or
  this device's member gains or loses admin. Catch-up uses the two-day margin.
  `indexNostrEvent` skips a wrap `p`-tagged to a room this device reads for.
- `GroupDmReceiver`: the ledger, the wrap round, the seal round or kept key, and filing.
- `DirectMessagePeerSync` fetches the 10050 and kind 0 of each correspondent, for D10's
  relays, D5's evidence, and names on the screen.
- **Rate.** Anyone can make the group's admins do work by sending wraps. The receiver opens
  at most 60 new wrap rounds per room per hour, two batches' worth at Phase 0's size. Beyond that, wraps wait in the ledger, newest first, and the inbox says some
  are queued. The group's 10050 relays are its first line here, which the setup screen says.
- Tests (`GroupDmReceiveJvmTest`):
  - a message from an outside identity, wrapped by quartz's stock code, opens on all three
    admin devices once two have answered;
  - a second message from the same sender takes one round;
  - the same rumor in two wraps is one message;
  - the member's device files nothing;
  - a promoted admin's device opens a wrap from before their promotion;
  - the rate cap queues rather than drops.
  The Phase 1 wrap pin flips.

### Phase 8: the messages, seen

Routes and screens, each with a back button, `readableContent()`, the four states and
`ScreenStateTransition`:

- `GroupDmListScreen`: conversations, with names, the last line and an unread mark. A
  "being opened" line for wraps waiting on admins, naming who has not answered yet. The
  empty state says nobody has written yet. A queued count when the rate cap is reached.
- `GroupDmConversationScreen`: messages in and out. Outgoing ones show their state: being
  signed, signed, sent, or signed but not sent (with **Send again**).
- The group's screen: the "Messages" row with its unread count, only where this device's
  member is a current admin (D12). Members who are not admins see that the group receives
  messages, since the 10050 is public, and nothing else.
- Strings in sentence case from the catalogue, and nothing says "ECDH" to a person. The
  waiting line reads "Waiting for 1 more admin to open this", not a protocol name.
- Tests (`GroupDmScreenJvmTest`): the rows' presence by role, the empty and waiting states,
  and Send again.

### Phase 9: writing (D10, D12, D13)

- `GroupDmDraft.build(room, recipients, text, replyTo)`: the rumor, the manifest, and a seal
  per recipient from kept keys. A recipient without a kept key opens a round first, and the
  composer shows "Preparing" until it combines.
- The composer, under a conversation and from **New message** (paste or pick an npub; the
  recipient's evidence is fetched, and the composer says when none can be found). It
  proposes through `proposeSigningBatch` with the manifest tags, and says above the button
  that the group's quorum has to sign before anything is sent.
- `GroupDmRefusal` in the four places, with D13's checks, and the waiting-not-declining
  behaviour for a signer still computing a key.
- `ProposedEvent` for a batch of seals: on an admin's device, "A message from the group" with
  the text and recipients; elsewhere, "A private message from the group".
  `FrostSigningScreen` shows the text, what it answers, and the recipients.
- `ChatMessage.applyInnerEvent`: a room-signed kind 13 draws nothing in the transcript. The
  session's own lines already say the group signed, and naming the message would tell
  members who are not admins.
- `GroupDmPublisher`: on completion, on the proposer's device, wrap and send with backoff.
  Every admin's device files the message as outgoing from the manifest.
- Tests (`GroupDmSendJvmTest`):
  - a message proposed by A and signed by B reaches the recipient's 10050 as stock NIP-17,
    and opens with quartz's stock `unwrapAndUnsealOrNull` under the recipient's key;
  - C sees it as outgoing;
  - each D13 refusal on its own: mismatched seals, a seal to the wrong key, an extra
    recipient, forged evidence, a blank message, a seal with tags, and a signer outside D4's
    set. Each is declined with its reason, and the proposer's device shows the decline;
  - a member's device reads the proposal as "A private message from the group" and learns
    nothing more;
  - a recipient written to before needs no round.
  The Phase 1 kind 13 pin flips.

### Phase 10: admin changes (D14)

- The wipe on seeing oneself removed, with its transcript line.
- `RoomAdmins` changes re-send the subscription and re-key D4's slot list at the next flush.
- Tests (`GroupDmAdminChangeJvmTest`):
  - after C's removal lands, a new message opens on A and B, and C's device gets no slot and
    opens nothing;
  - C's kept keys are wiped on C's device;
  - after a promotion, the new admin's device answers rounds and reads the history;
  - a share rotation after the removal leaves every kept key valid on A and B (D1).

### Phase 11: the record, and devices

- Mark this plan built, with a *what the plan said / what it turned out to be* table, and
  update the README row.
- A *since* note on [confederation.md](./confederation.md) D5: a group can now be written to
  at its npub, through its admins' shares, where it has opened its messages. D5's seal stays
  as it is.
- Device run, three desktop clients or emulators in one keyed group at `t = 2`, a fourth
  member who is not an admin, and an outside identity on a stock NIP-17 client (one that
  knows nothing of Giza):
  1. A opens the group's messages; A and B sign the 10050; a public client shows it.
  2. The outside identity writes to the group's npub. With two of the three admin apps open,
     the message appears on all three; D sees no messages row.
  3. B replies; A signs; the outside client shows the reply from the group's npub, and can
     answer it.
  4. Close all but one admin app: a new message shows "Waiting for 1 more admin". Open a
     second: it opens.
  5. Remove C as admin: the next message does not open on C's device; C's kept keys are gone.
  6. Promote D: D's device opens the conversation's history.
  7. The group writes first to a new npub; the outside client receives it.

---

## Risks

| risk | what to do |
|---|---|
| D2's signs are wrong, and every key is wrong | Phase 0, check 3, against quartz's own key derivation; Phase 2 covers every parity case |
| D3's proof is hand-written cryptography | about forty lines over libsecp256k1 calls, with no secret arithmetic outside it; tested for every way a proof can be wrong; reviewed before Phase 5 builds on it |
| reading waits for `t` admin apps to be open | the cost of never putting the group's key in one place. The screen names who it waits on. Background sync is a separate plan for the whole app ([long-running-sync.md](./long-running-sync.md)) |
| anyone can make the admins' devices work, and fill the room with partials, by sending wraps | D11's batching, Phase 7's rate cap, and the group's choice of relays in its 10050 |
| the static Diffie-Hellman oracle | D5: only keys whose owner signed something, so no `x^k·G` can be asked for, and never the group's own npub |
| a kept conversation key leaks with a stolen phone | it opens no new wrap without `t` holders (D6), and the phone is cut off once its owner is removed (D4). Not keeping keys would cost a round per message from known senders; keeping them is recommended |
| members who are not admins see how many seals a message has, so how many recipients | stated in D12; padding with decoy seals is possible and not planned |
| a promoted admin reads the past (D14) | a decision, made explicitly in D14 and reversible by policy |
| a recipient's relays drop gift wraps from unknown keys | the wrap is stock NIP-59, so it fares like any NIP-17 message; Send again exists |
| a Bennu admin answers nothing | the group still reads with `t` Giza admins; the waiting line names who has not answered |
| NIP-17's sender copy is skipped (D10) | the group's sent messages live in its signing sessions, which reach every admin; a non-Giza client holding the group's key does not exist, so no other device misses the copy |

---

## Out of scope

- **NIP-4E.** The sibling plan. *Against the NIP-4E plan* says how the two would meet.
- **Narrowing a promoted admin's view to after their promotion** (D14).
- **Kind 15 (files), reactions and edits.** Filed, and shown as a message this screen
  cannot show yet.
- **Notifications** for the group's new messages.
- **Answering rounds in the background.** The app's sync runs in the foreground; this plan
  inherits that.
- **Decoy seals** to hide the number of recipients from members who are not admins.
- **Threshold ECDH for anything else.** The confederation's seal, NIP-44 content for other
  kinds, NIP-46. Each would widen D5's table, and each needs its own argument.
- **The personal path's ignored 10050** (`NostrNip17Dao.kt:186-218`). Noticed, not fixed
  here.

---

## Order and size

| phase | rough size | depends on |
|---|---|---|
| 0 checks | scratch tests, this document | — |
| 1 pin | ~150 test | 0 |
| 2 arithmetic | ~350 main, ~500 test | 0 |
| 3 wire | ~500 main, ~450 test | 2 |
| 4 storage | ~400 main, ~200 test | 3 |
| 5 rounds | ~900 main, ~700 test | 4 |
| 6 relays | ~600 main, ~300 test | 1 |
| 7 receiving | ~700 main, ~600 test | 5 and 6 |
| 8 screens | ~1,200 main, ~300 test | 7 |
| 9 writing | ~1,100 main, ~800 test | 5, 7 and 8 |
| 10 admin changes | ~250 main, ~400 test | 9 |
| 11 record | docs and a device session | all |

Phase 6 touches none of Phases 2 to 5's files and can be built beside them. Everything else
is in order: the arithmetic before anything rests on it, rounds before either direction,
and reading before writing, because writing reuses the rounds and keys reading leaves
behind.

---

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew check
```

`wallet-audit.sh` greps for retired kinds and sats. None of 13, 14, 1059, 10050, 30400 or
30401 is retired. `brand-audit.sh` fails on a Bennu name outside a comment, which the
waiting line for a Bennu admin must not use: it names the admin, not their app.
