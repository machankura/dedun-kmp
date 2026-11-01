# dedun docs

Notes on the parts of this app whose behaviour is not recoverable by reading the
code alone — where the reasoning lives in a protocol, a failure mode that is
silent, or a decision that looked arbitrary and was not.

| document | covers |
|---|---|
| [shared-key-ceremony.md](./shared-key-ceremony.md) | ChillDKG over NIP-17: the rounds, the approval gates, the chat transcript, participant ordering |
| [shared-key-derivation.md](./shared-key-derivation.md) | deriving further keys from the group's threshold key with FROST tweaks — why not BIP32, why no chain code, and the one rule that must not be broken |
| [subgroups.md](./subgroups.md) | a group making another group — the four ceremonies, what the parent's signature actually covers, and why the child's key is fresh rather than derived |
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
| [marmot-membership.md](./marmot-membership.md) | how members join an MLS group, and the epoch race that makes a missing member look like a successful invite |
| [member-chronicle.md](./member-chronicle.md) | handing a member added after the work was done the group's signed record — why the events are not on the wire at all, and why the room's id is enough to verify them |
| [marmot-direct-messages.md](./marmot-direct-messages.md) | a one-to-one message inside a group as a stock NIP-59 gift wrap — what its MIP-03 carve-out costs, why the sender cannot read their own, and the one query that would broadcast it |
| [mls-skipped-keys.md](./mls-skipped-keys.md) | why a group event that arrives a moment late is dropped for good, which flows trigger it, the quartz fix, and the partial mitigation in this app |
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
| [personal-swap-in-wallet.md](./personal-swap-in-wallet.md) | the personal node's swap-in wallet, planned: its addresses and coins on a screen, and why a coin with 3 confirmations has not moved. Covers why the swap-in already runs on its own, the four conditions it needs, why the group's node must never show a swap-in address, the fee limit that silently refuses a first deposit, and the refund at six months |
| [lightning/README.md](./lightning/README.md) | the remediation plans for the review of the group Lightning wallet — seven findings, one phased plan each, and the order to build them in |
| [../testing/regtest/README.md](../testing/regtest/README.md) | running the group Lightning wallet end to end on a regtest network of bitcoind, electrs and two eclair nodes — the prerequisites and ports, where the run's output and eclair's log end up, and what each failure means. Not part of `check` or CI |
| [marmot-v2.md](./marmot-v2.md) | bringing the group layer onto the Marmot current profile after the quartz 1.15.2 bump — the nine rules that reach this app, why the room's id and the routing id have to become two values, nine phases from reading both profiles to publishing before applying, and the one fork the library cannot yet settle |
| [dedun-rename.md](./dedun-rename.md) | renaming Bennu to Dedun under `com.machankura.dedun`: the twelve `bennu/` hash tags that stay, why a new application id is a new app with an empty sandbox, the history sentences a sweep would falsify, and eight phases that hold the store identities back until the owner decides what happens to existing installs |

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

The group Lightning liquidity note is a phased plan that has been built, but for
its Phase 9, and is on, on mainnet, by decision ahead of its own mainnet check.
It is the group Lightning wallet note's check 8 built for one kind of splice:
read that note's D6, Phase 9 and Phase 13 first, and the lightning
remediation README's step 2, which this plan waits for, then its six
constraints and eight decisions before any phase, and its table of what
Phase 0 found against eclair before the code: the minute eclair allows a whole
splice is what every group signature in it has to fit inside.

The standalone-electrum note is a phased plan that has been built, and is the
group chain wallet note's *An identity without a node* answered: read that
paragraph and the nsec sign-in note's *The constraint* first, then its seven
decisions, and its table of where the build chose differently before the code.
