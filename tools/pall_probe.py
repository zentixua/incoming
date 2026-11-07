#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), then for each view the pall pass GPU time at forced
ladder rungs (-Dincoming.pallRung), with the mushroom depth grid and with live depths (-Dincoming.pallLiveTaus); a frame
per rung and mode after the history settled. With --resolve: rung 13 only, accumulation terms switched off one by one
(-Dincoming.pallCut bits 2^25..2^28), no shots. Usage: tools/pall_probe.py <client game dir> [rung ...]; then read "SCENARIO pall probe_*" lines."""
import os, sys

VIEWS = [(80, 10), (80, 60), (200, 60)]
MODES = [("grid", "false"), ("live", "true")]

args = [a for a in sys.argv[1:] if a != "--resolve"]
resolve = "--resolve" in sys.argv
game = args[0]
rungs = [int(r) for r in args[1:]] or ([13] if resolve else [2, 8, 13, 17])
RESOLVE_CUTS = [0, 1 << 25, 1 << 26, 1 << 27, 1 << 28, (1 << 25) | (1 << 26) | (1 << 27) | (1 << 28)]
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
        if resolve:
            for c in RESOLVE_CUTS:
                cmd(f"prop incoming.pallCut {c}")
                cmd("wait 100")
                cmd(f"log probe_r{r}_rc{c}_up{up}_p{pitch}")
            cmd("prop incoming.pallCut 0")
            continue
        for name, live in MODES:
            cmd(f"prop incoming.pallLiveTaus {live}")
            cmd("wait 100")
            cmd(f"shot probe_r{r}_{name}_up{up}_p{pitch}")
            cmd(f"log probe_r{r}_{name}_up{up}_p{pitch}")
        cmd("prop incoming.pallLiveTaus false")
cmd("prop incoming.pallRung -1")
cmd("quit")
print(n, "commands in", hold)
