# dedun docs

Notes on the parts of this app whose behaviour is not recoverable by reading the
code alone — where the reasoning lives in a protocol, a failure mode that is
silent, or a decision that looked arbitrary and was not.

| document | covers |
|---|---|
| [shared-key-ceremony.md](./shared-key-ceremony.md) | ChillDKG over NIP-17: the rounds, the approval gates, the chat transcript, participant ordering |
| [shared-key-derivation.md](./shared-key-derivation.md) | deriving further keys from the group's threshold key with FROST tweaks — why not BIP32, why no chain code, and the one rule that must not be broken |
| [subgroups.md](./subgroups.md) | a group making another group — the four ceremonies, what the parent's signature actually covers, and why the child's key is fresh rather than derived |
| [automatic-key-ceremony.md](./automatic-key-ceremony.md) | joining is the only approval: a phased plan, built but for its device session, that makes a key ceremony run by itself after a member joins. Covers why contributing and confirming were never decisions a person could add to, the resume sweep that replaces the tap, the coordinator's follow-up steps and the one key state a device can sign without asking, and why abandon stays |
| [ceremony-creation.md](./ceremony-creation.md) | one place to start a key ceremony — how joining a subgroup's ceremony opened a second one for the parent room, the screen that showed a ceremony and offered to start one in the same button, and the removal of the nip30303 documents, whose kinds collided with the ceremony's, leaving the member chronicle with nothing to carry and nothing to say |
| [frost-batch-signing.md](./frost-batch-signing.md) | signing several events in one ceremony — why one nonce can never cover two messages, and the phased schema, wire and UI work that follows from it |
| [group-chain-wallet.md](./group-chain-wallet.md) | an on-chain wallet the group's threshold key holds, modelled on Phoenix's final wallet — why the path is BIP86's unhardened tail under a public account step, why each input is a batch item with its own cache, and the seven checks a member makes before signing a spend |
| [group-send-an-amount.md](./group-send-an-amount.md) | sending an amount in sats from the group's wallet, with change — why the gate as written could never lift and is removed in favour of the mainnet cap, the decimal point the gated form multiplies by ten, and change addresses reserved like coins |
| [group-wallet-guardians.md](./group-wallet-guardians.md) | letting an ancestor group spend from a subgroup's wallet through a taproot leaf — why the parent signs the guardian keys into the child's certificate, why every inherited key is derived one step further per child, and why every ancestor waits 12 blocks |
| [subgroup-wallet-policy.md](./subgroup-wallet-policy.md) | the parent deciding, at a subgroup's birth, whether its wallet is guarded or unguarded — why the choice is signed three times, why *unguarded* means no keys in the certificate, why a guarded group's subgroups are all guarded, and why the rule is every honest client's rather than the chain's |
| [member-wallet-delay.md](./member-wallet-delay.md) | a group deciding whether it guards members' wallets, at what delay, and closing again -- why a group-signed event of its own rather than the birth certificate, why no event means not offered, why the request carries the policy it was built under, and why closing never releases a wallet already guarded |
| [guardian-delay.md](./guardian-delay.md) | a parent choosing how long a coin waits before a guardian can take it, from 6 blocks to BIP68's 65,535 -- why each ancestor keeps the delay it chose as its key goes down the tree, what a long delay gives the child, and how a number of blocks is said in time |
| [member-guarded-wallets.md](./member-guarded-wallets.md) | a guarded wallet a group keeps for one member — why the member makes the key and the leaves before asking, why the group signs a certificate of its own kind rather than a birth certificate, and why a member's wallet shows as `@name` and opens their profile |
| [guarded-wallet-forwarding.md](./guarded-wallet-forwarding.md) | telling the groups above about a guarded wallet without anybody pressing *Share* — why it goes upward only, why one device sends at once and the rest catch up, and the two bugs in the button it fixes on the way |
| [member-wallet-guarding-group.md](./member-wallet-guarding-group.md) | naming the group that directly guards each member wallet in an ancestor's list, and gathering a person's wallets under them -- why the direct guardian is the certificate's signer, why the founding name comes before the live one, and why the forwarding notice carries the guardian's birth certificate as a tag |
| [guarded-wallet-funding.md](./guarded-wallet-funding.md) | a group paying into a descendant's guarded wallet — why the address is the first unreserved one from `current + 2`, why the claim rides in the PSBT output's derivation and every signer rederives it, and how the guarded wallet row makes room for *Fund* beside *Spend* |
| [group-wallet-addresses.md](./group-wallet-addresses.md) | listing the group wallet's receive addresses as Phoenix lists its swap-in ones — why the list stops at the current address plus two, why a paid address past that still shows, and why a used address can be copied but never shown as a QR code |
| [frost-session-resend.md](./frost-session-resend.md) | asking the members who never got a signing proposal to look again — why a proposer can repeat only what it wrote, why a member's availability signal needs no repeating, and the one half that has to come from the silent member's own device |
| [frost-share-refresh.md](./frost-share-refresh.md) | giving every member a new share of the same group key, phased and built behind a flag that is off in release — why enrollment cannot rotate a share, the zero-sum refresh that can, what a rotation protects against and the two things it never can, and re-enrollment as the repair that brings back the members a refresh left out |
| [rotate-shares-single-approval.md](./rotate-shares-single-approval.md) | taking part is the only approval: a phased plan, built but for its device session, that makes a share rotation run by itself after a member agrees to take part. Covers the three asks a recipient gets today, why storing the new share was never a decision, the one key state for a new generation a device can sign without asking, and the abandon a member loses once nothing is left to approve |
| [marmot-membership.md](./marmot-membership.md) | how members join an MLS group, and the epoch race that makes a missing member look like a successful invite |
| [member-chronicle.md](./member-chronicle.md) | handing a member added after the work was done the group's signed record — why the events are not on the wire at all, and why the room's id is enough to verify them |
| [marmot-direct-messages.md](./marmot-direct-messages.md) | a one-to-one message inside a group as a stock NIP-59 gift wrap — what its MIP-03 carve-out costs, why the sender cannot read their own, and the one query that would broadcast it |
| [mls-skipped-keys.md](./mls-skipped-keys.md) | why a group event that arrives a moment late was dropped for good, which flows triggered it, the fix as it landed in mls-kmp, and the one word of the diagnosis that was wrong |
| [long-running-sync.md](./long-running-sync.md) | the chat subscriptions that stay open instead of pulling once per screen — why the request queue could not simply hold one, and how the group filter follows the room list |
| [dead-code.md](./dead-code.md) | code in the sync and relay stack that nothing calls, why each piece is still there, and which of it is a bug rather than a leftover |
| [nsec-sign-in.md](./nsec-sign-in.md) | signing in with an existing nostr key — why an nsec can never have a wallet behind it, the ten call sites that make it small, and the sign-in machine that was already built and unreachable |
| [standalone-electrum.md](./standalone-electrum.md) | a profile with no node watching the chain — why only a nodeless profile gets its own client, why it connects at start rather than on first look, and why Tor on still means no clearnet fallback |
| [npub-sign-in.md](./npub-sign-in.md) | signing in with only a public key — what a key that cannot sign can still see here, why that is a preview rather than a browser, and the four decisions the word settles |
| [multiple-profiles.md](./multiple-profiles.md) | several profiles on one device — why a profile is a credential and a seed is a wallet attached to one, why a switch is a restart rather than a swap, the two entrances the app lacks, and the inbox a switch would silently lose |
| [jvm-target.md](./jvm-target.md) | what desktop support cost, phased — why the native chain was already done, why an empty source set in our phoenix fork was the real blocker, and why DAO tests need none of it |
| [npub-profile-preview.md](./npub-profile-preview.md) | showing the person before a direct message is started from a pasted npub — why the preview is a screen keyed by public key, what "found" means when a row can be a placeholder, and the phase that makes the button honest about a key package that never comes |
| [material-design-conformance.md](./material-design-conformance.md) | what the M3 foundations actually require, measured against all 43 screens — the colour pairing that renders the app's own proposals invisible, and eight phases that put the decisions back in the theme |
| [group-lightning-wallet.md](./group-lightning-wallet.md) | a Lightning wallet whose channels the group holds with an Iceberg threshold key, run by one elected operator. Covers why Iceberg needs its own ceremony and a DKG of its own, why every payment needs `2t-1` devices to answer, the echo rule that keeps a hostile operator from reusing a nonce label, and why a closing channel must pay the group's key rather than the node's |
| [group-lightning-transcript.md](./group-lightning-transcript.md) | the group Lightning wallet in a room's chat, phased — why every Lightning kind but the three the group signs became a raw-JSON bubble, the standing notice the Iceberg ceremony never got, and which of the operator's messages earn a line and which never should |
| [lsp-connection-status.md](./lsp-connection-status.md) | whether the group's node can reach the LSP, phased — why *connected* means the LSP's `init` and not the handshake, why a refused start is a status too, and why the group hears of the link through the snapshot, settled and rate-limited, rather than an event of its own |
| [group-lightning-liquidity.md](./group-lightning-liquidity.md) | buying inbound liquidity for the group's Lightning wallet as Phoenix does, phased — why a purchase is a splice every co-signer refuses today, why the group decides it with an authorisation rather than the operator with a tap, the same group key at every funding index, and the rule that lets a device that missed a splice follow the channel to its new output |
| [group-payout-destination.md](./group-payout-destination.md) | where the group pays a member, built: a Lightning address they sign into the room, and optionally an onchain address that must be unused when set. Covers why the member signs it and the group doesn't, why Electrum's history call can't say "unused" but its subscription can, why Tor on means the address isn't resolved, why a co-signer can't verify a Lightning payee but can name an onchain one, and why a member added later is sent the rest's destinations without a line |
| [member-deposits.md](./member-deposits.md) | whose deposit it is, built: no address or invoice shown until it is a member's, through a group-signed depositor statement that embeds their payout destination and gives them a wallet branch of their own; per-member tallies kept as a ledger rather than read off the unspent coins; and BOLT12 switched off, in the app and in the lightning-kmp fork, because an offer belongs to nobody |
| [group-wavelength-wallet.md](./group-wavelength-wallet.md) | an Ark wallet the group holds with Iceberg keys, run as a watch-only daemon by one elected operator, planned and not built. Covers why a threshold signer that does not judge what it signs protects nothing, why each VTXO's tree key is a child share hashed from the seeds rather than a BIP32 child, why the identity key has to stop being the vHTLC key, why credit stays at zero, and why a wallet with no quorum loses money rather than just stalling |
| [group-wavelength-ui.md](./group-wavelength-ui.md) | the screens for that wallet and what has to run behind them, on mainnet by decision and not yet on a phone. Covers why the balance is a device's own ledger and not the operator's figure, why a real round on phones comes before most of the screens and can stop the rest, and what the UI holds back until the wallet's own gaps close |
| [group-wavelength-payouts.md](./group-wavelength-payouts.md) | paying a depositor out of the group's Wavelength wallet to their signed destination, onchain and over Lightning, built in `jvmTest` and on for mainnet by decision, with no payout yet run against a real round. Covers why an onchain payout is a leave in a round that pays exactly what was approved and once, why the policy's unfilled `authorisedScripts` has to become a budget for leaves alone, why a refunded Lightning payout must go back to the member and not the pot, and why the onchain rail can reach mainnet before Lightning |
| [group-wavelength-closing-payout.md](./group-wavelength-closing-payout.md) | emptying the group's Wavelength wallet when the last member is paid out, planned and not built, from a mainnet room wedged by 12 sats. Covers why the operator's minimum change makes a wallet that cannot be emptied, why the residue is the unspent fee reserve of every Lightning payout and not bad luck, why a closing leave's amount has to be a range and why returning the group no change is what makes that safe, why a quorum's FROST signature cannot stand in for the member whose dust is taken, and the exit path that already empties a wallet today with no policy change |
| [group-wavelength-spendable-ceiling.md](./group-wavelength-spendable-ceiling.md) | paying a member the most the group's Wavelength wallet can spend rather than refusing their whole balance, built (phases 1-3; the run on a real phone, Phase 4, not yet), from the same mainnet room wedged by 12 sats. Covers why the operator's minimum change is a floor on change and not a ban on a smaller spend, why a member whose balance covers the whole wallet still takes all of it, why the fee reserve sits inside the ceiling and is safe in the direction fees vary, why nothing on the wire or in the policy changes, and why the last 1,000 sats are the closing payout's business |
| [group-wavelength-redesign.md](./group-wavelength-redesign.md) | the seven screens of that wallet read against the Bitcoin Design Guide, built. Covers which of the guide's pages are normative and which are a single-user reference design whose threat model does not hold here, the exchange rate the app has held since Phoenix and never shown, why a unified BIP 321 request would hide the one thing a member needs to decide, why *daemon* leaves the UI and *round* stays, and ten phases that change nothing a device signs |
| [payouts-only.md](./payouts-only.md) | the group's wallet paying only a member's own coins to that member's own payout destination, built: why a payout has no change, whose a coin is by branch and by the group's word, a coin assignment (30341) for the coins nobody owns, shared coins paid to all their owners at once, and why a Lightning payout is bounded by the member's balance but its payee is still the proposer's word |
| [member-transfers.md](./member-transfers.md) | a member transferring part of their attributed balance to another member, built (phases 0-6; the emulator run, Phase 7, not yet), offered on regtest and signet only: a request the giver signs (30342), embedded whole in a transfer the group signs (30343). Covers why the group's signature alone would reopen what payouts-only closed and the giver's alone would leave two transfers unordered, why nothing moves but a ledger term on the Wavelength and Lightning rails and coin shares onchain, why amounts are in ledger sats before the Wavelength scaling, why a transfer must never reach the deposit ledger, why every device has to count transfers a release before any device can propose one, and the two adversarial reviews that made every device void a transfer the signed records don't cover and refuse one dated before the last |
| [kinds.md](./kinds.md) | the register of every nostr kind Dedun uses in 30300–30449 and who allocated it, shared with Giza and Bennu: new kinds go here before code |
| [non-member-deposits.md](./non-member-deposits.md) | deposits for someone outside the group, built (phases 0-8; the emulator run, Phase 9, not yet): a *contact*, a name and one phone number or email address, which the group signs whole before any deposit can be counted to them, every member sees, the group can pay out to a destination it signs for them, open a deposit branch and invoices for, and later say a member was. Covers why the contact's id is derived from the number (no duplicates by construction, no chosen keys), why the quorum decides every step with no member's consent asked, how a contact's payout reuses every rail's destination check, why nothing is proposed in a room until every device has announced it reads contacts (an older build shows an unknown kind as raw JSON), and the three releases, readers first |
| [group-lightning-swap-in.md](./group-lightning-swap-in.md) | onchain deposits into the group's Lightning wallet, built (phases 0-7; the live run, Phase 8, not yet): a swap-in address per member whose key path is the group's Iceberg key with the LSP's, and whose refund leaf is a FROST key derived per member and index. Covers the swap-in signer the fork lacks, the co-signer rule for a splice that only adds group coins, why the depositor pays the fee and why there is no channel opening from a swap-in, and the personal swap-in that was built and reverted |
| [bark-wallet.md](./bark-wallet.md) | a personal wallet under the profile, built and then removed, and kept for its findings: an Ark wallet run on the device by Second's Bark SDK, opened with the seed profile's own twelve words. Covers why Bark's `m/350'` and BIP86 paths share the words safely, one open wallet per datadir and per process, a port because iOS has no Kotlin binding, why Tor on means no Ark wallet, and coins that must be renewed within 28 days |
| [bark-wallet-removal.md](./bark-wallet-removal.md) | taking that wallet back out, built, in one release because no build held real sats. Covers why turning off its rollout flag would strand money, the order the code, the iOS half, the build and the test network came out in, what has not been checked, and an appendix with the wind-down release to use if a build with money turns up |
| [wavelength-wallet.md](./wavelength-wallet.md) | a personal Ark wallet under the profile again, on mainnet, through Lightning Labs' daemon and the `wavelength-kmp` submodule, set to mainnet by decision and not released, with no coin yet moved through it. Covers what a real mainnet operator told an empty wallet, why the profile's words go in as account-level keys rather than a master key that would share Phoenix's `m/84'`, the unreleased daemon the SDK's `descriptors` branch needs, why desktop has no runtime, why the wallet's directory is not backed up, coins kept alive with no renewal verb, why a first open recovers only a little and public Esplora hosts fail it, and the mainnet check Bark skipped |
| [lightning/README.md](./lightning/README.md) | the remediation plans for the review of the group Lightning wallet — seven findings, one phased plan each, and the order to build them in |
| [../testing/regtest/README.md](../testing/regtest/README.md) | running the group Lightning wallet end to end on a regtest network of bitcoind, electrs and two eclair nodes — the prerequisites and ports, where the run's output and eclair's log end up, and what each failure means. Not part of `check` or CI |
| [marmot-v2.md](./marmot-v2.md) | bringing the group layer onto the Marmot current profile after the quartz 1.15.2 bump — the nine rules that reach this app, why the room's id and the routing id have to become two values, nine phases from reading both profiles to publishing before applying, and the one fork the library cannot yet settle |
| [dedun-rename.md](./dedun-rename.md) | renaming Bennu to Dedun under `com.machankura.dedun`: the twelve `bennu/` hash tags that stay, why a new application id is a new app with an empty sandbox, the history sentences a sweep would falsify, and eight phases that hold the store identities back until the owner decides what happens to existing installs |
| [giza-into-dedun.md](./giza-into-dedun.md) | taking Giza's work into Dedun, planned and not started: why it comes through Bennu's port rather than straight from Giza, the kinds that collide (Dedun's deposit, coin, transfer and Wavelength kinds against Giza's refresh, repair and governance) and why Dedun moves its own to 30390–30393 and 30400–30412, reading the old numbers for good because a signed event's id covers its kind, in three releases before any of Giza's kinds ship; the schema at Giza + 9; and the Dedun-only work in each phase: the Wavelength wallet's Iceberg holders, what a rotation waits for, the leave payouts in a governed room, and wavelength-kmp's place in the toolchain bump |
| [giza-second-batch.md](./giza-second-batch.md) | the 166 Giza commits after `d0437d6c`, planned and not started, with the owner's decisions D8–D13 taken. Recovery codes come first, handed to new profiles in the release that brings them in, and in Dedun a code restores the wallet as well as the profile. Notifications and background sync share one service with Dedun's own Wavelength service. The second collision of kinds: Giza's confederation, inbox and group-message kinds landed on 30379–30416, so Dedun's moved kinds and its contacts kinds move again, before K2. Disappearing messages come in, with every Dedun NIP-17 kind classified. Confederations are not taken |
| [recovery-code-v3.md](./recovery-code-v3.md) | Giza's note on nine-block recovery codes, with a Dedun preface: why a code's key is BIP39 entropy and so a 24-word phrase in another notation, why the words go in `seed.dat` and the code beside them in `recovery-codes.dat`, the typo a checksum can locate, and why no build hands out a code it cannot restore. In Dedun the words also start the profile's Lightning node, so the code backs the wallet |
| [notifications-and-background-sync.md](./notifications-and-background-sync.md) | Giza's note on notifications, with a Dedun preface: notify from the `ChatMessage` table and never from the wire, a cursor that is a row id, armed only after the first sync, one runtime per process, and a background pass that is the foreground catch-up, bounded. In Dedun every wallet line is classified, and the wallets' own objects stay with the screens |
| [paid-requests.md](./paid-requests.md) | an invoice or address ceasing to be a QR code once money arrives against it, built (phases 0-7) — the seven screens that hand out a request, the three that kept showing a paid one and the three that rotated in silence, why first sight rather than confirmation, why paid is stated from the phases that say a payment happened rather than by negating the ones that say nobody has paid, why an Ark boarding address can only be known paid once the group signs for the coin, why an expired invoice is hidden on the same argument and by a timer rather than a poll, and the two request states the tree had been missing |
| [group-wavelength-unconfirmed-deposits.md](./group-wavelength-unconfirmed-deposits.md) | money on its way into the group's Wavelength wallet -- an onchain payment to a boarding address, from the mempool until the round that takes it in -- built (phases 1-5; the spikes and the real deposit, 0 and 7, not run). Covers the three states of a deposit's life no screen showed and how long they last on mainnet, why the watch needs nothing from the operator when every device already holds the checked address and has its own connection to the chain, why an arriving coin must never reach the deposit ledger (where one replaceable payment would halve every other member's payable balance), why it is a table rather than memory and keyed so that one id spans all four states, and the first sight of money the receive screen's paid panel was always meant to have |
| [marmot-kmp.md](./marmot-kmp.md) | moving the group layer off quartz's engine and onto marmot-kmp, mls-kmp and crypto-kmp — why staying on quartz 1.15.2 is staying on an engine that left, the 228 import lines and the one rule that places all but two of them, the group state on a device that must still open afterwards, and eight phases that put the swap in one commit and the evidence around it |
| [marmot-kmp.md](./marmot-kmp.md) | moving the group layer off quartz's engine and onto marmot-kmp, mls-kmp and crypto-kmp — why staying on quartz 1.15.2 is staying on an engine that left, the 228 import lines and the one rule that places all but two of them, the group state on a device that must still open afterwards, nine phases that put the swap in one commit and the evidence around it, and how often this app persists a group once a sent message no longer rewrites it |
| [marmot-kmp.md](./marmot-kmp.md) | moving the group layer off quartz's engine and onto marmot-kmp, mls-kmp and crypto-kmp — why staying on quartz 1.15.2 is staying on an engine that left, the 228 import lines and the one rule that places all but two of them, the group state on a device that must still open afterwards, nine phases that put the swap in one commit and the evidence around it, how often this app persists a group once a sent message no longer rewrites it, and the schema change that followed, opened from the version the app ships |
| [quartz-mls-marmot-update.md](./quartz-mls-marmot-update.md) | moving quartz, mls-kmp, marmot-kmp and crypto-kmp to their newest releases, phased and built but for a mac and mls-kmp 1.4.0's release — why almost nothing the app calls has changed while the way the build reaches them has (Nostr + Blossom through Magpie, none of it on Maven Central), the go/no-go that is the owner's, marmot's own pins first and the newest second, and the lock that has to be written on a mac before it can be frozen |
| [dependency-update.md](./dependency-update.md) | moving everything else in the catalogue to its newest stable release, phased and built but for a mac, devices and pushing the submodule branches — why the libraries go first and need no newer Kotlin, why AGP and Kotlin can only move down the whole submodule chain at once, the material3 alpha that was built for an older Compose than the app's, and what has to keep opening on a device afterwards |
| [member-admin-promotion.md](./member-admin-promotion.md) | making a member an admin and back, phased and built, promotion with a share and demotion with a refresh under the experimental enrollment, under three rules: every admin holds a share, every commit in a keyed room is a proposal until the group's threshold signature is on it, and a robust group keeps at least three admins. Covers why the commit rides inside the group's signature and the rule inside the group's state, which lets every device refuse an unsigned commit with the libraries as they are, why a member's own leave stays exempt, and why a removed admin comes back as an admin when re-invited today |
| [admin-change-rotation.md](./admin-change-rotation.md) | an admin change carries its rotation with it: a phased plan, built but for its device session, in which the rotation that follows removing an admin, and the one before a first promotion, asks nobody, because the group's signature on the change already decided who is withheld, and in which admin changes and rotations take turns. Covers the five ways the two overlap today, a removed admin who keeps a live share among them, and the one check a device makes to take part unasked |
| [member-row-actions.md](./member-row-actions.md) | every member row but your own offers every action that applies to that member, and an action you can't take now opens a screen saying what stands in the way and what would change it — phased and built. Covers the twelve reasons an action is refused today, why an unavailable item is marked rather than disabled, and why the explanation screen recomputes its reason instead of carrying it |
| [member-admin-promotion.md](./member-admin-promotion.md) | making a member an admin and back, phased and built, promotion with a share and demotion with a refresh under the experimental enrollment, under three rules: every admin holds a share, every commit in a keyed room is a proposal until the group's threshold signature is on it, and a robust group keeps at least three admins. Covers why the commit rides inside the group's signature and the rule inside the group's state, which lets every device refuse an unsigned commit with the libraries as they are, why a member's own leave stays exempt, and why a removed admin comes back as an admin when re-invited today |
| [member-admin-promotion.md](./member-admin-promotion.md) | making a member an admin and back, phased and built but for promotion with a share and demotion with a refresh, under three rules: every admin holds a share, every commit in a keyed room is a proposal until the group's threshold signature is on it, and a robust group keeps at least three admins. Covers why the commit rides inside the group's signature and the rule inside the group's state, which lets every device refuse an unsigned commit with the libraries as they are, why a member's own leave stays exempt, and why a removed admin comes back as an admin when re-invited today |
| [robust-group-admins.md](./robust-group-admins.md) | making every founding member of a robust group an admin of its room, on every device -- phased and built. Covers why the ceremony's participants are the founding set, the Welcome that flags no admins, the joiner's check against the ceremony it took part in (since removed), and finishing an invite that partly failed |
| [remove-convenient-groups.md](./remove-convenient-groups.md) | every group a member makes is robust -- phased and built. Covers why a one-to-one chat is not a group yet takes no new members, why the three-member floor moves to the member picker rather than down to 2-of-2, the quorum rules that outlive `ChatRoomType`, and why a Bennu peer's convenient group is still joined |
| [remove-founding-admins-card.md](./remove-founding-admins-card.md) | taking out the "Founding members aren't admins" card, its repair and the Welcome line behind it -- phased and built. Covers why a founder missing from the policy stopped meaning a fault once admins could be demoted, why the check cannot be taught the difference, and why the retired caption type stays |

Start with the ceremony if you are new to this area; the Marmot notes all assume it.
Read the skipped-keys note before debugging any "the other device never got it"
report — it is silent, and it looks like every other kind of delivery failure. The
resend note is the recovery for it on a signing session, and says which delivery
failures a resend repairs and which heal on their own. The
sync note stands alone, and the dead-code inventory reads as a follow-up to it. The
batch-signing note is a phased plan that has been built: read it after the
derivation note, whose one rule is the same one it is built around. The member
chronicle note is a phased plan that has been built and now carries nothing --
the document kinds it was for are gone -- and reads as the membership note's
unanswered half: what a member who joins late can be given, and the one thing
they cannot. The
jvm-target note is unrelated to all of them: it is a build and packaging story.
The subgroups note is a phased plan that has been built; it assumes both
shared-key notes and reads as the ceremony's second half — what a group does
once it has a key, and what it can say about a group that does not yet.
The group-wallet-addresses note is a phased plan that has been built, and is the
group-chain-wallet note's Phase 5 taken one screen further: read that note's
Phases 4 and 5 first, and its own table of where the build chose differently
before the code.
The group-wallet-guardians note is a phased plan that has been built; it assumes the subgroups and group-chain-wallet notes, and changes one sentence in
each.
The member-guarded-wallets note is a phased plan that has been built. It
takes the guardians note's derivation unchanged and gives the key path to one
person: read that note and the policy note's D2 first, then this note's table
of why a birth certificate will not do, its Phase 0, and its table of where the
build chose differently before the code.
The member-wallet-guarding-group note is a phased plan that has been built,
and changes what the member-guarded-wallets note's Phases 10 and 11 draw, not
what they verify: read those two phases first.
The subgroup-wallet-policy note is a phased plan that has been built. It
reads as the guardians note's *whose choice it is* reversed: read
that section, then this note's Phase 0, whose five decisions shape every phase
after it, and its table of where the build chose differently before the code.
The guardian-delay note is a phased plan that has been built. It changes
the guardians note's *Why 12, and why for everybody* and reuses the
wallet-policy note's plumbing one field further: read both, then its table of
what is already per leaf, then its Phase 0, and its table of where the build
chose differently before the code.
The member-wallet-delay note is a phased plan that has been built. It
reads as the guardian-delay note's last build-table row answered: read the
member-wallets note and the guardian-delay note first, then its table of where
the delay should live, then its Phase 0, and its table of where the build chose
differently before the code.
The guarded-wallet-forwarding note is a phased plan that has been built.
It changes who sends a 30333 notice and when, and no format: read the guardians
note's *How an ancestor finds the coins* and the member-guarded-wallets note's
Phase 10 first, then its two bugs, its Phase 0, and its table of where the build
chose differently before the code.
The guarded-wallet-funding note is a phased plan that has been built. It
reads as the guardians note run the other way, coins in rather than out: read
that note and the address note's *Why `+ 2`* first, then its seven decisions
before its phases, and its table of where the build chose differently before
the code.
The Material Design note is a phased plan that has not been built, and is the
only one about what the app looks like rather than what it does; read the
jvm-target note first if you want to know why its adaptive-layout phase exists.
The nsec sign-in note is a phased plan that has been built; it inherits the
key-storage decision from the jvm-target note and drives the navigation state
machine `NavigationViewModel.processLocalAccount` implements, so read it with the
code open, and read its table of where the build chose differently first.
The npub sign-in note is a phased plan that has been built, and reads as that
plan's out-of-scope note answered: it takes the `Identity` type and the sign-in
machine as given and asks what an identity with no secret is for, before it asks
how to build one; read its table of where the build chose differently first.
The multiple-profiles note is a phased plan that has been built, and reads as the
third of the sign-in notes: it asks what happens when the device holds two
identities, and its first phase changes one rule the other two share — a seed's
key becomes a credential like any other — so read it with `StoredIdentity.merge`,
`SovereignWalletViewModel.switchToIdentity` and the startup screen open, and read
its table of where the build chose differently first.
The npub profile preview note is a phased plan that has been built, and is the
smallest of the plans: it changes the entrance to the direct-message flow and
nothing past it, so read it with `StartDirectMessageToNpubOrNip05Dialog` and
`ChatRoomMessagingViewModel.initiateNewChat` open, its four decisions before its
four phases, and its table of where the build chose differently first.
The group chain wallet note is a phased plan that has been built. It assumes
both shared-key notes and the batch-signing note, and reads as the derivation
note's one rule with money behind it; read its derivation section before any
phase, since every phase after the first is built on that path, and its table of
where the build chose differently before any of the code.
The Marmot v2 note is a phased plan that has been built, and is the largest of
them: it assumes the membership and derivation notes, takes the quartz 1.15.2 bump
as its Phase 0, and is ordered so that the phases that reach other clients come
before the ones that make the app's forks settle the way theirs do; read its table
of nine rules and its two constraints before any phase, and its table of where the
build chose differently before any of the code -- its first row, that a room's
profile picks its pipeline, is the shape of everything that follows, and its last
is the one fork the library cannot yet settle.
The group send-an-amount note is a phased plan that has been built, and reads
as the third gate of the group chain wallet note's Phase 10: why that gate
could not lift as written, and what stands in for it now that it is gone. Read
its list of what the gated code got wrong before its phases.
The marmot-kmp note is a phased plan that has been built, phases 0 to 9, and reads
as the Marmot v2 note's Phase 0 come round again: quartz took the engine out, so
the version the v2 work was built on is the last one that has it. Read the v2 note
first — this plan happens entirely on the current-profile side of its first row —
and read this one's two constraints before any phase: the group state already on
devices is what decides whether the move can ship at all, and Phase 4 is where it
is measured rather than argued. Phases 8 and 9 were not in the plan as written and
are the pair to read if you are changing when a group is saved or what the database
holds: 8 is where a sent message stopped rewriting the whole state, where the
ordering that decision hangs on is spelled out, and where the skipped-keys note's
diagnosis is corrected; 9 is the schema change 8 made necessary, opened from a
database at the version the app ships. What they do not claim is in the status note:
no manual upgrade of a profile with rooms that have real histories has been done, and
that is still the last check before a release.
The quartz-mls-marmot-update note is that plan's D2 come due, and is built but for a
mac and a release. Phases 0 to 5a and the Linux half of 6 and 7 are done. mls-kmp 1.4.0
(5b) waits for a release, Phase 6's iOS lock lines and the freeze wait for a mac, and
Phase 8 is the manual checks. Read it after the marmot-kmp note. Its headline is that the
code barely moved and the distribution did: the libraries are on Nostr + Blossom through
Magpie, and `gradle/magpie.lock` is now as much a part of the build as the catalogue.
Read its "What Magpie is" before touching either. Its Phase 3 has the one correction worth
knowing before reading a library's commit log as its release: mls-kmp drafted an epoch-0
repair and removed it before 1.3.1 shipped.
The ceremony-creation note is a phased plan that has been built. It reads as
the subgroups note's Phase 10 followed through to the screens: read it after
that phase, and its table of findings before any phase. Its second half
removes the nip30303 documents and leaves the member chronicle built but
carrying no kinds, so read it before building on the chronicle note.

The group Lightning wallet note is a phased plan that has been built, except
for splicing out, and run end to end on regtest. It assumes the group chain
wallet note and the ceremony note, and reads as the lightning-kmp fork's
`FEATURE-PARITY.md` taken past its "out of scope" line: read its eight
constraints and its decision table before any phase, its trust table before
arguing with either, and its two tables of what the build found before
changing anything it describes.
for keyed ones, which wait on its Phase 0. It reads as
and 9), which waits on enrollment leaving experimental. It reads as
The member-admin-promotion note is a phased plan built but for a keyed room's admin
leaving; its share work (Phases 8 and 9) is behind the experimental enrollment. It reads as
the Marmot v2 note's Phase 7 finished, and as the share-refresh note put to work: a
promotion is an enrollment and a demotion is a refresh. Read its three rules and its
headline before any phase. Its headline is the one idea the keyed-room half stands
on -- the commit travels inside the group's signature -- and its ship order says which
half can go out before enrollment leaves experimental.

The wallet-removal note is a phased plan that has been built but for its Phase 7b.
It is how Giza came to keep no wallet: read its constraints and its decisions
before any phase, and its table of where the build chose differently before the
code. It reads as the rebrand note's D1 and D2 tested: one network shared with
Bennu, whose members still bring wallets into rooms Giza is in.

The group Lightning liquidity note is a phased plan that has been built, but for
its Phase 9, and is on, on mainnet, by decision ahead of its own mainnet check.
It is the group Lightning wallet note's check 8 built for one kind of splice:
read that note's D6, Phase 9 and Phase 13 first, and the lightning
remediation README's step 2, which this plan waits for, then its six
constraints and eight decisions before any phase, and its table of what
Phase 0 found against eclair before the code: the minute eclair allows a whole
splice is what every group signature in it has to fit inside.

The Bark wallet note is a phased plan that was built, and then removed: the
removal note says how and why, and its code is at `56031156`. It describes nothing
in the tree now. Read it for its table of what Bark asks and its Phase 0's findings,
if Ark comes up again.

The standalone-electrum note is a phased plan that has been built, and is the
group chain wallet note's *An identity without a node* answered: read that
paragraph and the nsec sign-in note's *The constraint* first, then its seven
decisions, and its table of where the build chose differently before the code.

The group Wavelength unconfirmed-deposits note is a phased plan that has been
built, but for its spikes and its live run. It is the paid-requests note's D1 taken to the one surface that could not
reach it: read that note's D1 and the table row saying a boarding address can only
be known paid once the group signs for the coin, then this note's *the one thing
that must not happen* -- `balancesIn` reads the deposit ledger with no filter on
`confirmedAt`, which is why an arriving coin gets a table of its own -- and then
its A2 and A3 before any phase, and its table of what the build found -- where A2's dilution is
measured on a real pair of balances.

The member-transfers note is a phased plan that has been built, but for its emulator
run. It changes what every balance in the payouts-only, member-deposits and Wavelength
payouts notes counts, without changing a single payout's rule: read payouts-only's D2
and D4 and the Wavelength payouts note's W9 first, then this note's fact 3 -- why the
giver signs as well as the group -- and its T15 and T16, which the reviews added, before
any of the code.
