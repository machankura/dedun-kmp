# A group chain wallet

An on-chain bitcoin wallet that belongs to a group: its addresses are derived from
the group's ChillDKG threshold key, its spends are signed by the FROST signing
ceremony the app already runs, and nobody — not the coordinator, not any member,
not any `t - 1` of them — can move a coin alone. It is modelled on Phoenix's
*final wallet*, the one-address on-chain wallet a Phoenix node keeps beside its
channels, and this note says where it follows that wallet and the three places it
cannot.

Read [shared-key-derivation.md](./shared-key-derivation.md) first, for the rule a
wallet makes more expensive to break, and
[frost-batch-signing.md](./frost-batch-signing.md) second, because a transaction
with `k` inputs is a batch of `k` and that note's nonce rule is the one this
plan is built around too.

**Built**, phases 1-10, one commit per phase (two each for 8 and 9). Written
against the code as it stood at `b75d4b42`, with the `lightning-kmp-app` submodule
at `e0d2b82`. `GroupWalletDerivation`, `FrostSigningManager.proposeSpend` and
`GroupTransactionWatcher` are the entry points, and `GroupWalletRollout` holds the
gates Phase 10 ships behind. Every path into
the library below is into that submodule, which a fresh worktree does not have
checked out — run `git submodule update --init --recursive` first. The phases are
kept as written; where the build chose differently the table below says so.

| what the plan said | what it turned out to be |
|---|---|
| one `tr([fp]xpub/<0;1>/*)` descriptor | two, `…/0/*` and `…/1/*`, as bitcoin-kmp's `BIP84Descriptors` does. Many watch-only importers, Bitcoin Core's `importdescriptors` among them, refuse a multipath descriptor |
| `derive(account, thresholdPublicKey, change, index)` | `derive(account, change, index)`: the `Account` carries the cache that signs as its key, so the threshold key is not asked for twice and cannot be passed in mismatched |
| late members get the statement through the chronicle | they do not: `ChronicleEvent`'s allowlist carries document kinds only, and leaves out every group-signed statement *about* the group — relay lists and profile included — for the replay reason its kdoc gives. A member who joined later sees *Open a group wallet* instead, and signing it again is harmless: a statement for one account is the same values under the same `d` tag, and the newest replaces the rest |
| the descriptor rides in a `descriptor` tag | two, each marked `receive` or `change`, and the `d` tag is read back and checked against the network and account, so "newest per account" cannot be split by a `d` that disagrees with the fields |
| the transcript line is checked by signature | by signature *and* rederivation, through `GroupWallet.of` — a line people will send money on is held to the same check as the wallet screen |
| `GroupWalletManager` starts a wallet for every room with a statement, under the identity's `collectLatest` | `GroupOnchainWallets`, one per identity, makes a room's wallet the first time its screen asks and keeps it for the identity's life. Starting every room's at launch would subscribe a member in twenty groups to two hundred addresses before they looked at one; `GroupWalletManager` kept the reads (the key, the account, the statement) and nothing that runs |
| `receiveAddress` is the first unused address | it is, and that is `ElectrumMiniWallet`'s rule: the *lowest* unused index, so a gap below a paid address is refilled before the next one is offered. Null until Electrum has answered, rather than `/0/0` |
| members who can sign see *Open a group wallet* on the group's screen; others see nothing | every member sees a *Group wallet* row, saying *Open on mainnet* or *Not opened yet*; the open action lives on the wallet screen's empty state, behind the same `canSign` gate, with Phase 10's sentence about unencrypted shares above the button |
| the wallet's network is the identity's | `NodeParamsManager.chain`, read statically. An nsec or npub profile has no node, and still has to know which network's statement to look for |
| `GroupOnchainWallets` started under the identity's `collectLatest` | made in `DedunNavHost`, remembered on the identity's `PhoenixBusiness` and stopped in a `DisposableEffect` when it changes — the same lifetime, held where the business already is |
| `FrostSigningItem.eventId` and `unsignedEventJson` renamed in Kotlin to `message` and `payload` with `@ColumnInfo(name = …)` | not renamed: `message` and `payload` are getters over them. `eventId` is spelled the same way in thirty files that have nothing to do with this table, and a rename a search cannot tell apart from them is not worth what it would tidy |
| `GroupTransaction` has `broadcastAt` and `confirmedAt` | and `seenAt`, because Electrum's broadcast reports success whatever the server said: sent and seen are the two facts there are, and confirmed is a third. Inserted with `IGNORE` so the proposed PSBT is write-once at the database, not only in the manager |
| a claimed index is bounded by the range the wallet has generated | by a constant, `GroupSpend.MAX_ADDRESS_INDEX` (100,000). The bound is on work, and a member with no node — who can still sign — has no generated range to bound it by |
| the fee ceiling is relative to `FeerateManager`'s fastest | an absolute 2,000 sat/vB, for the same member: no node, no fee source. It catches three zeros too many, not a merely generous fee — the approval screen is what catches that |
| no script-path fields | refused by refusing any input whose PSBT entry has `unknown` fields: bitcoin-kmp does not parse `tap_leaf_script`, `tap_merkle_root` or `tap_script_sig`, so that is where they land |
| the account comes from the statement, never from the proposal | and the proposal carries `chain` and `account` tags to say *which* statement. Acceptance then looks the statement up under the ceremony's own key, which is also what refuses a spend opened under a subgroup's ceremony held in its parent's room |
| a new `TYPE_GROUP_TRANSACTION_SIGNED` line | none: `TYPE_FROST_COMPLETE` with a spend's wording — sats sent, sats in fees — since it is the settlement line the transcript and the proposal list already read, and a new type would need adding to five sets |
| `complete()` for a transaction | `completeSpend`, which re-runs `GroupSpend.check` over the stored PSBT so every signature is checked against the coin it spends, then runs `correctlySpends` before writing `signedTransaction` |
| the feerate comes from `FeerateManager`'s `halfHour` | from Electrum's six-block estimate, through `GroupOnchainWallets` — the wallet already holds the identity's Electrum client, and the business's fee manager would be a second way in to the same node. Prefilled, never used unseen |
| the inputs behind a disclosure on the approval screen | a line saying how many of the group's coins it spends. Each was checked against the wallet before the screen was drawn, so there is nothing about them a member could check by opening one |
| the composer marks coins reserved | `FrostSigningManager.reservedOutPoints`: every coin an open spend holds, released when its session fails, held by a finished one until Electrum stops reporting the coin |
| transcript lines for broadcast and for confirmed | confirmed only, `TYPE_GROUP_TRANSACTION_CONFIRMED`, written by `recordConfirmed` once. A broadcast is this device's own act and says nothing the group did; the wallet screen shows each spend's status instead |
| a conflicting spend is noticed when a reserved coin is spent by a different transaction | when one of the group's spends *confirms*: only the group can spend its coins, so the only other transaction that can take one is another of its own spends, and the confirmation is the moment the question is settled. Settled by the confirmed spend's own row, so every device decides the same way |
| a fee bump is proposed at a feerate the member chooses | at twice the spend's feerate, from a *Bump the fee* button on the spend's row — comfortably over BIP125's increment, and one decision rather than a form |
| `GroupTransaction` rows are kept by the screen that shows them | `GroupTransactionWatcher`, one per identity beside the wallets, started with the identity's node whether or not anybody opens the wallet. It found a real bug before it shipped: following each spend from the list a pass began with wrote a settled row back from its stale copy |
| a feature flag, off, until Phases 1-9 are in | `GroupWalletRollout.ENABLED`, on: its own lift condition — Phases 1-9 in, the JVM suite passing — was met when it landed. `MAINNET_CAP` holds, at 100,000 sats per spend proposed from this device. `SEND_AN_AMOUNT` was removed: its lift condition, a release with no mislabelled change, could not be met while it kept every network on send-all. A member sends an amount on every network, with the cap as the bound on mainnet — [group-send-an-amount.md](./group-send-an-amount.md) |
| the gates are on the composer | and on nothing else, on purpose: a device with a gate closed still accepts, shows and signs what another member proposes, through every check `GroupSpend` makes. A gate decides what this device offers, never what a quorum may decide |

The headline: **the best path is BIP86's own unhardened tail, `/<change>/<index>`,
hung under an account key the group's threshold key is tweaked to, with the
BIP341 key-path tweak on top.** Every hardened level of `m/86'/0'/0'` is
impossible for a threshold key, and each one's job turns out to be done by
something else here: the ceremony is the hardened step, and one public
account step — a tagged HMAC over the threshold key, the network and an account
number — does what `coin'` and `account'` do. What is left below it is ordinary
BIP32 public derivation, which is why the group can export a standard
`tr(xpub/<0;1>/*)` descriptor and any watch-only wallet can follow it.

---

## What Phoenix's final wallet is, and what carries over

`FinalWallet` (`lightning-kmp-app/experimental/lightning-kmp/modules/core/src/commonMain/kotlin/fr/acinq/lightning/blockchain/electrum/FinalWallet.kt`)
is small, and almost all of it is reusable in shape:

| Phoenix's final wallet | the group's |
|---|---|
| `Bip84OnChainKeys(chain, master, account = 0)` from the node seed | `GroupWalletKeys(chain, thresholdPublicKey, account = 0)` from the ceremony |
| `m/84'/{0',1'}/0'/0/0`, P2WPKH, ECDSA | account step over `K`, then `/0/i` and `/1/i`, P2TR key path, BIP340 |
| one `ElectrumMiniWallet`, `addAddress(address(0))` — index 0 forever | two `ElectrumMiniWallet`s, one per branch, each with `addAddressGenerator` |
| `finalAddress` | `receiveAddress`, the first unused receive address |
| `buildSendAllTransaction(address, feerate)`: confirmed utxos, one output, fee from a dummy-signed copy, 546-sat floor | the same algorithm, and a second builder that pays an amount and returns change |
| `signTransaction`: a local private key, `SIGHASH_ALL`, witness v0 | `proposeSpend`: a FROST session with one item per input, `SIGHASH_DEFAULT`, witness v1 |
| never broadcast; `buildSendAllTransaction` has no caller in `library/` or this app | broadcast by any member once the session completes |
| a `zpub` for display | an `xpub` and a `tr()` descriptor for watch-only export |

Three things cannot carry over, and each is a constraint rather than a choice:

1. **P2WPKH is out.** FROST produces BIP340 Schnorr signatures. A segwit-v0 output
   needs ECDSA, and there is no threshold ECDSA anywhere in this stack. The only
   output a FROST key can spend is a taproot one.
2. **`m/84'/…` is out, and so is `m/86'/…`.** Hardened derivation needs the parent
   *private* key, which exists only as shares — see
   [Why not BIP32](./shared-key-derivation.md#why-not-bip32). No member and no
   quorum short of reconstructing the secret can walk a `'`.
3. **Signing is not a function call.** `FinalWallet.signTransaction` returns a
   signed transaction synchronously. Here a spend is a proposal that a quorum of
   people approve over minutes or days, through the same session machinery a
   relay list goes through.

What the library already gives, so none of it is written twice:

| need | where it is |
|---|---|
| watch any set of P2TR addresses on a shared Electrum connection, gap-limited | `ElectrumMiniWallet(chainHash, client, scope, logger, lookAhead)`, `addAddressGenerator`; unknown script hashes are ignored, so many wallets share one client |
| the UTXO set with confirmations | `WalletState.utxos`, `withConfirmations`, `withoutReservedUtxos` |
| taproot sighash over all spent outputs | `Transaction.hashForSigningTaprootKeyPath(inputIndex, inputs, SigHash.SIGHASH_DEFAULT)` |
| BIP86 output keys and addresses | `XonlyPublicKey.outputKey(KeyPathTweak)`, `Bitcoin.computeBIP86Address`, `Script.pay2tr` |
| witness | `Script.witnessKeyPathPay2tr(sig)` |
| the final judge of a spend | `Transaction.correctlySpends(previousOutputs, flags)` |
| plain and x-only FROST tweaks | `TweakCache.tweak(scalar, isXonly)` — `false` is a BIP32-style add, `true` the BIP341 one |
| BIP32 public derivation | `DeterministicWallet.ExtendedPublicKey.derivePublicKey` |
| PSBT with `tap_key_sig`, `tap_internal_key`, `tap_bip32_derivation` | `fr.acinq.bitcoin.psbt.Psbt` |
| fee estimates | `IElectrumClient.estimateFees`, Phoenix's `FeerateManager` (mempool.space) |

And the one thing in the library that looks reusable and is not:
`Frost.taprootSession`, `Frost.signTaprootInput` and
`Frost.aggregateTaprootSignatures` take a `KeyMaterial`, whose `init` requires
every participant's secret share — a trusted-dealer shape. A ChillDKG participant
has one share. Do what those helpers do, by hand, through `TweakCache` and
`Session`, exactly as `FrostSigningManager.advance` already does for events.

---

## The derivation

### The constraints a path has to meet

| constraint | from |
|---|---|
| no hardened step anywhere | the parent private key does not exist |
| a P2TR key-path output | FROST signs BIP340 only |
| no collision with the room's own key, `m/9420/0/0` | the room's id *is* that key; a wallet on it would be findable by anyone who knows the room |
| mainnet and test networks yield unrelated keys | BIP44's `coin'` does this for a seed wallet, and the same group will try a wallet on a test network first |
| more than one wallet per group is possible later | BIP44's `account'` |
| a member can check, alone, that no script path is hidden in the address | otherwise one member can propose an address with a back door and nobody can tell |
| addresses rotate, and change is told apart from payment | address reuse is a privacy leak for a treasury, and a signer has to see where the money goes |
| a watch-only tool can follow the wallet | a treasurer, an auditor or a member's Sparrow should not need Dedun to see the balance |
| everything is a pure function of what every member already holds | [Rederive rather than persist](./shared-key-derivation.md#what-it-produces) |

### The path

```
K                     the group's ChillDKG threshold key, a full 33-byte point
                      (DkgSession.thresholdPublicKey)

I   = HMAC-SHA512(key  = "bennu/group-wallet/account/v1",
                  data = serP(K) ‖ chainHash ‖ ser32(account))
t_a = I[0:32]                                  account tweak
c_a = I[32:64]                                 account chain code
K_a = K + t_a·G                                account key           plain tweak

(K_a, c_a)            an ordinary BIP32 extended public key, depth 0

K_ci = CKDpub(CKDpub((K_a, c_a), change), index)                      plain tweaks
P    = xonly(K_ci)                             BIP86 internal key
Q    = P + H_TapTweak(P)·G                     BIP86 output key      x-only tweak

address = bech32m(Q)      change ∈ {0 receive, 1 change}, index unhardened
```

Written as a path, that is **`account(K, network, 0)/0/i` for receiving and
`account(K, network, 0)/1/i` for change** — the same tail BIP86 hangs under
`m/86'/coin'/account'`, under a different head.

As a FROST tweak cache it is four tweaks, in order, and nothing else:

```kotlin
TweakCache.create(K)
    .tweak(t_a,           isXonly = false)   // account step
    .tweak(IL(change),    isXonly = false)   // BIP32, the I_L of CKDpub
    .tweak(IL(index),     isXonly = false)
    .tweak(tapTweak(P),   isXonly = true)    // BIP341, no script tree
// cache.tweakedPublicKey == Q, and must be checked to be
```

### Why each level is what it is

**The ceremony is the hardened step.** The point of `m/86'/0'/0'` being hardened
is that a leaked key below an account cannot reach the seed or a sibling account.
For a threshold key there is no seed to protect, and the isolation hardening buys
is bought instead by running another ceremony: two ceremonies' keys are
independent, which is exactly what two hardened siblings are. So a group that
wants a wallet isolated from its room key runs a ceremony for it — see the
[appendix](#appendix--what-was-considered-and-rejected). One that is content with the
room's key does what follows.

**An account step, not `86/0/0` unhardened.** The obvious path is BIP86's numbers
with the apostrophes taken off, `K/86/0/0/0/i`. It is worse in three ways:

- It needs a chain code at the root, which ChillDKG does not produce, so one has to
  be invented anyway. The account step is where it is invented, once, with a tag.
- It puts `K` itself in any xpub the group exports at the root, and `K` is what
  derives the room's id. Anyone handed the wallet's xpub could then compute which
  room it belongs to. `K_a` reveals nothing about `K` — inverting `t_a` needs `K`.
- It reads as BIP86 to anyone who sees it, and somebody will type `m/86'/0'/0'`
  into a wallet and wonder where the money went. A path that is not BIP86 should
  not be spelled like it.

**The network and the account are in the HMAC, not in the path.** Putting them in
the account step makes every `(network, account)` its own xpub with its own chain
code, which is the property `coin'` and `account'` exist for: a regtest wallet
tells nobody anything about the mainnet one, and account 1 shares no addresses
with account 0. The chain hash is the 32 bytes of lightning-kmp's
`Chain.chainHash` *as bitcoin-kmp holds them*; byte order is exactly the thing two
implementations disagree on, so it is pinned by a test vector, not by prose.

**A deterministic chain code, not a random one.** A random `c_a` agreed by the
group would hide the wallet from anyone who learned `K`. But `K` is already
confidential — `GroupKeyStateEvent` says in as many words that it only ever lives
inside an encryption a relay cannot open — and every member, current or departed,
holds it. A random chain code would therefore protect against nobody who is not
already inside, and it would add the one thing this design otherwise does not
have: a value that, if every device lost it, would leave the coins unspendable
with the quorum intact. Deterministic means a member who restores their share
from ChillDKG's recovery data restores the wallet with it.

**BIP32 below the account, not `SharedKeyDerivation`.** The derivation note's
reasons for avoiding BIP32 were that there is no chain code and that x-only keys
leave `serP(K_par)` ambiguous. Neither holds here: the account step supplies the
chain code, and `DkgSession.thresholdPublicKey` is the full compressed point, so
`serP` has exactly one answer at every level. What BIP32 buys in exchange is
everything in the last three rows of the constraints table — a ranged descriptor,
`tap_bip32_derivation` fields in a PSBT that an external tool understands, and
BIP86's own published vectors as a test of the tail.

`SharedKeyDerivation` stays exactly as it is for nostr keys. The two functions
must never be confused, so they do not share a type: `SharedKeyDerivation` uses
x-only tweaks at every step and a SHA-256 scalar; `GroupWalletDerivation` uses
plain tweaks and HMAC-SHA512 until the last step. A test asserts the room's key
and the wallet's `/0/0` are different points.

**No script tree, and that is a security property.** BIP86 commits the output key
to *no* script path: `Q = P + H_TapTweak(P)·G` with nothing else hashed in. A
proposer who wanted a back door would use `Q' = P + H_TapTweak(P ‖ root)·G` with a
script only they can satisfy, and the address would look like any other. Because
every member derives every address themselves and compares it byte for byte, an
address with a hidden script path is an address no member's device recognises —
which is the whole defence, and why the app never displays, accepts or signs for
an address it did not derive.

A subgroup's *guarded* wallet does have a tree -- one leaf per ancestor, see
[group-wallet-guardians.md](./group-wallet-guardians.md) -- and the defence is the
same with one word changed: every member derives every address, tree included,
from the child's key and a certificate every member verifies, and compares it byte
for byte. The tree is computed, never proposed.

### The rule this inherits, made more expensive

Every tweak above is publicly computable from `K`. So the derivation note's rule
applies with money attached:

> **Never reconstruct a wallet key in the clear.** A leaked private key for any
> address recovers the group's threshold key: the room's identity, every address
> of every account, and every event the group can ever sign.

There is no "export private key", no "sweep with a key", no test helper that
aggregates shares into a scalar. The only export is the account-level xpub.

### What the xpub reveals, and to whom

| holder | learns |
|---|---|
| anyone handed the xpub or descriptor | every address and the balance of that `(network, account)`; nothing about `K` or the room |
| a member, or a former member | `K`, so every account on every network, forever — removal from the group does not un-know a key |
| a relay | nothing: `K` and the wallet statement never leave an encryption |
| a chain observer | nothing beyond any single-key taproot wallet: a key-path spend is indistinguishable from one |

The xpub is serialised at depth 0 with a zero parent fingerprint and child number
(bitcoin-kmp's `ExtendedPublicKey` requires that shape at depth 0), prefix `xpub`
on mainnet and `tpub` otherwise. The descriptor is `tr([fp]xpub…/<0;1>/*)#checksum`,
`fp` being the account key's own fingerprint, with `Descriptor.checksum` from
bitcoin-kmp; there is no `tr()` builder in the library, so it is formatted here.

---

## Phase 0 — before any code

**No code. An afternoon, and three decisions that are not the code's to make.**

1. **Which network development runs on.** `NodeParamsManager.chain` is hard-coded
   to `Chain.Mainnet`, and so are the LSP, the fee source and the Electrum server
   list through it. The wallet takes its `Chain` as a parameter from the start, so
   every test below runs on `Chain.Regtest` without a network. What is undecided is
   the first run against a real chain: a testnet build is a change to the Phoenix
   fork with its own blast radius, and the alternative is mainnet with a cap. This
   plan assumes **mainnet behind a cap**, in [Phase 10](#phase-10--rollout), and
   says where that changes if a testnet build happens first.
2. **Secret shares at rest — decided: used as they are.** `DkgSession.secretShare`
   is plaintext in a Room database; its kdoc relies on encrypted local storage the
   app does not have. That was a risk to a group's *signature*, and with coins
   behind the same share it is a risk to the group's money: a stolen phone plus
   `t - 1` colluders is a theft. The wallet reads the share exactly where
   `FrostSigningManager.advance` already does, and encrypting it is its own piece
   of work, not a gate on this one. What the decision costs is stated once, in
   [Phase 10](#phase-10--rollout), so a group turning the wallet on can read it.
3. **Kinds.** Two new inner kinds are needed, and 30327-30330 are taken (chronicle,
   chronicle request, subgroup certificate, decline — and 30330 is already shared
   with the direct-message envelope). This plan uses **30331** for the wallet
   statement and **30332** for a transaction proposal. Check again before Phase 3;
   the note in `FrostSigningEvents.kt` says the next family should not rely on the
   routing accident that kept the last ones apart, and this is the next family.

---

## Phase 1 — `GroupWalletDerivation`

**A day. Pure functions, no I/O, no schema.**

`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/managers/GroupWalletDerivation.kt`,
an `object` beside `SharedKeyDerivation` and shaped like it:

```kotlin
object GroupWalletDerivation {
    const val RECEIVE = 0L
    const val CHANGE = 1L

    data class Account(
        val chain: Chain,
        val account: Long,
        val extendedPublicKey: DeterministicWallet.ExtendedPublicKey,   // (K_a, c_a), depth 0
    ) {
        val xpub: String
        val descriptor: String          // tr([fp]xpub/<0;1>/*)#checksum
    }

    data class Derived(
        val change: Long,
        val index: Long,
        val internalKey: XonlyPublicKey,   // P
        val outputKey: XonlyPublicKey,     // Q
        val cache: TweakCache,             // K -> Q, four tweaks
    ) {
        val pubkeyScript: ByteVector       // OP_1 <Q>
        fun address(chain: Chain): String
    }

    fun account(thresholdPublicKey: HexKey, chain: Chain, account: Long = 0): Account
    fun derive(account: Account, thresholdPublicKey: HexKey, change: Long, index: Long): Derived
}
```

`derive` computes the child twice and refuses to return if the two disagree: once
as points through `ExtendedPublicKey.derivePublicKey`, and once as the four-tweak
cache. `cache.tweakedPublicKey == Q` and
`Bitcoin.computeBIP86Address(K_ci, chainHash) == address` are `require`s, not
tests, because they are the only things standing between a parity mistake in the
cache and a wallet whose signatures do not verify — the failure the
[derivation note](./shared-key-derivation.md#what-it-produces) calls "tedious to
diagnose from the outside, because the signature is valid — just not for the key
you expected". Here it is worse: the coins are already at `Q`.

The `I_L` values the cache needs are not exposed by `DeterministicWallet`, so they
are recomputed with `Crypto.hmac512(c, serP(K) ‖ ser32(i))`, and the cross-check
above is what proves the recomputation matches.

`change` must be 0 or 1 and `index` below `2^31`; anything else is a `require`,
never a clamp. An `I_L` at or above the curve order, or a child at infinity, has
probability around `2^-127` and BIP32 skips the index; mirror `derivePublicKey`,
which throws, rather than writing a skip nobody will ever test.

**Tests**, in `commonTest`:

- **BIP86's own vectors prove the tail.** Take the account xpub from
  `BIP86TestsCommon.kt` (`m/86'/0'/0'` of the "abandon … about" mnemonic), hand it
  to the tail as though it were `(K_a, c_a)`, and assert the vector addresses at
  `/0/0`, `/0/1` and `/1/0` come out — through the cache, not only through the
  points. This is the test that says the bottom two levels and the TapTweak *are*
  BIP86.
- **A pinned vector for the account step.** A fixed `K`, `Chain.Mainnet` and
  `Chain.Regtest`, accounts 0 and 1: the xpubs, the descriptors with checksums, and
  `/0/0`, written out as literals. This is the vector that freezes the tag, the
  byte order of the chain hash and the order of the HMAC's inputs; changing any of
  them is a protocol fork and the test should say so.
- **Both parities.** Fixed `K`s chosen so that `K_a` has even and odd y, and so
  that some `K_ci` do and some do not — `PrefractalTaprootSessionTestsCommon.kt`
  does this for the same reason, because a parity bug passes half the time.
- **Separation.** The room key `SharedKeyDerivation.derive(K)` and the wallet's
  `/0/0` differ; mainnet and regtest `/0/0` differ; accounts 0 and 1 differ;
  receive and change at the same index differ.
- **Refusals.** A hardened `change` or `index` throws.

---

## Phase 2 — FROST spends a group output, in a test only

**A day. No production code changes; this is the phase that de-risks the crypto
before the manager is touched.**

One test, in `commonTest` beside `FrostSigningRoundTest`, against real FROST with
no database — the same reason that file exists:

1. Run ChillDKG between three simulated participants, 2-of-3, the way
   `ChillDKGTestsCommon.kt`'s `dkg output can sign with frost` does.
2. Derive `/0/0` and `/0/1`, and fabricate a funding transaction paying both.
3. Build a transaction spending both outputs to an external address with change
   to `/1/0`.
4. For each input `j`: the cache from `derive`, the message
   `hashForSigningTaprootKeyPath(j, spentOutputs, SIGHASH_DEFAULT)`, an independent
   nonce seed, `SecretNonce.generate` with `cache.tweakedPublicKey` as the hint,
   `Session.create`, two partial signatures, `Session.verify` on each,
   `aggregateSigs`, `Script.witnessKeyPathPay2tr`.
5. `correctlySpends` with standard script flags. That is the judge, not
   `verifySignatureSchnorr`: a signature that verifies against the wrong sighash
   would pass the latter.

And the negatives that matter:

- **The wrong cache.** Signing input 0 with input 1's cache aggregates to a valid
  signature that `correctlySpends` rejects. So does an untweaked cache, and a
  cache missing only the TapTweak — `IcebergTaprootSessionTestsCommon.kt` pins the
  same case for channels.
- **No shared nonces.** The two inputs' aggregated nonces are distinct, and so are
  their seeds. Every positive assertion above still passes if they are not; what a
  shared nonce costs is the share.
- **Amounts are committed to.** Change the amount of one spent output in the list
  handed to the sighash and the transaction fails `correctlySpends`. This is
  BIP341's fix for the fee attack, and it is what lets Phase 7 trust a PSBT's
  amounts without a lookup; the test is what makes that claim checkable here.

---

## Phase 3 — the group opens its wallet

**A day. The first phase a member can see; nothing spends yet.**

Every room with a key *could* have a wallet without anybody agreeing to it — the
addresses are a function of `K`. That is the wrong default for money: a member
should not be able to show an address the group never decided to keep funds at,
and a depositor deserves to know the group said so. So a wallet exists when the
group signs a statement that it does, and not before.

`GroupWalletStatement`, kind **30331**, an inner event in the same private space
as the key state:

| field | carries |
|---|---|
| content | the account xpub |
| `chain` tag | `mainnet`, `regtest`, … |
| `account` tag | `0` |
| `descriptor` tag | the `tr()` descriptor with checksum |
| `d` tag | `<network>:<account>`, so a later statement replaces an earlier one per account |

It is proposed with `FrostSigningManager.proposeSigning` like any single event,
and `ProposedEvent.summarize` gains an arm for it: *"Open a group wallet on
mainnet"*.

**Trusted no further than it can be checked.** A reader accepts a statement only
if it passes both of `GroupKeyStateEvent`'s two kinds of check: it is signed by the
room, and `GroupWalletDerivation.account(K, chain, account)` rederives the xpub
and the descriptor it carries. The second is the load-bearing one — the statement
is a record that the group agreed, not a source of keys. The app never reads an
address out of it.

**Never broadcast.** The xpub on a public relay is the group's whole history on
chain, to anyone. `BroadcastGroupSignedEventScreen` refused this kind, and a test
said it refused it. That screen has since been removed with the group's nostr
profile, relay lists and posts, and nothing in the app now sends a group-signed
event to a relay.

`GroupWallet` is a read model over `GroupSignedEvent`, in the shape the
since-removed `GroupRelayList.of`/`newestAmong` had. There is no table: a wallet is a signed event
and a derivation.

**Not batched** with anything. A wallet statement is what every spend proposal is
opened against, and the batch note's reasoning for the key state — that it should
never be as available as its worst co-passenger — applies the same way.

**Late members** need nothing extra. They hold `K` from the moment they hold a
share, and the statement reaches them through the chronicle with the rest of the
group's signed record.

---

## Phase 4 — watching the chain

**Two days.**

`GroupOnchainWallet`, the counterpart of `FinalWallet`, in
`composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/wallet/`:

```kotlin
class GroupOnchainWallet(
    val chain: Chain,
    val account: GroupWalletDerivation.Account,
    private val thresholdPublicKey: HexKey,
    electrum: IElectrumClient,
    scope: CoroutineScope,
    loggerFactory: LoggerFactory,
) {
    val receive = ElectrumMiniWallet(chain.chainHash, electrum, scope, logger, lookAhead = RECEIVE_LOOKAHEAD)
    val change  = ElectrumMiniWallet(chain.chainHash, electrum, scope, logger, lookAhead = CHANGE_LOOKAHEAD)

    val walletState: Flow<GroupWalletState>        // both branches, with confirmations
    val receiveAddress: Flow<String>               // first unused /0/i
    fun nextChangeIndex(): Long                    // first unused /1/i

    fun derivationOf(script: ByteVector): Derived?   // which (change, index) this is, if ours
}
```

**Two mini-wallets, because a mini-wallet holds one generator.** `addAddressGenerator`
is single-shot per instance, and change and receive need separate gap limits. Both
share the business's Electrum client, which is safe because each mini-wallet ignores
script hashes it does not know.

**Gap limit.** Phoenix's final wallet watches one address; the swap-in wallet's
default `lookAhead` is 3. A group hands addresses out from several devices, so two
members can show the same next address and the gap is mostly bounded by deposits
nobody made. (The address list in
[group-wallet-addresses.md](./group-wallet-addresses.md) is what lets them hand
out different ones.) Start at 10 for receive and 3 for change — each is one Electrum
subscription per group, and a member in twenty groups pays for all of them.
`derivationOf` searches only addresses the wallets have generated, which is the
bound on how far Phase 7's checks will look.

**Confirmations** come the way `PeerManager.finalWallet` gets them: combine with
the tip and map through `WalletState.withConfirmations`.

**Who runs it.** `GroupWalletManager` starts one `GroupOnchainWallet` per room that
has a verified statement on the active identity's chain, as a child of the same
`collectLatest` over the identity that runs the notary and the sync pumps, so a
profile switch cancels it with them. It is in-memory, as Phoenix's is: everything
it knows is re-fetched from Electrum on start.

**An identity without a node.** `Identity.business` is null for an nsec or npub
profile, and with it the Electrum client. Such a member still holds a share if
they are in the quorum, and Phase 7 is built so they can sign without the chain.
What they could not do in this phase is see a balance; the screen said so rather
than showing zero. **Since built:** a standalone Electrum client for those
identities, [standalone-electrum.md](./standalone-electrum.md), which picks its
server as `AppConfigurationManager.randomElectrumServer` does. The screen's
*cannot watch* branch is gone with it.

**Test** in `jvmTest`, with a fake `IElectrumClient` — the interface is small and
documented never to throw: funding `/0/2` makes the receive address move to
`/0/3`; a change output is counted in the balance and not offered as a receive
address; two groups on one client see only their own coins.

---

## Phase 5 — receive

**Two days, most of it in the screen.**

- `GroupWalletRoute(activeUserPublicKey, chatRoomId, relayHint)` and a
  `composable<GroupWalletRoute>` in `DedunNavHost`, with `onNavigateBack` and
  `NavigateBackButton` — it is a pushed screen.
- A `SignedWorkRow` on `ChatRoomDetailScreen`, fed by a `groupWallet` field on
  `ChatRoomDetailUIState.Loaded`. With no statement, members who can sign see
  *"Open a group wallet"*, which proposes one; others see nothing.
- The screen: balance (confirmed and pending, said apart), the receive address
  with a QR code and copy, and the coins. Four states, through
  `ScreenStateTransition`; `EmptyState` has its own message — *"Nothing has been
  sent to this wallet yet"* — not the shared one.
- *Export watch-only* copies the descriptor, under a sentence saying what it gives
  away: every address and the balance, to whoever has it.

Conventions that bite on this screen in particular: the QR code's modules are the
one place a hardcoded colour is right, and take `// m3-color-exempt:`; an amount
is a format argument in the string catalogue, not an interpolation; and the
address is selectable text, not a `.clickable` without
`minimumInteractiveComponentSize()`. `./gradlew :composeApp:m3Audit` before
committing.

---

## Phase 6 — schema for spends

**Half a day. No behaviour change.**

Version 25 → 26, `AutoMigration(25, 26)`, three changes:

```kotlin
enum class FrostSigningSubject { EVENTS, TRANSACTION }

// FrostSigningSession
val subject: FrostSigningSubject = FrostSigningSubject.EVENTS     // @ColumnInfo(defaultValue = "EVENTS")

// FrostSigningItem -- two property renames that are not column renames
@ColumnInfo(name = "eventId")           val message: HexKey      // an event id, or a BIP341 sighash
@ColumnInfo(name = "unsignedEventJson") val payload: String      // the unsigned event, or the input's derivation
val inputIndex: Int? = null

@Entity
data class GroupTransaction(
    @PrimaryKey val frostSigningSessionId: String,   // FK, cascade
    val chatRoomId: String,
    val chain: String,
    val account: Long,
    val psbt: String,               // as proposed, base64; write-once
    val txid: String,               // of the unsigned tx -- taproot txids do not change when witnesses are added
    val signedTransaction: String? = null,
    val broadcastAt: Long? = null,
    val confirmedAt: Long? = null,
    val replacedByTxid: String? = null,
)
```

**`subject` is a column, not an inference.** The session could be recognised as a
transaction by the presence of a `GroupTransaction` row, and the code that decides
what a nonce signs should not be a join away from the answer.

**The renames are Kotlin only.** `@ColumnInfo(name = …)` keeps the schema and every
row as they are, so the migration does not touch `FrostSigningItem` beyond the new
column, and `advance()` reads `item.message` for both subjects. It also removes the
one thing that would make this phase dangerous: `unsignedEventJson` is `NOT NULL`,
and changing that in SQLite is a table rebuild — which, as the batch note explains,
fires the cascades from `FrostSigningSession` and deletes the items it was meant to
keep. For an input item, `payload` is `{"change":1,"index":4}`.

**Taproot txids are fixed before signing.** A segwit txid excludes witnesses, so the
`txid` is known at proposal and is what the transcript, the list and the chain all
key on. That is the reason `GroupTransaction` can be written whole at acceptance.

**Test:** extend `FrostSigningSessionDaoJvmTest` with a transaction session and its
row, and the cascade; regenerate `schemas/26.json`.

---

## Phase 7 — the manager signs sighashes

**Three days. This is the phase with the security argument in it.**

### On the wire

`FrostSigningEvents.TRANSACTION_PROPOSAL`, kind **30332**, content the PSBT in
base64, and the same `FrostSessionIdTag` and `FrostKeyTag` as `PROPOSAL`. It goes
into `ALL`. Everything after the proposal — nonces, signer set, partials,
signature, decline, failure, resend — is the existing kinds unchanged, with one
comma-joined value per input, exactly as a batch of events.

What an old build does with it:

| old build receives | outcome |
|---|---|
| 30332 | not in its `ALL`; ignored |
| 30321-30324 for that session id | stored by `record()`, which accepts messages ahead of their proposal; never acted on |

That is the right failure: an old device never signs a transaction it cannot show
its owner. The stored messages are dead rows — note them in `dead-code.md` rather
than adding a pruning pass for a rollout artefact.

**A PSBT, not a raw transaction.** A signer needs each input's spent output to
compute any sighash, and its derivation to find its cache; PSBT v0 has a field for
both (`witness_utxo`, `tap_bip32_derivation`) and bitcoin-kmp parses them. Change
outputs carry `tap_internal_key` and `tap_bip32_derivation` too, which is how a
signer is *told* which output is change — and Phase 7's checks are how they decide
whether to believe it. The fingerprint in both is the account key's.

`witness_utxo` alone is enough. Before taproot a signer needed the whole previous
transaction to trust an amount; BIP341's sighash commits to every spent amount and
script, so a lie produces a signature that is invalid on chain rather than one
that overpays a fee. Phase 2 has the test.

### `proposeSpend`

```kotlin
suspend fun proposeSpend(
    database: DedunDatabase,
    localChatRoom: LocalChatRoom,
    userPublicKey: HexKey,
    psbt: Psbt,
    key: DkgSession? = null,
): FrostSigningSession
```

It runs the same checks as acceptance below on its own proposal, writes the
session (`subject = TRANSACTION`), one item per input with its own fresh 32-byte
`nonceRandom`, and the `GroupTransaction` row, all in one transaction; broadcasts
30332; then calls `advance`.

### `acceptProposal`, for a transaction

Every member re-derives everything from the PSBT and the room, and trusts nothing
the proposer computed — the counterpart of rebuilding each event's id from its
fields. A proposal fails whole if any of these fails, and the reason goes in the
log, not the transcript:

1. **There is a wallet.** The room has a verified statement for the PSBT's chain
   and account. The account comes from the statement, never from the proposal.
2. **Every input is the group's.** Each has `witness_utxo` and exactly one
   `tap_bip32_derivation` for the account fingerprint, with `change ∈ {0, 1}` and
   an index within the generated range; `GroupWalletDerivation.derive` at that
   path gives exactly the `witness_utxo` script. Foreign inputs are refused — a
   joint transaction with someone outside the group is a different feature, and
   one where a signer cannot see whose money is whose.
3. **Key path, default sighash, nothing else.** No `tap_leaf_script`, no
   `tap_merkle_root`, and `sighash_type` absent or `SIGHASH_DEFAULT`. Anything
   else is refused outright: `SIGHASH_NONE` or `SINGLE|ANYONECANPAY` would let
   whoever holds the signatures redirect the money afterwards.
4. **Bounded.** At most `MAX_BATCH_SIZE` (64) inputs, checked here independently
   of `proposeSpend`, for the reason the batch note gives: the proposal is the one
   place a remote party decides how much work everyone else does. Version 2, no
   absolute locktime in the future, every sequence at or below `0xfffffffd` so the
   spend can be replaced.
5. **Change is change only if it derives.** An output is labelled change when its
   claimed derivation reproduces its script; one whose claim does not derive is an
   error, not a payment, because it means the proposer is mislabelling.
6. **A sane fee.** `Σ inputs − Σ outputs`, refused above a ceiling (a feerate far
   over `FeerateManager`'s fastest, or a fixed fraction of the amount moved). This
   is not a security check — the approval screen shows the fee — but a fat-fingered
   feerate is the most likely way to lose money here, and refusing it costs a
   retry.
7. **Write-once.** A second 30332 under the same session id whose PSBT differs in
   any byte of the unsigned transaction is logged and ignored. Every item's seed is
   already committed to that item's sighash.

Then the sighashes are computed — `hashForSigningTaprootKeyPath(j, allSpentOutputs,
SIGHASH_DEFAULT)` — and written into `item.message`, once.

### `advance` and `complete`

- **The cache is per item.** In an event session every item shares the room's
  path, and the batch note's table lists the `TweakCache` as shared. In a
  transaction it is per input, rebuilt from `item.payload` through
  `GroupWalletDerivation.derive`, and passed both to `SecretNonce.generate` as the
  hint and to `Session.create`. A transaction session never reads `signingPath`;
  an event session never reads `payload`. One `when (session.subject)` at the top
  of the loop, not a branch at every call site.
- **`complete()`** verifies every signature with
  `Crypto.verifySignatureSchnorr(item.message, sig, derived.outputKey)` instead of
  `Nip01Crypto.verify`, adds the witnesses, and runs `correctlySpends` over the
  finished transaction before writing `GroupTransaction.signedTransaction`. It
  does not call `recordSignedEvents` or `applySignedEvent`: nothing here is a
  nostr event, and a transaction must not appear in the `GroupSignedEvent` table
  that the chronicle and the broadcast screen read.
- **The transcript** gets one new type, `TYPE_GROUP_TRANSACTION_SIGNED`, written by
  `complete`. It has to be added to `FROST_TYPES` and the settlement sets; a type
  missing from `FROST_TYPES` renders as a chat bubble and nothing fails.

### The rules that are the same as a batch, said again

- **A retry is a new session with new seeds**, never a re-proposal under the old
  id. A fee bump is a retry.
- **All or nothing.** A transaction with one unsigned input is not a transaction.
  Here there is not even a product question about partial success.

**Tests** in `jvmTest`, extending the two-device harness `SignedGroupKeyStateTest`
uses: a two-input spend between two devices over two databases reaches `COMPLETE`
on both, the finished transaction `correctlySpends`, and the two inputs' nonces
are distinct. Then one test per numbered check above, each driven by a hand-built
30332, each asserting no nonce was published — the observable fact, rather than
that a function returned early.

---

## Phase 8 — spend

**Three days.**

- **Send all** first, because it is what Phoenix's final wallet does and it has no
  change and no coin selection: every confirmed coin, one output, fee from the
  weight. The weight is exact before signing — a `SIGHASH_DEFAULT` key-path
  witness is always 64 bytes — so the dummy is
  `witnessKeyPathPay2tr(ByteVector64.Zeroes)` rather than Phoenix's dummy
  signature. Keep Phoenix's 546-sat floor.
- **Send an amount** next: largest-first over confirmed coins, change to
  `nextChangeIndex()`, no change output if it would be dust (the remainder goes to
  fee, and the screen says so).
- **Coins in an open proposal are reserved.** `WalletState.withoutReservedUtxos`
  over every `GroupTransaction` whose session is not settled. Two proposals
  spending one coin are not dangerous — only one can confirm, and each has its own
  nonces — but the second is a wasted quorum, and the composer should not offer it.
- **The feerate** from `FeerateManager`'s `halfHour`, with Electrum's
  `estimateFees` as the fallback, editable.
- **The approval.** `FrostSigningScreen` renders a transaction where it renders an
  event: every output with its address and amount, change labelled as change, the
  fee and feerate, and the inputs behind a disclosure. The batch note's rule holds
  with more force: *a member who cannot see every output must not be able to
  approve it.* `ProposalListScreen` shows a spend as *"Send 120,000 sats to
  bc1p…"*.

`FrostSigningRoute` is unchanged. A transaction is a session.

---

## Phase 9 — broadcast and after

**Two days.**

- **On `COMPLETE`, every member with an Electrum client broadcasts.** The
  transaction is identical on every device, so a second broadcast is a no-op on
  the network, and nobody's phone being asleep holds up the group. This is unlike
  a group event, where broadcasting is a separate decision; here the quorum has
  already decided the one thing broadcasting does.
- **Broadcast reports no failure.** `ElectrumClient.broadcastTransaction` drops the
  server's error and returns the txid either way. So "broadcast" is recorded when
  sent and "seen" only when the wallet's state contains the transaction; a
  *Broadcast again* action appears when one has been sent and not seen.
- **Transcript lines** for broadcast and confirmed, written by each device from
  what it observed, like every other line: no event behind them.
- **A conflicting spend.** When a reserved coin is spent by a different
  transaction, the session that wanted it is over whatever stage it is in;
  `ProposalState` shows it as abandoned with the reason *"Spent by another
  transaction"*.
- **A fee bump** is a new proposal spending the same coins at a higher feerate —
  RBF, which the sequence rule in Phase 7 allows — and `replacedByTxid` links the
  two when one confirms. CPFP from a change output needs nothing new: it is an
  ordinary spend of a coin the group owns.

---

## Phase 10 — rollout

**No code beyond a flag and a cap.**

| gate | lifts when |
|---|---|
| the wallet is behind a feature flag, off | Phases 1-9 are in and the jvm suite passes |
| on mainnet, a statement can be signed and a spend proposed, but the composer refuses to move more than a small fixed amount | a release has run real spends end to end with no session that failed to aggregate or a transaction that failed to confirm |

If a testnet build exists by then, the cap is unnecessary there and the first two
rows run on it instead.

The cap is about the new code, not the shares: per
[Phase 0](#phase-0--before-any-code), the shares stay unencrypted at rest, and
lifting the cap does not change that. Anyone with a member's unlocked device, or a
backup of its database, holds that member's share in the clear. The member-facing
text where a group opens its wallet should say so, in a sentence.

Before any group turns it on, every member's device needs a build that understands
30331 and 30332. There is no negotiation and none is worth adding: a member on an
old build simply never sees the proposal, which is a member who did not sign, and
a quorum without them still can.

### Three hazards that are not bugs

**Re-keying moves the money, or loses it.** A group that re-runs its ceremony gets
a new key and, by `GroupKeyStateEvent`'s design, a new room. The old wallet is
spendable only by the old shares. So the rule, stated where a group decides to
re-key: *sweep the old wallet to the new one first.* A group that re-keys because
it cannot muster the old quorum has already lost access, and nothing here can
change that.

**A removed member is still a share.** Leaving the Marmot group does not delete
anybody's `DkgSession`. Any `t` holders of the old shares, removed or not, can
spend. Removing a member from a group with funds is therefore a re-key plus a
sweep, and the member-removal flow should say so when the room has a wallet.

**Everyone who was ever a member can watch forever.** They hold `K`. That is the
same as a shared xpub in any multisig and is stated here so nobody is surprised.

### What is left, when someone wants it

- ~~A standalone Electrum client for nsec and npub identities (Phase 4).~~ Built:
  [standalone-electrum.md](./standalone-electrum.md).
- **Funding from a member's own node.** Phoenix's close-channels flow takes a
  destination address (`CloseChannelsConfigurationController` defaults it to
  `finalAddress`); offering the group's receive address there, and as a
  splice-out destination, is how a member moves money from their Lightning
  balance into the group's. This app has no on-chain send UI of its own yet, so
  it is a product decision as much as a feature.
- Additional accounts: `account = 1`, its own statement, nothing else changes.

---

## Appendix — what was considered and rejected

**Phoenix's own path, `m/84'/0'/0'/0/0`.** Hardened, and P2WPKH. Neither is
reachable from a threshold key.

**BIP86 as written, `m/86'/0'/0'/<c>/<i>`.** Three hardened levels. The account
step is what replaces them.

**BIP86 with the apostrophes removed, from `K`.** Needs an invented root chain
code, puts `K` in any exported xpub — and with it the room's identity — and looks
like a path it is not. See [Why each level is what it is](#why-each-level-is-what-it-is).

**The room's own key, `m/9420/0/0`, as the internal key.** The room's id is that
key, so anyone who can see the room's id could compute the wallet's address and
read its balance off the chain. It would also have one key sign nostr events and
bitcoin transactions, which is cross-protocol key reuse with nothing to gain.

**`SharedKeyDerivation` at a new path, e.g. `m/9421/<coin>/<account>/<c>/<i>`.**
Works, reuses tested code, and needs no new tag. Rejected because nothing outside
Dedun can derive its addresses: no ranged descriptor, no meaningful
`tap_bip32_derivation`, no watch-only tool, and no published vectors for any of
it. Its reasons for avoiding BIP32 do not apply to a full-point key with a chain
code to hand.

**A random chain code agreed by the group.** Hides the wallet from someone who
learned `K` without being a member, a case `GroupKeyStateEvent` already rules out,
and adds a value whose loss strands the coins. See
[Why each level is what it is](#why-each-level-is-what-it-is).

**A separate ceremony for the wallet.** The strongest option, and the one to
choose for a wallet holding more than the group would mind losing. Its key is
independent of the room's, so a leaked wallet key reaches neither the room's
signature nor any other account — the isolation the derivation note says
derivation cannot give. It costs a full ceremony, which every member must take
part in, and a second `DkgSession` per member. The plan above is compatible with
it: `GroupWalletDerivation.account` takes a threshold key, and nothing says it has
to be the room's. Phase 3's statement would name which ceremony's key it is, with
a `FrostKeyTag`, and every phase after it is unchanged.

**A script path for recovery** — a timelocked leaf letting a smaller quorum, or a
named recovery key, spend after a year of inactivity. A real answer to "the group
can no longer reach `t`", and a different descriptor, `tr(K, {…})`, with a
different security argument: BIP86's "no script path" is what lets a member check
an address alone, and a recovery leaf replaces that with checking the leaf.
Worth its own plan.

**MuSig2 instead of FROST.** `n`-of-`n` only, and the group already has a `t`-of-`n`
key and a ceremony that made it.

**A raw transaction plus a list of spent outputs instead of a PSBT.** Smaller, and
a format of our own for exactly the information PSBT already standardises,
including the derivation fields that make the wallet legible to a watch-only
tool.
