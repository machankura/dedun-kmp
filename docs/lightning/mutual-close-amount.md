# The mutual-close amount check trusts the operator's spec, not the co-signer's own ledger

Finding 4 of the security review. The fix keeps `checkClosing`'s shape — output-by-output,
no rebuild — but anchors what the group must be paid to the channel ledger the device
already keeps, instead of to a number the operator supplies. About five days, all in the
app; no schema, wire-kind or fork change.

## The finding

`GroupLightningCoSigner.checkClosing` (`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupLightningCoSigner.kt:408-417`)
is the whole of Phase 9's check 7 ("its amount is our balance minus a capped fee",
`docs/group-lightning-wallet.md:799-800`). It reads:

```kotlin
val ours = context.spec.toLocalMsat / 1000                                   // :413
val paid = tx.txOut.filter { it.publicKeyScript == local }.sumOf { it.amount.sat }
val allowance = statement.policy.commitmentFeeAllowanceSat
return if (ours > allowance && paid < ours - allowance) "…" else null        // :416
```

`ours` is `context.spec.toLocalMsat` — a field of the `SigningContext.Closing` the
operator's node attached to the request and serialised over the wire
(`CoSigningWire.kt:200`). Every other context type is checked against the device's own
records: remote commitments answer to `CoSignerPolicy.checkRemoteCommitment` over the
pinned channel ledger (`GroupLightningCoSigner.kt:399-405`), and both commitment paths are
rebuilt from the context and compared byte-for-byte (`:348-359`, `:384-395`). The closing
path alone takes the operator's word for the group's balance. The pinned ledger is in
scope at the call site (`:313`, `:320`) and is never handed in.

Worse, the guard `ours > allowance` means a spec with `toLocal = 0` switches the amount
check off entirely.

The attack, step by step:

1. The operator runs modified node code, and the LSP colludes (or is itself modified).
   A *stock* LSP rebuilds the closer's closing transaction from its own commitment state
   and refuses one whose amounts differ (`Helpers.kt:366-410` in the fork, matching its
   `closingTxs.localAndRemote` against the offered signature), so a dishonest close needs
   the LSP in on it. The design doc's whole premise is that the co-signers, not the
   counterparty, are the group's defence ("a co-signer signs any mutual close that passes
   check 7", `docs/group-lightning-wallet.md:108`).
2. The LSP initiates a mutual close. No `close` authorisation is needed on the co-signer
   side — by design, so an honest LSP-initiated close is never refused (doc line 108).
3. The operator relays a signing request carrying a closing transaction that pays, say,
   5,000 sats to the group's onchain address and the remaining 895,000 to the LSP's
   script, with a context whose spec says `toLocal = 0`.
4. Every co-signer's `checkClosing` passes: the local script is the wallet's own derived
   address (`:411`), every output pays one of the two named scripts (`:412`), and with
   `ours = 0` the amount check is skipped. Each co-signer recomputes the sighash over the
   *given* transaction (`messageOf`, `:269-273`), so the signature it produces is valid
   for the cheating transaction. The echo rule sees one honest hash per label and signs.
5. The LSP aggregates and broadcasts. The group loses everything above 5,000 sats, up to
   the 1,000,000-sat channel cap — with every co-signer having "checked" the close.

There is also no rebuild of the closing transaction — both commitment paths got one and
this path did not — and no test of a close to a wrong address or a short amount: the
co-signing suite's five refusals (`GroupLightningCoSigningJvmTest.kt:226-319`) never build
a closing transaction.

## Root cause

Check 7 was implemented as an *internal consistency* check — the transaction must match
the context it arrives with — on the one path where consistency is not enough, because
both halves of the comparison come from the operator. The device holds the truth the check
needs: `CoSignerPolicy.Ledger` (`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/lightning/CoSignerPolicy.kt:32-43`)
tracks exactly what the group can claim (`oursMsat` plus offered HTLCs, `valueMsat`)
against a floor that only authorised payments lower, and it is persisted per channel in
`IcebergChannelLedger` and loadable with `load(pinned.ledger)`. The closing path was never
wired to it.

Two real complications explain why it was not, and any fix has to answer both:

- **The ledger is the LSP's view; the close's spec is the node's.** `commitLedger` records
  the last *remote* commitment this device signed (`GroupLightningCoSigner.kt:422`), while
  `SigningContext.Closing` carries `commitment.localCommit.spec` — the node's *local*
  commitment (fork `Helpers.kt:333`, `:398`). The two move one index apart while an update
  is in flight — the same snapshot mismatch the design doc explains at line 97. A naive
  `paid >= ledger.valueMsat - allowance` would refuse an honest close requested mid-dance.
- **The closing fee is not the commitment fee.** When the group's node is the closer, the
  fee comes out of the group's side (`Transactions.kt:1324`, `ClosingTxFee.PaidByUs`), and
  at a high feerate it can exceed the 5,000-sat `commitmentFeeAllowanceSat` default
  (`GroupLightningWalletStatementEvent.kt:41`). Anchoring to the ledger with only that
  allowance would fail honest closes exactly when fees spike.

## The fix, in phases

### Phase 1 — a closing rule in `CoSignerPolicy`

**Goal: the amount a close must pay is derived from the ledger by a pure, unit-tested
function, with the in-flight gap and the closing fee handled explicitly. About two days.**

`CoSignerPolicy.kt`, new function beside `checkRemoteCommitment`:

```kotlin
fun checkClosing(previous: Ledger, closeSpec: CommitmentSpec, feeSat: Long, budgets: Map<String, Long>, closed: Set<String>): Verdict
```

`closeSpec` is the context's spec in the node's local view: `toLocal` is ours, and an
HTLC *we* offered arrives as `OutgoingHtlc`. The rule:

1. **Flip the view** (`toLocal` ↔ `toRemote`, `OutgoingHtlc` ↔ `IncomingHtlc`) so the
   spec is comparable with the ledger, then decide which state the close is from:
   - **Same state as the ledger.** The flipped spec's value (`toRemote` plus offered
     HTLCs) equals `previous.valueMsat`. This is the normal case: the channel went quiet
     before closing (`Transactions.makeClosingTxs` requires `spec.htlcs.isEmpty()`,
     fork `Transactions.kt:1317`, and negotiation only signs once both sides converge).
   - **One state ahead.** Treat the flipped spec as the next remote commitment and run it
     through `checkRemoteCommitment(previous, previous.commitIndex + 1, …)` with the live
     budgets. A Sign verdict means the local commitment legitimately settled a payment the
     ledger has not seen — the mid-dance gap, passed without trusting the operator.
   - Anything else: refuse. The policy's existing rules do the work — an understated
     `toLocal` eats the fee allowance and then the floor refuses it; an HTLC that
     "settled" without an authorisation has no budget to consume.
2. **Return the validated value** (the flipped spec's value from whichever branch
   matched). From here on `closeSpec` is no longer operator data: it has been forced to
   agree with the device's own ledger within the policy's own tolerances.
3. **The fee is measured, not assumed.** The caller passes `feeSat = input amount −
   Σ outputs` of the actual transaction. The close must pay the group at least
   `validated − feeAllowance − feeSat` when the group is the fee-payer, and
   `validated − feeAllowance` when it is not — and `feeSat` itself is capped at a new
   constant `MAX_CLOSING_FEE_SAT` (start at 50,000: beyond any sane negotiated fee, below
   a theft worth having). A close whose fee exceeds the cap is refused as greedy, the same
   rule `GroupSweep.check` already applies to sweep feerates.

| | decision | why |
|---|---|---|
| | two accepted states (same, or one ahead via the existing policy) rather than a wider tolerance on amounts | a tolerance re-introduces "the operator picks the number"; replaying the policy means the only slack is the fee allowance the group already signed |
| | the fee read off the transaction and capped, not folded into `commitmentFeeAllowanceSat` | a mainnet fee spike can legitimately exceed 5,000 sats; refusing those closes would push the group into a force close at a higher fee. The cap is a constant for now; a statement policy field is the cleaner form and is a statement-version change, left as a follow-up |
| | the dust exemption stays: a validated value at or under the allowance keeps the current skip | a channel whose remainder is dust has nothing checkable to pay; refusing its close would strand it |

**Tests, in `CoSignerPolicyTest.kt`** beside the existing floor and budget cases:

- a close at the ledger's value, no HTLCs, passes in the same state;
- a close one state ahead where the difference is exactly an authorised payment settling
  passes, and consumes budget;
- a close one state ahead whose extra fall has no authorisation is refused;
- a close two states away, or with a value that fits neither state, is refused;
- the fee cap: at the cap passes, over it refused.

### Phase 2 — wire it into `checkClosing`

**Goal: the co-signer refuses a close its own ledger cannot explain, including every
close on a channel it has no ledger for. About two days, tests included.**

`GroupLightningCoSigner.kt`:

1. `checkClosing` gains `pinned: IcebergChannelLedger?`, `budgets` and `closed` — all
   already computed or one call away at the call site (`:308-321`). The script checks at
   `:411-412` are unchanged.
2. **`pinned == null` refuses the close.** A device that never signed a remote commitment
   for the channel — new, or restored from a stale backup with an empty ledger — has no
   floor and cannot check anything; it abstains. This is the close-path twin of the
   first-commitment exemption that
   [first-commitment-policy.md](./first-commitment-policy.md) removes for commitments, and
   it leans on [restored-device-marks.md](./restored-device-marks.md): until high-water-mark
   collection lands, a restored device abstains from closes for good, which costs the
   group `n-t+1` quorum slack but never money.
3. The amount line becomes: load the ledger, run `CoSignerPolicy.checkClosing`, and on
   Sign require `paid >= validated − feeAllowance − (feeSat if the group pays the fee)`,
   with `feeSat` measured from the transaction and capped. The refusal message says what
   the ledger believes the group holds.
4. **No rebuild.** Both commitment paths are rebuilt with `Commitments.makeRemoteTxs` /
   `makeLocalTxs` because a commitment's outputs are *derived* — per-commitment tweaks,
   HTLC leaves, key bases — and the only way to enumerate them is to build the
   transaction. A closing transaction has no derived structure: two outputs to two scripts
   the context names and the device independently pins, an input already pinned by check
   3, and amounts Phase 1 validates against the ledger. `Transactions.makeClosingTxs`
   exists and is pure (fork `Transactions.kt:1316`), but a byte-for-byte rebuild would
   need the negotiated `fee` and `lockTime` on the wire — neither is in
   `CoSigningWire.Context` today, so a rebuild is a wire change that buys nothing over the
   output-by-output check once the amounts are anchored. The sighash the co-signer
   recomputes covers the whole transaction, so what it checks is what it signs.

| | decision | why |
|---|---|---|
| | output-by-output checks with a ledger-anchored amount, not a rebuild | the closing transaction has no derived parts to mistrust; a rebuild adds a wire-field change (fee, lockTime) and a second code path for no extra assurance |
| | `pinned == null` refuses rather than trusts the spec | trusting the spec with no ledger is the finding, verbatim. The cost is abstention by devices that cannot check, and the echo rule tolerates up to `t-1` of those |
| | no `close` authorisation required on the co-signer, unchanged | doc line 108: refusing an LSP-initiated close only makes the LSP force-close at the group's cost. The ledger anchor is what makes that safe to keep |

**Tests, in `GroupLightningCoSigningJvmTest.kt`** (and therefore in
`GroupLightningCoSigningMainnetJvmTest.kt`, which re-runs the suite on mainnet
parameters), with a `Channel.close(spec, fee)` helper beside `Channel.commitment` that
builds the transaction with `Transactions.makeClosingTxs` and the context as
`FundingSigner.SigningContext.Closing`:

- `a mutual close that pays a script that is not the group's is refused` — today untested;
- `a mutual close that pays the group short is refused` — the honest spec, a light
  transaction;
- `a mutual close with an understated spec is refused` — the finding itself: the
  transaction matches the lying context, the ledger disagrees;
- `an honest close is still signed`, including one requested one state behind the ledger
  (a payment settled in the local commitment the device has not signed the remote twin
  of), and the LSP-initiated shape of it;
- `a device that never signed for the channel abstains from its close` — sign commitments
  with three devices, then ask all four for the close, and assert the fourth never
  echoes.

### Phase 3 — rollout and the design doc

**About a day.**

- No schema change (the ledger already carries everything Phase 1 reads), no wire-kind
  change (the context carries everything Phase 2 needs), no fork change. The new rule is
  co-signer-side only.
- Deployment realism, as the design doc puts it for the kinds: a co-signer on an old
  build still echoes a cheating close. The attack needs `n - t + 1` devices to echo the
  same hash, so the group is safe once fewer than `n - t + 1` devices run the old build —
  but every participant should upgrade before the group treats the rule as in force, the
  same way Phase 15 treats the kinds (`docs/group-lightning-wallet.md:977-981`). For
  2-of-4 that means at least two devices on the new build.
- The design doc gains a deviation row: check 7's "its amount is our balance minus a
  capped fee" now says whose balance — the device's own ledger, with the one-state gap
  and the measured, capped closing fee — and line 108's rationale is kept and now safe.

## What this does not fix

- **A colluding operator and LSP can still grief.** They can refuse to close honestly,
  force-close at the group's fee cost, or stall. D5 (group basepoints) is what sends a
  force close's proceeds to the group's keys; this plan is about the mutual-close path
  only.
- **The force-close fence off-by-one** — signing local commitment `lastRemote − 1`, which
  may already be revoked — is [force-close-revoked-commitment.md](./force-close-revoked-commitment.md)'s.
  Both plans read the same `Ledger.commitIndex`; whichever lands second must rebase its
  fence arithmetic on the other's.
- **An overstated ledger blocks honest closes.** `commitLedger` runs at echo time, before
  the signature completes, so a burned commitment can advance the ledger past the
  channel's real state — and Phase 1's anchor would then refuse the honest close that
  follows. That is [griefing-surface.md](./griefing-surface.md)'s echo-time-commit
  finding made visible on the close path; the failure is a refusal (liveness), never a
  theft, but the two plans should land together.
- **In-flight HTLCs at a force close** stay claimable by a node-seed holder:
  [node-seed-in-flight-exposure.md](./node-seed-in-flight-exposure.md). A mutual close
  requires empty HTLCs, so this plan never sees them.
- **A restored device abstains from closes** until
  [restored-device-marks.md](./restored-device-marks.md) rebuilds its ledger. If the whole
  group restored from stale backups, the wallet cannot mutual-close at all and must
  force-close under D5.

## Risks and open questions

| risk | why it matters | what to do |
|---|---|---|
| the one-state gap is wider than one state | `commitLedger` at echo time can advance the ledger over commitments that were never completed, so the close's spec can be several states behind the ledger with honest balances | griefing-surface.md's fix (commit at signing, not echoing) removes most of it; until then the refusal is safe-direction and the group can still force-close |
| the closing-fee cap is a guess | too low and honest mainnet closes at fee spikes are refused; too high and it is the new allowance | 50,000 sats is a starting constant, pinned by a test; the real fix is a policy field on the wallet statement, which is a statement-version change and a follow-up |
| `pinned == null` abstention shrinks the quorum | a group with several new or restored devices may fall under `n - t + 1` echoing devices and be unable to close | acceptable: force-close under D5 remains, and restored-device-marks.md is the real fix. The ceremony and restore flows should say so |
| the LSP's closee path signs before the group's | in `signClosingTx` the group's signature answers the LSP's chosen transaction (fork `Helpers.kt:357-410`); a colluding LSP picks which of the variants to offer | harmless once the amount is anchored: every variant must pay the validated value to the pinned script |
| the ledger's `valueMsat` includes offered HTLCs that a close spec can never carry | a close with HTLCs outstanding is not signable (`Transactions.kt:1317` requires them empty), but the ledger may still list offered HTLCs that failed back without a settling commitment reaching this device | the one-ahead policy run explains exactly those deltas through budgets; a delta with no budget refuses. If that bites in practice, the answer is high-water-mark exchange, not a wider tolerance |
