#!/usr/bin/env bash
#
# Material Design 3 conformance audit.
#
# Regenerates every count quoted in docs/material-design-conformance.md. The plan
# in that document has acceptance criteria per phase; this is what checks them.
#
# Usage:
#   docs/scripts/m3-audit.sh            report, always exit 0
#   docs/scripts/m3-audit.sh --check    report, exit 1 if any budget is exceeded
#
# The budgets at the top are the state of the tree at the phase named beside each
# one. They ratchet down as phases land: lower the number in the same commit that
# earns it, never raise one. Phase 8 wires --check into CI, at which point raising
# a budget is what a reviewer looks for.

set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

UI=composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui
THEME="$UI/theme"

# ---------------------------------------------------------------------------
# Budgets. "-1" means not yet budgeted -- reported, but never fails --check.
# ---------------------------------------------------------------------------
BUDGET_HARDCODED_COLOR=0       # phase 3: reached 2026-09-08
BUDGET_SPACING_LITERALS=0      # phase 2: reached 2026-09-08
BUDGET_BARE_CLICKABLE=0        # phase 3: reached 2026-09-08
BUDGET_NULL_DESCRIPTION=0      # phase 3: reached 2026-09-08
BUDGET_STRING_LITERALS=-1      # phase 4 drives to <10
BUDGET_TITLE_CASE=0            # phase 4: reached 2026-09-08
BUDGET_UNSET_COLOR_ROLES=0     # phase 1: reached 2026-09-07

# A floor rather than a ceiling: --check fails when the count drops *below* it. The
# adaptive work is the one thing in this document that a later edit removes rather
# than adds -- a screen that stops reading the breakpoint still compiles and still
# renders -- so the budget that protects it has to point the other way.
FLOOR_ADAPTIVE_APIS=12         # phase 6: reached 2026-09-08
FLOOR_NAVIGATION_COMPONENTS=2  # phase 6: reached 2026-09-08

fail_count=0

hdr()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
note() { printf '   %s\n' "$1"; }

# floor <label> <value> <minimum>
# The mirror of report(), for counts a phase drives *up*. Used by the adaptive
# section, where the regression to catch is a screen quietly losing its breakpoint.
floor() {
  local label=$1 value=$2 minimum=$3
  if (( value < minimum )); then
    printf '   %-42s %6s   \033[31mbelow floor %s\033[0m\n' "$label" "$value" "$minimum"
    fail_count=$((fail_count + 1))
  else
    printf '   %-42s %6s   (floor %s)\n' "$label" "$value" "$minimum"
  fi
}

# report <label> <value> <budget>
report() {
  local label=$1 value=$2 budget=$3
  if [[ $budget == "-1" ]]; then
    printf '   %-42s %6s   (no budget)\n' "$label" "$value"
  elif (( value > budget )); then
    printf '   %-42s %6s   \033[31mover budget %s\033[0m\n' "$label" "$value" "$budget"
    fail_count=$((fail_count + 1))
  else
    printf '   %-42s %6s   (budget %s)\n' "$label" "$value" "$budget"
  fi
}

# Lines that are comments rather than code. Without this a note *about* a hardcoded
# colour counts as one -- which happened the first time a call site was fixed and the
# commit explained what it had replaced.
NOT_A_COMMENT='^[^:]*:[[:space:]]*(//|\*|/\*)'

# A colour that genuinely cannot come from a role -- a QR code's modules, a control
# floating over an arbitrary photograph -- is marked at the site with
# `// m3-color-exempt: <reason>` on the lines above it, the same convention
# m3-spacing-positions.py uses. `grep -A` pulls the following lines in so the marker
# above a literal suppresses it; the reason travels with the code rather than living
# in a list of file names in this script.
hardcoded_colours() {
  grep -rE -A 8 'm3-color-exempt' "$UI" --include=*.kt 2>/dev/null \
    | grep -E 'Color\(0x|Color\.(Red|Blue|Green|Gray|LightGray|DarkGray|White|Black|Yellow|Magenta|Cyan)' \
    | sed 's/^\([^-:]*\)[-:]/\1:/' | sort -u > /tmp/.m3-exempt-lines.$$
  grep -rE 'Color\(0x|Color\.(Red|Blue|Green|Gray|LightGray|DarkGray|White|Black|Yellow|Magenta|Cyan)' \
    "$UI" --include=*.kt 2>/dev/null \
    | grep -v "^$THEME/" | grep -vE "$NOT_A_COMMENT" \
    | grep -vxFf /tmp/.m3-exempt-lines.$$ 2>/dev/null
  rm -f /tmp/.m3-exempt-lines.$$
}

# Count matches across the UI tree, optionally excluding the theme package.
# $1 pattern, $2 "exclude-theme" | "all"
count() {
  local pattern=$1 scope=${2:-all}
  if [[ $scope == exclude-theme ]]; then
    grep -rE "$pattern" "$UI" --include=*.kt 2>/dev/null \
      | grep -v "^$THEME/" | grep -vE "$NOT_A_COMMENT" | wc -l | tr -d ' '
  else
    grep -rE "$pattern" "$UI" --include=*.kt 2>/dev/null \
      | grep -vE "$NOT_A_COMMENT" | wc -l | tr -d ' '
  fi
}

printf '\033[1mMaterial Design 3 conformance audit\033[0m\n'
printf 'tree: %s\n' "$(git rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout')"
printf 'over: %s\n' "$UI"

# ---------------------------------------------------------------------------
hdr 'Colour  (phases 1, 3)'

# Roles ColorScheme declares that Theme.kt never assigns. An unassigned role
# falls through to the Material baseline palette -- lavender, in a monochrome
# app -- so this is a defect count, not a style count.
declared=$(grep -oE '^\s{4}[a-zA-Z]+ = ' "$THEME/Theme.kt" 2>/dev/null \
  | tr -d ' =' | sort -u)
# The 49 roles of androidx.compose.material3.ColorScheme, as of material3
# 1.10.0-alpha05. Hardcoded because the artifact is not on this script's path.
all_roles="primary onPrimary primaryContainer onPrimaryContainer inversePrimary
secondary onSecondary secondaryContainer onSecondaryContainer
tertiary onTertiary tertiaryContainer onTertiaryContainer
background onBackground surface onSurface surfaceVariant onSurfaceVariant
surfaceTint inverseSurface inverseOnSurface error onError errorContainer
onErrorContainer outline outlineVariant scrim surfaceBright surfaceDim
surfaceContainer surfaceContainerHigh surfaceContainerHighest
surfaceContainerLow surfaceContainerLowest
primaryFixed primaryFixedDim onPrimaryFixed onPrimaryFixedVariant
secondaryFixed secondaryFixedDim onSecondaryFixed onSecondaryFixedVariant
tertiaryFixed tertiaryFixedDim onTertiaryFixed onTertiaryFixedVariant"
# A role left unassigned takes lightColorScheme()'s default. For the twelve
# *Fixed* roles that default is ColorLightTokens.PrimaryFixed and friends --
# PaletteTokens.Primary90, #EADDFF -- so a monochrome app renders Material
# baseline lavender. For surfaceTint the default is `primary`, which is right.
# Only the first kind is a defect, so they are counted apart.
unset_baseline=0; unset_derived=0
baseline_list=""; derived_list=""
for role in $all_roles; do
  echo "$declared" | grep -qx "$role" && continue
  case $role in
    *Fixed|*FixedDim|*FixedVariant)
      unset_baseline=$((unset_baseline + 1)); baseline_list="$baseline_list $role" ;;
    *)
      unset_derived=$((unset_derived + 1)); derived_list="$derived_list $role" ;;
  esac
done
report 'roles falling to the baseline palette' "$unset_baseline" "$BUDGET_UNSET_COLOR_ROLES"
[[ -n $baseline_list ]] && note "lavender:$baseline_list"
[[ -n $derived_list ]] && note "derived (not a defect):$derived_list"

hardcoded=$(hardcoded_colours | wc -l | tr -d ' ')
report 'hardcoded Color outside theme/' "$hardcoded" "$BUDGET_HARDCODED_COLOR"
[[ $hardcoded -gt 0 ]] && hardcoded_colours | cut -d: -f1 | sort -u | sed "s|$UI/|     |"

alpha=$(count '\.copy\(alpha')
report 'colours derived with .copy(alpha =)' "$alpha" -1

# ---------------------------------------------------------------------------
hdr 'Spacing  (phase 2)'

# Classified by call shape rather than by value, which is the only thing that says
# whether a given literal is spacing or a dimension: 16.dp is a spacing stop and also a
# plausible icon size, and 50.dp was a Spacer height in 53 places and a divider width in
# one. m3-spacing-positions.py does the parse; it exits 1 while any spacing literal is
# left, which is phase 2's acceptance criterion.
spacing_report=$(python3 docs/scripts/m3-spacing-positions.py)
spacing_left=$(echo "$spacing_report" | awk '/spacing positions/ {print $NF}')
dimensions=$(echo "$spacing_report" | grep 'dimension positions' | grep -oE '[0-9]+')
report 'dp literals in spacing positions' "$spacing_left" "$BUDGET_SPACING_LITERALS"
note "in dimension positions (out of scope): $dimensions"
note 'run docs/scripts/m3-spacing-positions.py --list to see them'


# ---------------------------------------------------------------------------
hdr 'Typography  (phase 1)'

typo_total=$(count 'MaterialTheme\.typography\.')
note "MaterialTheme.typography reads:         $typo_total"
grep -rhoE 'MaterialTheme\.typography\.[a-zA-Z]+' "$UI" --include=*.kt 2>/dev/null \
  | sed 's/.*typography\.//' | sort | uniq -c | sort -rn \
  | awk '{printf "     %-26s %s\n", $2, $1}'
label_uses=$(grep -rhoE 'MaterialTheme\.typography\.label[A-Za-z]*' "$UI" --include=*.kt 2>/dev/null | wc -l | tr -d ' ')
note "of which label* roles:                  $label_uses"
fontsize=$(count 'fontSize = [0-9]')
note "hardcoded fontSize:                     $fontsize"

# ---------------------------------------------------------------------------
hdr 'Targets and labels  (phase 3)'

# Counting `.clickable` was never the question -- a clickable Card is fine and a
# clickable Text is not, and only the minimum-size modifier tells them apart.
targets_left=$(python3 docs/scripts/m3-touch-targets.py | grep -oE '[0-9]+$')
report 'clickable chains with no minimum target' "$targets_left" "$BUDGET_BARE_CLICKABLE"
note 'run docs/scripts/m3-touch-targets.py --list to see them'
# `null` and `Decorative` compile to the same thing; the difference is that one of them
# is a decision. Untriaged icons are the count that matters.
null_desc=$(count 'contentDescription = null')
report 'contentDescription = null (untriaged)' "$null_desc" "$BUDGET_NULL_DESCRIPTION"
note "marked Decorative: $(count 'contentDescription = Decorative')"
icons=$(count 'Icon\(')
note "Icon( call sites:                       $icons"
min_size=$(count 'minimumInteractiveComponentSize')
note "minimumInteractiveComponentSize:        $min_size"
centred=$(count 'TextAlign\.Center')
note "TextAlign.Center:                       $centred"

# ---------------------------------------------------------------------------
hdr 'Content  (phase 4)'

# A file whose strings are sample text rather than UI text -- ThemeGallery, whose whole
# job is to render colour pairings and whose words are chosen to be words -- marks itself
# once at the top with `// m3-string-exempt: <reason>`. Per file rather than per line,
# because the exemption is a property of what the file is for, and eight markers down one
# gallery would say less than one at the top of it.
#
# The same shape as `m3-color-exempt` and `m3-spacing-exempt`: the reason travels with the
# code rather than living in a list of file names here.
sample_text_files() {
  grep -rlE 'm3-string-exempt' "$UI" --include=*.kt 2>/dev/null
}

string_literals() {
  local exempt
  exempt=$(sample_text_files | sed 's|^|^|' )
  if [[ -z $exempt ]]; then
    grep -rE 'text = "|Text\("' "$UI" --include=*.kt 2>/dev/null | grep -vE "$NOT_A_COMMENT"
  else
    grep -rE 'text = "|Text\("' "$UI" --include=*.kt 2>/dev/null | grep -vE "$NOT_A_COMMENT" \
      | grep -vE "$(sample_text_files | paste -sd'|' -)"
  fi
}

literals=$(string_literals | wc -l | tr -d ' ')
report 'string literals in composables' "$literals" "$BUDGET_STRING_LITERALS"
note "sample-text files exempt: $(sample_text_files | wc -l | tr -d ' ')"
res=$(count 'stringResource|Res\.string')
note "stringResource / Res.string:            $res"
# Delegated, because the grep version was wrong twice: it required every word after the
# first to be capitalised (missing "Invite a Friend") and it read one line at a time
# (missing a `Text(` whose literal was on the next). It also counted sample data --
# "Steve Biko" is title case because that is how a name is written.
title_case=$(python3 docs/scripts/m3-title-case.py | grep -oE '[0-9]+$')
report 'Title Case in UI strings' "$title_case" "$BUDGET_TITLE_CASE"
note 'run docs/scripts/m3-title-case.py --list to see them'

# ---------------------------------------------------------------------------
hdr 'States and feedback  (phase 5)'

scaffolds=$(count '(^|[^A-Za-z])Scaffold\(')
snackbars=$(count 'Snackbar|SnackbarHost')
note "Scaffold( call sites:                   $scaffolds"
note "Snackbar / SnackbarHost:                $snackbars"
went_wrong=$(count '"Something went wrong"')
note '"Something went wrong" sites:           '"$went_wrong"
for c in FilledTonalButton OutlinedButton ElevatedButton Button TextButton; do
  n=$(grep -rhoE "\b$c\(" "$UI" --include=*.kt 2>/dev/null | wc -l | tr -d ' ')
  note "$(printf '%-38s' "$c:")$n"
done

# ---------------------------------------------------------------------------
hdr 'Adaptive and motion  (phases 6, 7)'

adaptive=$(count 'WindowSizeClass|currentWindowAdaptiveInfo|currentWindowDpSize|NavigationSuiteScaffold|ListDetailPaneScaffold|SupportingPaneScaffold|BoxWithConstraints|MaterialTheme\.breakpoint|listPaneWidthFor')
floor "adaptive APIs in use" "$adaptive" "$FLOOR_ADAPTIVE_APIS"
# NavigationSuiteScaffold rather than the components themselves: it is what phase 6
# uses, and it chooses between ShortNavigationBar, WideNavigationRail collapsed and
# WideNavigationRail expanded per breakpoint. Counting only the concrete components
# reported zero for an app that had just grown a navigation bar.
nav=$(count 'NavigationBar\(|NavigationRail\(|WideNavigationRail\(|ShortNavigationBar\(|NavigationSuiteScaffold\(|NavigationSuiteItem\(')
floor "navigation components" "$nav" "$FLOOR_NAVIGATION_COMPONENTS"
motion=$(count 'AnimatedVisibility|AnimatedContent|Crossfade|MotionScheme|updateTransition')
note "motion APIs in use:                     $motion"
transitions=$(count 'enterTransition|exitTransition|popEnterTransition')
note "navigation transitions:                 $transitions"

# ---------------------------------------------------------------------------
printf '\n'
if [[ ${1:-} == --check ]]; then
  if (( fail_count > 0 )); then
    printf '\033[31m%s budget(s) exceeded.\033[0m See docs/material-design-conformance.md.\n' "$fail_count"
    exit 1
  fi
  printf '\033[32mAll budgets met.\033[0m\n'
fi
exit 0
