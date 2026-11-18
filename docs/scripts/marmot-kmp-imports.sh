#!/usr/bin/env bash
#
# Where the Marmot engine's classes come from: docs/marmot-kmp.md, Phase 0.
#
#   docs/scripts/marmot-kmp-imports.sh           lists the lines that still name quartz's engine
#   docs/scripts/marmot-kmp-imports.sh --check   and exits 1 if there are any
#   docs/scripts/marmot-kmp-imports.sh --apply   rewrites them onto marmot-kmp, mls-kmp and crypto-kmp
#   docs/scripts/marmot-kmp-imports.sh --revert   puts them back
#
# quartz 2.3.0 dropped the Marmot engine. What is left in quartz under
# `com.vitorpamplona.quartz.marmot` is the event and tag classes -- KeyPackageEvent,
# WelcomeEvent, GroupEvent, the MIP-05 push events -- and those must NOT be rewritten;
# the engine around them moved to three libraries. The rule table below is that split,
# derived by listing what quartz 2.15.1 still has under `marmot/` against what marmot-kmp
# 2.2.1 and mls-kmp 1.3.0 declare, so each line of it is a fact about two checkouts
# rather than a guess:
#
#   quartz.marmot.mls.*            -> com.vitorpamplona.mls.*          (mls-kmp, RFC 9420)
#   quartz.marmot.mls.crypto.*     -> com.vitorpamplona.crypto.*       (crypto-kmp, the curves)
#   quartz.marmot.mls.group.MlsGroup{Manager,StateStore}
#                                  -> com.vitorpamplona.marmot.groups.*
#   quartz.marmot.<the rest>       -> com.vitorpamplona.marmot.<the rest>
#   quartz.utils.{RandomInstance,sha256} -> com.vitorpamplona.crypto.*
#
# The manager and the store are the one place the positional rule breaks: everything else
# named MlsGroup* is mls-kmp's, those two are marmot-kmp's, and they are one letter apart.
#
# Reversible on purpose. The fork still cherry-picks from upstream
# (docs/giza-rebrand.md), upstream is on quartz 1.15.2 with the engine inside it, and
# every line this touches is a line a cherry-pick will conflict in -- so the resolution
# is to re-run the rule, not to read the diff.

set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

ROOT=composeApp/src

# old|new, applied in order. The specific rules come first: `mls.crypto.Ed25519` and
# `mls.group.MlsGroupManager` have to be taken before the general `mls.` rule reaches them.
RULES=(
  # Moved out of a package quartz still has. Everything else in these four stays.
  "com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageBundleStore|com.vitorpamplona.marmot.mip00KeyPackages.KeyPackageBundleStore"
  "com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEventFromMls|com.vitorpamplona.marmot.mip00KeyPackages.KeyPackageEventFromMls"
  "com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageFetcher|com.vitorpamplona.marmot.mip00KeyPackages.KeyPackageFetcher"
  "com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager|com.vitorpamplona.marmot.mip00KeyPackages.KeyPackageRotationManager"
  "com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils|com.vitorpamplona.marmot.mip00KeyPackages.KeyPackageUtils"
  "com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeGiftWrap|com.vitorpamplona.marmot.mip02Welcome.WelcomeGiftWrap"
  "com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEventEncryption|com.vitorpamplona.marmot.mip03GroupMessages.GroupEventEncryption"
  "com.vitorpamplona.quartz.marmot.mip05PushNotifications.MarmotPushStateStore|com.vitorpamplona.marmot.mip05PushNotifications.MarmotPushStateStore"
  "com.vitorpamplona.quartz.marmot.mip05PushNotifications.PushRecordStore|com.vitorpamplona.marmot.mip05PushNotifications.PushRecordStore"
  "com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenEncryption|com.vitorpamplona.marmot.mip05PushNotifications.TokenEncryption"

  # The curves are crypto-kmp's; the MLS engine only used them.
  "com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519|com.vitorpamplona.crypto.curve25519.Ed25519"
  "com.vitorpamplona.quartz.marmot.mls.crypto.X25519|com.vitorpamplona.crypto.curve25519.X25519"

  # The manager and the store are Marmot's, not MLS's. Before the general mls. rule.
  "com.vitorpamplona.quartz.marmot.mls.group.MlsGroupManager|com.vitorpamplona.marmot.groups.MlsGroupManager"
  "com.vitorpamplona.quartz.marmot.mls.group.MlsGroupStateStore|com.vitorpamplona.marmot.groups.MlsGroupStateStore"

  # Everything else under mls. is mls-kmp's, same subpackage, same name.
  "com.vitorpamplona.quartz.marmot.mls.|com.vitorpamplona.mls."

  # Packages quartz keeps nothing of.
  "com.vitorpamplona.quartz.marmot.appComponents.|com.vitorpamplona.marmot.appComponents."
  "com.vitorpamplona.quartz.marmot.client.|com.vitorpamplona.marmot.client."
  "com.vitorpamplona.quartz.marmot.foundation.|com.vitorpamplona.marmot.foundation."
  "com.vitorpamplona.quartz.marmot.groups.|com.vitorpamplona.marmot.groups."
  "com.vitorpamplona.quartz.marmot.mip01Groups.|com.vitorpamplona.marmot.mip01Groups."
  "com.vitorpamplona.quartz.marmot.mip04EncryptedMedia.|com.vitorpamplona.marmot.mip04EncryptedMedia."
  "com.vitorpamplona.quartz.marmot.protocolCore.|com.vitorpamplona.marmot.protocolCore."

  # The engine's own top-level classes, named one by one so the rule cannot reach into
  # the packages above it.
  "com.vitorpamplona.quartz.marmot.GroupEventResult|com.vitorpamplona.marmot.GroupEventResult"
  "com.vitorpamplona.quartz.marmot.MarmotFilters|com.vitorpamplona.marmot.MarmotFilters"
  "com.vitorpamplona.quartz.marmot.MarmotInboundProcessor|com.vitorpamplona.marmot.MarmotInboundProcessor"
  "com.vitorpamplona.quartz.marmot.MarmotIngestDedupStore|com.vitorpamplona.marmot.MarmotIngestDedupStore"
  "com.vitorpamplona.quartz.marmot.MarmotOutboundProcessor|com.vitorpamplona.marmot.MarmotOutboundProcessor"
  "com.vitorpamplona.quartz.marmot.MarmotSubscriptionManager|com.vitorpamplona.marmot.MarmotSubscriptionManager"
  "com.vitorpamplona.quartz.marmot.MarmotWelcomeSender|com.vitorpamplona.marmot.MarmotWelcomeSender"
  "com.vitorpamplona.quartz.marmot.RecipientRelayFetcher|com.vitorpamplona.marmot.RecipientRelayFetcher"

  # Not the engine, but quartz 2.x no longer declares either: both are crypto-kmp's now.
  "com.vitorpamplona.quartz.utils.sha256.sha256|com.vitorpamplona.crypto.sha2.sha256"
  "com.vitorpamplona.quartz.utils.RandomInstance|com.vitorpamplona.crypto.random.RandomInstance"
)

mode="${1:-list}"

rewrite() {
  local reverse="$1" count=0
  for rule in "${RULES[@]}"; do
    local old="${rule%%|*}" new="${rule##*|}"
    if [ "$reverse" = "yes" ]; then
      local tmp="$old"; old="$new"; new="$tmp"
    fi
    # Only the lines that name it, so a file with none is not rewritten at all.
    local files
    files=$(grep -rlF "$old" --include="*.kt" "$ROOT" 2>/dev/null)
    [ -z "$files" ] && continue
    local hits
    hits=$(grep -rhoF "$old" --include="*.kt" "$ROOT" 2>/dev/null | wc -l)
    count=$((count + hits))
    # shellcheck disable=SC2086
    sed -i "s|${old//./\\.}|$new|g" $files
    printf "  %4d  %s\n           -> %s\n" "$hits" "$old" "$new"
  done
  echo "$count line(s) rewritten."
}

case "$mode" in
  --apply)
    echo "Rewriting onto marmot-kmp, mls-kmp and crypto-kmp:"
    rewrite no
    echo
    echo "Imports are not sorted by this script. ./gradlew check does not sort them either,"
    echo "but a rewritten import keeps its old alphabetical slot -- fix the order by hand"
    echo "where it reads wrong."
    ;;
  --revert)
    echo "Putting quartz's engine back:"
    rewrite yes
    ;;
  --check|list|"")
    # A moved symbol that is still named. The event classes quartz keeps are not in RULES,
    # so they are not reported.
    found=0
    for rule in "${RULES[@]}"; do
      old="${rule%%|*}"
      if grep -rnF "$old" --include="*.kt" "$ROOT" 2>/dev/null; then
        found=1
      fi
    done
    if [ "$found" = 1 ]; then
      echo "The Marmot engine is not quartz's any more. See docs/marmot-kmp.md." >&2
      [ "$mode" = "--check" ] && exit 1
    else
      echo "quartz's Marmot engine: 0 references"
    fi
    ;;
  *)
    echo "usage: $0 [--check|--apply|--revert]" >&2
    exit 2
    ;;
esac
