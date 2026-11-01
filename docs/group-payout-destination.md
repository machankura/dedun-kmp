# Where the group pays a member: a Lightning address, and maybe an unused onchain address

A group that holds money will want to pay its own members: a share of what came
in, a refund, a reimbursement. Today the member who proposes such a payment has to
ask the payee for an invoice or an address, in the chat, every time. Then each
co-signer reads whatever the proposer pasted. Nothing in the room says where a
member wants to be paid.

This plan gives every member one standing record per group, a
**`GroupPayoutDestination`**:

- **A Lightning address (LUD-16, `name@domain`) is required.** It is the default
  way a group pays, because it can be paid any number of times without anybody
  handing out a fresh invoice.
- **An onchain address is optional, and has to be unused when it is set.** It is
  the fallback for an amount too large for the group's channels, or for a group
  that has no Lightning wallet at all.

**The member signs the destination with their own nostr key. It travels in the
room as an MLS application message, like a guarded-wallet request (30335), and is
never put on a relay. The newest one signed by a current member is the one that
counts. The group does not sign it, because where Alice wants to be paid is
Alice's fact, and a quorum adds nothing to it.**

Read [member-guarded-wallets.md](./member-guarded-wallets.md) Phase 3 first: the
request it describes is the only member-signed record a room carries today, and
this one copies its shape. [group-send-an-amount.md](./group-send-an-amount.md)
and the Lightning wallet's Phase 12 in
[group-lightning-wallet.md](./group-lightning-wallet.md) cover the two send forms
that Phase 7 below fills in.

**Built**, Phases 0-7, one commit each, written against `230d8dff`, schema v35,
with no schema change. Both spikes ran first, and their results are under each.
`GroupPayoutDestinationEvent`, `GroupPayoutDestinations`,
`LightningAddressResolver`, `UnusedAddressCheck` and
`GroupPayoutDestinationScreen` are the entry points. The phases below are kept as
written. Where the build chose differently, the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 1: any address type bitcoin-kmp accepts | the five templates a wallet hands out. bitcoin-kmp also reads segwit versions 2 to 16, which nothing can spend from yet, so a payout to one would be lost. Refused as *not an address* |
| Phase 1: `LightningAddress.parse` wraps the fork's parser | it does, for telling BIP-353 apart, and holds LUD-16 strictly on top: the fork keeps the first word of the first line, and here a second word is refused, as are an IP address and a port |
| Phase 1: `GroupPayoutDestination` with `ClosedRange<MilliSatoshi>` | `PayoutStatement`, sealed, is the reading: `GroupPayoutDestination` or `PayoutDestinationRemoved`. The range is a `Sendable` |
| Phase 3: five resolutions | a sixth, `Refused(reason)`, for LUD-06's `{"status": "ERROR"}`. A server that says why it won't answer is not the same as nonsense. The tag is checked before the fork's parser, so a withdraw request with no `k1` reads as *not a pay request* rather than malformed |
| Phase 3: `usageOf(chatRoomId, chain, script)` on `GroupOnchainWallets` | `usageOf(chain, script, ownWallet)`: the repository looks up the room's wallet. It waits up to five seconds for that wallet's first state and checks without it rather than not at all |
| Phase 4: `MemberWalletManager.queue` moved to a `RoomInnerEvents.queue` | reused where it is. `GroupPayoutDestinations.publish` calls it, then writes the member's own line, because a device never reads its own message back |
| Phase 4: the row reads the current address or *Not set* | and offers *Set* or *Change*. A profile with no identity counts as Tor on: there is nobody to set anything for, and nothing to leak |
| Phase 5: extend `ChatRoomDetailViewModelTest` | there was none. `MemberPayoutRowsJvmTest` renders the screen, and `GroupPayoutDestinationsJvmTest` covers a removed member |
| Phase 6: the transcript arm skips a signed event it has already filed | a re-sent statement's inner event carries a `resent` tag and makes no line on any device. That way the newcomer, who never saw the original, gets no line for something said before they joined either: walkthrough step 3 |
| Phase 7: the `payout` tag on the spend proposal too | not needed. Every co-signer's approval screen matches each output against the members' own signed onchain addresses (`GroupPayoutDestinations.payeesOf`), so the name is the device's own finding and the proposal carries nothing new. Only the 30363 gets the tag |
| Phase 7: the sheet's *Pay* buttons | shown only on a device that holds a share, since only that device can propose. *Pay onchain* refuses *couldn't check* as well as *paid before*. The Lightning label goes on the proposal only while the invoice in the form is the one the address gave |

Still to do by hand: the three-emulator run in Phase 8.

---

## What is already there

More of this exists than the app uses. Most of it sits in the Phoenix fork, and
the app never calls it.

| piece | where | state |
|---|---|---|
| a member-signed record, carried whole in the room | `MemberWalletRequestEvent` (30335): `build`, `verified`, `carried` (`nostr/wallet/MemberWalletRequestEvent.kt:30-264`) | built. This plan copies it |
| inner events kept as rows, newest per key | `MarmotInnerEvent` and `MarmotInnerEventDao.getByChatRoomAndKinds`. `MemberWalletManager.requestsIn` sorts newest first and keeps one per key (`managers/MemberWalletManager.kt:539-546`) | built |
| an inner event's author is the MLS sender | `MarmotInboundManager.mip03Rejection` (`managers/MarmotInboundManager.kt:95-110`) | built |
| the room's members | `Participant` and `ParticipantDao.findParticipantsByChatRoomId`. A removed member's row is hard-deleted (`MarmotInboundManager.kt:248`) | built |
| a transcript line for an inner event | `ChatMessage.applyInnerEvent`, its `when (event.kind)` (`database/model/ChatMessage.kt:1340`) | built |
| `lud16` from a member's kind 0 | `Profile.lud16` (`database/model/Profile.kt:58-59`). Display only | built |
| LUD-16 parsing and the `.well-known/lnurlp` URL | `Parser.parseEmailLikeAddress`, `EmailLikeAddress.LnurlBased` (fork `utils/Parser.kt:97,230`) | built, **never called from the app** |
| an LNURL-pay response as a type | `Lnurl.parseLnurlJson`, `LnurlPay` (fork `data/lnurl/`) | built, never called |
| asking the callback for an invoice, and refusing one for the wrong amount | `LnurlManager.requestPayInvoice` (fork `managers/LnurlManager.kt:111-145`) | built, never called. It runs on its own unproxied `HttpClient`, through a `PhoenixBusiness`, and an nsec profile has no business |
| an HTTP client | Ktor 3.5.2: CIO on Android and JVM, Darwin on iOS (`network/PlatformHttpClientEngine.kt`) | built. Used only for websockets and images. **No call honours Tor** |
| parsing an address for a chain | `Bitcoin.addressToPublicKeyScript(chainHash, …)`, in `GroupSpendBuilder.scriptOf` (`wallet/GroupSpendBuilder.kt:245-249`). The error is thrown away | built |
| an Electrum client per chain | `GroupChainClients.clientFor(chain)` (`wallet/GroupChainClients.kt:30`). It works for nodeless profiles too, through `StandaloneElectrum` | built |
| the group's own addresses | `GroupWalletState.derivationOf(script)` (`wallet/GroupOnchainWallet.kt:294-302`) | built |
| the group paying a BOLT11 invoice | 30363 `pay` authorisation, `GroupLightningAuthorisations.prepare`/`propose`, `GroupLightningSendScreen` | built |
| the group paying an onchain address | `GroupSendScreen`, `GroupSpendBuilder` | built |

Four things in that list decide how this plan is shaped.

1. **`getScriptHashHistory` cannot answer "unused".** lightning-kmp returns
   `listOf()` when the RPC fails (`ElectrumClient.kt:284`). An address checked
   while the server is down therefore reads as never used. `GroupOnchainWallets`
   makes this worse by wrapping the call in `getOrDefault(emptyList())`.
   `startScriptHashSubscription` is the call to use. It retries until it gets an
   answer (`rpcCallMustSucceed`, `:261-273`), and its `status` is `null` exactly
   when the script has no history, mempool included. Wrapped in a timeout, it
   gives three answers: unused, used, or *couldn't ask*. The plan needs all
   three.
2. **There is no Tor transport for HTTP.** Resolving a Lightning address is a
   clearnet GET to the address's domain. That tells the domain's owner that this
   IP is about to be paid through, or wants to be paid at, that name. A profile
   with Tor on has asked for exactly this not to happen, and today nothing in the
   app would stop it. D6 settles it.
3. **The fork's LNURL client needs a `PhoenixBusiness`.** An nsec profile can
   sign, so it can set a destination, but it has no node and no business
   ([nsec-sign-in.md](./nsec-sign-in.md)). So the app needs its own small
   resolver on the app's own engine. It can still use the fork's *pure* parsers.
   Phase 0's first spike checks that they are public.
4. **A co-signer cannot check that a Lightning invoice pays the member.** An
   LNURL callback's answer is not signed, and the invoice's description hash is
   just a hash that anybody's node can put in an invoice. This does not block
   *setting* a destination. It does limit what Phase 7 can claim, and that phase
   says so.

---

## Phase 0: decisions, before any code

**Half a day, and two spikes of an hour each.** D1 to D10 change what every later
phase builds.

### D1. A member-signed inner event, not a group-signed one

| place | who signs | why not |
|---|---|---|
| the member's **kind 0** (`lud16`) | the member | public on every relay. It is one value for the whole of nostr, not one per group. It holds no onchain address. It can change between a proposal and its signing without anybody in the room seeing the change. **Used as a starting value only** (Phase 4) |
| a **group-signed roster** | the group's quorum | a quorum for every change of address. The member can't change where they are paid without `t` other people, and the group's signature says nothing the member's doesn't |
| a **relay event** | the member | leaks the member's address and membership to anyone who reads the relay. A per-group onchain address next to an npub is exactly the link an *unused* address exists to avoid |
| a **NIP-17 DM** to each member | the member | `n` messages per change, nothing in the room's history, and a member added later gets nothing |
| **a member-signed inner event of its own kind, 30338** | the member | **recommended.** The same shape as 30335, delivered and authenticated by MLS, and signed, so the record outlives the room |

**Why signed, when MLS already authenticates the sender.** An inner event is an
unsigned rumor, and its row keeps no signature. Like 30335, the destination is
carried whole: the inner event's content is the signed event. This costs one
signature. It buys two things. Any later reader can check that the member said
it, including a group-signed payout that embeds it (Phase 7), or a device that
received it re-sent rather than from the member (D8). And `carried` can still
insist that whoever put it in the room is its author, as 30335 does.

**Why 30338.** It comes after the member-wallet kinds, which end at 30337, and
30338 to 30348 appear nowhere in the code or the docs. The Lightning kinds start
at 30349. The restored-marks plan has claimed 30367 to 30370.

### D2. One destination per member, per group, per chain

The `d` tag is `<room id>:<chain>`. Per group, because an onchain address given to
two groups links the two groups to one person. That is the reason the address has
to be unused, and one address per group keeps that reason true. Per chain,
because an onchain address belongs to exactly one network, and group wallets
exist on signet and regtest as well as mainnet. The Lightning address does not
depend on the chain, but the invoices it returns do (D5). In practice it is
mainnet, since `NodeParamsManager.chain` is hard-coded, and the screen takes its
chain from the route the way the other wallet screens do.

### D3. Newest wins, only from a current member, only about themselves

- **Author.** The signed event's `pubkey` is the member. `carried` refuses a
  signed event whose author is not the inner event's author. The `h` tag has to
  name the room it arrived in.
- **Newest.** `created_at`, with the id breaking ties: the same `ORDER` that
  `MemberWalletPolicyEvent.newestAmong` uses.
- **Current.** Only members in `Participant` are read. A removed member's rows
  stay where they are and are skipped. If they are added again, their old
  destination counts again. That is the record they last signed, and they can
  change it.
- **No future dates.** A destination dated more than ten minutes ahead of the
  reader's clock is ignored. Otherwise one could sit on top forever and stop its
  author replacing it.

### D4. Removing is a newer event, not a deletion

A member can withdraw their destination. The `removed` tag works like the
member-wallet policy's `closed` tag (that note's D9): a deletion never reaches
devices that already filed the event, and an absence cannot be told apart from a
destination that never arrived. A newest event with `removed` means *don't pay
me here*. It carries no addresses.

### D5. What "a Lightning address" means here

- **LUD-16 only**, `username@domain`. Lowercase the input, then require
  `username` to match `[a-z0-9._+-]+`. The domain has to be a hostname with a dot
  and must not end in `.onion` (D6). A leading `lightning:` is stripped, because
  people paste it.
- **Refused:** a bech32 `lnurl1…`, a BOLT12 offer, a BIP-353 `₿name@domain`, and
  a BOLT11 invoice. Each is refused with its own reason. The ask was a Lightning
  address. The group's node pays only BOLT11, so an offer couldn't be paid
  anyway. A BIP-353 name resolves over DNS to something that may be an offer.
  An invoice is single-use, which defeats the point of a standing record.
- **Resolved once, on the member's own device, when it is set** (Phase 3).
  Resolving means a GET to `https://<domain>/.well-known/lnurlp/<username>`.
  The answer has to be HTTP 200, JSON with `tag == "payRequest"`, an `https`
  callback, `minSendable` no greater than `maxSendable`, and metadata that
  parses. Nothing from the answer is stored except a `sendable` range, kept for
  display only. The answer can change at any time, and whoever pays resolves the
  address again (Phase 7).
- **Other members' devices don't resolve it on arrival.** Doing so would make
  every member's device contact the domain each time a destination arrived.

### D6. Tor on means the address is not resolved

With Tor on, the device doesn't make the clearnet request. The destination is
saved unresolved, and the screen says so: *Not checked: Tor is on, and this
device can't reach the address's server through it.* Nobody's identity setting
is quietly overridden. Once the app has a Tor transport, the resolver uses it,
and the unresolved state is shown only when that transport is unavailable.
Phase 7's payer follows the same rule.

### D7. What "unused" means for the onchain address

- **It is checked on the member's device when it is set**, against the chain's
  Electrum, using `startScriptHashSubscription` under a 15-second timeout. A
  `null` status means unused, mempool included. A non-null status means
  refused: *This address has been used before. Use a new one from your wallet.*
  A timeout or failure means refused as well, because unknown is not unused:
  *Couldn't check the address. Try again when you're connected.* An address is
  never published on a guess.
- **It is checked again by whoever pays** (Phase 7). An address the group has
  already paid is, by definition, used. It is shown as *paid before*, it can be
  copied, it is never offered as a QR code, and the payer is told to ask for a
  new one. These are the rules group-wallet-addresses.md gives for used
  addresses.
- **Refused if it belongs to the group's own wallet** (`derivationOf` on the
  room's wallet state for that chain). Paying the group from the group is the
  onchain twin of Lightning's `PaysTheGroup`. A member's guarded wallet under
  this group is allowed, because the money then belongs to the member, and
  `Destination.Funding` stays the better way to pay one.
- **Any address type bitcoin-kmp accepts for the chain**: P2PKH, P2SH, P2WPKH,
  P2WSH, P2TR. The parse error is kept rather than thrown away, so the screen
  can tell *wrong network* from *not an address*.
- **No xpub.** Handing the group an xpub would let it derive a fresh address for
  every payout, but it would also show the group every coin the member's wallet
  has ever received. One unused address is the ask.

### D8. A member added later gets the destinations from their owners

Inner events are not chronicled, so a member added after Alice set her
destination never sees it. **Each member's device sends its own newest
destination again whenever it files a commit that adds members.** Only the owner
sends, so no election is needed, and each re-send is one inner event per member
per addition. If Alice is offline, the new member sees no destination for her
until she next opens the app. The member row then says *hasn't said where to be
paid*, which is true from where the new member stands. Because the record is
signed (D1), a later plan can let any member forward somebody else's destination
the way guarded-wallet forwarding does. This plan does not.

### D9. Who can set one

Any profile that can sign nostr events and is a member of a Marmot group that is
not disbanded. An npub profile sees the destinations and has no button. Whether
the profile holds a share of the group key doesn't matter: the destination is not
a group signature.

### D10. Mixed versions

A build without Phase 2 shows a raw-JSON bubble for a kind it has no arm for. In
that bubble a member would see the destination's addresses. They are members, so
that is not a leak, but it is ugly. **Phase 2 ships a release ahead of Phase 4**,
for the same reason member-wallet-delay.md's Phase 2 shipped early.

### Spike A: the fork's pure LNURL pieces

Check that `Parser.parseEmailLikeAddress`, `EmailLikeAddress.LnurlBased.url`,
`Lnurl.processLnurlResponse` and `Lnurl.parseLnurlJson` are public and callable
from `commonMain` without a `PhoenixBusiness`. If they are, Phase 3 uses them. If
they are internal or need a logger the app can't supply, Phase 3 writes its own
forty lines and drops BIP-353 from the start: `parseEmailLikeAddress` tries DNS
first, and D5 refuses BIP-353.

**Result.** `Parser` is a public `object`, and `parseEmailLikeAddress` is a pure
function: it returns `Bip353` for a leading `₿` and `UnknownType` otherwise, and
never touches DNS. The DNS lookup is `SendManager`'s. `Lnurl.parseLnurlJson(url,
json)` and `LnurlPay.parseLnurlPayInvoice` are public and pure, and
`LnurlPay.Intent.Metadata.raw` keeps the metadata string that Phase 7 hashes.
`Lnurl.processLnurlResponse` wants a kermit `Logger`. The resolver reads the body
itself instead, which also lets it cap the body's size. Phase 1 wraps
`parseEmailLikeAddress`, and Phase 3 uses `parseLnurlJson`.

### Spike B: "unused" on both kinds of profile

Against regtest (`testing/regtest/regtest.sh up`), call
`startScriptHashSubscription` under `withTimeoutOrNull` on the node's client and
on a `StandaloneElectrum`. Do it for a fresh address, a funded address, an
address with only a mempool transaction, and with electrs stopped. Write down
what each returns. D7 assumes the answers are null, non-null, non-null and a
timeout. If the stopped-server case hangs past the timeout, the check needs a
`select` against the connection state instead.

**Result, 2026-11-01**, `PayoutAddressRegtestJvmTest`, on a `lightning-kmp`
`ElectrumClient` connected to the regtest electrs:

| address | `status` |
|---|---|
| fresh, from the miner's wallet | `null` |
| paid, still in the mempool | a status |
| paid and confirmed | a status |
| any address, with no server connected | no answer. `withTimeoutOrNull` returns `null` after 3 seconds |

So D7 holds as written. The disconnected client waits for a connection rather
than failing, so the timeout is the only way out, and it does not hang past the
timeout. `StandaloneElectrum` wraps the same `ElectrumClient`, so the
nodeless-profile case is the same call. The test stays as Phase 3's regtest
case.

---

## The event

```
kind:    30338
pubkey:  <the member>
content: ""
tags:
  ["d",         "<room id>:<chain>"]
  ["h",         <room id>]
  ["chain",     "mainnet" | "signet" | "testnet4" | "regtest"]
  ["lightning", "alice@example.com"]
  ["sendable",  <min msat>, <max msat>]       optional: what the address said when it was set
  ["onchain",   "bc1p…"]                       optional
  ["removed"]                                  instead of lightning, sendable, onchain
```

It is carried in the room as an inner event of the same kind whose content is
the signed event, whole (`carried`, as in 30335). **Never on a relay.**

`GroupPayoutDestination` is the reading:

```kotlin
data class GroupPayoutDestination(
    val member: String,             // hex pubkey
    val chatRoomId: String,
    val chain: Chain,
    val lightningAddress: LightningAddress,
    val sendable: ClosedRange<MilliSatoshi>?,
    val onchainAddress: String?,
    val event: Event,               // the signed event, for embedding (Phase 7)
)
```

A `removed` event reads as `null` for that member, and stops any older event
being read in its place.

---

## Phase 1: the event and its two values, pure

**One and a half days.** Dark: a kind that nothing produces.

- `text/LightningAddress.kt`: `LightningAddress.parse(input): Either<Problem,
  LightningAddress>`, with `Problem` = `NotAnAddress`, `Lnurl`, `Offer`,
  `Bip353`, `Invoice`, `OnionDomain`. Also `wellKnownUrl`. This is pure. If
  Spike A says so, it wraps the fork's parser.
- `wallet/PayoutAddress.kt`: `PayoutAddress.parse(chain, input): Either<Problem,
  ByteVector>`, with `Problem` = `NotAnAddress`, `OtherNetwork`. It keeps
  bitcoin-kmp's `ChainHashMismatch` rather than dropping it. A leading
  `bitcoin:` is stripped, along with any query string.
- `nostr/wallet/GroupPayoutDestinationEvent.kt`: `KIND`, `build(signer, room,
  chain, lightning, sendable, onchain)`, `buildRemoved(signer, room, chain)`,
  `read`, `verified` (signature, `d` matching `h`:`chain`, both addresses
  reparsed for the chain), `carried`, `ORDER`, `newestAmong`. The kdoc says
  *why 30338*, *signed and carried whole*, and *never on a relay*, the three
  headings 30335's kdoc has.
- `GroupPayoutDestinationEventTest` covers:
  - a round trip, with and without `onchain`;
  - an event naming another room;
  - a `d` that disagrees with `h`;
  - a testnet address on mainnet;
  - a removed event carrying an address;
  - an author who isn't the carrier;
  - two events at the same second (the id decides);
  - an event dated an hour ahead.
- `LightningAddressTest` holds one row per accepted and refused form in D5,
  plus mixed case, surrounding spaces and the `lightning:` prefix.

## Phase 2: filed, read, and a line in the transcript

**One day.** Dark, and **ships a release before Phase 4** (D10).

- `ChatMessage.applyInnerEvent`: a `GroupPayoutDestinationEvent.KIND` arm.
  `carried`, then `verified`, then the room check, then a line. The line reads
  *@name set where this group pays them*, or *@name removed where this group
  pays them*. **No address in the line.** The member row shows it (Phase 5). A
  line, unlike a row, stays in the scrollback after the member changes it. New
  `TYPE_PAYOUT_DESTINATION_SET` / `_REMOVED`. The chat list's summary arm gets
  the same words.
- `managers/GroupPayoutDestinations.kt`: `destinationsIn(database, chatRoomId,
  chain): Map<String, GroupPayoutDestination>`. It does
  `getByChatRoomAndKinds`, then `carried`, `verified`, D3's filters, and
  `newestAmong` per member, then drops the removed. It also has
  `destinationOf(database, chatRoomId, chain, member)`. **No schema change**:
  the `MarmotInnerEvent` row is the whole record, as it is for 30335. If a room
  with long history makes the read slow, a typed table is a later, separate
  change.
- Tests in `GroupPayoutDestinationsJvmTest`, on the in-memory database:
  newest wins; removed hides an older one; a removed member is skipped and
  comes back when added again; another chain is not read.

## Phase 3: the two checks before anything is sent

**Two days.**

- `network/LightningAddressResolver.kt`: `resolve(address, torEnabled):
  Resolution`, where `Resolution` = `Resolved(sendable)`, `NotChecked(TOR)`,
  `Unreachable`, `NotFound`, `NotAPayRequest`, `Malformed(reason)`. It uses
  `platformHttpClientEngineFactory()`. It has a 10-second timeout, no redirects
  to another host, and the body is capped at 64 KiB. JSON is parsed with
  kotlinx-serialization directly, so no content-negotiation dependency is added.
- `wallet/UnusedAddressCheck.kt`: `check(chain, script): Usage`, where `Usage` =
  `Unused`, `Used`, `Unknown`, `TheGroups`. It calls
  `GroupChainClients.clientFor(chain).startScriptHashSubscription` under
  `withTimeoutOrNull(15.seconds)` (D7, Spike B), then `derivationOf` on the
  room's wallet for that chain, if it has one. It is exposed through
  `GroupOnchainWallets` as `usageOf(chatRoomId, chain, script)` rather than by
  handing out `clients`.
- Tests:
  - `LightningAddressResolverTest`, on Ktor's `MockEngine`. This adds
    `ktor-client-mock` as a `commonTest` dependency, the one build change in the
    plan. It covers each `Resolution`, a redirect to another host, an `http`
    callback, `min > max`, and an oversized body.
  - `UnusedAddressCheckTest`, on a fake `IElectrumClient`: null status,
    non-null status, a client that never answers, and the group's own receive
    address.
  - A regtest case added to `GroupLightningRegtestJvmTest`'s fixture set, or a
    sibling `PayoutAddressRegtestJvmTest`: fund an address, then watch it go
    from `Unused` to `Used`.

## Phase 4: the screen where a member sets theirs

**Two and a half days.**

- `GroupPayoutDestinationRoute(chatRoomId, chain?)`, wired in `DedunNavHost` the
  way `MemberWalletPolicyRoute` is (`:1376-1392`).
- `GroupPayoutDestinationViewModel` holds `lightningField` and `onchainField` as
  typed. The UI state is `Loading`, `Loaded(current, canSet)` and `Error`. The
  first time, the Lightning field is filled from the member's own
  `Profile.lud16` when that parses, and the field label says it came from their
  profile. `save()` runs:
  1. parse both fields;
  2. resolve the Lightning address and, at the same time, check the onchain
     address if there is one;
  3. refuse on any problem except `NotChecked(TOR)` (D6), each with its own
     sentence;
  4. build and sign the event, then queue it with `MemberWalletManager.queue`'s
     path, generalised and moved to a small `RoomInnerEvents.queue`;
  5. call back for the snackbar.

  `remove()` builds the removed event.
- `GroupPayoutDestinationScreen`. Following CLAUDE.md:
  - a `TopAppBar` with `NavigateBackButton`;
  - `ScreenStateTransition` over the state `when`, and `readableContent()`;
  - spacing from `MaterialTheme.spacing`, and every string from the catalogue in
    sentence case;
  - `rememberNotifier` for *Payout destination saved* and *Payout destination
    removed*.

  It has the Lightning field (required), the onchain field (optional), with
  supporting text that says why the address must be new (*The group sees this
  address. A new one keeps your other payments out of its view.*), the
  resolved `sendable` range once checked, *Save*, and, when one exists,
  *Remove*. While a check runs, *Save* shows progress and the fields are
  read-only.
- `ChatRoomDetailScreen` gets a *Payouts* section above *Members*, with one row:
  *Where this group pays you*. The row shows the current Lightning address, or
  *Not set*. It is hidden for a disbanded group and shown without a button for
  an npub profile (D9).
- Tests: `GroupPayoutDestinationViewModelTest`, on a fake resolver and a fake
  usage check. It covers each refusal sentence, Tor, the prefill, save
  queueing exactly one inner event, and remove.
- `./gradlew :composeApp:m3Audit` stays inside every budget.

## Phase 5: what the other members see

**One and a half days.**

- Each row in *Members* on `ChatRoomDetailScreen` gains a supporting line with
  the member's Lightning address, or *Hasn't said where to be paid*. Tapping
  the line opens a bottom sheet with:
  - the Lightning address, with copy;
  - the onchain address, if there is one, with copy;
  - *Set on <date>*;
  - for the onchain address, a usage line, checked lazily when the sheet opens
    with Phase 3's check: *Unused*, *Paid before*, or *Couldn't check*.

  There is no QR code for a *paid before* address.
- The member's own row shows *Where this group pays you* in place of the
  supporting line, and opens Phase 4's screen.
- `ChatRoomDetailViewModel` reads `destinationsIn` in the same pass that builds
  `Loaded`.
- Tests: an extended `ChatRoomDetailViewModelTest`. A removed member doesn't
  appear. A member with a removed destination reads *Hasn't said*.

## Phase 6: a member added later is told

**One day.**

- In `MarmotInboundManager.processGroupMembershipChanges`, after a commit that
  adds at least one member is filed, this device re-queues its own profile's
  newest destination for that room, on every chain it has one for (D8). It does
  nothing for a removed destination, since the new member has nothing older to
  hide.
- Re-queueing the same signed event is idempotent on every receiver, because
  `newestAmong` doesn't care that it arrives twice. The transcript arm skips the
  line when the same signed event id has already been filed in the room.
- Test: a two-member room, with Alice's destination set. Carol is added, then
  Alice files the commit. Alice's device queued exactly one inner event,
  carrying the signed event it held before. On Carol's side, `destinationsIn`
  reads Alice's destination, and Carol's transcript has no second line on
  Alice's side.

## Phase 7: the group pays it, from the member's sheet

**Three days. Optional, and a release of its own.** Phases 1-6 are useful
without it: a member can copy the address into either send form. This phase
does the copying.

- **Pay over Lightning.** The sheet's *Pay* button asks for an amount and checks
  it against `sendable`. It then resolves the address again on the proposer's
  device, under D6. It calls the callback with `amount` (in msat), and refuses an
  invoice whose amount differs, whose network differs, or whose description
  hash isn't `sha256(metadata)` (LUD-06). It then opens `GroupLightningSendScreen`
  with the invoice filled in. Everything after that is the existing 30363 flow.
- **Pay onchain.** The sheet checks the address again (D7). For `Used`, it shows
  the payer *paid before: ask @name for a new address* and doesn't open the
  form. Otherwise it opens `GroupSendScreen` with the address filled in.
- **The approval screen names the member, and says that it is the proposer's
  word.** The 30363 authorisation and the spend proposal gain an optional
  `["payout", <member>, <destination event id>]` tag. `ProposedEvent` then reads
  *Pay 21,000 sats to @alice's Lightning address, as the proposer resolved it*.
  For onchain, the co-signer's device *can* check the match: the output's
  script equals the destination's onchain address for that member, and the
  line drops the qualifier. For Lightning, it cannot (fact 4 above). No
  co-signer rule is added: the tag is a label, `read()` ignores it on older
  builds, and the payee check stays *pay N sats to node `…`*.
- Tests:
  - `PayoutInvoiceTest`, on `MockEngine`: wrong amount, wrong network, wrong
    description hash, `amount` outside `sendable`.
  - Pre-filled routes for both forms.
  - The onchain label check: a matching script and a mismatched one.

## Phase 8: docs, and checking it by hand

**One day.**

- This note's *Built* line and the table of where the build chose differently.
  A row in [README.md](./README.md). Every note that lists the member-wallet
  kinds as ending at 30337 (member-guarded-wallets.md, member-wallet-delay.md)
  gets a pointer to 30338.
- `StringCatalogueJvmTest` stays green, along with
  `./gradlew :composeApp:m3Audit` and
  `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest`.
- On three emulators against regtest, Alice, Bob and Carol, where Carol is added
  after step 2:
  1. Alice sets `alice@<test LNURL server>` and a fresh regtest address. Bob's
     transcript gets a line, and his *Members* row shows her Lightning address.
  2. Bob sets a Lightning address only. Alice's sheet for Bob shows no onchain
     row.
  3. Carol is added. Within one sync she sees both destinations, with no new
     transcript lines from before she joined.
  4. Alice tries an address she has already received to. She is refused with
     *used before*.
  5. Electrs is stopped and Alice tries a new address. She is refused with
     *couldn't check*.
  6. Bob turns Tor on and changes his address. It saves as *Not checked*.
  7. *(Phase 7)* Bob pays Alice 1,000 sats onchain from her sheet. Her sheet then
     reads *Paid before*, and the next *Pay onchain* from the sheet tells Bob to
     ask her for a new one.
  8. Alice removes hers. Bob's row reads *Hasn't said where to be paid*.

```bash
testing/regtest/regtest.sh up
```

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 0 | this note, two spikes | nothing |
| 1 | `LightningAddress`, `PayoutAddress`, `GroupPayoutDestinationEvent` and their tests | dark |
| 2 | the transcript and summary arms, `GroupPayoutDestinations` | dark, **a release ahead of 4** (D10) |
| 3 | `LightningAddressResolver`, `UnusedAddressCheck`, `ktor-client-mock` in tests | dark |
| 4 | the route, screen, view model and the detail-screen row | **with 5 and 6**: from here members can set one |
| 5 | member rows and the sheet | **with 4 and 6** |
| 6 | the re-send on commits that add members | **with 4 and 5**: without it, the first member added after release sees nobody's destination |
| 7 | pre-filled send forms, the `payout` label | its own release, after 4-6 have had one |
| 8 | docs and the emulator run | last |

About fourteen working days for Phases 0-6 and 8, and three more for Phase 7.
**No schema change**: destinations are `MarmotInnerEvent` rows, like requests.
**No new co-signer rule**: nothing here changes what a group signature
authorises.

---

## What this does not do

**It does not let co-signers verify a Lightning payee.** A Lightning address
resolves to an invoice from a server that signs nothing. Phase 7 labels a
payment as being for a member, on the proposer's word. Proving the payee would
need the member's own node to issue the invoice, or BOLT12 with a key the member
signs into the destination. Both are larger than this plan.

**It does not keep the onchain address unused.** It is unused when set, and
checked again before paying. After one payment it is used, and only the member
can give a new one. A group that pays a member often should pay over Lightning.

**It does not forward other members' destinations.** A member added while Alice
is offline waits for Alice (D8). The event is signed so that a later plan can do
this, the way guarded-wallet forwarding does.

**It does not put anything on a relay, or change the member's kind 0.** The
kind 0's `lud16` is read as a starting value, once, and never written.

**It does not reach other groups.** A destination is for one room. A member of
a subgroup and its parent sets one in each, which keeps D2's reason intact.

**It does not add Tor to HTTP.** It refuses to make the clearnet request when
Tor is on (D6), and becomes stricter once a transport exists.

## Appendix: what was considered and rejected

| idea | why not |
|---|---|
| reading `lud16` from kind 0 and nothing else | public, global, unsigned for this group, and no onchain address (D1) |
| a roster the group's quorum signs | a quorum for a fact only the member can state (D1) |
| one destination per member across all groups | one onchain address shared by several groups links them (D2) |
| accepting LNURL bech32, BOLT12 offers or BIP-353 names | the ask was a Lightning address. The group's node pays only BOLT11, and BIP-353 can resolve to an offer (D5) |
| other members' devices resolving the address on arrival | every member's device would contact the domain each time a destination arrived, and Tor-on devices couldn't anyway (D5, D6) |
| `getScriptHashHistory` for the unused check | an RPC failure reads as no history (fact 1). An address would be published as unused on a guess |
| an xpub, so the group derives a fresh address per payout | shows the group the member's whole wallet (D7) |
| deleting a destination to remove it | a deletion never reaches devices that already filed the event (D4) |
| a typed `GroupPayoutDestination` table | the `MarmotInnerEvent` row already holds it whole. 30335 does the same, and a table is a migration with nothing to put in it that the row lacks |
| making the onchain address required | asked for as optional. A group with only a Lightning wallet has no use for one |
