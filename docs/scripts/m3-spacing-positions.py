#!/usr/bin/env python3
"""Count .dp literals by whether they sit in a spacing position or a dimension one.

m3-audit.sh used to split them by value, exempting a list of numbers that "looked like"
dimensions -- which is wrong twice over. 16.dp is a spacing stop *and* a plausible icon
size, and 50.dp was a Spacer height in 53 places and a divider width in one. Only the call
shape says which, so this reads the shape.

    spacing     padding(...), PaddingValues(...), Arrangement.spacedBy(...), and a
                .height()/.width() whose enclosing call is Spacer(
    dimension   .size(), a non-Spacer .height()/.width(), RoundedCornerShape(),
                BorderStroke(), a `size =` argument

Spacing positions are what phase 2 drives to zero; dimensions are out of its scope and are
reported so the number is visible rather than exempted.

Usage: m3-spacing-positions.py [--list]
"""
import os, re, sys

UI = 'composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui'
THEME = os.path.join(UI, 'theme')


def _spans(text, opener):
    """Brace-matched character ranges of every call matching `opener`."""
    spans = []
    for m in re.finditer(opener, text):
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == '(':
                depth += 1
            elif text[i] == ')':
                depth -= 1
                if depth == 0:
                    spans.append((m.start(), i))
                    break
            i += 1
    return spans


def classify(path):
    text = open(path, encoding='utf-8').read()
    pads = _spans(text, r'\.?\bpadding\s*\(|\bPaddingValues\s*\(')
    gaps = _spans(text, r'\bspacedBy\s*\(')
    spacers = _spans(text, r'\bSpacer\s*\(')

    def inside(pos, spans):
        return any(a <= pos <= b for a, b in spans)

    hw = [
        (m.start(1), m.end(1))
        for m in re.finditer(r'\.(?:height|width)\s*\(\s*(\d+\.dp)\s*\)', text)
        if inside(m.start(), spacers)
    ]

    spacing, dimension = [], []
    for m in re.finditer(r'\b(\d+)\.dp\b', text):
        pos = m.start()
        line_start = text.rfind('\n', 0, pos) + 1
        line = text[line_start:text.find('\n', pos)]
        if re.match(r'\s*(//|\*|/\*)', line):
            continue
        # An explicit, reasoned opt-out. A value that is genuinely not a step in the
        # spacing rhythm -- room reserved below a list, say -- is marked at the site
        # rather than exempted by value in this file, so the reason travels with it
        # and a reviewer sees it in the diff.
        # Walk back over the unbroken run of comment lines directly above.
        exempt = False
        prev = text[:line_start].rstrip('\n').split('\n')
        for candidate in reversed(prev[-8:]):
            stripped = candidate.strip()
            if not stripped.startswith('//'):
                break
            if 'm3-spacing-exempt' in stripped:
                exempt = True
                break
        if exempt:
            continue
        entry = (path, text[:pos].count('\n') + 1, m.group(0), line.strip()[:90])
        if inside(pos, pads) or inside(pos, gaps) or any(a <= pos < b for a, b in hw):
            spacing.append(entry)
        else:
            dimension.append(entry)
    return spacing, dimension


def main():
    spacing, dimension = [], []
    for root, _, files in os.walk(UI):
        if root.startswith(THEME):
            continue
        for f in sorted(files):
            if f.endswith('.kt'):
                s, d = classify(os.path.join(root, f))
                spacing += s
                dimension += d

    print(f'   {"dp literals in spacing positions":42s} {len(spacing):6d}')
    print(f'   {"dp literals in dimension positions":42s} {len(dimension):6d}   (out of scope)')

    if '--list' in sys.argv:
        for path, line, lit, ctx in spacing:
            print(f'     {path.replace(UI + "/", "")}:{line}  {lit:>7s}  {ctx}')

    return 1 if spacing else 0


if __name__ == '__main__':
    sys.exit(main())
