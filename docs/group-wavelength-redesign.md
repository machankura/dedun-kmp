# The group's Wavelength wallet, read against the Bitcoin Design Guide

What ten phases of screen work would change if the [Bitcoin Design Guide](https://bitcoin.design/guide/)
were the measure, rather than Material Design 3 — which
[material-design-conformance.md](./material-design-conformance.md) already settled for all 43 screens, and
which this plan does not revisit.

**Planned on 8 November 2026 and built on the same day, in the order 1-5, 7, 8, 10, 9, then the store.**
Nine of the ten phases are complete. **Phase 6's notification is not** — see *What Phase 6 left, and what
looking properly found*, where the first reason given for that turned out to be wrong twice. It is the
one piece still waiting on a decision, and the decision is now a choice between three sizes rather than
a question about whether it can be done at all. Phases 3 and 10 were each completed afterwards by the
per-group-wallet store they had both been blocked on, which answered questions 2 and 6 (see *The store,
afterwards*). Written against `9d22a7c0`,
and every file and line named below was as it stood at that commit, except where a built phase says what
the build found. It takes the seven screens listed under *What exists* as they are,
and changes nothing a device signs: no `ArkPolicy` rule, no refusal, no event's wire shape. The wallet
itself is [group-wavelength-wallet.md](./group-wavelength-wallet.md), its screens are
[group-wavelength-ui.md](./group-wavelength-ui.md), and that note's decisions (U1 to U10) and the wallet
note's (D1 to D16) still hold. Where a guide rule and one of those decisions disagree, the decision wins
and this plan says why — that happens four times, and each is marked.

Read these first:

- [group-wavelength-ui.md](./group-wavelength-ui.md): the screens, and why the balance is a device's own
  ledger rather than the operator's figure. Its "Where the build stands" table is the ground this plan
  stands on, including the parts marked *not run*.
- [group-wavelength-transaction-details.md](./group-wavelength-transaction-details.md): the history and
  the entry screen. Its T9 (*nothing on this screen does anything*) and its "What this plan does not do"
  list are deliberately narrow, and Phases 7 and 8 below widen two of them.
- [material-design-conformance.md](./material-design-conformance.md) and [../CLAUDE.md](../CLAUDE.md):
  the budgets every phase here has to keep. Each phase ends green on
  `./gradlew :composeApp:m3Audit`, and none of them raises a budget.

## What the guide is, and what it is not

The guide has two kinds of page, and they carry different weight here.

**The principles chapter is normative and applies in full.** Seven principles — self-custody, security,
inclusion, interoperability, transparency, privacy, decentralization — each with a Do and a Don't list,
and the guide's own instruction that diverging from them needs very good reason. This wallet already
satisfies self-custody, decentralization and transparency unusually well, and the gaps are concentrated
in inclusion, privacy and interoperability. That is the finding this plan is built on.

**The reference designs are worked examples, and most of what they assume is false here.** The daily
spending wallet is one person, one key, an automatic cloud backup, an LSP that holds payments while the
phone is off, and a unified BIP 321 request that hides the choice of rail. This wallet is four devices
holding one Iceberg key, no backup in the guide's sense at all, an elected member's phone in place of the
LSP, and two rails whose trust models genuinely differ. Taking the daily spending design's screens as a
target would be taking its threat model with them.

The guide's own closest fits are two pages this plan leans on instead:

- [shared-wallet](https://bitcoin.design/guide/shared-wallet/), whose onboarding sequence — a cover
  screen, an informational screen about the scheme, then key creation, then an explicit completion — is
  the shape `Commitment` is already most of the way towards (Phase 10).
- [multi-key](https://bitcoin.design/guide/how-it-works/private-key-management/multi-key/), which states
  that multi-key cannot control a lightning node's keys, and names shared control by several people as the
  scheme's second use. It is describing the problem the Iceberg key solves, and its *Cons* — significant
  op-sec burden, every key needing its own backup — are this wallet's known risks written in the guide's
  words.

Two places where the guide contradicts itself, so neither can be cited as settled: 1-of-2 multi-key is
non-recommended on one page and adequate for spouses on two others, and showing a balance on the home
screen is a *don't* on the requesting page while the privacy page's own home example shows one. Where the
guide is divided this plan picks a side and says so.

One guide proposal is explicitly **not adopted**: the ₿-only format (integers only, a `₿` prefix,
deprecating the word *sats*). The guide says of it that there is no clear consensus, and this app's
amounts are read by a signer comparing a fee to an output. Changing the unit is Phase 2's subject and the
answer there is to add a second line, not to renumber the first.

## What exists

Seven screens and one row, as of `9d22a7c0`:

| screen | file | lines | states |
|---|---|---|---|
| the row into it | `ChatRoomDetailScreen.kt:1399` `WavelengthWalletRow` | 1 per network | has an onchain wallet, doesn't, test network |
| the wallet | `GroupWavelengthWalletScreen.kt` | 799 | 8: error, loading, needs an onchain wallet, needs a key, not opened, waiting for the seed, refused, opened |
| add to it | `GroupWavelengthReceiveScreen.kt` | 288 | 6, then two rails behind a chip pair |
| pay out of it | `GroupPayoutsScreen.kt` | 566 | shared with the onchain and Lightning wallets |
| one transaction | `GroupWavelengthEntryScreen.kt` | 264 | found, not found, loading, error |
| its technical fields | `GroupWavelengthEntryTechnicalSheet.kt` | 312 | one sheet per kind |
| advanced | `GroupWavelengthAdvancedScreen.kt` | 197 | coins, terms, three untested warnings, a link to the exit |
| leaving alone | `GroupWavelengthExitScreen.kt` | 136 | a plan, no plan, stages under way |

351 strings in `strings.xml` carry `wavelength` in their name. `Sats.kt` is the only amount formatter:
sats, grouped in threes by comma, with a documented reason for refusing bitcoin — a decimal point eight
places in is the easiest digit on the screen to misread.

## What the guide finds, in order of what it costs a member

Each finding names the principle or page it comes from, and the site it applies to.

### 1. There is no local currency anywhere in the app, and the rate source is already here

**Units & symbols:** *the option to select the local currency should also be readily available*.
**Transparency:** a claim a member cannot check is a claim the guide tells you not to make, and
*100,000 sats* is not a sum most members can value. The mainnet cap
(`GroupWavelengthRollout.capFor`) is a decision about how much the group may risk, stated in a unit that
does not answer the question *how much is that*.

`SovereignWalletViewModel.kt:126` already holds `phoenixGlobal.currencyManager.ratesFlow`, `stateIn`'d
with an empty initial value, **and nothing reads it.** The rate source exists, is maintained by Phoenix,
and has never reached a screen. This is the largest guide gap in the wallet and the cheapest one to
close.

### 2. A balance that cannot be hidden, in a screen reached from a chat room

**Privacy chapter:** a quick toggle within close reach of the balance, hold-to-reveal to stop accidental
reveals, and an optional PIN before unhiding. Today there is no masking of any kind: `BalanceCard`
(`GroupWavelengthWalletScreen.kt:594`) renders `headlineMedium` sats unconditionally, and so do the four
numbers under it. A group wallet is the case where this matters most — the screen is two taps from a room
other people in the room can ask to see.

### 3. The balance card is five numbers and a provenance label where the guide wants one number

`BalanceCard` leads with `group_wavelength_balance_label`, *What your device has signed for*, and then
shows, in order: the settled figure, pending, held, the operator's figure, whether the two agree, and
credit. Six numbers and three qualifications, all at once.

Every one of them is there for a reason this plan does not dispute — U2 is why the device's figure comes
first, and the agreement line is the guide's transparency principle done better than most wallets manage.
The finding is about **hierarchy, not content**: the guide's review pattern is a compact summary with the
things to be aware of highlighted and the rest a tap away.

### 4. Receiving makes the member choose a rail, which the guide names as a don't

**Requesting:** *Avoid users having to choose between requesting with lightning or on-chain* — stated as
a don't, with a unified BIP 321 request as the answer. `Rails`
(`GroupWavelengthReceiveScreen.kt:158`) opens on two `FilterChip`s of equal weight, *Onchain* and
*Lightning*, with onchain merely the default.

**A unified request is unsafe here and this plan refuses it.** The two rails do not differ in speed and
fee, as they do in the guide's design; they differ in who has to be trusted. An onchain address is one
the member's own device verified the group's key makes
(`group_wavelength_receive_checked`). A Lightning invoice is the operator's word and the member's device
cannot confirm where it pays (`group_wavelength_invoice_warning`). Putting both behind one QR code would
hide exactly the thing a member needs to decide.

What the finding does reach is the **presentation**: a peer chip pair says the two are equivalent
choices, and they are not. Phase 4 makes the verifiable rail the screen and the other a disclosed route
off it.

### 5. The requesting screen leaks the balance

**Requesting**, again, and as a don't: *Showing a wallet balance on the requesting screen affects the
user's privacy*. `GroupWavelengthReceiveScreen.kt:208` shows `group_wavelength_receive_room_s` — *The
wallet can take %1$s sats more on mainnet*. The cap is a build constant every member can read on the
wallet screen, so room-remaining is the balance, arithmetic away, on the one screen a member holds up to
somebody else to be paid.

### 6. The vocabulary is a protocol's, not a member's

**Inclusion**, whose Don't list is *surface technical detail the user does not need*, and whose named
pattern is *educate in place*. Counted over what a member actually reads — the **values** in
`strings.xml`, not the keys, which is the measurement that matters and the one that makes this finding
much smaller than a glance at the key names suggests:

| word | strings whose text says it | what it is |
|---|---|---|
| `Ark operator` | 8 | the Ark server, said correctly. Three more say it without the *Ark*, and see below |
| `signer` | 3 here, 10 app-wide | a member's device. The guide uses *signer* for a hardware wallet, so a member who reads both meets one word for two things. The other 7 are the key ceremony's, the group Lightning wallet's and the guarded wallets', where it names a third thing |
| `a round` | 9, plus 1 *rounds* | Ark's batch. A member meets it in *joins the wallet in a round* before anything has defined it |
| `daemon` | 4 | a server-administration word, in a wallet |
| `VTXO` | 2 | on the advanced screen and the technical sheet, where the guide would allow it |
| `boarding` | 0 | Ark's word for a coin joining. **Never reaches a member** — already solved |

**The thirteen `group_wavelength_daemon_*` keys are not thirteen jargon strings.** Their text mostly says
the right thing already — *Running on this device.*, *Opening the wallet on this device…*, *Paused while
your own wallet is open.* Four strings say *daemon*, and that is the whole of it.

The real ambiguity is subtler than a jargon count and worth more than one: the **Ark server** is
*Ark operator* in 8 strings, and the **elected member** is *whoever runs the wallet* in 17 — but a bare
*operator* is used for **both**, 7 times. Four of those mean the member
(`group_wavelength_operator_says_s`, `group_wavelength_no_report`, `group_wavelength_only_operator_s`,
`group_wavelength_only_device_s`) and three mean the server, all three on the payouts screen
(`wavelength_onchain_payout_wallet_too_small`, `wavelength_payout_holds_back_s_s_s`,
`wavelength_payout_all_held_back_s_s`). So two phrases one word apart name two different parties, one of
the four is the balance card's second figure, and three of the seven are on the screen that moves money.
A member who reads *The operator says 120,000 sats* on the wallet screen, *the Wavelength wallet's
operator won't take a spend* on the payouts screen and *Ark operator 03a4…* under Advanced has no way to
know that the first is a person and the other two are a server.

The app is otherwise generous with in-place explanation — `group_wavelength_operator_explainer`,
`group_wavelength_commitment_devices` and the receive screen's four `Line`s are the guide's *educate in
place* done well, and better than the guide's own examples. **So this is not a case for more text,** and
it is a smaller case than it first looks: four strings to drop a word from, seven to disambiguate, three
to rename, and one place for the terms that stay.

### 7. Errors stop at the diagnosis

**Sending**, which specifies error copy in four parts: what went wrong, the status of the funds, how it
can be fixed or avoided, and what to do next. **Common user flows**, which asks that errors be
communicated as clearly as possible.

Today: all four `ErrorState` calls on these screens pass `onRetry = null` — the wallet screen (`:293`),
the entry screen (`:139`), the advanced screen (`:117`) and the receive screen (`:149`) — and
`ScreenState.kt:60` is explicit that passing null is meant to be a decision rather than a habit. On these
four it is a habit: each one is a read that could be retried. `AlarmCard` (`:580`) prints the device's
refusal strings with no action beside any of them, and U4 makes that card the one thing a member is ever
told about what their device signs. `DaemonStatusLine` (`:772`) covers ten states, one of which
(`Refused`) carries seven reasons of its own, and of all seventeen outcomes *Couldn't start the wallet on
this device. Trying again shortly* is as close as any comes to a next step.

A second finding sits inside the first, and it is one the house rules already name.
`GroupWavelengthWalletManager.Refusal` has **ten** values and the screen maps them onto **six** messages
(`:331` to `:339`) — `UNREADABLE`, `NO_GROUP_KEY`, `NO_ONCHAIN_WALLET` and `WAITING_FOR_SEED` all fall
through to `group_wavelength_refused_other`. `GroupWavelengthDaemon.Refusal` has seven mapped onto six,
with `NOT_OPERATOR_OR_BEHIND` and `NOBODY_RUNS_IT` sharing *Waiting to catch up with the group*. That is
exactly the thing [../CLAUDE.md](../CLAUDE.md) warns about where it says one shared default is how five
different absences came to say *No events were found* — applied here to refusals rather than empties.

Several of these genuinely have no retry — a wallet the group signed that this device refuses is not
something to try again, and the refusal is the point. The finding is that **none** of them says what to
do, including the ones where something can be done: nominate another member, open the app on the other
devices, wait for a confirmation.

### 8. No search, no filter, no export, over money several people share

**Activity:** search, filter and export matter for migration and for accounting or tax. A group pooling
money has the strongest version of that need and the history has none of the three. It is a flat
`LazyColumn` of this device's entries, then the operator's under a sub-heading
(`GroupWavelengthWalletScreen.kt:482`, `historyItems`).

The transaction-details plan's own risk list already names the renewal entries as the rows that will
outnumber everything else on a wallet that renews weekly. Grouping them is the guide's *smart grouping*,
and the question that plan left open for the owner.

### 9. The QR code reads its whole payload aloud

**Accessibility:** correctly labelling interactive elements is called one of the essential practices, and
the guide's own worked example is a QR code, whose recommended label is of the form *QR code to copy the
receive address*. `QRCodeView.kt:109` sets `contentDescription = data` — so TalkBack and VoiceOver read
out a bech32 address or a BOLT 11 invoice, character by character, to the one member who cannot see the
code.

Two more from the same chapter and from **Requesting**: raise screen brightness automatically while a QR
is shown, and uppercase bech32 before encoding, which the guide notes gives a simpler code. Neither is
done. Three things the app already does right and this plan does not touch: `+`/`−` beside amounts rather
than colour alone (`wavelength_entry_plus_s_sats`), red reserved for errors, and the 48dp target and
`Decorative` rules the M3 audit holds at zero.

### 10. A weekly obligation with no reminder

**Security**, whose named patterns are proactive support, a security checklist that abstracts away
detailed settings, and reminder etiquette — *Do not alarm the user, but be helpful*, nothing during
sensitive activity, more discreet when the risk is lower.

This wallet's obligations are harder than the guide's example wallet's: coins last about a week,
`2t-1` devices have to answer for a round, the operator's phone has to stay on and connected, and a
member who opens their own Wavelength wallet pauses the group's. Every one of those is stated somewhere
on the wallet screen. **All of it is stated only to a member who opens the wallet screen.**
`DeadlineLine` (`:666`) turns red at `WalletExpiry.Urgent` and nothing carries that off the screen.
`WavelengthMaintenanceWorker` exists for the personal wallet and has no notification in it;
`GroupWavelengthService` is the only file in the app that builds one.

### 11. The way out is two taps behind *Advanced*, under a warning that it has never run

**Usage life cycle**, the Interested phase, lists *Can I get out if something goes wrong?* among the four
questions a self-custodial product has to answer before a user commits. **Interoperability** opens with
not locking users in.

This wallet's answer is honest and buried: `GroupWavelengthAdvancedScreen.kt:169` offers the exit screen,
under `group_wavelength_advanced_untested_exit`, and the exit screen's first line is that it has never
been run. Both of those are the transparency principle working. The finding is the **location** —
discoverable only to a member who has already decided to look at a screen called *Advanced*.

### 12. The commitment screen ends in a button, and records nothing

**First use:** the responsibility step must tell users the team cannot recover their funds, *these
caveats mustn't be hidden*, and the guide's own worked screen has two options the user picks between to
confirm they understood. **Shared wallet** ends its sequence by clearly confirming the wallet was
created.

`Commitment` (`GroupWavelengthWalletScreen.kt:350`) is the best screen in this wallet and close to what
the guide asks for: six plain-language sentences about what the group is agreeing to — devices needed,
whose phone, fees, that the scheme has had no outside review, the cap, and that the key share is stored
unencrypted — said before the button, which D11 required. What it lacks is the guide's last step: nothing
distinguishes a member who read it from a member who tapped past it, and the member who taps *Open a
Wavelength wallet* is proposing on behalf of three others who will each see this screen only if they
happen to open it before the proposal is signed.

### What the guide would not change

Worth stating, because a plan that only lists faults misreads the wallet:

- **Self-custody and decentralization** are met without qualification. No custody, no third party able to
  freeze anything, no single point of failure the guide would recognise — the operator is elected, replaceable
  at any time, and holds no key that can move money (`group_wavelength_operator_explainer`).
- **Transparency** is met better than the guide's own examples. Every fee is stated before it is incurred:
  the round's cap on the receive screen, the budget on the advanced screen, the per-entry fee in the
  history. `group_wavelength_commitment_unreviewed` tells a group in one sentence that the scheme has had
  no outside review, which is the Don't in *make claims that are not explained or verifiable* taken
  seriously enough to be uncomfortable.
- **Sats as the unit** is what the guide recommends by default for a wallet of this size, so Phase 2 adds
  to it rather than replacing it.
- **The device's own ledger before the operator's figure** (U2) is the guide's transparency principle
  applied to a number, and `GroupWavelengthReports.Agreement`'s three outcomes are more honest than
  anything in the guide's reference designs.
- **No actions on the entry detail** (T9) stays. The guide's activity page wants metadata, which Phase 8
  adds; it does not ask for retry buttons, and the reasoning in T9 — that a failure is reported on the
  operator's word, so an action offered on it would be too — is sound.
- **Payouts only.** The guide's *Sending bitcoin* flow does not apply: there is no arbitrary send to
  design, by [payouts-only.md](./payouts-only.md). Its naming rule still reaches the labels, which is B3
  below.

## Decisions

**B1. Sats stay the unit, and a local-currency line goes under every primary amount.** `Sats.kt`'s
reasoning holds and the guide agrees with it for a wallet this size. What changes is that a primary
amount — the balance, a payout's amount, an entry's signed figure, the cap, the fee cap on the receive
screen — gains a second line in the member's local currency, read from
`phoenixGlobal.currencyManager.ratesFlow`. The guide's rounding rules apply to that line and only to it:
two digits minimum, digits kept below one cent, two non-zero digits with a leading `~` where the value is
uneven. Secondary amounts — pending, held, credit, a round's fee in a history row — get no second line;
six fiat figures on one card is the thing Phase 3 is removing.

A member with no rate yet sees the sats line alone. **The fiat figure is never the only figure, and never
the one a decision is taken on**: no threshold, no refusal and no approval reads it, and the approval
screens the group signs keep sats alone, because what the devices agree on is an integer.

**B2. The balance's provenance stays, and stops being the headline.** `BalanceCard` becomes: the figure,
large; the local currency under it; then one status line; then everything else behind a disclosure. *What
your device has signed for* moves from a label above the number to the status line under it, where it
reads as the answer to *whose figure is this* rather than as the card's title. U2's ordering is
untouched — the device's figure is still the one shown large, and the operator's is still only ever shown
beside it, inside the disclosure, with its agreement line.

**B3. One name per party, and a glossary for the terms that have to stay.** *Daemon* leaves the UI — four
strings, each of which is about what a device is doing and should say so. The **elected member** is
*whoever runs the wallet* everywhere, which 18 strings already say, so the four that say bare *the
operator* change to match; the **Ark server** keeps *Ark operator*, which 8 strings already say
correctly. *Signer* becomes *device* in its 7 strings, because the guide uses *signer* for a hardware
wallet and a member who reads both meets one word for two things. *Round*, *Ark* and *exit* stay — each
names something a member has to be able to reason about — and each gets an entry in a glossary sheet
reachable from the term's first appearance, which is the guide's *educate in place* with somewhere for
the second explanation to live. *Boarding* needs nothing: it already reaches no member.

Counted and held: **a budget of 0 for `daemon` and 0 for bare *the operator*** in `strings.xml`,
enforced the way the M3 audit enforces its own, added to `docs/scripts/` in Phase 1. The second is the
one worth a script: it is the mistake that will be made again, because *the operator* is the natural
short form of the thing 18 strings spell out.

**B4. The receive screen is the verifiable rail, and the other is a route off it.** No chip pair, no
unified QR. The screen opens on the onchain address, which is the rail the member's own device checked.
Lightning becomes a single text button under it whose label says what it costs in trust, leading to a
screen that opens with `group_wavelength_invoice_warning` and then asks for an amount. This is the
guide's don't honoured in substance — the member is not asked to compare two rails they have no basis to
compare — while the asymmetry the guide's design does not have stays visible.

**B5. Room remaining leaves the receive screen and goes to the wallet screen.** The limits a sender needs
stay: least per coin, most per coin, confirmations, the round's fee cap. *The wallet can take N sats more*
is a fact about the group's balance, not about this payment, and it belongs beside the cap on the wallet
screen, where B2's disclosure already holds the balance's other figures. A member who would exceed the
cap learns it from the refusal they already get (`GroupWavelengthReceiveUIState.AtTheCap`), which is the
guide's *educate in place* applied to a limit.

**B6. Every error states the four parts, and `onRetry` is passed wherever a retry exists.** The four
parts are the guide's: what went wrong, where the money stands, how it is fixed or avoided, what to do
next. Where there is no retry, the string carries the fourth part in words and `onRetry` stays null with
a comment saying which it is. `AlarmCard`'s rows each gain a next step; `DaemonStatusLine`'s thirteen
branches each gain one, and three of them gain a button — nominate someone else, open the app elsewhere,
see the key ceremony.

**B7. A health screen, in the guide's checklist shape, and one notification channel.** The guide's
security checklist abstracts away detailed settings and gives simplified feedback. Here the items are the
wallet's real obligations: coins' time left, devices seen lately, who is running it and whether their
app has answered, the fee budget this term, whether the group has a key with no departed holders. Each
item is green, amber or a problem, each links to the screen that fixes it, and each says what happens if
nobody does.

The notification is **one channel, two triggers, both urgent**: coins inside `WalletExpiry.Urgent`, and
too few devices seen for a round when coins are inside a fortnight. Nothing else notifies. The guide's
reminder etiquette is explicit that lower risks earn a quieter treatment, and everything else on the
checklist is a thing a member can see when they look.

**B8. The history gains search, a filter and an export, and renewals group.** Search over the member name
and the entry's words. A filter by kind and by member — three chips, not a dialog. Export as CSV from the
entries the device holds, marked per row with whose word each is, because an export that silently mixes
the device's records with the operator's claims is the one artefact where that distinction will be lost.
Renewals fold into one row per term that expands, which is the open question T10 left for the owner,
answered here as *fold*, and reversible.

**B9. The entry detail gains a receipt and nothing else.** The guide's activity page asks for contact
assignment, tags and a description, and for a saveable, shareable receipt. **Only the receipt is in
scope**: a plain-text summary of what the entry already says, shared through the platform's share sheet,
with the provenance line in it and a line saying what proof the group does and does not hold. The app has
no notes, tags or contacts anywhere, and T9's reasoning against actions on this screen holds. Tags on one
wallet's history would be the first of a feature the whole app lacks, and that is a decision for the
owner, not a phase here (Question 4).

**B10. The way out is named on the wallet screen, and the commitment screen is acknowledged.** The exit
keeps its warnings and its location, and gains a line in B7's health screen — *if the group's devices
stop answering, this is what is left*, with the link. The commitment screen gains the guide's last step:
two choices rather than one button, the member picking the one that says they understand, and the choice
recorded locally per member and wallet so a member who reaches a wallet already opened by someone else
sees the same six sentences and makes the same choice before their device co-signs its first round.

**What B10 does not do:** it does not gate co-signing on that acknowledgement. A device that refuses to
sign because its owner has not tapped a screen is a device that loses the group money on a deadline, and
D16's fee arithmetic makes that everyone's loss, not just theirs. The record is for telling the member
what they have not yet read, not for stopping their device.

## The screens, after

The wallet screen, phone width, a wallet open and healthy:

```
Wavelength wallet                                    [back]

  120,000 sats                                 ← headlineMedium, hideable
  ≈ R 21,400                                   ← bodyLarge, onSurfaceVariant
  What your device has signed for.             ← bodyMedium, the status line (B2)
                                               ← the four other figures, behind:
  Where this figure comes from              ⌄

  [ Add to the wallet ]  [ Pay out ]

  Everything is in order.                   →  ← the health row, B7
  Coins have about 5 days left.

  History                              [search]
  Filters: All · Deposits · Payouts · Mine
  ────────────────────────────────────────────
  ↙ 50,000 sats in, from Thandi
    Settled · 6 Nov, 14:20
  ↗ 12,000 sats to Sipho                 2 sats
    Paid · 5 Nov, 09:11
  ⟳ Renewals this term (7)                  ⌄
    −94 sats
```

The same screen when something is wrong keeps today's order — alarms and departed holders above the
balance — and each of those rows now ends somewhere.

Receiving:

```
Add to the wallet                                    [back]

  Your address                                 ← the onchain rail, which is the screen
  [ QR ]                                       ← labelled, brightened, uppercased
  bc1q…                               [ Copy ]

  Your device checked that this address is one the group's key makes.
  Whatever's sent here counts as yours.

  Send at least 1,000 and no more than 50,000 sats to a coin.
  Coins join the wallet in a round, once they have 2 confirmations
  and enough of the group's devices are online.
  A round takes its fee out of what's sent: the group lets it take
  up to 2,000 sats (≈ R 3.60).

  Ask for a Lightning invoice instead →        ← B4: a route, not a peer
    Only the member running the wallet can make one, and your
    device can't check where it pays.
```

## Phases

One commit per phase, each compiling and passing
`./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest` and `./gradlew :composeApp:m3Audit`.
Messages end `(Group Wavelength redesign, Phase N)`. Every string added goes in
`composeApp/src/commonMain/composeResources/values/strings.xml` in sentence case, with interpolation as a
format argument, in the phase that uses it.

### Phase 1: the words, and a budget that keeps them — **built**

Fourteen strings, one widget and two budgets. No behaviour change, and nothing already on a screen moved.
**The smallest phase here,** which is the finding of §6 rather than a gap in it.

- The four strings that say *daemon* say what a device is doing instead: *The daemon opened a wallet other
  than the one the group signed* became *This device opened a different wallet from the one the group
  signed, so it has stopped running it.* The other three are
  `group_wavelength_tech_no_proof`, `group_wavelength_look_explainer` and
  `group_wavelength_operator_explainer`. Nothing renamed in Kotlin: the keys stay, so the member-visible
  diff is one file, and *daemon* survives in KDoc and one `logger.e`, where it is the right word.
- The four that say bare *the operator* for the elected member say *whoever runs the wallet*, joining the
  17 that already did: `group_wavelength_operator_says_s`, `group_wavelength_no_report`,
  `group_wavelength_only_operator_s`, `group_wavelength_only_device_s`.
- **Three more said a bare *operator* for the Ark server,** which this plan did not have and writing the
  budget found: `wavelength_onchain_payout_wallet_too_small`, `wavelength_payout_holds_back_s_s_s` and
  `wavelength_payout_all_held_back_s_s`, all three on the payouts screen and all three about the least
  change the server will accept before it will sign a spend. They now say *Ark operator*, joining the 8
  that already did. So the ambiguity was not four strings against eight: it was four naming the member
  against eleven naming the server, three of them on the screen that moves money, and *the Wavelength
  wallet's operator won't take a spend* sat one word away from *the operator says 100,000 sats*.
- *Signer* → *device* in `group_wavelength_signers` (*Devices*), `group_wavelength_departed_s` and
  `group_wavelength_refused_size`. **Three strings, not the seven this plan claimed** — that count was of
  keys again, the same mistake §6 was written to correct. `group_wavelength_signers_s_of_s` and
  `a_wavelength_wallet_needs_four_signers` have *signers* in the key and say *to sign* and *members* in
  the text a member reads, so they needed nothing. The other seven in the catalogue belong to the key
  ceremony, the group Lightning wallet and the guarded wallets, where *signer* names a third thing again
  and which this plan does not touch.
- **New:** `GroupWavelengthGlossarySheet.kt` in `ui/composable/widgets/wallet/`, a `ModalBottomSheet` with
  three entries — a round, the two parties, leaving on your own — each three sentences, beside a
  `GroupWavelengthGlossaryButton` that owns its own open state. A real `contentDescription`, not
  `Decorative`: the icon carries the only route to the explanation.
- **It is in each app bar's `actions` slot, not beside the term,** which is the one place the build
  departed from this plan. Three reasons, in order of weight: an inline `IconButton` inside a card's
  `Column` is exactly the layout change this phase promised not to make; the three words are spread over
  the balance card, the history and the devices card, so *beside the term* is three icons saying the same
  thing; and Phases 3 and 4 rebuild two of those three regions, so an inline button would be placed twice.
  Owning the state in the button is what reduced each screen to one line.
- **New:** `docs/scripts/m3-jargon.py`, taking `--list` like its neighbours. Its scope is every key under
  `wavelength_`/`group_wavelength_` **plus every string a Wavelength screen borrows from elsewhere**,
  because a word is jargon for the screen it is on and not for the prefix on its key; the file set is
  every `*Wavelength*.kt` under `ui/` plus `GroupPayoutsScreen.kt`, so a new Wavelength screen is in
  scope the day it is written. The bare-operator rule is *one name per party*: *Ark operator* passes,
  *whoever runs the wallet* passes, and any other *operator* is counted. Wired into `m3-audit.sh` as
  `BUDGET_JARGON_DAEMON=0` and `BUDGET_BARE_OPERATOR=0`.
- **Also, and not in this plan:** `m3Audit` declared only the UI Kotlin tree and the shell script as
  inputs. A commit touching nothing but `strings.xml` therefore left the task up to date and every
  catalogue-reading budget unrun — title case among them, since the M3 work's Phase 4 moved 364 strings
  out of composables and into a file the task could not see. The task now declares the catalogue and
  `docs/scripts/`. **A budget whose input is not declared is a budget that passes CI without running,**
  which is worth knowing before trusting either of the two this phase adds.
- **Tests**, `GroupWavelengthGlossaryJvmTest`: the three terms are each explained once; nothing of the
  glossary is on the screen until the button is pressed; the button has a name to press. Dismissal is
  `ModalBottomSheet`'s own — scrim, drag, back press — and none of those is a semantic node a desktop
  test can address, so it is **not** asserted, which is a gap this plan claimed to close and does not.
  `StringCatalogueJvmTest` gains the two budget assertions, so a new string reintroducing either word
  fails `jvmTest` as well as the audit. Seven assertions in `GroupWavelengthWalletScreenJvmTest` read the
  old words and now read the new ones.

**Done** on 8 November 2026: both budgets are in `m3-audit.sh` at zero, `--check` passes, and
`:composeApp:jvmTest` is green.

### Phase 2: an amount in money the group counts in — **built**

One formatter, one composition local, six lines. B1. No view model and no UI state changed, which is
not what this plan expected and is the better half of what the build found.

- `Sats.kt` gained `Satoshi.inLocalCurrency(rate: ExchangeRate.BitcoinPriceRate?): String?` — null when
  there is no rate, null for a price that is zero, negative or not finite, and null past ten trillion
  of a currency, where a double stops holding the scaled integer exactly. A pure function, tested on
  its own.
- **The guide's rounding rules are narrower than this plan said, and the plan's "five examples" was
  wrong.** The [units page](https://bitcoin.design/guide/designing-products/units-and-symbols/#formatting-very-small-amounts)
  gives **eight** worked examples — four round, four uneven — and **every one of them is below one unit
  of currency.** The section is headed *Formatting very small amounts* and the guide says nothing about
  amounts of one unit or more, which is what this wallet almost always shows. So below one unit the
  built rule is the guide's exactly, and **at or above one unit the rule is this app's:** two decimal
  places, grouped. The mark is `~`, which is the guide's; `≈` appears nowhere in the guide. The
  condition for it is the guide's too — rounding changed the value — so on a converted amount it is
  there nearly always, which is the honest result.
- **The preference is not in `GlobalPrefs`.** It is `UserPrefs.getFiatCurrencies`, reached through
  `Identity.userPrefs`, and **its `primary` defaults to `FiatCurrency.USD`** — so there is no such
  thing as a member who has set no preference, and the locale fallback this plan asked for was neither
  needed nor built. That is half of Question 1 answered by the build rather than by a decision.
- **`ratesFlow` on its own would have been wrong, not just insufficient.** Most currencies arrive as an
  `ExchangeRate.UsdPriceRate` — a price against the US dollar, not against bitcoin — so
  `rates.first { it.fiatCurrency == mine }` returns a rate whose `price` read as a bitcoin price is out
  by the whole dollar rate. `CurrencyManager.calculateOriginalFiat` does the two-step and is what this
  uses. **Phoenix's own `primaryFiatRate` ambient has exactly that bug** (a plain lookup in
  `LocalExchangeRatesMap`), which is why it is not reused.
- **A composition local, not a field on `Opened`.** `LocalExchangeRate` is provided once in
  `DedunNavHost`, above every screen, beside `ProvideSigningCapability`. A rate is a fact about this
  device and this member, not about a wallet — and more concretely, two of the six sites cannot be
  reached from `Opened` at all: the cap on the commitment screen is drawn from `NotOpened`, before a
  wallet exists, and `GroupPayoutsScreen` has a view model of its own. The plan's design would have
  shipped four of six.
- The one widget is `LocalAmount`, which draws the line in `onSurfaceVariant` at `bodySmall` or nothing
  at all. One widget rather than six `?.let { Text(…) }`, so the line has one style and one condition
  everywhere: **the sats figure above it is the figure.**
- The six sites, as planned: the balance figure, the cap beside the thresholds, the cap on the
  commitment screen, the round's fee cap on the receive screen, the entry detail's leading amount, and
  the payout preview's per-member amount. Nowhere else.
- **A rate needs a started Phoenix business.** `BusinessManager.startNewBusiness` is what calls
  `startMonitoringCurrencies`, and a member signed in with a bare nostr key starts no business. They
  have no rates and see no second line anywhere, which is right — the alternative is quoting a price
  this app never fetched — and is worth knowing before reading an empty screen as a bug.
- **Tests** are in `commonTest/SatsTest.kt`, which already existed, rather than a new `SatsJvmTest`:
  all eight of the guide's examples, the €0.0000431 the guide names as the problem case, the rule above
  one unit, every null, a negative, and the figure too large to hold.
  `GroupWavelengthWalletScreenJvmTest` gained three: the balance keeps its sats figure *and* gains the
  line; with no rate it is one line as before; and the mainnet cap says what it is worth on the
  commitment screen. **The case this plan asked for — "no approval or threshold string contains the
  local currency" — was not written, because there is no such string with an amount in it to leak
  into.** What is asserted instead is B1 itself: the sats figure is still there and the currency is a
  second line under it.
- **One bug worth the note.** The first cut tested *exact* for the `~`, and a double multiply means a
  figure that is round to its last digit arrives as `246.90000000000003`. That put a `~` on a figure
  that had lost nothing and padded the guide's `0.03` out to `0.030`. The question the code asks now is
  whether the *display* lost anything, at a tolerance above a double's noise.

**Done** on 8 November 2026: a screen test with a rate shows two lines, one without shows one, and the
figure a decision reads is still in sats everywhere.

### Phase 3: the balance, as a balance — **built**

Layout within `BalanceCard`, one new widget, and B5 done early. B2.

- On the face of the card: the figure, what it is worth, and **one status line** — whether anybody
  disagrees, or that nobody has reported yet. In the disclosure: pending, held, whoever-runs-the-wallet's
  figure with its own currency line, a credit, and B5's room remaining. **This plan put the agreement
  line inside the disclosure and also asked for "one status line" outside, which are not compatible;**
  the agreement line *is* the status line, and it stays out, because the provenance of this balance is
  the whole of U2 and must not be the thing a member goes looking for.
- Collapsed by default, and it opens itself whenever `Agreement.DIFFERS` or a credit is non-zero — not
  just at first composition. `openFor` reopens it if the figures start agreeing and later disagree,
  which a once-only default would have missed, since `refreshFacts` fills the comparison in after the
  card has already composed.
- **The widget is `Disclosure`, not `DisclosureCard`.** Its one caller is already inside a `Card`, and a
  card inside a card is two elevations saying one thing. A `ColumnScope` extension, so the label and the
  content are siblings in the caller's column rather than a nested surface.
- Hide and reveal: an `IconButton` beside the figure, one bullet per digit so the card does not change
  size, and the local-currency line and the whole disclosure gone while it is hidden. **The warning
  stays** — hiding is about who can read the screen over a member's shoulder, not about what the member
  is told.
- **No "hold to reveal":** a long press with no affordance is undiscoverable, and one tap already
  reveals.
- The toggle was **not persisted** when this phase landed — `rememberSaveable`, forgetting itself the
  moment a member navigated away — because the app had no per-group preference store and writing one
  would have answered **question 2** by implementation. It persists now: see *The store, afterwards*.
- **B5 is done here rather than in Phase 4.** `group_wavelength_receive_room_s` is deleted, not moved:
  the receive screen's sentence was written for that screen, and the disclosure gets a shorter
  `group_wavelength_room_s`. Doing it now avoids the figure standing in two places between phases.
- The transition is `expandVertically` + `fadeIn` from `MaterialTheme.motionScheme` — spatial for the
  height, effects for the fade — and under `MaterialTheme.reducedMotion` the section still appears and
  disappears, it just stops growing.
- **Tests**, `GroupWavelengthWalletScreenJvmTest`: all four figures absent until one tap brings all four
  back; `DIFFERS` opens it with no tap; a credit opens it with no tap; hiding removes every figure on the
  card and leaves the disagreement, the deadline and the card's label; the three agreement outcomes each
  render, as they already did.
- **Two things the build taught, both about the tools.** `said()` names its coins `a:0`, `b1:0`, …, so a
  snapshot built with it never matches a ledger of named coins — a collapsed-state test written on that
  fixture compares as `DIFFERS`, opens the disclosure and asserts nothing. And `m3-touch-targets.py`
  reads a `.clickable`'s own line and the one above it, so `.minimumInteractiveComponentSize()` on the
  line *below* reads as no minimum at all. The checker is left as it is: its window only ever produces a
  false positive, and widening it could hide a real one.

**Done** on 8 November 2026: the five cases pass, and the card's collapsed height is one figure, one
currency line and one sentence.

### Phase 4: one way in — **built**

The receive screen, restructured. B4, B5, and finding 9's three items.

- `Rails` is `TheWayIn`, and the `FilterChip` pair is gone. The onchain items are the screen; the
  Lightning route is a `TextButton` with the reason under it, to a new `GroupWavelengthInvoiceRoute`
  whose `GroupWavelengthInvoiceScreen` is the old `lightningItems` with the warning first and
  `InvoiceAsk` under it. The invoice is not reachable without leaving the receive screen.
- ~~`group_wavelength_receive_room_s` moves to the wallet screen's disclosure (Phase 3's card) and
  leaves this screen.~~ **Done in Phase 3,** which built the disclosure: the string is deleted rather
  than moved, and the disclosure has a shorter `group_wavelength_room_s` of its own.
- `QRCodeView`'s `contentDescription` is a required parameter, the same argument `ScreenState.kt:60`
  makes for `onRetry`. **Ten call sites, not nine:** `WavelengthReceiveScreen`'s `RequestCode` draws an
  invoice on one branch and an address on the other, so one label for that file would have been the
  shared default CLAUDE.md warns about, and it takes the description as a parameter now. Ten strings,
  each naming what its code is for. `data` stops being the description.
- **The description being the payload was load-bearing in a test.** `PaidRequestsNoQrJvmTest` asks "is
  this scannable?" by looking for a node whose description *is* the request — it says so in its own
  KDoc — so the whole class moved onto the labels. It is no weaker for it: each of those screens draws
  at most one code, and the assertion that a description is **not** its payload is now its own test.
- Brightness: `RaiseBrightnessWhileVisible`, an `expect` in `ui/composable/widgets/` with three
  `actual`s. Android sets the window's own `screenBrightness` and hands it back with
  `BRIGHTNESS_OVERRIDE_NONE`; desktop has no per-window brightness; **iOS is a deliberate no-op**,
  because `UIScreen.brightness` is the device setting rather than a window override — raising it there
  would change what a member finds after they leave the app, which is worse than a dim QR code. Called
  by the two Wavelength receive screens rather than by `QRCodeView`, so the profile share and the
  group's other wallets keep the behaviour they had.
- bech32 uppercased **inside `QRCodeView`**, so what a member reads and copies is untouched — the
  display string is the `Copyable` beside the code. Only where it is certainly safe: every character in
  the bech32 alphabet plus the `:` of a scheme. A BIP 21 `?label=` means its own case and a mixed-case
  payload is not bech32, so both are encoded exactly as given.
- **Tests:** the receive screen has no chip pair, says the route's reason on the button, reaches no
  invoice, and calls back when the route is tapped; the invoice screen's warning is the first thing on
  it; `QRCodeViewJvmTest` asserts the description is not the payload and covers the uppercasing's four
  safe and four unsafe cases. `PaidRequestsNoQrJvmTest` gained a `groupInvoice` host for the rail that
  is now its own screen.
- **The Android brightness `actual` has no test, which is the one thing this phase did not deliver.**
  `androidInstrumentedTest` has no Compose UI test dependency — it holds two worker tests and nothing
  that hosts a composable — so covering it means adding `ui-test-junit4` and `ui-test-manifest` to the
  build. That is Phase 9's work, which needs the same harness for three screens at the largest text
  size, and doing it here would have been build-configuration work in a phase that planned none. The
  code is 20 lines and reviewable; it is **not** verified on a device.

**Done** on 8 November 2026: a member on the receive screen cannot reach a Lightning invoice without
passing its warning, and no screen test finds the balance or the room remaining on it.

### Phase 5: errors that end in an action — **built**

B6. Twenty-five messages, five retries, and one `else` branch deleted.

- **Five** `ErrorState(onRetry = null)` sites got a retry, not four: Phase 4 added the invoice screen.
  Each is a read, and `viewModel.initiate()` is the retry. No null remains on these screens.
- **The collapses are undone, and both were worse than counted.** The wallet's ten refusals shared six
  messages behind an `else`, so `UNREADABLE`, `NO_GROUP_KEY`, `NO_ONCHAIN_WALLET` and
  `WAITING_FOR_SEED` all read *this device can't confirm it* — four situations whose next steps are
  *propose it again*, *make the key*, *open the onchain wallet* and *wait*. `NO_CEREMONY` and
  `CEREMONY_DISAGREES` shared a seventh, which this plan did not notice: *no record of making this key*
  and *a record that disagrees with it* are not the same thing to tell a member. All ten have their own
  message and **the `when` is exhaustive**, so an eleventh refusal fails to compile instead of joining
  them. `group_wavelength_refused_other` is deleted rather than kept for unknown refusals: with an
  exhaustive `when` there are none, and a spare bucket is how this started.
- `NOT_OPERATOR_OR_BEHIND` and `NOBODY_RUNS_IT` separate, as planned. Both read *waiting to catch up
  with the group* before, which is *wait* standing in for *somebody has to volunteer* — a member
  reading the first when the second was true would wait for a wallet that was never going to renew.
- Every one of the twenty-five says where the money stands and what to do. *The group opened a wallet
  that doesn't match the keys this device holds* now adds *Nothing has been signed, and nothing of
  yours can move while it refuses. Ask the group to check what was proposed against the key ceremony.*
- **`AlarmCard` gets one next step for the card, not one per row,** which is a departure. Its rows are
  the device's own free text about whatever it refused — there is no enum to map — so a supporting line
  under each row would have been the same string repeated, which is the shared default this phase
  exists to undo. The step after any refusal is the same: nothing of yours moved, go and find out what
  was proposed.
- `DaemonStatusLine` is `DaemonStatus`, a `Column` with the state, its next step, and for three of them
  a button. `BusyWithAnother` needed one more thing to make its button real: it carried a group's
  **name** and not its id, so `GroupWavelengthDaemonStatus.BusyWithAnother` gained a `chatRoomId` and
  `GroupWavelengthRunner` fills it from the room it is already holding. Nothing on the wire changed.
  `SIGNERS_NOT_READY` and `Failed` get no button, and their strings say they retry themselves.
- **Tests:** the four refusals that shared a sentence each say their own with a next step, and so do
  `NO_CEREMONY` and `CEREMONY_DISAGREES`; *wait* and *somebody has to volunteer* are no longer one
  string; the three buttons fire and `Failed` offers none; an error offers a retry.
  `StringCatalogueJvmTest` asserts over the whole list that **every one of the twenty-five has at least
  two sentence endings** — a crude proxy for the guide's four parts, and the one a script can keep —
  and that no two are the same string. The one deliberate reuse is
  `group_wavelength_refused_chain`, which is one fact said once: the wallet won't be signed for on a
  network this build does not offer, and the device won't run it.
- **Twenty-five, not the plan's twenty-seven.** Ten wallet refusals, nine daemon states and six daemon
  refusals, with `refused_chain` serving the seventh.

**Done** on 8 November 2026: no state on these screens says only what happened, and no two say the same
thing.

### Phase 6: the wallet's health, said before it is urgent — **the screen is built; the notification is not**

B7's first paragraph is built. Its second is not, and **the reason first written here was wrong**: the
trigger is already evaluated every thirty minutes, and the app already does background networking twice.
What is outstanding is delivery. See *What Phase 6 left, and what looking properly found* below.

- `GroupWavelengthHealth` is a pure object in `wallet/wavelength/group/`, not a view model: five items,
  each `FINE`, `ATTENTION` or `PROBLEM`, each with a `Fix`, derived entirely from
  `GroupWavelengthWalletUIState.Opened`. A `GroupWavelengthHealthViewModel` would have been a second
  reader of the same state, so the screen uses `GroupWavelengthWalletViewModel` the way the advanced
  screen does and the logic is a function with a fixture-driven test.
- The five are B7's: the coins' deadline, devices answering, who runs the wallet, this term's fees, and
  who holds a share. **Fourteen messages for five items,** because what to do about a fee budget four
  fifths spent is nothing like what to do about one that is gone — a checklist whose amber and red say
  the same thing has two colours and one message.
- **Two items read the deadline to decide how much they matter,** which the plan did not call for and
  the writing made obvious: too few devices answering is amber at three weeks and a problem at three
  days, because what it costs depends on whether a renewal is due. `SOON_BLOCKS` is a fortnight, the
  same threshold B7 gives the second notification trigger.
- **A wallet holding nothing gets no deadline item at all,** rather than a green one. A tick against a
  deadline that does not exist reads as *checked and fine* where the truth is *nothing to check*.
- The row sits between the balance and the history, where `DeadlineLine` was. All five fine reads
  *Everything is in order* with the deadline under it; anything else reads **the worst item's own
  sentence** rather than that something is wrong. `DeadlineLine`'s text came out into a shared
  `deadlineText` so the row could say it.
- The leading icon has a **real description**, not `Decorative`: green, amber and red are the whole of
  what it says, and a member who cannot tell them apart would otherwise read five identical rows.
- **Tests:** ten cases in `GroupWavelengthHealthJvmTest` — each item's own states, the fee budget's
  three bands, the device that runs it reading its own process rather than the room, worst-first with
  two problems, all-fine, and the wallet with no coins. Four screen cases: the row in good order with
  the deadline, the row naming the worst, the checklist's five lines with their consequences and fix
  buttons, and the four-line checklist.

#### What Phase 6 left, and what looking properly found

The notification is not built. **The reason first given here was wrong, in the way worth recording:**
this section said the trigger *cannot be evaluated in a periodic worker without a background-networking
decision this plan did not make*. Both halves of that are false, and finding out took one afternoon of
reading that Phase 6 should have done before writing the paragraph.

**The trigger is already evaluated, every thirty minutes.** `GroupArkWatcher.deadlinePass()` walks every
wallet with coins, takes the earliest `expiresAtHeight` the device holds, reads the tip from Electrum,
computes `WalletExpiry`, classifies it as *renewing*, *urgent* or *expired*, **and names who has not
answered in a day** — which is B7's second trigger. It then writes a line into the room,
`ChatMessage.TYPE_WAVELENGTH_DEADLINE`, which the transcript already draws with a warning icon. So B7's
two triggers are not a computation this app lacks. They are a computation it has been doing all along
and delivering to a place a member only sees **if they open the group**.

**And the app already does background networking, twice.** `ChannelsWatcher` is a periodic worker,
scheduled by dedun's own `schedulePlatformLogic`, that starts a *headless Phoenix business* and waits
for `electrumWatcher.openUpToDateFlow()` — every 36 hours. `WavelengthMaintenanceWorker` opens the
personal Wavelength wallet in the background every 24. So a worker that opened an Electrum connection
would be the third, not the first, and the battery-and-privacy argument this section made is an
argument against something the app already does.

**The tip needs no identity and no seed.** `IdentityElectrum.standaloneOn` takes an `Identity` for
exactly two things — `getIsTorEnabled` and `getElectrumServer`, both `UserPrefs` flows — and
`WavelengthMaintenanceWorker` already loads precisely those, in two lines, without the words. A chain
tip is public data. Nothing has to be decrypted to read one.

**De-duplication is already solved.** `GroupLightningLines.writeFact` returns early if a line with the
same `factKey` exists, and the key is `"$walletKey:$earliest:$level"` — so the existing mechanism
already fires once per coin-deadline per level, which is exactly B7's *one channel, two triggers, both
urgent, nothing else*.

So what is left is **delivery, not evaluation**, and it is one decision in three sizes:

| | what it is | reaches | what it costs |
|---|---|---|---|
| **1** | `GroupArkWatcher` posts a notification where it already writes the room line | any member whose app process is alive — always on the operator's device, which the foreground service keeps up | an `expect`/`actual` notifier and a channel. Android posts; desktop and iOS no-op or their own equivalent. Perhaps 60 lines, no new scheduling, no new networking, de-duplication already there |
| **2** | 1, plus a periodic Android worker running `deadlinePass` | every member, app open or closed | the worker: the aux database (platform context only), a `StandaloneElectrum` (two `UserPrefs`), and `WorkManager` scheduling. A third periodic network connection per member, on the same footing as the two already there |
| **3** | 2, but evaluated offline from a stored tip | every member, without networking | a persisted `(height, observedAt)` and an extrapolation at an assumed block interval. **Not recommended:** it is the only option that can be wrong, and it errs in whichever direction the assumed interval does |

**The one option that is actively wrong,** and worth naming so nobody reaches for it: the operator's
snapshot carries a `tip`, and it is already stored. Using it would make the warning suppressible by the
party the warning is about — an operator who wanted the coins to expire need only report a stale tip.
This wallet's whole principle is that the operator's figures are not what a decision reads, and a
deadline warning is the decision where that matters most.

**What this means for the plan's own claim.** *Order* says the cheapest useful stopping point after 6
is that *the wallet stops relying on a member opening a screen to learn that its coins are expiring*.
With the room line it already relies on a member opening the **group** rather than the wallet screen,
which is less than the claim and more than this section first said. Option 1 makes it true for a member
whose app is alive; option 2 makes it true.

### Phase 7: a history a group can account for — **built**

B8. Two new pure files, and the wallet screen's history section rewritten.

- `GroupWavelengthHistoryView` holds the search, the three filters and the fold — **a pure object, not
  the view model the plan named.** The plan's reason for the view model was that the composable should
  not filter inside its item lambdas, which this satisfies, and a function with a fixture has a test
  where a view model would have needed a harness. The *typing* stays in the composable in
  `rememberSaveable`, which is what lets a screen test drive the search box.
- Two things it cannot know for itself are passed in: an entry's **words** and a member's **name** both
  come from composable lookups, so the caller resolves them once for the whole list and this searches
  what it is given. Resolved once rather than per row per keystroke: a 200-row search would otherwise do
  400 string lookups on every letter typed.
- **Folding happens after searching,** which the plan did not say and the writing forced: a term row
  standing for the one renewal a search matched would be a heading over a single line. A term of one
  renewal is left alone for the same reason.
- **A round whose term this device does not hold is never folded.** The term comes from the fee row, and
  a round inside the free refresh window leaves none — so guessing which term it belonged to would put
  a figure into a total that nothing supports.
- **The operator-only heading stays, which the first cut of this dropped.** The partition that puts
  *only whoever runs the wallet says these happened* over the rows nothing of this device's supports is
  where the screen says whose word they are; the new *whose word* chip is a way to narrow to them, not a
  replacement for saying so. Two tests caught it, which is what they were written for.
- `GroupWavelengthExport.toCsv` is ten columns, every field quoted rather than only the ones that need
  it: a member name is free text somebody typed, and the first one with a comma in it is how a file like
  this quietly becomes one column short on every row below.
- **The export goes to the clipboard, not a share sheet,** which is the one place this phase is thinner
  than the plan. A share sheet means an `expect`/`actual` whose Android half wants a `FileProvider` in
  the manifest and whose desktop half wants a file picker — build and platform work in a phase that
  planned none, and unverifiable here for the same reason Phase 4's brightness is. The clipboard works
  on all three platforms today with no platform code, and **B8's reason for this feature is the `source`
  column rather than the delivery.** A file is the better delivery and is not built.
- **Tests:** thirteen in `GroupWavelengthHistoryViewJvmTest` — search by a member's name and by a status
  word, each filter alone, two together, a filter with a search, the fold's total against the sum of its
  rows, the term of one, the round with no term, and the dimensions offered. Seven in
  `GroupWavelengthExportJvmTest`, including a name with a comma and a name with a quote, read back
  through a CSV parser rather than compared as a string. Seven screen cases: the fold and its total,
  typing each way, the no-match empty state in **its own words**, a chip narrowing and clearing, the
  export's report, and a wallet with nothing in it offering no search box.

**Done** on 8 November 2026: a group with a year of renewals and one payout finds the payout by typing
three letters of it, and the file it exports says whose word every row is.

### Phase 8: a receipt — **built**

B9. One control on the entry detail, one new pure file, and nothing else on the screen.

- A `TextButton` beside *Technical details* copies a plain-text record of what the screen already shows:
  what it was, how it went and when, how much, what it cost, whose it is, **whose word each of those
  is**, and what the group can show for it. `GroupWavelengthReceipt` assembles it; the screen resolves
  the words, since every one of them is a string lookup or a member's name.
- **The source line sits above the proof line, not at the end.** A reader who gets no further than it
  has still been told the one thing that qualifies everything above it, which is the whole of U2 in one
  sentence.
- **There is no txid, which this plan expected.** `GroupArkLeave` — an onchain payout's own row — holds
  the round's `commitment` and the destination `script`, and no transaction id at all: a payout leaves
  **in a round**, and the round is the thing this device put a signature on. So the receipt names the
  commitment, which is what a member can take to the chain.
- The other kinds name what this device actually holds: a boarding deposit names the coin's outpoint, a
  Lightning deposit names the **transfer** its claim went into and not the payment hash (a hash shows a
  payment was asked for, not that one happened), and a round names its commitment. An exit and a line of
  the operator's alone name nothing, and the source line above has already said why.
- A Lightning payout gets `group_wavelength_tech_no_proof`, reused exactly as the plan insists — the one
  line in this app that says *we cannot prove this*, and the reason it is worth a test of its own.
- **To the clipboard, not a share sheet,** for Phase 7's reason: a share sheet wants a `FileProvider` in
  the manifest, and a receipt is a paragraph somebody pastes into a message.
- No tag, no note, no contact. Question 4 still stands.
- **Tests:** eight in a new `GroupWavelengthReceiptJvmTest` — all seven kinds have a receipt and every
  one of them says whose word it is; the no-proof line is the last line of a Lightning payout's; the
  onchain payout names its round; each kind that holds something names it; an exit and a line of theirs
  name nothing; a payout with no round yet names nothing rather than an empty id; and the lines nobody
  holds are left out rather than left blank. One screen case for the control and what it reports.
  **The tests are a file of their own rather than in `GroupWavelengthEntryScreenJvmTest`,** because what
  is worth asserting is the assembly and the proof rule, and both are a pure function.

**Done** on 8 November 2026: each of the seven kinds' receipts names its provenance, and the one kind
that can never prove itself says so.

### Phase 9: read aloud, and at twice the size — **built**

The guide's accessibility chapter against these screens, after the layout settled. Last of the ten, and
the ordering earned itself: Phase 3 rebuilt the balance card, Phase 4 rebuilt the receive screen and
Phase 6 added a screen the plan's list of three does not mention.

- **The font-scale cases are `jvmTest`, not the `androidInstrumentedTest` the plan named.** Font scale
  is `LocalDensity`, which a desktop test provides as easily as a device does, and a window 320dp wide
  is `runDesktopComposeUiTest`'s own argument. So what the plan expected to need an emulator needed
  neither one nor the device harness — and the cases run in the ordinary suite rather than behind an
  instrumentation flag, which is the difference between a check that runs and one that could.
- The three the plan expected to break hold at twice the text in 320dp: the balance card with its
  figure, currency line, status line and disclosure; the commitment screen's six sentences and two
  choices; the advanced screen's terms. **A fourth was added** — Phase 6's checklist, which did not
  exist when the three were listed.
- `SignersCard`'s rows are merged, so a member reads as one thing. Four members were eight unrelated
  strings read out in a row with nothing to say which half belonged to whom.
- **What a merged node can and cannot be asserted about, which cost a wrong test first:** a merged node
  still reports each of its descendants' texts, so *nothing is the seen line alone* is not a thing the
  semantics tree can be asked. What tells merged from unmerged is **one node matching two texts**, and
  that is what the test does.
- A haptic on the two actions that commit the group — asking to be paid out, and asking to leave — and
  on nothing else, asserted both ways: the committing action is felt, and opening a disclosure is not.
  A haptic on everything is a haptic on nothing.
- **The device harness is added and the brightness `actual` is verified on an emulator,** which closes
  Phase 4's one gap. `compose.uiTest` in place of the plan's `ui-test-junit4`, because this is a Compose
  Multiplatform project and the multiplatform artifact is the one that versions with the rest of
  compose; `ui-test-manifest` is needed after all, for the empty `ComponentActivity` the content is
  hosted in — `MainActivity` calls `setContent` itself, and a test that calls it again gets *has
  already set content*.
- **Two things stood between that test and running, neither of them the code under test.** `compose.uiTest`
  brings espresso 3.5.0 transitively, which calls `InputManager.getInstance` — removed in Android 14 —
  so on an API 36 emulator every compose device test dies inside `onIdle` with a
  `NoSuchMethodException`. Espresso is pinned to 3.7.0 in the build file with that written down, because
  the next person to add a device test here would lose the same hour to it.
- **Not done: TalkBack itself.** Reading order and the merge are asserted through semantics, which is
  what a screen reader is handed, and that is not the same as listening to one. Nothing here replaces a
  person with TalkBack on going through the seven screens, and no test in this plan can.

**Done** on 8 November 2026: four dense screens hold at twice the text in 320dp, `SignersCard` reads as
one row per member, the two committing actions are felt, and the brightness actual is verified on a
device.

### Phase 10: the way in, acknowledged — **built** (the record, with the store)

B10. Built out of order, before Phase 9, because the plan requires Phase 9 to be last.

- `Commitment` keeps its six sentences and has two choices in place of its single `Button`: *I
  understand, and the group can open it*, and *Not yet*. The first proposes; the second leaves the
  screen. One button made reading the six sentences optional and opening the wallet the only thing on
  the screen to do.
- The second choice's label is **two words, not the plan's *Not yet — show me this again***. A button
  says what it does in as few words as will carry it, and what it does is on the screen it returns to:
  the six sentences are there again next time, because nothing was recorded.
- The way out is named on the health screen with its link, and **the link appears only where the exit is
  actually offered** — `offersUnilateralExit` and the device that runs the wallet. A button to a screen
  that would refuse is worse than a sentence saying what would happen. A member who cannot start one
  still gets the sentence.
- The acknowledgement record was **not built** when this phase landed, because the app had nowhere
  local to put one row per member and wallet, and writing one would have answered **question 6** by
  implementation. It is built now: see *The store, afterwards*.
- **Nothing was gated on anything,** which is B10's own limit and the one part of this phase that could
  have done harm. A device that refuses to sign because its owner has not tapped a screen loses the
  group money on a deadline.
- **Tests:** the two choices, and that the second proposes nothing; the health screen names the way out
  and links to it for the device that runs the wallet; a member who does not run it gets the sentence
  and no button.

**Done** on 8 November 2026: the commitment screen asks rather than tells, the way out is answerable
without the word *Advanced*, and — once the store landed — a member who joined after the wallet was
opened reads the six sentences before the wallet.

## The store, afterwards

Phases 3 and 10 each shipped without a per-group-wallet fact they needed, and each wrote down why rather
than deciding it: the balance's hide toggle forgot itself the moment a member left the screen, and the
commitment screen's acknowledgement was not recorded at all. Both were the same gap. `GroupWalletPrefs`
is it, built after Phase 9 on the owner's answer to questions 2 and 6.

- **A `DataStore`, not a table.** Everything in it is a local per-device preference: nothing is signed,
  nothing goes on the wire, nothing another member can read, nothing a query joins against. A Room table
  would have meant a schema migration in `composeApp/schemas` for two booleans.
- **And no new platform code.** `computePreferencePath` is Phoenix's own public `expect`, with an
  `actual` for each of the three targets — so the store is one file, and the thing both phases called a
  wall turned out to be a function that was already there. Worth saying plainly: the obstacle was not
  that the platform work was hard, it was that neither phase had looked for it.
- Keys are the wallet key `GroupArkCoSigner.walletKey` already builds, so a group with two wallets, or
  the same group on two networks, keeps them apart. The acknowledgement's key carries the member's own
  key too, because two members sharing a device must not acknowledge for each other — which is the one
  assertion in its test that would matter.
- `GroupWavelengthWalletUIState.Opened` gains `balanceHidden` and `acknowledged`, and the view model
  **watches** them rather than reading once: a member who acknowledges on the screen above the wallet
  has to reach the wallet without navigating. `acknowledged` defaults to **true**, the opposite of the
  store's default, so a state built without a store — a preview, a test, a screen that has not read it
  yet — never flashes the commitment screen over a wallet.
- **The six sentences are now one `commitmentSentences`,** shared by the screen that asks before opening
  and the screen that asks a member who joined later. Two copies would have drifted, and a member
  joining late should read exactly what the group read.
- **Still nothing is gated on anything.** `acknowledged` is read by the screen and by nothing that
  signs, which is B10's own limit: a device that refused to co-sign because its owner had not tapped a
  screen would lose the group money on a deadline, and D16's fee arithmetic makes that everyone's loss.
- **Tests:** nine in `GroupWalletPrefsJvmTest`, all of them about scoping — one member with two wallets,
  two members on one device, a key in the wrong case, and the two preferences not standing in for each
  other. Four screen cases: the six sentences over an unacknowledged wallet and not the wallet behind
  them, the wallet once acknowledged, *Not yet* recording nothing, and a hidden balance coming back
  hidden.

**What the store does not touch.** Phase 6's notification is still not built: its blocker is the chain
tip needing a live Electrum subscription, which no preference store helps with.

## Order

1 → 2 → 3 is the spine, and each needs the one before: the words before the layout that uses them, and
the formatter before the card that calls it. 4, 5 and 6 are independent of each other and all three need
2. 7 and 8 need nothing but 1. 9 has to be last. 10 can land any time after 1 and is the only phase that
is purely a question of policy.

The cheapest useful stopping points, if this is not built through: **after 2**, every amount in the wallet
is in money a member can value, which is the single largest finding; **after 3**, the balance reads as a
balance and can be hidden; **after 6**, the wallet stops relying on a member opening a screen to learn
that its coins are expiring — which the build reached only halfway, and for a smaller reason than this
plan first recorded: see *What Phase 6 left, and what looking properly found*.

Nothing here blocks on the real round on phones (the UI note's Phase 4), on a funded wallet, or on the
unilateral exit. **And nothing here makes any of those less necessary.** Every screen this plan improves
is a screen that has still never shown a real coin moving, and a redesign cannot find what a round on four
phones will.

## Risks

- **Phase 2 puts a price on the screen, and a price is a claim about the outside world.** The rate comes
  from Phoenix's `currencyManager` and can be stale, wrong, or absent. The mitigations are B1's: never the
  only figure, never the figure a decision reads, `~` on anything rounded, and nothing at all rather than
  a guess when there is no rate. The failure to watch for is a member reading a stale fiat line as the
  reason a payout is worth proposing.
- **Phase 3 hides four numbers that are there because somebody needed them.** The expand-on-`DIFFERS` and
  expand-on-credit rules are what keep a disagreement visible, and they are the two tests in that phase
  that matter. If a member who needed *held* now has to tap for it, that is the cost, and reverting is one
  parameter.
- **Phase 4 makes the Lightning rail harder to reach, on purpose.** It is the rail that works when the
  onchain one is slow, and a member in a hurry will now pass a warning to get to it. That is the intent —
  the warning is true — but it will read as friction to whoever asked for the rail.
- **Phase 6 notifies about somebody else's phone.** The two triggers fire on every member's device when
  the operator's is off, so four people get the same notification about one person's phone. The guide's
  etiquette is why it is only ever the two urgent triggers, and the thing to watch is a quiet group
  getting the same notice for a fortnight.
- **Phase 7's export is the first artefact of this wallet that leaves the app.** It carries member names,
  amounts and times for everybody in the group, written by any member who can open the screen. Today that
  information is equally visible and far less portable, which is a real change in exposure even though it
  is no change in access. Question 5 puts it to the owner.
- **The vocabulary is the part of this plan a later change can most easily undo.** Phase 1's two budgets
  cover *daemon* and bare *the operator*, and a new string with either fails both `--check` and
  `jvmTest`. Nothing stops the next screen reintroducing *signer* for a device, or writing *round* with no
  glossary link, and a budget over those two would fail on the strings that legitimately need them —
  *Devices* cannot be spelled without a word for a device. So two of the four corrections ratchet and two
  rely on review, which is worth knowing before trusting the audit to hold this.
- **Scope.** Ten phases over seven screens, in a wallet whose own note says a real round on phones can
  still invalidate its screens. A phase here that starts needing a change in
  `GroupWavelengthWalletManager`, a policy rule or an event is a phase that has gone wrong, and should
  stop.

## Questions, for the owner

1. **Whether the group shares one currency.** *Half answered by the build:* there is no member who has
   set no preference, because `UserPrefs.getFiatCurrencies` defaults its `primary` to USD, so the
   locale fallback this plan asked about was never needed. What is still open is the other half. Phase 2
   shipped the member's own Phoenix preference, so four members may read one balance in four currencies
   — right for each of them, and odd in a shared wallet when two of them compare screens in the room.
   Per-member, or one currency the group signs into the wallet's statement?
2. **Whether the hide/reveal preference is per wallet or per app.** *Half answered by the build:* it
   persists, per group wallet, in `GroupWalletPrefs`. What is still open is the other half — whether a
   member who hides one balance means *hide balances*, which is nine other wallet screens and a plan of
   its own. Nothing here presumes an answer: the key is the wallet's, so an app-wide preference later
   would read these as the per-wallet overrides they are.
3. **The exit's location.** B10 leaves it under *Advanced* and names it on the health screen. The guide's
   Interested-phase question argues for it being answerable before a member commits — which would mean on
   the commitment screen, saying *this is how you leave if the group's devices stop answering*, about a
   path that has never been run. More honest, or more alarming?
4. **Tags, notes and contacts.** The guide asks for all three on an activity screen and the app has none
   anywhere. Phase 8 deliberately builds only the receipt. Is a notes-and-tags feature across the app's
   wallets something to plan, or is the receipt the answer?
5. **Who may export.** Phase 7 lets any member export every member's deposits and payouts to a file. The
   same is true of the screen today, and a file is different. Every member, the operator only, or a
   per-member export of their own rows?
6. **Whether acknowledgement is worth a row.** *Answered: yes, and it is not a row.* It is two keys in
   `GroupWalletPrefs`, which cost no schema migration, and it buys the half of B10 that was missing — a
   member who reaches a wallet somebody else opened now reads the six sentences before the wallet. It
   still changes nothing a device signs, which is B10's own limit and is asserted.

## What this plan does not do

- **It changes nothing a device signs.** No `ArkPolicy` rule, no refusal, no event's wire shape, no new
  kind, no new tag, no change to what `GroupWavelengthWalletManager` or the co-signer will accept. No
  phase is marked *policy*; one that needs to be stops first.
- It asks the operator for nothing new. No field is added to a snapshot and no request is added to the
  wire. Every figure on every screen here is one the device already holds.
- **It does not adopt the ₿-only format,** and it does not switch the wallet to bitcoin. `Sats.kt`'s
  reasoning stands and the guide's default agrees with it.
- **It does not unify the two receive rails** behind one BIP 321 request, which the guide recommends and
  which would hide the one thing a member needs to decide (B4).
- It does not gate co-signing on anything a member has or has not tapped (B10).
- It adds no action to the entry detail beyond sharing a receipt. T9 holds.
- It does not touch the personal Wavelength wallet's screens, the group's Lightning wallet, the onchain
  wallet or a member's guarded wallet, **with one deliberate exception**: Phase 4 makes
  `QRCodeView`'s `contentDescription` a required parameter, which reaches its ten call sites across eight
  screens. Every one of them reads its payload aloud today, and fixing it on one screen while leaving it
  on seven would be the wrong half of an accessibility finding. The three widgets Phases 3, 4 and 7 add
  to `ui/composable/widgets/` are available to those screens and used by none of them.
- It adds no custody option, no cloud backup and no recovery phrase. The guide's whole
  backup-and-recovery chapter is about a single key on a phone; this wallet's share is one of four and the
  recovery route is the group, which `your_share_of_this_key_is_stored_unencrypted` already says out loud.
- It does not revisit the M3 work. Every phase keeps the audit's budgets and floors where they are.
- **It does not make this wallet safer.** Every finding here is about what a member can understand, hide,
  value, search or be told in time. The three things still open at the top of
  [group-wavelength-ui.md](./group-wavelength-ui.md) — a forfeit signed for a made-up round, a Lightning
  claim with no deadline a device can work out, a personal wallet that stops renewing — are untouched by
  all ten phases.

## Appendix: guide rules considered and not taken

| the guide says | why not here |
|---|---|
| A unified BIP 321 request, so the member never picks a rail | The rails differ in who is trusted, not in speed and fee. B4 |
| Default to bitcoin with 8 decimals on-chain | Its own units page recommends sats for small amounts, and `Sats.kt` has the stronger argument for a signer reading integers |
| ₿-only integers, deprecating *sats* | The guide calls it a proposal with no clear consensus |
| Automatic encrypted cloud backup for small amounts | There is no single key to back up. One of four shares, and the recovery route is the group |
| A recovery phrase, shown and confirmed word by word | Same. The Iceberg share is not a phrase and is not the member's alone |
| A PIN before revealing a hidden balance | The app has no PIN anywhere. The guide calls it optional; Phase 3 ships hold-to-reveal without one |
| A dummy wallet behind a second PIN | Same reason, and a group wallet with fake data in a real room is a worse idea than the guide's single-user case |
| Spending limits the holders change with a signing-device approval | The cap is a build constant (`GroupWavelengthRollout.capFor`) and making it group-settable is a policy change, which this plan is barred from |
| Contacts, with a card for the wallet owner | The room's members are the contacts, and `localParticipants` already names them. A contact list would be a second one |
| Address reuse highly discouraged, a new address per request | Already done: the operator issues one per request and `paid-requests.md` stops a paid one being shown |
| Push notifications for every received payment | B7 holds notifications to the two urgent triggers. A deposit becoming a coin is visible on the screen and is not time-critical |
| Replace-by-fee and child-pays-for-parent to speed a pending payment | Neither reaches an Ark round, and the member's device does not build the transaction |
| Warn when the fee is 50% or more of the amount | Worth taking, and it belongs in `GroupPayoutsScreen` with the other wallets rather than in a Wavelength-only phase. Noted for the payouts screen, not planned here |
