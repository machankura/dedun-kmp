# Requesting inbound liquidity for the group's Lightning wallet

Phoenix lets its owner buy inbound liquidity ahead of need: pick an amount, see the fee,
confirm, and the LSP splices that much of its own money into the channel. This plan gives the
group's Lightning wallet the same button. The difference is who presses it. In Phoenix one
person decides and one key signs. Here the group decides, with a FROST-signed authorisation,
and `2t-1` co-signers sign a splice that every one of them has checked.

**Built, but for Phase 9, and on, on mainnet.** Written against `84984ca8` on `bennu-lightning`, with the
`lightning-kmp-app` submodule at `6f3f792` and lightning-kmp at `bd78ed62` (branch
`claude/group-lightning-wallet`). The library phases land on a new branch,
`claude/group-lightning-liquidity`, in both `lightning-kmp-app` and `experimental/lightning-kmp`;
it is local and not pushed. Read these first:

- [group-lightning-wallet.md](./group-lightning-wallet.md): D6 ("people approve outgoing
  payments, closes and splices"), Phase 9's check 8, and the Phase 13 and 15 rows of its build
  table. This plan is check 8 built, for one kind of splice.
- [lightning/README.md](./lightning/README.md): its step 2, the co-signer core release, rewrites
  the same functions this plan does and changes what the ledger's `commitIndex` means. This plan
  goes after it.
- `lightning-kmp-app/experimental/lightning-kmp/FEATURE-PARITY.md`, Phase 6 ("splice design B").
  This plan's Phase 1 is that phase.

### Where the build stands

One commit per phase. The phases below are kept as they were written; where the build chose
differently, this table says so.

| phase | state |
|---|---|
| 0 | steps 1, 3 and 4 run on regtest (`LiquidityPurchaseRegtestJvmTest`), after Phase 1, which the key check needs; see *What Phase 0 found*. Step 2 and the mainnet half of step 1 are not run: they need a node on mainnet and a purchase with real money, which is the owner's to make |
| 1 | done, in the fork: the guard is gone, and `IcebergSpliceTestsCommon` splices a group's channel eight ways. The fork's JVM suite passes but for its Electrum tests, which reach public servers and time out differently on every run |
| 2 | done, in the fork: `SigningContext.Splice`, and every commitment's context names its funding index. The regtest run checks the context a real purchase fills |
| 3 | done: the `splice` context and every spent output on the wire, the sighash over all of them, check 3 for a splice. A co-signer still refuses every splice, now by name (*a splice, which this device does not sign yet*), until Phase 5. `CoSigningWireJvmTest`, four tests |
| 4 | done: `buy-liquidity` (`GroupLightningAuthorisationEvent.BuyLiquidity`), `liquidityPurchases`, `prepareBuyLiquidity`, `proposeBuyLiquidity`, its summary and its transcript line. `GroupLightningLiquidityJvmTest`, three tests, run on regtest and on mainnet |
| 5 | done: a ledger per funding output (schema 35, `IcebergChannelLedger` keyed by output, with `fundingTxIndex`, `parentOutPoint` and `purchaseId`), `CoSignerPolicy.openSplice`, the shared input checked against S1 to S6, and a payment that settles on both live commitments spending its budget once. `GroupLightningSpliceCoSigningJvmTest`, seven four-device tests on regtest and on mainnet, among them a purchase the script interpreter accepts |
| 6 | done: `PeerHandle.fundingRates`, `feeratePerByte` and `buyLiquidity`, `GroupLightningNode.buyAuthorised` on the node's watch, and the snapshot's rates, feerate and purchases. Four tests with the stand-in peer in `GroupLightningLiquidityJvmTest` |
| 7 | done, behind `GroupLightningRollout.LIQUIDITY_ENABLED`, which is off until Phase 10: `GroupLightningLiquidityScreen` and its route, view model and state; *Add liquidity* on the wallet's balance card, or the reason it is not there; a purchase's state on its channel row; *Inbound liquidity added* in the payment list; and two transcript lines from the snapshot, added and failed. `LiquidityPrice` works out the price on the member's device, and matches what eclair charged in Phase 0 to the sat. Tests: `LiquidityPriceJvmTest`, and two more cases in `LightningPaymentRowJvmTest`. `m3Audit` meets every budget |
| 8 | **run**, in `GroupLightningRegtestJvmTest`, between *receive* and *pay*: a member prices 200,000 sats from the operator's snapshot, the group authorises it at the default cap, the operator's node splices, every co-signer checks the splice and none refuses; the channel grows from 100,000 to 299,779 sats for 2,521 sats, the figure the member's device estimated; a 50,000-sat payment then arrives with no on-the-fly splice; and *pay*, *close* (124,295 sats to the onchain wallet) and *sweep* run on the spliced channel. Sixty seconds from a fresh network |
| 9 | **not built**, as the plan leaves it: whether the LSP may splice the channel to deliver a payment nobody asked for is the group's decision, and a statement field to record it. Until then such a splice is refused at its first commitment, by every co-signer, as *the group has not authorised buying liquidity for this channel* |
| 10 | the documents, and `GroupLightningRollout.LIQUIDITY_ENABLED` is **on, on mainnet, by decision**, ahead of the mainnet half of Phase 0. Phase 8 passed, which is what the plan named for the gate, but ACINQ's LSP has not been seen to accept a splice in which our funding key does not change, nor its rates read; the first purchase on mainnet is that check. If ACINQ refuses, the splice aborts before anything is signed into it, the snapshot reports the purchase failed with lightning-kmp's reason, and the group's money is where it was. What bounds a purchase meanwhile: `MAINNET_CHANNEL_CAP`, which every co-signer holds the new funding output to, the fee cap the group signs, and S1 to S6 on every device. Every member's device needs this build: one on an older build refuses the splice, and after it every commitment on the new output |

| what the plan said | what it turned out to be |
|---|---|
| Phase 0 before Phase 1 | after: the key check needs a node that names one key at every index, and Phase 1 is what makes `fundingPublicKey(1)` return index 0's key for an injected signer. The stock node in the test injects a private-key signer of its own index-0 key |
| Phase 0 waits for the co-signer core release | it did not need to: none of Phase 0 touches the co-signer. Phases 3 and 5 do, and are built on the co-signer as it stands; the release's rewrite will have to be merged over them |
| Phase 2 adds a context for the splice and nothing else | also `fundingTxIndex` on `RemoteCommitment` and `LocalCommitment`, defaulted to 0. A co-signer cannot otherwise tell the two commitments of one update apart but by their input, and the plan's ledger per funding output needs to know which is the newer |
| a splice's context carries a `spec` like the others | none: `CoSigningWire.Context.spec` is optional now, and every check that reads it refuses a context without one. A splice's money is in the first commitment on the new output, signed just before it |
| Phase 3: `messageOf` over all of the spent outputs | as written, and it returns null when the request leaves one out, which check 3 refuses before any echo: there is no hash to commit to |
| Phase 4: purchase problems join the invoice's `Problem` | a `LiquidityProblem` of their own. The send screen matches every `Problem` to a sentence about an invoice, and neither of these is one |
| Phase 4: the tags | also an `id`, eight random bytes, in the `d` tag and a tag of its own, so two purchases for one channel are two events rather than one replacing the other |
| S1 to S6 run on the shared input | S6's half runs first. The first commitment on the new output arrives before the shared input, with nothing but a spec, so it is checked against the open `buy-liquidity` authorisations for the channel: same number and HTLCs as the parent's ledger, our balance lower by no more than one's fee cap (`openSplice`). The authorisation it fits is recorded on the new ledger, and the shared input is then held to *that* one: its amount, the payment type, the exact fee split, the new output, and the fee the first commitment took, to the msat |
| a purchase is spent once a splice locks | an authorisation is bound to the parent output of the first splice it let, so every attempt at it spends that output, and once one confirms no other can. Nothing has to watch for the lock |
| the parent ledger is marked `spliced` when the splice confirms | not built: the co-signer has no chain client, and nothing needs it. A commitment on a parent output stays checked against its own ledger, and once the splice confirms it spends an output that no longer exists |
| the device that missed the splice follows it, by echoes or by the chain | **not built, and not a splice's problem.** A device that misses any commitment is already out: rule 1 needs each commitment to follow the last one this device signed, so a device that comes back after the group signed on without it refuses every request after. That is [lightning/restored-device-marks.md](./lightning/restored-device-marks.md)'s ground, and a splice adds one more thing for its marks to carry: the ledgers of outputs the device never saw |
| budgets, per payment hash, already work across both live outputs | they did not. The same payment settles on both outputs' commitments at one number, and each consumed its budget, so the second was refused for falling under its floor. Each ledger now keeps what its last commitment consumed, and a sibling at the same number spends only the difference. The test fails without it |
| Phase 6: the node records the purchase from the payments database, and on-the-fly opens get the same row | the purchase's row comes from the answer: `buyLiquidity` adds a `liquidity` payment as it returns `PENDING`. On-the-fly opens still report nothing of their own: `LiquidityEvents.Purchased` carries no channel or transaction, and the payments database has no query for every purchase. They are left as they were |
| Phase 6: `requestInboundLiquidity` for the authorisation's channel | for the node's first `Normal` channel, which is all lightning-kmp's `Peer` offers. A group node has one channel to its LSP; the authorisation still names the channel, and every co-signer holds the splice to it |
| Phase 7's *Try again* acts under the same authorisation | a failed purchase is not tried again by the node at all, as a failed payment is not; *Try again* proposes a new authorisation. Every attempt under one authorisation spends the same parent output in any case, so a retry would have bought nothing a new one does not |
| Phase 7: the price on the member's device | as written, with lightning-kmp's own arithmetic: `LiquidityAds.FundingRate.fees` for the service fee and the LSP's inputs, and `FundingContributions.computeWeightPaid` over a stand-in taproot input for our share. It is the price at the reported feerate before any CPFP bump the node may add, which is why the cap carries 50% more by default |
| *as reported by Thandi, 2 minutes ago* | *the feerate the operator reported in term N*, as the balance card already says its figures are the operator's of a term |
| *Ask the group* returns to the wallet screen with a snackbar | it opens the signing session, as *Send* does: the proposer is the first to sign |
| the payment row comes from the snapshot's `kind` | from a nullable `liquiditySat` on `GroupLightningPaymentRow`. It went into schema 35, which this branch made and nothing installed has, rather than a 36 |
| Phase 10's gate | added here, off, so the screen and the node's purchases ship dark until the regtest run of Phase 8 passes. Co-signers check a purchase either way |
| the screen's JVM tests cover each reason, the fee lines and the payment row | the reasons, the price and the payment row. The form itself is not rendered in a test: it needs the view model over a repository, and what it shows is `LiquidityPrice`'s, which is |
| Phase 8: a device stopped during the splice signs the next payment | not run: a device that misses a commitment is out of that channel's quorum whatever the commitment is, and that is the marks plan's to repair (Phase 5's row above) |
| S4: the new funding amount is at least the old one plus the amount less `purchase.fees.miningFee` | less **our own share of the splice's mining fee**, which is the splice's negative local contribution. `purchase.fees.miningFee` is the LSP's inputs' share, which the group pays from its balance with the service fee, in the first commitment on the new output. Phoenix's estimate (`ChannelManagementFees.miningFee`) is the two shares together, so `estimate.total = -localContribution + purchase.fees.total`, and the authorisation's cap covers both |

### What Phase 0 found

Run on 2026-11-01 against the docker eclair v0.14.4, whose rates are one band, 10,000 to
10,000,000 sats at 100 sats plus 1% and 400 weight units, paid by any of the four payment types.

| step | found |
|---|---|
| 1, our key unchanged | **eclair accepts it.** Three purchases of 200,000 sats in a row on one channel, each naming index 0's key again; eclair's key changed at each, so every new funding output had a script of its own. The source reading in step 1 holds at runtime |
| the price | 2,521 sats for 200,000 at 2 sat/vbyte: our own mining share 221, the LSP's inputs 200, service 2,100 |
| 3, how long eclair waits | **one minute for the whole splice, not per signature.** A purchase needs exactly two group signatures (the first commitment on the new output, then the shared input): at 10 seconds a signature it was answered in 20. eclair starts `channel.quiescence-timeout`, 1 minute by default, at quiescence; at 30 seconds a signature it logged *quiescence timed out in state SpliceWaitingForSigs, closing connection* at 60 seconds exactly, as the second signature arrived. So both of the group's signatures, with their echoes, have to fit in under a minute together, and while they are being made the channel carries no payments |
| what the liquidity is good for | a payment the channel had no room for before arrives over the spliced channel, but **not one over the channel's in-flight maximum**, which was fixed when the channel opened and no splice raises. This LSP sets it at 100% of the opening capacity: the run's channel opened on the fly at 100,000 sats, took 600,000 sats of inbound in three purchases, and eclair then refused a 150,000-sat payment with *in-flight htlcs hold too much value: maximum=100000000 msat*. A payment of 90,000 arrived. Phoenix's channels with ACINQ do not hit this, so ACINQ's mainnet limit is not a share of the opening capacity; on this regtest LSP it is, and the screen (Phase 7) must not promise more per payment than the channel's maximum |
| 4, two commitments live | 11 to 21 seconds on regtest, from the answer to both `splice_locked`, mining a block a second. eclair locks a splice after confirmations here, since this LSP is not zero-conf with the wallet; on mainnet it is the LSP's confirmation policy, and so a matter of blocks rather than seconds unless ACINQ treats the splice as zero-conf |

Fork paths below are relative to
`lightning-kmp-app/experimental/lightning-kmp/modules/core/src/commonMain/kotlin/fr/acinq/lightning/`;
app paths to `composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/`.

---

## What Phoenix does

Read from `ACINQ/phoenix` master (`3a9843a`, 2026-10-28). Android paths are relative to
`phoenix-android/src/main/kotlin/fr/acinq/phoenix/android/`.

| step | Phoenix | here |
|---|---|---|
| entrance | *Request liquidity* on the home screen and *Add liquidity* in settings, shown only when a channel is usable (`LocalChannelInfo.canRequestLiquidity`) and a server-side switch, `is_manual_liquidity_enabled` in ACINQ's `walletcontext.json`, is on | a button on the wallet screen's balance card, behind `GroupLightningRollout.LIQUIDITY_ENABLED` (Phase 10). The switch is ours, not the LSP's |
| no channel | *No channels yet! You first need funds in the wallet.* A manual purchase never opens a channel | the same. The group's first channel comes from its first payment, by on-the-fly funding |
| the amount | a discrete slider, `SatoshiDiscretePicker`, over `LiquidityLimits.liquidityOptions`: 100k, 250k, 500k, 1M, 2M and 10M sats. No free entry | the same six, less those the LSP's rates do not cover and those that would take the channel over `MAINNET_CHANNEL_CAP`. On mainnet today that leaves at most 100k, 250k and 500k |
| what it already has | *Current liquidity*, the sum of each channel's `availableForReceive` | *can be received*, `Snapshot.inboundMsat`, already on the balance card |
| the price | *Estimate liquidity cost* calls `Peer.estimateFeeForInboundLiquidity` with mempool.space's `hourFee` and `remoteFundingRates.findRate(amount)`. Four rows: miner fee, service fee, *Duration: 1 year* (*the remaining unused liquidity will be reclaimed by the service*), total | the same four rows, computed on each member's device from the rate and feerate the operator reports (L5), and named as the operator's claim |
| fee checks | a warning, not a block, when the total is 25% of the amount or more; a block when it exceeds the balance | the same two, and a third the group adds: the fee cap the authorisation will carry |
| confirming | *Review* opens a bottom sheet: the amount is an *initial* amount, used up as payments arrive, reclaimed after a year. A checkbox must be ticked before *Confirm* | the same sheet and checkbox, and *Confirm* becomes *Ask the group*: it proposes a `buy-liquidity` authorisation, and `t` members approve it like a payment (L1) |
| the purchase | `Peer.requestInboundLiquidity(amount, actualFeerate, fundingRate)` — the feerate the estimate raised for CPFP, not the target — a splice with `RequestFunding(…, FromChannelBalance)`. *Processing splice…*, then *Liquidity successfully added!* | the operator's node makes the same call once the authorisation completes (Phase 6), and every co-signer checks the splice before it is signed (Phase 5) |
| the policy | the manual request never passes through `LiquidityPolicy.maybeReject`; that is only for on-the-fly funding and swap-ins. Phoenix's own policy has fee credit off and no inbound target | the same: the authorisation is the policy. Fee credit stays off (L2) |
| history | `ManualLiquidityPurchasePayment`, listed as *Manual liquidity (+N sat)* where N is the fee; its detail shows *Liquidity added* once `lockedAt` is set, the amount bought, service and miner fees, and the channel and transaction | a *Inbound liquidity added* row in the payment list, pending until the splice locks (Phase 6, 7) |
| failure | each `ChannelFundingResponse.Failure` in words (`SpliceOutView.spliceFailureDetails`): *Not enough funds*, *Aborted by peer*, *Invalid liquidity-ads request* (which is how an LSP that declines shows), *Channel is disconnected*, and eleven more; *The requested amount is invalid* when no rate covers it | the same words, from the outcome the operator's snapshot reports, and the co-signers' refusals besides |

Two things Phoenix does that this plan does not. Phoenix's purchase is one person's tap with one
key behind it; here it is a decision of the group's, because the fee leaves money the group
holds. And Phoenix needs nothing to agree to a splice but its own node, where here every
co-signer has to be able to check one.

---

## What exists here, and what is missing

The library half is all there. lightning-kmp already speaks liquidity ads, and the group's node
already buys liquidity: every on-the-fly channel open is a purchase. What is missing is a
splice that a group-held channel can survive.

| piece | what exists | what is missing |
|---|---|---|
| the LSP's rates | `Peer.remoteFundingRates` (`io/Peer.kt:259`), set from the LSP's `init` (`:1255`) | `GroupLightningNode.PeerHandle` (`managers/GroupLightningNode.kt:116-145`) does not expose it, and the snapshot does not carry it, so no member can see a price |
| a fee estimate | `Peer.estimateFeeForInboundLiquidity(amount, targetFeerate, fundingRate)` (`io/Peer.kt:687`), returning `ChannelManagementFees(miningFee, serviceFee)`; `LiquidityAds.FundingRate.fees(...)` (`wire/LiquidityAds.kt:49`) is pure | nothing calls either |
| the purchase | `Peer.requestInboundLiquidity(amount, feerate, fundingRate)` (`io/Peer.kt:782`): a `ChannelCommand.Commitment.Splice.Request` with `RequestFunding(amount, rate, PaymentDetails.FromChannelBalance)` | a `PeerHandle` method, and an operator action that calls it only under a group decision |
| a group decision | kind 30363 with `pay`, `close` and `force-close` (`nostr/lightning/GroupLightningAuthorisationEvent.kt:34-38`) | an action for buying liquidity. The design doc reserved `splice-out` and never built it |
| a group key at the next funding index | none: `ChannelKeys.fundingPublicKey(i)` and `fundingSigner(i)` throw for any `i ≠ 0` when the signer is injected (`crypto/KeyManager.kt:161-187`). Two tests pin the throw (`IcebergChannelTestsCommon.kt:255`, `PrefractalChannelTestsCommon.kt:279`) | `FEATURE-PARITY.md` Phase 6, design B: the same group key at every index |
| what the shared input signs | `SharedFundingInput.sign` (`channel/InteractiveTx.kt:46-67`) calls `signWithPublishedNonce` with no context, which reaches the co-signer as `SigningContext.Unknown` | a `SigningContext.Splice` that says what the splice is: the parent funding output, the new one, both contributions and the `LiquidityAds.Purchase` |
| a co-signer that can sign a splice | `GroupLightningCoSigner.check` (`managers/GroupLightningCoSigner.kt:300-345`) refuses it four ways: more than one input (`:318`), a funding output other than the pinned one (`:334-337`), a context type other than `remote`, `closing` or `local` (`:339-344`), and a sighash over one prevout (`messageOf`, `:290-294`) | a `splice` check, a ledger per funding output, and a way for a device that missed the splice to follow the channel to its new output |
| room to grow | `GroupLightningRollout.MAINNET_CHANNEL_CAP`, 1,000,000 sats, checked against the funding input of every commitment (`GroupLightningCoSigner.kt:395-398`) | nothing: the cap stays, and bounds what can be bought (L4) |
| a screen | the balance card shows *can be received*, `Snapshot.inboundMsat` (`ui/composable/GroupLightningWalletScreen.kt:348-380`) | an *Add liquidity* screen, a proposal summary, a payment row and transcript lines |

One more gap is not about this feature and is fixed by it. The mainnet run left a channel with
16,442 sats of inbound, and noted that a bigger payment would be funded by the LSP splicing the
channel, which the co-signers refuse. An LSP-initiated splice reaches the same index guard
(`channel/states/Normal.kt:471`) and the same refusals. Phases 1 to 5 remove both; Phase 9 says
what an LSP-initiated splice needs on top.

---

## The constraints the plan follows

### 1. A liquidity purchase is a splice

There is no other way to add inbound to an open channel. `requestInboundLiquidity` quiesces the
channel (`stfu`), sends `splice_init` with `request_funding`, and runs the interactive
transaction: the LSP adds inputs worth the amount bought, the new funding output is the old
capacity plus that amount less our share of the mining fee, and the service fee moves from our
balance to the LSP's in the first commitment on the new output (`channel/states/Normal.kt:371-435`,
`:522-620`). Every rule the co-signers hold to was written for a channel that never moves, and
this one moves.

### 2. Two group signatures inside one negotiation

The splice needs the group twice before `tx_signatures`: once for the LSP's first commitment on
the new funding output (`InteractiveTxSigningSession.create`, `channel/InteractiveTx.kt:1225-1300`),
and once for the shared input, the old funding output being spent (`:496`). Each is an Iceberg
round two from `2t-1` co-signers, behind an echo from `n-t+1`. The LSP's interactive-tx timeout
has never been measured, and neither has the 60-second signing deadline
(`RemoteIcebergFundingSigner.deadline`). A splice doubles what must fit inside it.

### 3. Two live commitments until the splice locks

From `tx_signatures` until both sides send `splice_locked`, a channel has one active commitment
per funding output, and `Commitments.sendCommit` (`channel/Commitments.kt:896-912`) signs one of
each at the *same* commitment index. The co-signer's ledger is keyed by channel
(`IcebergChannelLedger`, primary key `wallet, channelId`), and its rule 1 refuses a second
transaction at an index it has already signed. The ledger has to be keyed by funding output,
and every update in that window costs the group twice.

### 4. The fee leaves the group's balance

`FromChannelBalance` takes the mining and service fees out of the group's side of the new
commitment. `CoSignerPolicy`'s rule 3 lets the group's value fall only by an authorised
payment that settles, or by the commitment fee allowance (5,000 sats by default). A purchase
that costs more than the allowance is refused; one that costs less silently uses it up. Neither
is right. The fall has to be named by something the group signed.

### 5. Devices that were not there

The echo needs `n-t+1` co-signers; the rest may be offline. A device that comes back after the
splice finds every request now spends an output it never approved, and check 3's pin refuses
them all, for good. Without a catch-up rule, every splice permanently removes the devices that
missed it from the channel's quorum. A group of four, of whom three must answer, would be one
missed splice from a wallet nobody can sign for.

### 6. The operator is hostile

As in every plan in [lightning/](./lightning/README.md). The operator chooses the funding rate,
the feerate, which inputs the LSP's are, and when to ask. No rule below rests on any of those
being honest. What a co-signer cannot check — that the LSP's inputs exist, that the feerate is
the market's — it bounds instead.

---

## Decisions

| | decision | why |
|---|---|---|
| L1 | **A purchase is a group decision.** A new 30363 action, `buy-liquidity`, FROST-signed through `proposeSigning`, names the channel, the amount, a cap on the total fee and an expiry. The operator's node buys only under one, and co-signers sign a purchase only under one | D6 already says people approve splices, and constraint 4 says the fee has to be named by something the group signed. Phoenix's one-tap purchase is the operator deciding alone, which the trust table rules out |
| L2 | **Fees from the channel balance, and nothing else.** `PaymentDetails.FromChannelBalance` only, which is what `requestInboundLiquidity` sends. Fee credit stays off (`maxAllowedFeeCredit = 0.msat`, `GroupLightningNode.kt:485-494`) | fee credit is a balance the LSP keeps, outside any commitment a co-signer can rebuild. A purchase paid from it would be one the group cannot audit |
| L3 | **Design B: the same group funding key at every funding index.** The LSP's funding key may change per splice; the co-signer records whichever key the splice names | Iceberg has no resharing and no derivation, so a fresh key per index means a fresh ceremony per splice. `FEATURE-PARITY.md` names one risk — something validating that our key rotates. eclair v0.14.4's source has no such check, and rotates its own key, so the new output's script still changes; Phase 0, step 1 runs it before any code |
| L4 | **The mainnet cap stays.** The amount picker offers only amounts that keep the channel at or under `MAINNET_CHANNEL_CAP`, and every co-signer refuses a new funding output over it | the cap is the rollout's bound on loss while the scheme is unreviewed. Lifting it is that gate's decision, not this feature's. Under the cap, a 25,000-sat on-the-fly channel can still take up to 975,000 sats of inbound |
| L5 | **The price comes from the LSP, through the operator, and every member computes the fee itself.** The snapshot carries the LSP's `WillFundRates` and the feerate the operator would use; the screen runs `FundingRate.fees(...)` locally. The authorisation caps the *total*, so a feerate the operator lies about can only make the purchase fail | the service fee is a pure function of the rate and the amount; the mining fee depends on a feerate no member can check from a phone. A cap bounds both without trusting either |
| L6 | **One ledger per funding output, and a channel's pin moves only through a splice the device approved or can prove was approved.** Rule 1 and the floor apply per funding output; budgets stay per payment hash (`IcebergBudgetSpend`), so an HTLC on both live commitments spends its budget once | constraints 3 and 5 |
| L7 | **The shared input is signed last, and only for a splice whose first new commitment this device approved.** Both requests carry the new funding txid, and the co-signer refuses a shared-input request whose txid it has not approved a commitment for | lightning-kmp already signs in that order. Tying the two means the group never gives up the old funding output for a new one it has not checked the commitment of |
| L8 | **Competing splices are allowed; competing purchases are not paid twice.** A splice that fails before `tx_signatures` may be retried under the same authorisation with a new funding txid. A splice that got past `tx_signatures` is not retried: it confirms, or it is bumped, which is out of scope | every attempt spends the same funding output, so at most one ever confirms. Each attempt's floor is checked on its own; the fee leaves the group once. eclair adds a limit of its own: it refuses any splice while the latest funding transaction is unconfirmed and unlocked, or has unconfirmed RBF attempts (`Channel.scala:1171-1177`), so a second purchase, or one right after an on-the-fly splice, waits until the earlier splice locks |

### Who can do what, once this is in

| holder | can | cannot |
|---|---|---|
| the operator | choose when to buy under an open authorisation, and at which of the LSP's rates. Refuse to buy. Pick a feerate that makes the purchase fail | buy without an authorisation, buy more or a different amount than it names, pay more than its fee cap, or send any of the group's balance anywhere but the new funding output |
| a member | propose a purchase, see its price before anyone approves | buy alone. `t` approvals, as for any decision |
| the LSP | refuse, or quote a rate the fee cap does not cover | take a fee the authorisation does not allow: the co-signers rebuild the new commitment and refuse it |
| `t - 1` members, colluding | refuse to sign, so the purchase stalls | sign one |

---

## Phase 0 — two checks and two measurements

**About three days. No product code. Needs the co-signer core release
([lightning/README.md](./lightning/README.md), step 2) merged first.**

1. **Does the LSP accept a splice in which our funding key does not change?** This is about
   the key, not about which way the money moves. Every splice ends in a new funding output,
   MuSig2 of a key from each side, and each side names its key for it: we name ours in
   `splice_init` when we start the splice (a liquidity purchase, a splice-out) and in
   `splice_ack` when the LSP starts it (an on-the-fly splice for an incoming payment). Stock
   lightning-kmp names a fresh key per funding index, `hardened(fundingTxIndex)` under the
   funding master key. The group has one key, from its Iceberg ceremony, and nothing derives
   more from it, so design B (L3) names that same key at every index. The question is whether
   the LSP refuses that, for every kind of splice at once.

   **eclair's source, at v0.14.4** (the version `testing/regtest/` runs), says it does not.
   `Channel.scala:1162` handles `splice_init`: it takes `msg.fundingPubKey` as it arrives and
   builds the new funding script from it (`:1181`, `:1215`). When eclair starts the splice, it
   does the same with the key in our `splice_ack` (`:1268`, `:1276`). Nothing in its channel or
   wire code compares our key with the parent commitment's. And eclair rotates its *own* key on
   every splice (`channelKeys.fundingKey(parentCommitment.fundingTxIndex + 1)`, `:1180`), so
   the new funding output's script differs from the old one's even though our half of it is
   the same: the two outputs are never the same script.

   What is left is to run it: on regtest, a stock (non-threshold) lightning-kmp node whose
   `fundingPublicKey(1)` returns index 0's key, buying liquidity from the docker eclair, then a
   payment and a mutual close over the spliced channel. Then the same purchase once against
   ACINQ's mainnet LSP from an ordinary node, since the production LSP need not be v0.14.4 as
   released. If either refuses, L3 is wrong and this plan stops here: a key per index means an
   Iceberg ceremony per splice.

   The same reading found two refusals that bound the plan (`Channel.scala:1171-1177`). eclair
   refuses a splice while the channel's latest funding transaction is unconfirmed and not yet
   locked (`DualFundedUnconfirmedFundingTx`), and while that transaction has unconfirmed RBF
   attempts. So a purchase cannot follow another splice until that one has locked. L8 and Phase
   6 take this into account.
2. **What ACINQ's mainnet LSP sells.** Log the `liquidityRates` in the `init` the group node
   already receives (`GroupLightningNode.dropReason` already logs the `init`'s features): the
   rates' `minAmount`, `maxAmount`, `feeProportional`, `feeBase` and `fundingWeight`, and its
   payment types. If the smallest `minAmount` is above `MAINNET_CHANNEL_CAP` less a channel's
   capacity, nothing can be bought on mainnet under L4, and the feature waits for the cap.
3. **How long the LSP waits inside a splice.** The signer-hygiene plan's Phase 5 measures the
   wait between `commit_sig` and `revoke_and_ack`. Extend the same harness to a splice: a
   `FundingSigner` that sleeps before each answer, grown until eclair aborts the interactive
   transaction. The number goes beside `RemoteIcebergFundingSigner.deadline`, and if two
   deadlines do not fit inside it, the screen says the wallet needs devices that stay on.
4. **How long two commitments stay live.** With the LSP as a zero-conf peer, measure from
   `tx_signatures` to both `splice_locked` on regtest. It sets how long every payment costs
   double (constraint 3).

---

## Phase 1 — the same group key at every funding index, in the fork

**About two days, on `claude/group-lightning-wallet` in `experimental/lightning-kmp`.** This is
`FEATURE-PARITY.md` Phase 6.

- `ChannelKeys.fundingPublicKey(i)` and `fundingSigner(i)` return the injected signer's key and
  signer at every index. `requireIndexZero` goes; its doc comment's warning ("would silently
  produce a funding output nobody can spend") is answered by design B: the key at index 1 *is*
  the group's key, which the group can spend.
- `fundingKey(i)` keeps throwing for an injected signer. Segwit v0 splices still go through it
  (`InteractiveTx.kt:56`) and still fail by name.
- Nothing else in lightning-kmp changes. The shared input already goes through the seam
  (`InteractiveTx.kt:62`), and `InteractiveTxSession` already builds its signer from the
  session's own index (`:755`).

**Tests,** in `IcebergChannelTestsCommon` and `PrefractalChannelTestsCommon`, replacing the two
"fails loudly" tests with what `FEATURE-PARITY.md` lists:

1. A liquidity purchase on a group-backed channel, the group as initiator, against a stock
   counterparty with liquidity ads: the new funding output is MuSig2 of the group key and the
   counterparty's new key, the first commitment on it pays the fee from our balance, and the
   splice transaction verifies (`correctlySpends`).
2. A splice the counterparty starts, the group as accepter (Phase 9's path).
3. A mutual close and a force close after a splice, against the new funding output.
4. A disconnect mid-splice, and a resume: a fresh published-nonce session, never the old one.
5. No label signs two messages, across every case above.
6. `SpliceTestsCommon` passes unmodified.

---

## Phase 2 — the signer is told it is signing a splice, in the fork

**About three days, in the fork, then the submodule pointers.**

`SigningContext` (`crypto/FundingSigner.kt:130-158`) has four cases and none of them is a
splice. Add one:

```kotlin
data class Splice(
    val channelParams: ChannelParams,
    val parentFundingTxIndex: Long,
    val parentFunding: Transactions.InputInfo,       // the output being spent
    val fundingTxIndex: Long,
    val fundingOutputIndex: Int,                     // in the splice transaction
    val remoteFundingPubKey: PublicKey,              // at the new index
    val localContribution: Satoshi,                  // negative: our share of the mining fee
    val remoteContribution: Satoshi,
    val purchase: LiquidityAds.Purchase?,
    val feerate: FeeratePerKw,
    val spentOutputs: Map<OutPoint, TxOut>,          // every input, ours and the LSP's
) : SigningContext()
```

- `SharedFundingInput.sign` passes it (`InteractiveTx.kt:62`), built from the session's
  `fundingParams`, `liquidityPurchase` and the shared transaction.
- `RemoteCommitment`'s context gains the funding output it is a commitment *on*
  (`fundingTxIndex` and `fundingTxId`), so a co-signer can tell the commitment on the new output
  from the one on the old, at the same commit index (constraint 3).
- `RemoteIcebergFundingSigner` carries `spentOutputs` into `IcebergRoundTwoRequest`, which
  already has a field for the extra UTXOs.

**Tests:** in the Phase 1 cases, a recording signer asserts that the shared input arrives with a
`Splice` context whose purchase, contributions and funding output match what the channel
negotiated, and that every commitment's context names the funding output it spends. Then advance
the submodule in `lightning-kmp-app`, and that in the app, as every library phase has.

---

## Phase 3 — the wire carries a splice, in the app

**About two days.**

- **`CoSigningWire.Context`** (`wallet/lightning/CoSigningWire.kt:81`) gains type `splice` and
  its fields, and `context()` (`:196-201`) maps `SigningContext.Splice` to it. Every new field
  is defaulted, so an old build decodes the request and refuses it as "an unknown kind of
  transaction", which is the safe answer.
- **`SigningRequest`** carries every spent output and the index of the input being signed.
  `messageOf` (`GroupLightningCoSigner.kt:290-294`) computes the taproot sighash at that index
  over *all* of them. A co-signer cannot check the LSP's inputs exist, and does not need to:
  BIP341 commits the signature to every spent output's amount and script, so a request that
  lies about them gets a signature nobody can mine, never one that moves money.
- **Check 3** (`:318`) generalises. A commitment or a close still spends exactly the funding
  output. A splice spends the pinned funding output once, at the index the request names, and
  its other inputs are not the group's.

**Tests:** a round trip of each context type, and an old-format request still decoding;
`messageOf` matches lightning-kmp's own sighash for a three-input splice built in the test.

---

## Phase 4 — `buy-liquidity`, the group's decision

**About three days.**

**The authorisation**, kind 30363, a fourth action beside `pay`, `close` and `force-close`:

| tag | carries |
|---|---|
| `action` | `buy-liquidity` |
| `chain`, `wallet` | which wallet |
| `channel` | the channel to splice. A wallet with no channel cannot buy: the first payment opens one |
| `amount` | sats of inbound to buy. The LSP's `requestedAmount` |
| `max-fee` | sats, mining and service together |
| `expires` | as for `pay`; 72 hours by default |

- `GroupLightningAuthorisationEvent` gains a `BuyLiquidity` data class and its reader. Today's
  readers return null for an action they do not know (`:110-121`), so an old build ignores the
  event and refuses the splice that follows. Fail safe.
- `GroupLightningAuthorisations` gains `liquidityPurchases(wallet)` (open, unexpired, not yet
  used by a locked splice) and `proposeBuyLiquidity(...)`, through
  `FrostSigningManager.proposeSigning`, like `proposeChannelAction`.
- `ProposedEvent.summarize` reads *Add 500,000 sats of inbound liquidity for up to 6,200 sats*.
  The proposal screen shows it as it shows a payment: amount, cap, expiry, and the channel's
  capacity before and after.
- The group's own kinds are unchanged: this is a new value in an existing tag, not a new kind.

**Tests:** round trip; an old reader returning null; a `buy-liquidity` with an amount that would
take the channel over the mainnet cap is refused at proposal time (and Phase 5 refuses it
again on every device, since the proposer is not trusted either).

---

## Phase 5 — the co-signer checks a splice

**About a week and a half. The phase with the security argument in it.**

`GroupLightningCoSigner.check` gains a `splice` branch, `checkSplice`, and `CoSignerPolicy`
gains a splice rule beside its three. A co-signer approves a **shared-input request** only if
all of these hold, on top of checks 1 to 4 as they stand:

| | rule | why |
|---|---|---|
| S1 | an open `buy-liquidity` authorisation names this channel, and its amount is the context's `purchase.amount` | L1 |
| S2 | the request spends the funding output this device has pinned, at the input the request names | the group gives up only the output it knows |
| S3 | exactly one output of the splice is `MuSig2(group key, context.remoteFundingPubKey)`, at `fundingOutputIndex`, and its amount is under `MAINNET_CHANNEL_CAP` | L3, L4 |
| S4 | the new funding amount is at least the old one plus `purchase.amount` less `purchase.fees.miningFee` | the LSP put in what it is being paid for. Without it, a purchase could be a fee for nothing |
| S5 | `purchase.fees.total ≤ max-fee`, the payment details are `FromChannelBalance`, and the purchase is `LiquidityAds.Purchase.Standard`, never `WithFeeCredit` | L2, and the cap the group signed. lightning-kmp subtracts any current fee credit from a manual purchase's fees, so the co-signer refuses a purchase that claims any |
| S6 | this device has already approved the first remote commitment on that new funding output | L7 |

And a **commitment request on the new funding output** is approved if it passes check 5 as it
stands, on a ledger opened from the parent's: same offered HTLCs, same budgets, and a floor
lowered by exactly `purchase.fees.total`, once. A second commitment on the parent output at the
same index is approved against the parent's ledger (constraint 3).

**The ledger, schema 34 to 35.** `IcebergChannelLedger`'s primary key becomes
`(wallet, channelId, fundingTxId)`, with a `fundingTxIndex` and a `state`: `live`, `spliced`
(a later output has locked) or `abandoned` (an attempt that never reached `tx_signatures`). An
`AutoMigration` keeps today's rows as each channel's index 0. A commitment request is checked
against the ledger of the output it spends; one for a `spliced` output is refused.

**How a co-signer learns a splice locked.** It cannot see `splice_locked`, and does not need
to for safety: each funding output's ledger holds its own floor, so a commitment on the old
output is checked as strictly after the splice as before it, and once the splice confirms that
commitment spends an output that no longer exists. The co-signer moves a parent ledger to
`spliced` when its own Electrum client (`GroupChainClients`) sees the splice transaction
confirmed, and refuses commitments on it from then on. That keeps the ledger honest without
believing anything the operator says about when the splice locked.

**The device that missed it** (constraint 5). The room holds the 30358 requests and the 30359
echoes. A co-signer that meets a commitment on a funding output it has no ledger for looks for
the shared-input request that made it, and adopts the new output if either:

- it finds that request, the request passes S1 to S5 *as this device checks them now*, and
  `n-t+1` participants echoed its message hash — so at least one honest co-signer approved it,
  under the same rules; or
- its own Electrum client sees a transaction that spends its pinned output and pays S3's output.

It then opens the new ledger as S6's commitment would have, by replaying the checks on that
commitment's request from the room. If neither holds, it refuses, and the transcript says why
(*This device did not see the splice that moved channel 3442db60…*).

**Tests,** in the four-device harness (`GroupLightningCoSigningJvmTest`, and the mainnet suite
with it), one hand-built request per rule, each asserting no 30361 was published:

- S1: no authorisation; an expired one; one for another channel; a different amount.
- S3: the new output keyed to the operator's own key; two outputs to the group; over the cap.
- S4: an LSP contribution short by one sat. S5: a fee one sat over; a `FromFutureHtlc` purchase.
- S6: a shared-input request before its commitment.
- The floor: a first commitment on the new output that takes one sat more than the fee.
- Two commitments at one index on two outputs both signed; a third on the old output after the
  new one is `spliced`, refused.
- A device offline for the splice adopts the new output from the echoes, and one that sees only
  the request, with too few echoes, refuses until the transaction confirms.
- A retried splice under the same authorisation: both attempts' shared inputs are signed, and
  only one can confirm.

---

## Phase 6 — the operator buys

**About four days.**

- **`PeerHandle`** gains `fundingRates(): LiquidityAds.WillFundRates?`,
  `estimateLiquidity(amount, feerate, rate)` and `buyLiquidity(amount, feerate, rate)`, over
  the three `Peer` members above. The production handle wires them; `FakeGroupNode` records
  them.
- **The operator's action,** `buyAuthorised`, beside `payAuthorised` (`GroupLightningNode.kt:272`),
  on the same watch of kind 30363 and under the same lock. For each open `buy-liquidity` it has
  not acted on: `WillFundRates.findRate(amount)`, the node's own feerate (the Electrum feerates
  it already refreshes every ten minutes, where Phoenix uses mempool.space's `hourFee`), an
  estimate, and a purchase only if the estimate is under the authorisation's cap. It passes the
  estimate's raised feerate to the purchase, as Phoenix does, not the target. Once per authorisation and
  funding txid, as payments are once per hash: a node that tried and failed does not try again
  by itself (L8 permits a retry; the member asks for it from the screen). While any channel has
  an earlier splice that has not locked, the node does not try at all, since eclair would abort
  the purchase (Phase 0, step 1). It reports the authorisation as `waiting` with the earlier
  splice's txid, and acts once that splice locks, if the authorisation is still open.
- **What the snapshot carries** (`WalletWire.Snapshot`), every field defaulted:
  `fundingRates` (the LSP's `WillFundRates`, as rates and payment types), `feerate` (sat/vbyte,
  the operator's claim), and the outcome of each purchase it acted on: `waiting` with the
  earlier splice's txid, `pending` with this splice's txid, `locked`, or `failed` with
  lightning-kmp's `ChannelFundingResponse.Failure` name
  (`InsufficientFunds`, `InvalidLiquidityAds`, `ChannelNotQuiescent`, …).
- **The payment list.** `PaymentView` gains a `kind` (`payment`, defaulted, or `liquidity`)
  and the purchase's amount and fees. lightning-kmp already writes a
  `ManualLiquidityPurchasePayment` (`db/PaymentsDb.kt:563`) to the group node's payments
  database when a `FromChannelBalance` purchase is stored (`io/Peer.kt:1048-1058`), and emits
  `LiquidityEvents.Purchased` (`NodeEvents.kt:55`). The event carries no channel or txid, so the
  node reads the row back with `getInboundLiquidityPurchase(txId)` (`db/PaymentsDb.kt:15`), using
  the txid from `ChannelFundingResponse.Success`. The on-the-fly opens the node already makes are
  stored as `AutomaticLiquidityPurchasePayment` and get the same row, which today they do not:
  the collector (`GroupLightningNode.kt:523-527`) reads only `PaymentReceived` and `PaymentSent`.

**Tests:** with `FakeGroupNode`, an authorisation is bought once; an estimate over the cap is
never sent; a failure is reported and not retried; the snapshot round-trips with and without the
new fields.

---

## Phase 7 — the screen

**About a week.** The conventions in `CLAUDE.md` apply in full: spacing from the scale, colour
from roles, every string in the catalogue in sentence case, amounts as format arguments, 48dp
targets, four states through `ScreenStateTransition`, `NavigateBackButton`, `readableContent()`,
and `m3Audit` before committing.

- **The entrance.** On `GroupLightningWalletScreen`'s balance card, beside *can be received*,
  an *Add liquidity* button. It is there when the wallet has a channel in `Normal`, the
  snapshot carries rates, and the channel has room under the cap. Otherwise the reason is said
  where the button would be: *Receive a payment first: it opens the channel*; *The channel is
  at the most it may hold on mainnet for now*; *The operator has not reported the LSP's prices*.
- **`GroupLightningLiquidityScreen`,** pushed, with its route and view model:
  - the amount, on Phoenix's discrete slider of 100k, 250k, 500k, 1M, 2M and 10M sats,
    filtered to the rates' `minAmount..maxAmount` and to what keeps the channel under the cap;
    with *Current liquidity* above it, as Phoenix has;
  - the fee, computed on this device from the snapshot's rate and feerate: miner fee, service
    fee, *Duration: 1 year* with Phoenix's sentence on what happens after it, the total, and
    *as reported by Thandi, 2 minutes ago*, as the balance is;
  - Phoenix's two checks: a warning when the total is 25% of the amount or more, and no way on
    when it exceeds the group's spendable balance;
  - a review sheet with Phoenix's explanation (an initial amount, used up as payments arrive,
    reclaimed after a year) and its acknowledgement checkbox;
  - the fee cap the proposal will carry: the estimate with headroom (50% by default, so a
    feerate rise between proposal and approval does not fail it), shown and editable;
  - the channel's capacity and inbound before and after;
  - *Ask the group*, which proposes the authorisation and returns to the wallet screen with a
    snackbar (`rememberNotifier`): *Asked the group to add 500,000 sats of inbound liquidity*.
- **On the wallet screen afterwards,** a purchase that is approved and not yet locked shows on
  its channel row: *Adding 500,000 sats of inbound liquidity*, then the row's capacity changes.
  A failure says lightning-kmp's reason in words and offers *Try again*, which acts under the
  same authorisation if it is still open.
- **The payment list** shows a purchase as *Inbound liquidity added*, the amount bought, and its
  fee, as Phoenix does, never as money sent.
- **Transcript lines,** in [group-lightning-transcript.md](./group-lightning-transcript.md)'s
  style: the proposal's summary; *Thandi's node is adding 500,000 sats of inbound liquidity*;
  *Inbound liquidity added: 500,000 sats, for 6,184 sats*; each refusal from S1 to S6 in words.

**Tests:** JVM composable tests for each reason the button is absent, the fee lines from a fixed
rate, and the payment row; `GroupLightningWalletViewModelJvmTest` gains the purchase's states.

---

## Phase 8 — end to end, on regtest

**About three days.**

`testing/regtest/lsp.conf` already sells liquidity, `from_channel_balance` among its payment
types. `GroupLightningRegtestJvmTest` gains a step after *receive*: a member proposes buying
200,000 sats, three approve, the operator's node splices, every co-signer checks and signs, the
splice confirms after a block, and every member's snapshot shows the bigger channel and the
purchase in the payment list. Then a payment larger than the old inbound arrives without a new
on-the-fly splice, and the existing *pay*, *close* and *sweep* steps run against the spliced
channel. One device is stopped during the splice and restarted after it, and must sign the next
payment's commitments (the catch-up rule).

---

## Phase 9 — the splices the LSP starts

**About a week, and its own decision.** Not needed for the button, but the same machinery, and
what the mainnet run's last row asks for.

When a payment arrives that the channel cannot hold, the LSP proposes an on-the-fly splice
(`Normal.kt:452-511`) paid from the payment itself (`from_future_htlc`) or from the balance.
There is no authorisation to point at, because nobody in the group asked. What the co-signers
already allow for an on-the-fly *open* — the LSP's fee out of the incoming amount, up to the
statement's `maxLiquidityFeeSat` — is the rule to extend: S1 becomes "an incoming HTLC in the
new commitment that the purchase is for, and a fee within the statement's cap", S4 and S5 stay,
and the fee comes from the incoming HTLC, never from the balance the group already holds. This
is the auto-liquidity half of Phoenix; whether the group wants it on is a statement field, off
until decided.

---

## Phase 10 — rollout and the documents

**About a day.**

- **A gate,** `GroupLightningRollout.LIQUIDITY_ENABLED`, off until Phase 8 passes. While off, the
  button is not shown and the operator does not act on `buy-liquidity`; co-signers *do* run
  Phase 5, so a group whose devices are all upgraded is ready when the gate lifts.
- **Old builds.** A device on a build before Phase 5 refuses every splice and, after the first
  one, every commitment on the new output: it is out of the channel until it upgrades, and then
  catches up by Phase 5's rule. The echo needs `n-t+1`, so a purchase succeeds only while at
  most `t-1` devices are on old builds. The screen lists who has not answered a proposal, as the
  ceremony screen does.
- **The documents.** In [group-lightning-wallet.md](./group-lightning-wallet.md): the build
  table's check 8 and Phase 13 rows, and the mainnet run's last sentence. The comments that say
  a channel never grows (`GroupLightningRollout.kt:34`, `GroupLightningCoSigner.kt:394`). This
  note's own table of where the build chose differently. The README's index.

---

## Order, and what can run in parallel

| phase | depends on | lands in | estimate |
|---|---|---|---|
| 0 checks | the co-signer core release | nowhere | 3 days |
| 1 design B | 0 | lightning-kmp | 2 days |
| 2 splice context | 1 | lightning-kmp, then the app's pointer | 3 days |
| 3 wire | 2 | app | 2 days |
| 4 authorisation | 0 | app | 3 days |
| 5 co-signer | 3, 4 | app, schema 35 | 1.5 weeks |
| 6 operator | 3, 4 | app | 4 days |
| 7 screen | 4, 6 | app | 1 week |
| 8 regtest | 5, 6, 7 | app | 3 days |
| 9 LSP-initiated | 5 | app | 1 week |
| 10 rollout | 8 | app | 1 day |
| **total, without 9** | | | **about 5 to 6 weeks, one engineer** |

Phases 1–2 (the fork) and 4 (the authorisation) are independent and can run in parallel. The
first thing a person can see is the end of Phase 7, on regtest.

---

## What this does not do

- **Splice in from the onchain wallet.** The group's FROST inputs inside lightning-kmp's
  interactive transaction: two threshold protocols in one negotiation. The design doc leaves it
  for its own plan, and so does this one.
- **Splice out.** `splice-out` stays reserved. Its rules are S2, S3 and S6 with an extra output
  to an onchain wallet address, so Phase 5 is most of it.
- **Bumping a stuck splice.** `Normal.kt` handles no `tx_init_rbf` (only the opening states
  do); a stuck splice is bumped by `Peer.spliceCpfp`, another splice with a mining fee. Until it is authorised as its own action, a
  purchase confirms at the feerate it was made at.
- **Lifting the mainnet cap.** L4.
- **Fee credit.** L2.

---

## Risks and open questions

| risk | why it matters | what to do |
|---|---|---|
| the LSP insists our funding key rotates | design B produces a splice the LSP refuses | eclair v0.14.4's source does not check it (Phase 0, step 1). The run on regtest and the one against ACINQ's mainnet LSP confirm it before any code. The fallback is a ceremony per index, which is its own plan |
| two group signatures do not fit the LSP's splice timeout | every purchase aborts, and burns labels | Phase 0, step 3. A desktop co-signer as the always-on third device, as the design doc already suggests |
| a splice that never locks | two live commitments, double signatures, indefinitely | the confirmed-transaction rule moves the ledger on; the operator's snapshot says *pending* with the txid so a member can look |
| a co-signer adopts a splice it should not have | the group's pin moves to an output it did not check | adoption replays S1 to S5 on this device, and needs `n-t+1` echoes or a confirmed spend of the pinned output; there is no third way |
| the mainnet cap leaves too little to buy | the button is mostly absent on mainnet | Phase 0, step 2 measures it; the cap's own gate decides |
| this lands before the co-signer core release | two plans rewriting `check`, the ledger and `commitIndex` at once | Phase 0 depends on that release; the schema numbers follow whatever it takes |

**One decision belongs to the group, not to this plan:** Phase 9, whether the LSP may splice the
channel to deliver a payment without anybody asking. The button needs only Phases 0 to 8.
