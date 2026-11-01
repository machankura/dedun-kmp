#!/usr/bin/env python3
"""Move literal UI strings out of composables and into the resource catalogue.

Handles the simple case only: a `text = "..."` or `Text("...")` whose literal contains no
Kotlin interpolation. Interpolated strings need format placeholders and an argument order
decided per site, which is not something to do blind.

Resource names are derived from the string's content, snake_cased and truncated at a word
boundary. That is the conventional shape for an automated extraction and it has a known
cost: rewording the copy makes the name a little stale. The alternative -- a name derived
from the string's *purpose* -- needs somebody to read all 285 call sites, and a wrong
purpose in a name is worse than a slightly dated one.

Usage:
    m3-extract-strings.py            dry run, prints the plan
    m3-extract-strings.py --apply
"""
import os, re, sys, io, collections

UI = 'composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui'
STRINGS = 'composeApp/src/commonMain/composeResources/values/strings.xml'
DRY = '--apply' not in sys.argv

# Literals inside a `Text(...)` call, with no `$` inside.
#
# The first version of this matched a bare `text = "…"` anywhere, which is wrong: `text`
# is an ordinary parameter name and the tree uses it on data classes too. It rewrote
# `NavigationUIState.Loading(text = "…")` -- not a composable -- and the compiler caught
# it with "@Composable invocations can only happen from the context of a @Composable
# function". So the call is brace-matched instead, and only literals genuinely inside a
# `Text(` are touched.
def text_call_spans(source):
    """Character ranges of every `Text(` / `BasicText(` call, brace-matched.

    Anchoring to the call is what keeps `NavigationUIState.Loading(text = "…")` out of
    the results. `text` is an ordinary parameter name and the tree uses it on data
    classes too; an earlier version matched a bare `text = "…"` anywhere and rewrote a
    non-composable, which the compiler caught with "@Composable invocations can only
    happen from the context of a @Composable function".
    """
    spans = []
    for m in re.finditer(r'\b(?:Basic)?Text\s*\(', source):
        depth, i = 0, m.end() - 1
        while i < len(source):
            if source[i] == '(':
                depth += 1
            elif source[i] == ')':
                depth -= 1
                if depth == 0:
                    spans.append((m.start(), i))
                    break
            i += 1
    return spans


def kotlin_strings(source):
    """Every top-level Kotlin string literal, as (start, end, body, interpolated).

    A regex cannot do this. `"a ${if (n == 1) "chunk" else "chunks"} b"` contains two
    inner literals that belong to the outer template, and matching quote pairs left to
    right pulls them out as strings of their own -- which is how an earlier version of
    this script decided "chunk" and "note" were UI strings worth translating.

    So the source is scanned: on an opening quote, walk forward tracking `${` depth and
    recursing over nested literals, and stop at the closing quote that is at depth zero.
    Raw strings and escapes are skipped rather than parsed, which is enough here.
    """
    out, i, n = [], 0, len(source)
    while i < n:
        c = source[i]
        if c == '"' and source[i:i + 3] == '\"\"\"':
            end = source.find('\"\"\"', i + 3)
            i = n if end == -1 else end + 3
            continue
        if c != '"':
            i += 1
            continue
        j, depth, interpolated = i + 1, 0, False
        while j < n:
            ch = source[j]
            if ch == '\\':
                j += 2
                continue
            if ch == '$' and j + 1 < n and (source[j + 1] == '{' or source[j + 1].isalpha()):
                interpolated = True
                if source[j + 1] == '{':
                    depth += 1
                    j += 2
                    continue
            if depth > 0:
                if ch == '{':
                    depth += 1
                elif ch == '}':
                    depth -= 1
                elif ch == '"':
                    # a literal inside the interpolation: skip it whole
                    k = j + 1
                    while k < n and source[k] != '"':
                        k += 2 if source[k] == '\\' else 1
                    j = k
                j += 1
                continue
            if ch == '"':
                break
            if ch == '\n':
                break
            j += 1
        if j < n and source[j] == '"':
            out.append((i, j + 1, source[i + 1:j], interpolated))
            i = j + 1
        else:
            i += 1
    return out


def literals_in_text_calls(source):
    """(start, end, value) for each plain, whole literal inside a Text( call."""
    spans = text_call_spans(source)
    found = []
    for start, end, value, interpolated in kotlin_strings(source):
        if interpolated or not value.strip():
            continue
        if not any(a <= start <= b for a, b in spans):
            continue
        line_start = source.rfind('\n', 0, start) + 1
        line = source[line_start:source.find('\n', start)]
        if line.lstrip().startswith(('//', '*', '/*')):
            continue
        # A fragment of a concatenation. `"a " + x + " b"` is one sentence in three
        # pieces, and " b" is not a translatable unit -- word order differs between
        # languages, so a translator handed " b" has nothing to work with. These go with
        # the interpolated strings, which need format placeholders and a human.
        before = source[:start].rstrip()
        after = source[end:].lstrip()
        if before.endswith('+') or after.startswith('+'):
            continue
        if value != value.strip() or not any(c.isalpha() for c in value):
            continue
        if before.endswith('append('):
            continue
        # The same fragment problem one level out. A pluralisation reads
        #
        #     (if (n == 2) "event" else "events") +
        #
        # so neither literal is adjacent to the `+` -- the parenthesis is. Testing the
        # line instead catches those four sites while leaving a genuine either/or alone:
        #
        #     text = if (session == null) "Start key ceremony" else "Try again"
        #
        # has no `+` on its line and both branches are whole strings.
        stripped = line.strip()
        if stripped.endswith('+') or stripped.startswith('+'):
            continue
        found.append((start, end, value))
    return found


def resource_name(text, taken):
    slug = re.sub(r'[^a-z0-9]+', '_', text.lower()).strip('_')
    words = slug.split('_')
    name, out = '', []
    for w in words:
        if len(name) + len(w) + 1 > 44:
            break
        out.append(w)
        name = '_'.join(out)
    name = name or 'string'
    if name[0].isdigit():
        name = 's_' + name
    base, i = name, 2
    while name in taken:
        name = f'{base}_{i}'
        i += 1
    return name


def xml_escape(text):
    """XML entities only.

    Compose Resources is not aapt, and the difference is a shipped bug rather than a
    detail. An earlier version of this escaped apostrophes as `\\'` and doubled `%`, which
    is what android's resource compiler requires. Compose Resources does neither:
    `getString` returned `Don\\'t sign`, backslash included, and
    StringCatalogueJvmTest caught it.

    It *does* process `\\n`, which the same test asserts -- so escape handling here is
    partial rather than absent, and worth checking per escape rather than assuming a
    family.

    `%` is left alone. Nothing in this catalogue contains one, and if something does
    later it only matters for an entry passed through `getString(resource, args)`.
    """
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')



def has_import(source, statement):
    """Whole-line match.

    `statement in source` is wrong here and silently so: the accessors are named after
    their strings, so `...resources.translate` is a prefix of
    `...resources.translate_into_which_dialect`, and a substring test decides the import
    is already present. The compiler then reports "Unresolved reference 'translate'" in a
    file whose imports look complete.
    """
    return any(line.strip() == statement for line in source.split('\n'))

def main():
    occurrences = collections.defaultdict(list)
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            src = open(path, encoding='utf-8').read()
            for start, _end, value in literals_in_text_calls(src):
                occurrences[value].append((path, src[:start].count('\n') + 1))

    taken, names = set(), {}
    for text in sorted(occurrences):
        names[text] = resource_name(text, taken)
        taken.add(names[text])

    total = sum(len(v) for v in occurrences.values())
    print(f'{len(occurrences)} distinct strings, {total} occurrences')
    if DRY:
        for text in sorted(occurrences)[:10]:
            print(f'  {names[text]:46s} {text[:60]!r}')
        print('  ...')
        return 0

    # Rewrite the call sites.
    rewritten = 0
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            src = io.open(path, encoding='utf-8').read()
            found = literals_in_text_calls(src)
            changed = len(found)
            # Right to left, so earlier offsets stay valid.
            for start, end, value in reversed(found):
                src = src[:start] + f'stringResource(Res.string.{names[value]})' + src[end:]
            if changed:
                for imp in ('import bennu.composeapp.generated.resources.Res',
                            'import org.jetbrains.compose.resources.stringResource'):
                    if not has_import(src, imp):
                        lines = src.split('\n')
                        idx = max(i for i, l in enumerate(lines) if l.startswith('import '))
                        lines.insert(idx + 1, imp)
                        src = '\n'.join(lines)
                # the generated accessors are one per string, under the same package
                need = sorted({names[t] for t in occurrences
                               if any(p == path for p, _ in occurrences[t])})
                lines = src.split('\n')
                idx = max(i for i, l in enumerate(lines) if l.startswith('import '))
                for nm in reversed(need):
                    imp = f'import bennu.composeapp.generated.resources.{nm}'
                    if not has_import(src, imp):
                        lines.insert(idx + 1, imp)
                src = '\n'.join(lines)
                io.open(path, 'w', encoding='utf-8').write(src)
                rewritten += changed
    print(f'{rewritten} call sites rewritten')

    # Write the catalogue.
    existing = io.open(STRINGS, encoding='utf-8').read()
    entries = '\n'.join(
        f'    <string name="{names[t]}">{xml_escape(t)}</string>'
        for t in sorted(occurrences, key=lambda x: names[x]))
    io.open(STRINGS, 'w', encoding='utf-8').write(
        existing.replace('</resources>', entries + '\n</resources>'))
    print(f'{len(occurrences)} entries written to {STRINGS}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
