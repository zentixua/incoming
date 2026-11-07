#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), the GPU warms up, then for each view at rung 13 the pall
pass GPU time with accumulation terms (-Dincoming.pallCut bits 2^25..2^28) and march terms switched off one by one, and
frames of rung 2 with the mushroom depth grid, rung 2 with live depths (-Dincoming.pallLiveTaus) and rung 13 with the grid.
Usage: tools/pall_probe.py <client game dir>; then read "SCENARIO pall probe_*" lines."""
import os, sys

# (height above ground, pitch): inside the pall, above it looking down, toward the mushroom level and up at it
VIEWS = [(80, 60), (200, 60), (200, 0), (200, -15)]
RESOLVE_CUTS = [1 << 25, 1 << 26, 1 << 27, 1 << 28, (1 << 25) | (1 << 26) | (1 << 27) | (1 << 28)]
# near-sun billow shadow, exit at transmittance 0.05, mushroom lighting off, pall noise, pall light
MARCH_CUTS = [262144, 131072, 2097152, 1024, 128]
SHOTS = [(2, "grid", "false"), (2, "live", "true"), (13, "grid", "false")]

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
    for c in [0] + RESOLVE_CUTS + MARCH_CUTS:
        cmd(f"prop incoming.pallCut {c}")
        cmd("wait 100")
        cmd(f"log probe_r13_c{c}_up{up}_p{pitch}")
    cmd("prop incoming.pallCut 0")
    for r, name, live in SHOTS:
        cmd(f"prop incoming.pallRung {r}")
        cmd(f"prop incoming.pallLiveTaus {live}")
        cmd("wait 100")
        cmd(f"shot probe_r{r}_{name}_up{up}_p{pitch}")
        cmd(f"log probe_r{r}_{name}_up{up}_p{pitch}")
    cmd("prop incoming.pallLiveTaus false")
cmd("prop incoming.pallRung -1")
cmd("quit")
print(n, "commands in", hold)
