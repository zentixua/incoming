#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), then for each view the pall pass GPU time at forced
ladder rungs (-Dincoming.pallRung) and with shader terms switched off (-Dincoming.pallCut bits); a frame per rung after
the history settled. Usage: tools/pall_probe.py <client game dir> [rung ...]; then read "SCENARIO pall probe_*" lines."""
import os, sys

VIEWS = [(80, 10), (80, 60), (200, 60)]
CUTS = [0, 131072]

game = sys.argv[1]
rungs = [int(r) for r in sys.argv[2:]] or [2, 7, 8, 11, 13, 17]
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
        cmd("wait 100")
        cmd(f"shot probe_r{r}_up{up}_p{pitch}")
        for c in CUTS:
            cmd(f"prop incoming.pallCut {c}")
            cmd("wait 100")
            cmd(f"log probe_r{r}_up{up}_p{pitch}")
        cmd("prop incoming.pallCut 0")
cmd("prop incoming.pallRung -1")
cmd("quit")
print(n, "commands in", hold)
