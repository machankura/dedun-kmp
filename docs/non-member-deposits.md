# Deposits for someone outside the group: attributed to a phone number or an email address

A group saves together, but not everyone whose money goes into it is a member. A
mother saves through her son's group. A cousin with no smartphone hands over cash, and
somebody in the group deposits it for them. A neighbour pays the group's invoice for a
member's stokvel share. Today every deposit is one member's, nobody's yet, or the
group's own ([member-deposits.md](./member-deposits.md), D10). The money of a person
outside the group can only be counted to the member who brought it in, and the tally
then says that member put in what they didn't.

This plan lets the group say whose a deposit really is when that person isn't in the
group. **Any member proposes a *contact*: a name and one phone number or one email
address. The group's quorum signs the contact whole, as a *contact record*, and only a
contact the group has signed can be counted. Every member sees who the contact is.
Deposits are then attributed to the contact the way they are attributed to a member
today: by a signed 30390, and later by a deposit branch and invoices of the contact's
own. The group can pay a contact out, to a payout destination it signs for them, by
the same rules it pays a member. If the person later joins the group, the quorum signs
that the contact was them, and from then on the contact's deposits and balance are
that member's.** At every step the quorum decides. Nothing is sent to the contact, and
nothing checks their number or address.

Read [member-deposits.md](./member-deposits.md) first: the depositor statement (30339),
the branch (D3), the attribution (D10) and the ledger are what this plan extends. Then
read [payouts-only.md](./payouts-only.md) and
[group-payout-destination.md](./group-payout-destination.md), whose rules a contact's
payout follows, and [member-transfers.md](./member-transfers.md) T10, for how a member
without a share asks. Read [member-chronicle.md](./member-chronicle.md) for how a
member added later gets the group's signed record.

**Built**, Phases 0-8, one commit each, written against `1a111849`, schema version 49. No
schema change was needed. Phase 9's checks ran as the table below Phase 9 says, the
four-emulator run included; it found one gap in this feature that blocks D5 in practice (a
device doesn't announce on joining or updating) and five smaller ones. `Contacts`, `ContactIdentifier`, `Party`, the six `Contact*Event`
kinds, `ContactScreen` and `AddContactSheet` are the entry points. Where the build chose
differently from the phases below, this table says so, and the phases are kept as written.

| where | the plan said | the build does, and why |
|---|---|---|
| D2 | an email address is NFC-normalised | ASCII only, trimmed and lowercased. NFC needs each platform's own normaliser, and two that disagreed on one address would give one person two ids. An internationalised address is refused rather than risked |
| D4, 30398 | a member-signed request | an ordinary inner event. MLS already says who sent it, and the request decides nothing (D7), so no later reader needs a signature to check |
| Phase 2 | `ContactRow` and `MemberFeatureRow` tables, schema 50 | no new table and no schema change. Contacts are read from `GroupSignedEvent` each time, as `Depositors` reads statements, and announcements from the stored inner events, as `GroupPayoutDestinations` reads destinations |
| D6, Phase 2 | a transcript line renders the contact's name at display time | the line's text is written when the event arrives, as every other group-signed line's is. A rename after that doesn't rewrite older lines. The contact's page and the tallies always show the current name |
| D9, Phase 2 | `payoutDestinationsIn` returns `Map<Party, PayoutDestination>` | it stays keyed by the 64-hex id. A contact's id never coincides with a member's key, and every caller already keys by id. `PayoutDestination` is an interface that a member's 30338 and a contact's 30399 both implement |
| D8, Phase 2 | claims resolve where a party is read | also in `DepositLedger.signedPayouts` and `GroupWavelengthPayouts.balancesIn`, so a payout made to a contact before the claim counts against the member after it |
| D7, Phase 3 | the member whose deposit is moved is notified | the app has no notifications of its own. The signing screen says, in the error colour, whose deposits an attribution gives away and that the group can do it without them. Every member who opens the proposal sees it |
| Phase 3 | `ProposedEvent.summarize` takes a separate `contactNameOf` | the one `nameOf` answers for a contact's id too. A second function parameter after it would have caught every caller's trailing lambda. The signing screen passes contacts' names. The proposal list and the transcript preview don't, and say "a contact" |
| Phase 5 | *Deposits were made for me before I joined* is a banner for a member's first week | it is a button on the Deposits screen, for as long as the group counts an unclaimed contact. The app keeps no record of when a member joined, and the button costs nothing when unused |
| Phase 5 | the Deposits screen says *For @mum, before they joined* | a claimed contact's deposits read *From @mum*, as any of the member's do. The contact's page says *Mum is @mum*, and keeps listing the deposits that were Mum's |
| D9, Phase 6 | every signer checks a contact's onchain address is unused, by its own Electrum, and not the group's own | the proposing device makes both checks, as a member's own device does for theirs, and refuses to propose otherwise. Signers check what they can without the network: the address is on the group's chain, and nobody else is paid at it. A signer's check runs where `FrostSigningManager` has no wallet or Electrum client |
| Phase 6 | *Pay out* on the contact's page opens the payout sheet | it opens the group's Payouts screen, where the contact's balance is a row like a member's, named and offered by the same rules |
| D10, Phase 8 | the receive card offers *Deposit for someone outside the group*, and the addresses screen lists contacts' branches | the contact's page has a *Deposits for them* section: *Open deposits for them*, then the address on their branch with its QR code and copy, and *Ask for a Lightning invoice for them*, which opens the Lightning receive screen for that contact. A contact's addresses aren't listed beside members' branches |
| D10, Phase 8 | the operator answers a contact's invoice request from the contact's sponsor | from any current member, since the quorum, not the sponsor, decides about a contact (owner's decision 3) |
| Phase 3 | a request is put to the group from wherever a share-holder sees it | from a *Requests* section at the top of the Deposits screen, which shows each request summarised as the signing screen would summarise it |

**Decided by the owner, 2026-11-25:**
1. The contact is signed whole and not encrypted, and every member sees it in full.
2. The group can pay out a contact who hasn't joined.
3. The quorum is supreme. It may give any deposit to a contact, a member's included,
   without that member asking.
4. Mainnet is ready. There's no regtest-and-signet stage.
5. Nothing is sent to the contact by SMS or email, and nothing checks the number or
   the address.

---

## What is already there

| fact | where | what it means here |
|---|---|---|
| A deposit's owner is a 64-hex string in `DepositRow.member`, in the 30390's member slot, and in `CoinOwnership.Owner.Member` | [DepositRow.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/DepositRow.kt), [DepositAttributionEvent.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/wallet/DepositAttributionEvent.kt), [CoinOwnership.kt:98](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/CoinOwnership.kt) | a contact's id is 32 bytes too, so tallies, coin shares and balances carry it unchanged. Only the wire form and the screens need to tell the two apart (D3) |
| `DepositAttributionEvent.read` rejects the whole event if one member slot is neither `group` nor 64 hex | [DepositAttributionEvent.kt:117](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/wallet/DepositAttributionEvent.kt) | an older build drops an attribution that names a contact, and shows those deposits as unattributed. That is the honest way to degrade, and it decides the wire form (D3) |
| A signer refuses an attribution to anybody without a depositor statement | [DepositLedger.kt:260](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/DepositLedger.kt) `refuseAttribution` | an older signer refuses every contact attribution. Mixed versions fail closed |
| A signer's checks are chosen by the proposed event's kind, and a kind with no checks is shown as *Event of kind N* with its content | [FrostSigningManager.kt:768](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/FrostSigningManager.kt), [ProposedEvent.kt:317](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/text/ProposedEvent.kt) | each contact kind needs its own checks and its own summary. An older build could be tapped into signing one blind, which is part of why D5 waits for every device to read contacts |
| **Every device in the room applies a completed group-signed event through `ChatMessage.applyInnerEvent`, and an unknown kind becomes a chat bubble of its raw JSON** | [FrostSigningManager.kt:2451](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/FrostSigningManager.kt) `applySignedEvent`, [ChatMessage.kt:2024](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt) `unsupported()` | on an older build, a signed contact record would land as JSON in the chat. This decides D5 |
| An older build's chronicle skips any kind it doesn't carry | [ChronicleManager.kt:539](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/ChronicleManager.kt) | a member who joins on an older build isn't shown the records already signed. Only records signed while they are in the room reach them, and D5 stops those |
| A payout is checked against the payee's newest destination, by that destination event's id | [GroupWavelengthPayouts.kt:233](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupWavelengthPayouts.kt), `GroupLightningAuthorisationEvent.Payout(member, destinationEventId)`, [MemberBalances.kt:81](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/MemberBalances.kt) `payoutContextOf` | a contact's destination has to be an event too, with an id. Payouts then name it as they name a member's, and every rail's check reads it through one lookup (D9) |
| A member's destination is member-signed (30338): a Lightning address, and an unused onchain address or none | [group-payout-destination.md](./group-payout-destination.md) D1, D7 | a contact can't sign, so their destination is group-signed. The quorum stands where the member's own signature stands (D9) |
| The paid-out column counts every address a member ever signed | [DepositLedger.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/DepositLedger.kt) `paidOut`, `GroupPayoutDestinations.everSignedIn` | contacts' destinations join that list, or a contact's payouts would never show as paid |
| The branch is a tagged hash of room, member and salt, checked for collisions against every statement | [DepositBranch.kt:41](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/DepositBranch.kt), [Depositors.kt:78](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/Depositors.kt) | `DepositBranch.of(room, contactId, salt)` works as it is. The collision check has to cover contacts' branches as well as members' |
| A Wavelength deposit request is refused from anybody without a depositor statement | [GroupWavelengthDeposits.kt:72](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupWavelengthDeposits.kt) | no Wavelength deposit address is issued for a contact in this plan. A contact's Wavelength balance, from attributed deposits, can still be paid out |
| The attribute dialog offers the depositors and *The group's own*, and only on a device that `canSign` | [DepositsScreen.kt:167](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/DepositsScreen.kt) `AttributeDialog` | the contact choice goes here, and a member without a share asks (T10) |
| The chronicle carries 30339, 30390, 30391, 30393, in that apply order | [ChronicleEvent.kt:173](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/chronicle/ChronicleEvent.kt) | the contact kinds join it, ahead of the attribution |
| Nothing in the app normalises, stores or shows a phone number or an email address, and nothing says which build a member runs | `grep` over `commonMain` | both are new. Spike S1 picks the phone normaliser, and Spike S2 the announcement |
| 30383–30412 is Dedun's range. 30390–30393 and 30400–30412 are taken. `docs/kinds.md` isn't written yet | [giza-into-dedun.md](./giza-into-dedun.md), Phase K and Phase 11 | the six new kinds take 30394–30399, the rest of that gap. They go in the register before any code (Spike S3) |

---

## Phase 0: decisions, before any code

### D1. A contact is a person the group agreed to count, and pays by its own signature

A contact is a *party* to the ledger, never a member. A contact has a tally, deposits,
coins, a Lightning balance and a Wavelength balance. A contact has no share, no vote and
no messages. Their payout destination is one the group signs for them (D9).

**Only a contact the group has signed exists.** No device counts, shows, attributes to
or pays a contact without a verified contact record. An attribution or a payout naming
a contact the signer has no record of is refused. The quorum that signs a record is
the group agreeing that this person, by this name and this number or address, may be
counted.

A contact's balance leaves them in three ways:

- **a payout** (D9), to the destination the group signed for them, under the rules a
  member's payout follows;
- **a claim** (D8): the person joins the group, and the group signs that the contact
  was them. Their deposits, coins and balance are then a member's like any other;
- **a correction**: the group signs a newer 30390 that gives the deposit to someone
  else, as it can for any deposit today.

### D2. The identifier: one phone number or one email address, normalised

- **Phone:** E.164 only: `+`, a country calling code and a national number, 8 to 15
  digits in all. Spaces, dashes and brackets are stripped. A number typed without
  `+` or `00` takes the country the person picks. The picker defaults to the
  device's region, and a trunk `0` is dropped. The screen shows the number back
  formatted before the member confirms. Spike S1 decides between a library and a
  hand-written normaliser.
- **Email:** trimmed, NFC, lowercased whole. Exactly one `@`, a dot in the
  domain, 254 characters at most. No provider-specific rules: dots in a Gmail
  address are not stripped, because that's Google's rule and not the address's.
- **One identifier per contact, fixed once signed.** A person known by a number and
  an address is two contacts. One member can claim both (D8). Another number is
  another contact, never an edit, so a contact the group agreed to can't be pointed
  at somebody else afterwards.

Every signer normalises the identifier again, and refuses a record whose identifier
doesn't come out the same. Two builds must never spell one number two ways. If they
did, D3 would give one person two ids.

Nobody checks that the person holds the number or address. The identifier is what the
proposer said, and the quorum accepted it.

### D3. The contact record, and the contact's id

```
kind:    30394
pubkey:  <the room>
content: ""
tags:
  ["d",          <contact id>]
  ["p",          <proposer>]                      who asked: shown as "added by", decides nothing
  ["name",       "Mum"]                           1–64 characters, what the group calls them
  ["identifier", "phone" | "email", "+27821234567"]
```

```
contactId = taggedHash("dedun/contact/v1", room ‖ type ‖ utf8(identifier))
```

`type` is one byte: `p` for phone, `e` for email.

- **Derived, not chosen.** A proposer who could choose a contact id could choose an
  existing member's public key. That contact's deposits would then fold into the
  member, or the member's into the contact. Every signer recomputes the id, and a
  hash can't be steered onto a key.
- **One id per person per room, by construction.** The same number in the same room
  is the same id. The record is write-once (the first by `created_at`, then id,
  wins), so a second record for a number the group already counts is refused, and
  no duplicate check is needed. The add sheet finds the existing contact by
  computing the id, and offers that one instead. A closed contact is reopened by an
  amendment, never re-added.
- **On the wire** a contact is `contact:<id>` wherever a member slot would hold a
  key: in the 30390's member slot and in the payout kinds' payee slots (D9). In
  storage and in the ledger it is the bare 64-hex id, so `DepositRow`, `Tally`,
  `CoinOwnership` and `LightningBalance` carry it unchanged. A `Party` type
  (`Member(pubkey)` or `Contact(id)`) is read at the boundaries: the screens, the
  signer checks and the payout context. That way no code that sends to a key, or
  looks up a profile, is handed a contact's id.

### D4. Six kinds, 30394–30399, every one group-signed but the request

| kind | name | carried | what it says |
|---|---|---|---|
| 30394 | contact record | chronicle | the contact, whole (D3). Write-once |
| 30395 | contact amendment | chronicle | a new `name`, `closed` (no new deposits may be attributed or opened for them, and their tally stands) or `open`. Newest wins per contact. It never changes the identifier (D2) |
| 30396 | contact depositor statement | chronicle | the contact's deposit branch on one chain (D10). Write-once per contact per chain, like 30339 |
| 30397 | contact claim | chronicle | contact id → member key, or `none`. Newest wins per contact, so the quorum can correct a claim (D8) |
| 30398 | contact request | inner event, member-signed | *put this to the group*: the unsigned template of any of the five above, or of a 30390 naming a contact, from a member without a share (T10). It carries no authority. Signers check the proposal, not who asked |
| 30399 | contact payout destination | chronicle | where the group pays the contact on one chain: a Lightning address, an unused onchain address, or both, or `removed`. Newest wins per contact per chain (D9) |

The 30390 doesn't change kind. Its member slot learns `contact:<id>`.

**Why the group signs the contact, not just the deposits.** A signature on a 30390 says
*this deposit is X's*. Without a record, X is whatever the proposer typed, and nobody
agreed who X is. With the record, every attribution and every payout points at a
person the quorum looked at and accepted. Signing a record takes one more round, once
per contact. After that, no attribution to that contact needs a new decision about who
they are.

**Why not one 30339 with a contact form.** The depositor statement embeds the
member's signed payout destination, and a contact can't sign one. An older build
would also fail to verify a contact-form 30339 and silently not watch its branch.
Coins paid there would vanish from that build's balance, not show as unattributed. A
kind of its own makes the older build's ignorance visible: it never watches a
contact's branch, and a contact's address is never shown until every build reading
the room watches one (D11).

### D5. No contact is proposed until every device in the room reads contacts

A record carries the number in the clear, and that's as decided. But every device in
the room applies a completed signature (`applySignedEvent`), and an older build turns
a kind it doesn't know into a bubble of raw JSON (`ChatMessage.kt:2024`). A 30398
carrying a record's template is an inner event, and an older build does the same with
it. The number would sit as JSON in Bob's chat on his older build. Bob's older build
would also show the proposal as *Event of kind 30394*, and a tap could sign it unseen.

So:

- **R1 announces.** Every R1 device adds `["reads", "contacts/1"]` to a message it
  already sends in the room. Spike S2 finds one that every build already files without
  a bubble. A device keeps the newest announcement per member.
- **R2 waits for everybody.** *Add someone outside the group* is offered only when
  every current member has announced `contacts/1`. Otherwise it says *Waiting for
  @charlie to update Dedun*. The proposer checks this, and so does every signer, which
  refuses any contact kind while a current member hasn't announced.
- **A member added later on an older build** isn't shown the records already signed,
  because their chronicle skips the kind. From the moment they join, the room is
  waiting for them again.
- **R1 also gives every kind in 30394–30399 a `null` arm in `ChatMessage`**, so a
  current build never makes a bubble of one. It also gives `ProposedEvent` a summary
  for each kind (D6).

### D6. What every member sees

- **At signing**, the proposal reads *Count deposits for Mum, +27 82 123 4567, added
  by @alice*. That is the decision. A contact kind is never signed automatically.
  Like every proposal but the key state
  ([rotate-shares-single-approval.md](./rotate-shares-single-approval.md)), it waits
  for a member's tap.
- **Everywhere afterwards**, the contact is shown in full: the name, and the number
  formatted or the address. The Deposits section, the contact's page, deposit rows
  and the payout sheet all show it. The group signed it, and every member is party
  to it.
- **Transcript lines** store the contact id and render the name from the record and
  amendments at display time, so a renamed contact reads with their new name.
- **The number is permanent.** It is part of the group's signed record, and every
  member added later gets it through the chronicle. `closed` stops new deposits and
  keeps the record. The add sheet says so before the member proposes: *Everyone in
  the group, now and later, will see this number.*

### D7. The quorum decides everything about a contact

Any current member may propose any contact kind, or ask for one through a 30398. The
proposer's identity decides nothing. Every signer checks only that the proposal is
well formed and consistent with the records, and the quorum decides.

| proposal | signers check |
|---|---|
| a record (30394) | the id recomputes; the identifier normalises to itself (D2); the name is 1 to 64 characters; no record exists for that id; every current member has announced (D5) |
| an amendment (30395) | a record exists; the name rule; `open` only on a closed contact |
| an attribution (30390) naming a contact | per deposit, against the signer's own `DepositRow` as today: it exists, on that chain, for that amount. Per contact: recorded, open and unclaimed. **Whose the deposit was is not asked.** An unattributed deposit, the group's own, a member's and another contact's all go to a contact on the quorum's word. A claimed contact's deposits are given to the member instead, so the attribution can't be undone by mistake |
| a claim (30397) | a record exists; the member is a current member; the member has no other claim of the same contact (a no-op) |
| a destination (30399) | a record exists, open and unclaimed; D9's address rules |
| a depositor statement (30396) | a record exists, open and unclaimed; the branch recomputes and is nobody else's; none exists for that chain |

**Giving a member's deposit to a contact without the member asking** is the owner's
decision 3. The signing screen makes it visible: *Moves 20,000 sats deposited by
@alice to Mum*. Phase 3 also notifies the member whose deposit it is, which no proposal
does today. They can decline to sign it, and the quorum can still sign without them.

### D8. A claim: the person joins, and the group says it's them

1. Mum installs Dedun, makes a profile, and is added to the group the usual way.
2. Any member proposes *Mum is @mum* from the contact's page. Mum's own device offers
   *Deposits were made for me before I joined*. It lists the room's open, unclaimed
   contacts, and her pick becomes a 30398 for a share-holder to put to the group.
3. The quorum signs the 30397. Nothing proves Mum holds the number. The group knows
   who she is, and decides.

**After a claim**, `Contacts.resolve(id)` returns the member wherever a party is read:
the ledger's `depositsIn` and `tallies`, `CoinOwnership.Records.of`,
`MemberBalances.balancesIn` and the payout checks. Mum's tally includes everything
deposited for her before she joined, less what the group has already paid to her
contact destination. Those coins are paid to her own destination from then on. The
contact's 30399 is no longer used. The deposit rows keep the contact id. Resolution
happens on read, so a claim that arrives through the chronicle out of order still
lands.

**A claim can be corrected.** A newer 30397 names another member, or `none`. The
screen calls it a correction, and resolution follows the newest.

One member may claim several contacts, for example a number and an email address.

### D9. Paying a contact who hasn't joined

The owner's decision 2. A contact is paid as a member is, with the group's signature
standing in for the member's own:

- **The destination is a 30399**, group-signed, `d` = `<contact id>:<chain>`, newest
  wins:

  ```
  ["lightning", "mum@example.com"]        a Lightning address, optional
  ["onchain",   "bc1q…"]                  an unused address, optional
  ["removed"]                             instead of both: pay them nowhere on this chain
  ```

  At least one of the two addresses is required. A member's destination needs a
  Lightning address because every member has a device that can make one. A contact
  may only have an onchain address, so for a contact the Lightning address is
  optional too. A Lightning payout to a contact with no Lightning address is refused,
  as an onchain one to a member with no onchain address is today.
- **The address rules are group-payout-destination's D5 and D7, checked by every
  signer** rather than once by the member:
  - the onchain address parses for the chain, isn't the group's own, and is unused
    by the signer's own Electrum check. A timeout means the signer refuses, because
    unknown is not unused;
  - the Lightning address resolves when the payout is made, as a member's does, and
    with Tor on it isn't resolved (that note's D6);
  - no other party's current destination has the same onchain address (the check
    `GroupWavelengthPayouts.refuseOnchain` already makes between members).
- **One lookup for every rail.** `GroupPayoutDestinations.payoutDestinationsIn`
  returns `Map<Party, PayoutDestination>`, where `PayoutDestination` is the
  member's 30338 or the contact's 30399. Every rail already checks a payout's
  `destinationEventId` against the newest destination. A contact's is the 30399's
  id, so the check doesn't change. The payee slot in
  `GroupLightningAuthorisationEvent.Payout`, `GroupWavelengthPaymentEvent` and
  `GroupWavelengthOnchainPayoutEvent` reads `contact:<id>` as the 30390's does. An
  older build refuses such a payout, which fails closed.
- **What a contact may be paid** is what they own, by the rules a member's payout
  follows: their coins onchain (`PayoutRule`), their Lightning balance, and their
  Wavelength balance. `everSignedIn` includes every 30399, so the ledger's
  paid-out column counts a contact's payouts.
- **Screens:** the contact's page has *Where the group pays them*, which proposes a
  30399, and *Pay out*, which opens the same payout sheet a member's balance row
  opens. The sheet says *Pays Mum, a contact of @alice, at mum@example.com*.

### D10. A contact's own deposit branch and invoices

Retroactive attribution covers the cash handed to a member, and the deposit already
made. For the person who will keep saving, the group can *open deposits* for the
contact:

- **onchain:** a 30396 gives the contact a branch, `DepositBranch.of(room, contactId,
  salt)`, collision-checked against every 30339 and 30396. Any member's receive card
  gains *Deposit for someone outside the group*. It shows an address on the
  contact's branch, which the member passes on;
- **Lightning:** `InvoiceRequest` gains `contact`. The operator answers a request
  from any current member for an open, unclaimed contact with a 30396 on that chain.
  The description is *Deposit for Mum to #Council*. It carries the name and never
  the identifier, because an invoice reaches the payer and the LSP.

Once the contact is claimed or closed, no new address or invoice is issued for their
branch. Coins already on it are still counted: to the contact, or through resolution
to the member who claimed them.

### D11. Three releases, readers first

| release | phases | what it does |
|---|---|---|
| **R1, readers** | 1, 2 | every device reads and verifies all six kinds, `contact:` attributions and `contact:` payees; resolves claims; counts contacts in tallies; checks and makes payouts to contacts' destinations; watches contact branches; summarises each kind for signing; never makes a bubble of 30394–30399; and announces `contacts/1`. Signers refuse every contact proposal, and nothing proposes one |
| **R2, the group's contacts** | 3, 4, 5, 6 | proposing a contact, attributing to one, the contact's page, claiming, paying. On every chain, mainnet included (decision 4), in a room once every member has announced (D5) |
| **R3, depositing for a contact** | 7, 8 | the contact's branch and invoices |

R1 has to have been out a release before R2 is offered, as member-deposits D9 and
member-transfers T11 settled for their own kinds. D5's wait makes the order hold per
room as well as per release: in a room with one member still on an older build,
nothing is proposed. The mainnet cap (`GroupWalletRollout.MAINNET_CAP`) and the
Lightning rollout gate apply to contacts' payouts exactly as to members'.

### Spike S1: normalising a phone number in common code

1. Is there a Kotlin Multiplatform port of libphonenumber that builds for Android,
   iOS and the JVM, is maintained, and is under 1 MB of metadata? Check it against
   numbers from ZA, NG, KE, GH, UG, TZ, ZW, US and GB, each typed with and without
   `+`, with a trunk `0`, and with spaces.
2. If there isn't one: a hand-written `PhoneNumbers.normalise(typed, region)`. It
   holds a table of calling codes and trunk prefixes for the regions in the picker,
   and accepts any E.164 number typed with `+` whatever its country. It checks only
   the length, never validity per range.
3. What each target gives as the device region: `Locale.getDefault()` on Android
   and the JVM, and `NSLocale.currentLocale` on iOS.

Recommendation, before the spike: (2). The app needs one canonical spelling, not
validation, and the table is small and testable. It also has to give the same answer
on every build for as long as records are signed (D2), because the spelling is in the
id (D3). A library whose metadata updates could change a spelling between two
members' builds.

**Result, 2026-11-25: (2).** No libphonenumber port is in the version catalogue or the
Gradle cache. A new one would be a dependency fetched over the network, and its
metadata would move under signed ids. `PhoneNumbers.normalise` is hand-written. Its
table covers the countries Machankura serves, plus US, CA and GB, each with its
calling code, trunk prefix and national length. A number typed with `+` is taken
whatever its country.

### Spike S2: where the announcement rides

1. Which message does every device already send in a room often enough to announce
   with, and file without a bubble on every build since the deposit kinds shipped?
   Candidates: a FROST `NONCE` or availability signal (`FrostSigningEvents.ALL` is
   never content), or Marmot's kind 1210, which builds already drop
   (`ChatMessage`'s `KIND_SYSTEM` arm). Check that an extra tag doesn't change how an
   older build reads it.
2. If none is sent often enough: a 30398 of its own form, `reads`, sent once per
   member when R1 first opens the room, and again when a commit adds members. On
   older builds that is one bubble per member, with nothing in it but the feature
   name. That is the fallback.
3. Run the current release against an R1 build in `MarmotClientInteropJvmTest`'s
   fixture. Confirm that the older build has no row holding an identifier after an
   R1 room has announced and refused one proposal.

**Result, 2026-11-25: the fallback, (2).** Nothing every device sends routinely is
filed silently by every build:
- FROST messages are sent only inside a signing session.
- The key notice (30336) is sent only when a member asks.
- A payout destination (30338) is re-sent only by members who have one.
- Kind 1210 is Marmot v2's own system row. Other Marmot clients read it as an
  assertion about the group's state, so Dedun must not write one.
- The kinds older builds drop silently (`GizaProtocolEvents.ALL`) mean something in
  Giza and Bennu rooms.

So the announcement is a 30398 of its own form, `["request", "reads"]` with
`["reads", "contacts/1"]` and nothing else. Each R1 device sends its own the first
time it sees a room with no announcement of its own, and again for the members a
commit adds, as `GroupPayoutDestinations.resendOwn` does. An older build shows one
bubble per R1 member, with nothing in it but the feature name. Step 3 is left for
the Phase 9 run, where an older build is in the room by design.

### Spike S3: the kinds register

Write `docs/kinds.md`, which giza-into-dedun.md Phase 11 already asks for, with
30394–30399 claimed. Check that Giza's and Bennu's trees don't use them.

---

## Phase 1: identifiers, the six events, and resolution, pure

**Three days.** Dark: kinds nothing produces.

- `contacts/ContactIdentifier.kt`: `Phone` and `Email`, `normalise` (Spike S1),
  `formatted`, `contactId(room)`. Tests: every format in the S1 list comes out as
  one E.164 string, an email in mixed case comes out lowercased, nothing over 15
  digits or 254 characters is accepted, and the id is pinned with fixture values.
- `nostr/wallet/ContactRecordEvent.kt` (30394), `ContactAmendmentEvent.kt` (30395),
  `ContactDepositorStatementEvent.kt` (30396), `ContactClaimEvent.kt` (30397),
  `ContactRequestEvent.kt` (30398), `ContactPayoutDestinationEvent.kt` (30399):
  - each one's `template`, `read` and `verify`;
  - `firstPerContact` for the record and the depositor statement;
  - `newestPerContact` for the amendment and the claim;
  - `newestPerContactAndChain` for the destination;
  - `carried` for the request, as 30338's does.
- **The payee slots:** `DepositAttributionEvent`,
  `GroupLightningAuthorisationEvent.Payout`, `GroupWavelengthPaymentEvent` and
  `GroupWavelengthOnchainPayoutEvent` read `contact:<id>`. Each payee becomes a
  `Party`, and `template` writes either form. Old fixtures still read.
- `contacts/Party.kt`, and `Contacts.resolve(party, claims)`, pure.
- Tests, one mutation per check, as the siblings have:
  - an id that doesn't recompute;
  - an identifier that doesn't normalise to itself;
  - an empty name, or one of 65 characters;
  - a second record for one number;
  - an amendment that carries an identifier;
  - a destination with neither address, or with `removed` and an address;
  - a newer claim that wins, and `none`;
  - an attribution or payout with `contact:` and a malformed id;
  - and the guard on D3: no contact id can equal a fixture member key.

## Phase 2: every device reads them, and announces (R1)

**Five days.** **Ships alone, a release ahead of Phase 3** (D11). Schema 50.

- **`ChatMessage`:** a `null` arm for 30394–30399 above `unsupported()`, and
  group-signed transcript lines in `GROUP_IDENTITY_TYPES`:
  - 30394: *#Council is counting deposits for Mum, +27 82 123 4567*;
  - 30395: *renamed*, *closed* or *reopened*;
  - 30396: *opened deposits for*;
  - 30397: *Mum is @mum*;
  - 30399: *will pay Mum at …*.

  Each name is filled in at render. A test in `ProtocolKindDispatchJvmTest`: no kind
  in 30394–30399 ever yields an `unsupported` row.
- **`ProposedEvent`:** a summary per kind (D6). An attribution that moves a member's
  deposit names the member and the amount (D7). There must be no *Event of kind N*
  for any of them.
- **The announcement** (Spike S2), sent and filed in `MemberFeatureRow`
  (`chatRoomId`, `member`, `feature`, `seenAt`).
  `Contacts.everyoneReads(room)` compares it against the current participants.
- **`ContactRow`**: a cache of the record, its newest amendment and its newest
  claim, rebuilt from `GroupSignedEvent`. It holds `chatRoomId`, `contactId`,
  `proposer`, `name`, `type`, `identifier`, `closedAt` and `claimedBy`.
  `AutoMigration(49, 50)`.
- **`managers/Contacts.kt`:** `recordsIn`, `recordOf`, `claimsIn`, `resolve`,
  `everyoneReads`.
- **The ledger and balances:** `DepositLedger.depositsIn`, `tallies` and `paidOut`,
  and `MemberBalances.recordsOf`, `payoutContextOf` and `balancesIn`, resolve each
  party through `Contacts.resolve`. A `contact:` attribution naming no verified
  record is ignored, so the deposit stays unattributed (D1). `Tallies` gains
  `contacts: Map<ContactId, Tally>` beside `members`. The two maps and the
  unattributed still add up to the total, and the test that says so stays.
  `MemberBalance` gains `party` and the contact's destination.
- **Payout checks:** `GroupPayoutDestinations.payoutDestinationsIn` and
  `everSignedIn` return contacts' 30399s beside members' 30338s (D9).
  `PayoutRule`, `GroupLightningPayouts`, `GroupWavelengthPayouts.refuse` and
  `refuseOnchain` take the payee as a `Party`. An R1 signer can check and sign a
  payout to a contact from the day R2 proposes one.
- **`refuseAttribution`, `Depositors.refuse` and the new kinds' `refuse`:** every
  contact proposal is refused with *this build doesn't sign for contacts yet*. That
  line is lifted in Phase 3.
- **The wallet watches contact branches:** `GroupOnchainWallet` adds a watcher per
  30396 beside the 30339 ones. `recordOnchain`'s `byBranch` includes them, and
  `derivationOf` searches them. `GroupSpend` accepts an input on a contact's
  branch, as member-deposits Phase 2 did for member branches. Nothing produces a
  30396 yet, so this is dark. It ships now so that R3 can show an address knowing
  every R1 device sees the coin.
- **`ChronicleKinds`:** the contact kinds ahead of what refers to them:
  `[30394, 30395, 30397, 30399, 30339, 30396, 30390, 30391, 30393]`. The doc
  comment says why: a record and a claim have to be known before an attribution or
  a payout names them, so the apply-time checks pass.
- **Screens, read-only:** the Deposits section and screen list contacts under the
  members: *Mum*, the number, *added by @alice*, and the tally. A deposit row says
  *For Mum*.
- Tests (`ContactsJvmTest`, `DepositLedgerJvmTest`, `MemberBalancesJvmTest`,
  `PayoutRuleTest`):
  - a hand-made R2 record and attribution are counted to the contact, and a claim
    moves them to the member;
  - an attribution to a contact with no record is ignored;
  - a rename shows in old transcript lines;
  - a late joiner's chronicle carries every contact kind, and their tallies match;
  - a payout to a contact's newest 30399 passes, and one to a replaced 30399 is
    refused;
  - a contact's payouts are in the paid-out column;
  - a coin on a contact's branch is the contact's;
  - `everyoneReads` is false while one participant hasn't announced, and goes false
    again when a member without an announcement is added.

## Phase 3: proposing a contact, and attributing deposits to one (R2)

**Four days.**

- **`Contacts.propose(room, proposer, identifier, name)`:** refuses unless
  `everyoneReads`, or if the id already has a record. On a share-holding device it
  proposes the 30394 straight away. Otherwise it signs a 30398 for a share-holder
  to put to the group: *Put @alice's request to the group* (T10).
- **`Contacts.amend(room, contact, name | closed | open)`**, in the same way.
- **`DepositLedger.proposeAttribution`** takes `Party`. A member without a share
  signs a 30398, and a share-holder proposes it.
- **Signers' checks:** D7. The Phase 2 refusals are lifted.
- **The signing screen** shows the 30394 summary with the full number, and *Decline*
  beside *Sign*, as for any proposal. A proposal moving a member's deposit names
  them, and notifies them (D7).
- **`DepositsScreen`'s `AttributeDialog`** gains a divider and two entries below the
  depositors and *The group's own*:
  - the room's open contacts, by name, with the number or address as supporting
    text;
  - *Someone outside the group…*, which opens the add-contact sheet.

  It's offered on every deposit, a member's included (D7). A deposit can only be
  attributed to a contact once that contact's record is signed. Adding a contact is
  its own round, and the sheet ends with *Asked the group to add Mum. You can
  attribute to her once they agree.*
- **`AddContactSheet`:**
  - a segmented button, *Phone number* or *Email address*. The field gets the
    matching keyboard, with the country picker for a phone number;
  - *Name* (*What the group will call them*);
  - a read-back of the normalised value, and D6's notice that everyone will see it;
  - *Ask the group*.

  If the number already has a record, the sheet says *The group already counts this
  number as Gogo* and offers *Attribute to Gogo*, or *Reopen* for a closed contact.
  While D5 isn't met, the sheet opens on *Waiting for @charlie to update Dedun* and
  can't be submitted.
- **Follows CLAUDE.md:** every string in the catalogue in sentence case, spacing
  from the scale, four screen states, 48dp targets, and outcomes through the
  notifier.
- Tests (`ContactProposalJvmTest`, `ContactAttributionJvmTest`,
  `DepositsScreenJvmTest`):
  - a record proposed while one member hasn't announced is refused by every
    signer;
  - a share-holder adds a contact, the quorum signs, and an unattributed deposit
    is then attributed to them and counted on every device;
  - an attribution proposed before the record is signed is refused;
  - a member's deposit is moved to a contact by a quorum without the member;
  - the same number proposed twice gets one record, and the second proposal is
    refused;
  - a closed contact takes no new attribution until it's reopened;
  - the sheet's states at phone width and at 1280dp.

## Phase 4: the contact's page

**Two days.**

- **`ContactScreen`** (pushed, with `NavigateBackButton`) shows:
  - the name, the number or address, and *Added by @alice*;
  - the tally by rail, paid out, and the balance;
  - every deposit;
  - where the group pays them, per chain, or *Nowhere yet*.
- **Actions, for any member:** *Rename* and *Stop counting deposits for them* (or
  *Reopen*), each proposing a 30395. The close dialog says *Their deposits so far
  stay theirs, and the group can still pay them out.*
- **Tap targets:** the number and the address are selectable and copyable, and
  nothing on the page dials or mails (decision 5).
- Tests: the page's four states; a renamed contact shows the new name everywhere; a
  closed contact's page offers no attribution and still offers *Pay out*.

## Phase 5: claiming

**Two days.**

- **On the contact's page:** *They've joined the group* opens a picker of current
  members, and proposes the 30397.
- **On the member's own device**, for the first week after they joined: *Deposits
  were made for me before I joined*. It lists the open, unclaimed contacts, and the
  member's pick becomes a 30398.
- **A claimed contact's page** offers *Not them: correct*, which proposes a newer
  30397 naming another member or `none`.
- **After the claim:** the contact's page becomes a line on the member's, *Includes
  deposits made for them before they joined, as Mum*. The Deposits screen shows
  those rows as *For @mum, before they joined*.
- **Signers' checks:** D7.
- Tests (`ContactClaimJvmTest`):
  - a claim moves the tally, the coins and the balance, and the member is then paid
    to their own destination, not the contact's;
  - a correction moves them back, and `none` returns them to the contact;
  - one member claims two contacts;
  - a late joiner's chronicle resolves the claim before the attributions.

## Phase 6: paying a contact

**Four days.**

- **`Contacts.proposeDestination(room, contact, chain, lightning?, onchain?)`:** the
  proposer's device makes group-payout-destination's checks first (the onchain
  address unused by Electrum, not the group's own, the right network; the Lightning
  address well formed). Then it proposes the 30399. *Remove* proposes `removed`.
- **Signers' checks:** D9's address rules, by each signer's own Electrum.
- **The contact's page:** *Where the group pays them*, a sheet with both fields,
  each optional, and at least one required. Beside each balance, *Pay out* opens the
  payout sheet every rail already has, with the contact as payee.
- **The payout sheets** (onchain, Lightning, Wavelength) take a `Party`. They show
  the contact's name and number beside the destination, so a signer sees who is
  being paid.
- Tests (`ContactPayoutsJvmTest`, and the regtest run):
  - an onchain payout of a contact's coins to their 30399 address is signed, and is
    counted as paid out;
  - a payout of another party's coin to a contact is refused by `PayoutRule`;
  - a Lightning payout to a contact with no Lightning address is refused;
  - a used or the group's own onchain address is refused by a signer;
  - a payout naming a replaced 30399 is refused;
  - a Wavelength payout of a contact's balance;
  - after a claim, a payout to the contact's 30399 is refused.

## Phase 7: signers accept a contact's depositor statement (R3, dark)

**Two days.**

- **`Contacts.openDeposits(room, contact, chain)`:** proposes a 30396, with the salt
  picked against every 30339 and 30396 branch.
- **Signers:** D7. `Depositors.refuse` also checks a new 30339 against contacts'
  branches.
- Tests: a collision with a member's branch moves to salt 1; a second 30396 is
  refused, and so is one for a claimed or closed contact.

## Phase 8: depositing for a contact (R3)

**Three days.**

- **Receive card:** *Deposit for someone outside the group* lists the open contacts
  with open deposits, and offers *Open deposits for…* to the rest. It shows the
  lowest unused address on the contact's branch, with a QR code and copy, as Phase
  5 of member-deposits shows a member's.
- **`GroupWalletAddressesScreen`:** each contact's branch is listed under its name,
  used rows only and copy only, as other members' branches are.
- **Lightning:**
  - `InvoiceRequest.contact` and `GroupLightningInvoiceRow.contact`, both nullable;
  - the operator's check in `answerInvoiceRequests` (D10);
  - `recordLightning` counts a held invoice with a contact to the contact, by
    invoice.
- **After a claim or a close**, the contact's address and invoice entries are gone.
- Tests:
  - an address on the contact's branch is counted to them by branch;
  - an invoice request for a closed contact is unanswered;
  - the description holds the name and not the identifier;
  - no address is offered after a claim or a close.

## Phase 9: docs, and checking it by hand

**Two days.**

- This note's *Built* line, and a table of where the build chose differently.
  Rows in [README.md](./README.md) and `docs/kinds.md`. A paragraph in
  member-deposits.md's *What this does not do*, which today says *Deposit for
  @bob* is a follow-up: it points here. A line in group-payout-destination.md and
  payouts-only.md: a contact's destination is group-signed.
- Run the checks below.
- **Four emulators against regtest:** Alice, Bob and Charlie hold shares, and
  Medium_Phone is *Mum*, who isn't in the group:
  1. Charlie's device runs the previous release. Alice's *Someone outside the
     group…* says it is waiting for Charlie. Charlie updates, and the sheet opens.
  2. Alice receives 50,000 sats on her branch. Bob proposes Mum by phone number;
     Charlie's signing screen shows the name and the full number, and he signs.
     Bob then proposes moving 20,000 of Alice's deposit to Mum. Alice is notified,
     and Bob and Charlie sign without her. Every device shows Mum's tally at 20,000
     and Alice's at 30,000.
  3. Alice tries to add the same number as *Gogo*. She's told the group already
     counts it as Mum.
  4. A legacy branch-0 deposit is attributed to Mum, and the quorum signs.
  5. The group opens deposits for Mum, and pays the shown address. It is counted to
     Mum by branch.
  6. The group sets a destination for Mum, an onchain address, and pays her 10,000
     sats of her coins. Every device shows it as paid out of her tally.
  7. Mum installs Dedun and is added to the group. Her chronicle shows the contact,
     its tally and its payout. Bob proposes *Mum is @mum* and Charlie signs. Every
     device counts the rest to @mum.
  8. Mum sets her own destination, and the group pays her out. A payout to the
     contact's old address is refused.
  9. The group adds a second contact by email, then closes it. Every device offers
     no attribution, and keeps the contact's tally and *Pay out*.

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

### What the checks found, 2026-11-26

| check | result |
|---|---|
| `./gradlew :composeApp:jvmTest`, on each release's last phase (R1, R2, R3) | 3,085 tests on R3, none failing; the 7 skipped are the regtest runs, which need `DEDUN_REGTEST=1` |
| `DEDUN_REGTEST=1 … --tests '*GroupLightningRegtestJvmTest'` against `testing/regtest/regtest.sh up` | passes, run after Phase 8 changed how invoice requests are answered |
| `./gradlew :composeApp:m3Audit` | every budget met, after each phase from 3 on |
| `./gradlew :composeApp:compileDebugKotlinAndroid` | compiles, after each phase |
| the iOS target | not compiled: this was a Linux host. `DeviceRegion.ios.kt` is the one iOS file, and it is unchecked |
| the four-emulator run, steps 1-9 above | **run, every step passes, with the gaps below.** On a local regtest build, described below |

### The four-emulator run, 2026-11-26

The app is built for mainnet, so the run used a debug build with three local-only changes,
reverted afterwards and never committed: `NodeParamsManager.chain` set to regtest, a regtest
entry in `LSP.from` pointing at the `testing/regtest` LSP, and the payout destination screen
skipping its Lightning address lookup on regtest, as it already does under Tor, since
example.com answers no LNURL. Electrum was `testing/regtest`'s electrs, reached at the
emulators' `10.0.2.2:51002` through a forward to 28001. Each device made a fresh regtest
profile; the mainnet profiles and their data are untouched.

The group was *Stokvel*, 2 of 3, with Alice, Bob and Charlie holding shares. Medium_Phone was
Mum. Charlie ran `1a111849` with the same changes until step 1 had him update.

| step | result |
|---|---|
| 1 | Alice's *Someone outside the group…* said *Waiting for @bob, @charlie*. **Bob was on this build too**: see the first gap below. Once Bob and Charlie had each opened the sheet, it opened on every device |
| 2 | Charlie's signing screen read *Count deposits for Mum, +27 82 123 4567*, and he signed. Moving a coin of Alice's to Mum read *This gives away deposits made by @alice. The group can do this without them.*, and Bob and Charlie signed it without her. Every device showed *Mum put in 20,000 sats* and *@alice put in 50,000 sats*. A deposit moves whole, so Alice was sent a second, 20,000-sat coin for this rather than splitting her 50,000 |
| 3 | *0821234567* with ZA picked read *The group will see +27 82 123 4567* and *The group already counts this as Mum.*, and offered *Attribute to Mum* in place of a new contact |
| 4 | 15,000 sats to branch 0 showed as unattributed, was attributed to Mum, and the quorum signed |
| 5 | *Open deposits for them* was signed, the page showed the branch's address and QR code, and 40,000 sats paid there showed as *From Mum · by their address* on every device. The page then showed the branch's next address |
| 6 | Mum's destination, onchain only, was signed. A payout pays everything a person holds, not a chosen amount, so the group paid Mum 74,572 sats of her 75,000, less 428 sats of fee. It reached her address, and every device showed *Paid to them: 74,572 sats* and a balance of 0 |
| 7 | Mum was invited; the chronicle carried the contact, its branch, its destination and both attributions. Bob proposed *Mum is @mum*, Charlie signed, and Alice's, Bob's and Charlie's devices showed *@mum put in 75,000 sats · Paid to them: 74,572 sats*. **Mum's own device showed nothing**: see the last gap |
| 8 | Mum set her own destination. 30,000 sats paid to the contact's branch counted to @mum, and the group paid @mum 29,802 sats at her new address. Payouts offered only her own destination; the contact's old one was gone. A proposal to the old address can't be made from the app, and `ContactPayoutsJvmTest` covers its refusal |
| 9 | *Gogo*, by email (*Gogo@Example.COM* read *The group will see gogo@example.com*), was added, given a deposit and a destination, and closed. On every signer the attribute dialog no longer offered Gogo, and Gogo's page kept *Gogo put in 10,000 sats*, *The group no longer counts new deposits for them.*, *Pay out* and *Reopen Gogo* |

What it found, in this feature:

1. **A device doesn't announce that it reads contacts when it joins or updates.** `Contacts.announceIfNeeded` runs from `MarmotInboundManager.processGroupMembershipChanges`, on a commit, and from `contactsWaitingFor`, when the member opens the sheet. A member who joined from a Welcome with no commit since, or who just updated, never announces until they open *Someone outside the group…* themselves, so D5 holds every proposal for a member who never does. The fix is to announce after a Welcome is processed and once for each room at start.
2. The sheet reads who it waits for once, when the Deposits screen loads, and doesn't see an announcement that arrives while it is open.
3. The group wallet's *Coins* list still said *From @alice* for the coin the group had attributed to Mum, while every Deposits screen counted it as Mum's.
4. On the Payouts screen a contact with only an onchain destination reads *Hasn't said where to be paid* directly above that address: `GroupPayoutsScreen.kt:415` shows the Lightning address or that line.
5. A spend's signing screen and its transcript line name a contact by its id, *Pay out @4a6e9c71* and *The group signed a payout of 74572 sats to @4a6e9c71*, where everything else says *Mum*. The *Assign coins* screen for a legacy coin offers members only; the Deposits screen's *Attribute* offers contacts.
6. With the device's country on US, *0821234567* was accepted as *+1 082 123 4567*. No US area code starts with 0 or 1.

And two that are older than this feature, but shaped what a contact's run looks like:

- A signer drops an attribution of a coin its own wallet hasn't recorded yet, which happens until the member opens the group wallet. Step 2's first proposal was dropped this way by Alice and Charlie, and went through on *Ask again*.
- A member without a key share drops every signing proposal, so their device stores nothing the group signs after they join: Mum's never saw the claim. And the group wallet's statement (30331) isn't a chronicled kind, so a late joiner without a share is shown no wallet at all, and no deposits or tallies.

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 0 | this note, three spikes, `docs/kinds.md` | nothing |
| 1 | identifiers, six kinds, `contact:` payees, pure | dark |
| 2 | reading, resolving, payout checks, watching, chronicling, the announcement, the signing summaries; schema 50 | **R1, alone, a release ahead of 3** (D11) |
| 3 | proposing a contact and attributing to one | **R2**, every chain, in rooms where everyone has announced |
| 4 | the contact's page, rename, close | R2 |
| 5 | claiming | R2 |
| 6 | the contact's destination and payouts | R2: a contact nobody can pay or claim is a balance nobody can reach, so 3 doesn't ship without 5 and 6 |
| 7 | signers accept 30396 | R3, dark |
| 8 | addresses and invoices for a contact | R3 |
| 9 | docs and the emulator run | with R3 |

About six weeks for Phases 0–9, two of them before anybody can propose a contact.
**Phases 3, 4, 5 and 6 are one release.**

---

## What this does not do

**It doesn't let anyone forget a number.** The group signs the contact whole, so the
number is in the group's permanent record and reaches every member added later.
Closing a contact stops new deposits, and nothing more.

**It doesn't check or contact the person.** Nothing is sent by SMS or email, and
nothing checks that the person holds the number or address (decision 5). The
identifier is the proposer's word, accepted by the quorum, and so is a claim.

**It doesn't issue Wavelength deposit addresses to contacts.** A Wavelength deposit
address is issued to a depositor with a 30339 (`GroupWavelengthDeposits.kt:72`).
Attributing a Wavelength deposit to a contact afterwards, by 30390, works, and so
does paying their Wavelength balance out.

**It doesn't make transfers to contacts.** A transfer moves a balance and never a
deposit (member-transfers T7). *This was Mum's money* is an attribution, because the
tally should say who put it in. Giving Mum part of your balance is a different
statement, and a different plan.

**It doesn't read the address book.** The member types the number. A contacts picker
needs a runtime permission on Android and iOS, and has no counterpart on the desktop.

**It doesn't merge contacts.** A number and an email address for one person are two
contacts until one member claims both.

## Appendix: what was considered and rejected

| idea | why not |
|---|---|
| a group-signed commitment to the number, with the number itself in an encrypted member-signed card | the group would sign a hash it can't read. What the quorum agrees to has to be the person (owner's decision 1) |
| no record: the 30390 names the contact by number directly | every attribution would be the group's first look at who the person is, and two proposers could spell one person two ways |
| proposing a record without waiting for every device (D5) | an older build makes a bubble of the signed record, and can be tapped into signing it unseen |
| a proposer-chosen random contact id | it could be chosen to equal a member's key, and it would let one number have two contacts (D3) |
| an amendment that changes the identifier | the group agreed to that number. Another number is another contact, or the contact could be redirected after the fact (D2) |
| only the member themselves may give their deposit to a contact | the quorum is supreme (owner's decision 3). The signing screen names the member and notifies them instead |
| a claim proved by a code sent to the number by SMS or email | nothing is sent (owner's decision 5), and the quorum decides who a member is (D8) |
| holding a contact's balance until they join | the group pays them where it signed (owner's decision 2) |
| a nostr key the sponsor makes for the contact, handed over later | the sponsor holds the secret until then, so the contact's money is the sponsor's. Handing a key over is handing over a credential |
| one 30339 with a contact form | it embeds a destination a contact can't sign, and an older build would silently not watch the branch (D4) |
| the contact's destination in the record or an amendment | a destination is per chain and changes. A kind of its own is newest-wins per chain, as the member's 30338 is |
| a contact per chain | a person isn't per network. Only the branch and the destination are |
| attributing by the payer's name in the invoice description | free text anybody can type, as member-deposits rejected for members |
