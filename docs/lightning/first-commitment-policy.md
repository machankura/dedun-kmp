# The first commitment must pass the co-signer policy

A remediation plan for finding 3 of the security review: the first remote
commitment a device signs for a channel skips every value rule in
`CoSignerPolicy`. It interacts with [group-lightning-wallet.md](../group-lightning-wallet.md)
Phase 9, check 5, and with the floor-starting deviation at its line 80
("a floor that starts at the first commitment less the fee allowance").

## The finding

`GroupLightningCoSigner.checkRemote` runs its checks in this order
(`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupLightningCoSigner.kt`):

1. the mainnet channel cap, `:374-377`, which runs for every remote commitment,
   first or not;
2. the rebuild: `Commitments.makeRemoteTxs` from the request's `SigningContext`,
   byte-equality with the transaction asked to sign, `:384-395`;
3. then `:399`:

   ```kotlin
   if (pinned == null) return null
   ```

   The first commitment this device signs for a channel is approved there. Rule 2
   (every newly offered HTLC sits in an open FROST-signed authorisation with
   budget left, `CoSignerPolicy.kt:89-101`), the in-flight cap
   (`CoSignerPolicy.kt:105-106`, the statement's `inFlightCapSat`, default
   200,000 sats) and the floor (`CoSignerPolicy.kt:108-130`) are all behind that
   return — they are only reached through `checkRemoteCommitment` at `:402`, which
   needs the previous ledger `pinned` holds.

The write path is symmetric. `commitLedger` folds what was approved into the
device's channel ledger, and its `pinned == null` branch (`:430-431`) calls
`CoSignerPolicy.open` (`CoSignerPolicy.kt:58-62`), which builds the starting
ledger from whatever commitment 0 holds — offered HTLCs included — with no
authorisation read and nothing consumed. The floor starts at
`ours + offered − feeAllowance` (`:61`), so an unauthorised offered HTLC in
commitment 0 is not just signed, it is *banked* as the group's baseline.

**The attack, step by step.** It needs a hostile operator *and* the LSP the
statement names, colluding. It cannot work against an honest LSP: check 4 pins
`params.remoteParams.nodeId == statement.lspNodeId` (`:307`), on-the-fly funding
makes commitment 0 carry only the incoming HTLC, and the LSP validates its own
commitment before the channel is live. The review (co-signer report, finding C;
operator-wiring report, gap 1) could not construct an operator-only variant.

1. A payer pays the group — say 500,000 sats — which under on-the-fly funding is
   what opens the channel.
2. The operator's node and the colluding LSP negotiate the channel so that the
   LSP's first commitment (the one the group signs) carries, beside the incoming
   HTLC, an *offered* HTLC of 400,000 sats to a payment hash whose preimage the
   LSP holds.
3. Every co-signer runs `checkRemote`: the funding output is the group key with
   the LSP's (`:294-316`), the capacity is under `MAINNET_CHANNEL_CAP`
   (1,000,000 sats, `GroupLightningRollout.kt:38`), the transaction rebuilds
   from its context because the operator built a real one, and `pinned == null`
   approves it. No authorisation is ever consulted, because none exists.
4. Every co-signer echoes, `n − t + 1` echoes collect, and the group signs.
   `commitLedger` starts the floor at 100,000 + 400,000 − allowance.
5. The LSP fulfils the HTLC with its preimage. Of the 500,000 the group was
   paid, it keeps 100,000. The policy's own arithmetic now *defends* this
   outcome: the floor says the group is owed only what the attack left it.

The bound is the incoming amount itself, up to the channel cap — the in-flight
cap does not apply either, so this is larger than the exposure the statement's
cap exists to bound (the design doc's "the cap is the most the node seed can
ever be worth" assumes it does).

## Root cause

`open` was written as a pure ledger constructor — its doc comment is "whatever
it holds is where the floor starts" (`CoSignerPolicy.kt:57`) — and `checkRemote`
treats the first commitment as the definition of the floor rather than as a
commitment. The three value rules were placed inside `checkRemoteCommitment`,
which needs a previous ledger, so a commitment with no predecessor gets none of
them. But only rule 3's fall-versus-floor reasoning genuinely needs a
predecessor. Rule 2 and the cap degenerate cleanly: with `previous.offered`
empty, *every* offered HTLC is "added", and both checks are fully computable on
commitment 0. The exemption was never a structural necessity; it is a missing
branch.

## The fix, in phases

### Phase 1 — `open` runs rule 2 and the cap

**About a day. App only: no schema, no wire kind, no fork change.**

`CoSignerPolicy.open` (`CoSignerPolicy.kt:58-62`) gains the three arguments
`checkRemoteCommitment` already takes — `budgets: Map<String, Long>`,
`closed: Set<String>`, `inFlightCapMsat: Long` — and returns a `Verdict` instead
of a bare `Ledger`:

- **Rule 2 at open.** Every offered HTLC in commitment 0 is checked the way
  `checkRemoteCommitment` checks an added one (`:96-101`): its hash must have a
  budget (open authorisations, `GroupLightningAuthorisations.budgets`), must not
  be expired (`closed`), and its amount must fit what is left. `remaining`
  starts from `budgets` itself, since nothing is in flight from earlier
  commitments. The per-hash accumulation is the same loop; factor the
  added-HTLC block out of `checkRemoteCommitment` so open and later commitments
  share it verbatim, rather than copying it.
- **The cap at open.** All offered HTLCs are new at commitment 0, so the
  `added.isNotEmpty()` guard (`:106`) is always true and the check is simply
  `offered.sum > inFlightCapMsat` → refuse.
- **The floor still starts where the first commitment puts it.** The
  `Verdict.Sign` ledger keeps `floorMsat = ours + offered − feeAllowance` as at
  `:61` today, and `consumed` is empty: nothing can settle at commitment 0, so
  no budget is spent by opening. What changes is only that offered HTLCs in the
  baseline are now authorised ones.

| | decision | why |
|---|---|---|
| an *authorised* offered HTLC in commitment 0 signs | rule 2 already expresses "the group agreed"; refusing offered HTLCs at open outright would hardcode today's on-the-fly-only world into the policy | a group-funded or spliced-in channel (the splice-out follow-up in Phase 13 of the design doc) would carry one legitimately, and a policy that cannot express it will be worked around |
| the floor-starting behaviour is kept | a co-signer's first commitment is its only truth about the channel; any other starting floor (zero, capacity) refuses every real open | the review found no fault in the floor itself, only in what gets into it |
| the checks live in `CoSignerPolicy`, not in `checkRemote` | the policy is pure and unit-tested in `CoSignerPolicyTest.kt`; logic in the co-signer is reachable only through the four-device harness | the same shape as every other value rule |

**Tests, in `CoSignerPolicyTest.kt`:** one new test beside the existing eight:

- `the first commitment answers to the authorisations too` — `open` with an
  offered HTLC and empty budgets refuses; with a budget for its hash it signs
  and the floor starts at value − allowance; with the hash in `closed` it
  refuses; with two offered HTLCs against one budget it applies the same
  budget-left arithmetic as `one authorisation cannot be offered twice over`;
  with offered HTLCs over `inFlightCapMsat` it refuses; and the honest shape —
  an incoming-only on-the-fly commitment with empty budgets — signs.

### Phase 2 — the two call sites, and the four-device tests

**About a day.**

`GroupLightningCoSigner.kt`:

- **`checkRemote` (`:399-405`).** The early return goes. Budgets, the expired
  set and the cap are fetched for the open case as they already are for the
  pinned one (`:400-402`), and the branch becomes
  `pinned == null → CoSignerPolicy.open(commitIndex, txid, spec, budgets, closed, cap)`
  with `Refuse` mapped to its reason exactly as the `checkRemoteCommitment`
  branch does. The mainnet cap check at `:374-377` and the rebuild at `:384-395`
  keep running first, unchanged.
- **`commitLedger` (`:430-438`).** The `null` branch takes the same new
  signature and handles `Verdict.Refuse` by returning, as the `else` branch
  already does at `:433`. The two sites must change together: `commitLedger`
  runs only after `check` passed (`:193-208`), so a disagreement would mean a
  commitment refused at check time is still written into the channel ledger at
  echo time, floor and all.

**Tests, in `GroupLightningCoSigningJvmTest.kt`** (which
`GroupLightningCoSigningMainnetJvmTest` re-runs on mainnet parameters), beside
`an outgoing payment the group did not authorise is refused` (`:251`), which
does the same at commitment 1:

- `the first commitment carrying an offered HTLC is refused without an authorisation` —
  `channel.commitment(0, spec with an IncomingHtlc)` never gets a signature
  (`IcebergSigningTimeout`), and no device's `IcebergChannelLedger` row exists
  for the channel afterwards.
- `the first commitment carrying an authorised HTLC is signed` — the group signs
  a `pay` authorisation for the hash first (`fixture.sign` with
  `GroupLightningAuthorisationEvent.template`, as the force-close test at
  `:200-202` does), then commitment 0 with the HTLC is signed and
  `correctlySpends` passes.
- `the first commitment over the in-flight cap is refused` — an authorised HTLC
  whose amount exceeds the statement's `inFlightCapSat` never gets a signature.

**The regtest happy path must not move.** `GroupLightningRegtestJvmTest.kt`'s
`receive(all, 100_000)` (`:268`) is the on-the-fly open whose commitment 0
carries only the incoming HTLC; it exercises the new `open` branch with empty
budgets and must pass unchanged. That is the regression proof that the honest
path is unaffected.

### Phase 3 — rollout, and the design doc

**About half a day.**

This is a co-signer behaviour change in app code only: schema 32 is untouched
(`StoredLedger`'s JSON shape does not change), no kind moves, the fork gets no
commit. But as with the kinds in the design doc's Phase 15, **the rule a group
relies on is the rule its worst co-signer runs**: signing needs `n − t + 1`
echoes, and devices on an old build will still echo a poisoned first commitment,
so the finding is closed for a group only once every participant's device runs
the new build. A mixed group fails safe, not silent — new-build devices refuse,
so a malicious open stalls rather than signing — and the honest on-the-fly open
is byte-for-byte the path it was, so upgrading cannot wedge a working channel.
The wallet screen's existing per-member bookkeeping is the place to note who has
not upgraded, as Phase 15 already does for unanswered ceremony proposals.

The design doc's deviation table then gets a line: the floor "starts at the
first commitment less the fee allowance" gains *"and the first commitment
answers to the authorisations and the cap like any other"*.

## What this does not fix

- **A restored device re-opens the channel.** A co-signer whose database was
  restored has `pinned == null` for a channel mid-life and will treat its next
  remote commitment as a first one: rule 2 and the cap now run, but the floor
  restarts at whatever that commitment holds, so a drained state becomes the new
  baseline. That is the unbuilt high-water-mark collection (design doc line 83),
  covered by [restored-device-marks.md](./restored-device-marks.md); this fix
  narrows what a restored device can be fed but does not replace the marks.
- **The liquidity fee on the open is still the operator's word.**
  `maxLiquidityFeeSat` is enforced by the operator node's liquidity policy only;
  no co-signer reads it (the review's doc-overclaim note beside this finding).
  With the colluding-LSP threat model of this finding, the fee bound rests on
  the LSP's honesty regardless of this fix.
- **Incoming HTLCs at open stay unchecked by design** — an HTLC offered *to*
  the group is the counterparty's to add (`CoSignerPolicy.kt:103-104`), and what
  the seed can take from them is
  [node-seed-in-flight-exposure.md](./node-seed-in-flight-exposure.md)'s subject.
- **The ledger is still committed at echo time,** so a refused-then-retried open
  interacts with the echo-time budget burn and channel-wedge surface in
  [griefing-surface.md](./griefing-surface.md); this fix adds one more refusal
  reason before the ledger write, which is the safe direction, but does not
  reorder anything.
- Independent of the open path: the force-close fence's one-behind hole
  ([force-close-revoked-commitment.md](./force-close-revoked-commitment.md)) and
  the mutual-close amount anchored to the operator's spec
  ([mutual-close-amount.md](./mutual-close-amount.md)).

## Risks and open questions

| risk or question | why it matters | what to do |
|---|---|---|
| a legitimate first commitment with an offered HTLC never occurs today | on-the-fly funding is the only open flow, and its commitment 0 is incoming-only; the authorised-HTLC-at-open branch will be dead code until group-funded channels exist | keep it anyway (the decision table above), with the Phase 2 test as its only exercise; a splice-in flow that needs it must re-read this note |
| mixed-build groups | old-build co-signers still echo a poisoned open, so the fix is per-device, not per-group | say so on the wallet screen until every participant is upgraded; the failure mode is a stalled malicious open, never a signed one |
| the factored added-HTLC loop must not drift from `checkRemoteCommitment`'s | two copies of rule 2 would re-create this finding in miniature the next time one is edited | share one private function; the existing `CoSignerPolicyTest` suite plus the new open tests pin both paths |
| does `open` need the repeat-commitment branch? | `checkRemoteCommitment` allows re-signing the same txid at the same index (`:79-81`) for retransmission; at open there is no previous row, so a retransmitted commitment 0 arrives while `pinned` is still null on devices that never echoed it — already handled, since `open` is deterministic over the same spec | no special case; a device that already echoed commitment 0 has a ledger row and takes the `checkRemoteCommitment` repeat branch |
| interaction with the first-commitment trust in `signedOursMsat` | the wallet screen's "matches the last commitment you signed" reads the ledger this fix now guards more strictly | nothing to do; the figure can only improve |
