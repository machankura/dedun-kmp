# A restored device asks for high-water marks, and the share repair gets wired

Remediation for one security-review finding, against the group Lightning wallet
in [../group-lightning-wallet.md](../group-lightning-wallet.md) (Phase 9's
co-signer, and Phase 1's repair). Two halves: **(A)** the restored-device
high-water-mark collection the design doc promised and never built, and **(B)**
a wire protocol for the share repair that `IcebergDkg` already implements and
nothing calls.

## The finding

**A restored co-signer answers from an empty ledger.** The design doc admits it
(line 83): the label ledger is never pruned, a device restored from a stale
backup starts over with whatever the backup held, and the planned high-water-mark
collection from `t` other participants is unimplemented. The code matches the
admission. Everything a co-signer knows about what it already did lives in four
Room tables (`IcebergCoSigning.kt`): the label ledger
(`IcebergLabelLedger`, first-write-wins at `IcebergCoSigningDao.kt:39-41`), the
per-channel ledger (`IcebergChannelLedger`, `:68-76`), the echoes it saw
(`IcebergEchoRow`) and the budget it recorded as spent (`IcebergBudgetSpend`,
`:78-84`). A restore takes them all back to the backup's point in time, or to
empty.

The attack, step by step:

1. The group's device D is backed up (an OS or app backup of its database) at
   time T. The group keeps operating. D echoes and signs label `L` under
   message `M`, its channel ledger advances to commitment `N`, and it records a
   payment's budget as consumed.
2. D's phone is lost and restored from the backup. Its share is present; its
   ledgers are as of T, or absent.
3. The operator — assumed hostile throughout (decision D7) — sends a signing
   request for label `L` under a *different* message `M'`. At
   `GroupLightningCoSigner.kt:203-207` the ledger check finds no row, so the
   restore is invisible; the request passes every other check, because they all
   consult the same missing state. D echoes `M'` and, once `n-t+1` echoes land
   (`:237`), signs. Two different messages under one label is the scheme's one
   way to leak a share (`iceberg.md`; the doc's constraint 5).
4. The echo rule is what saves the group in the simple case: honest devices
   still hold their ledgers and refuse to echo `M'`, so a second message gets
   signed under `L` only if `n-2t+1` other devices are also wrong — for a 2-of-4
   group, one more. That margin is thinner than it looks, because restores
   cluster (one OS bug, one bad update, one cloud-backup habit can stale
   several devices at once).
5. Finding 1's hole compounds it. The force-close fence runs only under
   `pinned?.let { … }` (`GroupLightningCoSigner.kt:345`): a restored device has
   no channel pin, so it signs *any* local commitment a force-close
   authorisation covers, including deeply revoked ones. See
   [force-close-revoked-commitment.md](./force-close-revoked-commitment.md).
   The same `pinned == null` empties the whole commitment policy on the first
   signature (`:399`); see
   [first-commitment-policy.md](./first-commitment-policy.md).
6. A second, quieter consequence the review surfaced: budget consumption is
   recorded per device (`GroupLightningAuthorisations.consume` into
   `IcebergBudgetSpend`). A restored device has an empty spend table, so a
   settled payment's authorisation regains its full budget on that device and a
   second attempt's HTLC passes the budget check there. Other devices refuse,
   so the echo rule again bounds it — unless they were restored too.

Re-echoing the *same* `(label, message)` is safe: the partial signature is
deterministic, so the replayed answer is byte-identical. The danger is only a
different message under an answered label, and everything below is shaped by
that.

**The share repair is dead code.** The design doc presents repair as "v1's
recovery" (Phase 1, line 469: *"Repair comes free, and is v1's recovery"*).
`IcebergDkg.repairSeeds` and `IcebergDkg.repair` exist and are tested
(`IcebergDkg.kt:273-300`; `IcebergDkgTest.kt:186-198`, including a lying helper
being refused). But a grep of `composeApp/src` finds no production call site —
only the test — and no kind in `GroupLightningEvents` carries a repair message.
A member who loses a share today has no recovery path at all: the wallet keeps
working while `2t-1` co-signers stay online, and every lost device is a
permanent subtraction from that number.

## Root cause

Both halves are the same shape: the design named the recovery mechanism, the
library-level piece was built and tested, and the app-level wire, state and
gates were never written. For marks, Phase 9's "a restored device is the
dangerous one" paragraph describes a protocol — who asks, who answers, what
they answer with — that has no message kinds, no DAO queries, and no gate in
`handleRequest`. For repair, `IcebergRitualManager` ends at `COMPLETE` and
nothing models a member whose session completed but whose share is gone. The
app also has **no restore marker**: a backup restore is indistinguishable from
a fresh install except by what the database fails to contain, so the trigger
has to be inferred from the co-signing tables themselves.

## The fix, in phases

### Phase 1 — high-water marks: kinds, collection, and the gate

**About a week. Wire kinds, a schema version, and the co-signer gate.**

**Goal.** A co-signer that cannot account for its own history refuses to echo
or sign until `t` other participants have told it where their history ended,
and afterwards answers only above the maxima.

**New kinds,** taking the next four past `GroupLightningEvents.kt:68` (the
family is one unbroken run, asserted by `GroupLightningEventsTest`'s
`the family is one unbroken run from 30350 to 30366`, which becomes
30350-30370):

| kind | from | carries |
|---|---|---|
| 30367 `MARK_REQUEST` | a co-signer in restore | the wallet key; nothing else — the answer is self-describing |
| 30368 `MARKS` | participant | per-channel `(channelId, fundingOutPoint, remoteFundingPubKey, commitIndex)`, the highest pool counter it has echoed, and per-payment-hash consumed budget |
| 30369 `REPAIR_REQUEST` | a member that lost its share | the ceremony session id |
| 30370 `REPAIR_SEEDS` | helper | the seeds it shares with the requester, NIP-44 encrypted to the requester's nostr key |

30367-30368 join `CO_SIGNING` and route to `GroupLightningCoSigner.process`;
30369-30370 get their own `REPAIR` set routed beside `CEREMONY` in
`NostrDao.kt:1638`. All four are MLS inner events like the rest of the family;
none is ever broadcast.

**The trigger.** A device cannot detect "I was restored" directly, so the gate
is inferred, conservatively, from state the device already holds:

> Gate the wallet when this device holds a completed ceremony and a verified
> statement for it, its own `IcebergLabelLedger` is empty for the wallet, and
> some *other* participant's echo exists for the wallet (an
> `IcebergEchoRow` from someone else — an echo exists only if a co-signer
> checked a request and answered, so this is room evidence of signing the
> device cannot account for) — or when a repair just completed (Phase 2 sets
> the same flag).

The alternatives were considered: *missing `IcebergKeySession`* is the repair
case, not the stale-ledger case; *empty label ledger alone* gates every honest
late-joining device forever in a group that never signs; *"empty ledger while a
statement exists"* fires for every fresh participant of a wallet that has
signed, which is exactly the restore signature — the echo witness is what
separates "never signed" from "must have signed". A fresh group produces no
echoes, so no one is gated spuriously at open. A false positive (a device that
was merely offline through every past signature) costs one marks round and is
correct, just slower.

**The gate.** In `GroupLightningCoSigner.handleRequest`
(`GroupLightningCoSigner.kt:176`), before check 1: if the wallet is gated,
refuse — log it and answer nothing. `provision` (`:126`) is *not* gated:
round-one contributions for fresh counters give nothing away and the restored
device must keep its pool published, or the group loses a contributor. Echoing
and signing are what the gate stops.

**What a mark truthfully carries.** Everything is read off the reporter's own
`IcebergCoSigningDao`:

- **Per channel:** every `IcebergChannelLedger` row (`channelLedger`, `:61`),
  contributing `channelId`, `fundingOutPoint`, `remoteFundingPubKey` and the
  `commitIndex` inside its stored ledger JSON (`load`, `GroupLightningCoSigner.kt:103`).
  Not `oursMsat`, not the floor: the restored device re-seeds its floor from
  the first commitment it signs after the restore (`CoSignerPolicy.open`,
  `CoSignerPolicy.kt:58`), which is conservative in msat; what it cannot
  reconstruct is the *commitment number*, and that is what the mark is for.
- **Per wallet:** the highest pool counter the reporter has echoed. Labels are
  `poolLabel(icebergKey, counter)`, so this is recoverable today only by
  scanning the ledger against recomputed pool labels up to `highestNonce`.
  Instead, **schema 32 → 33** adds a nullable `counter` column to
  `IcebergLabelLedger` (filled at insert from the request, null on legacy
  rows — a device with nulls reports what it can and the gate stays up if that
  is not enough, see below) and a new `IcebergRestoreState(wallet, gatedAt,
  marksFrom, liftedAt?)` table recording who answered and when the gate lifted.
- **Per payment hash:** every `IcebergBudgetSpend` row, closing the
  budget-refill replay above.

**Collecting and applying.** The gated device sends one 30367 and files every
30368 whose sender is a statement participant. When marks from `t` distinct
participants are in, it takes, per channel and per counter, the **maximum**
across reports. Among `t` reporters at most `t-1` are corrupt, so at least one
is honest, and one honest report puts the maximum at or above the true
high-water mark — under-reporting cannot lower it below the truth. Then:

- each reported channel becomes an `IcebergChannelLedger` row marked as
  marks-derived (the pin is real: same funding-outpoint pinning, and the
  force-close fence at `:345` now has a `commitIndex` to fence with);
- reported budgets seed `IcebergBudgetSpend` at the maximum consumed;
- the device refuses any label whose counter is at or below the maximum echoed
  counter, and any remote commitment whose index is at or below a channel's
  mark;
- a channel no mark names is treated as never signed: a first commitment
  (index 0) may proceed, anything else is refused.

`IcebergRestoreState.liftedAt` is set, and the gate comes down.

**Interaction with the echo rule and the fence.** None, structurally — the
echo rule (`:237`) is untouched; marks only decide what this device will echo.
Their job is to make the restore look like the ledger was never lost: the pin,
the fence and the budget checks all read the same tables as before. The two
plans that fix the `pinned == null` holes —
[first-commitment-policy.md](./first-commitment-policy.md) and
[force-close-revoked-commitment.md](./force-close-revoked-commitment.md) —
must treat a marks-derived row as a real pin; ordering this phase ahead of
either is fine, but the three are one deployable unit.

**Tests** (four-device harness, `composeApp/src/jvmTest/kotlin/com/machankura/dedun/compose/managers/`, beside `GroupLightningCoSigningJvmTest.kt`):

- *a restored device answers nothing before the marks*: sign commitments to
  advance every ledger, wipe device 4's co-signing tables, replay the room's
  events; device 4 gates, and a new signing request gets no echo and no share
  from it.
- *a restored device answers only above the marks*: after collecting marks from
  `t = 2`, a request at a counter at or below the mark is refused, a request
  above is signed; a remote commitment at or below the marked index is refused.
- *a lying reporter cannot lower the mark*: `t-1 = 1` device reports counter 0;
  the honest report still sets the maximum, and the restored device refuses the
  replayed label.
- *an inflated mark only wedges*: a reporter claiming counter 2^40 leaves the
  restored device refusing everything until the pool passes it — asserted as a
  refusal, not a signature (the DoS side, cross-referenced in
  [griefing-surface.md](./griefing-surface.md)).
- *budgets survive the restore*: a payment's budget spent before the wipe is
  still spent after the marks, and a second HTLC under its hash is refused.
- `GroupLightningEventsTest` asserts the run is now 30350-30370, and a
  device on the old build ignores 30367-30370 (the pattern the ceremony test
  already uses).

### Phase 2 — the share repair, over the wire

**About four days. Wire kinds from Phase 1, no schema changes of its own.**

**Goal.** A member whose `IcebergKeySession` completed but whose `share` is
null asks the room; the other participants resend the seeds they share with it,
encrypted to its nostr key exactly as round 2 did it; the rebuilt share is
accepted only if it reproduces the public share the session published.

**Flow.**

1. **Request.** The device holds the session row with `publicShares` and
   `groupPublicKey` set and `share` null. It publishes 30369 naming the session
   id. Requesting needs no human gate: it reveals nothing (the session id rides
   on every ceremony message already) and the requester can receive only its
   own seeds.
2. **Helper.** On a 30369, a device checks: it is a participant of that
   session, its own `share` is present, and the sender is a participant
   (`indexOf` the session, as `IcebergRitualManager.kt:268-282` does for the
   round kinds). Then it asks its owner. **Sending needs a person**: the helper
   exports share material, and although NIP-44 to the member's nostr key means
   only that key's holder can read it, a stolen nostr key plus a repair request
   is a share-exfiltration path, so a human confirms the member actually lost
   their device. This is a fourth approval beside the ceremony's three
   (`announceApprovalNeeded`, `IcebergApprovalStep` gains a value). On approval,
   `IcebergDkg.repairSeeds(params, me, share, requesterIndex)` (`IcebergDkg.kt:273`)
   and NIP-44 per the round-2 pattern (`IcebergRitualManager.kt:312-318`,
   `Nip44.encrypt` from the helper's nostr key to the requester's), out as
   30370.
3. **Rebuild.** The requester files every 30370 addressed to it (decrypt
   failures are dropped, as `revealsTo` does), and once the seeds it holds are
   covered calls `IcebergDkg.repair(params, me, received, session.publicShares[me-1])`
   (`:288-300`). Helpers' seeds must agree per rank and the result must
   reproduce the published public share — the check the design doc's Phase 1
   specified, and the one `IcebergDkgTest.kt:193-198` already pins. On success
   the share is stored on the session row and the device enters Phase 1's
   marks gate: **a repaired share always implies the marks flow**, because a
   lost share and a stale ledger are the same restore.
4. **What repair does not restore.** The node seed has its own path: the
   restored participant asks the operator for it and checks it against the
   statement's `node` tag (Phase 6, `GroupLightningWalletManager.receiveSeed`).
   The two are independent; the transcript line for a completed repair says so.

**Where it lives.** A `repair` section in `IcebergRitualManager` (it owns
sessions, participants and the NIP-44 plumbing), with its wire shapes beside
the ceremony's `Round1/2/3` serializers, and routing in `NostrDao` for the
`REPAIR` set. No fork changes, no schema changes: `IcebergParticipantMessage`
can carry the two kinds, or they are read straight off the inner events as the
co-signing messages are.

**Tests** (four-device ceremony harness, beside `IcebergCeremonyJvmTest.kt`):

- *a lost share is repaired over the wire*: after a completed 2-of-4 ceremony,
  null device 3's share; it requests, two helpers approve and answer, and the
  rebuilt share equals the original and reproduces the published public share;
  device 3 then co-signs a real commitment in the co-signing harness.
- *a repaired device is gated*: the same device answers no signing request
  until Phase 1's marks arrive — the two flows compose.
- *a lying helper is refused*: one helper's payload altered; `repair` returns
  `Fault.RepairMismatch`, the share is not stored, and the honest helpers'
  answers still complete the repair.
- *gates*: a helper sends nothing before its owner approves; a non-participant's
  30369 is ignored; a 30370 from a non-participant is dropped.
- *an old build ignores 30369-30370* — which is also the deployment story: a
  group whose helpers all run old builds simply has no repair yet.

## What this does not fix

- **The `pinned == null` holes themselves.** Marks give a restored device a pin
  and a fence; they do not repair the one-behind revocation hole
  ([force-close-revoked-commitment.md](./force-close-revoked-commitment.md)) or
  the first commitment skipping the policy
  ([first-commitment-policy.md](./first-commitment-policy.md)). Those plans own
  those.
- **An operator that re-assigns a counter after its own restore.** The
  operator's `IcebergLabelAssignment` table is restorable too; a re-issued
  counter is refused by every co-signer whose ledger (or marks) covers it, so
  this plan reduces it to liveness, but the transport's persist-before-return
  contract is [signer-hygiene.md](./signer-hygiene.md)'s to enforce.
- **Marks are claims.** `t-1` lying reporters can inflate marks and wedge a
  restored device into refusing everything — a one-time DoS against one device,
  attributable to the senders. It is listed with the rest of the griefing
  surface in [griefing-surface.md](./griefing-surface.md).
- **A group that cannot muster the marks.** If fewer than `t` other
  participants are reachable (or on the new build), the gate never lifts and
  the device sits out; the wallet meanwhile needs `2t-1` co-signers online, so
  mass restores are an availability problem this plan does not pretend to
  solve.
- **The seed and HTLC exposure of a restored device**
  ([node-seed-in-flight-exposure.md](./node-seed-in-flight-exposure.md)), and
  the failure-broadcast plaintext leak the review found in `revealsTo`
  ([signer-hygiene.md](./signer-hygiene.md)) — both adjacent, both separate.

## Deployment

New wire kinds (30367-30370), one schema version (33), no fork changes. As the
kinds section of the design doc puts it for the family at large: every device
in the group needs the new build **before the group relies on marks**. A
co-signer on an old build never answers a 30367, so a restored device in a
2-of-4 group needs both other helpers upgraded before its gate can lift; a
member whose helpers all run old builds has no repair. The ceremony-screen
sentence about devices that have not answered gains a mark/repair counterpart:
the Lightning wallet screen shows a gated device as *restored — waiting for
marks from the group*, and a repair in progress as such, so the group knows why
a co-signer went quiet rather than reading it as offline.

## Risks and open questions

| risk or question | why it matters | what to do |
|---|---|---|
| the trigger misfires on an honest but always-offline device | it is gated and must wait for marks it does not need | harmless: the marks round is one request and `t` answers, and the device was answering nothing anyway |
| legacy `IcebergLabelLedger` rows have no `counter` | a reporter whose history predates schema 33 can report only per-channel marks | the gate requires a counter maximum from at least one reporter whose rows carry it; a group where every device predates 33 keeps the gate up until any new signing — acceptable, and said on the screen |
| inflated marks as griefing | one reporter can wedge a restored device until the pool outgrows the lie | attributable (the mark names its sender), bounded, and listed in griefing-surface.md; refusing marks from non-participants is already in the flow |
| the helper approval is skipped socially | "Thandi lost her phone" is exactly what an attacker holding Thandi's nostr key would say | the approval sheet says to confirm out of band; the repaired share still cannot sign alone, so the cost of a wrong approval is bounded by the threshold, but it erodes it by one |
| should marks be re-collected after every restore, or cached? | `IcebergRestoreState.liftedAt` caches the lift; a *second* restore wipes it, which re-gates | intended: the gate is state in the same database whose loss it guards, so a restore re-arms it automatically |
| marks and the pending `pinned == null` fixes disagree about what a mark-seeded row is | three plans touch `checkRemote`/`checkLocal`'s null handling | the order in this file (marks first) is the recommended one; whichever lands second treats marks-derived rows as real pins |
