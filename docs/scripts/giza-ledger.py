#!/usr/bin/env python3
"""Regenerate the table in docs/giza-ledger.md: docs/giza-into-dedun.md, Phase 0.

    docs/scripts/giza-ledger.py [giza-ref] [bennu-port-ref]

One row per Giza commit after Dedun's fork point 4f1b4a43 and up to the first batch's tip
d0437d6c, in Giza's order:

- the Bennu port commit that carries it (its `Giza: <sha>` trailer);
- Bennu's group, phase and verdict, read from the port's own docs/giza-ledger.md;
- Dedun's state: the Dedun commit whose `Giza: <sha>` trailer names it, else the verdict
  Dedun inherits (skip, held), else pending.

Then one row per Bennu-only commit in the port (no `Giza:` trailer), matched by Dedun's
`Bennu: <sha>` trailers. Everything above the first `| giza |` table header in the file is
kept as written; only the tables are replaced.

Then the second batch (docs/giza-second-batch.md): one row per Giza commit after d0437d6c,
grouped by the rules in SECOND_BATCH, with no Bennu column because Bennu has ported none of
it. Dedun's state is the Dedun commit whose `Giza: <sha>` trailer names it, else the verdict
the plan gives the group.
"""
import re
import subprocess
import sys

GIZA = sys.argv[1] if len(sys.argv) > 1 else "refs/remotes/giza/giza"
PORT = sys.argv[2] if len(sys.argv) > 2 else "refs/remotes/bennu/giza-port"
FORK = "4f1b4a43"
BENNU_FORK = "4e106fb5"
FIRST_BATCH_TIP = "d0437d6c"
LEDGER = "docs/giza-ledger.md"

# docs/giza-second-batch.md: (subject pattern or sha, group, phase, verdict). First match wins.
SECOND_BATCH = [
    ("5017547f", "names", "—", "skip (D12)"),
    ("50f73e17", "confederations", "—", "skip (D12)"),
    ("22d36931", "fix", "1", "audited: the bug is in confederation code"),
    ("9a1abc61", "fix", "1", "pending"),
    ("b7e5dd19", "fix", "1", "pending"),
    ("fff891a4", "second group", "5", "pending"),
    (r"recovery-code-v3", "recovery codes", "2", "pending"),
    (r"\(notifications", "notifications", "3", "pending"),
    (r"nip17-multiple-ceremonies", "second group", "5", "pending"),
    (r"group-nostr-profile", "group profile", "5", "pending"),
    (r"group-inbox", "group inbox", "6", "pending"),
    (r"group-threshold-ecdh", "group messages", "6", "pending"),
    (r"disappearing-messages", "disappearing", "7", "pending"),
    (r"group-name-(prefix|braces)", "names", "8", "pending"),
    (r"confederation", "confederations", "—", "skip (D12)"),
    (r"(?i)update icon", "icon", "—", "skip"),
]


def second_batch_group(sha, subject):
    for pattern, group, phase, verdict in SECOND_BATCH:
        if sha.startswith(pattern) or (not re.fullmatch(r"[0-9a-f]{8}", pattern) and re.search(pattern, subject)):
            return group, phase, verdict
    return "—", "—", "pending"

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
    giza = git("log", "--reverse", "--no-merges", "--format=%h%x09%s", f"{FORK}..{FIRST_BATCH_TIP}").splitlines()
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

    second = ["| giza | group | phase | Dedun | subject |", "|---|---|---|---|---|"]
    for line in git("log", "--reverse", "--no-merges", "--format=%h%x09%s", f"{FIRST_BATCH_TIP}..{GIZA}").splitlines():
        sha, subject = line.split("\t", 1)
        sha = sha[:8]
        group, phase, verdict = second_batch_group(sha, subject)
        dedun = f"`{dedun_by_giza[sha]}`" if sha in dedun_by_giza else verdict
        second.append(f"| `{sha}` | {group} | {phase} | {dedun} | {subject.replace('|', chr(92) + '|')} |")

    text = open(LEDGER).read()
    head = text.split("\n| giza |", 1)[0].rstrip() + "\n\n"
    tail = ("\n".join(rows) + "\n\n## Bennu-only commits in the port\n\n" + "\n".join(bennu_only) + "\n"
            + "\n## The second batch\n\n"
            + "Giza after `" + FIRST_BATCH_TIP + "`, planned in [giza-second-batch.md](./giza-second-batch.md). "
            + "Bennu has ported none of it, so each commit comes through `giza-mirror.sh` and then "
            + "`bennu-mirror.sh`. The phase column is the phase there.\n\n"
            + "\n".join(second) + "\n")
    open(LEDGER, "w").write(head + tail)


if __name__ == "__main__":
    main()
