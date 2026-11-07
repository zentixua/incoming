#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), the GPU warms up, then for each view the pall pass GPU time at
rung 13 with the sunward-billow reuse and the new ray exit switched back (-Dincoming.pallCut bits 2^29, 2^30), and frames
of rung 2 as before them, rung 13 and 17 with ray-pixel history weights (-Dincoming.pallTracedHistory) and clamp pulls (-Dincoming.pallClamp), and frames in
the middle of a 2°-per-tick turn (hold command pan).
Usage: tools/pall_probe.py <client game dir>; then read "SCENARIO pall probe_*" lines."""
import os, sys

# (height above ground, pitch): inside the pall, above it looking down, toward the mushroom level and up at it
VIEWS = [(1500, 0), (3000, -10)]
# 2^29: sunward billows traced at every step (no reuse along the ray); 2^30: old ray exit (0.005, rest dropped)
OLD = (1 << 29) | (1 << 30)
CUTS = [0]
# rung, name, cut, ray-pixel history weight (0.0: 0.9^n², as before), clamp pull per frame (1: outright)
SHOTS = [(2, "old", OLD, "0.0", "1"), (13, "h8hard", 0, "0.8", "1"), (13, "h8soft", 0, "0.8", "0.25"),
         (13, "h9soft", 0, "0.9", "0.25"), (17, "h9soft", 0, "0.9", "0.25")]
# rung, name, history weight, clamp pull: frames while the view turns 2 degrees per tick
PANS = [(2, "old", "0.0", "1"), (13, "h8soft", "0.8", "0.25"), (13, "h9soft", "0.9", "0.25"), (17, "h9soft", "0.9", "0.25")]

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
    for r, name, c, h, k in SHOTS:
        cmd(f"prop incoming.pallRung {r}")
        cmd(f"prop incoming.pallCut {c}")
        cmd(f"prop incoming.pallTracedHistory {h}")
        cmd(f"prop incoming.pallClamp {k}")
        cmd("wait 160")
        cmd(f"shot probe_r{r}_{name}_up{up}_p{pitch}")
        cmd(f"log probe_r{r}_{name}_up{up}_p{pitch}")
    cmd("prop incoming.pallCut 0")
    for r, name, h, k in PANS:
        cmd(f"prop incoming.pallRung {r}")
        cmd(f"prop incoming.pallTracedHistory {h}")
        cmd(f"prop incoming.pallClamp {k}")
        cmd(f"tp {up} {pitch}")
        cmd("wait 160")
        cmd(f"pan 2 40 probe_pan_r{r}_{name}_up{up}_p{pitch}")
    cmd("prop incoming.pallTracedHistory 0.8")
cmd("prop incoming.pallRung -1")
cmd("quit")
print(n, "commands in", hold)
