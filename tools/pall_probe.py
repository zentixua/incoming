#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
for each view and quality, the pall pass GPU time with each shader term switched off (-Dincoming.pallCut bits).
Usage: tools/pall_probe.py <client game dir> [quality ...]; then read the "SCENARIO pall probe_*" lines from its log."""
import os, sys

CUTS = [0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 16384, 32768, 65536, 131072,
        2048 | 1, 65536 | 16384, 1 | 2 | 4 | 32 | 4096, 0]
VIEWS = [(80, 10), (80, 60), (200, 10), (200, 60)]

game = sys.argv[1]
qualities = sys.argv[2:] or ["MEDIUM", "LOW"]
hold = os.path.join(game, "hold")
os.makedirs(hold, exist_ok=True)
n = 0
def cmd(text):
    global n
    n += 1
    with open(os.path.join(hold, f"{n:04d}.cmd"), "w") as f:
        f.write(text + "\n")
for q in qualities:
    cmd(f"quality {q}")
    for up, pitch in VIEWS:
        cmd(f"tp {up} {pitch}")
        cmd("wait 120")
        cmd(f"shot probe_{q}_up{up}_p{pitch}")
        for c in CUTS:
            cmd(f"prop incoming.pallCut {c}")
            cmd("wait 100")
            cmd(f"log probe_{q}_up{up}_p{pitch}")
        cmd("prop incoming.pallCut 0")
cmd("quit")
print(n, "commands in", hold)
