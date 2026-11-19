# The old engine's state blob

A throwaway project whose only dependency is the engine the app shipped before it moved to
marmot-kmp: `com.vitorpamplona.quartz:quartz:1.15.2`. It prints a `MlsGroupState`
TLS blob, at the `STATE_VERSION = 4` that version wrote.

What it printed is pinned in `OldEngineBlob`, in `commonTest`, and two tests read it from
there: `MlsGroupStateCompatibilityTest` decodes it with mls-kmp, and
`ShippedProfileUpgradeJvmTest` puts it in a database at the schema version the app ships
and opens that database with the app's own builder (docs/marmot-kmp.md, Phases 4 and 9).
One copy, because the only thing that makes those bytes worth anything is that they are
old ones, and a blob living in two files can be regenerated in one of them.

```bash
../../../gradlew --project-dir . -q run
```

It is here for provenance. The fixture has to be bytes the *old* engine wrote, so
regenerating it with the engine the app now ships would defeat the point of both tests --
and once this project is gone there is no way to tell the difference by reading the hex. It is not part of the root build (its own `settings.gradle.kts` makes it a build
of its own), and nothing in `./gradlew check` runs it.

Not a general-purpose tool: it is the one blob Phase 4 of docs/marmot-kmp.md needed, and
Phase 9 found a second use for rather than a second source.
