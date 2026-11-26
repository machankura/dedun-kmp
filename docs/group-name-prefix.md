# A group's name starts with `&`, not `#`

A phased plan to change the mark in front of every Marmot room's name from `#` to `&`:
`#Ekklesia` becomes `&Ekklesia`. That covers rooms made from now on, rooms already on a
device, rooms a Bennu peer or another Marmot client makes, and the names the groups have
already signed into subgroup birth certificates.

**Superseded** by [group-name-braces.md](./group-name-braces.md): the mark is now drawn as
`{name}` and not stored, and `&name` rooms are stored bare from schema v55. This document is
the record of the `&` change.

**Built**, phases 0-6, one commit each on `claude/group-prefix-replacement-plan-80c110`.
The build followed the plan, with these notes:

- Phase 2 also changed `CurrentProfileKeyPackageJvmTest`. A room from another client that
  had no mark is now joined as `&made elsewhere`, which is D4.
- Phase 3's `AutoMigration(51, 52)` with a spec worked. Room generated it although the
  schema did not change, and `52.json` matches `51.json` apart from the version.
- Phase 4's line test writes the `TYPE_SUBGROUP_CERTIFIED` line through
  `ChatMessage.fromGroupEventResult`, in `SubgroupManagerJvmTest`.

`:composeApp:jvmTest` passed after each phase, with 1997 tests after Phase 5.
`ProposeConfederationViewModelJvmTest`'s "the button waits on a name" failed once in the
Phase 2 run and passed on its own. It asserts "still looking" before the lookup can finish,
so it is flaky, and this change did not cause it. **Not run:** the checks under "Checking the
work" on a device.

Written against `9db986c1` on `giza`. Read [subgroups.md](./subgroups.md) ("A name field at
the top") first. It explains why the mark is part of the name the group is welcomed with and
not added when the name is drawn, and why the rule has to be idempotent.

---

## Why

`#` already has a meaning in nostr, and this app uses it. In a note, `#word` is a hashtag:
`ContentParser` turns `#Ekklesia` into a `HashtagSegment`, and `SearchScreen` treats a
leading `#` as a hashtag search. A group's name written into a note, or typed into search,
is read as a topic and not as a group. Nothing else in the app gives `&` a meaning.

The mark still has the job [subgroups.md](./subgroups.md) gave it: it tells a person which
rows on the room list are groups (Marmot rooms) and which are conversations (NIP-17 rooms),
and it does that at the front of the row, where a truncated row still shows it. This plan
changes the character and keeps the job.

## What the code does today

These checks were run while writing the plan, so the answers below are what this plan is
built on. Phase 0 repeats them against the commit the build starts from.

- **One rule.** `MarmotGroupName` (`managers/MarmotGroupName.kt`) holds `PREFIX = "#"` and
  `of(name)`, which trims the name, strips any leading `#`s and puts one back. A blank name
  is returned unchanged.
- **Three production callers.** `MarmotGroupCreation.create` names every Marmot room it
  creates, and both admin rooms and subgroups go through it.
  `SubgroupManager.proposeBirthCertificate` names the subgroup that the parent's quorum
  signs. `SelectSubgroupAdminsScreen` shows `PREFIX` as the name field's `prefix`. No code
  anywhere *reads* the prefix: nothing calls `startsWith("#")` on a subject or compares two
  subjects, and nothing finds a room by its name.
- **Where the name ends up.**
  - In the MLS group state. Each member is welcomed with the name, and `NostrDao`
    (`processWelcome`, ~line 877) writes `group.roomName()` to `ChatRoom.subject` without
    changing it. A group made before this plan, or by a Bennu peer, arrives as `#name`. A
    group from another Marmot client arrives with no mark at all.
  - In `ChatRoom.subject`. This is the only column that holds a Marmot room's name.
    `FrostSigningSession.subject` is an enum and has nothing to do with names.
    `DkgSession.subject` holds the *bare* name a ceremony was given, before any mark.
  - In the `name` tag of a `SubgroupBirthCertificateEvent`. It is signed, so it can never
    change. Three places read it, and all three only display it: `Subgroup.name`
    (`SubgroupManager`, ~line 447), the `TYPE_SUBGROUP_CERTIFIED` line (`ChatMessage`,
    ~line 1427), and the proposal summary (`ProposedEvent`, ~line 72). No device compares
    the certified name with the room's name.
  - In transcript lines already written. `ChatMessage` ~line 1439 builds "The group made
    #X a subgroup" from the certified name and stores the finished text.
- **Interop.** Bennu still makes `#name` rooms, and a Giza member can be welcomed into one.
  A Bennu member can be welcomed into a room Giza made. Bennu does not read the mark either.
- **Tests and previews.** About 90 lines in 47 files across `commonTest` and `jvmTest`
  write `"#Ekklesia"`, `"#Polis"`, `"#Atlas"`, `"#room"`, and so on. Most of them only seed a
  subject. A few assertions check what a room was actually named (`MarmotGroupNameTest`,
  `MarmotGroupCreationJvmTest`, `ConfederationCeremonyJvmTest`, `SubgroupManagerJvmTest`).
  Five `@Preview`s in `ui/composable` use a `#` subject.

## What stays

- **The rule is still applied when a room is created, not when it is drawn**, and for the
  same reason: the name in the epoch-0 `MarmotGroupData` is what every member's device
  shows.
- **Signed certificates are not touched.** A certificate that says `#Translators` keeps
  saying it.
- **Hashtags keep `#`.** `ContentParser`, `RichContent`, `SearchScreen` and
  `strings.xml`'s `word_position` (`#%1$s`, a seed word's number) all use `#` for something
  other than groups, and stay as they are.
- **The MLS state of rooms that already exist is not changed.** See D3.
- **Transcript lines already written stay as written.** See D6.

## Decisions

### D1. One rule that accepts both marks and writes `&`

`MarmotGroupName.PREFIX` becomes `"&"`. `of(name)` strips any leading run of whitespace,
`#` and `&` in any order, and puts one `&` in front. So:

| input | output |
|---|---|
| `Ekklesia` | `&Ekklesia` |
| `#Ekklesia` | `&Ekklesia` (a room from before this change, or from Bennu) |
| `&Ekklesia` | `&Ekklesia` (idempotent) |
| `# & #Ekklesia` | `&Ekklesia` |
| `Arts & crafts` | `&Arts & crafts` (only a *leading* mark is stripped) |
| `   ` | `` (blank stays blank, as today) |
| `#` / `&` | unchanged (only a mark, so nothing to name) |

`of` stays idempotent, and `of(old) == of(new)` for every name, so the same function turns
an old name into the new one. That is what lets Phases 2 to 4 use `of` and nothing else.

One side effect was already there with `#`: a name whose real first character is `#` or `&`
(`#1 fans`) loses it. Nobody can type a `#` into the field today and keep it, so nothing
gets worse.

### D2. `&` goes on the wire as well as on the screen

New rooms are created with `&name` in their `MarmotGroupData`. The alternatives were to keep
`#` on the wire and show `&` only on Giza, or to put no mark on the wire and add `&` when
drawing. Both bring back the problem `MarmotGroupName` was written to solve: the room would
be called one thing on the creator's device and another on every other device. A Bennu
member of a room Giza made sees `&Ekklesia`, which is accurate and harmless. A Giza member
of a room Bennu made sees `&Ekklesia` too, because of D4.

**Decided by the owner: `&` everywhere.** This is the only decision that changes what a
Bennu peer sees, so it was the owner's to make. The alternative was to keep `#` on the wire
for Bennu. Phase 1 would then have left `PREFIX` alone and let Phase 2's normalisation on
arrival do all the work, with the other phases unchanged.

### D3. Existing rooms are renamed locally, not by a commit

Renaming a room for everyone takes a `GroupContextExtensions` commit. In a governed room,
every commit is a proposal until the group's threshold signature is on it
([member-admin-promotion.md](./member-admin-promotion.md)). Asking every group's quorum to
sign a punctuation change, and leaving rooms whose quorum never does on `#`, is worse than
the problem. Bennu peers would also see the change as an unexplained rename.

Instead, each device rewrites its own `ChatRoom.subject` for Marmot rooms (Phase 3) and
applies the same rule to every Welcome from then on (Phase 2). The MLS state keeps `#name`.
`subject` is the only thing the app ever shows, and the only other place that reads
`roomName()` is the Welcome, so the old name never reaches the screen.

### D4. Every Marmot room gets the mark, even a room another client named without one

Phase 2 runs `of` over every Welcome, so a room from a Marmot client that never used a mark
arrives as `&name`. Until now those rooms arrived unmarked, which made the mark unreliable
in the one place it is supposed to be reliable. This changes behaviour beyond the character
swap, so the phase commit says so.

A room whose MLS state has no name keeps `subject = null`, and the room list falls back as
it does today. `of` is never asked to make up a name.

### D5. Show certified names with the rule applied, except where somebody is asked to sign

A certificate signed before this change says `#Translators`. The subgroup list and the
`TYPE_SUBGROUP_CERTIFIED` line describe what the subgroup is *called*, so they read the
certificate's name through `of` and show `&Translators`, which matches the room.

The proposal summary in `ProposedEvent` is different. It describes the bytes a signer is
about to approve, so it shows the tag exactly as written. A new device that is asked to sign
an old coordinator's `#Translators` certificate is shown `#Translators`. A summary that
changes what is being signed, even only cosmetically, is the wrong habit to start.

A subgroup ceremony that spans the upgrade produces a `#X` certificate and an `&X` room.
Nothing compares the two (see "What the code does today"), and after this phase both read
`&X` everywhere except the signing summary that was already approved.

### D6. Transcript lines already written stay as written

A line such as "The group made #Translators a subgroup" was stored as finished text. The
plan does not rewrite chat history: the line is a record of what was said when it was
written, `JoinedGroupAtMigration` set the precedent of deleting only lines that say nothing,
and a search-and-replace over free text would also hit a person's message that happened to
contain `#Something`. New lines get `&` from Phase 4 onwards.

### D7. No audit script

The prefix is one constant behind one function. A grep audit for `"#[A-Z]` would mostly
match hashtags and test fixtures. Phase 5 leaves `#` in a test fixture only where the test
is about the legacy mark, and gives each of those a comment saying so. That is enough for
the next reader.

### D8. `&` in XML is `&amp;`

The prefix stays a Kotlin constant and is not a string resource. If a resource ever spells
it out, it has to be written `&amp;`, and Compose Resources' handling of entities gets an
assertion in `StringCatalogueJvmTest`, as CLAUDE.md asks for any escape the app depends on.
No resource does today.

---

## Phases

Each phase is one commit, leaves `./gradlew check` and `:composeApp:jvmTest` passing, and
puts `(group-name-prefix, Phase N)` in its subject.

### Phase 0. The record

- This document, and a row for it in [README.md](./README.md).
- Repeat the checks in "What the code does today" on the commit the build starts from:

  ```bash
  grep -rn "MarmotGroupName" composeApp/src
  grep -rn "subject = " composeApp/src/commonMain --include=*.kt
  grep -rn "parseName(" composeApp/src/commonMain --include=*.kt
  grep -rnE "startsWith\(['\"]#|trimStart\(['\"]#|removePrefix\(['\"]#" composeApp/src iosApp
  ```

  If a new caller of `MarmotGroupName`, a new writer of `ChatRoom.subject` from MLS state,
  or a new reader of the certificate's name has appeared, add it to the phase that covers
  it before going on.

### Phase 1. The rule

- `MarmotGroupName.PREFIX = "&"`. `of` strips the leading run described in D1. Keep a
  private `MARKS = setOf('#', '&')` beside `PREFIX`, so the legacy mark is named in one
  place.
- Rewrite the KDoc. It names `#` and says why the mark exists. Keep the history
  (`(#admins)`, then `#`, then `&`) and add the reason from "Why" above.
- `MarmotGroupNameTest`: the table in D1, row by row. Add
  `an old name and a new name normalise to the same thing`, and keep the idempotence test.
- The assertions that check a created room's name go from `#` to `&`:
  `MarmotGroupCreationJvmTest` (the `chatRoom.subject` and `roomName()` checks),
  `ConfederationCeremonyJvmTest` (~line 378) and `SubgroupManagerJvmTest`. Inputs that are
  passed as `"#Ekklesia"` can stay as they are, and they now also test that the old mark is
  accepted.
- Comments that say `#`: `SubgroupManager` ~233, `SelectSubgroupAdminsViewModel` ~79 and
  ~273, `SelectSubgroupAdminsScreen` ~166, `MarmotGroupCreation` ~100,
  `CeremonyFollowThrough` ~95.
- `SelectSubgroupAdminsScreen`'s field prefix follows the constant without any change.

After this phase, new rooms and new certificates carry `&`. Rooms already on a device still
show `#` until Phase 3. That is the intended in-between state, and it fixes itself.

### Phase 2. What arrives in a Welcome

- In `NostrDao.processWelcome`, write `subject = group.roomName()?.let(MarmotGroupName::of)`.
  This covers both the first Welcome and a Welcome that replaces a held room
  (`replacesHeldRoom`), since both write the same `ChatRoom`.
- Do not apply the rule inside `MlsGroup.roomName()`. It is the group's own record of its
  name, and `MarmotGroupFactoryJvmTest` relies on it returning exactly what was set.
- Tests, in a new `MarmotGroupNameWelcomeJvmTest` or beside the existing Welcome tests:
  a room created as `#Ekklesia` is joined as `&Ekklesia`; one created as `Ekklesia` (another
  client) is joined as `&Ekklesia`; one created as `&Ekklesia` is unchanged; one with no
  name is joined with `subject == null`.

### Phase 3. Rooms already on the device (schema v52)

- `DedunDatabase` version 51 → 52. The schema does not change, so register
  `AutoMigration(from = 51, to = 52, spec = MarmotGroupNameMigration::class)`, following
  `ReadMarkMigrationSpec`. If Room will not generate an auto-migration with no schema
  difference, use a manual `Migration(51, 52)` registered the same way as the manual ones
  around it.
- `onPostMigrate` reads `SELECT id, userPublicKey, subject FROM ChatRoom WHERE
  mlsGroupState IS NOT NULL AND subject IS NOT NULL`. The predicate is
  `ChatRoomDao`'s own, ~line 94, without the `deletedAt`/`leftGroupAt` filter, because a
  room someone left is still listed and still named. For each row, it writes
  `MarmotGroupName.of(subject)` wherever that differs from `subject`. The rule runs in
  Kotlin, not as a `ltrim` in SQL, so that it is the same function as Phase 1's and not a
  copy of it.
- NIP-17 rooms (`mlsGroupState IS NULL`) are not touched, even if somebody titled one
  `#something`.
- Export `composeApp/schemas/.../52.json`.
- `MarmotGroupNameMigrationJvmTest`, after `JoinedGroupAtMigrationJvmTest`: build a v51
  database with a `#Ekklesia` Marmot room, a bare `Polis` Marmot room, an `&Atlas` Marmot
  room, a `#topic` NIP-17 room and a Marmot room with a null subject. Migrate it, then
  assert `&Ekklesia`, `&Polis`, `&Atlas`, `#topic` and `null`.
- `ShippedProfileUpgradeJvmTest` should still pass. Check that it opens the newest version.

### Phase 4. Names a group has already signed

- `SubgroupManager` ~447: `name = parseName(event.tags)?.let(MarmotGroupName::of)`.
- `ChatMessage` ~1427/1439: the `TYPE_SUBGROUP_CERTIFIED` line uses the same normalised
  name, so new lines say `&Translators`.
- `ProposedEvent` ~72 stays verbatim (D5). Add a comment there saying why, so the next
  person does not "fix" it.
- Tests: a subgroup with a `#Translators` certificate is listed as `&Translators`. The line
  written for it says `&Translators`. The proposal summary for the same certificate says
  `#Translators`. `ProposedEventTest`'s fixture (`#Translation`) is that last case: keep it,
  and add a comment saying it is the legacy form on purpose.

### Phase 5. Fixtures and previews

- Every fixture that only seeds a subject goes from `"#Name"` to `"&Name"`, along with the
  `AdminRoom("#…")` lists and `defaultSubject = "#room"` in the harnesses (`GroupFixtures`,
  `GroupInboxHarness`, `SigningProposalHarness`, `ConfederationTestGroups`'s `"#$name"`, and
  the `ChatRoom(... subject = "#…")` lines in most of the 47 files).
- The five `@Preview`s with a `#` subject (`GroupInboxSettingsScreen`, `GroupInboxScreen`,
  `GroupInboxConversationScreen`, `FrostSigningScreen`, `ProposalListScreen`) and
  `SelectQuorumScreen`'s `#group-discussions`.
- Leave `#` only in tests that are about the old mark: Phase 1's input cases, Phase 3's
  migration and Phase 4's certificate. Each gets a comment saying so.
- Do this as a reviewed edit, not a blind `sed`. Hashtag tests (`ContentParser`, search,
  `RichContent`) use `#` correctly. A short script that prints each candidate line for a
  yes or no is enough, kept in the scratchpad and not in `docs/scripts`.
- After the sweep, `grep -rnE '"#[A-Z]' composeApp/src` lists only the commented legacy
  cases and hashtags.

### Phase 6. The docs

- [subgroups.md](./subgroups.md) ~761 and [confederation.md](./confederation.md) Phase 13's
  "`#name`": say `&`, and point here.
- [nip17-multiple-ceremonies.md](./nip17-multiple-ceremonies.md),
  [shared-key-ceremony.md](./shared-key-ceremony.md),
  [notifications-and-background-sync.md](./notifications-and-background-sync.md) and
  [remove-convenient-groups.md](./remove-convenient-groups.md): change examples such as
  `#Ekklesia` where they describe today's behaviour. Leave quotations of earlier test output
  and commit subjects alone.
- `retired/` is not touched.
- Update this document's status line, and its row in README.md, to built.

### Phase 7. The device run

The checks under "Checking the work", on Android and desktop at least.

---

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew check
```

On a device that has `#` groups from before the change:

1. **Upgrade.** Install over the previous build. Every group on the room list reads
   `&Name`, and no conversation has changed. A group's detail screen, its proposals list,
   the confederation screens and notifications all say `&Name`.
2. **Create.** Make a group and a subgroup. The subgroup's name field shows `&` beside what
   is typed. The parent's admins are asked to approve `&Name`, and the room arrives as
   `&Name` on every member's device.
3. **Mixed versions.** A subgroup ceremony started on the old build and finished on the new
   one. The room is `&Name`, the subgroup list says `&Name`, and the proposal that was signed
   still says `#Name`.
4. **Bennu.** A Bennu peer creates a group with a Giza member in it. Giza shows `&Name` and
   Bennu shows `#Name`. A group Giza made shows `&Name` on the Bennu peer (D2).
5. **Hashtags.** A note containing `&Ekklesia` renders as plain text, and `#Ekklesia` still
   renders as a hashtag.
6. **Screen readers.** TalkBack and VoiceOver read the room list. Write down how `&` is read
   ("ampersand", "and", or nothing) next to how `#` was read. If it reads as "and Ekklesia",
   that is a finding for a follow-up that gives the row a `contentDescription`. It is not a
   reason to hold this change, and this plan does not fix it.

## Risks

- **A third-party client that keys on `#`.** Nothing in Giza or Bennu reads the mark, and
  MIP-01 gives the name no structure. A client that did would see Giza's rooms as unmarked.
  Low risk. If it happens, D2's alternative (`#` on the wire) is a change to Phase 1 alone.
- **Sorting.** `&` (U+0026) sorts before digits and letters just as `#` (U+0023) does, so
  any list sorted by name keeps groups together.
- **A Welcome during the migration.** Room runs the migration before the database opens, so
  no Welcome can write in between, and Phase 2 covers every Welcome after.
