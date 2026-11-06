# A group Wavelength wallet

An Ark wallet that belongs to a group. Every key that can move its money is an Iceberg threshold key, which no
member holds. One member at a time, the **operator**, runs Lightning Labs' `waved` daemon on their device with no
fund key in it. Every signature the daemon needs is asked of the group, and each member's device checks what it is
being asked to sign before it answers. People approve money leaving. Devices approve keeping it alive.

**Plan, not built.** Written on 6 November 2026 against `94142916`, with `wavelength-kmp` at `2a92750c`, its nested
daemon at `63d545fb` (`machankura/wavelength`, `descriptors`), `lightning-kmp-app` at `949bfcb9` and lightning-kmp
at `065dcf89`. Files and lines named below are as they stood at those commits, and the submodules were read in the
main checkout, since a fresh worktree does not have them.

**Nothing here has been run.** Every claim about the daemon and the libraries comes from reading them. Phase 0 exists
to test the ones the plan depends on, and three of its spikes can change the design or stop it.

Read these first:

- [group-lightning-wallet.md](./group-lightning-wallet.md): the operator, the Iceberg ceremony, the co-signer, the
  echo rule and the label ledger. This plan reuses all of them, and its constraints 1 to 5 and 8 apply unchanged.
- [wavelength-wallet.md](./wavelength-wallet.md): what Wavelength asks of an app, and what its Phase 0 found.
- [member-deposits.md](./member-deposits.md) and [payouts-only.md](./payouts-only.md): the rules any group wallet
  now follows for money coming in and going out.
- `…/secp256k1-kmp/native/secp256k1/doc/iceberg.md`: the scheme's own rules. It says *"Do not put money behind this
  module."*
- In the daemon: `docs/daemon_cli_guide.md` (the remote signer), `docs/arkscript_spec.md`, `docs/swap_system.md`,
  `lib/keyfamily/keyfamily.go`.

The headline: **the daemon's watch-only mode gives this plan a place to put a threshold signer, and the app's Iceberg
work gives it the signer. Neither does the five things that make it safe.**

1. **A signer does not judge.** The daemon's guide says of a remote signer: *"neither an lnd signer nor `wavesigner`
   judges what it signs."* A threshold signer that signs what it is handed gives the operator the whole wallet with
   extra steps. The policy that makes signing conditional has to be written, in Kotlin, for every shape of Ark
   transaction (Phase 7). This is most of the security of the plan and most of its risk.
2. **The tree signing key cannot be derived.** Each VTXO needs its own MuSig2 key in the tree (`lib/tree/tree.go:358`:
   *"Each signing key maps to exactly one VTXO"*), and neither Iceberg nor prefractal can sign under a key tweaked
   below the aggregate. So the group needs another way to make many keys (D5).
3. **The identity key is too busy for a threshold and too powerful to leave out.** It signs mailbox authentication and
   indexer queries, so a quorum per use cannot work. It is also the vHTLC key and the credit account, so one device
   holding it can take money (D6).
4. **Iceberg has only ever signed beside a cosigner.** A VTXO's owner key signs a plain tapscript signature, with no
   second MuSig2 participant, and `secp256k1_iceberg_partial_sign` requires a cosigners' nonce (D4).
5. **A wallet with no quorum loses money rather than just stalling.** A Lightning channel with no quorum stops, and
   the money waits. A VTXO with no quorum expires, and after that it depends on Ark's operator (D11). The operator's
   round deadlines are also unknown, because its code is not in the tree (Phase 0, spike 4).

Two things are owed before any of it. **No coin has moved through Wavelength in this app on any network,** so the
personal wallet's own money paths are unproven. And **the fork has no release** (`wavelength-wallet.md`, D2). This plan
adds to that fork and leans harder on it.

### Where the build stands

One commit per phase. The phases below are kept as written; this table says what happened to each.

| phase | state |
|---|---|
| 0 | **partly run.** Spikes 2 and 3 pass, in `IcebergArkSpikeJvmTest`. Spikes 1 and 5 are half answered against real signet servers, and 6 as far as the wire format goes. Spikes 4, 7 and 8 are open: 4 and 8 need signet coins, the rest of 1 and 5 do too, and 7 needs an answer from Lightning Labs. **Spike 4's condition for going on, that the owner has seen the operator's deadlines, is not met:** the phases below are being built against the shut gate meanwhile, on local branches, and can't be finished without it. See "What Phase 0 found" |
| 1 | **not started, and not this session's to do.** It needs signet coins sent by hand and a release of the fork, which is the owner's call. Everything below is on local branches, gated, and unproven against any money |
| 5 | done as far as it can be without a runtime in `jvmTest`: `GroupWavelengthDaemon` opens a hosted wallet only for the elected, freshly synced device, with the manifest the group signed, and stops one that opens as anything else. Four tests, over the SDK's fake. **Not built: D13's holder and a foreground service.** The fake refuses a second daemon, as the real SDK will, so a member's personal wallet and the group's can't both open, and nothing yet decides between them. `IdentityWavelength` is being changed on another branch right now, so that waits. Nothing calls this: the gate is shut. Handover's stopped message (30382) isn't sent yet either |
| 6 | done, on the same tables, with no change to Lightning's co-signer: `GroupArkCoSigner`, `GroupArkTransport`, `ArkWire`, `GroupWavelengthOperatorManager` (the election half of Phase 5, because the term is what a co-signer fences on), schema still 38. Nineteen tests on four devices in `GroupArkCoSigningJvmTest`: the group signs an exit, and a tree node beside a stock operator, and both signatures verify; a theft gets no signature and each device says so; an old term gets nothing; an operator showing two messages under one label gets no share and everyone raises the alarm; a restored device answers nothing until it knows how far the group has got, in labels and in tree keys. **The ledger reserves and pends, and nothing settles it yet:** that takes something watching the chain and the operator's own signatures, which is Phase 8's, so until then a device spends no VTXO it has only seen pending. Not covered: a real daemon has not talked to it, only the same requests by hand. The transport still sends one signature to a batch: the wire has batches, but nothing collects them yet |
| 7 | **the part that can be tested without a host, done; read twice by a reviewer that isn't a person, and by nobody else.** `ArkPolicy`, `ArkScripts`, `GroupArkKeys`, 63 tests in `ArkPolicyJvmTest`, against fixtures `round/group_policy_fixtures_test.go` builds with the daemon's own builders and passes through the real callback signer. So every allowed case is a real shape: its digest, tree aggregate and scripts have to agree with Go's, and did. Covered: proofs, swap acknowledgements, join proofs, tree signatures, forfeits, boarding, exit sweeps, on-chain spends, transfers to the group's own keys or an authorised script. **Refused for now, so nothing is signed blind: every vHTLC leaf** (claim, refund, and the forfeit of a funded one), because a co-signer can't yet rebuild the policy, so Lightning can't move through this wallet until Phase 10. **Not done:** the second person's adversarial test list, and the property tests. See "What the readings found", which is why that person still matters |
| 4 | done, except the screen: `GroupWavelengthEvents` (30370-30382), `GroupWavelengthWalletStatementEvent`, `GroupWavelengthSeed`, `GroupWavelengthWalletManager`, `GroupWavelengthRollout` with `ENABLED` off and mainnet refused, `IcebergKeySession.purpose`, schema 38. Ten tests in `GroupWavelengthWalletJvmTest`. The statement carries a fee policy where the plan's had only a cap, and no swap server, which the operator's key implies. Nothing reaches this from a screen yet: that waits for Phase 9, behind the gate |
| 3 | done, in the fork and the SDK, except its "done when": `callbacksigner`, `lib/signctx`, tree keys from the host, no start-up probe, vHTLCs keyed from family 202, credit closed, notes for rounds and transfers, `HostedWalletClient`, and Android bindings rebuilt. Commits `a2d849b6` to `a61089ae` and on, local and not pushed. The fork's touched packages pass. A funded wallet hasn't signed anything through it: a host that answers a signed board or send on signet still needs coins |
| 2 | done: `GroupArkDerivation`, `GroupArkAuth`, `GroupArkManifest`, `IcebergChildKeys`, `GroupArkSigner`, 12 tests in `GroupArkKeysTest`. Four tests in the fork (`walletdesc/group_manifest_test.go`) pin the same bytes against the daemon's own parser: the manifest is already canonical and hashes the same, the auth keys join it, an owner child matches, and lnd's MuSig2 aggregates a tree key to the same output key |

| what the plan said | what it turned out to be |
|---|---|
| D4: `tr, 42, 44, 202, vHTLC` slots | `tr, 42, 44, 45, 202`, because a manifest must fill every fund family in `keyfamily.Registry`, and adding one to the registry would break every existing manifest and minimal bundle. So no family 203, and the vHTLC key has to come from one the group already holds (Phase 3) |
| D6: identity moves to the `Auth` class | the registry doesn't change. Identity stays a fund slot in the manifest, filled from the authentication seed's root at its own path, so it isn't an override and needs no `allowOverride`. The callback signer answers for family 6 on the operator's device at once. The daemon can't tell |
| D6: a new fund family 203 for vHTLCs | index 0 of family 202, for the reason in the first row. It can also be a receive script's key, which costs nothing: a different leaf, and the operator sees both anyway. **It also names the credit account**, so credit is now guarded by the same key, though D10 stays: nothing here tests how the server treats it |
| D3: the callback carries lnd's protobufs | its own small JSON, decoded in Go. bitcoin-kmp's PSBT can't carry a taproot leaf (`group-lightning-wallet.md`), and a host wants the transaction, every prevout and the leaf, so `callbacksigner` reads the PSBT and writes the signature back |
| Phase 3: a signature with no registered context fails, in Go | the note is attached if there is one, and Kotlin refuses a kind that needs one and has none. Go can't tell which kinds need one without reimplementing the policy |
| Phase 3: each call site registers what it signs, by digest | two notes: one when a round has been validated, under its commitment, tree transactions, tree digests and connectors, and one when a transfer is signed, under its Ark transaction and checkpoints. A forfeit finds its round through the connector it spends. Everything else carries its own transaction, so it needs none |
| D12 and the statement: fee caps only | the statement also fixes the operator's exit delay, and a co-signer refuses a VTXO under any other. It is the operator's to change otherwise, and a longer one holds the group's coins up on the way out |
| Phase 6: extract `IcebergCoSigningCore` from the Lightning co-signer first | not extracted. That code runs a wallet on mainnet, and moving it has no value to anyone using it yet. The new co-signer shares its tables, under a wallet key and label domain of its own, and repeats about a hundred lines of the echo logic. **So the restored-device rule is built here only, and the Lightning co-signer still lacks it.** The two can become one once this wallet has signed something real |
| Phase 6: the floor is checked against every label | plain labels by counter, and tree keys by child: a tree key below the floor is refused whole, since it may have answered under any of its labels |
| Phase 6: the echo is `(label, message hash)` | the hash also binds the nonces, the cosigners' nonce, and the key: the operator chooses those, and a share under one label for two challenges leaks whether or not the message differs. Round two signs from the row a device checked, not from a request |
| Phase 3: one note for a round, and one for a transfer | each gets amended partway: a round's when its tree is signed, a transfer's when the operator has co-signed. Those are the proofs a device needs before giving anything up |
| Phase 7: a checkpoint is checked against the rules for a transfer, by itself | the package as one: both transactions, each time, because a checkpoint means nothing without what the Ark transaction does with it |
| Phase 7: no VTXO in two live rounds | through a reservation on the commitment's txid, which ends with the term it was made in. Within a term a hostile operator can freeze a coin by asking for a round it never submits, and an election is the way out. After one, the same coin can be signed into a second round, which costs nothing: a forfeit only counts if its round happened, and then the group has the new coin |
| Phase 7: the statement fixes fees and the exit delay | and a shortest batch life, `minSweepDelay`, because how soon the operator can sweep decides how long the group has to renew or leave. **Its default, 1,000 blocks, is a guess** until a real operator's terms have been read, and so is the 144 beside it |
| Phase 7: `ValidatePath`'s rules for a tree key | those, and one thing more: a tree key signs for one commitment, ever. `round/transitions.go` keeps a VTXO's keys across a retried round, so **a retried round is refused**, and the coins wait for the next one. It fails the safe way, but a real operator's retry rate is one more thing Phase 8 has to measure |
| Phase 2: a Go test writes the fixture and a Kotlin test reads it | both pin the same literals. Kotlin computes them from a group key, and Go has to accept them and hash them the same |

**What the readings found.** Before anything had been signed, an agent reading the policy adversarially, with none of the reasoning behind it, found nine holes that 39 tests had passed straight over, because each test changed one thing about a valid request and the holes were in what nothing checked at all. Four were enough on their own to lose the wallet to whoever runs the daemon:

| found | what it would have let a hostile operator do | now |
|---|---|---|
| an exit could pay its value to an anchor | take all of it, needing no help from the Ark server, since an anchor is anyone's | an anchor is allowed for nothing, and only for nothing |
| a checkpoint's own output wasn't checked | get a VTXO signed over to a thief, with a harmless Ark transaction beside it to satisfy the rest of the policy | its output is rebuilt from the owner's key |
| a tree key signed any node it was on, as often as asked | with the Ark server, re-sign a VTXO's path to somewhere else after the round | only the path to its own VTXO, for one commitment, ever |
| the tree wasn't validated as the daemon does it | build a tree whose ancestors the group's key isn't on, or that loses value on the way down | `VerifyVTXOPath`, `Tree.Verify` and `ValidateDelayParameters`, ported |
| value was counted per note, and blind to duplicates | describe one output twice, or give each request its own account of a round | each output counted once, one note per commitment, for good |
| a label could get two challenges | extract a share, by showing members the same message under other nonces | the echo binds everything that goes into the challenge |
| boarding keys past the window counted as nothing | make a deposit vanish from a round's accounting, and keep it as fee | a key outside the window is refused |
| a signature wrote the ledger as fact | credit coins that don't exist, and retire ones that do | reserved and pending, until this device sees it settle |
| tree labels had no floor | ask a restored device to answer a tree key's label again | a floor for tree keys, asked for with the plain one |

A second reading, after all of that was fixed, found more, and three of them were as bad:

| found | what it would have let a hostile operator do | now |
|---|---|---|
| a note's boarding input could be negative, and nothing covers a forfeit's view of it | bring what goes into a round down to what comes out, and hide a forfeited VTXO in the difference | no value outside consensus's range, in a note or a transaction, and no sum past `MAX_MONEY` |
| a checkpoint was signed with no sign the operator had co-signed its Ark transaction | with the Ark server: publish the checkpoint, wait out the delay, and take the coin by the operator's own leaf | the fork's note carries the Ark transaction's signatures, and a device verifies the operator's on every input first |
| forfeits and boarding inputs were signed with no sign the tree was signed, and a node could have a locktime | confirm a commitment over a tree nobody can exit, and sweep it at expiry. An election doesn't help | the fork's note carries the tree's signatures, and a device verifies every node on the path to each of its VTXOs, none locked. The daemon does the same, and a device no longer leans on that |
| any member could send a huge high-water mark | stop every device that was still asking, for good, or wrap a floor round to nothing | only an answer to a question counts, and only close to what `t` members agree on. A liar costs some labels. The operator and provisioning skip to the floor |
| any member's stray echo stopped a label, and one bad share spoiled a signature | veto everything | an echo that disagrees raises the alarm and stops nothing, and shares are verified one at a time, of which `t` are enough |
| fee caps were per request | burn the on-chain account a self-spend at a time, or the VTXOs a round at a time | a budget over thirty days and one term, `maxMonthlyFeeSat`. Exits don't count against it. **Its default is a guess too** |
| labels didn't name the chain or wallet | get three answers per label out of three statements on one key, given a FROST quorum | they do |
| the operator's nonces were taken on its word | choose which nonces a challenge is built from | they have to be what members published |

It also said, working the algebra, that showing different messages to different members could beat the echo rule in a 3-of-5, where honest members each reach a quorum with the colluders' echoes before seeing a conflict. True, and it is why `n >= 3t-2` isn't only a rule about the library: there, any two quorums share `t` participants, so one of them is honest and won't echo twice. A 3-of-5 can't be deployed, but nothing here stopped a statement naming one, and now `verify` does. **The rule rests on that, so it should be the first thing an outside reviewer checks.**

Left as they are, and known:
- **A swap authorisation signs a digest of a request a device can't see.** So the operator can authorise anything the swap server takes from that key, including redeeming credit. That is harmless while the daemon's credit stays closed (D10), and it stops being so if that ever changes. A device could only tell with the request itself in a note, which the fork would have to supply.
- **The operator names the sweep key,** because nothing a device holds says what it should be. A sweep key can do at expiry only what the operator can do by then anyway.
- **Nothing settles a VTXO yet,** so until Phase 8 builds the watcher a device refuses every forfeit and transfer. That is the safe way round, and it means those two paths have only ever run in tests, against fixtures.
- **A bad fee budget can hold up a renewal.** Rounds past the budget are refused until the window passes or the term changes, and a batch's expiry doesn't wait. Exits still work. Phase 8's numbers decide whether the default is right.

**These readings aren't the outside review Phase 14 asks for.** They say what a careful reader found, on two passes, and the second found things the first didn't, so there is no reason to think a third would find nothing. What they do show is that the tests here prove the allowed shapes are real, and that they don't prove the refusals are complete.

**Where this stops, and why.** Phase 8 is next, and it can't be done from here: it needs signet coins sent by hand, and Phase 1 has to happen first, a person seeing coins move through the personal wallet. Spike 4's deadlines, which the owner was to read before anything was built on them, come out of the same run. Phases 9 to 13 stand on Phase 8 and on those numbers, so they aren't started: a screen for a wallet that may turn out to need always-on co-signers would be written twice.

## What Phase 0 found

| spike | answer |
|---|---|
| 2. A plain signature from Iceberg | **Works, with no library change and no public cosigner.** A MuSig2 aggregation of the group's key alone, an outer BIP32 tweak, and 66 zero bytes for the cosigners' nonce, which is how MuSig2's extended encoding writes infinity. A 2-of-4 group spends a VTXO's collaborative leaf beside a stock operator's signature, and a BIP86 key path, and bitcoin's interpreter accepts both. The cache's child is the key an xpub derives, checked at each step. A wrong child and another group's shares both spend nothing. D4's public cosigner works too and stays as the fallback |
| 3. A child key in the tree | **Works, both ways.** A hashed child (D5) gives public shares on one polynomial from every quorum at 2-of-4 and 3-of-7, and signs a tree node's key path beside a stock MuSig2 cosigner under a script-path tweak. Any quorum can sign, and a member who sat round one out can sign too, which `IcebergSigner`'s comment said was untested. The parent's shares and a sibling's do not sign for a child. **D5's fallback also signs:** with the child passed as `group_pk` and `e·a·g·gacc·τ` added afterwards, the spend is valid. So it is a real alternative that would need no daemon change, and its only remaining objection is the one in D5: it is outside the module's contract and the draft proof. D5 stands |
| 1. The census, what can be run with no coins | **Run on 6 November 2026, against the public signet operator, with a throwaway hosted wallet (`TestHostedSignetCensus`).** It opens with no signature on a fund key beyond indexer proofs, in 52 seconds. **A recovery scan costs five group signatures per key index:** two on family 44 and three on family 202, all tagged `wavelength/indexer/v1`, so a window of 3 was 15, and a window of 100 would be 500 quorum sessions. That changes D14: a handover has to use the narrowest window the last snapshot allows. An idle minute asked for no fund-key signature at all, only mailbox authentication on the identity, which the device answers at once. An invoice costs two group signatures: `swap-credit-account-auth-v1` and a proof registering the claim script. Everything with money is still uncounted |
| 5. A vHTLC key that isn't the identity | **Half answered, by the real server.** Lightning Labs' signet swap server issued a route and an invoice for a `client_vhtlc_pubkey` that isn't the wallet's identity, and took the account authorisation from that key. What it does when the vHTLC is funded is still not known without a coin. Before that run, the wire already looked encouraging. `RequestChannelIdRequest.client_vhtlc_pubkey` is a key of its own, and `swap.proto` says it derives the channel id *"without adding a separate auth identity"*. `account_authorization` is needed only *"before consulting or reserving account credits"*, which D10 closes. Two things are now known that the plan had missed: `AcknowledgeOutSwapHtlc` carries a BIP-340 signature from that key, so **every receive's acknowledgement is one more group signature**, and `CreateInSwap` takes the key too, so an outgoing vHTLC can be keyed the same way. **And a cost:** the indexer authorises a vHTLC query by proof from a participant of its policy, so while a receive waits for its vHTLC, every poll is a group signature unless the mailbox event is used instead. That is the next thing to fix when coins are available |
| 6. An invoice a member can check | **Not settled, and leaning towards no.** `swap.proto` says the server combines the key and the hash *"to derive a unique virtual channel ID"*, but not how, and the server's code isn't in the tree. So a member cannot recompute it today. It stays an open hole in the threat table |

---

## What was asked, and what exists

| asked for | what exists | what is missing |
|---|---|---|
| a wallet on Wavelength | `IdentityWavelength`, one daemon per process, a personal wallet on mainnet with no coin moved | a daemon whose keys are not in the app's process, and a holder that can run one for a group |
| keys outside the daemon | `StartWatchOnlyWallet`, a minimal bundle or a manifest, `accountsigner`, `signerserver`, `wavesigner` | a signer the app itself can answer as, without opening a port |
| no single member can move funds | `IcebergDkg`, `IcebergRitualManager`, `GroupIcebergTransport`, `GroupLightningCoSigner`, the echo rule, the label ledger | the same machinery pointed at Ark's requests, plain signatures, many keys, and a policy for Ark |
| group coordination | `FrostSigningManager`, proposals, `GroupLightningAuthorisationEvent`, `PayoutRule` | an Ark form of authorisation that says what a co-signer may sign |
| an operator | `GroupLightningOperatorManager`, terms, the start gate, handover | a handover for a daemon, whose state rebuilds from the chain and the indexer and not from a peer's backup |
| deposits and payouts | `MemberBalances`, depositor statements, `GroupLightningPayouts`, `GroupPayoutDestination` | Ark's rails in each: a boarding branch, a depositor's invoice, a vHTLC as a payout |

## What the daemon asks of a signer

From `lib/keyfamily/keyfamily.go`, `docs/daemon_cli_guide.md` and the signing call sites.

| key | used for | what it signs | here |
|---|---|---|---|
| `tr` account, `m/86'` | the backing onchain wallet, exit fees | BIP86 key path, through `SignPsbt` | group (D4) |
| family 42, boarding | owns boarding outputs with Ark's operator | tapscript | group (D4) |
| family 44, VTXO owner | committed into every VTXO's two leaves, signs forfeits and exit sweeps | tapscript, `<owner> <operator>` and `CSV <owner>` | group (D4) |
| family 45, VTXO tree | one per VTXO, a participant in the tree's MuSig2 | MuSig2 partial signature, key path | group, with its own derivation (D5) |
| family 202, OOR receive | owns out-of-round receive scripts | tapscript, plus indexer proofs for its scripts | group (D4) |
| family 6, identity | mailbox authentication, mailbox TLS bind, indexer proofs, the swap's client certificate, **and the vHTLC key and credit account** | tagged Schnorr, often, with no output to inspect | split (D6) |
| family 21, receive auth | signs invoices, decrypts the final onion | ECDSA and ECDH, which neither threshold scheme can do | shared secret (D6) |
| family 43, round-join auth | authenticates a join request | Schnorr | shared secret (D6) |

The daemon's signer protocol is lnd's: `SignMessage`, `DeriveSharedKey`, `SignPsbt` and the `MuSig2*` calls. Its
`RemoteSignerConnection` is an interface, and `routedConn` and `accountsigner` already answer it in-process. A
**PSBT request shows the signer the transaction and its prevouts. A `MuSig2Sign` request shows it a 32-byte digest and
nothing else.**

Every start also has the signer sign a fresh challenge with each fund family's first key, and spend a fabricated
output (`walletcore/signer_probe.go`). `BackgroundRestartPolicy.reopening` restarts the daemon on every foreground
return. Left as they are, those two would mean a quorum to open the wallet, every time.

## The constraints the plan follows

From `group-lightning-wallet.md`, unchanged: Iceberg shares cannot be made from FROST shares (1), so this is a ceremony
of its own, over `IcebergDkg` (2). Only 2-of-4 to 2-of-10, 3-of-7 to 3-of-10 and 4-of-10 can be deployed (3). Rounds
one and two each want `2t-1` devices (4). One label signs one message across the whole group, or a share leaks with no
error (5). The module is experimental and says so (8).

New here:

**9. Round one has to answer at once.** `MuSig2CreateSession` returns its public nonce in the same call, and the round
goes on to the operator with it. Iceberg's round one does not depend on the message, so a pre-published pool answers it.
This is why Iceberg fits and prefractal fits worse (D1).

**10. A round is a deadline the group does not set.** The daemon gives itself 30 minutes (`docs/round_admission_deadlines.md`)
and 10 seconds per signer request, which is configurable. Ark's operator will have shorter deadlines of its own, and nothing
in the tree says what they are. Scheduled rounds also have a cutoff of their own.

**11. `InputSigSentState` is the point of no return.** Once forfeits are handed over, a timeout does not release the inputs.
So a co-signer's checks all have to be finished before the forfeit, and they can't be undone by a later refusal.

**12. One daemon per process.** `sdk/wavewalletdk/mobile/mobile.go` holds a package-level singleton, and the SDK's lifecycle
lock depends on it. A member who operates a group wallet and has a personal wallet has one slot for two (D13).

**13. One daemon per wallet, anywhere.** *"Running two daemons with the same seed at the same time can make responses arrive
at the wrong process."* It is a nuisance rather than a loss once co-signers fence on term, but it still has to be prevented.

**14. There is no way to test against a local Ark operator yet.** The README mentions a local regtest operator. The tree holds
`waved`, `wavecli` and `wavesigner` and no server, and `harness/` starts bitcoind, electrs and lnd. Unless spike 7 finds one,
the end-to-end runs are on signet, whose coins come by hand because it has no faucet API.

---

## Decisions

**D1. Iceberg, with its own ceremony per wallet.** The participants are the onchain key's holders still in the room, as in the
Lightning wallet's D1, with their own `t`.

- **Not FROST.** It cannot occupy a MuSig2 slot, so it cannot sign the tree. Its sessions here are human-approved and hold a nonce
  between rounds.
- **Not prefractal.** It could reuse the ChillDKG shares and express any `t`-of-`n`, which would let small groups in, and that is a real
  loss. But round two must be signed by exactly the set that answered round one, and in a round the nonce has already gone to Ark's
  operator, so one phone dropping out loses the round. It has no answer to D5 either: a polynomial share has no equivalent of deriving a
  fresh key without interaction.
- **Not the Lightning wallet's Iceberg key.** A shared key would share a label space and a removal. `GroupLightningWalletManager.unusedKey`
  takes the newest key no statement uses, so a ceremony gains a `purpose` (`LIGHTNING`, `WAVELENGTH`). Without it, a Lightning statement
  could claim this wallet's key. Existing rows read as `LIGHTNING`.

**D2. A watch-only daemon on one elected operator's device, and no fund key in it.** Everything that can spend sits behind the signer. The
operator is trusted for liveness only, elected and fenced by term exactly as in the Lightning wallet's D3. **Only the operator needs a daemon.**
So desktop, which has no Wavelength runtime, and iOS, whose runtime has never been compiled, can both co-sign. A desktop left on is the realistic
always-online co-signer. Until `wavelength-wallet.md`'s D4 moves, only Android can operate.

**D3. The signer is a callback, in-process.** A new Go package, `callbacksigner`, implements `rpcwallet.RemoteSignerConnection` over a
gomobile interface that Kotlin implements. `accountsigner` already shows the shape, with requests served in-process.

- **Not a gRPC server in Kotlin.** That means a new dependency, TLS and a macaroon to manage, and a port on a phone, all to talk to our own process.
- **Not `allowinbound`.** It has the same costs in the other direction.
- `RuntimeConfig.remoteSigner` gains a third form beside `rpcHost` and the inbound stream. A normal wallet is untouched.

**D4. Plain signatures come from one group key, with BIP32 children as outer tweaks.** The group's Iceberg key `P` is the only participant in a
one-key MuSig2 aggregation, `Q = KeyAgg([P])`, and each family gets a public derivation step from `Q`, as `GroupLightningDerivation` does:

```
I   = HMAC-SHA512("dedun/group-wavelength/account/v1", ser(Q) ‖ chain ‖ ser32(wallet) ‖ ser32(slot))
Q_s = Q + I[0:32]·G,  c_s = I[32:64]        an xpub, one per slot: tr, 42, 44, 202, vHTLC
```

The daemon derives children from those xpubs itself, and they reach Iceberg as `musig_pubkey_ec_tweak_add` on the outer cache, which the module handles
correctly and takes no input on. The slots go in as a **manifest**, which the daemon supports for fund slots from several keys. Every member
recomputes the manifest, and the statement carries its hash, which `info.manifestHash` then confirms (Phase 4).

`secp256k1_iceberg_partial_sign` takes a non-null `cosigner_aggnonce`, and nothing has been signed with no cosigner. Spike 2 tests it. **The fallback needs
no library change:** a public cosigner, `Q = KeyAgg([P, D])`, where `D`'s secret is a constant in the code. The coordinator plays `D`, which is at worst a
hostile MuSig2 cosigner, and MuSig2 assumes one already. Both forms go to the external review.

**D5. Tree keys are child Iceberg keys, made by hashing the seeds.** An Iceberg share is a list of seeds, and each one is random and unknown to its subset.
So a member can derive a child share alone:

```
s'[T] = H("dedun/group-wavelength/tree-seed/v1", s[T], ser32(child))     for each subset T the member holds
```

Each child is a complete, independent Iceberg key, with the same holders and the same secrecy argument as `IcebergDkg`'s: a seed stays hidden from its subset if the
original was. Nothing is ever tweaked, so nothing sits outside the module's contract, and no library change is needed since `share_parse` already takes shares from
outside. The group key is unknown until members publish public shares, so each co-signer publishes them for the next window of children beside its nonce batch, and
`Iceberg.groupPublicKey`'s consistency check gives the key. The fork then serves family 45 from this pool instead of an xpub.

This is new cryptography, small but real, and it goes to review with the DKG. The alternatives, and why not:

| option | why not first |
|---|---|
| **Sign with a child by adding the tweak afterwards.** Pass the child key as `group_pk`, so `partial_sign` takes its coefficient, and have the coordinator add `e·a·τ` to the group's partial signature. It would leave family 45 as a plain xpub, with no daemon change | it relies on `group_pk` being used only that way, and it is outside the draft proof, where D5 is a fresh key. **Spike 3 tries it as the fallback** |
| **Change the Iceberg module to take an inner tweak** | C, JNI, cinterop and bitcoin-kmp, in a module that hasn't been reviewed, for what the Kotlin above may do |
| **One constant tree key** | refused by `VerifyVTXOPath`, and by the *one leaf per signing key* rule behind it |
| **A tree key on the operator's device, deleted after the round** | the Ark model for one person, but a member who keeps it can, together with Ark's operator, re-sign the path to their VTXO and take it. A member alone can't, so this is **allowed on regtest and signet only, as a way to reach Phase 8 sooner**, and never with real money |

**D6. The identity key becomes an authentication key, and the money it controls moves off it.** Identity signs often, with nothing a co-signer could inspect, so it goes to
the `Auth` class beside families 21 and 43. Those three private keys derive from a shared **authentication seed**, sent to every participant as the Lightning wallet's node seed
is (its D4), and bound to the statement by the identity public key. Then:

- **vHTLC keys get a new fund family, 203,** served by the group. `out_swap.go`'s `client_vhtlc_pubkey` and its in-swap counterpart stop using identity. Without this, whoever holds identity
  and a payment's preimage can claim that payment for themselves. **Spike 5 asks whether the swap server accepts a vHTLC key that isn't the identity. If it does not, Lightning stays out of this
  wallet, and boarding and rounds ship alone, until Lightning Labs changes it.**
- **Credit is closed** (D10).
- **Indexer proofs for owned scripts use the identity as principal** if the operator's `authorizeScriptScopeQuery` allows it, so scanning does not call a quorum. Spike 1 counts how many there are.

**What any participant then holds:** the means to impersonate the wallet to Ark's operator and the swap server. That is griefing: they can take its mailbox, or drop it from a round. None of those keys
appears in an output script.

**D7. People approve money leaving. Devices approve keeping it.** An outgoing payment is a FROST-signed authorisation under the onchain key, with `t` people through the existing proposal screens, as in the Lightning wallet's Phase
12. Co-signers answer automatically when a request fits an authorisation, and automatically for the things that only keep the group's money the group's: renewing into its own keys, claiming a vHTLC the group issued into its own
script, leaving to its own onchain wallet. **Any other request is refused and becomes a transcript line.**

**D8. The echo rule, the ledger and the pool, as they are.** A co-signer echoes `(label, message hash)` and answers round two only when `n-t+1` have echoed the same hash. The ledger is never pruned. A round has many labels at once, so
echoes and shares go in batches, one event per round per device. **The restored-device rule the Lightning wallet left unbuilt is built here** (Phase 6): a device whose database is older than its share answers nothing until it holds high-water
marks from `t` others. A wallet that signs this much should not depend on nobody restoring a phone, and the rule covers the Lightning co-signer too once it is in the shared core.

**D9. A wallet needs an onchain wallet, and that is where money goes when it leaves unattended.** The statement names `(chain, account)`, as in the Lightning wallet's D2. A cooperative exit, a unilateral exit's sweep and a re-key's leftovers pay an address each co-signer
derives itself. This is the final-wallet rule, and it is why those exits need a quorum and no approval.

**D10. Credit stays at zero.** Credit is a row in the swap server's ledger, keyed by the wallet's identity. Spending it is a request authenticated by that key, with no signature on any output, so **no threshold can guard it.** So this wallet:
- sets `maxCreditSat` to zero and never picks a `CREDIT` or `MIXED` rail. A co-signer refuses a top-up, because it is an OOR transfer the policy doesn't allow.
- advertises no `supports_in_ark_credit`, and refuses to issue an invoice below the operator's floor.
- shows any credit that arrives anyway, by a route not foreseen here, as *Not protected by the group*, and never adds it to the balance or a member's tally.

**D11. Liveness is stated, and the group is told before it matters.** Renewing is automatic and has no approval, but it wants `2t-1` devices at once, inside the free window (on mainnet 648 blocks, about 4.5 days). The earliest deadline is on the wallet screen and in the transcript, with two thresholds:
- **Renewal due and not done:** a transcript line naming who has not answered.
- **Close to expiry:** the operator's device starts a cooperative exit to the onchain wallet, then a unilateral one, and co-signers answer (D9).

The ceremony screen says all this before anybody joins: *this wallet needs three of your four devices online every few days, or the money stops being safe*. That sentence should decide whether a group wants this wallet.

**D12. A cap every co-signer holds to, and gates that stay shut until their conditions are met.** `GroupWavelengthRollout` has `ENABLED`, `NETWORK` and `MAINNET_CAP`. As with the Lightning wallet's channel cap, the cap is checked by co-signers, so a build with the gate lifted cannot get past another member's device: a round or
receive that would take the group's unspent total over it isn't signed. **The personal wallet and the Lightning wallet were both moved to mainnet by decision, ahead of what their plans named.** This plan asks that this one not be. Those were one person's coins behind a released scheme, and a
channel whose close still pays the group. This wallet has a policy written from scratch, and an expiry clock.

**D13. One slot, and the group's wallet has first claim on it while the device is elected.** `IdentityWavelength` becomes a holder of one running daemon with an owner, personal or group. On a device that is operating a group wallet, the personal wallet's row says *This device is running <group>'s wallet*, and opening it pauses the group's wallet for as
long as that screen is up. A separate Android process per daemon would remove the conflict, but it would put a binder between the daemon and the room's database, at 550 MB of PSS, so it is left until somebody needs both at once. The nomination screen says all this, and a device already operating a wallet can't nominate for a second.

**D14. A handover recovers from the chain.** `waved`'s database does not move and nothing like peer storage backs it up. The new operator opens the wallet with the same manifest, `recoverState`, and a window taken from the last snapshot's key indices plus a margin, rather than `wavelength-wallet.md`'s 10 or 100. Since a recovery proves ownership per scan, spike 1 tells us what that costs. What can't be rebuilt, an
exit under way and an in-flight swap, is listed in the old operator's stopped message and on the new operator's screen, and a nomination warns while either exists.

**D15. Deposits and payouts are the existing rules, on new rails.** An invoice and a boarding address go only to a member with a depositor statement, and every deposit lands on their ledger. The only payment the group authorises is a payout to that member's destination, up to their balance. Paid renewal fees and exit costs have no owner under those rules, and the plan does not choose one: see the open questions.

**D16. One commit per phase, each compiling and passing.** Messages end `(Group Wavelength wallet, Phase N)`. Work in the forks lands on their own branch, and the app's submodule pointer follows. Strings go through `strings.xml` in sentence case, *renew* rather than *refresh*, and *VTXO* only on an advanced screen. Every screen follows `CLAUDE.md`. Each new table goes in one migration when the branch merges, as the Lightning wallet's did.

### Who can do what, once every phase is in

| holder | can | cannot |
|---|---|---|
| the operator | run the daemon, make invoices, choose when to join a round, see everything, stall. Let renewal slip, which D11 catches and an election replaces. **Hand out an invoice that pays somebody else, unless spike 6 finds a binding** | sign anything, or get a request past `n-t+1` honest echoes |
| any participant | impersonate the wallet to Ark's operator and the swap server, disrupt its mailbox, spend credit if any exists (D10) | move a VTXO, a boarding output, a vHTLC or the `tr` account |
| `t-1` members | refuse to sign. With few devices online, that is a slow route to expiry (D11) | sign anything, or learn a share, provided the echo rule and the ledger hold |
| a quorum, with `t` people for what leaves | everything the group can do | - |
| a removed member | what they could do before, since Iceberg has no resharing | anything new. Phase 13 moves the money away from them |
| Ark's operator | what any Ark operator can do: refuse a round, see everything, sweep an expired batch | spend a live VTXO, or collude with one member to do so, since neither the owner key nor the tree key is on a device (D4, D5) |
| the swap server | what it can do to any Wavelength wallet, and hold credit | claim a vHTLC without the group |
| a relay | see that the room has traffic | read any of it: every kind here is an MLS inner event |

## Kinds

From **30370**, past the Lightning family, with a test like `GroupLightningEventsTest` asserting the run is unbroken and touches nothing the app dispatches on. All are MLS inner events, and none is ever broadcast. The ceremony reuses 30350 to 30354 with its new `purpose` tag.

| kind | from | carries | phase |
|---|---|---|---|
| 30370 | group (FROST) | wallet statement: chain, wallet, onchain account, Iceberg key, identity key, manifest hash, Ark operator key, swap server, policy | 4 |
| 30371 | group (FROST) | operator statement, with term | 5 |
| 30372 | opener | authentication seed, encrypted to each participant | 4 |
| 30373 | operator | signing request: labels, messages, the context to check them against | 6 |
| 30374 | participant | echo batch: `(label, message hash)` | 6 |
| 30375 | participant | round-one contributions and tree-key public shares, for a window | 6 |
| 30376 | participant | signature share batch | 6 |
| 30377 | participant | a restored device's question, and the high-water marks that answer it | 6 |
| 30378 | operator | snapshot | 9 |
| 30379 | group (FROST) | authorisation: payout, exit, re-key | 11, 12, 13 |
| 30380 | member | deposit request: invoice or boarding address | 10 |
| 30381 | operator | deposit answer | 10 |
| 30382 | old operator | stopped: last indices, exits and swaps under way | 5 |

---

## Phase 0: find out whether it can work

**No product code, and no real money.** Each answer becomes a short section in this note. Spikes 2, 3 and 4 run first, because any of them can stop the plan or change its shape.

1. **The signing census.** Run a funded signet watch-only wallet against a `wavesigner` that logs every request: method, family, index, and the flow it came from. Go through open, recovery, board, renew, OOR send, Lightning receive, Lightning send, cooperative exit, unilateral exit, and an idle hour. **This produces the list Phase 7 has to cover, and shows how many sessions each flow costs a group.** Anything not in the table above changes this plan. Also record every identity use, to confirm D6.
2. **A plain signature from Iceberg.** In `jvmTest`, a 2-of-4 group signs a tapscript and a BIP86 key path under `KeyAgg([P])` with an outer BIP32 tweak, and bitcoin's interpreter checks both spends, with negative controls like `IcebergSwapInSpikeJvmTest`'s. Is there a representation of *no cosigners* the library takes? If not, the same with the public cosigner. **If neither works, D4 falls back to FROST for plain signatures,** which means two keys, and a human-approved scheme made automatic. That would need its own plan.
3. **A child key in the tree.** `s'[T]` as in D5 for 2-of-4 and 3-of-7: does the share parse, do the public shares pass `groupPublicKey`, and does a signature verify beside a stock MuSig2 cosigner with a taproot tweak, as `NewSignerSession` uses? Then try D5's fallback the same way. **If neither works, the tree key is the one key a single member holds, in a wallet that says so, and the owner decides whether that wallet is still wanted.**
4. **The operator's deadlines. This can stop the plan.** A signer that sleeps before answering, on a funded signet wallet. Mainnet's terms differ from signet's (`wavelength-wallet.md`, spike 1), so the number is read again at Phase 14. How long does Ark's operator wait for nonces, for tree signatures, for forfeits? Does a slow client get dropped from the round, or fail it for everyone? Then measure the other side: **what does a group take to answer one round, for one VTXO and for five,** over real relays, on phones rather than emulators on one machine? The Lightning wallet's regtest run got 0.4 to 0.9 seconds per signature in the second case. If the deadline is shorter than a phone quorum, only always-on co-signers can use this wallet, and the ceremony screen has to say that or the plan stops.
5. **A vHTLC key that isn't the identity.** Build the daemon with `client_vhtlc_pubkey` from a separate key. On signet, receive and send over Lightning. What does `RequestChannelId`, the per-swap mailbox and the indexer's authorisation make of it? This decides D6's first bullet.
6. **An invoice a member can check.** On the Lightning wallet a member refuses an invoice whose payee isn't the statement's node. Here, the payee is a per-payment receive-auth key, and the route hint names a virtual short channel id *"derived from the client key and payment hash"*. Can a member recompute that? If so, it binds the invoice to the group's vHTLC key. If it involves a server secret, a hostile operator can hand a depositor an invoice that pays itself, the wallet's one way round the threshold, and the fix is either a change at Lightning Labs or receiving through boarding only.
7. **A local operator.** Ask Lightning Labs, and look for anything in the harness, for an Ark operator that can run on regtest. With one, `testing/regtest/wavelength/` gets the same run the Lightning wallet has. Without one, every end-to-end check needs signet coins by hand, so someone has to send some, and a stand-in operator in Kotlin for tests gets no further than the policy's fixtures.
8. **Renewal with a quorum.** Scheduled rounds have a cutoff: is `cutoff_unix` early enough to tell co-signers, and does the zero-fee quote hold through a slower join? This overlaps with the personal wallet's spike 5, which still needs a coin.

**Done when** this note carries the answers, D4, D5 and D6 are confirmed or amended, and the owner has read spike 4's numbers and said go.

## Phase 1: what this stands on

**Owed by [wavelength-wallet.md](./wavelength-wallet.md), and required here.**

- **A signet wallet has held coins:** received, sent, renewed and exited, on the personal wallet. A group wallet can't be the first thing to prove those flows. This answers that note's open question 2, and its spikes 5 and 6.
- **The fork is rebased and released** (its Phase 1). It is 109 ahead and 50 behind, and Phase 3 here adds to the side that has to be carried. If upstream takes the descriptor work, the signer callback goes to upstream too, as a proposal.
- **The SDK's licence question** is answered, since this wallet ships the same code.

**Done when** the submodule pins a real release and a person has seen coins move.

## Phase 2: the keys, pure

`composeApp/…/wallet/wavelength/group/`, and nothing else:

- `GroupArkDerivation`: D4's slots, with a golden vector pinned, frozen once a release holds money.
- `GroupArkManifest`: the manifest and its hash, byte for byte what the daemon's `walletdesc` parser takes and hashes. A Go test writes the fixture, and a Kotlin test reads it.
- `IcebergChildKeys`: D5, with its tag and vector.
- `GroupArkSigner`: rounds one and two for a plain signature and a tree signature, as pure functions over a share, a label and a keyagg cache.

**Tests,** in `commonTest`: signatures from every `(2t-1)` subset at 2-of-4 and 3-of-7, through the interpreter. Every family's child against the Go derivation. A test that fails if a child's tweak ever reaches a share. Negatives: a share from another key, a reused label refused by the wrapper rather than the library.

**Done when** `jvmTest` passes, and nothing outside this package changed.

## Phase 3: the daemon, in the fork

In `machankura/wavelength`, then `wavelength-kmp`. **All of it behind one config: a wallet without a callback signer behaves byte for byte as today.**

1. **`callbacksigner`** (D3): a gomobile interface with one method per signer call, carrying the JSON the facade already uses for its envelopes. `RequestTimeout` comes from the group's budget (spike 4) rather than the 10-second default, and a timed-out request burns its labels.
2. **Pre-provisioned family 45** (D5): `guardedKeyRing.DeriveNextKey` asks the signer for the next provisioned key, and `MuSig2CreateSession` passes it the locator. A pool that has run out is a clean failure, before the round is joined, and leaves nothing reserved.
3. **No probe with a callback signer.** The app holds the keys' public halves already, and a quorum to open a wallet is not acceptable. The identity pin stays. The manifest hash covers what the probe was for.
4. **The identity split** (D6), and family 203 in `keyfamily.Registry`, in the minimal bundle's list and the manifest's, and in the recovery scan.
5. **Credit closed** (D10) by a config field, not just left unused by the app.
6. **A signing context.** `MuSig2Sign` carries a digest, and `SignOutputRaw` has no context parameter to widen. So each call site registers what it is about to sign, by digest, in a small registry the callback signer reads: `Register(sighash, kind, material)`. The material is what a co-signer needs to rebuild the digest: for a tree node its path and the batch prevout, for a forfeit its connector and commitment, for an OOR transfer the Ark transaction and checkpoints. **It is a hint from an untrusted device, and a co-signer rebuilds everything from it.** The sites are `round/signing_executor.go`, `vtxo/transitions.go:596`, `waved/rpc_server.go:4071`, `oor/ark_sign.go`, `oor/checkpoint_sign.go`, `round/join_auth.go`, `lib/arkscript/spend_helpers.go` and `vhtlcrecovery/unrollpolicy`, and spike 1 says whether that is all of them. A signature with no registered context fails, so a site that was missed shows up in a test rather than being signed blind.
7. **Fixtures.** A Go test writes each kind's context, digest and expected verdict as JSON, for Phase 7's Kotlin to read. They are what keeps the two implementations from drifting, and every daemon bump regenerates them.

In the SDK: `RuntimeConfig`, redaction, `WalletKind`, and `startWatchOnlyWallet` with the callback.

**Tests:** the daemon's suite on its release tags, a regtest or signet watch-only wallet against a Go test double of the callback through every flow in the census, and `:wavelength-embedded:connectedAndroidDeviceTest` with a Kotlin signer holding a plain key.

**Done when** an emulator's watch-only wallet boards and sends on signet with a Kotlin signer that holds an ordinary key: everything but the threshold.

## Phase 4: the ceremony and the statement

- `IcebergKeySession.purpose` (D1), defaulting existing rows to `LIGHTNING`, and `unusedKey` reading it. `IcebergRitualManager.proposeCeremony` takes it, and the transcript words a line by it.
- `GroupWavelengthWalletManager`, shaped like `GroupLightningWalletManager`, and `GroupWavelengthWalletStatementEvent`, kind 30370, signed through `proposeSigning`, refused by `BroadcastGroupSignedEventScreen`, with a `summarize` line. A reader accepts it only if: the room signed it, the onchain statement it names verifies, the Iceberg key matches the reader's own session, the manifest recomputes to the hash, and the authentication seed reproduces the identity. A non-participant checks everything but the last two.
- **The operator key and the terms are in the statement,** so a change at Ark's operator needs the group to agree, rather than the daemon to quietly follow it.
- **The screen opening it** says what D11 says, names the cap and that the scheme is unreviewed, and states why a size is refused, as the Lightning ceremony does. Entry is a row on `ChatRoomDetailScreen`, below the Lightning wallet's.

**Tests:** a four-device JVM run gives every device the same `P`, the same manifest hash and the same identity. A Lightning statement can't claim a Wavelength ceremony, nor the reverse. A device that lacks the seed refuses the statement. A device on an old build ignores the kinds.

**Done when** four devices hold a verified statement, and the audit is unchanged, with `adaptive APIs` still at its floor or above.

## Phase 5: the operator

- `GroupWavelengthOperatorManager`: nominate yourself, FROST-signed, highest term wins, ties to the lower event id, stepping down, and `mayRunDaemon` with the start gate from the newest saved event.
- **The holder** (D13). `IdentityWavelength` takes an owner, and one daemon runs at a time. A foreground service holds it on Android, since a daemon stopped on background (`BackgroundRestartPolicy`) can't answer a round. That service is what the Lightning wallet left unbuilt, and it can now host both. The worker in `WavelengthMaintenanceWorker` gives way to it.
- **The open.** `startWatchOnlyWallet` with the manifest and the auth keys, the password derived from the authentication seed since a watch-only wallet has no secret to derive one from, in `<noBackupFilesDir>/wavelength/group/<wallet>/`. `info.manifestHash` is checked, and a mismatch is a hard stop.
- **Handover** (D14): the old device stops and sends 30382. The new one recovers.
- A signer that refuses everything for now, so this phase moves nothing.

**Tests:** an election, two nominations for one term, a stale device kept out by the gate, a switch between personal and group ownership of the slot with a fake client. By hand on signet: an emulator opens the wallet, and shows a boarding address that a second member's device computes for itself and agrees with.

**Done when** a handover between two emulators shows the same empty wallet, and only one daemon ever runs.

## Phase 6: the co-signer, with no rules yet

**First, extract.** `GroupLightningCoSigner` and `GroupIcebergTransport` hold the pool, the echo, share filing and aggregation, mixed in with Lightning's checks. Lift the first set into `IcebergCoSigningCore`, parameterised by kinds, a label domain and a check. Lightning's behaviour doesn't change, and its suite and regtest run prove that, in a commit on its own. **Its labels don't change either: a wallet in use holds them in its ledger.**

Then `GroupArkCoSigner` and `GroupArkTransport` on top, with their own domain, `dedun/group-wavelength/label/v1`:

- **The pool** gets larger: a round wants a nonce per tree node on each VTXO's path, one per forfeit, and one per join proof, so the window comes from the census rather than Lightning's. Tree-key public shares ride in the same batch.
- **Batches** (D8): one request, one echo event, one share event per round, or a five-VTXO round costs dozens of Marmot messages inside a deadline.
- **The bridge**: the daemon's callback blocks on the transport, with the round-one answer served from the pool at once (constraint 9).
- **Restored devices** (D8), for this wallet and, through the core, Lightning's.
- **A co-signer process that outlives the screen.** Like `GroupTransactionWatcher`, it runs whether or not anybody opens the wallet. On Android, whether it is awake in the background is the same question Lightning has, and this wallet's deadline makes it matter more. Desktop gets the sentence: *leave this open*.

The check for now refuses everything except a fixture kind. **Tests,** in the four-device harness: an operator sending two messages under a label gets no share from anyone and raises the alarm in the transcript. A request from an old term is refused. A device offline for round one signs round two. One restored database answers nothing until it has its marks. The core's tests run for both wallets.

**Done when** a Kotlin signer on one emulator gets a fixture signed by a 2-of-4 group across four devices, and Lightning's suite and regtest run are unchanged.

## Phase 7: the policy

**This phase holds the security argument. It is written as pure functions, with no database and no network, so it can be read and reviewed alone.** `ArkPolicy.check(state, request): Verdict`, in `commonMain`, on bitcoin-kmp. It never takes the operator's word: it rebuilds each digest from the material, and an unknown kind is a refusal.

| what is asked | a co-signer checks | approval |
|---|---|---|
| **join proof** (`round/join_auth.go`) | input 0 spends the challenge output it recomputes from the join message, so this transaction can never be valid. The other inputs are the group's coins | automatic |
| **tree signature**, family 45 | the digest is this node's, on a path it rebuilds from the batch output to the leaf (`ValidatePath`'s rules: one leaf per key, operator present, the amount as requested). The leaf is a standard VTXO whose owner is a child of the group's slot, with the operator and exit delay the statement names. The sweep root is as expected. The round as a whole passes the two rows below | as for the round |
| **forfeit** | spends the VTXO's collab leaf and a connector from the commitment transaction of a round this device has validated. The value it gives up reappears in that round as the group's own new leaves, or authorised payout outputs, less a fee under the statement's cap. No VTXO is forfeited into two live rounds | automatic if it all returns to the group's keys, otherwise an authorisation |
| **boarding input** | the same, for a boarding output, in a commitment transaction that pays the group's new leaf. The deposit is attributed by the boarding index (Phase 10) | automatic |
| **OOR transfer**, Ark transaction and checkpoints | the inputs are the group's. Every output is a vHTLC under an authorised payment hash, within budget, with the leaves `NewVHTLCPolicy` builds, or change to a fresh child of the group's slot | an authorisation |
| **vHTLC claim** | leaf 1 of a vHTLC whose hash the group issued in a deposit answer, to the group's own receive script | automatic |
| **vHTLC refund**, leaves 2, 3, 5 and 6 | the sender key is the group's, the destination is the group's | automatic |
| **forfeit of a funded vHTLC** on refresh | what `swap_system.md` §3 says the receive session checks: payment hash, outpoint, amount, script, policy template | automatic |
| **exit sweep**, the CSV leaf | pays an onchain wallet address the co-signer derives (D9) | automatic |
| **`tr` account spend** | every output returns to the account, or funds a boarding script of the group's, or an exit's fee, under a fee cap | automatic within that, otherwise refused |
| **anything else, or a context that doesn't rebuild the digest** | refused, with a transcript line naming what and why | never |

Also held by every co-signer:
- **The cap** (D12), against the unspent total on its own ledger.
- **A control block binds to its pkScript** (`arkscript_spec.md` §9.2): without this a caller obtains a signature over an arbitrary tapscript.
- **Standard shapes only,** as `ValidateStandardVTXOPolicy` requires, and an operator-only leaf is refused (§9.3). A custom policy has no business in a group wallet, whatever it would allow.
- **The ledger**: for each VTXO it knows of, its state, from what it has itself signed. That is what lets it refuse a double forfeit, and it is also how a member checks a snapshot.

**Tests.** The Go fixtures from Phase 3, each with its verdict, on both implementations. A refusal test for every clause in the table above, each changing one thing about a valid request, in the style of the Lightning co-signer's numbered checks: a leaf paying a key one step off, a forfeit whose connector is from another commitment, an authorised hash with a changed amount, a fee one sat over. Property tests over randomly mutated contexts: nothing mutated ever passes, unless the digest it produces is one the unmutated context would pass. **And a second person writes an adversarial test list before reading the implementation.**

**Done when** every kind in the census has a row, every row has a refusal test, and the review of this phase's code has happened (`/security-review`, then someone who did not write it).

## Phase 8: the first real round

The plumbing meets the policy, **on signet, with one person's coins.**

- Board one VTXO under a 2-of-4 group on four devices, two of them phones if possible. Renew it. Record every stage's time beside spike 4's deadline.
- Lift the operator's start gate and `GroupArkTransport` to whatever this shows: retries, the abandonment of a failed round, the pool refilling.
- What failure looks like: a device off during a nonce, during signatures, during forfeits, and the operator's device dying after `InputSigSentState`.

**Done when** a threshold-held VTXO exists on signet, was renewed by the group with nobody pressing anything, and this note carries the timings. **If it can't reliably be done on phones, this is the place to stop or to change what the ceremony screen says.**

## Phase 9: what the group sees

- **The snapshot,** kind 30378: balance, VTXOs with expiry, boarding coins, activity, indices, and any credit, as the operator's claim. Each member compares it with its own ledger and shows *matches what you signed* or *differs from what you signed*, as the Lightning wallet does with commitments.
- **`GroupWavelengthWalletRoute`**: pushed, `NavigateBackButton`, four states through `ScreenStateTransition`, `EmptyState` with its own message, `readableContent()`, two panes from `Breakpoint.Expanded`, and every outcome through `rememberNotifier`. It has the balance, the operator and when last seen, the earliest deadline in time, the cap, who is online for signing, and the activity. *Run this wallet on my device* lives here, with D13's sentence.
- **The transcript,** per [group-lightning-transcript.md](./group-lightning-transcript.md): protocol kinds never appear as raw JSON. Elections, refusals, equivocation, a renewal, a missed renewal and an approaching deadline each get a line.

**Tests:** compose tests for each state at phone width and 1280 dp, and a mismatch shown when a snapshot lies. `./gradlew :composeApp:m3Audit`.

**Done when** all four members see the signet wallet on their screens, and agree on it.

## Phase 10: deposits

- **An invoice** only for a member with a depositor statement: request, answer, and the requester's own checks, with spike 6's binding if there is one. The hash is filed so the co-signers' claim rule recognises it. Below the floor is refused (D10). The cap counts what is pending.
- **A boarding address**: the operator allocates an index, and the member recomputes the script from the statement's slot and the operator key, and refuses one that differs. Index and member are recorded on every device, so a boarding is attributed before it is shown. Boarding costs and confirmations are stated.
- **Into `MemberBalances`,** as a third rail beside the two it knows. A deposit nobody can be matched to is *unattributed*, as elsewhere, until the quorum says whose it is.
- **Receiving needs a quorum.** A claim is a signature, and until it happens the payer's Lightning payment is held. If `2t-1` can't answer, the payment fails on the payer's side. The receive screen says so, and states who is online.

**Tests:** view model paths, an invoice with the wrong binding refused, a boarding script off by one refused, a deposit landing on the right ledger on all devices. On signet: both rails, end to end.

**Done when** a member's deposit by Lightning and onchain lands on their tally on every device.

## Phase 11: payouts

- `GroupWavelengthAuthorisationEvent`, kind 30379, `payout`, following `PayoutRule` and `GroupLightningPayouts`: to the member's own destination, up to their balance, with an expiry and fee cap. Any member can propose, and `t` approve.
- **Over Lightning:** the operator pays each once per hash. Co-signers hold the OOR transfer to the budget. As with the Lightning wallet, a Lightning payee is still the proposer's word; the payouts note says why.
- **Onchain:** a leave output in a round, or the cooperative path, whichever the census shows leaves least behind, to the member's onchain destination, which a co-signer can name exactly.
- **Failed and expired payments:** the refund leaves come back automatically, and the budget is spent only once.

**Tests:** an HTLC to an unauthorised hash, over budget, to a member over their balance, after expiry. Two authorisations for one payment. On signet: a payout over each rail.

**Done when** a member has been paid out on signet, and their tally went to zero on every device.

## Phase 12: staying alive and getting out

**This phase comes before any mainnet money.** A group wallet with no tested way out does not get a mainnet flip.

- **Renewal:** the operator's daemon does it, the co-signers answer, the deadline and the two thresholds (D11) are live, and the worker's Android schedule holds for a group wallet.
- **Cooperative exit:** to the onchain wallet, automatic at the threshold, or by anyone's proposal.
- **Unilateral exit:** `getExitPlan`, the fee from the `tr` account, and the sweep to the onchain wallet. Exit state isn't recoverable, so 30382 and the screen say so. What it costs comes out of whom is an open question.
- **An operator nobody can reach:** another member is elected, recovers, and carries on with any of the above. **An operator that lies about expiry:** co-signers have their own ledger's expiry and chain tip, and raise the line themselves, and any member can then nominate.
- **Ark's operator gone:** blocked at the network level on signet, the group exits unilaterally through to a claim.
- A second run of `testing/regtest/` if spike 7 found an operator.

**Tests:** threshold mapping across tip heights, each stop in an exit. On signet: both exits, with nothing left unaccounted for, plus a coin deliberately left near expiry with the group's devices off and then back on.

**Done when** a signet group has left both ways, and the money reached the onchain wallet and is spendable by FROST.

## Phase 13: members change

Iceberg has no resharing, and the child keys in D5 inherit that. As in the Lightning wallet's Phase 14:

1. A new ceremony over the new membership, with its own manifest and a new authentication seed, since the removed member holds the old one.
2. A new wallet statement, with the next `wallet` number.
3. A `re-key` authorisation: the old quorum moves everything into the new wallet's keys, through a round. The policy gains that one row, for that one statement. Whatever can't move leaves to the onchain wallet (D9).
4. The old wallet keeps its operator until it is empty.

The removal flow says what a removal leaves. **A removed member keeps a share and the identity until the move is done.** The sentence says so, because the group should move the money soon. A group that can't gather the old quorum cannot move it: that is the threshold doing its job, and the D11 thresholds apply to the old wallet.

**Tests:** a five-device run, as Lightning's Phase 14, re-keying after a removal.

**Done when** a removal on signet ends with the money under the new key and the old wallet empty.

## Phase 14: soak, review and rollout

| gate | lifts when |
|---|---|
| `GroupWavelengthRollout.ENABLED`, off | Phases 0 to 13 are in, the JVM suite and audit pass, and a signet group has done Phases 8 to 13 by hand |
| signet only, mainnet refused in code | a signet group has run for a month with real devices, with no refused label, no missed renewal it wasn't warned of and no lost coin, **and** somebody outside the project has reviewed the Iceberg module, the DKG, D4's single-participant signing, D5's child keys and Phase 7's policy |
| `MAINNET_CAP` | small, and held by co-signers (D12). It also can't exceed what the mainnet operator allows: 300,000 sats per wallet and 50,000 per VTXO on the terms it gave in November 2026, re-read at the time. Lifts on the shape `GroupWalletRollout` uses |

Also before the second gate:
- **A real phone in each co-signer role,** which no wallet in this app has yet had.
- **A secrets check:** every flow's logs on all devices searched for shares, seeds and `prv`. The authentication seed and child seeds have redacting `toString`s, tested.
- **An upgrade test:** a wallet and a ledger from the previous build, as every daemon bump now also regenerates the Phase 3 fixtures.
- **Backup:** the daemon's directory is outside `allowBackup`, and a restored database triggers the rule from D8.
- **The final check:**

```bash
git submodule update --init --recursive
```

```bash
experimental/wavelength-kmp/scripts/fetch-bindings.sh android
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest :composeApp:m3Audit
```

```bash
./gradlew :composeApp:connectedDebugAndroidTest
```

Phase 6 touches Lightning's co-signer, so the Lightning regtest run is part of this check too:

```bash
testing/regtest/regtest.sh up
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

**Rollback.** Until the second gate lifts, every phase is an ordinary revert. Afterward someone may hold money, so following `wavelength-wallet.md`'s D5: a closed gate stops new wallets, and a wallet that exists always opens and co-signs. A wind-down keeps renewal running and offers the exit on the front screen, for longer than one coin's lifetime. **A build that stops co-signing is a device missing from the quorum, so this wind-down needs enough of the group to update, and that is part of the wind-down's message.**

---

## Order, and what can run in parallel

| phase | depends on | lands in | rough size |
|---|---|---|---|
| 0 spikes | - | nowhere | 2 weeks, and spike 7 waits on Lightning Labs |
| 1 foundations | - | the fork, the SDK, a person with coins | owed by the personal wallet's plan |
| 2 keys | 0 | app | 1 week |
| 3 daemon | 0, 1 | fork, SDK | 3 weeks |
| 4 ceremony, statement | 2 | app | 1 week |
| 5 operator | 3, 4 | app | 1.5 weeks |
| 6 co-signer | 2, 4 | app, touching Lightning | 2 weeks |
| 7 policy | 0, 3's fixtures | app | 3 weeks, plus review |
| 8 first round | 5, 6, 7 | app, and signet | 1 week |
| 9 what the group sees | 8 | app | 1 week |
| 10 deposits | 8, 9 | app | 1 week |
| 11 payouts | 10 | app | 1 week |
| 12 alive and out | 8 | app | 1.5 weeks |
| 13 members change | 12 | app | 1 week |
| 14 rollout | all | app | a month's soak, and an outside review of unknown length |

**About five months for one engineer before the soak.** That guess comes from the Lightning wallet's own estimate, which had a smaller unknown at its centre. Phases 2 and 3 can run side by side, and so can 5, 6 and 7 once they have their inputs. The first milestone a person can see is the end of Phase 8: a VTXO on signet nobody holds alone, renewed with no one pressing anything.

## Risks

| risk | why it matters | what to do |
|---|---|---|
| the operator's round deadline is shorter than a quorum | renewal fails, and a coin expires | spike 4, before any code. Always-on co-signers, stated on the ceremony screen. Failing that, stop |
| the policy misses a shape | a signature that moves money with no authorisation, which is the whole point lost | a refused default, a census with a test per shape, fixtures from Go, an adversarial test list, a review |
| the daemon changes shape under the policy | upstream says its RPC and on-disk schema may change between minor versions, and a new transaction shape is refused by every co-signer at once | pinned fork, fixtures regenerated at every bump, and refusals surfaced in the transcript, so a mismatch is loud and not silently wrong |
| carrying a bigger fork | it is already 109 ahead and 50 behind, for a security-critical daemon with no release | everything behind one config, and one seam. Propose the callback and the identity split upstream |
| D4 or D5's cryptography is wrong | a share or key leaks | spikes 2 and 3, then an outside review before mainnet. They use nothing the library doesn't already expose |
| label reuse | a share leaks, with no error | the echo rule, the never-pruned ledger, burned labels on timeout, and the restored-device rule, now built |
| a hostile operator hands out its own invoices | a deposit goes to the wrong place, with the threshold never asked | spike 6, and if there is no binding, boarding only, with Lightning receive held back |
| co-signers asleep on phones | missed rounds, missed claims, and in the end D11 | foreground service, desktop as a co-signer, the deadline shown. It is a limit of the phone rather than this design, and iOS will be worse |
| small groups can't have this | `n ≥ 3t-2` rules out anything below four share holders | stated on the ceremony screen, as Lightning does |

## Open questions, for the owner

1. **Does the daemon stay on a fork?** This plan is the case for owning a daemon fork for a long time, or for asking Lightning Labs to take a callback signer, the identity split and an accessible operator for regtest. Talking to them early changes spikes 5 and 7, and the risks above. **Recommended: ask now, before Phase 3.**
2. **Is D5's fallback ever acceptable?** That is, a tree key on the operator's device, so a member and Ark's operator together could take the group's VTXOs. It would save a good part of Phase 3 and all of `IcebergChildKeys`. This plan says no with real money, because of what was asked. The owner may see Lightning Labs as a different risk.
3. **Who pays renewal and exit costs under payouts-only?** Zero-fee renewal has no cost, but a paid refresh, a unilateral exit's fees and the `tr` reserve all spend money with no owner. Pro rata by balance is the likely answer, and it needs writing into `MemberBalances`. Phase 10 can't finish without it.
4. **Does a group really want both Lightning and Ark?** Both have an operator, a quorum and a separate key. The wallet here is cheaper to fund, needs no channel, and has an expiry clock where Lightning has a closing transaction. If the answer is *only if it replaces one*, then Phase 13's re-key and Phase 10's rails look different, and the Lightning wallet's mainnet groups need a way across.
5. **Who runs the Esplora,** which the personal wallet also owes, and which here also has an operator's onchain view behind it?
6. **Can this ship with the scheme unreviewed?** `iceberg.md` says not to. Phase 14 asks for a review. The Lightning wallet went ahead without one, by decision. This note's D12 gives the reasons for treating this wallet differently, but it is the owner's call.

## What this plan does not do

- **A group wallet with less than a quorum online for receipts.** Pre-signed claims would need a change in the swap protocol.
- **Desktop or iOS as an operator** (`wavelength-wallet.md`, D4). Both can co-sign.
- **A second daemon in a process,** or a daemon in a process of its own (D13).
- **Custom VTXO policies, assets, or guardians' leaves in an Ark script.** The standard shapes only, for now. A guardian's taproot leaf would need Ark's operator to accept the policy, and it's worth its own note.
- **Credit,** on purpose (D10).
- **Replacing the personal wallet, or any change to it** beyond the holder's owner (D13).

## Appendix: what was considered and rejected

**Trusting the signer to be honest because it takes a quorum.** That is, a threshold signer that signs what the operator sends, on the grounds that the operator is one member and the group elected them. It's what the headline's first point rules out: it would be a single member moving funds, with the others' devices as a rubber stamp.

**Each member approving every signature by hand.** A single renewal is several signatures per VTXO, on a deadline. The Lightning wallet's constraint 4 applies here more strongly.

**Running a daemon on every member's device.** It would give each co-signer its own view of Ark, and is the check that would most reduce Phase 7's reliance on an operator's material. But constraint 13 forbids two daemons with one identity, and it would put the daemon's memory on every phone (the app's PSS with a wallet open was 550 MB on an emulator). A co-signer's ledger gets most of what it needs. A view-only indexer client that shares identity might be a later addition.

**The group's onchain FROST key for plain signatures.** It needs no second ceremony for those families, and the tweaks are built. But it can't do the tree, so the wallet would need two keys and two liveness conditions, and FROST's automation here would be new work. Spike 2 keeps it as the fallback.

**Fresh keys for every family from D5.** It would remove D4's derivation and single-participant signing. But each owner key would take a round of published shares, and recovery would need a quorum to list a wallet's keys, where an xpub lets any device do it.

**A new Iceberg ceremony per round, for the tree key.** Correct, and far too slow.
