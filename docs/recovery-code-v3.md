# Recovery codes

<!--
  Giza's note, as Giza wrote it at a5a802c5 (docs/recovery-code-v3.md there). It is kept as the
  reasoning behind recovery codes, and Dedun's port follows its phases. Read "Giza" as the app it
  was written for: where Dedun differs, docs/giza-second-batch.md, Phase 2, says so.
-->

> **In Dedun the code also backs the wallet.** Giza keeps no wallet, so in Giza a code is a key
> backup and nothing more. In Dedun the code's 24 words start the profile's Lightning node, exactly
> as typed words do. So a code restores money, and the screens say so (D10). The npub, the profile
> id and the node id come from lightning-kmp's `LocalKeyManager` on `NodeParamsManager.chain`, not
> from Giza's `PhraseKeys`. `RecoveryCodeKeysTest` pins mainnet's, and the node id with them.
> Landing's `CreateProfileRoute` keeps `withWallet`, and the guard against phrases made from random
> bytes is `docs/scripts/phrase-audit.sh`, not Giza's wallet audit.

How a new Giza profile comes to be backed by a nine-block recovery code instead of twelve
words, what the code derives, where it is kept, and why every phrase already on a device
keeps working exactly as it does.

Read this after [multiple-profiles.md](./multiple-profiles.md) and
[giza-wallet-removal.md](./giza-wallet-removal.md). It inherits from the first that the
credentials file is the list of profiles and a backup is *attached* to the entry whose key
it derives; from the second, that a recovery phrase is a key backup and not a wallet, and
that what it derives is pinned byte for byte. A recovery code is a 24-word phrase in another
notation: its key is BIP39 entropy, so it derives through `PhraseKeys` as it stands, and
nothing here changes what a phrase derives.

**Built**, phases 1–8, one commit each, in the order given (Phase 3 is two: the library
commit on the submodule's `claude/recovery-codes` branch, and the bump). Phases 1–6 make a
code something Giza can read, restore and show; only Phase 7 makes it something Giza
*hands out*. That order is the spec's own safety rule (§11.1) applied to a single app: no
build may give a person a code that the same build cannot restore, or cannot show them
again. The phases are kept as written because they are the reasoning; where the
implementation chose differently the table below says so:

| what the plan said | what it turned out to be |
|---|---|
| `RecoveryCodeParse` beside the type, with a `Kind` | `RecoveryCode.Parse`, nested, and a public `RecoveryCodeProblem` enum the internal `RecoveryCodeFormat` shares — a public `Invalid` cannot carry an internal type |
| a typo is located when the checksum fails | whatever the first failure, for a 36-symbol input: a mistyped version symbol (`LC…`) fails normalisation before it reaches the checksum, and the spec's own vectors locate it |
| every v2 and v3 `private_key` row through the type | the v3 rows through `RecoveryCode.rootKey`; the v2 rows through the same HMAC called directly, because `RecoveryCode` will not hold a version 2 code |
| `writeRecoveryCode` returning `Written(id)` | `Written(id, isNew)`. Attaching a code to the phrase profile its words already made writes the code alone and skips the new-profile preparation, which would otherwise reset that profile's preferences |
| codes attached in the listing | in `StoredIdentity.merge`, last, with `parseCodes` dropping a stored code that does not parse; `DefaultIdentity` reads the codes too, so a background sync runs as the right kind |
| a refusal "gains an optional suggestion" | `Refused(problem, suggestion)`, and the sign-in view model holds it as `typoSuggestion` with `acceptSuggestion()`; the screen shows the corrected code itself above a *Use this code* button, not only the block and character |
| a checksum failure with no unique suggestion | the same `RecoveryCodeTypo` problem, without a suggestion: the sentence is "There's a typo in this code", which is what the spec's §11.4 asks for |
| `RecoveryPhraseViewModel` becomes `RecoveryBackupViewModel` | a `RecoveryCodeViewModel` and `RecoveryCodeScreen` of their own; the phrase screen is unchanged, and the code screen's error state links to it, since every code profile is also its words in `seed.dat` |
| "after *I have written it down*", two blocks | the two blocks *are* the confirmation: a match records the backup, and there is no checkbox to tick past it. Skipping is not checking, and the reminder stays |
| the words "behind a reveal of their own" | under a revealed code only, derived from it in memory: no second read of the keystore |
| a code round trip in `MultipleProfilesRoundTripJvmTest` | there, A is now made by `recoveryCodeProfileWriter` and listed as `RecoveryCode`; the empty-device round trip — the code, then its words — is in `IdentityWriterJvmTest`, where the files can be deleted between writes |

Not verified here: the iOS target was not compiled, and the screens were not run on a
device. The library commit is on a local branch of the submodule and has to be pushed with
this branch before anything else can build it.

## The format

The recovery code is Muun's, at version 3, as specified on the
[`recovery-code-v3-checksum`](https://github.com/kngako/libwallet/tree/recovery-code-v3-checksum)
branch of `kngako/libwallet`, at `7d58eb2e`. The normative text is that branch's
`recoverycode/SPEC.md` and its vectors are `recoverycode/testdata/v3_vectors.json`; this
note only restates what Giza depends on.

```
LBZC-MYTK-E9P3-7C7B-AHJN-KX79-22CY-WAY7-K7QZ
└┬┘└──────────── 30 random symbols ────────┘└┬┘
 L + version B (= 3)                    checksum block
```

- **Alphabet** `ABCDEFHJKLMNPQRSTUVWXYZ2345789`: thirty symbols, none of `0 1 6 G I O`.
- **Shape** nine blocks of four, dashes between: 36 symbols, 44 characters.
- **Entropy** 30 random symbols, 30·log₂30 ≈ **147 bits**. More than a twelve-word
  phrase's 128.
- **Checksum** block 9 is two weighted sums of symbols 0–31 mod 887, each written as two
  symbols. Minimum distance 3: every one- and two-symbol error is caught, any
  transposition is caught, a random string passes about once in 810,000, and a single
  wrong symbol can be *located*.
- **Key** `HMAC-SHA256(key = "muun:rc", message = canonical code)`, where the canonical code
  is the 44-character string **with its dashes**. Thirty-two bytes. The spec calls it a
  secp256k1 scalar; here it is BIP39 entropy, the root everything else is derived from.

Versions 1 and 2 exist (eight blocks, no checksum). Giza never made either, so neither can
be a Giza profile; they are recognised in order to be refused by name (Phase 5).

### Why a code and not a phrase

A phrase is twelve words from a list of 2048 in one language, checked by four bits that say
*something* is wrong and never *what*. A code is shorter to write by hand, has no wordlist
to get wrong, and its checksum can say "block 5, character 2: did you mean X?". That last
property is the one a person restoring from a paper in a drawer actually feels.

## The one decision: what the root derives

**Decided: B, the code's key is BIP39 entropy.** A code is a 24-word phrase written in nine
blocks.

The spec stops at thirty-two bytes. "Everything else is derived from it" leaves open *how*,
and whatever is chosen is permanent: it decides every npub a code will ever produce. Three
shapes were considered.

| | the code's 32 bytes are… | nostr key | profile id | further keys |
|---|---|---|---|---|
| A | a **BIP32 seed** | `m/44'/1237'/0'/0/0` | hash160 of `m/50'/0'` | any new path |
| **B** *(chosen)* | **BIP39 entropy** → 24 words | via `PhraseKeys` | via `PhraseKeys` | any new path |
| C | the **nostr secret** itself | the 32 bytes | hash160 of the x-only key | none |

```
code ─HMAC-SHA256("muun:rc")→ 32 bytes ─BIP39→ 24 words ─PhraseKeys→ nostr key, profile id
```

**B**, because it makes every code a NIP-06 phrase. Thirty-two bytes of entropy and their
eight checksum bits are exactly twenty-four words, and from the words on everything is
`PhraseKeys` as it stands: PBKDF2 with the empty passphrase, BIP32, the same two paths. So:

- **The code is the only thing to back up.** The words are computed from it whenever they
  are wanted; nobody has to write both down.
- **The profile can leave Giza.** The 24 words import into any client that reads NIP-06
  phrases, and into Bennu, and land on the same npub. Under A the only way out was the nsec.
- **One derivation, not two.** A code adds a step *in front of* `PhraseKeys` rather than a
  second door into BIP32 beside it, and every future path is shared by construction.

What it costs, and the plan answers below:

- **The words do not give back the code.** The HMAC runs one way. A person who keeps only
  the words can restore the profile — same npub, same id — but Giza cannot show them a code
  for it. Phase 6 shows the words as an export for other apps, not as a second backup, and
  says so.
- **A code profile and a phrase profile can now be the same profile.** Signing in with a
  code's 24 words lands on the code's npub and id. Phase 4 decides what each write means
  when the other is already here.

**A** is the phrase's tree with a different root: `DeterministicWallet.generate(rcKey)` and
the same two paths. It is one step shorter and its only loss is the export. Rejected for
that loss.

**C** is the simplest and closes the door: the nostr key is the root, so nothing can ever be
derived beside it, and "everything else derived from the root" becomes "there is nothing
else". Rejected.

The decision is pinned in Phase 2 by vectors, and the vectors are the decision: once one
code's npub is in a test, changing B to anything else is changing who a person is.

## What does not change

- **A phrase derives what it derives.** `PhraseKeys`, its two paths and the
  `ForkCompatibilityTest` vectors stay as they are. Every profile made from a phrase, on
  this device or restored from Bennu, keeps its npub and its id.
- **A phrase can still be restored.** Sign-in reads twelve words for as long as anyone holds
  them. Only making a phrase *from random bytes* goes away (Phase 7).
- **`seed.dat`'s format.** Not one byte. A code profile's 24 words go in it as a phrase's
  twelve do (Phase 3), so every build, older ones included, reads them correctly.
- **The `mantra/…` tags.** They hash the nostr key, not the root, so a code profile's DKG
  host key and shared-key tweak are computed exactly as a phrase profile's are.
- **The disappearing-messages rule, the wallet refusal, the brand rule.** A recovery code is
  a key backup. Nothing it touches is a wallet, and no user-facing string says "wallet",
  "seed", "sats" or "Muun". The format is credited in this note and in the code's KDoc.

## Phase 1 — the codec

A pure-Kotlin port of the spec into `commonMain`, under
`mobi/giza/compose/identity/recoverycode/`. gomobile's libwallet is not an option: it has no
JVM desktop or iOS-simulator build Giza could link on every target, and the format is
small enough that a port tested against the spec's own vectors is the safer dependency.

### The type

```kotlin
/** A canonical v3 recovery code: 44 characters, nine blocks, checksum verified. */
@JvmInline value class RecoveryCode private constructor(val canonical: String) {
    val blocks: List<String>                       // nine, for display
    fun rootKey(): ByteVector32                    // HMAC-SHA256("muun:rc", canonical)
    override fun toString() = "RecoveryCode(***)"  // never prints the code

    companion object {
        fun parse(input: String): RecoveryCodeParse          // normalize, then validate
        fun generate(random: (Int) -> ByteArray = RandomInstance::bytes): RecoveryCode
    }
}

sealed interface RecoveryCodeParse {
    data class Valid(val code: RecoveryCode) : RecoveryCodeParse
    data class Typo(val block: Int, val index: Int, val suggested: RecoveryCode) : RecoveryCodeParse
    data class Invalid(val kind: Kind) : RecoveryCodeParse   // Length, Characters, Checksum
    data class UnsupportedVersion(val version: Int?) : RecoveryCodeParse  // 1, 2, or unknown
}
```

Beneath it, internal and one function per spec section so that each can be tested against
its own vector table: `normalize` (§5), `version`/`validate` (§4, checks **in the spec's
order**, since the vectors assert which error comes first), `locateTypo` (§6, the
exhaustive search — 36 × 30 candidates is nothing, and the closed form is an optimisation
the spec makes optional), `blockCount` (§8) and `checksum`.

`Typo` is a *suggestion*. Nothing in the codec or above it applies one; the type exists so
that the only way to get a `RecoveryCode` out of a typo is for a person to accept it
(Phase 5). An input with two errors can sit one symbol from a different valid code, and
applying that silently would derive somebody else.

### Generation

Thirty symbols by rejection sampling over single random bytes: reject below
`256 % 30 = 16`, then take `byte % 30`. That threshold is the one the fork fixed
(`75e43b45`): the arc4random idiom it replaced rejected nothing in Go's signed arithmetic,
and the first sixteen symbols came up 9/256 of the time instead of 8/256. The port takes
the fixed rule and a test that drives `generate` with an injected byte source to show that
bytes 0–15 are skipped.

A generated code whose root key is zero or not below the curve order is discarded and
drawn again. The probability is about 2⁻¹²⁸; the rule exists so that every code Giza makes
is also a valid key to every other implementation of the spec.

Randomness is quartz's `RandomInstance`, which the bare-key writer and `AdminSeal` already
use, so a code is exactly as random as an nsec made here.

### Tests

- `RecoveryCodeVectorsJvmTest` reads `v3_vectors.json`, copied verbatim into
  `composeApp/src/jvmTest/resources/recoverycode/` with a `README` naming the fork commit,
  and runs all 77: every `validate` row's validity, version and error kind; every
  `normalize` row; every `locate` row's position and correction; every v2 and v3
  `private_key`. The two v1 key rows are skipped by name — they need scrypt and a salt, and
  Giza will never derive a v1 key.
- `RecoveryCodeTest` in `commonTest`, so it also runs on Android and iOS: five vectors
  inline, the dash rule (the key of the code without dashes is *not* the key of the code),
  a round trip of `generate → canonical → parse`, and `toString` not containing the code.

## Phase 2 — what a code derives

One function, because everything after the words already exists:

```kotlin
object RecoveryCodeKeys {
    /** The 24 words [code] is: its root key read as BIP39 entropy. */
    fun words(code: RecoveryCode): List<String> =
        MnemonicCode.toMnemonics(code.rootKey().toByteArray(), MnemonicLanguage.English.wordlist())
}
```

The nostr key and the profile id are `PhraseKeys.nostrPrivateKey(words(code))` and
`PhraseKeys.profileId(words(code))`. `PhraseKeys` does not change, and neither do its paths
or its empty passphrase, so a code's words typed into any NIP-06 client land on the npub Giza
shows.

### Tests

- `ForkCompatibilityTest` unchanged, and passing.
- `RecoveryCodeKeysTest`, pinning three codes from the spec's vectors to their root key, their
  24 words, nostr secret, npub and profile id; asserting the words pass
  `MnemonicCode.validate`; and asserting that `PhraseKeys` on the pinned words gives the pinned
  npub, which is the property the export depends on. The words and npubs are **Giza's** — no
  other app turns a recovery code into a phrase — so they are cross-checked once against an
  independent derivation: `docs/scripts/recovery-code-keys.py`, standard library only (hmac,
  hashlib's `pbkdf2_hmac`, a minimal BIP32), taking the BIP39 English wordlist as an
  argument, in the spirit of the fork's `gen_vectors.py`. The script is kept, so that the
  vectors can be regenerated by something that is not the code under test.

`CLAUDE.md`'s "Giza keeps no wallet" section gains a line: *a recovery code's key is BIP39
entropy; its 24 words derive through `PhraseKeys`, pinned in `RecoveryCodeKeysTest`*.

## Phase 3 — where a code is kept

In two places, each holding what only it can:

- **`seed.dat` holds the code's 24 words** under the profile id, exactly as it holds a
  phrase's twelve. The format does not change, and nothing reading the file can tell a code's
  words from typed ones.
- **`recovery-codes.dat` holds the code**, beside `seed.dat` and `nostr-credentials.dat` in
  `SeedManager.getDatadir`: a JSON map of profile id to canonical code, encrypted under
  `KeyStoreNames.KEY_NO_AUTH` like the other two, written through the same
  temp-file-and-rename the seed writer uses.

Both, because each can be lost without the other. The words cannot give back the code — the
HMAC runs one way — so the backup screen needs the code kept. And a code kept alone would
need every reader of `seed.dat` to learn about a second file before it could list, attach,
repair or refuse a duplicate of the profile. With the words in `seed.dat`, all of that is
already done: `StoredIdentity.merge` attaches the seed to its credential, `SeedCredentials`
repairs a missing credential, `writeMnemonic` refuses the same words twice,
`forgetNostrCredential` refuses to forget a key a seed derives. The code file adds one thing,
the code, and one rule:

**A code must derive the words beside it.** On read, a code whose `words(code)` is not
`seed.dat`'s entry for its id is dropped with a log line, and the profile lists as the phrase
it has. Two files that disagree mean one of them is wrong, and the words are the one every
build reads.

### What an older build sees

The phrase profile, with the right npub and id, and — on the phrase screen — the 24 words,
which restore it anywhere. Nothing is hidden and nothing is misread; only the code is not
shown until the newer build is back.

### Why not the code in `seed.dat`

`seed.dat` is `Map<WalletId, List<String>>`, and there were two ways to fit a code in it:

- **As a one-element list.** `["LBZC-…-K7QZ"]` reads without complaint in every build. And
  then `MnemonicCode.toSeed` hashes it — it validates nothing — and `SeedCredentials.reconcile`
  writes the credential of a key nobody owns. A downgrade would put a stranger's profile in
  the switcher and nothing on the screen would say why.
- **As a new inner version** (`MULTIPLE_SEED_VERSION = 4`, typed entries). An older build
  throws on the version byte, `loadAndDecryptOrNull` returns null, and every phrase profile on
  the device is gone from the listing too.

### The library change

In the `lightning-kmp-app` submodule, beside `NostrCredentialManager` and in its shape:
`EncryptedRecoveryCodes` (encrypt, decrypt, serialize, with a private serializable mirror)
and `RecoveryCodeManager` (`loadAndDecrypt`, `loadAndDecryptOrNull`, `writeToDisk`; a missing
file is an empty map, an unreadable one is null). The library stores **strings**: it does not
know the format, and validation stays in the app where the codec is. One commit on a branch
of the submodule, bumped into the app by this phase's commit, pushed with this branch.

### Tests

In the library: `EncryptedRecoveryCodesTest` (round trip, a tampered ciphertext refused) and
`RecoveryCodeManagerJvmTest` (absent file → empty, write then read, a failed write leaves the
previous file). In the app: nothing yet, because nothing writes it.

## Phase 4 — the code as an identity

Most of this phase is already done by Phase 3's choice: a code profile *is* a phrase profile
to everything that reads `seed.dat`. What is left is to know that it has a code.

### The kind

```kotlin
enum class IdentityKind { Mnemonic, RecoveryCode, NostrSecret, NostrPublic }

data class RecoveryCode(                       // in StoredIdentity
    val userWallet: UserWallet,                // the 24 words, from seed.dat
    val code: mobi.giza.compose.identity.recoverycode.RecoveryCode,
    val privateKey: PrivateKey,                // the credential's
) : StoredIdentity
```

A new kind rather than a nullable `code` on `Mnemonic`, because `StoredIdentity` is sealed and
the compiler then lists every `when` that has to decide what a code profile is — the trick
that found the startup screen's missing branch in the npub plan. There are roughly seventeen
such sites across eleven files today. Nearly all will answer *the same as a phrase*, and for
those a property says so once:

```kotlin
/** A backup this device can show again: a phrase or a code. */
val IdentityKind.hasBackup: Boolean get() = this == Mnemonic || this == RecoveryCode
```

`Mnemonic` keeps its name. Renaming it is a churn commit of its own and buys nothing this plan
needs.

### Listing

`StoredIdentity.merge` takes a third map, the codes, and does what it does today first: seeds
attached to credentials, missing credentials derived from the words. Then a `Mnemonic` whose id
has a code that derives its words becomes a `RecoveryCode`. `SeedCredentials.reconcile` does
not change — the words in `seed.dat` are what it repairs from.

`SovereignWalletViewModel.listIdentities` reads `recovery-codes.dat` beside the other two. An
unreadable codes file is logged and listed without codes: every profile in it is still a
phrase profile with the right key, so nothing is hidden and nothing can be forgotten that
should not be. The backup screen says it could not read the code (Phase 6).

### Writing

`IdentityWriter.writeRecoveryCode`, suspending and result-shaped like `writeNostrKey`, in the
order `writeMnemonic` established and for its reason, with the code last: the credential, then
the words, then the code. A crash after the words leaves a phrase profile that signing in with
the code again completes.

| already on the device for the derived key | result |
|---|---|
| nothing | `Written(id)` — a new profile, its words and its code |
| a `Public` entry | `Written(id)` — the read-only upgrade landing on a backup |
| a bare `Secret` | `Written(id)` — the key acquiring the code that derives it; the bare key's preference files are deleted, as `writeMnemonic` does, because the id changes |
| the code's 24 words, no code | `Written(id)` — the code attached to the phrase profile it already is; same id, same preferences |
| this code | `AlreadyExists(id)` |
| one of the files unreadable | `CannotLoadKeys`, nothing written |

The other writers need nothing. `writeMnemonic` given a code profile's 24 words finds the id in
`seed.dat` and answers `SeedAlreadyExists(id)`, as it would for any phrase already here;
`writeNostrKey`, `writeNostrPublicKey` and `forgetNostrCredential` find the key among the seeds'
and refuse as they do today.

### Starting

`SovereignWalletStartupScreen`, `WalletsSelector`, `DefaultIdentity` and `Identity` get their
arm, which for every one of them is the phrase's arm. The profile row says "Recovery code"
where a phrase profile says "Recovery phrase".

### Tests

`StoredIdentityJvmTest` and `IdentityWriterJvmTest` gain the code rows of every table above,
and a case where the code file disagrees with the words (listed as a phrase). A downgrade test
reads `seed.dat` and the credentials with the codes file absent and asserts the profile lists
as a `Mnemonic` with the same npub and id.

## Phase 5 — signing in with a code

### Recognising one

`CredentialParser.parse` checks for a code **before** it splits on whitespace, because a code
typed by hand arrives as `LBZC MYTK …` as often as with dashes, and the words branch would
call it a twelve-word phrase that failed its checksum.

The test is shape, not content: after trimming, the input is eight or nine groups of exactly four letters or digits separated by dashes or
whitespace, or a single run of 32 or 36 of them. A BIP39 phrase has 12, 15, 18, 21 or 24
groups and its words are three to eight letters, an nsec and an npub have their prefixes and
hex is 64 characters, so the shapes do not overlap. A code that matches the shape is
committed to the code branch: its problems are reported as a code's problems.

```kotlin
data class RecoveryCode(val code: …RecoveryCode, override val nostrPublicKey: HexKey) : SignInCredential

enum class CredentialProblem { …,
    InvalidRecoveryCode,          // length or characters
    RecoveryCodeTypo,             // checksum — carries the suggestion, see below
    UnsupportedRecoveryCode,      // v1 or v2: "Giza only makes nine-block codes"
}
```

`Refused` gains an optional `suggestion: RecoveryCodeParse.Typo?`, set only for
`RecoveryCodeTypo` and only when the search found exactly one correction.

### The typo

The spec's integration note §11.5 is the design: the sign-in screen shows *"Block 5,
character 2 looks wrong. Did you mean H?"* with the corrected block highlighted and a **Use
this** button. Accepting it replaces the field's text with the corrected code and runs the
parse again, so the confirm step — the derived npub, the moment a person can see whether
this is who they meant to be — still stands between the suggestion and the disk. Not
accepting leaves the field as typed. Nothing is ever corrected without a press.

A checksum failure with no unique suggestion says *"There's a typo in this code. Check each
block."* — distinct from *"That is not a recovery code"*, which is the spec's §11.4.

### Copy

The field's label, placeholder and hint learn the third shape:

| string | becomes |
|---|---|
| `recovery_phrase_nsec_or_npub` | Recovery code, phrase, nsec or npub |
| `twelve_words_nsec1_or_npub1` | LB… code, twelve words, nsec1… or npub1… |
| `enter_a_recovery_phrase_or_an_nsec_to_sign_in` | Enter a recovery code, a recovery phrase or an nsec to sign in. |
| new `recognised_as_a_recovery_code` | Recognised as a recovery code. |

All sentence case, all in `strings.xml`, the block and character numbers as format
arguments.

### Writing

`SignInToProfileViewModel` gains the fourth writer, through the shared write-and-map the
other three use, onto `IdentityWriter.writeRecoveryCode`. `AlreadyExists` offers to switch to
the profile, as it does for the others.

A code's 24 words need nothing new: they are a phrase, and sign-in already reads 24-word
phrases. On a device where the code profile is, they are `SeedAlreadyExists` and offer the
switch; on one where it is not, they make the phrase profile, and the code can be attached
later by signing in with it.

### Tests

`CredentialParserJvmTest` gains rows for: a canonical code; the same code lower-case, with
spaces, with no separators; one wrong symbol (refused with the right suggestion); two wrong
symbols (refused, no suggestion, or a suggestion that is *not* the original — the vector
table has such a case and it is the reason the suggestion is never applied); a v2 and a v1
code (unsupported); eight groups that are not a code; and a twelve-word phrase whose words
happen to be four letters (still a phrase — twelve groups). `SignInToProfileViewModelJvmTest`
signs in with a code, twice, and counts one profile; then with the code's words on a fresh
device followed by the code, and counts one profile with the code attached.

## Phase 6 — showing the code

### The backup screen

`RecoveryCodeScreen` beside `RecoveryPhraseScreen`, sharing its scaffolding — the reveal
that is a separate press, the keystore read on reveal and nowhere else, the two consents,
`ErrorState` when the device holds no code for this profile. What differs is the body:

- Nine blocks in a three-by-three grid, numbered, in a monospace face, at a size meant to
  be copied by hand. A grid rather than one line, because a person copying writes block by
  block and checks block by block, and because §11.3 asks that all nine be written down:
  a ninth block on a line of its own is the one that gets dropped.
- A sentence under it that the ninth block is part of the code, not a separator.
- No copy-to-clipboard. The phrase screen has none either, for the same reason.

`RecoveryPhraseViewModel` becomes `RecoveryBackupViewModel`, producing `Revealed.Phrase(words)`
for a phrase profile and `Revealed.Code(code)` for a code one. The backup-done preference is
keyed by profile id and is reused as it is. If the code file could not be read, the screen says
so with `ErrorState` and offers the words instead.

### The words, for other apps

Below the code, behind a reveal of its own: **Use this profile in another app**. It shows the
24 words, on the phrase screen's layout, under a sentence that says what they are for and what
they are not:

> These 24 words are this profile for apps that take a recovery phrase. Your recovery code is
> still your backup — these words can't give it back.

This is the reason the code's key is BIP39 entropy (see the decision), so it is in the plan
and not left for later. It is not part of the backup flow: the confirmation below asks for
blocks of the code, never words, and the reminder card clears on the code alone.

### Confirming the backup

After "I have written it down", the screen asks for **two blocks chosen at random** (never
block 1, which starts `LB` on every code) and checks them. This is new — the phrase screen asks only for
a checkbox — and it is here because the code makes it cheap: two short fields, and a typo in
them is a typo in the paper. Skippable, and a skip leaves the backup reminder showing.

### Where it is reached

`KeyRecoveryScreen` offers "Show recovery code" for a code profile where it offers "Show
recovery phrase" for a phrase one, from the same `hasBackup` branch; a new
`RecoveryCodeRoute` pushes the screen with a `NavigateBackButton`. The reminder card —
`you_have_not_backed_up_your_recovery_phrase` — gains a code sibling, and the screen's
explanatory text
(`the_recovery_phrase_sometimes_called_a_seed`) gets a code version that says what nine
blocks are and nothing about seeds.

### Tests

A compose test per state (hidden, revealed, no code, keystore failure), the grid's nine
blocks found by content description, and the confirmation's right and wrong answers. The
M3 audit runs: 0 spacing literals, 0 colours, 0 title-case strings, every target 48dp.

## Phase 7 — new profiles get a code

The phase that changes what a person is given. Everything before it is reachable only by
someone who already holds a code, which before this phase is nobody — so up to here the
branch can be released and nothing a person holds has changed.

After the first profile is made, the backup reminder shows as it does for a phrase today,
and leads to Phase 6's screen. Opening that screen straight after creation instead is a
one-line change to the create flow's tail; it is left as the phrase's flow behaves, so that
the two kinds are not taught differently.

- `recoveryCodeProfileWriter()` in `NewProfileWriters.kt`: `RecoveryCode.generate()`, then
  `writeRecoveryCode`, then `NewProfile(npub, id)`. Suspending throughout; no
  `WritingSeedState` and no one-second delay, which belonged to the old create flow's
  animation.
- `seedProfileWriter()` is deleted. Nothing in the app makes words from random bytes after this
  commit; the only words it makes are a code's.
  The tests that used it to make a phrase profile (`MultipleProfilesRoundTripJvmTest`) build
  one through `IdentityWriter.writeMnemonic` directly, because phrase profiles still have to
  be *tested* as long as they can be restored.
- `CreateProfileRoute.withWallet` becomes `withBackup`, and the Landing path passes `true`.
  The KDoc that says "the first profile on a device is the one the wallet will belong to"
  becomes "the first profile on a device is the one with a backup to write down".
- The switcher's "add a profile" path stays a bare key, as
  [multiple-profiles.md](./multiple-profiles.md) Phase 5 decided — wanting another profile
  is not wanting another thing to write down. That is a product decision and not this plan's;
  flipping it is one argument.
- A guard so it stays this way: `docs/scripts/wallet-audit.sh` fails on
  `MnemonicCode.toMnemonics` anywhere in `composeApp/src/*Main` but
  `identity/recoverycode/RecoveryCodeKeys.kt`, where the words come from a code. Tests may
  still make words from random bytes.

### Tests

`MultipleProfilesRoundTripJvmTest` creates its first profile through the new writer and
asserts it lists as `IdentityKind.RecoveryCode`. A round trip — create with a code, forget the
device's files, sign in with the code — lands on the same npub and the same id, and the
preferences written before are read after. A second round trip signs in with the 24 words
from the export instead and lands on the same npub and id.

## Phase 8 — the documents

- `docs/README.md` gains the row for this note.
- `CLAUDE.md`'s "Giza keeps no wallet" section: *a new profile is backed by a v3 recovery
  code, whose key is BIP39 entropy: its 24 words live in `seed.dat` as a phrase and derive
  through `PhraseKeys`, the code beside them in `recovery-codes.dat`. A phrase is restored and
  never made from random bytes. Pinned in `ForkCompatibilityTest` and `RecoveryCodeKeysTest`.*
- [giza-wallet-removal.md](./giza-wallet-removal.md) and
  [multiple-profiles.md](./multiple-profiles.md) each get a superseded-by line where they say
  a new profile from Landing is made of twelve words.
- This note's header becomes **Built**, with the table of where the code chose differently,
  in the house manner.

## What is out of scope

- **Reading codes in Bennu.** Bennu reads phrases, so a code profile goes to Bennu, or to any
  NIP-06 client, by its 24 words (Phase 6), wherever that app's restore takes a 24-word
  phrase. Teaching Bennu to read the code itself is Bennu's change. A code profile can talk to
  every Bennu peer either way — the protocol sees an npub, not where it came from.
- **Converting a phrase profile to a code.** Impossible without changing the npub: a code
  gives words, but words do not give a code. A person who wants a code makes a new profile.
- **A code that also guards money.** A Muun wallet's own v3 code is the same shape, and if one
  is typed into Giza it will become a profile. Giza stores the code it is given, so that would
  put the wallet's code on this phone. The sign-in copy cannot tell the two apart and does not
  try; the backup screen's text says the code is *this profile's* and should be used for
  nothing else.
- **Versions above 3.** The codec refuses an unknown version by name. A v4 is a new plan.

## Risks

| risk | answered by |
|---|---|
| the derivation is chosen wrong and has to change | Phase 2's vectors, written before any code is generated; Phase 7 is the point of no return |
| a downgrade misreads a code | the code never goes in `seed.dat`; its words do (Phase 3) |
| a typo suggestion derives the wrong person | never applied without a press, and the confirm step shows the npub before the write |
| the port disagrees with the spec | all 77 normative vectors, plus Giza's own derivation cross-checked by an independent script |
| a code is generated that this build cannot restore or show | Phases 1–6 land before Phase 7, in that order, on this branch |
| a person writes eight blocks | the grid, the sentence about block 9, and the two-block confirmation |
| a person keeps the exported words and not the code | the words restore the profile in full; the export's sentence says they cannot give the code back, and the backup confirmation asks for the code |
| the code file and the words disagree | the code must derive the words beside it, or it is dropped and the profile lists as its phrase (Phase 3) |
| a downgrade loses the profile | the words are in `seed.dat`, which every build reads; an older build lists the phrase profile with the same npub and id |
