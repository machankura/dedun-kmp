# The group Lightning wallet in the chat

How the group Lightning wallet comes to read, in a room's transcript, the way
every other piece of group business already does: its key ceremony as a ladder of
lines and a standing ask above the composer, and the operator's traffic as
sentences rather than raw events.

**Built**, phases 1–6, one commit each: `638c5aab`, `03833a1c`, `3922c15d`,
`1b4ed097`, `3ec79b95`, `a660fabd`, and Phase 7 is the commit that changed this
paragraph. Written against `515ddea2`. The phases are kept as written; where the
build chose differently the table below says so.

| what the plan said | what it turned out to be |
|---|---|
| a manual migration 32 → 33, and Phase 5's column in it if both landed together | two steps: `MIGRATION_32_33` deletes the bubbles and changes no shape, and v34 is an `AutoMigration` that adds `ChatMessage.lightningFactKey` |
| `AwaitingCeremony` gains a `kind` and a small sealed step | one sealed `Ask` replaces both `step` and `roomAwaitingCreation`: `SharedKeyStep`, `RoomCreation`, `LightningKeyStep`, `LightningWalletOpening`. A kind beside a step could disagree with it; the ask cannot |
| the unused-key notice is asked of any member who holds a share | of whoever opened the ceremony only, as ChillDKG's room creation is. Every holder being asked to open the wallet is how a room gets four proposals for one wallet |
| the chat's view model takes a `GroupLightningRepository` | and so does every screen between it and the nav host. `GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY` joins the other repositories' no-ops, for the previews and the screen tests |
| `RitualNotice`'s chevron says what each family opens | and the families that lead nowhere draw none and are not clickable. The approved tick moved to request lines only: a chronicle line, passed as answered to keep it quiet, was showing "You approved this" |
| the invoice request line drops its accent once answered | it never has one. Nobody but the operator's node can answer it, so it is a quiet line like the rest, and the node's answer is a line of its own naming whoever asked |
| the authorisation line names a closing channel's peer and amount | unchanged. The line is written from the signed event alone, before any snapshot that would say the amount; Phase 5's closed line says it when the channel does close |
| a channel's balance disagreeing with this device's ledger is an error line (Phase 6) | not built. The wallet screen already flags it per channel, and a snapshot can lag a commitment the device has just signed, so a line would raise alarms that settle themselves a minute later |
| refusals are said once per request | from `check()`'s own reasons, which were already sentences. A request from a member who is not the operator is still only logged: after a handover the old operator's requests are in flight for a while, and each would be a line |

Every phase was checked with `:composeApp:jvmTest` (1,598 tests; one,
`IdentitySwitchJvmTest`, timed out under the full run's load and passes alone),
`:composeApp:compileDebugKotlinAndroid` and `m3-audit.sh --check`.

The regtest run (`GroupLightningRegtestJvmTest`) now checks the transcript too: on every
device, one opened and one closed line per channel, one line per payment, the seed, the
invoice pair, and no raw JSON, refusal or alarm. Its first run found two things Phase 5's
unit test had not: lightning-kmp files a channel's close as an outgoing payment with no
payment hash, which wrote a "sent" line beside each closed one, and an outgoing payment's
amount includes its fee, so 20,000 sats with an 84-sat fee read as 20,084 sent. Both are
fixed, and the unit test's payments are now shaped as lightning-kmp reports them. The
wallet screen's payment list read the same two fields the same way, and now reads them
through the same two rules, `GroupLightningReports.isChannelClose` and `recipientMsat`: a
close is listed as a close, or as closing until it confirms, and a sent payment as what
reached the recipient with the fee beside it. A look at a room on a device has not been done.

Read
[group-lightning-wallet.md](./group-lightning-wallet.md) for the wallet itself and
[shared-key-ceremony.md](./shared-key-ceremony.md) for the transcript conventions
this plan holds the Lightning wallet to. The UI rules in
[CLAUDE.md](../CLAUDE.md) apply to every phase.

The headline: **the Lightning wallet's ceremony lines already exist and already
render. What a member sees instead is raw JSON, because fourteen of the wallet's
seventeen kinds — all but the three the group signs — also reach the
"unsupported" arm and get a bubble of their own. One dispatch check and a migration remove that.
The rest is the work ChillDKG had done for it and the Lightning wallet never got:
the standing notice above the composer, and lines for what the operator says.**

---

## What is already there

| fact | where | what it means here |
|---|---|---|
| A Marmot inner event is held back from the transcript only if its kind is in `DkgRitualEvents.ALL` or `FrostSigningEvents.ALL` | [ChatMessage.kt:946](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt) `isProtocolMessage` | `GroupLightningEvents` is in neither, so every Lightning kind goes on to `applyInnerEvent` |
| `applyInnerEvent` handles the three group-signed kinds (30355, 30356, 30363) and sends everything else to `unsupported()`, a bubble of `event.toJson()` | [ChatMessage.kt:1224, 1530](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt) | the ceremony's 30350–30354, the co-signing 30358–30361 and the wallet traffic 30357, 30362, 30364–30366 each leave a raw-JSON bubble, attributed to whoever sent it |
| The same events are *also* acted on, after the line is written | [NostrDao.kt:1585–1648](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/dao/NostrDao.kt) | the managers already work; only the transcript is wrong. A ceremony therefore shows its readable lines *and* a JSON bubble beside each |
| `IcebergRitualManager` writes `TYPE_ICEBERG_*` lines, request lines included, and `ChatTranscript` renders them as `RitualNotice`s that open `IcebergCeremonyScreen` | [IcebergRitualManager.kt:464–506](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/IcebergRitualManager.kt), [ChatTranscript.kt:285](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/chat/ChatTranscript.kt) | the readable half of the ceremony is built; it is buried, not missing |
| The co-signing traffic is high-volume: a nonce batch per pool window per member, an echo per member per signing request, a share per member per label | [GroupIcebergTransport.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupIcebergTransport.kt), [GroupLightningCoSigner.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupLightningCoSigner.kt) | a room with a running node fills with JSON faster than anyone talks in it, and the room list's preview reads the newest row's `content` (`ChatRoomDao`), so it shows JSON too |
| `ProtocolKindDispatchJvmTest` asserts "no chat line" for `DkgRitualEvents.ALL` and `FrostSigningEvents.ALL` only | [ProtocolKindDispatchJvmTest.kt:120, 169](../composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/database/model/ProtocolKindDispatchJvmTest.kt) | nothing caught this. The third family needs the same test |
| `MIGRATION_26_27` deleted the `unsupported` rows written for the ChillDKG kinds, found by the kind of the inner event behind them | [RemoveDocumentsMigration.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/migrations/RemoveDocumentsMigration.kt) | the exact precedent for cleaning rooms that already hold the bubbles. The schema is at 32 |
| The standing notice above the composer is fed by `ceremoniesAwaitingYou`, which observes `dkgRepository` only, and its card always calls `onOpenSharedKey` | [ChatMessageListViewModel.kt:112–140, 267–291](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/view/model/ChatMessageListViewModel.kt), [ChatTranscript.kt:203, 613](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/chat/ChatTranscript.kt) | an Iceberg gate never reaches it. The member has to scroll to the request line, which the JSON is burying |
| `IcebergRitualManager.pendingApproval` and `GroupLightningRepository.pendingApproval` / `observeCeremonies` / `hasUnusedKey` exist | [IcebergRitualManager.kt:402](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/IcebergRitualManager.kt), [GroupLightningRepository.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/repository/GroupLightningRepository.kt) | everything the notice needs is already exposed, the same way the ChillDKG notice reads `pendingApproval` |
| No Lightning manager writes a chat line. A co-signer's refusal and an operator's equivocation are `logger` calls | [GroupLightningCoSigner.kt:193, 232](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupLightningCoSigner.kt) | the wallet doc promises both as transcript lines ("A person only sees a refusal, as a transcript line"); neither is |
| The operator line says "The group chose who runs its Lightning wallet" without saying who | [ChatMessage.kt:1464–1483](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/model/ChatMessage.kt) | the one fact the line exists for is missing. The member-wallet lines already name people with `memberName` |
| A ceremony abandoned by somebody else is recorded as `"Abandoned by ${sender.take(8)}: ${content}"`, and a local fault as the exception's message; the screen shows `failureReason` verbatim | [IcebergRitualManager.kt:280, 365](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/IcebergRitualManager.kt), [IcebergCeremonyScreen.kt:200](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/IcebergCeremonyScreen.kt) | a key prefix and an exception string where ChillDKG shows a name and a sentence |
| The Iceberg gates are an inline card on the ceremony screen; ChillDKG's each have a screen of their own on `DkgApprovalScaffold` | [IcebergCeremonyScreen.kt:210–233](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/IcebergCeremonyScreen.kt), [DkgApprovalScaffold.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/DkgApprovalScaffold.kt) | a difference in shape, not a defect. Decision D5 |
| `RitualNotice`'s chevron says "Open the shared key ceremony" for every family it draws, signing lines and Lightning lines included | [ChatTranscript.kt:972](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui/composable/widgets/chat/ChatTranscript.kt) | a screen reader is told the wrong destination |

## Decisions

**D1. Every Lightning kind is protocol, not content.** `GroupLightningEvents.ALL`
joins the `isProtocolMessage` check, and `applyInnerEvent` gets an arm that
returns null for the non-signed kinds above every content arm, as it does for
the ChillDKG and FROST families. The three group-signed kinds keep their arms:
they reach `applyInnerEvent` from `FrostSigningManager` with a
`groupSignedEventId`, not as inner events, and their lines are right. Why not
write the lines in `applyInnerEvent`: the ChillDKG rule. The manager that acts on
a message is the one that knows what it meant, and it writes the line.

**D2. One line per fact, not one per message.** The ceremony already follows
this, one line per member per round. The operator's traffic gets lines only for
what a member would tell another member: the node seed handed out, an invoice
asked for and given, the operator stopping, a channel opening or closing, a
payment settling, a co-signer refusing. Nonce batches, echoes, signature shares
and signing requests get no line, ever. They are to the Lightning wallet what
the nonce messages of a signing session would be if a session had thousands of
them.

**D3. A device writes its own line when it sends.** A member's device cannot read
back its own MLS messages, so an authored line that waited for the event to come
back would appear for everybody but its author. `IcebergRitualManager.publishOwn`
already does this; `GroupLightningWalletManager.queue` gains the same, so the
invoice request, the stop and the seed are written on the way out.

**D4. Snapshot facts are written once, from what this device files.** A snapshot
arrives every few minutes and restates the whole wallet. Lines come from the
*difference* `GroupLightningReports.file` already computes, or can: a channel id
it has not held before, a channel whose state turns to closing or closed, a
payment row that first gets a `completedAt`. The precedent is
`TYPE_GROUP_TRANSACTION_CONFIRMED`, which is written once per transaction from a
table that remembers it was. A snapshot from an earlier term, or one that
re-delivers, writes nothing new.

**D5. The Iceberg gates stay on the ceremony screen.** ChillDKG's approval screens
exist because its host-key step shows the joiner a participant list it has to
check, and round 2 shows a certificate. Iceberg's set is fixed and checked
by the device before any gate, so each of its gates is one sentence and one
button, which the inline card already is. What the notice and the request lines
need is for the *words* to match, which they can without a new screen. If a
later review wants the screens, `DkgApprovalScaffold` takes them unchanged.

**D6. The transcript content stays written into the row, in English.** Every
ritual family writes its sentence at write time; the transcript does not
re-render types. Moving the Lightning lines alone to the string catalogue would
make them the one family that differs. The *notice* is UI text and goes in
`strings.xml`, in sentence case, as `CeremoniesAwaitingYouNotice`'s does.

**D7. Lightning lines are one family, `LIGHTNING_TYPES`, beside `ICEBERG_TYPES`.**
Not folded into `GROUP_IDENTITY_TYPES`, because those are things the group signed
and are passed as answered and settled with no destination. These lead
somewhere, to the Lightning wallet screen, and some are authored by a member.

---

## Phase 1: no more raw JSON

The smallest change that fixes both complaints' worst symptom. Nothing new
appears; what was never meant to be a line stops being one.

- `ChatMessage.fromGroupEventResult`: `event.kind in GroupLightningEvents.ALL`
  joins `isProtocolMessage` for every kind not in
  `GroupLightningEvents.GROUP_SIGNED`. The inner event is still stored, which is
  what the managers replay from.
- `ChatMessage.applyInnerEvent`: an arm `in GroupLightningEvents.CEREMONY +
  CO_SIGNING + WALLET_TRAFFIC -> null`, beside the `DkgRitualEvents.ALL` one and
  above every content arm, with the same comment about first-match order.
- A manual migration 32 → 33, as `MIGRATION_26_27` did: delete `ChatMessage`
  rows whose `messageType = 'unsupported'` and whose `marmotInnerEventId` is a
  `MarmotInnerEvent` of kind 30350–30366 except 30355, 30356 and 30363. The
  kinds are written out in the migration, not read from `GroupLightningEvents`,
  for the reason the earlier one gives.
- `ProtocolKindDispatchJvmTest`: "a Lightning wallet message writes no chat
  line" over every non-signed kind, and a migration test that seeds one row of
  each kind plus an `unsupported` row over an unknown kind and checks only the
  first lot goes.

**Done when** a four-device ceremony test and the co-signing test leave no
`unsupported` row in the room, and a room upgraded from 32 holds none for these
kinds. The Iceberg lines are then the only trace of a ceremony.

## Phase 2: the Lightning key ceremony asks from above the composer

ChillDKG's "The key ceremony needs you" card, for the Lightning wallet's key.

- `ChatMessageListViewModel.AwaitingCeremony` gains what it is a ceremony *of*:
  a `kind` (`SHARED_KEY`, `LIGHTNING_KEY`) and a step that can be either family's.
  A small sealed `AwaitingStep` holding a `DkgApprovalStep?` or an
  `IcebergApprovalStep?` keeps the ChillDKG branch exactly as it is.
- The view model takes a `GroupLightningRepository` (its factory and the two
  call sites that build it) and a second observer, `observeLightningCeremonies`,
  over `observeCeremonies(roomId)`: every session not `FAILED`, whose
  `pendingApproval` is non-null. The two observers feed one list, ChillDKG's
  first so the order within a room is what it is today.
- The ChillDKG notice's second kind of waiting, the coordinator's
  `roomAwaitingCreation`, has a Lightning counterpart: a completed key that no
  wallet statement uses yet (`hasUnusedKey`) and that this member holds a share
  of. The notice says the group has its Lightning key and opens the Lightning
  wallet screen, where *Open the wallet* is. It stops being asked for the moment
  a statement is proposed. Only on a network `GroupLightningRollout` offers.
- `CeremoniesAwaitingYouNotice`: a `Bolt` icon for the Lightning key in place of
  `Key`; headline "The Lightning wallet's key ceremony needs you"; the detail in
  the words the ceremony screen's buttons already use (`join_the_ceremony`,
  `send_your_contributions`, `confirm_what_you_saw`, all in the catalogue). With
  several ceremonies across both families the count stays the summary, as now.
- `ChatTranscript` takes the click by kind: `onOpenSharedKey` for one,
  `onOpenIcebergCeremony` for the other, and a new `onOpenLightningWallet` for
  the unused key. `ChatRoomMessagingScreen` passes the route it already builds
  for the ceremony lines.
- Tests: the view model with a fake repository, a session at each gate and at
  none, a failed session, and a completed unused key, held and not held.

**Done when** a member owed the Lightning wallet's join, reveal or confirm sees
the card under the newest message without scrolling, a tap lands on the
ceremony that wants them, and the card is gone once they approve.

## Phase 3: the ceremony's own lines, finished

Small, and what makes the ladder read like ChillDKG's.

- A failure names a person, not a key prefix. `record`'s remote-abandon branch
  stops writing `sender.take(8)` into `failureReason`; the line already has the
  actor in `senderPublicKey`, and the reason is the sender's content.
- Local faults are said as sentences. `IcebergDkg.Fault`'s cases
  (`MissingCommitment`, `MissingReveal`, `BadReveal`, `MalformedMessage`,
  `InconsistentShares`) and `advance`'s two `check`s (a disagreeing transcript, a
  wrong public share) map to one sentence each, naming the member at fault through
  `Fault.participant` where there is one, in the shape ChillDKG's blame lines use.
  `failureReason` keeps the raw message for the log.
- `IcebergCeremonyScreen` shows that sentence in place of `abandoned_s` with the
  raw reason, and names the culprit from the room's participants.
- `RitualNotice`'s chevron description follows the family: the shared key
  ceremony, the Lightning wallet's key ceremony, the signing proposal, the
  Lightning wallet. Each one a catalogue string.
- The JOIN request line for the member who opened the ceremony: today they are
  approved at proposal time and get no request line, which is right. Asserted in
  a test so it stays right.

**Done when** a ceremony abandoned on another device reads "@name abandoned the
Lightning wallet's key ceremony" with the reason in a sentence, on both the line
and the screen.

## Phase 4: what the operator and the members say to each other

The direct traffic outside the two protocols, as lines. New types on
`ChatMessage`, in a `LIGHTNING_TYPES` set with an `LIGHTNING_AUTHORED_TYPES`
subset (D7):

| kind | type | line | written by |
|---|---|---|---|
| 30357 node seed | `TYPE_LIGHTNING_SEED_SHARED` | "@name gave each key holder the Lightning node's seed." | `GroupLightningWalletManager`, on send (D3) and on `receiveSeed` |
| 30364 invoice request | `TYPE_LIGHTNING_INVOICE_REQUESTED` | "@name asked the operator's node for an invoice of 21,000 sats." or "…for an invoice." with no amount | `GroupLightningReports.requestInvoice` on send; on arrival, every device |
| 30365 invoice | `TYPE_LIGHTNING_INVOICE_ISSUED` | "The operator's node made @name's invoice." | `GroupLightningReports.receiveInvoice`, only once the invoice passes the checks it already makes |
| 30366 stopped | `TYPE_LIGHTNING_OPERATOR_STOPPED` | "@name stopped running the group's Lightning node." | `GroupLightningOperatorManager.announceStopped` on send; a new arm in the `NostrDao` dispatch on arrival |

- The invoice request is a request, and the invoice answers it: both lines carry
  the request id, and the request line drops its accent once its invoice line
  exists, through the same `answeredRequests` shape, keyed on the request id
  rather than `isUserMessage`. Nobody but the operator's node can answer it, so
  it is never put above the composer.
- The operator statement's line names the operator: "@name runs the group's
  Lightning wallet now." / "Nobody runs the group's Lightning wallet now. It stops
  until someone is chosen." The term number leaves the sentence; it means nothing
  to a reader and is on the wallet screen.
- The authorisation line names the channel's peer and amount for a close where
  the snapshot this device holds can say them, and keeps today's sentence where
  it cannot.
- Dedupe: each line is keyed on the inner event's id (`marmotInnerEventId`),
  which re-delivery and negentropy repeat. The send-side row and the arrival row
  for the same event are the same row, so `reconcileMarmotLine`'s rule holds.
- `ChatTranscript`: a `LIGHTNING_TYPES` branch drawing `RitualNotice`, with icons
  (`Key` for the seed, `RequestQuote`/`Receipt` for the invoice pair, `PowerOff`
  for the stop), opening the Lightning wallet screen through
  `onOpenLightningWallet`.
- Tests: each line from a two-device exchange, its author's copy written once,
  and no line from an invoice that fails the operator check.

**Done when** a member can follow, from the room alone, who set the node up, who
asked to be paid, and who stopped running it.

## Phase 5: what the snapshots say happened

Lines from the operator's snapshots, written once per fact (D4). All in
`GroupLightningReports.file`, which already has the held snapshot and the new one
side by side.

| fact | type | line |
|---|---|---|
| a channel id this device has not filed before | `TYPE_LIGHTNING_CHANNEL_OPENED` | "The group's Lightning node opened a channel of 500,000 sats." |
| a held channel that turns closing or closed | `TYPE_LIGHTNING_CHANNEL_CLOSED` | "A Lightning channel closed. Its 480,120 sats go to the onchain wallet." |
| a payment row that first gets a `completedAt`, incoming, succeeded | `TYPE_LIGHTNING_PAYMENT_RECEIVED` | "The group's Lightning wallet received 21,000 sats." with the description where there is one |
| the same, outgoing | `TYPE_LIGHTNING_PAYMENT_SENT` | "The group's Lightning wallet sent 21,000 sats, with 12 sats in fees." |
| the same, failed | `TYPE_LIGHTNING_PAYMENT_FAILED` | "A Lightning payment of 21,000 sats failed. Nothing left the wallet." |

- No author: the node did these, and the operator is already named by Phase 4's
  election line. Attributing a payment to the operator would read as them having
  chosen it.
- A channel check that disagrees with this device's ledger (`ChannelCheck`) is
  not a line here. It is Phase 6's.
- Dedupe by the fact, not the event: a `chatMessageDao` lookup on
  `(chatRoomId, messageType, factKey)`, where `factKey` is the channel id or
  payment id. That is a new nullable column, `lightningFactKey`, in the same
  migration as Phase 1 if both land together, an `AutoMigration` if not.
- Payments on a busy wallet are many lines. Decision D8 below.

**Done when** a regtest run (`GroupLightningRegtestJvmTest`) leaves exactly one
opened line per channel, one line per settled payment and one closed line per
close, on every device, whatever the number of snapshots between.

## Phase 6: refusals and the equivocation alarm

What the wallet doc promised and the co-signer only logs.

- A co-signer's refusal (`GroupLightningCoSigner.handleRequest`, the `check`
  result) writes `TYPE_LIGHTNING_REFUSED` on *this device only*: "Your device
  refused to co-sign for the Lightning node: the channel would grow past the
  1,000,000 sat cap." One sentence per refusal reason, once per request id. It
  is not broadcast; another member's refusal is theirs to see.
- Two hashes echoed for one label writes `TYPE_LIGHTNING_EQUIVOCATION`, in the
  error tint, on every device that sees both: "The operator's node asked the
  group to sign two different things under one label. Nobody will sign it." Once
  per label.
- A channel whose `ChannelCheck` disagrees with this device's ledger writes one
  error line per channel per disagreement, naming the difference in sats.
- Both error types join the tint `when` in `RitualNotice` beside
  `TYPE_ICEBERG_FAILED`.

**Done when** the existing refusal tests and the equivocation test each assert
their line, and a second identical request writes no second line.

## Phase 7: the notes, the audit and a device run

- [group-lightning-wallet.md](./group-lightning-wallet.md)'s state table gains a
  row saying the transcript is done and pointing here; the "A person only sees a
  refusal, as a transcript line" sentence becomes true.
- This document's status line, and a "what the plan said / what it turned out to
  be" table as the other built plans have.
- `./gradlew :composeApp:m3Audit` and `check`: every new notice string in the
  catalogue, sentence case, no `contentDescription = null`.
- A four-device run on regtest: ceremony, wallet, election, an invoice, a
  payment each way, a close. Screenshots of the room and of the room list,
  neither showing JSON.

---

## Still to decide

**D8. A line per payment, or a summary.** One per settled payment matches the
onchain wallet's one per confirmed transaction, and a group wallet is not a
merchant's. A busy wallet would want "3 payments received today" instead, which
needs a day boundary and a line that updates. The plan writes one per payment;
the summary can come later without a schema change if the fact key is kept.

**D9. Whether a refusal is told to the group.** Phase 6 keeps it on the refusing
device, because a refusal names a check the operator failed, and the group
learns that the payment stalled from the snapshot. Telling the group would mean
a new broadcast kind; not in this plan.

## Order and size

Phase 1 alone fixes what a member sees today, and should land first and on its
own. Phases 2 and 3 are the ceremony and can go together. Phases 4 and 5 depend
on Phase 1's dispatch change and share `LIGHTNING_TYPES`; Phase 5 depends on
Phase 4 for the family and on D8. Phase 6 depends on Phase 4 for the family only.

| phase | touches | rough size |
|---|---|---|
| 1 | `ChatMessage`, a migration, `DedunDatabase`, one test file | small |
| 2 | `ChatMessageListViewModel`, `ChatTranscript`, `ChatRoomMessagingScreen`, strings | medium |
| 3 | `IcebergRitualManager`, `IcebergCeremonyScreen`, `ChatTranscript`, strings | small |
| 4 | `ChatMessage`, `GroupLightningWalletManager`, `GroupLightningReports`, `GroupLightningOperatorManager`, `NostrDao`, `ChatTranscript` | medium |
| 5 | `GroupLightningReports`, `ChatMessage` (column), `ChatTranscript` | medium |
| 6 | `GroupLightningCoSigner`, `ChatTranscript` | small |
| 7 | docs | small |
