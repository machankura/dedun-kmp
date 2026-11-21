#!/usr/bin/env bash
#
# The debranded mirror of Giza: docs/giza-into-bennu.md, Phase 0.
#
#   docs/scripts/giza-mirror.sh <giza-commit>...      prints one mirror commit per argument
#   git cherry-pick $(docs/scripts/giza-mirror.sh <giza-commit>)
#
# Giza is Bennu under mobi.giza, so a Giza commit's diff names files and packages Bennu does
# not have, and its base blobs carry those names. A patch rewritten with sed applies to
# nothing, and a three-way merge conflicts on every package line. This script makes, for a
# Giza commit C, a commit M(C) whose tree is C's with the rename undone and whose parent is a
# commit holding C^1's tree, undone the same way. Cherry-picking M(C) onto Bennu then merges
# against the right base, and what conflicts is a real difference -- almost always wallet
# code, which Giza removed before it wrote any of this.
#
# Undoing the rename is docs/scripts/giza-rename.sh (on Giza) run backwards: the source sets
# move from mobi/giza to com/machankura/bennu, files named *Giza* become *Bennu*, the Room
# schema directory moves back, the package, Res and identifier rules are reversed, and inside
# composeApp/src the words Giza and giza become Bennu and bennu.
#
# Mirror commits keep C's author, date and message, with a "Giza: <sha>" trailer that a
# cherry-pick carries into Bennu's history, so `git log --grep 'Giza: <sha>'` finds the port.
# They are cached as refs/giza-mirror/<sha> and never pushed. The tree is built in a worktree
# under the common git directory, which leaves the caller's checkout alone.

set -euo pipefail

GIT_COMMON=$(git rev-parse --path-format=absolute --git-common-dir)
WT=$GIT_COMMON/giza-mirror-wt

ensure_worktree() {
    if [[ ! -d $WT ]]; then
        git worktree add -q --detach "$WT" HEAD >/dev/null
    fi
}

# The tree of a Giza commit with Bennu's names, cached by the Giza commit's own tree.
debranded_tree() {
    local commit=$1 source_tree cached
    source_tree=$(git rev-parse "$commit^{tree}")
    cached=$(git rev-parse -q --verify "refs/giza-mirror/tree/$source_tree" 2>/dev/null || true)
    if [[ -n $cached ]]; then
        git rev-parse "$cached^{tree}"
        return
    fi
    ensure_worktree
    (
        cd "$WT"
        git checkout -q --detach -f "$commit"
        git clean -qfdx
        for set in androidMain commonMain commonTest iosMain jvmMain jvmTest; do
            root=composeApp/src/$set/kotlin
            if [[ -d $root/mobi/giza ]]; then
                mkdir -p "$root/com/machankura"
                mv "$root/mobi/giza" "$root/com/machankura/bennu"
                rmdir "$root/mobi" 2>/dev/null || true
            fi
        done
        while IFS= read -r -d '' file; do
            base=$(basename "$file")
            mv "$file" "$(dirname "$file")/${base//Giza/Bennu}"
        done < <(find composeApp/src -type f -name '*Giza*' -print0)
        if [[ -d composeApp/schemas/mobi.giza.compose.database.GizaDatabase ]]; then
            mv composeApp/schemas/mobi.giza.compose.database.GizaDatabase \
                composeApp/schemas/com.machankura.bennu.compose.database.BennuDatabase
        fi
        # Packages, Res and identifiers, everywhere but the two files that name them on purpose.
        grep -rlIE --exclude-dir=.git --exclude-dir=lightning-kmp-app \
            'mobi\.giza|mobi/giza|Giza(Database|DatabaseConstructor|DatabaseJvmTest|Global|Application|App|Converters|NavHost|NavigationSuite|Theme|Shapes)\b|gizaGlobal|NotYetOnGiza|defaultGizaDir' . \
            | grep -vE '^\./docs/(giza-rebrand\.md|scripts/giza-rename\.sh)$' \
            | xargs -r sed -i -E \
                -e 's#mobi\.giza\.compose\.resources#bennu.composeapp.generated.resources#g' \
                -e 's#mobi\.giza\.compose\.database\.GizaDatabase#com.machankura.bennu.compose.database.BennuDatabase#g' \
                -e 's#mobi\.giza\.(compose|android|desktop)#com.machankura.bennu.\1#g' \
                -e 's#mobi/giza/(compose|android|desktop)#com/machankura/bennu/\1#g' \
                -e 's#\bGiza(Database|DatabaseConstructor|DatabaseJvmTest|Global|Application|App|Converters|NavHost|NavigationSuite|Theme|Shapes)\b#Bennu\1#g' \
                -e 's#\bgizaGlobal\b#bennuGlobal#g' \
                -e 's#\bNotYetOnGiza\b#NotYetOnBennu#g' \
                -e 's#\bdefaultGizaDir\b#defaultBennuDir#g'
        # The app's own name, in what the app says and sends.
        grep -rlIE '\bGiza\b|\bgiza\b' composeApp/src 2>/dev/null \
            | xargs -r sed -i -E -e 's/\bGiza\b/Bennu/g' -e 's/\bgiza\b/bennu/g'
        git add -A >/dev/null
        tree=$(git write-tree)
        git reset -q
        git update-ref "refs/giza-mirror/tree/$source_tree" "$(git commit-tree "$tree" -m "debranded tree $source_tree")"
        echo "$tree"
    )
}

mirror_commit() {
    local commit=$1 full cached parent_tree tree parent message
    full=$(git rev-parse "$commit^{commit}")
    cached=$(git rev-parse -q --verify "refs/giza-mirror/$full" 2>/dev/null || true)
    if [[ -n $cached ]]; then
        echo "$cached"
        return
    fi
    parent_tree=$(debranded_tree "$full^1")
    tree=$(debranded_tree "$full")
    parent=$(git commit-tree "$parent_tree" -m "debranded parent of $full")
    message=$(git log -1 --format=%B "$full")
    local mirror
    mirror=$(
        GIT_AUTHOR_NAME=$(git log -1 --format=%an "$full") \
        GIT_AUTHOR_EMAIL=$(git log -1 --format=%ae "$full") \
        GIT_AUTHOR_DATE=$(git log -1 --format=%aI "$full") \
        git commit-tree "$tree" -p "$parent" -m "$message" -m "Giza: $full"
    )
    git update-ref "refs/giza-mirror/$full" "$mirror"
    echo "$mirror"
}

if (( $# == 0 )); then
    sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
fi

for commit in "$@"; do
    mirror_commit "$commit"
done
