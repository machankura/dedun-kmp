#!/usr/bin/env bash
#
# The Dedun-named mirror of Bennu: docs/giza-into-dedun.md, Phase 0.
#
#   docs/scripts/bennu-mirror.sh <bennu-commit>...    prints one mirror commit per argument
#   git cherry-pick $(docs/scripts/bennu-mirror.sh <bennu-commit>)
#
# Dedun is Bennu under com.machankura.dedun (docs/dedun-rename.md), so a Bennu commit's diff
# names files and packages Dedun does not have, and its base blobs carry those names. This
# script makes, for a Bennu commit C, a commit M(C) whose tree is C's with Dedun's rename
# applied and whose parent holds C^1's tree renamed the same way. Cherry-picking M(C) onto
# Dedun then merges against the right base, and what conflicts is a real difference.
#
# The rename is dedun-rename.md's Phases 1 to 4 as rules: the source sets move from
# com/machankura/bennu to com/machankura/dedun, files named *Bennu* become *Dedun*, the Room
# schema directory moves, the package, Res and identifier rules apply, the three string keys
# and the developer switches are renamed, and inside composeApp/src and testing/ the words
# Bennu and bennu become Dedun and dedun -- except in the twelve `bennu/` hash tags, which are
# protocol and stay (DomainTagsPinnedTest).
#
# A Giza commit is mirrored in two steps: docs/scripts/giza-mirror.sh (Giza -> Bennu names),
# then this script on the commit that prints.
#
# Mirror commits keep C's author, date and message, and add a "Bennu: <sha>" trailer (a
# Giza commit's "Giza: <sha>" trailer, if C has one, is already in its message). They are
# cached as refs/bennu-mirror/<sha> and never pushed. The tree is built in a worktree under
# the common git directory, which leaves the caller's checkout alone.

set -euo pipefail

GIT_COMMON=$(git rev-parse --path-format=absolute --git-common-dir)
WT=$GIT_COMMON/bennu-mirror-wt

ensure_worktree() {
    if [[ ! -d $WT ]]; then
        git worktree add -q --detach "$WT" HEAD >/dev/null
    fi
}

# The rename, applied in the current directory.
rename_tree() {
    for set in androidMain commonMain commonTest iosMain jvmMain jvmTest androidUnitTest androidInstrumentedTest; do
        root=composeApp/src/$set/kotlin
        if [[ -d $root/com/machankura/bennu ]]; then
            mv "$root/com/machankura/bennu" "$root/com/machankura/dedun"
        fi
    done
    while IFS= read -r -d '' file; do
        base=$(basename "$file")
        mv "$file" "$(dirname "$file")/${base//Bennu/Dedun}"
    done < <(find composeApp/src testing -type f -name '*Bennu*' -print0 2>/dev/null)
    if [[ -d composeApp/schemas/com.machankura.bennu.compose.database.BennuDatabase ]]; then
        mv composeApp/schemas/com.machankura.bennu.compose.database.BennuDatabase \
            composeApp/schemas/com.machankura.dedun.compose.database.DedunDatabase
    fi
    # Packages, Res, the brand-named identifiers, the string keys and the developer switches,
    # everywhere but the docs that describe the renames.
    grep -rlIE --exclude-dir=.git --exclude-dir=lightning-kmp-app --exclude-dir=experimental \
        'com\.machankura\.bennu|com/machankura/bennu|bennu\.composeapp|Bennu[A-Z]|bennu[A-Z]|BENNU_|_bennu_|bennu_will|NotYetOnBennu|defaultBennuDir|rootProject\.name = "Bennu"|bennu-(regtest|lsp|payer)' . \
        | grep -vE '^\./docs/(dedun-rename\.md|giza-rebrand\.md|scripts/(giza-rename|giza-mirror|bennu-mirror)\.sh)$' \
        | xargs -r sed -i -E \
            -e 's#com\.machankura\.bennu#com.machankura.dedun#g' \
            -e 's#com/machankura/bennu#com/machankura/dedun#g' \
            -e 's#bennu\.composeapp\.generated\.resources#dedun.composeapp.generated.resources#g' \
            -e 's#\bBennu([A-Z][A-Za-z]*)#Dedun\1#g' \
            -e 's#\bbennu([A-Z][A-Za-z]*)#dedun\1#g' \
            -e 's#\bNotYetOnBennu\b#NotYetOnDedun#g' \
            -e 's#\bdefaultBennuDir\b#defaultDedunDir#g' \
            -e 's#rootProject\.name = "Bennu"#rootProject.name = "Dedun"#g' \
            -e 's#\bBENNU_#DEDUN_#g' \
            -e 's#_on_bennu_yet#_on_dedun_yet#g' \
            -e 's#\bbennu_will_#dedun_will_#g' \
            -e 's#bennu-(regtest|lsp|payer)#dedun-\1#g'
    # The app's own name, in what the app says, sends and tests -- but never a hash tag.
    grep -rlIE '\bBennu\b|\bbennu\b' composeApp/src testing 2>/dev/null \
        | xargs -r sed -i -E \
            -e 's#"bennu/#"\x01/#g' -e 's#`bennu/#`\x01/#g' \
            -e 's/\bBennu\b/Dedun/g' -e 's/\bbennu\b/dedun/g' \
            -e 's#\x01/#bennu/#g'
}

# The tree of a Bennu commit with Dedun's names, cached by the Bennu commit's own tree.
renamed_tree() {
    local commit=$1 source_tree cached
    source_tree=$(git rev-parse "$commit^{tree}")
    cached=$(git rev-parse -q --verify "refs/bennu-mirror/tree/$source_tree" 2>/dev/null || true)
    if [[ -n $cached ]]; then
        git rev-parse "$cached^{tree}"
        return
    fi
    ensure_worktree
    (
        cd "$WT"
        git checkout -q --detach -f "$commit"
        git clean -qfdx
        rename_tree
        git add -A >/dev/null
        tree=$(git write-tree)
        git reset -q
        git update-ref "refs/bennu-mirror/tree/$source_tree" "$(git commit-tree "$tree" -m "renamed tree $source_tree")"
        echo "$tree"
    )
}

mirror_commit() {
    local commit=$1 full cached parent_tree tree parent message
    full=$(git rev-parse "$commit^{commit}")
    cached=$(git rev-parse -q --verify "refs/bennu-mirror/$full" 2>/dev/null || true)
    if [[ -n $cached ]]; then
        echo "$cached"
        return
    fi
    parent_tree=$(renamed_tree "$full^1")
    tree=$(renamed_tree "$full")
    parent=$(git commit-tree "$parent_tree" -m "renamed parent of $full")
    message=$(git log -1 --format=%B "$full")
    local mirror
    mirror=$(
        GIT_AUTHOR_NAME=$(git log -1 --format=%an "$full") \
        GIT_AUTHOR_EMAIL=$(git log -1 --format=%ae "$full") \
        GIT_AUTHOR_DATE=$(git log -1 --format=%aI "$full") \
        git commit-tree "$tree" -p "$parent" -m "$message" -m "Bennu: $full"
    )
    git update-ref "refs/bennu-mirror/$full" "$mirror"
    echo "$mirror"
}

# `--tree <commit>` prints the renamed tree alone, for comparing a whole state.
if [[ ${1:-} == --tree ]]; then
    shift
    for commit in "$@"; do renamed_tree "$commit"; done
    exit 0
fi

if (( $# == 0 )); then
    sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
fi

for commit in "$@"; do
    mirror_commit "$commit"
done
