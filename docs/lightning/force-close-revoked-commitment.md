# Force close: the fence admits an already-revoked commitment

Remediation plan for the most serious finding of the group Lightning wallet review: the
co-signers' force-close fence lets the operator get a revoked local commitment signed, and
publishing it hands the LSP's penalty the entire channel balance. Read
[../group-lightning-wallet.md](../group-lightning-wallet.md) first; this plan amends its Phase 13
row ("only if its number is no more than one behind the counterparty commitment the device last
signed", doc line 109).

## The finding

`GroupLightningCoSigner.checkLocal` signs our own commitment, for a force close, under three
conditions: an open FROST-signed force-close authorisation
(`GroupLightningCoSigner.kt:341`, `GroupLightningAuthorisations.forceCloseAuthorised` at
`GroupLightningAuthorisations.kt:111-112`), a byte-exact rebuild from the request's
`SigningContext` (`GroupLightningCoSigner.kt:348-358`), and the commitment-number fence:

```kotlin
pinned?.let { if (commitIndex < load(it.ledger).commitIndex - 1) return "… revoked" }   // GroupLightningCoSigner.kt:345
```

The ledger's `commitIndex` is the last **remote** commitment this device signed
(`CoSignerPolicy.Ledger`, written at echo time by `commitLedger`, `GroupLightningCoSigner.kt:420-443`).
The `-1` is meant to cover a stalled dance. It also covers a revoked commitment, because of the
order the fork runs the commitment dance in:

1. The LSP sends `commit_sig` for our local commitment N+1. `Normal.kt:207-231` handles it:
   `Commitments.receiveCommit` (`Commitments.kt:918-961`, per commitment at `Commitments.kt:633-647`)
   builds local commitment N+1, and the state emits, in order: `StoreState` (Normal.kt:218),
   `Send(revoke_and_ack)` (Normal.kt:219) — **revoking local N immediately, before any group
   signature** — and only then `SendToSelf(ChannelCommand.Commitment.Sign)` (Normal.kt:221).
2. The `Sign` command calls `Commitments.sendCommit` (Normal.kt:69, wrapper at
   `Commitments.kt:896-916`, per commitment at `Commitments.kt:584-631`), which signs remote
   commitment N+1 through the co-signers with the context built at `Commitments.kt:613`.
3. The co-signers sign remote N+1 and record `commitIndex = N+1`. The fence then admits any local
   `commitIndex >= N` — but local N was revoked back in step 1.

The attack, step by step:

1. The operator waits for (or provokes, by paying the group from an outside wallet) an
   LSP-initiated dance. The node revokes local N automatically; the co-signers sign remote N+1.
2. The group grants a force-close authorisation (kind 30363) — a real `t`-of-`n` decision, but one
   granted exactly when things are going wrong, which is when the operator plays this.
3. The operator asks the co-signers to sign local commitment N. It holds the genuine historical
   transaction and context, so the rebuild passes. The fence passes: `N >= (N+1) - 1`. Every
   co-signer signs.
4. The operator publishes the revoked commitment N. The LSP's penalty transaction claims the
   **entire channel balance** — the outcome the doc says this check exists to prevent (doc line 109).

No LSP collusion is needed beyond ordinary operation: the dance and the penalty claim are both
automatic. With a colluding LSP the attack is repeatable at will.

Two adjacent holes the same line causes:

- **The revocation precedes the signature.** In step 1 the node sends `revoke_and_ack` *before*
  the group signature for remote N+1 exists. If the operator (the Iceberg coordinator) simply never
  completes that signature, the ledger floor stays at N — remote N+1 is never signed — while local
  N has just been revoked. The fence then admits local N — it refuses only below `N - 1` — and N is
  revoked. The operator can hold this window open indefinitely, since the channel retries a
  failed signature "on its own schedule" (doc, deviations table, line 94).
- **No fence at all with an empty ledger.** The fence runs only under `pinned?.let { … }`. A
  restored or late device has `pinned == null` and signs **any** local commitment under a
  force-close authorisation, however deeply revoked. The doc admits the high-water-mark collection
  that would protect such a device is not built (doc line 83); until
  [restored-device-marks.md](./restored-device-marks.md) lands, such a device must refuse instead.

## Root cause

The fence compares two numbers that do not move together. The ledger floor advances when **we**
sign a remote commitment; a local commitment is revoked when **we** send `revoke_and_ack`, which is
triggered by the LSP's `commit_sig` and needs no group signature at all. Worse, the fork emits the
revocation *before* the group signature it triggers (Normal.kt:219 before :221), so the revocation
strictly leads the floor whenever the LSP initiates. A co-signer cannot observe revocations — they
are the node's local business — so no fence computed from signing history alone can be safe while
the node is allowed to revoke ahead of its floor.

That is also why the sketch from the review — carry the node's `localCommit.index` in the
`SigningContext` (a fork change at `Commitments.kt:613`), record it in `IcebergChannelLedger`
(schema 32 → 33), and refuse `commitIndex` below the recorded value — **was verified against the
code and rejected**:

- It fails the dance it was designed for. When **we** initiate, `sendCommit` signs remote N+1 while
  `localCommit.index` is still N, so N is recorded. If the dance then completes, the LSP's
  `commit_sig` arrives, `receiveCommit` clears `localChanges.acked` and moves nothing new into
  `remoteChanges.acked` (`Commitments.kt:956-957`), so `localHasChanges()` is false
  (`Commitments.kt:77`) and **no further group signature happens** — yet Normal.kt:219 revokes
  local N. The recorded value stays N, the fence admits N, and N is revoked. Every outgoing payment
  the group makes opens this hole between the add-dance and the settle-dance.
- The migration it calls for is unnecessary: the one number the fence actually needs — the last
  remote commitment this device signed — is already in the ledger (`StoredLedger.commitIndex`,
  `GroupLightningCoSigner.kt:88-105`). No schema change is required.

The one thing a floor from signing history cannot distinguish is a *stalled* self-initiated dance
(local N unrevoked, remote N+1 signed, floor N+1) from a *completed* one (local N revoked, same
floor). Both look identical to every co-signer, because the difference is a message the node sent
or did not send. So the stalled-dance case cannot be served by the fence; it needs an explicit
group decision instead (Phase 3).

## The fix, in phases

| | decision | why |
|---|---|---|
| F1 | The fence is `commitIndex < floor` — refuse anything below the last remote commitment this device signed. **The `-1` slack is deleted**, and replaced by a named-commitment authorisation for the stalled-dance case | after signing remote M, the only local commitment guaranteed unrevoked is M itself: M-1 is revoked the moment the LSP's answering `commit_sig` is processed, and the co-signers cannot tell whether that has happened |
| F2 | The fork does not send `revoke_and_ack` until the group signature it triggers is in hand | otherwise the operator holds local M-1 revoked with the floor still at M-1, and F1 admits it (the pre-signature revocation window above) |
| F3 | A device with no ledger entry for the channel refuses to sign any local commitment | it cannot know what is revoked; today it signs everything. Recovery is restored-device-marks.md's |
| F4 | No `localCommitIndex` in the `SigningContext`, no schema migration | both were in the review's sketch; the code shows the first cannot work (root cause, above) and the second buys nothing |

### Phase 1 — the fence, in the co-signer

**About a day. App only.**

- `GroupLightningCoSigner.checkLocal` (`GroupLightningCoSigner.kt:332-360`):
  - `pinned == null` now refuses: *"this device has never signed for this channel, and cannot know
    what it has revoked"* (F3). This replaces a silent pass with a loud refusal, named in the log
    like every other.
  - The fence becomes `if (commitIndex < load(it.ledger).commitIndex) return "… revoked"` (F1).
  - The named-commitment escape hatch from Phase 3 is checked here; until it lands, Phase 1 ships
    with the hatch absent and the stalled-dance case simply refused (safe direction).
- The `checkLocal` doc comment (`GroupLightningCoSigner.kt:326-331`) is rewritten: the "no more
  than one behind" sentence is the finding.

**Tests,** in `GroupLightningCoSigningJvmTest.kt` (the mainnet suite,
`GroupLightningCoSigningMainnetJvmTest`, re-runs every case on mainnet parameters):

- Extend `our own commitment is signed only once the group authorises a force close, and never a
  revoked one` (`GroupLightningCoSigningJvmTest.kt:189-208`): after signing remotes 0..2, a request
  for local **1** — the boundary the suite never tried — is now refused by every member. Local 0
  stays refused; local 2 still signs and `correctlySpends`.
- New: `a device that never signed for the channel signs no local commitment` — a second `Channel`
  with a fresh funding outpoint, a force-close authorisation for it, and a request for its local
  commitment 0: refused everywhere, because no device has a ledger entry for it.

### Phase 2 — revocation rides the group signature, in the fork

**About three days. lightning-kmp fork, `threshold` branch.**

Restructure the `CommitSig` handling in `Normal.kt:207-231` so that when
`commitments1.changes.localHasChanges()` is true — the case that owes the LSP our `commit_sig` —
the `revoke_and_ack` is held back and sent together with that `commit_sig`, after
`Commitments.sendCommit` returns Right. Concretely:

- `Normal.kt:207-231`: run `receiveCommit`, `StoreState(commitments1)` as today, but do **not** add
  `Send(revoke_and_ack)` to the actions when a `Sign` is owed. Carry the pending `RevokeAndAck`
  alongside the `SendToSelf(ChannelCommand.Commitment.Sign)` (a new field on the command, or a
  state field — pick whichever the state machine's persistence already covers).
- `Normal.kt:60-96`: on a successful `sendCommit`, emit `Send(revoke_and_ack)` then
  `Send(commit_sig)`, in that wire order (BOLT 2 wants the revocation first).
- When `localHasChanges()` is false — the completion of our own dance, where nothing is owed
  (`Commitments.kt:77` after `:956-957`) — send the revocation immediately, as today. That case is
  safe without deferral: the floor advanced when *we* signed remote M, before the LSP's
  `commit_sig` could arrive.
- A signing failure or timeout leaves local M-1 **unrevoked** and the floor at M-1: the channel
  retries as today, and a force close from that state signs local M-1 legitimately. On reconnect,
  `channel_reestablish` makes the LSP re-send its `commit_sig` and the dance replays from the
  stored pre-signature state.
- The reorder applies to every channel in the fork, not just group channels: for a private-key
  signer the delay is microseconds, and two behaviours would be two things to test. The identity's
  own Phoenix node runs this fork, so its suite is the canary.

This changes when the LSP sees our `revoke_and_ack`: it now waits behind the group signature, up
to the 60-second `RemoteIcebergFundingSigner.deadline` — a guess, since Phase 0's measurement of
how long the LSP waits was never made (doc lines 50-51). Measuring it is now blocking: see risks.

**Tests,** in the fork's channel suites (`commonTest`):

- A signer whose answer is held back: feed the channel a `commit_sig`; assert no `revoke_and_ack`
  leaves while the signature is pending, and that `revoke_and_ack` then precedes `commit_sig` when
  it completes. The fork's suite already runs with a remote signer on random delays (doc, Phase 2
  tests); this adds the ordering assertion.
- Crash window: process `commit_sig`, store, stop before the signature completes, restore, replay
  `channel_reestablish`; the channel re-derives that it owes `revoke_and_ack` and re-sends it
  (verify `Syncing`'s retransmission covers a revocation owed — if it does not, that is part of
  this phase).
- Pin the negative case: after `receiveCommit` with no changes owed, `localHasChanges()` is false
  and the revocation goes out alone.
- The fork's full JVM suite (993 tests at the last advance) re-runs.

### Phase 3 — a force-close authorisation that names the commitment

**About two days. App, wire format.**

The stalled self-initiated dance — we signed remote M, the LSP's answering `commit_sig` never came,
local M-1 is unrevoked but below the floor — is the one legitimate force close F1 refuses. It is
also indistinguishable to a co-signer from the attack. So it stops being an automatic case and
becomes an explicit group decision:

- `GroupLightningAuthorisationEvent.ChannelAction` (`GroupLightningAuthorisationEvent.kt:99-103`)
  gains `commitment: Long?`, serialised as a `commitment` tag by `template` (`:105-117`) and read
  by `readChannelAction` (`:119-129`), cross-checked against the `d` tag the way `channel` is.
  Absent means what today means: the floor decides.
- `GroupLightningAuthorisations.forceCloseAuthorised` (`GroupLightningAuthorisations.kt:110-112`)
  returns the authorised commitment index (or null when unnamed) instead of a Boolean;
  `checkLocal` then signs `commitIndex` below the floor only if an open authorisation names exactly
  that index. Every other check — rebuild, funding pin, channel binding — still applies.
- The proposal flow (`proposeChannelAction`, `GroupLightningAuthorisations.kt:125-132`) gains the
  index for the stalled case, and the approval summary says what it means: *"Sign commitment N,
  below the M this device last signed — approve only if the channel's last update stalled."* The
  approver's own ledger is the evidence shown.

**Wire-kind note.** This is an additive tag on kind 30363, not a new kind. Old builds ignore the
tag and apply their old fence — looser, so a mixed group keeps the old vulnerability until the new
build is everywhere it matters (see the rollout note). Named-index authorisations created by an
old proposer are impossible, which is the safe direction.

**Tests:**

- `GroupLightningCoSigningJvmTest.kt`, new: `a stalled dance's last unrevoked commitment signs
  only under an authorisation that names it` — with the floor at 2: unnamed authorisation, local 1
  refused; authorisation naming 0, request for 1 refused (mismatch); authorisation naming 1, local
  1 signs and `correctlySpends`.
- `readChannelAction` round-trip with and without the tag, and a tag that disagrees with the `d`
  tag reads as null, beside the existing template tests for the event.

### Phase 4 — rollout

**About a day, plus the measurement.**

- The regtest run (`testing/regtest/regtest.sh up`, then
  `DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'`) re-runs
  end to end; its force-close step now exercises the deferred revocation against a real eclair.
- The app's JVM suite and the fork's suite pass.
- **Measure how long ACINQ's LSP waits for `revoke_and_ack`** before this ships to a real group:
  Phase 0's spike, which the doc still lists as unmade (doc line 50), now gates revocation as well
  as `commit_sig`.
- Deployment realism, as the design doc states it for the whole wallet (doc lines 977-980): the
  fence is a co-signer rule, so it protects the channel only once at least `n - t + 1` co-signers —
  the echo quorum — run the new build. Old-build devices still echo and sign a revoked commitment
  under any force-close authorisation. The fork reorder is operator-side and ships in the same
  build, so one rollout covers both; an upgraded operator with old co-signers is safe in the
  direction that matters (their fence is looser, never tighter). The wallet screen should say the
  channel is protected only once every participant's device has answered from the new build, the
  way the ceremony screen lists devices that have not answered.

| phase | estimate |
|---|---|
| 1 the fence | 1 day |
| 2 revocation ordering, fork | 3 days |
| 3 named-commitment authorisation | 2 days |
| 4 rollout and measurement | 1 day |
| **total** | **about 1 week, plus the LSP-latency measurement** |

## What this does not fix

- **A co-signer whose ledger is behind.** A device that missed the last `k` dances signs local
  commitments down to its own floor, of which up to `k - 1` are revoked. The echo quorum (`n - t +
  1` devices must echo before anyone signs) bounds this to groups of lagging devices; closing it is
  the high-water-mark exchange in [restored-device-marks.md](./restored-device-marks.md), which
  also gives the Phase 1 refusal for `pinned == null` a way back.
- **The authorisation itself.** Force close remains a `t`-of-`n` human decision, granted under
  stress, which is what makes this finding exploitable at all. Nothing here changes that; F1 only
  makes the decision unable to name a revoked commitment by accident.
- **The other force-close path's crash.** A lightning-kmp-initiated local signing without an
  authorisation still throws inside the channel (`Commitments.kt:301`'s `.right!!`, doc line 110);
  that is [signer-hygiene.md](./signer-hygiene.md)'s.
- **What the operator learns or burns by stalling** — budget consumption at echo time, a wedged
  channel at a signed index — is liveness griefing, covered in
  [griefing-surface.md](./griefing-surface.md).

## Risks and open questions

| risk | why it matters | what to do |
|---|---|---|
| the LSP waits less for `revoke_and_ack` than the group takes to sign | the deferred revocation turns a slow quorum into a protocol timeout, where today only `commit_sig` waits | Phase 4's measurement before rollout; if the budget is too small, the reorder can carry its own shorter signing deadline, or eclair's revocation timeout on our own regtest LSP tells us the floor |
| `channel_reestablish` does not re-send an owed revocation after a crash in the deferred window | the channel wedges until expiry of retries | Phase 2's crash-window test; add the retransmission to `Syncing` if missing |
| a `receiveCommit` path owes no `commit_sig` other than the verified one | revocation would race the floor again, reopening the window | the `localHasChanges()` reasoning (`Commitments.kt:77`, `:956-957`, `:1018`) is pinned by a fork test in Phase 2 |
| the named-commitment authorisation is misused socially | it is the one way left to sign below the floor; an operator who talks the group into naming an old index gets the attack back | the approval screen shows the device's own floor against the named index (Phase 3); a named index is never proposed by the normal force-close flow |
| old-build co-signers keep the old fence | the attack succeeds while `n - t + 1` devices predate the fix | Phase 4's rollout note; the screen says when the channel is protected |
