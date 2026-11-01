# Member wallets: a guarded wallet a group keeps for one person

Today a guarded wallet belongs to a group. A subgroup's quorum holds the key
path, and every ancestor holds a leaf that can take any coin 12 blocks after it
confirmed. This plan gives the same shape to **one member**. The key path is a
key only that member holds, and the leaves are the group's and the group's own
guardians', exactly as they would be for a guarded subgroup of that group. The
member spends alone, at any time, with one Schnorr signature. The group can step
in, and so can every group above it.

**The member proposes it.** Their device generates a random private key, works
out every ancestor leaf, builds the descriptors, stores the key, and only then
puts a request into the group's room. The group's quorum checks all of it and
signs a **member wallet certificate**: the group's signature saying it guards
this key for this person. That certificate is what the member's device opens the
wallet on, what every guardian watches, and what is forwarded upward so the
groups above can find the coins.

Read [subgroups.md](./subgroups.md),
[group-wallet-guardians.md](./group-wallet-guardians.md) and
[subgroup-wallet-policy.md](./subgroup-wallet-policy.md) first. This plan reuses
the guardian derivation from the second without changing it. It reuses the
grant, the leaf, the tree, the signing cache, the forwarding notice and the
guardian spend. What is new is who holds the key path, and the artefact the group
signs to agree to it.

**Built**, phases 1-12, one commit each (Phase 9 in two), written against the
code as it stood at `8cb6f04e`, schema v29; the build is schema v30.
`MemberWalletDerivation`, `MemberWalletRequestEvent`, `MemberWalletCertificateEvent`,
`MemberWalletManager` and `GuardianManager.GuardedSubject` are the entry points,
and `GroupWalletRollout.MEMBER_WALLETS_ON_MAINNET` is the gate. The phases
below are kept as written; where the build chose differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 2: the answer carries the signed `GroupKeyStateEvent`, whole | it carries the threshold key, its path and the group's certificate. A `GroupKeyState` row keeps what the state said and not the event, and nothing in the event is needed beyond what checks itself: the key at the path rederives the room, and the certificate certifies it and names that key |
| Phase 2: the first share-holder by npub answers at once, the rest after a minute | every device holding the verified state answers a request no answer names yet, as the chronicle's request is answered. Duplicates cost bandwidth and nothing else |
| Phase 3: the request is an inner event signed by the member | an inner event is an unsigned rumor, and the row it travels in keeps no `sig`. The signed request travels whole as the content of an inner event of the same kind -- `MemberWalletRequestEvent.carried` -- the way a 30333 notice carries a statement, and only from the member who signed it |
| Phase 5: `MemberWalletSpend` in a later bump | the same bump, v30, beside `MemberWallet` |
| Phase 6: the screens talk to `ChatRepository` | a `MemberWalletRepository` of their own, which holds the active identity: nothing above it touches a key |
| Phase 6: the gate arrives in Phase 12 | with Phase 6, where an entrance is first offered, as the guardians note's gate came with its Phase 5 |
| Phase 7: requests join `observeProposalsAwaitingYou` | *Put to the group* is on the request's row in the group page's *Members' guarded wallets* (Phase 11). The transcript says a request arrived; the proposal list still lists only sessions |
| Phase 7: the summary names the member | `ProposedEvent.summarize` takes a `nameOf`, and the callers that have no profiles to hand pass none, so the proposal list and signing screen say *Guard a member's wallet* without the name. The certificate's `p` tag is the member either way |
| Phase 8: the wipe callers warn first | `wipeDatabase` has no callers and `DedunDatabase.wipeData` deletes nothing. There is nothing to guard yet; a note on `wipeData` says what a caller must do once it does |
| Phase 9: `GroupTransactionWatcher` generalised | a member's spend has no session, so `MemberWalletViewModel` follows the member's spends while the wallet screen is open, sending again any Electrum has not heard of -- the economy the guardian-spend notice makes. *Bump the fee* is not built for a member's spend |
| Phase 10: `descendantChatRoomId` becomes `subject.id`, `statement` becomes `signedEvent` | both keep their names -- `descendantChatRoomId` is `subject.id`, and `statement` is the 30334 for a member -- so the guardian spend path did not change a line. `Spender.Guardian.name` is `@name` for a member |
| Phase 11: the person's page shows the amount within each group's reach | it does not watch the chain, so it shows the guarding group and *Spend*; the group's wallet screen has the amount. On one's own profile the section is not drawn: the profile's own list is the answer there |
| Phase 12: the three-emulator run lifts the gate | the gate was lifted by decision before the run; see below |
| D8: the member's own spend is capped until the gate lifts | capped on mainnet by `MAINNET_CAP` regardless, since the gate lifted without the run that was to justify lifting both |

**The mainnet gate is open.** It was to lift on Phase 12's run -- a three-level
hierarchy on signet watching one member's wallet, the root making a guardian
spend from it -- and was lifted by decision before that run, as the guardians
note's gate was. The run is still worth making. Until it has been, a member's
own spend on mainnet is held to `GroupWalletRollout.MAINNET_CAP`, as every
group spend and every guardian spend is; `memberCapFor` is where that lifts.
The mixed-version risk below is unchanged: a group whose signers are not all on
a build from Phase 7 onward should not be asked to guard a member's wallet.

Still to do by hand: Phase 12's run on signet.

---

## Should it be a birth certificate?

The question this plan has to settle first. The answer is in two parts.

**It needs a group-signed artefact, yes.** An ancestor believes a guarded wallet
only on signatures it can check itself. For a subgroup those are the parent's
signature on the certificate and the child's on the statement. A member wallet
needs the group's signature for the same reason: without it, the groups above
have only the forwarder's word that the group agreed to guard this key.

**It must not be a 30329.** A `SubgroupBirthCertificateEvent` says *this room is
my child*, and every reader of that kind assumes there is a room:

| reader | what it would do with a member's wallet in a 30329 |
|---|---|
| `SubgroupBirthCertificateEvent.certifies`, check 4 | requires `marmotGroupId(subgroup_key, frost_path) == content`. A wallet key is not a room, so the certificate either never verifies, or its content is set to the id that key *would* make, and then it is a room to everything below |
| `SubgroupManager.subgroupsOf`, `DatabaseDkgRepository`'s observer | lists every 30329 as a subgroup. The member's wallet would show in the parent's *Subgroups* section as *certified, not yet created*, forever, on every build, old ones included |
| `ChatMessage.applyInnerEvent`'s 30329 arm | writes a "subgroup was born" line in the transcript |
| `ProposedEvent.summaryOf`'s 30329 arm | asks the signers to approve "a subgroup, for Alice" |
| `SubgroupManager.refuseCertificate` | applies D4 of the policy note: a guarded group may only make guarded *subgroups*. That rule is about rooms, and its `name` and `p` tags are a founding roster that a wallet does not have |
| `GroupWalletStatementEvent.Reading.guardiansCheckOut` | expects the certificate to certify the room whose key signed the statement |
| `DkgRitualViewModel`'s `firstCarrying(30329)` | resumes a subgroup ceremony from the certificate it finds |

So the member wallet certificate is **its own kind, 30334**. It shares the
guardian tags, the grant function and the forwarding notice with the subgroup
path, and none of the lineage meaning. Nothing that reads 30329 ever sees it.

**How it shows on the group's pages**, as the question guessed:

- On `ChatRoomDetailScreen` it is **not** a subgroup row. It gets a section of its
  own, *Members' guarded wallets*, under *Subgroups*. A row there is a person, and
  tapping it opens that person's profile (`ProfilePreviewRoute`), the same place
  a tap on a member row already goes. It does not open a chat room, because there
  is none.
- In the *Guarded wallets* list on an ancestor's wallet screen, a subgroup's
  wallet stays `#groupName`. A member's wallet is `@username`.
- On the member's own profile, each wallet is named for the group that guards
  it: *Guarded by #Ekklesia*.

---

## The shape

```
k                  random 32 bytes, generated on the member's device   never leaves it
P_W  = k·G         the wallet key
W    = taggedHash("bennu/member-wallet/id/v1", serP(P_W))              the wallet's id: 32 bytes, nobody's key

account  = GroupWalletDerivation.account(P_W, chain, 0, grant)         unchanged, P_W in place of a threshold key
grant    = GuardianDerivation.grant(K_G, G, guardians(G), W)           unchanged, W in place of a child room id
```

An address of the member's wallet, beside a guarded subgroup's:

```
                 guarded subgroup C of G          member wallet W under G
key path         account(K_C)/change/index         account(P_W)/change/index
leaf 1           R_G / i(C) / n/a/change/index     R_G / i(W) / n/a/change/index
leaf 2…          g / i(C) / …  for g in guardians(G), nearest first -- the same list, one step further for W
```

So a member's wallet under `G` is guarded **exactly as a guarded subgroup of `G`
would be**: the same ancestors, the same 12-block lock, the same caterpillar, and
the same descriptor shape, which Bitcoin Core imports. Every ancestor signs for
its leaf with the `GuardianDerivation.signingCache` it already uses, walking the
line of descent down to `W` instead of down to a room.

### Why `W` is a hash and not the key

A room id here *is* a public key, and `GroupKeyStateEvent.isSignedByRoom` asks
whether that key signed something. If `W` were `xonly(P_W)`, the member could
sign events that read as *signed by room W*. A `W` that nobody holds a key for
cannot be mistaken for a room by anything that checks signatures. The tag keeps it
apart from every other hash in the app. It is 32 bytes, so `childIndex` takes it
as it stands.

### Why a random key, and only one wallet per key

The member asked for this, and the derivation gives a second reason. Every step
below `P_W` is an unhardened public tweak, and every guardian holds the account
xpub, with its chain code. So a guardian who learns the private key of **one**
address can recover `k`. That is ordinary BIP32, and it is the same rule the
wallet note states for a group's threshold key. It means `k` has to be a key that
is good for nothing else:

- **not the nostr key.** A leak would hand over the member's identity. A signer
  behind NIP-55 cannot apply taproot tweaks in any case. And a wallet key equal to
  an npub would tie the chain to the relay graph;
- **not a key from the profile's seed.** An nsec or npub profile has no seed
  ([nsec-sign-in.md](./nsec-sign-in.md)). This is the first wallet such a profile
  could hold.

One key gives one wallet, on one network, at account 0. A second wallet is a
second key.

### The member's own spend

Key path, one signature, nothing to coordinate:

```
d   = k + I[0:32]                       the account step, as a private key
d'  = CKDpriv(CKDpriv(d, change), index)   unhardened, matching CKDpub on the other side
sig = signSchnorr(sighash, d', TaprootTweak.ScriptTweak(merkleRoot(tree)))
```

`k` itself signs exactly one thing: the proof of possession in the request,
under its own tagged hash (Phase 3). It is never an output key, because the
account step always tweaks it first.

---

## Who agrees to what

Three parties, and each one signs what it is agreeing to:

1. **The member agrees to be guarded.** The request is an inner event signed with
   their nostr key, so it is bound to their identity. It is also signed with `k`,
   which proves they hold the wallet key. It names the group, the wallet key, the
   guardians and the descriptors their own device worked out. Consent comes first
   here, which is the reverse of a subgroup, because the member already exists and
   holds their key before anyone grants anything.
2. **The group's quorum agrees to guard it.** Each signer's device recomputes
   the grant from the group's own key and certificate, checks the request against
   it byte for byte, and checks that the person asking is a member. The
   certificate embeds the request whole, so anyone who reads the certificate can
   see that the member asked.
3. **The ancestors are not asked**, in the same way they are not asked when a
   guarded subgroup is born. Each inherited key is the group's own grant passed one
   step further. The group's quorum already holds the power to pass it to a
   subgroup, and nothing here gives the ancestors any power they did not already
   have.

### What "full control" means, and what the member gives up

The member can spend every coin, at any time, alone. No script stops a key-path
spend. The group, and every group above it, can also take any coin that has sat
confirmed long enough, without asking and without warning. How long is each
guardian's own delay: the group's own leaf at the delay its member-wallet
policy sets, and every group above at the delay it chose for the group below it
-- see [member-wallet-delay.md](./member-wallet-delay.md) and
[guardian-delay.md](./guardian-delay.md). It was 12 blocks for every leaf when
this note was written. That is the
guardians note's *the child gives up a real thing*, said about one person. The
consent screen says it in these words before a key is made (Phase 6).

The groups above see the wallet too. The certificate carries the member's npub
and the account xpub, so every member of every guarding group can see its whole
balance and know whose it is. That also goes on the consent screen.

---

## The order, and why it is forced

```
  1. the member's device   reads K_G and guardians(G); generates k; derives W, the grant, the account
                           and both descriptors; stores k                          nothing has left the device
  2. the member            publishes the request into G's room                     the proposal
  3. any signer of G       opens a FROST session over the certificate the request determines
  4. G's quorum            signs it; every device in G files the certificate      the wallet exists
  5. a member in G and P   forwards it into P as a 30333 notice, and so on up      the ancestors find it
```

Step 2 comes after step 1 because the member asked for it to. It also has to: the
request carries the leaves, and the leaves are a function of `W`, so of `k`. Step
3 needs a signer, because a FROST session can only be opened by a device holding a
share (`proposeSigningBatch` throws without a `signerIdOf`). Step 4 is when
addresses are first offered. A wallet that the guardians have not agreed to guard
should not be handed out to be paid.

---

## Phase 0 — decisions, before any code

### D1. A new kind, not a birth certificate

**Decided by the table above.** The certificate is 30334 and the request is
30335, the next free numbers after the notice at 30333. Phase 2's key notice
takes 30336.

### D2. Who puts the request to the group?

**Recommended: any signer, by tapping *Put to the group*.** The member's request
appears on every share-holder's device beside the proposals already waiting for
them. The first to tap it opens the FROST session, and proposing counts as their
own approval, as it does for every session. If the member is a signer, their
device opens the session directly and needs no sponsor.

The certificate is a pure function of the request, and its `created_at` is the
request's. So two signers who tap at the same moment open two sessions over **one
event id**, and `GroupSignedEvent` keeps one row. A sponsor cannot change
anything, because every other signer rebuilds the certificate from the request
and drops a proposal that differs by a byte.

The alternative is to let the member coordinate the FROST session without
holding a share. It is a better fit to the words "the individual proposes", and
FROST allows a coordinator who is not a signer. But `FrostSigningManager.advance`
returns at its first line without a `secretShare`, `proposeSigningBatch`
requires a `signerIdOf`, and the session row's `signApprovedAt` assumes the
proposer signs. Changing all three means touching the one manager every signature
in the app goes through, to save one tap. That is rejected for now, and written
up under the appendix.

### D3. Which members may ask?

**Recommended: any member of the group whose profile can sign and can watch the
chain.** Not only admins. The group has to be a Marmot room with a threshold key,
and, since [member-wallet-delay.md](./member-wallet-delay.md), one that has
opened to its members' wallets: a group that has signed no member-wallet policy,
or whose newest one is closed, takes no request.
A read-only profile cannot sign the request. A profile with no Electrum client
(`GroupOnchainWallets` is null for it) could not see its own coins, so it is not
offered a wallet it cannot see. **Since
[standalone-electrum.md](./standalone-electrum.md)** there is no such profile:
an nsec profile watches on a client of its own, the `CANNOT_WATCH` refusal is
gone, and an nsec profile may ask.

### D4. What the member's device has to know, and the member who joined late

The grant needs `K_G` and the guardian keys in `G`'s own certificate. Today both
are read off `G`'s verified key state, and only ceremony participants hold one. A
member welcomed after the founding has neither. That is
[subgroups.md](./subgroups.md#what-this-does-not-do)'s gap, and it would leave
most ordinary members unable to ask.

**Recommended: Phase 2, a key notice.** A member's device that holds no key state
for `G` posts a request for one. Any device that does hold one answers with the
signed `GroupKeyStateEvent`, whole, in a 30336. The asking device does **not**
file it as key state: the chronicle note's reason for keeping key states out of
the chronicle still holds. It reads `K_G` and the certificate out of the notice on
every use, after `GroupKeyStateManager.stateFrom`'s checks. This is safe for one
reason: `G` is `marmotGroupId(K_G)`, so no validly signed state, however old, can
name a different key.

An answer is not needed when `G` has a guarded wallet: its statement carries
`G`'s certificate, and so `K_G` too. That is a fallback, not the route, because a
root group has neither.

If D4 is deferred, Phases 1 and 3 to 12 still stand. The entrance is then hidden
for a member with no key state, and the screen says why.

### D5. How many wallets, and what are they called?

**Recommended: as many as the member likes, one key each, one network each,
account 0, with an optional label** (*Savings*). The label goes in the request,
and so into the certificate. The person's name does **not**. Rows show `@` plus
the member's live profile name (`Profile.humanReadableNameOrPubkey()`). The
certificate fixes the npub. A person renaming themselves is not drift in a
historical record, because there is no founding name to drift from.

### D6. Where the key lives, and how it is backed up

**Recommended: in the database, scoped to the profile, as `DkgSession.secretShare`
already is**: a `MemberWallet` row keyed by `W`, with the owner's npub. It is
**never** a foreign key to `ChatRoom`. Deleting the group's room on this device
must not delete a private key that coins may sit behind. That is the same
argument `parentChatRoomId` makes against a cascade, with money at the end of it.

Backups:

- the member wallet screen offers **Export key**, behind a confirmation, as a
  `tr(xprv…/0/*,{…})` descriptor any wallet that imports taproot descriptors can
  sweep from;
- **the group is the recovery path.** A member who loses the device loses `k`,
  and their guardians can still take every coin after 12 blocks and pay it to the
  member's new wallet. The screen says so, because it is the honest upside of being
  guarded;
- `wipeDatabase()` ("end this", and signing out) has to warn when a member wallet
  holds coins and its key is on this device only (Phase 8).

### D7. Which guardians, and can the member leave some out?

**Decided: exactly `SubgroupManager.grantFor(G, W)`**, the list a guarded subgroup
of `G` would get. A member cannot drop an ancestor or choose their own: signers
compare the whole list. Under a root group, or one whose certificate is
*unguarded*, that is `G` alone, by D2 of the policy note. Under a *guarded* `G` it
is `G` and every group above it. A member who wants no guardians has a plain
wallet already. That is the profile's own wallet, and it is outside this plan.

### D8. Rollout

**Recommended: `GroupWalletRollout.MEMBER_WALLETS_ON_MAINNET = false`**, lifted
after a signet run (Phase 12) in which a three-level hierarchy watches one
member's wallet and the root makes a guardian spend from it. The member's own
spends go through a single-signer path the app has never had. So until the gate
lifts, a member's own spend from this device on mainnet is held to `MAINNET_CAP`.
Guardian spends from a member wallet are already held to it.

---

## Phase 1 — the derivation and the member's signature, pure

New `managers/MemberWalletDerivation.kt`, beside `GuardianDerivation`:

```kotlin
object MemberWalletDerivation {
    /** `taggedHash("bennu/member-wallet/id/v1", serP(walletKey))`, hex. Frozen once a certificate carries one. */
    fun walletId(walletKey: PublicKey): String

    /** The account the member's key opens under [guardians]: `GroupWalletDerivation.account` over [walletKey]. */
    fun account(walletKey: PublicKey, chain: Chain, guardians: List<GuardianDerivation.Guardian>): GroupWalletDerivation.Account

    /** The private key of the address at `change/index`: the account step, then CKDpriv twice. */
    fun privateKeyAt(walletPrivateKey: PrivateKey, chain: Chain, change: Long, index: Long): PrivateKey

    /** The signature an input of a checked spend needs: [privateKeyAt] under the address's script-path tweak. */
    fun sign(walletPrivateKey: PrivateKey, input: GroupSpend.SpentInput, account: GroupWalletDerivation.Account): ByteVector64
}
```

`GroupWalletDerivation.account` already takes any public key. The only change
there is its parameter's name and kdoc. It says *threshold key*, and from now on
it is "the key the key path belongs to".

**Tests**, commonTest:

- `privateKeyAt(k)`'s public key is `derive(account, change, index).internalKey`
  at ten indices on both branches;
- a signature from `sign` verifies against the address's output key, and
  `correctlySpends` accepts a key-path spend of a guarded address built by
  `GroupSpendBuilder`;
- `GuardianDerivation.grant(K_G, G, inherited, W)` reaches the keys a guardian's
  `signingCache` reaches for `lineage = [… , W]`, so an ancestor can sign every
  leaf of a member wallet;
- `walletId` vectors, and a check that `W` is not `xonly(P_W)`;
- the descriptor passes bitcoin-kmp's checksum and matches
  `GroupWalletDerivation.Account.receiveDescriptor`.

Nothing calls it yet.

---

## Phase 2 — every member can know the group's key (D4)

New `nostr/wallet/GroupKeyNoticeEvent.kt`, kind **30336**, in two shapes:

```
asking:     kind 30336, content "",                    ["h", G]
answering:  kind 30336, content <signed 30326, whole>, ["h", G], ["e", <the asking notice's id>]
```

- `MemberWalletManager.groupKeyFor(database, G)` returns `K_G` and the
  certificate, read off this device's verified key state, then off `G`'s guarded
  wallet statement, then off the newest answer in `G`'s room that passes
  `stateFrom`. It is never written into `GroupKeyState`.
- **Answering.** A device that holds a verified state answers an asking notice
  that has no answer yet. To keep a room of signers from all answering at once,
  only the share-holder whose npub sorts first among those the device knows about
  answers straight away. The rest answer if nothing has arrived after a minute.
  Duplicates are harmless.
- `ChatMessage.applyInnerEvent` gets a 30336 arm that writes **no** line. It is
  plumbing, and without the arm an older build would show a raw-JSON bubble, which
  is reason enough to ship it early.

**Tests**: an answer carrying a state for another room is ignored; an answer
whose certificate does not read is ignored; an old state for the same room still
gives the right `K_G`; nothing is filed into `GroupKeyState`.

---

## Phase 3 — the request, pure

New `nostr/wallet/MemberWalletRequestEvent.kt`, kind **30335**, an inner event
in `G`'s MLS room and signed by the member's nostr key. It never goes to a relay,
for the reason the statement never does: it carries an xpub.

```
kind:    30335
pubkey:  <the member's npub>
content: <the account xpub>
tags:
  ["h",           G]                                    the group asked; a request is not replayable into another room
  ["wallet",      W]
  ["wallet_key",  <P_W, 33-byte hex>]
  ["chain",       "mainnet" | "signet" | …]             GroupWalletStatementEvent.chainName
  ["guardian",    <room id>, <key>, <chain code>, "12"] one per guardian, nearest first -- GuardianTag reused
  ["descriptor",  tr(…,{…}), "receive"]
  ["descriptor",  tr(…,{…}), "change"]
  ["label",       "Savings"]                            optional
  ["member_wallet_policy", <the group's open 30337>]    since member-wallet-delay.md: what the request was built under
  ["possession",  <BIP340 signature by k>]
```

The possession signature is over
`taggedHash("bennu/member-wallet/possession/v1", xonly(npub) ‖ G ‖ serP(P_W) ‖ chainHash)`.
That message includes everything that has to bind the key to one person in one
group on one network. Without it, Bob could ask for a certificate on Alice's
wallet key under his own name, and every list above would show her coins as
`@bob`'s.

`read(event): Reading?` and `Reading.verified(): MemberWalletDerivation.Account?`
check:

1. the nostr signature, and that the event is 30335;
2. `W == walletId(P_W)`;
3. the possession signature under `P_W`;
4. the first guardian is `G` itself, so a request cannot leave out the group it
   asks;
5. `account(P_W, chain, guardians)` rederives the xpub and both descriptors byte
   for byte.

This is everything a reader can check without `G`'s key. The grant is Phase 7's,
because only a device that holds `K_G` can check it.

**Tests**: a request round-trips; each of the five checks fails on its own
mutation; a request with no label reads; two `wallet` tags do not read.

---

## Phase 4 — the certificate, pure

New `nostr/wallet/MemberWalletCertificateEvent.kt`, kind **30334**, signed by
`G`'s room through FROST:

```
kind:       30334
pubkey:     G                                 set by the signing session
created_at: the request's created_at          so every sponsor proposes one event id
content:    W
tags:
  ["d",          W]                           addressable: newest per wallet wins, all of them identical
  ["p",          <the member's npub>]
  ["wallet_key", <P_W>]
  ["chain",      …]
  ["guardian",   …]                           as in the request
  ["descriptor", …, "receive"]
  ["descriptor", …, "change"]
  ["label",      …]                           if the request had one
  ["request",    <the signed 30335, whole>]
```

- `template(request: Event): EventTemplate`. The **only** way a certificate is
  built. A sponsor passes the request and nothing else.
- `verify(certificate, groupChatRoomId): Verified?`, standalone and with no
  database:
  1. 30334, and `isSignedByRoom(certificate, G)`;
  2. the embedded request passes Phase 3's `verified()`, and its `h` is `G`;
  3. the certificate is exactly `template(request)` with `G` as author. One
     comparison covers every tag, so no tag can say one thing in the request and
     another in the certificate;
  4. the `p` tag is the request's author.

  `Verified` carries the npub, `W`, the label and the account. It is what the
  member's device, every guardian and every forwarded notice open the wallet on.

The request is embedded rather than looked up, as the certificate is embedded in a
guarded statement. That makes the certificate self-contained for an ancestor who
was never in `G`'s room, and it means *the member asked* is checked from the
group's signature rather than taken on trust.

**Tests**: a certificate verifies; one signed by another room, one whose request
is from another group, one whose `p` is not the request's author, and one with a
guardian swapped relative to its request each fail.
`SubgroupBirthCertificateEvent.certifies` is false for a 30334, and
`SubgroupManager.subgroupsOf` ignores one stored in the parent. That last test
exists to fail the day somebody reads both kinds in one query.

---

## Phase 5 — schema 29 → 30

One new table. Room auto-migrates a new table, so the entry joins the list in
`DedunDatabase`.

```kotlin
@Entity(indices = [Index("ownerPublicKey"), Index("guardingChatRoomId")])
data class MemberWallet(
    @PrimaryKey val walletId: String,          // W
    val ownerPublicKey: HexKey,                // the profile it belongs to: one database holds every profile
    val guardingChatRoomId: String,            // G -- a plain column, never a foreign key (D6)
    val chain: String,
    val walletPrivateKey: HexKey,              // k
    val label: String?,
    val requestJson: String,                   // the signed 30335, kept so a declined request can be asked again
    val createdAt: Instant,
)
```

No *certified* column. A wallet is certified when `G`'s `GroupSignedEvent` rows
hold a 30334 for `W` that passes `verify`. That is read again each time, as
`GroupWallet` reads statements. A column would be a second copy that could
disagree with the signature.

`MemberWalletDao`: `getByOwner`, `observeByOwner`, `getById`, `upsert`, and a
`delete` the manager calls only for a wallet that was never certified (Phase 8).

Spends get a second table, `MemberWalletSpend`, in Phase 9, where they are first
needed.

---

## Phase 6 — the member asks

New `managers/MemberWalletManager.kt`:

```kotlin
object MemberWalletManager {
    /** Steps 1 and 2: makes the key, works out the leaves, stores the key, and only then publishes the request. */
    suspend fun request(database: DedunDatabase, groupRoom: LocalChatRoom, identity: Identity, chain: Chain, label: String?): MemberWallet

    /** Every wallet [ownerPublicKey] holds a key for here, or that a group this device is in has certified for them. */
    suspend fun walletsOf(database: DedunDatabase, ownerPublicKey: HexKey): List<MemberWalletRow>
}
```

`request` checks the request it built with Phase 3's `verified()`, and checks the
grant against `groupKeyFor`, **before** it publishes. A request this device would
refuse should never be shown to a quorum. It stores the row before it publishes,
so a crash between the two leaves a key and no request rather than a request and
no key.

### The entrances

- **The member's own profile** (`ActiveProfileScreen`): a *Guarded wallets*
  section (Phase 8) with *New guarded wallet*, which opens a picker of the groups
  this profile is in that have a key.
- **The group's detail screen**: *Ask this group to guard a wallet for you*, in
  the new *Members' guarded wallets* section (Phase 11). It opens the same screen
  with the group already chosen.

Both lead to `NewMemberWalletScreen` (route `NewMemberWalletRoute(activeUserPublicKey, chatRoomId?)`,
with a `NavigateBackButton`). It is a consent screen first and a form second:

| line | says |
|---|---|
| headline | *A wallet only you can spend from, guarded by #Ekklesia* |
| who can step in | *#Ekklesia and the N groups above it can take any coin that has been confirmed for 12 blocks, without asking you* |
| who can see it | *Members of those groups will see this wallet's balance, and that it is yours* |
| recovery | *If you lose this device, those groups can still move your coins for you* |
| label | an optional text field |
| action | *Ask #Ekklesia* |

`N` is `grant.size - 1`. Strings go in `strings.xml`, in sentence case, with
`%1$s` and `%2$d`. CLAUDE.md applies in full.

### Refusals, at the screen and again in `request`

| refused | because |
|---|---|
| a read-only profile | it cannot sign the request (`LocalCanSign`) |
| ~~no Electrum client for the profile~~ | gone: every profile has one since [standalone-electrum.md](./standalone-electrum.md) (D3) |
| the group has no key, or is not a Marmot room | there is nobody to sign the certificate, and no room to carry it |
| `groupKeyFor` is null | the grant cannot be worked out. Offer *Ask the group for its key* (Phase 2) |
| `grantFor` is null | `G`'s certificate carries guardian keys this build cannot read. Today that surfaces at the end of a ceremony, and here it is refused up front |
| `MEMBER_WALLETS_ON_MAINNET` is shut and the chain is mainnet | D8 |

**Tests**, jvmTest: `request` stores before it publishes; the published request
passes `verified()` and its guardians equal `grantFor`; each refusal above holds;
a member with no key state succeeds once an answer from Phase 2 is in the room.

---

## Phase 7 — the group signs, and every signer checks

### Every signer refuses a certificate the group did not agree to

`FrostSigningManager.acceptProposal` gets a third check beside the certificate
and statement checks it already has (`:569`, `:591`). It is
`MemberWalletManager.refuseCertificate(database, chatRoomId, thresholdPublicKey, rebuilt)`,
for every rebuilt 30334, and it runs before anything is shown:

- `MemberWalletCertificateEvent.verify` fails → *is not a certificate this group
  could have been asked for*;
- the request's `h` is not this room → *was asked of another group*;
- the request's author is not a participant of this room (a `Participant` row
  with `deletedAt == null`) → *was asked by somebody who is not a member*;
- the guardians are not `SubgroupManager.grantFor(chatRoomId, thresholdPublicKey, W)`
  → *grants guardian keys this group does not*. This is `refuseCertificate`'s key
  check with `W` in place of the subgroup's room id, and it reuses the same
  function.

The group's policy, read off its own certificate, decides only *which* keys the
grant carries (D7). It never refuses a member wallet. Even an unguarded group
can guard its members.

### Sponsoring

- `MemberWalletManager.putToGroup(database, groupRoom, userPublicKey, request)`
  is `FrostSigningManager.proposeSigning(kind = 30334, template(request))`. It
  returns the open session instead when one already carries that event id.
- `ChatMessageListViewModel.observeProposalsAwaitingYou` also lists requests that
  have no certificate and no open session, on share-holders' devices. Each gets a
  *Put to the group* action, which counts as the sponsor's approval, and the
  dialog says so.

### What the group reads

- `ProposedEvent.summaryOf` gets a 30334 arm: *Guard a wallet for @alice
  (Savings). This group and the N above it will be able to take its coins once
  they are 12 blocks old. Only @alice can spend from it before then.*
- `ChatMessage.applyInnerEvent` arms:
  - 30335 writes *@alice asked this group to guard a wallet for her*, and nothing
    else happens. It is a request, not a decision;
  - 30334, when it comes through `applySignedEvent`, writes *This group now
    guards a wallet for @alice*.

  Without these arms, both kinds fall to `else ->` and show raw JSON. That is the
  failure mode [member-chronicle.md](./member-chronicle.md) names, and it is
  avoidable here.

**Tests**, jvmTest, in the shape of `SubgroupManagerJvmTest`: a certificate whose
guardians omit an ancestor, swap one or reorder them is refused; a request from a
removed member is refused; a request replayed from another group is refused; two
sponsors produce one `GroupSignedEvent`; an unguarded group's certificate carries
only its own key and is accepted.

---

## Phase 8 — the member's wallet

### Opening

`MemberWalletManager.walletsOf` joins two sources:

1. `MemberWallet` rows for this profile, the wallets whose key is on this device;
2. 30334s in the signed events of every room this device is in whose `p` is this
   profile's npub, which includes wallets this profile made on **another device**.

Each source gives a `MemberWalletRow`:

| state | when | the profile row's supporting line |
|---|---|---|
| `Asked` | a key and request here, no session, no certificate | *Waiting for someone in #Ekklesia to put it to the group* |
| `Signing` | an open session over the certificate | *#Ekklesia is signing* |
| `NotSigned` | the session failed or was declined, and there is no certificate | *#Ekklesia did not sign. Ask again, or discard* |
| `Open` | a certificate, and the key is here | the balance |
| `WatchOnly` | a certificate, and the key is not here | the balance, *key on another device* |

*Ask again* republishes the stored request, unchanged. *Discard* deletes the row,
and is offered only before a certificate exists. No address has been offered by
then, so no coin can be behind the key.

### The profile section

`ActiveProfileScreen` gets *Guarded wallets* between the profile's own rows and
*Switch profile*. There is one `ListItem` per `MemberWalletRow`, with the headline
*#Ekklesia*, or *#Ekklesia · Savings*, and the supporting line from the table. A
`ListItem` is clickable, so it gets `minimumInteractiveComponentSize()`. *New
guarded wallet* sits under the list. An empty list shows `EmptyState` with its own
message, *You have no guarded wallets*.

The list is never published. A kind 0 is public, and the xpub must never leave an
encryption.

### `MemberWalletScreen`

Route `MemberWalletRoute(activeUserPublicKey, walletId)`, a pushed screen with a
`NavigateBackButton`. It reuses `GroupWalletScreen`'s composables wherever they
are already separate: `BalanceCard`, `CoinRow`, and the address list as
`GroupWalletAddressesScreen` draws it (current address plus two, so a guardian's
watch never falls behind the addresses handed out). It adds:

- *Guarded by #Ekklesia and N more*, with the 12-block line;
- **guardian spends**: `GuardianManager.takenIn` over the wallet's history,
  rendered *#Ekklesia took 50,000 sats in …*. They are read off the chain on each
  pass, as a subgroup's are, and stored nowhere;
- *Send* (Phase 9), and for an `Open` wallet *Export key* (D6);
- the four states, wrapped in `ScreenStateTransition`.

### Losing the key by accident

`wipeDatabase()`'s callers ("end this" on `CreateProfileScreen`, and the sign-out
dialogs) check for an `Open` member wallet with a balance whose key exists only
here. If there is one, they say so and offer *Export key* before going on.

**Tests**, `MemberWalletViewModelJvmTest`: each row of the state table; a
certificate for this npub with no key here reads `WatchOnly`; *Discard* is not
offered once a certificate exists.

---

## Phase 9 — the member spends

- `MemberWalletSpendViewModel` builds with `GroupSpendBuilder`, checks with
  `GroupSpend.check(psbt, account, Spender.Owner)`, which is unchanged, signs each
  `SpentInput` with `MemberWalletDerivation.sign`, witnesses with
  `Checked.witnessed`, and asserts `correctlySpends` before it broadcasts.
- The form is `GroupSendScreen`'s. If its fields are not already a separate
  composable, extracting them is this phase's first commit, so the member's form
  cannot drift from the group's (the decimal-point bug in
  [group-send-an-amount.md](./group-send-an-amount.md) is the reason).
- `MemberWalletSpend` table (schema 30 → 31, or folded into Phase 5's bump if
  both land in one release): `txid` PK, `walletId`, `psbt`, `signedTransaction`,
  `broadcastAt`, `seenAt`, `confirmedAt`, `replacedByTxid`. It is the subset of
  `GroupTransaction` a spend with no session needs. `GroupTransaction` cannot hold
  it, because its foreign keys are to a session and a room.
- `GroupTransactionWatcher`'s follow and conflict logic, generalised over the
  row's type, or copied once with a comment pointing at the original if the
  generic version reads worse. *Bump the fee* signs a replacement locally.
- Change goes to the wallet's own change branch, reserved as
  `reservedChangeIndices` does for a group.
- `capFor(chain)` applies while D8's gate is shut.

**Tests**: a spend of two coins and change passes `correctlySpends`; a spend to
a foreign change output is refused by `check`, as it is for a group; a bump
replaces and settles.

---

## Phase 10 — the guardians see `@alice`

> Since [guarded-wallet-forwarding.md](./guarded-wallet-forwarding.md) a member's
> wallet reaches the groups above with nobody tapping *Share*: the group's devices
> that are also in a group above tell it as the certificate is filed.

The guardian side is built. What changes is what a guarded wallet can be.

[member-wallet-guarding-group.md](./member-wallet-guarding-group.md) later
gathered these rows by person and named, on each, the group that directly
guards the wallet.

### `GuardianManager.GuardedWallet` learns its subject

```kotlin
sealed interface GuardedSubject {
    /** The id the line of descent ends in: a room id, or a member wallet's W. */
    val id: String
    data class Subgroup(override val id: String, val name: String?) : GuardedSubject
    data class Member(override val id: String, val ownerPublicKey: HexKey, val label: String?) : GuardedSubject
}
```

`descendantChatRoomId` becomes `subject.id`. `statement` becomes `signedEvent`,
which is the 30331 for a subgroup and the 30334 for a member. `certificate` stays
the subgroup's alone. `spender()`, `position` and `lineage` do not change, because
`GuardianDerivation.lineage(guardians, W, ancestor)` already works for any
32-byte id.

### Reading

- `verify` dispatches on kind: 30331 takes today's path, and 30334 takes
  `MemberWalletCertificateEvent.verify` plus the same ancestor walk (rule 4 of
  `verify`'s kdoc).
- `guardedWalletsOf(G)` reads `G`'s **own** 30334 `GroupSignedEvent`s beside the
  30333 notices it was forwarded. The direct guardian holds the certificate it
  signed, so it needs no notice to learn of it.
- `shareableFrom(G)` includes `G`'s member certificates, so a member of `G` who
  sits in `P` sees them under *Tell the groups above*, and one tap forwards them.
  The 30333 notice carries a 30334 as its content. An older build's `verify`
  returns null for that kind, so it shows no line and lists nothing.
- `describeNotice` gets *Shared @alice's guarded wallet with this group*.

### Spending

`proposeGuardianSpend` and its `["guarded", <id>]` tag already take an id, and
`guardedWalletFor` looks it up. With `W` in that slot, nothing in `GroupSpend`'s
guardian arm changes. `GroupTransaction.guardedChatRoomId` holds `W` for a member
wallet. The column keeps its name, because a rename is a migration for a word, and
its kdoc says *the room or member wallet*.

### Showing it

`GroupWalletScreen.guardianItems` gives the headline by subject:

```kotlin
when (val subject = row.wallet.subject) {
    is GuardedSubject.Subgroup -> subject.name ?: stringResource(Res.string.a_subgroup)              // "#Translation"
    is GuardedSubject.Member   -> stringResource(Res.string.at_s, profileName(subject.ownerPublicKey)) // "@alice"
}
```

The profile name comes from `ProfileDao`, falling back to a shortened npub.
`GuardedWalletRow` carries the profile, so the composable does no lookups. A
member wallet with a label shows it in the supporting line, before the *within
reach* amount.

**Tests**, `GuardianSigningJvmTest`-shaped: a parent's shares sign a leaf of a
member wallet, and `correctlySpends` accepts it at sequence 12 and refuses it at
11; a root two levels up does the same through a forwarded notice; a notice
carrying a 30334 whose guardian entry for this group is not the key its walk
reaches is ignored; `guardedWalletsOf` returns both kinds, sorted by display name.

---

## Phase 11 — the group's page and the person's page

### `ChatRoomDetailScreen`

There is a new section, **Members' guarded wallets**, under *Subgroups*. Its rows
are `MemberWalletRow`s read from the room's 30334s:

- leading: the member's avatar;
- headline: `@alice`, and `@alice · Savings` when there is a label;
- supporting: *Asked by @alice, not yet signed* for a request with no certificate,
  and otherwise nothing more. The balance belongs to the wallet screen;
- tap: `ProfilePreviewRoute(activeUserPublicKey, ownerPublicKey)`, as member
  rows already do (`:575`).

Below the rows, for a member who may ask (D3), is *Ask this group to guard a
wallet for you*. An empty section with the button hidden is not shown at all.

### `ProfilePreviewScreen`

There is a new section, **Wallets your groups guard**, listing every guarded
wallet this device knows of whose subject is that npub. These come from each
group's own certificates and from forwarded notices, so a root sees a
grandchild's member. (Since [member-wallet-guarding-group.md](./member-wallet-guarding-group.md),
D7, there is one entry per wallet: *Guarded by* names the group that signed its
certificate, and each group here holding a leaf has a line of its own beneath.)
Each row reads *Guarded by #Ekklesia*, with the amount within
this group's reach, and *Spend* for a share-holder, which opens
`GroupSendRoute(chatRoomId = <the guardian room>, guardedChatRoomId = W)`, the
guardian spend that exists today. When the profile shown is the active one, the
section is the Phase 8 list instead.

This is where the question's "navigate to the individual user profile instead of
the group chat" ends up. A subgroup is somewhere you go into. A person is someone
you look at.

**Tests**: the detail screen's state lists a certified wallet and a pending
request separately from the subgroups; the preview's state lists wallets from two
guardian rooms for one npub.

---

## Phase 12 — the gate, docs, and the emulators

- `GroupWalletRollout.MEMBER_WALLETS_ON_MAINNET` and `offersMemberWalletsOn(chain)`,
  with the condition that lifts them (D8). They gate the entrances in Phase 6 and
  Phase 11's button only. A device with the gate shut still accepts, shows and
  signs a certificate somebody else asked for. A gate decides what this device
  offers, never what a quorum may decide.
- [group-wallet-guardians.md](./group-wallet-guardians.md): *What this does not
  do* gets a pointer here. *How an ancestor finds the coins* says the forwarded
  thing may be a member certificate.
- [subgroups.md](./subgroups.md): one line under the certificate's tag table
  saying a member wallet is not a subgroup and has its own kind.
- [README.md](./README.md): the row, and a sentence on reading order.
- This note: *Built*, and a table of where the build chose differently.

By hand, signet, three emulators, a root `R`, its guarded subgroup `M`, and Alice
in `M` but not in `R`:

1. Alice, holding no key state for `M`, asks for `M`'s key. An `M` signer's device
   answers it, and her *New guarded wallet* opens.
2. The consent screen names `#M` and one group above it. She asks, and `M`'s
   transcript says so.
3. An `M` signer taps *Put to the group*, and a second approves. Alice's profile
   row goes from *waiting* to *signing* to a balance of 0.
4. `M`'s detail screen lists `@alice` under *Members' guarded wallets*, and the
   row opens her profile.
5. Pay her receive address. A member in both `M` and `R` taps *Share*. `R`'s
   wallet screen lists `@alice` beside `#M`.
6. Alice spends part of it alone. `correctlySpends` holds, and her change is
   watched by `M` and `R`.
7. After 12 blocks, `R` takes the rest through its leaf. Alice's wallet screen
   says *#R took …*.
8. Alice's second phone, on the same profile, lists the wallet as *key on another
   device*.
9. A request edited to drop `R`'s key, sent from a debug build, is dropped by
   every `M` signer, and the reason is in the log.

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 1 | `MemberWalletDerivation` and its tests | dark |
| 2 | key notice, its dispatch arm | dark, but useful on its own |
| 3 | the request, pure | dark |
| 4 | the certificate, pure | dark |
| 5 | schema 30 | dark |
| 7 | signer checks, sponsoring, transcript arms | **before 6**: binding on every device that did not draw the screen |
| 6 | the member asks | **the switch** |
| 8 | the member's wallet and profile section | with 6 |
| 9 | the member spends | with 6. A wallet that can receive and not send is a trap |
| 10 | guardians list and spend from it | with 6 |
| 11 | detail and profile pages | any time after 7 |
| 12 | gate, docs, emulator run | last |

Phases 1 to 5 and 7 can land before anyone can ask for anything, and 7 should.
It is what makes every signer check the grant and every older-build bubble go
away, so it wants to be on every member's phone before the first request exists.
That is the policy note's reasoning about its own Phase 7, applied again.

**The mixed-version risk, placed once more.** A signer on a build before Phase 7
sees *Event of kind 30334* with nothing checked, and could sign a certificate with
a guardian missing. That does not cost the member coins, because they still hold
the key path. But it can drop a group above from a wallet it is meant to guard.
The remedy is the familiar one: everybody in a group that guards members should be
on a build from Phase 7 onward before the gate opens.

---

## What this does not do

**It does not make the guard cryptographic beyond the leaf.** The member's key is
the member's, and they can move every coin to a plain wallet at any time. What
the group gets is the right to take coins that have sat for 12 blocks, which is
the same thing a guarded subgroup's ancestors get, and nothing more.

**It does not guard a wallet that already exists.** The profile's own wallet,
and any wallet made elsewhere, stay as they are. Being guarded means a new key.

**It does not end when the member leaves the group.** The leaf is in every
address. A member who leaves keeps a wallet the group can still take from, and the
way out is to spend everything to a new wallet. The profile row says *You are no
longer in #Ekklesia* when that is so.

**It does not let the group revoke.** There is nothing to revoke. The
certificate is a signature that exists forever, and the group can only stop
watching.

**It does not reach a read-only profile at all.** It did not reach a member who
could not watch the chain either (D3), until
[standalone-electrum.md](./standalone-electrum.md) gave every profile a client.

**It does not survive a re-keyed group**, for the reason the guardians note
gives. The leaf is bound to `K_G`.

**It does not spend from several member wallets at once.** Each wallet is one
key, and a spend is from one wallet.

---

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| a 30329 birth certificate for the person | every reader of 30329 assumes a room. See the table at the top |
| the member coordinating the FROST session without a share | the closest fit to "the individual proposes", and FROST allows it. But `advance`, `proposeSigningBatch` and `signApprovedAt` all assume the coordinator signs, and this is the one manager every signature goes through. Worth revisiting if a second feature wants a non-signing coordinator |
| the group's signers computing the leaves | the member asked to incorporate them before sharing, and a member who checks nothing is agreeing to a tree they never saw |
| the member's nostr key as the wallet key | leaks the identity through any one address's key, does not work for a NIP-55 signer, and links the npub to the chain |
| a key from the profile's seed, hardened | backed up by the mnemonic for free, but an nsec or npub profile has none. The group is the recovery path anyway (D6) |
| `W = xonly(P_W)` | `W` would then be a key, and an event "signed by W" would read as signed by a room |
| a member's wallet as a 1-member subgroup | a ceremony, a key state, a Marmot room and a three-admin floor, to hold one person's key. The point of a member wallet is that it is *not* a group |
| filing the key notice into `GroupKeyState` | it would give every late member a key state and quietly change what `thresholdKeyFor` answers for them across the app. Reading it in one place, for one purpose, keeps the change where it is needed |
| choosing which ancestors guard it | the grant is a function, and a choice is a second thing signers would have to agree on. A guarded subgroup of `G` has no choice either |
| listing member wallets in the subgroups section | a row there navigates into a room, and its title is a founding name. A person is neither |
