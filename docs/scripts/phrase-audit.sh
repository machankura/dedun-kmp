#!/usr/bin/env bash
#
# No profile phrase is made from random bytes: docs/giza-second-batch.md, Phase 2f.
#
#   docs/scripts/phrase-audit.sh           lists the lines that break the rule
#   docs/scripts/phrase-audit.sh --check   and exits 1 if there are any
#
# A new profile from Landing is backed by a recovery code, whose 24 words are made in
# identity/recoverycode/RecoveryCodeKeys.kt and nowhere else, so the code is always the
# backup of a profile and its wallet. Phrases are restored, never made.
#
# The one other phrase Dedun makes is a separate wallet's own seed, not a profile's -- the
# Wavelength wallet's twelve words (wallet/wavelength/WalletSeedStore.kt). A site like that
# says so on the line above the call:
#
#   // phrase-audit-exempt: <why this phrase is not a profile's>
#
# Needs only bash and git, like the other audits.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

CHECK=0
[[ ${1:-} == --check ]] && CHECK=1

MAIN=(':(glob)composeApp/src/*Main/**/*.kt')

lines=$(
    git grep -nE 'MnemonicCode\.toMnemonics' -- "${MAIN[@]}" ':!*/identity/recoverycode/RecoveryCodeKeys.kt' \
        | while IFS=: read -r file line _; do
            previous=$(sed -n "$((line - 1))p" "$file")
            if [[ $previous != *'phrase-audit-exempt:'* ]]; then
                printf '%s:%s: %s\n' "$file" "$line" "$(sed -n "${line}p" "$file" | sed 's/^ *//')"
            fi
        done || true
)

n=0
[[ -n "$lines" ]] && n=$(printf '%s\n' "$lines" | wc -l)
echo "Phrases made from random bytes outside RecoveryCodeKeys: $n"
[[ -n "$lines" ]] && printf '%s\n' "$lines" | sed 's/^/   /'

if (( CHECK && n > 0 )); then
    echo "A new profile is backed by a recovery code. See docs/giza-second-batch.md, Phase 2f." >&2
    exit 1
fi
