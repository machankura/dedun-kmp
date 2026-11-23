# A group's name is drawn as `{name}`, a confederation's as `{{name}}`

A phased plan to change how a Marmot room's name looks: `&Ekklesia` becomes `{Ekklesia}`.
A confederation, which has had no mark until now, is drawn with doubled braces:
`{{Hellenic league}}`. The braces are **cosmetic**. The database stores the bare name,
`Ekklesia`, and the braces go on only where the name is drawn. This changes the model that
[group-name-prefix.md](./group-name-prefix.md) set up, where the mark was part of the name
and was written into `ChatRoom.subject`, the MLS group state and signed certificates.

**Status: proposed, every decision made.** Nothing is built. The owner decided:

- D2: the bare name goes on the wire.
- D6: a stored sentence is written with the braces in it.
- D7: the subgroup field is framed in braces.
- D10: a confederation is drawn `{{name}}`.

Written against `dc4ed388` on `giza`. Read group-name-prefix.md first for the history of the
mark and for why existing rooms are renamed on each device instead of by a commit.

---

## Why

The mark's job has not changed since [subgroups.md](./subgroups.md). It tells a person which
rows on the room list are groups (Marmot rooms) and which are conversations (NIP-17 rooms).
It does that at the front of the row, where a truncated row still shows it. `{Translation
te…` still says "group". Braces also mark where the name ends, so `{Arts & crafts}` reads as
one name, where `&Arts & crafts` read as two ampersands.

**Why cosmetic.** The mark says what *kind* of room a row is. It is not part of what the
group is called. Storing it causes problems:

- Every reader has to know that the mark is there and strip it before comparing.
  `SelectQuorumUIState` (line 21) compares a typed `Ekklesia` against `&Ekklesia` and finds
  no match.
- Every change of character needs a migration and a period where peers disagree.
  group-name-prefix.md needed both.
- The mark is signed into certificates that can never change.

With a bare name in storage, the character is two constants, and changing it again changes
nothing on disk.

**Why doubled for a confederation.** A confederation is a group of groups
([confederation.md](./confederation.md)), and the doubled braces say that: one more level of
nesting. Its names appear in the same places as group names: transcript lines, the
confederation screens, and lists of what a group belongs to. Single braces would make the
two look alike.

## What the code does today

These checks were run while writing the plan, against `dc4ed388`. Phase 0 runs them again.

- **One rule.** `MarmotGroupName` (`managers/MarmotGroupName.kt`) holds `PREFIX = "&"`,
  `MARKS = '&', '#'`, and `of(name)`. `of` trims the name, removes any leading marks, and
  puts one `&` in front. It is idempotent.
- **The mark is written in four places:**
  - `MarmotGroupCreation.create`, line 124, puts it into the MLS `MarmotGroupData`, which
    goes on the wire, and into `ChatRoom.subject`.
  - `SubgroupManager.proposeBirthCertificate`, line 235, puts it into the signed `name` tag.
  - `NostrDao.processWelcome`, line 885, puts it into `ChatRoom.subject` for every joined
    room.
  - `MarmotGroupNameMigrationSpec` (v52→v53) put it into `ChatRoom.subject` for every Marmot
    room on the device.
- **Bare names already exist next to marked ones.** `DkgSession.subject` holds the bare
  name a ceremony was given. `DatabaseChatRepository` (lines 165 and 183),
  `SelectQuorumViewModel` (line 120), `DkgRitualViewModel` (line 198) and
  `ChillDkgRitualManager` (line 240) each fall back from `session.subject` to the room's
  `subject`. So one list can hold `Ekklesia` from a ceremony and `&Agora` from a room, and
  shows them side by side.
- **Every read of `ChatRoom.subject`.** There are 31 lines in `commonMain`, and none in
  `iosApp` or the platform source sets. Sorted by what they do:

  | site | what it does | after this plan |
  |---|---|---|
  | `LocalChatRoom.RenderChatRoomTitleText` (line 67) | the room's title, used by the room list row (`ChatRoomListViewModel`, line 206), the messaging and detail top bars, `EditGroupNostrProfileScreen` and `BroadcastGroupSignedEventScreen` | draw |
  | `NotificationContent.roomTitle` (line 64) | the notification title | draw |
  | `SearchMemberToAddToChatRoomScreen` (lines 99–100) | "Invite new member to …" | draw |
  | `AddMemberToChatRoomConfirmationScreen` (line 153) | `to_join_the_chat_room` | draw |
  | `DkgJoinApprovalScreen` (80), `ProposeGroupEventScreen` (228), `AddGroupPostScreen` (210), `DkgRitualScreen` (207), `CreateSharedKeyScreen` (138, 200–202) | a ceremony's or room's name in a screen | draw |
  | `ChatRoomDetailScreen` (lines 1874–1875) | a subgroup: its room's subject, or else its certified name | draw |
  | `ChatTranscript` (lines 703 and 717) | ceremony subjects in "The key ceremony for … needs you" | draw |
  | `MemberActionUnavailableScreen` (184–197), `SelectQuorumScreen` (312) | `AdminRoom.name`, `ExistingGroup.name` | draw |
  | `ConfederationManager.nameOf` (line 202) | a group's name in prose: its profile's name, else its room's subject, else its npub | draw, room-subject branch only |
  | `DatabaseChatRepository` (165, 174, 183), `SelectQuorumViewModel` (120), `DkgRitualViewModel` (198) | build `AdminRoom` / `ExistingGroup` / name lists | data, stays bare |
  | `ChillDkgRitualManager` (216, 240) | compares names with `sameName` | logic, stays bare |
  | `CeremonyFollowThrough` (216) | the name a new room is created with | logic, stays bare |
  | `CreateSharedKeyViewModel` (108–109), `SelectSubgroupAdminsViewModel` (134) | pre-fill a name field | logic, stays bare |

- **Certified names.** `SubgroupManager` (line 451) and `ChatMessage` (line 1449) read a
  certificate's name through `of`. `ProposedEvent` (line 72) shows it verbatim, because it
  is what a signer is approving.
- **Stored prose.** `ChatMessage` (line 1462) writes the `TYPE_SUBGROUP_CERTIFIED` line as
  finished text: "The group made &X a subgroup, …". `ConfederationManager.nameOf` feeds
  refusal notes and confederation lines that are also stored as text.
- **Confederation names.** A confederation has no room
  ([confederation-room-removal.md](./confederation-room-removal.md)), so its name is never a
  `ChatRoom.subject`. Its name lives in these places:
  - `Confederation.name` (`database/model/Confederation.kt`, line 30).
  - The signed `name` tag of the proposal statement (`ConfederationStatements`, line 115)
    and of the charter (`ConfederationCharterEvent`, line 45).
  - `ConfederationProfileManager.Membership.name`.

  `ProposeConfederationViewModel` (line 154) only trims it. Nothing has ever marked it.
  **Two checks compare it byte for byte:** `ConfederationSigningManager` (line 221,
  `charter.name == held.confederation.name`) and `ConfederationChangeRefusal` (line 127,
  `charter.name != membership.name`). A mark written into any copy would make a charter
  disagree with its record.
- **Where a confederation's name is drawn.**

  | site | what it does | after this plan |
  |---|---|---|
  | `ConfederationScreen` (line 169) | top bar title | draw `{{…}}` |
  | `ConfederationScreen` (line 341) | the confederation's profile name, else `state.name` | draw the fallback only (D3) |
  | `ConfederationsScreen` (line 190) | a proposal's row | draw |
  | `ConfederationProposalScreen` (line 157) | headline | draw |
  | `ConfederationRitualScreen` (lines 128 and 156) | headline | draw |
  | `ConfederationChangeScreen` (line 169) | headline | draw |
  | `ProposeConfederationScreen` (line 215) | the name field | prefix and suffix (D10) |
  | `ProposedEvent` (lines 157 and 164) | signing summaries of a proposal and a charter | verbatim (D5) |
  | `ConfederationManager` (lines 143–152 and 188), `ConfederationFormation` (line 86), `ConfederationChangeRefusal` (line 166), `ConfederationProfileManager` (line 95) | sentences stored as transcript lines or notes | written with `{{…}}` (D6) |
  | `ConfederationSigningManager` (221), `ConfederationChangeRefusal` (127) | byte-for-byte comparisons | untouched |

- **Database.** `DedunDatabase` is at `version = 54`.
- **The name field.** `SelectSubgroupAdminsScreen` (line 171) shows `PREFIX` in the
  `prefix` slot. Material3's `suffix` slot is drawn at the field's trailing edge, not
  straight after the text: `internal/TextFieldImpl.kt` in 1.5.0-alpha22, line 1189, places
  it at `width - trailingPlaceable.width - suffixPlaceable.width`.
- **Interop.** Bennu makes `#name` rooms and draws `roomName()` as it is. Giza builds from
  before this change make `&name` rooms and run their own `of` over every Welcome.
- **Tests and previews.** 101 lines in 48 test files write an `&` name, and about 35 of
  them assert the rule's output. Six `@Preview`s write `&`.

## What stays

- **Existing rooms are renamed on each device, never by a commit.** A rename commit in a
  governed room is a proposal that its quorum has to sign. That is group-name-prefix.md's
  D3.
- **Signed certificates are not touched.** An old certificate that says `&Translators` or
  `#Translators` keeps saying it. It is read through the storage rule, and nothing is
  written back.
- **`ProposedEvent` shows the signed bytes verbatim.**
- **`MlsGroup.roomName()` stays raw.**
- **NIP-17 rooms are never drawn with braces**, whatever their subject is.
- **Hashtags keep `#`.**
- **Transcript lines already written stay as written.**
- **A confederation's stored and signed name is never rewritten.** No migration touches it,
  and the byte-for-byte checks keep comparing exactly what was signed.

## Decisions

### D1. Two functions: `bare` for storage, `display` for drawing

`MarmotGroupName` gets two functions in place of `of`:

- **`bare(name)`** is the storage rule. Every name that goes into the database, onto the
  wire, or into a certificate passes through it. It trims the name and removes the leading
  run of whitespace, `#`, `&` and `{`, counting the `{`s in that run as *k*. It then removes
  up to *k* trailing `}`s, with any whitespace between them, and trims again. If nothing is
  left, it returns `name.trim()`.
- **`display(name)`** is the drawing rule: `PREFIX + bare(name) + SUFFIX`, with
  `PREFIX = "{"` and `SUFFIX = "}"`. It returns a blank name unchanged. It runs `bare`
  first, so it draws whatever is stored correctly, before the migration or after it. Phase 1
  relies on that.

| stored or received | `bare` | `display` |
|---|---|---|
| `Ekklesia` | `Ekklesia` | `{Ekklesia}` |
| `&Ekklesia` | `Ekklesia` | `{Ekklesia}` |
| `#Ekklesia` | `Ekklesia` | `{Ekklesia}` |
| `{Ekklesia}` | `Ekklesia` | `{Ekklesia}` |
| `# & #Ekklesia` | `Ekklesia` | `{Ekklesia}` |
| `Arts & crafts` | `Arts & crafts` | `{Arts & crafts}` |
| `#Room #2` | `Room #2` | `{Room #2}` |
| `Set {a}` | `Set {a}` | `{Set {a}}` |
| `   ` | `` | `` |
| `&` / `{}` | unchanged | unchanged |

`bare` is idempotent. `display(display(x)) == display(x)`, so drawing a name twice by
mistake is harmless.

Braces are removed only in pairs that open at the front. That way a name whose real last
character is `}` keeps it. Without the count *k*, `Set {a}` would come out as `Set {a`.

### D2. The bare name goes on the wire. *Decided by the owner.*

"Cosmetic" means the braces live in the drawing code. So new rooms are created with the
bare name in their `MarmotGroupData`, and new certificates sign the bare name. What each
kind of peer sees:

| peer | a room Giza makes after this change | why |
|---|---|---|
| Giza after this change | `{Ekklesia}` | it draws braces |
| Giza before this change | `&Ekklesia` | its `of` marks every Welcome |
| Bennu | `Ekklesia`, unmarked | it draws `roomName()` as it is |
| another Marmot client | `Ekklesia` | same |

The only loss is Bennu's view: a Bennu member of a room Giza made no longer sees a mark.
Bennu does not read the mark for anything.

There are two alternatives:

- **Keep `&` on the wire.** Older Giza and Bennu would see `&Ekklesia` exactly as today.
  The cost is that what is stored is no longer what is sent: creation and certificates
  would call a separate `wireName`, and the mark stays in signed data forever.
- **Braces on the wire.** This contradicts the request: braces would no longer be cosmetic.

**Decided: the bare name on the wire.** It is the only option where no layer holds the mark.
Its one cost falls on Bennu, which draws the name it is given. Had the owner chosen to keep
`&` on the wire for Bennu's sake, only Phase 2 would have changed.

### D3. Where braces are drawn

Draw braces wherever the app names a **Marmot group**:

- A room whose `mlsGroupState` is not null.
- An admin room (`AdminRoom`).
- A subgroup, by its room's subject or its certified name.
- A group a ceremony is making (`DkgSession.subject`, `ExistingGroup`).
- The room-subject branch of `ConfederationManager.nameOf`.

Draw doubled braces wherever the app names a **confederation** by its own name: the screens
in the confederation table above, and the stored sentences of D6.

Do not draw braces on:

- A NIP-17 room's subject.
- A person's name.
- A group's or a confederation's public profile name (`nameOf`'s profile branch, and
  `ConfederationScreen` line 341's profile branch). That is a separate field the group chose
  for itself, and it is never a room's or a charter's name.

Ceremony subjects are included on purpose. `DatabaseChatRepository` already merges a
ceremony's subject and a room's subject into one `AdminRoom.name`. If one were drawn with
braces and the other without, a single list would mix `{Agora}` and `Ekklesia`. Today it
mixes `&Agora` and `Ekklesia` for the same reason.

There is one entry point for rooms: an extension `ChatRoom.title: String?`, beside
`LocalChatRoom`. It returns `MarmotGroupName.display(subject)` for a Marmot room and
`subject` for a NIP-17 room. Every "draw" row in the table above goes through it, or
through `MarmotGroupName.display` when what it holds is not a `ChatRoom` (`AdminRoom.name`,
`Subgroup.name`, `ExistingGroup.name`, ceremony subjects). No call site concatenates the
braces itself.

### D4. Storage becomes bare, by a v55 migration

`ChatRoom.subject` for a Marmot room stores `bare(name)`:

- Creation writes it.
- `processWelcome` writes `roomName()?.let(MarmotGroupName::bare)`.
- A v54→v55 migration strips the mark from every Marmot room already on the device. It uses
  the same predicate as v53: `mlsGroupState IS NOT NULL AND subject IS NOT NULL`, including
  rooms the user has left. NIP-17 rooms are not touched.

The body of `MarmotGroupNameMigrationSpec` moves into an internal
`renameMarmotRooms(connection, rule)`. The v53 spec calls it with `MarmotGroupName::bare`,
and so does the new v55 spec. That means a device upgrading from v52 or earlier goes
straight to bare names at v53, and v55 has nothing to do. This is correct: `of` is deleted
in Phase 2, and v53's intent was always "apply the current rule".

### D5. Certified names: `bare` on reading, `display` on drawing

`SubgroupManager` (line 451) reads a certificate's name with `bare`, so `Subgroup.name` is
bare like every other stored name. The screens draw it with `display`. An old `&Translators`
certificate and a new `Translators` certificate both draw as `{Translators}`.

`ProposedEvent` stays verbatim. A new certificate's summary therefore shows the bare
`Translators`, and an old one shows `&Translators`. This is the one place a name is shown
without braces, on purpose, because it shows the bytes being signed. Its comment says so.

### D6. Prose written to disk. *Decided by the owner: A.*

The `TYPE_SUBGROUP_CERTIFIED` line and the confederation refusal notes are sentences stored
as finished text. They are not name columns, so the request does not decide them directly.

- **A. Write the braces into the sentence**, through `display`, at the moment it is
  written: "The group made {Translators} a subgroup". This is one change at each writer.
  Braces end up on disk, but only inside a sentence that records what was said, which is
  what the line already does with `&`.
- **B. Render the line at display time** from the certificate (`marmotInnerEventId`).
  Nothing on disk carries a brace. The cost is a lookup in `ChatTranscript` for one message
  type, and a fallback for a line whose certificate is gone.

**Decided: A.** A transcript line is a record, not a name, and B would make the transcript
query the database for one message type. This is the one place braces reach the disk: inside
a sentence, never in a name column.

### D7. The subgroup name field. *Decided by the owner: A.*

The ViewModel already holds the bare text, which is now exactly what is stored, so the field
needs no rule of its own. These options are for drawing:

- **A. `prefix = "{"`, `suffix = "}"`.** One line. The braces frame the whole field, as in
  `{Translation team            }`.
- **B. `prefix = "{"`, with a `supportingText` that shows `display(name)`**, such as "Will
  be called {Translation team}". This needs one new string resource.
- **C. A `VisualTransformation`** that draws the braces against the text. The caret logic
  becomes custom, which is what the ViewModel's KDoc (line 75) avoids.

**Decided: A.** Phase 7 looks at it at phone width. If the gap between the text and the
`}` reads badly there, B is a follow-up confined to `SelectSubgroupAdminsScreen` and
`strings.xml`.

### D8. Comparisons stop needing to know about the mark

Once both sides are bare, these comparisons compare like with like:

- `SelectQuorumUIState` (line 21), which checks whether a name is already taken.
- `ChillDkgRitualManager.sameName`, which folds a repeated request into a running ceremony.

Today a room named before ceremonies carried a `subject` is `&Ekklesia`. It does not match a
typed `Ekklesia`, so the duplicate check misses it. Phase 2 adds a test for that, as a fix
that comes with this change and not as its goal.

### D9. Names a person types

The `{` and `&` that `bare` removes are only leading marks. If a person names a group
`{Ekklesia}` by hand, it is stored as `Ekklesia`, which is what they meant. If they name it
`&Co`, it is stored as `Co`. That side effect already existed with `#` and `&`, and it does
not get worse.

### D10. A confederation is drawn `{{name}}`, and its name stays exactly as signed

This is the owner's decision. A new `ConfederationName` object, beside `MarmotGroupName`,
holds:

- **`display(name)`**: `"{{" + inner + "}}"`, where *inner* is the name with any matched
  outer braces and whitespace removed by the same count-*k* rule as D1. It returns a blank
  name unchanged, and it is idempotent. It does **not** remove `#` or `&`. Confederation
  names never had a mark, so a leading `#` in one is part of the name.
- **`bare(name)`**: the same brace-stripping without the drawing. It is applied in exactly
  one place: `ProposeConfederationViewModel` (line 154), where a name is typed and *before*
  it is signed. A person who types `{{League}}` into a field already framed in braces
  proposes `League`.

`bare` is **never** applied to a name that has been received or signed. That covers
`Confederation.name`, a charter's or proposal's tag, and `Membership.name`.
`ConfederationSigningManager` (line 221) and `ConfederationChangeRefusal` (line 127) compare
those copies byte for byte. Normalising one copy and not the other would make a valid
charter look tampered with. A name another device proposed as `{X}` therefore stays `{X}`
in storage, and `display` draws it as `{{X}}`.

That is also why confederations need no migration. Nothing stored has a mark to remove, and
nothing stored may change.

Both objects share the brace-stripping, which lives in one private function. Neither object
copies it.

The confederation name field (`ProposeConfederationScreen`, line 215) follows D7's choice:
`prefix = { Text("{{") }` and `suffix = { Text("}}") }`, from `ConfederationName`'s
constants.

---

## Phases

Each phase is one commit, leaves `./gradlew check` and `:composeApp:jvmTest` passing, and
puts `(group-name-braces, Phase N)` in its subject. Phase 1 changes only what is drawn, and
it works on today's `&` data. Phases 2 and 3 change what is stored. The order means a person
sees braces from the first commit, and nothing on disk changes until the drawing is ready
for bare names.

### Phase 0. The record

- This document, and a row in [README.md](./README.md).
- Repeat the checks above on the commit the build starts from:

  ```bash
  grep -rnE "\.subject\b" composeApp/src --include=*.kt | grep -v "Test\|DkgSession"
  grep -rn "MarmotGroupName" composeApp/src
  grep -rn "parseName(" composeApp/src/commonMain --include=*.kt
  grep -rn "subject" iosApp --include=*.swift
  grep -n "version = " composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/database/DedunDatabase.kt
  grep -rnE "(proposal|charter|confederation|membership\([^)]*\)\??)\.name\b|confederationName" composeApp/src/commonMain --include=*.kt
  ```

  Sort any new read of a subject into the table's "draw" or "stays bare" rows before going
  on, and any new read of a confederation's name into the confederation table. If the
  database is past 54, renumber Phase 3.
- D2, D6, D7 and D10 are decided. Nothing waits on the owner.

### Phase 1. Draw the braces

This phase makes no change to storage. Everything it draws goes through `display`, which
runs `bare` first, so today's `&Ekklesia` subjects already draw as `{Ekklesia}`.

- `MarmotGroupName`: add `bare`, `display` and `SUFFIX`, and set `PREFIX = "{"`. Keep `of`
  for now, but have it build on `bare` and use the *old* `&` as a private `LEGACY_PREFIX`,
  so the writers do not change in this phase. Rewrite the KDoc: the mark is drawn and not
  stored, with the history and a pointer here.
- `ChatRoom.title` (D3), with a test: a Marmot room stored as `&Ekklesia` or `Ekklesia` has
  the title `{Ekklesia}`, a NIP-17 room `&topic` keeps `&topic`, and a null subject stays
  null.
- Route every "draw" row in the table through `title` or `display`:
  - `RenderChatRoomTitleText`, which covers the row and every top bar that calls it.
  - `NotificationContent.roomTitle`.
  - The five ceremony and room screens.
  - `SearchMemberToAddToChatRoomScreen`. While there, move its hard-coded "Invite new
    member to …" into `strings.xml` with a `%1$s`, as CLAUDE.md requires of any line that
    is touched.
  - `AddMemberToChatRoomConfirmationScreen`.
  - `ChatRoomDetailScreen`'s subgroup row.
  - `ChatTranscript`'s ceremony lines.
  - `MemberActionUnavailableScreen` and `SelectQuorumScreen`.
- `MarmotGroupNameTest`: D1's table, one test per row, for `bare` and `display`. Test that
  `bare` is idempotent and that `display` is idempotent.
- Screen tests that check visible text (`GroupInboxScreenJvmTest`,
  `MemberActionUnavailableScreenJvmTest`, `ConfederationScreensJvmTest`, and any others
  Phase 0 finds): expect `{Name}` where they expected `&Name`.
- `SelectSubgroupAdminsScreen`: add `suffix = { Text(MarmotGroupName.SUFFIX) }` beside the
  existing `prefix` (D7), and update the comment above them.
- Confederations (D10):
  - Add `ConfederationName` with `PREFIX = "{{"`, `SUFFIX = "}}"`, `display` and `bare`.
    The brace-stripping moves into one private function that both objects call.
  - Route every "draw" row of the confederation table through `ConfederationName.display`.
    For `ConfederationScreen` line 341, that means the fallback only.
  - `ProposeConfederationScreen`'s name field gets the `{{` prefix and the `}}` suffix.
  - `ProposeConfederationViewModel` (line 154): `name = ConfederationName.bare(name)`.
  - Tests:
    - `display` and `bare` over `League`, `{League}`, `{{League}}`, `#1 league` (kept),
      and blank.
    - `ConfederationScreensJvmTest` expects `{{Name}}` in the title.
    - A charter with the name `{X}` still matches a record with the name `{X}`. This test
      guards the byte-for-byte comparison against someone "fixing" it with `bare`.

After this phase, every screen shows `{Name}` for a group and `{{Name}}` for a
confederation. What is stored and sent is still `&Name` for a group, and unchanged for a
confederation.

### Phase 2. Store and send the bare name

- `MarmotGroupCreation.create`: `val groupName = MarmotGroupName.bare(name)`. As D2
  decided, the same string goes into `MarmotGroupData` and `ChatRoom.subject`.
- `SubgroupManager.proposeBirthCertificate`: sign `bare(name)`.
- `NostrDao.processWelcome`: `subject = group.roomName()?.let(MarmotGroupName::bare)`.
- `SubgroupManager` (line 451): `Subgroup.name` reads the certificate through `bare` (D5).
- Delete `of` and `LEGACY_PREFIX`. Rewrite the comments that explain idempotence between
  the certificate and the room (`SubgroupManager` ~233, `SelectSubgroupAdminsViewModel` ~75
  and ~267, `MarmotGroupCreation` ~100, `CeremonyFollowThrough` ~95). The reasoning is the
  same with `bare`.
- `ProposedEvent` (line 72): update the comment for D5. The code does not change.
- Update the assertions on the stored or sent name from `&X` to `X`:
  - `MarmotGroupCreationJvmTest`, lines 161, 162 and 273.
  - `MarmotGroupNameWelcomeJvmTest`, with `#`, `&`, `{…}` and bare inputs all joined as
    `Ekklesia`, and a nameless room joined with `subject == null`.
  - `SubgroupManagerJvmTest`, lines 129, 151 and 271.
  - `GroupsInOneRoomJvmTest`, lines 66 and 71.
  - `CurrentProfileKeyPackageJvmTest`, line 218.
  - `MarmotGroupNameMigrationJvmTest`, whose v53 cases now expect bare names. Do this here
    if the v53 spec is repointed in this phase; otherwise do it in Phase 3.
- D8's test: a ceremony whose room is stored as `Ekklesia` folds a repeated request for
  "Ekklesia". So does `SelectQuorumUIState`'s duplicate check.

### Phase 3. Rooms already on the device (schema v55)

- Extract `renameMarmotRooms(connection, rule)` from `MarmotGroupNameMigrationSpec`, and
  repoint v53 at `MarmotGroupName::bare` (D4).
- Add `MarmotGroupBareNameMigrationSpec`, and set `DedunDatabase` to `version = 55` with
  `AutoMigration(from = 54, to = 55, spec = …)`. Add a comment in the style of v53's. The
  schema does not change. Room generated this kind of migration at v53. If it will not here,
  register a manual `Migration(54, 55)`.
- Export `55.json`. It should match `54.json` apart from the version.
- `MarmotGroupBareNameMigrationJvmTest`. Build a v54 database with these rooms, migrate it,
  and check the results:

  | room | before | after |
  |---|---|---|
  | Marmot | `&Ekklesia` | `Ekklesia` |
  | Marmot | `#Atlas` | `Atlas` |
  | Marmot | `Agora` | `Agora`, not rewritten |
  | Marmot that the user has left | `&Delos` | `Delos` |
  | Marmot | `&Arts & crafts` | `Arts & crafts` |
  | NIP-17 | `&topic` | `&topic` |
  | Marmot | `null` | `null` |

- `ShippedProfileUpgradeJvmTest` still passes and opens the newest version.
- The migration does not touch confederations: no `Confederation`, charter or statement row
  changes (D10).

### Phase 4. Prose written to disk

D6 decided that each sentence is written with the braces in it, through `display`, when it
is stored.

- `ChatMessage` (line 1462): `"The group made ${name?.let(MarmotGroupName::display) ?: "a subgroup"} a subgroup, …"`.
  Update the comment above it (~1441): the line records the name as it was drawn when it
  was written.
- `ConfederationManager.nameOf`: wrap only the room-subject branch in `display`. A group's
  profile name and the npub fallback stay as they are (D3).
- Confederation sentences name the confederation through `ConfederationName.display`:
  - `ConfederationManager`, lines 143–152 (proposed, invited, accepted, declined,
    withdrawn) and 188 (charter signed).
  - `ConfederationFormation`, line 86 ("This group is part of the confederation
    {{Hellenic league}}, now formed").
  - `ConfederationChangeRefusal`, line 166, and `ConfederationProfileManager`, line 95.
    Their `"the confederation"` fallback stays plain.
  - Leave `ConfederationFormation`'s log line (92) alone. A log is not drawn.
- Tests:
  - A certificate line says `{Translators}` for both a `Translators` certificate and an
    `&Translators` one.
  - A confederation note names a group with no profile as `{Agora}`, and a group with a
    profile by its profile's name.
  - A proposal line names the confederation as `{{Hellenic league}}`, and so does the
    formation line.
- Lines already written keep their `&`, and confederation lines already written keep their
  unmarked name.

### Phase 5. Fixtures and previews

- Every fixture that seeds a subject or a name goes from `"&Name"` to `"Name"`. That
  includes `GroupFixtures`, `ConfederationTestGroups` (`"&$name"` → `name`),
  `ConfederationPeople`, the harnesses' `defaultSubject` and `name` arguments, the
  `AdminRoom("&…")` lists, `MarmotGroupFactoryJvmTest`, and the `ChatRoom(... subject =
  "&…")` lines in the rest of the 48 files.
- The six `@Preview`s: `GroupInboxScreen`, `GroupInboxSettingsScreen`,
  `GroupInboxConversationScreen`, `FrostSigningScreen`, `ProposalListScreen` and
  `SelectQuorumScreen` (`&group-discussions`). They draw `{Name}` through Phase 1's
  accessor.
- Keep `&` and `#` only where a test is about a legacy form: the `bare` table, the Welcome
  and migration tests, and certificate fixtures such as `ProposedEventTest`'s. Each gets a
  one-line comment saying so.
- Make this a reviewed edit, not a blind `sed`: `"&"` also appears in URL query strings and
  `&&`. Use a scratchpad script that prints each `"&[A-Za-z$]` line for a yes or no.
- After the sweep, `grep -rnE '"&[A-Za-z$]' composeApp/src` lists only the commented legacy
  cases.

### Phase 6. The docs

- [group-name-prefix.md](./group-name-prefix.md): add a line under its status saying that
  the mark is now drawn and not stored, pointing here. Leave its body alone, because it is
  the record of the `&` change.
- [shared-key-ceremony.md](./shared-key-ceremony.md) (~202), [subgroups.md](./subgroups.md)
  (~761), [confederation.md](./confederation.md) (Phase 13's row),
  [nip17-multiple-ceremonies.md](./nip17-multiple-ceremonies.md) (32, 67, 526–537) and
  [automatic-key-ceremony.md](./automatic-key-ceremony.md) (490–492): say the room *is
  called* `Ekklesia` and *shows as* `{Ekklesia}` where they describe today's behaviour.
  Leave quotations of earlier output alone.
- `retired/` is not touched.
- README rows for both documents, and this document's status, with build notes.

### Phase 7. The device run

The checks below, on Android and desktop at least.

---

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew check
```

On a device that already has `&` groups, ideally including one from before v53 (`#`):

1. **Upgrade.** Install over the previous build. Every group reads `{Name}` on the room
   list, its top bar, its detail screen, notifications, the proposals list and the
   confederation screens. No conversation has braces.
2. **Storage.** In a debug build, or with the database inspector, Marmot rooms'
   `ChatRoom.subject` values are bare. NIP-17 subjects are unchanged.
3. **Truncation.** A long name truncates as `{Name…`.
4. **Create.** Make a group and a subgroup. The field shows `{` before the text and `}` at
   its trailing edge (D7). The
   parent's admins approve the name. The signing summary shows the bare name, which is the
   signed bytes (D5). The room arrives as `{Name}` on every Giza member's device.
5. **Name taken.** Starting a second group under an existing group's name is caught,
   including for a group whose room was named before this change (D8).
6. **Mixed Giza versions.** A device on the previous build shows a new room as `&Name`. An
   upgraded device shows an old room as `{Name}`.
7. **Bennu.** A Bennu peer creates a group with a Giza member in it: Giza shows `{Name}`. A
   group Giza made shows `Name` on the Bennu peer (D2).
8. **Text.** `{Ekklesia}` in a note renders as plain text, and `#Ekklesia` is still a
   hashtag.
9. **Confederations.** Propose one. The name field frames the name in `{{ }}`, and the
   proposal, the ritual, the confederation screen and the transcript lines all say
   `{{Name}}`. A confederation with a public profile shows the profile's name on its card,
   unmarked. Its member groups are listed as `{Name}`. The charter is signed and accepted
   on every device, which means the byte-for-byte checks still agree.
10. **Screen readers.** Write down how TalkBack and VoiceOver read a `{Name}` row. If the
   braces are read out ("left brace Ekklesia right brace"), that is a finding for a
   follow-up. Because the mark is now drawn, the follow-up is easy: `title` can give the row
   a `contentDescription` ("Ekklesia, group") without touching anything stored. This plan
   does not do it.

## Risks

- **A site that draws a subject without going through `title`.** It would show a bare name
  with no mark. Phase 0's grep and the table guard against that today. To stop a new one,
  leave a comment on `ChatRoom.subject` pointing at `title`. A lint rule is not worth it for
  one field.
- **Drawing twice.** `display(display(x)) == display(x)`, so a site that wraps an
  already-drawn title shows it correctly. The same holds for `ConfederationName.display`.
- **Normalising a confederation's signed name.** A future change that runs
  `ConfederationName.bare` over a received name would make a charter disagree with its
  record. Phase 1's comparison test and the KDoc on `bare` ("only where a name is typed")
  guard against it.
- **The wrong function for a confederation.** Running `MarmotGroupName.display` over a
  confederation's name would draw `{Name}` and would also remove a leading `#`. Both objects
  sit side by side, and their KDocs name each other.
- **Bennu loses the mark** on rooms Giza makes (D2). This is cosmetic, and the owner
  accepted it.
- **Sorting.** No list sorts rooms by name, and the room list sorts by recency. A future
  name sort now sorts bare names, which is the right behaviour.
- **A Welcome during the migration.** Room runs migrations before the database opens, and
  Phase 2's Welcome already writes bare names.
