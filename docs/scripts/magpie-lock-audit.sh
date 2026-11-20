#!/usr/bin/env bash
#
# The catalogue and the Magpie lock agree: docs/quartz-mls-marmot-update.md, Phase 7.
#
#   docs/scripts/magpie-lock-audit.sh           lists what it checked
#   docs/scripts/magpie-lock-audit.sh --check   and exits 1 on any problem
#
# quartz, marmot-kmp, mls-kmp and crypto-kmp come from Nostr + Blossom through the Magpie
# settings plugin, and gradle/magpie.lock pins the manifest of every module resolved. A
# frozen lock catches a version the lock lacks -- but only at resolution time, and only for
# the targets the machine resolves: a lock bumped on Linux has no iOS lines, and Linux never
# notices. This reads the three files as text, so it says the same on every machine:
#
#   - every library in gradle/libs.versions.toml whose group settings.gradle.kts hands to
#     Magpie has a `release` line in the lock for each module suffix below, at the
#     catalogue's version;
#   - and that version is declared `strictly` (D3), because gradle otherwise resolves to
#     the highest version anything asks for, and the catalogue stops being the graph.
#
# Transitive groups (negentropy, schnorr256k1) are not in the catalogue, so they are the
# frozen lock's to catch, not this script's.

set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

SETTINGS=settings.gradle.kts
CATALOG=gradle/libs.versions.toml
LOCK=gradle/magpie.lock

# The root module and one per target the lock has to cover. Add "-iosarm64" and
# "-iossimulatorarm64" once a mac has written them (Phase 6), and this requires them for
# every later bump.
SUFFIXES=("" "-jvm" "-android")

CHECK=0
[[ "${1:-}" == "--check" ]] && CHECK=1

for f in "$SETTINGS" "$CATALOG" "$LOCK"; do
    [[ -f "$f" ]] || { echo "magpie-lock-audit: $f is missing"; exit 1; }
done

# The groups inside settings.gradle.kts's `magpie { }` block.
mapfile -t MAGPIE_GROUPS < <(awk '/^magpie[[:space:]]*\{/{inside=1; next} inside && /^\}/{inside=0} inside' "$SETTINGS" \
    | sed -nE 's/.*group\("([^"]+)"\).*/\1/p')

if [[ ${#MAGPIE_GROUPS[@]} -eq 0 ]]; then
    echo "magpie-lock-audit: no magpie { group(...) } block in $SETTINGS"
    exit $CHECK
fi

# version name -> "value|strict", from [versions].
declare -A VERSION
while IFS='|' read -r name value strict; do
    VERSION[$name]="$value|$strict"
done < <(awk '/^\[versions\]/{v=1; next} /^\[/{v=0} v' "$CATALOG" | sed -nE \
    -e 's/^([A-Za-z0-9_-]+)[[:space:]]*=[[:space:]]*\{[[:space:]]*strictly[[:space:]]*=[[:space:]]*"([^"]+)".*/\1|\2|strict/p' \
    -e 's/^([A-Za-z0-9_-]+)[[:space:]]*=[[:space:]]*"([^"]+)".*/\1|\2|plain/p')

problems=0
checked=0
while IFS='|' read -r module ref; do
    group=${module%%:*}
    artifact=${module#*:}
    in_magpie=0
    for g in "${MAGPIE_GROUPS[@]}"; do
        [[ "$group" == "$g" || "$group" == "$g".* ]] && in_magpie=1
    done
    [[ $in_magpie -eq 1 ]] || continue

    entry=${VERSION[$ref]:-}
    if [[ -z "$entry" ]]; then
        echo "  $module: version.ref \"$ref\" is not in [versions]"
        problems=$((problems + 1)); continue
    fi
    version=${entry%%|*}
    kind=${entry#*|}
    checked=$((checked + 1))

    if [[ "$kind" != "strict" ]]; then
        echo "  $module:$version is not declared strictly in [versions] ($ref)"
        problems=$((problems + 1))
    fi
    for suffix in "${SUFFIXES[@]}"; do
        if ! grep -qE "^release ${group//./\\.}:${artifact}${suffix}:${version//./\\.} " "$LOCK"; then
            echo "  $group:$artifact$suffix:$version has no release line in $LOCK"
            problems=$((problems + 1))
        fi
    done
done < <(awk '/^\[libraries\]/{l=1; next} /^\[/{l=0} l' "$CATALOG" | sed -nE \
    's/.*module[[:space:]]*=[[:space:]]*"([^"]+)".*version\.ref[[:space:]]*=[[:space:]]*"([^"]+)".*/\1|\2/p')

echo "magpie-lock-audit: ${#MAGPIE_GROUPS[@]} Magpie groups, $checked catalogue libraries in them, ${#SUFFIXES[@]} modules each, $problems problems"

if [[ $problems -gt 0 ]]; then
    echo "  Resolve once without a frozen lock on a machine that builds every target, and commit gradle/magpie.lock."
    echo "  docs/quartz-mls-marmot-update.md"
    exit $CHECK
fi
exit 0
