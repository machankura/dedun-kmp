#!/usr/bin/env python3
"""Find the two words the group Wavelength screens are not allowed to say.

Phase 1 of docs/group-wavelength-redesign.md. Two words, because two words were naming
things a member has no way to know:

  - *daemon*, which is what the code calls the process that runs the wallet. On a screen it
    is a word about this app's internals, and the thing it names is just a device.
  - a bare *operator*, which named two different parties a word apart. The Ark server is
    the *Ark operator*; the member the group elected to keep the wallet running is
    *whoever runs the wallet*. Eight strings said "Ark operator", four said "the operator"
    for the member and three said "operator" for the server -- including one of the two
    figures on the balance card. Nothing on the screen said which was which.

So the budget is not "no jargon" but "one name per party": a string may say *Ark operator*,
and it may say *whoever runs the wallet*, and anything else is counted here. A new string
has to pick one, which is the whole point -- the alternative is a review catching it, and a
review did not catch these.

Scope is the Wavelength feature's own strings: every key under the `wavelength_` or
`group_wavelength_` prefix, plus every string any Wavelength screen reads, so a string
borrowed from elsewhere and shown on one of these screens is counted too. What this
deliberately does *not* cover is the group Lightning wallet, where *the operator* names a
third thing again -- the member whose device runs the Lightning node -- and renaming that is
a plan of its own.

Usage: m3-jargon.py [--list]
"""
import os, re, sys

UI = 'composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/ui'
CATALOGUE = 'composeApp/src/commonMain/composeResources/values/strings.xml'

# A file is a Wavelength screen's if its name says so. GroupPayoutsScreen is the one that
# does not: it is the Wavelength wallet's payouts screen and three of its strings were in
# the count.
def in_scope(filename):
    return filename.endswith('.kt') and ('Wavelength' in filename or 'GroupPayouts' in filename)


def catalogue():
    text = open(CATALOGUE, encoding='utf-8').read()
    return [(m.group(1), m.group(2))
            for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', text)]


def keys_in_scope():
    keys = set()
    for name, _ in catalogue():
        if name.startswith(('wavelength_', 'group_wavelength_')):
            keys.add(name)
    for root, _, files in os.walk(UI):
        for f in sorted(files):
            if not in_scope(f):
                continue
            text = open(os.path.join(root, f), encoding='utf-8').read()
            keys.update(re.findall(r'Res\.string\.([a-z0-9_]+)', text))
    return keys


DAEMON = re.compile(r'daemon', re.I)
OPERATOR = re.compile(r'operator', re.I)


def bare_operators(value):
    """Every "operator" in `value` that is not the tail of "Ark operator"."""
    return [m for m in OPERATOR.finditer(value) if not value[:m.start()].endswith('Ark ')]


def offenders():
    scope = keys_in_scope()
    daemon, operator = [], []
    for name, value in catalogue():
        if name not in scope:
            continue
        if DAEMON.search(value):
            daemon.append((name, value))
        if bare_operators(value):
            operator.append((name, value))
    return daemon, operator


def main():
    daemon, operator = offenders()
    print(f'   {"daemon in Wavelength strings":42s} {len(daemon):6d}')
    print(f'   {"bare operator in Wavelength strings":42s} {len(operator):6d}')
    if '--list' in sys.argv:
        for label, found in (('daemon', daemon), ('bare operator', operator)):
            for name, value in found:
                print(f'     {label:14s} {name}  {value!r}')
    return 1 if daemon or operator else 0


if __name__ == '__main__':
    sys.exit(main())
