#!/usr/bin/env python3
"""PROFILING ONLY (not for main). Writes hold-rig commands for a held client scenario (INCOMING_BELOW_HOLD=1):
the scene is frozen (-Dincoming.nukeFreeze, no daylight cycle), then for each view and quality (interleaved) the pall pass
GPU time per march variant (full uber-shader, mushroom compiled out, pall steps compiled out) and with shader terms switched
off (-Dincoming.pallCut bits). Usage: tools/pall_probe.py <client game dir>; then read "SCENARIO pall probe_*" lines."""
import os, sys

# mushroom terms: near-billow sun shadow, mean depths, ball light, all lighting, cap lobes, one optical depth per step
FULL = [0, 1, 262144, 524288, 262144 | 524288, 1048576, 2097152, 8388608, 2097152 | 8388608, 16777216, 131072, 0]
NOMUSH = []
NOPALL = []
VIEWS = [(80, 10), (80, 60), (200, 60)]

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
for up, pitch in VIEWS:
    cmd(f"tp {up} {pitch}")
    cmd("wait 120")
    for q in ["MEDIUM", "LOW"]:
        cmd(f"quality {q}")
        cmd("wait 100")
        cmd(f"shot probe_{q}_up{up}_p{pitch}")
        for variant, cuts in [("full", FULL), ("nomush", NOMUSH), ("nopall", NOPALL)]:
            if not cuts:
                continue
            cmd(f"prop incoming.pallVariant {variant}")
            for c in cuts:
                cmd(f"prop incoming.pallCut {c}")
                cmd("wait 100")
                cmd(f"log probe_{q}_up{up}_p{pitch}_{variant}")
        cmd("prop incoming.pallVariant full")
        cmd("prop incoming.pallCut 0")
cmd("quit")
print(n, "commands in", hold)
