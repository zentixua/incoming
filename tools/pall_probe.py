#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), the GPU warms up, then for each view the pall pass GPU time at
rung 13 with the sunward-billow reuse and the new ray exit switched back (-Dincoming.pallCut bits 2^29, 2^30), and frames
of rung 2 as before them, rung 2, 13 and 17 with them.
Usage: tools/pall_probe.py <client game dir>; then read "SCENARIO pall probe_*" lines."""
import os, sys

# (height above ground, pitch): inside the pall, above it looking down, toward the mushroom level and up at it
VIEWS = [(200, 60), (1500, 0), (3000, -10)]
# 2^29: sunward billows traced at every step (no reuse along the ray); 2^30: old ray exit (0.005, rest dropped)
OLD = (1 << 29) | (1 << 30)
CUTS = [0, 1 << 29, 1 << 30, OLD]
SHOTS = [(2, "old", OLD), (2, "new", 0), (13, "new", 0), (17, "new", 0)]

game = sys.argv[1]
hold = os.path.join(game, "hold")
os.makedirs(hold, exist_ok=True)
n = 0
def cmd(text):
    global n
    n += 1
    with open(os.path.join(hold, f"{n:04d}.cmd"), "w") as f:
        f.write(text + "\n")
cmd("cmd gamerule doDaylightCycle false")
cmd("prop incoming.nukeFreeze true")
cmd("quality MEDIUM")
cmd(f"tp {VIEWS[0][0]} {VIEWS[0][1]}")
cmd("wait 900")
for up, pitch in VIEWS:
    cmd(f"tp {up} {pitch}")
    cmd("wait 120")
    cmd("prop incoming.pallRung 13")
    for c in CUTS:
        cmd(f"prop incoming.pallCut {c}")
        cmd("wait 100")
        cmd(f"log probe_r13_c{c}_up{up}_p{pitch}")
    cmd("prop incoming.pallCut 0")
    for r, name, c in SHOTS:
        cmd(f"prop incoming.pallRung {r}")
        cmd(f"prop incoming.pallCut {c}")
        cmd("wait 100")
        cmd(f"shot probe_r{r}_{name}_up{up}_p{pitch}")
        cmd(f"log probe_r{r}_{name}_up{up}_p{pitch}")
    cmd("prop incoming.pallCut 0")
cmd("prop incoming.pallRung -1")
cmd("quit")
print(n, "commands in", hold)
