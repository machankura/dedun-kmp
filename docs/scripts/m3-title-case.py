#!/usr/bin/env python3
"""Find UI strings still written in title case.

M3's style guide: "All text, including titles, headings, labels, menu items, navigation
components, app bars, and buttons should use sentence-style capitalization." Product names
and branded terms keep their capitals.

Two things this gets right that the first, grep-based version did not:

  - It allows lowercase articles inside a title-cased phrase, so "Invite a Friend" is
    caught. Requiring every word after the first to be capitalised missed four strings.
  - It scans the whole file rather than one line at a time, so a `Text(` whose literal is
    on the next line is caught. That missed one more.

Sample data is excluded by name rather than by pattern, because "Steve Biko" and "To Kill
a Mockingbird" are title case for the correct reason: they are a person and a book.

Usage: m3-title-case.py [--list]
"""
import os, re, sys

UI = 'composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui'
CATALOGUE = 'composeApp/src/commonMain/composeResources/values/strings.xml'

SMALL = {'a', 'an', 'the', 'to', 'of', 'for', 'and', 'or', 'via',
         'in', 'on', 'at', 'with', 'from', 'by'}

# People, book titles and other proper nouns used as preview and test fixtures.
SAMPLE = {
    'Steve Biko', 'John Doe', 'Frank Talk', 'Alan Turing',
    'To Kill a Mockingbird', 'Man With A Plan', 'Woman Of Few Words',
    # Films, as suggested to a curated list in the queue screen's preview.
    'The Rise and Rise of Bitcoin', 'Magic Money',
}


def offenders():
    found = []
    # The catalogue, first. When phase 4 moved 364 strings out of composables it moved
    # them out of this checker's reach at the same time -- it scanned .kt files only, so
    # it went on reporting zero while "%1$s Key Packages" and three surviving mentions of
    # the old product name sat in strings.xml. Externalising narrows what a source scan
    # can see; the check has to follow.
    if os.path.exists(CATALOGUE):
        for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>',
                             open(CATALOGUE, encoding='utf-8').read()):
            phrase = m.group(2)
            if phrase in SAMPLE:
                continue
            words = [w for w in re.sub(r'%\d+\$s', '', phrase).split() if w]
            if len(words) < 2 or not words[0][:1].isupper():
                continue
            later = [w for w in words[1:] if w.lower() not in SMALL]
            if not later:
                continue
            if all(w[:1].isupper() for w in later) and \
               any(w[:1].isupper() and w[1:].islower() for w in later):
                found.append((CATALOGUE, m.group(1), phrase))
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            text = open(path, encoding='utf-8').read()
            for m in re.finditer(r'"([^"$\\]{4,90})"', text):
                phrase = m.group(1)
                if phrase in SAMPLE:
                    continue
                line_start = text.rfind('\n', 0, m.start()) + 1
                line = text[line_start:text.find('\n', m.start())]
                if line.lstrip().startswith(('//', '*', '/*')):
                    continue
                # A log line is not UI copy. `logger.d("Queried Sync")` is written for
                # whoever is reading logcat, and sentence-casing it would be cargo cult.
                if re.search(r'\blogger\s*\.\s*[dewiv]\s*\(', line):
                    continue
                words = phrase.split()
                if len(words) < 2 or not words[0][:1].isupper():
                    continue
                later = [w for w in words[1:] if w.lower() not in SMALL]
                if not later:
                    continue
                # Title case: every significant word capitalised, and at least one of
                # them an ordinary capitalised word rather than an acronym like NIP.
                if all(w[:1].isupper() for w in later) and \
                   any(w[:1].isupper() and w[1:].islower() for w in later):
                    found.append((path, text[:m.start()].count('\n') + 1, phrase))
    return found


def main():
    found = offenders()
    print(f'   {"Title Case in UI strings":42s} {len(found):6d}')
    if '--list' in sys.argv:
        for path, line, phrase in found:
            print(f'     {path.replace(UI + "/", "")}:{line}  {phrase!r}')
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main())
