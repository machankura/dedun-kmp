# Messages are lost when two arrive out of order

A group event that a relay hands back a moment late is dropped and cannot be
recovered. Two messages published in the same second reliably lose one of them.

This is a conformance gap in quartz's MLS implementation, not in this app. What
this app can do about it from outside the library is partial, and is described
at the end.

**This is closed.** The engine left quartz for mls-kmp
([marmot-kmp.md](./marmot-kmp.md)), and mls-kmp's state format carries the skipped keys
beside the ratchet positions they belong to -- steps 1 to 3 of "The fix, in quartz" below,
landed in the library the app now depends on. Step 4, the replay guard, is still not
persisted by anyone, and that is the one thing here that is still open. The last section
says what landed where, and corrects one thing this note has wrong about why the bug bit.
Everything between here and there is the account as it stood, and is kept because it is
still why any of it matters -- and because the diagnosis, not just the fix, is what makes
the next state format reviewable.

## Symptom

The receiver stores the kind:445 group event and produces nothing from it. No
inner event, no chat line, no error the user sees. `MarmotGroupEvent` is written
*before* the message is decrypted, so the row survives while everything
downstream of it silently does not:

```
receiver, room 6d8ec3ad ("Frosty (#admins)")

  20:36:55  kind 9   chat message      decrypted, applied
  20:37:34  kind 30321  nonce          decrypted, applied
  20:37:34  kind 30320  proposal       group event stored, no inner event
```

Both 20:37:34 events were published by the same sender in the same
`proposeSigning` call. Every message that arrived on its own decrypted fine; the
back-to-back pair lost exactly one.

Downstream the failure reads as something else entirely. In the case above a
FROST signing session never started on the receiver, because the proposal that
opens one never arrived — leaving a nonce filed against a session that will
never exist. An earlier instance of the same bug dropped a dialect, and the
artifact referencing it then failed a foreign key and rolled back its whole
transaction.

## Cause

MLS is specified to tolerate out-of-order delivery inside an epoch. RFC 9420
§9.1: a receiver that gets generation `N+1` before `N` derives the intermediate
keys and keeps them, so the older message can still be read when it turns up.

Quartz does implement this. `SecretTree` caches them:

```kotlin
// SecretTree.kt
private val skippedKeys = mutableMapOf<Pair<Int, Int>, KeyNonceGeneration>()

fun applicationKeyNonceForGeneration(leafIndex: Int, generation: Int): KeyNonceGeneration {
    val cachedKey = skippedKeys.remove(Pair(leafIndex, generation))
    if (cachedKey != null) { /* ...replay check... */ return cachedKey }

    val state = getOrInitSender(leafIndex)
    require(generation >= state.applicationGeneration) {
        "Generation $generation already consumed (current: ${state.applicationGeneration})"
    }
    ...
}
```

The gap is that the cache is never persisted:

```kotlin
// SecretTree.kt
fun exportSenderStates(): Map<Int, SenderRatchetState> = senderState.toMap()

fun importSenderStates(states: Map<Int, SenderRatchetState>) {
    senderState.putAll(states)
}
```

`exportSenderStates()` returns the ratchet *positions* only. `MlsGroup.saveState()`
calls it (`senderRatchetStates = secretTree.exportSenderStates()`) and
`MlsGroup.restore()` calls `importSenderStates`. So `skippedKeys` exists only in
one `SecretTree` instance's memory.

That would be harmless if the group instance outlived the messages. It does not:
`NostrDao` rebuilds it from stored state for every inbound event and saves it
back afterwards. So the sequence is

1. generation 1 arrives, ratchet advances 0 → 2, generation 0's key goes into
   `skippedKeys`
2. `saveState()` — `skippedKeys` is dropped on the floor
3. generation 0 arrives, a fresh tree is restored with
   `applicationGeneration = 2`, the cache is empty, `require` fails
4. the exception is swallowed, the event yields no `ApplicationMessage`

Step 3 is terminal. The key is derived from a ratchet that has moved past it and
cannot be recovered.

Nothing *asked* the sender to resend, either, which is a second gap and a
narrower one. A FROST signing session now has a resend: the proposer repeats what
it wrote, and the repeat names the members it has heard nothing from so their own
devices repeat what they wrote. See
[frost-session-resend.md](./frost-session-resend.md). It is a recovery driven by
somebody noticing, not a fix — the fix is below, and it is in quartz.

Verified against the published artifact rather than a checkout:
`quartz-1.14.0-sources.jar`, `commonMain/com/vitorpamplona/quartz/marmot/mls/schedule/SecretTree.kt`.

## Why it is not an edge case here

Nostr relays make no ordering guarantee at all, and negentropy reconciliation
hands back a room's backlog in whatever order it likes. Any two messages close
enough together can swap.

Several flows publish in bursts, and each of them is a reliable trigger:

| flow | messages in one pass |
|---|---|
| `FrostSigningManager.proposeSigning` | proposal, then the proposer's nonce |
| `ChillDkgRitualManager.proposeRitual` | proposal, then the host key |
| `MantraDao.addArtifact` | the artifact, then its first version |
| `MantraDao.addChapter` | the chapter, then one per paragraph chunk |

*The two `MantraDao` rows are history: the documents were removed
([ceremony-creation.md](./ceremony-creation.md)).*

`addChapter` is the worst of these: a chapter with twenty paragraphs publishes
twenty-one events at once, and only the ones that happen to arrive in ascending
generation order survive.

`proposeRitual` is the one with the worst *consequence*. It reached this table on
gift wraps, where the bug does not apply; since `docs/subgroups.md` Phase 10 a
subgroup's ceremony rides group events, and a ChillDKG cannot finish until every
participant has taken part — so one lost round-1 message stalls it permanently for
everybody, rather than costing one member a line of chat.

## The fix, in quartz

Carry the skipped keys through `saveState`/`restore` alongside the ratchet
positions.

**1. Export and import them.** In `SecretTree`:

```kotlin
fun exportSkippedKeys(): Map<Pair<Int, Int>, KeyNonceGeneration> = skippedKeys.toMap()

fun importSkippedKeys(keys: Map<Pair<Int, Int>, KeyNonceGeneration>) {
    skippedKeys.putAll(keys)
}
```

`MAX_SKIPPED_KEYS` already bounds the map, so the serialised size is bounded by
the same constant and needs no separate cap.

**2. Put them in the group state.** `MlsGroup.saveState()` already writes
`senderRatchetStates = secretTree.exportSenderStates()`; add a sibling field, and
have `restore()` call `importSkippedKeys` next to its existing
`importSenderStates`.

**3. Keep old state readable.** The persisted state is a TLS-encoded struct that
existing installs already hold, so the new field has to be optional: absent means
an empty map, which is exactly the behaviour today. Without that, every device
with a stored group is broken by the upgrade.

**4. Consumed-generation replay protection.** `consumedGenerations` guards
against a replayed message re-using a cached key. It is in-memory too, so it
should travel with the skipped keys or the guard weakens across restarts. Worth
deciding deliberately rather than by omission.

A test worth having with it: save and restore a group between the two messages
of an out-of-order pair, and assert the older one still decrypts. That is the
property, and it is invisible to any test that keeps one instance alive.

### Getting the change into this build

Quartz is **not** a local fork. It is `com.vitorpamplona.quartz:quartz`, pinned
in `gradle/libs.versions.toml` and resolved from mavenCentral;
`settings.gradle.kts` only `includeBuild`s `lightning-kmp-app`. Nothing in this
repository can change it.

There is a full amethyst clone at `~/Documents/development/nostr/amethyst` whose
`SecretTree.kt` was byte-identical to published 1.14.0 when this was written, so
the patch itself is a small delta against a known-good base. Landing it means one
of:

- **Upstream it.** It is a genuine RFC 9420 conformance gap and affects any
  client that reloads group state per message, which is the ordinary shape for a
  mobile app. Slowest, and the only option that leaves this repo's build
  reproducible.
- **Patch the clone and publish to mavenLocal**, then add `mavenLocal()` here and
  pin the patched version. Fast, but the build then depends on a patched crypto
  library built from one machine's filesystem.
- **Wire quartz as a composite build**, the way `lightning-kmp-app` is. Same
  coupling to a path outside the repo, but the source is at least visible.

## What this app does in the meantime

`MlsGroupCache` keeps a room's `MlsGroup` instance alive between messages instead
of rebuilding it from stored state each time, so `skippedKeys` survives for as
long as the process does. The inbound path in `NostrDao` goes through it.

This covers the case that actually bites — a burst arriving in one sync, decrypted
one after another against the same tree — and it is what makes the flows in the
table above work.

It is not the fix, and it is worth being precise about what it leaves broken:

- **A restart loses the cache.** Messages skipped before the app closed cannot be
  read after it reopens.
- **Another writer invalidates it.** Sending a message advances the sender ratchet
  and saves the room's state; adding a member does too. The cache reuses its
  instance only while the stored state is still exactly what it last wrote, and
  rebuilds otherwise — dropping the skipped keys at that point, exactly as before.
- **Nothing helps a long reorder.** A message the relay holds back until after a
  restart or an outbound send is gone.

A message lost this way is gone for good, but it is not necessarily
unrecoverable: a *new* message carrying the same payload is encrypted at a
generation the receiver has not passed, so it decrypts normally. That is the
whole mechanism behind
[frost-session-resend.md](./frost-session-resend.md), and the same move would
work for any other flow in the table above that someone can be asked to repeat.

The staleness check is what keeps the cache from being *worse* than no cache: a
group that has been overtaken by another writer is never carried on with, so the
fallback is always the old behaviour rather than a diverged ratchet.

## What the move to marmot-kmp changed

The engine is not quartz's any more. quartz 2.3.0 dropped it, and the RFC 9420 half
is [mls-kmp](https://github.com/vitorpamplona/mls-kmp) now
([marmot-kmp.md](./marmot-kmp.md)). Three of the four steps above are in it:

| the fix above | mls-kmp 1.3.0 |
|---|---|
| 1. export and import the skipped keys | `SecretTree.exportSkippedApplicationSecrets` / `importSkippedSecrets`, and the same pair for the handshake ratchet |
| 2. put them in the group state | `MlsGroupState.skippedApplicationSecrets` and `.skippedHandshakeSecrets`, written by `saveState` and read by `restore` |
| 3. keep old state readable | they arrived at `STATE_VERSION` 5 and the decoder accepts 1 to 10, so a version-4 blob -- which is what quartz 1.15.2 wrote, and what every device holds -- reads as an empty map, exactly as this note asked |
| 4. the consumed-generation replay guard travels with them | **not done.** `consumedGenerations` is still a field of one `SecretTree` instance, in no version of the state, so the guard against a replayed message re-using a cached key still weakens across a restart |

So the test this note asked for -- "save and restore a group between the two messages
of an out-of-order pair, and assert the older one still decrypts" -- now passes, and it
is `SkippedGenerationJvmTest`.

**And the row keeps up with it.** `MlsGroupManager.decrypt` persists only when the
message was a commit that advanced the epoch, so an application message reached the live
group's state and nothing else. `MarmotEngine.processGroupEvent` writes the state after
one now ([marmot-kmp.md](./marmot-kmp.md), Phase 8), which is why `NostrDao` reads inbound
events through the engine rather than through the library's processor directly.

**A correction to this note, while closing it.** Step 2 above is not merely half the fix
-- it is the whole of it, and this note's account of *why* the bug bit needs one word
changed. The losing pair was not "the cache is dropped" on its own. It was that the cache
was dropped *while the positions were kept*: `exportSenderStates` wrote that the ratchet
had passed generation N, and nothing carried N's secret, so the restored state refused a
message it had told itself it was past. mls-kmp writes both or neither, so a state saved
without the skipped keys is now an older **coherent** state, and a coherent state that has
not passed the late generation derives it forward. The three bullets of the mitigation
section above are therefore no longer describing a lost message. What an unwritten row
costs is the deriving -- every generation since the last full save, re-derived on each
restart, against `SecretTree`'s thousand-step forward cap -- and replay protection, which
step 4 does not give us in any case.

Which leaves the reason the receive-side write landed when it did, and it is not this note's
bug. Until Phase 8 every sent message wrote the whole group state, so the row could not
fall far behind inside an epoch. Phase 8 replaced that with a ~100-byte sender-ratchet
record, and from then on nothing would have updated the row between epoch changes: a
long-lived room could drift an unbounded number of generations from what the device had
actually read. The write keeps it current. The two halves are one change for that reason,
not for this one.

`SkippedGenerationJvmTest` pins what is true: the secret is in the state the engine would
save, a group rebuilt from those bytes reads the late message, the row carries what the
live group holds, and -- the one that matters for the paragraph above -- the row's ratchet
positions move with the secrets it keeps, so the two can never disagree again.

One thing this does not change: a device that loses a message this way can still be sent
a new one, which is encrypted at a generation it has not passed. That is still the
recovery, and [frost-session-resend.md](./frost-session-resend.md) is still where it
is built.
