# The Wavelength wallet row waits for the onchain wallet

A group's Wavelength wallet leaves to the group's onchain wallet on the same network. Until the
group has opened one there is nothing behind the row on `ChatRoomDetailScreen`, and the row should
say so and open nothing — exactly as the Lightning wallet's row above it does.

`docs/group-wavelength-ui.md` already asked for this. Its screens table names the row as

> | row on `ChatRoomDetailScreen` | everyone in the room, **disabled with a reason until the onchain wallet exists** | 2 |

and Phase 2 is marked done. The row shipped without it. This note is the plan for closing that gap.

## What is there today

| | Lightning | Wavelength |
|---|---|---|
| the row | `ChatRoomDetailScreen.kt:487` | `ChatRoomDetailScreen.kt:512`, one per network |
| the row's gate | `enabled = hasOnchainWallet` | **none** — `WavelengthWalletRow` takes no `enabled`, and `SignedWorkRow` defaults it to `true` |
| the reason on the row | `lightning_needs_an_onchain_wallet` | **none** |
| the screen's gate | `GroupLightningWalletUIState.NeedsOnchainWallet` | `GroupWavelengthWalletUIState.NeedsOnchainWallet`, from `GroupWavelengthWalletViewModel.kt:77` |
| what the screen says | `open_the_group_s_onchain_wallet_first` | the same string — which says *"A **Lightning** wallet closes to the group's onchain wallet"* |

So the gate exists one screen too late. A member taps a live row with a chevron, lands on an empty
state, and is told about a Lightning wallet they did not ask for.

### The row the gate is copied from

`ChatRoomDetailScreen.kt:487-507`, whole:

```kotlin
if (GroupLightningRollout.ENABLED) item {
    val hasOnchainWallet = chatRoomDetailUIState.groupWallet != null
    SignedWorkRow(
        icon = Icons.Default.Bolt,
        headline = stringResource(Res.string.lightning_wallet),
        supporting = if (hasOnchainWallet) {
            GroupWalletStatementEvent.chainName(GroupLightningRollout.NETWORK)
        } else {
            stringResource(Res.string.lightning_needs_an_onchain_wallet)
        },
        enabled = hasOnchainWallet,
        onOpen = { … },
    )
}
```

`SignedWorkRow` (`ChatRoomDetailScreen.kt:1403`) is what makes `enabled = false` mean something: the
`Card`'s `onClick` stops firing, the headline and leading icon drop to M3's 38%, the chevron goes,
and the supporting text stays at full contrast *because that is where the reason is*. Nothing new is
needed there — the parameter is already in the signature and already tested through the Lightning
row.

### The one thing that does not copy over: the chain

This is the whole of the design work, and the reason this is not a two-line change.

- Lightning has **one** network. `GroupLightningRollout.NETWORK` is `NodeParamsManager.chain`, and
  `ChatRoomDetailViewModel.kt:124` reads `chatRepository.groupWallet(chatRoomId, NodeParamsManager.chain)`.
  So `groupWallet != null` is, for Lightning, the right question about the right chain.
- Wavelength has a **row per network**, over `GroupWavelengthRollout.NETWORKS`, and its screen asks
  `repository.hasOnchainWallet(chatRoomId, chain)` — *that row's* chain
  (`GroupWavelengthWalletViewModel.kt:77`).
- Both predicates bottom out in the identical call, `GroupWalletManager.walletFor(database, chatRoomId, chain)`
  (`DatabaseChatRepository.kt:171`, `DatabaseGroupWavelengthRepository.kt:49`). So a per-chain check on
  the row can agree with the screen behind it exactly, with no second notion of "set up" to drift.

`NETWORKS` is `listOf(Chain.Mainnet)` today and `NodeParamsManager.chain` is mainnet, so reusing
`chatRoomDetailUIState.groupWallet != null` would look correct in every test and on every phone.
It would be correct by coincidence. `docs/group-wavelength-ui.md` U8 records that signet sat beside
mainnet in `NETWORKS` until 7 November 2026 and that `allows` still takes it, so the coincidence has
an expiry date. Carry the chain.

## Decisions

**R1. The fact is chain-keyed, and read in the view model.** `ChatRoomDetailUIState.Loaded` gains
`wavelengthOnchainChains: Set<Chain>` — the networks in `GroupWavelengthRollout.NETWORKS` the group
has an onchain wallet on. Not a `Boolean`, because one row per network each needs its own answer; not
a `Map<Chain, GroupWallet>`, because the row says only whether it is open, which is what the existing
`groupWallet` doc comment already promises for the onchain row.

**R2. The row takes the answer, not the state.** `WavelengthWalletRow(chain, hasOnchainWallet, onOpen)`,
so it stays renderable from a JVM test with no repository, which is how its two existing tests work
(`GroupWavelengthWalletScreenJvmTest.kt:48` and `:56`). A default of `hasOnchainWallet: Boolean = true`
would let a future call site forget the gate silently; there are two call sites, both in this repo, so
make it required.

**R3. The reason is a string of its own, not Lightning's.** `lightning_needs_an_onchain_wallet` names
Lightning. A Wavelength row needs its own sentence, in sentence case, and the shared
`open_the_group_s_onchain_wallet_first` has to stop claiming Lightning on two Wavelength screens
(`GroupWavelengthWalletScreen.kt:293` and `IcebergCeremonyScreen.kt:164`, which is purpose-aware
everywhere else through `isWavelength`).

**R4. Disabling the row closes the only door to a non-default-chain onchain wallet, and that is
recorded rather than worked around.** `GroupWavelengthWalletScreen.kt:249` is the *only* call site in
the app that passes a non-null `chain` to `GroupWalletRoute`; the onchain row itself
(`ChatRoomDetailScreen.kt:471`) passes none, which `GroupWalletRoute.chain`'s own comment reads as
"the node's own". The code comment at `ChatRoomDetailScreen.kt:509-511` says as much — *"that's the
one way to a signet onchain wallet from here"* — and it is why the row was left open. With `NETWORKS`
mainnet-only and the node on mainnet, those two routes are the same chain and nothing is lost. The
comment is stale, and the plan deletes it. What replaces it is a condition written into
`docs/group-wavelength-ui.md`: **restoring a non-mainnet chain to `NETWORKS` needs a way to a group
onchain wallet on that chain that does not go through the Wavelength row**, because that row will now
be disabled. That is a smaller, checkable obligation than leaving a live row in front of a wrong
sentence for every group on mainnet.

**R5. No rollout gate of its own.** The gate is a property of the row, not a new thing to ship. It
lands with `GroupWavelengthRollout.ENABLED` already on.

## Phases

### Phase 0 — make this worktree able to build

No Gradle task runs in a fresh `.claude/worktrees/*` checkout until the submodules and
`local.properties` are in place; `settings.gradle.kts` reads a commit out of
`experimental/wavelength-kmp` during settings evaluation, so it fails before configuration.

```bash
git -c protocol.file.allow=always submodule update --init --recursive
```

Then `sdk.dir=…` in a `local.properties` at each build root: the worktree root,
`lightning-kmp-app`, `lightning-kmp-app/experimental/lightning-kmp`, `.../bitcoin-kmp`,
`.../secp256k1-kmp`, `experimental/wavelength-kmp` and
`experimental/wavelength-kmp/experimental/wavelength`.

**Done when** `./gradlew :composeApp:compileKotlinJvm` configures and compiles. Use that and not
`compileDebugKotlinAndroid` as the compile check here: the Android target wants the Wavelength
bindings under `experimental/wavelength-kmp/bindings/android`, which are built artifacts and not in
git, and `settings.gradle.kts` only warns about their absence.

### Phase 1 — carry the chains into the chat room's state

`ChatRoomDetailUIState.kt`, beside `groupWallet` (`:72`) and the two participant sets:

```kotlin
/**
 * The networks in [GroupWavelengthRollout.NETWORKS] the group has an onchain wallet on, which
 * is what a Wavelength wallet leaves to. A row for a network absent here is disabled and says
 * so, as the Lightning row does: the same question `GroupWavelengthWalletViewModel` asks
 * before its screen's first state.
 */
val wavelengthOnchainChains: Set<Chain> = emptySet(),
```

`ChatRoomDetailViewModel.kt:126`, in the `Loaded(...)` it builds, read exactly as
`lightningParticipants` reads — gated on `ENABLED` so a device with the gate shut does no extra
queries:

```kotlin
wavelengthOnchainChains = if (GroupWavelengthRollout.ENABLED) {
    GroupWavelengthRollout.NETWORKS.filter { chatRepository.groupWallet(chatRoomId, it) != null }.toSet()
} else emptySet(),
```

One query per network, inside the same `Dispatchers.IO` read that already makes a dozen; `NETWORKS`
is one entry long. `groupWallet` swallows its own failures and logs (`DatabaseChatRepository.kt:170`),
so a read that throws leaves the row disabled with its reason rather than crashing the screen — which
is the safe direction.

**Done when** `:composeApp:compileKotlinJvm` passes and the default keeps every existing
`ChatRoomDetailUIState.Loaded(...)` construction in the tests compiling untouched.

### Phase 2 — the row says why, and opens nothing

`ChatRoomDetailScreen.kt:1381`:

```kotlin
/**
 * A group's Wavelength wallet on one network: a test network says its coins have no value, and
 * a network with no onchain wallet behind it says that instead and opens nothing, since that is
 * where this wallet's money leaves to.
 */
@Composable
internal fun WavelengthWalletRow(chain: Chain, hasOnchainWallet: Boolean, onOpen: () -> Unit) {
    val name = GroupWalletStatementEvent.chainName(chain)
    SignedWorkRow(
        icon = Icons.Default.Waves,
        headline = stringResource(Res.string.group_wavelength_wallet),
        supporting = when {
            !hasOnchainWallet -> stringResource(Res.string.group_wavelength_needs_an_onchain_wallet)
            chain.isMainnet() -> stringResource(Res.string.group_wavelength_on_s, name)
            else -> stringResource(Res.string.group_wavelength_test_network_s, name)
        },
        enabled = hasOnchainWallet,
        onOpen = onOpen,
    )
}
```

The reason wins over the network line. A row that is disabled has one thing to say, and the network
is in the headline's own screen once it opens; this is the same ordering the Lightning row uses.

And the call site, `ChatRoomDetailScreen.kt:512`:

```kotlin
items(GroupWavelengthRollout.NETWORKS, key = { "wavelength:" + GroupWalletStatementEvent.chainName(it) }) { chain ->
    WavelengthWalletRow(chain, chain in chatRoomDetailUIState.wavelengthOnchainChains) { … }
}
```

The comment above it (`:509-511`) is replaced — the claim about signet is no longer true and the
behaviour it justified is what this change removes:

```kotlin
// The group's Wavelength wallet, a row per network. Its coins leave to the group's onchain
// wallet on the same network, so until the group has opened one there is nothing behind the
// row: it stays, disabled, and says what it is waiting for -- as the Lightning row above does.
```

New string in `composeApp/src/commonMain/composeResources/values/strings.xml`, beside
`group_wavelength_wallet` (`:1329`), sentence case:

```xml
<string name="group_wavelength_needs_an_onchain_wallet">A Wavelength wallet is only available once the group has an onchain wallet.</string>
```

**Done when** the row is dim and chevron-less with no wallet behind it, and unchanged with one.

### Phase 3 — the screen behind it stops saying "Lightning"

`open_the_group_s_onchain_wallet_first` (`strings.xml:570`) is read by three screens, and on two of
them it is wrong:

| site | today | after |
|---|---|---|
| `GroupLightningWalletScreen.kt:205` | correct | unchanged |
| `GroupWavelengthWalletScreen.kt:293` | says *Lightning* | a Wavelength string |
| `IcebergCeremonyScreen.kt:164` | says *Lightning* on both purposes | picks on `isWavelength`, as its two neighbouring branches already do |

Add one string and leave the Lightning one alone, so no Lightning assertion moves:

```xml
<string name="open_the_group_s_onchain_wallet_first_wavelength">A Wavelength wallet's money leaves to the group's onchain wallet. Open that first.</string>
```

`IcebergCeremonyScreen.kt:164` becomes
`stringResource(if (isWavelength) Res.string.open_the_group_s_onchain_wallet_first_wavelength else Res.string.open_the_group_s_onchain_wallet_first)`,
matching the two `when` branches under it.

This phase is not strictly required to disable the row, and it is in the same change because the
row's reason and the screen's reason are one sentence read twice: a member who gets past the row
through a deep link, or who opened the screen before the group's wallet was read, should be told the
same thing the row would have told them.

**Done when** no Wavelength screen names Lightning. `grep -rn "open_the_group_s_onchain_wallet_first\b"`
should show only `GroupLightningWalletScreen` and the ceremony screen's `else`.

### Phase 4 — tests

**`GroupSignedWorkRowsJvmTest.kt`** — the two Lightning row tests mirror directly, and its
`onchainWallet` fixture is already built on a chain (`GroupLightningRollout.NETWORK`); parameterise it
on the chain so a Wavelength one can be built on `GroupWavelengthRollout.NETWORKS.first()`:

- `the Wavelength wallet's row opens it` — state with the chain in `wavelengthOnchainChains`, assert
  the row shows `On mainnet`, click, assert `GroupWavelengthWalletRoute(activeUserPublicKey, chatRoomId, "mainnet")`.
- `the Wavelength wallet's row waits for an onchain wallet` — empty set, assert the row shows
  *"A Wavelength wallet is only available once the group has an onchain wallet."*, click, `assertNull(opened)`.
  This is the test that fails before Phase 2 and passes after, and the one that would have caught the
  gap when Phase 2 of the UI note was called done.
- Both take the same `if (!GroupWalletRollout.ENABLED || !GroupWavelengthRollout.ENABLED) return`
  guard as their Lightning counterparts, since the rows are gated.
- A row per network: with two chains in `NETWORKS` and one in `wavelengthOnchainChains`, one row is
  live and the other is not. Worth writing even while `NETWORKS` is one long — it is the test that
  holds R1's chain-keying in place, and it is the only place the per-chain behaviour is visible.

**`GroupWavelengthWalletScreenJvmTest.kt:48,:56`** — the two existing row tests gain the new argument.
`:48` (signet, "its coins have no value") and `:56` (mainnet, "On mainnet") both pass
`hasOnchainWallet = true`, which is what they were asserting all along.

**The string catalogue** — nothing to add to `StringCatalogueJvmTest`: neither new string carries an
escape or a format argument.

**Done when** `./gradlew :composeApp:jvmTest` passes, and the new disabled-row test fails when
`enabled = hasOnchainWallet` is reverted. Check that second half by hand: a gate test that passes
against the ungated row is not a test.

### Phase 5 — the audit and the checks

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileKotlinJvm :composeApp:jvmTest
```

Nothing here should move a budget: two new `strings.xml` entries in sentence case, no `.dp`, no
`Color(0x…)`, no new `clickable`, no new `contentDescription`. The 38% alphas the disabled row uses
are `SignedWorkRow`'s own and are M3's disabled state, which is the stated exception. Confirm rather
than assume — the audit is cheap and `BUDGET_TITLE_CASE` is 0, so a capitalised word in either new
string fails `check`.

No regtest run: nothing here touches the node, co-signing or sweeps.

### Phase 6 — the documentation

`docs/group-wavelength-ui.md`:

- The screens table's first row (`:153`) is satisfied; its phase column can say so.
- U8 (`:143`) gains R4's condition: a non-mainnet chain returning to `NETWORKS` needs a route to a
  group onchain wallet on that chain that does not go through the Wavelength row, which is now
  disabled without one.
- The "what is missing" table's first row (`:97`) still says `ENABLED` is off and nothing reads it,
  which has not been true since the rollout; correct it while the file is open.

**Done when** the UI note describes the row that exists, and the signet condition is written where
whoever lifts that gate will read it.

## What this does not do

- It does not change `GroupWavelengthWalletUIState.NeedsOnchainWallet` or its view model. The screen
  keeps its own gate: a deep link, a stale state and a wallet closed between the read and the tap all
  reach it, and the row is not a security boundary.
- It does not gate on anything beyond the onchain wallet. A group too small for four signers, a
  missing key, an unknown operator and changed terms are all states *on* the screen
  (`NeedsKey`, `Blocked`), where they have room for the reason and a way forward. Only the onchain
  wallet comes to the row, because it is the one condition the group's own screen can already answer
  and the one the Lightning row sets a precedent for.
- It does not hide the row. Everyone in the room sees that the group could have this wallet, which is
  what the UI note's screens table asks for.
