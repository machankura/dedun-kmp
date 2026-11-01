# Onchain deposits into the group's Lightning wallet: a swap-in for the group's node

Each member gets a bitcoin address of their own that pays the **group's Lightning wallet**. A coin sent
there is spliced into the group's channel once it has 3 confirmations, and it is counted to that
member as a Lightning deposit, like every invoice they are issued. Phoenix calls this swap-in. This
note plans it for a node whose keys nobody holds alone.

**Planned.** Written against `00b723a0` (`dedun`, after the revert below), with the
`lightning-kmp-app` submodule at `7aaacadc` and its `lightning-kmp` at `86f0eb3a`.

**Where this comes from.** A first attempt built swap-in for the *profile's personal* node instead: a
screen of personal deposit addresses. It was merged into `dedun` as `316fc153` and reverted in
`00b723a0`. It offered members a place to put money the group never counts, and the swap-in that was
wanted is the group's. The work is still on `claude/lightning-onchain-swap-plan-76701f`. Three things
from it carry over:
- the measurements in its Phase 0 (`6bf92ecc`, `docs/personal-swap-in-wallet.md` on that branch);
- the pure coin statuses (`SwapInWalletView`, `0f2e5594`);
- the refund's broadcast check (`NodeRefunder.broadcastAndCheck`, `3eb07d5b`). lightning-kmp's
  `broadcastTransaction` returns a transaction id even when the server refuses the transaction.

The headline: **the swap-in address is a MuSig2 of the group's Iceberg key and the LSP's swap-in key,
with a refund leaf that only the group's FROST key can spend, and a different refund key for each
member and index. So every address is one member's, every device can derive every address, and no
device can move a coin alone.** Three parts are missing today:
- a signer in the fork for the swap-in input, like the one the channel's funding input already has;
- a co-signer rule that signs a splice which only adds group swap-in coins;
- a ledger row that counts the credit to the member whose address was paid.

Read [group-lightning-wallet.md](./group-lightning-wallet.md) (D3-D8, co-signing),
[group-lightning-liquidity.md](./group-lightning-liquidity.md) (how a splice is co-signed, S1-S6),
[member-deposits.md](./member-deposits.md), [payouts-only.md](./payouts-only.md) (D2, D8, D9) and
[shared-key-derivation.md](./shared-key-derivation.md) (its one rule) first.

---

## What the group's node does today

**It has a swap-in wallet, and it must never be shown.** `Peer` builds a `SwapInWallet` from
`keyManager.swapInOnChainWallet` whenever its client is Electrum (`Peer.kt:283`). The group node's key
manager delegates that to `LocalKeyManager(seed, chain, unusedSwapInXpub(chain))`
(`GroupNodeKeyManager.kt`, `GroupLightningCoSigner.kt:77,90`). Its addresses are therefore 2-of-2s
between a key from the *operator's node seed*, which every member holds (D4), and the LSP. Nothing
calls `startWatchSwapInWallet` for the group node, so nothing ever swaps in. Swap-in is off only
because that call is missing; no flag turns it off.

**Co-signers refuse every splice that adds the group's money.** `checkSplice`
(`GroupLightningCoSigner.kt:448-493`) refuses for three reasons:
- the splice has no liquidity purchase (S1, :476);
- the group's own contribution is above zero (:483);
- the new output's first commitment has no open `buy-liquidity` authorisation (`openingOf`, :410-446).

On top of that, payouts-only refuses to FROST-sign any `buy-liquidity` at all
(`GroupLightningPayouts.kt:30-38`). So today, a swap-in splice could not get one signature.

**A swap-in credit would be nobody's.** `paymentView` (`GroupLightningNode.kt:566-575`) sets
`paymentHash` only for a `LightningIncomingPayment`. A splice-in arrives as an
`OnChainIncomingPayment` with no hash, and `DepositLedger.recordLightning` (:59-82) drops anything
without one. Under payouts-only D8, a member's Lightning balance is their Lightning deposits less
their payouts, so an uncounted swap-in belongs to nobody.

**The wallet runs on mainnet.** `GroupLightningRollout.NETWORK` is `NodeParamsManager.chain`, which is
fixed to `Chain.Mainnet`. Every channel is capped at `MAINNET_CHANNEL_CAP`, 1,000,000 sat. The LSP
is ACINQ's, with lightning-kmp's default swap-in windows:
- 3 confirmations before a coin swaps in;
- 17,280 blocks (about 4 months) after which it can no longer swap in;
- 25,920 blocks (about 6 months) before the refund opens.

---

## What the fork already has, and what it lacks

From `lightning-kmp/modules/core/.../lightning/`:

| step of a swap-in input | today | what the group needs |
|---|---|---|
| the address | `SwapInProtocol(user, server, userRefund, delay)`. The key path is `Musig2.aggregateKeys([user, server])`, unsorted with the user key first. The leaf is `<userRefund> CHECKSIGVERIFY <delay> CSV` (`SwapInProtocol.kt:17-25`) | `user` is the group's Iceberg key. Any `PublicKey` is accepted, so the address is valid as it is |
| the user key | one key for every address (`KeyManager.kt:343-388`). Only the refund key changes per index (`getSwapInProtocol`, :379-383) | the same. Iceberg has no derivation (liquidity L3), so one key for every address suits it |
| the server key | the LSP xpub at `perUserPath(nodeId)` (`LocalKeyManager.kt:43-52`) | unchanged: the group node's id is in its statement |
| our nonce | `InteractiveTxSession.send()` (`InteractiveTx.kt:847-856`, not `suspend`) makes it with `Musig2.generateNonce(…, userPrivateKey, …)` as it sends `tx_add_input`, and keeps the secret in `swapInSecretNonces` | the aggregate of a round-one *contribution* from the Iceberg pool, which is synchronous already (`RemoteIcebergFundingSigner`'s `transport.contributions(label)`). No secret is kept |
| the nonce exchange | `tx_complete`'s `SwapInNonces` TLV (:786-821) | unchanged |
| our partial signature | `SharedTransaction.sign` (:496-586, `suspend`), at :545-557: `signSwapInputUser` with `userPrivateKey`. This runs after both `tx_complete`s and before the LSP's `tx_signatures` | Iceberg round two, as `RemoteIcebergFundingSigner` does for the funding input: `suspend` and under a deadline. The key order is `[user, server]`, unsorted, with the script-tree tweak (`Musig2.taprootSession`) |
| the label | a funding input's label is `nextFreshLabel(fundingTxId)` | the funding txid is not known when `tx_add_input` goes out, so it is keyed by the swap-in coin's outpoint and an attempt counter. Every RBF is a new attempt |
| which addresses are watched | `SwapInWallet` takes one address generator (`SwapInWallet.kt`) | every depositor's addresses, as `GroupOnchainWallet` watches every depositor's branch (member-deposits Phase 4) |

The funding input is already a MuSig2 of the Iceberg key and the LSP's funding key
(`RemoteIcebergFundingSigner.prepare`, :220-233, checked with `Iceberg.keyAggregationCheck`). So Iceberg
already acts as one side of a 2-party MuSig2. A swap-in input differs in three ways: the key order, the
taproot tweak (a script tree instead of `KeyPathTweak`), and the sighash, which covers every spent
output. No primitive appears to stand in the way, but nobody has tried it yet: that is Phase 0's
first spike.

---

## Decisions

| | decision | why |
|---|---|---|
| D1 | **The key path's user key is the group's Iceberg key, the same for every address.** | the swap-in has to be co-signed the way the channel is, with `2t-1` devices answering. Iceberg has no derivation, and the protocol needs none: lightning-kmp also uses one user key for every address |
| D2 | **The refund key is a FROST key, derived publicly per member and per index, and never reconstructed.** | the refund needs no LSP, so whoever holds the refund key holds the coin after 6 months. A per-address FROST key keeps it the group's, and changing the refund key is what makes each address different. Derivation follows [shared-key-derivation.md](./shared-key-derivation.md): additive public tweaks, signed under FROST, never in the clear |
| D3 | **Every swap-in address is one member's: derived from their depositor statement, and not shown until they have one.** | member-deposits' rule for every address and invoice. The derivation takes the member's branch `b` (`DepositBranch`), so a coin's address says whose it is, on every device, without asking the operator |
| D4 | **Only the operator's node swaps in. Co-signers sign a swap-in splice by rule, without an authorisation.** | money only comes in, as with an on-the-fly open, which payouts-only D9 keeps. An authorisation per deposit would make every deposit wait for a quorum of people, not devices. The rule (Phase 4) is narrow: the splice adds group swap-in coins, nothing leaves, and the fee is within a cap |
| D5 | **The depositor pays the fee.** Their Lightning deposit is the coin's amount less its share of the splice's mining fee | payouts-only refuses liquidity purchases because "nobody's balance is charged with it". A swap-in splice has a payer: the member whose coin it is. When several coins splice together, the fee is shared in proportion to their amounts |
| D6 | **A swap-in only splices into an existing channel. With no channel, coins wait.** | opening a channel from a swap-in buys liquidity (`Peer`'s `SelectChannelResult.None` branch), and that is exactly what payouts-only refuses. The group's first channel opens on the fly, from a Lightning deposit. Revisit this once a rule for opening costs exists |
| D7 | **The fee cap is the group statement's `maxLiquidityFee`, the same one the node's `LiquidityPolicy` already uses.** | no second number to sign. A coin whose fee is over it waits, and the screen says so |
| D8 | **The channel cap holds.** A splice that would take the channel past `MAINNET_CHANNEL_CAP` is refused, and its coins wait | S2 already refuses a bigger output. The swap-in rule keeps it rather than routing around it |
| D9 | **A refund pays the member's own deposit branch**, at an address the group derives. Only an address of their own is accepted | so the coin stays theirs under payouts-only D2. The group's receive branch 0 would make it nobody's until a coin assignment |
| D10 | **The node's own seed-key swap-in wallet is never shown, and the fork replaces it for the group node.** | its addresses are a 2-of-2 between a key every member holds and the LSP: any member could co-sign a swap with the LSP alone, and any member could refund alone after 6 months |
| D11 | **No regtest run of the swap.** The fork is tested in process, and the whole path once against ACINQ's LSP | open-source eclair has no swap-in code (Phase 0 of the reverted plan, spike 3). The refund needs no LSP and does run on regtest |

---

## Phases

One commit per phase. Each is green on `:composeApp:jvmTest`, `:composeApp:compileDebugKotlinAndroid`
and `m3Audit`, and the fork's phases also on `lightning-kmp`'s own `jvmTest`.

### Phase 0: spikes

1. **Iceberg as the user side of a swap-in MuSig2, in process.** A 2-of-3 Iceberg key with all three
   shares local stands in for the group, and a plain key for the LSP's server key. Build
   `SwapInProtocol(groupKey, server, refundKey, 144)`. Spend its key path with:
   - an aggregate Iceberg partial signature over `[user, server]`, unsorted, with the script-tree tweak;
   - the server's ordinary MuSig2 partial signature.

   Check the combined signature with `Crypto.verifySignatureSchnorr` against the output key. **This
   decides whether the plan is possible at all.** If Iceberg cannot take a script-path tweak, the
   plan stops here, and the note says why.
2. **ACINQ accepts a swap-in from a threshold user key.** A bare `Peer` whose swap-in keys are the
   spike-1 Iceberg key, with every share local and the round-two "transport" just a function call,
   connects to ACINQ's LSP and is sent a small coin. Run it on testnet3 if ACINQ's testnet LSP still
   swaps in, otherwise on mainnet within a few thousand sat. Record:
   - whether the LSP splices it in, or opens a channel with it;
   - what it charges;
   - the `OnChainIncomingPayment` that lands: its type, `fees`, and whether it names the coins it
     spent (`Origin.OnChainWallet.inputs` and `localInputs`). Phase 5 attributes by those outpoints.
3. **What a swap-in splice looks like to a co-signer.** Capture the `CoSigningWire` request the
   operator sends for a splice with a swap-in input, from spike 2's node run through the fork's
   signer hooks. Record:
   - what `context` carries (`localContributionSat`, `purchase`);
   - which inputs `messageOf` needs prevouts for;
   - what the first commitment on the new output shows.

   Phase 4's rules are written against this.

Done when the answers are under *Phase 0 outcomes* in this note.

### Phase 1: the addresses, pure

`managers/GroupSwapInDerivation.kt`:

- `refundKey(frostKey, chain, wallet, branch, index)`: a public derivation from the group's FROST key,
  tagged `dedun/group-lightning/swap-in-refund/v1` over `(key, chainHash, wallet)` and then `branch`
  and `index`, in the style of `GroupLightningDerivation`'s basepoints. It returns the x-only key and
  the `TweakCache` that a FROST script-path session needs.
- `protocol(statement, depositor, index)`: `SwapInProtocol(icebergKey, lspServerKey(nodeId),
  refundKey, refundDelay)`, with `lspServerKey = LSP xpub at perUserPath(nodeId)`.
- `address(…)`, and `addressesOf(depositor, upTo)` with a look-ahead like the onchain branches'.

Tests (`GroupSwapInDerivationJvmTest`):
- two members never share an address;
- every device derives the same addresses from the statement and the depositor statement alone;
- the refund leaf is byte-for-byte `GroupSweep.leafScript(refundKey, refundDelay)`;
- the same vectors come out on Android and on the JVM;
- the operator's seed swap-in addresses (D10) are not among them.

### Phase 2: the fork, a swap-in signer

`lightning-kmp`, behind interfaces whose default is today's behaviour, so Phoenix's swap-in and
lightning-kmp's own swap-in tests are unchanged:

- `SwapInKeys`, which `KeyManager.swapInOnChainWallet` returns:
  - `userPublicKey`, `remoteServerPublicKey`;
  - `protocol(addressMeta)`;
  - `signer`.

  `SwapInOnChainKeys` implements it as it does today.
- `SwapInSigner`, alongside `FundingSigner`:
  - `nonce(outPoint, attempt)`, synchronous: the round-one aggregate, from the Iceberg pool or local;
  - `suspend sign(tx, index, spentOutputs, protocol, serverNonce)`, returning
    `Either<Throwable, ByteVector32>`.

  `InteractiveTxSession.send()` and `SharedTransaction.sign` call it in place of `userPrivateKey`
  and `swapInSecretNonces`.
- `Peer` takes an optional `swapInWalletSource: Flow<WalletState>`. When it is given, it replaces the
  internal `SwapInWallet`, and `AddressMeta` is resolved through `SwapInKeys.protocol`.
- `RemoteIcebergSwapInSigner`, beside `RemoteIcebergFundingSigner`. It uses the same transport, with
  labels from `(outPoint, attempt)` and the round-two request typed for a swap-in input.

Tests in the fork, in process: a two-`Peer` interactive splice in which one side's swap-in input is
signed by a local three-share Iceberg signer, and the result checks against the input's prevout. The
existing swap-in tests pass unchanged.

### Phase 3: the group's node watches every depositor's swap-in addresses

- `GroupSwapInWallet`, beside `GroupOnchainWallet`. It holds one `ElectrumMiniWallet` per depositor,
  over Phase 1's addresses with the look-ahead, and every device runs it, so everyone sees the coins
  without asking the operator.
- The operator's node gets it as `swapInWalletSource` and calls `startWatchSwapInWallet()`. Its
  `LiquidityPolicy` is already capped at the statement's `maxLiquidityFee` (D7).
- `GroupNodeKeyManager.swapInOnChainWallet` returns the group's `SwapInKeys`, with the Iceberg signer.
  The seed-key swap-in wallet is no longer built (D10).
- The pure coin statuses come back from `0f2e5594` (`SwapInWalletView.coins`), unchanged.

Tests:
- a depositor's coin shows on every device's `GroupSwapInWallet`;
- a coin to an address that belongs to no depositor is never offered to the node;
- the node's swap-in manager sees only group addresses.

### Phase 4: co-signers sign a swap-in splice by rule

`GroupLightningCoSigner.checkSplice` gains a second shape beside the liquidity purchase. Each rule is a
refusal string, as S1-S6 are:

| rule | the splice | refused when |
|---|---|---|
| W1 | spends the funding output once, plus swap-in coins | any other input is not on an address that Phase 1 derives for a depositor of this wallet, rederived here and not taken from the request |
| W2 | has no purchase | `context.purchase` is present: that is the liquidity shape, and stays S1's |
| W3 | pays nothing out | there is any output but the new funding output |
| W4 | adds exactly the coins | the new output is not the old one plus the coins less our mining fee, with the LSP adding nothing |
| W5 | costs within the cap | our mining fee is over the statement's `maxLiquidityFee` (D7) |
| W6 | stays within the channel cap | the new output is over `MAINNET_CHANNEL_CAP` (D8, as S2) |
| W7 | credits the group | the first commitment on the new output does not raise the group's balance by exactly the coins less the fee |
| W8 | is signed once per attempt | a label for `(outPoint, attempt)` was already answered for another transaction (the echo rule, D7 of the wallet note) |

A co-signer that passes them answers round two twice: once for the shared funding input, as today,
and once for each swap-in input, through Phase 2's signer. `openingOf` accepts a new output that W1-W7
passed without a `buy-liquidity` authorisation (D4).

Tests in `GroupLightningSpliceCoSigningJvmTest`: one refusal for each rule, plus one accepted swap-in
splice signed by the whole fixture group. The purchase shape is untouched: its tests stay as they are.

### Phase 5: whose deposit it is

- `DepositAttributionEvent.DepositKey.SwapIn(outPoint)`, and `DepositLedger.recordSwapIn`: one
  `LIGHTNING` row per coin, counted `BY_SWAP_IN_ADDRESS` to the depositor whose address it paid, for
  the coin's amount less its share of the splice's fee (D5). It is recorded when the operator reports
  the `OnChainIncomingPayment`, and the outpoints are matched against what `GroupSwapInWallet` already
  saw, so no device has to take the operator's word for whose a coin is.
- `WalletWire.PaymentView` gains `swapInInputs` and the fee. `paymentView` fills them for an
  `OnChainIncomingPayment`. An old device ignores them and reads the payment as unattributed, which
  is today's behaviour.
- `MemberBalances` picks the rows up unchanged, since payouts-only D8 counts `LIGHTNING` deposits.

Tests:
- one coin is one deposit, less its fee;
- two members' coins in one splice share the fee by amount, and the shares add up to the fee;
- a payment report naming an outpoint that belongs to no depositor is unattributed, never guessed.

### Phase 6: the screens

- **Receive.** `GroupLightningReceiveScreen` offers *Deposit onchain* beside the invoice form, for a
  depositor only, as the invoice form is (`NotDepositor` otherwise). It shows the member's current
  swap-in address as a QR code, says that coins move into the group's Lightning balance after 3
  confirmations, and says what a splice costs at today's feerate.
- **The addresses and coins.** `GroupSwapInAddressesScreen`, a sibling of `GroupWalletAddressesScreen`:
  - your own addresses, with the current one shown as a QR code;
  - your coins, each with its status from `SwapInWalletView`;
  - the used addresses of other members, copy only.
- **Why a coin waits.** The operator's snapshot carries the swap-in manager's last refusal, as it
  already carries the node's status (lsp-connection-status). The waiting line names one of:
  - the operator's node is away;
  - fewer than `2t-1` co-signers answered;
  - the fee is over the group's cap;
  - the channel is full;
  - there is no channel yet (D6).
- **Expiring coins.** A transcript line for a member whose coin has less than two weeks left to swap
  in, sent once per coin.

Strings in the catalogue, in sentence case. Each screen has four states, and `ScreenStateTransition`
round its state `when`. Tests are desktop Compose tests of each state, as the reverted branch had.

### Phase 7: refunds

- `GroupSweep.check` gains the input type `SwapInRefund(depositor, index)`. The leaf is
  `leafScript(refundKey, refundDelay)`, the sequence is `refundDelay`, and the only output allowed is
  an address on the same depositor's branch (D9).
- `FrostSigningManager.proposeSwapInRefund`, which any member can propose for their own coin once it is
  refundable, and the depositor's co-members sign. It is broadcast with the broadcast check from
  `3eb07d5b`.
- The ledger: a refunded coin's `LIGHTNING` row is never written, because the coin was never spliced.
  The coin is now an onchain deposit on the depositor's branch, counted `BY_BRANCH` as any other.

Tests: the refund at `refundDelay - 1` is refused, at `refundDelay` it passes, and an output off the
depositor's branch is refused. There is also a regtest run as in `3eb07d5b`: a 144-block delay, a
bare wallet, and electrs. The LSP is not needed.

### Phase 8: end to end against ACINQ

On mainnet within the cap, or testnet3 if ACINQ's testnet LSP swaps in, with a group of four devices
from the fixture:
1. A member's deposit is sent to their swap-in address.
2. It confirms 3 times.
3. The operator's node splices it in, with every co-signer checking W1-W8.
4. The member's Lightning balance rises by the coin less its fee.

It is run by hand, like the mainnet co-signing suite, and the result is written into this note. It
is not part of `check`.

---

## What this plan does not do

- **Open the group's first channel from a swap-in** (D6). That waits on a rule for who pays for
  buying liquidity.
- **Swap a member's existing onchain deposits into Lightning.** A coin on a member's onchain branch is
  not on a swap-in address, and moving it there is a spend the group signs. That is a plan of its own.
- **A personal swap-in.** That was reverted, and is not coming back.
- **Swap in while the operator's device is off.** The node runs only there. Coins wait and say so, and
  have 4 months.

## Open questions

- Spike 1 decides whether the plan is possible: can Iceberg sign under a script-tree tweak?
- Should co-signers sign a swap-in splice without an authorisation (D4), or should each depositor's
  own device have to agree first? The first is simpler. The second guards against an operator
  splicing in a coin its depositor meant to refund.
- Is the 6-month refund the right failure for a coin that never swaps in, given that it needs the
  group's FROST quorum and not just the depositor?
