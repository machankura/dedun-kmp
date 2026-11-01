#!/usr/bin/env python3
"""Move interpolated UI strings into the catalogue as format strings.

Companion to m3-extract-strings.py, which handles literals with no interpolation. Here a

    Text("Add chapter to ${uiState.artifact.name}")

becomes a resource holding `Add chapter to %1$s` and a call

    Text(stringResource(Res.string.add_chapter_to, uiState.artifact.name))

**Only standalone literals.** A literal that is one term of a `+` concatenation is left
alone: reassembling `"a " + x + " b"` into a single format string means deciding what the
whole sentence is, and that is per-site reading rather than a rewrite. Those are listed by
--remaining.

Usage:
    m3-extract-formatted.py              dry run
    m3-extract-formatted.py --apply
    m3-extract-formatted.py --remaining  list what is deliberately left
"""
import os, re, sys, io, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
_helpers = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             'm3-extract-strings.py')).read()
exec(_helpers.split('def resource_name(')[0])          # UI, STRINGS, kotlin_strings, ...
exec('def resource_name(' + _helpers.split('def resource_name(')[1].split('def xml_escape')[0])

DRY = '--apply' not in sys.argv


def split_template(body):
    """Kotlin template body -> (segments, expressions).

    segments is the literal text with each interpolation replaced by a placeholder index.
    """
    parts, exprs, i, buf = [], [], 0, ''
    while i < len(body):
        c = body[i]
        if c == '\\':
            buf += body[i:i + 2]
            i += 2
            continue
        if c == '$' and i + 1 < len(body):
            if body[i + 1] == '{':
                depth, j = 1, i + 2
                while j < len(body) and depth:
                    if body[j] == '{':
                        depth += 1
                    elif body[j] == '}':
                        depth -= 1
                    j += 1
                parts.append(buf)
                buf = ''
                exprs.append(body[i + 2:j - 1])
                i = j
                continue
            m = re.match(r'\$([A-Za-z_][A-Za-z0-9_]*)', body[i:])
            if m:
                parts.append(buf)
                buf = ''
                exprs.append(m.group(1))
                i += m.end()
                continue
        buf += c
        i += 1
    parts.append(buf)
    return parts, exprs


def unescape_for_xml(text):
    """Kotlin escapes -> what belongs in the resource file.

    `\\uXXXX` and `\\"` are Kotlin source syntax with no meaning in XML, so they are
    decoded: a resource file is UTF-8 and can hold the character itself. `\\n` is left
    alone, because StringCatalogueJvmTest shows Compose Resources processes it and a
    real newline in an XML value would be reflowed by the parser.
    """
    out, i = '', 0
    while i < len(text):
        if text[i] == '\\' and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == 'u' and i + 6 <= len(text):
                out += chr(int(text[i + 2:i + 6], 16))
                i += 6
                continue
            if nxt in '"\'':
                out += nxt
                i += 2
                continue
        out += text[i]
        i += 1
    return out


def format_string(parts, exprs):
    out = unescape_for_xml(parts[0]).replace('%', '%%')
    for n, tail in enumerate(parts[1:], start=1):
        out += f'%{n}$s' + unescape_for_xml(tail).replace('%', '%%')
    return out


def candidates(source):
    """(start, end, body, exprs, format_string) for standalone interpolated literals."""
    spans = text_call_spans(source)
    found = []
    for start, end, body, interpolated in kotlin_strings(source):
        if not interpolated or not body.strip():
            continue
        if not any(a <= start <= b for a, b in spans):
            continue
        line_start = source.rfind('\n', 0, start) + 1
        line = source[line_start:source.find('\n', start)]
        if line.lstrip().startswith(('//', '*', '/*')):
            continue
        before, after = source[:start].rstrip(), source[end:].lstrip()
        stripped = line.strip()
        if before.endswith('+') or after.startswith('+') or \
           stripped.endswith('+') or stripped.startswith('+'):
            continue
        parts, exprs = split_template(body)
        if not exprs:
            continue
        # A template that is *only* interpolation has nothing to translate.
        # `Text("$name")` would become `stringResource(Res.string.string, name)`, which is
        # a resource holding "%1$s" -- longer, slower and no more localisable than the
        # code it replaced. Same for one whose literal half is punctuation or a spacer.
        literal = unescape_for_xml(''.join(parts))
        if not any(c.isalpha() for c in literal):
            continue
        fmt = format_string(parts, exprs)
        # A leading or trailing space means the string is being glued to a neighbour --
        # " \u00b7 %1$s" is a separator, not a sentence. The test is on the *format
        # string*, not on the literal halves: a template that opens with an interpolation
        # leaves parts[0] empty and parts[1] starting with the space that separates them,
        # so joining the halves makes "%1$s Key packages" look like a fragment when it is
        # a whole label.
        if fmt != fmt.strip():
            continue
        found.append((start, end, body, exprs, fmt))
    return found



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
    if '--remaining' in sys.argv:
        for root, _, files in os.walk(UI):
            for f in sorted(files):
                if not f.endswith('.kt'):
                    continue
                path = os.path.join(root, f)
                src = open(path, encoding='utf-8').read()
                spans = text_call_spans(src)
                done = {(a, b) for a, b, *_ in candidates(src)}
                for a, b, body, _i in kotlin_strings(src):
                    if not body.strip() or (a, b) in done:
                        continue
                    if not any(x <= a <= y for x, y in spans):
                        continue
                    line_start = src.rfind('\n', 0, a) + 1
                    line = src[line_start:src.find('\n', a)]
                    if line.lstrip().startswith(('//', '*', '/*')):
                        continue
                    print(f'  {path.replace(UI + "/", "")}:{src[:a].count(chr(10)) + 1}  {body[:70]!r}')
        return 0

    plan = {}
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            for _s, _e, body, exprs, fmt in candidates(open(path, encoding='utf-8').read()):
                plan[fmt] = plan.get(fmt, 0) + 1

    existing = io.open(STRINGS, encoding='utf-8').read()
    taken = set(re.findall(r'<string name="([^"]+)"', existing))
    names = {}
    for fmt in sorted(plan):
        # name from the literal half only -- "%1$s" contributes nothing readable
        names[fmt] = resource_name(re.sub(r'%\d+\$s', ' ', fmt), taken)
        taken.add(names[fmt])

    print(f'{len(plan)} distinct format strings, {sum(plan.values())} occurrences')
    if DRY:
        for fmt in sorted(plan)[:12]:
            print(f'  {names[fmt]:44s} {fmt[:64]!r}')
        return 0

    rewritten = 0
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            src = io.open(path, encoding='utf-8').read()
            found = candidates(src)
            if not found:
                continue
            for start, end, _body, exprs, fmt in reversed(found):
                args = ', '.join(exprs)
                src = src[:start] + f'stringResource(Res.string.{names[fmt]}, {args})' + src[end:]
                rewritten += 1
            need = sorted({names[fmt] for *_x, fmt in [(c[0], c[4]) for c in found]})
            for imp in (['import dedun.composeapp.generated.resources.Res',
                         'import org.jetbrains.compose.resources.stringResource']
                        + [f'import dedun.composeapp.generated.resources.{n}' for n in need]):
                if not has_import(src, imp):
                    lines = src.split('\n')
                    idx = max(i for i, l in enumerate(lines) if l.startswith('import '))
                    lines.insert(idx + 1, imp)
                    src = '\n'.join(lines)
            io.open(path, 'w', encoding='utf-8').write(src)
    print(f'{rewritten} call sites rewritten')

    entries = '\n'.join(
        f'    <string name="{names[fmt]}">'
        f'{fmt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</string>'
        for fmt in sorted(plan, key=lambda x: names[x]))
    io.open(STRINGS, 'w', encoding='utf-8').write(
        existing.replace('</resources>', entries + '\n</resources>'))
    print(f'{len(plan)} entries written')
    return 0


if __name__ == '__main__':
    sys.exit(main())
