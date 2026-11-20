# engine-interop

The MLS engine the app ships against the one it shipped before, in two JVMs.
[docs/quartz-mls-marmot-update.md](../../quartz-mls-marmot-update.md), Phase 3.

- `old/` is one Marmot member played by quartz 1.15.2's engine, the one a Bennu peer
  on that version still runs. It reads one command per line on stdin and answers on
  stdout (see `OldPeer.kt`).
- `new/` is the app's engine at the versions in `gradle/libs.versions.toml`:
  marmot-kmp over mls-kmp, through Magpie. Its driver starts the old peer as a child
  process and runs two scenarios: the new engine creates the room and the old one joins,
  then the reverse. In each, the creator commits two more adds after the Welcome, and
  both sides must land on the same epoch and kind:445 key and read each other's
  messages at every epoch.

They are two builds because they cannot be one: both depend on the `com.vitorpamplona.quartz`
group, at 1.15.2 and 2.20.0. Magpie serves that group exclusively, and its relay has nothing
older than 2.19.0, so `old/` resolves from Maven Central with no Magpie at all.

Not part of `./gradlew check`. It is a probe to run by hand when the engine moves.

```bash
../../../gradlew -p old installDist
```

```bash
../../../gradlew -p new run --args="$PWD/old/build/install/engine-interop-old/lib"
```

It prints `ok` per check and `ALL PASSED`, or `FAIL` with the step and exits 1. Move
`new/build.gradle.kts`'s versions with the catalogue's, and let the first run add them to
`new/gradle/magpie.lock`.
