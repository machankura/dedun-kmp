#!/usr/bin/env python3
"""Rewrite .dp literals that sit in spacing positions onto MaterialTheme.spacing.

Checked in because phase 2 runs it twice and the breakpoint phase will want it again.
Usage:

    m3-migrate-spacing.py '{"5":"space50","15":"space200"}'          # dry run
    m3-migrate-spacing.py '{"5":"space50","15":"space200"}' --apply

Only three call shapes are touched, and each is matched with the literal in place so a
dimension can never be caught by accident:

  padding(...)             any of the overloads, including named start/end/top/bottom
  Arrangement.spacedBy(N)  horizontal or vertical
  Spacer height/width      a .height()/.width() whose enclosing call is Spacer(

Everything else -- .size(), a non-Spacer .height(), RoundedCornerShape, BorderStroke --
is a dimension and is left alone.
"""
import re, sys, io, os

UI = 'composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui'
# {"padding": {"8": "compactPadding"}, "gap": {...}, "spacer": {...}, "*": {...}}
# A shape-specific entry wins over "*". Shape matters because the same number means
# different things in different positions: 8dp of padding is compactPadding, 8dp of gap
# is itemGap, and 8dp under a Spacer is neither.
MAPPING = {}
DRY = '--apply' not in sys.argv

def token(value, shape):
    return MAPPING.get(shape, {}).get(value) or MAPPING.get('*', {}).get(value)

def spacer_spans(text):
    """Character ranges covered by a Spacer( ... ) call, brace-matched."""
    spans = []
    for m in re.finditer(r'\bSpacer\s*\(', text):
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

def in_spans(pos, spans):
    return any(a <= pos <= b for a, b in spans)

def padding_spans(text):
    spans = []
    for m in re.finditer(r'\.?\bpadding\s*\(|\bPaddingValues\s*\(', text):
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

def spacedby_spans(text):
    spans = []
    for m in re.finditer(r'\bspacedBy\s*\(', text):
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

def process(path):
    text = io.open(path, encoding='utf-8').read()
    original = text
    changed = []

    for _ in range(60):                       # spans shift after each edit; recompute
        pads = padding_spans(text)
        gaps = spacedby_spans(text)
        spacers = spacer_spans(text)
        # a .height()/.width() literal counts only inside a Spacer(
        hw = [(m.start(1), m.end(1), m.group(1))
              for m in re.finditer(r'\.(?:height|width)\s*\(\s*(\d+\.dp)\s*\)', text)
              if in_spans(m.start(), spacers)]

        edit = None
        for m in re.finditer(r'\b(\d+)\.dp\b', text):
            lit = m.group(0)
            pos = m.start()
            line_start = text.rfind('\n', 0, pos) + 1
            line = text[line_start:text.find('\n', pos)]
            if re.match(r'\s*(//|\*|/\*)', line):        # a comment
                continue
            if in_spans(pos, pads):
                shape = 'padding'
            elif in_spans(pos, gaps):
                shape = 'gap'
            elif any(a <= pos < b for a, b, _ in hw):
                shape = 'spacer'
            else:
                continue
            tok = token(m.group(1), shape)
            if tok is None:
                continue
            edit = (m.start(), m.end(), tok, lit, line.strip()[:80])
            break
        if edit is None:
            break
        a, b, tok, lit, ctx = edit
        text = text[:a] + f'MaterialTheme.spacing.{tok}' + text[b:]
        changed.append((lit, tok, ctx))

    if text != original:
        if 'import androidx.compose.material3.MaterialTheme' not in text:
            # insert alphabetically among the material3 imports, else after the last import
            lines = text.split('\n')
            idx = max(i for i, l in enumerate(lines) if l.startswith('import '))
            for i, l in enumerate(lines):
                if l.startswith('import androidx.compose.material3.') and l > 'import androidx.compose.material3.MaterialTheme':
                    idx = i - 1
                    break
            lines.insert(idx + 1, 'import androidx.compose.material3.MaterialTheme')
            text = '\n'.join(lines)
        if 'import com.machankura.dedun.compose.ui.theme.spacing' not in text and \
           not path.endswith('theme/Spacing.kt'):
            lines = text.split('\n')
            idx = max(i for i, l in enumerate(lines) if l.startswith('import '))
            lines.insert(idx + 1, 'import com.machankura.dedun.compose.ui.theme.spacing')
            text = '\n'.join(lines)
        if not DRY:
            io.open(path, 'w', encoding='utf-8').write(text)
    return changed

if __name__ == '__main__':
    import json
    MAPPING.update(json.loads(sys.argv[1]))
    total = 0
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            p = os.path.join(root, f)
            ch = process(p)
            if ch:
                print(f'{p.replace(UI + "/", "")}')
                for lit, tok, ctx in ch:
                    print(f'    {lit:>7s} -> {tok:18s}  {ctx}')
                total += len(ch)
    print(f'\n{total} literal(s) {"would be " if DRY else ""}rewritten')
