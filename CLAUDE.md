# Conventions for UI code

Eight phases of work brought this app's 43 screens onto Material Design 3; the full
account, with the numbers and the reasoning, is in
[docs/material-design-conformance.md](docs/material-design-conformance.md). What follows
is the short version — the rules a new screen has to follow, in the place they are needed,
which is while it is being written rather than while it is being reviewed.

Most of these are enforced. `./gradlew check` runs `docs/scripts/m3-audit.sh --check`,
which fails on a budget that has been exceeded or a floor that has been undercut. Where a
rule below has a number beside it, that number is the budget.

## Spacing comes from the scale — never a `.dp` literal

```kotlin
Modifier.padding(MaterialTheme.spacing.containerPadding)   // yes
Modifier.padding(16.dp)                                    // no
```

M3's eighteen stops live on `Spacing`, with eight semantic names over them —
`screenMargin`, `containerPadding`, `compactPadding`, `relatedGap`, `itemGap`,
`sectionGap`, `emphasisGap`, `targetGap`, `paneGap`. Reach for a semantic name first and
a raw stop (`space125`) only when none of them says the job.

The semantic names are what adapt: `screenMargin` widens from 16dp to 24dp at the medium
breakpoint without a call site changing. That is the whole reason the scale exists rather
than a file of constants.

**Budget: 0 dp literals in spacing positions.** A `.dp` in a *dimension* position — an
avatar's size, a hairline border — is fine and is counted separately.

## Colour comes from a role — never a `Color(0x…)`

```kotlin
MaterialTheme.colorScheme.onSurfaceVariant                    // yes
MaterialTheme.extendedColors.bluePill.onContainer             // yes, for the brand pair
Color(0xFF888888)                                             // no
onSurfaceVariant.copy(alpha = 0.5f)                           // almost never
```

Six schemes are declared — light and dark, each with medium and high contrast variants —
and the platform's contrast setting selects between them. A colour written at a call site
belongs to none of them and will be wrong in five.

`.copy(alpha = …)` on a content role is how nine contrast failures got in: an alpha over
an unknown background has no ratio until it is composited, and the composite is usually
under 4.5:1. The exception M3 states is the 38% disabled state.

Where a colour genuinely cannot come from a role — a QR code's modules, a control over an
arbitrary photograph — mark it at the site:

```kotlin
// m3-color-exempt: the modules of a QR code have to be pure black on pure white
```

**Budget: 0 hardcoded colours outside `ui/theme/`.** `ColorSchemeContrastTest` measures
every pair in all six schemes; it runs in `:composeApp:jvmTest`.

## Text comes from the catalogue, in sentence case

```kotlin
Text(stringResource(Res.string.publish_new_key_package))   // yes
Text("Publish New Key Package")                            // no, twice over
```

Strings live in `composeApp/src/commonMain/composeResources/values/strings.xml`.
Interpolation is a format argument (`%1$s`), not a `"${…}"`.

Capitalisation is **sentence case everywhere** — titles, headings, labels, menu items,
buttons — which is M3's rule and not a preference. Proper nouns keep their capitals.

Compose Resources is not aapt: it does *not* unescape `\'` and does *not* collapse `%%`,
though it does process `\n`. `StringCatalogueJvmTest` asserts each escape the app depends
on; add to it rather than assuming a family rule.

A file whose strings are sample text rather than UI text — a gallery of colour pairings,
say — marks itself once at the top:

```kotlin
// m3-string-exempt: these words are sample text for looking at colour pairings
```

**Budget: 0 title-case strings.** Literals in composables are reported without a budget —
19 remain, all of them terms of a `+` concatenation.

## Every target is 48dp, and every icon has a decided description

```kotlin
Modifier.clickable { … }.minimumInteractiveComponentSize()   // yes
Icon(Icons.Default.Search, contentDescription = "Search")     // yes
Icon(Icons.Default.Add, contentDescription = Decorative)      // yes, when the label is beside it
Icon(Icons.Default.Add, contentDescription = null)            // no — say which
```

`IconButton` and `FilledIconButton` enforce 48dp themselves; a bare `Modifier.clickable`
does not, and three of the app's nineteen were text-sized before this rule.

`Decorative` is the same `null` the compiler sees, and it records that somebody looked. An
icon carrying state the surrounding text does not repeat needs a real description.

**Budgets: 0 unguarded `.clickable`, 0 untriaged `contentDescription = null`.**

## A screen has four states, and says so

Loading, empty, error, loaded. `ErrorState`, `EmptyState` and `LoadingDataIndicator` are
the shared ones; `ErrorState` takes an `onRetry`, and passing `null` is a decision rather
than a default. `EmptyState`'s message is required, because one shared default is how five
different absences all came to say "No events were found".

Report outcomes through the snackbar host:

```kotlin
val notify = rememberNotifier(rememberCoroutineScope())
val published = stringResource(Res.string.key_package_published)   // read outside the handler
…
onClick = { viewModel.publish { notify(published) } }
```

Both `rememberNotifier` and `stringResource` are composable and an `onClick` lambda is
not, so read them above the handler. The notifier takes the caller's scope on purpose:
"saved" is usually shown as the screen navigates away, and a message launched in the
departing composable's scope would be cancelled with it.

Wrap the state `when` so the change is a transition rather than a cut:

```kotlin
ScreenStateTransition(viewModel.uiState) { uiState ->
    when (val state = uiState) { … }
}
```

It only works where the `when` is the composable's whole body — `AnimatedContent` is a
layout node, so wrapping one inside a `Column` takes its branches out of `ColumnScope`.

## A pushed screen has a way back

```kotlin
TopAppBar(
    title = { Text(stringResource(Res.string.add_post)) },
    navigationIcon = { NavigateBackButton(onNavigateBack) },   // yes
)
TopAppBar(title = { … })                                       // no, on a pushed screen
```

A screen reached by `navController.navigate(...)` takes an `onNavigateBack: () -> Unit`
and puts `NavigateBackButton` in its app bar's leading slot; the host passes
`popBackStack()`. Android has a system back and iOS an edge swipe, but the desktop target
has neither, and a pushed screen with no button there is a dead end. Twenty-two were.

The button is *only* for pushed screens. The three top-level destinations have the
navigation bar instead, and a screen that cleared the stack to get there -- onboarding,
the loading gate -- has nothing under it to pop to. Where one composable is reached both
ways, the callback is nullable and the caller decides: `ChatRoomMessagingScreen` is a
destination on a phone and the home screen's detail pane in a wide window, and in the
pane the list beside it is the way back.

## Layout adapts to the window, not to the composable

```kotlin
Modifier.padding(innerPadding).readableContent()   // on every screen's content root
MaterialTheme.breakpoint.isAtLeast(Breakpoint.Expanded)
```

`readableContent()` holds content to sixty characters of `bodyLarge` — derived from the
type scale, so it follows the reader's text size — and centres the column, not the text.
Centring text loses the leading edge that rows, avatars and icons align to; centre a block
only when it is the only thing on the screen.

Two panes from `Breakpoint.Expanded` up and never below, which is M3's rule for dense
content and also what `calculatePaneScaffoldDirective` does. `listPaneWidthFor` gives the
width.

Read the *window* through `MaterialTheme.breakpoint`, not the local constraints. A pane
300dp wide inside a 1400dp window is still in a large layout.

**Floors: at least 12 adaptive API uses, at least 2 navigation components.** These regress
by being removed, so the audit checks them from below.

## Motion comes from the scheme

```kotlin
MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()   // things that move
MaterialTheme.motionScheme.defaultEffectsSpec<Float>()       // things that fade
tween(300)                                                   // no
```

`MotionSchemeKeyTokens` is `internal` to material3 and cannot be reached from here;
`MaterialTheme.motionScheme` is the public surface. Honour `MaterialTheme.reducedMotion`
— it means drop the movement, not the transition.

## Checking your work

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

A change to the group Lightning wallet's node, co-signing or sweeps also has an end-to-end run against
a regtest network with a real LSP, which `jvmTest` reports as skipped unless asked for.
[testing/regtest/README.md](testing/regtest/README.md) has the prerequisites and what a failure means:

```bash
testing/regtest/regtest.sh up
```

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

`docs/scripts/` also holds the tools each phase was done with —
`m3-spacing-positions.py`, `m3-touch-targets.py`, `m3-title-case.py` and the two string
extractors — each of which takes `--list` to show the sites rather than the count.
