# The old engine's state blob

A throwaway project whose only dependency is the engine the app shipped before it moved to
marmot-kmp: `com.vitorpamplona.quartz:quartz:1.15.2`. It prints a `MlsGroupState`
TLS blob, at the `STATE_VERSION = 4` that version wrote, for
`MlsGroupStateCompatibilityTest` to decode with mls-kmp.

```bash
../../../gradlew --project-dir . -q run
```

It is here for provenance. The fixture in that test has to be bytes the *old* engine
wrote, so regenerating it with the engine the app now ships would defeat the point of the
test -- and once this project is gone there is no way to tell the difference by reading
the hex. It is not part of the root build (its own `settings.gradle.kts` makes it a build
of its own), and nothing in `./gradlew check` runs it.

Not a general-purpose tool: it is the one blob Phase 4 of docs/marmot-kmp.md needed.
