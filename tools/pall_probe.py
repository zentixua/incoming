#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), then for each view the pall pass GPU time at forced
ladder rungs (-Dincoming.pallRung), with the mushroom depth grid and with live depths (-Dincoming.pallLiveTaus); a frame
per rung and mode after the history settled. Usage: tools/pall_probe.py <client game dir> [rung ...]; then read "SCENARIO pall probe_*" lines."""
import os, sys

VIEWS = [(80, 10), (80, 60), (200, 60)]
MODES = [("grid", "false"), ("live", "true")]

game = sys.argv[1]
rungs = [int(r) for r in sys.argv[2:]] or [2, 8, 13, 17]
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
for up, pitch in VIEWS:
    cmd(f"tp {up} {pitch}")
    cmd("wait 120")
    for r in rungs:
        cmd(f"prop incoming.pallRung {r}")
        for name, live in MODES:
            cmd(f"prop incoming.pallLiveTaus {live}")
            cmd("wait 100")
            cmd(f"shot probe_r{r}_{name}_up{up}_p{pitch}")
            cmd(f"log probe_r{r}_{name}_up{up}_p{pitch}")
        cmd("prop incoming.pallLiveTaus false")
cmd("prop incoming.pallRung -1")
cmd("quit")
print(n, "commands in", hold)
