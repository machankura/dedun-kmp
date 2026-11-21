#!/usr/bin/env python3
"""Regenerate the table in docs/giza-ledger.md: docs/giza-into-dedun.md, Phase 0.

    docs/scripts/giza-ledger.py [giza-ref] [bennu-port-ref]

One row per Giza commit after Dedun's fork point 4f1b4a43, in Giza's order:

- the Bennu port commit that carries it (its `Giza: <sha>` trailer);
- Bennu's group, phase and verdict, read from the port's own docs/giza-ledger.md;
- Dedun's state: the Dedun commit whose `Giza: <sha>` trailer names it, else the verdict
  Dedun inherits (skip, held), else pending.

Then one row per Bennu-only commit in the port (no `Giza:` trailer), matched by Dedun's
`Bennu: <sha>` trailers. Everything above the first `| giza |` table header in the file is
kept as written; only the tables are replaced.
"""
import re
import subprocess
import sys

GIZA = sys.argv[1] if len(sys.argv) > 1 else "refs/remotes/giza/giza"
PORT = sys.argv[2] if len(sys.argv) > 2 else "refs/remotes/bennu/giza-port"
FORK = "4f1b4a43"
BENNU_FORK = "4e106fb5"
LEDGER = "docs/giza-ledger.md"

# Dedun's own verdicts, where they differ from Bennu's (giza-into-dedun.md, Decisions).
DEDUN_OVERRIDES = {
    "8dd09aa0": ("bennu", "0", "held (D7)"),
    "4e106fb5": ("bennu", "—", "skip"),
}


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def trailers(rev_range, key):
    """{trailer value (short) -> commit (short)} for commits in rev_range."""
    out = git("log", "--format=%h%x09%(trailers:key=" + key + ",valueonly,separator=%x20)", rev_range)
    found = {}
    for line in out.splitlines():
        commit, _, values = line.partition("\t")
        for value in values.split():
            found.setdefault(value[:8], commit[:8])
    return found


def main():
    giza = git("log", "--reverse", "--no-merges", "--format=%h%x09%s", f"{FORK}..{GIZA}").splitlines()
    port_by_giza = trailers(f"{BENNU_FORK}..{PORT}", "Giza")
    dedun_by_giza = trailers(f"{FORK}..HEAD", "Giza")
    dedun_by_bennu = trailers(f"{FORK}..HEAD", "Bennu")

    bennu_rows = {}
    for line in git("show", f"{PORT}:docs/giza-ledger.md").splitlines():
        m = re.match(r"\| `([0-9a-f]{8})` \| ([^|]*) \| ([^|]*) \| ([^|]*) \|", line)
        if m:
            bennu_rows[m.group(1)] = (m.group(2).strip(), m.group(3).strip(), m.group(4).strip())

    rows = ["| giza | bennu port | group | phase | Bennu | Dedun | subject |", "|---|---|---|---|---|---|---|"]
    for line in giza:
        sha, subject = line.split("\t", 1)
        sha = sha[:8]
        group, phase, verdict = bennu_rows.get(sha, ("—", "—", "pending"))
        if sha in DEDUN_OVERRIDES:
            group, phase, dedun = DEDUN_OVERRIDES[sha]
        elif sha in dedun_by_giza:
            dedun = f"`{dedun_by_giza[sha]}`"
        elif verdict.startswith("skip"):
            dedun = "skip"
        elif verdict.startswith("held") or verdict.startswith("pending ("):
            dedun = verdict
        else:
            dedun = "pending"
        port = f"`{port_by_giza[sha]}`" if sha in port_by_giza else "—"
        subject = subject.replace("|", "\\|")
        rows.append(f"| `{sha}` | {port} | {group} | {phase} | {verdict} | {dedun} | {subject} |")

    bennu_only = ["| bennu port | Dedun | subject |", "|---|---|---|"]
    out = git("log", "--reverse", "--no-merges", "--format=%h%x09%(trailers:key=Giza,valueonly,separator=)%x09%s", f"{BENNU_FORK}..{PORT}")
    for line in out.splitlines():
        sha, giza_trailer, subject = line.split("\t", 2)
        if giza_trailer.strip():
            continue
        sha = sha[:8]
        dedun = f"`{dedun_by_bennu[sha]}`" if sha in dedun_by_bennu else ("skip: Bennu's own notes" if subject.startswith("docs:") else "pending")
        bennu_only.append(f"| `{sha}` | {dedun} | {subject.replace('|', chr(92) + '|')} |")

    text = open(LEDGER).read()
    head = text.split("\n| giza |", 1)[0].rstrip() + "\n\n"
    tail = "\n".join(rows) + "\n\n## Bennu-only commits in the port\n\n" + "\n".join(bennu_only) + "\n"
    open(LEDGER, "w").write(head + tail)


if __name__ == "__main__":
    main()
