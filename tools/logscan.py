#!/usr/bin/env python3
"""Summarize Incoming's game log without asking players to inspect it manually.

  python3 tools/logscan.py               last session in latest.log
  python3 tools/logscan.py --all         all of latest.log
  python3 tools/logscan.py --since 18:30 lines after HH:MM
  python3 tools/logscan.py path/to.log   another log, including .log.gz

Sections include mod loading, errors with stack traces, strikes, server stalls and work budgets,
legacy migration, missing sounds, Sable lifecycle, player chat and connections.
"""
import gzip
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import MC  # noqa: E402

args = sys.argv[1:]
path = next((a for a in args if not a.startswith("--") and not re.match(r"^\d\d:\d\d$", a)), os.path.join(MC, "logs", "latest.log"))
since = args[args.index("--since") + 1] if "--since" in args else None
opener = gzip.open if path.endswith(".gz") else open
with opener(path, "rt", encoding="utf-8", errors="replace") as f:
    lines = f.read().splitlines()

if "--all" not in args and since is None:
    # Last session starts at the most recent world startup.
    starts = [i for i, l in enumerate(lines) if "Starting integrated minecraft server" in l or "Preparing start region" in l]
    if starts:
        lines = lines[starts[-1]:]


def time_of(line):
    m = re.match(r"\[(?:\d+\S*\s)?(\d\d:\d\d:\d\d)", line)
    return m.group(1) if m else ""


if since:
    lines = [l for l in lines if time_of(l) >= since]

# Stack trace lines after an error from our mod.
errors = []
for i, l in enumerate(lines):
    if re.search(r"/(ERROR|WARN)\]", l) and re.search(r"Incoming/\]|ua\.zentix|ua\.ze\.in\.", l) and "Assets URL" not in l:
        block = [l]
        for nxt in lines[i + 1:i + 40]:
            if nxt.startswith(("\tat ", "Caused by", "java.", "\t... ")) or re.match(r"^\S+Exception", nxt):
                block.append(nxt)
            else:
                break
        errors.append("\n    ".join(block[:12]))

sections = [
    ("Work budget", r"Incoming/\]: Mod work over 30 s:"),
    ("Mod loading", r"Loading mod incoming|Found mod file incoming|incoming.*(mod file|version)"),
    ("Strikes and nuclear detonations", r"Incoming/\]: (Strike:|Nuclear detonation|ICBM)"),
    # Vanilla lateness accumulates between reports; include nearby strikes and slow ticks, excluding chat.
    ("Server lateness", r"^(?!.*(\[CHAT\]|\]: (\[Not Secure\] )?<)).*(Can't keep up|Incoming/\]: (Strike:|Impacts \(|Strike summary \(|Impact series summary \(|Blast: removing block|Nuclear tick|Server thread stalled)|Teleported)"),
    ("Projectiles: lost targets, self-destruction, interceptions", r"Incoming/\]: Projectiles: "),
    ("Legacy datapack migration", r"Incoming/\]: (Disabling old datapacks|Datapack settings moved|Removed objectives of the old datapack)"),
    ("Unknown sounds", r"Unable to play unknown soundEvent|Missing sound for event: (incoming|snassets):"),
    ("Sable sub-levels (added/removed)", r"shtreimel\.lifecycle.*sub-level (added|removed)"),
    ("Player chat", r"\[CHAT\] <"),
    ("Player connections", r"joined the game|left the game|lost connection"),
]


def short(line):
    text = re.sub(r"^\[[^\]]*?(\d\d:\d\d:\d\d)(?:\.\d+)?\] \[[^\]]*\] \[[^\]]*\]: ", r"\1 ", line)
    return text if "Mod work over 30 s:" in line else text[:260]


print(f"== Mod errors and warnings: {len(errors)}")
for e in errors[-20:]:
    print("  " + short(e))
for title, pat in sections:
    hits = [l for l in lines if re.search(pat, l)]
    print(f"\n== {title}: {len(hits)}")
    for l in hits[-40:]:
        print("  " + short(l))
